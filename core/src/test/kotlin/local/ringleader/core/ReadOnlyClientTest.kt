package local.ringleader.core

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Transport checks for the 0.21.4 read-only calls against a local HTTPS fixture. The bodies are handwritten parser inputs, not
 * captured responses. Every call must be a GET at an exact path, and refusals must happen before any request is sent.
 */
class ReadOnlyClientTest {
    private class Seen(val method: String, val target: String, val authorization: String?)
    private class Reply(val status: Int = 200, val body: String = """{"data":[]}""", val location: String? = null)

    private object Tls {
        val pin: String
        val context: SSLContext
        init {
            val dir = Files.createTempDirectory("ringmaster-readonly-tls")
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
            context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
            pin = MessageDigest.getInstance("SHA-256").digest(store.getCertificate("fixture").encoded).joinToString("") { "%02x".format(it) }
            Files.deleteIfExists(key)
            Files.deleteIfExists(dir)
        }
    }

    private fun withServer(reply: (Seen) -> Reply, block: (address: String, pin: String, seen: MutableList<Seen>) -> Unit) {
        val seen = java.util.Collections.synchronizedList(mutableListOf<Seen>())
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(Tls.context)
        server.createContext("/") { exchange ->
            val target = exchange.requestURI.rawPath + (exchange.requestURI.rawQuery?.let { "?$it" } ?: "")
            val request = Seen(exchange.requestMethod, target, exchange.requestHeaders.getFirst("Authorization"))
            seen += request
            val answer = reply(request)
            val bytes = answer.body.toByteArray()
            answer.location?.let { exchange.responseHeaders.add("Location", it) }
            exchange.sendResponseHeaders(answer.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { block("https://127.0.0.1:${server.address.port}", Tls.pin, seen) } finally { server.stop(0) }
    }

    private fun good(request: Seen): Reply = when {
        request.target == PERMISSIONS_PATH -> Reply(body = """{"data":{"/":{"Sys.Audit":1}}}""")
        request.target.endsWith("/services") -> Reply(body = """{"data":[{"name":"pveproxy","state":"running"}]}""")
        request.target.contains("/syslog") -> Reply(body = """{"data":[{"n":1,"t":"a"}],"total":1}""")
        request.target.endsWith("/apt/update") -> Reply(body = """{"data":[{"Package":"p","Version":"1"}]}""")
        request.target.contains("/content") -> Reply(body = """{"data":[{"volid":"local:iso/a.iso","content":"iso"}]}""")
        else -> Reply(404)
    }

    private fun connection(address: String, pin: String, kind: String = DIRECT_PVE_KIND) =
        Connection(address, if (kind == PDM_KIND) "fixture@pdm!read" else "fixture@pve!read", "test-only", pin, kind)

    private fun reads(client: PveClient, c: Connection): List<Pair<String, () -> Any?>> = listOf(
        "permissions" to { client.permissions(c) },
        "services" to { client.nodeServices(c, "node-a") },
        "syslog" to { client.nodeSyslog(c, "node-a", 0, 1) },
        "updates" to { client.nodeUpdates(c, "node-a") },
        "content" to { client.storageContent(c, "node-a", "local") },
    )

    @Test fun everyReadIsOneGetAtItsExactPath() {
        withServer(::good) { address, pin, seen ->
            val client = PveClient()
            connection(address, pin).use { c ->
                assertTrue(client.permissions(c).paths.containsKey("/"))
                assertEquals("pveproxy", client.nodeServices(c, "node-a").single().name)
                assertEquals(1, client.nodeSyslog(c, "node-a", 0, 1).total)
                assertEquals(1, client.nodeSyslog(c, "node-a", 100, 501, "pveproxy").lines.size)
                assertEquals("p", client.nodeUpdates(c, "node-a").single().name)
                assertEquals("local:iso/a.iso", client.storageContent(c, "node-a", "local").single().volid)
                assertEquals("iso", client.storageContent(c, "node-a", "local", "iso").single().content)
            }
            assertEquals(listOf(
                "/api2/json/access/permissions",
                "/api2/json/nodes/node-a/services",
                "/api2/json/nodes/node-a/syslog?start=0&limit=1",
                "/api2/json/nodes/node-a/syslog?start=100&limit=501&service=pveproxy",
                "/api2/json/nodes/node-a/apt/update",
                "/api2/json/nodes/node-a/storage/local/content",
                "/api2/json/nodes/node-a/storage/local/content?content=iso",
            ), seen.map { it.target })
            assertTrue(seen.all { it.method == "GET" }, seen.map { it.method }.toString())
            assertTrue(seen.all { it.authorization?.startsWith("PVEAPIToken=fixture@pve!read=") == true })
            assertTrue(seen.none { it.target.contains("test-only") })
        }
    }

    @Test fun permissionDenialKeepsTheSessionAndAnExpiredTokenEndsIt() {
        for ((status, expected) in listOf(403 to GuestPermissionException::class, 401 to SessionExpiredException::class)) {
            withServer({ Reply(status) }) { address, pin, seen ->
                connection(address, pin).use { c ->
                    for ((name, call) in reads(PveClient(), c)) {
                        val failure = assertFailsWith<IllegalStateException>("$name $status") { call() }
                        assertEquals(expected, failure::class, "$name $status")
                    }
                    assertEquals(5, seen.size)
                    // The connection is still usable after a refusal; only a 401 makes the workspace drop it.
                    c.withSecret { assertEquals("test-only", it) }
                }
            }
        }
    }

    @Test fun aMissingPageNamesTheReadNotTheGuest() {
        withServer({ Reply(404) }) { address, pin, _ ->
            connection(address, pin).use { c ->
                val messages = reads(PveClient(), c).map { (_, call) -> assertFailsWith<IllegalStateException> { call() }.message }
                assertEquals(listOf(PERMISSIONS_NOT_FOUND, NODE_SERVICES_NOT_FOUND, NODE_SYSLOG_NOT_FOUND, NODE_UPDATES_NOT_FOUND, STORAGE_CONTENT_NOT_FOUND), messages)
                assertTrue(messages.none { it == GUEST_NOT_FOUND })
            }
        }
    }

    @Test fun aRefusedRequestSaysSo() {
        withServer({ Reply(400) }) { address, pin, _ ->
            connection(address, pin).use { c ->
                val messages = reads(PveClient(), c).map { (_, call) -> assertFailsWith<IllegalStateException> { call() }.message }
                assertEquals(listOf(PERMISSIONS_REFUSED, NODE_SERVICES_REFUSED, NODE_SYSLOG_REFUSED, NODE_UPDATES_REFUSED, STORAGE_CONTENT_REFUSED), messages)
            }
        }
    }

    @Test fun aStorageThatCannotBeReadGetsAPlainSentenceAndOtherServerErrorsStayGeneric() {
        withServer({ Reply(500) }) { address, pin, _ ->
            connection(address, pin).use { c ->
                val messages = reads(PveClient(), c).associate { (name, call) -> name to assertFailsWith<IllegalStateException> { call() }.message }
                assertEquals(STORAGE_CONTENT_UNAVAILABLE, messages.getValue("content"))
                assertEquals("The server returned HTTP 500.", messages.getValue("services"))
                assertEquals("The server returned HTTP 500.", messages.getValue("syslog"))
            }
        }
    }

    @Test fun redirectsAreRefusedAndNeverFollowed() {
        withServer({ Reply(302, location = "https://127.0.0.1:1/stolen") }) { address, pin, seen ->
            connection(address, pin).use { c ->
                for ((name, call) in reads(PveClient(), c)) {
                    val failure = assertFailsWith<IllegalStateException>(name) { call() }
                    assertTrue(failure.message!!.contains("redirected"), name)
                    assertFalse(failure is SessionExpiredException)
                }
                assertEquals(5, seen.size)
                assertTrue(seen.none { it.target.endsWith("/stolen") })
            }
        }
    }

    @Test fun theNodeIsNeverAskedToRefreshItsPackageLists() {
        withServer(::good) { address, pin, seen ->
            val transport = ProxmoxTransport()
            connection(address, pin).use { c ->
                val path = aptUpdatePath("node-a")
                for (body in listOf(ByteArray(0), "{}".toByteArray(), """{"notify":1}""".toByteArray())) {
                    val refusal = assertFailsWith<IllegalArgumentException> { transport.direct(c, path, body) }
                    assertEquals(APT_REFRESH_REFUSAL, refusal.message)
                    assertFailsWith<IllegalArgumentException> { transport.direct(c, "$path?quiet=1", body, method = "POST") }
                    assertFailsWith<IllegalArgumentException> { transport.direct(address, "fixture@pve!read", "test-only", pin, path, body) }
                }
                assertTrue(seen.isEmpty(), "Nothing was sent")
                assertEquals(1, PveClient().nodeUpdates(c, "node-a").size)
            }
            assertEquals(listOf("GET" to aptUpdatePath("node-a")), seen.map { it.method to it.target })
        }
    }

    @Test fun theStorageIndexAndTheNodeListAreStillRefusedBeforeAnyRequest() {
        withServer(::good) { address, pin, seen ->
            val transport = ProxmoxTransport()
            connection(address, pin).use { c ->
                for (path in listOf("/api2/json/nodes/node-a/storage", "/api2/json/nodes/node-a/storage?enabled=1", "/api2/json/nodes")) {
                    assertFailsWith<IllegalArgumentException>(path) { transport.direct(c, path) }
                }
                assertEquals(0, seen.size)
                // One storage's content list is a different path and is allowed.
                assertEquals("local:iso/a.iso", PveClient().storageContent(c, "node-a", "local").single().volid)
            }
            assertEquals(listOf("/api2/json/nodes/node-a/storage/local/content"), seen.map { it.target })
        }
    }

    @Test fun aPdmConnectionNeverReachesTheNetwork() {
        withServer(::good) { address, pin, seen ->
            connection(address, pin, PDM_KIND).use { c ->
                for ((name, call) in reads(PveClient(), c)) {
                    val failure = assertFailsWith<IllegalArgumentException>(name) { call() }
                    assertEquals(DIRECT_REQUEST_REFUSAL, failure.message, name)
                }
            }
            assertTrue(seen.isEmpty())
        }
    }

    @Test fun anUnpinnedOrWrongCertificateFailsBeforeAnyRequestIsSent() {
        withServer(::good) { address, _, seen ->
            for (pin in listOf("00".repeat(32), "")) {
                connection(address, pin).use { c ->
                    for ((name, call) in reads(PveClient(), c)) assertFailsWith<SSLException>("$name pin='$pin'") { call() }
                }
            }
            assertTrue(seen.isEmpty())
        }
    }

    @Test fun namesThatAreNotNodesStoragesOrUnitsAreRefusedBeforeAnyRequest() {
        withServer(::good) { address, pin, seen ->
            val client = PveClient()
            connection(address, pin).use { c ->
                for (node in listOf("node.example", "a b", "node/../x", "")) {
                    assertFailsWith<IllegalArgumentException>(node) { client.nodeServices(c, node) }
                    assertFailsWith<IllegalArgumentException>(node) { client.nodeSyslog(c, node, 0, 1) }
                    assertFailsWith<IllegalArgumentException>(node) { client.nodeUpdates(c, node) }
                    assertFailsWith<IllegalArgumentException>(node) { client.storageContent(c, node, "local") }
                }
                assertFailsWith<IllegalArgumentException> { client.storageContent(c, "node-a", "x") }
                assertFailsWith<IllegalArgumentException> { client.storageContent(c, "node-a", "local", "bogus") }
                assertFailsWith<IllegalArgumentException> { client.nodeSyslog(c, "node-a", 0, 1, "a b") }
                assertFailsWith<IllegalArgumentException> { client.nodeSyslog(c, "node-a", -1, 1) }
                assertFailsWith<IllegalArgumentException> { client.nodeSyslog(c, "node-a", 0, 0) }
            }
            assertTrue(seen.isEmpty())
        }
    }
}
