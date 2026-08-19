package org.explorink.gpsbridge.wallet

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Turns picked or shared images into a wallet item on disk.
 *
 * Runs on a worker thread: one A4 page is 24 assets and ~1.5 MB of dithering and
 * packing, which is far too much for the main looper. Nothing here touches a
 * view; the caller posts progress back itself.
 *
 * Multiple images become **one multi-page item** in this phase (brief section 3
 * allows either; the MVP defaults to one item, which is what a two-sided
 * document or a multi-page contract wants). Splitting them into separate items
 * is a later choice in the import dialog, not a format question.
 */
object WalletImporter {

    private const val TAG = "WalletImport"

    /** Where the store lives inside the app's private files directory. */
    fun storeRoot(context: Context): File = File(context.filesDir, "wallet")

    /**
     * The app's wallet store, with its key store attached.
     *
     * The key is Keystore-wrapped and created on first use ([AndroidWalletKeyStore]).
     * Every store built here therefore writes an **encrypted** tree once a key exists,
     * which is what brief section 16 asks for and what stops a phone sync from being
     * invisible on a card that already holds one (`docs/wallet-plan.md` 7l).
     */
    fun store(context: Context): WalletStore {
        val root = storeRoot(context)
        return WalletStore(root, keys = AndroidWalletKeyStore.vault(root))
    }

    class Progress(val page: Int, val pages: Int, val asset: Int, val assets: Int)

    sealed class Outcome {
        class Ok(val item: WalletItem, val wallet: Wallet, val millis: Long) : Outcome()
        class Failed(val message: String) : Outcome()
    }

    /**
     * Render [uris] into one item and write it into [store].
     *
     * [title] empty means "derive one from the first image's name", which is what
     * the share path does when the rider does not type anything.
     */
    fun importImages(
        context: Context,
        store: WalletStore,
        uris: List<Uri>,
        title: String,
        /**
         * Four-level grey for this document. Chosen at import because the grey
         * assets are built from the source pages, and the phone does not keep the
         * originals -- a share-target Uri is not persistable, so there is nothing to
         * re-render from later. The item screen can still flip the flag off and back
         * on once the assets exist (`WalletStore.setGrey`).
         */
        grey: Boolean = false,
        /**
         * A photograph rather than a scan. Only reaches the **grey** levels, where it
         * swaps nearest-value quantisation for error diffusion: a photo taken the
         * document way posterises into bands (seen on the panel 2026-08-19).
         */
        tone: WalletPipeline.Tone = WalletPipeline.Tone.DOCUMENT,
        onProgress: (Progress) -> Unit = {},
    ): Outcome {
        if (uris.isEmpty()) return Outcome.Failed("nothing to import")
        val started = System.currentTimeMillis()
        return try {
            val loaded = ArrayList<ImageImport.Loaded>(uris.size)
            for (uri in uris) {
                loaded.add(ImageImport.load(context, uri))
            }
            val names = loaded.map { it.name }
            val finalTitle = title.ifBlank { titleFrom(names.first()) }
            val itemId = WalletFormat.itemIdFor(finalTitle, names)
            // Encryption on by default (brief section 16). A wallet that already holds
            // cleartext items stays cleartext -- converting needs a re-import, because
            // the asset id recipe is crypto-scoped. Said out loud in the log either way:
            // a cleartext wallet is the one the device can hide behind a manifest.enc.
            val enc = store.applyDefaultEncryption()
            Log.i(TAG, "wallet is ${if (enc) "encrypted" else "cleartext"} " +
                "(key store: ${store.keys.description})")
            // Page images only, no pre-cut tiles (design B, the generator's own default
            // since 2026-08-19), and the store's cipher so an encrypted wallet stays one.
            val pipeline = store.pipeline(grey = grey, tone = tone)
            val sources = loaded.map {
                WalletPipeline.PageSource(it.gray, it.name, it.dpiX, it.dpiY,
                    codes = detectCodes(it))
            }
            var page = 0
            val item = pipeline.buildItem(
                itemId = itemId,
                title = finalTitle,
                createdAt = isoNow(),
                sortOrder = 0,
                sources = sources,
                sink = store.sink(),
                paper = "auto",
                progress = { done, total ->
                    val perPage = maxOf(1, total / sources.size)
                    page = minOf(sources.size, (done - 1) / perPage + 1)
                    onProgress(Progress(page, sources.size, done, total))
                },
            )
            val wallet = store.addItem(item, names)
            Log.i(TAG, "imported ${item.id} '${item.title}': ${item.pageCount} pages, " +
                "${item.assetCount} assets, ${item.rawBytes} B raw, " +
                (if (grey) "grey" else "1bpp") + ", tone=${tone.key}")
            Outcome.Ok(item, wallet, System.currentTimeMillis() - started)
        } catch (t: Throwable) {
            Log.w(TAG, "import failed", t)
            Outcome.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * Codes on one imported page (phase P5).
     *
     * Runs on the grey pixels **as decoded**, before `autocontrast`: that is the
     * photograph, and the fewer steps between the camera and the decoder the
     * better. Only the payload and the symbology are taken from it -- the code the
     * device shows is regenerated clean by [CodeWriter], never cropped out of the
     * photo (`docs/wallet-format.md` section 10).
     *
     * Never fails an import. A page with no code is the normal case (a passport
     * spread, a photo of a contract), and a decoder that throws on a damaged
     * region must not cost the rider the whole document.
     */
    fun detectCodes(page: ImageImport.Loaded): List<WalletPipeline.CodeRequest> {
        val found = try {
            CodeReader.detect(page.gray)
        } catch (t: Throwable) {
            Log.w(TAG, "code detection failed on ${page.name}", t)
            return emptyList()
        }
        for (f in found) {
            // The payload is personal data (a boarding pass names its passenger),
            // so the log gets the symbology and a length, never the text.
            Log.i(TAG, "code on ${page.name}: ${f.symbology.key}, " +
                "${f.payload.length} chars, found at ${f.stage}")
        }
        return found.map { WalletPipeline.CodeRequest(it.symbology, it.payload) }
    }

    /**
     * "passport-front.jpg" -> "passport-front".
     *
     * A bare number means the name was not a name: a MediaStore Uri that arrived
     * through a share answers no metadata query without `READ_MEDIA_IMAGES`, so
     * the best available "name" is the row id ("24"). Suggesting that as a title
     * is worse than suggesting nothing, so it becomes "Document" and the rider
     * types over it. See `ImageImport.displayName`.
     */
    fun titleFrom(name: String): String {
        val base = name.substringAfterLast('/').substringBeforeLast('.')
        if (base.isBlank() || base.all { it.isDigit() }) return "Document"
        return base
    }

    /** ISO 8601 UTC, the same shape `walletgen.py` writes. */
    fun isoNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }
}
