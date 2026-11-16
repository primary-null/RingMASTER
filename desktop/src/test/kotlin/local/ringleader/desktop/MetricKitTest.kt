@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ringleader.core.HistoryPoint
import local.ringleader.core.Sample
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The metric kit's rules: scales, ticks, units, tones, gaps, resets, slots, tool statistics, readout placement, keys and widths. */
class MetricKitTest {
    private val utc = ZoneOffset.UTC

    @Test fun niceNumbersAndTicks() {
        // value, round -> nice
        for ((x, round, nice) in listOf(Triple(0.0, false, 1.0), Triple(-3.0, true, 1.0), Triple(Double.NaN, false, 1.0), Triple(1.0, false, 1.0), Triple(1.2, false, 2.0),
            Triple(2.2, false, 2.5), Triple(3.0, false, 5.0), Triple(7.3, false, 10.0), Triple(1.2, true, 1.0), Triple(2.0, true, 2.0), Triple(3.0, true, 2.5), Triple(8.0, true, 10.0),
            Triple(0.0034, false, 0.005), Triple(4.2e12, false, 5e12))) assertEquals(nice, niceNumber(x, round), 1e-9 * nice, "niceNumber($x, $round)")
        assertEquals(2.0 to listOf(0.0, 2.0, 4.0, 6.0, 8.0), niceTicks(7.3, 4))
        assertEquals(listOf(0.0, .25, .5, .75, 1.0), niceTicks(0.0).second, "an empty range still has a scale")
        assertEquals(listOf(0.0, 0.25), niceTicks(0.25, 1).second)
        for ((step, places) in listOf(5.0 to 0, 250.0 to 0, 2.5 to 1, 0.5 to 1, 0.25 to 2, 0.02 to 2, 0.001 to 2, Double.NaN to 0)) assertEquals(places, decimalsFor(step), "decimalsFor($step)")
    }

    @Test fun percentAxesStartAtZeroAndNeverPassAHundred() {
        // peak -> top
        for ((peak, top) in listOf(0.0 to .05, -1.0 to .05, Double.NaN to .05, .03 to .05, .049 to .10, .2 to .25, .45 to .5, .5 to 1.0, .97 to 1.0, 1.4 to 1.0)) {
            val s = axisScale(AxisKind.Fraction, peak)
            assertEquals(top, s.top, 1e-12, "Fraction top for $peak")
            assertEquals(0.0, s.ticks.first())
            assertEquals(top, s.ticks.last(), 1e-12)
        }
        assertEquals(listOf("0%", "25%", "50%", "75%", "100%"), axisScale(AxisKind.Fraction, .9).let { s -> s.ticks.map(s::label) })
        assertEquals(listOf("0%", "1%", "2%", "3%", "4%", "5%"), axisScale(AxisKind.Fraction, .01).let { s -> s.ticks.map(s::label) })
        assertEquals(25.0, axisScale(AxisKind.Percent, 22.0).top, 1e-9)
        assertEquals("25%", axisScale(AxisKind.Percent, 22.0).let { it.label(it.top) })
    }

