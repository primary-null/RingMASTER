package local.ringleader.core

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceTest {
    private val remoteA = "https://example.test:8006"
    private val remoteB = "https://b.example.test:8006"
    private fun connection(address: String = remoteA, secret: String = "test-only-secret") =
        Connection(address, "tester@pve!read", secret, "")
    private val first = Guest(remoteA, 100, "alpha", "node-a", "VM", "running")
    private val second = Guest(remoteA, 101, "beta", "node-b", "Container", "stopped")
    private class Fake : InventoryGateway {
        var inventory: suspend (Connection) -> Overview = { connection ->
            Overview(2, listOf(
                Guest(connection.remoteKey, 100, "alpha", "node-a", "VM", "running"),
                Guest(connection.remoteKey, 101, "beta", "node-b", "Container", "stopped"),
            ))
        }
        var detail: suspend (Connection, Guest) -> GuestDetails = { _, guest -> demoDetails(guest) }
        override suspend fun overview(connection: Connection) = inventory(connection)
        override suspend fun details(connection: Connection, guest: Guest) = detail(connection, guest)
    }

    @Test fun refreshKeepsCredentialsInSessionAndFailureMarksSnapshotStale() = runTest {
        val fake = Fake()
        val workspace = Workspace(this, fake)
        val connection = connection()
        workspace.connect(connection); runCurrent()
        val updated = workspace.state.value.updatedAt
        val initial = workspace.state.value.overview
        fake.inventory = { it.withSecret { token -> assertEquals("test-only-secret", token) }; throw IOException("private endpoint data") }
        workspace.refresh(); runCurrent()
        assertEquals(initial, workspace.state.value.overview)
        assertEquals(updated, workspace.state.value.updatedAt)
        assertTrue(workspace.state.value.stale)
        assertFalse(workspace.state.value.toString().contains("test-only-secret"))
        assertFalse(workspace.state.value.error!!.contains("private endpoint"))
        fake.inventory = { Overview(0, emptyList()) }
        workspace.refresh(); runCurrent()
        assertFalse(workspace.state.value.stale)
        assertNull(workspace.state.value.error)
        workspace.close()
        assertFailsWith<IllegalStateException> { connection.withSecret { it } }
    }

    @Test fun disconnectDiscardsLateResultsEvenWhenTransportIgnoresCancellation() = runTest {
        val gate = CompletableDeferred<Overview>()
        val fake = Fake().apply { inventory = { withContext(NonCancellable) { gate.await() } } }
        val workspace = Workspace(this, fake)
        workspace.connect(connection()); runCurrent()
        workspace.disconnect()
        gate.complete(Overview(1, listOf(first))); runCurrent()
        assertEquals(WorkspaceState(), workspace.state.value)
    }

    @Test fun switchingConnectionsDiscardsOldInventoryAndClosesOldSecret() = runTest {
        val gate = CompletableDeferred<Overview>()
        var calls = 0
        val fake = Fake().apply { inventory = { if (++calls == 1) withContext(NonCancellable) { gate.await() } else Overview(1, listOf(second)) } }
        val workspace = Workspace(this, fake)
        val old = connection()
        workspace.connect(old); runCurrent()
        workspace.connect(connection()); runCurrent()
        gate.complete(Overview(1, listOf(first))); runCurrent()
        assertEquals(listOf(second), workspace.state.value.overview!!.guests)
        assertFailsWith<IllegalStateException> { old.withSecret { it } }
        workspace.close()
    }

    @Test fun switchingGuestsNeverShowsThePreviousGuestDetails() = runTest {
        val gate = CompletableDeferred<GuestDetails>()
        val fake = Fake().apply {
            inventory = { Overview(2, listOf(first, second)) }
            detail = { _, guest -> if (guest.id == first.id) withContext(NonCancellable) { gate.await() } else demoDetails(guest) }
        }
        val workspace = Workspace(this, fake)
        workspace.connect(connection()); runCurrent()
        workspace.select(first); runCurrent()
        workspace.select(second)
        assertNull(workspace.state.value.details)
        runCurrent()
        gate.complete(demoDetails(first)); runCurrent()
        assertEquals(second, workspace.state.value.details!!.guest)
        workspace.close()
    }

    @Test fun migratedGuestUsesNewNodeAndRemovedGuestClosesDetails() = runTest {
        val fake = Fake().apply { inventory = { Overview(1, listOf(first)) } }
        val workspace = Workspace(this, fake)
        workspace.connect(connection()); runCurrent(); workspace.select(first); runCurrent()
        val migrated = first.copy(node = "node-b")
        fake.inventory = { Overview(1, listOf(migrated)) }
        workspace.refresh(); runCurrent()
        assertEquals(first.resource, workspace.state.value.details!!.guest.resource)
        assertEquals("node-b", workspace.state.value.details!!.guest.node)
        fake.inventory = { Overview(1, emptyList()) }
        workspace.refresh(); runCurrent()
        assertNull(workspace.state.value.selected)
        assertNull(workspace.state.value.details)
        workspace.close()
    }

    @Test fun revokedSessionClearsInventoryAndSecret() = runTest {
        val fake = Fake()
        val workspace = Workspace(this, fake)
        val connection = connection()
        workspace.connect(connection); runCurrent()
        fake.inventory = { throw SessionExpiredException() }
        workspace.refresh(); runCurrent()
        assertFalse(workspace.state.value.connected)
        assertNull(workspace.state.value.overview)
        assertNotNull(workspace.state.value.error)
        assertFailsWith<IllegalStateException> { connection.withSecret { it } }
    }

    @Test fun twoFactorNeededAgainOnRefreshClosesTheSession() = runTest {
        val fake = Fake()
        val workspace = Workspace(this, fake)
        val connection = connection()
        workspace.connect(connection); runCurrent()
        assertNotNull(workspace.state.value.overview)
        fake.inventory = { throw TwoFactorRequiredException() }
        workspace.refresh(); runCurrent()
        assertFalse(workspace.state.value.connected)
        assertNull(workspace.state.value.overview)
        assertEquals(TwoFactorRequiredException().message, workspace.state.value.error)
        assertFailsWith<IllegalStateException> { connection.withSecret { it } }
        workspace.close()
    }

    @Test fun twoFactorNeededAgainOnLiveTickClosesTheSession() = runTest {
        val fake = Fake()
        val workspace = Workspace(this, fake)
        val connection = connection()
        workspace.connect(connection); runCurrent()
        fake.inventory = { throw TwoFactorRequiredException() }
        advanceTimeBy(LIVE_INTERVAL_DEFAULT * 1000L + 1); runCurrent()
        assertFalse(workspace.state.value.connected)
        assertEquals(TwoFactorRequiredException().message, workspace.state.value.error)
        assertFailsWith<IllegalStateException> { connection.withSecret { it } }
        // The loop stopped with the session: no further reads.
        var reads = 0
        fake.inventory = { reads++; Overview(0, emptyList()) }
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, reads)
        workspace.close()
    }

    @Test fun firstConnectWithoutTheCodeClosesAndIsNotKeptOpen() = runTest {
        val fake = Fake()
        fake.inventory = { throw TwoFactorRequiredException() }
        val workspace = Workspace(this, fake)
        workspace.connect(connection()); runCurrent()
        assertFalse(workspace.state.value.connected)
        assertFalse(sessionOpen(workspace.state.value))
        assertEquals("This account uses two-factor authentication. Enter the current code.", workspace.state.value.error)
        workspace.close()
    }

    @Test fun demoDoesNotAccessNetworkOrRetainPreviousSession() = runTest {
        val fake = Fake().apply { inventory = { error("Unexpected network request") }; detail = { _, _ -> error("Unexpected network request") } }
        val workspace = Workspace(this, fake)
        workspace.exploreDemo(); workspace.select(demoOverview.guests.first()); workspace.refresh(); runCurrent()
        assertTrue(workspace.state.value.demo)
        assertNotNull(workspace.state.value.details)
        workspace.close()
    }

    @Test fun repeatedRefreshDoesNotQueueDuplicateRequests() = runTest {
        val gate = CompletableDeferred<Overview>()
        var calls = 0
        val fake = Fake().apply { inventory = { calls++; gate.await() } }
        val workspace = Workspace(this, fake)
        workspace.connect(connection()); runCurrent()
        repeat(5) { workspace.refresh() }; runCurrent()
        assertEquals(1, calls)
        gate.complete(Overview(2, listOf(first, second))); runCurrent()
        workspace.close()
    }

    @Test fun migrationMatchesTheSameRemoteWhileAForeignVmIdDoesNot() {
        val moved = first.copy(node = "node-c")
        val foreign = Guest(remoteB, first.id, "other", "node-c", first.kind, "running")
        assertEquals(first.resource, moved.resource)
        assertNotEquals(first.resource, foreign.resource)
        assertEquals(moved, findCurrentGuest(remoteA, first, listOf(foreign, moved)))
        assertNull(findCurrentGuest(remoteA, first, listOf(foreign)))
        assertNull(findCurrentGuest(remoteB, first, listOf(moved)))
        assertNull(findCurrentGuest(remoteA, first, emptyList()))
    }

    @Test fun overlappingIdsCannotCrossSelectOrClobberDetails() = runTest {
        val guestA = Guest(remoteA, 100, "alpha", "node-a", "VM", "running")
        val guestB = Guest(remoteB, 100, "beta", "node-b", "VM", "stopped")
        val gate = CompletableDeferred<GuestDetails>()
        var detailCalls = 0
        val fake = Fake().apply {
            inventory = { connection ->
                if (connection.remoteKey == remoteA) Overview(1, listOf(guestA)) else Overview(1, listOf(guestB))
            }
            detail = { connection, guest ->
                check(guest.remote == connection.remoteKey)
                detailCalls++
                if (guest.remote == remoteA) withContext(NonCancellable) { gate.await() } else demoDetails(guest)
            }
        }
        val workspace = Workspace(this, fake)
        val older = connection(remoteA, "secret-a")
        workspace.connect(older); runCurrent()
        workspace.select(guestA); runCurrent()
        assertTrue(detailCalls >= 1)
        workspace.connect(connection(remoteB, "secret-b")); runCurrent()
        workspace.select(guestB); runCurrent()
        assertEquals(guestB.resource, workspace.state.value.details!!.guest.resource)
        assertEquals("beta", workspace.state.value.details!!.guest.name)
        assertEquals("node-b", workspace.state.value.details!!.guest.node)
        gate.complete(demoDetails(guestA)); runCurrent()
        assertEquals(guestB.resource, workspace.state.value.selected!!.resource)
        assertEquals("beta", workspace.state.value.details!!.guest.name)
        assertEquals("node-b", workspace.state.value.details!!.guest.node)
        val callsBeforeReject = detailCalls
        assertFailsWith<IllegalArgumentException> { workspace.select(guestA) }
        assertEquals(callsBeforeReject, detailCalls)
        assertEquals(guestB.resource, workspace.state.value.selected!!.resource)
        assertEquals("beta", workspace.state.value.details!!.guest.name)
        workspace.close()
        assertFailsWith<IllegalStateException> { older.withSecret { it } }
    }

    @Test fun refreshRejectsGuestsFromAnotherConnection() = runTest {
        val foreign = Guest(remoteB, first.id, "other", "node-z", first.kind, "running")
        val fake = Fake().apply { inventory = { Overview(1, listOf(first)) } }
        val workspace = Workspace(this, fake)
        workspace.connect(connection()); runCurrent()
        workspace.select(first); runCurrent()
        val details = workspace.state.value.details
        fake.inventory = { Overview(1, listOf(foreign, first.copy(node = "node-c"))) }
        workspace.refresh(); runCurrent()
        assertEquals(first.resource, workspace.state.value.selected!!.resource)
        assertEquals("node-a", workspace.state.value.selected!!.node)
        assertEquals(details, workspace.state.value.details)
        assertTrue(workspace.state.value.stale)
        assertNotNull(workspace.state.value.error)
        workspace.close()
    }
    @Test fun duplicateInventoryIsRejectedWithoutPublishingInvalidKeys() = runTest {
        val fake = Fake().apply { inventory = { Overview(1, listOf(first)) } }
        val workspace = Workspace(this, fake)
        workspace.connect(connection()); runCurrent()
        fake.inventory = { Overview(1, listOf(first, first.copy(node = "node-b"))) }
        workspace.refresh(); runCurrent()
        assertEquals(listOf(first), workspace.state.value.overview!!.guests)
        assertTrue(workspace.state.value.stale)
        assertNotNull(workspace.state.value.error)
        workspace.close()
    }

}
