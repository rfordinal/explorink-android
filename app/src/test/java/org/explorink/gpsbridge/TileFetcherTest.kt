package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fetch conversation end to end, with BLE and time replaced by fakes.
 *
 * This is the code that runs unattended mid-ride against a device that can
 * vanish at any point in the sequence, so the interesting cases here are the
 * ugly ones: a tile the source does not have, a wrong format version, a device
 * that refuses a push, a device that stops answering.
 */
class TileFetcherTest {

    /** A tile with a valid v2 header and [size] total bytes. */
    private fun tileBytes(size: Int, version: Int = 2): ByteArray {
        val out = ByteArray(size)
        TileHeader.MAGIC.copyInto(out)
        out[4] = (version and 0xff).toByte()
        out[5] = ((version shr 8) and 0xff).toByte()
        for (i in 6 until size) out[i] = (i and 0xff).toByte()
        return out
    }

    private class FakeSource : TileSource {
        val tiles = mutableMapOf<String, ByteArray>()
        /** Format version the fetcher passed down, so a test can prove it did. */
        var lastFormatAsked: Int? = -1
        /** Expected content id the fetcher passed down, so a test can prove it did. */
        var lastCrcAsked: Long? = -1L

        /**
         * Every read asked for, in order, as `"z/col/row"`.
         *
         * The read-ahead is only visible here: prefetching is not a wire event,
         * so "the next tile's GET started before this tile's OK" is a statement
         * about the order of calls into the source and nothing else.
         */
        val reads = mutableListOf<String>()

        /** Keys whose reads are parked instead of answered inline. */
        val hold = mutableSetOf<String>()
        private val held = mutableListOf<Pair<String, (ByteArray?) -> Unit>>()

        // Completes inline unless held. The real sources come back on the main
        // thread from a worker; the fetcher only requires "exactly once, on my
        // thread", which this satisfies without a looper in the test.
        override fun read(
            z: Int,
            col: Long,
            row: Long,
            formatVersion: Int?,
            expectedContentId: Long?,
            done: (ByteArray?) -> Unit,
        ) {
            lastFormatAsked = formatVersion
            lastCrcAsked = expectedContentId
            val key = "$z/$col/$row"
            reads.add(key)
            if (key in hold) {
                held.add(key to done)
                return
            }
            done(tiles[key])
        }

        /** Answers the [index]th parked read, in the order they were asked for. */
        fun release(index: Int) {
            val (key, done) = held[index]
            done(tiles[key])
        }

        override fun describe(): String = "fake"
    }

    /** Records every write and completes it immediately, as a healthy link does. */
    private class FakeTransport(private val payload: Int = 100) : TileFetcher.Transport {
        val commands = mutableListOf<String>()
        val frames = mutableListOf<ByteArray>()
        var failNextFrame = false
        var failNextCommand = false

        /**
         * Command writes are queued instead of acked immediately once armed.
         * A second `NEED_TILES` can restart the fetch before an earlier
         * `missing`/`skip` write's own response comes back, so that ordering
         * has to be testable the same way [holdBeginAcks] makes a late begin
         * failure testable.
         */
        var holdCommands = false
        private val heldCommands = mutableListOf<(Boolean, String?) -> Unit>()

        override fun sendCommand(line: String, done: (Boolean, String?) -> Unit) {
            commands.add(line)
            if (holdCommands) {
                heldCommands.add(done)
                return
            }
            if (failNextCommand) {
                failNextCommand = false
                done(false, "fake failure")
            } else {
                done(true, null)
            }
        }

        /** Answers the [index]th held command write, in the order it was sent. */
        fun answerHeldCommand(index: Int, ok: Boolean, error: String? = null) {
            heldCommands[index](ok, error)
        }

        /**
         * Chunk writes from this index on are not acked until [ackHeldChunks]
         * runs. The device's `OK` indication and the last chunk's write
         * response race on the real link, so that ordering has to be testable.
         */
        var holdChunkAcksFrom = -1
        private var chunksSeen = 0
        private val heldAcks = mutableListOf<(Boolean, String?) -> Unit>()

        /**
         * How many of the next begin writes get no answer until
         * [answerHeldBegins] runs. `BleLink`'s op queue can hold a write for its
         * whole 10 s timeout and answer it after this side has given up on the
         * tile, so a begin failure arriving late has to be testable.
         */
        var holdBeginAcks = 0
        private val heldBeginAcks = mutableListOf<(Boolean, String?) -> Unit>()

        override fun sendFrame(frame: ByteArray, done: (Boolean, String?) -> Unit) {
            frames.add(frame)
            if (failNextFrame) {
                failNextFrame = false
                done(false, "fake failure")
                return
            }
            if (frame[0] == TransferFrames.OP_BEGIN && holdBeginAcks > 0) {
                holdBeginAcks--
                heldBeginAcks.add(done)
                return
            }
            if (frame[0] == TransferFrames.OP_CHUNK) {
                val index = chunksSeen++
                if (holdChunkAcksFrom >= 0 && index >= holdChunkAcksFrom) {
                    heldAcks.add(done)
                    return
                }
            }
            done(true, null)
        }

        /** Answers every held begin write, as the stack finally would. */
        fun answerHeldBegins(ok: Boolean, error: String? = null) {
            val pending = heldBeginAcks.toList()
            heldBeginAcks.clear()
            pending.forEach { it(ok, error) }
        }

        fun ackHeldChunks() {
            val pending = heldAcks.toList()
            heldAcks.clear()
            pending.forEach { it(true, null) }
        }

        override fun maxChunkPayload(): Int = payload

        // Named apart from the interface method: a `var fastLink` would generate
        // a setFastLink(Z)V setter that clashes with it on the JVM.
        var fastLinkHeld = false
        var fastLinkGrabs = 0
        var fastLinkReleases = 0
        override fun setFastLink(fast: Boolean) {
            fastLinkHeld = fast
            if (fast) fastLinkGrabs++ else fastLinkReleases++
        }

