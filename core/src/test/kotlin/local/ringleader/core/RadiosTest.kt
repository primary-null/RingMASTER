package local.ringleader.core

import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Shapes follow answers captured read-only from a PVE 9 host (addresses, names and MAC-based names replaced). */
class RadiosTest {
    private val remote = "https://192.0.2.10:8006"
    private fun guest(id: Int, kind: String, status: String, name: String = "guest-$id", node: String = "node-a") = Guest(remote, id, name, node, kind, status)

    // The default PCI list of the captured host, trimmed: graphics, a CNVi Wi-Fi, Ethernet and an NVMe disk.
    private val pciBody = """{"data":[
        {"class":"0x030000","device":"0x4680","device_name":"AlderLake-S GT1","id":"0000:00:02.0","iommugroup":0,"subsystem_device":"0x3355","subsystem_vendor":"0x17aa","vendor":"0x8086","vendor_name":"Intel Corporation"},
        {"class":"0x0c0330","device":"0x7ae0","device_name":"Alder Lake-S PCH USB 3.2 Gen 2x2 XHCI Controller","id":"0000:00:14.0","iommugroup":5,"vendor":"0x8086","vendor_name":"Intel Corporation"},
        {"class":"0x028000","device":"0x7af0","device_name":"Alder Lake-S PCH CNVi WiFi","id":"0000:00:14.3","iommugroup":6,"subsystem_device":"0x0074","subsystem_vendor":"0x8086","vendor":"0x8086","vendor_name":"Intel Corporation"},
        {"class":"0x020000","device":"0x1a1c","device_name":"Ethernet Connection (17) I219-LM","id":"0000:00:1f.6","iommugroup":10,"vendor":"0x8086","vendor_name":"Intel Corporation"},
        {"class":"0x010802","device":"0xa80d","device_name":"NVMe SSD Controller PM9C1a (DRAM-less)","id":"0000:01:00.0","iommugroup":11,"vendor":"0x144d","vendor_name":"Samsung Electronics Co Ltd"}]}"""

    // The captured node network: an onboard Wi-Fi whose altname is its PCI path, four USB Wi-Fi names by MAC, and a
    // USB Ethernet that is configured but absent (its adapter is given to a running VM).
    private val networkBody = """{"data":[
        {"iface":"vmbr0","type":"bridge","active":1,"autostart":1,"bridge_ports":"eno2","method":"static"},
        {"iface":"eno2","type":"eth","active":1,"exists":1,"altnames":["enp0s31f6","enx020000000001"],"method":"manual"},
        {"iface":"wlo1","type":"eth","exists":1,"altnames":["wlp0s20f3","wlx020000000002"],"method":"manual"},
        {"iface":"wlx020000000003","type":"eth","exists":1,"method":"manual"},
        {"iface":"wlx020000000004","type":"eth","exists":1,"method":"manual"},
        {"iface":"wlx020000000005","type":"eth","exists":1,"method":"manual"},
        {"iface":"enx020000000006","type":"eth","method":"manual"},
        {"iface":"wlx020000000007","type":"eth","exists":1,"method":"manual"}]}"""

    // hardware/usb rows as pve-common's scan_usb builds them (the lab token may not read this list).
    private val usbBody = """{"data":[
        {"busnum":1,"devnum":1,"port":0,"level":0,"class":9,"vendid":"1d6b","prodid":"0002","speed":"480","product":"xHCI Host Controller","manufacturer":"Linux"},
        {"busnum":1,"devnum":2,"port":1,"level":1,"usbpath":"2","class":9,"vendid":"05e3","prodid":"0610","speed":"480","product":"USB2.0 Hub"},
        {"busnum":1,"devnum":5,"port":0,"level":2,"usbpath":"2.1","class":224,"vendid":"0a12","prodid":"0001","speed":"12"},
        {"busnum":1,"devnum":6,"port":1,"level":2,"usbpath":"2.2","class":224,"vendid":"0a12","prodid":"0001","speed":"12"},
        {"busnum":1,"devnum":7,"port":13,"level":1,"usbpath":"14","class":"224","vendid":"8087","prodid":"0026","speed":"12"},
        {"busnum":2,"devnum":3,"port":3,"level":1,"usbpath":"4","class":0,"vendid":"0bda","prodid":"8156","speed":"5000","product":"USB 10/100/1G/2.5G LAN","manufacturer":"Realtek"},
        {"busnum":1,"devnum":8,"port":4,"level":1,"usbpath":"5","class":0,"vendid":"148f","prodid":"5370","speed":"480","product":"802.11 n WLAN","manufacturer":"Ralink"},
        {"busnum":1,"devnum":9,"port":5,"level":1,"usbpath":"6","class":0,"vendid":"046d","prodid":"c52b","speed":"12","product":"USB Receiver","manufacturer":"Logitech"}]}"""

    private fun node(usb: RadioSection<List<NodeUsbDevice>>, pci: String = pciBody, network: String = networkBody) =
        NodeRadioInputs("node-a", usb = usb, pci = RadioSection(parseNodePciDevices(pci)), network = RadioSection(parseWirelessInterfaces(network)))

