package org.explorink.gpsbridge

/**
 * The zone the rider picked. Intent, never progress.
 *
 * [label] is what the screen shows ("Barcelona 20 km") and is written once, at
 * the pick, because nothing later can reconstruct it -- the app has no
 * geocoder and never asks Google anything (`docs/send-tiles-plan.md`,
 * "Picking the area").
 */
data class TileZone(
    val zoneId: String,
    val label: String,
    val latE7: Int,
    val lonE7: Int,
    val sideKm: Int,
    val createdAtMs: Long,
)

/**
 * One tile the rider asked for, and everything since learned about it.
 *
 * **This record is the only place the ask exists.** The wallet's plan is derived
 * from files the phone still holds, so a wallet item cannot be lost; a tile item
 * is a decision and nothing else records it. That is why the plan is stored
 * rather than recomputed, and why every field here has to survive a process
 * kill.
 */
data class TileItem(
    val zoneId: String,
    val tile: TileRef,
    /** When the rider asked. The 24 h give-up counts from here, not from the last look. */
    val queuedAtMs: Long,
    /** What the last index read said. [TilePlan.State.UNKNOWN] until one answers. */
    val cdn: TilePlan.State = TilePlan.State.UNKNOWN,
    /** Exact, from the index. Zero until the tile is known present. */
    val sizeBytes: Long = 0L,
    /**
     * What the CDN says this tile's content is.
     *
     * Carried so the fetch can ask for `?crc=<content_id>`: the edge caches a
     * tile path for seven days with no purge, so a rebuilt tile is served as the
     * copy it replaces unless the request names the version wanted
     * ([TileSource.read]).
     */
    val contentId: Long = 0L,
    /** How many index reads have answered "not built yet". Drives the backoff. */
    val buildChecks: Int = 0,
    /** Earliest ms this item may be looked at or sent again. */
    val nextTryAtMs: Long = 0L,
    /** Transient failures since the last success. */
    val attempts: Int = 0,
    /** Last transient failure, short and human-readable. Null when there was none. */
    val error: String? = null,
    /** A failure that will not come right by waiting. Never retried. */
    val terminal: Boolean = false,
) {
    val key: String get() = tile.key
}

/**
 * The device's own word that it holds a tile. The only thing that makes a tile
 * sent.
 */
data class TileReceipt(
    /** Bytes the device counted, from `OK <bytes> <crc32hex>`. */
    val bytes: Long,
    /** CRC the device computed by reading the file back off the card. */
    val crc32: Long,
    /** `ble` today. Wi-Fi later, behind the same seam. */
    val transport: String,
    val atMs: Long,
)

/**
 * Where one item stands. Derived on every read, never stored.
 */
enum class TileState {
    /** Waiting its turn: either the index has not been read yet, or it is ready to push. */
    QUEUED,

    /** A transport is pushing it right now. */
    SENDING,

    /** The device said `OK` and the bytes and CRC matched what went out. */
    SENT,

    /** Nothing is built on this ground yet. It is coming; the recheck is timed. */
    WAITING_BUILD,

    /** Built ground, no tile. Sea, or empty OSM. Never retried. */
    ABSENT,

    /** Still unbuilt 24 h after the ask. Past the server's own cooldown, so it is over. */
    EXPIRED,

    /** A failure that waiting does not fix. Never retried. */
    FAILED,

    /** A transient failure, waiting out its backoff. */
    RETRY,
}

