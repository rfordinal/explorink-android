package org.explorink.gpsbridge.wallet

/**
 * Four-level grey for a wallet document (`docs/wallet-format.md` section 13,
 * phase P2b). The phone half of it: quantise, pack 2bpp, and bake the three
 * planes.
 *
 * The panel does **4 grey levels, not 1 bit**
 * (`firmware/explorink/docs/eink-grayscale.md:3`), and grey is a **per-document**
 * property decided on the glass 2026-08-18 (`docs/wallet-plan.md` 7k): a scan or a
 * photo earns the tone, a page of text the rider pans around does not, because a
 * grey frame costs 2,604 ms and a baked plane set cannot pan at all.
 *
 * Level numbering, read off the firmware's own 2bpp consumer
 * (`lib/Epub/Epub/converters/DirectPixelWriter.h:147-165`, and the same numbering
 * in `lib/GfxRenderer/GfxRenderer.cpp:444-446`):
 *
 *     0 black    1 dark grey    2 light grey    3 white
 *
 * Two things that are silent when wrong, and both live here and nowhere else:
 *
 *  - **A grey pixel is a black pixel the planes lighten.** Black *and both greys*
 *    lay ink in the BW base. Lose the planes and a grey pixel reads full black,
 *    not white -- contrast collapses, it does not wash out.
 *  - **A set plane bit means "nudge this pixel"**, which is inverted relative to
 *    the framebuffer, where a *clear* bit is ink (`GrayShade.h:38-44`,
 *    `GfxRenderer.cpp:515-524`).
 *
 * Pure JVM: no Android, so it runs in `./gradlew test`, and an iOS port lifts it
 * unchanged.
 */
object Grey {

    const val BLACK = 0
    const val DARK = 1
    const val LIGHT = 2
    const val WHITE = 3
    const val LEVEL_COUNT = 4

    /**
     * Nearest level, no dithering.
     *
     * `(43, 128, 213)` and `round(value * 3 / 255)` are the same function, pinned by
     * a test over all 256 inputs. The table is the normative form because it has no
     * rounding-mode hazard. **No error diffusion, deliberately**: grey exists to be
     * smoother than dither, and diffusing into four levels would put the pattern
     * back and make the on-glass comparison meaningless. The cost is stated rather
     * than hidden -- a smooth ramp bands into four steps.
     *
     * Open, needs measurement: this assumes the four levels are evenly spaced in
     * reflectance. Nobody has photographed a four-step wedge off the panel.
     */
    val THRESHOLDS = intArrayOf(43, 128, 213)

    /**
     * Where a photograph's white starts, for [photoCurve].
     *
     * **Measured, on the maintainer's own photograph, 2026-08-20.** A camera picture of
     * a normal scene has a mean around 114 and only about 5 % of it above 200, and error
     * diffusion is faithful to that mean -- so the panel drew 41 % light grey against
     * 10 % paper white and the whole picture read as grey. The one bright thing an e-ink
     * panel owns is the paper, and a faithful transfer barely used it.
     *
     * Clipping at 200 triples the white (10.5 % to 27.8 %) and leaves the black almost
     * alone (21.6 % to 19.3 %). A gamma lift was tried against it and rejected: gamma
     * 1.5 moves the mid tone from 128 to 195, which buys more white by flattening
     * everything that was meant to be distinguishable, and it halves the black.
     */
    const val PHOTO_WHITE_POINT = 200

    /**
     * The tone curve a photograph goes through before quantisation: stretch 0..200 over
     * the full range, and anything at or above 200 is paper white.
     *
     * Integer arithmetic, and the same expression in `walletgen.py` -- a table both
     * implementations compute rather than share, so it is pinned by a test over all 256
     * inputs and by the `wallet-parity-photo` fixture.
     */
    fun photoCurve(whitePoint: Int = PHOTO_WHITE_POINT): IntArray = IntArray(256) { i ->
        if (i >= whitePoint) 255 else (i * 255 / whitePoint).coerceIn(0, 255)
    }

    fun levelOf(value: Int): Int = when {
        value < THRESHOLDS[0] -> BLACK
        value < THRESHOLDS[1] -> DARK
        value < THRESHOLDS[2] -> LIGHT
        else -> WHITE
    }

    /** Which levels put a **1** bit in each plane. */
    val PLANE_NAMES = listOf("base", "lsb", "msb")

    /** Rows in one plane band. `GrayscaleFrame.h:101-102` (`STRIP_ROWS`). */
    const val PLANE_BAND_ROWS = 80

    const val PLANE_COUNT = 3