    private fun fleet(usb: RadioSection<List<NodeUsbDevice>>, vararg guests: Pair<Guest, String>,
        usbMaps: List<HardwareMapping> = emptyList(), pciMaps: List<HardwareMapping> = emptyList(), network: String = networkBody) =
        assembleFleetRadios(FleetRadioInputs(remote, listOf(node(usb, network = network)), guests.map { (g, body) -> GuestRadioInput(g, parseGuestRadioConfig(body)) },
            RadioSection(usbMaps), RadioSection(pciMaps)))

    // ---- parsing and classification ----

    @Test fun pciRowsParseAndOnlyTheWifiCardIsARadio() {
        val rows = parseNodePciDevices(pciBody)
        assertEquals(5, rows.size)
        val wifi = rows.single { it.address == "0000:00:14.3" }
        assertEquals(0x028000, wifi.classCode); assertEquals(0x8086, wifi.vendorId); assertEquals(0x7af0, wifi.deviceId); assertEquals(6, wifi.iommuGroup)
        val radios = rows.mapNotNull { r -> classifyPciRadio(r.classCode, r.vendorId, r.deviceName)?.let { r.address to it } }
        assertEquals(listOf("0000:00:14.3"), radios.map { it.first })
        assertEquals(RadioKind.WiFi, radios.single().second.kind)
        assertEquals(RadioCertainty.Certain, radios.single().second.certainty)
        assertEquals(RadioCertainty.Likely, classifyPciRadio(0x028000, 0x8086, null)!!.certainty)
        assertEquals(RadioCertainty.Guess, classifyPciRadio(0x028000, 0x1234, null)!!.certainty)
        assertEquals(RadioKind.Bluetooth, classifyPciRadio(0x0d1100, 0x1234, null)!!.kind)
        assertEquals(RadioCertainty.Certain, classifyPciRadio(0x0d2100, null, null)!!.certainty)
        assertNull(classifyPciRadio(0x020000, 0x8086, "Ethernet Connection I219-LM"))
        assertEquals("0000:01:00.0", normalPci("01:00.0")); assertEquals("0000:03:00", normalPci("03:00")); assertEquals("0001:02:00.1", normalPci("0001:02:00.1"))
    }

    @Test fun usbRowsKeepThePortPathAndTheClassNumber() {
        val rows = parseNodeUsbDevices(usbBody)
        assertEquals(8, rows.size)
        assertNull(rows[0].address, "a root hub has no port path")
        assertEquals("1-2.1", rows[2].address); assertEquals(224, rows[2].deviceClass); assertEquals(0x0a12, rows[2].vendorId)
        assertEquals(224, rows[4].deviceClass, "a class sent as a string is still read")
        assertEquals("2-4", rows[5].address)
    }

    @Test fun usbClassificationClaimsNoMoreThanTheEvidence() {
        fun c(cls: Int?, v: Int, p: Int, product: String? = null, maker: String? = null) = classifyUsbRadio(cls, v, p, product, maker)
        assertEquals(RadioCertainty.Certain, c(0xe0, 0x8087, 0x0026)!!.certainty)
        assertEquals(RadioCertainty.Certain, c(0xe0, 0x1234, 0x5678, "Bluetooth Radio")!!.certainty)
        val unknownE0 = c(0xe0, 0x1234, 0x5678)!!
        assertEquals(RadioKind.Bluetooth, unknownE0.kind); assertEquals(RadioCertainty.Likely, unknownE0.certainty)
        val ralink = c(0, 0x148f, 0x5370, "802.11 n WLAN")!!
        assertEquals(RadioKind.WiFi, ralink.kind); assertEquals(RadioCertainty.Likely, ralink.certainty)
        assertEquals(RadioCertainty.Likely, c(null, 0x0a12, 0x0001)!!.certainty, "a table id alone is likely")
        val named = c(0xff, 0x1234, 0x5678, "Wireless LAN adapter")!!
        assertEquals(RadioKind.WiFi, named.kind); assertEquals(RadioCertainty.Guess, named.certainty)
        assertNull(c(0, 0x0bda, 0x8156, "USB 10/100/1G/2.5G LAN", "Realtek"), "a Realtek Ethernet adapter is not a radio")
        assertNull(c(0, 0x046d, 0xc52b, "USB Receiver", "Logitech"))
        assertNull(c(0, 0x1234, 0x5678, "Wireless Mouse"))
        assertNull(c(0, 0x1234, 0x5678, "Bluetooth Headset"))
        assertNull(c(9, 0x05e3, 0x0610, "USB2.0 Hub"))
        assertNull(c(0xe0, 0x1d6b, 0x0002), "root hubs are never radios")
        assertEquals(RadioKind.WiFi, radioKindFromName("Intel Wireless-AC 9260"))
        assertNull(radioKindFromName("Wireless_Device"))
    }

