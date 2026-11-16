package local.ringleader.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** What the engine asks of the network, answered from tables and timed on request. Records what was asked, in order, and how many were open at once. */
private class FakeProber(
    private val reach: (String, Int) -> Reach = { _, _ -> Reach.Silent },
    private val hits: (String, Int) -> List<ScanHit> = { ip, port -> listOf(ScanHit(ip, port, true, ScanKind.WEB)) },
    private val reachMs: (String, Int) -> Long = { _, _ -> 0 },
    private val probeMs: (String, Int) -> Long = { _, _ -> 0 },
) : ScanProber {
    val events = CopyOnWriteArrayList<String>()
    val timeouts = CopyOnWriteArrayList<Pair<String, Int>>()
    private val reaching = AtomicInteger(); private val probing = AtomicInteger()
    val peakReach = AtomicInteger(); val peakProbe = AtomicInteger()
    val peakReachBy = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>(); private val reachingBy = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
    val started = AtomicInteger(); val cancelled = AtomicInteger()
    private fun peak(now: AtomicInteger, peak: AtomicInteger) = peak.accumulateAndGet(now.get()) { a, b -> maxOf(a, b) }
    override suspend fun reach(ip: String, port: Int, timeoutMs: Int): Reach {
        events += "reach $ip:$port"; timeouts += ip to timeoutMs; started.incrementAndGet()
        val group = ip.substringBeforeLast('.')
        peak(reaching.also { it.incrementAndGet() }, peakReach)
        peak(reachingBy.getOrPut(group) { AtomicInteger() }.also { it.incrementAndGet() }, peakReachBy.getOrPut(group) { AtomicInteger() })
        try { val ms = reachMs(ip, port); if (ms > 0) delay(ms); return reach(ip, port) }
        catch (e: CancellationException) { cancelled.incrementAndGet(); throw e }
        finally { reaching.decrementAndGet(); reachingBy.getValue(group).decrementAndGet() }
    }
    override suspend fun probe(ip: String, port: Int): List<ScanHit> {
        events += "probe $ip:$port"
        peak(probing.also { it.incrementAndGet() }, peakProbe)
        try { val ms = probeMs(ip, port); if (ms > 0) delay(ms); return hits(ip, port) } finally { probing.decrementAndGet() }
    }
    fun reached(ip: String) = events.filter { it.startsWith("reach $ip:") }.map { it.substringAfterLast(':').toInt() }
    fun probed(ip: String) = events.filter { it.startsWith("probe $ip:") }.map { it.substringAfterLast(':').toInt() }
}

private fun net(base: String, hosts: IntRange, onLink: Boolean, ports: List<Int>? = null, cidr: String = "$base.0/24") =
    ScanNetwork(ScanInterface(cidr, "$base.1", 24), cidr, null, hosts.map { "$base.$it" }, realCidr = cidr, onLink = onLink, ports = ports)

private fun plan(vararg networks: ScanNetwork) = NetworkPlan(networks.toList())
private val fast = ScanTuning(settleMs = 0, publishEveryMs = 0)

private fun run(plan: NetworkPlan, prober: ScanProber, neighbours: NeighbourSource = NeighbourSource.NONE, tuning: ScanTuning = fast, origins: List<String> = emptyList(),
    known: List<String> = emptyList(), concurrency: Int = 24, ports: List<Int> = LAN_SCAN_PORTS, connectTimeoutMs: Int = LAN_CONNECT_TIMEOUT_MS): List<NetworkProgress> =
    runBlocking { withTimeout(60_000) { ScanEngine(prober, neighbours, tuning, connectTimeoutMs).run(plan, origins, known, concurrency, ports).toList() } }

/**
 * The sweep: what it asks, in which order, of whom, how many at once, and what it does when stopped. All with a prober that answers
 * from tables; the real sockets have their own tests at the bottom. Addresses are 10.20.x.x documentation of the rules, and nothing
 * here leaves the process.
 */
class ScanEngineTest {
    // ---- what is asked ------------------------------------------------------------------------------------------------------

