package local.ringleader.core.tools

import java.io.InputStreamReader
import java.io.StringReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

class ToolSafetyTest {

    // -- Output limits --

    @Test fun linesSplitLikeReadLineAndLongLinesAreCut() {
        val lines = mutableListOf<String>()
        val over = readToolLines(StringReader("one\r\ntwo\nthree\rfour\n\nlast"), 8, 1000) { lines += it }
        assertFalse(over)
        assertEquals(listOf("one", "two", "three", "four", "", "last"), lines)

        val cut = mutableListOf<String>()
        readToolLines(StringReader("x".repeat(50) + "\nshort\n"), 10, 1000) { cut += it }
        assertEquals(listOf("x".repeat(10) + "…", "short"), cut)
    }

    @Test fun outputPastTheTotalLimitStopsReading() {
        val lines = mutableListOf<String>()
        val over = readToolLines(StringReader("abc\n".repeat(10_000)), 100, 1000) { lines += it }
        assertTrue(over)
        assertTrue(lines.size in 200..1100, "${lines.size} lines")
    }

    @Test fun aToolThatNeverStopsPrintingIsStopped() = runBlocking {
        val dir = Files.createTempDirectory("ringmaster-spew")
        val source = dir.resolve("Spew.java")
        Files.writeString(source, """
            public class Spew { public static void main(String[] a) throws Exception {
                java.io.OutputStream out = new java.io.BufferedOutputStream(System.out, 65536);
                byte[] b = new byte[8192]; java.util.Arrays.fill(b, (byte) 'x');
                while (true) out.write(b);
            } }
        """.trimIndent())
        val java = ProcessHandle.current().info().command().get()
        val lines = mutableListOf<String>()
        val before = RunningTools.pids.toSet()
        try {
            val failure = withTimeout(60_000) {
                runCatching { runToolProcess(listOf(java, source.toString())) { line -> synchronized(lines) { lines += line } } }.exceptionOrNull()
            }
            assertIs<ToolOutputLimitException>(failure)
            assertTrue(lines.isNotEmpty())
            assertTrue(lines.all { it.length <= TOOL_LINE_LIMIT + 1 }, "a line was not cut")
            assertTrue(lines.first().endsWith("…"))
            assertTrue(RunningTools.pids.none { it !in before }, "the process is still listed")
        } finally {
            Files.deleteIfExists(source); Files.deleteIfExists(dir)
        }
    }

    // -- Windows console text --

    @Test fun windowsOemCodePageIsReadAndDecoded() {
        assertEquals(850, parseOemCodePage("\r\nHKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Control\\Nls\\CodePage\r\n    OEMCP    REG_SZ    850\r\n\r\n"))
        assertEquals(437, parseOemCodePage("    OEMCP    REG_SZ    437"))
        assertNull(parseOemCodePage("ERROR: The system was unable to find the specified registry key or value."))
        for (cp in listOf(437, 850, 852, 866, 932, 936)) assertNotNull(codePageCharset(cp), "code page $cp")
        assertEquals(Charsets.UTF_8, codePageCharset(65001))
        assertNull(codePageCharset(1))
        assertEquals(java.nio.charset.Charset.defaultCharset(), consoleOutputCharset("Linux"))
    }

