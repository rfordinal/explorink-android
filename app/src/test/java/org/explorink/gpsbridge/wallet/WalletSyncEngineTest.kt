package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine loop: send the next pending asset, believe only the device, retry a
 * pass, and **continue** rather than restart when the transport changes under it
 * (brief section 30).
 *
 * Driven with a fake transport so the wire is out of the picture. The wires have
 * their own tests: [WalletWifiTransportTest] against a localhost double that
 * reproduces the device's endpoint quirks, and [WalletBleTransportTest] against a
 * frame-level stub.
 */
class WalletSyncEngineTest {

    private class Recorder : WalletSyncEngine.Listener {
        val confirmed = ArrayList<String>()
        val failed = ArrayList<Pair<String, String>>()
        var finishes = 0
        var lastReason = ""
        var lastRemaining = -1

        override fun onAssetConfirmed(a: SyncAsset, transport: String, detail: String) {
            confirmed.add(a.key)
        }

        override fun onAssetFailed(a: SyncAsset, reason: String) {
            failed.add(Pair(a.key, reason))
        }

        override fun onSyncFinished(confirmed: Int, failed: Int, remaining: Int, reason: String) {
            finishes++
            lastReason = reason
            lastRemaining = remaining
        }
    }

    private fun rig(codes: Boolean = false): Triple<WalletStore, WalletSyncQueue, Recorder> {
        val store = SyncFixtures.store()
        SyncFixtures.addItem(store, "Passport", codes = if (codes)
            listOf(WalletPipeline.CodeRequest(Symbology.QR, "TEST12345")) else emptyList())
        val q = SyncFixtures.queue(store)
        q.queueAll()
        return Triple(store, q, Recorder())
    }

    private fun engine(store: WalletStore, q: WalletSyncQueue, rec: Recorder,
                       persisted: MutableList<Int> = ArrayList()): WalletSyncEngine {
        val e = WalletSyncEngine(SyncFixtures.bytesOf(store), rec,
            persist = {
                persisted.add(it.confirmed.size)
                store.saveSyncState(it)
            }, clock = { 42L })
        e.setQueue(q)
        return e
    }

    @Test
    fun a_clean_run_confirms_every_asset_in_priority_order() {
        val (store, q, rec) = rig(codes = true)
        val t = SyncFixtures.FakeTransport()
        val e = engine(store, q, rec)
        e.useTransport(t)
        e.start()

        assertEquals(q.plan.map { it.key }, rec.confirmed)
        assertEquals(q.plan.map { it.relPath }, t.sentPaths)
        assertTrue(q.pending().isEmpty())
        assertEquals("everything confirmed", rec.lastReason)
        assertFalse(e.running)
        assertEquals(SyncState.FULLY_SYNCED, q.statusOf(store.load().items[0].id).state)
    }

    @Test
    fun the_ledger_is_persisted_once_per_confirmation() {
        val (store, q, rec) = rig()
        val persisted = ArrayList<Int>()
        val e = engine(store, q, rec, persisted)
        e.useTransport(SyncFixtures.FakeTransport())
        e.start()
        assertEquals(q.plan.size, persisted.size)
        assertEquals((1..q.plan.size).toList(), persisted)
        // ...and it really is on disk, so a kill here resumes rather than restarts.
        assertEquals(q.plan.size, store.loadState().confirmed.size)
    }

    @Test
    fun nothing_is_confirmed_when_the_transport_only_reports_progress() {
        val (store, q, rec) = rig()
        val silent = object : WalletTransport {
            override val name = "silent"
            override val label = "silent"
            override val bytesPerSecond = 1000
            override val resumesAcrossSessions = false
            override fun isReady() = true
            override fun cancel() {}
            override fun send(job: SendJob, cb: SendCallback) {
                // The whole file went out and the wire said 200. That is not a verdict.
                cb.onProgress(job.bytes.size)
                cb.onFailed("no verdict from the device", retryable = false)
            }
        }
        val e = engine(store, q, rec)
        e.useTransport(silent)
        e.start()
        assertTrue(rec.confirmed.isEmpty())
        assertEquals(q.plan.size, q.pending().size)
        assertEquals(SyncState.ERROR, q.statusOf(store.load().items[0].id).state)
    }

