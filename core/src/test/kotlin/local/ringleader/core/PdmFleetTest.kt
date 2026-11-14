package local.ringleader.core

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.IOException
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Handwritten JSON in this file is source-derived from the pinned PDM contract.
 * It is not a recording of a live server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PdmFleetTest {
    private val origin = "https://pdm.example.test:8443"
    private val token = "fixture@pdm!read"
    private val secret = "sec=ret:part"

    @Test fun headersPreserveEqualsAndColonInTheSecret() {
        assertEquals("PDMAPIToken=$token:$secret", authorizationHeader(PDM_KIND, token, secret))
        assertEquals("PVEAPIToken=$token=$secret", authorizationHeader(DIRECT_PVE_KIND, token, secret))
        assertEquals(":$secret", authorizationHeader(PDM_KIND, token, secret).removePrefix("PDMAPIToken=$token"))
        assertEquals("=$secret", authorizationHeader(DIRECT_PVE_KIND, token, secret).removePrefix("PVEAPIToken=$token"))
        assertFailsWith<IllegalArgumentException> { authorizationHeader(PDM_KIND, "user@pam!tok:en", secret) }
        assertTrue(ProxmoxTransport().runCatching { pdm(origin, token, secret, "", "/api2/json/cluster/resources") }.isFailure)
        assertTrue(ProxmoxTransport().runCatching { direct(origin, token, secret, "", "/api2/json/resources/list?max-age=0") }.isFailure)
    }

    @Test fun kindIsExplicitAndThePortIsNotInvented() {
        assertEquals("https://pdm.example.test", serverOrigin("https://pdm.example.test").toString())
        Connection(origin, token, secret, "").use { connection ->
            assertEquals(DIRECT_PVE_KIND, connection.kind)
            assertEquals(origin, connection.remoteKey)
        }
        val draft = profileFromDraft(ProfileDraft(null, "Datacenter", "https://pdm.example.test", token, "", PDM_KIND))
        assertEquals(PDM_KIND, draft.kind)
        assertEquals("https://pdm.example.test", draft.origin)
    }

    @Test fun parserKeepsRemoteIdentityAndDropsCredentials() {
        val parsed = parsePdmRemotes(remotesBody(
            """{"id":"aa","type":"pve","token":"super-secret-token","authid":"root@pam","web-url":"https://node.internal:8006","nodes":[{"hostname":"node.internal","fingerprint":"abc123"}]}""",
            """{"id":"bb","type":"pbs","token":""}""",
        ))
        assertEquals(listOf(PdmRemoteRecord("aa", "pve"), PdmRemoteRecord("bb", "pbs")), parsed)
        val text = parsed.toString()
        assertFalse(text.contains("super-secret-token"))
        assertFalse(text.contains("node.internal"))
        assertFalse(text.contains("abc123"))
        assertFalse(text.contains("root@pam"))
        assertFails { parsePdmVersion("""{"error":"no"}""") }
        assertFails { parsePdmVersion("""{"data":{"release":"1","repoid":"abc"}}""") }
        assertFails { parsePdmVersion("""{"data":{"version":1,"release":"1","repoid":"abc"}}""") }
    }

    @Test fun resourceRowsKeepScopeAndKindAndDropShortTags() {
        val groups = parsePdmResources(resourcesBody(
            group("aa", listOf(
                """{"type":"qemu","vmid":100,"name":"short","node":"node-a","status":"running"}""",
                """{"type":"pve-qemu","vmid":0,"name":"zero","node":"node-a","status":"running"}""",
                """{"type":"pve-qemu","name":"missing","node":"node-a","status":"running"}""",
                """{"type":"pve-qemu","vmid":"100","name":"string","node":"node-a","status":"running"}""",
                """{"type":"pve-node","node":"should-drop","status":"online"}""",
                """{"type":"pve-storage","node":"storage-only","storage":"local","status":"available"}""",
                """{"type":"pbs-datastore","name":"store"}""",
                qemu(100, "desktop", extra = ""","cpu":0.5,"maxcpu":4,"mem":11,"maxmem":22,"disk":33,"maxdisk":44,"uptime":7"""),
                """{"type":"pve-lxc","vmid":100,"name":"ct","node":"node-b","status":"stopped","template":false}""",
            )),
            group("bb", listOf(qemu(100, "other", node = "node-c"))),
        ), origin)
        val guests = groups.flatMap { it.guests }
        assertEquals(listOf("aa", "aa", "bb"), guests.map { it.scope })
        assertEquals(listOf("VM", "Container", "VM"), guests.map { it.kind })
        assertEquals(setOf("desktop", "ct", "other"), guests.map { it.name }.toSet())
        assertTrue(guests.none { it.node == "should-drop" || it.node == "storage-only" || it.name == "store" })
        val desktop = guests.first { it.name == "desktop" }
        val container = guests.first { it.name == "ct" }
        val other = guests.first { it.name == "other" }
        assertNotEquals(desktop.resource, container.resource)
        assertNotEquals(desktop.resource, other.resource)
        assertEquals(other, findCurrentGuest(origin, other, guests))
        assertNull(findCurrentGuest(origin, desktop, listOf(other)))
        val moved = desktop.copy(node = "node-z")
        assertEquals(desktop.resource, moved.resource)
        assertEquals(moved, findCurrentGuest(origin, desktop, listOf(other, moved)))
        val details = pdmListDetails(desktop)
        assertEquals(PDM_LIST_PROVENANCE, details.provenance)
        assertEquals(4, details.cpus)
        assertEquals(0.5, details.cpuFraction)
        assertEquals(33L, details.diskBytes)
        assertNull(details.networkInBytes)
        assertNull(details.networkOutBytes)
        assertFalse(PDM_DISK_NOTE.contains("filesystem usage is"))
        assertTrue(PDM_DISK_NOTE.contains("not guest filesystem usage"))
        val bare = guests.first { it.name == "ct" }
        assertNull(pdmListDetails(bare).diskBytes)
        assertEquals("unknown", pdmListDetails(bare.copy(status = "")).status)
        val direct = Guest(origin, 100, "direct", "node-a", "VM", "running")
        assertNotEquals(direct.resource, desktop.resource)
    }

    @Test fun scopeBoundsAndDuplicateIdentityAreRejected() {
        assertNull(pdmScopeOrNull("a"))
        assertEquals("aa", pdmScopeOrNull("aa"))
        assertEquals("a".repeat(32), pdmScopeOrNull("a".repeat(32)))
        assertNull(pdmScopeOrNull("a".repeat(33)))
        assertNull(pdmScopeOrNull("a/b"))
        assertNull(pdmScopeOrNull("a b"))
        assertNull(pdmScopeOrNull("aa\n"))
        assertTrue(parsePdmResources("""{"data":[{"remote":"a"}]}""", origin).isEmpty())
        assertTrue(parsePdmResources("""{"data":[{"remote":"aa/bb"}]}""", origin).isEmpty())
        assertFails { parsePdmResources(resourcesBody(group("aa", emptyList()), group("aa", emptyList())), origin) }
        assertFails {
            parsePdmResources(resourcesBody(group("aa", listOf(qemu(100, "one"), qemu(100, "two")))), origin)
        }
        val foreign = Guest("https://other.example.test:8443", 100, "x", "node-a", "VM", "running", false, "aa")
        assertFails { mergePdmFleet(null, origin, document(groups = listOf(PdmResourceGroup("aa", null, listOf(foreign))))) }
    }

    @Test fun matchingNodeNamesOnDifferentRemotesStayDistinct() {
        val fleet = mergePdmFleet(null, origin, document(groups = listOf(
            PdmResourceGroup("aa", null, listOf(guest("aa", 100, "one", node = "shared-node"))),
            PdmResourceGroup("bb", null, listOf(guest("bb", 100, "two", node = "shared-node"))),
        )))
        assertEquals(2, fleet.nodes)
    }

    @Test fun malformedRemoteDoesNotBlockHealthySibling() {
        val previous = mergePdmFleet(null, origin, initialDocument())
        val remotes = parsePdmRemotes("""{"data":[null,{"id":"a"},{"id":"aa","type":"pve"},{"id":"bb","type":"pve"}]}""")
        val groups = parsePdmResources(resourcesBody("null", """{"remote":"bad/id"}""",
            group("aa", listOf("null")), group("bb", listOf(qemu(100, "updated")))) , origin)
        val merged = mergePdmFleet(previous, origin, document(remotes = remotes, groups = groups))
        assertEquals(listOf("kept", "updated"), merged.guests.map { it.name })
        assertTrue(merged.remotes.first { it.scope == "aa" }.stale)
        assertFalse(merged.remotes.first { it.scope == "bb" }.stale)
        val badMetadata = parsePdmRemotes("""{"data":[{"id":"aa","type":12},{"id":"bb","type":"pve"}]}""")
        val retained = mergePdmFleet(previous, origin, document(remotes = badMetadata, groups = emptyList()))
        assertEquals("kept", retained.guests.single().name)
        assertTrue(retained.remotes.first { it.scope == "aa" }.stale)
    }

    @Test fun mergeKeepsErrorsClearsEmptiesAndDropsOmissions() {
        val previous = mergePdmFleet(null, origin, document(
            remotes = listOf(PdmRemoteRecord("aa", "pve"), PdmRemoteRecord("bb", "pve"), PdmRemoteRecord("cc", "pbs")),
            groups = listOf(
                PdmResourceGroup("aa", null, listOf(guest("aa", 100, "kept"))),
                PdmResourceGroup("bb", null, listOf(guest("bb", 100, "old"))),
            ),
        ))
        val merged = mergePdmFleet(previous, origin, document(
            remotes = listOf(PdmRemoteRecord("aa", "pve"), PdmRemoteRecord("bb", "pve"), PdmRemoteRecord("dd", "pve")),
            groups = listOf(
                PdmResourceGroup("aa", "connection timed out", emptyList()),
                PdmResourceGroup("bb", null, emptyList()),
                PdmResourceGroup("dd", null, listOf(guest("dd", 7, "new"))),
            ),
        ))
        assertEquals(listOf("kept", "new"), merged.guests.map { it.name })
        assertEquals("aa", merged.guests.first().scope)
        assertTrue(merged.remotes.first { it.scope == "aa" }.stale)
        assertEquals("connection timed out", merged.remotes.first { it.scope == "aa" }.error)
        assertFalse(merged.remotes.first { it.scope == "bb" }.stale)
        assertTrue(merged.guests.none { it.scope == "bb" })
        assertTrue(merged.remotes.none { it.scope == "cc" })
        assertEquals("pbs", previous.remotes.first { it.scope == "cc" }.product)
        val failed = runCatching { mergePdmFleet(merged, origin, document(groups = listOf(PdmResourceGroup("aa", null, listOf(guest("aa", 100, "one"), guest("aa", 100, "two")))))) }
        assertTrue(failed.isFailure)
    }

    @Test fun workspaceMergesRecoversAndIgnoresLateOrExpiredResults() = runTest {
        val pdm = FakePdm()
        val directCalls = AtomicInteger()
        val taskCalls = AtomicInteger()
        val powerCalls = AtomicInteger()
        val direct = object : InventoryGateway {
            override suspend fun overview(connection: Connection): Overview { directCalls.incrementAndGet(); error("direct inventory") }
            override suspend fun details(connection: Connection, guest: Guest): GuestDetails { directCalls.incrementAndGet(); error("direct detail") }
        }
        val tasks = object : TaskGateway {
            override suspend fun tasks(connection: Connection): List<ClusterTask> { taskCalls.incrementAndGet(); error("tasks") }
        }
        val power = object : PowerGateway {
            override suspend fun submit(connection: Connection, guest: Guest, action: PowerAction): String { powerCalls.incrementAndGet(); error("power") }
            override suspend fun status(connection: Connection, node: String, upid: String): PowerTaskStatus { powerCalls.incrementAndGet(); error("status") }
        }
        pdm.load = { initialDocument() }
        val workspace = Workspace(this, direct, tasks, power, pdm)
        val connection = connection()
        workspace.connect(connection); runCurrent()
        assertEquals(listOf("kept", "old"), workspace.state.value.overview!!.guests.map { it.name })
        assertEquals(0, directCalls.get())
        workspace.select(workspace.state.value.overview!!.guests.first { it.scope == "aa" }); runCurrent()
        assertEquals(PDM_LIST_PROVENANCE, workspace.state.value.details!!.provenance)
        assertEquals(0, directCalls.get())
        val originalReadAt = workspace.state.value.detailsUpdatedAt
        workspace.refreshTasks(); runCurrent()
        assertEquals(0, taskCalls.get())
        assertEquals(PDM_KIND, workspace.state.value.kind)
        assertNull(workspace.capturePower(PowerAction.Start, "Lab"))
        assertFalse(workspace.submitPower(PowerConfirmation(
            workspace.state.value.connectionEpoch, origin, origin, "Lab",
            ResourceId(origin, "aa", "VM", 100), "node-a", "kept", "VM", PowerAction.Start, "running", false,
        )))
        assertEquals(0, powerCalls.get())
        pdm.load = {
            document(
                remotes = listOf(PdmRemoteRecord("aa", "pve"), PdmRemoteRecord("bb", "pve"), PdmRemoteRecord("dd", "pve")),
                groups = listOf(
                    PdmResourceGroup("aa", "connection timed out", listOf(guest("aa", 999, "should-not-install"))),
                    PdmResourceGroup("bb", null, emptyList()),
                    PdmResourceGroup("dd", null, listOf(guest("dd", 7, "new"))),
                ),
            )
        }
        workspace.refresh(); runCurrent()
        val partial = workspace.state.value.overview!!
        assertEquals(listOf("kept", "new"), partial.guests.map { it.name })
        assertTrue(partial.remotes.first { it.scope == "aa" }.stale)
        assertFalse(workspace.state.value.stale)
        assertEquals(originalReadAt, workspace.state.value.detailsUpdatedAt)
        workspace.select(workspace.state.value.selected!!)
        assertEquals(originalReadAt, workspace.state.value.detailsUpdatedAt)
        assertEquals("node-a", workspace.state.value.selected?.node)
        assertEquals("kept", workspace.state.value.selected?.name)
        pdm.load = {
            document(
                remotes = listOf(PdmRemoteRecord("aa", "pve"), PdmRemoteRecord("dd", "pve")),
                groups = listOf(
                    PdmResourceGroup("aa", null, listOf(guest("aa", 100, "kept", node = "node-b"))),
                    PdmResourceGroup("dd", null, listOf(guest("dd", 7, "new"))),
                ),
            )
        }
        workspace.refresh(); runCurrent()
        val moved = workspace.state.value.overview!!
        assertEquals("node-b", workspace.state.value.selected?.node)
        assertEquals(ResourceId(origin, "aa", "VM", 100), workspace.state.value.selected?.resource)
        assertFalse(moved.remotes.first { it.scope == "aa" }.stale)
        pdm.load = { throw IOException("down") }
        workspace.refresh(); runCurrent()
        assertEquals(moved, workspace.state.value.overview)
        assertTrue(workspace.state.value.stale)
        assertTrue(workspace.state.value.connected)
        pdm.load = { throw SessionExpiredException() }
        workspace.refresh(); runCurrent()
        assertFalse(workspace.state.value.connected)
        assertNull(workspace.state.value.overview)
        assertFailsWith<IllegalStateException> { connection.withSecret { it } }
        workspace.close()
    }

    @Test fun sameOriginKindSwitchDropsLateResultsAndDirectGatewaysRefusePdm() = runTest {
        val directGate = CompletableDeferred<Overview>()
        val pdmGate = CompletableDeferred<PdmFleetDocument>()
        var directCalls = 0
        val direct = object : InventoryGateway {
            override suspend fun overview(connection: Connection): Overview {
                directCalls++
                return kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { directGate.await() }
            }
            override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
        }
        val pdm = FakePdm()
        pdm.load = { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { pdmGate.await() } }
        val workspace = Workspace(this, direct, LiveTaskGateway(), LivePowerGateway(), pdm)
        val older = Connection(origin, token, "direct-secret", "", DIRECT_PVE_KIND)
        workspace.connect(older); runCurrent()
        val newer = connection()
        workspace.connect(newer); runCurrent()
        directGate.complete(Overview(1, listOf(Guest(origin, 100, "stale-direct", "node-a", "VM", "running")))); runCurrent()
        assertNull(workspace.state.value.overview)
        pdmGate.complete(initialDocument()); runCurrent()
        assertEquals(listOf("kept", "old"), workspace.state.value.overview!!.guests.map { it.name })
        assertEquals(PDM_KIND, workspace.state.value.kind)
        assertEquals(origin, workspace.state.value.address)
        assertFailsWith<IllegalStateException> { older.withSecret { it } }
        assertEquals(1, directCalls)
        workspace.close()
    }

    @Test fun directClientsRefuseAPdmConnectionBeforeHttp() {
        fixture { address, pin, calls, seen ->
            Connection(address, token, secret, pin, PDM_KIND).use { connection ->
                val guest = Guest(connection.remoteKey, 100, "desktop", "node-a", "VM", "running", false, "aa")
                assertFailsWith<IllegalArgumentException> { PveClient().overview(connection) }
                assertFailsWith<IllegalArgumentException> { PveClient().details(connection, guest.copy(scope = "")) }
                assertFailsWith<IllegalArgumentException> { PveClient().details(connection, guest) }
                assertFailsWith<IllegalArgumentException> { PveClient().tasks(connection) }
                assertFailsWith<IllegalArgumentException> { PveClient().submitPower(connection, guest, PowerAction.Start) }
                assertFailsWith<IllegalArgumentException> { PveClient().powerStatus(connection, "node-a", "UPID:node-a:00000010:00000011:65ABCDEF:qmstart:100:fixture@pdm:") }
                assertFailsWith<IllegalArgumentException> { PveClient().nodeStatus(connection, "node-a") }
                assertFailsWith<IllegalArgumentException> { directNodeStatusPath(connection, connection.remoteKey, "node-a", "") }
                Connection(address, token, secret, pin, DIRECT_PVE_KIND).use { direct ->
                    assertFailsWith<IllegalArgumentException> { PveClient().details(direct, guest) }
                    assertFailsWith<IllegalArgumentException> { PveClient().submitPower(direct, guest, PowerAction.Start) }
                }
            }
            assertEquals(0, calls.get())
            assertTrue(seen.isEmpty())
        }
    }

    @Test fun pdmReadUsesThePinnedRoutesAndRefusesRedirectsAndPinMismatch() {
        fixture(bodies = mapOf(
            "/api2/json/version" to versionBody(),
            "/api2/json/remotes/remote" to remotesBody(
                """{"id":"aa","type":"pve","token":"super-secret-token","web-url":"https://node.internal:8006"}""",
                """{"id":"bb","type":"pbs","token":""}""",
            ),
            "/api2/json/resources/list" to resourcesBody(group("aa", listOf(qemu(100, "desktop")))),
        )) { address, pin, calls, seen ->
            Connection(address, token, secret, pin, PDM_KIND).use { connection ->
                val document = PdmClient().read(connection)
                assertEquals("1.1.9 1 (d66b5e157116)", document.serverLabel)
                assertEquals(listOf("aa"), document.groups.flatMap { it.guests }.map { it.scope })
                assertFalse(document.toString().contains("super-secret-token"))
                assertFalse(document.toString().contains("node.internal"))
            }
            assertEquals(3, calls.get())
            assertEquals(listOf("/api2/json/version", "/api2/json/remotes/remote", "/api2/json/resources/list"), seen.map { it.path })
            assertEquals(listOf(null, null, "max-age=0"), seen.map { it.query })
            assertTrue(seen.all { it.authorization == "PDMAPIToken=$token:$secret" })
            assertTrue(seen.none { it.path.startsWith("/api2/json/nodes") || it.path.startsWith("/api2/json/cluster") || it.path.contains("/pve/remotes") })
        }
    }

    @Test fun redirectPinAndStatusCodesUseTheSharedTransport() {
        fixture(status = 302) { address, pin, calls, _ ->
            Connection(address, token, secret, pin, PDM_KIND).use { connection ->
                val error = assertFailsWith<IllegalStateException> { PdmClient().read(connection) }
                assertTrue(error.message!!.contains("redirected"))
                assertFalse(error is SessionExpiredException)
            }
            assertEquals(1, calls.get())
        }
        fixture { address, _, calls, _ ->
            Connection(address, token, secret, "00".repeat(32), PDM_KIND).use { connection ->
                assertFailsWith<SSLException> { PdmClient().read(connection) }
            }
            assertEquals(0, calls.get())
        }
        fixture { address, _, calls, _ ->
            Connection(address, token, secret, "", PDM_KIND).use { connection ->
                assertFailsWith<SSLException> { PdmClient().read(connection) }
            }
            assertEquals(0, calls.get())
        }
        fixture(status = 401) { address, pin, _, _ ->
            Connection(address, token, secret, pin, PDM_KIND).use { connection ->
                assertFailsWith<SessionExpiredException> { PdmClient().read(connection) }
            }
        }
        fixture(status = 403) { address, pin, _, _ ->
            Connection(address, token, secret, pin, PDM_KIND).use { connection ->
                val error = assertFailsWith<IllegalStateException> { PdmClient().read(connection) }
                assertFalse(error is SessionExpiredException)
                assertTrue(error.message!!.contains("Permission"))
            }
        }
    }

    private fun connection() = Connection(origin, token, secret, "", PDM_KIND)

    private fun guest(scope: String, vmid: Int, name: String, node: String = "node-a") =
        Guest(origin, vmid, name, node, "VM", "running", false, scope)

    private fun versionBody() = """{"data":{"version":"1.1.9","release":"1","repoid":"d66b5e157116"}}"""

    private fun remotesBody(vararg rows: String) = """{"data":[${rows.joinToString()}]}"""

    private fun resourcesBody(vararg groups: String) = """{"data":[${groups.joinToString()}]}"""

    private fun group(scope: String, rows: List<String>, error: String? = null): String {
        val errorField = if (error == null) "" else ""","error":"$error""""
        return """{"remote":"$scope"$errorField,"resources":[${rows.joinToString()}]}"""
    }

    private fun qemu(vmid: Int, name: String, node: String = "node-a", extra: String = "") =
        """{"type":"pve-qemu","vmid":$vmid,"name":"$name","node":"$node","status":"running","template":false$extra}"""

    private fun initialDocument() = document(
        remotes = listOf(PdmRemoteRecord("aa", "pve"), PdmRemoteRecord("bb", "pve"), PdmRemoteRecord("cc", "pbs")),
        groups = listOf(
            PdmResourceGroup("aa", null, listOf(guest("aa", 100, "kept").copy(node = "node-a"))),
            PdmResourceGroup("bb", null, listOf(guest("bb", 100, "old"))),
        ),
    )

    private fun document(
        remotes: List<PdmRemoteRecord> = listOf(PdmRemoteRecord("aa", "pve")),
        groups: List<PdmResourceGroup>,
    ) = PdmFleetDocument("1.1.9", "1", "d66b5e157116", remotes, groups)

    private class Seen(val path: String, val query: String?, val authorization: String?)

    private class FakePdm : PdmReadGateway {
        var load: suspend (Connection) -> PdmFleetDocument = { error("unset") }
        override suspend fun read(connection: Connection): PdmFleetDocument {
            require(connection.kind == PDM_KIND)
            return load(connection)
        }
    }

    private fun fixture(
        status: Int = 200,
        bodies: Map<String, String> = emptyMap(),
        block: (String, String, AtomicInteger, MutableList<Seen>) -> Unit,
    ) {
        val dir = Files.createTempDirectory("ring-leader-pdm")
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
        val seen = java.util.Collections.synchronizedList(mutableListOf<Seen>())
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(context)
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            val path = exchange.requestURI.path
            seen.add(Seen(path, exchange.requestURI.rawQuery, exchange.requestHeaders.getFirst("Authorization")))
            val payload = (bodies[path] ?: """{"data":{}}""").toByteArray()
            if (status == 302) exchange.responseHeaders.add("Location", "https://127.0.0.1:${server.address.port}/stolen")
            exchange.sendResponseHeaders(status, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try { block("https://127.0.0.1:${server.address.port}", pin, calls, seen) }
        finally { server.stop(0); Files.deleteIfExists(key); Files.deleteIfExists(dir) }
    }
}
