@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import local.ringleader.core.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

val LocalSearchFocus = staticCompositionLocalOf { androidx.compose.ui.focus.FocusRequester() }

/** Load colour: <50% green, 50-85% amber, above 85% red. */
internal fun ramp(value: Double) = when { value < .5 -> Tech.Green; value <= .85 -> Tech.Amber; else -> Tech.Red }
internal fun formatRate(value: Double?): String = when {
    value == null || !value.isFinite() || value < 0 -> "—"
    // Switch unit at 1000, not 1024, so a value never needs four integer digits ("1014.9 KiB/s" clipped).
    value >= 1000 * 1048576.0 -> String.format(Locale.ROOT, "%.2f GiB/s", value / 1073741824)
    value >= 1000 * 1024.0 -> String.format(Locale.ROOT, if (value < 10 * 1048576.0) "%.2f MiB/s" else "%.1f MiB/s", value / 1048576)
    value >= 1000 -> String.format(Locale.ROOT, if (value < 10 * 1024.0) "%.2f KiB/s" else "%.1f KiB/s", value / 1024)
    else -> String.format(Locale.ROOT, "%.0f B/s", value)
}
internal fun percent(value: Double?): String {
    if (value == null || !value.isFinite() || value < 0.0) return "—"
    return String.format(Locale.ROOT, if (value < .1) "%.1f%%" else "%.0f%%", value * 100)
}
internal fun gib(bytes: Long?): String {
    if (bytes == null || bytes < 0L) return "—"
    return String.format(Locale.ROOT, if (bytes >= 10L * 1073741824) "%.0f" else "%.1f", bytes / 1073741824.0)
}
internal fun shortcutPage(number: Int) = ringPages.getOrNull(number - 1)

/** Latest finite, non-negative sample, otherwise the same check on the recorded fallback. */
internal fun latestCpu(series: Series?, fallback: Double?): Double? =
    (series?.samples?.lastOrNull()?.cpu ?: fallback)?.takeIf { it.isFinite() && it >= 0.0 }

internal fun latestMemory(series: Series?, fallback: Long?): Long? =
    (series?.samples?.lastOrNull()?.mem ?: fallback)?.takeIf { it >= 0L }

/**
 * How far above 1 a CPU fraction may read before it is not trusted. Proxmox reports CPU as a fraction of capacity, and
 * accounting jitter can put a busy node a little over 1. Up to this it reads as 100%.
 */
internal const val CPU_OVERSHOOT = 1.05

/** A CPU fraction for display: 0..1, a slight overshoot shown as 1, anything else (or beyond [CPU_OVERSHOOT]) null. The node gauge and fact line both use it. */
internal fun cpuShare(value: Double?): Double? =
    value?.takeIf { it.isFinite() && it >= 0.0 }?.let { if (it <= 1.0) it else if (it <= CPU_OVERSHOOT) 1.0 else null }

/** Null when either side is missing, negative, or the capacity is zero. */
internal fun memoryFraction(used: Long?, capacity: Long?): Double? {
    if (used == null || capacity == null || used < 0L || capacity <= 0L) return null
    return used.toDouble() / capacity
}

/** A guest that is neither running, stopped nor a template: paused, suspended, or unknown while its node is offline. */
internal fun guestOtherStatus(guest: Guest) =
    !guest.template && !guest.status.equals("running", true) && !guest.status.equals("stopped", true)

/** Running and stopped counts exclude templates, matching [fleetTotals]; Other holds every remaining state. Search includes tags. */
internal fun guestVisible(guest: Guest, filter: String, search: String): Boolean {
    val statusOk = when (filter) {
        "All" -> true
        "Templates" -> guest.template
        "Running" -> !guest.template && guest.status.equals("running", true)
        "Stopped" -> !guest.template && guest.status.equals("stopped", true)
        "Other" -> guestOtherStatus(guest)
        else -> guest.status.equals(filter, true)
    }
    if (!statusOk) return false
    val query = search.trim()
    if (query.isEmpty()) return true
    return guest.tags.any { it.contains(query, true) } ||
        listOf(guest.name, guest.node, guest.scope, guest.id.toString()).any { it.contains(query, true) }
}

/**
 * Ascending comparator. The header reverses it while the down arrow is showing, so CPU, memory and uptime
 * put the busiest guest first and missing values last. Stopped guests and templates display a dash, so they sort as missing.
 */
internal fun guestComparator(sort: String, history: FleetHistory = FleetHistory()): Comparator<Guest> = when (sort) {
    in steadySorts -> compareBy { guest -> steadySortScore(sort, guest, history.guests[guest.resource]) }
    "ID" -> compareBy { it.id }
    "Uptime" -> compareBy { guest ->
        if (guest.template || !guest.status.equals("running", true)) -1L
        else guest.metrics?.uptimeSeconds?.takeIf { it >= 0L } ?: -1L
    }
    else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
}
/**
 * Vertical scale for a sparkline: never flatter than [floor], 25% headroom over the peak, capped at [ceiling].
 * A guest idling at 2% still shows movement instead of a flat line along the bottom.
 */
internal fun sparkTop(values: List<Double?>, floor: Double = .05, ceiling: Double = 1.0): Double {
    val low = if (floor.isFinite() && floor > 0.0) floor else 0.05
    val high = if (ceiling.isFinite() && ceiling >= low) ceiling else low
    val peak = values.asSequence().filterNotNull().filter { it.isFinite() && it >= 0.0 }.maxOrNull() ?: 0.0
    return (peak * 1.25).coerceIn(low, high)
}

