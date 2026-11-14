package local.ringleader.desktop

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import local.ringleader.core.TaskOutcome

class ActivityElapsedTest {
    private val start = Instant.ofEpochSecond(1_700_000_000)

    @Test fun clientClockBehindTheNodeIsUnavailableNotZero() {
        assertEquals("Unavailable", activityElapsed(start, null, start.minusSeconds(30)))
        assertEquals("Unavailable", activityElapsed(start, start.minusSeconds(1), start))
        assertEquals("0s", activityElapsed(start, null, start))
        assertEquals("1m 5s", activityElapsed(start, start.plusSeconds(65), start.plusSeconds(999)))
        assertEquals("—", activityElapsed(null, null, start))
    }

    @Test fun okCounterIsNotGreenWhenWarningsArePresent() {
        assertEquals(Tech.Green, counterColor(TaskOutcome.Succeeded, 0))
        assertEquals(Tech.Amber, counterColor(TaskOutcome.Succeeded, 1))
        assertEquals(Tech.Red, counterColor(TaskOutcome.Unsuccessful, 1))
    }

    @Test fun submissionsBannerNoLongerSaysCheckReadsOnce() {
        assertFalse(SUBMISSIONS_EXPLANATION.contains("reads once"))
        assertTrue(SUBMISSIONS_EXPLANATION.contains("until it stops"))
    }
}

class GuestPageGuardTest {
    @Test fun pdmGuestHasNoPageOnEitherOpenPath() {
        val direct = local.ringleader.core.Guest("https://example.test:8006", 100, "alpha", "node-a", "VM", "running")
        val scoped = direct.copy(scope = "remote-a")
        assertTrue(guestHasPage(local.ringleader.core.WorkspaceState(connected = true), direct))
        assertFalse(guestHasPage(local.ringleader.core.WorkspaceState(connected = true, kind = local.ringleader.core.PDM_KIND), direct))
        assertFalse(guestHasPage(local.ringleader.core.WorkspaceState(connected = true), scoped))
    }
}
