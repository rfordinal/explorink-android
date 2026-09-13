package org.explorink.gpsbridge

/**
 * Reads the device's `NEED_POINTS` ask and its reply to `points`.
 *
 * Split out of [PointSync] the same way [MissingList] is split out of
 * [TileFetcher]: pure parsing that more than one consumer needs without
 * owning a stateful sync instance. [BridgeService.onCommandLine] is exactly
 * that consumer -- it sniffs every command line for `NEED_POINTS` before any
 * gating decision, the same way it already does for [MissingList.parseNeedTiles].
 */
object PointList {

    /** `NEED_POINTS <count> fmt <version>`, or null if the line is something else. */
    data class NeedPoints(val count: Int, val formatVersion: Int?)

    /** `NEED_POINTS ...`, or null if the line is something else. */
    fun parseNeedPoints(line: String): NeedPoints? {
        val t = line.trim()
        if (!t.startsWith("NEED_POINTS")) return null
        val tokens = t.removePrefix("NEED_POINTS").trim().split(' ').filter { it.isNotEmpty() }
        val count = tokens.getOrNull(0)?.toIntOrNull() ?: return null
        val fmtAt = tokens.indexOf("fmt")
        val version = if (fmtAt >= 0) tokens.getOrNull(fmtAt + 1)?.toIntOrNull() else null
        return NeedPoints(count, version)
    }

    /** One shard's status from the `points` reply. */
    data class ShardStatus(val col: Long, val row: Long, val have: Boolean)

    /**
     * Reads the reply to `points`:
     *
     *     INFO point_total=4
     *     INFO point_562_354=have
     *     INFO point_562_355=absent
     *     ...
     *     OK
     *
     * Never paged -- bounded at 9 shards worst case
     * (`MapPointShards::rangeForRadius`'s 3x3 bbox), same reasoning
     * [MissingList.ViewportReader] gives for `tiles`. Two refusals distinct
     * from a real answer, mirroring `have`/`missing`'s own "cannot answer
     * must never read as a real zero": `points=unavailable` (no source wired
     * on the device) and `points=no_position` (no fix to centre a range on).
     */
    class ListReader {
        private val entries = mutableListOf<ShardStatus>()
        val shards: List<ShardStatus> get() = entries

        var complete: Boolean = false
            private set
        var unavailable: Boolean = false
            private set
        var noPosition: Boolean = false
            private set
        var total: Int? = null
            private set

        /**
         * `OK` arrived but fewer `point_<col>_<row>` lines landed than
         * `point_total` declared -- an indication lost on the link. Same
         * guard [MissingList.HaveReader.truncated] has for `have`, and for
         * the same measured reason (2026-08-13, cited there): a listing that
         * lost a line describes a different set of shards than the device
         * actually holds, and answering as if nothing had been missed is
         * worse than refusing the whole listing. Added in code review,
         * 2026-09-13 -- `total` was parsed and never checked against
         * anything.
         */
        val truncated: Boolean
            get() {
                val n = total ?: return false
                return complete && shards.size != n
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

            if (body == "points=unavailable") {
                unavailable = true
                return true
            }
            if (body == "points=no_position") {
                noPosition = true
                return true
            }
            val eq = body.indexOf('=')
            if (eq <= 0) return false
            val key = body.substring(0, eq)
            val value = body.substring(eq + 1)

            if (key == "point_total") {
                total = value.toIntOrNull()
                return true
            }
            if (!key.startsWith("point_")) return false
            val parts = key.removePrefix("point_").split('_')
            if (parts.size != 2) return false
            val col = parts[0].toLongOrNull() ?: return false
            val row = parts[1].toLongOrNull() ?: return false
            val have = when (value) {
                "have" -> true
                "absent" -> false
                else -> return false
            }
            entries.add(ShardStatus(col, row, have))
            return true
        }
    }
}
