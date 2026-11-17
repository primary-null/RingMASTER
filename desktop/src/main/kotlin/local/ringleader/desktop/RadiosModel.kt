package local.ringleader.desktop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import local.ringleader.core.Guest
import local.ringleader.core.HardwareSummary
import local.ringleader.core.ResourceId

/*
 * What the Radios screens show (the Radios page, the Fleet strip and guest marks, the guest page's Radios tab and the node
 * inspector's section), as plain display types. The screens read only these; the mapping from what core reads lives
 * with the feeds, in one place, so the composables never see a core scan.
 */

enum class RadioKind(val label: String, val short: String) { Bluetooth("Bluetooth", "BT"), WiFi("Wi-Fi", "Wi-Fi") }

/** How sure the app is that a device is the radio it says. A guess is said as "looks like". */
enum class RadioSure(val label: String) { Certain("Certain"), Likely("Likely"), Guess("Guess") }

/**
 * What an adapter is doing now. Each state has its own look (never colour alone) and its own word:
 * [Active] is in use (its guest is running, the node has its interface up, this computer is connected or scanning);
 * [Neutral] is on and free, with nothing using it; [Inactive] is held but dormant (its guest is stopped) or switched off;
 * [Disabled] cannot be used (blocked by a radio switch, turned off in the system, or its node is offline).
 */
enum class RadioState(val label: String) { Active("Active"), Neutral("Ready"), Inactive("Inactive"), Disabled("Disabled") }

enum class RadioBus(val label: String) { Usb("USB"), Pci("PCI"), Builtin("Built-in"), Unknown("Unknown bus") }

/** How a guest was given an adapter. */
enum class RadioVia(val label: String, val phrase: String, val help: String) {
    HostDevice("USB host device", "as a USB host device", "The node passes this USB device to the guest."),
    Mapping("Resource mapping", "through a resource mapping", "A Proxmox resource mapping names this device on the node; the guest uses the mapping."),
    Spice("SPICE channel", "through a SPICE channel", "A USB device on the computer running the console can be redirected to the guest through this channel."),
    PciPassthrough("PCI pass-through", "by PCI pass-through", "The whole PCI card is passed to the guest; the node no longer uses it."),
    DeviceNode("Device node", "as a device node", "A device file of the node is bound into the container."),
}

enum class RadioHostKind { ThisComputer, Node }

/** A button beside a sentence. Absent by default; the orchestrator can offer a way to fix what the sentence says. */
@Stable class RadioAction(val label: String, val run: () -> Unit)

/** One sentence the user should read: why something is empty, refused or uncertain, with an optional [action]. */
data class RadioNote(val text: String, val action: RadioAction? = null, val warning: Boolean = false)

/** A guest as the Radios screens name it. [session] says which open connection it belongs to, for navigation. */
data class RadioGuestRef(
    val key: String, val vmid: Int, val name: String, val kind: String, val node: String, val running: Boolean,
    val resource: ResourceId? = null, val session: String? = null,
) {
    val isVm get() = kind == "VM"
    /** "VM 120" or "CT 121". */
    val label get() = "${if (isVm) "VM" else "CT"} $vmid"
    val title get() = name.ifBlank { "Guest $vmid" }
}

/** Who has an adapter: the guest, how it was given ([via]) and its config entry ([entry], such as usb0, hostpci1, dev0). */
data class RadioHolder(val guest: RadioGuestRef, val via: RadioVia, val entry: String? = null)

/** Where a scan with an adapter runs. Nodes have no scan of their own in this slice. */
enum class RadioScanRoute { ThisComputer, InGuest }

/** One Bluetooth or Wi-Fi adapter on this computer or a node. [id] is stable across reads and unique on the board. */
data class RadioCard(
    val id: String,
    val kind: RadioKind,
    val name: String,
    val hostKey: String,
    val vendor: String? = null,
    val ids: String? = null,
    val bus: RadioBus = RadioBus.Unknown,
    val port: String? = null,
    val sure: RadioSure = RadioSure.Certain,
    val holder: RadioHolder? = null,
    val driver: String? = null,
    val scan: RadioScanRoute? = null,
    /** Why there is no scan with this adapter, when [scan] is null. */
    val scanRefusal: RadioNote? = null,
    /** What it is doing now, and the sentence that says why. */
    val state: RadioState = RadioState.Neutral,
    val stateWhy: String? = null,
) {
    val free get() = holder == null
    /** This computer's own adapter. It is never given to a guest, so "free" is not said of it. */
    val own get() = hostKey == THIS_COMPUTER_KEY
    /** "Bluetooth adapter", "Probably a Wi-Fi adapter", "Looks like a Bluetooth adapter". */
    val sureText get() = when (sure) {
        RadioSure.Certain -> "${kind.label} adapter"
        RadioSure.Likely -> "Probably a ${kind.label} adapter"
        RadioSure.Guess -> "Looks like a ${kind.label} adapter"
    }
    val title get() = name.ifBlank { "${kind.label} adapter" }
}

