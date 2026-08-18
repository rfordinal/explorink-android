package org.explorink.gpsbridge.wallet

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
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
        render()
    }

    private fun render() {
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID)
        val wallet = store.load()
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
        tvBody.text = describe(item, store.loadState().stateOf(item.id))
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

    private fun describe(item: WalletItem, sync: SyncState): String {
        val sb = StringBuilder()
        sb.append("id          ").append(item.id).append('\n')
        sb.append("created     ").append(item.createdAt).append('\n')
        sb.append("order       ").append(item.sortOrder).append('\n')
        sb.append("sync        ").append(sync.label()).append('\n')
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
