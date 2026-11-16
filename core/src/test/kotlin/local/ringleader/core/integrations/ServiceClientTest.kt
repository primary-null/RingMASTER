package local.ringleader.core.integrations

import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import local.ringleader.core.Trace
import local.ringleader.core.TraceSource
import kotlin.test.*

/** A scripted service behind [ServiceSender]: every request is recorded; [answer] decides the reply. */
internal class FakeService(var answer: (WireRequest) -> Pair<Int, String>) : ServiceSender {
    val sent: MutableList<WireRequest> = Collections.synchronizedList(mutableListOf())
    var failNext: WireFailure? = null
    override fun send(origin: ServiceOrigin, trust: WireTrust, request: WireRequest, limits: WireLimits): WireAnswer {
        sent += request
        failNext?.let { failNext = null; throw ServiceWireException(it, "scripted failure") }
        val (status, body) = answer(request)
        val headers = if (status == 429) mapOf("retry-after" to "120") else mapOf("content-type" to "application/json")
        return WireAnswer(status, headers, body.toByteArray(), null, if (origin.secure) null else UnencryptedMark.Unencrypted)
    }
    fun lines() = sent.map { "${it.method} ${it.path}" }
}

internal class TestClock(var now: Instant = Instant.parse("2026-11-16T12:00:00Z")) { fun advance(d: Duration) { now = now.plus(d) } }

class ServiceClientTest {
    private val sessionTemplate = ServiceTemplateParser.parse("t.json", ServiceTemplateParserTest.GOOD.replace(
        "\"scheme\": \"header-key\", \"header\": \"X-Api-Key\",",
        "\"scheme\": \"session-password\", \"login\": {\"path\": \"/api/auth\", \"body\": {\"password\": \"secret\"}, \"token\": \"/session/sid\", " +
            "\"validity\": \"/session/validity\", \"totp\": \"/session/totp\", \"sendAs\": \"X-SID\", \"minIntervalSeconds\": 60, \"extendsOnUse\": true}, " +
            "\"logout\": {\"path\": \"/api/auth\"}, \"errors\": {\"401\": \"Refused, scripted.\", \"429\": \"No seats, scripted.\"},"))
    private val keyTemplate = ServiceTemplateParser.parse("t.json", ServiceTemplateParserTest.GOOD)
    private val origin = ServiceOrigin.parseOrThrow("http://192.0.2.5:8080/base")
    private val clock = TestClock()
    private val password = "pw-SECRET-123"
    private var sid = "sid-ALPHA"
    private val stats = """{"total": 10, "part": 4, "state": "on", "h": []}"""

    private fun pihole(loginStatus: Int = 200) = FakeService { r ->
        when {
            r.method == WireMethod.POST && r.path == "/base/api/auth" ->
                if (loginStatus != 200) loginStatus to """{"session":{"valid":false,"sid":null,"totp":false,"validity":-1}}"""
                else if (String(r.body!!).contains(password)) 200 to """{"session":{"valid":true,"sid":"$sid","validity":1800,"totp":false}}"""
                else 401 to """{"session":{"valid":false,"sid":null,"totp":false,"validity":-1}}"""
            r.method == WireMethod.DELETE -> 204 to ""
            r.headers.any { it == ("X-SID" to sid) } -> 200 to stats
            else -> 401 to """{"error":{"key":"unauthorized"}}"""
        }
    }

    private fun client(service: FakeService, template: ServiceTemplate = sessionTemplate, secret: String = password, linkId: String = "link-1") =
        ServiceClient(template, template.variants.single(), origin, WireTrust.PlainHttp(PlainHttpAck.forOrigin(origin, Instant.EPOCH, "k", "p")),
            ServiceCredential(emptyMap(), secret.toCharArray()), service, { clock.now }, linkId)

    private val params = mapOf("pick" to listOf("abc"))

    @Test fun theLoginIsSentOnceAndTheSessionReused() {
        val s = pihole(); val c = client(s)
        repeat(3) { assertNull(c.fetch("stats", params).problem) }
        assertEquals(listOf("POST /base/api/auth", "GET /base/api/stats?count=8&ids=abc", "GET /base/api/stats?count=8&ids=abc", "GET /base/api/stats?count=8&ids=abc"), s.lines())
        assertEquals("""{"password":"$password"}""", String(s.sent.first().body!!), "the declared body, built only from the credential")
        assertTrue(s.sent.first().carriesCredential)
    }

    @Test fun concurrentFetchesShareOneLogin() {
        val s = pihole(); val c = client(s)
        val start = CountDownLatch(1)
        val threads = (1..6).map { Thread { start.await(); c.fetch("stats", params) } }
        threads.forEach { it.start() }; start.countDown(); threads.forEach { it.join(5000) }
        assertEquals(1, s.lines().count { it.startsWith("POST") }, "single flight")
        assertEquals(6, s.lines().count { it.startsWith("GET") })
    }

