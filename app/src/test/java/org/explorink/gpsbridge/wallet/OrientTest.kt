package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Orient] against `ImageOps.exif_transpose()`.
 *
 * A 4x3 source with a unique value per pixel, and the expected output of every
 * one of the eight EXIF cases as Pillow produces it. The pair that actually goes
 * wrong in practice is 6 and 8: PIL's rotations are counter-clockwise, so EXIF 6
 * ("turn it clockwise to display") is PIL's ROTATE_270. Swapping them puts a
 * photographed page upside down, which no test on the synthetic sheet would ever
 * catch -- that sheet has no EXIF.
 */
class OrientTest {

    private val src = GrayImage(4, 3, ByteArray(12) { it.toByte() })

    private fun run(exif: Int): String {
        val out = Orient.apply(src, exif)
        return "${out.width}x${out.height} ${WalletFormat.hex(out.pixels)}"
    }

    @Test fun normal_is_untouched() = assertEquals("4x3 000102030405060708090a0b", run(1))

    @Test fun flip_horizontal() = assertEquals("4x3 03020100070605040b0a0908", run(2))

    @Test fun rotate_180() = assertEquals("4x3 0b0a09080706050403020100", run(3))

    @Test fun flip_vertical() = assertEquals("4x3 08090a0b0405060700010203", run(4))

    @Test fun transpose() = assertEquals("3x4 00040801050902060a03070b", run(5))

    @Test fun rotate_90_clockwise() = assertEquals("3x4 0804000905010a06020b0703", run(6))

    @Test fun transverse() = assertEquals("3x4 0b07030a0602090501080400", run(7))

    @Test fun rotate_90_counter_clockwise() = assertEquals("3x4 03070b02060a010509000408", run(8))

    @Test
    fun unknown_values_are_left_alone() {
        // Some cameras write 0. Treating that as a rotation would be worse than
        // ignoring it.
        assertEquals("4x3 000102030405060708090a0b", run(0))
        assertEquals("4x3 000102030405060708090a0b", run(99))
    }

    @Test
    fun swaps_axes_agrees_with_the_output_size() {
        for (exif in 1..8) {
            val out = Orient.apply(src, exif)
            val swapped = out.width == src.height && out.height == src.width
            assertEquals("exif $exif", Orient.swapsAxes(exif), swapped)
        }
    }
}
