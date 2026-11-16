package local.ringleader.core

import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

// The sweep, in the order that finds things soonest and asks the network for the least:
//
//   1. Known hosts first: the servers the app already knows and the neighbours this computer's own table lists. Their cards
//      appear within about a second.
//   2. One cheap pass: a bare TCP connect on the Proxmox ports (8006, 8007, 8443) of every other address, up to 128 at a time. An
//      answer (open, or refused: a host that sends a reset is there) puts the host on the list at once, and an open port is read
//      the moment it opens.
//   3. On a network this computer is attached to, the neighbour table says which addresses are alive; their remaining ports are
//      tried. When the table cannot be read, every address's remaining ports are tried instead (the old, slow, complete way).
//      Across a router or a tunnel there is no neighbour table: only hosts that answered the cheap pass get their other ports.
//
// Results appear as they are found, in the order found; the list is sorted once, at the end. Nothing here trusts what it sees:
// the sockets are the discovery sockets of [probeScanPort] (a private accept-anything TLS context per scan, no credentials,
// redirects never followed), and a connect that opens is closed again unread.

/** The most connects open at once. The sweep never asks for more, whatever it is told. */
const val MAX_QUICK_IN_FLIGHT = 128

/** How a scan paces itself. Every number is a limit, not a target. */
data class ScanTuning(
    /** Connects open at once, over all networks. At most [MAX_QUICK_IN_FLIGHT]. */
    val quickInFlight: Int = MAX_QUICK_IN_FLIGHT,
    /** How long the cheap pass waits for a connect on a network this computer is attached to. */
    val onLinkQuickMs: Int = 300,
    /** And across a router or a tunnel. */
    val routedQuickMs: Int = 800,
    /** New connections a second across a router or a tunnel, over all such networks. */
    val routedPerSecond: Int = 500,
    /** How long after the cheap pass the system is given to fill its neighbour table. */
    val settleMs: Long = 1200,
    /** A network never gets fewer than this many of the connects (when there are many networks). */
    val perNetworkFloor: Int = 16,
    /** Progress is sent at most this often while nothing new is found. */
    val publishEveryMs: Long = 50,
) {
    init {
        require(quickInFlight in 1..MAX_QUICK_IN_FLIGHT) { "At most $MAX_QUICK_IN_FLIGHT connects at once." }
        require(routedPerSecond in 1..1000 && perNetworkFloor >= 1)
    }
}

/** How one cheap connect ended. */
internal enum class Reach { Open, Refused, Silent }

/** What the engine needs from the network. The real one opens sockets; a test's answers from a table. */
internal interface ScanProber {
    /** A bare TCP connect within [timeoutMs]. [Reach.Refused] (a reset) proves a live host; silence proves nothing. */
    suspend fun reach(ip: String, port: Int, timeoutMs: Int): Reach
    /** The full exchange on a port that opened: connect, TLS if the port takes it, one request, the answer classified. */
    suspend fun probe(ip: String, port: Int): List<ScanHit>
}

/** The old per-port probe as a prober: its answer for a port is both the cheap check (hits found: open) and the full exchange. */
internal class LambdaProber(private val fn: suspend (String, Int) -> List<ScanHit>) : ScanProber {
    private val cache = ConcurrentHashMap<Pair<String, Int>, List<ScanHit>>()
    override suspend fun reach(ip: String, port: Int, timeoutMs: Int): Reach {
        val hits = fn(ip, port)
        if (hits.isEmpty()) return Reach.Silent
        cache[ip to port] = hits
        return Reach.Open
    }
    override suspend fun probe(ip: String, port: Int): List<ScanHit> = cache.remove(ip to port) ?: fn(ip, port)
}

