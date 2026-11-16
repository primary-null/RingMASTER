package local.ringleader.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KClass
import kotlin.test.*

/**
 * A raw HTTPS/1.1 server for transport safety: every TCP connection, every decrypted request byte and every complete request
 * line is counted. [drop] decides, per complete request, to read it whole and close without a status line (what a NAT
 * timeout, a Wi-Fi change or a restarting pveproxy does). [resetAfter] instead resets the TCP connection (RST, no TLS
 * close_notify) after that many request bytes, before the request is complete. [answer] sees the whole request head.
 * Loopback only.
 */
internal class OneShotServer(
    private val context: SSLContext,
    private val drop: (String) -> Boolean = { false },
    private val resetAfter: Int? = null,
    private val protocols: Array<String>? = null,
    private val answer: (String) -> Pair<Int, String>,
) : AutoCloseable {
    private val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val connections = AtomicInteger()
    val requestBytes = AtomicLong()
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val heads: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val negotiated: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val address get() = "https://127.0.0.1:${listener.localPort}"
    private val thread = Thread {
        while (!listener.isClosed) {
            val raw = try { listener.accept() } catch (_: Exception) { break }
            Thread { serve(raw) }.apply { isDaemon = true }.start()
        }
    }.apply { isDaemon = true; start() }

    private fun serve(raw: Socket) {
        connections.incrementAndGet()
        try {
            raw.soTimeout = 10_000
            val tls = context.socketFactory.createSocket(raw, "127.0.0.1", raw.port, true) as SSLSocket
            tls.useClientMode = false
            protocols?.let { tls.enabledProtocols = it }
            tls.startHandshake()
            negotiated += tls.session.protocol
            val input = Counting(tls.inputStream)
            if (resetAfter != null) {
                input.readNBytes(resetAfter)
                raw.setSoLinger(true, 0)
                raw.close()
                return
            }
            val head = readHead(input) ?: return
            val line = head.lineSequence().first().substringBeforeLast(" HTTP/")
            val length = head.lineSequence().firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            if (input.readNBytes(length).size != length) return
            heads += head
            requests += line
            if (drop(line)) { tls.close(); return }
            val (status, body) = answer(head)
            val bytes = body.toByteArray()
            tls.outputStream.apply {
                write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes); flush()
            }
            tls.close()
        } catch (_: Exception) {
        } finally { runCatching { raw.close() } }
    }

    private inner class Counting(private val inner: InputStream) : InputStream() {
        override fun read(): Int = inner.read().also { if (it >= 0) requestBytes.incrementAndGet() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) requestBytes.addAndGet(it.toLong()) }
    }

    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (out.size() <= 65536) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            val a = out.toByteArray(); val s = a.size
            if (s >= 4 && a[s - 4] == 13.toByte() && a[s - 3] == 10.toByte() && a[s - 2] == 13.toByte() && a[s - 1] == 10.toByte()) return a.toString(Charsets.ISO_8859_1)
        }
        return null
    }

    override fun close() { listener.close(); thread.join(2000) }
}

/** Transport safety: what reaches the server, how many times, and what the caller is told. Proven against [OneShotServer]. */
class TransportSafetyTest {
    private val store = AuditCerts.generate("leaf")
    private val pin = AuditCerts.pin(AuditCerts.cert(store))
    private val other = AuditCerts.pin(AuditCerts.cert(AuditCerts.generate("other")))
    private val upid = """{"data":"UPID:node-a:00000001:00000002:00000003:qmdelsnapshot:112:root@pam:"}"""
    private fun server(drop: (String) -> Boolean = { false }, resetAfter: Int? = null, answer: (String) -> Pair<Int, String> = { 200 to upid }) =
        OneShotServer(AuditCerts.serverContext(store), drop, resetAfter, answer = answer)
    private fun token(address: String, fingerprint: String = pin) = Connection(address, "root@pam!audit", "token-secret", fingerprint)
    private val mutationsByTransport = listOf(
        Triple("POST", "/api2/json/nodes/node-a/qemu/112/status/start", "{}".toByteArray()),
        Triple("DELETE", "/api2/json/nodes/node-a/qemu/112/snapshot/s1", ByteArray(0)),
    )

