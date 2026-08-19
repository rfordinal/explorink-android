package org.explorink.gpsbridge.wallet

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The Wi-Fi transport against a localhost double ([WifiDeviceDouble]) that
 * reproduces the device's real endpoint behaviour, quirks and all.
 *
 * The two things being tested are the two that would silently ruin a card:
 *
 *  - **stage, verify, swap, in that order.** A host script that deleted first and
 *    uploaded second wiped a card's manifest and wrote nothing back -- brief
 *    section 31's failure exactly. The order is asserted from the double's own
 *    request log, not from reading the code.
 *  - **a 200 from `/upload` is not a confirmation.** `GET /api/hash` is, and when
 *    the two disagree nothing is confirmed and the live file is left alone.
 */
class WalletWifiTransportTest {

    private lateinit var device: WifiDeviceDouble
    private lateinit var transport: WalletWifiTransport

    @Before
    fun setUp() {
        device = WifiDeviceDouble().start()
        transport = WalletWifiTransport(device.host, device.port)
        File(device.cardRoot, "trailink").mkdirs()
    }

    @After
    fun tearDown() {
        device.stop()
    }

    private class Result {
        var confirmed: String? = null
        var failed: String? = null
        var retryable = true
        var progress = 0
    }

    private fun send(relPath: String, bytes: ByteArray): Result {
        val r = Result()
        transport.send(SendJob(relPath, bytes, WalletFormat.sha256Hex(bytes)),
            object : SendCallback {
                override fun onProgress(sentBytes: Int) {
                    r.progress = sentBytes
                }

                override fun onConfirmed(detail: String) {
                    r.confirmed = detail
                }

                override fun onFailed(reason: String, retryable: Boolean) {
                    r.failed = reason
                    r.retryable = retryable
                }
            })
        return r
    }

    private fun card(rel: String): File = File(device.cardRoot, "trailink/$rel")

    @Test
    fun the_device_answers_a_probe() {
        assertTrue(transport.probe())
        assertTrue(transport.lastProbeDetail.startsWith("HTTP 200"))
    }

    @Test
    fun a_fresh_asset_is_uploaded_hashed_and_renamed_into_place() {
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        val r = send("wallet/ab/abcdef0123456789.dat", bytes)

        assertNull(r.failed)
        assertTrue(r.confirmed!!.startsWith("api/hash 4096 B sha256 "))
        assertEquals(4096, r.progress)
        assertArrayEquals_(bytes, card("wallet/ab/abcdef0123456789.dat").readBytes())
        // No temp left over.
        assertFalse(card("wallet/ab/abcdef0123456789.dat.part").exists())
    }

    @Test
    fun the_shard_is_created_because_upload_will_not_do_it() {
        assertFalse(card("wallet/cd").exists())
        val r = send("wallet/cd/1122334455667788.dat", ByteArray(64) { 7 })
        assertNull(r.failed)
        assertTrue(card("wallet/cd").isDirectory)
        // Both components, parent first: `wallet` then `cd`.
        val mkdirs = device.requests.filter { it.startsWith("POST /mkdir") }
        assertEquals(2, mkdirs.size)
    }

    @Test
    fun a_second_asset_in_the_same_shard_does_not_mkdir_again() {
        send("wallet/ef/aaaaaaaaaaaaaaaa.dat", ByteArray(32) { 1 })
        val before = device.requests.count { it.startsWith("POST /mkdir") }
        send("wallet/ef/bbbbbbbbbbbbbbbb.dat", ByteArray(32) { 2 })
        assertEquals(before, device.requests.count { it.startsWith("POST /mkdir") })
    }

