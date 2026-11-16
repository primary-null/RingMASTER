package local.ringleader.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import local.ringleader.core.tools.HttpTlsPlugin
import local.ringleader.core.tools.ToolInput
import local.ringleader.core.tools.ToolInputException
import local.ringleader.core.tools.ToolInputKind
import local.ringleader.core.tools.iperfCommand
import local.ringleader.core.tools.parsePingLatency
import local.ringleader.core.tools.parsePingReport
import local.ringleader.core.tools.validateHost
import local.ringleader.core.tools.validateHttpUrl
import local.ringleader.core.tools.validateToolValue

/**
 * Network audit 2026-11-16: the tools and helper processes. Mixed: verified-correct checks (option injection, planted
 * executables, trust-all confinement, no credentials) and characterizations of defects (IPv6 literal URLs, Cyrillic ping units).
 */
class NetAuditToolsTest {
    private val hostInput = ToolInput("host", "Host", ToolInputKind.Host)

    // ---- argument injection ----

    @Test fun aHostNameThatStartsLikeAnOptionIsNeverAccepted() {
        val options = listOf("-n", "--help", "-c1", "-w", "-", "--", "-1.2.3.4", "\u2212n", "-oProxyCommand=x", "/?", "-h", " -n", "\t--version")
        for (value in options) assertFailsWith<ToolInputException>(value) { validateToolValue(hostInput, value) }
        for (value in listOf("192.0.2.1", "2001:db8::1", "pve.example.test", "xn--bcher-kva.example", "a-b.c")) assertEquals(value, validateToolValue(hostInput, value))
    }

    @Test fun commandsAreBuiltAsArgumentListsWithTheHostLast() {
        val iperf = iperfCommand("C:\\tools\\iperf3.exe", false, "192.0.2.9", 5201, 10, 1, false, false)
        assertEquals("192.0.2.9", iperf[iperf.indexOf("-c") + 1])
        // Server mode: no -B, so iperf3 listens on every address (user confirmation is the only guard, and -1 ends it after one test).
        val server = iperfCommand("iperf3", true, "", 5201, 10, 1, false, false)
        assertFalse("-B" in server)
        assertTrue("-1" in server && "-s" in server)
    }

    // ---- planted executables ----

