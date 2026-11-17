package local.ringleader.core

import java.time.Instant

/*
 * The radios of the sample fleet and of the computer that looks at it. The fleet's radios go through the same parsers and
 * matcher as a live read; the Wi-Fi scan is what a computer with one adapter would see.
 */

/** Reads [sampleFleetRadios] for any connection. */
class SampleFleetRadioSource : FleetRadioSource {
    override suspend fun read(connection: Connection?, overview: Overview, force: Boolean): FleetRadios = sampleFleetRadios(overview)
    override suspend fun readGuest(connection: Connection?, guest: Guest, fleet: FleetRadios): FleetRadios = fleet
}

fun sampleFleetRadios(overview: Overview = sampleOverview()): FleetRadios {
    val remote = overview.guests.firstOrNull()?.remote ?: SAMPLE_ADDRESS
    val guests = overview.guests.filter { it.remote == remote }
    fun config(id: Int) = when (id) {
        120 -> """{"data":[{"key":"digest","value":"demo120"},{"key":"usb0","value":"host=0bda:8771"},{"key":"usb1","value":"spice"}]}"""
        121 -> """{"data":{"digest":"demo121","lxc":[["lxc.mount.entry","/run/dbus run/dbus none bind,create=dir"]]}}"""
        130 -> """{"data":[{"key":"digest","value":"demo130"},{"key":"hostpci0","value":"0000:02:00,pcie=1"},{"key":"usb0","value":"spice"},
         {"key":"usb1","value":"host=0a12:0001"}]}"""
        else -> """{"data":[{"key":"digest","value":"demo$id"}]}"""
    }
    val nodeA = NodeRadioInputs("demo-node-a",
        usb = RadioSection(parseNodeUsbDevices("""{"data":[
            {"busnum":1,"devnum":1,"port":0,"level":0,"class":9,"vendid":"1d6b","prodid":"0002","speed":"480","product":"xHCI Host Controller"},
            {"busnum":1,"devnum":3,"port":2,"level":1,"usbpath":"3","class":224,"vendid":"0bda","prodid":"8771","speed":"12",
             "product":"Bluetooth Radio","manufacturer":"Realtek"},
            {"busnum":1,"devnum":4,"port":3,"level":1,"usbpath":"4","class":0,"vendid":"2357","prodid":"010c","speed":"480","product":"802.11n NIC",
             "manufacturer":"Realtek"},
            {"busnum":1,"devnum":5,"port":5,"level":1,"usbpath":"6","class":0,"vendid":"046d","prodid":"c52b","speed":"12","product":"USB Receiver",
             "manufacturer":"Logitech"},
            {"busnum":1,"devnum":7,"port":13,"level":1,"usbpath":"14","class":224,"vendid":"8087","prodid":"0026","speed":"12"}]}""")),
        pci = RadioSection(parseNodePciDevices("""{"data":[
            {"id":"0000:00:1f.6","class":"0x020000","vendor":"0x8086","device":"0x15bc","vendor_name":"Intel Corporation",
             "device_name":"Ethernet Connection I219-V","iommugroup":11},
            {"id":"0000:03:00.0","class":"0x028000","vendor":"0x8086","device":"0x2723","vendor_name":"Intel Corporation",
             "device_name":"Wi-Fi 6 AX200","iommugroup":14}]}""")),
        network = RadioSection(parseWirelessInterfaces("""{"data":[
            {"iface":"vmbr0","type":"bridge","active":1},
            {"iface":"wlp3s0","type":"eth","exists":1,"altnames":["wlx020000000a02"]},
            {"iface":"wlx020000000a01","type":"eth","exists":1}]}""")))
    val nodeB = NodeRadioInputs("demo-node-b",
        usb = RadioSection(error = RADIOS_USB_REFUSED),
        pci = RadioSection(parseNodePciDevices("""{"data":[
            {"id":"0000:02:00.0","class":"0x028000","vendor":"0x10ec","device":"0xb852","vendor_name":"Realtek Semiconductor Co., Ltd.",
             "device_name":"RTL8852BE PCIe 802.11ax Wireless Network Controller","iommugroup":9}]}""")),
        network = RadioSection(emptyList()))
    val inputs = FleetRadioInputs(remote, listOf(nodeA, nodeB), guests.map { GuestRadioInput(it, parseGuestRadioConfig(config(it.id))) })
    return assembleFleetRadios(inputs, Instant.now(), 0, 0)
}

private fun samplePoint(ssid: String?, bssid: String, dbm: Int, mhz: Int, width: Int, phy: WifiPhy, security: WifiSecurity, raw: String, rate: Int,
    connected: Boolean = false) =
    WifiAccessPoint(ssid, bssid, dbm, wifiPercentFromDbm(dbm), mhz, wifiChannelOfMhz(mhz), wifiBandOfMhz(mhz), width, phy, security, raw, rate,
        connected, "wlan0")

/** Six invented access points in four networks, seen by one adapter that is connected to the first. */
fun sampleWifiScan(source: WifiSource = BtSource.Local, at: Instant = Instant.now()): WifiScan {
    val points = listOf(
        samplePoint("Example Home", "02:00:00:00:02:01", -48, 5180, 80, WifiPhy.Ax, WifiSecurity.Wpa2Wpa3, "WPA2 WPA3", 1201, connected = true),
        samplePoint("Example Home", "02:00:00:00:02:02", -61, 2437, 20, WifiPhy.N, WifiSecurity.Wpa2Wpa3, "WPA2 WPA3", 286),
        samplePoint("Example Home", "02:00:00:00:02:03", -70, 6115, 160, WifiPhy.Ax, WifiSecurity.Wpa3, "WPA3", 2401),
        samplePoint("Sample Guest", "02:00:00:00:03:01", -66, 2462, 20, WifiPhy.N, WifiSecurity.Open, "Open", 144),
        samplePoint("Sample Office", "02:00:00:00:04:01", -74, 5745, 80, WifiPhy.Ac, WifiSecurity.Enterprise, "WPA2 802.1X", 866),
        samplePoint(null, "02:00:00:00:05:01", -82, 2412, 20, WifiPhy.G, WifiSecurity.Wpa2, "WPA2", 54),
    )
    val adapter = WifiAdapter("wlan0", "Sample Wi-Fi 6E adapter", "02:00:00:00:00:20", true, RadioBlock.None,
        AdapterHardware(driver = "iwlwifi", bus = "pci", pciAddress = "0000:03:00.0", pciVendorId = 0x8086, pciDeviceId = 0x2725),
        "phy0", listOf(WifiBand.Ghz24, WifiBand.Ghz5, WifiBand.Ghz6))
    val link = WifiLink("wlan0", true, "Example Home", "02:00:00:00:02:01", -48, wifiPercentFromDbm(-48), 5180, 36, WifiBand.Ghz5, 80, WifiPhy.Ax,
        WifiSecurity.Wpa2Wpa3, "WPA2 WPA3", 1201.0, 1201.0)
    return WifiScan(source, listOf(adapter), listOf(link), groupWifiNetworks(points), at, "Sample data")
}

/** A scanner that returns [sampleWifiScan], so no radio is touched. */
class SampleWifiScanner(private val now: () -> Instant = Instant::now) : WifiScanner {
    override val platform = "Sample"
    override fun scan(seconds: Int): WifiScan = sampleWifiScan(BtSource.Local, now())
}
