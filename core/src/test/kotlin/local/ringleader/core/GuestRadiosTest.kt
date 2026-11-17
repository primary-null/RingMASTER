package local.ringleader.core

import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.*

class GuestRadiosTest {
    private val remote = "https://192.0.2.10:8006"
    private val vm = Guest(remote, 113, "fixture", "pve1", "VM", "running")
    private val at = Instant.EPOCH
    private val source = guestRadioSource(vm)
    private fun sample(name: String) = WifiSamples.text(name)
    private fun hw(agent: String?, os: String?) = HardwareSummary(buildMap { os?.let { put("ostype", it) }; agent?.let { put("agent", it) } }, emptyList(), emptyList())
    private fun direct() = Connection(remote, "tester@pve!fixture", "fixture-secret", "")

    @Test fun everyRefusalIsATypedValueWithTheSentenceTheBluetoothScanAlreadyShows() {
        assertEquals("Guest scans need a QEMU VM with the guest agent.", GuestRefusal.Container.sentence)
        assertEquals("Start the VM to scan inside it.", GuestRefusal.NotRunning.sentence)
        assertEquals("Guest scans need a direct Proxmox VE connection.", GuestRefusal.PdmSession.sentence)
        assertEquals("Loading the VM configuration" + Char(0x2026), GuestRefusal.ConfigLoading.sentence)
        assertEquals("This VM is Windows. Guest scans need Linux.", GuestRefusal.WindowsGuest.sentence)
        assertEquals("The QEMU guest agent is turned off in this VM's options.", GuestRefusal.AgentOff.sentence)
        assertEquals("This token cannot run commands in the guest (needs VM.GuestAgent.Unrestricted on the VM).", GuestRefusal.NoPermission.sentence)
        assertEquals("The QEMU guest agent did not answer. It must be installed and running inside the VM.", GuestRefusal.AgentNotAnswering.sentence)
        assertEquals("The scan inside the VM did not finish in time.", GuestRefusal.TimedOut.sentence)
        assertEquals(GuestRefusal.entries.size, GuestRefusal.entries.map { it.sentence }.toSet().size)
        assertEquals(setOf(GuestRefusal.Container, GuestRefusal.PdmSession, GuestRefusal.WindowsGuest, GuestRefusal.AgentOff, GuestRefusal.NoPermission, GuestRefusal.AgentNotAnswering),
            GuestRefusal.entries.filter { it.engineCouldHelp }.toSet(), "the ones a helper inside the guest would get around")
    }

    @Test fun theRuleGivesTheTypedReasonAndTheBluetoothRuleStillGivesItsSentence() {
        val cases: List<Triple<Triple<Guest, Boolean, HardwareSummary?>, GuestRefusal?, String>> = listOf(
            Triple(Triple(vm.copy(kind = "Container"), true, hw("1", "l26")), GuestRefusal.Container, "container"),
            Triple(Triple(vm.copy(status = "stopped"), true, hw("1", "l26")), GuestRefusal.NotRunning, "stopped"),
            Triple(Triple(vm.copy(template = true), true, hw("1", "l26")), GuestRefusal.NotRunning, "template"),
            Triple(Triple(vm, false, hw("1", "l26")), GuestRefusal.PdmSession, "PDM"),
            Triple(Triple(vm, true, null), GuestRefusal.ConfigLoading, "config not loaded"),
            Triple(Triple(vm, true, hw("1", "win11")), GuestRefusal.WindowsGuest, "Windows 11"),
            Triple(Triple(vm, true, hw("1", "Win10")), GuestRefusal.WindowsGuest, "Windows 10 in capitals"),
            Triple(Triple(vm, true, hw("1", "wxp")), GuestRefusal.WindowsGuest, "Windows XP"),
            Triple(Triple(vm, true, hw("0", "l26")), GuestRefusal.AgentOff, "agent off"),
            Triple(Triple(vm, true, hw(null, "l26")), GuestRefusal.AgentOff, "agent missing"),
            Triple(Triple(vm, true, hw("enabled=0,type=virtio", "l26")), GuestRefusal.AgentOff, "agent disabled with options"),
            Triple(Triple(vm, true, hw("1", "l26")), null, "ordinary Linux"),
            Triple(Triple(vm, true, hw("1", null)), null, "no os type"),
            Triple(Triple(vm, true, hw("enabled=1,type=virtio", "other")), null, "other os with options"),
        )
        for ((input, expected, label) in cases) {
            val (guest, direct, hardware) = input
            assertEquals(expected, guestRadioRefusal(guest, direct, hardware), label)
            assertEquals(expected?.sentence, guestBluetoothRefusal(guest, direct, hardware), "$label: the Bluetooth rule says the same words")
        }
        // The order of the checks: the kind decides before the state, the state before the connection, the connection before the configuration.
        assertEquals(GuestRefusal.Container, guestRadioRefusal(vm.copy(kind = "Container", status = "stopped"), false, null))
        assertEquals(GuestRefusal.NotRunning, guestRadioRefusal(vm.copy(status = "stopped"), false, null))
        assertEquals(GuestRefusal.PdmSession, guestRadioRefusal(vm, false, null))
        assertEquals(GuestRefusal.WindowsGuest, guestRadioRefusal(vm, true, hw("0", "win10")))
    }

