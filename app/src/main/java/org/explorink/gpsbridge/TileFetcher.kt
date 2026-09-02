package org.explorink.gpsbridge

import android.util.Log

/**
 * Answers the device's `NEED_TILES` by pushing the tiles it is missing.
 *
 * The whole conversation, in one place:
 *
 *  1. Device sends `NEED_TILES <count>` unprompted on the command channel.
 *  2. This asks for the list with `missing`, paging until `missing_next=done`.
 *  3. For each tile, in the order the device gave them (already fetch priority
 *     -- see [MissingTile]), it reads the bytes from the [TileSource] and pushes
 *     them over the transfer channel: begin, wait for `RDY`, chunks, `OK`. The
 *     next tile's read starts at this tile's `RDY`, so the CDN fetch overlaps
 *     the BLE transfer instead of following it ([prefetched]).
 *  4. A tile the source does not have becomes `skip <z> <col> <row> <reason>`,
 *     so the device's progress screen counts it as failed instead of waiting
 *     for a file that is never coming.
 *  5. `FETCH_CANCEL` from the device (the rider pressed Back) stops everything
 *     and aborts whatever is in flight.
 *
 * **No BLE and no Android in here except the log tag.** Everything that touches
 * hardware is behind [Transport]; everything that touches time is behind
 * [Scheduler]. That is what makes the state machine testable, which matters
 * more here than anywhere else in the app: this code runs unattended, mid-ride,
 * against a device that can vanish at any point in the sequence.
 *
 * Single-threaded by contract: every method and every callback runs on the
 * caller's one thread (the service's main looper in the app, the test thread in
 * tests). No locks, and none needed.
 */