    @Test fun germanPingInOemBytesDecodesAndParses() {
        val text = "Antwort von 127.0.0.1: Bytes=32 Zeit<1ms TTL=128\r\n" +
            "Antwort von 127.0.0.1: Bytes=32 Zeit=2ms TTL=128\r\n" +
            "Zeitüberschreitung der Anforderung.\r\n\r\n" +
            "Ping-Statistik für 127.0.0.1:\r\n" +
            "    Pakete: Gesendet = 4, Empfangen = 3, Verloren = 1\r\n" +
            "    (25% Verlust),\r\n" +
            "Ungefähre Zeitangaben in Millisekunden:\r\n" +
            "    Minimum = 0ms, Maximum = 4ms, Mittelwert = 2ms\r\n"
        val oem = codePageCharset(850)!!
        val bytes = text.toByteArray(oem)
        val lines = mutableListOf<String>()
        readToolLines(InputStreamReader(bytes.inputStream(), oem), TOOL_LINE_LIMIT, TOOL_OUTPUT_LIMIT) { lines += it }
        assertEquals("Zeitüberschreitung der Anforderung.", lines[2])
        assertEquals("Ungefähre Zeitangaben in Millisekunden:", lines[7])
        // Read as UTF-8, the same bytes lose the umlauts.
        val wrong = mutableListOf<String>()
        readToolLines(InputStreamReader(bytes.inputStream(), Charsets.UTF_8), TOOL_LINE_LIMIT, TOOL_OUTPUT_LIMIT) { wrong += it }
        assertNotEquals(lines[2], wrong[2])
        val report = parsePingReport(lines.joinToString("\n"), 4)
        assertEquals(4, report.sent); assertEquals(3, report.received); assertEquals(listOf(0.0, 2.0), report.samples)
    }

    // -- Process tree --

    @Test fun childrenStartedAfterTheFirstLookAreKilled() {
        val root = FakeHandle(1)
        val first = FakeHandle(2).also { root.kids += it }
        // The root starts another child just after the first snapshot is taken.
        root.afterLook = { looks -> if (looks == 1) root.kids += FakeHandle(3) }
        var rootKilled = false
        killTree(root, pause = {}, destroyRoot = { rootKilled = true; root.alive = false })
        assertTrue(rootKilled)
        assertFalse(first.alive)
        assertEquals(2, root.kids.size)
        assertTrue(root.kids.none { it.alive }, "a child started after the first snapshot is still running")
    }

    @Test fun aRootThatKeepsStartingChildrenIsStillKilled() {
        val root = FakeHandle(1)
        var next = 10L
        root.afterLook = { root.kids += FakeHandle(next++) }
        var rootKilled = false
        killTree(root, rounds = 5, pause = {}, destroyRoot = { rootKilled = true; root.alive = false })
        assertTrue(rootKilled)
        assertTrue(root.kids.dropLast(1).none { it.alive })
    }

    @Test fun aLoneProcessIsKilledWithoutWaiting() {
        val root = FakeHandle(1)
        var pauses = 0
        killTree(root, pause = { pauses++ }, destroyRoot = { root.alive = false })
        assertFalse(root.alive); assertEquals(0, pauses)
    }

    // -- Traceroute --

    @Test fun tracepathSaysItHasNoTimeout() = runBlocking {
        val events = mutableListOf<ToolEvent>()
        val plugin = TraceroutePlugin("Linux") { if (it == "tracepath") "/nonexistent/ringmaster/tracepath" else null }
        val result = plugin.run(mapOf("host" to "127.0.0.1", "timeout" to "1000", "hops" to "3")) { synchronized(events) { events += it } }
        assertFalse(result.ok)
        assertTrue(events.any { it is ToolEvent.Status && TRACEPATH_NO_TIMEOUT in it.text }, events.toString())
        assertTrue(traceIgnoresTimeout(listOf("/usr/bin/tracepath", "-n")))
        assertFalse(traceIgnoresTimeout(listOf("/usr/bin/traceroute", "-n")))
        assertFalse(traceIgnoresTimeout(listOf("C:\\Windows\\System32\\TRACERT.EXE")))
    }

    // -- iperf3 --

    private fun iperfValues(server: Boolean, port: String = "5201", executable: String = "") = mapOf(
        "server" to server.toString(), "host" to if (server) "" else "127.0.0.1", "port" to port, "duration" to "1",
        "parallel" to "1", "reverse" to "false", "udp" to "false", "executable" to executable)