    @Test fun aGuestAgentFailureIsAnIllegalStateWithTheSentenceAndTheType() {
        val failure = GuestAgentFailure(GuestRefusal.NoPermission)
        assertIs<IllegalStateException>(failure); assertEquals(GuestRefusal.NoPermission, failure.refusal); assertEquals(GUEST_BT_NO_PERMISSION, failure.message)
        val reply = assertFailsWith<GuestAgentFailure> { parseAgentExecPid("""{"data":null}""") }
        assertEquals(GuestRefusal.AgentNotAnswering, reply.refusal); assertEquals(GUEST_BT_AGENT, reply.message)
        assertEquals(GuestRefusal.AgentNotAnswering, assertFailsWith<GuestAgentFailure> { parseAgentExecStatus("""{"data":null}""") }.refusal)
        assertEquals(BtSource(vm.resource.toString(), "VM 113 · fixture"), guestRadioSource(vm))
    }

    @Test fun theProbeReadsTheRealScriptOutputWithTheBluetoothAndWifiToolsPresent() {
        val probe = parseGuestRadioProbe(sample("probe-constructed-tree-tools.txt"), source, at)
        assertTrue(probe.complete); assertNull(probe.note); assertEquals(1000, probe.uid)
        assertEquals(setOf("nmcli", "iw", "rfkill", "bluetoothctl", "hciconfig", "hcitool", "btmgmt"), probe.tools)
        assertEquals(3, probe.radios.size)
        val bt = probe.bluetooth.single()
        assertEquals("hci0", bt.id); assertEquals("Sample Host", bt.name, "BlueZ's alias beats the product string"); assertEquals("02:00:00:00:00:10", bt.address)
        assertEquals(RadioBlock.Software, bt.block); assertEquals(false, bt.powered, "a blocked controller is not powered, whatever hciconfig said")
        assertNull(bt.connected)
        assertEquals(AdapterHardware(driver = "btusb", bus = "usb", usbVendorId = 0x13d3, usbProductId = 0x3585, product = "Wireless_Device", maker = "MediaTek Inc."), bt.hardware)
        val wifi = probe.wifi.first { it.id == "wlp3s0" }
        assertEquals("iwlwifi adapter", wifi.name); assertEquals("02:00:00:00:00:01", wifi.address); assertEquals(true, wifi.powered); assertEquals(RadioBlock.None, wifi.block)
        assertEquals(true, wifi.connected, "iw says connected"); assertEquals("phy0", wifi.phy); assertTrue(wifi.hasInterface)
        assertEquals(AdapterHardware(driver = "iwlwifi", bus = "pci", pciAddress = "0000:03:00.0", pciVendorId = 0x8086, pciDeviceId = 0x2725), wifi.hardware)
        val orphan = probe.wifi.first { it.id == "phy1" }
        assertFalse(orphan.hasInterface); assertNull(orphan.address); assertNull(orphan.powered); assertEquals(RadioBlock.Unknown, orphan.block); assertNull(orphan.connected)
        assertEquals(listOf("hci0"), probe.bluetooth.map { it.id }); assertEquals(setOf("wlp3s0", "phy1"), probe.wifi.map { it.id }.toSet())
    }

    @Test fun theProbeStillWorksWhenTheGuestHasNoToolsAtAll() {
        val probe = parseGuestRadioProbe(sample("probe-constructed-tree.txt"), source, at)
        assertTrue(probe.tools.isEmpty()); assertNull(probe.note)
        val bt = probe.bluetooth.single()
        assertEquals("Wireless_Device", bt.name, "without BlueZ tools the USB product string names it"); assertNull(bt.address); assertEquals(false, bt.powered)
        val wifi = probe.wifi.first { it.id == "wlp3s0" }
        assertEquals(true, wifi.connected, "no iw: the interface is up and has a carrier")
    }

