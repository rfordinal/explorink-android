package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The scan-retry delay is what stands between a phone in a pocket and a link
 * dead until manual Retry (`docs/ble-review-2026-08.md`, "Stability -- app",
 * `onScanFailed` is terminal). The arithmetic that matters most: code 6's 35 s
 * base must be long enough that the retry itself cannot land inside the 30 s
 * throttle window that produced the failure.
 */
class ScanRetryPolicyTest {

    @Test
    fun code6FirstFailureWaits35s() {
        assertEquals(
            35_000L,
            ScanRetryPolicy.delayMs(
                errorCode = ScanRetryPolicy.SCAN_FAILED_SCANNING_TOO_FREQUENTLY,
                streak = 0,
            ),
        )
    }

    @Test
    fun code6RetryDelayClearsAndroidsThrottleWindow() {
        // Android's SCANNING_TOO_FREQUENTLY is 5 starts inside a rolling 30 s
        // window. A retry delay has to be longer than that window, or the
        // retry's own start can land inside the window that tripped the
        // original failure and become a 6th start in it.
        val delay = ScanRetryPolicy.delayMs(ScanRetryPolicy.SCAN_FAILED_SCANNING_TOO_FREQUENTLY, streak = 0)
        val androidThrottleWindowMs = 30_000L
        org.junit.Assert.assertTrue(
            "35 s retry must clear the 30 s window, not just approach it",
            delay > androidThrottleWindowMs,
        )
    }

    @Test
    fun otherCodeFirstFailureWaits5s() {
        assertEquals(5_000L, ScanRetryPolicy.delayMs(errorCode = 3, streak = 0))
    }

    @Test
    fun otherCodeBacksOffExponentially() {
        assertEquals(5_000L, ScanRetryPolicy.delayMs(3, streak = 0))
        assertEquals(10_000L, ScanRetryPolicy.delayMs(3, streak = 1))
        assertEquals(20_000L, ScanRetryPolicy.delayMs(3, streak = 2))
        assertEquals(40_000L, ScanRetryPolicy.delayMs(3, streak = 3))
    }

    @Test
    fun otherCodeBackoffCapsAt60s() {
        // 5_000 << 4 == 80_000, past the cap.
        assertEquals(60_000L, ScanRetryPolicy.delayMs(3, streak = 4))
        assertEquals(60_000L, ScanRetryPolicy.delayMs(3, streak = 5))
    }

    @Test
    fun code6BackoffAlsoCapsAt60s() {
        // 35_000 << 1 == 70_000, past the cap.
        assertEquals(
            60_000L,
            ScanRetryPolicy.delayMs(ScanRetryPolicy.SCAN_FAILED_SCANNING_TOO_FREQUENTLY, streak = 1),
        )
    }

    @Test
    fun aLongFlappingStreakStaysCappedAndDoesNotOverflow() {
        assertEquals(
            60_000L,
            ScanRetryPolicy.delayMs(ScanRetryPolicy.SCAN_FAILED_SCANNING_TOO_FREQUENTLY, streak = 1_000),
        )
        assertEquals(60_000L, ScanRetryPolicy.delayMs(3, streak = 1_000))
    }
}
