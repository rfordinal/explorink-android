package org.explorink.gpsbridge.wallet

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.Result
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader

/**
 * Finding codes in a photo, and reading them back out of a stored asset.
 *
 * Two jobs, one decoder:
 *
 *  - [detect] runs over an imported photograph. It only ever produces a
 *    **payload plus a symbology** -- the photo is never cropped, never scaled into
 *    an asset, never shown to the rider as the code. [CodeWriter] regenerates a
 *    clean one from the payload (`docs/wallet-format.md` section 10).
 *  - [decodeAsset] and [verify] close the verify loop on the **stored asset
 *    bytes**: unpack, put them back the way the rider will see them, decode. Not
 *    an intermediate image -- the bytes that go on the card. `verified` in the
 *    manifest is the result of that comparison and nothing else.
 *
 * A symbology that cannot be decoded back is `verified = false`, and the device
 * must not present it as trusted (brief section 11).
 */
object CodeReader {

    /** One decoded code: what it is, what it says, and what it took to see it. */
    data class Found(val symbology: Symbology, val payload: String, val stage: String)

    /** At most this many distinct codes are taken from one photo. */
    const val MAX_CODES_PER_PAGE = 8

    private val FORMATS = mapOf(
        BarcodeFormat.QR_CODE to Symbology.QR,
        BarcodeFormat.PDF_417 to Symbology.PDF417,
        BarcodeFormat.AZTEC to Symbology.AZTEC,
        BarcodeFormat.DATA_MATRIX to Symbology.DATAMATRIX,
        BarcodeFormat.CODE_128 to Symbology.CODE128,
        BarcodeFormat.EAN_13 to Symbology.EAN13,
    )

    private val SYMBOLOGY_FORMAT = FORMATS.entries.associate { it.value to it.key }

    private fun hints(): Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to FORMATS.keys.toList(),
        DecodeHintType.TRY_HARDER to true,
        // A photo of a screen or a laminated pass can come back inverted.
        DecodeHintType.ALSO_INVERTED to true,
    )

    /**
     * The attempt ladder, in this order, stopping at the first stage that finds
     * anything.
     *
     * Each element is (downsample step, binarizer, quarter turns). Why each one:
     *
     *  - **step** 1, 2, 4: a phone photo is far bigger than the code needs, and
     *    downsampling averages away sensor noise and JPEG ringing. Measured: some
     *    blurred and noisy renders are found only at step 2 or 4
     *    (`docs/android-wallet.md` section 13).
     *  - **binarizer**: `HybridBinarizer` is local and handles uneven lighting;
     *    `GlobalHistogramBinarizer` is one threshold for the whole image and wins
     *    on flat, clean renders where the local one over-reacts.
     *  - **quarter turns** 0 and 1: ZXing's 1D readers scan rows, so a Code128 or
     *    EAN13 photographed with the bars running down the image is invisible
     *    until the image is turned. 2D readers do not need it.
     */
    private val STAGES: List<Triple<Int, String, Int>> = listOf(
        Triple(1, "hybrid", 0),
        Triple(1, "hybrid", 1),
        Triple(1, "global", 0),
        Triple(1, "global", 1),
        Triple(2, "hybrid", 0),
        Triple(2, "hybrid", 1),
        Triple(2, "global", 0),
        Triple(4, "hybrid", 0),
        Triple(4, "hybrid", 1),
        Triple(4, "global", 0),
    )

    /** Below this on the short side an image is not worth another downsample. */
    private const val MIN_SHORT_SIDE = 120

    /**
     * Every code in [gray], as payload plus symbology.
     *
     * Deterministic: the ladder is a fixed order and the results of one stage are
     * returned in the decoder's own order, deduplicated by (symbology, payload).
     * Never throws -- a photo with no code returns an empty list, which is exactly
     * what a page of a passport should produce.
     */
    fun detect(gray: GrayImage, maxCodes: Int = MAX_CODES_PER_PAGE): List<Found> {
        val out = LinkedHashMap<Pair<Symbology, String>, Found>()
        for ((step, binarizer, turns) in STAGES) {
            if (step > 1 && minOf(gray.width, gray.height) / step < MIN_SHORT_SIDE) continue
            val source = source(gray, step, turns)
            val label = "step$step/$binarizer/${turns * 90}deg"
            for (res in decodeMultiple(source, binarizer)) {
                val sym = FORMATS[res.barcodeFormat] ?: continue
                val text = res.text ?: continue
                if (text.isEmpty()) continue
                out.putIfAbsent(Pair(sym, text), Found(sym, text, label))
                if (out.size >= maxCodes) return out.values.toList()
            }
            if (out.isNotEmpty()) return out.values.toList()
        }
        return out.values.toList()
    }

    /**
     * Decode the code straight out of the packed device asset.
     *
     * This is the whole point of the verify loop: not the intermediate image, but
     * the bytes the device will blit. Unpack, put it back the way the rider will
     * see it (a portrait asset needs unrotating, a landscape one does not), decode.
     */
    fun decodeAsset(assetPayload: ByteArray, panel: PanelProfile,
                    presentation: Int = WalletFormat.PRESENTATION_PORTRAIT): List<Found> {
        return detect(assetImage(assetPayload, panel, presentation).toGray())
    }

    /** The stored asset as the rider sees it: unpacked, and unrotated if portrait. */
    fun assetImage(assetPayload: ByteArray, panel: PanelProfile,
                   presentation: Int): MonoImage {
        val stored = MonoImage.unpack1bpp(assetPayload, panel.width, panel.height)
        return if (presentation == WalletFormat.PRESENTATION_PORTRAIT) {
            stored.unrotateNative()
        } else {
            stored
        }
    }

    /**
     * True only when the rendered asset decodes back to the same bytes.
     *
     * Byte for byte, and the symbology has to match too -- a payload that comes
     * back out of a different format is not the code we were asked to store.
     */
    fun verify(assetPayload: ByteArray, panel: PanelProfile, symbology: Symbology,
               expected: String, presentation: Int): Boolean {
        val want = expected.toByteArray(Charsets.UTF_8)
        for (found in decodeAsset(assetPayload, panel, presentation)) {
            if (found.symbology == symbology &&
                found.payload.toByteArray(Charsets.UTF_8).contentEquals(want)) {
                return true
            }
        }
        return false
    }

    /** The ZXing format for a symbology. Test and debug use. */
    fun zxingFormat(symbology: Symbology): BarcodeFormat =
        SYMBOLOGY_FORMAT.getValue(symbology)

    // --- the decoder ---------------------------------------------------------

    private fun decodeMultiple(source: LuminanceSource, binarizer: String): List<Result> {
        val bitmap = BinaryBitmap(
            if (binarizer == "hybrid") HybridBinarizer(source) else GlobalHistogramBinarizer(source))
        val reader = MultiFormatReader().apply { setHints(hints()) }
        return try {
            GenericMultipleBarcodeReader(reader).decodeMultiple(bitmap, hints()).toList()
        } catch (t: Throwable) {
            // NotFoundException is the normal answer for "no code here", and a
            // damaged image can also throw FormatException or ChecksumException out
            // of a reader. None of them is a failure of the import.
            emptyList()
        }
    }

    private fun source(gray: GrayImage, step: Int, turns: Int): LuminanceSource {
        var img = if (step > 1) downsample(gray, step) else gray
        var src: LuminanceSource = GrayLuminanceSource(img)
        for (i in 0 until turns) src = src.rotateCounterClockwise()
        return src
    }

    /**
     * Box-average downsample by an integer step. Not Pillow's Lanczos and it does
     * not have to be: nothing about detection touches asset bytes, and a box
     * average is what suppresses sensor noise best.
     */
    fun downsample(gray: GrayImage, step: Int): GrayImage {
        require(step >= 1) { "step must be positive" }
        if (step == 1) return gray
        val w = gray.width / step
        val h = gray.height / step
        require(w > 0 && h > 0) { "downsample by $step leaves nothing of ${gray.width}x${gray.height}" }
        val out = ByteArray(w * h)
        val n = step * step
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                for (dy in 0 until step) {
                    val base = (y * step + dy) * gray.width + x * step
                    for (dx in 0 until step) sum += gray.pixels[base + dx].toInt() and 0xff
                }
                out[y * w + x] = (sum / n).toByte()
            }
        }
        return GrayImage(w, h, out)
    }
}

