package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scanning the index for a whole box, with no network.
 *
 * Every case here is about keeping three answers apart. A tile that is really
 * sea must be given up on; a tile whose ground nobody has built must be waited
 * for; and a tile whose index this phone could not reach must be **neither**.
 * Fold the third into either of the others and the queue either retries the
 * Mediterranean forever or abandons a city because a lift blocked the signal.
 */
class IndexScannerTest {

    // A Barcelona z13 tile and its neighbour: same z7 block, same zoom, so one
    // span covers both (`docs/send-tiles-plan.md`, the 20 km box).
    private val a = TileRef(13, 4144, 3059)
    private val b = TileRef(13, 4145, 3059)

    @Test
    fun `a present slot comes back with its exact size`() {
        val h = Harness()
        h.index.put(a, contentId = 0xABB60454L, sizeBytes = 966_878L)
        h.scan(a)

        val found = h.reading(a) as TilePlan.Reading.Found
        assertTrue(found.slot.present)
        // Exact, not an estimate: verified byte for byte against the served
        // HTTP body for this very tile on 2026-09-02.
        assertEquals(966_878L, found.slot.sizeBytes)
        assertEquals(0xABB60454L, found.slot.contentId)
        assertTrue(h.summary!!.complete)
    }

    @Test
    fun `an empty slot in a real block is Found and not present`() {
        // The sea case. It is a *reading* like any other -- what makes it a
        // give-up is the area list, not the index.
        val h = Harness()
        h.index.block(a)
        h.scan(a)

        val found = h.reading(a) as TilePlan.Reading.Found
        assertFalse(found.slot.present)
        assertEquals(TilePlan.State.ABSENT, TilePlan.classify(found, groundIsBuilt = true))
        assertEquals(TilePlan.State.WAITING_BUILD, TilePlan.classify(found, groundIsBuilt = false))
    }

    @Test
    fun `a block that answers 404 makes every tile in the span NoBlock`() {
        // v4/base/index/7/60/44.idx answers 404 today: real, reachable ground
        // with no index block at all.
        val h = Harness()
        h.scan(a, b)

        assertEquals(TilePlan.Reading.NoBlock, h.reading(a))
        assertEquals(TilePlan.Reading.NoBlock, h.reading(b))
        assertTrue(h.summary!!.complete)
        assertEquals(0, h.summary!!.unreachable)
    }

    @Test
    fun `an unreachable span is never a verdict`() {
        val h = Harness()
        h.index.unreachable = true
        h.scan(a, b)

        assertEquals(TilePlan.Reading.Unreachable, h.reading(a))
        assertEquals(TilePlan.Reading.Unreachable, h.reading(b))
        assertEquals(TilePlan.State.UNKNOWN, TilePlan.classify(h.reading(a)!!, true))
        assertEquals(2, h.summary!!.unreachable)
        assertFalse(h.summary!!.complete)
    }

    @Test
    fun `an unreachable CDN cannot make a tile absent even where the ground is built`() {
        // The whole reason the third answer exists. Barcelona's ground is
        // published; a phone in a lift still must not conclude the city is sea.
        val h = Harness()
        h.index.unreachable = true
        h.scan(a)

        val outbox = TileOutbox()
        val zone = TileZone("z", "Barcelona 20 km", 413_874_000, 21_686_000, 20, 1_000L)
        outbox.addZone(zone, listOf(a))
        outbox.observe(a, h.reading(a)!!, groundIsBuilt = true, nowMs = 1_000L)

        assertEquals(TilePlan.State.UNKNOWN, outbox.items.single().cdn)
        assertEquals(TileState.RETRY, outbox.stateOf(outbox.items.single(), 1_000L))
    }

    @Test
    fun `a whole box of one zoom costs one read, not one per tile`() {
        // 202 tiles at 19.4 MB, and deciding what they cost is a handful of kB.
        val h = Harness()
        val tiles = (3059L..3062L).flatMap { row ->
            (4144L..4147L).map { col -> TileRef(13, col, row) }
        }
        tiles.forEach { h.index.put(it, contentId = 1L, sizeBytes = 100L) }
        h.scan(*tiles.toTypedArray())

        assertEquals(1, h.index.reads.size)
        assertEquals(16, h.summary!!.read)
    }

