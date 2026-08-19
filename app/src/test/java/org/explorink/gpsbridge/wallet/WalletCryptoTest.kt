package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Wallet crypto v1, on its own: the CTR seek, the GCM container, the id scoping and
 * the key vault's envelope.
 *
 * Parity against `tools/walletgen.py` lives in [WalletCryptoParityTest]; this file is
 * about the properties the format leans on, each written as the failure it prevents.
 */
class WalletCryptoTest {

    private val key = ByteArray(32) { (it * 7 + 1).toByte() }
    private val other = ByteArray(32) { (it * 5 + 3).toByte() }
    private val aid = "0123456789abcdef"

    // --- the IV ------------------------------------------------------------

    @Test
    fun the_iv_is_the_asset_id_then_the_version_then_zero() {
        val iv = WalletCrypto.assetIv(aid, 1)
        assertEquals(16, iv.size)
        assertEquals(aid, WalletFormat.hex(iv, 8))
        // u32 version, little endian, then the block counter word at zero.
        assertEquals("01000000", WalletFormat.hex(iv.copyOfRange(8, 12)))
        assertEquals("00000000", WalletFormat.hex(iv.copyOfRange(12, 16)))
    }

    @Test
    fun a_new_version_of_one_asset_gets_a_new_keystream() {
        // Same asset, same key, different version -> different IV. Without this, a
        // re-rendered page would encrypt new plaintext under the old keystream, which
        // is the one thing CTR does not survive.
        val data = ByteArray(64) { it.toByte() }
        val v1 = WalletCrypto.ctrCrypt(key, WalletCrypto.assetIv(aid, 1), data)
        val v2 = WalletCrypto.ctrCrypt(key, WalletCrypto.assetIv(aid, 2), data)
        assertNotEquals(WalletFormat.hex(v1), WalletFormat.hex(v2))
    }

    // --- the seek, which is why CTR was chosen -----------------------------

    /**
     * **Every byte offset has to be reachable without decrypting from the start.**
     *
     * Design B's windowed reads pull 100-byte rows out of the middle of a page image
     * (`docs/wallet-format.md` section 9), so the device starts the counter at
     * `offset / 16` and throws away `offset % 16` bytes of keystream. Tested at the
     * offsets that break a naive implementation: 0, 1, a block edge either side, a row
     * boundary, and the last byte.
     */
    @Test
    fun decryption_from_any_offset_gives_the_same_plaintext() {
        val plain = ByteArray(1000) { ((it * 31) and 0xff).toByte() }
        val iv = WalletCrypto.assetIv(aid, 3)
        val cipher = WalletCrypto.ctrCrypt(key, iv, plain)
        for (offset in intArrayOf(0, 1, 15, 16, 17, 99, 100, 512, 999)) {
            for (len in intArrayOf(1, 16, 100)) {
                if (offset + len > plain.size) continue
                val slice = cipher.copyOfRange(offset, offset + len)
                val back = WalletCrypto.ctrCrypt(key, iv, slice, offset)
                assertArrayEquals("offset $offset len $len",
                    plain.copyOfRange(offset, offset + len), back)
            }
        }
    }

    @Test
    fun a_seek_past_the_first_65536_blocks_still_counts_correctly() {
        // The counter is the low word of the IV and AES-CTR increments the whole
        // 128-bit block. A page image is tens of thousands of blocks; this walks past
        // the point where a 16-bit counter would wrap.
        val iv = WalletCrypto.assetIv(aid, 1)
        val at = WalletCrypto.counterBlock(iv, 70_000)
        assertEquals(aid, WalletFormat.hex(at, 8))
        assertEquals(70_000, ((at[15].toInt() and 0xff) or
            ((at[14].toInt() and 0xff) shl 8) or ((at[13].toInt() and 0xff) shl 16)))
    }

    @Test
    fun the_counter_block_carries_into_the_version_word_rather_than_wrapping() {
        // A carry cannot happen for any asset this format can hold, but if it ever
        // did, it must behave as AES-CTR does -- big-endian increment of the whole
        // block -- and not silently wrap the low word.
        val iv = ByteArray(16) { if (it >= 12) 0xff.toByte() else 0x11 }
        val at = WalletCrypto.counterBlock(iv, 1)
        // 11 bytes of 0x11, then 0x11 + 1 = 0x12, then the four wrapped bytes.
        assertEquals("1111111111111111111111" + "12" + "00000000", WalletFormat.hex(at))
    }

    @Test
    fun ctr_is_its_own_inverse() {
        val plain = "a page of a passport".toByteArray(Charsets.UTF_8)
        val iv = WalletCrypto.assetIv(aid, 9)
        val c = WalletCrypto.ctrCrypt(key, iv, plain)
        assertFalse(c.contentEquals(plain))
        assertArrayEquals(plain, WalletCrypto.ctrCrypt(key, iv, c))
    }

