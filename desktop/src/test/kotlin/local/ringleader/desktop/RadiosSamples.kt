package local.ringleader.desktop

import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import local.ringleader.core.SAMPLE_ADDRESS
import local.ringleader.core.Guest
import local.ringleader.core.RadioBlock
import local.ringleader.core.ResourceId
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Invented radios for the scene tests: they match the sample fleet (demo-node-a and demo-node-b and their three guests),
 * so Open guest and Open node lead somewhere. Names are made up; addresses are locally administered (02:00:00:…).
 */

private fun demoRef(vmid: Int, name: String, kind: String, node: String, running: Boolean) =
    RadioGuestRef("$SAMPLE_ADDRESS/$kind/$vmid", vmid, name, kind, node, running, ResourceId(SAMPLE_ADDRESS, "", kind, vmid), SAMPLE_ADDRESS)

internal val demoWorkbench = demoRef(120, "debian-workbench", "VM", "demo-node-a", true)
internal val demoWebServices = demoRef(121, "web-services", "Container", "demo-node-a", true)
internal val demoBackupLab = demoRef(130, "backup-lab", "VM", "demo-node-b", false)

/** The demo board. [at] is when it was "read". */
internal fun demoRadiosBoard(at: Instant = Instant.parse("2026-11-16T09:30:00Z")) = RadiosBoard(
    hosts = listOf(
        RadioHost(THIS_COMPUTER_KEY, THIS_COMPUTER_LABEL, RadioHostKind.ThisComputer),
        RadioHost("demo-node-a", "demo-node-a", RadioHostKind.Node, session = SAMPLE_ADDRESS,
            link = RadioHostLink(-61, WifiBand.B5, 44, "Wi-Fi 6E")),
        RadioHost("demo-node-b", "demo-node-b", RadioHostKind.Node, session = SAMPLE_ADDRESS),
    ),
    radios = listOf(
        RadioCard("local/wifi0", RadioKind.WiFi, "Intel Wi-Fi 6 AX201", THIS_COMPUTER_KEY, "Intel", "8086:a0f0", RadioBus.Pci, "PCI 0000:00:14.3",
            driver = "Netwtw10", scan = RadioScanRoute.ThisComputer),
        RadioCard("local/bt0", RadioKind.Bluetooth, "Intel Wireless Bluetooth", THIS_COMPUTER_KEY, "Intel", "8087:0026", RadioBus.Usb, "USB internal port",
            driver = "ibtusb", scan = RadioScanRoute.ThisComputer),
        RadioCard("demo-node-a/pci/0000:03:00.0", RadioKind.WiFi, "Intel Wi-Fi 6E AX210", "demo-node-a", "Intel", "8086:2725", RadioBus.Pci, "PCI 0000:03:00.0",
            driver = "iwlwifi", scanRefusal = RadioNote(RADIOS_NODE_SCAN)),
        RadioCard("demo-node-a/usb/1-10", RadioKind.Bluetooth, "Intel AX210 Bluetooth", "demo-node-a", "Intel", "8087:0032", RadioBus.Usb, "USB 1-10",
            driver = "btusb", scanRefusal = RadioNote(RADIOS_NODE_SCAN)),
        RadioCard("demo-node-a/usb/1-4", RadioKind.Bluetooth, "TP-Link UB500", "demo-node-a", "TP-Link", "2357:0604", RadioBus.Usb, "USB 1-4",
            holder = RadioHolder(demoWorkbench, RadioVia.HostDevice, "usb0"), driver = "btusb", scan = RadioScanRoute.InGuest),
        RadioCard("demo-node-a/usb/2-1", RadioKind.WiFi, "MediaTek MT7921AU", "demo-node-a", "MediaTek", "0e8d:7961", RadioBus.Usb, "USB 2-1",
            holder = RadioHolder(demoWorkbench, RadioVia.Mapping, "usb1"), driver = "mt7921u", scan = RadioScanRoute.InGuest),
        RadioCard("demo-node-a/usb/1-6", RadioKind.Bluetooth, "Realtek USB device", "demo-node-a", "Realtek", "0bda:a725", RadioBus.Usb, "USB 1-6",
            sure = RadioSure.Guess, holder = RadioHolder(demoWebServices, RadioVia.DeviceNode, "dev0"),
            scanRefusal = RadioNote("A container cannot scan. Its adapter belongs to the node's kernel.")),
        RadioCard("demo-node-b/usb/3-2", RadioKind.Bluetooth, "ASUS USB-BT500", "demo-node-b", "ASUS", "0b05:190e", RadioBus.Usb, "USB 3-2",
            sure = RadioSure.Likely, holder = RadioHolder(demoBackupLab, RadioVia.HostDevice, "usb0"), driver = "btusb",
            scanRefusal = RadioNote("Start the VM to scan inside it.")),
    ),
    channels = listOf(RadioChannel(demoWorkbench, "usb2")),
    readAt = at,
).withStates(inUse = setOf("local/wifi0", "demo-node-a/pci/0000:03:00.0"), blocked = setOf("demo-node-a/usb/1-10"))

