@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.coroutines.CoroutineContext
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.yield
import local.ringleader.core.BtScan
import org.junit.jupiter.api.Test

/**
 * Opt-in benchmark of the Bluetooth scan view during a live scan (RINGMASTER_BT_BENCH=1). Invented devices only; no radio.
 *
 * For 10, 40 and 120 devices, at 1600×1000 (density 1) and 2560×1440 (density 1.25), it drives [BtScanView] for 60 warm-up
 * and 360 measured frames at 16 ms steps. A new snapshot arrives every 200 ms, as a real scan's partials do; in the
 * second half the pointer circles over the field, as when the user looks for a dot. Each frame is `render` plus the
 * `yield` that lets the scene's recomposition and effects run, timed together. It records frame time (median, 95th
 * percentile, worst), bytes allocated on the UI thread per frame, table-row compositions and text layouts.
 *
 * Two modes. RASTER uses ImageComposeScene.render, which also fills every pixel on the CPU: at 2560×1440 that alone costs
 * about 20 ms a frame however little changes (the "idle" rows show it), so it hides the page's own work. RECORDED does
 * what render does but draws into a Skia picture instead of pixels: recomposition, layout and draw recording, which is the
 * UI thread's share of a frame in the app, where a GPU (Direct3D, Metal, OpenGL) replays the drawing.
 *
 * Every scenario runs three rounds (other agents may share this computer): the table shows the best round's numbers and
 * the spread of the medians. Output: stdout and desktop/build/bt-bench/<label>.txt (RINGMASTER_BT_BENCH_LABEL, default "run").
 */
class BluetoothBenchTest {
    private data class Shape(val width: Int, val height: Int, val density: Float) { override fun toString() = "${width}x$height@$density" }

    private class Result(val record: Boolean, val shape: Shape, val devices: Int, val framesMs: List<Double>, val allocPerFrame: Double, val updates: Int,
        val rows: Int, val texts: Int, val hits: Int, val moves: Int, val pointerMedianMs: Double, val renderMs: List<Double>, val live: Boolean) {
        val renderMedian get() = renderMs.sorted()[renderMs.size / 2]
        val median get() = pct(.5); val p95 get() = pct(.95); val worst get() = framesMs.max()
        fun pct(p: Double) = framesMs.sorted().let { it[((it.size - 1) * p).toInt()] }
    }

    @Test fun bench() {
        if (System.getenv("RINGMASTER_BT_BENCH") != "1") return
        val label = System.getenv("RINGMASTER_BT_BENCH_LABEL") ?: "run"
        val shapes = listOf(Shape(1600, 1000, 1f), Shape(2560, 1440, 1.25f))
        val counts = listOf(10, 40, 120)
        val results = mutableListOf<Result>()
        Appearance.preview(ThemeMode.Dark)
        runSceneTest {
            // JIT and Skia warm-up; not reported.
            run(Shape(1600, 1000, 1f), 40, coroutineContext, frames = 240)
            run(Shape(1600, 1000, 1f), 40, coroutineContext, frames = 240, record = true)
            repeat(3) {
                for (record in listOf(true, false)) for (shape in shapes) {
                    for (n in counts) results += run(shape, n, coroutineContext, record = record)
                    // The floor: the same view holding a finished scan, with nothing moving. A scene repaints every pixel each frame.
                    results += run(shape, 120, coroutineContext, live = false, record = record)
                }
            }
        }
        val text = buildString {
            appendLine("Bluetooth scan view benchmark: $label (best of 3 rounds by median; spread = max - min of the 3 medians)")
            for (record in listOf(true, false)) {
            appendLine()
            appendLine(if (record) "RECORDED: frame = recomposition + layout + drawing into a Skia picture + effects (the UI thread's work; a GPU replays the picture)"
                else "RASTER: frame = ImageComposeScene.render (the same, plus CPU rasterisation of every pixel) + effects")
            appendLine("| size | devices | median ms | p95 ms | worst ms | spread ms | render median ms | pointer-half median ms | KB alloc/frame | row comps/update | text layouts/frame | hit positions/move|")
            appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
            for (shape in shapes) for ((n, live) in counts.map { it to true } + (120 to false)) {
                val rounds = results.filter { it.record == record && it.shape == shape && it.devices == n && it.live == live }
                val best = rounds.minBy { it.median }
                val spread = rounds.maxOf { it.median } - rounds.minOf { it.median }
                appendLine("| $shape | ${if (live) "$n" else "$n idle"} | ${f(best.median)} | ${f(best.p95)} | ${f(best.worst)} | ${f(spread)} | ${f(best.renderMedian)} | ${f(best.pointerMedianMs)} | " +
                    "${f(best.allocPerFrame / 1024)} | ${f(best.rows.toDouble() / best.updates.coerceAtLeast(1))} | " +
                    "${f(best.texts.toDouble() / best.framesMs.size)} | ${f(best.hits.toDouble() / best.moves.coerceAtLeast(1))} |")
            }
            }
        }
        println(text)
        File("build/bt-bench").apply { mkdirs() }.resolve("$label.txt").writeText(text)
    }

