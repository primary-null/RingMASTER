package local.ringleader.desktop

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import local.ringleader.core.*

/**
 * What the Scan page reads from this computer. The app uses [Local]; a test passes its own, so that no test starts PowerShell,
 * reads the real adapters or sweeps a network.
 */
internal interface ScanEnvironment {
    /** This computer's own network cards. */
    fun cards(): List<ScanInterface>
    /** What the system says about adapters and routes. */
    suspend fun facts(): NetworkFactsRead
    /** The certificates the person approved for manual connections, as pin to a word for where the approval is. Empty when they cannot be read. */
    fun approvedCertificates(): Map<String, String> = emptyMap()
    /** The scanner a Start runs. */
    fun scanner(): NetworkScanner

    object Local : ScanEnvironment {
        override fun cards() = NetworkScanner.attachedInterfaces()
        override suspend fun facts() = LocalNetworkReader().read()
        override fun approvedCertificates(): Map<String, String> = runCatching {
            CertificateTrustStore(profileFile().resolveSibling("certificates.json")).load().mapNotNull { c ->
                pinKey(c.pin)?.let { it to "approved certificate for ${runCatching { java.net.URI(c.origin).host }.getOrNull() ?: "a server"}" }
            }.toMap()
        }.getOrDefault(emptyMap())
        override fun scanner() = NetworkScanner()
    }
}

/**
 * What the window already knows about servers, handed to the Scan page: the saved connections and the open ones (their addresses
 * give networks to offer), the saved origins (for the Saved tag) and the certificate pins of the saved connections, each with the
 * connection's name (a pin is written as [pinKey] writes it).
 */
data class ScanKnown(
    val servers: List<KnownServer> = emptyList(),
    val origins: List<String> = emptyList(),
    val pins: Map<String, String> = emptyMap(),
) {
    companion object { val NONE = ScanKnown() }
}

/** The page's view of what is known: the saved profiles and the connections open now. */
fun scanKnownOf(profiles: List<ConnectionProfile>, open: List<OpenSession> = emptyList()): ScanKnown {
    val saved = profiles.mapNotNull { p -> ipv4HostOf(p.origin)?.let { KnownServer(it, p.displayName, PlanSource.Saved) } }
    val sessions = open.mapNotNull { s ->
        val state = s.workspace.state.value
        if (!sessionOpen(state)) return@mapNotNull null
        val name = profiles.firstOrNull { it.id == s.profileId }?.displayName
        ipv4HostOf(state.address)?.let { KnownServer(it, name ?: it, PlanSource.Session) }
    }
    return ScanKnown(saved + sessions, profiles.map { it.origin }, profiles.mapNotNull { p -> pinKey(p.sha256Pin)?.let { it to "saved connection ${p.displayName}" } }.toMap())
}

/**
 * Holds the Scan page's plan and one manual scan for the window's lifetime, so results survive moving to Desk and back.
 *
 * The plan is what the page shows before Start: this computer's own networks, the networks its routes and servers reach, and the
 * subnet the person typed, each ticked or not ([ScanPlanner]). It is rebuilt whenever anything it is made of changes. Nothing is
 * swept until Start, and then only what the plan ticks.
 */
class ScanSession internal constructor(private val scope: CoroutineScope, private val env: ScanEnvironment, val choices: ScanChoices) {
    constructor(scope: CoroutineScope) : this(scope, ScanEnvironment.Local, ScanChoices.stored())

    var progress by mutableStateOf<NetworkProgress?>(null); private set
    var running by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    /** What [message] is: nothing, a cancelled scan, a scan an error stopped, or a Start with nothing ticked. */
    val notice: ScanNotice get() = scanNoticeOf(message).let { if (it == ScanNotice.NothingTicked && plan.hasWork) ScanNotice.None else it }
    var durationSeconds by mutableStateOf(0L); private set

