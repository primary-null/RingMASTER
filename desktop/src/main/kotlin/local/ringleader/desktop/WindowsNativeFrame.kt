package local.ringleader.desktop

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.WindowPlacement
import com.sun.jna.CallbackReference
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import java.awt.EventQueue
import java.awt.Window
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import local.ringleader.core.Trace
import local.ringleader.core.TraceOutcome
import local.ringleader.core.TraceSource

/**
 * The native frame for one window. Windows owns the caption drag, the resize and the maximise button, which is where
 * snapping lives. The window procedure runs on the AWT toolkit thread: it only reads [snapshot], and it never calls
 * Compose or Swing. [callback] is held here, and the instance is held in [live], so the native thunk cannot be collected.
 */
internal class WindowsNativeFrame private constructor(private val window: Window, private val hwnd: Pointer) {
    val hovered = mutableStateOf(false)
    val pressed = mutableStateOf(false)
    var onMaximize: (() -> Unit)? = null

    @Volatile private var snapshot = FrameSnapshot(EMPTY_REGIONS, FrameMode.Floating)
    // A nested class, not an inner one: JNA calls this from native code, and a hidden outer pointer would shift the arguments.
    private val callback: FrameProc = Proc(this)
    private var previous: Long = 0
    private var originalStyle: Int = 0
    private var installed = false
    private var trackingLeave = false
    @Volatile private var hoverWanted = false
    @Volatile private var pressWanted = false
    private var dark = true
    private var border = 0
    private val restyling = AtomicBoolean(false)
    @Volatile private var clientInsetX = 0
    @Volatile private var clientInsetY = 0
    private val calls = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var publishes = 0
    @Volatile private var lastMsg = 0
    @Volatile private var maxTrace = "minmax none"
    @Volatile private var posTrace = "clamp none"

    /** The layout and the mode, published together from the UI thread. Styles follow the mode; the theme follows the colours. */
    fun publish(placement: WindowPlacement, regions: FrameRegions, dark: Boolean, border: Int) {
        publishes++
        val mode = frameMode(placement)
        val modeChanged = snapshot.mode != mode
        snapshot = FrameSnapshot(regions, mode)
        if (!installed) return
        if (modeChanged) applyMode(mode)
        if (modeChanged || this.dark != dark || this.border != border) {
            this.dark = dark
            this.border = border
            applyTheme(dark, border)
        }
    }

