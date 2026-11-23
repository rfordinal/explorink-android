package org.explorink.gpsbridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.tan

/**
 * Writes a `.tir` route file -- one planned route, one file.
 *
 * Contract: `docs/route-file-spec.md` in the xteink repo. Device reader:
 * `MapRouteReader.cpp`. Reference writer, the one the device has been fed by
 * since 2026-08-06: `mapbuilder/route_file.py` -- this is a port of it, not a
 * re-derivation.
 *
 * **Two implementations of one binary format must agree exactly, not
 * approximately.** `RouteWriterTest` asserts byte-for-byte equality against a
 * golden file produced by the Python writer, because the device trusts a file
 * the moment it opens it: a header crc that differs in the last bit is a route
 * the picker lists as unreadable, with nothing to say why.
 *
 * Points are **absolute Mercator metres** (EPSG:3857) with y growing **north**,
 * the same numbers `tiles.lonlat_to_merc` returns. A `.tib` way record stores
 * tile-local y, which grows **south**; mixing the two mirrors a route about its
 * own latitude and nothing downstream notices.
 *
 * Pure: no Android, no I/O, no BLE. [FilePusher] carries the bytes.
 */
object RouteWriter {

    /** `TIR1`, the file's first four bytes. */
    private val MAGIC = byteArrayOf('T'.code.toByte(), 'I'.code.toByte(), 'R'.code.toByte(), '1'.code.toByte())

    const val FORMAT_VERSION = 1

    /** Bytes before the name. The point array starts at `roundUp4(this + nameLen)`. */
    const val HEADER_FIXED_BYTES = 40

    /** Offset of `header_crc32`, zeroed while it is computed. */
    private const val HEADER_CRC_OFFSET = 36

    const val MAX_NAME_BYTES = 64

    /** Two points is the shortest thing that is a route rather than a pin. */
    const val MIN_POINTS = 2

    /** EPSG:3857's sphere radius, the value `tiles.py` projects with. */
    private const val MERC_R = 6378137.0

    /** A `.tir` that cannot be trusted. Never partially written. */
    class RouteFormatException(message: String) : IllegalArgumentException(message)

    /** One point in Mercator metres. Doubles until [pack] rounds them. */
    data class Merc(val x: Double, val y: Double)

    /** EPSG:3857, in metres. Latitude first, the same order `tiles.py` takes. */
    fun project(lat: Double, lon: Double): Merc {
        val x = Math.toRadians(lon) * MERC_R
        val y = MERC_R * ln(tan(Math.PI / 4 + Math.toRadians(lat) / 2))
        return Merc(x, y)
    }

    /**
     * `[(lat, lon), ...]` -> Mercator metres, simplified.
     *
     * Douglas-Peucker runs on the **projected** points, not on lat/lon, so one
     * tolerance means the same distance everywhere in one route.
     */
    fun routeFromLatLon(latLon: List<Pair<Double, Double>>, simplifyMetres: Double = 5.0): List<Merc> {
        if (latLon.isEmpty()) return emptyList()
        val projected = latLon.map { (lat, lon) -> project(lat, lon) }
        // Adjacent duplicates first: two identical points make a zero-length
        // segment, and the perpendicular distance to one is a different formula.
        val deduped = ArrayList<Merc>(projected.size)
        deduped.add(projected.first())
        for (p in projected.drop(1)) if (p != deduped.last()) deduped.add(p)
        return if (simplifyMetres > 0) simplify(deduped, simplifyMetres) else deduped
    }

    /** Douglas-Peucker. Endpoints are always kept. */
    fun simplify(points: List<Merc>, epsilon: Double): List<Merc> {
        if (points.size < 3 || epsilon <= 0) return points.toList()

        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true

        // Iterative, with an explicit stack: a recorded track is 20,000 points
        // and a recursive split on a nearly straight one recurses that deep.
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (i0, i1) = stack.removeLast()
            if (i1 <= i0 + 1) continue
            var maxDist = -1.0
            var maxIdx = -1
            for (i in (i0 + 1) until i1) {
                val d = perpDistance(points[i], points[i0], points[i1])
                if (d > maxDist) {
                    maxDist = d
                    maxIdx = i
                }
            }
            if (maxDist > epsilon) {
                keep[maxIdx] = true
                stack.addLast(i0 to maxIdx)
                stack.addLast(maxIdx to i1)
            }
        }

