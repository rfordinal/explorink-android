package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A whole pre-trip round, with the device, the CDN and the disk replaced by
 * fakes.
 *
 * The interesting cases are the ones nobody can produce by hand: a firmware too
 * old to say which screen it is on, a link that drops halfway through a city, a
 * process killed between two squares, and a device whose CRC does not match what
 * went out. Each of those is a way for the rider to be told their map is on the
 * card when it is not, which is the one failure this whole design exists to
 * refuse.
 */
class TileOutboxControllerTest {

    /**
     * The pre-trip case is the whole reason this exists, and it does not resolve
     * without it: the tile host builds from a 404 in its own access log and an
     * index read never produces one, so a queue that only re-reads the index
     * waits forever for a build nobody asked for. Found 2026-09-02 against a real
     * box of 33 squares over ground no device had ever visited -- the screen said
     * the server builds them on ask and nothing had ever asked.
     */
    @Test
    fun a_round_asks_the_server_for_every_square_it_has_not_built() {
        val asked = ArrayList<String>()
        val primer = TileOutboxController.Primer { tile, _, done ->
            asked.add(tile.key)
            // false: the server does not have it, which is the ordinary answer
            // and the reason the ask went out at all.
            done(false)
        }
        assertNotNull(primer)
        // The interface is what the round drives; the wiring that turns it into a
        // real HEAD is CdnTileSource.prime, which needs the network and is not
        // tested here.
        primer.prime(TileRef(13, 4146, 3061), 4) { }
        assertEquals(listOf("13/4146/3061"), asked)
    }

    /**
     * The compiled-in guess has to name the tree the firmware actually reads.
     *
     * It is not a harmless default. The pre-trip planner runs with no device in
     * the room -- that is the point of it -- so this number is what a rider's
     * first plan is measured against. It said 2 until 2026-09-02, and `/v2/` is
     * an abandoned tree: every index block answers 404 and `mapset.json` lists
     * zero areas, so a real phone reported "0 of 26 squares available" for
     * ground where all 26 existed.
     *
     * **Bump it with the firmware.** `MapTileReader::kFormatVersion` is the
     * authority; this test exists so a firmware bump cannot leave the app
     * silently reading an empty tree.
     */
    @Test
    fun the_compiled_in_format_guess_names_the_tree_the_firmware_reads() {
        assertEquals(4, CdnTileSource.DEFAULT_FORMAT_VERSION)
    }

    // Three real Barcelona squares. All in one z7 index block (7/64/47), so one
    // span answers for all three -- the 20 km box of `docs/send-tiles-plan.md`.
    private val a = TileRef(13, 4144, 3059)
    private val b = TileRef(13, 4145, 3059)
    // Outside every published area: real ground nobody has built yet. `b` plays
    // the opposite role -- an empty slot inside a built area, which is sea.
    private val unbuilt = TileRef(13, 4146, 3061)

    /** The published area covering a and b, named the way the server's queue names them. */
    private val builtCity = TilePlan.BuiltArea("auto-z11-1036-764", 41.0, 2.0, 42.0, 3.0)

    private val t0 = 1_788_100_000_000L

    // --- the refusals -----------------------------------------------------

    @Test
    fun `a batch is refused while the device is on its map screen`() {
        val h = Harness(a)
        h.connect()

        assertEquals(listOf("info"), h.transport.commands)
        h.reply("INFO screen=map", "INFO tile_fmt=4", "OK")

        // Nothing further on the channel, nothing pushed, and a reason the
        // rider can act on. The map screen's post-arrival redraw fires on a
        // settle timer with no check on whether bytes are moving, so a city
        // tile over it kills the link.
        assertEquals(listOf("info"), h.transport.commands)
        assertEquals(0, h.pusher.batches.size)
        assertTrue(h.controller.blocker!!.contains("map screen"))
        assertEquals(TileOutboxController.Phase.IDLE, h.controller.phase)
    }

    @Test
    fun `a firmware that does not say which screen it is on is refused, not assumed`() {
        val h = Harness(a)
        h.connect()
        // A healthy `info` from a build that predates the key.
        h.reply("INFO pos=1", "INFO lat=41.3874000", "INFO tile_fmt=4", "OK")

        assertEquals(0, h.pusher.batches.size)
        assertEquals(DeviceInfo.Screen.UNSTATED, h.controller.device!!.screen)
        assertTrue(h.controller.blocker!!.contains("does not say"))
    }

