package local.ringleader.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.time.Instant

/*
 * What the radios inside a VM look like from the host: one probe that lists the Bluetooth controllers and Wi-Fi interfaces
 * the VM itself sees, and one Wi-Fi scan through such an interface. The QEMU guest agent is the first way to reach the guest.
 * The command, the text it prints and the parsers do not depend on it: [GuestTextTransport] is the seam, so a helper
 * installed in a guest (or on a node) can run the same command and hand the same text to the same parsers.
 */

/**
 * Why a guest radio call cannot run, or did not. [sentence] is the plain-language line. [engineCouldHelp] marks the ones a
 * helper inside the guest would get around (the guest agent is not the way in), so a screen can offer to set it up.
 * The sentences are the ones the Bluetooth scan already shows.
 */
enum class GuestRefusal(val sentence: String, val engineCouldHelp: Boolean) {
    /** A container: it has no guest agent. */
    Container("Guest scans need a QEMU VM with the guest agent.", true),
    /** Stopped, or a template. */
    NotRunning(GUEST_BT_NOT_RUNNING, false),
    /** Connected through Proxmox Datacenter Manager: it does not carry guest-agent calls. */
    PdmSession(GUEST_BT_NEED_DIRECT, true),
    /** The VM configuration has not loaded yet, so the operating system and the agent option are unknown. */
    ConfigLoading(GUEST_BT_LOADING, false),
    WindowsGuest(GUEST_BT_WINDOWS, true),
    /** The guest agent option is off in the VM's settings. */
    AgentOff(GUEST_BT_AGENT_OFF, true),
    /** The token may not run commands in the guest (HTTP 403). */
    NoPermission(GUEST_BT_NO_PERMISSION, true),
    /** The agent did not answer: not installed in the guest, or not running. */
    AgentNotAnswering(GUEST_BT_AGENT, true),
    TimedOut(GUEST_BT_TIMEOUT, false),
}

/** A guest radio call that failed for a typed reason. It is an [IllegalStateException] with the sentence as its message. */
class GuestAgentFailure(val refusal: GuestRefusal) : IllegalStateException(refusal.sentence)

/**
 * Why a guest call must not start, or null when it may. [hardware] null means the VM config has not loaded. A missing or
 * `0` agent option is off. Windows `ostype` values are refused before any guest command is sent.
 */
fun guestRadioRefusal(guest: Guest, directPve: Boolean, hardware: HardwareSummary?): GuestRefusal? = when {
    guest.kind != "VM" -> GuestRefusal.Container
    guest.status != "running" || guest.template -> GuestRefusal.NotRunning
    !directPve -> GuestRefusal.PdmSession
    hardware == null -> GuestRefusal.ConfigLoading
    hardware.fields["ostype"]?.lowercase() in windowsGuestOs -> GuestRefusal.WindowsGuest
    !qemuAgentEnabled(hardware.fields["agent"]) -> GuestRefusal.AgentOff
    else -> null
}

fun guestRadioSource(guest: Guest): WifiSource = guestBluetoothSource(guest)

enum class GuestRadioKind { Bluetooth, WiFi }

/** One Bluetooth controller or Wi-Fi interface the guest itself sees. */
data class GuestRadio(
    val kind: GuestRadioKind,
    val id: String,                 // hci0, wlan0, phy1
    val name: String,
    val address: String? = null,
    /** On and unblocked. Null when the guest did not say. */
    val powered: Boolean? = null,
    val block: RadioBlock = RadioBlock.Unknown,
    /** Wi-Fi: associated with a network. Null for Bluetooth (the probe lists no devices) and when the guest did not say. */
    val connected: Boolean? = null,
    val hardware: AdapterHardware = AdapterHardware(),
    val phy: String? = null,
    /** False for a Wi-Fi radio the kernel knows that has no network interface. */
    val hasInterface: Boolean = true,
) {
    fun toWifiAdapter() = WifiAdapter(id, name, address, powered, block, hardware, phy, hasInterface = hasInterface)
    fun toBtAdapter() = BtAdapter(id, name, address, powered, null, hardware.usbVendorId, hardware.usbProductId)
}

data class GuestRadioProbe(
    val source: WifiSource,
    val radios: List<GuestRadio>,
    val at: Instant,
    /** Plain-language note: the guest sees no radio, or the answer was cut short. */
    val note: String? = null,
    /** False when the script was cut off before its end, so the list may be short. */
    val complete: Boolean = true,
    /** Which optional tools the guest had (nmcli, iw, bluetoothctl ...). What a helper has to bring along when they are missing. */
    val tools: Set<String> = emptySet(),
    /** Who ran the probe in the guest; 0 is root. */
    val uid: Int? = null,
) {
    val bluetooth: List<GuestRadio> get() = radios.filter { it.kind == GuestRadioKind.Bluetooth }
    val wifi: List<GuestRadio> get() = radios.filter { it.kind == GuestRadioKind.WiFi }
}

