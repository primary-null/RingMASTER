package local.ringleader.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Wall clock tied to virtual test time, plus an [offset] for clock jumps (sleep, NTP corrections, rollbacks). */
internal class VirtualClock(private val scheduler: TestCoroutineScheduler, private val base: Instant) : Clock() {
    var offset: Duration = Duration.ZERO
    override fun instant(): Instant = base.plusMillis(scheduler.currentTime).plus(offset)
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleEngineTest {
    // Friday 2026-11-20 05:55Z; a daily 07:00 Europe/Berlin plan is due at 06:00Z.
    private val base = Instant.parse("2026-11-20T05:55:00Z")
    private val due = Instant.parse("2026-11-20T06:00:00Z")

    private inner class Rig(val test: TestScope, val dir: Path = Files.createTempDirectory("schedules"), plans: List<SchedulePlan>? = null,
        val gateway: FakeScheduleGateway = FakeScheduleGateway(), val secrets: FakeScheduleSecrets = FakeScheduleSecrets(),
        var profiles: List<ConnectionProfile> = listOf(ScheduleFixtures.profile()), val leases: MutationLeases = MutationLeases(),
        val clock: VirtualClock = VirtualClock(test.testScheduler, base.minusMillis(test.testScheduler.currentTime)), seed: Boolean = true, access: Boolean = true) {
        val file: Path = dir.resolve("schedules.json")
        val plan = ScheduleFixtures.plan(notBefore = base)
        init {
            if (seed) ScheduleStore(file).apply { load(); save(ScheduleDocument(plans ?: listOf(plan),
                if (access) listOf(BackgroundAccess(ScheduleFixtures.PROFILE_ID, profileBinding(ScheduleFixtures.profile()), base)) else emptyList())) }
        }
        var store = ScheduleStore(file)
        fun engine(store: ScheduleStore = this.store, gateway: ScheduleGateway = this.gateway) = ScheduleEngine(store, { profiles }, secrets, gateway, leases, clock, test.backgroundScope,
            StandardTestDispatcher(test.testScheduler))
        val engine = engine()
        val runs get() = engine.state.value.document.runs
        fun step(ms: Long) { test.advanceTimeBy(ms); test.runCurrent() }
        fun text() = Files.readString(file)
    }

    @Test fun dueRunDispatchesOnceFollowsToOkAndNeverRepeatsAfterRestartOrRollback() = runTest {
        val rig = Rig(this)
        rig.engine.start(); runCurrent()
        assertTrue(rig.engine.state.value.loaded)
        assertEquals(ReadyLevel.Ready, rig.engine.state.value.readiness[rig.plan.id]!!.level)
        rig.step(Duration.between(base, due).toMillis() - 1000)
        assertEquals(0, rig.gateway.dispatches)
        rig.step(10_000)
        assertEquals(1, rig.gateway.dispatches)
        val run = rig.runs.single()
        assertEquals(RunPhase.Succeeded, run.phase)
        assertEquals(due, run.due)
        assertEquals(ScheduleFixtures.upid(), run.upid)
        assertEquals("node-a", run.node)
        assertFalse(rig.text().contains(ScheduleFixtures.SECRET))
        assertFalse(rig.text().contains(ScheduleFixtures.UUID_A))
        assertEquals(listOf(ScheduleFixtures.SECRET), rig.gateway.secretsSeen)
        // Clock rollback by two hours: the same due time comes around again and is not run.
        rig.clock.offset = Duration.ofHours(-2); rig.engine.poke(); rig.step(3 * 3600_000L)
        assertEquals(1, rig.gateway.dispatches)
        rig.engine.shutdown()
        // Restart on the same file: nothing is claimed twice.
        val again = rig.engine(ScheduleStore(rig.file))
        again.start(); rig.step(120_000)
        assertEquals(1, rig.gateway.dispatches)
        assertEquals(1, again.state.value.document.runs.size)
    }

    @Test fun sleepWithinGraceCatchesUpAndLongerSleepRecordsOneMissedWithoutAStorm() = runTest {
        val rig = Rig(this)
        rig.engine.start(); runCurrent()
        // Asleep across the due time; resumed 14 minutes later: inside the grace window, so it runs.
        rig.clock.offset = Duration.ofMinutes(19); rig.engine.poke(); rig.step(1_000)
        rig.step(10_000)
        assertEquals(1, rig.gateway.dispatches)
        assertEquals(RunPhase.Succeeded, rig.runs.single().phase)
        // Off for ten days: nine more occurrences passed; one Missed row folds them all.
        rig.clock.offset = Duration.ofDays(10).plusHours(1); rig.engine.poke(); rig.step(1_000)
        assertEquals(1, rig.gateway.dispatches)
        val missed = rig.runs.filter { it.phase == RunPhase.Missed }
        assertEquals(1, missed.size)
        assertEquals(9, missed.single().missedEarlier)
        assertTrue(missed.single().detail.contains("Nothing was sent"))
    }

    @Test fun sixteenMinutesLateIsMissed() = runTest {
        val rig = Rig(this)
        rig.engine.start(); runCurrent()
        rig.clock.offset = Duration.ofMinutes(21); rig.engine.poke(); rig.step(1_000)
        assertEquals(0, rig.gateway.dispatches)
        assertEquals(RunPhase.Missed, rig.runs.single().phase)
        assertEquals(0, rig.runs.single().missedEarlier)
    }

    @Test fun interruptedRunsRecoverWithoutSending() = runTest {
        val rig = Rig(this, seed = false)
        val plan = rig.plan
        fun run(phase: RunPhase, dueAt: Instant) = ScheduleRun(java.util.UUID.randomUUID().toString(), plan.id, 1, dueAt, ScheduleAction.Start, "alpha (104)", phase, "x", base,
            upid = if (phase == RunPhase.Accepted) ScheduleFixtures.upid() else null)
        ScheduleStore(rig.file).apply { load(); save(ScheduleDocument(listOf(plan.copy(lastHandledDue = due)), emptyList(),
            listOf(run(RunPhase.Claimed, due.minusSeconds(86400 * 3)), run(RunPhase.Dispatching, due.minusSeconds(86400 * 2)), run(RunPhase.Accepted, due.minusSeconds(86400))))) }
        val engine = rig.engine(ScheduleStore(rig.file))
        engine.start(); rig.step(120_000)
        val phases = engine.state.value.document.runs.sortedBy { it.due }.map { it.phase }
        assertEquals(listOf(RunPhase.NotSent, RunPhase.Unknown, RunPhase.Submitted), phases)
        assertEquals(ScheduleFixtures.upid(), engine.state.value.document.runs.single { it.phase == RunPhase.Submitted }.upid)
        assertEquals(0, rig.gateway.dispatches)
        assertTrue(rig.text().contains("\"Unknown\""))
    }

    @Test fun uncertainRefusedAndUnsentDispatchesAreNeverResent() = runTest {
        for ((thrown, phase) in listOf(ScheduleUncertainException() to RunPhase.Unknown, ScheduleRefusedException(401) to RunPhase.Rejected,
            ScheduleRefusedException(403) to RunPhase.Rejected, ScheduleNotSentException("The server could not be reached securely. Nothing was sent.") to RunPhase.NotSent)) {
            val rig = Rig(this)
            rig.gateway.dispatch = { throw thrown }
            rig.engine.start(); runCurrent()
            rig.step(Duration.between(base, due).toMillis() + 5_000)
            rig.step(3 * 3600_000L)
            assertEquals(1, rig.gateway.dispatches, "$thrown")
            assertEquals(phase, rig.runs.single().phase, "$thrown")
            assertEquals(0, rig.gateway.statusReads)
            if (thrown is ScheduleRefusedException && thrown.status == 401) assertTrue(rig.runs.single().detail.contains("not sent again"))
            rig.engine.shutdown()
        }
    }

    @Test fun preflightRefusalsSendNothing() = runTest {
        data class Case(val name: String, val phase: RunPhase, val text: String, val setup: (FakeScheduleGateway) -> Unit)
        val cases = listOf(
            Case("running", RunPhase.NoChange, "already running") { it.state = ScheduledGuestState("running", "running", null, false) },
            Case("template", RunPhase.Skipped, "template") { it.guests = mutableListOf(ScheduleFixtures.guest(template = true)) },
            Case("gone", RunPhase.Skipped, "no longer on this server") { it.guests = mutableListOf() },
            Case("permission", RunPhase.Skipped, "VM.PowerMgmt") { it.dump = PermissionDump(mapOf("/vms" to mapOf("VM.Audit" to true))) },
            Case("locked", RunPhase.Skipped, "locked (backup)") { it.state = ScheduledGuestState("stopped", null, "backup", false) },
            Case("paused", RunPhase.Skipped, "not stopped") { it.state = ScheduledGuestState("paused", "paused", null, false) },
        )
        for (case in cases) {
            val rig = Rig(this)
            case.setup(rig.gateway)
            rig.engine.start(); runCurrent()
            rig.step(Duration.between(base, due).toMillis() + 5_000)
            assertEquals(0, rig.gateway.dispatches, case.name)
            assertEquals(case.phase, rig.runs.single().phase, case.name)
            assertTrue(rig.runs.single().detail.contains(case.text), "${case.name}: ${rig.runs.single().detail}")
            rig.engine.shutdown()
        }
    }

    @Test fun refusedReadWithSavedTokenSkipsAndShutdownOfStoppedGuestIsNoChange() = runTest {
        val rig = Rig(this, plans = listOf(ScheduleFixtures.plan(notBefore = base, action = ScheduleAction.Shutdown)))
        val failing = object : ScheduleGateway by rig.gateway {
            override suspend fun guests(connection: Connection): List<Guest> = throw ScheduleRefusedException(401)
        }
        val engine = ScheduleEngine(ScheduleStore(rig.file), { rig.profiles }, rig.secrets, failing, rig.leases, rig.clock, backgroundScope, StandardTestDispatcher(testScheduler))
        engine.start(); runCurrent()
        rig.step(Duration.between(base, due).toMillis() + 5_000)
        assertEquals(RunPhase.Skipped, engine.state.value.document.runs.single().phase)
        assertTrue(engine.state.value.document.runs.single().detail.contains("HTTP 401"))
        assertEquals(0, rig.gateway.dispatches)
    }

    @Test fun guestIdentityChangeInvalidatesUntilReauthorized() = runTest {
        val rig = Rig(this)
        rig.gateway.facts = ScheduleFixtures.facts(uuid = "99999999-0000-4000-8000-000000000001")
        rig.engine.start(); runCurrent()
        rig.step(Duration.between(base, due).toMillis() + 5_000)
        assertEquals(0, rig.gateway.dispatches)
        assertEquals(RunPhase.Skipped, rig.runs.single().phase)
        val plan = rig.engine.state.value.document.plans.single()
        assertNotNull(plan.invalidReason)
        assertEquals(ReadyLevel.Review, rig.engine.state.value.readiness[plan.id]!!.level)
        // The next day is skipped without touching the network.
        val reads = rig.gateway.guestReads
        rig.step(Duration.ofDays(1).toMillis())
        assertEquals(reads, rig.gateway.guestReads)
        assertEquals(RunPhase.Skipped, rig.runs.maxBy { it.due }.phase)
        // Reauthorizing needs a fresh guest check; with it the plan is valid again.
        val draft = ScheduleDraft(plan.name, plan.action, RepeatKind.Daily, emptySet(), "", "07:00", "Europe/Berlin")
        val missing = rig.engine.savePlan(plan.id, draft, null, false, true, ScheduleFixtures.PROFILE_ID)
        assertTrue(missing is EngineResult.Error)
        val check = (rig.engine.inspectGuest(ScheduleFixtures.PROFILE_ID, ScheduleFixtures.guest()) as EngineResult.Ok).value
        val saved = rig.engine.savePlan(plan.id, draft, check, false, true, ScheduleFixtures.PROFILE_ID)
        assertTrue(saved is EngineResult.Ok, "$saved")
        assertNull(saved.value.invalidReason)
        assertEquals(2, saved.value.revision)
    }

    @Test fun connectionChangesAndCredentialProblemsBlockWithoutNetwork() = runTest {
        data class Case(val name: String, val level: ReadyLevel, val text: String, val setup: (Rig) -> Unit)
        val cases = listOf(
            Case("pin changed", ReadyLevel.Review, "changed") { it.profiles = listOf(ScheduleFixtures.profile(pin = "ab".repeat(32))) },
            Case("token changed", ReadyLevel.Review, "changed") { it.profiles = listOf(ScheduleFixtures.profile(token = "other@pve!runner")) },
            Case("pin removed", ReadyLevel.Review, "changed") { it.profiles = listOf(ScheduleFixtures.profile(pin = null)) },
            Case("deleted", ReadyLevel.Review, "deleted") { it.profiles = emptyList() },
            Case("not remembered", ReadyLevel.Blocked, "No secret is remembered") { it.secrets.remembered = false },
            Case("vault off", ReadyLevel.Blocked, "locked for test") { it.secrets.status = VaultStatus.Unavailable("The vault is locked for test.") },
        )
        for (case in cases) {
            val rig = Rig(this)
            case.setup(rig)
            rig.engine.start(); runCurrent()
            assertEquals(case.level, rig.engine.state.value.readiness[rig.plan.id]!!.level, case.name)
            assertTrue(rig.engine.state.value.readiness[rig.plan.id]!!.message.contains(case.text), "${case.name}: ${rig.engine.state.value.readiness[rig.plan.id]}")
            rig.step(Duration.between(base, due).toMillis() + 5_000)
            assertEquals(0, rig.gateway.guestReads, case.name)
            assertEquals(0, rig.secrets.reads, case.name)
            assertEquals(RunPhase.Skipped, rig.runs.single().phase, case.name)
            rig.engine.shutdown()
        }
        // No access granted at all.
        val rig = Rig(this, access = false)
        rig.engine.start(); runCurrent()
        assertTrue(rig.engine.state.value.readiness[rig.plan.id]!!.message.contains("Background access is off"))
        // Passwords and PDM never qualify.
        rig.profiles = listOf(ScheduleFixtures.profile(token = "root@pam"))
        rig.engine.refresh()
        assertFalse(rig.engine.state.value.access.single().canGrant)
        assertTrue(rig.engine.state.value.access.single().message.contains("API token"))
        assertNotNull(rig.engine.grantAccess(ScheduleFixtures.PROFILE_ID))
    }

    @Test fun removedSecretSkipsAtRunTime() = runTest {
        val rig = Rig(this)
        rig.secrets.secret = null
        rig.engine.start(); runCurrent()
        rig.step(Duration.between(base, due).toMillis() + 5_000)
        assertEquals(RunPhase.Skipped, rig.runs.single().phase)
        assertTrue(rig.runs.single().detail.contains("no longer in the test vault"))
        assertEquals(0, rig.gateway.guestReads)
    }

    @Test fun editOrDeleteBeforeSendingCancelsAndSendsNothing() = runTest {
        for (delete in listOf(false, true)) {
            val rig = Rig(this)
            rig.gateway.guestsGate = CompletableDeferred()
            rig.engine.start(); runCurrent()
            rig.step(Duration.between(base, due).toMillis() + 1_000)
            assertEquals(RunPhase.Claimed, rig.runs.single().phase)
            if (delete) assertNull(rig.engine.delete(rig.plan.id))
            else {
                val result = rig.engine.savePlan(rig.plan.id, ScheduleDraft("Moved", ScheduleAction.Start, RepeatKind.Daily, emptySet(), "", "08:00", "Europe/Berlin"), null, false, true, ScheduleFixtures.PROFILE_ID)
                assertTrue(result is EngineResult.Ok, "$result")
            }
            rig.gateway.guestsGate!!.complete(Unit)
            rig.step(5_000)
            assertEquals(0, rig.gateway.dispatches)
            assertEquals(RunPhase.Cancelled, rig.runs.single().phase)
            rig.engine.shutdown()
        }
    }

    @Test fun pauseAfterTheFinalStateReadStillCancelsBeforeDispatch() = runTest {
        val rig = Rig(this)
        rig.gateway.stateGate = CompletableDeferred()
        rig.engine.start(); runCurrent()
        rig.step(Duration.between(base, due).toMillis() + 1_000)
        assertEquals(1, rig.gateway.stateReads)
        assertTrue(rig.leases.scheduledHolds(GuestKey(ScheduleFixtures.ORIGIN, "VM", 104)))
        assertNull(rig.engine.setEnabled(rig.plan.id, false))
        rig.gateway.stateGate!!.complete(Unit)
        rig.step(5_000)
        assertEquals(0, rig.gateway.dispatches)
        assertEquals(RunPhase.Cancelled, rig.runs.single().phase)
        assertFalse(rig.leases.scheduledHolds(GuestKey(ScheduleFixtures.ORIGIN, "VM", 104)))
    }

    @Test fun foregroundMutationMakesTheRunWaitThenItRunsOrIsSkippedAfterGrace() = runTest {
        for (release in listOf(true, false)) {
            val rig = Rig(this)
            var busy: String? = "A foreground action on this guest is still running."
            val provider = ForegroundMutations { key -> if (key == GuestKey(ScheduleFixtures.ORIGIN, "VM", 104)) busy else null }
            rig.leases.register(provider)
            rig.engine.start(); runCurrent()
            rig.step(Duration.between(base, due).toMillis() + 1_000)
            rig.step(60_000)
            assertEquals(0, rig.gateway.dispatches)
            assertEquals(RunPhase.Claimed, rig.runs.single().phase)
            if (release) {
                busy = null
                rig.step(25_000)
                assertEquals(1, rig.gateway.dispatches)
                assertEquals(RunPhase.Succeeded, rig.runs.single().phase)
            } else {
                rig.step(Duration.ofMinutes(16).toMillis())
                assertEquals(0, rig.gateway.dispatches)
                assertEquals(RunPhase.Skipped, rig.runs.single().phase)
                assertTrue(rig.runs.single().detail.contains("grace window"))
            }
            assertFalse(rig.leases.scheduledHolds(GuestKey(ScheduleFixtures.ORIGIN, "VM", 104)))
            rig.engine.shutdown()
        }
    }

    @Test fun withdrawingAccessDuringBusyWaitPreventsDispatch() = runTest {
        for (change in listOf("revoke", "delete", "rebind", "forget")) {
            val rig = Rig(this)
            var busy = true
            val provider = ForegroundMutations { if (busy) "Foreground action pending" else null }
            rig.leases.register(provider)
            rig.engine.start(); runCurrent()
            rig.step(Duration.between(base, due).toMillis() + 1_000)
            assertEquals(RunPhase.Claimed, rig.runs.single().phase)
            assertEquals(1, rig.secrets.reads)
            when (change) {
                "revoke" -> assertNull(rig.engine.revokeAccess(ScheduleFixtures.PROFILE_ID))
                "delete" -> rig.profiles = emptyList()
                "rebind" -> rig.profiles = listOf(ScheduleFixtures.profile(token = "other@pve!token"))
                "forget" -> rig.secrets.remembered = false
            }
            busy = false
            rig.step(25_000)
            assertEquals(0, rig.gateway.dispatches, change)
            assertEquals(RunPhase.Cancelled, rig.runs.single().phase, change)
            assertTrue(rig.runs.single().detail.contains("Nothing was sent"))
            rig.engine.shutdown()
        }
    }

    @Test fun revocationDuringTheFinalStateReadPreventsDispatch() = runTest {
        val rig = Rig(this)
        val gate = CompletableDeferred<Unit>()
        rig.gateway.stateGate = gate
        rig.engine.start(); runCurrent()
        rig.step(Duration.between(base, due).toMillis() + 1_000)
        assertEquals(1, rig.gateway.stateReads)
        assertNull(rig.engine.revokeAccess(ScheduleFixtures.PROFILE_ID))
        gate.complete(Unit); runCurrent()
        assertEquals(0, rig.gateway.dispatches)
        assertEquals(RunPhase.Cancelled, rig.runs.single().phase)
        rig.engine.shutdown()
    }

    @Test fun twoBusyGuestsDoNotStarveAnUnrelatedDueSchedule() = runTest {
        val plans = (104..106).map { ScheduleFixtures.plan(vmid = it, notBefore = base) }
        val rig = Rig(this, plans = plans)
        rig.gateway.guests = (104..106).map { ScheduleFixtures.guest(vmid = it) }.toMutableList()
        rig.gateway.dispatch = { ScheduleFixtures.upid(vmid = 106) }
        val provider = ForegroundMutations { if (it.vmid != 106) "Foreground action pending" else null }
        rig.leases.register(provider)
        rig.engine.start(); runCurrent()
        rig.step(Duration.between(base, due).toMillis() + 10_000)
        assertEquals(1, rig.gateway.dispatches)
        assertEquals(RunPhase.Succeeded, rig.runs.single { it.planId == plans[2].id }.phase)
        assertEquals(2, rig.runs.count { it.phase == RunPhase.Claimed })
        assertTrue(rig.engine.shutdown())
    }

    @Test fun boundedShutdownReportsUnfinishedReadsAndBlocksTheirLaterDispatch() = runTest {
        val rig = Rig(this)
        val gate = CompletableDeferred<Unit>()
        val gateway = object : ScheduleGateway by rig.gateway {
            override suspend fun state(connection: Connection, node: String, kind: String, vmid: Int): ScheduledGuestState =
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    rig.gateway.stateReads++
                    gate.await()
                    rig.gateway.state
                }
        }
        val engine = rig.engine(gateway = gateway)
        engine.start(); runCurrent()
        rig.step(Duration.between(base, due).toMillis() + 1_000)
        assertEquals(1, rig.gateway.stateReads)
        assertFalse(engine.shutdown(Duration.ofMillis(20)))
        assertEquals(0, rig.gateway.dispatches)
        gate.complete(Unit); runCurrent()
        assertTrue(engine.shutdown())
        assertEquals(0, rig.gateway.dispatches)
        assertFalse(engine.state.value.document.runs.single().phase.inFlight)
    }