/** The node's own Wi-Fi link, when the node uses a Wi-Fi adapter itself. The network name is never part of it. */
data class RadioHostLink(val signalDbm: Int?, val band: WifiBand?, val channel: Int?, val generation: String? = null)

/** This computer or one node. [notes] say what could not be read there ("This token may not list USB devices"). */
data class RadioHost(
    val key: String, val name: String, val kind: RadioHostKind, val online: Boolean = true,
    val notes: List<RadioNote> = emptyList(), val session: String? = null, val link: RadioHostLink? = null,
) {
    val isNode get() = kind == RadioHostKind.Node
}

/** A SPICE USB channel of a guest: not an adapter, a way for one on the console computer to reach the guest. */
data class RadioChannel(val guest: RadioGuestRef, val entry: String)

/** A guest that has a radio, with the cards it holds and its SPICE channels. */
data class RadioGuest(val ref: RadioGuestRef, val radios: List<RadioCard>, val channels: List<RadioChannel>)

/** Everything the Radios screens show at once. */
data class RadiosBoard(
    val hosts: List<RadioHost> = emptyList(),
    val radios: List<RadioCard> = emptyList(),
    val channels: List<RadioChannel> = emptyList(),
    val notes: List<RadioNote> = emptyList(),
    val readAt: Instant? = null,
    val loading: Boolean = false,
) {
    val guests: List<RadioGuest> by lazy {
        val refs = LinkedHashMap<String, RadioGuestRef>()
        radios.mapNotNull { it.holder?.guest }.forEach { refs.putIfAbsent(it.key, it) }
        channels.forEach { refs.putIfAbsent(it.guest.key, it.guest) }
        refs.values.map { ref -> RadioGuest(ref, radios.filter { it.holder?.guest?.key == ref.key }, channels.filter { it.guest.key == ref.key }) }
    }
    fun host(key: String) = hosts.firstOrNull { it.key == key }
    fun radiosOn(hostKey: String) = radios.filter { it.hostKey == hostKey }
    fun radio(id: String) = radios.firstOrNull { it.id == id }
    fun guest(key: String) = guests.firstOrNull { it.ref.key == key }
    fun guestFor(resource: ResourceId) = guests.firstOrNull { it.ref.resource == resource }
    fun count(kind: RadioKind) = radios.count { it.kind == kind }
    val inGuests get() = radios.count { !it.free }
    val empty get() = radios.isEmpty() && channels.isEmpty()
}

// ---- Wi-Fi ----

enum class WifiBand(val label: String) { B24("2.4 GHz"), B5("5 GHz"), B6("6 GHz") }

data class WifiAdapterView(
    val name: String, val description: String? = null, val driver: String? = null, val address: String? = null,
    val connected: Boolean = false, val enabled: Boolean = true, val bands: List<WifiBand> = emptyList(),
)

/** The current link. [history] is the signal in dBm, oldest first, one reading per refresh; null where none was read. */
data class WifiLinkView(
    val ssid: String, val bssid: String? = null, val signalDbm: Int? = null, val quality: Int? = null,
    val channel: Int? = null, val band: WifiBand? = null, val widthMhz: Int? = null, val generation: String? = null,
    val rxMbps: Double? = null, val txMbps: Double? = null, val security: String? = null, val history: List<Int?> = emptyList(),
)

data class WifiApView(
    val bssid: String, val signalDbm: Int? = null, val channel: Int? = null, val band: WifiBand? = null,
    val widthMhz: Int? = null, val generation: String? = null, val connected: Boolean = false,
)

