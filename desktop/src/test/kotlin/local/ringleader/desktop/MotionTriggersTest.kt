package local.ringleader.desktop

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import local.ringleader.core.ClusterTask
import local.ringleader.core.DEMO_REMOTE_KEY
import local.ringleader.core.PowerAction
import local.ringleader.core.PowerPhase
import local.ringleader.core.PowerSubmission
import local.ringleader.core.ResourceId
import local.ringleader.core.TaskIdentity
import local.ringleader.core.TaskLogLine
import local.ringleader.core.TaskOutcome
import local.ringleader.core.WorkspaceState
import local.ringleader.core.classifyTask
import local.ringleader.core.demoOverview
import local.ringleader.core.GuestListMetrics

/** Pure tests: which moments fire between two states, and never because a refresh redrew the list. */
class MotionTriggersTest {
    private val vm120 = ResourceId(DEMO_REMOTE_KEY, "", "VM", 120)
    private val ct121 = ResourceId(DEMO_REMOTE_KEY, "", "Container", 121)
    private val vm130 = ResourceId(DEMO_REMOTE_KEY, "", "VM", 130)

    private fun sub(id: String, resource: ResourceId, action: PowerAction, phase: PowerPhase) = SubmissionFact(id, resource, action, phase)
    private fun facts(
        subs: List<SubmissionFact> = emptyList(), tasks: Map<TaskIdentity, TaskFact> = emptyMap(),
        status: Map<ResourceId, String> = mapOf(vm120 to "stopped", ct121 to "running", vm130 to "stopped"), epoch: Long = 1,
    ) = MotionFacts(epoch, subs, tasks, status)
    private fun task(upid: String) = TaskIdentity(DEMO_REMOTE_KEY, "demo:$upid")
    private fun kinds(old: MotionFacts?, new: MotionFacts) = momentsBetween(old, new).map { it.kind to it.resource }

    // ---- nothing fires without a real change --------------------------------------------------------------------

    @Test fun theFirstSnapshotAndANewConnectionOnlySetTheBaseline() {
        val busy = facts(subs = listOf(sub("local-1", vm120, PowerAction.Start, PowerPhase.Accepted)), tasks = mapOf(task("a") to TaskFact(vm120, "qmstart", TaskOutcome.Running)))
        assertTrue(momentsBetween(null, busy).isEmpty())
        assertTrue(momentsBetween(facts(epoch = 1), busy.copy(epoch = 2)).isEmpty(), "a different connection compares with nothing")
    }

    @Test fun aRefreshThatOnlyMovesNumbersFiresNothing() {
        val guests = demoOverview.guests
        fun state(cpu: Double) = WorkspaceState(connected = true, demo = true, connectionEpoch = 7, overview = demoOverview.copy(guests = guests.map { it.copy(metrics = GuestListMetrics(cpuFraction = cpu, memoryBytes = (cpu * 1e9).toLong())) }))
        val first = motionFacts(state(.10)); val second = motionFacts(state(.37))
        assertEquals(first, second, "metrics are not part of the facts")
        assertTrue(momentsBetween(first, second).isEmpty())
        // Same tasks in another order, as the 10 s feed may return them, is not a change either.
        val a = task("a") to TaskFact(vm120, "qmstart", TaskOutcome.Running); val b = task("b") to TaskFact(ct121, "vzdump", TaskOutcome.Running)
        assertEquals(facts(tasks = linkedMapOf(a, b)), facts(tasks = linkedMapOf(b, a)))
        assertTrue(momentsBetween(facts(tasks = linkedMapOf(a, b)), facts(tasks = linkedMapOf(b, a))).isEmpty())
    }

    // ---- submissions ----------------------------------------------------------------------------------------------

    @Test fun aNewAcceptedOrSimulatedSubmissionStartsAMoment() {
        val before = facts()
        listOf(PowerPhase.Accepted, PowerPhase.Simulated, PowerPhase.Unknown).forEach { phase ->
            assertEquals(listOf(MomentKind.Started to vm120), kinds(before, facts(subs = listOf(sub("local-1", vm120, PowerAction.Start, phase)))), "$phase")
        }
    }

