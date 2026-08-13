package org.explorink.gpsbridge

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

    @Test
    fun `a have listing that lost lines is answered unknown, never checked`() {
        // Measured on hardware 2026-08-13: the device answered `have` with
        // have_total=4 and four tile lines, one indication each, and the phone
        // received one of them. The check then reported "0 stale of 1" for a
        // viewport holding two out-of-date tiles, and the device believed it.
        val h = Harness()
        h.checker.onCommandLine("CHECK_TILES 4 fmt 3")
        h.checker.onCommandLine("INFO have_total=4")
        h.checker.onCommandLine("INFO have_11_1120_710=ec483e47")
        h.checker.onCommandLine("OK")

        assertEquals(listOf("have", "checked unknown"), h.transport.commands)
        assertEquals(0, h.index.reads.size)
        assertEquals(FreshnessChecker.Phase.IDLE, h.checker.phase)
    }

    @Test
    fun `a CHECK_TILES restart before the have write is acked does not let the stale write finish or lose the new listing's count`() {
        // The defect this guards against: a second CHECK_TILES restarts the
        // whole check while start()'s own `have` write is still
        // unacknowledged. Without a generation gate, that write's callback
        // has no idea a restart happened and calls finish() unconditionally
        // -- wiping the new listing's reader and whatever it had already
        // accumulated from its own `have` INFO lines
        // (`docs/ble-review-2026-08.md`, "Stability -- app", item 3).
        val h = Harness()
        h.transport.holdCommands = true
        h.index.put(13, 4482, 2839, slotB)

        h.checker.onCommandLine("CHECK_TILES 5")
        assertEquals(listOf("have"), h.transport.commands)
        assertEquals(FreshnessChecker.Phase.LISTING, h.checker.phase)

        // The device asks again before that write answers.
        h.checker.onCommandLine("CHECK_TILES 1")
        assertEquals(listOf("have", "have"), h.transport.commands)
        assertEquals(FreshnessChecker.Phase.LISTING, h.checker.phase)

        // The old write finally answers, and fails -- exactly what a
        // dropped write on a real link looks like.
        h.transport.answerHeldCommand(0, false, "fake failure")

        // The new listing must still be alive.
        assertEquals(FreshnessChecker.Phase.LISTING, h.checker.phase)

        // Its own reply now flows through normally and gets reported --
        // proof the stale callback did not reach in and finish or clear it.
        h.checker.onCommandLine("INFO have_total=1")
        h.checker.onCommandLine("INFO have_13_4482_2839=${"%08x".format(slotA)}")
        h.checker.onCommandLine("OK")
        assertEquals(listOf("have", "have", "stale 13 4482 2839", "checked 1"), h.transport.commands)
        assertEquals(slotB, h.expected.get(13, 4482, 2839))
        assertEquals(FreshnessChecker.Phase.IDLE, h.checker.phase)
    }

    @Test
    fun `a restart discards the old have reply instead of feeding it to the new one`() {
        // The other half of the same defect: even when the write-callback
        // gate above works perfectly, a *successful* stale write still has
        // a whole reply coming. Firmware answers `have` whole and
        // uninterrupted once it has the command -- nothing else, including
        // the very CHECK_TILES that triggers this restart, can reach the
        // wire ahead of it -- so its lines land after `reader` already
        // belongs to the new generation, and nothing in them says which
        // generation they are for (`docs/ble-review-2026-08.md`,
        // "Stability -- app", item 3).
        val h = Harness()
        h.transport.holdCommands = true
        h.index.put(13, 4482, 2839, slotB)

        h.checker.onCommandLine("CHECK_TILES 5")
        assertEquals(listOf("have"), h.transport.commands)

        // The device asks again while that write is still outstanding.
        h.checker.onCommandLine("CHECK_TILES 1")
        assertEquals(listOf("have", "have"), h.transport.commands)

        // The old write succeeds after all: the device did get it, and a
        // whole stale reply is now coming.
        h.transport.answerHeldCommand(0, true, null)

        // That stale reply arrives -- a tile that must never reach the new
        // listing's report.
        h.checker.onCommandLine("INFO have_total=1")
        h.checker.onCommandLine("INFO have_11_1_1=${"%08x".format(slotA)}")
        h.checker.onCommandLine("OK")

        // Only now does the new listing's own, unrelated reply start.
        h.checker.onCommandLine("INFO have_total=1")
        h.checker.onCommandLine("INFO have_13_4482_2839=${"%08x".format(slotA)}")
        h.checker.onCommandLine("OK")

        // The stale tile never reached the index or the report -- one read,
        // for the new listing's own tile only.
        assertEquals(1, h.index.reads.size)
        assertTrue(h.transport.commands.none { it.contains("11 1 1") })
        assertEquals(listOf("have", "have", "stale 13 4482 2839", "checked 1"), h.transport.commands)
        assertEquals(FreshnessChecker.Phase.IDLE, h.checker.phase)
    }

    @Test
    fun `CHECK_TILES states its own format version, no NEED_TILES required`() {
        // A device with nothing missing never sends NEED_TILES at all -- this
        // used to leave formatVersion null forever, falling back to a stale
        // default and checking the wrong /v<N>/ index tree for exactly these
        // devices.
        val h = Harness()
        h.index.put(13, 4482, 2839, slotA)
        h.checker.onCommandLine("CHECK_TILES 1 fmt 3")
        h.have(HeldTile(13, 4482, 2839, slotA))
        assertEquals(3, h.index.reads.single().formatVersion)
    }

    // --- fast link (T5.3) ----------------------------------------------------
    //
    // The listing runs at idle connection parameters otherwise: 9 indication
    // round trips at measured 688-1503 ms each = 6-14 s against the 15 s
    // reply timeout (`docs/ble-review-2026-08.md`, "Performance"). Every one
    // of these checks the link is fast for the ask and handed back exactly
    // once per exit -- a release that fires twice is as much a bug as one
    // that never fires, since the underlying link priority is not refcounted
    // (`BleLink.requestHighPriority`).

    @Test
    fun `fast link is asserted the moment the have listing starts`() {
        val h = Harness()
        h.checker.onCommandLine("CHECK_TILES 1")
        assertEquals(listOf(true), h.transport.fastLinkCalls)
    }

    @Test
    fun `fast link is released exactly once when the check succeeds`() {
        val h = Harness()
        h.index.put(13, 4482, 2839, slotA)
        h.check(HeldTile(13, 4482, 2839, slotA))

        assertEquals(listOf(true, false), h.transport.fastLinkCalls)
    }

    @Test
    fun `fast link is released exactly once when the have listing is truncated`() {
        // Same defect the "lost lines" test above guards against: a
        // count-mismatch answers `checked unknown`, not a verdict, and the
        // fast link still has to come back.
        val h = Harness()
        h.checker.onCommandLine("CHECK_TILES 4 fmt 3")
        h.checker.onCommandLine("INFO have_total=4")
        h.checker.onCommandLine("INFO have_11_1120_710=ec483e47")
        h.checker.onCommandLine("OK")

        assertEquals(listOf("have", "checked unknown"), h.transport.commands)
        assertEquals(listOf(true, false), h.transport.fastLinkCalls)
    }

    @Test
    fun `fast link is released exactly once when the device stops answering`() {
        val h = Harness()
        h.checker.onCommandLine("CHECK_TILES 1")
        h.scheduler.fire()

        assertEquals(FreshnessChecker.Phase.IDLE, h.checker.phase)
        assertEquals(listOf(true, false), h.transport.fastLinkCalls)
    }

    @Test
    fun `fast link is released exactly once when a hung index read times out`() {
        // The READING-phase timeout path -- a different armTimeout() branch
        // than the LISTING one above, both routed through the same finish().
        val h = Harness()
        h.index.hang = true
        h.checker.onCommandLine("CHECK_TILES 1")
        h.have(HeldTile(13, 4482, 2839, slotA))
        h.scheduler.fire()

        assertEquals(FreshnessChecker.Phase.IDLE, h.checker.phase)
        assertEquals(listOf(true, false), h.transport.fastLinkCalls)
    }

    @Test
    fun `fast link is released exactly once when the link drops mid-check`() {
        val h = Harness()
        h.checker.onCommandLine("CHECK_TILES 1")
        h.checker.onDisconnected()

        assertEquals(listOf(true, false), h.transport.fastLinkCalls)
    }

    @Test
    fun `a restart answered immediately releases the fast link the abandoned listing was holding`() {
        // The listing/reading phase this restart abandons never reaches
        // finish() -- start()'s own reset() clears it -- so if the new
        // CHECK_TILES answers immediately (nothing to check) instead of
        // reaching a new LISTING, the old run's fast link would otherwise
        // never come back.
        val h = Harness()
        h.checker.onCommandLine("CHECK_TILES 5")
        assertEquals(listOf(true), h.transport.fastLinkCalls)

        h.checker.onCommandLine("CHECK_TILES 0")
        assertEquals(listOf(true, false), h.transport.fastLinkCalls)
        assertEquals(FreshnessChecker.Phase.IDLE, h.checker.phase)
    }

    @Test
    fun `a restart into a fresh listing does not release the fast link in between`() {
        // The common restart case: reasserting true is a harmless no-op on
        // the link (same as TileFetcher's own restart), and must not be
        // preceded by a spurious release -- that would be a false "battery
        // saved" moment mid-check, not a real one.
        val h = Harness()
        h.checker.onCommandLine("CHECK_TILES 5")
        h.checker.onCommandLine("CHECK_TILES 1")

        assertEquals(listOf(true, true), h.transport.fastLinkCalls)
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

        /**
         * Command writes are queued instead of acked immediately once armed.
         * A second `CHECK_TILES` can restart the check before an earlier
         * `have` write's own response comes back, so that ordering has to be
         * testable.
         */
        var holdCommands = false
        private val heldCommands = mutableListOf<(Boolean, String?) -> Unit>()

        override fun sendCommand(line: String, done: (Boolean, String?) -> Unit) {
            commands.add(line)
            if (holdCommands) {
                heldCommands.add(done)
                return
            }
            done(true, null)
        }

        /** Answers the [index]th held command write, in the order it was sent. */
        fun answerHeldCommand(index: Int, ok: Boolean, error: String? = null) {
            heldCommands[index](ok, error)
        }

        override fun sendFrame(frame: ByteArray, done: (Boolean, String?) -> Unit) =
            done(true, null)

        override fun maxChunkPayload(): Int = 100

        /** Every `setFastLink` call, in order -- true is acquire, false release. */
        val fastLinkCalls = mutableListOf<Boolean>()

        override fun setFastLink(fast: Boolean) {
            fastLinkCalls.add(fast)
        }
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
