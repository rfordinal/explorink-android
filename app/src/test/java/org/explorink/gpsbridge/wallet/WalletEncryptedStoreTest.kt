package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The whole phone path with encryption on: store, tree, manifest, sync plan.
 *
 * [WalletCryptoTest] checks the primitives and [WalletCryptoParityTest] checks the
 * bytes against the generator. This checks the things only the store can get wrong --
 * which manifest file exists, what happens to the other one, whether a sidecar
 * survives, and what the sync plan then says it is going to send.
 */
class WalletEncryptedStoreTest {

    private fun store(): WalletStore {
        val root = Files.createTempDirectory("wallet-enc").toFile()
        return WalletStore(root, keys = WalletKeyVault(File(root, "wallet.key"),
            InMemoryKeyEncryptionKey()))
    }

    private fun encryptedStore(): WalletStore = store().also {
        assertTrue("encryption is on by default", it.applyDefaultEncryption())
    }

    @Test
    fun encryption_is_on_by_default_for_a_fresh_wallet() {
        val s = store()
        assertFalse(s.encrypted)
        assertTrue(s.applyDefaultEncryption())
        assertTrue(s.encrypted)
        assertEquals(WalletFormat.MANIFEST_ENC_NAME, s.manifestFile.name)
    }

    @Test
    fun a_store_with_no_key_store_stays_cleartext() {
        val s = SyncFixtures.store()
        assertFalse(s.applyDefaultEncryption())
        assertFalse(s.encrypted)
        assertEquals(WalletFormat.MANIFEST_CLEAR_NAME, s.manifestFile.name)
    }

    /**
     * Turning encryption on later is **refused**, not silently half-done: the asset id
     * recipe is crypto-scoped, so the ids of everything already written would change
     * while the ciphertext for them does not exist. Converting needs a re-import.
     */
    @Test
    fun encryption_cannot_be_switched_on_under_an_existing_cleartext_wallet() {
        val s = store()
        SyncFixtures.addItem(s, "Passport")
        assertFalse(s.encrypted)
        assertFalse("must refuse once there is an item", s.applyDefaultEncryption())
        assertFalse(s.encrypted)
        assertEquals(ManifestKind.CLEARTEXT, s.treeKind())
    }

    @Test
    fun an_encrypted_wallet_writes_manifest_enc_and_no_cleartext_copy() {
        val s = encryptedStore()
        SyncFixtures.addItem(s, "Passport")
        assertEquals(ManifestKind.ENCRYPTED, s.treeKind())
        assertTrue(File(s.treeDir, WalletFormat.MANIFEST_ENC_NAME).isFile)
        assertFalse(File(s.treeDir, WalletFormat.MANIFEST_CLEAR_NAME).isFile)
        val wallet = s.load()
        assertEquals(1, wallet.items.size)
        assertEquals(ManifestKind.ENCRYPTED, wallet.manifestKind)
        assertEquals("wallet-crypto-v1", Json.asString(wallet.crypto!!["scheme"]))
    }

    /**
     * A stale `manifest.json` beside `manifest.enc` is exactly the file that makes a
     * later cleartext sync look like it worked, so the writer removes it from **our
     * own tree**. Nothing is ever removed from the card.
     */
    @Test
    fun a_stale_cleartext_manifest_in_our_own_tree_is_removed() {
        val s = encryptedStore()
        s.treeDir.mkdirs()
        val stale = File(s.treeDir, WalletFormat.MANIFEST_CLEAR_NAME)
        stale.writeText("{\"formatVersion\": 1}")
        SyncFixtures.addItem(s, "Passport")
        assertFalse(stale.isFile)
        assertEquals(ManifestKind.ENCRYPTED, s.treeKind())
    }

    @Test
    fun the_second_write_keeps_one_backup_of_the_previous_manifest() {
        val s = encryptedStore()
        SyncFixtures.addItem(s, "One")
        val first = File(s.treeDir, WalletFormat.MANIFEST_ENC_NAME).readBytes()
        val bak = File(s.treeDir, WalletFormat.MANIFEST_BAK_NAME)
        assertFalse("nothing to back up on the first write", bak.isFile)
        SyncFixtures.addItem(s, "Two")
        assertTrue(bak.isFile)
        assertArrayEquals(first, bak.readBytes())
        // And the backup is a readable manifest of the previous version.
        val key = s.keys.loadKey()!!
        val old = Wallet.fromManifestJson(
            WalletCrypto.decryptManifest(key, bak.readBytes()).toString(Charsets.UTF_8))
        assertEquals(1, old.items.size)
        assertEquals(2, s.load().items.size)
    }

