package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue: one ordered list, one ledger, seven derived states, and delta sync.
 *
 * The tests that matter most are the negative ones. Brief section 27's real
 * requirement is "kritické je, aby aplikácia používateľovi neklamala", so the
 * states that claim something about the card must be **unreachable** without a
 * confirmation, and that is asserted directly rather than argued for in a comment.
 */
class WalletSyncQueueTest {

    /**
     * [full] asks for the 1:1 level too. It defaults to **on** here because most of
     * these tests are about the ledger and the ordering over a whole plan, and the
     * deferral has its own tests in `WalletSyncDeferralTest`.
     */
    private fun oneItem(codes: Boolean = false, full: Boolean = true): Pair<WalletStore, WalletSyncQueue> {
        val store = SyncFixtures.store()
        SyncFixtures.addItem(store, "Passport", codes = if (codes)
            listOf(WalletPipeline.CodeRequest(Symbology.QR, "TEST12345")) else emptyList())
        val q = SyncFixtures.queue(store, full = full)
        q.queueAll()
        return Pair(store, q)
    }

    // --- states that never lie ---------------------------------------------

    @Test
    fun an_unqueued_item_is_local_only_and_nothing_else() {
        val store = SyncFixtures.store()
        val item = SyncFixtures.addItem(store, "Passport")
        val q = SyncFixtures.queue(store)
        assertEquals(SyncState.LOCAL_ONLY, q.statusOf(item.id).state)
        assertFalse(q.statusOf(item.id).usable)
        assertTrue(q.pending().isEmpty())
    }

    @Test
    fun queueing_is_intent_and_moves_nothing() {
        val (store, q) = oneItem()
        val id = store.load().items[0].id
        assertEquals(SyncState.QUEUED, q.statusOf(id).state)
        assertEquals(0, q.statusOf(id).confirmedAssets)
        assertTrue(q.pending().isNotEmpty())
    }

    @Test
    fun no_state_says_on_device_without_a_confirmation() {
        val (store, q) = oneItem(codes = true)
        val id = store.load().items[0].id

        // Walk the whole plan, "sending" everything and confirming nothing. This is
        // exactly the mistake brief section 28 forbids: the phone sent the data.
        for (a in q.plan) {
            q.progress(a.key, a.bytes)
        }
        val st = q.statusOf(id)
        assertFalse("nothing was confirmed, so nothing may be usable", st.usable)
        assertNotEquals(SyncState.USABLE_ON_DEVICE, st.state)
        assertNotEquals(SyncState.FULL_QUALITY_SYNCING, st.state)
        assertNotEquals(SyncState.FULLY_SYNCED, st.state)
        assertEquals(SyncState.QUEUED, st.state)
        assertEquals(0, st.confirmedAssets)
    }

    @Test
    fun a_confirmation_for_the_wrong_bytes_does_not_count() {
        val (store, q) = oneItem()
        val id = store.load().items[0].id
        // A ledger entry with the right asset id and the wrong hash. That is what a
        // re-rendered page looks like, and what a card holding an older version looks
        // like. The id says which asset; the hash decides.
        for (a in q.plan) {
            q.confirm(a.copy(sha256 = "0".repeat(64)), "fake", 1L)
        }
        assertEquals(q.plan.size, q.pending().size)
        assertEquals(SyncState.QUEUED, q.statusOf(id).state)
    }

    @Test
    fun usable_on_device_needs_the_manifest_the_fit_assets_and_every_verified_code() {
        val (store, q) = oneItem(codes = true)
        val id = store.load().items[0].id

        for (a in q.plan.filter { it.cls == SyncClass.FIT }) q.confirm(a, "fake", 1L)
        assertFalse("no manifest yet", q.statusOf(id).usable)

        q.confirm(q.plan.first { it.isManifest }, "fake", 1L)
        assertFalse("no code yet", q.statusOf(id).usable)

        for (a in q.plan.filter { it.cls == SyncClass.CODE }) q.confirm(a, "fake", 1L)
        val st = q.statusOf(id)
        assertTrue(st.usable)
        assertEquals(SyncState.USABLE_ON_DEVICE, st.state)
        // Usable is not synced. There is still detail and 1:1 outstanding.
        assertFalse(st.state.isFullySynced)
        assertTrue(st.state.isUsable)
    }

