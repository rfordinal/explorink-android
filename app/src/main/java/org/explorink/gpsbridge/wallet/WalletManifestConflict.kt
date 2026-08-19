package org.explorink.gpsbridge.wallet

/**
 * "The card holds a manifest of the other kind."
 *
 * The failure this exists for, found on hardware 2026-08-19 (`docs/wallet-plan.md`
 * 7l): the phone synced a **cleartext** wallet onto a card that already held
 * `manifest.enc`. Every one of 25 files landed, every one was confirmed by the
 * device's own CRC, the run finished `failed=0` -- and the rider still saw the old
 * wallet, because the device prefers the encrypted manifest whenever one exists
 * (`treeIsEncrypted()` is an existence check). **Nothing reported an error.** That is
 * worse than a failure.
 *
 * So the rule: a sync that would be invisible has to say so **before** it spends six
 * minutes of the rider's battery, and the fix is the rider's to choose. This class
 * decides nothing about what to do -- it only says whether the two kinds disagree.
 *
 * **It never deletes the other manifest.** The other manifest is somebody's wallet:
 * on a shared card it may be the only copy of a key-encrypted tree this phone cannot
 * rebuild. Removing it to make our own sync take effect would be the app choosing
 * data loss over a question.
 *
 * Pure Kotlin, no Android, no I/O -- the probe belongs to the transport.
 */

/** Which manifest a wallet tree carries. */
enum class ManifestKind(val label: String) {
    /** No manifest at all: a fresh card, or a `wallet/` directory that is not one. */
    NONE("no wallet"),

    /** `manifest.json`. */
    CLEARTEXT("cleartext"),

    /** `manifest.enc`, the `EWM1` GCM container. */
    ENCRYPTED("encrypted"),

    /**
     * Both files are on the card. Not a state anything writes on purpose; it is what a
     * half-finished switch between the two leaves behind, and the device silently
     * picks the encrypted one.
     */
    BOTH("both"),

    /**
     * The transport cannot ask. **True for BLE**, which is write-only from the phone's
     * side: there is no frame that reads a directory or a file off the card, so a BLE
     * sync cannot know what it is writing next to. Not a conflict -- an unknown, and
     * it must not be reported as an all-clear.
     */
    UNKNOWN("unknown");

    val hasEncrypted: Boolean get() = this == ENCRYPTED || this == BOTH
    val hasCleartext: Boolean get() = this == CLEARTEXT || this == BOTH

    companion object {
        fun of(hasCleartext: Boolean, hasEncrypted: Boolean): ManifestKind = when {
            hasCleartext && hasEncrypted -> BOTH
            hasEncrypted -> ENCRYPTED
            hasCleartext -> CLEARTEXT
            else -> NONE
        }
    }
}

/**
 * A disagreement worth a rider's decision.
 *
 * [invisible] is the one that matters most: it means the sync would **complete and
 * change nothing the rider can see**, which is the hardware finding above. The other
 * direction (an encrypted phone against a cleartext card) is visible -- the device
 * would start preferring the new `manifest.enc` -- but it still strands the old
 * cleartext manifest on the card, and that is worth saying too.
 */
data class ManifestConflict(
    val local: ManifestKind,
    val card: ManifestKind,
    /** True when the device would keep reading the card's manifest, not ours. */
    val invisible: Boolean,
    val message: String,
    /** What the rider is being asked to choose. Never done automatically. */
    val remedy: String,
)

object WalletManifestConflict {

    /**
     * Null when the sync will take effect. A [ManifestConflict] when it will not, or
     * when it leaves the other kind behind.
     *
     * [card] `UNKNOWN` is never a conflict: BLE cannot read the card, and inventing a
     * conflict from an unknown would block every BLE sync. The cost of that choice is
     * real and is written down -- a BLE sync onto a mismatched card is still silent,
     * and closing it needs a device-side answer the firmware does not have today.
     */
    fun of(local: ManifestKind, card: ManifestKind): ManifestConflict? {
        if (card == ManifestKind.UNKNOWN || card == ManifestKind.NONE) return null
        if (local == ManifestKind.NONE) return null
        if (local == card) return null

        val invisible = local == ManifestKind.CLEARTEXT && card.hasEncrypted
        val message = if (invisible) {
            "the card holds an encrypted wallet (${WalletFormat.MANIFEST_ENC_NAME}) and " +
                "this phone writes a cleartext one (${WalletFormat.MANIFEST_CLEAR_NAME}). " +
                "The device prefers the encrypted manifest whenever one exists, so this " +
                "sync would land, verify, and stay invisible."
        } else {
            "the card holds a cleartext wallet (${WalletFormat.MANIFEST_CLEAR_NAME}) and " +
                "this phone writes an encrypted one (${WalletFormat.MANIFEST_ENC_NAME}). " +
                "The device will switch to the new wallet, and the old manifest stays on " +
                "the card unused."
        }
        val remedy = if (invisible) {
            "Turn on encryption for this wallet, or remove " +
                "${WalletFormat.MANIFEST_ENC_NAME} from the card yourself. This app will " +
                "not delete it."
        } else {
            "Sync anyway, or remove ${WalletFormat.MANIFEST_CLEAR_NAME} from the card " +
                "yourself. This app will not delete it."
        }
        return ManifestConflict(local, card, invisible, message, remedy)
    }
}
