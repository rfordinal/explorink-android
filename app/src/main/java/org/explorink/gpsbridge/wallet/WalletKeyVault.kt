package org.explorink.gpsbridge.wallet

import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the wallet key K lives on the phone.
 *
 * ## The choice, and why
 *
 * K is **not** an Android Keystore key. It is a 32-byte secret generated with
 * `SecureRandom` and kept in app-private storage **wrapped under a hardware-backed
 * AES-256-GCM key that cannot leave the Keystore** ([KeyEncryptionKey], supplied by
 * `AndroidWalletKeyStore`).
 *
 * Two hard requirements rule out making K itself a Keystore key, and both come from
 * the format rather than from Android:
 *
 *  - **K has to leave the phone.** The device needs the same K to read the card
 *    (`docs/wallet-format.md`, "Provisioning"). A Keystore key is non-exportable by
 *    construction -- that is its entire value -- so a Keystore K could never be
 *    provisioned.
 *  - **AES-CTR at an arbitrary block counter needs the raw key.** Asset decryption
 *    starts the counter at `offset / 16` for a windowed read (section 11). The
 *    Keystore's AES/CTR takes an IV, not a caller-chosen counter block, and its keys
 *    are used through opaque handles.
 *
 * So the Keystore is used where it can actually apply: it protects K **at rest**.
 * The wrapping key is generated in the TEE (StrongBox when the phone has one), never
 * appears in app memory, and is destroyed with the app's Keystore entry. K exists in
 * the clear only in RAM while a tree is being written, and on disk only as GCM
 * ciphertext with a fresh nonce per write.
 *
 * What this does **not** protect against, stated rather than implied: a rooted phone
 * running as this app can ask the Keystore to unwrap, exactly as the app does. The
 * wrap raises the cost of an offline read of the app's data directory -- a backup, a
 * pulled image, another app with a filesystem hole -- and nothing more. Same shape of
 * boundary as the device side, where the wrapped key sits in plaintext NVS
 * (`docs/wallet-format.md`, "Threat boundary").
 *
 * ## Portability
 *
 * This file is plain JVM: the envelope format, the file layout and the atomic write
 * are testable on the laptop with an in-memory [KeyEncryptionKey]. The iOS shape is
 * the same one -- a Keychain item with `kSecAttrAccessible…ThisDeviceOnly`, or the
 * same blob wrapped by a Secure Enclave key -- so a port replaces
 * `AndroidWalletKeyStore` and keeps this.
 */
