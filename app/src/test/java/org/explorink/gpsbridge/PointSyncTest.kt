package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The point-shard sync conversation end to end, with BLE and time replaced by
 * fakes -- same shape as [TileFetcherTest], for the reasons [PointSync]'s own
 * doc gives: this runs unattended against a device and a CDN that can both
 * misbehave mid-sequence, so the interesting cases are the ugly ones.
 */
class PointSyncTest {

    /** A shard with a valid header declaring [version] at its documented offset. */
    private fun shardBytes(size: Int, version: Int = 1): ByteArray {
        val out = ByteArray(size)
        "TIP1".toByteArray().copyInto(out)
        out[4] = (version and 0xff).toByte()
        out[5] = ((version shr 8) and 0xff).toByte()
        for (i in 6 until size) out[i] = (i and 0xff).toByte()
        return out
    }

    private class FakeSource : PointSync.PointSource {
        val shards = mutableMapOf<String, ByteArray>()

        /** Keys that answer 404 instead of whatever [shards] holds for them. */
        val notFound = mutableSetOf<String>()

        /** Keys that answer a non-404 failure. */
        val failing = mutableSetOf<String>()

        val reads = mutableListOf<String>()

        override fun read(col: Long, row: Long, done: (PointSync.ReadResult) -> Unit) {
            val key = "$col/$row"
            reads.add(key)
            when {
                key in notFound -> done(PointSync.ReadResult.NotFound)
                key in failing -> done(PointSync.ReadResult.Failed)
                else -> {
                    val bytes = shards[key]
                    if (bytes != null) done(PointSync.ReadResult.Bytes(bytes)) else done(PointSync.ReadResult.NotFound)
                }
            }
        }
    }

    /** Two 404s before it says yes, matching decision 3's rule -- but a test can inspect the streak directly. */
    private class FakeNotFoundTracker : PointSync.NotFoundTracker {
        private val streak = mutableMapOf<String, Int>()

        override fun recordNotFound(col: Long, row: Long): Boolean {
            val key = "$col/$row"
            val n = (streak[key] ?: 0) + 1
            streak[key] = n
            return n >= 2
        }

        override fun recordFound(col: Long, row: Long) {
            streak.remove("$col/$row")
        }
    }

    /** Records every write and completes it immediately, as a healthy link does. */
    private class FakeTransport(private val payload: Int = 100) : PointSync.Transport {
        val commands = mutableListOf<String>()
        val frames = mutableListOf<ByteArray>()
        var failNextFrame = false
        var failNextCommand = false

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

        fun answerHeldCommand(index: Int, ok: Boolean, error: String? = null) {
            heldCommands[index](ok, error)
        }

        override fun sendFrame(frame: ByteArray, done: (Boolean, String?) -> Unit) {
            frames.add(frame)
            if (failNextFrame) {
                failNextFrame = false
                done(false, "fake failure")
                return
            }
            done(true, null)
        }

        override fun maxChunkPayload(): Int = payload

        var fastLinkHeld = false
        override fun setFastLink(fast: Boolean) {
            fastLinkHeld = fast
        }

        fun chunkFrames(): List<ByteArray> = frames.filter { it[0] == TransferFrames.OP_CHUNK }
        fun beginFrames(): List<ByteArray> = frames.filter { it[0] == TransferFrames.OP_BEGIN }
        fun abortFrames(): List<ByteArray> = frames.filter { it[0] == TransferFrames.OP_ABORT }
    }

    private class FakeScheduler : PointSync.Scheduler {
        var pending: (() -> Unit)? = null