    @Test
    fun the_order_is_upload_hash_delete_rename_and_never_delete_first() {
        val path = "wallet/ab/1111111111111111.dat"
        card("wallet/ab").mkdirs()
        card(path).writeBytes(ByteArray(100) { 9 })      // a good old version on the card
        device.requests.clear()

        val r = send(path, ByteArray(200) { 3 })
        assertNull(r.failed)

        // "POST /upload?path=... HTTP/1.1" -> "/upload"; a delete carries its target.
        val ops = device.requests.map {
            val parts = it.split(' ')
            if (parts[1] == "/delete") "/delete ${parts.getOrNull(2)}" else parts[1].substringBefore('?')
        }
        val abs = "/trailink/$path"
        val uploadAt = ops.indexOfFirst { it == "/upload" }
        val hashAt = ops.indexOfFirst { it == "/api/hash" }
        val renameAt = ops.indexOfLast { it == "/rename" }
        val liveDeleteAt = ops.indexOfFirst { it == "/delete $abs" }

        assertTrue("upload before hash", uploadAt < hashAt)
        // THE rule: the live file is deleted only after its replacement has been
        // verified on the card, and only because /rename refused to overwrite it.
        assertTrue("the live file is deleted at all", liveDeleteAt > 0)
        assertTrue("hash before the live delete", hashAt < liveDeleteAt)
        assertTrue("live delete before the final rename", liveDeleteAt < renameAt)
        // And nothing touched the live file before it was verified. A host script that
        // deleted first and uploaded second wiped a card's manifest and wrote nothing
        // back -- brief section 31.
        assertTrue("no delete of the live path before the hash",
            ops.take(hashAt).none { it == "/delete $abs" })
        assertArrayEquals_(ByteArray(200) { 3 }, card(path).readBytes())
    }

    @Test
    fun a_fresh_card_costs_no_wasted_delete_at_all() {
        // Deleting the temp and the live file unconditionally cost one wasted round
        // trip each per asset -- 160 of them on an 80-asset wallet, every one a 400.
        // Optimistic first, repair on the specific error.
        val r = send("wallet/ab/7777777777777777.dat", ByteArray(128) { 4 })
        assertNull(r.failed)
        assertEquals(0, device.requests.count { it.startsWith("POST /delete") })
        assertEquals(1, device.requests.count { it.startsWith("POST /upload") })
        assertEquals(1, device.requests.count { it.startsWith("POST /rename") })
    }

    @Test
    fun a_hash_mismatch_confirms_nothing_and_leaves_the_old_file_alone() {
        val path = "wallet/ab/2222222222222222.dat"
        card("wallet/ab").mkdirs()
        val old = ByteArray(100) { 9 }
        card(path).writeBytes(old)

        // The upload succeeds with a 200 and the card holds something else. This is
        // the case a "200 means written" transport would have called success.
        device.corruptNextUpload = true
        val r = send(path, ByteArray(200) { 3 })

        assertNull(r.confirmed)
        assertTrue(r.failed!!.startsWith("hash mismatch"))
        assertTrue(r.retryable)
        // The live file is untouched, which is the whole point of staging.
        assertArrayEquals_(old, card(path).readBytes())
        // And the bad temp is gone, so the retry is not blocked by it.
        assertFalse(card("$path.part").exists())
    }

    @Test
    fun a_leftover_temp_from_a_killed_run_does_not_block_the_retry() {
        val path = "wallet/ab/3333333333333333.dat"
        card("wallet/ab").mkdirs()
        card("$path.part").writeBytes(ByteArray(11) { 1 })
        // `/upload` refuses to overwrite, so without deleting the temp first this
        // would fail for ever.
        val r = send(path, ByteArray(64) { 5 })
        assertNull(r.failed)
        assertArrayEquals_(ByteArray(64) { 5 }, card(path).readBytes())
    }

    @Test
    fun a_failed_upload_is_retryable_and_confirms_nothing() {
        device.failUploads = 1
        val r = send("wallet/ab/4444444444444444.dat", ByteArray(64) { 5 })
        assertNull(r.confirmed)
        assertTrue(r.failed!!.startsWith("upload 400"))
        assertTrue(r.retryable)
    }

    @Test
    fun a_firmware_with_no_hash_endpoint_can_never_confirm() {
        // An older device: the upload lands and there is no way to check it. The
        // transport must refuse to confirm rather than trust the 200 -- otherwise
        // "on device" would mean "we posted it".
        device.hashEndpointMissing = true
        val path = "wallet/ab/5555555555555555.dat"
        val r = send(path, ByteArray(64) { 5 })
        assertNull(r.confirmed)
        assertTrue(r.failed!!.startsWith("no hash for"))
        // The bytes are on the card under the TEMP name, and the live name is
        // untouched, so nothing is half-swapped.
        assertTrue(card("$path.part").isFile)
        assertFalse(card(path).exists())
    }

