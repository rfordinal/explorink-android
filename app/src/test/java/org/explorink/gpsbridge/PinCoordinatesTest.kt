package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the rider may paste into the coordinate field.
 *
 * The failures matter as much as the successes: this is the one place in the pins
 * path where the app decides *where* a pin goes, and a wrong guess sends a rider
 * to a place they never chose.
 */
class PinCoordinatesTest {

    private fun parsed(text: String): PinCoordinates.Result.Parsed {
        val r = PinCoordinates.parse(text)
        assertTrue("refused: $text", r is PinCoordinates.Result.Parsed)
        return r as PinCoordinates.Result.Parsed
    }

    private fun reason(text: String): String {
        val r = PinCoordinates.parse(text)
        assertTrue("accepted: $text", r is PinCoordinates.Result.Failure)
        return (r as PinCoordinates.Result.Failure).reason
    }

    @Test
    fun `a bare pair, however it is separated`() {
        for (text in listOf("48.4372, 17.0186", "48.4372,17.0186", "48.4372 17.0186", "48.4372; 17.0186")) {
            val p = parsed(text)
            assertEquals(484372000, p.latE7)
            assertEquals(170186000, p.lonE7)
        }
    }

    @Test
    fun `a negative pair keeps both signs`() {
        val p = parsed("-33.8688, 151.2093")
        assertEquals(-338688000, p.latE7)
        assertEquals(1512093000, p.lonE7)
    }

    @Test
    fun `a geo link`() {
        val p = parsed("geo:48.4372,17.0186?z=15")
        assertEquals(484372000, p.latE7)
    }

    @Test
    fun `a maps q parameter`() {
        val p = parsed("https://maps.google.com/?q=48.4372,17.0186")
        assertEquals(484372000, p.latE7)
        assertEquals(170186000, p.lonE7)
    }

    @Test
    fun `the place pair wins over the camera centre`() {
        // `@` is where the view happened to be; `!3d/!4d` is the place that was
        // looked up. On a place link they differ by however far the map was dragged.
        val p = parsed(
            "https://www.google.com/maps/place/Camp/@48.4000000,17.0000000,17z/" +
                "data=!3m1!4b1!4m6!3m5!1s0x476c:0x2f!8m2!3d48.4372000!4d17.0186000"
        )
        assertEquals(484372000, p.latE7)
        assertEquals(170186000, p.lonE7)
    }

    @Test
    fun `a short link is refused, because the coordinates are not in it`() {
        val reason = reason("https://maps.app.goo.gl/abc123")
        assertTrue(reason, reason.contains("short"))
    }

    @Test
    fun `degrees minutes seconds is refused rather than half read`() {
        val reason = reason("48°26'13.9\"N 17°01'06.9\"E")
        assertTrue(reason, reason.contains("Degrees, minutes"))
    }

    @Test
    fun `out of range is refused before anything is sent`() {
        assertTrue(reason("91.0, 17.0").contains("Latitude"))
        assertTrue(reason("48.0, 181.0").contains("Longitude"))
    }

    @Test
    fun `text with no pair in it`() {
        assertTrue(reason("Meet me at the second bridge").contains("No coordinates"))
        assertTrue(reason("   ").contains("Nothing pasted"))
    }
}
