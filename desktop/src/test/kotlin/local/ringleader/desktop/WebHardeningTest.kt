package local.ringleader.desktop

import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import local.ringleader.desktop.web.*
import org.junit.jupiter.api.Assumptions

/**
 * Network audit 2026-11-16, N-09: hardening of the browser-hosted mode. The audit verified the listener's authentication, Host and
 * Origin checks, headers and surface; none of that is weakened here (WebModeTest and NetAuditWebListenerTest still pin it).
 */
class WebHardeningTest {
    private val key1 = "hardening-fixed-login-key-one-24-plus"
    private val key2 = "hardening-fixed-login-key-two-24-plus"
    private val quick = WebLimits(failedLoginDelayMs = 0)
    private val client = HttpClient.newHttpClient()
    private fun get(url: String, vararg headers: String): HttpResponse<String> =
        client.send(HttpRequest.newBuilder(URI(url)).apply { if (headers.isNotEmpty()) headers(*headers) }.GET().build(), HttpResponse.BodyHandlers.ofString())

    // ---- the gate: per address, a small ceiling for everyone, and the one-time link is never made to wait ----

    private class Clock { var now = 1_000_000L }
    private val limits = WebLimits(failuresBeforeLock = 5, lockMs = 60_000, globalFailuresBeforeLock = 8)

    @Test fun anAddressThatFailsWaitsAloneAndOthersAreNotHeldUpByIt() {
        val clock = Clock(); val gate = LoginGate(limits) { clock.now }
        repeat(4) { gate.failed("203.0.113.5") }
        assertEquals(0, gate.waitMillis("203.0.113.5"), "four failures: not yet")
        gate.failed("203.0.113.5")
        assertEquals(60_000, gate.waitMillis("203.0.113.5"), "the fifth locks this address")
        assertEquals(0, gate.waitMillis("203.0.113.6"), "another address is not held up")
        assertEquals(0, gate.waitMillis("2001:db8::1"))
        clock.now += 59_000
        assertEquals(1_000, gate.waitMillis("203.0.113.5"))
        clock.now += 1_001
        assertEquals(0, gate.waitMillis("203.0.113.5"), "the lock ends after lockMs")
        repeat(4) { gate.failed("203.0.113.5") }
        assertEquals(0, gate.waitMillis("203.0.113.5"), "and the count starts again from zero")
    }

    @Test fun aSignInForgetsTheAddressesFailures() {
        val clock = Clock(); val gate = LoginGate(WebLimits(failuresBeforeLock = 5, lockMs = 60_000, globalFailuresBeforeLock = 1000)) { clock.now }
        repeat(4) { gate.failed("a") }
        gate.succeeded("a")
        repeat(4) { gate.failed("a") }
        assertEquals(0, gate.waitMillis("a"))
        assertEquals(1, gate.remembered)
    }

    @Test fun aSmallCeilingOnAllFailuresTogetherSlowsAGuesserWhoSpreadsOverAddresses() {
        val clock = Clock(); val gate = LoginGate(limits) { clock.now }
        repeat(7) { gate.failed("198.51.100.$it") }                         // one each: no address is near its own limit
        assertEquals(0, gate.waitMillis("198.51.100.200"))
        gate.failed("198.51.100.7")                                         // the eighth failure in the window
        for (address in listOf("198.51.100.0", "198.51.100.200", "2001:db8::77")) assertEquals(60_000, gate.waitMillis(address), address)
        clock.now += 60_001
        assertEquals(0, gate.waitMillis("198.51.100.200"))
    }

    @Test fun manyAddressesDoNotGrowTheMemoryWithoutBound() {
        val clock = Clock(); val gate = LoginGate(WebLimits(globalFailuresBeforeLock = Int.MAX_VALUE)) { clock.now }
        repeat(5000) { clock.now += 5; gate.failed("addr-$it") }
        assertTrue(gate.remembered <= 1024, "remembered ${gate.remembered}")
    }

