@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package local.ringleader.desktop

import androidx.compose.runtime.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.*
import local.ringleader.core.*

/** Every page of the app on invented data, as the picture matrix builds them, for the tray's placement pictures. */
internal class TickerPages(private val scope: CoroutineScope, val lab: MatrixLab, private val dir: Path) : AutoCloseable {
    val idle = Workspace(scope)
    private val title = SHOT_TITLE
    private val origin = lab.origin
    private var schedulerFixture: SchedulerFixture? = null
    private fun scheduler() = schedulerFixture ?: SchedulerFixture().also { schedulerFixture = it }

    private val fingerprint = (("3A:7F:0C:91:" + "5E:C1:".repeat(14)).take(95)).replace(":", "").lowercase()
    private val profiles = listOf(
        ConnectionProfile("00000000-0000-4000-8000-000000000021", "Sample PVE", DIRECT_PVE_KIND, "https://pve-east.example.test:8006", "root@pam!ringmaster", fingerprint),
        ConnectionProfile("00000000-0000-4000-8000-000000000040", "Sample PDM", PDM_KIND, "https://pdm.example.test:8443", "admin@pam!ringmaster", fingerprint),
        ConnectionProfile("00000000-0000-4000-8000-000000000023", "Test cluster", DIRECT_PVE_KIND, "https://pve-lab.example.test:8006", "monitor@pve!readonly", null))
    private val deskScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val deskWorkspace = Workspace(deskScope)
    private val desk: ProfileDesk by lazy {
        val deskDir = Files.createDirectories(dir.resolve("desk"))
        val store = ProfileStore(deskDir.resolve("profiles.json")).also { it.save(profiles) }
        ProfileDesk(deskWorkspace, store, vault = ReadmeVault("Windows Credential Manager"), rememberedProfiles = RememberedProfiles(deskDir.resolve("remembered.json")), io = Dispatchers.Unconfined)
    }
    private val deskMotion by lazy { MotionPreferences(Files.createDirectories(dir.resolve("desk")).resolve("motion.txt")) }

    private val scanDone: ScanSession by lazy {
        val fingerprints = listOf("3A:7F:0C:91", "B2:44:E8:15", "6D:A0:5B:F3", "C9:12:7E:60", "0F:D6:38:AB").map { head -> (head + ":" + "5E:C1:".repeat(14)).take(95) }
        val hits = listOf(Triple("pve-east", ScanKind.PVE, 21), Triple("pve-west", ScanKind.PVE, 22), Triple("pve-lab", ScanKind.PVE, 23), Triple("pdm", ScanKind.PDM, 40), Triple("backup", ScanKind.PBS, 90))
            .mapIndexed { n, (host, kind, octet) ->
                ScanHit("192.0.2.$octet", if (kind == ScanKind.PDM) 8443 else if (kind == ScanKind.PBS) 8007 else 8006, true, kind, "Proxmox 9.2", fingerprints[n],
                    "CN=$host.example.test,O=Proxmox Virtual Environment,OU=PVE Cluster Node", n == 0)
            } + listOf(Triple("Router", ScanKind.WEB, 1), Triple("Pi-hole", ScanKind.PIHOLE, 53), Triple("Home Assistant", ScanKind.HOME_ASSISTANT, 123), Triple("AdGuard Home", ScanKind.ADGUARD, 153),
                Triple("Printer", ScanKind.WEB, 201), Triple("NAS", ScanKind.WEB, 240)).mapIndexed { n, (name, kind, octet) -> ScanHit("192.0.2.$octet", if (n % 2 == 0) 80 else 443, n % 2 == 1, kind, name) }
        val plan = NetworkPlan(listOf(ScanNetwork(ScanInterface("Ethernet", "192.0.2.2", 24), "192.0.2.0/24", null, (1..254).map { "192.0.2.$it" })))
        ScanSession(scope).also {
            stateOf<NetworkProgress?>(it, "progress").value = NetworkProgress(plan, 254, hits = hits, finished = true)
            stateOf<Long>(it, "durationSeconds").value = 6
        }
    }
    private val btScan = BtScan(BtSource.Local, listOf(BtAdapter("bt-0", "MediaTek Bluetooth Adapter", "AA:BB:CC:DD:EE:01", powered = true, discovering = false)), listOf(
        BtDevice("11:22:33:44:55:01", "Keychron K2", rssi = -40, paired = true, connected = true, kind = BtKind.Classic, category = "input-keyboard"),
        BtDevice("11:22:33:44:55:02", "MX Master 3", rssi = -61, paired = true, connected = false, kind = BtKind.LowEnergy, category = "input-mouse"),
        BtDevice("11:22:33:44:55:03", "UGREEN-BT505", rssi = -57, paired = true, kind = BtKind.Classic, category = "audio-card"),
        BtDevice("11:22:33:44:55:04", "Pixel 8", rssi = -73, kind = BtKind.Dual, category = "phone"),
        BtDevice("11:22:33:44:55:07", "Living Room TV", rssi = -84, paired = true, kind = BtKind.Classic, category = "video-display"),
        BtDevice("11:22:33:44:55:08", "Framework Laptop", rssi = -49, paired = true, kind = BtKind.Dual, category = "computer"),
    ), Instant.parse("2026-11-08T18:04:05Z"))
    private val vpnSession: VpnSession by lazy {
        fun peer(n: Int, online: Boolean, exit: Boolean = false) = TailscalePeer("p$n", "host-$n", "host-$n.example.ts.net", if (n % 2 == 0) "linux" else "windows", online, listOf("100.64.0.$n"), exit, false,
            if (n % 3 == 0) null else "dfw", if (n % 3 == 0) "192.0.2.$n:41641" else null, null, 1000L * n, 500L * n)
        val ts = TailscaleStatus("Running", "1.104.4", "example.ts.net", "example.ts.net", peer(1, true), (2..9).map { peer(it, it % 2 == 0, it == 4) }, null, emptyList())
        val snapshot = VpnSnapshot(Instant.now(), listOf(
            VpnTunnel("tailscale", VpnKind.Tailscale, "example.ts.net", VpnState.Up, "Tailscale", listOf("100.64.0.1"), "4 of 8 peers online", canToggle = true),
            VpnTunnel("wg:office", VpnKind.WireGuard, "office", VpnState.Down, "office", listOf("10.8.0.2/24"), "Endpoint 203.0.113.4:51820", canToggle = true, toggleNeedsAdmin = true, canRemove = true),
        ), true, ts, true, emptyList(), listOf("WireGuard peer details need administrator rights."))
        val backend = object : VpnBackend {
            override val platform = "Windows"
            override fun snapshot() = snapshot
            override fun routeTo(host: String, snapshot: VpnSnapshot?) = VpnRoute(host, host, "Tailscale", "tailscale", "Tailscale")
            override fun setTunnel(tunnel: VpnTunnel, up: Boolean) = error("probe")
            override fun setExitNode(peer: TailscalePeer?, allowLanAccess: Boolean) = error("probe")
            override fun importWireGuard(config: Path, name: String) = error("probe")
            override fun removeWireGuard(tunnel: VpnTunnel) = error("probe")
        }
        VpnSession(scope, backend, hostActions = true)
    }
    private val tools: ToolsSession by lazy {
        ToolsSession(scope).also { tools ->
            val ping = tools.plugins.first { it.id == "ping" }
            val host = "192.0.2.21"
            val replies = listOf(3, 2, 2, 4, 3, 2, 7, 3, 2, 3, 5, 2, 9, 4, 3)
            tools.draft(ping).apply { put("host", host); put("count", "${replies.size}") }
            tools.lines += "Pinging $host with 32 bytes of data:"
            replies.forEachIndexed { n, ms -> tools.lines += "Reply from $host: bytes=32 time=${ms}ms TTL=64"; tools.samples += ToolChartSample("latency", n + 1.0, ms.toDouble(), "ms") }
            tools.status = "${replies.size} sent, ${replies.size} received, 0% loss"
        }
    }

