package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import local.ringleader.core.Sample
import local.ringleader.core.Series

/** Hover readouts on sparklines and gauges. Pointer moves must not rebuild the line. */
class ChartHoverTest {
    private val zone = ZoneId.of("UTC")
    private val at = Instant.parse("2026-11-11T14:03:25Z")

    private fun sample(cpu: Double? = 0.42, mem: Long? = null, memMax: Long? = null, netIn: Double? = null, netOut: Double? = null, held: Boolean = false) =
        Sample(at, cpu, mem, memMax, netIn, netOut, held)

    @Test fun nearestSampleIgnoresTheSlide() {
        assertNull(sparkNearestIndex(10f, 100f, 0))
        assertNull(sparkNearestIndex(10f, 0f, 4))
        assertNull(sparkNearestIndex(Float.NaN, 100f, 4))
        assertEquals(0, sparkNearestIndex(0f, 100f, 1))
        assertEquals(0, sparkNearestIndex(80f, 100f, 1))
        assertEquals(0, sparkNearestIndex(-10f, 100f, 5))
        assertEquals(2, sparkNearestIndex(50f, 100f, 5))
        assertEquals(4, sparkNearestIndex(1000f, 100f, 5))
    }

    @Test fun readoutTextNamesTheSample() {
        assertEquals("42.0 %", sparkCpuValue(sample()))
        assertEquals("no sample", sparkCpuValue(sample(cpu = null)))
        assertEquals("no sample", sparkCpuValue(sample(cpu = Double.NaN)))
        val cap = 4L * 1073741824
        val mem = (cap * 0.61).toLong()
        val memory = sparkMemoryValue(sample(mem = mem, memMax = cap))
        assertTrue(memory.startsWith("61 % · "), memory)
        assertTrue(memory.endsWith(" GiB"), memory)
        assertEquals("no sample", sparkMemoryValue(sample(mem = null, memMax = null)))
        assertEquals("IN 1.20 MiB/s · OUT 310.0 KiB/s", sparkNetValue(sample(netIn = 1.2 * 1048576, netOut = 310.0 * 1024)))
        assertEquals("no sample", sparkNetValue(sample(netIn = null, netOut = null)))
        assertEquals("42.0 % (no change) · 14:03:25", sparkReadoutLine("42.0 %", true, "14:03:25"))
        assertEquals("no sample · 14:03:25", sparkReadoutLine("no sample", true, "14:03:25"))
        val longMemory = "61 % · 2.4 / 4.0 GiB"
        assertEquals("$longMemory · 14:03:25", sparkReadoutLine(longMemory, true, "14:03:25"))
        assertEquals("14:03:25", sparkClock(listOf(at), 0, zone))
        val overnight = listOf(Instant.parse("2026-11-11T23:00:00Z"), Instant.parse("2026-11-12T01:00:00Z"))
        assertEquals("2026-11-12 01:00:00", sparkClock(overnight, 1, zone))
        assertNull(sparkClock(null, 0, zone))
    }

    @Test fun gaugeHoverNamesTheExactValueAndTheSamples() {
        assertEquals("42.0 % · 2 vCPU", gaugeHoverText(0.42, "2 vCPU", null) { it.cpu })
        assertEquals("—", gaugeHoverText(null, null, null) { it.cpu })
        val series = Series(listOf(sample(0.03), sample(0.182), sample(0.61)))
        assertEquals("42.0 % · 2 vCPU\nLast 3 samples: min 3.0 % · avg 27.4 % · max 61.0 %", gaugeHoverText(0.42, "2 vCPU", series) { it.cpu })
        assertNull(gaugeStatsLine(Series(), { it.cpu }))
    }

    @Test fun movingThePointerShowsTheBubbleAndLeavingClearsIt() {
        val scene = ImageComposeScene(320, 80, Density(1f)) {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                Sparkline(listOf(0.1, 0.42, 0.2, 0.3, 0.5), Modifier.fillMaxSize().background(Color.Black), color = Color(0xFF1B5E20), pulse = false,
                    times = List(5) { at }, label = { "42.0 %" })
            }
        }
        try {
            var now = 0L
            fun light(): Int {
                now += 16
                return scene.render(now * 1_000_000L).use { image ->
                    org.jetbrains.skia.Bitmap.makeFromImage(image).use { bitmap ->
                        var n = 0
                        for (y in 0 until image.height) for (x in 0 until image.width) {
                            val c = bitmap.getColor(x, y)
                            if (minOf(c shr 16 and 255, c shr 8 and 255, c and 255) > 180) n++
                        }
                        n
                    }
                }
            }
            repeat(4) { light() }
            val before = light()
            val at = Offset(160f, 40f)
            scene.sendPointerEvent(PointerEventType.Enter, at)
            scene.sendPointerEvent(PointerEventType.Move, at)
            val shown = List(4) { light() }.last()
            assertTrue(shown > before + 15, "the readout should add light text pixels (was $before, then $shown)")
            scene.sendPointerEvent(PointerEventType.Exit, at)
            val gone = List(3) { light() }.last()
            assertTrue(gone < shown / 2, "leaving should clear the readout (shown $shown, left $gone)")
        } finally { scene.close() }
    }

    @Test fun pointerMovesDoNotRecomposeTheSparklineBody() {
        val ticks = mutableIntStateOf(0)
        val scene = ImageComposeScene(300, 80, Density(1f)) {
            CompositionLocalProvider(LocalReduceMotion provides true, LocalSparkBodyCompositions provides ticks) {
                Sparkline(List(24) { 0.2 }, Modifier.fillMaxSize().background(Color.Black), color = Color(0xFF1B5E20), pulse = false, label = { "20.0 %" })
            }
        }
        try {
            var now = 0L
            fun frame() { now += 16; scene.render(now * 1_000_000L).close() }
            repeat(6) { frame() }
            val settled = ticks.intValue
            repeat(8) { i ->
                scene.sendPointerEvent(PointerEventType.Move, Offset(20f + i * 30f, 40f))
                frame()
            }
            assertEquals(settled, ticks.intValue, "a pointer move rebuilt the sparkline body")
        } finally { scene.close() }
    }

    @Test fun aClickOnTheSparklineStillSelectsTheRow() {
        var clicks = 0
        val scene = ImageComposeScene(240, 36, Density(1f)) {
            val interaction = remember { MutableInteractionSource() }
            Row(Modifier.fillMaxSize().clickable(interactionSource = interaction, indication = null) { clicks++ }) {
                Sparkline(List(12) { 0.25 }, Modifier.width(120.dp).height(18.dp), color = Color(0xFF1B5E20), pulse = false, label = { "25.0 %" })
            }
        }
        try {
            var now = 0L
            fun frame() { now += 16; scene.render(now * 1_000_000L).close() }
            repeat(3) { frame() }
            val at = Offset(40f, 18f)
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at)
            scene.sendPointerEvent(PointerEventType.Release, at)
            frame()
            assertEquals(1, clicks)
        } finally { scene.close() }
    }
}
