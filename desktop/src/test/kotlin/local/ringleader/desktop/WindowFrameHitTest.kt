package local.ringleader.desktop

import androidx.compose.ui.unit.IntRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Hit testing for the native frame, with no window and no JNA. */
class WindowFrameHitTest {
    private val regions = FrameRegions(
        width = 1200, height = 800,
        titleBar = IntRect(0, 0, 1200, 32),
        maximizeButton = IntRect(1080, 0, 1120, 32),
        otherControls = listOf(IntRect(1040, 0, 1080, 32), IntRect(1120, 0, 1160, 32)),
        resizeBandPx = 6, resizeCornerPx = 14, titleBarHeightPx = 32,
    )

    private fun hit(x: Int, y: Int, mode: FrameMode = FrameMode.Floating, sample: FrameRegions = regions) =
        frameHitTest(x, y, sample, mode)

    @Test fun theTitleBarIsACaptionAndTheButtonsStayTheirs() {
        assertEquals(HTCAPTION, hit(600, 16))
        assertEquals(HTMAXBUTTON, hit(1100, 16))
        assertEquals(HTCLIENT, hit(1060, 16))
        assertEquals(HTCLIENT, hit(1140, 16))
        // The top band is one resize band tall, so only that sliver of the maximise button is a resize.
        assertEquals(HTTOP, hit(1100, 2))
        assertEquals(HTMAXBUTTON, hit(1100, 8))
    }

    @Test fun eachEdgeAndCornerResizesWhileFloating() {
        assertEquals(HTLEFT, hit(2, 400))
        assertEquals(HTRIGHT, hit(1197, 400))
        assertEquals(HTBOTTOM, hit(600, 797))
        assertEquals(HTTOP, hit(600, 2))
        assertEquals(HTTOPLEFT, hit(2, 2))
        assertEquals(HTTOPRIGHT, hit(1197, 2))
        assertEquals(HTBOTTOMLEFT, hit(2, 790))
        assertEquals(HTBOTTOMRIGHT, hit(1190, 790))
        assertEquals(HTCLIENT, hit(600, 400))
    }

    @Test fun aMaximisedWindowHasNoResizeEdges() {
        assertEquals(HTCAPTION, hit(600, 16, FrameMode.Maximized))
        assertEquals(HTCAPTION, hit(600, 2, FrameMode.Maximized))
        assertEquals(HTCAPTION, hit(2, 2, FrameMode.Maximized))
        assertEquals(HTMAXBUTTON, hit(1100, 16, FrameMode.Maximized))
        assertEquals(HTCLIENT, hit(2, 400, FrameMode.Maximized))
        assertEquals(HTCLIENT, hit(1197, 400, FrameMode.Maximized))
        assertEquals(HTCLIENT, hit(600, 797, FrameMode.Maximized))
        assertEquals(HTCLIENT, hit(2, 790, FrameMode.Maximized))
        assertEquals(HTCLIENT, hit(1190, 790, FrameMode.Maximized))
    }

    @Test fun fullScreenAndAHiddenTitleBarAreClient() {
        for (point in listOf(600 to 16, 1100 to 16, 2 to 2, 600 to 2, 2 to 400, 600 to 797, -4 to 10, 1300 to 10)) {
            assertEquals(HTCLIENT, hit(point.first, point.second, FrameMode.Fullscreen), "$point")
        }
        val hidden = regions.copy(titleBar = null, maximizeButton = null, otherControls = emptyList(), titleBarHeightPx = 0)
        assertEquals(HTCLIENT, hit(600, 16, sample = hidden))
        assertEquals(HTLEFT, hit(2, 400, sample = hidden))
    }

    @Test fun aPointOutsideTheWindowIsClient() {
        assertEquals(HTCLIENT, hit(-1, 10))
        assertEquals(HTCLIENT, hit(1200, 10))
        assertEquals(HTCLIENT, hit(10, 800))
        assertEquals(HTCLIENT, hit(10, -1))
    }

    @Test fun hitPointKeepsTheSignOfEachHalf() {
        assertEquals(-320 to -1080, unpackHitPoint(packHitPoint(-320, -1080)))
        assertEquals(100 to 200, unpackHitPoint(packHitPoint(100, 200)))
        assertEquals(-1 to -1, unpackHitPoint(packHitPoint(-1, -1)))
        // A negative y sign-extends the whole lParam on 64-bit. The low 32 bits still decode.
        val packed = packHitPoint(-400, -20)
        assertEquals(-400 to -20, unpackHitPoint(packed or -0x1_0000_0000L))
    }

    @Test fun theKillSwitchLeavesTheComposeFrame() {
        assertFalse(nativeFrameRequested(osName = "Windows 11", property = "false", environment = null, pointerBytes = 8))
        assertFalse(nativeFrameRequested(osName = "Windows 11", property = null, environment = "0", pointerBytes = 8))
        assertFalse(nativeFrameRequested(osName = "Windows 11", property = "true", environment = "0", pointerBytes = 8))
        assertFalse(nativeFrameRequested(osName = "Linux", property = null, environment = null, pointerBytes = 8))
        assertFalse(nativeFrameRequested(osName = "Windows 10", property = null, environment = null, pointerBytes = 4))
        assertTrue(nativeFrameRequested(osName = "Windows 11", property = null, environment = null, pointerBytes = 8))
        assertTrue(nativeFrameRequested(osName = "Windows 11", property = "true", environment = null, pointerBytes = 8))
    }

    @Test fun theAutoHideStripUsesTheWorkArea() {
        assertEquals(1, autoHideStripPx(96))
        assertEquals(2, autoHideStripPx(144))
        assertEquals(2, autoHideStripPx(120))
        val monitor = PixelBox(0, 0, 1920, 1080)
        val work = maximizedWorkBox(0, 0, 1920, 1080, 0, 0, 1920, 1032, null, 1)
        assertEquals(PixelBox(0, 0, 1920, 1032), work)
        val hidden = maximizedWorkBox(0, 0, 1920, 1080, 0, 0, 1920, 1080, TaskbarEdge.Bottom, 1)
        assertEquals(PixelBox(0, 0, 1920, 1079), hidden)
        val left = maximizedWorkBox(-2560, 0, 0, 1440, -2560, 0, 0, 1440, TaskbarEdge.Left, 2)
        assertEquals(PixelBox(-2558, 0, 2558, 1440), left)
        assertEquals(monitor.x, 0)
    }
}
