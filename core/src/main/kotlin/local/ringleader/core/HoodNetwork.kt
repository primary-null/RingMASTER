package local.ringleader.core

import java.net.URI
import java.time.Duration
import java.time.Instant

/*
 * The NETWORK area of Under the Hood: one card per open connection, from what the app already knows (the session's state,
 * the route observer's last reading, the trace). Nothing here opens a connection or reads the operating system.
 *
 * To give every connection another line, add a reader to [HoodNetwork.providers]. A reader gets the session and the
 * context and returns one [NetworkFact], or null when it has nothing to say.
 */

/** One line of a connection's card. [name] is upper-case and short; [value] is words. */
data class NetworkFact(val name: String, val value: String, val tone: TickerTone = TickerTone.Calm)

/** [label] and [address] are identities: the page hides them in Showcase. */
data class NetworkRow(
    val id: Long,
    val inView: Boolean,
    val label: String,
    val address: String,
    val kind: String,
    val phase: TickerSessionPhase,
    val facts: List<NetworkFact>,
) {
    val tone: TickerTone get() = facts.maxOfOrNull { it.tone } ?: TickerTone.Calm
}

/** What the trace shows of one server's requests in the last five minutes. */
data class SessionTraffic(val lastMs: Long?, val lastAt: Instant?, val lastFailed: Boolean, val failures: Int, val requests: Int)

/** What a fact reader may look at besides the session. [loop] is the live-loop state, which the trace holds for the session in view only. */
class NetworkContext(val now: Instant, val traffic: SessionTraffic?, val loop: TraceLiveLoop?)

object HoodNetwork {
    const val WINDOW_SECONDS = 300L

    /** The lines of a connection's card, in order. */
    val providers: List<(TickerSession, NetworkContext) -> NetworkFact?> = listOf(
        ::problem, ::reached, ::trust, ::signIn, ::lastRequest, ::failures, ::refresh,
    )

    fun rows(sessions: List<TickerSession>, events: List<TraceEvent>, now: Instant, loop: TraceLiveLoop = TraceLiveLoop()): List<NetworkRow> {
        val traffic = traffic(sessions, events, now)
        return sessions.map { s ->
            val context = NetworkContext(now, traffic[s.id], loop.takeIf { s.inView })
            NetworkRow(s.id, s.inView, s.label, s.address, s.kind, s.phase, providers.mapNotNull { it(s, context) })
        }
    }

    /**
     * Each session's share of the trace: the operations that name its server's address in their redacted URL. Sessions to the
     * same server (two accounts) share the figures. Operations without a URL (consoles, storage) are not attributed.
     */
    fun traffic(sessions: List<TickerSession>, events: List<TraceEvent>, now: Instant): Map<Long, SessionTraffic> {
        val byOrigin = sessions.mapNotNull { s -> originKey(s.address)?.let { it to s.id } }.groupBy({ it.first }, { it.second })
        if (byOrigin.isEmpty()) return emptyMap()
        val since = now.minusSeconds(WINDOW_SECONDS)
        class Tally { var lastAt: Instant? = null; var lastMs: Long? = null; var lastFailed = false; var failures = 0; var requests = 0 }
        val tallies = HashMap<String, Tally>()
        for (e in events) {
            if (e.kind != "end" || (e.source != TraceSource.PveApi && e.source != TraceSource.PdmApi)) continue
            val key = originKey(e.detail["url"] ?: continue) ?: continue
            if (key !in byOrigin) continue
            val at = e.endedAt ?: e.at
            if (at.isBefore(since) || at.isAfter(now)) continue
            val tally = tallies.getOrPut(key) { Tally() }
            tally.requests++
            val failed = e.outcome == TraceOutcome.Error
            if (failed) tally.failures++
            val seen = tally.lastAt
            if (seen == null || at.isAfter(seen)) { tally.lastAt = at; tally.lastMs = e.durationMs; tally.lastFailed = failed }
        }
        return buildMap { for ((key, ids) in byOrigin) tallies[key]?.let { t -> val traffic = SessionTraffic(t.lastMs, t.lastAt, t.lastFailed, t.failures, t.requests); ids.forEach { put(it, traffic) } } }
    }

    /** "host:port" of an https address or URL, lower-case, with 443 for no port. Null for anything else. */
    fun originKey(address: String): String? {
        if (address.isBlank()) return null
        return runCatching {
            val uri = URI(address.trim())
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            if (uri.scheme?.lowercase() != "https") return null
            "$host:${if (uri.port == -1) 443 else uri.port}"
        }.getOrNull()
    }

