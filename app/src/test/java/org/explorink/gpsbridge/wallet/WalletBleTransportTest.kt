package org.explorink.gpsbridge.wallet

import org.explorink.gpsbridge.TransferFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

/**
 * The BLE transport against a **frame-level stub**: a fake `...0004` that parses
 * the frames the transport writes, behaves like `MapTransferReceiver`, and answers
 * on the fake `...0005`.
 *
 * Stubbed at the frame level and not higher, because the frames are the contract
 * (`docs/ble-map-transfer-protocol.md`) and they are what a mistake would be in.
 * What is NOT exercised here is a real GATT stack, a real MTU negotiation and a
 * real X4 -- see `docs/android-wallet.md` section 14 for that list.
 */
class WalletBleTransportTest {

    /**
     * Behaves like the device's receiver: checks the offset against what it has
     * written, truncates on every begin (a leftover `.part` is never resumed), reads
     * the finished file back and CRC32s it.
     */
    private class FakeReceiver(
        var connected: Boolean = true,
        var mtu: Int = 256,
    ) : WalletBleTransport.FrameSink {

        lateinit var transport: WalletBleTransport

        var path: String? = null
        var declaredTotal = 0
        var declaredCrc = 0L
        var received = java.io.ByteArrayOutputStream()
        var begins = 0
        var writes = 0
        val chunkOffsets = ArrayList<Int>()
        val chunkSizes = ArrayList<Int>()
        var aborted = 0

        /** Refuse the next N writes, as a busy stack would. */
        var failWrites = 0

        /** Say nothing back, so a timeout can be tested. */
        var silent = false

        /** Answer this instead of RDY. */
        var errOnBegin: String? = null

        /** Corrupt what "lands", so the CRC read back disagrees. */
        var corrupt = false

        /** Report a byte count that is not what arrived. */
        var lieAboutBytes = false

        /** Every setFastLink call, in order: the fast link's whole life. */
        val fastLink = ArrayList<Boolean>()

        override fun setFastLink(fast: Boolean) {
            fastLink.add(fast)
        }

        override fun maxChunkPayload(): Int = TransferFrames.maxChunkPayload(mtu)

        override fun isConnected(): Boolean = connected

        override fun sendFrame(frame: ByteArray, done: (Boolean, String?) -> Unit) {
            writes++
            if (failWrites > 0) {
                failWrites--
                done(false, "gatt busy")
                return
            }
            if (!connected) {
                done(false, "not connected")
                return
            }
            when (frame[0]) {
                TransferFrames.OP_BEGIN -> {
                    begins++
                    declaredTotal = u32(frame, 1).toInt()
                    declaredCrc = u32(frame, 5)
                    val len = frame[9].toInt() and 0xff
                    path = String(frame, 10, len, Charsets.UTF_8)
                    // O_TRUNC: whatever a killed transfer left is not resumed.
                    received = java.io.ByteArrayOutputStream()
                    chunkOffsets.clear()
                    chunkSizes.clear()
                    done(true, null)
                    if (silent) return
                    val err = errOnBegin
                    if (err != null) transport.onStatusLine("ERR $err")
                    else transport.onStatusLine("RDY $declaredTotal")
                }

                TransferFrames.OP_CHUNK -> {
                    val offset = u32(frame, 1).toInt()
                    if (offset != received.size()) {
                        done(true, null)
                        transport.onStatusLine("ERR offset")
                        return
                    }
                    chunkOffsets.add(offset)
                    chunkSizes.add(frame.size - 5)
                    received.write(frame, 5, frame.size - 5)
                    done(true, null)
                    if (received.size() >= declaredTotal) {
                        // The device reads the finished file back OFF THE CARD and
                        // CRC32s it. That is what makes OK mean "the card holds these
                        // bytes" rather than "I got your writes".
                        val onCard = received.toByteArray().also {
                            if (corrupt && it.isNotEmpty()) it[0] = (it[0] + 1).toByte()
                        }
                        val crc = CRC32().apply { update(onCard) }.value
                        val bytes = if (lieAboutBytes) onCard.size - 1 else onCard.size
                        if (!silent) {
                            transport.onStatusLine("OK $bytes ${java.lang.Long.toHexString(crc)}")
                        }
                    }
                }

                TransferFrames.OP_ABORT -> {
                    aborted++
                    done(true, null)
                }
            }
        }

        private fun u32(b: ByteArray, at: Int): Long {
            var v = 0L
            for (i in 0 until 4) v = v or ((b[at + i].toLong() and 0xff) shl (8 * i))
            return v
        }
    }

