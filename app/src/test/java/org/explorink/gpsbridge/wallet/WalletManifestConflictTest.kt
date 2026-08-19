package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The silent failure, and the check that ends it.**
 *
 * 2026-08-19, on the real X4: the phone pushed a cleartext wallet onto a card that
 * already held `manifest.enc`. All 25 files landed, the device CRC-verified every
 * one, the run finished `failed=0 remaining=0` after 6.5 minutes -- and
 * `CMD:WALLETSTATUS` still answered `manifest=enc`, so the rider saw the old wallet
 * and nothing anywhere reported a problem (`docs/wallet-plan.md` 7l).
 *
 * Every test here is one sentence of that failure turned into an assertion.
 */
class WalletManifestConflictTest {

    private val clear = ManifestKind.CLEARTEXT
    private val enc = ManifestKind.ENCRYPTED

    // --- the decision ------------------------------------------------------

    @Test
    fun a_cleartext_sync_onto_an_encrypted_card_is_a_conflict_and_is_invisible() {
        val c = WalletManifestConflict.of(clear, enc)
        assertNotNull(c)
        assertTrue("the device would keep reading its own manifest", c!!.invisible)
        assertTrue(c.message.contains("invisible"))
        // The remedy is the rider's, and it is spelled out.
        assertTrue(c.remedy.contains("will not delete"))
    }

    @Test
    fun an_encrypted_sync_onto_a_cleartext_card_is_a_conflict_but_not_invisible() {
        val c = WalletManifestConflict.of(enc, clear)
        assertNotNull(c)
        assertFalse("the device switches to the new manifest.enc", c!!.invisible)
        assertTrue(c.message.contains("stays on"))
    }

    @Test
    fun a_card_holding_both_hides_a_cleartext_sync_just_the_same() {
        // The half-finished-switch case. The device prefers manifest.enc whenever one
        // exists, so BOTH behaves exactly like ENCRYPTED for our purposes.
        val c = WalletManifestConflict.of(clear, ManifestKind.BOTH)
        assertNotNull(c)
        assertTrue(c!!.invisible)
    }

    @Test
    fun matching_kinds_and_an_empty_card_are_not_conflicts() {
        assertNull(WalletManifestConflict.of(clear, clear))
        assertNull(WalletManifestConflict.of(enc, enc))
        assertNull(WalletManifestConflict.of(clear, ManifestKind.NONE))
        assertNull(WalletManifestConflict.of(enc, ManifestKind.NONE))
    }

    /**
     * `UNKNOWN` is not an all-clear and not a conflict either.
     *
     * BLE cannot read the card at all -- there is no read frame in the protocol -- so
     * treating "I could not ask" as a conflict would block every BLE sync, and
     * treating it as agreement would be the same lie in a different place. The gap is
     * left open on purpose and written down.
     */
    @Test
    fun an_unknown_card_is_neither_a_conflict_nor_a_clean_bill() {
        assertNull(WalletManifestConflict.of(clear, ManifestKind.UNKNOWN))
        assertNull(WalletManifestConflict.of(enc, ManifestKind.UNKNOWN))
        assertEquals("unknown", ManifestKind.UNKNOWN.label)
    }

    @Test
    fun the_kind_comes_from_which_files_exist() {
        assertEquals(ManifestKind.NONE, ManifestKind.of(false, false))
        assertEquals(clear, ManifestKind.of(true, false))
        assertEquals(enc, ManifestKind.of(false, true))
        assertEquals(ManifestKind.BOTH, ManifestKind.of(true, true))
        assertTrue(ManifestKind.BOTH.hasEncrypted)
        assertTrue(ManifestKind.BOTH.hasCleartext)
        assertFalse(ManifestKind.UNKNOWN.hasEncrypted)
    }

    // --- the engine refuses ------------------------------------------------

    private class Pipe(var ready: Boolean = true) : WalletTransport {
        override val name = "double"
        override val label = "Double"
        override val bytesPerSecond = 100_000
        override val resumesAcrossSessions = false
        var sent = 0
        override fun isReady() = ready
        override fun cancel() {}
        override fun send(job: SendJob, cb: SendCallback) {
            sent++
            cb.onProgress(job.bytes.size)
            cb.onConfirmed("double ok")
        }
    }

