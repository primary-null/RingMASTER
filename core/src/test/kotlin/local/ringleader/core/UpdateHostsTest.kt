package local.ringleader.core

import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Clock
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Network audit 2026-11-16, N-13: an update check follows redirects only to the release host and the file host GitHub hands a
 * download on to, on the default HTTPS port, and its deadline really ends a blocked read.
 */
class UpdateHostsTest {
    private val manifest = """{"schema":1,"channel":"stable","version":"0.99.0","published":"2026-11-10T00:00:00Z","notes":"Notes","assets":[{"platform":"windows-x64-msi","url":"https://github.com/primary-null/RingMASTER/releases/download/v0.99.0/RingMASTER-0.99.0.msi","sha256":"${"a".repeat(64)}","size":1048576}]}"""
    private fun pair() = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private fun publicKey(k: KeyPair) = Base64.getEncoder().encodeToString(k.public.encoded)
    private fun sign(bytes: ByteArray, k: KeyPair) = Base64.getEncoder().encode(Signature.getInstance("Ed25519").run { initSign(k.private); update(bytes); sign() })
    private fun prefs() = UpdatePreferences(Files.createTempDirectory("update-hosts").resolve("updates.properties")).also { it.put("consent", "yes") }
    private fun checker(key: KeyPair, transport: UpdateTransport, attemptTimeout: Long = 90_000, prefs: UpdatePreferences = prefs()) =
        UpdateChecker("0.25.10", prefs, listOf(publicKey(key)), transport, Clock.systemUTC(), { "windows-x64-msi" }, false, attemptTimeout)

    private val feedLatest = "https://github.com/primary-null/RingMASTER/releases/latest/download"
    private val feedTagged = "https://github.com/primary-null/RingMASTER/releases/download/v0.99.0"
    private val files = "https://release-assets.githubusercontent.com/github-production-release-asset/123/abc?token=redacted"

    /** GitHub's real chain for each file, as the audit saw it: two 302 responses on github.com, then the file from release-assets. */
    private fun githubChain(key: KeyPair, seen: MutableList<String>) = UpdateTransport { url, _, _ ->
        seen += url
        val uri = URI(url)
        when {
            uri.host == "github.com" && "/latest/download/" in uri.path -> UpdateResponse(302, location = url.replace(feedLatest, feedTagged))
            uri.host == "github.com" -> UpdateResponse(302, location = files + if (uri.path.endsWith(".sig")) "&file=sig" else "&file=json")
            uri.host == "release-assets.githubusercontent.com" ->
                UpdateResponse(200, if (uri.query.endsWith("sig")) sign(manifest.toByteArray(), key) else manifest.toByteArray())
            else -> error("unexpected host in $url")
        }
    }

    @Test fun theRealChainOfSixRequestsToTwoHostsIsFollowedAndTheSignedFeedIsOffered() {
        val key = pair(); val seen = mutableListOf<String>()
        val c = checker(key, githubChain(key, seen))
        runBlocking { c.check() }
        assertEquals(6, seen.size, seen.toString())
        assertEquals(setOf("github.com", "release-assets.githubusercontent.com"), seen.map { URI(it).host }.toSet())
        assertEquals(UPDATE_HOSTS, seen.map { URI(it).host }.toSet(), "the allow-list is exactly the hosts of the real chain")
        assertIs<UpdateState.Available>(c.state.value)
    }

    @Test fun everyHostThatIsNotOneOfTheTwoIsRefusedWhateverItLooksLike() {
        for (target in listOf(
            "https://example.com/feed", "https://github.com.example.invalid/x", "https://evilgithub.com/x", "https://githubusercontent.com/x",
            "https://raw.githubusercontent.com/x", "https://objects.githubusercontent.com/x", "https://release-assets.githubusercontent.com.example.invalid/x",
            "https://api.github.com/x", "https://gist.github.com/x", "https://github.com:4443/x", "https://release-assets.githubusercontent.com:8443/x",
            "https://127.0.0.1/x", "https://[::1]/x", "https://github.com./x",
        )) {
            val seen = mutableListOf<String>()
            val c = checker(pair(), { url, _, _ -> seen += url; if (seen.size == 1) UpdateResponse(302, location = target) else UpdateResponse(404) })
            runBlocking { c.check() }
            assertEquals(1, seen.size, "$target was requested")
            assertTrue("does not use for updates" in assertIs<UpdateState.Failed>(c.state.value).reason, target)
        }
    }

