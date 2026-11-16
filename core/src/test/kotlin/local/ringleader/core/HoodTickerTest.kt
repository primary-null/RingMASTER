package local.ringleader.core

import java.time.Duration
import java.time.Instant
import kotlin.test.*

/** The tile model: which tiles appear in which situations, their order, the cap, the rounding, the targets and what never shows. */
class HoodTickerTest {
    private val now: Instant = Instant.parse("2026-11-16T10:00:00Z")
    private val guest = ResourceId("https://pve.example.test:8006", "", "VM", 121)

    private fun event(id: Long, source: TraceSource, summary: String, ms: Long? = 40, outcome: TraceOutcome = TraceOutcome.Ok, status: Int? = 200,
        agoSeconds: Long = 5, detail: Map<String, String> = emptyMap()): TraceEvent {
        val at = now.minusSeconds(agoSeconds)
        return TraceEvent(id, at, source, "end", summary, detail, ms, null, null, status, outcome, spanId = id, endedAt = at)
    }

    private fun vitals(perMinute: Int = 12, inFlight: Int = 0, sessions: List<TraceSource> = emptyList(), errors: List<Int> = List(60) { 0 }) = TraceVitals(
        perMinute, errors.sum(), List(inFlight) { event(900L + it, TraceSource.PveApi, "GET /cluster/resources") },
        sessions.mapIndexed { i, source -> TraceSession(event(800L + i, source, "session"), 0, 0, 0, 0) }, List(60) { it % 5 }, errors)

    private fun metrics(p50: Long? = 38, p95: Long? = 120, sources: List<Pair<TraceSource, Int>> = listOf(TraceSource.PveApi to 9, TraceSource.Auth to 2),
        slowest: List<TraceEvent> = listOf(event(77, TraceSource.PveApi, "GET /nodes/pve-east/qemu/120/config", ms = 412)),
        errors: List<TraceEvent> = emptyList()) = TraceMetrics(p50, p95, List(6) { 0 }, sources, slowest, errors)

    private fun session(id: Long = 1, inView: Boolean = true, phase: TickerSessionPhase = TickerSessionPhase.Connected, route: TickerRoute? = TickerRoute(RouteKind.Direct),
        pinned: Boolean? = true, failure: TickerFailure? = null) = TickerSession(id, inView, phase, route = route, pinned = pinned, signIn = "API token", live = true,
        liveSeconds = 5, updatedAt = now, failure = failure, label = "pve-east", address = "https://pve.example.test:8006")

    private fun input(vitals: TraceVitals = vitals(), metrics: TraceMetrics = metrics(), loop: TraceLiveLoop = TraceLiveLoop("waiting", now.plusSeconds(4), 5000),
        activity: TickerActivity = TickerActivity(listOf(session())), showcase: Boolean = false, names: Boolean = false) =
        TickerInput(now, vitals, metrics, loop, activity, showcase, names)

    private fun failing(source: TraceSource = TraceSource.PveApi, summary: String = "GET /cluster/resources", status: Int? = 503, ago: Long = 8, id: Long = 31) =
        event(id, source, summary, ms = 900, outcome = TraceOutcome.Error, status = status, agoSeconds = ago)

    private fun ids(model: TickerModel) = model.tiles.map { it.id }

    @Test fun aCalmSessionShowsThreeTilesInOrder() {
        val model = HoodTicker.build(input())
        assertEquals(listOf(TickerTileId.Activity, TickerTileId.Latency, TickerTileId.LiveLoop), ids(model))
        assertEquals("12/min", model.tiles[0].headline)
        assertEquals("none in flight", model.tiles[0].detail)
        assertEquals("38 ms", model.tiles[1].headline)
        assertEquals("slow end 120 ms", model.tiles[1].detail)
        assertEquals("in 4 s", model.tiles[2].headline)
        assertTrue(model.tiles.all { it.tone == TickerTone.Calm })
        assertEquals(TickerTone.Calm, model.tone)
    }

    @Test fun nothingHappeningIsNoTilesAtAll() {
        val model = HoodTicker.build(input(vitals(perMinute = 0), metrics(p50 = null, p95 = null, sources = emptyList(), slowest = emptyList()), TraceLiveLoop("idle"), TickerActivity()))
        assertTrue(model.isEmpty)
        assertNull(model.pill)
        assertEquals(TickerTone.Calm, model.tone)
    }