/**
 * A [GrayImage] as a ZXing luminance source.
 *
 * Rotation and cropping are supported because the readers need them: the 1D
 * readers only scan rows, so a barcode photographed sideways needs
 * `rotateCounterClockwise`, and `GenericMultipleBarcodeReader` crops quadrants to
 * find a second code.
 */
class GrayLuminanceSource private constructor(
    private val pixels: ByteArray,
    private val stride: Int,
    private val left: Int,
    private val top: Int,
    width: Int,
    height: Int,
) : LuminanceSource(width, height) {

    constructor(gray: GrayImage) : this(gray.pixels, gray.width, 0, 0, gray.width, gray.height)

    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        require(y in 0 until height) { "row $y is outside 0..${height - 1}" }
        val out = if (row == null || row.size < width) ByteArray(width) else row
        System.arraycopy(pixels, (top + y) * stride + left, out, 0, width)
        return out
    }

    override fun getMatrix(): ByteArray {
        if (left == 0 && top == 0 && stride == width && pixels.size == width * height) return pixels
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            System.arraycopy(pixels, (top + y) * stride + left, out, y * width, width)
        }
        return out
    }

    override fun isCropSupported(): Boolean = true

    override fun crop(left: Int, top: Int, width: Int, height: Int): LuminanceSource =
        GrayLuminanceSource(pixels, stride, this.left + left, this.top + top, width, height)

    override fun isRotateSupported(): Boolean = true

    /** A copy turned a quarter turn counter-clockwise: `out(x, y) = in(y, w - 1 - x)`. */
    override fun rotateCounterClockwise(): LuminanceSource {
        val out = ByteArray(width * height)
        for (y in 0 until width) {
            for (x in 0 until height) {
                out[y * height + x] = pixels[(top + x) * stride + left + (width - 1 - y)]
            }
        }
        return GrayLuminanceSource(out, height, 0, 0, height, width)
    }
}