    @Test fun theTwoHostsAreAcceptedInAnyCaseOnTheDefaultPortOnly() {
        for (url in listOf("https://github.com/x", "https://GitHub.com/x", "https://github.com:443/x", "https://release-assets.githubusercontent.com/x", "https://RELEASE-assets.githubusercontent.com:443/x"))
            assertTrue(updateHostAllowed(URI(url)), url)
        for (url in listOf("http://github.com/x", "https://github.com:80/x", "https://github.com:8080/x", "ftp://github.com/x"))
            assertFalse(updateHostAllowed(URI(url)), url)
    }

    @Test fun aRedirectToTheOtherAllowedHostOnlyForTheFeedItselfFollowsTheSameRules() {
        // The first request is the feed's own address; a loop of redirects between the two hosts stops at six hops.
        var n = 0
        val c = checker(pair(), { _, _, _ -> n++; UpdateResponse(302, location = if (n % 2 == 1) files else feedTagged + "/ringmaster-update.json") })
        runBlocking { c.check() }
        assertEquals(6, n, "six hops per file at most")
        assertEquals("The update feed could not be verified.", assertIs<UpdateState.Failed>(c.state.value).reason)
    }

    @Test fun aDeadlineFreesTheLockSoTheNextCheckStartsAtOnce() {
        val key = pair()
        val stuck = CountDownLatch(1)
        var calls = 0
        val transport = object : UpdateTransport {
            override fun get(url: String, limit: Int, userAgent: String): UpdateResponse {
                if (++calls == 1) { stuck.await(30, TimeUnit.SECONDS); return UpdateResponse(404) }
                return UpdateResponse(404)
            }
            override fun abort() = Unit            // a transport that cannot be aborted: the worker stays stuck
        }
        val c = checker(key, transport, attemptTimeout = 200)
        try {
            runBlocking { c.check() }
            assertEquals("The update feed could not be reached. Check the network and try again.", assertIs<UpdateState.Failed>(c.state.value).reason)
            val started = System.nanoTime()
            runBlocking { c.check() }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2000, "the second check did not wait behind the stuck first one")
            assertEquals("The update feed was not found. Its address may have changed.", assertIs<UpdateState.Failed>(c.state.value).reason)
        } finally { stuck.countDown() }
    }

    @Test fun aCheckThatTimedOutWritesNothingToThePreferencesAfterwards() {
        val key = pair(); val prefs = prefs()
        val release = CountDownLatch(1)
        val transport = object : UpdateTransport {
            override fun get(url: String, limit: Int, userAgent: String): UpdateResponse {
                release.await(30, TimeUnit.SECONDS)
                return UpdateResponse(200, if (url.endsWith(".sig")) sign(manifest.toByteArray(), key) else manifest.toByteArray())
            }
            override fun abort() { release.countDown() }
        }
        val c = checker(key, transport, attemptTimeout = 200, prefs = prefs)
        runBlocking { c.check() }
        Thread.sleep(400)
        assertNull(prefs.feedPublished, "the abandoned check did not record a feed date")
    }

    // ---- the real transport ----

    @Test fun abortClosesTheConnectionARealReadIsBlockedInSoItFailsAtOnceNotAtTheTwentySecondTimeout() {
        val held = CountDownLatch(1)
        RawHttpServer(NetAuditCerts.loopback.server, reply = { held.await(30, TimeUnit.SECONDS); "" }).use { server ->
            val old = HttpsURLConnection.getDefaultSSLSocketFactory()
            HttpsURLConnection.setDefaultSSLSocketFactory(NetAuditCerts.loopback.trusting.socketFactory)
            try {
                val transport = HttpsUpdateTransport()
                val failure = AtomicReference<Throwable?>()
                val finished = CountDownLatch(1)
                Thread {
                    try { transport.get("https://127.0.0.1:${server.port}/slow", 1024, "RingMASTER/test") } catch (e: Throwable) { failure.set(e) } finally { finished.countDown() }
                }.apply { isDaemon = true; start() }
                waitUntil("the request reached the server") { server.bytesReadBeforeHead.get() > 0 }
                Thread.sleep(300)                                    // headers are out; the server holds its answer
                val started = System.nanoTime()
                transport.abort()
                assertTrue(finished.await(5, TimeUnit.SECONDS), "the blocked read ended")
                assertTrue(failure.get() is IOException, "an IOException, as any broken connection: ${failure.get()}")
                assertTrue((System.nanoTime() - started) / 1_000_000 < 3000)
            } finally { held.countDown(); HttpsURLConnection.setDefaultSSLSocketFactory(old) }
        }
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!condition()) { check(System.nanoTime() < deadline) { "timed out waiting for $what" }; Thread.sleep(10) }
    }
}