    @Test fun aSnapshotAlsoSnapsTheBrackets() {
        val moments = momentsBetween(facts(), facts(subs = listOf(sub("local-2", vm120, PowerAction.SnapshotCreate, PowerPhase.Accepted))))
        assertEquals(listOf(MomentKind.Started, MomentKind.Snapshot), moments.map { it.kind })
        assertTrue(moments.all { it.source == "submission:local-2" && it.resource == vm120 })
        assertEquals(listOf(MomentKind.Started), momentsBetween(facts(), facts(subs = listOf(sub("local-3", vm120, PowerAction.SnapshotDelete, PowerPhase.Accepted)))).map { it.kind })
    }

    @Test fun aRefusedSubmissionFailsAtOnceInsteadOfStarting() {
        listOf(PowerPhase.Rejected, PowerPhase.Unsuccessful).forEach { phase ->
            assertEquals(listOf(MomentKind.Failed to vm120), kinds(facts(), facts(subs = listOf(sub("local-1", vm120, PowerAction.Start, phase)))), "$phase")
        }
    }

    @Test fun anAcceptedSubmissionSucceedsOrFailsOnlyWhenItReachesATerminalPhase() {
        val accepted = facts(subs = listOf(sub("local-1", vm120, PowerAction.Start, PowerPhase.Accepted)))
        fun next(phase: PowerPhase) = facts(subs = listOf(sub("local-1", vm120, PowerAction.Start, phase)))
        assertEquals(listOf(MomentKind.Succeeded to vm120), kinds(accepted, next(PowerPhase.Succeeded)))
        assertEquals(listOf(MomentKind.Failed to vm120), kinds(accepted, next(PowerPhase.Unsuccessful)))
        assertTrue(momentsBetween(accepted, next(PowerPhase.Accepted)).isEmpty(), "no change")
        assertTrue(momentsBetween(accepted, next(PowerPhase.Unknown)).isEmpty(), "unknown is not an outcome")
        assertEquals("submission:local-1", momentsBetween(accepted, next(PowerPhase.Succeeded)).single().source)
    }

    // ---- tasks ------------------------------------------------------------------------------------------------------

    @Test fun aRunningTaskThatEndsSucceedsOrFailsOnce() {
        val running = facts(tasks = mapOf(task("a") to TaskFact(ct121, "vzdump", TaskOutcome.Running)))
        fun ended(outcome: TaskOutcome) = facts(tasks = mapOf(task("a") to TaskFact(ct121, "vzdump", outcome)))
        assertEquals(listOf(MomentKind.Succeeded to ct121), kinds(running, ended(TaskOutcome.Succeeded)))
        assertEquals(listOf(MomentKind.Failed to ct121), kinds(running, ended(TaskOutcome.Unsuccessful)))
        assertTrue(momentsBetween(running, ended(TaskOutcome.Unknown)).isEmpty())
        assertEquals("task:demo:a", momentsBetween(running, ended(TaskOutcome.Succeeded)).single().source)
        // Once it has ended, the next poll repeating the same row says nothing.
        assertTrue(momentsBetween(ended(TaskOutcome.Succeeded), ended(TaskOutcome.Succeeded)).isEmpty())
    }

    @Test fun aTaskThatShowsUpAlreadyFinishedCountsOnlyWhenTheUserWasWaitingOnThatGuest() {
        val finished = mapOf(task("a") to TaskFact(vm120, "qmstart", TaskOutcome.Succeeded))
        assertTrue(momentsBetween(facts(), facts(tasks = finished)).isEmpty(), "someone else's job that was never running here")
        val waiting = facts(subs = listOf(sub("local-1", vm120, PowerAction.Start, PowerPhase.Accepted)))
        assertEquals(listOf(MomentKind.Succeeded to vm120), kinds(waiting, waiting.copy(tasks = finished)), "a quick start the 10 s feed never saw running")
        assertTrue(momentsBetween(waiting, waiting.copy(tasks = mapOf(task("b") to TaskFact(ct121, "vzdump", TaskOutcome.Succeeded)))).isEmpty(), "another guest")
    }

    // ---- guests ------------------------------------------------------------------------------------------------------

