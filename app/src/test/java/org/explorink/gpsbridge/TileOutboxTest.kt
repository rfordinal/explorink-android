package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue that has to survive an app kill, a phone reboot and a dropped link.
 *
 * Every interesting case here is one of the ugly ones, because the ordinary path
 * -- tile goes out, device says OK -- is the one nobody loses data on. What the
 * rider loses data on is a receipt that does not match, a tile the CDN has not
 * built yet, a square of sea retried for ever, and a process that died halfway
 * through a 30 minute transfer.
 */
class TileOutboxTest {

    private val t0 = 1_756_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute

    private val a = TileRef(13, 4144, 3059)
    private val b = TileRef(13, 4145, 3059)
    private val c = TileRef(13, 4146, 3061)

    private fun zone(id: String = "z1", atMs: Long = t0) =
        TileZone(id, "Barcelona 20 km", 413_874_000, 21_686_000, 20, atMs)

    private fun present(size: Long, contentId: Long = 0xabcdL) =
        TilePlan.Reading.Found(TileIndex.Slot(true, TileIndex.COVERAGE_FULL, contentId, 1_786_201_179L, size))

    private val emptySlot = TilePlan.Reading.Found(TileIndex.Slot.ABSENT)

    /** One zone, three tiles, all three known present. */
    private fun readyOutbox(): TileOutbox {
        val box = TileOutbox()
        box.addZone(zone(), listOf(a, b, c))
        box.observe(a, present(966_878L), true, t0)
        box.observe(b, present(1_008_547L), true, t0)
        box.observe(c, present(809_034L), true, t0)
        return box
    }

    /** Sends [tile] all the way through, the way a transport does. */
    private fun send(box: TileOutbox, tile: TileRef, bytes: Long, crc: Long, atMs: Long): Boolean {
        val item = box.takeNext(atMs)
        assertEquals(tile, item?.tile)
        box.beginSend(tile.key, bytes, crc)
        return box.confirm(tile.key, bytes, crc, "ble", atMs)
    }

    // --- the ordinary path ---------------------------------------------------

    @Test
    fun `a receipt is the only thing that makes a tile sent`() {
        val box = readyOutbox()
        val item = box.takeNext(t0)!!
        assertEquals(a, item.tile)
        assertEquals(TileState.SENDING, box.stateOf(item, t0))

        // Every chunk acknowledged, the whole file on the wire -- and still not
        // sent. The last chunk being written is not a verdict.
        box.progress(a.key, 966_878L)
        assertFalse(box.isSent(a.key))
        assertEquals(TileState.SENDING, box.stateOf(box.items.first(), t0))

        box.beginSend(a.key, 966_878L, 0xdeadbeefL)
        assertTrue(box.confirm(a.key, 966_878L, 0xdeadbeefL, "ble", t0 + 100))
        assertTrue(box.isSent(a.key))
        assertEquals(TileState.SENT, box.stateOf(box.items.first(), t0 + 100))
        assertNull(box.inFlight)
    }

    @Test
    fun `a receipt for the wrong bytes does not count as sent`() {
        val box = readyOutbox()
        box.takeNext(t0)
        box.beginSend(a.key, 966_878L, 0xdeadbeefL)

        // The device reads the CRC back off its own card, so a disagreement
        // means the file there is not the file that went out.
        assertFalse(box.confirm(a.key, 966_878L, 0x11111111L, "ble", t0 + 100))
        assertFalse(box.isSent(a.key))
        assertEquals(TileState.RETRY, box.stateOf(box.items.first(), t0 + 100))
        assertEquals("receipt mismatch", box.items.first().error)

        // A short count is refused the same way.
        box.beginSend(a.key, 966_878L, 0xdeadbeefL)
        assertFalse(box.confirm(a.key, 900_000L, 0xdeadbeefL, "ble", t0 + 200))
        assertFalse(box.isSent(a.key))

        // And so is a receipt nothing was sent for at all.
        assertFalse(box.confirm(b.key, 1_008_547L, 0x22L, "ble", t0 + 300))
        assertFalse(box.isSent(b.key))
    }

