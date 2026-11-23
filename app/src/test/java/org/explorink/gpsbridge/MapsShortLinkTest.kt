package org.explorink.gpsbridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that decides whether the app makes a request at all.
 *
 * Only the pure half is tested here. Expanding a link needs the network, and the
 * one real expansion this was built from is recorded in [MapsShortLink]'s own
 * comment: a `302` whose `Location` carries `!3d49.9367636!4d17.9027618`.
 */
class MapsShortLinkTest {

    @Test
    fun `a maps share link is recognised`() {
        // The exact shape Google Maps' share sheet produced on a Galaxy S24
        // Ultra, 2026-09-02.
        assertTrue(MapsShortLink.isShortLink("https://maps.app.goo.gl/eYjBRiMipG8BLdok8"))
        assertTrue(MapsShortLink.isShortLink("  https://maps.app.goo.gl/abc123  "))
        assertTrue(MapsShortLink.isShortLink("https://goo.gl/maps/abc"))
    }

    /**
     * The whole point of matching on the host: a rule that fired on any URL would
     * send a request the rider never asked for, at a host they never named.
     */
    @Test
    fun `anything that is not a maps shortener is left alone`() {
        assertFalse(MapsShortLink.isShortLink("https://example.com/maps/abc"))
        assertFalse(MapsShortLink.isShortLink("https://goo.gl/somethingelse"))
        assertFalse(MapsShortLink.isShortLink("48.1656, 16.8532"))
        assertFalse(MapsShortLink.isShortLink(""))
        assertFalse(MapsShortLink.isShortLink("not a url at all"))
    }

    /**
     * A pasted sentence containing a short link is not a short link. Expanding it
     * would mean guessing which part of the text the rider meant, and the parser
     * that follows can already read a full URL out of surrounding text.
     */
    @Test
    fun `a link with text around it is not treated as a bare link`() {
        assertFalse(MapsShortLink.isShortLink("look at this https://maps.app.goo.gl/abc"))
    }

    /**
     * The expanded URL has to be something [PinCoordinates] can already read, or
     * the round trip buys nothing. This is the real Location header from the
     * measurement, trimmed of its tracking query.
     */
    @Test
    fun `the expanded url parses to the place that was shared`() {
        val expanded =
            "https://www.google.com/maps/place/49.936764,17.902762/" +
                "data=!4m6!3m5!1s0!7e2!8m2!3d49.9367636!4d17.9027618!18m1!1e1"
        val r = PinCoordinates.parse(expanded)
        assertTrue(r is PinCoordinates.Result.Parsed)
        r as PinCoordinates.Result.Parsed
        // !3d/!4d wins over the @ centre and over the path pair: it is the place
        // that was looked up rather than where the camera sat.
        assertTrue(r.latE7 == 499367636)
        assertTrue(r.lonE7 == 179027618)
    }
}