class TileFetcher(
    private val source: TileSource,
    private val transport: Transport,
    private val scheduler: Scheduler,
    private val listener: Listener,
    /**
     * What the freshness check learned the CDN's current content id is, per
     * tile. Consulted for every fetch, empty by default.
     *
     * It is what turns a stale-tile fetch into an actual replacement: the CDN
     * caches a tile path for seven days with no purge, so a rebuilt tile has to
     * be asked for as `?crc=<content_id>` or the edge hands back the copy being
     * replaced ([TileSource.read]).
     */
    private val expected: ExpectedContentIds = ExpectedContentIds(),
) {

    companion object {
        private const val TAG = "TileFetcher"

        /**
         * How long a `missing` page or a transfer verdict may take before the
         * fetch gives up. Generous: the device answers from an SD card, and a
         * page is 20 BLE indications each waiting for its own confirm.
         */
        const val REPLY_TIMEOUT_MS = 15_000L

        /** Reason word sent with `skip` when no source has the tile. */
        const val SKIP_NO_SOURCE = "nosource"

        /** Reason word sent with `skip` when the device refused or failed the push. */
        const val SKIP_REFUSED = "refused"

        /**
         * Reason word sent with `skip` when the tile exists but is built to a
         * format version this device cannot read. Distinct from a plain miss on
         * purpose: "no tile here" and "wrong tile here" want different fixes --
         * build the area, versus rebuild what is already built.
         */
        const val SKIP_WRONG_FORMAT = "fmt"
    }

    /** Everything that touches BLE. Each call completes exactly once. */
    interface Transport {
        /** Writes one ASCII line to the command characteristic. */
        fun sendCommand(line: String, done: (Boolean, String?) -> Unit)

        /** Writes one binary frame to the transfer characteristic. */
        fun sendFrame(frame: ByteArray, done: (Boolean, String?) -> Unit)

        /** Payload bytes per chunk on the current link -- MTU dependent. */
        fun maxChunkPayload(): Int

        /**
         * Ask the link for a fast connection interval, or give it back.
         *
         * Held only while a fetch is running. The interval is the transfer's
         * real ceiling -- a chunk is write-with-response, so it costs one
         * interval each way -- but a fast interval also keeps the radio busy
         * continuously, which is battery spent for nothing once the tiles have
         * landed.
         */
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
        /** A fetch started; [total] tiles were asked for. */
        fun onFetchStarted(total: Int)

        /** Progress changed: [sent] landed, [skipped] given up on, of [total]. */
        fun onFetchProgress(sent: Int, skipped: Int, total: Int)

        /** The fetch ended. [reason] is short and human-readable. */
        fun onFetchFinished(sent: Int, skipped: Int, total: Int, reason: String)

        /**
         * One square is finished, one way or the other: [ok] true means the device
         * has it on the card and verified the CRC, false means it was skipped or
         * refused. [bytes] is what landed, 0 for anything that did not.
         * [detail] is the skip reason, empty on success.
         *
         * Aggregate counts alone could not answer the question a rider actually
         * asks -- which squares did I get, which did I not, and why -- so this
         * carries the identity of each one. Default no-op: only the UI needs it.
         */
        fun onTileDone(z: Int, col: Long, row: Long, bytes: Int, ok: Boolean, detail: String) {}

        /** True while the current fetch is answering a viewport ask, not the whole list. */
        fun onFetchScope(viewportOnly: Boolean) {}

        /**
         * [sentBytes] of [totalBytes] of one square are on the card. Fired per
         * chunk, i.e. per write the device has already acknowledged, so it is a
         * measure of what landed rather than of what was queued.
         *
         * The only honest source of a progress bar on this link: a tile is tens of
         * kB at ~7 kB/s, so "sending" with no number reads as a hang. Default
         * no-op.
         */
        fun onTileProgress(z: Int, col: Long, row: Long, sentBytes: Int, totalBytes: Int) {}

        /**
         * The begin frame for one square is going out: [bytes] long, [crc32]
         * over exactly those bytes.
         *
         * Why the CRC leaves this class at all. A ledger that records "sent"
         * from the device's `OK <bytes> <crc32hex>` alone is recording a claim
         * it cannot check -- and the queue cannot derive the number for itself,
         * because the index's `content_id` is a hash of the tile's *layer* CRCs
         * (`mapbuilder/tilegen/tiles.py`, `content_id_from_layer_crcs()`), not
         * of the file. So only the sender knows what went out, only the device
         * knows what landed, and **a receipt nobody compared against what was
         * sent is not a receipt** ([TileOutbox.beginSend], [TileOutbox.confirm]).
         *
         * Default no-op: only a caller keeping a ledger needs it.
         */
        fun onTileSending(z: Int, col: Long, row: Long, bytes: Int, crc32: Long) {}

        /**
         * The device's own verdict for one square, whole: it counted [bytes] and
         * computed [crc32] by reading the file back off the card
         * (`docs/ble-map-transfer-brief.md`).
         *
         * Fired alongside [onTileDone], not instead of it. That one answers
         * "which squares did I get" for a screen; this one carries the two
         * numbers a receipt is made of, and dropping either is what turns a
         * ledger into a guess -- see [onTileSending].
         *
         * Default no-op.
         */
        fun onTileReceipt(z: Int, col: Long, row: Long, bytes: Int, crc32: Long) {}
    }

    enum class Phase { IDLE, LISTING, PUSHING }

    var phase: Phase = Phase.IDLE
        private set

    private var page: MissingList.Listing? = null

    /**
     * True while answering a `view` ask: the device wants the tiles on its
     * screen right now, read with `tiles`, not its whole hatched history read
     * with `missing`. Set per fetch from [MissingList.NeedTiles.viewportOnly].
     */
    private var viewportOnly = false
    private val queue = ArrayDeque<MissingTile>()
    private var total = 0
    private var sent = 0
    private var skipped = 0

    /**
     * The `.tib` format version the device said it reads, from `NEED_TILES`.
     * Null when it did not say -- an older firmware build.
     */
    private var wantedFormat: Int? = null

    // The transfer in flight, if any.
    private var tile: MissingTile? = null
    private var bytes: ByteArray? = null
    private var offset = 0
    private var awaitingReady = false

    /**
     * Which transfer the status channel is talking about.
     *
     * A status line is `RDY`/`OK`/`ERR` and nothing else -- no path, no tile,
     * no sequence number (`docs/ble-map-transfer-protocol.md`, "Status lines").
     * So identity has to come from what this side sent last. [statusGen] counts
     * transfer generations: a begin frame starts one, an abort ends one.
     * [liveStatusGen] is the generation whose lines may touch state, so
     * `statusGen != liveStatusGen` means the last thing this side did to the
     * channel was kill a transfer -- nothing arriving in that window belongs to
     * anything live.
     *
     * An int and not a flag on purpose: two aborts can be outstanding at once
     * (abort A, begin B, B stalls, abort B) and a flag cannot count that.
     *
     * The alternative -- hold the next begin until the aborted transfer's
     * `ERR aborted` has been seen -- was rejected deliberately: it serializes
     * the whole fetch on a dead tile's 15 s timeout, and every remaining tile
     * pays for it. Skipping generations costs nothing.
     */
    private var statusGen = 0
    private var liveStatusGen = 0

    /**
     * Verdicts (`OK`/`ERR`) still owed by transfers this side walked away from.
     *
     * The generation window above closes the moment the next begin goes out,
     * and a dead transfer's verdict can arrive after that -- so the window
     * alone would still credit it to the new tile, which is the whole defect
     * (`docs/ble-review-2026-08.md`, "Stability -- app", item 1). Each abort of
     * a live transfer earns exactly one dead verdict, so counting them and
     * consuming them is exact: the device sends `ERR aborted` when a transfer
     * is active (`MapTransferReceiver.cpp:119-124`, `:330-352`), and when it is
     * not -- because it already sent an `OK` that is still in flight -- the
     * abort is silent and that `OK` is the dead verdict instead.
     *
     * An accepted `RDY` clears the debt: the device emits status lines strictly
     * in order and blocks the host task until each goes out
     * (`BlePositionServer.cpp:593-617`), so a dead verdict always arrives
     * before the next begin's `RDY` -- and that same send gives up after 8
     * attempts, so a debt that never arrives must not eat a real verdict.
     */
    private var owedVerdicts = 0

    /**
     * Which fetch this is -- bumped once at every point a fetch starts or ends
     * ([pushTiles], [startListing], [finish]). Async work kicked off mid-fetch
     * (today: the CDN read in [nextTile] and the read-ahead in [maybePrefetch])
     * captures this value before handing off, and its callback checks it
     * against the live one before touching any state. A CDN read is 10-20 s of HTTP timeouts, so a fetch can end and a
     * *new* one start on the same channel before the old read lands; the
     * phase-only guard in [onTileBytes] cannot tell those two fetches apart --
     * both show `phase == PUSHING` -- so the late read's begin frame would
     * interleave into the new fetch's transfer
     * (`docs/ble-review-2026-08.md`, "Stability -- app", item 2).
     *
     * Every command-write callback ([requestPage], [skip]) captures it the
     * same way, for the same reason one level up the stack: a second
     * `NEED_TILES` restarts the whole conversation (bumping [fetchGen],
     * replacing [page]) before an outstanding write's callback returns, and
     * that callback has no idea it is answering a run this side already
     * walked away from -- `finish()` takes no argument saying which run it
     * means, so unguarded it tears down whatever fetch is live now
     * (`docs/ble-review-2026-08.md`, "Stability -- app", item 3).
     *
     * Distinct from [statusGen]/[liveStatusGen] above, and not to be merged
     * with them: those count *transfer* (begin/abort) generations for
     * status-line attribution, bumped once per tile. This one counts *fetch*
     * (one whole listing-then-push conversation) generations, bumped only at
     * the three points a fetch starts or ends -- many transfers happen inside
     * one unchanged [fetchGen]. They answer different questions and change at
     * different rates.
     */
    private var fetchGen = 0

    /**
     * Stale `missing`/`tiles` replies still owed by requests this side walked
     * away from at a restart.
     *
     * The generation guard on [requestPage]'s write callback stops a stale
     * write's *failure* from reaching into whatever fetch is live now, but it
     * cannot stop a stale write's *success*: once the device has the request,
     * firmware answers it whole and uninterrupted -- verified by reading
     * `BlePositionServer::sendCommandBlock()` (blocking, one call per line)
     * and `MapBleConsole`'s reply generation (one uninterrupted call per
     * command, `kMaxBlocksPerPoll` only defers *further* commands, never a
     * reply already triggered) -- so nothing else, including the very
     * `NEED_TILES` that triggers a restart, can reach the wire ahead of a
     * reply already in progress. Every line of that reply still arrives,
     * however many there are, after the restart, while [page] already
     * belongs to the new generation. [MissingList.Listing.feed] has no field
     * that tells old and new apart: two listings that differ only in *when*
     * they ran can carry the same offset, the same total, even the same
     * tiles (`docs/ble-review-2026-08.md`, "Stability -- app", item 3,
     * "paging offsets desync").
     *
     * So identity again has to come from what this side did, the same idea
     * as [owedVerdicts] one level down: [startListing] owns up to owing a
     * reply the moment it restarts on top of an incomplete [page], and
     * [requestPage]'s callback retracts that debt if the write it was
     * counting on turns out to have failed. [feedPage] discards every line
     * while a debt is outstanding, up to and including the stale reply's own
     * terminating `OK` -- exactly the boundary firmware guarantees, one
     * reply fully drained before the next line of anything else is sent.
     *
     * Unlike [owedVerdicts], **not** cleared by [reset]: a transfer's debt is
     * capped at one and safe to drop, because the alternative -- a fresh
     * fetch's real verdict wrongly eaten -- is worse than the rare case of
     * dropping it. A listing's debt can stack across more than one restart
     * (each one sent before the last resolved), arriving in the order the
     * device will answer them, and dropping it would let a stale reply
     * corrupt whatever fetch replaces it. Cleared only where the channel it
     * would have arrived on is provably gone -- [onDisconnected], [stop] --
     * so it cannot go on to poison a fetch that starts long after.
     */
    private var owedListingReplies = 0

    /**
     * The next tile's bytes, read from the source while the current tile was
     * still going out over BLE.
     *
     * Why: the two links are independent and this code used to use them one at
     * a time. Between tile N's `OK` and tile N+1's begin frame the BLE link sat
     * idle at HIGH priority for a whole HTTPS GET -- 0.3-1.5 s of dead air per
     * tile, both radios powered, because [nextTile]'s `source.read` only
     * started once the previous tile was finished
     * (`docs/ble-review-2026-08.md`, "Performance", "CDN fetch is serialized
     * into the BLE pipeline"). The read is started at tile N's accepted `RDY`
     * instead, so it overlaps tile N's chunks and the next begin frame can go
     * out the moment the `OK` lands.
     *
     * **Exactly one tile, never a pipeline.** Tiles run tens to hundreds of kB
     * and this is a phone mid-ride: at most one prefetched array and one live
     * transfer's array are alive at a time. A deeper queue would buy a second
     * or so and cost megabytes.
     *
     * [prefetching] is the tile a read is out for, [prefetched] the tile whose
     * read came back. Only ever one of the two is set -- [maybePrefetch]
     * refuses to start a second read while either is occupied -- which is what
     * bounds the memory. Identity (`===`) and not equality decides whether a
     * held tile is the one being asked for: [MissingTile] is a data class, and
     * the object in these fields is the very one the queue handed out.
     */
    private var prefetched: Pair<MissingTile, ByteArray>? = null
    private var prefetching: MissingTile? = null

    private var timeout: Scheduler.Cancellable? = null

    // --- input from the link ------------------------------------------------

    /** One line off the command characteristic (`...0003`). */
    fun onCommandLine(line: String) {
        val need = MissingList.parseNeedTiles(line)
        if (need != null) {
            startListing(need)
            return
        }

        if (MissingList.isFetchCancel(line)) {
            if (phase != Phase.IDLE) finish("cancelled on the device")
            return
        }
        if (phase == Phase.LISTING) feedPage(line)
    }

    /** One line off the transfer status characteristic (`...0005`). */
    fun onStatusLine(line: String) {
        if (phase != Phase.PUSHING) return
        val status = TransferFrames.parseStatus(line)
        val verdict = status is TransferFrames.Status.Ok || status is TransferFrames.Status.Err

        // Three gates, all of them the same question: does this line belong to
        // the transfer currently in flight? Nothing in the line itself says.

        // 1. Between a local abort and the next begin frame there is no live
        //    transfer at all -- everything on this channel is the dead one's
        //    tail. `RDY` for the next begin is the first line accepted again.
        if (statusGen != liveStatusGen) {
            Log.i(TAG, "ignoring '$line': gen $liveStatusGen was aborted, gen is now $statusGen")
            if (verdict && owedVerdicts > 0) owedVerdicts--
            return
        }

        // 2. The dead transfer's verdict can also land after the next begin has
        //    gone out, and then gate 1 cannot see it. Consume it against the
        //    debt instead of crediting it: this is the case that produced a
        //    false `onTileDone` (plus a `clearTransfer()` that killed the new
        //    tile's live state) or a false SKIP_REFUSED.
        if (verdict && owedVerdicts > 0) {
            owedVerdicts--
            Log.i(TAG, "ignoring '$line': an aborted transfer still owed a verdict")
            return
        }

        // 3. A verdict with nothing in flight has nothing to be about. Without
        //    this it would count a tile and call nextTile() a second time, and
        //    with an asynchronous source (the CDN) that window is a whole HTTP
        //    GET wide -- two transfers interleaved on one channel.
        if (verdict && tile == null) {
            Log.w(TAG, "ignoring '$line': no transfer in flight")
            return
        }

        when (status) {
            is TransferFrames.Status.Ready -> {
                if (!awaitingReady) return
                // Ordered channel: anything an aborted transfer still owed
                // arrived before this line, or was dropped by the device and is
                // never coming.
                owedVerdicts = 0
                awaitingReady = false
                armTimeout()
                // The device has the file open and this tile is committed, so
                // the tile after it is now the one worth spending the phone's
                // data on. Started before the first chunk goes out, not after
                // the last: the point is to overlap the GET with the whole
                // transfer, and nothing below this line needs the network.
                maybePrefetch()
                sendNextChunk()
            }

            is TransferFrames.Status.Ok -> {
                sent++
                Log.i(TAG, "landed ${describe(tile)} (${status.bytes} bytes)")
                tile?.let {
                    listener.onTileReceipt(it.z, it.col, it.row, status.bytes, status.crc32)
                    listener.onTileDone(it.z, it.col, it.row, status.bytes, true, "")
                }
                clearTransfer()
                listener.onFetchProgress(sent, skipped, total)
                nextTile()
            }

            is TransferFrames.Status.Err -> {
                // Terminal for this tile by contract: the device has already
                // closed and deleted the partial file, and further chunks would
                // each earn another `ERR no transfer`.
                Log.w(TAG, "device refused ${describe(tile)}: ${status.reason}")
                val failed = tile
                clearTransfer()
                if (failed != null) skip(failed, SKIP_REFUSED) else nextTile()
            }

            is TransferFrames.Status.Unknown -> Log.w(TAG, "unknown status: ${status.line}")
        }
    }

    /** The link dropped. Whatever was in flight is dead. */
    fun onDisconnected() {
        if (phase == Phase.IDLE) return
        // The channel any owed reply would have arrived on is gone with the
        // link -- it can never be paid, and holding onto it would wrongly
        // starve the first listing of whatever connects next.
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

    /**
     * Pushes tiles nobody asked for, in the order given.
     *
     * The one entry point that skips the listing phase, and it exists for the
     * freshness check: those tiles were found by this phone reading the CDN's
     * index, so the device has nothing to add by naming them back. Their
     * expected content ids are already in [expected], which is what makes the
     * fetch a replacement rather than a re-download of the copy being replaced.
     *
     * Refused while a fetch is running. Not queued behind it either: by the time
     * that one finishes the viewport may have moved, and the next check will
     * find whatever is still out of date.
     */
    fun pushTiles(tiles: List<MissingTile>, formatVersion: Int?) {
        if (phase != Phase.IDLE) {
            Log.i(TAG, "not pushing ${tiles.size} stale tile(s): a fetch is already running")
            return
        }
        if (tiles.isEmpty()) return
        Log.i(TAG, "pushing ${tiles.size} stale tile(s) unasked")
        fetchGen++
        phase = Phase.PUSHING
        wantedFormat = formatVersion
        total = tiles.size
        queue.addAll(tiles)
        transport.setFastLink(true)
        listener.onFetchStarted(total)
        nextTile()
    }

    // --- listing ------------------------------------------------------------

    private fun startListing(need: MissingList.NeedTiles) {
        if (phase != Phase.IDLE) {
            // A second NEED_TILES while one is running: the rider pressed the
            // menu item again. Start over rather than interleave two listings --
            // the device's own counters were reset by that press too.
            Log.i(TAG, "restarting fetch on a second NEED_TILES")
            // The page in flight, if any, was asked for over the same command
            // channel this side is about to reuse. Its write may already
            // have reached the device -- own up to owing that whole reply
            // now, before `reset()` drops the only record of it ([page]).
            // requestPage()'s write callback retracts this if the write
            // turns out to have failed (see [owedListingReplies]).
            if (page?.complete == false) owedListingReplies++
            abortInFlight()
            reset()
        }
        Log.i(
            TAG,
            "device wants ${need.count} tiles, format ${need.formatVersion ?: "unstated"}, " +
                "scope ${if (need.viewportOnly) "viewport" else "whole list"}, " +
                "source is ${source.describe()}",
        )
        // One fetch is starting, whether this is the first NEED_TILES or a
        // restart on a second one -- either way any read left over from
        // whatever ran before must not be able to touch what follows.
        fetchGen++
        phase = Phase.LISTING
        total = need.count
        wantedFormat = need.formatVersion
        viewportOnly = need.viewportOnly
        // For the duration of this fetch and no longer -- released in finish().
        transport.setFastLink(true)
        listener.onFetchScope(need.viewportOnly)
        listener.onFetchStarted(need.count)
        requestPage(0)
    }

    private fun requestPage(offset: Int) {
        // `view` asks are answered from the viewport and never paged -- at most
        // 32 tiles, one reply. Only offset 0 can reach here in that mode.
        val line: String
        if (viewportOnly) {
            page = MissingList.ViewportReader()
            line = "tiles"
        } else {
            page = MissingList.PageReader()
            line = if (offset == 0) "missing" else "missing $offset"
        }
        // Captured before the write, same reason as nextTile()'s `gen`: a
        // second NEED_TILES can restart the fetch (bumping fetchGen, replacing
        // `page`) before this write's callback returns. Unguarded, that late
        // `ok=false` would call finish() on the run that replaced this one --
        // it has no idea a restart happened, `finish()` takes no argument
        // saying which run it means (`docs/ble-review-2026-08.md`,
        // "Stability -- app", item 3).
        val gen = fetchGen
        armTimeout()
        transport.sendCommand(line) { ok, error ->
            if (gen != fetchGen) {
                if (!ok) {
                    // The debt startListing() registered for this request was
                    // a guess -- the write might still have reached the
                    // device. Now it is known it did not: nothing is coming,
                    // so retract it before it starves the fetch that is live
                    // now (see [owedListingReplies]).
                    owedListingReplies--
                    Log.i(TAG, "a late page-request write failed for gen $gen; retracting its owed reply")
                } else {
                    Log.i(TAG, "dropping a late page-request result for gen $gen; fetch has moved on")
                }
                return@sendCommand
            }
            if (!ok) finish("could not ask for the list: ${error ?: "write failed"}")
        }
    }

    private fun feedPage(line: String) {
        if (owedListingReplies > 0) {
            // A whole stale reply from a request this side abandoned is
            // still draining (see [owedListingReplies]). Every line up to
            // and including its own terminating OK belongs to that dead
            // conversation, not to the live `page`.
            if (line.trim() == "OK") owedListingReplies--
            return
        }
        val reader = page ?: return
        if (!reader.feed(line)) return
        if (reader.unavailable) {
            // Two different "cannot answer" cases, and they want different
            // words: no store wired to the console at all, versus no viewport
            // yet because no fix has landed since the device started.
            finish(if (viewportOnly) "device has no viewport yet" else "device has no missing-tile list")
            return
        }
        if (!reader.complete) return

        queue.addAll(reader.tiles)
        // The device's own total wins over NEED_TILES' count if they differ: the
        // list can have moved between the two (a tile hatched, or one arrived).
        reader.total?.let { if (it > 0) total = it }

        val next = reader.nextOffset
        if (!reader.done && next != null) {
            requestPage(next)
            return
        }

        cancelTimeout()
        Log.i(TAG, "list complete: ${queue.size} tiles of $total")
        phase = Phase.PUSHING
        nextTile()
    }

    // --- pushing ------------------------------------------------------------

    private fun nextTile() {
        val next = queue.removeFirstOrNull()
        if (next == null) {
            finish("done")
            return
        }

        val relPath = TransferFrames.tileRelPath(next.z, next.col, next.row)
        if (!TransferFrames.isSafeRelPath(relPath)) {
            // Cannot happen from a well-formed list; if it does, the device
            // would refuse it anyway and this saves the round trip.
            Log.w(TAG, "refusing to push an unsafe path: $relPath")
            skip(next, SKIP_REFUSED)
            return
        }

        val ready = prefetched
        if (ready != null && ready.first === next) {
            // Read while the previous tile was still going out. The begin frame
            // can go out now instead of after a whole HTTPS GET.
            prefetched = null
            Log.i(TAG, "using the prefetched ${describe(next)} (${ready.second.size} bytes)")
            onTileBytes(next, relPath, ready.second, fetchGen)
            return
        }
        // Anything held is for some other tile, or is a read still out for this
        // one that cannot be waited on here -- either way it can only sit on the
        // slot and keep the next prefetch from starting. Dropping it also makes
        // an outstanding read's callback throw its bytes away instead of parking
        // them behind a tile nobody will ask for again.
        dropPrefetch()

        // Captured before the async read, not read fresh in the callback: this
        // is the fetch the read belongs to, and `fetchGen` may have moved on by
        // the time the callback runs.
        val gen = fetchGen
        // Asynchronous: the source may be the CDN, and an HTTP GET cannot run
        // on the thread BLE lives on. The callback comes back on this thread.
        source.read(
            next.z, next.col, next.row, wantedFormat,
            expected.get(next.z, next.col, next.row),
        ) { data ->
            onTileBytes(next, relPath, data, gen)
        }
    }

    /**
     * Starts reading the tile behind the one now going out, if nothing is held.
     *
     * Goes through the same `source.read` entry point with the same
     * `expected.get(...)` argument as [nextTile], deliberately: that argument is
     * what turns a stale-tile fetch into `?crc=<content_id>` on the CDN URL
     * ([TileSource.read]), and a prefetch that dropped it would hand back the
     * very copy being replaced.
     *
     * A miss is not remembered -- the slot holds bytes or nothing. A tile the
     * CDN 404s is read a second time by [nextTile] and skipped there, which
     * costs one wasted GET on a tile that was never going to be pushed. Worth
     * it against carrying a "known absent" state through every invalidation
     * path below.
     */
    private fun maybePrefetch() {
        if (prefetched != null || prefetching != null) return
        val next = queue.firstOrNull() ?: return
        if (!TransferFrames.isSafeRelPath(TransferFrames.tileRelPath(next.z, next.col, next.row))) {
            // nextTile() refuses this one without a round trip; do not spend a
            // GET on it either.
            return
        }
        // Same guard, same reason as nextTile()'s: a CDN read is 10-20 s of HTTP
        // timeouts, wide enough for this fetch to end and a new one to start
        // before it lands, and `phase` cannot tell those two apart.
        val gen = fetchGen
        prefetching = next
        source.read(
            next.z, next.col, next.row, wantedFormat,
            expected.get(next.z, next.col, next.row),
        ) { data ->
            if (gen != fetchGen) {
                Log.i(TAG, "dropping a prefetch of ${describe(next)}; its fetch has moved on")
                return@read
            }
            if (prefetching !== next) {
                // The fetch walked away from this tile while the read was out --
                // it was skipped, or it became the live transfer and was read
                // again. Either way these bytes have no owner.
                Log.i(TAG, "dropping a prefetch of ${describe(next)}; nothing is waiting for it")
                return@read
            }
            prefetching = null
            if (data != null) prefetched = next to data
        }
    }

    /**
     * Forgets whatever was read ahead.
     *
     * An HTTP GET already in flight cannot be cancelled, so clearing
     * [prefetching] is how it is disowned: its callback sees the field no longer
     * naming its tile and drops the bytes on the floor instead of parking them.
     */
    private fun dropPrefetch() {
        prefetched = null
        prefetching = null
    }

    /**
     * The source answered for [next]. Everything from the begin frame onward.
     *
     * Guarded against a fetch that ended, or ended and was replaced, while the
     * read was in flight. A CDN read is 10-20 s of HTTP timeouts, wide enough
     * for the fetch it belongs to (A) to finish and a *new* one (B) to already
     * be running by the time it lands -- `phase` alone cannot catch that
     * because B leaves it exactly where A left it, `PUSHING`. `gen` is what
     * `nextTile` captured before starting the read, so it is A's fetch, not
     * whatever is live now; comparing it to the live `fetchGen` is the same
     * pattern as `sendNextChunk`'s `tile !== current` guard below, one level up
     * -- there it is one late chunk callback vs. the live tile, here it is one
     * late read vs. the live fetch.
     */
    private fun onTileBytes(next: MissingTile, relPath: String, data: ByteArray?, gen: Int) {
        if (gen != fetchGen) {
            Log.i(TAG, "dropping a late read for ${describe(next)}; its fetch has moved on")
            return
        }
        if (phase != Phase.PUSHING) {
            Log.i(TAG, "dropping a late read for ${describe(next)}; the fetch has ended")
            return
        }
        if (data == null) {
            skip(next, SKIP_NO_SOURCE)
            return
        }

        // Checked here, before a single byte goes out. A wrong-version tile
        // transfers fine and passes CRC, and the device only finds out when
        // MapTileReader refuses it on the next render -- by which time the
        // entry has been dropped from its missing list and the next fetch asks
        // for the same tile again. Cheaper to refuse locally and say why.
        if (!TileHeader.isAcceptable(data, wantedFormat)) {
            val found = TileHeader.formatVersion(data)
            Log.w(
                TAG,
                "$relPath is format ${found ?: "not a tile"}, device reads ${wantedFormat ?: "unstated"}",
            )
            skip(next, "$SKIP_WRONG_FORMAT${found ?: 0}")
            return
        }

        tile = next
        bytes = data
        offset = 0
        awaitingReady = true
        // A begin frame starts a new generation of status lines and makes it the
        // live one. Bumped before the frame goes out, not in its callback: the
        // `RDY` can arrive before the write response does.
        liveStatusGen = ++statusGen
        armTimeout()

        // Re-assert per tile, not just once at fetch start: nothing here
        // proves the stack actually honoured the earlier ask (Android may
        // silently ignore or revert it), and re-issuing is idempotent and
        // ~free (`docs/ble-review-2026-08.md`, "Performance"). This is the
        // point nextTile() actually commits to sending a tile -- the boundary
        // between one tile's transfer and the next.
        transport.setFastLink(true)
        val crc = TransferFrames.crc32(data)
        // Announced before the frame goes out, so a ledger records what it is
        // about to owe rather than what it hopes happened. The device's verdict
        // is checked against exactly these two numbers ([Listener.onTileSending]).
        listener.onTileSending(next.z, next.col, next.row, data.size, crc)
        val frame = TransferFrames.beginFrame(relPath, data.size, crc)
        transport.sendFrame(frame) { ok, error ->
            // Read `next`, never the current `tile`: a write failure can land
            // long after the write was issued. `BleLink`'s op queue holds an op
            // behind one that timed out, and its own transfer-write timeout is
            // 10 s against this fetcher's 15 s reply timeout, so by the time a
            // begin failure arrives this side may have given up on `next` and
            // moved to another tile. Crediting it to whatever is live now skips
            // a tile that is fine, kills its transfer state and pushes the fetch
            // on -- a second punishment for a tile that did nothing, while
            // `next` was already dealt with by its own timeout. Same shape as
            // the chunk callback's `tile !== current` guard below.
            if (!ok) {
                if (tile !== next) {
                    Log.i(TAG, "dropping a late begin failure for $relPath: $error")
                    return@sendFrame
                }
                Log.w(TAG, "begin write failed for $relPath: $error")
                clearTransfer()
                skip(next, SKIP_REFUSED)
            }
        }
    }

    /**
     * Sends one chunk and, on its write callback, the next.
     *
     * The callback *is* the flow control. The transfer characteristic is a plain
     * WRITE, so the ATT write response arrives only once the device has the
     * bytes on the SD card -- driving the loop off it means the sender
     * physically cannot outrun the card. Sending without waiting loses chunks
     * silently and the transfer dies on an offset check.
     */
    private fun sendNextChunk() {
        val data = bytes ?: return
        val current = tile ?: return
        if (offset >= data.size) return  // waiting for OK

        val remaining = data.size - offset
        val take = minOf(remaining, transport.maxChunkPayload())
        val payload = data.copyOfRange(offset, offset + take)
        val frame = TransferFrames.chunkFrame(offset, payload)
        val chunkOffset = offset

        transport.sendFrame(frame) { ok, error ->
            // A late callback from a transfer that has already been abandoned
            // must not drive the next one's chunk pointer.
            if (tile !== current) return@sendFrame
            if (!ok) {
                Log.w(TAG, "chunk at $chunkOffset failed: $error")
                abortInFlight()
                clearTransfer()
                skip(current, SKIP_REFUSED)
                return@sendFrame
            }
            offset = chunkOffset + take
            armTimeout()
            listener.onTileProgress(current.z, current.col, current.row, offset, data.size)
            if (offset < data.size) sendNextChunk()
            // Else: every byte is on the card and the device is computing the
            // CRC. The `OK` (or `ERR crc mismatch`) is what moves this on.
        }
    }

    private fun skip(missing: MissingTile, reason: String) {
        // A skipped tile is never asked for again in this fetch, so anything
        // read ahead for it is dead weight sitting on the one prefetch slot.
        // Reachable from nextTile()'s unsafe-path refusal, which skips the very
        // tile a prefetch was held for.
        if (prefetched?.first === missing || prefetching === missing) dropPrefetch()
        skipped++
        listener.onTileDone(missing.z, missing.col, missing.row, 0, false, reason)
        listener.onFetchProgress(sent, skipped, total)
        // Gen captured for the same reason as requestPage()'s: this write's
        // callback does nothing but log today, but a late one belongs to a
        // fetch this side has already walked away from, and the log line
        // should say so rather than name whatever tile is live now.
        val gen = fetchGen
        transport.sendCommand("skip ${missing.z} ${missing.col} ${missing.row} $reason") { ok, error ->
            if (gen != fetchGen) return@sendCommand
            if (!ok) Log.w(TAG, "skip write failed: $error")
        }
        nextTile()
    }

    private fun abortInFlight() {
        if (tile == null) return
        // The live generation is over here, not when the abort frame is
        // acknowledged: from this line on nothing on the status channel belongs
        // to a live transfer, and the device still owes this one a verdict.
        statusGen++
        owedVerdicts++
        transport.sendFrame(TransferFrames.abortFrame()) { ok, error ->
            if (!ok) Log.w(TAG, "abort write failed: $error")
        }
    }

    private fun clearTransfer() {
        tile = null
        bytes = null
        offset = 0
        awaitingReady = false
        cancelTimeout()
    }

    // --- timeouts and teardown ---------------------------------------------

    private fun armTimeout() {
        cancelTimeout()
        timeout = scheduler.postDelayed(REPLY_TIMEOUT_MS) {
            timeout = null
            Log.w(TAG, "timed out in $phase, ${describe(tile)}")
            when (phase) {
                Phase.LISTING -> finish("device stopped answering")
                Phase.PUSHING -> {
                    // One dead tile does not end the fetch: abort it, count it,
                    // move on. A device that is really gone will time out on the
                    // next one too and the queue drains.
                    val stalled = tile
                    abortInFlight()
                    clearTransfer()
                    if (stalled != null) skip(stalled, SKIP_REFUSED) else nextTile()
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
        // Every exit from a fetch comes through here -- done, cancelled, link
        // lost, stopped -- which is what makes this the one place the fast link
        // has to be handed back, and the one place a fetch unconditionally ends
        // even when nothing new replaces it.
        fetchGen++
        transport.setFastLink(false)
        val wasSent = sent
        val wasSkipped = skipped
        val wasTotal = total
        reset()
        Log.i(TAG, "fetch finished: $reason ($wasSent sent, $wasSkipped skipped of $wasTotal)")
        listener.onFetchFinished(wasSent, wasSkipped, wasTotal, reason)
    }

    private fun reset() {
        cancelTimeout()
        phase = Phase.IDLE
        page = null
        queue.clear()
        total = 0
        sent = 0
        skipped = 0
        wantedFormat = null
        viewportOnly = false
        tile = null
        bytes = null
        offset = 0
        awaitingReady = false
        // Tens to hundreds of kB held for a tile of a fetch that is over. The
        // generation guard in maybePrefetch()'s callback would refuse to park
        // anything new here, but bytes already parked have to go, or they
        // outlive the fetch that paid for them.
        dropPrefetch()
        // A fetch that ended right after an abort would otherwise leave a debt
        // behind for the next one to pay with its first tile's real verdict.
        owedVerdicts = 0
        liveStatusGen = statusGen
    }

    private fun describe(t: MissingTile?): String =
        if (t == null) "no tile" else "z${t.z} ${t.col}/${t.row}"
}
