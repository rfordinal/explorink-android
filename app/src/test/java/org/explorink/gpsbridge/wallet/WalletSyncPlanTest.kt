package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The plan: priority by user value, not FIFO (brief sections 26 and 39), and one
 * [SyncAsset] per file on disk with the hash of the **whole file**.
 */
class WalletSyncPlanTest {

    @Test
    fun the_manifest_comes_first_and_ones_and_ones_come_last() {
        val store = SyncFixtures.store()
        SyncFixtures.addItem(store, "Passport")
        val plan = WalletSyncPlan.build(store.load(), store.treeDir)

        assertTrue(plan.isNotEmpty())
        assertEquals(SyncAsset.MANIFEST_KEY, plan.first().key)
        assertEquals(SyncClass.ONE_TO_ONE, plan.last().cls)

        // The class order IS the order, for the whole plan.
        val ordinals = plan.map { it.cls.ordinal }
        assertEquals(ordinals.sorted(), ordinals)
    }

    @Test
    fun a_verified_code_outranks_detail_and_an_unverified_one_goes_last() {
        val store = SyncFixtures.store()
        SyncFixtures.addItem(store, "Boarding pass", codes = listOf(
            WalletPipeline.CodeRequest(Symbology.QR, "TEST12345")))
        val plan = WalletSyncPlan.build(store.load(), store.treeDir)
        val codeAt = plan.indexOfFirst { it.cls == SyncClass.CODE }
        val fitAt = plan.indexOfFirst { it.cls == SyncClass.FIT }
        val detailAt = plan.indexOfFirst { it.cls == SyncClass.DETAIL }
        assertTrue("a code must exist", codeAt >= 0)
        assertTrue("fit before code", fitAt < codeAt)
        assertTrue("code before detail", codeAt < detailAt)

        // The class is decided by `verified`, and `verified` means the STORED bytes
        // decoded back. Flip it and the same asset drops to the very end.
        val wallet = store.load()
        val flipped = wallet.copy(items = wallet.items.map { item ->
            item.copy(pages = item.pages.map { p ->
                p.copy(codes = p.codes.map { it.copy(verified = false) })
            })
        })
        val plan2 = WalletSyncPlan.build(flipped, store.treeDir)
        assertEquals(SyncClass.UNVERIFIED_CODE, plan2.last().cls)
    }

    @Test
    fun the_page_image_outranks_the_tiles_of_its_own_level() {
        val store = SyncFixtures.store()
        SyncFixtures.addItem(store, "Passport")
        val plan = WalletSyncPlan.build(store.load(), store.treeDir)
        val wallet = store.load()
        val pi = wallet.items[0].pages[0].levels.getValue("one_to_one").pageImage!!
        val oneToOne = plan.filter { it.cls == SyncClass.ONE_TO_ONE }
        assertEquals(pi.assetId, oneToOne.first().key)
    }

    @Test
    fun tiles_go_out_from_the_focal_tile_not_left_to_right() {
        val store = SyncFixtures.store()
        SyncFixtures.addItem(store, "Passport", paper = "a4")
        val wallet = store.load()
        val level = wallet.items[0].pages[0].levels.getValue("one_to_one")
        assertEquals(4, level.cols)
        val plan = WalletSyncPlan.build(wallet, store.treeDir)
        val order = plan.filter { it.cls == SyncClass.ONE_TO_ONE && it.key != level.pageImage?.assetId }
            .map { a -> level.assets.first { it.assetId == a.key } }

        // Brief section 39: "FIT -> central/detail tile -> other tiles". The focal
        // tile of a 4x4 grid is (1,1), and it must be the first tile out -- not (0,0).
        assertEquals(Pair(level.defaultTileX, level.defaultTileY),
            Pair(order.first().col, order.first().row))
        val distances = order.map {
            Math.abs(it.col - level.defaultTileX) + Math.abs(it.row - level.defaultTileY)
        }
        assertEquals(distances.sorted(), distances)
        assertNotEquals(Pair(0, 0), Pair(order.first().col, order.first().row))
    }

    @Test
    fun every_class_becomes_usable_before_any_item_gets_its_bulk() {
        val store = SyncFixtures.store()
        SyncFixtures.addItem(store, "One")
        SyncFixtures.addItem(store, "Two")
        val plan = WalletSyncPlan.build(store.load(), store.treeDir)
        val items = store.load().items.map { it.id }
        assertEquals(2, items.size)

        // Both items' FIT assets precede either item's 1:1 assets. The other order --
        // item first, class second -- would leave document two entirely absent while
        // document one spent two minutes on tiles nobody asked to read.
        val lastFit = plan.indexOfLast { it.cls == SyncClass.FIT }
        val firstBulk = plan.indexOfFirst { it.cls == SyncClass.ONE_TO_ONE }
        assertTrue(lastFit < firstBulk)
        assertEquals(items.toSet(),
            plan.filter { it.cls == SyncClass.FIT }.mapNotNull { it.itemId }.toSet())
    }

