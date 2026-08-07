package org.trailink.gpsbridge

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
 *     them over the transfer channel: begin, wait for `RDY`, chunks, `OK`.
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
        when (val status = TransferFrames.parseStatus(line)) {
            is TransferFrames.Status.Ready -> {
                if (!awaitingReady) return
                awaitingReady = false
                armTimeout()
                sendNextChunk()
            }

            is TransferFrames.Status.Ok -> {
                sent++
                Log.i(TAG, "landed ${describe(tile)} (${status.bytes} bytes)")
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
        finish("link lost")
    }

    /** Stop from this side (the service is shutting down, the user stopped it). */
    fun stop() {
        if (phase == Phase.IDLE) return
        abortInFlight()
        finish("stopped")
    }

    // --- listing ------------------------------------------------------------

    private fun startListing(need: MissingList.NeedTiles) {
        if (phase != Phase.IDLE) {
            // A second NEED_TILES while one is running: the rider pressed the
            // menu item again. Start over rather than interleave two listings --
            // the device's own counters were reset by that press too.
            Log.i(TAG, "restarting fetch on a second NEED_TILES")
            abortInFlight()
            reset()
        }
        Log.i(
            TAG,
            "device wants ${need.count} tiles, format ${need.formatVersion ?: "unstated"}, " +
                "scope ${if (need.viewportOnly) "viewport" else "whole list"}, " +
                "source is ${source.describe()}",
        )
        phase = Phase.LISTING
        total = need.count
        wantedFormat = need.formatVersion
        viewportOnly = need.viewportOnly
        // For the duration of this fetch and no longer -- released in finish().
        transport.setFastLink(true)
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
        armTimeout()
        transport.sendCommand(line) { ok, error ->
            if (!ok) finish("could not ask for the list: ${error ?: "write failed"}")
        }
    }

    private fun feedPage(line: String) {
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

        // Asynchronous: the source may be the CDN, and an HTTP GET cannot run
        // on the thread BLE lives on. The callback comes back on this thread.
        source.read(next.z, next.col, next.row, wantedFormat) { data ->
            onTileBytes(next, relPath, data)
        }
    }

    /**
     * The source answered for [next]. Everything from the begin frame onward.
     *
     * Guarded against a fetch that ended while the read was in flight: a
     * cancelled or dropped run must not start a transfer on the way out, and by
     * then `phase` has already moved on.
     */
    private fun onTileBytes(next: MissingTile, relPath: String, data: ByteArray?) {
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
        armTimeout()

        val frame = TransferFrames.beginFrame(relPath, data.size, TransferFrames.crc32(data))
        transport.sendFrame(frame) { ok, error ->
            if (!ok) {
                Log.w(TAG, "begin write failed for $relPath: $error")
                val failed = tile
                clearTransfer()
                if (failed != null) skip(failed, SKIP_REFUSED) else nextTile()
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
            if (offset < data.size) sendNextChunk()
            // Else: every byte is on the card and the device is computing the
            // CRC. The `OK` (or `ERR crc mismatch`) is what moves this on.
        }
    }

    private fun skip(missing: MissingTile, reason: String) {
        skipped++
        listener.onFetchProgress(sent, skipped, total)
        transport.sendCommand("skip ${missing.z} ${missing.col} ${missing.row} $reason") { ok, error ->
            if (!ok) Log.w(TAG, "skip write failed: $error")
        }
        nextTile()
    }

    private fun abortInFlight() {
        if (tile == null) return
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
        // has to be handed back.
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
    }

    private fun describe(t: MissingTile?): String =
        if (t == null) "no tile" else "z${t.z} ${t.col}/${t.row}"
}
