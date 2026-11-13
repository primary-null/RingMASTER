package local.ringleader.core

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scheduler's own transport against a local HTTPS fixture with a pinned self-signed certificate on 127.0.0.1.
 * Proves one TLS socket per call, no retry, no 401 replay, no redirect following, and at most one request even when
 * the failure happens right after the request headers were written.
 */
class ScheduleWireTest {
    private class Seen(val method: String, val path: String, val authorization: String?, val contentType: String?, var body: String = "")

    private object Tls {
        val pin: String
        val context: SSLContext
        init {
            val dir = Files.createTempDirectory("ringmaster-schedule-tls")
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
            Files.deleteIfExists(key); Files.deleteIfExists(dir)
        }
    }

    private fun withServer(status: Int, body: String, location: String? = null, headersArrived: CountDownLatch? = null,
        block: (address: String, seen: MutableList<Seen>) -> Unit) {
        val seen = java.util.Collections.synchronizedList(mutableListOf<Seen>())
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(Tls.context)
        server.createContext("/") { exchange ->
            val request = Seen(exchange.requestMethod, exchange.requestURI.rawPath, exchange.requestHeaders.getFirst("Authorization"), exchange.requestHeaders.getFirst("Content-Type"))
            seen += request // counted when the request line and headers arrive, before any body
            headersArrived?.countDown()
            request.body = runCatching { String(exchange.requestBody.readAllBytes()) }.getOrDefault("<cut>")
            val bytes = body.toByteArray()
            location?.let { exchange.responseHeaders.add("Location", it) }
            runCatching { exchange.sendResponseHeaders(status, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) } }
            exchange.close()
        }
        server.start()
        try { block("https://127.0.0.1:${server.address.port}", seen) } finally { server.stop(0) }
    }

    private val upid = "UPID:node-a:00000010:00000011:65ABCDEF:qmshutdown:104:sched@pve!runner:"
    private fun connection(address: String, pin: String = Tls.pin) = Connection(address, "sched@pve!runner", "test-only-secret", pin)

    @Test fun shutdownIsOneGracefulPostWithTheTokenHeaderOnly() = runBlocking {
        withServer(200, """{"data":"$upid"}""") { address, seen ->
            connection(address).use { c ->
                assertEquals(upid, runBlocking { LiveScheduleGateway(1000, 1500).dispatch(c, "node-a", "VM", 104, ScheduleAction.Shutdown) })
            }
            val request = seen.single()
            assertEquals("POST", request.method)
            assertEquals("/api2/json/nodes/node-a/qemu/104/status/shutdown", request.path)
            assertEquals("""{"forceStop":false}""", request.body)
            assertEquals("PVEAPIToken=sched@pve!runner=test-only-secret", request.authorization)
            assertEquals(POWER_CONTENT_TYPE, request.contentType)
        }
    }

    @Test fun startOnAContainerUsesTheLxcPathAndAnEmptyObject() = runBlocking {
        val ct = "UPID:node-a:00000010:00000011:65ABCDEF:vzstart:204:sched@pve!runner:"
        withServer(200, """{"data":"$ct"}""") { address, seen ->
            connection(address).use { c -> runBlocking { LiveScheduleGateway(1000, 1500).dispatch(c, "node-a", "Container", 204, ScheduleAction.Start) } }
            assertEquals("/api2/json/nodes/node-a/lxc/204/status/start", seen.single().path)
            assertEquals("{}", seen.single().body)
        }
    }

    @Test fun failureRightAfterTheHeadersIsUncertainAndExactlyOneRequest() {
        val arrived = CountDownLatch(1)
        withServer(200, """{"data":"$upid"}""", headersArrived = arrived) { address, seen ->
            val transport = ProxmoxTransport(1000, 1500, storageAfterHeaders = {
                check(arrived.await(2, TimeUnit.SECONDS))
                throw java.io.IOException("Injected failure after complete POST headers")
            })
            connection(address).use { c ->
                assertFailsWith<ScheduleUncertainException> { runBlocking { LiveScheduleGateway(transport).dispatch(c, "node-a", "VM", 104, ScheduleAction.Shutdown) } }
            }
            Thread.sleep(200)
            assertEquals(1, seen.size)
        }
    }

    @Test fun refusalsAndUnclearRepliesAreNeverReplayed() {
        for ((status, expected) in listOf(401 to "refused401", 403 to "refused403", 400 to "refused400", 500 to "uncertain", 302 to "uncertain", 404 to "uncertain")) {
            withServer(status, """{"data":null}""", location = if (status == 302) "https://elsewhere.example.test/" else null) { address, seen ->
                connection(address).use { c ->
                    val e = runCatching { runBlocking { LiveScheduleGateway(1000, 1500).dispatch(c, "node-a", "VM", 104, ScheduleAction.Start) } }.exceptionOrNull()
                    when (expected) {
                        "uncertain" -> assertTrue(e is ScheduleUncertainException, "$status: $e")
                        else -> assertEquals(expected.removePrefix("refused").toInt(), (e as ScheduleRefusedException).status)
                    }
                }
                assertEquals(1, seen.size, "HTTP $status")
            }
        }
        // 200 without a task for this guest is unclear too, never resent.
        withServer(200, """{"data":"UPID:node-b:00000010:00000011:65ABCDEF:qmstart:104:sched@pve!runner:"}""") { address, seen ->
            connection(address).use { c -> assertFailsWith<ScheduleUncertainException> { runBlocking { LiveScheduleGateway(1000, 1500).dispatch(c, "node-a", "VM", 104, ScheduleAction.Start) } } }
            assertEquals(1, seen.size)
        }
    }

    @Test fun aChangedCertificateIsRefusedBeforeAnythingIsSent() {
        withServer(200, """{"data":"$upid"}""") { address, seen ->
            connection(address, pin = "ab".repeat(32)).use { c ->
                assertFailsWith<ScheduleNotSentException> { runBlocking { LiveScheduleGateway(1000, 1500).dispatch(c, "node-a", "VM", 104, ScheduleAction.Shutdown) } }
                assertFailsWith<ScheduleNotSentException> { runBlocking { LiveScheduleGateway(1000, 1500).guests(c) } }
            }
            connection(address, pin = "").use { c -> // normal TLS refuses the self-signed fixture
                assertFailsWith<ScheduleNotSentException> { runBlocking { LiveScheduleGateway(1000, 1500).dispatch(c, "node-a", "VM", 104, ScheduleAction.Shutdown) } }
            }
            assertEquals(0, seen.size)
        }
    }

    @Test fun passwordConnectionsAreNeverUsedInTheBackground() {
        withServer(200, "{}") { address, seen ->
            Connection(address, "root@pam", "test-only-password", Tls.pin).use { c ->
                assertFailsWith<IllegalArgumentException> { runBlocking { LiveScheduleGateway(1000, 1500).dispatch(c, "node-a", "VM", 104, ScheduleAction.Start) } }
            }
            assertEquals(0, seen.size)
        }
    }

    @Test fun readsUseExactPathsAndParseIdentityAndState() {
        withServer(200, """{"data":{"status":"running","qmpstatus":"running","name":"alpha","smbios1":"uuid=0F0E0D0C-0B0A-4908-8706-050403020100,manufacturer=x","meta":"creation-qemu=9.0.2,ctime=1700000000","scsi0":"local-lvm:vm-104-disk-0,size=8G","ostype":"l26"}}""") { address, seen ->
            connection(address).use { c ->
                val facts = runBlocking { LiveScheduleGateway(1000, 1500).identity(c, "node-a", "VM", 104) }
                assertEquals("0F0E0D0C-0B0A-4908-8706-050403020100", facts.uuid)
                assertEquals(1700000000L, facts.ctime)
                assertEquals("VM|alpha|l26|local-lvm:vm-104-disk-0", facts.fallback)
                val state = runBlocking { LiveScheduleGateway(1000, 1500).state(c, "node-a", "VM", 104) }
                assertEquals("running", state.status)
                assertEquals("running", state.qmpStatus)
            }
            assertEquals(listOf("/api2/json/nodes/node-a/qemu/104/config", "/api2/json/nodes/node-a/qemu/104/status/current"), seen.map { it.path })
            assertTrue(seen.all { it.method == "GET" })
        }
        val ct = parseIdentityFacts("""{"data":{"hostname":"web","ostype":"debian","rootfs":"local-lvm:vm-204-disk-0,size=8G","lock":"backup","template":1}}""", "Container")
        assertNull(ct.uuid); assertNull(ct.ctime)
        assertEquals("backup", ct.lock); assertTrue(ct.template)
        assertEquals("Container|web|debian|local-lvm:vm-204-disk-0", ct.fallback)
        assertNull(parseIdentityFacts("""{"data":{"smbios1":"uuid=00000000-0000-0000-0000-000000000000"}}""", "VM").uuid)
    }
}
