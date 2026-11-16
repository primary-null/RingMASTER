package local.ringleader.desktop

import androidx.compose.runtime.*
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.ringleader.core.*

/*
 * The live ticker (the tray of tiles over the page area): its state, where its readings come from, and where its clicks go.
 * The drawing is in LiveTickerTray.kt; the tiles themselves are made by HoodTicker in core.
 */

/** The ink of a tone: plain, amber, the hotter amber, red. Every part of the app that draws a tone (the tray, the Network area) uses this one. */
internal fun toneInk(tone: TickerTone): androidx.compose.ui.graphics.Color = when (tone) {
    TickerTone.Calm -> Tech.Ink
    TickerTone.Busy -> Tech.Amber
    TickerTone.Warning -> hotAmber()
    TickerTone.Error -> Tech.Red
}

/** Pages that already show guest names, so a tile may say which guest (with Showcase off). */
internal val TICKER_NAME_PAGES = setOf("Fleet", "Activity")

/** The person's choice for the tray, kept beside the other motion and refresh preferences. */
internal object LiveTickerPreference {
    const val KEY = "liveTicker"
    var mode by mutableStateOf(TickerMode.fromStored(runCatching { MotionPreferences().number(KEY, TickerMode.Automatic.stored) }.getOrNull()))
        private set

    fun choose(value: TickerMode, prefs: MotionPreferences = MotionPreferences()) {
        mode = value
        prefs.putNumber(KEY, value.stored)
    }

    /** In memory only, so a test can set the mode without writing the owner's preferences. */
    fun preview(value: TickerMode) { mode = value }
}

// ---- asking a page to land somewhere and light it ----

/** One request to land on part of a page and light it. [token] is new for every request, so asking again lights it again. */
class FocusRequest(val target: TickerTarget, val token: Long, private val madeAt: Long) {
    /** A request nobody took within a few seconds is stale: a page opened much later must not light something nobody asked for. */
    fun fresh(now: Long = System.nanoTime()) = now - madeAt < FOCUS_FRESH_NANOS
}

private const val FOCUS_FRESH_NANOS = 4_000_000_000L

/**
 * The one place a request waits for the page it is for. A page reads [pending] for the targets it understands, does what
 * it does (scroll, select, light) and hands the request back with [consume]; a page that does not understand a target leaves it.
 */
@Stable class PageFocus(private val clock: () -> Long = System::nanoTime) {
    var pending by mutableStateOf<FocusRequest?>(null)
        private set
    private var counter = 0L

    fun request(target: TickerTarget) { pending = FocusRequest(target, ++counter, clock()) }

    /** The pending request when [accepts] its target and it is still fresh. */
    fun take(accepts: (TickerTarget) -> Boolean): FocusRequest? = pending?.takeIf { accepts(it.target) && it.fresh(clock()) }

    fun consume(request: FocusRequest) { if (pending?.token == request.token) pending = null }
}

val LocalPageFocus = staticCompositionLocalOf<PageFocus?> { null }

// ---- going to a target ----

/**
 * What a click goes through. The window supplies how to show a page, a guest and a task; this maps every target of the tray
 * onto them and, where the page can light something, leaves a request for it in [focus].
 */
internal class TickerNav(
    private val showPage: (String) -> Unit,
    private val showGuest: (ResourceId, Boolean) -> Unit,
    val focus: PageFocus,
) {
    fun go(target: TickerTarget) {
        when (target) {
            is TickerTarget.Hood -> { focus.request(target); showPage("Under the Hood") }
            TickerTarget.Desk -> showPage("Desk")
            TickerTarget.Scan -> { focus.request(target); showPage("Scan") }
            is TickerTarget.Vpn -> { focus.request(target); showPage("VPN") }
            TickerTarget.Tools -> showPage("Tools")
            TickerTarget.Storage -> showPage("Storage")
            TickerTarget.Bluetooth -> showPage("Bluetooth")
            is TickerTarget.Activity -> { focus.request(target); showPage("Activity") }
            is TickerTarget.Fleet -> target.guest?.let { showGuest(it, target.console) } ?: showPage("Fleet")
        }
    }
}

/**
 * True when the tray must not show, and must not watch either: browser-hosted mode (there is no Under the Hood there), a minimised
 * or hidden window, an immersive console, Under the Hood itself, any dialog or overlay (a chart lens, Settings), the create wizard
 * and the notification centre. Consoles on screen and toasts are handled where they are drawn.
 */
internal fun tickerBlocked(web: Boolean, traceView: Boolean, minimized: Boolean, immersive: Boolean, page: String, modalOpen: Boolean, creating: Boolean, notices: Boolean) =
    web || !traceView || minimized || immersive || page == "Under the Hood" || modalOpen || creating || notices

