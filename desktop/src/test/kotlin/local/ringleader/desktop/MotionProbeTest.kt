package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Color as AwtColor
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import local.ringleader.core.*

/**
 * Opt-in probe: renders every signature moment at several timestamps and writes one PNG strip per moment and tier.
 *   RINGMASTER_MOTION_PROBE=1 RINGMASTER_PROBE_DIR=<dir> gradlew :desktop:test --tests "*MotionProbeTest*"
 * Frames are rendered with ImageComposeScene.render(nanoTime), so the animation clock is the one the app uses, sampled
 * exactly. Labels show the time since the triggering state change. The scenes are the real screens (InventoryScreen,
 * ActivityScreen, ScanScreen) fed with state changes; the spark's launch stands in for the Send click.
 */
class MotionProbeTest {
    private val density = 2f
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val workspace by lazy { Workspace(scope) }
    private val vm120 = ResourceId(DEMO_REMOTE_KEY, "", "VM", 120)

    private fun guests(status120: String) = listOf(
        Guest(DEMO_REMOTE_KEY, 120, "debian-workbench", "demo-node-a", "VM", status120, metrics = if (status120 == "running") GuestListMetrics(cpuFraction = .12, memoryBytes = 1L shl 30, memoryLimitBytes = 4L shl 30, uptimeSeconds = 7200) else GuestListMetrics(memoryLimitBytes = 4L shl 30)),
        Guest(DEMO_REMOTE_KEY, 121, "web-services", "demo-node-a", "Container", "running", metrics = GuestListMetrics(cpuFraction = .31, memoryBytes = 1L shl 29, memoryLimitBytes = 2L shl 30, uptimeSeconds = 90000)),
        Guest(DEMO_REMOTE_KEY, 130, "backup-lab", "demo-node-b", "VM", "stopped"),
        Guest(DEMO_REMOTE_KEY, 140, "metrics", "demo-node-b", "VM", "running", metrics = GuestListMetrics(cpuFraction = .05, memoryBytes = 1L shl 29, memoryLimitBytes = 2L shl 30, uptimeSeconds = 600)),
    )

    private fun still(): ConsoleStill {
        val img = BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.paint = java.awt.GradientPaint(0f, 0f, AwtColor(20, 40, 70), 320f, 200f, AwtColor(5, 90, 60)); g.fillRect(0, 0, 320, 200)
        g.color = AwtColor(230, 230, 230); g.font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        listOf("debian login: root", "Password:", "Last login: Tue Nov 10", "root@debian-workbench:~# _").forEachIndexed { i, l -> g.drawString(l, 12, 30 + i * 18) }
        g.dispose()
        val out = ByteArrayOutputStream(); ImageIO.write(img, "png", out)
        return ConsoleStill(320, 200, out.toByteArray(), Instant.now(), false)
    }

    private fun sub(id: String, resource: ResourceId, action: PowerAction, phase: PowerPhase, detail: String? = null) = PowerSubmission(
        "local-$id", resource.remote, if (resource.vmid == 120 || resource.vmid == 121) "demo-node-a" else "demo-node-b", resource.vmid, resource.kind,
        guests("running").first { it.id == resource.vmid }.name, action, phase,
        detail ?: when (phase) { PowerPhase.Succeeded -> "Stopped with exit status OK."; PowerPhase.Accepted -> "The server accepted this action. It is not finished."; PowerPhase.Simulated -> SIMULATED_POWER_DETAIL; else -> "command failed: exit code 1" },
        phase == PowerPhase.Simulated, phase == PowerPhase.Accepted,
    )

    private fun base(status120: String, submissions: List<PowerSubmission> = emptyList(), tasks: List<ClusterTask>? = null, selected: Boolean = true, stills: Boolean = false): WorkspaceState {
        val all = guests(status120)
        return WorkspaceState(
            connected = true, demo = true, connectionEpoch = 1, updatedAt = Instant.now(),
            overview = demoOverview.copy(guests = all, nodes = 2), selected = if (selected) all.first() else null,
            submissions = submissions, tasks = tasks,
            consoleStills = if (stills) mapOf(all.first().resource to still()) else emptyMap(),
        )
    }