    val names = listOf("Desk", "Fleet", "Storage", "Activity", "Scan", "Bluetooth", "VPN", "Tools", "Schedules", WATCHER_PAGE, "Under the Hood")

    fun state(page: String): WorkspaceState = when (page) {
        "Fleet" -> lab.state(lab.vm)
        "Desk" -> WorkspaceState()
        else -> lab.state()
    }

    /** Page content. [highlight] lets a test hand a page a request through the tray's own focus holder. */
    /** [variant] names a tab of the guest page when [page] is Fleet and the selected guest's own page is wanted ("Summary", "Console"...). */
    @Composable fun Content(page: String, variant: String? = null) {
        val s = state(page)
        when (page) {
            "Desk" -> { val deskState by desk.state.collectAsState(); DeskScreen(WorkspaceState(), deskState, desk, motion = deskMotion) {} }
            "Fleet" -> if (variant != null) ProvideMotionBook(s) { ChartLensHost(s, idle) { GuestDetailPage(s, idle, {}, { _, _ -> }, initialTab = variant, pins = lab.pins, connectionKey = origin, onSwitchGuest = {}) } }
                else ChartLensHost(s, idle) { InventoryScreen(s, idle, title, "", {}, "All", {}, {}, {}, lab.pins, origin) }
            "Storage" -> {
                val key = StorageKey(origin, "pve-east", "local-lvm")
                val row = lab.overview.storage.first { it.id == "local-lvm" && it.node == "pve-east" }
                StoragePage(s.copy(storageSelection = key, storageContent = ReadSection.loaded(StorageContentView(key, null, demoStorageContent(row)), lab.now, sample = true)), idle)
            }
            "Activity" -> ActivityScreen(s, idle, title, {}, {})
            "Scan" -> ScanScreen(scanDone, emptyList()) {}
            "Bluetooth" -> BtScanView(btScan, scanning = false, platform = "Windows")
            "VPN" -> VpnScreen(vpnSession, "https://100.64.0.2:8006", emptyList()) {}
            "Tools" -> ToolsScreen(tools)
            "Schedules" -> scheduler().let { SchedulesContent(it.engine, null, TrayStatus.Installed, true, {}, now = { it.now }) }
            WATCHER_PAGE -> WatcherScreen(AlertsFixtures.watcher(), WatcherActions(onUnwatch = {}), { true }, {})
            else -> UnderTheHoodScreen(s)
        }
    }

    override fun close() { idle.close(); deskWorkspace.close(); deskScope.cancel(); schedulerFixture?.close() }
}