        fun chunkFrames(): List<ByteArray> = frames.filter { it[0] == TransferFrames.OP_CHUNK }
        fun beginFrames(): List<ByteArray> = frames.filter { it[0] == TransferFrames.OP_BEGIN }
        fun abortFrames(): List<ByteArray> = frames.filter { it[0] == TransferFrames.OP_ABORT }

    }

    /** Holds the pending timeout instead of running it, so a test can fire it. */
    private class FakeScheduler : TileFetcher.Scheduler {
        var pending: (() -> Unit)? = null

        override fun postDelayed(delayMs: Long, action: () -> Unit): TileFetcher.Scheduler.Cancellable {
            pending = action
            return object : TileFetcher.Scheduler.Cancellable {
                override fun cancel() {
                    if (pending === action) pending = null
                }
            }
        }

        fun fire() {
            val a = pending ?: error("no timeout armed")
            pending = null
            a()
        }
    }

    private class Recorder : TileFetcher.Listener {
        var started = -1
        val progress = mutableListOf<Triple<Int, Int, Int>>()
        var finished: String? = null
        var finalSent = -1
        var finalSkipped = -1

        /** Per-square verdicts, in order, so a test can prove which tile got one. */
        val doneTiles = mutableListOf<String>()

        override fun onTileDone(z: Int, col: Long, row: Long, bytes: Int, ok: Boolean, detail: String) {
            doneTiles.add("$z/$col/$row ${if (ok) "landed" else "skipped"}")
        }

        override fun onFetchStarted(total: Int) {
            started = total
        }

        override fun onFetchProgress(sent: Int, skipped: Int, total: Int) {
            progress.add(Triple(sent, skipped, total))
        }

        override fun onFetchFinished(sent: Int, skipped: Int, total: Int, reason: String) {
            finished = reason
            finalSent = sent
            finalSkipped = skipped
        }
    }

    private class Harness(payload: Int = 100) {
        val source = FakeSource()
        val transport = FakeTransport(payload)
        val scheduler = FakeScheduler()
        val recorder = Recorder()
        val fetcher = TileFetcher(source, transport, scheduler, recorder)

        /** One page listing exactly [tiles], terminated. */
        fun list(vararg tiles: MissingTile) {
            fetcher.onCommandLine("INFO missing_total=${tiles.size}")
            fetcher.onCommandLine("INFO missing_offset=0")
            tiles.forEach { fetcher.onCommandLine("INFO missing_${it.z}_${it.col}_${it.row}=${it.count}") }
            fetcher.onCommandLine("INFO missing_next=done")
            fetcher.onCommandLine("OK")
        }
    }

    @Test
    fun `a whole tile goes out in chunks and the device's OK moves to the next`() {
        val h = Harness(payload = 100)
        h.source.tiles["12/2199/1416"] = tileBytes(250)

        h.fetcher.onCommandLine("NEED_TILES 1 fmt 2")
        assertEquals(1, h.recorder.started)
        assertEquals(listOf("missing"), h.transport.commands)

        h.list(MissingTile(12, 2199, 1416, 7))
        // Begin first, then nothing until the device says RDY: sending chunks
        // before it has the file open earns ERR no transfer.
        assertEquals(1, h.transport.beginFrames().size)
        assertEquals(0, h.transport.chunkFrames().size)

        h.fetcher.onStatusLine("RDY 250")
        // 250 bytes at 100 per chunk: three chunks, the last one short. Each one
        // is sent from the previous one's write callback -- that is the flow
        // control, and the fake completes writes immediately, so all three are
        // already out.
        val chunks = h.transport.chunkFrames()
        assertEquals(3, chunks.size)
        assertEquals(105, chunks[0].size)
        assertEquals(105, chunks[1].size)
        assertEquals(55, chunks[2].size)
        // Offsets are byte counts, in order, no gaps.
        assertEquals(0, offsetOf(chunks[0]))
        assertEquals(100, offsetOf(chunks[1]))
        assertEquals(200, offsetOf(chunks[2]))

        h.fetcher.onStatusLine("OK 250 ${"%08x".format(TransferFrames.crc32(tileBytes(250)))}")
        assertEquals("done", h.recorder.finished)
        assertEquals(1, h.recorder.finalSent)
        assertEquals(0, h.recorder.finalSkipped)
    }

    @Test
    fun `a tile the source does not have is skipped, not waited for`() {
        val h = Harness()
        h.fetcher.onCommandLine("NEED_TILES 1 fmt 2")
        h.list(MissingTile(12, 2199, 1416, 1))

        // No begin frame at all, and the device is told -- otherwise its progress
        // screen sits on a file that is never coming.
        assertEquals(0, h.transport.frames.size)
        assertTrue(h.transport.commands.contains("skip 12 2199 1416 ${TileFetcher.SKIP_NO_SOURCE}"))
        assertEquals(1, h.recorder.finalSkipped)
        assertEquals("done", h.recorder.finished)
    }

    @Test
    fun `a tile built to another format version is skipped before any bytes go out`() {
        val h = Harness()
        h.source.tiles["12/2199/1416"] = tileBytes(200, version = 3)

        h.fetcher.onCommandLine("NEED_TILES 1 fmt 2")
        h.list(MissingTile(12, 2199, 1416, 1))

        // The whole point: a v3 tile would transfer fine and pass CRC, and the
        // device would only find out when its reader refuses it on the next
        // render -- after the entry had already been dropped from its list.
        assertEquals(0, h.transport.frames.size)
        assertTrue(h.transport.commands.contains("skip 12 2199 1416 ${TileFetcher.SKIP_WRONG_FORMAT}3"))
        assertEquals(1, h.recorder.finalSkipped)
    }

    @Test
    fun `with no format version stated the tile is pushed anyway`() {
        val h = Harness()
        h.source.tiles["12/2199/1416"] = tileBytes(50, version = 3)

        // An older firmware build cannot say which version it reads. Refusing
        // everything would break the fetch against exactly those builds.
        h.fetcher.onCommandLine("NEED_TILES 1")
        h.list(MissingTile(12, 2199, 1416, 1))
        assertEquals(1, h.transport.beginFrames().size)
    }

