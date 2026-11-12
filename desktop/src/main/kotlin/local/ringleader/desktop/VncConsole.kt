package local.ringleader.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import local.ringleader.core.*
import org.jetbrains.skia.Bitmap
import java.awt.EventQueue
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/** Set while a console has keyboard focus, so window shortcuts can stand down. */
object VncKeyboard {
    @Volatile var captured: Boolean = false
}

/** Maps a Compose desktop key event onto an X11 keysym. Named keys win; letters fall back when the code point is a control character. */
internal fun vncKeysym(event: KeyEvent): Int? {
    val named = event.key.toVncKey()
    val raw = event.utf16CodePoint
    val codePoint = if (named == VncKey.Character && raw !in 0x20..0x7E && raw !in 0xA0..0x10FFFF) event.key.fallbackChar() ?: raw else raw
    return vncKeysym(named, codePoint)
}

private fun Key.toVncKey(): VncKey = when (this) {
    Key.Enter -> VncKey.Enter
    Key.NumPadEnter -> VncKey.KeypadEnter
    Key.Escape -> VncKey.Escape
    Key.Backspace -> VncKey.Backspace
    Key.Tab -> VncKey.Tab
    Key.Delete -> VncKey.Delete
    Key.Insert -> VncKey.Insert
    Key.MoveHome -> VncKey.Home
    Key.MoveEnd -> VncKey.End
    Key.PageUp -> VncKey.PageUp
    Key.PageDown -> VncKey.PageDown
    Key.DirectionLeft -> VncKey.Left
    Key.DirectionUp -> VncKey.Up
    Key.DirectionRight -> VncKey.Right
    Key.DirectionDown -> VncKey.Down
    Key.F1 -> VncKey.F1
    Key.F2 -> VncKey.F2
    Key.F3 -> VncKey.F3
    Key.F4 -> VncKey.F4
    Key.F5 -> VncKey.F5
    Key.F6 -> VncKey.F6
    Key.F7 -> VncKey.F7
    Key.F8 -> VncKey.F8
    Key.F9 -> VncKey.F9
    Key.F10 -> VncKey.F10
    Key.F11 -> VncKey.F11
    Key.F12 -> VncKey.F12
    Key.ShiftLeft -> VncKey.ShiftLeft
    Key.ShiftRight -> VncKey.ShiftRight
    Key.CtrlLeft -> VncKey.ControlLeft
    Key.CtrlRight -> VncKey.ControlRight
    Key.AltLeft -> VncKey.AltLeft
    Key.AltRight -> VncKey.AltRight
    Key.MetaLeft -> VncKey.MetaLeft
    Key.MetaRight -> VncKey.MetaRight
    Key.CapsLock -> VncKey.CapsLock
    Key.NumLock -> VncKey.NumLock
    Key.PrintScreen -> VncKey.PrintScreen
    Key.Break -> VncKey.Pause
    Key.NumPad0 -> VncKey.Keypad0
    Key.NumPad1 -> VncKey.Keypad1
    Key.NumPad2 -> VncKey.Keypad2
    Key.NumPad3 -> VncKey.Keypad3
    Key.NumPad4 -> VncKey.Keypad4
    Key.NumPad5 -> VncKey.Keypad5
    Key.NumPad6 -> VncKey.Keypad6
    Key.NumPad7 -> VncKey.Keypad7
    Key.NumPad8 -> VncKey.Keypad8
    Key.NumPad9 -> VncKey.Keypad9
    Key.NumPadAdd -> VncKey.KeypadAdd
    Key.NumPadSubtract -> VncKey.KeypadSubtract
    Key.NumPadMultiply -> VncKey.KeypadMultiply
    Key.NumPadDivide -> VncKey.KeypadDivide
    Key.NumPadDot -> VncKey.KeypadDecimal
    Key.NumPadEquals -> VncKey.KeypadEqual
    else -> VncKey.Character
}