class WalletKeyVault(
    /** `<root>/wallet.key` -- app-private, and never inside `tree/`. */
    val file: File,
    private val kek: KeyEncryptionKey,
) : WalletKeyStore {

    override fun hasKey(): Boolean = file.isFile

    override fun loadKey(): ByteArray? {
        if (!file.isFile) return null
        return unwrap(file.readBytes())
    }

    /**
     * Generate K and store it wrapped. Refuses to overwrite: a second key would
     * orphan every asset already written under the first one, and the wallet has no
     * way to tell the rider which tree the old key belonged to.
     */
    override fun createKey(): ByteArray {
        check(!file.isFile) { "a wallet key already exists at $file" }
        val key = WalletCrypto.newKey()
        WalletStore.writeAtomic(file, wrap(key))
        return key
    }

    override fun loadOrCreateKey(): ByteArray = loadKey() ?: createKey()

    override fun deleteKey() {
        file.delete()
    }

    override val description: String get() = kek.description

    // --- the envelope ------------------------------------------------------

    /**
     * ```
     * magic "EWK1" | u8 version = 1 | u8 nonceLen | u8 nonce[nonceLen]
     * | ciphertext | u8 tag[16]
     * ```
     *
     * The nonce length is in the file rather than assumed: a Keystore AES/GCM
     * implementation is allowed to pick its own IV, and on some devices it does.
     * Hard-coding 12 here would produce a file that only unwraps on the phone that
     * wrote it and fails as "corrupt" everywhere else.
     */
    private fun wrap(key: ByteArray): ByteArray {
        WalletCrypto.requireKey(key)
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, kek.secretKey(), kek.encryptParams())
        val nonce = c.iv ?: throw IllegalStateException("GCM cipher produced no IV")
        require(nonce.size in 1..255) { "unusable GCM nonce length ${nonce.size}" }
        val sealed = c.doFinal(key)
        val out = ByteArray(HEADER + nonce.size + sealed.size)
        MAGIC.copyInto(out, 0)
        out[4] = VERSION.toByte()
        out[5] = nonce.size.toByte()
        nonce.copyInto(out, HEADER)
        sealed.copyInto(out, HEADER + nonce.size)
        return out
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        require(blob.size > HEADER) { "wallet key file is truncated" }
        for (i in 0 until 4) require(blob[i] == MAGIC[i]) { "not a wallet key file" }
        val version = blob[4].toInt() and 0xff
        require(version == VERSION) { "unsupported wallet key file version $version" }
        val nonceLen = blob[5].toInt() and 0xff
        require(blob.size > HEADER + nonceLen) { "wallet key file is truncated" }
        val nonce = blob.copyOfRange(HEADER, HEADER + nonceLen)
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, kek.secretKey(),
            GCMParameterSpec(TAG_BITS, nonce))
        val key = try {
            c.doFinal(blob, HEADER + nonceLen, blob.size - HEADER - nonceLen)
        } catch (t: Throwable) {
            throw IllegalStateException(
                "the wallet key does not unwrap (${t.javaClass.simpleName}). The " +
                    "key-encryption key is gone or the file was altered; the tree " +
                    "on this phone cannot be read.", t)
        }
        return WalletCrypto.requireKey(key)
    }

    companion object {
        val MAGIC = "EWK1".toByteArray(Charsets.US_ASCII)
        const val VERSION = 1

        /** magic(4) + version(1) + nonceLen(1) */
        const val HEADER = 6
        const val TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/**
 * The wallet key store seam. One implementation per platform, and nothing above it
 * knows which.
 */
interface WalletKeyStore {
    fun hasKey(): Boolean
    fun loadKey(): ByteArray?
    fun createKey(): ByteArray
    fun loadOrCreateKey(): ByteArray
    fun deleteKey()

    /** One line for the UI and the log: what is actually protecting the key. */
    val description: String

    /**
     * No key store at all. A wallet built with this one is **cleartext**, which the
     * device will then hide behind any `manifest.enc` already on the card
     * (`docs/wallet-plan.md` 7l). Tests and the pre-P3 path only.
     */
    object None : WalletKeyStore {
        override fun hasKey() = false
        override fun loadKey(): ByteArray? = null
        override fun createKey() = throw UnsupportedOperationException("no key store")
        override fun loadOrCreateKey() = throw UnsupportedOperationException("no key store")
        override fun deleteKey() {}
        override val description: String get() = "none (cleartext wallet)"
    }
}

/**
 * The key that wraps K. Its whole contract is "give me a `SecretKey` I cannot
 * export"; where it comes from is the platform's business.
 */
interface KeyEncryptionKey {
    fun secretKey(): SecretKey

    /** Encryption-side parameters. Null lets the provider pick its own IV. */
    fun encryptParams(): java.security.spec.AlgorithmParameterSpec? = null

    val description: String
}

/**
 * A KEK held in this process's memory. **Tests only** -- it protects nothing, and
 * it exists so the envelope format and the vault's file handling can be checked
 * without an Android Keystore.
 */
class InMemoryKeyEncryptionKey(
    raw: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) },
) : KeyEncryptionKey {
    private val key = javax.crypto.spec.SecretKeySpec(raw.copyOf(), "AES")
    override fun secretKey(): SecretKey = key
    override val description: String get() = "in-memory test key (protects nothing)"
}
