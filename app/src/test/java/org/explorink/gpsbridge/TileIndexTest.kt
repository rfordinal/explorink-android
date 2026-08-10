package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The index layout, pinned.
 *
 * This is the second implementation of `mapbuilder/tile_index.py`, ported by
 * hand, and a hand port with no fixed vectors is a coin flip: read a slot at the
 * wrong offset and every tile comes back stale, which looks exactly like the
 * feature working. Same arrangement as the `.tir` route format --
 * `mapbuilder/test_route_file.py`: one format, two languages, two tests.
 *
 * The vectors below were produced by `mapbuilder/tile_index.py` against the real
 * mirror (block 7/70/44, the `region1` build), not written by hand.
 */
class TileIndexTest {

    @Test
    fun `block layout matches the spec`() {
        assertEquals(7, TileIndex.BLOCK_Z)
        assertEquals(16, TileIndex.SLOT_BYTES)
        assertEquals(8, TileIndex.HEADER_BYTES)
        // 256 z11 + 1024 z12 + 4096 z13
        assertEquals(5376, TileIndex.BLOCK_SLOTS)
        assertEquals(86024, TileIndex.BLOCK_BYTES)
        assertEquals(16, TileIndex.side(11))
        assertEquals(32, TileIndex.side(12))
        assertEquals(64, TileIndex.side(13))
    }

    @Test
    fun `a tile lands in the block that holds it`() {
        // The real pair from the mirror: region1 and ride1-video both live here.
        assertEquals(70L, TileIndex.blockCol(13, 4482))
        assertEquals(44L, TileIndex.blockRow(13, 2839))
        assertEquals(70L, TileIndex.blockCol(12, 2241))
        assertEquals(44L, TileIndex.blockRow(12, 1419))
        assertEquals(70L, TileIndex.blockCol(11, 1120))
        assertEquals(44L, TileIndex.blockRow(11, 709))
    }

    @Test
    fun `slot offsets match mapbuilder`() {
        // The first slot of each plane, straight off the spec's arithmetic.
        assertEquals(8, TileIndex.slotOffset(11, 1120, 704))
        assertEquals(8 + 256 * 16, TileIndex.slotOffset(12, 2240, 1408))
        assertEquals(8 + 1280 * 16, TileIndex.slotOffset(13, 4480, 2816))

        // Real tiles, offsets computed by mapbuilder/tile_index.py.
        assertEquals(44072, TileIndex.slotOffset(13, 4482, 2839))
        assertEquals(9752, TileIndex.slotOffset(12, 2241, 1419))
        assertEquals(1288, TileIndex.slotOffset(11, 1120, 709))
    }

    @Test
    fun `a zoom with no plane has no offset`() {
        // Not an exception and not offset 0: an unindexed zoom must fall out of
        // planSpans rather than address a neighbouring tile's slot.
        assertEquals(-1, TileIndex.slotOffset(14, 0, 0))
        assertEquals(-1, TileIndex.slotOffset(10, 0, 0))
        assertEquals(-1, TileIndex.slotOffset(6, 0, 0))
    }

    @Test
    fun `slots parse the way mapbuilder packed them`() {
        // z13 4482/2839 out of the real block, byte for byte.
        val slot = hex("05005404b6ab5b44776a88ef00000000")
        val s = TileIndex.parseSlot(slot, 0)
        assertNotNull(s)
        assertTrue(s!!.present)
        assertEquals(TileIndex.COVERAGE_FULL, s.coverage)
        assertEquals(2880832596L, s.contentId)
        assertEquals(1786201179L, s.buildEpoch)
        assertEquals(61320L, s.sizeBytes)
    }

    @Test
    fun `an all-zero slot is absent by construction`() {
        val s = TileIndex.parseSlot(ByteArray(TileIndex.SLOT_BYTES), 0)
        assertNotNull(s)
        assertFalse(s!!.present)
        assertEquals(0L, s.contentId)
    }

    @Test
    fun `a short buffer parses to nothing rather than to garbage`() {
        assertNull(TileIndex.parseSlot(ByteArray(15), 0))
        assertNull(TileIndex.parseSlot(ByteArray(16), 1))
        assertNull(TileIndex.parseSlot(ByteArray(16), -1))
    }

    @Test
    fun `a viewport becomes one range request, not thirty-two`() {
        // 4x4 z13 tiles, the shape a viewport actually has. One span, and small:
        // 32 separate HTTPS round trips is what this exists to avoid.
        val tiles = mutableListOf<HeldTile>()
        for (row in 2839L..2842L) for (col in 4482L..4485L) {
            tiles.add(HeldTile(13, col, row, 1))
        }
        val spans = TileIndex.planSpans(tiles)
        assertEquals(1, spans.size)
        val span = spans[0]
        assertEquals(70L, span.blockCol)
        assertEquals(44L, span.blockRow)
        assertEquals(16, span.tiles.size)
        // Three row strides of 64 slots plus three columns, one slot wide.
        assertEquals((3 * 64 + 3 + 1) * 16, span.length)
        assertTrue(span.length < 4096)
        assertEquals("base/index/7/70/44.idx", span.relPath())
    }

    @Test
    fun `each zoom gets its own span`() {
        // One span across two planes would drag tens of kB of slots nobody asked
        // about over the rider's data.
        val spans = TileIndex.planSpans(
            listOf(
                HeldTile(13, 4482, 2839, 1),
                HeldTile(12, 2241, 1419, 2),
                HeldTile(11, 1120, 709, 3),
            ),
        )
        assertEquals(3, spans.size)
        assertTrue(spans.all { it.tiles.size == 1 })
        assertTrue(spans.all { it.length == TileIndex.SLOT_BYTES })
    }

    @Test
    fun `each block gets its own span`() {
        val spans = TileIndex.planSpans(
            listOf(HeldTile(13, 4482, 2839, 1), HeldTile(13, 4546, 2839, 2)),
        )
        assertEquals(2, spans.size)
        assertEquals(setOf(70L, 71L), spans.map { it.blockCol }.toSet())
    }

    @Test
    fun `a tile at an unindexed zoom is dropped, not misread`() {
        val spans = TileIndex.planSpans(
            listOf(HeldTile(13, 4482, 2839, 1), HeldTile(14, 8964, 5678, 2)),
        )
        assertEquals(1, spans.size)
        assertEquals(13, spans[0].z)
    }

    @Test
    fun `offsetWithin addresses the slot inside the bytes the span returns`() {
        val tiles = listOf(
            HeldTile(13, 4482, 2839, 1),
            HeldTile(13, 4483, 2839, 2),
            HeldTile(13, 4482, 2840, 3),
        )
        val span = TileIndex.planSpans(tiles).single()
        for (t in tiles) {
            val at = span.offsetWithin(t)
            assertTrue("$t is inside the span", at >= 0 && at + TileIndex.SLOT_BYTES <= span.length)
            assertEquals(TileIndex.slotOffset(t.z, t.col, t.row), span.first + at)
        }
    }

    @Test
    fun `the block header carries the layout version`() {
        val header = hex("0100000000000000")
        assertEquals(TileIndex.INDEX_FORMAT_VERSION.toLong(), TileIndex.formatVersion(header))
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
}
