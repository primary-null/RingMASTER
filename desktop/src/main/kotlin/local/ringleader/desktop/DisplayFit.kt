package local.ringleader.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.GraphicsConfiguration
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.nio.file.Files
import local.ringleader.core.profileFile
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One monitor as the window sees it. [bounds] and [work] are in AWT user space (logical units: device pixels divided by
 * [scale]), the same space as the window's own bounds. [key] stays the same for a monitor across launches.
 */
data class DisplayInfo(
    val key: String,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val scale: Double,
    val refreshHz: Int,
    val bounds: Rectangle,
    val work: Rectangle,
) {
    val portrait get() = pixelHeight > pixelWidth
    /** "2560×1440 · 125 % · 60 Hz", the facts the Appearance dialog shows. */
    val summary get() = "$pixelWidth×$pixelHeight · ${(scale * 100).roundToInt()} %" + (if (refreshHz > 0) " · $refreshHz Hz" else "") + if (portrait) " · portrait" else ""
}

fun displayOf(gc: GraphicsConfiguration): DisplayInfo {
    val device = gc.device
    val mode = runCatching { device.displayMode }.getOrNull()
    val scale = gc.defaultTransform.scaleX.takeIf { it > 0 } ?: 1.0
    val b = gc.bounds
    val insets = runCatching { Toolkit.getDefaultToolkit().getScreenInsets(gc) }.getOrNull()
    val work = if (insets == null) Rectangle(b) else Rectangle(b.x + insets.left, b.y + insets.top,
        b.width - insets.left - insets.right, b.height - insets.top - insets.bottom)
    val pw = mode?.width?.takeIf { it > 0 } ?: (b.width * scale).roundToInt()
    val ph = mode?.height?.takeIf { it > 0 } ?: (b.height * scale).roundToInt()
    val hz = mode?.refreshRate?.takeIf { it > 0 } ?: 0
    return DisplayInfo(displayKey(device.iDstring, pw, ph), pw, ph, scale, hz, Rectangle(b), work)
}

/** A properties-safe key: the OS device id plus its resolution, so a re-cabled or re-ordered monitor gets fresh defaults. */
internal fun displayKey(id: String, width: Int, height: Int): String =
    "${id.replace(Regex("[^A-Za-z0-9]"), "").ifBlank { "display" }}-${width}x$height"

/**
 * Interface size a display gets when nobody chose one for it. The OS scale already accounts for screen size, so this
 * only steps in where that leaves a very large or very small logical desktop: a 4K screen left at 100 % (common
 * through remote-display adapters) gets 150 %, a 3K-class screen at 100 % gets 125 %, a small laptop panel gets 90 %.
 * Measured on the short side, so a portrait monitor is judged like the same monitor in landscape.
 */
fun autoInterfaceScale(pixelWidth: Int, pixelHeight: Int, osScale: Double): Float {
    val shortSide = min(pixelWidth, pixelHeight) / osScale.coerceAtLeast(.5)
    return when {
        shortSide >= 2000 -> 1.5f
        shortSide >= 1700 -> 1.25f
        shortSide < 800 -> .9f
        else -> 1f
    }
}

/**
 * Where the window should go on [display]: keeps [wanted]'s size when it fits, otherwise shrinks it to 94 % of the
 * work area, then moves it so it lies inside the work area (the title bar can never end up off screen).
 */
internal fun fitInto(wanted: Rectangle, display: Rectangle, minWidth: Int, minHeight: Int): Rectangle {
    val w = (if (wanted.width > display.width) (display.width * .94).roundToInt() else wanted.width).coerceIn(min(minWidth, display.width), display.width)
    val h = (if (wanted.height > display.height) (display.height * .94).roundToInt() else wanted.height).coerceIn(min(minHeight, display.height), display.height)
    val x = wanted.x.coerceIn(display.x, display.x + display.width - w)
    val y = wanted.y.coerceIn(display.y, display.y + display.height - h)
    return Rectangle(x, y, w, h)
}

