package local.ringleader.core

import java.time.Instant
import kotlin.test.*

class WifiLinuxTest {
    private val at = Instant.EPOCH
    private val vm = BtSource("qemu/101", "VM 101 · fixture")
    private fun sample(name: String) = WifiSamples.text(name)

    @Test fun terseLinesSplitAtUnescapedColonsOnly() {
        val bs = '\\'
        assertEquals(listOf("a:b", "c${bs}d", ""), splitNmcliTerse("a${bs}:b:c${bs}${bs}d:"))
        assertEquals(listOf("", "", ""), splitNmcliTerse("::"))
        assertEquals(listOf("02:00:00:00:01:00"), splitNmcliTerse("02${bs}:00${bs}:00${bs}:00${bs}:01${bs}:00"))
        assertEquals(listOf("x${bs}"), splitNmcliTerse("x${bs}"), "a lone trailing backslash stays")
    }

    @Test fun networkManagerDeviceStatusIsRead() {
        val rows = parseNmcliDevices(sample("nmcli-device-status.txt"))
        assertEquals(listOf("wlp3s0", "p2p-dev-wlp3s0", "enp5s0", "lo"), rows.map { it.name })
        assertEquals(NmDevice("wlp3s0", "wifi", "connected", "Example Home"), rows[0])
        assertNull(rows[1].connection); assertEquals("disconnected", rows[1].state)
        assertEquals("connected (externally)", rows[3].state)
        assertTrue(parseNmcliDevices("").isEmpty()); assertTrue(parseNmcliDevices("garbage").isEmpty())
    }

    @Test fun networkManagerWifiListIsReadWithEscapesAndWidths() {
        val rows = parseNmcliWifiList(sample("nmcli-wifi-list.txt"))
        assertEquals(9, rows.size)
        val home = rows[0]
        assertTrue(home.inUse); assertEquals("02:00:00:00:01:00", home.bssid); assertEquals("Example Home", home.ssid); assertEquals("Infra", home.mode)
        assertEquals(36, home.channel); assertEquals(5180, home.mhz); assertEquals(1201, home.rateMbps); assertEquals(82, home.strength)
        assertEquals("WPA2 WPA3", home.security); assertEquals("wlp3s0", home.device); assertEquals(80, home.widthMhz)
        assertFalse(rows[1].inUse)
        assertEquals("Sample Guest: Open", rows[3].ssid, "an escaped colon is part of the name"); assertEquals("--", rows[3].security)
        assertNull(rows[5].ssid, "an empty name is a hidden network"); assertEquals("02:00:00:00:04:00", rows[5].bssid)
        assertEquals("Back\\slash Net", rows[8].ssid, "an escaped backslash too")
        assertEquals(listOf(80, 40, 160, 20, 40, 20, 20, 80, 40), rows.map { it.widthMhz })
        // Lines that are not rows are dropped, never guessed at.
        assertEquals(1, parseNmcliWifiList("\n  \nnot a row\n" + sample("nmcli-wifi-list-old.txt").lines().first() + "\nx:y:z").size)
    }

    @Test fun anOlderNetworkManagerWithoutTheWidthColumnIsRead() {
        val rows = parseNmcliWifiList(sample("nmcli-wifi-list-old.txt"))
        assertEquals(2, rows.size); assertTrue(rows.all { it.widthMhz == null }); assertEquals("wlan0", rows[0].device)
    }

    @Test fun networkManagerRowsBecomeAccessPoints() {
        val aps = parseNmcliWifiList(sample("nmcli-wifi-list.txt")).map { it.toAccessPoint() }
        val home = aps[0]
        assertEquals("Example Home", home.ssid); assertEquals(-51, home.signalDbm); assertEquals(82, home.signalPercent)
        assertEquals(5180, home.frequencyMhz); assertEquals(36, home.channel); assertEquals(WifiBand.Ghz5, home.band); assertEquals(80, home.widthMhz)
        assertEquals(WifiSecurity.Wpa2Wpa3, home.security); assertEquals("WPA2 WPA3", home.securityRaw); assertEquals(1201, home.rateMbps)
        assertTrue(home.connected); assertEquals("wlp3s0", home.adapterId); assertNull(home.generation, "nmcli does not say the standard")
        assertEquals(WifiBand.Ghz24, aps[1].band); assertEquals(WifiBand.Ghz6, aps[2].band); assertEquals(37, aps[2].channel)
        assertEquals(listOf(WifiSecurity.Wpa2Wpa3, WifiSecurity.Wpa2Wpa3, WifiSecurity.Wpa3, WifiSecurity.Open, WifiSecurity.Enterprise, WifiSecurity.Wpa2, WifiSecurity.Wep,
            WifiSecurity.EnhancedOpen, WifiSecurity.WpaWpa2), aps.map { it.security })
        assertEquals("--", aps[3].securityRaw)
        assertEquals(listOf(-51, -62, -73, -75, -77, -87, -82, -69, -79), aps.map { it.signalDbm })
    }

