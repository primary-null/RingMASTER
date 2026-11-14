package local.ringleader.core

import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task feed, scheduler feed, the WorkspaceState adapters and the runtime that connects them. */
@OptIn(ExperimentalCoroutinesApi::class)
class WatcherFeedsTest {
    private val origin = "https://pve.example.test:8006"
    private val account = "watch@pve!ro"
    private val t0 = Instant.parse("2026-11-13T10:00:00Z")
    private val scope = WatchScope.of(ScheduleFixtures.PROFILE_ID, DIRECT_PVE_KIND, origin, account, null)
    private val gib = 1024L * 1024 * 1024

    private inner class Rig(val clock: ManualWatchClock = ManualWatchClock(t0)) {
        val sink = RecordingSink()
        val watcher = Watcher(WatchStore(Files.createTempDirectory("feeds").resolve("watcher.json")), sink, clock).also { it.load() }
        var session = WatchSession(scope, 1, 1).also { watcher.open(it) }
        fun tasks(vararg tasks: ClusterTask, local: Set<Int> = emptySet(), on: WatchSession = session): WatchObservation {
            clock.advance(10_000)
            return watcher.observeTasks(on, WatchTaskReading.Fresh(clock.wall, tasks.toList(), local))
        }
        fun reconnect() { watcher.close(session); session = session.copy(epoch = session.epoch + 1); watcher.open(session) }
    }

    private fun task(n: Int, type: String, id: String = "101", status: String? = "running", user: String = account, remote: String = origin): ClusterTask {
        val ended = if (status == null || status == "running") null else t0
        val upid = "UPID:node-a:%08X:00000011:65ABCDEF:$type:$id:$user:".format(n)
        return ClusterTask(remote, upid, "node-a", type, id, user, t0.minusSeconds(60), ended, status, classifyTask(status, ended))
    }

    // ---- tasks ----

    @Test fun watchIdentityTravelsWithTheSnapshotAcrossReconnect() = runTest {
        val h = Harness(this, origin)
        h.workspace.connect(origin, account, "snapshot-test-secret", "")
        val old = h.workspace.state.value
        h.workspace.connect(origin, "other@pve!ro", "replacement-test-secret", "")
        val current = h.workspace.state.value
        assertEquals(account, watchScopeOf(old)?.account)
        assertEquals("other@pve!ro", watchScopeOf(current)?.account)
        assertTrue(old.connectionEpoch != current.connectionEpoch)
        h.workspace.disconnect()
        assertNull(h.workspace.watchScope())
        assertFalse("snapshot-test-secret" in old.toString())
    }

    @Test fun aMigrationWithWarningsNeverUsesSuccessSeverity() {
        val rig = Rig(); rig.tasks()
        rig.tasks(task(1, "qmigrate", status = "WARNINGS: 1"))
        val migration = rig.sink.events.single { it.category == NotificationCategory.Migration }
        assertEquals(NotificationSeverity.Warning, migration.severity)
        assertTrue("with warnings" in migration.body)
    }

    @Test fun theFirstTaskReadingIsBacklogAndAnnouncesNothing() {
        val rig = Rig()
        rig.tasks(task(1, "vzdump", status = "job errors"), task(2, "qmstart", status = "unexpected status"), task(3, "qmcreate", status = "OK"), task(4, "vzdump"))
        assertTrue(rig.sink.events.isEmpty(), "what finished before RingMASTER looked is history")
        // The same list again, and again: still nothing.
        repeat(3) { rig.tasks(task(1, "vzdump", status = "job errors"), task(2, "qmstart", status = "unexpected status"), task(3, "qmcreate", status = "OK"), task(4, "vzdump")) }
        assertTrue(rig.sink.events.isEmpty())
        // The backup that was running at baseline fails later: that is news.
        rig.tasks(task(1, "vzdump", status = "job errors"), task(4, "vzdump", status = "job errors"))
        assertEquals(listOf("Backup failed"), rig.sink.titles())
        assertEquals(NotificationCategory.BackupFailure, rig.sink.events.single().category)
    }