const val GUEST_RADIO_NONE = "This VM sees no Bluetooth controller and no Wi-Fi adapter."
const val GUEST_RADIO_CUT_SHORT = "The probe was cut short, so the list may be missing radios."
const val GUEST_RADIO_TRUNCATED = "The output from the VM was truncated."

/** The command that runs the probe once. Every transport sends this same command. */
fun guestRadioProbeCommand(): List<String> = radioScriptCommand(RadioScripts.probe, 20)

// ---------------------------------------------------------------------------------------------------------------
// Bluetooth tools: whichever of bluetoothctl, hciconfig, hcitool and btmgmt the guest has
// ---------------------------------------------------------------------------------------------------------------

/** What one tool said about one controller. Any field is null when that tool does not say it. */
internal data class BtFact(
    val id: String? = null, val address: String? = null, val name: String? = null, val powered: Boolean? = null,
    val usbVendorId: Int? = null, val usbProductId: Int? = null,
)

private val radioAnsi = Regex(Char(27) + "\\[[0-9;?]*[A-Za-z]")
private fun String.plain() = radioAnsi.replace(this, "")

/** `hciconfig -a`: "hci0:  Type: Primary  Bus: USB", "BD Address: AA:BB:...", "UP RUNNING" or "DOWN", "Name: 'host #0'". */
internal fun parseHciconfig(text: String): List<BtFact> {
    val out = mutableListOf<BtFact>()
    var current: BtFact? = null
    for (raw in text.plain().lineSequence()) {
        val line = raw.trim()
        val head = Regex("^(hci\\d+):").find(line)
        when {
            head != null -> { current?.let { out += it }; current = BtFact(id = head.groupValues[1]) }
            current == null -> {}
            line.startsWith("BD Address:") -> current = current!!.copy(address = normalizeBtAddress(line.removePrefix("BD Address:").trim().substringBefore(' ')))
            Regex("^(UP|DOWN)\\b").containsMatchIn(line) -> current = current!!.copy(powered = line.startsWith("UP"))
            line.startsWith("Name:") -> current = current!!.copy(name = line.removePrefix("Name:").trim().trim('\'').takeIf { it.isNotEmpty() })
        }
    }
    current?.let { out += it }
    return out
}

/** `hcitool dev`: "Devices:" then "<TAB>hci0<TAB>AA:BB:CC:DD:EE:FF". */
internal fun parseHcitoolDev(text: String): List<BtFact> = text.plain().lineSequence().mapNotNull { raw ->
    val parts = raw.trim().split(Regex("\\s+"))
    if (parts.size >= 2 && parts[0].matches(Regex("hci\\d+"))) normalizeBtAddress(parts[1])?.let { BtFact(id = parts[0], address = it) } else null
}.toList()

/** `btmgmt info`: "hci0:<TAB>Primary controller", "addr AA:BB... version ...", "current settings: powered ...", "name host". */
internal fun parseBtmgmtInfo(text: String): List<BtFact> {
    val out = mutableListOf<BtFact>()
    var current: BtFact? = null
    for (raw in text.plain().lineSequence()) {
        val line = raw.trim()
        val head = Regex("^(hci\\d+):").find(line)
        when {
            head != null -> { current?.let { out += it }; current = BtFact(id = head.groupValues[1]) }
            current == null -> {}
            line.startsWith("addr ") -> current = current!!.copy(address = normalizeBtAddress(line.removePrefix("addr ").substringBefore(' ')))
            line.startsWith("current settings:") -> current = current!!.copy(powered = "powered" in line.removePrefix("current settings:").trim().split(' '))
            line.startsWith("name ") -> current = current!!.copy(name = line.removePrefix("name ").trim().takeIf { it.isNotEmpty() })
        }
    }
    current?.let { out += it }
    return out
}

/** `bluetoothctl list`: "Controller AA:BB:CC:DD:EE:FF name [default]". Listed in controller order. */
internal fun parseBluetoothctlList(text: String): List<BtFact> = text.plain().lineSequence().mapNotNull { raw ->
    val m = Regex("^Controller\\s+([0-9A-Fa-f:]{17})\\s*(.*)$").matchEntire(raw.trim()) ?: return@mapNotNull null
    normalizeBtAddress(m.groupValues[1])?.let { BtFact(address = it, name = m.groupValues[2].removeSuffix("[default]").trim().takeIf { n -> n.isNotEmpty() }) }
}.toList()

