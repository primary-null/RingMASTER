@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.*
import local.ringleader.core.FILL_CRITICAL_FRACTION
import local.ringleader.core.FILL_HIGH_FRACTION
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * MetricKit: the one vocabulary every measurement in the app is drawn with, and the kit the service integrations reuse.
 *
 * Policy (see the "Metric visuals" section of the motion notes for the reasoning):
 * - Scale: every axis starts at zero. A fraction axis never goes past 100 %; its top is the smallest of 5, 10, 25, 50 and
 *   100 % that holds the peak. Byte and rate axes pick their IEC unit from the data and tick at 1, 2, 2.5 or 5 x 10^n of
 *   it. Comparison places (table rows, top lists, the strip) draw percentages on a fixed 0..100 % scale.
 * - Time: ticks fall on round local times (00:15, 06:00, a day, a month) chosen for the plot width, never on the
 *   arbitrary quarters of the visible span.
 * - Colour: tones judge a value (good, attention, critical) and are used only where a value is judged; series colours
 *   name a quantity (amber first, blue second, muted dashed for a capacity). Blue against amber survives red-green
 *   colour blindness, and blue never reads as the green "good" tone. Colour is never alone: thresholds also show as
 *   notches and bands by position, and every value is also written.
 * - Thresholds: load (CPU) changes meaning at 50 % and above 85 % (the app's long-standing ramp); capacity (memory, disk,
 *   storage) at 85 % and 95 % (the storage rules in core).
 * - Numbers: monospaced, so digits are tabular; the unit is set smaller than the value; values in a column are aligned
 *   to their end.
 */

/** What a value means against its thresholds. [Neutral] is "not judged" (no value, or a quantity that is not a load). */
enum class MetricTone { Good, Attention, Critical, Neutral }

/** The colour of a tone in the active theme. */
val MetricTone.color: Color get() = when (this) {
    MetricTone.Good -> Tech.Green
    MetricTone.Attention -> Tech.Amber
    MetricTone.Critical -> Tech.Red
    MetricTone.Neutral -> Tech.Muted
}

/**
 * Where a fraction changes meaning. A value reaches [attention] at or above it; it is critical above [critical], or at it
 * when [criticalInclusive]. The two sets the app uses are [Load] and [Capacity].
 */
data class MetricThresholds(val attention: Double, val critical: Double, val criticalInclusive: Boolean = false) {
    fun tone(value: Double?): MetricTone = when {
        value == null || !value.isFinite() || value < 0.0 -> MetricTone.Neutral
        value > critical || (criticalInclusive && value >= critical) -> MetricTone.Critical
        value >= attention -> MetricTone.Attention
        else -> MetricTone.Good
    }
    companion object {
        /** CPU and other load: 50 % and above 85 %, the boundaries of [ramp]. */
        val Load = MetricThresholds(.50, .85)
        /** Memory, disk and storage fill: 85 % and 95 %, the storage rules ([FILL_HIGH_FRACTION], [FILL_CRITICAL_FRACTION]). */
        val Capacity = MetricThresholds(FILL_HIGH_FRACTION, FILL_CRITICAL_FRACTION, criticalInclusive = true)
    }
}