/**
 * Gives each adapter of an invented board its state, as the live rules would: one given to a guest follows the guest,
 * [inUse] are the free ones their own computer is using, [blocked] the ones a radio switch has turned off.
 */
internal fun RadiosBoard.withStates(inUse: Set<String> = emptySet(), blocked: Set<String> = emptySet()) = copy(radios = radios.map { card ->
    val guest = card.holder?.guest
    val own = if (card.hostKey == THIS_COMPUTER_KEY) "This computer" else "The node"
    when {
        card.id in blocked -> card.copy(state = RadioState.Disabled, stateWhy = "Blocked by a radio switch on the node.")
        guest != null && guest.running -> card.copy(state = RadioState.Active, stateWhy = "${guest.label} is running and has this adapter.")
        guest != null -> card.copy(state = RadioState.Inactive, stateWhy = "${guest.label} is stopped. The adapter is kept for it and does nothing until it starts.")
        card.id in inUse -> card.copy(state = RadioState.Active, stateWhy = "$own itself is using it.")
        else -> card.copy(state = RadioState.Neutral, stateWhy = "On and free: nothing is using it.")
    }
})

private fun ap(last: Int, dbm: Int, ch: Int, band: WifiBand, width: Int, gen: String, connected: Boolean = false) =
    WifiApView("02:00:00:5a:%02x:%02x".format(last / 16, last % 16 * 16 + 1), dbm, ch, band, width, gen, connected)

/** Signal history for the current link: a slow swell with a little noise, as a laptop on a desk shows. */
internal fun demoSignalHistory(points: Int = 40, phase: Double = 0.0): List<Int?> =
    (0 until points).map { i -> (-56 + 4 * sin((i + phase) / 5.0) + 2 * sin((i + phase) * 1.7)).roundToInt() }

internal fun demoWifiPanel(source: String = THIS_COMPUTER_KEY, at: Instant = Instant.parse("2026-11-16T09:31:00Z"), phase: Double = 0.0): WifiPanel {
    val sources = listOf(
        WifiSourceView(THIS_COMPUTER_KEY, THIS_COMPUTER_LABEL, "Intel Wi-Fi 6 AX201"),
        WifiSourceView(demoWorkbench.key, "${demoWorkbench.label} ${demoWorkbench.name}", "MediaTek MT7921AU", inGuest = true),
        WifiSourceView(demoBackupLab.key, "${demoBackupLab.label} ${demoBackupLab.name}", "Not running", inGuest = true,
            refusal = RadioNote("Start the VM to scan inside it.")),
    )
    if (source == demoBackupLab.key) return WifiPanel(sources, source, refusal = sources.last().refusal)
    if (source == demoWorkbench.key) return WifiPanel(sources, source, at,
        adapters = listOf(WifiAdapterView("wlan0", "MediaTek MT7921AU", "mt7921u", "02:00:00:a1:00:07", connected = false, bands = listOf(WifiBand.B24, WifiBand.B5, WifiBand.B6))),
        link = null,
        networks = listOf(
            WifiNetworkView("Harbour Lab", "WPA3-Personal", listOf(ap(0x11, -63, 44, WifiBand.B5, 80, "Wi-Fi 6"), ap(0x12, -66, 6, WifiBand.B24, 20, "Wi-Fi 6"))),
            WifiNetworkView("Harbour Lab Guest", "WPA2-Personal", listOf(ap(0x13, -69, 6, WifiBand.B24, 20, "Wi-Fi 5"))),
            WifiNetworkView("Orchard-6E", "WPA3-Personal", listOf(ap(0x21, -74, 37, WifiBand.B6, 160, "Wi-Fi 6E"))),
        ))
    val history = demoSignalHistory(phase = phase)
    return WifiPanel(sources, source, at,
        adapters = listOf(WifiAdapterView("Wi-Fi", "Intel Wi-Fi 6 AX201 160MHz", "Netwtw10", "02:00:00:c0:ff:01", connected = true, bands = listOf(WifiBand.B24, WifiBand.B5))),
        link = WifiLinkView("Harbour Lab", "02:00:00:5a:01:11", history.last(), 2 * (history.last()!! + 100), 44, WifiBand.B5, 80, "Wi-Fi 6",
            rxMbps = 1201.0, txMbps = 864.5, security = "WPA3-Personal", history = history),
        networks = listOf(
            WifiNetworkView("Harbour Lab", "WPA3-Personal", listOf(ap(0x11, history.last()!!, 44, WifiBand.B5, 80, "Wi-Fi 6", connected = true), ap(0x12, -61, 6, WifiBand.B24, 20, "Wi-Fi 6"), ap(0x14, -71, 149, WifiBand.B5, 80, "Wi-Fi 6"))),
            WifiNetworkView("Harbour Lab Guest", "WPA2-Personal", listOf(ap(0x13, -64, 6, WifiBand.B24, 20, "Wi-Fi 5"), ap(0x15, -70, 36, WifiBand.B5, 40, "Wi-Fi 5"))),
            WifiNetworkView("Orchard-6E", "WPA3-Personal", listOf(ap(0x21, -67, 37, WifiBand.B6, 160, "Wi-Fi 6E"))),
            WifiNetworkView("Printer-Direct", "WPA2-Personal", listOf(ap(0x31, -70, 11, WifiBand.B24, 20, "Wi-Fi 4"))),
            WifiNetworkView("Bakery 2.4", "WPA2-Personal", listOf(ap(0x41, -79, 3, WifiBand.B24, 20, "Wi-Fi 4"))),
            WifiNetworkView("Corner Office", "WPA2-Enterprise", listOf(ap(0x51, -73, 149, WifiBand.B5, 80, "Wi-Fi 6"), ap(0x52, -81, 1, WifiBand.B24, 20, "Wi-Fi 6"))),
            WifiNetworkView("", "WPA2-Personal", listOf(ap(0x61, -77, 11, WifiBand.B24, 20, "Wi-Fi 5"))),
            WifiNetworkView("Tram Stop", "Open", listOf(ap(0x71, -84, 1, WifiBand.B24, 20, "Wi-Fi 4"))),
        ))
}

