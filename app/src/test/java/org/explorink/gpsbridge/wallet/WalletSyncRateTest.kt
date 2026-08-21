package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The estimate a rider reads while waiting.
 *
 * It exists because the old one was a constant dressed as knowledge. `bytesPerSecond` is
 * 8-9 kB/s for BLE, measured once, so 746 kB printed as "roughly a minute or two" -- and
 * the link at that moment was doing 0.33 kB/s with the phone's screen off, which is
 * **thirty-eight minutes**. The words were not vague, they were wrong, and the vagueness
 * hid it.
 *
 * So these pin three things: the arithmetic, that it refuses to answer before it has
 * measured anything, and that a stalled transfer says so instead of counting down.
 */
class WalletSyncRateTest {

    private class FakeClock(var now: Long = 1_000L) : () -> Long {
        override fun invoke(): Long = now
        fun advance(ms: Long) { now += ms }
    }

    @Test
    fun it_says_nothing_until_it_has_measured_enough() {
        val clock = FakeClock()
        val r = WalletSyncRate(clock)
        assertNull("nothing before a run starts", r.bytesPerSecond())
        r.start()
        assertNull("nothing at the start of a run", r.bytesPerSecond())

        // One small file is not a measurement: 47 kB in 6 s reads 7.8 kB/s and the next
        // one might read 3.9.
        r.confirmed(20_000)
        clock.advance(4_000)
        assertNull("under the byte floor", r.bytesPerSecond())

        r.confirmed(20_000)
        assertTrue("40 kB over 4 s is enough", r.bytesPerSecond()!! > 0)
    }

    @Test
    fun the_rate_is_bytes_over_the_elapsed_run() {
        val clock = FakeClock()
        val r = WalletSyncRate(clock)
        r.start()
        r.confirmed(48_032)
        r.confirmed(48_032)
        clock.advance(10_000)
        // 96,064 B in 10 s
        assertEquals(9606.4, r.bytesPerSecond()!!, 1.0)
    }

    @Test
    fun the_measured_rate_is_what_decides_the_eta_not_a_constant() {
        val clock = FakeClock()
        val r = WalletSyncRate(clock)
        r.start()
        // The screen-off case, measured on hardware: 0.33 kB/s.
        r.confirmed(48_032)
        clock.advance(142_000)
        val secs = r.secondsFor(746_352)!!
        assertTrue("746 kB at this rate is over half an hour, not two minutes: ${secs}s",
            secs > 1_800)
        // And the old constant would have said about 88 seconds for the same bytes.
        assertTrue(746_352 / 8_500 < 100)
    }

    @Test
    fun a_faster_link_gives_a_shorter_answer_from_the_same_bytes() {
        val clock = FakeClock()
        val r = WalletSyncRate(clock)
        r.start()
        r.confirmed(144_032)
        clock.advance(19_000)          // ~7.6 kB/s, the fixed-interval case
        val secs = r.secondsFor(746_352)!!
        assertTrue("$secs s", secs in 90..110)
    }

    @Test
    fun nothing_pending_is_zero_and_not_an_absent_answer() {
        val clock = FakeClock()
        val r = WalletSyncRate(clock)
        r.start()
        r.confirmed(48_032)
        clock.advance(6_000)
        assertEquals(0L, r.secondsFor(0))
    }

    @Test
    fun a_transfer_that_stopped_confirming_is_reported_as_stalled() {
        val clock = FakeClock()
        val r = WalletSyncRate(clock)
        r.start()
        r.confirmed(48_032)
        clock.advance(5_000)
        assertTrue(!r.stalledFor(WalletSyncRate.STALL_MS))
        clock.advance(WalletSyncRate.STALL_MS)
        assertTrue("a countdown here would be a fiction", r.stalledFor(WalletSyncRate.STALL_MS))
    }

    @Test
    fun a_new_run_forgets_the_old_ones_rate() {
        val clock = FakeClock()
        val r = WalletSyncRate(clock)
        r.start()
        r.confirmed(500_000)
        clock.advance(5_000)
        val fast = r.bytesPerSecond()!!
        r.start()
        assertNull("the interval, the screen state and the distance may all differ now",
            r.bytesPerSecond())
        assertEquals(0L, r.confirmedBytes)
        assertTrue(fast > 0)
    }

    @Test
    fun the_texts_read_as_numbers_and_use_human_units() {
        assertEquals("45 s", WalletSyncRate.clock(45))
        assertEquals("1:00", WalletSyncRate.clock(60))
        assertEquals("2:05", WalletSyncRate.clock(125))
        assertEquals("38:20", WalletSyncRate.clock(2300))
        assertEquals("7.4 kB/s", WalletSyncRate.rateText(7.4 * 1024))
        assertEquals("0.3 kB/s", WalletSyncRate.rateText(340.0))
        assertEquals("1.5 MB/s", WalletSyncRate.rateText(1.5 * 1048576))
    }
}
