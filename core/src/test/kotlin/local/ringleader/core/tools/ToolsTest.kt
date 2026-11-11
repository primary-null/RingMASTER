package local.ringleader.core.tools

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import local.ringleader.core.TailscalePeer
import local.ringleader.core.VpnBackend
import local.ringleader.core.VpnKind
import local.ringleader.core.VpnRoute
import local.ringleader.core.VpnSnapshot
import local.ringleader.core.VpnState
import local.ringleader.core.VpnTunnel
import local.ringleader.core.WireGuardInterface
import local.ringleader.core.WireGuardPeer
import local.ringleader.core.findExecutable
import org.junit.jupiter.api.Test

class ToolsTest {
    private val host = ToolInput("host", "Host", ToolInputKind.Host)

    @Test fun hostsRejectShellSyntax() {
        for (bad in listOf("8.8.8.8; rm -rf /", "$(x)", "`x`", "a&b", "8.8.8.8|calc", "name with space", "host\nname", "1.2.3.4.5", "256.1.1.1", "127.0.0.01")) {
            val error = runCatching { validateToolValue(host, bad) }.exceptionOrNull()
            assertTrue(error is ToolInputException, bad)
        }
        assertEquals("127.0.0.1", validateHost("127.0.0.1"))
        assertEquals("example.com", validateHost("example.com"))
        assertEquals("localhost", validateHost("localhost"))
        assertEquals("::1", validateHost("::1"))
        assertEquals("2001:db8::1", validateHost("2001:db8::1"))
    }

    @Test fun numbersAndPortsStayInsideBounds() {
        val count = ToolInput("count", "Count", ToolInputKind.Number, min = 1, max = 10)
        assertEquals("4", validateToolValue(count, "4"))
        assertTrue(runCatching { validateToolValue(count, "0") }.exceptionOrNull() is ToolInputException)
        assertTrue(runCatching { validateToolValue(count, "11") }.exceptionOrNull() is ToolInputException)
        assertTrue(runCatching { validateToolValue(count, "1;2") }.exceptionOrNull() is ToolInputException)
        assertTrue(runCatching { validateToolValue(count, "08") }.exceptionOrNull() is ToolInputException)
        assertFailsWith<ToolInputException> { parsePortList("80,443;rm") }
        assertFailsWith<ToolInputException> { parsePortList("$(x)") }
        assertFailsWith<ToolInputException> { parsePortList("`x`") }
        assertFailsWith<ToolInputException> { parsePortList("a&b") }
        assertFailsWith<ToolInputException> { parsePortList("0") }
        assertFailsWith<ToolInputException> { parsePortList("65536") }
        assertFailsWith<ToolInputException> { parsePortList((1..33).joinToString(",")) }
        assertEquals(listOf(22, 80, 443), parsePortList("22,80,443"))
        assertFailsWith<ToolInputException> { validateHttpUrl("https://8.8.8.8;rm/") }
        assertFailsWith<ToolInputException> { validateHttpUrl("file:///tmp/x") }
        assertEquals("example.com", validateHttpUrl("https://example.com/path").host)
    }

    @Test fun pingRejectsShellSyntaxBeforeStarting() = runBlocking {
        val before = RunningTools.lastCommand
        val result = PingPlugin().run(mapOf("host" to "8.8.8.8; rm -rf /", "count" to "1", "timeout" to "1000")) {}
        assertFalse(result.ok)
        assertEquals(before, RunningTools.lastCommand)
    }

    @Test fun pingCommandsAreArgumentLists() {
        assertEquals(listOf("C:\\Windows\\System32\\ping.exe", "-n", "4", "-w", "1000", "127.0.0.1"), pingCommand("Windows 11", "C:\\Windows\\System32\\ping.exe", "127.0.0.1", 4, 1000))
        assertEquals(listOf("/bin/ping", "-c", "4", "-W", "2", "127.0.0.1"), pingCommand("Linux", "/bin/ping", "127.0.0.1", 4, 1500))
        assertEquals(listOf("C:\\Windows\\System32\\tracert.exe", "-d", "-w", "500", "-h", "5", "127.0.0.1"), traceCommand("Windows 11", { "C:\\Windows\\System32\\tracert.exe" }, "127.0.0.1", 500, 5))
        assertEquals(listOf("/usr/bin/traceroute", "-n", "-w", "1", "-m", "8", "10.0.0.1"), traceCommand("Linux", { if (it == "traceroute") "/usr/bin/traceroute" else null }, "10.0.0.1", 1000, 8))
        assertEquals(listOf("/usr/bin/tracepath", "-n", "-m", "8", "10.0.0.1"), traceCommand("Linux", { if (it == "tracepath") "/usr/bin/tracepath" else null }, "10.0.0.1", 1000, 8))
    }

