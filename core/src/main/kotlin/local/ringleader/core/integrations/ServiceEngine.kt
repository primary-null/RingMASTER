package local.ringleader.core.integrations

import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How much of a link the UI shows right now. Nothing is fetched for [None]; [Compact] fetches only the pin's and ticker's needs. */
enum class Interest { None, Compact, Full }

/** What the engine asks of a link's transport. [ServiceClient] in the app. */
interface LinkFetcher {
    fun fetch(requestId: String, params: Map<String, List<String>>): Fetched
    fun logout(): Boolean
}

fun ServiceClient.asFetcher() = object : LinkFetcher {
    override fun fetch(requestId: String, params: Map<String, List<String>>) = this@asFetcher.fetch(requestId, params)
    override fun logout() = this@asFetcher.logout()
}

/** Polling rules, in one place. */
object PollRules {
    /** After a failure the interval doubles, up to this. */
    val MAX_BACKOFF: Duration = Duration.ofMinutes(10)
    /** A `Retry-After` is honoured up to an hour. */
    val MAX_RETRY_AFTER: Duration = Duration.ofHours(1)
    /** Each interval moves by up to this share either way, so many links do not poll in step. */
    const val JITTER = 0.10
    /** Three "API changed" answers in a row stop that request until the link is edited or restarted. */
    const val SHAPE_STRIKES = 3
}

/**
 * Polls one link. One coroutine, so one request in flight at a time; it sleeps while [Interest.None] or paused, fetches only
 * the requests a visible block needs, each on its own interval with jitter, backs off on failure and honours `Retry-After`.
 * A problem that only the owner can fix (refused credential, certificate, plain-HTTP rule, unsupported version) stops polling
 * until [restart]. Pausing (the kill switch) also logs the session out at the service.
 */