    @Test fun iperfRefusesBatchFiles() = runBlocking {
        val dir = Files.createTempDirectory("ringmaster-iperf-bat")
        val bat = Files.createFile(dir.resolve("iperf3.bat"))
        val cmd = Files.createFile(dir.resolve("iperf3.CMD"))
        try {
            for (path in listOf(bat, cmd)) {
                assertNull(resolveIperf(path.toString()) { null })
                val plugin = Iperf3Plugin { null }
                plugin.approvedExecutable = path.toString()
                val result = plugin.run(iperfValues(false, executable = path.toString())) {}
                assertFalse(result.ok); assertEquals(BATCH_FILE_REFUSED, result.summary)
                assertEquals(BATCH_FILE_REFUSED, plugin.availableFor(mapOf("executable" to path.toString())).reason)
            }
        } finally { Files.deleteIfExists(bat); Files.deleteIfExists(cmd); Files.deleteIfExists(dir) }
    }

    @Test fun iperfServerNeedsTheExactCommandConfirmedEachTime() = runBlocking {
        val fake = Files.createTempFile("ringmaster-iperf-server-", "")
        try {
            val plugin = Iperf3Plugin { if (it == "iperf3") fake.toString() else null }
            val before = RunningTools.lastCommand
            val unconfirmed = plugin.run(iperfValues(true)) {}
            assertFalse(unconfirmed.ok); assertTrue(unconfirmed.summary.contains("Confirm"), unconfirmed.summary)
            assertEquals(before, RunningTools.lastCommand)

            plugin.approvedServerCommand = plugin.previewCommand(iperfValues(true, port = "5202"))
            val otherPort = plugin.run(iperfValues(true)) {}
            assertTrue(otherPort.summary.contains("Confirm"), otherPort.summary)
            assertEquals(before, RunningTools.lastCommand)

            val command = plugin.previewCommand(iperfValues(true))!!
            plugin.approvedServerCommand = command
            plugin.run(iperfValues(true)) {}
            assertEquals(command, RunningTools.lastCommand, "the confirmed command did not start")
            assertNull(plugin.approvedServerCommand, "a server confirmation is used once")
            RunningTools.lastCommand = before
            val again = plugin.run(iperfValues(true)) {}
            assertTrue(again.summary.contains("Confirm"), again.summary)
            assertEquals(before, RunningTools.lastCommand)
        } finally { Files.deleteIfExists(fake) }
    }

    // -- Browser callers --

    @Test fun browserCallersAreRefusedInsideRun() = runBlocking {
        val reason = "Not available in the browser without --allow-host-actions."
        val fake = Files.createTempFile("ringmaster-iperf-browser-", "")
        try {
            val iperf = Iperf3Plugin { if (it == "iperf3") fake.toString() else null }
            iperf.approvedServerCommand = iperf.previewCommand(iperfValues(true))
            iperf.approvedExecutable = fake.toString()
            val user = UserToolPlugin("browser", "Browser", "Test", listOf(ToolInput("host", "Host", ToolInputKind.Host)),
                listOf("fake-program", "{host}"), null, "browser.json") { "/opt/fake-program" }
            user.approval = user.approvalFor(mapOf("host" to "127.0.0.1"))
            val before = RunningTools.lastCommand
            withContext(ToolCaller(hostActions = false)) {
                assertEquals(reason, iperf.run(iperfValues(true)) {}.summary)
                assertEquals(reason, iperf.run(iperfValues(false, executable = fake.toString())) {}.summary)
                assertEquals(reason, user.run(mapOf("host" to "127.0.0.1")) {}.summary)
                // The iperf client from PATH is a read-only probe and is not refused here.
                assertNotEquals(reason, Iperf3Plugin { null }.run(iperfValues(false)) {}.summary)
            }
            assertEquals(before, RunningTools.lastCommand)
            assertNotNull(iperf.approvedServerCommand, "a refused run must not use up the confirmation")
            withContext(ToolCaller(hostActions = true)) {
                assertNotEquals(reason, Iperf3Plugin { null }.run(iperfValues(true)) {}.summary)
            }
        } finally { Files.deleteIfExists(fake) }
    }