    @Test fun parsesWindowsAndLinuxPing() {
        val windows = parsePingReport(WINDOWS_PING, 4)
        assertEquals(4, windows.sent)
        assertEquals(3, windows.received)
        assertEquals(25.0, windows.lossPercent)
        assertEquals(0.0, windows.minMs)
        assertEquals(1.0, windows.avgMs)
        assertEquals(2.0, windows.maxMs)
        assertEquals(listOf(0.0, 2.0, 4.0), windows.samples)

        val german = parsePingReport(GERMAN_PING, 4)
        assertEquals(4, german.sent)
        assertEquals(3, german.received)
        assertEquals(listOf(0.0, 2.0), german.samples)
        assertEquals(0.0, german.minMs)
        assertEquals(2.0, german.avgMs)
        assertEquals(4.0, german.maxMs)

        val linux = parsePingReport(LINUX_PING, 3)
        assertEquals(3, linux.sent)
        assertEquals(3, linux.received)
        assertEquals(0.0, linux.lossPercent)
        assertEquals(0.042, linux.minMs)
        assertEquals(0.057, linux.avgMs)
        assertEquals(0.080, linux.maxMs)
        assertEquals(3, linux.samples.size)
    }

    @Test fun parsesWindowsAndLinuxTraceroute() {
        val windows = WINDOWS_TRACE.lines().mapNotNull(::parseTraceHop)
        assertEquals(3, windows.size)
        assertEquals("192.168.1.1", windows[0].address)
        assertEquals(0.0, windows[0].rttMs)
        assertTrue(windows[1].timeout)
        assertNull(windows[1].address)
        assertEquals("1.1.1.1", windows[2].address)
        assertEquals(11.0, windows[2].rttMs)

        val linux = LINUX_TRACE.lines().mapNotNull(::parseTraceHop)
        assertEquals(3, linux.size)
        assertEquals("192.168.1.1", linux[0].address)
        assertEquals(0.3, linux[0].rttMs)
        assertTrue(linux[1].timeout)
        assertEquals("1.1.1.1", linux[2].address)

        val path = TRACEPATH.lines().mapNotNull(::parseTraceHop)
        assertEquals(listOf(1, 2), path.map { it.hop })
        assertEquals("192.168.1.1", path[0].address)
        assertEquals(0.432, path[0].rttMs)
    }

    @Test fun parsesIperfIntervals() {
        val interval = parseIperfLine("[  5]   0.00-1.00   sec  1.25 MBytes  10.5 Mbits/sec")
        assertNotNull(interval)
        assertFalse(interval.summary)
        assertEquals(10.5 * 1_000_000, interval.bitsPerSecond, 1.0)
        val linuxSummary = parseIperfLine("[  5]   0.00-10.00  sec  12.5 MBytes  10.5 Mbits/sec                  sender")
        assertTrue(linuxSummary!!.summary)
        assertEquals(10.5 * 1_000_000, linuxSummary.bitsPerSecond, 1.0)
        val windowsSummary = parseIperfLine("[  5]   0.00-10.00  sec  11.0 GBytes  9.41 Gbits/sec                  receiver")
        assertTrue(windowsSummary!!.summary)
        assertEquals(9.41 * 1_000_000_000, windowsSummary.bitsPerSecond, 1_000.0)
        val udp = parseIperfLine("[  5]   0.00-1.00   sec   128 KBytes  1.05 Mbits/sec  0.012 ms  0/91 (0%)")
        assertFalse(udp!!.summary)
        assertEquals(1.05 * 1_000_000, udp.bitsPerSecond, 1.0)
        assertEquals(
            listOf("iperf3", "-c", "10.0.0.2", "-p", "5201", "-t", "10", "-P", "2", "--forceflush", "-R", "-u"),
            iperfCommand("iperf3", false, "10.0.0.2", 5201, 10, 2, true, true),
        )
        assertEquals(listOf("iperf3", "-s", "-1", "-p", "5201", "--forceflush"), iperfCommand("iperf3", true, "", 5201, 10, 1, false, false))
        assertTrue(IPERF_MISSING.contains("winget install iperf3"))
        assertTrue(IPERF_MISSING.contains("apt install iperf3"))
        assertNull(resolveIperf("not a path", { null }))
    }

