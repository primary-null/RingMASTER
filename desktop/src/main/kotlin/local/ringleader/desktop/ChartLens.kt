@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import local.ringleader.core.*

/** What a lens shows: whose numbers, and which chart. */
sealed interface LensSubject {
    data object Cluster : LensSubject
    data class GuestSubject(val resource: ResourceId) : LensSubject
    data class NodeSubject(val name: String) : LensSubject
}

/** [keys] are history keys (as HistoryGroup uses them); [live] is the live metric when there is one. */
data class LensChart(val title: String, val keys: List<String>, val live: LiveMetric?)

enum class LiveMetric { Cpu, Memory, Network }

data class LensRequest(
    val subject: LensSubject,
    val chart: LensChart,
    val timeframe: String = "live",
    val cf: String = "AVERAGE",
    val origin: Rect? = null,
)

@Stable class ChartLensState {
    var request by mutableStateOf<LensRequest?>(null)
        private set
    fun open(request: LensRequest) { this.request = request }
    fun close() { request = null }
}

val LocalChartLens = staticCompositionLocalOf<ChartLensState?> { null }

internal const val LENS_CLUSTER_ONLY = "The cluster has live samples only."
internal const val LENS_GUEST_GONE = "This guest is no longer in the inventory."
internal const val LENS_DIRECT_ONLY = "History needs a direct Proxmox VE connection."

private val liveKeys = setOf("cpu", "mem", "maxmem", "memused", "memtotal", "netin", "netout")
private val memoryKeys = setOf("mem", "maxmem", "memused", "memtotal")
private val networkKeys = setOf("netin", "netout")

/** One live metric when every key belongs to it. Disk, load, swap, pressure and a mixed pair have none. */
internal fun liveMetricFor(keys: List<String>): LiveMetric? {
    if (keys.isEmpty() || keys.any { it !in liveKeys }) return null
    val set = keys.toSet()
    return when {
        set == setOf("cpu") -> LiveMetric.Cpu
        set.all { it in memoryKeys } -> LiveMetric.Memory
        set.all { it in networkKeys } -> LiveMetric.Network
        else -> null
    }
}

/** History keys and title for a live metric. Node memory uses the node RRD names. */
internal fun lensChart(metric: LiveMetric, node: Boolean): LensChart = when (metric) {
    LiveMetric.Cpu -> LensChart("CPU", listOf("cpu"), metric)
    LiveMetric.Memory -> if (node) LensChart("Memory", listOf("memused", "memtotal"), metric) else LensChart("Memory", listOf("mem", "maxmem"), metric)
    LiveMetric.Network -> LensChart("Network", listOf("netin", "netout"), metric)
}

internal fun guestLens(guest: Guest, metric: LiveMetric) = LensRequest(LensSubject.GuestSubject(guest.resource), lensChart(metric, node = false))
internal fun nodeLens(name: String, metric: LiveMetric) = LensRequest(LensSubject.NodeSubject(name), lensChart(metric, node = true))
internal fun clusterLens(metric: LiveMetric) = LensRequest(LensSubject.Cluster, lensChart(metric, node = false))

/** Live samples as history points. [time] is epoch seconds. Cluster memory uses the guest keys. */
internal fun seriesHistory(series: Series, metric: LiveMetric, node: Boolean): List<HistoryPoint> =
    series.samples.map { sample ->
        val values: Map<String, Double?> = when (metric) {
            LiveMetric.Cpu -> mapOf("cpu" to sample.cpu)
            LiveMetric.Memory -> if (node) mapOf("memused" to sample.mem?.toDouble(), "memtotal" to sample.memMax?.toDouble())
            else mapOf("mem" to sample.mem?.toDouble(), "maxmem" to sample.memMax?.toDouble())
            LiveMetric.Network -> mapOf("netin" to sample.netInRate, "netout" to sample.netOutRate)
        }
        HistoryPoint(sample.at.toEpochMilli() / 1000.0, values)
    }

