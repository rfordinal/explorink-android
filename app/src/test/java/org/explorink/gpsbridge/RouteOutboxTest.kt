package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RouteOutboxTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun outbox() = RouteOutbox(tmp.newFolder("routes"))

    @Test
    fun aQueuedRouteComesBackWithItsPath() {
        val box = outbox()
        assertTrue(box.add("zahorie-20261027.tir", byteArrayOf(1, 2, 3)))
        val entry = box.next()!!
        assertEquals("zahorie-20261027.tir", entry.fileName)
        // Relative to /trailink, and under the directory the device's picker
        // lists (MapRouteStore.h, kTripsDir).
        assertEquals("trips/zahorie-20261027.tir", entry.relPath)
        assertEquals(3, entry.bytes.size)
    }

    @Test
    fun theOldestGoesFirst() {
        val box = outbox()
        box.add("first-20261027.tir", byteArrayOf(1))
        Thread.sleep(1100)  // lastModified has second granularity on some filesystems
        box.add("second-20261027.tir", byteArrayOf(2))
        assertEquals("first-20261027.tir", box.next()!!.fileName)
    }

    @Test
    fun removingIsWhatEmptiesIt() {
        val box = outbox()
        box.add("a-20261027.tir", byteArrayOf(1))
        assertEquals(1, box.count())
        box.remove("a-20261027.tir")
        assertEquals(0, box.count())
        assertNull(box.next())
    }

    @Test
    fun aHalfWrittenFileIsNotAQueuedRoute() {
        // .part is what add() writes before the rename, and it must never be
        // read back as a whole route.
        val dir = tmp.newFolder("routes2")
        java.io.File(dir, "half-20261027.tir.part").writeBytes(byteArrayOf(1, 2))
        assertEquals(0, RouteOutbox(dir).count())
        assertNull(RouteOutbox(dir).next())
    }

    @Test
    fun theFileNameIsAsciiAndCarriesTheDate() {
        assertEquals(
            "zahorie-nad-moravou-20261027.tir",
            RouteOutbox.fileNameFor("Záhorie nad Moravou", "20261027"),
        )
        // Diacritics are folded rather than dropped: the device reads this name
        // off a FAT card and draws the route's header name instead.
        assertEquals("sulov-hradna-20261027.tir", RouteOutbox.fileNameFor("Súľov-Hradná", "20261027"))
    }

    @Test
    fun aSecondRouteTheSameDayGetsItsOwnName() {
        val first = RouteOutbox.fileNameFor("Trip", "20261027", 0)
        val second = RouteOutbox.fileNameFor("Trip", "20261027", 1)
        assertEquals("trip-20261027.tir", first)
        assertEquals("trip-20261027-1.tir", second)
    }

    @Test
    fun aNameWithNothingUsableInItStillMakesAFile() {
        assertEquals("route-20261027.tir", RouteOutbox.fileNameFor("???", "20261027"))
    }

    @Test
    fun theNameStaysInsideTheDeviceSCap() {
        // MapRouteStore::kMaxFileNameBytes is 48 including the terminator, and
        // the transfer adds `trips/` and `.part` on top.
        val long = RouteOutbox.fileNameFor("A".repeat(80), "20261027")
        assertTrue(long, "trips/$long.part".length < 80)
        assertTrue(long, long.length < 48)
    }
}
