package local.ringleader.core

import java.time.Instant
import kotlin.test.*

/** The NETWORK area's cards: facts from what the app already knows, requests attributed by the server in their redacted URL. */
class HoodNetworkTest {
    private val now: Instant = Instant.parse("2026-11-16T10:00:00Z")

    private fun call(id: Long, url: String?, ms: Long, ago: Long, outcome: TraceOutcome = TraceOutcome.Ok, source: TraceSource = TraceSource.PveApi, kind: String = "end"): TraceEvent {
        val at = now.minusSeconds(ago)
        return TraceEvent(id, at, source, kind, "GET /cluster/resources", if (url == null) emptyMap() else mapOf("url" to url), ms, null, null, 200, outcome, spanId = id, endedAt = at)
    }

    private fun session(id: Long, address: String, inView: Boolean = false, phase: TickerSessionPhase = TickerSessionPhase.Connected, route: TickerRoute? = null,
        pinned: Boolean? = true, signIn: String? = "API token", live: Boolean = true, backoff: Int? = null, failure: TickerFailure? = null) =
        TickerSession(id, inView, phase, label = "lab-$id", address = address, signIn = signIn, pinned = pinned, route = route, live = live, liveSeconds = 5,
            backoffSeconds = backoff, updatedAt = now, failure = failure)

    private val east = "https://pve-east.example.test:8006"
    private val west = "https://pve-west.example.test"

    @Test fun requestsAreAttributedToTheServerTheirUrlNames() {
        val sessions = listOf(session(1, east, inView = true), session(2, west), session(3, "https://pdm.example.test:8443"))
        val events = listOf(
            call(1, "$east/api2/json/cluster/resources", 40, 30), call(2, "$east/api2/json/nodes", 90, 4), call(3, "$east/api2/json/cluster/tasks", 700, 9, TraceOutcome.Error),
            call(4, "https://PVE-WEST.example.test:443/api2/json/version", 12, 20),
            call(5, null, 33, 3), call(6, "https://elsewhere.example.test:8006/api2/json/version", 10, 3),
            call(7, "$east/api2/json/old", 10, 400), call(8, "$east/api2/json/begin", 10, 1, kind = "begin"), call(9, "$east/x", 5, 2, source = TraceSource.Console))
        val traffic = HoodNetwork.traffic(sessions, events, now)
        assertEquals(SessionTraffic(lastMs = 90, lastAt = now.minusSeconds(4), lastFailed = false, failures = 1, requests = 3), traffic[1])
        assertEquals(SessionTraffic(12, now.minusSeconds(20), false, 0, 1), traffic[2], "a default port in the address and in the URL name the same server")
        assertNull(traffic[3], "no request seen for this server")
    }

    @Test fun twoAccountsOnOneServerShareItsFigures() {
        val traffic = HoodNetwork.traffic(listOf(session(1, east), session(2, east)), listOf(call(1, "$east/api2/json/version", 25, 2)), now)
        assertEquals(traffic[1], traffic[2]); assertEquals(1, traffic[1]!!.requests)
    }

    @Test fun originKeysNormalisePortsAndCase() {
        assertEquals("pve.example.test:8006", HoodNetwork.originKey("https://PVE.example.test:8006/api2/json"))
        assertEquals("pve.example.test:443", HoodNetwork.originKey("https://pve.example.test"))
        assertNull(HoodNetwork.originKey("http://pve.example.test:8006")); assertNull(HoodNetwork.originKey("")); assertNull(HoodNetwork.originKey("not a url"))
    }

    private fun facts(row: NetworkRow) = row.facts.associate { it.name to it.value }