/** Hour to year for a node, and for a direct guest that is still in the inventory. Cluster and PDM stay on live samples. */
internal fun lensServerRanges(state: WorkspaceState, subject: LensSubject, guest: Guest?): Boolean = when (subject) {
    is LensSubject.Cluster -> false
    is LensSubject.NodeSubject -> !(state.kind == PDM_KIND && !state.demo)
    is LensSubject.GuestSubject -> guest != null && guestHasPage(state, guest)
}

/** Why a lens has no server ranges, or null when Hour to Year are available. */
internal fun lensRangeNote(subject: LensSubject, guest: Guest?, serverRanges: Boolean): String? = when {
    subject is LensSubject.GuestSubject && guest == null -> LENS_GUEST_GONE
    subject is LensSubject.Cluster -> LENS_CLUSTER_ONLY
    !serverRanges -> LENS_DIRECT_ONLY
    else -> null
}

/** The request an expand button opens. A chart with no live metric starts on the hour. */
internal fun historyLensRequest(state: WorkspaceState, node: String?, title: String, keys: List<String>, timeframe: String, cf: String, origin: Rect?): LensRequest {
    val live = liveMetricFor(keys)
    val selected = state.selected
    val subject = when {
        node != null -> LensSubject.NodeSubject(node)
        selected != null -> LensSubject.GuestSubject(selected.resource)
        else -> LensSubject.Cluster
    }
    val start = if (timeframe == "live" && live == null) "hour" else timeframe
    return LensRequest(subject, LensChart(title, keys, live), start, cf, origin)
}

/**
 * A chart that opens the lens on click: hand pointer, its bounds for the grow-from animation.
 * Does nothing where no lens host is present (tests, other pages). Press stays with the chart; the click is the outer one.
 */
fun Modifier.opensLens(request: () -> LensRequest): Modifier = composed {
    val lens = LocalChartLens.current ?: return@composed this
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val interaction = remember { MutableInteractionSource() }
    this.onGloballyPositioned { bounds = it.boundsInWindow() }
        .pointerHoverIcon(PointerIcon.Hand, overrideDescendants = true)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = "Open large") {
            lens.open(request().copy(origin = bounds))
        }
}

/** Wraps a page. The lens draws over that page, inside the page area. */
@Composable fun ChartLensHost(state: WorkspaceState, workspace: Workspace, content: @Composable () -> Unit) {
    val lens = remember { ChartLensState() }
    CompositionLocalProvider(LocalChartLens provides lens) {
        Box(Modifier.fillMaxSize()) {
            content()
            ChartLensLayer(lens, state, workspace)
        }
    }
}

