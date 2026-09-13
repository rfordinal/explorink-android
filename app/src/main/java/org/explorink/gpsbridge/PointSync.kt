package org.explorink.gpsbridge

import android.util.Log

/**
 * Answers the device's `NEED_POINTS` by pushing the point shards it is
 * missing, and tells it about the ones the CDN no longer has.
 *
 * The whole conversation, in one place (`docs/point-layer-lifecycle.md`,
 * decision 2 in the xteink repo; firmware: `MapCommandParser.h`,
 * `TileSyncActivity::askAboutPoints()`, `MapActivity::maybeSyncPointsLive()`):
 *
 *  1. Device sends `NEED_POINTS <count> fmt <version>` unprompted on the
 *     command channel.
 *  2. This asks for the list with `points` -- never paged, at most 9 shards
 *     (`MapPointShards::rangeForRadius`'s 3x3 worst case), unlike tiles'
 *     `missing`.
 *  3. Shards the device already `have` are left alone; the `absent` ones are
 *     read off the CDN and pushed over the same transfer channel tiles use
 *     (begin, wait for `RDY`, chunks, `OK`) -- [TransferFrames] again, a
 *     different relative path.
 *  4. A shard this phone cannot supply becomes
 *     `skip 10 <col> <row> <reason>` -- the tile grammar's `skip`, unchanged,
 *     with `10` (`MapPointShards::kShardZoom`) standing in for a tile zoom.
 *     No new word: z10 never means a tile, and only one conversation is ever
 *     open on the command channel, so the two cannot collide
 *     (`MapCommandParser.h`, "A shard the phone cannot supply reuses
 *     `skip`").
 *  5. A shard the CDN answers with a *definite* 404 for, twice in two
 *     separate sync sessions, becomes `gone <col> <row>` -- never on a
 *     timeout, a 5xx or no signal, and never on the first 404: a transient
 *     CDN hiccup must not delete a shard the rider will want back the next
 *     time they are near it (decision 3, "What would prove it wrong").
 *
 * **No BLE and no Android in here except the log tag and [NotFoundTracker]'s
 * default.** Everything that touches hardware is behind [Transport];
 * everything that touches time is behind [Scheduler]; everything that
 * survives across sync sessions is behind [NotFoundTracker]. Same reasons
 * [TileFetcher] gives, and for the same payoff: this runs unattended, against
 * a device and a CDN that can both misbehave mid-sequence.
 *
 * **Deliberately simpler than [TileFetcher] in two ways, both because points
 * are not tiles:**
 *
 *  - No prefetch pipeline. [TileFetcher]'s overlaps a tile's CDN read with the
 *    previous tile's BLE transfer because a tile is tens to hundreds of kB and
 *    the two links were sitting idle for 0.3-1.5 s each. A point shard is a
 *    few kB in the common case (`docs/point-file-spec.md`'s measured sizes)
 *    and there are at most 9 of them, so the same complexity here would buy
 *    a fraction of a second against a real maintenance cost. Fetch-then-push,
 *    one shard at a time.
 *  - No paging state. [MissingList.PageReader] exists because `missing` can
 *    be 200 entries; `points` never is (see point 2 above), so [PointList.ListReader]
 *    is shaped like [MissingList.ViewportReader] instead -- one reply, no
 *    `nextOffset`.
 *
 * Single-threaded by contract, same as [TileFetcher]: every method and every
 * callback runs on the caller's one thread. No locks, and none needed.
 */