    @Test
    fun `tiles go out in plan order and the queue drains`() {
        val box = readyOutbox()
        assertTrue(send(box, a, 966_878L, 1L, t0))
        assertTrue(send(box, b, 1_008_547L, 2L, t0 + minute))
        assertTrue(send(box, c, 809_034L, 3L, t0 + 2 * minute))
        assertNull(box.next(t0 + 3 * minute))
        assertEquals(3, box.totals(t0 + 3 * minute).sent)
        assertEquals(0L, box.totals(t0 + 3 * minute).remainingBytes)
    }

    // --- surviving a restart --------------------------------------------------

    @Test
    fun `a restart resumes at the next pending tile`() {
        val box = readyOutbox()
        assertTrue(send(box, a, 966_878L, 1L, t0))
        // The process dies here, mid transfer of b: taken, bytes on the wire,
        // no receipt.
        box.takeNext(t0 + minute)
        box.progress(b.key, 500_000L)

        // Everything a serializer writes, and nothing else.
        val restarted = TileOutbox(box.zones, box.items, box.receipts)

        assertNull(restarted.inFlight)
        assertTrue(restarted.isSent(a.key))
        // b was never confirmed, so it is pending again by construction.
        assertEquals(b, restarted.next(t0 + hour)?.tile)
        assertEquals(TileState.QUEUED, restarted.stateOf(restarted.items[1], t0 + hour))
        assertEquals(2, restarted.totals(t0 + hour).queued)
        assertEquals(1_008_547L + 809_034L, restarted.totals(t0 + hour).remainingBytes)
        // The index reads are not repeated: what the CDN said survived too.
        assertTrue(restarted.dueForIndexRead(t0 + hour).isEmpty())
    }

    // --- waiting for a build --------------------------------------------------

    @Test
    fun `a tile waiting for a build is not offered before its next try`() {
        val box = TileOutbox()
        box.addZone(zone(), listOf(a))
        box.observe(a, emptySlot, false, t0)

        val item = box.items.first()
        assertEquals(TilePlan.State.WAITING_BUILD, item.cdn)
        assertEquals(TileState.WAITING_BUILD, box.stateOf(item, t0))
        // Never pushed: there is nothing on the CDN to push.
        assertNull(box.next(t0 + 10 * hour))

        // The index re-read is what is timed, and it is a few kB, not a tile.
        assertTrue(box.dueForIndexRead(t0 + minute).isEmpty())
        assertTrue(box.dueForIndexRead(t0 + 4 * minute).isEmpty())
        assertEquals(listOf(a), box.dueForIndexRead(t0 + 5 * minute))
    }

    @Test
    fun `the recheck ladder is five then fifteen then hourly`() {
        val box = TileOutbox()
        box.addZone(zone(), listOf(a))

        box.observe(a, emptySlot, false, t0)
        assertEquals(t0 + 5 * minute, box.items.first().nextTryAtMs)

        box.observe(a, emptySlot, false, t0 + 5 * minute)
        assertEquals(t0 + 20 * minute, box.items.first().nextTryAtMs)

        box.observe(a, emptySlot, false, t0 + 20 * minute)
        assertEquals(t0 + 80 * minute, box.items.first().nextTryAtMs)

        // Then hourly, for as long as the give-up allows.
        box.observe(a, emptySlot, false, t0 + 80 * minute)
        assertEquals(t0 + 140 * minute, box.items.first().nextTryAtMs)
        assertEquals(4, box.items.first().buildChecks)
    }