    @Test fun theCheapPassAsksTheProxmoxPortsOfEveryAddressAndNothingMoreOfASilentOne() {
        val prober = FakeProber()
        val out = run(plan(net("10.20.30", 1..6, onLink = false)), prober)
        for (i in 1..6) { assertEquals(listOf(8006, 8007, 8443), prober.reached("10.20.30.$i").sorted()); assertEquals(emptyList(), prober.probed("10.20.30.$i")) }
        assertEquals(18, prober.events.size)
        assertTrue(out.last().finished); assertEquals(6, out.last().scanned); assertEquals(0, out.last().hits.size)
        // The cheap pass of one address starts the ports in their order.
        assertEquals(listOf(8006, 8007, 8443), prober.reached("10.20.30.1"))
    }

    @Test fun aHostThatAnsweredGetsItsOtherPortsAndASilentOneDoesNot() {
        val prober = FakeProber(reach = { ip, port -> when { ip == "10.20.30.3" && port == 8006 -> Reach.Open; ip == "10.20.30.4" && port == 8443 -> Reach.Refused; ip == "10.20.30.3" && port == 443 -> Reach.Open; else -> Reach.Silent } },
            hits = { ip, port -> listOf(ScanHit(ip, port, true, if (port == 8006) ScanKind.PVE else ScanKind.WEB)) })
        val out = run(plan(net("10.20.30", 1..6, onLink = false)), prober)
        assertEquals((PROXMOX_PORTS + listOf(80, 443, 3000, 8123)).sorted(), prober.reached("10.20.30.3").sorted())
        assertEquals((PROXMOX_PORTS + listOf(80, 443, 3000, 8123)).sorted(), prober.reached("10.20.30.4").sorted(), "a reset proves a live host")
        for (silent in listOf(1, 2, 5, 6)) assertEquals(PROXMOX_PORTS.sorted(), prober.reached("10.20.30.$silent").sorted())
        assertEquals(listOf(8006, 443).sorted(), prober.probed("10.20.30.3").sorted(), "only a port that opened is read"); assertEquals(emptyList(), prober.probed("10.20.30.4"))
        assertEquals(listOf("10.20.30.3"), out.last().hits.map { it.ip }.distinct()); assertEquals(2, out.last().alive)
        // The Proxmox card is not hidden by the plain web page of the same host.
        assertEquals(listOf(ScanKind.PVE), out.last().hits.map { it.kind })
    }

    @Test fun knownHostsAndSavedServersAreAskedFirstAndOnEveryPort() {
        val prober = FakeProber()
        run(plan(net("10.20.30", 1..8, onLink = true)), prober, origins = listOf("https://10.20.30.5:8006", "https://pve.example.test:8006"), known = listOf("10.20.30.4", "10.99.0.1"))
        val first = prober.events.take(14).map { it.substringAfter(' ').substringBeforeLast(':') }
        assertEquals(setOf("10.20.30.4", "10.20.30.5"), first.toSet(), "the known hosts come before any other address: ${prober.events.take(16)}")
        for (known in listOf(4, 5)) assertEquals(LAN_SCAN_PORTS.sorted(), prober.reached("10.20.30.$known").sorted())
        // Each is asked once on each port: the cheap pass leaves it out. An address outside the plan or a host name is not asked at all.
        assertEquals(7, prober.events.count { it.contains("10.20.30.4:") }); assertTrue(prober.events.none { it.contains("10.99.0.1") })
        assertEquals(7, prober.events.count { it.contains("10.20.30.5:") })
    }