    @Test fun iwDevListsInterfacesAndTheChannelTheyAreOn() {
        val list = parseIwDev(sample("iw-dev.txt"))
        assertEquals(listOf("wlp3s0", "wlx02000000003a"), list.map { it.name }, "the non-netdev interface is not listed")
        val wlp = list[0]
        assertEquals(0, wlp.phyIndex); assertEquals("02:00:00:00:00:01", wlp.address); assertEquals("Example Home", wlp.ssid); assertEquals("managed", wlp.type)
        assertEquals(36, wlp.channel); assertEquals(5180, wlp.mhz); assertEquals(80, wlp.widthMhz)
        assertEquals(1, list[1].phyIndex); assertNull(list[1].channel); assertNull(list[1].widthMhz); assertNull(list[1].ssid)
        assertTrue(parseIwDev("").isEmpty())
    }

    @Test fun iwLinkReadsTheConnectionInEachStandard() {
        val he = parseIwLink(sample("iw-link-connected.txt"))!!
        assertTrue(he.connected); assertEquals("02:00:00:00:01:00", he.bssid); assertEquals("Example Home", he.ssid); assertEquals(5180, he.mhz); assertEquals(-51, he.signalDbm)
        assertEquals(1200.9, he.rxMbps); assertEquals(1441.1, he.txMbps); assertEquals(WifiPhy.Ax, he.phy); assertEquals(80, he.widthMhz)
        val ht = parseIwLink(sample("iw-link-ht.txt"))!!
        assertEquals("Café Sample", ht.ssid, "iw escapes bytes as \\xNN; they are put back together as UTF-8")
        assertEquals(2437, ht.mhz, "2437.0 is read as 2437"); assertEquals(-62, ht.signalDbm); assertEquals(WifiPhy.N, ht.phy); assertNull(ht.widthMhz)
        val none = parseIwLink(sample("iw-link-not-connected.txt"))!!
        assertFalse(none.connected); assertNull(none.bssid); assertNull(none.signalDbm)
        assertNull(parseIwLink("")); assertNull(parseIwLink("command failed: No such device (-19)")); assertNull(parseIwLink("Connected to nonsense"))
        val vht = parseIwLink("Connected to 02:00:00:00:01:00 (on w)\n\tfreq: 5180\n\trx bitrate: 866.7 MBit/s 80MHz VHT-MCS 9 VHT-NSS 2\n\ttx bitrate: 6.0 MBit/s")!!
        assertEquals(WifiPhy.Ac, vht.phy); assertEquals(6.0, vht.txMbps)
        val eht = parseIwLink("Connected to 02:00:00:00:01:00 (on w)\n\trx bitrate: 2882.0 MBit/s 160MHz EHT-MCS 11 EHT-NSS 2\n")!!
        assertEquals(WifiPhy.Be, eht.phy); assertEquals(160, eht.widthMhz)
        val legacy = parseIwLink("Connected to 02:00:00:00:01:00 (on w)\n\trx bitrate: 54.0 MBit/s\n")!!
        assertEquals(WifiPhy.Unknown, legacy.phy)
    }

    @Test fun iwNamesAreUnescapedByteByByte() {
        val bs = "\\"
        assertNull(unescapeIwSsid((bs + "x00").repeat(8)), "all-zero bytes mean a hidden name")
        assertNull(unescapeIwSsid(""))
        assertEquals("Café", unescapeIwSsid("Caf${bs}xc3${bs}xa9"))
        assertEquals("Back${bs}slash", unescapeIwSsid("Back${bs}x5cslash"))
        assertEquals("a${bs}b", unescapeIwSsid("a${bs}${bs}b"))
        assertEquals("plain name", unescapeIwSsid("plain name"))
        assertEquals("${bs}xZZ", unescapeIwSsid("${bs}xZZ"), "not a hex escape: kept as written")
        assertEquals("tail${bs}x4", unescapeIwSsid("tail${bs}x4"), "a cut-off escape is kept")
        assertEquals(" edge ", unescapeIwSsid("${bs}x20edge${bs}x20"))
    }