    @Test fun aGuestThatSeesNoRadiosSaysSoAndACutShortProbeIsNotTakenForNoRadios() {
        val none = parseGuestRadioProbe(RadioText("probe").tools().uid(0).done().text(), source, at)
        assertTrue(none.radios.isEmpty()); assertEquals(GUEST_RADIO_NONE, none.note); assertTrue(none.complete); assertEquals(0, none.uid)
        assertEquals("This VM sees no Bluetooth controller and no Wi-Fi adapter.", GUEST_RADIO_NONE)
        val cut = parseGuestRadioProbe(RadioText("probe").tools().uid(0).bt("hci0").text(), source, at)
        assertFalse(cut.complete); assertEquals(GUEST_RADIO_CUT_SHORT, cut.note); assertEquals(1, cut.radios.size)
        val early = parseGuestRadioProbe(RadioText("probe").tools().uid(0).done(early = true).text(), source, at)
        assertEquals(GUEST_RADIO_CUT_SHORT, early.note)
        for (text in listOf("", "hello", sample("iw-scan.txt"), RadioText("wifi").tools().done().text())) {
            assertEquals(RADIO_OUTPUT_UNREADABLE, assertFailsWith<IllegalStateException>(text.take(20)) { parseGuestRadioProbe(text, source, at) }.message)
        }
    }

    @Test fun bluetoothToolsAreReadWhateverTheGuestHas() {
        val fact = BtFact(id = "hci0", address = "02:00:00:00:00:10", name = "sample-host", powered = true)
        assertEquals(listOf(fact), parseHciconfig(sample("hciconfig.txt")))
        assertEquals(listOf(fact.copy(powered = false, name = null)), parseHciconfig(sample("hciconfig-down.txt")))
        assertEquals(listOf(BtFact(id = "hci0", address = "02:00:00:00:00:10")), parseHcitoolDev(sample("hcitool-dev.txt")))
        assertEquals(listOf(fact), parseBtmgmtInfo(sample("btmgmt-info.txt")))
        assertEquals(listOf(BtFact(address = "02:00:00:00:00:10", name = "sample-host")), parseBluetoothctlList(sample("bluetoothctl-list.txt")))
        assertEquals(BtFact(address = "02:00:00:00:00:10", name = "Sample Host", powered = true, usbVendorId = 0x13d3, usbProductId = 0x3585), parseBluetoothctlShow(sample("bluetoothctl-show.txt")))
        // Colour codes and prompts around the lines do not matter.
        val colour = Char(27) + "[1m"; val reset = Char(27) + "[0m"
        assertEquals(listOf(fact.copy(name = null, powered = true)), parseHciconfig("${colour}hci0:\tType: Primary  Bus: USB$reset\n\tBD Address: 02:00:00:00:00:10  ACL MTU: 1021:4\n\tUP RUNNING \n"))
        assertTrue(parseHciconfig("Can't get device info: No such device").isEmpty()); assertTrue(parseHcitoolDev("Devices:\n").isEmpty()); assertTrue(parseBtmgmtInfo("").isEmpty())
        assertNull(parseBluetoothctlShow("No default controller available")); assertTrue(parseBluetoothctlList("No default controller available").isEmpty())
    }

    @Test fun twoControllersAreMatchedByTheirUsbIdsNotByTheOrderTheToolsListThem() {
        fun show(address: String, name: String, modalias: String) = "Controller $address (public)\n\tAlias: $name\n\tPowered: yes\n\tModalias: $modalias\n"
        val text = RadioText("probe").tools("bluetoothctl").uid(0).bt("hci0", vendor = "13d3", device = "3585").bt("hci1", vendor = "0a12", device = "0001")
            .section("bluetoothctl-list", "Controller 02:00:00:00:00:21 second [default]\nController 02:00:00:00:00:20 first\n")
            .section("bluetoothctl-show 02:00:00:00:00:21", show("02:00:00:00:00:21", "Dongle", "usb:v0A12p0001d0001"))
            .section("bluetoothctl-show 02:00:00:00:00:20", show("02:00:00:00:00:20", "Built-in", "usb:v13D3p3585d0001")).done().text()
        val radios = parseGuestRadioProbe(text, source, at).bluetooth.associateBy { it.id }
        assertEquals("02:00:00:00:00:20" to "Built-in", radios.getValue("hci0").let { it.address to it.name })
        assertEquals("02:00:00:00:00:21" to "Dongle", radios.getValue("hci1").let { it.address to it.name })
        // One controller on each side pairs by position when no USB ids are given.
        val single = RadioText("probe").tools("bluetoothctl").uid(0).bt("hci0", bus = "platform", vendor = "", device = "")
            .section("bluetoothctl-list", sample("bluetoothctl-list.txt")).section("bluetoothctl-show 02:00:00:00:00:10", sample("bluetoothctl-show.txt").replace("usb:v13D3p3585d0001", "none")).done().text()
        assertEquals("02:00:00:00:00:10", parseGuestRadioProbe(single, source, at).bluetooth.single().address)
    }

