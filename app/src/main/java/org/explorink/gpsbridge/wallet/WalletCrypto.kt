package org.explorink.gpsbridge.wallet

import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wallet crypto v1 on the phone. Contract: `docs/wallet-format.md` section 11.
 * Reference implementation: `tools/walletgen.py` (`ctr_crypt`,
 * `encrypt_manifest`, `asset_iv`) -- this file is a port of it and the encrypted
 * assets must come out byte-identical (`WalletCryptoParityTest`).
 *
 * ```
 * wallet key K   32 bytes from SecureRandom
 * assets         AES-256-CTR, key K, IV = assetId(8) || u32 version LE || u32 zero
 * manifest       AES-256-GCM, key K, fresh 12-byte nonce per write
 * scheme name    "wallet-crypto-v1"
 * ```
 *
 * **Portability.** `javax.crypto` is a JDK API, not an Android one, so every byte
 * of this file runs in a laptop unit test and an iOS port replaces it with
 * CryptoKit (`AES.GCM`, `AES._CTR`) without touching anything above it. The one
 * platform-shaped question -- where K is kept -- is behind [WalletKeyStore], and
 * only its Android implementation knows about the Android Keystore
 * (`AndroidWalletKeyStore`).
 *
 * **What this does not do.** It does not derive the device's key-wrapping KEK,
 * because that formula needs a 32-byte secret that never leaves the device
 * (`docs/wallet-format.md`, "Provisioning"). Handing K to a device is a separate
 * concern and is not wired here: today a test serial command provisions it.
 */
object WalletCrypto {

    const val SCHEME = "wallet-crypto-v1"
    const val ASSET_ALG = "aes-256-ctr"
    const val MANIFEST_ALG = "aes-256-gcm"

    /** 256-bit wallet key. Nothing here accepts another length. */
    const val KEY_LEN = 32

    val MANIFEST_MAGIC = "EWM1".toByteArray(Charsets.US_ASCII)

    /**
     * Container version 2. v1 was `magic|version|flags|nonce|plaintextLen`; v2 adds
     * a **cleartext** `u32 walletVersion` after `plaintextLen` so a locked card can
     * be diffed without a key (`docs/wallet-plan.md` 7g). It sits outside the GCM
     * tag, so it is a hint and never a trust anchor.
     */
    const val MANIFEST_VERSION = 2

    const val GCM_NONCE_LEN = 12
    const val GCM_TAG_LEN = 16

    /** `magic(4) | version(1) | flags(1) | nonce(12) | plaintextLen(4)` */
    const val MANIFEST_HEADER_V1 = 22

    /** ... `| walletVersion(4)` */
    const val MANIFEST_HEADER_V2 = 26

    private val random = SecureRandom()

    // --- keys --------------------------------------------------------------

    /** A fresh 256-bit wallet key K. */
    fun newKey(): ByteArray {
        val k = ByteArray(KEY_LEN)
        random.nextBytes(k)
        return k
    }

    fun requireKey(key: ByteArray): ByteArray {
        require(key.size == KEY_LEN) { "wallet key must be $KEY_LEN bytes, got ${key.size}" }
        return key
    }

    fun keyFromHex(text: String): ByteArray {
        val t = text.trim()
        require(t.length == KEY_LEN * 2) { "a wallet key is ${KEY_LEN * 2} hex characters" }
        val out = ByteArray(KEY_LEN)
        for (i in out.indices) {
            out[i] = ((hexDigit(t[2 * i]) shl 4) or hexDigit(t[2 * i + 1])).toByte()
        }
        return out
    }

    private fun hexDigit(c: Char): Int {
        val v = Character.digit(c, 16)
        require(v >= 0) { "not a hex digit: $c" }
        return v
    }

    // --- assets: AES-256-CTR -----------------------------------------------

    /**
     * The 16-byte CTR IV of one asset.
     *
     *     assetId raw bytes (8) || u32 version little endian || u32 zero
     *
     * The trailing zero word is the block counter. AES-CTR increments the whole
     * 128-bit block, which is the same thing as incrementing that last word until
     * it would carry -- and it cannot: the biggest asset here is a page image of a
     * few hundred thousand bytes, tens of thousands of blocks.
     */
    fun assetIv(assetId: String, version: Int): ByteArray {
        require(assetId.length == 16) { "assetId must be 8 bytes of hex, got '$assetId'" }
        val iv = ByteArray(16)
        for (i in 0 until 8) {
            iv[i] = ((hexDigit(assetId[2 * i]) shl 4) or hexDigit(assetId[2 * i + 1])).toByte()
        }
        for (i in 0 until 4) iv[8 + i] = ((version ushr (8 * i)) and 0xff).toByte()
        // 12..15 stay zero: the block counter.
        return iv
    }

