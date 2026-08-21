package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Renaming a document.
 *
 * The point of these is not that a string changes. It is that **nothing else does**: the
 * item id is stored at import, every asset id is built from that stored id, so a rename
 * must leave every asset, every hash and every confirmation exactly where they were and
 * cost one manifest upload. If that ever stops being true, renaming a passport re-renders
 * and re-sends it over a 7 kB/s link, and the rider pays two minutes for a word.
 */
class WalletRenameTest {

    private fun oneItem(title: String = "20260819_083639"): Pair<WalletStore, WalletItem> {
        val store = SyncFixtures.store()
        return Pair(store, SyncFixtures.addItem(store, title))
    }

    @Test
    fun the_name_changes_and_the_id_does_not() {
        val (store, item) = oneItem()
        val after = store.rename(item.id, "Boarding pass")
        val renamed = after.items.first { it.id == item.id }
        assertEquals("Boarding pass", renamed.title)
        assertEquals("the id may never move", item.id, renamed.id)
    }

    @Test
    fun not_one_asset_id_or_hash_moves() {
        val (store, item) = oneItem()
        // Three different entry types on a level, so each is mapped on its own rather
        // than through a list that would erase to Any.
        fun assets(w: Wallet): List<Pair<String, String>> =
            w.items.first { it.id == item.id }.pages.flatMap { p -> p.levels.values }
                .flatMap { l ->
                    buildList {
                        l.pageImage?.let { add(it.assetId to it.sha256) }
                        l.greyPlanes?.let { add(it.assetId to it.sha256) }
                        l.greyPageImage?.let { add(it.assetId to it.sha256) }
                    }
                }
        val before = assets(store.load())
        val after = assets(store.rename(item.id, "Insurance"))
        assertTrue("the fixture must have assets to compare", before.isNotEmpty())
        assertEquals(before, after)
    }

    @Test
    fun a_rename_leaves_every_confirmation_standing() {
        val (store, item) = oneItem()
        val q = SyncFixtures.queue(store, full = true)
        q.queueAll()
        while (true) {
            val a = q.takeNext() ?: break
            q.confirm(a, "test", 1L)
            q.release()
        }
        store.saveSyncState(q)
        assertEquals(SyncState.FULLY_SYNCED, q.statusOf(item.id).state)

        store.rename(item.id, "Boarding pass")

        // Only the manifest is pending now: its bytes changed, no image did.
        val after = SyncFixtures.queue(store, full = true)
        after.queueAll()
        val pending = after.pending()
        assertEquals("exactly one file to send: $pending", 1, pending.size)
        assertTrue("and it is the manifest", pending.first().isManifest)
    }

    @Test
    fun the_wallet_version_moves_so_the_card_reads_as_stale() {
        val (store, item) = oneItem()
        val before = store.load().walletVersion
        val after = store.rename(item.id, "Boarding pass").walletVersion
        assertEquals(before + 1, after)
    }

    @Test
    fun a_blank_name_or_the_same_name_writes_nothing() {
        val (store, item) = oneItem("Passport")
        val v = store.load().walletVersion
        for (attempt in listOf("", "   ", "Passport")) {
            val w = store.rename(item.id, attempt)
            assertEquals("attempt '$attempt' must not write", v, w.walletVersion)
            assertEquals("Passport", w.items.first { it.id == item.id }.title)
        }
    }

    @Test
    fun an_unknown_id_writes_nothing() {
        val (store, _) = oneItem()
        val v = store.load().walletVersion
        assertEquals(v, store.rename("ffffffffffffffff", "Anything").walletVersion)
    }

    @Test
    fun a_long_name_is_cut_to_what_the_binary_index_will_hold() {
        val (store, item) = oneItem()
        val long = "Boarding pass for the flight to Vienna on the twentieth of August"
        val got = store.rename(item.id, long).items.first { it.id == item.id }.title
        assertTrue("must be cut: '$got'", got.length < long.length)
        assertTrue("within the field: ${got.toByteArray(Charsets.UTF_8).size} B",
            got.toByteArray(Charsets.UTF_8).size <= WalletFormat.TITLE_MAX_BYTES)
        assertTrue("cut at a word, not mid-air: '$got'", long.startsWith(got))
    }

    @Test
    fun the_cut_never_splits_a_character() {
        // Emoji are surrogate pairs in Kotlin and four bytes in UTF-8, so a naive cut by
        // byte count produces an invalid string. A rider who names a document with one
        // gets a shorter name, not a broken one.
        val (store, item) = oneItem()
        val emoji = "📄".repeat(20)   // 20 x page emoji, 80 bytes
        val got = store.rename(item.id, emoji).items.first { it.id == item.id }.title
        assertTrue(got.toByteArray(Charsets.UTF_8).size <= WalletFormat.TITLE_MAX_BYTES)
        assertEquals("no half characters", got, String(got.toByteArray(Charsets.UTF_8),
            Charsets.UTF_8))
        assertNotEquals("and something survived", "", got)
    }
}