    @Test fun byteAndRateAxesPickTheirUnitFromTheData() {
        val idle = axisScale(AxisKind.Rate, 0.0)
        assertEquals(listOf("0.0 KiB/s", "0.5 KiB/s", "1.0 KiB/s"), idle.ticks.map(idle::label))
        val small = axisScale(AxisKind.Rate, 1000.0)
        assertEquals("KiB/s", small.unit); assertEquals("1.5 KiB/s", small.label(small.top))
        val mid = axisScale(AxisKind.Rate, 2.4 * 1048576)
        assertEquals("MiB/s", mid.unit); assertEquals(listOf("0 MiB/s", "1 MiB/s", "2 MiB/s", "3 MiB/s"), mid.ticks.map(mid::label))
        val fine = axisScale(AxisKind.Rate, 1.9 * 1048576)
        assertEquals(listOf("0.0 MiB/s", "0.5 MiB/s", "1.0 MiB/s", "1.5 MiB/s", "2.0 MiB/s"), fine.ticks.map(fine::label))
        // Just under the next unit: 1000 GiB stays GiB, 1020 GiB moves to TiB rather than reading "1250 GiB".
        assertEquals("GiB", axisScale(AxisKind.Bytes, 900.0 * 1073741824).unit)
        assertEquals("TiB", axisScale(AxisKind.Bytes, 1020.0 * 1073741824).unit)
        val huge = axisScale(AxisKind.Bytes, 3e18)
        assertEquals("PiB", huge.unit); assertTrue(huge.top >= 3e18)
        val bytes = axisScale(AxisKind.Bytes, 700.0)
        assertEquals("B", bytes.unit); assertEquals("800 B", bytes.label(bytes.top))
        // A constant series still sits below the top, never on it.
        val constant = axisScale(AxisKind.Rate, 2.0 * 1048576)
        assertTrue(constant.top > 2.0 * 1048576)
        val load = axisScale(AxisKind.Count, 38.0)
        assertEquals(listOf(0.0, 10.0, 20.0, 30.0, 40.0), load.ticks)
        assertEquals(listOf("0.0", "0.5", "1.0", "1.5"), axisScale(AxisKind.Count, 1.0, maxTicks = 4).let { s -> s.ticks.map(s::label) })
    }

    @Test fun historyKeysMapToAxisKinds() {
        assertEquals(AxisKind.Fraction, historyAxisKind("cpu")); assertEquals(AxisKind.Fraction, historyAxisKind("iowait"))
        assertEquals(AxisKind.Percent, historyAxisKind("pressureiosome")); assertEquals(AxisKind.Count, historyAxisKind("loadavg"))
        assertEquals(AxisKind.Rate, historyAxisKind("netin")); assertEquals(AxisKind.Rate, historyAxisKind("diskwrite"))
        assertEquals(AxisKind.Bytes, historyAxisKind("memused")); assertEquals(AxisKind.Bytes, historyAxisKind("total"))
    }

    private fun at(text: String) = Instant.parse(text).epochSecond.toDouble()

    @Test fun timeTicksFallOnRoundLocalTimes() {
        // An hour: quarter hours.
        assertEquals(listOf("13:15", "13:30", "13:45", "14:00", "14:15"), timeTicks(at("2026-11-16T13:11:00Z"), at("2026-11-16T14:20:00Z"), 6, utc).map { it.label })
        // A day: three hours, the date at midnight.
        val day = timeTicks(at("2026-11-15T15:20:00Z"), at("2026-11-16T14:20:00Z"), 8, utc).map { it.label }
        assertEquals(listOf("18:00", "21:00", "16 Nov", "03:00", "06:00", "09:00", "12:00"), day)
        // A week: days.
        assertEquals(listOf("10 Nov", "11 Nov", "12 Nov", "13 Nov", "14 Nov", "15 Nov", "16 Nov"), timeTicks(at("2026-11-09T16:44:00Z"), at("2026-11-16T14:20:00Z"), 8, utc).map { it.label })
        // A month: Mondays.
        val month = timeTicks(at("2026-10-18T00:00:00Z"), at("2026-11-16T14:20:00Z"), 6, utc)
        assertTrue(month.all { Instant.ofEpochSecond(it.time.toLong()).atZone(utc).dayOfWeek == java.time.DayOfWeek.MONDAY }, "$month")
        // A year: months.
        val year = timeTicks(at("2025-11-16T00:00:00Z"), at("2026-11-16T00:00:00Z"), 6, utc).map { it.label }
        assertEquals(listOf("Jan 26", "Mar 26", "May 26", "Jul 26", "Sep 26", "Nov 26"), year)
        // Two minutes of live samples: seconds.
        assertEquals(listOf("14:18:30", "14:19:00", "14:19:30", "14:20:00"), timeTicks(at("2026-11-16T14:18:05Z"), at("2026-11-16T14:20:00Z"), 5, utc).map { it.label })
        // Nothing to tick.
        assertEquals(emptyList(), timeTicks(10.0, 10.0, 5, utc)); assertEquals(emptyList(), timeTicks(20.0, 10.0, 5, utc)); assertEquals(emptyList(), timeTicks(0.0, 100.0, 0, utc))
        assertEquals(emptyList(), timeTicks(Double.NaN, 100.0, 4, utc))
        // Never more than asked for (plus the edges the loop allows).
        assertTrue(timeTicks(at("2026-11-16T13:11:00Z"), at("2026-11-16T14:20:00Z"), 3, utc).size <= 5)
    }