    @Test
    fun switching_transport_mid_sync_continues_and_does_not_restart() {
        val (store, q, rec) = rig()
        val ble = SyncFixtures.FakeTransport("ble", bytesPerSecond = 8_500)
        val wifi = SyncFixtures.FakeTransport("wifi", bytesPerSecond = 199_000)
        val e = engine(store, q, rec)

        // Three assets over "BLE", then the fourth is held mid-flight -- the rider
        // walked to the hotspot.
        e.useTransport(ble)
        ble.holdNext = false
        var confirmedOnBle = 0
        val stopAfter = 3
        val slow = object : WalletTransport {
            override val name = "ble"
            override val label = "BLE"
            override val bytesPerSecond = 8_500
            override val resumesAcrossSessions = false
            override fun isReady() = true
            override fun cancel() {}
            override fun send(job: SendJob, cb: SendCallback) {
                if (confirmedOnBle >= stopAfter) return       // held, never answered
                confirmedOnBle++
                ble.sentPaths.add(job.relPath)
                cb.onConfirmed("ble ok")
            }
        }
        e.useTransport(slow)
        e.start()
        assertEquals(stopAfter, rec.confirmed.size)
        assertTrue(e.running)                                 // waiting on the held asset

        val before = rec.confirmed.toList()
        e.useTransport(wifi)

        // Everything left went over Wi-Fi, and nothing was sent twice.
        assertEquals(q.plan.size, rec.confirmed.size)
        assertEquals(before, rec.confirmed.take(stopAfter))
        assertEquals(q.plan.size - stopAfter, wifi.sentPaths.size)
        assertEquals(stopAfter, ble.sentPaths.size)
        assertTrue(wifi.sentPaths.none { it in ble.sentPaths })
        assertTrue(q.pending().isEmpty())
    }

    @Test
    fun a_confirmation_records_which_transport_said_so() {
        val (store, q, rec) = rig()
        val e = engine(store, q, rec)
        e.useTransport(SyncFixtures.FakeTransport("wifi"))
        e.start()
        assertTrue(q.confirmed.values.all { it.transport == "wifi" })
        assertTrue(q.confirmed.values.all { it.atMs == 42L })
    }

    @Test
    fun a_retryable_failure_gets_another_pass() {
        val (store, q, rec) = rig()
        val t = SyncFixtures.FakeTransport()
        val doomed = q.plan[2]
        t.failPaths[doomed.relPath] = Pair("ERR busy", true)
        val e = engine(store, q, rec)
        e.useTransport(t)
        e.start()

        // Pass 1 skips it, then the pass count buys it more attempts -- and it keeps
        // failing, so the run ends with a visible ERROR rather than a spinner.
        assertEquals(WalletSyncEngine.MAX_PASSES, t.sentPaths.count { it == doomed.relPath })
        assertEquals(q.plan.size - 1, rec.confirmed.size)
        assertEquals(1, q.pending().size)
        assertTrue(rec.lastReason.startsWith("gave up after"))
        assertEquals(SyncState.ERROR, q.statusOf(store.load().items[0].id).state)
    }

    @Test
    fun a_failure_that_heals_lands_on_the_second_pass() {
        val (store, q, rec) = rig()
        val t = SyncFixtures.FakeTransport()
        val flaky = q.plan[1]
        t.failPaths[flaky.relPath] = Pair("ERR busy", true)
        val e = engine(store, q, rec)
        e.useTransport(t)
        // Heal it as soon as the first attempt has been made: this is the "the link
        // came back" case the extra pass exists for.
        val healer = object : WalletSyncEngine.Listener {
            override fun onAssetFailed(a: SyncAsset, reason: String) {
                rec.onAssetFailed(a, reason)
                t.failPaths.remove(flaky.relPath)
            }

            override fun onAssetConfirmed(a: SyncAsset, transport: String, detail: String) =
                rec.onAssetConfirmed(a, transport, detail)

            override fun onSyncFinished(c: Int, f: Int, r: Int, reason: String) =
                rec.onSyncFinished(c, f, r, reason)
        }
        val e2 = WalletSyncEngine(SyncFixtures.bytesOf(store), healer, clock = { 1L })
        e2.setQueue(q)
        e2.useTransport(t)
        e2.start()
        assertTrue(q.pending().isEmpty())
        assertEquals("everything confirmed", rec.lastReason)
    }

    @Test
    fun a_non_retryable_failure_stops_the_run_instead_of_failing_every_asset() {
        val (store, q, rec) = rig()
        val t = SyncFixtures.FakeTransport()
        t.failPaths[q.plan[1].relPath] = Pair("disconnected", false)
        val e = engine(store, q, rec)
        e.useTransport(t)
        e.start()

        // One confirmation, one failure, and then it stops: marching on against a
        // dead link would bury the real reason under twenty identical errors.
        assertEquals(1, rec.confirmed.size)
        assertEquals(1, rec.failed.size)
        assertTrue(rec.lastReason.contains("disconnected"))
        assertEquals(q.plan.size - 1, rec.lastRemaining)
        assertFalse(e.running)
    }

