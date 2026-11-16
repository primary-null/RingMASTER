package local.ringleader.core

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Network audit 2026-11-16: [storageWire], the hand-written HTTP framing over one TLS socket that carries uploads and
 * every storage mutation. Local TLS servers only.
 */
class NetAuditStorageWireTest {
    private val token = mapOf("Authorization" to "PVEAPIToken=root@pam!audit=SECRET-VALUE")

    private fun get(origin: String, pin: String, onBoundary: () -> Unit = {}, readMs: Int = 3000): Pair<Int, String> =
        storageWire(serverOrigin(origin), pin, "/api2/json/nodes/n1/storage/local/content", "GET", token, null, null, null,
            3000, readMs, UploadCancel(), beforeHeaders = onBoundary) { code, body -> code to body.readNBytes(1 shl 20).toString(Charsets.UTF_8) }

    // ---- TLS decision happens before the first request byte ----

    @Test fun aWrongPinEndsTheHandshakeBeforeTheRequestBoundaryAndNoByteIsSent() {
        RawHttpServer(NetAuditCerts.loopback.server).use { server ->
            val boundary = AtomicInteger()
            assertFailsWith<SSLException> { get("https://127.0.0.1:${server.port}", "00".repeat(32), { boundary.incrementAndGet() }) }
            assertEquals(0, boundary.get(), "beforeHeaders (the point after which a mutation is 'sent') must not run")
            assertTrue(server.seen.isEmpty())
            assertEquals(0, server.bytesReadBeforeHead.get(), "the server read no application byte")
        }
    }

    @Test fun theRightPinSendsHostConnectionCloseAndTheCredential() {
        RawHttpServer(NetAuditCerts.loopback.server).use { server ->
            assertEquals(200, get("https://127.0.0.1:${server.port}", NetAuditCerts.loopback.pin).first)
            val seen = server.seen.single()
            assertEquals("GET /api2/json/nodes/n1/storage/local/content HTTP/1.1", seen.requestLine)
            assertEquals("127.0.0.1:${server.port}", seen.header("Host"))
            assertEquals("close", seen.header("Connection"))
            assertEquals("PVEAPIToken=root@pam!audit=SECRET-VALUE", seen.header("Authorization"))
        }
    }

    @Test fun withoutAPinTheSocketChecksTheCertificateAndTheHostName() {
        RawHttpServer(NetAuditCerts.loopback.server).use { server ->
            assertFailsWith<SSLException> { get("https://127.0.0.1:${server.port}", "") }              // untrusted by the JVM's own store
            withTrustedCertificate(NetAuditCerts.loopback) { assertEquals(200, get("https://127.0.0.1:${server.port}", "").first) }
        }
        RawHttpServer(NetAuditCerts.otherName.server).use { server ->
            withTrustedCertificate(NetAuditCerts.otherName) {
                assertFailsWith<SSLException> { get("https://127.0.0.1:${server.port}", "") }          // trusted, but named for another host
            }
            assertTrue(server.seen.isEmpty())
        }
    }

    @Test fun ipv6LiteralHostIsFramedAndVerifiedOnBothPaths() {
        assumeIpv6()
        RawHttpServer(NetAuditCerts.loopback.server, "::1").use { server ->
            assertEquals(200, get("https://[::1]:${server.port}", NetAuditCerts.loopback.pin).first)
            withTrustedCertificate(NetAuditCerts.loopback) { assertEquals(200, get("https://[::1]:${server.port}", "").first) }
            assertEquals(listOf("[::1]:${server.port}", "[::1]:${server.port}"), server.seen.map { it.header("Host") })
        }
    }

    // ---- response parsing ----

    private fun answer(reply: String, block: (Int, String) -> Unit) {
        RawHttpServer(NetAuditCerts.loopback.server, reply = { reply }).use { server ->
            val (code, body) = get("https://127.0.0.1:${server.port}", NetAuditCerts.loopback.pin)
            block(code, body)
        }
    }

    @Test fun chunkedAndInterimResponsesAreParsed() {
        answer("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n5\r\nhello\r\n0\r\n\r\n") { code, body -> assertEquals(200, code); assertEquals("hello", body) }
        answer("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}") { code, body -> assertEquals(200, code); assertEquals("{}", body) }
        answer("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nuntil-close") { code, body -> assertEquals(200, code); assertEquals("until-close", body) }
        answer("HTTP/1.1 302 Found\r\nLocation: https://other.invalid/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n") { code, _ -> assertEquals(302, code) }
    }