    @Test fun theLiveOneTimeLinkNeverWaitsWhateverAPeerHasDoneButTheOperatorsKeyDoes() {
        WebServer(WebOptions(port = 0), key1, WebLimits(failedLoginDelayMs = 0, failuresBeforeLock = 2, globalFailuresBeforeLock = 50)).use { server ->
            server.start()
            repeat(2) { assertEquals(403, get("${server.origin}/login?key=guess$it").statusCode()) }
            assertEquals(429, get("${server.origin}/login?key=guess").statusCode(), "guesses wait")
            assertEquals(429, get("${server.origin}/login?key=$key1").statusCode(), "so does the operator's reusable key: it is the one a guesser is after")
            assertFalse(server.link.value!!.used)
            val login = get(server.loginUrl)
            assertEquals(302, login.statusCode(), "the owner's one-time link is not held up by a peer's failures")
            assertTrue(server.link.value!!.used)
        }
        // The ceiling over all addresses holds back guesses and the operator's key, never the one-time link.
        WebServer(WebOptions(port = 0), key1, WebLimits(failedLoginDelayMs = 0, failuresBeforeLock = 100, globalFailuresBeforeLock = 2)).use { server ->
            server.start()
            repeat(2) { assertEquals(403, get("${server.origin}/login?key=guess$it").statusCode()) }
            assertEquals(429, get("${server.origin}/login?key=$key1").statusCode())
            assertEquals(302, get(server.loginUrl).statusCode())
        }
    }

    // ---- the cookie ----

    /** A browser's cookie jar: keyed by host, so every port of 127.0.0.1 shares it (RFC 6265 section 8.5). */
    private class Jar {
        val cookies = linkedMapOf<String, String>()
        fun absorb(response: HttpResponse<*>) { response.headers().allValues("set-cookie").forEach { it.substringBefore(';').let { pair -> cookies[pair.substringBefore('=')] = pair.substringAfter('=') } } }
        fun header() = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    @Test fun twoServersOnOneHostStaySignedInTogetherAndEachIgnoresTheOthersCookie() {
        WebServer(WebOptions(port = 0), key1, quick).use { a -> WebServer(WebOptions(port = 0), key2, quick).use { b ->
            a.start(); b.start()
            assertNotEquals(a.port, b.port)
            val jar = Jar()
            for (server in listOf(a, b)) {
                val login = get(server.loginUrl)
                assertEquals(302, login.statusCode())
                val name = login.headers().firstValue("set-cookie").get().substringBefore('=')
                assertEquals("ringmaster-${server.port}", name, "a name of this server's own")
                jar.absorb(login)
            }
            assertEquals(2, jar.cookies.size, "the second sign-in did not replace the first server's cookie")
            // The browser sends BOTH cookies to either port (it cannot tell them apart); each server reads only its own.
            assertEquals(204, get("${a.origin}/status", "Cookie", jar.header()).statusCode())
            assertEquals(204, get("${b.origin}/status", "Cookie", jar.header()).statusCode())
            val onlyA = "ringmaster-${a.port}=${jar.cookies.getValue("ringmaster-${a.port}")}"
            assertEquals(403, get("${b.origin}/status", "Cookie", onlyA).statusCode(), "A's cookie is no session on B")
            // A cookie called by the old, shared name, set by anything on any port, is no session either.
            assertEquals(403, get("${a.origin}/status", "Cookie", "ringmaster=${jar.cookies.getValue("ringmaster-${a.port}")}").statusCode())
        } }
    }

    @Test fun theCookieStillCarriesEveryFlagTheAuditVerified() {
        WebServer(WebOptions(port = 0), key1, quick).use { server ->
            server.start()
            val flags = get(server.loginUrl).headers().firstValue("set-cookie").get().split(';').map { it.trim().lowercase() }
            assertTrue("httponly" in flags && "samesite=strict" in flags && "path=/" in flags, flags.toString())
            assertTrue(flags.any { it.startsWith("max-age=") })
            assertTrue(flags.none { it.startsWith("domain=") })
        }
    }

    // ---- --bind ::1 ----

    private fun ipv6Loopback() = runCatching { java.net.ServerSocket(0, 1, InetAddress.getByName("::1")).use { true } }.getOrDefault(false)