    @Test
    fun `a device ERR counts the tile as skipped and moves on`() {
        val h = Harness()
        h.source.tiles["12/1/1"] = tileBytes(50)
        h.source.tiles["11/2/2"] = tileBytes(50)

        h.fetcher.onCommandLine("NEED_TILES 2 fmt 2")
        h.list(MissingTile(12, 1, 1, 1), MissingTile(11, 2, 2, 1))

        h.fetcher.onStatusLine("ERR crc mismatch")
        // No more chunks for the dead one -- each would earn another
        // `ERR no transfer` -- and the next tile's begin is already out.
        assertTrue(h.transport.commands.any { it.startsWith("skip 12 1 1") })
        assertEquals(2, h.transport.beginFrames().size)

        h.fetcher.onStatusLine("RDY 50")
        h.fetcher.onStatusLine("OK 50 00000000")
        assertEquals(1, h.recorder.finalSent)
        assertEquals(1, h.recorder.finalSkipped)
    }

    @Test
    fun `a listing that stops being answered ends the fetch`() {
        val h = Harness()
        h.fetcher.onCommandLine("NEED_TILES 5 fmt 2")
        h.fetcher.onCommandLine("INFO missing_total=5")
        // No `OK`, no more lines. The armed timeout is the only way out.
        h.scheduler.fire()
        assertEquals("device stopped answering", h.recorder.finished)
    }

    @Test
    fun `a stalled transfer is aborted and the fetch carries on`() {
        val h = Harness()
        h.source.tiles["12/1/1"] = tileBytes(50)
        h.source.tiles["11/2/2"] = tileBytes(50)

        h.fetcher.onCommandLine("NEED_TILES 2 fmt 2")
        h.list(MissingTile(12, 1, 1, 1), MissingTile(11, 2, 2, 1))
        // First tile's RDY never arrives.
        h.scheduler.fire()

        // The device's .part file has to be cleaned up, and one dead tile must
        // not end the whole fetch.
        assertEquals(1, h.transport.abortFrames().size)
        assertTrue(h.transport.commands.any { it.startsWith("skip 12 1 1") })
        assertEquals(2, h.transport.beginFrames().size)
    }

    @Test
    fun `the rider cancelling on the device stops everything`() {
        val h = Harness()
        h.source.tiles["12/1/1"] = tileBytes(500)

        h.fetcher.onCommandLine("NEED_TILES 1 fmt 2")
        h.list(MissingTile(12, 1, 1, 1))
        h.fetcher.onStatusLine("RDY 500")

        h.fetcher.onCommandLine("FETCH_CANCEL")
        assertEquals("cancelled on the device", h.recorder.finished)
        assertEquals(TileFetcher.Phase.IDLE, h.fetcher.phase)

        // A late status line from the abandoned transfer must not restart it.
        h.fetcher.onStatusLine("OK 500 00000000")
        assertEquals(TileFetcher.Phase.IDLE, h.fetcher.phase)
    }

    @Test
    fun `the device's format version is what the source is asked for`() {
        // The CDN publishes one path per .tib format version, so this number
        // picks the URL. Passing the device's own answer through makes a format
        // mismatch impossible rather than something to detect afterwards.
        val h = Harness()
        h.source.tiles["12/1/1"] = tileBytes(50)
        h.fetcher.onCommandLine("NEED_TILES 1 fmt 2")
        h.list(MissingTile(12, 1, 1, 1))
        assertEquals(2, h.source.lastFormatAsked)

        // An older firmware that does not say leaves it null -- the source picks
        // its own fallback, and nothing here pretends to know.
        val old = Harness()
        old.source.tiles["12/1/1"] = tileBytes(50)
        old.fetcher.onCommandLine("NEED_TILES 1")
        old.list(MissingTile(12, 1, 1, 1))
        assertEquals(null, old.source.lastFormatAsked)
    }

    @Test
    fun `a read that lands after the fetch ended starts nothing`() {
        // The source is asynchronous now, so a cancel or a dropped link can
        // arrive while a read is in flight. The late answer must not open a
        // transfer on a fetch that is already over.
        val h = Harness()
        var deliver: ((ByteArray?) -> Unit)? = null
        val slow = object : TileSource {
            override fun read(
                z: Int,
                col: Long,
                row: Long,
                formatVersion: Int?,
                expectedContentId: Long?,
                done: (ByteArray?) -> Unit,
            ) {
                deliver = done
            }
            override fun describe(): String = "slow"
        }
        val fetcher = TileFetcher(slow, h.transport, h.scheduler, h.recorder)
        fetcher.onCommandLine("NEED_TILES 1 fmt 2")
        fetcher.onCommandLine("INFO missing_total=1")
        fetcher.onCommandLine("INFO missing_offset=0")
        fetcher.onCommandLine("INFO missing_12_1_1=1")
        fetcher.onCommandLine("INFO missing_next=done")
        fetcher.onCommandLine("OK")

        fetcher.onDisconnected()
        assertEquals(TileFetcher.Phase.IDLE, fetcher.phase)

        deliver?.invoke(tileBytes(50))
        assertEquals(0, h.transport.beginFrames().size)
        assertEquals(TileFetcher.Phase.IDLE, fetcher.phase)
    }

