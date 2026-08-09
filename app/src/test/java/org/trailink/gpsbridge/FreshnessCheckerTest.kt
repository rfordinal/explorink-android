package org.trailink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The freshness exchange, end to end, with no BLE and no network.
 *
 * The cases that matter are the ones where the honest answer is "I do not
 * know": every one of them must produce `checked unknown` and no `stale` line
 * the phone cannot stand behind. A false "stale" costs a rider their viewport
 * over a 7 kB/s link to replace tiles that were already right.
 */
class FreshnessCheckerTest {

    private val slotA = 0xABB60454L
    private val slotB = 0x1C316331L

    @Test
    fun `a tile whose content id matches is not reported stale`() {
        val h = Harness()
        h.index.put(13, 4482, 2839, slotA)
        h.check(HeldTile(13, 4482, 2839, slotA))

        assertTrue(h.transport.commands.none { it.startsWith("stale") })
        assertEquals("checked 0", h.transport.commands.last())
    }

    @Test
    fun `a tile whose content id differs is reported once, with the expected id kept`() {
        val h = Harness()
        h.index.put(13, 4482, 2839, slotB)
        h.check(HeldTile(13, 4482, 2839, slotA))

        assertEquals(listOf("have", "stale 13 4482 2839", "checked 1"), h.transport.commands)
        // The fetch that follows has to ask the CDN for *this* version, or the
        // edge serves the copy being replaced and the device asks forever.
        assertEquals(slotB, h.expected.get(13, 4482, 2839))
    }

    @Test
    fun `a slot with no tile behind it is not stale`() {
        // The CDN publishes nothing here. The device holds a tile nobody vouches
        // for -- there is nothing to fetch, so there is nothing to say.
        val h = Harness()
        h.check(HeldTile(13, 4482, 2839, slotA))

        assertTrue(h.transport.commands.none { it.startsWith("stale") })
        assertEquals("checked 0", h.transport.commands.last())
        assertNull(h.expected.get(13, 4482, 2839))
    }

    @Test
    fun `a whole viewport costs one range read, not one per tile`() {
        val h = Harness()
        val tiles = (2839L..2842L).flatMap { row ->
            (4482L..4485L).map { col -> HeldTile(13, col, row, slotA) }
        }
        tiles.forEach { h.index.put(it.z, it.col, it.row, slotA) }
        h.check(*tiles.toTypedArray())

        assertEquals(1, h.index.reads.size)
        assertEquals("checked 0", h.transport.commands.last())
    }

    @Test
    fun `an unreachable CDN answers unknown, never stale`() {
        val h = Harness()
        h.index.unreachable = true
        h.check(HeldTile(13, 4482, 2839, slotA))

        assertTrue(h.transport.commands.none { it.startsWith("stale") })
        assertEquals("checked unknown", h.transport.commands.last())
    }

    @Test
    fun `a partly readable check reports what it knows and still says unknown`() {
        // One block answers, the other does not. The stale tile it did find is
        // certain and worth sending; the verdict is still incomplete.
        val h = Harness()
        h.index.put(13, 4482, 2839, slotB)
        h.index.failFrom = 1
        h.check(HeldTile(13, 4482, 2839, slotA), HeldTile(13, 4546, 2839, slotA))

        assertTrue(h.transport.commands.contains("stale 13 4482 2839"))
        assertEquals("checked unknown", h.transport.commands.last())
    }

    @Test
    fun `an offline phone is not asked again until the backoff expires`() {
        val h = Harness()
        h.index.unreachable = true
        h.check(HeldTile(13, 4482, 2839, slotA))
        assertEquals(1, h.index.reads.size)

        // Same second: refused outright, still answered, no HTTP attempted.
        h.transport.commands.clear()
        h.checker.onCommandLine("CHECK_TILES 1")
        assertEquals(listOf("checked unknown"), h.transport.commands)
        assertEquals(1, h.index.reads.size)

        // Past the window, it tries again -- an outage must not be permanent.
        h.nowMs += FreshnessChecker.BACKOFF_START_MS + 1
        h.transport.commands.clear()
        h.check(HeldTile(13, 4482, 2839, slotA))
        assertEquals(2, h.index.reads.size)
    }

    @Test
    fun `a device with no viewport says so and gets no verdict`() {
        val h = Harness()
        h.checker.onCommandLine("CHECK_TILES 4")
        h.checker.onCommandLine("INFO have=none")

        // "Cannot answer" is not "everything is current": nothing is claimed.
        assertEquals(listOf("have"), h.transport.commands)
        assertEquals(FreshnessChecker.Phase.IDLE, h.checker.phase)
    }

    @Test
    fun `nothing to check is answered without asking for a list`() {
        val h = Harness()
        h.checker.onCommandLine("CHECK_TILES 0")
        assertEquals(listOf("checked 0"), h.transport.commands)
    }