    private val typed = mutableStateOf("")
    /** A private network (or, on the Proxmox ports only, any other unicast one) typed by the person. Blank for none. */
    var extraSubnet: String
        get() = typed.value
        set(value) { typed.value = value }

    /** This computer's own cards, null until they have been read. */
    var cards by mutableStateOf<List<ScanInterface>?>(null); private set
    /** What the system says about adapters and routes, null until it has been read. */
    var facts by mutableStateOf<NetworkFactsRead?>(null); private set
    var reading by mutableStateOf(false); private set
    /** What the window knows about servers. */
    var known by mutableStateOf(ScanKnown.NONE)
    private var picks by mutableStateOf(choices.all())
    /** Certificates approved for manual connections, read with the networks. They count as saved, like a pin of a saved connection. */
    private var approved by mutableStateOf<Map<String, String>>(emptyMap())
    /** What the app has saved, for the Saved tag: the saved origins, and every certificate pinned by a connection or approved. */
    val saved: KnownSaved get() = KnownSaved(known.origins, approved + known.pins)
    /** Proxmox servers found by the last scan: their networks are offered next time. */
    private var earlier by mutableStateOf<List<KnownServer>>(emptyList())
    private var job: Job? = null
    private var reader: Job? = null
    private var readAt = 0L

    /** Sources of reachable networks beyond the two the page always has (the routing table and the servers it knows). See [connect]. */
    private var extraSources by mutableStateOf<List<ReachableNetworksSource>>(emptyList())

    /**
     * Connects another source of reachable networks, such as the tailnet's subnet routes ([TailnetSubnetRoutes]). Its answer joins
     * the plan from then on and follows whatever the source reads from, if that is Compose state.
     */
    fun connect(source: ReachableNetworksSource) { extraSources = extraSources + source }

    private val planned = derivedStateOf {
        val own = cards.orEmpty()
        val read = facts
        // Extra sources first: where two sources name the same network the first one's wording stays.
        val sources = extraSources + listOf(OsRoutesSource(), KnownServersSource { known.servers + earlier })
        val reachable = ReachableNetworks.collect(sources, ReachableContext(own, read?.facts))
        ScanPlanner.plan(own, read?.facts, reachable.networks, typed.value, picks, listOfNotNull(read?.problem, reachable.problem).joinToString(" ").ifBlank { null }, reachable.leftOut)
    }

    /** The plan as it stands: what is offered, what is ticked and what a Start would sweep. */
    val plan: ScanPlanView get() = planned.value

    /** Why the typed text is not a network. Blank when it is one, or when nothing is typed. */
    val extraNote: String get() = plan.typedProblem

    /** True when Start can do something: not running, the typed text is a network, and something is ticked (or the plan is not read yet). */
    val canStart: Boolean get() = !running && extraNote.isEmpty() && (cards == null || plan.hasWork)

    /** Tick or untick [id]; the choice is remembered for that network. */
    fun setChoice(id: String, on: Boolean) { choices.put(id, on); picks = choices.all() }

    /** Back to the defaults for every network. */
    fun resetChoices() { choices.reset(); picks = emptyMap() }

    /**
     * Reads this computer's cards, adapters and routes. Once per visit to the page, and again on request; while the page is open
     * the answer is kept. A read that fails leaves the cards' own networks on the plan and says so ([ScanPlanView.problem]).
     */
    fun refresh(force: Boolean = false) {
        if (reading) return
        if (!force && cards != null && !readIsOld()) return
        reading = true
        reader = scope.launch {
            try {
                approved = withContext(Dispatchers.IO) { runCatching { env.approvedCertificates() }.getOrDefault(emptyMap()) }
                cards = withContext(Dispatchers.IO) { runCatching { env.cards() }.getOrDefault(emptyList()) }
                facts = try { env.facts() } catch (e: CancellationException) { throw e } catch (e: Exception) { NetworkFactsRead(null, "This computer's routes could not be read.") }
                readAt = System.nanoTime()
            } finally { reading = false }
        }
    }