    /**
     * AES-256-CTR over [data], which starts at byte [offset] of the asset's stream.
     *
     * Symmetric: the same call encrypts and decrypts. [offset] is what makes design
     * B's windowed reads possible -- the device seeks to a row at an arbitrary byte
     * offset, starts the counter at `offset / 16` and throws away `offset % 16`
     * bytes of keystream. No per-row nonce, no re-keying.
     */
    fun ctrCrypt(key: ByteArray, iv: ByteArray, data: ByteArray, offset: Int = 0): ByteArray {
        requireKey(key)
        require(iv.size == 16) { "a CTR IV is 16 bytes" }
        require(offset >= 0) { "offset must not be negative" }
        val skip = offset % 16
        val block = offset / 16
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"),
            IvParameterSpec(counterBlock(iv, block)))
        val input = ByteArray(skip + data.size)
        data.copyInto(input, skip)
        val out = c.doFinal(input)
        return if (skip == 0) out else out.copyOfRange(skip, out.size)
    }

    /** The IV advanced by [blockIndex] blocks, big endian, as AES-CTR does. */
    fun counterBlock(iv: ByteArray, blockIndex: Int): ByteArray {
        if (blockIndex == 0) return iv.copyOf()
        val n = (BigInteger(1, iv) + BigInteger.valueOf(blockIndex.toLong()))
            .mod(BigInteger.ONE.shiftLeft(128))
        val raw = n.toByteArray()
        val out = ByteArray(16)
        // toByteArray() may be shorter (leading zeroes dropped) or 17 bytes long
        // (a sign byte); take the low 16 either way.
        val from = maxOf(0, raw.size - 16)
        raw.copyInto(out, 16 - (raw.size - from), from, raw.size)
        return out
    }

    // --- manifest: AES-256-GCM ---------------------------------------------

    /**
     * The `manifest.enc` container:
     *
     * ```
     * magic "EWM1" | u8 version=2 | u8 flags | u8 nonce[12] | u32 plaintextLen
     * | u32 walletVersion | ciphertext | u8 tag[16]
     * ```
     *
     * A **fresh nonce every write**, so two writes of identical content differ --
     * and so a nonce is never reused under one key, which is the one mistake GCM
     * does not survive. That is also why an encrypted manifest cannot be
     * byte-compared against another writer's: same key, same content, different
     * bytes, by design.
     */
    fun encryptManifest(key: ByteArray, plaintext: ByteArray, walletVersion: Int): ByteArray {
        requireKey(key)
        val nonce = ByteArray(GCM_NONCE_LEN)
        random.nextBytes(nonce)
        return sealManifest(key, plaintext, walletVersion, nonce)
    }

    /** [encryptManifest] with the nonce supplied. Tests only -- never reuse one. */
    fun sealManifest(key: ByteArray, plaintext: ByteArray, walletVersion: Int,
                     nonce: ByteArray): ByteArray {
        requireKey(key)
        require(nonce.size == GCM_NONCE_LEN) { "a GCM nonce is $GCM_NONCE_LEN bytes" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_LEN * 8, nonce))
        val sealed = c.doFinal(plaintext)          // ciphertext || tag
        val out = ByteArray(MANIFEST_HEADER_V2 + sealed.size)
        MANIFEST_MAGIC.copyInto(out, 0)
        out[4] = MANIFEST_VERSION.toByte()
        out[5] = 0                                  // flags, none defined yet
        nonce.copyInto(out, 6)
        putU32(out, 18, plaintext.size)
        putU32(out, 22, walletVersion)
        sealed.copyInto(out, MANIFEST_HEADER_V2)
        return out
    }

    /**
     * Inverse. Throws [ManifestAuthException] when the tag does not verify --
     * wrong key, flipped ciphertext bit, flipped tag bit. A failure is a hard
     * error, never a warning.
     */
    fun decryptManifest(key: ByteArray, blob: ByteArray): ByteArray {
        requireKey(key)
        val head = readManifestHeader(blob)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_LEN * 8, blob.copyOfRange(6, 6 + GCM_NONCE_LEN)))
        val plain = try {
            c.doFinal(blob, head.headerLen, blob.size - head.headerLen)
        } catch (t: Throwable) {
            throw ManifestAuthException(
                "manifest does not authenticate: wrong key, or the file was altered")
        }
        if (plain.size != head.plaintextLen) {
            throw ManifestAuthException(
                "manifest length mismatch: header ${head.plaintextLen}, got ${plain.size}")
        }
        return plain
    }

    class ManifestAuthException(message: String) : RuntimeException(message)

    /**
     * What the cleartext part of a container says. Readable **without a key**,
     * which is the whole point of the v2 field: a locked card can still be diffed.
     */
    class ManifestHeader(
        val version: Int,
        val flags: Int,
        val plaintextLen: Int,
        /** -1 on a v1 container: it does not carry one. Never a trust anchor. */
        val walletVersion: Int,
        val headerLen: Int,
    )

    fun readManifestHeader(blob: ByteArray): ManifestHeader {
        require(blob.size >= MANIFEST_HEADER_V1 + GCM_TAG_LEN) {
            "encrypted manifest is truncated"
        }
        for (i in 0 until 4) {
            require(blob[i] == MANIFEST_MAGIC[i]) { "not an encrypted manifest" }
        }
        val version = blob[4].toInt() and 0xff
        val flags = blob[5].toInt() and 0xff
        return when (version) {
            1 -> ManifestHeader(1, flags, u32(blob, 18), -1, MANIFEST_HEADER_V1)
            2 -> {
                require(blob.size >= MANIFEST_HEADER_V2 + GCM_TAG_LEN) {
                    "encrypted manifest is truncated"
                }
                ManifestHeader(2, flags, u32(blob, 18), u32(blob, 22), MANIFEST_HEADER_V2)
            }
            else -> throw IllegalArgumentException(
                "unsupported manifest container version $version")
        }
    }

    private fun putU32(buf: ByteArray, at: Int, v: Int) {
        for (i in 0 until 4) buf[at + i] = ((v ushr (8 * i)) and 0xff).toByte()
    }

    private fun u32(buf: ByteArray, at: Int): Int {
        var v = 0
        for (i in 3 downTo 0) v = (v shl 8) or (buf[at + i].toInt() and 0xff)
        return v
    }

    /** The manifest's `crypto` block. `null` on a cleartext tree, and written as such. */
    fun descriptor(): LinkedHashMap<String, Any?> = linkedMapOf(
        "scheme" to SCHEME,
        "assets" to ASSET_ALG,
        "manifest" to MANIFEST_ALG,
    )
}

