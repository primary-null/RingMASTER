@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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
/** Ctrl+1 to Ctrl+9 only; pages after the ninth (Schedules) have no number shortcut. */
internal fun shortcutPage(number: Int) = if (number in 1..9) ringPages.getOrNull(number - 1) else null

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

/**
 * A percentage that eases to new values. Monospaced digits with the "%" set smaller ([metricText]); its colour is the
 * tone of the value against [thresholds]. Give it a width and [align] End where digits must not shift as they change.
 */
@Composable fun AnimatedNumber(value: Double, modifier: Modifier = Modifier, fontSize: TextUnit = 15.sp, align: TextAlign = TextAlign.Start,
    thresholds: MetricThresholds = MetricThresholds.Load) {
    val v by animateFloatAsState(value.toFloat(), tween(if (LocalReduceMotion.current) 0 else 400))
    Text(metricText(percent(v.toDouble()), unitScaleFor(fontSize)), modifier, fontSize = fontSize, color = thresholds.tone(value).color, fontFamily = Tech.Mono, fontWeight = FontWeight.SemiBold,
        maxLines = 1, softWrap = false, textAlign = align)
}

@Composable fun AnimatedCount(value: Int, modifier: Modifier = Modifier, color: Color = Tech.Ink, fontSize: TextUnit = 18.sp) {
    val v by animateIntAsState(value, tween(if (LocalReduceMotion.current) 0 else 400))
    Text("$v", modifier, fontSize = fontSize, color = color, fontFamily = Tech.Mono, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
}

/** Below this many samples a series sparkline keeps this many slots, so a guest that just started draws a short line at the right instead of stretching two samples across. */
internal const val SPARK_MIN_SLOTS = 30

/** Index into [count] samples drawn right-aligned in max([count], [slots]) slots; null in the empty part on the left. */
internal fun sparkSlotIndex(x: Float, width: Float, count: Int, slots: Int): Int? {
    val n = maxOf(count, slots)
    val at = sparkNearestIndex(x, width, n) ?: return null
    val i = at - (n - count)
    return if (i < 0) null else i.coerceAtMost(count - 1)
}

/** Index ranges of missing samples (null, NaN or negative) that sit between, before or after drawn ones. */
internal fun sparkGaps(values: List<Double?>): List<IntRange> {
    val gaps = ArrayList<IntRange>()
    var start = -1
    values.forEachIndexed { i, v ->
        val missing = v == null || !v.isFinite() || v < 0.0
        if (missing && start < 0) start = i
        if (!missing && start >= 0) { gaps += start until i; start = -1 }
    }
    if (start >= 0) gaps += start until values.size
    return gaps
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
    held: List<Boolean>? = null, stamp: Any? = null, ease: Boolean = pulse, times: List<Instant>? = null, label: ((Int) -> String)? = null, hover: SparkHover? = null,
    slots: Int = 0, scaleLabel: ((Double) -> String)? = null, guides: List<Double> = emptyList(), stale: Boolean = false, peak: Boolean = false, scaleKind: AxisKind? = null) {
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
    // With a [scaleKind] the top is rounded up to a value worth naming (5, 10, 25, 50, 100 % or a round rate) and labelled.
    val rawTop = sparkTop(values, floor, ceiling)
    val axis = scaleKind?.let { remember(it, rawTop) { axisScale(it, rawTop, maxTicks = 4, headroom = 1.0) } }
    val targetGain = (1.0 / (axis?.top ?: rawTop)).toFloat()
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
    val scaleText = axis?.let { it.label(it.top) } ?: scaleLabel?.invoke(1.0 / targetGain)
    Box(modifier) {
        Box(Modifier.matchParentSize().clipToBounds()) {
            SparklineBody(values, tint, gain, grid, pulsing, held, lead, shift, slots, guides, stale, peak, scaleText)
            SparkHoverMarks(cursor, values, gain, tint, shift, slots)
        }
        if (label != null) SparkReadout(cursor, count, times, held, label, slots)
        // Above the line, so the hit is this overlay and not the hairline drawn on top of the path.
        if (hover == null) Box(Modifier.matchParentSize().sparkTrack(owned))
    }
}

/**
 * The cached stroke and fill. It does not read the pointer, so hover cannot rebuild these paths.
 *
 * Missing samples break the line and leave a dotted floor under the gap, so "no data" never reads as zero. [guides] are
 * faint dotted lines at threshold values; [scaleText] names the top of the scale in the top-left corner of a chart tall
 * enough to carry it; [peak] marks the window's largest sample; [stale] fades the line while the data is out of date.
 */
@Composable private fun SparklineBody(values: List<Double?>, tint: Color, gain: Float, grid: Boolean, pulsing: Boolean, held: List<Boolean>?, lead: Double?,
    shift: Animatable<Float, AnimationVector1D>, slots: Int = 0, guides: List<Double> = emptyList(), stale: Boolean = false, peak: Boolean = false, scaleText: String? = null) {
    val tick = LocalSparkBodyCompositions.current
    SideEffect { if (tick != null) tick.intValue = tick.intValue + 1 }
    val count = values.size
    val phase = LocalMotionPhase.current
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val labelColor = MetricColors.axis
    val plate = Tech.Panel.copy(alpha = .78f)
    Box(Modifier.fillMaxSize().drawWithCache {
        val unit = gain.toDouble()
        val total = maxOf(count, slots)
        val offset = total - count
        val n = (total - 1).coerceAtLeast(1)
        val step = size.width / n
        val pad = 2.dp.toPx()
        val h = size.height - pad * 2
        fun y(v: Double) = pad + (h * (1 - (v * unit).coerceIn(0.0, 1.0))).toFloat()
        fun x(i: Int) = if (total == 1) size.width else step * (i + offset)
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
        if (lead != null && count >= 2 && values.first()?.let { it.isFinite() && it >= 0.0 } == true) point(Offset(x(0) - step, y(lead)))
        for (i in sparkPoints(values, held)) {
            // A missing sample between two drawn ones breaks the line; a held one does not.
            if (i > expected && (expected until i).any { values[it]?.let { v -> v.isFinite() && v >= 0.0 } != true }) { closeArea(); prev = null; start = null }
            point(Offset(x(i), y(values[i]!!)))
            expected = i + 1
        }
        closeArea()
        // Dotted floor under each run of missing samples (and the whole width when nothing has arrived yet).
        val gapSpans = if (count == 0 || values.none { it != null && it.isFinite() && it >= 0.0 }) listOf(0f to size.width)
            else sparkGaps(values).map { r -> (if (r.first == 0) x(0) else x(r.first - 1)) to (if (r.last == count - 1) size.width else x(r.last + 1)) }
        val peakAt = if (peak) values.indices.filter { values[it]?.let { v -> v.isFinite() && v >= 0.0 } == true }.maxByOrNull { values[it]!! } else null
        val stroke = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val fade = if (stale) .45f else 1f
        val fill = Brush.verticalGradient(listOf(tint.copy(alpha = .30f * fade), tint.copy(alpha = .03f)))
        val gridColor = MetricColors.grid
        val dots = PathEffect.dashPathEffect(floatArrayOf(1.5.dp.toPx(), 2.5.dp.toPx()))
        val guideYs = guides.filter { it.isFinite() && it * unit < 1.0 && it > 0.0 }.map { y(it) }
        val scaleLayout = if (scaleText != null && size.height >= 28.dp.toPx())
            measurer.measure(scaleText, TextStyle(fontSize = 8.sp, fontFamily = Tech.Mono, color = labelColor)) else null
        val headRadius = 2.dp.toPx(); val glowRadius = 5.dp.toPx()
        onDrawBehind {
            if (grid) { drawLine(gridColor, Offset(0f, size.height - .5f), Offset(size.width, size.height - .5f), 1f); drawLine(gridColor.copy(alpha = gridColor.alpha * .45f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f) }
            guideYs.forEach { gy -> drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), 1f, pathEffect = dots) }
            gapSpans.forEach { (a, b) -> if (b - a > 2f) drawLine(labelColor.copy(alpha = .55f), Offset(a, size.height - 1.5f), Offset(b, size.height - 1.5f), 1f, pathEffect = dots) }
            val glowAlpha = if (!pulsing) 0.2f else {
                val t = ((phase.pulse.floatValue - 0.15f) / 0.45f).coerceIn(0f, 1f)
                0.10f + 0.28f * t
            }
            translate(left = shift.value * step) {
                drawPath(area, fill); drawPath(line, tint, style = stroke, alpha = fade)
                peakAt?.let { i -> val p = Offset(x(i), y(values[i]!!)); drawCircle(tint, 2.5.dp.toPx(), p, style = Stroke(1.dp.toPx())) }
                head?.let { if (stale) drawCircle(tint, headRadius, it, style = Stroke(1.dp.toPx())) else { drawCircle(tint, glowRadius, it, alpha = glowAlpha); drawCircle(tint, headRadius, it) } }
            }
            scaleLayout?.let {
                // On a quiet plate, so the label reads over the fill.
                drawRect(plate, Offset(1.dp.toPx(), 1.dp.toPx()), Size(it.size.width + 3.dp.toPx(), it.size.height.toFloat()))
                drawText(it, topLeft = Offset(2.5.dp.toPx(), 1.dp.toPx()))
            }
        }
    })
}