    @Test
    fun an_unverified_code_never_makes_an_item_look_usable() {
        val store = SyncFixtures.store()
        SyncFixtures.addItem(store, "Pass", codes = listOf(
            WalletPipeline.CodeRequest(Symbology.QR, "TEST12345")))
        val wallet = store.load()
        val unverified = wallet.copy(items = wallet.items.map { item ->
            item.copy(pages = item.pages.map { p ->
                p.copy(codes = p.codes.map { it.copy(verified = false) })
            })
        })
        val q = WalletSyncQueue(WalletSyncPlan.build(unverified, store.treeDir))
        q.queueAll()
        val id = unverified.items[0].id

        // Manifest and FIT land; the code is UNVERIFIED_CODE, which is not counted
        // towards usable at all. So the item becomes usable without it -- and the
        // code is still sent, last, rather than dropped.
        q.confirm(q.plan.first { it.isManifest }, "fake", 1L)
        for (a in q.plan.filter { it.cls == SyncClass.FIT }) q.confirm(a, "fake", 1L)
        assertTrue(q.statusOf(id).usable)
        assertTrue(q.pending().any { it.cls == SyncClass.UNVERIFIED_CODE })
    }

    @Test
    fun full_quality_syncing_needs_usable_plus_something_in_flight() {
        val (store, q) = oneItem()
        val id = store.load().items[0].id
        q.confirm(q.plan.first { it.isManifest }, "fake", 1L)
        for (a in q.plan.filter { it.cls == SyncClass.FIT }) q.confirm(a, "fake", 1L)
        assertEquals(SyncState.USABLE_ON_DEVICE, q.statusOf(id).state)

        assertNotNull_(q.takeNext())
        assertEquals(SyncState.FULL_QUALITY_SYNCING, q.statusOf(id).state)

        q.release()
        assertEquals(SyncState.USABLE_ON_DEVICE, q.statusOf(id).state)
    }

    @Test
    fun syncing_is_for_an_item_that_is_not_usable_yet() {
        val (store, q) = oneItem()
        val id = store.load().items[0].id
        assertNotNull_(q.takeNext())
        assertEquals(SyncState.SYNCING, q.statusOf(id).state)
    }

    @Test
    fun fully_synced_needs_every_single_asset() {
        val (store, q) = oneItem(codes = true)
        val id = store.load().items[0].id
        for (a in q.plan) q.confirm(a, "fake", 1L)
        assertEquals(SyncState.FULLY_SYNCED, q.statusOf(id).state)
        assertTrue(q.pending().isEmpty())

        // Take one back out and it is not synced any more. A single missing tile
        // is the difference between "synced" and a lie.
        val one = q.plan.last()
        val reduced = WalletSyncQueue(q.plan,
            q.confirmed.filterKeys { it != one.key }, emptyMap(), setOf(id))
        assertNotEquals(SyncState.FULLY_SYNCED, reduced.statusOf(id).state)
    }

    @Test
    fun a_failure_outranks_usable_but_not_fully_synced() {
        val (store, q) = oneItem()
        val id = store.load().items[0].id
        q.confirm(q.plan.first { it.isManifest }, "fake", 1L)
        for (a in q.plan.filter { it.cls == SyncClass.FIT }) q.confirm(a, "fake", 1L)
        q.fail(q.plan.last().key, "ERR nospace")

        val st = q.statusOf(id)
        // ERROR is what the rider sees, because a failure they cannot see is the
        // thing that lies. What still works is carried beside it, not folded in.
        assertEquals(SyncState.ERROR, st.state)
        assertTrue("usable is still true and still reported", st.usable)
        assertEquals(1, st.failedAssets)

        // ... and a confirmation clears that asset's error, so a retry that lands
        // does not leave a stale ERROR behind.
        q.confirm(q.plan.last(), "fake", 2L)
        assertNotEquals(SyncState.ERROR, q.statusOf(id).state)
    }