@Composable fun AnimatedNumber(value: Double, modifier: Modifier = Modifier, fontSize: TextUnit = 15.sp) {
    val v by animateFloatAsState(value.toFloat(), tween(if (LocalReduceMotion.current) 0 else 400))
    Text(percent(v.toDouble()), modifier, fontSize = fontSize, color = ramp(value), fontFamily = Tech.Mono, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

@Composable fun AnimatedCount(value: Int, modifier: Modifier = Modifier, color: Color = Tech.Ink, fontSize: TextUnit = 18.sp) {
    val v by animateIntAsState(value, tween(if (LocalReduceMotion.current) 0 else 400))
    Text("$v", modifier, fontSize = fontSize, color = color, fontFamily = Tech.Mono, fontWeight = FontWeight.SemiBold)
}

/** How long a chart takes to take in a new sample: the slide to the left and the change of scale run together. */
internal const val SPARK_SETTLE_MS = 450

/** The sample that scrolled off the left edge when [next] replaced [previous] in a full buffer, to draw it out instead of cutting it off. */
internal fun sparkLead(previous: List<Double?>, next: List<Double?>): Double? =
    if (previous.size == next.size && next.size >= 2 && previous != next) previous.first()?.takeIf { it.isFinite() && it >= 0.0 } else null

/**
 * Nearest sample to a pointer [x] inside a sparkline of [width]. The slide is ignored here: the hairline adds it when
 * it draws, so the index stays on the sample the line is showing. Empty and a non-positive width have no sample.
 */
internal fun sparkNearestIndex(x: Float, width: Float, count: Int): Int? {
    if (count <= 0 || !(width > 0f) || !x.isFinite()) return null
    if (count == 1) return 0
    val step = width / (count - 1).toFloat()
    if (!(step > 0f)) return 0
    return (x / step).roundToInt().coerceIn(0, count - 1)
}

/** Which words a series sparkline says on hover. [None] draws the hairline and no bubble. */
enum class SparkReadout { Cpu, Memory, None }

/** Hover x in the sparkline's own coordinates. Read only while drawing and inside the readout, never by the line body. */
@Stable
class SparkHover {
    var x by mutableStateOf<Float?>(null)
}

/** Counts compositions of the sparkline body when a test provides it. Pointer moves must not change it. */
internal val LocalSparkBodyCompositions = staticCompositionLocalOf<MutableIntState?> { null }

internal fun sparkCpuValue(sample: Sample): String {
    val cpu = sample.cpu
    if (cpu == null || !cpu.isFinite() || cpu < 0.0) return "no sample"
    return String.format(Locale.ROOT, "%.1f %%", cpu * 100)
}

internal fun sparkMemoryValue(sample: Sample): String {
    val fraction = memoryFraction(sample.mem, sample.memMax)
    val mem = sample.mem
    if (fraction == null && (mem == null || mem < 0L)) return "no sample"
    val pct = fraction?.let { String.format(Locale.ROOT, "%.0f %%", it * 100) } ?: "—"
    val cap = sample.memMax
    if (mem != null && mem >= 0L && cap != null && cap > 0L) return "$pct · ${gib(mem)} / ${gib(cap)} GiB"
    return pct
}

internal fun sparkNetValue(sample: Sample): String {
    if (sample.netInRate == null && sample.netOutRate == null) return "no sample"
    return "IN ${formatRate(sample.netInRate)} · OUT ${formatRate(sample.netOutRate)}"
}

internal fun sparkSampleValue(sample: Sample, readout: SparkReadout): String = when (readout) {
    SparkReadout.Memory -> sparkMemoryValue(sample)
    else -> sparkCpuValue(sample)
}

/**
 * The bubble text. "(no change)" is kept only when the whole line, including the time, fits [limit]; a long memory
 * line drops it. A missing sample stays "no sample".
 */
internal fun sparkReadoutLine(value: String, held: Boolean, time: String?, limit: Int = 42): String {
    val base = if (held && value != "no sample") {
        val withHeld = "$value (no change)"
        val extra = if (time.isNullOrBlank()) 0 else 3 + time.length
        if (withHeld.length + extra <= limit) withHeld else value
    } else value
    return if (time.isNullOrBlank()) base else "$base · $time"
}

/** Local `HH:mm:ss`, or the date as well when [times] spans midnight in [zone]. */
internal fun sparkClock(times: List<Instant>?, index: Int, zone: ZoneId = ZoneId.systemDefault()): String? {
    if (times == null || index !in times.indices) return null
    val days = times.asSequence().map { it.atZone(zone).toLocalDate() }.toSet()
    val pattern = if (days.size > 1) "yyyy-MM-dd HH:mm:ss" else "HH:mm:ss"
    return DateTimeFormatter.ofPattern(pattern).withZone(zone).format(times[index])
}

/** Exact gauge value, its detail, and the live samples' minimum, average and maximum when a series is passed. */
internal fun gaugeExact(value: Double?): String =
    value?.takeIf { it.isFinite() && it >= 0.0 }?.let { String.format(Locale.ROOT, "%.1f %%", it * 100) } ?: "—"

internal fun gaugeStatsLine(series: Series, metric: (Sample) -> Double?): String? {
    val values = series.samples.mapNotNull { metric(it)?.takeIf { v -> v.isFinite() && v >= 0.0 } }
    if (values.isEmpty()) return null
    fun one(v: Double) = String.format(Locale.ROOT, "%.1f %%", v * 100)
    return "Last ${values.size} samples: min ${one(values.min())} · avg ${one(values.average())} · max ${one(values.max())}"
}

internal fun gaugeHoverText(value: Double?, detail: String?, series: Series?, metric: (Sample) -> Double?): String {
    val head = if (detail.isNullOrBlank()) gaugeExact(value) else "${gaugeExact(value)} · $detail"
    val stats = series?.let { gaugeStatsLine(it, metric) } ?: return head
    return "$head\n$stats"
}

/**
 * The indexes a sparkline draws a point at. A sample the server had not refreshed ([held]) repeats the one before it,
 * and a point there would draw a flat step; the line runs straight through it instead. The first sample after a gap
 * and the newest sample are always drawn, so a line never starts late or stops short of the right edge.
 */
internal fun sparkPoints(values: List<Double?>, held: List<Boolean>?): List<Int> {
    val points = ArrayList<Int>(values.size)
    var open = false
    values.forEachIndexed { i, v ->
        if (v == null || !v.isFinite() || v < 0.0) { open = false; return@forEachIndexed }
        if (open && i < values.lastIndex && held?.getOrNull(i) == true) return@forEachIndexed
        points += i; open = true
    }
    return points
}

/**
 * Live trend line: auto-scaled, smoothed, gradient fill, gaps for missing samples, pulsing head.
 *
 * A new sample is taken in as one calm motion: the line slides one step to the left while the vertical scale eases to
 * its new value (the scale used to snap, so a new peak made the whole line jump). [stamp] identifies the newest sample
 * (its time); with it every recorded sample slides, also one whose value equals the last. [held] marks samples that
 * repeat the one before because the server had not refreshed; the line runs straight through them.
 *
 * [ease] eases the scale; it defaults to [pulse], so table rows (pulse = false) apply a new scale at once. The scale is
 * read where the paths are built, and an eased scale rebuilds them on every frame of the settle (no per-frame allocation for rows): a table
 * row rebuilds its paths once per new sample. The strip and the inspector pass ease = true.
 */
@Composable fun Sparkline(values: List<Double?>, modifier: Modifier = Modifier, color: Color? = null, floor: Double = .05, ceiling: Double = 1.0, grid: Boolean = true, pulse: Boolean = true,
    held: List<Boolean>? = null, stamp: Any? = null, ease: Boolean = pulse, times: List<Instant>? = null, label: ((Int) -> String)? = null, hover: SparkHover? = null) {
    val last = values.lastOrNull { it != null }
    val tint = color ?: ramp(last ?: 0.0)
    val reduce = LocalReduceMotion.current
    val phase = LocalMotionPhase.current
    val pulsing = pulse && !reduce && phase.active.value
    val shift = remember { Animatable(0f) }
    val count = values.size
    LaunchedEffect(count, stamp ?: values.lastOrNull()) { if (!reduce && count > 2) { shift.snapTo(1f); shift.animateTo(0f, tween(SPARK_SETTLE_MS, easing = LinearOutSlowInEasing)) } else shift.snapTo(0f) }
    // The eased quantity is the gain (height per unit), not the ceiling: a point's height is proportional to the gain, so
    // the line moves evenly. Easing the ceiling made most of the movement happen in the first few frames.
    val targetGain = (1.0 / sparkTop(values, floor, ceiling)).toFloat()
    val eased by animateFloatAsState(targetGain, if (reduce || !ease) snap() else tween(SPARK_SETTLE_MS, easing = FastOutSlowInEasing))
    // Without the ease the animated value is not read at all, so its one-frame snap cannot rebuild the paths a second time.
    val gain = if (reduce || !ease) targetGain else eased
    // The value that just left a full buffer: drawn one step off the left edge so the slide has no gap there.
    val rolled = remember { object { var seen: List<Double?> = emptyList(); var lead: Double? = null } }
    if (rolled.seen != values) { rolled.lead = sparkLead(rolled.seen, values); rolled.seen = values }
    val lead = rolled.lead
    // Hover lives on its own state. This function never reads it, so a pointer move does not rebuild the paths below.
    val owned = remember { SparkHover() }
    val cursor = hover ?: owned
    Box(modifier) {
        Box(Modifier.matchParentSize().clipToBounds()) {
            SparklineBody(values, tint, gain, grid, pulsing, held, lead, shift)
            SparkHoverMarks(cursor, values, gain, tint, shift)
        }
        if (label != null) SparkReadout(cursor, count, times, held, label)
        // Above the line, so the hit is this overlay and not the hairline drawn on top of the path.
        if (hover == null) Box(Modifier.matchParentSize().sparkTrack(owned))
    }
}

/** The cached stroke and fill. It does not read the pointer, so hover cannot rebuild these paths. */
@Composable private fun SparklineBody(values: List<Double?>, tint: Color, gain: Float, grid: Boolean, pulsing: Boolean, held: List<Boolean>?, lead: Double?, shift: Animatable<Float, AnimationVector1D>) {
    val tick = LocalSparkBodyCompositions.current
    SideEffect { if (tick != null) tick.intValue = tick.intValue + 1 }
    val count = values.size
    val phase = LocalMotionPhase.current
    Box(Modifier.fillMaxSize().drawWithCache {
        val unit = gain.toDouble()
        val n = (count - 1).coerceAtLeast(1)
        val step = size.width / n
        val pad = 2.dp.toPx()
        val h = size.height - pad * 2
        fun y(v: Double) = pad + (h * (1 - (v * unit).coerceIn(0.0, 1.0))).toFloat()
        val line = Path(); val area = Path()
        var head: Offset? = null; var start: Offset? = null; var prev: Offset? = null
        fun closeArea() { val s = start; val e = prev; if (s != null && e != null) { area.lineTo(e.x, size.height); area.lineTo(s.x, size.height); area.close() } }
        fun point(p: Offset) {
            val o = prev
            if (o == null) { line.moveTo(p.x, p.y); area.moveTo(p.x, size.height); area.lineTo(p.x, p.y); start = p }
            else { val mid = (o.x + p.x) / 2; line.cubicTo(mid, o.y, mid, p.y, p.x, p.y); area.cubicTo(mid, o.y, mid, p.y, p.x, p.y) }
            prev = p; head = p
        }
        var expected = 0
        if (lead != null && count >= 2 && values.first()?.let { it.isFinite() && it >= 0.0 } == true) point(Offset(-step, y(lead)))
        for (i in sparkPoints(values, held)) {
            // A missing sample between two drawn ones breaks the line; a held one does not.
            if (i > expected && (expected until i).any { values[it]?.let { v -> v.isFinite() && v >= 0.0 } != true }) { closeArea(); prev = null; start = null }
            point(Offset(if (count == 1) size.width else step * i, y(values[i]!!)))
            expected = i + 1
        }
        closeArea()
        val stroke = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val fill = Brush.verticalGradient(listOf(tint.copy(alpha = .32f), tint.copy(alpha = .04f)))
        val gridColor = Tech.Edge.copy(alpha = .55f)
        onDrawBehind {
            if (grid) { drawLine(gridColor, Offset(0f, size.height - .5f), Offset(size.width, size.height - .5f), 1f); drawLine(gridColor.copy(alpha = .25f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f) }
            val glowAlpha = if (!pulsing) 0.2f else {
                val t = ((phase.pulse.floatValue - 0.15f) / 0.45f).coerceIn(0f, 1f)
                0.10f + 0.28f * t
            }
            translate(left = shift.value * step) {
                drawPath(area, fill); drawPath(line, tint, style = stroke)
                head?.let { drawCircle(tint, 5.dp.toPx(), it, alpha = glowAlpha); drawCircle(tint, 2.dp.toPx(), it) }
            }
        }
    })
}

/** Pointer tracking that does not take the click. Press and Release stay with the row that selects a guest. */
private fun Modifier.sparkTrack(cursor: SparkHover): Modifier =
    onPointerEvent(PointerEventType.Enter) { event ->
        cursor.x = event.changes.firstOrNull()?.position?.x
    }.onPointerEvent(PointerEventType.Move) { event ->
        cursor.x = event.changes.firstOrNull()?.position?.x
    }.onPointerEvent(PointerEventType.Exit) { cursor.x = null }

/** Hairline and ring dot. Both are drawn from the hover state, so a pointer move does not rebuild the line. */
@Composable private fun SparkHoverMarks(cursor: SparkHover, values: List<Double?>, gain: Float, tint: Color, shift: Animatable<Float, AnimationVector1D>) {
    val count = values.size
    Box(Modifier.fillMaxSize().drawBehind {
        val x = cursor.x ?: return@drawBehind
        if (count <= 0 || !(size.width > 0f)) return@drawBehind
        val index = sparkNearestIndex(x, size.width, count) ?: return@drawBehind
        val step = if (count <= 1) size.width else size.width / (count - 1)
        val hair = (if (count <= 1) size.width else step * index + shift.value * step).coerceIn(0f, size.width)
        drawLine(Tech.Ink.copy(alpha = .5f), Offset(hair, 0f), Offset(hair, size.height), 1f)
        val sample = values.getOrNull(index)
        if (sample != null && sample.isFinite() && sample >= 0.0) {
            val pad = 2.dp.toPx()
            val y = pad + ((size.height - pad * 2) * (1 - (sample * gain.toDouble()).coerceIn(0.0, 1.0))).toFloat()
            val at = Offset(hair, y)
            val radius = 3.dp.toPx()
            drawCircle(Tech.Panel, radius, at)
            drawCircle(tint, radius, at, style = Stroke(1.25.dp.toPx()))
        }
    })
}

/**
 * The only composable that reads the hover position. Past 60% of the width the bubble sits to the left of the pointer.
 * It stays inside the window, and inside the chart when there is no room above it (a chart that fills the scene).
 */
@Composable private fun BoxScope.SparkBubble(cursor: SparkHover, count: Int, textAt: (Int) -> String) {
    val x = cursor.x ?: return
    BoxWithConstraints(Modifier.matchParentSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val index = sparkNearestIndex(x, widthPx, count) ?: return@BoxWithConstraints
        val text = textAt(index)
        var bubble by remember { mutableStateOf(IntSize.Zero) }
        var chartAt by remember { mutableStateOf(Offset.Zero) }
        val window = LocalWindowInfo.current.containerSize
        Box(Modifier.matchParentSize().onGloballyPositioned { chartAt = it.positionInWindow() }) {
            val windowW = window.width
            val limit = if (windowW > 0) windowW.toFloat() else widthPx
            val originX = if (windowW > 0) chartAt.x else 0f
            var left = if (x > widthPx * .6f) x - 8f - bubble.width else x + 8f
            if (bubble.width > 0) {
                if (originX + left + bubble.width > limit) left = limit - originX - bubble.width
                if (originX + left < 0f) left = -originX
            }
            val top = if (window.height > 0 && bubble.height > 0 && chartAt.y >= bubble.height + 2) -bubble.height.toFloat() else 4f
            Surface(Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }.onSizeChanged { bubble = it },
                color = Tech.Raised, shape = Tech.Control, border = BorderStroke(1.dp, Tech.Edge),
                shadowElevation = if (LocalMotion.current.lite) 0.dp else 4.dp) {
                Text(text, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), color = Tech.Ink, fontSize = 10.sp, fontFamily = Tech.Mono, maxLines = 1)
            }
        }
    }
}

@Composable private fun BoxScope.SparkReadout(cursor: SparkHover, count: Int, times: List<Instant>?, held: List<Boolean>?, label: (Int) -> String) {
    SparkBubble(cursor, count) { index -> sparkReadoutLine(label(index), held?.getOrNull(index) == true, sparkClock(times, index)) }
}

@Composable fun Sparkline(series: Series, modifier: Modifier = Modifier, pulse: Boolean = true, grid: Boolean = true, floor: Double = .05, ease: Boolean = pulse,
    readout: SparkReadout = SparkReadout.Cpu, hover: SparkHover? = null, metric: (Sample) -> Double? = { it.cpu }) =
    Sparkline(series.samples.map(metric), modifier, floor = floor, grid = grid, pulse = pulse, held = series.samples.map { it.held }, stamp = series.samples.lastOrNull()?.at, ease = ease,
        times = series.samples.map { it.at }, label = if (readout == SparkReadout.None) null else { i -> sparkSampleValue(series.samples[i], readout) }, hover = hover)

/**
 * Inbound (green) and outbound (amber) rates on one shared scale, at least 1 KiB/s tall. The two lines are drawn in
 * bytes per second against the same eased ceiling, so a burst rescales both together and smoothly.
 */
@Composable fun NetSparkline(series: Series, modifier: Modifier = Modifier, pulse: Boolean = true, ease: Boolean = pulse) {
    val ins = series.samples.map { it.netInRate }; val outs = series.samples.map { it.netOutRate }
    val top = sparkTop(ins + outs, floor = 1024.0, ceiling = Double.MAX_VALUE)
    val held = series.samples.map { it.held }; val stamp = series.samples.lastOrNull()?.at
    val cursor = remember { SparkHover() }
    Box(modifier) {
        Sparkline(ins, Modifier.matchParentSize(), color = Tech.Green, floor = top, ceiling = top, pulse = pulse, held = held, stamp = stamp, ease = ease, hover = cursor)
        Sparkline(outs, Modifier.matchParentSize(), color = Tech.Amber, floor = top, ceiling = top, grid = false, pulse = pulse, held = held, stamp = stamp, ease = ease, hover = cursor)
        Box(Modifier.matchParentSize().sparkTrack(cursor))
        NetSparkReadout(cursor, series)
    }
}

/** One bubble for both rates. It is the only reader of the shared hover, so the two lines are not rebuilt as the pointer moves. */
@Composable private fun BoxScope.NetSparkReadout(cursor: SparkHover, series: Series) {
    val samples = series.samples
    val times = remember(samples) { samples.map { it.at } }
    SparkBubble(cursor, samples.size) { index ->
        val sample = samples[index]
        sparkReadoutLine(sparkNetValue(sample), sample.held, sparkClock(times, index))
    }
}

/** How many of the newest refreshed samples a ranking averages: about a minute at the server's 10-second refresh. */
internal const val STEADY_SAMPLES = 6

/** Mean of [metric] over the newest [STEADY_SAMPLES] refreshed samples, so a ranking follows the trend and not one reading. */
internal fun steadyValue(series: Series?, fallback: Double?, metric: (Sample) -> Double?): Double? {
    val recent = series?.samples.orEmpty().asReversed().asSequence().filter { !it.held }.mapNotNull(metric).filter { it.isFinite() && it >= 0.0 }.take(STEADY_SAMPLES).toList()
    return if (recent.isEmpty()) fallback?.takeIf { it.isFinite() && it >= 0.0 } else recent.average()
}

/**
 * A ranking that holds still. An entry moves above the one before it only when its score leads by more than [margin]
 * (relative) and by more than [floor] (absolute); otherwise the order from the last call stands. New entries enter by
 * score. Without this, a "top five" re-sorts on every reading and its rows swap places every few seconds.
 */
internal class SteadyRanking<K>(private val margin: Double = .15, private val floor: Double = .01) {
    private var order: List<K> = emptyList()
    fun rank(scores: Map<K, Double>): List<K> {
        val kept = order.filter { it in scores }
        val ranked = (kept + scores.keys.filter { it !in kept }.sortedByDescending { scores.getValue(it) }).toMutableList()
        for (i in 1 until ranked.size) {
            var j = i
            while (j > 0) {
                val mine = scores.getValue(ranked[j]); val above = scores.getValue(ranked[j - 1])
                // A newcomer has no place to keep: it is placed by plain score.
                val leads = if (ranked[j] !in kept) mine > above else mine > above * (1 + margin) && mine - above > floor
                if (!leads) break
                val moved = ranked[j]; ranked[j] = ranked[j - 1]; ranked[j - 1] = moved; j--
            }
        }
        order = ranked
        return ranked
    }
}

/** Sorts on a live value. They rank by the last minute and hold still, like the top-five lists ([SteadyGuestOrder]). */
internal val steadySorts = setOf("CPU", "Memory", "Net")

/**
 * A guest's score for a live sort: the mean of its newest refreshed samples ([steadyValue]). A stopped guest or a
 * template shows a dash and scores -1, so it sorts as missing. Memory is in bytes and net is inbound plus outbound.
 */
internal fun steadySortScore(sort: String, guest: Guest, series: Series?): Double {
    if (guest.template || !guest.status.equals("running", true)) return -1.0
    return when (sort) {
        "CPU" -> steadyValue(series, guest.metrics?.cpuFraction) { it.cpu }
        "Memory" -> steadyValue(series, guest.metrics?.memoryBytes?.toDouble()) { it.mem?.toDouble() }
        "Net" -> steadyValue(series, null) { s -> if (s.netInRate == null && s.netOutRate == null) null else (s.netInRate ?: 0.0) + (s.netOutRate ?: 0.0) }
        else -> null
    } ?: -1.0
}

/** The lead a row needs, beyond the 15 % margin, before it passes the row above: one CPU point, 64 MiB, 1 KiB/s. */
private fun steadySortFloor(sort: String) = when (sort) { "Memory" -> 64.0 * 1048576; "Net" -> 1024.0; else -> .01 }

/**
 * Table order for a sort. ID, name and uptime are plain comparators. CPU, memory and net use one [SteadyRanking] per
 * group (node or remote): a row passes the one above only when it clearly leads, so two guests trading a few percent
 * stop swapping rows on every tick. Ascending is the descending order reversed. Changing the sort starts fresh.
 */
internal class SteadyGuestOrder {
    private var sort: String? = null
    private val rankings = HashMap<Any, SteadyRanking<ResourceId>>()
    fun order(group: Any, sort: String, descending: Boolean, guests: List<Guest>, history: FleetHistory): List<Guest> {
        if (sort !in steadySorts) return guests.sortedWith(guestComparator(sort, history).let { if (descending) it.reversed() else it })
        if (sort != this.sort) { rankings.clear(); this.sort = sort }
        val ranking = rankings.getOrPut(group) { SteadyRanking(floor = steadySortFloor(sort)) }
        // Entries with equal scores enter in ID order.
        val scores = LinkedHashMap<ResourceId, Double>()
        guests.sortedBy { it.id }.forEach { scores[it.resource] = steadySortScore(sort, it, history.guests[it.resource]) }
        val byResource = guests.associateBy { it.resource }
        val ranked = ranking.rank(scores).mapNotNull(byResource::get)
        return if (descending) ranked else ranked.asReversed()
    }
}

/** Compact per-second rate for table cells: 1.2M, 340K, 12B. */
internal fun shortRate(value: Double?): String = when {
    value == null || !value.isFinite() || value < 0 -> "—"
    value >= 1048576 -> String.format(Locale.ROOT, "%.1fM", value / 1048576)
    value >= 1024 -> String.format(Locale.ROOT, "%.0fK", value / 1024)
    else -> String.format(Locale.ROOT, "%.0fB", value)
}

@Composable fun RateLine(label: String, value: Double?, tint: Color, fontSize: TextUnit = 10.sp) = Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(label, Modifier.width(26.dp), fontSize = fontSize, lineHeight = fontSize * 1.2f, fontFamily = Tech.Mono, color = tint, maxLines = 1)
    Text(formatRate(value), fontSize = fontSize, lineHeight = fontSize * 1.2f, fontFamily = Tech.Mono, color = Tech.Ink.copy(alpha = .85f), maxLines = 1)
}