/** Series colours: they name a quantity and never judge it. */
object MetricColors {
    /** The first series of a chart: CPU, used memory, outbound, write. */
    val primary: Color get() = Tech.Amber
    /** The second series: inbound, read, IO wait. Blue, so it is not taken for the green "good" tone. */
    val secondary: Color get() = Appearance.palette.let { p ->
        when {
            p.highContrast && p.light -> Color(0xFF00489C)
            p.highContrast -> Color(0xFF7FD4FF)
            p.light -> Color(0xFF185CA6)
            else -> Color(0xFF66B5FF)
        }
    }
    /**
     * The parts of a breakdown (a stacked bar and its legend): amber, blue, teal, violet, rose. None is the green or red
     * of a tone, so a part is never read as a verdict; every part is also labelled, so the hue is not the only cue.
     */
    val categorical: List<Color> get() = Appearance.palette.let { p ->
        when {
            p.highContrast && p.light -> listOf(Tech.Amber, Color(0xFF00489C), Color(0xFF005F63), Color(0xFF5B2A86), Color(0xFF8A1F55))
            p.highContrast -> listOf(Tech.Amber, Color(0xFF7FD4FF), Color(0xFF6FE3E6), Color(0xFFD9B3FF), Color(0xFFFFB0D0))
            p.light -> listOf(Tech.Amber, Color(0xFF185CA6), Color(0xFF007379), Color(0xFF7540A0), Color(0xFFA6336B))
            else -> listOf(Tech.Amber, Color(0xFF66B5FF), Color(0xFF58C9CD), Color(0xFFCA99FF), Color(0xFFF29BC1))
        }
    }
    /** The [index]th categorical colour; past the fifth they repeat. */
    fun category(index: Int): Color = categorical.let { it[Math.floorMod(index, it.size)] }
    /** A capacity or total, drawn dashed. */
    val capacity: Color get() = Tech.Muted
    /** A bar that ranks a quantity (largest volumes, by guest): not a judgement, so not a tone. */
    val rank: Color get() = if (Appearance.palette.highContrast) Tech.Ink else Tech.Ink.copy(alpha = .55f)
    /** Grid lines recede: the data is the brightest thing in a chart. */
    val grid: Color get() = if (Appearance.palette.highContrast) Tech.Edge.copy(alpha = .55f) else Tech.Edge.copy(alpha = .42f)
    val gridFaint: Color get() = if (Appearance.palette.highContrast) Tech.Edge.copy(alpha = .3f) else Tech.Edge.copy(alpha = .22f)
    /** Track of a gauge or bar. */
    val track: Color get() = if (Appearance.palette.highContrast) Tech.Edge.copy(alpha = .6f) else Tech.Edge.copy(alpha = .55f)
    /** Axis labels: quieter than values, still readable (High Contrast keeps them at full strength). */
    val axis: Color get() = Tech.Muted
}

// ---------------------------------------------------------------------------------------------------------------------
// Scales

/** What an axis measures. [Fraction] is 0..1 shown as a percentage; [Percent] is already 0..100. */
enum class AxisKind { Fraction, Percent, Bytes, Rate, Count }

/** A resolved axis: its top, its ticks (bottom first) and how a tick is written. */
data class AxisScale(val kind: AxisKind, val top: Double, val ticks: List<Double>, val divisor: Double, val unit: String, val decimals: Int) {
    fun label(value: Double): String {
        val n = value / divisor
        val number = if (decimals == 0) n.roundToInt().toString() else String.format(Locale.ROOT, "%.${decimals}f", n)
        return when (kind) {
            AxisKind.Fraction, AxisKind.Percent -> "$number%"
            AxisKind.Bytes, AxisKind.Rate -> if (unit.isEmpty()) number else "$number $unit"
            AxisKind.Count -> number
        }
    }
}

private val percentLadder = listOf(.05, .10, .25, .50, 1.0)
private val iecUnits = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")

/** Heckbert's nice number: 1, 2, 2.5, 5 or 10 times a power of ten, rounded ([round]) or the ceiling. */
internal fun niceNumber(x: Double, round: Boolean): Double {
    if (!x.isFinite() || x <= 0.0) return 1.0
    val exp = floor(log10(x))
    val f = x / 10.0.pow(exp)
    val nice = if (round) when { f < 1.5 -> 1.0; f < 2.25 -> 2.0; f < 3.5 -> 2.5; f < 7.5 -> 5.0; else -> 10.0 }
        else when { f <= 1.0 -> 1.0; f <= 2.0 -> 2.0; f <= 2.5 -> 2.5; f <= 5.0 -> 5.0; else -> 10.0 }
    return nice * 10.0.pow(exp)
}

/** Ticks from zero to a nice top that holds [max], about [maxTicks] intervals. Returns the step and the ticks. */
internal fun niceTicks(max: Double, maxTicks: Int = 4): Pair<Double, List<Double>> {
    val m = if (max.isFinite() && max > 0.0) max else 1.0
    val intervals = maxTicks.coerceAtLeast(1)
    val step = niceNumber(m / intervals, round = false)
    val count = ceil(m / step - 1e-9).toInt().coerceAtLeast(1)
    return step to (0..count).map { it * step }
}

/** Decimal places a tick step needs to be written exactly: 5 needs none, 2.5 and 0.5 one, 0.25 two (at most two). */
internal fun decimalsFor(step: Double): Int {
    if (!step.isFinite() || step <= 0.0) return 0
    var d = 0
    while (d < 2 && abs(step * 10.0.pow(d) - (step * 10.0.pow(d)).roundToInt()) > 1e-6) d++
    return d
}

