package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Detection from photograph-like input.
 *
 * A clean-image pass proves nothing: the real question is whether a code survives
 * a phone camera in a hotel lobby -- a smaller image than the screen it was drawn
 * for, out of focus, a few degrees off square, lit from one side, and noisy. So
 * every code here is drawn at a plausible camera scale and then degraded on
 * purpose, one effect at a time and then several at once.
 *
 * These are **synthesised** degradations, not photographs, and they carry no JPEG
 * artefacts -- `javax.imageio` is not on the Android unit-test classpath, so
 * nothing here can encode a JPEG. The JPEG half of the ladder runs through the
 * real app on the emulator (`docs/android-wallet.md` section 13).
 *
 * The floors below are measured, not guessed. They are floors so that a better
 * library cannot fail the build, only a worse one.
 */
class CodeDetectTest {

    private val panel = Panels.X4

    private val subjects = listOf(
        Symbology.QR to CodeFixtures.BCBP136,
        Symbology.PDF417 to CodeFixtures.BCBP136,
        Symbology.AZTEC to CodeFixtures.BCBP136,
        Symbology.DATAMATRIX to CodeFixtures.BCBP136,
        Symbology.CODE128 to CodeFixtures.SHORT,
        Symbology.EAN13 to CodeFixtures.EAN,
    )

    /**
     * Degradations **every** symbology survives. Measured, not wished for: a
     * strong brightness gradient is NOT in this list, because the 1D codes lose it
     * (see the ladder table), and neither is a 3x downscale.
     */
    private val required = listOf("clean", "scale 50%", "blur 1px", "blur 2px",
        "rotate 2deg", "rotate 5deg", "noise 10", "noise 25")

    /**
     * How many of the whole ladder each symbology survives, at least. Measured on
     * this machine with ZXing 3.5.3; a floor, so an improvement cannot fail.
     */
    private val floors = mapOf(
        Symbology.QR to 11,          // loses a 3x downscale and the two combined cases
        Symbology.PDF417 to 12,      // loses 10 degrees of tilt and the harsh case
        Symbology.AZTEC to 10,       // the most fragile: 3x downscale, 3 px blur, both combined
        Symbology.DATAMATRIX to 13,  // the most robust: only the harsh case
        Symbology.CODE128 to 12,     // loses a strong brightness gradient
        Symbology.EAN13 to 12,       // same
    )

    // --- the ladder ----------------------------------------------------------

    private fun ladder(): List<Pair<String, (GrayImage) -> GrayImage>> = listOf(
        "clean" to { g -> g },
        "scale 50%" to { g -> scale(g, 0.5) },
        "scale 33%" to { g -> scale(g, 1.0 / 3.0) },
        "blur 1px" to { g -> blur(g, 1.0) },
        "blur 2px" to { g -> blur(g, 2.0) },
        "blur 3px" to { g -> blur(g, 3.0) },
        "rotate 2deg" to { g -> rotate(g, 2.0) },
        "rotate 5deg" to { g -> rotate(g, 5.0) },
        "rotate 10deg" to { g -> rotate(g, 10.0) },
        "gradient 45%" to { g -> gradient(g, 0.45) },
        "noise 10" to { g -> noise(g, 10.0, 7) },
        "noise 25" to { g -> noise(g, 25.0, 11) },
        // A plausible photo: held by hand, slightly out of focus, lit from a window.
        "lobby" to { g -> noise(gradient(rotate(blur(scale(g, 0.6), 1.5), 3.0), 0.6), 12.0, 3) },
        // And a bad one: further away, badly out of focus, well off square.
        "harsh" to { g -> noise(gradient(rotate(blur(scale(g, 0.4), 2.5), 7.0), 0.35), 20.0, 5) },
    )

    /**
     * Photo pixels per module. A phone photo of a boarding pass held at arm's
     * length lands around here: an A6 pass across 3000 px is about 8 px for a
     * 49-module QR. Every number below is against this scale, so they can be read.
     */
    private val PHOTO_MODULE_PX = 8

    @Test
    fun every_symbology_survives_the_mild_degradations() {
        val results = run()
        for ((sym, _) in subjects) {
            for (name in required) {
                assertTrue("${sym.key} was not found after '$name'",
                    results.getValue(sym).getValue(name) != null)
            }
        }
    }

