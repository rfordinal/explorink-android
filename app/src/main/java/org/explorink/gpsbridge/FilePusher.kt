package org.explorink.gpsbridge

import android.util.Log

/**
 * Pushes **one file's bytes to one relative path** on the device, over the
 * transfer characteristic.
 *
 * Lifted out of [TileFetcher], which drove this loop for tiles and could not
 * share it with anything else: a route file is the same conversation with a
 * different path (`docs/route-share-plan.md`, Phase 1). Nothing here knows what
 * a tile is.
 *
 * **This is a move, not a rewrite.** Every guard below was put there by a
 * measured failure -- a late verdict credited to the transfer after it, an `OK`
 * that beats the last chunk's write response, a begin failure arriving after
 * this side gave up. `TileFetcherTest` covers all of them and is the reason
 * this refactor can be trusted; do not re-derive any of it.
 *
 * The caller keeps everything above one file: what to send next, what to do
 * with a failure, the fast link, and the listing conversation on the command
 * characteristic.
 *
 * Interfaces come from [TileFetcher] because they are already the app's shared
 * ones -- `PointSync`, `PinManager` and `FreshnessChecker` all take
 * `TileFetcher.Scheduler` today.
 */
class FilePusher(
    private val transport: TileFetcher.Transport,
    private val scheduler: TileFetcher.Scheduler,
    private val listener: Listener,
    private val replyTimeoutMs: Long,
) {

    /**
     * What the caller hears back. [tag] is whatever it handed [start] -- the
     * pusher never looks inside it, it only hands it back so the caller can
     * tell which of its own jobs a callback is about.
     */
    interface Listener {
        /**
         * About to send the begin frame, with the two numbers the device's
         * verdict will be checked against.
         */
        fun onPushStarting(tag: Any?, path: String, totalBytes: Int, crc32: Long) {}

        /** `RDY` -- the device accepted the begin frame and has the file open. */
        fun onPushReady(tag: Any?) {}

        /** Bytes the device has acknowledged so far. */
        fun onPushProgress(tag: Any?, sentBytes: Int, totalBytes: Int) {}

        /** `OK` -- every byte is on the card and the device's CRC matched. */
        fun onPushDone(tag: Any?, bytes: Int, crc32: Long)

        /**
         * The push is over and the file is not on the card. The transfer has
         * already been aborted and the pusher is idle; the caller decides
         * whether to skip, retry or stop.
         */
        fun onPushFailed(tag: Any?, reason: String)
    }

    private var tag: Any? = null
    private var path: String = ""
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
     * the caller's whole queue on a dead file's 15 s timeout, and everything
     * still waiting pays for it. Skipping generations costs nothing.
     */
    private var statusGen = 0
    private var liveStatusGen = 0

    /**
     * Verdicts (`OK`/`ERR`) still owed by transfers this side walked away from.
     *
     * The generation window above closes the moment the next begin goes out,
     * and a dead transfer's verdict can arrive after that -- so the window
     * alone would still credit it to the new file, which is the whole defect
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

    private var timeout: TileFetcher.Scheduler.Cancellable? = null

    /** True while a file is in flight. */
    val busy: Boolean get() = bytes != null

    /**
     * Starts one file. The caller must not call this while [busy].
     *
     * Everything the device will be asked for is decided here: the path, the
     * length and the whole-file CRC32 it will check its own copy against.
     */
    fun start(tag: Any?, relPath: String, data: ByteArray) {
        this.tag = tag
        path = relPath
        bytes = data
        offset = 0
        awaitingReady = true
        // A begin frame starts a new generation of status lines and makes it the
        // live one. Bumped before the frame goes out, not in its callback: the
        // `RDY` can arrive before the write response does.
        liveStatusGen = ++statusGen
        armTimeout()

        val crc = TransferFrames.crc32(data)
        // Announced before the frame goes out, so a ledger records what it is
        // about to owe rather than what it hopes happened.
        listener.onPushStarting(tag, relPath, data.size, crc)
        val frame = TransferFrames.beginFrame(relPath, data.size, crc)
        val started = tag
        transport.sendFrame(frame) { ok, error ->
            // Read `started`, never the current job: a write failure can land
            // long after the write was issued. `BleLink`'s op queue holds an op
            // behind one that timed out, and its own transfer-write timeout is
            // 10 s against this pusher's reply timeout, so by the time a begin
            // failure arrives this side may have given up and moved on.
            // Crediting it to whatever is live now kills a transfer that is
            // fine, while `started` was already dealt with by its own timeout.
            if (!ok) {
                if (this.tag !== started) {
                    Log.i(TAG, "dropping a late begin failure for $relPath: $error")
                    return@sendFrame
                }
                Log.w(TAG, "begin write failed for $relPath: $error")
                fail(REASON_REFUSED, abort = false)
            }
        }
    }

    /**
     * One line off the transfer status characteristic (`...0005`).
     *
     * Returns true when the line was this pusher's to act on, so a caller that
     * has its own use for the channel can tell.
     */
    fun onStatusLine(line: String): Boolean {
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
            return false
        }

        // 2. The dead transfer's verdict can also land after the next begin has
        //    gone out, and then gate 1 cannot see it. Consume it against the
        //    debt instead of crediting it: this is the case that produced a
        //    false completion plus a teardown that killed the new file's live
        //    state.
        if (verdict && owedVerdicts > 0) {
            owedVerdicts--
            Log.i(TAG, "ignoring '$line': an aborted transfer still owed a verdict")
            return false
        }

        // 3. A verdict with nothing in flight has nothing to be about. Without
        //    this it would complete a file a second time, and with an
        //    asynchronous source that window is a whole HTTP GET wide.
        if (verdict && bytes == null) {
            Log.w(TAG, "ignoring '$line': no transfer in flight")
            return false
        }

        when (status) {
            is TransferFrames.Status.Ready -> {
                if (!awaitingReady) return false
                // Ordered channel: anything an aborted transfer still owed
                // arrived before this line, or was dropped by the device and is
                // never coming.
                owedVerdicts = 0
                awaitingReady = false
                armTimeout()
                // The caller gets the ready notice before the first chunk goes
                // out, not after the last: that is what lets it overlap a read
                // of the next file with this one's whole transfer.
                listener.onPushReady(tag)
                sendNextChunk()
            }

            is TransferFrames.Status.Ok -> {
                val finished = tag
                Log.i(TAG, "landed $path (${status.bytes} bytes)")
                clear()
                listener.onPushDone(finished, status.bytes, status.crc32)
            }

            is TransferFrames.Status.Err -> {
                // Terminal for this file by contract: the device has already
                // closed and deleted the partial file, and further chunks would
                // each earn another `ERR no transfer`.
                Log.w(TAG, "device refused $path: ${status.reason}")
                fail(REASON_REFUSED, abort = false)
            }

            is TransferFrames.Status.Unknown -> {
                Log.w(TAG, "unknown status: ${status.line}")
                return false
            }
        }
        return true
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
        val current = tag
        if (offset >= data.size) return  // waiting for OK

        val remaining = data.size - offset
        val take = minOf(remaining, transport.maxChunkPayload())
        val payload = data.copyOfRange(offset, offset + take)
        val frame = TransferFrames.chunkFrame(offset, payload)
        val chunkOffset = offset

        transport.sendFrame(frame) { ok, error ->
            // A late callback from a transfer that has already been abandoned
            // must not drive the next one's chunk pointer.
            if (tag !== current || bytes !== data) return@sendFrame
            if (!ok) {
                Log.w(TAG, "chunk at $chunkOffset failed: $error")
                fail(REASON_REFUSED, abort = true)
                return@sendFrame
            }
            offset = chunkOffset + take
            armTimeout()
            listener.onPushProgress(current, offset, data.size)
            if (offset < data.size) sendNextChunk()
            // Else: every byte is on the card and the device is computing the
            // CRC. The `OK` (or `ERR crc mismatch`) is what moves this on.
        }
    }

    /**
     * Tells the device to drop what is in flight.
     *
     * The live generation is over here, not when the abort frame is
     * acknowledged: from this line on nothing on the status channel belongs to
     * a live transfer, and the device still owes this one a verdict.
     */
    fun abortInFlight() {
        if (bytes == null) return
        statusGen++
        owedVerdicts++
        transport.sendFrame(TransferFrames.abortFrame()) { ok, error ->
            if (!ok) Log.w(TAG, "abort write failed: $error")
        }
    }

    /** Forgets the file in flight without telling the device anything. */
    fun clear() {
        tag = null
        path = ""
        bytes = null
        offset = 0
        awaitingReady = false
        cancelTimeout()
    }

    /**
     * Drops everything, including the debt.
     *
     * For a caller that is shutting a whole conversation down: a debt carried
     * past it would be paid with the next conversation's first real verdict.
     */
    fun reset() {
        clear()
        owedVerdicts = 0
        liveStatusGen = statusGen
    }

    private fun fail(reason: String, abort: Boolean) {
        val failed = tag
        if (abort) abortInFlight()
        clear()
        listener.onPushFailed(failed, reason)
    }

    private fun armTimeout() {
        cancelTimeout()
        timeout = scheduler.postDelayed(replyTimeoutMs) {
            timeout = null
            Log.w(TAG, "timed out pushing $path")
            // One dead file does not have to end the caller's work: abort it,
            // report it, let the caller decide. A device that is really gone
            // will time out on the next one too.
            fail(REASON_REFUSED, abort = true)
        }
    }

    private fun cancelTimeout() {
        timeout?.cancel()
        timeout = null
    }

    companion object {
        private const val TAG = "FilePusher"

        /** What the caller is told when the device refused, failed or went quiet. */
        const val REASON_REFUSED = "refused"
    }
}
