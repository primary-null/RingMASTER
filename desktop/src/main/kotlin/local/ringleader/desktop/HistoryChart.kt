@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import kotlin.math.abs
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
import androidx.compose.ui.semantics.text
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
    // Tasks the app already holds for this guest, marked on its charts. Node charts carry none.
    val events = remember(state.tasks, node, state.selected) { if (node == null) guestChartEvents(state.tasks.orEmpty(), state.selected) else emptyList() }
    val extent = remember(all) { if (all.size > 1) all.first().time..all.last().time else null }
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
                                        onExpand = lens?.let { { it.open(historyLensRequest(state, node, title, keys, timeframe, cf, origin)) } }, events = events, extent = extent)
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

/** A task the app already knows about (from the task list in state), marked on a history chart at [time] (epoch seconds). */
data class ChartEvent(val time: Double, val label: String, val tone: MetricTone = MetricTone.Neutral)

/** Tasks for one guest as chart events: when each started, what it was, and whether it failed. No request is made. */
internal fun guestChartEvents(tasks: List<ClusterTask>, guest: Guest?): List<ChartEvent> {
    if (guest == null) return emptyList()
    val id = guest.id.toString()
    return tasks.asSequence().filter { it.subjectId == id && it.node == guest.node && it.startedAt != null }.map { t ->
        val tone = when (t.outcome) { TaskOutcome.Unsuccessful -> MetricTone.Critical; TaskOutcome.Running -> MetricTone.Attention; else -> MetricTone.Neutral }
        ChartEvent(t.startedAt!!.epochSecond.toDouble(), taskTypeName(t.type) + if (t.outcome == TaskOutcome.Unsuccessful) " failed" else "", tone)
    }.toList()
}

/** The series colour of a history key: capacities muted, inbound and read blue, everything else amber first. */
internal fun historySeriesColor(key: String, keys: List<String>): Color {
    val capacity = setOf("maxmem", "memtotal", "swaptotal", "total")
    return when {
        key in capacity -> MetricColors.capacity
        key == "netin" || key == "diskread" || key == "iowait" || key == "pressureiofull" || key == "pressurememoryfull" -> MetricColors.secondary
        key == "netout" || key == "diskwrite" -> MetricColors.primary
        else -> if (keys.filterNot { it in capacity }.indexOf(key) % 2 == 1) MetricColors.secondary else MetricColors.primary
    }
}

/**
 * Server RRD history chart: one or two series, gradient fill, aligned axes on round values, stats, shared crosshair.
 *
 * The Y axis starts at zero; a percentage never runs past 100 % and picks its top from 5, 10, 25, 50 and 100 % ([axisScale]);
 * bytes and rates tick at round IEC values. The time axis ticks on round local times ([timeTicks]). When the scale
 * changes (a new range, a zoom) it eases to its new top. A dashed line marks the average of the main series; missing
 * samples are left as a shaded gap; [events] (tasks from the task list) are marked on the time axis.
 *
 * Hover and keyboard share one cursor with every chart of the group ([HistoryInteraction]): the stats line of each
 * chart then names its own values at that moment, so the readout never covers the data. Focus the plot and use Left,
 * Right, Home and End to step through samples; Esc leaves. Drag to zoom, double-click to reset; in the lens
 * ([wheelZoom]) the wheel zooms about the pointer, elsewhere Ctrl and the wheel do.
 *
 * Hover is cheap: the shared cursor is read only while drawing and by [HistoryStatsLine], never by this composable's
 * body, so moving the pointer does not rebuild the cached paths and text layouts.
 */
