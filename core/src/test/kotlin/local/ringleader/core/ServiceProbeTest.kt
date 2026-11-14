package local.ringleader.core

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Everything here talks to servers on 127.0.0.1 that the test starts itself. */
class ServiceProbeTest {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val closers = mutableListOf<AutoCloseable>()
    private val dir: Path = Files.createTempDirectory("ringmaster-service-probe")

    @AfterTest fun tearDown() {
        closers.reversed().forEach { runCatching { it.close() } }
        dir.toFile().deleteRecursively()
    }

    /** A plain HTTP server that records each request's header lines and answers every connection with [reply]. */
    private inner class PlainServer(private val reply: String) {
        val requests: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
        val server = ServerSocket(0, 50, loopback).also { closers += it }
        val port get() = server.localPort
        init {
            Thread {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    Thread { serve(client) }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }
        private fun serve(client: Socket) = client.use {
            it.soTimeout = 5000
            val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val lines = mutableListOf<String>()
            while (true) { val line = runCatching { reader.readLine() }.getOrNull() ?: break; if (line.isEmpty()) break; lines += line }
            if (lines.isNotEmpty() && lines.first().startsWith("GET ")) {
                requests += lines.toList()
                it.getOutputStream().write(reply.toByteArray(Charsets.UTF_8)); it.getOutputStream().flush()
            }
        }
    }

    /** A TLS server with [store]'s key. It counts the requests that arrive after a handshake. */
    private inner class TlsServer(store: KeyStore, private val body: String = "<title>Vaultwarden Web</title>") {
        val requests = AtomicInteger()
        val handshakes = AtomicInteger()
        val server: SSLServerSocket
        init {
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "changeit".toCharArray()) }
            val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
            server = (context.serverSocketFactory.createServerSocket(0, 50, loopback) as SSLServerSocket).also { closers += it }
            Thread {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() as javax.net.ssl.SSLSocket }.getOrNull() ?: break
                    Thread {
                        client.use { c ->
                            c.soTimeout = 5000
                            runCatching {
                                c.startHandshake(); handshakes.incrementAndGet()
                                val reader = c.inputStream.bufferedReader()
                                val first = reader.readLine() ?: return@runCatching
                                if (first.startsWith("GET ")) requests.incrementAndGet()
                                while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
                                c.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                                c.outputStream.flush()
                            }
                        }
                    }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }
        val port get() = server.localPort
    }

    /** Accepts connections and says nothing, like a service that hangs. */
    private fun silentServer(): ServerSocket = ServerSocket(0, 50, loopback).also { s ->
        closers += s
        Thread { while (!s.isClosed) { val c = runCatching { s.accept() }.getOrNull() ?: break; closers += c } }.apply { isDaemon = true }.start()
    }

    /** Sends a banner that is not HTTP, then waits. */
    private fun bannerServer(): ServerSocket = ServerSocket(0, 50, loopback).also { s ->
        closers += s
        Thread {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                closers += c
                runCatching { c.getOutputStream().write("SSH-2.0-OpenSSH_9.9\r\n".toByteArray()); c.getOutputStream().flush() }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun closedPort(): Int = ServerSocket(0, 1, loopback).use { it.localPort }

    private fun keyStore(san: String? = "ip:127.0.0.1"): Pair<KeyStore, X509Certificate> {
        val path = dir.resolve("k${System.nanoTime()}.p12")
        val keytool = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool")
        val args = mutableListOf(keytool.toString(), "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
            "-storetype", "PKCS12", "-keystore", path.toString(), "-storepass", "changeit", "-keypass", "changeit",
            "-dname", "CN=localhost", "-validity", "2", "-noprompt")
        if (san != null) args += listOf("-ext", "SAN=$san")
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes()
        check(process.waitFor() == 0) { "keytool failed: " + output.toString(Charsets.UTF_8) }
        val store = KeyStore.getInstance("PKCS12")
        Files.newInputStream(path).use { store.load(it, "changeit".toCharArray()) }
        return store to store.getCertificate("test") as X509Certificate
    }

    private fun trusting(cert: X509Certificate): KeyStore = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("anchor", cert) }

    private val ok = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nServer: test\r\nConnection: close\r\n\r\n<html><head><title>Grafana</title></head></html>"

    @Test fun findReportsOpenClosedAndUntrustedPortsWithoutTrustingAnything() = runBlocking {
        val web = PlainServer(ok)
        val (store, _) = keyStore()
        val tls = TlsServer(store)
        val banner = bannerServer()
        val closed = closedPort()
        val ports = listOf(web.port, closed, tls.port, banner.localPort)
        val probe = ServiceProbe(connectTimeoutMs = 500, readTimeoutMs = 1500, concurrency = 2)
        val steps = withTimeout(30_000) { probe.find(listOf("127.0.0.1"), ports) { if (it == tls.port) ServiceEndpoint(it, secure = true) else null }.toList() }
        val last = steps.last()
        assertTrue(last.finished)
        assertEquals(4, last.checked)
        assertEquals(4, last.total)
        val byPort = last.answers.associateBy { it.port }
        byPort.getValue(web.port).let {
            assertEquals(PortState.Open, it.state); assertEquals(false, it.secure); assertEquals(200, it.status); assertEquals("Grafana", it.title)
        }
        assertEquals(PortState.Closed, byPort.getValue(closed).state)
        byPort.getValue(tls.port).let {
            assertEquals(true, it.secure); assertEquals("self-signed", it.untrusted); assertNull(it.status); assertNull(it.title)
        }
        byPort.getValue(banner.localPort).let { assertEquals(PortState.Open, it.state); assertNull(it.secure) }
        // One GET to the web page, and none to the server whose certificate failed.
        assertEquals(1, web.requests.size)
        assertEquals(0, tls.requests.get())
        // Web pages are listed first.
        assertEquals(web.port, last.answers.first().port)
    }

    @Test fun theRequestCarriesNoCredentialsAndOnlyTheseHeaders() = runBlocking {
        val web = PlainServer(ok)
        ServiceProbe().look("127.0.0.1", web.port, secure = false, path = "/admin")
        assertEquals(listOf("GET /admin HTTP/1.1", "Host: 127.0.0.1:${web.port}", "User-Agent: RingMASTER", "Accept: text/html", "Connection: close"), web.requests.single())
        assertTrue(SERVICE_PROBE_REQUEST.contains("User-Agent: RingMASTER"))
    }

    @Test fun aTrustedCertificateGetsTheGetOverTls() = runBlocking {
        val (store, cert) = keyStore()
        val tls = TlsServer(store)
        val probe = ServiceProbe(trust = trusting(cert), readTimeoutMs = 1500)
        val answer = probe.look("127.0.0.1", tls.port, secure = true)
        assertEquals(true, answer.secure)
        assertNull(answer.untrusted)
        assertEquals(200, answer.status)
        assertEquals("Vaultwarden Web", answer.title)
        assertEquals(1, tls.requests.get())
        // With no hint an unknown port is asked in plain HTTP first. That was its one GET, so the handshake that
        // follows sends nothing: the answer says https, without a title.
        val unhinted = probe.look("127.0.0.1", tls.port, secure = null)
        assertEquals(true, unhinted.secure)
        assertNull(unhinted.status)
        assertEquals(1, tls.requests.get())
    }

    @Test fun aCertificateForAnotherNameIsReported() = runBlocking {
        val (store, cert) = keyStore(san = "dns:service.invalid")
        val tls = TlsServer(store)
        val answer = ServiceProbe(trust = trusting(cert)).look("127.0.0.1", tls.port, secure = true)
        assertEquals(true, answer.secure)
        assertEquals("made for another name", answer.untrusted)
        assertEquals(0, tls.requests.get())
    }

    @Test fun theWrongSchemeHintStillFindsTheRightScheme() = runBlocking {
        val web = PlainServer(ok)
        val plainAsTls = ServiceProbe(readTimeoutMs = 1500).look("127.0.0.1", web.port, secure = true)
        assertEquals(false, plainAsTls.secure)
        assertEquals("Grafana", plainAsTls.title)
        assertEquals(1, web.requests.size)
        val (store, _) = keyStore()
        val tls = TlsServer(store)
        val tlsAsPlain = ServiceProbe(readTimeoutMs = 1500).look("127.0.0.1", tls.port, secure = false)
        assertEquals(true, tlsAsPlain.secure)
        assertEquals("self-signed", tlsAsPlain.untrusted)
    }

    @Test fun cancellingStopsAHangingLookAtOnce() = runBlocking {
        val silent = silentServer()
        val probe = ServiceProbe(readTimeoutMs = 15_000)
        val started = System.nanoTime()
        val job = async { probe.look("127.0.0.1", silent.localPort, secure = false) }
        delay(300)
        job.cancel()
        runCatching { job.await() }
        assertTrue(job.isCancelled)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "cancel did not wait for the read timeout")
    }

    @Test fun testSaysWhatHappenedInPlainWords() = runBlocking {
        val probe = ServiceProbe(connectTimeoutMs = 500, readTimeoutMs = 1500)
        val closed = closedPort()
        assertEquals(ServiceTest(TestLevel.Bad, "Port $closed is closed."), probe.test("http://127.0.0.1:$closed"))
        val web = PlainServer(ok)
        assertEquals(ServiceTest(TestLevel.Good, "Answers: Grafana."), probe.test("http://127.0.0.1:${web.port}"))
        val wrong = probe.test("https://127.0.0.1:${web.port}/")
        assertEquals(TestLevel.Bad, wrong.level)
        assertEquals("Port ${web.port} speaks http, not https.", wrong.message)
        assertEquals("http://127.0.0.1:${web.port}", wrong.fix)
        val (store, _) = keyStore()
        val tls = TlsServer(store)
        val untrusted = probe.test("https://127.0.0.1:${tls.port}")
        assertEquals(TestLevel.Warn, untrusted.level)
        assertEquals("The certificate is not trusted: self-signed. The browser will warn before it opens the page.", untrusted.message)
        val plain = probe.test("http://127.0.0.1:${tls.port}")
        assertEquals("Port ${tls.port} speaks https, not http. Its certificate is not trusted: self-signed.", plain.message)
        assertEquals("https://127.0.0.1:${tls.port}", plain.fix)
        assertEquals(0, tls.requests.get())
        assertEquals(TestLevel.Bad, probe.test("ftp://127.0.0.1").level)
    }

    @Test fun describeTestCoversRedirectsSignInsAndErrors() {
        val url = "http://192.0.2.10:3000"
        fun answer(status: Int, title: String? = null, location: String? = null) =
            ServiceAnswer("192.0.2.10", 3000, PortState.Open, secure = false, status = status, title = title, location = location)
        assertEquals("Answers: a web page. It sends the browser on to /login.", describeTest(url, answer(302, location = "/login")).message)
        val elsewhere = describeTest(url, answer(301, location = "https://192.0.2.10:8443/x?y=z"))
        assertEquals("Answers: a web page. It sends the browser to another address.", elsewhere.message)
        assertEquals("https://192.0.2.10:8443/x", elsewhere.fix)
        assertEquals("Answers: Grafana. It asks for a sign-in.", describeTest(url, answer(401, "Grafana")).message)
        val notFound = describeTest("http://192.0.2.10:3000/nope", answer(404))
        assertEquals(TestLevel.Warn, notFound.level)
        assertEquals("Answers, but /nope was not found (HTTP 404).", notFound.message)
        assertEquals("http://192.0.2.10:3000", notFound.fix)
        assertEquals("Answers with a server error (HTTP 502).", describeTest(url, answer(502)).message)
        val page = describeTest(url, answer(200, "pve-west dashboard"))
        assertEquals("Answers: pve-west dashboard.", page.message)
        assertTrue(page.fromPage, "a title from the page may name a host, so the dialog conceals it in showcase mode")
        assertEquals("No answer on port 3000. The guest may be off, or a firewall drops it.", describeTest(url, ServiceAnswer("192.0.2.10", 3000, PortState.NoAnswer)).message)
        assertEquals("Port 3000 is open, but it is not a web page.", describeTest(url, ServiceAnswer("192.0.2.10", 3000, PortState.Open)).message)
        assertEquals("http://[2001:db8::5]:443", withScheme("https://[2001:db8::5]/", false))
        assertEquals("https://192.0.2.10", withScheme("http://192.0.2.10:443", true))
    }

    @Test fun untrustedReasonsReadPlainly() {
        assertEquals("not from an authority this computer trusts", untrustedReason(emptyList(), RuntimeException("x")))
        assertEquals("expired", untrustedReason(emptyList(), java.security.cert.CertificateException(java.security.cert.CertificateExpiredException("old"))))
    }
}
