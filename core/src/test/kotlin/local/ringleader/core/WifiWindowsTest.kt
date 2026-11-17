package local.ringleader.core

import java.time.Instant
import kotlin.test.*

class WifiWindowsTest {
    private val guid = "{00000000-0000-0000-0000-0000000000A1}"
    private fun lines(name: String) = WifiSamples.text(name).lines().filter { it.isNotBlank() }
    private fun parser(windowMs: Long = 8000) = WindowsWifiLiveParser(windowMs, Instant.EPOCH)
    private fun feed(p: WindowsWifiLiveParser, input: List<String>) = input.map { p.accept(it) }

    private fun adapter(radio: String = "on", hw: String = "on", airplane: Boolean = false, id: String = guid) =
        """{"t":"adapter","id":"$id","name":"Example Adapter","address":"020000000021","status":"Up","state":"connected","radio":"$radio","hwRadio":"$hw","airplane":$airplane}"""

    @Test fun whileLocationIsOffTheAdapterAndWhatWindowsStillAllowsComeOutWithATypedNote() {
        val p = parser()
        val changed = feed(p, lines("windows-location-off.jsonl"))
        assertEquals(listOf(true, true, true, true, true), changed)
        assertTrue(p.done); assertTrue(p.lastMs > 0)
        val scan = p.snapshot(p.lastMs, finished = true)
        assertEquals(WifiIssue.LocationOff, scan.issue); assertEquals(WifiIssue.LocationOff.sentence(), scan.note); assertTrue("Location" in scan.note!!)
        assertTrue(scan.networks.isEmpty(), "the note says why; the empty list is not left to look like 'no networks'")
        val a = scan.adapters.single()
        assertEquals(guid, a.id); assertEquals("Example Wi-Fi 6E Wireless LAN Card", a.name); assertEquals("02:00:00:00:00:21", a.address)
        assertEquals(true, a.powered); assertEquals(RadioBlock.None, a.block)
        assertEquals(AdapterHardware(driver = "1.0.0.1", bus = "pci", pciAddress = "0000:03:00.0", pciVendorId = 0x8086, pciDeviceId = 0x2725), a.hardware)
        val link = scan.links.single()
        assertTrue(link.connected); assertEquals(guid, link.adapterId); assertEquals(157, link.channel); assertEquals(-50, link.signalDbm); assertEquals(100, link.signalPercent)
        assertNull(link.ssid); assertNull(link.bssid)
        assertNull(link.band, "157 is a 5 GHz or a 6 GHz channel and Windows does not say which while Location is off"); assertNull(link.frequencyMhz)
        assertEquals(866.7, link.rxMbps); assertEquals(866.7, link.txMbps, "the adapter's own link speed fills in what the connection query refuses")
        assertEquals(1, link.samples.size)
    }