    @Test fun pathNamesTellWhereAnInterfaceSits() {
        assertEquals(InterfacePlace("0000:00:14.3", null, false), interfacePlace("wlp0s20f3"))
        assertEquals(InterfacePlace("0000:03:00.0", null, false), interfacePlace("wlp3s0"))
        assertEquals(InterfacePlace("0001:02:00.0", null, false), interfacePlace("wlP1p2s0"))
        assertEquals(InterfacePlace("0000:00:14.0", "2.1", true), interfacePlace("wlp0s20f0u2u1"))
        assertEquals(InterfacePlace("0000:00:14.0", "3", true), interfacePlace("wlp0s20f0u3i2"))
        assertEquals(InterfacePlace(null, null, true), interfacePlace("wlx020000000003"))
        assertNull(interfacePlace("wlo1")); assertNull(interfacePlace("wlan0")); assertNull(interfacePlace("eno2"))
    }

    @Test fun onlyWirelessInterfacesAreKept() {
        val nics = parseWirelessInterfaces(networkBody)
        assertEquals(listOf("wlo1", "wlx020000000003", "wlx020000000004", "wlx020000000005", "wlx020000000007"), nics.map { it.name })
        assertEquals(listOf("wlp0s20f3", "wlx020000000002"), nics[0].altNames)
        assertTrue(nics.all { it.present })
        val renamed = parseWirelessInterfaces("""{"data":[{"iface":"wifi0","type":"eth","altnames":["wlp2s0"]},{"iface":"wlbr","type":"bridge"}]}""")
        assertEquals(listOf("wifi0"), renamed.map { it.name }); assertFalse(renamed[0].present)
    }

    @Test fun guestConfigsKeepRadioEntriesPendingChangesAndRawLxcLines() {
        val vm = parseGuestRadioConfig("""{"data":[{"key":"digest","value":"abc"},{"key":"usb0","value":"host=0bda:8156"},
            {"key":"usb1","pending":"host=8087:0026"},{"key":"hostpci0","value":"0000:00:14.3","delete":1},{"key":"net0","value":"virtio=02:00:00:00:00:01"}]}""")
        assertEquals("abc", vm.digest)
        assertEquals(listOf(GuestRadioEntry("usb0", "host=0bda:8156"), GuestRadioEntry("usb1", "host=8087:0026", RadioChange.AtNextStart),
            GuestRadioEntry("hostpci0", "0000:00:14.3", RadioChange.RemovedAtNextStart)), vm.entries)
        val replaced = parseGuestRadioConfig("""{"data":[{"key":"usb0","value":"spice","pending":"host=1-2"}]}""").entries
        assertEquals(listOf(RadioChange.RemovedAtNextStart, RadioChange.AtNextStart), replaced.map { it.change })
        val ct = parseGuestRadioConfig("""{"data":{"digest":"def","unprivileged":1,"dev0":"/dev/bus/usb/001/005,mode=0660",
            "lxc":[["lxc.cgroup2.devices.allow","c 10:200 rwm"],["lxc.mount.entry","/dev/net/tun dev/net/tun none bind,create=file"]]}}""")
        assertEquals("def", ct.digest)
        assertEquals(listOf("dev0", "lxc.cgroup2.devices.allow", "lxc.mount.entry"), ct.entries.map { it.key })
    }

    @Test fun mappingsParseNodesIdsPathsAndErrors() {
        val maps = parseHardwareMappings("""{"data":[
            {"id":"bt-dongle","description":"Desk dongle","map":["node=node-a,id=0a12:0001,path=1-2.2","node=node-b,id=0a12:0001"],"digest":"x"},
            {"id":"wifi-card","map":["node=node-a,path=0000:03:00.0;0000:04:00.0,id=8086:2723,iommugroup=14"],"errors":[{"severity":"warning","message":"No mapping for node node-c."}]}]}""")
        assertEquals(2, maps.size)
        assertEquals(MappingEntry("node-a", 0x0a12, 0x0001, listOf("1-2.2"), null), maps[0].entries[0])
        assertEquals(emptyList(), maps[0].entries[1].paths)
        assertEquals(listOf("0000:03:00.0", "0000:04:00.0"), maps[1].entries.single().paths)
        assertEquals(listOf("No mapping for node node-c."), maps[1].errors)
        assertEquals(emptyList(), parseHardwareMappings("""{"data":[]}"""))
    }

    // ---- the captured host ----

