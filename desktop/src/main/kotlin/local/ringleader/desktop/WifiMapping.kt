package local.ringleader.desktop

import local.ringleader.core.AdapterHardware
import local.ringleader.core.GuestRadio
import local.ringleader.core.GuestRadioKind
import local.ringleader.core.GuestRadioProbe
import local.ringleader.core.RadioBlock
import local.ringleader.core.WifiAccessPoint
import local.ringleader.core.WifiAdapter
import local.ringleader.core.WifiIssue
import local.ringleader.core.WifiLink
import local.ringleader.core.WifiNetwork
import local.ringleader.core.WifiScan
import local.ringleader.core.WifiBand as CoreWifiBand

/*
 * From what core scans of Wi-Fi to what the screens show: the panel of a scan, this computer's own adapters as cards of
 * the board with their state, and what a VM itself sees. Everything here is pure, so it is tested without a radio.
 */

const val WIFI_NO_SCANNER = "Wi-Fi scanning is not available on this system."
const val WIFI_IN_GUEST_TAB = "A scan inside a VM runs from that VM's Radios tab, where its configuration is loaded."

internal fun wifiBandOf(band: CoreWifiBand?): WifiBand? = when (band) {
    CoreWifiBand.Ghz24 -> WifiBand.B24
    CoreWifiBand.Ghz5 -> WifiBand.B5
    CoreWifiBand.Ghz6 -> WifiBand.B6
    null -> null
}

private fun hex4(value: Int) = value.toString(16).padStart(4, '0')

/** "8086:2725" from the USB or PCI ids the system gave. */
internal fun hardwareIds(hardware: AdapterHardware): String? {
    val usbVendor = hardware.usbVendorId
    val usbProduct = hardware.usbProductId
    if (usbVendor != null && usbProduct != null) return hex4(usbVendor) + ":" + hex4(usbProduct)
    val pciVendor = hardware.pciVendorId
    val pciDevice = hardware.pciDeviceId
    if (pciVendor != null && pciDevice != null) return hex4(pciVendor) + ":" + hex4(pciDevice)
    return null
}

internal fun hardwareBus(hardware: AdapterHardware): RadioBus = when (hardware.bus?.lowercase()) {
    "usb" -> RadioBus.Usb
    "pci" -> RadioBus.Pci
    null -> RadioBus.Unknown
    else -> RadioBus.Builtin
}

/** Whether a switch or the system blocks the radio; null when the system did not say. */
private fun blocked(block: RadioBlock): Boolean? = when (block) {
    RadioBlock.None -> false
    RadioBlock.Unknown -> null
    RadioBlock.Software, RadioBlock.Hardware, RadioBlock.Both -> true
}

/**
 * What a radio is doing now, by one rule for this computer's Wi-Fi and for every radio a guest reports (through the guest
 * agent or the RingMASTER Engine), with the sentence that says why. A switch or the system blocking it disables it;
 * turned off it is inactive; connected it is active; otherwise it is ready. [where] is "on this computer" or
 * "in the guest". The sentence never names the network.
 */
internal fun radioOnNow(
    kind: RadioKind,
    block: RadioBlock,
    powered: Boolean?,
    hasInterface: Boolean,
    connected: Boolean?,
    channel: Int?,
    where: String,
): Pair<RadioState, String> {
    val name = kind.label
    return when {
        block == RadioBlock.Hardware || block == RadioBlock.Both ->
            RadioState.Disabled to "Switched off by a hardware switch or key $where."
        block == RadioBlock.Software ->
            RadioState.Disabled to "Blocked in the system: airplane mode is on or $name is switched off."
        powered == false ->
            RadioState.Inactive to "$name is turned off $where."
        !hasInterface ->
            RadioState.Neutral to "The radio is here, but the system has no network interface for it."
        connected == true ->
            RadioState.Active to ("Connected" + (channel?.let { " on channel $it" } ?: "") + ".")
        kind == RadioKind.WiFi ->
            RadioState.Neutral to "On, not connected to a network."
        // The probe lists controllers, not devices, so a Bluetooth controller is never known to be in use.
        powered == true ->
            RadioState.Neutral to "On and ready."
        else ->
            RadioState.Neutral to "The system did not say whether it is on."
    }
}

/** What a Wi-Fi adapter of this computer is doing now, by [radioOnNow]. */
internal fun localWifiNow(adapter: WifiAdapter, link: WifiLink?): Pair<RadioState, String> =
    radioOnNow(RadioKind.WiFi, adapter.block, adapter.powered, adapter.hasInterface, link?.connected, link?.channel, "on this computer")

