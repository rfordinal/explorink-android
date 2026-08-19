package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The store: atomic manifest writes, a monotone `walletVersion`, deletion and
 * reordering on the phone (the device is read-only, brief section 21), and no
 * orphaned assets left behind.
 *
 * Plain temp directories -- the store takes a `File`, not a `Context`, so all of
 * this runs on the laptop.
 */
class WalletStoreTest {

    private fun tempStore(): WalletStore =
        WalletStore(Files.createTempDirectory("wallet-store").toFile())

    private fun item(id: String, title: String, assetIds: List<String>): WalletItem {
        val assets = assetIds.mapIndexed { i, aid ->
            WalletAsset(aid, WalletFormat.ASSET_FIT, i, 0, 48000, "0".repeat(64), 1000)
        }
        val level = WalletLevel(assets.size, 1, 0, 0, assets, null)
        val page = WalletPage("p001", "a4", linkedMapOf("fit" to level), emptyList())
        return WalletItem(id, title, "2026-08-18T00:00:00Z", 0, listOf(page))
    }

    private fun writeAssets(store: WalletStore, ids: List<String>) {
        val sink = store.sink()
        for (id in ids) sink.write(id, ByteArray(64), ByteArray(48))
    }

    @Test
    fun an_empty_store_loads_an_empty_wallet() {
        val w = tempStore().load()
        assertEquals(0, w.items.size)
        assertEquals(0, w.walletVersion)
        assertEquals("x4", w.panelName)
    }

    @Test
    fun wallet_version_increments_on_every_write() {
        val store = tempStore()
        assertEquals(1, store.addItem(item("aaaa000000000000", "One", listOf("11aa000000000001"))).walletVersion)
        assertEquals(2, store.addItem(item("bbbb000000000000", "Two", listOf("22bb000000000002"))).walletVersion)
        assertEquals(3, store.moveItem("bbbb000000000000", -1).walletVersion)
        assertEquals(4, store.deleteItem("aaaa000000000000").walletVersion)
    }

    @Test
    fun sort_order_follows_the_list_position() {
        val store = tempStore()
        store.addItem(item("aaaa000000000000", "One", emptyList()))
        store.addItem(item("bbbb000000000000", "Two", emptyList()))
        store.addItem(item("cccc000000000000", "Three", emptyList()))
        val moved = store.moveItem("cccc000000000000", -2)
        assertEquals(listOf("Three", "One", "Two"), moved.items.map { it.title })
        assertEquals(listOf(0, 1, 2), moved.items.map { it.sortOrder })
    }

    @Test
    fun moving_off_either_end_does_nothing() {
        val store = tempStore()
        store.addItem(item("aaaa000000000000", "One", emptyList()))
        val before = store.load().walletVersion
        assertEquals(before, store.moveItem("aaaa000000000000", -1).walletVersion)
        assertEquals(before, store.moveItem("aaaa000000000000", 1).walletVersion)
        assertEquals(before, store.deleteItem("nosuchitem000000").walletVersion)
    }

    @Test
    fun deleting_an_item_removes_its_assets_and_prunes_the_shard() {
        val store = tempStore()
        val ids = listOf("aa00000000000001", "aa00000000000002", "bb00000000000003")
        writeAssets(store, ids)
        store.addItem(item("aaaa000000000000", "One", ids))
        for (id in ids) {
            assertTrue(store.assetFile(id, "dat").isFile)
            assertTrue(store.assetFile(id, "rle").isFile)
        }
        store.deleteItem("aaaa000000000000")
        for (id in ids) {
            assertFalse("orphaned asset $id", store.assetFile(id, "dat").exists())
            assertFalse(store.assetFile(id, "rle").exists())
        }
        assertFalse(File(store.treeDir, "aa").exists())
        assertFalse(File(store.treeDir, "bb").exists())
        assertTrue(File(store.treeDir, "manifest.json").isFile)
    }

    @Test
    fun another_items_assets_survive_a_delete() {
        val store = tempStore()
        // Assets are written by the pipeline just before the item is added, which
        // is the order this follows: adding an item also collects garbage, so
        // pre-writing both items' files would sweep the second one's away.
        writeAssets(store, listOf("aa00000000000001"))
        store.addItem(item("aaaa000000000000", "One", listOf("aa00000000000001")))
        writeAssets(store, listOf("aa00000000000002"))
        store.addItem(item("bbbb000000000000", "Two", listOf("aa00000000000002")))
        store.deleteItem("aaaa000000000000")
        assertFalse(store.assetFile("aa00000000000001", "dat").exists())
        assertTrue(store.assetFile("aa00000000000002", "dat").isFile)
    }

