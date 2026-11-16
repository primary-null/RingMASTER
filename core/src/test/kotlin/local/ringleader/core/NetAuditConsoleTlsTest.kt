package local.ringleader.core

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Network audit 2026-11-16: how the connection's certificate pin (or normal TLS) reaches every console channel, and that
 * the credential-bearing upgrade request leaves only after the certificate was accepted. Verified-correct evidence.
 *
 * Servers are local (loopback). A rejected handshake shows up as [MiniWsServer.upgrades] staying empty: not a header, not a
 * ticket in the query string, not a token reached the peer.
 */
class NetAuditConsoleTlsTest {
    private val base = "/api2/json/nodes/n1/qemu/100"
    private val vncJson = """{"data":{"port":5900,"ticket":"T-1","password":"pw"}}"""
    private val termJson = """{"data":{"port":5900,"ticket":"T-1","user":"root@pam!audit","upid":"UPID:fixture"}}"""
    private fun connection(port: Int, pin: String, host: String = "127.0.0.1") =
        Connection("https://$host:$port", "root@pam!audit", "SECRET-VALUE", pin)

    private fun assertNothingSent(server: MiniWsServer) {
        assertTrue(server.upgrades.isEmpty(), "no upgrade request (ticket, token) may reach a peer whose certificate was refused")
    }
    private fun assertTokenUpgrade(upgrade: WsUpgrade, authority: String) {
        assertTrue(upgrade.requestLine.startsWith("GET $base/vncwebsocket?port=5900&vncticket=T-1 HTTP/1.1"), upgrade.requestLine)
        assertEquals("PVEAPIToken=root@pam!audit=SECRET-VALUE", upgrade.headers["authorization"])
        assertNull(upgrade.headers["cookie"])
        assertEquals(authority, upgrade.headers["host"])
        assertEquals("binary", upgrade.headers["sec-websocket-protocol"])
    }

    // ---- pinned context: VNC, terminal, still ----

    /**
     * N-10, fixed: a refused certificate used to read like a lost connection ("The connection was lost." for the VNC console, "The terminal
     * could not connect." for the terminal), so a changed certificate could not be told from a network drop. Both now say it is the certificate.
     */
    @Test fun vncAndTerminalRefuseAWrongPinBeforeSendingAnythingAndSayItIsTheCertificate() {
        MiniWsServer(NetAuditCerts.loopback.server).use { server ->
            val failure = assertFails { openVncSession(connection(server.port, "00".repeat(32)), base) { vncJson } }
            assertFalse("T-1" in failure.message.orEmpty() || "wss://" in failure.message.orEmpty(), "ticket or URL leaked into the error: ${failure.message}")
            assertNothingSent(server)
            assertEquals(CONSOLE_CERTIFICATE_REFUSED, failure.message)
            val terminal = assertFails { openTerminalSession(connection(server.port, "00".repeat(32)), TerminalTarget("n1", "qemu", 100)) { termJson } }
            assertEquals(CONSOLE_CERTIFICATE_REFUSED, terminal.message)
            val still = assertFails { captureConsoleStill(connection(server.port, "00".repeat(32)), base) { vncJson } }
            println("NETAUDIT wrong-pin messages: vnc='${failure.message}' terminal='${terminal.message}' still=${still.javaClass.simpleName}")
        }
    }

    @Test fun vncWebsocketWithTheRightPinSendsTheTokenAndTicketOnce() {
        MiniWsServer(NetAuditCerts.loopback.server) { it.close() }.use { server ->
            assertFails { openVncSession(connection(server.port, NetAuditCerts.loopback.pin), base) { vncJson } } // the peer hangs up: no RFB
            assertEquals(1, server.upgrades.size)
            assertTokenUpgrade(server.upgrades[0], "127.0.0.1:${server.port}")
        }
    }

    @Test fun terminalWebsocketRefusesAWrongPinAndSendsTheTicketOnlyAfterTheRightOne() {
        MiniWsServer(NetAuditCerts.loopback.server) { conn -> conn.sendBinary("OK".toByteArray()) }.use { server ->
            assertFails { openTerminalSession(connection(server.port, "00".repeat(32)), TerminalTarget("n1", "qemu", 100)) { termJson } }
            assertNothingSent(server)
        }
        var greeting: WsFrame? = null
        MiniWsServer(NetAuditCerts.loopback.server) { conn -> greeting = conn.received.poll(10, TimeUnit.SECONDS); conn.sendBinary("OK".toByteArray()) }.use { server ->
            val session = openTerminalSession(connection(server.port, NetAuditCerts.loopback.pin), TerminalTarget("n1", "qemu", 100)) { termJson }
            try {
                val deadline = System.nanoTime() + 5_000_000_000
                while (greeting == null && System.nanoTime() < deadline) Thread.sleep(10)
                assertEquals("root@pam!audit:T-1\n", greeting?.text)   // the one-time ticket travels only inside the verified channel
                assertEquals(1, server.upgrades.size)
                assertEquals("PVEAPIToken=root@pam!audit=SECRET-VALUE", server.upgrades[0].headers["authorization"])
            } finally { session.close() }
        }
    }

