package local.ringleader.core

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Refusal text, lock and snapshot-state gates, and confirmation wording. Pure functions only. */
class PowerRefusalTest {
    private val remote = "https://example.test:8006"
    private val guest = Guest(remote, 100, "alpha", "node-a", "VM", "running")

    private fun confirmation(guest: Guest, action: PowerAction, snapshot: SnapshotRequest? = null) = PowerConfirmation(
        3, guest.remote, guest.remote, "Lab", guest.resource, guest.node, guest.name, guest.kind, action, guest.status, guest.template, snapshot = snapshot,
    )

    private fun ready(guest: Guest) = WorkspaceState(connected = true, address = guest.remote, overview = Overview(1, listOf(guest)), selected = guest, connectionEpoch = 3)

    @Test fun lockedGuestIsRefusedWithTheLockNamed() {
        val locked = guest.copy(lock = "backup")
        assertEquals("Locked: backup. Nothing was sent.", powerRefusal(confirmation(locked, PowerAction.Shutdown), ready(locked)))
        assertEquals("Locked: backup.", guestLockRefusal(locked, PowerAction.SnapshotCreate))
        // State matching is unchanged by a lock, so the button stays and a click explains itself.
        assertTrue(actionMatches(locked, PowerAction.Shutdown))
        assertNull(guestLockRefusal(guest, PowerAction.Shutdown))
        assertNull(guestLockRefusal(guest.copy(lock = " "), PowerAction.Shutdown))
        // A hibernated VM keeps the suspended lock and is resumed with Start.
        val hibernated = guest.copy(status = "stopped", lock = "suspended")
        assertNull(guestLockRefusal(hibernated, PowerAction.Start))
        assertNull(powerRefusal(confirmation(hibernated, PowerAction.Start), ready(hibernated)))
        assertEquals("Locked: suspended.", guestLockRefusal(hibernated, PowerAction.SnapshotCreate))
    }

    @Test fun rollbackToAnIncompleteSnapshotIsRefused() {
        val stopped = guest.copy(status = "stopped")
        val snapshots = listOf(
            GuestSnapshot("baseline", null, 1, null, false, false),
            GuestSnapshot("halfway", "baseline", 2, null, false, false, snapstate = "prepare"),
            GuestSnapshot("current", "halfway", null, null, false, true),
        )
        val inspection = demoInspection(stopped, "hour").copy(snapshots = InspectionSection(snapshots))
        val state = ready(stopped).copy(inspection = inspection)
        assertEquals("Snapshot halfway is not complete (prepare). Nothing was sent.",
            powerRefusal(confirmation(stopped, PowerAction.SnapshotRollback, SnapshotRequest("halfway")), state))
        assertNull(powerRefusal(confirmation(stopped, PowerAction.SnapshotRollback, SnapshotRequest("baseline")), state))
        // Deleting a half-made snapshot is how it is cleaned up, so only rollback is gated.
        assertNull(powerRefusal(confirmation(stopped, PowerAction.SnapshotDelete, SnapshotRequest("halfway")), state))
        assertNull(snapshotRollbackRefusal(null))
        assertEquals(snapshots[0], confirmationSnapshot(confirmation(stopped, PowerAction.SnapshotRollback, SnapshotRequest("baseline")), state))
    }

    @Test fun snapstateIsParsedFromTheSnapshotList() {
        val parsed = parseSnapshots("""{"data":[{"name":"a","snaptime":1},{"name":"b","parent":"a","snapstate":"delete"},{"name":"current","parent":"a"}]}""")
        assertNull(parsed.single { it.name == "a" }.snapstate)
        assertEquals("delete", parsed.single { it.name == "b" }.snapstate)
    }

