package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pin conversation, end to end, with no BLE and no clock.
 *
 * Two properties carry the whole class and both are tested by their failure
 * mode: **one command at a time** (two open conversations on this channel end
 * each other on the shared `OK`, measured on hardware 2026-08-11), and **nothing
 * is believed that the device did not say** -- a refusal, a timeout or a short
 * listing must never leave a pin on the screen that is not on the card.
 */
class PinManagerTest {

    @Test
    fun `a refresh asks once and hands back what the device said`() {
        val h = Harness()
        h.pins.refresh()
        assertEquals(listOf("pin list"), h.transport.commands)

        h.reply("INFO pins_total=1", "INFO pin_camp=48.4372000,17.0186000,0,1", "OK")
        assertEquals(1, h.listener.pins.size)
        assertEquals("camp", h.listener.pins[0].key)
        assertFalse(h.pins.busy)
    }

    @Test
    fun `a save carries the phone's clock and is read back from the device`() {
        val h = Harness()
        h.pins.save("camp", 484372000, 170186000, 1755400000L)
        assertEquals(listOf("pin set camp 48.4372000 17.0186000 1755400000"), h.transport.commands)

        h.reply("INFO pin_set=camp", "OK")
        assertEquals(listOf("camp" to true), h.listener.writes)
        // The device is authoritative: a write is followed by a listing, never by a
        // local edit of what the screen shows.
        assertEquals(listOf("pin set camp 48.4372000 17.0186000 1755400000", "pin list"), h.transport.commands)
    }

    @Test
    fun `a refused write reports the refusal and does not re-read`() {
        val h = Harness()
        h.pins.delete("camp")
        // `ERR` is the terminator and there is no `OK` behind it. A reader waiting
        // for one would sit out the whole timeout and then report a timeout, which
        // is a different thing from a device that answered.
        h.pins.onCommandLine("ERR pin_write")

        assertEquals(listOf("camp" to false), h.listener.writes)
        assertTrue(h.listener.writeReasons[0]!!.contains("no such pin"))
        assertEquals(listOf("pin del camp"), h.transport.commands)
        assertFalse(h.pins.busy)
    }

    @Test
    fun `a write the link would not take fails at once, not at the timeout`() {
        val h = Harness()
        h.transport.failNext = "not connected"
        h.pins.save("base", 480000000, 170000000, 0L)

        assertEquals(listOf("base" to false), h.listener.writes)
        assertEquals("not connected", h.listener.writeReasons[0])
        assertFalse(h.pins.busy)
    }

    @Test
    fun `two requests never share the channel`() {
        val h = Harness()
        h.pins.refresh()
        h.pins.delete("camp")
        // The second command is not on the wire yet: its reply would be terminated
        // by the first listing's `OK`.
        assertEquals(listOf("pin list"), h.transport.commands)

        h.reply("INFO pins_total=0", "OK")
        assertEquals(listOf("pin list", "pin del camp"), h.transport.commands)
        assertTrue(h.pins.busy)
    }

    @Test
    fun `a listing already queued is not queued twice`() {
        val h = Harness()
        h.pins.refresh()
        h.pins.refresh()
        h.pins.refresh()
        h.reply("INFO pins_total=0", "OK")
        assertEquals(listOf("pin list"), h.transport.commands)
    }

    @Test
    fun `unavailable is reported as itself, never as an empty list`() {
        val h = Harness()
        h.pins.refresh()
        h.reply("INFO pins=unavailable", "OK")

        assertTrue(h.listener.unavailable)
        // Nothing was handed over: the device could not answer, and overwriting the
        // screen with an empty list would tell the rider their pins are gone.
        assertNull(h.listener.lastPins)
    }

    @Test
    fun `a write against a device with no pin store is not a successful write`() {
        val h = Harness()
        h.pins.save("camp", 484372000, 170186000, 0L)
        // `pins=unavailable` is followed by `OK`, so this is exactly the case where
        // a reader that only watches for the terminator reports a save that never
        // happened.
        h.reply("INFO pins=unavailable", "OK")

        assertTrue(h.listener.unavailable)
        assertEquals(listOf("camp" to false), h.listener.writes)
        assertEquals(listOf("pin set camp 48.4372000 17.0186000"), h.transport.commands)
    }

    @Test
    fun `a listing that lost lines is refused, not shown`() {
        val h = Harness()
        h.pins.refresh()
        h.reply("INFO pins_total=3", "INFO pin_camp=48.4372000,17.0186000,0,1", "OK")

        assertNull(h.listener.lastPins)
        assertTrue(h.listener.errors.single().contains("incomplete"))
    }