    private class FakeScheduler : WalletBleTransport.Scheduler {
        val pending = ArrayList<Pair<Long, () -> Unit>>()

        override fun postDelayed(delayMs: Long, action: () -> Unit): WalletBleTransport.Scheduler.Cancellable {
            val entry = Pair(delayMs, action)
            pending.add(entry)
            return object : WalletBleTransport.Scheduler.Cancellable {
                override fun cancel() {
                    pending.remove(entry)
                }
            }
        }

        /** Fire whatever is still armed, which is what a real timeout would do. */
        fun fire() {
            val now = ArrayList(pending)
            pending.clear()
            for ((_, a) in now) a()
        }
    }

    private class Result {
        var confirmed: String? = null
        var failed: String? = null
        var retryable = true
        val progress = ArrayList<Int>()
    }

    private fun rig(): Triple<FakeReceiver, FakeScheduler, WalletBleTransport> {
        val recv = FakeReceiver()
        val sched = FakeScheduler()
        val t = WalletBleTransport(recv, sched)
        recv.transport = t
        return Triple(recv, sched, t)
    }

    private fun send(t: WalletBleTransport, relPath: String, bytes: ByteArray): Result {
        val r = Result()
        t.send(SendJob(relPath, bytes, WalletFormat.sha256Hex(bytes)), object : SendCallback {
            override fun onProgress(sentBytes: Int) {
                r.progress.add(sentBytes)
            }

            override fun onConfirmed(detail: String) {
                r.confirmed = detail
            }

            override fun onFailed(reason: String, retryable: Boolean) {
                r.failed = reason
                r.retryable = retryable
            }
        })
        return r
    }

    @Test
    fun an_asset_goes_over_in_ordered_chunks_and_ok_confirms_it() {
        val (recv, sched, t) = rig()
        val bytes = ByteArray(1000) { (it % 97).toByte() }
        val r = send(t, "wallet/ab/abcdef0123456789.dat", bytes)

        assertNull(r.failed)
        assertTrue(r.confirmed!!.startsWith("OK 1000 "))
        // Relative to /trailink, which is what a begin frame wants -- and that means
        // **no** "trailink/" of our own. The receiver prepends its root:
        // MapTransferReceiver.cpp does `snprintf(finalPath_, ..., "%s/%s", rootDir_, rel)`
        // with rootDir_ = "/trailink".
        //
        // This assertion used to expect "trailink/wallet/..." and passed, because the
        // transport agreed with it. On hardware 2026-08-19 that shipped a 25-file sync
        // whose every file landed at /trailink/trailink/wallet/... The device answered
        // `OK <bytes> <crc32>` for each one -- it had written and verified exactly what
        // it was asked for -- and the wallet stayed invisible. A test that agrees with
        // the code proves nothing about the other end of the wire.
        assertEquals("wallet/ab/abcdef0123456789.dat", recv.path)
        assertEquals(1000, recv.declaredTotal)
        assertArrayEquals_(bytes, recv.received.toByteArray())

        // Chunks go 0, then previous + previous length, in order, no gaps, no repeats.
        var expect = 0
        for ((i, off) in recv.chunkOffsets.withIndex()) {
            assertEquals(expect, off)
            expect += recv.chunkSizes[i]
        }
        assertEquals(1000, expect)
        // And no chunk is bigger than the MTU allows.
        assertTrue(recv.chunkSizes.all { it <= TransferFrames.maxChunkPayload(256) })
        // Progress is monotone and ends at the whole file.
        assertEquals(r.progress.sorted(), r.progress)
        assertEquals(1000, r.progress.last())
        // Nothing is left armed after a clean finish.
        assertTrue(sched.pending.isEmpty())
        assertFalse(t.isBusy)
    }

    @Test
    fun a_crc_that_does_not_match_confirms_nothing() {
        val (recv, _, t) = rig()
        recv.corrupt = true
        val r = send(t, "wallet/ab/1111111111111111.dat", ByteArray(500) { 3 })
        assertNull(r.confirmed)
        assertTrue(r.failed, r.failed!!.startsWith("OK 500 B crc "))
        assertTrue(r.retryable)
    }

    @Test
    fun an_ok_with_the_wrong_byte_count_confirms_nothing_either() {
        val (recv, _, t) = rig()
        recv.lieAboutBytes = true
        val r = send(t, "wallet/ab/2222222222222222.dat", ByteArray(500) { 3 })
        assertNull(r.confirmed)
        assertTrue(r.failed!!.contains("sent 500 B"))
    }

