package org.explorink.gpsbridge

import android.util.Log

/**
 * Drives one pre-trip batch end to end: ask the device what it is, read the
 * CDN's index for everything due, announce the batch, push it, record every
 * receipt, and write the queue to disk as it goes.
 *
 * The queue itself is [TileOutbox] and it is pure. This is the part that has to
 * talk -- to the device, to the CDN, to the disk -- so everything that does is
 * behind an interface, exactly the way [TileFetcher] does it. That is not
 * decoration: a city is 20 to 40 minutes over BLE across as many connections as
 * it takes, which is a sequence nobody can exercise by hand, and the iOS rule
 * (`CLAUDE.md`, "The phone app must stay portable to iOS") asks for the
 * arithmetic to be liftable without the I/O.
 *
 * **Single-threaded by contract.** Every method and every callback runs on the
 * caller's one thread -- the service's main looper in the app, the test thread
 * in tests. No locks, and none needed.
 *
 * ## The sequence
 *
 * 1. **`info` first, always.** `INFO screen=sync` is required. Absent means
 *    *cannot say*, not *map* ([DeviceInfo.Screen]), so an older firmware is
 *    refused rather than started on a batch it cannot finish. The same reply
 *    carries `INFO tile_fmt=<n>`, and it is the only source of that number for a
 *    pre-trip push: a device with nothing missing never sends `NEED_TILES` or
 *    `CHECK_TILES`, and pushing the wrong format wastes the whole transfer.
 * 2. **`mapset.json` once per round** ([MapsetSource]) -- the published area
 *    list, which is the whole of "not yet" against "never".
 * 3. **One index scan** ([IndexScanner]) over [TileOutbox.dueForIndexRead], each
 *    answer fed straight into [TileOutbox.observe].
 * 4. **`push <n>`** with the number of tiles actually about to go.
 * 5. **[TileFetcher.pushTiles]** with those tiles, in queue order.
 *
 * ## Two things that are easy to get wrong
 *
 * **The command channel takes one conversation at a time.** `info` and
 * `push <n>` both end on a plain `OK`, exactly like `missing`, `have` and the
 * pin commands, so a second conversation open at the same time can be ended by
 * the other's terminator -- measured on hardware 2026-08-11, and it left the
 * device showing 20 rows of "waiting" with nothing pushed
 * ([BridgeService.onCommandLine]). This class never opens its own conversation;
 * it asks its owner first, through [Gate], and its owner refuses everything else
 * while [busy] is true.
 *
 * **`push <n>` goes once per batch, and a reconnect is a new batch.** A dropped
 * link puts the device's sync screen back to Waiting and then to Finished, so a
 * sender that reconnects and simply resumes pushing lands its bytes dark. The
 * wire contract states it as "once per connection, before the first `begin`
 * frame"; announcing per batch satisfies that and is what the reason behind it
 * actually demands, because a second batch inside one connection can only start
 * once the first has finished -- which puts that screen in the same Finished
 * state a reconnect does. Either way the number announced is what is **still**
 * to come, never what the round originally wanted.
 */
