package org.explorink.gpsbridge.wallet

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.explorink.gpsbridge.MainThread
import org.explorink.gpsbridge.R

/**
 * The wallet: a list of items, and the two ways documents get in.
 *
 * Plain framework views, built by hand, no RecyclerView and no AppCompat -- same
 * as the rest of this app (`android/README.md`, "Deliberately minimal").
 *
 * This activity is also the **share target**: it is registered for
 * `ACTION_SEND` and `ACTION_SEND_MULTIPLE` of any image MIME type, so a photo can go
 * straight from the camera roll, a scanner app or a chat into the wallet
 * (brief section 3). Sharing several pictures at once makes ONE multi-page item.
 *
 * Deletion and reordering live here, on the phone, because the device is
 * deliberately read-only (brief section 21): there is no path on the panel that
 * removes or renames anything.
 */
class WalletActivity : Activity() {

    companion object {
        private const val TAG = "WalletActivity"
        private const val REQ_PICK = 11
    }

    private lateinit var tvHead: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tvBusy: TextView
    private lateinit var btnImport: Button
    private lateinit var list: LinearLayout

    private val store: WalletStore by lazy { WalletImporter.store(this) }
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallet)
        tvHead = findViewById(R.id.tvWalletHead)
        tvEmpty = findViewById(R.id.tvWalletEmpty)
        tvBusy = findViewById(R.id.tvWalletBusy)
        btnImport = findViewById(R.id.btnWalletImport)
        list = findViewById(R.id.walletList)
        btnImport.setOnClickListener { pickImages() }
        handleShare(intent)
        render()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleShare(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // --- import ------------------------------------------------------------

    /**
     * `ACTION_OPEN_DOCUMENT` and not `ACTION_PICK`: it needs no storage
     * permission, it grants a persistable read on exactly the files the rider
     * chose, and it multi-selects. `EXTRA_ALLOW_MULTIPLE` is what the brief's
     * "several pages of one document" needs.
     */
    private fun pickImages() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(intent, REQ_PICK)
        } catch (t: Throwable) {
            Toast.makeText(this, "no picker on this device", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
        } else {
            data.data?.let { uris.add(it) }
        }
        if (uris.isEmpty()) return
        askTitleThenImport(uris)
    }

    /**
     * A share intent, single or multiple.
     *
     * Three places a sender can put the pictures, and all three are read:
     *
     *  - `EXTRA_STREAM`, one Uri or a list. What every gallery and chat app uses.
     *  - `ClipData`. A share that grants read access **must** carry the Uris here
     *    too, because `FLAG_GRANT_READ_URI_PERMISSION` applies to the data Uri and
     *    to ClipData and **not** to extras. `ShareCompat` and `Intent.setData` fill
     *    it in automatically; an app that builds the Intent by hand may leave
     *    `EXTRA_STREAM` ungranted, and then reading the extra fails with a
     *    SecurityException while the ClipData copy works.
     *  - the data Uri, for a sender that puts a single picture there.
     *
     * Learned on the emulator: `adb shell am start -a SEND --eu
     * android.intent.extra.STREAM <uri> --grant-read-uri-permission` produces
     * exactly that ungranted case, and reading only the extra throws
     * "has no access to content://...". Handling all three is what a share target
     * has to do anyway.
     *
     * The typed `getParcelableExtra(name, Class)` overloads need API 33 and this
     * app's `minSdk` is 31, so the deprecated ones are the only ones that work on
     * every supported phone. Suppressed deliberately, not overlooked.
     */
    @Suppress("DEPRECATION")
    private fun handleShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) {
            return
        }
        val uris = LinkedHashSet<Uri>()
        // Read the extra untyped and check what it actually is: a sender may put a
        // single Uri under EXTRA_STREAM on a SEND_MULTIPLE intent or the other way
        // round, and `getParcelableExtra<Uri>` on a list throws a
        // ClassCastException rather than returning null.
        when (val raw = intent.extras?.get(Intent.EXTRA_STREAM)) {
            is Uri -> uris.add(raw)
            is List<*> -> for (v in raw) if (v is Uri) uris.add(v)
        }
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
        }
        intent.data?.let { uris.add(it) }

        // Consume it, so a rotation or a return from the picker does not import the
        // same pictures twice.
        intent.action = Intent.ACTION_MAIN
        intent.removeExtra(Intent.EXTRA_STREAM)
        intent.clipData = null
        intent.data = null
        if (uris.isNotEmpty()) askTitleThenImport(uris.toList())
    }

    private fun askTitleThenImport(uris: List<Uri>) {
        if (busy) {
            Toast.makeText(this, "an import is already running", Toast.LENGTH_SHORT).show()
            return
        }
        val suggested = try {
            WalletImporter.titleFrom(ImageImport.displayName(this, uris.first()))
        } catch (t: Throwable) {
            "Document"
        }
        val input = EditText(this).apply {
            setText(suggested)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(if (uris.size == 1) "Import 1 page" else "Import ${uris.size} pages")
            .setMessage("One item, ${uris.size} page(s). Title:")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                runImport(uris, input.text.toString().trim())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runImport(uris: List<Uri>, title: String) {
        busy = true
        btnImport.isEnabled = false
        tvBusy.visibility = View.VISIBLE
        tvBusy.text = "rendering ${uris.size} page(s)..."
        Thread({
            val outcome = WalletImporter.importImages(this, store, uris, title) { p ->
                MainThread.post {
                    tvBusy.text = "page ${p.page}/${p.pages}, asset ${p.asset}/${p.assets}"
                }
            }
            MainThread.post {
                busy = false
                btnImport.isEnabled = true
                tvBusy.visibility = View.GONE
                when (outcome) {
                    is WalletImporter.Outcome.Ok -> {
                        val i = outcome.item
                        Toast.makeText(this,
                            "${i.title}: ${i.assetCount} assets in ${outcome.millis} ms",
                            Toast.LENGTH_LONG).show()
                        render()
                        openItem(i.id)
                    }
                    is WalletImporter.Outcome.Failed -> {
                        Log.w(TAG, "import failed: ${outcome.message}")
                        AlertDialog.Builder(this)
                            .setTitle("Import failed")
                            .setMessage(outcome.message)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }, "wallet-import").start()
    }

    // --- list --------------------------------------------------------------

    private fun render() {
        val wallet = store.load()
        val state = store.loadState()
        tvHead.text = "Wallet: ${wallet.items.size} item(s), " +
            "wallet version ${wallet.walletVersion}, panel ${wallet.panelName}"
        tvEmpty.visibility = if (wallet.items.isEmpty()) View.VISIBLE else View.GONE
        list.removeAllViews()
        for (item in wallet.items) {
            list.addView(row(item, state))
        }
    }

    private fun row(item: WalletItem, state: WalletLocalState): View {
        val dp = resources.displayMetrics.density
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = item.title
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        texts.addView(TextView(this).apply {
            text = "${item.pageCount} page(s), ${item.codeCount} code(s), " +
                "${item.assetCount} assets, ${bytes(item.rawBytes)}"
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        })
        texts.addView(TextView(this).apply {
            text = state.stateOf(item.id).label()
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        })
        texts.setOnClickListener { openItem(item.id) }
        outer.addView(texts)

        outer.addView(smallButton("^") { move(item.id, -1) })
        outer.addView(smallButton("v") { move(item.id, 1) })
        outer.addView(smallButton("X") { confirmDelete(item) })
        return outer
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button {
        val dp = resources.displayMetrics.density
        return Button(this).apply {
            text = label
            minWidth = (44 * dp).toInt()
            minimumWidth = (44 * dp).toInt()
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        }
    }

    private fun move(itemId: String, delta: Int) {
        store.moveItem(itemId, delta)
        render()
    }

    private fun confirmDelete(item: WalletItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${item.title}?")
            .setMessage("${item.assetCount} assets, ${bytes(item.rawBytes)}. " +
                "The device has no delete of its own, so this is the only place it happens.")
            .setPositiveButton("Delete") { _, _ ->
                store.deleteItem(item.id)
                render()
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun openItem(itemId: String) {
        startActivity(Intent(this, WalletItemActivity::class.java)
            .putExtra(WalletItemActivity.EXTRA_ITEM_ID, itemId))
    }

    private fun bytes(n: Long): String = when {
        n >= 1024L * 1024 -> "%.1f MB".format(n / (1024.0 * 1024.0))
        n >= 1024 -> "%d kB".format(n / 1024)
        else -> "$n B"
    }
}
