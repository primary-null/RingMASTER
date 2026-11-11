package local.ringleader.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceViewTest {
    @Test fun traceViewFollowsWeb() {
        assertFalse(AppHost(true).traceView)
        assertTrue(DesktopAppHost().traceView)
    }

    @Test fun pagesForHidesTheHoodInTheBrowser() {
        assertEquals(ringPages, pagesFor(DesktopAppHost()))
        assertFalse("Under the Hood" in pagesFor(AppHost(true)))
    }

    @Test fun ninthShortcutIsNullInTheBrowserAndTheHoodOnDesktop() {
        assertNull(pagesFor(AppHost(true)).getOrNull(8))
        assertEquals("Under the Hood", pagesFor(DesktopAppHost()).getOrNull(8))
    }
}