/** 240° gauge that eases to new values without overshoot, number in the centre, caption and detail below. Hover names the exact value. */
@Composable fun ArcGauge(value: Double?, caption: String, detail: String? = null, size: Dp = 84.dp, modifier: Modifier = Modifier, series: Series? = null, metric: (Sample) -> Double? = { it.cpu }) {
    var hovered by remember { mutableStateOf(false) }
    val setHovered = remember<(Boolean) -> Unit> { { hovered = it } }
    Column(modifier.onPointerEvent(PointerEventType.Enter) { setHovered(true) }
        .onPointerEvent(PointerEventType.Exit) { setHovered(false) },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.size(size)) {
            ArcGaugeFace(value, caption, size)
            GaugeTip(hovered, value, detail, series, metric)
        }
        detail?.let { Text(it, fontSize = 10.sp, lineHeight = 12.sp, color = Tech.Ink.copy(alpha = .8f), fontFamily = Tech.Mono, maxLines = 1) }
    }
}

/** The arc, the number and the caption. Hover is not a parameter, so the face is left alone when the pointer enters. */
@Composable private fun ArcGaugeFace(value: Double?, caption: String, size: Dp) {
    val reduce = LocalReduceMotion.current
    val shown = value?.takeIf { it.isFinite() && it >= 0.0 }
    // It used to spring with a bounce, so the needle overshot and came back on every reading.
    val v by animateFloatAsState((shown ?: 0.0).toFloat().coerceIn(0f, 1f), if (reduce) snap() else tween(SPARK_SETTLE_MS, easing = FastOutSlowInEasing))
    val tint by animateColorAsState(ramp(shown ?: 0.0), tween(if (reduce) 0 else 300))
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().drawWithCache {
            val w = 6.dp.toPx(); val inset = w / 2 + 2.dp.toPx()
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val track = Stroke(w, cap = StrokeCap.Round); val glow = Stroke(w * 2.2f, cap = StrokeCap.Round)
            onDrawBehind {
                drawArc(Tech.Edge, 150f, 240f, false, Offset(inset, inset), arcSize, style = track)
                // tick marks every 10%
                for (t in 0..10) {
                    val a = Math.toRadians(150.0 + 24.0 * t); val r1 = this.size.minDimension / 2 - inset - w; val r2 = r1 - 3.dp.toPx()
                    drawLine(Tech.Edge, center + Offset((r1 * Math.cos(a)).toFloat(), (r1 * Math.sin(a)).toFloat()), center + Offset((r2 * Math.cos(a)).toFloat(), (r2 * Math.sin(a)).toFloat()), 1f)
                }
                if (shown != null) {
                    drawArc(tint.copy(alpha = .18f), 150f, 240f * v, false, Offset(inset, inset), arcSize, style = glow)
                    drawArc(tint, 150f, 240f * v, false, Offset(inset, inset), arcSize, style = track)
                }
            }
        })
        if (shown == null) Text("—", color = Tech.Muted, fontFamily = Tech.Mono) else AnimatedNumber(shown, fontSize = 16.sp)
        // Caption sits in the open bottom of the arc.
        Text(caption.uppercase(), Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp), fontSize = 9.sp, lineHeight = 11.sp, color = Tech.Muted, fontFamily = Tech.Mono, letterSpacing = 1.sp, maxLines = 1)
    }
}

