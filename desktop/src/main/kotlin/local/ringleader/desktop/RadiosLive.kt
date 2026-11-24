package local.ringleader.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import local.ringleader.core.FleetRadios
import local.ringleader.core.Guest
import local.ringleader.core.GuestRefusal
import local.ringleader.core.HardwareSummary
import local.ringleader.core.PDM_KIND
import local.ringleader.core.RADIO_REFRESH_CADENCE
import local.ringleader.core.ResourceId
import local.ringleader.core.WifiIssue
import local.ringleader.core.WifiScanner
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState
import local.ringleader.core.guestRadioRefusal
import local.ringleader.core.readableError

/*
 * The live feeds of the Radios screens for one session: the board read through the workspace, the guest tab, and the hub
 * that ties them to this computer's Wi-Fi. What they show is built by RadiosBoardMapping.kt and WifiMapping.kt.
 */

/** How often the hub looks whether the board is due for another read while a page that shows radios is in view. */
private const val STALE_CHECK_MILLIS = 30_000L

/**
 * The live board of one session. It reads through the workspace when asked, and keeps the last answer when a read fails.
 * Every call arrives on the window's thread.
 */
internal class LiveRadiosFeed(private val scope: CoroutineScope, private val workspace: Workspace, private val session: String?) : RadiosFeed {
    private val state = MutableStateFlow(radiosBoard(null, emptyList(), session, connected = false, pdm = false))
    override val board: StateFlow<RadiosBoard> get() = state

    var fleet: FleetRadios? = null
        private set
    private var nodes = emptyList<RadioNodeRow>()
    private var connected = false
    private var pdm = false
    private var epoch = -1L
    private var failure: String? = null
    private var job: Job? = null
    private var readAtNanos = 0L
    private var running = emptyMap<ResourceId, Boolean>()
    private var names = emptyMap<ResourceId, String>()
    private var local = emptyList<RadioCard>()

    private val reading get() = job?.isActive == true

    /** This computer's own adapters, from its last Wi-Fi reading. */
    fun setLocal(cards: List<RadioCard>) {
        if (cards == local) return
        local = cards
        publish(loading = reading)
    }

    /** The session changed: another server, another node list, a guest started or stopped, or it closed. */
    fun onState(now: WorkspaceState) {
        if (now.connectionEpoch != epoch) forget(now.connectionEpoch)
        connected = now.connected && now.overview != null
        pdm = now.kind == PDM_KIND
        nodes = now.overview?.members.orEmpty().map { RadioNodeRow(it.name, it.status == "online") }
        // The inventory poll knows within seconds that a guest started, stopped, appeared or went away. The board follows
        // it at once, and the server is read again: the configs of guests that did not change are reused.
        val guests = now.overview?.guests.orEmpty()
        val status = guests.associate { it.resource to (it.status == "running") }
        val changed = radioGuestTurned(status) || (fleet != null && status.keys != running.keys)
        running = status
        names = guests.associate { it.resource to it.name }
        publish(loading = reading)
        if (changed && connected && !pdm) read(force = false)
    }

    /** A new server: the last answer belongs to the old one. */
    private fun forget(newEpoch: Long) {
        epoch = newEpoch
        job?.cancel()
        job = null
        fleet = null
        failure = null
        readAtNanos = 0
    }

    /** True when a guest that holds or reaches a radio is now running where it was stopped, or the reverse. */
    private fun radioGuestTurned(status: Map<ResourceId, Boolean>): Boolean = fleet?.guests.orEmpty().any { guest ->
        val matters = guest.reachesRadio || guest.spiceSlots.isNotEmpty()
        val was = running[guest.guest] ?: guest.running
        val isNow = status[guest.guest]
        matters && isNow != null && isNow != was
    }

    override fun refresh() = read(force = true)

    /** Reads when nothing was read yet for this server, or the last answer is older than the cadence. */
    fun readIfStale() {
        val stale = readAtNanos == 0L || System.nanoTime() - readAtNanos >= RADIO_REFRESH_CADENCE.toNanos()
        if (connected && !pdm && stale) read(force = false)
    }

