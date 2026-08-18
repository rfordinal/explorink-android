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

    fun store(context: Context): WalletStore = WalletStore(storeRoot(context))

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
            val pipeline = WalletPipeline(Panels.byName(store.panelName))
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
                "${item.assetCount} assets, ${item.rawBytes} B raw")
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
