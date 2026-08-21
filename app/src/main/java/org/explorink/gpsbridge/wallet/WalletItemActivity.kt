package org.explorink.gpsbridge.wallet

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.EditText
import android.widget.Toast
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.explorink.gpsbridge.R

/**
 * One item: what was rendered for it, and the only place it can be deleted.
 *
 * It shows the numbers a rider or a maintainer actually needs to trust the
 * import -- pages, the tile grid per zoom level, the page-image geometry, asset
 * ids, bytes -- because nothing on the device can show them: the panel is a
 * read-only viewer (brief section 21) and its screen is 480 px wide.
 *
 * No thumbnail. Rendering a preview would mean keeping a colour copy of a
 * personal document around, and the assets themselves are 1bpp panel-native
 * bytes -- unrotating one for a preview is device work, not phone work. A later
 * phase can add it from the FIT asset if it turns out to be wanted.
 */
class WalletItemActivity : Activity() {

    companion object {
        const val EXTRA_ITEM_ID = "itemId"
    }

    private val store: WalletStore by lazy { WalletImporter.store(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallet_item)
        render()
    }

    override fun onResume() {
        super.onResume()
        loadedWallet = null
        render()
        main.removeCallbacks(tick)
        tick.run()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
    }

    private var loadedWallet: Wallet? = null

    private val main = Handler(Looper.getMainLooper())

    /** 1 Hz while a sync runs, and not a beat otherwise. Same as WalletActivity. */
    private val tick = object : Runnable {
        override fun run() {
            if (WalletSyncSession.queue != null) {
                render()
                main.postDelayed(this, 1000L)
            }
        }
    }

    private fun render() {
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID)
        // Cached between ticks: this screen also redraws at 1 Hz while a sync runs, and
        // store.load() decrypts and parses the whole manifest on the main thread, which
        // is where the BLE transport's callbacks land too.
        val wallet = loadedWallet ?: store.load().also { loadedWallet = it }
        val item = itemId?.let { wallet.item(it) }
        val tvTitle = findViewById<TextView>(R.id.tvItemTitle)
        val tvBody = findViewById<TextView>(R.id.tvItemBody)
        val btnDelete = findViewById<Button>(R.id.btnItemDelete)

        if (item == null) {
            tvTitle.text = "Item is gone"
            tvBody.text = "It was deleted, or this wallet was rebuilt."
            findViewById<LinearLayout>(R.id.itemCodes).removeAllViews()
            btnDelete.isEnabled = false
            return
        }

