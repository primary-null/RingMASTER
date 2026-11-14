package local.ringleader.desktop

import local.ringleader.core.Guest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A guest row: one click selects, a quick second click on the same row opens the guest. */
class FleetRowClickTest {
    @Test fun aQuickSecondClickOnTheSameRowOpensIt() {
        val clicks = RowClicks(window = 500)
        assertEquals(RowClick.Select, clicks.register("vm-100", 1_000))
        assertEquals(RowClick.Open, clicks.register("vm-100", 1_300))
        // A third quick click starts over: it does not open the guest a second time.
        assertEquals(RowClick.Select, clicks.register("vm-100", 1_500))
        assertEquals(RowClick.Open, clicks.register("vm-100", 1_900))
    }

    @Test fun slowClicksAndClicksOnDifferentRowsOnlySelect() {
        val clicks = RowClicks(window = 500)
        assertEquals(RowClick.Select, clicks.register("vm-100", 1_000))
        assertEquals(RowClick.Select, clicks.register("vm-100", 1_501))
        assertEquals(RowClick.Select, clicks.register("vm-101", 1_600))
        assertEquals(RowClick.Select, clicks.register("vm-100", 1_700))
        assertEquals(RowClick.Open, clicks.register("vm-100", 2_200))
    }

    @Test fun aRunningGuestOpensOnItsConsoleAndAnythingElseOnItsSummary() {
        fun guest(kind: String, status: String, template: Boolean = false) = Guest("https://pve.example.test:8006", 100, "alpha", "node-a", kind, status, template = template)
        assertEquals("Console", guestOpenTab(guest("VM", "running")))
        assertEquals("Console", guestOpenTab(guest("CT", "running")))
        assertEquals("Summary", guestOpenTab(guest("VM", "stopped")))
        assertEquals("Summary", guestOpenTab(guest("VM", "running", template = true)))
        // The demo has no consoles, so a running sample guest opens on its summary too.
        assertEquals("Summary", guestOpenTab(guest("VM", "running"), demo = true))
    }

    @Test fun theDoubleClickTimeIsSane() {
        assertEquals(true, doubleClickMillis() in 200L..900L)
    }
}
