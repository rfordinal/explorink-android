package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareRoutingTest {

    @Test
    fun aDirectionsLinkIsARoute() {
        assertEquals(
            ShareRouting.Target.ROUTE,
            ShareRouting.classify("https://www.google.com/maps/dir/48.1,17.1/A+Place/data=!3e0"),
        )
    }

    @Test
    fun theDocumentedApiFormIsARouteToo() {
        assertEquals(
            ShareRouting.Target.ROUTE,
            ShareRouting.classify("https://www.google.com/maps/dir/?api=1&origin=48.1,17.1&destination=48.2,17.2"),
        )
    }

    @Test
    fun aShortLinkIsResolvedBeforeItIsClassified() {
        // Three characters of the text say nothing about what is behind it: a
        // short link expands to a place or to directions and only the redirect
        // tells which.
        assertEquals(
            ShareRouting.Target.RESOLVE_FIRST,
            ShareRouting.classify("https://maps.app.goo.gl/abcdef"),
        )
    }

    @Test
    fun aCoordinateIsAPin() {
        assertEquals(ShareRouting.Target.PIN, ShareRouting.classify("48.4372, 17.0186"))
        assertEquals(ShareRouting.Target.PIN, ShareRouting.classify("48°09'05.4\"N 17°07'47.1\"E"))
    }

    @Test
    fun aPlaceLinkIsAPinEvenWhenItCarriesNoCoordinates() {
        // The pins screen is where "that is a named place, not a dropped pin"
        // gets explained; silently dropping it here would say nothing at all.
        assertEquals(
            ShareRouting.Target.PIN,
            ShareRouting.classify("https://www.google.com/maps/place/Example+Beach/data=!4m2!3m1!1s0x1:0x2"),
        )
    }

    @Test
    fun nonsenseIsUnknownRatherThanAScreen() {
        assertEquals(ShareRouting.Target.UNKNOWN, ShareRouting.classify("hello"))
        assertEquals(ShareRouting.Target.UNKNOWN, ShareRouting.classify(""))
        assertEquals(ShareRouting.Target.UNKNOWN, ShareRouting.classify(null))
    }
}