    @Test fun theCollapsedPillIsTheFirstTilesReading() {
        val calm = HoodTicker.build(input())
        assertEquals(TickerPill("12/min", TickerTone.Calm), calm.pill)
        val errors = HoodTicker.build(input(metrics = metrics(errors = listOf(failing()))))
        assertEquals(TickerPill("1 error", TickerTone.Error), errors.pill)
    }

    @Test fun requestsOffersTheBusiestSourcesAsStreamLinks() {
        val tile = HoodTicker.build(input(metrics = metrics(sources = listOf(TraceSource.PveApi to 9, TraceSource.Auth to 4, TraceSource.PdmApi to 2, TraceSource.Console to 1)))).tiles.first()
        assertEquals(TickerTarget.Hood(HoodFocus.Requests), tile.target)
        assertEquals(listOf("PVE API", "Sign-in", "PDM API"), tile.links.map { it.label })
        assertEquals(TickerTarget.Hood(HoodFocus.Stream(TraceSource.Auth)), tile.links[1].target)
        // No requests by source yet: one link to the whole stream.
        val none = HoodTicker.build(input(vitals(perMinute = 0, inFlight = 1), metrics(sources = emptyList()))).tiles.first { it.id == TickerTileId.Activity }
        assertEquals(listOf(TickerLink("Stream", TickerTarget.Hood(HoodFocus.Stream(null)))), none.links)
        assertEquals("1 in flight", none.detail)
    }

    @Test fun latencyLinksToTheSlowestOperationAndWarnsWhenSlow() {
        val tile = HoodTicker.build(input()).tiles.first { it.id == TickerTileId.Latency }
        assertEquals(TickerTarget.Hood(HoodFocus.Latency), tile.target)
        assertEquals(TickerLink("Slowest", TickerTarget.Hood(HoodFocus.Operation(77))), tile.links.first())
        assertEquals(TickerTarget.Hood(HoodFocus.Slowest), tile.links.last().target)
        val slow = HoodTicker.build(input(metrics = metrics(p50 = 700, p95 = 2400))).tiles.first { it.id == TickerTileId.Latency }
        assertEquals(TickerTone.Warning, slow.tone)
        assertEquals("700 ms", slow.headline); assertEquals("slow end 2.4 s", slow.detail)
        // No slowest operation: only the area link.
        val bare = HoodTicker.build(input(metrics = metrics(slowest = emptyList()))).tiles.first { it.id == TickerTileId.Latency }
        assertEquals(listOf(TickerTarget.Hood(HoodFocus.Slowest)), bare.links.map { it.target })
        // No completed request in the last minute: no latency tile.
        assertFalse(HoodTicker.build(input(metrics = metrics(p50 = null, p95 = null))).tiles.any { it.id == TickerTileId.Latency })
    }

    @Test fun numbersAreRoundedForReading() {
        assertEquals("38 ms", formatMs(38)); assertEquals("999 ms", formatMs(999)); assertEquals("1.0 s", formatMs(1000)); assertEquals("1.2 s", formatMs(1234))
        assertEquals("12 s", formatMs(12_345)); assertEquals("0/min", formatPerMinute(0)); assertEquals("999/min", formatPerMinute(999)); assertEquals("1.5k/min", formatPerMinute(1500))
        assertEquals("5 s", formatSeconds(5)); assertEquals("2 min", formatSeconds(150)); assertEquals("1 h", formatSeconds(3700))
        assertEquals("1.2 s", HoodTicker.build(input(metrics = metrics(p50 = 1234, p95 = 12_345))).tiles.first { it.id == TickerTileId.Latency }.headline)
    }

