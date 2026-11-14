package local.ringleader.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConsoleWorkspaceTest {
    private val remote = "https://example.test:8006"
    private fun guest(id: Int, status: String = "running", template: Boolean = false) =
        Guest(remote, id, "guest$id", "node", "VM", status, template = template)
    private fun connection() = Connection(remote, "tester@pve!read", "secret", "")

    private class Inventory(var guests: List<Guest>) : InventoryGateway {
        override suspend fun overview(connection: Connection) = Overview(1, guests)
        override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
    }

    private class Vnc : VncConsoleGateway {
        var opens = 0
        val sessions = mutableListOf<VncSession>()
        override suspend fun open(connection: Connection, guest: Guest): VncSession {
            opens++
            return solidFrameSession().also { sessions += it }
        }
    }

    @Test fun takeAfterReleaseDoesNotOpenAgainAndTheStateFollows() = runTest {
        val vnc = Vnc()
        val alpha = guest(1)
        val workspace = Workspace(this, Inventory(listOf(alpha)), vncGateway = vnc)
        workspace.connect(connection()); runCurrent(); workspace.setLive(false)
        val lease = workspace.leaseConsole(alpha)
        assertEquals(1, vnc.opens)
        assertTrue(lease.fresh)
        lease.release(true)
        assertTrue(alpha.resource in workspace.state.value.keptConsoles)
        val again = workspace.takeKeptConsole(alpha)
        assertNotNull(again)
        assertFalse(again.fresh)
        assertEquals(1, vnc.opens)
        assertFalse(alpha.resource in workspace.state.value.keptConsoles)
        again.release(false)
        workspace.close()
    }

    @Test fun disconnectClosesKeptSessions() = runTest {
        val vnc = Vnc()
        val alpha = guest(1)
        val workspace = Workspace(this, Inventory(listOf(alpha)), vncGateway = vnc)
        workspace.connect(connection()); runCurrent(); workspace.setLive(false)
        workspace.leaseConsole(alpha).release(true)
        assertFalse(vnc.sessions.single().isClosed())
        workspace.disconnect(); runCurrent()
        assertTrue(vnc.sessions.single().isClosed())
        assertTrue(workspace.state.value.keptConsoles.isEmpty())
        workspace.close()
    }

    @Test fun aStoppedGuestClosesItsKeptSession() = runTest {
        val vnc = Vnc()
        val alpha = guest(1)
        val inventory = Inventory(listOf(alpha))
        val workspace = Workspace(this, inventory, vncGateway = vnc)
        workspace.connect(connection()); runCurrent(); workspace.setLive(false)
        workspace.leaseConsole(alpha).release(true)
        inventory.guests = listOf(guest(1, status = "stopped"))
        workspace.refresh(); runCurrent()
        assertTrue(vnc.sessions.single().isClosed())
        assertFalse(alpha.resource in workspace.state.value.keptConsoles)
        workspace.close()
    }

    @Test fun aLeaseStillOnScreenClosesWhenTheConnectionDoes() = runTest {
        val vnc = Vnc()
        val alpha = guest(1)
        val workspace = Workspace(this, Inventory(listOf(alpha)), vncGateway = vnc)
        workspace.connect(connection()); runCurrent(); workspace.setLive(false)
        val lease = workspace.leaseConsole(alpha)
        workspace.disconnect(); runCurrent()
        lease.release(true)
        assertTrue(vnc.sessions.single().isClosed())
        assertTrue(workspace.state.value.keptConsoles.isEmpty())
        workspace.close()
    }
}