    @Test
    fun `ground that gets built is picked up on a later look`() {
        val box = TileOutbox()
        box.addZone(zone(), listOf(a))
        box.observe(a, emptySlot, false, t0)
        assertNull(box.next(t0 + 5 * minute))

        // The server built it, four passes later, with nobody asking twice.
        box.observe(a, present(966_878L, contentId = 0x5678L), false, t0 + 5 * minute)
        val item = box.next(t0 + 5 * minute)
        assertNotNull(item)
        assertEquals(966_878L, item!!.sizeBytes)
        // Carried so the fetch can ask for ?crc= and not be handed the cached
        // copy it is replacing.
        assertEquals(0x5678L, item.contentId)
        assertEquals(0, item.buildChecks)
    }

    @Test
    fun `a tile still unbuilt after twenty four hours is given up on`() {
        val box = TileOutbox()
        box.addZone(zone(), listOf(a))
        box.observe(a, emptySlot, false, t0)

        assertEquals(TileState.WAITING_BUILD, box.stateOf(box.items.first(), t0 + 23 * hour))
        assertEquals(TileState.EXPIRED, box.stateOf(box.items.first(), t0 + 24 * hour))
        // Past the server's own per cell cooldown nothing further gets built for
        // this ground, so it stops being asked about too.
        assertTrue(box.dueForIndexRead(t0 + 24 * hour).isEmpty())
        assertEquals(1, box.totals(t0 + 24 * hour).unavailable)
        // The clock runs from the ask, not from the last look.
        assertEquals(t0, box.items.first().queuedAtMs)
    }

    @Test
    fun `an unreachable cdn never expires a tile it could not look at`() {
        val box = TileOutbox()
        box.addZone(zone(), listOf(a))
        box.observe(a, TilePlan.Reading.Unreachable, false, t0)

        // Giving up here would be giving up because this phone's network
        // failed, which says nothing at all about the ground.
        assertEquals(TilePlan.State.UNKNOWN, box.items.first().cdn)
        assertEquals(TileState.QUEUED, box.stateOf(box.items.first(), t0 + 48 * hour))
        assertEquals(listOf(a), box.dueForIndexRead(t0 + 48 * hour))
    }

    // --- sea ------------------------------------------------------------------

    @Test
    fun `a tile on built ground that is not there is never retried`() {
        val box = TileOutbox()
        box.addZone(zone(), listOf(c))
        // 13/4146/3061: covered by auto-z11-1036-765, 404 on the CDN today.
        box.observe(c, emptySlot, true, t0)

        assertEquals(TileState.ABSENT, box.stateOf(box.items.first(), t0))
        assertNull(box.next(t0 + 48 * hour))
        assertTrue(box.dueForIndexRead(t0 + 48 * hour).isEmpty())
        assertEquals(1, box.totals(t0).unavailable)
        assertEquals(0L, box.totals(t0).remainingBytes)
    }

    // --- failures -------------------------------------------------------------

    @Test
    fun `a transient failure retries and a terminal one does not`() {
        val box = readyOutbox()
        box.takeNext(t0)
        box.fail(a.key, "link lost", t0)

        assertEquals(TileState.RETRY, box.stateOf(box.items.first(), t0))
        assertNull(box.inFlight)
        // Held back briefly so a CDN refusing everything is not hammered inside
        // one connection, then offered again on its own.
        assertEquals(b, box.next(t0)?.tile)
        assertEquals(a, box.next(t0 + TileOutbox.TRANSIENT_RETRY_MS)?.tile)
        assertEquals(1, box.items.first().attempts)

        // A fresh link is new information about the thing that failed.
        box.fail(a.key, "link lost", t0 + 2 * minute)
        box.clearFailures()
        assertEquals(a, box.next(t0 + 2 * minute)?.tile)

        // Terminal: a tile built to a format this device cannot read transfers,
        // passes CRC, is renamed into place and is refused on open. Retrying it
        // spends the rider's data on a certainty.
        box.fail(a.key, TileFetcher.SKIP_WRONG_FORMAT, t0 + 3 * minute, terminal = true)
        assertEquals(TileState.FAILED, box.stateOf(box.items.first(), t0 + 3 * minute))
        assertEquals(b, box.next(t0 + 48 * hour)?.tile)
        box.clearFailures()
        assertEquals(b, box.next(t0 + 48 * hour)?.tile)
        assertTrue(box.dueForIndexRead(t0 + 48 * hour).isEmpty())
    }

