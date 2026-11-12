package local.ringleader.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class HoodClearTest {
    @Test fun clearNoticeSaysWhatWasRemovedAndKept() {
        assertEquals("Cleared 0 operations.", clearNotice(0, 0))
        assertEquals("Cleared 1 operation.", clearNotice(1, 0))
        assertEquals("Cleared 12 operations. Kept 2 still running.", clearNotice(12, 2))
    }
}
