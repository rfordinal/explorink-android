package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The numbers a progress bar is drawn from.
 *
 * These exist because of a complaint that no screen could tell a working transfer
 * from a stalled one: confirmed bytes alone step by whole assets, and one 1:1 page
 * image is over a megabyte, so a bar fed by confirmations stands still for a minute
 * at a time. The fix counts bytes on the wire, and blurring "sent" with "confirmed"
 * is only ever allowed to move a bar -- never to claim a document is on the device.
 * That is what the last test here pins.
 */
class WalletSyncProgressTest {

    private fun queued(): Pair<WalletItem, WalletSyncQueue> {
        val store = SyncFixtures.store()
        val item = SyncFixtures.addItem(store, "Passport")
        val q = SyncFixtures.queue(store)
        q.queueAll()
        return Pair(item, q)
    }

    @Test
    fun nothing_sent_is_zero_and_never_one() {
        val (item, q) = queued()
        assertEquals(0f, q.statusOf(item.id).fraction, 0f)
        assertEquals(0f, q.totals().fraction, 0f)
    }

    @Test
    fun bytes_on_the_wire_move_the_fraction_before_any_confirmation() {
        val (item, q) = queued()
        val a = q.plan.first { it.itemId == item.id }
        q.takeNext()  // the manifest goes first
        q.confirm(q.plan.first { it.isManifest }, "test", 1L)
        q.release()

        // Take the item's own asset and report half of it sent.
        var taken = q.takeNext()
        while (taken != null && taken.key != a.key) {
            q.confirm(taken, "test", 1L)
            q.release()
            taken = q.takeNext()
        }
        assertEquals(a.key, taken?.key)
        q.progress(a.key, (a.bytes / 2).toInt())

        val st = q.statusOf(item.id)
        assertTrue("in-flight bytes must be counted", st.inFlightBytes > 0L)
        assertTrue("the bar must have moved", st.fraction > 0f)
        assertTrue("and not be full", st.fraction < 1f)
        assertTrue("the whole wallet moves too", q.totals().inFlightBytes > 0L)
    }

    @Test
    fun a_transport_reporting_more_than_it_was_given_cannot_push_past_the_end() {
        val (item, q) = queued()
        val a = q.plan.first { it.itemId == item.id }
        q.takeNext()
        while (q.inFlight != null && q.inFlight != a.key) {
            q.confirm(q.plan.first { it.key == q.inFlight }, "test", 1L)
            q.release()
            q.takeNext()
        }
        q.progress(a.key, (a.bytes * 3).toInt())
        assertEquals(a.bytes.toLong(), q.statusOf(item.id).inFlightBytes)
        assertTrue(q.statusOf(item.id).fraction <= 1f)
        assertTrue(q.totals().fraction <= 1f)
    }

    @Test
    fun the_manifest_is_charged_to_no_item() {
        val store = SyncFixtures.store()
        val a1 = SyncFixtures.addItem(store, "One")
        val a2 = SyncFixtures.addItem(store, "Two")
        val q = SyncFixtures.queue(store)
        q.queueAll()
        val manifest = q.plan.first { it.isManifest }
        q.takeNext()
        assertEquals(manifest.key, q.inFlight)
        q.progress(manifest.key, manifest.bytes.toInt())

        // Neither document may show progress for bytes that are not its own: the
        // manifest belongs to the wallet, and charging it to every queued item would
        // show movement on a document nothing is being sent for.
        assertEquals(0L, q.statusOf(a1.id).inFlightBytes)
        assertEquals(0L, q.statusOf(a2.id).inFlightBytes)
        assertTrue("the whole-wallet bar still moves", q.totals().inFlightBytes > 0L)
    }

    @Test
    fun bytes_sent_are_not_a_confirmation() {
        val (item, q) = queued()
        // The manifest goes first and stays unconfirmed on purpose: with nothing
        // confirmed anywhere, every byte here is "sent" and not one is "on the card".
        val a = q.takeNext()!!
        q.progress(a.key, a.bytes)

        val st = q.statusOf(item.id)
        assertEquals("nothing may count as confirmed", 0, st.confirmedAssets)
        assertTrue(st.state.toString(), st.state != SyncState.FULLY_SYNCED)
        assertTrue(st.state.toString(), st.state != SyncState.USABLE_ON_DEVICE)
        // An unconfirmed asset cannot be released and re-taken forever either: the
        // queue hands out the same one again, which is what makes a retry a retry.
        q.release()
        assertEquals(a.key, q.takeNext()?.key)
    }

    @Test
    fun the_session_says_nothing_when_no_sync_is_running() {
        WalletSyncSession.clear()
        assertEquals(null, WalletSyncSession.statusLine())
        assertEquals(null, WalletSyncSession.queue)
        assertTrue(!WalletSyncSession.running)
    }

    @Test
    fun the_session_line_names_the_transport_and_a_percentage() {
        val (_, q) = queued()
        WalletSyncSession.publish(q, "BLE", running = true)
        val line = WalletSyncSession.statusLine() ?: ""
        assertTrue(line, line.contains("BLE"))
        assertTrue(line, line.contains("%"))
        assertTrue(line, line.startsWith("syncing"))

        // A screen that is open but not sending must not read as sending.
        WalletSyncSession.publish(q, "BLE", running = false)
        assertTrue(WalletSyncSession.statusLine()!!.contains("not sending"))
        WalletSyncSession.clear()
    }
}
