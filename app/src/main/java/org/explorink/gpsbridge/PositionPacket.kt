package org.explorink.gpsbridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The 21-byte position packet the ExplorInk firmware expects on the position
 * characteristic. Layout is fixed by
 * `firmware/explorink/lib/BlePositionServer/include/BlePositionServer.h`:
 *
 *   [0..3]   lat        int32,  degrees * 1e7
 *   [4..7]   lon        int32,  degrees * 1e7
 *   [8..11]  utc        uint32, unix seconds
 *   [12..13] tz_offset  int16,  minutes east of UTC
 *   [14]     heading    0-15, 16 sectors, 0 = North, clockwise
 *   [15]     seq        rolling counter
 *   [16]     flags      bit0 = off-route warning (always 0 here, no route),
 *                       bit1 = altitude present,
 *                       bits 2-3 = heading quality, see [DirTrust]
 *   [17]     accuracy   metres, saturating; 0 = no figure
 *   [18]     speed      km/h, saturating
 *   [19..20] altitude   int16, metres above sea level; only meaningful if
 *                       flags bit1 is set. Zero is a real altitude (sea
 *                       level), so "no vertical fix" needs the flag bit
 *                       rather than a sentinel value.
 *
 * Little endian, no padding. A write of any other length is dropped by the
 * firmware, so the buffer is built byte by byte rather than by any
 * serialization library.
 */
object PositionPacket {

    const val SIZE = 21

    /**
     * How much the device's position marker may claim about the heading in this
     * packet, carried in flags bits 2-3.
     *
     * **[UNSTATED] is zero on purpose.** A build of this app from before these
     * bits existed writes no bits at all, lands on UNSTATED, and keeps
     * producing exactly the marker it always did. So the meaning of zero can
     * never be "bad" -- a client that says nothing about quality must not look
     * like a client reporting a fault.
     *
     * The device turns these into a shape: GOOD draws today's arrow, COARSE
     * draws a wedge one heading step either side, UNKNOWN draws no heading mark
     * at all. See the firmware's `MapFixTrust.h` and `docs/marker-fix-trust.md`
     * -- and note that the device, not this app, owns what each state looks
     * like.
     */
    object DirTrust {
        const val UNSTATED = 0
        const val GOOD = 1
        const val COARSE = 2
        const val UNKNOWN = 3

        const val FLAG_SHIFT = 2
        const val FLAG_MASK = 0x0C
    }

    /** [dirTrust] packed into flags bits 2-3, merged with [flags]. */
    fun withDirTrust(flags: Int, dirTrust: Int): Int =
        (flags and DirTrust.FLAG_MASK.inv()) or ((dirTrust and 0x03) shl DirTrust.FLAG_SHIFT)

    fun build(
        latDeg: Double,
        lonDeg: Double,
        utcSeconds: Long,
        tzOffsetMinutes: Int,
        heading: Int,
        seq: Int,
        flags: Int,
        accuracyMetres: Double,
        speedKmh: Double,
        altitudeMetres: Double? = null,
    ): ByteArray {
        val hasAltitude = altitudeMetres != null && !altitudeMetres.isNaN()
        val effectiveFlags = flags or (if (hasAltitude) 0x02 else 0)

        val b = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(degreesToE7(latDeg))
        b.putInt(degreesToE7(lonDeg))
        b.putInt((utcSeconds and 0xFFFFFFFFL).toInt())
        b.putShort(tzOffsetMinutes.coerceIn(-32768, 32767).toShort())
        b.put((heading and 0x0F).toByte())
        b.put((seq and 0xFF).toByte())
        b.put((effectiveFlags and 0xFF).toByte())
        b.put(saturateByte(accuracyMetres))
        b.put(saturateByte(speedKmh))
        b.putShort(if (hasAltitude) altitudeToInt16(altitudeMetres!!) else 0)
        return b.array()
    }

    private fun altitudeToInt16(metres: Double): Short {
        val rounded = metres.roundToLong()
        return rounded.coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort()
    }

    private fun degreesToE7(deg: Double): Int {
        val scaled = (deg * 1e7).roundToLong()
        return scaled.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    private fun saturateByte(v: Double): Byte {
        if (v.isNaN()) return 0
        return v.roundToInt().coerceIn(0, 255).toByte()
    }

    /**
     * 16 sectors, 22.5 degrees apart, 0 = North, increasing clockwise.
     * Sector 1 = 22.5 deg, sector 2 = 45 deg, and so on.
     */
    fun headingSector(bearingDeg: Float): Int {
        var d = bearingDeg.toDouble() % 360.0
        if (d < 0) d += 360.0
        return ((d / 22.5).roundToInt()) % 16
    }

    fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (x in bytes) {
            out.append(HEX[(x.toInt() shr 4) and 0x0F])
            out.append(HEX[x.toInt() and 0x0F])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