    @Test
    fun `a late read from an ended fetch does not join the fetch that replaces it`() {
        // The defect this guards against: fetch A's CDN read is still in
        // flight (10-20s of HTTP timeouts) when A ends. A new fetch B starts
        // right after, on the same fetcher, same channel. `phase` alone cannot
        // tell A's late read apart from B's own reads -- both see `PUSHING` --
        // so without a fetch generation gate, A's late read would open a
        // second begin frame on top of B's live transfer
        // (`docs/ble-review-2026-08.md`, "Stability -- app", item 2).
        val h = Harness()
        val source = object : TileSource {
            val immediate = mutableMapOf<String, ByteArray>()
            var deliverA: ((ByteArray?) -> Unit)? = null
            override fun read(
                z: Int,
                col: Long,
                row: Long,
                formatVersion: Int?,
                expectedContentId: Long?,
                done: (ByteArray?) -> Unit,
            ) {
                val bytes = immediate["$z/$col/$row"]
                if (bytes != null) done(bytes) else deliverA = done
            }
            override fun describe(): String = "test"
        }
        val fetcher = TileFetcher(source, h.transport, h.scheduler, h.recorder)

        // Fetch A: one tile, whose read never comes back before A ends.
        fetcher.onCommandLine("NEED_TILES 1 fmt 2")
        fetcher.onCommandLine("INFO missing_total=1")
        fetcher.onCommandLine("INFO missing_offset=0")
        fetcher.onCommandLine("INFO missing_12_1_1=1")
        fetcher.onCommandLine("INFO missing_next=done")
        fetcher.onCommandLine("OK")
        assertNotNull("A's read should be outstanding", source.deliverA)

        // The rider cancels -- ends fetch A while its read is still in flight.
        fetcher.onCommandLine("FETCH_CANCEL")
        assertEquals(TileFetcher.Phase.IDLE, fetcher.phase)

        // Fetch B starts right after: a stale-tile push, same fetcher, same
        // source and transport -- what the freshness check does once a
        // listing gets cancelled mid-ride.
        source.immediate["9/9/9"] = tileBytes(60)
        fetcher.pushTiles(listOf(MissingTile(9, 9, 9, 1)), 2)
        assertEquals(TileFetcher.Phase.PUSHING, fetcher.phase)
        assertEquals(1, h.transport.beginFrames().size)

        // A's read finally lands, mid B's transfer.
        source.deliverA?.invoke(tileBytes(50))

        // No begin frame for A's tile -- the guard held.
        assertEquals(1, h.transport.beginFrames().size)

        // And B's own tile/bytes/offset state is exactly as it was: its
        // transfer still completes normally on its own status lines.
        fetcher.onStatusLine("RDY 60")
        assertEquals(1, h.transport.chunkFrames().size)
        fetcher.onStatusLine("OK 60 ${"%08x".format(TransferFrames.crc32(tileBytes(60)))}")
        assertEquals(listOf("9/9/9 landed"), h.recorder.doneTiles)
        assertEquals("done", h.recorder.finished)
        assertEquals(1, h.recorder.finalSent)
        assertEquals(0, h.recorder.finalSkipped)
    }

    @Test
    fun `the fast link is held for the fetch and given back at the end`() {
        val h = Harness()
        h.source.tiles["12/1/1"] = tileBytes(50)

        h.fetcher.onCommandLine("NEED_TILES 1 fmt 2")
        // A high-priority connection holds the radio at a fast interval
        // continuously, so it must not outlive the transfer it exists for.
        assertTrue(h.transport.fastLinkHeld)

        h.list(MissingTile(12, 1, 1, 1))
        h.fetcher.onStatusLine("RDY 50")
        h.fetcher.onStatusLine("OK 50 00000000")
        assertFalse(h.transport.fastLinkHeld)
        assertEquals(1, h.transport.fastLinkReleases)
    }

    @Test
    fun `the fast link is re-asserted at every tile boundary, not just once per fetch`() {
        // Android can silently ignore or revert a connection-priority request,
        // so a single ask at fetch start is not proof the link stayed fast.
        // Re-issuing it as each tile's begin frame goes out is idempotent and
        // costs nothing (`docs/ble-review-2026-08.md`, "Performance").
        val h = Harness()
        h.source.tiles["12/1/1"] = tileBytes(50)
        h.source.tiles["12/1/2"] = tileBytes(50)
        h.source.tiles["12/1/3"] = tileBytes(50)
        val tiles = arrayOf(
            MissingTile(12, 1, 1, 1),
            MissingTile(12, 1, 2, 1),
            MissingTile(12, 1, 3, 1),
        )

        h.fetcher.onCommandLine("NEED_TILES 3 fmt 2")
        h.list(*tiles)
        repeat(tiles.size) {
            h.fetcher.onStatusLine("RDY 50")
            h.fetcher.onStatusLine("OK 50 00000000")
        }

        assertEquals("done", h.recorder.finished)
        assertEquals(3, h.recorder.finalSent)
        // N tiles -> at least N assertions of HIGH: one per begin frame, plus
        // whatever the listing/pushing phase transitions already asked for.
        assertTrue(
            "expected >= ${tiles.size} grabs, got ${h.transport.fastLinkGrabs}",
            h.transport.fastLinkGrabs >= tiles.size,
        )
        // Regardless of how many times it was grabbed, finish() releases it
        // exactly once.
        assertFalse(h.transport.fastLinkHeld)
        assertEquals(1, h.transport.fastLinkReleases)
    }

    @Test
    fun `the fast link is given back when a fetch dies rather than finishes`() {
        // Every exit has to release it, not just the happy one -- a link lost
        // mid-transfer would otherwise leave the radio fast until the app dies.
        for (kill in listOf<(Harness) -> Unit>(
            { it.fetcher.onDisconnected() },
            { it.fetcher.onCommandLine("FETCH_CANCEL") },
            { it.fetcher.stop() },
        )) {
            val h = Harness()
            h.fetcher.onCommandLine("NEED_TILES 3 fmt 2")
            assertTrue(h.transport.fastLinkHeld)
            kill(h)
            assertFalse(h.transport.fastLinkHeld)
        }
    }

    @Test
    fun `losing the link ends the fetch`() {
        val h = Harness()
        h.fetcher.onCommandLine("NEED_TILES 3 fmt 2")
        h.fetcher.onDisconnected()
        assertEquals("link lost", h.recorder.finished)
        assertEquals(TileFetcher.Phase.IDLE, h.fetcher.phase)
    }