@Composable fun HistoryChart(title: String, points: List<HistoryPoint>, keys: List<String>, emptyNote: String = "No samples yet — new guests take a few minutes", plotHeight: Dp = 150.dp, onExpand: (() -> Unit)? = null,
    events: List<ChartEvent> = emptyList(), extent: ClosedFloatingPointRange<Double>? = null, wheelZoom: Boolean = false) {
    val shared = LocalHistoryInteraction.current
    KeepCrosshair(shared, points)
    var hidden by remember(keys) { mutableStateOf(emptySet<String>()) }
    val activeKeys = keys.filterNot { it in hidden }
    val kind = historyAxisKind(keys.first())
    val format: (Double?) -> String = { if (keys.first() in listOf("cpu","iowait")) percentage(it) else if(keys.first().startsWith("pressure")) it?.let { "%.1f%%".format(java.util.Locale.ROOT,it) } ?: "?" else if(keys.first()=="loadavg") it?.let { "%.2f".format(java.util.Locale.ROOT,it) } ?: "?" else compactIec(it, keys.first() in listOf("netin","netout","diskread","diskwrite")) }
    val peak = remember(points, keys, hidden) { activeKeys.flatMap { k -> points.mapNotNull { it.values[k]?.takeIf { v -> v.isFinite() && v >= 0.0 } } }.maxOrNull() ?: 0.0 }
    // An idle window (every sample zero) still gets a small scale, so its line is drawn along the floor.
    val scale = remember(kind, peak, keys.first()) { axisScale(kind, if (peak > 0.0) peak else idleHistoryTop(keys.first())) }
    val reduce = LocalReduceMotion.current
    val top by animateFloatAsState(scale.top.toFloat(), if (reduce) snap() else tween(SPARK_SETTLE_MS, easing = FastOutSlowInEasing))
    val visibleSpan = ((points.lastOrNull()?.time ?: 0.0) - (points.firstOrNull()?.time ?: 0.0)).coerceAtLeast(0.0)
    val readoutClock = remember(historyReadoutPattern(visibleSpan)) { DateTimeFormatter.ofPattern(historyReadoutPattern(visibleSpan)).withZone(ZoneId.systemDefault()) }
    val capacity = setOf("maxmem", "memtotal", "swaptotal", "total")
    fun seriesColor(k: String) = historySeriesColor(k, keys)
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
            Text(metricText(format(latest), unitScaleFor(14.sp)), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Tech.Mono, color = primary?.let { seriesColor(it) } ?: Tech.Ink, maxLines = 1)
        }
        HistoryStatsLine(shared, points, activeKeys, readoutClock, format, ::seriesColor, primaryValues, events)
        Spacer(Modifier.height(4.dp))
        if (points.none { p -> keys.any { p.values[it] != null } }) {
            Box(Modifier.fillMaxWidth().height(plotHeight), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2f, 4f))
                    repeat(4) { n -> val y = size.height * (n + 1) / 4 - 1f; drawLine(MetricColors.gridFaint, Offset(0f, y), Offset(size.width, y), 1f, pathEffect = dash) }
                }
                Text(emptyNote, color = Tech.Muted, fontSize = 11.sp, fontFamily = Tech.Mono)
            }
            return@TechPanel
        }
        val description = if (primaryValues.isEmpty()) historyChartDescription(title, null, null, null, null)
            else historyChartDescription(title, format(latest), format(primaryValues.min()), format(primaryValues.average()), format(primaryValues.max()))
        HistoryPlot(shared, points, activeKeys, keys, scale, top, plotHeight, primary, primaryValues, events, description, extent, wheelZoom, ::seriesColor)
    }
}

/**
 * The stats line: minimum, average and maximum of the main series, or, while a cursor is on the group, the time and each
 * series' value at that sample (and a task that started there). It is the only reader of the cursor outside drawing.
 */