private fun Key.fallbackChar(): Int? = when (this) {
    Key.A -> 'a'.code; Key.B -> 'b'.code; Key.C -> 'c'.code; Key.D -> 'd'.code; Key.E -> 'e'.code
    Key.F -> 'f'.code; Key.G -> 'g'.code; Key.H -> 'h'.code; Key.I -> 'i'.code; Key.J -> 'j'.code
    Key.K -> 'k'.code; Key.L -> 'l'.code; Key.M -> 'm'.code; Key.N -> 'n'.code; Key.O -> 'o'.code
    Key.P -> 'p'.code; Key.Q -> 'q'.code; Key.R -> 'r'.code; Key.S -> 's'.code; Key.T -> 't'.code
    Key.U -> 'u'.code; Key.V -> 'v'.code; Key.W -> 'w'.code; Key.X -> 'x'.code; Key.Y -> 'y'.code
    Key.Z -> 'z'.code
    Key.Zero, Key.NumPad0 -> '0'.code
    Key.One, Key.NumPad1 -> '1'.code
    Key.Two, Key.NumPad2 -> '2'.code
    Key.Three, Key.NumPad3 -> '3'.code
    Key.Four, Key.NumPad4 -> '4'.code
    Key.Five, Key.NumPad5 -> '5'.code
    Key.Six, Key.NumPad6 -> '6'.code
    Key.Seven, Key.NumPad7 -> '7'.code
    Key.Eight, Key.NumPad8 -> '8'.code
    Key.Nine, Key.NumPad9 -> '9'.code
    Key.Spacebar -> ' '.code
    else -> null
}

/**
 * Where the framebuffer is drawn in the view, in view pixels. [sharp] is true at a whole-number scale, where the
 * frame is drawn with nearest-neighbour sampling so text stays crisp. Fit mode snaps down to a whole scale when the
 * view is at most 10 % larger than that, trading a slightly wider border for sharp pixels.
 */
internal class VncFrameLayout(val left: Int, val top: Int, val width: Int, val height: Int, val scale: Float, val sharp: Boolean)

internal fun vncFrameLayout(viewW: Float, viewH: Float, fbW: Int, fbH: Int, fit: Boolean): VncFrameLayout? {
    if (fbW <= 0 || fbH <= 0) return null
    if (!fit) return VncFrameLayout(0, 0, fbW, fbH, 1f, true)
    if (viewW <= 0f || viewH <= 0f) return null
    val exact = min(viewW / fbW, viewH / fbH)
    if (exact <= 0f) return null
    val whole = kotlin.math.floor(exact)
    val scale = if (whole >= 1f && exact - whole <= exact * 0.1f) whole else exact
    val width = (fbW * scale).toInt().coerceAtLeast(1)
    val height = (fbH * scale).toInt().coerceAtLeast(1)
    val sharp = scale == whole && width == fbW * whole.toInt() && height == fbH * whole.toInt()
    return VncFrameLayout(((viewW - width) / 2f).toInt(), ((viewH - height) / 2f).toInt(), width, height, scale, sharp)
}

/** View-local point to a framebuffer pixel. Null when the point is in the letterbox. Uses the same layout as the draw. */
internal fun vncPointerPoint(localX: Float, localY: Float, viewW: Float, viewH: Float, fbW: Int, fbH: Int, fit: Boolean): Pair<Int, Int>? {
    if (viewW <= 0f || viewH <= 0f) return null
    val layout = vncFrameLayout(viewW, viewH, fbW, fbH, fit) ?: return null
    val x = localX - layout.left
    val y = localY - layout.top
    if (x < 0f || y < 0f || x >= layout.width || y >= layout.height) return null
    return (x / layout.scale).toInt().coerceIn(0, fbW - 1) to (y / layout.scale).toInt().coerceIn(0, fbH - 1)
}

/**
 * RFB XT keycode for a Windows key event. AWT reports the layout's scan code for the key (no E0 flag), so the flag
 * is restored from the key: the navigation cluster outside the keypad, right Ctrl and Alt, keypad Enter and Divide,
 * the Windows and menu keys. Print Screen and Pause have fixed codes. Zero when unknown.
 */
