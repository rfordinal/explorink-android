package org.explorink.gpsbridge.wallet

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * **Debug builds only.** Runs the real import path over image files placed in the
 * app's private directory and prints what detection found.
 *
 * Why it exists: the degradation numbers that matter are the ones a **phone**
 * produces -- Android's own JPEG decoder, ZXing on ART, real JPEG artefacts. A
 * JVM unit test cannot make a JPEG at all (`javax.imageio` is not on the Android
 * unit-test classpath), and this app has no instrumented test set-up. So the
 * measurement is driven from adb instead:
 *
 *     adb exec-out run-as org.explorink.gpsbridge sh -c 'mkdir -p files/codetest'
 *     cat photo.jpg | adb shell run-as org.explorink.gpsbridge sh -c \
 *         'cat > files/codetest/photo.jpg'
 *     adb shell am start -n org.explorink.gpsbridge/.wallet.WalletCodeSelfTestActivity
 *     adb logcat -d -s WalletCodeSelfTest
 *
 * With `--es import <name>` it also runs a full [WalletImporter] import of that
 * one file, so the wallet ends up with a real item whose codes came off a
 * degraded photograph.
 *
 * It is exported only in `src/debug/AndroidManifest.xml`, so a release build has
 * neither the activity nor the entry point.
 */
class WalletCodeSelfTestActivity : Activity() {

    companion object {
        private const val TAG = "WalletCodeSelfTest"
        private const val DIR = "codetest"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val out = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 10f
            setPadding(16, 16, 16, 16)
        }
        setContentView(ScrollView(this).apply { addView(out) })

        val dir = File(filesDir, DIR)
        val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
        val log = StringBuilder("self-test: ${files.size} file(s) in ${dir.absolutePath}\n")
        Log.i(TAG, "start: ${files.size} file(s) in ${dir.absolutePath}")

        for (file in files) {
            val started = System.currentTimeMillis()
            val line = try {
                val loaded = ImageImport.load(this, Uri.fromFile(file))
                val decoded = System.currentTimeMillis()
                val found = CodeReader.detect(loaded.gray)
                val done = System.currentTimeMillis()
                "%-38s %5dx%-5d sample=%d decode=%dms detect=%dms -> %s".format(
                    file.name, loaded.gray.width, loaded.gray.height, loaded.sampleSize,
                    decoded - started, done - decoded,
                    if (found.isEmpty()) "NOTHING"
                    else found.joinToString(", ") {
                        "${it.symbology.key}/${it.payload.length}chars/${it.stage}"
                    })
            } catch (t: Throwable) {
                "%-38s FAILED %s".format(file.name, t)
            }
            Log.i(TAG, line)
            log.append(line).append('\n')
        }

        // `--es delete <itemId>[,<itemId>...]` removes documents without tapping X on
        // every row. It exists because a **test** wallet grows fast and the device caps
        // the encrypted manifest at 32 KB (`WalletCrypto.h`
        // `kMaxEncryptedManifestBytes`): nine test documents produced a 36,296 byte
        // manifest and the panel answered "Wallet list is too big for this device",
        // 2026-08-19. Pruning through the UI is a tap and a dialog per row, with the
        // rows renumbering under you after each one.
        val toDelete = intent.getStringExtra("delete")
        if (toDelete != null) {
            val store = WalletImporter.store(this)
            for (id in toDelete.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val before = store.load().items.size
                val after = store.deleteItem(id).items.size
                val line = "delete $id: items $before -> $after"
                Log.i(TAG, line)
                log.append(line).append('\n')
            }
        }

        val toImport = intent.getStringExtra("import")
        if (toImport != null) {
            val file = File(dir, toImport)
            val store = WalletImporter.store(this)
            // `--ez grey true` marks the imported document grey, which is the only way
            // to get a grey item onto an emulator without tapping the import dialog's
            // checkbox (phase P6/P7 verification).
            val grey = intent.getBooleanExtra("grey", false)
            val title = intent.getStringExtra("title")
                ?: "Selftest ${file.nameWithoutExtension}"
            val outcome = WalletImporter.importImages(this, store, listOf(Uri.fromFile(file)),
                title, grey)
            val line = when (outcome) {
                is WalletImporter.Outcome.Ok ->
                    "import ${file.name}: item ${outcome.item.id}, " +
                    "${outcome.item.codeCount} code(s), ${outcome.item.assetCount} assets, " +
                    "${outcome.millis} ms, grey=${outcome.item.grey}, codes=" +
                        outcome.item.pages.flatMap { it.codes }
                        .joinToString(", ") {
                            "${it.id}/${it.symbology}/${it.orientation}/${it.moduleSize}px/" +
                                (if (it.verified) "verified" else "UNVERIFIED")
                        }
                is WalletImporter.Outcome.Failed -> "import ${file.name} FAILED: ${outcome.message}"
            }
            Log.i(TAG, line)
            log.append(line).append('\n')
        }

        Log.i(TAG, "done")
        out.text = log
    }
}