    /** U1 (moved unchanged from NetworkAuditTlsDefectTest). */
    @Test fun deleteMutationIsNeverReplayedByTheJdk() {
        val upid = """{"data":"UPID:n1:00000001:00000002:00000003:qmdelsnapshot:100:root@pam:"}"""
        RawTlsServer(AuditCerts.serverContext(store)) { 200 to upid }.use { server ->
            server.dropFirst.set(1)
            Connection(server.address, "root@pam!audit", "token-secret", pin).use { c ->
                val outcome = runCatching { ProxmoxTransport().direct(c, "/api2/json/nodes/n1/qemu/100/snapshot/s1", ByteArray(0), method = "DELETE") }
                assertEquals(listOf("DELETE /api2/json/nodes/n1/qemu/100/snapshot/s1"), server.requests.toList(),
                    "the JDK replayed a confirmed DELETE")
                assertIs<AmbiguousPowerResultException>(outcome.exceptionOrNull(), "a lost reply after a sent DELETE must stay ambiguous")
            }
        }
    }

    @Test fun mutationWhoseAnswerIsLostIsSentOnceAndAmbiguous() {
        for ((method, path, body) in mutationsByTransport) server(drop = { true }).use { s ->
            token(s.address).use { c ->
                val outcome = runCatching { ProxmoxTransport(1000, 1500).direct(c, path, body.copyOf(), method = method) }
                assertIs<AmbiguousPowerResultException>(outcome.exceptionOrNull(), method)
            }
            assertEquals(listOf("$method $path"), s.requests.toList(), method)
            assertEquals(1, s.connections.get(), "no second connection for $method")
        }
    }

    @Test fun mutationAnsweredNormallyIsSentOnce() {
        for ((method, path, body) in mutationsByTransport) server().use { s ->
            token(s.address).use { c -> assertEquals(upid, ProxmoxTransport(1000, 1500).direct(c, path, body.copyOf(), method = method)) }
            assertEquals(listOf("$method $path"), s.requests.toList(), method)
            assertEquals(1, s.connections.get(), method)
            assertTrue(s.heads.single().contains("Connection: close"), method)
        }
    }

    @Test fun mutationToAMismatchedPinWritesNoRequestByte() {
        for ((method, path, body) in mutationsByTransport) server().use { s ->
            token(s.address, other).use { c ->
                assertFailsWith<SSLException> { ProxmoxTransport(1000, 1500).direct(c, path, body.copyOf(), method = method) }
            }
            assertTrue(s.requests.isEmpty(), method)
            assertEquals(0, s.requestBytes.get(), "not one decrypted request byte for $method")
            assertEquals(1, s.connections.get(), method)
        }
    }

    @Test fun mutationResetMidRequestIsAmbiguousAndNotResent() {
        for ((method, path, body) in mutationsByTransport) server(resetAfter = 12).use { s ->
            token(s.address).use { c ->
                val outcome = runCatching { ProxmoxTransport(1000, 1500).direct(c, path, body.copyOf(), method = method) }
                assertIs<AmbiguousPowerResultException>(outcome.exceptionOrNull(), method)
            }
            assertTrue(s.requests.isEmpty(), method)
            assertEquals(1, s.connections.get(), "no second connection for $method")
        }
    }

    /** Proxmox answers HTTP 501 to a DELETE that declares a body; the one-shot DELETE declares none. */
    @Test fun deleteCarriesNoBodyHeaders() = server().use { s ->
        token(s.address).use { c -> ProxmoxTransport(1000, 1500).direct(c, "/api2/json/nodes/node-a/qemu/112/snapshot/s1", "a=1".toByteArray(), method = "DELETE") }
        val head = s.heads.single().lowercase()
        assertFalse("content-length" in head, head); assertFalse("content-type" in head, head); assertFalse("transfer-encoding" in head, head)
        assertTrue("authorization: pveapitoken=root@pam!audit=token-secret" in head)
    }

    private val ticket = """{"data":{"ticket":"PVE:root@pam:1::sig","CSRFPreventionToken":"csrf","username":"root@pam"}}"""
    private val challenge = """{"data":{"ticket":"challenge","NeedTFA":1}}"""
    private val login = "POST /api2/json/access/ticket"
    private fun password(address: String, fingerprint: String = pin, otp: String? = null) = Connection(address, "root@pam", "pass word", fingerprint, otp = otp)
    /** A lost login answer is an I/O failure, never a refusal (which would latch) and never a second attempt. */
    private fun assertLoginLost(failure: Throwable?) {
        assertIs<java.io.IOException>(failure, "got $failure")
        assertFalse(failure is SSLException, "a lost answer is not a certificate problem")
    }

