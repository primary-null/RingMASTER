package local.ringleader.desktop

import local.ringleader.core.FleetRadios
import local.ringleader.core.Guest
import local.ringleader.core.GuestRadios
import local.ringleader.core.Radio
import local.ringleader.core.RadioCertainty
import local.ringleader.core.RadioChange
import local.ringleader.core.RadioMeans
import local.ringleader.core.RadioTie
import local.ringleader.core.RadioUse
import local.ringleader.core.ResourceId
import local.ringleader.core.radioMeansText
import local.ringleader.core.usersOf
import local.ringleader.core.RadioBus as CoreRadioBus
import local.ringleader.core.RadioKind as CoreRadioKind

/*
 * From what core reads about a server's radios (which adapter each node has and which guest holds it) to the board the
 * Radios screens show. Everything here is pure, so it is tested without a server.
 */

const val RADIOS_NOT_CONNECTED = "Connect to a server to see the radios of its nodes and guests."
const val RADIOS_CT_SCAN = "A container cannot scan. Its adapter belongs to the node's kernel."
const val RADIOS_VM_STOPPED = "Start the VM to scan inside it."
const val RADIOS_GUEST_ONLY = "Named only in the guest's configuration"

/** A node as the board needs it: its name and whether it is online. */
data class RadioNodeRow(val name: String, val online: Boolean)

/**
 * What a card needs beside the fleet. [running] and [names] are the guests' status and names from the inventory, which is
 * newer than the radios read and wins where it knows the guest: a guest created a moment ago has no name in the read yet.
 * [guestWifi] says whether a Wi-Fi scan inside a VM can run in this build.
 */
internal data class BoardFacts(
    val session: String?,
    val running: Map<ResourceId, Boolean> = emptyMap(),
    val guestWifi: Boolean = false,
    val names: Map<ResourceId, String> = emptyMap(),
) {
    fun nameOf(guest: ResourceId, read: String): String = names[guest]?.takeIf { it.isNotBlank() } ?: read
}

internal fun radioVia(means: RadioMeans) = when (means) {
    RadioMeans.UsbDevice, RadioMeans.UsbPort -> RadioVia.HostDevice
    RadioMeans.UsbMapping, RadioMeans.PciMapping -> RadioVia.Mapping
    RadioMeans.PciDevice -> RadioVia.PciPassthrough
    RadioMeans.ContainerDevice, RadioMeans.ContainerUsbBus, RadioMeans.ContainerInterface, RadioMeans.ContainerDbus,
    RadioMeans.ContainerRfkill, RadioMeans.ContainerVhci -> RadioVia.DeviceNode
}

private fun kindOf(kind: CoreRadioKind) = when (kind) {
    CoreRadioKind.Bluetooth -> RadioKind.Bluetooth
    CoreRadioKind.WiFi -> RadioKind.WiFi
}

private fun sureOf(certainty: RadioCertainty) = when (certainty) {
    RadioCertainty.Certain -> RadioSure.Certain
    RadioCertainty.Likely -> RadioSure.Likely
    RadioCertainty.Guess -> RadioSure.Guess
}

private fun busOf(bus: CoreRadioBus?) = when (bus) {
    CoreRadioBus.Usb -> RadioBus.Usb
    CoreRadioBus.Pci -> RadioBus.Pci
    null -> RadioBus.Unknown
}

/** An entry holds an adapter when it names one, or one of several identical ones. A broad rule only reaches it. */
private fun firm(use: RadioUse) = use.tie == RadioTie.Exact || use.tie == RadioTie.OneOfSeveral

private fun guestKey(guest: ResourceId, kind: String) = "${guest.remote}/$kind/${guest.vmid}"

internal fun radioGuestRef(use: RadioUse, facts: BoardFacts): RadioGuestRef {
    val running = facts.running[use.guest] ?: use.running
    val name = facts.nameOf(use.guest, use.guestName)
    return RadioGuestRef(guestKey(use.guest, use.guestKind), use.guest.vmid, name, use.guestKind, use.node, running, use.guest, facts.session)
}

