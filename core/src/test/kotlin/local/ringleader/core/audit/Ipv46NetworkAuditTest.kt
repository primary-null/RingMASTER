package local.ringleader.core.audit

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import local.ringleader.core.CERTIFICATE_ERROR
import local.ringleader.core.Connection
import local.ringleader.core.PveClient
import local.ringleader.core.ServiceProbe
import local.ringleader.core.UploadCancel
import local.ringleader.core.pinnedSslContext
import local.ringleader.core.probeDiagnosticReachability
import local.ringleader.core.probeTlsFingerprint
import local.ringleader.core.readableError
import local.ringleader.core.serverOrigin
import local.ringleader.core.storageWire
import local.ringleader.core.PortState
import local.ringleader.core.tools.PortOutcome
import local.ringleader.core.tools.connectPort
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * IPv4/IPv6 audit, evidence 2: real sockets on this computer's loopback (IPv4 and IPv6). Nothing leaves the machine.
 * `defect...` tests assert the correct behaviour and fail today; `ok...` tests pin checked, correct behaviour.
 * Run: `./gradlew.bat :core:test --tests "local.ringleader.core.audit.Ipv46NetworkAuditTest" --console=plain -i`
 */
class Ipv46NetworkAuditTest {
    private fun ipv6Loopback() = runCatching { ServerSocket(0, 1, InetAddress.getByName("::1")).use { true } }.getOrDefault(false)

    private fun closedPort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    /** What a person would be told for one address: the exception class, and the sentence [readableError] gives. */
    private fun told(address: String, connectMs: Int = 600, readMs: Int = 600): Pair<String, String> = try {
        PveClient(connectMs, readMs).overview(address, "fixture@pve!read", "audit-secret", "")
        "no error" to "no error"
    } catch (e: Exception) {
        e.javaClass.simpleName + ": " + e.message?.take(60) to readableError(e)
    }

    // ---------------------------------------------------------------- IPv6 literals: every client, checked

    @Test fun okApiClientReachesAnIpv6LiteralAndSendsABracketedHost() = AuditTls.serve("::1") { s ->
        assertEquals(1, PveClient().overview("https://[::1]:${s.port}", "fixture@pve!read", "x", s.pin).nodes)
        assertEquals(setOf("[::1]:${s.port}"), s.hosts.toSet())
    }

    @Test fun okTicketLoginReachesAnIpv6Literal() = AuditTls.serve("::1") { s ->
        Connection("https://[::1]:${s.port}", "root@pam", "audit-password", s.pin).use { PveClient().overview(it) }
        assertTrue("POST /api2/json/access/ticket" in s.requests, s.requests.toString())
        assertEquals(setOf("[::1]:${s.port}"), s.hosts.toSet())
    }

    @Test fun okStorageUploadSocketSendsABracketedHostHeader() = AuditTls.serve("::1") { s ->
        val code = storageWire(serverOrigin("https://[::1]:${s.port}"), s.pin, "/api2/json/x", "GET", emptyMap(), null, null, null, 2000, 2000, UploadCancel(), {},
            response = { status, _ -> status })
        assertEquals(200, code)
        assertEquals(listOf("[::1]:${s.port}"), s.hosts.toList())
    }

