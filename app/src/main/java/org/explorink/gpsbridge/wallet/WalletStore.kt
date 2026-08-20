package org.explorink.gpsbridge.wallet

import java.io.File

/**
 * The phone-side wallet on disk.
 *
 * Persistence is a **JSON manifest plus a files directory, written atomically**
 * (temp file, then rename). No Room, no SQLite. Three reasons, in order:
 *
 *  - the manifest already IS the sync unit (`walletVersion` is monotone and the
 *    device compares it), so a database would be a second source of truth that
 *    has to be projected back into the very JSON the device reads;
 *  - the assets are big opaque blobs (48 KB a screen, ~1 MB an A4 page) and
 *    belong in files whatever the metadata store is;
 *  - the app has one dependency and no database (`android/README.md`), and a
 *    wallet of a few dozen items is a few hundred kB of JSON -- Room would buy
 *    migrations and queries nothing here needs.
 *
 * Layout:
 *
 *     <root>/state.json      app-private: sync state, source names
 *     <root>/wallet.key      the wrapped wallet key K, if this wallet is encrypted
 *     <root>/tree/           a wallet tree, exactly docs/wallet-format.md
 *       manifest.json        cleartext tree
 *       manifest.enc         encrypted tree (EWM1 v2), plus one manifest.bak
 *       <2 hex>/<16 hex>.dat
 *       <2 hex>/<16 hex>.rle cleartext trees only -- ciphertext does not compress
 *
 * `tree/` holds nothing of ours, so it can be pushed to the device (or pulled off
 * with `adb pull`) and diffed against a `tools/walletgen.py` run byte for byte.
 * That separation is what makes the parity claim checkable on a real device.
 *
 * Plain `java.io`, no Android types, so the whole store runs in a laptop unit test.
 */
