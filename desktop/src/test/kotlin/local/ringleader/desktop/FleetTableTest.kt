package local.ringleader.desktop

import local.ringleader.core.FleetHistory
import local.ringleader.core.FleetTotals
import local.ringleader.core.Guest
import local.ringleader.core.GuestListMetrics
import local.ringleader.core.Sample
import local.ringleader.core.Series
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fleet table and inspector rules from the 2026-11-11 fleet review (G3). */
class FleetTableTest {
    private fun guest(id: Int, status: String = "running", template: Boolean = false, name: String = "guest-$id", cpu: Double? = null, mem: Long? = null) =
        Guest("https://pve.example.test:8006", id, name, "node-a", "VM", status, template, metrics = GuestListMetrics(cpuFraction = cpu, memoryBytes = mem))

    private fun series(vararg cpu: Double, net: Double? = null) =
        Series(cpu.mapIndexed { i, c -> Sample(Instant.ofEpochSecond(i * 10L), c, 1L shl 30, 4L shl 30, net, net) })

    @Test fun segmentCountsFollowTheSearchAndOtherStatesHaveASegment() {
        val guests = listOf(guest(1, name = "web-1"), guest(2, "stopped", name = "web-2"), guest(3, name = "db-1"), guest(4, "stopped", template = true, name = "web-tpl"), guest(5, "paused", name = "web-paused"))
        assertEquals(listOf("All" to 4, "Running" to 1, "Stopped" to 1, "Templates" to 1, "Other" to 1), fleetSegments(guests, "web", "All"))
        assertEquals(listOf("All" to 5, "Running" to 2, "Stopped" to 1, "Templates" to 1, "Other" to 1), fleetSegments(guests, "", "All"))
        // No paused, suspended or unknown guest: no Other segment, unless it is the selected filter.
        val plain = guests.filter { it.status != "paused" }
        assertEquals(listOf("All", "Running", "Stopped", "Templates"), fleetSegments(plain, "", "All").map { it.first })
        assertEquals("Other" to 0, fleetSegments(plain, "", "Other").last())
        assertTrue(guestVisible(guests[4], "Other", "")); assertFalse(guestVisible(guests[4], "Running", "")); assertFalse(guestVisible(guests[4], "Stopped", ""))
        assertFalse(guestVisible(guests[3], "Other", ""), "a template is counted under Templates")
        assertTrue(guestVisible(guest(6, "unknown"), "Other", ""))
    }

    @Test fun theStripSplitAddsUpToTheGuestCount() {
        assertEquals("3 run · 2 stop · 1 tpl", guestSplitText(FleetTotals(3, 2, 1, 6, 1, null, null, null)))
        assertEquals("3 run · 2 stop · 1 tpl · 2 other", guestSplitText(FleetTotals(3, 2, 1, 8, 1, null, null, null)))
        assertEquals(2, otherGuests(FleetTotals(3, 2, 1, 8, 1, null, null, null)))
        assertEquals(0, otherGuests(FleetTotals(3, 2, 1, 5, 1, null, null, null)))
    }

    @Test fun aLiveSortHoldsStillWhileTwoGuestsTradeAFewPercent() {
        val a = guest(1); val b = guest(2); val idle = guest(3); val off = guest(4, "stopped", cpu = .99)
        val order = SteadyGuestOrder()
        fun ids(history: FleetHistory, descending: Boolean = true) = order.order("node:a", "CPU", descending, listOf(off, idle, b, a), history).map { it.id }
        assertEquals(listOf(1, 2, 3, 4), ids(FleetHistory(mapOf(a.resource to series(.30), b.resource to series(.28), idle.resource to series(.01)))))
        // b edges ahead on the latest readings: the rows stay where they are (the latest-value sort swapped them).
        assertEquals(listOf(1, 2, 3, 4), ids(FleetHistory(mapOf(a.resource to series(.30, .28), b.resource to series(.28, .31), idle.resource to series(.01, .01)))))
        // b clearly leads over the last minute: it moves up.
        assertEquals(listOf(2, 1, 3, 4), ids(FleetHistory(mapOf(a.resource to series(.10, .10), b.resource to series(.40, .40), idle.resource to series(.01, .01)))))
        // Ascending is the same steady order reversed: missing (stopped) first.
        assertEquals(listOf(4, 3, 1, 2), ids(FleetHistory(mapOf(a.resource to series(.10, .10), b.resource to series(.40, .40), idle.resource to series(.01, .01))), descending = false))
        // A plain sort does not use the ranking.
        assertEquals(listOf(1, 2, 3, 4), order.order("node:a", "ID", false, listOf(off, idle, b, a), FleetHistory()).map { it.id })
    }

