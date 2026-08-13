package org.explorink.gpsbridge

/**
 * How long [BleLink] should wait before retrying a scan that just failed.
 *
 * A scan failure is not terminal: the rider still sees FAILED, but the retry
 * itself must not be the thing that fails again. Two cases, different reasons:
 *
 *  - Code 6 (`android.bluetooth.le.ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY`)
 *    is Android's scan-start throttle -- 5 starts inside a rolling 30 s window
 *    trips it (AOSP `ScanManager`). [BleLink]'s fast-scan downshift
 *    (`scanDownshift`, one stop+start) plus a reconnect rescan can add up to
 *    this inside one window, so a retry that lands *inside* the window that
 *    tripped the failure just becomes the 6th start and trips it again. 35 s
 *    clears any 30 s window that could have been open when the failure
 *    landed -- see [BleLink] for the arithmetic that makes this exact.
 *  - Every other code has no rate window to clear -- 5 s is just "give the
 *    stack a moment" -- with exponential backoff (doubling per repeated
 *    failure, capped at 60 s) in case the cause is sticky rather than a
 *    one-off.
 *
 * Kept free of Android types on purpose -- this is the part with the
 * reasoning in it, so it is the part that gets unit-tested.
 */
object ScanRetryPolicy {

    /** Mirrors `android.bluetooth.le.ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY`. */
    const val SCAN_FAILED_SCANNING_TOO_FREQUENTLY = 6

    /** Must clear Android's 5-starts/30 s throttle window -- see class doc. */
    const val CODE6_BASE_MS = 35_000L

    /** No rate window to clear for any other code -- just a short breather. */
    const val OTHER_BASE_MS = 5_000L

    /** Backoff never grows past this, however long the failure streak. */
    const val MAX_DELAY_MS = 60_000L

    /**
     * @param errorCode the code `ScanCallback.onScanFailed` reported.
     * @param streak scan failures in a row with no successful start between
     *   them; 0 for the first failure of a fresh streak.
     * @return delay in ms before the next retry should call [BleLink.start].
     */
    fun delayMs(errorCode: Int, streak: Int): Long {
        val base = if (errorCode == SCAN_FAILED_SCANNING_TOO_FREQUENTLY) CODE6_BASE_MS else OTHER_BASE_MS
        // Capped before the shift, not just after: shl with an uncapped streak
        // (a long flapping run) would eventually overflow the Long instead of
        // just producing a big number coerceAtMost could clamp.
        val shift = streak.coerceIn(0, 6)
        return (base shl shift).coerceAtMost(MAX_DELAY_MS)
    }
}