/**
 * The axis for a peak value of [kind]. Missing, negative and non-finite peaks count as zero; a zero peak still gets a
 * small readable scale (5 %, 1 KiB, 1), so an idle line sits on the floor instead of filling the plot.
 */
internal fun axisScale(kind: AxisKind, peak: Double, maxTicks: Int = 4, headroom: Double = 1.04): AxisScale {
    val p = if (peak.isFinite() && peak > 0.0) peak * headroom else 0.0
    return when (kind) {
        AxisKind.Fraction, AxisKind.Percent -> {
            val unit = if (kind == AxisKind.Fraction) 1.0 else 100.0
            val top = percentLadder.firstOrNull { it * unit >= p - 1e-12 }?.times(unit) ?: unit
            val step = when (top / unit) { 1.0 -> .25; .5 -> .1; .25 -> .05; .1 -> .02; else -> .01 } * unit
            val ticks = (0..(top / step).roundToInt()).map { it * step }
            AxisScale(kind, top, ticks, unit / 100.0, "%", 0)
        }
        AxisKind.Bytes, AxisKind.Rate -> {
            val suffix = if (kind == AxisKind.Rate) "/s" else ""
            if (p <= 0.0) return AxisScale(kind, 1024.0, listOf(0.0, 512.0, 1024.0), 1024.0, "KiB$suffix", 1)
            var power = floor(ln(p) / ln(1024.0)).toInt().coerceIn(0, iecUnits.lastIndex)
            var divisor = 1024.0.pow(power)
            var (step, ticks) = niceTicks(p / divisor, maxTicks)
            if (ticks.last() >= 1024.0 && power < iecUnits.lastIndex) {
                power++; divisor = 1024.0.pow(power)
                niceTicks(p / divisor, maxTicks).let { step = it.first; ticks = it.second }
            }
            AxisScale(kind, ticks.last() * divisor, ticks.map { it * divisor }, divisor, iecUnits[power] + suffix, if (power == 0) 0 else decimalsFor(step))
        }
        AxisKind.Count -> {
            val (step, ticks) = niceTicks(if (p > 0.0) p else 1.0, maxTicks)
            AxisScale(kind, ticks.last(), ticks, 1.0, "", decimalsFor(step))
        }
    }
}

/** The axis kind of a Proxmox history key. */
internal fun historyAxisKind(key: String): AxisKind = when {
    key == "cpu" || key == "iowait" -> AxisKind.Fraction
    key.startsWith("pressure") -> AxisKind.Percent
    key == "loadavg" -> AxisKind.Count
    key in setOf("netin", "netout", "diskread", "diskwrite") -> AxisKind.Rate
    else -> AxisKind.Bytes
}

// ---------------------------------------------------------------------------------------------------------------------
// Time ticks

/** One label on a time axis, at [time] (epoch seconds). */
data class TimeTick(val time: Double, val label: String)

private val secondSteps = longArrayOf(1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 10800, 21600, 43200, 86400, 172800, 604800, 1209600)
private val monthSteps = intArrayOf(1, 2, 3, 6, 12)

/**
 * Ticks on round local times between [start] and [end] (epoch seconds), at most [maxTicks]. Under a minute apart they
 * read HH:mm:ss; under a day HH:mm, with the date at midnight; a day or more "dd MMM"; months "MMM yy".
 */
