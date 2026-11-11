package local.ringleader.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

data class ToolChartSample(val series: String, val x: Double, val y: Double, val unit: String)

/** Fixed-height sample chart. The box stays in place whether or not samples have arrived. */
@Composable fun ToolChart(samples: List<ToolChartSample>, modifier: Modifier = Modifier) {
    val unit = samples.firstOrNull { it.unit.isNotBlank() }?.unit.orEmpty()
    val shown = if (unit.isEmpty()) samples else samples.filter { it.unit == unit }
    val names = shown.map { it.series }.distinct()
    val colors = listOf(Tech.Amber, Tech.Green, Tech.Muted)
    val peak = shown.maxOfOrNull { it.y }?.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    val minX = shown.minOfOrNull { it.x } ?: 0.0
    val span = ((shown.maxOfOrNull { it.x } ?: minX) - minX).takeIf { it > 0.0 } ?: 1.0
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (unit.isBlank()) "SAMPLES" else unit.uppercase(), fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Muted)
            names.forEachIndexed { index, name ->
                val values = shown.filter { it.series == name }.map { it.y }
                Text(name, color = colors[index % colors.size], fontFamily = Tech.Mono, fontSize = 10.sp, maxLines = 1)
                if (values.isNotEmpty()) Text(
                    "min ${chartNumber(values.min())} · avg ${chartNumber(values.average())} · max ${chartNumber(values.max())}",
                    color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(Tech.Black.copy(alpha = .35f))) {
            Canvas(Modifier.fillMaxSize()) {
                val grid = Tech.Edge.copy(alpha = .45f)
                repeat(4) { n ->
                    val y = size.height * n / 3f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
                }
                names.forEachIndexed { index, name ->
                    val points = shown.filter { it.series == name }
                    if (points.isEmpty()) return@forEachIndexed
                    fun px(sample: ToolChartSample) = ((sample.x - minX) / span * size.width).toFloat()
                    fun py(sample: ToolChartSample) = (size.height * (1.0 - (sample.y / peak).coerceIn(0.0, 1.0))).toFloat()
                    val color = colors[index % colors.size]
                    if (points.size == 1) {
                        drawCircle(color, 2.5.dp.toPx(), Offset(px(points[0]), py(points[0])))
                        return@forEachIndexed
                    }
                    val line = Path().apply {
                        moveTo(px(points[0]), py(points[0]))
                        points.zipWithNext().forEach { (a, b) ->
                            val mid = (px(a) + px(b)) / 2f
                            cubicTo(mid, py(a), mid, py(b), px(b), py(b))
                        }
                    }
                    val area = Path().apply {
                        addPath(line)
                        lineTo(px(points.last()), size.height)
                        lineTo(px(points.first()), size.height)
                        close()
                    }
                    drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = .26f), color.copy(alpha = .02f))))
                    drawPath(line, color, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
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