    @Test fun theNeighbourTableDecidesWhoOnAnAttachedNetworkGetsAllItsPorts() {
        val calls = AtomicInteger()
        // Empty before the sweep, filled after it: the system learns the live hosts from the connects themselves.
        val table = NeighbourSource { if (calls.incrementAndGet() == 1) emptySet() else setOf("10.20.30.2", "10.20.30.5", "10.20.30.250", "10.20.99.9") }
        val prober = FakeProber()
        val out = run(plan(net("10.20.30", 1..9, onLink = true)), prober, table)
        for (live in listOf(2, 5)) assertEquals(LAN_SCAN_PORTS.sorted(), prober.reached("10.20.30.$live").sortedDistinct(), "the table's hosts get every port")
        for (silent in listOf(1, 3, 4, 6, 7, 8, 9)) assertEquals(PROXMOX_PORTS.sorted(), prober.reached("10.20.30.$silent").sorted(), "the others only the Proxmox ports")
        assertTrue(prober.events.none { it.contains("10.20.30.250") || it.contains("10.20.99.9") }, "an address that is not on the plan is not asked")
        assertTrue(out.last().notes.isEmpty()); assertEquals(2, calls.get())
        assertTrue(out.any { it.phase == ScanPhase.Settling } && out.any { it.phase == ScanPhase.Probing } && out.last().phase == ScanPhase.Done)
    }

    private fun List<Int>.sortedDistinct() = sorted().distinct()

    @Test fun theNeighboursAlreadyKnownBeforeTheSweepAreReadFirst() {
        val table = NeighbourSource { setOf("10.20.30.2", "10.20.30.5") }
        val prober = FakeProber()
        run(plan(net("10.20.30", 1..9, onLink = true)), prober, table)
        assertEquals(7, prober.events.count { it.contains("10.20.30.2:") }, "asked once on every port, before the sweep")
        assertEquals(prober.events.take(14).map { it.substringAfter(' ').substringBeforeLast(':') }.toSet(), setOf("10.20.30.2", "10.20.30.5"))
    }

    @Test fun whenThereIsNoNeighbourTableTheOtherPortsOfEveryAddressAreTriedAndTheScanSaysSo() {
        val prober = FakeProber()
        val out = run(plan(net("10.20.30", 1..5, onLink = true)), prober, NeighbourSource.NONE)
        for (i in 1..5) assertEquals(LAN_SCAN_PORTS.sorted(), prober.reached("10.20.30.$i").sorted())
        assertEquals(1, out.last().notes.size); assertTrue(out.last().notes.single().contains("neighbour table could not be read"))
        // The same when reading it fails.
        val failing = run(plan(net("10.20.30", 1..2, onLink = true)), FakeProber(), NeighbourSource { error("no arp") })
        assertEquals(1, failing.last().notes.size); assertTrue(failing.last().finished)
    }

    @Test fun acrossARouterTheNeighbourTableIsNeverAsked() {
        val asked = AtomicInteger()
        val prober = FakeProber()
        run(plan(net("10.20.30", 1..6, onLink = false)), prober, NeighbourSource { asked.incrementAndGet(); (1..6).map { "10.20.30.$it" }.toSet() })
        assertEquals(0, asked.get()); assertTrue(prober.events.none { it.endsWith(":80") || it.endsWith(":443") })
    }

    @Test fun aNetworkWithItsOwnPortsKeepsThemAndAListOfPortsIsHonoured() {
        val prober = FakeProber(reach = { ip, port -> if (ip == "10.20.30.2" && port == 8006) Reach.Open else Reach.Silent })
        run(plan(net("10.20.30", 1..3, onLink = false, ports = PROXMOX_PORTS), net("10.20.40", 1..2, onLink = false)), prober, ports = listOf(8006, 80))
        assertEquals(PROXMOX_PORTS.sorted(), prober.reached("10.20.30.2").sorted(), "no other port for a network that asked for the Proxmox ports only")
        assertEquals(listOf(8006), prober.reached("10.20.40.1"), "the scan's own ports when the network names none: 8006 is the Proxmox one, 80 follows only for a host that answered")
        // A list of ports with no Proxmox port is all quick.
        val p2 = FakeProber()
        run(plan(net("10.20.30", 1..2, onLink = false, ports = listOf(8123))), p2)
        assertEquals(listOf(8123), p2.reached("10.20.30.1"))
    }

    @Test fun anAddressNamedByTwoNetworksIsAskedOnce() {
        val prober = FakeProber()
        val out = run(plan(net("10.20.30", 1..4, onLink = false), net("10.20.30", 3..6, onLink = false, cidr = "10.20.30.0/25")), prober)
        for (i in 1..6) assertEquals(3, prober.reached("10.20.30.$i").size, "10.20.30.$i")
        assertEquals(6, out.last().scanned); assertEquals(6, out.last().total)
        assertEquals(listOf(4, 2), out.last().tallies.map { it.total })
    }

