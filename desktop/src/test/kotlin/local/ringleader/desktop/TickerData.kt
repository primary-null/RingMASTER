package local.ringleader.desktop

import java.time.Instant
import local.ringleader.core.*

/** Invented readings for the live ticker's scenes: a fixed instant, no clock, nothing from a server or this computer. */
internal object TickerData {
    val now: Instant = Instant.parse("2026-11-16T10:00:00Z")
    val guest = ResourceId("https://pve-east.example.test:8006", "", "VM", 122)

    fun event(id: Long, source: TraceSource, summary: String, ms: Long? = 40, outcome: TraceOutcome = TraceOutcome.Ok, status: Int? = 200, agoSeconds: Long = 5,
        detail: Map<String, String> = emptyMap(), kind: String = "end"): TraceEvent {
        val at = now.minusSeconds(agoSeconds)
        return TraceEvent(id, at, source, kind, summary, detail, ms, null, null, status, outcome, spanId = id, endedAt = at)
    }

    /** A believable minute of requests: a few a second, with a bump. */
    fun requests(base: Int = 6) = List(60) { i -> (base + 3 * kotlin.math.sin(i / 4.0) + (i % 7) / 3).toInt().coerceAtLeast(0) }

    fun vitals(perMinute: Int = 12, inFlight: Int = 0, sessions: List<TraceSource> = emptyList(), errorsAt: List<Int> = emptyList()) = TraceVitals(
        perMinute, errorsAt.size, List(inFlight) { event(900L + it, TraceSource.PveApi, "GET /cluster/resources", kind = "begin") },
        sessions.mapIndexed { i, source -> TraceSession(event(800L + i, source, "session"), 0, 0, 0, 0) }, requests(),
        List(60) { i -> if (i in errorsAt) 1 else 0 })

    fun metrics(p50: Long? = 38, p95: Long? = 120, sources: List<Pair<TraceSource, Int>> = listOf(TraceSource.PveApi to 9, TraceSource.Auth to 2),
        errors: List<TraceEvent> = emptyList()) = TraceMetrics(p50, p95, List(6) { 0 }, sources,
        listOf(event(77, TraceSource.PveApi, "GET /nodes/pve-east/qemu/120/config", ms = 412)), errors)

    fun failing(source: TraceSource = TraceSource.PveApi, summary: String = "GET /cluster/resources", status: Int? = 503, ago: Long = 8, id: Long = 31) =
        event(id, source, summary, ms = 900, outcome = TraceOutcome.Error, status = status, agoSeconds = ago)

    fun session(id: Long = 1, inView: Boolean = true, phase: TickerSessionPhase = TickerSessionPhase.Connected, route: TickerRoute? = TickerRoute(RouteKind.Direct, medium = RouteMedium.Ethernet),
        pinned: Boolean? = true, failure: TickerFailure? = null, label: String = "Lab cluster", address: String = "https://pve-east.example.test:8006") =
        TickerSession(id, inView, phase, label = label, address = address, signIn = "API token", pinned = pinned, route = route, live = true, liveSeconds = 5,
            updatedAt = now.minusSeconds(3), failure = failure)

    fun input(vitals: TraceVitals = vitals(), metrics: TraceMetrics = metrics(), loop: TraceLiveLoop = TraceLiveLoop("waiting", now.plusSeconds(4), 5000),
        activity: TickerActivity = TickerActivity(listOf(session())), showcase: Boolean = false, names: Boolean = false) =
        TickerInput(now, vitals, metrics, loop, activity, showcase, names)

    val calm get() = input()
    val busy get() = input(vitals(perMinute = 96, inFlight = 4), metrics(p50 = 220, p95 = 1450, sources = listOf(TraceSource.PveApi to 70, TraceSource.PdmApi to 18, TraceSource.Auth to 8)))
    val error get() = input(vitals(errorsAt = listOf(52, 57)), metrics(errors = listOf(failing(), failing(id = 30, ago = 40, summary = "GET /nodes/pve-east/status", status = 500))))
    val longRunning get() = input(activity = TickerActivity(listOf(session()), scan = TickerScan(42, 5),
        tasks = listOf(TickerTask("UPID:pve-east:0001", "Backup", "build-runner", now.minusSeconds(95)))))
    val vpn get() = input(activity = TickerActivity(listOf(session(route = TickerRoute(RouteKind.Vpn, VpnKind.Tailscale)))))
    val failedConnection get() = input(vitals(perMinute = 0), metrics(p50 = null, p95 = null, sources = emptyList()), TraceLiveLoop("idle"),
        TickerActivity(listOf(session(phase = TickerSessionPhase.Failed, route = null, failure = TickerFailure.Certificate))))
    val backoff get() = input(loop = TraceLiveLoop("backoff", now.plusSeconds(24), 5000, 30), metrics = metrics(errors = listOf(failing(ago = 90))))
}