/** The discovery sockets. [io] must allow as many blocked threads as there are connects in flight. */
internal class SocketProber(private val connectTimeoutMs: Int, private val tls: SSLContext, private val io: CoroutineDispatcher) : ScanProber {
    override suspend fun reach(ip: String, port: Int, timeoutMs: Int): Reach = withContext(io) {
        val socket = Socket()
        // Close as soon as cancellation is requested, including during a blocking connect.
        val closer = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { runCatching { socket.close() } } }
        try {
            ensureActive()
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            Reach.Open
        } catch (e: CancellationException) { throw e }
        // A reset is a ConnectException (refused). A silent address ends in the connect timeout; "no route" and the like are other socket errors.
        catch (e: ConnectException) { currentCoroutineContext().ensureActive(); if (e.message?.contains("timed out", ignoreCase = true) == true) Reach.Silent else Reach.Refused }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); Reach.Silent }
        finally { closer.cancel(); runCatching { socket.close() } }
    }
    override suspend fun probe(ip: String, port: Int): List<ScanHit> = probeScanPort(ip, port, connectTimeoutMs, tls)
}

/** At most [perSecond] connections a second are let through [acquire]. The clock and the wait are replaceable for a test. */
internal class Pacer(perSecond: Int, private val nanoTime: () -> Long = System::nanoTime, private val wait: suspend (Long) -> Unit = { ns -> delay((ns + 999_999) / 1_000_000) }) {
    private val interval = 1_000_000_000L / perSecond
    private val lock = Mutex()
    private var next = 0L
    /** Takes [count] connections' worth of the pace in one go, so the ports of one address start together, in their order. */
    suspend fun acquire(count: Int = 1) {
        val waitNs = lock.withLock {
            val now = nanoTime()
            val at = maxOf(next, now)
            next = at + interval * count
            at - now
        }
        if (waitNs > 0) wait(waitNs)
    }
}

enum class ScanPhase { Sweeping, Settling, Probing, Done }

/**
 * How far one network is. [id] is its swept CIDR, or the source's name for a list of addresses. [scanned] of [total] addresses have
 * had their cheap pass (a known host: every port). [answered] of them proved to be there: a port opened, a reset came back, or this
 * computer's neighbour table lists them. [deep] hosts are having, and [deepDone] have had, every port tried; [services] is what was
 * read on this network so far. [done]: nothing more will be asked of this network.
 */
data class NetworkTally(val id: String, val scanned: Int, val total: Int, val answered: Int = 0, val deep: Int = 0, val deepDone: Int = 0,
    val services: Int = 0, val done: Boolean = false)

/** One step of a sweep: [done] of [total] addresses. */
data class StepCount(val done: Int = 0, val total: Int = 0)

/**
 * The three steps of a sweep, over all networks. [known]: the servers the app already knew and the neighbours this computer already
 * listed, asked on every port. [quick]: the cheap pass over every other address. [deep]: every port of the hosts that answered the
 * cheap pass or that the neighbour table named afterwards; its total grows while the scan runs. Known hosts and the cheap pass run
 * side by side, so these are counts, not a sequence.
 */
data class ScanSteps(val known: StepCount = StepCount(), val quick: StepCount = StepCount(), val deep: StepCount = StepCount())

/** How the scan learnt that a host is there. */
enum class FoundBy {
    /** A server the app already knew (saved, open, or found by an earlier scan), and it answered. */
    Known,
    /** This computer's own neighbour table lists it. */
    Neighbour,
    /** It answered the sweep: a port opened or a reset came back. */
    Sweep,
}

/** A service read on a host, and when: milliseconds after the scan started. */
data class ServiceFinding(val port: Int, val kind: ScanKind, val atMs: Long)

/**
 * A host that proved to be there, in the order the scan learnt of them. [network] is the [NetworkTally.id] of its network, [atMs] the
 * milliseconds after the scan started, and [services] what was read on it since (empty: it is there and offers nothing a scan knows).
 */
data class HostFinding(val ip: String, val network: String, val by: FoundBy, val atMs: Long, val services: List<ServiceFinding> = emptyList())

