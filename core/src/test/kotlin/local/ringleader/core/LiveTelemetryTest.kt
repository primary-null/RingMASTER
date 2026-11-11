package local.ringleader.core

import java.io.IOException
import java.time.Instant
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTelemetryTest {
    private val remoteA = "https://example.test:8006"
    private val remoteB = "https://b.example.test:8006"

    private fun connection(address: String = remoteA, secret: String = "test-only-secret", kind: String = DIRECT_PVE_KIND) =
        Connection(address, "tester@pve!read", secret, "", kind)

    private fun guest(remote: String, id: Int = 100, status: String = "running", template: Boolean = false, metrics: GuestListMetrics? = null) =
        Guest(remote, id, "alpha", "node-a", "VM", status, template, metrics = metrics)

    private fun overview(remote: String, guests: List<Guest> = listOf(guest(remote)), members: List<RecordedMember> = emptyList()) =
        Overview(members.size, guests, members = members)

    private class Fake : InventoryGateway {
        var inventory: suspend (Connection) -> Overview = { connection -> Overview(1, listOf(Guest(connection.remoteKey, 100, "alpha", "node-a", "VM", "running"))) }
        var detail: suspend (Connection, Guest) -> GuestDetails = { _, guest -> demoDetails(guest) }
        override suspend fun overview(connection: Connection) = inventory(connection)
        override suspend fun details(connection: Connection, guest: Guest) = detail(connection, guest)
    }

    private class Inspect : GuestInspectionGateway {
        override suspend fun read(connection: Connection, guest: Guest, storages: List<String>, timeframe: String) = demoInspection(guest, timeframe)
    }

    private fun TestScope.step(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test fun tickDoesNotOverlapAReadAlreadyInFlight() = runTest {
        val gate = CompletableDeferred<Overview>()
        var calls = 0
        var block = false
        val fake = Fake()
        val ready = overview(remoteA)
        fake.inventory = {
            calls++
            if (block) gate.await() else ready
        }
        val workspace = Workspace(this, fake)
        try {
            workspace.connect(connection()); runCurrent()
            assertEquals(1, calls)
            workspace.setLive(true, 2); runCurrent()
            block = true
            step(2_000)
            assertEquals(2, calls)
            step(2_000)
            assertEquals(2, calls)
            gate.complete(ready); runCurrent()
            assertEquals(2, calls)
            block = false
            step(2_000)
            assertEquals(3, calls)
            val manual = CompletableDeferred<Overview>()
            block = true
            fake.inventory = {
                calls++
                manual.await()
            }
            workspace.refresh(); runCurrent()
            val duringManual = calls
            step(2_000)
            assertEquals(duringManual, calls)
            manual.complete(ready); runCurrent()
        } finally { workspace.close() }
    }

    @Test fun quietTickPreservesSelectionDetailsAndInspection() = runTest {
        val gate = CompletableDeferred<GuestDetails>()
        val fake = Fake()
        val listed = guest(remoteA, metrics = GuestListMetrics(cpuFraction = 0.25, memoryBytes = 10, memoryLimitBytes = 40, netIn = 100, netOut = 40))
        // The server refreshes its numbers between reads (the uptime moves), so every read is recorded.
        var reads = 0L
        fake.inventory = { overview(remoteA, listOf(listed.copy(metrics = listed.metrics!!.copy(uptimeSeconds = 100 + 10 * reads++)))) }
        val scope = this
        val workspace = Workspace(this, fake, inspectionGateway = Inspect(), clock = { Instant.ofEpochMilli(scope.currentTime) })
        try {
            workspace.connect(connection()); runCurrent()
            workspace.select(listed); runCurrent()
            workspace.inspectGuest(); runCurrent()
            val inspection = workspace.state.value.inspection
            val details = workspace.state.value.details
            val selected = workspace.state.value.selected
            assertNotNull(inspection)
            assertNotNull(details)
            assertFalse(workspace.state.value.loading)
            assertFalse(workspace.state.value.detailsLoading)
            fake.detail = { _, _ -> gate.await() }
            step(5_000)
            assertEquals(selected?.resource, workspace.state.value.selected?.resource)
            assertEquals(details, workspace.state.value.details)
            assertEquals(inspection, workspace.state.value.inspection)
            assertFalse(workspace.state.value.loading)
            assertFalse(workspace.state.value.detailsLoading)
            assertFalse(workspace.state.value.inspectionLoading)
            assertFalse(workspace.state.value.nodeStatusLoading)
            val fresh = demoDetails(listed).copy(cpuFraction = 0.5)
            gate.complete(fresh); runCurrent()
            assertEquals(fresh, workspace.state.value.details)
            assertEquals(inspection, workspace.state.value.inspection)
            assertFalse(workspace.state.value.loading)
            assertTrue(workspace.state.value.history.guests.getValue(listed.resource).samples.size >= 2)
        } finally { workspace.close() }
    }

    /**
     * Proxmox refreshes its numbers about every 10 seconds. Polled every 5, every other read repeats the last one:
     * recording it drew each value twice (a staircase) and moved the charts without new data (reported in 0.23).
     */
    @Test fun aReadTheServerHasNotRefreshedRecordsNothing() = runTest {
        val fake = Fake()
        val member = RecordedMember(remoteA, "node-a", "online", null, 0.25, 4.0, 100, 400, 10, 50, 3)
        var listed = guest(remoteA, metrics = GuestListMetrics(cpuFraction = 0.25, memoryBytes = 10, memoryLimitBytes = 40, netIn = 1_000, netOut = 400, uptimeSeconds = 100))
        var node = member
        fake.inventory = { overview(remoteA, listOf(listed), listOf(node)) }
        val scope = this
        val workspace = Workspace(this, fake, clock = { Instant.ofEpochMilli(scope.currentTime) })
        try {
            workspace.connect(connection()); runCurrent()
            fun samples() = workspace.state.value.history.guests.getValue(listed.resource).samples
            assertEquals(1, samples().size)
            val before = workspace.state.value.history
            step(5_000)                                         // the server has not refreshed yet
            assertEquals(1, samples().size)
            assertTrue(workspace.state.value.history === before, "an unrefreshed read must not touch the history at all")
            listed = listed.copy(metrics = listed.metrics!!.copy(cpuFraction = 0.5, netIn = 21_000, uptimeSeconds = 110))
            node = member.copy(cpuFraction = 0.4, uptimeSeconds = 13)
            step(5_000)                                         // now it has
            assertEquals(listOf(0.25, 0.5), samples().map { it.cpu })
            assertEquals(listOf(false, false), samples().map { it.held })
            // The rate is the growth over the whole 10 seconds since the counters last moved, not over the last 5.
            assertEquals(2_000.0, samples().last().netInRate)
            assertEquals(2, workspace.state.value.history.cluster.samples.size)
            // A manual refresh of unrefreshed numbers records nothing either.
            workspace.refresh(); runCurrent()
            assertEquals(2, samples().size)
        } finally { workspace.close() }
    }

    /** Nodes refresh on their own clocks. A read in which only one did still gives every series a sample, the repeated ones marked held. */
    @Test fun whenOnlySomeNodesRefreshedTheOthersGetAHeldSample() {
        fun member(name: String, cpu: Double, uptime: Long) = RecordedMember(remoteA, name, "online", null, cpu, 4.0, 100, 400, 10, 50, uptime)
        fun on(node: String, id: Int, cpu: Double, uptime: Long) = Guest(remoteA, id, "g$id", node, "VM", "running", metrics = GuestListMetrics(cpuFraction = cpu, memoryBytes = 10, memoryLimitBytes = 40, netIn = 100, netOut = 40, uptimeSeconds = uptime))
        val stopped = Guest(remoteA, 300, "off", "node-b", "VM", "stopped")
        fun read(aCpu: Double, aUp: Long, bCpu: Double, bUp: Long) = Overview(2, listOf(on("node-a", 100, aCpu, aUp), on("node-b", 200, bCpu, bUp), stopped), members = listOf(member("node-a", aCpu, aUp), member("node-b", bCpu, bUp)))
        var step = nextFleetHistory(FleetHistory(), emptyMap(), read(0.10, 10, 0.20, 10), Instant.ofEpochSecond(0))
        assertTrue(step.fresh)
        step = nextFleetHistory(step.history, step.counters, read(0.30, 20, 0.20, 10), Instant.ofEpochSecond(5), step.readings)      // only node-a refreshed
        assertTrue(step.fresh)
        val a = step.history.guests.getValue(ResourceId(remoteA, "", "VM", 100)).samples
        val b = step.history.guests.getValue(ResourceId(remoteA, "", "VM", 200)).samples
        assertEquals(listOf(false, false), a.map { it.held })
        assertEquals(listOf(false, true), b.map { it.held })
        assertEquals(listOf(0.20, 0.20), b.map { it.cpu })
        assertEquals(listOf(false, false), step.history.nodes.getValue("node-a").samples.map { it.held })
        assertEquals(listOf(false, true), step.history.nodes.getValue("node-b").samples.map { it.held })
        assertEquals(listOf(false, false), step.history.cluster.samples.map { it.held })
        // Every series has the same number of samples, so they share one time base.
        assertEquals(setOf(2), (step.history.guests.values + step.history.nodes.values + step.history.cluster).map { it.samples.size }.toSet())
        val again = nextFleetHistory(step.history, step.counters, read(0.30, 20, 0.20, 10), Instant.ofEpochSecond(10), step.readings)  // nothing refreshed
        assertFalse(again.fresh)
        assertTrue(again.history === step.history)
        val next = nextFleetHistory(again.history, again.counters, read(0.30, 20, 0.25, 20), Instant.ofEpochSecond(15), again.readings)  // node-b refreshed
        assertEquals(listOf(false, true, false), next.history.guests.getValue(ResourceId(remoteA, "", "VM", 200)).samples.map { it.held })
        assertEquals(listOf(false, false, true), next.history.guests.getValue(ResourceId(remoteA, "", "VM", 100)).samples.map { it.held })
    }

    @Test fun quietTickRefreshesSelectedNodeWithoutLoading() = runTest {
        val gate = CompletableDeferred<NodeLiveStatus>()
        var calls = 0
        val member = RecordedMember(remoteA, "node-a", "online", null, 0.25, 4.0, 100, 400, 10, 50, 3)
        val fake = Fake()
        fake.inventory = { overview(remoteA, members = listOf(member)) }
        val nodes = object : NodeStatusGateway {
            override suspend fun status(connection: Connection, origin: String, node: String, scope: String): NodeLiveStatus {
                calls++
                val status = NodeLiveStatus(node, 0.2, 100, 400, 10, 50, 3, "test")
                return if (calls > 1) gate.await() else status
            }
        }
        val workspace = Workspace(this, fake, nodeStatusGateway = nodes)
        try {
            workspace.connect(connection()); runCurrent()
            workspace.selectMember("node-a"); runCurrent()
            assertEquals(1, calls)
            val previous = workspace.state.value.nodeStatus
            assertNotNull(previous)
            assertFalse(workspace.state.value.nodeStatusLoading)
            workspace.setLive(true, 2); runCurrent()
            step(2_000)
            assertEquals(2, calls)
            assertEquals(previous, workspace.state.value.nodeStatus)
            assertFalse(workspace.state.value.nodeStatusLoading)
            assertFalse(workspace.state.value.loading)
            gate.complete(NodeLiveStatus("node-a", 0.4, 120, 400, 12, 50, 4, "test")); runCurrent()
            assertEquals(0.4, workspace.state.value.nodeStatus?.cpuFraction)
            assertFalse(workspace.state.value.nodeStatusLoading)
        } finally { workspace.close() }
    }

    @Test fun backoffSequenceRecoversAndPermissionKeepsTheSession() = runTest {
        var phase = "ok"
        val fake = Fake()
        val ready = overview(remoteA)
        fake.inventory = {
            when (phase) {
                "down" -> throw IOException("down")
                "denied" -> throw GuestPermissionException()
                else -> ready
            }
        }
        val workspace = Workspace(this, fake)
        try {
            workspace.connect(connection()); runCurrent()
            assertTrue(workspace.state.value.live)
            assertEquals(LIVE_INTERVAL_DEFAULT, workspace.state.value.liveIntervalSeconds)
            workspace.setLive(true, 2); runCurrent()
            phase = "down"
            step(2_000)
            assertEquals(5, workspace.state.value.liveBackoffSeconds)
            assertTrue(workspace.state.value.stale)
            assertTrue(workspace.state.value.connected)
            assertFalse(workspace.state.value.loading)
            assertNotNull(workspace.state.value.overview)
            phase = "denied"
            step(5_000)
            assertEquals(10, workspace.state.value.liveBackoffSeconds)
            assertTrue(workspace.state.value.connected)
            assertNotNull(workspace.state.value.overview)
            phase = "down"
            step(10_000)
            assertEquals(20, workspace.state.value.liveBackoffSeconds)
            step(20_000)
            assertEquals(40, workspace.state.value.liveBackoffSeconds)
            step(40_000)
            assertEquals(60, workspace.state.value.liveBackoffSeconds)
            step(60_000)
            assertEquals(60, workspace.state.value.liveBackoffSeconds)
            assertTrue(workspace.state.value.connected)
            phase = "ok"
            step(60_000)
            assertNull(workspace.state.value.liveBackoffSeconds)
            assertFalse(workspace.state.value.stale)
            assertNull(workspace.state.value.error)
            step(2_000)
            assertNull(workspace.state.value.liveBackoffSeconds)
            assertTrue(workspace.state.value.connected)
        } finally { workspace.close() }
    }

    @Test fun expiredSessionStopsTheLoop() = runTest {
        var calls = 0
        var fail = false
        val fake = Fake()
        val ready = overview(remoteA)
        fake.inventory = {
            calls++
            if (fail) throw SessionExpiredException() else ready
        }
        val workspace = Workspace(this, fake)
        val open = connection()
        try {
            workspace.connect(open); runCurrent()
            workspace.setLive(true, 2); runCurrent()
            val baseline = calls
            fail = true
            step(2_000)
            assertFalse(workspace.state.value.connected)
            assertNull(workspace.state.value.overview)
            assertNotNull(workspace.state.value.error)
            assertFailsWith<IllegalStateException> { open.withSecret { it } }
            fail = false
            step(120_000)
            assertEquals(baseline + 1, calls)
            assertFalse(workspace.state.value.connected)
        } finally { workspace.close() }
    }

    @Test fun disconnectAndEpochChangeCancelTheLoopAndResetHistory() = runTest {
        val gate = CompletableDeferred<Overview>()
        var blockA = false
        val fake = Fake()
        fake.inventory = { connection ->
            if (connection.remoteKey == remoteA && blockA) gate.await()
            overview(connection.remoteKey, listOf(guest(connection.remoteKey)))
        }
        val scope = this
        val workspace = Workspace(this, fake, clock = { Instant.ofEpochMilli(scope.currentTime) })
        try {
            val first = connection(remoteA, "secret-a")
            workspace.connect(first); runCurrent()
            assertTrue(workspace.state.value.history.guests.keys.any { it.remote == remoteA })
            workspace.setLive(true, 2); runCurrent()
            blockA = true
            step(2_000)
            workspace.disconnect()
            gate.complete(overview(remoteA)); runCurrent()
            assertEquals(WorkspaceState(), workspace.state.value)
            assertFailsWith<IllegalStateException> { first.withSecret { it } }
            workspace.connect(connection(remoteB, "secret-b")); runCurrent()
            assertEquals(remoteB, workspace.state.value.address)
            assertTrue(workspace.state.value.history.guests.isNotEmpty())
            assertTrue(workspace.state.value.history.guests.keys.all { it.remote == remoteB })
            val previous = workspace.state.value.history
            workspace.connect(connection(remoteA, "secret-c")); runCurrent()
            assertEquals(remoteA, workspace.state.value.address)
            assertTrue(workspace.state.value.history.guests.keys.all { it.remote == remoteA })
            assertNotEquals(previous, workspace.state.value.history)
        } finally { workspace.close() }
    }

    @Test fun seriesKeepsOneHundredTwentyNewestSamples() {
        val remote = remoteA
        val listed = guest(remote, metrics = GuestListMetrics(cpuFraction = 0.1, memoryBytes = 1, memoryLimitBytes = 2, netIn = 1, netOut = 1))
        val member = RecordedMember(remote, "node-a", "online", null, 0.1, 1.0, 1, 2, null, null, null)
        val current = overview(remote, listOf(listed), listOf(member))
        var step = FleetHistoryStep(FleetHistory(), emptyMap())
        repeat(121) { index ->
            step = nextFleetHistory(step.history, step.counters, current, Instant.ofEpochMilli(index * 1000L))
        }
        val series = step.history.guests.getValue(listed.resource)
        assertEquals(SERIES_CAP, series.samples.size)
        assertEquals(Instant.ofEpochMilli(1_000), series.samples.first().at)
        assertEquals(Instant.ofEpochMilli(120_000), series.samples.last().at)
        assertTrue(series.samples.zipWithNext().all { (older, newer) -> older.at < newer.at })
        assertEquals(SERIES_CAP, step.history.nodes.getValue("node-a").samples.size)
        assertEquals(SERIES_CAP, step.history.cluster.samples.size)
        val dropped = nextFleetHistory(step.history, step.counters, Overview(0, emptyList()), Instant.ofEpochMilli(121_000))
        assertTrue(dropped.history.guests.isEmpty())
        assertTrue(dropped.history.nodes.isEmpty())
        assertEquals(SERIES_CAP, dropped.history.cluster.samples.size)
    }

    @Test fun unchangedCountersHoldTheLastRateUntilTheyGoStale() {
        val remote = remoteA
        val id = guest(remote).resource
        fun listed(netIn: Long, netOut: Long) = guest(remote, metrics = GuestListMetrics(cpuFraction = 0.5, memoryBytes = 10, memoryLimitBytes = 20, netIn = netIn, netOut = netOut))
        var step = nextFleetHistory(FleetHistory(), emptyMap(), overview(remote, listOf(listed(0, 0))), Instant.ofEpochSecond(0))
        step = nextFleetHistory(step.history, step.counters, overview(remote, listOf(listed(10_000, 2_000))), Instant.ofEpochSecond(10))
        assertEquals(1_000.0, step.history.guests.getValue(id).samples.last().netInRate)
        // PVE has not refreshed the counters 5 s later: keep 1000 B/s instead of dropping to 0.
        step = nextFleetHistory(step.history, step.counters, overview(remote, listOf(listed(10_000, 2_000))), Instant.ofEpochSecond(15))
        assertEquals(1_000.0, step.history.guests.getValue(id).samples.last().netInRate)
        assertEquals(200.0, step.history.guests.getValue(id).samples.last().netOutRate)
        // The next refresh is averaged over the full 10 s since the last change.
        step = nextFleetHistory(step.history, step.counters, overview(remote, listOf(listed(30_000, 2_000))), Instant.ofEpochSecond(20))
        assertEquals(2_000.0, step.history.guests.getValue(id).samples.last().netInRate)
        assertEquals(0.0, step.history.guests.getValue(id).samples.last().netOutRate)
        // Unchanged for longer than the hold window: the guest really is idle.
        step = nextFleetHistory(step.history, step.counters, overview(remote, listOf(listed(30_000, 2_000))), Instant.ofEpochSecond(50))
        assertEquals(0.0, step.history.guests.getValue(id).samples.last().netInRate)
    }

    @Test fun networkRateUsesCounterDeltasAndTreatsResetAsMissing() {
        val remote = remoteA
        val id = guest(remote).resource
        fun listed(netIn: Long?, netOut: Long?) = guest(remote, metrics = GuestListMetrics(cpuFraction = 0.5, memoryBytes = 10, memoryLimitBytes = 20, netIn = netIn, netOut = netOut))
        var step = nextFleetHistory(FleetHistory(), emptyMap(), overview(remote, listOf(listed(1_000, 400))), Instant.ofEpochMilli(0))
        val first = step.history.guests.getValue(id).samples.single()
        assertNull(first.netInRate)
        assertNull(first.netOutRate)
        assertNull(step.history.cluster.samples.last().netInRate)
        step = nextFleetHistory(step.history, step.counters, overview(remote, listOf(listed(1_600, 400))), Instant.ofEpochMilli(2_000))
        val second = step.history.guests.getValue(id).samples.last()
        assertEquals(300.0, second.netInRate)
        assertEquals(0.0, second.netOutRate)
        assertEquals(300.0, step.history.cluster.samples.last().netInRate)
        assertEquals(0.0, step.history.cluster.samples.last().netOutRate)
        step = nextFleetHistory(step.history, step.counters, overview(remote, listOf(listed(1_600, 400))), Instant.ofEpochMilli(2_000))
        assertNull(step.history.guests.getValue(id).samples.last().netInRate)
        step = nextFleetHistory(step.history, step.counters, overview(remote, listOf(listed(100, 700))), Instant.ofEpochMilli(4_000))
        val reset = step.history.guests.getValue(id).samples.last()
        assertNull(reset.netInRate)
        assertEquals(150.0, reset.netOutRate)
    }

    @Test fun clusterAggregateSkipsMissingAndZeroCapacity() {
        val remote = remoteA
        fun member(name: String, cpu: Double?, maxCpu: Double?, mem: Long?, maxMem: Long?) =
            RecordedMember(remote, name, "online", null, cpu, maxCpu, mem, maxMem, null, null, null)
        val members = listOf(
            member("node-a", 0.5, 4.0, 100, 200),
            member("node-b", null, 4.0, 50, null),
            member("node-c", 1.0, 0.0, null, 100),
            member("node-d", 0.25, 4.0, 10, 50),
        )
        val aggregate = clusterAggregate(members)
        assertEquals(0.375, aggregate.cpuFraction)
        assertEquals(160L, aggregate.memoryBytes)
        assertEquals(350L, aggregate.memoryLimitBytes)
        assertNull(clusterAggregate(listOf(member("node-a", null, null, null, null))).cpuFraction)
        assertNull(clusterAggregate(listOf(member("node-a", 0.5, 0.0, null, null))).cpuFraction)
        assertNull(clusterAggregate(emptyList()).memoryBytes)
        val totals = fleetTotals(Overview(9, listOf(
            guest(remote, 1, "running"),
            guest(remote, 2, "stopped"),
            guest(remote, 3, "running", template = true),
            guest(remote, 4, "paused"),
        ), members = members))
        assertEquals(1, totals.running)
        assertEquals(1, totals.stopped)
        assertEquals(1, totals.templates)
        assertEquals(4, totals.guests)
        assertEquals(4, totals.nodes)
        assertEquals(0.375, totals.cpuFraction)
        assertEquals(160L, totals.memoryBytes)
        assertEquals(350L, totals.memoryLimitBytes)
        assertEquals(9, fleetTotals(Overview(9, emptyList())).nodes)
    }

    @Test fun tagsParseSeparatorsLimitsAndControls() {
        assertEquals(emptyList(), parseResourceTags(null as String?))
        assertEquals(emptyList(), parseResourceTags(" ; , \t "))
        assertEquals(emptyList(), parseResourceTags("\u0001\u0002"))
        assertEquals(listOf("a", "b", "cd"), parseResourceTags(" a;b, a  c${'\u0001'}d ,, "))
        assertEquals(listOf("a", "b"), parseResourceTags("a\nb"))
        assertEquals("x".repeat(40), parseResourceTags("x".repeat(41)).single())
        val many = (1..17).joinToString(";") { "t$it" }
        assertEquals((1..16).map { "t$it" }, parseResourceTags(many))
        val shared = "a".repeat(40)
        assertEquals(listOf(shared), parseResourceTags("${shared}a;$shared"))
        val direct = parseOverview(
            """{"data":[{"type":"qemu","vmid":7,"name":"web","node":"pve1","status":"running","tags":" web; db, web ","cpu":0.25,"mem":5,"maxmem":9,"netin":1000,"netout":250}]}""",
            remoteA,
        ).guests.single()
        assertEquals(listOf("web", "db"), direct.tags)
        assertEquals(1000L, direct.metrics?.netIn)
        assertEquals(250L, direct.metrics?.netOut)
        val absent = parseOverview(
            """{"data":[{"type":"lxc","vmid":8,"name":"ct","node":"pve1","status":"stopped","tags":1,"netin":-5,"netout":"12"}]}""",
            remoteA,
        ).guests.single()
        assertEquals(emptyList(), absent.tags)
        assertNull(absent.metrics?.netIn)
        assertNull(absent.metrics?.netOut)
        val origin = "https://pdm.example.test:8443"
        val pdm = parsePdmResources(
            """{"data":[{"remote":"aa","resources":[{"type":"pve-qemu","vmid":7,"name":"web","node":"node-a","status":"running","tags":"app, app;db","netin":80,"netout":20}]}]}""",
            origin,
        ).single().guests.single()
        assertEquals(listOf("app", "db"), pdm.tags)
        assertEquals(80L, pdm.metrics?.netIn)
        assertEquals(20L, pdm.metrics?.netOut)
    }

    @Test fun demoWalkStaysInBoundsAndMatchesItsSeed() {
        val seed = 42L
        var overview = demoOverview
        val random = Random(seed)
        var previousIn = 0L
        var previousOut = 0L
        repeat(40) {
            overview = stepDemoOverview(overview, random)
            overview.guests.forEach { guest ->
                if (guest.status != "running") {
                    assertNull(guest.metrics?.cpuFraction)
                    assertNull(guest.metrics?.memoryBytes)
                } else {
                    val metrics = guest.metrics!!
                    assertTrue(metrics.cpuFraction!! in 0.0..1.0)
                    assertTrue(metrics.memoryBytes!! in 0L..metrics.memoryLimitBytes!!)
                    assertTrue(metrics.netIn!! > 0L)
                    assertTrue(metrics.netOut!! > 0L)
                }
            }
            val running = overview.guests.first { it.id == 120 }.metrics!!
            assertTrue(running.netIn!! > previousIn)
            assertTrue(running.netOut!! > previousOut)
            previousIn = running.netIn
            previousOut = running.netOut
            overview.members.forEach { member ->
                member.cpuFraction?.let { assertTrue(it in 0.0..1.0) }
                val used = member.memoryBytes
                val max = member.maxMemoryBytes
                if (used != null && max != null) assertTrue(used in 0L..max)
            }
            assertNull(overview.members.single { it.name == "demo-node-b" }.memoryBytes)
        }
        var again = demoOverview
        val repeatRandom = Random(seed)
        repeat(40) { again = stepDemoOverview(again, repeatRandom) }
        assertEquals(overview, again)
    }

    @Test fun demoTickMatchesTheSeededWalk() = runTest {
        val seed = 21L
        val scope = this
        val workspace = Workspace(this, object : InventoryGateway {
            override suspend fun overview(connection: Connection) = error("Unexpected network request")
            override suspend fun details(connection: Connection, guest: Guest) = error("Unexpected network request")
        }, clock = { Instant.ofEpochMilli(scope.currentTime) }, demoSeed = seed)
        try {
            workspace.exploreDemo()
            assertTrue(workspace.state.value.live)
            val selected = workspace.state.value.overview!!.guests.first()
            workspace.select(selected)
            workspace.inspectGuest()
            val inspection = workspace.state.value.inspection
            workspace.setLive(true, 2); runCurrent()
            step(2_000)
            val random = Random(seed)
            val first = stepDemoOverview(demoOverview, random)
            assertEquals(first, workspace.state.value.overview)
            assertEquals(inspection, workspace.state.value.inspection)
            assertEquals(selected.resource, workspace.state.value.selected?.resource)
            assertFalse(workspace.state.value.loading)
            assertNotNull(workspace.state.value.details)
            assertNull(workspace.state.value.overview!!.guests.single { it.id == 130 }.metrics?.cpuFraction)
            step(2_000)
            val second = stepDemoOverview(first, random)
            assertEquals(second, workspace.state.value.overview)
            assertEquals(inspection, workspace.state.value.inspection)
            assertFalse(workspace.state.value.loading)
        } finally { workspace.close() }
    }

    @Test fun setLiveFalseStopsTicks() = runTest {
        var calls = 0
        val fake = Fake()
        fake.inventory = { calls++; overview(remoteA) }
        val workspace = Workspace(this, fake)
        try {
            workspace.connect(connection()); runCurrent()
            workspace.setLive(true, 60)
            workspace.setLive(false); runCurrent()
            assertFalse(workspace.state.value.live)
            assertEquals(60, workspace.state.value.liveIntervalSeconds)
            val baseline = calls
            step(120_000)
            assertEquals(baseline, calls)
            assertFalse(workspace.state.value.live)
        } finally { workspace.close() }
    }

    @Test fun intervalChoicesAndLabels() {
        assertEquals(listOf(5, 10, 30, 120), LIVE_INTERVAL_CHOICES)
        assertEquals(5, LIVE_INTERVAL_DEFAULT)
        assertEquals(listOf("5s", "10s", "30s", "2m"), LIVE_INTERVAL_CHOICES.map(::liveIntervalLabel))
        assertTrue(LIVE_INTERVAL_CHOICES.all { clampLiveInterval(it) == it })
    }

    @Test fun intervalClampsToTwoThroughTwoMinutes() = runTest {
        var calls = 0
        val fake = Fake()
        fake.inventory = { calls++; overview(remoteA) }
        val workspace = Workspace(this, fake)
        try {
            workspace.connect(connection()); runCurrent()
            workspace.setLive(true, 1); runCurrent()
            assertEquals(2, workspace.state.value.liveIntervalSeconds)
            val afterClamp = calls
            step(2_000)
            assertEquals(afterClamp + 1, calls)
            workspace.setLive(true, 500); runCurrent()
            assertEquals(120, workspace.state.value.liveIntervalSeconds)
            val afterWide = calls
            step(2_000)
            assertEquals(afterWide, calls)
            step(118_000)
            assertEquals(afterWide + 1, calls)
        } finally { workspace.close() }
    }

    @Test fun liveTasksStayQuietUntilTheSessionExpires() = runTest {
        var calls = 0
        var phase = "ok"
        val tasks = object : TaskGateway {
            override suspend fun tasks(connection: Connection): List<ClusterTask> {
                calls++
                when (phase) {
                    "denied" -> throw GuestPermissionException()
                    "expired" -> throw SessionExpiredException()
                    else -> return emptyList()
                }
            }
        }
        val workspace = Workspace(this, Fake(), tasks)
        try {
            workspace.connect(connection()); runCurrent()
            workspace.setLiveTasks(true); runCurrent()
            assertEquals(0, calls)
            step(10_000)
            assertEquals(1, calls)
            assertEquals(emptyList(), workspace.state.value.tasks)
            assertFalse(workspace.state.value.tasksLoading)
            phase = "denied"
            step(10_000)
            assertTrue(workspace.state.value.connected)
            assertTrue(workspace.state.value.tasksStale)
            assertFalse(workspace.state.value.tasksLoading)
            phase = "expired"
            step(10_000)
            assertFalse(workspace.state.value.connected)
            val baseline = calls
            step(30_000)
            assertEquals(baseline, calls)
        } finally { workspace.close() }
    }

    @Test fun pdmTickUsesTheExistingRead() = runTest {
        val origin = "https://pdm.example.test:8443"
        var calls = 0
        var directCalls = 0
        val guest = Guest(origin, 7, "web", "node-a", "VM", "running", scope = "aa", metrics = GuestListMetrics(cpuFraction = 0.2, memoryBytes = 10, memoryLimitBytes = 40, netIn = 5, netOut = 1), tags = listOf("web"))
        val document = PdmFleetDocument("8", "1", "abc", listOf(PdmRemoteRecord("aa", "pve")), listOf(PdmResourceGroup("aa", null, listOf(guest))))
        val pdm = object : PdmReadGateway {
            override suspend fun read(connection: Connection): PdmFleetDocument {
                calls++
                return document
            }
        }
        val direct = object : InventoryGateway {
            override suspend fun overview(connection: Connection): Overview { directCalls++; error("direct") }
            override suspend fun details(connection: Connection, guest: Guest): GuestDetails { directCalls++; error("direct") }
        }
        val workspace = Workspace(this, direct, pdmGateway = pdm)
        try {
            workspace.connect(connection(origin, kind = PDM_KIND)); runCurrent()
            assertEquals(1, calls)
            assertEquals(0, directCalls)
            workspace.select(workspace.state.value.overview!!.guests.single()); runCurrent()
            val inspection = workspace.state.value.inspection
            workspace.setLive(true, 2); runCurrent()
            step(2_000)
            assertEquals(2, calls)
            assertEquals(0, directCalls)
            assertEquals(listOf("web"), workspace.state.value.selected?.tags)
            assertEquals(inspection, workspace.state.value.inspection)
            assertFalse(workspace.state.value.loading)
            assertFalse(workspace.state.value.detailsLoading)
            assertNotNull(workspace.state.value.history.guests[guest.resource])
        } finally { workspace.close() }
    }
}
