@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import local.ringleader.core.HistoryPoint
import local.ringleader.core.ResourceId
import local.ringleader.core.Sample
import local.ringleader.core.Series
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState

/** The chart lens: live mapping, open and close, and the history chart's plot height and expand button. */
class ChartLensTest {
    private val at = Instant.parse("2026-11-11T14:03:25Z")

    private fun sample(cpu: Double = 0.42, mem: Long = 100, memMax: Long = 400, netIn: Double = 1000.0, netOut: Double = 2000.0) =
        Sample(at, cpu, mem, memMax, netIn, netOut, false)

    @Test fun aSeriesBecomesHistoryPointsForTheSubject() {
        val series = Series(listOf(sample()))
        val cpu = seriesHistory(series, LiveMetric.Cpu, node = false).single()
        assertEquals(at.toEpochMilli() / 1000.0, cpu.time)
        assertEquals(mapOf("cpu" to 0.42), cpu.values)
        assertEquals(listOf("mem", "maxmem"), seriesHistory(series, LiveMetric.Memory, node = false).single().values.keys.toList())
        assertEquals(listOf("memused", "memtotal"), seriesHistory(series, LiveMetric.Memory, node = true).single().values.keys.toList())
        assertEquals(100.0, seriesHistory(series, LiveMetric.Memory, node = true).single().values["memused"])
        assertEquals(mapOf("netin" to 1000.0, "netout" to 2000.0), seriesHistory(series, LiveMetric.Network, node = false).single().values)
        assertEquals(listOf("cpu"), lensChart(LiveMetric.Cpu, node = true).keys)
        assertEquals(listOf("memused", "memtotal"), lensChart(LiveMetric.Memory, node = true).keys)
        assertEquals(listOf("mem", "maxmem"), lensChart(LiveMetric.Memory, node = false).keys)
    }

    @Test fun liveIsOfferedOnlyForOneLiveMetric() {
        assertEquals(LiveMetric.Cpu, liveMetricFor(listOf("cpu")))
        assertEquals(LiveMetric.Memory, liveMetricFor(listOf("mem", "maxmem")))
        assertEquals(LiveMetric.Memory, liveMetricFor(listOf("memused", "memtotal")))
        assertEquals(LiveMetric.Network, liveMetricFor(listOf("netin", "netout")))
        assertNull(liveMetricFor(listOf("cpu", "iowait")))
        assertNull(liveMetricFor(listOf("diskread", "diskwrite")))
        assertNull(liveMetricFor(listOf("loadavg")))
        assertNull(liveMetricFor(emptyList()))
        val disk = historyLensRequest(WorkspaceState(), "node-a", "Disk IO", listOf("diskread", "diskwrite"), "live", "MAX", null)
        assertEquals("hour", disk.timeframe)
        assertEquals(listOf("diskread", "diskwrite"), disk.chart.keys)
        assertEquals(LensSubject.NodeSubject("node-a"), disk.subject)
        val guest = historyLensRequest(WorkspaceState(selected = local.ringleader.core.Guest("https://example.test:8006", 112, "web", "node-a", "VM", "running")), null, "CPU", listOf("cpu"), "hour", "AVERAGE", null)
        assertTrue(guest.subject is LensSubject.GuestSubject)
        assertEquals("hour", guest.timeframe)
        assertEquals(LiveMetric.Cpu, guest.chart.live)
    }

    @Test fun theLensOpensAndCloses() {
        val lens = ChartLensState()
        assertNull(lens.request)
        val request = LensRequest(LensSubject.Cluster, lensChart(LiveMetric.Cpu, false))
        lens.open(request)
        assertEquals(request, lens.request)
        lens.close()
        assertNull(lens.request)
    }

