@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import local.ringleader.core.ResourceId
import local.ringleader.core.TaskOutcome
import local.ringleader.core.WorkspaceState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The signature moments, as small reusable composables and modifiers. Each one reads its animated values inside draw or
 * layout lambdas (no recomposition per frame), stops requesting frames when it is done, and resolves itself per tier
 * through LocalMotion. Triggers come from MotionBook, which only speaks when state really changed.
 */

// ---------------------------------------------------------------------------------------------------------------------
// The book: compares snapshots, remembers when the user started waiting, announces moments
// ---------------------------------------------------------------------------------------------------------------------

@Stable
class MotionBook(private val nowNanos: () -> Long = System::nanoTime) {
    val moments = MutableSharedFlow<MotionMoment>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var previous: MotionFacts? = null
    private val firstSeen = HashMap<String, Long>()

    /** How long the user has waited on [source], since this book first saw it in flight. */
    fun waitedMs(source: String?): Long = firstSeen[source ?: return 0L]?.let { (nowNanos() - it) / 1_000_000L } ?: 0L

    /** Compare with the last snapshot. With [emit] false (window not active) the baseline still moves but nothing is announced. */
    fun update(facts: MotionFacts, emit: Boolean) {
        val moments = momentsBetween(previous, facts)
        previous = facts
        val now = nowNanos()
        val inFlight = inFlightSources(facts)
        for (source in inFlight) firstSeen.putIfAbsent(source, now)
        if (emit) for (m in moments) this.moments.tryEmit(m.copy(waitedMs = waitedMs(m.source)))
        firstSeen.keys.retainAll(inFlight)
    }

    /** The spark touched down on [resource] (or on the rail icon when null). */
    fun land(resource: ResourceId?) { moments.tryEmit(MotionMoment(MomentKind.Landed, resource, "spark")) }
}

val LocalMotionBook = staticCompositionLocalOf<MotionBook?> { null }
/** Monotonic nanoseconds for "how long has the user waited". Tests and probes swap it; the app uses [System.nanoTime]. */
val LocalMotionClock = staticCompositionLocalOf<() -> Long> { System::nanoTime }
val LocalGuestActivity = compositionLocalOf<Map<ResourceId, GuestActivity>> { emptyMap() }

/** Provide the trigger book and the busy-guest map for one screen. Recomposes readers only when the facts really change. */
@Composable fun ProvideMotionBook(state: WorkspaceState, content: @Composable () -> Unit) {
    // The app provides one book for the whole window (Main.kt). A screen that asks for its own joins it, so "how long has the
    // user waited" survives a page change instead of starting again when the page is composed.
    if (LocalMotionBook.current != null) { content(); return }
    val clock = LocalMotionClock.current
    val book = remember(clock) { MotionBook(clock) }
    val facts = remember(state.connectionEpoch, state.submissions, state.tasks, state.overview) { motionFacts(state) }
    val activity = remember(facts) { guestActivities(facts) }
    val windowActive = rememberUpdatedState(LocalWindowActive.current)
    LaunchedEffect(facts) { book.update(facts, emit = windowActive.value) }
    CompositionLocalProvider(LocalMotionBook provides book, LocalGuestActivity provides activity, content = content)
}