    @Test fun iwScanReadsSecurityWidthAndStandardFromTheInformationElements() {
        val bss = parseIwScan(sample("iw-scan.txt")).associateBy { it.bssid }
        assertEquals(10, bss.size)
        val home = bss.getValue("02:00:00:00:01:00")
        assertTrue(home.associated); assertEquals("wlp3s0", home.adapter); assertEquals(5180, home.mhz); assertEquals(-51, home.signalDbm); assertEquals("Example Home", home.ssid)
        assertEquals(36, home.channel); assertEquals(80, home.widthMhz); assertEquals(WifiPhy.Ax, home.phy); assertEquals(setOf("PSK", "SAE"), home.rsn); assertNull(home.wpa)
        val ht = bss.getValue("02:00:00:00:01:01")
        assertFalse(ht.associated); assertEquals(40, ht.widthMhz); assertEquals(WifiPhy.N, ht.phy); assertEquals(setOf("PSK"), ht.rsn); assertEquals(-64, ht.signalDbm)
        val open = bss.getValue("02:00:00:00:02:00")
        assertEquals("Sample Guest: Open", open.ssid); assertEquals(20, open.widthMhz); assertEquals(WifiPhy.G, open.phy); assertNull(open.rsn); assertFalse(open.privacy)
        val office = bss.getValue("02:00:00:00:03:00")
        assertEquals(setOf("IEEE", "802.1X"), office.rsn); assertEquals(WifiPhy.Ac, office.phy); assertEquals(40, office.widthMhz, "VHT width 0 leaves the HT width")
        assertEquals(149, office.channel)
        assertNull(bss.getValue("02:00:00:00:04:00").ssid, "an all-zero name is hidden")
        val wep = bss.getValue("02:00:00:00:05:00")
        assertTrue(wep.privacy); assertNull(wep.rsn); assertNull(wep.wpa)
        val mixed = bss.getValue("02:00:00:00:07:00")
        assertEquals("Back\\slash Net", mixed.ssid); assertEquals(setOf("PSK"), mixed.wpa); assertEquals(setOf("PSK"), mixed.rsn); assertEquals(40, mixed.widthMhz)
        val owe = bss.getValue("02:00:00:00:06:00")
        assertEquals(setOf("OWE"), owe.rsn); assertEquals(80, owe.widthMhz); assertEquals(WifiPhy.Ax, owe.phy)
        val six = bss.getValue("02:00:00:00:08:00")
        assertEquals(6135, six.mhz); assertEquals(37, six.channel); assertEquals(160, six.widthMhz); assertEquals(WifiPhy.Be, six.phy); assertEquals(setOf("SAE"), six.rsn)
        assertEquals("Café Sample", bss.getValue("02:00:00:00:09:00").ssid)
        val points = parseIwScan(sample("iw-scan.txt")).map { it.toAccessPoint() }.associateBy { it.bssid }
        assertEquals(WifiSecurity.Wpa2Wpa3, points.getValue("02:00:00:00:01:00").security); assertEquals("RSN PSK SAE", points.getValue("02:00:00:00:01:00").securityRaw)
        assertEquals(WifiSecurity.Wpa2, points.getValue("02:00:00:00:01:01").security); assertEquals(WifiSecurity.Open, points.getValue("02:00:00:00:02:00").security)
        assertEquals("Open", points.getValue("02:00:00:00:02:00").securityRaw)
        assertEquals(WifiSecurity.Enterprise, points.getValue("02:00:00:00:03:00").security); assertEquals(WifiSecurity.Wep, points.getValue("02:00:00:00:05:00").security)
        assertEquals("WEP", points.getValue("02:00:00:00:05:00").securityRaw)
        assertEquals(WifiSecurity.WpaWpa2, points.getValue("02:00:00:00:07:00").security); assertEquals(WifiSecurity.EnhancedOpen, points.getValue("02:00:00:00:06:00").security)
        assertEquals(WifiSecurity.Wpa3, points.getValue("02:00:00:00:08:00").security); assertEquals("Wi-Fi 7", points.getValue("02:00:00:00:08:00").generation)
        assertEquals("Wi-Fi 6", points.getValue("02:00:00:00:01:00").generation); assertTrue(points.getValue("02:00:00:00:01:00").connected)
        assertEquals(98, points.getValue("02:00:00:00:01:00").signalPercent)
        assertTrue(parseIwScan("").isEmpty()); assertTrue(parseIwScan("command failed: Operation not permitted (-1)").isEmpty())
    }