    @Test fun aFailureIsAnnouncedExactlyOnceAcrossRepeatedSnapshots() {
        val rig = Rig()
        rig.tasks()
        rig.tasks(task(1, "qmstart"))
        assertTrue(rig.sink.events.isEmpty(), "a running task is not a result")
        repeat(4) { rig.tasks(task(1, "qmstart", status = "start failed: no such device")) }
        val event = rig.sink.events.single()
        assertEquals("Task failed: qmstart", event.title)
        assertEquals("101 on node node-a. Open Activity for the task's status.", event.body)
        assertEquals(NotificationCategory.TaskFailure, event.category); assertEquals(NotificationSeverity.Error, event.severity)
        assertFalse("no such device" in event.title + event.body, "the server's status text is not copied")
        assertEquals(NotificationTarget.Task(scope.profileId, scope.binding, task(1, "qmstart").upid), event.target)
        // A task first seen already failed (it started and ended between two reads) is announced once too.
        rig.sink.take()
        repeat(3) { rig.tasks(task(1, "qmstart", status = "start failed"), task(2, "vzstart", id = "200", status = "exit code 1")) }
        assertEquals(listOf("Task failed: vzstart"), rig.sink.titles())
    }

    @Test fun identitiesAreDeterministicSoAResetCannotDuplicate() {
        val a = Rig(); val b = Rig()
        listOf(a, b).forEach { it.tasks(); it.tasks(task(1, "vzdump", status = "interrupted")) }
        assertEquals(a.sink.events.single().identity.id, b.sink.events.single().identity.id)
        // After a reconnect the feed is baseline again: the old failure is not announced a second time.
        a.sink.take(); a.reconnect()
        a.tasks(task(1, "vzdump", status = "interrupted"))
        a.tasks(task(1, "vzdump", status = "interrupted"))
        assertTrue(a.sink.events.isEmpty())
        // Another connection's identical UPID is another identity.
        val other = WatchScope.of(null, DIRECT_PVE_KIND, "https://other.example.test:8006", account, null)
        val events = taskEvents(other, task(1, "vzdump", status = "interrupted", remote = "https://other.example.test:8006"), TaskOutcome.Running, false, false, emptySet())
        assertFalse(events.single().identity.id == b.sink.events.single().identity.id)
    }

    @Test fun onlyAnOkExitIsDescribedAsFinished() {
        val rig = Rig()
        rig.tasks()
        rig.tasks(task(1, "qmcreate", id = "120"), task(2, "vzclone", id = "121"), task(3, "qmigrate", id = "122"), task(4, "qmstart", id = "123"), task(5, "vzdump", id = "124"))
        assertTrue(rig.sink.events.isEmpty())
        rig.tasks(task(1, "qmcreate", id = "120", status = "OK"), task(2, "vzclone", id = "121", status = "OK"), task(3, "qmigrate", id = "122", status = "OK"),
            task(4, "qmstart", id = "123", status = "OK"), task(5, "vzdump", id = "124", status = "OK"))
        assertEquals(listOf("New VM 120", "New container 121", "Migration finished: 122"), rig.sink.titles(), "an ordinary OK task is not announced")
        assertEquals(listOf(NotificationCategory.NewGuest, NotificationCategory.NewGuest, NotificationCategory.Migration), rig.sink.events.map { it.category })
        // No status, an unknown status, a status with an end time but "running": none is success or failure.
        rig.sink.take()
        val unclear = ClusterTask(origin, task(9, "qmcreate").upid, "node-a", "qmcreate", "101", account, t0, t0, "running", TaskOutcome.Unknown)
        rig.tasks(task(8, "qmcreate", id = "130", status = null), unclear)
        rig.tasks(task(8, "qmcreate", id = "130", status = null), unclear)
        assertTrue(rig.sink.events.isEmpty())
        // A create that fails is a failure, never a new guest.
        rig.tasks(task(8, "qmcreate", id = "130", status = "unable to create VM 130"))
        assertEquals(listOf("Task failed: qmcreate"), rig.sink.titles())
    }