    /**
     * `base` is the BW framebuffer, where a **set** bit is white (no ink), so only
     * white sets it. `lsb` nudges dark grey alone; `msb` nudges either grey. Read
     * off `GrayShade.h:26-48`.
     */
    fun planeSets(plane: String): Set<Int> = when (plane) {
        "base" -> setOf(WHITE)
        "lsb" -> setOf(DARK)
        "msb" -> setOf(DARK, LIGHT)
        else -> throw IllegalArgumentException("unknown plane '$plane'")
    }

    fun bandCount(panelHeight: Int): Int =
        (panelHeight + PLANE_BAND_ROWS - 1) / PLANE_BAND_ROWS
}

/**
 * A raster of level indices 0..3, one byte per pixel. The grey pipeline's working
 * image, the exact analogue of [MonoImage] for the 1bpp path.
 *
 * Grey and 1bpp quantise the **same level canvas** -- same scale, same
 * `autocontrast`, same letterbox -- so the only difference on the card is grey
 * against dither, not tone mapping. That is what makes the panel comparison fair.
 */
class GreyLevels(val width: Int, val height: Int, val levels: ByteArray) {

    init {
        require(levels.size == width * height) {
            "expected ${width * height} bytes for ${width}x$height, got ${levels.size}"
        }
    }

    operator fun get(x: Int, y: Int): Int = levels[y * width + x].toInt() and 0xff

    companion object {

        /**
         * What each level is assumed to reflect, 0..255, for error diffusion.
         *
         * **Assumed, not measured.** Evenly spaced is the same assumption
         * [Grey.THRESHOLDS] already makes. If the panel's two middle levels turn out
         * to sit closer together than this, a diffused photo comes out with the wrong
         * mid tone -- the fix is to photograph a four-step wedge off the glass and put
         * the real numbers here, which is the open item in
         * `firmware/explorink/docs/wallet-grey.md`.
         */
        val LEVEL_VALUES = intArrayOf(0, 85, 170, 255)

        /**
         * Four levels with **Floyd-Steinberg error diffusion**, for photographs.
         *
         * [quantise] takes the nearest level per pixel, which is right for a scan: flat
         * paper stays flat and text stays hard. On a photograph it posterises -- a sky
         * becomes three bands, and the maintainer's own photo on the panel is what
         * produced this function (2026-08-19). Diffusion trades those bands for a fine
         * pattern, which at arm's length reads as a tone.
         *
         * Same weights and same serpentine-free order as [Dither.floydSteinberg], so
         * the two paths are one algorithm at two bit depths: 7/3/5/1, left to right,
         * error carried in an int accumulator rather than in the image.
         *
         * It does **not** replace [quantise]. A document rendered this way gains a
         * dither pattern in its white margins, which is exactly what grey was supposed
         * to remove; the caller picks per document.
         */
        fun diffuse(src: GrayImage, levelValues: IntArray = LEVEL_VALUES): GreyLevels {
            require(levelValues.size == Grey.LEVEL_COUNT) {
                "expected ${Grey.LEVEL_COUNT} level values, got ${levelValues.size}"
            }
            val w = src.width
            val h = src.height
            val out = ByteArray(w * h)
            // One row of carried error, plus the next row's. Ints, so nothing rounds
            // twice; the image itself is never written back into.
            var err = IntArray(w + 2)
            var next = IntArray(w + 2)
            for (y in 0 until h) {
                // Serpentine: even rows left to right, odd rows right to left. Without it
                // the error only ever travels one way and a smooth gradient grows
                // diagonal worms; with it the same gradient measured 0.24 against 0.27
                // (blurred tone error) and looks visibly more even. Costs nothing.
                val leftToRight = (y % 2 == 0)
                for (i in 0 until w) {
                    val x = if (leftToRight) i else w - 1 - i
                    val want = (src.pixels[y * w + x].toInt() and 0xff) + err[x + 1]
                    var best = 0
                    var bestDist = Int.MAX_VALUE
                    for (l in levelValues.indices) {
                        val d = kotlin.math.abs(levelValues[l] - want)
                        if (d < bestDist) {
                            bestDist = d
                            best = l
                        }
                    }
                    out[y * w + x] = best.toByte()
                    val e = want - levelValues[best]
                    // floorDiv, not `/`. The error is negative half the time, and
                    // Kotlin's `/` truncates toward zero while Python's `//` floors:
                    // `-9 / 16` is 0 here and -1 in `walletgen.py`. One pixel of
                    // disagreement is a different asset id, so the two implementations
                    // have to round the same way, and floor is the one the generator
                    // already does.
                    //
                    // The neighbour offsets mirror with the scan direction: `ahead` is
                    // the next pixel this row will visit, not "to the right".
                    val ahead = if (leftToRight) 1 else -1
                    err[x + 1 + ahead] += Math.floorDiv(e * 7, 16)
                    next[x + 1 - ahead] += Math.floorDiv(e * 3, 16)
                    next[x + 1] += Math.floorDiv(e * 5, 16)
                    next[x + 1 + ahead] += Math.floorDiv(e * 1, 16)
                }
                val swap = err
                err = next
                next = swap
                java.util.Arrays.fill(next, 0)
            }
            return GreyLevels(w, h, out)
        }

        /** Nearest level, position independent. See [Grey.THRESHOLDS]. */
        fun quantise(src: GrayImage): GreyLevels {
            val lut = IntArray(256) { Grey.levelOf(it) }
            val out = ByteArray(src.pixels.size)
            for (i in src.pixels.indices) {
                out[i] = lut[src.pixels[i].toInt() and 0xff].toByte()
            }
            return GreyLevels(src.width, src.height, out)
        }
    }

