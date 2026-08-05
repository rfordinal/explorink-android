package org.trailink.gpsbridge

/**
 * One tile the device asked for, as the `missing` command reports it.
 *
 * The device sends them already in fetch priority -- regional LOD first, then
 * overview, then detail, hit count breaking ties inside a tier
 * (`firmware/trailink/src/MissingTilePriority.h`). **Keep that order.** A fetch
 * that gets interrupted should have delivered the tiles the rider's normal view
 * needs, and re-sorting this list here would throw that away.
 */
data class MissingTile(
    val z: Int,
    val col: Long,
    val row: Long,
    val count: Long,
)

/**
 * Reads the device's replies to `missing [<offset>]` and the one line it sends
 * unprompted, `NEED_TILES <count>`.
 *
 * Wire shape (`firmware/trailink/docs/missing-tiles.md`):
 *
 *     INFO missing_total=25
 *     INFO missing_offset=0
 *     INFO missing_13_4000_2832=1
 *     ...
 *     INFO missing_next=20         (or missing_next=done)
 *     OK
 *
 * Pure parsing, no BLE: the transport hands lines in, this hands structure out.
 */
object MissingList {

    /**
     * The device asking for a fetch: `NEED_TILES <count> fmt <version>`.
     *
     * [formatVersion] is the only `.tib` format version that build can read
     * (`MapTileReader::kFormatVersion`). It matters because a tile built to
     * another version transfers fine, passes CRC, is renamed into place -- and
     * is then refused by the reader on the next render and recorded as missing
     * again. Pushing one is a transfer wasted on every fetch, not just once.
     *
     * Null when the device did not say, which is an older firmware build. Then
     * the supplier can only push and hope; see [TileFetcher].
     */
    data class NeedTiles(val count: Int, val formatVersion: Int?)

    /** `NEED_TILES ...`, or null if the line is something else. */
    fun parseNeedTiles(line: String): NeedTiles? {
        val t = line.trim()
        if (!t.startsWith("NEED_TILES")) return null
        val tokens = t.removePrefix("NEED_TILES").trim().split(' ').filter { it.isNotEmpty() }
        val count = tokens.getOrNull(0)?.toIntOrNull() ?: return null
        // `fmt <version>` is a keyword pair rather than a bare trailing number
        // so a later field can be added the same way without the two colliding.
        val fmtAt = tokens.indexOf("fmt")
        val version = if (fmtAt >= 0) tokens.getOrNull(fmtAt + 1)?.toIntOrNull() else null
        return NeedTiles(count, version)
    }

    /** The rider pressed Back on the device's fetch screen. Stop pushing. */
    fun isFetchCancel(line: String): Boolean = line.trim() == "FETCH_CANCEL"

    /** Accumulates one page of a listing, line by line. */
    class PageReader {
        var total: Int? = null
            private set
        var offset: Int? = null
            private set

        /** Where the next page starts, once the page is complete. */
        var nextOffset: Int? = null
            private set

        /** True once `missing_next=done` was seen: this was the last page. */
        var done: Boolean = false
            private set

        /**
         * True when no store is wired to the device's console at all.
         * Deliberately not the same as `total == 0`: a firmware build that never
         * connected the two must not read as a device that needs no tiles.
         */
        var unavailable: Boolean = false
            private set

        /** True once the terminating `OK` arrived. */
        var complete: Boolean = false
            private set

        private val entries = mutableListOf<MissingTile>()
        val tiles: List<MissingTile> get() = entries

        /** Feeds one reply line. Returns true if the line belonged to this listing. */
        fun feed(line: String): Boolean {
            val t = line.trim()
            if (t == "OK") {
                complete = true
                return true
            }
            if (!t.startsWith("INFO ")) return false
            val body = t.removePrefix("INFO ").trim()

            if (body == "missing=unavailable") {
                unavailable = true
                return true
            }
            val eq = body.indexOf('=')
            if (eq <= 0) return false
            val key = body.substring(0, eq)
            val value = body.substring(eq + 1)

            when (key) {
                "missing_total" -> total = value.toIntOrNull()
                "missing_offset" -> offset = value.toIntOrNull()
                "missing_next" -> if (value == "done") done = true else nextOffset = value.toIntOrNull()
                else -> {
                    val tile = parseEntry(key, value) ?: return false
                    entries.add(tile)
                }
            }
            return true
        }

        /** `missing_<z>_<col>_<row>` = `<count>`. */
        private fun parseEntry(key: String, value: String): MissingTile? {
            if (!key.startsWith("missing_")) return null
            val parts = key.removePrefix("missing_").split('_')
            if (parts.size != 3) return null
            val z = parts[0].toIntOrNull() ?: return null
            val col = parts[1].toLongOrNull() ?: return null
            val row = parts[2].toLongOrNull() ?: return null
            val count = value.toLongOrNull() ?: return null
            if (z !in 0..255) return null
            return MissingTile(z, col, row, count)
        }
    }
}
