package local.ringleader.core

import java.net.ServerSocket
import java.security.cert.CertificateExpiredException
import java.util.concurrent.ExecutionException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Network audit 2026-11-16, N-10: the consoles say when it was the server's certificate that was refused (a pin that no longer matches, an
 * untrusted or expired certificate, a name that does not match), and still say "lost" for a TLS failure that is not about a certificate.
 */
class ConsoleCertificateTest {
    private val base = "/api2/json/nodes/n1/qemu/100"
    private val vncJson = """{"data":{"port":5900,"ticket":"T-1","password":"pw"}}"""
    private val termJson = """{"data":{"port":5900,"ticket":"T-1","user":"root@pam!audit","upid":"UPID:fixture"}}"""
    private fun connection(port: Int, pin: String) = Connection("https://127.0.0.1:$port", "root@pam!audit", "SECRET-VALUE", pin)

    private fun assertCertificateSentence(message: String?) {
        assertEquals(CONSOLE_CERTIFICATE_REFUSED, message)
        for (secret in listOf("127.0.0.1", "T-1", "SECRET-VALUE", "wss://", "ticket", "fingerprint")) assertFalse(secret in message.orEmpty(), "'$secret' in '$message'")
        assertTrue("Desk" in message.orEmpty(), "it says where to go: $message")
    }

    @Test fun withoutAPinAnUntrustedCertificateIsSaidToBeTheCertificate() {
        MiniWsServer(NetAuditCerts.loopback.server) { it.close() }.use { server ->
            assertCertificateSentence(assertFails { openVncSession(connection(server.port, ""), base) { vncJson } }.message)
            assertCertificateSentence(assertFails { openTerminalSession(connection(server.port, ""), TerminalTarget("n1", "qemu", 100)) { termJson } }.message)
        }
    }

    @Test fun aTrustedCertificateForAnotherNameIsSaidToBeTheCertificate() {
        MiniWsServer(NetAuditCerts.otherName.server) { it.close() }.use { server ->
            withTrustedCertificate(NetAuditCerts.otherName) {
                assertCertificateSentence(assertFails { openVncSession(connection(server.port, ""), base) { vncJson } }.message)
                assertCertificateSentence(assertFails { openTerminalSession(connection(server.port, ""), TerminalTarget("n1", "qemu", 100)) { termJson } }.message)
            }
        }
    }

    @Test fun aTlsFailureThatIsNotAboutACertificateStaysALostConnection() {
        // A server that hangs up in the middle of the handshake: nothing was said about any certificate.
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { closer ->
            Thread { repeat(4) { runCatching { closer.accept().close() } } }.apply { isDaemon = true; start() }
            val vnc = assertFails { openVncSession(connection(closer.localPort, NetAuditCerts.loopback.pin), base) { vncJson } }
            assertEquals(VNC_LOST, vnc.message)
            val terminal = assertFails { openTerminalSession(connection(closer.localPort, NetAuditCerts.loopback.pin), TerminalTarget("n1", "qemu", 100)) { termJson } }
            assertEquals("The terminal could not connect.", terminal.message)
        }
    }

    @Test fun theCauseChainDecidesNotTheTypeOfTheOuterException() {
        val pin = java.security.cert.CertificateException("Certificate fingerprint changed")
        assertTrue(certificateRefused(ExecutionException(SSLHandshakeException("handshake failed").apply { initCause(pin) })))
        assertTrue(certificateRefused(ExecutionException(java.io.IOException("wrapped", CertificateExpiredException("expired")))))
        assertTrue(certificateRefused(SSLPeerUnverifiedException("peer not authenticated")))
        assertFalse(certificateRefused(SSLHandshakeException("Remote host terminated the handshake")))
        assertFalse(certificateRefused(ExecutionException(java.net.ConnectException("refused"))))
        assertFalse(certificateRefused(IllegalStateException("The console timed out.")))
    }
}