    @Test fun warningsAreTheirOwnCategoryAndStillFinish() {
        val rig = Rig()
        rig.tasks()
        rig.tasks(task(1, "vzcreate", id = "140", status = "WARNINGS: 1"))
        rig.tasks(task(1, "vzcreate", id = "140", status = "WARNINGS: 1"))
        assertEquals(listOf("New container 140", "Task finished with warnings: vzcreate"), rig.sink.titles())
        assertEquals("The create task finished on node node-a with warnings.", rig.sink.events[0].body)
        assertEquals(NotificationCategory.TaskWarnings, rig.sink.events[1].category)
    }

    @Test fun fencingPackageAndForeignPowerTasksAreSeenOnce() {
        val rig = Rig()
        rig.tasks()
        val list = arrayOf(
            task(1, "fence-node", id = ""), task(2, "aptupdate", id = ""), task(3, "qmstop", id = "150", user = "ops@pam"),
            task(4, "qmstart", id = "151", user = account), task(5, "vzshutdown", id = "152", user = "ops@pam!ci"), task(6, "qmstop", id = "153", user = "ops@pam"),
        )
        repeat(3) { rig.tasks(*list, local = setOf(153)) }
        assertEquals(listOf(NotificationCategory.HaFencing, NotificationCategory.PackageUpdates, NotificationCategory.ExternalPower, NotificationCategory.ExternalPower), rig.sink.events.map { it.category })
        assertEquals("A task of type fence-node appeared on node node-a.", rig.sink.events[0].body, "only what the feed shows; no HA state is claimed")
        assertEquals(listOf("Guest 150: qmstop by another account", "Guest 152: vzshutdown by another account"), rig.sink.titles().drop(2),
            "this connection's own account and a guest this session acted on are not foreign")
        assertEquals("Started by ops@pam on node node-a.", rig.sink.events[3].body, "the token name is not shown")
    }

    @Test fun staleDuplicateForeignAndSessionlessTaskReadingsAreRefused() {
        val rig = Rig()
        rig.tasks()
        val failed = task(1, "vzdump", status = "failed")
        val reading = WatchTaskReading.Fresh(rig.clock.wall, listOf(failed))
        assertEquals(WatchObservation.Duplicate, rig.watcher.observeTasks(rig.session, reading), "same read time as the baseline")
        rig.clock.advance(200_000)
        assertEquals(WatchObservation.Stale, rig.watcher.observeTasks(rig.session, WatchTaskReading.Fresh(rig.clock.wall.minusSeconds(150), listOf(failed))))
        assertEquals(WatchObservation.WrongRemote, rig.tasks(task(2, "vzdump", status = "failed", remote = "https://other.example.test:8006")))
        assertEquals(WatchObservation.Accepted, rig.watcher.observeTasks(rig.session, WatchTaskReading.Failed))
        val old = rig.session
        rig.reconnect()
        assertEquals(WatchObservation.NoSession, rig.tasks(failed, on = old))
        assertTrue(rig.sink.events.isEmpty())
        // The refused readings did not consume the failure: on the live session it is still announced (after its baseline).
        rig.tasks(); rig.tasks(failed)
        assertEquals(1, rig.sink.events.size)
    }

    // ---- schedules ----

    private val plan = ScheduleFixtures.plan(id = "aaaaaaaa-2222-4333-8444-555555555555")
    private fun run(n: Int, phase: RunPhase, updatedAt: Instant = t0.plusSeconds(60), revision: Int = 1, due: Instant = t0.plusSeconds(n * 3600L), detail: String = "", missedEarlier: Int = 0) =
        ScheduleRun("00000000-0000-4000-8000-%012d".format(n), plan.id, revision, due, ScheduleAction.Start, "alpha (104)", phase, detail, updatedAt, missedEarlier = missedEarlier)
    private fun snapshot(vararg runs: ScheduleRun, loaded: Boolean = true, problem: String? = null) =
        SchedulerState(loaded = loaded, document = ScheduleDocument(listOf(plan), emptyList(), runs.toList()), problem = problem)

