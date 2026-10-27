package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures mirror the **shape** measured on 2026-10-27 from a real share
 * (`docs/route-share-plan.md`, "What the capture found") with invented
 * coordinates, names and feature ids: a real `dir` link carries where the rider
 * actually rode, and this repository is public.
 *
 * Still missing, and it is why one test below is about alignment rather than a
 * captured file: a link mixing a typed coordinate with picked places has not
 * been captured yet (T-133). The rule under test -- walk the path, a named
 * segment takes the next unused group -- is read off a single example.
 */
class RouteLinkTest {

    /** The captured shape: origin as coordinates, then two picked places. */
    private val phoneShare =
        "https://www.google.com/maps/dir/48.1000000,17.1000000/Villa+Name/Other+Place/data=" +
            "!4m19!4m18!1m1!4e1" +
            "!1m5!1m4!1s0x476c000000000001:0x1111111111111111!8m2!3d48.2000000!4d17.2000000" +
            "!1m5!1m4!1s0x476c000000000002:0x2222222222222222!8m2!3d48.3000000!4d17.3000000" +
            "!2m2!2b1!11b1!3e0?utm_source=mstt_0"

    private fun route(url: String): RouteLink.Result.Route {
        val r = RouteLink.parse(url)
        assertTrue("expected a route, got $r", r is RouteLink.Result.Route)
        return r as RouteLink.Result.Route
    }

    @Test
    fun readsTheStopsOutOfAPhoneShare() {
        val r = route(phoneShare)
        assertEquals(3, r.stops.size)
        assertEquals(listOf(48.1, 48.2, 48.3), r.stops.map { it.lat })
        assertEquals(listOf(17.1, 17.2, 17.3), r.stops.map { it.lon })
        assertEquals(listOf(null, "Villa Name", "Other Place"), r.stops.map { it.label })
        assertEquals(RouteLink.Mode.CAR, r.mode)
        assertTrue(r.unresolved.isEmpty())
    }

    @Test
    fun alignsGroupsByPositionWhenATypedCoordinateSitsBetweenPickedPlaces() {
        // The case T-133 is still owed a capture for. Position is the only rule
        // that can work: the blob carries feature ids, never names, so a named
        // segment can only be matched to a group by counting.
        val mixed =
            "https://www.google.com/maps/dir/First+Place/48.5000000,17.5000000/Last+Place/data=" +
                "!4m19!1m5!1m4!1s0x1:0x1!8m2!3d48.4000000!4d17.4000000" +
                "!1m5!1m4!1s0x2:0x2!8m2!3d48.6000000!4d17.6000000!3e2"
        val r = route(mixed)
        assertEquals(listOf(48.4, 48.5, 48.6), r.stops.map { it.lat })
        assertEquals(listOf("First Place", null, "Last Place"), r.stops.map { it.label })
        assertEquals(RouteLink.Mode.WALK, r.mode)
    }

    @Test
    fun aNamedStopWithNoGroupBehindItIsReportedNotGuessed() {
        // Four named segments, one group: the rest need a geocoder, and the
        // caller has to see which ones before anything is pushed.
        val short =
            "https://www.google.com/maps/dir/A+Town/B+Town/C+Town/data=" +
                "!1m5!1m4!1s0x1:0x1!8m2!3d48.1000000!4d17.1000000!3e0"
        val r = route(short)
        assertEquals(1, r.stops.size)
        assertEquals(listOf("B Town", "C Town"), r.unresolved)
    }

    @Test
    fun readsTheDocumentedApiForm() {
        val api = "https://www.google.com/maps/dir/?api=1&origin=48.1,17.1" +
            "&waypoints=48.2,17.2%7C48.3,17.3&destination=48.4,17.4&travelmode=bicycling"
        val r = route(api)
        assertEquals(listOf(48.1, 48.2, 48.3, 48.4), r.stops.map { it.lat })
        assertEquals(RouteLink.Mode.BICYCLE, r.mode)
    }

    @Test
    fun aPlaceShareIsNotARoute() {
        val place = "https://www.google.com/maps/place/48.1,17.1/data=!3m1!4b1"
        val r = RouteLink.parse(place)
        assertTrue("expected NotARoute, got $r", r is RouteLink.Result.NotARoute)
    }

    @Test
    fun oneStopIsNotARouteEither() {
        val one = "https://www.google.com/maps/dir/Single+Place/data=" +
            "!1m5!1m4!1s0x1:0x1!8m2!3d48.1000000!4d17.1000000!3e0"
        val r = RouteLink.parse(one)
        assertTrue("expected NotARoute, got $r", r is RouteLink.Result.NotARoute)
    }

    @Test
    fun aShortLinkIsRefusedRatherThanGuessedAt() {
        // The coordinates are not in the text at all; MapsShortLink resolves it
        // first. Same refusal PinCoordinates makes, for the same reason.
        val r = RouteLink.parse("https://maps.app.goo.gl/abcdef")
        assertTrue("expected NotARoute, got $r", r is RouteLink.Result.NotARoute)
    }

    @Test
    fun aCoordinateOutsideTheWorldKillsTheWholeParse() {
        val bad = "https://www.google.com/maps/dir/91.0,17.1/48.2,17.2/data=!3e0"
        val r = RouteLink.parse(bad)
        assertTrue("expected Unreadable, got $r", r is RouteLink.Result.Unreadable)
    }

    @Test
    fun theCameraCentreIsNotAStop() {
        // `@lat,lon,zoom` is where the view sat, not a point of the route.
        val withCamera =
            "https://www.google.com/maps/dir/48.1,17.1/48.2,17.2/@48.15,17.15,12z/data=!3e0"
        val r = route(withCamera)
        assertEquals(2, r.stops.size)
    }

    @Test
    fun aModeTheLinkDoesNotStateIsUnknownRatherThanCar() {
        val noMode = "https://www.google.com/maps/dir/48.1,17.1/48.2,17.2/data=!4m2"
        assertEquals(RouteLink.Mode.UNKNOWN, route(noMode).mode)
    }
}