/** Immediate readout. HelpTip waits 450 ms, which is too slow for a value under the pointer. */
@Composable private fun BoxScope.GaugeTip(hovered: Boolean, value: Double?, detail: String?, series: Series?, metric: (Sample) -> Double?) {
    if (!hovered) return
    Box(Modifier.matchParentSize().wrapContentSize(Alignment.TopCenter, unbounded = true)) {
        Surface(color = Tech.Raised, shape = Tech.Control, border = BorderStroke(1.dp, Tech.Edge),
            shadowElevation = if (LocalMotion.current.lite) 0.dp else 4.dp) {
            Text(gaugeHoverText(value, detail, series, metric), Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                color = Tech.Ink, fontSize = 10.sp, fontFamily = Tech.Mono, textAlign = TextAlign.Center)
        }
    }
}

/** Animated proportional bar; colours follow running / stopped / template / other. */
@Composable fun SplitBar(values: List<Int>, modifier: Modifier = Modifier, colors: List<Color> = listOf(Tech.Green, Tech.Muted.copy(alpha = .55f), Tech.Amber, Tech.Ink.copy(alpha = .7f))) {
    Row(modifier.height(4.dp).background(Tech.Edge.copy(alpha = .4f)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        values.forEachIndexed { i, n ->
            val w by animateFloatAsState(n.toFloat(), tween(if (LocalReduceMotion.current) 0 else 350))
            if (w > 0.01f) Box(Modifier.weight(w).fillMaxHeight().background(colors[i % colors.size]))
        }
    }
}

/** Thin usage bar for memory and similar fractions. */
@Composable fun UsageBar(fraction: Double?, modifier: Modifier = Modifier) {
    val f by animateFloatAsState((fraction ?: 0.0).toFloat().coerceIn(0f, 1f), tween(if (LocalReduceMotion.current) 0 else 350))
    val tint = ramp(fraction ?: 0.0)
    Box(modifier.height(4.dp).background(Tech.Edge.copy(alpha = .5f))) { if (fraction != null) Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(tint.copy(alpha = .85f))) }
}