    @Test
    fun err_on_begin_is_a_retryable_failure() {
        val (recv, _, t) = rig()
        recv.errOnBegin = "no space"
        val r = send(t, "wallet/ab/3333333333333333.dat", ByteArray(64))
        assertNull(r.confirmed)
        assertEquals("ERR no space", r.failed)
        assertTrue(r.retryable)
    }

    @Test
    fun a_disconnected_link_is_refused_before_a_frame_is_written() {
        val (recv, _, t) = rig()
        recv.connected = false
        val r = send(t, "wallet/ab/4444444444444444.dat", ByteArray(64))
        assertEquals("not connected", r.failed)
        assertFalse(r.retryable)
        assertEquals(0, recv.writes)
    }

    @Test
    fun losing_the_link_mid_asset_restarts_that_asset_from_zero() {
        val (recv, _, t) = rig()
        recv.silent = true                       // no RDY, so nothing is sent yet
        val first = send(t, "wallet/ab/5555555555555555.dat", ByteArray(4000) { 1 })
        assertTrue(t.isBusy)
        t.onDisconnected()
        assertEquals("disconnected", first.failed)
        assertFalse(first.retryable)

        // The device deletes its .part when the link dies, and a begin truncates
        // anyway (MapTransferReceiver.cpp:310-312). So the retry starts at offset 0:
        // resume across a connection is per ASSET, never per byte.
        recv.silent = false
        recv.connected = true
        val second = send(t, "wallet/ab/5555555555555555.dat", ByteArray(4000) { 1 })
        assertNull(second.failed)
        assertEquals(0, recv.chunkOffsets.first())
        assertEquals(2, recv.begins)
        assertFalse(t.resumesAcrossSessions)
    }

    @Test
    fun a_transient_write_failure_retries_from_the_same_offset() {
        val (recv, _, t) = rig()
        val bytes = ByteArray(2000) { (it % 31).toByte() }
        // Fail the third write (begin, chunk, chunk) once. The offset is a byte
        // offset checked against what the device has actually written, so retrying
        // the same offset is safe -- and it is the one place resume-by-offset earns
        // its keep today.
        val r = Result()
        var armed = false
        val wrapper = object : WalletBleTransport.FrameSink {
            override fun maxChunkPayload(): Int = recv.maxChunkPayload()
            override fun isConnected(): Boolean = recv.isConnected()
            override fun sendFrame(frame: ByteArray, done: (Boolean, String?) -> Unit) {
                if (!armed && frame[0] == TransferFrames.OP_CHUNK && recv.chunkOffsets.size == 2) {
                    armed = true
                    done(false, "gatt busy")
                    return
                }
                recv.sendFrame(frame, done)
            }
        }
        val t2 = WalletBleTransport(wrapper, FakeScheduler())
        recv.transport = t2
        t2.send(SendJob("wallet/ab/6666666666666666.dat", bytes,
            WalletFormat.sha256Hex(bytes)), object : SendCallback {
            override fun onProgress(sentBytes: Int) = Unit
            override fun onConfirmed(detail: String) {
                r.confirmed = detail
            }

            override fun onFailed(reason: String, retryable: Boolean) {
                r.failed = reason
            }
        })
        assertNull(r.failed)
        assertTrue(r.confirmed!!.startsWith("OK 2000 "))
        assertArrayEquals_(bytes, recv.received.toByteArray())
        // One begin: the retry did not restart the transfer.
        assertEquals(1, recv.begins)
    }

    @Test
    fun repeated_write_failures_give_up_without_confirming() {
        val (recv, _, t) = rig()
        recv.failWrites = 99
        val r = send(t, "wallet/ab/7777777777777777.dat", ByteArray(64))
        assertNull(r.confirmed)
        assertTrue(r.failed!!.startsWith("begin write failed"))
        assertFalse(r.retryable)
    }

    @Test
    fun a_silent_device_times_out_rather_than_hanging() {
        val (recv, sched, t) = rig()
        recv.silent = true
        val r = send(t, "wallet/ab/8888888888888888.dat", ByteArray(64))
        assertNull(r.confirmed)
        assertNull(r.failed)
        assertEquals(1, sched.pending.size)
        assertEquals(WalletBleTransport.READY_TIMEOUT_MS, sched.pending[0].first)
        sched.fire()
        assertEquals("no RDY", r.failed)
        assertTrue(r.retryable)
    }

