package org.explorink.gpsbridge

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * One tile position, with no opinion about where its bytes come from.
 *
 * Deliberately not [MissingTile]: that type carries the device's own hit count
 * and its comment insists the order it arrives in is the device's priority. A
 * tile the rider asked for was never on any device list, so borrowing that type
 * would mean carrying a count that means nothing and inviting the next reader to
 * trust an order the device did not choose.
 */
data class TileRef(val z: Int, val col: Long, val row: Long) {
    /**
     * Identity in the outbox ledger.
     *
     * Keyed by the tile and **not** by the zone that asked for it: two zones
     * overlap (a 20 km and a 40 km box around the same city share almost
     * everything), and a receipt is a fact about what the device holds, not
     * about which pick produced it.
     */
    val key: String get() = "$z/$col/$row"

    /**
     * The same position as the index reader wants it.
     *
     * `contentId` is 0 because nothing here holds the tile -- this is a probe,
     * not a claim. [TileIndex.planSpans] only uses `z`, `col` and `row`.
     */
    fun asIndexProbe(): HeldTile = HeldTile(z, col, row, 0L)
}

/**
 * Turns "here, this many kilometres across" into the tiles that cover it.
 *
 * The rider picks a place in Google Maps and shares it in ([PinCoordinates]
 * reads the share); this decides what a box around that point actually costs in
 * tiles. Three LOD zooms, because the device reads all three
 * ([TileIndex.LOD_ZOOMS]) and a box with only z13 in it draws nothing when the
 * rider zooms out.
 *
 * **Web Mercator, the same projection the index assumes.** A tile's ground width
 * therefore depends on latitude: 3.67 km at z13 in Barcelona, 2.55 km in
 * Reykjavik, 4.89 km at the equator. Sizing a box in tiles instead of in
 * kilometres would hand a Nordic rider a third of the ground a Spanish one gets
 * for the same pick.
 *
 * The box is square **in ground distance**, not in projected units: half the
 * side north and south, half the side east and west, using the same
 * [PinGeo.METRES_PER_DEGREE] the rest of the app and the device measure with.
 * Verified against `docs/send-tiles-plan.md`, "What a box around the city centre
 * costs": this reproduces that table's 26, 71 and 202 tiles for the 10, 20 and
 * 40 km boxes around 41.3874, 2.1686.
 *
 * Pure: no Android, no I/O, no clock.
 */
object TileBox {

    /**
     * Where Web Mercator stops. Beyond this `ln(tan + sec)` runs away and the
     * projection has no row to name, which is why every slippy-map
     * implementation cuts the world here rather than at 90.
     */
    const val MAX_LATITUDE_DEG = 85.05112877980659

    /** Ground width of one tile at [z] and [latDeg], in kilometres. */
    fun tileWidthKm(z: Int, latDeg: Double): Double {
        val lat = latDeg.coerceIn(-MAX_LATITUDE_DEG, MAX_LATITUDE_DEG)
        return 360.0 / (1L shl z) * PinGeo.METRES_PER_DEGREE * cos(lat * PI / 180.0) / 1000.0
    }

    /** Inclusive tile ranges covering one box at one zoom. */
    data class Range(
        val z: Int,
        val firstCol: Long,
        val lastCol: Long,
        val firstRow: Long,
        val lastRow: Long,
        /**
         * True when the box crosses the antimeridian, so [firstCol] is greater
         * than [lastCol] and the columns run first..(n-1), 0..last.
         */
        val wraps: Boolean,
    ) {
        val cols: Int
            get() = if (wraps) ((1L shl z) - firstCol + lastCol + 1).toInt() else (lastCol - firstCol + 1).toInt()
        val rows: Int get() = (lastRow - firstRow + 1).toInt()
        val count: Int get() = cols * rows
    }

    /**
     * Every tile of the box, coarse zoom first, centre tile first inside a zoom.
     *
     * **Both orderings are the transfer's insurance policy.** A city is 20 to 40
     * minutes over BLE and the link can drop at any point, so what has landed
     * when it drops has to be worth having: z11 first means the rider can at
     * least zoom out and see where they are, and centre-out means the part they
     * pinned is the part that arrived. The wallet learned the same lesson about
     * ordering a plan by what a half-finished sync leaves usable.
     *
     * Empty for a side of zero or less, and for anything not finite -- the
     * screen offers 10, 20 and 40, so a bad number here is a bug, not a rider.
     */
    fun tilesFor(
        latDeg: Double,
        lonDeg: Double,
        sideKm: Double,
        zooms: IntArray = TileIndex.LOD_ZOOMS,
    ): List<TileRef> {
        val out = mutableListOf<TileRef>()
        for (z in zooms) {
            val r = rangeFor(latDeg, lonDeg, sideKm, z) ?: continue
            val n = 1L shl z
            val centre = centreTile(latDeg, lonDeg, z)
            val ofZoom = mutableListOf<TileRef>()
            var i = 0
            while (i < r.cols) {
                val col = (r.firstCol + i) % n
                for (row in r.firstRow..r.lastRow) ofZoom.add(TileRef(z, col, row))
                i++
            }
            // Centre-out, and stable: two tiles the same distance away are
            // ordered by row then column so the list does not shuffle between
            // runs and a saved plan keeps reading the way it was written.
            ofZoom.sortWith(
                compareBy(
                    { distanceSquared(it, centre, n) },
                    { it.row },
                    { it.col },
                )
            )
            out.addAll(ofZoom)
        }
        return out
    }

