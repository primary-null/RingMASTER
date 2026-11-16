package local.ringleader.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Network audit 2026-11-16, N-16: [VncSession.close] joins the writer (up to 300 ms) and the reader (up to 500 ms), and the console view
 * calls it from the UI thread when the page is left, so a session whose socket had stalled held the window for most of a second.
 * [VncSession.closeInBackground] marks the session closed for the caller at once and finishes on a short-lived thread.
 */
class VncCloseTest {
    /** Serves the hello, then blocks reads until closed. A write blocks while [stuck] is on, as a stalled socket's does. */
    private class Channel(bytes: ByteArray) : RfbChannel {
        private val input = bytes.inputStream()
        val closed = CountDownLatch(1)
        val writes = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        @Volatile var stuck = false
        private val gate = Semaphore(0)
        override fun read(n: Int): ByteArray {
            if (input.available() >= n) return input.readNBytes(n)
            closed.await()
            error(VNC_LOST)
        }
        override fun write(bytes: ByteArray) { if (stuck) gate.acquire(); writes += bytes }
        override fun close() { closed.countDown(); gate.release(100) }
    }

    private fun hello(): ByteArray {
        val raw = ByteArrayOutputStream()
        DataOutputStream(raw).apply {
            writeBytes("RFB 003.008\n"); writeByte(1); writeByte(2); write(ByteArray(16)); writeInt(0)
            writeShort(4); writeShort(2); write(ByteArray(16)); writeInt(2); writeBytes("vm")
        }
        return raw.toByteArray()
    }

    /** A started session whose writer is stuck inside a write, with one more message waiting behind it. */
    private fun stuckSession(): Pair<VncSession, Channel> {
        val channel = Channel(hello())
        val session = VncSession(channel)
        session.handshake("password")
        channel.stuck = true
        session.start()
        session.key(true, 0x61)          // the writer takes this and sticks inside write
        session.key(false, 0x61)
        Thread.sleep(150)
        return session to channel
    }

    @Test fun aSynchronousCloseWaitsOnAStuckWriterForItsFlushTime() {
        val (session, channel) = stuckSession()
        val started = System.nanoTime()
        session.close()
        val took = (System.nanoTime() - started) / 1_000_000
        println("NETAUDIT VncSession.close() with a stuck writer: $took ms")
        assertTrue(took >= 250, "the caller waited for the writer's flush time: $took ms")
        assertTrue(channel.closed.await(2, TimeUnit.SECONDS))
    }

    @Test fun closingInTheBackgroundReturnsAtOnceAndStillFinishesTheClose() {
        val (session, channel) = stuckSession()
        val started = System.nanoTime()
        session.closeInBackground()
        val took = (System.nanoTime() - started) / 1_000_000
        println("NETAUDIT VncSession.closeInBackground() with a stuck writer: $took ms")
        assertTrue(took < 150, "the caller did not wait: $took ms")
        assertTrue(session.isClosed(), "closed for the caller at once")
        assertTrue(channel.closed.await(3, TimeUnit.SECONDS), "the slow part finished by itself: the channel was closed")
        session.closeInBackground(); session.close()                  // both are idempotent
    }

    @Test fun backgroundCloseStillReleasesWhatTheGuestSeesAsHeld() {
        val channel = Channel(hello())
        val session = VncSession(channel)
        session.handshake("password")
        session.start()
        session.key(true, 0x61)
        session.keyInput(7, true, 0x62)                              // a tracked key that is down when the page is left
        val before = channel.writes.size
        session.closeInBackground()
        assertTrue(channel.closed.await(3, TimeUnit.SECONDS))
        val released = channel.writes.drop(before).any { it.contentEquals(vncKeyEvent(false, 0x62)) }
        assertTrue(released, "the key-up for the held key was sent before the channel closed")
        assertFalse(session.reason != null, "closing is not an error")
        assertEquals(null, session.reason)
    }
}
