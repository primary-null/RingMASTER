package local.ringleader.desktop

import local.ringleader.core.DEMO_REMOTE_KEY
import local.ringleader.core.Guest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuestMarksTest {
    @Test fun startedOnARunningGuestIsTheGreenHaChip() {
        val plan = guestChipPlan(guest(status = "running", haState = "started", tags = listOf("web", "lab")), 2)
        assertEquals(listOf("HA"), plan.marks.map { it.label })
        assertEquals(GuestMarkTone.Healthy, plan.marks.single().tone)
        assertEquals(listOf("web"), plan.userTags)
        assertEquals(listOf("lab"), plan.hiddenTags)
    }

    @Test fun errorAndFenceAreRedAndOtherHaStatesAreAmber() {
        val error = guestPriorityMarks(guest(status = "running", haState = "error")).single()
        val fence = guestPriorityMarks(guest(status = "stopped", haState = "fence")).single()
        val migrate = guestPriorityMarks(guest(status = "running", haState = "migrate")).single()
        val startedStopped = guestPriorityMarks(guest(status = "stopped", haState = "started")).single()
        assertEquals(GuestMarkTone.Problem, error.tone)
        assertEquals("error", error.label)
        assertEquals(GuestMarkTone.Problem, fence.tone)
        assertEquals("fence", fence.label)
        assertEquals(GuestMarkTone.Attention, migrate.tone)
        assertEquals("migrate", migrate.label)
        assertEquals(GuestMarkTone.Attention, startedStopped.tone)
        assertEquals("started", startedStopped.label)
        assertTrue(error.help.isNotBlank() && migrate.help.isNotBlank())
    }

    @Test fun aLockOrHaProblemTakesTheSlotAheadOfAUserTag() {
        val locked = guestChipPlan(guest(lock = "backup", tags = listOf("web", "lab", "db")), 2)
        assertEquals(listOf("LOCK · backup"), locked.marks.map { it.label })
        assertEquals(GuestMarkTone.Attention, locked.marks.single().tone)
        assertEquals(listOf("web"), locked.userTags)
        val both = guestChipPlan(guest(status = "running", haState = "fence", lock = "migrate", tags = listOf("web")), 2)
        assertEquals(listOf("fence", "LOCK · migrate"), both.marks.map { it.label })
        assertEquals(emptyList(), both.userTags)
        val template = guestChipPlan(guest(template = true, lock = "snapshot", haState = "error", tags = listOf("web")), 2)
        assertEquals(listOf("Template", "error"), template.marks.map { it.label })
        assertEquals(emptyList(), template.userTags)
    }

    @Test fun aFleetRowWithoutMarksStillShowsThreeUserTags() {
        val plan = guestChipPlan(guest(tags = listOf("a", "b", "c", "d")), 3)
        assertEquals(emptyList(), plan.marks)
        assertEquals(listOf("a", "b", "c"), plan.userTags)
        assertEquals(listOf("d"), plan.hiddenTags)
    }

    private fun guest(
        status: String = "running",
        template: Boolean = false,
        tags: List<String> = emptyList(),
        haState: String? = null,
        lock: String? = null,
    ) = Guest(DEMO_REMOTE_KEY, 120, "guest", "demo-node-a", "VM", status, template, tags = tags, haState = haState, lock = lock)
}