    @Test fun oldRunsAreBacklogAndLaterResultsPostExactlyOnce() {
        val rig = Rig()
        val before = t0.minusSeconds(3600)
        // History from earlier days, including failures: silent.
        rig.watcher.observeSchedules(snapshot(run(1, RunPhase.Failed, before), run(2, RunPhase.Missed, before), run(3, RunPhase.Succeeded, before)))
        assertTrue(rig.sink.events.isEmpty())
        // A new run walks through its phases; every snapshot is delivered several times.
        for (phase in listOf(RunPhase.Claimed, RunPhase.Dispatching, RunPhase.Accepted)) repeat(3) {
            rig.watcher.observeSchedules(snapshot(run(1, RunPhase.Failed, before), run(2, RunPhase.Missed, before), run(3, RunPhase.Succeeded, before), run(4, phase)))
        }
        assertTrue(rig.sink.events.isEmpty(), "a run in flight has no result")
        repeat(5) { rig.watcher.observeSchedules(snapshot(run(1, RunPhase.Failed, before), run(2, RunPhase.Missed, before), run(3, RunPhase.Succeeded, before), run(4, RunPhase.Succeeded))) }
        val event = rig.sink.events.single()
        assertEquals("${plan.name}: Start finished OK", event.title); assertEquals("alpha (104).", event.body)
        assertEquals(NotificationCategory.ScheduleOutcome, event.category); assertEquals(NotificationSeverity.Success, event.severity)
        assertEquals(NotificationTarget.ScheduleRun(plan.id, run(4, RunPhase.Succeeded).id), event.target)
    }

    @Test fun everyOutcomeKeepsItsOwnHonestWording() {
        val expected = linkedMapOf(
            RunPhase.Succeeded to "Start finished OK", RunPhase.Warnings to "Start finished with warnings", RunPhase.Failed to "Start failed",
            RunPhase.Rejected to "Start refused by the server", RunPhase.Submitted to "Start submitted, not confirmed",
            RunPhase.Unknown to "Start interrupted, result unknown", RunPhase.Missed to "Start missed", RunPhase.Skipped to "Start skipped",
            RunPhase.NotSent to "Start not sent", RunPhase.NoChange to "nothing to do", RunPhase.Cancelled to "run cancelled",
        )
        assertEquals(RunPhase.entries.filter { !it.inFlight }.toSet(), expected.keys, "every final phase has wording")
        val events = expected.keys.mapIndexed { index, phase -> phase to scheduleEvent(run(index + 1, phase, detail = "server said: secret-internal-detail"), plan.name) }.toMap()
        expected.forEach { (phase, words) -> assertEquals("${plan.name}: $words", events[phase]!!.title) }
        assertEquals(events.size, events.values.map { it.title }.distinct().size)
        // Only OK is success. Submitted, Unknown, Missed, Failed and the rest are problems, and none of them says OK or finished.
        assertEquals(listOf(RunPhase.Succeeded), events.filterValues { it.severity == NotificationSeverity.Success }.keys.toList())
        assertEquals(setOf(RunPhase.Succeeded, RunPhase.NoChange, RunPhase.Cancelled), events.filterValues { it.category == NotificationCategory.ScheduleOutcome }.keys)
        events.filterKeys { it != RunPhase.Succeeded && it != RunPhase.Warnings }.values.forEach { e ->
            assertFalse(" OK" in e.title || "finished" in e.title || "succeeded" in (e.title + e.body).lowercase(), e.title)
        }
        assertFalse(events[RunPhase.NoChange]!!.native); assertFalse(events[RunPhase.Cancelled]!!.native); assertTrue(events[RunPhase.Missed]!!.native)
        assertTrue(events.values.none { "secret-internal-detail" in it.title + it.body }, "the run's free-text detail is not copied")
        assertTrue("2 earlier runs also missed" in scheduleEvent(run(1, RunPhase.Missed, missedEarlier = 2), plan.name).body)
    }

