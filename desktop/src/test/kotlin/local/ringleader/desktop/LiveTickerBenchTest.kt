@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.unit.Density
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.util.TimeZone
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import local.ringleader.core.*
import org.junit.jupiter.api.Test

/**
 * Opt-in benchmark of the live ticker's cost (RINGMASTER_TICKER_BENCH=1). Invented data; the Fleet page under the app's shell.
 *
 * For the Full and Lite motion tiers (Lite is what software drawing gets) at 1280x720 and 1920x1080 it measures the frame time of the
 * same page: with no tray (the floor), with the tray collapsed to its pill, open and idle, open with a new reading every second as the
 * sampler delivers one, and while it opens and closes. 60 warm-up and 240 measured frames at 16 ms steps; each frame is `render` plus
 * the `yield` that lets recomposition and effects run. Like the Bluetooth bench there are two modes: RASTER (ImageComposeScene.render,
 * which also fills every pixel on the CPU, as a software renderer does) and RECORDED (drawing into a Skia picture: the UI thread's own share,
 * which a GPU replays). Two rounds by default (RINGMASTER_TICKER_BENCH_ROUNDS, and _FRAMES for the measured frames); the best round's median is
 * shown with the spread. Output: stdout and desktop/build/ticker-bench/<label>.txt.
 */
class LiveTickerBenchTest {
    private class Result(val record: Boolean, val tier: MotionTier, val size: String, val scenario: String, val framesMs: List<Double>, val allocPerFrame: Double) {
        val median get() = pct(.5); val p95 get() = pct(.95); val worst get() = framesMs.max()
        fun pct(p: Double) = framesMs.sorted().let { it[((it.size - 1) * p).toInt()] }
    }

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

    @Test fun bench() {
        if (System.getenv("RINGMASTER_TICKER_BENCH") != "1") return
        val label = System.getenv("RINGMASTER_TICKER_BENCH_LABEL") ?: "run"
        val home = System.getProperty("user.home")
        val sandbox = Files.createTempDirectory("ringmaster-ticker-bench")
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        System.setProperty("user.home", sandbox.toString())
        val results = mutableListOf<Result>()
        try {
            Appearance.preview(ThemeMode.Dark)
            runSceneTest {
                val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
                val pages = TickerPages(scope, MatrixLab(sandbox), sandbox)
                try {
                    // JIT and Skia warm-up; not reported.
                    run(pages, MotionTier.Full, "Fleet", 1280, 720, "open idle", coroutineContext, record = false, frames = 200)
                    run(pages, MotionTier.Full, "Fleet", 1280, 720, "open idle", coroutineContext, record = true, frames = 200)
                    repeat(rounds) {
                        for (record in listOf(true, false)) for (tier in listOf(MotionTier.Full, MotionTier.Lite)) for ((w, h) in listOf(1280 to 720, 1920 to 1080))
                            for (scenario in SCENARIOS) results += run(pages, tier, "Fleet", w, h, scenario, coroutineContext, record)
                    }
                } finally { pages.close(); scope.cancel() }
            }
        } finally { TimeZone.setDefault(zone); System.setProperty("user.home", home); LiveTickerPreference.preview(TickerMode.Automatic); sandbox.toFile().deleteRecursively() }
        val text = buildString {
            appendLine("Live ticker benchmark: $label (best of $rounds rounds by median, $measuredFrames measured frames each; spread = max - min of the round medians; frame ms)")
            for (record in listOf(true, false)) {
                appendLine()
                appendLine(if (record) "RECORDED: recomposition + layout + drawing into a Skia picture + effects (the UI thread's work; a GPU replays the picture)"
                    else "RASTER: the same plus CPU rasterisation of every pixel (what a software renderer does) + effects")
                appendLine("| tier | size | scenario | median | p95 | worst | spread | KB alloc/frame | median over floor |")
                appendLine("|---|---|---|---|---|---|---|---|---|")
                for (tier in listOf(MotionTier.Full, MotionTier.Lite)) for (size in listOf("1280x720", "1920x1080")) {
                    val floor = results.filter { it.record == record && it.tier == tier && it.size == size && it.scenario == SCENARIOS.first() }.minOf { it.median }
                    for (scenario in SCENARIOS) {
                        val taken = results.filter { it.record == record && it.tier == tier && it.size == size && it.scenario == scenario }
                        val best = taken.minBy { it.median }
                        val spread = taken.maxOf { it.median } - taken.minOf { it.median }
                        appendLine("| $tier | $size | $scenario | ${f(best.median)} | ${f(best.p95)} | ${f(best.worst)} | ${f(spread)} | ${f(best.allocPerFrame / 1024)} | ${if (scenario == SCENARIOS.first()) "-" else "%+.2f".format(java.util.Locale.ROOT, best.median - floor)} |")
                    }
                }
            }
        }
        println(text)
        File("build/ticker-bench").apply { mkdirs() }.resolve("$label.txt").writeText(text)
    }

