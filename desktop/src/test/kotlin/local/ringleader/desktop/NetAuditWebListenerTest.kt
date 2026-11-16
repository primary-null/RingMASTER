package local.ringleader.desktop

import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import local.ringleader.desktop.web.WebOptions
import local.ringleader.desktop.web.WebServer

/**
 * Network audit 2026-11-16: what the browser-mode listener exposes by default. Complements WebModeTest, which already pins the
 * one-time link, Host/Origin allow-list, cookie flags, lifetimes and the //session bypass.
 */
class NetAuditWebListenerTest {
    private val key = "netaudit-fixed-login-key-24-plus-chars"
    private val client = HttpClient.newHttpClient()

    @Test fun theDefaultListenerAnswersOnlyOnTheLoopbackAddress() {
        WebServer(WebOptions(port = 0), key).use { server ->
            server.start()
            assertEquals(200, rawStatus(server.port, "/", "127.0.0.1:${server.port}"))
            val others = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }.flatMap { it.inetAddresses.toList() }
                .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress }
            for (address in others) {
                assertFailsWith<java.io.IOException>("${address.hostAddress} must not reach the listener") {
                    Socket().use { it.connect(InetSocketAddress(address, server.port), 2000) }
                }
            }
            // No dual-stack surprise: the IPv6 loopback is not served by the IPv4-only default.
            val v6 = runCatching { InetAddress.getByName("::1") }.getOrNull()
            if (v6 != null) assertFailsWith<ConnectException> { Socket().use { it.connect(InetSocketAddress(v6, server.port), 2000) } }
            println("NETAUDIT web listener (default options): bound ${server.origin}; ${others.size} other local addresses refused; ::1 refused")
        }
    }

    @Test fun withoutALoginTheOnlyReachablePagesAreTheStubAndRefusals() {
        WebServer(WebOptions(port = 0), key).use { server ->
            server.start()
            val own = "127.0.0.1:${server.port}"
            val paths = listOf("/", "/status", "/login", "/login?key=", "/index.html", "/web/index.html", "/favicon.ico", "/robots.txt",
                "/%2e%2e/%2e%2e/windows/win.ini", "/..%5c..%5cwindows%5cwin.ini", "/session", "/logout", "/api2/json/version", "//", "/;x", "/login/")
            val statuses = paths.associateWith { rawStatus(server.port, it, own) }
            println("NETAUDIT unauthenticated statuses: $statuses")
            assertTrue(statuses.values.all { it in setOf(200, 400, 403, 404, 405) }, statuses.toString())
            assertEquals(200, statuses.getValue("/"))
            val body = client.send(HttpRequest.newBuilder(URI("${server.origin}/")).GET().build(), HttpResponse.BodyHandlers.ofString()).body()
            assertFalse("<canvas" in body || "<script" in body, "without a session the page is only the stub")
        }
    }
}