    @Test fun liveSortScoresUseTheLastMinuteAndRankStoppedGuestsLast() {
        val running = guest(1, cpu = .9, mem = 500)
        assertEquals(.2, steadySortScore("CPU", running, series(.1, .3)), 1e-9)
        assertEquals(.9, steadySortScore("CPU", running, null), 1e-9, "the list row's value until live samples exist")
        assertEquals(500.0, steadySortScore("Memory", running, null), 1e-9)
        assertEquals(2048.0, steadySortScore("Net", running, series(.1, net = 1024.0)), 1e-9)
        assertEquals(-1.0, steadySortScore("Net", running, null))
        assertEquals(-1.0, steadySortScore("CPU", guest(2, "stopped", cpu = .9), series(.5)))
        assertEquals(-1.0, steadySortScore("CPU", guest(3, template = true, cpu = .9), series(.5)))
    }

    @Test fun diskUsageNeedsBothSides() {
        assertEquals(10L to 40L, guestDiskUsage(10, 40))
        assertNull(guestDiskUsage(0, 40), "Proxmox reports 0 used for a VM without the guest agent")
        assertNull(guestDiskUsage(10, null)); assertNull(guestDiskUsage(null, 40)); assertNull(guestDiskUsage(10, 0))
    }

    @Test fun nodeCpuGaugeAndFactAgreeJustAboveOne() {
        assertEquals(1.0, cpuShare(1.02)); assertEquals("100.0%", cpuFact(1.02))
        assertEquals(.25, cpuShare(.25)); assertEquals("25.0%", cpuFact(.25))
        assertNull(cpuShare(1.5)); assertEquals("Unavailable", cpuFact(1.5))
        assertNull(cpuShare(Double.NaN)); assertNull(cpuShare(-.1)); assertNull(cpuShare(null)); assertEquals("Unavailable", cpuFact(null))
        // The gauge shows percent(cpuShare(..)): the same figure the fact line rounds.
        assertEquals("100%", percent(cpuShare(1.02)))
    }

    @Test fun historyChartsDescribeThemselvesAndAnIdleWindowHasAScale() {
        assertEquals("CPU chart. Latest 2.0%. Minimum 1.0%, average 1.5%, maximum 2.0%.", historyChartDescription("CPU", "2.0%", "1.0%", "1.5%", "2.0%"))
        assertEquals("Network chart. No samples.", historyChartDescription("Network", null, null, null, null))
        assertEquals(.01, idleHistoryTop("cpu")); assertEquals(1.0, idleHistoryTop("loadavg")); assertEquals(1.0, idleHistoryTop("pressureiosome")); assertEquals(1024.0, idleHistoryTop("netin"))
    }

    @Test fun aRecoveredInventoryReloadsTheCharts() {
        val gate = HistoryLoadGate()
        val key = listOf("hour", "AVERAGE", "node-a", null)
        assertTrue(gate.shouldLoad(key, stale = false))
        assertFalse(gate.shouldLoad(key, stale = true), "going stale keeps the last good series")
        assertTrue(gate.shouldLoad(key, stale = false), "a successful sync loads the chart again")
        // Opened while stale: ask once (the workspace decides), and again once the sync recovers.
        val other = listOf("day", "AVERAGE", "node-a", null)
        assertTrue(gate.shouldLoad(other, stale = true))
        assertFalse(gate.shouldLoad(other, stale = true))
        assertTrue(gate.shouldLoad(other, stale = false))
    }
}
