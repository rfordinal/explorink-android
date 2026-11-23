package org.explorink.gpsbridge

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.roundToLong
import kotlin.math.sinh

/**
 * What a zone actually costs, before the rider commits to it.
 *
 * A city box is 55 tiles, 16 MB and 20 to 40 minutes over BLE
 * (`docs/send-tiles-plan.md`, "What a box around the city centre costs"). Nobody
 * should start that without being told the number first, and the number is
 * **arithmetic, never an average**: the freshness index carries each tile's
 * exact `sizeBytes`, verified byte for byte against the served HTTP body for
 * three Barcelona tiles on 2026-09-02.
 *
 * The one thing this refuses to do is collapse "no tile" into a single answer.
 * A slot that is not present means one of two opposite things:
 *
 *  - **the ground was built and this tile is genuinely not there** -- sea, empty
 *    OSM. It will never exist. Retrying it is retrying sea.
 *  - **nobody has built this ground yet.** It will exist, minutes from now, and
 *    nothing has to happen for it to (`docs/tile-autobuild.md`).
 *
 * A 20 km box around Barcelona holds 55 of the first kind of hit, 3 of the
 * second and 13 of the third (measured against `v4/base/index/7/64/47.idx`,
 * 2026-09-02). Fold the last two together and the queue either retries sea
 * forever or gives up on a city that was about to appear.
 *
 * Pure: no Android, no I/O, no clock. The HTTP that produces a [Reading] lives
 * in `IndexSource`; this only reads the answer.
 */
object TilePlan {

    /**
     * Measured BLE throughput: 81 774 B in 9.08 s at MTU 256, 2026-08-14
     * (`docs/ble-map-transfer-protocol.md`).
     *
     * A **starting** figure and nothing more. The real link is not a constant --
     * the wallet measured 7.4 kB/s at a 15 ms connection interval, 3.9 at 30 ms
     * and 0.33 with the phone's screen off -- so [etaSeconds] takes a rate
     * rather than reading this, and the caller feeds it what the last real batch
     * actually did. This is only what to say before there has been a batch.
     */
    const val START_BYTES_PER_SECOND = 9000.0

    /** What is known about one tile on the CDN. */
    enum class State {
        /** No index read has answered yet, or the CDN could not be reached. */
        UNKNOWN,

        /** Published, and its size is exact. */
        PRESENT,

        /** Nothing is built here yet. It is coming; ask again later. */
        WAITING_BUILD,

        /** Built ground, no tile. Sea or empty OSM. It is never coming. */
        ABSENT,
    }

    /**
     * What one index read produced, in the same three shapes `IndexSource.Result`
     * has -- and for the same reason: **an unreachable CDN must never look like
     * a verdict.**
     *
     * Restated here rather than imported so this file stays free of the HTTP
     * class. Both 404s are real and reachable: a block for ground nobody has
     * built answers 404 (`v4/base/index/7/60/44.idx`, 2026-09-02), and so does a
     * tile inside a block whose slot is empty. Only the second can ever be
     * [State.ABSENT].
     */
    sealed class Reading {
        /** The block exists and this is its 16-byte slot. */
        class Found(val slot: TileIndex.Slot) : Reading()

        /** No index block covers this ground at all. Nothing is published here. */
        object NoBlock : Reading()

        /** No network, a server error, a range that came back short. Nothing is known. */
        object Unreachable : Reading()
    }

    /**
     * One published area, as `v4/mapset.json` lists it.
     *
     * Only the fields a coverage test needs. `build_epoch`, `rules_hash`,
     * `tiles` and `points` are in the file too and are deliberately not read
     * here -- this class answers one question, and a field nobody reads is a
     * field that goes stale without anyone noticing.
     */
    data class BuiltArea(
        val name: String,
        val south: Double,
        val west: Double,
        val north: Double,
        val east: Double,
    )

    /**
     * Whether a build has ever covered a tile's ground.
     *
     * This is the whole of "not yet" versus "never", and it is the one thing the
     * tile fetch cannot answer: a 404 is a 404 either way.
     *
     * Two paths, and the second is not decoration. Every area published today is
     * named `auto-z11-<col>-<row>` by the server's own queue, which makes the
     * lookup a set membership test on the tile's z11 ancestor -- 60 areas is
     * 21 kB of JSON today and it grows linearly, so nothing here re-parses per
     * tile. But an area published **on purpose** (T-310) will not carry that
     * name, so anything that does not match the pattern is kept and tested by
     * its bbox instead. Making the name the only path would silently declare
     * hand-built ground unbuilt.
     *
     * The bbox test demands the tile lie **wholly** inside one area, which is
     * what the z11 fast path means as well (a z11 area contains all of its
     * children). It is the conservative direction on purpose: calling built
     * ground unbuilt costs a few retries and then a give-up, while calling
     * unbuilt ground built marks a tile [State.ABSENT] and the rider never gets
     * it.
     */
    class BuiltGround(areas: List<BuiltArea> = emptyList()) {

        private val autoCells = HashSet<Long>()
        private val boxes = ArrayList<BuiltArea>()

        init {
            for (a in areas) {
                val m = AUTO_NAME.matchEntire(a.name)
                val col = m?.groupValues?.get(1)?.toLongOrNull()
                val row = m?.groupValues?.get(2)?.toLongOrNull()
                if (col != null && row != null) autoCells.add(cellKey(col, row)) else boxes.add(a)
            }
        }