internal fun timeTicks(start: Double, end: Double, maxTicks: Int, zone: ZoneId = ZoneId.systemDefault()): List<TimeTick> {
    if (!start.isFinite() || !end.isFinite() || end <= start || maxTicks < 1) return emptyList()
    val span = end - start
    val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(zone)
    val seconds = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(zone)
    val day = DateTimeFormatter.ofPattern("dd MMM", Locale.ROOT).withZone(zone)
    val month = DateTimeFormatter.ofPattern("MMM yy", Locale.ROOT).withZone(zone)
    val step = secondSteps.firstOrNull { span / it <= maxTicks }
    val out = ArrayList<TimeTick>()
    if (step != null && step < 86400) {
        // Count from local midnight of the first day, so 15-minute ticks fall on :00, :15, :30, :45.
        val first = Instant.ofEpochSecond(floor(start).toLong()).atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond()
        var t = first + ceil((start - first) / step).toLong() * step
        while (t <= end + 1e-6 && out.size < maxTicks + 2) {
            val at = Instant.ofEpochSecond(t)
            val midnight = at.atZone(zone).toLocalTime().toSecondOfDay() == 0
            out += TimeTick(t.toDouble(), when { step < 60 -> seconds.format(at); midnight -> day.format(at); else -> clock.format(at) })
            t += step
        }
        return out
    }
    if (step != null) {
        val days = (step / 86400).toInt()
        var date = Instant.ofEpochSecond(ceil(start).toLong()).atZone(zone).toLocalDate()
        if (date.atStartOfDay(zone).toEpochSecond() < start) date = date.plusDays(1)
        // Weeks start on a Monday; two days count from the epoch.
        while (Math.floorMod(if (days == 7 || days == 14) date.toEpochDay() + 3 else date.toEpochDay(), days.toLong()) != 0L) date = date.plusDays(1)
        while (date.atStartOfDay(zone).toEpochSecond() <= end && out.size < maxTicks + 2) {
            val at = date.atStartOfDay(zone).toInstant()
            out += TimeTick(at.epochSecond.toDouble(), day.format(at)); date = date.plusDays(days.toLong())
        }
        return out
    }
    val months = monthSteps.firstOrNull { span / (it * 30.44 * 86400) <= maxTicks } ?: 12
    var date: LocalDate = Instant.ofEpochSecond(ceil(start).toLong()).atZone(zone).toLocalDate().withDayOfMonth(1)
    if (date.atStartOfDay(zone).toEpochSecond() < start) date = date.plusMonths(1)
    while ((date.monthValue - 1) % months != 0) date = date.plusMonths(1)
    while (date.atStartOfDay(zone).toEpochSecond() <= end && out.size < maxTicks + 2) {
        val at = date.atStartOfDay(zone).toInstant()
        out += TimeTick(at.epochSecond.toDouble(), month.format(at)); date = date.plusMonths(months.toLong())
    }
    return out
}

// ---------------------------------------------------------------------------------------------------------------------
// Numbers

/** Splits "61%" into "61" and "%", "3.70 MiB/s" into "3.70" and " MiB/s". Text without a leading number is all value. */
internal fun splitUnit(text: String): Pair<String, String> {
    val end = text.indexOfFirst { !(it.isDigit() || it == '.' || it == ',' || it == '-' || it == '+' || it == '−') }
    if (end <= 0) return text to ""
    if (text.substring(0, end).none { it.isDigit() }) return text to ""
    return text.substring(0, end) to text.substring(end)
}

/** The unit's share of a value set at [fontSize]: .72, but the unit itself never under 9 sp, where a "%" stops reading. */
internal fun unitScaleFor(fontSize: TextUnit): Float = if (fontSize.isSp && fontSize.value > 0f) (9f / fontSize.value).coerceIn(.72f, 1f) else .72f

/**
 * A value with its units set at [unitScale] of the value's size: "61" large, "%" smaller. Every later number keeps the
 * full size too ("23d 4h", "15/16G"). The text itself is unchanged. Pass [unitScaleFor] the font size for small values.
 */
fun metricText(text: String, unitScale: Float = .72f): AnnotatedString {
    val (number, rest) = splitUnit(text)
    if (rest.isEmpty() || unitScale >= 1f) return AnnotatedString(text)
    val small = SpanStyle(fontSize = unitScale.em, fontWeight = FontWeight.Normal)
    return buildAnnotatedString {
        append(number)
        var i = 0
        while (i < rest.length) {
            val numeric = rest[i].isDigit()
            var j = i + 1
            while (j < rest.length && (rest[j].isDigit() || (numeric && rest[j] == '.' && j + 1 < rest.length && rest[j + 1].isDigit())) == numeric) j++
            if (numeric) append(rest, i, j) else withStyle(small) { append(rest, i, j) }
            i = j
        }
    }
}