    @Test
    fun `three zooms of one block are three spans, read one at a time`() {
        // A block's planes are 4, 16 and 64 kB apart. One span across two zooms
        // would drag tens of kB of slots nobody asked about over mobile data.
        val h = Harness()
        val tiles = listOf(TileRef(11, 1036, 764), TileRef(12, 2072, 1529), a)
        tiles.forEach { h.index.put(it, contentId = 1L, sizeBytes = 100L) }
        h.scan(*tiles.toTypedArray())

        assertEquals(3, h.index.reads.size)
        assertEquals(listOf(11, 12, 13), h.index.reads.map { it.zoomAsked })
        // Sequenced, not fanned out: read N was made only after N-1 answers.
        assertEquals(listOf(0, 1, 2), h.index.reads.map { it.answeredBefore })
    }

    @Test
    fun `progress is reported as it goes, not once at the end`() {
        val h = Harness()
        val tiles = listOf(TileRef(11, 1036, 764), TileRef(12, 2072, 1529), a)
        tiles.forEach { h.index.put(it, contentId = 1L, sizeBytes = 100L) }
        h.scan(*tiles.toTypedArray())

        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), h.progress)
    }

    @Test
    fun `a cancel mid-scan keeps what was learned and stops reading`() {
        // The rider backs out of the plan screen. Two spans in, one to go.
        val h = Harness()
        val tiles = listOf(TileRef(11, 1036, 764), TileRef(12, 2072, 1529), a)
        tiles.forEach { h.index.put(it, contentId = 7L, sizeBytes = 100L) }
        h.cancelAfterTiles = 2

        h.scan(*tiles.toTypedArray())

        assertEquals(2, h.index.reads.size)
        assertEquals(2, h.readings.size)
        assertTrue(h.summary!!.cancelled)
        assertFalse(h.summary!!.complete)
        assertEquals(2, h.summary!!.read)
        assertEquals(3, h.summary!!.total)
        assertFalse(h.scanner.running)
        // Exactly one finish, and no progress line after it.
        assertEquals(1, h.finishes)
        assertEquals(listOf(1 to 3, 2 to 3), h.progress)
    }

    @Test
    fun `a cancel before anything answers still finishes exactly once`() {
        val h = Harness()
        h.index.hang = true
        h.scanner.start(listOf(a), null, h)
        assertTrue(h.scanner.running)

        h.scanner.cancel()
        assertEquals(1, h.finishes)
        assertTrue(h.summary!!.cancelled)
        assertFalse(h.scanner.running)

        // A second cancel is not a second finish.
        h.scanner.cancel()
        assertEquals(1, h.finishes)
    }

    @Test
    fun `an empty list finishes rather than leaving a scan that never ends`() {
        val h = Harness()
        h.scanner.start(emptyList(), null, h)
        assertEquals(1, h.finishes)
        assertEquals(0, h.summary!!.total)
        assertTrue(h.summary!!.complete)
        assertFalse(h.scanner.running)
    }

    @Test
    fun `the same tile asked for twice is read once`() {
        // Two zones round the same city share almost every square, and the
        // index does not answer differently the second time.
        val h = Harness()
        h.index.put(a, contentId = 1L, sizeBytes = 100L)
        h.scan(a, a, a)

        assertEquals(1, h.index.reads.size)
        assertEquals(1, h.summary!!.total)
    }

    @Test
    fun `a zoom the index has no plane for is reported, never answered hopefully`() {
        // TileBox only ever produces 11, 12 and 13, so this is a caller bug --
        // and there is no honest Reading for it. NoBlock would say "wait" and
        // Unreachable would say "ask again", and both retry forever.
        val h = Harness()
        val odd = TileRef(9, 259, 191)
        h.index.put(a, contentId = 1L, sizeBytes = 100L)
        h.scan(a, odd)

        assertEquals(listOf(odd), h.summary!!.unindexed)
        assertFalse(h.summary!!.complete)
        assertNull(h.reading(odd))
        assertEquals(1, h.index.reads.size)
    }

    @Test
    fun `a body shorter than the range asked for is unreachable, never a neighbour's slot`() {
        // A proxy that ignores Range is the live way this happens. Parsing at
        // the wrong offset would report another tile's size with a straight
        // face.
        val h = Harness()
        h.index.put(a, contentId = 1L, sizeBytes = 100L)
        h.index.truncateTo = 4
        h.scan(a)

        assertEquals(TilePlan.Reading.Unreachable, h.reading(a))
        assertEquals(1, h.summary!!.unreachable)
    }

    @Test
    fun `the index is read from the format version the device stated`() {
        val h = Harness()
        h.index.put(a, contentId = 1L, sizeBytes = 100L)
        h.scanner.start(listOf(a), 3, h)
        assertEquals(3, h.index.reads.single().formatVersion)
    }

    @Test
    fun `a restart drops the abandoned scan's late answer`() {
        val h = Harness()
        h.index.hang = true
        h.scanner.start(listOf(a), null, h)
        val stale = h.index.held.single()

        h.index.hang = false
        h.index.put(b, contentId = 5L, sizeBytes = 200L)
        h.scanner.start(listOf(b), null, h)

        // The first scan's read finally answers, for a scan that is over.
        stale(IndexSource.Result.Unreachable("late"))

        assertEquals(listOf(b.key), h.readings.keys.toList())
        assertEquals(1, h.finishes)
    }

    // --- harness --------------------------------------------------------------

    private inner class Harness : IndexScanner.Listener {
        val index = FakeIndex()
        val scanner = IndexScanner(index)

        val readings = LinkedHashMap<String, TilePlan.Reading>()
        val progress = mutableListOf<Pair<Int, Int>>()
        var summary: IndexScanner.Summary? = null
        var finishes = 0

        /** Cancel once this many tiles have answers. 0 means never. */
        var cancelAfterTiles = 0

        fun scan(vararg tiles: TileRef) = scanner.start(tiles.toList(), null, this)

        fun reading(t: TileRef): TilePlan.Reading? = readings[t.key]

        override fun onTilesRead(reads: List<IndexScanner.Read>) {
            reads.forEach { readings[it.tile.key] = it.reading }
        }

        override fun onScanProgress(read: Int, total: Int) {
            progress.add(read to total)
            if (cancelAfterTiles in 1..read) {
                cancelAfterTiles = 0
                scanner.cancel()
            }
        }

        override fun onScanFinished(summary: IndexScanner.Summary) {
            this.summary = summary
            finishes++
        }
    }

    /**
     * An index that answers from a map of published slots, completing inline.
     * The real one comes back on the main thread from a worker; the scanner only
     * requires "exactly once, on my thread".
     */
    private class FakeIndex : IndexSource {
        class Read(
            val relPath: String,
            val first: Int,
            val last: Int,
            val formatVersion: Int?,
            val answeredBefore: Int,
        ) {
            /**
             * Which zoom plane this range fell in, from its own offset. Reading
             * it back off the request is what makes "one span per zoom"
             * checkable without trusting the scanner's own bookkeeping.
             */
            val zoomAsked: Int = TileIndex.LOD_ZOOMS.first { z ->
                val base = TileIndex.HEADER_BYTES + TileIndex.planeBaseSlots(z) * TileIndex.SLOT_BYTES
                val side = TileIndex.side(z)
                first >= base && first < base + side * side * TileIndex.SLOT_BYTES
            }
        }

        val reads = mutableListOf<Read>()
        val held = mutableListOf<(IndexSource.Result) -> Unit>()

        /** Blocks that exist, whether or not any slot in them is set. */
        private val blocks = HashSet<String>()
        private val slots = HashMap<String, Pair<Long, Long>>()

        var unreachable = false
        var hang = false

        /** Answer with this many bytes instead of the range asked for. -1 is off. */
        var truncateTo = -1

        /** How many reads had answered when each read was made. */
        var answered = 0

        fun block(t: TileRef) {
            blocks.add(TileIndex.blockRelPath(TileIndex.blockCol(t.z, t.col), TileIndex.blockRow(t.z, t.row)))
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
            reads.add(Read(relPath, first, last, formatVersion, answered))
            if (hang) {
                held.add(done)
                return
            }
            val result = when {
                unreachable -> IndexSource.Result.Unreachable("test")
                relPath !in blocks -> IndexSource.Result.NotPublished
                else -> IndexSource.Result.Bytes(body(relPath, first, last))
            }
            answered++
            done(result)
        }

        private fun body(relPath: String, first: Int, last: Int): ByteArray {
            val buf = ByteArray(if (truncateTo >= 0) truncateTo else last - first + 1)
            for ((key, v) in slots) {
                val (z, col, row) = key.split('/').let {
                    Triple(it[0].toInt(), it[1].toLong(), it[2].toLong())
                }
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
