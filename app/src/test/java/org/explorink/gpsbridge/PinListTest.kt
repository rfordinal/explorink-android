package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `pin` wire shapes, both directions, with no BLE.
 *
 * Every literal reply here is the firmware's own format string
 * (`MapCommandConsole.cpp`, `writePinList` / `writePinLog`), so a change on
 * either side breaks a test rather than a rider's pin list.
 */
class PinListTest {

    // --- numbers --------------------------------------------------------

    @Test
    fun `coordinates survive the round trip through the console format`() {
        assertEquals("48.4372000", PinList.formatE7(484372000))
        assertEquals("17.0186000", PinList.formatE7(170186000))
        assertEquals("-0.1275000", PinList.formatE7(-1275000))
        assertEquals("0.0000000", PinList.formatE7(0))
        for (v in listOf(484372000, -1275000, 0, 900000000, -1800000000)) {
            assertEquals(v, PinList.parseDegreesE7(PinList.formatE7(v)))
        }
    }

    @Test
    fun `a fraction longer than seven digits is truncated, not rounded`() {
        // What the device does (`parseDegrees`: digits past the seventh are read
        // and dropped). Rounding here would send a different coordinate than the
        // one the same text produces on the serial console.
        assertEquals(484372009, PinList.parseDegreesE7("48.43720099"))
    }

    @Test
    fun `text the device would refuse is refused here`() {
        for (bad in listOf("", "-", ".", "1.2.3", "4e5", "12x", "48,4372", " 48.4")) {
            assertNull(bad, PinList.parseDegreesE7(bad))
        }
    }

    // --- commands -------------------------------------------------------

    @Test
    fun `set carries the phone's clock, and omits it when there is none`() {
        assertEquals(
            "pin set camp 48.4372000 17.0186000 1755400000",
            PinList.setCommand("camp", 484372000, 170186000, 1755400000L),
        )
        // 0 is the device's own "no clock" value, and omitting the field means the
        // same thing to its parser.
        assertEquals(
            "pin set camp 48.4372000 17.0186000",
            PinList.setCommand("camp", 484372000, 170186000, 0L),
        )
    }

    @Test
    fun `log paging asks the way the device pages`() {
        assertEquals("pin log", PinList.logCommand(0))
        assertEquals("pin log 8", PinList.logCommand(8))
    }

    // --- pin list -------------------------------------------------------

    @Test
    fun `a listing reads back every field the device stated`() {
        val r = PinList.ListReader()
        r.feed("INFO pins_total=2")
        r.feed("INFO pin_camp=48.4372000,17.0186000,1755400000,3")
        r.feed("INFO pin_parking=-0.1275000,51.5072000,0,1")
        r.feed("OK")

        assertTrue(r.complete)
        assertFalse(r.truncated)
        assertEquals(2, r.pins.size)
        assertEquals(DevicePin("camp", 484372000, 170186000, 1755400000L, 3L), r.pins[0])
        // utc 0 is kept as 0: the device had no clock, and the app must not
        // substitute its own -- the pin was saved at an unknown time.
        assertEquals(0L, r.pins[1].utc)
    }

    @Test
    fun `a trailing field-name comment is not part of the value`() {
        // The device's docs print one; a later build may put one on the wire.
        val r = PinList.ListReader()
        r.feed("INFO pins_total=1")
        r.feed("INFO pin_camp=48.4372000,17.0186000,0,1        lat,lon,utc,id")
        r.feed("OK")
        assertEquals(1, r.pins.size)
        assertEquals(484372000, r.pins[0].latE7)
    }

    @Test
    fun `a listing that lost a line is truncated, not shown`() {
        val r = PinList.ListReader()
        r.feed("INFO pins_total=3")
        r.feed("INFO pin_camp=48.4372000,17.0186000,0,1")
        r.feed("OK")
        assertTrue(r.truncated)
    }

    @Test
    fun `unavailable is not an empty list`() {
        val r = PinList.ListReader()
        r.feed("INFO pins=unavailable")
        r.feed("OK")
        assertTrue(r.unavailable)
        assertTrue(r.pins.isEmpty())
        // The distinction is the whole point: "cannot answer" must never read as
        // "the rider has saved nothing".
        assertNull(r.total)
    }

    @Test
    fun `a key this build does not know still lists, and keeps its raw key`() {
        val r = PinList.ListReader()
        r.feed("INFO pins_total=1")
        r.feed("INFO pin_bivvy=48.4372000,17.0186000,0,7")
        r.feed("OK")
        assertEquals("bivvy", r.pins[0].key)
        assertEquals("bivvy", r.pins[0].label)
        assertFalse(PinKinds.isKnown("bivvy"))
    }

    @Test
    fun `another conversation's lines are not claimed`() {
        val r = PinList.ListReader()
        assertFalse(r.feed("INFO missing_total=4"))
        assertFalse(r.feed("NEED_TILES 4 fmt 3"))
        assertTrue(r.feed("INFO pins_total=0"))
    }

    // --- pin log --------------------------------------------------------

    @Test
    fun `a history page reads its records, its total and where the next page starts`() {
        val r = PinList.LogReader()
        r.feed("INFO pinlog_total=10")
        r.feed("INFO pinlog_offset=0")
        r.feed("INFO pinlog_10=rep,camp,48.5000000,17.1000000,1755400000")
        r.feed("INFO pinlog_9=del,camp,,,0")
        r.feed("INFO pinlog_next=8")
        r.feed("OK")

        assertTrue(r.complete)
        assertEquals(10, r.total)
        assertEquals(0, r.offset)
        assertEquals(8, r.nextOffset)
        assertFalse(r.done)
        assertEquals(2, r.records.size)
        assertEquals("rep", r.records[0].op)
        // A `del` carries no position and prints two empty fields. Empty is the
        // record saying so, not a parse failure.
        assertNull(r.records[1].latE7)
        assertNull(r.records[1].lonE7)
    }

    @Test
    fun `the last page says done and offers no next offset`() {
        val r = PinList.LogReader()
        r.feed("INFO pinlog_total=2")
        r.feed("INFO pinlog_offset=0")
        r.feed("INFO pinlog_2=add,base,48.0000000,17.0000000,0")
        r.feed("INFO pinlog_next=done")
        r.feed("OK")
        assertTrue(r.done)
        assertNull(r.nextOffset)
    }

    // --- write acks -----------------------------------------------------

    @Test
    fun `a write ack names the key it acted on`() {
        assertEquals("camp", PinList.parseWriteAck("INFO pin_set=camp"))
        assertEquals("camp", PinList.parseWriteAck("INFO pin_del=camp"))
        assertNull(PinList.parseWriteAck("OK"))
        assertNull(PinList.parseWriteAck("INFO pins_total=1"))
    }
}