private fun radioGuestRef(guest: GuestRadios, facts: BoardFacts): RadioGuestRef {
    val running = facts.running[guest.guest] ?: guest.running
    val name = facts.nameOf(guest.guest, guest.name)
    return RadioGuestRef(guestKey(guest.guest, guest.kind), guest.guest.vmid, name, guest.kind, guest.node, running, guest.guest, facts.session)
}

/**
 * What an adapter of a node is doing now, by rule, with the sentence that says why. An offline node disables its
 * adapters; an adapter given to a guest follows that guest (running, stopped, or set for its next start); a free one is
 * active when the node itself has its interface up, and ready otherwise.
 */
internal fun radioNow(radio: Radio, use: RadioUse?, guest: RadioGuestRef?, nodeOnline: Boolean): Pair<RadioState, String> {
    if (!nodeOnline) return RadioState.Disabled to "The node is offline, so this adapter cannot be reached."
    if (use != null && guest != null) return heldRadioNow(use, guest)
    val nic = radio.interfaceName?.let(::shortInterface)
    return when (radio.interfaceActive) {
        true -> RadioState.Active to "The node itself is using it: its interface $nic is up."
        false -> RadioState.Neutral to "Free on the node. Its interface $nic is down."
        null -> RadioState.Neutral to "Free on the node: no guest has it."
    }
}

private fun heldRadioNow(use: RadioUse, guest: RadioGuestRef): Pair<RadioState, String> = when {
    use.change == RadioChange.AtNextStart ->
        RadioState.Neutral to "Set for ${guest.label}. It takes effect when the guest next starts."
    guest.running && use.change == RadioChange.RemovedAtNextStart ->
        RadioState.Active to "${guest.label} is running and has this adapter. It is taken away when the guest next starts."
    guest.running ->
        RadioState.Active to "${guest.label} is running and has this adapter."
    else ->
        RadioState.Inactive to "${guest.label} is stopped. The adapter is kept for it and does nothing until it starts."
}

private val addressNamed = Regex("^(wlx|enx)[0-9a-f]{8}([0-9a-f]{4})$")

/** An interface named after its hardware address (wlx and twelve digits) is shown by its last four digits only. */
internal fun shortInterface(name: String): String {
    val match = addressNamed.matchEntire(name) ?: return name
    return match.groupValues[1] + "…" + match.groupValues[2]
}

/** The adapter's name. One known only as a network interface of the node is named for what it is, not for its address. */
internal fun radioName(radio: Radio): String {
    val nic = radio.interfaceName
    if (nic == null || radio.name != "Wi-Fi interface $nic") return radio.name
    val base = if (radio.bus == CoreRadioBus.Usb) "USB Wi-Fi adapter" else "Wi-Fi adapter"
    val tail = addressNamed.matchEntire(nic)?.groupValues?.get(2)
    return if (tail != null) "$base …$tail" else "$base $nic"
}

/** Where the adapter sits, as one short line: "PCI 0000:00:14.3 · interface wlo1". */
internal fun radioPort(radio: Radio): String? {
    val parts = listOfNotNull(
        radio.address?.let { "${radio.bus?.label ?: "Port"} $it" },
        radio.interfaceName?.let { "interface ${shortInterface(it)}" },
        RADIOS_GUEST_ONLY.takeIf { !radio.listedOnNode },
    )
    return parts.joinToString(" · ").ifBlank { null }
}

/** Why no scan can run with this adapter, or null when one can: only inside a running VM that holds it. */
private fun scanRefusal(kind: RadioKind, holder: RadioHolder?, guestWifi: Boolean): String? = when {
    holder == null -> RADIOS_NODE_SCAN
    !holder.guest.isVm -> RADIOS_CT_SCAN
    !holder.guest.running -> RADIOS_VM_STOPPED
    kind == RadioKind.WiFi && !guestWifi -> WIFI_UNAVAILABLE
    else -> null
}

