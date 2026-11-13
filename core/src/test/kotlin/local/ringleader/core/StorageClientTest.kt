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
 * The storage view's two reads (`GET /storage` and a storage's `rrddata`) against a local HTTPS fixture with a pinned
 * self-signed certificate on 127.0.0.1. Bodies are handwritten with invented names and documentation addresses. Every call is
 * one GET at an exact path; every refusal happens before a request is sent.
 */
class StorageClientTest {
    private class Seen(val method: String, val target: String, val authorization: String?, val bodyBytes: Int)
    private class Reply(val status: Int = 200, val body: String = """{"data":[]}""", val location: String? = null)

    private object Tls {
        val pin: String
        val context: SSLContext
        init {
            val dir = Files.createTempDirectory("ringmaster-storage-tls")
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
            val request = Seen(exchange.requestMethod, target, exchange.requestHeaders.getFirst("Authorization"), exchange.requestBody.readAllBytes().size)
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

    private val configBody = """{"data":[
        {"storage":"local","type":"dir","content":"iso,vztmpl,backup","path":"/srv/fixture-local","digest":"0123456789abcdef0123456789abcdef01234567"},
        {"storage":"backup-nfs","type":"nfs","content":"backup","server":"198.51.100.7","export":"/export/fixture","disable":1,"nodes":"node-a",
         "prune-backups":"keep-last=3","max-protected-backups":4},
        {"storage":"pbs-store","type":"pbs","content":"backup","server":"backup.example.test","datastore":"fixture-store","username":"fixture@pbs",
         "fingerprint":"aa:bb:cc:dd","namespace":"fixture-ns"}
    ]}"""

    private val historyBody = """{"data":[{"time":1790000060,"used":20,"total":100,"avail":80},{"time":1790000000,"used":10,"total":100}]}"""

    private fun good(request: Seen): Reply = when {
        request.target == STORAGE_CONFIG_PATH -> Reply(body = configBody)
        request.target.contains("/rrddata") -> Reply(body = historyBody)
        else -> Reply(404)
    }

    private fun connection(address: String, pin: String, kind: String = DIRECT_PVE_KIND) =
        Connection(address, if (kind == PDM_KIND) "fixture@pdm!read" else "fixture@pve!read", "test-only", pin, kind)

    private fun reads(client: PveClient, c: Connection): List<Pair<String, () -> Any?>> = listOf(
        "config" to { client.storageConfig(c) },
        "history" to { client.storageHistory(c, "node-a", "local", "hour", "AVERAGE") },
    )

    @Test fun eachReadIsOneGetAtItsExactPath() {
        withServer(::good) { address, pin, seen ->
            val client = PveClient()
            connection(address, pin).use { c ->
                val config = client.storageConfig(c)
                assertEquals(listOf("local", "backup-nfs", "pbs-store"), config.map { it.storage })
                assertTrue(config.single { it.storage == "backup-nfs" }.disabled)
                assertEquals(setOf("node-a"), config.single { it.storage == "backup-nfs" }.nodes)
                val history = client.storageHistory(c, "node-a", "local", "hour", "AVERAGE")
                assertEquals(listOf(1_790_000_000.0, 1_790_000_060.0), history.map { it.time })
                assertEquals(setOf("used", "total"), history.flatMap { it.values.keys }.toSet(), "avail is not kept")
                client.storageHistory(c, "node-a", "backup-nfs", "year", "MAX")
            }
            assertEquals(listOf(
                "/api2/json/storage",
                "/api2/json/nodes/node-a/storage/local/rrddata?timeframe=hour&cf=AVERAGE",
                "/api2/json/nodes/node-a/storage/backup-nfs/rrddata?timeframe=year&cf=MAX",
            ), seen.map { it.target })
            assertTrue(seen.all { it.method == "GET" && it.bodyBytes == 0 }, seen.map { it.method }.toString())
            assertTrue(seen.all { it.authorization?.startsWith("PVEAPIToken=fixture@pve!read=") == true })
        }
    }

    @Test fun nothingThatNamesAServerOrAPathLeavesTheConfigurationRead() {
        withServer(::good) { address, pin, _ ->
            Trace.clear()
            val rows = connection(address, pin).use { PveClient().storageConfig(it) }
            val kept = rows.toString()
            val traced = Trace.snapshot().joinToString("\n") { Trace.jsonLine(it) }
            for (text in listOf("198.51.100.7", "/export/fixture", "/srv/fixture-local", "backup.example.test", "fixture-store", "fixture@pbs",
                "aa:bb:cc:dd", "fixture-ns", "0123456789abcdef")) {
                assertFalse(kept.contains(text), "kept: $text")
                assertFalse(traced.contains(text), "traced: $text")
            }
            assertTrue(traced.contains("/api2/json/storage"), "The span names the path")
            assertFalse(traced.contains("test-only"), "The secret is never traced")
        }
    }

    @Test fun deniedExpiredMissingAndRefusedReadsSaySo() {
        for ((status, expected) in listOf(403 to GuestPermissionException::class, 401 to SessionExpiredException::class)) {
            withServer({ Reply(status) }) { address, pin, seen ->
                connection(address, pin).use { c ->
                    for ((name, call) in reads(PveClient(), c)) {
                        assertEquals(expected, assertFailsWith<IllegalStateException>("$name $status") { call() }::class, "$name $status")
                    }
                }
                assertEquals(2, seen.size, "No read is retried")
            }
        }
        withServer({ Reply(404) }) { address, pin, _ ->
            connection(address, pin).use { c ->
                assertEquals(listOf(STORAGE_CONFIG_NOT_FOUND, STORAGE_HISTORY_NOT_FOUND),
                    reads(PveClient(), c).map { (_, call) -> assertFailsWith<IllegalStateException> { call() }.message })
            }
        }
        withServer({ Reply(400) }) { address, pin, _ ->
            connection(address, pin).use { c ->
                assertEquals(listOf(STORAGE_CONFIG_REFUSED, STORAGE_HISTORY_REFUSED),
                    reads(PveClient(), c).map { (_, call) -> assertFailsWith<IllegalStateException> { call() }.message })
            }
        }
    }

    @Test fun aBodyThatIsNotAListFailsClosed() {
        withServer({ Reply(body = """{"data":{"storage":"local"}}""") }) { address, pin, _ ->
            connection(address, pin).use { c ->
                assertEquals(listOf(STORAGE_CONFIG_EMPTY_BODY, STORAGE_HISTORY_EMPTY_BODY),
                    reads(PveClient(), c).map { (_, call) -> assertFailsWith<IllegalStateException> { call() }.message })
            }
        }
    }

    @Test fun redirectsAreRefusedAndNeverFollowed() {
        withServer({ Reply(302, location = "https://127.0.0.1:1/elsewhere") }) { address, pin, seen ->
            connection(address, pin).use { c ->
                for ((name, call) in reads(PveClient(), c)) assertTrue(assertFailsWith<IllegalStateException>(name) { call() }.message!!.contains("redirected"), name)
            }
            assertTrue(seen.none { it.target.endsWith("/elsewhere") })
        }
    }

    @Test fun theStatusReadAndTheIndexAreRefusedBeforeAnyRequest() {
        withServer(::good) { address, pin, seen ->
            val transport = ProxmoxTransport()
            connection(address, pin).use { c ->
                for (path in listOf(
                    "/api2/json/nodes/node-a/storage/local/status",
                    "/api2/json/nodes/node-a/storage/local/status?content=iso",
                    "/api2/json/nodes/node-a/storage/local/status/",
                    "/api2/json/nodes/node-a/storage",
                    "/api2/json/nodes/node-a/storage/",
                )) {
                    assertEquals(FORBIDDEN_NODE_READ, assertFailsWith<IllegalArgumentException>(path) { transport.direct(c, path) }.message, path)
                    assertFailsWith<IllegalArgumentException>(path) { transport.direct(address, "fixture@pve!read", "test-only", pin, path) }
                }
            }
            assertTrue(seen.isEmpty(), seen.map { it.target }.toString())
        }
    }

    @Test fun aPdmConnectionNeverReachesTheNetwork() {
        withServer(::good) { address, pin, seen ->
            connection(address, pin, PDM_KIND).use { c ->
                for ((name, call) in reads(PveClient(), c)) assertEquals(DIRECT_REQUEST_REFUSAL, assertFailsWith<IllegalArgumentException>(name) { call() }.message, name)
            }
            assertTrue(seen.isEmpty())
        }
    }

    @Test fun badNamesAndRangesAreRefusedBeforeAnyRequest() {
        withServer(::good) { address, pin, seen ->
            val client = PveClient()
            connection(address, pin).use { c ->
                for (node in listOf("node.example", "a b", "node/../x", "")) assertFailsWith<IllegalArgumentException>(node) { client.storageHistory(c, node, "local", "hour", "AVERAGE") }
                for (storage in listOf("x", "a/b", "../status", "local/status")) assertFailsWith<IllegalArgumentException>(storage) { client.storageHistory(c, "node-a", storage, "hour", "AVERAGE") }
                assertFailsWith<IllegalArgumentException> { client.storageHistory(c, "node-a", "local", "decade", "AVERAGE") }
                assertFailsWith<IllegalArgumentException> { client.storageHistory(c, "node-a", "local", "hour", "MIN") }
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
}
