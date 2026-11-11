package local.ringleader.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import local.ringleader.core.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.TimeZone
import kotlin.math.sin

/**
 * Opt-in: renders the README pictures from the app's own screens with invented sample data.
 * Set RINGMASTER_README_SHOTS to an output folder. Never connects to a server; every name and address is made up.
 * Writes fleet, snapshots, activity and scan PNGs, plus scan-clip/NNN.png frames (15 per second) for the animated scan.
 */
class ReadmeShotsTest {
    @Test fun shots() {
        val dir = File(System.getenv("RINGMASTER_README_SHOTS") ?: return).apply { mkdirs() }
        // Times are shown in UTC, so the pictures do not tell where or when in the day they were made.
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try { render(dir) } finally { TimeZone.setDefault(zone) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> stateOf(owner: Any, name: String) =
        owner.javaClass.getDeclaredField("$name\$delegate").apply { isAccessible = true }.get(owner) as MutableState<T>

    private fun render(dir: File) {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = Workspace(scope)
        workspace.exploreDemo()
        workspace.select(workspace.state.value.overview!!.guests.first())
        workspace.inspectGuest("hour")
        runBlocking { delay(1500) }
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
        val inspection = base.inspection!!.copy(
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
        val state = base.copy(selected = guests.first(), history = FleetHistory(perGuest, perNode, series(40, .19, 30.4, 96)),
            overview = base.overview!!.copy(nodes = 3, guests = guests, members = members),
            inspection = inspection.copy(resource = guests.first().resource, node = guests.first().node), tasks = tasks)
        // The fleet picture inspects a container: its inspector row (no Console button) fits the 1600 px window.
        val fleetState = state.copy(selected = guests[6])

        val temp = Files.createTempDirectory("ringmaster-readme")
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

        // The app's window: its own title bar, the rail and a page header, around one page.
        fun appWindow(width: Int, height: Int, shown: WorkspaceState, page: String, title: String, reduce: Boolean, content: @Composable () -> Unit) =
            ImageComposeScene(width, height) {
                MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides reduce) {
                    Surface(Modifier.fillMaxSize()) { Column {
                        Row(Modifier.fillMaxWidth().background(Tech.Raised).height(32.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Image(AppIcon.small, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text("RingMASTER", Modifier.weight(1f), color = Tech.Amber)
                            WindowControl("—") {}; WindowControl("□") {}; WindowControl("×") {}
                        }
                        Row(Modifier.weight(1f)) {
                            NavigationRail(shown, page, "Windows 11", workspace, initialExpanded = true, pins = pins, connectionKey = "demo") {}
                            Column(Modifier.weight(1f).padding(16.dp)) {
                                Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("WORKSPACE / ${page.uppercase()}", fontSize = Tech.Label, color = Tech.Muted); Text(title, fontSize = Tech.Title)
                                }
                                HorizontalDivider(color = Tech.Edge, modifier = Modifier.padding(vertical = Tech.Space12))
                                Box(Modifier.weight(1f)) { content() }
                            }
                        }
                    } }
                } }
            }

        data class Still(val name: String, val width: Int, val height: Int, val shown: WorkspaceState, val page: String, val title: String, val content: @Composable () -> Unit)
        val stills = listOf(
            Still("fleet", 1600, 1000, fleetState, "Fleet", "Fleet overview") { InventoryScreen(fleetState, workspace, null, "", {}, "All", {}, {}, {}, pins, "demo") },
            Still("snapshots", 1600, 640, state, "Fleet", guests.first().name) { GuestDetailPage(state, workspace, {}, { _, _ -> }, initialTab = "Snapshots", pins = pins, connectionKey = "demo") },
            Still("activity", 1600, 800, state, "Activity", "Recent activity") { ActivityScreen(state, workspace, null, {}, {}) },
            Still("scan", 1600, 1090, state, "Scan", "Network scan") { ScanScreen(session, emptyList()) {} },
        )
        Appearance.preview(false, false)
        try {
            for (still in stills) {
                val scene = appWindow(still.width, still.height, still.shown, still.page, still.title, reduce = true, still.content)
                try {
                    var t = 0L; repeat(10) { scene.render(t); t += 200_000_000L; Thread.sleep(80) }
                    File(dir, "${still.name}.png").writeBytes(scene.render(t).encodeToData()!!.bytes)
                } finally { scene.close() }
            }

            // The clip: one /24 sweep with full motion. Each service lights up when the sweep reaches its address, then the result holds.
            val clip = File(dir, "scan-clip").apply { deleteRecursively(); mkdirs() }
            val ordered = hits.sortedBy { it.ip.substringAfterLast('.').toInt() }
            val fps = 15; val sweep = 75; val hold = 30
            progress.value = NetworkProgress(plan, 0, hits = emptyList(), finished = false); running.value = true; seconds.value = 0
            val scene = appWindow(1600, 1000, state, "Scan", "Network scan", reduce = false) { ScanScreen(session, emptyList()) {} }
            try {
                for (frame in 0 until sweep + hold) {
                    if (frame < sweep) {
                        val scanned = minOf(254, (frame + 1) * 254 / sweep)
                        progress.value = NetworkProgress(plan, scanned, "192.168.50.$scanned",
                            ordered.filter { it.ip.substringAfterLast('.').toInt() <= scanned }, finished = scanned == 254)
                        if (scanned == 254) { seconds.value = 6; running.value = false }
                    }
                    val image = scene.render(frame * 1_000_000_000L / fps)
                    File(clip, "%03d.png".format(frame)).writeBytes(image.encodeToData()!!.bytes)
                    Thread.sleep(5)
                }
            } finally { scene.close() }
        } finally {
            Appearance.preview(false)
            workspace.close(); scope.cancel()
        }
    }
}
