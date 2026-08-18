package org.explorink.gpsbridge.wallet

/**
 * EXIF orientation, applied to a grayscale raster.
 *
 * Step 1 of the pipeline (`docs/wallet-format.md` section 6) is what
 * `ImageOps.exif_transpose()` does on the laptop. A phone camera almost never
 * rotates the pixels it writes -- it writes the sensor's orientation and an EXIF
 * tag saying which way is up -- so skipping this step means a sideways document,
 * every time, for photos taken in portrait.
 *
 * The eight cases and their PIL equivalents (`PIL/ImageOps.py`, `exif_transpose`):
 *
 *     1 normal              (nothing)
 *     2 FLIP_LEFT_RIGHT     3 ROTATE_180        4 FLIP_TOP_BOTTOM
 *     5 TRANSPOSE           6 ROTATE_270        7 TRANSVERSE      8 ROTATE_90
 *
 * Note 6 and 8: PIL's rotations are counter-clockwise, so EXIF 6 ("rotate 90 CW
 * to display") is PIL's ROTATE_270. Getting that pair backwards puts the page
 * upside down instead of upright, which is why they are pinned by test.
 *
 * Pure arithmetic on bytes -- no Bitmap, no Matrix -- so it is unit-testable and
 * costs one pass over the pixels.
 */
object Orient {

    const val NORMAL = 1
    const val FLIP_HORIZONTAL = 2
    const val ROTATE_180 = 3
    const val FLIP_VERTICAL = 4
    const val TRANSPOSE = 5
    const val ROTATE_90_CW = 6
    const val TRANSVERSE = 7
    const val ROTATE_270_CW = 8

    /** Apply an EXIF orientation value. Unknown values are treated as normal. */
    fun apply(img: GrayImage, exifOrientation: Int): GrayImage {
        val w = img.width
        val h = img.height
        return when (exifOrientation) {
            FLIP_HORIZONTAL -> map(img, w, h) { x, y -> pair(w - 1 - x, y) }
            ROTATE_180 -> map(img, w, h) { x, y -> pair(w - 1 - x, h - 1 - y) }
            FLIP_VERTICAL -> map(img, w, h) { x, y -> pair(x, h - 1 - y) }
            TRANSPOSE -> map(img, h, w) { x, y -> pair(y, x) }
            // EXIF 6: the display needs a 90 degrees clockwise turn.
            ROTATE_90_CW -> map(img, h, w) { x, y -> pair(y, h - 1 - x) }
            TRANSVERSE -> map(img, h, w) { x, y -> pair(w - 1 - y, h - 1 - x) }
            // EXIF 8: 90 degrees counter-clockwise.
            ROTATE_270_CW -> map(img, h, w) { x, y -> pair(w - 1 - y, x) }
            else -> img
        }
    }

    /** True when this orientation swaps width and height. */
    fun swapsAxes(exifOrientation: Int): Boolean = exifOrientation in intArrayOf(
        TRANSPOSE, ROTATE_90_CW, TRANSVERSE, ROTATE_270_CW)

    // (x, y) packed into a Long so the mapper allocates nothing per pixel.
    private fun pair(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)

    private inline fun map(src: GrayImage, outW: Int, outH: Int,
                           where: (Int, Int) -> Long): GrayImage {
        val out = ByteArray(outW * outH)
        var at = 0
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                val p = where(x, y)
                val sx = (p shr 32).toInt()
                val sy = (p and 0xffffffffL).toInt()
                out[at++] = src.pixels[sy * src.width + sx]
            }
        }
        return GrayImage(outW, outH, out)
    }
}
