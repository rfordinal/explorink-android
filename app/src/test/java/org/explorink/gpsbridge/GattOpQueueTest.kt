package org.explorink.gpsbridge

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The GATT queue's ugly cases, with time and the stack replaced by fakes.
 *
 * This is the code that decides what happens when the device's SD card stalls
 * mid-transfer, which is the failure that used to discard the rest of a tile
 * fetch (`docs/ble-review-2026-08.md`, "Fix first" item 4). The interesting
 * cases here are all about a callback that arrives late, or never.
 */
class GattOpQueueTest {

    /**
     * Runs delayed work when a test says the clock moved, and nothing else.
     * Also the queue's clock, so the two cannot disagree.
     */
    private class FakeScheduler : GattOpQueue.Scheduler {
        private class Task(val at: Long, val action: () -> Unit) {
            var cancelled = false
        }

        var now = 0L
            private set

        private val tasks = mutableListOf<Task>()

        override fun postDelayed(
            delayMs: Long,
            action: () -> Unit,
        ): GattOpQueue.Scheduler.Cancellable {
            val task = Task(now + delayMs, action)
            tasks.add(task)
            return object : GattOpQueue.Scheduler.Cancellable {
                override fun cancel() {
                    task.cancelled = true
                }
            }
        }

        /** Moves time forward, firing due tasks in the order they come due. */
        fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at }
                    ?: break
                tasks.remove(next)
                now = next.at
                next.action()
            }
            now = target
        }
    }

    /** Records what the queue issued, and answers however a test wants. */
    private class FakeStack {
        val issued = mutableListOf<GattOpQueue.Op>()
        var refuse = false

        fun execute(op: GattOpQueue.Op): Boolean {
            issued.add(op)
            return !refuse
        }

        fun labels(): List<String> = issued.map { it.label }
    }

    /** One op's outcome, or null while nothing has been reported. */
    private class Outcome {
        var calls = 0
        var ok: Boolean? = null
        var error: String? = null

        val done: (Boolean, String?) -> Unit = { o, e ->
            calls++
            ok = o
            error = e
        }
    }

    private fun char(): BluetoothGattCharacteristic =
        BluetoothGattCharacteristic(UUID.randomUUID(), 0, 0)

    private fun descriptor(): BluetoothGattDescriptor =
        BluetoothGattDescriptor(UUID.randomUUID(), 0)

    private class Fixture {
        val scheduler = FakeScheduler()
        val stack = FakeStack()
        val events = mutableListOf<String>()
        val linkDead = mutableListOf<String>()
        val queue = GattOpQueue(
            scheduler = scheduler,
            clock = { scheduler.now },
            execute = { stack.execute(it) },
            onEvent = { kind, message -> events.add("$kind: $message") },
            onLinkDead = { reason -> linkDead.add(reason) },
        ).also { it.open() }
    }

    // --- 1: the happy path ----------------------------------------------

    @Test
    fun secondWriteWaitsForTheFirstCallback() {
        val f = Fixture()
        val a = char()
        val b = char()
        val first = Outcome()
        val second = Outcome()

        f.queue.enqueue(GattOpQueue.Op.write("first", a, byteArrayOf(1), done = first.done))
        f.queue.enqueue(GattOpQueue.Op.write("second", b, byteArrayOf(2), done = second.done))

        assertEquals(listOf("first"), f.stack.labels())
        assertEquals(0, first.calls)

        f.queue.onWriteComplete(a, true, null)

        assertEquals(true, first.ok)
        assertEquals(listOf("first", "second"), f.stack.labels())
        assertEquals(0, second.calls)

        f.queue.onWriteComplete(b, true, null)
        assertEquals(true, second.ok)
    }

    // --- 2: a timeout does not free the stack ---------------------------

    @Test
    fun timeoutFailsTheOpAndPumpsNothing() {
        val f = Fixture()
        val a = char()
        val b = char()
        val first = Outcome()
        val second = Outcome()

        f.queue.enqueue(GattOpQueue.Op.write("first", a, byteArrayOf(1), done = first.done))
        f.queue.enqueue(GattOpQueue.Op.write("second", b, byteArrayOf(2), done = second.done))

        f.scheduler.advance(GattOpQueue.WRITE_TIMEOUT_MS)

        assertEquals(1, first.calls)
        assertEquals(false, first.ok)
        assertEquals("timeout", first.error)
        assertTrue(f.queue.hasTombstone)
        // The whole point: Android still holds its busy flag, so nothing may go
        // out behind the timed-out write.
        assertEquals(listOf("first"), f.stack.labels())
        assertEquals(0, second.calls)
    }

    // --- 3: the late callback releases the queue ------------------------

    @Test
    fun lateCallbackIsDiscardedAndTheQueueMovesOn() {
        val f = Fixture()
        val a = char()
        val b = char()
        val first = Outcome()
        val second = Outcome()

        f.queue.enqueue(GattOpQueue.Op.write("first", a, byteArrayOf(1), done = first.done))
        f.queue.enqueue(GattOpQueue.Op.write("second", b, byteArrayOf(2), done = second.done))
        f.scheduler.advance(GattOpQueue.WRITE_TIMEOUT_MS)

        // The stack finally answers the first write, successfully. Too late: the
        // caller already heard false, and it must hear nothing more.
        f.queue.onWriteComplete(a, true, null)

        assertEquals(1, first.calls)
        assertEquals(false, first.ok)
        assertFalse(f.queue.hasTombstone)
        assertEquals(listOf("first", "second"), f.stack.labels())
        assertTrue(f.events.any { it.startsWith("gatt_late") })
    }

    // --- 4: the wrong-op completion (regression) ------------------------

    @Test
    fun lateCallbackDoesNotCompleteTheNextOp() {
        val f = Fixture()
        // One shared characteristic, as every transfer frame has: this is
        // exactly what made the old code complete the wrong op.
        val transfer = char()
        val first = Outcome()
        val second = Outcome()

        f.queue.enqueue(
            GattOpQueue.Op.write(
                "chunk 0",
                transfer,
                byteArrayOf(1),
                GattOpQueue.TRANSFER_WRITE_TIMEOUT_MS,
                first.done,
            )
        )
        f.queue.enqueue(
            GattOpQueue.Op.write(
                "chunk 1",
                transfer,
                byteArrayOf(2),
                GattOpQueue.TRANSFER_WRITE_TIMEOUT_MS,
                second.done,
            )
        )
        f.scheduler.advance(GattOpQueue.TRANSFER_WRITE_TIMEOUT_MS)

        // chunk 0's real callback. It frees the slot, chunk 1 goes out -- and
        // chunk 1 is NOT completed by it, even though the characteristic is the
        // same object.
        f.queue.onWriteComplete(transfer, true, null)

        assertEquals(listOf("chunk 0", "chunk 1"), f.stack.labels())
        assertEquals(0, second.calls)

        // chunk 1's own callback is what completes it.
        f.queue.onWriteComplete(transfer, true, null)
        assertEquals(1, second.calls)
        assertEquals(true, second.ok)
    }

    // --- 5: the stack never answers -------------------------------------

    @Test
    fun hardTimeoutReportsTheLinkDeadAndDrainsTheQueue() {
        val f = Fixture()
        val a = char()
        val b = char()
        val first = Outcome()
        val second = Outcome()

        f.queue.enqueue(GattOpQueue.Op.write("first", a, byteArrayOf(1), done = first.done))
        f.queue.enqueue(GattOpQueue.Op.write("second", b, byteArrayOf(2), done = second.done))

        f.scheduler.advance(GattOpQueue.WRITE_TIMEOUT_MS)
        assertEquals(0, f.linkDead.size)

        f.scheduler.advance(GattOpQueue.STACK_DEAD_TIMEOUT_MS)

        assertEquals(1, f.linkDead.size)
        assertFalse(f.queue.hasTombstone)
        // The op that timed out heard its failure once, on the timeout.
        assertEquals(1, first.calls)
        // The one still queued hears one now instead of waiting forever.
        assertEquals(1, second.calls)
        assertEquals(false, second.ok)
        assertEquals(listOf("first"), f.stack.labels())
    }

    // --- 6: per-op timeouts ---------------------------------------------

    @Test
    fun transferFramesGetTenSecondsAndCommandsThree() {
        val transfer = char()
        val command = char()

        // The mapping BleLink.enqueueWrite uses, checked directly.
        assertEquals(
            GattOpQueue.TRANSFER_WRITE_TIMEOUT_MS,
            GattOpQueue.writeTimeoutFor(transfer, transfer),
        )
        assertEquals(
            GattOpQueue.WRITE_TIMEOUT_MS,
            GattOpQueue.writeTimeoutFor(command, transfer),
        )
        assertEquals(3000L, GattOpQueue.WRITE_TIMEOUT_MS)
        assertEquals(10_000L, GattOpQueue.TRANSFER_WRITE_TIMEOUT_MS)

        // And the queue honours what it was given: a frame survives a 3 s stall,
        // a command write does not.
        val frameFixture = Fixture()
        val frame = Outcome()
        frameFixture.queue.enqueue(
            GattOpQueue.Op.write(
                "frame",
                transfer,
                byteArrayOf(1),
                GattOpQueue.writeTimeoutFor(transfer, transfer),
                frame.done,
            )
        )
        frameFixture.scheduler.advance(GattOpQueue.WRITE_TIMEOUT_MS)
        assertEquals(0, frame.calls)
        frameFixture.scheduler.advance(
            GattOpQueue.TRANSFER_WRITE_TIMEOUT_MS - GattOpQueue.WRITE_TIMEOUT_MS
        )
        assertEquals(1, frame.calls)
        assertEquals("timeout", frame.error)

        val cmdFixture = Fixture()
        val cmd = Outcome()
        cmdFixture.queue.enqueue(
            GattOpQueue.Op.write(
                "cmd",
                command,
                byteArrayOf(1),
                GattOpQueue.writeTimeoutFor(command, transfer),
                cmd.done,
            )
        )
        cmdFixture.scheduler.advance(GattOpQueue.WRITE_TIMEOUT_MS)
        assertEquals(1, cmd.calls)
        assertEquals(false, cmd.ok)
    }

    // --- 7: the MTU exchange is an op like any other ---------------------

    @Test
    fun theMtuOpHoldsBackTheFirstCommandWrite() {
        val f = Fixture()
        val commandCccd = descriptor()
        val statusCccd = descriptor()
        val command = char()
        val mtu = Outcome()
        val ask = Outcome()

        // The real order after a connect: subscribe both indicate channels, ask
        // for a bigger MTU -- and the device fires NEED_TILES the moment the
        // command channel is subscribed, so the fetcher's `missing` ask is
        // enqueued while the MTU exchange is still outstanding.
        f.queue.enqueue(GattOpQueue.Op.descriptor("subscribe cmd", commandCccd, byteArrayOf(2, 0)) { _, _ -> })
        f.queue.enqueue(GattOpQueue.Op.descriptor("subscribe status", statusCccd, byteArrayOf(2, 0)) { _, _ -> })
        f.queue.onDescriptorComplete(commandCccd, true, null)
        f.queue.enqueue(GattOpQueue.Op.mtu("mtu 517", mtu.done))
        f.queue.onDescriptorComplete(statusCccd, true, null)
        f.queue.enqueue(GattOpQueue.Op.write("cmd", command, byteArrayOf(1), done = ask.done))

        assertEquals(listOf("subscribe cmd", "subscribe status", "mtu 517"), f.stack.labels())
        assertEquals(0, ask.calls)

        // An unsolicited onMtuChanged aside, the write goes out only once the
        // MTU op is answered.
        f.queue.onMtuComplete(true, null)
        assertEquals(true, mtu.ok)
        assertEquals(
            listOf("subscribe cmd", "subscribe status", "mtu 517", "cmd"),
            f.stack.labels(),
        )
    }

    // --- 8: an MTU that never comes back --------------------------------

    @Test
    fun anMtuTimeoutDoesNotStickTheQueue() {
        val f = Fixture()
        val command = char()
        val mtu = Outcome()
        val ask = Outcome()

        f.queue.enqueue(GattOpQueue.Op.mtu("mtu 517", mtu.done))
        f.queue.enqueue(GattOpQueue.Op.write("cmd", command, byteArrayOf(1), done = ask.done))

        f.scheduler.advance(GattOpQueue.MTU_TIMEOUT_MS)
        assertEquals(1, mtu.calls)
        assertEquals(false, mtu.ok)
        // The tombstone rule applies to the MTU op too: Android's busy flag is
        // still held, so the write waits for the stack's own answer.
        assertEquals(listOf("mtu 517"), f.stack.labels())

        f.queue.onMtuComplete(true, null)
        assertEquals(listOf("mtu 517", "cmd"), f.stack.labels())
        // And the late answer is not a second outcome for the MTU op.
        assertEquals(1, mtu.calls)
        // A failed MTU is slow, not fatal: the write is still live and the link
        // was not declared dead.
        assertEquals(0, ask.calls)
        f.queue.onWriteComplete(command, true, null)
        assertEquals(true, ask.ok)
        assertEquals(0, f.linkDead.size)
    }

    @Test
    fun anUnsolicitedMtuChangeDoesNotCompleteAWrite() {
        val f = Fixture()
        val command = char()
        val ask = Outcome()

        f.queue.enqueue(GattOpQueue.Op.write("cmd", command, byteArrayOf(1), done = ask.done))
        // The peer can start an MTU exchange itself. Taking that as the answer to
        // a pending write would free the slot while the stack is still busy.
        f.queue.onMtuComplete(true, null)
        assertEquals(0, ask.calls)

        // Same while the write is a tombstone: the slot stays held.
        f.scheduler.advance(GattOpQueue.WRITE_TIMEOUT_MS)
        f.queue.onMtuComplete(true, null)
        assertTrue(f.queue.hasTombstone)

        f.queue.onWriteComplete(command, false, "gatt status 133")
        assertFalse(f.queue.hasTombstone)
    }

    // --- the rest: contracts the queue owes its caller ------------------

    @Test
    fun descriptorWriteIsCompletedByItsOwnDescriptor() {
        val f = Fixture()
        val cccd = descriptor()
        val other = descriptor()
        val subscribe = Outcome()

        f.queue.enqueue(
            GattOpQueue.Op.descriptor("subscribe", cccd, byteArrayOf(2, 0), subscribe.done)
        )
        f.queue.onDescriptorComplete(other, true, null)
        assertEquals(0, subscribe.calls)

        f.queue.onDescriptorComplete(cccd, true, null)
        assertEquals(true, subscribe.ok)
    }

    @Test
    fun aRefusedOpFailsAtOnceAndTheNextGoesOut() {
        val f = Fixture()
        val a = char()
        val b = char()
        val first = Outcome()
        val second = Outcome()

        f.stack.refuse = true
        f.queue.enqueue(GattOpQueue.Op.write("first", a, byteArrayOf(1), done = first.done))
        assertEquals(false, first.ok)
        assertTrue(first.error!!.contains("refused"))

        f.stack.refuse = false
        f.queue.enqueue(GattOpQueue.Op.write("second", b, byteArrayOf(2), done = second.done))
        assertEquals(listOf("first", "second"), f.stack.labels())
        f.queue.onWriteComplete(b, true, null)
        assertEquals(true, second.ok)
    }

    @Test
    fun failAllClosesTheQueueSoLateEnqueuesAreRefusedNotIssued() {
        val f = Fixture()
        val a = char()
        val b = char()
        val inFlight = Outcome()
        val queued = Outcome()
        val afterwards = Outcome()

        f.queue.enqueue(GattOpQueue.Op.write("in flight", a, byteArrayOf(1), done = inFlight.done))
        f.queue.enqueue(GattOpQueue.Op.write("queued", b, byteArrayOf(2), done = queued.done))

        f.queue.failAll("torn down")

        assertEquals(false, inFlight.ok)
        assertEquals("torn down", inFlight.error)
        assertEquals(false, queued.ok)

        // What the fetcher does from a failure callback: abort, then skip. On a
        // torn-down link both must be refused, not written onto a dying gatt.
        f.queue.enqueue(GattOpQueue.Op.write("abort", a, byteArrayOf(3), done = afterwards.done))
        assertEquals(false, afterwards.ok)
        assertEquals("not connected", afterwards.error)
        assertEquals(listOf("in flight"), f.stack.labels())

        // And no timer survives the teardown: 30 s later nothing fires.
        f.scheduler.advance(60_000)
        assertEquals(0, f.linkDead.size)
        assertEquals(1, inFlight.calls)
    }

    @Test
    fun failAllDoesNotCallATombstoneDoneTwice() {
        val f = Fixture()
        val a = char()
        val timedOut = Outcome()

        f.queue.enqueue(GattOpQueue.Op.write("first", a, byteArrayOf(1), done = timedOut.done))
        f.scheduler.advance(GattOpQueue.WRITE_TIMEOUT_MS)
        assertEquals(1, timedOut.calls)

        f.queue.failAll("Bluetooth off")
        assertEquals(1, timedOut.calls)
    }

    @Test
    fun hasPendingForCoversQueuedAndInFlightAndTombstoned() {
        val f = Fixture()
        val position = char()
        val other = char()
        val first = Outcome()

        assertFalse(f.queue.hasPendingFor(position))
        f.queue.enqueue(GattOpQueue.Op.write("position", position, byteArrayOf(1), done = first.done))
        assertTrue(f.queue.hasPendingFor(position))
        assertFalse(f.queue.hasPendingFor(other))

        f.scheduler.advance(GattOpQueue.WRITE_TIMEOUT_MS)
        // A tombstone still owns the slot, so the channel is still busy.
        assertTrue(f.queue.hasPendingFor(position))

        f.queue.onWriteComplete(position, true, null)
        assertFalse(f.queue.hasPendingFor(position))
    }
}