/** Pointer tracking that does not take the click. Press and Release stay with the row that selects a guest. */
internal fun Modifier.sparkTrack(cursor: SparkHover): Modifier =
    onPointerEvent(PointerEventType.Enter) { event ->
        cursor.x = event.changes.firstOrNull()?.position?.x
    }.onPointerEvent(PointerEventType.Move) { event ->
        cursor.x = event.changes.firstOrNull()?.position?.x
    }.onPointerEvent(PointerEventType.Exit) { cursor.x = null }

/** Hairline and ring dot. Both are drawn from the hover state, so a pointer move does not rebuild the line. */
@Composable private fun SparkHoverMarks(cursor: SparkHover, values: List<Double?>, gain: Float, tint: Color, shift: Animatable<Float, AnimationVector1D>, slots: Int = 0) {
    val count = values.size
    Box(Modifier.fillMaxSize().drawBehind {
        val x = cursor.x ?: return@drawBehind
        if (count <= 0 || !(size.width > 0f)) return@drawBehind
        val index = sparkSlotIndex(x, size.width, count, slots) ?: return@drawBehind
        val total = maxOf(count, slots)
        val step = if (total <= 1) size.width else size.width / (total - 1)
        val hair = (if (total <= 1) size.width else step * (index + total - count) + shift.value * step).coerceIn(0f, size.width)
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
 * Window position of a hover readout. Past 60% of the anchor width it sits left of the pointer.
 * It prefers the space above the anchor, then below, and is clamped to the window either way.
 * [above] false keeps the history readout beside the crosshair (a few pixels down from the plot top).
 */
internal fun hoverBubblePosition(
    pointerX: Float, anchor: IntRect, bubble: IntSize, window: IntSize,
    center: Boolean = false, above: Boolean = true, gap: Int = 8, besideTop: Int = 6,
): IntOffset {
    val width = anchor.width.coerceAtLeast(1)
    var left = if (center) anchor.left + (anchor.width - bubble.width) / 2
        else if (pointerX > width * 0.6f) anchor.left + pointerX.roundToInt() - gap - bubble.width
        else anchor.left + pointerX.roundToInt() + gap
    if (bubble.width > 0 && window.width > 0) {
        if (left + bubble.width > window.width) left = window.width - bubble.width
        if (left < 0) left = 0
    }
    val top = if (!above) {
        var y = anchor.top + besideTop
        if (bubble.height > 0 && window.height > 0) {
            if (y + bubble.height > window.height) y = window.height - bubble.height
            if (y < 0) y = 0
        }
        y
    } else {
        val aboveY = anchor.top - bubble.height
        val belowY = anchor.bottom
        var y = when {
            bubble.height <= 0 -> anchor.top
            window.height <= 0 -> if (anchor.top >= bubble.height + 2) aboveY else anchor.top + 4
            aboveY >= 0 -> aboveY
            belowY + bubble.height <= window.height -> belowY
            else -> (window.height - bubble.height).coerceAtLeast(0)
        }
        if (bubble.height > 0 && window.height > 0) {
            if (y < 0) y = 0
            if (y + bubble.height > window.height) y = (window.height - bubble.height).coerceAtLeast(0)
        }
        y
    }
    return IntOffset(left, top)
}

private class HoverBubblePosition(
    private val pointerX: Float, private val center: Boolean, private val above: Boolean, private val gap: Int, private val besideTop: Int,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        hoverBubblePosition(pointerX, anchorBounds, popupContentSize, windowSize, center, above, gap, besideTop)
}

private val readoutPopupProperties = PopupProperties(
    focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false,
    clippingEnabled = false, usePlatformDefaultWidth = false, usePlatformInsets = false,
)

/**
 * A scene-layer readout. It is not a child of the chart, so a clipped fleet row cannot crop it,
 * and it does not take the click (not focusable, and it does not dismiss on an outside press).
 */
@Composable internal fun HoverReadoutPopup(
    pointerX: Float, center: Boolean = false, above: Boolean = true, gap: Int = 8, besideTop: Int = 6,
    content: @Composable () -> Unit,
) {
    val window = LocalWindowInfo.current.containerSize
    val maxWidth = with(LocalDensity.current) { (window.width.takeIf { it > 24 } ?: 480).toDp() - 8.dp }
    val position = remember(pointerX, center, above, gap, besideTop) { HoverBubblePosition(pointerX, center, above, gap, besideTop) }
    Popup(popupPositionProvider = position, onDismissRequest = null, properties = readoutPopupProperties) {
        Surface(color = Tech.Raised, shape = Tech.Control, border = BorderStroke(1.dp, Tech.Edge),
            shadowElevation = if (LocalMotion.current.lite) 0.dp else 4.dp) {
            Box(Modifier.widthIn(max = maxWidth)) { content() }
        }
    }
}

/** The only composable that reads the hover position for one sparkline. Past 60% of the width the bubble sits to the left. */
@Composable private fun BoxScope.SparkBubble(cursor: SparkHover, count: Int, slots: Int = 0, textAt: (Int) -> String) {
    // Measured even before the pointer arrives, so the first hover frame already knows the chart width.
    // The popup's anchor is this sparkline, not this zero-content box.
    var width by remember { mutableIntStateOf(0) }
    Box(Modifier.matchParentSize().onSizeChanged { width = it.width })
    val x = cursor.x ?: return
    val index = sparkSlotIndex(x, width.toFloat(), count, slots) ?: return
    HoverReadoutPopup(pointerX = x) {
        Text(textAt(index), Modifier.padding(horizontal = 6.dp, vertical = 3.dp), color = Tech.Ink, fontSize = 10.sp, fontFamily = Tech.Mono, maxLines = 2)
    }
}

@Composable private fun BoxScope.SparkReadout(cursor: SparkHover, count: Int, times: List<Instant>?, held: List<Boolean>?, label: (Int) -> String, slots: Int = 0) {
    SparkBubble(cursor, count, slots) { index -> sparkReadoutLine(label(index), held?.getOrNull(index) == true, sparkClock(times, index)) }
}

/**
 * A series sparkline. A short series (a guest that just started) is drawn at the right of [slots] slots instead of
 * stretched across. For a percentage, [floor] = 1.0 fixes the scale at 0..100 % (the table rows and top lists use it, so
 * rows compare); otherwise the scale follows the data and [scaleLabel] can name its top.
 */
@Composable fun Sparkline(series: Series, modifier: Modifier = Modifier, pulse: Boolean = true, grid: Boolean = true, floor: Double = .05, ease: Boolean = pulse,
    readout: SparkReadout = SparkReadout.Cpu, hover: SparkHover? = null, slots: Int = SPARK_MIN_SLOTS, color: Color? = null, scaleLabel: ((Double) -> String)? = null, scaleKind: AxisKind? = null,
    guides: List<Double> = emptyList(), stale: Boolean = false, peak: Boolean = false, metric: (Sample) -> Double? = { it.cpu }) {
    val samples = series.samples
    // Remembered per series, so a pointer move or a parent recomposition does not rebuild these lists.
    val values = remember(samples, metric) { samples.map(metric) }
    val held = remember(samples) { samples.map { it.held } }
    val times = remember(samples) { samples.map { it.at } }
    Sparkline(values, modifier, color = color, floor = floor, ceiling = maxOf(1.0, floor), grid = grid, pulse = pulse, held = held, stamp = samples.lastOrNull()?.at, ease = ease,
        times = times, label = if (readout == SparkReadout.None) null else { i -> sparkSampleValue(samples[i], readout) }, hover = hover,
        slots = slots, scaleLabel = scaleLabel, guides = guides, stale = stale, peak = peak, scaleKind = scaleKind)
}

/**
 * A counter reset: the guest reported (CPU is there) but its rates are missing, because a counter went backwards
 * (a restart, a migration) and the delta was dropped. Plain missing samples have no CPU either.
 */
internal fun netResets(samples: List<Sample>): List<Int> = samples.indices.filter { i ->
    val s = samples[i]; s.cpu != null && s.netInRate == null && s.netOutRate == null && i > 0
}

/**
 * Inbound (blue) and outbound (amber) rates on one shared scale, at least 1 KiB/s tall. The two lines are drawn in
 * bytes per second against the same eased ceiling, so a burst rescales both together and smoothly. A counter reset
 * leaves a gap with a short dashed tick, so it reads as "reset" and not as a drop to zero. [scaleLabel] names the top.
 */
@Composable fun NetSparkline(series: Series, modifier: Modifier = Modifier, pulse: Boolean = true, ease: Boolean = pulse, slots: Int = SPARK_MIN_SLOTS,
    scaleLabel: Boolean = false, stale: Boolean = false) {
    val samples = series.samples
    val ins = remember(samples) { samples.map { it.netInRate } }; val outs = remember(samples) { samples.map { it.netOutRate } }
    // Both lines share one round top (1, 2, 2.5 or 5 x 10^n of an IEC unit), so the label names a value worth reading.
    val raw = sparkTop(ins + outs, floor = 1024.0, ceiling = Double.MAX_VALUE)
    val axis = remember(raw) { axisScale(AxisKind.Rate, raw, maxTicks = 4, headroom = 1.0) }
    val top = axis.top
    val held = remember(samples) { samples.map { it.held } }; val stamp = samples.lastOrNull()?.at
    val resets = remember(samples) { netResets(samples) }
    val cursor = remember { SparkHover() }
    val mark = Tech.Muted
    Box(modifier) {
        Sparkline(ins, Modifier.matchParentSize(), color = MetricColors.secondary, floor = top, ceiling = top, pulse = pulse, held = held, stamp = stamp, ease = ease, hover = cursor,
            slots = slots, scaleLabel = if (scaleLabel) { _ -> axis.label(axis.top) } else null, stale = stale)
        Sparkline(outs, Modifier.matchParentSize(), color = MetricColors.primary, floor = top, ceiling = top, grid = false, pulse = pulse, held = held, stamp = stamp, ease = ease, hover = cursor,
            slots = slots, stale = stale)
        if (resets.isNotEmpty()) Box(Modifier.matchParentSize().drawBehind {
            val total = maxOf(samples.size, slots); val step = if (total <= 1) size.width else size.width / (total - 1)
            val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))
            resets.forEach { i -> val x = step * (i + total - samples.size); drawLine(mark, Offset(x, size.height * .25f), Offset(x, size.height), 1.dp.toPx(), pathEffect = dash) }
        })
        Box(Modifier.matchParentSize().sparkTrack(cursor))
        NetSparkReadout(cursor, series, slots)
    }
}

/** One bubble for both rates. It is the only reader of the shared hover, so the two lines are not rebuilt as the pointer moves. */
@Composable private fun BoxScope.NetSparkReadout(cursor: SparkHover, series: Series, slots: Int = 0) {
    val samples = series.samples
    val times = remember(samples) { samples.map { it.at } }
    SparkBubble(cursor, samples.size, slots) { index ->
        val sample = samples[index]
        val value = if (sample.cpu != null && sample.netInRate == null && sample.netOutRate == null && index > 0) "counter reset" else sparkNetValue(sample)
        sparkReadoutLine(value, sample.held, sparkClock(times, index))
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
    // The label grows with the text size: a fixed 26 units cut "OUT" to "OU" at 150 % text.
    Text(label, Modifier.widthIn(min = 26.dp), fontSize = fontSize, lineHeight = fontSize * 1.2f, fontFamily = Tech.Mono, color = tint, maxLines = 1, softWrap = false)
    Text(formatRate(value), fontSize = fontSize, lineHeight = fontSize * 1.2f, fontFamily = Tech.Mono, color = Tech.Ink.dimmed(.85f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
}

/**
 * 240° gauge that eases to new values without overshoot, number in the centre, caption and detail below. Hover names the exact value.
 *
 * Threshold bands sit just outside the track (attention, then critical, from [thresholds]) and each threshold cuts a
 * notch in the track, so the zone a value is in reads by position as well as by colour. With a [series] the gauge also
 * shows the range of the live window as a faint arc under the value, and its peak as a bright mark when the peak is
 * above the current value.
 */
@Composable fun ArcGauge(value: Double?, caption: String, detail: String? = null, size: Dp = 84.dp, modifier: Modifier = Modifier, series: Series? = null, metric: (Sample) -> Double? = { it.cpu },
    thresholds: MetricThresholds = MetricThresholds.Load) {
    var hovered by remember { mutableStateOf(false) }
    val setHovered = remember<(Boolean) -> Unit> { { hovered = it } }
    val samples = series?.samples
    // Range and peak of the live window, remembered per sample list: a hover or a recomposition does not walk the series.
    val range = remember(samples) { samples?.let { s -> gaugeRange(s.map(metric)) } }
    Column(modifier.onPointerEvent(PointerEventType.Enter) { setHovered(true) }
        .onPointerEvent(PointerEventType.Exit) { setHovered(false) },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.size(size)) {
            ArcGaugeFace(value, caption, size, thresholds, range)
            GaugeTip(hovered, value, detail, series, metric)
        }
        detail?.let { Text(it, fontSize = 10.sp, lineHeight = 12.sp, color = Tech.Ink.dimmed(.8f), fontFamily = Tech.Mono, maxLines = 1) }
    }
}

/** Lowest and highest finite, non-negative value, clamped to 0..1, or null when there is none. */
internal fun gaugeRange(values: List<Double?>): ClosedFloatingPointRange<Double>? {
    var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
    for (v in values) if (v != null && v.isFinite() && v >= 0.0) { if (v < lo) lo = v; if (v > hi) hi = v }
    return if (hi < lo) null else lo.coerceIn(0.0, 1.0)..hi.coerceIn(0.0, 1.0)
}

/** The window's peak when it is worth a mark of its own: more than two points above the value the gauge shows now. */
internal fun gaugePeak(shown: Double?, range: ClosedFloatingPointRange<Double>?): Double? =
    range?.endInclusive?.takeIf { shown != null && shown.isFinite() && it - shown > .02 }

/** The arc, the number and the caption. Hover is not a parameter, so the face is left alone when the pointer enters. */
@Composable private fun ArcGaugeFace(value: Double?, caption: String, size: Dp, thresholds: MetricThresholds = MetricThresholds.Load, range: ClosedFloatingPointRange<Double>? = null) {
    val reduce = LocalReduceMotion.current
    val shown = value?.takeIf { it.isFinite() && it >= 0.0 }
    // It used to spring with a bounce, so the needle overshot and came back on every reading.
    val v by animateFloatAsState((shown ?: 0.0).toFloat().coerceIn(0f, 1f), if (reduce) snap() else tween(SPARK_SETTLE_MS, easing = FastOutSlowInEasing))
    val tint by animateColorAsState(thresholds.tone(shown ?: 0.0).color, tween(if (reduce) 0 else 300))
    val attention = Tech.Amber; val critical = Tech.Red; val track = MetricColors.track; val ink = Tech.Ink; val gap = Tech.Panel
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().drawWithCache {
            val w = 6.dp.toPx(); val band = 2.dp.toPx(); val inset = w / 2 + band + 3.dp.toPx()
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val bandInset = inset - w / 2 - band / 2 - 1.5.dp.toPx()
            val bandSize = Size(this.size.width - bandInset * 2, this.size.height - bandInset * 2)
            val trackStroke = Stroke(w, cap = StrokeCap.Round); val glow = Stroke(w * 2.2f, cap = StrokeCap.Round)
            val bandStroke = Stroke(band, cap = StrokeCap.Butt); val rangeStroke = Stroke(w, cap = StrokeCap.Butt)
            val radius = this.size.minDimension / 2 - inset
            val r1 = radius - w; val r2 = r1 - 3.dp.toPx()
            val mid = Offset(this.size.width / 2, this.size.height / 2)
            fun at(fraction: Double, r: Float): Offset {
                val a = Math.toRadians(150.0 + 240.0 * fraction)
                return mid + Offset((r * Math.cos(a)).toFloat(), (r * Math.sin(a)).toFloat())
            }
            val minor = (0..10).map { at(it / 10.0, r1) to at(it / 10.0, r2) }
            // Thresholds cut the track, the same notch the bars and rings carry, so nothing floats beside the number.
            val notches = listOf(thresholds.attention, thresholds.critical).map { at(it, radius - w / 2 - 1f) to at(it, radius + w / 2 + 1f) }
            val notchWidth = 1.5.dp.toPx()
            // The window's peak, only when it stands clear of the current value: a short mark across the track, out to the band.
            val peakMark = gaugePeak(shown, range)?.let { at(it, radius - w / 2 - 1.dp.toPx()) to at(it, radius + w / 2 + band + 2.5.dp.toPx()) }
            val a0 = 150f + 240f * thresholds.attention.toFloat(); val a1 = 150f + 240f * thresholds.critical.toFloat()
            val minorColor = Tech.Edge.copy(alpha = .7f)
            onDrawBehind {
                drawArc(track, 150f, 240f, false, Offset(inset, inset), arcSize, style = trackStroke)
                // Threshold bands: amber from attention to critical, red from critical to full.
                drawArc(attention.copy(alpha = .55f), a0, a1 - a0, false, Offset(bandInset, bandInset), bandSize, style = bandStroke)
                drawArc(critical.copy(alpha = .75f), a1, 390f - a1, false, Offset(bandInset, bandInset), bandSize, style = bandStroke)
                minor.forEach { (p, q) -> drawLine(minorColor, p, q, 1f) }
                if (shown != null) {
                    range?.let { r -> if (r.endInclusive > r.start) drawArc(tint.copy(alpha = .22f), 150f + 240f * r.start.toFloat(), 240f * (r.endInclusive - r.start).toFloat(), false, Offset(inset, inset), arcSize, style = rangeStroke) }
                    drawArc(tint.copy(alpha = .16f), 150f, 240f * v, false, Offset(inset, inset), arcSize, style = glow)
                    drawArc(tint, 150f, 240f * v, false, Offset(inset, inset), arcSize, style = trackStroke)
                }
                notches.forEach { (p, q) -> drawLine(gap, p, q, notchWidth) }
                peakMark?.let { (p, q) -> drawLine(ink, p, q, notchWidth, cap = StrokeCap.Round) }
            }
        })
        if (shown == null) Text("—", color = Tech.Muted, fontFamily = Tech.Mono) else AnimatedNumber(shown, fontSize = 16.sp, align = TextAlign.Center, thresholds = thresholds)
        // Caption sits in the open bottom of the arc.
        Text(caption.uppercase(), Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp), fontSize = 9.sp, lineHeight = 11.sp, color = Tech.Muted, fontFamily = Tech.Mono, letterSpacing = 1.sp, maxLines = 1)
    }
}