    @Test
    fun `a confirmed tile stops being a failure`() {
        val box = readyOutbox()
        box.takeNext(t0)
        box.fail(a.key, "link lost", t0)
        assertTrue(send(box, b, 1_008_547L, 2L, t0))

        box.beginSend(a.key, 966_878L, 7L)
        assertTrue(box.confirm(a.key, 966_878L, 7L, "ble", t0 + minute))
        assertNull(box.items.first().error)
        assertEquals(0, box.items.first().attempts)
    }

    // --- zones ------------------------------------------------------------------

    @Test
    fun `dropping a zone takes its tiles and nothing else`() {
        val box = TileOutbox()
        box.addZone(zone("bcn", t0), listOf(a, b))
        box.addZone(zone("gir", t0), listOf(b, c))
        box.observe(a, present(100L), true, t0)
        box.observe(b, present(200L), true, t0)
        box.observe(c, present(300L), true, t0)
        assertEquals(4, box.items.size)

        box.removeZone("bcn")

        assertNull(box.zone("bcn"))
        assertNotNull(box.zone("gir"))
        assertEquals(listOf("gir", "gir"), box.items.map { it.zoneId })
        // b belongs to both, so dropping the first zone must not cancel it.
        assertEquals(setOf(b.key, c.key), box.items.map { it.key }.toSet())
        assertEquals(500L, box.totals(t0).remainingBytes)
    }

    @Test
    fun `a tile two zones both asked for is sent once and counted once`() {
        val box = TileOutbox()
        box.addZone(zone("bcn", t0), listOf(a, b))
        box.addZone(zone("gir", t0), listOf(b))
        assertEquals(3, box.items.size)
        // Two items for b, but one index read: asking twice would spend the
        // rider's data to learn the same thing.
        assertEquals(listOf(a, b), box.dueForIndexRead(t0))

        box.observe(a, present(100L), true, t0)
        box.observe(b, present(200L), true, t0)

        // One read answers for both zones, so they can never disagree about the
        // same square.
        assertEquals(2, box.items.count { it.key == b.key })
        assertTrue(box.items.filter { it.key == b.key }.all { it.cdn == TilePlan.State.PRESENT })

        assertEquals(300L, box.totals(t0).remainingBytes)
        assertEquals(2, box.totals(t0).tiles)
        // Per zone, each still owns what it asked for.
        assertEquals(200L, box.zoneTotals("gir", t0).remainingBytes)

        assertTrue(send(box, a, 100L, 1L, t0))
        assertTrue(send(box, b, 200L, 2L, t0))
        assertNull(box.next(t0))
        assertEquals(1, box.zoneTotals("gir", t0).sent)
        assertEquals(2, box.totals(t0).sent)
    }

    @Test
    fun `a receipt survives the zone that asked for it`() {
        val box = TileOutbox()
        box.addZone(zone("bcn", t0), listOf(a))
        box.observe(a, present(100L), true, t0)
        assertTrue(send(box, a, 100L, 1L, t0))

        box.removeZone("bcn")
        // The tile is on the card whatever the rider does with the zone, so a
        // later overlapping pick must not push it again.
        assertTrue(box.isSent(a.key))
        box.addZone(zone("gir", t0 + hour), listOf(a))
        box.observe(a, present(100L), true, t0 + hour)
        assertNull(box.next(t0 + hour))
        assertEquals(1, box.totals(t0 + hour).sent)
    }

    // --- the summary line -------------------------------------------------------

