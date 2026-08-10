package org.explorink.gpsbridge

import android.util.Log

/**
 * Answers the device's `CHECK_TILES`: which of the tiles it already holds have
 * been republished with different content.
 *
 * The device cannot answer this itself and deliberately does not try. Reading
 * the index on the X4 would mean seek/offset arithmetic in firmware and a
 * world-sized index mirrored onto one SD card, for a lookup the phone already
 * has the network to do -- the same division of labour the missing-tile fetch
 * uses (`docs/tile-index-spec.md`, "What actually reads it").
 *
 * The conversation:
 *
 *  1. Device sends `CHECK_TILES <count>` unprompted on the command channel.
 *  2. This asks for the list with `have`, one reply, no paging.
 *  3. The tiles are grouped into one byte range per (z7 block, zoom) and read
 *     out of the CDN's `.idx` files ([TileIndex.planSpans]).
 *  4. Every tile whose published `content_id` differs from the one the device
 *     reported gets a `stale <z> <col> <row>` line.
 *  5. `checked <n>` closes the exchange, or `checked unknown` when the CDN could
 *     not be reached.
 *
 * **`unknown` is not a formality.** Without the network there is no way to tell
 * a current tile from a stale one, and answering "stale" would send a rider's
 * whole viewport over BLE to replace tiles that were already correct. Silence
 * would be worse: the device would sit waiting. So the phone says it does not
 * know, and backs off before trying again ([backoffMs]) so an offline phone is
 * not asked the same unanswerable question every cooldown.
 *
 * **No BLE and no Android in here except the log tag**, same contract as
 * [TileFetcher]: hardware behind [TileFetcher.Transport], time behind
 * [TileFetcher.Scheduler]. Single-threaded, every callback on the caller's one
 * thread.
 */