@Composable fun CompactSearchField(value: String, onValue: (String) -> Unit, placeholder: String = "Search name · node · ID · tag", label: String = "Search guests") {
    val focus = LocalSearchFocus.current
    BasicTextField(value, onValue, singleLine = true, textStyle = TextStyle(color = Tech.Ink, fontSize = 13.sp), cursorBrush = SolidColor(Tech.Amber),
        // The placeholder is drawn text, not a label: a screen reader gets the field's name from here.
        modifier = Modifier.semantics { contentDescription = label }.focusRequester(focus).onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onValue(""); true } else false }
            .fillMaxWidth().height(30.dp).border(1.dp, Tech.Edge, Tech.Control).background(Tech.Black.copy(alpha = .4f)),
        decorationBox = { inner ->
            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Canvas(Modifier.size(12.dp)) { drawCircle(Tech.Muted, size.minDimension * .36f, Offset(size.width * .42f, size.height * .42f), style = Stroke(1.4f)); drawLine(Tech.Muted, Offset(size.width * .68f, size.height * .68f), Offset(size.width, size.height), 1.6f) }
                Box(Modifier.weight(1f)) { if (value.isEmpty()) Text(placeholder, color = Tech.Muted, fontSize = 12.sp, maxLines = 1); inner() }
                Text("Ctrl F", fontSize = 9.sp, color = Tech.Muted.copy(alpha = .7f), fontFamily = Tech.Mono)
            }
        })
}