    fun uninstall() {
        if (!installed) return
        installed = false
        synchronized(active) { active.remove(window) }
        try {
            val mine = Pointer.nativeValue(CallbackReference.getFunctionPointer(callback))
            val restored = user32.GetWindowLongPtrW(hwnd, GWLP_WNDPROC) == mine
            if (restored) user32.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, previous)
            setStyle(originalStyle)
            frameChanged()
            // Only now may the thunk be collected. If something subclassed the window after this frame, the thunk is
            // still in that chain and Windows can still call it: the frame then stays in [live] for the life of the
            // process, a few hundred bytes, instead of leaving a dead function pointer behind.
            if (restored) live.remove(this)
        } catch (_: Throwable) {
            // The window is going away and the restore did not finish. The frame stays in [live] for the same reason.
        }
    }

    internal fun currentStyle(): Int = user32.GetWindowLongPtrW(hwnd, GWL_STYLE).toInt()
    internal fun currentProc(): Long = user32.GetWindowLongPtrW(hwnd, GWLP_WNDPROC)
    internal fun originalStyle(): Int = originalStyle
    internal fun originalProc(): Long = previous
    internal fun sendHitTest(screenX: Int, screenY: Int): Int {
        val code = user32.SendMessageW(hwnd, WM_NCHITTEST, 0, packHitPoint(screenX, screenY))
        return code.toInt()
    }
    internal fun postCommand(command: Int) {
        user32.PostMessageW(hwnd, WM_SYSCOMMAND, command.toLong(), 0)
    }
    internal fun windowBox(): PixelBox {
        val rect = RECT()
        user32.GetWindowRect(hwnd, rect)
        return PixelBox(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top)
    }
    internal fun monitorWork(): MonitorWork? {
        val monitor = user32.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST) ?: return null
        val info = MONITORINFO()
        info.cbSize = MONITORINFO_SIZE
        if (!user32.GetMonitorInfoW(monitor, info)) return null
        return MonitorWork(
            info.rcMonitor.left, info.rcMonitor.top, info.rcMonitor.right, info.rcMonitor.bottom,
            info.rcWork.left, info.rcWork.top, info.rcWork.right, info.rcWork.bottom,
            runCatching { user32.GetDpiForWindow(hwnd) }.getOrDefault(96),
        )
    }

    private fun install(placement: WindowPlacement) {
        live.add(this)
        originalStyle = user32.GetWindowLongPtrW(hwnd, GWL_STYLE).toInt()
        var styled = false
        var subclassed = false
        try {
            setStyle(originalStyle or FRAME_BITS)
            styled = true
            val thunk = Pointer.nativeValue(CallbackReference.getFunctionPointer(callback))
            previous = user32.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, thunk)
            if (previous == 0L) error("no previous procedure")
            subclassed = true
            frameChanged()
            dark = !Appearance.palette.light
            border = colorRef(Appearance.palette.edge)
            applyTheme(dark, border)
            snapshot = snapshot.copy(mode = frameMode(placement))
            if (snapshot.mode == FrameMode.Fullscreen) removeFrameBits()
            installed = true
            synchronized(active) { active.add(window) }
        } catch (thrown: Throwable) {
            if (subclassed) runCatching { user32.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, previous) }
            if (styled) runCatching { setStyle(originalStyle); frameChanged() }
            live.remove(this)
            throw thrown
        }
    }

    private fun dispatch(hwnd: Pointer, msg: Int, wParam: Long, lParam: Long): Long {
        when (msg) {
            WM_NCCALCSIZE -> if (wParam != 0L) {
                // Floating and full screen keep the whole window as the client. A maximised thick frame is one
                // border outside the work area; the client is pulled back to the work area so the title bar is on screen.
                insetMaximizedClient(hwnd, lParam)
                return 0L
            }
            WM_NCHITTEST -> return hitTest(lParam).toLong()
            WM_GETMINMAXINFO -> {
                val result = callPrevious(hwnd, msg, wParam, lParam)
                applyMaxInfo(hwnd, lParam)
                return result
            }
            WM_NCMOUSEMOVE -> {
                if (wParam.toInt() == HTMAXBUTTON) onMaxMove(hwnd) else clearPointer()
            }
            WM_NCMOUSELEAVE -> clearPointer()
            WM_NCLBUTTONDOWN -> if (wParam.toInt() == HTMAXBUTTON) {
                pressWanted = true
                publishPress()
                return 0L
            }
            WM_NCLBUTTONUP -> if (wParam.toInt() == HTMAXBUTTON && pressWanted) {
                pressWanted = false
                publishPress()
                val toggle = onMaximize
                if (toggle != null) EventQueue.invokeLater { toggle() }
                return 0L
            }
            WM_STYLECHANGED -> if (wParam.toInt() == GWL_STYLE) ensureForMode()
        }
        return callPrevious(hwnd, msg, wParam, lParam)
    }

    private fun hitTest(lParam: Long): Int {
        val (screenX, screenY) = unpackHitPoint(lParam)
        val rect = RECT()
        if (!user32.GetWindowRect(hwnd, rect)) return HTCLIENT
        val seen = snapshot
        // A system maximise can land before Compose publishes the new placement. Full screen stays as published.
        val mode = when {
            seen.mode == FrameMode.Fullscreen -> FrameMode.Fullscreen
            seen.mode == FrameMode.Maximized -> FrameMode.Maximized
            user32.IsZoomed(hwnd) && !user32.IsIconic(hwnd) -> FrameMode.Maximized
            else -> FrameMode.Floating
        }
        return frameHitTest(screenX - rect.left - clientInsetX, screenY - rect.top - clientInsetY, seen.regions, mode)
    }

    /**
     * ptMaxPosition is relative to the monitor origin. The size is the work area. Windows still adds one thick-frame
     * border on every side after this returns (measured: 0,0 2560x1380 became -9,-9 2578x1398). [insetMaximizedClient]
     * pulls the client back. Four ints only, so the minimum size AWT wrote stays.
     */
    private fun applyMaxInfo(hwnd: Pointer, lParam: Long) {
        val placed = monitorPlacement(hwnd) ?: return
        val memory = Pointer(lParam)
        memory.setInt(8, placed.box.width)
        memory.setInt(12, placed.box.height)
        memory.setInt(16, placed.box.x - placed.monitorLeft)
        memory.setInt(20, placed.box.y - placed.monitorTop)
        maxTrace = "minmax ${placed.box.x - placed.monitorLeft},${placed.box.y - placed.monitorTop} ${placed.box.width}x${placed.box.height} border=${placed.border}"
    }

    /** When maximised, rgrc[0] becomes the work area. Otherwise the client stays the whole window and the inset is cleared. */
    private fun insetMaximizedClient(hwnd: Pointer, lParam: Long) {
        val memory = Pointer(lParam)
        val proposedLeft = memory.getInt(0)
        val proposedTop = memory.getInt(4)
        val proposedRight = memory.getInt(8)
        val proposedBottom = memory.getInt(12)
        val zoomed = snapshot.mode != FrameMode.Fullscreen && (
            snapshot.mode == FrameMode.Maximized || user32.IsZoomed(hwnd) ||
                (user32.GetWindowLongPtrW(hwnd, GWL_STYLE).toInt() and WS_MAXIMIZE) != 0)
        val box = if (zoomed) monitorPlacement(hwnd)?.box else null
        if (box == null || proposedRight - proposedLeft < box.width || proposedBottom - proposedTop < box.height) {
            clientInsetX = 0
            clientInsetY = 0
            return
        }
        memory.setInt(0, box.x)
        memory.setInt(4, box.y)
        memory.setInt(8, box.x + box.width)
        memory.setInt(12, box.y + box.height)
        clientInsetX = box.x - proposedLeft
        clientInsetY = box.y - proposedTop
        posTrace = "client ${box.x},${box.y} ${box.width}x${box.height} inset=$clientInsetX,$clientInsetY"
    }

    private fun monitorPlacement(hwnd: Pointer): MonitorPlacement? {
        val monitor = user32.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST) ?: return null
        val info = MONITORINFO()
        info.cbSize = MONITORINFO_SIZE
        if (!user32.GetMonitorInfoW(monitor, info)) return null
        val dpi = runCatching { user32.GetDpiForWindow(hwnd) }.getOrDefault(96)
        val frame = runCatching { user32.GetSystemMetricsForDpi(SM_CXFRAME, dpi) }.getOrDefault(0)
        val pad = runCatching { user32.GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi) }.getOrDefault(0)
        val box = maximizedWorkBox(
            info.rcMonitor.left, info.rcMonitor.top, info.rcMonitor.right, info.rcMonitor.bottom,
            info.rcWork.left, info.rcWork.top, info.rcWork.right, info.rcWork.bottom,
            WindowsTaskbar.autoHideEdge, autoHideStripPx(dpi),
        )
        return MonitorPlacement(info.rcMonitor.left, info.rcMonitor.top, info.rcMonitor.right, info.rcMonitor.bottom, box, frame + pad)
    }

    private class MonitorPlacement(
        val monitorLeft: Int, val monitorTop: Int, val monitorRight: Int, val monitorBottom: Int,
        val box: PixelBox, val border: Int,
    )
    /** True once Compose has published a laid-out title bar. The probe waits for this; a hit test before it sees an empty window. */
    internal fun layoutReady(): Boolean {
        val regions = snapshot.regions
        return regions.width > 0 && regions.height > 0 && regions.titleBar != null && regions.maximizeButton != null
    }

    /** Probe evidence: whether the subclass is still installed and what layout it last saw. */
    internal fun debugLine(): String {
        val mine = Pointer.nativeValue(CallbackReference.getFunctionPointer(callback))
        val now = currentProc()
        val seen = snapshot
        val title = seen.regions.titleBar
        return "calls=${calls.get()} publishes=$publishes lastMsg=${lastMsg.toString(16)} procMatch=${now == mine} snap=${seen.regions.width}x${seen.regions.height} mode=${seen.mode} title=${title?.left},${title?.top},${title?.right},${title?.bottom} $maxTrace $posTrace"
    }

    internal fun borderMetrics(): String {
        val dpi = runCatching { user32.GetDpiForWindow(hwnd) }.getOrDefault(0)
        val frame = runCatching { user32.GetSystemMetricsForDpi(SM_CXFRAME, dpi) }.getOrDefault(-1)
        val pad = runCatching { user32.GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi) }.getOrDefault(-1)
        return "dpi=$dpi cxframe=$frame pad=$pad"
    }

    private fun onMaxMove(hwnd: Pointer) {
        hoverWanted = true
        publishHover()
        if (trackingLeave) return
        val track = TRACKMOUSEEVENT()
        track.cbSize = track.size()
        track.dwFlags = TME_LEAVE or TME_NONCLIENT
        track.hwndTrack = hwnd
        if (user32.TrackMouseEvent(track)) trackingLeave = true
    }

    private fun clearPointer() {
        trackingLeave = false
        if (!hoverWanted && !pressWanted) return
        hoverWanted = false
        pressWanted = false
        publishHover()
        publishPress()
    }

    private fun publishHover() {
        val wanted = hoverWanted
        EventQueue.invokeLater { if (hovered.value != wanted) hovered.value = wanted }
    }

    private fun publishPress() {
        val wanted = pressWanted
        EventQueue.invokeLater { if (pressed.value != wanted) pressed.value = wanted }
    }

    private fun ensureForMode() {
        if (!installed || !restyling.compareAndSet(false, true)) return
        try {
            if (snapshot.mode == FrameMode.Fullscreen) removeFrameBits() else ensureFrameBits()
        } finally {
            restyling.set(false)
        }
    }

    private fun applyMode(mode: FrameMode) {
        if (mode != FrameMode.Maximized) {
            clientInsetX = 0
            clientInsetY = 0
        }
        if (!restyling.compareAndSet(false, true)) return
        try {
            ensureProc()
            if (mode == FrameMode.Fullscreen) removeFrameBits() else ensureFrameBits()
        } finally {
            restyling.set(false)
        }
    }

    /** AWT replaces the procedure when it enters and leaves full screen. Subclass again, keeping its new procedure. */
    private fun ensureProc() {
        val mine = Pointer.nativeValue(CallbackReference.getFunctionPointer(callback))
        val current = user32.GetWindowLongPtrW(hwnd, GWLP_WNDPROC)
        if (current != mine) previous = user32.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, mine)
    }

    private fun ensureFrameBits() {
        val style = user32.GetWindowLongPtrW(hwnd, GWL_STYLE).toInt()
        val next = style or FRAME_BITS
        if (next != style) {
            setStyle(next)
            frameChanged()
        }
    }

    private fun removeFrameBits() {
        val style = user32.GetWindowLongPtrW(hwnd, GWL_STYLE).toInt()
        val next = style and FRAME_BITS.inv()
        if (next != style) {
            setStyle(next)
            frameChanged()
        }
    }

    private fun setStyle(style: Int) {
        user32.SetWindowLongPtrW(hwnd, GWL_STYLE, style.toLong() and 0xFFFFFFFFL)
    }

    private fun frameChanged() {
        user32.SetWindowPos(hwnd, null, 0, 0, 0, 0, SWP_FRAMECHANGED or SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE)
    }

    private fun applyTheme(dark: Boolean, border: Int) {
        val dwm = dwmapi ?: return
        val flag = com.sun.jna.Memory(4)
        flag.setInt(0, if (dark) 1 else 0)
        dwm.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, flag, 4)
        val color = com.sun.jna.Memory(4)
        color.setInt(0, border)
        dwm.DwmSetWindowAttribute(hwnd, DWMWA_BORDER_COLOR, color, 4)
    }

    private fun callPrevious(hwnd: Pointer, msg: Int, wParam: Long, lParam: Long): Long =
        if (previous != 0L) user32.CallWindowProcW(previous, hwnd, msg, wParam, lParam)
        else user32.DefWindowProcW(hwnd, msg, wParam, lParam)

    private class Proc(private val frame: WindowsNativeFrame) : FrameProc {
        override fun invoke(hwnd: Pointer, msg: Int, wParam: Long, lParam: Long): Long {
            frame.calls.incrementAndGet()
            frame.lastMsg = msg
            return try {
                frame.dispatch(hwnd, msg, wParam, lParam)
            } catch (_: Throwable) {
                try {
                    frame.callPrevious(hwnd, msg, wParam, lParam)
                } catch (_: Throwable) {
                    0L
                }
            }
        }
    }

    companion object {
        private val live = Collections.synchronizedSet(mutableSetOf<WindowsNativeFrame>())
        private val active = Collections.newSetFromMap(IdentityHashMap<Window, Boolean>())

        fun isActive(window: Window): Boolean = synchronized(active) { window in active }

        /**
         * Installs the frame, or returns null when it is switched off, the peer is not ready, or a step failed.
         * [giveUp] is set only after a failed install that has already been undone.
         */
        fun attempt(window: Window, placement: WindowPlacement): Attempt {
            if (!nativeFrameRequested()) return Attempt(null, giveUp = false)
            if (!window.isDisplayable) return Attempt(null, giveUp = false)
            val hwnd = runCatching { Native.getWindowPointer(window) }.getOrNull() ?: return Attempt(null, giveUp = false)
            return try {
                val frame = WindowsNativeFrame(window, hwnd)
                frame.install(placement)
                Attempt(frame, giveUp = false)
            } catch (_: Throwable) {
                Trace.emit(TraceSource.App, "window", "Native frame unavailable", outcome = TraceOutcome.Warn)
                Attempt(null, giveUp = true)
            }
        }
    }
}

