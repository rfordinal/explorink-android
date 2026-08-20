package org.explorink.gpsbridge.wallet

import org.explorink.gpsbridge.TransferFrames

/**
 * Wallet assets over the X4's existing BLE file-transfer channel (phase P6's
 * phone half).
 *
 * **No new protocol.** The map's transfer characteristic already is a generic file
 * push with the acknowledgement brief section 28 asks for, and the frames are
 * already ported and already proved against hardware
 * (`org.explorink.gpsbridge.TransferFrames`, `docs/ble-map-transfer-protocol.md`):
 *
 *     begin(u32 totalLen, u32 crc32, u8 pathLen, path)      -> RDY <len> | ERR
 *     chunk(u32 offset, bytes)                              -> the ATT write response
 *     abort                                                 -> device deletes the .part
 *
 * The device writes `<path>.part`, **reads the finished file back off the card**,
 * CRC32s it and renames on match. So `OK <bytes> <crc32hex>` already means "the
 * card holds these bytes". That is the ACK; nothing else is invented on top of it.
 *
 * ## Three facts that shape this class
 *
 *  - **There is no chunk ack and none is needed.** `...0004` is a plain `WRITE`, so
 *    the ATT write response *is* the ack, and the device sends it only after the
 *    bytes are on the card. Write-with-response gives correct flow control for
 *    free: the sender physically cannot outrun the SD write. Write-without-response
 *    silently loses chunks.
 *  - **A begin frame truncates.** "A leftover .part from an earlier killed transfer
 *    is not resumed -- the whole file is coming again, from offset 0"
 *    (`MapTransferReceiver.cpp:310-312`). The byte offset in a chunk frame is real
 *    and is what a retry inside one transfer uses; it does **not** survive losing
 *    the connection. Resume across a connection is therefore per asset, which is
 *    what brief section 29 asks for.
 *  - **The receiver is attached only on the map and tile-sync screens**, and the
 *    device holds **one** BLE connection. So a wallet sync competes with anything
 *    else connected, and a panel redraw mid-transfer kills the link (observed 4x,
 *    `docs/ble-map-transfer-protocol.md:683`) -- which is why the device side wants
 *    a screen that holds the panel still.
 *
 * ## Portability
 *
 * Nothing Android is in here: the wire is [FrameSink], four methods wide. BLE GATT
 * in the central role is the portable transport -- CoreBluetooth does it -- so an
 * iOS port reimplements [FrameSink] and keeps this state machine. iOS background
 * execution is stricter, so a long unattended transfer needs re-checking there;
 * see `docs/android-wallet.md` ("iOS notes").
 */