/** Immediate readout, above the gauge. HelpTip waits 450 ms, which is too slow for a value under the pointer. */
@Composable private fun GaugeTip(hovered: Boolean, value: Double?, detail: String?, series: Series?, metric: (Sample) -> Double?) {
    if (!hovered) return
    HoverReadoutPopup(pointerX = 0f, center = true) {
        Text(gaugeHoverText(value, detail, series, metric), Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            color = Tech.Ink, fontSize = 10.sp, fontFamily = Tech.Mono, textAlign = TextAlign.Center)
    }
}

/**
 * Animated proportional bar; colours follow running / stopped / template / other. Each part is set apart from the next
 * by a gap, so two neighbours of similar colour stay apart.
 */
@Composable fun SplitBar(values: List<Int>, modifier: Modifier = Modifier, colors: List<Color> = listOf(Tech.Green, Tech.Muted.copy(alpha = .55f), Tech.Amber, Tech.Ink.copy(alpha = .7f)),
    height: Dp = 4.dp) {
    Row(modifier.height(height).background(MetricColors.track.copy(alpha = .5f)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        values.forEachIndexed { i, n ->
            val w by animateFloatAsState(n.toFloat(), tween(if (LocalReduceMotion.current) 0 else 350))
            if (w > 0.01f) Box(Modifier.weight(w).fillMaxHeight().background(colors[i % colors.size]))
        }
    }
}

/**
 * Thin usage bar for memory and similar fractions ([BarGauge]): the tone against [thresholds], notches where the meaning
 * changes, and a dashed track for "no value". [color] makes it a ranking bar with no judgement.
 */
@Composable fun UsageBar(fraction: Double?, modifier: Modifier = Modifier, thresholds: MetricThresholds = MetricThresholds.Load, color: Color? = null, notches: Boolean = true) =
    BarGauge(fraction, modifier, thresholds = thresholds, color = color, notches = notches)

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
                Text("Ctrl F", fontSize = 9.sp, color = Tech.Muted.dimmed(.7f), fontFamily = Tech.Mono)
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
    Column(modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically)) {
        Text(label, if (identity) Modifier.identity() else Modifier, fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = 1.2.sp, color = Tech.Muted, fontFamily = Tech.Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        content()
    }
}
@Composable private fun StripDivider() = Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 10.dp).background(Tech.Edge))

