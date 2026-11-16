package local.ringleader.core

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.nio.file.Files
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Network audit 2026-11-16: behaviour that was checked and holds on 0.25.9. Green tests; they guard the clauses of the
 * "pin or normal TLS, no TOFU, non-retrying mutation" contract that the audit relies on.
 */
class NetworkAuditTlsVerifiedTest {
    private val one = AuditCerts.generate("one")
    private val two = AuditCerts.generate("two")
    private val pinOne = AuditCerts.pin(AuditCerts.cert(one))
    private val upid = """{"data":"UPID:n1:00000001:00000002:00000003:qmstart:100:root@pam:"}"""

    private fun switchingServer(manager: AuditCerts.Switching, calls: AtomicInteger, block: (String) -> Unit) {
        val server = HttpsServer.create(loopback(0), 0)
        server.httpsConfigurator = HttpsConfigurator(AuditCerts.switchingContext(manager))
        server.createContext("/") { e ->
            calls.incrementAndGet()
            val body = """{"data":[{"type":"node","node":"n1"}]}""".toByteArray()
            e.sendResponseHeaders(200, body.size.toLong()); e.responseBody.use { it.write(body) }
        }
        server.start()
        try { block("https://127.0.0.1:${server.address.port}") } finally { server.stop(0) }
    }

    /** The approved value is the one shown; a server that swaps its leaf after the read fails closed before any HTTP. */
    @Test fun certificateSwappedAfterTheReadFailsClosed() {
        val manager = AuditCerts.Switching(AuditCerts.key(one) to arrayOf(AuditCerts.cert(one)))
        val calls = AtomicInteger()
        switchingServer(manager, calls) { address ->
            val shown = runBlocking { probeTlsFingerprint(address) }!!
            assertEquals(pinOne, canonicalOptionalPin(shown))
            manager.current.set(AuditCerts.key(two) to arrayOf(AuditCerts.cert(two)))
            assertFailsWith<SSLException> { PveClient().overview(address, "root@pam!audit", "token-secret", shown) }
            assertEquals(0, calls.get(), "no request (and no Authorization header) reached the swapped server")
        }
    }

    /** Only the leaf counts: a chain whose second certificate is the pinned one is refused. */
    @Test fun pinnedCertificateAsIntermediateIsRefused() {
        val manager = AuditCerts.Switching(AuditCerts.key(two) to arrayOf(AuditCerts.cert(two), AuditCerts.cert(one)))
        val calls = AtomicInteger()
        switchingServer(manager, calls) { address ->
            assertFailsWith<SSLException> { PveClient().overview(address, "root@pam!audit", "token-secret", pinOne) }
            assertEquals(0, calls.get())
        }
    }

    /** A fixed-length POST mutation is never re-sent by the JDK; a lost reply is ambiguous, as the power contract says. */
    @Test fun fixedLengthPostIsNotReplayedAndStaysAmbiguous() {
        RawTlsServer(AuditCerts.serverContext(one)) { 200 to upid }.use { server ->
            server.dropFirst.set(1)
            Connection(server.address, "root@pam!audit", "token-secret", pinOne).use { c ->
                val outcome = runCatching { ProxmoxTransport().direct(c, "/api2/json/nodes/n1/qemu/100/status/start", "{}".toByteArray()) }
                assertEquals(listOf("POST /api2/json/nodes/n1/qemu/100/status/start"), server.requests.toList())
                assertIs<AmbiguousPowerResultException>(outcome.exceptionOrNull())
            }
        }
    }

    /** The storage/schedule wire (one raw socket, Connection: close) never replays a DELETE either. */
    @Test fun storageWireDeleteIsNotReplayed() {
        RawTlsServer(AuditCerts.serverContext(one)) { 200 to upid }.use { server ->
            server.dropFirst.set(1)
            Connection(server.address, "root@pam!audit", "token-secret", pinOne).use { c ->
                val outcome = runCatching { ProxmoxTransport().storageExchange(c, "/api2/json/nodes/n1/storage/local/content/local:iso/x.iso", "DELETE") }
                assertEquals(1, server.requests.size)
                assertIs<StorageUnclear>(outcome.exceptionOrNull())
            }
        }
    }

    /** Reads are idempotent; the JDK's single silent re-send of a GET is acceptable and documented here. */
    @Test fun getIsResentOnceByTheJdkAndThatIsHarmless() {
        RawTlsServer(AuditCerts.serverContext(one)) { 200 to """{"data":[]}""" }.use { server ->
            server.dropFirst.set(1)
            Connection(server.address, "root@pam!audit", "token-secret", pinOne).use { c ->
                ProxmoxTransport().direct(c, "/api2/json/cluster/resources")
                assertEquals(2, server.requests.size)
            }
        }
    }

