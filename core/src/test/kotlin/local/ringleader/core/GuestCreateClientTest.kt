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
class GuestCreateClientTest {
    private class Seen(val method: String, val target: String, val authorization: String?, val body: String, val contentType: String?)
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
            val request = Seen(exchange.requestMethod, target, exchange.requestHeaders.getFirst("Authorization"), exchange.requestBody.readAllBytes().toString(Charsets.UTF_8), exchange.requestHeaders.getFirst("Content-Type"))
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

    private fun request(kind: NewGuestKind = NewGuestKind.Container) = CreateRequest(kind, "node-a", 105, "fixture", listOf(CreateParam("vmid", CreateValue.Int(105))))
    @Test fun exactPostFormAndSecretWipe() {
        withServer({ Reply(body = """{"data":"UPID:node-a:00000001:00000001:00000001:vzcreate:105:fixture@pve:"}""") }) { address, pin, seen ->
            val chars = SecretChars.copyOf("marker +&é")
            var held: CharArray? = null; chars.use { held = it }
            Trace.clear()
            Connection(address, "fixture@pve!test", "test-only", pin).use { c ->
                assertTrue(PveClient().createGuest(c, request(), CreateSecrets(chars)) is CreateReply.Accepted)
            }
            assertEquals(1, seen.size); assertEquals("POST", seen.single().method)
            assertEquals("/api2/json/nodes/node-a/lxc", seen.single().target)
            assertEquals("application/x-www-form-urlencoded", seen.single().contentType)
            assertTrue(seen.single().body.contains("password=marker+%2B%26%C3%A9"))
            assertTrue(chars.closed); assertTrue(held!!.all { it == '\u0000' })
            assertFalse(Trace.snapshot().joinToString { Trace.jsonLine(it) }.contains("marker"))
        }
    }
    @Test fun reflectedFieldErrorsNeverReachReplyOrTrace() {
        withServer({ Reply(400, """{"errors":{"password":"marker-secret","memory":"marker-secret"},"message":"marker-secret"}""") }) { address, pin, seen ->
            Trace.clear(); val secret = SecretChars.copyOf("marker-secret")
            val reply = Connection(address, "fixture@pve!test", "test-only", pin).use { PveClient().createGuest(it, request(), CreateSecrets(secret)) }
            assertTrue(reply is CreateReply.Rejected); assertEquals(setOf("password", "memory"), (reply as CreateReply.Rejected).fieldErrors.keys)
            assertFalse(reply.toString().contains("marker-secret")); assertFalse(Trace.snapshot().joinToString { Trace.jsonLine(it) }.contains("marker-secret"))
            assertEquals(1, seen.size); assertTrue(secret.closed)
        }
    }
    @Test fun redirectedAndFailedPostsAreUnknownAndNeverRetried() {
        for (code in listOf(302, 404, 409, 429, 500, 503)) withServer({ Reply(code, "marker-secret", "/elsewhere") }) { address, pin, seen ->
            val secret = SecretChars.copyOf("marker-secret")
            Connection(address, "fixture@pve!test", "test-only", pin).use { c -> assertFailsWith<AmbiguousPowerResultException> { PveClient().createGuest(c, request(NewGuestKind.Vm), CreateSecrets(secret)) } }
            assertEquals(1, seen.size); assertTrue(secret.closed)
        }
    }
    @Test fun passwordSessionSent401NeverGetsAnotherTicketOrRetries() {
        withServer({ seen -> if (seen.target == "/api2/json/access/ticket") Reply(body = """{"data":{"ticket":"fixture-ticket","CSRFPreventionToken":"fixture-csrf","username":"root@pam"}}""") else Reply(401, "refused") }) { address, pin, seen ->
            val secret = SecretChars.copyOf("fixture-password")
            Connection(address, "root@pam", "fixture-password", pin).use { c -> assertFailsWith<SessionExpiredException> { PveClient().createGuest(c, request(), CreateSecrets(secret)) } }
            assertEquals(1, seen.count { it.target == "/api2/json/access/ticket" })
            assertEquals(1, seen.count { it.target.endsWith("/lxc") }); assertTrue(secret.closed)
        }
    }
    @Test fun wrongPinSendsNothingAndWipesSecrets() {
        withServer({ Reply() }) { address, _, seen ->
            val secret = SecretChars.copyOf("marker-secret")
            Connection(address, "fixture@pve!test", "test-only", "a".repeat(64)).use { c -> assertFailsWith<SSLException> { PveClient().createGuest(c, request(), CreateSecrets(secret)) } }
            assertTrue(seen.isEmpty()); assertTrue(secret.closed)
        }
    }
    @Test fun createReadRoutesAndTakenId() {
        withServer({ seen -> when { seen.target.contains("nextid?") -> Reply(400, """{"errors":{"vmid":"taken"}}"""); seen.target == NEXT_ID_PATH -> Reply(body = """{"data":"140"}"""); seen.target == CLUSTER_OPTIONS_PATH -> Reply(body = """{"data":{}}"""); else -> Reply() } }) { address, pin, seen ->
            Connection(address, "fixture@pve!test", "test-only", pin).use { c ->
                val client = PveClient(); assertEquals(NextIdReply.Free(140), client.nextId(c)); assertTrue(client.nextId(c, 105) is NextIdReply.Taken)
                client.pools(c); client.bridges(c, "node-a"); client.cpuModels(c, "node-a", "aarch64"); client.cpuFlags(c, "node-a", "x86_64", "tcg"); client.clusterOptions(c)
            }
            assertEquals(listOf(NEXT_ID_PATH, "$NEXT_ID_PATH?vmid=105", POOLS_PATH, "/api2/json/nodes/node-a/network?type=any_bridge", "/api2/json/nodes/node-a/capabilities/qemu/cpu?arch=aarch64", "/api2/json/nodes/node-a/capabilities/qemu/cpu-flags?arch=x86_64&accel=tcg", CLUSTER_OPTIONS_PATH), seen.map { it.target })
            assertTrue(seen.all { it.method == "GET" && it.body.isEmpty() })
        }
    }
    @Test fun transportWipesFormBytesImmediatelyAfterWriting() {
        withServer({ Reply(body = """{"data":"accepted"}""") }) { address, pin, seen ->
            val body = "vmid=105&password=marker-secret".toByteArray()
            Connection(address, "fixture@pve!test", "test-only", pin).use { c ->
                ProxmoxTransport().direct(c, "/api2/json/nodes/node-a/lxc", body, contentType = "application/x-www-form-urlencoded", readCreateErrors = true)
            }
            assertTrue(body.all { it == 0.toByte() }); assertEquals(1, seen.size)
        }
    }
    @Test fun pdmCannotUseAnyCreateReads() {
        Connection("https://example.test:8443", "fixture@pdm!test", "test-only", "", PDM_KIND).use { c ->
            val client = PveClient()
            assertFailsWith<IllegalArgumentException> { client.nextId(c) }
            assertFailsWith<IllegalArgumentException> { client.pools(c) }
            assertFailsWith<IllegalArgumentException> { client.bridges(c, "node-a") }
            assertFailsWith<IllegalArgumentException> { client.cpuModels(c, "node-a", null) }
            assertFailsWith<IllegalArgumentException> { client.cpuFlags(c, "node-a", null, "kvm") }
            assertFailsWith<IllegalArgumentException> { client.clusterOptions(c) }
        }
    }

}