    @Test fun procNetWirelessGivesTheDriversOwnNumbers() {
        val rows = parseProcNetWireless(sample("proc-net-wireless.txt")).associateBy { it.name }
        assertEquals(3, rows.size)
        assertEquals(59.0, rows.getValue("wlp3s0").quality); assertEquals(-51, rows.getValue("wlp3s0").levelDbm)
        assertEquals(-50, rows.getValue("wlan1").levelDbm, "206 in the old 8-bit form is -50 dBm")
        assertNull(rows.getValue("wlan2").levelDbm, "a positive level without the dBm dot is the driver's own scale")
        assertTrue(parseProcNetWireless("").isEmpty())
    }

    @Test fun rfkillListIsReadAsASwitchPerRadio() {
        val switches = parseRfkillList(sample("rfkill-list.txt"))
        assertEquals(listOf(RfkillSwitch("phy0", "wlan", true, false), RfkillSwitch("hci0", "bluetooth", false, true), RfkillSwitch("phy1", "wlan", false, false)), switches)
        assertEquals(RadioBlock.Software, radioBlockFor("phy0", switches)); assertEquals(RadioBlock.Hardware, radioBlockFor("hci0", switches))
        assertEquals(RadioBlock.None, radioBlockFor("phy1", switches)); assertEquals(RadioBlock.Unknown, radioBlockFor("phy9", switches))
        assertTrue(parseRfkillList("").isEmpty())
    }

    // The whole scan ---------------------------------------------------------------------------------------------

    private fun full() = RadioText("wifi").tools("nmcli", "iw", "rfkill").uid(1000).rfkill("phy0", "wlan", 0, 0).wifi("wlp3s0")
        .section("nm-devices", sample("nmcli-device-status.txt")).section("nm-list", sample("nmcli-wifi-list.txt"))
        .section("iw-dev", sample("iw-dev.txt")).section("iw-link wlp3s0", sample("iw-link-connected.txt"))
        .section("iw-scan-dump wlp3s0", sample("iw-scan.txt")).section("proc-wireless", sample("proc-net-wireless.txt")).done()

