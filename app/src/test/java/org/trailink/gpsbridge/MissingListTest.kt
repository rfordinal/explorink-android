package org.trailink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The device's own reply shape, from `firmware/trailink/docs/missing-tiles.md`. */
class MissingListTest {

    @Test
    fun `need tiles carries the count and the format version`() {
        val need = MissingList.parseNeedTiles("NEED_TILES 25 fmt 2")
        assertEquals(25, need?.count)
        assertEquals(2, need?.formatVersion)
    }

    @Test
    fun `need tiles without a format version is an older firmware`() {
        val need = MissingList.parseNeedTiles("NEED_TILES 7")
        assertEquals(7, need?.count)
        // Null, not a default: guessing a version would push tiles that build
        // cannot read, which is the exact failure the field exists to prevent.
        assertNull(need?.formatVersion)
    }

    @Test
    fun `other lines are not need tiles`() {
        assertNull(MissingList.parseNeedTiles("INFO zoom=2"))
        assertNull(MissingList.parseNeedTiles("NEED_TILES"))
        assertNull(MissingList.parseNeedTiles("OK"))
    }

    @Test
    fun `fetch cancel is recognised`() {
        assertTrue(MissingList.isFetchCancel("FETCH_CANCEL"))
        assertTrue(MissingList.isFetchCancel("  FETCH_CANCEL  "))
        assertFalse(MissingList.isFetchCancel("FETCH_CANCELLED"))
    }

    @Test
    fun `a page reads its entries in the order the device sent them`() {
        val page = MissingList.PageReader()
        listOf(
            "INFO missing_total=3",
            "INFO missing_offset=0",
            "INFO missing_12_2199_1416=7",
            "INFO missing_11_1099_708=2",
            "INFO missing_13_4482_2789=9",
            "INFO missing_next=done",
            "OK",
        ).forEach { assertTrue(it, page.feed(it)) }

        assertEquals(3, page.total)
        assertEquals(0, page.offset)
        assertTrue(page.done)
        assertTrue(page.complete)
        assertFalse(page.unavailable)

        // Fetch priority, as the device sorted it: regional, overview, detail.
        // Re-sorting here would throw away the ordering an interrupted fetch
        // depends on.
        assertEquals(
            listOf(
                MissingTile(12, 2199, 1416, 7),
                MissingTile(11, 1099, 708, 2),
                MissingTile(13, 4482, 2789, 9),
            ),
            page.tiles,
        )
    }

    @Test
    fun `a partial page says where to resume`() {
        val page = MissingList.PageReader()
        listOf(
            "INFO missing_total=25",
            "INFO missing_offset=0",
            "INFO missing_12_1_1=1",
            "INFO missing_next=20",
            "OK",
        ).forEach { page.feed(it) }

        assertEquals(20, page.nextOffset)
        assertFalse(page.done)
        assertTrue(page.complete)
    }

    @Test
    fun `unavailable is not the same as an empty list`() {
        val page = MissingList.PageReader()
        page.feed("INFO missing=unavailable")
        page.feed("OK")
        assertTrue(page.unavailable)
        // A firmware build that never wired its store must not read as a device
        // that needs no tiles.
        assertNull(page.total)
        assertTrue(page.tiles.isEmpty())
    }

    @Test
    fun `an empty list is total zero and done`() {
        val page = MissingList.PageReader()
        page.feed("INFO missing_total=0")
        page.feed("INFO missing_offset=0")
        page.feed("INFO missing_next=done")
        page.feed("OK")
        assertEquals(0, page.total)
        assertTrue(page.done)
        assertFalse(page.unavailable)
    }

    @Test
    fun `lines from other commands are not swallowed`() {
        val page = MissingList.PageReader()
        // A `pos` reply or an `info` line can share the channel; feed() reports
        // false so the caller can route it elsewhere.
        assertFalse(page.feed("ERR bad_number"))
        assertFalse(page.feed("NEED_TILES 4 fmt 2"))
        assertTrue(page.feed("INFO missing_total=1"))
        // An INFO key this listing does not own is not an entry either.
        assertFalse(page.feed("INFO zoom=2"))
        assertTrue(page.tiles.isEmpty())
    }

    @Test
    fun `malformed entries are refused rather than half read`() {
        val page = MissingList.PageReader()
        assertFalse(page.feed("INFO missing_12_2199=7"))       // a component short
        assertFalse(page.feed("INFO missing_12_x_1416=7"))     // not a number
        assertFalse(page.feed("INFO missing_999_1_1=7"))       // z past a uint8
        assertFalse(page.feed("INFO missing_12_1_1=x"))        // count not a number
        assertTrue(page.tiles.isEmpty())
    }
}
