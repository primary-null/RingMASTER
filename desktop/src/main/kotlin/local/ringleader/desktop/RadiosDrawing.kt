package local.ringleader.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The Radios drawing kit: one glyph and one chip shape per kind, so Bluetooth and Wi-Fi are told apart by shape and by
 * label as well as by colour. Bluetooth is the bind rune on a cut-corner plate; Wi-Fi is a fan of solid bands on a rounded
 * pill. Both marks are drawn for the sizes they are shown at (10 to 20 dp) and fitted to the pixel grid there.
 */

/** Bluetooth's ink: the chart blue. Wi-Fi's: the chart teal. Neither is a verdict colour (green or red). */
internal fun radioTint(kind: RadioKind): Color = when (kind) {
    RadioKind.Bluetooth -> MetricColors.secondary
    RadioKind.WiFi -> MetricColors.category(2)
}

/**
 * The ink of a mark in [state]. While the adapter is on it wears its kind's tint, a little quieter when nothing is using
 * it; once it is off or disabled it wears the muted ink. Shape says the same: only an active mark is filled, and a
 * disabled one is struck through.
 */
internal fun radioInk(kind: RadioKind, state: RadioState): Color = when (state) {
    RadioState.Active -> radioTint(kind)
    RadioState.Neutral -> radioTint(kind).dimmed(.8f)
    RadioState.Inactive -> Tech.Muted
    RadioState.Disabled -> Tech.Muted.dimmed(.75f)
}

/** A soft swell, 0 at the start and end of [phase] and 1 in the middle. */
private fun swell(phase: Float): Float = .5f - .5f * cos(2f * PI.toFloat() * (phase - floor(phase)))

/** A stroke across a mark that cannot be used. */
private fun DrawScope.strike(ink: Color) {
    val s = size.minDimension
    drawLine(ink, Offset(s * .14f, s * .14f), Offset(s * .86f, s * .86f), max(1.4f, s * .09f), StrokeCap.Round)
}

/** Bluetooth chips have cut corners; Wi-Fi chips are rounded. */
internal fun radioShape(kind: RadioKind, corner: Dp = 7.dp): Shape = when (kind) {
    RadioKind.Bluetooth -> CutCornerShape(corner)
    RadioKind.WiFi -> RoundedCornerShape(50)
}

/**
 * The Bluetooth rune as one path: upper left arm, through the crossing to the lower right point, the foot of the stem, up
 * the stem, the upper right point, back through the crossing to the lower left arm. [x] and [d] are how far the points
 * reach sideways and up from the crossing; the stem is twice [d] each way.
 */
private fun runePath(cx: Float, mid: Float, x: Float, d: Float) = Path().apply {
    moveTo(cx - x, mid - d); lineTo(cx + x, mid + d); lineTo(cx, mid + 2 * d); lineTo(cx, mid - 2 * d); lineTo(cx + x, mid - d); lineTo(cx - x, mid + d)
}

/**
 * The Bluetooth mark. From 16 pixels up it is drawn like the sign on a device: sharp mitred points, arms cut square, and
 * the two loops filled with a wash of the tint so the mark has body on a dark page. The mitres are measured so the points
 * touch the box without leaving it. Below 16 pixels there is no room for that: the rune is a lighter round stroke whose
 * points reach wider, so the loops stay open, with the stem and the crossing on pixel centres.
 *
 * [state] changes the drawing, not only the ink the caller passes: the loops are filled only while the adapter is active
 * (and breathe with [pulse], 0 to 1, when one is given), and a disabled adapter is struck through.
 */
