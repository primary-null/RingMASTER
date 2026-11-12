package local.ringleader.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
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
 * Opt-in: renders the frames of the README's animated clips with invented sample data. Set RINGMASTER_README_CLIPS to
 * an output folder. Never connects to a server; every name and address is made up, and times are shown in UTC.
 *
 * Each clip is a folder of numbered PNG frames plus frames.txt, one line per frame: `<file> <milliseconds> <keep>`.
 * keep is 1 for a frame that must stay (a hold) and 0 for an in-between frame that tools/readme/make_gif.py may thin out.
 *  - theme-clip: the fleet holding on Dark, crossfading to Pitch Black, to Light and back to Dark, at 60 frames a second.
 *  - scan-clip: one /24 sweep, as ReadmeShotsTest's clip, at 15 frames a second.
 *  - lens-clip: the pointer runs along the inspector's CPU chart (the hover readout follows it), a click opens the chart
 *    lens, which holds, and a click on the scrim closes it again. 60 frames a second; a fourth column gives the pointer.
 */
class ReadmeClipsTest {
    @Test fun clips() {
        val dir = File(System.getenv("RINGMASTER_README_CLIPS") ?: return).apply { mkdirs() }
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try { runSceneTest { render(dir) } } finally { TimeZone.setDefault(zone) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> stateOf(owner: Any, name: String) =
        owner.javaClass.getDeclaredField("$name\$delegate").apply { isAccessible = true }.get(owner) as MutableState<T>

    /** Frames and their durations, written as the clip goes. */
    private class Clip(val dir: File) {
        private val lines = StringBuilder()
        private var n = 0
        init { dir.deleteRecursively(); dir.mkdirs() }
        /** [pointer]: where the mouse is, so the GIF script can draw a cursor (a scene has none). */
        fun add(scene: ImageComposeScene, time: Long, ms: Double, keep: Boolean, pointer: Offset? = null) {
            val name = "%03d.png".format(n++)
            scene.render(time).use { File(dir, name).writeBytes(it.encodeToData()!!.bytes) }
            lines.append(name).append(' ').append("%.3f".format(java.util.Locale.ROOT, ms)).append(' ').append(if (keep) 1 else 0)
            pointer?.let { lines.append(' ').append(it.x.toInt()).append(',').append(it.y.toInt()) }
            lines.append('\n')
        }
        fun finish() = File(dir, "frames.txt").writeText(lines.toString())
    }

    private suspend fun CoroutineScope.render(dir: File) {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = Workspace(scope)
        workspace.exploreDemo()
        workspace.select(workspace.state.value.overview!!.guests.first())
        workspace.inspectGuest("hour")
        delay(1500)
        val base = workspace.state.value
        val now = Instant.now()
        val gib = 1073741824L

        // The same invented fleet as ReadmeShotsTest, so the clips and the stills agree.
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

        data class Job(val type: String, val id: String, val seconds: Long?, val ago: Long, val failed: Boolean = false)
        val jobs = listOf(
            Job("vzdump", "121", null, 95), Job("qmstart", "122", 4, 1_310), Job("qmsnapshot", "120", 9, 2_580),
            Job("vzstart", "126", 2, 3_400), Job("qmshutdown", "128", 27, 4_720), Job("vzdump", "125", 48, 5_960, failed = true),
            Job("qmrollback", "120", 14, 7_150), Job("aptupdate", "", 11, 8_830), Job("vzdump", "123", 196, 9_640),
            Job("qmstart", "127", 5, 11_020), Job("qmreboot", "124", 33, 12_510), Job("vzdump", "120", 241, 14_400),
        )
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
        val tasks = jobs.mapIndexed { n, job ->
            val outcome = when { job.seconds == null -> TaskOutcome.Running; job.failed -> TaskOutcome.Unsuccessful; else -> TaskOutcome.Succeeded }
            val start = now.minusSeconds(job.ago)
            ClusterTask(DEMO_REMOTE_KEY, "demo:readme$n", nodeOf(job.id) ?: "demo-node-b", job.type, job.id, "demo@ringmaster", start,
                job.seconds?.let { start.plusSeconds(it) },
                when (outcome) { TaskOutcome.Running -> "running"; TaskOutcome.Succeeded -> "OK"; else -> "Backup storage unavailable" }, outcome)
        }
        val state = base.copy(selected = guests.first(), history = FleetHistory(perGuest, perNode, series(40, .19, 30.4, 96)),
            overview = base.overview!!.copy(nodes = 3, guests = guests, members = members),
            inspection = base.inspection!!.copy(resource = guests.first().resource, node = guests.first().node), tasks = tasks)
        // As in the fleet still: a container is inspected, so its inspector row fits the 1600 px window.
        val fleetState = state.copy(selected = guests[6])

        val temp = Files.createTempDirectory("ringmaster-readme-clips")
        val pins = PinStore(temp.resolve("pins.json"))
        pins.toggleGuest("demo", guests[0]); pins.toggleView("demo", guests[0], "Console")
        pins.addService("demo", guests[1], "DNS admin", "http://192.0.2.53:3000")
        pins.toggleGuest("demo", guests[5]); pins.toggleView("demo", guests[2], "Snapshots"); pins.toggleGuest("demo", guests[4])

        val fingerprints = listOf("3A:7F:0C:91", "B2:44:E8:15", "6D:A0:5B:F3", "C9:12:7E:60", "0F:D6:38:AB").map { head -> (head + ":" + "5E:C1:".repeat(14)).take(95) }
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

        val context = coroutineContext
        // "synced Ns ago" and task ages read this clock, so the text stays put while the frames are written and the loop closes.
        val shownNow = Instant.now()
        // The app's window, as in ReadmeShotsTest, with full motion. [crossfade] wraps it the way Main.kt does, so a theme change fades.
        fun appWindow(width: Int, height: Int, shown: WorkspaceState, page: String, title: String, crossfade: Boolean, content: @Composable () -> Unit) =
            ImageComposeScene(width, height, Density(1f), coroutineContext = context) {
                MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides false, LocalNow provides shownNow) {
                    val window: @Composable () -> Unit = {
                        Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) { Column {
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
                    }
                    if (crossfade) ThemeCrossfade(Modifier.fillMaxSize()) { window() } else window()
                } }
            }

        val frame = 1_000_000_000L / 60
        Appearance.preview(ThemeMode.Dark)
        try {
            // The theme change: one frame per hold, every 60 Hz frame of each fade.
            val clip = Clip(File(dir, "theme-clip"))
            val scene = appWindow(1600, 1000, fleetState, "Fleet", "Fleet overview", crossfade = true) {
                InventoryScreen(fleetState, workspace, null, "", {}, "All", {}, {}, {}, pins, "demo")
            }
            try {
                var time = 0L
                suspend fun step() { time += frame; scene.render(time).close(); yield() }
                repeat(90) { step() }  // entrances and charts settle
                val holdMs = 1600.0
                for (next in listOf(ThemeMode.PitchBlack, ThemeMode.Light, ThemeMode.Dark)) {
                    time += frame; clip.add(scene, time, holdMs, keep = true); yield()
                    Appearance.preview(next, ease = true)
                    // Two frames for every surface to take its picture, the fade itself, and a few frames to land.
                    val frames = (paletteFadeMillis(MotionTier.Full) + 120) * 60 / 1000
                    repeat(frames) { time += frame; clip.add(scene, time, 1000.0 / 60, keep = false); yield() }
                    repeat(30) { step() }
                    check(Appearance.palette == Appearance.targetPalette) { "the fade to $next did not land" }
                }
                // Back on Dark: the loop continues with the first frame, which is the same picture.
                clip.finish()
            } finally { scene.close() }

            // The scan: one /24 sweep with full motion. Each service lights up when the sweep reaches its address, then the result holds.
            Appearance.preview(ThemeMode.Dark)
            val scan = Clip(File(dir, "scan-clip"))
            val ordered = hits.sortedBy { it.ip.substringAfterLast('.').toInt() }
            val fps = 15; val sweep = 75; val hold = 30
            progress.value = NetworkProgress(plan, 0, hits = emptyList(), finished = false); running.value = true; seconds.value = 0
            val scanScene = appWindow(1600, 1100, state, "Scan", "Network scan", crossfade = false) { ScanScreen(session, emptyList()) {} }
            try {
                var time = 0L
                for (n in 0 until sweep + hold) {
                    if (n < sweep) {
                        val scanned = minOf(254, (n + 1) * 254 / sweep)
                        progress.value = NetworkProgress(plan, scanned, "192.168.50.$scanned",
                            ordered.filter { it.ip.substringAfterLast('.').toInt() <= scanned }, finished = scanned == 254)
                        // The elapsed time counts up with the sweep (5 s of clip stand for a 6 s scan).
                        seconds.value = 6L * (n + 1) / sweep
                        if (scanned == 254) { seconds.value = 6; running.value = false }
                    }
                    // Four 60 Hz frames per clip frame, so animations advance as they would on screen.
                    repeat(3) { time += frame; scanScene.render(time).close(); yield() }
                    time += frame
                    scan.add(scanScene, time, 1000.0 / fps, keep = n == sweep + hold - 1)
                    yield()
                }
                scan.finish()
            } finally { scanScene.close() }

            // Chart hover, then the lens: the fleet page inside a lens host, as Main.kt composes it.
            val lensClip = Clip(File(dir, "lens-clip"))
            val lensScene = appWindow(1600, 1000, fleetState, "Fleet", "Fleet overview", crossfade = false) {
                ChartLensHost(fleetState, workspace) { InventoryScreen(fleetState, workspace, null, "", {}, "All", {}, {}, {}, pins, "demo") }
            }
            try {
                var time = 0L
                val rest = Offset(1180f, 820f)
                var pointer = rest
                suspend fun step() { time += frame; lensScene.render(time).close(); yield() }
                suspend fun shot(ms: Double = 1000.0 / 60, keep: Boolean = false) { time += frame; lensClip.add(lensScene, time, ms, keep, pointer); yield() }
                fun move(to: Offset) { pointer = to; lensScene.sendPointerEvent(PointerEventType.Move, to) }
                fun click() { lensScene.sendPointerEvent(PointerEventType.Press, pointer); lensScene.sendPointerEvent(PointerEventType.Release, pointer) }
                fun ease(t: Float) = t * t * (3 - 2 * t)
                move(rest)
                repeat(90) { step() }
                shot(1200.0, keep = true)
                // Glide onto the inspector's CPU chart, then along it.
                val start = Offset(1275f, 575f); val end = Offset(1545f, 575f)
                for (n in 1..24) { move(rest + (start - rest) * ease(n / 24f)); shot() }
                for (n in 1..90) { move(start + (end - start) * (n / 90f)); shot() }
                repeat(20) { shot() }
                click()
                repeat(36) { shot() }
                shot(2400.0, keep = true)
                // Close: a click on the scrim, top left of the page.
                val scrim = Offset(330f, 140f)
                for (n in 1..24) { move(end + (scrim - end) * ease(n / 24f)); shot() }
                click()
                repeat(30) { shot() }
                // Back to where the clip began.
                for (n in 1..24) { move(scrim + (rest - scrim) * ease(n / 24f)); shot() }
                lensClip.finish()
            } finally { lensScene.close() }
        } finally {
            Appearance.preview(false)
            workspace.close(); scope.cancel()
        }
    }
}