class WalletStore(
    val root: File,
    val panelName: String = Panels.DEFAULT_NAME,
    /**
     * Where the wallet key K lives. [WalletKeyStore.None] means this phone writes a
     * **cleartext** tree, which the device then hides behind any `manifest.enc`
     * already on the card (`docs/wallet-plan.md` 7l).
     */
    val keys: WalletKeyStore = WalletKeyStore.None,
) {

    val treeDir: File get() = File(root, "tree")
    private val clearManifest: File get() = File(treeDir, WalletFormat.MANIFEST_CLEAR_NAME)
    private val encManifest: File get() = File(treeDir, WalletFormat.MANIFEST_ENC_NAME)
    private val bakManifest: File get() = File(treeDir, WalletFormat.MANIFEST_BAK_NAME)
    private val stateFile: File get() = File(root, "state.json")

    // --- encryption --------------------------------------------------------

    /** True when this store writes an encrypted tree: a key exists. */
    val encrypted: Boolean get() = keys.hasKey()

    /**
     * Encryption is **on by default** (brief section 16: no cleartext documents at
     * rest). Call this before writing an item; it creates the key on a wallet that has
     * no items yet and does nothing otherwise.
     *
     * It deliberately does **not** convert an existing cleartext wallet. Flipping the
     * flag would change every asset id (the recipe is crypto-scoped) while the assets
     * on disk stayed cleartext under their old ids, so the tree would be half of each.
     * Converting means re-importing, which needs source pages this app does not keep.
     * Returns true when the wallet will be written encrypted.
     */
    fun applyDefaultEncryption(): Boolean {
        if (keys === WalletKeyStore.None) return false
        return enableEncryption()
    }

    /** Which manifest the local tree actually carries right now. */
    fun treeKind(): ManifestKind = ManifestKind.of(clearManifest.isFile, encManifest.isFile)

    /** The manifest file this store writes. */
    val manifestFile: File get() = if (encrypted) encManifest else clearManifest

    /**
     * The cipher every asset goes through. One place, so nothing above can write half
     * an encrypted tree.
     */
    fun cipher(): AssetCipher =
        if (encrypted) Aes256CtrCipher(requireKey()) else AssetCipher.None

    private fun requireKey(): ByteArray = keys.loadKey()
        ?: throw IllegalStateException("this wallet is encrypted but its key is gone")

    /**
     * Turn encryption on for a wallet that has no items yet.
     *
     * **Refused once the tree holds an item**, and that is not laziness: the asset id
     * recipe is crypto-scoped (`docs/wallet-plan.md` 7f), so every asset would need a
     * new id and new ciphertext -- a full re-render from the source pages, which this
     * app does not keep (a share-target `Uri` is not persistable). The caller shows
     * that as "re-import these documents".
     */
    fun enableEncryption(): Boolean {
        if (encrypted) return true
        if (load().items.isNotEmpty()) return false
        keys.createKey()
        return true
    }

    // --- reading -----------------------------------------------------------

    /**
     * The wallet on disk, or an empty one. Never throws on a missing tree.
     *
     * It reads **the manifest this store writes**, not "whichever exists": an encrypted
     * store reads `manifest.enc` and a cleartext one reads `manifest.json`. Falling back
     * to the other kind would read a manifest whose assets this store cannot decrypt
     * (or can, but under different ids -- the recipe is crypto-scoped), and half a tree
     * is worse than none.
     *
     * The kinds cannot cross by accident: a key is only ever created for a wallet with
     * no items ([applyDefaultEncryption]), so a cleartext wallet never grows a key
     * underneath itself.
     *
     * On the **card** the rule is the other way round -- `manifest.enc` wins whenever it
     * exists, because that is what the firmware does. That asymmetry is the whole reason
     * [WalletManifestConflict] exists.
     */
    fun load(): Wallet {
        if (encrypted) {
            if (!encManifest.isFile) return Wallet.empty(panelName, encrypted = true)
            val plain = WalletCrypto.decryptManifest(requireKey(), encManifest.readBytes())
            return Wallet.fromManifestJson(plain.toString(Charsets.UTF_8))
        }
        if (!clearManifest.isFile) return Wallet.empty(panelName)
        return Wallet.fromManifestJson(clearManifest.readText(Charsets.UTF_8))
    }

    fun loadState(): WalletLocalState {
        if (!stateFile.isFile) return WalletLocalState()
        return try {
            WalletLocalState.fromJson(stateFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            // A damaged state file must never hide the wallet: the manifest is the
            // real data and every item falls back to "phone only".
            WalletLocalState()
        }
    }

    fun assetFile(assetId: String, ext: String): File =
        File(File(treeDir, WalletFormat.shardOf(assetId)), "$assetId.$ext")

    /** A sink that writes into this store's tree. */
    fun sink(): AssetSink = FileAssetSink(treeDir)

    /** A pipeline configured for this store: the right panel and the right cipher. */
    fun pipeline(
        grey: Boolean = false,
        tiles: Boolean = false,
        tone: WalletPipeline.Tone = WalletPipeline.Tone.DOCUMENT,
    ): WalletPipeline =
        WalletPipeline(Panels.byName(panelName), pageImage = true, tiles = tiles,
            grey = grey, tone = tone, cipher = cipher())

    // --- writing -----------------------------------------------------------

    /**
     * Add (or replace) an item and bump `walletVersion`.
     *
     * Replace, not refuse, when the id is already there: an item id is
     * `sha256(title | source names)`, so re-importing the same pictures under the
     * same title is the same document. The laptop generator refuses instead
     * (`--append` is a one-shot command); an app that refused would leave the rider
     * with no way to redo a failed import. The replaced item keeps its position.
     */
    fun addItem(item: WalletItem, sourceNames: List<String> = emptyList()): Wallet {
        val current = load()
        val items = ArrayList(current.items)
        val at = items.indexOfFirst { it.id == item.id }
        if (at >= 0) items[at] = item.copy(sortOrder = at) else items.add(item)
        val next = resequence(current, items)
        val state = loadState()
        val sources = LinkedHashMap(state.sourceNames)
        if (sourceNames.isNotEmpty()) sources[item.id] = sourceNames
        // Nothing is reset here, on purpose. A re-render keeps the same asset ids
        // (the recipe has no content in it) and produces different BYTES, and the
        // ledger keys a confirmation to its sha256 -- so every changed asset is
        // pending again by arithmetic, and every unchanged one stays confirmed.
        // That is delta sync (brief section 40) falling out instead of being
        // computed, and it is why there is no per-item state to invalidate.
        writeState(state.copy(sourceNames = sources))
        write(next)
        collectGarbage(next)
        return next
    }

    /** Delete an item, its assets and its state. Bumps `walletVersion`. */
    fun deleteItem(itemId: String): Wallet {
        val current = load()
        val items = current.items.filter { it.id != itemId }
        if (items.size == current.items.size) return current
        val next = resequence(current, items)
        write(next)
        val state = loadState()
        // The item's confirmations go with it: those asset ids are gone from the
        // manifest, and a ledger entry for a file nobody references is just a leak
        // that grows every time a document is replaced.
        val gone = current.item(itemId)?.let { item ->
            Wallet(current.formatVersion, current.walletVersion, current.panelName,
                listOf(item)).assetIds().toSet()
        } ?: emptySet()
        writeState(WalletLocalState(
            confirmed = state.confirmed.filterKeys { it !in gone },
            queued = state.queued - itemId,
            errors = state.errors.filterKeys { it !in gone },
            sourceNames = state.sourceNames.filterKeys { it != itemId }))
        collectGarbage(next)
        return next
    }

    /**
     * Move an item by [delta] places. Deletion and reordering live on the phone
     * because the device is deliberately read-only (brief section 21).
     */
    fun moveItem(itemId: String, delta: Int): Wallet {
        val current = load()
        val items = ArrayList(current.items)
        val at = items.indexOfFirst { it.id == itemId }
        if (at < 0) return current
        val to = at + delta
        if (to < 0 || to >= items.size) return current
        val moved = items.removeAt(at)
        items.add(to, moved)
        val next = resequence(current, items)
        write(next)
        return next
    }

    /** Persist the queue's ledger. Called after every device confirmation. */
    /** Ask for (or stop asking for) this item's 1:1 level. */
    fun setFullQuality(itemId: String, full: Boolean) {
        val cur = loadState()
        writeState(cur.copy(
            queued = if (full) cur.queued + itemId else cur.queued,
            fullQuality = if (full) cur.fullQuality + itemId else cur.fullQuality - itemId))
    }

    fun saveSyncState(queue: WalletSyncQueue) {
        val cur = loadState()
        writeState(cur.copy(
            confirmed = LinkedHashMap(queue.confirmed),
            queued = LinkedHashSet(queue.queuedItems),
            fullQuality = LinkedHashSet(queue.fullQualityItems),
            errors = LinkedHashMap(queue.errors)))
    }

    fun setQueued(itemId: String, queued: Boolean) {
        val cur = loadState()
        writeState(cur.copy(
            queued = if (queued) cur.queued + itemId else cur.queued - itemId))
    }

    fun queueAll() {
        val cur = loadState()
        writeState(cur.copy(queued = load().items.map { it.id }.toSet()))
    }

    /**
     * Flip a document's `grey` flag. Bumps `walletVersion`.
     *
     * This is the cheap half of the per-document grey toggle: turning grey off
     * leaves the grey assets on the card and only stops the device using them
     * (an absent or false flag means no grey), so the whole change is **the
     * manifest** -- one small file, brief section 40's own example of a metadata
     * change that must not re-upload image data.
     *
     * Turning grey **on** for a document that has no grey assets is refused: the
     * assets are built from the source pages at import time and the phone does not
     * keep the originals (a share-target Uri is not persistable). The caller shows
     * that as "re-import this document as grey".
     */
    fun setGrey(itemId: String, grey: Boolean): Wallet {
        val current = load()
        val at = current.items.indexOfFirst { it.id == itemId }
        if (at < 0) return current
        val item = current.items[at]
        if (item.grey == grey) return current
        if (grey && !hasGreyAssets(item)) return current
        val items = ArrayList(current.items)
        items[at] = item.copy(grey = grey)
        val next = resequence(current, items)
        write(next)
        return next
    }

    /** Does this item carry the two grey assets on every level of every page? */
    fun hasGreyAssets(item: WalletItem): Boolean = item.pages.isNotEmpty() &&
        item.pages.all { p ->
            p.levels.isNotEmpty() &&
                p.levels.values.all { it.greyPlanes != null && it.greyPageImage != null }
        }

    /**
     * `sortOrder` is the item's index at write time, and `walletVersion` increments
     * on **every** write to the tree, monotone (brief section 54).
     */
    private fun resequence(current: Wallet, items: List<WalletItem>): Wallet = Wallet(
        formatVersion = WalletFormat.MANIFEST_FORMAT_VERSION,
        walletVersion = current.walletVersion + 1,
        panelName = current.panelName.ifEmpty { panelName },
        items = items.mapIndexed { i, it -> it.copy(sortOrder = i) },
        crypto = if (encrypted) WalletCrypto.descriptor() else null,
    )

    /**
     * Write the manifest. Atomic either way.
     *
     * An encrypted tree keeps **one backup** of the previous good `manifest.enc`
     * (`manifest.bak`, brief section 32's cheap half) and carries **no** cleartext
     * copy -- a stale `manifest.json` left beside it is exactly the file that makes a
     * later cleartext sync look like it worked. So it is deleted here, on our own
     * phone. Nothing is ever deleted from the **card**: that is the rider's call
     * ([WalletManifestConflict]).
     */
    private fun write(wallet: Wallet) {
        treeDir.mkdirs()
        val plain = wallet.toManifestJson().toByteArray(Charsets.UTF_8)
        if (!encrypted) {
            writeAtomic(clearManifest, plain)
            return
        }
        if (encManifest.isFile) encManifest.copyTo(bakManifest, overwrite = true)
        writeAtomic(encManifest,
            WalletCrypto.encryptManifest(requireKey(), plain, wallet.walletVersion))
        if (clearManifest.isFile) clearManifest.delete()
    }

    private fun writeState(state: WalletLocalState) {
        root.mkdirs()
        writeAtomic(stateFile, state.toJson().toByteArray(Charsets.UTF_8))
    }

    /**
     * Delete asset files no item references any more, then prune empty shards. Run
     * after every removal: an orphaned 48 KB screen is invisible and permanent
     * otherwise.
     */
    private fun collectGarbage(wallet: Wallet) {
        val live = wallet.assetIds().toHashSet()
        val shards = treeDir.listFiles() ?: return
        for (shard in shards) {
            if (!shard.isDirectory || shard.name.length != 2) continue
            val files = shard.listFiles() ?: continue
            for (f in files) {
                val id = f.name.substringBeforeLast('.')
                if (id !in live) f.delete()
            }
            if ((shard.listFiles()?.size ?: 0) == 0) shard.delete()
        }
    }

    companion object {
        /**
         * Atomic write: a `.part` file, flushed to the platter, then renamed over
         * the target. A kill in the middle leaves either the old file or the new
         * one, never half of the manifest.
         */
        fun writeAtomic(target: File, bytes: ByteArray) {
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, target.name + ".part")
            java.io.FileOutputStream(part).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!part.renameTo(target)) {
                // Windows-style rename-over-existing failure; on Android and Linux
                // rename(2) replaces, so this is the belt to the braces.
                target.delete()
                if (!part.renameTo(target)) throw java.io.IOException("cannot replace $target")
            }
        }
    }
}

/**
 * Writes assets into a wallet tree: `<shard>/<assetId>.dat`, and `.rle` only when the
 * cipher produced one. A stale sidecar from a previous cleartext build of the same id
 * is removed rather than left to rot beside ciphertext.
 */
class FileAssetSink(private val treeDir: File) : AssetSink {
    override fun write(assetId: String, dat: ByteArray, rle: ByteArray?) {
        val shard = File(treeDir, WalletFormat.shardOf(assetId))
        shard.mkdirs()
        WalletStore.writeAtomic(File(shard, "$assetId.dat"), dat)
        val side = File(shard, "$assetId.rle")
        if (rle != null) WalletStore.writeAtomic(side, rle) else side.delete()
    }
}

/** Keeps every asset in memory. For tests and for a dry run. */
class MemoryAssetSink : AssetSink {
    val dat = LinkedHashMap<String, ByteArray>()
    val rle = LinkedHashMap<String, ByteArray>()
    override fun write(assetId: String, dat: ByteArray, rle: ByteArray?) {
        this.dat[assetId] = dat
        if (rle != null) this.rle[assetId] = rle else this.rle.remove(assetId)
    }
}