    @Test fun executableLookupUsesOnlyExplicitPathEntriesNeverTheWorkingDirectory() {
        val dir = Files.createTempDirectory("ringmaster-planted")
        try {
            Files.writeString(dir.resolve("ping.exe"), "MZ")
            Files.writeString(dir.resolve("ping"), "x")
            // An empty PATH, blank entries and an empty entry between separators are not "the current directory" here.
            assertNull(findExecutableIn("ping", "", emptyList()))
            assertNull(findExecutableIn("ping", java.io.File.pathSeparator + java.io.File.pathSeparator, emptyList()))
            assertNull(findExecutableIn("ping", null, emptyList()))
            // PATH is the only source: a directory the user put on PATH is honoured (that is the user's choice).
            assertNotNull(findExecutableIn("ping", dir.toString(), emptyList()))
            // A name with a separator is a path, not a lookup.
            assertNull(findExecutableIn("sub/ping", dir.toString(), emptyList()))
        } finally { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    // ---- IPv6 readiness of the tools ----

    /** Fixed: the HTTP/TLS tool takes a bracketed IPv6 URL. */
    @Test fun theHttpTlsToolTakesAnIpv6LiteralUrl() {
        assertEquals("192.0.2.1", validateHttpUrl("https://192.0.2.1:8006/").host)
        assertEquals("https://[2001:db8::1]:8006/", validateHttpUrl("https://[2001:db8::1]:8006/").toString())
        // The other tools take a bare IPv6 literal but cannot take a scoped link-local one or a bracketed one.
        assertEquals("2001:db8::1", validateHost("2001:db8::1"))
        // A link-local address with a named zone: Windows resolves only numeric zones, so it is refused there; Linux takes it.
        if (System.getProperty("os.name").startsWith("Windows")) assertFailsWith<ToolInputException> { validateHost("fe80::1%eth0") }
        else assertEquals("fe80::1%eth0", validateHost("fe80::1%eth0"))
        assertFailsWith<ToolInputException> { validateHost("[::1]") }
    }

    // ---- locale-dependent parsing ----

    @Test fun windowsPingInEnglishParsesButTheRussianLatencyUnitDoesNot() {
        // English, real format.
        assertEquals(1.0, parsePingLatency("Reply from 192.0.2.1: bytes=32 time=1ms TTL=64"))
        assertEquals(0.0, parsePingLatency("Reply from 192.0.2.1: bytes=32 time<1ms TTL=64"))
        // Russian Windows, lines copied from public forum posts (documentation address substituted). The unit is Cyrillic.
        val reply = "Ответ от 192.0.2.1: число байт=32 время=47мс TTL=243"
        val replyFast = "Ответ от 192.0.2.1: число байт=32 время<1мс TTL=64"
        assertNull(parsePingLatency(reply))
        assertNull(parsePingLatency(replyFast))
        val block = listOf(reply, reply, reply, reply, "", "Статистика Ping для 192.0.2.1:", "    Пакетов: отправлено = 4, получено = 4, потеряно = 0", "    (0% потерь)").joinToString("\n")
        val report = parsePingReport(block, 4)
        assertEquals(4, report.received)        // the counts line has no unit, so it still parses
        assertTrue(report.samples.isEmpty())    // but every latency sample is lost: no chart, no min/avg/max
        assertNull(report.minMs); assertNull(report.avgMs); assertNull(report.maxMs)
    }

    /** Fixed: a name with no address is a name problem, not "filtered" (which reads as a firewall). */
    @Test fun portCheckReportsAnUnresolvableNameAsANameProblem() {
        val outcome = local.ringleader.core.tools.connectPort("no-such-host.invalid", 22, 1500)
        assertTrue(outcome is local.ringleader.core.tools.PortOutcome.NameNotFound, "got: $outcome")
    }

    // ---- the HTTP/TLS tool and trust-all ----

    @Test fun theHttpTlsToolSendsNoCredentialEvenWhenAnyCertificateIsAccepted() {
        RawHttpServer(NetAuditCerts.loopback.server).use { server ->
            val result = runBlocking {
                HttpTlsPlugin().run(mapOf("url" to "https://127.0.0.1:${server.port}/", "method" to "GET", "timeout" to "5", "insecure" to "true")) { }
            }
            assertTrue(result.ok, result.summary)
            val head = server.seen.single().head.lowercase()
            assertFalse("authorization" in head || "cookie" in head || "csrfpreventiontoken" in head, head)
            assertEquals("RingMASTER", server.seen.single().header("User-Agent"))
            println("NETAUDIT http-tls tool request head:\n" + server.seen.single().head.trim())
        }
    }

    // ---- trust-all confinement (a tripwire, not a proof of absence) ----

    private fun mainSources(): List<Pair<String, String>> {
        val roots = listOf(Path.of("src/main/kotlin"), Path.of("../desktop/src/main/kotlin"))
        return roots.filter { Files.isDirectory(it) }.flatMap { root ->
            Files.walk(root).use { stream -> stream.filter { it.toString().endsWith(".kt") }.map { it.fileName.toString() to Files.readString(it) }.toList() }
        }
    }

    @Test fun theTrustAllContextIsReferencedOnlyByDiscoveryAndTheDiagnosticProbe() {
        val sources = mainSources()
        assertTrue(sources.size > 100, "source walk found ${sources.size} files")
        val users = sources.filter { "scanTlsContext" in it.second }.map { it.first }.toSet()
        assertEquals(setOf("NetworkScan.kt", "DiagnosticBundle.kt"), users)
        // Neither file may handle a credential.
        for ((name, text) in sources.filter { it.first in users }) {
            for (token in listOf("authorizationHeader", ".tickets", "withSecret", "CSRFPreventionToken", "\"Authorization\"", "\"Cookie\"", "PVEAPIToken", "PDMAPIToken")) {
                assertFalse(token in text, "$name mentions $token")
            }
        }
    }

    @Test fun onlyKnownFilesImplementTrustManagersOrDisableHostNameChecks() {
        val sources = mainSources()
        val trustManagers = sources.filter { Regex("""(:|,)\s*(javax\.net\.ssl\.)?X509(Extended)?TrustManager\b|object\s*:\s*(javax\.net\.ssl\.)?X509(Extended)?TrustManager""").containsMatchIn(it.second) }.map { it.first }.toSet()
        // PlatformTrust.kt is the trust for unpinned connections: Java's roots plus this computer's store, host name check on.
        assertEquals(setOf("PveClient.kt", "NetworkScan.kt", "SocketTools.kt", "ServiceProbe.kt", "PlatformTrust.kt"), trustManagers)
        val verifiers = sources.filter { Regex("""HostnameVerifier\s*\{\s*_\s*,\s*_\s*->\s*true""").containsMatchIn(it.second) }.map { it.first }.toSet()
        assertEquals(setOf("DiagnosticBundle.kt"), verifiers)
    }
}