    @Test
    fun `a device that stops answering ends the check instead of hanging`() {
        val h = Harness()
        h.checker.onCommandLine("CHECK_TILES 1")
        h.scheduler.fire()
        assertEquals(FreshnessChecker.Phase.IDLE, h.checker.phase)
    }

    @Test
    fun `a hung index read ends as unknown`() {
        val h = Harness()
        h.index.hang = true
        h.checker.onCommandLine("CHECK_TILES 1")
        h.have(HeldTile(13, 4482, 2839, slotA))
        h.scheduler.fire()

        assertEquals("checked unknown", h.transport.commands.last())
        assertEquals(FreshnessChecker.Phase.IDLE, h.checker.phase)
    }

    @Test
    fun `a dropped link ends the check silently`() {
        val h = Harness()
        h.checker.onCommandLine("CHECK_TILES 1")
        h.checker.onDisconnected()
        assertEquals(FreshnessChecker.Phase.IDLE, h.checker.phase)
        assertEquals(listOf("have"), h.transport.commands)
    }

    @Test
    fun `the index is read from the format version the device stated`() {
        val h = Harness()
        h.checker.onCommandLine("NEED_TILES 2 fmt 3")
        h.index.put(13, 4482, 2839, slotA)
        h.check(HeldTile(13, 4482, 2839, slotA))
        assertEquals(3, h.index.reads.single().formatVersion)
    }

    // --- harness ------------------------------------------------------------

    private class Harness {
        val transport = FakeTransport()
        val scheduler = FakeScheduler()
        val index = FakeIndex()
        val expected = ExpectedContentIds()
        var nowMs = 1_000L
        val checker = FreshnessChecker(index, expected, transport, scheduler).also {
            it.nowMs = { nowMs }
        }

        /** The device's reply to `have`, terminated. */
        fun have(vararg tiles: HeldTile) {
            checker.onCommandLine("INFO have_total=${tiles.size}")
            tiles.forEach {
                checker.onCommandLine("INFO have_${it.z}_${it.col}_${it.row}=%08x".format(it.contentId))
            }
            checker.onCommandLine("OK")
        }

        /** A whole exchange: the device asks, answers, and gets its verdict. */
        fun check(vararg tiles: HeldTile) {
            checker.onCommandLine("CHECK_TILES ${tiles.size}")
            have(*tiles)
        }
    }

    /**
     * An index that answers from a map of published content ids, completing
     * inline. The real one comes back on the main thread from a worker; the
     * checker only requires "exactly once, on my thread".
     */
    private class FakeIndex : IndexSource {
        class Read(val relPath: String, val first: Int, val last: Int, val formatVersion: Int?)

        val reads = mutableListOf<Read>()
        private val published = mutableMapOf<String, Long>()

        /** Every read fails as if there were no network. */
        var unreachable = false

        /** Reads from this index onward fail; earlier ones answer normally. */
        var failFrom = Int.MAX_VALUE

        /** Never completes -- the callback is simply never invoked. */
        var hang = false

        fun put(z: Int, col: Long, row: Long, contentId: Long) {
            published["$z/$col/$row"] = contentId
        }

        override fun readRange(
            relPath: String,
            first: Int,
            last: Int,
            formatVersion: Int?,
            done: (IndexSource.Result) -> Unit,
        ) {
            val n = reads.size
            reads.add(Read(relPath, first, last, formatVersion))
            if (hang) return
            if (unreachable || n >= failFrom) {
                done(IndexSource.Result.Unreachable("test"))
                return
            }
            val buf = ByteArray(last - first + 1)
            for ((key, contentId) in published) {
                val (z, col, row) = key.split('/').let {
                    Triple(it[0].toInt(), it[1].toLong(), it[2].toLong())
                }
                if (TileIndex.blockRelPath(TileIndex.blockCol(z, col), TileIndex.blockRow(z, row)) != relPath) {
                    continue
                }
                val at = TileIndex.slotOffset(z, col, row) - first
                if (at < 0 || at + TileIndex.SLOT_BYTES > buf.size) continue
                buf[at] = (1 or (TileIndex.COVERAGE_FULL shl 1)).toByte()
                for (i in 0 until 4) buf[at + 2 + i] = ((contentId shr (8 * i)) and 0xff).toByte()
            }
            done(IndexSource.Result.Bytes(buf))
        }
    }

    private class FakeTransport : TileFetcher.Transport {
        val commands = mutableListOf<String>()

        override fun sendCommand(line: String, done: (Boolean, String?) -> Unit) {
            commands.add(line)
            done(true, null)
        }

        override fun sendFrame(frame: ByteArray, done: (Boolean, String?) -> Unit) =
            done(true, null)

        override fun maxChunkPayload(): Int = 100

        override fun setFastLink(fast: Boolean) = Unit
    }

    private class FakeScheduler : TileFetcher.Scheduler {
        private var pending: (() -> Unit)? = null

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
}
