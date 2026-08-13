package org.explorink.gpsbridge

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor

/**
 * The one-at-a-time GATT operation queue, with no Android BLE calls of its own.
 *
 * Android runs exactly one GATT operation per connection: `BluetoothGatt` takes
 * an internal busy flag when an operation is issued and releases it when **that
 * operation's own callback** arrives. A second operation issued while the flag
 * is held is refused on the spot (`writeCharacteristic` returns false /
 * `ERROR_GATT_WRITE_REQUEST_BUSY`). So the queue's job is not politeness, it is
 * the only way the stack works at all.
 *
 * Everything that touches BLE is behind [execute]; everything that touches time
 * is behind [Scheduler] and [clock]. That is what makes the ugly cases -- a
 * timeout, a callback that arrives after it, a stack that never answers --
 * testable on the laptop (`GattOpQueueTest`).
 *
 * Single-threaded by contract: every method and every callback runs on the
 * caller's one thread (the main looper in the app, the test thread in tests).
 *
 * ## A timeout does not free the stack -- the tombstone rule
 *
 * The old code called the op's `done(false)` on timeout and immediately pumped
 * the next one. That is wrong, and it cost a whole tile fetch per SD stall:
 * Android's busy flag is still held by the timed-out operation, so every write
 * pumped behind it is refused, and the fetcher turns each refusal into a `skip`
 * that the device remembers as a refused tile until it reboots
 * (`docs/ble-map-transfer-protocol.md`, "skip").
 *
 * So a timeout does three things and no more: mark the in-flight op
 * [Op.timedOut], fire its `done(false, "timeout")` so the caller is not left
 * hanging, and **leave it in the in-flight slot as a tombstone**. Nothing is
 * pumped while the tombstone sits there, because the stack is not free.
 *
 * The tombstone is cleared by the late callback -- whichever write or descriptor
 * callback arrives next is, by construction, the timed-out one's (there is never
 * a second op in flight). Its result is discarded: the caller already heard
 * `false`, and a `true` arriving now would be a completion for an op the fetcher
 * has already given up on. Then the queue pumps again.
 *
 * If no callback arrives at all within [STACK_DEAD_TIMEOUT_MS] more -- 30 s
 * total, the ATT transaction timeout -- the link is gone, not slow, and
 * [onLinkDead] says so upward. Pumping there would only produce refusals.
 *
 * ## Completion matching
 *
 * A callback completes the in-flight op only if the slot is non-null and the
 * kind and the identity match. Identity matters because all transfer frames
 * share one characteristic: matching on the characteristic alone (what the old
 * code did) let a late callback complete the *next* op, which had not actually
 * been written yet.
 */