/** Run [handler] for each moment about [resource] or [source]. The handler must not suspend; it starts pulses. */
@Composable fun OnMoment(resource: ResourceId?, source: String? = null, handler: (MotionMoment) -> Unit) {
    val book = LocalMotionBook.current ?: return
    val current = rememberUpdatedState(handler)
    LaunchedEffect(book, resource, source) {
        book.moments.collect { m ->
            if ((resource != null && m.resource == resource) || (source != null && m.source == source)) current.value(m)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Pulse: one finite animation clock, read inside draw lambdas
// ---------------------------------------------------------------------------------------------------------------------

/** A one-shot clock from 0 to 1 over [durationMs]. It reads 1 (and requests no frames) when idle. */
@Stable
class Pulse(private val scope: CoroutineScope) {
    val clock = Animatable(1f)
    var kind: Int = 0
    var durationMs: Int = 1
    var hard: Boolean = false
    var plan: AfterglowPlan = afterglowPlan(0L)
    private var job: Job? = null

    fun fire(durationMs: Int, kind: Int = 0, hard: Boolean = false, plan: AfterglowPlan? = null) {
        this.kind = kind; this.durationMs = durationMs.coerceAtLeast(1); this.hard = hard
        if (plan != null) this.plan = plan
        job?.cancel()
        job = scope.launch { clock.snapTo(0f); clock.animateTo(1f, tween(this@Pulse.durationMs, easing = LinearEasing)) }
    }

    /** Milliseconds into the pulse, or -1 when idle. Read it inside a draw or layout lambda. */
    fun ms(): Float = clock.value.let { if (it >= 1f) -1f else it * durationMs }
    val running: Boolean get() = clock.value < 1f
}

@Composable fun rememberPulse(): Pulse { val scope = rememberCoroutineScope(); return remember { Pulse(scope) } }

private const val KIND_POP = 1
private const val KIND_COLLAPSE = 2
private const val KIND_CHECK = 3
private const val CHECK_TOTAL_MS = 1100
private const val CHECK_DRAW_MS = 150f
private const val CHECK_FADE_MS = 150f
private const val POP_MS = 320
/** A breathing pip rests after this long, so nothing breathes forever on an item that never resolves. */
private const val BREATHE_CAP_MS = 10 * 60 * 1000L

/** The knockback nudge on a status chip: -6, +5, -3, 0 dp over 280 ms, once. */
const val NUDGE_MS = 280
fun nudgeOffsetDp(tMs: Float): Float = when {
    tMs <= 0f -> 0f
    tMs < 50f -> -6f * tMs / 50f
    tMs < 110f -> -6f + 11f * (tMs - 50f) / 60f
    tMs < 180f -> 5f - 8f * (tMs - 110f) / 70f
    tMs < 280f -> -3f + 3f * (tMs - 180f) / 100f
    else -> 0f
}

private fun popScale(ms: Float, reduced: Boolean): Float =
    if (reduced) 1f else 0.55f + 0.45f * springStep(MotionSpring.Pop.damping, MotionSpring.Pop.stiffness, ms / 1000f)

// ---------------------------------------------------------------------------------------------------------------------
// Spark: the hand-off from the confirm button to the guest
// ---------------------------------------------------------------------------------------------------------------------

class SparkFlight(val from: Offset, val targets: List<Any>, val hot: Boolean, val onLand: () -> Unit) {
    val flight = Animatable(0f)
    val ring = Animatable(1f)
    var lastDestination: Offset? = null
}

/**
 * Launches the spark. The flight is drawn by the overlay [ProvideMotionSystem] puts above the whole app, so it survives
 * the confirmation dialog closing. Under Reduced there is no flight: the landing happens at once and the destination
 * pip fades in.
 */
@Stable
class SparkLauncher {
    internal val flights = mutableStateListOf<SparkFlight>()
    internal var attached = false

    /** [from] is the confirm button's bounds in the window; [targets] are anchor keys to try, in order. Returns at once. */
    fun ignite(motion: RmMotion, from: Rect?, targets: List<Any>, hot: Boolean = false, onLand: () -> Unit = {}) {
        if (!attached || !motion.sparkFlies || from == null) { onLand(); return }
        flights.add(SparkFlight(from.center, targets, hot, onLand))
    }

    fun isFlyingTo(target: Any): Boolean = flights.any { target in it.targets }
}

val LocalSparks = staticCompositionLocalOf { SparkLauncher() }

/** Collapsed rail: the Activity icon centre, from the rail's own metrics (60 dp wide, 20 dp inset, 40 dp rows from 8 dp). */
internal const val RAIL_ITEM_HEIGHT_DP = 40
internal fun railActivityCentreDp(): Pair<Float, Float> = 30f to (8f + ringPages.indexOf("Activity").coerceAtLeast(0) * RAIL_ITEM_HEIGHT_DP + RAIL_ITEM_HEIGHT_DP / 2f)

@Composable internal fun SparkOverlay(launcher: SparkLauncher, anchors: MotionAnchors, motion: RmMotion) {
    val origin = remember { floatArrayOf(0f, 0f) }
    DisposableEffect(launcher) { launcher.attached = true; onDispose { launcher.attached = false } }
    Box(Modifier.fillMaxSize().onGloballyPositioned { val p = it.positionInWindow(); origin[0] = p.x; origin[1] = p.y }) {
        if (launcher.flights.isNotEmpty()) Canvas(Modifier.fillMaxSize()) {
            val density = this.density
            val rail = railActivityCentreDp().let { Offset(it.first * density, it.second * density) }
            for (f in launcher.flights) {
                val color = if (f.hot) hotAmber() else Tech.Amber
                val sprite = GlowSprites.sprite(color)
                val here = Offset(origin[0], origin[1])
                val destination = f.targets.firstNotNullOfOrNull { anchors.bounds(it)?.center }?.let { it - here } ?: f.lastDestination ?: rail
                f.lastDestination = destination
                val from = f.from - here
                val arc = min(48.dp.toPx(), (destination - from).getDistance() * .25f)
                val p = f.flight.value
                val r = f.ring.value
                val gain = motion.lightGain * lightScale()
                // In flight until the ring starts (the landing): then the spark has become the pip, so the head is gone.
                if (r >= 1f) {
                    if (motion.sparkTrail) glow(sprite, sparkPosition(from, destination, (p - .08f).coerceAtLeast(0f), arc), 10.dp.toPx(), .25f * gain)
                    val position = sparkPosition(from, destination, p, arc)
                    glow(sprite, position, 14.dp.toPx(), .9f * gain)
                    drawCircle(color, 3.dp.toPx(), position)
                }
                if (r < 1f) drawCircle(color, 4.dp.toPx() + 14.dp.toPx() * r, destination, alpha = (1f - r) * .8f * gain, style = Stroke(1.dp.toPx()))
            }
        }
    }
    for (flight in launcher.flights.toList()) key(flight) {
        LaunchedEffect(flight) {
            flight.ring.snapTo(1f)
            flight.flight.animateTo(1f, if (flight.hot) MotionSpring.SpatialFast.spec() else MotionSpring.SpatialDefault.spec())
            flight.onLand()
            flight.ring.snapTo(0f)
            flight.ring.animateTo(1f, tween(motion.ms(MotionDur.Short), easing = MotionEase.Enter))
            launcher.flights.remove(flight)
        }
    }
}

/** Press dip for a confirm button: scale 0.97 over [MotionDur.Micro] (Reduced: a faint dim instead of a scale). Observes presses, consumes nothing. */
@Composable fun Modifier.pressDip(): Modifier {
    val motion = LocalMotion.current
    var pressed by remember { mutableStateOf(false) }
    val dip = remember { Animatable(0f) }
    LaunchedEffect(pressed) { dip.animateTo(if (pressed) 1f else 0f, tween(MotionDur.Micro, easing = MotionEase.Standard)) }
    return this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    pressed = event.changes.any { it.pressed }
                }
            }
        }
        .graphicsLayer {
            val d = dip.value
            if (motion.reduced) alpha = 1f - .15f * d else { scaleX = 1f - .03f * d; scaleY = 1f - .03f * d }
        }
}