    @Test fun unitsAreSplitOffTheValue() {
        assertEquals("61" to "%", splitUnit("61%"))
        assertEquals("3.70" to " MiB/s", splitUnit("3.70 MiB/s"))
        assertEquals("1284" to "/s", splitUnit("1284/s"))
        assertEquals("—" to "", splitUnit("—"))
        assertEquals("no sample" to "", splitUnit("no sample"))
        assertEquals("-" to "", splitUnit("-"))
        assertEquals("23" to "d 4h", splitUnit("23d 4h"))
        // The text a screen reader gets is unchanged.
        assertEquals("61%", metricText("61%").text)
        assertEquals(1, metricText("61%").spanStyles.size)
        assertEquals(0, metricText("—").spanStyles.size)
        // Later numbers keep the full size: only "d " and "h" are set smaller, and only "/" and "G".
        assertEquals(listOf(2 to 4, 5 to 6), metricText("23d 4h").spanStyles.map { it.start to it.end })
        assertEquals(listOf(2 to 3, 5 to 6), metricText("15/16G").spanStyles.map { it.start to it.end })
        assertEquals(listOf(4 to 10), metricText("3.70 MiB/s").spanStyles.map { it.start to it.end })
        assertEquals("23d 4h", metricText("23d 4h").text)
        // The unit is .72 of a large value and never under 9 sp of a small one.
        assertEquals(.72f, unitScaleFor(28.sp)); assertEquals(.72f, unitScaleFor(16.sp)); assertEquals(.9f, unitScaleFor(10.sp)); assertEquals(1f, unitScaleFor(8.sp))
        assertEquals(0, metricText("61%", unitScaleFor(8.sp)).spanStyles.size)
    }

    @Test fun categoricalColoursAreNotTonesAndReadInEveryTheme() {
        for (theme in listOf(ThemeMode.Dark, ThemeMode.Light, ThemeMode.PitchBlack, ThemeMode.HighContrast)) {
            Appearance.preview(theme)
            val set = MetricColors.categorical
            assertEquals(5, set.distinct().size, "$theme")
            assertEquals(MetricColors.primary, set[0]); assertEquals(MetricColors.secondary, set[1])
            for (c in set) {
                assertTrue(c != Tech.Green && c != Tech.Red, "$theme: a part must not wear a tone")
                assertTrue(contrastRatio(c, Appearance.palette.panel) >= 3f, "$theme: $c contrast ${contrastRatio(c, Appearance.palette.panel)}")
            }
            assertEquals(set[0], MetricColors.category(5)); assertEquals(set[4], MetricColors.category(-1))
        }
        Appearance.preview(ThemeMode.Dark)
    }

    @Test fun gaugePeakIsMarkedOnlyClearOfTheValue() {
        assertEquals(.72, gaugePeak(.61, .26..(.72)))
        assertNull(gaugePeak(.83, .80..(.84)), "a peak at the needle is the needle")
        assertNull(gaugePeak(1.0, .5..1.0)); assertNull(gaugePeak(null, .1..(.9))); assertNull(gaugePeak(.5, null))
    }