/**
 * AES-256-CTR at rest, the real [AssetCipher].
 *
 * The 32-byte header stays cleartext and `flags` bit 0 is set; `sha256_prefix` and
 * the manifest's `sha256` keep covering the **plaintext**, so on an encrypted asset
 * they are an integrity check after decryption and **not a MAC** -- they do not
 * authenticate the ciphertext, and only the manifest is authenticated.
 *
 * The IV needs the asset's `version`, and the header already carries it at offset
 * 16, so nothing above this has to pass it twice.
 *
 * **No sidecar.** An encrypted tree writes no `.rle` at all: ciphertext does not
 * compress (measured 3.89x down to 0.99x, `docs/wallet-format.md` section 12), so
 * absence is the signal and `rleLen` is null in the manifest.
 */
class Aes256CtrCipher(key: ByteArray) : AssetCipher {

    private val key = WalletCrypto.requireKey(key.copyOf())

    override val flags: Int get() = WalletFormat.FLAG_ENCRYPTED

    override val encrypted: Boolean get() = true

    override val writesSidecar: Boolean get() = false

    override fun seal(assetId: String, header: ByteArray, payload: ByteArray): ByteArray {
        val version = WalletFormat.versionOfHeader(header)
        return WalletCrypto.ctrCrypt(key, WalletCrypto.assetIv(assetId, version), payload)
    }

    /**
     * Never called on an encrypted tree, because no sidecar is written. Kept honest
     * rather than silently returning the plaintext: if some future caller asks for
     * a sidecar here, the same keystream would cover a second, different plaintext
     * -- the one thing CTR does not survive.
     */
    override fun sealSidecar(assetId: String, header: ByteArray, block: ByteArray): ByteArray =
        throw UnsupportedOperationException(
            "an encrypted tree writes no RLE sidecar: sealing one would reuse the " +
                "asset's CTR keystream on a second plaintext")

    /** Plaintext back out of a stored `.dat` body. Used by tests and by a verify pass. */
    fun open(assetId: String, header: ByteArray, body: ByteArray): ByteArray =
        seal(assetId, header, body)
}
