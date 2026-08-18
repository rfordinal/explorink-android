package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Dither] and [GrayImage.autocontrast] against Pillow 12.3.0.
 *
 * The three flat-field cases are not filler. They are what pinned Pillow's
 * arithmetic down in the first place:
 *
 *  - a flat 64 stays entirely black, because the in-row carry converges to
 *    64 * 16/9 = 113.8, under the threshold. A textbook float implementation with
 *    a 128 threshold does the same, but one that clips or rounds differently
 *    starts speckling;
 *  - a flat 128 comes out identical to a flat 127, which is what fixes the
 *    threshold at "white when strictly greater than 128" rather than 127.
 */
class DitherTest {

    private fun gray(w: Int, h: Int, f: (Int, Int) -> Int): GrayImage {
        val px = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = f(x, y).toByte()
        return GrayImage(w, h, px)
    }

    @Test
    fun gradient_matches_pillow_bit_for_bit() {
        val src = gray(16, 8) { x, y -> minOf(255, x * 16 + y * 3) }
        val packed = src.dither().pack1bpp()
        // Image.frombytes("L", (16, 8), ...).convert("1", dither=FLOYDSTEINBERG)
        assertEquals("02bf0957157f02ab157f02af2adf0977", WalletFormat.hex(packed))
    }

    @Test
    fun flat_64_is_black_on_the_first_row_and_dotted_after() {
        // One row of 64 stays entirely black: the in-row carry converges to
        // 64 * 16/9 = 113.8, under the threshold. From the second row on, the 9/16
        // owed downward pushes it over, and PIL produces a 50 % pattern. Both
        // recorded from Pillow 12.3.0, and together they pin the carry weights and
        // the row hand-off, which "all black" would not.
        assertEquals("0000", WalletFormat.hex(gray(16, 1) { _, _ -> 64 }.dither().pack1bpp()))
        assertEquals("0000555500005555",
            WalletFormat.hex(gray(16, 4) { _, _ -> 64 }.dither().pack1bpp()))
    }

    @Test
    fun flat_128_dithers_the_same_as_127() {
        val a = gray(16, 4) { _, _ -> 127 }.dither().pack1bpp()
        val b = gray(16, 4) { _, _ -> 128 }.dither().pack1bpp()
        assertEquals(WalletFormat.hex(a), WalletFormat.hex(b))
        // ... and it is a real checkerboard, not "all black both times".
        assertTrue(a.any { it.toInt() != 0 })
    }

    @Test
    fun flat_129_starts_white() {
        val mono = gray(16, 1) { _, _ -> 129 }.dither()
        assertEquals(255, mono[0, 0])
        assertEquals(0, mono[1, 0])
    }

    @Test
    fun pure_black_and_pure_white_survive() {
        assertTrue(gray(8, 8) { _, _ -> 0 }.dither().pixels.all { it.toInt() == 0 })
        assertTrue(gray(8, 8) { _, _ -> 255 }.dither().pixels.all { it.toInt() == -1 })
    }

    @Test
    fun autocontrast_matches_pillow() {
        val src = GrayImage(12, 1, byteArrayOf(
            60, 60, 61, 70, 80, 90, 100, 180.toByte(), 181.toByte(), 182.toByte(),
            182.toByte(), 250.toByte()))
        assertEquals("0000010d1a2835a1a2a3a3ff",
            WalletFormat.hex(src.autocontrast(1).pixels))
    }

    @Test
    fun autocontrast_of_a_flat_image_changes_nothing() {
        // hi <= lo, Pillow's "don't bother" branch: an identity LUT, not a
        // division by zero and not a black frame.
        val src = GrayImage(4, 2, ByteArray(8) { 77 })
        assertEquals(WalletFormat.hex(src.pixels),
            WalletFormat.hex(src.autocontrast(1).pixels))
    }

    @Test
    fun packing_polarity_is_bit_one_is_white() {
        // bit 0 = black ink, bit 1 = white (GfxRenderer.cpp:515-524).
        val mono = MonoImage(16, 1, ByteArray(16) { if (it == 0) 255.toByte() else 0 })
        assertEquals("8000", WalletFormat.hex(mono.pack1bpp()))
    }

    @Test
    fun native_packing_follows_the_rotation_rule() {
        // native(u, v) = logical(LW - 1 - v, u). A single white pixel at logical
        // (0, 0) must land at native (0, LW - 1) -- the last native row.
        val lw = 8
        val lh = 16
        val px = ByteArray(lw * lh)
        px[0] = 255.toByte()
        val packed = MonoImage(lw, lh, px).packNativeRegion(0, 0, lw, lh)
        val stride = lh / 8
        assertEquals(stride * lw, packed.size)
        // native row lw-1, native x 0 -> top bit of the first byte of that row.
        assertEquals(0x80, packed[(lw - 1) * stride].toInt() and 0xff)
        for (i in 0 until (lw - 1) * stride) assertEquals(0, packed[i].toInt())
    }
}