    @Test fun whenWindowsAllowsScanningTheNetworksAreGroupedAndTheLinkIsFilledFromItsAccessPoint() {
        val p = parser()
        val changed = feed(p, lines("windows-scan-allowed.jsonl"))
        assertEquals(23, changed.size); assertTrue(changed.count { !it } == 16, "access point lines change nothing visible until their pass ends")
        val scan = p.snapshot(p.lastMs, finished = true)
        assertNull(scan.issue); assertNull(scan.note)
        assertEquals(setOf("Example Home" to WifiSecurity.Wpa2, "Example Home" to WifiSecurity.Wpa3, "Sample Guest" to WifiSecurity.Open, null to WifiSecurity.Wpa2,
            "Old Router" to WifiSecurity.Wep, "Sample Office" to WifiSecurity.Enterprise, "Cafe Enhanced" to WifiSecurity.EnhancedOpen), scan.networks.map { it.ssid to it.security }.toSet())
        assertTrue(scan.networks.first().connected); assertEquals("Example Home", scan.networks.first().ssid)
        val points = scan.networks.flatMap { it.accessPoints }.associateBy { it.bssid }
        assertEquals(8, points.size)
        val five = points.getValue("02:00:00:00:01:00")
        assertTrue(five.connected); assertEquals(5180, five.frequencyMhz); assertEquals(36, five.channel); assertEquals(WifiBand.Ghz5, five.band); assertNull(five.widthMhz, "Windows does not report the width")
        assertEquals(WifiPhy.Ax, five.phy); assertEquals("Wi-Fi 6", five.generation); assertEquals(WifiSecurity.Wpa2, five.security); assertEquals("RsnaPsk Ccmp", five.securityRaw)
        assertEquals(-55, five.signalDbm, "the latest pass"); assertEquals(listOf(4320L to -52, 8320L to -55), five.samples.map { it.ms to it.dbm }); assertEquals(4320L, five.firstSeenMs)
        assertEquals("Wi-Fi 4", points.getValue("02:00:00:00:01:01").generation)
        val six = points.getValue("02:00:00:00:01:02")
        assertEquals(WifiBand.Ghz6, six.band); assertEquals(37, six.channel); assertEquals("Wi-Fi 6E", six.generation); assertEquals(WifiSecurity.Wpa3, six.security)
        assertEquals(WifiSecurity.Open, points.getValue("02:00:00:00:02:00").security); assertEquals("Open80211", points.getValue("02:00:00:00:02:00").securityRaw)
        assertEquals("Open80211 Wep", points.getValue("02:00:00:00:05:00").securityRaw); assertEquals(WifiSecurity.Wep, points.getValue("02:00:00:00:05:00").security)
        assertEquals("Wi-Fi 5", points.getValue("02:00:00:00:03:00").generation); assertNull(points.getValue("02:00:00:00:04:00").ssid)
        assertTrue(points.values.count { it.connected } == 1)
        val link = scan.links.single()
        assertEquals("Example Home", link.ssid); assertEquals("02:00:00:00:01:00", link.bssid); assertEquals(5180, link.frequencyMhz); assertEquals(WifiBand.Ghz5, link.band)
        assertEquals(36, link.channel); assertEquals(WifiPhy.Ax, link.phy); assertEquals("Wi-Fi 6", link.generation); assertEquals(WifiSecurity.Wpa2, link.security)
        assertEquals("RsnaPsk Ccmp", link.securityRaw); assertEquals(-52, link.signalDbm); assertEquals(96, link.signalPercent); assertEquals(1200.9, link.rxMbps); assertEquals(1441.1, link.txMbps)
        assertEquals(2, link.samples.size); assertNotNull(link.smoothedDbm)
    }

    @Test fun aPartialSnapshotIsNotFinished() {
        val p = parser(8000)
        feed(p, lines("windows-scan-allowed.jsonl").take(12))
        val partial = p.snapshot(5000, finished = false)
        assertFalse(partial.finished); assertEquals(8000L, partial.windowMs); assertEquals(5000L, partial.elapsedMs); assertFalse(p.done)
        assertEquals(8, partial.networks.flatMap { it.accessPoints }.size - 0, "the first pass is in")
    }

    @Test fun radioStatesBecomeTypedIssues() {
        fun issueOf(vararg l: String): WifiScan { val p = parser(); feed(p, l.toList() + """{"t":"done","ms":1}"""); return p.snapshot(1, true) }
        assertNull(issueOf(adapter()).issue)
        val off = issueOf(adapter(radio = "off"))
        assertEquals(WifiIssue.AdapterOff, off.issue); assertEquals("The Wi-Fi adapter is turned off.", off.note)
        assertEquals(false, off.adapters.single().powered); assertEquals(RadioBlock.Software, off.adapters.single().block)
        assertEquals(WifiIssue.AirplaneMode, issueOf(adapter(radio = "off", airplane = true)).issue)
        assertEquals(WifiIssue.HardwareSwitch, issueOf(adapter(hw = "off")).issue); assertEquals(RadioBlock.Hardware, issueOf(adapter(hw = "off")).adapters.single().block)
        assertEquals(WifiIssue.HardwareSwitch, issueOf(adapter(radio = "off", hw = "off", airplane = true)).issue, "a hardware switch is the more specific cause")
        assertEquals(RadioBlock.Both, issueOf(adapter(radio = "off", hw = "off")).adapters.single().block)
        val unknown = issueOf(adapter(radio = "unknown", hw = "unknown")).adapters.single()
        assertEquals(RadioBlock.Unknown, unknown.block); assertNull(unknown.powered)
        // Several adapters: one off is not a problem for the one that is on.
        val two = issueOf(adapter(radio = "off", id = "{00000000-0000-0000-0000-0000000000B2}"), adapter())
        assertNull(two.issue); assertEquals(listOf(false, true), two.adapters.map { it.powered }); assertEquals(2, two.adapters.size)
        assertEquals(WifiIssue.NoAdapter, issueOf("""{"t":"note","note":"NO_ADAPTER"}""").issue)
        assertEquals("No Wi-Fi adapter was found on this computer.", issueOf("""{"t":"note","note":"NO_ADAPTER"}""").note)
        assertEquals(WifiIssue.ServiceOff, issueOf("""{"t":"note","note":"WLAN_SERVICE"}""").issue)
        val failed = issueOf(adapter(), """{"t":"error","stage":"scan","message":"The device is not ready"}""")
        assertEquals(WifiIssue.ScanFailed, failed.issue); assertEquals("The Wi-Fi adapter did not return a scan. The device is not ready", failed.note)
        // An error that still left networks behind is not a failed scan.
        val partlyFine = issueOf(*lines("windows-scan-allowed.jsonl").dropLast(1).toTypedArray(), """{"t":"error","stage":"scan","message":"late"}""")
        assertNull(partlyFine.issue)
    }