internal fun windowsXtKeycode(scancode: Int, awtKeyCode: Int, location: Int): Int {
    if (awtKeyCode == java.awt.event.KeyEvent.VK_PRINTSCREEN) return 0xb7
    if (awtKeyCode == java.awt.event.KeyEvent.VK_PAUSE) return 0xc6
    if (scancode !in 1..0x7f) return 0
    val numpad = location == java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD
    val right = location == java.awt.event.KeyEvent.KEY_LOCATION_RIGHT
    val extended = when (awtKeyCode) {
        java.awt.event.KeyEvent.VK_LEFT, java.awt.event.KeyEvent.VK_RIGHT, java.awt.event.KeyEvent.VK_UP, java.awt.event.KeyEvent.VK_DOWN,
        java.awt.event.KeyEvent.VK_HOME, java.awt.event.KeyEvent.VK_END, java.awt.event.KeyEvent.VK_PAGE_UP, java.awt.event.KeyEvent.VK_PAGE_DOWN,
        java.awt.event.KeyEvent.VK_INSERT, java.awt.event.KeyEvent.VK_DELETE -> !numpad
        java.awt.event.KeyEvent.VK_CONTROL, java.awt.event.KeyEvent.VK_ALT -> right
        java.awt.event.KeyEvent.VK_ENTER, java.awt.event.KeyEvent.VK_DIVIDE -> numpad
        java.awt.event.KeyEvent.VK_ALT_GRAPH, java.awt.event.KeyEvent.VK_WINDOWS, java.awt.event.KeyEvent.VK_CONTEXT_MENU, java.awt.event.KeyEvent.VK_META -> true
        else -> false
    }
    return vncXtKeycode(scancode, extended)
}

/** Linux evdev codes past the main block, as RFB XT keycodes. Codes 1..83 and 86..88 are the XT make code itself. */
private val evdevExtended = mapOf(
    96 to 0x9c, 97 to 0x9d, 98 to 0xb5, 99 to 0xb7, 100 to 0xb8, 102 to 0xc7, 103 to 0xc8, 104 to 0xc9, 105 to 0xcb,
    106 to 0xcd, 107 to 0xcf, 108 to 0xd0, 109 to 0xd1, 110 to 0xd2, 111 to 0xd3, 117 to 0x59, 119 to 0xc6,
    125 to 0xdb, 126 to 0xdc, 127 to 0xdd,
)

/** RFB XT keycode for an X11 keycode (evdev code + 8, as Xorg and XWayland use). Zero when unknown. */
internal fun x11XtKeycode(x11Keycode: Int): Int {
    val evdev = x11Keycode - 8
    return when {
        evdev in 1..83 || evdev in 86..88 -> evdev
        else -> evdevExtended[evdev] ?: 0
    }
}

/** `rawCode` and `scancode` from [java.awt.event.KeyEvent.paramString], the only public view of them. */
internal fun awtKeyCodes(paramString: String): Pair<Int, Int> {
    fun field(name: String) = Regex("(?:^|,)$name=(\\d+)").find(paramString)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it in 0..0xffff }?.toInt() ?: 0
    return field("rawCode") to field("scancode")
}

private val windowsHost = System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")
private val linuxHost = System.getProperty("os.name").orEmpty().lowercase().startsWith("linux")

/** The physical key as an RFB XT keycode, for QEMU extended key events. Zero (keysym only) when it cannot be known. */
internal fun vncKeycode(event: KeyEvent): Int {
    val awt = event.awtEventOrNull ?: return 0
    val (raw, scan) = awtKeyCodes(awt.paramString())
    return when {
        windowsHost -> windowsXtKeycode(scan, awt.keyCode, awt.keyLocation)
        linuxHost -> x11XtKeycode(raw)
        else -> 0
    }
}

