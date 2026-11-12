package local.ringleader.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Input tracking, QEMU extended keys, the writer thread, the keepalive and the websocket pipe of [VncSession]. */
class VncInputTest {
    /** Serves [bytes], then blocks reads until closed. Writes are recorded and can be held back with [gate]. */
    private class Channel(bytes: ByteArray) : RfbChannel {
        private val input = bytes.inputStream()
        private val closed = CountDownLatch(1)
        val writes: MutableList<ByteArray> = java.util.Collections.synchronizedList(mutableListOf())
        @Volatile var blocking = false
        val gate = Semaphore(0)
        override fun read(n: Int): ByteArray {
            if (input.available() >= n) return input.readNBytes(n)
            closed.await()
            error(VNC_LOST)
        }
        override fun write(bytes: ByteArray) {
            if (blocking) gate.acquire()
            writes += bytes
        }
        override fun close() { closed.countDown() }
    }

    private fun handshake(vararg after: ByteArray): ByteArray {
        val raw = ByteArrayOutputStream()
        DataOutputStream(raw).apply {
            writeBytes("RFB 003.008\n"); writeByte(1); writeByte(2); write(ByteArray(16)); writeInt(0)
            writeShort(4); writeShort(2); write(ByteArray(16)); writeInt(2); writeBytes("vm")
            after.forEach { write(it) }
        }
        return raw.toByteArray()
    }