        fun covers(tile: TileRef): Boolean {
            if (tile.z >= AUTO_AREA_Z) {
                val shift = tile.z - AUTO_AREA_Z
                if (cellKey(tile.col shr shift, tile.row shr shift) in autoCells) return true
            }
            if (boxes.isEmpty()) return false
            val b = TileBounds.of(tile)
            return boxes.any {
                it.south <= b.south && it.north >= b.north && it.west <= b.west && it.east >= b.east
            }
        }

        private companion object {
            /** The name the server's own build queue writes. `tilequeue.py`. */
            val AUTO_NAME = Regex("""auto-z11-(\d+)-(\d+)""")
            const val AUTO_AREA_Z = 11

            /** One key for a (col, row) pair; z11 columns fit in 11 bits. */
            fun cellKey(col: Long, row: Long): Long = (col shl 32) or (row and 0xffffffffL)
        }
    }

    /** A tile's own ground bounds, in degrees. */
    data class TileBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
        companion object {
            fun of(tile: TileRef): TileBounds {
                val n = (1L shl tile.z).toDouble()
                val west = tile.col / n * 360.0 - 180.0
                val east = (tile.col + 1) / n * 360.0 - 180.0
                return TileBounds(
                    south = latOfRow(tile.row + 1, n),
                    west = west,
                    north = latOfRow(tile.row, n),
                    east = east,
                )
            }

            private fun latOfRow(row: Long, n: Double): Double =
                atan(sinh(PI * (1.0 - 2.0 * row / n))) * 180.0 / PI
        }
    }

    /**
     * What one index read means for one tile.
     *
     * [Reading.NoBlock] is [State.WAITING_BUILD] even when an area claims the
     * ground: the index block is written by the build itself, so a claim with no
     * block behind it describes a build that did not finish, and the honest
     * answer to that is "ask again", not "never".
     */
    fun classify(reading: Reading, groundIsBuilt: Boolean): State = when (reading) {
        is Reading.Unreachable -> State.UNKNOWN
        is Reading.NoBlock -> State.WAITING_BUILD
        is Reading.Found ->
            if (reading.slot.present) State.PRESENT
            else if (groundIsBuilt) State.ABSENT
            else State.WAITING_BUILD
    }

    /** One tile of a plan, as the index describes it. */
    data class Entry(
        val tile: TileRef,
        val state: State,
        /** Exact, from the index. Zero for anything not [State.PRESENT]. */
        val sizeBytes: Long = 0L,
        val contentId: Long = 0L,
    ) {
        companion object {
            /** Reads one slot into an entry, taking the size only when it is real. */
            fun of(tile: TileRef, reading: Reading, groundIsBuilt: Boolean): Entry {
                val state = classify(reading, groundIsBuilt)
                val slot = (reading as? Reading.Found)?.slot
                return if (state == State.PRESENT && slot != null) {
                    Entry(tile, state, slot.sizeBytes, slot.contentId)
                } else {
                    Entry(tile, state)
                }
            }
        }
    }

    /**
     * What the rider is being asked to agree to.
     *
     * [bytes] counts [State.PRESENT] tiles only. An absent tile has no bytes and
     * a waiting one has no size yet -- charging the rider for either would put a
     * number on the screen that the transfer can never reach.
     */
    data class Summary(
        val tiles: Int,
        val present: Int,
        val waitingBuild: Int,
        val absent: Int,
        val unknown: Int,
        val bytes: Long,
    ) {
        /** Seconds to send [bytes] at [bytesPerSecond]. Null when the rate is not usable. */
        fun etaSeconds(bytesPerSecond: Double): Long? = TilePlan.etaSeconds(bytes, bytesPerSecond)
    }

    /** Counts and bytes over a plan. Duplicate tiles are counted once. */
    fun summarize(entries: Collection<Entry>): Summary {
        val seen = HashSet<String>()
        var present = 0
        var waiting = 0
        var absent = 0
        var unknown = 0
        var bytes = 0L
        for (e in entries) {
            // Two zones round the same city share almost every tile, and the
            // device only needs each one once -- so a total that counted them
            // twice would promise twice the transfer that is going to happen.
            if (!seen.add(e.tile.key)) continue
            when (e.state) {
                State.PRESENT -> {
                    present++
                    bytes += e.sizeBytes
                }
                State.WAITING_BUILD -> waiting++
                State.ABSENT -> absent++
                State.UNKNOWN -> unknown++
            }
        }
        return Summary(seen.size, present, waiting, absent, unknown, bytes)
    }

    /**
     * Seconds to move [bytes] at [bytesPerSecond], or null when there is no
     * usable rate.
     *
     * Null rather than a fallback constant: "I do not know yet" and "18 minutes"
     * are different things to say to somebody deciding whether to start a
     * half-hour transfer, and a constant dressed up as a measurement is how the
     * wallet's screen came to be wrong by a factor of twenty.
     */
    fun etaSeconds(bytes: Long, bytesPerSecond: Double): Long? {
        if (!bytesPerSecond.isFinite() || bytesPerSecond <= 0.0) return null
        if (bytes <= 0L) return 0L
        return (bytes / bytesPerSecond).roundToLong()
    }
}