    @Test fun aFreshErrorLeadsInRedWithTheLatestAndItsLinks() {
        val spikes = List(60) { if (it > 55) 1 else 0 }
        val model = HoodTicker.build(input(vitals(errors = spikes), metrics(errors = listOf(failing(), failing(id = 30, ago = 100))), activity = TickerActivity(listOf(session()))))
        val tile = model.tiles.first()
        assertEquals(TickerTileId.Errors, tile.id)
        assertEquals(TickerTone.Error, tile.tone)
        assertEquals("2", tile.headline); assertEquals("2 errors", tile.pill)
        assertEquals(spikes, tile.history)
        assertTrue(tile.detail!!.startsWith("PVE API 503 GET /cluster/resources"), tile.detail)
        assertEquals(TickerTarget.Hood(HoodFocus.Errors), tile.target)
        assertEquals(TickerLink("Latest", TickerTarget.Hood(HoodFocus.Operation(31))), tile.links.first())
        assertEquals(TickerTarget.Hood(HoodFocus.Stream(TraceSource.PveApi)), tile.links.last().target)
        assertEquals(TickerTone.Error, model.tone)
    }

    @Test fun anErrorTilePointsToTheOtherPartOfTheAppItBelongsTo() {
        val task = TickerTask("UPID:pve-east:0001", "Backup", "VM 121", now.minusSeconds(60))
        val kept = TickerConsole(guest, "build-runner")
        fun linkFor(source: TraceSource, activity: TickerActivity = TickerActivity(listOf(session()), failedTask = task, kept = listOf(kept))) =
            HoodTicker.build(input(metrics = metrics(errors = listOf(failing(source))), activity = activity)).tiles.first { it.id == TickerTileId.Errors }.links.map { it.target }
        assertTrue(TickerTarget.Vpn() in linkFor(TraceSource.Vpn))
        assertTrue(TickerTarget.Scan in linkFor(TraceSource.Scan))
        assertTrue(TickerTarget.Activity(task.upid) in linkFor(TraceSource.Task))
        assertTrue(TickerTarget.Tools in linkFor(TraceSource.Tools))
        assertTrue(TickerTarget.Fleet(guest, console = true) in linkFor(TraceSource.Console))
        assertTrue(TickerTarget.Fleet(guest, console = true) in linkFor(TraceSource.Terminal))
        assertTrue(TickerTarget.Desk in linkFor(TraceSource.Auth))
        // A source that lives only in Under the Hood has no page: Latest and the filtered stream, never more than three links.
        assertEquals(2, linkFor(TraceSource.PveApi).size)
        assertTrue(listOf(TraceSource.Vpn, TraceSource.Task, TraceSource.Console, TraceSource.Auth).all { linkFor(it).size <= HoodTicker.MAX_LINKS })
        // An activity page with no failed task known still opens.
        assertTrue(TickerTarget.Activity() in linkFor(TraceSource.Task, TickerActivity(listOf(session()))))
    }

    @Test fun anOldErrorTurnsAmberAndStopsLeading() {
        val scan = TickerActivity(listOf(session()), scan = TickerScan(40, 3))
        val old = HoodTicker.build(input(metrics = metrics(errors = listOf(failing(ago = 150))), activity = scan))
        assertEquals(listOf(TickerTileId.Now, TickerTileId.Errors), ids(old).take(2))
        assertEquals(TickerTone.Warning, old.tiles[1].tone)
        val fresh = HoodTicker.build(input(metrics = metrics(errors = listOf(failing(ago = 3))), activity = scan))
        assertEquals(listOf(TickerTileId.Errors, TickerTileId.Now), ids(fresh).take(2))
        // After five minutes the metrics no longer hold it: no tile.
        assertFalse(HoodTicker.build(input()).tiles.any { it.id == TickerTileId.Errors })
    }