/** First-launch size on a display: 1440×900 where it fits, otherwise 88 % of the work area, centred. */
internal fun defaultBounds(work: Rectangle): Rectangle {
    val w = min(1440, (work.width * .88).roundToInt()); val h = min(900, (work.height * .88).roundToInt())
    return Rectangle(work.x + (work.width - w) / 2, work.y + (work.height - h) / 2, w, h)
}

/** Window size per display and the last placement, in logical units, beside the profiles file. */
object WindowMemory {
    private val file get() = profileFile().resolveSibling("window.properties")
    private val props: Properties by lazy { ConfigSchemas.properties(file) }

    fun size(key: String): Pair<Int, Int>? = props.getProperty("size.$key")?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 && it.all { v -> v in 320..20000 } }?.let { it[0] to it[1] }
    fun last(): Rectangle? = props.getProperty("last")?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 4 && it[2] in 320..20000 && it[3] in 240..20000 }?.let { Rectangle(it[0], it[1], it[2], it[3]) }
    fun maximized(): Boolean = props.getProperty("maximized") == "true"

    @Synchronized fun remember(key: String, bounds: Rectangle) {
        val size = "${bounds.width},${bounds.height}"; val last = "${bounds.x},${bounds.y},${bounds.width},${bounds.height}"
        if (props.getProperty("size.$key") == size && props.getProperty("last") == last) return
        props.setProperty("size.$key", size); props.setProperty("last", last); save()
    }
    @Synchronized fun rememberMaximized(on: Boolean) { if (maximized() != on) { props.setProperty("maximized", on.toString()); save() } }

    private fun save() = runCatching {
        if (!ConfigSchemas.writable(file)) return@runCatching
        props.setProperty("schema", "1")
        Files.createDirectories(file.parent)
        val temp = Files.createTempFile(file.parent, "window-", ".tmp")
        try { Files.newBufferedWriter(temp).use { props.store(it, null) }; Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        finally { Files.deleteIfExists(temp) }
    }.onFailure { SaveStatus.report("Window placement", it) }
}

/** Smallest window: the 960 by 640 dp floor of the spec at the current interface size, never larger than the work area. */
internal fun minimumWindowSize(uiScale: Float, work: Rectangle): java.awt.Dimension {
    val scale = if (uiScale.isFinite() && uiScale > 0f) uiScale else 1f
    return java.awt.Dimension(min((960 * scale).roundToInt(), work.width), min((640 * scale).roundToInt(), work.height))
}

/** Screen edge a taskbar sits on. */
enum class TaskbarEdge { Left, Top, Right, Bottom }

/**
 * Where a maximised borderless window goes on a monitor with [screen] bounds and [insets] (both AWT user space): the
 * work area, so a visible taskbar stays visible. An auto-hide taskbar reserves no space, and a window that covers the
 * whole monitor counts as full screen, so the hidden taskbar could no longer slide out. With [autoHide] set and no inset
 * on that edge, one unit is left free there.
 */
internal fun maximizedBoundsFor(screen: Rectangle, insets: java.awt.Insets, autoHide: TaskbarEdge?): Rectangle {
    var left = insets.left; var top = insets.top; var right = insets.right; var bottom = insets.bottom
    when (autoHide) {
        TaskbarEdge.Left -> if (left == 0) left = 1
        TaskbarEdge.Top -> if (top == 0) top = 1
        TaskbarEdge.Right -> if (right == 0) right = 1
        TaskbarEdge.Bottom -> if (bottom == 0) bottom = 1
        null -> {}
    }
    return Rectangle(screen.x + left, screen.y + top, (screen.width - left - right).coerceAtLeast(1), (screen.height - top - bottom).coerceAtLeast(1))
}

/**
 * The Windows taskbar's auto-hide setting and edge, from `StuckRects3`: byte 8 bit 0 is auto-hide, byte 12 the edge
 * (0 left, 1 top, 2 right, 3 bottom). Read off the UI thread by a short-lived `reg query`, at most once a minute.
 */
