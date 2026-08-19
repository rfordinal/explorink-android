package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The phone's distance must read the same as the panel's.
 *
 * The device's own numbers are checked against haversine within 1 %
 * (`firmware/explorink/test/pins/PinsTest.cpp`), so this checks the two things
 * that would make the phone disagree with the *device*: the projection, and the
 * rounding brackets.
 */
class PinGeoTest {

    @Test
    fun `distance does not depend on the argument order`() {
        // The cosine is taken at the midpoint latitude for exactly this reason.
        val a = PinGeo.distanceM(484372000, 170186000, 500000000, 143000000)
        val b = PinGeo.distanceM(500000000, 143000000, 484372000, 170186000)
        assertEquals(a, b)
    }

    @Test
    fun `a degree of latitude is the constant the device uses`() {
        val m = PinGeo.distanceM(480000000, 170000000, 490000000, 170000000)
        assertEquals(111_320L, m)
    }

    @Test
    fun `two points either side of the antimeridian are close, not half a world apart`() {
        val m = PinGeo.distanceM(0, 1799000000, 0, -1799000000)
        assertTrue("got $m", m < 30_000L)
    }

    @Test
    fun `a known separation lands within a percent of haversine`() {
        // 48.4372,17.0186 to 48.5,17.1: haversine on a 6,371,008.8 m sphere,
        // computed off-device (9,207.5 m). This projection answers 9,218 m -- 0.11 %
        // out, which is the degree constant's own disagreement with the mean radius
        // and is far below the 10 m the printed value is rounded to.
        val haversine = 9_207.5
        val m = PinGeo.distanceM(484372000, 170186000, 485000000, 171000000)
        assertTrue("got $m", abs(m - haversine) / haversine < 0.01)
    }

    @Test
    fun `the brackets are the device's brackets`() {
        assertEquals("820 m", PinGeo.formatDistance(824))
        assertEquals("0 m", PinGeo.formatDistance(0))
        // 999 m must not print as "1000 m": two spellings of one kilometre in one
        // list is how a rider decides the numbers cannot be trusted.
        assertEquals("1.0 km", PinGeo.formatDistance(999))
        assertEquals("4.2 km", PinGeo.formatDistance(4_237))
        assertEquals("10 km", PinGeo.formatDistance(10_000))
        assertEquals("37 km", PinGeo.formatDistance(36_700))
    }
}
