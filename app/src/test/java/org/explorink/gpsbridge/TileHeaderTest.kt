package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `content_id`, pinned against the other two implementations.
 *
 * There are three: `mapbuilder/tiles.py` `content_id_from_layer_crcs()`,
 * `MapTileReader::contentId()` in the firmware, and [TileHeader.contentId] here.
 * They must produce the same number from the same tile or the freshness check
 * reports every tile stale forever -- a failure that looks like the feature
 * working hard.
 *
 * The fixed vectors are the ones `mapbuilder/test_tile_index.py` asserts, and
 * the real-tile vector is `region1`'s z13 4482/2839, byte for byte off the
 * mirror.
 */
class TileHeaderTest {

    @Test
    fun `fixed vectors match mapbuilder`() {
        assertEquals(0xA3C1CA20L, contentIdOf(emptyMap()))
        assertEquals(0x22E4AF07L, contentIdOf(mapOf(1 to 0x00000001L)))
        assertEquals(0x1B7DAD45L, contentIdOf(mapOf(6 to 0x00000001L)))
        assertEquals(
            0x1653D850L,
            contentIdOf(
                mapOf(
                    1 to 0xAABBCCDDL, 2 to 0x11223344L, 3 to 0xDEADBEEFL,
                    4 to 0x00000000L, 5 to 0xFFFFFFFFL, 6 to 0x12345678L,
                ),
            ),
        )
    }

    @Test
    fun `an absent layer and an empty one are the same thing`() {
        // crc32 of nothing is 0, so a layer that was not written and a layer
        // with no geometry cannot be told apart -- and must not be.
        assertEquals(contentIdOf(emptyMap()), contentIdOf(mapOf(3 to 0L)))
    }

    @Test
    fun `every layer counts and the order is fixed`() {
        // Same crc in two different layers must give different content ids, or a
        // change that moves geometry between layers would go unnoticed.
        assertEquals(true, contentIdOf(mapOf(1 to 7L)) != contentIdOf(mapOf(2 to 7L)))
    }

    @Test
    fun `a real tile's header gives the content id the index carries`() {
        // 162 bytes: the fixed header plus the six-entry layer directory. The
        // rest of the tile is geometry this never reads -- which is why the
        // check costs a byte-range read and not a download.
        val head = hex(
            "5449423103000d82110000170b00002cd01c0056d45d005b44776a6143776a3106ca7d0601a20000008c" +
                "030000c81b9adb0000000000000000022e0400000c6300009b9156d10000000000000000033a670000" +
                "c4670000057597d6000000000000000004fece00000000000000000000000000000000000005fece00" +
                "0000120000db539552000000000000000006fee000008a0e0000425f00370000000000000000",
        )
        assertEquals(162, head.size)
        assertEquals(3, TileHeader.formatVersion(head))
        // The same number mapbuilder wrote into the index slot for this tile.
        assertEquals(0xABB60454L, TileHeader.contentId(head))
    }

    @Test
    fun `a truncated or foreign file has no content id`() {
        assertNull(TileHeader.contentId(ByteArray(0)))
        assertNull(TileHeader.contentId(ByteArray(35)))
        // Right length, wrong magic.
        assertNull(TileHeader.contentId(ByteArray(162)))
        // Right magic, directory cut short: better no answer than a plausible one.
        val short = ByteArray(40)
        TileHeader.MAGIC.copyInto(short)
        short[4] = 3
        short[35] = 6
        assertNull(TileHeader.contentId(short))
    }

    /** Builds the 36-byte header plus a layer directory holding [crcs]. */
    private fun contentIdOf(crcs: Map<Int, Long>): Long? {
        val ids = crcs.keys.sorted()
        val out = ByteArray(36 + ids.size * 21)
        TileHeader.MAGIC.copyInto(out)
        out[4] = 3  // format version, little endian u16
        out[35] = ids.size.toByte()
        for ((i, id) in ids.withIndex()) {
            val at = 36 + i * 21
            out[at] = id.toByte()
            putU32(out, at + 9, crcs.getValue(id))
        }
        return TileHeader.contentId(out)
    }

    private fun putU32(buf: ByteArray, at: Int, v: Long) {
        for (i in 0 until 4) buf[at + i] = ((v shr (8 * i)) and 0xff).toByte()
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
}