    @Test
    fun `a device that does not state its tile format is refused`() {
        // The one number that cannot be guessed: a device with nothing missing
        // never sends NEED_TILES or CHECK_TILES, so `info` is the only source,
        // and a tile of the wrong version passes CRC and is then refused on
        // open -- a whole batch spent for nothing.
        val h = Harness(a)
        h.connect()
        h.reply("INFO screen=sync", "OK")

        assertEquals(0, h.pusher.batches.size)
        assertTrue(h.controller.blocker!!.contains("tile format"))
    }

    @Test
    fun `nothing is said on the channel while another conversation holds it`() {
        val h = Harness(a)
        // Present on the CDN, so the round has something to push once the gate
        // opens. Without it `startDraining` now looks the square up first, finds
        // it waiting on a build, and correctly says nothing to the device -- true
        // behaviour, but it would hide the thing this test is about.
        h.index.put(a, 0xabc, 1000)
        h.gate.reason = "a pin command is running"
        h.controller.onConnected()
        h.scheduler.fire(TileOutboxController.CONNECT_SETTLE_MS)

        assertEquals(emptyList<String>(), h.transport.commands)
        assertFalse(h.controller.busy)

        // And it is not an error: the owner calls back when the channel frees.
        assertEquals("a pin command is running", h.controller.startDraining())
        assertEquals(emptyList<String>(), h.transport.commands)

        h.gate.reason = null
        assertNull(h.controller.startDraining())
        assertEquals(listOf("info"), h.transport.commands)
    }

    // --- the ordinary path ------------------------------------------------

    @Test
    fun `the format the device stated is what the index and the push both use`() {
        val h = Harness(a)
        h.index.put(a, contentId = 0xABB60454L, sizeBytes = 966_878L)
        h.connect()
        h.reply("INFO screen=sync", "INFO tile_fmt=4", "OK")
        h.reply("OK")

        assertEquals(4, h.mapset.lastFormatAsked)
        assertEquals(4, h.index.reads.single().formatVersion)
        assertEquals(4, h.pusher.lastFormat)
    }

    @Test
    fun `a whole round announces the batch before the first square goes out`() {
        val h = Harness(a, b)
        h.index.put(a, contentId = 1L, sizeBytes = 966_878L)
        h.index.put(b, contentId = 2L, sizeBytes = 500_000L)
        h.connect()
        h.reply("INFO screen=sync", "INFO tile_fmt=4", "OK")

        // `push <n>` goes before anything is handed to the pusher: without it
        // the device's sync screen sizes itself from an empty missing list and
        // shows "nothing missing" for the whole transfer.
        assertEquals(listOf("info", "push 2"), h.transport.commands)
        assertEquals(0, h.pusher.batches.size)

        h.reply("OK")
        assertEquals(1, h.pusher.batches.size)
        assertEquals(listOf(a.key, b.key), h.pusher.batches.single().map { "${it.z}/${it.col}/${it.row}" })
        // A rider-chosen tile was never on any device list, so there is no hit
        // count to carry.
        assertTrue(h.pusher.batches.single().all { it.count == 0L })
    }

    // --- the ledger -------------------------------------------------------

    @Test
    fun `a receipt is recorded only when it matches what went out`() {
        val h = Harness(a, b)
        h.index.put(a, contentId = 1L, sizeBytes = 966_878L)
        h.index.put(b, contentId = 2L, sizeBytes = 500_000L)
        h.runToPush()

        // The device reads the CRC back off its own card, so a disagreement
        // means the file there is not the file that went out.
        h.controller.onTileSending(a.z, a.col, a.row, 966_878, 0xdeadbeefL)
        h.controller.onTileReceipt(a.z, a.col, a.row, 966_878, 0x11111111L)
        assertFalse(h.outbox.isSent(a.key))
        assertEquals("receipt mismatch", h.outbox.items.first { it.key == a.key }.error)

        // A short count is refused the same way.
        h.controller.onTileSending(a.z, a.col, a.row, 966_878, 0xdeadbeefL)
        h.controller.onTileReceipt(a.z, a.col, a.row, 900_000, 0xdeadbeefL)
        assertFalse(h.outbox.isSent(a.key))

        // And the matching one lands.
        h.controller.onTileSending(a.z, a.col, a.row, 966_878, 0xdeadbeefL)
        h.controller.onTileReceipt(a.z, a.col, a.row, 966_878, 0xdeadbeefL)
        assertTrue(h.outbox.isSent(a.key))
        assertEquals(966_878L, h.outbox.receipts[a.key]!!.bytes)
        assertEquals(0xdeadbeefL, h.outbox.receipts[a.key]!!.crc32)
        assertEquals(TileOutboxController.TRANSPORT_NAME, h.outbox.receipts[a.key]!!.transport)
    }

