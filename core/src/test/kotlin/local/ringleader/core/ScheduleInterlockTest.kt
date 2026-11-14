package local.ringleader.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shared interlock between scheduled runs and a real foreground [Workspace] session. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleInterlockTest {
    private val remote = "https://example.test:8006"
    private val upid = "UPID:node-a:00000010:00000011:65ABCDEF:qmshutdown:100:tester@pve!read:"
    private val key = GuestKey(remote, "VM", 100)

    private class Inventory : InventoryGateway {
        override suspend fun overview(connection: Connection) = Overview(1, listOf(Guest(connection.remoteKey, 100, "alpha", "node-a", "VM", "running")))
        override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
    }

    private class Power(private val upid: String) : PowerGateway {
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null
        var status = PowerTaskStatus(PowerPhase.Accepted, "running")
        override suspend fun submit(connection: Connection, guest: Guest, action: PowerAction): String { calls++; gate?.await(); return upid }
        override suspend fun status(connection: Connection, node: String, upid: String) = status
    }

    private fun TestScope.connected(power: Power, leases: MutationLeases): Workspace {
        val workspace = Workspace(this, Inventory(), LiveTaskGateway(), power)
        workspace.mutationLeases = leases
        workspace.lostResults = LostPowerResultStore()
        workspace.connect(Connection(remote, "tester@pve!read", "test-only-secret", "")); runCurrent()
        workspace.setLive(true, LIVE_INTERVAL_MAX); runCurrent()
        workspace.select(Guest(remote, 100, "alpha", "node-a", "VM", "running")); runCurrent()
        return workspace
    }

    @Test fun aScheduledHoldRefusesForegroundPowerWithoutUsingTheConfirmation() = runTest {
        val leases = MutationLeases()
        val power = Power(upid)
        val workspace = connected(power, leases)
        val confirmation = workspace.capturePower(PowerAction.Shutdown, "Lab")!!
        assertNull(leases.acquireScheduled(key, "run-1"))
        assertFalse(workspace.submitPower(confirmation))
        assertEquals(MutationLeases.SCHEDULED_REFUSAL, workspace.state.value.powerNotice)
        assertEquals(0, power.calls)
        // Another guest on the same server is not affected; a stale release cannot free the hold.
        leases.releaseScheduled(key, "someone-else")
        assertTrue(leases.scheduledHolds(key))
        leases.releaseScheduled(key, "run-1")
        assertTrue(workspace.submitPower(confirmation)) // the refused confirmation was not consumed
        runCurrent()
        assertEquals(1, power.calls)
        workspace.close()
    }

    @Test fun foregroundSendAndUnresolvedResultBlockAScheduledClaim() = runTest {
        val leases = MutationLeases()
        val power = Power(upid).apply { gate = CompletableDeferred() }
        val workspace = connected(power, leases)
        assertTrue(workspace.submitPower(workspace.capturePower(PowerAction.Shutdown, "Lab")!!)); runCurrent()
        assertNotNull(leases.acquireScheduled(key, "run-1"), "a POST in flight blocks the guest")
        assertNull(leases.acquireScheduled(GuestKey(remote, "VM", 101), "run-2"), "another guest is free")
        assertNull(leases.acquireScheduled(GuestKey("https://other.example.test:8006", "VM", 100), "run-3"), "another server is free")
        power.gate!!.complete(Unit); runCurrent()
        assertEquals(PowerPhase.Accepted, workspace.state.value.submissions.single().phase)
        assertTrue(leases.acquireScheduled(key, "run-1")!!.contains("still running"))
        workspace.close()
        assertNull(leases.acquireScheduled(key, "run-1"), "a closed session no longer blocks")
    }

    @Test fun checkAndMarkAreAtomicUnderOneLock() {
        val leases = MutationLeases()
        var marked = false
        leases.register { k -> if (marked && k == key) "busy" else null }
        assertEquals(true, leases.foreground(key) { marked = true; true })
        assertEquals("busy", leases.acquireScheduled(key, "run"))
        marked = false
        assertNull(leases.acquireScheduled(key, "run"))
        assertNull(leases.acquireScheduled(key, "run"), "re-entrant for the same owner")
        assertEquals("Another scheduled action is running on this guest.", leases.acquireScheduled(key, "other-run"))
        assertNull(leases.foreground(key) { error("must not run") })
    }
}