/** Joined segmented control with live counts. */
@Composable fun SegmentedFilter(items: List<Pair<String, Int>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.height(30.dp).border(1.dp, Tech.Edge, Tech.Control)) {
        items.forEachIndexed { i, (name, count) ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Tech.Edge))
            val active = selected == name
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val bg by animateColorAsState(if (active) Tech.Amber.copy(alpha = .16f) else if (hovered) Tech.Raised else Color.Transparent, LocalMotion.current.fade(MotionDur.Quick))
            Row(Modifier.fillMaxHeight().background(bg).hoverable(interaction).clickable(interactionSource = interaction, indication = null) { onSelect(name) }.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, fontSize = 12.sp, color = if (active) Tech.Amber else Tech.Ink)
                Text("$count", fontSize = 10.sp, fontFamily = Tech.Mono, color = if (active) Tech.Amber else Tech.Muted)
            }
        }
    }
}

@Composable fun TableHeader(label: String, onSort: () -> Unit) { Text(label, Modifier.clickable(onClick = onSort), fontSize = 10.sp, color = Tech.Muted, fontFamily = Tech.Mono) }

/** Header cell: label, active sort arrow, click to sort. */
@Composable fun SortHeader(label: String, key: String, sort: String, descending: Boolean, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start, onSort: (String) -> Unit) {
    val active = sort == key
    Text(label + if (active) (if (descending) " ↓" else " ↑") else "", modifier.clickable { onSort(key) }, fontSize = 10.sp, letterSpacing = 1.sp,
        color = if (active) Tech.Amber else Tech.Muted, fontFamily = Tech.Mono, textAlign = align, maxLines = 1)
}