    @Test fun aGuestThatStopsCollapsesGracefullyOrHard() {
        val running = facts(status = mapOf(vm120 to "running", ct121 to "running", vm130 to "stopped"))
        val stopped = running.copy(guestStatus = running.guestStatus + (vm120 to "stopped"))
        val graceful = momentsBetween(running, stopped).single()
        assertEquals(MomentKind.Stopped, graceful.kind); assertEquals(vm120, graceful.resource); assertFalse(graceful.hard)
        val hardOld = running.copy(submissions = listOf(sub("local-9", vm120, PowerAction.HardStop, PowerPhase.Accepted)))
        val hard = momentsBetween(hardOld, hardOld.copy(guestStatus = stopped.guestStatus)).single { it.kind == MomentKind.Stopped }
        assertTrue(hard.hard, "an accepted hard stop makes the cut hard")
        val shutdownOld = running.copy(submissions = listOf(sub("local-9", vm120, PowerAction.Shutdown, PowerPhase.Accepted)))
        assertFalse(momentsBetween(shutdownOld, shutdownOld.copy(guestStatus = stopped.guestStatus)).single { it.kind == MomentKind.Stopped }.hard)
        // An old hard stop that was already resolved does not make a later stop hard.
        val settled = running.copy(submissions = listOf(sub("local-9", vm120, PowerAction.HardStop, PowerPhase.Succeeded)))
        assertFalse(momentsBetween(settled, settled.copy(guestStatus = stopped.guestStatus)).single().hard)
    }

    @Test fun aStartedGuestArrivesOnlyWhenAStartWasPending() {
        val pending = facts(subs = listOf(sub("local-1", vm120, PowerAction.Start, PowerPhase.Accepted)))
        val up = pending.copy(guestStatus = pending.guestStatus + (vm120 to "running"))
        val arrived = momentsBetween(pending, up).single()
        assertEquals(MomentKind.Arrived, arrived.kind); assertEquals(vm120, arrived.resource); assertEquals("submission:local-1", arrived.source)
        val unasked = facts()
        assertTrue(momentsBetween(unasked, unasked.copy(guestStatus = unasked.guestStatus + (vm120 to "running"))).isEmpty(), "someone else started it")
        assertTrue(momentsBetween(facts(), facts().copy(guestStatus = facts().guestStatus - vm130)).isEmpty(), "a guest disappearing is not a moment")
        assertTrue(momentsBetween(facts(), facts().copy(guestStatus = facts().guestStatus + (ResourceId(DEMO_REMOTE_KEY, "", "VM", 999) to "running"))).isEmpty(), "a new guest is not a moment")
    }

    @Test fun aSuccessAndTheArrivalOfTheSameGuestAreAnnouncedOnce() {
        val pending = facts(subs = listOf(sub("local-1", vm120, PowerAction.Start, PowerPhase.Accepted)))
        val both = facts(subs = listOf(sub("local-1", vm120, PowerAction.Start, PowerPhase.Succeeded)), status = pending.guestStatus + (vm120 to "running"))
        assertEquals(listOf(MomentKind.Succeeded to vm120), kinds(pending, both), "Check status said OK and the inventory caught up in one step")
        // Another guest's arrival is still its own moment.
        val other = pending.copy(submissions = pending.submissions + sub("local-2", vm130, PowerAction.Start, PowerPhase.Accepted))
        val bothGuests = both.copy(submissions = both.submissions + sub("local-2", vm130, PowerAction.Start, PowerPhase.Accepted), guestStatus = both.guestStatus + (vm130 to "running"))
        assertEquals(setOf(MomentKind.Succeeded to vm120, MomentKind.Arrived to vm130), kinds(other, bothGuests).toSet())
    }

    // ---- what a row shows ---------------------------------------------------------------------------------------------

    @Test fun aGuestIsBusyWhileAnActionIsUnresolvedOrATaskRuns() {
        val accepted = facts(subs = listOf(sub("local-1", vm120, PowerAction.Start, PowerPhase.Accepted)))
        assertEquals(GuestActivity(true, PowerAction.Start, false), guestActivities(accepted)[vm120])
        assertEquals(true, guestActivities(facts(subs = listOf(sub("local-1", vm120, PowerAction.Reboot, PowerPhase.Unknown))))[vm120]?.inFlight, "unknown counts")
        assertNull(guestActivities(facts(subs = listOf(sub("local-1", vm120, PowerAction.Start, PowerPhase.Simulated))))[vm120], "a simulated action is never in flight")
        assertNull(guestActivities(facts(subs = listOf(sub("local-1", vm120, PowerAction.Start, PowerPhase.Succeeded))))[vm120], "still once it succeeded")
        val task = facts(tasks = mapOf(task("a") to TaskFact(ct121, "vzdump", TaskOutcome.Running)))
        assertEquals(true, guestActivities(task)[ct121]?.inFlight)
        assertNull(guestActivities(facts(tasks = mapOf(task("a") to TaskFact(ct121, "vzdump", TaskOutcome.Succeeded))))[ct121])
    }