    @Test
    fun a_dead_host_fails_without_being_retried_forever() {
        device.stop()
        val r = send("wallet/ab/6666666666666666.dat", ByteArray(64) { 5 })
        assertNull(r.confirmed)
        // -1 from the request layer means "not answering", and that is not retryable:
        // marching through the queue against a dead hotspot would fail every asset
        // and bury the reason.
        assertFalse(r.retryable)
    }

    @Test
    fun a_whole_wallet_goes_over_and_every_file_matches_byte_for_byte() {
        val store = SyncFixtures.store()
        SyncFixtures.addItem(store, "Passport", codes = listOf(
            WalletPipeline.CodeRequest(Symbology.QR, "TEST12345")))
        val q = SyncFixtures.queue(store)
        q.queueAll()
        val rec = ArrayList<String>()
        val engine = WalletSyncEngine(SyncFixtures.bytesOf(store),
            object : WalletSyncEngine.Listener {
                override fun onAssetConfirmed(a: SyncAsset, transport: String, detail: String) {
                    rec.add(a.key)
                }

                override fun onAssetFailed(a: SyncAsset, reason: String) {
                    throw AssertionError("${a.key}: $reason")
                }
            }, persist = { store.saveSyncState(it) })
        engine.setQueue(q)
        engine.useTransport(transport)
        engine.start()

        assertEquals(q.plan.size, rec.size)
        assertTrue(q.pending().isEmpty())
        // The card's bytes, compared against the phone's own tree. This is the check
        // `/api/hash` performs on the device, done again from the outside.
        for (a in q.plan) {
            val onCard = card(a.relPath)
            assertTrue("${a.relPath} is on the card", onCard.isFile)
            assertEquals(a.sha256, WalletFormat.sha256Hex(onCard.readBytes()))
        }
        // Nothing was left staged.
        assertTrue(card("wallet").walk().none { it.name.endsWith(".part") })
        assertEquals(SyncState.FULLY_SYNCED, q.statusOf(store.load().items[0].id).state)
    }

    @Test
    fun a_second_sync_of_an_unchanged_wallet_sends_nothing_at_all() {
        val store = SyncFixtures.store()
        SyncFixtures.addItem(store, "Passport")
        val q = SyncFixtures.queue(store)
        q.queueAll()
        val engine = WalletSyncEngine(SyncFixtures.bytesOf(store),
            object : WalletSyncEngine.Listener {}, persist = { store.saveSyncState(it) })
        engine.setQueue(q)
        engine.useTransport(transport)
        engine.start()
        assertTrue(q.pending().isEmpty())

        device.requests.clear()
        val again = SyncFixtures.queue(store)
        assertTrue(again.pending().isEmpty())
        val engine2 = WalletSyncEngine(SyncFixtures.bytesOf(store),
            object : WalletSyncEngine.Listener {})
        engine2.setQueue(again)
        engine2.useTransport(transport)
        engine2.start()
        // Not one request. And the card would have refused an overwrite anyway, which
        // is exactly the failure a delta-less sync would hit on the second run.
        assertTrue(device.requests.isEmpty())
    }

    @Test
    fun a_title_change_puts_one_small_file_on_the_wire() {
        val store = SyncFixtures.store()
        SyncFixtures.addItem(store, "Passport")
        val q = SyncFixtures.queue(store)
        q.queueAll()
        val e = WalletSyncEngine(SyncFixtures.bytesOf(store),
            object : WalletSyncEngine.Listener {}, persist = { store.saveSyncState(it) })
        e.setQueue(q)
        e.useTransport(transport)
        e.start()

        val wallet = store.load()
        store.addItem(wallet.items[0].copy(title = "Passport (renewed)"))
        device.requests.clear()

        val delta = SyncFixtures.queue(store)
        val e2 = WalletSyncEngine(SyncFixtures.bytesOf(store),
            object : WalletSyncEngine.Listener {}, persist = { store.saveSyncState(it) })
        e2.setQueue(delta)
        e2.useTransport(transport)
        e2.start()

        assertEquals(1, device.requests.count { it.startsWith("POST /upload") })
        assertTrue(delta.pending().isEmpty())
        val onCard = File(device.cardRoot, "trailink/wallet/manifest.json").readText()
        assertTrue(onCard.contains("Passport (renewed)"))
    }

    private fun assertArrayEquals_(a: ByteArray, b: ByteArray) {
        assertEquals(a.size, b.size)
        for (i in a.indices) if (a[i] != b[i]) throw AssertionError("byte $i differs")
    }
}
