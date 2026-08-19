package org.explorink.gpsbridge.wallet

/**
 * A pipe. Brief section 51: "Transport nesmie vedieť nič o významoch dokumentov"
 * -- a transport knows bytes, paths and connection state, and nothing about
 * items, pages, priorities or what is already on the card.
 *
 * The one thing a transport DOES own is **what counts as confirmation**, because
 * that differs per wire and only the transport can read its own verdict:
 *
 *  - BLE: the device writes `<path>.part`, reads the finished file back off the
 *    card, CRC32s it and renames on match. `OK <bytes> <crc32hex>` therefore
 *    already means "the card holds these bytes" -- brief section 28's ACK, with
 *    no second mechanism invented on top.
 *  - Wi-Fi: `POST /upload` answering 200 means "written", not "correct". The
 *    verdict is `GET /api/hash?path=…`, which streams the file off the card and
 *    answers `{"size":N,"sha256":"…"}`.
 *
 * So [SendCallback.onConfirmed] may only be called once the device's own answer
 * has been compared against what was sent.
 */
interface WalletTransport {

    /** Short id recorded in the ledger: "ble" or "wifi". */
    val name: String

    /** What the sync screen calls it. */
    val label: String

    /**
     * Measured throughput, bytes per second, used for estimates only.
     *
     * BLE 8-9 kB/s from this app (`docs/ble-map-transfer-protocol.md:565`, `:665`),
     * Wi-Fi upload 199-236 kB/s (`docs/wallet-plan.md` 7d). Both measured; both
     * still only good enough for a rough figure, which is why [estimateText]
     * refuses to print seconds.
     */
    val bytesPerSecond: Int

    /**
     * Whether a half-sent asset survives losing the connection.
     *
     * **False for both transports today**, and the reason is in the firmware, not
     * in this app: the BLE receiver truncates on every begin -- "A leftover .part
     * from an earlier killed transfer is not resumed -- the whole file is coming
     * again, from offset 0" (`MapTransferReceiver.cpp:310-312`) -- and `/upload` is
     * a whole-file multipart POST with no range support. Resume is therefore at
     * **asset** granularity, which is what brief section 29 actually asks for
     * ("continue with tile 8", not "continue mid-tile"). The byte offset in a chunk
     * frame is real and is used inside one transfer; it does not survive one.
     */
    val resumesAcrossSessions: Boolean

    /** Connected, subscribed, and able to take a job right now. */
    fun isReady(): Boolean

    /** Push one file and confirm what the card holds. One job at a time. */
    fun send(job: SendJob, cb: SendCallback)

    /** Give up whatever is in flight. The asset stays unconfirmed, so it is resent. */
    fun cancel()

    /**
     * A rough time for [bytes], with **no false precision** (brief section 38).
     *
     * Under a minute is "under a minute"; above it, whole minutes. The BLE figure
     * is a measured range and the real rate depends on the connection interval and
     * on whatever else is talking to the device, so a "1 m 47 s" would be a
     * fabrication dressed as a measurement.
     */
    fun estimateText(bytes: Long): String {
        if (bytes <= 0L) return "nothing pending"
        val seconds = bytes.toDouble() / bytesPerSecond
        return when {
            seconds < 10 -> "a few seconds"
            seconds < 60 -> "under a minute"
            seconds < 150 -> "roughly a minute or two"
            else -> "roughly ${Math.round(seconds / 60.0)} minutes"
        }
    }
}

/** One file to put on the card. [relPath] is relative to `/trailink`. */
class SendJob(
    val relPath: String,
    val bytes: ByteArray,
    /** sha256 of the whole file, hex. What `/api/hash` is compared against. */
    val sha256: String,
)

interface SendCallback {
    /** Bytes of this job the wire has taken so far. Progress only, never a verdict. */
    fun onProgress(sentBytes: Int)

    /** The device confirmed the card holds exactly these bytes. */
    fun onConfirmed(detail: String)

    /**
     * This job did not land.
     *
     * [retryable] false means the transport itself is gone (link dropped, hotspot
     * away) and the engine should stop rather than march through the rest of the
     * queue failing every asset in turn.
     */
    fun onFailed(reason: String, retryable: Boolean)
}