// ---------------------------------------------------------------------------------------------------------------------
// Status glyph: breathing pip, landing pop, check, CRT collapse
// ---------------------------------------------------------------------------------------------------------------------

/**
 * The status tag of a guest (replaces StatusTag where a guest is known). Idle it looks exactly like StatusTag. While a
 * task runs for the guest the dot becomes the one working indicator: it breathes (low contrast, 0.83 Hz) and the shared
 * phase clock drives it, so it stops with the window. Under Reduced it is a still dot and the word "working". When the
 * work ends it goes still; a stop collapses the glyph like a CRT switching off; a failure nudges the chip once.
 */
@Composable fun ActivityStatusTag(label: String, healthy: Boolean, resource: ResourceId?, modifier: Modifier = Modifier, anchor: Any? = null) {
    val motion = LocalMotion.current
    val phase = LocalMotionPhase.current
    val reduce = motion.reduced
    val activity = resource?.let { LocalGuestActivity.current[it] }
    val inFlight = activity?.inFlight == true
    var breathing by remember { mutableStateOf(true) }
    LaunchedEffect(inFlight) { breathing = true; if (inFlight) { delay(BREATHE_CAP_MS); breathing = false } }
    HoldAmbientClock(inFlight && breathing && motion.ambient)
    val tag = rememberPulse()
    val nudge = rememberPulse()
    var lastPop by remember { mutableLongStateOf(0L) }
    OnMoment(resource) { m ->
        when (m.kind) {
            MomentKind.Started, MomentKind.Landed -> {
                val now = System.nanoTime()
                if (now - lastPop > 600_000_000L) { lastPop = now; tag.fire(motion.ms(POP_MS), KIND_POP) }
            }
            MomentKind.Stopped -> tag.fire(crtCollapseDurationMs(m.hard), KIND_COLLAPSE, hard = m.hard)
            MomentKind.Succeeded, MomentKind.Arrived -> tag.fire(CHECK_TOTAL_MS, KIND_CHECK)
            MomentKind.Failed -> if (!reduce) nudge.fire(NUDGE_MS)
            else -> Unit
        }
    }
    val localDensity = LocalDensity.current
    val boxPx = with(localDensity) { 9.dp.toPx() }
    val check = remember(boxPx) { checkPath(boxPx, boxPx) }
    val measure = remember { PathMeasure() }
    val scratch = remember { Path() }
    val shown = if (inFlight && reduce) "working" else label
    Row(
        modifier.offset { IntOffset(if (nudge.running) nudgeOffsetDp(nudge.ms()).dp.roundToPx() else 0, 0) }
            .semantics { liveRegion = LiveRegionMode.Polite; stateDescription = if (inFlight) "working" else label },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        // [anchor] registers this dot as a place the spark can land on.
        Canvas(Modifier.size(5.dp).then(if (anchor != null) Modifier.motionAnchor(anchor) else Modifier)) {
            val side = size.minDimension
            val centre = Offset(size.width / 2f, size.height / 2f)
            val ms = tag.ms()
            val kind = if (ms >= 0f) tag.kind else 0
            val gain = motion.lightGain * lightScale()
            val pop = if (kind == KIND_POP) popScale(ms, reduce) else 1f
            val popLight = if (kind == KIND_POP) (1f - ms / POP_MS).coerceIn(0f, 1f) else 0f
            if (inFlight) {
                val b = if (breathing && phase.active.value && motion.ambient) breath(phase.pulse.floatValue) else .55f
                val amber = Tech.Amber
                glow(GlowSprites.sprite(amber), centre, 9.dp.toPx() * pop, (0.2f + 0.3f * b + .35f * popLight) * gain)
                val s = side * pop
                drawRect(amber.copy(alpha = if (reduce) 1f else .55f + .45f * b), Offset(centre.x - s / 2f, centre.y - s / 2f), Size(s, s))
            } else {
                val pulsing = healthy && !reduce && phase.active.value
                val alpha = if (!pulsing) 1f else 0.4f + 0.6f * ((phase.pulse.floatValue - 0.15f) / 0.45f).coerceIn(0f, 1f)
                val s = side * pop
                drawRect(if (healthy) Tech.Green.copy(alpha = alpha) else Tech.Muted, Offset(centre.x - s / 2f, centre.y - s / 2f), Size(s, s))
                if (popLight > 0f) glow(GlowSprites.sprite(Tech.Amber), centre, 9.dp.toPx(), .55f * popLight * gain)
            }
            if (kind == KIND_COLLAPSE) {
                val green = Tech.Green
                if (reduce) drawRect(green.copy(alpha = 1f - (ms / MotionDur.Quick).coerceIn(0f, 1f)), Offset(centre.x - side / 2f, centre.y - side / 2f), Size(side, side))
                else {
                    val f = crtCollapseFrame(ms, tag.hard)
                    val w = side * (1f + 1.2f * (1f - f.scaleY)) * f.scaleX
                    val h = max(side * f.scaleY, if (f.scaleY < 1f) 1.dp.toPx() else 0f)
                    drawRect(green.copy(alpha = f.lineAlpha), Offset(centre.x - w / 2f + f.glitchPx * density, centre.y - h / 2f), Size(w, h))
                    glow(GlowSprites.sprite(green), centre, 8.dp.toPx(), .6f * f.dotGlow * gain)
                }
            }
            if (kind == KIND_CHECK) {
                val fadeIn = if (reduce) (ms / MotionDur.Quick).coerceIn(0f, 1f) else 1f
                val fadeOut = ((CHECK_TOTAL_MS - ms) / CHECK_FADE_MS).coerceIn(0f, 1f)
                val progress = if (reduce) 1f else (ms / CHECK_DRAW_MS).coerceIn(0f, 1f)
                val scale = if (reduce) 1f else 0.7f + 0.3f * springStep(MotionSpring.Pop.damping, MotionSpring.Pop.stiffness, ms / 1000f)
                val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                withTransform({ translate(centre.x - boxPx / 2f, centre.y - boxPx / 2f); scale(scale, scale, Offset(boxPx / 2f, boxPx / 2f)) }) {
                    drawPath(partialPath(check, progress, measure, scratch), Tech.Green.copy(alpha = fadeIn * fadeOut), style = stroke)
                }
            }
        }
        Text(shown.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = if (inFlight) Tech.Amber else if (healthy) Tech.Green else Tech.Muted)
    }
}