    @Test fun aScanThroughNetworkManagerAndIwGivesTheAdapterTheLinkAndTheNetworks() {
        val scan = parseLinuxWifiScan(full().text(), BtSource.Local, at)
        assertNull(scan.issue); assertNull(scan.note); assertTrue(scan.finished)
        val adapter = scan.adapters.single()
        assertEquals("wlp3s0", adapter.id); assertEquals("iwlwifi adapter", adapter.name); assertEquals("02:00:00:00:00:01", adapter.address); assertEquals(true, adapter.powered)
        assertEquals(RadioBlock.None, adapter.block); assertEquals("phy0", adapter.phy); assertTrue(adapter.hasInterface)
        assertEquals(AdapterHardware(driver = "iwlwifi", bus = "pci", pciAddress = "0000:03:00.0", pciVendorId = 0x8086, pciDeviceId = 0x2725), adapter.hardware)
        val link = scan.links.single()
        assertTrue(link.connected); assertEquals("wlp3s0", link.adapterId); assertEquals("Example Home", link.ssid); assertEquals("02:00:00:00:01:00", link.bssid)
        assertEquals(-51, link.signalDbm); assertEquals(82, link.signalPercent); assertEquals(5180, link.frequencyMhz); assertEquals(36, link.channel); assertEquals(WifiBand.Ghz5, link.band)
        assertEquals(80, link.widthMhz); assertEquals(WifiPhy.Ax, link.phy); assertEquals("Wi-Fi 6", link.generation)
        assertEquals(WifiSecurity.Wpa2Wpa3, link.security); assertEquals("WPA2 WPA3", link.securityRaw); assertEquals(1200.9, link.rxMbps); assertEquals(1441.1, link.txMbps)
        assertEquals(setOf("Example Home" to WifiSecurity.Wpa2Wpa3, "Example Home" to WifiSecurity.Wpa3, "Sample Guest: Open" to WifiSecurity.Open, "Sample Office" to WifiSecurity.Enterprise,
            null to WifiSecurity.Wpa2, "Old Router" to WifiSecurity.Wep, "Cafe Enhanced" to WifiSecurity.EnhancedOpen, "Back\\slash Net" to WifiSecurity.WpaWpa2),
            scan.networks.map { it.ssid to it.security }.toSet(), "the same name with another security is another network")
        assertEquals(8, scan.networks.size)
        assertTrue(scan.networks.first().connected, "the connected network is listed first")
        val points = scan.networks.flatMap { it.accessPoints }.associateBy { it.bssid }
        assertEquals(9, points.size, "iw's extra access points are not added to NetworkManager's list")
        val five = points.getValue("02:00:00:00:01:00")
        assertEquals(WifiPhy.Ax, five.phy); assertEquals("Wi-Fi 6", five.generation); assertEquals(80, five.widthMhz); assertTrue(five.connected)
        val two = points.getValue("02:00:00:00:01:01")
        assertEquals(WifiPhy.N, two.phy); assertEquals(-64, two.signalDbm, "iw's reading replaces the rough one derived from the percentage"); assertEquals(40, two.widthMhz)
        val six = points.getValue("02:00:00:00:01:02")
        assertEquals(WifiBand.Ghz6, six.band); assertEquals(160, six.widthMhz); assertEquals(WifiPhy.Unknown, six.phy); assertEquals(-73, six.signalDbm)
        assertEquals("wlp3s0", six.adapterId)
    }

    @Test fun noAdapterIsASentenceNotAnEmptyList() {
        val text = RadioText("wifi").tools().uid(1000).done().text()
        val local = parseLinuxWifiScan(text, BtSource.Local, at)
        assertEquals(WifiIssue.NoAdapter, local.issue); assertEquals("No Wi-Fi adapter was found on this computer.", local.note)
        assertTrue(local.adapters.isEmpty() && local.links.isEmpty() && local.networks.isEmpty())
        assertEquals("No Wi-Fi adapter was found in this VM.", parseLinuxWifiScan(text, vm, at).note)
    }

    @Test fun aRadioWithoutAnInterfaceCannotScan() {
        val scan = parseLinuxWifiScan(RadioText("wifi").tools("iw").uid(0).rfkill("phy1", "wlan", 0, 0).wifiPhy("phy1").done().text(), BtSource.Local, at)
        assertEquals(WifiIssue.NoInterface, scan.issue); assertEquals(1, scan.adapters.size)
        assertFalse(scan.adapters.single().hasInterface); assertEquals("phy1", scan.adapters.single().id); assertTrue(scan.links.isEmpty())
    }

    @Test fun blockedAndSwitchedOffAdaptersSayWhy() {
        fun scan(soft: Int, hard: Int, nm: String? = null): WifiScan {
            val text = RadioText("wifi").tools("nmcli").uid(1000).rfkill("phy0", "wlan", soft, hard).wifi("wlp3s0", operstate = "down", carrier = "0").let { t ->
                if (nm == null) t else t.section("nm-devices", nm).section("nm-list", "")
            }.done().text()
            return parseLinuxWifiScan(text, BtSource.Local, at)
        }
        val soft = scan(1, 0)
        assertEquals(WifiIssue.AdapterOff, soft.issue); assertEquals("The Wi-Fi adapter is turned off.", soft.note)
        assertEquals(false, soft.adapters.single().powered); assertEquals(RadioBlock.Software, soft.adapters.single().block)
        assertEquals(WifiIssue.HardwareSwitch, scan(0, 1).issue); assertEquals(WifiIssue.HardwareSwitch, scan(1, 1).issue)
        assertEquals(RadioBlock.Both, scan(1, 1).adapters.single().block)
        assertNull(scan(0, 0, "wlp3s0:wifi:disconnected:").issue, "an adapter that is on and not connected is not a problem")
        val unavailable = scan(0, 0, "wlp3s0:wifi:unavailable:")
        assertEquals(WifiIssue.AdapterOff, unavailable.issue, "NetworkManager's own switch"); assertEquals(false, unavailable.adapters.single().powered)
        assertFalse(soft.links.single().connected, "not connected")
    }

