package local.ringleader.core

import java.io.IOException
import java.time.Instant
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The live loop's wait, its side reads, and the on-demand reads that end with the session (review 2026-11-11, G2). */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveLoopTest {
    private val remote = "https://example.test:8006"

    private fun connection(kind: String = DIRECT_PVE_KIND) = Connection(remote, "tester@pve!read", "test-only-secret", "", kind)

    private val member = RecordedMember(remote, "node-a", "online", null, 0.25, 4.0, 100, 400, 10, 50, 3)
    private val listed = Guest(remote, 100, "alpha", "node-a", "VM", "running")

    private class Fake : InventoryGateway {
        var overviews = 0
        var detailReads = 0
        var inventory: suspend (Connection) -> Overview = { error("set inventory") }
        var detail: suspend (Connection, Guest) -> GuestDetails = { _, guest -> demoDetails(guest) }
        override suspend fun overview(connection: Connection): Overview { overviews++; return inventory(connection) }
        override suspend fun details(connection: Connection, guest: Guest): GuestDetails { detailReads++; return detail(connection, guest) }
    }

    private class Nodes : NodeStatusGateway {
        var reads = 0
        var answer: suspend (String) -> NodeLiveStatus = { node -> NodeLiveStatus(node, 0.2, 100, 400, 10, 50, 3, "test") }
        override suspend fun status(connection: Connection, origin: String, node: String, scope: String): NodeLiveStatus { reads++; return answer(node) }
    }

    /** Every read moves the uptime, so the server counts as refreshed and each read records history. */
    private fun Fake.serving(guest: Guest = listed, members: List<RecordedMember> = listOf(member)) {
        var reads = 0L
        inventory = { Overview(members.size, listOf(guest.copy(metrics = GuestListMetrics(cpuFraction = 0.25, uptimeSeconds = 100 + reads++))), members = members) }
    }

    private fun TestScope.step(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private fun TestScope.workspace(fake: Fake, nodes: Nodes = Nodes()): Workspace {
        val scope = this
        return Workspace(this, fake, nodeStatusGateway = nodes, clock = { Instant.ofEpochMilli(scope.currentTime) }).also { it.liveJitter = { 0.0 } }
    }

    @Test fun waitIsNeverShorterThanTheChosenInterval() {
        assertEquals(5_000, liveWaitMillis(5, null))
        assertEquals(5_000, liveWaitMillis(2, 5))
        assertEquals(60_000, liveWaitMillis(5, 60))
        // The 2 min choice stays at 2 min through the whole backoff sequence.
        for (backoff in listOf(5, 10, 20, 40, 60)) assertEquals(120_000, liveWaitMillis(120, backoff))
        // ±15 %, folded back above the interval when it would fall below it.
        assertEquals(69_000, liveWaitMillis(5, 60, 1.0))
        assertEquals(51_000, liveWaitMillis(5, 60, -1.0))
        assertEquals(138_000, liveWaitMillis(120, 5, -1.0))
        assertEquals(138_000, liveWaitMillis(120, 5, 1.0))
        val random = Random(7)
        val spread = mutableSetOf<Long>()
        repeat(500) {
            val interval = LIVE_INTERVAL_CHOICES.random(random)
            val backoff = listOf(null, 5, 10, 20, 40, 60).random(random)
            val wait = liveWaitMillis(interval, backoff, random.nextDouble(-1.0, 1.0))
            val base = maxOf(interval, backoff ?: 0) * 1000L
            assertTrue(wait >= interval * 1000L, "wait $wait below interval $interval")
            assertTrue(wait <= base * 115 / 100, "wait $wait above base $base + 15 %")
            if (interval == 5 && backoff == null) spread += wait
        }
        assertTrue(spread.size > 10, "jitter must spread the waits")
    }

    @Test fun aTwoMinuteIntervalStaysTwoMinutesAfterAFailure() = runTest {
        var down = false
        val fake = Fake()
        val ready = Overview(0, listOf(listed))
        fake.inventory = { if (down) throw IOException("down") else ready }
        val workspace = workspace(fake)
        try {
            workspace.connect(connection()); runCurrent()
            workspace.setLive(true, 120); runCurrent()
            val start = fake.overviews
            down = true
            step(120_000)
            assertEquals(start + 1, fake.overviews)
            assertEquals(5, workspace.state.value.liveBackoffSeconds)
            assertTrue(workspace.state.value.stale)
            step(60_000)                                    // the old code read again after 5 s, 10 s, 20 s and 40 s
            assertEquals(start + 1, fake.overviews)
            step(60_000)
            assertEquals(start + 2, fake.overviews)
            assertEquals(10, workspace.state.value.liveBackoffSeconds)
            down = false
            step(120_000)
            assertEquals(start + 3, fake.overviews)
            assertNull(workspace.state.value.liveBackoffSeconds)
            assertFalse(workspace.state.value.stale)
        } finally { workspace.close() }
    }

    @Test fun theErrorWaitIsJitteredAndANormalWaitIsNot() = runTest {
        var down = false
        val fake = Fake()
        val ready = Overview(0, listOf(listed))
        fake.inventory = { if (down) throw IOException("down") else ready }
        val workspace = workspace(fake)
        workspace.liveJitter = { 1.0 }
        try {
            workspace.connect(connection()); runCurrent()
            workspace.setLive(true, 2); runCurrent()
            val start = fake.overviews
            step(2_000)                                     // a normal wait is the interval, exactly
            assertEquals(start + 1, fake.overviews)
            down = true
            step(2_000)
            assertEquals(start + 2, fake.overviews)
            assertEquals(5, workspace.state.value.liveBackoffSeconds)
            step(5_700)                                     // backoff 5 s + 15 %
            assertEquals(start + 2, fake.overviews)
            step(50)
            assertEquals(start + 3, fake.overviews)
        } finally { workspace.close() }
    }

    @Test fun resumeStaggersTheFirstWait() = runTest {
        val fake = Fake()
        fake.inventory = { Overview(0, listOf(listed)) }
        val workspace = workspace(fake)
        workspace.liveJitter = { -1.0 }
        try {
            workspace.connect(connection()); runCurrent()
            workspace.setLive(false); runCurrent()
            val start = fake.overviews
            workspace.resumeLive(); runCurrent()
            assertTrue(workspace.state.value.live)
            step(5_700)                                     // 5 s - 15 %, folded above the interval: 5.75 s
            assertEquals(start, fake.overviews)
            step(50)
            assertEquals(start + 1, fake.overviews)
            step(5_000)                                     // later waits are the interval again
            assertEquals(start + 2, fake.overviews)
        } finally { workspace.close() }
    }

    @Test fun sessionsResumeStaggersEveryRestoredSession() = runTest {
        val fakes = mutableListOf<Fake>()
        val sessions = Sessions(this) {
            val fake = Fake().also { it.inventory = { Overview(0, listOf(listed)) }; fakes += it }
            Workspace(this, fake).also { it.liveJitter = { 1.0 } }
        }
        try {
            sessions.state.value.active.workspace.connect(connection()); runCurrent()
            sessions.pause(); runCurrent()
            val start = fakes.single().overviews
            assertTrue(sessions.resume()); runCurrent()
            step(5_000)
            assertEquals(start, fakes.single().overviews)
            step(750)
            assertEquals(start + 1, fakes.single().overviews)
        } finally { sessions.close() }
    }

    @Test fun aFailedGuestReadStaysOnTheDetails() = runTest {
        val fake = Fake()
        fake.serving(members = emptyList())
        val workspace = workspace(fake)
        try {
            workspace.connect(connection()); runCurrent()
            workspace.select(workspace.state.value.overview!!.guests.single()); runCurrent()
            val details = assertNotNull(workspace.state.value.details)
            workspace.setLive(true, 5); runCurrent()
            fake.detail = { _, _ -> throw GuestPermissionException() }
            val before = workspace.state.value.history.guests.getValue(listed.resource).samples.size
            step(5_000)
            val state = workspace.state.value
            assertFalse(state.stale)
            assertNull(state.error)
            assertNull(state.liveBackoffSeconds)
            assertEquals(details, state.details)
            assertNotNull(state.detailsError)
            assertEquals(before + 1, state.history.guests.getValue(listed.resource).samples.size)
            assertNotNull(workspace.capturePower(PowerAction.Shutdown, "Lab"), "a side-read failure must not block power")
            // The next read comes after the interval, not a backoff, and a good read clears the error.
            fake.detail = { _, guest -> demoDetails(guest) }
            val reads = fake.overviews
            step(5_000)
            assertEquals(reads + 1, fake.overviews)
            assertNull(workspace.state.value.detailsError)
        } finally { workspace.close() }
    }

    @Test fun aFailedNodeReadStaysOnTheNodeStatus() = runTest {
        val fake = Fake()
        fake.serving()
        val nodes = Nodes()
        val workspace = workspace(fake, nodes)
        try {
            workspace.connect(connection()); runCurrent()
            workspace.selectMember("node-a"); runCurrent()
            val status = assertNotNull(workspace.state.value.nodeStatus)
            workspace.setLive(true, 5); runCurrent()
            nodes.answer = { throw IOException("timeout") }
            step(5_000)
            val state = workspace.state.value
            assertFalse(state.stale)
            assertNull(state.error)
            assertNull(state.liveBackoffSeconds)
            assertEquals(status, state.nodeStatus)
            assertTrue(state.nodeStatusStale)
            assertNotNull(state.nodeStatusError)
            assertFalse(state.nodeStatusLoading)
        } finally { workspace.close() }
    }

    @Test fun aLostLoginOnASideReadStillEndsTheSession() = runTest {
        val fake = Fake()
        fake.serving(members = emptyList())
        val workspace = workspace(fake)
        val open = connection()
        try {
            workspace.connect(open); runCurrent()
            workspace.select(workspace.state.value.overview!!.guests.single()); runCurrent()
            workspace.setLive(true, 5); runCurrent()
            fake.detail = { _, _ -> throw SessionExpiredException() }
            step(5_000)
            assertFalse(workspace.state.value.connected)
            assertEquals(SessionExpiredException().message, workspace.state.value.error)
        } finally { workspace.close() }
    }

    @Test fun aSessionOutOfViewReadsOnlyTheInventory() = runTest {
        val fake = Fake()
        fake.serving()
        val nodes = Nodes()
        val workspace = workspace(fake, nodes)
        try {
            workspace.connect(connection()); runCurrent()
            workspace.select(workspace.state.value.overview!!.guests.single()); runCurrent()
            workspace.setLive(true, 5); runCurrent()
            step(5_000)
            val detailReads = fake.detailReads
            assertEquals(2, detailReads)                    // the select and one quiet tick
            workspace.inView = false
            val overviews = fake.overviews
            step(5_000); step(5_000)
            assertEquals(overviews + 2, fake.overviews, "the rail stays live")
            assertEquals(detailReads, fake.detailReads)
            workspace.inView = true
            step(5_000)
            assertEquals(detailReads + 1, fake.detailReads)
            // The same for the selected node.
            workspace.selectMember("node-a"); runCurrent()
            val nodeReads = nodes.reads
            workspace.inView = false
            step(5_000)
            assertEquals(nodeReads, nodes.reads)
            workspace.inView = true
            step(5_000)
            assertEquals(nodeReads + 1, nodes.reads)
        } finally { workspace.close() }
    }

    @Test fun aTickBlockedByANodeReadRunsSoonAfterIt() = runTest {
        val fake = Fake()
        fake.serving()
        val nodes = Nodes()
        val gate = CompletableDeferred<NodeLiveStatus>()
        val workspace = workspace(fake, nodes)
        try {
            workspace.connect(connection()); runCurrent()
            workspace.setLive(true, 5); runCurrent()
            nodes.answer = { gate.await() }
            workspace.selectMember("node-a"); runCurrent()  // the explicit node read is in flight
            val start = fake.overviews
            step(5_000)
            assertEquals(start, fake.overviews, "never two reads at once")
            step(1_500)
            gate.complete(NodeLiveStatus("node-a", 0.2, 100, 400, 10, 50, 3, "test")); runCurrent()
            step(500)                                       // the next one-second check, not a whole interval later
            assertEquals(start + 1, fake.overviews)
        } finally { workspace.close() }
    }

    @Test fun aManualRefreshThatFinishedWhileWaitingStartsTheIntervalAgain() = runTest {
        val fake = Fake()
        fake.serving()
        val gate = CompletableDeferred<Unit>()
        val workspace = workspace(fake)
        try {
            workspace.connect(connection()); runCurrent()
            workspace.setLive(true, 5); runCurrent()
            val serving = fake.inventory
            fake.inventory = { gate.await(); serving(it) }
            step(4_000)
            workspace.refresh(); runCurrent()
            val start = fake.overviews
            step(2_500)                                     // due at 5 s, held by the manual refresh
            fake.inventory = serving
            gate.complete(Unit); runCurrent()
            step(1_000)                                     // the one-second check at 7 s finds the refresh done
            assertEquals(start, fake.overviews, "the manual refresh has just read the inventory")
            step(4_000)
            assertEquals(start, fake.overviews)
            step(500)                                       // a whole interval after that check
            assertEquals(start + 1, fake.overviews)
        } finally { workspace.close() }
    }

    @Test fun oneSuccessfulReadIsOneStateUpdate() = runTest {
        var down = false
        val fake = Fake()
        fake.serving(members = emptyList())
        val serving = fake.inventory
        fake.inventory = { if (down) throw IOException("down") else serving(it) }
        val gate = CompletableDeferred<GuestDetails>()
        val workspace = workspace(fake)
        val seen = mutableListOf<WorkspaceState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { workspace.state.collect { seen += it } }
        try {
            workspace.connect(connection()); runCurrent()
            workspace.select(workspace.state.value.overview!!.guests.single()); runCurrent()
            workspace.setLive(true, 5); runCurrent()
            down = true
            step(5_000)
            assertTrue(workspace.state.value.stale)
            down = false
            fake.detail = { _, _ -> gate.await() }
            val samples = workspace.state.value.history.guests.getValue(listed.resource).samples.size
            seen.clear()
            step(5_000)                                     // backoff 5 s; the guest read is still in flight
            assertEquals(1, seen.size, "overview, history and the cleared error arrive together")
            val state = seen.single()
            assertFalse(state.stale)
            assertNull(state.error)
            assertNull(state.liveBackoffSeconds)
            assertEquals(samples + 1, state.history.guests.getValue(listed.resource).samples.size)
            gate.complete(demoDetails(listed)); runCurrent()
            assertEquals(2, seen.size)                      // the details follow on their own
        } finally { workspace.close() }
    }

    @Test fun historyThatCannotBeReadDoesNotStayLoading() = runTest {
        var down = false
        val fake = Fake()
        fake.serving(members = listOf(member))
        val serving = fake.inventory
        fake.inventory = { if (down) throw IOException("down") else serving(it) }
        val workspace = workspace(fake)
        val points = listOf(HistoryPoint(1.0, mapOf("cpu" to 0.5)))
        var reads = 0
        workspace.historyRead = { _, _, _, _, _ -> reads++; points }
        try {
            // Not connected.
            workspace.inspectHistory("hour", "AVERAGE")
            assertFalse(workspace.state.value.chartHistoryLoading)
            assertEquals(HISTORY_NOT_CONNECTED, workspace.state.value.chartHistory.error)
            workspace.connect(connection()); runCurrent()
            // Nothing selected.
            workspace.inspectHistory("hour", "AVERAGE")
            assertFalse(workspace.state.value.chartHistoryLoading)
            assertEquals(HISTORY_NOTHING_SELECTED, workspace.state.value.chartHistory.error)
            workspace.select(workspace.state.value.overview!!.guests.single()); runCurrent()
            workspace.inspectHistory("hour", "AVERAGE"); runCurrent()
            assertEquals(points, workspace.state.value.chartHistory.value)
            assertEquals(1, reads)
            // The fleet goes stale: the same chart keeps its series and does not stay on "Loading".
            workspace.setLive(true, 5); runCurrent()
            down = true
            step(5_000)
            assertTrue(workspace.state.value.stale)
            workspace.inspectHistory("hour", "AVERAGE"); runCurrent()
            assertFalse(workspace.state.value.chartHistoryLoading)
            assertEquals(points, workspace.state.value.chartHistory.value)
            assertEquals(1, reads)
            // Another chart does not show this one's series; it says why it is empty.
            workspace.inspectHistory("day", "AVERAGE"); runCurrent()
            assertFalse(workspace.state.value.chartHistoryLoading)
            assertNull(workspace.state.value.chartHistory.value)
            assertEquals(HISTORY_STALE, workspace.state.value.chartHistory.error)
            // Recovered: it loads again.
            down = false
            step(5_000)
            assertFalse(workspace.state.value.stale)
            workspace.inspectHistory("day", "AVERAGE"); runCurrent()
            assertEquals(points, workspace.state.value.chartHistory.value)
            assertFalse(workspace.state.value.chartHistoryLoading)
            assertEquals(2, reads)
        } finally { workspace.close() }
    }

    @Test fun historyReloadOfTheSameChartKeepsItsSeriesWhileLoading() = runTest {
        val fake = Fake()
        fake.serving(members = emptyList())
        val workspace = workspace(fake)
        val first = listOf(HistoryPoint(1.0, mapOf("cpu" to 0.5)))
        val second = listOf(HistoryPoint(2.0, mapOf("cpu" to 0.6)))
        val gate = CompletableDeferred<List<HistoryPoint>>()
        var answer: suspend () -> List<HistoryPoint> = { first }
        workspace.historyRead = { _, _, _, _, _ -> answer() }
        try {
            workspace.connect(connection()); runCurrent()
            workspace.select(workspace.state.value.overview!!.guests.single()); runCurrent()
            workspace.inspectHistory("hour", "AVERAGE"); runCurrent()
            assertEquals(first, workspace.state.value.chartHistory.value)
            answer = { gate.await() }
            workspace.inspectHistory("hour", "AVERAGE"); runCurrent()
            assertTrue(workspace.state.value.chartHistoryLoading)
            assertEquals(first, workspace.state.value.chartHistory.value)
            gate.complete(second); runCurrent()
            assertEquals(second, workspace.state.value.chartHistory.value)
            assertFalse(workspace.state.value.chartHistoryLoading)
        } finally { workspace.close() }
    }

    @Test fun disconnectCancelsTheHistoryAndConsoleReads() = runTest {
        val fake = Fake()
        fake.serving(members = emptyList())
        var historyCancelled = false
        var stillCancelled = false
        val stills = object : ConsoleStillGateway {
            override suspend fun capture(connection: Connection, guest: Guest): ConsoleStill =
                try { awaitCancellation() } finally { stillCancelled = true }
        }
        val scope = this
        val workspace = Workspace(this, fake, consoleStillGateway = stills, clock = { Instant.ofEpochMilli(scope.currentTime) })
        workspace.historyRead = { _, _, _, _, _ -> try { awaitCancellation() } finally { historyCancelled = true } }
        try {
            workspace.connect(connection()); runCurrent()
            val guest = workspace.state.value.overview!!.guests.single()
            workspace.select(guest); runCurrent()
            workspace.inspectHistory("hour", "AVERAGE"); runCurrent()
            workspace.captureConsoleStill(guest); runCurrent()
            assertTrue(workspace.state.value.chartHistoryLoading)
            assertTrue(guest.resource in workspace.state.value.consoleStillBusy)
            workspace.disconnect(); runCurrent()
            assertTrue(historyCancelled)
            assertTrue(stillCancelled)
            assertEquals(WorkspaceState(), workspace.state.value)
        } finally { workspace.close() }
    }
}
