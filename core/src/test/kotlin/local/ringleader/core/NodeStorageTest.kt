package local.ringleader.core

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Handwritten parser and transport fixtures. None of these bodies is a captured server response. */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeStorageTest {
    private val remote = "https://example.test:8006"
    private val other = "https://other.example.test:8006"

    @Test fun recordedNumbersKeepZeroAndLeaveAbsentFieldsNull() {
        val overview = parseOverview("""{"data":[
            {"type":"node","node":"node-a","status":"online","cpu":0.25,"maxcpu":4,"mem":10,"maxmem":20,"disk":3,"maxdisk":30,"uptime":5,"hastate":"maintenance","level":"","cgroup-mode":2,"host-arch":"amd64","id":"node/node-a","ssl_fingerprint":"abc","ip":"10.1.2.3"},
            {"type":"node","node":"node-b","status":"online","cpu":0,"mem":0,"disk":0,"maxdisk":0,"uptime":0},
            {"type":"node","node":"node-c","status":"online","maxmem":8},
            {"type":"storage","node":"node-a","storage":"local-lvm","shared":0,"disk":7,"maxdisk":9,"status":"available","plugintype":"lvmthin","content":"images","server":"secret.internal","username":"root","password":"s3cret","fingerprint":"abc","portal":"10.9.9.9"},
            {"type":"qemu","vmid":10,"name":"desktop","node":"node-a","status":"stopped"},
            {"type":"lxc","vmid":12,"name":"service","node":"node-a","status":"running"}
        ]}""", remote)
        val first = overview.members.single { it.name == "node-a" }
        assertEquals("online", first.status)
        assertEquals("maintenance", first.haState)
        assertEquals(0.25, first.cpuFraction)
        assertEquals(10L, first.memoryBytes)
        assertEquals(3L, first.rootBytes)
        assertEquals(30L, first.maxRootBytes)
        val zero = overview.members.single { it.name == "node-b" }
        assertEquals(0.0, zero.cpuFraction)
        assertEquals(0L, zero.memoryBytes)
        assertEquals(0L, zero.rootBytes)
        val absent = overview.members.single { it.name == "node-c" }
        assertNull(absent.cpuFraction)
        assertNull(absent.memoryBytes)
        assertEquals(8L, absent.maxMemoryBytes)
        assertNull(absent.haState)
        val storage = overview.storage.single()
        assertEquals(7L, storage.diskBytes)
        assertEquals(false, storage.shared)
        assertEquals("lvmthin", storage.pluginType)
        assertEquals(listOf(10, 12), overview.guests.map { it.id })
        val text = overview.toString()
        assertFalse(text.contains("abc"))
        assertFalse(text.contains("10.1.2.3"))
        assertFalse(text.contains("secret.internal"))
        assertFalse(text.contains("s3cret"))
        assertFalse(text.contains("cgroup-mode"))
        assertFalse(text.contains("host-arch"))
        assertEquals(25.0, cpuUsagePercent(first.cpuFraction!!))
        assertEquals(0.0, cpuUsagePercent(zero.cpuFraction!!))
    }

    @Test fun offlineAndRejectedNamesDoNotBuildAStatusPath() {
        val overview = parseOverview("""{"data":[
            {"type":"node","node":"node-a","status":"offline","maxmem":99},
            {"type":"node","node":"node-b","status":"unknown","cpu":0.5},
            {"type":"node","node":"node.example","status":"online","cpu":1},
            {"type":"node","node":"https://node","status":"online"},
            {"type":"node","node":"ok","status":"online","cpu":0},
            {"type":"storage","node":"node.example","storage":"local","disk":1,"maxdisk":2},
            {"type":"storage","node":"ok","storage":"a","disk":1,"maxdisk":2},
            {"type":"qemu","vmid":4,"name":"kept","node":"ok","status":"running"}
        ]}""", remote)
        val offline = overview.members.single { it.name == "node-a" }
        assertEquals("offline", offline.status)
        assertNull(offline.memoryBytes)
        assertEquals(99L, offline.maxMemoryBytes)
        assertNull(nodeStatusPathForMember(offline))
        assertNull(nodeStatusPathForMember(overview.members.single { it.name == "node-b" }))
        assertEquals(listOf("node-a", "node-b", "ok"), overview.members.map { it.name })
        assertEquals("/api2/json/nodes/ok/status", nodeStatusPathForMember(overview.members.single { it.name == "ok" }))
        assertTrue(overview.storage.isEmpty())
        assertEquals(listOf(4), overview.guests.map { it.id })
        assertNull(overview.indexError)
        val connection = direct(remote)
        assertFailsWith<IllegalArgumentException> { directNodeStatusPath(connection, remote, "node.example", "") }
        assertFailsWith<IllegalArgumentException> { directNodeStatusPath(connection, remote, "https://node", "") }
        connection.close()
    }

    @Test fun sharedRowsStaySeparateAndAreNotSummed() {
        val equal = parseOverview("""{"data":[
            {"type":"storage","node":"node-a","storage":"shared-data","shared":1,"disk":10,"maxdisk":50,"status":"available"},
            {"type":"storage","node":"node-b","storage":"shared-data","shared":1,"disk":10,"maxdisk":50,"status":"available"},
            {"type":"storage","node":"node-a","storage":"local-lvm","shared":0,"disk":4,"maxdisk":8,"status":"available"},
            {"type":"storage","node":"node-a","storage":"missing-size","shared":1,"status":"unknown"},
            {"type":"storage","node":"node-a","storage":"quoted","shared":"1","disk":3,"maxdisk":3,"status":"available"},
            {"type":"storage","node":"node-b","storage":"flagged","shared":true,"disk":3,"maxdisk":3,"status":"available"}
        ]}""", remote)
        assertEquals(listOf("node-a" to "shared-data", "node-b" to "shared-data"), equal.storage.filter { it.id == "shared-data" }.map { it.node to it.id })
        val matched = sharedStorageReport(equal.storage.filter { it.id == "shared-data" })
        assertTrue(matched.disagreements.isEmpty())
        assertNull(matched.summedBytes)
        assertNull(equal.storage.single { it.id == "missing-size" }.diskBytes)
        assertEquals(true, equal.storage.single { it.id == "missing-size" }.shared)
        assertNull(equal.storage.single { it.id == "quoted" }.shared)
        assertNull(equal.storage.single { it.id == "flagged" }.shared)
        val uneven = parseOverview("""{"data":[
            {"type":"storage","node":"node-a","storage":"shared-data","shared":1,"disk":10,"maxdisk":50,"status":"available"},
            {"type":"storage","node":"node-b","storage":"shared-data","shared":1,"disk":11,"maxdisk":50,"status":"available"}
        ]}""", remote)
        val report = sharedStorageReport(uneven.storage)
        assertEquals(setOf("shared-data"), report.disagreements)
        assertNull(report.summedBytes)
        assertEquals(listOf(10L, 11L), uneven.storage.map { it.diskBytes })
    }

    @Test fun duplicateIdentityFailsTheIndexAndStillParsesGuests() {
        val parsed = parseOverview("""{"data":[
            {"type":"node","node":"node-a","status":"online","cpu":0.5},
            {"type":"node","node":"node-a","status":"offline"},
            {"type":"storage","node":"node-b","storage":"local-lvm","shared":0,"disk":1,"maxdisk":2},
            {"type":"storage","node":"node-b","storage":"local-lvm","shared":1,"disk":9,"maxdisk":9},
            {"type":"qemu","vmid":70,"name":"beta","node":"node-a","status":"running"}
        ]}""", remote)
        assertEquals(RECORDED_INDEX_ERROR, parsed.indexError)
        assertTrue(parsed.indexStale)
        assertTrue(parsed.members.isEmpty())
        assertTrue(parsed.storage.isEmpty())
        assertEquals("beta", parsed.guests.single().name)
        val indexError = checkNotNull(parsed.indexError)
        assertFalse(indexError.contains("Permission"))
        assertFalse(indexError.contains("certificate"))
        assertFalse(indexError.contains("fingerprint"))
    }

    @Test fun duplicateIndexKeepsThePreviousRowsAndPublishesTheNewGuests() = runTest {
        val fake = Fake()
        fake.inventory = { goodOverview() }
        fake.handler = { _, _, node, _ -> fake.count++; live(node) }
        val workspace = Workspace(this, fake, nodeStatusGateway = fake)
        workspace.connect(direct(remote)); runCurrent()
        workspace.selectMember("node-a"); runCurrent()
        assertEquals(1, fake.count)
        assertEquals("alpha", workspace.state.value.overview!!.guests.single().name)
        fake.inventory = { duplicateOverview() }
        workspace.refresh(); runCurrent()
        val state = workspace.state.value
        assertEquals("beta", state.overview!!.guests.single().name)
        assertEquals(listOf("node-a"), state.overview.members.map { it.name })
        assertEquals("local-lvm", state.overview.storage.single().id)
        assertTrue(state.overview.indexStale)
        assertEquals(RECORDED_INDEX_ERROR, state.overview.indexError)
        assertFalse(state.stale)
        assertNull(state.error)
        assertTrue(state.connected)
        workspace.refreshNodeStatus(); runCurrent()
        assertEquals(1, fake.count)
        assertNull(workspace.state.value.nodeStatusNote)
        assertEquals(live("node-a"), workspace.state.value.nodeStatus)
        assertTrue(workspace.state.value.nodeStatusStale)
        workspace.close()
    }

    @Test fun selectionDuringSyncReadsOnlyThatNodeAfterSync() = runTest {
        val fake = Fake()
        fake.inventory = { goodOverview() }
        fake.handler = { _, _, node, _ -> fake.count++; live(node) }
        val workspace = Workspace(this, fake, nodeStatusGateway = fake)
        workspace.connect(direct(remote)); runCurrent()
        val refresh = CompletableDeferred<Overview>()
        fake.inventory = { refresh.await() }
        workspace.refresh(); runCurrent()
        workspace.selectMember("node-a"); runCurrent()
        assertEquals(0, fake.count)
        refresh.complete(goodOverview()); runCurrent()
        assertEquals(1, fake.count)
        assertEquals(live("node-a"), workspace.state.value.nodeStatus)
        fake.inventory = { goodOverview() }
        workspace.refresh(); runCurrent()
        assertEquals(1, fake.count)
        workspace.close()
    }

    @Test fun liveStatusKeepsRootSeparateAndRejectsAFractionAboveOne() {
        val status = parseNodeStatus("node-a", """{"data":{"cpu":1.5,"uptime":9,"memory":{"used":10,"total":20,"free":1},"rootfs":{"used":3,"total":30,"avail":4},"pveversion":"pve-manager/9.0.0","kversion":"ignored","loadavg":["0.10","0.20","0.30"]}}""")
        assertEquals(1.5, status.cpuFraction)
        assertNull(cpuUsagePercent(status.cpuFraction))
        assertEquals(10L, status.memoryUsedBytes)
        assertEquals(3L, status.rootUsedBytes)
        assertEquals("pve-manager/9.0.0", status.pveVersion)
        assertFails { parseNodeStatus("node-a", """{"data":{"cpu":0.5,"uptime":1,"memory":{"total":2},"rootfs":{"used":3,"total":4}}}""") }
        assertFails { parseNodeStatus("node-a", """{"data":{"cpu":"0.5","uptime":1,"memory":{"used":1,"total":2},"rootfs":{"used":3,"total":4}}}""") }
        assertFails { parseNodeStatus("node.example", """{"data":{"cpu":0.5,"uptime":1,"memory":{"used":1,"total":2},"rootfs":{"used":3,"total":4}}}""") }
        val zero = parseNodeStatus("node-a", """{"data":{"cpu":0,"uptime":0,"memory":{"used":0,"total":0},"rootfs":{"used":0,"total":0}}}""")
        assertEquals(0.0, cpuUsagePercent(zero.cpuFraction))
        assertEquals(0L, zero.rootUsedBytes)
        assertNull(zero.pveVersion)
    }

    @Test fun directGateRejectsPdmScopedAndForeignNodesBeforeHttp() = fixture { address, pin, calls, _, _ ->
        Connection(address, "fixture@pve!read", "test-only", pin, PDM_KIND).use { pdm ->
            val pdmRefusal = assertFailsWith<IllegalArgumentException> { directNodeStatusPath(pdm, pdm.remoteKey, "node-a", "") }
            assertEquals(DIRECT_REQUEST_REFUSAL, pdmRefusal.message)
            assertFalse(pdmRefusal.message!!.contains("/api2/json/nodes"))
            assertFailsWith<IllegalArgumentException> { PveClient().nodeStatus(pdm, "node-a") }
            pdm.withSecret { secret ->
                assertFailsWith<IllegalArgumentException> {
                    ProxmoxTransport().pdm(pdm.address, pdm.tokenId, secret, pin, "/api2/json/nodes/node-a/status")
                }
                assertFailsWith<IllegalArgumentException> {
                    ProxmoxTransport().direct(pdm.address, pdm.tokenId, secret, pin, "/api2/json/nodes")
                }
                assertFailsWith<IllegalArgumentException> {
                    ProxmoxTransport().direct(pdm.address, pdm.tokenId, secret, pin, "/api2/json/nodes/node-a/storage")
                }
            }
        }
        Connection(address, "fixture@pve!read", "test-only", pin).use { connection ->
            val scoped = assertFailsWith<IllegalArgumentException> { directNodeStatusPath(connection, connection.remoteKey, "node-a", "aa") }
            assertEquals(DIRECT_GUEST_REFUSAL, scoped.message)
            assertFalse(scoped.message!!.contains("/api2/json/nodes"))
            val foreign = assertFailsWith<IllegalArgumentException> { directNodeStatusPath(connection, other, "node-a", "") }
            assertFalse(foreign.message!!.contains("/api2/json/nodes"))
            assertEquals("/api2/json/nodes/node-a/status", directNodeStatusPath(connection, connection.remoteKey, "node-a", ""))
        }
        assertEquals(0, calls.get())
    }

    @Test fun stoppedGuestInspectionSkipsTheAddressLookup() {
        for (status in listOf("stopped", "running")) {
            val paths = mutableListOf<String>()
            fixture(responseBody = """{"data":{}}""", onPath = { paths += it }) { address, pin, _, _, _ ->
                Connection(address, "fixture@pve!read", "test-only", pin).use { connection ->
                    val guest = Guest(connection.remoteKey, 112, "desktop", "node-a", "VM", status)
                    val inspection = kotlinx.coroutines.runBlocking { LiveGuestInspectionGateway().read(connection, guest, emptyList(), "hour") }
                    if (status == "stopped") assertEquals(GUEST_OFF_ADDRESSES, inspection.addresses.error)
                }
            }
            assertEquals(status == "running", paths.any { "/agent/" in it }, "agent lookup for a $status guest")
            assertTrue(paths.any { it.endsWith("/rrddata") || "/rrddata" in it }, "history still read for a $status guest")
        }
    }

    @Test fun nodeStatusUsesTheDirectHeaderAndKeepsPinRedirectAndNotFoundDistinct() {
        val paths = mutableListOf<String>()
        val methods = mutableListOf<String>()
        val queries = mutableListOf<String?>()
        val headers = mutableListOf<String?>()
        fixture(responseBody = statusBody(0.5), onPath = { paths += it }, methods = methods, queries = queries, headers = headers) { address, pin, calls, _, _ ->
            Connection(address, "fixture@pve!read", "test-only", pin).use { connection ->
                val status = PveClient().nodeStatus(connection, "node-a")
                assertEquals(10L, status.memoryUsedBytes)
                assertEquals(3L, status.rootUsedBytes)
                assertEquals(100.0, cpuUsagePercent(1.0))
            }
            assertEquals(1, calls.get())
        }
        assertEquals(listOf("/api2/json/nodes/node-a/status"), paths)
        assertEquals(listOf("GET"), methods)
        assertEquals(listOf<String?>(null), queries)
        assertTrue(headers.single()!!.startsWith("PVEAPIToken="))
        assertFalse(headers.single()!!.startsWith("PDMAPIToken"))
        fixture { address, _, calls, _, _ ->
            Connection(address, "fixture@pve!read", "test-only", "00".repeat(32)).use { connection ->
                assertFailsWith<SSLException> { PveClient().nodeStatus(connection, "node-a") }
            }
            assertEquals(0, calls.get())
        }
        fixture(302, responseBody = statusBody(0.5)) { address, pin, calls, _, _ ->
            Connection(address, "fixture@pve!read", "test-only", pin).use { connection ->
                val error = assertFailsWith<IllegalStateException> { PveClient().nodeStatus(connection, "node-a") }
                assertTrue(error.message!!.contains("redirected"))
                assertFalse(error is SessionExpiredException)
            }
            assertEquals(1, calls.get())
        }
        fixture(404) { address, pin, _, _, _ ->
            Connection(address, "fixture@pve!read", "test-only", pin).use { connection ->
                val node = assertFailsWith<IllegalStateException> { PveClient().nodeStatus(connection, "node-a") }
                assertEquals(NODE_STATUS_NOT_FOUND, node.message)
                assertFalse(node.message!!.contains("guest"))
                val guest = Guest(connection.remoteKey, 112, "desktop", "node-a", "VM", "running")
                val moved = assertFailsWith<IllegalStateException> { PveClient().details(connection, guest) }
                assertEquals(GUEST_NOT_FOUND, moved.message)
            }
        }
        fixture(401) { address, pin, _, _, _ ->
            Connection(address, "fixture@pve!read", "test-only", pin).use { connection ->
                assertFailsWith<SessionExpiredException> { PveClient().nodeStatus(connection, "node-a") }
            }
        }
        fixture(403) { address, pin, _, _, _ ->
            Connection(address, "fixture@pve!read", "test-only", pin).use { connection ->
                val error = assertFailsWith<IllegalStateException> { PveClient().nodeStatus(connection, "node-a") }
                assertFalse(error is SessionExpiredException)
                assertTrue(error.message!!.contains("Permission"))
            }
        }
        fixture(500) { address, pin, _, _, _ ->
            Connection(address, "fixture@pve!read", "test-only", pin).use { connection ->
                val error = assertFailsWith<IllegalStateException> { PveClient().nodeStatus(connection, "node-a") }
                assertFalse(error is SessionExpiredException)
                assertTrue(error.message!!.contains("HTTP 500"))
            }
        }
    }

    @Test fun selectionGuardsDropLateResultsAndPreservePriorStatus() = runTest {
        val fake = Fake()
        val first = CompletableDeferred<NodeLiveStatus>()
        fake.inventory = { onlineOverview() }
        val workspace = Workspace(this, fake, nodeStatusGateway = fake)
        val connection = direct(remote)
        fake.handler = { _, _, node, _ ->
            fake.count++
            if (fake.count == 1) withContext(NonCancellable) { first.await() } else live(node)
        }
        workspace.connect(connection); runCurrent()
        workspace.selectMember("node-a"); runCurrent()
        workspace.selectMember("node-b"); runCurrent()
        first.complete(live("node-a", 0.25)); runCurrent()
        assertEquals("node-b", workspace.state.value.nodeStatus!!.node)
        assertEquals("node-b", workspace.state.value.selectedNode)
        assertNull(workspace.state.value.selected)
        val prior = workspace.state.value.nodeStatus
        fake.handler = { _, _, _, _ -> error("Permission was refused. Check this token's resource permissions.") }
        workspace.refreshNodeStatus(); runCurrent()
        assertEquals(prior, workspace.state.value.nodeStatus)
        assertTrue(workspace.state.value.nodeStatusStale)
        assertTrue(workspace.state.value.nodeStatusError!!.contains("Permission"))
        assertTrue(workspace.state.value.connected)
        assertEquals(listOf(100), workspace.state.value.overview!!.guests.map { it.id })
        fake.handler = { _, _, _, _ -> error("The server returned HTTP 500.") }
        workspace.refreshNodeStatus(); runCurrent()
        assertEquals(prior, workspace.state.value.nodeStatus)
        assertTrue(workspace.state.value.nodeStatusError!!.contains("HTTP 500"))
        assertEquals(listOf(100), workspace.state.value.overview!!.guests.map { it.id })
        fake.handler = { _, _, _, _ -> error(NODE_STATUS_NOT_FOUND) }
        workspace.refreshNodeStatus(); runCurrent()
        assertEquals(NODE_STATUS_NOT_FOUND, workspace.state.value.nodeStatusError)
        assertFalse(workspace.state.value.nodeStatusError!!.contains("no longer at that node"))
        assertEquals(prior, workspace.state.value.nodeStatus)
        workspace.select(Guest(remote, 100, "desktop", "node.example", "VM", "running")); runCurrent()
        assertNull(workspace.state.value.selectedNode)
        assertEquals("node.example", workspace.state.value.selected!!.node)
        workspace.close()
    }

    @Test fun removalSwitchAndExpiryDropTheLiveRead() = runTest {
        val fake = Fake()
        val gate = CompletableDeferred<NodeLiveStatus>()
        fake.inventory = { onlineOverview() }
        fake.handler = { _, _, node, _ -> withContext(NonCancellable) { gate.await() }; live(node) }
        val workspace = Workspace(this, fake, nodeStatusGateway = fake)
        val firstConnection = direct(remote)
        workspace.connect(firstConnection); runCurrent()
        workspace.selectMember("node-a"); runCurrent()
        fake.inventory = { overviewWithoutNode() }
        workspace.refresh(); runCurrent()
        gate.complete(live("node-a")); runCurrent()
        assertNull(workspace.state.value.selectedNode)
        assertNull(workspace.state.value.nodeStatus)
        assertEquals(listOf(100), workspace.state.value.overview!!.guests.map { it.id })
        val switched = CompletableDeferred<NodeLiveStatus>()
        fake.inventory = { connection -> if (connection.remoteKey == remote) onlineOverview() else Overview(0, emptyList()) }
        fake.handler = { _, _, node, _ -> withContext(NonCancellable) { switched.await() }; live(node) }
        workspace.refresh(); runCurrent()
        workspace.selectMember("node-a"); runCurrent()
        val second = direct(other)
        workspace.connect(second); runCurrent()
        switched.complete(live("node-a")); runCurrent()
        assertEquals(other, workspace.state.value.address)
        assertNull(workspace.state.value.selectedNode)
        assertNull(workspace.state.value.nodeStatus)
        assertTrue(workspace.state.value.overview!!.members.isEmpty())
        assertFailsWith<IllegalStateException> { firstConnection.withSecret { it } }
        workspace.close()
    }

    @Test fun nodeStatusExpiryClearsTheSession() = runTest {
        val fake = Fake()
        fake.inventory = { onlineOverview() }
        fake.handler = { _, _, _, _ -> throw SessionExpiredException() }
        val workspace = Workspace(this, fake, nodeStatusGateway = fake)
        val connection = direct(remote)
        workspace.connect(connection); runCurrent()
        val guests = workspace.state.value.overview!!.guests
        assertEquals(listOf(100), guests.map { it.id })
        workspace.selectMember("node-a"); runCurrent()
        assertFalse(workspace.state.value.connected)
        assertNull(workspace.state.value.overview)
        assertNull(workspace.state.value.selectedNode)
        assertFailsWith<IllegalStateException> { connection.withSecret { it } }
        workspace.close()
    }

    @Test fun refreshDoesNotFanOutAndOfflineDemoOrForeignNamesDoNotRequestStatus() = runTest {
        val fake = Fake()
        fake.inventory = { onlineOverview() }
        fake.handler = { _, _, node, _ -> fake.count++; live(node) }
        val workspace = Workspace(this, fake, nodeStatusGateway = fake)
        workspace.connect(direct(remote)); runCurrent()
        workspace.select(Guest(remote, 100, "desktop", "node.example", "VM", "running")); runCurrent()
        workspace.selectMember("https://node")
        workspace.selectMember("node.example")
        assertEquals(0, fake.count)
        assertEquals("node.example", workspace.state.value.selected!!.node)
        workspace.selectMember("node-a"); runCurrent()
        assertEquals(1, fake.count)
        assertNull(workspace.state.value.selected)
        workspace.refresh(); runCurrent()
        assertEquals(1, fake.count)
        fake.inventory = { offlineOverview() }
        workspace.refresh(); runCurrent()
        assertEquals("offline", workspace.state.value.overview!!.members.single().status)
        workspace.refreshNodeStatus(); runCurrent()
        assertEquals(1, fake.count)
        assertEquals(LIVE_STATUS_NOT_REQUESTED, workspace.state.value.nodeStatusNote)
        assertNull(workspace.state.value.nodeStatus)
        workspace.exploreDemo()
        workspace.selectMember("demo-node-a"); runCurrent()
        workspace.refresh(); runCurrent()
        assertEquals(1, fake.count)
        assertEquals(LIVE_STATUS_SAMPLE, workspace.state.value.nodeStatusNote)
        assertNull(workspace.state.value.nodeStatus)
        assertEquals(DEMO_REMOTE_KEY, demoOverview.members.first().remote)
        assertNull(nodeStatusPathForMember(demoOverview.members.first()))
        assertFails { serverOrigin("demo-node-a") }
        assertFails { serverOrigin("demo-node-b") }
        assertNull(sharedStorageReport(demoOverview.storage).summedBytes)
        workspace.close()
    }

    private fun direct(address: String) = Connection(address, "tester@pve!read", "test-only-secret", "")

    private fun live(node: String, cpu: Double = 0.5) = NodeLiveStatus(node, cpu, 10, 20, 3, 30, 9, "pve-manager/9.0.0")

    private fun goodOverview() = parseOverview("""{"data":[
        {"type":"node","node":"node-a","status":"online","cpu":0.25,"mem":10,"maxmem":20,"disk":3,"maxdisk":4},
        {"type":"storage","node":"node-a","storage":"local-lvm","shared":0,"disk":4,"maxdisk":8,"status":"available"},
        {"type":"qemu","vmid":100,"name":"alpha","node":"node-a","status":"running"}
    ]}""", remote)

    private fun duplicateOverview() = parseOverview("""{"data":[
        {"type":"node","node":"node-a","status":"online"},
        {"type":"node","node":"node-a","status":"offline"},
        {"type":"qemu","vmid":200,"name":"beta","node":"node-a","status":"stopped"}
    ]}""", remote)

    private fun onlineOverview() = parseOverview("""{"data":[
        {"type":"node","node":"node-a","status":"online","cpu":0.25,"mem":10,"maxmem":20,"disk":3,"maxdisk":4},
        {"type":"node","node":"node-b","status":"online","cpu":0,"mem":1,"maxmem":2,"disk":5,"maxdisk":6},
        {"type":"qemu","vmid":100,"name":"desktop","node":"node.example","status":"running"}
    ]}""", remote)

    private fun offlineOverview() = parseOverview("""{"data":[
        {"type":"node","node":"node-a","status":"offline","maxmem":99},
        {"type":"qemu","vmid":100,"name":"desktop","node":"node-a","status":"stopped"}
    ]}""", remote)

    private fun overviewWithoutNode() = parseOverview("""{"data":[
        {"type":"node","node":"node-b","status":"online","cpu":0.1},
        {"type":"qemu","vmid":100,"name":"desktop","node":"node-b","status":"running"}
    ]}""", remote)

    private fun statusBody(cpu: Double) = """{"data":{"cpu":$cpu,"uptime":9,"memory":{"used":10,"total":20},"rootfs":{"used":3,"total":30},"pveversion":"pve-manager/9.0.0","loadavg":["0.1","0.2","0.3"]}}"""

    private class Fake : InventoryGateway, NodeStatusGateway {
        var count = 0
        var inventory: suspend (Connection) -> Overview = { Overview(0, emptyList()) }
        var handler: suspend (Connection, String, String, String) -> NodeLiveStatus = { _, _, node, _ -> NodeLiveStatus(node, 0.5, 10, 20, 3, 30, 9, null) }
        override suspend fun overview(connection: Connection) = inventory(connection)
        override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
        override suspend fun status(connection: Connection, origin: String, node: String, scope: String) = handler(connection, origin, node, scope)
    }

    private fun fixture(
        status: Int = 200,
        responseBody: String = statusBody(0.5),
        onPath: (String) -> Unit = {},
        methods: MutableList<String>? = null,
        queries: MutableList<String?>? = null,
        headers: MutableList<String?>? = null,
        block: (String, String, AtomicInteger, MutableList<String>, MutableList<String?>) -> Unit,
    ) {
        val dir = Files.createTempDirectory("ring-leader-node")
        val key = dir.resolve("fixture.p12")
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool"
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
            "-dname", "CN=fixture.invalid", "-storetype", "PKCS12", "-keystore", key.toString(),
            "-storepass", "fixture-only", "-keypass", "fixture-only", "-noprompt").redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        check(process.waitFor() == 0)
        val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(key).use { load(it, "fixture-only".toCharArray()) } }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "fixture-only".toCharArray()) }
        val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        val pin = MessageDigest.getInstance("SHA-256").digest(store.getCertificate("fixture").encoded).joinToString("") { "%02x".format(it) }
        val calls = AtomicInteger()
        val seenPaths = mutableListOf<String>()
        val seenQueries = mutableListOf<String?>()
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(context)
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            methods?.add(exchange.requestMethod)
            queries?.add(exchange.requestURI.rawQuery)
            headers?.add(exchange.requestHeaders.getFirst("Authorization"))
            seenPaths += exchange.requestURI.path
            seenQueries += exchange.requestURI.rawQuery
            onPath(exchange.requestURI.path)
            val body = responseBody.toByteArray()
            if (status == 302) exchange.responseHeaders.add("Location", "https://127.0.0.1:${server.address.port}/stolen")
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try { block("https://127.0.0.1:${server.address.port}", pin, calls, seenPaths, seenQueries) }
        finally { server.stop(0); Files.deleteIfExists(key); Files.deleteIfExists(dir) }
    }
    @kotlin.test.Test fun directResourceRowsCarryListMetrics() {
        val origin = "https://pve.example.invalid:8006"
        val guest = parseOverview("""{"data":[{"type":"qemu","vmid":102,"name":"media","node":"pve1","status":"running",
            "cpu":0.25,"maxcpu":4,"mem":2147483648,"maxmem":4294967296,"disk":0,"maxdisk":34359738368,"uptime":3600}]}""", origin).guests.single()
        kotlin.test.assertEquals(0.25, guest.metrics?.cpuFraction)
        kotlin.test.assertEquals(2147483648L, guest.metrics?.memoryBytes)
        kotlin.test.assertEquals(4294967296L, guest.metrics?.memoryLimitBytes)
        kotlin.test.assertEquals(3600L, guest.metrics?.uptimeSeconds)
    }
}
