package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A real version-4 tile header, against the content id the published index
 * carries for it.
 *
 * The fixed vectors next door pin the fold. This pins the parsing: the header
 * grew a byte at version 4 and this side kept reading the version-3 layout, so
 * it took the layer count from the wrong offset and folded a directory of
 * nonsense. Both faults had to be fixed before a stale tile could ever be
 * replaced, and a synthetic fixture would have missed the second one because
 * the test built its own headers at the length it already believed in.
 *
 * The bytes are the first 205 of `13/4483/2842.tib` as the CDN served it on
 * 2026-11-05 -- the fixed header plus the eight-entry layer directory, which is
 * everything [TileHeader.contentId] reads. The geometry is not here.
 */
class RealCdnTileTest {

    @Test
    fun `a published version 4 tile gives the content id its index carries`() {
        val head = javaClass.getResourceAsStream("/v4-head-13-4483-2842.bin")!!.readBytes()
        assertEquals(205, head.size)
        assertEquals(4, TileHeader.formatVersion(head))
        // mapbuilder/tilegen's index slot for this tile, read off the live CDN.
        assertEquals(0x953E7604L, TileHeader.contentId(head))
    }
}