    @Test fun stillCaptureRefusesAWrongPinBeforeSendingAnything() {
        MiniWsServer(NetAuditCerts.loopback.server) { it.close() }.use { server ->
            assertFails { captureConsoleStill(connection(server.port, "00".repeat(32)), base) { vncJson } }
            assertNothingSent(server)
        }
        MiniWsServer(NetAuditCerts.loopback.server) { it.close() }.use { server ->
            assertFails { captureConsoleStill(connection(server.port, NetAuditCerts.loopback.pin), base) { vncJson } }
            assertEquals(1, server.upgrades.size)
            assertTokenUpgrade(server.upgrades[0], "127.0.0.1:${server.port}")
        }
    }

    // ---- no pin: platform trust and host name checking, no fallback to trust-all ----

    @Test fun withoutAPinAnUntrustedCertificateIsRefusedOnEveryChannel() {
        MiniWsServer(NetAuditCerts.loopback.server) { it.close() }.use { server ->
            assertFails { openVncSession(connection(server.port, ""), base) { vncJson } }
            assertFails { openTerminalSession(connection(server.port, ""), TerminalTarget("n1", "qemu", 100)) { termJson } }
            assertFails { captureConsoleStill(connection(server.port, ""), base) { vncJson } }
            assertNothingSent(server)
        }
    }

    @Test fun withoutAPinATrustedCertificateForAnotherNameIsRefused() {
        // The default context trusts this certificate, but it is only valid for other.invalid: 127.0.0.1 must not match.
        MiniWsServer(NetAuditCerts.otherName.server) { it.close() }.use { server ->
            withTrustedCertificate(NetAuditCerts.otherName) {
                assertFails { openVncSession(connection(server.port, ""), base) { vncJson } }
                assertFails { openTerminalSession(connection(server.port, ""), TerminalTarget("n1", "qemu", 100)) { termJson } }
                assertFails { captureConsoleStill(connection(server.port, ""), base) { vncJson } }
            }
            assertNothingSent(server)
        }
    }

    @Test fun withoutAPinATrustedCertificateWithAnIpAddressNameIsAccepted() {
        MiniWsServer(NetAuditCerts.loopback.server) { it.close() }.use { server ->
            withTrustedCertificate(NetAuditCerts.loopback) {
                assertFails { openVncSession(connection(server.port, ""), base) { vncJson } }   // fails later, at the RFB hello
            }
            assertEquals(1, server.upgrades.size, "the normal-TLS path connects when the certificate is trusted and names this address")
            assertTokenUpgrade(server.upgrades[0], "127.0.0.1:${server.port}")
        }
    }

    // ---- IPv6 literal origins ----

    @Test fun ipv6LiteralOriginPinnedAndPlatformTrust() {
        assumeIpv6()
        MiniWsServer(NetAuditCerts.loopback.server, "::1") { it.close() }.use { server ->
            assertFails { openVncSession(connection(server.port, NetAuditCerts.loopback.pin, "[::1]"), base) { vncJson } }
            assertEquals(1, server.upgrades.size, "pinned IPv6 literal connects")
            assertTokenUpgrade(server.upgrades[0], "[::1]:${server.port}")
        }
        MiniWsServer(NetAuditCerts.loopback.server, "::1") { it.close() }.use { server ->
            withTrustedCertificate(NetAuditCerts.loopback) {
                assertFails { openVncSession(connection(server.port, "", "[::1]"), base) { vncJson } }
            }
            assertEquals(1, server.upgrades.size, "platform-trust IPv6 literal matches an IP SAN")
        }
    }

    @Test fun connectionOriginKeepsBracketsAndTheWebsocketUriIsWellFormed() {
        val origin = serverOrigin("https://[2001:db8::1]:8006")
        assertEquals("[2001:db8::1]:8006", origin.rawAuthority)
        val uri = java.net.URI("wss://${origin.rawAuthority}$base/vncwebsocket?port=5900&vncticket=T-1")
        assertEquals("[2001:db8::1]", uri.host)
        assertEquals(8006, uri.port)
        assertNotNull(uri.rawQuery)
    }
}