internal class Attempt(val frame: WindowsNativeFrame?, val giveUp: Boolean)

internal data class FrameSnapshot(val regions: FrameRegions, val mode: FrameMode)

internal data class MonitorWork(
    val monitorLeft: Int, val monitorTop: Int, val monitorRight: Int, val monitorBottom: Int,
    val workLeft: Int, val workTop: Int, val workRight: Int, val workBottom: Int,
    val dpi: Int,
)

private val EMPTY_REGIONS = FrameRegions(0, 0, null, null, emptyList(), 6, 14, 0)

internal fun frameMode(placement: WindowPlacement): FrameMode = when (placement) {
    WindowPlacement.Fullscreen -> FrameMode.Fullscreen
    WindowPlacement.Maximized -> FrameMode.Maximized
    else -> FrameMode.Floating
}

/** COLORREF is 0x00BBGGRR. */
internal fun colorRef(color: Color): Int {
    val red = (color.red * 255f).roundToInt().coerceIn(0, 255)
    val green = (color.green * 255f).roundToInt().coerceIn(0, 255)
    val blue = (color.blue * 255f).roundToInt().coerceIn(0, 255)
    return (blue shl 16) or (green shl 8) or red
}

private const val GWL_STYLE = -16
private const val GWLP_WNDPROC = -4
private const val WS_THICKFRAME = 0x00040000
private const val WS_MAXIMIZEBOX = 0x00010000
private const val WS_MINIMIZEBOX = 0x00020000
private const val WS_SYSMENU = 0x00080000
private const val WS_CAPTION = 0x00C00000
private const val FRAME_BITS = WS_CAPTION or WS_THICKFRAME or WS_MAXIMIZEBOX or WS_MINIMIZEBOX or WS_SYSMENU
private const val SWP_NOSIZE = 1
private const val SWP_NOMOVE = 2
private const val SWP_NOZORDER = 4
private const val SWP_NOACTIVATE = 0x10
private const val SWP_FRAMECHANGED = 0x0020
private const val WM_GETMINMAXINFO = 0x0024
private const val WS_MAXIMIZE = 0x01000000
private const val WM_NCCALCSIZE = 0x0083
private const val WM_NCHITTEST = 0x0084
private const val WM_SYSCOMMAND = 0x0112
private const val WM_STYLECHANGED = 0x007D
private const val WM_NCMOUSEMOVE = 0x00A0
private const val WM_NCLBUTTONDOWN = 0x00A1
private const val WM_NCLBUTTONUP = 0x00A2
private const val WM_NCMOUSELEAVE = 0x02A2
private const val TME_LEAVE = 0x00000002
private const val TME_NONCLIENT = 0x00000010
private const val MONITOR_DEFAULTTONEAREST = 2
private const val MONITORINFO_SIZE = 40
private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
private const val DWMWA_BORDER_COLOR = 34
private const val SM_CXFRAME = 32
private const val SM_CXPADDEDBORDER = 92