    @Test fun theCapturedHostShowsItsWifiCardGivenToAVmAndItsUsbWifiNames() {
        val f = fleet(RadioSection(error = RADIOS_USB_REFUSED),
            guest(101, "VM", "stopped") to """{"data":[{"key":"digest","value":"a"},{"key":"hostpci0","value":"0000:00:14.3"}]}""",
            guest(114, "VM", "running") to """{"data":[{"key":"digest","value":"b"},{"key":"usb0","value":"host=0bda:8156"},{"key":"net0","value":"virtio=02:00:00:00:00:02,bridge=vmbr0"}]}""",
            guest(104, "Container", "stopped") to """{"data":{"digest":"c","lxc":[["lxc.cgroup2.devices.allow","c 10:200 rwm"],["lxc.mount.entry","/dev/net/tun dev/net/tun none bind,create=file"]]}}""")
        val node = f.nodes.single()
        assertFalse(node.usbListed); assertTrue(node.pciListed); assertTrue(node.networkListed)
        assertEquals(listOf(RADIOS_USB_REFUSED, RADIOS_COMBO_HINT), node.notes)
        val card = f.radios.single { it.bus == RadioBus.Pci }
        assertEquals("$remote|node-a|pci:0000:00:14.3", card.id)
        assertEquals(RadioKind.WiFi, card.kind); assertEquals(RadioCertainty.Certain, card.certainty); assertEquals("wlo1", card.interfaceName)
        assertEquals("Wi-Fi", radioTitle(card))
        val usbNames = f.radios.filter { it.bus == RadioBus.Usb }
        assertEquals(listOf("wlx020000000003", "wlx020000000004", "wlx020000000005", "wlx020000000007"), usbNames.map { it.interfaceName })
        assertTrue(usbNames.all { it.certainty == RadioCertainty.Likely && it.kind == RadioKind.WiFi })
        assertEquals("Looks like Wi-Fi", radioTitle(usbNames[0]))
        assertEquals("The node's own Wi-Fi (wlx020000000003)", radioPlaceText(f, usbNames[0]))

        val vm101 = radiosOf(f, guest(101, "VM", "stopped").resource)!!
        assertTrue(vm101.holdsRadio)
        assertEquals(listOf(card), vm101.radios)
        val use = usersOf(f, card).single()
        assertEquals(RadioMeans.PciDevice, use.means); assertEquals(RadioTie.Exact, use.tie); assertEquals("hostpci0", use.key)
        assertEquals("PCI device 0000:00:14.3", radioMeansText(use))
        assertEquals("Given to VM 101 " + Char(0xB7) + " guest-101", radioPlaceText(f, card))
        assertFalse(radioFree(f, card))

        val vm114 = radiosOf(f, guest(114, "VM", "running").resource)!!
        assertFalse(vm114.holdsRadio)
        assertEquals("This token cannot list USB devices to tell what it is.", vm114.others.single().note)
        val ct104 = radiosOf(f, guest(104, "Container", "stopped").resource)!!
        assertFalse(ct104.reachesRadio); assertTrue(ct104.others.isEmpty())
        assertEquals(listOf(vm101), guestsWithRadios(f))
    }

    @Test fun aKnownIdNamedOnlyByAGuestBecomesARadioTheNodeDidNotList() {
        val f = fleet(RadioSection(error = RADIOS_USB_REFUSED), guest(110, "VM", "running") to """{"data":[{"key":"usb0","value":"host=0x8087:0x0026"}]}""")
        val bt = f.radios.single { it.kind == RadioKind.Bluetooth }
        assertEquals("$remote|node-a|usb-id:8087:0026", bt.id)
        assertFalse(bt.listedOnNode); assertEquals(RadioCertainty.Likely, bt.certainty); assertEquals("Intel Bluetooth", bt.name)
        assertEquals("8087:0026", bt.ids)
        assertTrue(radiosOf(f, guest(110, "VM", "running").resource)!!.holdsRadio)
        assertEquals("Given to VM 110 " + Char(0xB7) + " guest-110", radioPlaceText(f, bt))
    }

    // ---- matching with a USB list ----

