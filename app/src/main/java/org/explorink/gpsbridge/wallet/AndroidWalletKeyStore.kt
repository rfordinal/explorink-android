package org.explorink.gpsbridge.wallet

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * The Android half of the wallet key store: a hardware-backed AES-256-GCM
 * key-encryption key in the `AndroidKeyStore`, wrapping the wallet key K that
 * [WalletKeyVault] keeps in a file.
 *
 * **This is the only file in the crypto path that imports an Android type**, which
 * is what keeps the format portable (`docs/android-wallet.md`, "iOS notes"). The
 * reasoning for wrapping K rather than making K a Keystore key is on
 * [WalletKeyVault] and is a property of the wallet format, not of Android.
 *
 * The generation parameters, and why each one:
 *
 *  - `AES / GCM / NoPadding`, 256-bit -- authenticated, so a tampered key file fails
 *    loudly instead of unwrapping into garbage that then "decrypts" every asset.
 *  - Randomized encryption (the Keystore's default) -- the provider picks the IV, and
 *    the vault records its length rather than assuming 12.
 *  - **No user-authentication requirement.** A sync has to be able to run with the
 *    phone in a tank bag and the screen off; `setUserAuthenticationRequired` would
 *    put a fingerprint prompt in front of every transfer. The rider-facing secret is
 *    the device's directional PIN, which guards the panel where documents are
 *    actually readable (`docs/wallet-format.md` section 11).
 *  - **StrongBox when the phone has one**, falling back to the TEE. It has to be
 *    asked for and caught: a phone without StrongBox throws only from
 *    `generateKey()`.
 */
class AndroidWalletKeyStore private constructor(private val alias: String) : KeyEncryptionKey {

    /** What actually held the key, once we know. Never guessed. */
    private var backing: String = "not yet created"

    override fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = ks.getKey(alias, null) as? SecretKey
        if (existing != null) {
            backing = describe(existing)
            return existing
        }
        return generate()
    }

    override val description: String get() = "AndroidKeyStore AES-256-GCM, $backing"

    private fun generate(): SecretKey {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val k = keyGen().let { it.init(spec(strongBox = true)); it.generateKey() }
                backing = describe(k)
                return k
            } catch (t: Throwable) {
                // StrongBoxUnavailableException is API 28+ and not on every variant's
                // compile classpath, so it is reported by name rather than caught by
                // type. Anything else lands here too and gets the same retry, which
                // rethrows if the TEE cannot do it either.
                Log.i(TAG, "StrongBox unavailable (${t.javaClass.simpleName}), using the TEE")
            }
        }
        val k = keyGen().let { it.init(spec(strongBox = false)); it.generateKey() }
        backing = describe(k)
        return k
    }

    private fun keyGen(): KeyGenerator =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)

    private fun spec(strongBox: Boolean): KeyGenParameterSpec {
        val b = KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            b.setIsStrongBoxBacked(true)
        }
        return b.build()
    }

    /**
     * Ask the key where it lives instead of assuming. `getSecurityLevel()` is API 31+
     * and this app's `minSdk` is 31, so it is always available; the older
     * `isInsideSecureHardware` is the fallback for a provider that does not answer.
     */
    private fun describe(key: SecretKey): String = try {
        val info = SecretKeyFactory.getInstance(key.algorithm, KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software-backed"
            else -> "security level ${info.securityLevel}"
        }
    } catch (t: Throwable) {
        "backing unknown (${t.javaClass.simpleName})"
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "explorink-wallet-kek"
        private const val TAG = "WalletKeys"

        /**
         * The vault for a wallet root. The KEK is created on first use, not here, so
         * constructing this costs nothing and cannot throw.
         */
        fun vault(root: File, alias: String = DEFAULT_ALIAS): WalletKeyVault =
            WalletKeyVault(File(root, "wallet.key"), AndroidWalletKeyStore(alias))
    }
}