    // -- Plug-ins --

    @Test fun pluginApprovalNamesTheResolvedProgram() = runBlocking {
        var onPath = "/usr/bin/fake-curl"
        val plugin = UserToolPlugin("resolved", "Resolved", "Test", listOf(ToolInput("host", "Host", ToolInputKind.Host)),
            listOf("fake-curl", "{host}"), null, "resolved.json") { if (it == "fake-curl") onPath else null }
        val values = mapOf("host" to "127.0.0.1")
        val approval = plugin.approvalFor(values)!!
        assertEquals("/usr/bin/fake-curl", approval.program)
        assertEquals(listOf("fake-curl", "127.0.0.1"), approval.argv)
        plugin.approval = approval
        onPath = "/tmp/elsewhere/fake-curl"
        val before = RunningTools.lastCommand
        val result = plugin.run(values) {}
        assertFalse(result.ok); assertTrue(result.summary.contains("changed"), result.summary)
        assertEquals(before, RunningTools.lastCommand)
        assertNotEquals(approval, plugin.approvalFor(values), "a different file on PATH needs a new confirmation")
    }

    @Test fun slowLinePatternsGiveUp() {
        val catastrophic = Regex("(?<value>(.*a){12})b")
        val started = System.nanoTime()
        assertEquals(LineMatch.TooSlow, findWithBudget(catastrophic, "a".repeat(40), 50))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "the budget was not kept")
        val found = findWithBudget(Regex("time=(?<value>[0-9.]+) ms"), "64 bytes: time=0.42 ms", 50)
        assertEquals("0.42", (found as LineMatch.Found).match.groups["value"]?.value)
        assertEquals(LineMatch.None, findWithBudget(Regex("(?<value>\\d+)"), "no digits", 50))
        // Only the first PATTERN_LINE_LIMIT characters are matched.
        assertEquals(LineMatch.None, findWithBudget(Regex("(?<value>7)"), "x".repeat(PATTERN_LINE_LIMIT) + "7", 50))
    }

    @Test fun aSlowPatternDoesNotStallAPluginRun() = runBlocking {
        val dir = Files.createTempDirectory("ringmaster-lines")
        val source = dir.resolve("Lines.java")
        Files.writeString(source, "public class Lines { public static void main(String[] a) { for (int i = 0; i < 20; i++) System.out.println(\"a\".repeat(40)); } }")
        try {
            val java = ProcessHandle.current().info().command().get()
            val plugin = UserToolPlugin("slow", "Slow", "Test", emptyList(), listOf(java, source.toString()),
                Regex("(?<value>(.*a){12})b"), "slow.json")
            plugin.approval = plugin.approvalFor(emptyMap())
            val lines = mutableListOf<String>()
            val result = withTimeout(60_000) { plugin.run(emptyMap()) { if (it is ToolEvent.Line) synchronized(lines) { lines += it.text } } }
            assertTrue(result.ok, result.summary)
            assertEquals(20, lines.count { it == "a".repeat(40) }, lines.toString())
            assertTrue(lines.any { it.contains("too slow") }, lines.toString())
        } finally { Files.deleteIfExists(source); Files.deleteIfExists(dir) }
    }

    // -- DNS --

    @Test fun dnsLookupHasADeadline() = runBlocking {
        val never = CountDownLatch(1)
        val plugin = DnsPlugin(resolve = { never.await(); emptyArray() })
        val started = System.nanoTime()
        val result = plugin.run(mapOf("name" to "slow.example", "timeout" to "1")) {}
        val millis = (System.nanoTime() - started) / 1_000_000
        assertFalse(result.ok); assertTrue(result.summary.contains("No answer"), result.summary)
        assertTrue(millis < 5_000, "took $millis ms")
        never.countDown()
    }

    @Test fun dnsStopReturnsPromptly() = runBlocking {
        val never = CountDownLatch(1)
        val entered = CompletableDeferred<Unit>()
        val plugin = DnsPlugin(resolve = { entered.complete(Unit); never.await(); emptyArray() })
        val job = launch(Dispatchers.Default) { plugin.run(mapOf("name" to "slow.example", "timeout" to "30")) {} }
        withTimeout(5_000) { entered.await() }
        val started = System.nanoTime()
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        never.countDown()
    }

    @Test fun reverseLookupsShareTheDeadline() = runBlocking {
        val never = CountDownLatch(1)
        val loopback = InetAddress.getByName("127.0.0.1")
        val plugin = DnsPlugin(resolve = { arrayOf(loopback, loopback) }, reverse = { never.await(); "late" })
        val lines = mutableListOf<String>()
        val started = System.nanoTime()
        val result = plugin.run(mapOf("name" to "two.example", "reverse" to "true", "timeout" to "1")) { if (it is ToolEvent.Line) lines += it.text }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        assertTrue(result.ok, result.summary)
        assertEquals(2, lines.count { it.startsWith("PTR") && it.endsWith("no answer in time") }, lines.toString())
        never.countDown()
    }

    // -- HTTP proxy --

    @Test fun ipLiteralsBypassTheSystemProxy() {
        assertTrue(bypassProxy("192.168.1.10")); assertTrue(bypassProxy("[::1]")); assertTrue(bypassProxy("fe80::1"))
        assertFalse(bypassProxy("pve.example")); assertFalse(bypassProxy("localhost"))
    }

    @Test fun httpCheckOfAnAddressGoesDirect() = runBlocking {
        val previous = ProxySelector.getDefault()
        val deadProxy = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val accept = Thread {
            runCatching {
                while (true) server.accept().use { s ->
                    s.getInputStream().read(ByteArray(4096))
                    s.getOutputStream().write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            ProxySelector.setDefault(object : ProxySelector() {
                override fun select(uri: URI?) = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", deadProxy)))
                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) = Unit
            })
            val direct = HttpTlsPlugin().run(mapOf("url" to "http://127.0.0.1:${server.localPort}/", "method" to "HEAD", "timeout" to "5")) {}
            assertTrue(direct.ok, direct.summary)
            assertEquals("204", direct.metrics["status"])
            // A hostname still uses the system proxy, which is down here.
            val proxied = HttpTlsPlugin().run(mapOf("url" to "http://localhost:${server.localPort}/", "method" to "HEAD", "timeout" to "5")) {}
            assertFalse(proxied.ok, proxied.summary)
        } finally {
            ProxySelector.setDefault(previous)
            server.close(); accept.join(2000)
        }
    }
}

