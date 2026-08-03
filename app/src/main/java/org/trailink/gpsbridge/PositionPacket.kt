package org.trailink.gpsbridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The 19-byte position packet the TrailInk firmware expects on the position
 * characteristic. Layout is fixed by
 * `firmware/trailink/lib/BlePositionServer/include/BlePositionServer.h`:
 *
 *   [0..3]   lat        int32,  degrees * 1e7
 *   [4..7]   lon        int32,  degrees * 1e7
 *   [8..11]  utc        uint32, unix seconds
 *   [12..13] tz_offset  int16,  minutes east of UTC
 *   [14]     heading    0-15, 16 sectors, 0 = North, clockwise
 *   [15]     seq        rolling counter
 *   [16]     flags      bit0 = off-route warning (always 0 here, no route)
 *   [17]     accuracy   metres, saturating
 *   [18]     speed      km/h, saturating
 *
 * Little endian, no padding. A write of any other length is dropped by the
 * firmware, so the buffer is built byte by byte rather than by any
 * serialization library.
 */
object PositionPacket {

    const val SIZE = 19

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
    ): ByteArray {
        val b = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(degreesToE7(latDeg))
        b.putInt(degreesToE7(lonDeg))
        b.putInt((utcSeconds and 0xFFFFFFFFL).toInt())
        b.putShort(tzOffsetMinutes.coerceIn(-32768, 32767).toShort())
        b.put((heading and 0x0F).toByte())
        b.put((seq and 0xFF).toByte())
        b.put((flags and 0xFF).toByte())
        b.put(saturateByte(accuracyMetres))
        b.put(saturateByte(speedKmh))
        return b.array()
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
