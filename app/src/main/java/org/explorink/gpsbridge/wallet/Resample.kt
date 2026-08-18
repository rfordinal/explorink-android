package org.explorink.gpsbridge.wallet

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

/**
 * Pillow's 8-bit Lanczos resampler, reimplemented so the phone and the laptop
 * generator produce the same pixels.
 *
 * This is a deliberate line-by-line port of Pillow's `src/libImaging/Resample.c`
 * (`precompute_coeffs`, `normalize_coeffs_8bpc`, `ImagingResampleHorizontal_8bpc`,
 * `ImagingResampleVertical_8bpc`), not "a Lanczos filter". A generic Lanczos
 * differs from Pillow's in three ways that all change bytes:
 *
 *  - coefficients are normalised to sum 1.0 in double, then quantised to 22-bit
 *    fixed point ([PRECISION_BITS]) with round-half-away-from-zero;
 *  - the accumulator starts at `1 << (PRECISION_BITS - 1)` so the shift rounds,
 *    and it is a wrapping 32-bit int;
 *  - the resize is TWO passes with an **8-bit clipped intermediate**, horizontal
 *    first, so the vertical pass reads already-rounded bytes.
 *
 * Verified: byte-identical to `Image.resize(..., Image.LANCZOS)` on Pillow 12.3.0
 * for up-, down- and single-axis scaling (see `ResampleTest`, and the parity test
 * runs it over the real A4 demo page at all three zoom levels).
 */
object Resample {

    /** Pillow: `#define PRECISION_BITS (32 - 8 - 2)`. */
    const val PRECISION_BITS = 32 - 8 - 2

    /** Lanczos support, `a = 3`. */
    private const val SUPPORT = 3.0

    private fun sinc(x0: Double): Double {
        if (x0 == 0.0) return 1.0
        val x = x0 * PI
        return sin(x) / x
    }

    private fun lanczos(x: Double): Double =
        if (x >= -3.0 && x < 3.0) sinc(x) * sinc(x / 3.0) else 0.0

    private class Coeffs(val ksize: Int, val bounds: IntArray, val kk: IntArray)

    /**
     * `precompute_coeffs` + `normalize_coeffs_8bpc`. `in0`/`in1` are always the
     * full axis here (Pillow's `box`), which is what `Image.resize` passes when
     * no box is given.
     */
    private fun precompute(inSize: Int, in0: Double, in1: Double, outSize: Int): Coeffs {
        val scale = (in1 - in0) / outSize
        val filterScale = if (scale < 1.0) 1.0 else scale
        val support = SUPPORT * filterScale
        val ksize = ceil(support).toInt() * 2 + 1
        val kk = IntArray(outSize * ksize)
        val bounds = IntArray(outSize * 2)
        val row = DoubleArray(ksize)
        for (xx in 0 until outSize) {
            val center = in0 + (xx + 0.5) * scale
            var ww = 0.0
            val ss = 1.0 / filterScale
            var xmin = (center - support + 0.5).toInt()
            if (xmin < 0) xmin = 0
            var xmax = (center + support + 0.5).toInt()
            if (xmax > inSize) xmax = inSize
            xmax -= xmin
            for (x in 0 until xmax) {
                val w = lanczos((x + xmin - center + 0.5) * ss)
                row[x] = w
                ww += w
            }
            if (ww != 0.0) {
                for (x in 0 until xmax) row[x] /= ww
            }
            for (x in 0 until xmax) {
                val v = row[x]
                // normalize_coeffs_8bpc: round away from zero, then truncate.
                kk[xx * ksize + x] =
                    if (v < 0) (-0.5 + v * (1 shl PRECISION_BITS)).toInt()
                    else (0.5 + v * (1 shl PRECISION_BITS)).toInt()
            }
            for (x in xmax until ksize) kk[xx * ksize + x] = 0
            bounds[xx * 2] = xmin
            bounds[xx * 2 + 1] = xmax
        }
        return Coeffs(ksize, bounds, kk)
    }

    /** Pillow's `clip8`: arithmetic shift down, then clamp to a byte. */
    private fun clip8(v: Int): Int {
        val s = v shr PRECISION_BITS
        return if (s < 0) 0 else if (s > 255) 255 else s
    }

    /**
     * Resize an 8-bit grayscale raster. Two passes with an 8-bit intermediate,
     * exactly as `ImagingResample` does it.
     */
    fun resizeGray(src: ByteArray, sw: Int, sh: Int, dw: Int, dh: Int): ByteArray {
        require(sw > 0 && sh > 0 && dw > 0 && dh > 0) { "empty resize" }
        if (dw == sw && dh == sh) return src.copyOf()

        val h = precompute(sw, 0.0, sw.toDouble(), dw)
        val v = precompute(sh, 0.0, sh.toDouble(), dh)

        // First and last source row any output row needs, so the horizontal pass
        // only touches the rows the vertical pass will read.
        val yboxFirst = v.bounds[0]
        val yboxLast = v.bounds[dh * 2 - 2] + v.bounds[dh * 2 - 1]

        var cur = src
        var curW = sw
        val needHorizontal = dw != sw
        val needVertical = dh != sh

        if (needHorizontal) {
            for (i in 0 until dh) v.bounds[i * 2] -= yboxFirst
            val th = yboxLast - yboxFirst
            val tmp = ByteArray(dw * th)
            for (yy in 0 until th) {
                val rowBase = (yy + yboxFirst) * sw
                val outBase = yy * dw
                for (xx in 0 until dw) {
                    val xmin = h.bounds[xx * 2]
                    val xmax = h.bounds[xx * 2 + 1]
                    val kbase = xx * h.ksize
                    var ss = 1 shl (PRECISION_BITS - 1)
                    for (x in 0 until xmax) {
                        ss += (cur[rowBase + x + xmin].toInt() and 0xff) * h.kk[kbase + x]
                    }
                    tmp[outBase + xx] = clip8(ss).toByte()
                }
            }
            cur = tmp
            curW = dw
        }

        if (needVertical) {
            val out = ByteArray(curW * dh)
            for (yy in 0 until dh) {
                val ymin = v.bounds[yy * 2]
                val ymax = v.bounds[yy * 2 + 1]
                val kbase = yy * v.ksize
                val outBase = yy * curW
                for (xx in 0 until curW) {
                    var ss = 1 shl (PRECISION_BITS - 1)
                    for (y in 0 until ymax) {
                        ss += (cur[(y + ymin) * curW + xx].toInt() and 0xff) * v.kk[kbase + y]
                    }
                    out[outBase + xx] = clip8(ss).toByte()
                }
            }
            cur = out
        }
        return cur
    }
}