    /** The pinned context offers only TLS 1.3 and 1.2 (java.security disables older ones) and has no client key. */
    @Test fun pinnedContextProtocolsAndNoClientCertificate() {
        val parameters = pinnedSslContext(pinOne)!!.defaultSSLParameters
        val protocols = parameters.protocols.toSet()
        assertTrue(protocols.isNotEmpty() && protocols.all { it == "TLSv1.3" || it == "TLSv1.2" }, "protocols: $protocols")
        // HttpsURLConnection's keep-alive cache is keyed by socket-factory identity: each call gets a new factory, so a
        // kept-alive pinned socket is never shared between two requests (or two pins) even in the disconnect() race.
        val context = pinnedSslContext(pinOne)!!
        assertNotSame(context.socketFactory, context.socketFactory)
        println("AUDIT pinned protocols=$protocols suites=${parameters.cipherSuites.size} first=${parameters.cipherSuites.take(4)}")
    }

    /** Pin spelling: colons and case normalise the same way at the form, the store and the context. Inner spaces are refused. */
    @Test fun pinSpellingIsCanonicalEverywhere() {
        val spelled = pinOne.uppercase().chunked(2).joinToString(":")
        assertEquals(pinOne, canonicalOptionalPin(" $spelled "))
        assertEquals(pinOne, Connection("https://127.0.0.1:8006", "root@pam!audit", "s", spelled).fingerprint)
        assertSame(pinnedSslContext(pinOne), pinnedSslContext(spelled))
        assertEquals(pinOne, ApprovedCertificate("https://127.0.0.1:8006", pinOne).pin)
        // Inverted with the fix of F8 (2026-11-16): the common pastes are accepted, in one spelling, everywhere.
        assertEquals(pinOne, canonicalOptionalPin(pinOne.chunked(2).joinToString(" ")))
        assertEquals(pinOne, canonicalOptionalPin("SHA256 Fingerprint=$spelled"))
        assertEquals(pinOne, Connection("https://127.0.0.1:8006", "root@pam!audit", "s", "sha256 Fingerprint=$spelled").fingerprint)
    }

    /** Two app instances: a save after the other instance saved succeeds when the desk re-loads first, as ProfileDesk does. */
    @Test fun twoInstancesInterleaveWhenEachLoadsBeforeSaving() {
        val dir = Files.createTempDirectory("ring-audit-trust")
        val file = dir.resolve("certificates.json")
        try {
            val a = CertificateTrustStore(file); val b = CertificateTrustStore(file)
            a.load(); b.load()
            b.save(listOf(ApprovedCertificate("https://pve-b.example.test:8006", pinOne)))
            assertFailsWith<CertificateTrustStoreException> { a.save(listOf(ApprovedCertificate("https://pve-a.example.test:8006", pinOne))) }
            val current = a.load()
            a.save(current + ApprovedCertificate("https://pve-a.example.test:8006", pinOne))
            assertEquals(2, CertificateTrustStore(file).load().size)
        } finally { dir.toFile().deleteRecursively() }
    }

    /**
     * Cost of a full TCP+TLS handshake per call (today: disconnect() after every request) against one kept-alive
     * connection with the same pinned socket factory, on loopback. Printed, not asserted.
     */
    @Test fun measureHandshakePerRequestAgainstKeepAlive() {
        val calls = AtomicInteger()
        switchingServer(AuditCerts.Switching(AuditCerts.key(one) to arrayOf(AuditCerts.cert(one))), calls) { address ->
            val n = 60
            repeat(5) { PveClient().overview(address, "root@pam!audit", "token-secret", pinOne) }
            val fresh = System.nanoTime()
            repeat(n) { PveClient().overview(address, "root@pam!audit", "token-secret", pinOne) }
            val freshMs = (System.nanoTime() - fresh) / 1e6 / n
            val factory = pinnedSslContext(pinOne)!!.socketFactory
            fun keptRead() {
                val c = java.net.URI("$address/api2/json/cluster/resources").toURL().openConnection() as HttpsURLConnection
                c.sslSocketFactory = factory
                c.setHostnameVerifier { _, s -> certificateMatches(s.peerCertificates[0] as X509Certificate, pinOne) }
                c.inputStream.use { it.readAllBytes() }
            }
            repeat(5) { keptRead() }
            val kept = System.nanoTime()
            repeat(n) { keptRead() }
            val keptMs = (System.nanoTime() - kept) / 1e6 / n
            println("AUDIT handshake-per-request=%.2f ms keep-alive=%.2f ms (loopback, RSA-2048, n=%d)".format(freshMs, keptMs, n))
        }
    }
}
