@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import local.ringleader.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

@Suppress("UNCHECKED_CAST")
internal fun <T> stateOf(owner: Any, name: String): MutableState<T> =
    owner.javaClass.getDeclaredField("$name\$delegate").apply { isAccessible = true }.get(owner) as MutableState<T>

/** A connected workspace whose every gateway is a stand-in that answers from [lab]: nothing is asked of a network. */
internal fun connectedWorkspace(scope: CoroutineScope, lab: MatrixLab): Workspace {
    val ws = Workspace(scope,
        gateway = object : InventoryGateway {
            override suspend fun overview(connection: Connection) = lab.overview
            override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
        },
        taskGateway = object : TaskGateway { override suspend fun tasks(connection: Connection) = lab.tasks },
        nodeStatusGateway = object : NodeStatusGateway {
            override suspend fun status(connection: Connection, origin: String, node: String, scope: String): NodeLiveStatus = lab.nodeStatus
        },
        inspectionGateway = object : GuestInspectionGateway {
            override suspend fun read(connection: Connection, guest: Guest, storages: List<String>, timeframe: String) = lab.inspectionFor(guest)
        },
        consoleStillGateway = object : ConsoleStillGateway {
            override suspend fun capture(connection: Connection, guest: Guest): ConsoleStill = lab.still
        },
        vncGateway = object : VncConsoleGateway {
            override suspend fun open(connection: Connection, guest: Guest): VncSession = pictureSession(1024, 640, drawConsole(1024, 640, listOf(
                plainLine("Debian GNU/Linux 13 ${guest.name.take(30)} tty1"), plainLine(), promptLine("ci", guest.name.take(20), "uptime"),
                plainLine(" 10:29:41 up 1:30,  1 user,  load average: 0.61, 0.58, 0.40"), promptLine("ci", guest.name.take(20), ""))))
        })
    ws.connect(Connection(lab.origin, "demo@pve!matrix", "invented-matrix-secret", ""))
    ws.setLive(false)
    return ws
}

private fun WorkspaceState.withLabHistory(lab: MatrixLab) = copy(history = lab.history, tasks = lab.tasks, tasksUpdatedAt = lab.now)