    private fun f(v: Double) = "%.2f".format(java.util.Locale.ROOT, v)

    /** One frame drawn into a picture instead of the scene's raster surface: what ImageComposeScene.render does, minus the pixels. */
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

    private suspend fun run(shape: Shape, n: Int, context: CoroutineContext, frames: Int = 360, live: Boolean = true, record: Boolean = false): Result {
        val sim = BtSimulatedScan(n, simultaneous = System.getenv("RINGMASTER_BT_SIMULTANEOUS") == "1")
        sim.advanceTo(if (live) 2_000 else sim.windowMs)
        if (System.getenv("RINGMASTER_BT_SIMULTANEOUS") == "1") check(sim.snapshot().devices.size == n)
        var scan by mutableStateOf<BtScan?>(sim.snapshot(finished = !live))
        var received by mutableLongStateOf(0L)
        val scene = ImageComposeScene(shape.width, shape.height, Density(shape.density), coroutineContext = context) {
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides false) {
                Surface(Modifier.fillMaxSize(), color = Tech.Canvas) {
                    BtScanView(scan, scanning = live, modifier = Modifier.padding(16.dp), platform = "Windows · WinRT", receivedAt = received)
                }
            } }
        }
        val recorder = if (record) Recorder(scene, shape.width, shape.height) else null
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val step = 16_000_000L
        var time = 0L
        var lastEmit = 2_000L
        val warm = 60
        val measured = ArrayList<Double>(frames)
        val renders = ArrayList<Double>(frames)
        val pointerFrames = ArrayList<Double>(frames / 2)
        var alloc = 0L; var updates = 0; var moves = 0
        // The field sits at the top left of the wide layout: 16 dp page padding, a 14 dp header line, an 8 dp plate inset.
        val cx = (16 + 8 + 182) * shape.density; val cy = (16 + 22 + 8 + 182) * shape.density
        try {
            for (i in 0 until warm + frames) {
                if (i == warm) BtScanStats.reset()
                val ms = 2_000L + i * 16L
                val a0 = threads.currentThreadAllocatedBytes
                val t0 = System.nanoTime()
                time += step
                if (live && ms - lastEmit >= 200) {
                    sim.advanceTo(ms); scan = sim.snapshot(); received = time; lastEmit = ms
                    if (i >= warm) updates++
                }
                val pointer = live && i >= warm + frames / 2
                if (pointer) {
                    val a = i * .05
                    scene.sendPointerEvent(PointerEventType.Move, Offset((cx + cos(a) * 120 * shape.density).toFloat(), (cy + sin(a) * 120 * shape.density).toFloat()))
                    moves++
                }
                val r0 = System.nanoTime()
                if (recorder != null) recorder.frame(time) else scene.render(time).close()
                val r1 = System.nanoTime()
                yield()
                val dt = (System.nanoTime() - t0) / 1e6
                val a1 = threads.currentThreadAllocatedBytes
                if (i >= warm) { measured += dt; renders += (r1 - r0) / 1e6; alloc += a1 - a0; if (pointer) pointerFrames += dt }
            }
        } finally { scene.close() }
        val pm = pointerFrames.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        return Result(record, shape, n, measured, alloc.toDouble() / frames, updates, BtScanStats.rowCompositions, BtScanStats.textMeasures,
            BtScanStats.hitPositions, moves.coerceAtLeast(1), pm, renders, live)
    }
}
