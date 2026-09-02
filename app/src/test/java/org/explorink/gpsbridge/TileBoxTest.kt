package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The box arithmetic, against numbers computed outside this code.
 *
 * The tile counts below are the ones in `docs/send-tiles-plan.md`, "What a box
 * around the city centre costs" -- 26, 71 and 202 tiles for the 10, 20 and 40 km
 * boxes around 41.3874, 2.1686 -- and that table was produced from the live CDN
 * index, not from this class. A projection bug here is invisible in the app
 * (tiles arrive, they are just the wrong ones), so the fixed vectors are the
 * whole defence.
 */
class TileBoxTest {

    private val barcelonaLat = 41.3874
    private val barcelonaLon = 2.1686

    private fun countAt(tiles: List<TileRef>, z: Int) = tiles.count { it.z == z }

    @Test
    fun `a barcelona box matches the counts measured off the cdn`() {
        assertEquals(26, TileBox.tilesFor(barcelonaLat, barcelonaLon, 10.0).size)
        assertEquals(71, TileBox.tilesFor(barcelonaLat, barcelonaLon, 20.0).size)
        assertEquals(202, TileBox.tilesFor(barcelonaLat, barcelonaLon, 40.0).size)
    }

    @Test
    fun `the twenty kilometre box covers the ranges the index was read at`() {
        val tiles = TileBox.tilesFor(barcelonaLat, barcelonaLon, 20.0)
        assertEquals(6, countAt(tiles, 11))
        assertEquals(16, countAt(tiles, 12))
        assertEquals(49, countAt(tiles, 13))

        val z13 = tiles.filter { it.z == 13 }
        assertEquals(4142L, z13.minOf { it.col })
        assertEquals(4148L, z13.maxOf { it.col })
        assertEquals(3056L, z13.minOf { it.row })
        assertEquals(3062L, z13.maxOf { it.row })

        // 13/4144/3059 is one of the three tiles whose index sizeBytes was
        // compared against the served body byte for byte (the plan doc's table).
        assertTrue(TileRef(13, 4144, 3059) in tiles)
    }

    @Test
    fun `a mid latitude box gives different tiles for the same kilometres`() {
        // Bratislava. A z13 tile is 3.27 km across here against 3.67 in
        // Barcelona, which is the whole reason the box is sized in kilometres
        // and not in tiles.
        val tiles = TileBox.tilesFor(48.1486, 17.1077, 20.0)
        assertEquals(9, countAt(tiles, 11))
        assertEquals(16, countAt(tiles, 12))
        assertEquals(49, countAt(tiles, 13))
        assertEquals(74, tiles.size)
    }

    @Test
    fun `a tile is narrower the further north it is`() {
        assertEquals(3.670, TileBox.tileWidthKm(13, barcelonaLat), 0.001)
        assertEquals(7.340, TileBox.tileWidthKm(12, barcelonaLat), 0.001)
        assertEquals(14.681, TileBox.tileWidthKm(11, barcelonaLat), 0.001)
        assertEquals(4.891, TileBox.tileWidthKm(13, 0.0), 0.001)
    }

    @Test
    fun `alignment decides the count, and a corner is the cheap case not the dear one`() {
        // 20 km is 5.45 z13 tiles wide here, so the box covers 6 tiles when it
        // starts on a tile boundary and 7 when it straddles one. The dear case
        // is a centre in the middle of a tile, not a centre on its corner --
        // the opposite of what is easy to assume, and the reason both are
        // pinned here.
        val corner = TileBox.tilesFor(41.40977583200955, 2.109375, 20.0)
        assertEquals(36, countAt(corner, 13))

        val middle = TileBox.tilesFor(41.393292198855946, 2.13134765625, 20.0)
        assertEquals(49, countAt(middle, 13))

        // Never worse than ceil(span) + 1 per side, whatever the alignment.
        assertTrue(countAt(middle, 13) <= 7 * 7)
    }

    @Test
    fun `the coarse zoom comes first and the centre tile comes first inside it`() {
        // What has landed when a 30 minute transfer drops has to be worth
        // having: zoomed out and centred on the place the rider pinned.
        val tiles = TileBox.tilesFor(barcelonaLat, barcelonaLon, 20.0)
        assertEquals(11, tiles.first().z)
        assertEquals(TileBox.centreTile(barcelonaLat, barcelonaLon, 11), tiles.first())
        assertEquals(TileRef(11, 1036, 764), tiles.first())

        val firstOfEachZoom = tiles.groupBy { it.z }.mapValues { it.value.first() }
        for (z in TileIndex.LOD_ZOOMS) {
            assertEquals(TileBox.centreTile(barcelonaLat, barcelonaLon, z), firstOfEachZoom[z])
        }
        // Coarse to fine, never interleaved.
        assertEquals(listOf(11, 12, 13), tiles.map { it.z }.distinct())
    }

    @Test
    fun `no tile is asked for twice`() {
        val tiles = TileBox.tilesFor(barcelonaLat, barcelonaLon, 40.0)
        assertEquals(tiles.size, tiles.map { it.key }.toSet().size)
    }

    @Test
    fun `a box across the antimeridian wraps its columns instead of spanning the world`() {
        val range = TileBox.rangeFor(0.0, 179.99, 40.0, 11)!!
        assertTrue(range.wraps)
        assertEquals(2046L, range.firstCol)
        assertEquals(0L, range.lastCol)
        assertEquals(3, range.cols)

        val tiles = TileBox.tilesFor(0.0, 179.99, 40.0, intArrayOf(11))
        assertEquals(12, tiles.size)
        assertEquals(setOf(2046L, 2047L, 0L), tiles.map { it.col }.toSet())
        // Every column is a real one. A raw 2048 would be a 404 for ever.
        assertTrue(tiles.all { it.col in 0 until 2048L })
    }

    @Test
    fun `the seam does not reorder the box around it`() {
        // Chebyshev distance the short way round: the tile the rider pinned is
        // still first even though its neighbours are numbered a world apart.
        val tiles = TileBox.tilesFor(0.0, 179.99, 40.0, intArrayOf(11))
        assertEquals(TileBox.centreTile(0.0, 179.99, 11), tiles.first())
        assertEquals(2047L, tiles.first().col)
    }

    @Test
    fun `latitude clamps at the mercator limit`() {
        val tiles = TileBox.tilesFor(89.9, 10.0, 20.0, intArrayOf(11))
        assertTrue(tiles.isNotEmpty())
        assertTrue(tiles.all { it.row in 0 until 2048L })
        // The box hangs off the top of the world, so it starts at the top of it.
        assertEquals(0L, tiles.minOf { it.row })
    }

    @Test
    fun `a box wider than the world is the world`() {
        val range = TileBox.rangeFor(0.0, 0.0, 50_000.0, 11)!!
        assertFalse(range.wraps)
        assertEquals(0L, range.firstCol)
        assertEquals(2047L, range.lastCol)
        assertEquals(2048, range.cols)
    }

    @Test
    fun `a side that is not a real distance asks for nothing`() {
        assertNull(TileBox.rangeFor(barcelonaLat, barcelonaLon, 0.0, 13))
        assertNull(TileBox.rangeFor(barcelonaLat, barcelonaLon, -5.0, 13))
        assertNull(TileBox.rangeFor(barcelonaLat, barcelonaLon, Double.NaN, 13))
        assertNull(TileBox.rangeFor(Double.NaN, barcelonaLon, 20.0, 13))
        assertTrue(TileBox.tilesFor(barcelonaLat, barcelonaLon, 0.0).isEmpty())
    }
}
