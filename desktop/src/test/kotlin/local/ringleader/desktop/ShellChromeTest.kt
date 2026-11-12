package local.ringleader.desktop

import androidx.compose.ui.geometry.Offset
import java.awt.Insets
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The window shell: one resizer, maximise inside the work area, the minimum size, and the page slide. */
class ShellChromeTest {
    private fun zonesAt(zones: List<ResizeZone>, x: Float, y: Float) = zones.filter { it.area.contains(Offset(x, y)) }.map { it.edges }

    @Test fun theTopOfTheTitleBarDragsAndOnlyItsCornersResize() {
        val zones = resizeZones(1440f, 900f, band = 6f, corner = 14f, titleBar = 32f)
        // Anywhere along the top edge between the corners is title bar, not a resize band.
        for (x in listOf(7f, 100f, 720f, 1300f, 1400f, 1433f)) assertEquals(emptyList(), zonesAt(zones, x, 1f), "top edge at $x")
        assertEquals(listOf(ResizeEdge.Top or ResizeEdge.Left), zonesAt(zones, 2f, 2f))
        assertEquals(listOf(ResizeEdge.Top or ResizeEdge.Right), zonesAt(zones, 1438f, 2f))
        // The close button (right end of the title bar) stays whole below the corner.
        assertEquals(emptyList(), zonesAt(zones, 1437f, 20f))
        assertEquals(listOf(ResizeEdge.Right), zonesAt(zones, 1437f, 40f))
        assertEquals(listOf(ResizeEdge.Left), zonesAt(zones, 2f, 400f))
        assertEquals(listOf(ResizeEdge.Bottom), zonesAt(zones, 720f, 897f))
        assertEquals(listOf(ResizeEdge.Bottom or ResizeEdge.Right), zonesAt(zones, 1430f, 890f))
        assertEquals(listOf(ResizeEdge.Bottom or ResizeEdge.Left), zonesAt(zones, 10f, 890f))
        // The middle of the window is never a resize area, and no two areas overlap.
        assertEquals(emptyList(), zonesAt(zones, 720f, 450f))
        for (a in zones) for (b in zones) if (a !== b) assertFalse(a.area.overlaps(b.area), "${a.edges} overlaps ${b.edges}")
        assertTrue(resizeZones(0f, 0f, 6f, 14f, 32f).isEmpty())
    }

    @Test fun aResizeMovesOnlyTheDraggedEdgesAndStopsAtTheMinimum() {
        val start = Rectangle(100, 100, 1200, 800)
        assertEquals(Rectangle(100, 100, 1250, 800), resizedBounds(ResizeEdge.Right, start, 50, 30, 960, 640))
        assertEquals(Rectangle(150, 100, 1150, 800), resizedBounds(ResizeEdge.Left, start, 50, 30, 960, 640))
        assertEquals(Rectangle(80, 70, 1220, 830), resizedBounds(ResizeEdge.Top or ResizeEdge.Left, start, -20, -30, 960, 640))
        assertEquals(Rectangle(100, 100, 1200, 840), resizedBounds(ResizeEdge.Bottom, start, 99, 40, 960, 640))
        // Dragging the left edge far to the right stops at the minimum width; the right edge stays where it was.
        val clamped = resizedBounds(ResizeEdge.Left, start, 900, 0, 960, 640)
        assertEquals(960, clamped.width); assertEquals(start.x + start.width, clamped.x + clamped.width)
        val short = resizedBounds(ResizeEdge.Top, start, 0, 500, 960, 640)
        assertEquals(640, short.height); assertEquals(start.y + start.height, short.y + short.height)
    }

    @Test fun maximisingKeepsTheTaskbarVisible() {
        // 1920x1080 at the origin with a 48-unit taskbar at the bottom.
        assertEquals(Rectangle(0, 0, 1920, 1032), maximizedBoundsFor(Rectangle(0, 0, 1920, 1080), Insets(0, 0, 48, 0), null))
        // A second monitor to the left, taskbar on its left edge: absolute coordinates on that monitor.
        assertEquals(Rectangle(-2496, 0, 2496, 1440), maximizedBoundsFor(Rectangle(-2560, 0, 2560, 1440), Insets(0, 64, 0, 0), null))
        // An auto-hide taskbar reserves nothing; one unit stays free on its edge so it can still slide out.
        assertEquals(Rectangle(0, 0, 1920, 1079), maximizedBoundsFor(Rectangle(0, 0, 1920, 1080), Insets(0, 0, 0, 0), TaskbarEdge.Bottom))
        assertEquals(Rectangle(1, 0, 1919, 1080), maximizedBoundsFor(Rectangle(0, 0, 1920, 1080), Insets(0, 0, 0, 0), TaskbarEdge.Left))
        // A monitor that does reserve the edge is left as the work area.
        assertEquals(Rectangle(0, 0, 1920, 1032), maximizedBoundsFor(Rectangle(0, 0, 1920, 1080), Insets(0, 0, 48, 0), TaskbarEdge.Bottom))
    }

    @Test fun theTaskbarSettingIsReadFromStuckRects() {
        val shown = "\r\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StuckRects3\r\n    Settings    REG_BINARY    30000000FEFFFFFF02000000030000003E00000030000000\r\n"
        val hidden = shown.replace("FEFFFFFF02000000030000", "FEFFFFFF03000000030000")
        val hiddenTop = shown.replace("FEFFFFFF02000000030000", "FEFFFFFF03000000010000")
        assertNull(WindowsTaskbar.parse(shown))
        assertEquals(TaskbarEdge.Bottom, WindowsTaskbar.parse(hidden))
        assertEquals(TaskbarEdge.Top, WindowsTaskbar.parse(hiddenTop))
        assertNull(WindowsTaskbar.parse("ERROR: The system was unable to find the specified registry key or value."))
        assertNull(WindowsTaskbar.parse("Settings    REG_BINARY    3000"))
    }

    @Test fun theMinimumWindowFollowsTheInterfaceSize() {
        val work = Rectangle(0, 0, 2560, 1400)
        assertEquals(java.awt.Dimension(960, 640), minimumWindowSize(1f, work))
        assertEquals(java.awt.Dimension(1440, 960), minimumWindowSize(1.5f, work))
        assertEquals(java.awt.Dimension(864, 576), minimumWindowSize(.9f, work))
        // Never larger than the work area: at 200 % on a small screen, maximise is the way to fit.
        assertEquals(java.awt.Dimension(1366, 728), minimumWindowSize(2f, Rectangle(0, 0, 1366, 728)))
        assertEquals(java.awt.Dimension(960, 640), minimumWindowSize(Float.NaN, work))
    }

    @Test fun thePageSlideIs12dpOnFullAndNothingOnLiteOrUnderReduceMotion() {
        assertEquals(12f, pageSlideDp(false, RmMotion(MotionTier.Full)))
        assertEquals(0f, pageSlideDp(false, RmMotion(MotionTier.Lite)))
        assertEquals(0f, pageSlideDp(false, RmMotion(MotionTier.Reduced)))
        assertEquals(0f, pageSlideDp(true, RmMotion(MotionTier.Full)))
    }
}