/**
 * The persistent queue behind sending a whole area to the device.
 *
 * A city is ~55 tiles and ~16 MB, which is 20 to 40 minutes over BLE. That does
 * not survive one connection, so this is **not an operation**: it is a ledger
 * that outlives the link, the process and the phone reboot, and it drains
 * whenever a link exists and the device is on its sync screen.
 *
 * Deliberately the same shape as `WalletSyncQueue` on `feat/wallet` -- same
 * method names, same laws -- because one shared queue is coming (T-115) and an
 * extraction from two working implementations is measuring, while designing the
 * base class now would be guessing. Two laws are copied verbatim:
 *
 *  - **Only a receipt makes a sent state.** The device's `OK <bytes> <crc32hex>`
 *    and nothing else. The wallet's comment is worth repeating here: "A 200 from
 *    `/upload` is not a verdict." Neither is the last chunk being written.
 *  - **States are derived, never stored.** What is stored is the ledger of
 *    receipts plus what the rider asked for.
 *
 * One structural difference, and it is why the plan is stored: a wallet item is
 * derived from a file the phone still holds, so it cannot be lost. **A tile item
 * exists nowhere but here.**
 *
 * And one state the wallet has no analogue for. A wallet asset fails because the
 * phone, the device or the link failed. A tile can fail because **the server has
 * not made it yet** -- neither an error nor a skip, and it resolves with nobody
 * doing anything (`docs/tile-autobuild.md`). Telling that from "this tile will
 * never exist" is done from the index and `mapset.json`, never from the tile
 * fetch, because a 404 is a 404 either way ([TilePlan.classify]). Without the
 * split a 40 km box around Barcelona retries 125 squares of sea forever.
 *
 * Pure: no Android, no I/O, no threads, no clock -- every method that needs the
 * time is given it. Single-threaded by contract, like [TileFetcher].
 *
 * **On disk** (`<filesDir>/tiles/outbox.json`, app-private, the wallet's
 * convention). One object, three arrays, and a serializer needs nothing this
 * class does not expose:
 *
 * ```
 * { "version": 1,
 *   "zones":    [ {zoneId,label,latE7,lonE7,sideKm,createdAtMs}, ... ],
 *   "items":    [ {zoneId,z,col,row,queuedAtMs,cdn,sizeBytes,contentId,
 *                  buildChecks,nextTryAtMs,attempts,error,terminal}, ... ],
 *   "receipts": { "13/4144/3059": {bytes,crc32,transport,atMs}, ... } }
 * ```
 *
 * `cdn` is [TilePlan.State] by name, so an unknown word from a newer build reads
 * back as [TilePlan.State.UNKNOWN] and costs one index read rather than a wrong
 * verdict. [inFlight] is **not** written: a process that died mid-transfer sent
 * no receipt, so the tile is pending again by construction.
 */
