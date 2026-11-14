package local.ringleader.core

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PowerActionsTest {
    private val remote = "https://example.test:8006"
    private val upid = "UPID:node-a:00000010:00000011:65ABCDEF:qmstart:100:tester@pve!read:"

    @Test fun pathsBodiesAndTaskStatusDoNotTreatAcceptanceAsSuccess() {
        assertEquals("/api2/json/nodes/node-a/qemu/100/status/start", powerActionPath("node-a", "VM", 100, PowerAction.Start))
        assertEquals("/api2/json/nodes/node-a/qemu/100/status/shutdown", powerActionPath("node-a", "VM", 100, PowerAction.Shutdown))
        assertEquals("/api2/json/nodes/node-b/lxc/101/status/stop", powerActionPath("node-b", "Container", 101, PowerAction.HardStop))
        assertEquals("{}", powerRequestBody(PowerAction.Start).decodeToString())
        assertEquals("{}", powerRequestBody(PowerAction.HardStop).decodeToString())
        val shutdown = powerRequestBody(PowerAction.Shutdown).decodeToString()
        assertEquals("""{"forceStop":false}""", shutdown)
        assertFalse(shutdown.contains("true") || shutdown.contains("timeout") || shutdown.contains("overrule") || shutdown.contains("skiplock"))
        assertFailsWith<IllegalArgumentException> { powerActionPath("../node", "VM", 100, PowerAction.Start) }
        assertFailsWith<IllegalArgumentException> { powerActionPath("node/a", "VM", 100, PowerAction.Start) }

        val encoded = taskStatusPath("node-a", upid)
        assertEquals("/api2/json/nodes/node-a/tasks/UPID%3Anode-a%3A00000010%3A00000011%3A65ABCDEF%3Aqmstart%3A100%3Atester%40pve%21read%3A/status", encoded)
        assertFalse(encoded.contains("tester@pve"))
        assertEquals(upid, parseAcceptedUpid("""{"data":"$upid"}""", "node-a", 100))
        assertFailsWith<PowerResponseUnclearException> { parseAcceptedUpid("""{"data":"$upid"}""", "node-b", 100) }

        val running = parsePowerTaskStatus(statusBody("running", null), "node-a", upid)
        assertEquals(PowerPhase.Accepted, running.phase)
        assertEquals(PowerPhase.Succeeded, parsePowerTaskStatus(statusBody("stopped", "OK"), "node-a", upid).phase)
        assertEquals(PowerPhase.Unsuccessful, parsePowerTaskStatus(statusBody("stopped", "got timeout"), "node-a", upid).phase)
        assertEquals(PowerPhase.Accepted, parsePowerTaskStatus(statusBody("running", "OK"), "node-a", upid).phase)
        assertEquals(PowerPhase.Unknown, parsePowerTaskStatus(statusBody("stopped", null), "node-a", upid).phase)
        val warnings = parsePowerTaskStatus(statusBody("stopped", "WARNINGS: 1"), "node-a", upid)
        assertEquals(PowerPhase.Unsuccessful, warnings.phase)
        assertEquals("WARNINGS: 1", warnings.detail)
        assertFailsWith<PowerResponseUnclearException> { parsePowerTaskStatus(statusBody("running", "OK", upid = upid.replace("node-a", "other")), "node-a", upid) }
        assertTrue(HARD_STOP_CONSEQUENCE.contains("damage"))
        assertFalse(SHUTDOWN_GUARANTEE.contains("forceStop\":true"))
    }

    @Test fun confirmationRefusesMigrationTemplateStaleLoadingAndSelectionChange() {
        val guest = Guest(remote, 100, "alpha", "node-a", "VM", "running")
        val confirmation = confirmation(guest, PowerAction.HardStop)
        val ready = readyState(guest)
        assertNull(powerRefusal(confirmation, ready))
        assertTrue(liveActionsAvailable(ready))
        assertEquals("This guest moved to another node. Nothing was sent.", powerRefusal(confirmation, ready.copy(overview = Overview(1, listOf(guest.copy(node = "node-b"))), selected = guest.copy(node = "node-b"))))
        assertEquals("Templates have no power action. Nothing was sent.", powerRefusal(confirmation.copy(template = true), ready.copy(overview = Overview(1, listOf(guest.copy(template = true))), selected = guest.copy(template = true))))
        assertEquals("Inventory is stale. Nothing was sent.", powerRefusal(confirmation, ready.copy(stale = true)))
        assertEquals("Inventory is still loading. Nothing was sent.", powerRefusal(confirmation, ready.copy(loading = true)))
        assertEquals("The selection changed. Nothing was sent.", powerRefusal(confirmation, ready.copy(selected = guest.copy(id = 101, name = "beta"))))
        assertEquals("The connection changed. Nothing was sent.", powerRefusal(confirmation, ready.copy(connectionEpoch = 9)))
        assertFalse(actionMatches(guest.copy(status = "paused"), PowerAction.Start))
        assertFalse(actionMatches(guest.copy(template = true, status = "stopped"), PowerAction.Start))
        assertFalse(liveActionsAvailable(ready.copy(demo = true, connected = false)))
    }

    private fun statusBody(status: String, exitStatus: String?, upid: String = this.upid): String {
        val exit = if (exitStatus == null) "" else ""","exitstatus":"$exitStatus""""
        return """{"data":{"type":"qmstart","status":"$status","id":"100","node":"node-a","upid":"$upid","user":"tester@pve","pstart":1,"starttime":1,"pid":16$exit}}"""
    }

    private fun confirmation(guest: Guest, action: PowerAction) = PowerConfirmation(
        3, guest.remote, guest.remote, "Lab", guest.resource, guest.node, guest.name, guest.kind, action, guest.status, guest.template,
    )

    private fun readyState(guest: Guest) = WorkspaceState(
        connected = true, address = guest.remote, overview = Overview(1, listOf(guest)), selected = guest, connectionEpoch = 3,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class PowerWorkspaceTest {
    private val remote = "https://example.test:8006"
    private val upid = "UPID:node-a:00000010:00000011:65ABCDEF:qmshutdown:100:tester@pve:"

    private class Inventory : InventoryGateway {
        var inventory: suspend (Connection) -> Overview = { connection ->
            Overview(1, listOf(Guest(connection.remoteKey, 100, "alpha", "node-a", "VM", "running"), Guest(connection.remoteKey, 101, "beta", "node-b", "Container", "stopped")))
        }
        override suspend fun overview(connection: Connection) = inventory(connection)
        override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
    }

    private class Power : PowerGateway {
        var calls = 0
        var statusCalls = 0
        var onSubmit: suspend (Guest, PowerAction) -> String = { _, _ ->
            "UPID:node-a:00000010:00000011:65ABCDEF:qmshutdown:100:tester@pve:"
        }
        var onStatus: suspend (String, String) -> PowerTaskStatus = { _, _ ->
            PowerTaskStatus(PowerPhase.Accepted, "The server accepted this action. It is still running.")
        }
        override suspend fun submit(connection: Connection, guest: Guest, action: PowerAction): String {
            calls++
            return onSubmit(guest, action)
        }
        override suspend fun status(connection: Connection, node: String, upid: String): PowerTaskStatus {
            statusCalls++
            return onStatus(node, upid)
        }
    }

    @Test fun acceptedTaskIsNotSuccessUntilStoppedWithOkAndIsNotRetried() = runTest {
        val power = Power()
        val workspace = Workspace(this, Inventory(), LiveTaskGateway(), power)
        val connection = Connection(remote, "tester@pve!read", "test-only-secret", "")
        workspace.connect(connection); runCurrent()
        workspace.select(Guest(remote, 100, "alpha", "node-a", "VM", "running")); runCurrent()
        val confirmation = workspace.capturePower(PowerAction.Shutdown, "Lab")
        assertEquals(PowerAction.Shutdown, confirmation?.action)
        assertTrue(workspace.submitPower(confirmation!!)); runCurrent()
        val accepted = workspace.state.value.submissions.single()
        assertEquals(PowerPhase.Accepted, accepted.phase)
        assertFalse(powerVisibleFields(accepted).joinToString(" ").contains("UPID:"))
        assertFalse(workspace.state.value.toString().contains("test-only-secret"))
        assertEquals(1, power.calls)
        power.onStatus = { _, _ -> parsePowerTaskStatus("""{"data":{"status":"running","node":"node-a","upid":"$upid"}}""", "node-a", upid) }
        workspace.checkPower(accepted.localId); runCurrent()
        assertEquals(PowerPhase.Accepted, workspace.state.value.submissions.single().phase)
        power.onStatus = { _, _ -> parsePowerTaskStatus("""{"data":{"status":"stopped","exitstatus":"OK","node":"node-a","upid":"$upid"}}""", "node-a", upid) }
        workspace.checkPower(accepted.localId); runCurrent()
        assertEquals(PowerPhase.Succeeded, workspace.state.value.submissions.single().phase)
        assertEquals(1, power.calls)
        workspace.close()
    }

    @Test fun ambiguousFailureIsUnknownAndIsNotSentAgain() = runTest {
        val power = Power().apply { onSubmit = { _, _ -> throw AmbiguousPowerResultException() } }
        val workspace = connected(power)
        val confirmation = workspace.capturePower(PowerAction.HardStop, "Lab")!!
        assertTrue(workspace.submitPower(confirmation)); runCurrent()
        val outcome = workspace.state.value.submissions.single()
        assertEquals(PowerPhase.Unknown, outcome.phase)
        assertTrue(outcome.detail.contains("not sent again"))
        assertFalse(outcome.checkable)
        assertEquals(1, power.calls)
        runCurrent()
        assertEquals(1, power.calls)
        workspace.close()
    }

    @Test fun acceptedActionBlocksReplayAndNewActionsUntilTerminalEvidence() = runTest {
        val power = Power()
        val workspace = connected(power)
        val confirmation = workspace.capturePower(PowerAction.Shutdown, "Lab")!!
        assertTrue(workspace.submitPower(confirmation)); runCurrent()
        assertFalse(workspace.submitPower(confirmation))
        assertNull(workspace.capturePower(PowerAction.HardStop, "Lab"))
        assertEquals(1, power.calls)
        val accepted = workspace.state.value.submissions.single()
        power.onStatus = { _, _ -> throw IOException("network") }
        workspace.checkPower(accepted.localId); runCurrent()
        assertEquals(PowerPhase.Accepted, workspace.state.value.submissions.single().phase)
        assertTrue(workspace.state.value.powerNotice!!.contains("earlier result stands"))
        assertFalse(workspace.submitPower(confirmation))
        power.onStatus = { _, _ -> PowerTaskStatus(PowerPhase.Succeeded, "Stopped with exit status OK.") }
        workspace.checkPower(accepted.localId); runCurrent()
        assertFalse(workspace.submitPower(confirmation))
        assertNotNull(workspace.capturePower(PowerAction.Shutdown, "Lab"))
        assertEquals(1, power.calls)
        workspace.close()
    }

    @Test fun doubleSubmitDoesNotStartASecondAction() = runTest {
        val gate = CompletableDeferred<String>()
        val power = Power().apply { onSubmit = { _, _ -> gate.await() } }
        val workspace = connected(power)
        val confirmation = workspace.capturePower(PowerAction.Start, "Lab")
        assertNull(confirmation)
        workspace.select(Guest(remote, 101, "beta", "node-b", "Container", "stopped")); runCurrent()
        val start = workspace.capturePower(PowerAction.Start, "Lab")!!
        assertTrue(workspace.submitPower(start))
        assertFalse(workspace.submitPower(start))
        runCurrent()
        assertEquals(1, power.calls)
        assertTrue(workspace.state.value.powerNotice!!.contains("Nothing else was sent"))
        gate.complete("UPID:node-b:00000010:00000011:65ABCDEF:vzstart:101:tester@pve:"); runCurrent()
        assertEquals(PowerPhase.Accepted, workspace.state.value.submissions.single().phase)
        assertEquals(1, power.calls)
        workspace.close()
    }

    @Test fun migrationTemplateSelectionAndSessionChangesDoNotPost() = runTest {
        val inventory = Inventory()
        val power = Power()
        val workspace = Workspace(this, inventory, LiveTaskGateway(), power)
        workspace.connect(Connection(remote, "tester@pve!read", "test-only-secret", "")); runCurrent()
        val running = Guest(remote, 100, "alpha", "node-a", "VM", "running")
        workspace.select(running); runCurrent()
        val confirmation = workspace.capturePower(PowerAction.Shutdown, "Lab")!!
        inventory.inventory = { Overview(1, listOf(running.copy(node = "node-c"), Guest(remote, 101, "beta", "node-b", "Container", "stopped"))) }
        workspace.refresh(); runCurrent()
        assertFalse(workspace.submitPower(confirmation))
        assertTrue(workspace.state.value.powerNotice!!.contains("moved"))
        assertEquals(0, power.calls)

        workspace.select(Guest(remote, 101, "beta", "node-b", "Container", "stopped")); runCurrent()
        val selected = workspace.capturePower(PowerAction.Start, "Lab")!!
        workspace.select(Guest(remote, 100, "alpha", "node-c", "VM", "running")); runCurrent()
        assertFalse(workspace.submitPower(selected))
        assertTrue(workspace.state.value.powerNotice!!.contains("selection"))
        assertEquals(0, power.calls)

        inventory.inventory = { Overview(1, listOf(Guest(remote, 100, "alpha", "node-a", "VM", "stopped", template = true))) }
        workspace.refresh(); runCurrent()
        workspace.select(Guest(remote, 100, "alpha", "node-a", "VM", "stopped", template = true)); runCurrent()
        assertNull(workspace.capturePower(PowerAction.Start, "Lab"))
        val crafted = PowerConfirmation(workspace.state.value.connectionEpoch, remote, remote, "Lab", ResourceId(remote, "", "VM", 100), "node-a", "alpha", "VM", PowerAction.Start, "stopped", true)
        assertFalse(workspace.submitPower(crafted))
        assertTrue(workspace.state.value.powerNotice!!.contains("Templates"))
        assertEquals(0, power.calls)
        assertFalse(workspace.state.value.powerNotice!!.contains("remote task"))
        workspace.close()
    }

    @Test fun staleOrLoadingInventoryDoesNotPost() = runTest {
        val inventory = Inventory()
        val power = Power()
        val workspace = Workspace(this, inventory, LiveTaskGateway(), power)
        workspace.connect(Connection(remote, "tester@pve!read", "secret", "")); runCurrent()
        workspace.select(Guest(remote, 101, "beta", "node-b", "Container", "stopped")); runCurrent()
        val confirmation = workspace.capturePower(PowerAction.Start, "Lab")!!
        val gate = CompletableDeferred<Overview>()
        inventory.inventory = { gate.await() }
        workspace.refresh(); runCurrent()
        assertTrue(workspace.state.value.loading)
        assertFalse(workspace.submitPower(confirmation))
        assertTrue(workspace.state.value.powerNotice!!.contains("loading"))
        gate.complete(Overview(1, listOf(Guest(remote, 101, "beta", "node-b", "Container", "stopped")))); runCurrent()
        inventory.inventory = { throw IOException("hidden route") }
        workspace.refresh(); runCurrent()
        assertTrue(workspace.state.value.stale)
        assertNull(workspace.capturePower(PowerAction.Start, "Lab"))
        assertFalse(workspace.submitPower(confirmation))
        assertTrue(workspace.state.value.powerNotice!!.contains("stale"))
        assertEquals(0, power.calls)
        workspace.close()
    }

    @Test fun disconnectDropsLatePowerResultsWithoutClaimingTheRemoteTaskStopped() = runTest {
        val gate = CompletableDeferred<String>()
        val power = Power().apply { onSubmit = { _, _ -> withContext(NonCancellable) { gate.await() } } }
        val workspace = connected(power)
        val confirmation = workspace.capturePower(PowerAction.HardStop, "Lab")!!
        assertTrue(workspace.submitPower(confirmation)); runCurrent()
        val secret = Connection(remote, "tester@pve!read", "test-only-secret", "")
        workspace.disconnect(); runCurrent()
        gate.complete(upid); runCurrent()
        assertFalse(workspace.state.value.connected)
        assertTrue(workspace.state.value.submissions.isEmpty())
        assertFalse(workspace.state.value.toString().contains("stopped the remote"))
        assertFalse(workspace.state.value.toString().contains(upid))
        assertFalse(workspace.state.value.toString().contains("test-only-secret"))
        secret.close()
        workspace.close()
    }

    @Test fun demoConfirmationStaysLocalAndTaskRefreshDoesNotDropSubmissions() = runTest {
        var network = 0
        val inventory = Inventory().apply { inventory = { network++; error("network") } }
        val power = Power().apply { onSubmit = { _, _ -> network++; error("network") }; onStatus = { _, _ -> network++; error("network") } }
        val tasks = object : TaskGateway {
            override suspend fun tasks(connection: Connection): List<ClusterTask> { network++; return emptyList() }
        }
        val workspace = Workspace(this, inventory, tasks, power)
        workspace.exploreDemo(); runCurrent()
        // Demo guest 130 carries the backup lock, so Start on it is refused; the running VM 120 is used instead.
        assertTrue(demoOverview.guests.single { it.id == 130 }.lock == "backup")
        workspace.select(demoOverview.guests.single { it.id == 120 }); runCurrent()
        val confirmation = workspace.capturePower(PowerAction.Shutdown, "Demonstration")!!
        assertEquals("Demonstration", confirmation.origin)
        assertTrue(workspace.submitPower(confirmation)); runCurrent()
        val simulated = workspace.state.value.submissions.single()
        assertEquals(PowerPhase.Simulated, simulated.phase)
        assertEquals(SIMULATED_POWER_DETAIL, simulated.detail)
        workspace.checkPower(simulated.localId); runCurrent()
        workspace.select(demoOverview.guests.single { it.id == 120 }); runCurrent()
        assertEquals(simulated.localId, workspace.state.value.submissions.single().localId)
        workspace.refreshTasks(); runCurrent()
        assertEquals(1, workspace.state.value.submissions.size)
        assertEquals(0, network)
        assertEquals(0, power.calls)
        workspace.close()
    }

    private fun kotlinx.coroutines.test.TestScope.connected(power: Power): Workspace {
        val workspace = Workspace(this, Inventory(), LiveTaskGateway(), power)
        workspace.connect(Connection(remote, "tester@pve!read", "test-only-secret", "")); runCurrent()
        workspace.select(Guest(remote, 100, "alpha", "node-a", "VM", "running")); runCurrent()
        return workspace
    }
}
