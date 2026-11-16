package local.ringleader.core

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * N-05 against the real server's behaviour, modelled. On the lab host (2026-11-16) the console relay (termproxy running `pct
 * console`) stopped for good once it was handed more input than it had echoed: 4,080 and 16,320 bytes sent at once came back whole, 32,720
 * bytes (two frames of 16 KiB) returned 3,752 bytes of echo and nothing after, and 96,000 bytes in frames of 512 left the shell silent too; with
 * the tty echo off 300 KiB went through whole. [Relay] is that behaviour in a few lines, so the pacing can be tested without a server: a paste
 * that holds back until the echo returns survives it, one that does not is wedged by it.
 */
class TerminalPasteEchoTest {
    private fun waitFor(what: String, millis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (!condition()) { check(System.nanoTime() < deadline) { "timed out waiting for $what" }; Thread.sleep(10) }
    }

    /**
     * Takes `0:<n>:<data>` messages in order. Echoes each back (unless [echo] is false or [echoLimit] bytes were echoed) after a delay that
     * grows with its size. Wedges for good, sending nothing more, when the input waiting for its echo (this message and every one still
     * queued behind it) passes [capacity].
     */
    private class Relay(private val echo: Boolean, private val capacity: Int = 20_000, private val echoLimit: Long = Long.MAX_VALUE) {
        @Volatile var wedged = false
        @Volatile var taken = 0L
        @Volatile var echoed = 0L
        @Volatile var peak = 0L
        @Volatile var typed = ""
        private fun size(frame: WsFrame): Int =
            if (frame.opcode == 1 && frame.text.startsWith("0:")) frame.text.substring(2, frame.text.indexOf(':', 2)).toInt() else 0
        fun serve(conn: WsConn) {
            conn.received.poll(10, TimeUnit.SECONDS)                         // user:ticket
            conn.sendBinary("OK".toByteArray())
            while (true) {
                val frame = conn.received.poll(30, TimeUnit.SECONDS) ?: break
                val n = size(frame)
                if (n == 0) continue
                val data = frame.text.substring(frame.text.indexOf(':', 2) + 1)
                if (n < 16) typed += data
                var waiting = n + conn.received.sumOf { size(it) }
                peak = maxOf(peak, waiting.toLong())
                taken += n
                // The real console wedges on input it has not echoed. With echo off nothing waits for an echo: the live server took
                // 240,000 bytes whole. So a backlog here with echo off is this model being slow on a loaded machine, not a wedge.
                if (echo && waiting > capacity) { wedged = true; Thread.sleep(60_000); break }
                if (!echo || echoed >= echoLimit) continue
                // The echo goes back a kilobyte at a time; input that arrives meanwhile is waiting for its echo too.
                val back = data.replace(Char(13).toString(), Char(13).toString() + Char(10)).toByteArray()
                var at = 0
                while (at < back.size) {
                    val step = minOf(1024, back.size - at)
                    Thread.sleep(2)
                    conn.sendBinary(back.copyOfRange(at, at + step)); at += step
                    waiting = (n - (at.toLong() * n / back.size)).toInt() + conn.received.sumOf { size(it) }
                    peak = maxOf(peak, waiting.toLong())
                    if (waiting > capacity) { wedged = true; Thread.sleep(60_000); return }
                }
                echoed += n
            }
        }
    }

    private class Live(val session: TerminalSession, val http: HttpClient, val ws: WebSocket)

    private fun open(server: MiniWsServer, probeMillis: Long = 60, stallMillis: Long = 600): Live {
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val session = TerminalSession(TerminalModel(80, 24), http, pasteProbeMillis = probeMillis, pasteStallMillis = stallMillis)
        val ws = http.newWebSocketBuilder().subprotocols("binary").buildAsync(URI("ws://127.0.0.1:${server.port}/x"), session).get(10, TimeUnit.SECONDS)
        session.start(ws, TermProxy(5900, "t", "root@pam", "UPID:fixture"))
        waitFor("ready") { session.ready }
        return Live(session, http, ws)
    }

    private val line = "x".repeat(79) + "\r"

    // ---- the model itself behaves like the measured server ----

    @Test fun theModelWedgesOnABurstAndTakesASmallOneWhole() {
        for ((burstFrames, expectWedge) in listOf(1 to false, 2 to true)) {
            val relay = Relay(echo = true)
            MiniWsServer(null) { relay.serve(it) }.use { server ->
                val http = HttpClient.newHttpClient()
                val got = java.util.concurrent.atomic.AtomicLong()
                val ws = http.newWebSocketBuilder().buildAsync(URI("ws://127.0.0.1:${server.port}/x"), object : WebSocket.Listener {
                    override fun onOpen(w: WebSocket) { w.request(Long.MAX_VALUE) }
                    override fun onBinary(w: WebSocket, data: java.nio.ByteBuffer, last: Boolean): CompletionStage<*>? { got.addAndGet(data.remaining().toLong()); return null }
                }).get(10, TimeUnit.SECONDS)
                try {
                    ws.sendText("greeting", true).join()
                    val frame = line.repeat(204).substring(0, 16_320)               // 16,320 data bytes, the size that worked live
                    repeat(burstFrames) { ws.sendText("0:${frame.length}:$frame", true).join() }
                    Thread.sleep(1_500)
                    assertEquals(expectWedge, relay.wedged, "$burstFrames frames of 16,320 bytes at once")
                    if (!expectWedge) assertTrue(got.get() >= 16_320, "echoed ${got.get()}")
                } finally { ws.abort(); http.shutdownNow() }
            }
        }
    }

    // ---- the paste against it ----

    @Test fun aPasteIsPacedByTheEchoSoTheRelayIsNeverWedgedAndEveryByteArrives() {
        val relay = Relay(echo = true)
        MiniWsServer(null) { relay.serve(it) }.use { server ->
            val live = open(server)
            try {
                val text = line.repeat(3840)                                          // 300 KiB
                assertNull(live.session.paste(text))
                waitFor("the paste to leave", 60_000) { live.session.pasteLeft == 0 }
                waitFor("the echo", 20_000) { relay.echoed >= text.length }
                // Sent by the relay is not yet received here: wait for the last of it to arrive before counting it.
                waitFor("the echo to arrive", 20_000) { live.session.bytesReceived >= text.length }
                println("NETAUDIT paced paste of ${text.length} bytes into an echoing relay: peak waiting ${relay.peak} bytes, echoed ${relay.echoed}, session reason=${live.session.reason}")
                assertFalse(relay.wedged, "the relay was never handed more than it could echo (peak ${relay.peak})")
                assertTrue(relay.peak <= TERMINAL_PASTE_WINDOW + 2 * TERMINAL_PASTE_FRAME_BYTES, "most input ever waiting for its echo: ${relay.peak}")
                assertEquals(text.length.toLong(), relay.taken)
                assertNull(live.session.reason)
                assertNull(live.session.takePasteNote())
                assertTrue(live.session.bytesReceived >= text.length, "the echo came back: ${live.session.bytesReceived}")
            } finally { live.session.close() }
        }
    }

    @Test fun pasteWithoutAnyPacingWedgesTheModelProvingThePacingIsWhatSavesIt() {
        // The same paste as one burst of the frames the sender used to cut, straight to the socket.
        val relay = Relay(echo = true)
        MiniWsServer(null) { relay.serve(it) }.use { server ->
            val http = HttpClient.newHttpClient()
            val ws = http.newWebSocketBuilder().buildAsync(URI("ws://127.0.0.1:${server.port}/x"), object : WebSocket.Listener {
                override fun onOpen(w: WebSocket) { w.request(Long.MAX_VALUE) }
            }).get(10, TimeUnit.SECONDS)
            try {
                ws.sendText("greeting", true).join()
                for (piece in terminalChunks(line.repeat(3840), TERMINAL_FRAME_BYTES)) ws.sendText(terminalFrame(data = piece), true).join()
                waitFor("the relay to wedge", 10_000) { relay.wedged }
                assertTrue(relay.echoed < 20_000, "echoed ${relay.echoed} of 307,200 before it stopped for good")
            } finally { ws.abort(); http.shutdownNow() }
        }
    }

    @Test fun aPasteIntoNoEchoAtAllIsSentWithoutWaitingForever() {
        val relay = Relay(echo = false)
        MiniWsServer(null) { relay.serve(it) }.use { server ->
            val live = open(server, probeMillis = 80)
            try {
                val text = line.repeat(3840)
                val started = System.nanoTime()
                assertNull(live.session.paste(text))
                waitFor("the paste to leave", 30_000) { live.session.pasteLeft == 0 }
                waitFor("the relay to take it", 60_000) { relay.taken >= text.length }
                val seconds = (System.nanoTime() - started) / 1e9
                println("NETAUDIT paste of ${text.length} bytes with no echo: all taken by the relay in %.2f s".format(seconds))
                assertTrue(seconds < 15, "no echo is no reason to crawl: $seconds s")
                assertFalse(relay.wedged); assertNull(live.session.reason); assertNull(live.session.takePasteNote())
            } finally { live.session.close() }
        }
    }

    @Test fun echoThatStopsMidPasteEndsItWithASentenceAndNothingMoreIsPushedIntoTheServer() {
        val relay = Relay(echo = true, echoLimit = 3_000)
        MiniWsServer(null) { relay.serve(it) }.use { server ->
            val live = open(server, stallMillis = 500)
            try {
                val text = line.repeat(3840)
                assertNull(live.session.paste(text, bracketed = true))
                waitFor("the note", 20_000) { live.session.takePasteNote()?.also { println("NETAUDIT stalled paste note: $it") } != null }
                // The closing bracket still left, so the shell is not left believing a paste is going on.
                waitFor("the closing marker", 10_000) { relay.typed.endsWith(Char(27).toString() + "[201~") }
                assertTrue(relay.taken < 3_000 + TERMINAL_PASTE_WINDOW + 2 * TERMINAL_PASTE_FRAME_BYTES, "pushed ${relay.taken} bytes into a server that stopped echoing at 3,000")
                assertEquals(0, live.session.pasteLeft)
                assertNull(live.session.reason, "the terminal itself is still open")
                assertTrue(live.session.ready)
            } finally { live.session.close() }
        }
    }

    @Test fun stoppingAPasteThatIsWaitingForEchoReleasesTheWriterAtOnce() {
        val relay = Relay(echo = true, echoLimit = 2_000)
        MiniWsServer(null) { relay.serve(it) }.use { server ->
            val live = open(server, stallMillis = 60_000)                             // would wait a minute if nothing stopped it
            try {
                assertNull(live.session.paste(line.repeat(3840)))
                waitFor("the paste to stall at the window", 10_000) { relay.taken >= 2_000 + 4_000 }
                Thread.sleep(300)
                val taken = relay.taken
                live.session.stopPaste()
                assertEquals(0, live.session.pasteLeft)
                live.session.input("k")                                                // a key typed after it is not held behind it
                waitFor("the key", 3_000) { relay.typed.endsWith("k") }
                assertEquals(taken, relay.taken - 1, "no more of the paste was sent; only the key")
            } finally { live.session.close() }
        }
    }

    @Test fun anInterruptedSessionNeverLeavesThePacingLoopRunning() {
        val relay = Relay(echo = true, echoLimit = 1_000)
        MiniWsServer(null) { relay.serve(it) }.use { server ->
            val live = open(server, stallMillis = 60_000)
            assertNull(live.session.paste(line.repeat(3840)))
            waitFor("the writer to be waiting", 10_000) { relay.taken >= 1_000 + 4_000 }
            val started = System.nanoTime()
            live.session.close()
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1500)
            assertNotNull(live.session.reason ?: "closing yourself leaves no reason") // closed cleanly: no exception, no hang
        }
    }
}
