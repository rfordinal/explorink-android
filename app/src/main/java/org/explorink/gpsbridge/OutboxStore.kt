package org.explorink.gpsbridge

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The outbox as text: `outbox.json`, both directions, with no file in the way.
 *
 * Pure, so the whole format is checked on the laptop -- string in, [TileOutbox]
 * out, and back to a string that reads the same. That is not a convenience: a
 * second client has to reproduce this file from a document rather than from
 * Kotlin (`CLAUDE.md`, "The phone app must stay portable to iOS"), and a format
 * only a phone can exercise is a format nobody can check the document against.
 * `docs/tile-outbox-format.md` is that document and wins if the two disagree.
 *
 * The shape is the one [TileOutbox]'s own class doc states:
 *
 * ```
 * { "version": 1,
 *   "zones":    [ {zoneId,label,latE7,lonE7,sideKm,createdAtMs}, ... ],
 *   "items":    [ {zoneId,z,col,row,queuedAtMs,cdn,sizeBytes,contentId,
 *                  buildChecks,nextTryAtMs,attempts,error,terminal}, ... ],
 *   "receipts": { "13/4144/3059": {bytes,crc32,transport,atMs}, ... } }
 * ```
 *
 * Four things that doc left open, settled here and written into the format doc:
 *
 *  - **Every field is always written**, `error` as `null` when there is none. A
 *    fixed shape costs a few kB on a 202-tile city -- the whole file is about
 *    74 kB there -- and saves a second client from guessing which absences are
 *    meaningful.
 *  - **Every timestamp is Unix epoch milliseconds, wall clock.** Not the
 *    monotonic clock [FreshnessChecker] backs off on: this file exists to
 *    survive a reboot, and a monotonic reading does not.
 *  - **A field that is missing takes [TileItem]'s own default**, and an entry
 *    that cannot be read at all is skipped rather than failing the file. Losing
 *    one square of the ask is cheap; losing the whole ask is not.
 *  - **`cdn` is a [TilePlan.State] by name**, and a word this build does not
 *    know reads back as [TilePlan.State.UNKNOWN]. That costs one index read.
 *    Guessing would cost a verdict.
 *
 * What is deliberately **not** written, and why: `inFlight`, the [TileOutbox.beginSend]
 * expectation behind it, and the per-tile progress bytes. A process that died
 * mid-transfer sent no receipt, so that tile is pending again by construction --
 * and a persisted "sending" would be a state with nothing behind it, which is
 * the exact thing the receipt law exists to forbid.
 */
object OutboxJson {

    /**
     * The version this build writes and the only one it reads.
     *
     * **Versioned from the first commit**, because the wallet learned what the
     * alternative costs: its version-1 `state.json` is migrated by *dropping*
     * its states rather than translating them, since `ON_DEVICE` recorded no
     * hash and no byte count and so could not become a confirmation
     * (`docs/android-wallet.md`, and `WalletStoreTest`'s
     * `a_version_1_state_file_loses_its_states_rather_than_inventing_confirmations`).
     *
     * The law that falls out, and it holds for every future bump here:
     * **a field whose meaning changed is dropped, never guessed.** Forgetting a
     * receipt costs one transfer. Inventing one tells the rider the device holds
     * a tile it does not, and nothing later corrects that.
     */
    const val VERSION = 1

    /** What reading a file produced. */
    sealed class Load {
        /**
         * A file this build understands. [skipped] counts entries that were
         * individually unreadable and dropped -- zero for a healthy file.
         */
        class Restored(val outbox: TileOutbox, val skipped: Int = 0) : Load()

        /** A version this build does not read. Nothing in it is interpreted. */
        class UnknownVersion(val version: Int?) : Load()

        /** Truncated, empty, or not JSON at all. */
        class Damaged(val why: String) : Load()
    }

    fun write(outbox: TileOutbox): String = Json.write(
        linkedMapOf<String, Any?>(
            "version" to VERSION,
            "zones" to outbox.zones.map { zoneJson(it) },
            "items" to outbox.items.map { itemJson(it) },
            "receipts" to LinkedHashMap<String, Any?>().apply {
                for ((key, r) in outbox.receipts) put(key, receiptJson(r))
            },
        )
    )