    @Test fun haManagerAcceptanceIsSubmittedRatherThanGuestSuccess() = runTest {
        for (type in listOf("hastart", "hastop")) {
            val rig = Rig(this)
            rig.gateway.dispatch = { ScheduleFixtures.upid(type = type) }
            rig.engine.start(); runCurrent()
            rig.step(Duration.between(base, due).toMillis() + 10_000)
            assertEquals(1, rig.gateway.dispatches)
            assertEquals(RunPhase.Submitted, rig.runs.single().phase)
            assertTrue(rig.runs.single().detail.contains("HA manager"))
            assertNotNull(rig.runs.single().upid)
            rig.engine.shutdown()
        }
    }

    @Test fun inspectingGuestAfterAccessRevocationDoesNotReadVaultOrServer() = runTest {
        val rig = Rig(this)
        rig.engine.start(); runCurrent()
        assertNull(rig.engine.revokeAccess(ScheduleFixtures.PROFILE_ID))
        assertTrue(rig.engine.inspectGuest(ScheduleFixtures.PROFILE_ID, ScheduleFixtures.guest()) is EngineResult.Error)
        assertEquals(0, rig.secrets.reads)
        assertEquals(0, rig.gateway.identityReads)
        rig.engine.shutdown()
    }

    @Test fun scheduledHoldBlocksForegroundUntilTheFollowEnds() = runTest {
        val rig = Rig(this)
        rig.gateway.defaultStatus = { PowerTaskStatus(PowerPhase.Accepted, "running") }
        rig.engine.start(); runCurrent()
        rig.step(Duration.between(base, due).toMillis() + 2_000)
        val key = GuestKey(ScheduleFixtures.ORIGIN, "VM", 104)
        assertEquals(RunPhase.Accepted, rig.runs.single().phase)
        assertTrue(rig.leases.scheduledHolds(key))
        assertNull(rig.leases.foreground(key) { true })
        assertEquals(true, rig.leases.foreground(GuestKey(ScheduleFixtures.ORIGIN, "VM", 105)) { true })
        rig.step(SCHEDULE_FOLLOW.toMillis())
        assertEquals(RunPhase.Submitted, rig.runs.single().phase)
        assertEquals(ScheduleFixtures.upid(), rig.runs.single().upid)
        assertFalse(rig.leases.scheduledHolds(key))
        assertEquals(true, rig.leases.foreground(key) { true })
    }