    @Test fun tonesFollowTheTwoThresholdSets() {
        Appearance.preview(ThemeMode.Dark)
        val load = MetricThresholds.Load
        assertEquals(MetricTone.Good, load.tone(.499)); assertEquals(MetricTone.Attention, load.tone(.5)); assertEquals(MetricTone.Attention, load.tone(.85)); assertEquals(MetricTone.Critical, load.tone(.851))
        // Load is the long-standing ramp, colour for colour.
        for (v in listOf(0.0, .3, .499, .5, .7, .85, .851, 1.0)) assertEquals(ramp(v), load.tone(v).color, "ramp($v)")
        val cap = MetricThresholds.Capacity
        assertEquals(MetricTone.Good, cap.tone(.849)); assertEquals(MetricTone.Attention, cap.tone(.85)); assertEquals(MetricTone.Attention, cap.tone(.949)); assertEquals(MetricTone.Critical, cap.tone(.95))
        for (v in listOf(null, Double.NaN, -.1)) assertEquals(MetricTone.Neutral, load.tone(v))
        assertEquals(Tech.Muted, MetricTone.Neutral.color)
        // The second series colour is blue in every theme: never the green "good" tone.
        for (theme in listOf(ThemeMode.Dark, ThemeMode.Light, ThemeMode.PitchBlack, ThemeMode.HighContrast)) {
            Appearance.preview(theme)
            val c = MetricColors.secondary
            assertTrue(c.blue > c.green && c.blue > c.red, "$theme: $c")
            assertTrue(contrastRatio(c, Appearance.palette.panel) >= 3f, "$theme: series colour contrast ${contrastRatio(c, Appearance.palette.panel)}")
        }
        Appearance.preview(ThemeMode.Dark)
    }

    @Test fun gapsResetsAndSlots() {
        assertEquals(listOf(1..2, 4..4), sparkGaps(listOf(1.0, null, Double.NaN, 2.0, -1.0)))
        assertEquals(listOf(0..2), sparkGaps(listOf(null, null, null)))
        assertEquals(emptyList(), sparkGaps(emptyList()))
        assertEquals(emptyList(), sparkGaps(listOf(.1, .2)))
        fun s(cpu: Double?, net: Double?) = Sample(Instant.EPOCH, cpu, null, null, net, net)
        // A reset has CPU but no rates; a missing sample has neither; the first sample has no delta at all.
        assertEquals(listOf(2), netResets(listOf(s(.1, null), s(.1, 5.0), s(.1, null), s(null, null), s(.1, 3.0))))
        // Two samples in thirty slots sit at the right edge.
        assertEquals(1, sparkSlotIndex(290f, 290f, 2, 30))
        assertEquals(0, sparkSlotIndex(280f, 290f, 2, 30))
        assertNull(sparkSlotIndex(0f, 290f, 2, 30), "the empty part has no sample")
        assertEquals(2, sparkSlotIndex(50f, 100f, 5, 0), "no slots: the plain nearest sample")
        assertNull(sparkSlotIndex(10f, 0f, 3, 30))
        assertEquals(.1..1.0, gaugeRange(listOf(.1, null, Double.NaN, -2.0, 1.3)))
        assertNull(gaugeRange(listOf(null, Double.NaN)))
        assertEquals("3–61", rangeText(listOf(.031, null, .61, .2), ::wholePercent))
        assertNull(rangeText(emptyList(), ::wholePercent))
        assertEquals(.61, peakOf(listOf(.031, null, .61, Double.NaN)))
    }

    @Test fun shortPlotsThinTheirLabels() {
        assertEquals(1, historyTickStride(20f, 14f)); assertEquals(2, historyTickStride(10f, 14f)); assertEquals(3, historyTickStride(5f, 14f))
        assertEquals(1, historyTickStride(0f, 14f)); assertEquals(1, historyTickStride(10f, Float.NaN))
    }