    @Test fun usbEntriesMatchByIdPortAndMapping() {
        val usb = RadioSection(parseNodeUsbDevices(usbBody))
        val maps = parseHardwareMappings("""{"data":[{"id":"desk","map":["node=node-a,id=0a12:0001,path=1-2.2"]},{"id":"any-csr","map":["node=node-a,id=0a12:0001"]}]}""")
        val f = fleet(usb,
            guest(120, "VM", "running") to """{"data":[{"key":"usb0","value":"host=0a12:0001"},{"key":"usb1","value":"spice"}]}""",
            guest(121, "VM", "stopped") to """{"data":[{"key":"usb0","value":"host=1-2.1,usb3=1"},{"key":"usb1","value":"host=1-9"}]}""",
            guest(122, "VM", "stopped") to """{"data":[{"key":"usb0","value":"mapping=desk"},{"key":"usb1","value":"mapping=hidden"}]}""",
            guest(123, "VM", "stopped") to """{"data":[{"key":"usb0","value":"host=0bda:8156"},{"key":"usb1","value":"host=0bda:8771"},{"key":"usb2","value":"mapping=any-csr"}]}""",
            usbMaps = maps)
        val node = f.nodes.single()
        assertTrue(node.usbListed); assertFalse(RADIOS_COMBO_HINT in node.notes)
        val csr1 = f.radio("$remote|node-a|usb:1-2.1")!!; val csr2 = f.radio("$remote|node-a|usb:1-2.2")!!
        assertEquals(RadioCertainty.Certain, csr1.certainty)
        assertEquals(RadioCertainty.Certain, f.radio("$remote|node-a|usb:1-14")!!.certainty)
        assertNull(f.radio("$remote|node-a|usb:1-6"), "the Logitech receiver is not a radio")
        assertNull(f.radio("$remote|node-a|usb:1-2"), "hubs are not radios")

        val g120 = radiosOf(f, guest(120, "VM", "running").resource)!!
        assertEquals(listOf(RadioTie.OneOfSeveral, RadioTie.OneOfSeveral), g120.uses.map { it.tie }, "two identical dongles: either may be the one")
        assertEquals(setOf(csr1.id, csr2.id), g120.uses.map { it.radioId }.toSet())
        assertEquals(listOf("usb1"), g120.spiceSlots)

        val g121 = radiosOf(f, guest(121, "VM", "stopped").resource)!!
        assertEquals(RadioTie.Exact, g121.uses.single().tie); assertEquals(csr1.id, g121.uses.single().radioId); assertEquals(RadioMeans.UsbPort, g121.uses.single().means)
        assertEquals("Nothing is plugged into that port now.", g121.others.single().note)

        val g122 = radiosOf(f, guest(122, "VM", "stopped").resource)!!
        assertEquals(csr2.id, g122.uses.single().radioId); assertEquals(RadioTie.Exact, g122.uses.single().tie)
        assertEquals("Uses mapping hidden, which this token cannot read (Mapping.Audit on /mapping/usb/hidden).", g122.others.single().note)

        val g123 = radiosOf(f, guest(123, "VM", "stopped").resource)!!
        assertEquals("USB 10/100/1G/2.5G LAN", g123.others.single { it.key == "usb0" }.note)
        val absent = g123.uses.single { it.key == "usb1" }
        assertEquals("Not plugged into the node now.", absent.note)
        assertFalse(f.radio(absent.radioId!!)!!.listedOnNode)
        assertEquals(listOf(RadioTie.OneOfSeveral, RadioTie.OneOfSeveral), g123.uses.filter { it.key == "usb2" }.map { it.tie })

        assertEquals(listOf(121, 120, 123), usersOf(f, csr1).map { it.guest.vmid }, "exact ties first")
        assertTrue(radioFree(f, f.radio("$remote|node-a|usb:1-14")!!))
        assertEquals("On the node, not given to a guest", radioPlaceText(f, f.radio("$remote|node-a|usb:1-14")!!))
        val dot = " " + Char(0xB7) + " "
        assertEquals("Given to VM 122${dot}guest-122, VM 120${dot}guest-120, VM 123${dot}guest-123", radioPlaceText(f, csr2), "exact users come first")
    }

    @Test fun aUsbWifiAdapterOnAControllerPortTakesItsInterface() {
        val network = """{"data":[{"iface":"wlp0s20f0u5","type":"eth","exists":1}]}"""
        val f = fleet(RadioSection(parseNodeUsbDevices(usbBody)), network = network)
        val ralink = f.radio("$remote|node-a|usb:1-5")!!
        assertEquals("wlp0s20f0u5", ralink.interfaceName); assertEquals(RadioKind.WiFi, ralink.kind)
        assertNull(f.radios.firstOrNull { it.id.contains("|net:") })
        // The CNVi card has no interface here, so it is a card on the node but not "its own Wi-Fi".
        assertEquals("On the node, not given to a guest", radioPlaceText(f, f.radio("$remote|node-a|pci:0000:00:14.3")!!))
    }

    @Test fun aLoneUsbWifiNameAndALoneUsbWifiAdapterArePaired() {
        val network = """{"data":[{"iface":"wlx020000000009","type":"eth","exists":1}]}"""
        val f = fleet(RadioSection(parseNodeUsbDevices(usbBody)), network = network)
        assertEquals("wlx020000000009", f.radio("$remote|node-a|usb:1-5")!!.interfaceName)
    }

    @Test fun pciEntriesCoverWholeDevicesMultiplePathsAndMappings() {
        val pci = """{"data":[
            {"class":"0x028000","device":"0x2723","device_name":"Wi-Fi 6 AX200","id":"0000:03:00.0","vendor":"0x8086"},
            {"class":"0x028000","device":"0x2723","device_name":"Wi-Fi 6 AX200","id":"0000:04:00.0","vendor":"0x8086"},
            {"class":"0x0c0330","device":"0x7ae0","id":"0000:00:14.0","vendor":"0x8086"}]}"""
        val maps = parseHardwareMappings("""{"data":[{"id":"wifi","map":["node=node-a,path=0000:03:00.0;0000:04:00.0,id=8086:2723"]}]}""")
        val inputs = FleetRadioInputs(remote, listOf(NodeRadioInputs("node-a", usb = RadioSection(emptyList()), pci = RadioSection(parseNodePciDevices(pci)), network = RadioSection(emptyList()))),
            listOf(
                GuestRadioInput(guest(130, "VM", "stopped"), parseGuestRadioConfig("""{"data":[{"key":"hostpci0","value":"03:00,pcie=1"}]}""")),
                GuestRadioInput(guest(131, "VM", "stopped"), parseGuestRadioConfig("""{"data":[{"key":"hostpci0","value":"mapping=wifi"},{"key":"hostpci1","value":"host=0000:00:14.0"}]}""")),
            ), pciMappings = RadioSection(maps))
        val f = assembleFleetRadios(inputs)
        val g130 = radiosOf(f, guest(130, "VM", "stopped").resource)!!
        assertEquals(listOf("$remote|node-a|pci:0000:03:00.0"), g130.uses.map { it.radioId }, "a path without a function takes every function")
        val g131 = radiosOf(f, guest(131, "VM", "stopped").resource)!!
        assertEquals(listOf(RadioTie.OneOfSeveral, RadioTie.OneOfSeveral), g131.uses.map { it.tie }, "the first free path is taken at start")
        assertEquals(RadioMeans.PciMapping, g131.uses[0].means)
        assertEquals("hostpci1", g131.others.single().key, "the USB controller is passed through but is not a radio")
    }

