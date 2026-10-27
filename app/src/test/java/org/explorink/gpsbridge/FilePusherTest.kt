package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pusher on its own, with a path that is not a tile.
 *
 * `TileFetcherTest` already drives this class through the fetcher and covers
 * every measured race it carries. What these tests add is the thing the
 * extraction was for: the loop works for any file, which is what a route push
 * needs (`docs/route-share-plan.md`, Phase 1).
 */
class FilePusherTest {

    private class FakeTransport(private val payload: Int = 16) : TileFetcher.Transport {
        val frames = mutableListOf<ByteArray>()
        var failNextFrame = false

        override fun sendCommand(line: String, done: (Boolean, String?) -> Unit) = done(true, null)

        override fun sendFrame(frame: ByteArray, done: (Boolean, String?) -> Unit) {
            frames.add(frame)
            if (failNextFrame) {
                failNextFrame = false
                done(false, "fake failure")
            } else {
                done(true, null)
            }
        }

        override fun maxChunkPayload(): Int = payload

        override fun setFastLink(fast: Boolean) = Unit

        fun opcodes(): List<Byte> = frames.map { it[0] }
    }

    private class FakeScheduler : TileFetcher.Scheduler {
        var pending: (() -> Unit)? = null

        override fun postDelayed(delayMs: Long, action: () -> Unit): TileFetcher.Scheduler.Cancellable {
            pending = action
            return object : TileFetcher.Scheduler.Cancellable {
                override fun cancel() {
                    if (pending === action) pending = null
                }
            }
        }

        /** Fires the armed timer, as a device that stopped answering would. */
        fun fire() {
            val action = pending ?: error("nothing armed")
            pending = null
            action()
        }
    }

    private class Recorder : FilePusher.Listener {
        val events = mutableListOf<String>()
        var doneBytes = 0
        var doneCrc = 0L
        var failReason: String? = null
        var startingCrc = 0L

        override fun onPushStarting(tag: Any?, path: String, totalBytes: Int, crc32: Long) {
            startingCrc = crc32
            events.add("starting $tag $path $totalBytes")
        }

        override fun onPushReady(tag: Any?) {
            events.add("ready $tag")
        }

        override fun onPushProgress(tag: Any?, sentBytes: Int, totalBytes: Int) {
            events.add("progress $sentBytes/$totalBytes")
        }

        override fun onPushDone(tag: Any?, bytes: Int, crc32: Long) {
            doneBytes = bytes
            doneCrc = crc32
            events.add("done $tag")
        }

        override fun onPushFailed(tag: Any?, reason: String) {
            failReason = reason
            events.add("failed $tag $reason")
        }
    }

    private val path = "trips/zahorie-2026-08.tir"
    private val data = ByteArray(40) { (it and 0xff).toByte() }

    private fun pusher(
        transport: FakeTransport = FakeTransport(),
        scheduler: FakeScheduler = FakeScheduler(),
        recorder: Recorder = Recorder(),
    ) = FilePusher(transport, scheduler, recorder, 15_000L)

    @Test
    fun aWholeFileGoesOutInChunksAndTheDeviceSOkFinishesIt() {
        val transport = FakeTransport(payload = 16)
        val recorder = Recorder()
        val push = pusher(transport, FakeScheduler(), recorder)

        push.start("route", path, data)
        assertTrue(push.busy)
        assertEquals(listOf(TransferFrames.OP_BEGIN), transport.opcodes())
        assertEquals(TransferFrames.crc32(data), recorder.startingCrc)

        push.onStatusLine("RDY ${data.size}")
        // 40 bytes at 16 per chunk is three chunks, the last one short.
        assertEquals(
            listOf(TransferFrames.OP_BEGIN, TransferFrames.OP_CHUNK, TransferFrames.OP_CHUNK, TransferFrames.OP_CHUNK),
            transport.opcodes(),
        )
        assertEquals(listOf("progress 16/40", "progress 32/40", "progress 40/40"), recorder.events.filter { it.startsWith("progress") })

        push.onStatusLine("OK 40 deadbeef")
        assertEquals(40, recorder.doneBytes)
        assertEquals(0xdeadbeefL, recorder.doneCrc)
        assertFalse(push.busy)
    }

    @Test
    fun theBeginFrameCarriesThePathTheCallerAskedFor() {
        val transport = FakeTransport()
        pusher(transport).start("route", path, data)
        val begin = transport.frames.first()
        val pathBytes = begin.copyOfRange(begin.size - path.toByteArray().size, begin.size)
        assertEquals(path, String(pathBytes))
    }

    @Test
    fun aDeviceErrEndsThePushAndSaysSo() {
        val recorder = Recorder()
        val push = pusher(recorder = recorder)
        push.start("route", path, data)
        push.onStatusLine("ERR no space")
        assertEquals(FilePusher.REASON_REFUSED, recorder.failReason)
        assertFalse(push.busy)
    }

    @Test
    fun aFailedBeginWriteEndsThePushRatherThanStalling() {
        val transport = FakeTransport()
        val recorder = Recorder()
        transport.failNextFrame = true
        pusher(transport, FakeScheduler(), recorder).start("route", path, data)
        assertEquals(FilePusher.REASON_REFUSED, recorder.failReason)
    }

    @Test
    fun aDeviceThatStopsAnsweringIsAbortedAndReported() {
        val transport = FakeTransport()
        val scheduler = FakeScheduler()
        val recorder = Recorder()
        val push = pusher(transport, scheduler, recorder)
        push.start("route", path, data)
        scheduler.fire()
        assertEquals(FilePusher.REASON_REFUSED, recorder.failReason)
        assertEquals(TransferFrames.OP_ABORT, transport.opcodes().last())
        assertFalse(push.busy)
    }

    @Test
    fun anAbortedTransferSVerdictIsNotCreditedToTheFileAfterIt() {
        // The measured defect the generation counter and the verdict debt exist
        // for: the device finishes what it was told to abort, and its OK lands
        // after the next begin has gone out.
        val transport = FakeTransport()
        val recorder = Recorder()
        val push = pusher(transport, FakeScheduler(), recorder)

        push.start("first", path, data)
        push.abortInFlight()
        push.clear()
        push.start("second", "trips/second.tir", data)
        push.onStatusLine("OK 40 deadbeef")

        assertTrue(push.busy)
        assertFalse(recorder.events.any { it.startsWith("done") })
    }

    @Test
    fun aVerdictWithNothingInFlightIsIgnored() {
        val recorder = Recorder()
        val push = pusher(recorder = recorder)
        push.onStatusLine("OK 40 deadbeef")
        assertFalse(recorder.events.any { it.startsWith("done") })
    }
}