    @Test
    fun `every chunk acknowledged is still not a sent tile`() {
        val h = Harness(a)
        h.index.put(a, contentId = 1L, sizeBytes = 966_878L)
        h.runToPush()

        h.controller.onTileSending(a.z, a.col, a.row, 966_878, 7L)
        h.controller.onTileProgress(a.z, a.col, a.row, 966_878, 966_878)
        assertFalse(h.outbox.isSent(a.key))
        assertEquals(TileState.SENDING, h.outbox.stateOf(h.outbox.items.single(), t0))
    }

    @Test
    fun `a wrong format is terminal and a refusal is not`() {
        val h = Harness(a, b)
        h.index.put(a, contentId = 1L, sizeBytes = 100L)
        h.index.put(b, contentId = 2L, sizeBytes = 100L)
        h.runToPush()

        h.controller.onTileSkipped(a.z, a.col, a.row, TileFetcher.SKIP_WRONG_FORMAT)
        h.controller.onTileSkipped(b.z, b.col, b.row, TileFetcher.SKIP_REFUSED)

        assertTrue(h.outbox.items.first { it.key == a.key }.terminal)
        assertFalse(h.outbox.items.first { it.key == b.key }.terminal)
    }

    // --- not yet against never ---------------------------------------------

    @Test
    fun `built ground with no tile is given up on and unbuilt ground is waited for`() {
        // The split the whole feature turns on, all the way through the chain:
        // one index read, one area list, three different verdicts.
        val h = Harness(a, b, unbuilt)
        h.index.put(a, contentId = 1L, sizeBytes = 966_878L)
        // b and unbuilt are empty slots in a block that exists. Only the area list
        // tells them apart.
        h.mapset.areas = listOf(builtCity)
        h.connect()
        h.reply("INFO screen=sync", "INFO tile_fmt=4", "OK")

        assertEquals(TilePlan.State.PRESENT, h.cdnOf(a))
        // Built ground, no tile: sea or empty OSM. Never asked for again.
        assertEquals(TilePlan.State.ABSENT, h.cdnOf(b))
        // Outside every published area: it is coming, minutes from now, with
        // nobody doing anything.
        assertEquals(TilePlan.State.WAITING_BUILD, h.cdnOf(unbuilt))

        assertTrue(h.outbox.dueForIndexRead(t0).isEmpty())
        assertEquals(listOf(unbuilt), h.outbox.dueForIndexRead(t0 + 6 * 60_000L))
        // Only the present one is announced and pushed.
        assertEquals(listOf("info", "push 1"), h.transport.commands)
    }

    @Test
    fun `an unreachable area list can never make a city sea`() {
        // One flight-mode toggle must not mark a whole box ABSENT. The round
        // skips the scan entirely and pushes only what was already established.
        val h = Harness(a, b)
        h.index.put(a, contentId = 1L, sizeBytes = 100L)
        h.mapset.unreachable = true
        h.connect()
        h.reply("INFO screen=sync", "INFO tile_fmt=4", "OK")

        assertEquals(0, h.index.reads.size)
        assertEquals(TilePlan.State.UNKNOWN, h.cdnOf(a))
        assertEquals(TilePlan.State.UNKNOWN, h.cdnOf(b))
        assertEquals(0, h.pusher.batches.size)
    }

    @Test
    fun `a zoom the index cannot describe is terminal, never retried forever`() {
        val odd = TileRef(9, 259, 191)
        val h = Harness(a, odd)
        h.index.put(a, contentId = 1L, sizeBytes = 100L)
        h.connect()
        h.reply("INFO screen=sync", "INFO tile_fmt=4", "OK")

        assertTrue(h.outbox.items.first { it.key == odd.key }.terminal)
        assertTrue(h.outbox.dueForIndexRead(t0 + 48 * 3_600_000L).none { it == odd })
    }

    // --- across connections -------------------------------------------------