/** Identifies one physical key for key-up matching: the key code and its location. */
private fun keyId(event: KeyEvent): Long = event.key.keyCode

private fun safeConsoleReason(error: Exception): String {
    val raw = when (error) {
        is SessionExpiredException -> "The session expired. Reconnect to open a console."
        is java.io.IOException, is javax.net.ssl.SSLException -> readableError(error)
        else -> error.message ?: VNC_LOST
    }
    return if ("vncticket" in raw || "wss://" in raw) VNC_LOST else raw
}

/** Left Ctrl, left Alt, Delete: keysyms plus XT keycodes (1D, 38, E0 53), so it works with or without extended keys. */
private fun sendCtrlAltDel(session: VncSession) {
    val keys = listOf(VncKey.ControlLeft to 0x1d, VncKey.AltLeft to 0x38, VncKey.Delete to 0xd3)
    keys.forEach { (key, code) -> session.key(true, vncKeysym(key, 0)!!, code) }
    keys.asReversed().forEach { (key, code) -> session.key(false, vncKeysym(key, 0)!!, code) }
}

/**
 * In-app VNC console. Opens [Workspace.openConsole] while composed and closes it when the guest,
 * connection, or this view leaves the composition.
 */
@Composable fun VncConsoleView(state: WorkspaceState, workspace: Workspace, guest: Guest, modifier: Modifier = Modifier) {
    var serial by remember(guest.resource) { mutableStateOf(false) }
    val serialAvailable = state.inspection?.takeIf { it.resource == guest.resource && it.node == guest.node }?.hardware?.value?.fields?.containsKey("serial0") == true
    if (serial && serialAvailable) {
        Column(modifier.fillMaxSize()) {
            Row(Modifier.height(32.dp)) { MiniAction("VNC") { serial = false }; Text("Serial", color = Tech.Amber) }
            TerminalView(state, workspace, TerminalTarget.guest(guest), Modifier.weight(1f))
        }
        return
    }
    val scope = rememberCoroutineScope()
    var reconnect by remember(guest.resource) { mutableStateOf(0) }
    val status = remember(guest.resource, state.connectionEpoch, reconnect) { mutableStateOf("Connecting…") }
    val failed = remember(guest.resource, state.connectionEpoch, reconnect) { mutableStateOf(false) }
    val session = remember(guest.resource, state.connectionEpoch, reconnect) { mutableStateOf<VncSession?>(null) }
    var fit by remember { mutableStateOf(true) }
    var focused by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf(0) }
    var fbW by remember { mutableStateOf(0) }
    var fbH by remember { mutableStateOf(0) }
    var cursor by remember { mutableStateOf<VncCursor?>(null) }
    var screen by remember { mutableStateOf(false) }
    val frames = remember { FramePixels() }
    val pixels = remember { mutableStateOf(IntArray(0)) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val alive = remember(guest.resource, state.connectionEpoch, reconnect) { AtomicBoolean(true) }
    fun publish(live: VncSession) {
        if (!alive.get()) return
        val current = pixels.value
        val probe = live.readInto(current)
        val dest = if (probe.copied) current else IntArray(probe.width * probe.height).also {
            pixels.value = it
            live.readInto(it)
        }
        fbW = probe.width
        fbH = probe.height
        if (probe.cursor != cursor) {
            cursor = probe.cursor
            frames.cursor(probe.cursor)
        }
        if (probe.width > 0 && probe.height > 0) { frames.write(probe.width, probe.height, dest); screen = true }
        frame++
    }
    // While this view is composed the guest has a live console, so the Summary page does not auto-capture a still.
    DisposableEffect(guest.resource) {
        LiveConsoles.open(guest.resource)
        onDispose { LiveConsoles.close(guest.resource) }
    }
    DisposableEffect(guest.resource, state.connectionEpoch, reconnect) {
        val uiTrace = Trace.begin(TraceSource.Console, "VNC UI attached")
        alive.set(true)
        val box = AtomicReference<VncSession?>(null)
        val job = scope.launch {
            try {
                val opened = workspace.openConsole(guest)
                box.set(opened)
                if (!isActive || !alive.get()) { opened.close(); return@launch }
                session.value = opened
                status.value = "Connected ${opened.width}×${opened.height}"
                // Coalesced: however fast updates arrive, at most one publish waits on the event queue.
                val pending = AtomicBoolean(false)
                opened.onUpdate = { if (pending.compareAndSet(false, true)) EventQueue.invokeLater { pending.set(false); if (alive.get()) publish(opened) } }
                opened.onClosed = { reason -> EventQueue.invokeLater { if (alive.get() && status.value != VNC_GUEST_STOPPED) { failed.value = true; status.value = reason } } }
                publish(opened)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (alive.get()) { failed.value = true; status.value = safeConsoleReason(e) } }
        }
        onDispose {
            uiTrace.end()
            alive.set(false)
            job.cancel()
            // close() sends a key-up for every held key and button mask 0 before the socket goes.
            box.get()?.let { it.onUpdate = null; it.onClosed = null; it.close() }
            session.value = null
            focused = false
            VncKeyboard.captured = false
        }
    }
    DisposableEffect(frames) { onDispose { frames.close() } }
    LaunchedEffect(session.value, guest.status, guest.template) {
        val live = session.value ?: return@LaunchedEffect
        if (guest.status != "running" || guest.template) {
            failed.value = true
            status.value = VNC_GUEST_STOPPED
            live.close()
        }
    }
    val hardware = state.inspection?.takeIf { it.resource == guest.resource && it.node == guest.node }?.hardware?.value
    val spice = hardware != null && spiceDisplayAvailable(hardware.fields["vga"])
    val haveFrame = frame > 0 && fbW > 0 && fbH > 0
    // The first frame arrives with the CRT power-on reveal (a draw effect, so input and frames are never delayed); it replaces the old alpha fade.
    val liveAlpha = 1f
    val powerOn = if (haveFrame) session.value else null
    Column(modifier.fillMaxSize()) {
        ConsoleToolbar(status.value, failed.value, spice, hardware != null, session.value, fit, { fit = !fit }, { reconnect++ }, state, workspace, guest, focusManager, { serial = true })
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            val still = if (!haveFrame) rememberStillBitmap(state.consoleStills[guest.resource]) else null
            if (still != null) Image(still, "Last known screen", Modifier.fillMaxSize().alpha(0.45f), contentScale = ContentScale.Fit)
            if (screen && haveFrame) {
                Box(Modifier.fillMaxSize().crtPowerOn(powerOn)) {
                    VncSurface(
                        frames, frame, cursor, fbW, fbH, fit, focused, liveAlpha, session.value, focus,
                        onFocus = {
                            focused = it; VncKeyboard.captured = it
                            // Keys still held when the keyboard leaves would stay down in the guest.
                            if (!it) session.value?.releaseInput()
                        },
                        onOutside = { focusManager.clearFocus() },
                    )
                }
            } else if (still == null) {
                Text(if (failed.value) status.value else "CONNECTING…", Modifier.align(Alignment.Center), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted)
            }
        }
    }
}