@Composable private fun ChartLensLayer(lens: ChartLensState, state: WorkspaceState, workspace: Workspace) {
    var shown by remember { mutableStateOf<LensRequest?>(null) }
    val request = lens.request
    // Recorded as an overlay only while it is open, so page shortcuts wait behind it and are back as soon as it starts
    // to close: the frame stays composed for the closing animation, the record does not.
    if (request != null) RegisterModal(ModalKind.Overlay, lens::close)
    SideEffect { if (request != null) shown = request }
    val display = request ?: shown ?: return
    val reduce = LocalReduceMotion.current || LocalMotion.current.reduced
    val ms = if (reduce) 120 else LocalMotion.current.ms(MotionDur.Standard)
    val progress = remember { Animatable(0f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(request, ms) {
        if (request != null) progress.animateTo(1f, tween(ms, easing = MotionEase.Standard))
        else {
            progress.animateTo(0f, tween(ms, easing = MotionEase.Standard))
            if (lens.request == null) shown = null
        }
    }
    if (progress.value <= 0f && request == null) return
    val fraction = progress.value
    Box(Modifier.fillMaxSize().onGloballyPositioned { /* host bounds are read by the overlay below */ }) {
        ChartLensFrame(display, fraction, reduce, state, workspace, focus, lens::close)
    }
}

@Composable private fun ChartLensFrame(
    request: LensRequest, fraction: Float, reduce: Boolean, state: WorkspaceState, workspace: Workspace, focus: FocusRequester, onClose: () -> Unit,
) {
    var host by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { host = it.boundsInWindow() }
        .focusRequester(focus).focusable().onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { onClose(); true } else false
        }) {
        val margin = 24.dp
        val availW = (maxWidth - margin * 2).coerceAtLeast(0.dp)
        val availH = (maxHeight - margin * 2).coerceAtLeast(0.dp)
        val targetW = availW.coerceAtMost(1200.dp).let { if (availW >= 640.dp) it.coerceAtLeast(640.dp) else it }
        val targetH = availH.coerceAtMost(780.dp).let { if (availH >= 420.dp) it.coerceAtLeast(420.dp) else it }
        val density = androidx.compose.ui.platform.LocalDensity.current
        val hostRect = host
        val target = with(density) {
            val tw = targetW.toPx(); val th = targetH.toPx()
            val left = ((constraints.maxWidth - tw) / 2f).coerceAtLeast(0f)
            val top = ((constraints.maxHeight - th) / 2f).coerceAtLeast(0f)
            Rect(left, top, left + tw, top + th)
        }
        val origin = request.origin?.let { window ->
            val at = hostRect ?: return@let null
            val local = Rect(window.left - at.left, window.top - at.top, window.right - at.left, window.bottom - at.top)
            if (local.right > 0f && local.bottom > 0f && local.left < constraints.maxWidth && local.top < constraints.maxHeight) local else null
        }
        val rect = if (reduce || origin == null) target else lerpRect(origin, target, fraction)
        val panelAlpha = if (reduce) fraction else 1f
        val contentAlpha = if (reduce) fraction else ((fraction - 0.82f) / 0.18f).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f * fraction }.background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose))
        Box(Modifier.offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .graphicsLayer { alpha = panelAlpha }
            .clip(Tech.Plate)
            .background(Tech.Panel)
            .border(1.dp, Tech.Edge, Tech.Plate)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial)
                }
            }) {
            Column(Modifier.fillMaxSize().graphicsLayer { alpha = contentAlpha }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LensBody(request, state, workspace, onClose)
            }
        }
    }
}

private fun lerpRect(a: Rect, b: Rect, t: Float) = Rect(
    a.left + (b.left - a.left) * t,
    a.top + (b.top - a.top) * t,
    a.right + (b.right - a.right) * t,
    a.bottom + (b.bottom - a.bottom) * t,
)