    fun start(origins: List<String> = known.origins) {
        if (running) return
        // Nothing starts on a typo: it must not look like a scan that found nothing.
        if (extraNote.isNotEmpty()) return
        if (cards != null && !plan.hasWork) { message = nothingToSweep(plan); return }
        running = true; message = ""; progress = null; durationSeconds = 0
        val started = System.nanoTime()
        job = scope.launch {
            // The elapsed time counts up while the scan runs, not only when it ends.
            val clock = launch { while (true) { delay(1000); durationSeconds = (System.nanoTime() - started) / 1_000_000_000 } }
            try {
                // The first read decides what is swept, so a Start before it ends waits for it (a read never takes longer than its own limits).
                if (cards == null || readIsOld()) { refresh(force = true); withTimeoutOrNull(START_WAITS_MS) { reader?.join() } }
                val view = plan
                if (!view.hasWork) { message = nothingToSweep(view); return@launch }
                // The servers the app already knows are looked at first, with the hosts of the saved origins.
                env.scanner().scan(view.toNetworkPlan(), origins, knownHosts = (known.servers + earlier).map { it.address }.distinct()).collect { progress = it }
                earlier = progress?.hits.orEmpty().filter { it.proxmox }.map { KnownServer(it.ip, scanHostname(it), PlanSource.Earlier) }
            }
            catch (e: CancellationException) { message = SCAN_CANCELLED; throw e }
            catch (e: Exception) { message = scanFailureMessage(e) }
            finally { clock.cancel(); durationSeconds = (System.nanoTime() - started) / 1_000_000_000; running = false }
        }
    }
    fun cancel() { job?.cancel() }

    private fun readIsOld() = System.nanoTime() - readAt >= READ_VALID_NANOS

    private companion object {
        /** A read of this computer's networks is good for this long. */
        const val READ_VALID_NANOS = 30_000_000_000L
        const val START_WAITS_MS = 30_000L
    }
}

internal const val SCAN_CANCELLED = "Cancelled"
internal const val SCAN_STOPPED = "The scan stopped: "
internal const val NOTHING_TICKED = "Nothing is ticked. Tick a network below, or type one."
internal const val NO_NETWORK_TO_SCAN = "No network to scan was found on this computer. Connect it to a network, or type one in Additional subnet."

/** Why a plan with nothing to sweep has nothing: no network was offered at all, or every one offered is unticked. */
internal fun nothingToSweep(plan: ScanPlanView): String = if (plan.rows.none { it.sweepable }) NO_NETWORK_TO_SCAN else NOTHING_TICKED

/** What the page says an error ended the scan with: the cause in a sentence a person can use, never only a class name. */
internal fun scanFailureText(e: Throwable): String = when (e) {
    is SecurityException -> "this computer would not let RingMASTER open the connections a scan needs."
    is java.io.IOException -> "the network stopped answering while it ran (${e.javaClass.simpleName})."
    is IllegalArgumentException -> (e.message?.takeIf { it.isNotBlank() && it.length <= 160 } ?: "a scan was asked for something it does not do").let { if (it.endsWith('.')) it else "$it." }
    else -> "an unexpected error ended it (${e.javaClass.simpleName})."
}

internal fun scanFailureMessage(e: Throwable): String = SCAN_STOPPED + scanFailureText(e)

enum class ScanNotice { None, Cancelled, Failed, NothingTicked }

/** The kind of a session message. Anything else the session might say is bad news, so it is shown as a stopped scan. */
internal fun scanNoticeOf(message: String): ScanNotice = when {
    message.isEmpty() -> ScanNotice.None
    message == SCAN_CANCELLED -> ScanNotice.Cancelled
    message == NOTHING_TICKED || message == NO_NETWORK_TO_SCAN -> ScanNotice.NothingTicked
    else -> ScanNotice.Failed
}