    /**
     * The one rotation rule, same as [MonoImage.packNativeRegion]'s:
     * `native(u, v) = logical(LW - 1 - v, u)`, size (LW, LH) -> (LH, LW).
     */
    fun rotateNative(): GreyLevels {
        val nativeW = height          // == logical height
        val nativeH = width           // == logical width
        val out = ByteArray(nativeW * nativeH)
        for (v in 0 until nativeH) {
            val srcCol = width - 1 - v
            val dst = v * nativeW
            for (u in 0 until nativeW) {
                out[dst + u] = levels[u * width + srcCol]
            }
        }
        return GreyLevels(nativeW, nativeH, out)
    }

    fun crop(x0: Int, y0: Int, w: Int, h: Int): GreyLevels {
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            System.arraycopy(levels, (y0 + y) * width + x0, out, y * w, w)
        }
        return GreyLevels(w, h, out)
    }

    /**
     * 2bpp, 4 pixels per byte, MSB-first: pixel x lands in bits `6 - 2 * (x and 3)`.
     * Matches the firmware's own 2bpp reader
     * (`lib/Epub/Epub/blocks/ImageBlock.cpp:181-186`).
     *
     * Row stride is `width / 4` and the width must be a multiple of 4 so a row ends
     * on a byte. A page image keeps the 1bpp rule -- a multiple of **8** -- so the
     * 1bpp and 2bpp page images of one level share exactly one geometry.
     */
    fun pack2bpp(): ByteArray {
        require(width % 4 == 0) { "2bpp rows must end on a byte: width $width" }
        val stride = width / 4
        val out = ByteArray(stride * height)
        for (y in 0 until height) {
            val src = y * width
            val dst = y * stride
            var x = 0
            while (x < width) {
                out[dst + (x shr 2)] = (
                    ((levels[src + x].toInt() and 3) shl 6) or
                        ((levels[src + x + 1].toInt() and 3) shl 4) or
                        ((levels[src + x + 2].toInt() and 3) shl 2) or
                        (levels[src + x + 3].toInt() and 3)
                    ).toByte()
                x += 4
            }
        }
        return out
    }

    /** One plane, 1bpp MSB-first, framebuffer layout. White in the mask is a 1 bit. */
    fun planeMask(plane: String): ByteArray {
        val wants = Grey.planeSets(plane)
        val px = ByteArray(width * height)
        for (i in levels.indices) {
            px[i] = if ((levels[i].toInt() and 0xff) in wants) 255.toByte() else 0
        }
        return MonoImage(width, height, px).pack1bpp()
    }

    /**
     * `base || lsb || msb` for exactly one screen -- the payload of `assetType 7`.
     *
     * One asset is **one screen, not a page**, because
     * `writeGrayscalePlaneStrip()` writes `numRows * panelWidthBytes` bytes at the
     * **panel's** row stride (`Ssd1677Driver.cpp:494-502`). A page-wide plane could
     * not be handed to it without repacking, which is the cost this type exists to
     * avoid. Bands are contiguous and in y order inside a plane, so band `b` is the
     * byte slice at `b * 80 * rowBytes` and needs no repacking.
     *
     * Order is the order the device consumes them: base frame first (its refresh is
     * a HALF), then LSB, then MSB (`GrayscaleFrame.cpp:95-112`).
     */
    fun packPlanes(panel: PanelProfile): ByteArray {
        require(width == panel.width && height == panel.height) {
            "grey planes want one native screen ${panel.width}x${panel.height} for " +
                "panel ${panel.name}, got ${width}x$height"
        }
        val out = ByteArray(Grey.PLANE_COUNT * panel.assetBytes)
        for ((i, plane) in Grey.PLANE_NAMES.withIndex()) {
            val bytes = planeMask(plane)
            if (bytes.size != panel.assetBytes) {
                throw AssertionError("plane $plane is ${bytes.size} B, want ${panel.assetBytes}")
            }
            bytes.copyInto(out, i * panel.assetBytes)
        }
        return out
    }
}