    @Test
    fun `restarting the listing before a missing write is acked does not let the stale write finish the new one`() {
        // The defect this guards against: a second NEED_TILES restarts the
        // whole fetch while requestPage()'s own `missing` write is still
        // unacknowledged. Without a generation gate, that write's callback
        // has no idea a restart happened and calls finish() unconditionally
        // -- tearing down the run that replaced it, not the one it actually
        // belongs to (`docs/ble-review-2026-08.md`, "Stability -- app", item 3).
        val h = Harness()
        h.transport.holdCommands = true

        h.fetcher.onCommandLine("NEED_TILES 1 fmt 2")
        assertEquals(listOf("missing"), h.transport.commands)
        assertEquals(TileFetcher.Phase.LISTING, h.fetcher.phase)

        // The rider presses the menu item again before that write answers.
        h.fetcher.onCommandLine("NEED_TILES 1 fmt 2")
        assertEquals(listOf("missing", "missing"), h.transport.commands)
        assertEquals(TileFetcher.Phase.LISTING, h.fetcher.phase)

        // The old write finally answers, and fails -- exactly what a dropped
        // write on a real link looks like.
        h.transport.answerHeldCommand(0, false, "fake failure")

        // The new run must still be alive: this failure belongs to the run
        // that was already replaced, not to the one now in flight.
        assertNull(h.recorder.finished)
        assertEquals(TileFetcher.Phase.LISTING, h.fetcher.phase)

        // And the new run's own write, once it answers, still drives things
        // normally.
        h.transport.answerHeldCommand(1, true, null)
        h.list(MissingTile(12, 1, 1, 1))
        assertEquals("done", h.recorder.finished)
        assertEquals(1, h.recorder.finalSkipped)
    }

    @Test
    fun `a restart mid-paging discards the old page's stale reply instead of feeding it to the new one`() {
        // The other half of the same defect: even when the write-callback
        // gate above works perfectly, a *successful* stale write still has
        // a whole reply coming. Firmware answers a `missing <offset>` whole
        // and uninterrupted once it has the command -- nothing else,
        // including the very NEED_TILES that triggers this restart, can
        // reach the wire ahead of it -- so its lines land after `page`
        // already belongs to the new generation, and nothing in them says
        // which generation they are for (`docs/ble-review-2026-08.md`,
        // "Stability -- app", item 3, "paging offsets desync").
        val h = Harness()

        h.fetcher.onCommandLine("NEED_TILES 2 fmt 2")
        h.fetcher.onCommandLine("INFO missing_total=2")
        h.fetcher.onCommandLine("INFO missing_offset=0")
        h.fetcher.onCommandLine("INFO missing_next=20")
        h.fetcher.onCommandLine("OK")
        assertEquals(listOf("missing", "missing 20"), h.transport.commands)

        // The rider presses the menu item again while the offset=20 page is
        // still outstanding.
        h.fetcher.onCommandLine("NEED_TILES 2 fmt 2")
        assertEquals(listOf("missing", "missing 20", "missing"), h.transport.commands)

        // The old page's reply arrives anyway: the device already had the
        // command and answers it in full, offset=20 and all, before this
        // side ever gets an answer to what it just asked instead.
        h.fetcher.onCommandLine("INFO missing_total=25")
        h.fetcher.onCommandLine("INFO missing_offset=20")
        h.fetcher.onCommandLine("INFO missing_13_9_9=1")
        h.fetcher.onCommandLine("INFO missing_next=done")
        h.fetcher.onCommandLine("OK")

        // Only now does the new listing's own reply start.
        h.list(MissingTile(12, 1, 1, 1))

        // The stale page's tile never reached the queue -- proven by it
        // never being asked about at all -- and the new listing's own
        // single tile is the only one skipped.
        assertTrue(h.transport.commands.none { it.contains("13 9 9") })
        assertEquals(listOf("12/1/1 skipped"), h.recorder.doneTiles)
        assertEquals("done", h.recorder.finished)
        assertEquals(1, h.recorder.finalSkipped)
    }

    @Test
    fun `paging follows missing_next until done`() {
        val h = Harness()
        h.fetcher.onCommandLine("NEED_TILES 2 fmt 2")

        h.fetcher.onCommandLine("INFO missing_total=2")
        h.fetcher.onCommandLine("INFO missing_offset=0")
        h.fetcher.onCommandLine("INFO missing_12_1_1=1")
        h.fetcher.onCommandLine("INFO missing_next=1")
        h.fetcher.onCommandLine("OK")
        assertEquals(listOf("missing", "missing 1"), h.transport.commands)

        h.fetcher.onCommandLine("INFO missing_total=2")
        h.fetcher.onCommandLine("INFO missing_offset=1")
        h.fetcher.onCommandLine("INFO missing_11_2_2=1")
        h.fetcher.onCommandLine("INFO missing_next=done")
        h.fetcher.onCommandLine("OK")

        // Both tiles were listed, neither is in the source, so both are skipped
        // -- which is what proves the second page's entry reached the queue.
        assertEquals(2, h.recorder.finalSkipped)
    }

    @Test
    fun `a view ask reads the viewport with tiles, not the whole missing list`() {
        val h = Harness()
        h.fetcher.onCommandLine("NEED_TILES 2 fmt 2 view")

        // The command that went out is what this whole feature is about: asking
        // for the whole 200-entry list mid-ride is the thing being avoided.
        assertEquals(listOf("tiles"), h.transport.commands)

        h.fetcher.onCommandLine("INFO tile_13_4496_2846=missing")
        h.fetcher.onCommandLine("INFO tile_13_4496_2847=ok")
        h.fetcher.onCommandLine("INFO tile_13_4497_2846=missing")
        h.fetcher.onCommandLine("OK")

        // Two missing, one already on the card. Neither missing tile is in the
        // source, so both are skipped -- which is what proves they were queued
        // and that the `ok` one was not.
        assertEquals(2, h.recorder.finalSkipped)
        assertEquals("done", h.recorder.finished)
    }

