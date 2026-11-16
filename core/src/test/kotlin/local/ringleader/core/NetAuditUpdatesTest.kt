package local.ringleader.core

import java.nio.file.Files
import java.security.KeyPairGenerator
import java.time.Clock
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Network audit 2026-11-16: the one outbound-to-the-internet touchpoint, the signed update feed, against the promise in
 * RELEASING.md "What the app does with it". N-13 (a redirect to any host; a deadline that could not interrupt a blocking read) is
 * FIXED: the two characterizations are flipped below, same scenarios, renamed. UpdateHostsTest has the allow-list and the real chain.
 */
class NetAuditUpdatesTest {
    @Test fun theFeedRequestCarriesOnlyAUserAgentAndTheJdksOwnDefaults() {
        RawHttpServer(NetAuditCerts.loopback.server, reply = { "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc" }).use { server ->
            val old = HttpsURLConnection.getDefaultSSLSocketFactory()
            HttpsURLConnection.setDefaultSSLSocketFactory(NetAuditCerts.loopback.trusting.socketFactory)
            try {
                val reply = HttpsUpdateTransport().get("https://127.0.0.1:${server.port}/releases/latest/download/ringmaster-update.json", 1024, "RingMASTER/0.25.9")
                assertEquals(200, reply.status)
                assertEquals("abc", reply.bytes.toString(Charsets.US_ASCII))
            } finally { HttpsURLConnection.setDefaultSSLSocketFactory(old) }
            val head = server.seen.single().head
            val names = head.lineSequence().drop(1).filter { ':' in it }.map { it.substringBefore(':').lowercase() }.toList()
            println("NETAUDIT update request head:\n" + head.trim())
            assertEquals("RingMASTER/0.25.9", server.seen.single().header("User-Agent"))
            assertEquals(setOf("host", "user-agent", "accept", "connection"), names.toSet())
            assertFalse(names.any { it in setOf("cookie", "authorization", "referer", "x-forwarded-for") })
        }
    }

    private fun checker(transport: UpdateTransport, attemptTimeout: Long = 90_000): Pair<UpdateChecker, UpdatePreferences> {
        val prefs = UpdatePreferences(Files.createTempDirectory("netaudit-updates").resolve("updates.properties"))
        prefs.put("consent", "yes")
        val key = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded)
        return UpdateChecker("0.25.9", prefs, listOf(key), transport, Clock.systemUTC(), { "windows-x64-msi" }, false, attemptTimeout) to prefs
    }

    /**
     * The audit's scenario: a redirect to any HTTPS host and port was followed (the Ed25519 signature protected the content, but
     * nothing limited where the request went). Now the redirect is refused before any request is made to that host.
     */
    @Test fun aRedirectToAHostOutsideTheAllowListIsRefusedBeforeAnyRequestIsMade() {
        val urls = mutableListOf<String>()
        val (checker, _) = checker({ url, _, _ ->
            urls += url
            if (urls.size == 1) UpdateResponse(302, location = "https://tracker.example.invalid:4443/anything?x=1") else UpdateResponse(200, "{}".toByteArray())
        })
        runBlocking { checker.check() }
        assertEquals(1, urls.size, "the other host was never requested: $urls")
        val failed = assertIs<UpdateState.Failed>(checker.state.value)
        assertEquals("The update feed redirected to a server RingMASTER does not use for updates, so the check stopped. This can happen on a captive portal or a filtered network.", failed.reason)
        assertEquals(RELEASE_PAGE, failed.releasePage, "the way to the download page stays open")
    }

    @Test fun anHttpRedirectIsRefusedBeforeAnyRequestIsMade() {
        val urls = mutableListOf<String>()
        val (checker, _) = checker({ url, _, _ -> urls += url; UpdateResponse(302, location = "http://tracker.example.invalid/x") })
        runBlocking { checker.check() }
        assertEquals(1, urls.size, "the http:// target was never requested")
        assertEquals("The update feed redirected to an address that is not HTTPS. This can happen on a captive portal.", (checker.state.value as UpdateState.Failed).reason)
    }

    /**
     * The audit's scenario: the 90 s deadline (300 ms here) could not interrupt a blocking transport; check() returned only after the
     * 3000 ms read did (3080 to 3217 ms measured). Now the wait ends at the deadline, the transport is told to abort what it is blocked
     * in, and the worker is released.
     */
    @Test fun theAttemptDeadlineEndsTheCheckWhileTheTransportIsStillBlockedAndAbortsIt() {
        val release = CountDownLatch(1)
        val aborted = AtomicBoolean(false)
        val transport = object : UpdateTransport {
            override fun get(url: String, limit: Int, userAgent: String): UpdateResponse { release.await(30, TimeUnit.SECONDS); return UpdateResponse(404) }
            override fun abort() { aborted.set(true); release.countDown() }
        }
        val (checker, prefs) = checker(transport, attemptTimeout = 300)
        val started = System.nanoTime()
        runBlocking { checker.check() }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        println("NETAUDIT attemptTimeout=300 ms, blocking transport: check() returned after $tookMs ms")
        assertTrue(tookMs in 250..1500, "check() ended at the deadline, not after the read ($tookMs ms)")
        assertTrue(aborted.get(), "the transport was told to close what it was blocked in")
        assertEquals("The update feed could not be reached. Check the network and try again.", (checker.state.value as UpdateState.Failed).reason)
        assertTrue(prefs.lastChecked != null, "a check that ran out of time is recorded like any other failure")
    }
}
