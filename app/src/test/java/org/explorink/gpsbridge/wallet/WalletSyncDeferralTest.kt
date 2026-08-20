package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is held back from a transfer, and what that does to the states.
 *
 * A grey A4 photograph is 2.8 MB and took the maintainer half an hour over Bluetooth
 * (2026-08-19). Two of those megabytes were avoidable:
 *
 *  - the 2bpp grey form is **never planned**, because the device draws grey from the
 *    baked plane set and reads that form only for a host preview. 1.6 MB of the 2.8 MB.
 *  - the 1:1 level is **deferred**: planned and counted, but not sent until the rider
 *    asks. 713 kB more.
 *
 * The difference between the two matters and is the point of this file. Not planning
 * something says "the device does not need it". Deferring says "not yet" and must never
 * let an item claim it is fully synced.
 */
class WalletSyncDeferralTest {

    private fun greyItem(): Pair<WalletStore, WalletItem> {
        val store = SyncFixtures.store()
        val item = SyncFixtures.addItem(store, "Photo", grey = true)
        return Pair(store, item)
    }

    @Test
    fun the_one_to_one_level_is_not_sent_until_it_is_asked_for() {
        val (store, item) = greyItem()
        val q = SyncFixtures.queue(store)
        q.queue(item.id)

        val pending = q.pending()
        assertTrue("pending must hold something", pending.isNotEmpty())
        assertTrue("no 1:1 asset may be pending by default",
            pending.none { it.cls == SyncClass.ONE_TO_ONE })
        assertTrue("the plan still contains the 1:1 level",
            q.plan.any { it.cls == SyncClass.ONE_TO_ONE })

        q.requestFullQuality(item.id)
        assertTrue("asking for it makes it pending",
            q.pending().any { it.cls == SyncClass.ONE_TO_ONE })
    }

    @Test
    fun an_item_whose_one_to_one_never_went_is_usable_and_never_fully_synced() {
        val (store, item) = greyItem()
        val q = SyncFixtures.queue(store)
        q.queue(item.id)
        // Send everything the queue offers, which is every level except 1:1.
        while (true) {
            val a = q.takeNext() ?: break
            q.confirm(a, "test", 1L)
            q.release()
        }
        val st = q.statusOf(item.id)
        assertTrue("it has to be readable on the device", st.usable)
        assertEquals(SyncState.USABLE_ON_DEVICE, st.state)
        assertFalse("and it must not claim to be complete", st.state.isFullySynced)
        assertTrue("the count says what is missing: ${st.confirmedAssets}/${st.assets}",
            st.confirmedAssets < st.assets)
    }

    @Test
    fun asking_for_full_quality_and_finishing_it_reaches_fully_synced() {
        val (store, item) = greyItem()
        val q = SyncFixtures.queue(store)
        q.queue(item.id)
        q.requestFullQuality(item.id)
        while (true) {
            val a = q.takeNext() ?: break
            q.confirm(a, "test", 1L)
            q.release()
        }
        assertEquals(SyncState.FULLY_SYNCED, q.statusOf(item.id).state)
    }

    @Test
    fun the_deferred_bytes_are_reported_so_a_button_can_say_the_price() {
        val (store, item) = greyItem()
        val q = SyncFixtures.queue(store)
        q.queue(item.id)
        val deferred = q.deferredBytes(item.id)
        val oneToOne = q.plan.filter { it.itemId == item.id && it.cls == SyncClass.ONE_TO_ONE }
            .sumOf { it.bytes.toLong() }
        assertEquals(oneToOne, deferred)
        assertTrue("a grey document defers real bytes: $deferred", deferred > 0L)

        // And what is already confirmed is not offered again.
        val first = q.plan.first { it.itemId == item.id && it.cls == SyncClass.ONE_TO_ONE }
        q.confirm(first, "test", 1L)
        assertEquals(oneToOne - first.bytes, q.deferredBytes(item.id))
    }

    @Test
    fun the_deferred_level_is_the_biggest_thing_in_the_plan() {
        // The arithmetic that produced the change, as a check rather than as prose.
        // Stated per class rather than as a fraction, because the fraction depends on
        // the paper: this fixture is A5, so its 1:1 grid is 3x3 and the queue offers
        // 535 kB of 972 kB, while a real A4 photograph is a 4x4 grid and the split is
        // far harder. What holds on any paper is which class is the bulk.
        val (store, item) = greyItem()
        val q = SyncFixtures.queue(store)
        q.queue(item.id)

        val byClass = q.plan.groupBy { it.cls }
            .mapValues { e -> e.value.sumOf { it.bytes.toLong() } }
        assertEquals("the 1:1 level is the bulk of a document: $byClass",
            SyncClass.ONE_TO_ONE, byClass.maxByOrNull { it.value }!!.key)

        val offered = q.pending().sumOf { it.bytes.toLong() }
        val whole = q.plan.sumOf { it.bytes.toLong() }
        assertTrue("offered $offered of $whole", offered < whole)
        assertEquals("what is held back is exactly the 1:1 level",
            whole - offered, q.deferredBytes(item.id))
    }

    @Test
    fun the_choice_survives_a_restart_because_it_is_persisted() {
        val (store, item) = greyItem()
        val q = SyncFixtures.queue(store)
        q.queue(item.id)
        q.requestFullQuality(item.id)
        store.saveSyncState(q)

        val again = SyncFixtures.queue(store)
        assertTrue("the request has to come back with the state",
            again.pending().any { it.cls == SyncClass.ONE_TO_ONE })
        assertTrue(item.id in store.loadState().fullQuality)

        store.setFullQuality(item.id, false)
        assertFalse(item.id in store.loadState().fullQuality)
    }

    @Test
    fun a_document_that_is_not_grey_defers_its_own_one_to_one_too() {
        // Nothing here is about grey: the 1:1 level is the bulk of a 1bpp document as
        // well (572 kB of 806 kB on an A4 page).
        val store = SyncFixtures.store()
        val item = SyncFixtures.addItem(store, "Scan")
        val q = SyncFixtures.queue(store)
        q.queue(item.id)
        assertTrue(q.pending().none { it.cls == SyncClass.ONE_TO_ONE })
        assertTrue(q.deferredBytes(item.id) > 0L)
    }
}
