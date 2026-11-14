package local.ringleader.core

import java.net.InetAddress
import java.net.URI
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the connection tag is about, decided by the UI from the session in view. */
sealed interface RouteContext {
    /** No server connection in view. */
    data object None : RouteContext
    /** Demonstration data: there is no server, so there is no route. */
    data object Demo : RouteContext
    /** Browser mode: the RingMASTER host makes the connection, not the viewer's browser. Not inspected. */
    data object Browser : RouteContext
    /** A connected session. [epoch] changes on every connect, so a reconnect is a new key. */
    data class Server(val session: Long, val epoch: Long, val address: String) : RouteContext
}

data class RouteKey(val session: Long, val epoch: Long, val origin: String)

sealed interface RouteTagState {
    data object Hidden : RouteTagState
    data object Demo : RouteTagState
    data object Browser : RouteTagState
    data class Checking(val key: RouteKey, val target: RouteTarget) : RouteTagState
    /** [refreshing] while a newer read of the same key is running. */
    data class Observed(val key: RouteKey, val observation: RouteObservation, val refreshing: Boolean = false) : RouteTagState
}

/** Fence the rendered frame too: the observer's UI effect runs after composition switches sessions. */
fun RouteTagState.forContext(context: RouteContext): RouteTagState = when (context) {
    RouteContext.None -> RouteTagState.Hidden
    RouteContext.Demo -> RouteTagState.Demo
    RouteContext.Browser -> RouteTagState.Browser
    is RouteContext.Server -> routeTarget(context.address)?.let { target ->
        val expected = RouteKey(context.session, context.epoch, target.origin)
        when {
            this is RouteTagState.Observed && key == expected -> this
            this is RouteTagState.Checking && key == expected -> this
            else -> RouteTagState.Checking(expected, target)
        }
    } ?: RouteTagState.Hidden
}

/**
 * VPN inventory for route classification, without competing with the VPN page: its latest snapshot is used while it is
 * fresh ([shared]); otherwise one snapshot of our own is taken and kept for [maxAgeMillis]. [invalidate] (a VPN change,
 * a network change) discards everything older.
 */
class VpnInventorySource(
    private val shared: () -> VpnSnapshot?,
    private val take: (() -> VpnSnapshot)?,
    private val clock: () -> Instant = Instant::now,
    val maxAgeMillis: Long = 300_000,
    val sharedMaxAgeMillis: Long = 60_000,
) {
    private var own: VpnSnapshot? = null
    private var ownGeneration = 0L
    private var generation = 0L
    private var cutoff: Instant = Instant.EPOCH
    /** Snapshots this source took itself (tests and the report). */
    @Volatile var taken = 0; private set

    @Synchronized fun invalidate() { own = null; generation++; cutoff = clock() }

    /** Blocks while a snapshot is taken. */
    fun current(): VpnInventory {
        val now = clock()
        val (limit, cached, gen) = synchronized(this) { Triple(cutoff, own?.takeIf { ownGeneration == generation }, generation) }
        fun young(s: VpnSnapshot, max: Long) = !s.at.isBefore(limit) && Duration.between(s.at, now).toMillis() in -5_000..max
        runCatching { shared() }.getOrNull()?.takeIf { young(it, sharedMaxAgeMillis) }?.let { return VpnInventory(it) }
        cached?.takeIf { young(it, maxAgeMillis) }?.let { return VpnInventory(it) }
        val read = take ?: return VpnInventory.Unavailable
        return try {
            val s = read()
            synchronized(this) { taken++; if (generation == gen) { own = s; ownGeneration = gen } }
            VpnInventory(s)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            VpnInventory(null, "The VPN check failed. ${vpnFailureMessage(e.message.orEmpty())}".trim())
        }
    }
}