// ---- where the readings come from ----

internal class TraceReading(val vitals: TraceVitals, val metrics: TraceMetrics, val loop: TraceLiveLoop)

/** The live trace, read once per sample: the vitals are O(60), the metrics one pass over the ring, neither on the UI thread. */
internal fun readLiveTrace(now: Instant) = TraceReading(Trace.vitals(now), TraceMetrics.from(Trace.snapshot(), now), Trace.liveLoop.value)

/**
 * One reading for the tile model. [activity] runs on the UI thread (it reads the window's own state, which is confined
 * there); the trace is read on a worker. Nothing here listens to the trace: a reading is only taken when asked for.
 */
internal class TickerFeed(
    private val activity: () -> TickerActivity,
    private val trace: (Instant) -> TraceReading = ::readLiveTrace,
    val clock: () -> Instant = Instant::now,
) {
    suspend fun read(names: Boolean, showcase: Boolean): TickerInput {
        val seen = activity()
        val now = clock()
        val reading = withContext(Dispatchers.Default) { trace(now) }
        return TickerInput(now, reading.vitals, reading.metrics, reading.loop, seen, showcase, names)
    }
}

/** What the route observer last saw for each session: it observes only the one in view, so the others keep their last reading. */
internal class RouteMemory {
    private val seen = HashMap<Long, Pair<Long, TickerRoute>>()

    fun observe(state: RouteTagState) {
        if (state is RouteTagState.Observed) {
            val o = state.observation
            seen[state.key.session] = state.key.epoch to TickerRoute(o.kind, o.vpnKind, o.medium)
        }
    }

    /** The reading for [session] only while it is for the same connection (a reconnect starts a new epoch). */
    fun routeOf(session: Long, epoch: Long): TickerRoute? = seen[session]?.takeIf { it.first == epoch }?.second
    fun retain(open: Set<Long>) { seen.keys.retainAll(open) }
}

internal fun classifyFailure(error: String?): TickerFailure = when {
    error == null -> TickerFailure.Other
    error == CERTIFICATE_ERROR -> TickerFailure.Certificate
    error == LOGIN_REFUSED || error == SESSION_REFUSED -> TickerFailure.SignIn
    "could not be reached" in error -> TickerFailure.Unreachable
    else -> TickerFailure.Other
}

/**
 * Reads the window's own state into the small snapshot the tile model takes: every open connection, and for the one in view
 * its running tasks, upload and kept consoles. Called on the UI thread, a few property reads each; it opens nothing.
 */
internal class TickerReader(
    private val sessions: Sessions,
    private val profiles: () -> List<ConnectionProfile>,
    private val route: () -> RouteTagState,
    private val scan: ScanSession,
    private val vpn: VpnSession,
    private val tools: ToolsSession,
    private val routes: RouteMemory = RouteMemory(),
    private val clock: () -> Instant = Instant::now,
) {
    fun read(): TickerActivity {
        val all = sessions.state.value
        routes.observe(route())
        routes.retain(all.open.map { it.id }.toSet())
        val known = profiles()
        val list = all.open.mapNotNull { open -> session(open, open.id == all.activeId, known) }
        val view = all.active.workspace.state.value
        val now = clock()
        val running = view.tasks.orEmpty().filter { it.outcome == TaskOutcome.Running }
        val failed = view.tasks.orEmpty().filter { it.outcome == TaskOutcome.Unsuccessful && (it.endedAt ?: it.startedAt)?.let { at -> Duration.between(at, now).seconds in 0..300 } == true }
            .maxByOrNull { it.endedAt ?: it.startedAt ?: Instant.EPOCH }
        fun guestName(task: ClusterTask) = view.overview?.guests?.firstOrNull { it.id.toString() == task.subjectId }?.name ?: task.subjectId.takeIf { it.isNotBlank() }
        val upload = view.storageActions.submissions.firstOrNull { it.action == StorageAction.Upload && it.phase == PowerPhase.Accepted && (it.totalBytes ?: 0L) > it.bytesSent }
        val progress = scan.progress
        return TickerActivity(
            sessions = list,
            scan = if (scan.running) TickerScan(progress?.takeIf { it.total > 0 }?.let { (it.scanned * 100 / it.total).coerceIn(0, 100) } ?: 0, progress?.hits?.map { it.ip }?.distinct()?.size ?: 0) else null,
            vpnBusy = vpn.busy != null, toolRunning = tools.running,
            tasks = running.map { TickerTask(it.upid, taskTypeName(it.type), guestName(it), it.startedAt) },
            failedTask = failed?.let { TickerTask(it.upid, taskTypeName(it.type), guestName(it), it.startedAt) },
            uploading = upload != null, uploadPercent = upload?.let { (it.bytesSent * 100 / (it.totalBytes ?: 1L)).toInt().coerceIn(0, 100) },
            kept = view.keptConsoles.map { id -> TickerConsole(id, view.overview?.guests?.firstOrNull { it.resource == id }?.name) },
        )
    }

    private fun session(open: OpenSession, inView: Boolean, known: List<ConnectionProfile>): TickerSession? {
        val ws = open.workspace.state.value
        val phase = when {
            ws.demo -> TickerSessionPhase.Demo
            connectionFailed(ws) -> TickerSessionPhase.Failed
            ws.connected && ws.overview == null -> TickerSessionPhase.Loading
            ws.connected -> TickerSessionPhase.Connected
            else -> return null
        }
        val info = open.workspace.diagnostic()
        return TickerSession(open.id, inView, phase, ws.kind, sessionLabel(ws, known.find { it.id == open.profileId }), ws.address.ifBlank { ws.failedAddress },
            signIn = info?.authKind, pinned = if (phase == TickerSessionPhase.Demo) null else info?.let { it.pin != null },
            route = routes.routeOf(open.id, ws.connectionEpoch), live = ws.live, liveSeconds = ws.liveIntervalSeconds, backoffSeconds = ws.liveBackoffSeconds,
            updatedAt = ws.updatedAt, failure = if (phase == TickerSessionPhase.Failed) classifyFailure(ws.error) else null)
    }
}

