package org.explorink.gpsbridge.wallet

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Crop and perspective correction: four corners of a document in a photo, out
 * comes the rectangle they enclose, de-skewed.
 *
 * Hand-rolled on purpose. OpenCV would do this in one call and cost ~10 MB of
 * native libraries in an APK that is currently a few hundred kB with one
 * dependency; the whole job is an 8x8 solve plus a bilinear sampler.
 *
 * The transform is a true projective one (a homography), not an affine fit: a
 * photo of a sheet taken at an angle has converging edges, and an affine warp
 * cannot straighten those -- it would leave a trapezoid stretched into a
 * parallelogram.
 *
 * Sampling is bilinear with edge clamping. Not Lanczos: this step usually
 * *reduces* by a small factor at most, and the pipeline's own Lanczos resize
 * (which must stay bit-identical to the generator) runs afterwards on the result.
 *
 * Unverified: no real photograph has been through this on a phone. The maths is
 * checked by unit test (known homographies, a synthetic trapezoid recovered back
 * to a square) -- `PerspectiveWarpTest`.
 */
object PerspectiveWarp {

    /** One corner in source pixels. */
    data class Point(val x: Double, val y: Double)

    /**
     * The four corners of the document in the source image, in this order:
     * top-left, top-right, bottom-right, bottom-left **as the reader sees the
     * page**, not as the photo happens to be oriented.
     */
    data class Quad(val tl: Point, val tr: Point, val br: Point, val bl: Point) {
        fun asList(): List<Point> = listOf(tl, tr, br, bl)
    }

    /**
     * A sensible output size: the longer of each pair of opposite edges, rounded.
     * Keeps the straightened page at roughly the resolution the photo had, which is
     * what the pipeline's resize expects to work from.
     */
    fun suggestedSize(q: Quad): Pair<Int, Int> {
        val top = dist(q.tl, q.tr)
        val bottom = dist(q.bl, q.br)
        val left = dist(q.tl, q.bl)
        val right = dist(q.tr, q.br)
        val w = maxOf(1, maxOf(top, bottom).roundToInt())
        val h = maxOf(1, maxOf(left, right).roundToInt())
        return Pair(w, h)
    }

    private fun dist(a: Point, b: Point): Double = hypot(b.x - a.x, b.y - a.y)

    /**
     * Warp [src] so that [quad] becomes the full [outW] x [outH] rectangle.
     *
     * The homography is solved **destination to source**, which is the direction a
     * resampler needs: every output pixel asks where it came from, so no output
     * pixel is left unwritten.
     */
    fun warp(src: GrayImage, quad: Quad, outW: Int, outH: Int): GrayImage {
        require(outW > 0 && outH > 0) { "empty output ${outW}x$outH" }
        val h = solve(
            // destination corners
            doubleArrayOf(0.0, 0.0),
            doubleArrayOf(outW.toDouble(), 0.0),
            doubleArrayOf(outW.toDouble(), outH.toDouble()),
            doubleArrayOf(0.0, outH.toDouble()),
            // where each one comes from
            quad.asList(),
        )
        val out = ByteArray(outW * outH)
        for (y in 0 until outH) {
            val dy = y + 0.5
            var at = y * outW
            for (x in 0 until outW) {
                val dx = x + 0.5
                val den = h[6] * dx + h[7] * dy + 1.0
                val sx = (h[0] * dx + h[1] * dy + h[2]) / den
                val sy = (h[3] * dx + h[4] * dy + h[5]) / den
                out[at++] = sampleBilinear(src, sx - 0.5, sy - 0.5).toByte()
            }
        }
        return GrayImage(outW, outH, out)
    }

    /** Bilinear sample with edge clamping. */
    fun sampleBilinear(img: GrayImage, x: Double, y: Double): Int {
        val x0 = Math.floor(x).toInt()
        val y0 = Math.floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val x1 = x0 + 1
        val y1 = y0 + 1
        val p00 = at(img, x0, y0)
        val p10 = at(img, x1, y0)
        val p01 = at(img, x0, y1)
        val p11 = at(img, x1, y1)
        val top = p00 + (p10 - p00) * fx
        val bottom = p01 + (p11 - p01) * fx
        val v = top + (bottom - top) * fy
        val r = Math.round(v).toInt()
        return if (r < 0) 0 else if (r > 255) 255 else r
    }

    private fun at(img: GrayImage, x: Int, y: Int): Double {
        val cx = if (x < 0) 0 else if (x >= img.width) img.width - 1 else x
        val cy = if (y < 0) 0 else if (y >= img.height) img.height - 1 else y
        return img[cx, cy].toDouble()
    }

    /**
     * Solve the eight unknowns of
     *
     *     u = (a x + b y + c) / (g x + h y + 1)
     *     v = (d x + e y + f) / (g x + h y + 1)
     *
     * from four (x, y) -> (u, v) pairs, by Gaussian elimination with partial
     * pivoting. Returns [a, b, c, d, e, f, g, h].
     */
    fun solve(d0: DoubleArray, d1: DoubleArray, d2: DoubleArray, d3: DoubleArray,
              srcs: List<Point>): DoubleArray {
        require(srcs.size == 4) { "need four source corners" }
        val dst = listOf(d0, d1, d2, d3)
        val m = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = dst[i][0]
            val y = dst[i][1]
            val u = srcs[i].x
            val v = srcs[i].y
            val r0 = m[i * 2]
            r0[0] = x; r0[1] = y; r0[2] = 1.0
            r0[6] = -x * u; r0[7] = -y * u; r0[8] = u
            val r1 = m[i * 2 + 1]
            r1[3] = x; r1[4] = y; r1[5] = 1.0
            r1[6] = -x * v; r1[7] = -y * v; r1[8] = v
        }
        // Gaussian elimination, partial pivoting.
        for (col in 0 until 8) {
            var pivot = col
            for (r in col + 1 until 8) {
                if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) pivot = r
            }
            if (Math.abs(m[pivot][col]) < 1e-12) {
                throw IllegalArgumentException("degenerate quad: the four corners are collinear")
            }
            val t = m[col]; m[col] = m[pivot]; m[pivot] = t
            val p = m[col][col]
            for (c in col until 9) m[col][c] /= p
            for (r in 0 until 8) {
                if (r == col) continue
                val f = m[r][col]
                if (f == 0.0) continue
                for (c in col until 9) m[r][c] -= f * m[col][c]
            }
        }
        return DoubleArray(8) { m[it][8] }
    }
}