        return points.filterIndexed { i, _ -> keep[i] }
    }

    /**
     * Distance from `pt` to the **infinite line** through `a` and `b`, not to
     * the segment: `t` is deliberately not clamped to `0..1`.
     *
     * That is what `tiles._perp_dist` does, and the two simplifiers have to
     * keep the same points or the golden-file test fails for a reason that
     * looks like a format bug and is not one.
     */
    private fun perpDistance(pt: Merc, a: Merc, b: Merc): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        if (dx == 0.0 && dy == 0.0) return hypot(pt.x - a.x, pt.y - a.y)
        val t = ((pt.x - a.x) * dx + (pt.y - a.y) * dy) / (dx * dx + dy * dy)
        return hypot(pt.x - (a.x + t * dx), pt.y - (a.y + t * dy))
    }

    /**
     * Mercator metres -> the file's bytes.
     *
     * [buildEpoch] is unix seconds. It is a parameter rather than a clock read
     * so a test can produce the same bytes twice.
     */
    fun pack(pointsMerc: List<Merc>, name: String, buildEpoch: Long): ByteArray {
        val points = pointsMerc.map { roundToMetre(it.x) to roundToMetre(it.y) }
        if (points.size < MIN_POINTS) {
            throw RouteFormatException("a route needs at least $MIN_POINTS points, got ${points.size}")
        }

        val nameBytes = truncateUtf8(name, MAX_NAME_BYTES)
        val pointsOffset = roundUp4(HEADER_FIXED_BYTES + nameBytes.size)

        val body = ByteBuffer.allocate(8 * points.size).order(ByteOrder.LITTLE_ENDIAN)
        for ((x, y) in points) {
            body.putInt(x)
            body.putInt(y)
        }
        val bodyBytes = body.array()

        val header = ByteBuffer.allocate(pointsOffset).order(ByteOrder.LITTLE_ENDIAN)
        header.put(MAGIC)
        header.putShort(FORMAT_VERSION.toShort())
        header.put(nameBytes.size.toByte())
        header.put(0)  // reserved, must be 0
        header.putInt(points.size)
        header.putInt(points.minOf { it.first })
        header.putInt(points.minOf { it.second })
        header.putInt(points.maxOf { it.first })
        header.putInt(points.maxOf { it.second })
        header.putInt(buildEpoch.toInt())
        header.putInt(crc32(bodyBytes))
        header.putInt(0)  // header_crc32, zeroed while it is computed
        header.put(nameBytes)
        // Whatever is left is the pad to the 4-byte boundary. ByteBuffer
        // allocated it zeroed and the header crc covers it, so it is part of
        // the format rather than slack.
        val headerBytes = header.array()

        val headerCrc = crc32(headerBytes)
        ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN).putInt(HEADER_CRC_OFFSET, headerCrc)

        return headerBytes + bodyBytes
    }

    /**
     * Rounds a Mercator metre to a whole one the way Python's `round()` does:
     * **half to even**, not half up.
     *
     * `Math.round(0.5)` is 1 and `rint(0.5)` is 0. One point landing a metre
     * off is invisible on the panel (0.22 px at the finest rung) and fatal to
     * the golden-file test, which is the only thing keeping the two writers
     * equal.
     */
    private fun roundToMetre(v: Double): Int {
        val r = Math.rint(v)
        if (r < Int.MIN_VALUE.toDouble() || r > Int.MAX_VALUE.toDouble()) {
            throw RouteFormatException("point $v is outside i32 Mercator metres")
        }
        return r.toInt()
    }

    /**
     * UTF-8 bytes of [name], cut at [maxBytes] **on a character boundary**.
     *
     * The device draws this string, and half a multi-byte character is a glyph
     * nobody can read. Decoding the truncated slice with the malformed tail
     * ignored drops exactly the incomplete sequence -- walking back over
     * continuation bytes by hand eats the last byte of a complete character too.
     */
    private fun truncateUtf8(name: String, maxBytes: Int): ByteArray {
        val raw = name.toByteArray(StandardCharsets.UTF_8)
        if (raw.size <= maxBytes) return raw
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.IGNORE)
            .onUnmappableCharacter(CodingErrorAction.IGNORE)
        val decoded: CharBuffer = decoder.decode(ByteBuffer.wrap(raw, 0, maxBytes))
        return decoded.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun roundUp4(v: Int): Int = (v + 3) and 3.inv()

    private fun crc32(bytes: ByteArray): Int {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value.toInt()
    }
}