/** Where the Under the Hood page gets the connections for its NETWORK card. Provided by the window; null where there is none. */
internal val LocalTickerActivity = staticCompositionLocalOf<(() -> TickerActivity)?> { null }

// ---- the tray's own state ----

/**
 * Everything the tray remembers for the window: the latest tiles, whether it is open, the pin and the dismissal. It lives
 * above the per-connection part of the window, so switching connections keeps the pin. [clock] is the test clock's seam.
 */
@Stable internal class LiveTickerState(val clock: () -> Instant = Instant::now, private val presence: TickerPresence = TickerPresence()) {
    var model by mutableStateOf(TickerModel())
        private set
    var expanded by mutableStateOf(false)
        private set
    var pinned by mutableStateOf(false)
        private set
    var dismissed by mutableStateOf(false)
        private set
    /** Counts every new sample, so a test (or a motion) can tell one arrived. */
    var samples by mutableStateOf(0)
        private set
    /** Counts every change of what keeps the tray open (a cue, hover, focus, pin, collapse), so the wake-up for settling is set again. */
    var revision by mutableStateOf(0)
        private set
    private var previous: TickerMarks? = null
    private var holding = false

    /** One reading arrived: new tiles, and anything that changed since the last one may open the tray. */
    fun apply(input: TickerInput, mode: TickerMode) {
        val next = HoodTicker.build(input)
        val cues = HoodTicker.cues(previous, next.marks)
        previous = next.marks
        if (cues.isNotEmpty() && mode == TickerMode.Automatic) { presence.cue(clock()); revision++ }
        if (next != model) model = next
        samples++
        refresh(mode)
    }

    /** The tray stopped watching (hidden): the next reading is a baseline again, so what happened meanwhile never pops it open. */
    fun forget() { previous = null }

    /** Recompute whether it is open from the clock. Cheap; called after every change and when an opening is due to end. */
    fun refresh(mode: TickerMode) {
        val open = presence.expanded(mode, clock())
        if (open != expanded) expanded = open
        if (presence.pinned != pinned) pinned = presence.pinned
        if (presence.dismissed != dismissed) dismissed = presence.dismissed
    }

    fun visible(mode: TickerMode) = presence.visible(mode, !model.isEmpty)
    fun settlesAt(mode: TickerMode): Instant? = presence.settlesAt(mode, clock())

    fun hold(on: Boolean, mode: TickerMode) {
        if (on == holding) return
        holding = on
        presence.hold(on, clock())
        revision++
        refresh(mode)
    }

    fun open(mode: TickerMode) { presence.open(clock()); revision++; refresh(mode) }
    fun collapse(mode: TickerMode) { holding = false; presence.collapse(); revision++; refresh(mode) }
    fun togglePin(mode: TickerMode) { presence.pinned = !presence.pinned; revision++; refresh(mode) }
    fun dismiss(mode: TickerMode) { holding = false; presence.dismiss(); revision++; refresh(mode) }
    /** The person chose a mode in Settings: that also brings back a tray they dismissed. */
    fun restore(mode: TickerMode) { presence.restore(); revision++; refresh(mode) }
}