    private fun read(force: Boolean) {
        if (!connected || reading) return
        val asked = epoch
        job = scope.launch {
            publish(loading = true)
            try {
                val answer = workspace.fleetRadios(force)
                if (asked == epoch) {
                    fleet = answer
                    failure = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The last answer stays on the board; the sentence is shown above it with a button to try again.
                if (asked == epoch) failure = readFailureSentence(e)
            }
            if (asked == epoch) {
                readAtNanos = System.nanoTime()
                publish(loading = false)
            }
        }
    }

    private fun readFailureSentence(e: Exception): String = if (e is IOException) readableError(e) else e.message ?: "Reading radios failed."

    private fun publish(loading: Boolean) {
        state.value = radiosBoard(
            fleet, nodes, session, connected, pdm, loading, failure, retry = ::refresh,
            local = local, guestWifi = true, running = running, names = names,
        )
    }
}

/**
 * The guest tab over the live board: the adapters the guest holds, then the entries that only reach one. Looking inside
 * and scanning run through the workspace, with the configuration the tab last showed for that guest.
 */
internal class LiveGuestRadios(
    private val feed: LiveRadiosFeed,
    private val workspace: Workspace,
    private val engine: EngineHub? = null,
) : BoardGuestRadios() {
    val setup = kotlinx.coroutines.flow.MutableStateFlow<Guest?>(null)
    private val shown = HashMap<ResourceId, HardwareSummary?>()

    override fun view(guest: Guest, hardware: HardwareSummary?, board: RadiosBoard): GuestRadiosView {
        shown[guest.resource] = hardware
        val base = super.view(guest, hardware, board)
        val taken = base.lines.map { it.entry }.toSet()
        val loose = looseRadioLines(feed.fleet, guest).filter { it.entry !in taken }
        return base.copy(lines = base.lines + loose, throughEngine = engine?.pairingFor(guest) != null)
    }

    /**
     * Why the app may not look inside this guest, by the same rule the gateway applies before it sends anything. A paired
     * Engine answers instead, so there is nothing to refuse; a refusal the Engine would get around offers to set it up.
     */
    override fun probeRefusal(guest: Guest): RadioNote? {
        if (engine?.pairingFor(guest) != null) return null
        val direct = workspace.state.value.kind != PDM_KIND
        val refusal = guestRadioRefusal(guest, direct, shown[guest.resource]) ?: return null
        val text = if (refusal == GuestRefusal.Container) RADIOS_CT_PROBE else refusal.sentence
        val setup = engine?.takeIf { refusal.engineCouldHelp }?.let { RadioAction(ENGINE_SETUP_LABEL) { setup.value = guest } }
        return RadioNote(text, setup)
    }

    override suspend fun probe(guest: Guest): GuestProbeView = guestProbeView(workspace.guestRadioProbe(guest, shown[guest.resource]))

    override suspend fun wifiScan(guest: Guest): WifiPanel {
        val scan = workspace.guestWifiScan(guest, shown[guest.resource])
        val source = WifiSourceView(guest.resource.toString(), "${guest.kind} ${guest.id} ${guest.name}", inGuest = true)
        return wifiPanel(scan, listOf(source), source.key)
    }
}

/** The VMs that hold a Wi-Fi adapter, as sources of the Wi-Fi view. Each leads to its own tab, where its config is loaded. */
private fun guestWifiSources(board: RadiosBoard, nav: RadiosNav): List<WifiSourceView> {
    val holders = board.guests.filter { guest -> guest.ref.isVm && guest.radios.any { it.kind == RadioKind.WiFi } }
    return holders.map { guest ->
        val adapter = guest.radios.first { it.kind == RadioKind.WiFi }
        val openTab = RadioAction("Open guest") { nav.openGuest(guest.ref, "Radios") }
        WifiSourceView(guest.ref.key, "${guest.ref.label} ${guest.ref.name}", adapter.title, inGuest = true, refusal = RadioNote(WIFI_IN_GUEST_TAB, openTab))
    }
}

/** The system page that fixes [issue], as a button's action, or null where no page helps. Opening it changes nothing. */
private fun settingsPageFor(issue: WifiIssue): (() -> Unit)? {
    if (wifiSettingsCommand(issue) == null) return null
    return { openWifiSettings(issue) }
}

/**
 * The hub of a live session. The board is read when [active] (a page that shows radios is in view) and again on the
 * cadence while it stays in view; never on the inventory poll. This computer's own Wi-Fi is read once when the Radios
 * page opens ([radiosOpen]), then every few seconds while the Wi-Fi view is on screen, and when the user scans.
 */
@Composable internal fun rememberLiveRadiosHub(
    state: WorkspaceState, workspace: Workspace, session: String?, nav: RadiosNav, active: Boolean,
    radiosOpen: Boolean = false, scanner: WifiScanner? = null,
): RadiosHub {
    val scope = rememberCoroutineScope()
    val feed = remember(workspace, session) { LiveRadiosFeed(scope, workspace, session) }
    val open = state.connected && state.overview != null
    val nodes = state.overview?.members.orEmpty().map { it.name to it.status }
    val guests = state.overview?.guests.orEmpty().map { it.resource to it.status }
    LaunchedEffect(feed, open, state.kind, state.connectionEpoch, nodes, guests) { feed.onState(state) }
    LaunchedEffect(feed, active, open, state.connectionEpoch) {
        while (active && open) {
            feed.readIfStale()
            delay(STALE_CHECK_MILLIS)
        }
    }
    val wifi = remember(feed, nav, scanner) {
        LiveWifiFeed(
            scope, scanner,
            guests = { guestWifiSources(feed.board.value, nav) },
            fix = ::settingsPageFor,
            onScan = { scan -> feed.setLocal(localWifiCards(scan)) },
        )
    }
    val board by feed.board.collectAsState()
    val guestStates = board.guests.map { it.ref.key to it.ref.running }
    LaunchedEffect(wifi, guestStates) { wifi.sourcesChanged() }
    LaunchedEffect(wifi, radiosOpen) { if (radiosOpen) wifi.readOnce() }
    return remember(feed, wifi, nav) { RadiosHub(feed, wifi, LiveGuestRadios(feed, workspace, GuestEngine.hub), nav, GuestEngine.hub) }
}