    @Test fun portCheckSeesOpenAndClosed() = runBlocking {
        ServerSocket(0).use { open ->
            val accept = Thread { runCatching { open.accept().close() } }
            accept.isDaemon = true
            accept.start()
            val closed = ServerSocket(0).use { it.localPort }
            val lines = mutableListOf<String>()
            val result = PortCheckPlugin().run(mapOf("host" to "127.0.0.1", "ports" to "${open.localPort},$closed", "timeout" to "1000")) {
                if (it is ToolEvent.Line) lines += it.text
            }
            assertTrue(result.ok, result.summary)
            assertTrue(lines.any { it.startsWith("${open.localPort} open") }, lines.joinToString())
            assertTrue(lines.any { it == "$closed closed" }, lines.joinToString())
            accept.join(2000)
        }
    }

    @Test fun dnsResolvesLocalhost() = runBlocking {
        val lines = mutableListOf<String>()
        val result = DnsPlugin().run(mapOf("name" to "localhost", "reverse" to "true")) { if (it is ToolEvent.Line) lines += it.text }
        assertTrue(result.ok, result.summary)
        assertTrue(lines.any { it.contains("127.0.0.1") }, lines.joinToString())
        assertTrue(result.metrics.getValue("count").toInt() >= 1)
    }

    @Test fun httpTlsReportsSelfSignedFingerprint() = runBlocking {
        val dir = Files.createTempDirectory("ringmaster-tls")
        try {
            val (store, cert) = testKeyStore(dir)
            val expected = sha256Fingerprint(cert)
            val context = SSLContext.getInstance("TLS")
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keys.init(store, "changeit".toCharArray())
            context.init(keys.keyManagers, null, null)
            val server = context.serverSocketFactory.createServerSocket() as SSLServerSocket
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            server.soTimeout = 8_000
            val done = AtomicBoolean(false)
            val worker = Thread {
                try {
                    val socket = server.accept() as SSLSocket
                    socket.soTimeout = 8_000
                    socket.use { client ->
                        client.startHandshake()
                        val reader = client.inputStream.bufferedReader()
                        val request = reader.readLine().orEmpty()
                        while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
                        val body = if (request.startsWith("HEAD")) "" else "ok"
                        client.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                        client.outputStream.flush()
                    }
                } catch (_: Exception) {
                } finally { done.set(true) }
            }
            worker.isDaemon = true
            worker.start()
            try {
                val lines = mutableListOf<String>()
                val result = HttpTlsPlugin().run(mapOf(
                    "url" to "https://127.0.0.1:${server.localPort}/",
                    "method" to "GET",
                    "timeout" to "8",
                    "insecure" to "true",
                )) { if (it is ToolEvent.Line) lines += it.text }
                assertTrue(result.ok, result.summary + "\n" + lines.joinToString("\n"))
                assertEquals(expected, result.metrics["fingerprint"])
                assertTrue(result.summary.contains("not verified"), result.summary)
                assertTrue(lines.any { it.contains(expected) }, lines.joinToString("\n"))
                assertTrue(result.metrics["subject"].orEmpty().contains("localhost"))
                assertTrue(result.metrics["protocol"].orEmpty().startsWith("TLS"), result.metrics.toString())
            } finally {
                server.close()
                worker.join(2000)
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun manifestSubstitutionAndConfirmation() = runBlocking {
        val bad = readUserPlugin("bad.json", """{"id":"bad","name":"Bad","summary":"Broken","command":"curl"}""")
        assertTrue(bad is ManifestRead.Bad)
        assertEquals("command must be a list of arguments.", (bad as ManifestRead.Bad).reason)
        val relative = readUserPlugin("rel.json", """{"id":"rel","name":"Rel","summary":"Relative","command":["../curl"],"inputs":[]}""")
        assertEquals("The program must be an absolute path or a program name.", (relative as ManifestRead.Bad).reason)
        val missing = readUserPlugin("miss.json", """{"id":"miss","name":"Miss","summary":"Missing","command":["curl","{host}"],"inputs":[]}""")
        assertEquals("Placeholder {host} has no input.", (missing as ManifestRead.Bad).reason)
        val reserved = loadUserPlugins(Files.createTempDirectory("ringmaster-plugins").also { dir ->
            Files.writeString(dir.resolve("ping.json"), """{"id":"ping","name":"Ping","summary":"Reserved","command":["ping"],"inputs":[]}""")
        }, setOf("ping"))
        assertEquals("This id is reserved.", reserved.second.single().reason)

        val argv = substitutePlaceholders(listOf("/usr/bin/curl", "host={host}"), mapOf("host" to "10.0.0.1"))
        assertEquals(listOf("/usr/bin/curl", "host=10.0.0.1"), argv)
        val spaced = substitutePlaceholders(listOf("echo", "{text}"), mapOf("text" to "a b; rm"))
        assertEquals(listOf("echo", "a b; rm"), spaced)

        val ping = findExecutable("ping")
        assertNotNull(ping)
        val text = buildJsonObject {
            put("id", "user-ping")
            put("name", "User ping")
            put("summary", "Wraps ping")
            putJsonArray("command") {
                add(ping)
                if (windowsOs()) { add("-n"); add("{count}"); add("-w"); add("1000"); add("{host}") }
                else { add("-c"); add("{count}"); add("-W"); add("1"); add("{host}") }
            }
            putJsonArray("inputs") {
                addJsonObject { put("key", "host"); put("label", "Host"); put("kind", "Host"); put("default", "127.0.0.1") }
                addJsonObject { put("key", "count"); put("label", "Count"); put("kind", "Number"); put("default", "1"); put("min", 1); put("max", 5) }
            }
        }.toString()
        val parsed = readUserPlugin("user-ping.json", text)
        assertTrue(parsed is ManifestRead.Ok, parsed.toString())
        val plugin = (parsed as ManifestRead.Ok).plugin
        val before = RunningTools.lastCommand
        val refused = plugin.run(mapOf("host" to "8.8.8.8; rm -rf /", "count" to "1")) {}
        assertFalse(refused.ok)
        assertEquals(before, RunningTools.lastCommand)
        val unconfirmed = plugin.run(mapOf("host" to "127.0.0.1", "count" to "1")) {}
        assertFalse(unconfirmed.ok)
        assertTrue(unconfirmed.summary.contains("Confirm"))
        assertEquals(before, RunningTools.lastCommand)
        plugin.confirmed = true
        val ran = plugin.run(mapOf("host" to "127.0.0.1", "count" to "1")) {}
        assertTrue(ran.ok, ran.summary)
        assertFalse(RunningTools.lastCommand.any { it.contains(";") || it.contains("|") || it == "cmd.exe" || it == "sh" })
        assertEquals(ping, RunningTools.lastCommand.first())

        val root = generateSequence(java.nio.file.Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("settings.gradle.kts")) }
        val example = readUserPlugin("example-curl.json", Files.readString(root.resolve("docs/plugins/example-curl.json")))
        assertTrue(example is ManifestRead.Ok, example.toString())
        assertEquals(listOf("curl", "-I", "--max-time", "10", "--url", "https://example.com"), (example as ManifestRead.Ok).plugin.commandFor(emptyMap()))
    }

    @Test fun cancellationKillsPing() = runBlocking {
        val before = RunningTools.pids.toSet()
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            PingPlugin().run(mapOf("host" to "127.0.0.1", "count" to "40", "timeout" to "1000")) {
                if (it is ToolEvent.Line && it.text.isNotBlank()) started.complete(Unit)
            }
        }
        try {
            withTimeout(20_000) { started.await() }
            val pid = withTimeout(5_000) {
                while (true) {
                    val found = RunningTools.pids.firstOrNull { it !in before }
                    if (found != null) return@withTimeout found
                    delay(20)
                }
                0L
            }
            job.cancelAndJoin()
            val dead = (1..40).any {
                val handle = ProcessHandle.of(pid).orElse(null)
                if (handle == null || !handle.isAlive) true else { Thread.sleep(100); false }
            }
            assertTrue(dead, "ping $pid is still running")
        } finally { job.cancel() }
    }

    @Test fun wireGuardIsReadOnlyAndSamplesRates() = runBlocking {
        val now = Instant.parse("2026-01-01T00:00:30Z")
        val peer = WireGuardPeer("abc", "203.0.113.9:51820", listOf("10.8.0.2/32"), now.epochSecond - 30, 1_000, 2_000, 25)
        val tunnel = VpnTunnel("wg:wg0", VpnKind.WireGuard, "wg0", VpnState.Up, "wg0")
        fun snap(rx: Long, tx: Long) = VpnSnapshot(
            now, listOf(tunnel), false, null, true,
            listOf(WireGuardInterface("wg0", null, 51820, listOf(peer.copy(rxBytes = rx, txBytes = tx)))),
            emptyList(),
        )
        val snaps = ArrayDeque(listOf(snap(1_000, 2_000), snap(1_000, 2_000), snap(251_000, 127_000), snap(501_000, 252_000)))
        var mutations = 0
        val backend = object : VpnBackend {
            override val platform = "Test"
            override fun snapshot() = if (snaps.size > 1) snaps.removeFirst() else snaps.first()
            override fun routeTo(host: String, snapshot: VpnSnapshot?) = VpnRoute(host, host, null, null, null)
            override fun setTunnel(tunnel: VpnTunnel, up: Boolean) { mutations++ }
            override fun setExitNode(peer: TailscalePeer?) { mutations++ }
            override fun importWireGuard(config: java.nio.file.Path, name: String): String { mutations++; return "" }
            override fun removeWireGuard(tunnel: VpnTunnel) { mutations++ }
        }
        val plugin = WireGuardToolPlugin(
            backendProvider = { backend },
            pinger = { host, emit -> emit(ToolEvent.Line("ping $host")); ToolResult(true, "3 sent, 3 received, 0% loss") },
            pause = {},
            clock = { now },
        )
        plugin.refreshSnapshot()
        val events = mutableListOf<ToolEvent>()
        val result = plugin.run(mapOf("peer" to "wg:wg0|10.8.0.2", "seconds" to "4")) { events += it }
        assertTrue(result.ok, result.summary)
        assertEquals(0, mutations)
        assertEquals("30s ago", result.metrics["handshake"])
        val rate = events.filterIsInstance<ToolEvent.Sample>().first { it.series == "receive" }
        assertEquals(1.0, rate.y, 0.01)
        assertEquals("Mbit/s", rate.unit)
        assertTrue(events.any { it is ToolEvent.Line && it.text.contains("10.8.0.2") })
    }

    @Test fun liveLocalProbes() = runBlocking {
        val pingLines = mutableListOf<String>()
        val ping = withContext(Dispatchers.IO) {
            PingPlugin().run(mapOf("host" to "127.0.0.1", "count" to "2", "timeout" to "1000")) { if (it is ToolEvent.Line) pingLines += it.text }
        }
        assertTrue(ping.ok, ping.summary + "\n" + pingLines.joinToString("\n"))
        assertTrue(ping.metrics.getValue("received").toInt() >= 1, ping.summary)

        val traceLines = mutableListOf<String>()
        val trace = withContext(Dispatchers.IO) {
            TraceroutePlugin().run(mapOf("host" to "127.0.0.1", "timeout" to "500", "hops" to "5")) { if (it is ToolEvent.Line) traceLines += it.text }
        }
        assertTrue(trace.ok, trace.summary + "\n" + traceLines.joinToString("\n"))
        assertTrue(traceLines.mapNotNull(::parseTraceHop).any { it.address == "127.0.0.1" }, traceLines.joinToString("\n"))

        ServerSocket(0).use { open ->
            val accept = Thread { runCatching { open.accept().close() } }
            accept.isDaemon = true
            accept.start()
            val ports = PortCheckPlugin().run(mapOf("host" to "127.0.0.1", "ports" to "${open.localPort}", "timeout" to "1000")) {}
            assertTrue(ports.ok, ports.summary)
            assertEquals("1", ports.metrics["open"])
            accept.join(2000)
        }

        val dns = DnsPlugin().run(mapOf("name" to "localhost")) {}
        assertTrue(dns.ok, dns.summary)
        val example = DnsPlugin().run(mapOf("name" to "example.com")) {}
        assertTrue(example.ok, example.summary)
        assertTrue(example.metrics.getValue("count").toInt() >= 1)
    }
}

private fun testKeyStore(dir: java.nio.file.Path): Pair<KeyStore, X509Certificate> {
    val path = dir.resolve("test.p12")
    val keytool = java.nio.file.Path.of(System.getProperty("java.home"), "bin", if (windowsOs()) "keytool.exe" else "keytool")
    val process = ProcessBuilder(
        keytool.toString(), "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
        "-storetype", "PKCS12", "-keystore", path.toString(), "-storepass", "changeit", "-keypass", "changeit",
        "-dname", "CN=localhost", "-validity", "1", "-noprompt",
    ).redirectErrorStream(true).start()
    val output = process.inputStream.readBytes()
    check(process.waitFor() == 0) { "keytool failed: " + output.toString(Charsets.UTF_8) }
    val store = KeyStore.getInstance("PKCS12")
    Files.newInputStream(path).use { store.load(it, "changeit".toCharArray()) }
    return store to store.getCertificate("test") as X509Certificate
}

private const val WINDOWS_PING = """
Pinging 127.0.0.1 with 32 bytes of data:
Reply from 127.0.0.1: bytes=32 time<1ms TTL=128
Reply from 127.0.0.1: bytes=32 time=2ms TTL=128
Request timed out.
Reply from 127.0.0.1: bytes=32 time=4ms TTL=128

Ping statistics for 127.0.0.1:
    Packets: Sent = 4, Received = 3, Lost = 1 (25% loss),
Approximate round trip times in milli-seconds:
    Minimum = 0ms, Maximum = 2ms, Average = 1ms
"""

private const val GERMAN_PING = """
Antwort von 127.0.0.1: Bytes=32 Zeit<1ms TTL=128
Antwort von 127.0.0.1: Bytes=32 Zeit=2ms TTL=128
Zeitüberschreitung der Anforderung.

Ping-Statistik für 127.0.0.1:
    Pakete: Gesendet = 4, Empfangen = 3, Verloren = 1
    (25% Verlust),
Ungefähre Zeitangaben in Millisekunden:
    Minimum = 0ms, Maximum = 4ms, Mittelwert = 2ms
"""

private const val LINUX_PING = """
PING 127.0.0.1 (127.0.0.1) 56(84) bytes of data.
64 bytes from 127.0.0.1: icmp_seq=1 ttl=64 time=0.042 ms
64 bytes from 127.0.0.1: icmp_seq=2 ttl=64 time=0.080 ms
64 bytes from 127.0.0.1: icmp_seq=3 ttl=64 time=0.050 ms

--- 127.0.0.1 ping statistics ---
3 packets transmitted, 3 received, 0% packet loss, time 2003ms
rtt min/avg/max/mdev = 0.042/0.057/0.080/0.016 ms
"""

private const val WINDOWS_TRACE = """
Tracing route to 1.1.1.1 over a maximum of 30 hops

  1    <1 ms    <1 ms    <1 ms  192.168.1.1
  2     *        *        *     Request timed out.
  3    12 ms    11 ms    14 ms  1.1.1.1

Trace complete.
"""

private const val LINUX_TRACE = """
traceroute to 1.1.1.1 (1.1.1.1), 30 hops max, 60 byte packets
 1  192.168.1.1  0.4 ms  0.3 ms  0.5 ms
 2  * * *
 3  1.1.1.1  11.2 ms  10.9 ms  11.4 ms
"""

private const val TRACEPATH = """
 1?: [LOCALHOST]                      pmtu 65535
 1:  192.168.1.1                                           0.432ms
 2:  1.1.1.1                                              11.204ms reached
"""