    @Test fun aConnectionCardReadsAllItsFacts() {
        val sessions = listOf(session(1, east, inView = true, route = TickerRoute(RouteKind.Vpn, VpnKind.Tailscale)), session(2, west, route = TickerRoute(RouteKind.Direct, medium = RouteMedium.Ethernet), pinned = false, signIn = "Password"))
        val rows = HoodNetwork.rows(sessions, listOf(call(1, "$east/api2/json/version", 38, 8), call(2, "$west/api2/json/version", 1500, 30, TraceOutcome.Error)), now,
            TraceLiveLoop("waiting", now.plusSeconds(3), 5000))
        val a = rows[0]; val b = rows[1]
        assertTrue(a.inView); assertFalse(b.inView)
        assertEquals(mapOf("REACHED" to "VPN · Tailscale", "TRUST" to "Pinned certificate", "SIGN-IN" to "API token", "LAST REQUEST" to "38 ms · 8 s ago",
            "FAILURES" to "none in 5 min", "REFRESH" to "Next read in 3 s · every 5 s"), facts(a))
        assertEquals(mapOf("REACHED" to "Direct · Ethernet", "TRUST" to "Normal TLS", "SIGN-IN" to "Password", "LAST REQUEST" to "1.5 s · 30 s ago",
            "FAILURES" to "1 in 5 min", "REFRESH" to "Every 5 s"), facts(b), "only the session in view has the trace's live-loop state")
        assertEquals(TickerTone.Error, b.facts.first { it.name == "LAST REQUEST" }.tone)
        assertEquals(listOf("lab-1", "lab-2"), rows.map { it.label })
    }

    @Test fun theFactListIsOrderedAndAFailureComesFirst() {
        val row = HoodNetwork.rows(listOf(session(1, east, inView = true, phase = TickerSessionPhase.Failed, failure = TickerFailure.SignIn)), emptyList(), now).single()
        assertEquals("PROBLEM", row.facts.first().name); assertEquals("Sign-in refused", row.facts.first().value); assertEquals(TickerTone.Error, row.tone)
        assertFalse(row.facts.any { it.name == "REACHED" || it.name == "REFRESH" }, "a closed session reads no route and no loop")
        assertEquals(listOf("PROBLEM", "TRUST", "SIGN-IN", "LAST REQUEST", "FAILURES"), row.facts.map { it.name })
    }

    @Test fun aDemoSessionClaimsNoServer() {
        val row = HoodNetwork.rows(listOf(session(1, "", inView = true, phase = TickerSessionPhase.Demo, pinned = null, signIn = "Demo")), emptyList(), now).single()
        assertEquals(listOf("REACHED", "REFRESH"), row.facts.map { it.name })
        assertEquals("Demonstration data, no server", row.facts.first().value)
    }

    @Test fun theRefreshFactNamesPauseAndBackOff() {
        fun refresh(s: TickerSession, loop: TraceLiveLoop = TraceLiveLoop()) = HoodNetwork.rows(listOf(s), emptyList(), now, loop).single().facts.first { it.name == "REFRESH" }
        assertEquals("Paused", refresh(session(1, east, live = false)).value)
        val backing = refresh(session(1, east, backoff = 30))
        assertEquals("Backing off · waits 30 s", backing.value); assertEquals(TickerTone.Warning, backing.tone)
        assertEquals("Reading now", refresh(session(1, east, inView = true), TraceLiveLoop("refreshing")).value)
        assertEquals("Not read yet", facts(HoodNetwork.rows(listOf(session(1, east)), emptyList(), now).single())["REACHED"])
        assertEquals("Being read", facts(HoodNetwork.rows(listOf(session(1, east, inView = true)), emptyList(), now).single())["REACHED"])
    }

    @Test fun anotherFactIsOneLineInTheProviderList() {
        // The seam other work plugs into: a reader in the list becomes a line on every card.
        val extra: (TickerSession, NetworkContext) -> NetworkFact? = { s, _ -> NetworkFact("EXAMPLE", "for ${s.id}") }
        val row = (HoodNetwork.providers + extra).let { providers ->
            val context = NetworkContext(now, null, null)
            session(5, east).let { s -> providers.mapNotNull { it(s, context) } }
        }
        assertEquals("EXAMPLE", row.last().name); assertEquals("for 5", row.last().value)
    }
}