class FreshnessChecker(
    private val index: IndexSource,
    private val expected: ExpectedContentIds,
    private val transport: TileFetcher.Transport,
    private val scheduler: TileFetcher.Scheduler,
    private val listener: Listener? = null,
) {

    companion object {
        private const val TAG = "FreshnessChecker"

        /** How long the device may take to answer `have` before this gives up. */
        const val REPLY_TIMEOUT_MS = 15_000L

        /**
         * How long an index read may take. Shorter than the reply timeout on
         * purpose: the whole exchange -- ask, listen, read, answer -- has to fit
         * inside the device's own patience, and the HTTP part is the only part
         * that can hang on a dead network rather than a dead link.
         */
        const val INDEX_TIMEOUT_MS = 10_000L

        /** First refusal window after an unreachable CDN. */
        const val BACKOFF_START_MS = 60_000L

        /** Ceiling on the doubling. Long enough to stop mattering, short enough to recover. */
        const val BACKOFF_MAX_MS = 30 * 60_000L

        /** Verdict word when the CDN could not be reached. Never a tile list. */
        const val ANSWER_UNKNOWN = "unknown"
    }

    interface Listener {
        /** A check ended. [stale] is -1 when the answer was `unknown`. */
        fun onCheckFinished(examined: Int, stale: Int, reason: String)

        /**
         * These tiles are out of date, and this phone knows which content id
         * each should be at. **Push them; do not wait to be asked.**
         *
         * The device could ask -- it has the list, it just received it -- but
         * that would be the device relaying a list back to the side that wrote
         * it, and the expected content ids would have to survive the round trip
         * to be any use. The transfer channel already accepts an unsolicited
         * push while the map or the sync screen is up
         * (`docs/ble-map-transfer-protocol.md`).
         */
        fun onStaleTilesFound(tiles: List<HeldTile>)
    }

    enum class Phase { IDLE, LISTING, READING }

    var phase: Phase = Phase.IDLE
        private set

    private var reader: MissingList.HaveReader? = null
    private var timeout: TileFetcher.Scheduler.Cancellable? = null

    /** Spans still to read, and what has been decided from the ones already read. */
    private val pending = ArrayDeque<TileIndex.Span>()
    private val staleTiles = mutableListOf<HeldTile>()
    private var examined = 0

    /**
     * True once any span of this check came back unreachable. The check still
     * reports the stale tiles it did establish -- those are certain -- but the
     * verdict word becomes `unknown`, because the answer is not complete.
     */
    private var incomplete = false

    /**
     * The `.tib` format version the device reads, stated on the most recent
     * `CHECK_TILES` or `NEED_TILES`, whichever was last. The index lives under
     * the same `/v<N>/` prefix as the tiles it describes, so a device on an
     * older format must be answered from its own tree or every slot would look
     * wrong at once.
     *
     * `CHECK_TILES` states its own now -- it used to rely entirely on
     * `NEED_TILES` having arrived first, which never happens for a device with
     * nothing missing, so this defaulted to [CdnTileSource.DEFAULT_FORMAT_VERSION]
     * (a stale constant) for exactly the devices this check exists to keep
     * current.
     */
    private var formatVersion: Int? = null

    /** Earliest [nowMs] at which a check may start again after an unreachable CDN. */
    private var blockedUntilMs = 0L
    private var backoffMs = BACKOFF_START_MS

    /** Monotonic milliseconds, injected so the backoff is testable. */
    var nowMs: () -> Long = { System.nanoTime() / 1_000_000L }

    // --- input from the link ------------------------------------------------

    /** One line off the command characteristic (`...0003`). */
    fun onCommandLine(line: String) {
        MissingList.parseNeedTiles(line)?.let { formatVersion = it.formatVersion ?: formatVersion }

        val checkTiles = MissingList.parseCheckTiles(line)
        if (checkTiles != null) {
            formatVersion = checkTiles.formatVersion ?: formatVersion
            start(checkTiles.count)
            return
        }
        if (MissingList.isFetchCancel(line)) {
            if (phase != Phase.IDLE) finish("cancelled on the device")
            return
        }
        if (phase == Phase.LISTING) feed(line)
    }

    fun onDisconnected() {
        if (phase == Phase.IDLE) return
        finish("link lost")
    }

    fun stop() {
        if (phase == Phase.IDLE) return
        finish("stopped")
    }

    // --- listing ------------------------------------------------------------

    private fun start(count: Int) {
        if (phase != Phase.IDLE) {
            Log.i(TAG, "restarting on a second CHECK_TILES")
            reset()
        }
        if (count <= 0) {
            // Nothing to check is a real answer and a cheap one. Saying it
            // rather than staying quiet is what lets the device close its own
            // pending flag instead of waiting out a timeout.
            answerChecked(0)
            listener?.onCheckFinished(0, 0, "nothing to check")
            return
        }
        val wait = blockedUntilMs - nowMs()
        if (wait > 0) {
            // Answered, not extended. The device asking again inside the window
            // is not a fresh failure, and letting it push the window out would
            // let a chatty device back the phone off indefinitely.
            Log.i(TAG, "backing off ${wait}ms more before another check")
            answerUnknown()
            listener?.onCheckFinished(0, -1, "backing off")
            return
        }

        Log.i(TAG, "device wants $count tile(s) checked, format ${formatVersion ?: "unstated"}")
        phase = Phase.LISTING
        reader = MissingList.HaveReader()
        armTimeout(REPLY_TIMEOUT_MS)
        transport.sendCommand("have") { ok, error ->
            if (!ok) finish("could not ask for the list: ${error ?: "write failed"}")
        }
    }

    private fun feed(line: String) {
        val r = reader ?: return
        if (!r.feed(line)) return
        if (r.unavailable) {
            finish("device has no viewport yet")
            return
        }
        if (!r.complete) return

        cancelTimeout()
        val tiles = r.tiles
        examined = tiles.size
        if (tiles.isEmpty()) {
            answerChecked(0)
            finish("nothing to check")
            return
        }

        val spans = TileIndex.planSpans(tiles)
        val dropped = tiles.size - spans.sumOf { it.tiles.size }
        if (dropped > 0) {
            // A zoom with no plane in this index layout. Saying so beats an
            // unexplained shortfall between what was asked and what was answered.
            Log.w(TAG, "$dropped tile(s) are at a zoom this index layout does not cover")
        }
        Log.i(TAG, "checking ${tiles.size} tile(s) in ${spans.size} range read(s)")
        phase = Phase.READING
        pending.clear()
        pending.addAll(spans)
        readNextSpan()
    }

    // --- reading the index --------------------------------------------------

    private fun readNextSpan() {
        val span = pending.removeFirstOrNull()
        if (span == null) {
            report()
            return
        }
        armTimeout(INDEX_TIMEOUT_MS)
        index.readRange(span.relPath(), span.first, span.last, formatVersion) { result ->
            // A late read from a check that has already ended must not answer for
            // the one that replaced it.
            if (phase != Phase.READING) {
                Log.i(TAG, "dropping a late index read; the check has ended")
                return@readRange
            }
            cancelTimeout()
            onSpan(span, result)
        }
    }

    private fun onSpan(span: TileIndex.Span, result: IndexSource.Result) {
        when (result) {
            is IndexSource.Result.Bytes -> {
                for (tile in span.tiles) {
                    val slot = TileIndex.parseSlot(result.data, span.offsetWithin(tile))
                    if (slot == null) {
                        incomplete = true
                        continue
                    }
                    // Not present means the CDN publishes nothing here. The
                    // device holds a tile nobody vouches for -- possibly one
                    // pruned since it synced. There is nothing to fetch, so
                    // there is nothing to say.
                    if (!slot.present) continue
                    expected.put(tile.z, tile.col, tile.row, slot.contentId)
                    if (slot.contentId != tile.contentId) staleTiles.add(tile)
                }
            }

            // Nothing is published for this ground. Certain, and not stale.
            is IndexSource.Result.NotPublished ->
                Log.i(TAG, "no index block for ${span.relPath()}")

            is IndexSource.Result.Unreachable -> {
                Log.w(TAG, "could not read ${span.relPath()}: ${result.why}")
                incomplete = true
            }
        }
        readNextSpan()
    }

    // --- answering ----------------------------------------------------------

    private fun report() {
        for (tile in staleTiles) {
            transport.sendCommand("stale ${tile.z} ${tile.col} ${tile.row}") { ok, error ->
                if (!ok) Log.w(TAG, "stale write failed: $error")
            }
        }
        if (staleTiles.isNotEmpty()) listener?.onStaleTilesFound(staleTiles.toList())
        if (incomplete) {
            sendUnknown()
            finish("index unreachable, ${staleTiles.size} stale found anyway")
            return
        }
        // A complete answer clears the backoff, whatever it had grown to.
        backoffMs = BACKOFF_START_MS
        blockedUntilMs = 0
        val n = staleTiles.size
        answerChecked(n)
        finish("$n stale of $examined")
    }

    private fun answerChecked(n: Int) {
        transport.sendCommand("checked $n") { ok, error ->
            if (!ok) Log.w(TAG, "checked write failed: $error")
        }
    }

    /** "I do not know." Never a tile list, and never silence. */
    private fun answerUnknown() {
        transport.sendCommand("checked $ANSWER_UNKNOWN") { ok, error ->
            if (!ok) Log.w(TAG, "checked write failed: $error")
        }
    }

    /**
     * "I do not know", plus a longer wait before trying the network again.
     *
     * The doubling matters more than it looks: a rider out of coverage for an
     * afternoon would otherwise have the phone attempt this every cooldown, each
     * time waking the radio, spending the timeout and answering nothing. Only a
     * real failed attempt extends it -- see [start].
     */
    private fun sendUnknown() {
        answerUnknown()
        blockedUntilMs = nowMs() + backoffMs
        backoffMs = minOf(backoffMs * 2, BACKOFF_MAX_MS)
    }

    // --- timeouts and teardown ---------------------------------------------

    private fun armTimeout(ms: Long) {
        cancelTimeout()
        timeout = scheduler.postDelayed(ms) {
            timeout = null
            Log.w(TAG, "timed out in $phase")
            when (phase) {
                Phase.LISTING -> finish("device stopped answering")
                // A hung HTTP read is exactly the offline case, so it takes the
                // same honest exit as one: report what is certain, say unknown,
                // back off.
                Phase.READING -> {
                    incomplete = true
                    pending.clear()
                    report()
                }

                Phase.IDLE -> Unit
            }
        }
    }

    private fun cancelTimeout() {
        timeout?.cancel()
        timeout = null
    }

    private fun finish(reason: String) {
        val wasExamined = examined
        val wasStale = if (incomplete) -1 else staleTiles.size
        reset()
        Log.i(TAG, "check finished: $reason")
        listener?.onCheckFinished(wasExamined, wasStale, reason)
    }

    private fun reset() {
        cancelTimeout()
        phase = Phase.IDLE
        reader = null
        pending.clear()
        staleTiles.clear()
        examined = 0
        incomplete = false
    }
}

/**
 * The content id the index says a tile should have, remembered between the
 * check that read it and the fetch that acts on it.
 *
 * Small and lossy on purpose. It exists so a stale tile is fetched as
 * `?crc=<content_id>` -- the CDN caches a tile path for seven days with no purge
 * mechanism, so without a per-version cache key the fetch would be served the
 * very copy it is replacing and the device would ask again forever
 * (`docs/tile-cdn-plan.md`, "Caching").
 *
 * An entry that has fallen out is not a bug: the fetch then goes without the
 * query, which is what every fetch did before this existed.
 */
class ExpectedContentIds(private val capacity: Int = 256) {

    private val map = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > capacity
    }

    private fun key(z: Int, col: Long, row: Long) = "$z/$col/$row"

    fun put(z: Int, col: Long, row: Long, contentId: Long) {
        map[key(z, col, row)] = contentId
    }

    fun get(z: Int, col: Long, row: Long): Long? = map[key(z, col, row)]

    fun clear() = map.clear()

    val size: Int get() = map.size
}
