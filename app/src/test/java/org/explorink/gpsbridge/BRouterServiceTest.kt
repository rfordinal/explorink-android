package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two pure halves: the request URL and the answer. No network here. */
class BRouterServiceTest {

    private val stops = listOf(
        RouteLink.Stop(48.1574598, 17.0477014, null),
        RouteLink.Stop(48.1955534, 17.0156718, "First"),
        RouteLink.Stop(48.1514085, 17.1299005, "Second"),
    )

    @Test
    fun theRequestPutsLongitudeFirst() {
        // The one place in this app where the order flips. A swap here does not
        // fail -- it routes somewhere else entirely.
        val url = BRouterService.buildUrl("https://example.test/brouter", stops, RouteLink.Mode.CAR)
        assertTrue(
            url,
            url.startsWith(
                "https://example.test/brouter?lonlats=" +
                    "17.047701,48.157460|17.015672,48.195553|17.129901,48.151409",
            ),
        )
    }

    @Test
    fun theProfileComesFromTheLinkSTravelMode() {
        assertEquals("trekking", BRouterService.profileFor(RouteLink.Mode.BICYCLE))
        assertEquals("hiking-mountain", BRouterService.profileFor(RouteLink.Mode.WALK))
        assertEquals("car-vario", BRouterService.profileFor(RouteLink.Mode.CAR))
        // No router for transit here: it routes as a car and the screen says so.
        assertEquals("car-vario", BRouterService.profileFor(RouteLink.Mode.TRANSIT))
    }

    @Test
    fun readsTheLineOutOfGeoJson() {
        val body = """
            {"type":"FeatureCollection","features":[{"type":"Feature",
             "properties":{"track-length":"4210"},
             "geometry":{"type":"LineString","coordinates":
               [[17.047701,48.157460],[17.048100,48.158000],[17.049000,48.159000]]}}]}
        """.trimIndent()
        val result = BRouterService.parseGeoJson(body)
        assertTrue("$result", result is RouteService.Result.Ok)
        val line = (result as RouteService.Result.Ok).latLon
        assertEquals(3, line.size)
        // Back to latitude first, the order the rest of the app speaks.
        assertEquals(48.15746, line.first().first, 1e-9)
        assertEquals(17.047701, line.first().second, 1e-9)
    }

    @Test
    fun aTwoPointAnswerIsNotALine() {
        // A router that hands back only the endpoints has not routed, and a
        // straight line is the one thing this feature must never draw.
        val body = """{"features":[{"geometry":{"coordinates":[[17.0,48.0]]}}]}"""
        assertTrue(BRouterService.parseGeoJson(body) is RouteService.Result.Failed)
    }

    @Test
    fun junkIsAFailureWithAReasonRatherThanAnException() {
        assertTrue(BRouterService.parseGeoJson("<html>503</html>") is RouteService.Result.Failed)
        assertTrue(BRouterService.parseGeoJson("""{"ok":true}""") is RouteService.Result.Failed)
    }
}