    @Test
    fun the_hash_is_of_the_whole_file_not_of_the_manifests_payload_hash() {
        val store = SyncFixtures.store()
        SyncFixtures.addItem(store, "Passport")
        val wallet = store.load()
        val plan = WalletSyncPlan.build(wallet, store.treeDir)
        val fit = wallet.items[0].pages[0].levels.getValue("fit").assets[0]
        val planned = plan.first { it.key == fit.assetId }

        val file = store.assetFile(fit.assetId, "dat").readBytes()
        assertEquals(file.size, planned.bytes)
        assertEquals(WalletFormat.sha256Hex(file), planned.sha256)
        // The manifest's own sha256 covers the PAYLOAD, which is 32 bytes shorter.
        // Both transports confirm the whole file, so the whole file is what the
        // ledger has to compare -- confusing the two would make every asset look
        // changed for ever.
        assertNotEquals(fit.sha256, planned.sha256)
        assertEquals(fit.rawLen + WalletFormat.ASSET_HEADER_LEN, planned.bytes)
    }

    @Test
    fun the_card_path_is_relative_to_trailink_and_shard_sharded() {
        val store = SyncFixtures.store()
        SyncFixtures.addItem(store, "Passport")
        val plan = WalletSyncPlan.build(store.load(), store.treeDir)
        val a = plan.first { !it.isManifest }
        assertEquals("wallet/${a.key.substring(0, 2)}/${a.key}.dat", a.relPath)
        assertEquals("wallet/manifest.json", plan.first { it.isManifest }.relPath)
        // The device's own path rules, so a doomed transfer is never begun.
        assertTrue(plan.all { org.explorink.gpsbridge.TransferFrames.isSafeRelPath("trailink/${it.relPath}") })
    }

    @Test
    fun only_dat_is_planned_never_the_rle_sidecar() {
        val store = SyncFixtures.store()
        SyncFixtures.addItem(store, "Passport")
        val plan = WalletSyncPlan.build(store.load(), store.treeDir)
        assertTrue(plan.none { it.relPath.endsWith(".rle") })
        // The sidecars are on disk -- they are just not sent, because nothing on the
        // device knows the format and an encrypted tree ships none anyway.
        val a = plan.first { !it.isManifest }
        assertTrue(store.assetFile(a.key, "rle").isFile)
    }

    @Test
    fun a_missing_file_is_planned_as_zero_bytes_rather_than_skipped() {
        val store = SyncFixtures.store()
        SyncFixtures.addItem(store, "Passport")
        val wallet = store.load()
        val fit = wallet.items[0].pages[0].levels.getValue("fit").assets[0]
        assertTrue(store.assetFile(fit.assetId, "dat").delete())
        val plan = WalletSyncPlan.build(wallet, store.treeDir)
        val planned = plan.first { it.key == fit.assetId }
        // Still in the plan, so it is visibly pending and the engine reports "file
        // missing" instead of the wallet quietly looking complete.
        assertEquals(0, planned.bytes)
    }

    @Test
    fun a_grey_document_plans_its_grey_assets_in_the_right_classes() {
        val store = SyncFixtures.store()
        SyncFixtures.addItem(store, "Scan", grey = true)
        val wallet = store.load()
        val plan = WalletSyncPlan.build(wallet, store.treeDir)
        val fitLevel = wallet.items[0].pages[0].levels.getValue("fit")
        val planes = fitLevel.greyPlanes!!.assetId
        val greyPage = fitLevel.greyPageImage!!.assetId

        // Grey assets of the FIT level are what makes a grey document readable at
        // all, so they belong to the FIT phase.
        assertEquals(SyncClass.FIT, plan.first { it.key == planes }.cls)
        assertEquals(SyncClass.FIT, plan.first { it.key == greyPage }.cls)
        // And the plane set -- the screen the viewer opens at -- outranks the
        // whole-page 2bpp copy.
        assertTrue(plan.indexOfFirst { it.key == planes } <
            plan.indexOfFirst { it.key == greyPage })
    }

    @Test
    fun a_wallet_with_no_manifest_file_plans_nothing_for_it() {
        val store = SyncFixtures.store()
        SyncFixtures.addItem(store, "Passport")
        // Hold the wallet in hand before removing its file, so the plan really is
        // asked about an item set whose manifest is gone rather than about an empty
        // wallet -- a store that has lost its manifest loads as empty and the
        // assertion would pass for the wrong reason.
        val wallet = store.load()
        assertTrue(File(store.treeDir, "manifest.json").delete())
        val plan = WalletSyncPlan.build(wallet, store.treeDir)
        assertFalse(plan.any { it.isManifest })
        assertTrue(plan.any { it.cls == SyncClass.FIT })
    }
}