    fun read(text: String): Load {
        val root = try {
            Json.asMap(Json.parse(text))
        } catch (t: Throwable) {
            return Load.Damaged(t.javaClass.simpleName)
        }
        val version = root["version"]?.let { runCatching { Json.asInt(it) }.getOrNull() }
        // A file with no version is not assumed to be version 1. It is either
        // damage or something that was never this file, and both deserve to be
        // kept rather than parsed hopefully.
        if (version == null) return Load.Damaged("no version")
        if (version != VERSION) return Load.UnknownVersion(version)

        var skipped = 0
        val zones = ArrayList<TileZone>()
        for (z in listOrEmpty(root["zones"])) {
            val zone = runCatching { zone(Json.asMap(z)) }.getOrNull()
            if (zone == null) skipped++ else zones.add(zone)
        }
        val items = ArrayList<TileItem>()
        for (i in listOrEmpty(root["items"])) {
            val item = runCatching { item(Json.asMap(i)) }.getOrNull()
            if (item == null) skipped++ else items.add(item)
        }
        val receipts = LinkedHashMap<String, TileReceipt>()
        val raw = runCatching { Json.asMap(root["receipts"]) }.getOrNull() ?: emptyMap()
        for ((key, v) in raw) {
            val receipt = runCatching { receipt(Json.asMap(v)) }.getOrNull()
            if (receipt == null) skipped++ else receipts[key] = receipt
        }
        return Load.Restored(TileOutbox(zones, items, receipts), skipped)
    }

    private fun listOrEmpty(v: Any?): List<Any?> =
        runCatching { Json.asList(v) }.getOrNull() ?: emptyList()

    // --- one entry each way ---------------------------------------------------

    private fun zoneJson(z: TileZone): Map<String, Any?> = linkedMapOf(
        "zoneId" to z.zoneId,
        "label" to z.label,
        "latE7" to z.latE7,
        "lonE7" to z.lonE7,
        "sideKm" to z.sideKm,
        "createdAtMs" to z.createdAtMs,
    )

    private fun zone(o: Map<String, Any?>) = TileZone(
        zoneId = Json.asString(o["zoneId"]),
        label = Json.optString(o, "label") ?: "",
        latE7 = Json.optInt(o, "latE7"),
        lonE7 = Json.optInt(o, "lonE7"),
        sideKm = Json.optInt(o, "sideKm"),
        createdAtMs = Json.optLong(o, "createdAtMs"),
    )

    private fun itemJson(i: TileItem): Map<String, Any?> = linkedMapOf(
        "zoneId" to i.zoneId,
        "z" to i.tile.z,
        "col" to i.tile.col,
        "row" to i.tile.row,
        "queuedAtMs" to i.queuedAtMs,
        "cdn" to i.cdn.name,
        "sizeBytes" to i.sizeBytes,
        "contentId" to i.contentId,
        "buildChecks" to i.buildChecks,
        "nextTryAtMs" to i.nextTryAtMs,
        "attempts" to i.attempts,
        "error" to i.error,
        "terminal" to i.terminal,
    )

    /**
     * `z`, `col` and `row` are required: an item with no tile behind it is not a
     * degraded ask, it is a record of nothing. Everything else defaults.
     */
    private fun item(o: Map<String, Any?>) = TileItem(
        zoneId = Json.optString(o, "zoneId") ?: "",
        tile = TileRef(
            z = Json.asInt(o["z"]),
            col = Json.asLong(o["col"]),
            row = Json.asLong(o["row"]),
        ),
        queuedAtMs = Json.optLong(o, "queuedAtMs"),
        cdn = state(Json.optString(o, "cdn")),
        sizeBytes = Json.optLong(o, "sizeBytes"),
        contentId = Json.optLong(o, "contentId"),
        buildChecks = Json.optInt(o, "buildChecks"),
        nextTryAtMs = Json.optLong(o, "nextTryAtMs"),
        attempts = Json.optInt(o, "attempts"),
        error = Json.optString(o, "error"),
        terminal = Json.optBool(o, "terminal"),
    )

    /**
     * A verdict word this build does not know becomes
     * [TilePlan.State.UNKNOWN] -- which costs exactly one byte-range index read
     * to re-establish. Every other reading of an unknown word would be a
     * guessed verdict, and two of the four states here mean "stop asking".
     */
    private fun state(name: String?): TilePlan.State {
        if (name == null) return TilePlan.State.UNKNOWN
        return TilePlan.State.entries.firstOrNull { it.name == name } ?: TilePlan.State.UNKNOWN
    }

    private fun receiptJson(r: TileReceipt): Map<String, Any?> = linkedMapOf(
        "bytes" to r.bytes,
        "crc32" to r.crc32,
        "transport" to r.transport,
        "atMs" to r.atMs,
    )

    /**
     * `bytes` and `crc32` are required. A receipt is the device's own word about
     * what is on its card, and one missing either number is not a weaker
     * receipt -- it is a claim with nothing behind it, which is what
     * [TileOutbox.confirm] exists to refuse.
     */
    private fun receipt(o: Map<String, Any?>) = TileReceipt(
        bytes = Json.asLong(o["bytes"]),
        crc32 = Json.asLong(o["crc32"]),
        transport = Json.optString(o, "transport") ?: "",
        atMs = Json.optLong(o, "atMs"),
    )
}