@Composable private fun ConsoleToolbar(
    status: String, failed: Boolean, spice: Boolean, spiceKnown: Boolean, session: VncSession?, fit: Boolean,
    onFit: () -> Unit, onReconnect: () -> Unit, state: WorkspaceState, workspace: Workspace, guest: Guest,
    focusManager: androidx.compose.ui.focus.FocusManager, onSerial: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().height(if (spice) 48.dp else 32.dp).pointerInput(focusManager) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                if (event.type == PointerEventType.Press) focusManager.clearFocus()
            }
        }
    }) {
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(status, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = Tech.Mono, fontSize = 11.sp, color = if (failed) Tech.Amber else if (session != null) Tech.Green else Tech.Muted)
            // The SPICE note shares the toolbar row, so it never crowds the screen below.
            Text(if (spice) "·  SPICE gives a richer console: clipboard, USB and audio." else "", Modifier.weight(1f).padding(start = 6.dp), maxLines = 1,
                overflow = TextOverflow.Ellipsis, fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted)
            if (spice) SpiceConsoleButton(state, workspace, guest, "Open in SPICE")
            else if (spiceKnown) HelpTip("Set Display to SPICE (qxl) in this VM's hardware for clipboard, USB and audio.") {
                Text("ⓘ", color = Tech.Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 4.dp))
            }
            if (state.inspection?.hardware?.value?.fields?.containsKey("serial0") == true) MiniAction("Serial", onClick = onSerial)
            MiniAction("Ctrl+Alt+Del", enabled = session != null) { session?.let { sendCtrlAltDel(it) } }
            MiniAction(if (fit) "Fit" else "100 %", onClick = onFit)
            MiniAction("Reconnect", onClick = onReconnect)
        }
    }
}