    @Test fun anExpiredSessionIsRenewedAndUseExtendsIt() {
        val s = pihole(); val c = client(s)
        c.fetch("stats", params)
        clock.advance(Duration.ofMinutes(20)); c.fetch("stats", params)
        clock.advance(Duration.ofMinutes(20)); c.fetch("stats", params)
        assertEquals(1, s.lines().count { it.startsWith("POST") }, "each use extends a 30-minute session")
        clock.advance(Duration.ofMinutes(31)); sid = "sid-BRAVO"; c.fetch("stats", params)
        assertEquals(2, s.lines().count { it.startsWith("POST") }, "an idle session past its validity is renewed")
    }

    @Test fun a401OnAReadLogsInOnceAndRetriesOnce() {
        val s = pihole(); val c = client(s)
        c.fetch("stats", params)
        sid = "sid-CHARLIE" // the service restarted; the old SID is gone
        clock.advance(Duration.ofMinutes(2))
        assertNull(c.fetch("stats", params).problem)
        assertEquals(listOf("POST", "GET", "GET", "POST", "GET"), s.lines().map { it.substringBefore(' ') })
    }

    @Test fun aRefusedLoginIsLatched() {
        val s = pihole(); val c = client(s, secret = "wrong")
        val first = c.fetch("stats", params).problem!!
        assertEquals(ProblemKind.CredentialRefused, first.kind); assertEquals("Refused, scripted.", first.message)
        clock.advance(Duration.ofHours(2))
        assertEquals(ProblemKind.CredentialRefused, c.fetch("stats", params).problem!!.kind)
        assertEquals(1, s.sent.size, "nothing more is sent until the credential changes")
    }

    @Test fun aLostLoginAnswerIsNotRetriedAtOnce() {
        val s = pihole(); val c = client(s)
        s.failNext = WireFailure.NoAnswer
        assertEquals(ProblemKind.NotReachable, c.fetch("stats", params).problem!!.kind)
        assertEquals(ProblemKind.RateLimited, c.fetch("stats", params).problem!!.kind)
        assertEquals(1, s.sent.size, "a second login waits for the minimum interval")
        clock.advance(Duration.ofSeconds(61))
        assertNull(c.fetch("stats", params).problem)
        assertEquals(2, s.lines().count { it.startsWith("POST") })
    }

    @Test fun noFreeSeatWaitsForRetryAfter() {
        val s = pihole(loginStatus = 429); val c = client(s)
        val p = c.fetch("stats", params).problem!!
        assertEquals(ProblemKind.RateLimited, p.kind); assertEquals("No seats, scripted.", p.message); assertEquals(120L, p.retryAfterSeconds)
        clock.advance(Duration.ofSeconds(90)); c.fetch("stats", params)
        assertEquals(1, s.sent.size)
    }

    @Test fun logoutSendsTheDeclaredDeleteWithTheSession() {
        val s = pihole(); val c = client(s)
        c.fetch("stats", params)
        assertTrue(c.sessionSignedIn)
        assertTrue(c.logout())
        assertFalse(c.sessionSignedIn)
        val delete = s.sent.last()
        assertEquals("DELETE /base/api/auth", "${delete.method} ${delete.path}"); assertEquals(listOf("X-SID" to sid), delete.headers)
        assertFalse(c.logout(), "nothing to drop twice")
    }

    @Test fun headerKeysGoInTheHeaderOnly() {
        val s = FakeService { 200 to stats }
        val c = client(s, keyTemplate, "key-SECRET")
        assertNull(c.fetch("stats", params).problem)
        assertEquals(listOf("X-Api-Key" to "key-SECRET"), s.sent.single().headers)
        assertFalse(s.sent.single().path.contains("SECRET"))
    }

    @Test fun statusesMapToTheirClasses() {
        val cases = mapOf(401 to ProblemKind.CredentialRefused, 403 to ProblemKind.Forbidden, 404 to ProblemKind.ApiChanged, 429 to ProblemKind.RateLimited,
            500 to ProblemKind.ServerError, 302 to ProblemKind.Redirected, 418 to ProblemKind.ApiChanged)
        for ((status, kind) in cases) assertEquals(kind, client(FakeService { status to "{}" }, keyTemplate, "k").fetch("stats", params).problem!!.kind, "$status")
        assertEquals(ProblemKind.NotThisService, client(FakeService { 200 to "<html>" }, keyTemplate, "k").fetch("stats", params).problem!!.kind)
        assertEquals(ProblemKind.SettingNeeded, client(FakeService { 200 to "{}" }, keyTemplate, "k").fetch("stats", emptyMap()).problem!!.kind)
        assertEquals(ProblemKind.SettingNeeded, client(FakeService { 200 to "{}" }, keyTemplate, "k").fetch("stats", mapOf("pick" to listOf("BAD;"))).problem!!.kind, "a parameter must match its pattern")
        assertEquals(ProblemKind.CredentialNeeded, ServiceClient(keyTemplate, keyTemplate.variants.single(), origin, WireTrust.PlainHttp(null), null, FakeService { 200 to "{}" }, { clock.now }, "x").fetch("stats", params).problem!!.kind)
    }