    @Test
    fun `a silent device times out, and a save that timed out is re-read`() {
        val h = Harness()
        h.pins.save("camp", 484372000, 170186000, 0L)
        h.scheduler.fire()

        // Not reported as a refusal: the device may have written the record and lost
        // the reply, and the listing that follows is what settles it.
        assertEquals(listOf("camp" to false), h.listener.writes)
        assertEquals(listOf("pin set camp 48.4372000 17.0186000", "pin list"), h.transport.commands)
    }

    @Test
    fun `a reply that arrives after its timeout is ignored`() {
        val h = Harness()
        h.pins.refresh()
        h.scheduler.fire()
        h.listener.errors.clear()

        // The late `OK` belongs to a conversation that is over. Feeding it into the
        // listing that replaced it would complete that one with one tile of the
        // wrong reply in it.
        h.reply("INFO pins_total=1", "INFO pin_camp=48.4372000,17.0186000,0,1", "OK")
        assertNull(h.listener.lastPins)
    }

    @Test
    fun `a dropped link clears the queue`() {
        val h = Harness()
        h.pins.refresh()
        h.pins.delete("camp")
        h.pins.onDisconnected()

        assertFalse(h.pins.busy)
        assertTrue(h.listener.errors.single().contains("link dropped"))
        // Nothing is retried on reconnect: the device may not even be on the map
        // screen then, and the pins screen asks again for itself.
        assertEquals(listOf("pin list"), h.transport.commands)
    }

    @Test
    fun `a history page is handed over with its paging`() {
        val h = Harness()
        h.pins.history(0)
        assertEquals(listOf("pin log"), h.transport.commands)

        h.reply(
            "INFO pinlog_total=10",
            "INFO pinlog_offset=0",
            "INFO pinlog_10=rep,camp,48.5000000,17.1000000,0",
            "INFO pinlog_next=8",
            "OK",
        )
        assertEquals(1, h.listener.history.size)
        assertEquals(8, h.listener.historyNext)
        assertEquals(10, h.listener.historyTotal)
    }

    @Test
    fun `lines from another conversation are not claimed`() {
        val h = Harness()
        h.pins.refresh()
        h.pins.onCommandLine("NEED_TILES 4 fmt 3")
        h.pins.onCommandLine("INFO missing_total=4")
        assertNull(h.listener.lastPins)
        h.reply("INFO pins_total=0", "OK")
        assertEquals(0, h.listener.pins.size)
    }

    // --- harness --------------------------------------------------------

    private class Harness {
        val transport = FakeTransport()
        val scheduler = FakeScheduler()
        val listener = Recorder()
        val pins = PinManager(transport, scheduler, listener)

        fun reply(vararg lines: String) = lines.forEach { pins.onCommandLine(it) }
    }

    private class FakeTransport : PinManager.Transport {
        val commands = mutableListOf<String>()

        /** Fails the next write with this reason, the way a dead link does. */
        var failNext: String? = null

        override fun sendCommand(line: String, done: (Boolean, String?) -> Unit) {
            commands.add(line)
            val fail = failNext
            failNext = null
            if (fail != null) done(false, fail) else done(true, null)
        }
    }

    private class FakeScheduler : TileFetcher.Scheduler {
        private var pending: (() -> Unit)? = null

        override fun postDelayed(delayMs: Long, action: () -> Unit): TileFetcher.Scheduler.Cancellable {
            pending = action
            return object : TileFetcher.Scheduler.Cancellable {
                override fun cancel() {
                    if (pending === action) pending = null
                }
            }
        }

        fun fire() {
            val a = pending ?: error("no timeout armed")
            pending = null
            a()
        }
    }

    private class Recorder : PinManager.Listener {
        var lastPins: List<DevicePin>? = null
        val pins: List<DevicePin> get() = lastPins ?: emptyList()

        /** key to ok, in order. */
        val writes = mutableListOf<Pair<String, Boolean>>()
        val writeReasons = mutableListOf<String?>()
        val errors = mutableListOf<String>()
        var unavailable = false
        var history: List<PinLogEntry> = emptyList()
        var historyNext: Int? = null
        var historyTotal: Int? = null

        override fun onPins(pins: List<DevicePin>) {
            lastPins = pins
        }

        override fun onPinHistory(
            records: List<PinLogEntry>,
            offset: Int,
            total: Int?,
            nextOffset: Int?,
        ) {
            history = records
            historyTotal = total
            historyNext = nextOffset
        }

        override fun onPinWrite(key: String, deleting: Boolean, ok: Boolean, reason: String?) {
            writes.add(key to ok)
            writeReasons.add(reason)
        }

        override fun onPinsUnavailable() {
            unavailable = true
        }

        override fun onPinsError(reason: String) {
            errors.add(reason)
        }
    }
}
