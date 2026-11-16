package local.ringleader.core

import java.net.InetAddress
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import local.ringleader.core.tools.*
import kotlin.test.*

/**
 * Robustness after the 2026-11-16 audit (F3, F4, F6, F7): a fresh peer path for the route tag, the helper output cap,
 * the WireGuard/Tailscale tool's cheap Tailscale samples and peer order, and where the Tailscale CLI is looked for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VpnRobustnessTest {
    // ------------------------------------------------------------------ F3: fresh peer path for the tag

    private fun ts(path: String) = parseTailscaleStatus("""{"BackendState":"Running","TUN":true,"Self":{"ID":"s","TailscaleIPs":["100.64.0.1"]},
        "Peer":{"p":{"ID":"p","HostName":"pve1","TailscaleIPs":["100.64.0.10"],"Online":true,"Active":true,"Relay":"fra",$path}}}""")

    private val tailscaleUp = VpnTunnel("tailscale", VpnKind.Tailscale, "Tailscale", VpnState.Up, "Tailscale", listOf("100.64.0.1"))

    private fun observe(test: TestScope, address: String, fresh: (() -> TailscaleStatus?)?, onFresh: () -> Unit = {}): RouteObservation {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(dispatcher + Job())
        val clock = { Instant.EPOCH.plusMillis(test.testScheduler.currentTime) }
        // The long-lived snapshot still says DERP; the fresh status says direct.
        val stale = VpnSnapshot(clock(), listOf(tailscaleUp), true, ts(""""CurAddr":"""""), false, emptyList(), emptyList())
        val inventory = VpnInventorySource({ null }, { stale.copy(at = clock()) }, clock, freshTailscale = fresh?.let { f -> { onFresh(); f() } })
        val observer = ConnectionRouteObserver(scope, object : RouteProbe {
            override fun route(address: String) = OsRoute(address, RouteInterface("Tailscale", 9, "Tailscale Tunnel", RouteMedium.Tunnel, VpnKind.Tailscale), "100.64.0.1", null)
        }, inventory, resolve = { listOf(InetAddress.getByName(address)) }, proxyFor = { null }, fingerprint = null, clock = clock, io = dispatcher)
        try {
            observer.update(RouteContext.Server(1, 1, "https://$address:8006"), true)
            test.testScheduler.runCurrent()
            return assertIs<RouteTagState.Observed>(observer.state.value).observation
        } finally { observer.close(); scope.cancel() }
    }

    @Test fun theTagsPeerPathComesFromAFreshStatus() = runTest {
        var reads = 0
        val o = observe(this, "100.64.0.10", { ts(""""CurAddr":"203.0.113.9:41641"""") }) { reads++ }
        assertEquals(PeerPath.Direct("203.0.113.9:41641"), o.peerPath); assertEquals(1, reads)
        assertEquals(PeerPath.Relayed("fra"), observe(this, "100.64.0.10", null).peerPath, "without a fresh source the snapshot's path stays")
        assertEquals(PeerPath.Relayed("fra"), observe(this, "100.64.0.10", { error("tailscale: socket gone") }).peerPath, "a failed fresh read keeps the snapshot's path")
        var notPeerReads = 0
        assertEquals(PeerPath.NotPeer, observe(this, "192.0.2.15", { ts(""""CurAddr":"203.0.113.9:41641"""") }) { notPeerReads++ }.peerPath)
        assertEquals(0, notPeerReads, "no fresh read for an address that is not a peer's own")
    }

    // ------------------------------------------------------------------ F4: output cap

    @Test fun cappedReadStopsAtTheLimit() {
        var stopped = 0
        assertEquals("abc", readCapped("abc".byteInputStream(), 3) { stopped++ }); assertEquals(0, stopped)
        assertEquals("abc", readCapped("abcd".byteInputStream(), 3) { stopped++ }); assertEquals(1, stopped)
        assertEquals(16 * 1024 * 1024, VPN_OUTPUT_LIMIT)
        assertEquals("The VPN command printed more than 16 MiB, so it was stopped.", VpnOutputLimitException(VPN_OUTPUT_LIMIT).message)
    }

    @Test fun aHelperThatPrintsTooMuchFailsWithAPlainMessage() {
        val dir = Files.createTempDirectory("vpn-cap")
        try {
            val big = dir.resolve("big.txt"); Files.writeString(big, "x".repeat(64 * 1024))
            val windows = System.getProperty("os.name").startsWith("Windows", true)
            val command = if (windows) listOf(windowsSystemRoot() + "\\System32\\cmd.exe", "/c", "type", big.toString()) else listOf("/bin/cat", big.toString())
            val e = assertFailsWith<VpnOutputLimitException> { runVpnCommand(command, 20, 4096) }
            assertEquals("The VPN command printed more than 4096 bytes, so it was stopped.", e.message)
            assertEquals(64 * 1024, runVpnCommand(command, 20, VPN_OUTPUT_LIMIT).trimEnd().length, "under the limit nothing changes")
        } finally { dir.toFile().deleteRecursively() }
    }

    // ------------------------------------------------------------------ F6: the WireGuard/Tailscale tool

    private fun tailnet(rx: Long, onlineB: Boolean = false) = parseTailscaleStatus("""{"BackendState":"Running","Self":{"ID":"s","TailscaleIPs":["100.64.0.1"]},"Peer":{
        "z":{"ID":"z","HostName":"zeta","TailscaleIPs":["100.64.0.30"],"Online":true,"Active":true,"CurAddr":"203.0.113.9:41641","RxBytes":$rx,"TxBytes":0,"LastHandshake":"2026-01-01T00:00:00Z"},
        "a":{"ID":"a","HostName":"alpha-off","TailscaleIPs":["100.64.0.20"],"Online":$onlineB},
        "b":{"ID":"b","HostName":"beta","TailscaleIPs":["100.64.0.21"],"Online":true,"Active":true,"PeerRelay":"198.51.100.7:40000:12"}}}""")

    @Test fun onlinePeersComeFirstAndOfflineOnesSaySo() {
        val snap = VpnSnapshot(Instant.EPOCH, emptyList(), true, tailnet(0), false, emptyList(), emptyList())
        val options = peerOptions(snap)
        assertEquals(listOf("beta \u2192 100.64.0.21", "zeta \u2192 100.64.0.30", "alpha-off \u2192 100.64.0.20 (offline)"), options.map { it.label })
        assertEquals("tailscale|100.64.0.20", options.last().value)
    }

    @Test fun tailscaleSamplesReadOnlyTheStatus() = runBlocking {
        var snapshots = 0
        var statuses = 0
        val rx = ArrayDeque(listOf(0L, 0L, 250_000L, 500_000L))
        val backend = object : VpnBackend {
            override val platform = "Test"
            override fun snapshot(): VpnSnapshot { snapshots++; return VpnSnapshot(Instant.EPOCH, emptyList(), true, tailnet(0), false, emptyList(), emptyList()) }
            override fun tailscaleStatus(): TailscaleStatus { statuses++; return tailnet(if (rx.size > 1) rx.removeFirst() else rx.first()) }
            override fun routeTo(host: String, snapshot: VpnSnapshot?) = VpnRoute(host, host, null, null, null)
            override fun setTunnel(tunnel: VpnTunnel, up: Boolean) = error("read-only")
            override fun setExitNode(peer: TailscalePeer?, allowLanAccess: Boolean) = error("read-only")
            override fun importWireGuard(config: java.nio.file.Path, name: String) = error("read-only")
            override fun removeWireGuard(tunnel: VpnTunnel) = error("read-only")
        }
        var clock = 0L
        val plugin = WireGuardToolPlugin({ backend }, pinger = { _, _ -> ToolResult(true, "3 sent, 3 received, 0% loss") }, pause = { clock += 2_000_000_000L },
            clock = { Instant.parse("2026-01-01T00:00:30Z") }, nanoTime = { clock })
        plugin.refreshSnapshot()
        assertEquals(1, snapshots, "the peer list is read once")
        val events = mutableListOf<ToolEvent>()
        val result = plugin.run(mapOf("peer" to "tailscale|100.64.0.30", "seconds" to "4")) { events += it }
        assertTrue(result.ok, result.summary)
        assertEquals(1, snapshots, "samples do not take whole VPN snapshots (PowerShell on Windows)")
        assertEquals(3, statuses)
        assertEquals("direct 203.0.113.9:41641", result.metrics["endpoint"])
        assertEquals(1.0, events.filterIsInstance<ToolEvent.Sample>().first { it.series == "receive" && it.y > 0 }.y, 0.01)
        assertEquals("peer relay 198.51.100.7:40000:12", tailscaleCounters(tailnet(0), "100.64.0.21").endpoint)
        assertEquals("offline", tailscaleCounters(tailnet(0), "100.64.0.20").endpoint)
    }

    // ------------------------------------------------------------------ F7: where the CLI is looked for

    @Test fun relativePathEntriesAreNeverSearched() {
        val root = Files.createTempDirectory("vpn-cwd")
        try {
            val bin = Files.createDirectories(root.resolve("bin")); Files.writeString(bin.resolve("tailscale"), "x")
            assertNull(findExecutableIn("tailscale", ".${java.io.File.pathSeparator}bin", emptyList()), "'.' and 'bin' are the working directory")
            assertEquals(bin.resolve("tailscale").toString(), findExecutableIn("tailscale", "bin${java.io.File.pathSeparator}$bin", emptyList()))
            assertNull(findExecutableIn("tailscale", "", listOf("relative")))
        } finally { root.toFile().deleteRecursively() }
        if (!System.getProperty("os.name").startsWith("Windows", true)) assertTrue("/snap/bin" in extraExecutableDirs())
    }

    @Test fun windowsLooksInProgramFilesThenTheDefaultFolderThenPath() {
        fun first(env: Map<String, String>, present: Set<String>): String? {
            val asked = mutableListOf<String>()
            val b = LocalVpnBackend("Windows", { c, _ -> if (c.last() == "--json") """{"BackendState":"Running"}""" else "{}" }, { asked += it; it.takeIf { p -> p in present } },
                hostActions = true, env = { env[it] })
            return b.snapshot().tailscaleCli
        }
        val custom = "D:\\Apps\\Tailscale\\tailscale.exe"
        assertEquals(custom, first(mapOf("ProgramFiles" to "D:\\Apps"), setOf(custom, "C:\\Program Files\\Tailscale\\tailscale.exe", "tailscale")))
        assertEquals(custom, first(mapOf("ProgramFiles" to "D:\\Apps\\"), setOf(custom)), "a trailing backslash is tolerated")
        assertEquals("C:\\Program Files\\Tailscale\\tailscale.exe", first(mapOf("ProgramFiles" to "D:\\Apps"), setOf("C:\\Program Files\\Tailscale\\tailscale.exe", "tailscale")))
        assertEquals("tailscale", first(emptyMap(), setOf("tailscale")), "PATH last (the real lookup returns an absolute path)")
        assertNull(first(emptyMap(), emptySet()))
    }

    @Test fun theSnapshotSaysWhichCliWasUsedWithoutMakingTheListIncomplete() {
        val b = LocalVpnBackend("Linux", { c, _ -> if (c.last() == "--json") """{"BackendState":"Running"}""" else "[]" }, { if (it == "tailscale") "/snap/bin/tailscale" else null }, hostActions = true)
        val s = b.snapshot()
        assertEquals("/snap/bin/tailscale", s.tailscaleCli)
        assertTrue(vpnListGaps(s).isEmpty(), "naming the binary is not a gap in the VPN list")
        assertEquals("Running", b.tailscaleStatus()?.backendState)
    }
}