    @Test
    fun `a reconnect announces the batch again, with what is still to come`() {
        val h = Harness(a, b)
        h.index.put(a, contentId = 1L, sizeBytes = 966_878L)
        h.index.put(b, contentId = 2L, sizeBytes = 500_000L)
        h.runToPush()
        assertEquals(listOf("info", "push 2"), h.transport.commands)

        // One square lands, then the link drops mid-batch.
        h.controller.onTileSending(a.z, a.col, a.row, 966_878, 7L)
        h.controller.onTileReceipt(a.z, a.col, a.row, 966_878, 7L)
        h.controller.onDisconnected()
        assertNull(h.outbox.inFlight)

        // The device's sync screen went back to Waiting and then Finished with
        // no announced total, so a sender that just resumed pushing would land
        // its bytes dark.
        h.connect()
        h.reply("INFO screen=sync", "INFO tile_fmt=4", "OK")
        assertEquals(listOf("info", "push 2", "info", "push 1"), h.transport.commands)

        h.reply("OK")
        assertEquals(listOf(b.key), h.pusher.batches.last().map { "${it.z}/${it.col}/${it.row}" })
    }

    @Test
    fun `the queue resumes at the next pending square after a process death`() {
        val h = Harness(a, b)
        h.index.put(a, contentId = 1L, sizeBytes = 966_878L)
        h.index.put(b, contentId = 2L, sizeBytes = 500_000L)
        h.runToPush()
        h.controller.onTileSending(a.z, a.col, a.row, 966_878, 7L)
        h.controller.onTileReceipt(a.z, a.col, a.row, 966_878, 7L)
        // Killed here: b's chunks were on the wire and no receipt came.
        h.controller.onTileSending(b.z, b.col, b.row, 500_000, 9L)
        h.controller.onTileProgress(b.z, b.col, b.row, 200_000, 500_000)

        val onDisk = h.store.saved ?: error("nothing was persisted")
        val restored = (OutboxJson.read(onDisk) as OutboxJson.Load.Restored).outbox

        // The receipt survived; the half-sent square is pending again by
        // construction, because nothing recorded it as sending.
        assertTrue(restored.isSent(a.key))
        assertNull(restored.inFlight)
        assertEquals(b, restored.next(t0)?.tile)

        val next = Harness(outbox = restored)
        next.index.put(b, contentId = 2L, sizeBytes = 500_000L)
        next.connect()
        next.reply("INFO screen=sync", "INFO tile_fmt=4", "OK")
        // Nothing to look up: both squares already have a verdict.
        assertEquals(0, next.index.reads.size)
        assertEquals(listOf("info", "push 1"), next.transport.commands)
        next.reply("OK")
        assertEquals(listOf(b.key), next.pusher.batches.single().map { "${it.z}/${it.col}/${it.row}" })
    }

    @Test
    fun `a link that drops before the reply ends the round rather than hanging`() {
        val h = Harness(a)
        h.connect()
        assertTrue(h.controller.busy)

        h.controller.onDisconnected()
        assertFalse(h.controller.busy)
        // And a stray reply from the dead link touches nothing.
        h.reply("INFO screen=sync", "INFO tile_fmt=4", "OK")
        assertEquals(listOf("info"), h.transport.commands)
    }

    @Test
    fun `a device that never answers stops the round on the timeout`() {
        val h = Harness(a)
        h.connect()
        h.scheduler.fire(TileOutboxController.REPLY_TIMEOUT_MS)

        assertFalse(h.controller.busy)
        assertTrue(h.controller.blocker!!.contains("did not answer"))
    }

    @Test
    fun `pause stops the next round and continue starts one`() {
        val h = Harness(a)
        h.index.put(a, contentId = 1L, sizeBytes = 100L)
        h.controller.onConnected()
        h.controller.pause()
        h.scheduler.fire(TileOutboxController.CONNECT_SETTLE_MS)
        assertEquals(emptyList<String>(), h.transport.commands)

        assertNull(h.controller.resume())
        assertEquals(listOf("info"), h.transport.commands)
    }