    /** A scene with the motion system around it, driven by a state the caller changes between frames. */
    private inner class Rig(val tier: MotionTier, w: Int, h: Int, content: @Composable () -> Unit) : AutoCloseable {
        var nanos = 0L
        /** The wait clock the motion book reads: moves with the rig, so "waited 12 s" is exact. */
        val waitClock = { nanos }
        val anchors = arrayOfNulls<MotionAnchors>(1)
        val sparks = arrayOfNulls<SparkLauncher>(1)
        val scene = ImageComposeScene((w * density).toInt(), (h * density).toInt(), Density(density)) {
            CompositionLocalProvider(LocalReduceMotion provides (tier == MotionTier.Reduced)) {
                ProvideMotionSystem(tierOverride = tier) {
                    anchors[0] = LocalMotionAnchors.current; sparks[0] = LocalSparks.current
                    CompositionLocalProvider(LocalMotionClock provides waitClock) {
                        ProvideDeskClock(animations = tier != MotionTier.Reduced, tickSeconds = false) {
                            MaterialTheme(ringColors) {
                                Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = Tech.Ink) { Box(Modifier.fillMaxSize().background(Tech.Canvas)) { content() } }
                            }
                        }
                    }
                }
            }
        }
        fun advance(ms: Long): BufferedImage { nanos += ms * 1_000_000L; return scene.render(nanos).use { it.toComposeImageBitmap().toAwtImage() } }
        fun settle(frames: Int = 3, stepMs: Long = 16) { repeat(frames) { advance(stepMs) } }
        /** Window-pixel bounds of an anchored place. */
        fun bounds(key: Any): java.awt.Rectangle? = anchors[0]?.bounds(key)?.let { java.awt.Rectangle(it.left.toInt(), it.top.toInt(), it.width.toInt(), it.height.toInt()) }
        override fun close() = scene.close()
    }

    private fun px(dp: Float) = (dp * density).toInt()
    private fun rect(x: Int, y: Int, w: Int, h: Int) = java.awt.Rectangle(x, y, w, h)

    /** Samples the rig at [times] (ms after the call), cropping each frame to [crop] (pixels), and writes the labelled strip. */
    private fun strip(file: File, rig: Rig, times: List<Int>, crop: java.awt.Rectangle, title: String, zoom: Float = 1f, cols: Int = 3, before: (Int) -> Unit = {}) {
        val start = rig.nanos
        val frames = times.map { t ->
            before(t)
            val image = rig.advance((start + t * 1_000_000L - rig.nanos) / 1_000_000L)
            val r = crop.intersection(java.awt.Rectangle(0, 0, image.width, image.height))
            val copy = BufferedImage(r.width.coerceAtLeast(1), r.height.coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
            copy.createGraphics().apply { drawImage(image, 0, 0, copy.width, copy.height, r.x, r.y, r.x + copy.width, r.y + copy.height, null); dispose() }
            t to copy
        }
        write(file, frames, title, zoom, cols)
    }

    private fun write(file: File, frames: List<Pair<Int, BufferedImage>>, title: String, zoom: Float, cols: Int) {
        val total = 2000
        val colWidth = (total - 8 * (cols + 1)) / cols
        val widest = frames.maxOf { it.second.width }
        val scale = minOf(zoom, colWidth.toFloat() / widest).coerceAtLeast(0.1f)
        val cw = (widest * scale).toInt(); val ch = (frames.maxOf { it.second.height } * scale).toInt()
        val rows = (frames.size + cols - 1) / cols
        val out = BufferedImage(cols * (cw + 8) + 8, 28 + rows * (ch + 26), BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.color = AwtColor(14, 16, 20); g.fillRect(0, 0, out.width, out.height)
        g.font = Font(Font.MONOSPACED, Font.BOLD, 13); g.color = AwtColor(255, 190, 80); g.drawString(title, 8, 18)
        frames.forEachIndexed { i, (t, img) ->
            val x = 8 + (i % cols) * (cw + 8); val y = 28 + (i / cols) * (ch + 26)
            g.color = AwtColor(160, 170, 180); g.font = Font(Font.MONOSPACED, Font.PLAIN, 12); g.drawString("+${t}ms", x, y + 11)
            val w = (img.width * scale).toInt(); val h = (img.height * scale).toInt()
            g.drawImage(img, x, y + 15, w, h, null)
            g.color = AwtColor(60, 66, 74); g.drawRect(x - 1, y + 14, w + 1, h + 1)
        }
        g.dispose()
        file.parentFile.mkdirs(); ImageIO.write(out, "png", file)
    }

    private fun fleet(state: MutableState<WorkspaceState>): @Composable () -> Unit = {
        val s by state
        InventoryScreen(s, workspace, null, "", {}, "All", {}, {}, {})
    }

    @Test fun strips() {
        if (System.getenv("RINGMASTER_MOTION_PROBE") != "1") return
        val dir = File(System.getenv("RINGMASTER_PROBE_DIR") ?: "../artifacts/motion").apply { mkdirs() }
        javax.swing.SwingUtilities.invokeAndWait {
            MotionTier.entries.forEach { tier ->
                val t = tier.name.lowercase()
                fun scene(name: String, block: () -> Unit) = runCatching(block).onFailure { println("$name $t failed: $it"); it.printStackTrace() }
                scene("spark") { spark(dir, tier, t, simulated = false) }
                scene("spark-demo") { spark(dir, tier, t, simulated = true) }
                scene("breathing") { breathing(dir, tier, t) }
                scene("success") { success(dir, tier, t) }
                scene("failure") { failure(dir, tier, t) }
                scene("collapse") { collapse(dir, tier, t, hard = false) }
                scene("hard collapse") { collapse(dir, tier, t, hard = true) }
                scene("brackets") { brackets(dir, tier, t) }
                scene("power-on") { powerOn(dir, tier, t) }
                scene("radar") { radar(dir, tier, t) }
                scene("progress") { progress(dir, tier, t) }
            }
        }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    // ---- a. spark: Send button to the guest's status dot -------------------------------------------------------------------

    private fun spark(dir: File, tier: MotionTier, t: String, simulated: Boolean) {
        val state = mutableStateOf(base("stopped"))
        Rig(tier, 1280, 520, fleet(state)).use { rig ->
            rig.settle()
            val dot = rig.bounds(RowAnchor(vm120)) ?: error("the selected row registered no anchor")
            // The confirmation dialog's Send button sits left of the list's status column, about where it centres on screen.
            val button = Rect(Offset(dot.x - px(330f).toFloat(), dot.y + px(34f).toFloat()), androidx.compose.ui.geometry.Size(px(120f).toFloat(), px(32f).toFloat()))
            rig.sparks[0]!!.ignite(RmMotion(tier), button, listOf(RowAnchor(vm120), TagAnchor(vm120)), hot = false) {}
            var posted = false
            val phase = if (simulated) PowerPhase.Simulated else PowerPhase.Accepted
            val left = (button.left - px(40f)).toInt()
            val crop = rect(left, dot.y - px(70f), (dot.x + px(150f)) - left, px(150f))
            strip(File(dir, "spark${if (simulated) "-demo" else ""}-$t.png"), rig, listOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 320, 400, 520, 700, 1000), crop,
                if (simulated) "SPARK, demo mode ($t): Simulate -> the dot pops once, nothing breathes (a simulated action never runs). Submission lands at +64ms."
                else "SPARK ($t): Send -> the spark leaves the button for the guest's status dot, the dot pops, then breathes (Accepted, not finished). Submission lands at +64ms.", 1f, 2) { ms ->
                if (!posted && ms >= 64) { posted = true; state.value = base("stopped", submissions = listOf(sub("1", vm120, PowerAction.Start, phase))) }
            }
        }
    }

    // ---- b. breathing pip --------------------------------------------------------------------------------------------------

    private fun breathing(dir: File, tier: MotionTier, t: String) {
        val state = mutableStateOf(base("stopped", submissions = listOf(sub("1", vm120, PowerAction.Start, PowerPhase.Accepted))))
        Rig(tier, 1280, 420, fleet(state)).use { rig ->
            rig.settle(5)
            val dot = rig.bounds(RowAnchor(vm120)) ?: error("no anchor")
            strip(File(dir, "breathing-$t.png"), rig, (0..1300 step 100).toList(), rect(dot.x - px(14f), dot.y - px(14f), px(130f), px(28f)),
                "BREATHING PIP ($t): a guest with an accepted action; one 1.2 s cycle from +0 to +1200. Reduced: still dot and the word WORKING.", 3f, 3)
        }
    }

    // ---- c. success glow ------------------------------------------------------------------------------------------------------

    private fun success(dir: File, tier: MotionTier, t: String) {
        fun scene(file: String, waitedMs: Long, title: String) {
            val state = mutableStateOf(base("stopped", submissions = listOf(sub("1", vm120, PowerAction.Start, PowerPhase.Accepted))))
            Rig(tier, 1280, 420, fleet(state)).use { rig ->
                rig.settle(4)
                rig.nanos += waitedMs * 1_000_000L; rig.advance(16)
                state.value = base("running", submissions = listOf(sub("1", vm120, PowerAction.Start, PowerPhase.Succeeded)))
                rig.advance(16)
                val dot = rig.bounds(RowAnchor(vm120)) ?: error("no anchor")
                strip(File(dir, file), rig, listOf(0, 30, 60, 90, 120, 160, 200, 260, 320, 400, 520, 900, 1100, 1300), rect(px(0f), dot.y - px(18f), dot.x + px(130f), px(36f)), title, 1f, 1)
            }
        }
        scene("success-$t.png", 12_000L, "SUCCESS ($t): Accepted -> Succeeded after a 12 s wait. Row bloom (left-weighted), the status square becomes a tick that draws itself. Names are demo samples.")
        scene("success-routine-$t.png", 200L, "SUCCESS, routine ($t): the same row after a quick action: a fainter bloom.")
    }

    // ---- d. failure ----------------------------------------------------------------------------------------------------------

    private fun failure(dir: File, tier: MotionTier, t: String) {
        val state = mutableStateOf(base("stopped", submissions = listOf(sub("1", vm120, PowerAction.Start, PowerPhase.Accepted))))
        Rig(tier, 1280, 420, fleet(state)).use { rig ->
            rig.settle(4)
            state.value = base("stopped", submissions = listOf(sub("1", vm120, PowerAction.Start, PowerPhase.Unsuccessful, "command 'start' failed: exit code 1")))
            rig.advance(16)
            val dot = rig.bounds(RowAnchor(vm120)) ?: error("no anchor")
            strip(File(dir, "failure-$t.png"), rig, listOf(0, 25, 50, 80, 110, 145, 180, 230, 280, 340, 600), rect(px(0f), dot.y - px(18f), dot.x + px(130f), px(36f)),
                "FAILURE ($t): one nudge of the status chip (-6, +5, -3 dp), the red edge stays, nothing flashes. Reduced: no nudge, colour and text only.", 1f, 1)
        }
    }

    // ---- e. CRT collapse -------------------------------------------------------------------------------------------------------

    private fun collapse(dir: File, tier: MotionTier, t: String, hard: Boolean) {
        val action = if (hard) PowerAction.HardStop else PowerAction.Shutdown
        val state = mutableStateOf(base("running", stills = true, submissions = listOf(sub("1", vm120, action, PowerPhase.Accepted))))
        Rig(tier, 1280, 720, fleet(state)).use { rig ->
            rig.settle(5)
            val tag = rig.bounds(TagAnchor(vm120)) ?: error("no inspector anchor")
            state.value = base("stopped", stills = true, submissions = listOf(sub("1", vm120, action, PowerPhase.Accepted)))
            rig.advance(16)
            val times = if (hard) listOf(0, 24, 48, 72, 96, 112, 130, 160, 200, 260, 500) else listOf(0, 40, 80, 120, 160, 220, 300, 380, 460, 560, 700)
            strip(File(dir, "collapse${if (hard) "-hard" else ""}-$t.png"), rig, times, rect(tag.x - px(160f), tag.y - px(60f), px(380f), px(300f)),
                "${if (hard) "HARD STOP" else "SHUTDOWN"} CRT collapse ($t): the inspector's console still and its status glyph. ${if (hard) "A cut: 110 ms linear, a one-frame 2 px glitch." else "Squash, then a 400 ms phosphor decay."}", 1f, 3)
        }
    }

    // ---- f. camera brackets (the modifier on a form-shaped column; GuestDetailPage uses the same one) -----------------------------

    private fun brackets(dir: File, tier: MotionTier, t: String) {
        val state = mutableStateOf(base("running", selected = false))
        Rig(tier, 700, 330, {
            val s by state
            ProvideMotionBook(s) {
                Column(Modifier.padding(30.dp)) {
                    Column(Modifier.shutterBrackets(vm120), verticalArrangement = Arrangement.spacedBy(Tech.Space12)) {
                        TechField("before-upgrade", {}, "Snapshot name", Modifier.width(420.dp))
                        TechField("", {}, "Description (optional)", Modifier.width(420.dp), singleLine = false)
                        TechAction("Create snapshot", active = true) {}
                    }
                }
            }
        }).use { rig ->
            rig.settle(4)
            state.value = base("running", selected = false, submissions = listOf(sub("2", vm120, PowerAction.SnapshotCreate, PowerPhase.Accepted)))
            rig.advance(16)
            strip(File(dir, "brackets-$t.png"), rig, listOf(0, 30, 60, 90, 130, 180, 260, 380, 460, 520, 600, 700), rect(0, 0, px(520f), px(300f)),
                "CAMERA BRACKETS ($t): snapshot started; brackets snap in 6 dp, one 10% exposure tick, then leave. Reduced: fade only.", 1f, 3)
        }
    }

    // ---- g. console power-on ----------------------------------------------------------------------------------------------------

    private fun powerOn(dir: File, tier: MotionTier, t: String) {
        val trigger = mutableStateOf<Any?>(null)
        Rig(tier, 640, 360, {
            val key by trigger
            Box(Modifier.fillMaxSize().padding(20.dp).background(Color.Black).crtPowerOn(key), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF0B3D6B), Color(0xFF127A4E)))))
                Text("root@debian-workbench:~# _", color = Color(0xFFE6E6E6), fontFamily = FontFamily.Monospace, fontSize = 15.sp)
            }
        }).use { rig ->
            rig.settle(3)
            trigger.value = "session-1"
            rig.advance(16)
            strip(File(dir, "poweron-$t.png"), rig, listOf(0, 16, 32, 48, 64, 90, 120, 160, 200, 240, 300), rect(0, 0, px(640f), px(360f)),
                "CONSOLE CRT POWER-ON ($t): first frame arrives; a glowing line, then the opening grows. Draw effect only: input and frames are never delayed. Reduced: crossfade.", 1f, 4)
        }
    }

    // ---- h. scan radar ping -------------------------------------------------------------------------------------------------------

    private fun radar(dir: File, tier: MotionTier, t: String) {
        val session = ScanSession(scope)
        val plan = NetworkPlan(listOf(ScanNetwork(ScanInterface("Ethernet", "192.168.50.2", 24), "192.168.50.0/24", null, (1..254).map { "192.168.50.$it" })))
        fun hit(n: Int, kind: ScanKind) = ScanHit("192.168.50.$n", if (kind == ScanKind.PVE) 8006 else 443, true, kind, "Host $n", "AB:".repeat(31) + "CD", "CN=host$n.local")
        fun set(count: Int, scanned: Int) {
            val hits = listOf(hit(12, ScanKind.PVE), hit(37, ScanKind.PDM), hit(88, ScanKind.PBS), hit(131, ScanKind.PVE), hit(160, ScanKind.WEB), hit(201, ScanKind.PVE), hit(222, ScanKind.PIHOLE)).take(count)
            @Suppress("UNCHECKED_CAST")
            session.javaClass.getDeclaredField("progress\$delegate").apply { isAccessible = true }.get(session).let { (it as MutableState<NetworkProgress?>).value = NetworkProgress(plan, scanned, hits = hits) }
        }
        @Suppress("UNCHECKED_CAST")
        session.javaClass.getDeclaredField("running\$delegate").apply { isAccessible = true }.get(session).let { (it as MutableState<Boolean>).value = true }
        set(2, 90)
        Rig(tier, 1100, 300, { ScanScreen(session, emptyList()) {} }).use { rig ->
            rig.settle(4)
            set(7, 200)
            rig.advance(16)
            strip(File(dir, "radar-$t.png"), rig, listOf(0, 30, 60, 100, 150, 200, 260, 320, 400, 520), rect(px(0f), px(70f), px(1100f), px(135f)),
                "SCAN RADAR PING ($t): five new hits arrive at once; each fades in and sends one ring, 30 ms apart (none under Reduced; the dots still fade in).", 1f, 2)
        }
    }

    // ---- i. progress hairline -----------------------------------------------------------------------------------------------------

    private fun progress(dir: File, tier: MotionTier, t: String) {
        val now = Instant.now()
        val task = ClusterTask(DEMO_REMOTE_KEY, "demo:vzdump:121", "demo-node-a", "vzdump", "121", "demo@ringmaster", now.minusSeconds(95), null, "running", TaskOutcome.Running)
        fun log(percent: Int) = TaskLogState(task, listOf(TaskLogLine(1, "INFO: starting new backup job"), TaskLogLine(2, "INFO: Starting Backup of VM 121 (lxc)"), TaskLogLine(3, "INFO: ${percent}%: ${percent * 12} MiB")), 3,
            TaskLogStatus("running", null, task.startedAt, null, 8801, "vzdump", "121", "demo@ringmaster", "demo-node-a"))
        fun state(percent: Int) = base("running", selected = false, tasks = listOf(task)).copy(taskLog = log(percent))
        val st = mutableStateOf(state(18))
        Rig(tier, 1500, 420, { val s by st; ActivityScreen(s, workspace, null, {}, {}) }).use { rig ->
            rig.settle(5)
            st.value = state(62); rig.advance(16)
            strip(File(dir, "progress-$t.png"), rig, listOf(0, 80, 160, 260, 380, 520, 700, 900), rect(0, px(146f), px(1010f), px(56f)),
                "PROGRESS TRACE ($t): the log reports 18% then 62%; the hairline glides along the cut-corner outline of the opened row (top edge, right edge, cut corner, bottom edge).", 1f, 1)
        }
    }
}
