package local.ringleader.desktop

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplayFitTest {
    @Test fun autoScaleFollowsTheLogicalDesktop() {
        assertEquals(1f, autoInterfaceScale(2560, 1440, 1.25))   // 1440p at 125 %
        assertEquals(1f, autoInterfaceScale(1080, 1920, 1.0))    // portrait 1080p
        assertEquals(1f, autoInterfaceScale(3840, 2160, 1.5))    // 4K at 150 %: the OS already scaled it
        assertEquals(1.5f, autoInterfaceScale(3840, 2160, 1.0))  // 4K left at 100 %
        assertEquals(1.25f, autoInterfaceScale(2880, 1800, 1.0)) // 3K-class at 100 %
        assertEquals(1.5f, autoInterfaceScale(2160, 3840, 1.0))  // portrait 4K judged like landscape
        assertEquals(.9f, autoInterfaceScale(1366, 768, 1.0))    // small panel
    }

    @Test fun aWindowTooBigForTheDisplayIsShrunkAndKeptInside() {
        val portrait = Rectangle(2560, 0, 1080, 1872)
        val fitted = fitInto(Rectangle(2560, 333, 1440, 900), portrait, 960, 640)
        assertEquals(1015, fitted.width)
        assertEquals(900, fitted.height)
        assertTrue(portrait.contains(fitted))
    }

    @Test fun aTitleBarAboveTheScreenComesBack() {
        val work = Rectangle(-2048, 0, 2048, 1104)
        val fitted = fitInto(Rectangle(-2061, -30, 2048, 898), work, 960, 640)
        assertEquals(0, fitted.y)
        assertTrue(work.contains(fitted))
    }

    @Test fun aWindowThatFitsKeepsItsSizeAndPlace() {
        val work = Rectangle(0, 0, 2048, 1104)
        assertEquals(Rectangle(100, 80, 1440, 900), fitInto(Rectangle(100, 80, 1440, 900), work, 960, 640))
    }

    @Test fun firstLaunchFitsSmallDisplays() {
        assertEquals(Rectangle(304, 102, 1440, 900), defaultBounds(Rectangle(0, 0, 2048, 1104)))
        val small = defaultBounds(Rectangle(0, 0, 1280, 680))
        assertEquals(1126, small.width); assertEquals(598, small.height)
    }

    @Test fun displayKeysAreSafeAndStable() {
        assertEquals("Display0-2560x1440", displayKey("\\Display0", 2560, 1440))
        assertEquals("display-1080x1920", displayKey("::", 1080, 1920))
    }
}