@Composable private fun VncSurface(
    frames: FramePixels, stamp: Int, cursor: VncCursor?, fbW: Int, fbH: Int, fit: Boolean,
    focused: Boolean, alpha: Float, session: VncSession?, focus: FocusRequester,
    onFocus: (Boolean) -> Unit, onOutside: () -> Unit,
) {
    val density = LocalDensity.current
    var pointer by remember { mutableStateOf<IntOffset?>(null) }
    var inside by remember { mutableStateOf(false) }
    // The amber edge announces that the console took the keyboard, holds briefly, then fades away; it used to stay lit
    // for as long as the console had focus. The hint at the bottom stays, in grey, as the lasting sign.
    val edge = rememberArrivalGlow(focused, lit = focused)
    val sessionRef = rememberUpdatedState(session)
    val fitRef = rememberUpdatedState(fit)
    val sizeRef = rememberUpdatedState(fbW to fbH)
    fun point(x: Float, y: Float, viewW: Float, viewH: Float) = vncPointerPoint(x, y, viewW, viewH, sizeRef.value.first, sizeRef.value.second, fitRef.value)
    // The guest draws its own cursor image; the OS pointer is hidden over the screen so there are not two.
    val hideOsCursor = inside && cursor != null && frames.hasCursor()
    val input = Modifier
        .focusRequester(focus)
        .onFocusChanged { onFocus(it.isFocused) }
        .focusable()
        .onPreviewKeyEvent { event ->
            if (!VncKeyboard.captured) return@onPreviewKeyEvent false
            if (event.type != KeyEventType.KeyDown && event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
            val live = sessionRef.value
            val keycode = vncKeycode(event)
            // With extended keys a key without a keysym (a dead key such as ^ on a German layout) still has a keycode.
            val keysym = vncKeysym(event) ?: if (keycode != 0 && live?.extendedKeys == true) 0 else return@onPreviewKeyEvent false
            live?.keyInput(keyId(event), event.type == KeyEventType.KeyDown, keysym, keycode)
            true
        }
        .pointerHoverIcon(if (hideOsCursor) blankPointer() else PointerIcon.Default)
        .pointerInput(fbW, fbH, session) {
            var wheelY = 0f
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: continue
                    val live = sessionRef.value
                    val (fw, fh) = sizeRef.value
                    val local = change.position
                    val mapped = if (event.type == PointerEventType.Exit) null
                        else point(local.x, local.y, if (fitRef.value) size.width.toFloat() else fw.toFloat(), if (fitRef.value) size.height.toFloat() else fh.toFloat())
                    if (mapped == null) {
                        inside = false
                        // Leaving the screen with a button down would leave it held in the guest.
                        if (live?.buttonsHeld == true) live.releaseButtons()
                        if (event.type == PointerEventType.Press) onOutside()
                        continue
                    }
                    inside = true
                    if (event.type == PointerEventType.Press) focus.requestFocus()
                    val (x, y) = mapped
                    pointer = IntOffset(x, y)
                    var mask = 0
                    if (event.buttons.isPrimaryPressed) mask = mask or 1
                    if (event.buttons.isTertiaryPressed) mask = mask or 2
                    if (event.buttons.isSecondaryPressed) mask = mask or 4
                    val scroll = change.scrollDelta
                    if (scroll.y != 0f && live != null) {
                        wheelY = (wheelY + scroll.y).coerceIn(-5f, 5f)
                        val steps = wheelY.toInt()
                        wheelY -= steps.toFloat()
                        if (steps != 0) {
                            val bit = if (steps > 0) 16 else 8
                            repeat(kotlin.math.abs(steps)) { live.pointer(mask or bit, x, y); live.pointer(mask, x, y) }
                        }
                    } else live?.pointer(mask, x, y)
                    change.consume()
                }
            }
        }
        .drawWithContent { drawContent(); val a = edge.value; if (a > 0f) drawRect(Tech.Amber.copy(alpha = a), style = Stroke(1.dp.toPx())) }
    Box(Modifier.fillMaxSize()) {
        if (fit) {
            Box(Modifier.fillMaxSize().then(input)) {
                FrameCanvas(frames, stamp, fbW, fbH, fit = true, alpha)
                CursorOverlay(frames, cursor, pointer, fbW, fbH, true)
                if (focused) FocusHint { edge.value }
            }
        } else {
            val width = with(density) { fbW.toDp() }
            val height = with(density) { fbH.toDp() }
            Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
                Box(Modifier.size(width, height).then(input)) {
                    FrameCanvas(frames, stamp, fbW, fbH, fit = false, alpha)
                    CursorOverlay(frames, cursor, pointer, fbW, fbH, false)
                    if (focused) FocusHint { edge.value }
                }
            }
        }
    }
}