    @Test fun aClickOpensTheLensAndEscOrTheScrimClosesIt() = runBlocking {
        val workspace = Workspace(CoroutineScope(Dispatchers.Unconfined))
        var page = 0
        val scene = ImageComposeScene(1000, 700, Density(1f), coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        ChartLensHost(WorkspaceState(), workspace) {
                            Box(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { page++ })
                                Sparkline(listOf(0.2, 0.4, 0.3), Modifier.opensLens { LensRequest(LensSubject.Cluster, lensChart(LiveMetric.Cpu, false)) }
                                    .offset(16.dp, 16.dp).size(200.dp, 48.dp).background(Color.Black), color = Color(0xFF1B5E20), pulse = false, label = { "20.0 %" })
                            }
                        }
                    }
                }
            }
        }
        try {
            var now = 0L
            suspend fun step(ms: Long = 16) { now += ms; scene.render(now * 1_000_000L).close(); yield() }
            repeat(4) { step() }
            assertTrue(centerDark(scene, now) < 100, "the page starts blank")
            val spark = Offset(80f, 40f)
            scene.sendPointerEvent(PointerEventType.Move, spark)
            scene.sendPointerEvent(PointerEventType.Press, spark)
            scene.sendPointerEvent(PointerEventType.Release, spark)
            repeat(12) { step() }
            assertTrue(centerDark(scene, now) > 1000, "the lens panel should cover the middle within 160 ms")
            assertEquals(0, page, "opening the lens must not click the page")
            val panel = Offset(500f, 350f)
            scene.sendPointerEvent(PointerEventType.Move, panel)
            scene.sendPointerEvent(PointerEventType.Press, panel)
            scene.sendPointerEvent(PointerEventType.Release, panel)
            repeat(4) { step() }
            assertTrue(centerDark(scene, now) > 1000, "a click on the panel stays on the panel")
            assertEquals(0, page, "a click on the panel must not reach the page")
            scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown))
            repeat(12) { step() }
            assertTrue(centerDark(scene, now) < 100, "Esc closes the lens")
            scene.sendPointerEvent(PointerEventType.Press, spark)
            scene.sendPointerEvent(PointerEventType.Release, spark)
            repeat(12) { step() }
            assertTrue(centerDark(scene, now) > 1000)
            scene.sendPointerEvent(PointerEventType.Move, Offset(4f, 4f))
            scene.sendPointerEvent(PointerEventType.Press, Offset(4f, 4f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(4f, 4f))
            repeat(12) { step() }
            assertTrue(centerDark(scene, now) < 100, "a scrim click closes the lens")
            assertEquals(0, page, "the scrim click must not reach the page")
        } finally { scene.close(); workspace.close() }
    }

    @Test fun reducedMotionShowsTheLensWithin160ms() = runBlocking {
        val workspace = Workspace(CoroutineScope(Dispatchers.Unconfined))
        val scene = ImageComposeScene(1000, 700, Density(1f), coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        ChartLensHost(WorkspaceState(), workspace) {
                            Sparkline(listOf(0.2, 0.5), Modifier.opensLens { LensRequest(LensSubject.Cluster, lensChart(LiveMetric.Memory, false)) }
                                .offset(20.dp, 20.dp).size(160.dp, 40.dp).background(Color.Black), color = Color(0xFF1B5E20), pulse = false)
                        }
                    }
                }
            }
        }
        try {
            var now = 0L
            suspend fun step() { now += 16; scene.render(now * 1_000_000L).close(); yield() }
            repeat(3) { step() }
            val at = Offset(60f, 36f)
            scene.sendPointerEvent(PointerEventType.Press, at)
            scene.sendPointerEvent(PointerEventType.Release, at)
            repeat(10) { step() }
            assertTrue(now <= 208, "the clock ran past the budget ($now ms)")
            assertTrue(centerDark(scene, now) > 1000, "reduced motion should be fully shown by 160 ms")
        } finally { scene.close(); workspace.close() }
    }

    @Test fun plotHeightChangesTheChart() {
        fun height(plot: Int): Int {
            var measured = 0
            val scene = ImageComposeScene(480, 640, Density(1f)) {
                MaterialTheme(colorScheme = ringColors) {
                    Box(Modifier.onSizeChanged { measured = it.height }) {
                        HistoryChart("CPU", emptyList(), listOf("cpu"), plotHeight = plot.dp)
                    }
                }
            }
            try { scene.render(16_000_000L).close() } finally { scene.close() }
            return measured
        }
        assertEquals(70, height(220) - height(150))
    }

    @Test fun theExpandButtonOpensThatChart() = runBlocking {
        var bounds by mutableStateOf<Rect?>(null)
        val lens = ChartLensState()
        val scene = ImageComposeScene(640, 360, Density(1f), coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalChartLens provides lens, LocalExpandBounds provides { bounds = it }, LocalReduceMotion provides true) {
                    HistoryChart("Disk IO", listOf(HistoryPoint(1.0, mapOf("diskread" to 10.0, "diskwrite" to 4.0))), listOf("diskread", "diskwrite"),
                        onExpand = { lens.open(historyLensRequest(WorkspaceState(), "node-a", "Disk IO", listOf("diskread", "diskwrite"), "hour", "AVERAGE", null)) })
                }
            }
        }
        try {
            repeat(4) { scene.render(System.nanoTime()).close(); yield() }
            val spot = bounds ?: error("the expand button did not report its bounds")
            val at = Offset(spot.center.x, spot.center.y)
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at)
            scene.sendPointerEvent(PointerEventType.Release, at)
            scene.render(System.nanoTime()).close()
            val opened = lens.request ?: error("the expand button did not open a lens")
            assertEquals(listOf("diskread", "diskwrite"), opened.chart.keys)
            assertEquals("Disk IO", opened.chart.title)
            assertEquals(LensSubject.NodeSubject("node-a"), opened.subject)
            assertNull(opened.chart.live)
        } finally { scene.close() }
    }

    private fun centerDark(scene: ImageComposeScene, timeMs: Long): Int = scene.render(timeMs * 1_000_000L).use { image ->
        org.jetbrains.skia.Bitmap.makeFromImage(image).use { bitmap ->
            var n = 0
            val x0 = image.width / 2 - 40
            val y0 = image.height / 2 - 40
            for (y in y0 until y0 + 80) for (x in x0 until x0 + 80) {
                if ((bitmap.getColor(x, y) shr 16 and 255) < 48) n++
            }
            n
        }
    }
}
