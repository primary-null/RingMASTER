package local.ringleader.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import local.ringleader.core.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.sin

/**
 * Opt-in: renders the README pictures from the app's own screens with invented sample data.
 * Set RINGMASTER_README_SHOTS to an output folder. Never connects to a server; every name and address is made up.
 * Writes fleet, guest-summary, activity, chart-lens, settings, network-tools, sign-in, console and under-the-hood PNGs,
 * plus scan-clip/NNN.png frames (15 per second) for the animated scan.
 */
class ReadmeShotsTest {
    @Test fun shots() {
        val dir = File(System.getenv("RINGMASTER_README_SHOTS") ?: return).apply { mkdirs() }
        // The app keeps its settings and plug-ins under the home folder. An empty one stands in for it, so nothing from
        // this computer (a plug-in, a saved preference) can reach a picture. XDG_CONFIG_HOME would win over it.
        check(System.getenv("XDG_CONFIG_HOME").isNullOrBlank()) { "Unset XDG_CONFIG_HOME for the README pictures." }
        val home = System.getProperty("user.home")
        val sandbox = Files.createTempDirectory("ringmaster-readme-home")
        // Times are shown in UTC, so the pictures do not tell where or when in the day they were made.
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        System.setProperty("user.home", sandbox.toString())
        try { runSceneTest { render(dir, sandbox) } } finally {
            TimeZone.setDefault(zone)
            System.setProperty("user.home", home)
            sandbox.toFile().deleteRecursively()
        }
        val written = dir.listFiles { file -> file.extension == "png" }.orEmpty()
        check(written.isNotEmpty())
        for (file in written) {
            val bad = pngChunks(file).filter { it in forbiddenPngChunks }
            check(bad.isEmpty()) { "${file.name} carries metadata chunks $bad" }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> stateOf(owner: Any, name: String) =
        owner.javaClass.getDeclaredField("$name\$delegate").apply { isAccessible = true }.get(owner) as MutableState<T>

    private suspend fun render(dir: File, sandbox: java.nio.file.Path) {
        val context = currentCoroutineContext()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = Workspace(scope)
        workspace.exploreDemo()
        workspace.select(workspace.state.value.overview!!.guests.first())
        workspace.inspectGuest("hour")
        delay(1500)
        val base = workspace.state.value
        val now = Instant.now()
        val gib = 1073741824L

        // name, kind source (0 VM running, 1 CT running, 2 VM stopped), node, cpu level, memory GiB used/limit, tags. Guest ids are 120 + index.
        data class Row(val name: String, val from: Int, val node: Char, val cpu: Double, val mem: Double, val max: Int, val tags: List<String>)
        val rows = listOf(
            Row("debian-workbench", 0, 'a', .22, 2.6, 8, listOf("dev")),
            Row("dns-service", 1, 'a', .03, 0.3, 1, listOf("infra")),
            Row("build-runner", 0, 'a', .61, 9.8, 16, listOf("ci")),
            Row("metrics", 1, 'a', .14, 1.7, 4, listOf("infra")),
            Row("gateway", 0, 'b', .07, 0.9, 2, listOf("infra", "edge")),
            Row("database", 0, 'b', .34, 11.2, 16, listOf("data")),
            Row("web", 1, 'b', .18, 0.8, 2, listOf("edge")),
            Row("media-library", 0, 'c', .09, 3.1, 8, emptyList()),
            Row("archive", 2, 'c', .0, .0, 4, listOf("data")),
            Row("ubuntu-template", 2, 'c', .0, .0, 4, emptyList()),
        )
        fun nodeOf(id: String) = id.toIntOrNull()?.let { rows.getOrNull(it - 120) }?.let { "demo-node-${it.node}" }

        // Recent tasks, newest first: type, guest id, seconds it took (null while running), seconds ago it started, failed.
        data class Job(val type: String, val id: String, val seconds: Long?, val ago: Long, val failed: Boolean = false)
        val jobs = listOf(
            Job("vzdump", "121", null, 95), Job("qmstart", "122", 4, 1_310), Job("qmsnapshot", "120", 9, 2_580),
            Job("vzstart", "126", 2, 3_400), Job("qmshutdown", "128", 27, 4_720), Job("vzdump", "125", 48, 5_960, failed = true),
            Job("qmrollback", "120", 14, 7_150), Job("aptupdate", "", 11, 8_830), Job("vzdump", "123", 196, 9_640),
            Job("qmstart", "127", 5, 11_020), Job("qmreboot", "124", 33, 12_510), Job("vzdump", "120", 241, 14_400),
        )
        // A guest started or rebooted by one of those tasks has been up since then.
        val upSince = jobs.filter { it.type in setOf("qmstart", "vzstart", "qmreboot") && !it.failed && it.seconds != null }
            .associate { it.id to it.ago - it.seconds!! }

        fun series(seed: Int, level: Double, memUsed: Double, memMax: Int) = Series((0..59).map { n ->
            val wave = sin(n * (.21 + seed * .037) + seed) * .35 + sin(n * (.83 + seed * .05) + seed * 2.1) * .18
            val cpu = (level * (1 + wave)).coerceIn(.004, .98)
            Sample(now.minusSeconds((59 - n) * 10L), cpu, (memUsed * gib * (1 + sin(n * .11 + seed) * .03)).toLong(), memMax * gib,
                (40_000 + seed * 9_000) * (1 + wave), (12_000 + seed * 4_000) * (1 + sin(n * .4 + seed) * .4))
        })
        // The guest page's hour of history, one point a minute, in the shape Proxmox's rrddata returns.
        fun hourHistory(seed: Int, level: Double, memUsed: Double, memMax: Int, cores: Int) = (0..59).map { n ->
            val wave = sin(n * .19 + seed) * .35 + sin(n * .71 + seed * 2.1) * .18
            HistoryPoint((now.epochSecond - (59 - n) * 60L).toDouble(), mapOf(
                "maxcpu" to cores.toDouble(), "cpu" to (level * (1 + wave)).coerceIn(.004, .98),
                "maxmem" to (memMax * gib).toDouble(), "mem" to memUsed * gib * (1 + sin(n * .13 + seed) * .04),
                "netin" to 42_000.0 * (1 + wave), "netout" to 13_000.0 * (1 + abs(sin(n * .4 + seed)) * .6),
                "diskread" to 60_000.0 + 45_000.0 * abs(sin(n * .5 + seed)), "diskwrite" to 30_000.0 + 22_000.0 * abs(sin(n * .37 + seed * 1.3))))
        }
        val guests = rows.mapIndexed { n, r ->
            val running = r.from != 2
            val id = 120 + n
            base.overview!!.guests[r.from].copy(id = id, name = r.name, node = "demo-node-${r.node}", template = r.name.endsWith("template"), tags = r.tags,
                // The one running backup holds its guest's lock; nothing else is locked.
                lock = if (id == 121) "backup" else null,
                metrics = GuestListMetrics(cpuFraction = if (running) r.cpu else null, maxCpu = if (r.max >= 8) 4.0 else 2.0,
                    memoryBytes = if (running) (r.mem * gib).toLong() else null, memoryLimitBytes = r.max * gib,
                    uptimeSeconds = if (running) upSince["$id"] ?: (86_400L * (2 + n * 3) + 3_600L * n) else null,
                    netIn = if (running) 40_000L + n * 9_000 else null, netOut = if (running) 12_000L + n * 4_000 else null))
        }
        val perGuest = guests.mapIndexedNotNull { n, g -> if (rows[n].from == 2) null else g.resource to series(n + 1, rows[n].cpu, rows[n].mem, rows[n].max) }.toMap()
        val perNode = listOf('a' to .26, 'b' to .19, 'c' to .08).mapIndexed { n, (c, level) -> "demo-node-$c" to series(20 + n, level, listOf(14.4, 12.9, 3.1)[n], 32) }.toMap()
        val member = base.overview!!.members.first()
        val members = listOf('a', 'b', 'c').map { member.copy(name = "demo-node-$it") }
        val workbench = guests.first()
        val hardware = parseHardware("""{"data":{"cores":4,"memory":8192,"ostype":"l26","agent":"1","scsi0":"local-lvm:vm-120-disk-0,size=32G","net0":"virtio=02:00:00:00:01:20,bridge=vmbr0,firewall=1","description":"Workbench for builds and tests","onboot":1,"tags":"dev"}}""")
        val history = hourHistory(1, rows[0].cpu, rows[0].mem, rows[0].max, 4)
        val inspection = base.inspection!!.copy(
            hardware = InspectionSection(hardware), history = InspectionSection(history),
            snapshots = InspectionSection(listOf(
                GuestSnapshot("clean-install", null, now.epochSecond - 86_400 * 21, "Fresh install, updates applied", false, false),
                GuestSnapshot("before-upgrade", "clean-install", now.epochSecond - 86_400 * 6, "Before the kernel upgrade", true, false),
                GuestSnapshot("toolchain", "before-upgrade", now.epochSecond - 2_580, "Compiler toolchain installed", false, false),
                GuestSnapshot("current", "toolchain", null, null, false, true))),
            backups = InspectionSection((0..4).map { n -> GuestBackup("sample-$n", "backup-store", now.epochSecond - 14_400 - n * 86_400, 2 * gib + n * 73_400_320L, "Scheduled backup", n == 1) }))
        val tasks = jobs.mapIndexed { n, job ->
            val outcome = when { job.seconds == null -> TaskOutcome.Running; job.failed -> TaskOutcome.Unsuccessful; else -> TaskOutcome.Succeeded }
            val start = now.minusSeconds(job.ago)
            ClusterTask(DEMO_REMOTE_KEY, "demo:readme$n", nodeOf(job.id) ?: "demo-node-b", job.type, job.id, "demo@ringmaster", start,
                job.seconds?.let { start.plusSeconds(it) },
                when (outcome) { TaskOutcome.Running -> "running"; TaskOutcome.Succeeded -> "OK"; else -> "Backup storage unavailable" }, outcome)
        }
        val state = base.copy(selected = workbench, history = FleetHistory(perGuest, perNode, series(40, .19, 30.4, 96)),
            overview = base.overview!!.copy(nodes = 3, guests = guests, members = members),
            inspection = inspection.copy(resource = workbench.resource, node = workbench.node), tasks = tasks,
            chartHistory = InspectionSection(history), chartHistoryLoading = false)
        // The fleet picture inspects a container: its inspector row (no Console button) fits the 1600 px window.
        val fleetState = state.copy(selected = guests[6])

        val temp = Files.createTempDirectory(sandbox, "readme")
        val pins = PinStore(temp.resolve("pins.json"))
        pins.toggleGuest("demo", guests[0]); pins.toggleView("demo", guests[0], "Console")
        pins.addService("demo", guests[1], "DNS admin", "http://192.0.2.53:3000")
        pins.toggleGuest("demo", guests[5]); pins.toggleView("demo", guests[2], "Snapshots"); pins.toggleGuest("demo", guests[4])

        val fingerprints = listOf("3A:7F:0C:91", "B2:44:E8:15", "6D:A0:5B:F3", "C9:12:7E:60", "0F:D6:38:AB").map { head -> (head + ":" + "5E:C1:".repeat(14)).take(95) }
        // Spread over the /24 so the animated sweep keeps finding things to the end.
        val hits = listOf(Triple("pve-east", ScanKind.PVE, 21), Triple("pve-west", ScanKind.PVE, 22), Triple("pve-lab", ScanKind.PVE, 23),
            Triple("pdm", ScanKind.PDM, 40), Triple("backup", ScanKind.PBS, 90)).mapIndexed { n, (host, kind, octet) ->
            ScanHit("192.168.50.$octet", if (kind == ScanKind.PDM) 8443 else if (kind == ScanKind.PBS) 8007 else 8006, true, kind, "Proxmox 9.2", fingerprints[n],
                "CN=$host.example.test,O=Proxmox Virtual Environment,OU=PVE Cluster Node", n == 0)
        } + listOf(Triple("Router", ScanKind.WEB, 1), Triple("Pi-hole", ScanKind.PIHOLE, 53), Triple("Home Assistant", ScanKind.HOME_ASSISTANT, 123),
            Triple("AdGuard Home", ScanKind.ADGUARD, 153), Triple("Printer", ScanKind.WEB, 201), Triple("NAS", ScanKind.WEB, 240)).mapIndexed { n, (title, kind, octet) ->
            ScanHit("192.168.50.$octet", if (n % 2 == 0) 80 else 443, n % 2 == 1, kind, title)
        }
        val plan = NetworkPlan(listOf(ScanNetwork(ScanInterface("Ethernet", "192.168.50.2", 24), "192.168.50.0/24", null, (1..254).map { "192.168.50.$it" })))
        val session = ScanSession(scope)
        val progress = stateOf<NetworkProgress?>(session, "progress")
        val running = stateOf<Boolean>(session, "running")
        val seconds = stateOf<Long>(session, "durationSeconds")
        progress.value = NetworkProgress(plan, 254, hits = hits, finished = true)
        seconds.value = 6

        // The app's window: its own title bar, the rail and the page header as Main.kt draws them, around one page.
        fun appWindow(width: Int, height: Int, page: String, ws: Workspace, reduce: Boolean, shown: @Composable () -> WorkspaceState, content: @Composable () -> Unit) =
            ImageComposeScene(width, height, coroutineContext = context) {
                MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides reduce) {
                    Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) { Column {
                        Row(Modifier.fillMaxWidth().background(Tech.Raised).height(32.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Image(AppIcon.small, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                                Text("RingMASTER", color = Tech.Amber)
                            }
                            WindowControl(WindowGlyph.Minimize) {}; WindowControl(WindowGlyph.Maximize) {}; WindowControl(WindowGlyph.Close) {}
                        }
                        val now = shown()
                        Row(Modifier.weight(1f)) {
                            NavigationRail(now, page, "Windows 11", ws, initialExpanded = true, pins = pins, connectionKey = "demo") {}
                            Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
                                Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    val selected = now.selected
                                    Text("WORKSPACE / ${page.uppercase()}${if (page == "Fleet" && selected != null) " / ${selected.kind} ${selected.id}" else ""}",
                                        fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.5.sp, color = Tech.Muted)
                                    Text(when (page) { "Fleet" -> selected?.name ?: "Fleet overview"; "Activity" -> "Recent activity"; "Scan" -> "Network scan"; "Tools" -> "Network tools"; "Under the Hood" -> "Under the Hood"; else -> "Connection desk" },
                                        Modifier.weight(1f), fontSize = Tech.Title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                HorizontalDivider(color = Tech.Edge)
                                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                            }
                        }
                    } }
                } }
            }

        var clock = 0L
        suspend fun frames(scene: ImageComposeScene, count: Int, stepMs: Long = 200, pauseMs: Long = 40) = repeat(count) {
            clock += stepMs * 1_000_000L
            scene.render(clock).close(); yield(); delay(pauseMs)
        }
        suspend fun save(scene: ImageComposeScene, name: String) {
            clock += 16_000_000L
            val image = scene.render(clock)
            try { File(dir, "$name.png").writeBytes(image.encodeToData()!!.bytes) } finally { image.close() }
            yield()
        }
        suspend fun still(name: String, width: Int, height: Int, page: String, shown: WorkspaceState, ws: Workspace = workspace, content: @Composable () -> Unit) {
            val scene = appWindow(width, height, page, ws, reduce = true, { shown }, content)
            try { frames(scene, 12); save(scene, name) } finally { scene.close() }
        }

        Appearance.preview(false, false)
        try {
            still("fleet", 1600, 1000, "Fleet", fleetState) { InventoryScreen(fleetState, workspace, null, "", {}, "All", {}, {}, {}, pins, "demo") }
            still("guest-summary", 1600, 1000, "Fleet", state) { GuestDetailPage(state, workspace, {}, { _, _ -> }, initialTab = "Summary", pins = pins, connectionKey = "demo") }
            still("activity", 1600, 800, "Activity", state) { ActivityScreen(state, workspace, null, {}, {}) }

            // The chart lens, opened on one guest's live CPU the way a click on its sparkline opens it, with the pointer resting on the chart.
            run {
                val lensed = guests[2]
                val scene = appWindow(1600, 1000, "Fleet", workspace, reduce = true, { fleetState }) {
                    ChartLensHost(fleetState, workspace) {
                        val lens = LocalChartLens.current
                        LaunchedEffect(Unit) { lens?.open(guestLens(lensed, LiveMetric.Cpu)) }
                        InventoryScreen(fleetState, workspace, null, "", {}, "All", {}, {}, {}, pins, "demo")
                    }
                }
                try {
                    frames(scene, 12)
                    scene.sendPointerEvent(PointerEventType.Enter, Offset(1080f, 560f))
                    scene.sendPointerEvent(PointerEventType.Move, Offset(1090f, 560f))
                    frames(scene, 2)
                    scene.sendPointerEvent(PointerEventType.Move, Offset(1100f, 560f))
                    frames(scene, 6)
                    save(scene, "chart-lens")
                } finally { scene.close() }
            }

            // Settings is an in-window modal over the page; Appearance is its first section.
            still("settings", 1600, 1000, "Fleet", fleetState) {
                InventoryScreen(fleetState, workspace, null, "", {}, "All", {}, {}, {}, pins, "demo")
                SettingsDialog(fleetState, workspace, SettingsSection.Appearance, {}, {}, {})
            }

            // Network tools after a ping of an invented host, as this computer's ping (Windows) prints it.
            run {
                val tools = ToolsSession(scope)
                val ping = tools.plugins.first { it.id == "ping" }
                val host = "192.168.50.21"
                val replies = listOf(3, 2, 2, 4, 3, 2, 7, 3, 2, 3, 5, 2, 9, 4, 3)
                tools.draft(ping).apply { put("host", host); put("count", "${replies.size}") }
                tools.lines += "Pinging $host with 32 bytes of data:"
                replies.forEachIndexed { n, ms ->
                    tools.lines += "Reply from $host: bytes=32 time=${ms}ms TTL=64"
                    tools.samples += ToolChartSample("latency", n + 1.0, ms.toDouble(), "ms")
                }
                val summary = "${replies.size} sent, ${replies.size} received, 0% loss, min ${replies.min()} ms, avg ${"%.1f".format(replies.average())} ms, max ${replies.max()} ms"
                tools.lines += listOf("", "Ping statistics for $host:", "    Packets: Sent = ${replies.size}, Received = ${replies.size}, Lost = 0 (0% loss),",
                    "Approximate round trip times in milli-seconds:", "    Minimum = ${replies.min()}ms, Maximum = ${replies.max()}ms, Average = ${replies.average().toInt()}ms", summary)
                tools.status = summary
                still("network-tools", 1600, 1000, "Tools", state) { ToolsScreen(tools) }
            }

            // The connection desk with a saved connection selected. Its vault is an invented one, so the
            // Remember on this computer row shows without the real credential store being asked anything.
            run {
                val deskScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
                val deskWorkspace = Workspace(deskScope)
                val deskDir = Files.createDirectories(temp.resolve("desk"))
                val store = ProfileStore(deskDir.resolve("profiles.json"))
                store.save(listOf(
                    ConnectionProfile("00000000-0000-4000-8000-000000000021", "Home lab", DIRECT_PVE_KIND, "https://192.168.50.21:8006", "root@pam!ringmaster", fingerprints[0].replace(":", "").lowercase()),
                    ConnectionProfile("00000000-0000-4000-8000-000000000040", "Datacenter Manager", PDM_KIND, "https://192.168.50.40:8443", "admin@pam!ringmaster", fingerprints[3].replace(":", "").lowercase()),
                    ConnectionProfile("00000000-0000-4000-8000-000000000023", "Test cluster", DIRECT_PVE_KIND, "https://pve-lab.example.test:8006", "monitor@pve!readonly", null)))
                val desk = ProfileDesk(deskWorkspace, store, vault = ReadmeVault("Windows Credential Manager"),
                    rememberedProfiles = RememberedProfiles(deskDir.resolve("remembered.json")), io = Dispatchers.Unconfined)
                desk.selectProfile("00000000-0000-4000-8000-000000000021")
                val motion = MotionPreferences(deskDir.resolve("motion.txt"))
                val scene = appWindow(1600, 1000, "Desk", deskWorkspace, reduce = true, { deskWorkspace.state.collectAsState().value }) {
                    val shownState by deskWorkspace.state.collectAsState()
                    val deskState by desk.state.collectAsState()
                    DeskScreen(shownState, deskState, desk, motion = motion) {}
                }
                try { frames(scene, 12); save(scene, "sign-in") } finally {
                    scene.close(); desk.close(); deskWorkspace.close(); deskScope.cancel()
                }
            }

            // The console page: the guest list beside a console. build-runner was opened first and left, so it is kept
            // open (the green dot) while debian-workbench is on screen. Both pictures are invented text consoles, handed
            // to the page as ready console sessions; every gateway is a stand-in, so nothing is asked of a network.
            run {
                val origin = "https://192.168.50.21:8006"
                val consoleGuests = guests.map { it.copy(remote = origin) }
                val utc = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC)
                val pictures = mapOf(
                    120 to listOf(
                        plainLine("Debian GNU/Linux 13 debian-workbench tty1"), plainLine(),
                        ConsoleLine("debian-workbench login: " to consoleGrey, "demo" to consoleWhite), plainLine("Password:"),
                        plainLine("Linux debian-workbench 6.12.57+deb13-amd64 #1 SMP PREEMPT_DYNAMIC Debian 6.12.57-1 x86_64"), plainLine(),
                        plainLine("The programs included with the Debian GNU/Linux system are free software;"),
                        plainLine("the exact distribution terms for each program are described in the"),
                        plainLine("individual files in /usr/share/doc/*/copyright."), plainLine(),
                        promptLine("demo", "debian-workbench", "uptime"),
                        plainLine(" ${utc.format(now)} up 3 days,  4:17,  1 user,  load average: 0.21, 0.18, 0.12"),
                        promptLine("demo", "debian-workbench", "free -h"),
                        plainLine("               total        used        free      shared  buff/cache   available"),
                        plainLine("Mem:           7.8Gi       2.6Gi       3.1Gi        41Mi       2.3Gi       5.2Gi"),
                        plainLine("Swap:          975Mi          0B       975Mi"),
                        promptLine("demo", "debian-workbench", "df -h /"),
                        plainLine("Filesystem      Size  Used Avail Use% Mounted on"),
                        plainLine("/dev/sda1        31G  6.2G   23G  22% /"),
                        promptLine("demo", "debian-workbench", "systemctl is-system-running"),
                        plainLine("running"),
                        promptLine("demo", "debian-workbench", "")),
                    122 to listOf(
                        plainLine("Debian GNU/Linux 13 build-runner tty1"), plainLine(),
                        promptLine("ci", "build-runner", "journalctl -u runner -n 4 --no-pager"),
                        plainLine("runner[812]: job 4127 started: build and test"),
                        plainLine("runner[812]: job 4127 finished: success"),
                        plainLine("runner[812]: job 4128 started: package"),
                        plainLine("runner[812]: waiting for the next job"),
                        promptLine("ci", "build-runner", "")),
                )
                val opened = mutableListOf<Int>()
                val consoleScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
                val consoleWorkspace = Workspace(consoleScope,
                    gateway = object : InventoryGateway {
                        override suspend fun overview(connection: Connection) = Overview(3, consoleGuests)
                        override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
                    },
                    taskGateway = object : TaskGateway { override suspend fun tasks(connection: Connection) = emptyList<ClusterTask>() },
                    nodeStatusGateway = object : NodeStatusGateway {
                        override suspend fun status(connection: Connection, origin: String, node: String, scope: String): NodeLiveStatus = error("Not part of the README scene.")
                    },
                    inspectionGateway = object : GuestInspectionGateway {
                        override suspend fun read(connection: Connection, guest: Guest, storages: List<String>, timeframe: String) = demoInspection(guest, timeframe)
                    },
                    consoleStillGateway = object : ConsoleStillGateway {
                        override suspend fun capture(connection: Connection, guest: Guest): ConsoleStill = error("Not part of the README scene.")
                    },
                    vncGateway = object : VncConsoleGateway {
                        override suspend fun open(connection: Connection, guest: Guest): VncSession {
                            opened += guest.id
                            return pictureSession(1024, 640, drawConsole(1024, 640, pictures[guest.id] ?: listOf(promptLine("demo", guest.name, ""))))
                        }
                    })
                val keep = ConsoleKeepPreference.minutes
                ConsoleKeepPreference.preview(10)
                val apiBefore = Trace.snapshot().count { it.source == TraceSource.PveApi || it.source == TraceSource.PdmApi }
                try {
                    consoleWorkspace.connect(Connection(origin, "demo@pve!readme", "invented-readme-secret", ""))
                    consoleWorkspace.setLive(false)
                    check(consoleWorkspace.state.value.overview?.guests?.size == consoleGuests.size) { "the invented inventory did not load" }
                    consoleWorkspace.select(consoleGuests[2])
                    val scene = appWindow(1600, 1000, "Fleet", consoleWorkspace, reduce = true, { consoleWorkspace.state.collectAsState().value }) {
                        val shownState by consoleWorkspace.state.collectAsState()
                        GuestDetailPage(shownState, consoleWorkspace, {}, { _, _ -> }, initialTab = "Console", onSwitchGuest = { consoleWorkspace.select(it) })
                    }
                    try {
                        frames(scene, 12, stepMs = 50)
                        check(122 in opened) { "build-runner's console did not open" }
                        consoleWorkspace.select(consoleGuests[0])
                        frames(scene, 30, stepMs = 50)
                        check(opened == listOf(122, 120)) { "unexpected console opens $opened" }
                        check(consoleGuests[2].resource in consoleWorkspace.state.value.keptConsoles) { "build-runner was not kept open" }
                        save(scene, "console")
                    } finally { scene.close() }
                } finally {
                    ConsoleKeepPreference.preview(keep)
                    consoleWorkspace.close(); consoleScope.cancel()
                }
                val apiAfter = Trace.snapshot().count { it.source == TraceSource.PveApi || it.source == TraceSource.PdmApi }
                check(apiAfter == apiBefore) { "the console scene made ${apiAfter - apiBefore} API calls" }
            }

            // Under the Hood in Demo mode: its own synthetic stream runs while the page is open. A minute of invented
            // requests to example.test addresses goes first, so the latency and source panels have something to show.
            run {
                Trace.clear()
                val calls = listOf("GET /cluster/resources" to "cluster/resources", "GET /cluster/tasks" to "cluster/tasks",
                    "GET /nodes/demo-node-a/status" to "nodes/demo-node-a/status", "GET /nodes/demo-node-b/qemu/125/status/current" to "nodes/demo-node-b/qemu/125/status/current",
                    "GET /nodes/demo-node-a/qemu/120/rrddata" to "nodes/demo-node-a/qemu/120/rrddata", "GET /nodes/demo-node-c/storage" to "nodes/demo-node-c/storage")
                repeat(36) { i ->
                    val (summary, path) = calls[i % calls.size]
                    val pdm = i % 9 == 4
                    val span = Trace.begin(if (pdm) TraceSource.PdmApi else TraceSource.PveApi, if (pdm) "GET /resources/list" else summary,
                        mapOf("demo" to "true", "url" to if (pdm) "https://pdm.example.test:8443/api2/json/resources/list" else "https://pve-east.example.test:8006/api2/json/$path"))
                    delay(listOf(6L, 14L, 9L, 31L, 62L, 18L)[i % 6])
                    span.end(status = 200, bytesIn = listOf(18_432L, 2_210L, 1_604L, 905L, 7_340L, 3_118L)[i % 6])
                    if (i % 4 == 0) Trace.emit(TraceSource.Task, "poll", "Task polling · running", mapOf("demo" to "true"))
                    if (i % 6 == 0) Trace.emit(TraceSource.LiveLoop, "tick", "Refresh tick · sample data", mapOf("demo" to "true"))
                }
                val scene = appWindow(1600, 1000, "Under the Hood", workspace, reduce = true, { state }) { UnderTheHoodScreen(state) }
                try { frames(scene, 30, stepMs = 100, pauseMs = 100); save(scene, "under-the-hood") } finally { scene.close() }
            }

            // The clip: one /24 sweep with full motion. Each service lights up when the sweep reaches its address, then the result holds.
            val clip = File(dir, "scan-clip").apply { deleteRecursively(); mkdirs() }
            val ordered = hits.sortedBy { it.ip.substringAfterLast('.').toInt() }
            val fps = 15; val sweep = 75; val hold = 30
            progress.value = NetworkProgress(plan, 0, hits = emptyList(), finished = false); running.value = true; seconds.value = 0
            val scene = appWindow(1600, 1000, "Scan", workspace, reduce = false, { state }) { ScanScreen(session, emptyList()) {} }
            try {
                for (frame in 0 until sweep + hold) {
                    if (frame < sweep) {
                        val scanned = minOf(254, (frame + 1) * 254 / sweep)
                        progress.value = NetworkProgress(plan, scanned, "192.168.50.$scanned",
                            ordered.filter { it.ip.substringAfterLast('.').toInt() <= scanned }, finished = scanned == 254)
                        if (scanned == 254) { seconds.value = 6; running.value = false }
                    }
                    val image = scene.render(frame * 1_000_000_000L / fps)
                    try { File(clip, "%03d.png".format(frame)).writeBytes(image.encodeToData()!!.bytes) } finally { image.close() }
                    yield(); delay(5)
                }
            } finally { scene.close() }
        } finally {
            Appearance.preview(false)
            workspace.close(); scope.cancel()
        }
    }
}