/** The demo's look inside a VM, with each radio's state by the same rule as a live probe. */
internal fun demoGuestProbe(at: Instant = Instant.parse("2026-11-16T09:32:00Z")): GuestProbeView {
    val (btState, btWhy) = radioOnNow(RadioKind.Bluetooth, RadioBlock.None, true, true, null, null, "in the guest")
    val (wifiState, wifiWhy) = radioOnNow(RadioKind.WiFi, RadioBlock.None, true, true, false, null, "in the guest")
    return GuestProbeView(
        bluetooth = listOf(GuestBtController("hci0", "02:00:00:b7:00:04", "btusb", powered = true, blocked = false, btState, btWhy)),
        wifi = listOf(GuestWifiInterface("wlan0", "02:00:00:a1:00:07", "mt7921u", true, false, connected = false, wifiState, wifiWhy)),
        at = at,
    )
}

/** Demo Wi-Fi: the current link moves a little every two seconds, and a scan takes a moment. */
internal class DemoWifiFeed(private val scope: CoroutineScope, tick: Boolean = true) : FixedWifiFeed(demoWifiPanel()) {
    private var phase = 0.0
    private var job: Job? = null
    init {
        if (tick) scope.launch {
            while (isActive) {
                delay(2_000)
                phase += 1.0
                if (!state.value.scanning) state.value = state.value.let { now ->
                    if (now.source == THIS_COMPUTER_KEY) demoWifiPanel(THIS_COMPUTER_KEY, Instant.now(), phase) else now
                }
            }
        }
    }
    override fun choose(source: String) { job?.cancel(); state.value = demoWifiPanel(source, Instant.now(), phase) }
    override fun refresh() {
        super.refresh()
        val chosen = state.value.chosen
        if (chosen?.refusal != null || state.value.scanning) return
        state.value = state.value.copy(scanning = true)
        job = scope.launch { delay(1_200); state.value = demoWifiPanel(state.value.source ?: THIS_COMPUTER_KEY, Instant.now(), phase) }
    }
}

/** Demo guest tab: the demo board's lines, and inventions for looking inside VM 120. */
internal class DemoGuestRadios : BoardGuestRadios() {
    override fun probeRefusal(guest: Guest): RadioNote? = when {
        guest.kind != "VM" -> RadioNote(RADIOS_CT_PROBE)
        guest.status != "running" -> RadioNote("Start the VM to look inside it.")
        else -> null
    }
    override suspend fun probe(guest: Guest): GuestProbeView { delay(900); return demoGuestProbe(Instant.now()) }
    override suspend fun wifiScan(guest: Guest): WifiPanel { delay(1_200); return demoWifiPanel(demoWorkbench.key, Instant.now()) }
}

/** A hub over the invented board and panels, for scenes. */
internal fun demoRadiosHub(scope: CoroutineScope, nav: RadiosNav, tick: Boolean = true) =
    RadiosHub(FixedRadiosFeed(demoRadiosBoard()), DemoWifiFeed(scope, tick), DemoGuestRadios(), nav)