    @Test fun radiosConvertToTheAdapterTypesTheScansUse() {
        val radio = GuestRadio(GuestRadioKind.Bluetooth, "hci0", "Sample Host", "02:00:00:00:00:10", true, RadioBlock.None, null,
            AdapterHardware(driver = "btusb", bus = "usb", usbVendorId = 0x13d3, usbProductId = 0x3585))
        assertEquals(BtAdapter("hci0", "Sample Host", "02:00:00:00:00:10", true, null, 0x13d3, 0x3585), radio.toBtAdapter())
        val wifi = GuestRadio(GuestRadioKind.WiFi, "wlan0", "A", "02:00:00:00:00:11", false, RadioBlock.Software, true, AdapterHardware(driver = "iwlwifi"), "phy0", hasInterface = false)
        assertEquals(WifiAdapter("wlan0", "A", "02:00:00:00:00:11", false, RadioBlock.Software, AdapterHardware(driver = "iwlwifi"), "phy0", hasInterface = false), wifi.toWifiAdapter())
    }

    // The gateway ----------------------------------------------------------------------------------------------------------

    private class Transport(val answer: (List<String>) -> String) : GuestTextTransport {
        val calls = mutableListOf<List<Any>>()
        override fun run(connection: Connection, guest: Guest, command: List<String>, firstPollSeconds: Int, budgetSeconds: Int): String {
            calls += listOf(command, firstPollSeconds, budgetSeconds); return answer(command)
        }
    }

    @Test fun theGatewayRefusesBeforeAnythingIsSentAndNamesTheTypedReason() = runBlocking {
        val transport = Transport { error("nothing may be sent") }
        val gateway = LiveGuestRadioGateway(transport)
        val pdm = Connection(remote, "tester@pve!fixture", "fixture-secret", "", PDM_KIND)
        val cases = listOf(
            Triple(vm.copy(kind = "Container"), hw("1", "l26"), GuestRefusal.Container), Triple(vm.copy(status = "stopped"), hw("1", "l26"), GuestRefusal.NotRunning),
            Triple(vm, null, GuestRefusal.ConfigLoading), Triple(vm, hw("1", "win11"), GuestRefusal.WindowsGuest), Triple(vm, hw("0", "l26"), GuestRefusal.AgentOff),
        )
        for ((guest, hardware, expected) in cases) {
            assertEquals(expected, assertFailsWith<GuestAgentFailure> { gateway.probe(direct(), guest, hardware) }.refusal, "probe $expected")
            assertEquals(expected, assertFailsWith<GuestAgentFailure> { gateway.wifiScan(direct(), guest, hardware, 20) }.refusal, "scan $expected")
        }
        assertEquals(GuestRefusal.PdmSession, assertFailsWith<GuestAgentFailure> { gateway.probe(pdm, vm, hw("1", "l26")) }.refusal)
        assertEquals(GuestRefusal.PdmSession, assertFailsWith<GuestAgentFailure> { gateway.probe(direct(), vm.copy(scope = "remote-1"), hw("1", "l26")) }.refusal)
        assertTrue(transport.calls.isEmpty(), "no command was sent for any refused guest")
    }

    @Test fun theGatewayRunsTheProbeAndTheScanThroughTheTransportAndParsesWhatComesBack() = runBlocking {
        val transport = Transport { command -> if ("RINGMASTER-RADIO 1 probe" in command[2]) sample("probe-constructed-tree-tools.txt") else sample("wifi-constructed-tree-tools.txt") }
        val gateway = LiveGuestRadioGateway(transport)
        val probe = gateway.probe(direct(), vm, hw("1", "l26"))
        assertEquals(3, probe.radios.size); assertEquals(source, probe.source)
        assertEquals(listOf<Any>(guestRadioProbeCommand(), 1, 30), transport.calls.single())
        val scan = gateway.wifiScan(direct(), vm, hw("enabled=1", "l26"), 20)
        assertEquals(source, scan.source); assertEquals("VM 113 · fixture", scan.source.label)
        assertEquals(listOf<Any>(guestWifiScanCommand(20), 4, 45), transport.calls.last())
        assertEquals(wifiScanBudgetSeconds(1), 5)
        gateway.wifiScan(direct(), vm, hw("1", "l26"), 999)
        assertEquals(listOf<Any>(guestWifiScanCommand(60), 4, 85), transport.calls.last(), "the budget is clamped")
        assertEquals(listOf("sh", "-c"), transport.calls.first().let { (it[0] as List<*>).take(2) })
    }

