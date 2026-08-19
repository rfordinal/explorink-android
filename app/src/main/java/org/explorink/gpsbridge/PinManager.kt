package org.explorink.gpsbridge

import android.util.Log

/**
 * Drives the device's `pin` console commands from the phone: list, save, delete,
 * history.
 *
 * **The device is authoritative and this holds no copy.** Decision 7 of
 * `firmware/explorink/docs/pins-plan.md`: the phone can push a pin and can read
 * back what the device has, and it reconciles nothing. So every mutation here is
 * followed by a fresh `pin list` rather than a local edit -- what the screen
 * shows is always the device's own answer, and a write that half-failed cannot
 * leave the phone showing a pin the card never recorded.
 *
 * One request at a time, queued. Two overlapping requests on this channel would
 * each be ended by the other's `OK`: that is not theory, it is the failure
 * [BridgeService.onCommandLine] already serialises the tile conversations
 * against, measured on hardware 2026-08-11.
 *
 * **Pins only work while the device is on the map screen.** The map activity is
 * the only thing that wires a pin store to its console
 * (`MapActivity.cpp:1816`, `consoleState_.setPinsSource()`); the tile sync screen
 * runs its own console with none, and answers `INFO pins=unavailable`. That is
 * reported as its own outcome, never as an empty list.
 *
 * No BLE and no Android in here except the log tag, same contract as
 * [TileFetcher] and [FreshnessChecker]: hardware behind [Transport], time behind
 * [TileFetcher.Scheduler]. Single-threaded, every callback on the caller's one
 * thread.
 */