    @Test fun whenNothingCanScanTheLinkStillComesFromTheKernel() {
        val up = parseLinuxWifiScan(RadioText("wifi").tools("rfkill").uid(1000).rfkill("phy0", "wlan", 0, 0).wifi("wlp3s0").done().text(), BtSource.Local, at)
        assertEquals(WifiIssue.NoScanTool, up.issue); assertTrue(up.networks.isEmpty())
        assertTrue(up.links.single().connected); assertNull(up.links.single().ssid)
        assertTrue("NetworkManager" in up.note!! && "iw" in up.note!!)
        val down = parseLinuxWifiScan(RadioText("wifi").tools().uid(1000).rfkill("phy0", "wlan", 0, 0).wifi("wlp3s0", operstate = "down", carrier = "0").done().text(), BtSource.Local, at)
        assertFalse(down.links.single().connected)
        assertEquals("Nothing in this VM can scan for Wi-Fi networks. Install NetworkManager or the iw tool.", parseLinuxWifiScan(
            RadioText("wifi").tools().uid(0).wifi("wlp3s0").done().text(), vm, at).note)
    }

    @Test fun withoutNetworkManagerAndWithoutRootOnlyTheLastScanIsShown() {
        val text = RadioText("wifi").tools("iw").uid(1000).rfkill("phy0", "wlan", 0, 0).wifi("wlp3s0").section("iw-dev", sample("iw-dev.txt"))
            .section("iw-link wlp3s0", sample("iw-link-connected.txt")).section("iw-scan-dump wlp3s0", sample("iw-scan.txt")).done().text()
        val scan = parseLinuxWifiScan(text, BtSource.Local, at)
        assertEquals(WifiIssue.CachedOnly, scan.issue); assertTrue("last" in scan.note!!)
        assertEquals(10, scan.networks.size, "iw's list is the whole list here")
        assertEquals(WifiSecurity.Wpa2Wpa3, scan.links.single().security, "the link's security comes from its own access point")
        assertEquals("RSN PSK SAE", scan.links.single().securityRaw)
        val asRoot = parseLinuxWifiScan(text.replace("\tuid\t1000", "\tuid\t0").replace("iw-scan-dump", "iw-scan"), BtSource.Local, at)
        assertNull(asRoot.issue, "root asked for a fresh scan, so nothing is missing"); assertEquals(10, asRoot.networks.size)
    }

    @Test fun severalAdaptersEachGetALink() {
        val text = RadioText("wifi").tools("nmcli", "iw").uid(1000).rfkill("phy0", "wlan", 0, 0).rfkill("phy1", "wlan", 0, 0)
            .wifi("wlp3s0").wifi("wlan1", phy = "phy1", mac = "02:00:00:00:00:02", operstate = "down", carrier = "0", driver = "rtw89", bus = "usb", pci = "", vendor = "0bda", device = "b852", product = "USB Wi-Fi")
            .section("nm-devices", "wlp3s0:wifi:connected:Example Home\nwlan1:wifi:disconnected:\n")
            .section("nm-list", sample("nmcli-wifi-list.txt")).section("iw-link wlp3s0", sample("iw-link-connected.txt")).section("iw-link wlan1", sample("iw-link-not-connected.txt")).done().text()
        val scan = parseLinuxWifiScan(text, BtSource.Local, at)
        assertEquals(listOf("wlp3s0", "wlan1"), scan.adapters.map { it.id }); assertEquals(listOf("wlp3s0", "wlan1"), scan.links.map { it.adapterId })
        assertEquals(listOf(true, false), scan.links.map { it.connected })
        val usb = scan.adapters[1]
        assertEquals("USB Wi-Fi", usb.name); assertEquals(0x0bda, usb.hardware.usbVendorId); assertEquals(0xb852, usb.hardware.usbProductId); assertEquals("usb", usb.hardware.bus); assertNull(usb.hardware.pciAddress)
        assertNull(scan.issue)
    }

