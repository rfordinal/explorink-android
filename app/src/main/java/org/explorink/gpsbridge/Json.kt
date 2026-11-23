package org.explorink.gpsbridge

/**
 * A JSON writer and parser, hand-rolled.
 *
 * **Not `org.json`, even though it ships with Android.** It exists in
 * `android.jar` only as a stub for unit tests, and this app runs its tests on
 * the laptop with `unitTests.isReturnDefaultValues = true`
 * (`app/build.gradle.kts`, and every log line in [TileFetcher] depends on that
 * flag). A stubbed `JSONObject` returns defaults instead of throwing, so a test
 * of a parser built on it passes while parsing nothing.
 *
 * Measured here, 2026-09-02, before writing a line of [MapsetSource]: a probe
 * test asserting `JSONObject("""{"a": 1}""").getInt("a") == 1` fails with an
 * `AssertionError`, not an exception -- the stub answered 0. Anything parsed
 * with `org.json` is therefore **unverifiable off-device**, which is exactly
 * what the outbox format and the mapset parse must not be.
 *
 * Not a library either: the app has one dependency (`androidx.core:core-ktx`)
 * and this is not worth a second.
 *
 * **A deliberate copy of `wallet/Json.kt` on `feat/wallet`**, which found the
 * same trap first and solved it the same way. Different package, so the two
 * coexist; when that branch merges, one of them is deleted and the other's
 * imports move. Kept identical rather than trimmed so that deletion stays a
 * one-line decision.
 *
 * The writer reproduces CPython's `json.dump(obj, indent=2, sort_keys=False)`
 * byte for byte, including `ensure_ascii` escaping, `": "` after keys, `{}` and
 * `[]` for empty containers, and insertion order. Nothing on this branch needs
 * that parity -- the wallet's manifest does -- but a writer whose output a
 * Python one-liner reproduces is a writer whose output can be diffed by hand
 * off an `adb pull`.
 *
 * Values are plain Kotlin types: `Map<String, Any?>` (insertion ordered),
 * `List<Any?>`, `String`, `Int`/`Long`, `Double`, `Boolean`, `null`.
 */
object Json {

    private const val FORM_FEED = ''

    // --- writing -----------------------------------------------------------

