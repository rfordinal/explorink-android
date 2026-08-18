package org.explorink.gpsbridge.wallet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.InputStream

/**
 * The one Android-only step of the pipeline: a content Uri to a [GrayImage].
 *
 * Everything after this point is plain arrays and runs in a laptop unit test
 * ([WalletPipeline]). Everything here needs a real decoder and a real
 * ContentResolver, so it is checked on a device or an emulator, not by parity.
 *
 * Three things it has to get right:
 *
 *  - **EXIF orientation.** A phone camera writes the sensor's orientation plus a
 *    tag; skip the tag and every portrait photo of a document lands sideways.
 *    Applied on the grey bytes by [Orient], not with a `Matrix` and a second
 *    `Bitmap`.
 *  - **Grayscale exactly as PIL does it.** `L = (R*19595 + G*38470 + B*7471 +
 *    0x8000) >> 16`. Verified against Pillow 12.3.0 on 50,000 random RGB triples
 *    (see `docs/android-wallet.md`); a naive `(r+g+b)/3` would put every asset
 *    off-parity for a colour source.
 *  - **A bound on the decode.** A 48 MP phone photo is 192 MB as ARGB_8888.
 *    Sources above [MAX_PIXELS] are decoded through `inSampleSize`, which is a
 *    power-of-two box filter -- so the pixels differ from a full-resolution
 *    decode. Nothing a scan or a normal photo of an A4 sheet reaches: A4 at 300
 *    DPI is 8.7 MP.
 */
object ImageImport {

    private const val TAG = "WalletImport"

    /** Above this, `inSampleSize` halves the decode until it fits. 24 MP. */
    const val MAX_PIXELS = 24_000_000

    /** What one picked image turned into. */
    class Loaded(
        val gray: GrayImage,
        val name: String,
        val dpiX: Double?,
        val dpiY: Double?,
        val exifOrientation: Int,
        val sampleSize: Int,
    )

    /**
     * Decode [uri] into grayscale, oriented upright.
     *
     * Throws [java.io.IOException] when the Uri cannot be read or does not decode;
     * the caller reports which page failed rather than half-importing an item.
     */
    fun load(context: Context, uri: Uri): Loaded {
        val name = displayName(context, uri)

        // Pass 1: bounds only, to size the sample step.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(context, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw java.io.IOException("not an image: $uri")
        }
        var sample = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / (sample.toLong() * sample) > MAX_PIXELS) {
            sample *= 2
        }

        // EXIF, from its own stream: BitmapFactory consumes the one it is given.
        var orientation = Orient.NORMAL
        var dpiX: Double? = null
        var dpiY: Double? = null
        try {
            open(context, uri).use { input ->
                val exif = ExifInterface(input)
                orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                val unit = exif.getAttributeInt(ExifInterface.TAG_RESOLUTION_UNIT, 2)
                // 2 = inches, 3 = centimetres. Anything else is not a DPI we can use.
                val rx = ratio(exif.getAttribute(ExifInterface.TAG_X_RESOLUTION))
                val ry = ratio(exif.getAttribute(ExifInterface.TAG_Y_RESOLUTION))
                if (rx != null && ry != null && rx > 0 && ry > 0) {
                    val perInch = if (unit == 3) 2.54 else 1.0
                    dpiX = rx * perInch / sample
                    dpiY = ry * perInch / sample
                }
            }
        } catch (t: Throwable) {
            // A PNG or a stripped JPEG has no EXIF at all. Not an error.
            Log.d(TAG, "no usable EXIF on $uri: $t")
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = open(context, uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw java.io.IOException("cannot decode $uri")
        val gray = try {
            toGray(bitmap)
        } finally {
            bitmap.recycle()
        }
        return Loaded(Orient.apply(gray, orientation), name, dpiX, dpiY, orientation, sample)
    }

    /**
     * ARGB bitmap to grayscale, one row at a time so the peak cost is the bitmap
     * plus one row, not the bitmap plus a full int array of it.
     */
    fun toGray(bitmap: Bitmap): GrayImage {
        val w = bitmap.width
        val h = bitmap.height
        val out = ByteArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            val base = y * w
            for (x in 0 until w) {
                out[base + x] = luma(row[x]).toByte()
            }
        }
        return GrayImage(w, h, out)
    }

    /** PIL's `rgb2l`, rounding included. Alpha is ignored, as PIL's `convert("L")` does. */
    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff
        return (r * 19595 + g * 38470 + b * 7471 + 0x8000) ushr 16
    }

    /**
     * The name to use for the item id. A content Uri has no file name, so this is
     * `DISPLAY_NAME` when the provider offers one and the last path segment
     * otherwise -- the closest equivalent of the laptop's `os.path.basename()`.
     * Same title and same names give the same item id on both sides.
     *
     * Measured on an emulator (Android 16): a MediaStore Uri answers a
     * `DISPLAY_NAME` projection with the real file name **when the share actually
     * granted read access**. Without a grant the query returns an EMPTY cursor
     * rather than throwing, and the name silently degrades to the row id ("24") --
     * which is the same condition under which the decode is about to fail with a
     * SecurityException anyway. So an unhelpful name here is a symptom, not a
     * separate problem.
     *
     * When the query answers nothing the last path segment is used. That is honest,
     * but it means an item id can differ between the phone and a laptop run of
     * `walletgen.py` on the same picture -- the id is a function of the names, and
     * the phone may not be told them. `docs/android-wallet.md` says so out loud.
     */
    fun displayName(context: Context, uri: Uri): String {
        for (projection in listOf(arrayOf(OpenableColumns.DISPLAY_NAME), null)) {
            try {
                context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) {
                        val v = c.getString(i)
                        if (!v.isNullOrEmpty()) return v
                    }
                    Log.d(TAG, "no display name column for $uri (projection " +
                        "${projection?.size ?: 0}, columns ${c.columnCount})")
                } ?: Log.d(TAG, "display name query returned null for $uri")
            } catch (t: Throwable) {
                Log.d(TAG, "display name query failed for $uri: $t")
            }
        }
        return uri.lastPathSegment ?: uri.toString()
    }

    private fun open(context: Context, uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("cannot open $uri")

    /** An EXIF rational, which ExifInterface hands back as "300/1". */
    private fun ratio(text: String?): Double? {
        if (text.isNullOrEmpty()) return null
        val slash = text.indexOf('/')
        return try {
            if (slash < 0) text.toDouble()
            else {
                val d = text.substring(slash + 1).toDouble()
                if (d == 0.0) null else text.substring(0, slash).toDouble() / d
            }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