    @Test fun aPlanWithNoAddressesFinishesAtOnce() {
        val out = run(NetworkPlan(listOf(ScanNetwork(ScanInterface("lo", "127.0.0.1", 8), null, "Loopback"))), FakeProber())
        assertEquals(2, out.size); assertTrue(out.last().finished); assertEquals(ScanPhase.Done, out.last().phase); assertEquals(0, out.last().total)
        assertTrue(run(NetworkPlan(emptyList()), FakeProber()).last().finished)
    }

    // ---- how many at once, and how fast ---------------------------------------------------------------------------------------

    @Test fun theConnectsInFlightAreBoundedAndTheFullProbesHaveTheirOwnBound() {
        val prober = FakeProber(reach = { ip, port -> if (port == 8006 && ip.endsWith("0")) Reach.Open else Reach.Silent }, reachMs = { _, _ -> 12 }, probeMs = { _, _ -> 12 })
        run(plan(net("10.20.30", 1..120, onLink = false)), prober, tuning = ScanTuning(quickInFlight = 10, perNetworkFloor = 2, settleMs = 0, publishEveryMs = 0), concurrency = 3)
        assertTrue(prober.peakReach.get() in 5..10, "at most the connects in flight allowed: ${prober.peakReach}")
        assertTrue(prober.peakProbe.get() in 1..3, "at most the full probes allowed: ${prober.peakProbe}")
        assertFailsWith<IllegalArgumentException> { ScanTuning(quickInFlight = MAX_QUICK_IN_FLIGHT + 1) }
        assertFailsWith<IllegalArgumentException> { ScanTuning(quickInFlight = 0) }
        assertEquals(128, MAX_QUICK_IN_FLIGHT); assertEquals(128, ScanTuning().quickInFlight)
    }

    /** The point of the per-network share: a network of silent, slow addresses holds its own share and not the others'. */
    @Test fun aSlowNetworkCannotStarveTheOthers() {
        val prober = FakeProber(reachMs = { ip, _ -> if (ip.startsWith("10.20.31.")) 120 else 1 })
        val tuning = ScanTuning(quickInFlight = 16, perNetworkFloor = 2, settleMs = 0, publishEveryMs = 0)
        val out = run(plan(net("10.20.31", 1..60, onLink = false), net("10.20.32", 1..30, onLink = false)), prober, tuning = tuning)
        assertTrue(prober.peakReachBy.getValue("10.20.31").get() <= 8, "the slow network holds at most its share of 16: ${prober.peakReachBy}")
        assertTrue(prober.peakReach.get() <= 16)
        val fastDoneAt = out.indexOfFirst { p -> p.tallies.firstOrNull { it.id == "10.20.32.0/24" }?.let { it.scanned == it.total } == true }
        assertTrue(fastDoneAt >= 0)
        val slowThen = out[fastDoneAt].tallies.first { it.id == "10.20.31.0/24" }
        assertTrue(slowThen.scanned < slowThen.total, "the fast network is finished while the slow one still has work: $slowThen")
        assertTrue(out.last().finished && out.last().scanned == 90)
    }

    @Test fun theQuickWaitFollowsTheKindOfNetworkAndTheScannersOwnTimeout() {
        val prober = FakeProber()
        run(plan(net("10.20.30", 1..2, onLink = true), net("10.20.40", 1..2, onLink = false)), prober, NeighbourSource { emptySet() })
        assertEquals(setOf(300), prober.timeouts.filter { it.first.startsWith("10.20.30.") }.map { it.second }.toSet())
        assertEquals(setOf(800), prober.timeouts.filter { it.first.startsWith("10.20.40.") }.map { it.second }.toSet())
        // A scanner built for far-away hosts (the tailnet's) waits as long as it was told.
        val far = FakeProber()
        run(plan(net("10.20.40", 1..2, onLink = false)), far, connectTimeoutMs = TAILNET_CONNECT_TIMEOUT_MS)
        assertEquals(setOf(1500), far.timeouts.map { it.second }.toSet())
    }