    @Test fun identityTellsOccurrenceRevisionAndOutcomeApart() {
        val base = scheduleEvent(run(1, RunPhase.Failed), plan.name).identity
        assertEquals(base.id, scheduleEvent(run(1, RunPhase.Failed, detail = "other text", updatedAt = t0.plusSeconds(999)), "renamed").identity.id, "same occurrence, same identity")
        val different = listOf(
            scheduleEvent(run(1, RunPhase.Failed, due = t0.plusSeconds(86_400)), plan.name), scheduleEvent(run(1, RunPhase.Failed, revision = 2), plan.name),
            scheduleEvent(run(1, RunPhase.Missed), plan.name), scheduleEvent(run(1, RunPhase.Failed).copy(planId = "bbbbbbbb-2222-4333-8444-555555555555"), plan.name),
        )
        assertEquals(different.size + 1, (different.map { it.identity.id } + base.id).distinct().size)
        assertEquals(base.subjectId, different[0].identity.subjectId, "one plan is one subject to snooze")
    }

    @Test fun runsFinalisedAtStartupAreAnnouncedOnTheFirstSnapshot() {
        val rig = Rig()
        // The engine recovered an interrupted run and recorded a missed one after this Watcher was created.
        rig.clock.advance(2_000)
        val now = rig.clock.wall
        rig.watcher.observeSchedules(snapshot(run(1, RunPhase.Succeeded, t0.minusSeconds(86_400)), run(2, RunPhase.Unknown, now), run(3, RunPhase.Missed, now), run(4, RunPhase.Submitted, now)))
        assertEquals(listOf("Start interrupted, result unknown", "Start missed", "Start submitted, not confirmed"), rig.sink.titles().map { it.substringAfter(": ") })
        rig.watcher.observeSchedules(snapshot(run(1, RunPhase.Succeeded, t0.minusSeconds(86_400)), run(2, RunPhase.Unknown, now), run(3, RunPhase.Missed, now), run(4, RunPhase.Submitted, now)))
        assertEquals(3, rig.sink.events.size)
    }

    @Test fun anUnloadedOrBrokenScheduleStoreIsNotABaseline() {
        val rig = Rig()
        rig.watcher.observeSchedules(snapshot(loaded = false))
        rig.watcher.observeSchedules(snapshot(problem = "schedules.json is not valid JSON and was left unchanged."))
        // The real first snapshot still treats old runs as backlog and new ones as news.
        rig.watcher.observeSchedules(snapshot(run(1, RunPhase.Failed, t0.minusSeconds(60)), run(2, RunPhase.Claimed)))
        assertTrue(rig.sink.events.isEmpty())
        // A later snapshot with a store problem announces nothing and forgets nothing.
        rig.watcher.observeSchedules(snapshot(problem = "schedules.json changed outside RingMASTER."))
        rig.watcher.observeSchedules(snapshot(run(1, RunPhase.Failed, t0.minusSeconds(60)), run(2, RunPhase.Failed)))
        assertEquals(1, rig.sink.events.size)
    }

    @Test fun aRollbackOfTheWallClockDoesNotHideALaterResult() {
        val rig = Rig()
        rig.watcher.observeSchedules(snapshot(run(1, RunPhase.Claimed)))
        // The run finishes with a timestamp before the Watcher's start (the clock was set back): it is still a change seen live.
        rig.watcher.observeSchedules(snapshot(run(1, RunPhase.Failed, t0.minusSeconds(7200))))
        assertEquals(1, rig.sink.events.size)
    }

    // ---- WorkspaceState adapters ----