    @Test fun aRunningScanTaskOrUploadBringsTheNowTileToTheFront() {
        val scan = HoodTicker.build(input(activity = TickerActivity(listOf(session()), scan = TickerScan(42, 5)))).tiles.first()
        assertEquals(TickerTileId.Now, scan.id)
        assertEquals("Scan 42 %", scan.headline); assertEquals("5 hosts answering", scan.detail)
        assertEquals(TickerTarget.Hood(HoodFocus.Sessions), scan.target)
        assertEquals(listOf(TickerLink("Scan", TickerTarget.Scan)), scan.links)
        val task = TickerTask("UPID:pve-east:0002", "Backup", "VM 121", now.minusSeconds(95))
        val running = HoodTicker.build(input(activity = TickerActivity(listOf(session()), tasks = listOf(task)))).tiles.first()
        assertEquals("Backup", running.headline); assertEquals("running 1 min", running.detail)
        assertEquals(listOf(TickerLink("Activity", TickerTarget.Activity(task.upid))), running.links)
        val upload = HoodTicker.build(input(activity = TickerActivity(listOf(session()), uploading = true, uploadPercent = 63))).tiles.first()
        assertEquals("Upload 63 %", upload.headline); assertEquals(TickerTarget.Storage, upload.links.single().target)
        val tool = HoodTicker.build(input(activity = TickerActivity(listOf(session()), toolRunning = true))).tiles.first()
        assertEquals(listOf(TickerLink("Tools", TickerTarget.Tools)), tool.links)
        val vpn = HoodTicker.build(input(activity = TickerActivity(listOf(session()), vpnBusy = true))).tiles.first()
        assertEquals(listOf(TickerLink("VPN", TickerTarget.Vpn())), vpn.links)
        // Nothing running, no console: no tile.
        assertFalse(HoodTicker.build(input()).tiles.any { it.id == TickerTileId.Now })
    }

    @Test fun severalRunningThingsShareOneTileAndOneLinkEach() {
        val task = TickerTask("UPID:pve-east:0003", "Backup", null, now.minusSeconds(10))
        val tile = HoodTicker.build(input(vitals(sessions = listOf(TraceSource.Console)),
            activity = TickerActivity(listOf(session()), scan = TickerScan(10, 1), tasks = listOf(task), kept = listOf(TickerConsole(guest))))).tiles.first()
        assertEquals("Backup", tile.headline)
        assertEquals("running 10 s · +2 more", tile.detail)
        assertEquals(listOf("Activity", "Scan", "Guest"), tile.links.map { it.label })
        assertEquals(TickerTarget.Fleet(guest, console = true), tile.links.last().target)
        assertTrue(tile.links.size <= HoodTicker.MAX_LINKS)
    }

    @Test fun anOpenConsoleIsATileOnlyWhenNothingElseRuns() {
        val tile = HoodTicker.build(input(vitals(sessions = listOf(TraceSource.Console)), activity = TickerActivity(listOf(session()), kept = listOf(TickerConsole(guest, "build-runner"))))).tiles.first()
        assertEquals(TickerTileId.Now, tile.id); assertEquals("Console open", tile.headline)
        assertEquals(TickerTarget.Fleet(guest, console = true), tile.links.single().target)
        val two = HoodTicker.build(input(vitals(sessions = listOf(TraceSource.Console, TraceSource.Terminal)))).tiles.first()
        assertEquals("2 consoles open", two.headline)
        // The guest is not known: Fleet without one.
        assertEquals(TickerTarget.Fleet(null, console = true), two.links.single().target)
    }

    @Test fun theLiveLoopReadsEveryState() {
        fun loop(state: String, next: Long? = null, interval: Long = 5000, backoff: Long = 0) =
            HoodTicker.build(input(loop = TraceLiveLoop(state, next?.let { now.plusSeconds(it) }, interval, backoff))).tiles.firstOrNull { it.id == TickerTileId.LiveLoop }
        assertEquals("in 4 s", loop("waiting", 4)!!.headline); assertEquals("every 5 s", loop("waiting", 4)!!.detail)
        assertEquals("Reading now", loop("refreshing")!!.headline); assertEquals(TickerTone.Busy, loop("refreshing")!!.tone)
        val backoff = loop("backoff", 28, backoff = 30)!!
        assertEquals("Backing off", backoff.headline); assertEquals("retry in 28 s · waits 30 s", backoff.detail); assertEquals(TickerTone.Warning, backoff.tone)
        assertNull(loop("paused")); assertNull(loop("idle"))
        assertEquals(TickerTarget.Hood(HoodFocus.Engine), loop("waiting", 4)!!.target)
        // A loop that is backing off leads the calm readings.
        assertEquals(TickerTileId.LiveLoop, HoodTicker.build(input(loop = TraceLiveLoop("backoff", now.plusSeconds(9), 5000, 10))).tiles.first().id)
        // The countdown never goes below zero.
        assertEquals("in 0 s", HoodTicker.build(input(loop = TraceLiveLoop("waiting", now.minusSeconds(3), 5000))).tiles.first { it.id == TickerTileId.LiveLoop }.headline)
    }

