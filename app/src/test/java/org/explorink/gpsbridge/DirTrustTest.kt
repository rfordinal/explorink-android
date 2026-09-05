package org.explorink.gpsbridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The heading-quality bits, and the trend spread behind them.
 *
 * The compatibility rule is the one worth guarding: an app that never sets
 * these bits writes zero, and zero has to keep meaning "said nothing" rather
 * than "bad", or every phone still on an old build would suddenly make the
 * device draw an alarm.
 */
class DirTrustTest {

    @Test
    fun unstatedIsZeroSoAnOldBuildIsUnchanged() {
        assertEquals(0, PositionPacket.DirTrust.UNSTATED)
        val bytes = PositionPacket.build(
            latDeg = 0.0,
            lonDeg = 0.0,
            utcSeconds = 0L,
            tzOffsetMinutes = 0,
            heading = 0,
            seq = 0,
            flags = PositionPacket.withDirTrust(0, PositionPacket.DirTrust.UNSTATED),
            accuracyMetres = 0.0,
            speedKmh = 0.0,
        )
        assertEquals(0, bytes[16].toInt())
    }

    @Test
    fun theBitsLandInTheirOwnTwoPlacesAndLeaveTheOthersAlone() {
        assertEquals(0x04, PositionPacket.withDirTrust(0, PositionPacket.DirTrust.GOOD))
        assertEquals(0x08, PositionPacket.withDirTrust(0, PositionPacket.DirTrust.COARSE))
        assertEquals(0x0C, PositionPacket.withDirTrust(0, PositionPacket.DirTrust.UNKNOWN))
        // Altitude's bit1 and the off-route bit0 must survive untouched.
        assertEquals(0x0B, PositionPacket.withDirTrust(0x03, PositionPacket.DirTrust.COARSE))
        // And setting it twice replaces rather than accumulates.
        val once = PositionPacket.withDirTrust(0x02, PositionPacket.DirTrust.UNKNOWN)
        assertEquals(0x02 or 0x0C, once)
        assertEquals(0x02 or 0x04, PositionPacket.withDirTrust(once, PositionPacket.DirTrust.GOOD))
    }

    @Test
    fun altitudeStillSetsItsOwnBitAlongsideTheQualityBits() {
        val bytes = PositionPacket.build(
            latDeg = 0.0,
            lonDeg = 0.0,
            utcSeconds = 0L,
            tzOffsetMinutes = 0,
            heading = 0,
            seq = 0,
            flags = PositionPacket.withDirTrust(0, PositionPacket.DirTrust.COARSE),
            accuracyMetres = 0.0,
            speedKmh = 0.0,
            altitudeMetres = 512.0,
        )
        assertEquals(0x02 or 0x08, bytes[16].toInt())
        assertEquals(512, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(19).toInt())
        assertEquals(PositionPacket.SIZE, bytes.size)
    }

    // A straight line east, one second and about 11 m apart: a confident trend
    // with essentially no leg-to-leg disagreement.
    private fun straightLine(): List<HeadingTrend.Point> =
        (0 until HeadingTrend.WINDOW_SIZE).map {
            HeadingTrend.Point(48.0, 17.0 + it * 0.00015, it * 1_000_000_000L)
        }

    @Test
    fun aStraightRunAgreesWithItselfAndEarnsASharpArrow() {
        val trend = HeadingTrend.trend(straightLine())
        assertNotNull(trend)
        assertEquals(90.0, trend!!.bearingDeg, 1.0)
        // Well inside one 22.5 degree heading step, which is the finest thing
        // the device can draw.
        assertTrue("spread was ${trend.spreadDeg}", trend.spreadDeg < 22.5)
    }

    @Test
    fun aZigZagStillCountsAsATrendButAWiderOne() {
        // Same net direction, but each leg wanders off it -- the case a wedge
        // exists for.
        val points = (0 until HeadingTrend.WINDOW_SIZE).map {
            // About 3.3 m either side of a leg that runs 11 m east, which puts
            // each leg roughly 31 degrees off the overall trend: past one
            // heading step, still inside the window's own gate.
            val wobble = if (it % 2 == 0) 0.00003 else -0.00003
            HeadingTrend.Point(48.0 + wobble, 17.0 + it * 0.00015, it * 1_000_000_000L)
        }
        val trend = HeadingTrend.trend(points)
        assertNotNull(trend)
        assertTrue("spread was ${trend!!.spreadDeg}", trend.spreadDeg > 22.5)
        assertTrue(trend.spreadDeg <= HeadingTrend.MAX_BEARING_SPREAD_DEG)
    }

    @Test
    fun trendAndHeadingGateIdentically() {
        // A caller must never be able to get a Trend where heading() would have
        // said no -- the wedge would then be drawn on a window the app itself
        // does not believe.
        val parked = (0 until HeadingTrend.WINDOW_SIZE).map {
            HeadingTrend.Point(48.0, 17.0, it * 1_000_000_000L)
        }
        assertNull(HeadingTrend.trend(parked))
        assertNull(HeadingTrend.heading(parked))

        val moving = straightLine()
        assertEquals(HeadingTrend.heading(moving), HeadingTrend.trend(moving)!!.bearingDeg)
    }
}
