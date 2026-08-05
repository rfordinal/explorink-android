package org.trailink.gpsbridge

import org.junit.Assert.assertEquals
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
        override fun read(z: Int, col: Long, row: Long): ByteArray? = tiles["$z/$col/$row"]
        override fun describe(): String = "fake"
    }

    /** Records every write and completes it immediately, as a healthy link does. */
    private class FakeTransport(private val payload: Int = 100) : TileFetcher.Transport {
        val commands = mutableListOf<String>()
        val frames = mutableListOf<ByteArray>()
        var failNextFrame = false
        var failNextCommand = false

        override fun sendCommand(line: String, done: (Boolean, String?) -> Unit) {
            commands.add(line)
            if (failNextCommand) {
                failNextCommand = false
                done(false, "fake failure")
            } else {
                done(true, null)
            }
        }

        override fun sendFrame(frame: ByteArray, done: (Boolean, String?) -> Unit) {
            frames.add(frame)
            if (failNextFrame) {
                failNextFrame = false
                done(false, "fake failure")
            } else {
                done(true, null)
            }
        }

        override fun maxChunkPayload(): Int = payload

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
    fun `losing the link ends the fetch`() {
        val h = Harness()
        h.fetcher.onCommandLine("NEED_TILES 3 fmt 2")
        h.fetcher.onDisconnected()
        assertEquals("link lost", h.recorder.finished)
        assertEquals(TileFetcher.Phase.IDLE, h.fetcher.phase)
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

    private fun offsetOf(chunk: ByteArray): Int =
        (chunk[1].toInt() and 0xff) or
            ((chunk[2].toInt() and 0xff) shl 8) or
            ((chunk[3].toInt() and 0xff) shl 16) or
            ((chunk[4].toInt() and 0xff) shl 24)
}
