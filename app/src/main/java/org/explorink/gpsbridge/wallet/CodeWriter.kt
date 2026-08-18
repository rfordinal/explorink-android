package org.explorink.gpsbridge.wallet

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.aztec.AztecWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.datamatrix.DataMatrixWriter
import com.google.zxing.oned.Code128Writer
import com.google.zxing.oned.EAN13Writer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Payload in, a clean machine-readable code on a full device screen out.
 *
 * The rules are `docs/wallet-format.md` section 10, all of them: pure black and
 * white, no dither, no grey, integer module size, the largest one that still
 * fits, a quiet zone of at least 4 modules, centred, and the orientation chosen
 * by arithmetic.
 *
 * **A code is never cropped out of the photo.** The photo only supplies the
 * payload ([CodeReader]); everything drawn here is regenerated from that payload.
 *
 * The module bits come from ZXing's Java writers, the geometry from
 * [CodeLayout]. Where ZXing's writers differ from the laptop generator's
 * (zxing-cpp + segno) the difference is recorded in `docs/android-wallet.md`
 * section 13 and proved harmless by decoding the stored bytes back
 * ([CodeReader.verify]).
 */
object CodeWriter {

    // --- conventions this side shares with the generator ---------------------

    /**
     * How tall a 1D code is drawn, in modules. Code128 and EAN13 have no height
     * of their own -- a bar is as tall as the printer makes it -- and the
     * generator's writer draws 50 modules, which is what the measured table in
     * `docs/wallet-format.md` section 10 was computed from (code128 123x50,
     * ean13 95x55 = 50 + the guard extension). Matching it keeps the two
     * implementations' geometry identical.
     */
    const val ONE_D_BAR_HEIGHT_MODULES = 50

    /**
     * EAN13 guard bars run 5 modules below the data bars, which is why the
     * generator's EAN13 matrix is 95x55 and not 95x50. ZXing's `EAN13Writer`
     * draws a flat rectangle, so the extension is added here.
     *
     * It is not cosmetic: the 5 rows change the matrix height, which changes the
     * module size the layout arithmetic can grant (6 px with them, 7 px without).
     * Dropping them would put every EAN13 asset on the phone at a different
     * module size from the laptop's for the same payload.
     */
    const val EAN13_GUARD_EXTENSION_MODULES = 5

    /**
     * EAN13 is always 95 modules wide, with guard patterns at these columns:
     * start `101`, centre `01010`, end `101` (ISO/IEC 15420). The extension rows
     * copy the encoder's own bits inside these ranges, so nothing here decides
     * what a guard bar looks like.
     */
    private val EAN13_GUARD_RANGES = listOf(0..2, 45..49, 92..94)
    private const val EAN13_MODULES_WIDE = 95

    /**
     * PDF417 rows are drawn 3 modules tall. That is the symbology's own
     * convention (the standard wants a row height of at least 3x the module
     * width) and it is what the generator's writer does: its boarding-pass
     * matrix is 171x48 for 16 codeword rows.
     */
    const val PDF417_ROW_HEIGHT_MODULES = 3

    /** PDF417 shape search space. 1..30 data columns is the symbology's range. */
    private val PDF417_COLUMNS = 1..30

    /**
     * PDF417 error-correction levels worth considering. Level 0 and 1 are below
     * what the standard recommends for payloads of this size; 2..5 covers the
     * recommendation for 1..320 data codewords.
     */
    private val PDF417_ECC_LEVELS = 2..5

    /**
     * QR error correction. The contract names **M** as the baseline: `H` survives
     * more damage but grows the matrix, and on a fixed 480 px panel a bigger
     * matrix means smaller modules, which is the thing that actually breaks a
     * scan off glass (`docs/wallet-format.md` section 10).
     */
    private val QR_BASELINE_ECC = ErrorCorrectionLevel.M

    /** Tried in this order by [freeErrorCorrection]; the first that fits wins. */
    private val QR_ECC_DESCENDING = listOf(
        ErrorCorrectionLevel.H, ErrorCorrectionLevel.Q, ErrorCorrectionLevel.M)