@Composable private fun HistoryStatsLine(shared: HistoryInteraction, points: List<HistoryPoint>, keys: List<String>, clock: DateTimeFormatter, format: (Double?) -> String,
    color: (String) -> Color, primaryValues: List<Double>, events: List<ChartEvent>) {
    val p = shared.instant?.let { nearestHistory(points, it) }
    val muted = Tech.Muted
    val text = if (p == null) {
        if (primaryValues.isEmpty()) AnnotatedString(" ")
        else AnnotatedString("MIN ${format(primaryValues.min())}   AVG ${format(primaryValues.average())}   MAX ${format(primaryValues.max())}")
    } else buildAnnotatedString {
        withStyle(SpanStyle(color = Tech.Ink)) { append(clock.format(Instant.ofEpochSecond(p.time.toLong()))) }
        keys.forEach { k -> append("   "); withStyle(SpanStyle(color = color(k))) { append("${historySeriesName(k).uppercase()} ${format(p.values[k])}") } }
        val step = if (points.size > 1) (points.last().time - points.first().time) / (points.size - 1) else 0.0
        events.firstOrNull { abs(it.time - p.time) <= step / 2 + 1 }?.let { e -> append("   "); withStyle(SpanStyle(color = if (e.tone == MetricTone.Neutral) Tech.Ink else e.tone.color)) { append("▲ ${e.label}") } }
    }
    Text(text, Modifier.fillMaxWidth().heightIn(min = 16.dp), fontSize = 9.sp, letterSpacing = .5.sp, fontFamily = Tech.Mono, color = muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
}

/** Room for the Y labels and the time labels; both grow with the text size, never with the plot height. */
private val HISTORY_GUTTER = 56.dp
private val HISTORY_AXIS = 16.dp

@Composable private fun HistoryPlot(
    shared: HistoryInteraction, points: List<HistoryPoint>, activeKeys: List<String>, keys: List<String>, scale: AxisScale, top: Float, plotHeight: Dp,
    primary: String?, primaryValues: List<Double>, events: List<ChartEvent>, description: String, extent: ClosedFloatingPointRange<Double>?, wheelZoom: Boolean,
    seriesColor: (String) -> Color,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fontScale = density.fontScale.coerceIn(1f, 1.5f)
    val gutterPx = with(density) { (HISTORY_GUTTER * fontScale).toPx() }
    val measurer = rememberTextMeasurer(cacheSize = 24)
    val capacity = setOf("maxmem", "memtotal", "swaptotal", "total")
    var dragFrom by remember { mutableStateOf<Float?>(null) }
    var dragTo by remember { mutableStateOf<Float?>(null) }
    var plotWidth by remember { mutableStateOf(1f) }
    val interaction = remember { MutableInteractionSource() }
    val focused = interaction.collectIsFocusedAsState()
    val axisColor = MetricColors.axis; val gridColor = MetricColors.grid; val faint = MetricColors.gridFaint
    val ink = Tech.Ink; val panel = Tech.Panel; val amber = Tech.Amber; val red = Tech.Red; val muted = Tech.Muted
    fun fractionAt(x: Float) = ((x - gutterPx) / plotWidth).coerceIn(0f, 1f).toDouble()
    fun timeAt(x: Float) = points.first().time + (points.last().time - points.first().time) * fractionAt(x)
    // Time ticks on round local times, as many as fit without touching. Placed here, once per data and width, so the
    // drawing and the screen reader's labels (one node per tick, below) are the same ticks in the same places.
    val timeLabels = remember(points, plotWidth, gutterPx, density, axisColor) {
        historyTimeLabels(points, plotWidth, gutterPx, measurer, TextStyle(fontSize = 9.sp, fontFamily = Tech.Mono, color = axisColor), with(density) { 18.dp.toPx() }, with(density) { 8.dp.toPx() })
    }
    val axisTopPx = with(density) { plotHeight.toPx() + 3.dp.toPx() }
    Box(Modifier.fillMaxWidth().height(plotHeight + HISTORY_AXIS * fontScale)
        .onSizeChanged { plotWidth = (it.width - gutterPx).coerceAtLeast(1f) }
        .semantics { contentDescription = description }
        // Focus observers and the key handler sit before the focus target, so they see its focus and its keys.
        .onFocusChanged { if (it.isFocused && shared.instant == null) shared.instant = points.lastOrNull()?.time }
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || points.isEmpty()) return@onKeyEvent false
            val at = shared.instant?.let { t -> points.indexOfFirst { it.time == t } } ?: -1
            val next = when (event.key) {
                Key.DirectionLeft -> (if (at < 0) points.lastIndex else at - 1).coerceAtLeast(0)
                Key.DirectionRight -> (if (at < 0) points.lastIndex else at + 1).coerceAtMost(points.lastIndex)
                Key.MoveHome -> 0
                Key.MoveEnd -> points.lastIndex
                Key.Escape -> { if (shared.instant == null) return@onKeyEvent false; shared.instant = null; return@onKeyEvent true }
                else -> return@onKeyEvent false
            }
            shared.instant = points[next].time
            true
        }
        .focusable(interactionSource = interaction)
        .onPointerEvent(PointerEventType.Move) { event ->
            val x = event.changes.firstOrNull()?.position?.x ?: return@onPointerEvent
            if (x < gutterPx || points.isEmpty()) return@onPointerEvent
            // Snap to the nearest sample, the one the markers and the stats line show.
            val at = historyAt(points, fractionAt(x))?.time
            if (shared.instant != at) shared.instant = at
        }
        .onPointerEvent(PointerEventType.Exit) { if (!focused.value) shared.instant = null }
        .onPointerEvent(PointerEventType.Scroll) { event ->
            val change = event.changes.firstOrNull() ?: return@onPointerEvent
            if (points.size < 3 || !(wheelZoom || event.keyboardModifiers.isCtrlPressed)) return@onPointerEvent
            val t = timeAt(change.position.x)
            val a = points.first().time; val b = points.last().time
            val f = if (change.scrollDelta.y < 0) .8 else 1.25
            val range = historyRange(t - (t - a) * f, t + (b - t) * f) ?: return@onPointerEvent
            val step = (b - a) / (points.size - 1)
            shared.zoom = when {
                extent != null && range.start <= extent.start && range.endInclusive >= extent.endInclusive -> null
                range.endInclusive - range.start < step * 4 -> shared.zoom
                else -> range
            }
            change.consume()
        }
        .pointerInput(points) { detectTapGestures(onDoubleTap = { shared.zoom = null }) }
        .pointerInput(points) {
            var start = 0f; var end = 0f
            detectDragGestures(onDragStart = { start = it.x; end = it.x; dragFrom = it.x; dragTo = it.x }, onDragEnd = {
                if (abs(end - start) > 5 && points.size > 1) shared.zoom = historyRange(timeAt(start), timeAt(end))
                dragFrom = null; dragTo = null
            }, onDragCancel = { dragFrom = null; dragTo = null }) { change, _ -> end = change.position.x; dragTo = end; change.consume() }
        }
        .drawWithCache {
            val left = gutterPx
            val plotH = plotHeight.toPx()
            val w = (size.width - left).coerceAtLeast(1f)
            val first = points.first().time
            val span = (points.last().time - first).coerceAtLeast(1.0)
            fun x(t: Double) = left + ((t - first) / span * w).toFloat()
            val inset = 1.dp.toPx()
            val topValue = top.toDouble().takeIf { it > 0.0 } ?: scale.top
            fun y(v: Double) = (inset + (plotH - 2 * inset) * (1 - (v / topValue).coerceIn(0.0, 1.0))).toFloat()
            // Series paths, broken at missing samples.
            val drawn = ArrayList<Triple<String, Path, Path>>()
            activeKeys.forEach { key ->
                val segment = ArrayList<HistoryPoint>()
                fun flush() {
                    if (segment.size >= 2) {
                        val line = Path(); line.moveTo(x(segment.first().time), y(segment.first().values[key]!!))
                        segment.zipWithNext().forEach { (a, b) -> val mid = (x(a.time) + x(b.time)) / 2; line.cubicTo(mid, y(a.values[key]!!), mid, y(b.values[key]!!), x(b.time), y(b.values[key]!!)) }
                        val area = Path().apply { addPath(line); lineTo(x(segment.last().time), plotH); lineTo(x(segment.first().time), plotH); close() }
                        drawn.add(Triple(key, line, area))
                    } else if (segment.size == 1) {
                        val p = segment.first(); val line = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(x(p.time), y(p.values[key]!!)), 1.5.dp.toPx())) }
                        drawn.add(Triple(key, line, Path()))
                    }
                    segment.clear()
                }
                points.forEach { val v = it.values[key]; if (v == null || !v.isFinite() || v < 0.0) flush() else segment.add(it) }
                flush()
            }
            // Gaps in the main series: from the last sample before to the first after.
            val gaps = ArrayList<Pair<Float, Float>>()
            if (primary != null) {
                var lastGood: Double? = null; var inGap = false
                points.forEach { p ->
                    val ok = p.values[primary]?.let { it.isFinite() && it >= 0.0 } == true
                    if (!ok) inGap = true
                    else { if (inGap && lastGood != null) gaps += x(lastGood!!) to x(p.time); inGap = false; lastGood = p.time }
                }
            }
            // Y ticks: labels from the target scale, placed on the eased one, so they slide into place with the lines.
            val labelStyle = TextStyle(fontSize = 9.sp, fontFamily = Tech.Mono, color = axisColor)
            // A short plot keeps every other tick (or fewer), always from zero, so labels never touch.
            val labelHeight = measurer.measure("0", labelStyle).size.height
            val spacing = if (scale.ticks.size > 1) plotH / (scale.ticks.size - 1) else plotH
            val stride = historyTickStride(spacing, labelHeight + 4.dp.toPx())
            val yLabels = scale.ticks.filterIndexed { i, _ -> i % stride == 0 }.map { v -> v to measurer.measure(scale.label(v), labelStyle) }
            val axisTop = plotH + 3.dp.toPx()
            // A flat series is its own average: no line for it.
            val avg = if (primary != null && primary !in capacity && primaryValues.size > 2 && primaryValues.max() > primaryValues.min()) primaryValues.average() else null
            val avgLayout = avg?.let { measurer.measure("avg", TextStyle(fontSize = 8.sp, fontFamily = Tech.Mono, color = seriesColor(primary!!))) }
            // A load chart at full scale shows where the load turns critical.
            val critical = if (scale.kind == AxisKind.Fraction && primary == "cpu" && scale.top >= 1.0) MetricThresholds.Load.critical else null
            val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val capacityStroke = Stroke(1.4.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
            val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
            val dots = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 3.dp.toPx()))
            val fills = drawn.associate { (k, _, _) -> k to seriesColor(k).let { c -> Brush.verticalGradient(listOf(c.copy(alpha = .26f), c.copy(alpha = .02f)), startY = 0f, endY = plotH) } }
            val markerRadius = 4.dp.toPx(); val markerStroke = Stroke(1.5.dp.toPx())
            val tick = 3.dp.toPx(); val eventSize = 4.dp.toPx()
            val eventPaths = events.filter { it.time in first..(first + span) }.map { e ->
                val ex = x(e.time)
                Triple(ex, Path().apply { moveTo(ex, plotH - eventSize * 1.6f); lineTo(ex + eventSize, plotH); lineTo(ex - eventSize, plotH); close() }, if (e.tone == MetricTone.Neutral) ink else e.tone.color)
            }
            val focusStroke = Stroke(1.dp.toPx())
            onDrawBehind {
                // Grid recedes: faint lines at each tick, the zero line a little stronger.
                yLabels.forEach { (v, layout) ->
                    val gy = y(v)
                    if (gy < -1f || gy > plotH + 1f) return@forEach
                    drawLine(if (v == 0.0) gridColor else faint, Offset(left, gy), Offset(size.width, gy), 1f)
                    val ly = (gy - layout.size.height / 2f).coerceIn(0f, plotH + 2.dp.toPx() - layout.size.height / 2f)
                    drawText(layout, topLeft = Offset(left - 6.dp.toPx() - layout.size.width, ly))
                }
                critical?.let { c -> drawLine(red.copy(alpha = .4f), Offset(left, y(c)), Offset(size.width, y(c)), 1f, pathEffect = dash) }
                gaps.forEach { (a, b) ->
                    drawRect(muted.copy(alpha = .07f), Offset(a, 0f), Size(b - a, plotH))
                    drawLine(muted.copy(alpha = .6f), Offset(a, plotH - 1f), Offset(b, plotH - 1f), 1f, pathEffect = dots)
                }
                clipRect(left, 0f, size.width, plotH) {
                    drawn.forEach { (key, line, area) ->
                        if (key in capacity) drawPath(line, seriesColor(key), style = capacityStroke)
                        else { drawPath(area, fills.getValue(key)); drawPath(line, seriesColor(key), style = stroke) }
                    }
                }
                avg?.let { a ->
                    val ay = y(a); val c = seriesColor(primary!!)
                    drawLine(c.copy(alpha = .55f), Offset(left, ay), Offset(size.width, ay), 1f, pathEffect = dash)
                    avgLayout?.let {
                        // On a plate at the right edge, so the word reads where the series crosses it.
                        val lx = size.width - it.size.width - 3.dp.toPx(); val ly = if (ay - it.size.height - 2f >= 0f) ay - it.size.height - 2f else ay + 2f
                        drawRect(panel.copy(alpha = .82f), Offset(lx - 2.dp.toPx(), ly), Size(it.size.width + 4.dp.toPx(), it.size.height.toFloat()))
                        drawText(it, topLeft = Offset(lx, ly))
                    }
                }
                eventPaths.forEach { (ex, path, c) ->
                    drawLine(c.copy(alpha = .45f), Offset(ex, 0f), Offset(ex, plotH), 1f, pathEffect = dots)
                    drawPath(path, c)
                }
                timeLabels.forEach { t ->
                    val cx = x(t.time)
                    drawLine(gridColor, Offset(cx, plotH), Offset(cx, plotH + tick), 1f)
                    drawText(t.layout, topLeft = Offset(t.left, axisTop))
                }
                val a = dragFrom; val b = dragTo
                if (a != null && b != null) drawRect(amber.copy(alpha = .14f), Offset(minOf(a, b).coerceAtLeast(left), 0f), Size(abs(b - a), plotH))
                // Draw-phase read of the shared cursor: the line and a marker on each visible series sit on the one sample the stats line names.
                shared.instant?.let { nearestHistory(points, it) }?.let { p ->
                    val px = x(p.time).coerceIn(left, size.width)
                    drawLine(ink.copy(alpha = .6f), Offset(px, 0f), Offset(px, plotH), 1f)
                    activeKeys.filter { it !in capacity }.forEach { k ->
                        val v = p.values[k]?.takeIf { it.isFinite() && it >= 0.0 } ?: return@forEach
                        val c = Offset(px, y(v))
                        drawCircle(panel, markerRadius, c); drawCircle(seriesColor(k), markerRadius, c, style = markerStroke)
                    }
                }
                if (focused.value) drawRect(amber, Offset(left, 0f), Size(size.width - left, plotH), style = focusStroke)
            }
        }) {
        timeLabels.forEach { t ->
            Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(t.left.toInt(), axisTopPx.toInt()) }
                .size(with(density) { t.layout.size.width.toDp() }, with(density) { t.layout.size.height.toDp() })
                .semantics { text = AnnotatedString(t.label) })
        }
    }
}