class TileOutbox(
    zones: List<TileZone> = emptyList(),
    items: List<TileItem> = emptyList(),
    receipts: Map<String, TileReceipt> = emptyMap(),
) {

    companion object {
        /**
         * How long to wait before asking the index again about ground that is not
         * built yet, in order. The last entry repeats.
         *
         * Read off the server's own cadence (`mapbuilder/tools/tilequeue/tilequeue.py`,
         * `docs/tile-autobuild.md`): it builds 10 missing tiles per pass, one
         * area at a time, and one z11 area of 21 tiles took 66.5 s measured on
         * the v4 deploy. `mapset.json` also sits behind a 300 s Varnish
         * `max-age`, so asking sooner than five minutes cannot see a change even
         * when there is one.
         */
        val RECHECK_MS = longArrayOf(5 * 60_000L, 15 * 60_000L, 60 * 60_000L)

        /**
         * Give up on unbuilt ground this long after the **ask**.
         *
         * The server holds a 24 h cooldown per z11 cell (`State.eligible`), so
         * past it nothing further will be built for that ground in this round no
         * matter how often it is asked. Waiting longer is waiting on nothing.
         */
        const val GIVE_UP_MS = 24 * 60 * 60_000L

        /**
         * Backoff after a transient failure -- a dropped link, an HTTP error.
         *
         * Short, because these are the failures that come right on their own,
         * and [clearFailures] shortcuts it anyway when a fresh link arrives. It
         * exists so a CDN that is refusing every request is not hammered inside
         * one connection.
         */
        const val TRANSIENT_RETRY_MS = 60_000L
    }

    private val zoneList = ArrayList(zones)
    private val plan = ArrayList(items)
    private val ledger = LinkedHashMap(receipts)

    /** Bytes handed to the transport for the tile in flight. Progress only, not persisted. */
    private val sent = HashMap<String, Long>()

    /** What the transport says it is about to push, so a receipt can be checked against it. */
    private var outgoing: Outgoing? = null

    private data class Outgoing(val key: String, val bytes: Long, val crc32: Long)

    /** The tile a transport is working on right now, or null. */
    var inFlight: String? = null
        private set

    // --- what a serializer needs -------------------------------------------

    val zones: List<TileZone> get() = zoneList
    val items: List<TileItem> get() = plan
    val receipts: Map<String, TileReceipt> get() = ledger

    // --- what the rider asked for ------------------------------------------

    /**
     * Adds a zone and its tiles, in the order [TileBox] produced them (coarse
     * zoom first, centre tile first).
     *
     * A tile already asked for by **another** zone is still added: the item is
     * the ask, and dropping one would make removing the first zone silently
     * cancel the second one's ground. The ledger is keyed by tile, so it is
     * still sent once ([next] skips what is already sent or in flight).
     */
    fun addZone(zone: TileZone, tiles: List<TileRef>) {
        if (zoneList.any { it.zoneId == zone.zoneId }) return
        zoneList.add(zone)
        val already = HashSet<String>()
        for (t in tiles) {
            if (!already.add(t.key)) continue
            plan.add(TileItem(zoneId = zone.zoneId, tile = t, queuedAtMs = zone.createdAtMs))
        }
    }

    /**
     * Drops a zone and its items. Nothing else moves.
     *
     * Receipts are **kept**, including for tiles no zone wants any more: a
     * receipt is a fact about what is on the device's card, and forgetting it
     * would make the next overlapping zone push bytes the device already holds.
     */
    fun removeZone(zoneId: String) {
        zoneList.removeAll { it.zoneId == zoneId }
        val gone = plan.filter { it.zoneId == zoneId }.map { it.key }.toSet()
        plan.removeAll { it.zoneId == zoneId }
        if (inFlight != null && inFlight in gone && plan.none { it.key == inFlight }) release()
    }

    fun zone(zoneId: String): TileZone? = zoneList.firstOrNull { it.zoneId == zoneId }

    // --- the ledger ---------------------------------------------------------

    fun isSent(key: String): Boolean = ledger.containsKey(key)

    /**
     * The transport has the bytes and is about to push them.
     *
     * Recording what went out is what makes a receipt checkable. The size the
     * index states is exact (verified against the served body, 2026-09-02), but
     * the CRC is of the bytes themselves and only the sender knows it -- the
     * index's `content_id` is a hash of layer CRCs, not of the file
     * (`mapbuilder/tilegen/tiles.py`, `content_id_from_layer_crcs()`).
     */
    fun beginSend(key: String, bytes: Long, crc32: Long) {
        outgoing = Outgoing(key, bytes, crc32)
    }

    /**
     * The device answered `OK <bytes> <crc32hex>`. Returns true if that is a
     * receipt for what was actually sent.
     *
     * **A mismatch is not a sent tile.** The device reads the CRC back off the
     * card rather than accumulating it over the arriving chunks
     * (`docs/ble-map-transfer-brief.md`), so a disagreement means the file on the
     * card is not the file that went out -- exactly the case a receipt exists to
     * catch. It is recorded as a transient failure: the same tile from the same
     * CDN usually lands correctly on the next attempt, and a card that keeps
     * failing shows up as a growing [TileItem.attempts] rather than as a tile
     * quietly marked done.
     *
     * A receipt with no [beginSend] behind it is refused for the same reason.
     */
    fun confirm(key: String, bytes: Long, crc32: Long, transport: String, atMs: Long): Boolean {
        val out = outgoing
        if (out == null || out.key != key || out.bytes != bytes || out.crc32 != crc32) {
            fail(key, "receipt mismatch", atMs)
            return false
        }
        ledger[key] = TileReceipt(bytes, crc32, transport, atMs)
        outgoing = null
        sent.remove(key)
        updateAll(key) { it.copy(attempts = 0, error = null, terminal = false, nextTryAtMs = 0L) }
        if (inFlight == key) inFlight = null
        return true
    }

    /**
     * A failure that is not the CDN's verdict: the link died, the device refused
     * the push, an HTTP error.
     *
     * [terminal] for the ones waiting cannot fix -- a tile built to a format
     * version this device cannot read is the live example ([TileFetcher.SKIP_WRONG_FORMAT]):
     * every retry transfers, passes CRC, is renamed into place and is then
     * refused on open, so retrying spends the rider's data on a certainty.
     */
    fun fail(key: String, reason: String, nowMs: Long, terminal: Boolean = false) {
        outgoing = null
        sent.remove(key)
        updateAll(key) {
            it.copy(
                attempts = it.attempts + 1,
                error = reason,
                terminal = terminal,
                nextTryAtMs = if (terminal) it.nextTryAtMs else nowMs + TRANSIENT_RETRY_MS,
            )
        }
        if (inFlight == key) inFlight = null
    }

    /**
     * Offer everything held back by a transient failure again, now.
     *
     * For a fresh link or a rider pressing Continue: both are new information
     * about the thing that failed, and waiting out a backoff that the new
     * connection already invalidated is time the transfer does not have.
     * Terminal failures and CDN verdicts are untouched.
     */
    fun clearFailures() {
        for (i in plan.indices) {
            val it = plan[i]
            if (it.error != null && !it.terminal) plan[i] = it.copy(error = null, nextTryAtMs = 0L)
        }
    }

    // --- what the CDN says --------------------------------------------------

    /**
     * Records one index read against **every** item for this tile, in every zone.
     *
     * One read, one truth: two zones overlapping the same square must not end up
     * with one of them believing it is sea and the other waiting for a build.
     *
     * An [TilePlan.State.UNKNOWN] answer -- an unreachable CDN -- changes no
     * verdict at all. It is a transient failure and nothing else, because "I
     * could not ask" must never read as "it is not there".
     */
    fun observe(tile: TileRef, reading: TilePlan.Reading, groundIsBuilt: Boolean, nowMs: Long) {
        val entry = TilePlan.Entry.of(tile, reading, groundIsBuilt)
        if (entry.state == TilePlan.State.UNKNOWN) {
            fail(tile.key, "cdn unreachable", nowMs)
            return
        }
        updateAll(tile.key) {
            when (entry.state) {
                TilePlan.State.PRESENT -> it.copy(
                    cdn = TilePlan.State.PRESENT,
                    sizeBytes = entry.sizeBytes,
                    contentId = entry.contentId,
                    nextTryAtMs = 0L,
                    buildChecks = 0,
                    error = null,
                )
                TilePlan.State.WAITING_BUILD -> {
                    val checks = it.buildChecks + 1
                    it.copy(
                        cdn = TilePlan.State.WAITING_BUILD,
                        buildChecks = checks,
                        nextTryAtMs = nowMs + recheckDelayMs(checks),
                        error = null,
                    )
                }
                // Sea. Zero its size so nothing can charge the rider for bytes
                // that will never move.
                TilePlan.State.ABSENT -> it.copy(
                    cdn = TilePlan.State.ABSENT,
                    sizeBytes = 0L,
                    contentId = 0L,
                    nextTryAtMs = 0L,
                    error = null,
                )
                TilePlan.State.UNKNOWN -> it
            }
        }
    }

    /** Backoff after [checks] answers of "not built yet": 5 min, 15 min, then hourly. */
    fun recheckDelayMs(checks: Int): Long =
        RECHECK_MS[(checks - 1).coerceIn(0, RECHECK_MS.size - 1)]

    /**
     * Tiles worth reading the index for now, once each, in plan order.
     *
     * Everything never looked at, plus everything waiting for a build whose
     * backoff has run out and which has not been waiting past [GIVE_UP_MS].
     *
     * **This is a byte-range read of the index, never a blind tile fetch**
     * (`CdnIndexSource.readRange`) -- a few kB against a whole tile, and it is
     * the only thing that can tell "not yet" from "never". Asking for the tile
     * itself is also what primes the server's build queue, which is why a
     * waiting item is fetched once per round and never in a loop
     * (`docs/tile-autobuild.md`).
     */
    fun dueForIndexRead(nowMs: Long): List<TileRef> {
        val seen = HashSet<String>()
        val out = mutableListOf<TileRef>()
        for (it in plan) {
            if (isSent(it.key) || it.terminal) continue
            if (!seen.add(it.key)) continue
            if (nowMs < it.nextTryAtMs) continue
            when (it.cdn) {
                TilePlan.State.UNKNOWN -> out.add(it.tile)
                TilePlan.State.WAITING_BUILD ->
                    if (nowMs - it.queuedAtMs < GIVE_UP_MS) out.add(it.tile)
                else -> Unit
            }
        }
        return out
    }

    // --- what to send next --------------------------------------------------

    /**
     * Everything still owed to the rider, in plan order: not sent, not sea, not
     * given up on.
     *
     * Recomputed rather than cached, same as the wallet's: a cached queue is a
     * second source of truth about the same ledger.
     */
    fun pending(nowMs: Long): List<TileItem> = plan.filter {
        when (stateOf(it, nowMs)) {
            TileState.QUEUED, TileState.SENDING, TileState.RETRY, TileState.WAITING_BUILD -> true
            else -> false
        }
    }

    /**
     * The next tile to push: the first one the CDN is known to have, not sent,
     * not in flight, and past any backoff.
     *
     * A tile the index has not answered for is **not** offered. Its size is
     * unknown, so it cannot be counted; and whether it exists at all is exactly
     * what has not been established. [dueForIndexRead] comes first.
     */
    fun next(nowMs: Long): TileItem? = plan.firstOrNull {
        it.cdn == TilePlan.State.PRESENT &&
            !isSent(it.key) &&
            !it.terminal &&
            it.key != inFlight &&
            nowMs >= it.nextTryAtMs
    }

    fun takeNext(nowMs: Long): TileItem? {
        val item = next(nowMs) ?: return null
        inFlight = item.key
        return item
    }

    fun release() {
        inFlight = null
        outgoing = null
    }

    fun progress(key: String, bytes: Long) {
        sent[key] = bytes
    }

    fun sentBytes(key: String): Long = sent[key] ?: 0L

    // --- states that never lie ----------------------------------------------

    /**
     * Where one item stands.
     *
     * The branch order is the priority of what the rider must not be misled
     * about: a receipt outranks everything, a failure outranks "queued", and
     * "the server has not made it yet" outranks a generic wait because it is the
     * one state that resolves with nobody doing anything.
     *
     * [TileState.EXPIRED] is only ever reached from [TilePlan.State.WAITING_BUILD].
     * An item nothing has managed to look up yet is not given up on at 24 h: it
     * would be giving up because **this phone's** network failed, which says
     * nothing about the ground.
     */
    fun stateOf(item: TileItem, nowMs: Long): TileState = when {
        isSent(item.key) -> TileState.SENT
        inFlight == item.key -> TileState.SENDING
        item.terminal -> TileState.FAILED
        item.cdn == TilePlan.State.ABSENT -> TileState.ABSENT
        item.cdn == TilePlan.State.WAITING_BUILD ->
            if (nowMs - item.queuedAtMs >= GIVE_UP_MS) TileState.EXPIRED else TileState.WAITING_BUILD
        item.error != null && nowMs < item.nextTryAtMs -> TileState.RETRY
        else -> TileState.QUEUED
    }

    /** Whole-outbox numbers for the screen's summary line. */
    fun totals(nowMs: Long): Totals = totalsOf(plan, nowMs)

    /** The same numbers for one zone, so a row can say what that pick still owes. */
    fun zoneTotals(zoneId: String, nowMs: Long): Totals =
        totalsOf(plan.filter { it.zoneId == zoneId }, nowMs)

    /**
     * [items] deduplicated by tile, because two zones round the same city share
     * almost every square and the device only needs each one once. A total that
     * counted them twice would promise twice the transfer that is going to
     * happen.
     */
    private fun totalsOf(items: List<TileItem>, nowMs: Long): Totals {
        val seen = HashSet<String>()
        var sentCount = 0
        var queued = 0
        var waiting = 0
        var unavailable = 0
        var bytesConfirmed = 0L
        var remaining = 0L
        for (it in items) {
            if (!seen.add(it.key)) continue
            when (stateOf(it, nowMs)) {
                TileState.SENT -> {
                    sentCount++
                    // The device's own count, not the index's: what it says it
                    // holds is the only number a receipt actually promises.
                    bytesConfirmed += ledger[it.key]?.bytes ?: 0L
                }
                TileState.WAITING_BUILD -> waiting++
                TileState.ABSENT, TileState.EXPIRED, TileState.FAILED -> unavailable++
                TileState.QUEUED, TileState.SENDING, TileState.RETRY -> {
                    queued++
                    remaining += it.sizeBytes
                }
            }
        }
        return Totals(
            tiles = seen.size,
            sent = sentCount,
            queued = queued,
            waitingBuild = waiting,
            unavailable = unavailable,
            sentBytes = bytesConfirmed,
            remainingBytes = remaining,
            inFlightBytes = inFlight?.let { key ->
                val size = items.firstOrNull { it.key == key }?.sizeBytes ?: return@let 0L
                // Never past the tile's own size: a transport reporting more
                // than it was given would push a bar past 100 percent.
                sentBytes(key).coerceAtMost(size)
            } ?: 0L,
        )
    }

    data class Totals(
        val tiles: Int,
        val sent: Int,
        /** Owed and sendable: queued, in flight, or waiting out a transient failure. */
        val queued: Int,
        val waitingBuild: Int,
        /** Sea, given up on, or terminally failed. Nothing further will be tried. */
        val unavailable: Int,
        val sentBytes: Long,
        /**
         * Bytes still to move, for the squares whose size is known.
         *
         * A tile still waiting for a build has no size yet and contributes
         * nothing -- putting a guess here would show a number the transfer can
         * never reach.
         */
        val remainingBytes: Long,
        val inFlightBytes: Long = 0L,
    ) {
        /** Seconds left at [bytesPerSecond]. Null when there is no measured rate yet. */
        fun etaSeconds(bytesPerSecond: Double): Long? =
            TilePlan.etaSeconds(remainingBytes - inFlightBytes, bytesPerSecond)

        /**
         * How far the whole ask is, 0..1, in tiles rather than bytes.
         *
         * Tiles because a rider counts squares and because the denominator in
         * bytes is not known until every index read has answered. Absent and
         * given-up squares count as done: they are never coming, and a bar that
         * can never fill reads as a stall.
         */
        val fraction: Float
            get() = if (tiles <= 0) 0f else (sent + unavailable).toFloat() / tiles
    }

    // --- plumbing ------------------------------------------------------------

    /**
     * Applies [f] to every item for this tile, in every zone.
     *
     * Always every one: the ledger is keyed by tile, so a record kept per zone
     * that disagreed with its twin would be a second answer to a question that
     * has one.
     */
    private fun updateAll(key: String, f: (TileItem) -> TileItem) {
        for (i in plan.indices) if (plan[i].key == key) plan[i] = f(plan[i])
    }
}