/** `bluetoothctl show <address>`: Name, Alias, Powered and the Modalias that carries the USB ids. */
internal fun parseBluetoothctlShow(text: String): BtFact? {
    var address: String? = null; var name: String? = null; var alias: String? = null; var powered: Boolean? = null
    var vendor: Int? = null; var product: Int? = null
    for (raw in text.plain().lineSequence()) {
        val line = raw.trim()
        when {
            line.startsWith("Controller ") -> address = normalizeBtAddress(line.removePrefix("Controller ").trim().substringBefore(' '))
            line.startsWith("Name:") -> name = line.removePrefix("Name:").trim().takeIf { it.isNotEmpty() }
            line.startsWith("Alias:") -> alias = line.removePrefix("Alias:").trim().takeIf { it.isNotEmpty() }
            line.startsWith("Powered:") -> powered = line.removePrefix("Powered:").trim().equals("yes", true)
            line.startsWith("Modalias:") -> Regex("usb:v([0-9A-Fa-f]{4})p([0-9A-Fa-f]{4})").find(line)?.let { vendor = it.groupValues[1].toInt(16); product = it.groupValues[2].toInt(16) }
        }
    }
    return address?.let { BtFact(address = it, name = alias ?: name, powered = powered, usbVendorId = vendor, usbProductId = product) }
}

/** Pairs each controller the kernel lists with what the Bluetooth tools said, by controller name first, then by USB ids, then by order. */
private fun bluetoothRadios(frame: RadioFrame, switches: List<RfkillSwitch>): List<GuestRadio> {
    val byId = (frame.section("hciconfig")?.let(::parseHciconfig).orEmpty() + frame.section("btmgmt-info")?.let(::parseBtmgmtInfo).orEmpty() +
        frame.section("hcitool-dev")?.let(::parseHcitoolDev).orEmpty()).filter { it.id != null }.groupBy { it.id }
    val listed = frame.section("bluetoothctl-list")?.let(::parseBluetoothctlList).orEmpty()
    val shown = frame.sectionsWith("bluetoothctl-show").mapNotNull { (_, text) -> parseBluetoothctlShow(text) }
    val records = frame.records("bt")
    val bluez = listed.map { l -> shown.firstOrNull { it.address == l.address }?.let { s -> s.copy(name = s.name ?: l.name) } ?: l }
    val used = HashSet<String>()
    return records.mapIndexedNotNull { index, r ->
        val id = r.field("id") ?: return@mapIndexedNotNull null
        val hardware = radioHardware(r)
        val facts = byId[id].orEmpty()
        val known = facts.firstNotNullOfOrNull { it.address }
        // BlueZ lists controllers by address. A controller another tool already named by id is matched by that address;
        // otherwise by the USB ids in its Modalias, and last by position when both lists have the same length.
        val fromBlueZ = if (known != null) bluez.firstOrNull { it.address == known }
            else bluez.firstOrNull { b -> b.address !in used && b.usbVendorId != null && b.usbVendorId == hardware.usbVendorId && b.usbProductId == hardware.usbProductId }
                ?: if (records.size == bluez.size) bluez.getOrNull(index)?.takeIf { it.address !in used } else null
        fromBlueZ?.address?.let { used += it }
        val address = known ?: fromBlueZ?.address
        val name = fromBlueZ?.name ?: facts.firstNotNullOfOrNull { it.name } ?: hardware.product ?: "Bluetooth controller"
        val block = radioBlockFor(id, switches)
        // A blocked controller cannot be powered, whatever a tool printed; otherwise the first tool that knows decides.
        val blocked = block == RadioBlock.Software || block == RadioBlock.Hardware || block == RadioBlock.Both
        val powered = if (blocked) false else facts.firstNotNullOfOrNull { it.powered } ?: fromBlueZ?.powered
        GuestRadio(GuestRadioKind.Bluetooth, id, name, address, powered, block, null, hardware)
    }
}

private fun wifiRadios(frame: RadioFrame, switches: List<RfkillSwitch>): List<GuestRadio> {
    val links = frame.sectionsWith("iw-link").mapNotNull { (name, text) -> parseIwLink(text)?.let { name to it } }.toMap()
    val interfaces = frame.records("wifi").mapNotNull { r ->
        val id = r.field("id") ?: return@mapNotNull null
        val hardware = radioHardware(r)
        val block = radioBlockFor(r.field("phy"), switches)
        val connected = links[id]?.connected
            ?: if (r.field("operstate") != null || r.field("carrier") != null) r.field("operstate") == "up" && r.field("carrier") == "1" else null
        GuestRadio(GuestRadioKind.WiFi, id, hardware.product ?: hardware.driver?.let { "$it adapter" } ?: "Wi-Fi adapter", normalizeWifiAddress(r.field("mac")),
            when (block) { RadioBlock.None -> true; RadioBlock.Unknown -> null; else -> false }, block, connected, hardware, r.field("phy"))
    }
    val radiosWithoutInterface = frame.records("wifiphy").mapNotNull { r ->
        val id = r.field("id") ?: return@mapNotNull null
        val hardware = radioHardware(r)
        GuestRadio(GuestRadioKind.WiFi, id, hardware.product ?: hardware.driver?.let { "$it radio" } ?: "Wi-Fi radio", null,
            null, radioBlockFor(id, switches), null, hardware, id, hasInterface = false)
    }
    return interfaces + radiosWithoutInterface
}