class PinManager(
    private val transport: Transport,
    private val scheduler: TileFetcher.Scheduler,
    private val listener: Listener? = null,
) {

    companion object {
        private const val TAG = "PinManager"

        /**
         * How long the device may take to answer one `pin` command.
         *
         * The same budget [FreshnessChecker] gives `have`, for the same reason: a
         * reply is one BLE indication per line, each waiting for the peer's ATT
         * confirm, and a `pin list` can be fourteen of them behind whatever else
         * the map screen is doing on the panel.
         */
        const val REPLY_TIMEOUT_MS = 15_000L
    }

    /** The one thing pins need from the link. */
    interface Transport {
        /** Writes one ASCII line to the command characteristic. */
        fun sendCommand(line: String, done: (Boolean, String?) -> Unit)
    }

    interface Listener {
        /** The device's own answer to `pin list`, complete. Replaces whatever was shown. */
        fun onPins(pins: List<DevicePin>) {}

        /** One page of `pin log`, newest first. */
        fun onPinHistory(
            records: List<PinLogEntry>,
            offset: Int,
            total: Int?,
            nextOffset: Int?,
        ) {}

        /**
         * A save or a delete finished. [ok] false means **nothing changed on the
         * card**: the device writes the history record first and only moves the
         * active pin if that worked, so a refusal leaves both untouched.
         */
        fun onPinWrite(key: String, deleting: Boolean, ok: Boolean, reason: String?) {}

        /**
         * The device cannot answer pin commands at all -- it is not on the map
         * screen. Distinct from "no pins" on purpose.
         */
        fun onPinsUnavailable() {}

        /** A request failed for a reason worth showing: a timeout, a lost reply, a link drop. */
        fun onPinsError(reason: String) {}

        /** Queue length or phase changed, so a UI can show that something is in flight. */
        fun onPinsBusyChanged() {}
    }

    enum class Phase { IDLE, LISTING, WRITING, HISTORY }

    private sealed class Request {
        object List : Request()
        class Save(val key: String, val latE7: Int, val lonE7: Int, val utc: Long) : Request()
        class Delete(val key: String) : Request()
        class History(val offset: Int) : Request()
    }

    var phase: Phase = Phase.IDLE
        private set

    /** True while anything is in flight or waiting to be sent. */
    val busy: Boolean get() = phase != Phase.IDLE || queue.isNotEmpty()

    private val queue = ArrayDeque<Request>()
    private var current: Request? = null

    private var listReader: PinList.ListReader? = null
    private var logReader: PinList.LogReader? = null
    private var timeout: TileFetcher.Scheduler.Cancellable? = null

    /**
     * Bumped on every finish. A timeout that fires after its request already
     * ended -- and a reply line that arrives after a timeout -- carries the old
     * generation and is dropped, the same guard [FreshnessChecker] uses.
     */
    private var gen: Int = 0

    // --- what the UI asks for -------------------------------------------

    /**
     * Reads the device's pins.
     *
     * Collapsed against a listing that is already in flight or already queued: a
     * screen that refreshes on every render must not queue a command per frame,
     * and two identical listings answer the same question twice over the rider's
     * link.
     */
    fun refresh() {
        if (current is Request.List || queue.any { it is Request.List }) return
        enqueue(Request.List)
    }

    /**
     * Creates or replaces a pin. [utcSeconds] is the phone's clock, which is the
     * one field the device cannot fill for itself -- it has no RTC, and a pin
     * saved on the panel before the first position packet lands has no time at
     * all (`firmware/explorink/docs/pins.md`, "The log on the card").
     */
    fun save(key: String, latE7: Int, lonE7: Int, utcSeconds: Long) {
        enqueue(Request.Save(key, latE7, lonE7, utcSeconds))
    }

    fun delete(key: String) {
        enqueue(Request.Delete(key))
    }

    /** One page of the history, newest first. [offset] 0 is the newest page. */
    fun history(offset: Int) {
        enqueue(Request.History(offset))
    }

    private fun enqueue(request: Request) {
        queue.addLast(request)
        listener?.onPinsBusyChanged()
        pump()
    }

    // --- the link -------------------------------------------------------

    /**
     * Every line off the command channel. Lines that belong to another
     * conversation are ignored, so this can be fed unconditionally.
     */
    fun onCommandLine(line: String) {
        val request = current ?: return
        val t = line.trim()

        // `ERR <reason>` is a terminator on its own and there is no `OK` behind
        // it (`MapConsoleState::executePin`). A reader waiting for `OK` after one
        // would wait out the whole timeout and then report the wrong thing.
        if (t.startsWith("ERR ")) {
            val reason = errorText(t.removePrefix("ERR ").trim())
            when (request) {
                is Request.Save -> finishWrite(request.key, deleting = false, ok = false, reason = reason)
                is Request.Delete -> finishWrite(request.key, deleting = true, ok = false, reason = reason)
                else -> {
                    listener?.onPinsError(reason)
                    finish()
                }
            }
            return
        }

        when (request) {
            is Request.List -> feedList(t)
            is Request.History -> feedHistory(t)
            is Request.Save -> feedWrite(t, request.key, deleting = false)
            is Request.Delete -> feedWrite(t, request.key, deleting = true)
        }
    }

    /** The link dropped. Nothing queued can be answered, so nothing stays queued. */
    fun onDisconnected() {
        if (current == null && queue.isEmpty()) return
        val had = current != null
        reset()
        if (had) listener?.onPinsError("the link dropped")
        listener?.onPinsBusyChanged()
    }

    /** Drops everything, silently. For teardown, not for an error. */
    fun stop() {
        reset()
    }

    // --- one request at a time ------------------------------------------

    private fun pump() {
        if (current != null) return
        val next = queue.removeFirstOrNull() ?: return
        current = next
        listReader = null
        logReader = null

        val line = when (next) {
            is Request.List -> {
                phase = Phase.LISTING
                listReader = PinList.ListReader()
                PinList.listCommand()
            }

            is Request.History -> {
                phase = Phase.HISTORY
                logReader = PinList.LogReader()
                PinList.logCommand(next.offset)
            }

            is Request.Save -> {
                phase = Phase.WRITING
                PinList.setCommand(next.key, next.latE7, next.lonE7, next.utc)
            }

            is Request.Delete -> {
                phase = Phase.WRITING
                PinList.delCommand(next.key)
            }
        }

        listener?.onPinsBusyChanged()
        val sentGen = gen
        armTimeout()
        transport.sendCommand(line) { ok, error ->
            // A write that fails never produces a reply, so the request has to end
            // here rather than sit until the timeout: the rider is looking at a
            // spinner and the link is already known to be gone.
            if (!ok && sentGen == gen && current != null) {
                val reason = error ?: "the command could not be sent"
                when (val r = current) {
                    is Request.Save -> finishWrite(r.key, deleting = false, ok = false, reason = reason)
                    is Request.Delete -> finishWrite(r.key, deleting = true, ok = false, reason = reason)
                    else -> {
                        listener?.onPinsError(reason)
                        finish()
                    }
                }
            }
        }
    }

    private fun feedList(line: String) {
        val reader = listReader ?: return
        if (!reader.feed(line)) return
        if (!reader.complete) return

        if (reader.unavailable) {
            listener?.onPinsUnavailable()
            finish()
            return
        }
        if (reader.truncated) {
            // A short listing describes a different set of pins from the one the
            // device holds. Showing it would invite a Replace on the wrong row,
            // so it is reported and dropped -- the rider can ask again.
            listener?.onPinsError(
                "the pin list arrived incomplete (${reader.pins.size} of ${reader.total})"
            )
            finish()
            return
        }
        listener?.onPins(reader.pins)
        finish()
    }

    private fun feedHistory(line: String) {
        val reader = logReader ?: return
        if (!reader.feed(line)) return
        if (!reader.complete) return

        if (reader.unavailable) {
            listener?.onPinsUnavailable()
            finish()
            return
        }
        listener?.onPinHistory(
            records = reader.records,
            offset = reader.offset ?: 0,
            total = reader.total,
            nextOffset = reader.nextOffset,
        )
        finish()
    }

    private fun feedWrite(line: String, key: String, deleting: Boolean) {
        if (line == PinList.UNAVAILABLE_LINE) {
            // The device answers `pins=unavailable` and then `OK`, so this must
            // not fall through to the terminator below and be read as a
            // successful write.
            listener?.onPinsUnavailable()
            listener?.onPinWrite(key, deleting, ok = false, reason = "the device is not on the map screen")
            finish()
            return
        }
        // The ack names the key; the terminator is what makes the write
        // successful. A refused write answers `ERR pin_write` and no `OK`, which
        // is handled above.
        PinList.parseWriteAck(line)?.let { acked ->
            if (acked != key) Log.w(TAG, "ack for $acked while writing $key")
        }
        if (line != "OK") return
        finishWrite(key, deleting, ok = true, reason = null)
    }

    private fun finishWrite(key: String, deleting: Boolean, ok: Boolean, reason: String?) {
        finish()
        listener?.onPinWrite(key, deleting, ok, reason)
        // The device is authoritative: read back rather than editing a local
        // copy. Only after a write that landed -- a refused one changed nothing,
        // and a listing would spend the link to prove it.
        if (ok) refresh()
    }

    private fun armTimeout() {
        cancelTimeout()
        val armedGen = gen
        timeout = scheduler.postDelayed(REPLY_TIMEOUT_MS) {
            if (armedGen != gen) return@postDelayed
            val request = current ?: return@postDelayed
            val what = when (request) {
                is Request.List -> "pin list"
                is Request.History -> "pin log"
                is Request.Save -> "pin set"
                is Request.Delete -> "pin del"
            }
            when (request) {
                is Request.Save -> {
                    // A timeout is not a refusal. The device may have written the
                    // record and lost the reply, so the honest report is "unknown",
                    // and the listing that follows is what settles it.
                    finish()
                    listener?.onPinWrite(request.key, false, ok = false, reason = "no answer from the device")
                    refresh()
                }

                is Request.Delete -> {
                    finish()
                    listener?.onPinWrite(request.key, true, ok = false, reason = "no answer from the device")
                    refresh()
                }

                else -> {
                    listener?.onPinsError("$what got no answer in ${REPLY_TIMEOUT_MS / 1000} s")
                    finish()
                }
            }
        }
    }

    private fun cancelTimeout() {
        timeout?.cancel()
        timeout = null
    }

    /** Ends the current request and starts the next one, if any. */
    private fun finish() {
        cancelTimeout()
        gen++
        current = null
        listReader = null
        logReader = null
        phase = Phase.IDLE
        listener?.onPinsBusyChanged()
        pump()
    }

    private fun reset() {
        cancelTimeout()
        gen++
        current = null
        listReader = null
        logReader = null
        queue.clear()
        phase = Phase.IDLE
    }

    /** The device's wire error words, in words a rider can act on. */
    private fun errorText(code: String): String = when (code) {
        // One reason for both a missing pin and a refused write, deliberately, in
        // the firmware: the caller's next move is the same either way, and the
        // listing that follows says which of the two it was.
        "pin_write" -> "the device refused: no such pin, or the card could not be written"
        "unknown_pin" -> "the device does not know that pin type"
        "bad_number" -> "the device could not read that coordinate"
        "out_of_range" -> "that coordinate is out of range"
        "bad_arity", "unknown_command" -> "this firmware does not support that pin command"
        else -> "the device answered: $code"
    }
}