/** One measured value: monospaced (tabular digits), unit smaller, aligned to its end so a column of them lines up. */
@Composable fun MetricValue(text: String, modifier: Modifier = Modifier, fontSize: TextUnit = 15.sp, color: Color = Tech.Ink,
    align: TextAlign = TextAlign.End, weight: FontWeight = FontWeight.SemiBold) {
    Text(metricText(text, unitScaleFor(fontSize)), modifier, fontSize = fontSize, lineHeight = fontSize * 1.15f, color = color, fontFamily = Tech.Mono, fontWeight = weight,
        textAlign = align, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
}

/** The small uppercase label above a value. */
@Composable fun MetricLabel(text: String, modifier: Modifier = Modifier, color: Color = Tech.Muted) =
    Text(text.uppercase(), modifier, fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = 1.1.sp, color = color, fontFamily = Tech.Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)

// ---------------------------------------------------------------------------------------------------------------------
// Composables

/**
 * The one figure a panel is about: label, a large value with a smaller unit, an optional caption and trend line.
 * [tone] colours the value only when it is judged.
 */
@Composable fun HeroFigure(label: String, value: String, modifier: Modifier = Modifier, tone: MetricTone = MetricTone.Neutral, caption: String? = null,
    trend: List<Double?>? = null, trendScale: Double = 1.0) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MetricLabel(label)
        MetricValue(value, fontSize = 28.sp, color = if (tone == MetricTone.Neutral) Tech.Ink else tone.color, align = TextAlign.Start)
        caption?.let { Text(it, fontSize = 10.sp, lineHeight = 12.sp, color = Tech.Muted, fontFamily = Tech.Mono, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (trend != null) Sparkline(trend, Modifier.fillMaxWidth().height(24.dp), color = if (tone == MetricTone.Neutral) MetricColors.primary else tone.color,
            floor = trendScale, ceiling = trendScale, pulse = false, grid = false)
    }
}

/**
 * A compact instrument tile: label, value, an optional trend, and the range the trend covered ("min 3 · max 61 %").
 * [onClick] makes the whole tile a button.
 */
@Composable fun StatTile(label: String, value: String, modifier: Modifier = Modifier, tone: MetricTone = MetricTone.Neutral, detail: String? = null,
    trend: List<Double?>? = null, trendScale: Double = 1.0, range: String? = null, onClick: (() -> Unit)? = null) {
    Column(modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MetricLabel(label, Modifier.weight(1f))
            range?.let { Text(it, fontSize = 9.sp, lineHeight = 11.sp, color = Tech.Muted, fontFamily = Tech.Mono, maxLines = 1) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (trend != null) Sparkline(trend, Modifier.weight(1f).height(22.dp), color = if (tone == MetricTone.Neutral) MetricColors.primary else tone.color,
                floor = trendScale, ceiling = trendScale, pulse = false, grid = false)
            MetricValue(value, if (trend == null) Modifier.weight(1f) else Modifier, fontSize = 17.sp, color = if (tone == MetricTone.Neutral) Tech.Ink else tone.color,
                align = if (trend == null) TextAlign.Start else TextAlign.End)
        }
        detail?.let { Text(it, fontSize = 10.sp, lineHeight = 12.sp, color = Tech.Muted, fontFamily = Tech.Mono, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/**
 * A sparkline for a panel. It is quiet until the pointer is on it; then it names its scale at the left edge (the top
 * and zero) and the sample under the pointer in a readout above the line: its value, and its time when [times] is
 * given. The scale starts at zero and its top is a round value of [kind] that holds the peak; [format] writes a value
 * (default: the axis's own label); [thresholds] adds faint guides where a fraction changes meaning.
 */
@Composable fun MetricSparkline(values: List<Double?>, modifier: Modifier = Modifier, kind: AxisKind = AxisKind.Count, color: Color = MetricColors.primary,
    times: List<Instant>? = null, format: ((Double) -> String)? = null, thresholds: MetricThresholds? = null) {
    val cursor = remember { SparkHover() }
    val peak = remember(values) { peakOf(values) ?: 0.0 }
    val axis = remember(kind, peak) { axisScale(kind, peak, maxTicks = 2, headroom = 1.0) }
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val labelColor = MetricColors.axis; val plate = Tech.Panel.copy(alpha = .82f)
    fun text(v: Double) = format?.invoke(v) ?: axis.label(v)
    Box(modifier) {
        Sparkline(values, Modifier.matchParentSize(), color = color, floor = axis.top, ceiling = axis.top, pulse = false, times = times,
            label = { i -> values.getOrNull(i)?.takeIf { it.isFinite() && it >= 0.0 }?.let(::text) ?: "no sample" }, hover = cursor,
            guides = thresholds?.let { listOf(it.attention, it.critical) }.orEmpty())
        // The scale appears with the pointer and is drawn from the hover state, so showing it rebuilds nothing.
        Box(Modifier.matchParentSize().sparkTrack(cursor).drawWithCache {
            val style = TextStyle(fontSize = 8.sp, fontFamily = Tech.Mono, color = labelColor)
            val top = measurer.measure(text(axis.top), style); val zero = measurer.measure(text(0.0), style)
            val pad = 1.dp.toPx()
            onDrawBehind {
                if (cursor.x == null) return@onDrawBehind
                drawRect(plate, Offset(pad, pad), Size(top.size.width + 3 * pad, top.size.height.toFloat()))
                drawText(top, topLeft = Offset(2.5f * pad, pad))
                if (size.height >= top.size.height + zero.size.height + 4 * pad) {
                    val y = size.height - zero.size.height - pad
                    drawRect(plate, Offset(pad, y), Size(zero.size.width + 3 * pad, zero.size.height.toFloat()))
                    drawText(zero, topLeft = Offset(2.5f * pad, y))
                }
            }
        })
    }
}

/** Threshold notches across a bar or ring track: the attention and critical positions, drawn in the track's gap colour. */
private fun DrawScope.barNotches(thresholds: MetricThresholds, color: Color) {
    for (t in listOf(thresholds.attention, thresholds.critical)) {
        val x = (size.width * t).toFloat()
        drawLine(color, Offset(x, -1f), Offset(x, size.height + 1f), 1.5f)
    }
}

/**
 * A bar gauge: the fill in the tone of [fraction] against [thresholds] (or [color] for a bar that only ranks), notches at
 * both thresholds, an optional [peak] tick, and a dashed track for "no value" so it never reads as zero.
 */
@Composable fun BarGauge(fraction: Double?, modifier: Modifier = Modifier, thresholds: MetricThresholds = MetricThresholds.Load, color: Color? = null,
    height: Dp = 4.dp, notches: Boolean = true, peak: Double? = null) {
    val reduce = LocalReduceMotion.current
    val shown = fraction?.takeIf { it.isFinite() && it >= 0.0 }
    val f by animateFloatAsState((shown ?: 0.0).toFloat().coerceIn(0f, 1f), tween(if (reduce) 0 else 350))
    val tint = color ?: thresholds.tone(shown).color
    val track = MetricColors.track
    val gap = Tech.Panel
    Box(modifier.height(height).drawWithCache {
        val dash = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))
        onDrawBehind {
            if (shown == null) { drawLine(track, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height.coerceAtMost(2f), pathEffect = dash); return@onDrawBehind }
            drawRect(track)
            drawRect(tint.copy(alpha = if (Appearance.palette.highContrast) 1f else .9f), size = Size(size.width * f, size.height))
            if (notches && color == null) barNotches(thresholds, gap)
            peak?.takeIf { it.isFinite() && it > 0.0 }?.let { p -> val x = (size.width * p.coerceIn(0.0, 1.0)).toFloat(); drawLine(Tech.Ink, Offset(x, -1f), Offset(x, size.height + 1f), 1.5f) }
        }
    })
}

/**
 * A ring gauge: a full circle from twelve o'clock, threshold notches, an optional [peak] tick and the percentage (or
 * [centre]) in the middle. Used by the storage cards; the kit's round counterpart of [BarGauge].
 */
@Composable fun RingGauge(fraction: Double?, modifier: Modifier = Modifier, size: Dp = 56.dp, thresholds: MetricThresholds = MetricThresholds.Capacity,
    stroke: Dp = 5.dp, peak: Double? = null, centre: String? = null, tint: Color? = null, description: String? = null, extra: (DrawScope.(inset: Float, ring: Size) -> Unit)? = null) {
    val reduce = LocalReduceMotion.current
    val shown = fraction?.takeIf { it.isFinite() && it >= 0.0 }
    val v by animateFloatAsState((shown ?: 0.0).toFloat().coerceIn(0f, 1f), tween(if (reduce) 0 else 300))
    val tone = tint ?: thresholds.tone(shown).color
    val label = centre ?: shown?.let { "${(it * 100).roundToInt()}%" } ?: "—"
    Box(modifier.size(size).semantics { contentDescription = description ?: "Usage $label" }, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().drawWithCache {
            val w = stroke.toPx(); val inset = w / 2 + 1.dp.toPx()
            val ring = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val line = Stroke(w)
            val track = MetricColors.track; val gap = Tech.Panel
            onDrawBehind {
                drawArc(track, -90f, 360f, false, Offset(inset, inset), ring, style = line)
                if (shown != null) drawArc(tone, -90f, 360f * v, false, Offset(inset, inset), ring, style = line)
                for (t in listOf(thresholds.attention, thresholds.critical)) drawArc(gap, -90f + 360f * t.toFloat() - 1f, 2f, false, Offset(inset, inset), ring, style = line)
                peak?.takeIf { it.isFinite() && it > 0.0 }?.let { p -> drawArc(Tech.Ink, -90f + 360f * p.toFloat().coerceIn(0f, 1f) - 1f, 2f, false, Offset(inset - w * .35f, inset - w * .35f), Size(ring.width + w * .7f, ring.height + w * .7f), style = Stroke(w * 1.7f)) }
                extra?.invoke(this, inset, ring)
            }
        })
        val fontSize = if (size >= 64.dp) 15.sp else 10.sp
        Text(metricText(label, unitScaleFor(fontSize)), fontFamily = Tech.Mono, fontSize = fontSize, lineHeight = 14.sp, color = Tech.Ink, fontWeight = FontWeight.SemiBold)
    }
}

/** One part of a [StackedBar]. A [hatched] part is drawn striped: "not listed", "other", or an estimate. */
data class BarSegment(val label: String, val value: Double, val color: Color, val hatched: Boolean = false)

/**
 * A stacked breakdown: segments side by side on one scale of [total] (default: their sum), a 1-unit gap between parts
 * so neighbours of similar colour stay apart, an optional dashed [marker] (for example a recorded total), and an
 * optional legend with each part's value.
 */
@Composable fun StackedBar(segments: List<BarSegment>, modifier: Modifier = Modifier, total: Double? = null, height: Dp = 12.dp, marker: Double? = null,
    legend: ((Double) -> String)? = null) {
    val sum = segments.sumOf { it.value.coerceAtLeast(0.0) }
    val scale = (total ?: sum).takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(height).drawWithCache {
            val track = MetricColors.track; val gap = Tech.Panel
            val dash = PathEffect.dashPathEffect(floatArrayOf(3f, 2f))
            onDrawBehind {
                drawRect(track)
                var x = 0f
                segments.forEach { s ->
                    val w = (s.value.coerceAtLeast(0.0) / scale * size.width).toFloat().coerceAtMost(size.width - x)
                    if (w <= 0f) return@forEach
                    if (s.hatched) {
                        drawRect(s.color.copy(alpha = .3f), Offset(x, 0f), Size(w, size.height))
                        clipRect(x, 0f, x + w, size.height) { var k = x - size.height; while (k < x + w) { drawLine(s.color, Offset(k, size.height), Offset(k + size.height, 0f), 1f); k += 6f } }
                    } else drawRect(s.color, Offset(x, 0f), Size(w, size.height))
                    x += w
                    if (x < size.width - 1f) drawLine(gap, Offset(x, 0f), Offset(x, size.height), 1.5f)
                }
                marker?.takeIf { it.isFinite() && it > 0.0 }?.let { m -> val mx = (m / scale * size.width).toFloat().coerceIn(0f, size.width); drawLine(Tech.Ink, Offset(mx, -2f), Offset(mx, size.height + 2f), 2f, pathEffect = dash) }
            }
        })
        if (legend != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            segments.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(9.dp).drawWithCache { onDrawBehind { if (s.hatched) { drawRect(s.color.copy(alpha = .3f)); drawLine(s.color, Offset(0f, size.height), Offset(size.width, 0f), 1f) } else drawRect(s.color) } })
                    Text("${s.label} ", fontSize = 10.sp, color = Tech.Ink, fontFamily = Tech.Mono, maxLines = 1)
                    Text(legend(s.value), fontSize = 10.sp, color = Tech.Muted, fontFamily = Tech.Mono, maxLines = 1)
                }
            }
        }
    }
}

