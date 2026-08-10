package org.explorink.gpsbridge

/**
 * One tile the device asked for, as the `missing` command reports it.
 *
 * The device sends them already in fetch priority -- regional LOD first, then
 * overview, then detail, hit count breaking ties inside a tier
 * (`firmware/explorink/src/MissingTilePriority.h`). **Keep that order.** A fetch
 * that gets interrupted should have delivered the tiles the rider's normal view
 * needs, and re-sorting this list here would throw that away.
 */
data class MissingTile(
    val z: Int,
    val col: Long,
    val row: Long,
    /**
     * How many separate viewport resets asked for this tile. **0 from a `tiles`
     * reply**, which carries no hit count -- the viewport listing is already
     * "what is on screen right now", so there is nothing for a count to rank.
     */
    val count: Long,
)

/**
 * Reads the device's replies to `missing [<offset>]` and the one line it sends
 * unprompted, `NEED_TILES <count>`.
 *
 * Wire shape (`firmware/explorink/docs/missing-tiles.md`):
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
    data class NeedTiles(
        val count: Int,
        val formatVersion: Int?,
        /**
         * The `view` word: answer from `tiles`, not from `missing`.
         *
         * The map screen's autosync sends this mid-ride, unannounced, when a
         * frame had to hatch something. `tiles` reports the tiles under the
         * screen right now, at most 32; `missing` reports every tile the device
         * has ever hatched, up to 200. On the trail those are very different
         * amounts of the rider's mobile data, and only the first is what they
         * are looking at.
         *
         * False for the tile sync screen's ask, which wants the whole list --
         * that one is preparation at home, and the point there is coverage.
         */
        val viewportOnly: Boolean = false,
    )

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
        // A bare flag word, not a keyword pair: it has no value, and a device
        // that does not know it simply never sends it.
        val viewportOnly = tokens.contains("view")
        return NeedTiles(count, version, viewportOnly)
    }

    /** The rider pressed Back on the device's fetch screen. Stop pushing. */
    fun isFetchCancel(line: String): Boolean = line.trim() == "FETCH_CANCEL"

    /**
     * The device asking whether the tiles it already holds are still current:
     * `CHECK_TILES <count> fmt <version>`.
     *
     * A different question from [NeedTiles], which is about tiles it does not
     * have at all. This one names tiles that open fine and draw fine, and asks
     * whether the CDN has since published different content for them
     * (`docs/tile-index-spec.md`; `firmware/explorink/docs/tile-freshness.md`).
     *
     * [formatVersion] matters here for the same reason as [NeedTiles]: the
     * index lives under the same `/v<N>/` prefix as the tiles it describes.
     * Without it, [FreshnessChecker] had nothing to go on until a `NEED_TILES`
     * happened to arrive first -- which a device with zero missing tiles never
     * sends -- so it silently checked against a stale format's index tree,
     * every time, for exactly the devices this feature exists to keep current.
     */
    data class CheckTiles(val count: Int, val formatVersion: Int?)

    /**
     * Returns the count and format version the device stated, or null if the
     * line is something else. Count zero is possible and means "nothing to
     * check" -- the phone answers `checked 0` without asking for a list.
     */
    fun parseCheckTiles(line: String): CheckTiles? {
        val t = line.trim()
        if (!t.startsWith("CHECK_TILES")) return null
        val tokens = t.removePrefix("CHECK_TILES").trim().split(' ').filter { it.isNotEmpty() }
        val count = tokens.getOrNull(0)?.toIntOrNull() ?: return null
        val fmtAt = tokens.indexOf("fmt")
        val version = if (fmtAt >= 0) tokens.getOrNull(fmtAt + 1)?.toIntOrNull() else null
        return CheckTiles(count, version)
    }

    /**
     * Reads the reply to `have`: the tiles the device holds, with the content id
     * it holds each at.
     *
     *     INFO have_total=4
     *     INFO have_13_4482_2839=0ebd55c8
     *     INFO have_13_4482_2840=f7a3de8d
     *     OK
     *
     * The content id is lowercase hex, up to 8 digits, no `0x`. Hex rather than
     * decimal because the same number is printed that way in the device's log
     * and in `mapbuilder`'s output, and a freshness bug gets diagnosed by eye
     * across all three.
     *
     * `INFO have=none` means the device cannot answer at all -- no viewport yet.
     * Kept distinct from an empty list for the same reason
     * `missing=unavailable` is: "cannot answer" must never read as "everything
     * is current".
     *
     * Never paged. A check is bounded by what the device is looking at (at most
     * 9 tiles -- `MapViewport::kMaxTiles`, a 3x3 worst case), so it fits one
     * reply comfortably -- and the phone
     * turns the whole reply into a single byte-range read per zoom plane, so a
     * list big enough to need paging would also be one the check could not
     * answer inside the reply timeout.
     */
    class HaveReader {
        private val entries = mutableListOf<HeldTile>()
        val tiles: List<HeldTile> get() = entries

        var complete: Boolean = false
            private set

        /** The device has no viewport yet, so there is nothing to check. */
        var unavailable: Boolean = false
            private set

        var total: Int? = null
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

            if (body == "have=none") {
                unavailable = true
                return true
            }
            val eq = body.indexOf('=')
            if (eq <= 0) return false
            val key = body.substring(0, eq)
            val value = body.substring(eq + 1)

            if (key == "have_total") {
                total = value.toIntOrNull()
                return true
            }
            if (!key.startsWith("have_")) return false
            val parts = key.removePrefix("have_").split('_')
            if (parts.size != 3) return false
            val z = parts[0].toIntOrNull() ?: return false
            val col = parts[1].toLongOrNull() ?: return false
            val row = parts[2].toLongOrNull() ?: return false
            // Unsigned 32-bit, so it does not fit an Int and must not be parsed
            // as one -- half the possible content ids are negative in that type.
            val contentId = value.toLongOrNull(16) ?: return false
            if (z !in 0..255 || contentId < 0 || contentId > 0xFFFFFFFFL) return false
            entries.add(HeldTile(z, col, row, contentId))
            return true
        }
    }

    /**
     * What the fetcher needs from a listing, whichever command produced it.
     *
     * Two wire shapes answer "which tiles do you want": paged `missing`, and
     * single-shot `tiles`. They differ in enough detail that one parser for both
     * would be a parser with two modes, so they are two classes behind this.
     */
    interface Listing {
        /** Tiles to push, in the order the device gave them. */
        val tiles: List<MissingTile>

        /** True once the terminating `OK` arrived. */
        val complete: Boolean

        /** The device could not answer at all -- no store, or no viewport yet. */
        val unavailable: Boolean

        /** The device's own count of what it wants, when it states one. */
        val total: Int?

        /** Where the next page starts, or null when there is no paging. */
        val nextOffset: Int?

        /** True when this was the last page. */
        val done: Boolean

        /** Feeds one reply line. Returns true if the line belonged to this listing. */
        fun feed(line: String): Boolean
    }

    /**
     * Reads the reply to `tiles`: the current viewport, one line per tile.
     *
     *     INFO tile_13_4496_2846=missing
     *     INFO tile_13_4496_2847=ok
     *     OK
     *
     * **Only the `missing` ones go in [tiles].** The `ok` ones are already on the
     * device's card, and pushing them would spend the rider's data to overwrite a
     * file with itself.
     *
     * Never paged: the viewport is at most 32 tiles
     * (`MapViewport::kMaxTiles`), which fits one reply.
     */
    class ViewportReader : Listing {
        private val entries = mutableListOf<MissingTile>()
        override val tiles: List<MissingTile> get() = entries

        override var complete: Boolean = false
            private set

        /**
         * `INFO tiles=none` -- the device has no viewport yet, because no fix or
         * `pos` has landed since it started. Distinct from an empty viewport for
         * the same reason the store's `missing=unavailable` is: "cannot answer"
         * must not read as "needs nothing".
         */
        override var unavailable: Boolean = false
            private set

        /** The number of missing tiles seen, once the reply is complete. */
        override val total: Int? get() = if (complete) entries.size else null

        /** No paging in this shape. */
        override val nextOffset: Int? get() = null
        override val done: Boolean get() = true

        override fun feed(line: String): Boolean {
            val t = line.trim()
            if (t == "OK") {
                complete = true
                return true
            }
            if (!t.startsWith("INFO ")) return false
            val body = t.removePrefix("INFO ").trim()

            if (body == "tiles=none") {
                unavailable = true
                return true
            }
            val eq = body.indexOf('=')
            if (eq <= 0) return false
            val key = body.substring(0, eq)
            val value = body.substring(eq + 1)

            if (!key.startsWith("tile_")) return false
            // An `ok` tile is a line that belonged to this listing and is
            // deliberately not queued -- return true, add nothing.
            if (value != "missing") return true

            val parts = key.removePrefix("tile_").split('_')
            if (parts.size != 3) return false
            val z = parts[0].toIntOrNull() ?: return false
            val col = parts[1].toLongOrNull() ?: return false
            val row = parts[2].toLongOrNull() ?: return false
            if (z !in 0..255) return false
            entries.add(MissingTile(z, col, row, 0))
            return true
        }
    }

    /** Accumulates one page of a listing, line by line. */
    class PageReader : Listing {
        override var total: Int? = null
            private set
        var offset: Int? = null
            private set

        /** Where the next page starts, once the page is complete. */
        override var nextOffset: Int? = null
            private set

        /** True once `missing_next=done` was seen: this was the last page. */
        override var done: Boolean = false
            private set

        /**
         * True when no store is wired to the device's console at all.
         * Deliberately not the same as `total == 0`: a firmware build that never
         * connected the two must not read as a device that needs no tiles.
         */
        override var unavailable: Boolean = false
            private set

        /** True once the terminating `OK` arrived. */
        override var complete: Boolean = false
            private set

        private val entries = mutableListOf<MissingTile>()
        override val tiles: List<MissingTile> get() = entries

        /** Feeds one reply line. Returns true if the line belonged to this listing. */
        override fun feed(line: String): Boolean {
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