    @Test fun toolSamplesBecomeDistributions() {
        assertEquals(2.0, percentileOf(listOf(1.0, 2.0, 3.0), .5)); assertEquals(2.9, percentileOf(listOf(1.0, 2.0, 3.0), .95), 1e-9)
        assertEquals(4.0, percentileOf(listOf(4.0), .95)); assertTrue(percentileOf(emptyList(), .5).isNaN())
        assertEquals(listOf(3.0, 5.0), toolLosses(listOf(1.0, 2.0, 4.0, 6.0)))
        assertEquals(emptyList(), toolLosses(listOf(0.5, 1.5, 3.5)), "time-based samples have no lost replies")
        assertEquals(emptyList(), toolLosses(listOf(1.0)))
        val stats = toolStats(listOf(1.0, 3.0, 2.0, 6.0).mapIndexed { i, y -> ToolChartSample("ping", if (i == 3) 5.0 else i.toDouble(), y, "ms") })!!
        assertEquals(1.0, stats.min); assertEquals(6.0, stats.max); assertEquals(2.5, stats.p50); assertEquals(2, stats.lost)
        assertEquals((2.0 + 1.0 + 4.0) / 3, stats.jitter!!, 1e-9, "jitter is the mean change between neighbours, in x order")
        assertNull(toolStats(emptyList()))
        assertEquals(listOf(1, 1, 0, 2), toolHistogram(listOf(.1, .3, .9, 5.0, Double.NaN, -1.0), 1.0, 4).toList(), "above the top lands in the last bin")
    }

    @Test fun readoutsStayInsideTheWindowAtTheEdges() {
        val window = IntSize(800, 600)
        val bubble = IntSize(160, 24)
        // Near the right edge the bubble sits left of the pointer.
        val right = hoverBubblePosition(390f, IntRect(400, 300, 800, 340), bubble, window)
        assertTrue(right.x + bubble.width <= 800 && right.x < 400 + 390, "$right")
        // Near the left edge it sits right of the pointer, never off the window.
        val left = hoverBubblePosition(2f, IntRect(0, 300, 400, 340), bubble, window)
        assertTrue(left.x >= 0 && left.x > 2, "$left")
        // Not enough room above: below the anchor.
        val top = hoverBubblePosition(100f, IntRect(0, 4, 400, 40), bubble, window)
        assertEquals(40, top.y)
        // A bubble wider than the window is pinned to its left edge.
        assertEquals(0, hoverBubblePosition(100f, IntRect(0, 300, 400, 340), IntSize(900, 24), window).x)
    }

    @Test fun keysStepTheSharedCursorThroughSamples() = runSceneTest {
        val points = (0 until 6).map { HistoryPoint(1_000.0 + it * 60, mapOf("cpu" to .1 * it)) }
        val shared = HistoryInteraction()
        val scene = ImageComposeScene(640, 320, Density(1f), coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides true) { HistoryChartPanel("CPU", points, listOf("cpu"), "none", 160.dp, shared) }
            }
        }
        try {
            var t = 0L
            suspend fun frame() { t += 16_000_000L; scene.render(t).close(); kotlinx.coroutines.yield() }
            suspend fun key(k: Key) { scene.sendKeyEvent(KeyEvent(k, KeyEventType.KeyDown)); scene.sendKeyEvent(KeyEvent(k, KeyEventType.KeyUp)); frame() }
            repeat(3) { frame() }
            // Tab reaches the plot (the legend chips and the plot are the focus stops); focus lands on the newest sample.
            repeat(4) { if (shared.instant == null) key(Key.Tab) }
            assertEquals(points.last().time, shared.instant, "focus should put the cursor on the newest sample")
            key(Key.DirectionLeft); assertEquals(points[4].time, shared.instant)
            key(Key.MoveHome); assertEquals(points[0].time, shared.instant)
            key(Key.DirectionLeft); assertEquals(points[0].time, shared.instant, "the first sample is the floor")
            key(Key.DirectionRight); assertEquals(points[1].time, shared.instant)
            key(Key.MoveEnd); assertEquals(points[5].time, shared.instant)
            key(Key.Escape); assertNull(shared.instant)
        } finally { scene.close() }
    }

    @Test fun digitsAreTabularSoValuesDoNotJumpWidth() {
        val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
        val style = TextStyle(fontSize = 15.sp, fontFamily = Tech.Mono)
        fun width(text: String) = measurer.measure(metricText(text), style).size.width
        assertEquals(width("11%"), width("88%"))
        assertEquals(width("1.0%"), width("9.9%"))
        assertEquals(width("100%"), width("888%"))
        assertEquals(width("1.11 MiB/s"), width("9.99 MiB/s"))
        // The unit is set smaller than the value.
        assertTrue(width("88%") < measurer.measure("88%", style).size.width)
    }
}