    /** W1 (moved unchanged from NetworkAuditTlsDefectTest). */
    @Test fun loginPostIsSentOnceEvenWhenTheConnectionDropsBeforeTheAnswer() {
        RawTlsServer(AuditCerts.serverContext(store)) { 200 to ticket }.use { server ->
            server.dropFirst.set(1)
            Connection(server.address, "root@pam", "pass word", pin).use { c ->
                runCatching { c.tickets.get(c) }
            }
            assertEquals(listOf("POST /api2/json/access/ticket"), server.requests.toList(),
                "the JDK re-sent the login POST on a fresh connection")
        }
    }

    @Test fun loginWhoseAnswerIsLostIsSentOnceAndAnIoFailure() = server(drop = { true }, answer = { 200 to ticket }).use { s ->
        password(s.address).use { c -> assertLoginLost(runCatching { c.tickets.get(c) }.exceptionOrNull()) }
        assertEquals(listOf(login), s.requests.toList())
        assertEquals(1, s.connections.get())
    }

    @Test fun twoFactorStepWhoseAnswerIsLostIsSentOnce() {
        val n = AtomicInteger()
        server(drop = { n.incrementAndGet() == 2 }, answer = { 200 to challenge }).use { s ->
            password(s.address, otp = "123456").use { c -> assertLoginLost(runCatching { c.tickets.get(c) }.exceptionOrNull()) }
            assertEquals(listOf(login, login), s.requests.toList(), "the password once and the code once")
            assertEquals(2, s.connections.get())
        }
    }

    /** A lost renewal is one POST of the old ticket; the store then signs in once with the password, as it does after any failed renewal. */
    @Test fun renewalWhoseAnswerIsLostIsSentOnce() {
        val n = AtomicInteger()
        var now = java.time.Instant.parse("2026-11-09T00:00:00Z")
        server(drop = { n.incrementAndGet() == 2 }, answer = { 200 to ticket }).use { s ->
            password(s.address).use { c ->
                val store = TicketStore { now }
                store.get(c); now = now.plusSeconds(61 * 60); store.get(c)
            }
            assertEquals(listOf(login, login, login), s.requests.toList(), "sign-in, one renewal, one full sign-in")
            assertEquals(3, s.connections.get())
        }
    }

    @Test fun loginAnsweredNormallyIsSentOnce() = server(answer = { 200 to ticket }).use { s ->
        password(s.address).use { c -> assertEquals("csrf", c.tickets.get(c).csrf) }
        assertEquals(listOf(login), s.requests.toList())
        val head = s.heads.single()
        assertTrue(head.contains("Content-Length: "), "a fixed-length (streaming) POST: $head")
        assertFalse(head.contains("chunked", ignoreCase = true), head)
    }

    @Test fun loginToAMismatchedPinWritesNoRequestByte() = server(answer = { 200 to ticket }).use { s ->
        password(s.address, other).use { c -> assertFailsWith<SSLException> { c.tickets.get(c) } }
        assertTrue(s.requests.isEmpty())
        assertEquals(0, s.requestBytes.get(), "not one decrypted byte of the password reached the server")
        assertEquals(1, s.connections.get())
    }

    @Test fun loginResetMidRequestIsAnIoFailureAndNotResent() = server(resetAfter = 12, answer = { 200 to ticket }).use { s ->
        password(s.address).use { c -> assertLoginLost(runCatching { c.tickets.get(c) }.exceptionOrNull()) }
        assertTrue(s.requests.isEmpty())
        assertEquals(1, s.connections.get(), "no second connection")
    }