/** One adapter of the fleet as a card. */
internal fun radioCard(fleet: FleetRadios, radio: Radio, facts: BoardFacts, nodeOnline: Boolean = true): RadioCard {
    val use = usersOf(fleet, radio).firstOrNull(::firm)
    val holder = use?.let { RadioHolder(radioGuestRef(it, facts), radioVia(it.means), it.key) }
    val (state, why) = radioNow(radio, use, holder?.guest, nodeOnline)
    val kind = kindOf(radio.kind)
    val refusal = scanRefusal(kind, holder, facts.guestWifi)
    return RadioCard(
        id = radio.id, kind = kind, name = radioName(radio), hostKey = radio.node, vendor = radio.vendorName, ids = radio.ids,
        bus = busOf(radio.bus), port = radioPort(radio), sure = sureOf(radio.certainty), holder = holder,
        scan = RadioScanRoute.InGuest.takeIf { refusal == null }, scanRefusal = refusal?.let { RadioNote(it) },
        state = state, stateWhy = why,
    )
}

/**
 * The board for one session. [fleet] is null before the first read. [local] are this computer's own adapters, [failure]
 * the sentence of a read that failed (with [retry] as its button), [running] and [names] the guests' status and names
 * from the inventory.
 */
internal fun radiosBoard(
    fleet: FleetRadios?, nodes: List<RadioNodeRow>, session: String?, connected: Boolean, pdm: Boolean,
    loading: Boolean = false, failure: String? = null, retry: (() -> Unit)? = null,
    local: List<RadioCard> = emptyList(), guestWifi: Boolean = false, running: Map<ResourceId, Boolean> = emptyMap(),
    names: Map<ResourceId, String> = emptyMap(),
): RadiosBoard {
    val facts = BoardFacts(session, running, guestWifi, names)
    // A direct connection shows its nodes, even before the first read. Anything else shows this computer and says why.
    val direct = connected && !pdm && fleet?.unavailable == null
    val read = fleet.takeIf { direct }
    val thisComputer = RadioHost(THIS_COMPUTER_KEY, THIS_COMPUTER_LABEL, RadioHostKind.ThisComputer)
    val nodeHosts = nodes.takeIf { direct }.orEmpty().map { node ->
        val notes = read?.nodes?.firstOrNull { it.node == node.name }?.notes.orEmpty()
        RadioHost(node.name, node.name, RadioHostKind.Node, node.online, notes.map { RadioNote(it) }, session)
    }
    val hosts = listOf(thisComputer) + nodeHosts
    val online = hosts.associate { it.key to it.online }
    val nodeRadios = read?.let { known ->
        known.radios.filter { it.node in online }.map { radioCard(known, it, facts, online[it.node] != false) }
    }.orEmpty()
    val channels = read?.guests.orEmpty().flatMap { guest ->
        guest.spiceSlots.map { slot -> RadioChannel(radioGuestRef(guest, facts), slot) }
    }
    val why = when {
        !connected -> RADIOS_NOT_CONNECTED
        !direct -> RADIOS_PDM
        else -> null
    }
    val notes = buildList {
        why?.let { add(RadioNote(it)) }
        failure?.let { add(RadioNote(it, retry?.let { again -> RadioAction("Try again", again) }, warning = true)) }
        read?.notes.orEmpty().forEach { add(RadioNote(it)) }
    }
    return RadiosBoard(hosts, local + nodeRadios, channels, notes, fleet?.readAt, loading)
}

/** A guest's entries that reach a radio without holding one adapter firmly (a broad container rule, an unresolved entry). */
internal fun looseRadioLines(fleet: FleetRadios?, guest: Guest): List<GuestRadioLine> {
    val held = fleet?.guests?.firstOrNull { it.guest == guest.resource } ?: return emptyList()
    return held.uses.filterNot(::firm).map { use ->
        val radio = use.radioId?.let { fleet.radio(it) }
        val means = radioMeansText(use)
        GuestRadioLine(
            entry = use.key, kind = radio?.let { kindOf(it.kind) }, name = radio?.name ?: means, via = radioVia(use.means),
            detail = use.note ?: means.takeIf { radio != null }, sure = RadioSure.Guess, radioId = radio?.id, hostName = radio?.node,
        )
    }
}
