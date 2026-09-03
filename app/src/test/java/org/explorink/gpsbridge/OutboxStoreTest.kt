package org.explorink.gpsbridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The outbox on disk.
 *
 * The queue is not an operation, it is a ledger that has to outlive the link,
 * the app process and the phone reboot -- and **a tile item exists nowhere but
 * this file**. The wallet can rebuild a lost item from pages the phone still
 * holds; the rider's decision to cache Barcelona is recorded here and nowhere
 * else. So every case below is about what survives, and about what a file this
 * build cannot read is allowed to do (never: quietly look like an empty queue).
 */
class OutboxStoreTest {

    private val root: File = File.createTempFile("outbox-test", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun store() = OutboxStore(root)

    private val barcelona = TileZone(
        zoneId = "zone-1",
        label = "Barcelona 20 km",
        latE7 = 413_874_000,
        lonE7 = 21_686_000,
        sideKm = 20,
        createdAtMs = 1_788_100_000_000L,
    )

    private val a = TileRef(13, 4144, 3059)
    private val b = TileRef(12, 2072, 1529)
    private val sea = TileRef(13, 4146, 3061)

    // --- round trips ---------------------------------------------------------

    @Test
    fun `an empty queue round-trips and is not mistaken for a missing file`() {
        val s = store()
        s.save(TileOutbox())

        val back = s.load()
        assertEquals(0, back.zones.size)
        assertEquals(0, back.items.size)
        assertEquals(0, back.receipts.size)
        assertTrue(s.lastLoad is OutboxJson.Load.Restored)
        assertTrue(s.file.isFile)
    }

    @Test
    fun `a missing file is a first run, not damage`() {
        val s = store()
        assertEquals(0, s.load().items.size)
        assertTrue(s.lastLoad is OutboxJson.Load.Restored)
        assertFalse(s.badFile.exists())
    }

    @Test
    fun `a queue mid-flight round-trips every field of the ask`() {
        val out = TileOutbox()
        out.addZone(barcelona, listOf(a, b, sea))
        val now = 1_788_100_500_000L
        out.observe(a, TilePlan.Reading.Found(slot(present = true, size = 966_878L, id = 0xABB60454L)), true, now)
        out.observe(sea, TilePlan.Reading.Found(TileIndex.Slot.ABSENT), true, now)
        out.observe(b, TilePlan.Reading.NoBlock, false, now)
        out.fail(a.key, "link dropped", now)

        val s = store()
        s.save(out)
        val back = s.load()

        assertEquals(1, back.zones.size)
        assertEquals(barcelona, back.zones.single())

        val items = back.items.associateBy { it.key }
        assertEquals(3, items.size)

        val present = items.getValue(a.key)
        assertEquals(TilePlan.State.PRESENT, present.cdn)
        assertEquals(966_878L, present.sizeBytes)
        assertEquals(0xABB60454L, present.contentId)
        assertEquals("link dropped", present.error)
        assertEquals(1, present.attempts)
        assertEquals(now + TileOutbox.TRANSIENT_RETRY_MS, present.nextTryAtMs)
        assertEquals(barcelona.zoneId, present.zoneId)
        assertEquals(barcelona.createdAtMs, present.queuedAtMs)

        // Sea stays sea across a restart. Re-reading it as UNKNOWN would put
        // 125 squares of Mediterranean back in the retry round.
        assertEquals(TilePlan.State.ABSENT, items.getValue(sea.key).cdn)

        val waiting = items.getValue(b.key)
        assertEquals(TilePlan.State.WAITING_BUILD, waiting.cdn)
        assertEquals(1, waiting.buildChecks)
        assertEquals(now + TileOutbox.RECHECK_MS[0], waiting.nextTryAtMs)
    }

    @Test
    fun `receipts survive, including for a tile no zone wants any more`() {
        // A receipt is a fact about what is on the device's card. Forgetting one
        // makes the next overlapping zone push bytes the device already holds --
        // 258 kB a square in Barcelona.
        val out = TileOutbox()
        out.addZone(barcelona, listOf(a))
        out.takeNextForTest(a)
        out.beginSend(a.key, 966_878L, 0xDEADBEEFL)
        assertTrue(out.confirm(a.key, 966_878L, 0xDEADBEEFL, "ble", 1_788_100_900_000L))
        out.removeZone(barcelona.zoneId)

        val s = store()
        s.save(out)
        val back = s.load()

        assertEquals(0, back.items.size)
        assertEquals(0, back.zones.size)
        val r = back.receipts.getValue(a.key)
        assertEquals(966_878L, r.bytes)
        assertEquals(0xDEADBEEFL, r.crc32)
        assertEquals("ble", r.transport)
        assertEquals(1_788_100_900_000L, r.atMs)
        assertTrue(back.isSent(a.key))
    }

    @Test
    fun `a terminal failure survives and is not retried after a restart`() {
        val out = TileOutbox()
        out.addZone(barcelona, listOf(a))
        out.observe(a, TilePlan.Reading.Found(slot(true, 100L, 1L)), true, 1_000L)
        out.fail(a.key, "format the device cannot read", 1_000L, terminal = true)

        val s = store()
        s.save(out)
        val back = s.load()

        val item = back.items.single()
        assertTrue(item.terminal)
        assertEquals(TileState.FAILED, back.stateOf(item, 2_000L))
        assertNull(back.next(2_000L))
        assertEquals(0, back.dueForIndexRead(9_999_999L).size)
    }

    @Test
    fun `nothing in flight is persisted, so a killed transfer is pending again`() {
        // The process died mid-transfer, so no receipt was ever sent. A stored
        // "sending" would be a state with nothing behind it, which is exactly
        // what the receipt law forbids.
        val out = TileOutbox()
        out.addZone(barcelona, listOf(a))
        out.observe(a, TilePlan.Reading.Found(slot(true, 966_878L, 1L)), true, 1_000L)
        val taken = out.takeNext(1_000L)
        assertNotNull(taken)
        out.beginSend(a.key, 966_878L, 7L)
        out.progress(a.key, 400_000L)

        val s = store()
        s.save(out)
        val back = s.load()

        assertNull(back.inFlight)
        assertEquals(0L, back.sentBytes(a.key))
        assertEquals(TileState.QUEUED, back.stateOf(back.items.single(), 1_000L))
        assertEquals(a.key, back.next(1_000L)!!.key)
        assertFalse(back.isSent(a.key))
    }

    @Test
    fun `the written file is exactly the documented shape`() {
        val out = TileOutbox()
        out.addZone(barcelona, listOf(a))
        val text = OutboxJson.write(out)
        val root = Json.asMap(Json.parse(text))

        assertEquals(listOf("version", "zones", "items", "receipts"), root.keys.toList())
        assertEquals(1L, root["version"])
        assertEquals(
            listOf("zoneId", "label", "latE7", "lonE7", "sideKm", "createdAtMs"),
            Json.asMap(Json.asList(root["zones"]).single()).keys.toList(),
        )
        assertEquals(
            listOf(
                "zoneId", "z", "col", "row", "queuedAtMs", "cdn", "sizeBytes",
                "contentId", "buildChecks", "nextTryAtMs", "attempts", "error", "terminal",
            ),
            Json.asMap(Json.asList(root["items"]).single()).keys.toList(),
        )
        // Always written, null when there is none: a fixed shape is what a
        // second client reads the format doc against.
        assertTrue(Json.asMap(Json.asList(root["items"]).single()).containsKey("error"))
        assertNull(Json.asMap(Json.asList(root["items"]).single())["error"])
    }

    @Test
    fun `a verdict word this build does not know costs one index read, not a verdict`() {
        val text = """
            {"version": 1, "zones": [], "receipts": {},
             "items": [{"zoneId": "z", "z": 13, "col": 4144, "row": 3059,
                        "queuedAtMs": 1000, "cdn": "MIRRORED_ON_MARS",
                        "sizeBytes": 5, "contentId": 9}]}
        """.trimIndent()
        val load = OutboxJson.read(text) as OutboxJson.Load.Restored
        val item = load.outbox.items.single()

        assertEquals(TilePlan.State.UNKNOWN, item.cdn)
        // Unknown means "ask the index", and asking is a few kB.
        assertEquals(listOf(a), load.outbox.dueForIndexRead(2_000L))
    }

    @Test
    fun `a field this build does not know is ignored and the entry still loads`() {
        val text = """
            {"version": 1, "zones": [], "receipts": {},
             "items": [{"zoneId": "z", "z": 13, "col": 4144, "row": 3059,
                        "queuedAtMs": 1000, "cdn": "PRESENT", "sizeBytes": 5,
                        "corridorWidthKm": 12}]}
        """.trimIndent()
        val load = OutboxJson.read(text) as OutboxJson.Load.Restored
        assertEquals(TilePlan.State.PRESENT, load.outbox.items.single().cdn)
        assertEquals(0, load.skipped)
    }

    @Test
    fun `a missing optional field takes the item's own default`() {
        val text = """
            {"version": 1, "items": [{"z": 13, "col": 4144, "row": 3059}]}
        """.trimIndent()
        val load = OutboxJson.read(text) as OutboxJson.Load.Restored
        val item = load.outbox.items.single()
        assertEquals(TilePlan.State.UNKNOWN, item.cdn)
        assertEquals(0L, item.sizeBytes)
        assertEquals(0, item.attempts)
        assertNull(item.error)
        assertFalse(item.terminal)
    }

    @Test
    fun `an entry with no tile behind it is dropped, the rest of the queue survives`() {
        // Losing one square of the ask is cheap. Losing the whole ask is not.
        val text = """
            {"version": 1, "zones": [], "receipts": {},
             "items": [{"zoneId": "z", "col": 4144, "row": 3059},
                       {"zoneId": "z", "z": 13, "col": 4144, "row": 3059}]}
        """.trimIndent()
        val load = OutboxJson.read(text) as OutboxJson.Load.Restored
        assertEquals(1, load.outbox.items.size)
        assertEquals(1, load.skipped)
    }

    @Test
    fun `a receipt missing its byte count is dropped, never read as a weaker receipt`() {
        // A receipt with no numbers behind it is a claim, not a receipt -- the
        // same thing TileOutbox.confirm refuses on the wire.
        val text = """
            {"version": 1, "zones": [], "items": [],
             "receipts": {"13/4144/3059": {"transport": "ble", "atMs": 5}}}
        """.trimIndent()
        val load = OutboxJson.read(text) as OutboxJson.Load.Restored
        assertEquals(0, load.outbox.receipts.size)
        assertEquals(1, load.skipped)
        assertFalse(load.outbox.isSent(a.key))
    }

    // --- files this build cannot read ----------------------------------------

    @Test
    fun `an unknown file version is not translated and not guessed`() {
        // The wallet's rule, and its reason: a version-1 state.json is migrated
        // by dropping its states, because a guessed state that claims the device
        // holds something is worse than no state at all.
        val s = store()
        root.mkdirs()
        s.file.writeText("""{"version": 99, "items": [{"z": 13, "col": 1, "row": 1}]}""")

        val back = s.load()
        assertEquals(0, back.items.size)
        val load = s.lastLoad as OutboxJson.Load.UnknownVersion
        assertEquals(99, load.version)
    }

    @Test
    fun `a load never writes, so an older build leaves a newer file alone`() {
        val s = store()
        val text = """{"version": 99, "items": []}"""
        s.file.writeText(text)

        s.load()

        assertEquals(text, s.file.readText())
        assertFalse(s.badFile.exists())
    }

    @Test
    fun `a file this build cannot read is kept, not destroyed, when a save replaces it`() {
        val s = store()
        val text = """{"version": 99, "items": []}"""
        s.file.writeText(text)
        s.load()

        s.save(TileOutbox())

        assertEquals(text, s.badFile.readText())
        assertEquals(1L, Json.asMap(Json.parse(s.file.readText()))["version"])
    }

    @Test
    fun `a truncated file is damage, not an empty queue`() {
        val out = TileOutbox()
        out.addZone(barcelona, listOf(a, b))
        val whole = OutboxJson.write(out)

        val s = store()
        s.file.writeText(whole.substring(0, whole.length / 2))
        assertEquals(0, s.load().items.size)
        assertTrue(s.lastLoad is OutboxJson.Load.Damaged)

        s.save(TileOutbox())
        assertTrue(s.badFile.isFile)
    }

    @Test
    fun `a garbage file is damage`() {
        val s = store()
        s.file.writeText("this was never JSON ")
        assertEquals(0, s.load().items.size)
        assertTrue(s.lastLoad is OutboxJson.Load.Damaged)
    }

    @Test
    fun `an empty file is damage, not a version this build reads`() {
        val s = store()
        s.file.writeText("")
        s.load()
        assertTrue(s.lastLoad is OutboxJson.Load.Damaged)
    }

    @Test
    fun `a file with no version at all is damage, not assumed to be version 1`() {
        val s = store()
        s.file.writeText("""{"zones": [], "items": [], "receipts": {}}""")
        s.load()
        assertTrue(s.lastLoad is OutboxJson.Load.Damaged)
    }

    // --- writing --------------------------------------------------------------

    @Test
    fun `a save leaves no part file behind`() {
        val s = store()
        s.save(TileOutbox())
        assertEquals(
            listOf(OutboxStore.FILE_NAME),
            root.list()!!.sorted(),
        )
    }

    @Test
    fun `saving twice replaces rather than appends`() {
        val s = store()
        val first = TileOutbox()
        first.addZone(barcelona, listOf(a, b))
        s.save(first)

        val second = TileOutbox()
        second.addZone(barcelona.copy(zoneId = "zone-2", label = "Girona 10 km"), listOf(a))
        s.save(second)

        val back = s.load()
        assertEquals(listOf("zone-2"), back.zones.map { it.zoneId })
        assertEquals(1, back.items.size)
    }

    @Test
    fun `a label with a newline and an accent comes back unchanged`() {
        // The label is written once, at the pick, because nothing later can
        // reconstruct it -- the app has no geocoder.
        val odd = barcelona.copy(label = "Žilina \"20 km\"\nsever")
        val out = TileOutbox()
        out.addZone(odd, listOf(a))

        val s = store()
        s.save(out)
        assertEquals(odd.label, s.load().zones.single().label)
    }

    @Test
    fun `the dir convention is tiles under filesDir`() {
        assertEquals("tiles", OutboxStore.dirIn(File("/data/x/files")).name)
        assertEquals("/data/x/files/tiles", OutboxStore.dirIn(File("/data/x/files")).path)
    }

    // --- helpers --------------------------------------------------------------

    private fun slot(present: Boolean, size: Long, id: Long) = TileIndex.Slot(
        present = present,
        coverage = TileIndex.COVERAGE_FULL,
        contentId = id,
        buildEpoch = 1_788_100_433L,
        sizeBytes = size,
    )

    /** Takes [tile] out of the queue whatever its backoff says. */
    private fun TileOutbox.takeNextForTest(tile: TileRef) {
        observe(tile, TilePlan.Reading.Found(slot(true, 966_878L, 1L)), true, 0L)
        takeNext(0L)
    }
}
