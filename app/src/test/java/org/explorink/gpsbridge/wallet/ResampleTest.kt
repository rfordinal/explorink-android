package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Resample] against Pillow 12.3.0, which is what `tools/walletgen.py` uses.
 *
 * The expected bytes come from `Image.resize((w, h), Image.LANCZOS)` on the same
 * deterministic source (`pixel = (x * 7 + y * 13) % 256`), recorded once. Four
 * cases on purpose: downscale in both axes, upscale in both, one axis unchanged
 * (so the two-pass code takes the single-pass branch), and a downscale on the
 * other axis only.
 *
 * A "close enough" Lanczos is not enough here: every asset id and every stored
 * byte in a wallet depends on these pixels, so one differing pixel means the
 * device and the phone disagree about what a document looks like.
 */
class ResampleTest {

    private fun source(w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) out[y * w + x] = ((x * 7 + y * 13) % 256).toByte()
        return out
    }

    private fun hex(b: ByteArray): String = WalletFormat.hex(b)

    @Test
    fun downscale_both_axes_matches_pillow() {
        assertEquals(
            "0d1d2f40502a3a4c5d6d48586a7b8b65758798a8",
            hex(Resample.resizeGray(source(12, 9), 12, 9, 5, 4)))
    }

    @Test
    fun upscale_both_axes_matches_pillow() {
        val want = "0001060b0e13171b1f23282c3034383d40454a4d04060b1013181c2024282d3135393d42454a4f52" +
            "0e11161b1e23272b2f33383c4044484d50555a5d16191e23262b2f33373b4044484c5055585d6265" +
            "1e21262b2e33373b3f43484c5054585d60656a6d26292e33363b3f43474b5054585c6065686d7275" +
            "2f32373c3f44484c5054595d6165696e71767b7e373a3f44474c5054585c6165696d7176797e8386" +
            "4043484d5055595d61656a6e72767a7f82878c8f484b5055585d6165696d72767a7e82878a8f9497" +
            "5053585d6065696d71757a7e82868a8f92979c9f585b6065686d7175797d82868a8e92979a9fa4a7" +
            "63666b7073787c8084888d9195999da2a5aaafb2686b7075787d8185898d92969a9ea2a7aaafb4b7"
        assertEquals(want, hex(Resample.resizeGray(source(12, 9), 12, 9, 20, 14)))
    }

    @Test
    fun width_unchanged_matches_pillow() {
        assertEquals(
            "080f161d242b323940474e55252c333a41484f565d646b72434a51585f666d747b8289" +
                "9060676e757c838a91989fa6ad",
            hex(Resample.resizeGray(source(12, 9), 12, 9, 12, 4)))
    }

    @Test
    fun height_unchanged_matches_pillow() {
        assertEquals(
            "071c3114293e21364b2e43583b5065485d72556a7f62778c6f84997c91a6899eb396abc0",
            hex(Resample.resizeGray(source(9, 12), 9, 12, 3, 12)))
    }

    @Test
    fun same_size_is_a_copy() {
        val src = source(7, 5)
        val out = Resample.resizeGray(src, 7, 5, 7, 5)
        assertEquals(hex(src), hex(out))
    }

    @Test
    fun a_flat_image_stays_flat() {
        // Lanczos overshoots at edges; on a flat field there is nothing to
        // overshoot, and the fixed-point rounding must not drift either.
        val src = ByteArray(40 * 30) { 200.toByte() }
        val out = Resample.resizeGray(src, 40, 30, 17, 44)
        for (b in out) assertEquals(200, b.toInt() and 0xff)
    }
}