    private val rounds = System.getenv("RINGMASTER_TICKER_BENCH_ROUNDS")?.toIntOrNull()?.coerceIn(1, 5) ?: 2
    private val measuredFrames = System.getenv("RINGMASTER_TICKER_BENCH_FRAMES")?.toIntOrNull()?.coerceIn(60, 600) ?: 240

    private fun f(v: Double) = "%.2f".format(java.util.Locale.ROOT, v)

    private suspend fun run(pages: TickerPages, tier: MotionTier, page: String, w: Int, h: Int, scenario: String, context: CoroutineContext, record: Boolean, frames: Int = measuredFrames): Result {
        val mode = when (scenario) { "no tray" -> TickerMode.Off; "pill (collapsed)" -> TickerMode.Automatic; else -> TickerMode.Always }
        LiveTickerPreference.preview(mode)
        val bench = TickerBench()
        bench.sample(TickerData.calm, mode)
        val scene = ImageComposeScene(w, h, Density(1f), coroutineContext = context) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides false, LocalMotion provides RmMotion(tier), LocalPageFocus provides bench.focus) {
                    Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                        TickerShell(page, pages.state(page), pages.idle, pages.lab, false, bench) { pages.Content(page) }
                    }
                }
            }
        }
        val recorder = if (record) Recorder(scene, w, h) else null
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val step = 16_000_000L
        var time = 0L
        val warm = 60
        val measured = ArrayList<Double>(frames)
        var alloc = 0L
        try {
            for (i in 0 until warm + frames) {
                val a0 = threads.currentThreadAllocatedBytes
                val t0 = System.nanoTime()
                time += step
                // A reading a second (every 62 frames of 16 ms), as the sampler delivers: new numbers and history in the tiles.
                if (scenario != "no tray" && (scenario == "open, a reading each second" || scenario == "open and close") && i % 62 == 0)
                    bench.sample(TickerData.input(TickerData.vitals(perMinute = 10 + (i / 62) % 9, inFlight = (i / 62) % 3), TickerData.metrics(p50 = 30L + (i / 62) % 17)), mode)
                // Open and close: Escape-style collapse and a click-style open, alternately, so the pop, the fade and the plate resizing run.
                if (scenario == "open and close" && i % 40 == 20) { if (bench.state.expanded) bench.state.collapse(mode) else bench.state.open(mode) }
                if (recorder != null) recorder.frame(time) else scene.render(time).close()
                yield()
                val dt = (System.nanoTime() - t0) / 1e6
                if (i >= warm) { measured += dt; alloc += threads.currentThreadAllocatedBytes - a0 }
            }
        } finally { scene.close() }
        return Result(record, tier, "${w}x$h", scenario, measured, alloc.toDouble() / frames)
    }

    companion object {
        val SCENARIOS = listOf("no tray", "pill (collapsed)", "open idle", "open, a reading each second", "open and close")
    }
}
