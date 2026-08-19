package org.explorink.gpsbridge

/**
 * One pin the device holds, as `pin list` reports it.
 *
 * [utc] is **0 when the device had no clock** for that pin -- it has no RTC, and
 * the time arrives in the phone's position packet, so a pin saved before the
 * first packet landed has no time and says so rather than inventing one
 * (`firmware/explorink/docs/pins.md`, "The log on the card"). A pin the app
 * saves always carries a real time: the phone has a clock, which is the one
 * thing it can add here for free.
 *
 * [id] is the device's monotonic pin id, never reused. The key says what type,
 * the id says which pin -- a Camp deleted and remade is two ids, a Camp replaced
 * keeps one.
 */
data class DevicePin(
    val key: String,
    val latE7: Int,
    val lonE7: Int,
    val utc: Long,
    val id: Long,
) {
    /** Catalogue label, or the raw key if this build does not know the type. */
    val label: String get() = PinKinds.labelFor(key)
}

/** One line of the device's append-only pin history, as `pin log` reports it. */
data class PinLogEntry(
    val seq: Long,
    /** `add` | `rep` | `del` | `res`, straight off the record. */
    val op: String,
    val key: String,
    /** Null for a record that carries no position -- every `del`. */
    val latE7: Int?,
    val lonE7: Int?,
    val utc: Long,
) {
    val label: String get() = PinKinds.labelFor(key)
}

/**
 * The `pin` half of the command console: what to send, and how to read what
 * comes back.
 *
 * Wire shapes are the firmware's
 * (`firmware/explorink/src/activities/map/MapCommandConsole.cpp`,
 * `writePinList` / `writePinLog`; `firmware/explorink/docs/pins.md`, "Console
 * commands"):
 *
 *     > pin list
 *     INFO pins_total=1
 *     INFO pin_camp=48.4372000,17.0186000,0,1        lat,lon,utc,id
 *     OK
 *
 *     > pin log
 *     INFO pinlog_total=10
 *     INFO pinlog_offset=0
 *     INFO pinlog_10=rep,camp,48.5000000,17.1000000,0    seq=op,key,lat,lon,utc
 *     INFO pinlog_next=8                                 or `done`
 *     OK
 *
 * Pure parsing and pure formatting, no BLE and no Android: the transport hands
 * lines in, this hands structure out. Same split as [MissingList], and for the
 * same reason -- every one of these shapes is host-testable and none of the
 * tests need a link.
 */
object PinList {

    /** The device console is there but nothing is wired to it -- not "no pins". */
    const val UNAVAILABLE_LINE = "INFO pins=unavailable"

    /** How many records one `pin log` page carries (`MapConsoleState::kPinLogPageSize`). */
    const val LOG_PAGE_SIZE = 8

    // --- commands -------------------------------------------------------

    fun listCommand(): String = "pin list"

    fun logCommand(offset: Int): String = if (offset <= 0) "pin log" else "pin log $offset"

    fun delCommand(key: String): String = "pin del $key"

    /**
     * `pin set <key> <lat> <lon> [<utc>]`.
     *
     * [utcSeconds] is dropped when it is 0, which is the device's own "no clock"
     * value: sending a literal zero and omitting the field mean the same thing to
     * the parser, and omitting it keeps the line honest if a caller ever has no
     * time to state.
     */
    fun setCommand(key: String, latE7: Int, lonE7: Int, utcSeconds: Long = 0L): String {
        val head = "pin set $key ${formatE7(latE7)} ${formatE7(lonE7)}"
        return if (utcSeconds > 0L) "$head $utcSeconds" else head
    }

    // --- numbers --------------------------------------------------------

    /**
     * int32 scaled by 1e7 back to plain decimal degrees, seven digits always --
     * a byte-for-byte port of the device's `formatE7`, so a coordinate makes the
     * round trip through the console unchanged.
     */
    fun formatE7(value: Int): String {
        val negative = value < 0
        val magnitude = if (negative) -value.toLong() else value.toLong()
        return buildString {
            if (negative) append('-')
            append(magnitude / 10_000_000L)
            append('.')
            append((magnitude % 10_000_000L).toString().padStart(7, '0'))
        }
    }