class GattOpQueue(
    private val scheduler: Scheduler,
    private val clock: () -> Long,
    /**
     * Issues the op against the real stack. Returns false when the stack would
     * not even take it -- the queue then completes the op with a failure. It
     * must not call back into the queue.
     */
    private val execute: (Op) -> Boolean,
    /** Diagnostics for the ride log: kind, message. */
    private val onEvent: (String, String?) -> Unit = { _, _ -> },
    /**
     * The stack stopped answering entirely. The link has to be treated exactly
     * like a disconnect: teardown, then reconnect.
     */
    private val onLinkDead: (String) -> Unit = {},
) {

    companion object {
        /** A normal operation that gets no callback in this long counts as failed. */
        const val WRITE_TIMEOUT_MS = 3000L

        /**
         * Same for a transfer frame, which is a different animal: `...0004` is a
         * plain WRITE, so the ATT response is sent only once the device has the
         * bytes on its SD card (`docs/ble-map-transfer-protocol.md`, "There is no
         * chunk ack"). The ack is SD-bound by design, and a card that stalls for
         * three seconds is slow, not broken.
         */
        const val TRANSFER_WRITE_TIMEOUT_MS = 10_000L

        /**
         * Extra grace after a timeout before the link is called dead. Sums with
         * [WRITE_TIMEOUT_MS] to 30 s, the ATT transaction timeout: past that the
         * stack itself has given up on the operation, so no callback is coming.
         */
        const val STACK_DEAD_TIMEOUT_MS = 27_000L

        /**
         * The per-op timeout for a characteristic write: transfer frames get the
         * SD-bound budget, everything else the normal one.
         */
        fun writeTimeoutFor(
            ch: BluetoothGattCharacteristic?,
            transferChar: BluetoothGattCharacteristic?,
        ): Long =
            if (ch != null && ch === transferChar) TRANSFER_WRITE_TIMEOUT_MS else WRITE_TIMEOUT_MS
    }

    /** Delayed work, injectable so tests do not wait for real seconds. */
    interface Scheduler {
        fun postDelayed(delayMs: Long, action: () -> Unit): Cancellable

        interface Cancellable {
            fun cancel()
        }
    }

    /** What the stack reports the operation on. One callback per kind. */
    enum class Kind {
        /** `onCharacteristicWrite`. */
        WRITE,

        /** `onDescriptorWrite` -- a CCCD write, i.e. a subscription. */
        DESCRIPTOR,
    }

    /**
     * One queued GATT operation. Payload and target are exactly what the old
     * in-`BleLink` version carried; [kind] and [timeoutMs] are what the queue
     * needs to match a callback and to time the op out.
     */
    class Op(
        val label: String,
        val kind: Kind,
        val bytes: ByteArray,
        val characteristic: BluetoothGattCharacteristic?,
        val descriptor: BluetoothGattDescriptor?,
        val timeoutMs: Long,
        val done: (Boolean, String?) -> Unit,
    ) {
        /** Set once the op timed out; the slot then holds it as a tombstone. */
        internal var timedOut = false

        /** [GattOpQueue.clock] reading when the op was issued, for the log lines. */
        internal var startedAt = 0L

        companion object {
            /** A characteristic write. [timeoutMs] via [writeTimeoutFor]. */
            fun write(
                label: String,
                ch: BluetoothGattCharacteristic,
                bytes: ByteArray,
                timeoutMs: Long = WRITE_TIMEOUT_MS,
                done: (Boolean, String?) -> Unit,
            ): Op = Op(label, Kind.WRITE, bytes, ch, null, timeoutMs, done)

            /** A descriptor (CCCD) write. */
            fun descriptor(
                label: String,
                d: BluetoothGattDescriptor,
                bytes: ByteArray,
                done: (Boolean, String?) -> Unit,
            ): Op = Op(label, Kind.DESCRIPTOR, bytes, null, d, WRITE_TIMEOUT_MS, done)
        }
    }

    private val ops = ArrayDeque<Op>()
    private var inFlight: Op? = null
    private var opTimer: Scheduler.Cancellable? = null
    private var deadTimer: Scheduler.Cancellable? = null

    /**
     * False until [open], and false again from [failAll] onwards.
     *
     * The queue refuses ops while it is closed instead of executing them. That
     * closes a hole the state check in the caller cannot: [failAll] runs
     * arbitrary caller code in the done-callbacks (the fetcher aborts and skips,
     * both of which enqueue), and on the teardown path the caller's own
     * "connected" state may not be cleared yet. An op accepted there would be
     * written onto a gatt that is closed a moment later, get no callback, and
     * cost a timeout plus a spurious link-dead 30 s later.
     */
    private var linkUp = false

    /** The link is up and the stack will take operations. */
    fun open() {
        linkUp = true
    }

    /** True while an op timed out and the stack still owes its callback. */
    val hasTombstone: Boolean
        get() = inFlight?.timedOut == true

    fun enqueue(op: Op) {
        if (!linkUp) {
            op.done(false, "not connected")
            return
        }
        ops.addLast(op)
        pump()
    }

    /**
     * True if an op for [ch] is queued or in flight. What the position channel
     * needs: a fix waiting behind another fix is stale by the time it goes out.
     */
    fun hasPendingFor(ch: BluetoothGattCharacteristic): Boolean =
        inFlight?.characteristic === ch || ops.any { it.characteristic === ch }

    // --- completions from the GATT callbacks ----------------------------

    fun onWriteComplete(ch: BluetoothGattCharacteristic, ok: Boolean, error: String?) {
        deliver(Kind.WRITE, ch, null, ok, error)
    }

    fun onDescriptorComplete(d: BluetoothGattDescriptor, ok: Boolean, error: String?) {
        deliver(Kind.DESCRIPTOR, null, d, ok, error)
    }

    private fun deliver(
        kind: Kind,
        ch: BluetoothGattCharacteristic?,
        d: BluetoothGattDescriptor?,
        ok: Boolean,
        error: String?,
    ) {
        val op = inFlight ?: return  // nothing outstanding: a stray callback.
        if (op.timedOut) {
            // The late callback of the timed-out op: whichever write or
            // descriptor callback arrives next is it, because there is never a
            // second op in flight. Identity is deliberately not checked -- all
            // transfer frames share one characteristic, so it proves nothing
            // here.
            onEvent(
                "gatt_late",
                "${op.label} answered ${clock() - op.startedAt} ms late, result dropped",
            )
            clearSlot()
            pump()
            return
        }
        val matches = when (kind) {
            Kind.WRITE -> op.kind == Kind.WRITE && op.characteristic === ch
            Kind.DESCRIPTOR -> op.kind == Kind.DESCRIPTOR && op.descriptor === d
        }
        if (!matches) return
        complete(ok, error)
    }

    // --- the queue itself ------------------------------------------------

    private fun pump() {
        // Non-null covers both a live op and a tombstone: in either case the
        // stack is busy and the next op would be refused.
        if (inFlight != null) return
        val next = ops.removeFirstOrNull() ?: return
        inFlight = next
        next.startedAt = clock()
        opTimer = scheduler.postDelayed(next.timeoutMs) { onTimeout(next) }
        if (!execute(next)) complete(false, "${next.label} refused by the stack")
    }

    private fun onTimeout(op: Op) {
        if (inFlight !== op || op.timedOut) return
        opTimer = null
        op.timedOut = true
        onEvent("gatt_timeout", "${op.label} got no callback in ${op.timeoutMs} ms")
        // Armed before the done-callback runs, so caller code cannot delay the
        // one timer that notices a dead stack.
        deadTimer = scheduler.postDelayed(STACK_DEAD_TIMEOUT_MS) { onStackDead(op) }
        op.done(false, "timeout")
        // Deliberately no pump. See the tombstone rule in the class doc.
    }

    private fun onStackDead(op: Op) {
        if (inFlight !== op) return
        deadTimer = null
        clearSlot()
        val reason = "no GATT callback for ${op.label} in " +
            "${op.timeoutMs + STACK_DEAD_TIMEOUT_MS} ms"
        onEvent("gatt_stack_dead", reason)
        onLinkDead(reason)
        // The listener normally tears the link down, and that fails everything
        // queued. If it did not, nothing else will: drain here rather than leave
        // callers waiting on a link that is gone.
        if (ops.isNotEmpty()) failAll("link dead")
    }

    private fun complete(ok: Boolean, error: String?) {
        val op = inFlight ?: return
        clearSlot()
        op.done(ok, error)
        // After the callback, not before: a callback that queues the next chunk
        // (the transfer path does exactly that) must find the slot free.
        pump()
    }

    private fun clearSlot() {
        inFlight = null
        opTimer?.cancel()
        opTimer = null
        deadTimer?.cancel()
        deadTimer = null
    }

    /**
     * Fails everything outstanding and closes the queue. Called from the
     * caller's teardown, so a caller waiting on a write always hears an outcome
     * instead of hanging on a dead link.
     *
     * A tombstone's `done` already fired on the timeout and is not called again.
     */
    fun failAll(reason: String) {
        linkUp = false
        val slot = inFlight
        clearSlot()
        val queued = ops.toList()
        ops.clear()
        if (slot != null && !slot.timedOut) slot.done(false, reason)
        queued.forEach { it.done(false, reason) }
    }
}