    private class Log : WalletSyncEngine.Listener {
        val conflicts = ArrayList<ManifestConflict>()
        var finishReason: String? = null
        var confirmed = 0
        override fun onManifestConflict(conflict: ManifestConflict) {
            conflicts.add(conflict)
        }
        override fun onSyncFinished(confirmed: Int, failed: Int, remaining: Int, reason: String) {
            this.confirmed = confirmed
            finishReason = reason
        }
    }

    private fun engineWith(local: ManifestKind, card: ManifestKind):
        Triple<WalletSyncEngine, Pipe, Log> {
        val store = SyncFixtures.store()
        SyncFixtures.addItem(store, "Doc")
        store.queueAll()
        val wallet = store.load()
        val plan = WalletSyncPlan.build(wallet, store.treeDir)
        val log = Log()
        val engine = WalletSyncEngine(
            bytes = object : WalletSyncEngine.AssetBytes {
                override fun read(a: SyncAsset): ByteArray? = ByteArray(a.bytes)
            },
            listener = log)
        engine.setQueue(WalletSyncQueue(plan, queued = wallet.items.map { it.id }.toSet()))
        engine.localManifest = local
        engine.cardManifest = card
        val pipe = Pipe()
        engine.useTransport(pipe)
        return Triple(engine, pipe, log)
    }

    @Test
    fun the_engine_refuses_to_start_a_sync_that_would_be_invisible() {
        val (engine, pipe, log) = engineWith(clear, enc)
        engine.start()
        assertFalse("the run must not begin", engine.running)
        assertEquals("not one byte may go over", 0, pipe.sent)
        assertEquals(1, log.conflicts.size)
        assertTrue(log.finishReason!!.startsWith("manifest conflict:"))
    }

    @Test
    fun the_rider_can_say_sync_anyway_and_then_it_runs() {
        val (engine, pipe, log) = engineWith(clear, enc)
        engine.start(ignoreManifestConflict = true)
        // The conflict is still reported -- consent is not silence.
        assertEquals(1, log.conflicts.size)
        assertTrue("assets went over", pipe.sent > 0)
        assertEquals("everything confirmed", log.finishReason)
    }

    @Test
    fun consent_does_not_carry_to_the_next_plan() {
        val (engine, pipe, _) = engineWith(clear, enc)
        engine.start(ignoreManifestConflict = true)
        val before = pipe.sent
        // A rebuilt plan can be a different wallet; the old answer is not consent to it.
        engine.setQueue(engine.queue)
        engine.start()
        assertEquals("nothing new may go over", before, pipe.sent)
    }

    @Test
    fun a_matching_card_starts_with_no_question_at_all() {
        val (engine, pipe, log) = engineWith(clear, clear)
        engine.start()
        assertEquals(0, log.conflicts.size)
        assertTrue(pipe.sent > 0)
    }

    @Test
    fun a_ble_sync_with_an_unknown_card_still_runs() {
        val (engine, pipe, log) = engineWith(clear, ManifestKind.UNKNOWN)
        engine.start()
        assertEquals(0, log.conflicts.size)
        assertTrue(pipe.sent > 0)
    }

    /** The default: a transport that cannot ask must answer `UNKNOWN`, not `NONE`. */
    @Test
    fun a_transport_that_cannot_ask_reports_unknown() {
        assertEquals(ManifestKind.UNKNOWN, Pipe().probeCardManifest())
    }

    // --- the store's own side ----------------------------------------------

    @Test
    fun a_cleartext_store_writes_manifest_json_and_says_so() {
        val store = SyncFixtures.store()
        SyncFixtures.addItem(store, "Doc")
        assertEquals(ManifestKind.CLEARTEXT, store.treeKind())
        assertEquals(ManifestKind.CLEARTEXT, store.load().manifestKind)
        assertEquals(WalletFormat.MANIFEST_CLEAR_NAME, store.manifestFile.name)
        val plan = WalletSyncPlan.build(store.load(), store.treeDir)
        assertEquals("wallet/${WalletFormat.MANIFEST_CLEAR_NAME}",
            plan.first { it.isManifest }.relPath)
    }
}