/**
 * Turns the text of the probe script into a [GuestRadioProbe]. Throws [IllegalStateException] when the text is not the probe's
 * output (so a guest that answered with something else is never read as "no radios").
 */
fun parseGuestRadioProbe(stdout: String, source: WifiSource, at: Instant): GuestRadioProbe {
    val frame = parseRadioFrame(stdout, "probe")
    val switches = rfkillSwitches(frame)
    val radios = bluetoothRadios(frame, switches) + wifiRadios(frame, switches)
    val tools = frame.records("tools").firstOrNull()?.items?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty().toSet()
    val uid = frame.records("uid").firstOrNull()?.items?.firstOrNull()?.trim()?.toIntOrNull()
    val note = when {
        !frame.complete -> GUEST_RADIO_CUT_SHORT
        radios.isEmpty() -> GUEST_RADIO_NONE
        else -> null
    }
    return GuestRadioProbe(source, radios, at, note, frame.complete, tools, uid)
}

// ---------------------------------------------------------------------------------------------------------------
// Reaching the guest
// ---------------------------------------------------------------------------------------------------------------

/**
 * How the text of a radio script gets out of a guest. The QEMU guest agent is one transport ([GuestAgentTransport]); a helper
 * installed in the guest or on the node can be another. [command] is the exact command [guestRadioProbeCommand] or
 * [guestWifiScanCommand] built, a transport that cannot run a shell command may run [RadioScripts] its own way, and either
 * way the returned text is what the parsers read. Failures are [GuestAgentFailure]s so a screen can offer the right next step.
 */
fun interface GuestTextTransport {
    fun run(connection: Connection, guest: Guest, command: List<String>, firstPollSeconds: Int, budgetSeconds: Int): String
}

/** The guest agent transport: one `agent/exec`, then polls of that pid, through [PveClient.guestAgentRun]. */
class GuestAgentTransport(private val client: PveClient = PveClient(), private val sleep: (Long) -> Unit = Thread::sleep) : GuestTextTransport {
    override fun run(connection: Connection, guest: Guest, command: List<String>, firstPollSeconds: Int, budgetSeconds: Int): String {
        val status = client.guestAgentRun(connection, guest, command, firstPollSeconds, budgetSeconds, sleep)
        check(!status.truncated) { GUEST_RADIO_TRUNCATED }
        return status.out
    }
}

/** The command for a Wi-Fi scan inside a guest. [seconds] is the most one scan may take. */
fun guestWifiScanCommand(seconds: Int = 20): List<String> = wifiScanCommand(seconds)

/**
 * Probe and Wi-Fi scan of a guest. [hardware] is the loaded VM configuration; the live gateway refuses (with a typed
 * [GuestAgentFailure]) before any command is sent when [guestRadioRefusal] says so. Never called on a timer or when a page opens.
 */
interface GuestRadioGateway {
    suspend fun probe(connection: Connection, guest: Guest, hardware: HardwareSummary?): GuestRadioProbe
    suspend fun wifiScan(connection: Connection, guest: Guest, hardware: HardwareSummary?, seconds: Int = 20): WifiScan
}

class LiveGuestRadioGateway(private val transport: GuestTextTransport = GuestAgentTransport()) : GuestRadioGateway {
    private fun refuse(connection: Connection, guest: Guest, hardware: HardwareSummary?) {
        guestRadioRefusal(guest, connection.kind != PDM_KIND && guest.scope.isEmpty(), hardware)?.let { throw GuestAgentFailure(it) }
    }

    override suspend fun probe(connection: Connection, guest: Guest, hardware: HardwareSummary?): GuestRadioProbe = runInterruptible(Dispatchers.IO) {
        refuse(connection, guest, hardware)
        val text = transport.run(connection, guest, guestRadioProbeCommand(), 1, 30)
        parseGuestRadioProbe(text, guestRadioSource(guest), Instant.now())
    }

    override suspend fun wifiScan(connection: Connection, guest: Guest, hardware: HardwareSummary?, seconds: Int): WifiScan = runInterruptible(Dispatchers.IO) {
        refuse(connection, guest, hardware)
        val budget = wifiScanBudgetSeconds(seconds)
        val text = transport.run(connection, guest, guestWifiScanCommand(budget), minOf(budget, 4), budget + 25)
        parseLinuxWifiScan(text, guestRadioSource(guest), Instant.now())
    }
}