/** The lens's own cursor and zoom, so they never mix with the charts on the page. */
@Composable internal fun HistoryChartPanel(title: String, points: List<HistoryPoint>, keys: List<String>, emptyNote: String, plotHeight: Dp, interaction: HistoryInteraction,
    extent: ClosedFloatingPointRange<Double>? = null, wheelZoom: Boolean = false, events: List<ChartEvent> = emptyList()) {
    CompositionLocalProvider(LocalHistoryInteraction provides interaction) {
        HistoryChart(title, points, keys, emptyNote, plotHeight = plotHeight, events = events, extent = extent, wheelZoom = wheelZoom)
    }
}

/**
 * Drops a crosshair whose sample has scrolled out of [points]. The instant is read inside [snapshotFlow], not during
 * composition, so a pointer move still rebuilds only [HistoryStatsLine].
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

/** Scale for a window whose samples are all zero: 1% for CPU, 1 for load and pressure, 1 KiB for bytes. */
internal fun idleHistoryTop(key: String): Double = when {
    key == "cpu" || key == "iowait" -> .01
    key == "loadavg" || key.startsWith("pressure") -> 1.0
    else -> 1024.0
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

/** Every [n]th tick keeps a label when ticks are [spacing] apart and a label needs [needed]: 1, 2, 3 ... */
internal fun historyTickStride(spacing: Float, needed: Float): Int =
    if (!(spacing > 0f) || !needed.isFinite()) 1 else kotlin.math.ceil(needed / spacing).toInt().coerceAtLeast(1)

/** A time-axis label as placed: its sample time, its text, its layout and its left edge in the plot's box. */
internal class HistoryTimeLabel(val time: Double, val label: String, val layout: androidx.compose.ui.text.TextLayoutResult, val left: Float)

/** Round-time ticks for [points] across a plot [plotWidth] wide after a [gutter], dropping any label that would touch the one before. */
internal fun historyTimeLabels(points: List<HistoryPoint>, plotWidth: Float, gutter: Float, measurer: androidx.compose.ui.text.TextMeasurer, style: TextStyle,
    spacing: Float, gap: Float): List<HistoryTimeLabel> {
    if (points.size < 2 || !(plotWidth > 1f)) return emptyList()
    val first = points.first().time
    val span = (points.last().time - first).coerceAtLeast(1.0)
    val probe = measurer.measure("00 Nov", style).size.width
    val maxTicks = (plotWidth / (probe + spacing)).toInt().coerceIn(2, 8)
    val out = ArrayList<HistoryTimeLabel>()
    var lastRight = Float.NEGATIVE_INFINITY
    for (t in timeTicks(first, first + span, maxTicks)) {
        val layout = measurer.measure(t.label, style)
        val cx = gutter + ((t.time - first) / span * plotWidth).toFloat()
        val lx = (cx - layout.size.width / 2f).coerceIn(gutter, (gutter + plotWidth - layout.size.width).coerceAtLeast(gutter))
        if (lx > lastRight + gap) { out += HistoryTimeLabel(t.time, t.label, layout, lx); lastRight = lx + layout.size.width }
    }
    return out
}
