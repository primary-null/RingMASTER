package local.ringleader.core

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The storage view's session reads through [Workspace] with a fake [StorageGateway] and the test dispatcher: the configuration,
 * the history of the storage on screen, and the chart lens read. Invented names and documentation addresses only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StorageWorkbenchTest {
    private val remote = "https://pve.example.test:8006"
    private val now = Instant.parse("2026-11-12T12:00:00Z")

    private fun overviewFor(origin: String) = parseOverview("""{"data":[
        {"type":"node","node":"node-a","status":"online","cpu":0.1,"maxcpu":4,"mem":1,"maxmem":2,"disk":1,"maxdisk":2,"uptime":5},
        {"type":"node","node":"node-b","status":"offline"},
        {"type":"storage","node":"node-a","storage":"local","shared":0,"disk":1,"maxdisk":10,"status":"available","plugintype":"dir","content":"iso,backup"},
        {"type":"storage","node":"node-a","storage":"nas","shared":1,"disk":4,"maxdisk":10,"status":"available","plugintype":"nfs","content":"backup"},
        {"type":"storage","node":"node-b","storage":"nas","shared":1,"disk":4,"maxdisk":10,"status":"available","plugintype":"nfs","content":"backup"},
        {"type":"qemu","vmid":100,"name":"alpha","node":"node-a","status":"running"}
    ]}""", origin)

    private class Inventory(var build: (Connection) -> Overview) : InventoryGateway {
        override suspend fun overview(connection: Connection) = build(connection)
        override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
    }

    private class QuietPdm : PdmReadGateway {
        override suspend fun read(connection: Connection): PdmFleetDocument = error("PDM inventory is not part of this test.")
    }

    private class Storage : StorageGateway {
        data class HistoryCall(val node: String, val storage: String, val timeframe: String, val cf: String)
        var configCalls = 0
        val historyCalls = mutableListOf<HistoryCall>()
        var configReader: suspend () -> List<StorageConfigRow> = { listOf(StorageConfigRow("local", "dir", setOf("iso"), false, false, null, null, null)) }
        var historyReader: suspend (HistoryCall) -> List<HistoryPoint> = { call ->
            listOf(HistoryPoint(1.0, mapOf("used" to 1.0, "total" to 10.0)), HistoryPoint(2.0, mapOf("used" to if (call.cf == "MAX") 3.0 else 2.0, "total" to 10.0)))
        }
        override suspend fun config(connection: Connection): List<StorageConfigRow> { configCalls++; return configReader() }
        override suspend fun history(connection: Connection, node: String, storage: String, timeframe: String, cf: String): List<HistoryPoint> {
            val call = HistoryCall(node, storage, timeframe, cf)
            historyCalls += call
            return historyReader(call)
        }
        val calls get() = configCalls + historyCalls.size
    }

    private fun TestScope.newWorkspace(storage: Storage, inventory: Inventory = Inventory { overviewFor(it.remoteKey) }) =
        Workspace(this, gateway = inventory, pdmGateway = QuietPdm(), storageGateway = storage, clock = { now })

    private fun TestScope.connected(storage: Storage, inventory: Inventory = Inventory { overviewFor(it.remoteKey) }): Workspace {
        val workspace = newWorkspace(storage, inventory)
        workspace.connect(Connection(remote, "tester@pve!read", "test-only-secret", ""))
        workspace.setLive(false)
        runCurrent()
        return workspace
    }

    private fun Workspace.view() = state.value.storageView

    // ---- configuration ----

    @Test fun theConfigurationIsReadOnceAndAReadInFlightIsNotDoubled() = runTest {
        val storage = Storage()
        val gate = CompletableDeferred<Unit>()
        storage.configReader = { gate.await(); listOf(StorageConfigRow("local", "dir", setOf("iso"), false, false, null, null, null)) }
        val workspace = connected(storage)
        assertEquals(0, storage.calls, "Nothing is read until the page asks")
        workspace.loadStorageConfig(); workspace.loadStorageConfig(); runCurrent()
        assertTrue(workspace.view().config.loading)
        gate.complete(Unit); runCurrent()
        assertEquals(1, storage.configCalls)
        val section = workspace.view().config
        assertEquals(listOf("local"), section.value!!.map { it.storage })
        assertEquals(now, section.updatedAt)
        assertFalse(section.sample)
        workspace.loadStorageConfig(); runCurrent()
        assertEquals(2, storage.configCalls, "A later call reads it again")
        workspace.close()
    }

    @Test fun aDeniedOrFailedConfigurationReadKeepsTheSessionAndTheLastRows() = runTest {
        val storage = Storage()
        val workspace = connected(storage)
        workspace.loadStorageConfig(); runCurrent()
        storage.configReader = { throw GuestPermissionException() }
        workspace.loadStorageConfig(); runCurrent()
        val denied = workspace.view().config
        assertTrue(denied.denied)
        assertEquals(STORAGE_CONFIG_DENIED, denied.error)
        assertTrue(denied.stale, "The last good rows stay, marked stale")
        assertNotNull(denied.value)
        assertTrue(workspace.state.value.connected)
        storage.configReader = { throw IOException("fixture") }
        workspace.loadStorageConfig(); runCurrent()
        assertFalse(workspace.view().config.denied)
        assertEquals(readableError(IOException("fixture")), workspace.view().config.error)
        workspace.close()
    }

    // ---- history ----

    @Test fun theHistoryOfARecordedStorageIsReadForThatRequest() = runTest {
        val storage = Storage()
        val workspace = connected(storage)
        workspace.loadStorageHistory("node-a", "local"); runCurrent()
        val request = StorageHistoryRequest(StorageKey(remote, "node-a", "local"), "hour", "AVERAGE")
        assertEquals(listOf(Storage.HistoryCall("node-a", "local", "hour", "AVERAGE")), storage.historyCalls)
        assertEquals(request, workspace.view().historyRequest)
        assertEquals(2, workspace.view().historyFor(request)!!.size)
        assertNull(workspace.view().historyFor(request.copy(cf = "MAX")), "A series answers only its own request")
        assertEquals(now, workspace.view().history.updatedAt)
        workspace.close()
    }

    @Test fun theSameStoragesSeriesStaysWhileAnotherRangeLoads() = runTest {
        val storage = Storage()
        val workspace = connected(storage)
        workspace.loadStorageHistory("node-a", "local"); runCurrent()
        val gate = CompletableDeferred<Unit>()
        storage.historyReader = { gate.await(); listOf(HistoryPoint(5.0, mapOf("used" to 5.0, "total" to 10.0))) }
        workspace.loadStorageHistory("node-a", "local", "day", "MAX"); runCurrent()
        val loading = workspace.view().history
        assertTrue(loading.loading)
        assertEquals("hour", loading.value!!.request.timeframe, "The last series stays on screen")
        assertEquals("day", workspace.view().historyRequest!!.timeframe)
        gate.complete(Unit); runCurrent()
        assertEquals("day", workspace.view().history.value!!.request.timeframe)
        // Another storage starts empty, not with this one's series.
        storage.historyReader = { CompletableDeferred<Unit>().await(); emptyList() }
        workspace.loadStorageHistory("node-a", "nas"); runCurrent()
        assertNull(workspace.view().history.value)
        assertTrue(workspace.view().history.loading)
        workspace.close()
    }

    @Test fun aLateAnswerForAnEarlierRequestIsDropped() = runTest {
        val storage = Storage()
        val first = CompletableDeferred<Unit>()
        storage.historyReader = { call ->
            if (call.storage == "local") first.await()
            listOf(HistoryPoint(1.0, mapOf("used" to if (call.storage == "local") 1.0 else 9.0, "total" to 10.0)))
        }
        val workspace = connected(storage)
        workspace.loadStorageHistory("node-a", "local"); runCurrent()
        workspace.loadStorageHistory("node-a", "nas"); runCurrent()
        first.complete(Unit); runCurrent()
        assertEquals("nas", workspace.view().history.value!!.request.key.storage)
        assertEquals(9.0, workspace.view().history.value!!.points.single().values["used"])
        workspace.close()
    }

    @Test fun refusalsHappenBeforeAnyRequest() = runTest {
        val storage = Storage()
        val workspace = connected(storage)
        workspace.loadStorageHistory("node-a", "unlisted"); runCurrent()
        assertEquals(STORAGE_NOT_RECORDED, workspace.view().history.error)
        workspace.loadStorageHistory("node-b", "nas"); runCurrent()
        assertEquals(STORAGE_NODE_NOT_ONLINE, workspace.view().history.error, "The offline node cannot answer")
        assertEquals("node-b", workspace.view().historyRequest!!.key.node)
        assertFailsWith<IllegalArgumentException> { workspace.loadStorageHistory("node-a", "local", "decade", "AVERAGE") }
        assertFailsWith<IllegalArgumentException> { workspace.loadStorageHistory("node-a", "local", "hour", "MIN") }
        assertEquals(0, storage.calls)
        workspace.close()
    }

    @Test fun aStaleInventoryReadsNoHistory() = runTest {
        val storage = Storage()
        val inventory = Inventory { overviewFor(it.remoteKey) }
        val workspace = connected(storage, inventory)
        inventory.build = { throw IOException("fixture") }
        workspace.refresh(); runCurrent()
        assertTrue(workspace.state.value.stale)
        workspace.loadStorageHistory("node-a", "local"); runCurrent()
        assertEquals(HISTORY_STALE, workspace.view().history.error)
        assertEquals(HISTORY_STALE, workspace.readStorageHistory("node-a", "local", "hour", "AVERAGE").error)
        assertEquals(0, storage.historyCalls.size)
        workspace.close()
    }

    @Test fun aDeniedHistoryReadSaysSoAndKeepsTheSession() = runTest {
        val storage = Storage()
        storage.historyReader = { throw GuestPermissionException() }
        val workspace = connected(storage)
        workspace.loadStorageHistory("node-a", "local"); runCurrent()
        assertTrue(workspace.view().history.denied)
        assertEquals(STORAGE_HISTORY_DENIED, workspace.view().history.error)
        assertTrue(workspace.state.value.connected)
        workspace.close()
    }

    @Test fun anExpiredSessionEndsLikeEveryOtherRead() = runTest {
        val storage = Storage()
        storage.configReader = { throw SessionExpiredException() }
        val workspace = connected(storage)
        workspace.loadStorageConfig(); runCurrent()
        assertFalse(workspace.state.value.connected)
        assertEquals(SessionExpiredException().message, workspace.state.value.error)
        assertEquals(StorageViewState(), workspace.view())
        workspace.close()
    }

    @Test fun closingTheSessionDropsEverythingAndALateReplyNeverLands() = runTest {
        val storage = Storage()
        val gate = CompletableDeferred<Unit>()
        val workspace = connected(storage)
        workspace.loadStorageConfig(); workspace.loadStorageHistory("node-a", "local"); runCurrent()
        assertNotNull(workspace.view().config.value)
        assertNotNull(workspace.view().history.value)
        storage.configReader = { gate.await(); listOf(StorageConfigRow("late", "dir", emptySet(), false, false, null, null, null)) }
        storage.historyReader = { gate.await(); listOf(HistoryPoint(9.0, mapOf("used" to 9.0, "total" to 10.0))) }
        workspace.loadStorageConfig(); workspace.loadStorageHistory("node-a", "local"); runCurrent()
        workspace.disconnect(); runCurrent()
        assertEquals(StorageViewState(), workspace.view())
        workspace.connect(Connection(remote, "tester@pve!read", "test-only-secret", "")); workspace.setLive(false); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(StorageViewState(), workspace.view(), "Nothing from the old session lands in the new one")
        workspace.close()
    }

    @Test fun clearingTheSelectionDropsItsHistory() = runTest {
        val storage = Storage()
        val workspace = connected(storage)
        workspace.loadStorageHistory("node-a", "local"); runCurrent()
        assertNotNull(workspace.view().history.value)
        workspace.clearStorageSelection()
        assertNull(workspace.view().history.value)
        assertNull(workspace.view().historyRequest)
        workspace.close()
    }

    // ---- the chart lens ----

    @Test fun theLensReadLeavesThePageAlone() = runTest {
        val storage = Storage()
        val workspace = connected(storage)
        workspace.loadStorageHistory("node-a", "local"); runCurrent()
        val before = workspace.view()
        val lens = workspace.readStorageHistory("node-a", "local", "week", "MAX")
        assertEquals(3.0, lens.value!!.last().values["used"])
        assertEquals(before, workspace.view())
        assertEquals(Storage.HistoryCall("node-a", "local", "week", "MAX"), storage.historyCalls.last())
        assertEquals(STORAGE_NOT_RECORDED, workspace.readStorageHistory("node-a", "unlisted", "hour", "AVERAGE").error)
        assertEquals(STORAGE_NODE_NOT_ONLINE, workspace.readStorageHistory("node-b", "nas", "hour", "AVERAGE").error)
        storage.historyReader = { throw GuestPermissionException() }
        assertEquals(STORAGE_HISTORY_DENIED, workspace.readStorageHistory("node-a", "local", "hour", "AVERAGE").error)
        assertEquals(3, storage.historyCalls.size)
        workspace.close()
    }

    // ---- PDM and demo ----

    @Test fun aPdmConnectionReadsNothing() = runTest {
        val storage = Storage()
        val workspace = newWorkspace(storage)
        workspace.connect(Connection(remote, "tester@pdm!read", "test-only-secret", "", PDM_KIND)); workspace.setLive(false); runCurrent()
        workspace.loadStorageConfig(); workspace.loadStorageHistory("node-a", "local"); runCurrent()
        assertEquals(STORAGE_VIEW_DIRECT_ONLY, workspace.view().config.error)
        assertEquals(HISTORY_DIRECT_ONLY, workspace.view().history.error)
        assertEquals(HISTORY_DIRECT_ONLY, workspace.readStorageHistory("node-a", "local", "hour", "AVERAGE").error)
        assertEquals(0, storage.calls)
        workspace.close()
    }

    @Test fun theDemoShowsLabelledSamplesAndSendsNothing() = runTest {
        val storage = Storage()
        val workspace = newWorkspace(storage)
        workspace.exploreDemo(); workspace.setLive(false); runCurrent()
        workspace.loadStorageConfig(); runCurrent()
        assertEquals(demoStorageConfig, workspace.view().config.value)
        assertTrue(workspace.view().config.sample)
        workspace.loadStorageHistory("demo-node-a", "local-lvm", "day", "AVERAGE"); runCurrent()
        val history = workspace.view().history
        assertTrue(history.sample)
        assertEquals(StorageKey(DEMO_REMOTE_KEY, "demo-node-a", "local-lvm"), history.value!!.request.key)
        assertEquals(demoStorageHistory(demoOverview.storage.first { it.id == "local-lvm" }, "day", "AVERAGE", now), history.value!!.points)
        assertEquals(120, workspace.readStorageHistory("demo-node-b", "shared-data", "hour", "MAX").value!!.size)
        workspace.loadStorageHistory("demo-node-a", "nowhere"); runCurrent()
        assertEquals(STORAGE_NOT_RECORDED, workspace.view().history.error)
        assertEquals(0, storage.calls)
        workspace.disconnect()
        assertEquals(StorageViewState(), workspace.view())
        workspace.close()
    }

    @Test fun notConnectedDoesNothing() = runTest {
        val storage = Storage()
        val workspace = newWorkspace(storage)
        workspace.loadStorageConfig(); workspace.loadStorageHistory("node-a", "local"); runCurrent()
        assertEquals(StorageViewState(), workspace.view())
        assertEquals(HISTORY_NOT_CONNECTED, workspace.readStorageHistory("node-a", "local", "hour", "AVERAGE").error)
        assertEquals(0, storage.calls)
        workspace.close()
    }
}
