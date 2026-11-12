package local.ringleader.desktop

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Window

/** Edges a resize grab moves, as bit flags. */
internal object ResizeEdge {
    const val Left = 1
    const val Right = 2
    const val Top = 4
    const val Bottom = 8
}

/** One grab area of the window frame: which edges it moves, and where it sits (window coordinates, dp). */
internal data class ResizeZone(val edges: Int, val area: Rect)

/**
 * The grab areas of a floating window of [width] by [height]. The title bar keeps its whole top edge for dragging the
 * window: there is no top band, only the two top corners ([band] square). The right band starts below [titleBar] so the
 * close button stays whole. Left, right and bottom bands are [band] wide; the bottom corners are [corner] square.
 */
internal fun resizeZones(width: Float, height: Float, band: Float, corner: Float, titleBar: Float): List<ResizeZone> {
    if (width <= 0f || height <= 0f) return emptyList()
    val bottomCorner = corner.coerceAtMost(height / 2)
    val zones = listOf(
        ResizeZone(ResizeEdge.Left, Rect(0f, band, band, height - bottomCorner)),
        ResizeZone(ResizeEdge.Right, Rect(width - band, titleBar.coerceAtLeast(band), width, height - bottomCorner)),
        ResizeZone(ResizeEdge.Bottom, Rect(bottomCorner, height - band, width - bottomCorner, height)),
        ResizeZone(ResizeEdge.Top or ResizeEdge.Left, Rect(0f, 0f, band, band)),
        ResizeZone(ResizeEdge.Top or ResizeEdge.Right, Rect(width - band, 0f, width, band)),
        ResizeZone(ResizeEdge.Bottom or ResizeEdge.Left, Rect(0f, height - bottomCorner, bottomCorner, height)),
        ResizeZone(ResizeEdge.Bottom or ResizeEdge.Right, Rect(width - bottomCorner, height - bottomCorner, width, height)),
    )
    return zones.filter { it.area.width > 0f && it.area.height > 0f }
}

/**
 * Window bounds after dragging [edges] by ([dx], [dy]) from [start]. The opposite edge stays where it was, and the size
 * never goes below [minWidth] by [minHeight].
 */
internal fun resizedBounds(edges: Int, start: Rectangle, dx: Int, dy: Int, minWidth: Int, minHeight: Int): Rectangle {
    val left = edges and ResizeEdge.Left != 0; val right = edges and ResizeEdge.Right != 0
    val top = edges and ResizeEdge.Top != 0; val bottom = edges and ResizeEdge.Bottom != 0
    val w = (start.width + if (left) -dx else if (right) dx else 0).coerceAtLeast(minWidth)
    val h = (start.height + if (top) -dy else if (bottom) dy else 0).coerceAtLeast(minHeight)
    return Rectangle(if (left) start.x + start.width - w else start.x, if (top) start.y + start.height - h else start.y, w, h)
}

private fun cursorFor(edges: Int): Int = when (edges) {
    ResizeEdge.Left -> Cursor.W_RESIZE_CURSOR
    ResizeEdge.Right -> Cursor.E_RESIZE_CURSOR
    ResizeEdge.Bottom -> Cursor.S_RESIZE_CURSOR
    ResizeEdge.Top -> Cursor.N_RESIZE_CURSOR
    ResizeEdge.Top or ResizeEdge.Left -> Cursor.NW_RESIZE_CURSOR
    ResizeEdge.Top or ResizeEdge.Right -> Cursor.NE_RESIZE_CURSOR
    ResizeEdge.Bottom or ResizeEdge.Left -> Cursor.SW_RESIZE_CURSOR
    else -> Cursor.SE_RESIZE_CURSOR
}

/**
 * The window's only resizer (Compose's own undecorated resizer is switched off with a zero thickness in Main.kt).
 * Thin grab areas sit above the content, so a press on them never reaches the title bar drag or a rail row underneath.
 * Compose it only while the window floats: a maximised or full-screen window has no resize edges.
 * Positions come from the screen pointer, not the event, because the window moves under the pointer while it is resized.
 */
@Composable fun WindowResizeFrame(window: Window, titleBar: Dp, band: Dp = 6.dp, corner: Dp = 14.dp) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        resizeZones(maxWidth.value, maxHeight.value, band.value, corner.value, titleBar.value).forEach { zone ->
            Box(Modifier.offset(zone.area.left.dp, zone.area.top.dp).size(zone.area.width.dp, zone.area.height.dp)
                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(cursorFor(zone.edges))))
                .pointerInput(zone.edges, window) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                        down.consume()
                        val origin = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
                        val start = window.bounds
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                            val at = MouseInfo.getPointerInfo()?.location ?: continue
                            val next = resizedBounds(zone.edges, start, at.x - origin.x, at.y - origin.y, window.minimumSize.width, window.minimumSize.height)
                            if (next != window.bounds) window.bounds = next
                        }
                    }
                })
        }
    }
}