internal fun DrawScope.bluetoothMark(tint: Color, state: RadioState = RadioState.Active, pulse: Float? = null) {
    val s = size.minDimension
    if (s < 16f) {
        val stroke = if (s < 12f) 1.2f else 1.2f + (s - 12f) * .1f
        val cx = floor(size.width / 2f) + .5f
        val mid = floor(size.height / 2f) + .5f
        val room = minOf(mid, size.height - mid) - stroke / 2f
        val d = floor(room * 2f) / 4f
        drawPath(runePath(cx, mid, d * 1.3f, d), tint, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (state == RadioState.Disabled) strike(tint)
        return
    }
    val stroke = max(1.8f, s * .092f)
    val ratio = 1.12f // the points reach a little wider than they are high, as on the real sign
    // How far a mitre runs past its corner: at the stem ends (between the stem and a diagonal) and at the side points.
    val stemAngle = atan2(ratio, 1f)
    val tipUp = stroke / 2f / sin(stemAngle / 2f) * cos(stemAngle / 2f)
    val sideAngle = PI.toFloat() - 2f * atan2(1f, ratio)
    val tipSide = stroke / 2f / sin(sideAngle / 2f)
    val d = (s / 2f - tipUp - s * .01f) / 2f
    val x = d * ratio
    // Arms end at the left edge of the mark, points at the right: centre the whole on the box, then put the stem on the grid.
    val odd = max(1, stroke.roundToInt()) % 2 == 1
    val ideal = size.width / 2f - tipSide / 2f
    val cx = if (odd) floor(ideal) + .5f else ideal.roundToInt().toFloat()
    val mid = size.height / 2f
    val loops = Path().apply {
        moveTo(cx, mid - 2 * d); lineTo(cx + x, mid - d); lineTo(cx, mid); lineTo(cx + x, mid + d); lineTo(cx, mid + 2 * d); close()
    }
    if (state == RadioState.Active) drawPath(loops, tint.copy(alpha = .22f + .2f * (pulse?.let(::swell) ?: 0f)), style = Fill)
    drawPath(runePath(cx, mid, x, d), tint, style = Stroke(stroke, miter = 8f, cap = StrokeCap.Butt, join = StrokeJoin.Miter))
    if (state == RadioState.Disabled) strike(tint)
}

/**
 * The Wi-Fi mark: solid bands of a sector standing on its dot, fitted inside the box and centred in it. Bands above [bars]
 * (0 to 3) are dimmed, not removed, so the outline is the same for a weak signal.
 *
 * From 20 pixels up the bands lie on a wash of the whole fan, which gives the mark body. Below that it is set on the pixel
 * grid: whole-pixel bands and gaps measured from a square dot, so the crown of each band covers whole rows, and two bands
 * instead of three below 13 pixels so the gaps never close.
 *
 * [state] changes the drawing, not only the ink the caller passes: the wash lies under the bands only while the adapter is
 * active, the bands then light one after another outwards with [pulse] (0 to 1) when one is given, and a disabled adapter
 * is struck through.
 */
internal fun DrawScope.wifiMark(tint: Color, bars: Int = 3, state: RadioState = RadioState.Active, pulse: Float? = null) {
    val s = size.minDimension
    fun ink(band: Int, count: Int): Color {
        val base = if (band <= (if (count == 2) (bars + 1) / 2 else bars)) 1f else .3f
        // A wave runs from the dot outwards: each band is at its brightest a moment after the one inside it.
        val live = if (state == RadioState.Active && pulse != null) .55f + .45f * swell(pulse - band * .17f) else 1f
        return tint.copy(alpha = tint.alpha * base * live)
    }
    if (s >= 20f) {
        val sweep = 92f
        val outer = minOf(s * .5f / sin(sweep / 2f * (PI.toFloat() / 180f)), s * .76f)
        val thick = outer * .175f; val gap = outer * .115f; val dot = thick * .8f
        val cx = size.width / 2f
        val apexY = size.height / 2f + (outer - dot) / 2f
        val fan = Path().apply {
            moveTo(cx, apexY); arcTo(Rect(cx - outer, apexY - outer, cx + outer, apexY + outer), 270f - sweep / 2f, sweep, false); close()
        }
        if (state == RadioState.Active) drawPath(fan, tint.copy(alpha = .16f), style = Fill)
        for (i in 1..3) {
            val r = outer - thick / 2f - (3 - i) * (thick + gap)
            drawArc(ink(i, 3), 270f - sweep / 2f, sweep, false, Offset(cx - r, apexY - r), Size(r * 2, r * 2), style = Stroke(thick, cap = StrokeCap.Butt))
        }
        drawCircle(tint, dot, Offset(cx, apexY))
        if (state == RadioState.Disabled) strike(tint)
        return
    }
    val px = s.roundToInt()
    val count = if (px < 13) 2 else 3
    val dotSize = if (px >= 16) 3 else 2
    val dotR = dotSize / 2f
    val target = s * .74f
    var thick = 2; var gap = 1; var miss = Float.MAX_VALUE
    for (t in 1..4) for (g in 1..3) {
        if (t > 2 * g || g > t) continue
        val outer = dotR + count * (t + g)
        if (outer + dotR > s) continue
        val off = abs(outer - target)
        if (off < miss - .01f || (abs(off - miss) <= .01f && t > thick)) { thick = t; gap = g; miss = off }
    }
    val outer = dotR + count * (thick + gap)
    // The dot covers whole pixel columns: an even dot is centred on a pixel edge, an odd one on a pixel centre.
    val cx = if (dotSize % 2 == 0) (size.width / 2f).roundToInt().toFloat() else floor(size.width / 2f) + .5f
    val apexY = floor((s - (outer + dotR)) / 2f) + outer
    val half = minOf(46.0, asin((s / 2f / outer).toDouble().coerceAtMost(1.0)) * 180.0 / PI).toFloat()
    for (i in 1..count) {
        val r = dotR + i * (thick + gap) - thick / 2f
        val band = Stroke(thick.toFloat(), cap = StrokeCap.Butt)
        drawArc(ink(i, count), 270f - half, half * 2, false, Offset(cx - r, apexY - r), Size(r * 2, r * 2), style = band)
    }
    drawRect(tint, Offset(cx - dotR, apexY - dotR), Size(dotSize.toFloat(), dotSize.toFloat()))
    if (state == RadioState.Disabled) strike(tint)
}

/**
 * The mark on a plate of its own shape (cut corners or a disc): the icon of an adapter on its chip, showing its [state].
 * Active: the plate is washed with the tint and breathes with [pulse]. Ready: an empty plate with a tinted edge.
 * Inactive: the same in the muted ink. Disabled: a dashed edge and the mark struck through.
 */
@Composable internal fun RadioBadge(
    kind: RadioKind, modifier: Modifier = Modifier, size: Dp = 26.dp,
    state: RadioState = RadioState.Active, pulse: State<Float>? = null,
) {
    val ink = radioInk(kind, state)
    val shape: Shape = if (kind == RadioKind.Bluetooth) CutCornerShape(size * .22f) else RoundedCornerShape(50)
    Box(modifier.size(size).clearAndSetSemantics { }.drawWithCache {
        val outline = shape.createOutline(this.size, layoutDirection, this)
        val line = 1.dp.toPx()
        val dashes = if (state == RadioState.Disabled) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.5.dp.toPx())) else null
        onDrawBehind {
            val breath = if (state == RadioState.Active) pulse?.value?.let(::swell) ?: 0f else 0f
            if (state == RadioState.Active) drawOutline(outline, ink.copy(alpha = .13f + .12f * breath))
            val edge = when (state) { RadioState.Active -> .6f + .4f * breath; RadioState.Neutral -> .55f; else -> .5f }
            drawOutline(outline, ink.copy(alpha = ink.alpha * edge), style = Stroke(line, pathEffect = dashes))
        }
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * .64f)) {
            val beat = if (state == RadioState.Active) pulse?.value else null
            if (kind == RadioKind.Bluetooth) bluetoothMark(ink, state, beat) else wifiMark(ink, 3, state, beat)
        }
    }
}