    @Test fun theRouteIsATileOnlyWhenItIsNotable() {
        fun network(activity: TickerActivity) = HoodTicker.build(input(activity = activity)).tiles.firstOrNull { it.id == TickerTileId.Network }
        assertNull(network(TickerActivity(listOf(session()))), "an ordinary direct route is already in the page heading")
        val vpn = network(TickerActivity(listOf(session(route = TickerRoute(RouteKind.Vpn, VpnKind.Tailscale)))))!!
        assertEquals("Tailscale", vpn.headline); assertEquals("pinned cert", vpn.detail)
        assertEquals(TickerTarget.Hood(HoodFocus.Network), vpn.target)
        assertEquals(listOf(TickerLink("VPN", TickerTarget.Vpn(VpnKind.Tailscale))), vpn.links)
        val proxy = network(TickerActivity(listOf(session(route = TickerRoute(RouteKind.Proxy), pinned = false))))!!
        assertEquals(TickerTone.Warning, proxy.tone); assertEquals("normal TLS", proxy.detail); assertTrue(proxy.links.isEmpty())
        val several = network(TickerActivity(listOf(session(), session(2, inView = false), session(3, inView = false))))!!
        assertEquals("3 connections", several.detail.orEmpty().substringAfterLast(" · "))
        val failed = network(TickerActivity(listOf(session(phase = TickerSessionPhase.Failed, route = null, failure = TickerFailure.Certificate))))!!
        assertEquals("Failed", failed.headline); assertEquals(TickerTone.Error, failed.tone); assertEquals("certificate refused", failed.detail)
        assertEquals(TickerTarget.Desk, failed.links.first().target)
        assertEquals("Connection failed", failed.pill)
        // A failed connection leads the calm readings.
        assertEquals(TickerTileId.Network, HoodTicker.build(input(activity = TickerActivity(listOf(session(phase = TickerSessionPhase.Failed, route = null))))).tiles.first().id)
    }

    @Test fun noMoreThanFourTilesAndTheMostTellingOnesWin() {
        val busy = TickerActivity(listOf(session(phase = TickerSessionPhase.Failed, route = null)), scan = TickerScan(10, 1), vpnBusy = true)
        val model = HoodTicker.build(input(vitals(inFlight = 5), metrics(errors = listOf(failing())), TraceLiveLoop("backoff", now.plusSeconds(9), 5000, 10), busy))
        assertEquals(HoodTicker.MAX_TILES, model.tiles.size)
        assertEquals(listOf(TickerTileId.Errors, TickerTileId.Now, TickerTileId.Network, TickerTileId.LiveLoop), ids(model))
        assertTrue(model.tiles.all { it.links.size <= HoodTicker.MAX_LINKS })
    }

    @Test fun aTileCannotHoldMoreThanThreeLinks() {
        assertFailsWith<IllegalArgumentException> {
            TickerTile(TickerTileId.Activity, "X", "1", target = TickerTarget.Desk, links = List(4) { TickerLink("a$it", TickerTarget.Desk) })
        }
    }

    @Test fun noIdentityReachesATileUnlessTheNamesMayShow() {
        val task = TickerTask("UPID:pve-east:0004", "Backup", "build-runner", now.minusSeconds(20))
        val summary = "POST /nodes/pve-east/qemu/7391/status/start?node=pve-east"
        val activity = TickerActivity(listOf(session()), tasks = listOf(task), kept = listOf(TickerConsole(guest, "build-runner")))
        val error = failing(summary = summary)
        fun words(model: TickerModel) = model.tiles.flatMap { listOf(it.label, it.headline, it.detail.orEmpty(), it.pill.orEmpty()) + it.links.map { l -> l.label } }.joinToString(" | ")
        for ((showcase, names) in listOf(false to false, true to true, true to false)) {
            val text = words(HoodTicker.build(input(vitals(sessions = listOf(TraceSource.Console)), metrics(errors = listOf(error)), activity = activity, showcase = showcase, names = names)))
            for (secret in listOf("build-runner", "pve-east", "pve.example.test", "7391", "121", "root@pam", "token")) assertFalse(secret in text, "'$secret' in tiles (showcase=$showcase, names=$names): $text")
        }
        // Showing is allowed only with Showcase off and a page that already shows the guest.
        val shown = words(HoodTicker.build(input(metrics = metrics(), activity = activity, showcase = false, names = true)))
        assertTrue("build-runner" in shown, shown)
        // Even then, the operation summary is general: names in its path are never carried.
        assertEquals("POST /nodes/…/qemu/…/status/start", generalSummary(summary))
        assertEquals("GET /cluster/resources", generalSummary("GET /cluster/resources?max-age=0"))
        assertEquals("Network discovery", generalSummary("Network discovery"))
    }