    @Test
    fun `a view ask never pages`() {
        val h = Harness()
        h.fetcher.onCommandLine("NEED_TILES 1 fmt 2 view")
        h.fetcher.onCommandLine("INFO tile_12_1_1=missing")
        h.fetcher.onCommandLine("OK")
        // No `missing` in the whole conversation. A second listing command would
        // be an offset the device never offered, and the fetch would hang on it
        // until the timeout. (`skip` lines are in here too -- the source has
        // nothing -- so this asserts about the listing commands, not the count.)
        assertTrue(h.transport.commands.none { it.startsWith("missing") })
        assertEquals("tiles", h.transport.commands.first())
    }

    @Test
    fun `without the view word the whole list is still read`() {
        val h = Harness()
        h.fetcher.onCommandLine("NEED_TILES 1 fmt 2")
        assertEquals(listOf("missing"), h.transport.commands)
    }

    @Test
    fun `a viewport with nothing missing finishes rather than waiting`() {
        val h = Harness()
        h.fetcher.onCommandLine("NEED_TILES 0 fmt 2 view")
        h.fetcher.onCommandLine("INFO tile_12_1_1=ok")
        h.fetcher.onCommandLine("OK")
        assertEquals("done", h.recorder.finished)
        assertEquals(0, h.recorder.finalSent)
    }

    @Test
    fun `a device with no viewport yet ends the fetch with its own words`() {
        val h = Harness()
        h.fetcher.onCommandLine("NEED_TILES 1 fmt 2 view")
        h.fetcher.onCommandLine("INFO tiles=none")
        h.fetcher.onCommandLine("OK")
        assertEquals("device has no viewport yet", h.recorder.finished)
    }

    @Test
    fun `a device with no missing list ends the fetch instead of hanging`() {
        val h = Harness()
        h.fetcher.onCommandLine("NEED_TILES 1 fmt 2")
        h.fetcher.onCommandLine("INFO missing=unavailable")
        h.fetcher.onCommandLine("OK")
        assertEquals("device has no missing-tile list", h.recorder.finished)
    }

    @Test
    fun `an empty list finishes immediately`() {
        val h = Harness()
        h.fetcher.onCommandLine("NEED_TILES 0 fmt 2")
        h.list()
        assertEquals("done", h.recorder.finished)
        assertEquals(0, h.recorder.finalSent)
    }

    @Test
    fun `a failed begin write skips the tile rather than stalling`() {
        val h = Harness()
        h.source.tiles["12/1/1"] = tileBytes(50)
        h.transport.failNextFrame = true

        h.fetcher.onCommandLine("NEED_TILES 1 fmt 2")
        h.list(MissingTile(12, 1, 1, 1))

        assertTrue(h.transport.commands.any { it.startsWith("skip 12 1 1") })
        assertEquals("done", h.recorder.finished)
    }

    /** A two-tile fetch where the first one stalls and is aborted locally. */
    private fun stalledFirstTile(): Harness {
        val h = Harness()
        h.source.tiles["12/1/1"] = tileBytes(50)
        h.source.tiles["11/2/2"] = tileBytes(50)

        h.fetcher.onCommandLine("NEED_TILES 2 fmt 2")
        h.list(MissingTile(12, 1, 1, 1), MissingTile(11, 2, 2, 1))

        // The first tile's `RDY` never comes. The local timeout aborts it, skips
        // it, and the next tile's begin is already out -- so from here on the
        // device owes a verdict for a transfer nothing is waiting for.
        h.scheduler.fire()
        assertEquals(1, h.transport.abortFrames().size)
        assertEquals(2, h.transport.beginFrames().size)
        assertEquals(listOf("12/1/1 skipped"), h.recorder.doneTiles)
        return h
    }

    @Test
    fun `a late OK from an aborted tile is not credited to the tile after it`() {
        val h = stalledFirstTile()

        // The device's verdict for the dead tile arrives after the new tile's
        // begin has gone out. Status lines carry no identity, so without the
        // generation gate this counts the new tile as landed and clears its
        // live state on the way out.
        h.fetcher.onStatusLine("OK 50 00000000")

        assertEquals(listOf("12/1/1 skipped"), h.recorder.doneTiles)
        assertEquals(0, h.transport.chunkFrames().size)
        assertNull(h.recorder.finished)

        // And the new tile still completes on its own lines: the gate skips a
        // generation, it does not deafen the channel.
        h.fetcher.onStatusLine("RDY 50")
        h.fetcher.onStatusLine("OK 50 00000000")
        assertEquals(listOf("12/1/1 skipped", "11/2/2 landed"), h.recorder.doneTiles)
        assertEquals("done", h.recorder.finished)
        assertEquals(1, h.recorder.finalSent)
        assertEquals(1, h.recorder.finalSkipped)
    }

    @Test
    fun `a late ERR aborted does not skip the tile after it`() {
        val h = stalledFirstTile()

        // `ERR aborted` is the device's answer to the abort frame
        // (MapTransferReceiver.cpp:119-124). It is about the dead tile, and
        // crediting it to the new one loses a tile the phone has in hand.
        h.fetcher.onStatusLine("ERR aborted")

        assertEquals(listOf("12/1/1 skipped"), h.recorder.doneTiles)
        assertEquals(1, h.transport.commands.count { it.startsWith("skip") })
        assertTrue(h.transport.commands.none { it.startsWith("skip 11 2 2") })
        assertNull(h.recorder.finished)

        h.fetcher.onStatusLine("RDY 50")
        h.fetcher.onStatusLine("OK 50 00000000")
        assertEquals(listOf("12/1/1 skipped", "11/2/2 landed"), h.recorder.doneTiles)
        assertEquals(1, h.recorder.finalSent)
        assertEquals(1, h.recorder.finalSkipped)
    }

