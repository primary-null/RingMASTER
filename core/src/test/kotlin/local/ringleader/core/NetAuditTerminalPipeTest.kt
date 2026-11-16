package local.ringleader.core

import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Network audit 2026-11-16, finding N-01 "terminal dies after about a minute of silence", now FIXED.
 *
 * [TerminalModel] used to hand host output to JediTerm through a PipedOutputStream/PipedInputStream. A pipe remembers the
 * LAST WRITER THREAD and fails when that thread has died ("Write end dead" / "Pipe broken" on the reading side, then "Read
 * end dead" on the next write). The JDK HttpClient delivers WebSocket callbacks on pool threads that end after 60 s without
 * work, so a terminal that was quiet for a minute lost its emulator thread (the loop swallowed the exception) and the next
 * byte of host output ended the session as "The terminal connection was lost.".
 *
 * The hand-off is now a [HostOutputStream], which has no rule about the writing thread. These tests keep the audit's scenarios
 * and assert the right behaviour; they failed against the pipe.
 */
class NetAuditTerminalPipeTest {
    private fun waitFor(what: String, millis: Long = 5000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (!condition()) { check(System.nanoTime() < deadline) { "timed out waiting for $what" }; Thread.sleep(10) }
    }

    @Test fun modelKeepsAcceptingOutputAfterTheThreadThatFedItHasDied() {
        TerminalModel(40, 5).use { model ->
            // Phase 1: some thread (a pool thread in production) feeds the first bytes and later ends.
            val writer = Thread { model.feed("first".toByteArray()); Thread.sleep(1000) }.apply { start() }
            waitFor("first output") { model.buffer.getScreenLines().contains("first") }
            writer.join()
            // Phase 2: quiet. The pipe failed its reader 3.0 s after the writer thread ended (measured, 3 of 3 runs); wait well past that.
            Thread.sleep(6000)
            // Phase 3: the next frame arrives on a different thread, as the next callback of the websocket pool does.
            val next = Thread { model.feed("second".toByteArray()) }.apply { start() }
            next.join()
            waitFor("second output") { model.buffer.getScreenLines().contains("firstsecond") }
        }
    }

    /**
     * The real classes over TLS: a pinned HttpClient built the way [openTerminalSession] builds it, the real [TerminalSession],
     * a local server that sends the "OK" greeting and a prompt, stays quiet for 75 s and then sends one more frame.
     * The session must be alive and show it.
     */
    @Test fun sessionOverTlsSurvivesAMinuteOfSilence() {
        assumeSlowAudit()
        val cert = NetAuditCerts.loopback
        val idleSeconds = 75L
        MiniWsServer(cert.server) { conn ->
            conn.received.poll(20, TimeUnit.SECONDS)                       // the "user:ticket" greeting
            conn.sendBinary("OKlogin: ".toByteArray())
            Thread.sleep(idleSeconds * 1000)
            runCatching { conn.sendBinary("AFTER-QUIET".toByteArray()) }
            Thread.sleep(5000)
        }.use { server ->
            val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(20))
                .sslContext(pinnedSslContext(cert.pin)!!).build()
            val session = TerminalSession(TerminalModel(40, 5), http)
            try {
                val ws = http.newWebSocketBuilder().subprotocols("binary")
                    .buildAsync(URI("wss://127.0.0.1:${server.port}/api2/json/nodes/n1/lxc/101/vncwebsocket?port=5900&vncticket=t"), session)
                    .get(20, TimeUnit.SECONDS)
                session.start(ws, TermProxy(5900, "t", "root@pam", "UPID:fixture"))
                waitFor("prompt", 10_000) { session.model.buffer.getScreenLines().contains("login:") }
                assertTrue(session.ready)
                Thread.sleep((idleSeconds + 8) * 1000)
                val screen = session.model.buffer.getScreenLines()
                println("NETAUDIT terminal after ${idleSeconds}s quiet: reason=${session.reason} ready=${session.ready} sawAfterQuiet=${screen.contains("AFTER-QUIET")}")
                // Fixed: the frame after the quiet period is drawn and the session is still connected.
                assertTrue(screen.contains("AFTER-QUIET"), screen)
                assertNull(session.reason)
                assertTrue(session.ready)
            } finally { session.close() }
        }
    }

    @Test fun controlSessionKeepsWorkingWhenOutputKeepsFlowing() {
        // The same feed from one long-lived thread was fine before too: this is why the existing tests never saw the defect.
        TerminalModel(40, 5).use { model ->
            model.feed("a".toByteArray()); Thread.sleep(4000); model.feed("b".toByteArray())
            waitFor("both") { model.buffer.getScreenLines().contains("ab") }
        }
    }
}