/**
 * The 16 dp outcome glyph of a task. Running is the breathing pip (the one indicator: no spinner beside it); success is a
 * tick that draws itself when the task ends; failure is a cross that nudges once.
 */
@Composable fun TaskPip(outcome: TaskOutcome, source: String?, modifier: Modifier = Modifier) {
    val motion = LocalMotion.current
    val phase = LocalMotionPhase.current
    val reduce = motion.reduced
    HoldAmbientClock(outcome == TaskOutcome.Running && motion.ambient)
    val tag = rememberPulse()
    val nudge = rememberPulse()
    OnMoment(null, source) { m ->
        when (m.kind) {
            MomentKind.Succeeded -> tag.fire(CHECK_TOTAL_MS, KIND_CHECK)
            MomentKind.Failed -> if (!reduce) nudge.fire(NUDGE_MS)
            else -> Unit
        }
    }
    val localDensity = LocalDensity.current
    val boxPx = with(localDensity) { 16.dp.toPx() }
    val check = remember(boxPx) { checkPath(boxPx, boxPx) }
    val measure = remember { PathMeasure() }
    val scratch = remember { Path() }
    Canvas(modifier.size(16.dp).offset { IntOffset(if (nudge.running) nudgeOffsetDp(nudge.ms()).dp.roundToPx() else 0, 0) }) {
        val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
        val centre = Offset(size.width / 2f, size.height / 2f)
        val gain = motion.lightGain * lightScale()
        when (outcome) {
            TaskOutcome.Running -> {
                val b = if (phase.active.value && motion.ambient) breath(phase.pulse.floatValue) else .55f
                val amber = Tech.Amber
                drawCircle(amber.copy(alpha = .2f), style = stroke)
                glow(GlowSprites.sprite(amber), centre, 9.dp.toPx(), (0.2f + 0.3f * b) * gain)
                drawCircle(amber.copy(alpha = if (reduce) 1f else .55f + .45f * b), 3.5.dp.toPx(), centre)
            }
            TaskOutcome.Succeeded -> {
                val ms = tag.ms()
                val drawing = ms >= 0f && tag.kind == KIND_CHECK
                val progress = if (drawing && !reduce) (ms / CHECK_DRAW_MS).coerceIn(0f, 1f) else 1f
                val alpha = if (drawing && reduce) (ms / MotionDur.Quick).coerceIn(0f, 1f) else 1f
                val scale = if (drawing && !reduce) 0.7f + 0.3f * springStep(MotionSpring.Pop.damping, MotionSpring.Pop.stiffness, ms / 1000f) else 1f
                withTransform({ scale(scale, scale, centre) }) { drawPath(partialPath(check, progress, measure, scratch), Tech.Green.copy(alpha = alpha), style = stroke) }
            }
            TaskOutcome.Unsuccessful -> {
                val i = size.width * .22f
                drawLine(Tech.Red, Offset(i, i), Offset(size.width - i, size.height - i), stroke.width, StrokeCap.Round)
                drawLine(Tech.Red, Offset(size.width - i, i), Offset(i, size.height - i), stroke.width, StrokeCap.Round)
            }
            else -> { drawCircle(Tech.Muted, style = stroke); drawCircle(Tech.Muted, 1.2.dp.toPx(), center) }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Row: success phosphor, persistent failure edge
// ---------------------------------------------------------------------------------------------------------------------

/**
 * A row or tile that glows when its task ends well: an additive bloom that rises in 60 ms and decays over 340 ms like
 * phosphor, brighter and wider the longer the user waited, with an edge sweep after a long wait. A failure leaves a red
 * edge that stays (it comes from [failedEdge], not from a moment). Matching is by [resource] (a guest's row) or by
 * [source] (one task's row).
 */
@Composable fun Modifier.phosphorRow(resource: ResourceId?, source: String? = null, failedEdge: Boolean = false): Modifier {
    val pulse = rememberPulse()
    OnMoment(resource, source) { m ->
        if (m.kind == MomentKind.Succeeded || m.kind == MomentKind.Arrived) pulse.fire(AFTERGLOW_TOTAL_MS, plan = afterglowPlan(m.waitedMs))
    }
    return phosphor(pulse, failedEdge)
}

/** Fire [pulse] with the plan for a wait of [waitedMs] and draw it with [phosphor]: for a panel that decides by itself when it succeeded. */
fun Pulse.fireAfterglow(waitedMs: Long) = fire(AFTERGLOW_TOTAL_MS, plan = afterglowPlan(waitedMs))

/** The knockback nudge, driven by [pulse]: the node moves -6, +5, -3, 0 dp once. Read in the layout phase only while it runs. */
fun Modifier.nudged(pulse: Pulse): Modifier = this.offset { IntOffset(if (pulse.running) nudgeOffsetDp(pulse.ms()).dp.roundToPx() else 0, 0) }

/** Draws the afterglow of [pulse] over this node, and the persistent red edge when [failedEdge]. */
@Composable fun Modifier.phosphor(pulse: Pulse, failedEdge: Boolean = false): Modifier {
    val motion = LocalMotion.current
    return this.drawWithContent {
        drawContent()
        val ms = pulse.ms()
        if (ms >= 0f) {
            val env = phosphorEnvelope(ms)
            val plan = pulse.plan
            val gain = motion.lightGain * lightScale()
            val a = (plan.intensity * env * gain).coerceIn(0f, 1f)
            val amber = Tech.Amber
            drawRect(Brush.horizontalGradient(listOf(amber.copy(alpha = min(1f, a * 1.6f)), amber.copy(alpha = a * .5f))), blendMode = lightBlend())
            if (plan.sweep && !motion.reduced) {
                val x = size.width * MotionEase.Enter.transform((ms / AFTERGLOW_TOTAL_MS).coerceIn(0f, 1f))
                glow(GlowSprites.sprite(amber), Offset(x, 1.dp.toPx()), 10.dp.toPx(), .8f * env * gain)
            }
        }
        if (failedEdge) drawRect(Tech.Red, Offset.Zero, Size(2.dp.toPx(), size.height))
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// CRT collapse on a console still (and any other tile)
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Keeps [content] composed while it plays the CRT switch-off: it squashes to a bright line, the line narrows to a dot,
 * the dot decays. A hard stop is the faster, harder cut. The stop moment for [resource] arrives a frame or so after
 * [visible] turns false, so the host waits a moment for it; without one (or under Reduced, or in an inactive window)
 * the content just goes, after a short fade under Reduced.
 */
@Composable fun CrtCollapseHost(resource: ResourceId?, visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val motion = LocalMotion.current
    val active = LocalWindowActive.current
    var shown by remember { mutableStateOf(visible) }
    val clock = remember { Animatable(1f) }
    var duration by remember { mutableStateOf(1) }
    var kindHard by remember { mutableStateOf(false) }
    var stop by remember { mutableStateOf<MotionMoment?>(null) }
    OnMoment(resource) { if (it.kind == MomentKind.Stopped) stop = it }
    LaunchedEffect(visible) {
        if (visible) { shown = true; stop = null; clock.snapTo(1f) }
        else if (shown) {
            val moment = withTimeoutOrNull(160) { snapshotFlow { stop }.filterNotNull().first() }
            if (moment != null && active && !motion.reduced) {
                duration = crtCollapseDurationMs(moment.hard); kindHard = moment.hard
                clock.snapTo(0f); clock.animateTo(1f, tween(duration, easing = LinearEasing))
            } else if (motion.reduced && active && moment != null) {
                duration = motion.ms(MotionDur.Quick); kindHard = false
                clock.snapTo(0f); clock.animateTo(1f, tween(duration, easing = MotionEase.Standard))
            }
            shown = false
            stop = null
        }
    }
    if (!shown) return
    Box(modifier.drawWithContent {
        val t = clock.value
        if (visible || t >= 1f) { drawContent(); return@drawWithContent }
        val ms = t * duration
        if (motion.reduced) {
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = 1f - t })
                this@drawWithContent.drawContent()
                canvas.restore()
            }
            return@drawWithContent
        }
        val f = crtCollapseFrame(ms, kindHard)
        val centre = Offset(size.width / 2f, size.height / 2f)
        if (f.scaleY > .081f) withTransform({ scale(1f, f.scaleY, centre) }) { this@drawWithContent.drawContent() }
        val width = size.width * f.scaleX
        val white = lerp(Color.White, Tech.Green, .35f)
        drawRect(white.copy(alpha = f.lineAlpha * motion.lightGain), Offset(centre.x - width / 2f + f.glitchPx * density, centre.y - 1.dp.toPx()), Size(width, 2.dp.toPx()), blendMode = lightBlend())
        glow(GlowSprites.sprite(Tech.Green), centre, 28.dp.toPx(), .5f * f.dotGlow * motion.lightGain * lightScale())
    }) { content() }
}

// ---------------------------------------------------------------------------------------------------------------------
// Snapshot: camera brackets
// ---------------------------------------------------------------------------------------------------------------------

/** Corner brackets snap in around this region when a snapshot starts for [resource], with one faint exposure tick. */
@Composable fun Modifier.shutterBrackets(resource: ResourceId?): Modifier {
    val motion = LocalMotion.current
    val pulse = rememberPulse()
    OnMoment(resource) { if (it.kind == MomentKind.Snapshot) pulse.fire(bracketTotalMs()) }
    return this.drawWithContent {
        drawContent()
        val ms = pulse.ms()
        if (ms >= 0f) {
            val f = bracketFrame(ms, motion.reduced)
            drawBrackets(Rect(Offset.Zero, size), f.outsideDp.dp.toPx(), 16.dp.toPx(), 1.5.dp.toPx(), Tech.Amber.copy(alpha = f.alpha), 10.dp.toPx())
            if (f.tick > 0f) drawRect(lerp(Color.White, Tech.Amber, .5f), alpha = (f.tick * motion.lightGain * lightScale()).coerceIn(0f, 1f), blendMode = lightBlend())
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Console: CRT power-on
// ---------------------------------------------------------------------------------------------------------------------

/**
 * The console opens like a CRT turning on: a clip grows from a thin glowing line at the vertical centre to the full
 * height over 240 ms. It is only a draw effect, so input and the live frames are never delayed. Pass the first-frame
 * moment as [trigger]; null means "no frame yet" and draws normally. Reduced crossfades instead.
 */
@Composable fun Modifier.crtPowerOn(trigger: Any?): Modifier {
    val motion = LocalMotion.current
    val active = LocalWindowActive.current
    val open = remember(trigger) { Animatable(if (trigger == null || !active) 1f else 0f) }
    LaunchedEffect(trigger) {
        if (trigger != null && active) {
            open.snapTo(0f)
            // The clock runs straight; [crtRevealFraction] shapes it (a short line, then the entrance curve). Reduced crossfades.
            open.animateTo(1f, if (motion.reduced) tween(motion.ms(MotionDur.Quick), easing = MotionEase.Standard) else tween(motion.ms(MotionDur.Standard), easing = LinearEasing))
        }
    }
    return this.drawWithContent {
        val v = open.value
        if (v >= 1f) { drawContent(); return@drawWithContent }
        if (motion.reduced) {
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = v })
                this@drawWithContent.drawContent()
                canvas.restore()
            }
            return@drawWithContent
        }
        val h = size.height * crtRevealFraction(v)
        val top = (size.height - h) / 2f
        clipRect(top = top, bottom = top + h) { this@drawWithContent.drawContent() }
        val line = Tech.Amber.copy(alpha = (0.6f + 0.4f * (1f - v)) * motion.lightGain)
        drawRect(line, Offset(0f, top - 1.dp.toPx()), Size(size.width, 2.dp.toPx()), blendMode = lightBlend())
        drawRect(line, Offset(0f, top + h - 1.dp.toPx()), Size(size.width, 2.dp.toPx()), blendMode = lightBlend())
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Progress: a hairline traced along the cut-corner outline
// ---------------------------------------------------------------------------------------------------------------------

/**
 * A hairline that traces this node's cut-corner outline up to [progress] (0..1, from the log's percentage), gliding to
 * each new value on spring.spatial.slow so coarse updates stay smooth, with a glow riding the tip. Null draws nothing.
 * Lite keeps the hairline and the tip; Reduced a still line that changes length on a fast effects spring, no tip.
 */
@Composable fun Modifier.progressTrace(progress: Float?, cutDp: Float = 10f): Modifier {
    val motion = LocalMotion.current
    val shown by animateFloatAsState(
        progress ?: 0f,
        if (motion.reduced) motion.effects(MotionSpring.EffectsFast) else motion.spatial(MotionSpring.SpatialSlow),
        label = "trace",
    )
    if (progress == null) return this
    return this.drawWithCache {
        // Inset by half the stroke so the whole line sits inside the node (a parent may clip at its edge).
        val inset = 0.75.dp.toPx()
        val outline = cutOutline(size.width - 2 * inset, size.height - 2 * inset, cutDp.dp.toPx())
        val measure = PathMeasure()
        val scratch = Path()
        onDrawWithContent {
            drawContent()
            val amber = Tech.Amber
            translate(inset, inset) {
                drawTrace(outline, measure, scratch, shown, amber, 1.5.dp.toPx(), if (motion.reduced) null else GlowSprites.sprite(amber), 6.dp.toPx(), motion.lightGain)
            }
        }
    }
}