/** The glyph of [kind], [size] square, decorative (the label beside it says the kind). [state] is the adapter's, when it stands for one. */
@Composable internal fun RadioGlyph(
    kind: RadioKind, modifier: Modifier = Modifier, size: Dp = 14.dp,
    state: RadioState = RadioState.Active, tint: Color = radioInk(kind, state),
) {
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        when (kind) {
            RadioKind.Bluetooth -> bluetoothMark(tint, state)
            RadioKind.WiFi -> wifiMark(tint, 3, state)
        }
    }
}

/** The colour of a state's word and pip: green for in use, the plain ink for ready, the muted ink for the rest. */
internal fun radioStateTint(state: RadioState): Color = when (state) {
    RadioState.Active -> Tech.Green
    RadioState.Neutral -> Tech.Ink
    RadioState.Inactive, RadioState.Disabled -> Tech.Muted
}

/** A state as a small sign beside its word: a disc (active), a ring (ready), a bar (inactive), a struck ring (disabled). */
@Composable internal fun RadioStatePip(state: RadioState, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val ink = radioStateTint(state)
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val r = this.size.minDimension / 2f
        val line = max(1.2f, r * .36f)
        when (state) {
            RadioState.Active -> drawCircle(ink, r)
            RadioState.Neutral -> drawCircle(ink, r - line / 2f, style = Stroke(line))
            RadioState.Inactive -> drawLine(ink, Offset(0f, center.y), Offset(this.size.width, center.y), line * 1.3f, StrokeCap.Butt)
            RadioState.Disabled -> {
                drawCircle(ink, r - line / 2f, style = Stroke(line))
                drawLine(ink, Offset(r * .45f, r * .45f), Offset(r * 1.55f, r * 1.55f), line, StrokeCap.Butt)
            }
        }
    }
}