    @Test
    fun the_whole_ladder_is_at_least_as_survivable_as_measured() {
        val results = run()
        val report = StringBuilder(
            "\ndetection ladder at $PHOTO_MODULE_PX px per module " +
            "(found = payload came back exact)\n")
        for ((sym, _) in subjects) {
            val row = results.getValue(sym)
            val hits = row.count { it.value != null }
            report.append("  %-11s %2d/%2d  ".format(sym.key, hits, row.size))
            report.append(row.entries.filter { it.value == null }
                .joinToString(", ") { "no ${it.key}" })
            report.append("   stages: ")
            report.append(row.entries.filter { it.value != null }
                .map { it.value }.toSet().joinToString(" "))
            report.append('\n')
        }
        println(report)
        for ((sym, _) in subjects) {
            val hits = results.getValue(sym).count { it.value != null }
            assertTrue("${sym.key} survived $hits of ${ladder().size}, " +
                "floor is ${floors.getValue(sym)}\n$report", hits >= floors.getValue(sym))
        }
    }

    /**
     * The number that decides whether a phone camera is enough: **how few pixels
     * per module detection still reads.** Swept per symbology, clean and blurred.
     * Assertions are ceilings -- a better library may need fewer, never more.
     */
    @Test
    fun the_smallest_module_in_pixels_that_still_decodes() {
        val ceilings = mapOf(
            Symbology.QR to Pair(1, 3),
            Symbology.PDF417 to Pair(1, 2),
            Symbology.AZTEC to Pair(2, 5),
            Symbology.DATAMATRIX to Pair(2, 3),
            Symbology.CODE128 to Pair(1, 2),
            Symbology.EAN13 to Pair(1, 2),
        )
        val report = StringBuilder("\nsmallest px per module that still decodes\n")
        for ((sym, payload) in subjects) {
            val clean = smallestModulePx(sym, payload) { it }
            val blurred = smallestModulePx(sym, payload) { blur(it, 1.0) }
            report.append("  %-11s clean %s px, blurred 1 px %s px%n".format(
                sym.key, clean ?: "none<=12", blurred ?: "none<=12"))
            val (wantClean, wantBlur) = ceilings.getValue(sym)
            assertTrue("${sym.key} clean needs $clean px, measured ceiling $wantClean\n$report",
                clean != null && clean <= wantClean)
            assertTrue("${sym.key} blurred needs $blurred px, ceiling $wantBlur\n$report",
                blurred != null && blurred <= wantBlur)
        }
        println(report)
    }

    private fun smallestModulePx(sym: Symbology, payload: String,
                                 degrade: (GrayImage) -> GrayImage): Int? {
        for (px in 1..12) {
            val photo = degrade(codePhoto(sym, payload, px, 0.25))
            if (CodeReader.detect(photo).any { it.symbology == sym && it.payload == payload }) {
                return px
            }
        }
        return null
    }

    @Test
    fun an_image_with_no_code_yields_nothing() {
        // Two false paths that must stay false: blank paper, and a page of text-like
        // noise. Detection that invents a code is worse than detection that misses one.
        assertEquals(emptyList<CodeReader.Found>(),
            CodeReader.detect(GrayImage.filled(900, 1200, 255)))
        val busy = GrayImage.filled(900, 1200, 255)
        val rnd = Random(42)
        for (row in 0 until 40) {                     // 40 lines of "text"
            val y = 40 + row * 28
            var x = 40
            while (x < 860) {
                val w = 8 + rnd.nextInt(40)
                for (dy in 0 until 10) {
                    for (dx in 0 until minOf(w, 860 - x)) {
                        busy.pixels[(y + dy) * busy.width + x + dx] = 40
                    }
                }
                x += w + 6 + rnd.nextInt(12)
            }
        }
        assertEquals("text-like ink must not decode as a code",
            emptyList<CodeReader.Found>(), CodeReader.detect(busy))
    }

    @Test
    fun a_photo_with_two_codes_yields_both() {
        val a = codePhoto(Symbology.QR, "FIRST-PAYLOAD", 6, 0.1)
        val b = codePhoto(Symbology.AZTEC, "SECOND-PAYLOAD", 6, 0.1)
        // Side by side, on one white sheet, the way a boarding pass carries two.
        val w = a.width + b.width + 60
        val h = maxOf(a.height, b.height) + 60
        val sheet = GrayImage.filled(w, h, 255)
        sheet.paste(a, 20, 20)
        sheet.paste(b, a.width + 40, 20)
        val found = CodeReader.detect(sheet)
        val payloads = found.map { it.payload }.toSet()
        assertTrue("expected both codes, got $payloads", payloads.containsAll(
            listOf("FIRST-PAYLOAD", "SECOND-PAYLOAD")))
    }

