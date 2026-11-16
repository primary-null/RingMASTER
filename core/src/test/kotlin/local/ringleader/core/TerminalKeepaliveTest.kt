package local.ringleader.core

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Network audit 2026-11-16, N-08: a terminal that notices a dead network. The live VNC session pings every 30 seconds and ends
 * when a ping goes unanswered for a period; the terminal used to send only a message nothing answers, so a computer that
 * slept or a network mapping that disappeared left it "connected" until TCP gave up. These tests run the real session over a
 * real websocket with the period shortened to one second.
 */
class TerminalKeepaliveTest {
    private fun waitFor(what: String, millis: Long = 8000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (!condition()) { check(System.nanoTime() < deadline) { "timed out waiting for $what" }; Thread.sleep(10) }
    }

    private class Live(val session: TerminalSession, val ws: WebSocket, val http: HttpClient)

    private fun open(server: MiniWsServer, pingSeconds: Long = 1): Live {
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val session = TerminalSession(TerminalModel(40, 5), http, pingSeconds)
        val ws = http.newWebSocketBuilder().subprotocols("binary").buildAsync(URI("ws://127.0.0.1:${server.port}/vncwebsocket?port=5900&vncticket=t"), session).get(10, TimeUnit.SECONDS)
        session.start(ws, TermProxy(5900, "t", "root@pam", "UPID:fixture"))
        return Live(session, ws, http)
    }

    private fun greet(conn: WsConn) { conn.received.poll(10, TimeUnit.SECONDS); conn.sendBinary("OKprompt# ".toByteArray()) }

    private fun pingsSeen(conn: WsConn) = conn.received.count { it.opcode == 9 }

    @Test fun aServerThatStopsAnsweringPingsEndsTheSessionWithASentenceThatOffersReconnect() {
        var connection: WsConn? = null
        MiniWsServer(null) { conn -> connection = conn; conn.ignorePings = true; greet(conn); Thread.sleep(30_000) }.use { server ->
            val live = open(server)
            val redraws = AtomicInteger()
            live.session.model.onUpdate = { redraws.incrementAndGet() }
            try {
                waitFor("the prompt") { live.session.ready }
                val before = redraws.get()
                waitFor("the session to give up") { live.session.reason != null }
                assertEquals(TERMINAL_NO_ANSWER, live.session.reason)
                assertTrue("lost" in live.session.reason!! && "Reconnect" in live.session.reason!!, live.session.reason)
                assertFalse(live.session.ready)
                assertTrue(redraws.get() > before, "the screen is told, so the toolbar shows the sentence while no output is coming")
                // The beats stop with the session: nothing is sent into the closed channel afterwards.
                val seen = pingsSeen(connection!!)
                Thread.sleep(2500)
                assertEquals(seen, pingsSeen(connection!!), "no pings after the session ended")
            } finally { live.session.close() }
        }
    }

    @Test fun aServerThatAnswersKeepsTheSessionAliveAcrossManyBeatsAndKeepsReceivingTheOldHeartbeat() {
        var connection: WsConn? = null
        MiniWsServer(null) { conn -> connection = conn; greet(conn); Thread.sleep(30_000) }.use { server ->
            val live = open(server)
            try {
                waitFor("the prompt") { live.session.ready }
                Thread.sleep(5200)
                assertNull(live.session.reason)
                assertTrue(live.session.ready)
                assertTrue(pingsSeen(connection!!) >= 4, "pings: ${pingsSeen(connection!!)}")
                // The application heartbeat Proxmox's own terminal sends stays alongside the ping.
                assertTrue(connection!!.received.count { it.opcode == 1 && it.text == "2" } >= 4)
            } finally { live.session.close() }
        }
    }

    @Test fun outputCountsAsAnAnswerWhenAPongIsQueuedBehindIt() {
        // A pong can wait behind a flood of output; the session must not read that as a dead connection.
        MiniWsServer(null) { conn ->
            conn.ignorePings = true
            greet(conn)
            repeat(14) { runCatching { conn.sendBinary(".".toByteArray()) }; Thread.sleep(400) }
        }.use { server ->
            val live = open(server)
            try {
                waitFor("the prompt") { live.session.ready }
                Thread.sleep(5200)
                assertNull(live.session.reason, "output kept arriving, so the server was clearly there")
                assertTrue(live.session.ready)
            } finally { live.session.close() }
        }
    }

    @Test fun closingTheSessionStopsTheBeats() {
        var connection: WsConn? = null
        MiniWsServer(null) { conn -> connection = conn; greet(conn); Thread.sleep(30_000) }.use { server ->
            val live = open(server)
            waitFor("the prompt") { live.session.ready }
            Thread.sleep(1800)
            live.session.close()
            val seen = pingsSeen(connection!!)
            Thread.sleep(2500)
            assertEquals(seen, pingsSeen(connection!!))
            assertNull(live.session.reason, "closing it yourself is not a lost connection")
        }
    }
}