object WindowsTaskbar {
    private val settings = Regex("""Settings\s+REG_BINARY\s+([0-9A-Fa-f]+)""")
    @Volatile var autoHideEdge: TaskbarEdge? = null
        private set
    private val lastRead = java.util.concurrent.atomic.AtomicLong(0L)

    /** The auto-hide edge, or null when the taskbar is always shown or the text does not hold the setting. */
    internal fun parse(output: String): TaskbarEdge? {
        val hex = settings.find(output)?.groupValues?.get(1) ?: return null
        if (hex.length < 26 || hex.length % 2 != 0) return null
        val flags = hex.substring(16, 18).toIntOrNull(16) ?: return null
        if (flags and 0x01 == 0) return null
        return when (hex.substring(24, 26).toIntOrNull(16)) { 0 -> TaskbarEdge.Left; 1 -> TaskbarEdge.Top; 2 -> TaskbarEdge.Right; else -> TaskbarEdge.Bottom }
    }

    /** Starts a read on a daemon thread unless one ran in the last minute; [then] runs on the AWT thread if the answer changed. */
    fun refresh(then: () -> Unit = {}) {
        val now = System.currentTimeMillis()
        val last = lastRead.get()
        if ((last != 0L && now - last < 60_000L) || !lastRead.compareAndSet(last, now)) return
        Thread({
            val edge = runCatching {
                val reg = System.getenv("SystemRoot")?.let { java.io.File(it, "System32/reg.exe") }?.takeIf { it.isFile }?.path ?: "reg"
                val process = ProcessBuilder(reg, "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StuckRects3", "/v", "Settings")
                    .redirectErrorStream(true).start()
                process.outputStream.close()
                if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); null }
                else parse(process.inputStream.bufferedReader().use { it.readText() })
            }.getOrNull()
            val changed = edge != autoHideEdge
            autoHideEdge = edge
            if (changed) javax.swing.SwingUtilities.invokeLater(then)
        }, "taskbar-settings").apply { isDaemon = true }.start()
    }
}

private val onWindows = System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")

/**
 * Windows only: a borderless window maximises over the whole monitor, taskbar included, unless the frame is told the
 * maximised bounds. Sets them for the monitor the window is on now; call it again whenever that monitor may have changed.
 * Linux window managers maximise into their own work area, so nothing is set there.
 */
fun applyMaximizedBounds(window: Window) {
    if (!onWindows) return
    val frame = window as? java.awt.Frame ?: return
    val gc = window.graphicsConfiguration ?: return
    val insets = runCatching { Toolkit.getDefaultToolkit().getScreenInsets(gc) }.getOrNull() ?: java.awt.Insets(0, 0, 0, 0)
    val wanted = maximizedBoundsFor(gc.bounds, insets, WindowsTaskbar.autoHideEdge)
    if (frame.maximizedBounds != wanted) frame.maximizedBounds = wanted
}

/**
 * Keeps the main window right for whatever monitor it lands on, however it got there (title-bar drag, Win+Shift+Arrow,
 * Snap, a monitor unplugged). Once a move settles: a window that changed monitor takes the size it last had on that
 * monitor, or is fitted to it; any window is kept inside the work area. [onDisplay] reports the monitor so the interface
 * size can follow it. Fitting acts only on a floating window; maximised and minimised windows belong to the OS. The
 * minimum size follows the interface size, and the maximised bounds follow the monitor.
 */
