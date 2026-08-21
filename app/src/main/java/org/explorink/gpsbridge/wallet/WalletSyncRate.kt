package org.explorink.gpsbridge.wallet

/**
 * How fast **this** transfer is going, and how long the rest of it will take.
 *
 * Why not the constant it replaces: `WalletTransport.bytesPerSecond` is a figure measured
 * once (8-9 kB/s for BLE) and the real link is nothing like a constant. Measured
 * 2026-08-20/21 on one phone and one device: 7.4 kB/s with the connection interval at
 * 15 ms, 3.9 kB/s at 30 ms, and **0.33 kB/s with the phone's screen off**. An estimate
 * built on a constant is wrong by a factor of twenty in the case a rider is most likely to
 * hit -- phone in a pocket -- and the old wording hid that by refusing to print a number
 * at all ("roughly a minute or two").
 *
 * So: measure the run, and say what the measurement implies. If the link degrades the
 * number gets worse, which is the whole point of showing it.
 *
 * Plain Kotlin, no Android types, clock injected: this is arithmetic a second client has
 * to reproduce, not platform glue (`CLAUDE.md`, "The phone app must stay portable to
 * iOS").
 */
class WalletSyncRate(private val clock: () -> Long = System::currentTimeMillis) {

    private var startedAtMs = 0L
    private var bytes = 0L
    private var lastProgressAtMs = 0L

    /** A new run. Nothing carries over: the link may be a different one. */
    fun start() {
        startedAtMs = clock()
        lastProgressAtMs = startedAtMs
        bytes = 0L
    }

    /** A file landed, confirmed by the device. Only confirmed bytes count. */
    fun confirmed(fileBytes: Int) {
        bytes += fileBytes.toLong()
        lastProgressAtMs = clock()
    }

    val confirmedBytes: Long get() = bytes

    /**
     * Bytes a second over this run, or null until there is enough to mean anything.
     *
     * The floor is deliberate: one 47 kB file landing in 6 s says 7.8 kB/s and the next
     * one might say 3.9, so a rate from a single small file would jump around more than
     * it informs. [MIN_BYTES] and [MIN_MILLIS] are both required.
     */
    fun bytesPerSecond(): Double? {
        if (startedAtMs == 0L || bytes < MIN_BYTES) return null
        val elapsed = clock() - startedAtMs
        if (elapsed < MIN_MILLIS) return null
        return bytes * 1000.0 / elapsed
    }

    /**
     * Seconds until [pendingBytes] have gone, from the measured rate, or null when there
     * is no measured rate yet.
     */
    fun secondsFor(pendingBytes: Long): Long? {
        val rate = bytesPerSecond() ?: return null
        if (pendingBytes <= 0L) return 0L
        return Math.round(pendingBytes / rate)
    }

    /** True when nothing has been confirmed for a while: an ETA would be a guess. */
    fun stalledFor(millis: Long): Boolean =
        startedAtMs != 0L && clock() - lastProgressAtMs >= millis

    companion object {
        const val MIN_BYTES = 32_768L
        const val MIN_MILLIS = 3_000L

        /** After this long with nothing confirmed, say so instead of counting down. */
        const val STALL_MS = 30_000L

        /**
         * `m:ss` above a minute, `NN s` below it. A real number, not a word: the words
         * this replaced could not tell two minutes from eight.
         */
        fun clock(seconds: Long): String = when {
            seconds < 60 -> "$seconds s"
            else -> "%d:%02d".format(seconds / 60, seconds % 60)
        }

        /** kB and MB, never a raw byte count, in anything a person reads. */
        fun rateText(bytesPerSecond: Double): String =
            if (bytesPerSecond >= 1048576) "%.1f MB/s".format(bytesPerSecond / 1048576)
            else "%.1f kB/s".format(bytesPerSecond / 1024)
    }
}
