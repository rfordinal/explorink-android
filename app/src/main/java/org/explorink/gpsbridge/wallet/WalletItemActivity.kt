package org.explorink.gpsbridge.wallet

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
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
            btnDelete.isEnabled = false
            return
        }

        tvTitle.text = item.title
        tvBody.text = describe(item, store.loadState().stateOf(item.id))
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
                sb.append("  codes       none (phase P5)\n")
            } else {
                for (c in page.codes) {
                    sb.append("  code ").append(c.id).append(' ').append(c.symbology)
                        .append(' ').append(c.orientation)
                        .append(if (c.verified) " verified" else " NOT VERIFIED")
                        .append('\n')
                }
            }
        }
        sb.append("\ntree        ").append(store.treeDir.absolutePath).append('\n')
        return sb.toString()
    }
}
