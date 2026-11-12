package local.ringleader.core

import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The chart lens reads history without touching the page's chart slot. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadHistoryTest {
    private val remote = "https://example.test:8006"
    private fun connection(kind: String = DIRECT_PVE_KIND) = Connection(remote, "tester@pve!read", "test-only-secret", "", kind)
    private val member = RecordedMember(remote, "node-a", "online", null, 0.25, 4.0, 100, 400, 10, 50, 3)
    private val listed = Guest(remote, 100, "alpha", "node-a", "VM", "running")

    private class Fake : InventoryGateway {
        var inventory: suspend (Connection) -> Overview = { error("set inventory") }
        override suspend fun overview(connection: Connection) = inventory(connection)
        override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
    }

    private fun Fake.serving() {
        inventory = { Overview(1, listOf(listed.copy(metrics = GuestListMetrics(cpuFraction = 0.25, uptimeSeconds = 10))), members = listOf(member)) }
    }

    private fun TestScope.step(millis: Long) { advanceTimeBy(millis); runCurrent() }

    private fun TestScope.workspace(fake: Fake = Fake()): Workspace {
        val scope = this
        return Workspace(this, fake, clock = { Instant.ofEpochMilli(scope.currentTime) }).also { it.liveJitter = { 0.0 } }
    }

    @Test fun refusalsLeaveThePageChartAlone() = runTest {
        val fake = Fake().also { it.serving() }
        val workspace = workspace(fake)
        val points = listOf(HistoryPoint(1.0, mapOf("cpu" to 0.5)))
        var reads = 0
        workspace.historyRead = { _, node, guest, _, _ -> reads++; assertNull(node); assertNotNull(guest); points }
        try {
            assertEquals(HISTORY_NOTHING_SELECTED, workspace.readHistory(null, null, "hour", "AVERAGE").error)
            assertEquals(HISTORY_NOT_CONNECTED, workspace.readHistory(listed, null, "hour", "AVERAGE").error)
            assertEquals(InspectionSection<List<HistoryPoint>>(), workspace.state.value.chartHistory)
            workspace.connect(connection()); runCurrent()
            val guest = workspace.state.value.overview!!.guests.single()
            workspace.inspectHistory("hour", "AVERAGE"); runCurrent()
            // Nothing is selected, so the page chart is the refusal, not our points. Select, then read beside it.
            workspace.select(guest); runCurrent()
            workspace.inspectHistory("hour", "AVERAGE"); runCurrent()
            val page = workspace.state.value.chartHistory
            assertEquals(points, page.value)
            val beside = workspace.readHistory(guest, null, "day", "MAX")
            assertEquals(points, beside.value)
            assertEquals(page, workspace.state.value.chartHistory)
            assertEquals(2, reads)
            val scoped = Guest(remote, 7, "other", "node-a", "VM", "running", scope = "lab")
            assertEquals(HISTORY_DIRECT_ONLY, workspace.readHistory(scoped, null, "hour", "AVERAGE").error)
            assertEquals(page, workspace.state.value.chartHistory)
            workspace.disconnect(); runCurrent()
            workspace.connect(connection(PDM_KIND)); runCurrent()
            assertEquals(HISTORY_DIRECT_ONLY, workspace.readHistory(guest, null, "hour", "AVERAGE").error)
        } finally { workspace.close() }
    }

    @Test fun aStaleInventoryIsRefusedAndABadRangeThrows() = runTest {
        var down = false
        val fake = Fake().also { it.serving() }
        val serving = fake.inventory
        fake.inventory = { if (down) throw IOException("down") else serving(it) }
        val workspace = workspace(fake)
        var reads = 0
        workspace.historyRead = { _, _, _, _, _ -> reads++; emptyList() }
        try {
            workspace.connect(connection()); runCurrent()
            val guest = workspace.state.value.overview!!.guests.single()
            assertFailsWith<IllegalArgumentException> { workspace.readHistory(guest, null, "nope", "AVERAGE") }
            workspace.setLive(true, 5); runCurrent()
            down = true
            step(5_000)
            assertTrue(workspace.state.value.stale)
            assertEquals(HISTORY_STALE, workspace.readHistory(guest, null, "hour", "AVERAGE").error)
            assertEquals(0, reads)
            assertNull(workspace.state.value.chartHistory.value)
        } finally { workspace.close() }
    }

    @Test fun demoHistoryDoesNotUseThePageSlot() = runTest {
        val workspace = workspace()
        try {
            workspace.exploreDemo(); runCurrent()
            val guest = workspace.state.value.overview!!.guests.first()
            val average = workspace.readHistory(guest, null, "hour", "AVERAGE")
            val maximum = workspace.readHistory(guest, null, "hour", "MAX")
            assertEquals(average.value!!.map { it.values["cpu"]!! * 1.25 }, maximum.value!!.map { it.values["cpu"] })
            assertEquals(average.value!!.map { it.values["maxmem"] }, maximum.value!!.map { it.values["maxmem"] })
            assertEquals(average.value!!.map { it.values["maxcpu"] }, maximum.value!!.map { it.values["maxcpu"] })
            val node = workspace.state.value.overview!!.members.first().name
            assertEquals(120, workspace.readHistory(null, node, "day", "AVERAGE").value!!.size)
            assertNull(workspace.state.value.chartHistory.value)
            assertEquals(HISTORY_NOTHING_SELECTED, workspace.readHistory(null, null, "hour", "AVERAGE").error)
        } finally { workspace.close() }
    }

    @Test fun permissionAndExpiryStayOffThePageChart() = runTest {
        val fake = Fake().also { it.serving() }
        val workspace = workspace(fake)
        val points = listOf(HistoryPoint(4.0, mapOf("cpu" to 0.2)))
        workspace.historyRead = { _, _, _, _, _ -> throw GuestPermissionException() }
        try {
            workspace.connect(connection()); runCurrent()
            val guest = workspace.state.value.overview!!.guests.single()
            workspace.select(guest); runCurrent()
            workspace.historyRead = { _, _, _, _, _ -> points }
            workspace.inspectHistory("hour", "AVERAGE"); runCurrent()
            workspace.historyRead = { _, _, _, _, _ -> throw GuestPermissionException() }
            val denied = workspace.readHistory(guest, null, "week", "AVERAGE")
            assertEquals("Not permitted for this token", denied.error)
            assertEquals(points, workspace.state.value.chartHistory.value)
            workspace.historyRead = { _, _, _, _, _ -> throw SessionExpiredException() }
            val expired = workspace.readHistory(guest, null, "week", "AVERAGE")
            assertEquals("The server refused this session. Connect again.", expired.error)
            assertEquals("The server refused this session. Connect again.", workspace.state.value.error)
        } finally { workspace.close() }
    }

    @Test fun aGenerationChangeDoesNotExpireAgain() = runTest {
        val fake = Fake().also { it.serving() }
        val workspace = workspace(fake)
        val gate = CompletableDeferred<Unit>()
        workspace.historyRead = { _, _, _, _, _ -> gate.await(); throw SessionExpiredException() }
        try {
            workspace.connect(connection()); runCurrent()
            val guest = workspace.state.value.overview!!.guests.single()
            val pending = async { workspace.readHistory(guest, null, "hour", "AVERAGE") }
            runCurrent()
            workspace.disconnect(); runCurrent()
            gate.complete(Unit); runCurrent()
            val section = pending.await()
            assertEquals("The server refused this session. Connect again.", section.error)
            assertNull(workspace.state.value.error)
        } finally { workspace.close() }
    }

    @Test fun cancellationIsRethrown() = runTest {
        val fake = Fake().also { it.serving() }
        val workspace = workspace(fake)
        workspace.historyRead = { _, _, _, _, _ -> throw CancellationException("stop") }
        try {
            workspace.connect(connection()); runCurrent()
            val guest = workspace.state.value.overview!!.guests.single()
            assertFailsWith<CancellationException> { workspace.readHistory(guest, null, "hour", "AVERAGE") }
        } finally { workspace.close() }
    }

    @Test fun aNodeWinsWhenBothArePassed() = runTest {
        val fake = Fake().also { it.serving() }
        val workspace = workspace(fake)
        var seenNode: String? = null
        var seenGuest = false
        workspace.historyRead = { _, node, guest, _, _ -> seenNode = node; seenGuest = guest != null; emptyList() }
        try {
            workspace.connect(connection()); runCurrent()
            val guest = workspace.state.value.overview!!.guests.single()
            workspace.readHistory(guest, "node-a", "hour", "AVERAGE")
            assertEquals("node-a", seenNode)
            assertTrue(seenGuest)
        } finally { workspace.close() }
    }
}