@Composable fun Shimmer(modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val phase = LocalMotionPhase.current
    if (reduce || !phase.active.value) { Box(modifier.height(2.dp).background(Tech.Amber.copy(alpha = .35f))); return }
    Box(modifier.height(2.dp).drawWithCache {
        val brush = Brush.horizontalGradient(listOf(Color.Transparent, Tech.Amber.copy(alpha = .8f), Color.Transparent))
        onDrawBehind {
            drawRect(Tech.Amber.copy(alpha = .12f))
            val travel = phase.shimmer.floatValue * 1.6f - 0.3f
            translate(left = size.width * travel) { drawRect(brush, size = Size(size.width * 0.4f, size.height)) }
        }
    })
}

@Composable fun StatusGlyph(outcome: TaskOutcome) {
    val reduce = LocalReduceMotion.current
    val phase = LocalMotionPhase.current
    val spinning = !reduce && outcome == TaskOutcome.Running && phase.active.value
    Canvas(Modifier.size(16.dp)) {
        val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
        when (outcome) {
            TaskOutcome.Running -> {
                val angle = if (spinning) phase.spin.floatValue else 0f
                drawCircle(Tech.Amber.copy(alpha = .2f), style = stroke); drawArc(Tech.Amber, angle, 100f, false, style = stroke)
            }
            TaskOutcome.Succeeded -> { val p = Path().apply { moveTo(size.width * .18f, size.height * .52f); lineTo(size.width * .42f, size.height * .76f); lineTo(size.width * .84f, size.height * .26f) }; drawPath(p, Tech.Green, style = stroke) }
            TaskOutcome.Unsuccessful -> { val i = size.width * .22f; drawLine(Tech.Red, Offset(i, i), Offset(size.width - i, size.height - i), stroke.width, StrokeCap.Round); drawLine(Tech.Red, Offset(size.width - i, i), Offset(i, size.height - i), stroke.width, StrokeCap.Round) }
            else -> { drawCircle(Tech.Muted, style = stroke); drawCircle(Tech.Muted, 1.2.dp.toPx(), center) }
        }
    }
}

/** Guests that are not running, stopped or a template (paused, suspended, unknown): the rest of the total. */
internal fun otherGuests(total: FleetTotals) = (total.guests - total.running - total.stopped - total.templates).coerceAtLeast(0)

/** The strip's split under the guest count. It adds up to the count: "other" appears when a guest is in none of the three. */
internal fun guestSplitText(total: FleetTotals): String =
    "${total.running} run · ${total.stopped} stop · ${total.templates} tpl" + otherGuests(total).let { if (it > 0) " · $it other" else "" }

internal fun syncedAgo(updatedAt: java.time.Instant?, now: java.time.Instant): String =
    updatedAt?.let { "synced ${java.time.Duration.between(it, now).seconds.coerceAtLeast(0)}s ago" } ?: "awaiting data"

@Composable private fun SyncedAgeText(updatedAt: java.time.Instant?) {
    val now = LocalNow.current ?: java.time.Instant.now()
    Text(syncedAgo(updatedAt, now), Modifier.fillMaxWidth(), fontSize = 10.sp, color = Tech.Muted, fontFamily = Tech.Mono, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
}

/** Live link pill: pulsing dot + mode. Click toggles live refresh. */
@Composable fun LivePill(state: WorkspaceState, onToggle: () -> Unit) {
    val reduce = LocalReduceMotion.current
    val phase = LocalMotionPhase.current
    val backoff = state.liveBackoffSeconds
    val tint = when { backoff != null || state.stale -> Tech.Amber; state.live -> Tech.Green; else -> Tech.Muted }
    val pulsing = !reduce && phase.active.value && state.live && backoff == null
    Row(Modifier.heightIn(min = 22.dp).border(1.dp, tint.copy(alpha = .6f), Tech.Control).clickable(onClick = onToggle).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(8.dp)) {
            val glow = if (pulsing) phase.pulse.floatValue else 0.2f
            drawCircle(tint, size.minDimension, alpha = glow); drawCircle(tint, size.minDimension * .3f)
        }
        Text(when { backoff != null -> "RETRY ${backoff}s"; state.live -> "LIVE ${liveIntervalLabel(state.liveIntervalSeconds).uppercase()}"; else -> "PAUSED" }, fontSize = 10.sp, fontFamily = Tech.Mono, color = tint, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun StripCell(label: String, modifier: Modifier = Modifier, identity: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxHeight().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically)) {
        Text(label, if (identity) Modifier.identity() else Modifier, fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = 1.2.sp, color = Tech.Muted, fontFamily = Tech.Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        content()
    }
}
@Composable private fun StripDivider() = Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 10.dp).background(Tech.Edge))