/** The split under the guest count, with each count tied to its noun: a line may break between items, never inside one. */
internal fun guestSplitDisplay(total: FleetTotals): String = guestSplitText(total).split(" · ").joinToString(" · ") { it.replace(' ', ' ') }

/** One collapsed line for a short window: the headline counts. The full strip is one click away. */
internal fun fleetDigest(state: WorkspaceState, total: FleetTotals?): String {
    if (total == null) return if (state.loading) "Loading the fleet…" else "No fleet data yet"
    val counted = if (state.overview?.remotes.isNullOrEmpty()) "${total.nodes} ${if (total.nodes == 1) "node" else "nodes"}" else "${state.overview!!.remotes.size} remotes"
    return listOfNotNull(counted, "${total.guests} guests", total.cpuFraction?.let { "CPU ${percent(it)}" },
        total.memoryBytes?.let { "MEM ${percent(memoryFraction(it, total.memoryLimitBytes))}" }).joinToString(" · ")
}

/**
 * Fleet telemetry: link, nodes, guests split, cluster CPU, guest network, busiest guest, memory, actions. One line on a
 * wide window. Narrower, the cells wrap onto further lines at a width that grows with the text size, so no fact is cut
 * at the edge or hidden behind a scroller. In a [short] page (a laptop at a large interface size) the strip folds to
 * one digest line with a Details control, so the inventory below stays in view; Details shows every cell and action.
 */