    /** CPython `json.dumps(value, indent=2)`. No trailing newline. */
    fun write(value: Any?): String {
        val sb = StringBuilder(4096)
        writeValue(sb, value, 0)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, value: Any?, depth: Int) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long -> sb.append(value.toString())
            is Double -> sb.append(writeDouble(value))
            is String -> writeString(sb, value)
            is Map<*, *> -> writeObject(sb, value, depth)
            is List<*> -> writeArray(sb, value, depth)
            else -> throw IllegalArgumentException("cannot serialise ${value.javaClass.name}")
        }
    }

    private fun writeObject(sb: StringBuilder, map: Map<*, *>, depth: Int) {
        if (map.isEmpty()) {
            sb.append("{}")
            return
        }
        sb.append("{\n")
        val pad = "  ".repeat(depth + 1)
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(",\n")
            first = false
            sb.append(pad)
            writeString(sb, k as String)
            sb.append(": ")
            writeValue(sb, v, depth + 1)
        }
        sb.append("\n").append("  ".repeat(depth)).append("}")
    }

    private fun writeArray(sb: StringBuilder, list: List<*>, depth: Int) {
        if (list.isEmpty()) {
            sb.append("[]")
            return
        }
        sb.append("[\n")
        val pad = "  ".repeat(depth + 1)
        var first = true
        for (v in list) {
            if (!first) sb.append(",\n")
            first = false
            sb.append(pad)
            writeValue(sb, v, depth + 1)
        }
        sb.append("\n").append("  ".repeat(depth)).append("]")
    }

    /**
     * CPython's `py_encode_basestring_ascii`: the six short escapes, `\u00xx`
     * for every other control character, and `\uXXXX` for everything above
     * 0x7e -- per UTF-16 code unit, so a non-BMP character comes out as its
     * surrogate pair, which is what Python emits too.
     */
    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == FORM_FEED -> sb.append("\\f")
                c.code < 0x20 || c.code > 0x7e -> {
                    sb.append("\\u")
                    val v = c.code
                    for (shift in intArrayOf(12, 8, 4, 0)) {
                        sb.append("0123456789abcdef"[(v ushr shift) and 0xf])
                    }
                }
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private fun writeDouble(v: Double): String {
        if (v.isNaN() || v.isInfinite()) throw IllegalArgumentException("cannot serialise $v")
        return v.toString()
    }

    // --- parsing -----------------------------------------------------------

    /** Throws [IllegalArgumentException] on anything that is not whole JSON. */
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.atEnd()) throw IllegalArgumentException("trailing data at ${p.pos}")
        return v
    }

    private class Parser(val src: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= src.length

        fun skipWs() {
            while (pos < src.length && (src[pos] == ' ' || src[pos] == '\n' ||
                    src[pos] == '\r' || src[pos] == '\t')) pos++
        }

        fun value(): Any? {
            if (atEnd()) throw IllegalArgumentException("unexpected end of JSON")
            return when (src[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        fun obj(): LinkedHashMap<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') {
                pos++
                return out
            }
            while (true) {
                skipWs()
                val k = str()
                skipWs()
                expect(':')
                skipWs()
                out[k] = value()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return out
                    }
                    else -> throw IllegalArgumentException("expected , or } at $pos")
                }
            }
        }

        fun arr(): ArrayList<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') {
                pos++
                return out
            }
            while (true) {
                skipWs()
                out.add(value())
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return out
                    }
                    else -> throw IllegalArgumentException("expected , or ] at $pos")
                }
            }
        }

        fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("unterminated string")
                val c = src[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) throw IllegalArgumentException("unterminated escape")
                        val e = src[pos++]
                        when (e) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append(FORM_FEED)
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) {
                                    throw IllegalArgumentException("truncated \\u at $pos")
                                }
                                val hex = src.substring(pos, pos + 4)
                                pos += 4
                                val code = hex.toIntOrNull(16)
                                    ?: throw IllegalArgumentException("bad \\u escape at $pos")
                                sb.append(code.toChar())
                            }
                            else -> throw IllegalArgumentException("bad escape at $pos")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Any {
            val start = pos
            if (peek() == '-' || peek() == '+') pos++
            var isInt = true
            while (!atEnd()) {
                val c = src[pos]
                if (c in '0'..'9') {
                    pos++
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isInt = false
                    pos++
                } else {
                    break
                }
            }
            val text = src.substring(start, pos)
            if (text.isEmpty()) throw IllegalArgumentException("expected a value at $start")
            return if (isInt) {
                text.toLongOrNull() ?: text.toDoubleOrNull()
                    ?: throw IllegalArgumentException("bad number at $start")
            } else {
                text.toDoubleOrNull() ?: throw IllegalArgumentException("bad number at $start")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!src.startsWith(word, pos)) throw IllegalArgumentException("bad literal at $pos")
            pos += word.length
            return v
        }

        fun peek(): Char = if (atEnd()) ' ' else src[pos]

        fun expect(c: Char) {
            if (peek() != c) throw IllegalArgumentException("expected '$c' at $pos")
            pos++
        }
    }

    // --- reading helpers (a parsed tree is Map/List/Long/Double/String) ------

    @Suppress("UNCHECKED_CAST")
    fun asMap(v: Any?): Map<String, Any?> =
        v as? Map<String, Any?> ?: throw IllegalArgumentException("not an object: $v")

    @Suppress("UNCHECKED_CAST")
    fun asList(v: Any?): List<Any?> =
        v as? List<Any?> ?: throw IllegalArgumentException("not an array: $v")

    fun asInt(v: Any?): Int = when (v) {
        is Long -> v.toInt()
        is Int -> v
        is Double -> v.toInt()
        else -> throw IllegalArgumentException("not a number: $v")
    }

    fun asLong(v: Any?): Long = when (v) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> v.toLong()
        else -> throw IllegalArgumentException("not a number: $v")
    }

    fun asDouble(v: Any?): Double = when (v) {
        is Double -> v
        is Long -> v.toDouble()
        is Int -> v.toDouble()
        else -> throw IllegalArgumentException("not a number: $v")
    }

    fun asString(v: Any?): String =
        v as? String ?: throw IllegalArgumentException("not a string: $v")

    // --- reading helpers that tolerate a missing field ----------------------
    //
    // The outbox reader uses these rather than the strict ones above: a field
    // this build does not know how to default is a field it should not be
    // reading, and a queue that refuses to load because one optional number is
    // absent loses the rider's whole ask.

    fun optLong(o: Map<String, Any?>, key: String, fallback: Long = 0L): Long =
        o[key]?.let { runCatching { asLong(it) }.getOrNull() } ?: fallback

    fun optInt(o: Map<String, Any?>, key: String, fallback: Int = 0): Int =
        o[key]?.let { runCatching { asInt(it) }.getOrNull() } ?: fallback

    fun optString(o: Map<String, Any?>, key: String): String? = o[key] as? String

    fun optBool(o: Map<String, Any?>, key: String, fallback: Boolean = false): Boolean =
        o[key] as? Boolean ?: fallback
}