/** A fully transparent pointer, made once. Falls back to the normal pointer where AWT cannot make custom cursors. */
private var blankCursor: PointerIcon? = null
private fun blankPointer(): PointerIcon = blankCursor ?: runCatching {
    val image = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    PointerIcon(java.awt.Toolkit.getDefaultToolkit().createCustomCursor(image, java.awt.Point(0, 0), "vnc-hidden"))
}.getOrDefault(PointerIcon.Default).also { blankCursor = it }

/** Amber while the edge is lit ([glow] 1), grey once it has faded (0): the keyboard is still captured, quietly. */
@Composable private fun BoxScope.FocusHint(glow: () -> Float) {
    Text("Keyboard captured · click outside to release",
        Modifier.align(Alignment.BottomCenter).padding(8.dp).background(Color.Black.copy(alpha = .65f), Tech.Control).padding(horizontal = 8.dp, vertical = 3.dp),
        color = androidx.compose.ui.graphics.lerp(Tech.Muted, Tech.Amber, glow().coerceIn(0f, 1f)), fontFamily = Tech.Mono, fontSize = 10.sp, maxLines = 1)
}

/**
 * Draws the live bitmap. [stamp] is read while drawing so a new framebuffer version repaints the same bitmap. The draw
 * takes the bitmap from [frames] under its lock, so it never sees a half-written or retired bitmap. At a whole-number
 * scale (100 % always) the frame is drawn with nearest-neighbour sampling.
 */
@Composable private fun FrameCanvas(frames: FramePixels, stamp: Int, fbW: Int, fbH: Int, fit: Boolean, alpha: Float) {
    Canvas(Modifier.fillMaxSize()) {
        if (stamp < 0 || fbW <= 0 || fbH <= 0) return@Canvas
        val layout = vncFrameLayout(size.width, size.height, fbW, fbH, fit) ?: return@Canvas
        frames.drawFrame { image ->
            drawImage(image, dstOffset = IntOffset(layout.left, layout.top), dstSize = IntSize(layout.width, layout.height), alpha = alpha,
                filterQuality = if (layout.sharp) FilterQuality.None else FilterQuality.Low)
        }
    }
}