/** One network name and its access points. A blank [ssid] is a network that hides its name. */
data class WifiNetworkView(val ssid: String, val security: String? = null, val aps: List<WifiApView> = emptyList()) {
    val best get() = aps.mapNotNull { it.signalDbm }.maxOrNull()
    val connected get() = aps.any { it.connected }
    val bands get() = aps.mapNotNull { it.band }.distinct().sorted()
}

/** A place a Wi-Fi scan can run: this computer, or a running Linux VM with a Wi-Fi adapter. [inGuest] scans need a confirmation. */
data class WifiSourceView(val key: String, val label: String, val detail: String? = null, val inGuest: Boolean = false, val refusal: RadioNote? = null)

/** What the Wi-Fi view shows for the chosen source. [refusal] replaces the readout when a scan cannot run there. */
data class WifiPanel(
    val sources: List<WifiSourceView> = emptyList(),
    val source: String? = null,
    val at: Instant? = null,
    val adapters: List<WifiAdapterView> = emptyList(),
    val link: WifiLinkView? = null,
    val networks: List<WifiNetworkView> = emptyList(),
    val refusal: RadioNote? = null,
    /** Something the system said about the scan that does not stop another try (Location is off, only an old scan is known). */
    val note: RadioNote? = null,
    val scanning: Boolean = false,
    val error: String? = null,
) {
    val chosen get() = sources.firstOrNull { it.key == source }
}

// ---- inside a guest ----

/** One radio the guest was given, from its config. [radioId] links to the adapter on the map when the board has it. */
data class GuestRadioLine(
    val entry: String, val kind: RadioKind?, val name: String, val via: RadioVia, val detail: String? = null,
    val sure: RadioSure = RadioSure.Certain, val radioId: String? = null, val hostName: String? = null,
)

/** A Bluetooth controller a guest itself sees, with what it is doing now ([state], and [stateWhy] in words). */
data class GuestBtController(
    val name: String, val address: String? = null, val driver: String? = null, val powered: Boolean? = null, val blocked: Boolean? = null,
    val state: RadioState = RadioState.Neutral, val stateWhy: String = "",
)

/** A Wi-Fi interface a guest itself sees, with what it is doing now. */
data class GuestWifiInterface(
    val name: String, val address: String? = null, val driver: String? = null, val up: Boolean? = null, val blocked: Boolean? = null,
    val connected: Boolean? = null, val state: RadioState = RadioState.Neutral, val stateWhy: String = "",
)

/** What a VM itself sees, read through the guest agent after the user asked. */
data class GuestProbeView(val bluetooth: List<GuestBtController>, val wifi: List<GuestWifiInterface>, val at: Instant? = null, val note: RadioNote? = null)

/** The guest page's Radios tab. Null refusals mean the button may be used. */
data class GuestRadiosView(
    val lines: List<GuestRadioLine> = emptyList(),
    val probeRefusal: RadioNote? = null,
    val hasWifi: Boolean = false,
    val wifiRefusal: RadioNote? = null,
    /** A paired RingMASTER Engine answers for this guest, so looking inside and scanning work for a container too. */
    val throughEngine: Boolean = false,
)

// ---- feeds ----

/** The board for every open session, read again on [refresh]. Reading only: nothing is attached or changed. */
interface RadiosFeed {
    val board: StateFlow<RadiosBoard>
    fun refresh()
}

/** Wi-Fi scans. [refresh] starts a scan of the chosen source; the screen confirms first when that source is a guest. */
interface WifiFeed {
    val panel: StateFlow<WifiPanel>
    fun choose(source: String)
    fun refresh()
}

/** The Radios tab of one guest. [probe] and [wifiScan] run in the guest and are called only after the user confirmed. */
interface GuestRadiosFeed {
    fun view(guest: Guest, hardware: HardwareSummary?, board: RadiosBoard): GuestRadiosView
    suspend fun probe(guest: Guest): GuestProbeView
    suspend fun wifiScan(guest: Guest): WifiPanel
}

/** A place in the Radios page: a view and, optionally, the item to select and light on arrival (see [radioItemKey]). */
data class RadiosTarget(val view: RadiosView = RadiosView.Map, val item: String? = null)

enum class RadiosView(val label: String) { Map("Map"), Bluetooth("Bluetooth"), WiFi("Wi-Fi") }

/** One request to open the Radios page somewhere. A new [nonce] lights the item again. */
data class RadiosRequest(val target: RadiosTarget, val nonce: Long)