    @Test fun unresolvedRefusalNamesTheGuestAndAction() {
        val accepted = PowerSubmission("local-1", remote, "node-a", 100, "VM", "alpha", PowerAction.Shutdown, PowerPhase.Accepted, "x", false, true)
        val other = Guest(remote, 101, "beta", "node-b", "Container", "stopped")
        val state = WorkspaceState(connected = true, address = remote, overview = Overview(1, listOf(guest, other)), selected = other, connectionEpoch = 3, submissions = listOf(accepted))
        val refusal = powerRefusal(confirmation(other, PowerAction.Start), state)!!
        assertTrue(refusal.contains("Shut down on alpha (100)"), refusal)
        assertTrue(refusal.endsWith("Nothing was sent."))
        assertEquals(accepted, unresolvedSubmission(state))
        val unknown = accepted.copy(phase = PowerPhase.Unknown, checkable = false)
        assertTrue(unresolvedActionText(unknown).startsWith("The result of Shut down on alpha (100) is unknown."))
        assertNull(unresolvedSubmission(state.copy(submissions = listOf(accepted.copy(phase = PowerPhase.Succeeded, detail = "Stopped with exit status OK.")))))
    }

    @Test fun confirmationWordingNamesTheActionAndItsConsequence() {
        assertEquals("Start", powerCommitLabel(PowerAction.Start))
        assertEquals("Shut down", powerCommitLabel(PowerAction.Shutdown))
        assertEquals("Hard stop", powerCommitLabel(PowerAction.HardStop))
        assertEquals("Reset", powerCommitLabel(PowerAction.Reset))
        assertEquals("Roll back", powerCommitLabel(PowerAction.SnapshotRollback))
        assertEquals("Delete snapshot", powerCommitLabel(PowerAction.SnapshotDelete))
        PowerAction.entries.forEach { assertFalse(powerCommitLabel(it).equals("Send", ignoreCase = true)) }
        assertEquals(setOf(PowerAction.HardStop, PowerAction.Reset, PowerAction.SnapshotRollback, PowerAction.SnapshotDelete),
            PowerAction.entries.filter(::powerActionDestructive).toSet())
        assertFalse(RESET_CONSEQUENCE == HARD_STOP_CONSEQUENCE)
        assertTrue(RESET_CONSEQUENCE.contains("restarts"))
        val unknownRam = rollbackConsequence("baseline", null)
        assertTrue(unknownRam.contains("since snapshot baseline") && unknownRam.contains("stopped at once") && unknownRam.contains("unless the snapshot includes RAM"))
        assertTrue(rollbackConsequence("baseline", true).contains("starts again"))
        assertTrue(rollbackConsequence("baseline", false).contains("stays stopped"))
        val ha = guest.copy(haState = "started")
        assertTrue(powerActionUsesHa(ha, PowerAction.Start))
        assertTrue(powerActionUsesHa(ha, PowerAction.Shutdown))
        assertFalse(powerActionUsesHa(ha, PowerAction.SnapshotCreate))
        assertFalse(powerActionUsesHa(guest, PowerAction.Start))
        assertFalse(powerActionUsesHa(null, PowerAction.Start))
    }

