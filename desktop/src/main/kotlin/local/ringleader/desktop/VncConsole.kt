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
import androidx.compose.ui.input.pointer.PointerEventPass
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

/** View-local point to a framebuffer pixel. Null when the point is in the letterbox. */
internal fun vncPointerPoint(localX: Float, localY: Float, viewW: Float, viewH: Float, fbW: Int, fbH: Int, fit: Boolean): Pair<Int, Int>? {
    if (fbW <= 0 || fbH <= 0 || viewW <= 0f || viewH <= 0f) return null
    if (!fit) {
        if (localX < 0f || localY < 0f || localX >= fbW || localY >= fbH) return null
        return localX.toInt().coerceIn(0, fbW - 1) to localY.toInt().coerceIn(0, fbH - 1)
    }
    val scale = min(viewW / fbW, viewH / fbH)
    if (scale <= 0f) return null
    val width = fbW * scale
    val height = fbH * scale
    val left = (viewW - width) / 2f
    val top = (viewH - height) / 2f
    if (localX < left || localY < top || localX >= left + width || localY >= top + height) return null
    return ((localX - left) / scale).toInt().coerceIn(0, fbW - 1) to ((localY - top) / scale).toInt().coerceIn(0, fbH - 1)
}

private fun safeConsoleReason(error: Exception): String {
    val raw = when (error) {
        is SessionExpiredException -> "The session expired. Reconnect to open a console."
        is java.io.IOException, is javax.net.ssl.SSLException -> readableError(error)
        else -> error.message ?: VNC_LOST
    }
    return if ("vncticket" in raw || "wss://" in raw) VNC_LOST else raw
}

private fun sendCtrlAltDel(session: VncSession) {
    val keys = listOf(VncKey.ControlLeft, VncKey.AltLeft, VncKey.Delete)
    keys.forEach { session.key(true, vncKeysym(it, 0)!!) }
    keys.asReversed().forEach { session.key(false, vncKeysym(it, 0)!!) }
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
    var cursorImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var screen by remember { mutableStateOf<ImageBitmap?>(null) }
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
            cursorImage = probe.cursor?.let { frames.cursor(it) }
        }
        if (probe.width > 0 && probe.height > 0) screen = frames.write(probe.width, probe.height, dest)
        frame++
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
            val image = screen
            if (image != null && haveFrame) {
                Box(Modifier.fillMaxSize().crtPowerOn(powerOn)) {
                    VncSurface(
                        image, frame, cursorImage, cursor, fbW, fbH, fit, focused, liveAlpha, session.value, focus,
                        onFocus = { focused = it; VncKeyboard.captured = it },
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
    image: ImageBitmap, stamp: Int, cursorImage: ImageBitmap?, cursor: VncCursor?, fbW: Int, fbH: Int, fit: Boolean,
    focused: Boolean, alpha: Float, session: VncSession?, focus: FocusRequester,
    onFocus: (Boolean) -> Unit, onOutside: () -> Unit,
) {
    val density = LocalDensity.current
    var pointer by remember { mutableStateOf<IntOffset?>(null) }
    // The amber edge announces that the console took the keyboard, holds briefly, then fades away; it used to stay lit
    // for as long as the console had focus. The hint at the bottom stays, in grey, as the lasting sign.
    val edge = rememberArrivalGlow(focused, lit = focused)
    val sessionRef = rememberUpdatedState(session)
    val fitRef = rememberUpdatedState(fit)
    val sizeRef = rememberUpdatedState(fbW to fbH)
    fun point(x: Float, y: Float, viewW: Float, viewH: Float) = vncPointerPoint(x, y, viewW, viewH, sizeRef.value.first, sizeRef.value.second, fitRef.value)
    val input = Modifier
        .focusRequester(focus)
        .onFocusChanged { onFocus(it.isFocused) }
        .focusable()
        .onPreviewKeyEvent { event ->
            if (!VncKeyboard.captured) return@onPreviewKeyEvent false
            if (event.type != KeyEventType.KeyDown && event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
            val keysym = vncKeysym(event) ?: return@onPreviewKeyEvent false
            sessionRef.value?.key(event.type == KeyEventType.KeyDown, keysym)
            true
        }
        .pointerInput(fbW, fbH, session) {
            var wheelY = 0f
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: continue
                    val live = sessionRef.value
                    val (fw, fh) = sizeRef.value
                    val local = change.position
                    val mapped = point(local.x, local.y, if (fitRef.value) size.width.toFloat() else fw.toFloat(), if (fitRef.value) size.height.toFloat() else fh.toFloat())
                    if (mapped == null) {
                        if (event.type == PointerEventType.Press) onOutside()
                        continue
                    }
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
                FrameCanvas(image, stamp, fbW, fbH, fit = true, alpha)
                CursorOverlay(cursorImage, cursor, pointer, fbW, fbH, true)
                if (focused) FocusHint { edge.value }
            }
        } else {
            val width = with(density) { fbW.toDp() }
            val height = with(density) { fbH.toDp() }
            Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
                Box(Modifier.size(width, height).then(input)) {
                    FrameCanvas(image, stamp, fbW, fbH, fit = false, alpha)
                    CursorOverlay(cursorImage, cursor, pointer, fbW, fbH, false)
                    if (focused) FocusHint { edge.value }
                }
            }
        }
    }
}