    private val listed = Guest(origin, 100, "alpha", "node-a", "VM", "running", metrics = GuestListMetrics(cpuFraction = 0.2, memoryBytes = gib, memoryLimitBytes = 4 * gib, uptimeSeconds = 5))
    private val memberA = RecordedMember(origin, "node-a", "online", null, 0.2, 4.0, gib, 8 * gib, 1, 10, 3)

    @Test fun inventoryAdapterReportsOnlySuccessfulReadsAsFresh() {
        assertNull(watchInventoryOf(WorkspaceState()), "not connected")
        val connected = WorkspaceState(connected = true, address = origin, connectionEpoch = 3)
        assertNull(watchInventoryOf(connected), "connected, nothing read yet")
        val good = connected.copy(overview = Overview(1, listOf(listed), members = listOf(memberA)), updatedAt = t0)
        val fresh = watchInventoryOf(good) as WatchInventoryReading.Fresh
        assertEquals(t0, fresh.readAt); assertTrue(fresh.nodesCovered); assertNull(fresh.coveredScopes)
        // Anything that is not a new successful read gives the very same reading: selection, loading flags, task updates.
        listOf(good.copy(selected = listed), good.copy(detailsLoading = true), good.copy(loading = true), good.copy(tasksUpdatedAt = t0.plusSeconds(5)), good.copy(powerNotice = "x")).forEach {
            assertEquals(fresh, watchInventoryOf(it))
        }
        // A failed refresh keeps the old rows on screen; they are not current facts.
        assertEquals(WatchInventoryReading.Failed, watchInventoryOf(good.copy(stale = true, error = "The server could not be reached.")))
        assertEquals(WatchInventoryReading.Failed, watchInventoryOf(connected.copy(error = "The server could not be reached.")))
        assertEquals(WatchInventoryReading.Failed, watchInventoryOf(good.copy(stale = true, loading = true)), "retrying does not make stale rows fresh")
        // A direct read whose node index failed does not cover nodes.
        assertFalse((watchInventoryOf(good.copy(overview = good.overview!!.copy(indexStale = true, indexError = "index"))) as WatchInventoryReading.Fresh).nodesCovered)
        // PDM: only remotes that answered are covered, and nodes never are.
        val pdm = good.copy(kind = PDM_KIND, overview = Overview(0, listOf(listed.copy(scope = "site-a")), remotes = listOf(FleetRemote("site-a", "pve"), FleetRemote("site-b", "pve", stale = true, error = "down"))))
        val fleet = watchInventoryOf(pdm) as WatchInventoryReading.Fresh
        assertEquals(setOf("site-a"), fleet.coveredScopes); assertFalse(fleet.nodesCovered)
    }

    @Test fun taskAdapterReportsOnlySuccessfulReadsAsFresh() {
        val connected = WorkspaceState(connected = true, address = origin)
        assertNull(watchTasksOf(WorkspaceState())); assertNull(watchTasksOf(connected), "the feed was never read (PDM, or Activity not opened)")
        val submission = PowerSubmission("local-1", origin, "node-a", 104, "VM", "alpha", PowerAction.Start, PowerPhase.Accepted, "Accepted.", simulated = false, checkable = true)
        val good = connected.copy(tasks = listOf(task(1, "vzdump")), tasksUpdatedAt = t0, submissions = listOf(submission))
        val fresh = watchTasksOf(good) as WatchTaskReading.Fresh
        assertEquals(t0, fresh.readAt); assertEquals(setOf(104), fresh.localGuests)
        assertEquals(fresh, watchTasksOf(good.copy(tasksLoading = true, selected = listed)))
        assertEquals(WatchTaskReading.Failed, watchTasksOf(good.copy(tasksStale = true, tasksError = "The server could not be reached.")))
    }

    // ---- runtime ----

