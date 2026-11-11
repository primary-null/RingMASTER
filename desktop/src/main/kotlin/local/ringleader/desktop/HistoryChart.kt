@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ringleader.core.*
import kotlin.math.pow
import java.time.*
import java.time.format.DateTimeFormatter

class HistoryInteraction {
    var instant by mutableStateOf<Double?>(null)
    var zoom by mutableStateOf<ClosedFloatingPointRange<Double>?>(null)
}
private val LocalHistoryInteraction = staticCompositionLocalOf { HistoryInteraction() }
private val LocalHistoryTimeframe = staticCompositionLocalOf { "hour" }
private object HistoryPreference { var timeframe = "hour"; var cf = "AVERAGE" }

@Composable fun HistoryGroup(state: WorkspaceState, workspace: Workspace, node: String? = null) {
    var timeframe by remember { mutableStateOf(HistoryPreference.timeframe) }
    var cf by remember { mutableStateOf(HistoryPreference.cf) }
    val interaction = remember(node, state.selected?.resource) { HistoryInteraction() }
    LaunchedEffect(timeframe, cf, node, state.selected?.resource) { interaction.zoom=null; workspace.inspectHistory(timeframe,cf,node) }
    val all = state.chartHistory.value.orEmpty()
    val points = all.filter { interaction.zoom?.contains(it.time) != false }
    val charts = if(node == null) listOf(("CPU / " + (all.lastOrNull { it.values["maxcpu"] != null }?.values?.get("maxcpu")?.toInt()?.toString() ?: "allocated") + " cores") to listOf("cpu"), "Memory" to listOf("mem","maxmem"), "Network" to listOf("netin","netout"), "Disk IO" to listOf("diskread","diskwrite")) else
        listOf("CPU / IO wait" to listOf("cpu","iowait"), "Server load" to listOf("loadavg"), "Memory" to listOf("memused","memtotal"), "Swap" to listOf("swapused","swaptotal"), "Network" to listOf("netin","netout")) +
        listOf("CPU pressure" to listOf("pressurecpusome"), "IO pressure" to listOf("pressureiosome","pressureiofull"), "Memory pressure" to listOf("pressurememorysome","pressurememoryfull")).filter { (_,keys) -> all.any { p -> keys.any { p.values[it] != null } } }
    val host = LocalAppHost.current
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        // Wraps in narrow places (the node inspector) instead of running off the edge.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalArrangement=Arrangement.spacedBy(6.dp), itemVerticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            HistorySegments(historyTimeframes.map { it to it.replaceFirstChar(Char::uppercase) }, timeframe) { timeframe = it; HistoryPreference.timeframe = it }
            HistorySegments(listOf("AVERAGE" to "Average", "MAX" to "Maximum"), cf) { cf = it; HistoryPreference.cf = it }
            if(interaction.zoom != null) MiniAction("Reset zoom") { interaction.zoom=null }
            if(!host.web) MiniAction("CSV") {
                val dialog=java.awt.FileDialog(null as java.awt.Frame?, "Export visible history",java.awt.FileDialog.SAVE)
                try { dialog.file="history.csv"; dialog.isVisible=true; dialog.file?.let { java.io.File(dialog.directory,it).writeText(historyCsv(points,charts.flatMap { it.second }.distinct())) } } finally { dialog.dispose() }
            }
        }
        state.chartHistory.error?.let { Text(it, color=Tech.Muted,fontFamily=Tech.Mono,fontSize=11.sp) }
        if(state.chartHistory.error == null) CompositionLocalProvider(LocalHistoryInteraction provides interaction, LocalHistoryTimeframe provides timeframe) {
            // Two charts per row on wide layouts, like the Proxmox graph panels; one per row when narrow.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val perRow = if (maxWidth >= 1000.dp) 2 else 1
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    charts.chunked(perRow).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { (title, keys) -> Box(Modifier.weight(1f)) { HistoryChart(title, points, keys, if (state.chartHistoryLoading) "Loading history\u2026" else "No samples in this window") } }
                            if (row.size < perRow) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** Joined segmented choice used for the timeframe and the consolidation. */
@Composable private fun HistorySegments(items: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.height(28.dp).border(1.dp, Tech.Edge, Tech.Control)) {
        items.forEachIndexed { i, (key, label) ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Tech.Edge))
            val active = key == selected
            Box(Modifier.fillMaxHeight().background(if (active) Tech.Amber.copy(alpha = .16f) else androidx.compose.ui.graphics.Color.Transparent)
                .clickable { onSelect(key) }.padding(horizontal = 10.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text(label, fontSize = 11.sp, fontFamily = Tech.Mono, color = if (active) Tech.Amber else Tech.Ink, maxLines = 1)
            }
        }
    }
}