/** One square of a [StatusGrid]. */
data class StatusCell(val label: String, val tone: MetricTone)

/**
 * Many small states at once (services, checks, guests). The tone also changes the shape, so the grid reads without
 * colour: good is a filled square, attention a square with a cut corner, critical a filled triangle, neutral an outline.
 */
@Composable fun StatusGrid(cells: List<StatusCell>, modifier: Modifier = Modifier, cell: Dp = 12.dp, gap: Dp = 3.dp) {
    FlowRow(modifier.semantics { contentDescription = cells.groupingBy { it.tone }.eachCount().entries.joinToString(", ") { "${it.value} ${it.key.name.lowercase()}" } },
        horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
        cells.forEach { c ->
            HelpTip("${c.label} · ${c.tone.name.lowercase()}") {
                Box(Modifier.size(cell).drawWithCache {
                    val color = c.tone.color
                    val path = Path()
                    when (c.tone) {
                        MetricTone.Critical -> { path.moveTo(size.width / 2, 0f); path.lineTo(size.width, size.height); path.lineTo(0f, size.height); path.close() }
                        MetricTone.Attention -> { path.moveTo(0f, 0f); path.lineTo(size.width * .6f, 0f); path.lineTo(size.width, size.height * .4f); path.lineTo(size.width, size.height); path.lineTo(0f, size.height); path.close() }
                        else -> path.addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                    }
                    onDrawBehind { if (c.tone == MetricTone.Neutral) drawPath(path, color, style = Stroke(1.dp.toPx())) else drawPath(path, color) }
                })
            }
        }
    }
}