    private class Inventory(private val origin: String) : InventoryGateway {
        var cpu = 0.10
        var status = "running"
        var fail = false
        private var uptime = 100L
        override suspend fun overview(connection: Connection): Overview {
            if (fail) throw IOException("unreachable")
            val guest = Guest(origin, 100, "alpha", "node-a", "VM", status, metrics = GuestListMetrics(cpuFraction = cpu, memoryBytes = 1L shl 30, memoryLimitBytes = 4L shl 30, uptimeSeconds = uptime++))
            return Overview(1, listOf(guest), members = listOf(RecordedMember(origin, "node-a", "online", null, 0.1, 4.0, 1L shl 30, 8L shl 30, 1, 10, uptime)))
        }
        override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
    }

    private class Harness(val test: TestScope, origin: String) {
        val dir = Files.createTempDirectory("runtime")
        val inventory = Inventory(origin)
        val clock = object : WatchClock {
            override fun now(): Instant = Instant.ofEpochMilli(test.testScheduler.currentTime)
            override fun monotonicMillis(): Long = test.testScheduler.currentTime
        }
        val workspace = Workspace(test.backgroundScope, inventory, clock = { Instant.ofEpochMilli(test.testScheduler.currentTime) }).also { it.liveJitter = { 0.0 } }
        val center = NotificationCenter(NotificationStore(dir.resolve("notifications.json"))) { clock.now() }
        val watcher = Watcher(WatchStore(dir.resolve("watcher.json")), center, clock)
        val runtime = WatcherRuntime(watcher, center, test.backgroundScope, StandardTestDispatcher(test.testScheduler))
        val profile = MutableStateFlow<String?>(null)
        fun step(ms: Long) { test.advanceTimeBy(ms); test.runCurrent() }
        fun watched() = watcher.state.value.watched.single()
        fun titles() = center.state.value.items.map { it.title }.reversed()
    }

