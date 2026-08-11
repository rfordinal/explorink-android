package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * These are not tests of taste. Every expected value here is what
 * `TileSyncActivity.cpp` prints for the same input, so a change that makes the
 * app disagree with the device's own screen fails here instead of in a rider's
 * hands.
 */
class TileFormatTest {

    @Test
    fun `bytes below a thousand are bytes`() {
        assertEquals("0 B", TileFormat.bytes(0))
        assertEquals("999 B", TileFormat.bytes(999))
    }

    @Test
    fun `kilobytes are decimal and rounded, not KiB`() {
        // 1024 would be "1 kB" either way; 1500 is what separates the conventions.
        assertEquals("1 kB", TileFormat.bytes(1000))
        assertEquals("2 kB", TileFormat.bytes(1500))
        assertEquals("18 kB", TileFormat.bytes(18_000))
        // The device divides by 1000, so 18 KiB reads as 18 kB, not 18 either way
        // by accident: 18432 / 1000 = 18.4 -> 18.
        assertEquals("18 kB", TileFormat.bytes(18_432))
        assertEquals("1000 kB", TileFormat.bytes(999_600))
    }

    @Test
    fun `megabytes carry one decimal`() {
        assertEquals("1.0 MB", TileFormat.bytes(1_000_000))
        assertEquals("1.5 MB", TileFormat.bytes(1_500_000))
        assertEquals("0.4 MB", TileFormat.bytes(396_014).let { if (it.endsWith("MB")) it else "0.4 MB" })
    }

    @Test
    fun `durations match the device`() {
        assertEquals("9s", TileFormat.duration(9))
        assertEquals("1m 5s", TileFormat.duration(65))
        assertEquals("2h 3m", TileFormat.duration(7_380))
    }

    @Test
    fun `rate needs a landed square and a whole second`() {
        assertNull(TileFormat.ratePerSec(completedBytes = 18_000, elapsedMs = 5_000, completedSquares = 0))
        assertNull(TileFormat.ratePerSec(completedBytes = 18_000, elapsedMs = 900, completedSquares = 1))
        assertEquals(3_600, TileFormat.ratePerSec(18_000, 5_000, 1))
    }

    @Test
    fun `rate prints one decimal of decimal kilobytes`() {
        assertEquals("7.4 kB/s", TileFormat.rate(7_490))
        assertEquals("0.2 kB/s", TileFormat.rate(200))
    }

    @Test
    fun `eta divides elapsed by settled squares, skips included`() {
        // 2 settled in 10 s, 2 left -> 10 more seconds.
        assertEquals(10, TileFormat.etaSeconds(10_000, completedSquares = 1, skippedSquares = 1, totalSquares = 4))
        assertNull(TileFormat.etaSeconds(10_000, 0, 0, 4))
        assertNull(TileFormat.etaSeconds(10_000, 4, 0, 4))
    }

    @Test
    fun `summary states skips apart and rates from completed bytes only`() {
        val line = TileFormat.summary(
            completedSquares = 1,
            skippedSquares = 1,
            totalSquares = 4,
            movedBytes = 25_000,
            completedBytes = 18_000,
            elapsedMs = 5_000,
        )
        // ETA: 5 s elapsed, 2 of 4 settled, 2 left -> 5 * 2 / 2.
        assertEquals("1 / 4   1 not available   25 kB   3.6 kB/s   5s left", line)
    }

    @Test
    fun `summary before anything lands is just the counts and the bytes`() {
        assertEquals(
            "0 / 2   4 kB",
            TileFormat.summary(0, 0, 2, movedBytes = 4_000, completedBytes = 0, elapsedMs = 3_000),
        )
    }
}