    /**
     * Decimal degrees to 1e7 fixed point, or null if the text is not a plain
     * decimal number.
     *
     * Mirrors the device's `parseDegrees`
     * (`firmware/explorink/src/activities/map/MapCommandParser.cpp:57`): an
     * optional sign, digits, an optional fraction of which only the first seven
     * digits count, and **no trailing anything** -- `1.2.3`, `4e5` and `12x` are
     * all refused there and must be refused here, or the app would send a line
     * the device answers with `ERR bad_number`.
     */
    fun parseDegreesE7(text: String): Int? {
        if (text.isEmpty()) return null
        var i = 0
        var negative = false
        if (text[0] == '+' || text[0] == '-') {
            negative = text[0] == '-'
            i = 1
        }
        var whole = 0L
        var wholeDigits = 0
        while (i < text.length && text[i].isAsciiDigit()) {
            if (wholeDigits >= 10) return null
            whole = whole * 10 + (text[i] - '0')
            wholeDigits++
            i++
        }
        var frac = 0L
        var fracDigits = 0
        if (i < text.length && text[i] == '.') {
            i++
            while (i < text.length && text[i].isAsciiDigit()) {
                if (fracDigits < 7) {
                    frac = frac * 10 + (text[i] - '0')
                    fracDigits++
                }
                i++
            }
        }
        if (i != text.length) return null
        if (wholeDigits == 0 && fracDigits == 0) return null
        var k = fracDigits
        while (k < 7) {
            frac *= 10
            k++
        }
        val value = whole * 10_000_000L + frac
        if (value > Int.MAX_VALUE) return null
        return (if (negative) -value else value).toInt()
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    /** Inside the device's own limits (`kLatMaxE7` / `kLonMaxE7`). */
    fun isValidLatE7(latE7: Int): Boolean = latE7 in -900_000_000..900_000_000

    fun isValidLonE7(lonE7: Int): Boolean = lonE7 in -1_800_000_000..1_800_000_000

    // --- write acknowledgements -----------------------------------------

    /**
     * `INFO pin_set=<key>` or `INFO pin_del=<key>`: the key the device acted on,
     * or null if the line is something else.
     *
     * The ack is not what makes a write successful -- the terminating `OK` is. A
     * refused write answers `ERR pin_write` and **no** `OK`
     * (`MapConsoleState::executePin`), so an `OK` on this channel means the
     * record reached the card. The ack only names the key, which matters when a
     * reply arrives for a pin the app is no longer showing.
     */
    fun parseWriteAck(line: String): String? {
        val t = line.trim()
        if (!t.startsWith("INFO ")) return null
        val body = t.removePrefix("INFO ").trim()
        for (prefix in listOf("pin_set=", "pin_del=")) {
            if (body.startsWith(prefix)) return body.removePrefix(prefix)
        }
        return null
    }

    // --- listings -------------------------------------------------------

    /**
     * Reads the reply to `pin list`.
     *
     * Never paged: the active set is bounded by the catalogue at fourteen entries
     * (`kPinMaxEntries`), which fits one reply comfortably.
     */
    class ListReader {
        private val entries = mutableListOf<DevicePin>()
        val pins: List<DevicePin> get() = entries

        var complete: Boolean = false
            private set

        /**
         * The device answered `pins=unavailable`: its console has no pin store
         * behind it, which in practice means the rider is **not on the map
         * screen** -- the tile sync screen runs its own console and wires no pins
         * to it (`firmware/explorink/src/activities/map/TileSyncActivity.h`).
         *
         * Kept apart from an empty list for the same reason `missing=unavailable`
         * is: "cannot answer" must never read as "the rider has saved nothing".
         */
        var unavailable: Boolean = false
            private set

        var total: Int? = null
            private set

        /**
         * `OK` arrived, but not as many pin lines as `pins_total` promised.
         *
         * One reply line is one BLE indication, and an indication can be lost --
         * measured on this channel 2026-08-13, when a five-line `have` reply
         * arrived as one. A short listing describes a *different* set of pins
         * from the one the device holds, and showing it would invite the rider to
         * replace a pin that is not the one they are looking at.
         */
        val truncated: Boolean
            get() {
                val n = total ?: return false
                return complete && entries.size != n
            }

        /** Feeds one reply line. Returns true if the line belonged to this listing. */
        fun feed(line: String): Boolean {
            val t = line.trim()
            if (t == "OK") {
                complete = true
                return true
            }
            if (!t.startsWith("INFO ")) return false
            val body = t.removePrefix("INFO ").trim()
            if (body == "pins=unavailable") {
                unavailable = true
                return true
            }
            val eq = body.indexOf('=')
            if (eq <= 0) return false
            val key = body.substring(0, eq)
            // The device appends a trailing field-name comment to the sample in
            // its docs, and a future build may append one on the wire: everything
            // after the first space of the value is not part of it.
            val value = body.substring(eq + 1).substringBefore(' ')

            if (key == "pins_total") {
                total = value.toIntOrNull()
                return true
            }
            if (!key.startsWith("pin_")) return false
            val pinKey = key.removePrefix("pin_")
            if (pinKey.isEmpty()) return false
            val parts = value.split(',')
            if (parts.size != 4) return false
            val latE7 = parseDegreesE7(parts[0]) ?: return false
            val lonE7 = parseDegreesE7(parts[1]) ?: return false
            val utc = parts[2].toLongOrNull() ?: return false
            val id = parts[3].toLongOrNull() ?: return false
            entries.add(DevicePin(pinKey, latE7, lonE7, utc, id))
            return true
        }
    }

    /**
     * Reads one page of the reply to `pin log [<offset>]`.
     *
     * Newest first, eight records a page, and the device states where the next
     * page starts so the reader never does the arithmetic -- the same contract as
     * `missing_next` ([MissingList.PageReader]).
     */
    class LogReader {
        private val entries = mutableListOf<PinLogEntry>()
        val records: List<PinLogEntry> get() = entries

        var complete: Boolean = false
            private set

        var unavailable: Boolean = false
            private set

        /** How many records the whole history holds, not this page. */
        var total: Int? = null
            private set

        var offset: Int? = null
            private set

        /** Where the next page starts, or null when this was the last one. */
        var nextOffset: Int? = null
            private set

        /** True once `pinlog_next=done` was seen. */
        var done: Boolean = false
            private set

        /** Feeds one reply line. Returns true if the line belonged to this listing. */
        fun feed(line: String): Boolean {
            val t = line.trim()
            if (t == "OK") {
                complete = true
                return true
            }
            if (!t.startsWith("INFO ")) return false
            val body = t.removePrefix("INFO ").trim()
            if (body == "pins=unavailable") {
                unavailable = true
                return true
            }
            val eq = body.indexOf('=')
            if (eq <= 0) return false
            val key = body.substring(0, eq)
            val value = body.substring(eq + 1).substringBefore(' ')

            when (key) {
                "pinlog_total" -> total = value.toIntOrNull()
                "pinlog_offset" -> offset = value.toIntOrNull()
                "pinlog_next" -> if (value == "done") done = true else nextOffset = value.toIntOrNull()
                else -> {
                    val record = parseRecord(key, value) ?: return false
                    entries.add(record)
                }
            }
            return true
        }

        /** `pinlog_<seq>` = `<op>,<key>,<lat>,<lon>,<utc>`. */
        private fun parseRecord(key: String, value: String): PinLogEntry? {
            if (!key.startsWith("pinlog_")) return null
            val seq = key.removePrefix("pinlog_").toLongOrNull() ?: return null
            val parts = value.split(',')
            if (parts.size != 5) return null
            val op = parts[0]
            if (op.isEmpty()) return null
            val pinKey = parts[1]
            if (pinKey.isEmpty()) return null
            // A `del` record carries no position and prints two empty fields.
            // Empty is the record saying so, not a parse failure.
            val latE7 = if (parts[2].isEmpty()) null else parseDegreesE7(parts[2]) ?: return null
            val lonE7 = if (parts[3].isEmpty()) null else parseDegreesE7(parts[3]) ?: return null
            val utc = parts[4].toLongOrNull() ?: return null
            return PinLogEntry(seq, op, pinKey, latE7, lonE7, utc)
        }
    }
}
