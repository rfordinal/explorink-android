package org.explorink.gpsbridge

/**
 * What the device answers to `info`, read off its `INFO key=value` lines.
 *
 * The reply is a flat list of keys the console writes in one uninterrupted
 * block, terminated by a plain `OK` (`MapCommandConsole::writeInfo`). Only the
 * keys a caller here acts on are given fields; the rest stay in [values] rather
 * than being dropped, so a log or a future reader can see them without this
 * class growing a field per line the firmware happens to write.
 *
 * **Pure.** No BLE, no Android, no clock -- the same shape [MissingList] and
 * [PinList] have, and for the same reason: a parser only a phone can exercise is
 * a parser nobody can check against the wire contract.
 * `docs/ble-map-transfer-protocol.md`, "A batch the device never asked for",
 * is that contract and wins if the two disagree.
 */
data class DeviceInfo(
    /**
     * Which screen the device is on. **Never a guess** -- see [Screen].
     */
    val screen: Screen,
    /**
     * The `.tib` format version this build reads, or null when it did not say.
     *
     * The only place a pre-trip sender can learn it: a device with nothing
     * missing never sends `NEED_TILES` or `CHECK_TILES`, which are the two lines
     * that otherwise carry `fmt` ([BridgeService] tracks it from there). Pushing
     * the wrong version wastes the whole transfer -- the tile lands, passes CRC,
     * is renamed into place and is then refused on open.
     */
    val tileFormat: Int?,
    /** The link's real ATT MTU as the device sees it, or null when it did not say. */
    val mtu: Int?,
    /** The negotiated connection interval in ms, or null when it did not say. */
    val connIntervalMs: Int?,
    /** Every `INFO key=value` of the reply, unparsed, in arrival order. */
    val values: Map<String, String> = emptyMap(),
) {

    /**
     * Which screen owns the BLE peripheral right now.
     *
     * **[UNSTATED] is not [MAP].** An older firmware writes no `screen=` line at
     * all, and reading that absence as either screen is exactly the guess this
     * enum exists to prevent: a sender that requires the sync screen must refuse
     * on an older build rather than start a half-hour batch over the map screen,
     * whose post-arrival redraw fires on a settle timer with no check on whether
     * bytes are moving and kills the link on any tile over ~45 kB
     * (`docs/ble-map-transfer-protocol.md`, "The hard half"). Every city tile is
     * over 45 kB.
     */
    enum class Screen {
        /** `INFO screen=sync` -- the Sync map tiles screen. A batch belongs here. */
        SYNC,

        /** `INFO screen=map` -- the map screen. */
        MAP,

        /** No `screen=` line. An older build. Cannot say, and must not be made to. */
        UNSTATED,

        /**
         * A `screen=` word this build does not know.
         *
         * Distinct from [UNSTATED] because it says something different: the
         * firmware is *newer*, not older, and it named a screen that did not
         * exist when this app was written. Both refuse a batch; only this one
         * tells a log reader to go and look at what the new word means.
         */
        OTHER,
    }

    companion object {
        const val COMMAND = "info"

        /** What the device answers when its screen has no push observer. */
        const val PUSH_UNAVAILABLE_LINE = "INFO push=unavailable"

        /**
         * The batch announcement. `n` is how many files are about to go.
         *
         * Bounded 1..[MAX_PUSH_COUNT] by the device's own parser; `push 0` is
         * refused with the grammar's ordinary error words, so a caller with
         * nothing to send must not send this at all.
         */
        fun pushCommand(count: Int): String = "push $count"

        /** The device's own bound on `push <n>` (`MapCommandParser.h`). */
        const val MAX_PUSH_COUNT = 4096
    }

    /**
     * Reads one `info` reply, line by line, ending on the plain `OK`.
     *
     * Same shape as [MissingList.HaveReader] and [PinList.ListReader]: [feed]
     * says whether the line belonged to this reply, so the reader can be fed
     * every line off the channel unconditionally.
     */
    class Reader {
        private val fields = LinkedHashMap<String, String>()

        var complete: Boolean = false
            private set

        /**
         * True once `INFO push=unavailable` was seen on **this** reply.
         *
         * Only ever set by a `push` reply, never by `info` -- kept here because
         * both are one-line conversations on the same channel ending on the same
         * `OK`, and one reader for both is one class fewer to keep in step with
         * the firmware.
         */
        var pushUnavailable: Boolean = false
            private set

        /** Feeds one reply line. Returns true if it belonged to this reply. */
        fun feed(line: String): Boolean {
            val t = line.trim()
            if (t == "OK") {
                complete = true
                return true
            }
            if (t == PUSH_UNAVAILABLE_LINE) {
                pushUnavailable = true
                return true
            }
            // `ERR <reason>` is a terminator on its own with no `OK` behind it,
            // the same as it is for pins. It is not this reader's to interpret,
            // so it is reported as not-mine and the caller ends the request.
            if (!t.startsWith("INFO ")) return false
            val body = t.removePrefix("INFO ").trim()
            val eq = body.indexOf('=')
            if (eq <= 0) return false
            fields[body.substring(0, eq)] = body.substring(eq + 1)
            return true
        }

        /**
         * What has been read so far.
         *
         * Readable before [complete], deliberately: a reply that lost its `OK`
         * still carries the keys that did arrive, and a caller timing out is
         * better off saying "the device is on its map screen" than "no answer".
         */
        fun info(): DeviceInfo = DeviceInfo(
            screen = when (fields["screen"]) {
                null -> Screen.UNSTATED
                "sync" -> Screen.SYNC
                "map" -> Screen.MAP
                else -> Screen.OTHER
            },
            tileFormat = fields["tile_fmt"]?.toIntOrNull(),
            mtu = fields["mtu"]?.toIntOrNull(),
            connIntervalMs = fields["conn_interval_ms"]?.toIntOrNull(),
            values = LinkedHashMap(fields),
        )
    }
}
