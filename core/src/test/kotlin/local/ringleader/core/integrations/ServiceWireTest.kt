package local.ringleader.core.integrations

import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import local.ringleader.core.AuditCerts
import local.ringleader.core.UploadCancel
import kotlin.test.*

/** The one request helper for linked services, against scripted loopback servers (plain and TLS). */
class ServiceWireTest {
    private val wire = ServiceWire("RingMASTER/0.0-test")
    private val ok = """{"ok":true}"""
    private fun plain(server: ScriptedServer) = ServiceOrigin.of("http", "127.0.0.1", server.port)
    private fun get(path: String = "/api/x", credential: Boolean = false, headers: List<Pair<String, String>> = emptyList()) =
        WireRequest(WireMethod.GET, path, headers, carriesCredential = credential)
    private val loopbackAck = { o: ServiceOrigin -> PlainHttpAck.forOrigin(o, Instant.EPOCH, "test key", "reads only") }
    private fun failure(block: () -> Unit): WireFailure = assertFailsWith<ServiceWireException> { block() }.failure

    @Test fun oneGetOnOneConnectionWithTheFixedHeaders() = ScriptedServer { Reply.json(200, ok) }.use { s ->
        val answer = wire.exchange(plain(s), WireTrust.PlainHttp(null), get("/api/stats/summary?count=8"))
        assertEquals(200, answer.status); assertEquals(ok, answer.text)
        assertEquals(UnencryptedMark.Unencrypted, answer.unencrypted)
        assertEquals(1, s.connections.get())
        val r = s.received.single()
        assertEquals("GET /api/stats/summary?count=8", r.line)
        assertEquals("127.0.0.1:${s.port}", r.header("Host"))
        assertEquals("RingMASTER/0.0-test", r.header("User-Agent"))
        assertEquals("identity", r.header("Accept-Encoding"))
        assertEquals("close", r.header("Connection"))
        assertNull(r.header("Cookie")); assertNull(r.header("Authorization"))
        val names = r.head.lineSequence().drop(1).filter { it.isNotBlank() }.map { it.substringBefore(':').lowercase() }.toSet()
        assertEquals(setOf("host", "user-agent", "accept", "accept-encoding", "connection"), names, "nothing identifying beyond these")
    }

    @Test fun aDroppedAnswerIsNeverResentForGetOrLoginPost() {
        for (request in listOf(get(credential = false), WireRequest(WireMethod.POST, "/api/auth", body = """{"password":"x"}""".toByteArray(), carriesCredential = true))) {
            ScriptedServer { Reply.Drop }.use { s ->
                val origin = plain(s)
                assertEquals(WireFailure.NoAnswer, failure { wire.exchange(origin, WireTrust.PlainHttp(loopbackAck(origin)), request) })
                assertEquals(1, s.received.size, "sent once: $request"); assertEquals(1, s.connections.get(), "one connection: $request")
            }
        }
    }

    @Test fun theLoginPostCarriesItsDeclaredBodyAndLength() = ScriptedServer { Reply.json(200, ok) }.use { s ->
        val origin = plain(s)
        wire.exchange(origin, WireTrust.PlainHttp(loopbackAck(origin)), WireRequest(WireMethod.POST, "/api/auth", body = """{"password":"p"}""".toByteArray(), carriesCredential = true))
        val r = s.received.single()
        assertEquals("POST /api/auth", r.line); assertEquals("""{"password":"p"}""", String(r.body)); assertEquals("16", r.header("Content-Length"))
    }

    @Test fun redirectsAreReportedNeverFollowed() {
        ScriptedServer { Reply.text(302, "", extra = "Location: /admin/login\r\n") }.use { s ->
            val answer = wire.exchange(plain(s), WireTrust.PlainHttp(null), get("/admin/"))
            assertEquals(302, answer.status); assertEquals(WireRedirect(true, "/admin/login"), answer.redirect); assertEquals(1, s.received.size)
        }
        ScriptedServer { Reply.text(301, "", extra = "Location: http://elsewhere.example.test/x\r\n") }.use { s ->
            assertEquals(WireRedirect(false, null), wire.exchange(plain(s), WireTrust.PlainHttp(null), get()).redirect); assertEquals(1, s.connections.get())
        }
        ScriptedServer { r -> Reply.text(307, "", extra = "Location: http://127.0.0.1:${r.header("Host")!!.substringAfter(':')}/b?x=1\r\n") }.use { s ->
            assertEquals(WireRedirect(true, "/b?x=1"), wire.exchange(plain(s), WireTrust.PlainHttp(null), get()).redirect)
        }
    }