/** The state of an adapter in words with its sign, for a chip or a line of detail. */
@Composable internal fun RadioStateTag(state: RadioState, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RadioStatePip(state, size = 7.dp)
        Text(
            state.label.uppercase(), fontFamily = Tech.Mono, fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = .8.sp,
            color = radioStateTint(state), maxLines = 1, softWrap = false,
        )
    }
}

/** A small "BT" or "WI-FI" label on the kind's own shape. */
@Composable internal fun RadioKindChip(kind: RadioKind, modifier: Modifier = Modifier) {
    val tint = radioTint(kind)
    Row(modifier.border(1.dp, tint, radioShape(kind, 5.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RadioGlyph(kind, size = 10.dp, tint = tint)
        Text(
            kind.short.uppercase(), fontFamily = Tech.Mono, fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold,
            color = tint, maxLines = 1, softWrap = false,
        )
    }
}

/** How sure the app is, as a tag: nothing for certain, a word for likely and a guess (never colour alone). */
@Composable internal fun RadioSureTag(card: RadioCard) {
    when (card.sure) {
        RadioSure.Certain -> Unit
        RadioSure.Likely -> NeutralTag("Likely", help = card.sureText + ", from its ids and driver.")
        RadioSure.Guess -> AttentionTag("Guess", help = card.sureText + ". The app could not confirm it.")
    }
}

/** Signal in dBm as four bars: below -90 is none, -50 and stronger is all four. */
internal fun wifiBars(dbm: Int?): Int = if (dbm == null) 0 else ((dbm.coerceIn(-90, -50) + 90) * 4 + 39) / 40

/** Words for a signal, so the bars are never the only cue. */
internal fun signalWord(dbm: Int?): String = when {
    dbm == null -> "No reading"
    dbm >= -55 -> "Excellent"
    dbm >= -65 -> "Good"
    dbm >= -75 -> "Fair"
    else -> "Weak"
}

/** The tone of a signal: green strong, amber fair, muted weak. */
internal fun wifiSignalTint(dbm: Int?): Color = when {
    dbm == null -> Tech.Muted
    dbm >= -65 -> Tech.Green
    dbm >= -75 -> Tech.Amber
    else -> Tech.Muted
}

@Composable internal fun SignalBars(dbm: Int?, modifier: Modifier = Modifier, width: Dp = 18.dp, height: Dp = 12.dp) {
    val bars = wifiBars(dbm)
    val tint = wifiSignalTint(dbm)
    val edge = Tech.Edge
    Canvas(modifier.size(width, height).clearAndSetSemantics { }) {
        val gap = 1.5.dp.toPx()
        val bw = (size.width - gap * 3) / 4f
        repeat(4) { i ->
            val h = size.height * (.3f + .7f * i / 3f)
            drawRect(if (i < bars) tint else edge, Offset(i * (bw + gap), size.height - h), Size(bw, h))
        }
    }
}

/**
 * A panel with a coloured rail on its left, like [TechPanel], but measured normally: TechPanel sizes its rail by intrinsic
 * height, and a FlowRow inside it (the Radios notes and button rows) could lose its last row.
 */
@Composable internal fun RadioPanel(
    modifier: Modifier = Modifier, rail: Color = Tech.Edge, padding: Dp = Tech.Space12,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.clip(Tech.Plate).background(Tech.Panel).border(1.dp, Tech.Edge, Tech.Plate)
        .drawBehind { drawRect(rail, size = Size(3.dp.toPx(), size.height)) }
        .padding(start = 3.dp + padding, top = padding, end = padding, bottom = padding), content = content)
}