    @Test fun aListenerOnTheIpv6LoopbackStartsAndItsHostAndOriginChecksUseTheBracketedAddress() {
        Assumptions.assumeTrue(ipv6Loopback(), "no IPv6 loopback on this machine")
        val v6 = InetAddress.getByName("::1")
        WebServer(WebOptions(bind = "::1", port = 0), key1, quick).use { server ->
            server.start()
            assertEquals("http://[::1]:${server.port}", server.origin)
            assertContains(server.loginUrl, "http://[::1]:${server.port}/login?key=")
            fun status(host: String, path: String = "/", extra: String = ""): Int = Socket(v6, server.port).use { socket ->
                socket.soTimeout = 15000
                socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $host\r\n${extra}Connection: close\r\n\r\n".toByteArray())
                socket.getInputStream().bufferedReader().readLine().split(' ')[1].toInt()
            }
            assertEquals(200, status("[::1]:${server.port}"))
            for (host in listOf("127.0.0.1:${server.port}", "localhost:${server.port}", "[::1]", "[::1]:1", "[::2]:${server.port}", "::1")) assertEquals(403, status(host), host)
            assertEquals(403, status("[::1]:${server.port}", "//"), "an empty path segment is refused here too")
            assertFailsWith<ConnectException> { Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", server.port), 2000) } }

            // Sign in as a browser would, over the bracketed literal.
            val login = get(server.loginUrl)
            assertEquals(302, login.statusCode())
            val cookie = login.headers().firstValue("set-cookie").get().substringBefore(';')
            assertTrue(cookie.startsWith("ringmaster-${server.port}="), cookie)
            assertEquals(204, get("${server.origin}/status", "Cookie", cookie).statusCode())
            fun handshake(origin: String): Int = try {
                client.newWebSocketBuilder().header("Origin", origin).header("Cookie", cookie)
                    .buildAsync(URI("ws://[::1]:${server.port}/session"), object : WebSocket.Listener {}).join().abort(); 101
            } catch (e: CompletionException) { (e.cause as WebSocketHandshakeException).response.statusCode() }
            assertEquals(101, handshake(server.origin), "the exact IPv6 origin is the one that is accepted")
            assertEquals(403, handshake("http://127.0.0.1:${server.port}"))
            assertEquals(403, handshake("http://[::1]:1"))
            assertEquals(403, handshake("http://localhost:${server.port}"))
            val out = client.send(HttpRequest.newBuilder(URI("${server.origin}/logout")).headers("Cookie", cookie, "Origin", server.origin).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(204, out.statusCode())
            assertEquals(403, get("${server.origin}/status", "Cookie", cookie).statusCode())
        }
    }

    // ---- other small things ----

    @Test fun anEmptyPathSegmentIsRefusedWhereTheRouterWouldHaveReadItAsAnotherAddress() {
        WebServer(WebOptions(port = 0), key1, quick).use { server ->
            server.start()
            val own = "127.0.0.1:${server.port}"
            assertEquals(200, rawStatus(server.port, "/", own))
            for (path in listOf("//", "//session", "/login//", "///", "//status")) assertEquals(403, rawStatus(server.port, path, own), path)
            assertEquals(404, rawStatus(server.port, "/nothing-here", own))
        }
    }

    @Test fun startingAndStoppingServersLeavesNoShutdownHooksBehind() {
        val field = WebServer::class.java.getDeclaredField("shutdownHook").apply { isAccessible = true }
        repeat(5) {
            val server = WebServer(WebOptions(port = 0), key1, quick)
            server.start()
            val hook = field.get(server) as Thread
            assertNotNull(hook)
            server.close()
            assertEquals(null, field.get(server))
            assertFalse(Runtime.getRuntime().removeShutdownHook(hook), "the hook was removed when the server stopped")
        }
    }

    @Test fun theHeadlessStartupSaysWhatHostActionsAndANetworkListenerMean() {
        val local = headlessLines(WebOptions(), "http://127.0.0.1:18780", "http://127.0.0.1:18780/login?key=K", terminal = true, hasFixedKey = false)
        assertContains(local.joinToString("\n"), "http://127.0.0.1:18780/login?key=K")
        assertTrue(local.none { "HOST ACTIONS" in it } && local.none { "network" in it })
        val acknowledged = WebOptions(bind = "192.0.2.10", allowRemote = true, keystore = "ring.p12", hostActions = true, acknowledgeRemoteHostActions = true)
        val remote = headlessLines(acknowledged, "https://192.0.2.10:18780", "https://192.0.2.10:18780/login?key=K", terminal = true, hasFixedKey = true).joinToString("\n")
        assertContains(remote, "HOST ACTIONS ARE ON"); assertContains(remote, "run programs on this computer"); assertContains(remote, "Other computers can reach this listener")
        assertContains(remote, "place on the network")
        // Into a log, with a fixed key to sign in with, the one-time link is not printed; without one it must be, or nobody could sign in.
        val logged = headlessLines(WebOptions(), "http://127.0.0.1:18780", "http://127.0.0.1:18780/login?key=SECRET", terminal = false, hasFixedKey = true).joinToString("\n")
        assertFalse("SECRET" in logged, logged); assertContains(logged, "RINGMASTER_WEB_KEY")
        assertContains(headlessLines(WebOptions(), "http://127.0.0.1:18780", "http://127.0.0.1:18780/login?key=SECRET", terminal = false, hasFixedKey = false).joinToString("\n"), "SECRET")
    }
}
