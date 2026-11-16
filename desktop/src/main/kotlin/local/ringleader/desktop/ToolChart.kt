package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

data class ToolChartSample(val series: String, val x: Double, val y: Double, val unit: String)

/** What a tool's samples say at a glance. [jitter] is the mean change between neighbouring samples; [lost] counts missing sequence numbers. */
internal data class ToolStats(val count: Int, val min: Double, val p50: Double, val p95: Double, val max: Double, val avg: Double, val jitter: Double?, val lost: Int)

/** Linear-interpolated percentile of [sorted] values; [p] in 0..1. */
internal fun percentileOf(sorted: List<Double>, p: Double): Double {
    if (sorted.isEmpty()) return Double.NaN
    if (sorted.size == 1) return sorted[0]
    val at = p.coerceIn(0.0, 1.0) * (sorted.size - 1)
    val lo = floor(at).toInt(); val hi = (lo + 1).coerceAtMost(sorted.lastIndex)
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (at - lo)
}

/**
 * Sequence numbers missing between the first and last of [xs], when every x is a whole number (ping replies, by
 * sequence). Time-based samples (fractional x) have no notion of a lost one and return nothing.
 */
internal fun toolLosses(xs: List<Double>): List<Double> {
    if (xs.size < 2 || xs.any { !it.isFinite() || it != floor(it) }) return emptyList()
    val seen = xs.map { it.toLong() }.toSortedSet()
    val first = seen.first(); val last = seen.last()
    if (last - first > 100_000) return emptyList()
    return (first..last).filter { it !in seen }.map { it.toDouble() }
}

internal fun toolStats(points: List<ToolChartSample>): ToolStats? {
    val ys = points.map { it.y }.filter { it.isFinite() }
    if (ys.isEmpty()) return null
    val sorted = ys.sorted()
    val ordered = points.filter { it.y.isFinite() }.sortedBy { it.x }.map { it.y }
    val jitter = if (ordered.size > 1) ordered.zipWithNext { a, b -> abs(b - a) }.average() else null
    return ToolStats(ys.size, sorted.first(), percentileOf(sorted, .5), percentileOf(sorted, .95), sorted.last(), ys.average(), jitter, toolLosses(points.map { it.x }).size)
}

/** Bucket counts of [values] over 0..[top] in [bins] equal bins (values above [top] land in the last). */
internal fun toolHistogram(values: List<Double>, top: Double, bins: Int): IntArray {
    val out = IntArray(bins.coerceAtLeast(1))
    if (!(top > 0.0)) return out
    values.forEach { v -> if (v.isFinite() && v >= 0.0) out[((v / top) * out.size).toInt().coerceIn(0, out.lastIndex)]++ }
    return out
}

/**
 * Fixed-height sample chart. The box stays in place whether or not samples have arrived.
 *
 * A Y axis on round values with the tool's unit; each sample is a point on the line when there is room for one (a ping
 * reply is a discrete measurement); missing sequence numbers are red marks on the floor. A single latency series (unit
 * "ms") also gets its distribution on the right, on the same scale, with p50 and p95 ticks: jitter and outliers read
 * as the spread they are.
 */
