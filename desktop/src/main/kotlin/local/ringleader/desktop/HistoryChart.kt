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
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.ringleader.core.*
import kotlin.math.pow
import java.time.*
import java.time.format.DateTimeFormatter

/**
 * The cursor and zoom shared by every chart of one group. [instant] is always the time of a sample of the plotted
 * points (see [historyAt]), so the crosshair, the markers and the readout name the same moment on every chart.
 */
class HistoryInteraction {
    var instant by mutableStateOf<Double?>(null)
    var zoom by mutableStateOf<ClosedFloatingPointRange<Double>?>(null)
}
private val LocalHistoryInteraction = staticCompositionLocalOf { HistoryInteraction() }

/** Set only by a test, so it can click the expand button without a semantics finder. */
internal val LocalExpandBounds = staticCompositionLocalOf<((Rect) -> Unit)?> { null }
private object HistoryPreference { var timeframe = "hour"; var cf = "AVERAGE" }

/** The history load key and whether this view should read now. A stale inventory cannot read, so the charts reload once it recovers. */
internal class HistoryLoadGate {
    private var loaded: Any? = null
    fun shouldLoad(key: Any, stale: Boolean): Boolean {
        if (stale && loaded == key) return false
        loaded = key
        return true
    }
}

@Composable fun HistoryGroup(state: WorkspaceState, workspace: Workspace, node: String? = null) {
    var timeframe by remember { mutableStateOf(HistoryPreference.timeframe) }
    var cf by remember { mutableStateOf(HistoryPreference.cf) }
    val resource = state.selected?.resource
    val interaction = remember(node, resource) { HistoryInteraction() }
    LaunchedEffect(timeframe, cf, node, resource) { interaction.zoom = null }
    // state.stale is a key: a view opened (or kept open) while the inventory was stale reads again once a sync succeeds.
    // Going stale does not read again, so the last good series stays on screen.
    val gate = remember { HistoryLoadGate() }
    LaunchedEffect(timeframe, cf, node, resource, state.stale) {
        if (gate.shouldLoad(listOf(timeframe, cf, node, resource), state.stale)) workspace.inspectHistory(timeframe, cf, node)
    }
    val all = state.chartHistory.value.orEmpty()
    val zoom = interaction.zoom
    // Remembered so the charts see the same list instance until the data or the zoom changes, and skip the live ticks.
    val points = remember(all, zoom) { if (zoom == null) all else all.filter { it.time in zoom } }
    val charts = remember(node, all) {
        if(node == null) listOf(("CPU / " + (all.lastOrNull { it.values["maxcpu"] != null }?.values?.get("maxcpu")?.toInt()?.toString() ?: "allocated") + " cores") to listOf("cpu"), "Memory" to listOf("mem","maxmem"), "Network" to listOf("netin","netout"), "Disk IO" to listOf("diskread","diskwrite")) else
        listOf("CPU / IO wait" to listOf("cpu","iowait"), "Server load" to listOf("loadavg"), "Memory" to listOf("memused","memtotal"), "Swap" to listOf("swapused","swaptotal"), "Network" to listOf("netin","netout")) +
        listOf("CPU pressure" to listOf("pressurecpusome"), "IO pressure" to listOf("pressureiosome","pressureiofull"), "Memory pressure" to listOf("pressurememorysome","pressurememoryfull")).filter { (_,keys) -> all.any { p -> keys.any { p.values[it] != null } } }
    }
    val host = LocalAppHost.current
    val scope = rememberCoroutineScope()
    var exportNote by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        // Wraps in narrow places (the node inspector) instead of running off the edge.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalArrangement=Arrangement.spacedBy(6.dp), itemVerticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            HistorySegments(historyTimeframes.map { it to it.replaceFirstChar(Char::uppercase) }, timeframe) { timeframe = it; HistoryPreference.timeframe = it }
            HistorySegments(listOf("AVERAGE" to "Average", "MAX" to "Maximum"), cf) { cf = it; HistoryPreference.cf = it }
            if(interaction.zoom != null) MiniAction("Reset zoom") { interaction.zoom=null }
            if(!host.web) MiniAction("CSV") {
                exportNote = null
                val dialog=java.awt.FileDialog(null as java.awt.Frame?, "Export visible history",java.awt.FileDialog.SAVE)
                val target = try { dialog.file="history.csv"; dialog.isVisible=true; dialog.file?.let { java.io.File(dialog.directory,it) } } finally { dialog.dispose() }
                if (target != null) {
                    val rows = points; val keys = charts.flatMap { it.second }.distinct()
                    // The file is written off the UI thread; only the outcome comes back.
                    scope.launch {
                        val failure = withContext(Dispatchers.IO) { runCatching { target.writeText(historyCsv(rows, keys)) }.exceptionOrNull() }
                        exportNote = if (failure == null) "Saved ${target.name}." to false else "Could not save ${target.name}. ${failure.message.orEmpty()}".trim() to true
                    }
                }
            }
        }
        exportNote?.let { (text, failed) -> Text(text, color = if (failed) Tech.Red else Tech.Muted, fontFamily = Tech.Mono, fontSize = 11.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        state.chartHistory.error?.let { Text(it, color=Tech.Muted,fontFamily=Tech.Mono,fontSize=11.sp) }
        if(state.chartHistory.error == null) CompositionLocalProvider(LocalHistoryInteraction provides interaction) {
            // Two charts per row on wide layouts, like the Proxmox graph panels; one per row when narrow.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val perRow = if (maxWidth >= 1000.dp) 2 else 1
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    charts.chunked(perRow).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { (title, keys) ->
                                var origin by remember(title, keys) { mutableStateOf<Rect?>(null) }
                                val lens = LocalChartLens.current
                                Box(Modifier.weight(1f).onGloballyPositioned { origin = it.boundsInWindow() }) {
                                    HistoryChart(title, points, keys, if (state.chartHistoryLoading) "Loading history…" else "No samples in this window",
                                        onExpand = lens?.let { { it.open(historyLensRequest(state, node, title, keys, timeframe, cf, origin)) } })
                                }
                            }
                            if (row.size < perRow) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** Joined segmented choice used for the timeframe and the consolidation. */
@Composable internal fun HistorySegments(items: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
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

private fun historySeriesName(key: String) = when (key) { "cpu" -> "CPU"; "mem" -> "Memory"; "netin" -> "Inbound"; "netout" -> "Outbound"; "diskread" -> "Disk read"; "diskwrite" -> "Disk write"; "total", "maxmem", "memtotal" -> "Total"; "used", "memused" -> "Used"; "swapused" -> "Used"; "swaptotal" -> "Total"; "iowait" -> "IO wait"; "loadavg" -> "Load average"; "pressurecpusome", "pressureiosome", "pressurememorysome" -> "Some"; "pressureiofull", "pressurememoryfull" -> "Full"; else -> key }

/** What a chart says to a screen reader: its title, the latest value, and the minimum, average and maximum. */
internal fun historyChartDescription(title: String, latest: String?, min: String?, avg: String?, max: String?): String =
    if (latest == null || min == null || avg == null || max == null) "$title chart. No samples."
    else "$title chart. Latest $latest. Minimum $min, average $avg, maximum $max."

/**
 * Server RRD history chart: one or two series, gradient fill, grid, axis labels, stats, hover crosshair with readout.
 *
 * Hover is cheap: the shared cursor is read only while drawing (the crosshair and the markers) and by [HistoryReadout],
 * never by this composable's body, so moving the pointer does not rebuild the cached stroke and fill paths.
 */
@Composable fun HistoryChart(title: String, points: List<HistoryPoint>, keys: List<String>, emptyNote: String = "No samples yet — new guests take a few minutes", plotHeight: Dp = 150.dp, onExpand: (() -> Unit)? = null) {
    val shared = LocalHistoryInteraction.current
    KeepCrosshair(shared, points)
    var hidden by remember(keys) { mutableStateOf(emptySet<String>()) }
    val activeKeys = keys.filterNot { it in hidden }
    val colors = listOf(Tech.Amber, Tech.Green)
    val format: (Double?) -> String = { if (keys.first() in listOf("cpu","iowait")) percentage(it) else if(keys.first().startsWith("pressure")) it?.let { "%.1f%%".format(java.util.Locale.ROOT,it) } ?: "?" else if(keys.first()=="loadavg") it?.let { "%.2f".format(java.util.Locale.ROOT,it) } ?: "?" else compactIec(it, keys.first() in listOf("netin","diskread")) }
    val axisFormat: (Double?) -> String = { v -> format(v) }
    val max = remember(points, keys, hidden) {
        val peak = activeKeys.flatMap { k -> points.mapNotNull { it.values[k]?.takeIf { v -> v.isFinite() && v >= 0.0 } } }.maxOrNull() ?: 0.0
        // An idle window (every sample zero) still gets a scale, so its line is drawn along the bottom.
        niceHistoryMaximum(peak * 1.04, keys.first() == "cpu").takeIf { it > 0.0 } ?: idleHistoryTop(keys.first())
    }
    var chartSize by remember { mutableStateOf(IntSize(1, 1)) }
    var inside by remember { mutableStateOf(false) }
    val visibleSpan = ((points.lastOrNull()?.time ?: 0.0) - (points.firstOrNull()?.time ?: 0.0)).coerceAtLeast(0.0)
    // The axis pattern follows the visible span, so a zoomed week or month still shows the time of day.
    val clock = remember(historyAxisPattern(visibleSpan)) { DateTimeFormatter.ofPattern(historyAxisPattern(visibleSpan)).withZone(ZoneId.systemDefault()) }
    val readoutClock = remember(historyReadoutPattern(visibleSpan)) { DateTimeFormatter.ofPattern(historyReadoutPattern(visibleSpan)).withZone(ZoneId.systemDefault()) }
    val axisWidth = 64.dp
    var dragFrom by remember { mutableStateOf<Float?>(null) }
    var dragTo by remember { mutableStateOf<Float?>(null) }
    val capacity = setOf("maxmem", "memtotal", "swaptotal", "total")
    fun seriesColor(k: String) = if (k in capacity) Tech.Muted else colors[keys.filterNot { it in capacity }.indexOf(k).coerceAtLeast(0) % colors.size]
    val primary = activeKeys.firstOrNull { it !in capacity } ?: activeKeys.firstOrNull()
    val primaryValues = remember(points, primary) { primary?.let { k -> points.mapNotNull { it.values[k]?.takeIf { v -> v.isFinite() } } }.orEmpty() }
    val latest = primary?.let { k -> points.lastOrNull { it.values[k] != null }?.values?.get(k) }
    TechPanel(Modifier.fillMaxWidth(), padding = 10.dp) {
        // One fixed-height header row: title, legend chips (click to hide a series), latest value of the main series.
        // The title and the legend wrap in a narrow panel (the node inspector); the expand button and the latest value stay at the right.
        Row(Modifier.fillMaxWidth().heightIn(min = 22.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(title.uppercase(), fontSize = 10.sp, letterSpacing = 1.sp, fontFamily = Tech.Mono, color = Tech.Muted, maxLines = 1)
            keys.forEach { k ->
                val off = k in hidden
                Row(Modifier.clickable { hidden = if (off) hidden - k else hidden + k }.padding(horizontal = 2.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Canvas(Modifier.size(14.dp, 8.dp)) {
                        val c = seriesColor(k).copy(alpha = if (off) .3f else 1f)
                        if (k in capacity) drawLine(c, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 3f)))
                        else drawRect(c, Offset(0f, size.height * .2f), Size(size.width, size.height * .6f))
                    }
                    Text(historySeriesName(k), fontSize = 10.sp, fontFamily = Tech.Mono, color = if (off) Tech.Muted.copy(alpha = .6f) else Tech.Ink, maxLines = 1)
                }
            }
          }
            if (onExpand != null) {
                val report = LocalExpandBounds.current
                Box(Modifier.onGloballyPositioned { report?.invoke(it.boundsInRoot()) }) {
                    HelpTip("Open large") { MiniAction("⤢", onClick = onExpand) }
                }
            }
            Text(format(latest), fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontFamily = Tech.Mono, color = primary?.let { seriesColor(it) } ?: Tech.Ink, maxLines = 1)
        }
        Text(if (primaryValues.isEmpty()) " " else "MIN ${format(primaryValues.min())}   AVG ${format(primaryValues.average())}   MAX ${format(primaryValues.max())}",
            Modifier.heightIn(min = 16.dp), fontSize = 9.sp, letterSpacing = .5.sp, fontFamily = Tech.Mono, color = Tech.Muted, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        if (points.none { p -> keys.any { p.values[it] != null } }) {
            Box(Modifier.fillMaxWidth().height(plotHeight), contentAlignment = androidx.compose.ui.Alignment.Center) { Text(emptyNote, color = Tech.Muted, fontSize = 11.sp, fontFamily = Tech.Mono) }
            return@TechPanel
        }
        val description = if (primaryValues.isEmpty()) historyChartDescription(title, null, null, null, null)
            else historyChartDescription(title, format(latest), format(primaryValues.min()), format(primaryValues.average()), format(primaryValues.max()))
        Row {
            Column(Modifier.width(axisWidth).height(plotHeight).padding(end = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                listOf(max, max * .75, max / 2, max * .25, 0.0).forEach { Text(axisFormat(it), Modifier.fillMaxWidth(), fontSize = 9.sp, color = Tech.Muted, fontFamily = Tech.Mono, textAlign = TextAlign.End, maxLines = 1) }
            }
            Box(Modifier.weight(1f).height(plotHeight)) {
            Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { chartSize = it }
                .semantics { contentDescription = description }
                .onPointerEvent(PointerEventType.Move) { event ->
                    val x = event.changes.firstOrNull()?.position?.x ?: return@onPointerEvent
                    // Snap to the nearest sample, the one the markers and the readout show.
                    val at = historyAt(points, (x / chartSize.width.coerceAtLeast(1)).toDouble())?.time
                    if (shared.instant != at) shared.instant = at
                    if (!inside) inside = true
                }
                .onPointerEvent(PointerEventType.Exit) { inside = false; shared.instant = null }
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
                    // A one-pixel inset keeps a stroke at zero (an idle window) or at the top fully visible inside the clip.
                    val inset = 1.dp.toPx()
                    fun y(v: Double) = (inset + (size.height - 2 * inset) * (1 - (v / max).coerceIn(0.0, 1.0))).toFloat()
                    val drawn = mutableListOf<Triple<Int, Path, Path>>()
                    activeKeys.forEachIndexed { index, key ->
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
                    val capacityStroke = Stroke(1.4.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
                    val fills = activeKeys.map { k -> seriesColor(k).let { c -> Brush.verticalGradient(listOf(c.copy(alpha = .26f), c.copy(alpha = .02f))) } }
                    val grid = Tech.Edge.copy(alpha = .45f)
                    val markerRadius = 4.dp.toPx(); val markerStroke = Stroke(1.5.dp.toPx())
                    onDrawBehind {
                        repeat(5) { n -> val h = size.height * n / 4; drawLine(grid, Offset(0f, h), Offset(size.width, h), 1f) }
                        drawn.forEach { (index, line, area) ->
                            val key = activeKeys[index]
                            if (key in capacity) drawPath(line, seriesColor(key), style = capacityStroke)
                            else { drawPath(area, fills[index]); drawPath(line, seriesColor(key), style = stroke) }
                        }
                        val a = dragFrom; val b = dragTo
                        if (a != null && b != null) drawRect(Tech.Amber.copy(alpha = .14f), Offset(minOf(a, b), 0f), Size(kotlin.math.abs(b - a), size.height))
                        // Draw-phase read of the shared cursor: the line and a marker on each visible series sit on the one sample the readout names.
                        shared.instant?.let { nearestHistory(points, it) }?.let { p ->
                            val px = x(p).coerceIn(0f, size.width)
                            drawLine(Tech.Ink.copy(alpha = .6f), Offset(px, 0f), Offset(px, size.height), 1f)
                            activeKeys.filter { it !in capacity }.forEach { k ->
                                val v = p.values[k]?.takeIf { it.isFinite() && it >= 0.0 } ?: return@forEach
                                val c = Offset(px, y(v))
                                drawCircle(Tech.Panel, markerRadius, c); drawCircle(seriesColor(k), markerRadius, c, style = markerStroke)
                            }
                        }
                    }
                })
            HistoryReadout(shared, points, { inside }, { chartSize.width }, activeKeys, readoutClock, ::historySeriesName, format, ::seriesColor)
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

/** The lens's own cursor and zoom, so they never mix with the charts on the page. */
@Composable internal fun HistoryChartPanel(title: String, points: List<HistoryPoint>, keys: List<String>, emptyNote: String, plotHeight: Dp, interaction: HistoryInteraction) {
    CompositionLocalProvider(LocalHistoryInteraction provides interaction) {
        HistoryChart(title, points, keys, emptyNote, plotHeight = plotHeight)
    }
}

/**
 * Drops a crosshair whose sample has scrolled out of [points]. The instant is read inside [snapshotFlow], not during
 * composition, so a pointer move still rebuilds only [HistoryReadout].
 */
@Composable private fun KeepCrosshair(shared: HistoryInteraction, points: List<HistoryPoint>) {
    LaunchedEffect(points) {
        snapshotFlow { shared.instant }.collect { instant ->
            if (instant == null) return@collect
            val first = points.firstOrNull()?.time
            val last = points.lastOrNull()?.time
            if (first == null || last == null || instant < first || instant > last) shared.instant = null
        }
    }
}

/**
 * The one readout: shown only on the chart under the pointer, beside the crosshair, for the sample the crosshair is on.
 * It is the only composable that reads the shared cursor, so a pointer move recomposes this panel and nothing else.
 */
@Composable private fun HistoryReadout(
    shared: HistoryInteraction, points: List<HistoryPoint>, visible: () -> Boolean, width: () -> Int, keys: List<String>,
    clock: DateTimeFormatter, name: (String) -> String, format: (Double?) -> String, color: (String) -> Color,
) {
    if (!visible() || points.isEmpty()) return
    val p = shared.instant?.let { nearestHistory(points, it) } ?: return
    val w = width().coerceAtLeast(1)
    val x = ((p.time - points.first().time) / (points.last().time - points.first().time).coerceAtLeast(1.0) * w).toFloat()
    HoverReadoutPopup(pointerX = x, above = false, gap = 12, besideTop = 6) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(clock.format(Instant.ofEpochSecond(p.time.toLong())), color = Tech.Muted, fontSize = 9.sp, fontFamily = Tech.Mono)
            keys.forEach { k -> Text("${name(k)}  ${format(p.values[k])}", color = color(k), fontSize = 10.sp, fontFamily = Tech.Mono) }
        }
    }
}

/** Scale for a window whose samples are all zero: 1% for CPU, 1 for load and pressure, 1 KiB for bytes. */
internal fun idleHistoryTop(key: String): Double = when {
    key == "cpu" || key == "iowait" -> .01
    key == "loadavg" || key.startsWith("pressure") -> 1.0
    else -> 1024.0
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