    @Test fun newConnectionsAcrossARouterArePacedAndOnesAttachedAreNot() {
        var now = 0L
        val waits = mutableListOf<Long>()
        val pacer = Pacer(500, { now }, { ns -> waits += ns })
        runBlocking { repeat(5) { pacer.acquire() } }
        assertEquals(listOf(2_000_000L, 4_000_000L, 6_000_000L, 8_000_000L), waits, "at most 500 a second: one every 2 ms")
        now += 1_000_000_000L; waits.clear()
        runBlocking { pacer.acquire(); pacer.acquire() }
        assertEquals(listOf(2_000_000L), waits, "after a quiet second the next one is free")
        // The three ports of one address take three connections' worth at once; the next address waits for them.
        now += 1_000_000_000L; waits.clear()
        runBlocking { pacer.acquire(3); pacer.acquire(3); pacer.acquire(3) }
        assertEquals(listOf(6_000_000L, 12_000_000L), waits)
        // The engine gives routed networks a pace and attached ones none.
        val paced = AtomicInteger()
        val engineRun = { onLink: Boolean ->
            paced.set(0)
            runBlocking { ScanEngine(FakeProber(), NeighbourSource.NONE, fast, 400, newPacer = { p -> Pacer(p, { 0L }, { paced.incrementAndGet() }) }).run(plan(net("10.20.30", 1..20, onLink = onLink)), emptyList(), emptyList(), 24, LAN_SCAN_PORTS).toList() }
            paced.get()
        }
        assertTrue(engineRun(false) > 0); assertEquals(0, engineRun(true))
    }

    // ---- results and progress -------------------------------------------------------------------------------------------------

    @Test fun resultsAppearInTheOrderTheyAreFoundAndAreSortedOnceAtTheEnd() {
        val prober = FakeProber(reach = { ip, port -> if (port == 8006 && (ip == "10.20.30.9" || ip == "10.20.30.2")) Reach.Open else Reach.Silent },
            hits = { ip, port -> listOf(ScanHit(ip, port, true, ScanKind.PVE)) }, probeMs = { ip, _ -> if (ip == "10.20.30.9") 20 else 150 })
        val out = run(plan(net("10.20.30", 1..9, onLink = false)), prober)
        val shown = out.map { it.hits.map { h -> h.ip } }.filter { it.isNotEmpty() }
        assertEquals(listOf("10.20.30.9"), shown.first(), "the first card is the first to arrive")
        assertTrue(shown.any { it == listOf("10.20.30.9", "10.20.30.2") }, "the later one is added below it: $shown")
        // Nothing already shown moves, until the scan ends.
        for ((a, b) in out.dropLast(1).zipWithNext()) assertEquals(a.hits, b.hits.take(a.hits.size), "an earlier card moved")
        assertEquals(listOf("10.20.30.2", "10.20.30.9"), out.last().hits.map { it.ip }, "sorted once, at the end")
        assertTrue(out.last().finished && out.dropLast(1).none { it.finished })
    }

    @Test fun progressIsPerNetworkAndAddsUp() {
        val out = run(plan(net("10.20.30", 1..10, onLink = false), net("10.20.40", 1..5, onLink = false, cidr = "10.20.40.0/28")), FakeProber(reachMs = { _, _ -> 2 }))
        for (p in out) { assertEquals(p.scanned, p.tallies.sumOf { it.scanned }, "the networks add up to the whole"); assertTrue(p.tallies.all { it.scanned <= it.total }) }
        assertEquals(listOf(NetworkTally("10.20.30.0/24", 10, 10, done = true), NetworkTally("10.20.40.0/28", 5, 5, done = true)), out.last().tallies)
        assertTrue(out.first().tallies.none { it.done }, "nothing is done before anything was asked")
        assertEquals(listOf(0, 0), out.first().tallies.map { it.scanned }); assertEquals(0, out.first().scanned)
        assertTrue(out.map { it.scanned }.zipWithNext().all { (a, b) -> b >= a }, "progress never goes back")
    }