    @Test fun aTransportFailureAndUnreadableTextBothComeThroughUnchanged() = runBlocking {
        val failing = LiveGuestRadioGateway(Transport { throw GuestAgentFailure(GuestRefusal.TimedOut) })
        assertEquals(GuestRefusal.TimedOut, assertFailsWith<GuestAgentFailure> { failing.probe(direct(), vm, hw("1", "l26")) }.refusal)
        val garbled = LiveGuestRadioGateway(Transport { "Segmentation fault" })
        assertEquals(RADIO_OUTPUT_UNREADABLE, assertFailsWith<IllegalStateException> { garbled.wifiScan(direct(), vm, hw("1", "l26"), 10) }.message)
    }

    @Test fun theDemoGatewayNeverTouchesAServer() = runBlocking {
        val gateway = SampleGuestRadioGateway { at }
        val probe = gateway.probe(direct(), vm, null)
        assertEquals("Sample data", probe.note); assertEquals(1, probe.bluetooth.size); assertEquals(1, probe.wifi.size)
        assertTrue(probe.radios.all { it.address!!.startsWith("02:00:00:") }); assertEquals(at, probe.at); assertEquals("fixture-bt", probe.bluetooth.single().name)
        val scan = gateway.wifiScan(direct(), vm.copy(status = "stopped"), null, 20)
        assertEquals("Sample data", scan.note); assertEquals(source, scan.source); assertTrue(scan.networks.isNotEmpty())
    }

    @Test fun realKernelsWithNoRadioGiveTheNoAdapterAnswers() {
        // Output of the scripts on a Fedora 45 VM (NetworkManager and rfkill present) and on Ubuntu under WSL2 (no tools), neither with any radio.
        val fedora = parseGuestRadioProbe(sample("real-fedora-probe-no-radio.txt"), source, at)
        assertTrue(fedora.radios.isEmpty()); assertEquals(GUEST_RADIO_NONE, fedora.note); assertEquals(setOf("nmcli", "rfkill"), fedora.tools); assertEquals(1000, fedora.uid); assertTrue(fedora.complete)
        val wsl = parseGuestRadioProbe(sample("real-wsl-probe-no-radio.txt"), source, at)
        assertTrue(wsl.radios.isEmpty() && wsl.tools.isEmpty()); assertEquals(0, wsl.uid); assertEquals(GUEST_RADIO_NONE, wsl.note)
        for (name in listOf("real-fedora-wifi-no-radio.txt", "real-wsl-wifi-no-radio.txt")) {
            val local = parseLinuxWifiScan(sample(name), BtSource.Local, at)
            assertEquals(WifiIssue.NoAdapter, local.issue, name); assertEquals("No Wi-Fi adapter was found on this computer.", local.note)
            assertTrue(local.adapters.isEmpty() && local.links.isEmpty() && local.networks.isEmpty())
            assertEquals("No Wi-Fi adapter was found in this VM.", parseLinuxWifiScan(sample(name), source, at).note)
        }
    }

    @Test fun theRealScanScriptOutputIsRead() {
        val scan = parseLinuxWifiScan(sample("wifi-constructed-tree-tools.txt"), source, at)
        assertNull(scan.issue); assertEquals(8, scan.networks.size); assertEquals("Example Home", scan.connectedLink?.ssid); assertEquals(WifiPhy.Ax, scan.connectedLink?.phy)
        assertEquals(listOf("wlp3s0", "phy1"), scan.adapters.map { it.id }); assertEquals(listOf(true, false), scan.adapters.map { it.hasInterface })
        val bare = parseLinuxWifiScan(sample("wifi-constructed-tree.txt"), BtSource.Local, at)
        assertEquals(WifiIssue.NoScanTool, bare.issue); assertTrue(bare.networks.isEmpty()); assertEquals(true, bare.links.single().connected)
        assertEquals("wlp3s0", bare.adapters.first().id)
    }
}
