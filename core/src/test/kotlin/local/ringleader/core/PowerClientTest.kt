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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PowerClientTest {
    private val upid = "UPID:node-a:00000010:00000011:65ABCDEF:qmshutdown:112:fixture@pve!read:"

    private class Seen(val method: String, val rawPath: String, val body: String, val type: String?, val authorization: String?)

    @Test fun postKeepsThePowerContractBeforeAndAfterTheServerResponds() {
        val mode = java.util.concurrent.atomic.AtomicReference("shutdown")
        val seen = java.util.Collections.synchronizedList(mutableListOf<Seen>())
        fixture(mode, seen) { address, pin, calls ->
            val client = PveClient(1_000, 1_500)
            Connection(address, "fixture@pve!read", "test-only", pin).use { connection ->
                val guest = Guest(connection.remoteKey, 112, "desktop", "node-a", "VM", "running")
                mode.set("shutdown")
                assertEquals(upid, client.submitPower(connection, guest, PowerAction.Shutdown))
                mode.set("start")
                assertEquals(upid, client.submitPower(connection, guest.copy(kind = "Container", status = "stopped"), PowerAction.Start))
                mode.set("stop")
                assertEquals(upid, client.submitPower(connection, guest, PowerAction.HardStop))
                mode.set("running")
                assertEquals(PowerPhase.Accepted, client.powerStatus(connection, "node-a", upid).phase)
                mode.set("ok")
                assertEquals(PowerPhase.Succeeded, client.powerStatus(connection, "node-a", upid).phase)
                mode.set("failed")
                assertEquals(PowerPhase.Unsuccessful, client.powerStatus(connection, "node-a", upid).phase)
                mode.set("redirect")
                val redirected = assertFailsWith<AmbiguousPowerResultException> { client.submitPower(connection, guest, PowerAction.Shutdown) }
                assertTrue(redirected.message!!.contains("not sent again"))
                mode.set("denied")
                val denied = assertFailsWith<IllegalStateException> { client.submitPower(connection, guest, PowerAction.HardStop) }
                assertFalse(denied is SessionExpiredException)
                mode.set("expired")
                assertFailsWith<SessionExpiredException> { client.submitPower(connection, guest, PowerAction.Start) }
                mode.set("error")
                assertFailsWith<AmbiguousPowerResultException> { client.submitPower(connection, guest, PowerAction.Shutdown) }
                mode.set("ambiguous")
                assertFailsWith<AmbiguousPowerResultException> { client.submitPower(connection, guest, PowerAction.HardStop) }
            }
            val posts = seen.filter { it.method == "POST" }
            assertEquals(listOf("shutdown", "start", "stop", "redirect", "denied", "expired", "error", "ambiguous").size, posts.size)
            assertEquals("""{"forceStop":false}""", posts[0].body)
            assertEquals("{}", posts[1].body)
            assertEquals("{}", posts[2].body)
            assertTrue(posts.all { it.type == POWER_CONTENT_TYPE })
            assertTrue(posts.all { it.authorization?.startsWith("PVEAPIToken=fixture@pve!read=") == true })
            assertTrue(posts.all { !it.rawPath.contains("test-only") && !it.body.contains("test-only") })
            assertEquals("/api2/json/nodes/node-a/qemu/112/status/shutdown", posts[0].rawPath)
            assertEquals("/api2/json/nodes/node-a/lxc/112/status/start", posts[1].rawPath)
            assertEquals("/api2/json/nodes/node-a/qemu/112/status/stop", posts[2].rawPath)
            assertTrue(seen.any { it.method == "GET" && it.rawPath == "/api2/json/nodes/node-a/tasks/UPID%3Anode-a%3A00000010%3A00000011%3A65ABCDEF%3Aqmshutdown%3A112%3Afixture%40pve%21read%3A/status" })
            assertEquals(0, seen.count { it.rawPath.endsWith("/stolen") })
            assertEquals(posts.size + 3, calls.get())
        }
        fixture(mode, seen) { address, _, calls ->
            seen.clear()
            val before = calls.get()
            Connection(address, "fixture@pve!read", "test-only", "00".repeat(32)).use { connection ->
                val guest = Guest(connection.remoteKey, 112, "desktop", "node-a", "VM", "running")
                assertFailsWith<SSLException> { PveClient().submitPower(connection, guest, PowerAction.Shutdown) }
            }
            assertEquals(before, calls.get())
            assertTrue(seen.isEmpty())
        }
    }

    @Test fun snapshotDeleteSendsNoBodyBecauseProxmoxRejectsOne() {
        val mode = java.util.concurrent.atomic.AtomicReference("delete")
        val seen = java.util.Collections.synchronizedList(mutableListOf<Seen>())
        fixture(mode, seen) { address, pin, _ ->
            Connection(address, "fixture@pve!read", "test-only", pin).use { connection ->
                val confirmation = PowerConfirmation(1, connection.remoteKey, connection.address, "fixture", ResourceId(connection.remoteKey, "", "VM", 112),
                    "node-a", "desktop", "VM", PowerAction.SnapshotDelete, "stopped", false, snapshot = SnapshotRequest("baseline"))
                assertEquals(upid, PveClient(1_000, 1_500).submitConfirmed(connection, confirmation))
            }
            val delete = seen.single { it.method == "DELETE" }
            assertEquals("/api2/json/nodes/node-a/qemu/112/snapshot/baseline", delete.rawPath)
            assertEquals("", delete.body)
            assertEquals(null, delete.type)
        }
    }

    private fun fixture(mode: java.util.concurrent.atomic.AtomicReference<String>, seen: MutableList<Seen>, block: (String, String, AtomicInteger) -> Unit) {
        val dir = Files.createTempDirectory("ring-leader-power")
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
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(context)
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            val body = exchange.requestBody.readBytes().decodeToString()
            val item = Seen(exchange.requestMethod, exchange.requestURI.rawPath, body,
                exchange.requestHeaders.getFirst("Content-Type"), exchange.requestHeaders.getFirst("Authorization"))
            seen.add(item)
            val payload = when (mode.get()) {
                "running" -> """{"data":{"status":"running","node":"node-a","upid":"$upid"}}"""
                "ok" -> """{"data":{"status":"stopped","exitstatus":"OK","node":"node-a","upid":"$upid"}}"""
                "failed" -> """{"data":{"status":"stopped","exitstatus":"got timeout","node":"node-a","upid":"$upid"}}"""                else -> """{"data":"$upid"}"""
            }.toByteArray()
            val status = when (mode.get()) {
                "redirect" -> 302
                "denied" -> 403
                "expired" -> 401
                "error" -> 500
                "ambiguous" -> -1
                else -> 200
            }
            if (status == 302) exchange.responseHeaders.add("Location", "https://127.0.0.1:${server.address.port}/stolen")
            if (status < 0) exchange.close() else {
                exchange.sendResponseHeaders(status, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
        }
        server.start()
        try { block("https://127.0.0.1:${server.address.port}", pin, calls) }
        finally { server.stop(0); Files.deleteIfExists(key); Files.deleteIfExists(dir) }
    }
}