    /** What the page draws from: each network's own counts, the three steps, and every host that proved to be there with how and when. */
    @Test fun eachNetworkSaysWhatAnsweredWhatIsBeingReadAndWhenItIsDone() {
        var now = 0L
        val prober = FakeProber(
            reach = { ip, port -> when { ip == "10.20.30.3" && port == 8006 -> Reach.Open; ip == "10.20.30.3" && port == 443 -> Reach.Open; ip == "10.20.30.4" && port == 8443 -> Reach.Refused
                ip == "10.20.40.2" && port == 8007 -> Reach.Open; else -> Reach.Silent } },
            hits = { ip, port -> listOf(ScanHit(ip, port, true, when (port) { 8006 -> ScanKind.PVE; 8007 -> ScanKind.PBS; else -> ScanKind.WEB })) })
        val out = runBlocking { ScanEngine(prober, NeighbourSource.NONE, fast, 400, nanoTime = { now += 1_000_000; now }).run(
            plan(net("10.20.30", 1..6, onLink = false), net("10.20.40", 1..3, onLink = false)), emptyList(), emptyList(), 24, LAN_SCAN_PORTS).toList() }
        val end = out.last()
        assertEquals(listOf(NetworkTally("10.20.30.0/24", 6, 6, answered = 2, deep = 2, deepDone = 2, services = 1, done = true),
            NetworkTally("10.20.40.0/24", 3, 3, answered = 1, deep = 1, deepDone = 1, services = 1, done = true)), end.tallies)
        assertEquals(ScanSteps(StepCount(0, 0), StepCount(9, 9), StepCount(3, 3)), end.steps)
        // Every host that answered is a finding, with the network it is in, how it was learnt and when; a reset is a host with no service.
        assertEquals(setOf("10.20.30.3", "10.20.30.4", "10.20.40.2"), end.findings.map { it.ip }.toSet()); assertEquals(3, end.alive)
        assertTrue(end.findings.all { it.by == FoundBy.Sweep && it.atMs > 0 && it.atMs <= end.elapsedMs }, end.findings.toString())
        assertEquals("10.20.40.0/24", end.findings.first { it.ip == "10.20.40.2" }.network)
        assertEquals(emptyList(), end.findings.first { it.ip == "10.20.30.4" }.services)
        // The services of a finding are the ones the list shows: the Proxmox card took the place of the host's plain web page.
        val three = end.findings.first { it.ip == "10.20.30.3" }
        assertEquals(listOf(8006 to ScanKind.PVE), three.services.map { it.port to it.kind }); assertTrue(three.services.all { it.atMs >= three.atMs })
        assertEquals(end.hits.map { it.ip to it.port }.toSet(), end.findings.flatMap { f -> f.services.map { f.ip to it.port } }.toSet())
        // While it runs: counts never go back, a network is done only when nothing more will be asked of it, and the findings only grow.
        for ((a, b) in out.zipWithNext()) {
            assertTrue(b.elapsedMs >= a.elapsedMs && b.findings.size >= a.findings.size && b.findings.map { it.ip }.take(a.findings.size) == a.findings.map { it.ip })
            for ((x, y) in a.tallies.zip(b.tallies)) assertTrue(y.answered >= x.answered && y.deep >= x.deep && y.deepDone >= x.deepDone && (!x.done || y.done), "$x then $y")
        }
        for (p in out) for (t in p.tallies) { assertTrue(t.deepDone <= t.deep && t.answered <= t.total, t.toString()); if (t.done) assertTrue(t.scanned == t.total && t.deepDone == t.deep, t.toString()) }
    }