    // ---- containers ----

    @Test fun containerEntriesTieWhereTheyCanAndSayPassesADeviceNodeWhereTheyCannot() {
        val usb = RadioSection(parseNodeUsbDevices(usbBody))
        val ct = guest(140, "Container", "running")
        val body = """{"data":{"dev0":"/dev/bus/usb/001/007","dev1":"/dev/rfkill","dev2":"/dev/ttyACM0","lxc":[
            ["lxc.cgroup2.devices.allow","c 189:* rwm"],
            ["lxc.mount.entry","/run/dbus run/dbus none bind,create=dir"],
            ["lxc.net.0.type","phys"],["lxc.net.0.link","wlx020000000004"],["lxc.net.0.flags","up"],
            ["lxc.net.1.type","phys"],["lxc.net.1.link","enp5s0"]]}}"""
        val f = fleet(usb, ct to body)
        val g = radiosOf(f, ct.resource)!!
        val intel = "$remote|node-a|usb:1-14"
        val dev0 = g.uses.single { it.key == "dev0" }
        assertEquals(intel, dev0.radioId); assertEquals(RadioTie.Exact, dev0.tie); assertEquals(RadioMeans.ContainerDevice, dev0.means)
        assertEquals("Passes a device node (/dev/bus/usb/001/007)", radioMeansText(dev0))
        assertEquals(RadioMeans.ContainerRfkill, g.uses.single { it.key == "dev1" }.means)
        assertNull(g.uses.firstOrNull { it.key == "dev2" }, "a serial device is not a Bluetooth or Wi-Fi radio")
        val broad = g.uses.filter { it.means == RadioMeans.ContainerUsbBus }
        assertTrue(broad.isNotEmpty() && broad.all { it.tie == RadioTie.Possible })
        val dbus = g.uses.filter { it.means == RadioMeans.ContainerDbus }
        assertEquals(setOf("$remote|node-a|usb:1-2.1", "$remote|node-a|usb:1-2.2", intel), dbus.map { it.radioId }.toSet())
        val wifi = g.uses.single { it.means == RadioMeans.ContainerInterface }
        assertEquals("lxc.net.0.link", wifi.key); assertEquals(RadioTie.Exact, wifi.tie)
        assertEquals("wlx020000000004", f.radio(wifi.radioId!!)!!.interfaceName)
        assertTrue(g.holdsRadio)
        assertEquals(1, g.uses.count { it.means == RadioMeans.ContainerInterface }, "an Ethernet phys link is not a radio")

        val refused = fleet(RadioSection(error = RADIOS_USB_REFUSED), ct to """{"data":{"dev0":"/dev/bus/usb/001/007"}}""")
        val r = radiosOf(refused, ct.resource)!!
        assertFalse(r.holdsRadio)
        assertEquals("Passes a device node; this token cannot list USB devices to tell what it is.", r.others.single().note)
    }

    @Test fun aRunningContainerHoldsAWifiInterfaceTheNodeNoLongerShows() {
        val ct = guest(141, "Container", "running")
        val f = fleet(RadioSection(error = RADIOS_USB_REFUSED), ct to """{"data":{"lxc":[["lxc.net.0.type","phys"],["lxc.net.0.link","wlp2s0"]]}}""",
            network = """{"data":[]}""")
        val use = radiosOf(f, ct.resource)!!.uses.single()
        val radio = f.radio(use.radioId!!)!!
        assertEquals("$remote|node-a|pci:0000:00:14.3", f.radios.single { it.bus == RadioBus.Pci }.id)
        assertEquals("$remote|node-a|net:wlp2s0", radio.id); assertFalse(radio.listedOnNode)
        val tied = fleet(RadioSection(error = RADIOS_USB_REFUSED), ct to """{"data":{"lxc":[["lxc.net.0.type","phys"],["lxc.net.0.link","wlp0s20f3"]]}}""",
            network = """{"data":[]}""")
        assertEquals("$remote|node-a|pci:0000:00:14.3", radiosOf(tied, ct.resource)!!.uses.single().radioId, "the path name ties it to the card")
    }