class ServiceRunner(
    val linkId: String,
    private val template: ServiceTemplate,
    private val variant: TemplateVariant,
    private val fetcher: LinkFetcher,
    private val params: Map<String, List<String>>,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val random: Random = Random.Default,
    paused: Boolean = false,
    /** Called once, after the first answer that was not a problem (a remembered secret is stored then, not before). */
    private val onFirstSuccess: () -> Unit = {},
) {
    private data class Control(val interest: Interest, val paused: Boolean, val generation: Int)

    private val control = MutableStateFlow(Control(Interest.None, paused, 0))
    private val snapshots = HashMap<String, RequestSnapshot>()
    private val failures = HashMap<String, Int>()
    private val strikes = HashMap<String, Int>()
    private val sampled = HashMap<String, List<SeriesPoint>>()
    private var rates: Map<String, RateSample> = emptyMap()
    private var linkProblem: ServiceProblem? = null
    private var unencrypted: UnencryptedMark? = null
    private var succeeded = false
    private val mutableState = MutableStateFlow(evaluate(null))
    val state: StateFlow<ServicePanelState> = mutableState.asStateFlow()
    /** Fetches made, for tests and the trace view. */
    @Volatile var fetches = 0
        private set
    private var job: Job? = null

    fun start(): ServiceRunner { if (job == null) job = scope.launch { loop() }; return this }

    fun setInterest(interest: Interest) = control.value.let { if (it.interest != interest) control.value = it.copy(interest = interest) }

    /** The per-link kill switch. Logs any session out at the service. */
    fun pause() {
        if (control.value.paused) return
        control.value = control.value.copy(paused = true)
        scope.launch(io) { fetcher.logout() }
        mutableState.value = evaluate(null)
    }

    fun resume() { if (control.value.paused) { control.value = control.value.copy(paused = false); mutableState.value = evaluate(null) } }

    /** Clears a latched problem (after the owner changed the credential or acknowledged) and polls again. */
    fun restart() {
        linkProblem = null; failures.clear(); strikes.clear()
        snapshots.replaceAll { _, s -> s.copy(nextAt = null, stopped = false, problem = null) }
        control.value = control.value.copy(generation = control.value.generation + 1)
    }

    /** Stops polling for good and logs the session out. */
    suspend fun stop() {
        job?.cancel(); job = null
        withContext(io) { fetcher.logout() }
    }

    private fun wanted(interest: Interest): Set<String> = when (interest) {
        Interest.None -> emptySet()
        Interest.Compact -> variant.compactRequests
        Interest.Full -> variant.panelRequests
    }.filter { it in variant.requests }.toSet()

    private suspend fun loop() {
        while (scope.isActive) {
            val c = control.first { !it.paused && it.interest != Interest.None && (linkProblem == null) }
            val now = clock()
            val wanted = wanted(c.interest).filter { snapshots[it]?.stopped != true }
            val due = wanted.filter { id -> snapshots[id]?.nextAt?.let { !now.isBefore(it) } ?: true }
            if (due.isEmpty()) {
                val next = wanted.mapNotNull { snapshots[it]?.nextAt }.minOrNull()
                val waitMs = next?.let { Duration.between(now, it).toMillis().coerceAtLeast(1) }
                if (waitMs == null) control.drop(1).first() else withTimeoutOrNull(waitMs) { control.drop(1).first() }
                continue
            }
            val id = due.minByOrNull { snapshots[it]?.nextAt ?: Instant.MIN }!!
            val fetched = withContext(io) { fetcher.fetch(id, params) }
            fetches++
            record(id, fetched, c.interest)
        }
    }

    private fun record(id: String, f: Fetched, interest: Interest) {
        val spec = variant.requests.getValue(id)
        val before = snapshots[id] ?: RequestSnapshot()
        val problem = f.problem
        if (f.unencrypted != null) unencrypted = f.unencrypted
        val next: Instant
        if (problem == null) {
            failures.remove(id)
            next = f.at.plus(jitter(spec.every))
            snapshots[id] = before.copy(response = f.response, okAt = f.at, fetchedAt = f.at, status = f.status, problem = null, nextAt = next)
            if (!succeeded) { succeeded = true; onFirstSuccess() }
        } else {
            val n = (failures[id] ?: 0) + 1
            failures[id] = n
            val backoff = minOf(spec.every.multipliedBy(1L shl minOf(n, 16)), PollRules.MAX_BACKOFF)
            val retry = problem.retryAfterSeconds?.let { Duration.ofSeconds(it).coerceAtMost(PollRules.MAX_RETRY_AFTER) }
            next = f.at.plus(jitter(maxOf(backoff, retry ?: Duration.ZERO)))
            snapshots[id] = before.copy(fetchedAt = f.at, status = f.status, problem = problem, nextAt = next,
                stopped = problem.kind == ProblemKind.SettingNeeded)
            if (problem.kind.waitsForOwner) linkProblem = problem
        }
        var state = evaluate(interest)
        // A required reading fed by this request and still missing after a good answer: count it; three stop the request.
        if (problem == null && state.status == PanelStatus.ApiChanged && variant.readings.any { it.required && id in it.expr.requests }) {
            val s = (strikes[id] ?: 0) + 1
            strikes[id] = s
            if (s >= PollRules.SHAPE_STRIKES) { snapshots[id] = snapshots.getValue(id).copy(stopped = true); state = evaluate(interest) }
        } else if (problem == null) strikes.remove(id)
        // A sampled series takes this poll's value; the state is evaluated again so it already shows the new point.
        if (problem == null && variant.series.any { it is SeriesSpec.Sampled }) { sample(state); state = evaluate(interest) }
        mutableState.value = state
    }

    private fun sample(state: ServicePanelState) {
        for (s in variant.series.filterIsInstance<SeriesSpec.Sampled>()) {
            val r = state.reading(s.reading) ?: continue
            sampled[s.id] = (sampled[s.id].orEmpty() + SeriesPoint(state.evaluatedAt, r.number)).takeLast(s.keep)
        }
    }

    private fun jitter(d: Duration): Duration {
        val factor = 1.0 + (random.nextDouble() * 2 - 1) * PollRules.JITTER
        return Duration.ofMillis((d.toMillis() * factor).toLong().coerceAtLeast(1000))
    }

    private fun evaluate(interest: Interest?): ServicePanelState {
        val c = control.value
        val evaluated = ServicePanel.evaluate(template, variant, PanelInput(
            snapshots.toMap(), clock(), params, sampled.toMap(), rates, paused = c.paused, linkProblem = linkProblem,
            unencrypted = unencrypted, wanted = interest?.let { wanted(it) }?.takeIf { it.isNotEmpty() },
        ))
        rates = evaluated.rates
        return evaluated.state
    }
}