    @Test
    fun `queueing a zone stores the ask and starts a round`() {
        val h = Harness()
        h.controller.onConnected()
        h.scheduler.fire(TileOutboxController.CONNECT_SETTLE_MS)
        // Nothing queued, so nothing was said.
        assertEquals(emptyList<String>(), h.transport.commands)

        h.controller.queueZone(413_874_000, 21_686_000, 10, "Barcelona 10 km")
        // 26 squares for a 10 km box round Barcelona, and the ask is on disk
        // before anything else happens -- it exists nowhere else.
        assertEquals(1, h.outbox.zones.size)
        assertEquals(26, h.outbox.items.size)
        assertNotNull(h.store.saved)
        // **No `info`, and that is the point.** Queueing now does its own CDN
        // lookup first (`lookUpAndAsk`, which needs no device), so by the time
        // the round is offered there is nothing left to look up and nothing that
        // the index says is present to push. A round with nothing to do says
        // nothing to the device rather than opening a conversation to announce a
        // batch of zero. The fake index answers no block for this ground, so
        // every square lands in the waiting-for-a-build state.
        assertEquals(emptyList<String>(), h.transport.commands)
    }

    @Test
    fun `dropping a zone keeps the receipts it earned`() {
        val h = Harness(a)
        h.index.put(a, contentId = 1L, sizeBytes = 100L)
        h.runToPush()
        h.controller.onTileSending(a.z, a.col, a.row, 100, 7L)
        h.controller.onTileReceipt(a.z, a.col, a.row, 100, 7L)

        h.controller.dropZone("zone-1")
        assertEquals(0, h.outbox.items.size)
        // A receipt is a fact about what is on the device's card. Forgetting it
        // would make the next overlapping zone push 258 kB the device holds.
        assertTrue(h.outbox.isSent(a.key))
    }

    // --- planning, which needs no device -------------------------------------

    @Test
    fun `a plan states the exact cost with no link and no device`() {
        val h = Harness()
        h.index.put(a, contentId = 1L, sizeBytes = 966_878L)
        h.index.put(b, contentId = 2L, sizeBytes = 500_000L)
        h.mapset.areas = listOf(builtCity)

        var plan: TileOutboxController.Plan? = null
        // Never connected, and it still answers: a rider plans at home.
        h.controller.plan(41.3874, 2.1686, 10.0, 4) { plan = it }

        val p = plan ?: error("no plan")
        assertEquals(26, p.summary.tiles)
        assertEquals(2, p.summary.present)
        // Exact, off the index, not an average.
        assertEquals(1_466_878L, p.summary.bytes)
        assertEquals(emptyList<String>(), h.transport.commands)
    }

    // --- harness ---------------------------------------------------------------

    private inner class Harness(
        vararg tiles: TileRef,
        val outbox: TileOutbox = TileOutbox(),
    ) {
        val transport = FakeTransport()
        val mapset = FakeMapset()
        val index = FakeIndex()
        val pusher = FakePusher()
        val store = FakeStore()
        val scheduler = FakeScheduler()
        val gate = FakeGate()
        var now = t0

        val controller = TileOutboxController(
            outbox = outbox,
            transport = transport,
            mapsetSource = mapset,
            indexSource = index,
            pusher = pusher,
            store = store,
            scheduler = scheduler,
            gate = gate,
            listener = null,
            now = { now },
        )

        init {
            if (tiles.isNotEmpty()) {
                outbox.addZone(
                    TileZone("zone-1", "Barcelona 20 km", 413_874_000, 21_686_000, 20, t0),
                    tiles.toList(),
                )
            }
        }

        /** Link up, settle timer fired, `info` on the wire. */
        fun connect() {
            controller.onConnected()
            scheduler.fire(TileOutboxController.CONNECT_SETTLE_MS)
        }

        /** Feeds one reply, line by line, as the channel delivers it. */
        fun reply(vararg lines: String) = lines.forEach { controller.onCommandLine(it) }

        /** All the way to the pusher holding the batch. */
        fun runToPush() {
            connect()
            reply("INFO screen=sync", "INFO tile_fmt=4", "OK")
            reply("OK")
        }

        fun cdnOf(t: TileRef): TilePlan.State = outbox.items.first { it.key == t.key }.cdn
    }

    /** Records every command and acks the write, as a healthy link does. */
    private class FakeTransport : TileOutboxController.Transport {
        val commands = mutableListOf<String>()
        var failWrites = false

        override fun sendCommand(line: String, done: (Boolean, String?) -> Unit) {
            commands.add(line)
            if (failWrites) done(false, "fake failure") else done(true, null)
        }
    }