@Composable fun ToolChart(samples: List<ToolChartSample>, modifier: Modifier = Modifier) {
    val unit = samples.firstOrNull { it.unit.isNotBlank() }?.unit.orEmpty()
    val shown = if (unit.isEmpty()) samples else samples.filter { it.unit == unit }
    val names = shown.map { it.series }.distinct()
    val colors = listOf(MetricColors.primary, MetricColors.secondary, Tech.Muted)
    val bySeries = remember(shown) { names.map { name -> shown.filter { it.series == name }.sortedBy { it.x } } }
    val stats = remember(shown) { bySeries.map { toolStats(it) } }
    val latency = unit.equals("ms", ignoreCase = true) && names.size == 1
    val losses = remember(shown) { if (names.size == 1) toolLosses(shown.map { it.x }) else emptyList() }
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val axisColor = MetricColors.axis; val grid = MetricColors.grid; val faint = MetricColors.gridFaint; val red = Tech.Red; val ink = Tech.Ink
    Column(modifier) {
        // The caption grows with the text size; a fixed 18 units let the chart overdraw it at 150 % text.
        Row(Modifier.fillMaxWidth().heightIn(min = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (unit.isBlank()) "SAMPLES" else unit.uppercase(), fontFamily = Tech.Mono, fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 1.sp, color = Tech.Muted)
            names.forEachIndexed { index, name ->
                val s = stats[index]
                Text(name, color = colors[index % colors.size], fontFamily = Tech.Mono, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1)
                if (s != null) Text(
                    if (latency) "p50 ${chartNumber(s.p50)} · p95 ${chartNumber(s.p95)} · max ${chartNumber(s.max)} · jitter ${s.jitter?.let(::chartNumber) ?: "—"}"
                    else "min ${chartNumber(s.min)} · avg ${chartNumber(s.avg)} · max ${chartNumber(s.max)}",
                    color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (losses.isNotEmpty()) Text("${losses.size} lost", color = Tech.Red, fontFamily = Tech.Mono, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1)
        }
        val peak = shown.maxOfOrNull { it.y }?.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        // Round ticks give the headroom: a 950 Mbit/s line sits under a 1000 tick, not under 1500.
        val scale = remember(peak) { axisScale(AxisKind.Count, peak, maxTicks = 4, headroom = 1.02) }
        val minX = shown.minOfOrNull { it.x } ?: 0.0
        val span = ((shown.maxOfOrNull { it.x } ?: minX) - minX).takeIf { it > 0.0 } ?: 1.0
        val description = stats.firstOrNull()?.let { s -> "Samples in $unit: ${s.count}, minimum ${chartNumber(s.min)}, median ${chartNumber(s.p50)}, maximum ${chartNumber(s.max)}" + if (losses.isNotEmpty()) ", ${losses.size} lost" else "" } ?: "No samples yet"
        Box(Modifier.fillMaxWidth().weight(1f).background(Tech.Black.copy(alpha = .35f)).semantics { contentDescription = description }.drawWithCache {
            val labelStyle = TextStyle(fontSize = 9.sp, fontFamily = Tech.Mono, color = axisColor)
            val gutter = if (shown.isEmpty()) 0f else 40.dp.toPx()
            val distW = if (latency && shown.size >= 8) 56.dp.toPx() else 0f
            val right = size.width - distW
            val pad = 6.dp.toPx()
            val plotH = size.height - pad
            fun px(x: Double) = gutter + ((x - minX) / span * (right - gutter - 4.dp.toPx())).toFloat()
            fun py(y: Double) = (pad + (plotH - pad) * (1.0 - (y / scale.top).coerceIn(0.0, 1.0))).toFloat()
            // The top tick carries the unit when that fits the gutter ("8 ms"); a longer one ("1000 Mbit/s") stays in the caption above.
            val labels = if (shown.isEmpty()) emptyList() else scale.ticks.map { v ->
                val plain = measurer.measure(scale.label(v), labelStyle)
                v to if (v == scale.top && unit.isNotBlank()) measurer.measure(scale.label(v) + " $unit", labelStyle).takeIf { it.size.width <= gutter - 4.dp.toPx() } ?: plain else plain
            }
            val lines = bySeries.mapIndexed { index, points ->
                val color = colors[index % colors.size]
                if (points.size < 2) return@mapIndexed Triple(color, null as Path?, points)
                val line = Path().apply {
                    moveTo(px(points[0].x), py(points[0].y))
                    points.zipWithNext().forEach { (a, b) -> val mid = (px(a.x) + px(b.x)) / 2f; cubicTo(mid, py(a.y), mid, py(b.y), px(b.x), py(b.y)) }
                }
                Triple(color, line, points)
            }
            val areas = lines.map { (color, line, points) -> line?.let { l -> Path().apply { addPath(l); lineTo(px(points.last().x), plotH); lineTo(px(points.first().x), plotH); close() } to Brush.verticalGradient(listOf(color.copy(alpha = .24f), color.copy(alpha = .02f)), startY = 0f, endY = plotH) } }
            val dotted = (right - gutter) / (shown.size.coerceAtLeast(1)) >= 6.dp.toPx()
            val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val dot = 1.6.dp.toPx(); val cross = 3.dp.toPx()
            val values = bySeries.firstOrNull()?.map { it.y }.orEmpty()
            val bins = 14
            val hist = if (distW > 0f) toolHistogram(values, scale.top, bins) else IntArray(0)
            val histMax = hist.maxOrNull()?.coerceAtLeast(1) ?: 1
            val sorted = values.filter { it.isFinite() }.sorted()
            val p50 = if (distW > 0f) percentileOf(sorted, .5) else Double.NaN
            val p95 = if (distW > 0f) percentileOf(sorted, .95) else Double.NaN
            val p50Label = if (distW > 0f) measurer.measure("p50", TextStyle(fontSize = 8.sp, fontFamily = Tech.Mono, color = ink)) else null
            val p95Label = if (distW > 0f) measurer.measure("p95", TextStyle(fontSize = 8.sp, fontFamily = Tech.Mono, color = ink)) else null
            onDrawBehind {
                if (shown.isEmpty()) {
                    repeat(4) { n -> val y = size.height * n / 3f; drawLine(faint, Offset(0f, y), Offset(size.width, y), 1f) }
                    return@onDrawBehind
                }
                labels.forEach { (v, layout) ->
                    val y = py(v)
                    drawLine(if (v == 0.0) grid else faint, Offset(gutter, y), Offset(right, y), 1f)
                    drawText(layout, topLeft = Offset((gutter - 4.dp.toPx() - layout.size.width).coerceAtLeast(0f), (y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)))
                }
                clipRect(gutter, 0f, right, size.height) {
                    areas.forEach { it?.let { (path, brush) -> drawPath(path, brush) } }
                    lines.forEach { (color, line, points) ->
                        if (line != null) drawPath(line, color, style = stroke)
                        if (dotted || points.size == 1) points.forEach { p -> drawCircle(color, if (points.size == 1) 2.5.dp.toPx() else dot, Offset(px(p.x), py(p.y))) }
                    }
                }
                // A lost reply: a red cross on the floor at its sequence number.
                losses.forEach { x -> val cx = px(x); val cy = plotH - cross - 1f
                    drawLine(red, Offset(cx - cross, cy - cross), Offset(cx + cross, cy + cross), 1.5f); drawLine(red, Offset(cx + cross, cy - cross), Offset(cx - cross, cy + cross), 1.5f) }
                if (distW > 0f) {
                    val x0 = right + 6.dp.toPx(); val wMax = distW - 14.dp.toPx()
                    drawLine(grid, Offset(right + 2.dp.toPx(), 0f), Offset(right + 2.dp.toPx(), size.height), 1f)
                    val binH = (plotH - pad) / bins
                    hist.forEachIndexed { i, n -> if (n > 0) {
                        val top = py(scale.top * (i + 1) / bins)
                        // A bin with one outlier still gets a bar wide enough to see.
                        drawRect(MetricColors.primary.copy(alpha = .75f), Offset(x0, top + 1f), Size((wMax * n / histMax).coerceAtLeast(3.dp.toPx()), binH - 2f))
                    } }
                    listOf(p50 to p50Label, p95 to p95Label).forEach { (v, layout) -> if (v.isFinite() && layout != null) {
                        val y = py(v)
                        drawLine(ink, Offset(x0 - 3.dp.toPx(), y), Offset(size.width - 2.dp.toPx(), y), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 2f)))
                        drawText(layout, topLeft = Offset(size.width - layout.size.width - 1.dp.toPx(), (y - layout.size.height).coerceAtLeast(0f)))
                    } }
                }
            }
        }) {
            if (shown.isEmpty()) Text("No samples yet", Modifier.align(Alignment.Center), color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 11.sp)
        }
    }
}

private fun chartNumber(value: Double): String = when {
    !value.isFinite() -> "—"
    value == kotlin.math.floor(value) && kotlin.math.abs(value) < 100000 -> String.format(Locale.ROOT, "%.0f", value)
    kotlin.math.abs(value) >= 10 -> String.format(Locale.ROOT, "%.1f", value)
    else -> String.format(Locale.ROOT, "%.2f", value)
}