        override fun postDelayed(delayMs: Long, action: () -> Unit): PointSync.Scheduler.Cancellable {
            pending = action
            return object : PointSync.Scheduler.Cancellable {
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

    private class Recorder : PointSync.Listener {
        var started = -1
        val progress = mutableListOf<Triple<Int, Int, Int>>()
        var finished: String? = null
        var finalPushed = -1
        var finalSkipped = -1
        var finalGone = -1

        val doneShards = mutableListOf<String>()

        override fun onShardDone(col: Long, row: Long, bytes: Int, ok: Boolean, detail: String) {
            doneShards.add("$col/$row ${if (ok) "landed" else detail.ifEmpty { "skipped" }}")
        }

        override fun onSyncStarted(total: Int) {
            started = total
        }

        override fun onSyncProgress(pushed: Int, skipped: Int, gone: Int, total: Int) {
            progress.add(Triple(pushed, skipped, gone))
        }

        override fun onSyncFinished(pushed: Int, skipped: Int, gone: Int, total: Int, reason: String) {
            finished = reason
            finalPushed = pushed
            finalSkipped = skipped
            finalGone = gone
        }
    }

    private class Harness(payload: Int = 100, notFound: PointSync.NotFoundTracker = PointSync.NotFoundTracker.None) {
        val source = FakeSource()
        val transport = FakeTransport(payload)
        val scheduler = FakeScheduler()
        val recorder = Recorder()
        val sync = PointSync(source, transport, scheduler, recorder, notFound)

        /** One reply to `points`, exactly [shards], terminated. */
        fun list(vararg shards: PointSync.ShardStatus) {
            sync.onCommandLine("INFO point_total=${shards.size}")
            shards.forEach { sync.onCommandLine("INFO point_${it.col}_${it.row}=${if (it.have) "have" else "absent"}") }
            sync.onCommandLine("OK")
        }
    }

    // --- parsing ----------------------------------------------------------

    @Test
    fun `NEED_POINTS carries the count and the format version`() {
        val h = Harness()
        assertEquals(PointSync.NeedPoints(4, 1), h.sync.parseNeedPoints("NEED_POINTS 4 fmt 1"))
        assertEquals(PointSync.NeedPoints(0, null), h.sync.parseNeedPoints("NEED_POINTS 0"))
        assertNull(h.sync.parseNeedPoints("NEED_TILES 4 fmt 1"))
    }

    @Test
    fun `ListReader tells have from absent and reports the two refusals`() {
        val r = PointSync.ListReader()
        assertTrue(r.feed("INFO point_total=2"))
        assertTrue(r.feed("INFO point_562_354=have"))
        assertTrue(r.feed("INFO point_562_355=absent"))
        assertTrue(r.feed("OK"))
        assertTrue(r.complete)
        assertEquals(2, r.total)
        assertEquals(
            listOf(PointSync.ShardStatus(562, 354, true), PointSync.ShardStatus(562, 355, false)),
            r.shards,
        )

        val unavailable = PointSync.ListReader()
        assertTrue(unavailable.feed("INFO points=unavailable"))
        assertTrue(unavailable.unavailable)

        val noFix = PointSync.ListReader()
        assertTrue(noFix.feed("INFO points=no_position"))
        assertTrue(noFix.noPosition)
    }

    // --- the happy path -----------------------------------------------------

    @Test
    fun `have shards are left alone, absent ones are pushed`() {
        val h = Harness()
        h.source.shards["562/355"] = shardBytes(250)

        h.sync.onCommandLine("NEED_POINTS 2 fmt 1")
        assertEquals(listOf("points"), h.transport.commands)

        h.list(PointSync.ShardStatus(562, 354, have = true), PointSync.ShardStatus(562, 355, have = false))
        assertEquals(1, h.recorder.started)  // only the absent one needs pushing
        assertEquals(listOf("562/355"), h.source.reads)  // never read the one it already has

        assertEquals(1, h.transport.beginFrames().size)
        val begin = h.transport.beginFrames()[0]
        // begin frame: opcode, u32 len, u32 crc, u8 pathLen, path bytes
        val pathLen = begin[9].toInt()
        val path = String(begin, 10, pathLen, Charsets.UTF_8)
        assertEquals("points/10/562/355.tip", path)

        h.sync.onStatusLine("RDY 250")
        h.sync.onStatusLine("OK 250 ${Integer.toHexString(TransferFrames.crc32(h.source.shards["562/355"]!!).toInt())}")

        assertEquals("done", h.recorder.finished)
        assertEquals(1, h.recorder.finalPushed)
        assertEquals(0, h.recorder.finalSkipped)
        assertEquals(0, h.recorder.finalGone)
        assertEquals(listOf("562/355 landed"), h.recorder.doneShards)
    }

    @Test
    fun `a source failure is a plain skip, never gone`() {
        val h = Harness()
        h.source.failing.add("562/354")

        h.sync.onCommandLine("NEED_POINTS 1 fmt 1")
        h.list(PointSync.ShardStatus(562, 354, have = false))

        assertEquals(listOf("points", "skip 10 562 354 nosource"), h.transport.commands)
        assertEquals("done", h.recorder.finished)
        assertEquals(1, h.recorder.finalSkipped)
        assertEquals(0, h.recorder.finalGone)
        assertEquals(0, h.transport.beginFrames().size)
    }

    @Test
    fun `a wrong format version is skipped, never pushed`() {
        val h = Harness()
        h.source.shards["562/354"] = shardBytes(120, version = 2)

        h.sync.onCommandLine("NEED_POINTS 1 fmt 1")
        h.list(PointSync.ShardStatus(562, 354, have = false))

        assertEquals(listOf("points", "skip 10 562 354 fmt2"), h.transport.commands)
        assertEquals(0, h.transport.beginFrames().size)
        assertEquals("done", h.recorder.finished)
    }

    @Test
    fun `the device refusing a begin is a skip too`() {
        val h = Harness()
        h.source.shards["562/354"] = shardBytes(100)

        h.sync.onCommandLine("NEED_POINTS 1 fmt 1")
        h.list(PointSync.ShardStatus(562, 354, have = false))
        assertEquals(1, h.transport.beginFrames().size)

        h.sync.onStatusLine("ERR bad_path")
        assertEquals(listOf("points", "skip 10 562 354 refused"), h.transport.commands)
        assertEquals("done", h.recorder.finished)
        assertEquals(1, h.recorder.finalSkipped)
    }

    // --- decision 3: gone needs two 404s in two separate syncs --------------

    @Test
    fun `the first 404 is a skip, the second one -- a new sync later -- is gone`() {
        val tracker = FakeNotFoundTracker()

        val first = Harness(notFound = tracker)
        first.source.notFound.add("562/354")
        first.sync.onCommandLine("NEED_POINTS 1 fmt 1")
        first.list(PointSync.ShardStatus(562, 354, have = false))
        assertEquals(listOf("points", "skip 10 562 354 nosource"), first.transport.commands)
        assertEquals(0, first.recorder.finalGone)

        // A later sync session, same tracker (as a real one persists across
        // sessions) -- the shard is still missing on the CDN.
        val second = Harness(notFound = tracker)
        second.source.notFound.add("562/354")
        second.sync.onCommandLine("NEED_POINTS 1 fmt 1")
        second.list(PointSync.ShardStatus(562, 354, have = false))
        assertEquals(listOf("points", "gone 562 354"), second.transport.commands)
        assertEquals(1, second.recorder.finalGone)
        assertEquals(0, second.recorder.finalSkipped)
    }

    @Test
    fun `a read that comes back with bytes clears the shard's 404 streak`() {
        val tracker = FakeNotFoundTracker()

        val first = Harness(notFound = tracker)
        first.source.notFound.add("562/354")
        first.sync.onCommandLine("NEED_POINTS 1 fmt 1")
        first.list(PointSync.ShardStatus(562, 354, have = false))
        assertEquals(listOf("points", "skip 10 562 354 nosource"), first.transport.commands)

        // The shard reappeared before the second 404 -- the streak must not
        // carry over as if it had not.
        val second = Harness(notFound = tracker)
        second.source.shards["562/354"] = shardBytes(80)
        second.sync.onCommandLine("NEED_POINTS 1 fmt 1")
        second.list(PointSync.ShardStatus(562, 354, have = false))
        second.sync.onStatusLine("RDY 80")
        second.sync.onStatusLine("OK 80 ${Integer.toHexString(TransferFrames.crc32(second.source.shards["562/354"]!!).toInt())}")

        val third = Harness(notFound = tracker)
        third.source.notFound.add("562/354")
        third.sync.onCommandLine("NEED_POINTS 1 fmt 1")
        third.list(PointSync.ShardStatus(562, 354, have = false))
        // Streak reset by the successful read above -- this is a first 404 again.
        assertEquals(listOf("points", "skip 10 562 354 nosource"), third.transport.commands)
    }

    // --- restart and teardown ------------------------------------------------

    @Test
    fun `a second NEED_POINTS mid-listing owes the stale reply to itself`() {
        val h = Harness()
        h.sync.onCommandLine("NEED_POINTS 2 fmt 1")
        h.sync.onCommandLine("INFO point_total=2")
        // Restart before the first listing's OK arrived.
        h.sync.onCommandLine("NEED_POINTS 1 fmt 1")
        assertEquals(listOf("points", "points"), h.transport.commands)

        // The stale reply's own tail must not be read as the new listing's.
        h.sync.onCommandLine("INFO point_562_354=absent")
        h.sync.onCommandLine("OK")  // closes the STALE reply, not the new one
        assertEquals(PointSync.Phase.LISTING, h.sync.phase)

        h.list(PointSync.ShardStatus(562, 354, have = false))
        assertEquals(1, h.recorder.started)
    }

    @Test
    fun `disconnecting mid-transfer ends the sync`() {
        val h = Harness()
        h.source.shards["562/354"] = shardBytes(100)
        h.sync.onCommandLine("NEED_POINTS 1 fmt 1")
        h.list(PointSync.ShardStatus(562, 354, have = false))
        assertEquals(1, h.transport.beginFrames().size)

        h.sync.onDisconnected()
        assertEquals("link lost", h.recorder.finished)
        assertFalse(h.transport.fastLinkHeld)
    }

    @Test
    fun `stop aborts whatever is in flight`() {
        val h = Harness()
        h.source.shards["562/354"] = shardBytes(100)
        h.sync.onCommandLine("NEED_POINTS 1 fmt 1")
        h.list(PointSync.ShardStatus(562, 354, have = false))

        h.sync.stop()
        assertEquals(1, h.transport.abortFrames().size)
        assertEquals("stopped", h.recorder.finished)
    }

    @Test
    fun `a stalled transfer times out, is skipped, and the sync moves on`() {
        val h = Harness()
        h.source.shards["562/354"] = shardBytes(100)
        h.sync.onCommandLine("NEED_POINTS 1 fmt 1")
        h.list(PointSync.ShardStatus(562, 354, have = false))
        h.sync.onStatusLine("RDY 100")

        h.scheduler.fire()
        assertEquals(1, h.transport.abortFrames().size)
        assertEquals(listOf("points", "skip 10 562 354 refused"), h.transport.commands)
        assertEquals("done", h.recorder.finished)
    }
}