    @Test fun pendingEntriesAreMarked() {
        val f = fleet(RadioSection(parseNodeUsbDevices(usbBody)), guest(150, "VM", "running") to """{"data":[{"key":"usb0","pending":"host=1-14"}]}""")
        assertEquals(RadioChange.AtNextStart, radiosOf(f, guest(150, "VM", "running").resource)!!.uses.single().change)
    }

    @Test fun offlineNodesAndUnreadGuestsSaySo() {
        val inputs = FleetRadioInputs(remote, listOf(NodeRadioInputs("node-b", online = false)),
            listOf(GuestRadioInput(guest(160, "VM", "stopped", node = "node-b"), error = RADIOS_GUEST_REFUSED), GuestRadioInput(guest(161, "VM", "stopped", node = "node-c"), GuestRadioConfig(null, emptyList()))))
        val f = assembleFleetRadios(inputs)
        assertEquals(listOf(RADIOS_NODE_OFFLINE), f.nodes.single().notes)
        assertEquals(RADIOS_GUEST_REFUSED, f.guests[0].note)
        assertEquals("Its node was not read.", f.guests[1].note)
    }

    // ---- reading ----

    private class FakeWire(private val answers: Map<String, String>, private val refused: Set<String> = emptySet()) : RadioWire {
        val requested = ConcurrentHashMap<String, AtomicInteger>()
        val active = AtomicInteger(); val peak = AtomicInteger()
        override fun get(connection: Connection, path: String): String {
            requested.getOrPut(path) { AtomicInteger() }.incrementAndGet()
            val now = active.incrementAndGet(); peak.accumulateAndGet(now, ::maxOf)
            try {
                Thread.sleep(5)
                if (path in refused) throw GuestPermissionException()
                return answers[path] ?: error("unexpected $path")
            } finally { active.decrementAndGet() }
        }
    }

    private val base = "/api2/json/nodes/node-a"
    private fun answers(guests: List<Guest>) = buildMap {
        put("$base/hardware/pci", pciBody); put("$base/network", networkBody)
        put(USB_MAPPINGS_PATH, """{"data":[]}"""); put(PCI_MAPPINGS_PATH, """{"data":[]}""")
        guests.forEach { g ->
            if (g.kind == "VM") put("$base/qemu/${g.id}/pending", """{"data":[{"key":"digest","value":"d${g.id}"},{"key":"hostpci0","value":"0000:00:14.3"}]}""")
            else put("$base/lxc/${g.id}/config", """{"data":{"digest":"d${g.id}"}}""")
        }
    }
    private fun member(name: String, status: String = "online") = RecordedMember(remote, name, status, null, null, null, null, null, null, null, null)

    @Test fun theLiveReaderMakesOnlyGetsBoundedAndCachesGuestConfigs() = runTest {
        val guests = (1..8).map { guest(200 + it, if (it % 2 == 0) "Container" else "VM", "stopped") }
        val wire = FakeWire(answers(guests), refused = setOf("$base/hardware/usb"))
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val source = LiveFleetRadioSource(wire, { now }, 3)
        val overview = Overview(1, guests, members = listOf(member("node-a"), member("node-z", "offline")))
        Connection(remote, "ring@pve!test", "test-only-secret", "").use { c ->
            val first = source.read(c, overview)
            assertEquals(2 + 3 + 8, first.calls)
            assertEquals(2 + 3 + 8, wire.requested.size, "every path once; the offline node is not asked")
            assertTrue(wire.peak.get() <= 3, "at most three reads at once, saw ${wire.peak.get()}")
            assertEquals(listOf(RADIOS_USB_REFUSED, RADIOS_COMBO_HINT), first.nodes.single { it.node == "node-a" }.notes)
            assertEquals(listOf(RADIOS_NODE_OFFLINE), first.nodes.single { it.node == "node-z" }.notes)
            assertEquals(4, guestsWithRadios(first).size)

            now = now.plus(Duration.ofMinutes(1))
            assertEquals(2 + 3, source.read(c, overview).calls, "fresh guest configs are reused")
            assertEquals(2 + 3 + 1, source.read(c, overview.copy(guests = guests.map { if (it.id == 201) it.copy(status = "running") else it })).calls,
                "a guest whose status changed is read again")
            assertEquals(2 + 3 + 8, source.read(c, overview, force = true).calls)
            now = now.plus(RADIO_GUEST_CONFIG_MAX_AGE)
            assertEquals(2 + 3 + 8, source.read(c, overview).calls, "old guest configs are read again")

            val one = source.readGuest(c, guests[0], first)
            assertEquals(1, one.calls)
            assertTrue(radiosOf(one, guests[0].resource)!!.holdsRadio)
        }
    }