@Composable fun TelemetryStrip(state: WorkspaceState, workspace: Workspace, onDisconnect: () -> Unit, short: Boolean = false) {
    val total = state.overview?.let(::fleetTotals)
    val prefs = remember { MotionPreferences() }
    val reduce = LocalReduceMotion.current
    val phase = LocalMotionPhase.current
    val spinning = !reduce && phase.active.value && state.loading
    var details by remember { mutableStateOf(false) }
    val host = state.address.substringAfter("://").trimEnd('/')
    // sp grows with the accessibility text size; dp does not. Widen the tight cells, and stop before the 960 and 1920 strips gain a line.
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
    fun textWidth(base: Dp, cap: Float) = base * fontScale.coerceAtMost(cap)
    val toggleLive: () -> Unit = { val next = !state.live; workspace.setLive(next); prefs.put("live", next) }
    val linkCell: @Composable (Modifier) -> Unit = { modifier ->
        StripCell("LINK · $host", modifier.width(textWidth(220.dp, 1.08f)), identity = true) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LivePill(state, toggleLive)
                Box(Modifier.weight(1f)) { SyncedAgeText(state.updatedAt) }
            }
        }
    }
    val nodesCell: @Composable (Modifier) -> Unit = { modifier ->
        if (total != null) StripCell(if (state.overview?.remotes.isNullOrEmpty()) "NODES" else "REMOTES", modifier.width(textWidth(76.dp, 1.25f))) {
            AnimatedCount(if (state.overview?.remotes.isNullOrEmpty()) total.nodes else state.overview!!.remotes.size)
        }
    }
    val guestsCell: @Composable (Modifier) -> Unit = { modifier ->
        if (total != null) StripCell("GUESTS", modifier.width(textWidth(230.dp, 1.12f))) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimatedCount(total.guests)
                Text(guestSplitDisplay(total), fontSize = 10.sp, lineHeight = 12.sp, color = Tech.Muted, fontFamily = Tech.Mono,
                    modifier = Modifier.weight(1f).padding(bottom = 3.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            // Templates are not a state to worry about: a quiet tone, not amber. "Other" (paused, unknown) keeps amber.
            SplitBar(listOf(total.running, total.stopped, total.templates, otherGuests(total)), Modifier.fillMaxWidth(),
                colors = listOf(Tech.Green, Tech.Muted.copy(alpha = .6f), Tech.Ink.copy(alpha = .28f), Tech.Amber), height = 5.dp)
        }
    }
    val cpuMin = textWidth(160.dp, 1.15f)
    // The range of the live window under the current value: "38–77 %" says how steady the number above it is.
    val cpuRange = remember(state.history.cluster.samples) { rangeText(state.history.cluster.samples.map { it.cpu }, ::wholePercent)?.let { "$it%" } }
    val cpuCell: @Composable (Modifier) -> Unit = { modifier ->
        if (total?.cpuFraction != null) StripCell("CLUSTER CPU", modifier) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Fixed 0..100 % with faint guides where the load colour changes, so the line's height is the load.
                Sparkline(state.history.cluster, Modifier.opensLens { clusterLens(LiveMetric.Cpu) }.weight(1f).height(26.dp), floor = 1.0,
                    guides = listOf(MetricThresholds.Load.attention, MetricThresholds.Load.critical), stale = state.stale)
                Column(Modifier.width(textWidth(54.dp, 1.5f)), horizontalAlignment = Alignment.End) {
                    AnimatedNumber(total.cpuFraction!!, Modifier.fillMaxWidth(), fontSize = 16.sp, align = TextAlign.End)
                    if (cpuRange != null) Text(cpuRange, fontSize = 9.sp, lineHeight = 10.sp, color = Tech.Muted, fontFamily = Tech.Mono, maxLines = 1, softWrap = false)
                }
            }
        }
    }
    val rateWidth = textWidth(104.dp, 1.35f)
    val netMin = textWidth(160.dp, 1.2f).coerceAtLeast(rateWidth + 92.dp)
    val netCell: @Composable (Modifier) -> Unit = { modifier ->
        if (total != null) {
            val net = state.history.cluster.samples.lastOrNull()
            StripCell("GUEST NETWORK", modifier) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NetSparkline(state.history.cluster, Modifier.opensLens { clusterLens(LiveMetric.Network) }.weight(1f).height(26.dp), stale = state.stale)
                    Column(Modifier.width(rateWidth), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        RateLine("IN", net?.netInRate, MetricColors.secondary)
                        RateLine("OUT", net?.netOutRate, MetricColors.primary)
                    }
                }
            }
        }
    }
    // The busiest guest by its last minute, and it keeps the title until another clearly leads: two guests
    // with similar load used to swap the name here on every reading.
    val busiest = remember(state.connectionEpoch) { SteadyRanking<ResourceId>() }
    val running = state.overview?.guests.orEmpty().filter { it.status == "running" && !it.template }
    val hot = if (total == null) null else busiest.rank(running.associate { it.resource to (steadyValue(state.history.guests[it.resource], it.metrics?.cpuFraction) { s -> s.cpu } ?: 0.0) })
        .firstOrNull()?.let { top -> running.firstOrNull { it.resource == top } }
    val busiestWidth = textWidth(200.dp, 1.12f)
    val busiestCell: @Composable (Modifier) -> Unit = { modifier ->
        if (total != null) StripCell("BUSIEST GUEST", modifier) {
            if (hot == null) Text("—", fontSize = 13.sp, fontFamily = Tech.Mono, color = Tech.Muted)
            else {
                val series = state.history.guests[hot.resource]
                val cpu = latestCpu(series, hot.metrics?.cpuFraction)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(hot.name.ifBlank { "Guest ${hot.id}" }, Modifier.weight(1f).identity(), fontSize = 13.sp, fontFamily = Tech.Mono, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
                    if (cpu != null) AnimatedNumber(cpu, Modifier.width(textWidth(44.dp, 1.5f)), fontSize = 13.sp, align = TextAlign.End)
                }
                Sparkline(series ?: Series(), Modifier.opensLens { guestLens(hot, LiveMetric.Cpu) }.fillMaxWidth().height(12.dp), grid = false, pulse = false, ease = true, floor = 1.0, stale = state.stale)
            }
        }
    }
    // The percentage is the first thing to drop: the bar still shows the fraction, and the gib line keeps its width.
    val showPercent = fontScale < 1.25f
    val memoryWidth = textWidth(190.dp, if (showPercent) 1.25f else 1.15f)
    val memoryCell: @Composable (Modifier) -> Unit = { modifier ->
        if (total?.memoryBytes != null) {
            val f = memoryFraction(total.memoryBytes, total.memoryLimitBytes)
            StripCell("MEMORY", modifier) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${gib(total.memoryBytes)} / ${gib(total.memoryLimitBytes)} GiB", fontSize = 13.sp, fontFamily = Tech.Mono,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
                    if (showPercent) Text(metricText(percent(f), unitScaleFor(11.sp)), fontSize = 11.sp, fontFamily = Tech.Mono, fontWeight = FontWeight.SemiBold, color = MetricThresholds.Capacity.tone(f).color, modifier = Modifier.padding(bottom = 1.dp), maxLines = 1, softWrap = false)
                }
                UsageBar(f, Modifier.fillMaxWidth(), thresholds = MetricThresholds.Capacity)
            }
        }
    }
    val actions: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HelpTip("Sync now (F5)") {
                Box(Modifier.size(28.dp).border(1.dp, Tech.Edge, Tech.Control).clickable(enabled = state.connected, onClick = { workspace.refresh(force = true) }), contentAlignment = Alignment.Center) {
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
            HelpTip("What this sign-in is allowed to do, feature by feature") { MiniAction("Permissions", enabled = state.connected) { permissions = true } }
            if (permissions) TechDialog("Permissions", { permissions = false }, width = 860.dp, subtitle = "Effective privileges of the signed-in identity, read from the server", dismiss = Dismiss.Light, actions = {
                TechAction("Done", active = true) { permissions = false }
            }) { PermissionsPanel(state, workspace, framed = false) }
            MiniAction("Disconnect", enabled = state.connected, onClick = onDisconnect)
            if (short) HelpTip("Fold the summary to one line so the inventory stays in view") { MiniAction("Hide details") { details = false } }
        }
    }
    Surface(color = Tech.Panel, shape = Tech.Plate, border = BorderStroke(1.dp, Tech.Edge), modifier = Modifier.fillMaxWidth()) {
        if (short && !details) {
            val digest = fleetDigest(state, total)
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LivePill(state, toggleLive)
                Box(Modifier.weight(1f)) {
                    HelpTip(listOfNotNull("LINK · $host", digest, total?.let(::guestSplitText)).joinToString("\n"), identity = true) {
                        Text(digest, fontSize = 11.sp, fontFamily = Tech.Mono, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
                    }
                }
                HelpTip("Show the whole summary: link, guest split, CPU and network trends, busiest guest, memory, Sync, Permissions and Disconnect") {
                    MiniAction("Details") { details = true }
                }
            }
            return@Surface
        }
        val wideHeight = 76.dp * fontScale.coerceAtMost(1.2f)
        val narrowHeight = 72.dp * fontScale.coerceAtMost(1.2f)
        BoxWithConstraints {
            val availableWidth = maxWidth
            if (availableWidth >= 1450.dp * fontScale) {
                Row(Modifier.fillMaxWidth().height(wideHeight), verticalAlignment = Alignment.CenterVertically) {
                    linkCell(Modifier.fillMaxHeight()); StripDivider(); nodesCell(Modifier.fillMaxHeight()); guestsCell(Modifier.fillMaxHeight()); StripDivider()
                    if (total?.cpuFraction != null) { cpuCell(Modifier.fillMaxHeight().weight(1f).widthIn(min = cpuMin)); StripDivider() }
                    if (total != null) {
                        netCell(Modifier.fillMaxHeight().weight(1f).widthIn(min = netMin)); StripDivider()
                        busiestCell(Modifier.fillMaxHeight().width(busiestWidth)); StripDivider()
                    } else Spacer(Modifier.weight(1f))
                    if (total?.memoryBytes != null) { memoryCell(Modifier.fillMaxHeight().width(memoryWidth)); StripDivider() }
                    actions(Modifier.fillMaxHeight())
                }
            } else Column {
                if (availableWidth >= 900.dp * fontScale) {
                    Row(Modifier.fillMaxWidth().height(narrowHeight), verticalAlignment = Alignment.CenterVertically) {
                        linkCell(Modifier.fillMaxHeight()); StripDivider(); nodesCell(Modifier.fillMaxHeight()); guestsCell(Modifier.fillMaxHeight()); Spacer(Modifier.weight(1f)); actions(Modifier.fillMaxHeight())
                    }
                } else {
                    // An expanded rail or large text reduces the width: the cells and every action wrap, each kept whole.
                    FlowRow(Modifier.fillMaxWidth(), itemVerticalAlignment = Alignment.CenterVertically) {
                        linkCell(Modifier.heightIn(min = narrowHeight)); nodesCell(Modifier.heightIn(min = narrowHeight)); guestsCell(Modifier.heightIn(min = narrowHeight))
                        actions(Modifier.heightIn(min = 44.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Tech.Edge))
                if (availableWidth >= 820.dp * fontScale) {
                    Row(Modifier.fillMaxWidth().height(narrowHeight), verticalAlignment = Alignment.CenterVertically) {
                        if (total?.cpuFraction != null) { cpuCell(Modifier.fillMaxHeight().weight(1f).widthIn(min = cpuMin)); StripDivider() }
                        if (total != null) {
                            netCell(Modifier.fillMaxHeight().weight(1f).widthIn(min = netMin)); StripDivider()
                            busiestCell(Modifier.fillMaxHeight().width(busiestWidth)); StripDivider()
                        } else Spacer(Modifier.weight(1f))
                        if (total?.memoryBytes != null) { memoryCell(Modifier.fillMaxHeight().width(memoryWidth)); StripDivider() }
                    }
                } else {
                    // Too narrow for one usage line: whole cells on as many lines as needed. Nothing waits behind a scroller.
                    FlowRow(Modifier.fillMaxWidth()) {
                        val cell = Modifier.heightIn(min = narrowHeight)
                        cpuCell(cell.width(cpuMin + 40.dp)); netCell(cell.width(netMin + 20.dp)); busiestCell(cell.width(busiestWidth)); memoryCell(cell.width(memoryWidth))
                    }
                }
            }
        }
    }
}
