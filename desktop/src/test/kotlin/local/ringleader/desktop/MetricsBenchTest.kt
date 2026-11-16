@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import local.ringleader.core.*
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.coroutines.CoroutineContext

/**
 * Opt-in frame bench of the metric visuals (RINGMASTER_METRICS_BENCH=1), in the manner of [BluetoothBenchTest]. Invented
 * fleet ([MetricsFixtures]); no server. Two scenes at 1600x1000, density 1, Dark, motion on:
 *
 * - fleet: the Fleet page with nothing selected (strip, 40-row table with mini charts, cluster inspector). A new live sample
 *   for every guest, node and the cluster arrives every second (five times the app's default rate, so each 450 ms slide is
 *   measured often); in the second half the pointer sweeps over the inspector's charts.
 * - lens: the chart lens open on one guest's live CPU, same sample rate, pointer sweeping the plot in the second half.
 *
 * 60 warm-up and 300 measured frames at 16 ms steps; each frame is render (or a recording) plus the yield that runs
 * effects. RECORDED draws into a Skia picture (the UI thread's work: composition, layout, draw recording); RASTER also fills
 * every pixel on the CPU, as the software renderer does. Best of three rounds by median. Writes desktop/build/metrics-bench/<label>.txt.
 */
class MetricsBenchTest {
    private class Result(val scene: String, val record: Boolean, val frames: List<Double>, val alloc: Double, val pointer: List<Double>) {
        fun pct(p: Double) = frames.sorted().let { it[((it.size - 1) * p).toInt()] }
        val median get() = pct(.5); val p95 get() = pct(.95); val worst get() = frames.max()
        val pointerMedian get() = pointer.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
    }

    @Test fun bench() {
        if (System.getenv("RINGMASTER_METRICS_BENCH") != "1") return
        val label = System.getenv("RINGMASTER_METRICS_BENCH_LABEL") ?: "run"
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = Workspace(scope)
        workspace.exploreDemo(); workspace.setLive(false)
        val base = workspace.state.value
        val overview = MetricsFixtures.overview(base.overview!!)
        val start = MetricsFixtures.history(overview)
        // Ten seconds of future samples, built before measuring so their cost is not counted.
        val states = (0..12).map { k -> base.copy(overview = overview, history = shifted(start, k), selected = null, updatedAt = MetricsFixtures.now.plusSeconds(k * 10L)) }
        val results = mutableListOf<Result>()
        Appearance.preview(ThemeMode.Dark)
        try {
            runSceneTest {
                run("fleet", states, workspace, coroutineContext, record = true, frames = 200)
                repeat(3) { for (record in listOf(true, false)) for (scene in listOf("fleet", "lens")) results += run(scene, states, workspace, coroutineContext, record) }
            }
        } finally { workspace.close(); scope.cancel() }
        val text = buildString {
            appendLine("Metric visuals bench: $label (1600x1000, density 1, Dark, motion on; best of 3 by median)")
            appendLine("| mode | scene | median ms | p95 ms | worst ms | pointer-half median ms | KB alloc/frame |")
            appendLine("|---|---|---|---|---|---|---|")
            for (record in listOf(true, false)) for (scene in listOf("fleet", "lens")) {
                val best = results.filter { it.record == record && it.scene == scene }.minBy { it.median }
                appendLine("| ${if (record) "RECORDED" else "RASTER"} | $scene | ${f(best.median)} | ${f(best.p95)} | ${f(best.worst)} | ${f(best.pointerMedian)} | ${f(best.alloc / 1024)} |")
            }
        }
        println(text)
        File("build/metrics-bench").apply { mkdirs() }.resolve("$label.txt").writeText(text)
    }

    /** The history [k] samples later: every series drops its oldest sample and gains one. */
    private fun shifted(h: FleetHistory, k: Int): FleetHistory {
        if (k == 0) return h
        fun Series.roll(): Series {
            if (samples.size < 2) return this
            val tail = samples.drop(k) + samples.take(k).map { it.copy(at = it.at.plusSeconds(MetricsFixtures.SAMPLES * 10L)) }
            return Series(tail.takeLast(samples.size))
        }
        return FleetHistory(h.guests.mapValues { it.value.roll() }, h.nodes.mapValues { it.value.roll() }, h.cluster.roll())
    }

    private fun f(v: Double) = "%.2f".format(java.util.Locale.ROOT, v)

    private class Recorder(scene: ImageComposeScene, private val w: Int, private val h: Int) {
        private val frames = ImageComposeScene::class.java.getDeclaredField("frameRecomposer").apply { isAccessible = true }.get(scene) as androidx.compose.ui.platform.FrameRecomposer
        private val inner = ImageComposeScene::class.java.getDeclaredField("scene").apply { isAccessible = true }.get(scene) as androidx.compose.ui.scene.ComposeScene
        fun frame(nanos: Long) {
            frames.performFrame(nanos)
            inner.measureAndLayout()
            val recorder = org.jetbrains.skia.PictureRecorder()
            val canvas = recorder.beginRecording(org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()))
            inner.draw(canvas.asComposeCanvas())
            recorder.finishRecordingAsPicture().close()
            recorder.close()
        }
    }

    private suspend fun run(name: String, states: List<WorkspaceState>, workspace: Workspace, context: CoroutineContext, record: Boolean, frames: Int = 300): Result {
        val w = 1600; val h = 1000
        var shown by mutableStateOf(states.first())
        val hot = states.first().overview!!.guests[MetricsFixtures.overloaded]
        val scene = ImageComposeScene(w, h, Density(1f), coroutineContext = context) {
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides false, LocalNow provides MetricsFixtures.now) {
                Surface(Modifier.fillMaxSize(), color = Tech.Canvas) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(16.dp)) {
                        if (name == "lens") ChartLensHost(shown, workspace) {
                            val lens = LocalChartLens.current
                            LaunchedEffect(Unit) { lens?.open(guestLens(hot, LiveMetric.Cpu)) }
                            InventoryScreen(shown, workspace, null, "", {}, "All", {}, {}, {})
                        } else InventoryScreen(shown, workspace, null, "", {}, "All", {}, {}, {})
                    }
                }
            } }
        }
        val recorder = if (record) Recorder(scene, w, h) else null
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val warm = 60
        val measured = ArrayList<Double>(frames); val pointer = ArrayList<Double>(frames / 2)
        var alloc = 0L; var time = 0L; var next = 1
        try {
            for (i in 0 until warm + frames) {
                val a0 = threads.currentThreadAllocatedBytes
                val t0 = System.nanoTime()
                time += 16_000_000L
                if (i > 0 && i % 62 == 0) { shown = states[next % states.size]; next++ }
                val sweep = i >= warm + frames / 2
                if (sweep) {
                    val t = ((i - warm - frames / 2) % 90) / 90f
                    val at = if (name == "lens") Offset(w * (.2f + .6f * t), h * .55f) else Offset(w - 340f + 300f * t, 330f)
                    scene.sendPointerEvent(PointerEventType.Move, at)
                }
                if (recorder != null) recorder.frame(time) else scene.render(time).close()
                yield()
                val dt = (System.nanoTime() - t0) / 1e6
                if (i >= warm) { measured += dt; alloc += threads.currentThreadAllocatedBytes - a0; if (sweep) pointer += dt }
            }
        } finally { scene.close() }
        return Result(name, record, measured, alloc.toDouble() / frames, pointer)
    }
}
