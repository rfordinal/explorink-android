package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the device's `info` reply.
 *
 * The case that matters most here is the one that is not written down in the
 * reply at all: an older firmware sends no `screen=` line, and reading that
 * absence as either screen is what would start a half-hour batch over the map
 * screen and lose the link on the first city tile.
 */
class DeviceInfoTest {

    private fun read(vararg lines: String): DeviceInfo {
        val r = DeviceInfo.Reader()
        lines.forEach { r.feed(it) }
        return r.info()
    }

    @Test
    fun `the sync screen is named and read`() {
        val info = read("INFO screen=sync", "INFO tile_fmt=4", "OK")
        assertEquals(DeviceInfo.Screen.SYNC, info.screen)
        assertEquals(4, info.tileFormat)
    }

    @Test
    fun `the map screen is named and read`() {
        assertEquals(DeviceInfo.Screen.MAP, read("INFO screen=map", "OK").screen)
    }

    @Test
    fun `an absent screen line is unstated, never map`() {
        // The whole reason the enum has four values. This reply is a real,
        // healthy `info` from a build that predates the key.
        val info = read(
            "INFO pos=1",
            "INFO lat=41.3874000",
            "INFO lon=2.1686000",
            "INFO tile_fmt=4",
            "OK",
        )
        assertEquals(DeviceInfo.Screen.UNSTATED, info.screen)
        assertFalse(info.screen == DeviceInfo.Screen.MAP)
        assertFalse(info.screen == DeviceInfo.Screen.SYNC)
    }

    @Test
    fun `a screen word from a newer build is its own answer`() {
        // Not UNSTATED: the firmware is newer, not older, and a log reader has
        // somewhere to go and look.
        val info = read("INFO screen=routes", "OK")
        assertEquals(DeviceInfo.Screen.OTHER, info.screen)
        assertEquals("routes", info.values["screen"])
    }

    @Test
    fun `the link numbers are read when they are stated and null when they are not`() {
        val full = read("INFO mtu=517", "INFO conn_interval_ms=15", "OK")
        assertEquals(517, full.mtu)
        assertEquals(15, full.connIntervalMs)

        // The device omits both when it has no provider wired, which is a real
        // state and not a zero (`MapCommandConsole::writeInfo`).
        val bare = read("INFO pos=0", "OK")
        assertNull(bare.mtu)
        assertNull(bare.connIntervalMs)
        assertNull(bare.tileFormat)
    }

    @Test
    fun `only OK completes the reply`() {
        val r = DeviceInfo.Reader()
        assertTrue(r.feed("INFO screen=sync"))
        assertFalse(r.complete)
        assertTrue(r.feed("OK"))
        assertTrue(r.complete)
    }

    @Test
    fun `a line from another conversation is not this reply's`() {
        val r = DeviceInfo.Reader()
        // The transfer channel's own words, and the device's error terminator.
        // Both are somebody else's, and a reader that swallowed them would
        // report a reply it never got.
        assertFalse(r.feed("RDY 250"))
        assertFalse(r.feed("ERR unknown_command"))
        assertFalse(r.feed("NEED_TILES 3 fmt 4"))
        assertFalse(r.complete)
    }

    @Test
    fun `a screen with no push observer says so before its OK`() {
        val r = DeviceInfo.Reader()
        r.feed(DeviceInfo.PUSH_UNAVAILABLE_LINE)
        r.feed("OK")
        assertTrue(r.pushUnavailable)
        assertTrue(r.complete)
    }

    @Test
    fun `the push command is the wire's own spelling`() {
        assertEquals("push 55", DeviceInfo.pushCommand(55))
    }

    @Test
    fun `partial values are readable before the terminator arrives`() {
        // A reply that lost its OK still carries what did arrive, and "the
        // device is on its map screen" is a better thing to say than "no
        // answer".
        val r = DeviceInfo.Reader()
        r.feed("INFO screen=map")
        assertEquals(DeviceInfo.Screen.MAP, r.info().screen)
        assertFalse(r.complete)
    }
}