class WalletBleTransport(
    private val frames: FrameSink,
    private val scheduler: Scheduler,
    // Empty, and that is the whole point: a BLE begin frame's path is **already
    // relative to /trailink** -- the receiver prepends its own root
    // (MapTransferReceiver.cpp, `snprintf(finalPath_, ..., "%s/%s", rootDir_, rel)`).
    // Shipping "trailink" here wrote every asset to /trailink/trailink/wallet/...
    // and the device happily answered OK for each one, because it had written and
    // CRC-verified exactly what it was asked to. Found on hardware 2026-08-19: a
    // 25-file sync reported "everything confirmed" and the wallet stayed invisible.
    // Kept as a parameter only so a test can pin the wire path.
    private val cardRoot: String = "",
) : WalletTransport {

    /** Everything this needs from a BLE stack, and nothing more. */
    interface FrameSink {
        /** Payload bytes that fit one chunk on the negotiated MTU. */
        fun maxChunkPayload(): Int

        /** One frame, one write, with response. [done] fires on the write result. */
        fun sendFrame(frame: ByteArray, done: (Boolean, String?) -> Unit)

        fun isConnected(): Boolean

        /** Ask for a fast connection interval while a transfer runs. */
        fun setFastLink(fast: Boolean) {}
    }

    interface Scheduler {
        fun postDelayed(delayMs: Long, action: () -> Unit): Cancellable
        interface Cancellable {
            fun cancel()
        }
    }

    override val name: String get() = "ble"
    override val label: String get() = "BLE"

    /** Measured from this app at MTU 256 (`docs/ble-map-transfer-protocol.md:565`). */
    override val bytesPerSecond: Int get() = 8_500

    override val resumesAcrossSessions: Boolean get() = false

    private var job: SendJob? = null
    private var cb: SendCallback? = null
    private var expectedCrc: Long = 0
    private var offset = 0
    private var awaitingReady = false
    private var awaitingVerdict = false
    private var writeRetries = 0
    private var timeout: Scheduler.Cancellable? = null

    /** Bumped on every finish, so a late status line for a dead job is ignored. */
    private var generation = 0

    override fun isReady(): Boolean = frames.isConnected()

    /**
     * A transfer is in flight, so status lines on `...0005` belong to this transport
     * and not to whatever else shares the channel.
     */
    val isBusy: Boolean get() = job != null

    override fun send(job: SendJob, cb: SendCallback) {
        if (this.job != null) {
            cb.onFailed("ble busy", retryable = true)
            return
        }
        val relPath = "$cardRoot/${job.relPath}".let { if (cardRoot.isEmpty()) job.relPath else it }
        // The device's own path rules, checked here so a doomed transfer is never
        // started. The device stays the authority; this saves a round trip.
        if (!TransferFrames.isSafeRelPath(relPath)) {
            cb.onFailed("bad path: $relPath", retryable = false)
            return
        }
        if (job.bytes.isEmpty() || job.bytes.size > TransferFrames.MAX_FILE_BYTES) {
            cb.onFailed("bad length: ${job.bytes.size}", retryable = false)
            return
        }
        if (!frames.isConnected()) {
            cb.onFailed("not connected", retryable = false)
            return
        }
        this.job = job
        this.cb = cb
        expectedCrc = TransferFrames.crc32(job.bytes)
        offset = 0
        awaitingReady = true
        awaitingVerdict = false
        writeRetries = 0
        raiseFastLink()

        val gen = generation
        arm(READY_TIMEOUT_MS, gen, "no RDY")
        frames.sendFrame(TransferFrames.beginFrame(relPath, job.bytes.size, expectedCrc)) { ok, err ->
            if (gen != generation) return@sendFrame
            if (!ok) fail("begin write failed: ${err ?: "?"}", retryable = false)
        }
    }

    override fun cancel() {
        if (job == null) return
        generation++
        clearTimeout()
        // Optional per the protocol -- disconnecting has the same effect -- but it
        // tells the device to drop its .part now rather than when the link dies.
        frames.sendFrame(TransferFrames.abortFrame()) { _, _ -> }
        reset()
        releaseFastLink()
    }

    /** Call from wherever `...0005` indications arrive. */
    fun onStatusLine(line: String) {
        if (job == null) return
        when (val s = TransferFrames.parseStatus(line)) {
            is TransferFrames.Status.Ready -> {
                if (!awaitingReady) return
                awaitingReady = false
                clearTimeout()
                val want = job?.bytes?.size ?: 0
                if (s.totalLen != want) {
                    fail("RDY ${s.totalLen}, sent $want", retryable = true)
                    return
                }
                writeNext()
            }

            is TransferFrames.Status.Ok -> {
                val j = job ?: return
                // THE acknowledgement. The device read the file back off the card and
                // CRC32'd it, so both halves have to agree before anything is
                // believed -- a matching length with a different CRC is exactly the
                // case a "200 means written" transport would have called success.
                if (s.bytes != j.bytes.size || s.crc32 != expectedCrc) {
                    fail("OK ${s.bytes} B crc ${java.lang.Long.toHexString(s.crc32)}, " +
                        "sent ${j.bytes.size} B crc ${java.lang.Long.toHexString(expectedCrc)}",
                        retryable = true)
                    return
                }
                val c = cb
                generation++
                clearTimeout()
                reset()
                c?.onConfirmed("OK ${s.bytes} ${java.lang.Long.toHexString(s.crc32)}")
            }

            is TransferFrames.Status.Err -> fail("ERR ${s.reason}", retryable = true)

            // Guessing at an unknown verdict is worse than waiting for the timeout.
            is TransferFrames.Status.Unknown -> Unit
        }
    }

    /** The link dropped. Whatever was in flight is gone, and its `.part` with it. */
    fun onDisconnected() {
        // Unconditional: the priority was raised on this connection and the connection
        // is gone, so the flag has to come down even when no asset was in flight.
        releaseFastLink()
        if (job == null) return
        fail("disconnected", retryable = false)
    }

    /** Reentrancy guard, so a synchronous write callback loops instead of recursing. */
    private var writing = false
    private var writeAgain = false

    /**
     * Send chunks until the file is out.
     *
     * A trampoline, not recursion. On a real Android stack `onCharacteristicWrite`
     * arrives on a binder thread and is posted, so each chunk gets a fresh stack --
     * but a transport must not depend on that. A synchronous [FrameSink] (a test
     * stub, or a future transport that writes straight to a socket) would otherwise
     * nest one frame per chunk, and a 48 kB asset at MTU 256 is ~194 chunks. That
     * overflowed the stack the first time this was tested against a synchronous stub,
     * which is exactly the kind of thing a double is for.
     */
    private fun writeNext() {
        if (writing) {
            writeAgain = true
            return
        }
        writing = true
        try {
            do {
                writeAgain = false
                writeOne()
            } while (writeAgain)
        } finally {
            writing = false
        }
    }

    private fun writeOne() {
        val j = job ?: return
        val payloadMax = frames.maxChunkPayload()
        val n = minOf(payloadMax, j.bytes.size - offset)
        if (n <= 0) {
            // Every byte written. The device is now reading the file back off the
            // card and CRC32ing it, which is why this wait is longer than the others.
            awaitingVerdict = true
            arm(VERDICT_TIMEOUT_MS, generation, "no OK")
            return
        }
        val at = offset
        val gen = generation
        val chunk = TransferFrames.chunkFrame(at, j.bytes.copyOfRange(at, at + n))
        arm(WRITE_TIMEOUT_MS, gen, "chunk write stalled")
        frames.sendFrame(chunk) { ok, err ->
            if (gen != generation) return@sendFrame
            clearTimeout()
            if (!ok) {
                // The offset is a byte offset and the device checks it against what it
                // has actually written, so one retry from the SAME offset is safe and
                // is the only place resume-by-offset earns its keep today.
                if (writeRetries < MAX_WRITE_RETRIES && frames.isConnected()) {
                    writeRetries++
                    writeNext()
                } else {
                    fail("chunk write failed at $at: ${err ?: "?"}", retryable = false)
                }
                return@sendFrame
            }
            writeRetries = 0
            offset = at + n
            cb?.onProgress(offset)
            writeNext()
        }
    }

    private fun arm(ms: Long, gen: Int, reason: String) {
        clearTimeout()
        timeout = scheduler.postDelayed(ms) {
            if (gen != generation) return@postDelayed
            fail(reason, retryable = true)
        }
    }

    private fun clearTimeout() {
        timeout?.cancel()
        timeout = null
    }

    private fun fail(reason: String, retryable: Boolean) {
        val c = cb ?: return
        generation++
        clearTimeout()
        reset()
        c.onFailed(reason, retryable)
    }

    /**
     * Clear the per-asset state.
     *
     * **The fast link is not dropped here.** This runs after every asset, and a queue is
     * dozens of them: `requestConnectionPriority` starts a parameter negotiation that
     * takes hundreds of milliseconds to land, so dropping and re-raising it per file left
     * whole files running at the balanced interval. Measured 2026-08-20 on one document:
     * the files that ran at 15 ms moved at 7.3-7.6 kB/s and the ones still at 30 ms
     * moved at 3.9 kB/s, in the same transfer (`docs/BUGS.md` BUG-002).
     *
     * The link is dropped by [releaseFastLink], which the engine calls when the queue is
     * done, and by [cancel] and [onDisconnected] -- the three places where there really
     * is no next asset.
     */
    private fun reset() {
        job = null
        cb = null
        offset = 0
        awaitingReady = false
        awaitingVerdict = false
        writeRetries = 0
    }

    /**
     * Give the radio back. Idempotent, and safe to call when nothing was raised.
     *
     * Separate from [reset] because "this asset is finished" and "the transfer is
     * finished" are different facts, and only the second one may slow the link.
     */
    override fun releaseFastLink() {
        if (!fastLinkRaised) return
        fastLinkRaised = false
        frames.setFastLink(false)
    }

    /**
     * Whether the radio has been asked for the fast interval on this queue.
     *
     * Tracked here as well as in `BleLink` on purpose: the link's own guard stops a
     * duplicate reaching the Android stack, and this one stops the duplicate being
     * *made*. A transport that asks per asset reads as if per-asset were the intent,
     * which is the thing that went wrong.
     */
    private var fastLinkRaised = false

    private fun raiseFastLink() {
        if (fastLinkRaised) return
        fastLinkRaised = true
        frames.setFastLink(true)
    }

    companion object {
        const val READY_TIMEOUT_MS = 15_000L
        const val WRITE_TIMEOUT_MS = 15_000L

        /**
         * The device reads the whole file back off the card and CRC32s it before it
         * answers. A 585 kB page image at the measured 550-608 kB/s read is ~1 s, so
         * this is generous rather than tight -- and a tight one would turn a slow
         * card into a failed sync.
         */
        const val VERDICT_TIMEOUT_MS = 30_000L

        const val MAX_WRITE_RETRIES = 2
    }
}