    @Test fun theRuntimeFeedsLiveSessionsAndStopsWithThem() = runTest {
        val h = Harness(this, origin)
        val native = mutableListOf<NativeNotificationRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.center.native.toList(native) }
        h.runtime.attachWorkspace(1, h.workspace, h.profile)
        runCurrent()
        assertTrue(h.center.state.value.loaded && h.watcher.state.value.loaded)
        h.workspace.connect(origin, account, "runtime-test-secret", "")
        runCurrent()
        val live = h.workspace.watchScope(null)!!
        assertEquals(scope.binding, live.binding, "the scope is the connection's kind, origin, account and pin")
        val vm = WatchResource(WatchKind.VM, vmid = 100)
        assertEquals(WatchResult.Ok, h.watcher.watch(live, vm, "alpha"))
        h.step(30_000)
        assertEquals(WatchAvailability.Live, h.watched().availability); assertFalse(h.watched().stressed)
        // Load rises; the live loop (5 s) delivers it, and unrelated state changes in between add nothing.
        h.inventory.cpu = 0.93
        h.step(5_000)
        repeat(20) { h.workspace.select(h.workspace.state.value.overview!!.guests.single()); runCurrent() }
        assertFalse(h.watched().stressed, "one hot read, however often the state was re-emitted")
        h.step(5_000)
        assertTrue(h.watched().stressedNow)
        assertEquals(listOf("alpha (100) is under load"), h.titles())
        assertEquals("alpha (100) is under load", native.single().title)
        // The server becomes unreachable: nothing is cleared, nothing is announced.
        h.inventory.fail = true
        h.step(60_000)
        assertEquals(WatchAvailability.Stale, h.watched().availability); assertTrue(h.watched().stressed)
        // Disconnect while stressed, with the guest now calm on the server: the old session can say nothing more.
        h.inventory.fail = false; h.inventory.cpu = 0.05
        h.workspace.disconnect(); runCurrent()
        assertEquals(WatchAvailability.NotConnected, h.watched().availability)
        h.step(120_000)
        assertEquals(1, h.center.state.value.items.size)
        // Reconnect: counting starts again, and the recovery is announced only after it held.
        h.workspace.connect(origin, account, "runtime-test-secret", "")
        h.step(10_000)
        assertEquals(WatchAvailability.Live, h.watched().availability)
        h.step(120_000)
        assertFalse(h.watched().stressed)
        assertEquals(listOf("alpha (100) is under load", "alpha (100) is back to normal"), h.titles())
        // The secret never reached a file.
        Files.list(h.dir).use { files -> files.forEach { assertFalse("runtime-test-secret" in Files.readString(it)) } }
        // Closing the runtime ends the session for the Watcher.
        h.runtime.close(); runCurrent()
        assertEquals(WatchAvailability.NotConnected, h.watched().availability)
    }

    @Test fun demoModeIsWatchedInMemoryAndLeavesNothingBehind() = runTest {
        val h = Harness(this, origin)
        val native = mutableListOf<NativeNotificationRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.center.native.toList(native) }
        h.runtime.attachWorkspace(1, h.workspace, h.profile)
        runCurrent()
        h.workspace.exploreDemo(); runCurrent()
        val guest = h.workspace.state.value.overview!!.guests.first { it.status == "running" }
        assertEquals(WatchResult.Ok, h.watcher.watch(WatchScope.Demo, watchResourceOf(guest)!!, guest.name))
        h.step(20_000)
        assertEquals(WatchAvailability.Live, h.watched().availability)
        // A demo event is shown in the window only.
        assertEquals(PostResult.Posted, h.center.post(NotificationEvent(NotificationIdentity("watch", DEMO_WATCH_BINDING, "x", "y"), NotificationCategory.WatchStress,
            NotificationSeverity.Warning, "Demo guest is under load", "CPU 99 %", demo = true)))
        assertTrue(native.isEmpty())
        h.workspace.disconnect(); runCurrent()
        assertTrue(h.watcher.state.value.watched.isEmpty(), "demo watches end with the demo")
        assertTrue(h.center.state.value.items.isEmpty(), "and so do its notifications")
        assertFalse(Files.exists(h.dir.resolve("watcher.json"))); assertFalse(Files.exists(h.dir.resolve("notifications.json")))
    }

    @Test fun schedulerSnapshotsReachTheCenterOnceThroughTheRuntime() = runTest {
        val h = Harness(this, origin)
        val scheduler = MutableStateFlow(SchedulerState())
        h.runtime.attachScheduler(scheduler)
        runCurrent()
        h.step(1_000)
        fun at(seconds: Long) = h.clock.now().plusSeconds(seconds)
        scheduler.value = snapshot(run(1, RunPhase.Failed, Instant.EPOCH.minusSeconds(999)), run(2, RunPhase.Claimed, at(0)))
        runCurrent()
        assertTrue(h.center.state.value.items.isEmpty())
        scheduler.value = snapshot(run(1, RunPhase.Failed, Instant.EPOCH.minusSeconds(999)), run(2, RunPhase.Submitted, at(0)))
        runCurrent()
        scheduler.value = snapshot(run(1, RunPhase.Failed, Instant.EPOCH.minusSeconds(999)), run(2, RunPhase.Submitted, at(0)), run(3, RunPhase.Missed, at(0)))
        runCurrent()
        assertEquals(listOf("Start submitted, not confirmed", "Start missed"), h.titles().map { it.substringAfter(": ") })
        // A restart with the same history: a new Watcher over the same notification file posts nothing twice.
        val again = NotificationCenter(NotificationStore(h.dir.resolve("notifications.json"))) { h.clock.now() }.also { it.load() }
        val watcher = Watcher(WatchStore(h.dir.resolve("watcher.json")), again, h.clock).also { it.load() }
        watcher.observeSchedules(snapshot(run(1, RunPhase.Failed, Instant.EPOCH.minusSeconds(999)), run(2, RunPhase.Submitted, at(5)), run(3, RunPhase.Missed, at(5))))
        assertEquals(2, again.state.value.items.size)
    }
}