/** This computer's Wi-Fi adapters as cards of the board, each with its state. */
internal fun localWifiCards(scan: WifiScan): List<RadioCard> = scan.adapters.map { adapter ->
    val link = scan.links.firstOrNull { it.adapterId == adapter.id }
    val (state, why) = localWifiNow(adapter, link)
    val hardware = adapter.hardware
    val noInterface = RadioNote(WifiIssue.NoInterface.sentence(scan.source))
    RadioCard(
        id = "local/wifi/${adapter.id}", kind = RadioKind.WiFi, name = hardware.product ?: adapter.name, hostKey = THIS_COMPUTER_KEY,
        vendor = hardware.maker, ids = hardwareIds(hardware), bus = hardwareBus(hardware), port = hardware.pciAddress?.let { "PCI $it" },
        driver = hardware.driver, scan = RadioScanRoute.ThisComputer.takeIf { adapter.hasInterface },
        scanRefusal = noInterface.takeIf { !adapter.hasInterface }, state = state, stateWhy = why,
    )
}

private fun adapterView(adapter: WifiAdapter, links: List<WifiLink>): WifiAdapterView {
    val hardware = adapter.hardware
    val connected = links.any { it.adapterId == adapter.id && it.connected }
    val enabled = adapter.powered != false && blocked(adapter.block) != true
    val description = hardware.product?.takeIf { it != adapter.name }
    return WifiAdapterView(adapter.name, description, hardware.driver, adapter.address, connected, enabled, adapter.bands.mapNotNull(::wifiBandOf))
}

/** The current link. A name the system withheld stays blank. With no earlier readings the line starts at this one. */
private fun linkView(link: WifiLink, history: List<Int?>): WifiLinkView = WifiLinkView(
    ssid = link.ssid.orEmpty(), bssid = link.bssid, signalDbm = link.signalDbm, quality = link.signalPercent,
    channel = link.channel, band = wifiBandOf(link.band), widthMhz = link.widthMhz, generation = link.generation,
    rxMbps = link.rxMbps, txMbps = link.txMbps, security = link.security?.label, history = history.ifEmpty { listOf(link.signalDbm) },
)

private fun accessPointView(point: WifiAccessPoint) =
    WifiApView(point.bssid, point.signalDbm, point.channel, wifiBandOf(point.band), point.widthMhz, point.generation, point.connected)

/** One network and its access points. A hidden network has a blank name. */
private fun networkView(network: WifiNetwork) =
    WifiNetworkView(network.ssid.orEmpty(), network.security.label, network.accessPoints.map(::accessPointView))

/**
 * One scan as the Wi-Fi view shows it. A note that the system gave (Location is off, the adapter is off, only an old scan
 * is known) is shown beside whatever was read and never blocks another try; [fix] gives the action that opens the system
 * page that helps, where one exists. [history] is the current link's signal over the earlier readings, oldest first.
 */
internal fun wifiPanel(
    scan: WifiScan, sources: List<WifiSourceView>, source: String, scanning: Boolean = false,
    history: List<Int?> = emptyList(), fix: ((WifiIssue) -> (() -> Unit)?)? = null,
): WifiPanel {
    val openSettings = scan.issue?.let { issue -> fix?.invoke(issue) }?.let { RadioAction("Open settings", it) }
    return WifiPanel(
        sources = sources, source = source, at = scan.at,
        adapters = scan.adapters.map { adapterView(it, scan.links) },
        link = scan.connectedLink?.let { linkView(it, history) },
        networks = scan.networks.map(::networkView),
        note = scan.note?.let { RadioNote(it, openSettings) },
        scanning = scanning,
    )
}

private fun guestRadioLabel(radio: GuestRadio): String =
    if (radio.name.isBlank() || radio.name == radio.id) radio.id else "${radio.id} · ${radio.name}"

/** What a radio inside a guest is doing now, by [radioOnNow]. */
internal fun guestRadioNow(radio: GuestRadio): Pair<RadioState, String> {
    val kind = if (radio.kind == GuestRadioKind.Bluetooth) RadioKind.Bluetooth else RadioKind.WiFi
    return radioOnNow(kind, radio.block, radio.powered, radio.hasInterface, radio.connected, null, "in the guest")
}

/** What a guest itself sees, as the guest tab shows it, each radio with its state. */
internal fun guestProbeView(probe: GuestRadioProbe): GuestProbeView {
    val bluetooth = probe.bluetooth.map { radio ->
        val (state, why) = guestRadioNow(radio)
        GuestBtController(guestRadioLabel(radio), radio.address, radio.hardware.driver, radio.powered, blocked(radio.block), state, why)
    }
    val wifi = probe.wifi.map { radio ->
        val (state, why) = guestRadioNow(radio)
        val label = guestRadioLabel(radio)
        GuestWifiInterface(label, radio.address, radio.hardware.driver, radio.powered, blocked(radio.block), radio.connected, state, why)
    }
    return GuestProbeView(bluetooth, wifi, probe.at, probe.note?.let { RadioNote(it) })
}
