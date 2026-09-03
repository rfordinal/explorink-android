package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON this app writes and reads, checked on the laptop.
 *
 * That is the whole reason this class exists rather than `org.json`: measured
 * 2026-09-02, a unit test of `JSONObject("""{"a": 1}""").getInt("a")` answers 0,
 * because `unitTests.isReturnDefaultValues = true` turns the framework's stub
 * into something that never parses and never complains. Every case below would
 * pass vacuously there.
 */
class JsonTest {

    private fun refuses(text: String) {
        var threw = false
        try {
            Json.parse(text)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("expected a refusal for: $text", threw)
    }

    @Test
    fun `an object keeps the order it was written in`() {
        val text = Json.write(linkedMapOf<String, Any?>("b" to 1, "a" to 2))
        assertEquals("{\n  \"b\": 1,\n  \"a\": 2\n}", text)
        assertEquals(listOf("b", "a"), Json.asMap(Json.parse(text)).keys.toList())
    }

    @Test
    fun `empty containers are written short`() {
        assertEquals(
            "{\n  \"a\": {},\n  \"b\": []\n}",
            Json.write(linkedMapOf<String, Any?>("a" to emptyMap<String, Any?>(), "b" to emptyList<Any?>())),
        )
    }

    @Test
    fun `a string with quotes, newlines and an accent round-trips`() {
        val s = "Žilina \"20 km\"\nsever\ttab\\slash"
        val back = Json.asMap(Json.parse(Json.write(mapOf("k" to s))))["k"]
        assertEquals(s, back)
    }

    @Test
    fun `everything above ascii is escaped, so the file is ascii`() {
        val text = Json.write(mapOf("k" to "Ž"))
        assertTrue(text.contains("\\u017d"))
        assertTrue(text.all { it.code <= 0x7e })
    }

    @Test
    fun `whole numbers read back as Long and fractions as Double`() {
        val o = Json.asMap(Json.parse("""{"i": 966878, "d": 41.376, "n": null, "b": true}"""))
        assertEquals(966_878L, o["i"])
        assertEquals(41.376, Json.asDouble(o["d"]), 1e-9)
        assertNull(o["n"])
        assertEquals(true, o["b"])
    }

    @Test
    fun `a u32 content id survives as a Long, not a truncated Int`() {
        // contentId and crc32 are unsigned 32-bit values held in a Long. Read
        // back as an Int they would go negative and stop matching the CDN.
        val o = Json.asMap(Json.parse("""{"contentId": 3403328319}"""))
        assertEquals(3_403_328_319L, Json.asLong(o["contentId"]))
    }

    @Test
    fun `truncated and trailing input is refused rather than half-read`() {
        refuses("""{"a": 1""")
        refuses("""{"a": 1} {"b": 2}""")
        refuses("""["a", ]""")
        refuses("""{"a": "unterminated""")
        refuses("")
        refuses("not json at all")
        refuses("""{"a": "\q"}""")
        refuses("""{"a": "\u12"}""")
    }

    @Test
    fun `the optional readers tolerate a missing or wrongly typed field`() {
        val o = Json.asMap(Json.parse("""{"n": "text", "s": 4}"""))
        assertEquals(7L, Json.optLong(o, "missing", 7L))
        assertEquals(7L, Json.optLong(o, "n", 7L))
        assertNull(Json.optString(o, "s"))
        assertEquals(false, Json.optBool(o, "missing"))
    }
}