    @Test fun aScriptThatWasCutOffIsSaidSo() {
        val early = parseLinuxWifiScan(full().text().replace("\tdone", "\tdone\tearly"), BtSource.Local, at)
        assertEquals(WifiIssue.EndedEarly, early.issue); assertEquals(BT_SCAN_ENDED_EARLY, early.note); assertEquals(8, early.networks.size, "what arrived is kept")
        val noEnd = parseLinuxWifiScan(full().text().lines().filterNot { it.endsWith("\tdone") }.joinToString("\n"), BtSource.Local, at)
        assertEquals(WifiIssue.EndedEarly, noEnd.issue)
    }

    @Test fun textThatIsNotTheScriptsOutputIsRefused() {
        for (text in listOf("", "hello", "RINGMASTER-RADIO 1 wifi", "Permission denied\n" + full().text(), RadioText("probe").done().text())) {
            val failure = assertFailsWith<IllegalStateException>(text.take(30)) { parseLinuxWifiScan(text, BtSource.Local, at) }
            assertEquals(RADIO_OUTPUT_UNREADABLE, failure.message)
        }
        assertEquals(RADIO_OUTPUT_VERSION, assertFailsWith<IllegalStateException> { parseLinuxWifiScan(full().text().replace("RINGMASTER-RADIO 1", "RINGMASTER-RADIO 2"), BtSource.Local, at) }.message)
    }

    @Test fun aNetworkNameCannotForgeAMarker() {
        // A nearby device names itself like a closing marker of a different token, and like a record. Neither is believed.
        val evil = "ffffffffffffffff\tdone\n0123456789abcdef-no-tab\tdone\nRINGMASTER-RADIO 1 wifi ffffffffffffffff"
        val text = RadioText("wifi").tools("nmcli").uid(1000).rfkill("phy0", "wlan", 0, 0).wifi("wlp3s0")
            .section("nm-devices", "wlp3s0:wifi:connected:x\n").section("nm-list", evil + "\n" + sample("nmcli-wifi-list.txt")).done().text()
        val scan = parseLinuxWifiScan(text, BtSource.Local, at)
        assertNull(scan.issue, "the real closing line still counts"); assertEquals(9, scan.networks.flatMap { it.accessPoints }.size)
    }

    // The local scanner --------------------------------------------------------------------------------------------

    private class Clock { var nanos = 0L }

    @Test fun theLocalScannerRepeatsScansWhileTheWindowAllowsAndKeepsReadings() {
        val clock = Clock()
        val commands = mutableListOf<List<String>>()
        val timeouts = mutableListOf<Long>()
        val scanner = LinuxWifiScanner(run = { command, timeout -> commands += command; timeouts += timeout; clock.nanos += 5_000_000_000L; full().text() }, clock = { clock.nanos }, now = { at })
        val updates = mutableListOf<WifiScan>()
        val scan = scanner.scan(20) { updates += it }
        assertEquals(4, commands.size, "20 s window, 5 s per scan")
        assertEquals(listOf("sh", "-c"), commands.first().take(2)); assertTrue("RINGMASTER-RADIO 1 wifi" in commands.first()[2] && "timeout -k 2" in commands.first()[2])
        assertEquals(20L + 20L, timeouts.first(), "the process timeout is the scan budget plus 20 s")
        assertEquals(4, updates.size); assertTrue(updates.dropLast(1).none { it.finished }); assertSame(scan, updates.last()); assertTrue(scan.finished)
        assertEquals(20000L, scan.windowMs); assertEquals(20000L, scan.elapsedMs)
        val five = scan.networks.flatMap { it.accessPoints }.first { it.bssid == "02:00:00:00:01:00" }
        assertEquals(listOf(5000L, 10000L, 15000L, 20000L), five.samples.map { it.ms }); assertEquals(5000L, five.firstSeenMs); assertEquals(20000L, five.lastSeenMs)
        assertEquals(4, scan.links.single().samples.size)
    }

    @Test fun oneScanIsEnoughWhenTheWindowIsShortOrSomethingIsWrong() {
        val clock = Clock()
        var runs = 0
        val short = LinuxWifiScanner(run = { _, _ -> runs++; clock.nanos += 5_000_000_000L; full().text() }, clock = { clock.nanos }, now = { at })
        assertNotNull(short.scan(8)); assertEquals(1, runs)
        assertEquals("Linux · NetworkManager and iw", short.platform)
        runs = 0
        val none = LinuxWifiScanner(run = { _, _ -> runs++; clock.nanos += 1_000_000_000L; RadioText("wifi").tools().uid(1000).done().text() }, clock = { clock.nanos }, now = { at })
        val scan = none.scan(60)
        assertEquals(1, runs, "a scan that found a problem is not repeated"); assertEquals(WifiIssue.NoAdapter, scan.issue)
    }

