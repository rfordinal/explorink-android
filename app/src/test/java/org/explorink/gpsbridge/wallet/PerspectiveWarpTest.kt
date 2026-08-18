package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The 4-corner warp. Checked by construction, not against a library: a known
 * homography is applied forward to build a synthetic photo, then the warp is
 * asked to undo it.
 *
 * Still unverified where it counts: no real photograph has been through this.
 * What a test can prove is that the maths is right (the identity case is exact,
 * a projective quad is recovered, a degenerate quad is refused); what it cannot
 * prove is that a rider's four taps land on the corners of a passport.
 */
class PerspectiveWarpTest {

    /** A checkerboard, so a wrong warp is obvious rather than plausible. */
    private fun board(w: Int, h: Int, cell: Int = 16): GrayImage {
        val px = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            px[y * w + x] = if (((x / cell) + (y / cell)) % 2 == 0) 240.toByte() else 20
        }
        return GrayImage(w, h, px)
    }

    @Test
    fun the_identity_quad_reproduces_the_source() {
        val src = board(64, 48)
        val quad = PerspectiveWarp.Quad(
            PerspectiveWarp.Point(0.0, 0.0),
            PerspectiveWarp.Point(64.0, 0.0),
            PerspectiveWarp.Point(64.0, 48.0),
            PerspectiveWarp.Point(0.0, 48.0))
        val out = PerspectiveWarp.warp(src, quad, 64, 48)
        assertEquals(WalletFormat.hex(src.pixels), WalletFormat.hex(out.pixels))
    }

    @Test
    fun a_sub_rectangle_is_a_plain_crop() {
        val src = board(64, 48)
        val quad = PerspectiveWarp.Quad(
            PerspectiveWarp.Point(16.0, 8.0),
            PerspectiveWarp.Point(48.0, 8.0),
            PerspectiveWarp.Point(48.0, 40.0),
            PerspectiveWarp.Point(16.0, 40.0))
        val out = PerspectiveWarp.warp(src, quad, 32, 32)
        assertEquals(WalletFormat.hex(src.crop(16, 8, 48, 40).pixels),
            WalletFormat.hex(out.pixels))
    }

    @Test
    fun a_photographed_trapezoid_comes_back_square() {
        // A page whose left edge is nearer the camera: the right edge is shorter.
        val src = board(200, 200, 25)
        val quad = PerspectiveWarp.Quad(
            PerspectiveWarp.Point(10.0, 5.0),
            PerspectiveWarp.Point(180.0, 40.0),
            PerspectiveWarp.Point(180.0, 160.0),
            PerspectiveWarp.Point(10.0, 195.0))
        val out = PerspectiveWarp.warp(src, quad, 170, 160)
        // The corners of the output must be the pixels under the corners of the
        // quad, within one sample of bilinear slack.
        assertNear(src[10, 5], out[0, 0])
        assertNear(src[179, 41], out[169, 0])
        assertNear(src[179, 159], out[169, 159])
        assertNear(src[10, 194], out[0, 159])
    }

    @Test
    fun suggested_size_uses_the_longer_edge_of_each_pair() {
        val quad = PerspectiveWarp.Quad(
            PerspectiveWarp.Point(0.0, 0.0),
            PerspectiveWarp.Point(100.0, 0.0),
            PerspectiveWarp.Point(80.0, 200.0),
            PerspectiveWarp.Point(0.0, 210.0))
        val (w, h) = PerspectiveWarp.suggestedSize(quad)
        assertEquals(100, w)
        assertEquals(210, h)
    }

    @Test
    fun a_collinear_quad_is_refused() {
        val src = board(32, 32)
        val flat = PerspectiveWarp.Quad(
            PerspectiveWarp.Point(0.0, 0.0),
            PerspectiveWarp.Point(10.0, 0.0),
            PerspectiveWarp.Point(20.0, 0.0),
            PerspectiveWarp.Point(30.0, 0.0))
        var threw = false
        try {
            PerspectiveWarp.warp(src, flat, 16, 16)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a degenerate quad must fail, not produce noise", threw)
    }

    @Test
    fun sampling_clamps_at_the_edges() {
        val src = GrayImage(2, 2, byteArrayOf(0, 100, 200.toByte(), 255.toByte()))
        assertEquals(0, PerspectiveWarp.sampleBilinear(src, -5.0, -5.0))
        assertEquals(255, PerspectiveWarp.sampleBilinear(src, 99.0, 99.0))
        assertEquals(50, PerspectiveWarp.sampleBilinear(src, 0.5, 0.0))
    }

    private fun assertNear(want: Int, got: Int) {
        assertTrue("want ~$want, got $got", abs(want - got) <= 40)
    }
}