/**
 * Keeps one route observation for the session in view, read off the UI thread and only while visible.
 *
 * - [update] is the single input from the UI (context and visibility). A new session, origin or connection epoch drops
 *   to Checking at once, so a result for connection A is never shown for B. A cached result for a key is reused only
 *   while younger than [refreshMillis] and while the network was checked recently.
 * - While visible, a read runs every [refreshMillis] and the interface fingerprint every [fingerprintMillis]; a change
 *   (or [invalidate], for VPN changes) drops the shown result and reads again. Reads never overlap; requests during a
 *   read coalesce into one more read.
 * - A failed read replaces the previous result with Unknown. Each blocking step is bounded by [stepTimeoutMillis].
 * - Hidden, minimised or not on a session page: nothing runs.
 */
class ConnectionRouteObserver(
    private val scope: CoroutineScope,
    private val probe: RouteProbe?,
    private val inventory: VpnInventorySource,
    private val resolve: (String) -> List<InetAddress> = ::javaResolve,
    private val proxyFor: (URI) -> String? = { javaProxyFor(it) },
    private val fingerprint: (() -> String?)? = ::networkFingerprint,
    private val clock: () -> Instant = Instant::now,
    io: CoroutineContext = Dispatchers.IO,
    val refreshMillis: Long = 90_000,
    val fingerprintMillis: Long = 15_000,
    val stepTimeoutMillis: Long = 20_000,
) {
    private val mutable = MutableStateFlow<RouteTagState>(RouteTagState.Hidden)
    val state: StateFlow<RouteTagState> = mutable.asStateFlow()
    private val worker = CoroutineScope(io + SupervisorJob())
    // Some OS/resolver calls ignore interruption. Hold the gate until they actually return, including after timeout.
    private val blockingGate = Mutex()
    private var context: RouteContext = RouteContext.None
    private var active = false
    private var key: RouteKey? = null
    private var target: RouteTarget? = null
    private var generation = 0L
    private var invalidations = 0L
    private var job: Job? = null
    private var wake = Channel<Unit>(Channel.CONFLATED)
    private val cache = LinkedHashMap<RouteKey, RouteObservation>()
    private var lastPrint: String? = null
    private var lastPrintAt: Instant? = null
    private var pausedAt: Instant? = null
    /** Observations started (tests). */
    var reads = 0; private set

    /** Called from the UI thread whenever the session in view, its connection or the visibility changes. */
    fun update(context: RouteContext, active: Boolean) {
        val nextKey = (context as? RouteContext.Server)?.let { c -> routeTarget(c.address)?.let { RouteKey(c.session, c.epoch, it.origin) } }
        val nextTarget = (context as? RouteContext.Server)?.let { routeTarget(it.address) }
        val sameKind = context::class == this.context::class
        if (sameKind && nextKey == key && active == this.active) return
        val keyChanged = !sameKind || nextKey != key
        this.context = context; key = nextKey; target = nextTarget
        val wasActive = this.active
        this.active = active
        if (keyChanged || !active) { job?.cancel(); job = null; generation++ }
        if (!active && wasActive) pausedAt = clock()
        when {
            context is RouteContext.Demo -> mutable.value = RouteTagState.Demo
            context is RouteContext.Browser -> mutable.value = RouteTagState.Browser
            nextKey == null || nextTarget == null -> mutable.value = RouteTagState.Hidden
            keyChanged -> { mutable.value = initialState(nextKey, nextTarget, resumed = false); if (active) start(nextKey, nextTarget) }
            active && !wasActive -> { mutable.value = initialState(nextKey, nextTarget, resumed = true); start(nextKey, nextTarget) }
        }
    }

    /** A VPN change or a network change: the shown result may be wrong now. Drops it and reads again when visible. */
    fun invalidate() {
        invalidations++
        inventory.invalidate()
        cache.clear()
        val k = key; val t = target
        if (k != null && t != null && mutable.value.let { it is RouteTagState.Observed && it.key == k }) mutable.value = RouteTagState.Checking(k, t)
        if (active) wake.trySend(Unit)
    }

    fun close() { job?.cancel(); worker.cancel() }

    private fun initialState(k: RouteKey, t: RouteTarget, resumed: Boolean): RouteTagState {
        val cached = cache[k] ?: return RouteTagState.Checking(k, t)
        val now = clock()
        val young = Duration.between(cached.at, now).toMillis() in 0 until refreshMillis
        val printAt = lastPrintAt
        val networkRecent = printAt != null && Duration.between(printAt, now).toMillis() < fingerprintMillis * 2
        val briefPause = !resumed || pausedAt?.let { Duration.between(it, now).toMillis() < fingerprintMillis } == true
        return if (young && networkRecent && briefPause) RouteTagState.Observed(k, cached) else RouteTagState.Checking(k, t)
    }

    private fun start(k: RouteKey, t: RouteTarget) {
        job?.cancel()
        val gen = ++generation
        val signal = Channel<Unit>(Channel.CONFLATED)
        wake = signal
        val shown = mutable.value
        job = scope.launch {
            if (fingerprint != null) launch { watchNetwork(gen) }
            var due = if (shown is RouteTagState.Observed) (refreshMillis - Duration.between(shown.observation.at, clock()).toMillis()).coerceIn(0, refreshMillis) else 0L
            while (isActive) {
                if (due > 0) withTimeoutOrNull(due) { signal.receive() }
                observe(k, t, gen)
                due = refreshMillis
            }
        }
    }

    private suspend fun observe(k: RouteKey, t: RouteTarget, gen: Long) {
        val mark = invalidations
        reads++
        (mutable.value as? RouteTagState.Observed)?.takeIf { it.key == k }?.let { mutable.value = it.copy(refreshing = true) }
        val observation = read(t)
        currentCoroutineContext().ensureActive()
        if (gen != generation || k != key) return
        if (mark != invalidations) return  // the wake that came with the invalidation reads again
        cache.remove(k); cache[k] = observation
        while (cache.size > 8) cache.remove(cache.keys.first())
        mutable.value = RouteTagState.Observed(k, observation)
    }

    private suspend fun read(t: RouteTarget): RouteObservation {
        val at = clock()
        val proxy = bounded { proxyFor(URI(t.origin)) }.getOrElse {
            return RouteObservation(t, at, RouteKind.Unknown, reason = "Proxy settings could not be checked. The connection route is unknown.")
        }
        if (proxy != null) return classifyConnectionRoute(t, at, proxy, null, null, VpnInventory.Unavailable)
        val addresses = bounded { resolve(t.host) }
        val first = addresses.getOrNull()?.firstOrNull()
        if (first == null || first.isLoopbackAddress) return classifyConnectionRoute(t, at, null, addresses, null, VpnInventory.Unavailable)
        val p = probe ?: return classifyConnectionRoute(t, at, null, addresses, null, VpnInventory.Unavailable)
            .copy(reason = "Route checks are not available on this platform.")
        val route = bounded { p.route(first.hostAddress.substringBefore('%')) }
        // A failed route read is Unknown whatever the VPN list says, so no snapshot is taken for it.
        if (route.isFailure) return classifyConnectionRoute(t, at, null, addresses, route, VpnInventory(null, "Not checked, because the route read failed."))
        val vpn = bounded { inventory.current() }.getOrElse { VpnInventory(null, "The VPN check took too long.") }
        return classifyConnectionRoute(t, at, null, addresses, route, vpn)
    }

    private suspend fun watchNetwork(gen: Long) {
        val read = fingerprint ?: return
        while (true) {
            val print = bounded { read() }.getOrNull()
            if (gen != generation) return
            if (print != null) {
                val before = lastPrint
                lastPrint = print; lastPrintAt = clock()
                if (before != null && before != print) invalidate()
            }
            delay(fingerprintMillis)
        }
    }

    /** Runs a blocking step on the worker with a time limit. A step that overruns is abandoned, not waited for. */
    private suspend fun <T> bounded(block: () -> T): Result<T> {
        val running = worker.async { blockingGate.withLock { runInterruptible { block() } } }
        return try {
            Result.success(withTimeout(stepTimeoutMillis) { running.await() })
        } catch (e: TimeoutCancellationException) {
            running.cancel(); Result.failure(IllegalStateException("The check took too long."))
        } catch (e: CancellationException) {
            running.cancel(); throw e
        } catch (e: Exception) { Result.failure(e) }
    }
}