    @Test fun pathsAreBuiltOnlyFromTheTemplate() {
        val now = Instant.parse("2026-11-16T12:00:00Z")
        assertEquals("/b/api/history/period/2026-11-15T12:00:00Z?filter_entity_id=sensor.a%2Csensor.b&minimal_response=",
            ServiceClient.buildPath("/b", "/api/history/period/{iso:now-24h}", linkedMapOf("filter_entity_id" to "{param:e}", "minimal_response" to ""),
                mapOf("e" to listOf("sensor.a", "sensor.b")), listOf(ParamSpec("e", "Entities", ParamKind.Multi, 3, Regex("^[a-z_]+\\.[a-z0-9_]+$"), null)), now))
        assertEquals("/x?from=1794744000&q=a%20b", ServiceClient.buildPath("", "/x", linkedMapOf("from" to "{now-1d}", "q" to "a b"), emptyMap(), emptyList(), now))
    }

    @Test fun traceRowsHoldMethodPathAndStatusOnly() {
        Trace.clear()
        val s = pihole(); val c = client(s, linkId = "link-secret-name")
        c.fetch("stats", params); c.logout()
        val rows = Trace.snapshot().filter { it.source == TraceSource.Services }
        assertEquals(listOf("POST /api/auth", "GET /api/stats", "DELETE /api/auth"), rows.map { it.summary })
        assertEquals(listOf(200, 200, 204), rows.map { it.status })
        val text = rows.joinToString("\n") { Trace.jsonLine(it) }
        for (secret in listOf(password, sid, "abc", "count=8", "192.0.2.5", "link-secret-name", "/base")) assertFalse(secret in text, "'$secret' leaked into a trace row:\n$text")
        assertEquals(ServiceClient.shortHash("link-secret-name"), rows.first().detail["link"])
    }

    @Test fun theProbeChoosesTheFirstVariantThatAnswersAsDeclared() {
        val two = ServiceTemplateParser.parse("t.json", ServiceTemplateParserTest.GOOD.replace("\"variants\": [{",
            "\"variants\": [{\"id\": \"v0\", \"label\": \"Old\", \"probe\": {\"path\": \"/old\", \"accept\": [200], \"expect\": \"has(@probe/legacy)\"}, \"unsupported\": \"Old is not supported yet.\"}, {"))
        val seen = AtomicReference<String>()
        fun verdict(service: FakeService) = ServiceClient.probe(two, origin, WireTrust.PlainHttp(null), service, { clock.now }, "t")
        assertEquals("v1", (verdict(FakeService { r -> if (r.path == "/base/old") 404 to "{}" else 401 to "401: Unauthorized" }) as ProbeVerdict.Found).variant.id,
            "an accepted refusal that is not JSON is confirmed later by the first read")
        assertEquals("v1", (verdict(FakeService { r -> if (r.path == "/base/old") 404 to "{}" else 401 to """{"ok": false}""" }) as ProbeVerdict.Found).variant.id)
        assertIs<ProbeVerdict.NoVariant>(verdict(FakeService { r -> if (r.path == "/base/old") 404 to "{}" else 401 to """{"error": "other product"}""" }),
            "an accepted refusal in JSON must still answer as declared")
        assertEquals("v0", (verdict(FakeService { r -> seen.set(r.path); 200 to """{"legacy": 1}""" }) as ProbeVerdict.Found).variant.id)
        assertEquals("v1", (verdict(FakeService { r -> if (r.path == "/base/old") 200 to "{}" else 200 to """{"ok": true}""" }) as ProbeVerdict.Found).variant.id, "expect must hold on a 200")
        assertEquals(ProblemKind.NotThisService, (verdict(FakeService { 404 to "{}" }) as ProbeVerdict.NoVariant).problem.kind)
        val down = FakeService { 200 to "{}" }.apply { failNext = WireFailure.ConnectionRefused }
        assertEquals(ProblemKind.NotReachable, (verdict(down) as ProbeVerdict.NoVariant).problem.kind)
        assertTrue(FakeService { 200 to "{}" }.also { verdict(it) }.sent.all { it.headers.isEmpty() && !it.carriesCredential }, "a probe carries no credential")
    }
}