    @Test fun theIndicatorGoesStillOnceTheGuestReachesTheStatusTheActionWaitedFor() {
        val startPending = facts(subs = listOf(sub("local-1", vm120, PowerAction.Start, PowerPhase.Accepted)))
        assertEquals(true, guestActivities(startPending)[vm120]?.inFlight)
        assertNull(guestActivities(startPending.copy(guestStatus = startPending.guestStatus + (vm120 to "running")))[vm120], "running now: the start is over, whatever Check status says")
        val stopPending = facts(subs = listOf(sub("local-2", ct121, PowerAction.Shutdown, PowerPhase.Accepted)))
        assertEquals(true, guestActivities(stopPending)[ct121]?.inFlight)
        assertNull(guestActivities(stopPending.copy(guestStatus = stopPending.guestStatus + (ct121 to "stopped")))[ct121])
        val reboot = facts(subs = listOf(sub("local-3", ct121, PowerAction.Reboot, PowerPhase.Accepted)))
        assertEquals(true, guestActivities(reboot)[ct121]?.inFlight, "reboot has no status to wait for, so it stays until resolved")
        assertEquals(listOf(null, null, "running"), listOf(expectedStatus(PowerAction.Reboot), expectedStatus(PowerAction.SnapshotCreate), expectedStatus(PowerAction.Start)?.single()))
    }

    @Test fun aFailedGuestKeepsItsRedEdgeUntilANewActionReplacesIt() {
        val failed = facts(subs = listOf(sub("local-1", vm120, PowerAction.Start, PowerPhase.Rejected)))
        assertEquals(GuestActivity(false, PowerAction.Start, true), guestActivities(failed)[vm120])
        val retried = facts(subs = listOf(sub("local-2", vm120, PowerAction.Start, PowerPhase.Accepted), sub("local-1", vm120, PowerAction.Start, PowerPhase.Rejected)))
        assertEquals(false, guestActivities(retried)[vm120]?.failed)
        assertEquals(true, guestActivities(retried)[vm120]?.inFlight)
    }

    @Test fun workspaceStateIsReducedToFactsWithTaskGuestsResolved() {
        val upid = "UPID:demo-node-a:0000BEEF:0000AAAA:00000001:qmstart:120:demo@ringmaster:"
        val ended = Instant.parse("2026-11-07T15:00:12Z")
        val tasks = listOf(
            ClusterTask(DEMO_REMOTE_KEY, "demo:qmstart:120", "demo-node-a", "qmstart", "120", "demo@ringmaster", ended.minusSeconds(12), ended, "OK", classifyTask("OK", ended).also { assertEquals(TaskOutcome.Succeeded, it) }),
            ClusterTask(DEMO_REMOTE_KEY, "demo:vzdump:121", "demo-node-a", "vzdump", "121", "demo@ringmaster", ended.minusSeconds(60), null, "running", TaskOutcome.Running),
            ClusterTask(DEMO_REMOTE_KEY, "demo:hastart:7", "demo-node-a", "hastart", "7", "demo@ringmaster", ended, null, null, TaskOutcome.Unknown),
        )
        val submission = PowerSubmission("local-abc", DEMO_REMOTE_KEY, "demo-node-a", 120, "VM", "debian-workbench", PowerAction.Start, PowerPhase.Accepted, "The server accepted this action. It is not finished.", false, true)
        val f = motionFacts(WorkspaceState(connected = true, demo = true, connectionEpoch = 3, overview = demoOverview, tasks = tasks, submissions = listOf(submission)))
        assertEquals(3L, f.epoch)
        assertEquals(listOf(SubmissionFact("local-abc", vm120, PowerAction.Start, PowerPhase.Accepted)), f.submissions)
        assertEquals(vm120, f.tasks[TaskIdentity(DEMO_REMOTE_KEY, "demo:qmstart:120")]?.resource, "qm… is a VM")
        assertEquals(ct121, f.tasks[TaskIdentity(DEMO_REMOTE_KEY, "demo:vzdump:121")]?.resource, "vzdump takes the kind from the guest list")
        assertNull(f.tasks[TaskIdentity(DEMO_REMOTE_KEY, "demo:hastart:7")]?.resource, "a subject that is no guest")
        assertEquals("running", f.guestStatus[vm130.copy(vmid = 121, kind = "Container")])
        assertEquals(setOf("submission:local-abc", "task:demo:vzdump:121"), inFlightSources(f))
        assertTrue(upid.startsWith("UPID:"))
    }