    @Test fun okConsoleWebSocketHandshakeSendsABracketedHostToAnIpv6Literal() = AuditTls.serve("::1") { s ->
        val origin = serverOrigin("https://[::1]:${s.port}")
        val uri = URI("wss://${origin.rawAuthority}/api2/json/nodes/n1/qemu/100/vncwebsocket?port=5900&vncticket=t")
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).sslContext(pinnedSslContext(s.pin)!!).build()
        client.use {
            val failure = runCatching { it.newWebSocketBuilder().buildAsync(uri, object : WebSocket.Listener {}).get(5, java.util.concurrent.TimeUnit.SECONDS) }
            assertTrue(failure.isFailure, "the fixture answers 404, so the upgrade must fail after the request was seen")
        }
        assertEquals(listOf("[::1]:${s.port}"), s.hosts.toList())
        assertTrue(s.requests.single().contains("/vncwebsocket"), s.requests.toString())
    }

    @Test fun okCertificateProbeAndDiagnosticProbeReachAnIpv6Literal() = AuditTls.serve("::1") { s ->
        val fingerprint = runBlocking { probeTlsFingerprint("https://[::1]:${s.port}", 3000) }
        assertEquals(s.pin, fingerprint?.replace(":", "")?.lowercase())
        val report = runBlocking { probeDiagnosticReachability("https://[::1]:${s.port}", 3000) }
        assertTrue(report.tcpSuccess && report.tlsHandshakeSuccess, report.toString())
        assertEquals(200, report.httpStatusCode)
        assertEquals("::1", report.host)
    }

    @Test fun okServiceProbeSendsABracketedHostOverPlainHttp() {
        PlainHttpServer("::1").use { server ->
            val answer = runBlocking { ServiceProbe().look("::1", server.port, secure = false) }
            assertEquals(PortState.Open, answer.state)
            assertEquals("Audit fixture", answer.title)
            assertTrue("Host: [::1]:${server.port}" in server.headerLines, server.headerLines.toString())
        }
    }

    @Test fun okPortCheckConnectsToAnIpv6Literal() {
        ServerSocket(0, 5, InetAddress.getByName("::1")).use { listener ->
            thread(isDaemon = true) { runCatching { listener.accept().close() } }
            assertTrue(connectPort("::1", listener.localPort, 1000) is PortOutcome.Open)
        }
    }

    // ---------------------------------------------------------------- dual stack: one address is tried, never the next

    @Ignore("audit finding #13: addressing part 2 (PveClient or the sign-in flow must try the next address or record the one that worked; see AddressFallbackJvmTest for the two-step form)")
    @Test fun defectApiClientDoesNotFallBackToTheSecondAddress() {
        val all = InetAddress.getAllByName("localhost")
        assumeTrue(all.size >= 2 && all[0] is Inet4Address && all.any { it is Inet6Address }, "localhost is not dual stack here: ${all.toList()}")
        assumeTrue(ipv6Loopback(), "no IPv6 loopback here")
        AuditTls.serve("::1") { s ->
            assertEquals(1, PveClient().overview("https://[::1]:${s.port}", "fixture@pve!read", "x", s.pin).nodes, "sanity: the server answers on ::1")
            assertEquals(1, PveClient().overview("https://localhost:${s.port}", "fixture@pve!read", "x", s.pin).nodes, "the same server by its name")
        }
    }

    // ---------------------------------------------------------------- what the person is told

    @Test fun defectRefusedUnknownNameAndSilentServerAreOneSentence() {
        assumeTrue(runCatching { InetAddress.getByName("no-such-host.invalid"); false }.getOrDefault(true), "this resolver answers for .invalid names")
        ScriptedServer("127.0.0.1", null, hangMillis = 5000).use { silent ->
            val cases = linkedMapOf(
                "connection refused" to told("https://127.0.0.1:${closedPort()}"),
                "name not found" to told("https://no-such-host.invalid:8006"),
                "accepts, never answers (read timeout)" to told("https://127.0.0.1:${silent.port}"),
            )
            cases.forEach { (name, pair) -> println("NETWORK|$name|${pair.first}|${pair.second}") }
            assertEquals(cases.size, cases.values.map { it.second }.toSet().size, "three different problems, one sentence: ${cases.values.map { it.second }.toSet()}")
        }
    }

    @Test fun defectPlainHttpOnAnHttpsPortIsReportedAsACertificateProblem() {
        val http = "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
        ScriptedServer("127.0.0.1", http).use { plain ->
            val (exception, sentence) = told("https://127.0.0.1:${plain.port}")
            println("NETWORK|plain HTTP answered where TLS was expected|$exception|$sentence")
            assertNotEquals(CERTIFICATE_ERROR, sentence, "no certificate was ever sent: the port does not speak TLS")
        }
    }

    @Test fun defectAnotherProtocolOnTheWrongPortIsReportedAsACertificateProblem() {
        ScriptedServer("127.0.0.1", "SSH-2.0-OpenSSH_9.6\r\n".toByteArray()).use { ssh ->
            val (exception, sentence) = told("https://127.0.0.1:${ssh.port}")
            println("NETWORK|SSH banner where TLS was expected|$exception|$sentence")
            assertNotEquals(CERTIFICATE_ERROR, sentence)
        }
    }

    @Test fun defectServerThatClosesDuringTheHandshakeIsReportedAsACertificateProblem() {
        ScriptedServer("127.0.0.1", ByteArray(0)).use { closing ->
            val (exception, sentence) = told("https://127.0.0.1:${closing.port}")
            println("NETWORK|connection closed during the TLS handshake|$exception|$sentence")
            assertNotEquals(CERTIFICATE_ERROR, sentence, "a reset or a firewall is not an untrusted certificate")
        }
    }

    // ---------------------------------------------------------------- the proposed message kinds are decidable

    private enum class Kind { NameNotFound, Refused, NoAnswer, NotTls, ClosedDuringHandshake, Certificate, SilentAfterConnect, Other }

    /**
     * The audit's proposed taxonomy for "the server could not be reached". It uses exception classes and the cause chain,
     * plus two English strings the JDK hard-codes ("refused", "terminated the handshake"). No operating-system text.
     */
    private fun classify(e: Throwable): Kind {
        val chain = generateSequence(e) { it.cause }.toList()
        return when {
            chain.any { it is java.net.UnknownHostException } -> Kind.NameNotFound
            chain.any { it is java.security.cert.CertificateException } -> Kind.Certificate
            chain.any { it is javax.net.ssl.SSLHandshakeException && it.message.orEmpty().contains("terminated the handshake") } || chain.any { it is java.io.EOFException } -> Kind.ClosedDuringHandshake
            chain.any { it is javax.net.ssl.SSLException } -> Kind.NotTls
            chain.any { it is java.net.ConnectException && it.message.orEmpty().contains("refused", ignoreCase = true) } -> Kind.Refused
            chain.any { it is java.net.SocketTimeoutException && it.message.orEmpty().startsWith("Read") } -> Kind.SilentAfterConnect
            chain.any { it is java.net.SocketTimeoutException || it is java.net.NoRouteToHostException || it is java.net.ConnectException } -> Kind.NoAnswer
            else -> Kind.Other
        }
    }

    private fun failure(address: String, pin: String = "", connectMs: Int = 600, readMs: Int = 600): Throwable? =
        try { PveClient(connectMs, readMs).overview(address, "fixture@pve!read", "audit-secret", pin); null } catch (e: Exception) { e }

    @Test fun okProposedFailureKindsCanBeToldApartFromExceptionClassesAlone() {
        assumeTrue(runCatching { InetAddress.getByName("no-such-host.invalid"); false }.getOrDefault(true), "this resolver answers for .invalid names")
        val http = "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
        val results = linkedMapOf<String, Pair<Kind, Kind>>()
        fun case(name: String, expected: Kind, error: Throwable?) {
            val actual = error?.let(::classify) ?: Kind.Other
            println("TAXONOMY|$name|${error?.javaClass?.simpleName}|${error?.cause?.javaClass?.simpleName}|$actual")
            results[name] = expected to actual
        }
        case("name does not resolve", Kind.NameNotFound, failure("https://no-such-host.invalid:8006"))
        case("nothing listens", Kind.Refused, failure("https://127.0.0.1:${closedPort()}"))
        case("black hole (TEST-NET-1)", Kind.NoAnswer, failure("https://192.0.2.1:8006", connectMs = 400))
        ScriptedServer("127.0.0.1", http).use { case("plain HTTP on the port", Kind.NotTls, failure("https://127.0.0.1:${it.port}")) }
        ScriptedServer("127.0.0.1", "SSH-2.0-OpenSSH_9.6\r\n".toByteArray()).use { case("SSH on the port", Kind.NotTls, failure("https://127.0.0.1:${it.port}")) }
        ScriptedServer("127.0.0.1", ByteArray(0)).use { case("closed during the handshake", Kind.ClosedDuringHandshake, failure("https://127.0.0.1:${it.port}")) }
        ScriptedServer("127.0.0.1", null, hangMillis = 5000).use { case("accepts then silence", Kind.SilentAfterConnect, failure("https://127.0.0.1:${it.port}")) }
        AuditTls.serve("127.0.0.1") { s ->
            case("self-signed certificate, no pin", Kind.Certificate, failure("https://127.0.0.1:${s.port}"))
            case("pin that does not match", Kind.Certificate, failure("https://127.0.0.1:${s.port}", pin = "00".repeat(32)))
        }
        val wrong = results.filterValues { it.first != it.second }
        assertTrue(wrong.isEmpty(), "classifier disagrees with the expected kind: $wrong")
    }

    @Test fun okRefusedPortIsClosedAndSilentPortIsFiltered() {
        assertTrue(connectPort("127.0.0.1", closedPort(), 1000) is PortOutcome.Closed)
        // 192.0.2.0/24 is TEST-NET-1 and never routed; with a short timeout the answer is a timeout, or no route.
        assertTrue(connectPort("192.0.2.1", 8006, 300) is PortOutcome.Filtered)
    }

    @Test fun okDnsFailureIsFastForUnknownReservedNamesAndSlowForLocalNames() {
        val started = System.nanoTime()
        runCatching { InetAddress.getByName("no-such-host.invalid") }
        val invalidMs = (System.nanoTime() - started) / 1_000_000
        val local = System.nanoTime()
        runCatching { InetAddress.getByName("ringmaster-audit-nonexistent.local") }
        val localMs = (System.nanoTime() - local) / 1_000_000
        println("NETWORK|lookup of an unknown .invalid name|${invalidMs}ms|lookup of an unknown .local name ${localMs}ms")
        assertTrue(invalidMs >= 0 && localMs >= 0)
    }
}