    @Test
    fun a_sideways_barcode_is_found_by_turning_the_image() {
        // ZXing's 1D readers scan rows, so this is the case the rotation stage of the
        // ladder exists for. Without it a Code128 photographed portrait is invisible.
        val photo = codePhoto(Symbology.CODE128, CodeFixtures.SHORT, 6, 0.2)
        val sideways = rotate90(photo)
        val found = CodeReader.detect(sideways)
        assertEquals(1, found.size)
        assertEquals(CodeFixtures.SHORT, found[0].payload)
        // It is found at the FIRST stage, not the turned one: ZXing's 1D reader turns
        // the bitmap itself when TRY_HARDER is set and the source supports rotation.
        // Which is why `GrayLuminanceSource` implements rotation -- without it a
        // sideways barcode would need our own turned stage, or be missed.
        assertTrue("expected an upright stage, got ${found[0].stage}",
            found[0].stage.contains("0deg"))
    }

    @Test
    fun detection_never_crops_the_photo_into_the_asset() {
        // The photo only supplies a payload. Proof: the asset rendered from a
        // detected payload is byte-identical to one rendered from the payload typed
        // in by hand, whatever the photo looked like.
        val photo = noise(blur(codePhoto(Symbology.QR, CodeFixtures.BCBP136, 5, 0.3), 1.5), 8.0, 1)
        val found = CodeReader.detect(photo)
        assertEquals(1, found.size)
        val fromPhoto = CodeWriter.render(found[0].symbology, found[0].payload, panel)
        val fromHand = CodeWriter.render(Symbology.QR, CodeFixtures.BCBP136, panel)
        assertTrue(CodeWriter.pack(fromPhoto.canvas, panel, fromPhoto.layout.orientation)
            .contentEquals(CodeWriter.pack(fromHand.canvas, panel, fromHand.layout.orientation)))
    }

    // --- running the ladder --------------------------------------------------

    private fun run(): Map<Symbology, Map<String, String?>> {
        val out = LinkedHashMap<Symbology, Map<String, String?>>()
        for ((sym, payload) in subjects) {
            val photo = codePhoto(sym, payload, PHOTO_MODULE_PX, 0.25)
            val row = LinkedHashMap<String, String?>()
            for ((name, degrade) in ladder()) {
                val found = CodeReader.detect(degrade(photo))
                row[name] = found.firstOrNull { it.symbology == sym && it.payload == payload }?.stage
            }
            out[sym] = row
        }
        return out
    }

    /** A code on white paper, at [modulePx] pixels per module, with a paper margin. */
    private fun codePhoto(sym: Symbology, payload: String, modulePx: Int,
                          margin: Double): GrayImage {
        val m = CodeWriter.matrix(sym, payload, panel)
        val qz = sym.quietZone
        val codeW = (m.width + 2 * qz) * modulePx
        val codeH = (m.height + 2 * qz) * modulePx
        val w = codeW + (codeW * margin).toInt() * 2
        val h = codeH + (codeH * margin).toInt() * 2
        val img = GrayImage.filled(w, h, 255)
        val x0 = (w - codeW) / 2 + qz * modulePx
        val y0 = (h - codeH) / 2 + qz * modulePx
        for (my in 0 until m.height) {
            for (mx in 0 until m.width) {
                if (!m[mx, my]) continue
                for (dy in 0 until modulePx) {
                    val base = (y0 + my * modulePx + dy) * w + x0 + mx * modulePx
                    java.util.Arrays.fill(img.pixels, base, base + modulePx, 0)
                }
            }
        }
        return img
    }

    // --- degradations --------------------------------------------------------