    @Test
    fun an_abandoned_import_leaves_no_orphans() {
        // The real reason addItem collects garbage: a cancelled or failed import
        // has already written assets nothing references.
        val store = tempStore()
        writeAssets(store, listOf("dd00000000000009"))
        store.addItem(item("aaaa000000000000", "One", emptyList()))
        assertFalse(store.assetFile("dd00000000000009", "dat").exists())
    }

    @Test
    fun re_importing_the_same_id_replaces_in_place() {
        val store = tempStore()
        store.addItem(item("aaaa000000000000", "One", emptyList()))
        store.addItem(item("bbbb000000000000", "Two", emptyList()))
        val after = store.addItem(item("aaaa000000000000", "One again", emptyList()))
        assertEquals(2, after.items.size)
        assertEquals(listOf("One again", "Two"), after.items.map { it.title })
    }

    @Test
    fun the_manifest_round_trips_through_json() {
        val store = tempStore()
        store.addItem(item("aaaa000000000000", "Pasž s diakritikou", listOf("cc00000000000001")))
        val text = File(store.treeDir, "manifest.json").readText()
        val parsed = Wallet.fromManifestJson(text)
        assertEquals("Pasž s diakritikou", parsed.items[0].title)
        assertEquals(text, parsed.toManifestJson())
    }

    @Test
    fun the_sync_ledger_lives_outside_the_manifest() {
        val store = tempStore()
        store.addItem(item("aaaa000000000000", "One", emptyList()))
        assertTrue(store.loadState().confirmed.isEmpty())

        // A confirmation is (asset id, sha256): the id says which asset, the hash
        // says which bytes.
        val q = WalletSyncQueue(WalletSyncPlan.build(store.load(), store.treeDir))
        q.queueAll()
        val a = q.plan.first { it.isManifest }
        q.confirm(a, "wifi", 1234L)
        store.saveSyncState(q)
        assertEquals("wifi", store.loadState().confirmed["manifest"]?.transport)
        assertTrue(store.loadState().isConfirmed("manifest", a.sha256))

        // The manifest must not have grown a sync field: it has to stay exactly what
        // the generator would write.
        val text = File(store.treeDir, "manifest.json").readText()
        assertFalse(text.contains("confirmed"))
        assertFalse(text.contains("wifi"))
    }

    @Test
    fun a_damaged_state_file_does_not_hide_the_wallet() {
        val store = tempStore()
        store.addItem(item("aaaa000000000000", "One", emptyList()))
        File(store.root, "state.json").writeText("{not json")
        assertEquals(1, store.load().items.size)
        assertTrue(store.loadState().confirmed.isEmpty())
    }

    @Test
    fun a_version_1_state_file_loses_its_states_rather_than_inventing_confirmations() {
        val store = tempStore()
        store.addItem(item("aaaa000000000000", "One", emptyList()))
        // What P4/P5 wrote: a per-item state with no hash and no byte count behind it.
        File(store.root, "state.json").writeText(
            "{\"version\": 1, \"syncState\": {\"aaaa000000000000\": \"ON_DEVICE\"}, " +
                "\"sourceNames\": {\"aaaa000000000000\": [\"one.png\"]}}")
        val state = store.loadState()
        // Forgetting costs a transfer; inventing would show "synced" for bytes
        // nobody checked. So the states go and the source names stay.
        assertTrue(state.confirmed.isEmpty())
        assertEquals(listOf("one.png"), state.sourceNames["aaaa000000000000"])
    }

    @Test
    fun atomic_write_leaves_no_part_file() {
        val dir = Files.createTempDirectory("atomic").toFile()
        val target = File(dir, "manifest.json")
        WalletStore.writeAtomic(target, "one".toByteArray())
        WalletStore.writeAtomic(target, "two".toByteArray())
        assertEquals("two", target.readText())
        assertFalse(File(dir, "manifest.json.part").exists())
    }
}