        tvTitle.text = item.title
        val st = syncStatus(item.id)
        tvBody.text = describe(item, st)
        val bar = findViewById<ProgressBar>(R.id.barItemSync)
        bar.max = 1000
        bar.progress = (st.fraction * 1000).toInt()
        // Hidden for a document nothing has been sent for: an empty bar there reads as
        // "sending, nothing through yet".
        bar.visibility = if (st.fraction > 0f) android.view.View.VISIBLE else android.view.View.GONE
        renderGrey(item)
        renderFullQuality(item, st)
        renderRename(item)
        renderCodes(item)
        btnDelete.isEnabled = true
        btnDelete.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete ${item.title}?")
                .setMessage("${item.assetCount} assets. The device cannot delete anything itself.")
                .setPositiveButton("Delete") { _, _ ->
                    store.deleteItem(item.id)
                    finish()
                }
                .setNegativeButton("Keep", null)
                .show()
        }
    }

    /**
     * One button per machine-readable code, because a code is the one asset a
     * rider will want to LOOK at before the device has it: it either scans or it
     * does not. Tapping opens it full screen ([WalletCodeActivity]).
     *
     * An unverified code says so on its own button. `verified` means the stored
     * asset decoded back (`docs/wallet-format.md` section 10) and nothing else, so
     * a false here is the difference between "a picture of a code" and "a code".
     */
    private fun renderCodes(item: WalletItem) {
        val box = findViewById<LinearLayout>(R.id.itemCodes)
        box.removeAllViews()
        for (page in item.pages) {
            for (code in page.codes) {
                box.addView(Button(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                    isAllCaps = false
                    text = "${page.id} ${code.id}  ${code.symbology}  ${code.orientation}" +
                        "  ${code.moduleSize} px/module  " +
                        (if (code.verified) "verified" else "NOT VERIFIED")
                    setOnClickListener {
                        startActivity(Intent(this@WalletItemActivity, WalletCodeActivity::class.java)
                            .putExtra(WalletCodeActivity.EXTRA_ITEM_ID, item.id)
                            .putExtra(WalletCodeActivity.EXTRA_CODE_ID, code.id))
                    }
                })
            }
        }
    }

    /**
     * The item's state, derived from the ledger the same way the list and the sync
     * screen derive it -- one derivation, so the three screens cannot disagree.
     *
     * Synchronous here, unlike the list: one item screen is one plan build, and the
     * rider has just tapped a row.
     */
    /**
     * Where this document stands. The **running** sync is preferred over a queue rebuilt
     * from disk: only the live one knows about bytes on the wire, and the rebuilt one
     * shows a transfer in progress as a frozen count.
     */
    private fun syncStatus(itemId: String): WalletSyncQueue.ItemStatus {
        WalletSyncSession.queue?.let { return it.statusOf(itemId) }
        val state = store.loadState()
        val q = WalletSyncQueue(WalletSyncPlan.build(store.load(), store.treeDir),
            state.confirmed, state.errors, state.queued, state.fullQuality)
        return q.statusOf(itemId)
    }

    /**
     * The per-document grey toggle (`docs/wallet-plan.md` 7k).
     *
     * Flipping the flag rewrites **the manifest and nothing else**, so the whole
     * cost of the change is one small upload -- brief section 40's own example. The
     * grey assets stay on the card either way; an absent or false flag simply stops
     * the device using them.
     *
     * Turning grey **on** needs those assets to exist, and they are built at import
     * from the source pages, which the phone does not keep. So a 1bpp document says
     * what to do instead rather than offering a button that would silently mark a
     * document grey with no planes to draw -- which on the device is a page of
     * present-but-zero geometry and a silently declined grey path, the exact bug
     * that cost a hardware session.
     */
    /**
     * The full-resolution request.
     *
     * The 1:1 level is 713 kB of a 2.8 MB grey A4 document and it is read only when the
     * rider zooms all the way in, so it is held back until asked for. The button says
     * what it costs, because over Bluetooth that is two minutes and the rider is the one
     * who waits.
     */
    /**
     * Rename, because the name a document was imported under is a filename, not a name.
     *
     * `20260819_083639` says nothing at a petrol stop; "Boarding pass" does. It costs one
     * manifest upload and touches no image data ([WalletStore.rename]), so it is cheap
     * enough to do on a whim -- but it does make the card stale until the next sync, and
     * the row says so afterwards.
     */
    private fun renderRename(item: WalletItem) {
        val btn = findViewById<Button>(R.id.btnItemRename)
        btn.text = "rename this document"
        btn.setOnClickListener {
            val input = EditText(this).apply {
                setText(item.title)
                setSelection(text.length)
            }
            AlertDialog.Builder(this)
                .setTitle("Rename")
                .setMessage("Shown on the device's list. Up to " +
                    "${WalletFormat.TITLE_MAX_BYTES} bytes; longer names are cut.")
                .setView(input)
                .setPositiveButton("Rename") { _, _ ->
                    val wanted = input.text.toString()
                    val before = item.title
                    val after = store.rename(item.id, wanted).items
                        .firstOrNull { it.id == item.id }?.title
                    loadedWallet = null
                    render()
                    if (after == before) {
                        Toast.makeText(this, "not renamed: blank, or the same name",
                            Toast.LENGTH_SHORT).show()
                    } else if (after != wanted.trim()) {
                        // Said out loud rather than silently: the rider typed something
                        // longer than the field holds.
                        Toast.makeText(this, "cut to fit: $after", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun renderFullQuality(item: WalletItem, sync: WalletSyncQueue.ItemStatus) {
        val btn = findViewById<Button>(R.id.btnItemFull)
        val state = store.loadState()
        val asked = item.id in state.fullQuality
        val pending = WalletSyncSession.queue?.deferredBytes(item.id)
            ?: WalletSyncQueue(WalletSyncPlan.build(store.load(), store.treeDir),
                state.confirmed, state.errors, state.queued, state.fullQuality)
                .deferredBytes(item.id)
        btn.text = when {
            asked && pending > 0L -> "full resolution: asked for, ${bytes(pending)} still to send"
            asked -> "full resolution: on the device"
            pending == 0L -> "full resolution: already on the device"
            else -> "send full resolution too (+${bytes(pending)})"
        }
        btn.isEnabled = !asked && pending > 0L
        btn.setOnClickListener {
            store.setFullQuality(item.id, true)
            render()
        }
    }

    /** kB and MB, never a raw byte count: this is a line a person reads. */
    private fun bytes(n: Long): String = when {
        n >= 1024L * 1024 -> "%.1f MB".format(n / (1024.0 * 1024.0))
        n >= 1024 -> "%d kB".format(n / 1024)
        else -> "$n B"
    }

    private fun renderGrey(item: WalletItem) {
        val btn = findViewById<Button>(R.id.btnItemGrey)
        val has = store.hasGreyAssets(item)
        btn.isEnabled = has
        btn.text = when {
            !has -> "grey: not built -- re-import this document with grey ticked"
            item.grey -> "grey is ON -- tap to draw this document 1bpp"
            else -> "grey is OFF -- tap to draw this document grey"
        }
        btn.setOnClickListener {
            store.setGrey(item.id, !item.grey)
            render()
        }
    }

    private fun describe(item: WalletItem, sync: WalletSyncQueue.ItemStatus): String {
        val sb = StringBuilder()
        sb.append("id          ").append(item.id).append('\n')
        sb.append("created     ").append(item.createdAt).append('\n')
        sb.append("order       ").append(item.sortOrder).append('\n')
        sb.append("sync        ").append(sync.state.label())
            .append("  ").append(sync.confirmedAssets).append('/').append(sync.assets)
            .append(" assets confirmed by the device")
        if (sync.failedAssets > 0) sb.append(", ").append(sync.failedAssets).append(" failed")
        sb.append('\n')
        sb.append("grey        ").append(if (item.grey) "yes" else "no").append('\n')
        sb.append("pages       ").append(item.pageCount).append('\n')
        sb.append("assets      ").append(item.assetCount).append('\n')
        sb.append("raw bytes   ").append(item.rawBytes).append('\n')
        for (page in item.pages) {
            sb.append('\n').append("page ").append(page.id)
                .append("  paper ").append(page.paper).append('\n')
            for (name in WalletFormat.LEVELS) {
                val level = page.levels[name] ?: continue
                sb.append("  ").append(name.padEnd(11))
                    .append(level.cols).append('x').append(level.rows)
                    .append(" tiles, focal (").append(level.defaultTileX)
                    .append(',').append(level.defaultTileY).append(')')
                    .append(", ").append(level.assets.size).append(" assets\n")
                val pi = level.pageImage
                if (pi != null) {
                    sb.append("             page image ")
                        .append(pi.nativeWidth).append('x').append(pi.nativeHeight)
                        .append(", ").append(pi.rowBytes).append(" B/row, step ")
                        .append(pi.windowStepX).append('/').append(pi.windowStepY)
                        .append(", focal ").append(pi.focalX).append('/').append(pi.focalY)
                        .append('\n')
                    sb.append("             ").append(pi.assetId)
                        .append("  ").append(pi.rawLen).append(" B raw, ")
                        .append(pi.rleLen).append(" B rle\n")
                }
                // A grey document's two extra assets per level. Shown because "grey
                // yes" alone does not say whether the planes are actually there, and
                // a missing plane set is a silently declined grey path on the device.
                level.greyPageImage?.let {
                    sb.append("             grey page ").append(it.assetId)
                        .append("  2bpp, ").append(it.rawLen).append(" B raw, ")
                        .append(it.rleLen).append(" B rle\n")
                }
                level.greyPlanes?.let {
                    sb.append("             grey planes ").append(it.assetId)
                        .append("  ").append(Json.asInt(it.fields["planeBandCount"]))
                        .append(" bands of ").append(Json.asInt(it.fields["planeBandRows"]))
                        .append(" rows, ").append(it.rawLen).append(" B raw, ")
                        .append(it.rleLen).append(" B rle, baked at ")
                        .append(Json.asInt(it.fields["originX"])).append('/')
                        .append(Json.asInt(it.fields["originY"])).append('\n')
                }
            }
            if (page.codes.isEmpty()) {
                sb.append("  codes       none found on this page\n")
            } else {
                for (c in page.codes) {
                    sb.append("  code ").append(c.id).append(' ').append(c.symbology)
                        .append(' ').append(c.orientation)
                        .append(", ").append(c.moduleSize).append(" px/module, qz ")
                        .append(c.quietZone).append(", ").append(c.codeWidthPx).append('x')
                        .append(c.codeHeightPx).append(" px, ")
                        .append(c.payload.length).append(" chars")
                        .append(if (c.verified) ", verified" else ", NOT VERIFIED")
                        .append('\n')
                    sb.append("             ").append(c.assetId).append('\n')
                }
            }
        }
        sb.append("\ntree        ").append(store.treeDir.absolutePath).append('\n')
        return sb.toString()
    }
}
