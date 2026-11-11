package local.ringleader.core

import java.io.IOException
import java.time.Instant
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

/** Workspace behaviour of the 0.21.4 read-only views, with fake gateways. No bytes of a real server response are used. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadOnlyWorkspaceTest {
    private val remote = "https://example.test:8006"
    private val otherRemote = "https://other.example.test:8006"
    private val now = Instant.parse("2026-11-09T12:00:00Z")

    private fun overviewFor(origin: String, nodeAStatus: String = "online") = parseOverview("""{"data":[
        {"type":"node","node":"node-a","status":"$nodeAStatus","cpu":0.1,"maxcpu":4,"mem":1,"maxmem":2,"disk":1,"maxdisk":2,"uptime":5},
        {"type":"node","node":"node-b","status":"offline"},
        {"type":"node","node":"node-c","status":"online"},
        {"type":"storage","node":"node-a","storage":"local","shared":0,"disk":1,"maxdisk":10,"status":"available","plugintype":"dir","content":"iso,backup"},
        {"type":"storage","node":"node-a","storage":"data","shared":0,"disk":1,"maxdisk":10,"status":"available","plugintype":"dir","content":"images"},
        {"type":"storage","node":"node-b","storage":"local","shared":0,"status":"unknown","plugintype":"dir","content":"iso,backup"},
        {"type":"storage","node":"node-c","storage":"local","shared":0,"disk":1,"maxdisk":10,"status":"available","plugintype":"dir","content":"iso,backup"},
        {"type":"qemu","vmid":100,"name":"alpha","node":"node-a","status":"running"}
    ]}""", origin)

    private class Inventory(var build: (Connection) -> Overview) : InventoryGateway {
        override suspend fun overview(connection: Connection) = build(connection)
        override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
    }

    private class QuietPdm : PdmReadGateway {
        override suspend fun read(connection: Connection): PdmFleetDocument = error("PDM inventory is not part of this test.")
    }

    private class Permissions : PermissionsGateway {
        var calls = 0
        var reader: suspend (Connection) -> PermissionDump = { PermissionDump(mapOf("/" to mapOf("Sys.Audit" to true))) }
        override suspend fun read(connection: Connection): PermissionDump { calls++; return reader(connection) }
    }

    private data class SyslogCall(val node: String, val start: Int, val limit: Int, val service: String?)

    /** A node's log. Every line has a unique text, as a real journal line does (it carries a timestamp). */
    private class Journal(count: Int) {
        val lines = (1..count).map { "line $it" }.toMutableList()
        private var next = count + 1
        fun page(start: Int, limit: Int) = SyslogPage((start until minOf(lines.size, start + limit)).map { SyslogLine(it + 1, lines[it]) }, lines.size)
        fun add(count: Int) { repeat(count) { lines += "line ${next++}" } }
    }

    private class Tools : NodeToolsGateway {
        val servicesCalls = mutableListOf<String>()
        val updatesCalls = mutableListOf<String>()
        val syslogCalls = mutableListOf<SyslogCall>()
        var inFlight = 0
        var maxInFlight = 0
        val journal = Journal(1_200)
        var servicesReader: suspend (Connection, String) -> List<NodeService> = { _, _ -> listOf(NodeService("pveproxy", null, "PVE API Proxy Server", "running", "enabled", "active")) }
        var updatesReader: suspend (Connection, String) -> List<PackageUpdate> = { _, _ -> listOf(PackageUpdate("pve-manager", null, null, "9.2.23", "9.2.21", "all", "Proxmox", null, null)) }
        var syslogReader: suspend (SyslogCall) -> SyslogPage = { journal.page(it.start, it.limit) }
        override suspend fun services(connection: Connection, node: String): List<NodeService> { servicesCalls += node; return servicesReader(connection, node) }
        override suspend fun updates(connection: Connection, node: String): List<PackageUpdate> { updatesCalls += node; return updatesReader(connection, node) }
        override suspend fun syslog(connection: Connection, node: String, start: Int, limit: Int, service: String?): SyslogPage {
            val call = SyslogCall(node, start, limit, service)
            syslogCalls += call
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try { return syslogReader(call) } finally { inFlight-- }
        }
    }

    private class Storage : StorageContentGateway {
        data class Call(val node: String, val storage: String, val content: String?)
        val calls = mutableListOf<Call>()
        var reader: suspend (Call) -> List<StorageVolume> = { call -> listOf(volume("${call.storage}:iso/a.iso")) }
        override suspend fun read(connection: Connection, node: String, storage: String, content: String?): List<StorageVolume> {
            val call = Call(node, storage, content)
            calls += call
            return reader(call)
        }
    }

    private companion object {
        fun volume(volid: String) = StorageVolume(volid, "iso", "iso", 10, null, null, null, null, null, false, null)
    }

    private fun connection(address: String = remote, token: String = "tester@pve!read") = Connection(address, token, "test-only-secret", "")

    private fun TestScope.newWorkspace(
        permissions: Permissions = Permissions(), tools: Tools = Tools(), storage: Storage = Storage(),
        inventory: Inventory = Inventory { overviewFor(it.remoteKey) },
    ) = Workspace(this, gateway = inventory, pdmGateway = QuietPdm(), permissionsGateway = permissions, nodeToolsGateway = tools,
        storageContentGateway = storage, clock = { now })

    private fun TestScope.connected(
        permissions: Permissions = Permissions(), tools: Tools = Tools(), storage: Storage = Storage(),
        inventory: Inventory = Inventory { overviewFor(it.remoteKey) }, connection: Connection = connection(),
    ): Workspace {
        val workspace = newWorkspace(permissions, tools, storage, inventory)
        workspace.connect(connection)
        workspace.setLive(false)
        runCurrent()
        return workspace
    }

    // ---- permissions ----

    @Test fun permissionsAreReadOnceAndKeepTheKindOfIdentity() = runTest {
        val gateway = Permissions()
        val workspace = connected(gateway)
        workspace.loadPermissions(); runCurrent()
        val section = workspace.state.value.permissions
        assertEquals(PermissionIdentity.Token, section.value!!.identity)
        assertEquals(setOf("/"), section.value!!.dump.paths.keys)
        assertEquals(now, section.updatedAt)
        assertFalse(section.stale || section.loading || section.sample || section.denied)
        assertNull(section.error)
        assertEquals(1, gateway.calls)
        workspace.connect(connection(token = "tester@pve")); runCurrent()
        workspace.loadPermissions(); runCurrent()
        assertEquals(PermissionIdentity.User, workspace.state.value.permissions.value!!.identity, "A password login reads as a user")
        workspace.close()
    }

    @Test fun aFailedRefreshKeepsThePermissionsAndMarksThemStale() = runTest {
        val gateway = Permissions()
        val workspace = connected(gateway)
        workspace.loadPermissions(); runCurrent()
        val first = workspace.state.value.permissions.value
        gateway.reader = { throw IOException("private endpoint data") }
        workspace.loadPermissions(); runCurrent()
        val failed = workspace.state.value.permissions
        assertEquals(first, failed.value)
        assertTrue(failed.stale)
        assertFalse(failed.denied)
        assertFalse(failed.error!!.contains("private"))
        assertTrue(workspace.state.value.connected)
        gateway.reader = { PermissionDump(emptyMap()) }
        workspace.loadPermissions(); runCurrent()
        assertFalse(workspace.state.value.permissions.stale)
        assertNull(workspace.state.value.permissions.error)
        assertTrue(workspace.state.value.permissions.value!!.dump.paths.isEmpty())
        workspace.close()
    }

    @Test fun refusedPermissionsSayNotPermittedAndKeepTheSession() = runTest {
        val gateway = Permissions().apply { reader = { throw GuestPermissionException() } }
        val workspace = connected(gateway)
        workspace.loadPermissions(); runCurrent()
        val section = workspace.state.value.permissions
        assertTrue(section.denied)
        assertEquals(PERMISSIONS_DENIED, section.error)
        assertNull(section.value)
        assertTrue(workspace.state.value.connected)
        workspace.close()
    }

    @Test fun anExpiredTokenEndsTheSessionAndLeavesNothingBehind() = runTest {
        val gateway = Permissions()
        val workspace = connected(gateway)
        workspace.loadPermissions(); runCurrent()
        assertNotNull(workspace.state.value.permissions.value)
        gateway.reader = { throw SessionExpiredException() }
        workspace.loadPermissions(); runCurrent()
        assertFalse(workspace.state.value.connected)
        assertEquals(SessionExpiredException().message, workspace.state.value.error)
        assertEquals(ReadSection(), workspace.state.value.permissions)
        workspace.close()
    }

    @Test fun aSecondPermissionReadWhileOneRunsIsIgnored() = runTest {
        val gate = CompletableDeferred<PermissionDump>()
        val gateway = Permissions().apply { reader = { gate.await() } }
        val workspace = connected(gateway)
        repeat(4) { workspace.loadPermissions() }
        runCurrent()
        assertEquals(1, gateway.calls)
        assertTrue(workspace.state.value.permissions.loading)
        gate.complete(PermissionDump(emptyMap())); runCurrent()
        assertFalse(workspace.state.value.permissions.loading)
        workspace.close()
    }

    @Test fun aLatePermissionReplyAfterAnotherConnectionIsDiscarded() = runTest {
        val gate = CompletableDeferred<PermissionDump>()
        val gateway = Permissions().apply { reader = { withContext(NonCancellable) { gate.await() } } }
        val workspace = connected(gateway)
        workspace.loadPermissions(); runCurrent()
        workspace.connect(connection(otherRemote)); workspace.setLive(false); runCurrent()
        gate.complete(PermissionDump(mapOf("/" to mapOf("Sys.Audit" to true)))); runCurrent()
        assertEquals(ReadSection(), workspace.state.value.permissions)
        workspace.close()
    }

    @Test fun demoPermissionsAreALabelledSampleAndContactNoServer() = runTest {
        val gateway = Permissions()
        val workspace = newWorkspace(gateway)
        workspace.exploreDemo(); workspace.setLive(false)
        workspace.loadPermissions(); runCurrent()
        val section = workspace.state.value.permissions
        assertTrue(section.sample)
        assertEquals(PermissionsView(demoPermissions, PermissionIdentity.Sample), section.value)
        assertEquals(0, gateway.calls)
        workspace.close()
    }

    @Test fun aPdmConnectionReadsNoPermissions() = runTest {
        val gateway = Permissions()
        val workspace = newWorkspace(gateway)
        workspace.connect(Connection(remote, "tester@pdm!read", "test-only-secret", "", PDM_KIND)); workspace.setLive(false); runCurrent()
        workspace.loadPermissions(); runCurrent()
        assertEquals(PERMISSIONS_DIRECT_ONLY, workspace.state.value.permissions.error)
        assertEquals(0, gateway.calls)
        workspace.close()
    }

    // ---- services and updates ----

    @Test fun servicesLoadForAnOnlineNodeAndAnotherNodesDataNeverStays() = runTest {
        val tools = Tools()
        val workspace = connected(tools = tools)
        workspace.loadNodeServices("node-a"); runCurrent()
        assertEquals("node-a", workspace.state.value.nodeServices.value!!.node)
        assertEquals(listOf("pveproxy"), workspace.state.value.nodeServices.value!!.services.map { it.name })
        assertEquals(now, workspace.state.value.nodeServices.updatedAt)
        workspace.loadNodeServices("node-b"); runCurrent()
        val section = workspace.state.value.nodeServices
        assertNull(section.value)
        assertEquals(NODE_TOOLS_NODE_NOT_ONLINE, section.error)
        assertEquals(listOf("node-a"), tools.servicesCalls, "An offline node is not asked")
        workspace.close()
    }

    @Test fun unknownAndMalformedNodeNamesSendNothing() = runTest {
        val tools = Tools()
        val workspace = connected(tools = tools)
        for (name in listOf("node-z", "node.example", "a/b", "", "https://node")) {
            workspace.loadNodeServices(name); runCurrent()
            assertEquals(NODE_TOOLS_NODE_UNKNOWN, workspace.state.value.nodeServices.error, name)
            workspace.loadNodeUpdates(name); runCurrent()
            assertEquals(NODE_TOOLS_NODE_UNKNOWN, workspace.state.value.nodeUpdates.error, name)
            workspace.openNodeSyslog(name); runCurrent()
            assertEquals(NODE_TOOLS_NODE_UNKNOWN, workspace.state.value.nodeSyslog.error, name)
        }
        assertTrue(tools.servicesCalls.isEmpty() && tools.updatesCalls.isEmpty() && tools.syslogCalls.isEmpty())
        workspace.close()
    }

    @Test fun refusedServicesAndUpdatesSayWhichPrivilegeIsNeeded() = runTest {
        val tools = Tools().apply {
            servicesReader = { _, _ -> throw GuestPermissionException() }
            updatesReader = { _, _ -> throw GuestPermissionException() }
        }
        val workspace = connected(tools = tools)
        workspace.loadNodeServices("node-a"); workspace.loadNodeUpdates("node-a"); runCurrent()
        assertEquals(NODE_SERVICES_DENIED, workspace.state.value.nodeServices.error)
        assertEquals(NODE_UPDATES_DENIED, workspace.state.value.nodeUpdates.error)
        assertTrue(workspace.state.value.nodeServices.denied && workspace.state.value.nodeUpdates.denied)
        assertTrue(workspace.state.value.connected)
        assertTrue(NODE_SERVICES_DENIED.contains("Sys.Audit") && NODE_UPDATES_DENIED.contains("Sys.Modify") && NODE_SYSLOG_DENIED.contains("Sys.Syslog"))
        workspace.close()
    }

    @Test fun anExpiredTokenOnANodeReadEndsTheSession() = runTest {
        val tools = Tools().apply { servicesReader = { _, _ -> throw SessionExpiredException() } }
        val workspace = connected(tools = tools)
        workspace.loadNodeServices("node-a"); runCurrent()
        assertFalse(workspace.state.value.connected)
        assertEquals(ReadSection(), workspace.state.value.nodeServices)
        workspace.close()
    }

    @Test fun aFailedNodeRefreshKeepsTheListMarkedStaleAndRecovers() = runTest {
        val tools = Tools()
        val workspace = connected(tools = tools)
        workspace.loadNodeUpdates("node-a"); runCurrent()
        val first = workspace.state.value.nodeUpdates.value
        tools.updatesReader = { _, _ -> throw IOException("private") }
        workspace.loadNodeUpdates("node-a"); runCurrent()
        val failed = workspace.state.value.nodeUpdates
        assertEquals(first, failed.value)
        assertTrue(failed.stale)
        assertFalse(failed.error!!.contains("private"))
        tools.updatesReader = { _, _ -> emptyList() }
        workspace.loadNodeUpdates("node-a"); runCurrent()
        assertFalse(workspace.state.value.nodeUpdates.stale)
        assertTrue(workspace.state.value.nodeUpdates.value!!.updates.isEmpty())
        workspace.close()
    }

    @Test fun aLateServicesReplyForAnotherNodeOrConnectionIsDiscarded() = runTest {
        val gate = CompletableDeferred<Unit>()
        val tools = Tools().apply { servicesReader = { _, node -> withContext(NonCancellable) { gate.await() }; listOf(NodeService("late-$node", null, "", null, null, null)) } }
        val workspace = connected(tools = tools)
        workspace.loadNodeServices("node-a"); runCurrent()
        tools.servicesReader = { _, node -> listOf(NodeService("fresh-$node", null, "", null, null, null)) }
        workspace.loadNodeServices("node-c"); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("fresh-node-c", workspace.state.value.nodeServices.value!!.services.single().name)
        assertEquals("node-c", workspace.state.value.nodeServices.value!!.node)
        // Disconnecting drops a reply that is still on its way.
        val late = CompletableDeferred<Unit>()
        tools.servicesReader = { _, _ -> withContext(NonCancellable) { late.await() }; emptyList() }
        workspace.loadNodeServices("node-a"); runCurrent()
        workspace.disconnect()
        late.complete(Unit); runCurrent()
        assertEquals(WorkspaceState(), workspace.state.value)
        workspace.close()
    }

    @Test fun demoServicesAndUpdatesAreLabelledSamplesAndContactNoServer() = runTest {
        val tools = Tools()
        val workspace = newWorkspace(tools = tools)
        workspace.exploreDemo(); workspace.setLive(false)
        workspace.loadNodeServices("demo-node-a"); workspace.loadNodeUpdates("demo-node-a"); runCurrent()
        assertTrue(workspace.state.value.nodeServices.sample && workspace.state.value.nodeUpdates.sample)
        assertEquals(demoNodeServices, workspace.state.value.nodeServices.value!!.services)
        assertEquals(demoPackageUpdates, workspace.state.value.nodeUpdates.value!!.updates)
        assertTrue(tools.servicesCalls.isEmpty() && tools.updatesCalls.isEmpty())
        workspace.close()
    }

    @Test fun aPdmConnectionReadsNoNodeData() = runTest {
        val tools = Tools()
        val workspace = newWorkspace(tools = tools)
        workspace.connect(Connection(remote, "tester@pdm!read", "test-only-secret", "", PDM_KIND)); workspace.setLive(false); runCurrent()
        workspace.loadNodeServices("node-a"); workspace.loadNodeUpdates("node-a"); workspace.openNodeSyslog("node-a"); workspace.selectStorage("node-a", "local"); runCurrent()
        assertEquals(NODE_TOOLS_DIRECT_ONLY, workspace.state.value.nodeServices.error)
        assertEquals(NODE_TOOLS_DIRECT_ONLY, workspace.state.value.nodeUpdates.error)
        assertEquals(NODE_TOOLS_DIRECT_ONLY, workspace.state.value.nodeSyslog.error)
        assertEquals(STORAGE_CONTENT_DIRECT_ONLY, workspace.state.value.storageContent.error)
        assertTrue(tools.servicesCalls.isEmpty() && tools.updatesCalls.isEmpty() && tools.syslogCalls.isEmpty())
        workspace.close()
    }

    // ---- system log ----

    @Test fun openingTheLogReadsTheTotalThenTheNewestPage() = runTest {
        val tools = Tools()
        val workspace = connected(tools = tools)
        workspace.openNodeSyslog("node-a"); runCurrent()
        assertEquals(listOf(SyslogCall("node-a", 0, 1, null), SyslogCall("node-a", 700, 500, null)), tools.syslogCalls)
        val view = workspace.state.value.nodeSyslog.value!!
        assertEquals((701..1_200).toList(), view.lines.map { it.n })
        assertEquals(1_200, view.total)
        assertTrue(view.following)
        assertTrue(view.canLoadEarlier)
        workspace.close()
    }

    @Test fun aShortOrEmptyLogNeedsOneOrTwoReads() = runTest {
        val tools = Tools().apply { journal.lines.subList(100, journal.lines.size).clear() }
        val workspace = connected(tools = tools)
        workspace.openNodeSyslog("node-a"); runCurrent()
        assertEquals(listOf(SyslogCall("node-a", 0, 1, null), SyslogCall("node-a", 0, 500, null)), tools.syslogCalls)
        assertEquals(100, workspace.state.value.nodeSyslog.value!!.lines.size)
        assertFalse(workspace.state.value.nodeSyslog.value!!.canLoadEarlier)
        tools.journal.lines.clear(); tools.syslogCalls.clear()
        workspace.openNodeSyslog("node-a"); runCurrent()
        assertEquals(listOf(SyslogCall("node-a", 0, 1, null)), tools.syslogCalls)
        assertTrue(workspace.state.value.nodeSyslog.value!!.lines.isEmpty())
        assertNull(workspace.state.value.nodeSyslog.error)
        workspace.close()
    }

    @Test fun theUnitFilterIsPassedOnAndABadUnitSendsNothing() = runTest {
        val tools = Tools()
        val workspace = connected(tools = tools)
        workspace.openNodeSyslog("node-a", "pveproxy"); runCurrent()
        assertTrue(tools.syslogCalls.isNotEmpty() && tools.syslogCalls.all { it.service == "pveproxy" })
        assertEquals("pveproxy", workspace.state.value.nodeSyslog.value!!.service)
        tools.syslogCalls.clear()
        workspace.openNodeSyslog("node-a", "a b"); runCurrent()
        assertEquals(NODE_SYSLOG_REFUSED, workspace.state.value.nodeSyslog.error)
        assertTrue(tools.syslogCalls.isEmpty())
        workspace.close()
    }

    @Test fun followReadsOnlyWhileThePanelIsVisibleEveryFiveSeconds() = runTest {
        val tools = Tools()
        val workspace = connected(tools = tools)
        workspace.openNodeSyslog("node-a"); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, tools.syslogCalls.size, "A hidden panel reads nothing after the first page")
        workspace.setSyslogVisible(true); runCurrent()
        assertEquals(3, tools.syslogCalls.size, "Showing the panel reads at once")
        assertEquals(SyslogCall("node-a", 1_199, 501, null), tools.syslogCalls.last(), "The held last line is read again, plus what follows it")
        advanceTimeBy(4_999); runCurrent()
        assertEquals(3, tools.syslogCalls.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(4, tools.syslogCalls.size)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(6, tools.syslogCalls.size)
        workspace.setSyslogVisible(false)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(6, tools.syslogCalls.size, "Hiding the panel stops the reads")
        workspace.close()
    }

    @Test fun aPanelAlreadyVisibleWaitsFiveSecondsBeforeItsFirstFollowRead() = runTest {
        val tools = Tools()
        val workspace = connected(tools = tools)
        workspace.setSyslogVisible(true)
        workspace.openNodeSyslog("node-a"); runCurrent()
        assertEquals(2, tools.syslogCalls.size)
        advanceTimeBy(4_999); runCurrent()
        assertEquals(2, tools.syslogCalls.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(3, tools.syslogCalls.size)
        workspace.close()
    }

    @Test fun followAppendsOnlyNewLinesAndNeverReloadsTheLog() = runTest {
        val tools = Tools()
        val workspace = connected(tools = tools)
        workspace.setSyslogVisible(true)
        workspace.openNodeSyslog("node-a"); runCurrent()
        tools.journal.add(3)
        advanceTimeBy(5_000); runCurrent()
        var view = workspace.state.value.nodeSyslog.value!!
        assertEquals((701..1_203).toList(), view.lines.map { it.n })
        assertEquals(1_203, view.total)
        assertNull(view.notice)
        advanceTimeBy(5_000); runCurrent()
        view = workspace.state.value.nodeSyslog.value!!
        assertEquals(1_203, view.lines.last().n, "Nothing new keeps the lines")
        assertEquals(listOf(SyslogCall("node-a", 0, 1, null), SyslogCall("node-a", 700, 500, null),
            SyslogCall("node-a", 1_199, 501, null), SyslogCall("node-a", 1_202, 501, null)), tools.syslogCalls)
        assertTrue(tools.syslogCalls.none { it.start == 0 && it.limit > 1 }, "The whole log is never read again")
        workspace.close()
    }

    @Test fun aBusyNodeIsCaughtUpInPagesWithinOneTick() = runTest {
        val tools = Tools()
        val workspace = connected(tools = tools)
        workspace.setSyslogVisible(true)
        workspace.openNodeSyslog("node-a"); runCurrent()
        tools.journal.add(1_100)
        advanceTimeBy(5_000); runCurrent()
        val view = workspace.state.value.nodeSyslog.value!!
        assertEquals(2_300, view.lines.last().n)
        assertEquals(2_300, view.total)
        assertEquals(listOf(1_199, 1_699, 2_199), tools.syslogCalls.drop(2).map { it.start })
        workspace.close()
    }

    @Test fun aRotatedLogIsReloadedFromItsTail() = runTest {
        val tools = Tools()
        val workspace = connected(tools = tools)
        workspace.setSyslogVisible(true)
        workspace.openNodeSyslog("node-a"); runCurrent()
        tools.journal.lines.subList(0, 50).clear() // the node dropped its oldest lines: every number moves
        tools.syslogCalls.clear()
        advanceTimeBy(5_000); runCurrent()
        val view = workspace.state.value.nodeSyslog.value!!
        assertEquals(NODE_SYSLOG_RESYNCED, view.notice)
        assertEquals(1_150, view.total)
        assertEquals((651..1_150).toList(), view.lines.map { it.n })
        assertEquals("line 1200", view.lines.last().text)
        assertEquals(listOf(SyslogCall("node-a", 1_199, 501, null), SyslogCall("node-a", 0, 1, null), SyslogCall("node-a", 650, 500, null)), tools.syslogCalls)
        workspace.close()
    }

    @Test fun aShiftedLogThatStayedLongEnoughIsStillCaughtByTheLineText() = runTest {
        val tools = Tools()
        val workspace = connected(tools = tools)
        workspace.setSyslogVisible(true)
        workspace.openNodeSyslog("node-a"); runCurrent()
        tools.journal.lines.subList(0, 50).clear()
        tools.journal.add(100) // total 1250 is above the held line number 1200, but line 1200 is now a different line
        advanceTimeBy(5_000); runCurrent()
        val view = workspace.state.value.nodeSyslog.value!!
        assertEquals(NODE_SYSLOG_RESYNCED, view.notice)
        assertEquals(1_250, view.lines.last().n)
        assertEquals((751..1_250).toList(), view.lines.map { it.n })
        workspace.close()
    }

    @Test fun followOffReadsNothingUntilRefreshOrTurnedBackOn() = runTest {
        val tools = Tools()
        val workspace = connected(tools = tools)
        workspace.setSyslogVisible(true)
        workspace.openNodeSyslog("node-a"); runCurrent()
        workspace.setSyslogFollowing(false); runCurrent()
        assertFalse(workspace.state.value.nodeSyslog.value!!.following)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, tools.syslogCalls.size)
        tools.journal.add(2)
        workspace.refreshNodeSyslog(); runCurrent()
        assertEquals(3, tools.syslogCalls.size, "Refresh is one read")
        assertEquals(1_202, workspace.state.value.nodeSyslog.value!!.lines.last().n)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(3, tools.syslogCalls.size)
        workspace.setSyslogFollowing(true); runCurrent()
        assertEquals(4, tools.syslogCalls.size, "Turning follow on reads at once")
        advanceTimeBy(5_000); runCurrent()
        assertEquals(5, tools.syslogCalls.size)
        workspace.close()
    }

    @Test fun aManualRefreshDuringAFollowReadWaitsForItAndNeverDuplicatesLines() = runTest {
        val tools = Tools()
        val workspace = connected(tools = tools)
        workspace.setSyslogVisible(true)
        workspace.openNodeSyslog("node-a"); runCurrent()
        val gate = CompletableDeferred<Unit>()
        val serve = tools.syslogReader
        tools.syslogReader = { call -> gate.await(); serve(call) }
        tools.journal.add(3)
        advanceTimeBy(5_000); runCurrent() // the follow read is now waiting inside the gateway
        workspace.refreshNodeSyslog(); runCurrent()
        assertEquals(1, tools.inFlight, "The refresh waits instead of reading the same page at the same time")
        gate.complete(Unit); runCurrent()
        assertEquals(1, tools.maxInFlight)
        val numbers = workspace.state.value.nodeSyslog.value!!.lines.map { it.n }
        assertEquals((701..1_203).toList(), numbers)
        assertEquals(numbers.size, numbers.toSet().size)
        workspace.close()
    }

    @Test fun aRefusedFollowStopsAndSaysWhichPrivilegeIsNeeded() = runTest {
        val tools = Tools()
        val workspace = connected(tools = tools)
        workspace.setSyslogVisible(true)
        workspace.openNodeSyslog("node-a"); runCurrent()
        tools.syslogReader = { throw GuestPermissionException() }
        advanceTimeBy(5_000); runCurrent()
        val section = workspace.state.value.nodeSyslog
        assertTrue(section.denied)
        assertEquals(NODE_SYSLOG_DENIED, section.error)
        assertFalse(section.value!!.following)
        assertEquals(500, section.value!!.lines.size, "The lines already read stay")
        assertTrue(workspace.state.value.connected)
        val calls = tools.syslogCalls.size
        advanceTimeBy(60_000); runCurrent()
        assertEquals(calls, tools.syslogCalls.size)
        workspace.close()
    }

    @Test fun aFailedFollowReadKeepsTheLinesStaleAndTriesAgain() = runTest {
        val tools = Tools()
        val workspace = connected(tools = tools)
        workspace.setSyslogVisible(true)
        workspace.openNodeSyslog("node-a"); runCurrent()
        var fail = true
        val good = tools.syslogReader
        tools.syslogReader = { if (fail) throw IOException("private endpoint data") else good(it) }
        advanceTimeBy(5_000); runCurrent()
        val failed = workspace.state.value.nodeSyslog
        assertTrue(failed.stale)
        assertFalse(failed.error!!.contains("private"))
        assertEquals(500, failed.value!!.lines.size)
        assertTrue(failed.value!!.following, "A network error does not turn follow off")
        fail = false
        tools.journal.add(2)
        advanceTimeBy(5_000); runCurrent()
        val recovered = workspace.state.value.nodeSyslog
        assertFalse(recovered.stale)
        assertNull(recovered.error)
        assertEquals(1_202, recovered.value!!.lines.last().n)
        workspace.close()
    }

    @Test fun anExpiredTokenDuringFollowEndsTheSession() = runTest {
        val tools = Tools()
        val workspace = connected(tools = tools)
        workspace.setSyslogVisible(true)
        workspace.openNodeSyslog("node-a"); runCurrent()
        tools.syslogReader = { throw SessionExpiredException() }
        advanceTimeBy(5_000); runCurrent()
        assertFalse(workspace.state.value.connected)
        assertEquals(ReadSection(), workspace.state.value.nodeSyslog)
        workspace.close()
    }

    @Test fun aNodeThatGoesOfflineStopsTheFollow() = runTest {
        val tools = Tools()
        var status = "online"
        val workspace = connected(tools = tools, inventory = Inventory { overviewFor(it.remoteKey, status) })
        workspace.setSyslogVisible(true)
        workspace.openNodeSyslog("node-a"); runCurrent()
        status = "offline"
        workspace.refresh(); runCurrent()
        val before = tools.syslogCalls.size
        advanceTimeBy(5_000); runCurrent()
        assertEquals(before, tools.syslogCalls.size, "An offline node is not asked")
        val section = workspace.state.value.nodeSyslog
        assertEquals(NODE_TOOLS_NODE_NOT_ONLINE, section.error)
        assertFalse(section.value!!.following)
        workspace.close()
    }

    @Test fun loadEarlierPrependsThePagesBeforeTheOldestLine() = runTest {
        val tools = Tools()
        val workspace = connected(tools = tools)
        workspace.openNodeSyslog("node-a"); runCurrent()
        workspace.loadEarlierSyslog(); runCurrent()
        assertEquals(SyslogCall("node-a", 200, 500, null), tools.syslogCalls.last())
        assertEquals((201..1_200).toList(), workspace.state.value.nodeSyslog.value!!.lines.map { it.n })
        workspace.loadEarlierSyslog(); runCurrent()
        assertEquals(SyslogCall("node-a", 0, 200, null), tools.syslogCalls.last())
        assertEquals((1..1_200).toList(), workspace.state.value.nodeSyslog.value!!.lines.map { it.n })
        assertFalse(workspace.state.value.nodeSyslog.value!!.canLoadEarlier)
        val calls = tools.syslogCalls.size
        workspace.loadEarlierSyslog(); runCurrent()
        assertEquals(calls, tools.syslogCalls.size, "The start of the log is the start")
        workspace.close()
    }

    @Test fun closingTheLogOrSwitchingConnectionStopsEveryRead() = runTest {
        val tools = Tools()
        val workspace = connected(tools = tools)
        workspace.setSyslogVisible(true)
        workspace.openNodeSyslog("node-a"); runCurrent()
        workspace.closeNodeSyslog()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, tools.syslogCalls.size)
        assertEquals(ReadSection(), workspace.state.value.nodeSyslog)
        workspace.openNodeSyslog("node-a"); runCurrent()
        assertEquals(4, tools.syslogCalls.size)
        workspace.connect(connection(otherRemote)); workspace.setLive(false); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(4, tools.syslogCalls.size, "The old session's loop is gone")
        assertEquals(ReadSection(), workspace.state.value.nodeSyslog)
        workspace.close()
    }

    @Test fun aLateFirstPageForAnEarlierOpenIsDiscarded() = runTest {
        val gate = CompletableDeferred<Unit>()
        val tools = Tools()
        val workspace = connected(tools = tools)
        tools.syslogReader = { call -> if (call.service == null) withContext(NonCancellable) { gate.await() }; tools.journal.page(call.start, call.limit) }
        workspace.openNodeSyslog("node-a"); runCurrent()
        workspace.openNodeSyslog("node-a", "pveproxy"); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("pveproxy", workspace.state.value.nodeSyslog.value!!.service)
        workspace.close()
    }

    @Test fun demoLogGrowsWithoutAServer() = runTest {
        val tools = Tools()
        val workspace = newWorkspace(tools = tools)
        workspace.exploreDemo(); workspace.setLive(false)
        workspace.setSyslogVisible(true)
        workspace.openNodeSyslog("demo-node-a"); runCurrent()
        var view = workspace.state.value.nodeSyslog.value!!
        assertTrue(workspace.state.value.nodeSyslog.sample)
        assertEquals(DEMO_SYSLOG_START_TOTAL, view.lines.last().n)
        assertEquals(SYSLOG_PAGE, view.lines.size)
        advanceTimeBy(5_000); runCurrent()
        view = workspace.state.value.nodeSyslog.value!!
        assertEquals(DEMO_SYSLOG_START_TOTAL + 2, view.lines.last().n)
        assertEquals(DEMO_SYSLOG_START_TOTAL + 2, view.total)
        workspace.loadEarlierSyslog(); runCurrent()
        // The tail began at line 741 (1240 - 500 + 1); one more page of 500 lines goes before it.
        assertEquals(741 - 500, workspace.state.value.nodeSyslog.value!!.lines.first().n)
        assertTrue(tools.syslogCalls.isEmpty(), "Demo contacts no server")
        workspace.close()
    }

    // ---- storage contents ----

    @Test fun selectingARecordedStorageReadsItsContentOnce() = runTest {
        val storage = Storage()
        val workspace = connected(storage = storage)
        workspace.selectStorage("node-a", "local"); runCurrent()
        assertEquals(listOf(Storage.Call("node-a", "local", null)), storage.calls)
        val key = StorageKey(remote, "node-a", "local")
        assertEquals(key, workspace.state.value.storageSelection)
        val view = workspace.state.value.storageContent.value!!
        assertEquals(key, view.key)
        assertNull(view.filter)
        assertEquals(listOf("local:iso/a.iso"), view.volumes.map { it.volid })
        assertEquals(now, workspace.state.value.storageContent.updatedAt)
        workspace.refreshStorageContent(); runCurrent()
        assertEquals(2, storage.calls.size)
        workspace.close()
    }

    @Test fun aTypeFilterIsPassedToTheServerAndAnUnknownTypeIsRefused() = runTest {
        val storage = Storage()
        val workspace = connected(storage = storage)
        workspace.selectStorage("node-a", "local", "backup"); runCurrent()
        assertEquals(Storage.Call("node-a", "local", "backup"), storage.calls.single())
        assertEquals("backup", workspace.state.value.storageContent.value!!.filter)
        workspace.refreshStorageContent(); runCurrent()
        assertEquals("backup", storage.calls.last().content, "Refresh keeps the filter")
        storage.calls.clear()
        workspace.selectStorage("node-a", "local", "bogus"); runCurrent()
        assertTrue(storage.calls.isEmpty())
        assertEquals(STORAGE_CONTENT_TYPE_REFUSED, workspace.state.value.storageContent.error)
        workspace.close()
    }

    @Test fun onlyAStorageTheInventoryListsIsEverRead() = runTest {
        val storage = Storage()
        val workspace = connected(storage = storage)
        for ((node, id) in listOf("node-a" to "missing", "node-z" to "local", "node-c" to "data", "node.example" to "local", "node-a" to "x")) {
            workspace.selectStorage(node, id); runCurrent()
            assertEquals(STORAGE_NOT_RECORDED, workspace.state.value.storageContent.error, "$node/$id")
            assertNull(workspace.state.value.storageSelection)
        }
        assertTrue(storage.calls.isEmpty())
        workspace.close()
    }

    @Test fun aStorageOnAnOfflineNodeIsNotRead() = runTest {
        val storage = Storage()
        val workspace = connected(storage = storage)
        workspace.selectStorage("node-b", "local"); runCurrent()
        assertEquals(STORAGE_NODE_NOT_ONLINE, workspace.state.value.storageContent.error)
        assertEquals(StorageKey(remote, "node-b", "local"), workspace.state.value.storageSelection)
        assertTrue(storage.calls.isEmpty())
        workspace.close()
    }

    @Test fun switchingStorageDropsTheOldContentAndALateReplyForIt() = runTest {
        val gate = CompletableDeferred<Unit>()
        val storage = Storage()
        val workspace = connected(storage = storage)
        workspace.selectStorage("node-a", "local"); runCurrent()
        storage.reader = { call -> if (call.storage == "local") withContext(NonCancellable) { gate.await() }; listOf(volume("${call.storage}:iso/late.iso")) }
        workspace.selectStorage("node-c", "local"); runCurrent()
        assertNull(workspace.state.value.storageContent.value, "The previous storage's rows are not shown for another one")
        assertTrue(workspace.state.value.storageContent.loading)
        storage.reader = { call -> listOf(volume("${call.storage}:iso/fresh.iso")) }
        workspace.selectStorage("node-a", "data"); runCurrent()
        gate.complete(Unit); runCurrent()
        val view = workspace.state.value.storageContent.value!!
        assertEquals(StorageKey(remote, "node-a", "data"), view.key)
        assertEquals(listOf("data:iso/fresh.iso"), view.volumes.map { it.volid })
        workspace.close()
    }

    @Test fun aRefusedStorageSaysWhichPrivilegeIsNeededAndKeepsTheSession() = runTest {
        val storage = Storage().apply { reader = { throw GuestPermissionException() } }
        val workspace = connected(storage = storage)
        workspace.selectStorage("node-a", "local"); runCurrent()
        val section = workspace.state.value.storageContent
        assertTrue(section.denied)
        assertEquals(STORAGE_CONTENT_DENIED, section.error)
        assertTrue(STORAGE_CONTENT_DENIED.contains("Datastore.Audit") && STORAGE_CONTENT_DENIED.contains("Datastore.AllocateSpace"))
        assertTrue(workspace.state.value.connected)
        storage.reader = { throw SessionExpiredException() }
        workspace.selectStorage("node-a", "local"); runCurrent()
        assertFalse(workspace.state.value.connected)
        workspace.close()
    }

    @Test fun aFailedStorageRefreshKeepsTheRowsStale() = runTest {
        val storage = Storage()
        val workspace = connected(storage = storage)
        workspace.selectStorage("node-a", "local"); runCurrent()
        val first = workspace.state.value.storageContent.value
        storage.reader = { throw IOException("private") }
        workspace.refreshStorageContent(); runCurrent()
        val failed = workspace.state.value.storageContent
        assertEquals(first, failed.value)
        assertTrue(failed.stale)
        assertFalse(failed.error!!.contains("private"))
        storage.reader = { emptyList() }
        workspace.refreshStorageContent(); runCurrent()
        assertFalse(workspace.state.value.storageContent.stale)
        assertTrue(workspace.state.value.storageContent.value!!.volumes.isEmpty())
        workspace.clearStorageSelection()
        assertNull(workspace.state.value.storageSelection)
        assertEquals(ReadSection(), workspace.state.value.storageContent)
        workspace.close()
    }

    @Test fun demoStorageContentFollowsTheRecordedContentTypesAndContactsNoServer() = runTest {
        val storage = Storage()
        val workspace = newWorkspace(storage = storage)
        workspace.exploreDemo(); workspace.setLive(false)
        workspace.selectStorage("demo-node-a", "shared-data"); runCurrent()
        val section = workspace.state.value.storageContent
        assertTrue(section.sample)
        assertEquals(setOf("images", "backup"), section.value!!.volumes.mapNotNull { it.content }.toSet())
        workspace.selectStorage("demo-node-a", "shared-data", "backup"); runCurrent()
        assertEquals(setOf("backup"), workspace.state.value.storageContent.value!!.volumes.mapNotNull { it.content }.toSet())
        workspace.selectStorage("demo-node-b", "missing"); runCurrent()
        assertEquals(STORAGE_NOT_RECORDED, workspace.state.value.storageContent.error)
        assertTrue(storage.calls.isEmpty())
        workspace.close()
    }

    // ---- the session ----

    @Test fun disconnectingDropsEveryViewEvenWhileRepliesAreOnTheirWay() = runTest {
        val gate = CompletableDeferred<Unit>()
        val permissions = Permissions().apply { reader = { withContext(NonCancellable) { gate.await() }; PermissionDump(emptyMap()) } }
        val tools = Tools().apply {
            servicesReader = { _, _ -> withContext(NonCancellable) { gate.await() }; emptyList() }
            updatesReader = { _, _ -> withContext(NonCancellable) { gate.await() }; emptyList() }
            syslogReader = { withContext(NonCancellable) { gate.await() }; journal.page(it.start, it.limit) }
        }
        val storage = Storage().apply { reader = { withContext(NonCancellable) { gate.await() }; emptyList() } }
        val workspace = connected(permissions, tools, storage)
        workspace.setSyslogVisible(true)
        workspace.loadPermissions(); workspace.loadNodeServices("node-a"); workspace.loadNodeUpdates("node-a")
        workspace.openNodeSyslog("node-a"); workspace.selectStorage("node-a", "local"); runCurrent()
        workspace.disconnect()
        gate.complete(Unit); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(WorkspaceState(), workspace.state.value)
        workspace.close()
    }

    @Test fun theViewsHoldNoSecretAndNoHostnameFromTheServer() = runTest {
        val workspace = connected()
        workspace.loadPermissions(); workspace.loadNodeServices("node-a"); workspace.loadNodeUpdates("node-a"); workspace.selectStorage("node-a", "local")
        workspace.openNodeSyslog("node-a"); runCurrent()
        val text = listOf(workspace.state.value.permissions, workspace.state.value.nodeServices, workspace.state.value.nodeUpdates,
            workspace.state.value.nodeSyslog, workspace.state.value.storageContent).joinToString { it.toString().take(4_000) }
        assertFalse(text.contains("test-only-secret"))
        assertFalse(text.contains("tester@pve"))
        workspace.close()
    }
}