/** One row of a [TopList]. [fraction] is the bar's length on a 0..1 scale; [tone] colours it only when the value is judged. */
data class TopItem(val label: String, val fraction: Double?, val value: String, val tone: MetricTone = MetricTone.Neutral, val identity: Boolean = false)

/**
 * A ranked list with bars. Bars of a ranking ([thresholds] null) share one neutral colour, so the longest bar is not
 * mistaken for a warning; with [thresholds] each bar takes its tone and the threshold notches.
 */
@Composable fun TopList(items: List<TopItem>, modifier: Modifier = Modifier, barWidth: Dp = 96.dp, valueWidth: Dp = 64.dp, thresholds: MetricThresholds? = null,
    onClick: ((Int) -> Unit)? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { i, item ->
            Row(Modifier.fillMaxWidth().heightIn(min = 24.dp).then(if (onClick != null) Modifier.clickable { onClick(i) } else Modifier),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.label, Modifier.weight(1f).then(if (item.identity) Modifier.identity() else Modifier), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BarGauge(item.fraction, Modifier.width(barWidth), thresholds = thresholds ?: MetricThresholds.Load, color = if (thresholds == null) MetricColors.rank else null, height = 5.dp)
                MetricValue(item.value, Modifier.widthIn(min = valueWidth), fontSize = 11.sp, weight = FontWeight.Normal,
                    color = if (item.tone == MetricTone.Neutral) Tech.Ink.dimmed(.85f) else item.tone.color)
            }
        }
    }
}