    private class FakeMapset : MapsetSource {
        var areas: List<TilePlan.BuiltArea> = emptyList()
        var unreachable = false
        var nothingPublished = false
        var lastFormatAsked: Int? = -1

        override fun read(formatVersion: Int?, done: (MapsetSource.Result) -> Unit) {
            lastFormatAsked = formatVersion
            done(
                when {
                    unreachable -> MapsetSource.Result.Unreachable("test")
                    nothingPublished -> MapsetSource.Result.NothingPublished
                    else -> MapsetSource.Result.Areas(areas)
                }
            )
        }
    }

    private class FakePusher : TileOutboxController.Pusher {
        val batches = mutableListOf<List<MissingTile>>()
        var lastFormat: Int? = -1
        override var idle: Boolean = true

        override fun pushTiles(tiles: List<MissingTile>, formatVersion: Int?) {
            batches.add(tiles)
            lastFormat = formatVersion
        }
    }

    private class FakeStore : TileOutboxController.Store {
        var saved: String? = null
        var saves = 0

        override fun save(outbox: TileOutbox) {
            saves++
            // Through the real serializer, so a test that reads it back is
            // testing the format a second process would actually find.
            saved = OutboxJson.write(outbox)
        }
    }

    /** Holds pending work by its delay, so a test fires exactly the timer it means. */
    private class FakeScheduler : TileFetcher.Scheduler {
        private val pending = mutableListOf<Pair<Long, () -> Unit>>()

        override fun postDelayed(delayMs: Long, action: () -> Unit): TileFetcher.Scheduler.Cancellable {
            val entry = delayMs to action
            pending.add(entry)
            return object : TileFetcher.Scheduler.Cancellable {
                override fun cancel() {
                    pending.remove(entry)
                }
            }
        }

        fun fire(delayMs: Long) {
            val at = pending.indexOfFirst { it.first == delayMs }
            if (at < 0) error("no timer armed for $delayMs ms")
            val (_, action) = pending.removeAt(at)
            action()
        }
    }

    private class FakeGate : TileOutboxController.Gate {
        var reason: String? = null
        override fun blocker(): String? = reason
    }

    /**
     * An index answering from a map of published slots, completing inline.
     * The same fake [IndexScannerTest] uses: the scanner only requires "exactly
     * once, on my thread".
     */
    private class FakeIndex : IndexSource {
        class Read(val relPath: String, val formatVersion: Int?)

        val reads = mutableListOf<Read>()
        private val blocks = HashSet<String>()
        private val slots = HashMap<String, Pair<Long, Long>>()
        var unreachable = false

        fun block(t: TileRef) {
            blocks.add(
                TileIndex.blockRelPath(TileIndex.blockCol(t.z, t.col), TileIndex.blockRow(t.z, t.row))
            )
        }

        fun put(t: TileRef, contentId: Long, sizeBytes: Long) {
            block(t)
            slots[t.key] = contentId to sizeBytes
        }

        override fun readRange(
            relPath: String,
            first: Int,
            last: Int,
            formatVersion: Int?,
            done: (IndexSource.Result) -> Unit,
        ) {
            reads.add(Read(relPath, formatVersion))
            done(
                when {
                    unreachable -> IndexSource.Result.Unreachable("test")
                    relPath !in blocks -> IndexSource.Result.NotPublished
                    else -> IndexSource.Result.Bytes(body(relPath, first, last))
                }
            )
        }

        private fun body(relPath: String, first: Int, last: Int): ByteArray {
            val buf = ByteArray(last - first + 1)
            for ((key, v) in slots) {
                val parts = key.split('/')
                val z = parts[0].toInt()
                val col = parts[1].toLong()
                val row = parts[2].toLong()
                val path = TileIndex.blockRelPath(TileIndex.blockCol(z, col), TileIndex.blockRow(z, row))
                if (path != relPath) continue
                val at = TileIndex.slotOffset(z, col, row) - first
                if (at < 0 || at + TileIndex.SLOT_BYTES > buf.size) continue
                buf[at] = (1 or (TileIndex.COVERAGE_FULL shl 1)).toByte()
                for (i in 0 until 4) buf[at + 2 + i] = ((v.first shr (8 * i)) and 0xff).toByte()
                for (i in 0 until 4) buf[at + 10 + i] = ((v.second shr (8 * i)) and 0xff).toByte()
            }
            return buf
        }
    }
}
