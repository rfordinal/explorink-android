package org.explorink.gpsbridge.wallet

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.security.SecureRandom

/**
 * **Debug builds only. Prints the wallet key in the clear.**
 *
 * The other half of a test seam that already exists on the device side: the firmware's
 * `CMD:WALLETPROVISION <keyhex64> <pattern> <salthex32> <iters>` takes
 * K as an argument and wraps it under PBKDF2(pattern || deviceSecret) -- the device does
 * **not** derive K, it stores whatever it is given
 * (`src/activities/wallet/WalletKeyStore.h:34`). So a phone-encrypted wallet is readable
 * on a device only if the device was provisioned with **this phone's** K.
 *
 * Nothing carries K from the phone to the device today. BLE provisioning (brief P4/P6)
 * is the real answer and does not exist; until it does, the only way to test the
 * encrypted chain end to end is to read K here and type it into that command. Both
 * seams are marked the same way and both go when BLE provisioning lands.
 *
 *     adb shell am start -n org.explorink.gpsbridge/.wallet.WalletKeyExportActivity
 *     adb logcat -d -s WalletKeyExport
 *
 * `--es pattern 567ba98c` and `--es salt <32 hex>` shape the printed command; without a
 * salt one is generated, because the salt is the device's to keep and any value works as
 * long as the same one is used for every unlock of that provisioning.
 *
 * The key it prints is the app's **real** wallet key -- the one every imported document
 * is encrypted under -- so a logcat of this run is as sensitive as the wallet itself.
 * Exported only in `src/debug/AndroidManifest.xml`: a release build has neither the
 * activity nor the entry point.
 */
class WalletKeyExportActivity : Activity() {

    companion object {
        private const val TAG = "WalletKeyExport"
        // The device's secret is a walk on its 4x4 grid, entered on four direction
        // buttons: 8 to 12 cells of 0-9a-f, start cell first, each step one cell
        // orthogonally (`src/activities/wallet/WalletCrypto.h`, kPatternMinPoints).
        // "567ba98c" is right, right, down, left, left, left, down from cell 5.
        //
        // **Legal hex is not enough and the firmware says so separately**: a diagonal
        // or a jump gets `WALLETPROVISION_ERR pattern is not a walk on the 4x4 grid`,
        // a wrong alphabet or length gets `...pattern must be 8-12 cells of 0-9a-f`.
        // So a hand-passed `--es pattern` can be refused for two different reasons and
        // the message says which.
        //
        // This default was `1234` first (refused: not the alphabet), then `UUDDLR`
        // under the directional PIN the gate replaced on 2026-11-26. Each change was a
        // round trip, which is why the rule is written down rather than remembered.
        private const val DEFAULT_PATTERN = "567ba98c"
        private const val ITERATIONS = 18000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val out = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 10f
            setPadding(16, 16, 16, 16)
        }
        setContentView(ScrollView(this).apply { addView(out) })

        val lines = ArrayList<String>()
        fun say(line: String) {
            Log.w(TAG, line)
            lines.add(line)
        }

        say("*** TEST PATH: this prints the wallet key in the clear. ***")
        say("*** Replaced by BLE provisioning (P4/P6). Never run it on a phone holding real papers. ***")

        val root = File(WalletImporter.storeRoot(this).absolutePath)
        val keys = AndroidWalletKeyStore.vault(root)

        val key = keys.loadKey()
        // After the load, not before: `description` reports the key-encryption key, and a
        // KEK that has not been touched yet says "not yet created" even when a wrapped
        // wallet key sits on disk. Printing it first read as "no key" on a phone that
        // had one -- measured 2026-08-19.
        say("vault: ${keys.description}")
        if (key == null) {
            // No key yet is the normal state of a phone that has imported nothing: the
            // key is created on the first import, not on install
            // (`WalletStore.applyDefaultEncryption`). Import a document first.
            say("no key on this phone -- import a document, then run this again")
        } else {
            val pattern = intent.getStringExtra("pattern") ?: DEFAULT_PATTERN
            val salt = intent.getStringExtra("salt") ?: hex(ByteArray(16).also { SecureRandom().nextBytes(it) })
            say("key: ${key.size} bytes")
            say("keyhex: ${hex(key)}")
            say("provision the device with:")
            say("CMD:WALLETPROVISION ${hex(key)} $pattern $salt $ITERATIONS force")
            key.fill(0)
        }

        val store = WalletStore(root, keys = keys)
        say("tree: ${store.treeKind()}, encrypted=${store.encrypted}, items=${store.load().items.size}")

        out.text = lines.joinToString("\n")
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append("%02x".format(b.toInt() and 0xff))
        return sb.toString()
    }
}