    @Test fun cuesFireOnlyForWhatChanged() {
        val base = TickerMarks(loaded = 1, latestErrorId = 5, running = setOf("scan"))
        assertEquals(emptySet(), HoodTicker.cues(null, base), "the first sample is only a baseline")
        assertEquals(emptySet(), HoodTicker.cues(base, base))
        assertEquals(setOf(TickerCue.FirstData), HoodTicker.cues(base.copy(loaded = 0), base))
        assertEquals(setOf(TickerCue.Error), HoodTicker.cues(base, base.copy(latestErrorId = 9)))
        assertEquals(emptySet(), HoodTicker.cues(base, base.copy(latestErrorId = null)), "an error aging out is not news")
        assertEquals(setOf(TickerCue.Started), HoodTicker.cues(base, base.copy(running = setOf("scan", "task:a"))))
        assertEquals(setOf(TickerCue.Finished), HoodTicker.cues(base, base.copy(running = emptySet())))
        assertEquals(setOf(TickerCue.BackOff), HoodTicker.cues(base, base.copy(backingOff = true)))
        assertEquals(emptySet(), HoodTicker.cues(base.copy(backingOff = true), base.copy(backingOff = true)))
        assertEquals(setOf(TickerCue.Started, TickerCue.Finished), HoodTicker.cues(base, base.copy(running = setOf("task:b"))))
    }

    @Test fun marksComeFromTheSample() {
        val model = HoodTicker.build(input(metrics = metrics(errors = listOf(failing(id = 44))), loop = TraceLiveLoop("backoff", now.plusSeconds(3), 5000, 10),
            activity = TickerActivity(listOf(session(), session(2, inView = false), session(3, phase = TickerSessionPhase.Loading)), scan = TickerScan(1, 0),
                tasks = listOf(TickerTask("UPID:x:1", "Backup")), uploading = true)))
        assertEquals(TickerMarks(loaded = 2, latestErrorId = 44, running = setOf("upload", "task:UPID:x:1", "scan"), backingOff = true), model.marks)
    }

    @Test fun everyTargetSaysWhereItLeads() {
        assertEquals("Open Under the Hood · Latency", TickerTarget.Hood(HoodFocus.Latency).describe())
        assertEquals("Open Under the Hood · PVE API stream", TickerTarget.Hood(HoodFocus.Stream(TraceSource.PveApi)).describe())
        assertEquals("Open Under the Hood · this operation", TickerTarget.Hood(HoodFocus.Operation(3)).describe())
        assertEquals("Open the VPN page", TickerTarget.Vpn(VpnKind.Tailscale).describe())
        assertEquals("Open the Scan page", TickerTarget.Scan.describe())
        assertEquals("Open Activity · this task", TickerTarget.Activity("UPID").describe())
        assertEquals("Open the guest's console", TickerTarget.Fleet(guest, console = true).describe())
        val all = listOf(HoodFocus.Engine, HoodFocus.Latency, HoodFocus.Requests, HoodFocus.Slowest, HoodFocus.Errors, HoodFocus.Network, HoodFocus.Sessions, HoodFocus.Stream(null))
        assertEquals(all.size, all.map { it.describe() }.distinct().size)
    }

    @Test fun theModelAddsNothingToTheTrace() {
        val before = Trace.snapshot().size
        HoodTicker.build(input(metrics = metrics(errors = listOf(failing())), activity = TickerActivity(listOf(session()), scan = TickerScan(1, 1))))
        assertEquals(before, Trace.snapshot().size)
        assertEquals(Duration.ZERO, Duration.ZERO)
    }
}
