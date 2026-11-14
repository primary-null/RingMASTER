package local.ringleader.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.*
import local.ringleader.core.*
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface as SkSurface
import org.junit.jupiter.api.Test

/**
 * Opt-in timed render probe of page changes on the real screens (RINGMASTER_MOTION_PROBE=1, or with RINGMASTER_SCREENSHOTS=1).
 * A rail plus Desk, Fleet, Activity and the Watcher, at 1600x900 in an offscreen scene (Skia on the CPU, the cost class of
 * the Lite tier), stepped in 16 ms frames. For each change it records the time each frame took to render and how many
 * pages were alive, and writes a strip of frames. Run with the previous AnimatedContent ("old") and the picture crossfade
 * ("new"), on Full, Lite and Reduced. Output: artifacts/ui/<phase>/motion/ (frames-*.png and motion-metrics.md).
 */
class PageMotionProbeTest {
    @Test fun probe() {
        if (System.getenv("RINGMASTER_MOTION_PROBE") != "1" && System.getenv("RINGMASTER_SCREENSHOTS") != "1") return
        val phase = System.getenv("RINGMASTER_UI_PHASE") ?: "after"
        val out = File("../artifacts/ui/$phase/motion").apply { mkdirs() }
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = Workspace(scope)
        workspace.exploreDemo()
        runBlocking { delay(2500) }
        val report = StringBuilder("# Page change probe (offscreen CPU raster, 1600x900, 16 ms steps)\n\n")
        report.append("Each row: one warm-up pass of every case first, then $REPS runs interleaved old/new. Times are per rendered frame over the 16 frames after the change.\n\n")
        report.append("| change | tier | host | first frame ms (median) | frame ms median | worst frame ms (max of runs) | frames > 33 ms (mean) | live pages max | blank frames |\n|---|---|---|---|---|---|---|---|---|\n")
        val alerts = AlertsUi(notices = AlertsFixtures.center(AlertsFixtures.mixed()), watcher = AlertsFixtures.watcher())
        val changes = listOf("Desk" to "Fleet", "Fleet" to "Activity", "Activity" to WATCHER_PAGE, "Fleet" to "Desk")
        class Result(val first: Double, val median: Double, val worst: Double, val slow: Int, val maxAlive: Int, val blank: Int)
        fun run(tier: MotionTier, old: Boolean, from: String, to: String, strip: File?): Result {
            val page = mutableStateOf(from)
            var alive = 0; var maxAlive = 0
            val state = workspace.state.value
            val scene = ImageComposeScene(1600, 900, Density(1f)) {
                MaterialTheme(colorScheme = ringColors) {
                    CompositionLocalProvider(LocalReduceMotion provides (tier == MotionTier.Reduced), LocalAlerts provides alerts) {
                        ProvideMotionSystem(tierOverride = tier) {
                            Surface(Modifier.fillMaxSize()) { Row {
                                NavigationRail(state, page.value, "Probe", workspace, initialExpanded = false) {}
                                Box(Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                                    val body: @Composable (String) -> Unit = { p ->
                                        DisposableEffect(Unit) { alive++; maxAlive = maxOf(maxAlive, alive); onDispose { alive-- } }
                                        when (p) {
                                            "Fleet" -> InventoryScreen(state, workspace, null, "", {}, "All", {}, {}, {})
                                            "Activity" -> ActivityScreen(state, workspace, null, {}, {})
                                            WATCHER_PAGE -> WatcherScreen(alerts.watcher!!, WatcherActions(onUnwatch = {}), { true }, {})
                                            else -> Box(Modifier.fillMaxSize())  // Desk needs a profile store; an empty page stands in.
                                        }
                                    }
                                    if (old) {
                                        val ms = if (tier == MotionTier.Reduced) 0 else PAGE_TRANSITION_MS
                                        val slide = if (tier == MotionTier.Full) 12 else 0
                                        AnimatedContent(page.value, transitionSpec = { (fadeIn(tween(ms, easing = MotionEase.Standard)) + slideInHorizontally(tween(ms, easing = MotionEase.Standard)) { if (ringPages.indexOf(targetState) > ringPages.indexOf(initialState)) slide else -slide }) togetherWith fadeOut(tween(ms)) }) { body(it) }
                                    } else PageTransitionHost(page.value, Modifier.fillMaxSize(), direction = ::pageDirection) { body(it) }
                                }
                            } }
                        }
                    }
                }
            }
            try {
                var clock = 0L
                fun frame(): Pair<Double, Image> { clock += 16_000_000L; val t0 = System.nanoTime(); val img = scene.render(clock); return (System.nanoTime() - t0) / 1e6 to img }
                repeat(40) { frame().second.close() }   // settle (row entrances finish) and warm up
                val startShare = frame().second.let { val v = contentShare(it); it.close(); v }
                alive = 1; maxAlive = 1
                page.value = to
                val times = ArrayList<Double>(); val shares = ArrayList<Float>(); val frames = ArrayList<Image>()
                for (i in 0 until 16) {
                    val (ms, img) = frame()
                    times += ms; shares += contentShare(img)
                    if (strip != null && i in listOf(0, 2, 4, 6, 9, 15)) frames += img else img.close()
                }
                repeat(30) { frame().second.close() }
                val endShare = frame().second.let { val v = contentShare(it); it.close(); v }
                // A blank frame shows far less than both the page before and the page after (an empty Desk stand-in excluded).
                val floor = minOf(startShare, endShare) * .5f
                val blank = if (floor < .01f) 0 else shares.count { it < floor }
                if (strip != null) { writeStrip(frames, strip); frames.forEach { it.close() } }
                val sorted = times.sorted()
                return Result(times.first(), sorted[sorted.size / 2], sorted.last(), times.count { it > 33.0 }, maxAlive, blank)
            } finally { scene.close() }
        }
        try {
            val tiers = listOf(MotionTier.Full, MotionTier.Lite, MotionTier.Reduced)
            for (tier in tiers) for (old in listOf(true, false)) for ((from, to) in changes) run(tier, old, from, to, null)   // warm-up pass
            for (tier in tiers) for ((from, to) in changes) {
                val runs = HashMap<Boolean, MutableList<Result>>()
                repeat(REPS) { rep -> for (old in listOf(rep % 2 == 0, rep % 2 != 0)) {
                    val strip = if (rep == 0) File(out, "frames-${tier.name.lowercase()}-${if (old) "old" else "new"}-${from.lowercase()}-${to.lowercase()}.png") else null
                    runs.getOrPut(old) { mutableListOf() } += run(tier, old, from, to, strip)
                } }
                for (old in listOf(true, false)) {
                    val r = runs.getValue(old)
                    fun med(f: (Result) -> Double) = r.map(f).sorted()[r.size / 2]
                    report.append("| $from → $to | $tier | ${if (old) "AnimatedContent (old)" else "picture crossfade (new)"} | ${"%.1f".format(med { it.first })} | ${"%.1f".format(med { it.median })} | ${"%.1f".format(r.maxOf { it.worst })} | ${"%.1f".format(r.map { it.slow }.average())} | ${r.maxOf { it.maxAlive }} | ${r.maxOf { it.blank }} |\n")
                }
            }
            report.append("\nFirst frame includes composing the incoming page for the old host; the new host composes it one frame later (after it took its picture), so compare the worst frame too. \"live pages max\" counts page compositions alive at once during the change. CPU raster in a test JVM: compare rows with each other, not with a GPU window.\n")
            File(out, "motion-metrics.md").writeText(report.toString(), Charsets.UTF_8)
            println(report)
        } finally { workspace.close(); scope.cancel() }
    }

    private companion object { const val REPS = 3 }

    /** Share of content-area pixels (right of the rail) that differ from the top-left canvas pixel. */
    private fun contentShare(img: Image): Float {
        val b = Bitmap.makeFromImage(img)
        try {
            val bg = b.getColor(1599, 899)
            var diff = 0; var n = 0
            for (y in 40 until 880 step 8) for (x in 120 until 1580 step 8) { n++; if (b.getColor(x, y) != bg) diff++ }
            return diff.toFloat() / n
        } finally { b.close() }
    }

    /** Six frames side by side at half size. */
    private fun writeStrip(frames: List<Image>, file: File) {
        val w = 800; val h = 450
        val surface = SkSurface.makeRasterN32Premul(w * frames.size, h)
        frames.forEachIndexed { i, f -> surface.canvas.drawImageRect(f, org.jetbrains.skia.Rect.makeXYWH(i * w.toFloat(), 0f, w.toFloat(), h.toFloat())) }
        file.writeBytes(surface.makeImageSnapshot().encodeToData()!!.bytes)
        surface.close()
    }
}