    @Test fun aKnownServerCountsAsThereOnlyWhenItAnswersAndANeighbourAtOnce() {
        // 10.20.30.4 is saved and silent, 10.20.30.5 is saved and answers, 10.20.30.2 is in this computer's neighbour table from the start.
        val prober = FakeProber(reach = { ip, port -> if (ip == "10.20.30.5" && port == 8006) Reach.Open else Reach.Silent }, hits = { ip, port -> listOf(ScanHit(ip, port, true, ScanKind.PVE)) })
        val out = run(plan(net("10.20.30", 1..8, onLink = true)), prober, NeighbourSource { setOf("10.20.30.2") }, known = listOf("10.20.30.4", "10.20.30.5"))
        val end = out.last()
        assertEquals(mapOf("10.20.30.2" to FoundBy.Neighbour, "10.20.30.5" to FoundBy.Known), end.findings.associate { it.ip to it.by }, "a saved server that is off is not a device that answered")
        assertEquals(2, end.alive)
        assertEquals(ScanSteps(known = StepCount(3, 3), quick = StepCount(5, 5), deep = StepCount(0, 0)), end.steps)
        assertEquals(NetworkTally("10.20.30.0/24", 8, 8, answered = 2, deep = 3, deepDone = 3, services = 1, done = true), end.tallies.single())
        // On an attached network the neighbour table is still to be asked after the cheap pass: the network is not done before that.
        assertTrue(out.filter { it.phase == ScanPhase.Sweeping }.none { it.tallies.single().done })
        // A neighbour named only after the cheap pass belongs to the deep step.
        val calls = AtomicInteger()
        val later = run(plan(net("10.20.30", 1..4, onLink = true)), FakeProber(), NeighbourSource { if (calls.incrementAndGet() == 1) emptySet() else setOf("10.20.30.3") }).last()
        assertEquals(ScanSteps(StepCount(0, 0), StepCount(4, 4), StepCount(1, 1)), later.steps); assertEquals(listOf(FoundBy.Neighbour), later.findings.map { it.by })
    }

    @Test fun aSavedServerIsMarkedAndTheHostsOfSavedOriginsAreNotProbedTwice() {
        val prober = FakeProber(reach = { ip, port -> if (ip == "10.20.30.4" && port == 8006) Reach.Open else Reach.Silent }, hits = { ip, port -> listOf(ScanHit(ip, port, true, ScanKind.PVE)) })
        val out = run(plan(net("10.20.30", 1..6, onLink = false)), prober, origins = listOf("https://10.20.30.4:8006"))
        assertTrue(out.last().hits.single().saved)
        assertEquals(1, prober.probed("10.20.30.4").count { it == 8006 })
    }

    // ---- stopping --------------------------------------------------------------------------------------------------------------