/** Amber while the edge is lit ([glow] 1), grey once it has faded (0): the keyboard is still captured, quietly. */
@Composable private fun BoxScope.FocusHint(glow: () -> Float) {
    Text("Keyboard captured · click outside to release",
        Modifier.align(Alignment.BottomCenter).padding(8.dp).background(Color.Black.copy(alpha = .65f), Tech.Control).padding(horizontal = 8.dp, vertical = 3.dp),
        color = androidx.compose.ui.graphics.lerp(Tech.Muted, Tech.Amber, glow().coerceIn(0f, 1f)), fontFamily = Tech.Mono, fontSize = 10.sp, maxLines = 1)
}

/** Draws the live bitmap. [stamp] is read while drawing so a new framebuffer version repaints the same bitmap. */
@Composable private fun FrameCanvas(image: ImageBitmap, stamp: Int, fbW: Int, fbH: Int, fit: Boolean, alpha: Float) {
    Canvas(Modifier.fillMaxSize()) {
        if (stamp < 0 || fbW <= 0 || fbH <= 0) return@Canvas
        if (!fit) {
            drawImage(image, dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)), alpha = alpha)
        } else {
            val scale = min(size.width / fbW, size.height / fbH)
            val dw = (fbW * scale).toInt().coerceAtLeast(1)
            val dh = (fbH * scale).toInt().coerceAtLeast(1)
            val left = ((size.width - dw) / 2f).toInt()
            val top = ((size.height - dh) / 2f).toInt()
            drawImage(image, dstOffset = IntOffset(left, top), dstSize = IntSize(dw, dh), alpha = alpha)
        }
    }
}

@Composable private fun CursorOverlay(image: ImageBitmap?, cursor: VncCursor?, pointer: IntOffset?, fbW: Int, fbH: Int, fit: Boolean) {
    if (image == null || cursor == null || pointer == null || fbW <= 0 || fbH <= 0) return
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val scale = if (!fit) 1f else min(size.width / fbW, size.height / fbH)
        val left = if (!fit) 0f else (size.width - fbW * scale) / 2f
        val top = if (!fit) 0f else (size.height - fbH * scale) / 2f
        val x = left + (pointer.x - cursor.hotspotX) * scale
        val y = top + (pointer.y - cursor.hotspotY) * scale
        drawImage(image, dstOffset = IntOffset(x.toInt(), y.toInt()), dstSize = IntSize((cursor.width * scale).toInt().coerceAtLeast(1), (cursor.height * scale).toInt().coerceAtLeast(1)))
    }
}

/** One Skia bitmap for the framebuffer, replaced only when the size changes, plus the latest cursor bitmap. */
private class FramePixels {
    private var bitmap: Bitmap? = null
    private var image: ImageBitmap? = null
    private var cursorBitmap: Bitmap? = null
    private var retiredCursor: Bitmap? = null
    private var retiredFrame: Bitmap? = null
    fun write(width: Int, height: Int, pixels: IntArray): ImageBitmap {
        retiredFrame?.close()
        retiredFrame = null
        var current = bitmap
        var fresh = false
        if (current == null || current.width != width || current.height != height) {
            retiredFrame = current
            current = Bitmap().apply { allocN32Pixels(width, height, true) }
            bitmap = current
            fresh = true
        }
        putPixels(current, pixels, width * height)
        if (fresh || image == null) image = current.asComposeImageBitmap()
        return image!!
    }
    fun cursor(cursor: VncCursor): ImageBitmap {
        retiredCursor?.close()
        retiredCursor = cursorBitmap
        val current = Bitmap().apply { allocN32Pixels(cursor.width, cursor.height, false) }
        cursorBitmap = current
        putPixels(current, cursor.argb, cursor.argb.size)
        return current.asComposeImageBitmap()
    }
    fun close() {
        bitmap?.close(); bitmap = null
        retiredFrame?.close(); retiredFrame = null
        cursorBitmap?.close(); cursorBitmap = null
        retiredCursor?.close(); retiredCursor = null
        image = null
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
