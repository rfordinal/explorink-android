package org.explorink.gpsbridge.wallet

import kotlin.math.min

/**
 * An 8-bit grayscale raster, one byte per pixel, row major. The pipeline's
 * working image: everything between "decoded the source" and "dithered to 1bpp"
 * is a [GrayImage].
 *
 * It is a plain array so the whole pipeline runs in a laptop unit test -- no
 * `Bitmap`, no `Canvas`, nothing from Android. The Android-only step (a content
 * Uri to a [GrayImage]) lives in `ImageImport`.
 *
 * Every operation here is a port of the PIL call `tools/walletgen.py` makes, in
 * the same order, and is checked against it byte for byte by `WalletParityTest`.
 */
class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {

    init {
        require(width > 0 && height > 0) { "empty image ${width}x$height" }
        require(pixels.size == width * height) {
            "expected ${width * height} bytes for ${width}x$height, got ${pixels.size}"
        }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xff

    companion object {
        /** A canvas filled with one value; 255 is the white the pipeline pads with. */
        fun filled(width: Int, height: Int, value: Int): GrayImage {
            val px = ByteArray(width * height)
            if (value != 0) java.util.Arrays.fill(px, value.toByte())
            return GrayImage(width, height, px)
        }
    }

    /**
     * `ImageOps.autocontrast(img, cutoff)`, ported from the Pillow Python source
     * (`PIL/ImageOps.py`): histogram, drop `cutoff` percent of pixels off each
     * end, then remap so the darkest surviving level becomes 0 and the lightest
     * 255. Deterministic, applied once, on the full-resolution grey.
     *
     * The integer arithmetic matters and is copied exactly: the cut count is
     * `int(n * cutoff // 100)`, the remap is `int(ix * scale + offset)` with
     * truncation, and when `hi <= lo` the LUT is the identity ("don't bother").
     */
    fun autocontrast(cutoff: Int): GrayImage {
        val h = IntArray(256)
        for (b in pixels) h[b.toInt() and 0xff]++

        if (cutoff != 0) {
            var n = 0
            for (v in h) n += v
            // low end
            var cut = n * cutoff / 100
            for (lo in 0 until 256) {
                if (cut > h[lo]) {
                    cut -= h[lo]
                    h[lo] = 0
                } else {
                    h[lo] -= cut
                    cut = 0
                }
                if (cut <= 0) break
            }
            // high end
            cut = n * cutoff / 100
            for (hi in 255 downTo 0) {
                if (cut > h[hi]) {
                    cut -= h[hi]
                    h[hi] = 0
                } else {
                    h[hi] -= cut
                    cut = 0
                }
                if (cut <= 0) break
            }
        }

        var lo = 0
        while (lo < 256 && h[lo] == 0) lo++
        if (lo == 256) lo = 255
        var hi = 255
        while (hi >= 0 && h[hi] == 0) hi--
        if (hi < 0) hi = 0

        val lut = IntArray(256)
        if (hi <= lo) {
            for (i in 0 until 256) lut[i] = i
        } else {
            val scale = 255.0 / (hi - lo)
            val offset = -lo * scale
            for (i in 0 until 256) {
                var v = (i * scale + offset).toInt()
                if (v < 0) v = 0 else if (v > 255) v = 255
                lut[i] = v
            }
        }

        val out = ByteArray(pixels.size)
        for (i in pixels.indices) out[i] = lut[pixels[i].toInt() and 0xff].toByte()
        return GrayImage(width, height, out)
    }

    /** Pillow-identical Lanczos resize. */
    fun resize(w: Int, h: Int): GrayImage =
        GrayImage(w, h, Resample.resizeGray(pixels, width, height, w, h))

    /**
     * `fit_into()`: scale to fit the box, aspect preserved, centred, letterboxed
     * white.
     *
     * `rint` and not `Math.round` for the scaled size: Python's `round()` is
     * half-to-even, Java's `Math.round` is half-up, and the two disagree on an
     * exact .5 -- which real page sizes do hit.
     */
    fun fitInto(boxW: Int, boxH: Int): GrayImage {
        val scale = min(boxW / width.toDouble(), boxH / height.toDouble())
        val nw = maxOf(1, Math.rint(width * scale).toInt())
        val nh = maxOf(1, Math.rint(height * scale).toInt())
        val scaled = resize(nw, nh)
        val canvas = filled(boxW, boxH, 255)
        canvas.paste(scaled, (boxW - nw) / 2, (boxH - nh) / 2)
        return canvas
    }

    /** Copy [src] in at (x, y). Clipped, like PIL's paste. */
    fun paste(src: GrayImage, x: Int, y: Int) {
        for (sy in 0 until src.height) {
            val dy = y + sy
            if (dy < 0 || dy >= height) continue
            var sx = 0
            var dx = x
            if (dx < 0) {
                sx = -dx
                dx = 0
            }
            val n = min(src.width - sx, width - dx)
            if (n <= 0) continue
            System.arraycopy(src.pixels, sy * src.width + sx, pixels, dy * width + dx, n)
        }
    }

    fun crop(x0: Int, y0: Int, x1: Int, y1: Int): GrayImage {
        val w = x1 - x0
        val h = y1 - y0
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            System.arraycopy(pixels, (y0 + y) * width + x0, out, y * w, w)
        }
        return GrayImage(w, h, out)
    }

    /** Floyd-Steinberg to 1bpp, PIL's `convert("1")`. See [Dither]. */
    fun dither(): MonoImage = Dither.floydSteinberg(this)
}