/** One native window-procedure method. Public to the JVM so JNA can bind it. */
internal interface FrameProc : StdCallLibrary.StdCallCallback {
    fun invoke(hwnd: Pointer, msg: Int, wParam: Long, lParam: Long): Long
}

internal interface User32 : StdCallLibrary {
    fun GetWindowLongPtrW(hwnd: Pointer, index: Int): Long
    fun SetWindowLongPtrW(hwnd: Pointer, index: Int, value: Long): Long
    fun CallWindowProcW(previous: Long, hwnd: Pointer, msg: Int, wParam: Long, lParam: Long): Long
    fun DefWindowProcW(hwnd: Pointer, msg: Int, wParam: Long, lParam: Long): Long
    fun SetWindowPos(hwnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
    fun GetWindowRect(hwnd: Pointer, rect: RECT): Boolean
    fun IsZoomed(hwnd: Pointer): Boolean
    fun IsIconic(hwnd: Pointer): Boolean
    fun GetSystemMetricsForDpi(index: Int, dpi: Int): Int
    fun GetDpiForWindow(hwnd: Pointer): Int
    fun MonitorFromWindow(hwnd: Pointer, flags: Int): Pointer?
    fun GetMonitorInfoW(monitor: Pointer, info: MONITORINFO): Boolean
    fun TrackMouseEvent(event: TRACKMOUSEEVENT): Boolean
    fun PostMessageW(hwnd: Pointer, msg: Int, wParam: Long, lParam: Long): Boolean
    fun SendMessageW(hwnd: Pointer, msg: Int, wParam: Long, lParam: Long): Long
}

internal interface DwmApi : StdCallLibrary {
    fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: Pointer, size: Int): Int
}

