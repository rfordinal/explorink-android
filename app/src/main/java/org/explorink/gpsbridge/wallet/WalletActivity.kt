package org.explorink.gpsbridge.wallet

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
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

        /** Same 1 Hz as the pins screen (PinsActivity.UI_TICK_MS), same reason. */
        private const val UI_TICK_MS = 1000L
    }

    private lateinit var tvHead: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tvBusy: TextView
    private lateinit var btnImport: Button
    private lateinit var list: LinearLayout
    private lateinit var barHead: ProgressBar

    /**
     * Redraw while a sync is running. 1 Hz, the same cadence the pins screen uses, and
     * for the same reason: the numbers move continuously and a screen that only
     * redraws on resume cannot tell a working transfer from a stalled one.
     *
     * It runs **only** while [WalletSyncSession] reports a sync, so an idle wallet
     * costs nothing.
     */
    private val main = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            if (WalletSyncSession.queue != null) {
                render()
                main.postDelayed(this, UI_TICK_MS)
            }
        }
    }

    private val store: WalletStore by lazy { WalletImporter.store(this) }
    private var busy = false

    /**
     * The manifest as last read. Cached because [render] runs at 1 Hz while a sync is
     * running, and reading it means decrypting and parsing the whole thing -- on the
     * main thread, which is also where the BLE transport's callbacks land.
     *
     * Cleared whenever the tree can have changed: an import, a delete, a reorder, a
     * grey flip, and on every resume.
     */
    private var loadedWallet: Wallet? = null

    /**
     * The sync queue, for the per-item states. Null until the worker has hashed the
     * tree -- and the states read "..." until then rather than guessing, because a
     * guessed state is exactly what brief section 27 forbids.
     */
    private var queue: WalletSyncQueue? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallet)
        tvHead = findViewById(R.id.tvWalletHead)
        tvEmpty = findViewById(R.id.tvWalletEmpty)
        tvBusy = findViewById(R.id.tvWalletBusy)
        btnImport = findViewById(R.id.btnWalletImport)
        list = findViewById(R.id.walletList)
        barHead = findViewById(R.id.barWalletHead)
        btnImport.setOnClickListener { pickImages() }
        findViewById<Button>(R.id.btnWalletSync).setOnClickListener {
            startActivity(Intent(this, WalletSyncActivity::class.java))
        }
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
        loadedWallet = null
        render()
        refreshStates()
        main.removeCallbacks(tick)
        tick.run()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
    }

    /**
     * Rebuild the plan off the UI thread. Reading and hashing the whole tree is the
     * honest cost of "the hashes decide" -- ~15 ms for a one-page wallet on a laptop
     * JVM, and it is why this is not done inside `render()`.
     */
    private fun refreshStates() {
        loadedWallet = null
        Thread({
            val wallet = store.load()
            val state = store.loadState()
            val q = WalletSyncQueue(WalletSyncPlan.build(wallet, store.treeDir),
                state.confirmed, state.errors, state.queued, state.fullQuality)
            MainThread.post {
                queue = q
                render()
            }
        }, "wallet-states").start()
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
        // Camera EXIF on the first page pre-ticks "photograph", because a photo wants
        // error diffusion and a scan does not. Read off the UI thread: it opens the Uri.
        Thread({
            val camera = try {
                ImageImport.load(this, uris.first()).fromCamera
            } catch (t: Throwable) {
                false
            }
            MainThread.post { showImportDialog(uris, camera) }
        }, "wallet-exif").start()
    }

    private fun showImportDialog(uris: List<Uri>, cameraSource: Boolean) {
        val suggested = try {
            WalletImporter.titleFrom(ImageImport.displayName(this, uris.first()))
        } catch (t: Throwable) {
            "Document"
        }
        val input = EditText(this).apply {
            setText(suggested)
            setSelection(text.length)
        }
        // Grey is chosen HERE and not later, because the grey assets are built from
        // the source pages and the phone does not keep them: a share-target Uri is
        // not persistable, so there is nothing to re-render from once the dialog is
        // gone. The item screen can still turn the flag off and back on afterwards,
        // which costs one manifest upload and no image data.
        val grey = CheckBox(this).apply {
            text = "grey (scan or photo): richer tone, +635 ms an entry, cannot pan"
            textSize = 13f
            // A camera file is a photograph, and a photograph wants grey: pre-tick both,
            // as one preset the rider can undo rather than two boxes to discover.
            isChecked = cameraSource
        }
        // Enabled always, and ticking it ticks grey, because the tone only reaches the
        // GREY levels -- the 1bpp path is dithered either way. It used to be disabled
        // until grey was ticked while still being pre-ticked and still being read, so a
        // rider could import with "photograph" showing and get a plain 1bpp document.
        // That happened on the maintainer's own photos, 2026-08-19.
        val photo = CheckBox(this).apply {
            text = "photograph: smooth tones by dithering (implies grey; a scan does not want it)"
            textSize = 13f
            isChecked = cameraSource
        }
        photo.setOnCheckedChangeListener { _, checked ->
            if (checked) grey.isChecked = true
        }
        grey.setOnCheckedChangeListener { _, checked ->
            if (!checked) photo.isChecked = false
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val dp = resources.displayMetrics.density
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            addView(input)
            addView(grey)
            addView(photo)
        }
        AlertDialog.Builder(this)
            .setTitle(if (uris.size == 1) "Import 1 page" else "Import ${uris.size} pages")
            .setMessage("One item, ${uris.size} page(s). Title:")
            .setView(box)
            .setPositiveButton("Import") { _, _ ->
                runImport(uris, input.text.toString().trim(),
                    grey = grey.isChecked || photo.isChecked,
                    tone = if (photo.isChecked) WalletPipeline.Tone.PHOTO
                    else WalletPipeline.Tone.DOCUMENT)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runImport(
        uris: List<Uri>,
        title: String,
        grey: Boolean,
        tone: WalletPipeline.Tone = WalletPipeline.Tone.DOCUMENT,
    ) {
        busy = true
        btnImport.isEnabled = false
        tvBusy.visibility = View.VISIBLE
        tvBusy.text = "rendering ${uris.size} page(s)..."
        Thread({
            val outcome = WalletImporter.importImages(this, store, uris, title, grey, tone) { p ->
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

    /**
     * One row's mutable parts, kept so a tick can change numbers without rebuilding the
     * view.
     *
     * Rebuilding the list every second is what made scrolling stutter and what starved
     * the BLE transport: `removeAllViews()` plus seven fresh views per document, on the
     * main thread, where the transport's own callbacks also land (measured 2026-08-19 --
     * 8.3 kB/s fell to 1.2 kB/s with a list open over a running sync).
     */
    private class Row(
        val view: View,
        val state: TextView,
        val bar: ProgressBar,
        val pct: TextView,
        val queueButton: Button,
    )

    private val rows = LinkedHashMap<String, Row>()

    private fun render() {
        val wallet = loadedWallet ?: store.load().also { loadedWallet = it }
        // The running sync wins over this screen's own snapshot. Both are the same
        // class over the same plan; only the live one knows about bytes on the wire,
        // and reading the stale one during a transfer is what made this screen look
        // frozen.
        val q = WalletSyncSession.queue ?: queue
        val live = WalletSyncSession.statusLine()
        tvHead.text = "Wallet: ${wallet.items.size} item(s), " +
            "wallet version ${wallet.walletVersion}, panel ${wallet.panelName}" +
            (q?.let {
                val t = it.totals()
                "\n${bytes(t.pendingBytes)} pending, ${t.confirmedAssets} of " +
                    "${t.totalAssets} assets confirmed by the device"
            } ?: "") +
            // Said out loud, because "no progress" and "nothing running" look identical
            // and only one of them is a problem.
            "\n" + (live ?: "no sync running -- press Sync to the device")
        barHead.max = 1000
        barHead.progress = ((q?.totals()?.fraction ?: 0f) * 1000).toInt()
        tvEmpty.visibility = if (wallet.items.isEmpty()) View.VISIBLE else View.GONE

        // Rebuild only when the set of documents or their order changed. A tick is the
        // common case and it touches text and two bars per row.
        val ids = wallet.items.map { it.id }
        if (ids != rows.keys.toList()) {
            rows.clear()
            list.removeAllViews()
            for (item in wallet.items) {
                val r = row(item, q)
                rows[item.id] = r
                list.addView(r.view)
            }
        } else {
            for (item in wallet.items) rows[item.id]?.let { update(it, item, q) }
        }
    }

    /** The parts of a row that change while a sync runs. */
    private fun update(r: Row, item: WalletItem, q: WalletSyncQueue?) {
        val st = q?.statusOf(item.id)
        r.state.text = if (st == null) "..." else buildString {
            append(st.state.label())
            append(" -- ${st.confirmedAssets}/${st.assets} assets confirmed")
            // Brief section 27's own example: "Ready on device / High-resolution
            // details syncing" is two facts, not one word, so both are shown.
            if (st.state == SyncState.ERROR && st.usable) {
                append("\nusable on device, ${st.failedAssets} asset(s) failed")
            }
            // Every page of this document is on the card and it still is not "synced",
            // which reads as a contradiction next to a full bar unless the reason is
            // said out loud: importing anything rewrites the wallet's index, and the
            // device reads the index.
            if (!st.manifestConfirmed && st.assets > 0 && st.confirmedAssets == st.assets) {
                append("\nall pages on the card, the wallet index still has to go")
            }
        }
        // Bytes, not assets, so a 1.17 MB page image does not look like a stall. Gone,
        // not empty, for a document nothing has been sent for: an empty bar next to
        // "local only" invites the reading "sending, 0 percent".
        val show = st != null && st.fraction > 0f
        r.bar.visibility = if (show) View.VISIBLE else View.GONE
        r.pct.visibility = if (show) View.VISIBLE else View.GONE
        if (show && st != null) {
            r.bar.progress = (st.fraction * 1000).toInt()
            r.pct.text = "${(st.fraction * 100).toInt()}% of ${bytes(st.totalBytes)} on the card" +
                (if (st.inFlightBytes > 0L) ", ${bytes(st.inFlightBytes)} in flight" else "")
        }
        val queued = item.id in (q?.queuedItems ?: emptySet())
        r.queueButton.text = if (queued) "-" else "+"
    }

    private fun row(item: WalletItem, q: WalletSyncQueue?): Row {
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
                "${item.assetCount} assets, ${bytes(item.rawBytes)}" +
                (if (item.grey) ", grey" else "")
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        })
        val state = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        texts.addView(state)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (2 * dp).toInt() }
        }
        texts.addView(bar)
        val pct = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        texts.addView(pct)
        texts.setOnClickListener { openItem(item.id) }
        outer.addView(texts)

        // Queue toggle. Intent, not progress: it says the rider wants this document
        // on the card, and nothing about whether any of it is there.
        val queueButton = smallButton("+") {
            val nowQueued = item.id in (WalletSyncSession.queue ?: queue)?.queuedItems.orEmpty()
            store.setQueued(item.id, !nowQueued)
            refreshStates()
        }
        outer.addView(queueButton)
        outer.addView(smallButton("^") { move(item.id, -1) })
        outer.addView(smallButton("v") { move(item.id, 1) })
        outer.addView(smallButton("X") { confirmDelete(item) })

        val r = Row(outer, state, bar, pct, queueButton)
        update(r, item, q)
        return r
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
        // Reorder rewrites the manifest, so the manifest's hash changed and it is
        // pending again -- and nothing else is. Brief section 40, visible in the
        // header's pending figure.
        refreshStates()
    }

    private fun confirmDelete(item: WalletItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${item.title}?")
            .setMessage("${item.assetCount} assets, ${bytes(item.rawBytes)}. " +
                "The device has no delete of its own, so this is the only place it happens.")
            .setPositiveButton("Delete") { _, _ ->
                store.deleteItem(item.id)
                render()
                refreshStates()
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
