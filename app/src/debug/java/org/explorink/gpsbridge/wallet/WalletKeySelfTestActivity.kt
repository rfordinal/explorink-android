package org.explorink.gpsbridge.wallet

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * **Debug builds only.** Exercises the wallet key against the **real Android
 * Keystore** and prints what protected it.
 *
 * Why it exists: [WalletKeyVault]'s envelope, its file layout and its failure modes
 * are all covered by `WalletCryptoTest` on the laptop, with an in-memory
 * key-encryption key. The one thing a JVM test cannot touch is
 * [AndroidWalletKeyStore] itself -- `AndroidKeyStore` is a platform provider that
 * does not exist off a device -- so the claim "the wallet key is Keystore-wrapped"
 * has to be checked on a real phone or an emulator, from adb:
 *
 *     adb shell am start -n org.explorink.gpsbridge/.wallet.WalletKeySelfTestActivity
 *     adb logcat -d -s WalletKeySelfTest
 *     adb exec-out run-as org.explorink.gpsbridge cat files/walletkeytest/result.txt
 *
 * `--ez fresh true` deletes the wrapped key file first, so a run starts from
 * "no key yet". The Keystore entry itself is **never** deleted here: destroying it
 * would make every wallet on the phone unreadable, which is not something a
 * self-test may do.
 *
 * What it asserts, in order:
 *
 *  1. A fresh vault has no key, and creating one gives 32 bytes.
 *  2. A reload gives the same 32 bytes -- so the Keystore unwrapped it.
 *  3. The key does **not** appear anywhere in the file that holds it.
 *  4. A second `createKey()` is refused rather than orphaning the tree.
 *  5. A tampered wrapped file fails to unwrap instead of yielding garbage.
 *  6. An asset encrypted under the loaded key decrypts back to its plaintext.
 *
 * Exported only in `src/debug/AndroidManifest.xml`, so a release build has neither
 * the activity nor the entry point.
 */
class WalletKeySelfTestActivity : Activity() {

    companion object {
        private const val TAG = "WalletKeySelfTest"
        private const val DIR = "walletkeytest"
    }

    private val lines = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val out = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 10f
            setPadding(16, 16, 16, 16)
        }
        setContentView(ScrollView(this).apply { addView(out) })

        val root = File(filesDir, DIR).also { it.mkdirs() }
        val keyFile = File(root, "wallet.key")
        if (intent.getBooleanExtra("fresh", false) && keyFile.delete()) {
            emit("fresh removed the previous wrapped key file")
        }

        var failures = 0
        fun check(name: String, body: () -> String) {
            failures += try {
                emit("ok   $name: ${body()}")
                0
            } catch (t: Throwable) {
                emit("FAIL $name: ${t.javaClass.simpleName}: ${t.message}")
                1
            }
        }

        val kek = AndroidWalletKeyStore.vault(root)
        emit("vault file=${kek.file.absolutePath}")

        check("the keystore answers") {
            // Forces creation of the KEK, which is where StrongBox is asked for.
            kek.loadOrCreateKey()
            kek.description
        }

        val key = kek.loadKey() ?: ByteArray(0)
        check("a key exists and is 256 bits") {
            require(key.size == WalletCrypto.KEY_LEN) { "got ${key.size} bytes" }
            "${key.size * 8} bits"
        }

        check("a reload unwraps to the same key") {
            val again = kek.loadKey()!!
            require(again.contentEquals(key)) { "the reloaded key differs" }
            "identical on reload"
        }

        check("the wrapped file does not contain the key") {
            val blob = kek.file.readBytes()
            require(!WalletFormat.hex(blob).contains(WalletFormat.hex(key))) {
                "the key is in the file in the clear"
            }
            require(blob.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "EWK1") {
                "not an EWK1 envelope"
            }
            "${blob.size} B envelope, key absent, nonce ${blob[5].toInt() and 0xff} B"
        }

        check("a second create is refused") {
            try {
                kek.createKey()
                throw AssertionError("a second createKey must be refused")
            } catch (e: AssertionError) {
                throw e
            } catch (e: Throwable) {
                e.javaClass.simpleName
            }
        }

        check("a tampered envelope fails to unwrap") {
            val good = kek.file.readBytes()
            try {
                val bad = good.copyOf()
                bad[bad.size - 2] = (bad[bad.size - 2].toInt() xor 0xff).toByte()
                kek.file.writeBytes(bad)
                try {
                    kek.loadKey()
                    throw AssertionError("a tampered envelope must not unwrap")
                } catch (e: AssertionError) {
                    throw e
                } catch (e: Throwable) {
                    e.javaClass.simpleName
                }
            } finally {
                kek.file.writeBytes(good)
            }
        }

        check("an asset encrypts and decrypts under the loaded key") {
            val cipher = Aes256CtrCipher(kek.loadKey()!!)
            val aid = WalletFormat.assetId("x4", "selftest", "p001",
                WalletFormat.ASSET_PAGE_IMAGE, 0, 1, encrypted = true)
            val plain = ByteArray(1000) { ((it * 37) and 0xff).toByte() }
            val header = WalletFormat.buildAssetHeader(
                WalletFormat.ASSET_PAGE_IMAGE, WalletFormat.BIT_DEPTH_1BPP, 0, 0,
                800, 480, plain, 1, flags = cipher.flags)
            val body = cipher.seal(aid, header, plain)
            require(!body.contentEquals(plain)) { "the body was not encrypted" }
            require(cipher.open(aid, header, body).contentEquals(plain)) {
                "the body did not decrypt back"
            }
            // And a windowed read: the device pulls a row from the middle.
            val at = 517
            val row = WalletCrypto.ctrCrypt(kek.loadKey()!!,
                WalletCrypto.assetIv(aid, 1), body.copyOfRange(at, at + 100), at)
            require(row.contentEquals(plain.copyOfRange(at, at + 100))) {
                "a windowed read at offset $at did not match"
            }
            "1000 B round trip, windowed read at $at B"
        }

        // What the app itself will do with this: the real store's own state.
        val store = WalletImporter.store(this)
        emit("app store encrypted=${store.encrypted} manifest=${store.manifestFile.name} " +
            "tree=${store.treeKind().label} keys=\"${store.keys.description}\"")

        emit(if (failures == 0) "done failures=0" else "done failures=$failures")
        out.text = lines.joinToString("\n")
        File(root, "result.txt").writeText(lines.joinToString("\n") + "\n")
    }

    private fun emit(line: String) {
        Log.i(TAG, line)
        lines.add(line)
    }
}