    @Test fun aLinkIsConnectedOrNotAndNamesItsBandOnlyWhenTheChannelDoes() {
        fun link(json: String): WifiLink { val p = parser(); feed(p, listOf(adapter(), json, """{"t":"done","ms":1}""")); return p.snapshot(1, true).links.single() }
        val down = link("""{"t":"link","ms":5,"adapter":"$guid","connected":false}""")
        assertFalse(down.connected); assertNull(down.channel); assertNull(down.signalDbm)
        val low = link("""{"t":"link","ms":5,"adapter":"$guid","connected":true,"channel":6,"rssi":-61}""")
        assertEquals(WifiBand.Ghz24, low.band); assertEquals(2437, low.frequencyMhz); assertEquals(6, low.channel)
        val five = link("""{"t":"link","ms":5,"adapter":"$guid","connected":true,"channel":36,"rssi":-61}""")
        assertEquals(WifiBand.Ghz5, five.band); assertEquals(5180, five.frequencyMhz)
        val ambiguous = link("""{"t":"link","ms":5,"adapter":"$guid","connected":true,"channel":1,"rssi":-61}""")
        assertNull(ambiguous.band); assertNull(ambiguous.frequencyMhz); assertEquals(1, ambiguous.channel)
        val onlyQuality = link("""{"t":"link","ms":5,"adapter":"$guid","connected":true,"ssid":"x","bssid":"02:00:00:00:01:00","quality":60,"phy":7,"auth":"Wpa3Sae","cipher":"Ccmp"}""")
        assertEquals(-70, onlyQuality.signalDbm, "60 percent is -70 dBm on Windows' scale"); assertEquals(60, onlyQuality.signalPercent); assertEquals(WifiPhy.N, onlyQuality.phy)
        assertEquals(WifiSecurity.Wpa3, onlyQuality.security)
        val noPhy = link("""{"t":"link","ms":5,"adapter":"$guid","connected":true,"ssid":"x","bssid":"02:00:00:00:01:00","phy":-1}""")
        assertEquals(WifiPhy.Unknown, noPhy.phy)
        val positiveRssi = link("""{"t":"link","ms":5,"adapter":"$guid","connected":true,"rssi":0,"quality":100}""")
        assertEquals(-50, positiveRssi.signalDbm, "0 dBm is not a reading")
    }

    @Test fun linesThatAreNotEventsChangeNothing() {
        val p = parser()
        assertFalse(p.accept("#< CLIXML")); assertFalse(p.accept("")); assertFalse(p.accept("{not json")); assertFalse(p.accept("""{"t":"mystery"}""")); assertFalse(p.accept("""{"t":"adapter"}"""))
        assertFalse(p.accept("""{"t":"link","connected":true}""")); assertFalse(p.accept("""{"t":"note","note":"SOMETHING_ELSE"}""")); assertFalse(p.accept("""{"t":"ap","bssid":"nonsense"}"""))
        assertTrue(p.snapshot(0, false).adapters.isEmpty()); assertFalse(p.done)
    }

    @Test fun windowsHardwareIdsAreReadFromThePnpString() {
        val bs = Char(92)
        assertEquals(AdapterHardware(driver = "3.4", bus = "pci", pciAddress = "0000:56:00.0", pciVendorId = 0x14c3, pciDeviceId = 0x7922),
            windowsAdapterHardware("PCI${bs}VEN_14C3&DEV_7922&SUBSYS_59111A3B&REV_00${bs}4&1872dfcf&0&00E0", "0000:56:00.0", "3.4"))
        assertEquals(AdapterHardware(bus = "usb", usbVendorId = 0x0e8d, usbProductId = 0x7922), windowsAdapterHardware("USB${bs}VID_0E8D&PID_7922${bs}5&2c1a", null, ""))
        assertEquals(AdapterHardware(), windowsAdapterHardware("ROOT${bs}NET${bs}0000", null, null)); assertEquals(AdapterHardware(), windowsAdapterHardware(null, "", null))
    }
}