    // ---- the readers ----

    private fun problem(s: TickerSession, c: NetworkContext): NetworkFact? =
        s.failure?.let { NetworkFact("PROBLEM", it.short.replaceFirstChar(Char::uppercase), TickerTone.Error) }

    private fun reached(s: TickerSession, c: NetworkContext): NetworkFact? {
        val route = s.route
        return when {
            s.phase == TickerSessionPhase.Demo -> NetworkFact("REACHED", "Demonstration data, no server")
            s.phase == TickerSessionPhase.Failed -> null
            route == null -> NetworkFact("REACHED", if (s.inView) "Being read" else "Not read yet")
            else -> NetworkFact("REACHED", routeLine(route), if (route.kind == RouteKind.Proxy) TickerTone.Warning else TickerTone.Calm)
        }
    }

    private fun trust(s: TickerSession, c: NetworkContext): NetworkFact? = when {
        s.phase == TickerSessionPhase.Demo -> null
        s.pinned == true -> NetworkFact("TRUST", "Pinned certificate")
        s.pinned == false -> NetworkFact("TRUST", "Normal TLS")
        else -> null
    }

    private fun signIn(s: TickerSession, c: NetworkContext): NetworkFact? = s.signIn?.takeIf { s.phase != TickerSessionPhase.Demo }?.let { NetworkFact("SIGN-IN", it) }

    private fun lastRequest(s: TickerSession, c: NetworkContext): NetworkFact? {
        if (s.phase == TickerSessionPhase.Demo) return null
        val t = c.traffic
        val at = t?.lastAt
        if (t == null || at == null) return NetworkFact("LAST REQUEST", "none seen in 5 min")
        val ago = Duration.between(at, c.now).seconds.coerceAtLeast(0)
        val ms = t.lastMs?.let(::formatMs)
        return NetworkFact("LAST REQUEST", listOfNotNull(ms, if (ago < 5) "just now" else "${formatSeconds(ago)} ago").joinToString(" · "),
            when { t.lastFailed -> TickerTone.Error; (t.lastMs ?: 0) >= 1000 -> TickerTone.Warning; else -> TickerTone.Calm })
    }

    private fun failures(s: TickerSession, c: NetworkContext): NetworkFact? {
        if (s.phase == TickerSessionPhase.Demo) return null
        val n = c.traffic?.failures ?: 0
        return NetworkFact("FAILURES", if (n == 0) "none in 5 min" else "$n in 5 min", when { n == 0 -> TickerTone.Calm; n < 3 -> TickerTone.Warning; else -> TickerTone.Error })
    }

    private fun refresh(s: TickerSession, c: NetworkContext): NetworkFact? {
        if (!s.usable) return null
        val loop = c.loop
        val next = loop?.nextTickAt?.let { Duration.between(c.now, it).seconds.coerceAtLeast(0) }
        return when {
            loop != null && loop.state == "refreshing" -> NetworkFact("REFRESH", "Reading now", TickerTone.Busy)
            (loop != null && loop.state == "backoff") || s.backoffSeconds != null -> {
                val waits = s.backoffSeconds?.toLong() ?: loop?.backoffSeconds?.takeIf { it > 0 }
                NetworkFact("REFRESH", "Backing off" + (waits?.let { " · waits ${formatSeconds(it)}" } ?: ""), TickerTone.Warning)
            }
            !s.live -> NetworkFact("REFRESH", "Paused")
            loop != null && next != null && loop.state in setOf("waiting", "demo") -> NetworkFact("REFRESH", "Next read in ${formatSeconds(next)} · every ${formatSeconds(s.liveSeconds.toLong())}")
            else -> NetworkFact("REFRESH", "Every ${formatSeconds(s.liveSeconds.toLong())}")
        }
    }

    private fun routeLine(route: TickerRoute): String = when (route.kind) {
        RouteKind.Direct -> listOfNotNull("Direct", route.medium?.label).joinToString(" · ")
        RouteKind.Vpn -> listOfNotNull("VPN", vpnLabel(route.vpn)).joinToString(" · ")
        RouteKind.Proxy -> "Through a proxy"
        RouteKind.Local -> "This computer"
        RouteKind.Unknown -> "Not known"
    }
}