    /**
     * The inclusive tile range of the box at [z], or null when there is nothing
     * to ask for.
     *
     * Longitude wraps and latitude clamps, and the asymmetry is the projection's,
     * not a shortcut: the world is a cylinder east-west, so a box straddling
     * +/-180 is one box whose columns run off one edge and back on the other,
     * and column arithmetic modulo `2^z` is the whole fix. North-south there is
     * nothing to wrap onto -- past [MAX_LATITUDE_DEG] the projection has no rows
     * at all -- so the box is cut instead.
     */
    fun rangeFor(latDeg: Double, lonDeg: Double, sideKm: Double, z: Int): Range? {
        if (!latDeg.isFinite() || !lonDeg.isFinite() || !sideKm.isFinite()) return null
        if (sideKm <= 0.0) return null
        if (z < 0 || z > 30) return null

        val n = 1L shl z
        val lat = latDeg.coerceIn(-MAX_LATITUDE_DEG, MAX_LATITUDE_DEG)
        val halfM = sideKm * 1000.0 / 2.0
        val halfLatDeg = halfM / PinGeo.METRES_PER_DEGREE
        // cos() at the centre latitude, not at each edge: the box is small
        // against the earth (40 km at most today) and taking the two edges
        // separately would make the north and south sides different lengths for
        // no gain the rider could see.
        // Never zero, because the latitude was clamped first: cos(85.05) is
        // 0.086, so the division below always has something to divide by. The
        // pole, where a degree of longitude vanishes, is outside the projection
        // altogether.
        val scale = cos(lat * PI / 180.0)
        val halfLonDeg = halfM / (PinGeo.METRES_PER_DEGREE * scale)

        val north = (lat + halfLatDeg).coerceAtMost(MAX_LATITUDE_DEG)
        val south = (lat - halfLatDeg).coerceAtLeast(-MAX_LATITUDE_DEG)
        val firstRow = rowOf(north, n).coerceIn(0L, n - 1)
        val lastRow = rowOf(south, n).coerceIn(0L, n - 1)

        if (halfLonDeg >= 180.0) {
            return Range(z, 0L, n - 1, firstRow, lastRow, wraps = false)
        }

        // Computed in continuous column space, where the west edge may be
        // negative and the east edge may be past 2^z, then folded back. Folding
        // first would put the east edge to the west of the west edge and lose
        // which side of the seam each one is on.
        val westX = floor(xOf(lonDeg - halfLonDeg, n))
        val eastX = floor(xOf(lonDeg + halfLonDeg, n))
        if (eastX - westX + 1 >= n) {
            return Range(z, 0L, n - 1, firstRow, lastRow, wraps = false)
        }
        val first = ((westX.toLong() % n) + n) % n
        val last = ((eastX.toLong() % n) + n) % n
        return Range(z, first, last, firstRow, lastRow, wraps = last < first)
    }

    /** The tile the centre point lands in. */
    fun centreTile(latDeg: Double, lonDeg: Double, z: Int): TileRef {
        val n = 1L shl z
        val lat = latDeg.coerceIn(-MAX_LATITUDE_DEG, MAX_LATITUDE_DEG)
        val col = ((floor(xOf(lonDeg, n)).toLong() % n) + n) % n
        return TileRef(z, col, rowOf(lat, n).coerceIn(0L, n - 1))
    }

    /** Continuous column of [lonDeg] at a plane [n] tiles wide. */
    private fun xOf(lonDeg: Double, n: Long): Double = (lonDeg + 180.0) / 360.0 * n

    /** Row of [latDeg] at a plane [n] tiles high. Standard Web Mercator. */
    private fun rowOf(latDeg: Double, n: Long): Long {
        val r = latDeg * PI / 180.0
        val y = (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0
        return floor(y * n).toLong()
    }

    /**
     * Squared tile distance from [centre], the short way round the seam.
     *
     * Without the wrap a box straddling the antimeridian would order its own
     * two halves as if they were a world apart, and the far edge would go before
     * the tile the rider actually pinned.
     */
    private fun distanceSquared(t: TileRef, centre: TileRef, n: Long): Long {
        var dc = abs(t.col - centre.col)
        if (dc > n / 2) dc = n - dc
        val dr = t.row - centre.row
        return dc * dc + dr * dr
    }
}