    @Test
    fun every_one_of_the_seven_states_is_reachable() {
        val seen = HashSet<SyncState>()
        val (store, q) = oneItem(codes = true)
        val id = store.load().items[0].id

        val fresh = SyncFixtures.queue(store)
        seen.add(fresh.statusOf(id).state)                       // LOCAL_ONLY
        seen.add(q.statusOf(id).state)                           // QUEUED
        q.takeNext()
        seen.add(q.statusOf(id).state)                           // SYNCING
        q.release()
        q.confirm(q.plan.first { it.isManifest }, "fake", 1L)
        for (a in q.plan.filter { it.cls == SyncClass.FIT || it.cls == SyncClass.CODE }) {
            q.confirm(a, "fake", 1L)
        }
        seen.add(q.statusOf(id).state)                           // USABLE_ON_DEVICE
        q.takeNext()
        seen.add(q.statusOf(id).state)                           // FULL_QUALITY_SYNCING
        q.release()
        q.fail(q.plan.last().key, "boom")
        seen.add(q.statusOf(id).state)                           // ERROR
        for (a in q.plan) q.confirm(a, "fake", 1L)
        seen.add(q.statusOf(id).state)                           // FULLY_SYNCED

        assertEquals(SyncState.entries.toSet(), seen)
    }

    // --- resume and delta ---------------------------------------------------

    @Test
    fun resume_picks_up_where_the_ledger_left_off() {
        val (store, q) = oneItem()
        val half = q.plan.take(q.plan.size / 2)
        for (a in half) q.confirm(a, "ble", 1L)
        store.saveSyncState(q)

        // A new process, a new queue, the same tree. This is the app being killed
        // and reopened, and brief section 29's "continue with tile 8".
        val again = SyncFixtures.queue(store, full = true)
        assertEquals(q.plan.size - half.size, again.pending().size)
        assertTrue(again.pending().none { a -> half.any { it.key == a.key } })
        // And it resumes in priority order, not at the front of the list.
        assertEquals(q.plan.drop(half.size).map { it.key }, again.pending().map { it.key })
    }

    @Test
    fun a_title_change_syncs_the_manifest_alone() {
        val store = SyncFixtures.store()
        val item = SyncFixtures.addItem(store, "Passport")
        val q = SyncFixtures.queue(store)
        q.queueAll()
        for (a in q.plan) q.confirm(a, "wifi", 1L)
        store.saveSyncState(q)
        assertTrue(SyncFixtures.queue(store).pending().isEmpty())

        // Retitle: rewrite the manifest, nothing else.
        val wallet = store.load()
        store.addItem(wallet.items[0].copy(title = "Passport (renewed)"),
            store.loadState().sourceNames[item.id] ?: emptyList())

        val delta = SyncFixtures.queue(store)
        assertEquals(1, delta.pending().size)
        assertTrue(delta.pending()[0].isManifest)
    }

    @Test
    fun a_reorder_syncs_the_manifest_alone() {
        val store = SyncFixtures.store()
        SyncFixtures.addItem(store, "One")
        val two = SyncFixtures.addItem(store, "Two")
        val q = SyncFixtures.queue(store)
        q.queueAll()
        for (a in q.plan) q.confirm(a, "wifi", 1L)
        store.saveSyncState(q)

        store.moveItem(two.id, -1)
        val delta = SyncFixtures.queue(store)
        assertEquals(listOf(SyncAsset.MANIFEST_KEY), delta.pending().map { it.key })
    }