/** A legend: one swatch per series; a [dashed] entry is a capacity line. */
data class LegendEntry(val label: String, val color: Color, val dashed: Boolean = false)

@Composable fun ChartLegend(entries: List<LegendEntry>, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(12.dp, 8.dp).drawWithCache {
                    val dash = PathEffect.dashPathEffect(floatArrayOf(3f, 2f))
                    onDrawBehind {
                        if (e.dashed) drawLine(e.color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5.dp.toPx(), pathEffect = dash)
                        else drawLine(e.color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(), cap = StrokeCap.Round)
                    }
                })
                Text(e.label, fontSize = 9.sp, lineHeight = 11.sp, color = Tech.Muted, fontFamily = Tech.Mono, maxLines = 1)
            }
        }
    }
}

/** "min 3 · max 61 %" over a series' finite, non-negative values, or null when there are none. */
internal fun rangeText(values: List<Double?>, format: (Double) -> String): String? {
    var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
    for (v in values) if (v != null && v.isFinite() && v >= 0.0) { if (v < lo) lo = v; if (v > hi) hi = v }
    return if (hi < lo) null else "${format(lo)}–${format(hi)}"
}

/** Whole percent for compact ranges: "3", "61", "100". */
internal fun wholePercent(v: Double): String = (v * 100).roundToInt().toString()

/** Largest finite, non-negative value, or null. */
internal fun peakOf(values: List<Double?>): Double? = values.asSequence().filterNotNull().filter { it.isFinite() && it >= 0.0 }.maxOrNull()

/** A small chart's heading: its label at the left, the legend of its series (and an optional value) at the right. */
@Composable fun MetricHeading(label: String, legend: List<LegendEntry> = emptyList(), trailing: String? = null, modifier: Modifier = Modifier) =
    Row(modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), fontSize = 9.sp, letterSpacing = 1.sp, color = Tech.Muted, fontFamily = Tech.Mono, maxLines = 1)
        if (legend.isNotEmpty()) ChartLegend(legend)
        trailing?.let { Text(it, fontSize = 9.sp, color = Tech.Muted, fontFamily = Tech.Mono, maxLines = 1) }
    }
