package local.ringleader.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The light vocabulary: one pre-baked radial glow sprite drawn with BlendMode.Plus (additive light, no blur), phosphor
 * decays, stroke-drawn glyphs, CRT collapses. Everything here is either a pure function of time (so tests and the probe
 * can sample it at any timestamp) or a DrawScope helper that allocates nothing per frame.
 */

/** 128 px radial glow sprites, baked once per colour. Blur is never used: it has an artifact bug on Windows OpenGL (CMP-7135). */
object GlowSprites {
    private val cache = HashMap<Long, ImageBitmap>()
    fun sprite(color: Color, px: Int = 128): ImageBitmap = synchronized(cache) {
        cache.getOrPut(color.value.toLong() * 31L + px) { bakeGlow(color, px) }
    }
}

internal fun bakeGlow(color: Color, px: Int = 128): ImageBitmap {
    val bitmap = ImageBitmap(px, px)
    val half = px / 2f
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(px.toFloat(), px.toFloat())) {
        drawCircle(
            Brush.radialGradient(0f to color, 0.35f to color.copy(alpha = 0.35f), 1f to Color.Transparent, center = Offset(half, half), radius = half),
            radius = half, center = Offset(half, half),
        )
    }
    return bitmap
}

/** Additive light on a dark theme; on a light theme additive white-on-light is invisible, so it composites normally and a little dimmer. */
internal fun lightBlend(): BlendMode = if (Tech.Light) BlendMode.SrcOver else BlendMode.Plus
internal fun lightScale(): Float = if (Tech.Light) 0.6f else 1f