class PointSync(
    private val source: PointSource,
    private val transport: Transport,
    private val scheduler: Scheduler,
    private val listener: Listener,
    private val notFound: NotFoundTracker = NotFoundTracker.None,
) {

    companion object {
        private const val TAG = "PointSync"

        /** Same generosity as [TileFetcher]'s: an SD-card reply over BLE. */
        const val REPLY_TIMEOUT_MS = 15_000L

        /**
         * `MapPointShards::kShardZoom`. Points have exactly one shard zoom, so
         * this is a constant here rather than a field anywhere in this file.
         */
        const val SHARD_ZOOM = 10

        const val SKIP_NO_SOURCE = "nosource"
        const val SKIP_REFUSED = "refused"

        /**
         * Reason prefix sent with `skip` when the shard exists but is built to
         * a format version this device cannot read. The found version is
         * appended (`"$SKIP_WRONG_FORMAT<version>"`), same convention
         * [TileFetcher] uses for tiles.
         */
        const val SKIP_WRONG_FORMAT = "fmt"
    }

    /** One shard's outcome reading it off the CDN. */
    sealed class ReadResult {
        data class Bytes(val bytes: ByteArray) : ReadResult()

        /** A *definite* 404 -- the CDN was asked and said this shard does not exist. */
        object NotFound : ReadResult()

        /** Anything else that stopped the read: a timeout, a 5xx, no signal. Never `gone`. */
        object Failed : ReadResult()
    }

    interface PointSource {
        fun read(col: Long, row: Long, done: (ReadResult) -> Unit)
    }

    /**
     * Remembers a 404 across sync sessions, so `gone` is sent only after the
     * second one (decision 3). A real implementation persists this (a small
     * SharedPreferences map, keyed `"$col/$row"`, is enough -- a shard that
     * never repeats a miss never grows an entry it has to prune). This class
     * has no persistence of its own, same reason [Transport] and [Scheduler]
     * do not either.
     */
    interface NotFoundTracker {
        /** Records a 404 for this shard now. True exactly the second time running. */
        fun recordNotFound(col: Long, row: Long): Boolean

        /** A read that came back with bytes -- any streak this shard had is over. */
        fun recordFound(col: Long, row: Long)

        companion object {
            /**
             * No memory at all: every 404 reads as the first. **Tests only.** A
             * real build must persist this across sessions, or the two-404 rule
             * above is not actually enforced -- see the interface doc.
             */
            val None: NotFoundTracker = object : NotFoundTracker {
                override fun recordNotFound(col: Long, row: Long) = false
                override fun recordFound(col: Long, row: Long) = Unit
            }
        }
    }

    /** Everything that touches BLE. Same shape as [TileFetcher.Transport]. */
    interface Transport {
        fun sendCommand(line: String, done: (Boolean, String?) -> Unit)
        fun sendFrame(frame: ByteArray, done: (Boolean, String?) -> Unit)
        fun maxChunkPayload(): Int
        fun setFastLink(fast: Boolean)
    }

    /** Delayed work, injectable so tests do not wait for real seconds. */
    interface Scheduler {
        fun postDelayed(delayMs: Long, action: () -> Unit): Cancellable

        interface Cancellable {
            fun cancel()
        }
    }

    interface Listener {
        /** A sync started; [total] shards need pushing (the `absent` ones only -- see [PointList.ListReader]). */
        fun onSyncStarted(total: Int)

        /** Progress changed: [pushed] landed, [skipped] given up on, [gone] deleted, of [total]. */
        fun onSyncProgress(pushed: Int, skipped: Int, gone: Int, total: Int)

        /** The sync ended. [reason] is short and human-readable. */
        fun onSyncFinished(pushed: Int, skipped: Int, gone: Int, total: Int, reason: String)

        /**
         * One shard is finished, one way or the other. [ok] true means the
         * device has it on the card; false covers both a skip and a `gone`,
         * distinguished by [detail] ("gone" for the latter).
         *
         * Default no-op: only the UI needs it.
         */
        fun onShardDone(col: Long, row: Long, bytes: Int, ok: Boolean, detail: String) {}

        /** [sentBytes] of [totalBytes] of one shard are on the card. Default no-op. */
        fun onShardProgress(col: Long, row: Long, sentBytes: Int, totalBytes: Int) {}
    }

    enum class Phase { IDLE, LISTING, PUSHING }

    var phase: Phase = Phase.IDLE
        private set

    private var list: PointList.ListReader? = null
    private val queue = ArrayDeque<PointList.ShardStatus>()
    private var total = 0
    private var pushed = 0
    private var skipped = 0
    private var gone = 0

    /** The `.tip` format version the device said it reads, from `NEED_POINTS`. */
    private var wantedFormat: Int? = null

    // The transfer in flight, if any.
    private var shard: PointList.ShardStatus? = null
    private var bytes: ByteArray? = null
    private var offset = 0
    private var awaitingReady = false

    /**
     * Which transfer the status channel is talking about, and what a dead one
     * still owes. Identical reasoning to [TileFetcher.statusGen] /
     * [TileFetcher.liveStatusGen] / [TileFetcher.owedVerdicts]: the status
     * line carries no identity of its own, and the hazard (a dead transfer's
     * verdict landing after the next begin) is the same channel, the same
     * shape, whatever path the file came from.
     */
    private var statusGen = 0
    private var liveStatusGen = 0
    private var owedVerdicts = 0

    /**
     * Which sync this is -- bumped at every point one starts or ends. Same
     * role as [TileFetcher.fetchGen]: an async CDN read captures this before
     * handing off, and its callback checks it against the live value before
     * touching any state.
     */
    private var syncGen = 0

    /**
     * A whole stale `points` reply still owed by a listing this side walked
     * away from. Narrower than [TileFetcher.owedListingReplies] on purpose: a
     * second `NEED_POINTS` mid-listing is far rarer here than a second
     * `NEED_TILES` is for tiles (autosync's 60 s retry loop has no points
     * equivalent -- both send sites ask at most once per visit or once per
     * power cycle), but the channel-ordering guarantee that makes the
     * counter exact for tiles holds here unchanged, so the same shape is kept
     * rather than dropped for a plain flag.
     */
    private var owedListingReplies = 0

    private var timeout: Scheduler.Cancellable? = null

    // --- input from the link ------------------------------------------------

    /** One line off the command characteristic. */
    fun onCommandLine(line: String) {
        val need = PointList.parseNeedPoints(line)
        if (need != null) {
            startListing(need)
            return
        }
        if (phase == Phase.LISTING) feedList(line)
    }

    /** One line off the transfer status characteristic. */
    fun onStatusLine(line: String) {
        if (phase != Phase.PUSHING) return
        val status = TransferFrames.parseStatus(line)
        val verdict = status is TransferFrames.Status.Ok || status is TransferFrames.Status.Err

        if (statusGen != liveStatusGen) {
            Log.i(TAG, "ignoring '$line': gen $liveStatusGen was aborted, gen is now $statusGen")
            if (verdict && owedVerdicts > 0) owedVerdicts--
            return
        }
        if (verdict && owedVerdicts > 0) {
            owedVerdicts--
            Log.i(TAG, "ignoring '$line': an aborted transfer still owed a verdict")
            return
        }
        if (verdict && shard == null) {
            Log.w(TAG, "ignoring '$line': no transfer in flight")
            return
        }

        when (status) {
            is TransferFrames.Status.Ready -> {
                if (!awaitingReady) return
                owedVerdicts = 0
                awaitingReady = false
                armTimeout()
                sendNextChunk()
            }

            is TransferFrames.Status.Ok -> {
                pushed++
                Log.i(TAG, "landed ${describe(shard)} (${status.bytes} bytes)")
                shard?.let { listener.onShardDone(it.col, it.row, status.bytes, true, "") }
                clearTransfer()
                listener.onSyncProgress(pushed, skipped, gone, total)
                nextShard()
            }

            is TransferFrames.Status.Err -> {
                Log.w(TAG, "device refused ${describe(shard)}: ${status.reason}")
                val failed = shard
                clearTransfer()
                if (failed != null) skip(failed, SKIP_REFUSED) else nextShard()
            }

            is TransferFrames.Status.Unknown -> Log.w(TAG, "unknown status: ${status.line}")
        }
    }

    /** The link dropped. Whatever was in flight is dead. */
    fun onDisconnected() {
        if (phase == Phase.IDLE) return
        owedListingReplies = 0
        finish("link lost")
    }

    /** Stop from this side (the service is shutting down, the user stopped it). */
    fun stop() {
        if (phase == Phase.IDLE) return
        abortInFlight()
        owedListingReplies = 0
        finish("stopped")
    }

    // --- listing --------------------------------------------------------

    private fun startListing(need: PointList.NeedPoints) {
        if (phase != Phase.IDLE) {
            Log.i(TAG, "restarting points sync on a second NEED_POINTS")
            if (list?.complete == false) owedListingReplies++
            abortInFlight()
            reset()
        }
        Log.i(TAG, "device wants ${need.count} point shard(s), format ${need.formatVersion ?: "unstated"}")
        syncGen++
        phase = Phase.LISTING
        wantedFormat = need.formatVersion
        transport.setFastLink(true)
        list = PointList.ListReader()
        val gen = syncGen
        armTimeout()
        transport.sendCommand("points") { ok, error ->
            if (gen != syncGen) {
                if (!ok) {
                    owedListingReplies--
                    Log.i(TAG, "a late 'points' write failed for gen $gen; retracting its owed reply")
                } else {
                    Log.i(TAG, "dropping a late 'points' result for gen $gen; sync has moved on")
                }
                return@sendCommand
            }
            if (!ok) finish("could not ask for the list: ${error ?: "write failed"}")
        }
    }

    private fun feedList(line: String) {
        if (owedListingReplies > 0) {
            if (line.trim() == "OK") owedListingReplies--
            return
        }
        val reader = list ?: return
        if (!reader.feed(line)) return
        if (reader.unavailable) {
            // The device still sends a terminating `OK` after `INFO
            // points=unavailable` (MapCommandConsole.cpp's Points case
            // replies it unconditionally) -- own up to owing it before
            // going IDLE, or it lands in whatever conversation runs next
            // and is read as that one's own terminator. Same fault class as
            // 2026-08-11's NEED_TILES/CHECK_TILES collision, found in code
            // review 2026-09-13 before it ever reached a device.
            owedListingReplies++
            finish("device has no point-shard source wired")
            return
        }
        if (reader.noPosition) {
            owedListingReplies++
            finish("device has no fix to centre a shard range on")
            return
        }
        if (!reader.complete) return
        if (reader.truncated) {
            // A dropped indication, not a device that wants nothing --
            // treated as a failure of the whole listing, same as
            // TileFetcher.feedPage()'s reconciliation against
            // missing_total. No owed OK here: `complete` is only true once
            // the `OK` itself has already been consumed.
            Log.w(TAG, "points listing truncated: ${reader.shards.size} of ${reader.total} line(s) arrived")
            finish("listing truncated on the link")
            return
        }

        cancelTimeout()
        val have = reader.shards.count { it.have }
        // distinctBy guards against a malformed reply naming the same shard
        // twice -- not a case the firmware produces (its range loop visits
        // each col/row once), but two reads of one 404 would otherwise look
        // like the two separate sync sessions decision 3's gone rule
        // actually requires (code review, 2026-09-13, low impact but a real
        // spec violation).
        queue.addAll(reader.shards.filterNot { it.have }.distinctBy { it.col to it.row })
        total = queue.size
        Log.i(TAG, "list complete: ${reader.shards.size} shard(s) in range, $have already held, ${queue.size} to push")
        phase = Phase.PUSHING
        listener.onSyncStarted(total)
        nextShard()
    }

    // --- pushing --------------------------------------------------------

    private fun nextShard() {
        val next = queue.removeFirstOrNull()
        if (next == null) {
            finish("done")
            return
        }

        val relPath = TransferFrames.pointShardRelPath(SHARD_ZOOM, next.col, next.row)
        if (!TransferFrames.isSafeRelPath(relPath)) {
            Log.w(TAG, "refusing to push an unsafe path: $relPath")
            skip(next, SKIP_REFUSED)
            return
        }

        val gen = syncGen
        source.read(next.col, next.row) { result -> onShardRead(next, relPath, result, gen) }
    }

    private fun onShardRead(next: PointList.ShardStatus, relPath: String, result: ReadResult, gen: Int) {
        if (gen != syncGen) {
            Log.i(TAG, "dropping a late read for ${describe(next)}; its sync has moved on")
            return
        }
        if (phase != Phase.PUSHING) {
            Log.i(TAG, "dropping a late read for ${describe(next)}; the sync has ended")
            return
        }

        when (result) {
            is ReadResult.Failed -> skip(next, SKIP_NO_SOURCE)

            is ReadResult.NotFound -> {
                if (notFound.recordNotFound(next.col, next.row)) {
                    sendGone(next)
                } else {
                    Log.i(TAG, "first 404 for ${describe(next)}; not sending gone yet")
                    skip(next, SKIP_NO_SOURCE)
                }
            }

            is ReadResult.Bytes -> {
                val found = pointShardFormatVersion(result.bytes)
                if (found == null) {
                    // Bad magic or truncated -- refused regardless of
                    // wantedFormat, same as TileHeader.isAcceptable() does
                    // for tiles, and for the same reason: a captive portal or
                    // a transparent proxy can answer 200 with an HTML body,
                    // and against a firmware build that sends `NEED_POINTS`
                    // with no `fmt` the old check (wantedFormat != null &&
                    // found != wantedFormat) let that body through whole --
                    // it would pass its own CRC (computed over what was
                    // sent, not over a valid file), land on the card, and
                    // answer `have` forever after (code review, 2026-09-13).
                    Log.w(TAG, "$relPath is not a valid point shard (bad magic or truncated)")
                    skip(next, "${SKIP_WRONG_FORMAT}0")
                    return
                }
                if (wantedFormat != null && found != wantedFormat) {
                    Log.w(TAG, "$relPath is format $found, device reads $wantedFormat")
                    skip(next, "$SKIP_WRONG_FORMAT$found")
                    return
                }
                notFound.recordFound(next.col, next.row)
                beginTransfer(next, relPath, result.bytes)
            }
        }
    }

    private fun beginTransfer(next: PointList.ShardStatus, relPath: String, data: ByteArray) {
        shard = next
        bytes = data
        offset = 0
        awaitingReady = true
        liveStatusGen = ++statusGen
        armTimeout()
        transport.setFastLink(true)

        val crc = TransferFrames.crc32(data)
        val frame = TransferFrames.beginFrame(relPath, data.size, crc)
        transport.sendFrame(frame) { ok, error ->
            if (!ok) {
                if (shard !== next) {
                    Log.i(TAG, "dropping a late begin failure for $relPath: $error")
                    return@sendFrame
                }
                Log.w(TAG, "begin write failed for $relPath: $error")
                clearTransfer()
                skip(next, SKIP_REFUSED)
            }
        }
    }

    private fun sendNextChunk() {
        val data = bytes ?: return
        val current = shard ?: return
        if (offset >= data.size) return  // waiting for OK

        val remaining = data.size - offset
        val take = minOf(remaining, transport.maxChunkPayload())
        val payload = data.copyOfRange(offset, offset + take)
        val frame = TransferFrames.chunkFrame(offset, payload)
        val chunkOffset = offset

        transport.sendFrame(frame) { ok, error ->
            if (shard !== current) return@sendFrame
            if (!ok) {
                Log.w(TAG, "chunk at $chunkOffset failed: $error")
                abortInFlight()
                clearTransfer()
                skip(current, SKIP_REFUSED)
                return@sendFrame
            }
            offset = chunkOffset + take
            armTimeout()
            listener.onShardProgress(current.col, current.row, offset, data.size)
            if (offset < data.size) sendNextChunk()
        }
    }

    // Named `target`, not `shard`: the latter shadows the [shard] field
    // above, and every call site here already has it null or clears it via
    // clearTransfer() first -- but a future caller that skipped that step
    // would silently keep going against the shadowed parameter with no
    // compiler complaint (code review, 2026-09-13; TileFetcher.skip() avoids
    // the same trap by naming its parameter `missing`).
    private fun skip(target: PointList.ShardStatus, reason: String) {
        skipped++
        listener.onShardDone(target.col, target.row, 0, false, reason)
        listener.onSyncProgress(pushed, skipped, gone, total)
        val gen = syncGen
        transport.sendCommand("skip $SHARD_ZOOM ${target.col} ${target.row} $reason") { ok, error ->
            if (gen != syncGen) return@sendCommand
            if (!ok) Log.w(TAG, "skip write failed: $error")
        }
        nextShard()
    }

    private fun sendGone(target: PointList.ShardStatus) {
        gone++
        Log.i(TAG, "second 404 for ${describe(target)}; telling the device it is gone")
        listener.onShardDone(target.col, target.row, 0, false, "gone")
        listener.onSyncProgress(pushed, skipped, gone, total)
        val gen = syncGen
        transport.sendCommand("gone ${target.col} ${target.row}") { ok, error ->
            if (gen != syncGen) return@sendCommand
            if (!ok) Log.w(TAG, "gone write failed: $error")
        }
        nextShard()
    }

    private fun abortInFlight() {
        if (shard == null) return
        statusGen++
        owedVerdicts++
        transport.sendFrame(TransferFrames.abortFrame()) { ok, error ->
            if (!ok) Log.w(TAG, "abort write failed: $error")
        }
    }

    private fun clearTransfer() {
        shard = null
        bytes = null
        offset = 0
        awaitingReady = false
        cancelTimeout()
    }

    // --- timeouts and teardown -------------------------------------------

    private fun armTimeout() {
        cancelTimeout()
        timeout = scheduler.postDelayed(REPLY_TIMEOUT_MS) {
            timeout = null
            Log.w(TAG, "timed out in $phase, ${describe(shard)}")
            when (phase) {
                Phase.LISTING -> finish("device stopped answering")
                Phase.PUSHING -> {
                    val stalled = shard
                    abortInFlight()
                    clearTransfer()
                    if (stalled != null) skip(stalled, SKIP_REFUSED) else nextShard()
                }
                Phase.IDLE -> Unit
            }
        }
    }

    private fun cancelTimeout() {
        timeout?.cancel()
        timeout = null
    }

    private fun finish(reason: String) {
        syncGen++
        transport.setFastLink(false)
        val wasPushed = pushed
        val wasSkipped = skipped
        val wasGone = gone
        val wasTotal = total
        reset()
        Log.i(TAG, "points sync finished: $reason ($wasPushed pushed, $wasSkipped skipped, $wasGone gone of $wasTotal)")
        listener.onSyncFinished(wasPushed, wasSkipped, wasGone, wasTotal, reason)
    }

    private fun reset() {
        cancelTimeout()
        phase = Phase.IDLE
        list = null
        queue.clear()
        total = 0
        pushed = 0
        skipped = 0
        gone = 0
        wantedFormat = null
        shard = null
        bytes = null
        offset = 0
        awaitingReady = false
        // A sync that ended right after an abort would otherwise leave a
        // debt behind for the next one to pay with its first shard's real
        // verdict (same reasoning as TileFetcher.reset(), which this line
        // mirrors).
        owedVerdicts = 0
        liveStatusGen = statusGen
    }

    private fun describe(s: PointList.ShardStatus?): String = if (s == null) "no shard" else "${s.col}/${s.row}"

    /**
     * `version` u16 LE at offset 4 (`point-file-spec.md`, "Layout";
     * `MapPointReader::kFormatVersion`'s slot). Null if too short to have one
     * at all -- a truncated read, never a legitimate shard.
     */
    private fun pointShardFormatVersion(data: ByteArray): Int? {
        if (data.size < 6) return null
        // "TIP1" magic at offset 0 (point-file-spec.md, "Layout"). Checked
        // deliberately, the same way TileHeader.formatVersion() refuses a
        // bad magic for tiles: without it, any 200 response with a body
        // (an HTML error page, a captive portal) reads a version out of
        // whatever bytes happen to sit at offset 4-5 and is treated as a
        // real shard (code review, 2026-09-13).
        if (data[0] != 'T'.code.toByte() || data[1] != 'I'.code.toByte() ||
            data[2] != 'P'.code.toByte() || data[3] != '1'.code.toByte()
        ) {
            return null
        }
        return (data[4].toInt() and 0xff) or ((data[5].toInt() and 0xff) shl 8)
    }
}
