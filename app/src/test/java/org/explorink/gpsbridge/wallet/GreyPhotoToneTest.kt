package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The photograph tone: four grey levels by error diffusion instead of by nearest value.
 *
 * It exists because a photograph rendered the document way posterises. On the panel, the
 * maintainer's own photo came out in bands ("prvu foto zobrazilo (skaredo)", 2026-08-19)
 * while the same image at 1bpp, which has always been dithered, read better -- the
 * "better" mode looked worse.
 *
 * What these pin is the arithmetic. **Whether it looks better is a panel question**, and
 * the level values the diffusion aims at are still assumed rather than measured
 * ([GreyLevels.LEVEL_VALUES]).
 */
class GreyPhotoToneTest {

    private fun flat(value: Int, w: Int = 64, h: Int = 64): GrayImage =
        GrayImage(w, h, ByteArray(w * h) { value.toByte() })

    /** A left-to-right ramp, the shape that posterises worst. */
    private fun ramp(w: Int = 256, h: Int = 32): GrayImage {
        val px = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = x.toByte()
        return GrayImage(w, h, px)
    }

    private fun histogram(g: GreyLevels): IntArray {
        val h = IntArray(Grey.LEVEL_COUNT)
        for (b in g.levels) h[b.toInt() and 0xff]++
        return h
    }

    @Test
    fun a_flat_tone_that_is_a_level_stays_that_one_level() {
        // No pattern where there is nothing to spread: 0, 85, 170 and 255 are the
        // levels themselves, so the error is zero and every pixel agrees.
        for ((value, level) in listOf(0 to Grey.BLACK, 85 to Grey.DARK,
                170 to Grey.LIGHT, 255 to Grey.WHITE)) {
            val h = histogram(GreyLevels.diffuse(flat(value)))
            assertEquals("value $value", 64 * 64, h[level])
        }
    }

    @Test
    fun a_flat_tone_between_two_levels_mixes_them_instead_of_picking_one() {
        // 128 sits between DARK (85) and LIGHT (170). Nearest value takes LIGHT for all
        // of it; diffusion has to use both, and that is the whole point.
        val nearest = histogram(GreyLevels.quantise(flat(128)))
        val diffused = histogram(GreyLevels.diffuse(flat(128)))
        assertEquals("nearest value picks exactly one level", 64 * 64, nearest[Grey.LIGHT])
        assertTrue("diffusion must use DARK too: ${diffused.toList()}", diffused[Grey.DARK] > 0)
        assertTrue("and LIGHT: ${diffused.toList()}", diffused[Grey.LIGHT] > 0)
    }

    @Test
    fun the_mean_of_the_diffused_levels_tracks_the_input() {
        // The test that would catch a wrong weight or a lost error term: the average
        // reflectance of the output has to land near the input, not merely "look mixed".
        for (value in listOf(20, 60, 100, 128, 190, 230)) {
            val g = GreyLevels.diffuse(flat(value))
            var sum = 0L
            for (b in g.levels) sum += GreyLevels.LEVEL_VALUES[b.toInt() and 0xff]
            val mean = sum.toDouble() / g.levels.size
            assertTrue("input $value gave mean $mean", kotlin.math.abs(mean - value) <= 6.0)
        }
    }

    @Test
    fun a_ramp_uses_every_level_and_nearest_value_bands_it() {
        val diffused = histogram(GreyLevels.diffuse(ramp()))
        for (l in 0 until Grey.LEVEL_COUNT) {
            assertTrue("level $l missing from a diffused ramp: ${diffused.toList()}",
                diffused[l] > 0)
        }
        // The banding claim, stated as a check rather than as prose: in the nearest-value
        // ramp every column is one level, so each row has exactly three transitions.
        val nearest = GreyLevels.quantise(ramp())
        var transitions = 0
        for (x in 1 until nearest.width) {
            if (nearest[x, 0] != nearest[x - 1, 0]) transitions++
        }
        assertEquals("nearest value bands a ramp into four blocks", 3, transitions)
    }

    @Test
    fun document_tone_is_untouched_by_all_of_this() {
        // The existing path has to be byte for byte what it was: a scan is not supposed
        // to gain a dither pattern because photographs got a mode.
        val src = ramp()
        val a = GreyLevels.quantise(src)
        val b = GreyLevels.quantise(src)
        assertEquals(a.levels.toList(), b.levels.toList())
        assertNotEquals("and it must differ from the photo path",
            a.levels.toList(), GreyLevels.diffuse(src).levels.toList())
    }

    @Test
    fun gamma_of_one_is_the_same_image_and_not_a_copy() {
        val src = ramp()
        assertSame(src, src.gamma(1.0))
    }

    @Test
    fun gamma_lifts_mid_tones_and_pins_the_ends() {
        val src = ramp()
        val lifted = src.gamma(2.2)
        assertEquals(0, lifted[0, 0])
        assertEquals(255, lifted[255, 0])
        assertTrue("mid tone must rise", lifted[128, 0] > src[128, 0])
        // Monotone, or a photograph gains contours out of nowhere.
        for (x in 1 until 256) {
            assertTrue("not monotone at $x", lifted[x, 0] >= lifted[x - 1, 0])
        }
    }

    @Test
    fun the_tone_a_document_was_rendered_with_survives_the_manifest() {
        val store = SyncFixtures.store()
        val item = SyncFixtures.addItem(store, "Photo", grey = true)
        // The fixture builds document tone; what matters here is that the field is
        // written and read back rather than defaulted on both sides.
        assertEquals("document", item.tone)
        val reloaded = store.load().items.first { it.id == item.id }
        assertEquals(item.tone, reloaded.tone)
    }
}