    @Test fun cancellingStopsEveryConnectAtOnce() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val prober = object : ScanProber {
            val live = AtomicInteger(); val closed = AtomicInteger()
            override suspend fun reach(ip: String, port: Int, timeoutMs: Int): Reach { live.incrementAndGet(); started.complete(Unit); try { awaitCancellation() } finally { closed.incrementAndGet() } }
            override suspend fun probe(ip: String, port: Int): List<ScanHit> = emptyList()
        }
        val job = launch(Dispatchers.Default) { ScanEngine(prober, NeighbourSource.NONE, fast, 400).run(plan(net("10.20.30", 1..200, onLink = true)), emptyList(), emptyList(), 24, LAN_SCAN_PORTS).collect() }
        started.await(); delay(100)
        assertTrue(prober.live.get() > 10)
        val stopped = System.nanoTime()
        withTimeout(2000) { job.cancelAndJoin() }
        assertTrue((System.nanoTime() - stopped) / 1_000_000 < 1500)
        assertEquals(prober.live.get(), prober.closed.get(), "every connect that was started was closed")
        val live = prober.live.get()
        delay(200); assertEquals(live, prober.live.get(), "nothing new is started once it is cancelled")
    }

    @Test fun cancellingDuringTheSettleStopsAtOnce() = runBlocking {
        val prober = FakeProber()
        val reached = CompletableDeferred<Unit>()
        val table = NeighbourSource { reached.complete(Unit); emptySet() }
        val job = launch(Dispatchers.Default) { ScanEngine(prober, table, ScanTuning(settleMs = 60_000), 400).run(plan(net("10.20.30", 1..3, onLink = true)), emptyList(), emptyList(), 24, LAN_SCAN_PORTS).collect() }
        reached.await(); delay(200)
        withTimeout(2000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }

    // ---- arrival order of the merge ----------------------------------------------------------------------------------------------

    @Test fun mergingInArrivalOrderGivesTheSameSetAsTheSortedMerge() {
        val random = java.util.Random(7)
        val kinds = ScanKind.entries
        repeat(200) {
            val hits = (0 until random.nextInt(14)).map { ScanHit("10.20.30.${random.nextInt(4)}", listOf(8006, 443, 80, 3000)[random.nextInt(4)], random.nextBoolean(), kinds[random.nextInt(kinds.size)]) }
            val incremental = hits.fold(emptyList<ScanHit>()) { acc, h -> ScanRules.mergeArrival(acc, listOf(h)) }
            val batched = ScanRules.mergeArrival(emptyList(), hits)
            assertEquals(ScanRules.merge(hits), ScanRules.merge(incremental), hits.toString())
            assertEquals(ScanRules.merge(hits), ScanRules.merge(batched), hits.toString())
        }
        // A named hit takes the place of the host's web page, and the secure copy wins without moving.
        val web = ScanHit("10.20.30.1", 80, false, ScanKind.WEB); val pve = ScanHit("10.20.30.1", 8006, true, ScanKind.PVE)
        assertEquals(listOf(pve), ScanRules.mergeArrival(listOf(web), listOf(pve)))
        assertEquals(listOf(pve), ScanRules.mergeArrival(listOf(pve), listOf(web)))
        val other = ScanHit("10.20.30.2", 80, false, ScanKind.WEB)
        assertEquals(listOf(other, web.copy(secure = true, port = 443)), ScanRules.mergeArrival(listOf(other, web), listOf(web.copy(secure = true, port = 443))))
    }

    // ---- the real sockets, on this computer only ------------------------------------------------------------------------------------

    /** The engine's one assumption about threads: a limited view of the IO dispatcher really runs 128 blocking connects at once (the pool's own limit is 64). */
    @Test fun theConnectDispatcherRunsAsManyBlockingConnectsAsAreAllowed() = runBlocking {
        val view = Dispatchers.IO.limitedParallelism(MAX_QUICK_IN_FLIGHT)
        val running = AtomicInteger(); val peak = AtomicInteger()
        coroutineScope { repeat(MAX_QUICK_IN_FLIGHT) { launch(view) { peak.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }; Thread.sleep(250); running.decrementAndGet() } } }
        assertTrue(peak.get() >= 100, "only ${peak.get()} blocking tasks ran at once")
    }

    @Test fun theConnectProbeTellsOpenFromRefusedAndStopsAtOnceWhenCancelled() = runBlocking {
        val prober = SocketProber(400, scanTlsContext(), Dispatchers.IO.limitedParallelism(8))
        java.net.ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            assertEquals(Reach.Open, prober.reach("127.0.0.1", server.localPort, 500))
        }
        val closed = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        assertEquals(Reach.Refused, prober.reach("127.0.0.1", closed, 500), "a reset is a live host")
        // Nothing answers a documentation address: a timeout, or no route, which is silence; a network that answers for it is not an open port.
        assertNotEquals(Reach.Open, prober.reach("192.0.2.1", 8006, 300))
        val started = System.nanoTime()
        val job = launch(Dispatchers.Default) { prober.reach("192.0.2.1", 8006, 30_000) }
        delay(250)
        withTimeout(5000) { job.cancelAndJoin() }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 4000, "the blocked connect was closed, not waited out")
    }

    @Test fun noScanSweepsMoreThanThePlannerAllows() {
        val big = NetworkPlan(listOf(ScanNetwork(ScanInterface("big", "10.20.0.1", 16), "10.20.0.0/16", null, (0 until MAX_SCAN_ADDRESSES + 1).map { "10.${it / 65536}.${it / 256 % 256}.${it % 256}" })))
        val failure = assertFailsWith<IllegalArgumentException> { runBlocking { NetworkScanner(400) { _, _ -> emptyList() }.scan(big).toList() } }
        assertTrue(failure.message!!.contains("4096"))
    }
}