    @Test fun followDelayRisesFromOneToFiveSeconds() {
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L, 5_000L, 5_000L), (0..6).map(::powerFollowDelay))
    }

    @Test fun lostResultStoreIsPerServerDeduplicatedAndHoldsNoTaskId() {
        val store = LostPowerResultStore()
        val at = java.time.Instant.EPOCH
        store.add(LostPowerResult("local-a", remote, "node-a", 100, "VM", "alpha", PowerAction.Shutdown, true, at))
        store.add(LostPowerResult("local-b", remote, "node-a", 100, "VM", "alpha", PowerAction.Shutdown, false, at))
        store.add(LostPowerResult("local-c", "https://other.test:8006", "node-x", 7, "VM", "x", PowerAction.Start, false, at))
        assertEquals(listOf("local-b"), store.forRemote(remote).map { it.id })
        store.dismiss("local-b")
        assertTrue(store.forRemote(remote).isEmpty())
        assertEquals(1, store.forRemote("https://other.test:8006").size)
        val text = lostPowerResultText(LostPowerResult("local-d", remote, "node-a", 100, "VM", "alpha", PowerAction.HardStop, false, at))
        assertTrue(text.contains("Hard stop on alpha (100, node node-a) may have run"), text)
        assertFalse(text.contains("UPID"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PowerFollowWorkspaceTest {
    private val remote = "https://example.test:8006"
    private val upid = "UPID:node-a:00000010:00000011:65ABCDEF:qmshutdown:100:tester@pve:"
    private val running = PowerTaskStatus(PowerPhase.Accepted, "The server accepted this action. It is still running.")
    private val ok = PowerTaskStatus(PowerPhase.Succeeded, "Stopped with exit status OK.")

    private class Inventory : InventoryGateway {
        var calls = 0
        var status = "running"
        override suspend fun overview(connection: Connection): Overview {
            calls++
            return Overview(1, listOf(Guest(connection.remoteKey, 100, "alpha", "node-a", "VM", status), Guest(connection.remoteKey, 101, "beta", "node-b", "Container", "stopped")))
        }
        override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
    }

    private class Power(var upid: String) : PowerGateway {
        var calls = 0
        var statusCalls = 0
        var onSubmit: suspend () -> String = { upid }
        val statuses = ArrayDeque<suspend () -> PowerTaskStatus>()
        override suspend fun submit(connection: Connection, guest: Guest, action: PowerAction): String { calls++; return onSubmit() }
        override suspend fun status(connection: Connection, node: String, upid: String): PowerTaskStatus {
            statusCalls++
            return (statuses.removeFirstOrNull() ?: error("no status scripted"))()
        }
    }

    private fun TestScope.step(millis: Long) { advanceTimeBy(millis); runCurrent() }

    private fun TestScope.connected(inventory: Inventory, power: Power, store: LostPowerResultStore = LostPowerResultStore()): Workspace {
        val workspace = Workspace(this, inventory, LiveTaskGateway(), power)
        workspace.lostResults = store
        workspace.connect(Connection(remote, "tester@pve!read", "test-only-secret", "")); runCurrent()
        // A long inventory interval keeps the regular live tick out of the counts below.
        workspace.setLive(true, LIVE_INTERVAL_MAX); runCurrent()
        workspace.select(Guest(remote, 100, "alpha", "node-a", "VM", "running")); runCurrent()
        return workspace
    }

    @Test fun acceptedTaskIsFollowedUntilItStopsThenTheBlockLiftsAndInventoryIsReadOnce() = runTest {
        val inventory = Inventory()
        val power = Power(upid)
        power.statuses.addAll(listOf({ running }, { running }, { ok }))
        val workspace = connected(inventory, power)
        assertTrue(workspace.submitPower(workspace.capturePower(PowerAction.Shutdown, "Lab")!!)); runCurrent()
        assertEquals(PowerPhase.Accepted, workspace.state.value.submissions.single().phase)
        val blocked = workspace.capturePower(PowerAction.HardStop, "Lab")
        assertNull(blocked)
        assertTrue(workspace.state.value.powerNotice!!.contains("Shut down on alpha (100) is still running"))
        assertEquals(0, power.statusCalls)
        step(999)
        assertEquals(0, power.statusCalls)
        step(1)
        assertEquals(1, power.statusCalls)
        step(2_000)
        assertEquals(2, power.statusCalls)
        assertEquals(PowerPhase.Accepted, workspace.state.value.submissions.single().phase)
        val before = inventory.calls
        inventory.status = "stopped"
        step(3_000)
        assertEquals(3, power.statusCalls)
        assertEquals(PowerPhase.Succeeded, workspace.state.value.submissions.single().phase)
        // The follow has stopped; the resource list is read once after the lag delay.
        step(POWER_CATCH_UP_DELAY_MS - 1)
        assertEquals(before, inventory.calls)
        step(1)
        assertEquals(before + 1, inventory.calls)
        assertEquals("stopped", workspace.state.value.overview!!.guests.single { it.id == 100 }.status)
        step(20_000)
        assertEquals(3, power.statusCalls)
        assertEquals(before + 1, inventory.calls)
        assertEquals(1, power.calls)
        // The block is gone, and the earlier refusal sentence went with the next good capture.
        assertNotNull(workspace.capturePower(PowerAction.Start, "Lab"))
        assertNull(workspace.state.value.powerNotice)
        workspace.close()
    }

    @Test fun followPausesWhileLiveReadsAreOffAndReadsAtOnceOnResume() = runTest {
        val power = Power(upid)
        power.statuses.addAll(listOf({ running }, { ok }))
        val workspace = connected(Inventory(), power)
        assertTrue(workspace.submitPower(workspace.capturePower(PowerAction.Shutdown, "Lab")!!)); runCurrent()
        step(1_000)
        assertEquals(1, power.statusCalls)
        workspace.setLive(false); runCurrent()
        step(60_000)
        assertEquals(1, power.statusCalls)
        assertEquals(PowerPhase.Accepted, workspace.state.value.submissions.single().phase)
        workspace.setLive(true, LIVE_INTERVAL_MAX); runCurrent()
        assertEquals(2, power.statusCalls)
        assertEquals(PowerPhase.Succeeded, workspace.state.value.submissions.single().phase)
        assertEquals(1, power.calls)
        workspace.close()
    }

    @Test fun failedFollowReadKeepsThePhaseAndTriesAgain() = runTest {
        val power = Power(upid)
        power.statuses.addAll(listOf({ throw IOException("blip") }, { throw IOException("blip") }, { throw IOException("blip") }, { ok }))
        val workspace = connected(Inventory(), power)
        assertTrue(workspace.submitPower(workspace.capturePower(PowerAction.Shutdown, "Lab")!!)); runCurrent()
        step(1_000); step(2_000)
        assertEquals(2, power.statusCalls)
        assertEquals(PowerPhase.Accepted, workspace.state.value.submissions.single().phase)
        step(3_000)
        assertTrue(workspace.state.value.powerNotice!!.contains("keeps trying"))
        step(4_000)
        assertEquals(4, power.statusCalls)
        assertEquals(PowerPhase.Succeeded, workspace.state.value.submissions.single().phase)
        assertEquals(1, power.calls)
        workspace.close()
    }

    @Test fun permissionDenialStopsTheFollowButCheckStatusStays() = runTest {
        val power = Power(upid)
        power.statuses.addAll(listOf({ throw GuestPermissionException() }, { ok }))
        val workspace = connected(Inventory(), power)
        assertTrue(workspace.submitPower(workspace.capturePower(PowerAction.Shutdown, "Lab")!!)); runCurrent()
        step(1_000)
        assertEquals(POWER_FOLLOW_DENIED, workspace.state.value.powerNotice)
        step(30_000)
        assertEquals(1, power.statusCalls)
        workspace.checkPower(workspace.state.value.submissions.single().localId); runCurrent()
        assertEquals(PowerPhase.Succeeded, workspace.state.value.submissions.single().phase)
        workspace.close()
    }

    @Test fun manualCheckThatResolvesStopsTheFollow() = runTest {
        val power = Power(upid)
        power.statuses.addAll(listOf({ ok }))
        val workspace = connected(Inventory(), power)
        assertTrue(workspace.submitPower(workspace.capturePower(PowerAction.Shutdown, "Lab")!!)); runCurrent()
        workspace.checkPower(workspace.state.value.submissions.single().localId); runCurrent()
        assertEquals(PowerPhase.Succeeded, workspace.state.value.submissions.single().phase)
        step(30_000)
        assertEquals(1, power.statusCalls)
        workspace.close()
    }

    @Test fun disconnectStopsTheFollowAndKeepsAnAcceptedWarningForTheSameServer() = runTest {
        val store = LostPowerResultStore()
        val inventory = Inventory()
        val power = Power(upid)
        power.statuses.addAll(listOf({ running }))
        val workspace = connected(inventory, power, store)
        assertTrue(workspace.submitPower(workspace.capturePower(PowerAction.Shutdown, "Lab")!!)); runCurrent()
        step(1_000)
        workspace.disconnect(); runCurrent()
        assertEquals(WorkspaceState(), workspace.state.value)
        step(30_000)
        assertEquals(1, power.statusCalls)
        val lost = store.forRemote(remote).single()
        assertTrue(lost.accepted)
        assertEquals(PowerAction.Shutdown, lost.action)
        assertFalse(lost.toString().contains("UPID"))
        // The next connection to the same server shows it; another server does not.
        workspace.connect(Connection("https://other.test:8006", "tester@pve!read", "test-only-secret", "")); runCurrent()
        assertTrue(workspace.state.value.lostPowerResults.isEmpty())
        workspace.connect(Connection(remote, "tester@pve!read", "test-only-secret", "")); runCurrent()
        assertEquals(listOf(lost), workspace.state.value.lostPowerResults)
        // A warning does not block actions; it only says to check the guest. Dismiss sends nothing.
        workspace.select(Guest(remote, 101, "beta", "node-b", "Container", "stopped")); runCurrent()
        assertNotNull(workspace.capturePower(PowerAction.Start, "Lab"))
        workspace.dismissLostPowerResult(lost.id)
        assertTrue(workspace.state.value.lostPowerResults.isEmpty())
        assertTrue(store.forRemote(remote).isEmpty())
        assertEquals(1, power.calls)
        assertFalse(workspace.state.value.toString().contains(upid))
        workspace.close()
    }

    @Test fun sessionExpiryAfterTheBodyWasSentKeepsAMayHaveRunWarning() = runTest {
        val store = LostPowerResultStore()
        val power = Power(upid).apply { onSubmit = { throw SessionExpiredException() } }
        val workspace = connected(Inventory(), power, store)
        assertTrue(workspace.submitPower(workspace.capturePower(PowerAction.HardStop, "Lab")!!)); runCurrent()
        assertFalse(workspace.state.value.connected)
        val lost = store.forRemote(remote).single()
        assertFalse(lost.accepted)
        assertEquals(PowerAction.HardStop, lost.action)
        assertTrue(lostPowerResultText(lost).contains("may have run"))
        workspace.connect(Connection(remote, "tester@pve!read", "test-only-secret", "")); runCurrent()
        assertEquals(1, workspace.state.value.lostPowerResults.size)
        assertEquals(1, power.calls)
        workspace.close()
    }

    @Test fun disconnectDuringAnInFlightPostKeepsAMayHaveRunWarning() = runTest {
        val store = LostPowerResultStore()
        val gate = CompletableDeferred<String>()
        val power = Power(upid).apply { onSubmit = { withContext(NonCancellable) { gate.await() } } }
        val workspace = connected(Inventory(), power, store)
        assertTrue(workspace.submitPower(workspace.capturePower(PowerAction.Shutdown, "Lab")!!)); runCurrent()
        workspace.disconnect(); runCurrent()
        gate.complete(upid); runCurrent()
        val lost = store.forRemote(remote).single()
        assertFalse(lost.accepted)
        assertEquals(1, power.calls)
        workspace.close()
    }

    @Test fun ambiguousResultIsKeptWhenTheSessionEnds() = runTest {
        val store = LostPowerResultStore()
        val power = Power(upid).apply { onSubmit = { throw AmbiguousPowerResultException() } }
        val workspace = connected(Inventory(), power, store)
        assertTrue(workspace.submitPower(workspace.capturePower(PowerAction.Shutdown, "Lab")!!)); runCurrent()
        assertEquals(PowerPhase.Unknown, workspace.state.value.submissions.single().phase)
        step(30_000)
        assertEquals(0, power.statusCalls)
        workspace.disconnect(); runCurrent()
        assertFalse(store.forRemote(remote).single().accepted)
        workspace.close()
    }

    @Test fun lockedGuestCaptureShowsTheReasonInsteadOfNothing() = runTest {
        val inventory = object : InventoryGateway {
            override suspend fun overview(connection: Connection) = Overview(1, listOf(Guest(connection.remoteKey, 100, "alpha", "node-a", "VM", "running", lock = "snapshot")))
            override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
        }
        val power = Power(upid)
        val workspace = Workspace(this, inventory, LiveTaskGateway(), power)
        workspace.lostResults = LostPowerResultStore()
        workspace.connect(Connection(remote, "tester@pve!read", "test-only-secret", "")); runCurrent()
        workspace.select(workspace.state.value.overview!!.guests.single()); runCurrent()
        assertNull(workspace.capturePower(PowerAction.SnapshotCreate, "Lab", SnapshotRequest("before")))
        assertEquals("Locked: snapshot. Nothing was sent.", workspace.state.value.powerNotice)
        assertEquals(0, power.calls)
        workspace.close()
    }
}