    @Test fun warningsAndFailuresAreNotSuccess() = runTest {
        for ((status, phase) in listOf(PowerTaskStatus(PowerPhase.Unsuccessful, "WARNINGS: 2") to RunPhase.Warnings,
            PowerTaskStatus(PowerPhase.Unsuccessful, "VM quit with error") to RunPhase.Failed,
            PowerTaskStatus(PowerPhase.Unknown, "The task stopped without an exit status. That is not success.") to RunPhase.Unknown)) {
            val rig = Rig(this)
            rig.gateway.statuses.add { PowerTaskStatus(PowerPhase.Accepted, "running") }
            rig.gateway.statuses.add { status }
            rig.engine.start(); runCurrent()
            rig.step(Duration.between(base, due).toMillis() + 10_000)
            assertEquals(phase, rig.runs.single().phase)
            assertEquals(1, rig.gateway.dispatches)
            rig.engine.shutdown()
        }
    }

    @Test fun persistenceFailureClaimsNothingAndSendsNothing() = runTest {
        val rig = Rig(this)
        var fail = false
        val store = ScheduleStore(rig.file) { temp, target -> if (fail) throw java.io.IOException("disk full") else Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
        val engine = rig.engine(store)
        engine.start(); runCurrent()
        fail = true
        rig.step(Duration.between(base, due).toMillis() + 10_000)
        assertEquals(0, rig.gateway.dispatches)
        assertEquals(0, rig.gateway.guestReads)
        assertNotNull(engine.state.value.problem)
        assertFalse(engine.state.value.running)
    }

    @Test fun aSecondInstanceOnTheSameFileCannotClaimTheSameOccurrence() = runTest {
        val rig = Rig(this)
        val other = rig.engine(ScheduleStore(rig.file))
        rig.engine.start(); other.start(); runCurrent()
        rig.step(Duration.between(base, due).toMillis() + 10_000)
        assertEquals(1, rig.gateway.dispatches)
        assertTrue(listOf(rig.engine, other).any { it.state.value.problem?.contains("changed outside") == true })
    }

    @Test fun shutdownWhileFollowingRecordsSubmittedAndWhileWaitingSendsNothing() = runTest {
        val rig = Rig(this)
        rig.gateway.defaultStatus = { PowerTaskStatus(PowerPhase.Accepted, "running") }
        rig.engine.start(); runCurrent()
        rig.step(Duration.between(base, due).toMillis() + 3_000)
        assertEquals(RunPhase.Accepted, rig.runs.single().phase)
        rig.engine.shutdown()
        runCurrent()
        assertEquals(RunPhase.Submitted, rig.runs.single().phase)
        assertTrue(rig.text().contains("Submitted"))
    }

    @Test fun dstGapDailyRunsOnceAtTheShiftedTime() = runTest {
        val start = Instant.parse("2026-03-29T00:00:00Z")
        val plan = ScheduleFixtures.plan(time = LocalTime.of(2, 30), notBefore = start)
        val rig = Rig(this, plans = listOf(plan), clock = VirtualClock(testScheduler, start))
        rig.engine.start(); runCurrent()
        rig.step(Duration.ofHours(4).toMillis())
        assertEquals(1, rig.gateway.dispatches)
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), rig.runs.single().due)
    }

    @Test fun dstOverlapDailyRunsOnlyTheFirstOccurrence() = runTest {
        val start = Instant.parse("2026-10-24T22:00:00Z")
        val plan = ScheduleFixtures.plan(time = LocalTime.of(2, 30), notBefore = start)
        val rig = Rig(this, plans = listOf(plan), clock = VirtualClock(testScheduler, start))
        rig.engine.start(); runCurrent()
        rig.step(Duration.ofHours(6).toMillis())
        assertEquals(1, rig.gateway.dispatches)
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), rig.runs.single().due)
    }

    @Test fun onceScheduleRunsOnceThenIsDone() = runTest {
        val plan = ScheduleFixtures.plan(repeat = ScheduleFixtures.once(LocalDate.of(2026, 11, 20), LocalTime.of(7, 0)), notBefore = base)
        val rig = Rig(this, plans = listOf(plan))
        rig.engine.start(); runCurrent()
        rig.step(Duration.ofDays(3).toMillis())
        assertEquals(1, rig.gateway.dispatches)
        assertEquals(ReadyLevel.Done, rig.engine.state.value.readiness[plan.id]!!.level)
    }

    @Test fun pauseSkipsAndResumeDoesNotCatchUpPausedTime() = runTest {
        val rig = Rig(this)
        rig.engine.start(); runCurrent()
        assertNull(rig.engine.setEnabled(rig.plan.id, false))
        rig.step(Duration.ofDays(2).plusMinutes(10).toMillis())
        assertEquals(0, rig.gateway.dispatches)
        assertTrue(rig.runs.isEmpty())
        assertNull(rig.engine.setEnabled(rig.plan.id, true))
        rig.step(10_000)
        assertEquals(0, rig.gateway.dispatches) // 06:00 today is inside the grace window but happened while paused
        assertTrue(rig.runs.isEmpty())
    }

    @Test fun saveValidatesAuthorizationIdentityPermissionAndTime() = runTest {
        val rig = Rig(this, plans = emptyList())
        rig.engine.start(); runCurrent()
        val draft = ScheduleDraft("Lab start", ScheduleAction.Start, RepeatKind.Weekdays, emptySet(), "", "07:30", "Europe/Berlin")
        val check = (rig.engine.inspectGuest(ScheduleFixtures.PROFILE_ID, ScheduleFixtures.guest()) as EngineResult.Ok).value
        val noAuth = rig.engine.savePlan(null, draft, check, false, false, ScheduleFixtures.PROFILE_ID) as EngineResult.Error
        assertEquals(setOf("authorize"), noAuth.fields.keys)
        val badTime = rig.engine.savePlan(null, draft.copy(time = "25:00"), check, false, true, ScheduleFixtures.PROFILE_ID) as EngineResult.Error
        assertEquals(setOf("time"), badTime.fields.keys)
        val noPerm = rig.engine.savePlan(null, draft, check.copy(powerMgmt = false), false, true, ScheduleFixtures.PROFILE_ID) as EngineResult.Error
        assertTrue(noPerm.fields["guest"]!!.contains("VM.PowerMgmt"))
        val weak = rig.engine.savePlan(null, draft, check.copy(strong = null), false, true, ScheduleFixtures.PROFILE_ID) as EngineResult.Error
        assertTrue(weak.fields["guest"]!!.contains("weak identity"))
        val template = rig.engine.savePlan(null, draft, check.copy(template = true), false, true, ScheduleFixtures.PROFILE_ID) as EngineResult.Error
        assertTrue(template.fields["guest"]!!.contains("Templates"))
        val ok = rig.engine.savePlan(null, draft, check, false, true, ScheduleFixtures.PROFILE_ID)
        assertTrue(ok is EngineResult.Ok)
        val plan = ok.value
        // Renaming keeps the authorization; changing the time requires it again.
        val renamed = rig.engine.savePlan(plan.id, draft.copy(name = "Renamed"), null, false, false, ScheduleFixtures.PROFILE_ID)
        assertTrue(renamed is EngineResult.Ok)
        assertEquals(1, renamed.value.revision)
        val moved = rig.engine.savePlan(plan.id, draft.copy(time = "08:00"), null, false, false, ScheduleFixtures.PROFILE_ID) as EngineResult.Error
        assertEquals(setOf("authorize"), moved.fields.keys)
        val weakOk = rig.engine.savePlan(null, draft, check.copy(strong = null), true, true, ScheduleFixtures.PROFILE_ID)
        assertEquals(IdentityMethod.Fallback, (weakOk as EngineResult.Ok).value.target.identity.method)
        assertNull(rig.engine.setEnabled(plan.id, false))
        val pausedEdit = rig.engine.savePlan(plan.id, draft.copy(time = "08:00"), null, false, true, ScheduleFixtures.PROFILE_ID)
        assertFalse((pausedEdit as EngineResult.Ok).value.enabled, "reauthorizing an edit must not silently resume a paused plan")
        // A connection that changed while editing is refused.
        rig.profiles = listOf(ScheduleFixtures.profile(pin = null))
        val stale = rig.engine.savePlan(null, draft, check, false, true, ScheduleFixtures.PROFILE_ID) as EngineResult.Error
        assertTrue(stale.fields["guest"]!!.contains("changed"))
        assertFalse(rig.text().contains(ScheduleFixtures.SECRET))
    }

    @Test fun accessGrantIsBoundToTheConnectionAndCanBeTurnedOff() = runTest {
        val rig = Rig(this, access = false)
        rig.engine.start(); runCurrent()
        assertTrue(rig.engine.state.value.access.single().canGrant)
        assertNull(rig.engine.grantAccess(ScheduleFixtures.PROFILE_ID))
        assertTrue(rig.engine.state.value.access.single().ready)
        assertEquals(0, rig.secrets.reads) // granting never opens the vault
        rig.profiles = listOf(ScheduleFixtures.profile(pin = "ef".repeat(32)))
        rig.engine.refresh()
        assertFalse(rig.engine.state.value.access.single().ready)
        assertTrue(rig.engine.state.value.access.single().message.contains("changed"))
        rig.profiles = listOf(ScheduleFixtures.profile())
        assertNull(rig.engine.revokeAccess(ScheduleFixtures.PROFILE_ID))
        assertFalse(rig.engine.state.value.access.single().ready)
        assertFalse(rig.text().contains(ScheduleFixtures.SECRET))
    }

    @Test fun futureAndMalformedFilesStopSchedulingAndAreNeverOverwritten() = runTest {
        val rig = Rig(this, seed = false)
        Files.writeString(rig.file, """{"version":9,"plans":[],"access":[],"runs":[]}""")
        rig.engine.start(); runCurrent()
        assertTrue(rig.engine.state.value.problem!!.contains("newer"))
        assertFalse(rig.engine.state.value.unreadable)
        assertNotNull(rig.engine.grantAccess(ScheduleFixtures.PROFILE_ID))
        assertEquals("""{"version":9,"plans":[],"access":[],"runs":[]}""", rig.text())
        assertNotNull(rig.engine.setAsideUnreadable())
        rig.engine.shutdown()
        Files.writeString(rig.file, """{"version":1,"plans":[],"access":[],"runs":[],"extra":1}""")
        val broken = rig.engine(ScheduleStore(rig.file))
        broken.start(); runCurrent()
        assertTrue(broken.state.value.unreadable)
        assertNull(broken.setAsideUnreadable())
        assertFalse(Files.exists(rig.file))
        assertEquals(1, Files.list(rig.dir).use { s -> s.filter { it.fileName.toString().startsWith("schedules.unreadable-") }.count() })
        assertNull(broken.state.value.problem)
    }

    @Test fun taskLogUsesTheExactTaskReference() = runTest {
        val rig = Rig(this)
        rig.engine.start(); runCurrent()
        rig.step(Duration.between(base, due).toMillis() + 10_000)
        val log = rig.engine.taskLog(rig.runs.single().id)
        assertTrue(log is EngineResult.Ok)
        assertEquals("TASK OK", log.value.lines.last().text)
        assertEquals(1, rig.gateway.logReads)
    }
}