    private fun scale(src: GrayImage, factor: Double): GrayImage {
        val w = maxOf(8, (src.width * factor).toInt())
        val h = maxOf(8, (src.height * factor).toInt())
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val sy = (y + 0.5) / factor - 0.5
            for (x in 0 until w) {
                out[y * w + x] = sample(src, (x + 0.5) / factor - 0.5, sy).toByte()
            }
        }
        return GrayImage(w, h, out)
    }

    /** Separable Gaussian, radius = 3 sigma. Out of focus, in other words. */
    private fun blur(src: GrayImage, sigma: Double): GrayImage {
        val r = maxOf(1, Math.ceil(sigma * 3).toInt())
        val k = DoubleArray(2 * r + 1)
        var sum = 0.0
        for (i in -r..r) {
            k[i + r] = Math.exp(-(i * i) / (2 * sigma * sigma))
            sum += k[i + r]
        }
        for (i in k.indices) k[i] /= sum
        val mid = ByteArray(src.width * src.height)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                var acc = 0.0
                for (i in -r..r) {
                    val sx = Math.min(src.width - 1, Math.max(0, x + i))
                    acc += k[i + r] * (src.pixels[y * src.width + sx].toInt() and 0xff)
                }
                mid[y * src.width + x] = Math.round(acc).toInt().coerceIn(0, 255).toByte()
            }
        }
        val out = ByteArray(src.width * src.height)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                var acc = 0.0
                for (i in -r..r) {
                    val sy = Math.min(src.height - 1, Math.max(0, y + i))
                    acc += k[i + r] * (mid[sy * src.width + x].toInt() and 0xff)
                }
                out[y * src.width + x] = Math.round(acc).toInt().coerceIn(0, 255).toByte()
            }
        }
        return GrayImage(src.width, src.height, out)
    }

    /** Rotate about the centre, bilinear, white outside. A hand-held photo. */
    private fun rotate(src: GrayImage, degrees: Double): GrayImage {
        val rad = Math.toRadians(degrees)
        val cos = Math.cos(rad)
        val sin = Math.sin(rad)
        val w = Math.ceil(Math.abs(src.width * cos) + Math.abs(src.height * sin)).toInt()
        val h = Math.ceil(Math.abs(src.width * sin) + Math.abs(src.height * cos)).toInt()
        val out = ByteArray(w * h)
        java.util.Arrays.fill(out, 255.toByte())
        val cx = w / 2.0
        val cy = h / 2.0
        val sx0 = src.width / 2.0
        val sy0 = src.height / 2.0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x - cx
                val dy = y - cy
                val sx = sx0 + dx * cos + dy * sin
                val sy = sy0 - dx * sin + dy * cos
                if (sx < 0 || sy < 0 || sx > src.width - 1 || sy > src.height - 1) continue
                out[y * w + x] = sample(src, sx, sy).toByte()
            }
        }
        return GrayImage(w, h, out)
    }

    private fun rotate90(src: GrayImage): GrayImage {
        val out = ByteArray(src.width * src.height)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                out[x * src.height + (src.height - 1 - y)] = src.pixels[y * src.width + x]
            }
        }
        return GrayImage(src.height, src.width, out)
    }

    /** Lit from one side: brightness falls off linearly to [minFactor] across x. */
    private fun gradient(src: GrayImage, minFactor: Double): GrayImage {
        val out = ByteArray(src.width * src.height)
        for (x in 0 until src.width) {
            val f = minFactor + (1.0 - minFactor) * (x / (src.width - 1.0))
            for (y in 0 until src.height) {
                val v = (src.pixels[y * src.width + x].toInt() and 0xff) * f
                out[y * src.width + x] = Math.round(v).toInt().coerceIn(0, 255).toByte()
            }
        }
        return GrayImage(src.width, src.height, out)
    }

    /** Gaussian sensor noise, deterministic per seed. */
    private fun noise(src: GrayImage, sigma: Double, seed: Long): GrayImage {
        val rnd = Random(seed)
        val out = ByteArray(src.pixels.size)
        for (i in src.pixels.indices) {
            val v = (src.pixels[i].toInt() and 0xff) + rnd.nextGaussian() * sigma
            out[i] = Math.round(v).toInt().coerceIn(0, 255).toByte()
        }
        return GrayImage(src.width, src.height, out)
    }

    private fun sample(src: GrayImage, x: Double, y: Double): Int {
        val x0 = Math.floor(x).toInt().coerceIn(0, src.width - 1)
        val y0 = Math.floor(y).toInt().coerceIn(0, src.height - 1)
        val x1 = (x0 + 1).coerceAtMost(src.width - 1)
        val y1 = (y0 + 1).coerceAtMost(src.height - 1)
        val fx = x - x0
        val fy = y - y0
        val p00 = src[x0, y0]
        val p10 = src[x1, y0]
        val p01 = src[x0, y1]
        val p11 = src[x1, y1]
        val top = p00 + (p10 - p00) * fx
        val bottom = p01 + (p11 - p01) * fx
        return Math.round(top + (bottom - top) * fy).toInt().coerceIn(0, 255)
    }
}