/** One-line fleet telemetry: link, nodes, guests split, cluster CPU, guest network, busiest guest, memory, actions. Wraps to two lines under 1450dp. */
@Composable fun TelemetryStrip(state: WorkspaceState, workspace: Workspace, onDisconnect: () -> Unit) {
    val total = state.overview?.let(::fleetTotals)
    val prefs = remember { MotionPreferences() }
    val reduce = LocalReduceMotion.current
    val phase = LocalMotionPhase.current
    val spinning = !reduce && phase.active.value && state.loading
    val host = if (state.demo) "DEMO · SAMPLE DATA" else state.address.substringAfter("://").trimEnd('/')
    // sp grows with the accessibility text size; dp does not. Widen the tight cells, and stop before the 960 and 1920 strips gain a line.
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
    fun textWidth(base: Dp, cap: Float) = base * fontScale.coerceAtMost(cap)
    val link: @Composable RowScope.() -> Unit = {
        StripCell("LINK · $host", Modifier.width(textWidth(220.dp, 1.08f)), identity = true) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LivePill(state) { val next = !state.live; workspace.setLive(next); prefs.put("live", next) }
                Box(Modifier.weight(1f)) { SyncedAgeText(state.updatedAt) }
            }
        }
    }
    val counts: @Composable RowScope.() -> Unit = {
        if (total != null) {
            StripCell(if (state.overview?.remotes.isNullOrEmpty()) "NODES" else "REMOTES", Modifier.width(textWidth(76.dp, 1.25f))) {
                AnimatedCount(if (state.overview?.remotes.isNullOrEmpty()) total.nodes else state.overview!!.remotes.size)
            }
            StripDivider()
            StripCell("GUESTS", Modifier.width(textWidth(230.dp, 1.12f))) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AnimatedCount(total.guests)
                    Text(guestSplitText(total), fontSize = 10.sp, color = Tech.Muted, fontFamily = Tech.Mono,
                        modifier = Modifier.weight(1f).padding(bottom = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
                }
                SplitBar(listOf(total.running, total.stopped, total.templates, otherGuests(total)), Modifier.fillMaxWidth())
            }
        }
    }
    val usage: @Composable RowScope.() -> Unit = {
        if (total?.cpuFraction != null) {
            StripCell("CLUSTER CPU", Modifier.weight(1f).widthIn(min = textWidth(160.dp, 1.15f))) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Sparkline(state.history.cluster, Modifier.opensLens { clusterLens(LiveMetric.Cpu) }.weight(1f).height(26.dp))
                    AnimatedNumber(total.cpuFraction!!, Modifier.width(textWidth(54.dp, 1.5f)), fontSize = 16.sp)
                }
            }
            StripDivider()
        }
        if (total != null) {
            val net = state.history.cluster.samples.lastOrNull()
            StripCell("GUEST NETWORK", Modifier.weight(1f).widthIn(min = textWidth(160.dp, 1.2f))) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NetSparkline(state.history.cluster, Modifier.opensLens { clusterLens(LiveMetric.Network) }.weight(1f).height(26.dp))
                    Column(Modifier.width(textWidth(104.dp, 1.35f)), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        RateLine("IN", net?.netInRate, Tech.Green)
                        RateLine("OUT", net?.netOutRate, Tech.Amber)
                    }
                }
            }
            StripDivider()
            // The busiest guest by its last minute, and it keeps the title until another clearly leads: two guests
            // with similar load used to swap the name here on every reading.
            val busiest = remember(state.connectionEpoch) { SteadyRanking<ResourceId>() }
            val running = state.overview?.guests.orEmpty().filter { it.status == "running" && !it.template }
            val hot = busiest.rank(running.associate { it.resource to (steadyValue(state.history.guests[it.resource], it.metrics?.cpuFraction) { s -> s.cpu } ?: 0.0) })
                .firstOrNull()?.let { top -> running.firstOrNull { it.resource == top } }
            StripCell("BUSIEST GUEST", Modifier.width(textWidth(200.dp, 1.12f))) {
                if (hot == null) Text("—", fontSize = 13.sp, fontFamily = Tech.Mono, color = Tech.Muted)
                else {
                    val series = state.history.guests[hot.resource]
                    val cpu = latestCpu(series, hot.metrics?.cpuFraction)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(hot.name.ifBlank { "Guest ${hot.id}" }, Modifier.weight(1f).identity(), fontSize = 13.sp, fontFamily = Tech.Mono, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
                        if (cpu != null) AnimatedNumber(cpu, Modifier.width(textWidth(44.dp, 1.5f)), fontSize = 13.sp)
                    }
                    Sparkline(series ?: Series(), Modifier.opensLens { guestLens(hot, LiveMetric.Cpu) }.fillMaxWidth().height(10.dp), grid = false, pulse = false, ease = true)
                }
            }
            StripDivider()
        } else Spacer(Modifier.weight(1f))
        if (total?.memoryBytes != null) {
            val f = memoryFraction(total.memoryBytes, total.memoryLimitBytes)
            // The percentage is the first thing to drop: the bar still shows the fraction, and the gib line keeps its width.
            val showPercent = fontScale < 1.25f
            StripCell("MEMORY", Modifier.width(textWidth(190.dp, if (showPercent) 1.25f else 1.15f))) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${gib(total.memoryBytes)} / ${gib(total.memoryLimitBytes)} GiB", fontSize = 13.sp, fontFamily = Tech.Mono,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
                    if (showPercent) Text(percent(f), fontSize = 10.sp, fontFamily = Tech.Mono, color = ramp(f ?: 0.0), modifier = Modifier.padding(bottom = 2.dp), maxLines = 1, softWrap = false)
                }
                UsageBar(f, Modifier.fillMaxWidth())
            }
            StripDivider()
        }
    }
    val actions: @Composable RowScope.() -> Unit = {
        Row(Modifier.fillMaxHeight().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HelpTip("Sync now (F5)") {
                Box(Modifier.size(28.dp).border(1.dp, Tech.Edge, Tech.Control).clickable(enabled = (state.connected || state.demo) && !state.loading, onClick = workspace::refresh), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(14.dp)) {
                        val spin = if (spinning) phase.spin.floatValue else 0f
                        val st = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
                        drawArc(if (state.loading) Tech.Amber else Tech.Ink, spin + 30f, 280f, false, style = st)
                        val a = Math.toRadians((spin + 310f).toDouble()); val r = size.minDimension / 2
                        drawCircle(if (state.loading) Tech.Amber else Tech.Ink, 1.8.dp.toPx(), center + Offset((r * Math.cos(a)).toFloat(), (r * Math.sin(a)).toFloat()))
                    }
                }
            }
            var permissions by remember { mutableStateOf(false) }
            HelpTip("What this sign-in is allowed to do, feature by feature") { MiniAction("Permissions", enabled = state.connected || state.demo) { permissions = true } }
            if (permissions) TechDialog("Permissions", { permissions = false }, width = 860.dp, subtitle = "Effective privileges of the signed-in identity, read from the server", actions = {
                TechAction("Done", active = true) { permissions = false }
            }) { PermissionsPanel(state, workspace, framed = false) }
            MiniAction("Disconnect", enabled = state.connected || state.demo, onClick = onDisconnect)
        }
    }
    Surface(color = Tech.Panel, shape = Tech.Plate, border = BorderStroke(1.dp, Tech.Edge), modifier = Modifier.fillMaxWidth()) {
        val wideHeight = 76.dp * fontScale.coerceAtMost(1.2f)
        val narrowHeight = 72.dp * fontScale.coerceAtMost(1.2f)
        BoxWithConstraints {
            if (maxWidth >= 1450.dp) {
                Row(Modifier.fillMaxWidth().height(wideHeight), verticalAlignment = Alignment.CenterVertically) { link(); StripDivider(); counts(); StripDivider(); usage(); actions() }
            } else {
                Column {
                    Row(Modifier.fillMaxWidth().height(narrowHeight), verticalAlignment = Alignment.CenterVertically) { link(); StripDivider(); counts(); Spacer(Modifier.weight(1f)); actions() }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Tech.Edge))
                    Row(Modifier.fillMaxWidth().height(narrowHeight), verticalAlignment = Alignment.CenterVertically) { usage() }
                }
            }
        }
    }
}