    @Test
    fun flipping_grey_syncs_the_manifest_alone() {
        val store = SyncFixtures.store()
        val item = SyncFixtures.addItem(store, "Scan", grey = true)
        val q = SyncFixtures.queue(store)
        q.queueAll()
        for (a in q.plan) q.confirm(a, "wifi", 1L)
        store.saveSyncState(q)

        // Grey off: the grey assets stay on the card, the device just stops using
        // them. Brief section 40 -- a metadata change must not re-upload image data.
        store.setGrey(item.id, false)
        val delta = SyncFixtures.queue(store)
        assertEquals(listOf(SyncAsset.MANIFEST_KEY), delta.pending().map { it.key })
    }

    @Test
    fun a_changed_page_syncs_that_pages_assets_and_the_manifest() {
        val store = SyncFixtures.store()
        SyncFixtures.addItem(store, "Two pages", pages = 2)
        val q = SyncFixtures.queue(store)
        q.queueAll()
        for (a in q.plan) q.confirm(a, "wifi", 1L)
        store.saveSyncState(q)

        // Re-render page 2 with different ink. The asset ids are unchanged -- the
        // recipe has no content in it -- so it is the HASHES that make exactly page
        // 2's assets pending again.
        val wallet = store.load()
        val item = wallet.items[0]
        val pipeline = WalletPipeline(Panels.byName(store.panelName))
        val sources = listOf(
            WalletPipeline.PageSource(SyncFixtures.page(), "Two pages-0.png"),
            WalletPipeline.PageSource(SyncFixtures.page(310, 430), "Two pages-1.png"))
        store.addItem(pipeline.buildItem(item.id, item.title, item.createdAt, 0, sources,
            store.sink(), paper = "a5"))

        val delta = SyncFixtures.queue(store)
        val pending = delta.pending()
        assertTrue(pending.any { it.isManifest })
        val touched = pending.filter { !it.isManifest }
        assertTrue("page 2 changed", touched.isNotEmpty())
        assertTrue("page 1 did not", touched.all { it.pageId == "p002" })
    }

    @Test
    fun a_new_code_syncs_the_code_asset_and_the_manifest() {
        val store = SyncFixtures.store()
        SyncFixtures.addItem(store, "Pass")
        val q = SyncFixtures.queue(store)
        q.queueAll()
        for (a in q.plan) q.confirm(a, "wifi", 1L)
        store.saveSyncState(q)

        val item = store.load().items[0]
        val pipeline = WalletPipeline(Panels.byName(store.panelName))
        store.addItem(pipeline.buildItem(item.id, item.title, item.createdAt, 0,
            listOf(WalletPipeline.PageSource(SyncFixtures.page(), "Pass-0.png",
                codes = listOf(WalletPipeline.CodeRequest(Symbology.QR, "TEST12345")))),
            store.sink(), paper = "a5"))

        val pending = SyncFixtures.queue(store).pending()
        assertEquals(setOf(SyncClass.MANIFEST, SyncClass.CODE), pending.map { it.cls }.toSet())
    }

    @Test
    fun pending_bytes_and_the_class_breakdown_add_up() {
        val (_, q) = oneItem()
        val totals = q.totals()
        assertEquals(q.plan.size, totals.totalAssets)
        assertEquals(totals.pendingBytes, q.pendingByClass().values.sum())
        assertEquals(totals.totalBytes, q.plan.sumOf { it.bytes.toLong() })
        assertEquals(0, totals.confirmedAssets)
    }

    @Test
    fun next_skips_an_asset_that_already_failed() {
        val (_, q) = oneItem()
        val first = q.plan.first()
        q.fail(first.key, "boom")
        assertNotEquals(first.key, q.next()?.key)
        // ...but it is still pending, so it is still visibly outstanding.
        assertTrue(q.pending().any { it.key == first.key })
    }

    private fun assertNotNull_(v: Any?) = assertTrue("expected non-null", v != null)
}