    @Test fun aRefusedGuestAndAPdmSessionAreReportedWithoutGuessing() = runTest {
        val guests = listOf(guest(301, "VM", "running"))
        val wire = FakeWire(answers(guests), refused = setOf("$base/hardware/usb", "$base/qemu/301/pending"))
        val source = LiveFleetRadioSource(wire, Instant::now, 3)
        Connection(remote, "ring@pve!test", "test-only-secret", "").use { c ->
            val f = source.read(c, Overview(1, guests, members = listOf(member("node-a"))))
            assertEquals(RADIOS_GUEST_REFUSED, f.guests.single().note)
        }
        val pdmWire = FakeWire(emptyMap())
        Connection("https://pdm.example.test:8443", "reader@pdm!fleet", "test-only", "", PDM_KIND).use { c ->
            val f = LiveFleetRadioSource(pdmWire, Instant::now, 3).read(c, Overview(0, emptyList()))
            assertEquals(RADIOS_NEED_DIRECT, f.unavailable)
        }
        assertTrue(pdmWire.requested.isEmpty())
        assertEquals(RADIOS_NEED_DIRECT, LiveFleetRadioSource().read(null, Overview(0, emptyList())).unavailable)
    }

    @Test fun pathsAreBuiltForTheNodeAndTheGuestKind() {
        Connection(remote, "ring@pve!test", "test-only-secret", "").use { c ->
            assertEquals("/api2/json/nodes/node-a/qemu/101/pending", radioGuestPath(c, guest(101, "VM", "stopped")))
            assertEquals("/api2/json/nodes/node-a/lxc/104/config", radioGuestPath(c, guest(104, "Container", "stopped")))
        }
        assertEquals("/api2/json/nodes/node-a/hardware/usb", radioNodePath("node-a", "/hardware/usb"))
        assertFailsWith<IllegalArgumentException> { radioNodePath("../x", "/hardware/usb") }
    }

    // ---- demo ----

    @Test fun theDemoShowsBothDirections() = runTest {
        val f = SampleFleetRadioSource().read(null, sampleOverview())
        assertNull(f.unavailable)
        val vm120 = radiosOf(f, sampleOverview().guests.single { it.id == 120 }.resource)!!
        assertTrue(vm120.holdsRadio); assertEquals(RadioKind.Bluetooth, vm120.radios.single().kind); assertEquals(listOf("usb1"), vm120.spiceSlots)
        val ct121 = radiosOf(f, sampleOverview().guests.single { it.id == 121 }.resource)!!
        assertTrue(ct121.reachesRadio); assertFalse(ct121.holdsRadio)
        val vm130 = radiosOf(f, sampleOverview().guests.single { it.id == 130 }.resource)!!
        assertEquals(setOf(RadioKind.WiFi, RadioKind.Bluetooth), vm130.radios.map { it.kind }.toSet())
        val a = f.nodes.single { it.node == "demo-node-a" }
        assertEquals("wlp3s0", a.radios.single { it.bus == RadioBus.Pci }.interfaceName)
        assertEquals("wlx020000000a01", a.radios.single { it.kind == RadioKind.WiFi && it.bus == RadioBus.Usb }.interfaceName)
        assertTrue(radioFree(f, a.radios.single { it.vendorId == 0x8087 }))
        assertEquals(listOf(RADIOS_USB_REFUSED, RADIOS_COMBO_HINT), f.nodes.single { it.node == "demo-node-b" }.notes)
        assertTrue(f.radios.none { it.id.contains("046d") }, "the demo receiver is not a radio")
        assertEquals(setOf(RadioKind.Bluetooth), radioKindsOf(f, vm120.guest))
        assertEquals(emptySet(), radioKindsOf(f, ct121.guest), "reaching a radio through D-Bus is not holding one")
        val s = radioSummary(f)
        assertEquals(f.radios.size, s.bluetooth + s.wifi); assertEquals(f.radios.size, s.given + s.free)
        assertEquals(2, s.guestsHolding); assertEquals(1, s.nodeNotes)
    }

    @Test fun thePermissionRowNamesEveryCall() {
        val row = FEATURE_REQUIREMENTS.single { it.id == "radios" }
        for (call in listOf("hardware/usb", "hardware/pci", "/network", "mapping/usb", "mapping/pci", "pending", "config")) assertTrue(row.api.contains(call), call)
        assertEquals(listOf(PrivilegeNeed(PermissionScope.Guest, listOf("VM.Audit"))), row.needs)
    }

    @Test fun anAdapterKnowsWhetherTheNodeHasItsInterfaceUp() {
        fun fleet(active: Boolean) = assembleFleetRadios(FleetRadioInputs("r", listOf(NodeRadioInputs("n",
            network = RadioSection(listOf(NodeRadioInterface("wlo1", emptyList(), present = true, active = active, type = "eth"))))), emptyList()))
        assertEquals(true, fleet(true).radios.single().interfaceActive)
        assertEquals(false, fleet(false).radios.single().interfaceActive)
        val none = assembleFleetRadios(FleetRadioInputs("r", listOf(NodeRadioInputs("n",
            pci = RadioSection(parseNodePciDevices("""{"data":[{"id":"0000:03:00.0","class":"0x028000","vendor":"0x8086","device":"0x2725","vendor_name":"Intel Corporation","device_name":"Wi-Fi 6 AX210"}]}""")))), emptyList()))
        assertEquals(null, none.radios.single().interfaceActive, "no interface named: nothing is claimed")
    }
}