@Composable fun InstallDisplayFit(window: Window, state: WindowState, onDisplay: (DisplayInfo) -> Unit) {
    val scale = Appearance.uiScale
    val settleNow = androidx.compose.runtime.remember(window) { arrayOfNulls<() -> Unit>(1) }
    DisposableEffect(window) {
        var lastKey: String? = null
        // A window that is maximised outside its maximised bounds (it came up maximised before they were set, or its
        // monitor changed) is maximised again, once for each new set of bounds so it can never loop.
        var remaximizedFor: Rectangle? = null
        fun settle() {
            val display = displayOf(window.graphicsConfiguration)
            window.minimumSize = minimumWindowSize(Appearance.uiScale, display.work)
            applyMaximizedBounds(window)
            if (lastKey != display.key) {
                local.ringleader.core.Trace.emit(local.ringleader.core.TraceSource.App, "display", "Display changed", mapOf("display" to display.summary))
                onDisplay(display)
            }
            val frame = window as? java.awt.Frame
            val maxBounds = frame?.maximizedBounds
            if (onWindows && frame != null && maxBounds != null && state.placement == WindowPlacement.Maximized && !state.isMinimized && remaximizedFor != maxBounds &&
                !Rectangle(maxBounds.x - 2, maxBounds.y - 2, maxBounds.width + 4, maxBounds.height + 4).contains(window.bounds)) {
                remaximizedFor = Rectangle(maxBounds)
                frame.extendedState = java.awt.Frame.NORMAL
                frame.extendedState = java.awt.Frame.MAXIMIZED_BOTH
            }
            if (state.placement != WindowPlacement.Floating || state.isMinimized) { lastKey = display.key; return }
            val current = window.bounds
            val wanted = if (lastKey != null && lastKey != display.key) {
                WindowMemory.size(display.key)?.let { (w, h) -> Rectangle(current.x, current.y, w, h) } ?: current
            } else current
            val fitted = fitInto(wanted, display.work, window.minimumSize.width, window.minimumSize.height)
            lastKey = display.key
            if (fitted != current) window.bounds = fitted
            WindowMemory.remember(display.key, fitted)
        }
        settleNow[0] = ::settle
        val timer = javax.swing.Timer(450) { settle() }.apply { isRepeats = false }
        val moves = object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = timer.restart()
            override fun componentResized(e: ComponentEvent) = timer.restart()
        }
        // A new monitor gets its maximised bounds at once, so a maximise right after the move uses the right work area.
        val monitor = java.beans.PropertyChangeListener { applyMaximizedBounds(window); timer.restart() }
        val focus = object : java.awt.event.WindowAdapter() {
            override fun windowGainedFocus(e: java.awt.event.WindowEvent) { if (onWindows) WindowsTaskbar.refresh { applyMaximizedBounds(window) } }
        }
        window.addComponentListener(moves)
        window.addPropertyChangeListener("graphicsConfiguration", monitor)
        window.addWindowFocusListener(focus)
        if (onWindows) WindowsTaskbar.refresh { applyMaximizedBounds(window) }
        javax.swing.SwingUtilities.invokeLater { settle() }
        onDispose {
            settleNow[0] = null
            timer.stop()
            window.removeComponentListener(moves)
            window.removePropertyChangeListener("graphicsConfiguration", monitor)
            window.removeWindowFocusListener(focus)
        }
    }
    // A new interface size moves the minimum at once (and grows a window that is now below it).
    androidx.compose.runtime.LaunchedEffect(scale) { settleNow[0]?.invoke() }
}

/** Starting bounds for the main window: the last placement when it is still on a connected display, else the default. */
fun initialWindowBounds(): Rectangle {
    val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
    val displays = runCatching { ge.screenDevices.map { displayOf(it.defaultConfiguration) } }.getOrDefault(emptyList())
    val primary = runCatching { displayOf(ge.defaultScreenDevice.defaultConfiguration) }.getOrNull()
    val last = WindowMemory.last()
    if (last != null) {
        val home = displays.maxByOrNull { d -> d.work.intersection(last).let { if (it.isEmpty) 0 else it.width * it.height } }
        if (home != null && home.work.intersects(last)) return fitInto(last, home.work, min(960, home.work.width), min(640, home.work.height))
    }
    return primary?.let { defaultBounds(it.work) } ?: Rectangle(80, 80, 1440, 900)
}