internal fun DrawScope.glow(sprite: ImageBitmap, center: Offset, radius: Float, alpha: Float, blend: BlendMode = lightBlend()) {
    if (alpha <= 0.002f || radius <= 0.5f) return
    drawImage(
        sprite,
        dstOffset = IntOffset((center.x - radius).toInt(), (center.y - radius).toInt()),
        dstSize = IntSize((radius * 2).toInt().coerceAtLeast(1), (radius * 2).toInt().coerceAtLeast(1)),
        alpha = alpha.coerceIn(0f, 1f), blendMode = blend,
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// Time functions (pure)
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Unit-mass spring step response from 0 to 1 with no initial velocity, at [tSec] seconds. For ζ < 1 this overshoots
 * (spring.pop, ζ 0.6, k 800, peaks 9.5% over), at ζ = 1 it is critically damped. Matches what Compose's spring() does.
 */
fun springStep(damping: Float, stiffness: Float, tSec: Float): Float {
    if (tSec <= 0f) return 0f
    val w = sqrt(stiffness)
    return if (damping < 1f) {
        val wd = w * sqrt(1f - damping * damping)
        val decay = exp(-damping * w * tSec)
        1f - decay * (cos(wd * tSec) + (damping * w / wd) * sin(wd * tSec))
    } else {
        1f - exp(-w * tSec) * (1f + w * tSec)
    }
}

/** Afterglow envelope: a quick linear rise, then a fast-then-lingering decay on the entrance curve, like phosphor persistence. */
fun phosphorEnvelope(tMs: Float, riseMs: Float = AFTERGLOW_RISE_MS, decayMs: Float = AFTERGLOW_DECAY_MS): Float {
    if (tMs <= 0f) return 0f
    if (tMs < riseMs) return tMs / riseMs
    val d = ((tMs - riseMs) / decayMs).coerceIn(0f, 1f)
    return 1f - MotionEase.Enter.transform(d)
}
const val AFTERGLOW_RISE_MS = 60f
const val AFTERGLOW_DECAY_MS = 340f
/** Rise plus decay is the 400 ms ceiling for a signature flourish. */
const val AFTERGLOW_TOTAL_MS = 400

/** Nielsen's response-time limits: under 1 s feels instant, past 10 s the user is really waiting. */
enum class WaitLevel { Instant, Brief, Long }

fun waitLevel(waitedMs: Long): WaitLevel = when {
    waitedMs < 1_000L -> WaitLevel.Instant
    waitedMs < 10_000L -> WaitLevel.Brief
    else -> WaitLevel.Long
}

/** 0 at no wait, 1 at a minute or more, on a log curve so the first seconds count most. */
fun waitedFactor(waitedMs: Long): Float {
    val seconds = max(0L, waitedMs) / 1000f
    return (ln(1f + seconds) / ln(61f)).coerceIn(0f, 1f)
}

/**
 * The success glow, scaled to how long the user waited: the longer the wait, the brighter and wider the bloom.
 * Duration never grows (the flourish stays within [AFTERGLOW_TOTAL_MS]); a long wait also earns the edge sweep.
 */
data class AfterglowPlan(val level: WaitLevel, val intensity: Float, val spreadDp: Float, val sweep: Boolean)

fun afterglowPlan(waitedMs: Long): AfterglowPlan {
    val f = waitedFactor(waitedMs)
    return AfterglowPlan(waitLevel(waitedMs), intensity = 0.10f + 0.20f * f, spreadDp = 10f * f, sweep = waitLevel(waitedMs) == WaitLevel.Long)
}

/** The CRT switching off: the glyph squashes to a bright line, the line narrows to a dot, the dot decays. */
data class CollapseFrame(val scaleY: Float, val scaleX: Float, val lineAlpha: Float, val dotGlow: Float, val glitchPx: Float)

const val COLLAPSE_SQUASH_MS = 120f
const val COLLAPSE_DECAY_MS = 400f
const val HARD_COLLAPSE_SQUASH_MS = 110f
const val HARD_COLLAPSE_DECAY_MS = 120f
const val HARD_GLITCH_MS = 16f

fun crtCollapseDurationMs(hard: Boolean): Int =
    if (hard) (HARD_COLLAPSE_SQUASH_MS + HARD_GLITCH_MS + HARD_COLLAPSE_DECAY_MS).toInt() else (COLLAPSE_SQUASH_MS + COLLAPSE_DECAY_MS).toInt()

/**
 * Graceful: squash on a decelerate curve (about the settle time of spring.spatial.fast), then a 400 ms phosphor decay.
 * Hard stop: a deliberate cut, 110 ms on a linear ramp with no ease, a one-frame 2 px horizontal glitch, a short decay.
 */
fun crtCollapseFrame(tMs: Float, hard: Boolean): CollapseFrame {
    val squash = if (hard) HARD_COLLAPSE_SQUASH_MS else COLLAPSE_SQUASH_MS
    val glitch = if (hard) HARD_GLITCH_MS else 0f
    val decay = if (hard) HARD_COLLAPSE_DECAY_MS else COLLAPSE_DECAY_MS
    if (tMs <= 0f) return CollapseFrame(1f, 1f, 0f, 0f, 0f)
    if (tMs < squash) {
        val p = tMs / squash
        val e = if (hard) p else MotionEase.Enter.transform(p)
        return CollapseFrame(scaleY = 1f - 0.92f * e, scaleX = 1f, lineAlpha = 1f, dotGlow = 0f, glitchPx = 0f)
    }
    val after = tMs - squash
    val d = (after / (glitch + decay)).coerceIn(0f, 1f)
    val line = 1f - MotionEase.Enter.transform(((after - glitch) / decay).coerceIn(0f, 1f))
    return CollapseFrame(
        scaleY = 0.08f, scaleX = 1f - 0.75f * MotionEase.Standard.transform(d),
        lineAlpha = if (after < glitch) 1f else line, dotGlow = line,
        glitchPx = if (hard && after < glitch) 2f else 0f,
    )
}

/** Camera brackets: snap in from [BRACKET_TRAVEL_DP] outside on spring.pop, hold, leave. A faint exposure tick marks the shutter. */
data class BracketFrame(val outsideDp: Float, val alpha: Float, val tick: Float)

const val BRACKET_SNAP_MS = 130f
const val BRACKET_HOLD_MS = 330f
const val BRACKET_LEAVE_MS = 150f
const val BRACKET_TRAVEL_DP = 6f
fun bracketTotalMs(): Int = (BRACKET_SNAP_MS + BRACKET_HOLD_MS + BRACKET_LEAVE_MS).toInt()

fun bracketFrame(tMs: Float, reduced: Boolean): BracketFrame {
    if (tMs <= 0f || tMs >= bracketTotalMs()) return BracketFrame(0f, 0f, 0f)
    val enterAlpha = (tMs / (if (reduced) MotionDur.Quick.toFloat() else 40f)).coerceIn(0f, 1f)
    val leave = ((tMs - BRACKET_SNAP_MS - BRACKET_HOLD_MS) / BRACKET_LEAVE_MS).coerceIn(0f, 1f)
    val travel = if (reduced) 0f else BRACKET_TRAVEL_DP * (1f - springStep(MotionSpring.Pop.damping, MotionSpring.Pop.stiffness, tMs / 1000f))
    // One soft luminance pulse when the brackets land: 0.10 at its peak, never repeated (WCAG 2.3.1).
    val tick = phosphorEnvelope(tMs - BRACKET_SNAP_MS * 0.6f, 40f, 220f) * 0.10f
    return BracketFrame(travel, enterAlpha * (1f - leave), tick)
}

/**
 * The CRT power-on: a thin bright line holds for the first frames, then the opening grows from it on the entrance curve.
 * Returns the open height as a fraction of the pane for the reveal clock [v] (0..1). Always 0 at the start, 1 at the end.
 */
fun crtRevealFraction(v: Float): Float {
    val x = v.coerceIn(0f, 1f)
    if (x >= 1f) return 1f
    if (x < CRT_LINE_HOLD) return CRT_LINE_FRACTION * (x / CRT_LINE_HOLD)
    return CRT_LINE_FRACTION + (1f - CRT_LINE_FRACTION) * MotionEase.Enter.transform((x - CRT_LINE_HOLD) / (1f - CRT_LINE_HOLD))
}
/** 40 ms of the 240 ms reveal is the line. */
const val CRT_LINE_HOLD = 40f / 240f
const val CRT_LINE_FRACTION = 0.02f

/** The 6 dp spark's position along a shallow arc. [p] is the flight progress from 0 to 1. */
fun sparkPosition(from: Offset, to: Offset, p: Float, arc: Float): Offset {
    val x = from.x + (to.x - from.x) * p
    val y = from.y + (to.y - from.y) * p - arc * 4f * p * (1f - p)
    return Offset(x, y)
}

/** Polaris pulse: the ring grows from 0.85 to 2.5 times the dot while it fades out. */
fun pingScale(p: Float): Float = 0.85f + (2.5f - 0.85f) * p.coerceIn(0f, 1f)
fun pingAlpha(p: Float): Float = (1f - p).coerceIn(0f, 1f)

/**
 * Radar rings on primitive arrays. At most [capacity] alive, one new ring per [gapMs]; a burst of hits waits in a short
 * queue instead of turning into a storm. Allocation-free after construction.
 */
class PingField(val capacity: Int, val gapMs: Long = 30L, val lifeMs: Long = MotionDur.Emphasis.toLong()) {
    private val slots = max(1, capacity)
    private val xs = FloatArray(slots)
    private val ys = FloatArray(slots)
    private val born = LongArray(slots)
    private val tags = IntArray(slots)
    private val used = BooleanArray(slots)
    private val queueX = FloatArray(QUEUE)
    private val queueY = FloatArray(QUEUE)
    private val queueTag = IntArray(QUEUE)
    private var queued = 0
    private var lastBirth = Long.MIN_VALUE / 2
    var alive = 0
        private set

    fun enqueue(x: Float, y: Float, tag: Int = 0) {
        if (capacity <= 0) return
        if (queued == QUEUE) { System.arraycopy(queueX, 1, queueX, 0, QUEUE - 1); System.arraycopy(queueY, 1, queueY, 0, QUEUE - 1); System.arraycopy(queueTag, 1, queueTag, 0, QUEUE - 1); queued-- }
        queueX[queued] = x; queueY[queued] = y; queueTag[queued] = tag; queued++
    }

    /** Retire the dead, then spawn at most one queued ring if the gap has passed. */
    fun step(nowMs: Long) {
        for (i in 0 until slots) if (used[i] && nowMs - born[i] >= lifeMs) { used[i] = false; alive-- }
        if (queued > 0 && alive < capacity && nowMs - lastBirth >= gapMs) {
            val slot = (0 until slots).firstOrNull { !used[it] } ?: return
            used[slot] = true; xs[slot] = queueX[0]; ys[slot] = queueY[0]; tags[slot] = queueTag[0]; born[slot] = nowMs; alive++; lastBirth = nowMs
            System.arraycopy(queueX, 1, queueX, 0, queued - 1); System.arraycopy(queueY, 1, queueY, 0, queued - 1); System.arraycopy(queueTag, 1, queueTag, 0, queued - 1); queued--
        }
    }

    val busy: Boolean get() = alive > 0 || queued > 0

    fun forEachAlive(nowMs: Long, block: (x: Float, y: Float, tag: Int, progress: Float) -> Unit) {
        for (i in 0 until slots) if (used[i]) block(xs[i], ys[i], tags[i], ((nowMs - born[i]).toFloat() / lifeMs).coerceIn(0f, 1f))
    }

    private companion object { const val QUEUE = 24 }
}

// ---------------------------------------------------------------------------------------------------------------------
// Drawing helpers
// ---------------------------------------------------------------------------------------------------------------------

/** The tick drawn when something is done: three points, drawn left to right. */
internal fun checkPath(w: Float, h: Float): Path = Path().apply {
    moveTo(w * .18f, h * .52f); lineTo(w * .42f, h * .76f); lineTo(w * .84f, h * .26f)
}

/** Stroke-draws [path] up to [fraction] of its length into [scratch]. Reuse the measure and scratch path between frames. */
internal fun partialPath(path: Path, fraction: Float, measure: PathMeasure, scratch: Path): Path {
    scratch.rewind()
    measure.setPath(path, false)
    val length = measure.length
    if (length > 0f && fraction > 0f) measure.getSegment(0f, length * fraction.coerceIn(0f, 1f), scratch, true)
    return scratch
}

/** Outline of a rectangle with its bottom-right corner cut (the Tech language), clockwise from the top-left corner. */
internal fun cutOutline(w: Float, h: Float, cut: Float): Path = Path().apply {
    val c = min(cut, min(w, h) / 2f)
    moveTo(0f, 0f); lineTo(w, 0f); lineTo(w, h - c); lineTo(w - c, h); lineTo(0f, h); close()
}

/** A hairline traced along [outline] up to [fraction], with a glow riding its tip. */
internal fun DrawScope.drawTrace(
    outline: Path, measure: PathMeasure, scratch: Path, fraction: Float, color: Color, strokePx: Float,
    sprite: ImageBitmap?, headRadius: Float, alpha: Float,
) {
    if (fraction <= 0f || alpha <= 0f) return
    measure.setPath(outline, true)
    val length = measure.length
    if (length <= 0f) return
    scratch.rewind()
    measure.getSegment(0f, length * fraction.coerceIn(0f, 1f), scratch, true)
    drawPath(scratch, color.copy(alpha = color.alpha * alpha), style = Stroke(strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (sprite != null) glow(sprite, measure.getPosition(length * fraction.coerceIn(0f, 1f)), headRadius, 0.9f * alpha)
}

/** Four corner brackets hugging [rect], pulled [outsidePx] outward. The bottom-right bracket follows a cut corner. */
internal fun DrawScope.drawBrackets(rect: Rect, outsidePx: Float, armPx: Float, strokePx: Float, color: Color, cutPx: Float) {
    val l = rect.left - outsidePx; val t = rect.top - outsidePx; val r = rect.right + outsidePx; val b = rect.bottom + outsidePx
    val s = Stroke(strokePx, cap = StrokeCap.Square, join = StrokeJoin.Miter)
    val arm = min(armPx, min(rect.width, rect.height) / 2f)
    val tl = Path().apply { moveTo(l, t + arm); lineTo(l, t); lineTo(l + arm, t) }
    val tr = Path().apply { moveTo(r - arm, t); lineTo(r, t); lineTo(r, t + arm) }
    val bl = Path().apply { moveTo(l, b - arm); lineTo(l, b); lineTo(l + arm, b) }
    val c = min(cutPx, arm)
    val br = Path().apply { moveTo(r - c - arm * .5f, b); lineTo(r - c, b); lineTo(r, b - c); lineTo(r, b - c - arm * .5f) }
    drawPath(tl, color, style = s); drawPath(tr, color, style = s); drawPath(bl, color, style = s); drawPath(br, color, style = s)
}

/** Breathing value from the shared pulse (0.15..0.60 sine) as 0..1. */
internal fun breath(pulse: Float): Float = ((pulse - 0.15f) / 0.45f).coerceIn(0f, 1f)

internal const val TWO_PI = (2.0 * PI).toFloat()
