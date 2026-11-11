package local.ringleader.desktop

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ActivityClockTest {
    @Test fun extremeOrMissingTimestampShowsPlaceholder() {
        assertEquals("Unavailable", activityClock(Instant.ofEpochSecond(31556889864403199)))
        assertEquals("Unavailable", activityClock(null))
        assertNotEquals("Unavailable", activityClock(Instant.parse("2026-11-07T12:00:00Z")))
    }
}