    /** F1 (moved unchanged from NetworkAuditTlsDefectTest). */
    @Test fun apiResponsesAreBounded() {
        val big = "{\"data\":[],\"pad\":\"" + "x".repeat(48 * 1024 * 1024) + "\"}"
        val bytes = big.toByteArray()
        val server = com.sun.net.httpserver.HttpsServer.create(loopback(0), 0)
        server.httpsConfigurator = com.sun.net.httpserver.HttpsConfigurator(AuditCerts.serverContext(store))
        server.createContext("/") { e -> e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) } }
        server.start()
        try {
            val outcome = runCatching { PveClient().overview("https://127.0.0.1:${server.address.port}", "root@pam!audit", "token-secret", pin) }
            assertTrue(outcome.isFailure, "a 48 MiB inventory answer was read into memory and accepted")
        } finally { server.stop(0) }
    }

    /** F2 (moved unchanged from NetworkAuditTlsDefectTest). */
    @Test fun deeplyNestedJsonIsAnOrdinaryParseError() {
        for (deep in listOf("{\"data\":" + "[".repeat(100_000) + "]".repeat(100_000) + "}",
                "{\"data\":[" + "{\"a\":".repeat(100_000) + "1" + "}".repeat(100_000) + "]}")) {
            val outcome = runCatching { parseOverview(deep, "https://127.0.0.1:8006") }
            val frames = outcome.exceptionOrNull()?.stackTrace?.map { "${it.className}.${it.methodName}" }?.distinct()?.take(4)
            assertTrue(outcome.exceptionOrNull() is Exception, "got ${outcome.exceptionOrNull()} at $frames")
        }
    }

    private fun padded(size: Int) = "{\"data\":[],\"pad\":\"" + "x".repeat(size - 20) + "\"}"
    private val deep = "{\"data\":" + "[".repeat(100_000) + "]".repeat(100_000) + "}"

    @Test fun answerLimitsFollowTheRoute() {
        assertEquals(ANSWER_LIMIT_LARGE, answerLimit("/api2/json/cluster/resources"))
        assertEquals(ANSWER_LIMIT_LARGE, answerLimit("/api2/json/resources/list?max-age=0"))
        assertEquals(ANSWER_LIMIT_LARGE, answerLimit("/api2/json/access/permissions"))
        assertEquals(ANSWER_LIMIT_LARGE, answerLimit("/api2/json/nodes/node-a/storage/local/content?content=backup"))
        assertEquals(ANSWER_LIMIT_LARGE, answerLimit("/api2/json/nodes/node-a/tasks/UPID%3Ax/log?start=0&limit=10000"))
        assertEquals(ANSWER_LIMIT_LARGE, answerLimit("/api2/json/nodes/node-a/syslog?start=0&limit=2000"))
        assertEquals(ANSWER_LIMIT_DEFAULT, answerLimit("/api2/json/nodes/node-a/storage/local/content/local%3Aiso%2Fa.iso"))
        assertEquals(ANSWER_LIMIT_DEFAULT, answerLimit("/api2/json/nodes/node-a/qemu/112/status/current"))
        assertEquals(ANSWER_LIMIT_DEFAULT, answerLimit("/api2/json/cluster/resources/extra"))
        assertEquals(4 * 1024 * 1024, ANSWER_LIMIT_DEFAULT); assertEquals(16 * 1024 * 1024, ANSWER_LIMIT_LARGE); assertEquals(64 * 1024, ANSWER_LIMIT_TICKET)
    }

    @Test fun readOverItsLimitIsAPlainSentenceAndTheInventoryMayBeLarger() {
        val body = padded(ANSWER_LIMIT_DEFAULT + 1)
        server(answer = { 200 to body }).use { s ->
            token(s.address).use { c ->
                val refused = assertFailsWith<AnswerRefusedException> { ProxmoxTransport(1000, 5000).direct(c, "/api2/json/nodes/node-a/network") }
                assertEquals("The server's answer was too large.", refused.message)
                assertEquals(body.length, ProxmoxTransport(1000, 5000).direct(c, "/api2/json/cluster/resources").length)
            }
        }
    }

    @Test fun deeplyNestedReadIsAPlainSentence() = server(answer = { 200 to deep }).use { s ->
        token(s.address).use { c ->
            assertEquals(ANSWER_TOO_DEEP, assertFailsWith<AnswerRefusedException> { ProxmoxTransport(1000, 5000).direct(c, "/api2/json/cluster/resources") }.message)
            assertEquals(ANSWER_TOO_DEEP, assertFailsWith<AnswerRefusedException> { PveClient(1000, 5000).overview(c) }.message)
        }
    }

    /** After the send, an answer that is too large or too deep proves nothing: the outcome is unknown, and nothing is resent. */
    @Test fun mutationAnswerOverTheLimitOrTooDeepIsAmbiguous() {
        for (body in listOf(padded(ANSWER_LIMIT_DEFAULT + 1), deep)) server(answer = { 200 to body }).use { s ->
            token(s.address).use { c ->
                assertFailsWith<AmbiguousPowerResultException> { ProxmoxTransport(1000, 5000).direct(c, "/api2/json/nodes/node-a/qemu/112/status/start", "{}".toByteArray()) }
            }
            assertEquals(1, s.requests.size)
        }
    }

    @Test fun storageAndScheduleAnswersUseTheSameBounds() {
        server(answer = { 200 to padded(ANSWER_LIMIT_DEFAULT + 1) }).use { s ->
            token(s.address).use { c ->
                assertEquals(ANSWER_TOO_LARGE, assertFailsWith<AnswerRefusedException> {
                    ProxmoxTransport(1000, 5000).storageExchange(c, "/api2/json/nodes/node-a/storage/local/content/local%3Aiso%2Fa.iso") }.message)
                assertFailsWith<StorageUnclear> { PveClient(1000, 5000).submitStorage(c, StorageKey(c.remoteKey, "node-a", "local"), StorageRequest.UrlDownload("https://example.test/a.iso", "iso", "a.iso")) }
                assertFailsWith<ScheduleUncertainException> { runBlocking { LiveScheduleGateway(1000, 5000).dispatch(c, "node-a", "VM", 112, ScheduleAction.Start) } }
            }
        }
        // A schedule's inventory read of a large cluster is not cut at the old 1 MiB storage bound.
        val rows = (1..4000).joinToString(",") { """{"type":"qemu","vmid":${it + 100},"name":"fixture-$it","node":"node-a","status":"stopped","pad":"${"x".repeat(300)}"}""" }
        server(answer = { 200 to """{"data":[{"type":"node","node":"node-a","status":"online"},$rows]}""" }).use { s ->
            token(s.address).use { c -> assertEquals(4000, runBlocking { LiveScheduleGateway(1000, 5000).guests(c) }.size) }
        }
    }

    @Test fun ticketAnswerIsBounded() = server(answer = { 200 to padded(ANSWER_LIMIT_TICKET + 1) }).use { s ->
        password(s.address).use { c -> assertEquals(ANSWER_TOO_LARGE, assertFailsWith<AnswerRefusedException> { c.tickets.get(c) }.message) }
        assertEquals(1, s.requests.size)
    }

    @Test fun depthScanCountsOnlyStructure() {
        requireJsonDepth("[".repeat(64) + "]".repeat(64))
        assertFailsWith<AnswerRefusedException> { requireJsonDepth("[".repeat(65) + "]".repeat(65)) }
        assertFailsWith<AnswerRefusedException> { requireJsonDepth("{\"a\":".repeat(65) + "1" + "}".repeat(65)) }
        requireJsonDepth("{\"a\":\"" + "[".repeat(500) + "\\\"{{{\",\"b\":[1]}")
        requireJsonDepth("[\"\\\\\"," + "[".repeat(63) + "]".repeat(63) + "]")
        assertFailsWith<AnswerRefusedException> { requireJsonDepth("[\"\\\\\"," + "[".repeat(64) + "]".repeat(64) + "]") }
    }

    @Test fun everyParserInTheTransportFilesRefusesHostileNesting() {
        val vm = Guest("https://127.0.0.1:8006", 112, "fixture", "node-a", "VM", "running")
        val parsers = listOf<(String) -> Any?>(
            { parseOverview(it, "https://127.0.0.1:8006") }, { parseDetails(vm, it) },
            { parsePdmVersion(it) }, { parsePdmRemotes(it) }, { parsePdmResources(it, "https://127.0.0.1:8443") },
            { parseScheduledState(it) }, { parseIdentityFacts(it, "VM") },
        )
        val objects = "{\"data\":" + "{\"a\":".repeat(100_000) + "1" + "}".repeat(100_000) + "}"
        for (parse in parsers) for (body in listOf(deep, objects)) {
            val failure = runCatching { parse(body) }.exceptionOrNull()
            assertTrue(failure is Exception, "got $failure")
        }
    }

    /** F5: a late 401 for a ticket another request already replaced keeps the fresh ticket; no extra password POST. */
    @Test fun lateRefusalKeepsTheFreshTicket() {
        val logins = AtomicInteger()
        val held = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val staleReads = AtomicInteger()
        server(answer = { head ->
            when {
                head.startsWith(login) -> logins.incrementAndGet().let { n -> 200 to """{"data":{"ticket":"t$n","CSRFPreventionToken":"c$n","username":"root@pam"}}""" }
                head.lineSequence().any { it.trimEnd() == "Cookie: PVEAuthCookie=t1" } -> {
                    if (staleReads.getAndIncrement() == 0) { held.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS) }
                    401 to "{}"
                }
                else -> 200 to """{"data":[]}"""
            }
        }).use { s ->
            password(s.address).use { c ->
                assertEquals("c1", c.tickets.get(c).csrf)
                val late = java.util.concurrent.atomic.AtomicReference<Any?>()
                val b = Thread { late.set(runCatching { ProxmoxTransport(1000, 15_000).direct(c, CLUSTER_TASKS_PATH) }) }.apply { isDaemon = true; start() }
                assertTrue(held.await(10, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals("""{"data":[]}""", ProxmoxTransport(1000, 5000).direct(c, CLUSTER_TASKS_PATH), "A: refused t1, signed in for t2, read")
                release.countDown(); b.join(15_000)
                assertEquals("""{"data":[]}""", (late.get() as Result<*>).getOrThrow(), "B: late 401 for t1, then read with t2")
                assertEquals("c2", c.tickets.get(c).csrf)
            }
            assertEquals(2, logins.get(), "the late 401 threw the fresh ticket away and signed in again")
        }
    }

    @Test fun clearDropsOnlyTheRefusedTicket() {
        val logins = AtomicInteger()
        server(answer = { logins.incrementAndGet().let { n -> 200 to """{"data":{"ticket":"t$n","CSRFPreventionToken":"c$n","username":"root@pam"}}""" } }).use { s ->
            password(s.address).use { c ->
                val first = c.tickets.get(c)
                c.tickets.clear(first)
                val second = c.tickets.get(c)
                assertNotSame(first, second)
                c.tickets.clear(first)
                assertSame(second, c.tickets.get(c))
                c.tickets.clear(second)
                assertNotSame(second, c.tickets.get(c))
            }
            assertEquals(3, logins.get())
        }
    }

    /**
     * F10: the diagnostic trust-all GET follows no redirect, reads a bounded amount, and waits out pveproxy's deliberate
     * 3-second delay on unauthenticated requests instead of timing out.
     */
    @Test fun diagnosticGetStaysPutReadsLittleAndWaitsOutTheUnauthenticatedDelay() {
        val mode = java.util.concurrent.atomic.AtomicReference("redirect")
        val elsewhere = AtomicInteger()
        val big = ByteArray(32 * 1024 * 1024) { 'x'.code.toByte() }
        val server = com.sun.net.httpserver.HttpsServer.create(loopback(0), 0)
        server.httpsConfigurator = com.sun.net.httpserver.HttpsConfigurator(AuditCerts.serverContext(store))
        server.createContext("/") { e ->
            try {
                if (e.requestURI.path != "/api2/json/version") { elsewhere.incrementAndGet(); e.sendResponseHeaders(200, 2); e.responseBody.use { it.write("{}".toByteArray()) }; return@createContext }
                when (mode.get()) {
                    "redirect" -> { e.responseHeaders.add("Location", "https://127.0.0.1:${server.address.port}/elsewhere"); e.sendResponseHeaders(302, -1); e.close() }
                    "big" -> { e.sendResponseHeaders(200, big.size.toLong()); e.responseBody.use { it.write(big) } }
                    else -> { Thread.sleep(3_500); val b = "{\"data\":null}".toByteArray(); e.sendResponseHeaders(401, b.size.toLong()); e.responseBody.use { it.write(b) } }
                }
            } catch (_: Exception) { e.close() }
        }
        server.start()
        try {
            val address = "https://127.0.0.1:${server.address.port}"
            val redirected = runBlocking { probeDiagnosticReachability(address) }
            assertEquals(302, redirected.httpStatusCode); assertEquals(0, elsewhere.get())
            mode.set("big")
            val large = runBlocking { probeDiagnosticReachability(address) }
            assertEquals(200, large.httpStatusCode); assertEquals(500, large.httpVersionBody?.length)
            mode.set("delayed")
            val delayed = runBlocking { probeDiagnosticReachability(address) }
            assertNull(delayed.httpError, "a healthy server's delayed 401 is not a timeout")
            assertEquals(401, delayed.httpStatusCode)
            assertEquals(6_000, DIAGNOSTIC_HTTP_READ_TIMEOUT_MS)
        } finally { server.stop(0) }
    }

    @Test fun tlsFloorIsTls13And12Only() {
        assertEquals(listOf("TLSv1.3", "TLSv1.2"), tlsFloor(arrayOf("SSLv3", "TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3")).toList())
        assertEquals(listOf("TLSv1.2"), tlsFloor(arrayOf("TLSv1.1", "TLSv1.2")).toList())
        requireTlsFloor("TLSv1.3"); requireTlsFloor("TLSv1.2")
        for (old in listOf("TLSv1.1", "TLSv1", "SSLv3", "DTLSv1.2", null)) assertFailsWith<javax.net.ssl.SSLHandshakeException> { requireTlsFloor(old) }
        assertEquals(listOf("TLSv1.3", "TLSv1.2"), tlsFloorParameters().protocols.toList())
        assertNull(tlsFloorParameters().cipherSuites, "cipher suites stay the JDK's")
    }

    /** Every socket the wrapped factory makes enables only the floor, whatever the inner factory enabled. */
    @Test fun floorFactoryEnablesOnlyTheFloor() {
        val old = SSLContext.getInstance("TLSv1.1").apply { init(null, null, null) }.socketFactory
        val inners = listOf(old, SSLContext.getDefault().socketFactory, pinnedSslContext(pin)!!.socketFactory)
        for (inner in inners) {
            val wrapped = TlsFloorSocketFactory(inner)
            wrapped.createSocket().use { s ->
                val enabled = (s as SSLSocket).enabledProtocols.toList()
                assertTrue(enabled.isNotEmpty() && enabled.all { it in TLS_FLOOR }, "enabled: $enabled")
            }
            assertContentEquals(inner.defaultCipherSuites, wrapped.defaultCipherSuites)
        }
        (old.createSocket() as SSLSocket).use { println("TRANSPORT inner TLSv1.1 context enabled=${it.enabledProtocols.toList()}") }
    }

    /** The floor's two protocols both work on every credentialed path: reads, mutations, the storage wire and the login. */
    @Test fun everyPathSpeaksBothFloorProtocols() {
        for (only in listOf("TLSv1.2", "TLSv1.3")) {
            OneShotServer(AuditCerts.serverContext(store), protocols = arrayOf(only), answer = { head ->
                200 to if (head.startsWith(login)) ticket else """{"data":"UPID:node-a:00000001:00000002:00000003:qmstart:112:root@pam:"}"""
            }).use { s ->
                token(s.address).use { c ->
                    ProxmoxTransport(1000, 5000).direct(c, "/api2/json/nodes/node-a/qemu/112/status/current")
                    ProxmoxTransport(1000, 5000).direct(c, "/api2/json/nodes/node-a/qemu/112/status/start", "{}".toByteArray())
                    ProxmoxTransport(1000, 5000).storageExchange(c, "/api2/json/nodes/node-a/storage/local/content/local%3Aiso%2Fa.iso")
                }
                password(s.address).use { c -> c.tickets.get(c) }
                assertEquals(List(4) { only }, s.negotiated.toList(), only)
                assertEquals(4, s.requests.size, only)
            }
        }
    }

    private class Path(val name: String, val method: String, val expected: KClass<out Throwable>, val message: String? = null, val call: (Connection) -> Any?)

    /**
     * Every mutation the app can send, run against a server that reads each non-GET request whole and closes without
     * answering. Each must reach the server exactly once and end in its own "outcome unknown" answer, never in a success.
     */
    @Test fun everyMutationPathIsSentOnceWhenTheAnswerIsLost() {
        val file = Files.createTempFile("ring-transport", ".iso")
        Files.write(file, ByteArray(4096) { 1 })
        try {
            val client = PveClient(1000, 1500)
            fun vm(c: Connection, status: String = "running") = Guest(c.remoteKey, 112, "fixture", "node-a", "VM", status)
            fun confirm(c: Connection, action: PowerAction, status: String = "stopped", snapshot: SnapshotRequest? = null) =
                PowerConfirmation(1, c.remoteKey, c.address, "Fixture", ResourceId(c.remoteKey, "", "VM", 112), "node-a", "fixture", "VM", action, status, false, snapshot = snapshot)
            val key = { c: Connection -> StorageKey(c.remoteKey, "node-a", "local") }
            val iso = StorageVolume("local:iso/a.iso", "iso", null, 1, null, null, null, null, null, false, null)
            val backup = iso.copy(volid = "local:backup/vzdump-qemu-112.vma.zst", content = "backup")
            val power = listOf(PowerAction.Start, PowerAction.Shutdown, PowerAction.HardStop, PowerAction.Reboot, PowerAction.Suspend, PowerAction.Resume, PowerAction.Reset).map { action ->
                Path("power ${action.name}", "POST", AmbiguousPowerResultException::class) { c -> client.submitPower(c, vm(c), action) }
            }
            val paths = power + listOf(
                Path("snapshot create", "POST", AmbiguousPowerResultException::class) { c -> client.submitConfirmed(c, confirm(c, PowerAction.SnapshotCreate, snapshot = SnapshotRequest("before"))) },
                Path("snapshot rollback", "POST", AmbiguousPowerResultException::class) { c -> client.submitConfirmed(c, confirm(c, PowerAction.SnapshotRollback, snapshot = SnapshotRequest("before"))) },
                Path("snapshot delete", "DELETE", AmbiguousPowerResultException::class) { c -> client.submitConfirmed(c, confirm(c, PowerAction.SnapshotDelete, snapshot = SnapshotRequest("before"))) },
                Path("create VM", "POST", AmbiguousPowerResultException::class) { c -> client.createGuest(c, CreateRequest(NewGuestKind.Vm, "node-a", 140, "fixture", listOf(CreateParam("vmid", CreateValue.Int(140)))), CreateSecrets(null)) },
                Path("create container", "POST", AmbiguousPowerResultException::class) { c -> client.createGuest(c, CreateRequest(NewGuestKind.Container, "node-a", 141, "fixture", listOf(CreateParam("vmid", CreateValue.Int(141)))), CreateSecrets(SecretChars.copyOf("fixture-secret"))) },
                Path("SPICE ticket", "POST", IllegalStateException::class, SPICE_REQUEST_FAILED) { c -> client.spiceTicket(c, vm(c)) },
                Path("VNC console ticket", "POST", IllegalStateException::class, "The server did not open a console channel.") { c -> client.consoleSession(c, vm(c)) },
                Path("console still ticket", "POST", IllegalStateException::class) { c -> client.consoleStill(c, vm(c)) },
                Path("node shell ticket", "POST", IllegalStateException::class, "The server did not open a terminal channel.") { c -> client.terminalSession(c, TerminalTarget("node-a")) },
                Path("guest agent exec", "POST", IllegalStateException::class, GUEST_BT_AGENT) { c -> client.guestBluetoothScan(c, vm(c), 3) { } },
                Path("storage template download", "POST", StorageUnclear::class) { c -> client.submitStorage(c, key(c), StorageRequest.TemplateDownload(AplTemplate("test_1.tar.zst", "test", "1", "lxc"))) },
                Path("storage URL download", "POST", StorageUnclear::class) { c -> client.submitStorage(c, key(c), StorageRequest.UrlDownload("https://example.test/a.iso", "iso", "a.iso")) },
                Path("storage upload", "POST", StorageUnclear::class) { c -> client.submitStorage(c, key(c), StorageRequest.Upload(UploadSource.capture(file), "iso", "a.iso")) },
                Path("storage remove volume", "DELETE", StorageUnclear::class) { c -> client.submitStorage(c, key(c), StorageRequest.RemoveVolume(iso)) },
                Path("storage edit notes", "PUT", StorageUnclear::class) { c -> client.submitStorage(c, key(c), StorageRequest.EditNotes(backup, "note")) },
                Path("storage protection", "PUT", StorageUnclear::class) { c -> client.submitStorage(c, key(c), StorageRequest.SetProtection(backup, true)) },
                Path("scheduled power", "POST", ScheduleUncertainException::class) { c -> runBlocking { LiveScheduleGateway(1000, 1500).dispatch(c, "node-a", "VM", 112, ScheduleAction.Start) } },
            )
            for (path in paths) server(drop = { !it.startsWith("GET ") }, answer = { 200 to """{"data":{"vga":"qxl"}}""" }).use { s ->
                val outcome = token(s.address).use { c -> runCatching { path.call(c) } }
                val failure = outcome.exceptionOrNull()
                assertNotNull(failure, "${path.name} reported success for a lost answer")
                assertTrue(path.expected.isInstance(failure), "${path.name}: got $failure")
                path.message?.let { assertEquals(it, failure.message, path.name) }
                val sent = s.requests.filter { !it.startsWith("GET ") }
                assertEquals(1, sent.size, "${path.name} reached the server ${sent.size} times: ${s.requests}")
                assertTrue(sent.single().startsWith(path.method + " "), "${path.name}: ${sent.single()}")
            }
        } finally { Files.deleteIfExists(file) }
    }
}
