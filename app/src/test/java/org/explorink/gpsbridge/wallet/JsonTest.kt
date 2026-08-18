package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Json] against CPython's `json.dumps(obj, indent=2)`.
 *
 * The writer's output is compared to the generator's `manifest.json` as text by
 * the parity test, so its formatting is part of the format: two-space indent,
 * `": "` after a key, `{}` / `[]` for empty containers, insertion order, and
 * `ensure_ascii` escaping of everything above 0x7e.
 */
class JsonTest {

    @Test
    fun object_layout_matches_python() {
        val v = linkedMapOf<String, Any?>("a" to 1, "b" to "two", "c" to listOf(1, 2))
        assertEquals(
            """
            {
              "a": 1,
              "b": "two",
              "c": [
                1,
                2
              ]
            }
            """.trimIndent(),
            Json.write(v))
    }

    @Test
    fun empty_containers_are_inline() {
        assertEquals("{\n  \"codes\": [],\n  \"levels\": {}\n}",
            Json.write(linkedMapOf<String, Any?>("codes" to emptyList<Any?>(),
                "levels" to emptyMap<String, Any?>())))
    }

    @Test
    fun non_ascii_is_escaped_like_python() {
        // json.dumps("Pas\u017e") == "\"Pas\\u017e\""
        assertEquals("\"Pas\\u017e\"", Json.write("Pas\u017e"))
        assertEquals("\"a\\nb\\tc\\\\d\\\"e\"", Json.write("a\nb\tc\\d\"e"))
        // Control characters with no short form.
        assertEquals("\"\\u0001\"", Json.write("\u0001"))
    }

    @Test
    fun booleans_and_null() {
        assertEquals("{\n  \"v\": true,\n  \"w\": false,\n  \"n\": null\n}",
            Json.write(linkedMapOf<String, Any?>("v" to true, "w" to false, "n" to null)))
    }

    @Test
    fun parses_what_it_writes() {
        val src = linkedMapOf<String, Any?>(
            "n" to 42,
            "s" to "with \"quotes\" and \u00e9",
            "list" to listOf(linkedMapOf<String, Any?>("x" to 1), emptyList<Any?>()),
            "flag" to false,
            "nothing" to null,
        )
        val text = Json.write(src)
        val back = Json.asMap(Json.parse(text))
        assertEquals(42, Json.asInt(back["n"]))
        assertEquals("with \"quotes\" and \u00e9", back["s"])
        assertEquals(false, back["flag"])
        assertEquals(null, back["nothing"])
        // and re-writing the parsed tree gives the same text
        assertEquals(text, Json.write(back))
    }

    @Test
    fun parses_the_escapes_python_writes() {
        val v = Json.asMap(Json.parse("{\"t\": \"a\\u017eb\\/c\\r\\n\"}"))
        assertEquals("a\u017eb/c\r\n", v["t"])
    }

    @Test
    fun rejects_trailing_garbage() {
        var threw = false
        try {
            Json.parse("{\"a\": 1} nonsense")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a damaged manifest must fail loudly", threw)
    }
}
