package org.explorink.gpsbridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The golden files are produced by `mapbuilder/route_file.py`, the writer the
 * device has been fed by since 2026-08-06. Regenerate them with
 * `mapbuilder/tools/make_route_goldens.py` and never by hand: a fixture edited
 * to match the Kotlin is a test that asserts nothing.
 *
 * Byte-for-byte is the whole point. A header crc differing in one bit is a
 * route the picker lists as unreadable with nothing to say why
 * (`docs/route-file-spec.md`, "Two checksums").
 */
class RouteWriterTest {

    private val epoch = 1_700_000_000L

    private fun golden(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("route/$name")) {
            "missing golden fixture route/$name"
        }.use { it.readBytes() }

    private val mercPoints = listOf(
        RouteWriter.Merc(1900123.4, 6210987.6),
        RouteWriter.Merc(1900500.0, 6211000.5),
        RouteWriter.Merc(1901000.49, 6211999.5),
    )

    private val latLon = listOf(
        48.4372 to 17.0186,
        48.4400 to 17.0200,
        48.4430 to 17.0215,
        48.4461 to 17.0229,
        48.4490 to 17.0244,
        48.4521 to 17.0258,
    )

    @Test
    fun packsBytesIdenticalToThePythonWriter() {
        assertArrayEquals(golden("golden-basic.tir"), RouteWriter.pack(mercPoints, "Záhorie 2026", epoch))
    }

    @Test
    fun projectsAndSimplifiesIdenticallyToThePythonWriter() {
        // Projection, Douglas-Peucker and the half-to-even rounding all have to
        // agree at once: this fixture is the one that catches a lat/lon swap, a
        // clamped perpendicular distance or Math.round().
        val merc = RouteWriter.routeFromLatLon(latLon, 5.0)
        assertEquals(5, merc.size)
        assertArrayEquals(golden("golden-latlon.tir"), RouteWriter.pack(merc, "", epoch))
    }

    @Test
    fun cutsAnOverlongNameOnACharacterBoundary() {
        // 40 two-byte characters is 80 bytes against a 64-byte cap, so the cut
        // lands mid-character unless the writer decodes before it truncates.
        assertArrayEquals(golden("golden-longname.tir"), RouteWriter.pack(mercPoints, "Ľ".repeat(40), epoch))
    }

    @Test
    fun emptyNameStillPadsThePointArrayToFourBytes() {
        val packed = RouteWriter.pack(mercPoints, "", epoch)
        assertEquals(0, packed[6].toInt())           // name_len
        assertEquals(40 + 8 * 3, packed.size)        // points start at 40
    }

    @Test
    fun refusesARouteShorterThanTwoPoints() {
        val thrown = runCatching { RouteWriter.pack(mercPoints.take(1), "one", epoch) }.exceptionOrNull()
        assertTrue("expected a RouteFormatException, got $thrown", thrown is RouteWriter.RouteFormatException)
    }

    @Test
    fun keepsBothEndpointsWhateverTheTolerance() {
        // The device draws an arrowhead at the far end, so an endpoint dropped
        // by the simplifier moves where the route is said to finish.
        val merc = RouteWriter.routeFromLatLon(latLon, 100_000.0)
        assertEquals(2, merc.size)
        assertEquals(RouteWriter.project(48.4372, 17.0186), merc.first())
        assertEquals(RouteWriter.project(48.4521, 17.0258), merc.last())
    }

    @Test
    fun dropsAdjacentDuplicatesBeforeSimplifying() {
        val withDupes = listOf(48.4372 to 17.0186, 48.4372 to 17.0186, 48.4521 to 17.0258)
        assertEquals(2, RouteWriter.routeFromLatLon(withDupes, 5.0).size)
    }
}