    @Test
    fun `a late begin failure does not skip the tile after it`() {
        val h = Harness()
        h.source.tiles["12/1/1"] = tileBytes(50)
        h.source.tiles["11/2/2"] = tileBytes(50)
        // The first begin's write response is withheld -- the state the link is
        // in while the op queue holds a write that has not been answered.
        h.transport.holdBeginAcks = 1

        h.fetcher.onCommandLine("NEED_TILES 2 fmt 2")
        h.list(MissingTile(12, 1, 1, 1), MissingTile(11, 2, 2, 1))
        assertEquals(1, h.transport.beginFrames().size)

        // No `RDY` for the first tile either: the local timeout aborts it, skips
        // it, and the second tile's begin goes out. The first tile has now been
        // dealt with, once.
        h.scheduler.fire()
        assertEquals(listOf("12/1/1 skipped"), h.recorder.doneTiles)
        assertEquals(2, h.transport.beginFrames().size)

        // Only now does the stack answer the first begin -- with a failure, which
        // is exactly what BleLink's write timeout produces. Crediting it to
        // whatever is live would skip a tile the phone has in hand, kill its
        // transfer state and push the fetch on.
        h.transport.answerHeldBegins(false, "timeout")

        assertEquals(listOf("12/1/1 skipped"), h.recorder.doneTiles)
        assertEquals(1, h.transport.commands.count { it.startsWith("skip") })
        assertTrue(h.transport.commands.none { it.startsWith("skip 11 2 2") })
        assertEquals(2, h.transport.beginFrames().size)
        assertNull(h.recorder.finished)

        // And the second tile's own state is untouched: it still completes on its
        // own lines.
        h.fetcher.onStatusLine("RDY 50")
        h.fetcher.onStatusLine("OK 50 00000000")
        assertEquals(listOf("12/1/1 skipped", "11/2/2 landed"), h.recorder.doneTiles)
        assertEquals("done", h.recorder.finished)
        assertEquals(1, h.recorder.finalSent)
        assertEquals(1, h.recorder.finalSkipped)
    }

    @Test
    fun `an OK that beats the last chunk's write response still completes the tile`() {
        val h = Harness(payload = 100)
        val data = tileBytes(250)
        h.source.tiles["12/1/1"] = data
        // The indication and the write response race, and the `OK` can win
        // (`docs/ble-map-transfer-protocol.md`). Hold the last chunk's response
        // to reproduce that order.
        h.transport.holdChunkAcksFrom = 2

        h.fetcher.onCommandLine("NEED_TILES 1 fmt 2")
        h.list(MissingTile(12, 1, 1, 1))
        h.fetcher.onStatusLine("RDY 250")
        assertEquals(3, h.transport.chunkFrames().size)

        h.fetcher.onStatusLine("OK 250 ${"%08x".format(TransferFrames.crc32(data))}")
        assertEquals(listOf("12/1/1 landed"), h.recorder.doneTiles)
        assertEquals("done", h.recorder.finished)
        assertEquals(1, h.recorder.finalSent)

        // The held response lands after the tile is finished and the fetch is
        // over. The `tile !== current` guard in the chunk callback is what makes
        // that harmless: no fourth chunk, no reopened fetch.
        h.transport.ackHeldChunks()
        assertEquals(3, h.transport.chunkFrames().size)
        assertEquals(TileFetcher.Phase.IDLE, h.fetcher.phase)
        assertEquals(1, h.recorder.finalSent)
    }

    // --- read-ahead into the BLE pipeline -----------------------------------

    @Test
    fun `the next tile's read starts at this tile's RDY, not at its OK`() {
        // The defect this fixes: the source read for tile N+1 only started once
        // tile N was finished, so the link idled at HIGH priority for a whole
        // HTTPS GET between every pair of tiles -- 0.3-1.5 s of dead air with
        // both radios on (`docs/ble-review-2026-08.md`, "Performance").
        val h = Harness(payload = 100)
        h.source.tiles["12/1/1"] = tileBytes(250)
        h.source.tiles["12/1/2"] = tileBytes(250)

        h.fetcher.onCommandLine("NEED_TILES 2 fmt 2")
        h.list(MissingTile(12, 1, 1, 1), MissingTile(12, 1, 2, 1))

        // Only the tile actually going out has been read so far.
        assertEquals(listOf("12/1/1"), h.source.reads)
        assertEquals(1, h.transport.beginFrames().size)

        // The device accepted the begin frame. That is the moment tile 2 becomes
        // worth spending data on, and it is a whole transfer before tile 1's OK.
        h.fetcher.onStatusLine("RDY 250")
        assertEquals(listOf("12/1/1", "12/1/2"), h.source.reads)
        assertNull("tile 1 has not been answered yet", h.recorder.finished)
        assertEquals(3, h.transport.chunkFrames().size)

        // Only now does tile 1 finish, and tile 2's begin follows with no read
        // in between.
        h.fetcher.onStatusLine("OK 250 00000000")
        assertEquals(2, h.transport.beginFrames().size)
        assertEquals(listOf("12/1/1", "12/1/2"), h.source.reads)
    }

    @Test
    fun `the prefetched bytes are used instead of being read a second time`() {
        // Two tiles must cost two reads. A read-ahead that got thrown away and
        // re-fetched would still look correct on the wire and would double the
        // rider's data bill.
        val h = Harness(payload = 100)
        h.source.tiles["12/1/1"] = tileBytes(250)
        h.source.tiles["12/1/2"] = tileBytes(250)

        h.fetcher.onCommandLine("NEED_TILES 2 fmt 2")
        h.list(MissingTile(12, 1, 1, 1), MissingTile(12, 1, 2, 1))
        repeat(2) {
            h.fetcher.onStatusLine("RDY 250")
            h.fetcher.onStatusLine("OK 250 00000000")
        }

        assertEquals("done", h.recorder.finished)
        assertEquals(2, h.recorder.finalSent)
        assertEquals(0, h.recorder.finalSkipped)
        assertEquals(listOf("12/1/1", "12/1/2"), h.source.reads)
        // And the last tile's RDY did not start a read for a tile that is not
        // there: the queue was empty by then.
        assertEquals(2, h.source.reads.size)
    }

