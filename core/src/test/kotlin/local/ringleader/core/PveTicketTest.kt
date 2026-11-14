package local.ringleader.core
import com.sun.net.httpserver.*
import java.net.*
import java.nio.file.*
import java.security.*
import javax.net.ssl.*
import java.time.Instant
import kotlin.test.*

class PveTicketTest {
    private fun fixture(handler: (HttpExchange) -> Pair<Int, String>, block: (Connection) -> Unit) {
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

        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(context)
        server.createContext("/") { e ->
            val (status, response) = handler(e)
            val bytes = response.toByteArray()
            e.sendResponseHeaders(status, bytes.size.toLong())
            e.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { Connection("https://127.0.0.1:${server.address.port}", "root@pam", "pass & word", pin).use(block) }
        finally { server.stop(0); Files.deleteIfExists(key); Files.deleteIfExists(dir) }
    }
    private val success = """{"data":{"ticket":"ticket+value","CSRFPreventionToken":"csrf","username":"root@pam"}}"""
    private val challenge = """{"data":{"ticket":"challenge","NeedTFA":1}}"""
    private fun fields(e: HttpExchange) = e.requestBody.bufferedReader().readText().split('&').associate {
        val parts = it.split('=', limit = 2)
        URLDecoder.decode(parts[0], Charsets.UTF_8) to URLDecoder.decode(parts[1], Charsets.UTF_8)
    }
    @Test fun cookieCsrfAndTokenHeaders() {
        val requests = mutableListOf<Pair<String?, String?>>()
        fixture({ e ->
            if (e.requestURI.path.endsWith("/ticket")) {
                assertEquals("pass & word", fields(e)["password"]); 200 to success
            } else {
                requests += e.requestHeaders.getFirst("Cookie") to e.requestHeaders.getFirst("Authorization")
                if (e.requestMethod == "POST") assertEquals("csrf", e.requestHeaders.getFirst("CSRFPreventionToken"))
                200 to "{}"
            }
        }) { c ->
            val transport = ProxmoxTransport()
            transport.direct(c, "/api2/json/test")
            transport.direct(c, "/api2/json/test", byteArrayOf())
            Connection(c.address, "root@pam!token", "secret", c.fingerprint).use { transport.direct(it, "/api2/json/test") }
        }
        assertEquals(listOf<Pair<String?, String?>>("PVEAuthCookie=ticket%2Bvalue" to null, "PVEAuthCookie=ticket%2Bvalue" to null,
            null to "PVEAPIToken=root@pam!token=secret"), requests)
    }
    @Test fun twoFactorRequired() = fixture({ 200 to challenge }) { c ->
        assertFailsWith<TwoFactorRequiredException> { ProxmoxTransport().direct(c, "/api2/json/test") }
    }
    @Test fun twoFactorPrefixesAndOneUse() {
        for ((code, expected) in listOf("123456" to "totp:123456", "abc-def" to "recovery:abc-def", "yubico:abc" to "yubico:abc")) {
            val bodies = mutableListOf<Map<String, String>>()
            fixture({ e -> bodies += fields(e); 200 to if (bodies.size == 1) challenge else success }) { c ->
                Connection(c.address, c.tokenId, "password", c.fingerprint, otp = code).use { auth ->
                    auth.tickets.get(auth)
                    assertEquals("challenge", bodies[1]["tfa-challenge"])
                    assertEquals(expected, bodies[1]["password"])
                    assertNull(auth.takeOtp())
                }
            }
        }
    }
    @Test fun wrongPassword() = fixture({ 401 to "{}" }) { c ->
        assertEquals("The server refused this user name or password.", assertFailsWith<LoginRefusedException> { c.tickets.get(c) }.message)
    }
    @Test fun retryExactlyOnce() {
        var logins = 0; var reads = 0
        fixture({ e -> if (e.requestURI.path.endsWith("/ticket")) { logins++; 200 to success }
            else { reads++; 401 to "{}" } }) { c ->
            assertFailsWith<SessionExpiredException> { ProxmoxTransport().direct(c, "/api2/json/test") }
        }
        assertEquals(2, logins); assertEquals(2, reads)
    }
    @Test fun sentMutationIsNotReplayedAfter401() {
        for (method in listOf("POST", "DELETE")) {
            var logins = 0; val calls = mutableListOf<String>()
            fixture({ e -> if (e.requestURI.path.endsWith("/ticket")) { logins++; 200 to success }
                else { e.requestBody.readAllBytes(); calls += e.requestMethod; 401 to "{}" } }) { c ->
                assertFailsWith<SessionExpiredException> {
                    ProxmoxTransport().direct(c, "/api2/json/test", "a=1".toByteArray(), method = method)
                }
            }
            assertEquals(listOf(method), calls, method); assertEquals(1, logins, method)
        }
    }
    @Test fun renewalUsesTicket() {
        var now = Instant.parse("2026-11-09T00:00:00Z")
        val passwords = mutableListOf<String?>()
        fixture({ e -> passwords += fields(e)["password"]; 200 to success }) { c ->
            val store = TicketStore { now }
            store.get(c); now = now.plusSeconds(61 * 60); store.get(c)
        }
        assertEquals(listOf<String?>("pass & word", "ticket+value"), passwords)
    }
    @Test fun wrongCodeIsDistinct() {
        var calls = 0
        fixture({ calls++; if (calls == 1) 200 to challenge else 401 to "{}" }) { c ->
            Connection(c.address, c.tokenId, "password", c.fingerprint, otp = "123456").use { auth ->
                assertEquals("The server refused the two-factor code.", assertFailsWith<LoginRefusedException> { auth.tickets.get(auth) }.message)
            }
        }
    }
    @Test fun failedRenewalFallsBackToFullLogin() {
        var now = Instant.parse("2026-11-09T00:00:00Z")
        val passwords = mutableListOf<String?>()
        fixture({ e ->
            val password = fields(e)["password"]; passwords += password
            if (password == "ticket+value") 401 to "{}" else 200 to success
        }) { c ->
            val store = TicketStore { now }; store.get(c)
            now = now.plusSeconds(61 * 60); store.get(c)
            now = now.plusSeconds(116 * 60); store.get(c)
        }
        assertEquals(listOf<String?>("pass & word", "ticket+value", "pass & word", "pass & word"), passwords)
    }
    /** A handler that holds the first login on the wire until [release] opens. */
    private class HeldLogin {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val logins = java.util.concurrent.atomic.AtomicInteger()
        fun handle(e: HttpExchange): Pair<Int, String> {
            e.requestBody.readAllBytes()
            logins.incrementAndGet(); entered.countDown()
            check(release.await(15, java.util.concurrent.TimeUnit.SECONDS))
            return 200 to """{"data":{"ticket":"ticket+value","CSRFPreventionToken":"csrf","username":"root@pam"}}"""
        }
    }
    private fun background(action: () -> Any?): Pair<Thread, java.util.concurrent.atomic.AtomicReference<Any?>> {
        val outcome = java.util.concurrent.atomic.AtomicReference<Any?>()
        return Thread { outcome.set(try { action() } catch (t: Throwable) { t }) }.apply { isDaemon = true; start() } to outcome
    }
    @Test fun concurrentLoginsShareOneRequest() {
        val held = HeldLogin()
        fixture(held::handle) { c ->
            val (first, a) = background { c.tickets.get(c) }
            assertTrue(held.entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val (second, b) = background { c.tickets.get(c) }
            Thread.sleep(300)
            held.release.countDown()
            first.join(10_000); second.join(10_000)
            assertIs<PveTicket>(a.get()); assertSame(a.get(), b.get())
        }
        assertEquals(1, held.logins.get())
    }
    @Test fun closeDoesNotWaitOnALoginInFlight() {
        val held = HeldLogin()
        try {
            fixture(held::handle) { c ->
                val (leader, a) = background { c.tickets.get(c) }
                assertTrue(held.entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                val (follower, b) = background { c.tickets.get(c) }
                Thread.sleep(300)
                val started = System.nanoTime()
                c.close()
                assertTrue(System.nanoTime() - started < 2_000_000_000L, "close waited on the login")
                follower.join(2_000)
                assertEquals("This connection is closed.", (b.get() as? IllegalStateException)?.message)
                held.release.countDown()
                leader.join(10_000)
                // The ticket that arrived after close is not kept or handed out.
                assertEquals("This connection is closed.", (a.get() as? IllegalStateException)?.message)
                assertEquals("This connection is closed.", assertFailsWith<IllegalStateException> { c.tickets.get(c) }.message)
            }
        } finally { held.release.countDown() }
        assertEquals(1, held.logins.get())
    }
    @Test fun refusedTicketIsDroppedWithoutClosing() = fixture({ 200 to success }) { c ->
        val first = c.tickets.get(c)
        c.tickets.clear()
        assertNotSame(first, c.tickets.get(c))
    }
    @Test fun passwordLoginPinAndRedirectBoundaries() {
        var calls = 0
        fixture({ calls++; 302 to "{}" }) { c ->
            assertFailsWith<IllegalStateException> { c.tickets.get(c) }
            Connection(c.address, c.tokenId, "password", "00".repeat(32)).use { wrong ->
                assertFailsWith<SSLException> { wrong.tickets.get(wrong) }
            }
        }
        assertEquals(1, calls)
    }
    @Test fun validationAndRedaction() {
        fun error(id: String, secret: String = "password", kind: String = DIRECT_PVE_KIND) =
            assertFailsWith<IllegalArgumentException> { Connection("https://example.org", id, secret, "", kind) }.message
        assertEquals("Use a user in user@realm form, for example root@pam.", error("bad"))
        assertEquals("Enter the password.", error("root@pam", "\n"))
        assertEquals("PDM connections use API tokens.", error("root@pam", kind = PDM_KIND))
        Connection("https://example.org", "root@pam", "password", "", otp = "123456").use {
            assertTrue(it.passwordLogin); assertEquals("Connection(redacted)", it.toString())
        }
    }
}