    @Test
    fun a_transport_that_is_not_ready_never_starts() {
        val (store, q, rec) = rig()
        val e = engine(store, q, rec)
        e.useTransport(SyncFixtures.FakeTransport(ready = false))
        e.start()
        assertFalse(e.running)
        assertTrue(rec.confirmed.isEmpty())
        assertTrue(rec.lastReason.endsWith("not ready"))
    }

    @Test
    fun no_transport_at_all_finishes_with_a_reason_rather_than_throwing() {
        val (store, q, rec) = rig()
        val e = engine(store, q, rec)
        e.start()
        assertFalse(e.running)
        assertEquals("no transport", rec.lastReason)
    }

    @Test
    fun a_file_that_vanished_under_the_run_fails_that_asset_and_says_so() {
        val (store, q, rec) = rig()
        val victim = q.plan.first { !it.isManifest }
        assertTrue(store.assetFile(victim.key, "dat").delete())
        val e = engine(store, q, rec)
        e.useTransport(SyncFixtures.FakeTransport())
        e.start()
        assertTrue(rec.failed.any { it.first == victim.key && it.second == "file missing" })
        assertFalse(rec.confirmed.contains(victim.key))
    }

    @Test
    fun a_file_whose_size_changed_is_refused_rather_than_sent() {
        val (store, q, rec) = rig()
        val victim = q.plan.first { !it.isManifest }
        // The tree changed under the plan. Sending it would confirm a hash the plan
        // no longer describes, which is a confirmation for the wrong bytes.
        store.assetFile(victim.key, "dat").writeBytes(ByteArray(7))
        val t = SyncFixtures.FakeTransport()
        val e = engine(store, q, rec)
        e.useTransport(t)
        e.start()
        assertTrue(rec.failed.any { it.first == victim.key })
        assertFalse(t.sentPaths.contains(victim.relPath))
    }

    @Test
    fun stopping_leaves_the_ledger_intact_so_the_next_run_resumes() {
        val (store, q, rec) = rig()
        val t = SyncFixtures.FakeTransport()
        var sent = 0
        val e = WalletSyncEngine(SyncFixtures.bytesOf(store), object : WalletSyncEngine.Listener {
            override fun onAssetConfirmed(a: SyncAsset, transport: String, detail: String) {
                rec.onAssetConfirmed(a, transport, detail)
            }

            override fun onSyncFinished(c: Int, f: Int, r: Int, reason: String) =
                rec.onSyncFinished(c, f, r, reason)
        }, persist = { store.saveSyncState(it) })
        e.setQueue(q)
        e.useTransport(object : WalletTransport {
            override val name = "half"
            override val label = "half"
            override val bytesPerSecond = 1000
            override val resumesAcrossSessions = false
            override fun isReady() = true
            override fun cancel() {}
            override fun send(job: SendJob, cb: SendCallback) {
                if (sent >= 4) {
                    e.stop("rider walked away")
                    return
                }
                sent++
                t.sentPaths.add(job.relPath)
                cb.onConfirmed("ok")
            }
        })
        e.start()
        assertEquals(4, rec.confirmed.size)
        assertEquals(4, store.loadState().confirmed.size)

        val again = SyncFixtures.queue(store)
        assertEquals(q.plan.size - 4, again.pending().size)
    }

    @Test
    fun an_estimate_never_prints_a_countdown() {
        val ble = SyncFixtures.FakeTransport("ble", bytesPerSecond = 8_500)
        val wifi = SyncFixtures.FakeTransport("wifi", bytesPerSecond = 199_000)
        // Brief section 38: the time "nesmie vytvarat falosnu presnost". So there is
        // no digit-level figure anywhere in the phrasing.
        assertEquals("a few seconds", wifi.estimateText(1_000_000))
        // 1 MB at the measured 8.5 kB/s is 117 s. It is reported as a phrase, not
        // as "1 m 57 s", because the real rate depends on the connection interval
        // and on whatever else is talking to the device.
        assertEquals("roughly a minute or two", ble.estimateText(1_000_000))
        assertEquals("roughly 10 minutes", ble.estimateText(5_000_000))
        assertEquals("nothing pending", ble.estimateText(0))
        assertEquals("under a minute", ble.estimateText(200_000))
        assertTrue(ble.estimateText(5_000_000).startsWith("roughly"))
    }
}