    @Test
    fun an_unknown_status_line_is_ignored_not_guessed_at() {
        val (recv, _, t) = rig()
        recv.silent = true
        val r = send(t, "wallet/ab/9999999999999999.dat", ByteArray(64))
        t.onStatusLine("SOMETHING ELSE")
        t.onStatusLine("")
        assertNull(r.confirmed)
        assertNull(r.failed)
        assertTrue(t.isBusy)
    }

    @Test
    fun an_impossible_path_or_length_is_refused_before_the_wire() {
        val (recv, _, t) = rig()
        assertEquals(0, recv.writes)
        val bad = send(t, "wallet/../etc/passwd", ByteArray(64))
        assertTrue(bad.failed!!.startsWith("bad path"))
        assertFalse(bad.retryable)

        val empty = send(t, "wallet/ab/aaaaaaaaaaaaaaaa.dat", ByteArray(0))
        assertEquals("bad length: 0", empty.failed)
        assertEquals(0, recv.writes)
    }

    @Test
    fun cancelling_aborts_so_the_device_drops_its_part_now() {
        val (recv, _, t) = rig()
        recv.silent = true
        val r = send(t, "wallet/ab/bbbbbbbbbbbbbbbb.dat", ByteArray(64))
        t.cancel()
        assertEquals(1, recv.aborted)
        assertFalse(t.isBusy)
        // No verdict either way: the asset stays unconfirmed, so the queue resends it.
        assertNull(r.confirmed)
        assertNull(r.failed)
    }

    @Test
    fun a_whole_wallet_goes_over_the_stub_and_the_engine_confirms_every_asset() {
        val store = SyncFixtures.store()
        SyncFixtures.addItem(store, "Passport")
        val q = SyncFixtures.queue(store, full = true)
        q.queueAll()
        val (recv, _, t) = rig()
        val landed = HashMap<String, ByteArray>()
        val recvWrapper = object : WalletBleTransport.FrameSink {
            override fun maxChunkPayload(): Int = recv.maxChunkPayload()
            override fun isConnected(): Boolean = recv.isConnected()
            override fun sendFrame(frame: ByteArray, done: (Boolean, String?) -> Unit) {
                recv.sendFrame(frame, done)
                if (frame[0] == TransferFrames.OP_CHUNK &&
                    recv.received.size() >= recv.declaredTotal) {
                    landed[recv.path!!] = recv.received.toByteArray()
                }
            }
        }
        val t2 = WalletBleTransport(recvWrapper, FakeScheduler())
        recv.transport = t2

        val engine = WalletSyncEngine(SyncFixtures.bytesOf(store),
            object : WalletSyncEngine.Listener {
                override fun onAssetFailed(a: SyncAsset, reason: String) {
                    throw AssertionError("${a.key}: $reason")
                }
            }, persist = { store.saveSyncState(it) })
        engine.setQueue(q)
        engine.useTransport(t2)
        engine.start()

        assertTrue(q.pending().isEmpty())
        assertEquals(q.plan.size, landed.size)
        for (a in q.plan) {
            // No "trailink/" prefix: the begin frame's path is relative to the
            // receiver's own root (see the wire-path assertion above).
            val bytes = landed[a.relPath]!!
            assertEquals(a.sha256, WalletFormat.sha256Hex(bytes))
        }
        assertEquals(SyncState.FULLY_SYNCED, q.statusOf(store.load().items[0].id).state)
        assertTrue(q.confirmed.values.all { it.transport == "ble" })
    }

    private fun assertArrayEquals_(a: ByteArray, b: ByteArray) {
        assertEquals(a.size, b.size)
        for (i in a.indices) if (a[i] != b[i]) throw AssertionError("byte $i differs")
    }

    @Test
    fun the_fast_link_is_held_for_the_whole_queue_and_not_per_asset() {
        // Measured 2026-08-20: raising the connection priority per file left whole files
        // running at the balanced interval, because Android's negotiation takes hundreds
        // of milliseconds to land. Inside one transfer, the files that reached 15 ms moved
        // at 7.5 kB/s and the ones still at 30 ms moved at 3.9 kB/s.
        val (recv, _, t) = rig()
        for (i in 1..3) {
            val r = send(t, "wallet/ab/asset$i.dat", ByteArray(600) { it.toByte() })
            assertTrue("asset $i did not confirm", r.confirmed != null)
        }

        // Three assets, one raise, and nothing dropped in between.
        assertEquals("raised once for the queue, not once per asset",
            1, recv.fastLink.count { it })
        assertEquals("nothing may drop it between assets", 0, recv.fastLink.count { !it })

        // The engine's end-of-queue call is what gives the radio back.
        t.releaseFastLink()
        assertEquals(listOf(true, false), recv.fastLink)
    }
}
