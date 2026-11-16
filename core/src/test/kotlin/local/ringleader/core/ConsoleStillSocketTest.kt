package local.ringleader.core

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Network audit 2026-11-16, N-07: the still capture used its own websocket listener, which asked for the next message without
 * looking at its 256-chunk queue, so a server faster than the pixel loop could overflow it ("The console channel closed before a
 * frame arrived."). It now reads through [VncSocket], the live console's socket, which asks for the next message only while its
 * queue has room. The capture's 20 s budget still bounds every wait.
 */
class ConsoleStillSocketTest {
    private val base = "/api2/json/nodes/n1/qemu/100"
    private val vncJson = """{"data":{"port":5900,"ticket":"T-1","password":"pw"}}"""
    private fun connection(port: Int) = Connection("https://127.0.0.1:$port", "root@pam!audit", "SECRET-VALUE", NetAuditCerts.loopback.pin)
    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    /** The pixel at ([x],[y]) of the frames the fake servers send. */
    private fun pixel(x: Int, y: Int) = (255 shl 24) or ((x * 5 and 255) shl 16) or ((y * 3 and 255) shl 8) or ((x xor y) and 255)

    /**
     * An RFB 3.8 server with no authentication that sends one Raw rectangle covering the screen, in websocket messages of
     * [message] bytes: as fast as the socket takes them, which is faster than the client turns them into pixels.
     */
    private fun rfbServer(width: Int, height: Int, message: Int): (WsConn) -> Unit = { conn ->
        try {
            conn.sendBinary("RFB 003.008\n".toByteArray())
            conn.received.poll(10, TimeUnit.SECONDS)                    // the client's version
            conn.sendBinary(byteArrayOf(1, 1))                          // one security type: none
            conn.received.poll(10, TimeUnit.SECONDS)
            conn.sendBinary(byteArrayOf(0, 0, 0, 0))                    // security result
            conn.received.poll(10, TimeUnit.SECONDS)                    // ClientInit
            val init = ByteArrayOutputStream()
            init.write(u16(width)); init.write(u16(height)); init.write(ByteArray(16)); init.write(byteArrayOf(0, 0, 0, 4)); init.write("test".toByteArray())
            conn.sendBinary(init.toByteArray())
            repeat(3) { conn.received.poll(10, TimeUnit.SECONDS) }      // pixel format, encodings, update request
            val all = ByteArrayOutputStream()
            all.write(byteArrayOf(0, 0, 0, 1)); all.write(u16(0)); all.write(u16(0)); all.write(u16(width)); all.write(u16(height)); all.write(byteArrayOf(0, 0, 0, 0))
            val row = ByteArray(width * 4)
            for (y in 0 until height) { for (x in 0 until width) putPixel(row, x * 4, pixel(x, y)); all.write(row) }
            val bytes = all.toByteArray()
            var at = 0
            while (at < bytes.size) { val n = minOf(message, bytes.size - at); conn.sendBinary(bytes.copyOfRange(at, at + n)); at += n }
            Thread.sleep(2000)
        } catch (_: Exception) { }
    }

    @Test fun aServerFasterThanThePixelLoopCannotOverflowAnyQueueBecauseTheSocketAsksOnlyWhileThereIsRoom() {
        // 2048 messages of 512 bytes into a queue of TWO chunks: without flow control the third message overflows it at once.
        MiniWsServer(null, onConnected = rfbServer(512, 512, 512)).use { server ->
            val socket = VncSocket(chunks = 2, timeoutMessage = "The console capture timed out.", lostMessage = "The console channel closed before a frame arrived.")
            val http = HttpClient.newHttpClient()
            try {
                val ws = http.newWebSocketBuilder().subprotocols("binary").buildAsync(URI("ws://127.0.0.1:${server.port}/"), socket).get(10, TimeUnit.SECONDS)
                socket.attach(ws)
                val frame = captureRfbFrame(socket, "pw")
                assertEquals(512, frame.width); assertEquals(512, frame.height)
                for (y in 0 until 512 step 5) for (x in 0 until 512 step 3) assertEquals(pixel(x, y), frame.pixels[y * 512 + x], "($x,$y)")
                assertEquals(pixel(511, 511), frame.pixels[512 * 512 - 1])
            } finally { socket.close(); http.shutdownNow() }
        }
    }

    @Test fun theStillOfABigRawFrameFromAFastServerSucceedsThroughThePinnedTlsPath() {
        // 2048 x 2048 raw = 16 MiB in 16 KiB messages: 1024 messages, four times the 256 chunks the queue holds.
        MiniWsServer(NetAuditCerts.loopback.server, onConnected = rfbServer(2048, 2048, 16 * 1024)).use { server ->
            val still = captureConsoleStill(connection(server.port), base) { vncJson }
            assertEquals(960, still.width); assertEquals(960, still.height)
            assertFalse(still.blank)
            assertTrue(still.png.size > 1000)
        }
    }

    @Test fun aServerThatSaysNothingEndsTheCaptureWithinItsBudgetAndSaysSo() {
        MiniWsServer(NetAuditCerts.loopback.server) { conn -> conn.sendBinary("RFB 003.008\n".toByteArray()); Thread.sleep(15_000) }.use { server ->
            val started = System.nanoTime()
            val failure = assertFailsWith<IllegalStateException> { captureConsoleStill(connection(server.port), base, seconds = 2) { vncJson } }
            val seconds = (System.nanoTime() - started) / 1e9
            assertEquals("The console capture timed out.", failure.message)
            assertTrue(seconds in 1.5..6.0, "gave up after $seconds s of a 2 s budget")
        }
    }

    @Test fun aServerThatClosesBeforeAFrameEndsTheCaptureWithTheClosedSentence() {
        MiniWsServer(NetAuditCerts.loopback.server) { conn -> conn.sendBinary("RFB 003.008\n".toByteArray()); Thread.sleep(300); conn.close() }.use { server ->
            val failure = assertFailsWith<IllegalStateException> { captureConsoleStill(connection(server.port), base) { vncJson } }
            assertEquals("The console channel closed before a frame arrived.", failure.message)
        }
    }

    @Test fun aTextFrameEndsTheCaptureAtOnce() {
        MiniWsServer(NetAuditCerts.loopback.server) { conn -> conn.sendText("not a console"); Thread.sleep(5000) }.use { server ->
            val started = System.nanoTime()
            val failure = assertFailsWith<IllegalStateException> { captureConsoleStill(connection(server.port), base) { vncJson } }
            assertEquals(VNC_TEXT_FRAME, failure.message)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 4000, "no waiting for the timeout")
        }
    }
}