    @Test fun tooManyInterimResponsesAndOversizedHeadersAreRefused() {
        val many = "HTTP/1.1 100 Continue\r\n\r\n".repeat(9) + "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        RawHttpServer(NetAuditCerts.loopback.server, reply = { many }).use { server ->
            assertFailsWith<IllegalStateException> { get("https://127.0.0.1:${server.port}", NetAuditCerts.loopback.pin) }
        }
        val longHeader = "HTTP/1.1 200 OK\r\nX-Long: ${"a".repeat(9000)}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        RawHttpServer(NetAuditCerts.loopback.server, reply = { longHeader }).use { server ->
            assertFailsWith<java.io.IOException> { get("https://127.0.0.1:${server.port}", NetAuditCerts.loopback.pin) }
        }
    }

    // ---- timeouts during a long upload ----

    /** A peer that stops reading mid-body. Java's soTimeout never applies to writes, so only Cancel ends the upload. */
    @Test fun aPeerThatStopsReadingBlocksTheUploadForeverUnlessItIsCancelled() {
        val release = CountDownLatch(1)
        val total = 256L * 1024 * 1024
        val written = AtomicLong()
        RawHttpServer(NetAuditCerts.loopback.server, readBody = { release.await(60, TimeUnit.SECONDS); 0L }).use { server ->
            val cancel = UploadCancel()
            val outcome = AtomicReference<Throwable?>()
            val done = CountDownLatch(1)
            val write: (OutputStream) -> Unit = { out ->
                val chunk = ByteArray(65536); var left = total
                while (left > 0) { val n = minOf(chunk.size.toLong(), left).toInt(); out.write(chunk, 0, n); written.addAndGet(n.toLong()); left -= n }
            }
            Thread {
                try {
                    storageWire(serverOrigin("https://127.0.0.1:${server.port}"), NetAuditCerts.loopback.pin, "/api2/json/nodes/n1/storage/local/upload", "POST", token,
                        "application/octet-stream", total, write, 3000, 1500 /* read timeout */, cancel, beforeHeaders = {}) { code, _ -> code }
                } catch (e: Throwable) { outcome.set(e) } finally { done.countDown() }
            }.apply { isDaemon = true; start() }
            Thread.sleep(2500)
            val frozenAt = written.get()
            assertTrue(frozenAt in 1 until total, "the sender is blocked mid-body after $frozenAt bytes")
            assertFalse(done.await(6, TimeUnit.SECONDS), "8.5 s after the stall, with a 1.5 s read timeout, the upload has neither failed nor finished")
            assertEquals(frozenAt, written.get(), "no byte moved while the peer was not reading")
            cancel.cancel()
            assertTrue(done.await(5, TimeUnit.SECONDS), "cancel closes the socket and unblocks the write")
            assertTrue(outcome.get() != null)
            println("NETAUDIT upload stalled after $frozenAt bytes; no timeout fired in 8.5 s; cancel unblocked it")
            release.countDown()
        }
    }

    // ---- proxy ----

    private class RecordingSelector : ProxySelector() {
        val asked = CopyOnWriteArrayList<URI>()
        override fun select(uri: URI): List<Proxy> { asked += uri; return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9))) }
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) = Unit
    }

    /**
     * With a proxy configured (java.net.ProxySelector), the REST client (HttpsURLConnection) and the WebSocket HttpClient go
     * to the proxy; the raw storage socket connects directly. A VPN or corporate-proxy setup would split one session across two routes.
     */
    @Test fun anHttpProxyIsHonouredByRestAndWebsocketsButNotByTheStorageSocket() {
        val cert = NetAuditCerts.loopback
        val old = ProxySelector.getDefault()
        val selector = RecordingSelector()
        ProxySelector.setDefault(selector)
        try {
            RawHttpServer(cert.server).use { rest ->
                assertFailsWith<java.io.IOException> { PveClient().overview("https://127.0.0.1:${rest.port}", "root@pam!audit", "SECRET-VALUE", cert.pin) }
                assertEquals(0, rest.accepted.get(), "REST asked the selector and went to the proxy (127.0.0.1:9), not to the server")
            }
            MiniWsServer(cert.server) { it.close() }.use { ws ->
                assertFailsWith<Exception> { openVncSession(Connection("https://127.0.0.1:${ws.port}", "root@pam!audit", "SECRET-VALUE", cert.pin), "/api2/json/nodes/n1/qemu/100") { """{"data":{"port":5900,"ticket":"T","password":"pw"}}""" } }
                assertEquals(0, ws.accepted.get(), "the WebSocket client also went to the proxy")
            }
            RawHttpServer(cert.server).use { storage ->
                assertEquals(200, get("https://127.0.0.1:${storage.port}", cert.pin).first)
                assertEquals(1, storage.accepted.get(), "the storage socket ignored the HTTP proxy and connected straight to the server")
                println("NETAUDIT selector was asked for: ${selector.asked.map { it.scheme }.distinct()} (socket:// is only used for SOCKS)")
            }
        } finally { ProxySelector.setDefault(old) }
    }
}
