package local.ringleader.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shown on entry before the console bar slides away. */
internal const val CONSOLE_BAR_HOLD_MS = 2_000L

/** How long the pointer must rest on the top edge before the bar returns. A pass-through does not count. */
internal const val CONSOLE_BAR_REST_MS = 300L

/** The top edge that counts as resting, in dp. */
internal const val CONSOLE_BAR_EDGE_DP = 4

/**
 * Immersive console for one window: full screen, RingMASTER's own chrome hidden, the console bar auto-hiding.
 * [fullscreen] is null on the browser host, which has no window; immersive only hides chrome there.
 */
@Stable class ImmersiveState(private val fullscreen: ((Boolean) -> Unit)? = null) {
    var active by mutableStateOf(false)
        private set

    fun enter() {
        if (!active) {
            active = true
            fullscreen?.invoke(true)
        }
    }

    fun exit() {
        if (active) {
            active = false
            fullscreen?.invoke(false)
        }
    }

    fun toggle() = if (active) exit() else enter()
}

val LocalImmersive = staticCompositionLocalOf<ImmersiveState?> { null }

/**
 * Whether the console bar is on screen. [pointerRestAtMs] is when the pointer entered the top edge and stayed;
 * it is null once the pointer leaves that edge. Passing through in under [CONSOLE_BAR_REST_MS] does not open the bar.
 */
internal fun consoleBarShown(nowMs: Long, enteredAtMs: Long, pointerRestAtMs: Long?, overBar: Boolean): Boolean {
    if (overBar) return true
    if (nowMs - enteredAtMs < CONSOLE_BAR_HOLD_MS) return true
    return pointerRestAtMs != null && nowMs - pointerRestAtMs >= CONSOLE_BAR_REST_MS
}

/** What a host key asks the guest page to do. F11 and Esc are only offered when the console does not hold the keyboard. */
internal enum class ConsoleHostAction { Previous, Next, ToggleImmersive, CloseGuests, ExitImmersive }

/**
 * Ctrl+Alt+PageUp / PageDown / Enter, plain F11, and plain Esc. Esc closes the guest overlay first and leaves
 * immersive on the next press. Shift, Meta, or Ctrl or Alt alone is not a host key. Key-ups are ignored here.
 */
internal fun consoleHostAction(event: KeyEvent, guestsOpen: Boolean, immersiveActive: Boolean): ConsoleHostAction? {
    if (event.type != KeyEventType.KeyDown) return null
    consoleShortcut(event)?.let { shortcut ->
        return when (shortcut) {
            ConsoleShortcut.PreviousGuest -> ConsoleHostAction.Previous
            ConsoleShortcut.NextGuest -> ConsoleHostAction.Next
            ConsoleShortcut.Immersive -> ConsoleHostAction.ToggleImmersive
        }
    }
    if (event.isCtrlPressed || event.isAltPressed || event.isShiftPressed || event.isMetaPressed) return null
    return when (event.key) {
        Key.F11 -> ConsoleHostAction.ToggleImmersive
        Key.Escape -> when {
            guestsOpen -> ConsoleHostAction.CloseGuests
            immersiveActive -> ConsoleHostAction.ExitImmersive
            else -> null
        }
        else -> null
    }
}

/** A host key the page should act on. Plain F11 is not consumed when no immersive state exists; Ctrl+Alt+Enter still is. */
internal data class ConsoleHostDecision(val action: ConsoleHostAction?, val consume: Boolean)

internal fun consoleHostDecision(event: KeyEvent, guestsOpen: Boolean, immersiveActive: Boolean, immersiveAvailable: Boolean): ConsoleHostDecision {
    val action = consoleHostAction(event, guestsOpen, immersiveActive)
    if (action == null) return ConsoleHostDecision(null, consoleShortcut(event) != null && event.type != KeyEventType.KeyDown)
    if (action == ConsoleHostAction.ToggleImmersive && !immersiveAvailable && consoleShortcut(event) == null) return ConsoleHostDecision(null, false)
    return ConsoleHostDecision(action, true)
}

/**
 * Watches the top [CONSOLE_BAR_EDGE_DP] without taking the event, so the guest still receives the movement.
 * [onResting] becomes true only after the pointer has stayed in that band for [CONSOLE_BAR_REST_MS].
 */
@Composable
internal fun Modifier.consoleTopRest(enabled: Boolean, onResting: (Boolean) -> Unit): Modifier {
    val scope = rememberCoroutineScope()
    val flag = rememberUpdatedState(enabled)
    val callback = rememberUpdatedState(onResting)
    val pending = remember { object { var job: Job? = null } }
    LaunchedEffect(enabled) {
        if (!enabled) {
            pending.job?.cancel()
            pending.job = null
            callback.value(false)
        }
    }
    val zone = with(LocalDensity.current) { CONSOLE_BAR_EDGE_DP.dp.toPx() }
    return this.pointerInput(zone) {
        awaitPointerEventScope {
            var inside = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull()
                val inZone = flag.value && change != null && event.type != PointerEventType.Exit && change.position.y <= zone
                if (inZone) {
                    if (!inside) {
                        inside = true
                        pending.job?.cancel()
                        pending.job = scope.launch {
                            delay(CONSOLE_BAR_REST_MS)
                            if (flag.value) callback.value(true)
                        }
                    }
                } else if (inside) {
                    inside = false
                    pending.job?.cancel()
                    pending.job = null
                    callback.value(false)
                }
            }
        }
    }
}

/**
 * The console bar floating over the picture. It is on screen on entry, slides up after [CONSOLE_BAR_HOLD_MS], and
 * slides back while [resting] or while the pointer is over the bar. Reduce motion snaps. The bar is a plain opaque
 * plate on every tier, including Lite, so nothing translucent is drawn over the live console. A bar that has slid
 * fully away leaves the composition, so it cannot take a click.
 */
@Composable
internal fun BoxScope.ImmersiveConsoleBar(resting: Boolean, content: @Composable () -> Unit) {
    val reduce = LocalReduceMotion.current
    var overBar by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(true) }
    val shift = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        held = true
        delay(CONSOLE_BAR_HOLD_MS)
        held = false
    }
    val want = overBar || held || resting
    LaunchedEffect(want, reduce) {
        val target = if (want) 0f else -1f
        if (reduce) shift.snapTo(target)
        else shift.animateTo(target, tween(MotionDur.Standard, easing = if (want) MotionEase.Enter else MotionEase.Exit))
    }
    if (want || shift.value > -0.999f) {
        val density = LocalDensity.current
        var heightPx by remember { mutableIntStateOf(with(density) { 32.dp.roundToPx() }) }
        Box(
            Modifier.align(Alignment.TopStart).fillMaxWidth()
                .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
                .offset { IntOffset(0, (shift.value * heightPx).toInt()) }
                .background(Tech.Raised)
                // The bar spans the screen edge to edge; its controls keep the same inset as the page toolbar.
                .padding(horizontal = 8.dp)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Exit -> overBar = false
                                PointerEventType.Enter, PointerEventType.Move, PointerEventType.Press -> overBar = true
                                else -> Unit
                            }
                        }
                    }
                },
        ) { content() }
    }
}
