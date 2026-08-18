package org.explorink.gpsbridge.wallet

/**
 * Pillow's Floyd-Steinberg dither for `L -> "1"`, reimplemented so the phone and
 * the laptop generator produce the same bits.
 *
 * Pillow's arithmetic is not the textbook float version, and the differences all
 * change pixels. Derived from Pillow 12.3.0's own output and pinned by test
 * (`DitherTest`, 71,867 pixels over random, gradient and flat inputs, plus the
 * whole A4 demo page through `WalletParityTest`):
 *
 *  - errors are integers held **16x scaled**, with one truncating division when
 *    they are read, not per-term division;
 *  - the corrected value is **clipped to 0..255 before** thresholding;
 *  - a pixel becomes white when the corrected value is **strictly greater than
 *    128** (so a flat 128 dithers the same as a flat 127);
 *  - weights are the classic 7/3/5/1, left to right, no serpentine.
 *
 * A useful sanity anchor: a flat grey of 64 stays entirely black, because the
 * in-row carry alone converges to 64*16/9 = 113.8, under the threshold.
 */
object Dither {

    fun floydSteinberg(src: GrayImage): MonoImage {
        val w = src.width
        val h = src.height
        val out = ByteArray(w * h)
        // 16x-scaled error owed to the next row; index x+1 is pixel x, so index 0
        // (down-left of pixel 0) and index w+1 (down-right of the last pixel) are
        // written and never read.
        var err = IntArray(w + 2)
        var next = IntArray(w + 2)
        for (y in 0 until h) {
            java.util.Arrays.fill(next, 0)
            var carry = 0          // 16x-scaled, in-row, from the pixel to the left
            val rowBase = y * w
            for (x in 0 until w) {
                val acc = err[x + 1] + carry
                // C integer division: truncate toward zero.
                val corr = if (acc >= 0) acc / 16 else -((-acc) / 16)
                var v = (src.pixels[rowBase + x].toInt() and 0xff) + corr
                if (v < 0) v = 0 else if (v > 255) v = 255
                val o = if (v > 128) 255 else 0
                out[rowBase + x] = o.toByte()
                val e = v - o
                carry = e * 7
                next[x] += e * 3
                next[x + 1] += e * 5
                next[x + 2] += e
            }
            val swap = err
            err = next
            next = swap
        }
        return MonoImage(w, h, out)
    }
}

/**
 * A dithered bitmap: one byte per pixel, 0 or 255, exactly how PIL holds mode
 * `"1"` internally. Packing to 1bpp and the rotation into panel byte order both
 * happen on the way out, so no rotated copy of a whole page is ever allocated.
 *
 * Ink polarity lives in [packNativeRegion] and [pack1bpp] and nowhere else:
 * **bit 0 = black ink, bit 1 = white**, MSB first. That is what the panel
 * framebuffer holds (`lib/GfxRenderer/GfxRenderer.cpp:515-524`: `drawPixel`
 * *clears* the bit for black) and what PIL's mode-"1" `tobytes()` produces. If
 * the firmware ever contradicts it, flip it here.
 */
class MonoImage(val width: Int, val height: Int, val pixels: ByteArray) {

    init {
        require(pixels.size == width * height) {
            "expected ${width * height} bytes for ${width}x$height, got ${pixels.size}"
        }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xff

    companion object {
        /**
         * Inverse of [pack1bpp]: MSB-first 1bpp rows back to one byte per pixel.
         * Same polarity, read the other way -- **bit 1 = white**, bit 0 = black ink.
         */
        fun unpack1bpp(data: ByteArray, width: Int, height: Int): MonoImage {
            val stride = (width + 7) / 8
            require(data.size == stride * height) {
                "expected ${stride * height} bytes for ${width}x$height, got ${data.size}"
            }
            val px = ByteArray(width * height)
            for (y in 0 until height) {
                val rowBase = y * stride
                val outBase = y * width
                for (x in 0 until width) {
                    val bit = (data[rowBase + (x shr 3)].toInt() shr (7 - (x and 7))) and 1
                    px[outBase + x] = if (bit != 0) 255.toByte() else 0
                }
            }
            return MonoImage(width, height, px)
        }
    }

    /**
     * Pack a logical region into panel-native byte order, the last step before an
     * asset payload is written.
     *
     * The one rotation rule, from `docs/wallet-format.md` ("The rotation rule"),
     * with `LW` = the region's logical width:
     *
     *     native(u, v) = logical(LW - 1 - v, u)      u in [0, LH), v in [0, LW)
     *
     * so a region of (LW, LH) logical pixels becomes (LH, LW) native ones. For one
     * tile LW is the panel's physical height, which is where the tile rule's
     * "H - 1" comes from. It is the firmware's own Portrait case
     * (`GfxRenderer.cpp:214-244`) applied once, at build time, to whole bytes.
     *
     * Native width must be a whole number of bytes; the caller guarantees it (a
     * tile is the panel's height, a page image is padded up to a multiple of 8).
     */
    fun packNativeRegion(x0: Int, y0: Int, lw: Int, lh: Int): ByteArray {
        require(lh % 8 == 0) { "native width $lh is not a whole number of bytes" }
        val stride = lh / 8
        val out = ByteArray(stride * lw)
        for (v in 0 until lw) {
            val srcCol = x0 + (lw - 1 - v)
            val outBase = v * stride
            var acc = 0
            var bit = 0
            var at = outBase
            for (u in 0 until lh) {
                acc = acc shl 1
                if (pixels[(y0 + u) * width + srcCol].toInt() != 0) acc = acc or 1
                bit++
                if (bit == 8) {
                    out[at++] = acc.toByte()
                    acc = 0
                    bit = 0
                }
            }
        }
        return out
    }

    /**
     * Inverse of [packNativeRegion] over a whole asset: panel-native bytes back to
     * the logical portrait image the rider sees.
     *
     * From the same rule, read backwards: `logical(x, y) = native(y, LW - 1 - x)`,
     * with `LW` = the logical width = this image's height. Used by the verify loop
     * ([CodeReader.decodeAsset]) and by the on-phone code viewer; the device never
     * does this -- it blits.
     */
    fun unrotateNative(): MonoImage {
        val lw = height          // logical width  == native row count
        val lh = width           // logical height == native column count
        val out = ByteArray(lw * lh)
        for (y in 0 until lh) {
            for (x in 0 until lw) {
                out[y * lw + x] = pixels[(lw - 1 - x) * width + y]
            }
        }
        return MonoImage(lw, lh, out)
    }

    /** 0/255 pixels as an 8-bit grey raster, for a decoder or a preview. */
    fun toGray(): GrayImage = GrayImage(width, height, pixels.copyOf())

    /**
     * Pack without rotating: MSB-first rows of this image as it stands. Used for a
     * landscape machine-code canvas, which already IS panel-native (phase P5).
     */
    fun pack1bpp(): ByteArray {
        val stride = (width + 7) / 8
        val out = ByteArray(stride * height)
        for (y in 0 until height) {
            val rowBase = y * width
            for (x in 0 until width) {
                if (pixels[rowBase + x].toInt() != 0) {
                    val at = y * stride + (x shr 3)
                    out[at] = (out[at].toInt() or (0x80 ushr (x and 7))).toByte()
                }
            }
        }
        return out
    }
}
