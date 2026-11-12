package local.ringleader.desktop

import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntRect
import kotlin.math.roundToInt

/** Windows hit-test codes this frame answers. The values are the Win32 HT* constants. */
internal const val HTCLIENT = 1
internal const val HTCAPTION = 2
internal const val HTMAXBUTTON = 9
internal const val HTLEFT = 10
internal const val HTRIGHT = 11
internal const val HTTOP = 12
internal const val HTTOPLEFT = 13
internal const val HTTOPRIGHT = 14
internal const val HTBOTTOM = 15
internal const val HTBOTTOMLEFT = 16
internal const val HTBOTTOMRIGHT = 17

/**
 * What Compose reports about the title bar, in window pixels (physical pixels, origin at the window's top-left).
 * [titleBar] is null while the title bar is hidden. [otherControls] are the minimise and close buttons, and anything
 * else in the title bar that Compose itself must click.
 */
internal data class FrameRegions(
    val width: Int,
    val height: Int,
    val titleBar: IntRect?,
    val maximizeButton: IntRect?,
    val otherControls: List<IntRect>,
    val resizeBandPx: Int,
    val resizeCornerPx: Int,
    val titleBarHeightPx: Int,
)

internal enum class FrameMode { Floating, Maximized, Fullscreen }

/**
 * Whether this process should install the native frame. The property and the environment variable are each a kill
 * switch. Anything that is not 64-bit Windows keeps the Compose drag and resize path.
 */
internal fun nativeFrameRequested(
    osName: String = System.getProperty("os.name").orEmpty(),
    property: String? = System.getProperty("ringmaster.nativeFrame"),
    environment: String? = System.getenv("RINGMASTER_NATIVE_FRAME"),
    pointerBytes: Int = com.sun.jna.Native.POINTER_SIZE,
): Boolean {
    if (!osName.startsWith("Windows", ignoreCase = true)) return false
    if (pointerBytes != 8) return false
    if (property == "false") return false
    if (environment == "0") return false
    return true
}

/** Screen point packed into a WM_NCHITTEST lParam: two signed 16-bit values, x in the low half. */
internal fun packHitPoint(x: Int, y: Int): Long {
    val lx = x.toShort().toLong() and 0xFFFF
    val ly = y.toShort().toLong() and 0xFFFF
    return (ly shl 16) or lx
}

/** The signed screen point in a WM_NCHITTEST lParam. High bits outside the low 32 are ignored. */
internal fun unpackHitPoint(lParam: Long): Pair<Int, Int> {
    val x = (lParam and 0xFFFF).toInt().toShort().toInt()
    val y = ((lParam ushr 16) and 0xFFFF).toInt().toShort().toInt()
    return x to y
}

/**
 * One logical pixel, in physical pixels, at [dpi] (96 is 100%). An auto-hide taskbar keeps this much of its edge free.
 * Matches [maximizedBoundsFor], which leaves one unit of AWT user space.
 */
internal fun autoHideStripPx(dpi: Int): Int {
    val safe = if (dpi > 0) dpi else 96
    return (safe + 95) / 96
}

/**
 * Where a maximised window should sit, in physical pixels. [work] already excludes a visible taskbar. An auto-hide
 * taskbar is not in [work]; one strip is left on that edge when the work edge is the monitor edge.
 */
internal data class PixelBox(val x: Int, val y: Int, val width: Int, val height: Int)

internal fun maximizedWorkBox(
    monitorLeft: Int, monitorTop: Int, monitorRight: Int, monitorBottom: Int,
    workLeft: Int, workTop: Int, workRight: Int, workBottom: Int,
    autoHide: TaskbarEdge?, strip: Int,
): PixelBox {
    var left = workLeft
    var top = workTop
    var right = workRight
    var bottom = workBottom
    val gap = strip.coerceAtLeast(1)
    when (autoHide) {
        TaskbarEdge.Left -> if (workLeft == monitorLeft) left += gap
        TaskbarEdge.Top -> if (workTop == monitorTop) top += gap
        TaskbarEdge.Right -> if (workRight == monitorRight) right -= gap
        TaskbarEdge.Bottom -> if (workBottom == monitorBottom) bottom -= gap
        null -> {}
    }
    return PixelBox(left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
}

internal fun LayoutCoordinates.frameRect(): IntRect {
    val at = positionInWindow()
    val x = at.x.roundToInt()
    val y = at.y.roundToInt()
    return IntRect(x, y, x + size.width, y + size.height)
}

/**
 * The Windows hit-test code for a point of the window.
 *
 * While floating, a top band [FrameRegions.resizeBandPx] tall sits between the two top corners and answers [HTTOP].
 * That is the "drag the top edge to the screen top" snap. The band is only those few pixels, so it covers the top
 * sliver of the caption buttons and nothing below that. The corners stay the same size as [resizeZones].
 */
internal fun frameHitTest(x: Int, y: Int, regions: FrameRegions, mode: FrameMode): Int {
    if (mode == FrameMode.Fullscreen) return HTCLIENT
    if (regions.width <= 0 || regions.height <= 0 || x < 0 || y < 0 || x >= regions.width || y >= regions.height) return HTCLIENT
    if (mode == FrameMode.Floating) {
        val edge = resizeHit(x, y, regions)
        if (edge != null) return edge
    }
    val maximize = regions.maximizeButton
    if (maximize != null && contains(maximize, x, y)) return HTMAXBUTTON
    if (regions.otherControls.any { contains(it, x, y) }) return HTCLIENT
    val title = regions.titleBar
    if (title != null && contains(title, x, y)) return HTCAPTION
    return HTCLIENT
}

private fun contains(rect: IntRect, x: Int, y: Int): Boolean =
    x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom

private fun resizeHit(x: Int, y: Int, regions: FrameRegions): Int? {
    val zones = resizeZones(
        regions.width.toFloat(), regions.height.toFloat(),
        regions.resizeBandPx.toFloat(), regions.resizeCornerPx.toFloat(), regions.titleBarHeightPx.toFloat(),
    )
    for (zone in zones) {
        val area = zone.area
        if (x >= area.left && x < area.right && y >= area.top && y < area.bottom) return hitCode(zone.edges)
    }
    val band = regions.resizeBandPx
    if (band > 0 && y in 0 until band && x in band until (regions.width - band)) return HTTOP
    return null
}

private fun hitCode(edges: Int): Int = when (edges) {
    ResizeEdge.Left -> HTLEFT
    ResizeEdge.Right -> HTRIGHT
    ResizeEdge.Top -> HTTOP
    ResizeEdge.Bottom -> HTBOTTOM
    ResizeEdge.Top or ResizeEdge.Left -> HTTOPLEFT
    ResizeEdge.Top or ResizeEdge.Right -> HTTOPRIGHT
    ResizeEdge.Bottom or ResizeEdge.Left -> HTBOTTOMLEFT
    ResizeEdge.Bottom or ResizeEdge.Right -> HTBOTTOMRIGHT
    else -> HTCLIENT
}
