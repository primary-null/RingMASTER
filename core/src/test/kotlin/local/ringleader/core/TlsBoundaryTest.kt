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
import kotlin.test.*

class TlsBoundaryTest {
    private fun fixture(status: Int = 200, responseBody: String = """{"data":[{"type":"node","node":"fixture"}]}""",
        onPath: (String) -> Unit = {}, methods: MutableList<String>? = null, queries: MutableList<String?>? = null,
        block: (String, String, AtomicInteger) -> Unit) {
        val dir = Files.createTempDirectory("ring-leader-tls")
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
            methods?.add(exchange.requestMethod)
            queries?.add(exchange.requestURI.rawQuery)
            onPath(exchange.requestURI.path)
            val body = responseBody.toByteArray()
            if (status == 302) exchange.responseHeaders.add("Location", "https://127.0.0.1:${server.address.port}/stolen")
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try { block("https://127.0.0.1:${server.address.port}", pin, calls) }
        finally { server.stop(0); Files.deleteIfExists(key); Files.deleteIfExists(dir) }
    }

    @Test fun exactPinAllowsPrivateCertificate() = fixture { address, pin, calls ->
        assertEquals(1, PveClient().overview(address, "fixture@pve!read", "test-only", pin).nodes)
        assertEquals(1, calls.get())
    }

    @Test fun wrongPinRefusesBeforeSendingCredentials() = fixture { address, _, calls ->
        assertFailsWith<SSLException> { PveClient().overview(address, "fixture@pve!read", "test-only", "00".repeat(32)) }
        assertEquals(0, calls.get())
    }

    @Test fun pinnedContextIsReusedPerPinAndStillRefusesAnotherCertificate() = fixture { address, pin, calls ->
        val spelled = pin.uppercase().chunked(2).joinToString(":")
        assertSame(pinnedSslContext(pin), pinnedSslContext(spelled))
        assertNotSame(pinnedSslContext(pin), pinnedSslContext("00".repeat(32)))
        assertNull(pinnedSslContext(" "))
        assertFailsWith<IllegalArgumentException> { pinnedSslContext("abc") }
        assertEquals(1, PveClient().overview(address, "fixture@pve!read", "test-only", pin).nodes)
        assertFailsWith<SSLException> { PveClient().overview(address, "fixture@pve!read", "test-only", "00".repeat(32)) }
        assertEquals(1, PveClient().overview(address, "fixture@pve!read", "test-only", spelled).nodes)
        assertEquals(2, calls.get())
    }

    @Test fun untrustedCertificateWithoutPinRefusesBeforeHttp() = fixture { address, _, calls ->
        assertFailsWith<SSLException> { PveClient().overview(address, "fixture@pve!read", "test-only") }
        assertEquals(0, calls.get())
    }

    @Test fun redirectsNeverForwardCredentials() = fixture(302) { address, pin, calls ->
        assertFailsWith<IllegalStateException> { PveClient().overview(address, "fixture@pve!read", "test-only", pin) }
        assertEquals(1, calls.get())
    }

    @Test fun detailRequestsStayAtTheAuthenticatedOriginAndUseGuestKind() {
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        fixture(responseBody = """{"data":{"status":"running","cpus":8,"maxmem":17179869184}}""", onPath = { paths.add(it) }) { address, pin, calls ->
            Connection(address, "fixture@pve!read", "test-only", pin).use { connection ->
                val vm = Guest(connection.remoteKey, 112, "desktop", "node-a", "VM", "running")
                assertEquals(8, PveClient().details(connection, vm).cpus)
                assertEquals(8, PveClient().details(connection, vm.copy(kind = "Container")).cpus)
                assertEquals(2, calls.get())
            }
        }
        assertEquals(listOf("/api2/json/nodes/node-a/qemu/112/status/current", "/api2/json/nodes/node-a/lxc/112/status/current"), paths)
    }

    @Test fun invalidSessionAndPermissionDenialAreDifferent() {
        fixture(401) { address, pin, _ ->
            assertFailsWith<SessionExpiredException> { PveClient().overview(address, "fixture@pve!read", "test-only", pin) }
        }
        fixture(403) { address, pin, _ ->
            val error = assertFailsWith<IllegalStateException> { PveClient().overview(address, "fixture@pve!read", "test-only", pin) }
            assertFalse(error is SessionExpiredException)
        }
    }

    @Test fun taskRouteIsGetOnTheClusterFeedWithoutAQueryOrLog() {
        val paths = mutableListOf<String>()
        val methods = mutableListOf<String>()
        val queries = mutableListOf<String?>()
        fixture(responseBody = taskFeed(), onPath = { paths.add(it) }, methods = methods, queries = queries) { address, pin, calls ->
            Connection(address, "fixture@pve!read", "test-only", pin).use { connection ->
                val task = PveClient().tasks(connection).single()
                assertEquals(TaskOutcome.Succeeded, task.outcome)
                assertEquals("112", task.subjectId)
                assertFalse(taskListFields(task).joinToString(" ").contains("!read"))
                assertFalse(task.toString().contains("fixture@pve!read"))
            }
            assertEquals(1, calls.get())
        }
        assertEquals(listOf(CLUSTER_TASKS_PATH), paths)
        assertEquals(listOf("GET"), methods)
        assertEquals(listOf<String?>(null), queries)
    }

    @Test fun taskRouteRefusesRedirectsAndKeepsPinRejectionBeforeHttp() {
        fixture(302, responseBody = taskFeed()) { address, pin, calls ->
            Connection(address, "fixture@pve!read", "test-only", pin).use { connection ->
                val error = assertFailsWith<IllegalStateException> { PveClient().tasks(connection) }
                assertTrue(error.message!!.contains("redirected"))
                assertFalse(error is SessionExpiredException)
            }
            assertEquals(1, calls.get())
        }
        fixture(responseBody = taskFeed()) { address, _, calls ->
            Connection(address, "fixture@pve!read", "test-only", "00".repeat(32)).use { connection ->
                assertFailsWith<SSLException> { PveClient().tasks(connection) }
            }
            assertEquals(0, calls.get())
        }
        fixture(responseBody = taskFeed()) { address, _, calls ->
            Connection(address, "fixture@pve!read", "test-only", "").use { connection ->
                assertFailsWith<SSLException> { PveClient().tasks(connection) }
            }
            assertEquals(0, calls.get())
        }
    }

    @Test fun taskRouteKeepsSessionExpiryDistinctFromPermissionRefusal() {
        fixture(401, responseBody = taskFeed()) { address, pin, _ ->
            Connection(address, "fixture@pve!read", "test-only", pin).use { connection ->
                assertFailsWith<SessionExpiredException> { PveClient().tasks(connection) }
            }
        }
        fixture(403, responseBody = taskFeed()) { address, pin, _ ->
            Connection(address, "fixture@pve!read", "test-only", pin).use { connection ->
                val error = assertFailsWith<IllegalStateException> { PveClient().tasks(connection) }
                assertFalse(error is SessionExpiredException)
                assertTrue(error.message!!.contains("Permission"))
            }
        }
    }

    private fun taskFeed(): String {
        val upid = "UPID:node-a:00000010:00000011:65ABCDEF:qmstart:112:fixture@pve:"
        return """{"data":[{"upid":"$upid","node":"node-a","type":"qmstart","id":"112","user":"fixture@pve","starttime":1700000000,"endtime":1700000010,"status":"OK","saved":"1","tokenid":"fixture@pve!read","pid":1}]}"""
    }
}