/** Server RRD history chart: one or two series, gradient fill, grid, axis labels, stats, hover crosshair with readout. */
@Composable fun HistoryChart(title: String, points: List<HistoryPoint>, keys: List<String>, emptyNote: String = "No samples yet — new guests take a few minutes") {
    val shared = LocalHistoryInteraction.current
    val timeframe = LocalHistoryTimeframe.current
    var hidden by remember(keys) { mutableStateOf(emptySet<String>()) }
    val activeKeys = keys.filterNot { it in hidden }
    fun series(key: String) = when (key) { "cpu" -> "CPU"; "mem" -> "Memory"; "netin" -> "Inbound"; "netout" -> "Outbound"; "diskread" -> "Disk read"; "diskwrite" -> "Disk write"; "maxmem", "memtotal" -> "Total"; "memused" -> "Used"; "swapused" -> "Used"; "swaptotal" -> "Total"; "iowait" -> "IO wait"; "loadavg" -> "Load average"; "pressurecpusome", "pressureiosome", "pressurememorysome" -> "Some"; "pressureiofull", "pressurememoryfull" -> "Full"; else -> key }
    val colors = listOf(Tech.Amber, Tech.Green)
    val format: (Double?) -> String = { if (keys.first() in listOf("cpu","iowait")) percentage(it) else if(keys.first().startsWith("pressure")) it?.let { "%.1f%%".format(java.util.Locale.ROOT,it) } ?: "?" else if(keys.first()=="loadavg") it?.let { "%.2f".format(java.util.Locale.ROOT,it) } ?: "?" else compactIec(it, keys.first() in listOf("netin","diskread")) }
    val axisFormat: (Double?) -> String = { v -> format(v) }
    val max = remember(points, keys, hidden) {
        val peak = activeKeys.flatMap { k -> points.mapNotNull { it.values[k]?.takeIf { v -> v.isFinite() && v >= 0.0 } } }.maxOrNull() ?: 0.0
        niceHistoryMaximum(peak * 1.04, keys.first() == "cpu")
    }
    var chartSize by remember { mutableStateOf(IntSize(1, 1)) }
    var hover by remember { mutableStateOf<Float?>(null) }
    val clock = remember(timeframe) { DateTimeFormatter.ofPattern(if(timeframe=="hour") "HH:mm:ss" else if(timeframe=="day") "dd MMM HH:mm" else "dd MMM yy").withZone(ZoneId.systemDefault()) }
    fun time(p: HistoryPoint) = clock.format(Instant.ofEpochSecond(p.time.toLong()))
    val axisWidth = 64.dp
    var dragFrom by remember { mutableStateOf<Float?>(null) }
    var dragTo by remember { mutableStateOf<Float?>(null) }
    val capacity = setOf("maxmem", "memtotal", "swaptotal")
    fun seriesColor(k: String) = if (k in capacity) Tech.Muted else colors[keys.filterNot { it in capacity }.indexOf(k).coerceAtLeast(0) % colors.size]
    val primary = activeKeys.firstOrNull { it !in capacity } ?: activeKeys.firstOrNull()
    val primaryValues = remember(points, primary) { primary?.let { k -> points.mapNotNull { it.values[k]?.takeIf { v -> v.isFinite() } } }.orEmpty() }
    TechPanel(Modifier.fillMaxWidth(), padding = 10.dp) {
        // One fixed-height header row: title, legend chips (click to hide a series), latest value of the main series.
        Row(Modifier.fillMaxWidth().heightIn(min = 22.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title.uppercase(), fontSize = 10.sp, letterSpacing = 1.sp, fontFamily = Tech.Mono, color = Tech.Muted, maxLines = 1)
            keys.forEach { k ->
                val off = k in hidden
                Row(Modifier.clickable { hidden = if (off) hidden - k else hidden + k }.padding(horizontal = 2.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Canvas(Modifier.size(14.dp, 8.dp)) {
                        val c = seriesColor(k).copy(alpha = if (off) .3f else 1f)
                        if (k in capacity) drawLine(c, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 3f)))
                        else drawRect(c, Offset(0f, size.height * .2f), Size(size.width, size.height * .6f))
                    }
                    Text(series(k), fontSize = 10.sp, fontFamily = Tech.Mono, color = if (off) Tech.Muted.copy(alpha = .6f) else Tech.Ink, maxLines = 1)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(format(primary?.let { k -> points.lastOrNull { it.values[k] != null }?.values?.get(k) }), fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontFamily = Tech.Mono, color = primary?.let { seriesColor(it) } ?: Tech.Ink, maxLines = 1)
        }
        Text(if (primaryValues.isEmpty()) " " else "MIN ${format(primaryValues.min())}   AVG ${format(primaryValues.average())}   MAX ${format(primaryValues.max())}",
            Modifier.heightIn(min = 16.dp), fontSize = 9.sp, letterSpacing = .5.sp, fontFamily = Tech.Mono, color = Tech.Muted, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        if (points.none { p -> keys.any { p.values[it] != null } }) {
            Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { Text(emptyNote, color = Tech.Muted, fontSize = 11.sp, fontFamily = Tech.Mono) }
            return@TechPanel
        }
        val idle = max == 0.0
        Row {
            Column(Modifier.width(axisWidth).height(150.dp).padding(end = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                (if (idle) listOf(0.0) else listOf(max, max * .75, max / 2, max * .25, 0.0)).forEach { Text(axisFormat(it), Modifier.fillMaxWidth(), fontSize = 9.sp, color = Tech.Muted, fontFamily = Tech.Mono, textAlign = TextAlign.End, maxLines = 1) }
            }
            Box(Modifier.weight(1f).height(150.dp).clipToBounds().onSizeChanged { chartSize = it }
                .onPointerEvent(PointerEventType.Move) { hover = it.changes.firstOrNull()?.position?.x; shared.instant=points.first().time+(points.last().time-points.first().time)*((hover ?: 0f)/chartSize.width).coerceIn(0f,1f) }.onPointerEvent(PointerEventType.Exit) { hover = null; shared.instant=null }
                .pointerInput(points) { detectTapGestures(onDoubleTap={ shared.zoom=null }) }
                .pointerInput(points) {
                    var start=0f; var end=0f
                    detectDragGestures(onDragStart={ start=it.x; end=it.x; dragFrom=it.x; dragTo=it.x }, onDragEnd={
                        fun at(x: Float)=points.first().time+(points.last().time-points.first().time)*(x/chartSize.width).coerceIn(0f,1f)
                        if(kotlin.math.abs(end-start)>5) shared.zoom=historyRange(at(start),at(end))
                        dragFrom=null; dragTo=null
                    }, onDragCancel={ dragFrom=null; dragTo=null }) { change,_ -> end=change.position.x; dragTo=end; change.consume() }
                }
                .drawWithCache {
                    val start = points.firstOrNull()?.time ?: 0.0
                    val span = ((points.lastOrNull()?.time ?: start) - start).coerceAtLeast(1.0)
                    fun x(p: HistoryPoint) = ((p.time - start) / span * size.width).toFloat()
                    fun y(v: Double) = (size.height * (1 - (v / max.takeIf { it > 0.0 }.let { it ?: 1.0 }).coerceIn(0.0, 1.0))).toFloat()
                    val drawn = mutableListOf<Triple<Int, Path, Path>>()
                    if (!idle) activeKeys.forEachIndexed { index, key ->
                        val segment = mutableListOf<HistoryPoint>()
                        fun flush() {
                            if (segment.size >= 2) {
                                val line = Path(); line.moveTo(x(segment.first()), y(segment.first().values[key]!!))
                                segment.zipWithNext().forEach { (a, b) -> val mid = (x(a) + x(b)) / 2; line.cubicTo(mid, y(a.values[key]!!), mid, y(b.values[key]!!), x(b), y(b.values[key]!!)) }
                                val area = Path().apply { addPath(line); lineTo(x(segment.last()), size.height); lineTo(x(segment.first()), size.height); close() }
                                drawn.add(Triple(index, line, area))
                            }
                            segment.clear()
                        }
                        points.forEach {
                            val sample = it.values[key]
                            if (sample == null || !sample.isFinite() || sample < 0.0) flush() else segment.add(it)
                        }
                        flush()
                    }
                    val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val grid = Tech.Edge.copy(alpha = .45f)
                    onDrawBehind {
                        if (idle) drawLine(grid, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f)
                        else repeat(5) { n -> val h = size.height * n / 4; drawLine(grid, Offset(0f, h), Offset(size.width, h), 1f) }
                        drawn.forEach { (index, line, area) ->
                            val key = activeKeys[index]
                            if (key in capacity) drawPath(line, seriesColor(key), style = Stroke(1.4.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
                            else { val c = seriesColor(key); drawPath(area, Brush.verticalGradient(listOf(c.copy(alpha = .26f), c.copy(alpha = .02f)))); drawPath(line, c, style = stroke) }
                        }
                        val a = dragFrom; val b = dragTo
                        if (a != null && b != null) drawRect(Tech.Amber.copy(alpha = .14f), Offset(minOf(a, b), 0f), Size(kotlin.math.abs(b - a), size.height))
                        shared.instant?.let { ((it-start)/span*size.width).toFloat().coerceIn(0f,size.width) }?.let { drawLine(Tech.Ink.copy(alpha = .6f), Offset(it, 0f), Offset(it, size.height), 1f) }
                        // A marker on each visible series at the sample the readout names.
                        if (!idle) shared.instant?.let { nearestHistory(points, it) }?.let { p ->
                            activeKeys.filter { it !in capacity }.forEach { k ->
                                val v = p.values[k]?.takeIf { it.isFinite() && it >= 0.0 } ?: return@forEach
                                val c = Offset(x(p), y(v))
                                drawCircle(Tech.Panel, 4.dp.toPx(), c); drawCircle(seriesColor(k), 4.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
                            }
                        }
                    }
                }) {
                shared.instant?.takeIf { points.isNotEmpty() }?.let { instant ->
                    val x=((instant-points.first().time)/(points.last().time-points.first().time).coerceAtLeast(1.0)*chartSize.width).toFloat()
                    val target = points.first().time + (points.last().time - points.first().time) * (x / chartSize.width).coerceIn(0f, 1f)
                    val p = nearestHistory(points,target)!!
                    Surface(Modifier.offset { IntOffset(if (x > chartSize.width * .6f) (x.toInt() - 12 - 170.dp.roundToPx()).coerceAtLeast(0) else x.toInt() + 12, 6) }, color = Tech.Raised, shape = Tech.Control,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Tech.Edge), shadowElevation = 4.dp) {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(time(p), color = Tech.Muted, fontSize = 9.sp, fontFamily = Tech.Mono)
                            activeKeys.forEach { k -> Text("${series(k)}  ${format(p.values[k])}", color = seriesColor(k), fontSize = 10.sp, fontFamily = Tech.Mono) }
                        }
                    }
                }
            }
        }
        // One time label per ~110 dp of plot, between 2 and 5, so labels never run into each other. Uses the plot's
        // measured width: the panel measures intrinsics, which BoxWithConstraints does not support.
        val steps = with(androidx.compose.ui.platform.LocalDensity.current) { (chartSize.width / 110.dp.toPx()).toInt().coerceIn(1, 4) }
        Row(Modifier.fillMaxWidth().padding(start = axisWidth, top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val first = points.firstOrNull()?.time; val last = points.lastOrNull()?.time
            (0..steps).forEach { n -> Text(if (first == null || last == null) " " else clock.format(Instant.ofEpochSecond((first + (last - first) * n / steps).toLong())), fontSize = 9.sp, color = Tech.Muted, fontFamily = Tech.Mono, maxLines = 1) }
        }
    }
}

/** Round up in percent or the appropriate IEC unit to a readable step (1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10). */
private fun niceHistoryMaximum(peak: Double, cpu: Boolean): Double {
    if (!peak.isFinite() || peak <= 0.0) return 0.0
    val unit = if (cpu) .01 else 1024.0.pow( kotlin.math.floor(kotlin.math.ln(peak) / kotlin.math.ln(1024.0)).coerceIn(0.0, 6.0))
    val value = peak / unit
    val magnitude = 10.0.pow( kotlin.math.floor(kotlin.math.log10(value)))
    val step = listOf(1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0).first { it * magnitude >= value }
    return step * magnitude * unit
}

/** Axis label: at most three significant digits, so labels fit the axis column ("768 KiB/s", "1.5 MiB/s"). */
internal fun compactIec(value: Double?, rate: Boolean): String {
    if (value == null || !value.isFinite() || value < 0) return "—"
    var n = value; var unit = 0
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
    while (n >= 1024 && unit < units.lastIndex) { n /= 1024; unit++ }
    val number = when { unit == 0 || n >= 100 -> String.format(java.util.Locale.ROOT, "%.0f", n); n >= 10 -> String.format(java.util.Locale.ROOT, "%.0f", n); else -> String.format(java.util.Locale.ROOT, "%.1f", n) }
    return "$number ${units[unit]}" + if (rate) "/s" else ""
}