    @Test fun anAnswerOverTheCapIsRefusedAndTheSocketClosedEarly() = ScriptedServer { Reply.Huge(5L * 1024 * 1024) }.use { s ->
        val started = System.nanoTime()
        assertEquals(WireFailure.TooLarge, failure { wire.exchange(plain(s), WireTrust.PlainHttp(null), get(), WireLimits(maxBytes = 1024 * 1024)) })
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    @Test fun theCapCannotBeRaisedAboveTheDefault() {
        assertFailsWith<IllegalArgumentException> { WireLimits(maxBytes = SERVICE_ANSWER_DEFAULT + 1) }
        assertEquals(2 * 1024 * 1024, WireLimits().maxBytes)
    }

    @Test fun hostileNestingIsRefusedBeforeParsing() = ScriptedServer { Reply.json(200, "[".repeat(100_000) + "]".repeat(100_000)) }.use { s ->
        assertEquals(WireFailure.TooDeep, failure { wire.exchange(plain(s), WireTrust.PlainHttp(null), get()) })
    }

    @Test fun aCompressedAnswerIsRefused() = ScriptedServer { Reply.json(200, ok, "Content-Encoding: gzip\r\n") }.use { s ->
        assertEquals(WireFailure.EncodingRefused, failure { wire.exchange(plain(s), WireTrust.PlainHttp(null), get()) })
    }

    @Test fun headersAndPathsOutsideTheRulesNeverOpenAConnection() = ScriptedServer { Reply.json(200, ok) }.use { s ->
        val bad = listOf(
            get(headers = listOf("Cookie" to "a=b")), get(headers = listOf("Host" to "x")), get(headers = listOf("Proxy-Foo" to "1")),
            get(headers = listOf("X Bad" to "1")), get(headers = listOf("X-Api-Key" to "a\r\nInjected: 1")), get(headers = listOf("Transfer-Encoding" to "chunked")),
            get("/../etc"), get("//elsewhere/x"), get("/a#b"), get("relative"), get("/a b"), get("/caf\u00e9"),
            WireRequest(WireMethod.GET, "/x", body = ByteArray(1)),
        )
        for (request in bad) assertEquals(WireFailure.RequestRefused, failure { wire.exchange(plain(s), WireTrust.PlainHttp(null), request) }, request.toString())
        assertEquals(0, s.connections.get())
        assertEquals(200, wire.exchange(plain(s), WireTrust.PlainHttp(null), get(headers = listOf("X-FTL-SID" to "sid"))).status)
    }

    @Test fun trustMustMatchTheScheme() {
        val https = ServiceOrigin.of("https", "127.0.0.1", 1)
        assertEquals(WireFailure.RequestRefused, failure { wire.exchange(https, WireTrust.PlainHttp(null), get()) })
        assertEquals(WireFailure.RequestRefused, failure { wire.exchange(ServiceOrigin.of("http", "127.0.0.1", 1), WireTrust.NormalTls, get()) })
    }

    private val store = AuditCerts.generate("svc")
    private val pin = AuditCerts.pin(AuditCerts.cert(store))
    private val otherPin = AuditCerts.pin(AuditCerts.cert(AuditCerts.generate("svc-other")))

    @Test fun aPinnedCertificateIsReadOverTls() = ScriptedServer(AuditCerts.serverContext(store)) { Reply.json(200, ok) }.use { s ->
        val answer = wire.exchange(ServiceOrigin.of("https", "127.0.0.1", s.port), WireTrust.Pinned(pin), get(credential = true, headers = listOf("X-Api-Key" to "k")))
        assertEquals(200, answer.status); assertNull(answer.unencrypted)
    }

    @Test fun aMismatchedPinSendsZeroRequestBytes() = ScriptedServer(AuditCerts.serverContext(store)) { Reply.json(200, ok) }.use { s ->
        assertEquals(WireFailure.CertificateChanged, failure {
            wire.exchange(ServiceOrigin.of("https", "127.0.0.1", s.port), WireTrust.Pinned(otherPin), get(credential = true, headers = listOf("X-Api-Key" to "secret-key")))
        })
        assertEquals(0, s.requestBytes.get(), "not one decrypted request byte"); assertTrue(s.received.isEmpty())
    }

    @Test fun normalTlsRefusesASelfSignedCertificateBeforeAnyByte() = ScriptedServer(AuditCerts.serverContext(store)) { Reply.json(200, ok) }.use { s ->
        assertEquals(WireFailure.CertificateUntrusted, failure { wire.exchange(ServiceOrigin.of("https", "127.0.0.1", s.port), WireTrust.NormalTls, get(credential = true)) })
        assertEquals(0, s.requestBytes.get())
    }

    @Test fun tlsToAPlainHttpPortIsATlsFailure() = ScriptedServer { Reply.json(200, ok) }.use { s ->
        assertEquals(WireFailure.TlsFailed, failure { wire.exchange(ServiceOrigin.of("https", "127.0.0.1", s.port), WireTrust.Pinned(pin), get(), WireLimits(readMs = 500)) })
        assertTrue(s.received.isEmpty())
    }

    @Test fun aCredentialOverPlainHttpNeedsTheAcknowledgementForThatEndpoint() = ScriptedServer { Reply.json(200, ok) }.use { s ->
        val origin = plain(s)
        assertEquals(WireFailure.PlainHttpNeedsAcknowledgement, failure { wire.exchange(origin, WireTrust.PlainHttp(null), get(credential = true)) })
        val other = PlainHttpAck.forOrigin(ServiceOrigin.of("http", "127.0.0.1", s.port + 1), Instant.EPOCH, "k", "p")
        assertEquals(WireFailure.PlainHttpNeedsAcknowledgement, failure { wire.exchange(origin, WireTrust.PlainHttp(other), get(credential = true)) })
        assertEquals(0, s.connections.get(), "nothing was sent before the acknowledgement")
        assertEquals(200, wire.exchange(origin, WireTrust.PlainHttp(loopbackAck(origin)), get(credential = true)).status)
    }

    @Test fun aNameThatResolvesToAPublicAddressIsRefusedAtEveryConnect() = ScriptedServer { Reply.json(200, ok) }.use { s ->
        // A rebinding resolver: private on the first lookup, public on the second. Each connect is judged on its own lookup.
        val lookups = AtomicInteger()
        val rebinding = ServiceWire("RingMASTER/0.0-test") { _, _ ->
            if (lookups.getAndIncrement() == 0) InetAddress.getByName("127.0.0.1") else InetAddress.getByName("203.0.113.9")
        }
        val origin = ServiceOrigin.of("http", "svc.example.test", s.port)
        val ack = loopbackAck(origin)
        assertEquals(200, rebinding.exchange(origin, WireTrust.PlainHttp(ack), get(credential = true)).status)
        val second = assertFailsWith<ServiceWireException> { rebinding.exchange(origin, WireTrust.PlainHttp(ack), get(credential = true)) }
        assertEquals(WireFailure.PlainHttpRefused, second.failure); assertEquals(PlainHttpRule.PUBLIC_REFUSED, second.message)
        assertEquals(1, s.connections.get())
    }

    @Test fun aJvmProxySettingIsIgnored() = ScriptedServer { Reply.json(200, ok) }.use { s ->
        val keys = listOf("socksProxyHost", "socksProxyPort", "http.proxyHost", "http.proxyPort")
        val saved = keys.associateWith { System.getProperty(it) }
        try {
            System.setProperty("socksProxyHost", "127.0.0.1"); System.setProperty("socksProxyPort", "9")
            System.setProperty("http.proxyHost", "127.0.0.1"); System.setProperty("http.proxyPort", "9")
            assertEquals(200, wire.exchange(plain(s), WireTrust.PlainHttp(null), get()).status)
        } finally { saved.forEach { (k, v) -> if (v == null) System.clearProperty(k) else System.setProperty(k, v) } }
    }

    @Test fun aHangingLookupFailsAtItsDeadline() {
        val resolver = BoundedResolver(maxLookups = 2) { Thread.sleep(30_000); InetAddress.getLoopbackAddress() }
        val started = System.nanoTime()
        assertFailsWith<UnknownHostException> { resolver.resolve("slow.example.test", 300) }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
        val hanging = ServiceWire("RingMASTER/0.0-test", resolver)
        assertEquals(WireFailure.NameNotResolved, failure { hanging.exchange(ServiceOrigin.of("http", "slow2.example.test", 80), WireTrust.PlainHttp(null), get(), WireLimits(lookupMs = 200)) })
    }

    @Test fun literalsAreNeverLookedUp() {
        val resolver = BoundedResolver { error("no lookup for a literal") }
        assertEquals(InetAddress.getByName("192.0.2.5"), resolver.resolve("192.0.2.5", 10))
        assertTrue(resolver.resolve("fd00::5", 10) is Inet6Address)
        assertFailsWith<UnknownHostException> { resolver.resolve("bad host", 10) }
    }

    @Test fun anUnusableAddressIsNeverConnected() {
        val zero = ServiceWire("RingMASTER/0.0-test") { _, _ -> InetAddress.getByName("0.0.0.0") }
        assertEquals(WireFailure.AddressRefused, failure { zero.exchange(ServiceOrigin.of("http", "z.example.test", 80), WireTrust.PlainHttp(null), get()) })
        assertEquals(WireFailure.AddressRefused, failure { zero.exchange(ServiceOrigin.of("https", "z.example.test", 443), WireTrust.NormalTls, get()) })
    }

    @Test fun aSilentServerTimesOutAtTheReadDeadline() = ScriptedServer { Reply.Hang }.use { s ->
        assertEquals(WireFailure.Timeout, failure { wire.exchange(plain(s), WireTrust.PlainHttp(null), get(), WireLimits(readMs = 300, totalMs = 5_000)) })
        assertEquals(1, s.received.size)
    }

    @Test fun aTricklingServerHitsTheWholeExchangeDeadline() =
        ScriptedServer { Reply.Trickle(Reply.json(200, ok).bytes, 60) }.use { s ->
            val started = System.nanoTime()
            assertEquals(WireFailure.Timeout, failure { wire.exchange(plain(s), WireTrust.PlainHttp(null), get(), WireLimits(readMs = 1_000, totalMs = 700)) })
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
        }

    @Test fun nothingListeningIsConnectionRefused() {
        val port = java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        assertEquals(WireFailure.ConnectionRefused, failure { wire.exchange(ServiceOrigin.of("http", "127.0.0.1", port), WireTrust.PlainHttp(null), get()) })
    }

    @Test fun cancellingClosesTheSocket() = ScriptedServer { Reply.Hang }.use { s ->
        val cancel = UploadCancel()
        Thread { Thread.sleep(200); cancel.cancel() }.start()
        assertEquals(WireFailure.Cancelled, failure { wire.exchange(plain(s), WireTrust.PlainHttp(null), get(), WireLimits(readMs = 5_000), cancel) })
    }

    @Test fun ipv6LiteralsGetBracketsInTheHostHeader() {
        val loop6 = runCatching { InetAddress.getByName("::1") }.getOrNull() ?: return
        val server = runCatching { ScriptedServer(bind = loop6) { Reply.json(200, ok) } }.getOrNull() ?: return // no IPv6 loopback here
        server.use { s ->
            val answer = wire.exchange(ServiceOrigin.parseOrThrow("http://[::1]:${s.port}"), WireTrust.PlainHttp(null), get())
            assertEquals(200, answer.status); assertEquals("[::1]:${s.port}", s.received.single().header("Host"))
        }
        assertEquals("[fd00::5]", ServiceOrigin.parseOrThrow("http://[fd00::5]").authority)
    }

    @Test fun theUserAgentNamesOnlyTheApp() {
        assertFailsWith<IllegalArgumentException> { ServiceWire("Mozilla/5.0") }
        assertFailsWith<IllegalArgumentException> { ServiceWire("RingMASTER/1.0 (host=pc-of-someone)") }
        ServiceWire("RingMASTER/0.25.10")
    }

    @Test fun retryAfterIsReadAsSecondsAndCapped() = ScriptedServer { Reply.json(429, "{}", "Retry-After: 99999\r\n") }.use { s ->
        assertEquals(3600L, wire.exchange(plain(s), WireTrust.PlainHttp(null), get()).retryAfterSeconds)
    }
}