    @Test
    fun an_encrypted_tree_holds_no_rle_sidecar_at_all() {
        val s = encryptedStore()
        val item = SyncFixtures.addItem(s, "Passport")
        var dats = 0
        for (id in Wallet(1, 1, s.panelName, listOf(item)).assetIds()) {
            assertTrue(id, s.assetFile(id, "dat").isFile)
            assertFalse("$id must have no sidecar", s.assetFile(id, "rle").isFile)
            dats++
        }
        assertEquals(3, dats)
        // rleLen is null in the manifest, not zero: absence is the signal.
        val level = s.load().items[0].pages[0].levels.getValue("fit")
        assertNull(level.pageImage!!.rleLen)
        assertTrue(File(s.treeDir, WalletFormat.MANIFEST_ENC_NAME)
            .readBytes().isNotEmpty())
        assertTrue(s.load().toManifestJson().contains("\"rleLen\": null"))
    }

    @Test
    fun every_asset_body_decrypts_back_to_its_header_hash() {
        // The plaintext hash in the cleartext header is the only integrity check an
        // encrypted asset has, so this is what a wrong key or a bad card would trip.
        val s = encryptedStore()
        val item = SyncFixtures.addItem(s, "Passport")
        val cipher = Aes256CtrCipher(s.keys.loadKey()!!)
        for (level in item.pages[0].levels.values) {
            val pi = level.pageImage!!
            val dat = s.assetFile(pi.assetId, "dat").readBytes()
            val header = dat.copyOfRange(0, WalletFormat.ASSET_HEADER_LEN)
            val plain = cipher.open(pi.assetId, header,
                dat.copyOfRange(WalletFormat.ASSET_HEADER_LEN, dat.size))
            assertEquals(pi.rawLen, plain.size)
            assertEquals(pi.sha256, WalletFormat.sha256Hex(plain))
            assertEquals(WalletFormat.hex(header.copyOfRange(24, 32)),
                WalletFormat.sha256Hex(plain).substring(0, 16))
            assertEquals(WalletFormat.FLAG_ENCRYPTED,
                header[20].toInt() and WalletFormat.FLAG_ENCRYPTED)
        }
    }

    @Test
    fun the_sync_plan_sends_manifest_enc_and_never_manifest_json() {
        val s = encryptedStore()
        SyncFixtures.addItem(s, "Passport")
        s.queueAll()
        val plan = WalletSyncPlan.build(s.load(), s.treeDir)
        val manifest = plan.first { it.isManifest }
        assertEquals("wallet/${WalletFormat.MANIFEST_ENC_NAME}", manifest.relPath)
        assertTrue(plan.none { it.relPath.endsWith(".json") })
        assertTrue(plan.none { it.relPath.endsWith(".rle") })
        // And the bytes planned are the container's, not the plaintext's.
        assertEquals(File(s.treeDir, WalletFormat.MANIFEST_ENC_NAME).length().toInt(),
            manifest.bytes)
    }

    @Test
    fun losing_the_key_makes_the_wallet_unreadable_rather_than_empty() {
        // Stated as a test because the alternative -- returning an empty wallet -- would
        // look like "the rider has no documents" and could then be written over.
        val root = Files.createTempDirectory("wallet-enc").toFile()
        val kek = InMemoryKeyEncryptionKey()
        val s = WalletStore(root, keys = WalletKeyVault(File(root, "wallet.key"), kek))
        s.applyDefaultEncryption()
        SyncFixtures.addItem(s, "Passport")
        val gone = WalletStore(root, keys = WalletKeyVault(File(root, "wallet.key"),
            InMemoryKeyEncryptionKey()))
        try {
            gone.load()
            throw AssertionError("a wallet with no usable key must not load as empty")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Throwable) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun a_grey_encrypted_document_still_emits_both_grey_assets_and_no_sidecars() {
        val s = encryptedStore()
        val item = SyncFixtures.addItem(s, "Scan", grey = true)
        assertTrue(item.grey)
        for (name in WalletFormat.LEVELS) {
            val level = item.pages[0].levels.getValue(name)
            assertNotNull(level.greyPageImage)
            assertNotNull(level.greyPlanes)
            assertNull(level.greyPageImage!!.rleLen)
            assertNull(level.greyPlanes!!.rleLen)
        }
        for (id in Wallet(1, 1, s.panelName, listOf(item)).assetIds()) {
            assertFalse(s.assetFile(id, "rle").isFile)
        }
    }

    @Test
    fun the_ids_of_an_encrypted_tree_differ_from_the_cleartext_ones() {
        val enc = encryptedStore()
        val clear = SyncFixtures.store()
        val a = SyncFixtures.addItem(enc, "Passport")
        val b = SyncFixtures.addItem(clear, "Passport")
        // Same document, same title, same pixels -- and no shared path on one card.
        assertEquals(a.id, b.id)
        val encIds = Wallet(1, 1, enc.panelName, listOf(a)).assetIds().toSet()
        val clearIds = Wallet(1, 1, clear.panelName, listOf(b)).assetIds().toSet()
        assertEquals(encIds.size, clearIds.size)
        assertTrue("no asset path may be shared across the crypto boundary",
            encIds.intersect(clearIds).isEmpty())
    }
}