private val user32: User32 by lazy { Native.load("user32", User32::class.java) }
private val dwmapi: DwmApi? by lazy { runCatching { Native.load("dwmapi", DwmApi::class.java) }.getOrNull() }

@Structure.FieldOrder("left", "top", "right", "bottom")
internal class RECT : Structure() {
    @JvmField var left = 0
    @JvmField var top = 0
    @JvmField var right = 0
    @JvmField var bottom = 0
    override fun getFieldOrder() = listOf("left", "top", "right", "bottom")
}

@Structure.FieldOrder("cbSize", "rcMonitor", "rcWork", "dwFlags")
internal class MONITORINFO : Structure() {
    @JvmField var cbSize = MONITORINFO_SIZE
    @JvmField var rcMonitor = RECT()
    @JvmField var rcWork = RECT()
    @JvmField var dwFlags = 0
    override fun getFieldOrder() = listOf("cbSize", "rcMonitor", "rcWork", "dwFlags")
}

@Structure.FieldOrder("cbSize", "dwFlags", "hwndTrack", "dwHoverTime")
internal class TRACKMOUSEEVENT : Structure() {
    @JvmField var cbSize = 0
    @JvmField var dwFlags = 0
    @JvmField var hwndTrack: Pointer? = null
    @JvmField var dwHoverTime = 0
    override fun getFieldOrder() = listOf("cbSize", "dwFlags", "hwndTrack", "dwHoverTime")
}