    /** A FramebufferUpdate with one empty pseudo-rectangle of [encoding]. */
    private fun pseudo(encoding: Int): ByteArray =
        ByteBuffer.allocate(4 + 12).put(0).put(0).putShort(1).putShort(0).putShort(0).putShort(0).putShort(0).putInt(encoding).array()

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) { check(System.nanoTime() < deadline) { "timed out waiting for $what" }; Thread.sleep(5) }
    }

    @Test fun qemuExtendedKeyEventEncoding() {
        assertContentEquals(byteArrayOf(-1, 0, 0, 1, 0, 0, 0, 0x61, 0, 0, 0, 0x1e), vncQemuKeyEvent(true, 0x61, 0x1e))
        assertContentEquals(byteArrayOf(-1, 0, 0, 0, 0, 0, 0xff.toByte(), 0x53, 0, 0, 0, 0xcd.toByte()), vncQemuKeyEvent(false, 0xff53, 0xcd))
        // XT make codes: one byte as is; E0-prefixed keys carry the high bit (E0 4D, Right arrow, is 0xCD).
        assertEquals(0x1e, vncXtKeycode(0x1e, extended = false))
        assertEquals(0xcd, vncXtKeycode(0x4d, extended = true))
        assertEquals(0x9d, vncXtKeycode(0x1d, extended = true))
        assertEquals(0, vncXtKeycode(0, extended = true))
        assertEquals(0, vncXtKeycode(0x80, extended = false))
    }

    @Test fun extendedKeysAreUsedOnlyAfterTheServerConfirmsThem() {
        val channel = Channel(handshake(pseudo(VNC_QEMU_EXTENDED_KEY)))
        val session = VncSession(channel)
        session.handshake("password")
        session.keyInput(1, true, 0x61, 0x1e)
        session.keyInput(1, false, 0x61, 0x1e)
        assertContentEquals(vncKeyEvent(true, 0x61), channel.writes[channel.writes.size - 2])
        assertContentEquals(vncKeyEvent(false, 0x61), channel.writes.last())
        assertTrue(session.pump())
        assertTrue(session.extendedKeys)
        session.keyInput(1, true, 0x61, 0x1e)
        assertContentEquals(vncQemuKeyEvent(true, 0x61, 0x1e), channel.writes.last())
        // A key without a known keycode still goes as a plain keysym.
        session.keyInput(2, true, 0x40, 0)
        assertContentEquals(vncKeyEvent(true, 0x40), channel.writes.last())
        session.key(true, 0xffe3, 0x1d)
        assertContentEquals(vncQemuKeyEvent(true, 0xffe3, 0x1d), channel.writes.last())
        session.close()
    }

    @Test fun keyUpRepeatsItsKeyDownAndReleaseInputLetsGoOfEverything() {
        val channel = Channel(handshake())
        val session = VncSession(channel)
        session.handshake("password")
        val start = channel.writes.size
        session.keyInput(10, true, 0xffe1)          // Shift down
        session.keyInput(20, true, 0x41)            // 'A' down while shifted
        session.keyInput(10, false, 0xffe1)         // Shift up
        session.keyInput(20, false, 0x61)           // the same key comes up as 'a'
        assertContentEquals(vncKeyEvent(false, 0x41), channel.writes.last(), "the key-up repeats the keysym that went down")
        session.keyInput(30, true, 0xffe3)          // Ctrl held
        session.pointer(1, 3, 1)                    // left button held
        assertTrue(session.buttonsHeld)
        val before = channel.writes.size
        session.releaseInput()
        assertEquals(listOf(vncKeyEvent(false, 0xffe3), vncPointerEvent(0, 3, 1)).map { it.toList() }, channel.writes.drop(before).map { it.toList() })
        assertFalse(session.buttonsHeld)
        session.releaseInput()
        assertEquals(before + 2, channel.writes.size, "nothing is held twice")
        assertEquals(start + 8, channel.writes.size)
        session.close()
    }

    @Test fun releaseButtonsKeepsKeysAndCloseReleasesKeys() {
        val channel = Channel(handshake())
        val session = VncSession(channel)
        session.handshake("password")
        session.keyInput(1, true, 0xffe9)
        session.pointer(4, 2, 1)
        session.releaseButtons()
        assertContentEquals(vncPointerEvent(0, 2, 1), channel.writes.last())
        session.releaseButtons()
        assertContentEquals(vncPointerEvent(0, 2, 1), channel.writes.last())
        val before = channel.writes.size
        session.close()
        assertEquals(listOf(vncKeyEvent(false, 0xffe9).toList()), channel.writes.drop(before).map { it.toList() }, "close sends the held Alt up")
        assertNull(session.reason)
    }

    @Test fun theWriterKeepsCallersOffTheSocketAndPreservesOrder() {
        val channel = Channel(handshake())
        val session = VncSession(channel)
        session.handshake("password")
        channel.blocking = true
        session.start()
        val before = channel.writes.size
        val started = System.nanoTime()
        repeat(3) { session.key(true, 0x61 + it) }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000, "a stalled socket does not block the caller")
        assertEquals(before, channel.writes.size)
        channel.gate.release(3)
        waitFor("three writes") { channel.writes.size >= before + 3 }
        assertEquals(listOf(0x61, 0x62, 0x63).map { vncKeyEvent(true, it).toList() }, channel.writes.drop(before).take(3).map { it.toList() })
        channel.blocking = false
        channel.gate.release(100)
        session.close()
        assertNull(session.reason)
    }

    @Test fun aStalledSocketDropsMotionButEndsTheSessionForKeys() {
        val channel = Channel(handshake())
        val session = VncSession(channel)
        session.handshake("password")
        channel.blocking = true
        session.start()
        repeat(VNC_OUTBOX_LIMIT + 50) { session.pointer(0, it % 4, 0) }
        assertNull(session.reason, "pointer motion is dropped when the outbox is full")
        session.key(true, 0x61)
        assertEquals(VNC_LOST, session.reason)
    }

    @Test fun keepaliveEndsOnlyWhenAPingGoesUnanswered() {
        var pings = 0
        var lost = 0
        var result: () -> CompletionStage<*> = { pings++; CompletableFuture.completedFuture(null) }
        val beat = VncKeepalive({ result() }) { lost++ }
        beat.tick(); beat.pong(); beat.tick(); beat.pong(); beat.tick()
        assertEquals(3, pings); assertEquals(0, lost)
        beat.tick()
        assertEquals(1, lost, "the second tick without a pong ends the session")
        beat.tick()
        assertEquals(1, lost, "it ends once")

        lost = 0
        result = { CompletableFuture.failedFuture<Any>(java.io.IOException("reset")) }
        VncKeepalive({ result() }) { lost++ }.tick()
        assertEquals(1, lost, "a ping that cannot be sent ends the session")

        lost = 0
        result = { CompletableFuture.failedFuture<Any>(IllegalStateException("pong pending")) }
        val busy = VncKeepalive({ result() }) { lost++ }
        busy.tick(); busy.tick()
        assertEquals(0, lost, "a ping refused because another control frame is in flight skips one beat")
    }

    /** Records request(n) calls; nothing is sent. */
    private class FakeSocket : WebSocket {
        var requested = 0L
        override fun sendText(data: CharSequence, last: Boolean): CompletableFuture<WebSocket> = CompletableFuture.completedFuture(this)
        override fun sendBinary(data: ByteBuffer, last: Boolean): CompletableFuture<WebSocket> = CompletableFuture.completedFuture(this)
        override fun sendPing(message: ByteBuffer): CompletableFuture<WebSocket> = CompletableFuture.completedFuture(this)
        override fun sendPong(message: ByteBuffer): CompletableFuture<WebSocket> = CompletableFuture.completedFuture(this)
        override fun sendClose(statusCode: Int, reason: String): CompletableFuture<WebSocket> = CompletableFuture.completedFuture(this)
        override fun request(n: Long) { requested += n }
        override fun getSubprotocol() = "binary"
        override fun isOutputClosed() = false
        override fun isInputClosed() = false
        override fun abort() = Unit
    }

    @Test fun aTextFrameEndsTheSocketAtOnce() {
        val ws = FakeSocket()
        val socket = VncSocket()
        socket.onOpen(ws); socket.attach(ws)
        socket.onText(ws, "RFB 003.008\n", true)
        val started = System.nanoTime()
        assertEquals(VNC_TEXT_FRAME, assertFailsWith<IllegalStateException> { socket.read(12) }.message)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000, "no wait for the handshake timeout")
    }

    @Test fun theSocketStopsAskingForDataWhileTheQueueIsFull() {
        val ws = FakeSocket()
        val socket = VncSocket(chunks = 2)
        socket.handshake = false
        socket.onOpen(ws); socket.attach(ws)
        assertEquals(1, ws.requested)
        socket.onBinary(ws, ByteBuffer.wrap(byteArrayOf(1)), true)
        assertEquals(2, ws.requested)
        socket.onBinary(ws, ByteBuffer.wrap(byteArrayOf(2)), true)
        assertEquals(2, ws.requested, "full: no more requests")
        var pongs = 0
        socket.onPong = { pongs++ }
        assertContentEquals(byteArrayOf(1), socket.read(1))
        assertEquals(3, ws.requested, "the reader resumes the flow once there is room")
        socket.onBinary(ws, ByteBuffer.wrap(byteArrayOf(3)), true)
        assertContentEquals(byteArrayOf(2, 3), socket.read(2))
        socket.onPong(ws, ByteBuffer.allocate(0))
        assertEquals(1, pongs)
        socket.close()
        assertEquals(VNC_LOST, assertFailsWith<IllegalStateException> { socket.read(1) }.message)
    }
}