/**
 * The outbox on disk: `<filesDir>/tiles/outbox.json`, app-private.
 *
 * The wallet's convention (`<filesDir>/wallet/state.json`) and the wallet's
 * reasons -- no database, because the queue is a few hundred kB of JSON, the app
 * has one dependency and Room would buy migrations and queries nothing here
 * needs (`docs/android-wallet.md`, section 3).
 *
 * **Written atomically**: `<file>.part`, `fsync`, `rename`. A kill mid-write
 * leaves the old queue or the new one, never half of one. This matters more here
 * than for the wallet: a wallet item can be rebuilt from files the phone still
 * holds, while **a tile item exists nowhere but this file** -- it is the rider's
 * decision and nothing else records it.
 *
 * **A file this build cannot read is never destroyed and never silently treated
 * as an empty queue.** It is kept as `outbox.json.bad` at the moment something
 * would overwrite it, so it survives an `adb pull` and can be read by hand, and
 * [lastLoad] says what happened so a screen can tell the rider their queue is
 * gone rather than showing them a clean slate. Two reasons for the timing: a
 * load never writes anything, and an older build launched by accident against a
 * newer file leaves that file exactly as it found it.
 *
 * Plain `java.io`, no Android types except the log tag, so the whole store runs
 * in a laptop unit test against a temp directory.
 */
class OutboxStore(val root: File) {

    companion object {
        private const val TAG = "OutboxStore"

        const val FILE_NAME = "outbox.json"

        /** Where an unreadable file is kept. One slot: the newest damage wins. */
        const val BAD_NAME = "outbox.json.bad"

        /** The store's directory inside the app's private files. */
        fun dirIn(filesDir: File): File = File(filesDir, "tiles")

        /**
         * Temp file, flush, `fsync`, rename. Lifted from `WalletStore.writeAtomic`
         * on `feat/wallet` -- same problem, same three lines, and keeping them
         * identical is what makes the later merge of the two stores mechanical.
         */
        fun writeAtomic(target: File, bytes: ByteArray) {
            val part = File(target.parentFile, target.name + ".part")
            FileOutputStream(part).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!part.renameTo(target)) {
                // On Android and Linux rename(2) replaces. This is the belt to
                // the braces.
                target.delete()
                if (!part.renameTo(target)) throw IOException("cannot replace $target")
            }
        }
    }

    val file: File get() = File(root, FILE_NAME)
    val badFile: File get() = File(root, BAD_NAME)

    /**
     * What the last [load] found. Null before the first one.
     *
     * Exposed rather than logged and forgotten: an empty queue after a damaged
     * file looks exactly like an empty queue after a fresh install, and only
     * this tells them apart.
     */
    var lastLoad: OutboxJson.Load? = null
        private set

    /**
     * The queue on disk, or an empty one. Never throws, and never writes.
     *
     * A missing file is an ordinary first run, not damage: [lastLoad] reports it
     * as an empty [OutboxJson.Load.Restored].
     */
    fun load(): TileOutbox {
        if (!file.isFile) {
            lastLoad = OutboxJson.Load.Restored(TileOutbox())
            return TileOutbox()
        }
        val result = try {
            OutboxJson.read(file.readText(Charsets.UTF_8))
        } catch (t: Throwable) {
            // An unreadable file -- permissions, a truncated read, a device
            // that filled up mid-write on a build before this one.
            OutboxJson.Load.Damaged(t.javaClass.simpleName)
        }
        lastLoad = result
        return when (result) {
            is OutboxJson.Load.Restored -> {
                if (result.skipped > 0) {
                    Log.w(TAG, "${result.skipped} unreadable entries dropped from $file")
                }
                result.outbox
            }
            is OutboxJson.Load.UnknownVersion -> {
                Log.w(TAG, "$file is version ${result.version}, this build reads ${OutboxJson.VERSION}")
                TileOutbox()
            }
            is OutboxJson.Load.Damaged -> {
                Log.w(TAG, "$file is damaged (${result.why})")
                TileOutbox()
            }
        }
    }

    /**
     * Writes the queue, atomically.
     *
     * If the last load could not read what was there, that file is moved to
     * [badFile] first -- this is the one moment it is about to be lost, and the
     * one moment it is worth keeping.
     */
    fun save(outbox: TileOutbox) {
        root.mkdirs()
        preserveUnreadable()
        writeAtomic(file, OutboxJson.write(outbox).toByteArray(Charsets.UTF_8))
    }

    private fun preserveUnreadable() {
        val last = lastLoad
        // Nothing loaded means nothing was read to be damaged. A caller saving
        // over a file it never opened is overwriting a queue it never looked
        // at, and there is no honest way to guess whether that was intended --
        // so the file is left to the atomic write, not set aside.
        if (last is OutboxJson.Load.Restored || last == null) return
        if (!file.isFile) return
        badFile.delete()
        if (file.renameTo(badFile)) {
            Log.w(TAG, "kept the unreadable queue as $badFile")
        }
        // The rename having failed changes nothing about what happens next: the
        // atomic write below replaces the file either way. Nothing is worth
        // refusing to save the rider's new ask over.
        lastLoad = OutboxJson.Load.Restored(TileOutbox())
    }
}