    /**
     * ONE rule, two symbologies: **take error correction that costs nothing.**
     *
     * The baseline is what the contract names (QR M, PDF417 the level the
     * standard recommends). If a stronger level still lands on the same module
     * size -- same QR version, same PDF417 shape -- the stronger level is free
     * damage tolerance and is taken.
     *
     * This is also what the generator does for QR without saying so: segno's
     * `boost_error` default is True, so `error="m"` on a short payload actually
     * emits **H**. Measured, both sides: `TEST12345` is version 1 at 21x21
     * whether it is M or H, and segno reports `error='H'`.
     */
    const val FREE_ERROR_CORRECTION = true

    // --- the module matrix ---------------------------------------------------

    /**
     * A code as a module matrix: one entry per module, true = dark. No scaling,
     * no quiet zone -- both belong to the layout.
     *
     * [note] records the parameters that were chosen (the QR ECC level, the
     * PDF417 shape), because for PDF417 they are not fixed: the shape is picked
     * to maximise the module size on THIS panel.
     */
    class Matrix(val width: Int, val height: Int, val dark: BooleanArray, val note: String) {
        init {
            require(width > 0 && height > 0) { "empty matrix ${width}x$height" }
            require(dark.size == width * height) {
                "expected ${width * height} modules for ${width}x$height, got ${dark.size}"
            }
        }

        operator fun get(x: Int, y: Int): Boolean = dark[y * width + x]

        /** Repeat every row [factor] times. PDF417's row height, nothing else. */
        fun stretchRows(factor: Int): Matrix {
            if (factor == 1) return this
            val out = BooleanArray(width * height * factor)
            for (y in 0 until height) {
                for (r in 0 until factor) {
                    System.arraycopy(dark, y * width, out, ((y * factor) + r) * width, width)
                }
            }
            return Matrix(width, height * factor, out, note)
        }
    }

    /** The module matrix for one payload, on one panel. */
    fun matrix(symbology: Symbology, payload: String, panel: PanelProfile): Matrix {
        require(payload.isNotEmpty()) { "empty payload for ${symbology.key}" }
        return when (symbology) {
            Symbology.QR -> qrMatrix(payload)
            Symbology.AZTEC -> fromBitMatrix(
                AztecWriter().encode(payload, BarcodeFormat.AZTEC, 1, 1, marginZero()), "aztec")
            Symbology.DATAMATRIX -> fromBitMatrix(
                DataMatrixWriter().encode(payload, BarcodeFormat.DATA_MATRIX, 1, 1, marginZero()),
                "datamatrix")
            Symbology.CODE128 -> fromBitMatrix(
                Code128Writer().encode(payload, BarcodeFormat.CODE_128, 1,
                    ONE_D_BAR_HEIGHT_MODULES, marginZero()),
                "code128 bars=$ONE_D_BAR_HEIGHT_MODULES")
            Symbology.EAN13 -> ean13Matrix(payload)
            Symbology.PDF417 -> pdf417Matrix(payload, panel)
        }
    }

    private fun marginZero(): Map<EncodeHintType, Any> =
        mapOf(EncodeHintType.MARGIN to 0)

