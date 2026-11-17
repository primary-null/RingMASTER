package local.ringleader.core

import java.time.Instant

/*
 * What the sample fleet's guests answer: one inspection (hardware, snapshots, a backup, an hour of history, an address),
 * a Bluetooth scan and a radio probe. Handwritten; nothing here was read from a guest.
 */

fun sampleInspection(guest: Guest, timeframe: String) = GuestInspection(guest.resource, guest.node, timeframe,
    InspectionSection(parseHardware("""{"data":{"cores":2,"memory":2048,"ostype":"l26","agent":"1","scsi0":"sample:vm-120-disk-0,size=16G",
     "net0":"virtio=02:00:00:00:00:01,bridge=vmbr0,tag=10,firewall=1","description":"Sample notes","onboot":1,"tags":"demo"}}""")),
    InspectionSection(listOf(GuestSnapshot("baseline",null,1794000000,"Sample snapshot",false,false),
        GuestSnapshot("current","baseline",null,"Current sample state",false,true))),
    InspectionSection(listOf(GuestBackup("sample:backup/example","sample",1794000000,104857600,"Sample backup",true))),
    InspectionSection((0..59).map { n ->
        val now = Instant.now().epochSecond.toDouble(); val wave = kotlin.math.sin(n / 6.0)
        HistoryPoint(now - (59 - n) * historySeconds(timeframe) / 59.0, mapOf(
            "maxcpu" to 2.0, "maxmem" to 2147483648.0, "cpu" to (0.12 + 0.08 * wave + (n % 9) / 300.0), "mem" to (1.6e9 + 2.0e8 * wave),
            "netin" to (18_000.0 + 9_000.0 * kotlin.math.abs(wave)), "netout" to (6_000.0 + 3_000.0 * kotlin.math.abs(wave)),
            "diskread" to (40_000.0 + 25_000.0 * (n % 5)), "diskwrite" to (22_000.0 + 12_000.0 * (n % 4))))
    }),
    InspectionSection(listOf("192.0.2.10 (sample)")))

/** What a Bluetooth scan inside [guest] finds: one adapter, a paired headset and an unnamed low-energy device. */
fun sampleGuestBluetoothScan(guest: Guest) = BtScan(guestBluetoothSource(guest),
    listOf(BtAdapter("hci0", "${guest.name}-bt", "02:00:00:00:00:10", powered = true)),
    listOf(BtDevice("02:00:00:00:01:01", "Sample headset", -58, paired = true, connected = false, kind = BtKind.Classic, category = "audio-headset",
        adapterId = "hci0"),
        BtDevice("02:00:00:00:01:02", null, -81, kind = BtKind.LowEnergy, adapterId = "hci0")),
    Instant.now(), "Sample data")

/** What a radio probe inside [guest] finds: a Bluetooth adapter and a Wi-Fi adapter, both on. */
fun sampleGuestRadioProbe(guest: Guest, at: Instant = Instant.now()) = GuestRadioProbe(guestRadioSource(guest), listOf(
    GuestRadio(GuestRadioKind.Bluetooth, "hci0", "${guest.name}-bt", "02:00:00:00:00:10", true, RadioBlock.None, null,
        AdapterHardware(driver = "btusb", bus = "usb", usbVendorId = 0x8087, usbProductId = 0x0033)),
    GuestRadio(GuestRadioKind.WiFi, "wlan0", "Sample Wi-Fi adapter", "02:00:00:00:00:11", true, RadioBlock.None, true,
        AdapterHardware(driver = "iwlwifi", bus = "pci", pciAddress = "0000:00:10.0", pciVendorId = 0x8086, pciDeviceId = 0x2725), "phy0"),
), at, "Sample data", tools = setOf("nmcli", "iw", "bluetoothctl"), uid = 0)

/** Answers every guest with the sample probe and the sample Wi-Fi scan. It never reaches a server. */
class SampleGuestRadioGateway(private val now: () -> Instant = Instant::now) : GuestRadioGateway {
    override suspend fun probe(connection: Connection, guest: Guest, hardware: HardwareSummary?) = sampleGuestRadioProbe(guest, now())
    override suspend fun wifiScan(connection: Connection, guest: Guest, hardware: HardwareSummary?,
        seconds: Int): WifiScan = sampleWifiScan(guestRadioSource(guest), now())
}
