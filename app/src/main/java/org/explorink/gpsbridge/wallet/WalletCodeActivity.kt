package org.explorink.gpsbridge.wallet

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * One machine-readable code, full screen, exactly as the device will show it.
 *
 * The bitmap is decoded **from the stored asset bytes** and not re-rendered from
 * the payload: what the rider sees here is the same 1bpp screen the panel will
 * blit, unpacked and (for a portrait asset) unrotated. If the asset were wrong,
 * this screen would show it wrong.
 *
 * The phone is not the scanner target -- the panel is -- but it is where a rider
 * can check a code arrived before the device is anywhere near. Two things are
 * stated on screen because they decide whether the code is usable: the module
 * size in device pixels, and whether the verify loop passed
 * (`docs/wallet-format.md` section 10). An unverified code is labelled
 * unverified; it is never presented as trusted.
 */
class WalletCodeActivity : Activity() {

    companion object {
        const val EXTRA_ITEM_ID = "itemId"
        const val EXTRA_CODE_ID = "codeId"
    }

    private val store: WalletStore by lazy { WalletImporter.store(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            // Without this the content is laid out at y=0, under the action bar,
            // and the caption is invisible -- seen on the emulator, fixed here.
            // Every other screen in this app gets it from its layout XML.
            fitsSystemWindows = true
        }
        val caption = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.BLACK)
            setPadding(16, 16, 16, 8)
        }
        val image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
        }
        root.addView(caption)
        root.addView(image)
        setContentView(root)

        val itemId = intent.getStringExtra(EXTRA_ITEM_ID)
        val codeId = intent.getStringExtra(EXTRA_CODE_ID)
        val wallet = store.load()
        val item = itemId?.let { wallet.item(it) }
        val page = item?.pages?.firstOrNull { p -> p.codes.any { it.id == codeId } }
        val code = page?.codes?.firstOrNull { it.id == codeId }
        if (code == null) {
            caption.text = "code is gone (deleted, or the wallet was rebuilt)"
            return
        }
        title = "${code.symbology} ${code.id}"

        val panel = Panels.byName(wallet.panelName)
        val file = store.assetFile(code.assetId, "dat")
        val bytes = try {
            file.readBytes()
        } catch (t: Throwable) {
            caption.text = "cannot read ${file.name}: $t"
            return
        }
        if (bytes.size != WalletFormat.ASSET_HEADER_LEN + panel.assetBytes) {
            caption.text = "asset is ${bytes.size} B, expected " +
                "${WalletFormat.ASSET_HEADER_LEN + panel.assetBytes} B"
            return
        }
        // Decrypt if the tree is encrypted. Reading the body straight off disk drew the
        // ciphertext as a bitmap -- a screen of noise, with the caption still claiming
        // the code was verified (found on a phone 2026-08-19). The store owns the
        // cipher, so ask it rather than guessing from the header.
        val header = bytes.copyOfRange(0, WalletFormat.ASSET_HEADER_LEN)
        val stored = bytes.copyOfRange(WalletFormat.ASSET_HEADER_LEN, bytes.size)
        val payload = try {
            store.cipher().open(code.assetId, header, stored)
        } catch (t: Throwable) {
            caption.text = "cannot decrypt ${file.name}: $t"
            return
        }
        val shown = CodeReader.assetImage(payload, panel, code.presentation)
        image.setImageBitmap(bitmapOf(shown))
        caption.text = buildString {
            append(code.symbology).append("  ").append(code.orientation)
            append("  ").append(code.moduleSize).append(" px/module")
            append("  qz ").append(code.quietZone)
            append("  ").append(code.codeWidthPx).append("x").append(code.codeHeightPx).append(" px")
            append('\n')
            append(if (code.verified) "verified: decoded back out of the stored asset"
                   else "NOT VERIFIED -- do not trust this code")
            append('\n')
            append(if (code.presentation == WalletFormat.PRESENTATION_LANDSCAPE)
                       "on the device: turn it sideways"
                   else "on the device: hold it as usual")
            append("   asset ").append(code.assetId)
        }
        caption.gravity = Gravity.START
    }

    /**
     * 1bpp screen to a screen bitmap. `ALPHA_8` would be smaller but draws as a
     * mask; `RGB_565` of 480x800 is 768 kB and shows real black on real white,
     * which is the point of looking at it.
     */
    private fun bitmapOf(img: MonoImage): Bitmap {
        val px = IntArray(img.width * img.height)
        for (i in px.indices) {
            px[i] = if (img.pixels[i].toInt() == 0) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(px, img.width, img.height, Bitmap.Config.RGB_565)
    }
}