@Composable private fun CursorOverlay(frames: FramePixels, cursor: VncCursor?, pointer: IntOffset?, fbW: Int, fbH: Int, fit: Boolean) {
    if (cursor == null || pointer == null || fbW <= 0 || fbH <= 0) return
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val layout = vncFrameLayout(size.width, size.height, fbW, fbH, fit) ?: return@Canvas
        val x = layout.left + (pointer.x - cursor.hotspotX) * layout.scale
        val y = layout.top + (pointer.y - cursor.hotspotY) * layout.scale
        frames.drawCursor { image ->
            drawImage(image, dstOffset = IntOffset(x.toInt(), y.toInt()), dstSize = IntSize((cursor.width * layout.scale).toInt().coerceAtLeast(1), (cursor.height * layout.scale).toInt().coerceAtLeast(1)),
                filterQuality = if (layout.sharp) FilterQuality.None else FilterQuality.Low)
        }
    }
}

/**
 * One Skia bitmap for the framebuffer, replaced only when the size changes, plus the latest cursor bitmap. Writes
 * (on the event queue) and draws (on whatever thread renders) share one lock, and a draw always takes the current
 * bitmap, so a replaced bitmap can be closed at once: nothing can still be drawing it. Compose copies a mutable
 * bitmap when it records the draw, so the lock covers the whole use.
 */
internal class FramePixels {
    private val lock = Any()
    private var bitmap: Bitmap? = null
    private var image: ImageBitmap? = null
    private var cursorBitmap: Bitmap? = null
    private var cursorImage: ImageBitmap? = null
    private var closed = false
    fun write(width: Int, height: Int, pixels: IntArray): Unit = synchronized(lock) {
        if (closed) return
        var current = bitmap
        if (current == null || current.width != width || current.height != height) {
            image = null
            current?.close()
            current = Bitmap().apply { allocN32Pixels(width, height, true) }
            bitmap = current
        }
        putPixels(current, pixels, width * height)
        if (image == null) image = current.asComposeImageBitmap()
    }
    fun cursor(cursor: VncCursor?): Unit = synchronized(lock) {
        if (closed) return
        cursorImage = null
        cursorBitmap?.close(); cursorBitmap = null
        if (cursor == null) return
        val current = Bitmap().apply { allocN32Pixels(cursor.width, cursor.height, false) }
        putPixels(current, cursor.argb, cursor.argb.size)
        cursorBitmap = current
        cursorImage = current.asComposeImageBitmap()
    }
    fun hasCursor(): Boolean = synchronized(lock) { cursorImage != null }
    /** Runs [draw] with the current frame while holding the lock. Does nothing before the first frame or after [close]. */
    fun drawFrame(draw: (ImageBitmap) -> Unit): Unit = synchronized(lock) { image?.let(draw) }
    fun drawCursor(draw: (ImageBitmap) -> Unit): Unit = synchronized(lock) { cursorImage?.let(draw) }
    fun close(): Unit = synchronized(lock) {
        closed = true
        image = null; cursorImage = null
        bitmap?.close(); bitmap = null
        cursorBitmap?.close(); cursorBitmap = null
    }
}

/**
 * Copies ARGB ints into an N32 bitmap (little-endian BGRA) through Skia's supported installPixels, reusing one byte
 * buffer per size. No foreign-memory access, so it runs on a plain JDK 21 without preview or native-access flags.
 */
private val pixelBytes = ThreadLocal<ByteArray>()
private fun putPixels(bitmap: Bitmap, pixels: IntArray, count: Int) {
    val width = bitmap.width
    val height = bitmap.height
    val size = width * height * 4
    var bytes = pixelBytes.get()
    if (bytes == null || bytes.size != size) { bytes = ByteArray(size); pixelBytes.set(bytes) }
    java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixels, 0, min(count, width * height))
    bitmap.installPixels(bitmap.imageInfo, bytes, width * 4)
    bitmap.notifyPixelsChanged()
}