internal class ScanEngine(
    private val prober: ScanProber,
    private val neighbours: NeighbourSource,
    private val tuning: ScanTuning,
    private val connectTimeoutMs: Int,
    private val nanoTime: () -> Long = System::nanoTime,
    private val newPacer: (Int) -> Pacer = { Pacer(it) },
) {
    fun run(plan: NetworkPlan, origins: List<String>, knownHosts: List<String>, concurrency: Int, ports: List<Int>): Flow<NetworkProgress> = channelFlow {
        val nets = plan.networks.filter { it.addresses.isNotEmpty() }
        val owner = HashMap<String, Int>()
        nets.forEachIndexed { i, n -> n.addresses.forEach { owner.putIfAbsent(it, i) } }
        val addresses = LinkedHashSet<String>().also { set -> nets.forEach { set.addAll(it.addresses) } }.toList()
        fun netOf(ip: String) = nets[owner.getValue(ip)]
        fun portsOf(ip: String) = netOf(ip).ports ?: ports
        fun quickOf(ip: String) = portsOf(ip).let { all -> all.filter { it in PROXMOX_PORTS }.ifEmpty { all } }
        fun restOf(ip: String) = portsOf(ip) - quickOf(ip).toSet()

        val totals = IntArray(nets.size).also { t -> addresses.forEach { t[owner.getValue(it)]++ } }
        val ids = nets.map { it.cidr ?: it.source.name }
        val done = AtomicIntegerArray(nets.size)
        val answeredIn = AtomicIntegerArray(nets.size)
        // Hosts whose every port is tried: per network (known hosts included), and over all networks by step.
        val deepIn = AtomicIntegerArray(nets.size); val deepDoneIn = AtomicIntegerArray(nets.size)
        val knownTotal = AtomicInteger(); val knownDone = AtomicInteger(); val quickDone = AtomicInteger()
        val deepTotal = AtomicInteger(); val deepDone = AtomicInteger()
        val settled = AtomicBoolean(false)
        val startedAt = nanoTime()
        fun elapsedMs() = (nanoTime() - startedAt) / 1_000_000
        val alive = ConcurrentHashMap.newKeySet<String>()
        // The hosts that proved to be there, in the order learnt, and when each service was first read. Both under [seen]'s monitor.
        val seen = LinkedHashMap<String, HostFinding>()
        val readAt = HashMap<Pair<String, Int>, Long>()
        val findings = AtomicReference<List<HostFinding>>(emptyList())
        val finished = ConcurrentHashMap.newKeySet<String>()   // addresses whose every port has been tried
        val notes = CopyOnWriteArrayList<String>()
        val lock = Mutex()
        var hits = emptyList<ScanHit>()
        val phase = AtomicReference(ScanPhase.Sweeping)
        val last = AtomicReference("")
        var lastSent = 0L

        val perNetwork = maxOf(tuning.perNetworkFloor, tuning.quickInFlight / maxOf(1, nets.size)).coerceAtMost(tuning.quickInFlight)
        val quickGate = Semaphore(tuning.quickInFlight)
        val netGates = nets.map { Semaphore(perNetwork) }
        val probeGate = Semaphore(concurrency)
        val pacer = if (nets.any { !it.onLink }) newPacer(tuning.routedPerSecond) else null

        fun tallies(end: Boolean): List<NetworkTally> {
            val services = IntArray(nets.size).also { s -> hits.forEach { h -> owner[h.ip]?.let { s[it]++ } } }
            return nets.indices.map { i ->
                val closed = end || (done.get(i) == totals[i] && deepDoneIn.get(i) == deepIn.get(i) && (!nets[i].onLink || settled.get()))
                NetworkTally(ids[i], done.get(i), totals[i], answeredIn.get(i), deepIn.get(i), deepDoneIn.get(i), services[i], closed)
            }
        }
        fun steps() = ScanSteps(StepCount(knownDone.get(), knownTotal.get()), StepCount(quickDone.get(), addresses.size - knownTotal.get()), StepCount(deepDone.get(), deepTotal.get()))
        fun scannedNow() = nets.indices.sumOf { done.get(it) }
        /** [ip] proved to be there. The first proof is the one that is kept. */
        fun answered(ip: String, by: FoundBy) {
            if (!alive.add(ip)) return
            val i = owner.getValue(ip)
            answeredIn.incrementAndGet(i)
            synchronized(seen) { seen[ip] = HostFinding(ip, ids[i], by, elapsedMs()); findings.set(seen.values.toList()) }
        }
        suspend fun publish(force: Boolean = false, end: Boolean = false) = lock.withLock {
            val now = nanoTime()
            if (!force && !end && now - lastSent < tuning.publishEveryMs * 1_000_000) return@withLock
            lastSent = now
            // The list is sorted once, when the scan ends; until then it is in the order the hosts were found.
            send(NetworkProgress(plan, scannedNow(), last.get(), if (end) ScanRules.merge(hits) else hits, end, phase.get(), alive.size, tallies(end), notes.toList(),
                steps(), findings.get(), elapsedMs()))
        }
        suspend fun record(found: List<ScanHit>) {
            if (found.isEmpty()) return
            lock.withLock {
                val before = hits.map { it.ip to it.port }.toSet()
                hits = ScanRules.mergeArrival(hits, found.map { it.copy(saved = ScanRules.saved(it, origins)) })
                hits.filter { (it.ip to it.port) !in before }.forEach { Trace.emit(TraceSource.Scan, "service", "Discovered ${it.kind}", mapOf("url" to it.origin)) }
                // A host's services are what the list shows for it now, each with the moment it was first read.
                val at = elapsedMs()
                val touched = found.map { it.ip }.filter { it in owner }.distinct()
                touched.forEach { answered(it, FoundBy.Sweep) }
                synchronized(seen) {
                    for (ip in touched) {
                        val shown = hits.filter { it.ip == ip }.map { ServiceFinding(it.port, it.kind, readAt.getOrPut(ip to it.port) { at }) }
                        seen[ip]?.let { seen[ip] = it.copy(services = shown) }
                    }
                    findings.set(seen.values.toList())
                }
            }
            publish(force = true)
        }
        suspend fun finishAddress(ip: String) {
            done.incrementAndGet(owner.getValue(ip)); last.set(ip)
            publish()
        }
        // Across a router the connects of one address are let through together, at the pace allowed.
        suspend fun pace(ip: String, connects: Int) { if (!netOf(ip).onLink) pacer?.acquire(connects) }
        // A cheap connect: never more than the network's share or the total in flight. The first look is short; a host that is known
        // to be there gets the scanner's full connect wait for the second one (a device in power-save answers slowly).
        suspend fun reach(ip: String, port: Int, patient: Boolean = false): Reach {
            val i = owner.getValue(ip); val net = nets[i]
            val timeout = when { net.onLink && patient -> connectTimeoutMs; net.onLink -> minOf(connectTimeoutMs, tuning.onLinkQuickMs); else -> maxOf(connectTimeoutMs, tuning.routedQuickMs) }
            return netGates[i].withPermit { quickGate.withPermit { prober.reach(ip, port, timeout) } }
        }
        suspend fun read(ip: String, port: Int) = record(probeGate.withPermit { prober.probe(ip, port) })
        // The ports of one host at once: each is a cheap connect, and only a port that opened is read.
        suspend fun services(ip: String, wanted: List<Int>, by: FoundBy) {
            if (wanted.isEmpty()) return
            pace(ip, wanted.size)
            coroutineScope {
                wanted.map { port -> async(start = CoroutineStart.UNDISPATCHED) {
                    when (reach(ip, port, patient = true)) {
                        Reach.Open -> { answered(ip, by); read(ip, port) }
                        Reach.Refused -> answered(ip, by)
                        Reach.Silent -> Unit
                    }
                } }.awaitAll()
            }
        }
        /** Every remaining port of a host that is there: counted as one host of the deep pass, of its network and (unless it was known) of the scan. */
        suspend fun deepPass(ip: String, wanted: List<Int>, by: FoundBy, known: Boolean = false) {
            val i = owner.getValue(ip)
            services(ip, wanted, by)
            deepDoneIn.incrementAndGet(i)
            if (known) knownDone.incrementAndGet() else deepDone.incrementAndGet()
        }
        fun enterDeep(ip: String, known: Boolean = false) { deepIn.incrementAndGet(owner.getValue(ip)); if (known) knownTotal.incrementAndGet() else deepTotal.incrementAndGet() }
        suspend fun quick(ip: String) {
            pace(ip, quickOf(ip).size)
            coroutineScope {
                quickOf(ip).map { port -> async(start = CoroutineStart.UNDISPATCHED) {
                    when (reach(ip, port)) {
                        Reach.Open -> { answered(ip, FoundBy.Sweep); read(ip, port) }
                        Reach.Refused -> answered(ip, FoundBy.Sweep)
                        Reach.Silent -> Unit
                    }
                } }.awaitAll()
            }
            // A host that answered anything gets the rest of its ports at once.
            if (ip in alive) { enterDeep(ip); deepPass(ip, restOf(ip), FoundBy.Sweep); finished += ip }
            quickDone.incrementAndGet()
            finishAddress(ip)
        }

        publish(force = true)
        if (addresses.isEmpty()) { phase.set(ScanPhase.Done); publish(end = true); return@channelFlow }
        coroutineScope {
            // 1. Known hosts: first in line for every gate.
            val onLink = addresses.filter { netOf(it).onLink }
            val listed = if (onLink.isEmpty() || neighbours === NeighbourSource.NONE) emptySet() else runCatching { neighbours.read() }.getOrNull().orEmpty()
            val handed = (knownHosts + origins.mapNotNull { ipv4HostOf(it) }).filter { it in owner }.toSet()
            val known = (handed + onLink.filter { it in listed }).distinct()
            // A neighbour this computer lists is there. A server the app knew is there once it answers.
            known.forEach { ip -> finished += ip; enterDeep(ip, known = true); if (ip in listed) answered(ip, if (ip in handed) FoundBy.Known else FoundBy.Neighbour) }
            val knownJobs = known.map { ip -> launch { deepPass(ip, portsOf(ip), FoundBy.Known, known = true); finishAddress(ip) } }
            // 2. The cheap pass over everything else, every network side by side.
            val sweeps = nets.indices.map { i -> launch {
                val window = Semaphore(perNetwork)
                coroutineScope {
                    for (ip in addresses) {
                        if (owner.getValue(ip) != i || ip in finished) continue
                        window.acquire()
                        launch { try { quick(ip) } finally { window.release() } }
                    }
                }
            } }
            sweeps.joinAll(); knownJobs.joinAll()

            // 3. On this computer's own networks the neighbour table says who is alive. The system fills it from the connects of
            //    the cheap pass over a second or so, so it is read until it stops growing (or the settle time is up).
            val rest = onLink.filter { it !in finished }
            if (rest.isNotEmpty()) {
                phase.set(ScanPhase.Settling); publish(force = true)
                val real = neighbours !== NeighbourSource.NONE
                val settleEnd = nanoTime() + tuning.settleMs * 1_000_000
                val handled = HashSet<String>()
                val probes = mutableListOf<Job>()
                var table: Set<String>? = null
                var first = true
                while (true) {
                    if (real && first) delay(minOf(tuning.settleMs, 500L))
                    val read = runCatching { neighbours.read() }.getOrNull()
                    if (read == null) { if (first) table = null; break }
                    val before = table?.size
                    table = read
                    phase.set(ScanPhase.Probing); if (first) publish(force = true)
                    val fresh = rest.filter { it in read && handled.add(it) }
                    fresh.forEach { ip -> answered(ip, FoundBy.Neighbour); enterDeep(ip); probes += launch { deepPass(ip, portsOf(ip), FoundBy.Neighbour) } }
                    first = false
                    if (!real || nanoTime() >= settleEnd || (fresh.isEmpty() && before == read.size)) break
                    delay(minOf(300L, maxOf(1L, (settleEnd - nanoTime()) / 1_000_000)))
                }
                if (table == null) {
                    phase.set(ScanPhase.Probing); publish(force = true)
                    notes += "The neighbour table could not be read, so the other ports of every address on this computer's own networks were tried."
                    rest.onEach { enterDeep(it) }.map { ip -> launch { deepPass(ip, restOf(ip), FoundBy.Sweep) } }.joinAll()
                }
                probes.joinAll()
            }
            settled.set(true)
        }
        phase.set(ScanPhase.Done)
        publish(end = true)
    }
}