class TileOutboxController(
    private val outbox: TileOutbox,
    private val transport: Transport,
    private val mapsetSource: MapsetSource,
    private val indexSource: IndexSource,
    private val pusher: Pusher,
    private val store: Store,
    private val scheduler: TileFetcher.Scheduler,
    private val gate: Gate,
    private val listener: Listener? = null,
    /** Wall clock in epoch milliseconds. Injected so the backoff is testable. */
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    companion object {
        private const val TAG = "TileOutbox"

        /**
         * How long the device may take to answer `info` or `push`.
         *
         * The same budget [PinManager] and [FreshnessChecker] give their one-shot
         * commands, for the same reason: a reply is one BLE indication per line
         * waiting for the peer's ATT confirm, and `info` is over twenty lines
         * behind whatever the panel is doing.
         */
        const val REPLY_TIMEOUT_MS = 15_000L

        /** `ble` today. Wi-Fi later, behind the same seam (`docs/send-tiles-plan.md`). */
        const val TRANSPORT_NAME = "ble"

        /**
         * How long after a link comes up before the queue drains itself.
         *
         * Not a nicety. The device fires its own `NEED_TILES` / `CHECK_TILES`
         * on resubscribe, and those describe what the rider is looking at right
         * now; a round that grabbed the channel in the same millisecond would
         * push them into the deferral queue behind a scan. Long enough for the
         * device's asks to land and be answered, short enough that a rider who
         * opened the sync screen on purpose does not sit watching nothing.
         */
        const val CONNECT_SETTLE_MS = 3_000L
    }

    /** The one thing this needs from the link: a line on the command channel. */
    interface Transport {
        fun sendCommand(line: String, done: (Boolean, String?) -> Unit)
    }

    /**
     * Pushing tiles nobody asked for. [TileFetcher.pushTiles] behind a name, so
     * the whole controller runs without one.
     *
     * Not a second pusher and never to become one: the fetcher already handles
     * the begin/RDY/chunk/OK dance, the read-ahead that keeps the radio busy
     * across a CDN fetch, and every late-callback guard that dance needs.
     */
    interface Pusher {
        /** True when nothing is in flight, so a batch may start. */
        val idle: Boolean

        fun pushTiles(tiles: List<MissingTile>, formatVersion: Int?)
    }

    /** Persisting the queue. [OutboxStore] behind a name, so tests keep no files. */
    interface Store {
        fun save(outbox: TileOutbox)
    }

    /**
     * Whether the shared command channel is free for this class right now.
     *
     * The gate is asked, not owned, because the other three conversations
     * (`tileFetcher`, `freshness`, `pins`) are the service's and it is the
     * service that already arbitrates between them. A fourth ad-hoc flag inside
     * this class would be a fourth answer to a question that must have one --
     * see T-116, which exists to give the channel a single owner.
     */
    interface Gate {
        /**
         * Null when a conversation may start, or a short reason in words why not.
         * Read as a rider-facing sentence: it goes straight onto the screen.
         */
        fun blocker(): String?
    }

    interface Listener {
        /** Anything the screen would repaint for. Fired on every state change. */
        fun onOutboxChanged() {}

        /** A round ended, one way or another. [reason] is short and human-readable. */
        fun onRoundFinished(reason: String) {}
    }

    enum class Phase {
        IDLE,

        /** `info` is out; the reply decides whether anything else happens. */
        ASKING_INFO,

        /** Reading `mapset.json`. No BLE involved, but the round owns the channel. */
        READING_MAPSET,

        /** Byte-range reads of the CDN index for everything due. */
        SCANNING,

        /** `push <n>` is out. */
        ANNOUNCING,

        /** [Pusher] has the batch. */
        PUSHING,
    }

    var phase: Phase = Phase.IDLE
        private set

    /**
     * True while a round holds the command channel, in either direction.
     *
     * The owner reads this to defer the device's own asks, and this class reads
     * the owner's [Gate] before starting -- both halves, or the collision this
     * whole arrangement exists to prevent comes back on the other side.
     */
    val busy: Boolean get() = phase != Phase.IDLE

    /** What the device last said it was, or null when nothing has answered on this link. */
    var device: DeviceInfo? = null
        private set

    /** The last thing that happened, in words for the screen. Null before anything has. */
    var status: String? = null
        private set

    /**
     * Why a round cannot start, or null when one could.
     *
     * Sticky across rounds on purpose: "the device is not on its Sync map tiles
     * screen" is the common refusal and the rider has to be told it *after* the
     * attempt, not only during it.
     */
    var blocker: String? = null
        private set

    /**
     * The rider pressed Pause. No round starts until [resume], including the
     * automatic one on a fresh connection.
     */
    var paused: Boolean = false
        private set

    /** True while a connection is up, as the owner reports it. */
    private var linked = false

    private var reader: DeviceInfo.Reader? = null
    private var timeout: TileFetcher.Scheduler.Cancellable? = null

    /**
     * Bumped at every point a round starts or ends. Async work captures it and
     * checks it before touching anything -- the same guard [FreshnessChecker],
     * [PinManager] and [TileFetcher] all carry, and for the same reason: an HTTP
     * read is 10-20 s of timeouts, wide enough for the round it belongs to to
     * end and a new one to start before it lands.
     */
    private var gen = 0

    /** The scan of a running round. Separate from [planScanner] -- see [plan]. */
    private val drainScanner = IndexScanner(indexSource)

    /** Ground the CDN says it has built, read once per round. */
    private var ground = TilePlan.BuiltGround()

    /** The tiles the current batch handed to the pusher, by key, for the receipts. */
    private var batch: List<TileRef> = emptyList()

    private val rate = Rate()

    // --- what the owner tells this class -------------------------------------

    /**
     * A link came up.
     *
     * Everything held back by a transient failure is offered again: a fresh link
     * is new information about the thing that failed, and waiting out a backoff
     * the new connection already invalidated is time the transfer does not have.
     * Then the queue drains **without the rider re-picking anything** -- that is
     * the whole point of persisting it.
     */
    fun onConnected() {
        linked = true
        device = null
        outbox.clearFailures()
        // After the settle, and re-checked then: the link can be gone again by
        // the time this runs, and `drain` refuses on that anyway.
        connectSettle?.cancel()
        connectSettle = scheduler.postDelayed(CONNECT_SETTLE_MS) {
            connectSettle = null
            drain()
        }
    }

    private var connectSettle: TileFetcher.Scheduler.Cancellable? = null

    /** The link dropped. Whatever a round was doing is dead. */
    fun onDisconnected() {
        linked = false
        connectSettle?.cancel()
        connectSettle = null
        device = null
        // The tile in flight sent no receipt, so it is pending again by
        // construction -- that is the receipt law, not a special case.
        outbox.release()
        if (busy) end("the link dropped")
    }

    /** Teardown. Silent: not an error, and nothing is left to tell. */
    fun stop() {
        connectSettle?.cancel()
        connectSettle = null
        cancelTimeout()
        drainScanner.cancel()
        planScanner.cancel()
        gen++
        phase = Phase.IDLE
        reader = null
    }

    // --- what the rider asks for ---------------------------------------------

    /**
     * Adds a zone and everything it covers. Returns the zone's id.
     *
     * The tiles are computed here rather than by the caller so the ask and its
     * plan cannot disagree: `label`, `sideKm` and the item list all describe one
     * decision, and a caller that passed its own list could store a box that is
     * not the box the label names.
     */
    fun queueZone(latE7: Int, lonE7: Int, sideKm: Int, label: String): String {
        val at = now()
        val zoneId = "zone-$at-${outbox.zones.size}"
        val zone = TileZone(zoneId, label, latE7, lonE7, sideKm, at)
        outbox.addZone(zone, TileBox.tilesFor(latE7 / 1e7, lonE7 / 1e7, sideKm.toDouble()))
        save()
        status = "queued $label"
        listener?.onOutboxChanged()
        drain()
        return zoneId
    }

    /** Drops a zone and its items. Receipts are kept -- see [TileOutbox.removeZone]. */
    fun dropZone(zoneId: String) {
        val label = outbox.zone(zoneId)?.label
        outbox.removeZone(zoneId)
        save()
        status = if (label != null) "dropped $label" else "dropped a zone"
        listener?.onOutboxChanged()
    }

    /**
     * Drops every zone that has nothing left to send.
     *
     * "Clear what is sent" on the screen. Receipts survive it: a receipt is a
     * fact about what is on the device's card, and forgetting it would make the
     * next overlapping zone push bytes the device already holds -- 258 kB a
     * square in Barcelona.
     */
    fun dropFinishedZones(): Int {
        val at = now()
        val done = outbox.zones.filter { z ->
            val t = outbox.zoneTotals(z.zoneId, at)
            t.queued == 0 && t.waitingBuild == 0
        }
        for (z in done) outbox.removeZone(z.zoneId)
        if (done.isNotEmpty()) {
            save()
            status = "cleared ${done.size} finished ${if (done.size == 1) "zone" else "zones"}"
            listener?.onOutboxChanged()
        }
        return done.size
    }

    /**
     * The rider pressed Pause.
     *
     * A batch already on the wire is **not** aborted: the tile in flight is
     * seconds to two minutes of the rider's data already spent, and throwing it
     * away to honour a button is a worse answer than finishing it. Nothing new
     * starts after it.
     */
    fun pause() {
        paused = true
        status = "paused"
        listener?.onOutboxChanged()
    }

    /** Continue. Also clears transient failures: pressing it is new information. */
    fun resume(): String? {
        paused = false
        outbox.clearFailures()
        return drain()
    }

    /**
     * Starts a round now, or says why it cannot. Null means one started (or one
     * was already running, or there was nothing to do -- see [status]).
     */
    fun startDraining(): String? {
        paused = false
        return drain()
    }

    // --- a round --------------------------------------------------------------

    private fun drain(): String? {
        if (busy) return null
        if (paused) return "paused"
        if (!linked) {
            blocker = "not connected to the device"
            return blocker
        }
        gate.blocker()?.let {
            // Not an error and not sticky: the channel is busy with somebody
            // else's conversation and the owner calls back when it frees.
            Log.i(TAG, "round deferred: $it")
            return it
        }
        if (!pusher.idle) return "map squares are already transferring"
        val at = now()
        if (outbox.dueForIndexRead(at).isEmpty() && outbox.next(at) == null) {
            // Nothing to look up and nothing to push. Not a refusal: a queue of
            // tiles all waiting out a build backoff is a healthy queue, and
            // saying "cannot start" about it would be a lie.
            blocker = null
            return null
        }
        blocker = null
        askInfo()
        return null
    }

    private fun askInfo() {
        gen++
        phase = Phase.ASKING_INFO
        val r = DeviceInfo.Reader()
        reader = r
        status = "asking the device what screen it is on"
        listener?.onOutboxChanged()
        send(DeviceInfo.COMMAND) { onInfoComplete(r) }
    }

    private fun onInfoComplete(r: DeviceInfo.Reader) {
        val info = r.info()
        device = info
        when (info.screen) {
            DeviceInfo.Screen.SYNC -> Unit

            DeviceInfo.Screen.MAP -> {
                // Not a warning to push through. The map screen's post-arrival
                // redraw fires on a settle timer with no check on whether bytes
                // are moving, and the link does not recover -- measured
                // 2026-08-14 for any tile over ~45 kB, and every city tile is
                // over 45 kB (`docs/ble-map-transfer-protocol.md`).
                refuse("the device is on its map screen -- open Sync map tiles on it")
                return
            }

            DeviceInfo.Screen.UNSTATED -> {
                // Absent is "cannot say", never "map". Refusing here is refusing
                // to start a half-hour batch on a build that has no way to show
                // it is running, and no way to be checked.
                refuse("this firmware does not say which screen it is on -- update it")
                return
            }

            DeviceInfo.Screen.OTHER -> {
                refuse("the device is on a screen this app does not know (${info.values["screen"]})")
                return
            }
        }
        if (info.tileFormat == null) {
            // The one number that cannot be guessed. `CdnTileSource`'s
            // compiled-in default is one version behind by design, and a tile
            // built to another version transfers, passes CRC, is renamed into
            // place and is then refused on open -- a whole batch spent for
            // nothing.
            refuse("the device did not say which tile format it reads")
            return
        }
        readMapset(info.tileFormat)
    }

    private fun readMapset(formatVersion: Int) {
        phase = Phase.READING_MAPSET
        status = "reading what the map server has built"
        listener?.onOutboxChanged()
        val mine = gen
        mapsetSource.read(formatVersion) { result ->
            if (mine != gen) return@read
            when (result) {
                is MapsetSource.Result.Areas -> {
                    ground = TilePlan.BuiltGround(result.areas)
                    scan(formatVersion)
                }

                // A real verdict: nothing is built under this format version at
                // all, so nothing can be ABSENT and every empty slot is ground
                // waiting for a build. An empty area list says exactly that.
                is MapsetSource.Result.NothingPublished -> {
                    ground = TilePlan.BuiltGround()
                    scan(formatVersion)
                }

                is MapsetSource.Result.Unreachable -> {
                    // **Not** an empty area list. Feeding one in would mark a
                    // whole city ABSENT on the strength of one flight-mode
                    // toggle, and the rider would never get those tiles.
                    // The scan is skipped and the round pushes whatever the
                    // index already established -- which needs no network.
                    Log.w(TAG, "mapset unreachable (${result.why}); pushing what is already known")
                    status = "could not reach the map server (${result.why})"
                    announceAndPush()
                }
            }
        }
    }

    private fun scan(formatVersion: Int) {
        val due = outbox.dueForIndexRead(now())
        if (due.isEmpty()) {
            announceAndPush()
            return
        }
        phase = Phase.SCANNING
        status = "checking ${due.size} ${squares(due.size)} against the map server"
        listener?.onOutboxChanged()
        val mine = gen
        drainScanner.start(due, formatVersion, object : IndexScanner.Listener {
            override fun onTilesRead(reads: List<IndexScanner.Read>) {
                if (mine != gen) return
                val at = now()
                for (r in reads) outbox.observe(r.tile, r.reading, ground.covers(r.tile), at)
            }

            override fun onScanProgress(read: Int, total: Int) {
                if (mine != gen) return
                status = "checked $read of $total ${squares(total)}"
                listener?.onOutboxChanged()
            }

            override fun onScanFinished(summary: IndexScanner.Summary) {
                if (mine != gen) return
                val at = now()
                // No index plane for that zoom. There is no honest Reading for
                // it -- NoBlock would say "wait, it is coming" and Unreachable
                // would say "ask again", and both retry forever -- so it is
                // terminal here (`IndexScanner.Summary.unindexed`).
                for (t in summary.unindexed) {
                    outbox.fail(t.key, "no index for zoom ${t.z}", at, terminal = true)
                }
                save()
                announceAndPush()
            }
        })
    }

    /**
     * Announces the batch and hands it to the pusher.
     *
     * The count is what is **still** to come, computed here and not carried from
     * the start of the round: a scan can have moved tiles into ABSENT since, and
     * a device told to expect more squares than arrive finishes its run on a
     * number that never completes.
     */
    private fun announceAndPush() {
        val at = now()
        batch = pendingTiles(at)
        if (batch.isEmpty()) {
            end(if (outbox.totals(at).waitingBuild > 0) "waiting for the map server to build" else "nothing to send")
            return
        }
        phase = Phase.ANNOUNCING
        val n = batch.size.coerceAtMost(DeviceInfo.MAX_PUSH_COUNT)
        status = "telling the device to expect $n ${squares(n)}"
        listener?.onOutboxChanged()
        val r = DeviceInfo.Reader()
        reader = r
        send(DeviceInfo.pushCommand(n)) {
            if (r.pushUnavailable) {
                // The screen has no push observer. That is what the map screen
                // answers, and `info` already refused that case -- so reaching
                // here means the device changed screens between the two
                // commands. Refusing is the same call for the same reason.
                refuse("the device left its Sync map tiles screen")
                return@send
            }
            push()
        }
    }

    private fun push() {
        phase = Phase.PUSHING
        rate.startBatch(now())
        status = "sending ${batch.size} ${squares(batch.size)}"
        listener?.onOutboxChanged()
        // count 0: a rider-chosen tile was never on any device list, so there is
        // no hit count to carry and inventing one would put a number in
        // [MissingTile] that means nothing.
        pusher.pushTiles(batch.map { MissingTile(it.z, it.col, it.row, 0) }, device?.tileFormat)
    }

    /** Distinct keys the CDN is known to have, not sent, not given up on, in queue order. */
    private fun pendingTiles(at: Long): List<TileRef> {
        val seen = HashSet<String>()
        val out = ArrayList<TileRef>()
        for (item in outbox.items) {
            if (item.cdn != TilePlan.State.PRESENT) continue
            if (outbox.isSent(item.key) || item.terminal) continue
            if (at < item.nextTryAtMs) continue
            if (!seen.add(item.key)) continue
            out.add(item.tile)
        }
        return out
    }

    // --- what the pusher reports back ------------------------------------------

    /**
     * The begin frame for one square is going out.
     *
     * Both numbers are recorded before anything can come back, because that is
     * what makes the device's verdict checkable at all: only the sender knows
     * the CRC of what went out, and the index's `content_id` is a hash of layer
     * CRCs rather than of the file, so the queue cannot derive it
     * ([TileFetcher.Listener.onTileSending]).
     */
    fun onTileSending(z: Int, col: Long, row: Long, bytes: Int, crc32: Long) {
        val key = TileRef(z, col, row).key
        if (!mine(key)) return
        outbox.beginSend(key, bytes.toLong(), crc32)
        listener?.onOutboxChanged()
    }

    /** Bytes acknowledged for the square in flight. Not persisted -- see the format doc. */
    fun onTileProgress(z: Int, col: Long, row: Long, sentBytes: Int, totalBytes: Int) {
        val key = TileRef(z, col, row).key
        if (!mine(key)) return
        outbox.progress(key, sentBytes.toLong())
        listener?.onOutboxChanged()
    }

    /** The device's `OK <bytes> <crc32hex>`, checked against what went out. */
    fun onTileReceipt(z: Int, col: Long, row: Long, bytes: Int, crc32: Long) {
        val key = TileRef(z, col, row).key
        if (!mine(key)) return
        val at = now()
        if (outbox.confirm(key, bytes.toLong(), crc32, TRANSPORT_NAME, at)) {
            rate.confirmed(bytes.toLong(), at)
        } else {
            // Recorded by confirm() as a transient failure already. Worth a log
            // line of its own: the device reads the CRC back off the card, so a
            // disagreement means the file there is not the file that went out.
            Log.w(TAG, "receipt for $key does not match what was sent")
            status = "a square arrived damaged and will be sent again"
        }
        save()
        listener?.onOutboxChanged()
    }

    /**
     * A square the pusher gave up on. Only the failures reach here -- a success
     * is [onTileReceipt], which is the only thing that makes a tile sent.
     */
    fun onTileSkipped(z: Int, col: Long, row: Long, detail: String) {
        val key = TileRef(z, col, row).key
        if (!mine(key)) return
        // A wrong format is the one failure waiting cannot fix: every retry
        // transfers, passes CRC, is renamed into place and is then refused on
        // open, so retrying spends the rider's data on a certainty.
        val terminal = detail == TileFetcher.SKIP_WRONG_FORMAT
        outbox.fail(key, detail, now(), terminal)
        save()
        listener?.onOutboxChanged()
    }

    /** The pusher finished the batch, however it ended. */
    fun onPushFinished(reason: String) {
        if (phase != Phase.PUSHING) return
        outbox.release()
        save()
        end(reason)
    }

    private fun mine(key: String): Boolean = batch.any { it.key == key }

    // --- planning, which needs no device ---------------------------------------

    /**
     * A second scanner, for the plan step.
     *
     * Separate from [drainScanner] and not shared: [IndexScanner.start] replaces
     * whatever was running, so a rider planning a new zone while a batch's scan
     * is out would silently cancel it and the round would end with tiles it
     * never looked up.
     */
    private val planScanner = IndexScanner(indexSource)

    /** What a zone would cost, before the rider commits to it. */
    class Plan(
        val entries: List<TilePlan.Entry>,
        val summary: TilePlan.Summary,
        /** False when the CDN could not answer for every tile -- see [TilePlan.Summary.unknown]. */
        val complete: Boolean,
        /** Null when it could not be read; the plan is then a floor, not the answer. */
        val problem: String? = null,
    )

    /**
     * Reads the index for a box and says exactly what it costs: how many tiles,
     * how many megabytes, how many are not available.
     *
     * **Deliberately independent of the device.** A rider plans at home, and the
     * device may be off, out of range, or on the wrong screen -- none of which
     * changes what the CDN holds. So this takes no `info`, holds no channel and
     * needs no link. It costs a handful of byte-range reads of a few kB, never a
     * tile fetch, which is what lets the screen state the exact byte count
     * before the rider commits to half an hour of radio.
     *
     * [formatVersion] should be the device's own when it is known, because the
     * index lives under the same `/v<N>/` prefix as the tiles it describes.
     * Null falls back to [CdnTileSource.DEFAULT_FORMAT_VERSION], and the plan is
     * then about that tree.
     */
    fun plan(
        latDeg: Double,
        lonDeg: Double,
        sideKm: Double,
        formatVersion: Int?,
        done: (Plan) -> Unit,
    ) {
        val tiles = TileBox.tilesFor(latDeg, lonDeg, sideKm)
        if (tiles.isEmpty()) {
            done(Plan(emptyList(), TilePlan.summarize(emptyList()), complete = true))
            return
        }
        mapsetSource.read(formatVersion) { mapset ->
            val areas = when (mapset) {
                is MapsetSource.Result.Areas -> mapset.areas
                is MapsetSource.Result.NothingPublished -> emptyList()
                // The same refusal to guess the queue makes: with no area list
                // nothing can be called ABSENT, so an empty slot reads as
                // "waiting for a build" and the plan says so.
                is MapsetSource.Result.Unreachable -> null
            }
            val built = TilePlan.BuiltGround(areas ?: emptyList())
            val entries = ArrayList<TilePlan.Entry>(tiles.size)
            planScanner.start(tiles, formatVersion, object : IndexScanner.Listener {
                override fun onTilesRead(reads: List<IndexScanner.Read>) {
                    for (r in reads) entries.add(TilePlan.Entry.of(r.tile, r.reading, built.covers(r.tile)))
                }

                override fun onScanFinished(summary: IndexScanner.Summary) {
                    for (t in summary.unindexed) {
                        entries.add(TilePlan.Entry(t, TilePlan.State.ABSENT))
                    }
                    done(
                        Plan(
                            entries = entries,
                            summary = TilePlan.summarize(entries),
                            complete = summary.complete && areas != null,
                            problem = when {
                                areas == null -> "the map server's area list could not be read"
                                summary.cancelled -> "the check was stopped"
                                summary.unreachable > 0 ->
                                    "${summary.unreachable} ${squares(summary.unreachable)} could not be checked"
                                else -> null
                            },
                        )
                    )
                }
            })
        }
    }

    fun cancelPlan() {
        planScanner.cancel()
    }

    // --- numbers for the screen -------------------------------------------------

    fun totals(): TileOutbox.Totals = outbox.totals(now())

    fun zoneTotals(zoneId: String): TileOutbox.Totals = outbox.zoneTotals(zoneId, now())

    val zones: List<TileZone> get() = outbox.zones

    /** The square on the wire right now, or null. */
    fun inFlight(): InFlight? {
        val key = outbox.inFlight ?: return null
        val item = outbox.items.firstOrNull { it.key == key } ?: return null
        return InFlight(item.tile, outbox.sentBytes(key), item.sizeBytes)
    }

    class InFlight(val tile: TileRef, val sentBytes: Long, val totalBytes: Long)

    /**
     * Bytes per second of the batches this run, or null before one has confirmed
     * a square.
     *
     * **Measured, never a constant.** [TilePlan.START_BYTES_PER_SECOND] is what
     * to say before there has been a batch, and it is a 2026-08-14 figure at MTU
     * 256; the real link is not a constant and a constant dressed up as a
     * measurement is how the wallet's screen came to be wrong by a factor of
     * twenty.
     */
    fun bytesPerSecond(): Double? = rate.bytesPerSecond()

    fun etaSeconds(): Long? = bytesPerSecond()?.let { totals().etaSeconds(it) }

    // --- one conversation, start to finish -----------------------------------

    /**
     * Sends one command and runs [onComplete] when its reply terminates.
     *
     * Every conversation here is the same shape -- one line out, `INFO` lines
     * back, a plain `OK` -- so there is one path for both rather than two that
     * can drift.
     */
    private fun send(line: String, onComplete: () -> Unit) {
        val mine = gen
        armTimeout(line)
        pendingCompletion = onComplete
        transport.sendCommand(line) { ok, error ->
            if (mine != gen) return@sendCommand
            // A write that fails never produces a reply, so the round ends here
            // rather than sitting until the timeout: the link is already known
            // to be gone and the rider is watching a status line.
            if (!ok) refuse(error ?: "the command could not be sent")
        }
    }

    private var pendingCompletion: (() -> Unit)? = null

    /**
     * One line off the command channel.
     *
     * Fed unconditionally by the owner: lines belonging to another conversation
     * are not this reader's and are ignored, the same contract [PinManager] and
     * [TileFetcher] keep.
     */
    fun onCommandLine(line: String) {
        val r = reader ?: return
        val t = line.trim()
        if (t.startsWith("ERR ")) {
            // A terminator on its own, with no `OK` behind it. Waiting for one
            // would burn the whole timeout and then report the wrong thing.
            refuse("the device answered: ${t.removePrefix("ERR ").trim()}")
            return
        }
        if (!r.feed(t)) return
        if (!r.complete) return
        val complete = pendingCompletion
        reader = null
        pendingCompletion = null
        cancelTimeout()
        complete?.invoke()
    }

    private fun armTimeout(what: String) {
        cancelTimeout()
        val mine = gen
        timeout = scheduler.postDelayed(REPLY_TIMEOUT_MS) {
            if (mine != gen) return@postDelayed
            refuse("the device did not answer `$what` in ${REPLY_TIMEOUT_MS / 1000} s")
        }
    }

    private fun cancelTimeout() {
        timeout?.cancel()
        timeout = null
    }

    /** The round cannot go on, and the rider is owed the reason. */
    private fun refuse(reason: String) {
        blocker = reason
        end(reason)
    }

    private fun end(reason: String) {
        cancelTimeout()
        drainScanner.cancel()
        gen++
        phase = Phase.IDLE
        reader = null
        pendingCompletion = null
        batch = emptyList()
        status = reason
        listener?.onOutboxChanged()
        listener?.onRoundFinished(reason)
    }

    private fun save() {
        try {
            store.save(outbox)
        } catch (t: Throwable) {
            // A queue that cannot be written is still a queue that works for
            // this session. Losing the rider's ask silently is the failure worth
            // shouting about; refusing to send over it is not.
            Log.w(TAG, "could not save the outbox: ${t.javaClass.simpleName}")
        }
    }

    private fun squares(n: Int): String = if (n == 1) "square" else "squares"

    /**
     * Throughput of the batches this run.
     *
     * Confirmed bytes over the time spent pushing them, and only confirmed ones:
     * a square whose chunks all went out and whose receipt never came moved no
     * bytes the rider can count on, and letting it into the numerator would make
     * a failing link look fast.
     */
    private class Rate {
        private var startedAtMs = 0L
        private var bytes = 0L
        private var elapsedMs = 0L

        fun startBatch(atMs: Long) {
            startedAtMs = atMs
        }

        fun confirmed(n: Long, atMs: Long) {
            if (startedAtMs == 0L) return
            bytes += n
            elapsedMs = (atMs - startedAtMs).coerceAtLeast(0L)
        }

        fun bytesPerSecond(): Double? {
            if (bytes <= 0L || elapsedMs <= 0L) return null
            return bytes * 1000.0 / elapsedMs
        }
    }
}