    // ---- the book: first seen, waited, quiet when the window is not active -------------------------------------------------

    private fun collect(book: MotionBook, block: () -> Unit): List<MotionMoment> {
        val got = mutableListOf<MotionMoment>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { book.moments.collect { got += it } }
        block()
        job.cancel()
        return got
    }

    @Test fun theBookMeasuresTheWaitFromTheFirstTimeItSawTheTaskInFlight() {
        var nanos = 1_000_000_000L
        val book = MotionBook { nanos }
        val running = facts(subs = listOf(sub("local-1", vm120, PowerAction.Start, PowerPhase.Accepted)))
        book.update(facts(), emit = true)
        book.update(running, emit = true)
        assertEquals(0L, book.waitedMs("submission:local-1"))
        nanos += 12_500_000_000L
        assertEquals(12_500L, book.waitedMs("submission:local-1"))
        val done = facts(subs = listOf(sub("local-1", vm120, PowerAction.Start, PowerPhase.Succeeded)))
        val got = collect(book) { book.update(done, emit = true) }
        assertEquals(1, got.size)
        assertEquals(MomentKind.Succeeded, got[0].kind); assertEquals(12_500L, got[0].waitedMs, "the moment carries how long the user waited")
        assertEquals(0L, book.waitedMs("submission:local-1"), "forgotten once it is no longer in flight")
        // ...and that wait picks the glow: 12 s is a long wait.
        assertEquals(WaitLevel.Long, afterglowPlan(got[0].waitedMs).level)
        assertEquals(0L, book.waitedMs(null)); assertEquals(0L, book.waitedMs("nothing"))
    }

    @Test fun anInactiveWindowMovesTheBaselineButAnnouncesNothing() {
        val book = MotionBook { 0L }
        book.update(facts(), emit = true)
        val quiet = collect(book) { book.update(facts(subs = listOf(sub("local-1", vm120, PowerAction.Start, PowerPhase.Accepted))), emit = false) }
        assertTrue(quiet.isEmpty())
        // The next change is compared with the silent one, so the Started above is not replayed.
        val next = collect(book) { book.update(facts(subs = listOf(sub("local-1", vm120, PowerAction.Start, PowerPhase.Succeeded))), emit = true) }
        assertEquals(listOf(MomentKind.Succeeded), next.map { it.kind })
    }

    @Test fun sparkLandingIsAMomentForItsGuest() {
        val book = MotionBook { 0L }
        val got = collect(book) { book.land(vm120); book.land(null) }
        assertEquals(listOf(MomentKind.Landed to vm120, MomentKind.Landed to null), got.map { it.kind to it.resource })
    }

    // ---- the percentage in a task log ----------------------------------------------------------------------------------

    private fun lines(vararg text: String) = text.mapIndexed { i, t -> TaskLogLine(i + 1, t) }

    @Test fun progressIsTheNewestPercentageInTheLog() {
        assertNull(taskProgress(emptyList()))
        assertNull(taskProgress(lines("INFO: starting new backup job: vzdump --mode snapshot 121", "INFO: Starting Backup of VM 121 (lxc)")))
        assertEquals(0.75f, taskProgress(lines("INFO: 0%: 0 B", "INFO: 50%: 640.0 MiB", "INFO: 75%: 960.0 MiB"))!!, 1e-4f)
        // What vzdump really prints.
        assertEquals(0.12f, taskProgress(lines("INFO: creating vzdump archive", "INFO:  12% (3.4 GiB of 28.0 GiB) in 3s, read: 1.1 GiB/s, write: 600.0 MiB/s"))!!, 1e-4f)
        assertEquals(0.004f, taskProgress(lines("drive-scsi0: transferred 12.3 MiB of 32.0 GiB (0.40%)"))!!, 1e-4f)
        assertEquals(1f, taskProgress(lines("INFO: 100%: 1.2 GiB", "INFO: archive file size: 1.2 GiB", "INFO: Finished Backup of VM 121 (00:01:12)", "TASK OK"))!!, 1e-4f, "the TASK line is not a percentage")
        assertNull(taskProgress(lines("INFO: storage is 250% over quota")), "values past 100 are not progress")
        assertEquals(0.5f, taskProgress(lines("INFO: 50%: half", "INFO: fan at 140% of nominal"))!!, 1e-4f, "an out-of-range number is skipped, not trusted")
    }
}