/** Item keys on the map: an adapter, a guest or a host. */
fun radioItemKey(card: RadioCard) = "radio:${card.id}"
fun guestItemKey(ref: RadioGuestRef) = "guest:${ref.key}"
fun hostItemKey(host: RadioHost) = "host:${host.key}"

/** Where the Radios screens send the user. The window supplies them; each is navigation only. */
class RadiosNav(
    val openGuest: (RadioGuestRef, String) -> Unit = { _, _ -> },
    val openNode: (RadioHost) -> Unit = {},
    val openRadios: (RadiosTarget) -> Unit = {},
)

/** The feeds and navigation of the Radios screens, provided once by the window. */
/** [engine] is the RingMASTER Engine of a live session; null in scenes and tests that do not need it. */
class RadiosHub(
    val board: RadiosFeed,
    val wifi: WifiFeed,
    val guests: GuestRadiosFeed,
    val nav: RadiosNav = RadiosNav(),
    val engine: EngineHub? = null,
)

val LocalRadios = staticCompositionLocalOf<RadiosHub?> { null }

/** Name of this computer on the board. */
const val THIS_COMPUTER_KEY = "this-computer"
const val THIS_COMPUTER_LABEL = "This computer"

const val RADIOS_EMPTY = "No radios found on this server."
const val RADIOS_LOADING = "Reading radios…"
const val RADIOS_PDM = "A PDM connection cannot be read for radios. Connect to a Proxmox VE node directly."
const val RADIOS_TOKEN_USB = "This token may not list USB devices."
const val RADIOS_NODE_SCAN = "Nodes cannot scan in this version. Give the adapter to a VM and scan inside it."
const val RADIOS_CT_PROBE = "A container has no guest agent, so the app cannot look inside it. Its radios are the node's."
const val WIFI_UNAVAILABLE = "Wi-Fi scanning is not available in this build."
const val GUEST_PROBE_UNAVAILABLE = "Looking inside the VM is not available in this build."

// ---- plain feeds and fakes ----

/** A feed that holds what it is given. Tests and the orchestrator's first wiring use it. */
open class FixedRadiosFeed(initial: RadiosBoard) : RadiosFeed {
    val state = MutableStateFlow(initial)
    override val board: StateFlow<RadiosBoard> get() = state
    var refreshes = 0; private set
    override fun refresh() { refreshes++ }
}

open class FixedWifiFeed(initial: WifiPanel) : WifiFeed {
    val state = MutableStateFlow(initial)
    override val panel: StateFlow<WifiPanel> get() = state
    var scans = 0; private set
    override fun choose(source: String) { state.value = state.value.copy(source = source) }
    override fun refresh() { scans++ }
}

/**
 * The guest tab from the board alone: the lines are the board's adapters held by this guest; looking inside and Wi-Fi scans
 * are refused until a real source is wired.
 */
open class BoardGuestRadios : GuestRadiosFeed {
    override fun view(guest: Guest, hardware: HardwareSummary?, board: RadiosBoard): GuestRadiosView {
        val held = board.guestFor(guest.resource)
        val lines = held?.radios.orEmpty().map { card ->
            GuestRadioLine(card.holder?.entry ?: card.id, card.kind, card.title, card.holder?.via ?: RadioVia.HostDevice,
                listOfNotNull(card.vendor, card.ids).joinToString(" · ").ifBlank { null }, card.sure, card.id, board.host(card.hostKey)?.name)
        } + held?.channels.orEmpty().map { GuestRadioLine(it.entry, null, "SPICE USB channel", RadioVia.Spice) }
        val hasWifi = lines.any { it.kind == RadioKind.WiFi }
        return GuestRadiosView(lines, probeRefusal(guest), hasWifi, if (hasWifi) probeRefusal(guest) else null)
    }
    open fun probeRefusal(guest: Guest): RadioNote? = when {
        guest.kind != "VM" -> RadioNote(RADIOS_CT_PROBE)
        else -> RadioNote(GUEST_PROBE_UNAVAILABLE)
    }
    override suspend fun probe(guest: Guest): GuestProbeView = error(GUEST_PROBE_UNAVAILABLE)
    override suspend fun wifiScan(guest: Guest): WifiPanel = error(WIFI_UNAVAILABLE)
}