    private fun fromBitMatrix(bm: BitMatrix, note: String): Matrix {
        val w = bm.width
        val h = bm.height
        val dark = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) dark[y * w + x] = bm.get(x, y)
        return Matrix(w, h, dark, note)
    }

    /** QR at the baseline ECC, upgraded for free when the version does not grow. */
    private fun qrMatrix(payload: String): Matrix {
        val baseline = qrAt(payload, QR_BASELINE_ECC)
        if (!FREE_ERROR_CORRECTION) return baseline
        for (level in QR_ECC_DESCENDING) {
            if (level == QR_BASELINE_ECC) break
            val tried = try {
                qrAt(payload, level)
            } catch (e: Exception) {
                null
            }
            if (tried != null && tried.width == baseline.width && tried.height == baseline.height) {
                return tried
            }
        }
        return baseline
    }

    private fun qrAt(payload: String, level: ErrorCorrectionLevel): Matrix = fromBitMatrix(
        QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 1, 1,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to level)),
        "qr ecc=$level")

    /** EAN13 bars plus the guard-bar extension the generator's writer draws. */
    private fun ean13Matrix(payload: String): Matrix {
        val bars = fromBitMatrix(
            EAN13Writer().encode(payload, BarcodeFormat.EAN_13, 1,
                ONE_D_BAR_HEIGHT_MODULES, marginZero()),
            "ean13 bars=$ONE_D_BAR_HEIGHT_MODULES+$EAN13_GUARD_EXTENSION_MODULES")
        require(bars.width == EAN13_MODULES_WIDE) {
            "EAN13 is ${bars.width} modules wide, expected $EAN13_MODULES_WIDE"
        }
        val h = bars.height + EAN13_GUARD_EXTENSION_MODULES
        val dark = BooleanArray(bars.width * h)
        System.arraycopy(bars.dark, 0, dark, 0, bars.dark.size)
        for (y in bars.height until h) {
            for (range in EAN13_GUARD_RANGES) {
                for (x in range) dark[y * bars.width + x] = bars[x, 0]
            }
        }
        return Matrix(bars.width, h, dark, bars.note)
    }

    /**
     * PDF417, with its shape chosen by the contract's own render rule.
     *
     * PDF417 is the only symbology whose matrix shape is a free choice: the same
     * payload fits in 1..30 data columns, and fewer columns means a wider row
     * count and a narrower code. The generator leaves that choice to zxing-cpp's
     * internal aspect-ratio heuristic; there is no way to ask ZXing's Java
     * encoder for the same shape, and its bits differ from zxing-cpp's even when
     * the shape is forced to match (measured, `docs/android-wallet.md`
     * section 13).
     *
     * So the shape is decided here, by the rule that already governs everything
     * else on this screen -- **the largest module that fits**: lay out every
     * (columns, ECC level) pair both ways round, take the biggest module, and
     * among the ties take the strongest error correction (free damage tolerance,
     * [FREE_ERROR_CORRECTION]) and then the fewest columns.
     */
    private fun pdf417Matrix(payload: String, panel: PanelProfile): Matrix {
        var best: Matrix? = null
        var bestModule = 0
        var bestEcc = -1
        var bestCols = Int.MAX_VALUE
        for (cols in PDF417_COLUMNS) {
            for (ecc in PDF417_ECC_LEVELS) {
                val raw = try {
                    pdf417At(payload, cols, ecc)
                } catch (e: Exception) {
                    // Too much data for this shape, or too many rows. Not an error:
                    // it is one candidate of many.
                    continue
                }
                val m = raw.stretchRows(PDF417_ROW_HEIGHT_MODULES)
                val module = CodeLayout.bestModuleSize(m.width, m.height, Symbology.PDF417, panel)
                val better = module > bestModule ||
                    (module == bestModule && ecc > bestEcc) ||
                    (module == bestModule && ecc == bestEcc && cols < bestCols)
                if (better) {
                    best = m
                    bestModule = module
                    bestEcc = ecc
                    bestCols = cols
                }
            }
        }
        return best ?: throw IllegalArgumentException(
            "pdf417 cannot carry ${payload.length} characters in any shape")
    }

    private fun pdf417At(payload: String, cols: Int, ecc: Int): Matrix {
        val enc = com.google.zxing.pdf417.encoder.PDF417()
        // (maxCols, minCols, maxRows, minRows): pin the columns, leave the rows to
        // the encoder. 3..90 rows is the symbology's range.
        enc.setDimensions(cols, cols, 90, 3)
        enc.generateBarcodeLogic(payload, ecc)
        val scaled = enc.barcodeMatrix.getScaledMatrix(1, 1)
        val h = scaled.size
        val w = scaled[0].size
        val dark = BooleanArray(w * h)
        for (y in 0 until h) {
            // THE TRAP: `BarcodeMatrix.getScaledMatrix()` returns the rows
            // **bottom to top** (`matrixOut[yMax - i - 1] = matrix[i / yScale]`),
            // and ZXing's own `PDF417Writer` silently flips them back. Used as it
            // comes, the symbol is upside down: PDF417 row indicators carry the row
            // number, so the codewords no longer agree with their position.
            //
            // Measured, and worth knowing because the failure looks like something
            // else entirely: zxing-cpp's reader still decodes the flipped symbol
            // (payload and all), while ZXing's own PDF417 reader answers
            // ChecksumException -- so a laptop cross-check would have called it
            // fine and only the verify loop caught it.
            val src = h - 1 - y
            for (x in 0 until w) dark[y * w + x] = scaled[src][x] != 0.toByte()
        }
        return Matrix(w, h, dark, "pdf417 cols=$cols ecc=$ecc rowHeight=$PDF417_ROW_HEIGHT_MODULES")
    }

    // --- rendering -----------------------------------------------------------

    /**
     * A rendered code: the canvas, its geometry, the matrix behind it, and where
     * on the canvas the modules were drawn ([drawnX], [drawnY] -- the top-left of
     * the code itself, quiet zone not included).
     */
    class Rendered(val canvas: MonoImage, val layout: CodeLayout.Layout, val matrix: Matrix,
                   val drawnX: Int, val drawnY: Int)

    /**
     * Draw one code on a full screen, in [orientation] (`"auto"`, `"portrait"` or
     * `"landscape"`).
     *
     * The canvas is in that orientation's own coordinates: portrait is the
     * logical screen and needs the same rotation as any tile, landscape already
     * IS panel-native. [pack] is the only place that knows the difference.
     *
     * Nothing interpolates: a module is a solid `module x module` block of black
     * or white pixels, so no module edge can come out grey.
     */
    fun render(symbology: Symbology, payload: String, panel: PanelProfile,
               orientation: String = "auto"): Rendered {
        val m = matrix(symbology, payload, panel)
        val chosen = if (orientation == "auto") {
            CodeLayout.chooseOrientation(m.width, m.height, symbology, panel)
        } else {
            orientation
        }
        val layout = CodeLayout.layout(m.width, m.height, symbology, panel, chosen)
        val (cw, ch) = CodeLayout.canvasSize(panel, chosen)
        if (layout.moduleSize < 1) {
            throw IllegalArgumentException(
                "${symbology.key} payload does not fit: ${m.width} x ${m.height} modules plus a " +
                "${layout.quietZone}-module quiet zone needs ${m.width + 2 * layout.quietZone} x " +
                "${m.height + 2 * layout.quietZone} px, panel ${panel.name} in $chosen gives " +
                "$cw x $ch")
        }

        val module = layout.moduleSize
        val drawnW = m.width * module
        val drawnH = m.height * module
        val x0 = (cw - drawnW) / 2
        val y0 = (ch - drawnH) / 2
        val px = ByteArray(cw * ch)
        java.util.Arrays.fill(px, 255.toByte())      // white paper
        for (my in 0 until m.height) {
            for (mx in 0 until m.width) {
                if (!m[mx, my]) continue
                val px0 = x0 + mx * module
                val py0 = y0 + my * module
                for (dy in 0 until module) {
                    val base = (py0 + dy) * cw + px0
                    java.util.Arrays.fill(px, base, base + module, 0)   // black ink
                }
            }
        }
        return Rendered(MonoImage(cw, ch, px), layout, m, x0, y0)
    }

    /**
     * Canvas to stored asset bytes. The one place orientation means anything.
     *
     * A portrait canvas is logical and is rotated like any tile. A landscape
     * canvas already IS panel-native -- same axes, same byte order, no rotation --
     * which is exactly what `presentation = 0` tells the device.
     */
    fun pack(canvas: MonoImage, panel: PanelProfile, orientation: String): ByteArray =
        when (orientation) {
            CodeLayout.PORTRAIT -> {
                require(canvas.width == panel.tileW && canvas.height == panel.tileH) {
                    "portrait canvas is ${canvas.width}x${canvas.height}, panel ${panel.name} " +
                        "wants ${panel.tileW}x${panel.tileH}"
                }
                canvas.packNativeRegion(0, 0, panel.tileW, panel.tileH)
            }
            CodeLayout.LANDSCAPE -> {
                require(canvas.width == panel.width && canvas.height == panel.height) {
                    "landscape canvas is ${canvas.width}x${canvas.height}, panel ${panel.name} " +
                        "is ${panel.width}x${panel.height}"
                }
                canvas.pack1bpp()
            }
            else -> throw IllegalArgumentException(
                "orientation must be one of ${CodeLayout.ORIENTATIONS}")
        }
}
