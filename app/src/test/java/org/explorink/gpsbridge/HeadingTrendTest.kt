package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * HeadingTrend is what stands between the map arrow and the phone's compass:
 * it must never turn the arrow just because the phone rotated in a backpack,
 * and it must hold the last known heading rather than guess when the recent
 * fixes don't add up to a trend yet.
 */
class HeadingTrendTest {

    /** Metres per degree of latitude/longitude, good enough near the equator
     * where these fixtures are placed so east/north stay orthogonal. */
    private val metresPerDeg = 111_320.0

    /** A fix [eastM]/[northM] from the origin, at [tSeconds] into the walk. */
    private fun pt(eastM: Double, northM: Double, tSeconds: Double) = HeadingTrend.Point(
        latDeg = northM / metresPerDeg,
        lonDeg = eastM / metresPerDeg,
        elapsedRealtimeNanos = (tSeconds * 1_000_000_000.0).toLong(),
    )

    @Test
    fun bearingDegOfCardinalDirections() {
        val origin = pt(0.0, 0.0, 0.0)
        assertEquals(0.0, HeadingTrend.bearingDeg(origin, pt(0.0, 50.0, 0.0)), 0.5)
        assertEquals(90.0, HeadingTrend.bearingDeg(origin, pt(50.0, 0.0, 0.0)), 0.5)
        assertEquals(180.0, HeadingTrend.bearingDeg(origin, pt(0.0, -50.0, 0.0)), 0.5)
        assertEquals(270.0, HeadingTrend.bearingDeg(origin, pt(-50.0, 0.0, 0.0)), 0.5)
    }

    @Test
    fun fewerThanTheWindowSizeIsNotATrendYet() {
        val points = listOf(pt(0.0, 0.0, 0.0), pt(0.0, 15.0, 1.0), pt(0.0, 30.0, 2.0))
        assertNull(HeadingTrend.heading(points))
    }

    @Test
    fun steadyTravelNorthIsAConfidentTrend() {
        // 15 m/leg, 1 s/leg -- 15 m/s, comfortably above the moving floor.
        val points = (0..4).map { pt(0.0, it * 15.0, it.toDouble()) }
        assertEquals(0.0, HeadingTrend.heading(points)!!, 0.5)
    }

    @Test
    fun stationaryJitterStaysBelowTheSpeedFloor() {
        // 0.1 m/leg over 1 s/leg -- GPS noise on a phone that isn't moving.
        val points = (0..4).map { pt(it * 0.1, it * 0.1, it.toDouble()) }
        assertNull(HeadingTrend.heading(points))
    }

    @Test
    fun fastButWanderingLegsAreNotAConsistentTrend() {
        // Net displacement is fast enough to pass the speed floor, but the
        // individual legs point every which way -- a phone getting bounced
        // around, not one holding a direction.
        val points = listOf(
            pt(0.0, 0.0, 0.0),
            pt(20.0, 20.0, 1.0),
            pt(20.0, 60.0, 2.0),
            pt(60.0, 60.0, 3.0),
            pt(60.0, 100.0, 4.0),
        )
        assertNull(HeadingTrend.heading(points))
    }

    @Test
    fun onlyTheLastWindowSizePointsAreConsidered() {
        // An old, wildly inconsistent point far outside the window must not
        // spoil an otherwise clean, fast, straight recent trend.
        val stale = pt(500.0, -500.0, -100.0)
        val points = listOf(stale) + (0..4).map { pt(0.0, it * 15.0, it.toDouble()) }
        assertEquals(0.0, HeadingTrend.heading(points)!!, 0.5)
    }
}