    @Test
    fun `the totals say what the screen has to render`() {
        val box = TileOutbox()
        val tiles = TileBox.tilesFor(41.3874, 2.1686, 20.0)
        box.addZone(zone("bcn", t0), tiles)
        assertEquals(71, box.items.size)

        // The live split, 2026-09-02: 55 present, 3 sea, 13 not built yet.
        val built = TilePlan.BuiltGround(
            listOf("auto-z11-1035-764", "auto-z11-1035-765", "auto-z11-1036-764", "auto-z11-1036-765")
                .map { TilePlan.BuiltArea(it, 0.0, 0.0, 0.0, 0.0) }
        )
        val sea = setOf(TileRef(13, 4146, 3061).key, TileRef(13, 4147, 3061).key, TileRef(13, 4147, 3062).key)
        for (tile in tiles) {
            val covered = built.covers(tile)
            val reading = if (!covered || tile.key in sea) emptySlot else present(300_000L)
            box.observe(tile, reading, covered, t0)
        }

        var totals = box.totals(t0)
        assertEquals(71, totals.tiles)
        assertEquals(0, totals.sent)
        assertEquals(55, totals.queued)
        assertEquals(13, totals.waitingBuild)
        assertEquals(3, totals.unavailable)
        assertEquals(55 * 300_000L, totals.remainingBytes)
        assertEquals(1833L, totals.etaSeconds(TilePlan.START_BYTES_PER_SECOND))

        // Half an hour later, 31 of them have landed.
        var at = t0
        repeat(31) {
            at += minute
            val item = box.takeNext(at)!!
            box.beginSend(item.key, 300_000L, it.toLong())
            assertTrue(box.confirm(item.key, 300_000L, it.toLong(), "ble", at))
        }
        totals = box.totals(at)
        assertEquals(31, totals.sent)
        assertEquals(24, totals.queued)
        assertEquals(13, totals.waitingBuild)
        assertEquals(3, totals.unavailable)
        assertEquals(31 * 300_000L, totals.sentBytes)
        assertEquals(24 * 300_000L, totals.remainingBytes)
    }

    @Test
    fun `progress moves inside one tile without claiming it landed`() {
        val box = readyOutbox()
        box.takeNext(t0)
        box.progress(a.key, 400_000L)
        assertEquals(400_000L, box.totals(t0).inFlightBytes)
        // Never past the tile's own size, whatever a transport reports.
        box.progress(a.key, 99_000_000L)
        assertEquals(966_878L, box.totals(t0).inFlightBytes)
        assertEquals(0, box.totals(t0).sent)
    }

    @Test
    fun `the bar counts squares that are never coming as done`() {
        val box = TileOutbox()
        box.addZone(zone(), listOf(a, b, c))
        box.observe(a, present(100L), true, t0)
        box.observe(b, emptySlot, true, t0)
        box.observe(c, emptySlot, false, t0)

        // b is sea and settles the moment the index says so.
        assertEquals(1f / 3f, box.totals(t0).fraction, 0.001f)
        assertTrue(send(box, a, 100L, 1L, t0))
        // a sent, b sea: two of three settled, and the third is still coming.
        assertEquals(2f / 3f, box.totals(t0).fraction, 0.001f)
    }

    @Test
    fun `an empty outbox has nothing to say and does not divide by zero`() {
        val box = TileOutbox()
        val totals = box.totals(t0)
        assertEquals(0, totals.tiles)
        assertEquals(0f, totals.fraction, 0.001f)
        assertEquals(0L, totals.etaSeconds(TilePlan.START_BYTES_PER_SECOND))
        assertNull(box.next(t0))
        assertTrue(box.dueForIndexRead(t0).isEmpty())
    }

    @Test
    fun `the same zone is not added twice`() {
        val box = TileOutbox()
        box.addZone(zone("bcn", t0), listOf(a, b))
        box.addZone(zone("bcn", t0 + hour), listOf(a, b, c))
        assertEquals(1, box.zones.size)
        assertEquals(2, box.items.size)
    }
}