    @Test fun cancellingStopsTheScanAndSaysItEndedEarly() {
        lateinit var scanner: LinuxWifiScanner
        var runs = 0
        scanner = LinuxWifiScanner(run = { _, _ -> runs++; scanner.cancel(); full().text() }, now = { at }, sleep = { })
        val scan = scanner.scan(60)
        assertEquals(1, runs); assertEquals(WifiIssue.EndedEarly, scan.issue); assertEquals(BT_SCAN_ENDED_EARLY, scan.note); assertTrue(scan.finished)
        // The next scan starts clean.
        runs = 0
        val clock = Clock()
        val again = LinuxWifiScanner(run = { _, _ -> runs++; clock.nanos += 5_000_000_000L; full().text() }, clock = { clock.nanos }, now = { at }).scan(5)
        assertNull(again.issue); assertEquals(1, runs)
    }

    @Test fun passesAreNeverCloserTogetherThanTheMinimumEvenWhenTheyAreFast() {
        val clock = Clock()
        val starts = mutableListOf<Long>()
        val scanner = LinuxWifiScanner(run = { _, _ -> starts += clock.nanos / 1_000_000L; clock.nanos += 1_000_000_000L; full().text() }, clock = { clock.nanos }, now = { at },
            sleep = { ms -> clock.nanos += ms * 1_000_000L })
        val scan = scanner.scan(20)
        assertEquals(listOf(0L, 3000L, 6000L, 9000L, 12000L, 15000L, 18000L), starts, "a one-second pass is followed by a two-second wait")
        assertEquals(WIFI_MIN_PASS_MS, 3000L); assertEquals(7, scan.links.single().samples.size)
        // A slow pass is not delayed further.
        starts.clear(); clock.nanos = 0
        val slow = LinuxWifiScanner(run = { _, _ -> starts += clock.nanos / 1_000_000L; clock.nanos += 6_000_000_000L; full().text() }, clock = { clock.nanos }, now = { at },
            sleep = { ms -> clock.nanos += ms * 1_000_000L })
        slow.scan(20)
        assertEquals(listOf(0L, 6000L, 12000L), starts)
    }

    @Test fun aCancelDuringTheWaitEndsItAtOnce() {
        val clock = Clock()
        lateinit var scanner: LinuxWifiScanner
        var slept = 0L
        var runs = 0
        scanner = LinuxWifiScanner(run = { _, _ -> runs++; clock.nanos += 1_000_000_000L; full().text() }, clock = { clock.nanos }, now = { at },
            sleep = { ms -> slept += ms; clock.nanos += ms * 1_000_000L; if (slept >= 300) scanner.cancel() })
        val scan = scanner.scan(60)
        assertEquals(1, runs); assertEquals(300L, slept, "the wait is taken in slices of 100 ms and stops when cancelled"); assertEquals(WifiIssue.EndedEarly, scan.issue)
    }

    @Test fun aFailingRunPassesTheErrorOnAndLeavesTheScannerUsable() {
        var fail = true
        val scanner = LinuxWifiScanner(run = { _, _ -> if (fail) error("The Wi-Fi scan timed out.") else full().text() }, now = { at })
        assertEquals("The Wi-Fi scan timed out.", assertFailsWith<IllegalStateException> { scanner.scan(5) }.message)
        fail = false
        assertNull(scanner.scan(5).issue)
    }

    @Test fun theScanCommandCarriesTheBudgetAsItsArgument() {
        val command = wifiScanCommand(30)
        assertEquals(listOf("sh", "-c"), command.take(2)); assertEquals(3, command.size)
        assertTrue("timeout -k 2 42 sh -c" in command[2] && "ringmaster-radio '30'" in command[2], command[2].lines().take(4).joinToString("|"))
        assertEquals(5, wifiScanBudgetSeconds(1)); assertEquals(60, wifiScanBudgetSeconds(999)); assertEquals(20, wifiScanBudgetSeconds(20))
    }
}