@Composable private fun ColumnScope.LensBody(request: LensRequest, state: WorkspaceState, workspace: Workspace, onClose: () -> Unit) {
    val subject = request.subject
    val guest = (subject as? LensSubject.GuestSubject)?.let { wanted -> state.overview?.guests?.firstOrNull { it.resource == wanted.resource } }
    val server = lensServerRanges(state, subject, guest)
    val note = lensRangeNote(subject, guest, server)
    var timeframe by remember(request) { mutableStateOf(if (request.timeframe == "live" && request.chart.live == null) "hour" else request.timeframe) }
    var cf by remember(request) { mutableStateOf(request.cf) }
    val interaction = remember(request) { HistoryInteraction() }
    LaunchedEffect(timeframe, cf) { interaction.zoom = null }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(request.chart.title.uppercase(), fontSize = 11.sp, letterSpacing = 1.sp, fontFamily = Tech.Mono, color = Tech.Muted, maxLines = 1)
            LensSubjectLine(subject, guest)
        }
        HelpTip("Close (Esc)") { MiniAction("✕", onClick = onClose) }
    }
    val ranges = buildList {
        if (request.chart.live != null) add("live" to "Live")
        if (server) historyTimeframes.forEach { add(it to it.replaceFirstChar(Char::uppercase)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (ranges.isNotEmpty()) HistorySegments(ranges, timeframe) { timeframe = it }
        if (server && timeframe != "live") HistorySegments(listOf("AVERAGE" to "Average", "MAX" to "Maximum"), cf) { cf = it }
        LensReset(interaction)
    }
    note?.let { Text(it, color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 11.sp, maxLines = 2) }
    val node = subject is LensSubject.NodeSubject
    val series = when (subject) {
        is LensSubject.Cluster -> state.history.cluster
        is LensSubject.NodeSubject -> state.history.nodes[subject.name] ?: Series()
        is LensSubject.GuestSubject -> guest?.let { state.history.guests[it.resource] } ?: Series()
    }
    val livePoints = request.chart.live?.let { seriesHistory(series, it, node) }.orEmpty()
    var held by remember(subject) { mutableStateOf<List<HistoryPoint>>(emptyList()) }
    var loading by remember(subject) { mutableStateOf(false) }
    var error by remember(subject) { mutableStateOf<String?>(null) }
    val nodeName = (subject as? LensSubject.NodeSubject)?.name
    LaunchedEffect(subject, timeframe, cf, server) {
        if (timeframe == "live" || !server) { loading = false; error = null; return@LaunchedEffect }
        loading = true
        val section = try { workspace.readHistory(if (nodeName != null) null else guest, nodeName, timeframe, cf) }
        catch (e: CancellationException) { throw e }
        loading = false
        val value = section.value
        if (value != null) held = value
        error = section.error
    }
    val source = if (timeframe == "live") livePoints else held
    LensPlot(request.chart, source, interaction, loading, error)
}

@Composable private fun LensSubjectLine(subject: LensSubject, guest: Guest?) {
    when (subject) {
        is LensSubject.Cluster -> Text("Cluster", fontSize = 14.sp, fontFamily = Tech.Mono, color = Tech.Ink, maxLines = 1)
        is LensSubject.NodeSubject -> Text(subject.name, Modifier.identity(), fontSize = 14.sp, fontFamily = Tech.Mono, color = Tech.Ink, maxLines = 1)
        is LensSubject.GuestSubject -> if (guest == null) Text(LENS_GUEST_GONE, fontSize = 12.sp, fontFamily = Tech.Mono, color = Tech.Muted, maxLines = 1)
        else {
            Text(guest.name.ifBlank { "Guest ${guest.id}" }, Modifier.identity(), fontSize = 14.sp, fontFamily = Tech.Mono, color = Tech.Ink, maxLines = 1)
            Text("${guest.kind} ${guest.id} · ${guest.node}", Modifier.identity(), fontSize = 11.sp, fontFamily = Tech.Mono, color = Tech.Muted, maxLines = 1)
        }
    }
}

/** Reads the zoom, so a crosshair move (which only changes the instant) does not rebuild the plot. */
@Composable private fun LensReset(interaction: HistoryInteraction) {
    if (interaction.zoom != null) MiniAction("Reset zoom") { interaction.zoom = null }
}

@Composable private fun ColumnScope.LensPlot(chart: LensChart, source: List<HistoryPoint>, interaction: HistoryInteraction, loading: Boolean, error: String?) {
    val zoom = interaction.zoom
    val points = if (zoom == null) source else source.filter { it.time in zoom }
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val plot = (maxHeight - 96.dp).coerceIn(64.dp, 460.dp)
        val empty = when {
            loading && points.isEmpty() -> "Loading history…"
            error != null && points.isEmpty() -> error
            else -> "No samples in this window"
        }
        HistoryChartPanel(chart.title, points, chart.keys, empty, plot, interaction)
        if (loading && points.isNotEmpty()) Text("Loading history…", Modifier.align(Alignment.TopStart).padding(top = 28.dp), color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 11.sp)
        if (error != null && points.isNotEmpty()) Text(error, Modifier.align(Alignment.BottomStart), color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 11.sp, maxLines = 2)
    }
}