/** A process handle the test controls. [afterLook] runs after each [descendants] snapshot, with the number of looks so far. */
private class FakeHandle(private val id: Long) : ProcessHandle {
    val kids = mutableListOf<FakeHandle>()
    var alive = true
    var looks = 0
    var afterLook: (Int) -> Unit = {}
    private fun tree(): List<ProcessHandle> = kids.filter { it.alive }.flatMap { listOf<ProcessHandle>(it) + it.tree() }
    override fun pid(): Long = id
    override fun parent(): Optional<ProcessHandle> = Optional.empty()
    override fun children(): Stream<ProcessHandle> = kids.filter { it.alive }.map { it as ProcessHandle }.stream()
    override fun descendants(): Stream<ProcessHandle> {
        val snapshot = tree()
        looks++
        if (alive) afterLook(looks)
        return snapshot.stream()
    }
    override fun info(): ProcessHandle.Info = throw UnsupportedOperationException()
    override fun onExit(): CompletableFuture<ProcessHandle> = CompletableFuture()
    override fun supportsNormalTermination(): Boolean = true
    override fun destroy(): Boolean { alive = false; return true }
    override fun destroyForcibly(): Boolean { alive = false; return true }
    override fun isAlive(): Boolean = alive
    override fun compareTo(other: ProcessHandle): Int = id.compareTo(other.pid())
}