    @Test
    fun `a cancel between the read-ahead and its use drops the bytes`() {
        // FETCH_CANCEL is the rider pressing Back. Tens to hundreds of kB read
        // for the tile after the one in flight must not survive it -- neither as
        // a begin frame that goes out anyway, nor as bytes still held when some
        // later fetch asks for that same tile.
        val h = Harness(payload = 100)
        h.source.tiles["12/1/1"] = tileBytes(250)
        h.source.tiles["12/1/2"] = tileBytes(250)

        h.fetcher.onCommandLine("NEED_TILES 2 fmt 2")
        h.list(MissingTile(12, 1, 1, 1), MissingTile(12, 1, 2, 1))
        h.fetcher.onStatusLine("RDY 250")
        // Tile 2 is read and held, tile 1 is still going out.
        assertEquals(listOf("12/1/1", "12/1/2"), h.source.reads)
        assertEquals(1, h.transport.beginFrames().size)

        h.fetcher.onCommandLine("FETCH_CANCEL")
        assertEquals("cancelled on the device", h.recorder.finished)
        assertEquals(TileFetcher.Phase.IDLE, h.fetcher.phase)
        // No begin frame for the tile whose bytes were in hand.
        assertEquals(1, h.transport.beginFrames().size)

        // The slot is empty, not merely unused: a fresh fetch for that very tile
        // reads it again rather than being served from a fetch that is over.
        h.fetcher.pushTiles(listOf(MissingTile(12, 1, 2, 1)), 2)
        assertEquals(listOf("12/1/1", "12/1/2", "12/1/2"), h.source.reads)
        assertEquals(2, h.transport.beginFrames().size)
    }

    @Test
    fun `a read-ahead the fetch walked away from is dropped, and its tile read fresh`() {
        // The read-ahead is one tile deep and cannot be cancelled once it is an
        // HTTP GET in flight. When the fetch reaches that tile before the GET
        // lands, the tile is read again and the outstanding one is disowned --
        // it must not open a second transfer when it finally answers.
        val h = Harness(payload = 100)
        h.source.tiles["12/1/1"] = tileBytes(250)
        h.source.tiles["12/1/2"] = tileBytes(250)
        h.source.tiles["12/1/3"] = tileBytes(250)
        h.source.hold.add("12/1/2")

        h.fetcher.onCommandLine("NEED_TILES 3 fmt 2")
        h.list(MissingTile(12, 1, 1, 1), MissingTile(12, 1, 2, 1), MissingTile(12, 1, 3, 1))
        h.fetcher.onStatusLine("RDY 250")
        assertEquals(listOf("12/1/1", "12/1/2"), h.source.reads)

        // The device refuses tile 1. The fetch moves on to tile 2 while tile 2's
        // read-ahead is still out, so tile 2 is read fresh.
        h.fetcher.onStatusLine("ERR crc mismatch")
        assertTrue(h.transport.commands.any { it.startsWith("skip 12 1 1") })
        assertEquals(listOf("12/1/1", "12/1/2", "12/1/2"), h.source.reads)
        assertEquals(1, h.transport.beginFrames().size)

        // The abandoned read-ahead lands first. Nothing is waiting for it.
        h.source.release(0)
        assertEquals(1, h.transport.beginFrames().size)

        // The live read is what drives the tile, and the fetch runs out normally.
        h.source.release(1)
        assertEquals(2, h.transport.beginFrames().size)
        h.fetcher.onStatusLine("RDY 250")
        h.fetcher.onStatusLine("OK 250 00000000")
        h.fetcher.onStatusLine("RDY 250")
        h.fetcher.onStatusLine("OK 250 00000000")
        assertEquals("done", h.recorder.finished)
        assertEquals(2, h.recorder.finalSent)
        assertEquals(1, h.recorder.finalSkipped)
    }

    @Test
    fun `skipping the prefetched tile leaves nothing behind for the tile after it`() {
        // A read-ahead can turn out unusable: the format check runs on the bytes,
        // not on the listing, so the tile is consumed out of the slot and then
        // skipped. The slot has to be empty afterwards, and the tile behind it
        // read normally.
        val h = Harness(payload = 100)
        h.source.tiles["12/1/1"] = tileBytes(250)
        h.source.tiles["12/1/2"] = tileBytes(200, version = 3)
        h.source.tiles["12/1/3"] = tileBytes(250)

        h.fetcher.onCommandLine("NEED_TILES 3 fmt 2")
        h.list(MissingTile(12, 1, 1, 1), MissingTile(12, 1, 2, 1), MissingTile(12, 1, 3, 1))
        h.fetcher.onStatusLine("RDY 250")
        assertEquals(listOf("12/1/1", "12/1/2"), h.source.reads)

        h.fetcher.onStatusLine("OK 250 00000000")
        // The prefetched tile was used -- read exactly once -- found to be v3,
        // and skipped without a byte going out. Tile 3 is read fresh, because
        // nothing ever issued a read-ahead for it.
        assertTrue(h.transport.commands.contains("skip 12 1 2 ${TileFetcher.SKIP_WRONG_FORMAT}3"))
        assertEquals(listOf("12/1/1", "12/1/2", "12/1/3"), h.source.reads)
        assertEquals(2, h.transport.beginFrames().size)

        h.fetcher.onStatusLine("RDY 250")
        h.fetcher.onStatusLine("OK 250 00000000")
        assertEquals("done", h.recorder.finished)
        assertEquals(2, h.recorder.finalSent)
        assertEquals(1, h.recorder.finalSkipped)
    }

    private fun offsetOf(chunk: ByteArray): Int =
        (chunk[1].toInt() and 0xff) or
            ((chunk[2].toInt() and 0xff) shl 8) or
            ((chunk[3].toInt() and 0xff) shl 16) or
            ((chunk[4].toInt() and 0xff) shl 24)
}
