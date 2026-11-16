package local.ringleader.core

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertPathValidatorException
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the platform's own TLS validation says about a server, asked before anything is trusted by hand. The servers are
 * local and self-signed, as a Proxmox node presents itself. Nothing here changes a global JVM setting: a certificate the
 * platform accepts is made by giving the probe a trust context of its own.
 */
class NormalTlsProbeTest {
    /** A local HTTPS server with a self-signed certificate. [requests] counts the HTTP requests it was ever sent. */
    private class Server(val port: Int, val certificate: Certificate, val requests: AtomicInteger) {
        val address get() = "https://127.0.0.1:$port"

        /** A client context that trusts this certificate and nothing else, as if the platform's own store held it. */
        fun trusting(): SSLContext {
            val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("fixture", certificate) }
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(anchors) }
            return SSLContext.getInstance("TLS").apply { init(null, factory.trustManagers, null) }
        }
    }

    /** [san] names what the certificate is for (keytool syntax). */
    private fun tlsServer(san: String = "ip:127.0.0.1", block: suspend (Server) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("ring-leader-normal-tls")
        val key = dir.resolve("fixture.p12")
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool"
        val command = listOf(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
            "-dname", "CN=fixture.invalid", "-ext", "SAN=$san", "-storetype", "PKCS12", "-keystore", key.toString(),
            "-storepass", "fixture-only", "-keypass", "fixture-only", "-noprompt")
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes()
        check(process.waitFor() == 0) { String(output) }
        val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(key).use { load(it, "fixture-only".toCharArray()) } }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "fixture-only".toCharArray()) }
        val requests = AtomicInteger()
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) })
        server.createContext("/") { exchange -> requests.incrementAndGet(); exchange.sendResponseHeaders(401, -1); exchange.close() }
        server.start()
        try { block(Server(server.address.port, store.getCertificate("fixture"), requests)) }
        finally { server.stop(0); Files.deleteIfExists(key); Files.deleteIfExists(dir) }
    }

    /** A TCP listener on loopback that does not speak TLS. [reply] goes to each client, which is then closed; null keeps clients open and silent. */
    private class Listener(private val reply: ByteArray?) : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val held = Collections.synchronizedList(mutableListOf<Socket>())
        val port get() = socket.localPort
        val accepted = CompletableDeferred<Unit>()
        init {
            thread(isDaemon = true) {
                try {
                    while (true) {
                        val client = socket.accept()
                        if (reply == null) held += client else { runCatching { client.getOutputStream().apply { write(reply); flush() } }; runCatching { client.close() } }
                        accepted.complete(Unit)
                    }
                } catch (_: Exception) { /* closed */ }
            }
        }
        override fun close() { runCatching { socket.close() }; synchronized(held) { held.forEach { runCatching { it.close() } } } }
    }

    @Test fun aSelfSignedServerIsRefusedAndNothingIsSentToIt() = tlsServer { server ->
        // The JVM's own trust does not know this certificate, which is the case for a Proxmox node with its own CA.
        assertEquals(NormalTls.Refused, probeNormalTls(server.address))
        assertEquals(0, server.requests.get())
    }

    @Test fun aCertificateThePlatformTrustsIsAcceptedWithoutARequest() = tlsServer { server ->
        assertEquals(NormalTls.Accepted, probeTlsValidation(server.address, 4000, server.trusting()))
        assertEquals(0, server.requests.get())
    }

    @Test fun aTrustedCertificateThatDoesNotNameTheHostIsRefused() = tlsServer(san = "dns:fixture.invalid") { server ->
        // The chain is trusted; the address 127.0.0.1 is not among the names it is good for.
        assertEquals(NormalTls.Refused, probeTlsValidation(server.address, 4000, server.trusting()))
        assertEquals(0, server.requests.get())
    }

    @Test fun aClosedPortIsUnknown() = runBlocking {
        val closed = ServerSocket(0).use { it.localPort }
        assertEquals(NormalTls.Unknown, probeNormalTls("https://127.0.0.1:$closed", timeoutMs = 500))
    }

    @Test fun anAddressThatIsNotAnHttpsOriginIsUnknownEvenWithATlsServerThere() = tlsServer { server ->
        assertEquals(NormalTls.Unknown, probeNormalTls("http://127.0.0.1:${server.port}", timeoutMs = 500))
        assertEquals(NormalTls.Unknown, probeNormalTls("127.0.0.1:${server.port}", timeoutMs = 500))
        assertEquals(NormalTls.Unknown, probeNormalTls("https://127.0.0.1:${server.port}/login", timeoutMs = 500))
        assertEquals(NormalTls.Unknown, probeNormalTls("", timeoutMs = 500))
        assertEquals(NormalTls.Unknown, probeNormalTls("   ", timeoutMs = 500))
        assertEquals(0, server.requests.get())
    }

    @Test fun aServerThatDoesNotSpeakTlsIsUnknown() = runBlocking {
        // Plain HTTP on the port, and a connection that is closed on the first byte: neither says anything about a certificate.
        Listener("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray()).use { plain ->
            assertEquals(NormalTls.Unknown, probeNormalTls("https://127.0.0.1:${plain.port}", timeoutMs = 2000))
        }
        Listener(ByteArray(0)).use { closing ->
            assertEquals(NormalTls.Unknown, probeNormalTls("https://127.0.0.1:${closing.port}", timeoutMs = 2000))
        }
    }

    @Test fun aServerThatNeverAnswersTimesOutAsUnknown() = runBlocking {
        Listener(null).use { silent ->
            val started = System.nanoTime()
            assertEquals(NormalTls.Unknown, probeNormalTls("https://127.0.0.1:${silent.port}", timeoutMs = 300))
            assertTrue(System.nanoTime() - started < 10_000_000_000L, "the probe ends when its time is up")
            assertTrue(silent.accepted.isCompleted, "the probe did reach the server")
        }
    }

    @Test fun cancellationClosesTheSocketInsteadOfWaitingForTheTimeout() = runBlocking {
        Listener(null).use { silent ->
            // A minute to answer. Only closing the socket on cancellation ends this within the wait below.
            val probe = launch(Dispatchers.Default) { probeNormalTls("https://127.0.0.1:${silent.port}", timeoutMs = 60_000) }
            withTimeout(10_000) { silent.accepted.await() }
            withTimeout(10_000) { probe.cancelAndJoin() }
            assertTrue(probe.isCancelled)
        }
    }

    @Test fun aFailureCountsAsARefusalOnlyWhenItIsAboutTheCertificate() {
        val untrusted = SSLHandshakeException("PKIX path building failed").apply { initCause(CertificateException("unable to find valid certification path")) }
        assertTrue(refusedOnCertificate(untrusted))
        assertTrue(refusedOnCertificate(SSLException("handshake failed", RuntimeException("wrapped", CertificateException("No name matching host found")))))
        assertTrue(refusedOnCertificate(SSLPeerUnverifiedException("peer not authenticated")))
        // An expired certificate, in the shape the JDK reports it. A fixture cannot make one: the JDK does not check the dates of a
        // certificate that is itself the trust anchor, which is all a self-signed server can be.
        val expired = SSLHandshakeException("PKIX path validation failed").apply {
            initCause(CertificateException("PKIX path validation failed", CertPathValidatorException("validity check failed", CertificateExpiredException("NotAfter: Mon Nov 09 12:00:00 UTC 2026"))))
        }
        assertTrue(refusedOnCertificate(expired))
        // TLS failures that say nothing about the certificate.
        assertFalse(refusedOnCertificate(SSLHandshakeException("Remote host terminated the handshake")))
        assertFalse(refusedOnCertificate(SSLHandshakeException("Received fatal alert: handshake_failure")))
        assertFalse(refusedOnCertificate(SSLException("Unsupported or unrecognized SSL message")))
        // Not TLS failures at all.
        assertFalse(refusedOnCertificate(SocketTimeoutException("Read timed out")))
        assertFalse(refusedOnCertificate(java.net.ConnectException("Connection refused")))
        assertFalse(refusedOnCertificate(java.net.SocketException("Connection reset")))
        assertFalse(refusedOnCertificate(java.net.UnknownHostException("pve.example.test")))
        assertFalse(refusedOnCertificate(CertificateException("not reported by a TLS layer")))
        // Causes that loop back on themselves end the walk.
        val loop = SSLException("loop")
        loop.initCause(RuntimeException("again", loop))
        assertFalse(refusedOnCertificate(loop))
    }
}
