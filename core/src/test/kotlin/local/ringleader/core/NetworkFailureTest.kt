package local.ringleader.core

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.EOFException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.security.cert.CertPathBuilderException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.time.Instant
import java.time.ZoneOffset
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLParameters
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * The failure taxonomy (network audit 2026-11-16, W4 and memo 3c): twelve causes, mapped from exception classes and
 * cause chains, never from messages. The first three tests moved here unchanged from NetworkAuditTlsDefectTest when the
 * finding was fixed; the rest are the mapping table and real loopback servers for each cause.
 */
class NetworkFailureTest {
    private val store = AuditCerts.generate("leaf")
    private val pin = AuditCerts.pin(AuditCerts.cert(store))
    private val now = Instant.parse("2026-11-16T09:12:00Z")

    /**
     * F4. A pinned leaf that has expired (or is not yet valid because this computer's clock is wrong) gives the same
     * sentence as a changed certificate, and the certificate check shows the SAME fingerprint as the known pin, so the
     * desk offers no approval and no reason: Connect fails again with the same sentence. PveClient.kt:814, Workspace.kt:2780.
     */
    @Test fun expiredPinnedCertificateHasItsOwnMessage() {
        val expired = AuditCerts.generate("expired", "-startdate", "-10d")
        val expiredPin = AuditCerts.pin(AuditCerts.cert(expired))
        val server = HttpsServer.create(loopback(0), 0)
        server.httpsConfigurator = HttpsConfigurator(AuditCerts.serverContext(expired))
        server.createContext("/") { e -> e.sendResponseHeaders(200, 2); e.responseBody.use { it.write("{}".toByteArray()) } }
        server.start()
        try {
            val address = "https://127.0.0.1:${server.address.port}"
            val observed = runBlocking { probeTlsFingerprint(address) }
            assertEquals(expiredPin, canonicalOptionalPin(observed!!), "the probe reads the same leaf, so the desk sees no change")
            val failure = assertFailsWith<SSLException> { PveClient().overview(address, "root@pam!audit", "token-secret", expiredPin) }
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is CertificateExpiredException }, "cause is the expiry")
            assertNotEquals(CERTIFICATE_ERROR, readableError(failure), "an expired pinned certificate needs its own message (dates, clock)")
        } finally { server.stop(0) }
    }

    /** F5. A plain-HTTP listener on the port is reported as a certificate problem, and the desk then offers a certificate check. */
    @Test fun plainHttpOnTheHttpsPortIsNotACertificateProblem() {
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { plain ->
            val worker = Thread {
                runCatching {
                    plain.accept().use { s ->
                        s.getInputStream().read(ByteArray(512))
                        s.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                }
            }.apply { isDaemon = true; start() }
            val failure = assertFailsWith<SSLException> { PveClient().overview("https://127.0.0.1:${plain.localPort}", "root@pam!audit", "token-secret", pin) }
            worker.join(2000)
            assertNotEquals(CERTIFICATE_ERROR, readableError(failure), "plain HTTP on an HTTPS port is not an untrusted certificate")
        }
    }

    /** F5. Refused, timed out and unknown name are three different user problems with three different fixes. */
    @Test fun refusedTimedOutAndUnknownNameAreDistinguished() {
        val messages = listOf(ConnectException("Connection refused"), SocketTimeoutException("Connect timed out"),
            UnknownHostException("pve.example.test")).map { readableError(it) }
        assertEquals(3, messages.distinct().size, "readableError collapses: $messages")
    }


    private fun ssl(cause: Throwable?) = SSLHandshakeException("handshake").apply { if (cause != null) initCause(cause) }

    /** The mapping table. Messages are deliberately misleading: only classes and causes decide. */
    @Test fun eachExceptionShapeMapsToItsCause() {
        val key = "11".repeat(32)
        val table: List<Pair<Throwable, NetworkFailure?>> = listOf(
            ssl(PinMismatchException("ab".repeat(32), "22".repeat(32))) to NetworkFailure.Changed("ab".repeat(32), "22".repeat(32)),
            ssl(PinMismatchException("ab".repeat(32), key)) to NetworkFailure.RenewedSameKey("ab".repeat(32)),
            ssl(CertificateDateException(Instant.parse("2024-10-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"), now, true, CertificateExpiredException("x")))
                to NetworkFailure.CertificateDates(true, now, Instant.parse("2024-10-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z")),
            ssl(CertificateException("x", CertPathValidatorException("x", CertificateExpiredException("Connection refused")))) to NetworkFailure.CertificateDates(true, now),
            ssl(CertificateException("x", CertificateNotYetValidException("x"))) to NetworkFailure.CertificateDates(false, now),
            ssl(CertificateException("PKIX", CertPathBuilderException("unable to find valid certification path"))) to NetworkFailure.Untrusted,
            ssl(CertificateException("x", CertPathValidatorException("x", null, null, -1, CertPathValidatorException.BasicReason.UNDETERMINED_REVOCATION_STATUS))) to NetworkFailure.Untrusted,
            ssl(CertificateException("No subject alternative names matching IP address 127.0.0.1 found")) to NetworkFailure.NameMismatch,
            SSLException("Unsupported or unrecognized SSL message") to NetworkFailure.PlainHttp,
            SSLHandshakeException("Received fatal alert: protocol_version") to NetworkFailure.OldTls,
            ConnectException("Connection timed out") to NetworkFailure.Refused,
            SocketTimeoutException("Connection refused") to NetworkFailure.TimedOut,
            HttpConnectTimeoutException("x") to NetworkFailure.TimedOut,
            java.io.IOException("wrapped", UnknownHostException("pve.example.test")) to NetworkFailure.NameNotFound,
            NoRouteToHostException("x") to NetworkFailure.NoRoute,
            // A handshake the network cut off, with no certificate in the chain: its own cause, never a certificate sentence.
            ssl(EOFException("SSL peer shut down incorrectly")) to NetworkFailure.ClosedDuringHandshake,
            SSLException("x", SocketException("Connection reset")) to NetworkFailure.ClosedDuringHandshake,
            // None of the named causes: the caller keeps its general sentence.
            ssl(CertificateException("unclassified", RuntimeException())) to null,
            java.io.IOException("no route") to null,
            IllegalStateException("The server refused the request.") to null,
        )
        for ((error, expected) in table) assertEquals(expected, networkFailure(error, key, now), "$error")
        // Without a recorded key a changed leaf is only "changed".
        assertEquals(NetworkFailure.Changed("ab".repeat(32), key), networkFailure(ssl(PinMismatchException("ab".repeat(32), key)), null, now))
        // A loop in the causes ends the walk.
        val loop = SSLException("loop"); loop.initCause(RuntimeException("again", loop))
        assertNull(networkFailure(loop))
    }

    @Test fun everyCauseHasItsOwnSentenceAndNextStepWithoutInternals() {
        val all = NetworkFailure.examples(now)
        assertEquals(12, all.size)
        assertEquals(12, all.map { it.sentence }.distinct().size)
        assertEquals(12, all.map { it.nextStep }.distinct().size)
        val certificate = all.filter { it.certificate }.map { it::class.simpleName }
        assertEquals(listOf("Untrusted", "Changed", "RenewedSameKey", "CertificateDates", "NameMismatch"), certificate)
        for (failure in all) {
            val text = failure.sentence + " " + failure.nextStep
            assertFalse(Regex("Exception|java\\.|sun\\.|PKIX|\\bat \\w+\\.").containsMatchIn(text), text)
            assertFalse(Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b").containsMatchIn(text), "no address: $text")
            assertNotEquals(CERTIFICATE_ERROR, failure.sentence)
        }
    }

    @Test fun anOutOfDateCertificateSaysTheDatesAndThisComputersClock() {
        val utc = ZoneOffset.UTC
        assertEquals("2026-10-01", certificateDay(Instant.parse("2026-10-01T10:00:00Z"), utc))
        assertEquals("2026-11-16 09:12", clockReading(now, utc))
        val expired = NetworkFailure.CertificateDates(true, now, Instant.parse("2024-10-01T00:00:00Z"), Instant.parse("2026-10-01T12:00:00Z"))
        assertTrue(expired.sentence.contains("expired on ${certificateDay(Instant.parse("2026-10-01T12:00:00Z"))}"), expired.sentence)
        assertTrue(expired.sentence.contains("this computer's clock says ${clockReading(now)}"), expired.sentence)
        assertTrue("pvecm updatecerts --force" in expired.nextStep && "clock" in expired.nextStep)
        val early = NetworkFailure.CertificateDates(false, now, Instant.parse("2026-12-01T00:00:00Z"), Instant.parse("2028-12-01T00:00:00Z"))
        assertTrue(early.sentence.contains("valid only from ${certificateDay(Instant.parse("2026-12-01T00:00:00Z"))}"), early.sentence)
        assertTrue(NetworkFailure.CertificateDates(true, now).sentence.contains("has expired"))
    }

    // ---- real servers on loopback ----

    @Test fun aNotYetValidPinnedLeafIsADateFailureToo() {
        val early = AuditCerts.generate("early", "-startdate", "+10d")
        val server = HttpsServer.create(loopback(0), 0)
        server.httpsConfigurator = HttpsConfigurator(AuditCerts.serverContext(early))
        server.createContext("/") { e -> e.sendResponseHeaders(200, 2); e.responseBody.use { it.write("{}".toByteArray()) } }
        server.start()
        try {
            val failure = assertFailsWith<SSLException> { PveClient().overview("https://127.0.0.1:${server.address.port}", "root@pam!audit", "token-secret", AuditCerts.pin(AuditCerts.cert(early))) }
            val cause = assertIs<NetworkFailure.CertificateDates>(networkFailure(failure))
            assertFalse(cause.expired); assertEquals(AuditCerts.cert(early).notBefore.toInstant(), cause.notBefore)
        } finally { server.stop(0) }
    }

    @Test fun aChangedLeafIsChangedAndARenewalWithTheSameKeyIsSaidSo() = TrustFixtures.selfSigned("pve", "-ext", "SAN=ip:127.0.0.1").use { keys ->
        val oldPin = keys.pin(); val key = keys.spki()
        keys.reissue("-ext", "SAN=ip:127.0.0.1")
        FixtureHttps(keys.serverContext()).use { server ->
            val failure = assertFailsWith<SSLException> { PveClient().overview(server.address, "root@pam!audit", "token-secret", oldPin) }
            assertEquals(NetworkFailure.RenewedSameKey(keys.pin()), networkFailure(failure, key))
            assertEquals(NetworkFailure.Changed(keys.pin(), key), networkFailure(failure))
            TrustFixtures.selfSigned("other").use { other ->
                assertEquals(NetworkFailure.Changed(keys.pin(), key), networkFailure(failure, other.spki()), "a different recorded key is a change")
            }
            assertEquals(NetworkFailure.Changed().sentence, readableError(failure))
            assertEquals(0, server.requests.get(), "nothing was sent to the changed server")
        }
    }

    @Test fun aSelfSignedServerOnNormalTlsIsNotTrustedByThisComputer() = TrustFixtures.selfSigned("pve", "-ext", "SAN=ip:127.0.0.1").use { keys ->
        FixtureHttps(keys.serverContext()).use { server ->
            val failure = assertFailsWith<SSLException> { PveClient().overview(server.address, "root@pam!audit", "token-secret", "") }
            assertEquals(NetworkFailure.Untrusted, networkFailure(failure))
        }
    }

    @Test fun aTrustedChainForAnotherNameIsANameMismatch() = TrustFixtures.caSigned(san = "dns:other.example.test").use { signed ->
        FixtureHttps(signed.leaf.serverContext()).use { server ->
            val trust = PlatformTrust({ OsTrustStore(listOf(signed.ca), "test store") })
            val connection = URI.create("${server.address}/").toURL().openConnection() as HttpsURLConnection
            connection.sslSocketFactory = trust.sslContext().socketFactory
            val failure = assertFailsWith<SSLException> { try { connection.responseCode } finally { connection.disconnect() } }
            assertEquals(NetworkFailure.NameMismatch, networkFailure(failure))
        }
    }

    @Test fun aServerWithNoCommonTlsVersionIsAnOldServer() {
        // What a server that speaks only TLS 1.0/1.1 answers to a TLS 1.2/1.3 hello: a fatal protocol_version alert.
        val socket = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val alert = byteArrayOf(0x15, 0x03, 0x01, 0x00, 0x02, 0x02, 0x46)
        val worker = Thread { runCatching { socket.accept().use { it.getInputStream().read(ByteArray(4096)); it.getOutputStream().apply { write(alert); flush() }; Thread.sleep(500) } } }.apply { isDaemon = true; start() }
        try {
            val failure = assertFails { PveClient(2000, 2000).overview("https://127.0.0.1:${socket.localPort}", "root@pam!audit", "token-secret", pin) }
            assertEquals(NetworkFailure.OldTls, networkFailure(failure), "$failure")
        } finally { socket.close(); worker.join(2000) }
    }

    @Test fun aClosedPortIsRefusedAndASilentServerTimesOut() {
        val closed = ServerSocket(0).use { it.localPort }
        val refused = assertFails { PveClient(2000, 2000).overview("https://127.0.0.1:$closed", "root@pam!audit", "token-secret", pin) }
        assertEquals(NetworkFailure.Refused, networkFailure(refused), "$refused")
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { silent ->
            val timedOut = assertFails { PveClient(2000, 500).overview("https://127.0.0.1:${silent.localPort}", "root@pam!audit", "token-secret", pin) }
            assertEquals(NetworkFailure.TimedOut, networkFailure(timedOut), "$timedOut")
        }
        val unknown = assertFails { PveClient(2000, 2000).overview("https://no-such-host.invalid:8006", "root@pam!audit", "token-secret", pin) }
        assertEquals(NetworkFailure.NameNotFound, networkFailure(unknown), "$unknown")
    }

    @Test fun backgroundRunsSayTheCertificateChangedAndWhereToReviewIt() {
        assertEquals("The server's certificate changed since it was trusted; review it on the Desk. Nothing was sent.",
            scheduleNotSentReason(ssl(PinMismatchException("ab".repeat(32), null))))
        assertEquals("The server could not be reached securely. Nothing was sent.", scheduleNotSentReason(java.io.IOException("x")))
        assertEquals("${NetworkFailure.Refused.sentence} Nothing was sent.", scheduleNotSentReason(ConnectException("x")))
    }
}