/** Every screen and state the matrix draws. The names are the folder names of the pictures. */
internal fun matrixShots(scope: CoroutineScope, lab: MatrixLab, env: MatrixEnv): List<MatrixShot> {
    val origin = lab.origin
    val title = "Lab cluster · $origin"
    val shots = ArrayList<MatrixShot>()

    // ---- Desk -------------------------------------------------------------------------------------------------------
    val fingerprint = (("3A:7F:0C:91:" + "5E:C1:".repeat(14)).take(95)).replace(":", "").lowercase()
    val profiles = listOf(
        ConnectionProfile("00000000-0000-4000-8000-000000000021", "Sample PVE", DIRECT_PVE_KIND, "https://pve-east.example.test:8006", "root@pam!ringmaster", fingerprint),
        ConnectionProfile("00000000-0000-4000-8000-000000000040", "Sample PDM", PDM_KIND, "https://pdm.example.test:8443", "admin@pam!ringmaster", fingerprint),
        ConnectionProfile("00000000-0000-4000-8000-000000000023", "Test cluster in the second rack of the east datacentre", DIRECT_PVE_KIND, "https://pve-lab-east-02.internal.example.test:8006", "monitor@pve!readonly-token-for-dashboards", null))
    fun deskShot(name: String, withProfiles: Boolean = true, rail: Boolean = false, connected: Boolean = false, end: Boolean = false, setup: (ProfileDesk) -> Unit = {}): MatrixShot {
        val deskScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val deskWorkspace = Workspace(deskScope)
        val deskDir = Files.createDirectories(env.dir.resolve("desk-$name"))
        val store = ProfileStore(deskDir.resolve("profiles.json"))
        if (withProfiles) store.save(profiles)
        val desk = ProfileDesk(deskWorkspace, store, vault = ReadmeVault("Windows Credential Manager"),
            rememberedProfiles = RememberedProfiles(deskDir.resolve("remembered.json")), io = Dispatchers.Unconfined)
        setup(desk)
        val motion = MotionPreferences(deskDir.resolve("motion.txt"))
        return MatrixShot(name, "Desk", rail, scrollEnd = end) { e ->
            val deskState by desk.state.collectAsState()
            val shown = if (connected) e.state() else WorkspaceState()
            MatrixWindow("Desk", shown, e.idle, e, rail) {
                DeskScreen(shown, if (connected) deskState.copy(connectedProfileId = profiles[0].id) else deskState, desk, motion = motion) {}
            }
        }
    }
    shots += deskShot("desk-saved")
    shots += deskShot("desk-selected") { it.selectProfile(profiles[0].id) }
    shots += deskShot("desk-empty", withProfiles = false)
    shots += deskShot("desk-manual", end = true)
    shots += deskShot("desk-new-profile", end = true) { it.beginCreate() }
    shots += deskShot("desk-editor") { it.selectProfile(profiles[2].id); it.beginEdit() }
    shots += deskShot("desk-connected", connected = true) { it.selectProfile(profiles[0].id) }

    // ---- Fleet and the inspector ------------------------------------------------------------------------------------
    fun fleet(name: String, guest: Guest?, node: String? = null, rail: Boolean = false, frames: Int = 6, end: Boolean = false) = MatrixShot(name, "Fleet", rail, frames = frames, scrollEnd = end) { e ->
        val s = e.state(guest, node)
        MatrixWindow("Fleet", s, e.idle, e, rail) { ChartLensHost(s, e.idle) { InventoryScreen(s, e.idle, title, "", {}, "All", {}, {}, {}, lab.pins, origin) } }
    }
    shots += fleet("fleet-vm", lab.vm)
    shots += fleet("fleet-vm-long", lab.longVm)
    shots += fleet("fleet-vm-stopped", lab.stoppedVm)
    shots += fleet("fleet-template", lab.guest(129))
    shots += fleet("fleet-ct", lab.container)
    shots += fleet("fleet-ct-long", lab.longContainer)
    shots += fleet("fleet-node", null, "pve-east")
    shots += fleet("fleet-node-offline", null, "pve-archive-03")
    shots += fleet("fleet-closed", null)
    shots += fleet("fleet-vm-rail", lab.vm, rail = true)
    // The same states scrolled to the bottom: below 900 units the list sits above the inspector and the page scrolls.
    shots += fleet("fleet-vm-end", lab.vm, end = true)
    shots += fleet("fleet-ct-end", lab.container, end = true)
    shots += fleet("fleet-node-end", null, "pve-east", end = true)
    shots += fleet("fleet-closed-end", null, end = true)

    // ---- The guest page ---------------------------------------------------------------------------------------------
    fun guestPage(name: String, guest: Guest, tab: String, frames: Int = 6, end: Boolean = false, prepare: (suspend (androidx.compose.ui.ImageComposeScene, MatrixDriver) -> Unit)? = null) =
        MatrixShot(name, "Fleet", frames = frames, scrollEnd = end, prepare = prepare) { e ->
            val s = e.state(guest)
            MatrixWindow("Fleet", s, e.idle, e, false) {
                ProvideMotionBook(s) { ChartLensHost(s, e.idle) { GuestDetailPage(s, e.idle, {}, { _, _ -> }, initialTab = tab, pins = lab.pins, connectionKey = origin, onSwitchGuest = {}) } }
            }
        }
    shots += guestPage("guest-summary", lab.vm, "Summary")
    shots += guestPage("guest-summary-long", lab.longVm, "Summary")
    shots += guestPage("guest-summary-stopped", lab.stoppedVm, "Summary")
    shots += guestPage("guest-summary-end", lab.vm, "Summary", end = true)
    shots += guestPage("guest-hardware", lab.vm, "Hardware")
    shots += guestPage("guest-hardware-end", lab.vm, "Hardware", end = true)
    shots += guestPage("guest-snapshots", lab.vm, "Snapshots")
    shots += guestPage("guest-snapshots-form", lab.vm, "Snapshots") { scene, d -> d.activateRequired(scene, "guest-snapshots-form", "Create snapshot") }
    shots += guestPage("guest-backups", lab.vm, "Backups")
    shots += guestPage("guest-bluetooth", lab.vm, "Bluetooth")
    shots += guestPage("guest-actions", lab.vm, "Actions")
    shots += guestPage("guest-ct-summary", lab.container, "Summary")
    shots += guestPage("guest-ct-hardware", lab.container, "Hardware")
    shots += guestPage("guest-ct-actions", lab.container, "Actions")
    shots += guestPage("guest-ct-long-summary", lab.longContainer, "Summary")
    shots += MatrixShot("dlg-pin-service", "Fleet") { e ->
        val s = e.state(lab.vm)
        MatrixWindow("Fleet", s, e.idle, e, false) {
            ProvideMotionBook(s) { GuestDetailPage(s, e.idle, {}, { _, _ -> }, initialTab = "Summary", pins = lab.pins, connectionKey = origin, onSwitchGuest = {}) }
        }
        val report = s.inspection
        ServicePinForm(
            hints = GuestHints(lab.vm.name, lab.vm.tags, report?.hardware?.value?.fields?.get("description")),
            hosts = report?.addresses?.value.orEmpty().mapNotNull(::reportedHost).distinct(),
            canProbe = false,
            onDismiss = {},
            onSave = { _, _ -> null },
            probeUnavailable = "Network probes are disabled in the synthetic screenshot harness.",
        )
    }
    fun console(name: String, expanded: Boolean) = MatrixShot(name, "Fleet", frames = 30, stepMs = 50) { e ->
        val ws = e.connected
        ConsoleSwitcherPreference.expanded = expanded
        LaunchedEffect(Unit) { if (ws.state.value.selected?.id != lab.vm.id) ws.select(lab.vm) }
        val shown by ws.state.collectAsState()
        val s = shown.withLabHistory(lab)
        MatrixWindow("Fleet", s, ws, e, false) {
            ProvideMotionBook(s) { GuestDetailPage(s, ws, {}, { _, _ -> }, initialTab = "Console", pins = lab.pins, connectionKey = origin, onSwitchGuest = { ws.select(it) }) }
        }
    }
    shots += console("guest-console-rail-open", true)
    shots += console("guest-console-rail-folded", false)
    shots += MatrixShot("guest-ct-console", "Fleet", frames = 8) { e ->
        val s = e.state(lab.container)
        MatrixWindow("Fleet", s, e.idle, e, false) { ProvideMotionBook(s) { GuestDetailPage(s, e.idle, {}, { _, _ -> }, initialTab = "Console", pins = lab.pins, connectionKey = origin, onSwitchGuest = {}) } }
    }

    // ---- The other pages --------------------------------------------------------------------------------------------
    val localLvm = lab.overview.storage.first { it.id == "local-lvm" && it.node == "pve-east" }
    val storageKey = StorageKey(origin, "pve-east", "local-lvm")
    shots += MatrixShot("storage", "Storage") { e ->
        val s = e.state().copy(storageSelection = storageKey, storageContent = ReadSection.loaded(StorageContentView(storageKey, null, demoStorageContent(localLvm)), lab.now, sample = true))
        MatrixWindow("Storage", s, e.idle, e, false) { StoragePage(s, e.idle) }
    }
    shots += MatrixShot("storage-empty", "Storage") { e ->
        val s = e.state()
        MatrixWindow("Storage", s, e.idle, e, false) { StoragePage(s, e.idle) }
    }
    shots += MatrixShot("activity", "Activity") { e ->
        val s = e.state()
        MatrixWindow("Activity", s, e.idle, e, false) { ActivityScreen(s, e.idle, title, {}, {}) }
    }
    shots += MatrixShot("activity-log", "Activity") { e ->
        val s = e.state().copy(taskLog = demoTaskLog(lab.tasks[5]))
        MatrixWindow("Activity", s, e.idle, e, false) { ActivityScreen(s, e.idle, title, {}, {}) }
    }

    val fingerprints = listOf("3A:7F:0C:91", "B2:44:E8:15", "6D:A0:5B:F3", "C9:12:7E:60", "0F:D6:38:AB").map { head -> (head + ":" + "5E:C1:".repeat(14)).take(95) }
    val hits = listOf(Triple("pve-east", ScanKind.PVE, 21), Triple("pve-west", ScanKind.PVE, 22), Triple("pve-lab", ScanKind.PVE, 23), Triple("pdm", ScanKind.PDM, 40), Triple("backup", ScanKind.PBS, 90))
        .mapIndexed { n, (host, kind, octet) ->
            ScanHit("192.0.2.$octet", if (kind == ScanKind.PDM) 8443 else if (kind == ScanKind.PBS) 8007 else 8006, true, kind, "Proxmox 9.2", fingerprints[n],
                "CN=$host.example.test,O=Proxmox Virtual Environment,OU=PVE Cluster Node", n == 0)
        } + listOf(Triple("Router", ScanKind.WEB, 1), Triple("Pi-hole", ScanKind.PIHOLE, 53), Triple("Home Assistant", ScanKind.HOME_ASSISTANT, 123), Triple("AdGuard Home", ScanKind.ADGUARD, 153),
            Triple("Printer", ScanKind.WEB, 201), Triple("NAS with a long product name and firmware banner", ScanKind.WEB, 240)).mapIndexed { n, (name, kind, octet) ->
            ScanHit("192.0.2.$octet", if (n % 2 == 0) 80 else 443, n % 2 == 1, kind, name)
        }
    val plan = NetworkPlan(listOf(ScanNetwork(ScanInterface("Ethernet", "192.0.2.2", 24), "192.0.2.0/24", null, (1..254).map { "192.0.2.$it" })))
    val scanDone = ScanSession(scope).also {
        stateOf<NetworkProgress?>(it, "progress").value = NetworkProgress(plan, 254, hits = hits, finished = true)
        stateOf<Long>(it, "durationSeconds").value = 6
    }
    val scanIdle = ScanSession(scope)
    shots += MatrixShot("scan-results", "Scan") { e -> MatrixWindow("Scan", e.state(), e.idle, e, false) { ScanScreen(scanDone, emptyList()) {} } }
    shots += MatrixShot("scan-idle", "Scan") { e -> MatrixWindow("Scan", e.state(), e.idle, e, false) { ScanScreen(scanIdle, emptyList()) {} } }

    val btAt = Instant.parse("2026-11-08T18:04:05Z")
    val btScan = BtScan(BtSource.Local, listOf(BtAdapter("bt-0", "MediaTek Bluetooth Adapter", "AA:BB:CC:DD:EE:01", powered = true, discovering = false)), listOf(
        BtDevice("11:22:33:44:55:01", "Keychron K2", rssi = -40, paired = true, connected = true, kind = BtKind.Classic, category = "input-keyboard"),
        BtDevice("11:22:33:44:55:02", "MX Master 3 wireless mouse with a very long advertised name", rssi = -61, paired = true, connected = false, kind = BtKind.LowEnergy, category = "input-mouse"),
        BtDevice("11:22:33:44:55:03", "UGREEN-BT505", rssi = -57, paired = true, kind = BtKind.Classic, category = "audio-card"),
        BtDevice("11:22:33:44:55:04", "Pixel 8", rssi = -73, kind = BtKind.Dual, category = "phone"),
        BtDevice("11:22:33:44:55:05", rssi = -93, kind = BtKind.LowEnergy),
        BtDevice("11:22:33:44:55:07", "Living Room TV", rssi = -84, paired = true, kind = BtKind.Classic, category = "video-display"),
        BtDevice("11:22:33:44:55:08", "Framework Laptop", rssi = -49, paired = true, kind = BtKind.Dual, category = "computer"),
    ), btAt)
    shots += MatrixShot("bluetooth-results", "Bluetooth") { e -> MatrixWindow("Bluetooth", e.state(), e.idle, e, false) { BtScanView(btScan, scanning = false, platform = "Windows") } }
    shots += MatrixShot("bluetooth-empty", "Bluetooth") { e -> MatrixWindow("Bluetooth", e.state(), e.idle, e, false) { BluetoothScreen(null) } }

    val vpnSnapshot = run {
        fun peer(n: Int, online: Boolean, exit: Boolean = false) = TailscalePeer("p$n", "host-$n", "host-$n.example.ts.net", if (n % 2 == 0) "linux" else "windows", online, listOf("100.64.0.$n"), exit, false,
            if (n % 3 == 0) null else "dfw", if (n % 3 == 0) "192.0.2.$n:41641" else null, null, 1000L * n, 500L * n)
        val ts = TailscaleStatus("Running", "1.104.4", "example.ts.net", "example.ts.net", peer(1, true), (2..9).map { peer(it, it % 2 == 0, it == 4) }, null, emptyList())
        VpnSnapshot(Instant.now(), listOf(
            VpnTunnel("tailscale", VpnKind.Tailscale, "example.ts.net", VpnState.Up, "Tailscale", listOf("100.64.0.1"), "4 of 8 peers online", canToggle = true),
            VpnTunnel("wg:office", VpnKind.WireGuard, "office", VpnState.Down, "office", listOf("10.8.0.2/24"), "Endpoint 203.0.113.4:51820", canToggle = true, toggleNeedsAdmin = true, canRemove = true),
            VpnTunnel("ras:Work", VpnKind.IpSec, "Work", VpnState.Down, detail = "Server vpn.example.com", canToggle = true),
        ), true, ts, true, emptyList(), listOf("WireGuard peer details need administrator rights."))
    }
    val vpnBackend = object : VpnBackend {
        override val platform = "Windows"
        override fun snapshot() = vpnSnapshot
        override fun routeTo(host: String, snapshot: VpnSnapshot?) = VpnRoute(host, host, "Tailscale", "tailscale", "Tailscale")
        override fun setTunnel(tunnel: VpnTunnel, up: Boolean) = error("probe")
        override fun setExitNode(peer: TailscalePeer?, allowLanAccess: Boolean) = error("probe")
        override fun importWireGuard(config: Path, name: String) = error("probe")
        override fun removeWireGuard(tunnel: VpnTunnel) = error("probe")
    }
    shots += MatrixShot("vpn", "VPN", frames = 12, prepare = { scene, d -> delay(300); d.frames(scene, 8) }) { e ->
        val session = remember { VpnSession(scope, vpnBackend, hostActions = true) }
        MatrixWindow("VPN", e.state(), e.idle, e, false) { VpnScreen(session, "https://100.64.0.2:8006", emptyList()) {} }
    }

    val tools = ToolsSession(scope).also { tools ->
        val ping = tools.plugins.first { it.id == "ping" }
        val host = "192.0.2.21"
        val replies = listOf(3, 2, 2, 4, 3, 2, 7, 3, 2, 3, 5, 2, 9, 4, 3)
        tools.draft(ping).apply { put("host", host); put("count", "${replies.size}") }
        tools.lines += "Pinging $host with 32 bytes of data:"
        replies.forEachIndexed { n, ms ->
            tools.lines += "Reply from $host: bytes=32 time=${ms}ms TTL=64"
            tools.samples += ToolChartSample("latency", n + 1.0, ms.toDouble(), "ms")
        }
        val summary = "${replies.size} sent, ${replies.size} received, 0% loss, min ${replies.min()} ms, avg ${"%.1f".format(replies.average())} ms, max ${replies.max()} ms"
        tools.lines += listOf("", "Ping statistics for $host:", "    Packets: Sent = ${replies.size}, Received = ${replies.size}, Lost = 0 (0% loss),", summary)
        tools.status = summary
    }
    val toolsIdle = ToolsSession(scope)
    shots += MatrixShot("tools-result", "Tools") { e -> MatrixWindow("Tools", e.state(), e.idle, e, false) { ToolsScreen(tools) } }
    shots += MatrixShot("tools-idle", "Tools") { e -> MatrixWindow("Tools", e.state(), e.idle, e, false) { ToolsScreen(toolsIdle) } }

    Trace.clear()
    val calls = listOf("GET /cluster/resources" to "cluster/resources", "GET /cluster/tasks" to "cluster/tasks", "GET /nodes/pve-east/status" to "nodes/pve-east/status",
        "GET /nodes/pve-west/qemu/125/status/current" to "nodes/pve-west/qemu/125/status/current", "GET /nodes/pve-east/qemu/120/rrddata" to "nodes/pve-east/qemu/120/rrddata")
    runBlocking {
        repeat(36) { i ->
            val (summary, path) = calls[i % calls.size]
            val span = Trace.begin(if (i % 9 == 4) TraceSource.PdmApi else TraceSource.PveApi, if (i % 9 == 4) "GET /resources/list" else summary,
                mapOf("demo" to "true", "url" to "https://pve-east.example.test:8006/api2/json/$path"))
            delay(listOf(2L, 5L, 3L, 9L, 14L, 6L)[i % 6])
            span.end(status = if (i % 13 == 0) 503 else 200, bytesIn = listOf(18_432L, 2_210L, 1_604L, 905L, 7_340L, 3_118L)[i % 6])
            if (i % 4 == 0) Trace.emit(TraceSource.Task, "poll", "Task polling · running", mapOf("demo" to "true"))
        }
    }
    shots += MatrixShot("hood", "Under the Hood", frames = 10) { e ->
        CompositionLocalProvider(LocalHoodProbe provides true) { MatrixWindow("Under the Hood", e.state(), e.idle, e, false) { UnderTheHoodScreen(WorkspaceState(demo = true)) } }
    }

    // ---- Settings, About and the dialogs ----------------------------------------------------------------------------
    val manifest = UpdateManifest("0.24.0", Instant.parse("2026-11-10T00:00:00Z"),
        "A signed release with clearer settings and safer configuration. Plain text <b>stays plain text</b>.", null, null, emptyList())
    val offer = OfferedUpdate(manifest, UpdateAsset("debian-13-amd64-deb", "https://example.com/ringmaster-0.24.0-amd64.deb", "a".repeat(64), 1048576))
    val updates = UpdateChecker(AppVersion.value, UpdatePreferences(Files.createDirectories(env.dir.resolve("updates")).resolve("updates.properties")))
    fun behindFleet(e: MatrixEnv, content: @Composable (WorkspaceState) -> Unit): @Composable () -> Unit = {
        val s = e.state(lab.vm)
        MatrixWindow("Fleet", s, e.idle, e, false) {
            ChartLensHost(s, e.idle) { InventoryScreen(s, e.idle, title, "", {}, "All", {}, {}, {}, lab.pins, origin) }
        }
        content(s)
    }
    // Scene-only resident: explicit temporary preferences, no lock, IPC, scheduler, shutdown hook or native tray.
    val settingsResident = ResidentRuntime(null, null, null, null,
        MotionPreferences(env.dir.resolve("matrix-background.properties")), { _, _, _ -> object : TrayPort {
            override fun install() = TrayStatus.Installed
            override fun update(tooltip: String, line: String) {}
            override fun notify(title: String, text: String) {}
            override fun remove() {}
        } }, {}).apply { installTray() }
    for (section in SettingsSection.entries) {
        shots += MatrixShot("settings-${section.name.lowercase()}", "Fleet") { e -> behindFleet(e) { s ->
            CompositionLocalProvider(LocalResident provides settingsResident) { SettingsDialog(s, e.idle, section, {}, {}, {}) }
        }() }
    }
    shots += MatrixShot("settings-updates-available", "Fleet") { e -> behindFleet(e) { s ->
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val size = settingsSheetSize(maxWidth, maxHeight)
            SettingsSheet(s, e.idle, SettingsSection.Updates, {}, {}, {}, Modifier.size(size.width, size.height), updatesChecker = updates, updatePreview = UpdateState.Available(offer), modal = true)
        }
    }() }
    shots += MatrixShot("dlg-clear", "Fleet", prepare = { scene, d ->
        d.scrollToEnd(scene)
        // Open the actual dialog through its production action. Large scaled popup coordinates are not
        // native input evidence; the dialog state contract below must still pass before capture.
        d.activateSemanticsRequired(scene, "dlg-clear", "Clear everything…")
    }) { e ->
        behindFleet(e) { s -> SettingsDialog(s, e.idle, SettingsSection.About, {}, {}, {}) }()
    }
    shots += MatrixShot("dlg-update-prompt", "Fleet") { e -> behindFleet(e) { UpdateConsentDialog({}, {}) }() }
    shots += MatrixShot("dlg-chart-lens", "Fleet", frames = 12) { e ->
        val s = e.state(lab.vm)
        MatrixWindow("Fleet", s, e.idle, e, false) {
            ChartLensHost(s, e.idle) {
                val lens = LocalChartLens.current
                LaunchedEffect(Unit) { lens?.open(guestLens(lab.vm, LiveMetric.Cpu)) }
                InventoryScreen(s, e.idle, title, "", {}, "All", {}, {}, {}, lab.pins, origin)
            }
        }
    }
    shots += MatrixShot("dlg-power-hardstop", "Fleet", frames = 10, prepare = { scene, d ->
        d.scrollToEnd(scene)
        d.activateRequired(scene, "dlg-power-hardstop", "Hard stop")
    }) { e ->
        val ws = e.connected
        LaunchedEffect(Unit) { if (ws.state.value.selected?.id != lab.vm.id) ws.select(lab.vm) }
        val shown by ws.state.collectAsState()
        val s = shown.withLabHistory(lab)
        MatrixWindow("Fleet", s, ws, e, false) { ChartLensHost(s, ws) { InventoryScreen(s, ws, title, "", {}, "All", {}, {}, {}, lab.pins, origin) } }
    }

    // Real production wizards over core's complete, invented demo choices. Nothing is submitted.
    fun createState(kind: NewGuestKind) = lab.state(demo = true).copy(
        creation = demoCreateSession(kind, "pve-east", 1, lab.now),
        connectionEpoch = 1,
    )
    shots += MatrixShot("create-vm", "Fleet") { e ->
        val s = createState(NewGuestKind.Vm)
        MatrixWindow("Fleet", s, e.idle, e, false) { CreateGuestHost(s, e.idle, "Demonstration", {}, onViewLog = {}) }
    }
    shots += MatrixShot("create-ct", "Fleet") { e ->
        val s = createState(NewGuestKind.Container)
        MatrixWindow("Fleet", s, e.idle, e, false) { CreateGuestHost(s, e.idle, "Demonstration", {}, onViewLog = {}) }
    }
    return shots
}