    @Test
    fun a_wrong_key_produces_garbage_rather_than_an_error() {
        // Stated because it is the reason the header keeps a plaintext hash: CTR has no
        // integrity, so a wrong key is only caught by that hash.
        val plain = ByteArray(48) { it.toByte() }
        val iv = WalletCrypto.assetIv(aid, 1)
        val c = WalletCrypto.ctrCrypt(key, iv, plain)
        val wrong = WalletCrypto.ctrCrypt(other, iv, c)
        assertFalse(wrong.contentEquals(plain))
    }

    // --- the manifest container --------------------------------------------

    @Test
    fun the_container_layout_is_exactly_the_documented_one() {
        val plain = "{\"a\":1}".toByteArray(Charsets.UTF_8)
        val blob = WalletCrypto.encryptManifest(key, plain, 42)
        assertEquals("EWM1", blob.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(2, blob[4].toInt())
        assertEquals(0, blob[5].toInt())
        assertEquals(WalletCrypto.MANIFEST_HEADER_V2 + plain.size + WalletCrypto.GCM_TAG_LEN,
            blob.size)
        val head = WalletCrypto.readManifestHeader(blob)
        assertEquals(plain.size, head.plaintextLen)
        assertEquals(42, head.walletVersion)
        assertArrayEquals(plain, WalletCrypto.decryptManifest(key, blob))
    }

    @Test
    fun the_wallet_version_is_readable_without_the_key() {
        // The whole point of container v2: the phone computes pending work while the
        // device is locked (`docs/wallet-plan.md` 7g).
        val blob = WalletCrypto.encryptManifest(key, "x".toByteArray(), 5)
        assertEquals(5, WalletCrypto.readManifestHeader(blob).walletVersion)
    }

    @Test
    fun a_v1_container_still_reads_and_reports_no_version() {
        // Hand-built v1: magic, version 1, flags, nonce, plainLen, then ct||tag.
        val plain = "old".toByteArray(Charsets.UTF_8)
        val v2 = WalletCrypto.encryptManifest(key, plain, 3)
        val nonce = v2.copyOfRange(6, 18)
        val sealed = v2.copyOfRange(WalletCrypto.MANIFEST_HEADER_V2, v2.size)
        val v1 = ByteArray(WalletCrypto.MANIFEST_HEADER_V1 + sealed.size)
        WalletCrypto.MANIFEST_MAGIC.copyInto(v1, 0)
        v1[4] = 1
        nonce.copyInto(v1, 6)
        v1[18] = plain.size.toByte()
        sealed.copyInto(v1, WalletCrypto.MANIFEST_HEADER_V1)
        val head = WalletCrypto.readManifestHeader(v1)
        assertEquals(1, head.version)
        assertEquals(-1, head.walletVersion)
        assertArrayEquals(plain, WalletCrypto.decryptManifest(key, v1))
    }

    @Test
    fun a_wrong_key_a_flipped_ciphertext_bit_and_a_flipped_tag_bit_all_fail_hard() {
        val plain = "{\"items\":[]}".toByteArray(Charsets.UTF_8)
        val blob = WalletCrypto.encryptManifest(key, plain, 1)

        fails("wrong key") { WalletCrypto.decryptManifest(other, blob) }

        val ct = blob.copyOf()
        ct[WalletCrypto.MANIFEST_HEADER_V2] = (ct[WalletCrypto.MANIFEST_HEADER_V2].toInt() xor 1)
            .toByte()
        fails("flipped ciphertext bit") { WalletCrypto.decryptManifest(key, ct) }

        val tag = blob.copyOf()
        tag[tag.size - 1] = (tag[tag.size - 1].toInt() xor 1).toByte()
        fails("flipped tag bit") { WalletCrypto.decryptManifest(key, tag) }

        val nonce = blob.copyOf()
        nonce[6] = (nonce[6].toInt() xor 1).toByte()
        fails("flipped nonce bit") { WalletCrypto.decryptManifest(key, nonce) }
    }

    /**
     * `walletVersion` is **outside the tag**, deliberately, and this test is the proof
     * that it is a hint and not a trust anchor: flipping it does not fail
     * authentication. Nothing destructive may be gated on it.
     */
    @Test
    fun the_cleartext_wallet_version_is_not_authenticated() {
        val plain = "{\"items\":[]}".toByteArray(Charsets.UTF_8)
        val blob = WalletCrypto.encryptManifest(key, plain, 1)
        blob[22] = 99
        assertEquals(99, WalletCrypto.readManifestHeader(blob).walletVersion)
        assertArrayEquals(plain, WalletCrypto.decryptManifest(key, blob))
    }

    @Test
    fun a_truncated_or_foreign_container_is_refused() {
        fails("truncated") { WalletCrypto.readManifestHeader(ByteArray(8)) }
        val bad = WalletCrypto.encryptManifest(key, "x".toByteArray(), 1)
        bad[0] = 'X'.code.toByte()
        fails("bad magic") { WalletCrypto.readManifestHeader(bad) }
        val futureVersion = WalletCrypto.encryptManifest(key, "x".toByteArray(), 1)
        futureVersion[4] = 9
        fails("unknown container version") { WalletCrypto.readManifestHeader(futureVersion) }
    }

    // --- ids ---------------------------------------------------------------

    /**
     * The collision that was hit for real (`docs/wallet-plan.md` 7f): before the id
     * recipe carried the crypto state, a cleartext and an encrypted build of one
     * document produced the same ids, so on one card they took the same path and the
     * last write won.
     */
    @Test
    fun a_cleartext_and_an_encrypted_asset_of_one_document_cannot_share_a_path() {
        val clear = WalletFormat.assetId("x4", "item", "p001",
            WalletFormat.ASSET_PAGE_IMAGE, 0, 1, encrypted = false)
        val enc = WalletFormat.assetId("x4", "item", "p001",
            WalletFormat.ASSET_PAGE_IMAGE, 0, 1, encrypted = true)
        assertNotEquals(clear, enc)
        // Deterministic on both sides of the boundary.
        assertEquals(clear, WalletFormat.assetId("x4", "item", "p001",
            WalletFormat.ASSET_PAGE_IMAGE, 0, 1))
    }

    // --- the key vault -----------------------------------------------------

    private fun vault(kek: KeyEncryptionKey = InMemoryKeyEncryptionKey()): WalletKeyVault =
        WalletKeyVault(java.io.File(Files.createTempDirectory("keys").toFile(), "wallet.key"), kek)

    @Test
    fun the_vault_round_trips_a_key_and_never_stores_it_in_the_clear() {
        val v = vault()
        assertFalse(v.hasKey())
        assertNull(v.loadKey())
        val k = v.createKey()
        assertEquals(32, k.size)
        assertTrue(v.hasKey())
        assertArrayEquals(k, v.loadKey())
        // The key must not appear anywhere in the file that holds it.
        val blob = v.file.readBytes()
        assertFalse("the wrapped file must not contain the key",
            WalletFormat.hex(blob).contains(WalletFormat.hex(k)))
        assertEquals("EWK1", blob.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(1, blob[4].toInt())
    }

    @Test
    fun a_second_create_is_refused_rather_than_orphaning_the_tree() {
        // A new key would leave every asset already written unreadable, with no way to
        // tell the rider which tree the old key belonged to.
        val v = vault()
        v.createKey()
        fails("a second createKey") { v.createKey() }
    }

    @Test
    fun a_different_kek_cannot_unwrap_and_says_so() {
        val kek = InMemoryKeyEncryptionKey()
        val dir = Files.createTempDirectory("keys").toFile()
        val a = WalletKeyVault(java.io.File(dir, "wallet.key"), kek)
        a.createKey()
        val b = WalletKeyVault(java.io.File(dir, "wallet.key"), InMemoryKeyEncryptionKey())
        fails("wrong KEK") { b.loadKey() }
    }

    @Test
    fun a_tampered_key_file_fails_instead_of_unwrapping_into_garbage() {
        val v = vault()
        v.createKey()
        val blob = v.file.readBytes()
        blob[blob.size - 2] = (blob[blob.size - 2].toInt() xor 0xff).toByte()
        v.file.writeBytes(blob)
        fails("tampered key file") { v.loadKey() }
    }

    @Test
    fun loadOrCreate_creates_once_and_then_loads() {
        val v = vault()
        val first = v.loadOrCreateKey()
        assertArrayEquals(first, v.loadOrCreateKey())
    }

    @Test
    fun the_none_key_store_means_cleartext_and_says_so() {
        assertFalse(WalletKeyStore.None.hasKey())
        assertNull(WalletKeyStore.None.loadKey())
        assertTrue(WalletKeyStore.None.description.contains("cleartext"))
    }

    // --- helpers -----------------------------------------------------------

    private fun fails(what: String, body: () -> Unit) {
        try {
            body()
            throw AssertionError("$what must fail")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Throwable) {
            // Expected: an IllegalArgumentException, an IllegalStateException or a
            // ManifestAuthException, depending on which guard caught it.
        }
    }
}
