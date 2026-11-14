package local.ringleader.core

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityWorkspaceTest {
    private val remoteA = "https://example.test:8006"
    private val remoteB = "https://b.example.test:8006"
    private fun connection(address: String = remoteA, secret: String = "test-only-secret") = Connection(address, "tester@pve!read", secret, "")
    private val first = Guest(remoteA, 100, "alpha", "node-a", "VM", "running")
    private val second = Guest(remoteA, 101, "beta", "node-b", "Container", "stopped")

    private class FakeInventory : InventoryGateway {
        var inventory: suspend (Connection) -> Overview = { Overview(1, emptyList()) }
        var detail: suspend (Connection, Guest) -> GuestDetails = { _, guest -> demoDetails(guest) }
        override suspend fun overview(connection: Connection) = inventory(connection)
        override suspend fun details(connection: Connection, guest: Guest) = detail(connection, guest)
    }

    private class FakeTasks : TaskGateway {
        var calls = 0
        var load: suspend (Connection) -> List<ClusterTask> = { emptyList() }
        override suspend fun tasks(connection: Connection): List<ClusterTask> {
            calls++
            return load(connection)
        }
    }

    @Test fun failureKeepsSameSessionActivityAndRecoveryClearsStale() = runTest {
        val task = taskOf(remoteA)
        val replacement = taskOf(remoteA, id = "101", type = "vzdump")
        var mode = "ok"
        val tasks = FakeTasks().apply { load = { when (mode) {
            "fail" -> throw IOException("private endpoint data")
            "next" -> listOf(replacement)
            else -> listOf(task)
        } } }
        val workspace = Workspace(this, FakeInventory().apply { inventory = { Overview(1, listOf(first)) } }, tasks)
        val connection = connection()
        workspace.connect(connection); runCurrent()
        workspace.refreshTasks(); runCurrent()
        val updated = workspace.state.value.tasksUpdatedAt
        val overview = workspace.state.value.overview
        mode = "fail"
        workspace.refreshTasks(); runCurrent()
        assertEquals(listOf(task), workspace.state.value.tasks)
        assertEquals(updated, workspace.state.value.tasksUpdatedAt)
        assertEquals(overview, workspace.state.value.overview)
        assertTrue(workspace.state.value.tasksStale)
        assertFalse(workspace.state.value.stale)
        assertFalse(workspace.state.value.tasksError!!.contains("private endpoint"))
        assertFalse(workspace.state.value.toString().contains("test-only-secret"))
        mode = "next"
        workspace.refreshTasks(); runCurrent()
        assertEquals(listOf(replacement), workspace.state.value.tasks)
        assertFalse(workspace.state.value.tasksStale)
        assertNull(workspace.state.value.tasksError)
        assertTrue(workspace.state.value.connected)
        connection.withSecret { assertEquals("test-only-secret", it) }
        workspace.close()
    }

    @Test fun repeatedTaskRefreshDoesNotQueueOrScheduleAnotherRead() = runTest {
        val gate = CompletableDeferred<List<ClusterTask>>()
        val tasks = FakeTasks().apply { load = { gate.await() } }
        val workspace = Workspace(this, FakeInventory(), tasks)
        workspace.connect(connection()); runCurrent()
        repeat(5) { workspace.refreshTasks() }
        runCurrent()
        assertEquals(1, tasks.calls)
        gate.complete(listOf(taskOf(remoteA))); runCurrent()
        val calls = tasks.calls
        runCurrent()
        assertEquals(calls, tasks.calls)
        assertFalse(workspace.state.value.tasksLoading)
        workspace.close()
    }

    @Test fun inventoryAndActivityRefreshStayIndependent() = runTest {
        val inventoryGate = CompletableDeferred<Overview>()
        var inventoryCalls = 0
        val inventory = FakeInventory().apply { inventory = { if (++inventoryCalls == 1) Overview(1, listOf(first)) else inventoryGate.await() } }
        val taskGate = CompletableDeferred<List<ClusterTask>>()
        val tasks = FakeTasks().apply { load = { taskGate.await() } }
        val workspace = Workspace(this, inventory, tasks)
        workspace.connect(connection()); runCurrent()
        workspace.refresh(); runCurrent()
        workspace.refreshTasks(); runCurrent()
        assertTrue(workspace.state.value.loading)
        assertTrue(workspace.state.value.tasksLoading)
        taskGate.complete(listOf(taskOf(remoteA))); runCurrent()
        assertFalse(workspace.state.value.tasksLoading)
        assertEquals("100", workspace.state.value.tasks!!.single().subjectId)
        assertTrue(workspace.state.value.loading)
        inventoryGate.complete(Overview(1, listOf(second))); runCurrent()
        assertEquals(listOf(second), workspace.state.value.overview!!.guests)
        assertFalse(workspace.state.value.loading)
        assertEquals(1, tasks.calls)
        workspace.close()
    }

    @Test fun switchingOrDisconnectDropsLateActivity() = runTest {
        val gate = CompletableDeferred<List<ClusterTask>>()
        var calls = 0
        val tasks = FakeTasks().apply { load = { if (++calls == 1) withContext(NonCancellable) { gate.await() } else listOf(taskOf(remoteB, node = "node-b")) } }
        val inventory = FakeInventory().apply { inventory = { connection -> Overview(1, listOf(Guest(connection.remoteKey, 100, "alpha", "node-a", "VM", "running"))) } }
        val workspace = Workspace(this, inventory, tasks)
        val old = connection(remoteA, "secret-a")
        workspace.connect(old); runCurrent()
        workspace.refreshTasks(); runCurrent()
        workspace.connect(connection(remoteB, "secret-b")); runCurrent()
        gate.complete(listOf(taskOf(remoteA))); runCurrent()
        assertNull(workspace.state.value.tasks)
        assertEquals(remoteB, workspace.state.value.address)
        workspace.refreshTasks(); runCurrent()
        assertEquals(remoteB, workspace.state.value.tasks!!.single().remote)
        assertFailsWith<IllegalStateException> { old.withSecret { it } }
        val failure = CompletableDeferred<Unit>()
        tasks.load = { withContext(NonCancellable) { failure.await() }; throw IOException("private endpoint data") }
        workspace.refreshTasks(); runCurrent()
        workspace.disconnect()
        failure.complete(Unit); runCurrent()
        assertEquals(WorkspaceState(), workspace.state.value)
    }

    @Test fun disconnectCancelsAnActivityRead() = runTest {
        val tasks = FakeTasks().apply { load = { suspendCancellableCoroutine { } } }
        val workspace = Workspace(this, FakeInventory(), tasks)
        workspace.connect(connection()); runCurrent()
        workspace.refreshTasks(); runCurrent()
        assertTrue(workspace.state.value.tasksLoading)
        workspace.disconnect()
        runCurrent()
        assertEquals(WorkspaceState(), workspace.state.value)
    }

    @Test fun expiredTaskClearsTheSessionAndLateReadsCannotRestoreIt() = runTest {
        val inventoryGate = CompletableDeferred<Overview>()
        val detailGate = CompletableDeferred<GuestDetails>()
        var inventoryCalls = 0
        val inventory = FakeInventory().apply {
            inventory = { if (++inventoryCalls == 1) Overview(1, listOf(first)) else withContext(NonCancellable) { inventoryGate.await() } }
            detail = { _, _ -> withContext(NonCancellable) { detailGate.await() } }
        }
        val taskGate = CompletableDeferred<Unit>()
        val tasks = FakeTasks().apply { load = { withContext(NonCancellable) { taskGate.await() }; throw SessionExpiredException() } }
        val workspace = Workspace(this, inventory, tasks)
        val connection = connection()
        workspace.connect(connection); runCurrent()
        workspace.select(first); runCurrent()
        workspace.refresh(); runCurrent()
        workspace.refreshTasks(); runCurrent()
        assertTrue(workspace.state.value.detailsLoading)
        assertTrue(workspace.state.value.loading)
        assertTrue(workspace.state.value.tasksLoading)
        taskGate.complete(Unit); runCurrent()
        assertFalse(workspace.state.value.connected)
        assertNull(workspace.state.value.overview)
        assertNull(workspace.state.value.tasks)
        assertNull(workspace.state.value.selected)
        assertNull(workspace.state.value.details)
        assertNotNull(workspace.state.value.error)
        assertFailsWith<IllegalStateException> { connection.withSecret { it } }
        inventoryGate.complete(Overview(1, listOf(second))); runCurrent()
        detailGate.complete(demoDetails(first)); runCurrent()
        assertNull(workspace.state.value.overview)
        assertNull(workspace.state.value.details)
        assertNull(workspace.state.value.tasks)
        assertFalse(workspace.state.value.connected)
        workspace.close()
    }

    @Test fun deniedTaskKeepsTheSessionInventoryAndPriorActivity() = runTest {
        val task = taskOf(remoteA)
        var deny = false
        val tasks = FakeTasks().apply { load = {
            if (deny) throw IllegalStateException("Permission was refused. Check this token's resource permissions.") else listOf(task)
        } }
        val workspace = Workspace(this, FakeInventory().apply { inventory = { Overview(1, listOf(first)) } }, tasks)
        val connection = connection()
        workspace.connect(connection); runCurrent()
        workspace.refreshTasks(); runCurrent()
        val updated = workspace.state.value.tasksUpdatedAt
        val epoch = workspace.state.value.connectionEpoch
        val overview = workspace.state.value.overview
        deny = true
        workspace.refreshTasks(); runCurrent()
        assertTrue(workspace.state.value.connected)
        assertEquals(epoch, workspace.state.value.connectionEpoch)
        assertEquals(overview, workspace.state.value.overview)
        assertEquals(listOf(task), workspace.state.value.tasks)
        assertEquals(updated, workspace.state.value.tasksUpdatedAt)
        assertTrue(workspace.state.value.tasksStale)
        assertFalse(workspace.state.value.stale)
        assertNull(workspace.state.value.error)
        assertTrue(workspace.state.value.tasksError!!.contains("Permission"))
        connection.withSecret { assertEquals("test-only-secret", it) }
        workspace.close()
    }

    @Test fun duplicateOrForeignActivityIsNotPublished() = runTest {
        val task = taskOf(remoteA)
        var mode = "ok"
        val tasks = FakeTasks().apply { load = { when (mode) {
            "duplicate" -> listOf(task, task)
            "foreign" -> listOf(taskOf(remoteB, node = "node-b"))
            else -> listOf(task)
        } } }
        val workspace = Workspace(this, FakeInventory(), tasks)
        workspace.connect(connection()); runCurrent()
        mode = "duplicate"
        workspace.refreshTasks(); runCurrent()
        assertNull(workspace.state.value.tasks)
        assertFalse(workspace.state.value.tasksStale)
        assertTrue(workspace.state.value.connected)
        assertEquals("The server response or connection settings were invalid.", workspace.state.value.tasksError)
        assertFalse(workspace.state.value.tasksError!!.contains("UPID"))
        mode = "ok"
        workspace.refreshTasks(); runCurrent()
        mode = "foreign"
        workspace.refreshTasks(); runCurrent()
        assertEquals(listOf(task), workspace.state.value.tasks)
        assertTrue(workspace.state.value.tasksStale)
        assertTrue(workspace.state.value.connected)
        mode = "duplicate"
        workspace.refreshTasks(); runCurrent()
        assertEquals(listOf(task), workspace.state.value.tasks)
        assertTrue(workspace.state.value.tasksStale)
        workspace.close()
    }

    @Test fun demoActivityStaysLocalWhenTheSessionChanges() = runTest {
        var inventoryCalls = 0
        var allowInventory = true
        val inventory = FakeInventory().apply {
            inventory = { check(allowInventory); inventoryCalls++; Overview(1, listOf(first)) }
            detail = { _, _ -> error("network") }
        }
        val tasks = FakeTasks().apply { load = { error("network") } }
        val workspace = Workspace(this, inventory, tasks)
        workspace.refreshTasks(); runCurrent()
        assertEquals(0, tasks.calls)
        val connection = connection()
        workspace.connect(connection); runCurrent()
        allowInventory = false
        workspace.exploreDemo()
        workspace.refreshTasks()
        workspace.refresh()
        workspace.select(demoOverview.guests.first())
        runCurrent()
        assertEquals(0, tasks.calls)
        assertEquals(1, inventoryCalls)
        assertTrue(workspace.state.value.demo)
        assertEquals(demoTasks.map { it.identity }, workspace.state.value.tasks!!.map { it.identity })
        assertTrue(workspace.state.value.tasks!!.any { it.type == "lab-maintenance" && taskStatusLabel(it) == "Unknown" })
        assertTrue(workspace.state.value.tasks!!.any { taskStatusLabel(it) == "running" })
        assertFailsWith<IllegalStateException> { connection.withSecret { it } }
        workspace.close()
        assertEquals(WorkspaceState(), workspace.state.value)
    }

    @Test fun emptyActivityPublishesAnEmptyList() = runTest {
        val workspace = Workspace(this, FakeInventory(), FakeTasks())
        workspace.connect(connection()); runCurrent()
        assertNull(workspace.state.value.tasks)
        workspace.refreshTasks(); runCurrent()
        assertEquals(emptyList(), workspace.state.value.tasks)
        assertNull(workspace.state.value.tasksError)
        assertFalse(workspace.state.value.tasksStale)
        workspace.close()
    }

    private fun taskOf(remote: String, node: String = "node-a", type: String = "qmstart", id: String = "100"): ClusterTask {
        val upid = "UPID:$node:00000010:00000011:65ABCDEF:$type:$id:tester@pve:"
        val body = """{"data":[{"upid":"$upid","node":"$node","type":"$type","id":"$id","user":"tester@pve","starttime":1700000000,"endtime":1700000010,"status":"OK"}]}"""
        return parseClusterTasks(body, remote).single()
    }
}
