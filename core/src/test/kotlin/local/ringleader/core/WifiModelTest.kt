package local.ringleader.core

import java.time.Instant
import kotlin.test.*

class WifiModelTest {
    @Test fun channelsAndFrequenciesRoundTripInEveryBand() {
        // (centre MHz, channel, band)
        val table = listOf(
            Triple(2412, 1, WifiBand.Ghz24), Triple(2437, 6, WifiBand.Ghz24), Triple(2472, 13, WifiBand.Ghz24), Triple(2484, 14, WifiBand.Ghz24),
            Triple(5180, 36, WifiBand.Ghz5), Triple(5500, 100, WifiBand.Ghz5), Triple(5745, 149, WifiBand.Ghz5), Triple(5885, 177, WifiBand.Ghz5), Triple(5905, 181, WifiBand.Ghz5),
            Triple(4915, 183, WifiBand.Ghz5), Triple(4980, 196, WifiBand.Ghz5),
            Triple(5935, 2, WifiBand.Ghz6), Triple(5955, 1, WifiBand.Ghz6), Triple(6115, 33, WifiBand.Ghz6), Triple(6135, 37, WifiBand.Ghz6), Triple(7115, 233, WifiBand.Ghz6),
        )
        for ((mhz, channel, band) in table) {
            assertEquals(band, wifiBandOfMhz(mhz), "band of $mhz")
            assertEquals(channel, wifiChannelOfMhz(mhz), "channel of $mhz")
            assertEquals(mhz, wifiMhzOfChannel(channel, band), "frequency of $band channel $channel")
        }
        // Not channel centres: between channels, the guard bands and outside Wi-Fi.
        assertNull(wifiChannelOfMhz(2410)); assertNull(wifiChannelOfMhz(2483)); assertNull(wifiChannelOfMhz(5001)); assertNull(wifiChannelOfMhz(5150))
        assertNull(wifiChannelOfMhz(5925)); assertNull(wifiChannelOfMhz(7120)); assertNull(wifiChannelOfMhz(1000))
        assertNull(wifiBandOfMhz(1000)); assertNull(wifiBandOfMhz(7126)); assertNull(wifiBandOfMhz(3600))
        assertEquals(WifiBand.Ghz5, wifiBandOfMhz(5924)); assertEquals(WifiBand.Ghz6, wifiBandOfMhz(5925))
        assertNull(wifiMhzOfChannel(0, WifiBand.Ghz24)); assertNull(wifiMhzOfChannel(15, WifiBand.Ghz24)); assertNull(wifiMhzOfChannel(31, WifiBand.Ghz5)); assertNull(wifiMhzOfChannel(234, WifiBand.Ghz6))
        // The same number is a different frequency in a different band.
        assertEquals(2412, wifiMhzOfChannel(1, WifiBand.Ghz24)); assertEquals(5955, wifiMhzOfChannel(1, WifiBand.Ghz6))
    }

    @Test fun aBareChannelNamesItsBandOnlyWhenOneBandFits() {
        for (c in listOf(2, 3, 4, 6, 7, 8, 10, 11, 12, 14)) assertEquals(WifiBand.Ghz24, wifiBandOfChannel(c), "channel $c")
        for (c in listOf(1, 5, 9, 13)) assertNull(wifiBandOfChannel(c), "channel $c is 2.4 or 6 GHz")
        for (c in listOf(36, 40, 52, 64, 100, 116, 144, 34, 38)) assertEquals(WifiBand.Ghz5, wifiBandOfChannel(c), "channel $c")
        for (c in listOf(149, 153, 157, 161, 165, 169, 173, 177)) assertNull(wifiBandOfChannel(c), "channel $c is 5 or 6 GHz")
        for (c in listOf(17, 21, 37, 97, 181, 185, 233)) assertEquals(WifiBand.Ghz6, wifiBandOfChannel(c), "channel $c")
        assertNull(wifiBandOfChannel(0)); assertNull(wifiBandOfChannel(234)); assertNull(wifiBandOfChannel(-3))
    }

    @Test fun widthsAreReadFromTheWordsSystemsUse() {
        assertEquals(80, parseWifiWidthMhz("80 MHz")); assertEquals(80, parseWifiWidthMhz("80MHz")); assertEquals(20, parseWifiWidthMhz("20 MHz (no HT)"))
        assertEquals(160, parseWifiWidthMhz("80+80 MHz")); assertEquals(160, parseWifiWidthMhz("160 MHz")); assertEquals(320, parseWifiWidthMhz("320 MHz"))
        assertEquals(40, parseWifiWidthMhz("1 (40 MHz)")); assertEquals(20, parseWifiWidthMhz(", width: 20 MHz, center1: 5180 MHz"))
        assertNull(parseWifiWidthMhz("")); assertNull(parseWifiWidthMhz(null)); assertNull(parseWifiWidthMhz("33 MHz")); assertNull(parseWifiWidthMhz("fast"))
    }

    @Test fun generationFollowsTheStandardAndTheBand() {
        assertEquals("Wi-Fi 4", wifiGenerationLabel(WifiPhy.N, WifiBand.Ghz24))
        assertEquals("Wi-Fi 5", wifiGenerationLabel(WifiPhy.Ac, WifiBand.Ghz5))
        assertEquals("Wi-Fi 6", wifiGenerationLabel(WifiPhy.Ax, WifiBand.Ghz5)); assertEquals("Wi-Fi 6", wifiGenerationLabel(WifiPhy.Ax, WifiBand.Ghz24)); assertEquals("Wi-Fi 6", wifiGenerationLabel(WifiPhy.Ax, null))
        assertEquals("Wi-Fi 6E", wifiGenerationLabel(WifiPhy.Ax, WifiBand.Ghz6))
        assertEquals("Wi-Fi 7", wifiGenerationLabel(WifiPhy.Be, WifiBand.Ghz6))
        for (old in listOf(WifiPhy.B, WifiPhy.A, WifiPhy.G, WifiPhy.Ad, WifiPhy.Unknown)) assertNull(wifiGenerationLabel(old, WifiBand.Ghz5), old.name)
        assertEquals("802.11ax", WifiPhy.Ax.standard)
        // The same on an access point and on a link.
        assertEquals("Wi-Fi 6E", WifiAccessPoint("x", "02:00:00:00:00:01", phy = WifiPhy.Ax, band = WifiBand.Ghz6).generation)
        assertEquals("Wi-Fi 5", WifiLink("a", true, phy = WifiPhy.Ac, band = WifiBand.Ghz5).generation)
    }

    @Test fun windowsPhyTypesCoverTheNumbersAndTheNames() {
        val numbers = mapOf("4" to WifiPhy.A, "5" to WifiPhy.B, "6" to WifiPhy.G, "7" to WifiPhy.N, "8" to WifiPhy.Ac, "9" to WifiPhy.Ad, "10" to WifiPhy.Ax, "11" to WifiPhy.Be, "2" to WifiPhy.B)
        for ((n, phy) in numbers) assertEquals(phy, wifiPhyFromWindows(n), n)
        val names = mapOf("Ofdm" to WifiPhy.A, "Hrdsss" to WifiPhy.B, "Erp" to WifiPhy.G, "HT" to WifiPhy.N, "Vht" to WifiPhy.Ac, "Dmg" to WifiPhy.Ad, "HE" to WifiPhy.Ax, "Eht" to WifiPhy.Be, "he" to WifiPhy.Ax)
        for ((n, phy) in names) assertEquals(phy, wifiPhyFromWindows(n), n)
        for (unknown in listOf("0", "1", "3", "12", "-1", "", "Fhss", "banana")) assertEquals(WifiPhy.Unknown, wifiPhyFromWindows(unknown), unknown)
        assertEquals(WifiPhy.Unknown, wifiPhyFromWindows(null))
    }

    @Test fun signalConvertsBetweenPercentAndDbm() {
        // Windows: 0 is -100 dBm, 100 is -50 dBm, linear between.
        assertEquals(-100, wifiDbmFromWindowsQuality(0)); assertEquals(-50, wifiDbmFromWindowsQuality(100)); assertEquals(-75, wifiDbmFromWindowsQuality(50))
        assertEquals(-64, wifiDbmFromWindowsQuality(71)); assertEquals(-50, wifiDbmFromWindowsQuality(150)); assertEquals(-100, wifiDbmFromWindowsQuality(-5))
        assertEquals(100, wifiPercentFromDbm(-50)); assertEquals(0, wifiPercentFromDbm(-100)); assertEquals(50, wifiPercentFromDbm(-75))
        assertEquals(100, wifiPercentFromDbm(-40)); assertEquals(0, wifiPercentFromDbm(-120)); assertEquals(98, wifiPercentFromDbm(-51))
        for (p in 0..100 step 2) assertEquals(p, wifiPercentFromDbm(wifiDbmFromWindowsQuality(p)), "round trip $p")
        // NetworkManager: -100 dBm is 0, -40 dBm is 100.
        assertEquals(-100, wifiDbmFromNetworkManagerStrength(0)); assertEquals(-40, wifiDbmFromNetworkManagerStrength(100)); assertEquals(-51, wifiDbmFromNetworkManagerStrength(82))
        assertEquals(-67, wifiDbmFromNetworkManagerStrength(55)); assertEquals(-62, wifiDbmFromNetworkManagerStrength(64)); assertEquals(-73, wifiDbmFromNetworkManagerStrength(45))
        assertEquals(-40, wifiDbmFromNetworkManagerStrength(120)); assertEquals(-100, wifiDbmFromNetworkManagerStrength(-9))
    }

    @Test fun securityIsNamedInPlainWordsFromEachSystem() {
        val nm = mapOf(
            null to WifiSecurity.Open, "" to WifiSecurity.Open, "--" to WifiSecurity.Open, "WEP" to WifiSecurity.Wep, "WPA1" to WifiSecurity.Wpa, "WPA" to WifiSecurity.Wpa,
            "WPA2" to WifiSecurity.Wpa2, "WPA3" to WifiSecurity.Wpa3, "WPA2 WPA3" to WifiSecurity.Wpa2Wpa3, "WPA1 WPA2" to WifiSecurity.WpaWpa2,
            "WPA2 802.1X" to WifiSecurity.Enterprise, "WPA3 802.1X" to WifiSecurity.Enterprise, "802.1X" to WifiSecurity.Enterprise,
            "OWE" to WifiSecurity.EnhancedOpen, "OWE-TM" to WifiSecurity.EnhancedOpen, "mystery" to WifiSecurity.Unknown,
        )
        for ((text, expected) in nm) assertEquals(expected, wifiSecurityFromNetworkManager(text), "nmcli '$text'")
        val windows = listOf(
            Triple("None", "None", WifiSecurity.Open), Triple("Open80211", "None", WifiSecurity.Open), Triple("Open80211", "Wep", WifiSecurity.Wep), Triple("Open80211", "Wep104", WifiSecurity.Wep),
            Triple("SharedKey80211", "Wep", WifiSecurity.Wep), Triple("Wpa", "Tkip", WifiSecurity.Enterprise), Triple("WpaPsk", "Tkip", WifiSecurity.Wpa),
            Triple("Rsna", "Ccmp", WifiSecurity.Enterprise), Triple("RsnaPsk", "Ccmp", WifiSecurity.Wpa2), Triple("Wpa3Sae", "Ccmp", WifiSecurity.Wpa3),
            Triple("Wpa3", "Gcmp256", WifiSecurity.Enterprise), Triple("Wpa3Enterprise", "Gcmp", WifiSecurity.Enterprise), Triple("Owe", "Ccmp", WifiSecurity.EnhancedOpen),
            Triple("Ihv", "Ihv", WifiSecurity.Unknown), Triple("Unknown", "Unknown", WifiSecurity.Unknown),
        )
        for ((auth, enc, expected) in windows) assertEquals(expected, wifiSecurityFromWindows(auth, enc), "$auth/$enc")
        assertEquals(WifiSecurity.Unknown, wifiSecurityFromWindows(null))
        val suites = listOf(
            Triple(setOf("PSK"), null, WifiSecurity.Wpa2), Triple(setOf("SAE"), null, WifiSecurity.Wpa3), Triple(setOf("PSK", "SAE"), null, WifiSecurity.Wpa2Wpa3),
            Triple(setOf("FT/PSK"), null, WifiSecurity.Wpa2), Triple(setOf("IEEE", "802.1X"), null, WifiSecurity.Enterprise), Triple(setOf("OWE"), null, WifiSecurity.EnhancedOpen),
            Triple(setOf("PSK"), setOf("PSK"), WifiSecurity.WpaWpa2), Triple(null, setOf("PSK"), WifiSecurity.Wpa), Triple(setOf("FILS/SHA-256"), null, WifiSecurity.Wpa2),
            Triple(emptySet<String>(), null, WifiSecurity.Wpa2),
        )
        for ((rsn, wpa, expected) in suites) assertEquals(expected, wifiSecurityFromSuites(rsn, wpa, privacy = true), "$rsn $wpa")
        assertEquals(WifiSecurity.Wep, wifiSecurityFromSuites(null, null, privacy = true))
        assertEquals(WifiSecurity.Open, wifiSecurityFromSuites(null, null, privacy = false))
        // The labels are the plain words the brief names.
        assertEquals(listOf("Open", "WEP", "WPA2 Personal", "WPA3 Personal", "Enterprise"),
            listOf(WifiSecurity.Open, WifiSecurity.Wep, WifiSecurity.Wpa2, WifiSecurity.Wpa3, WifiSecurity.Enterprise).map { it.label })
    }

    private fun ap(ssid: String?, bssid: String, dbm: Int?, security: WifiSecurity = WifiSecurity.Wpa2, band: WifiBand? = WifiBand.Ghz5, connected: Boolean = false) =
        WifiAccessPoint(ssid, bssid, dbm, dbm?.let(::wifiPercentFromDbm), band = band, security = security, connected = connected)

    @Test fun accessPointsGroupIntoNetworksByNameAndSecurityAndSortForDisplay() {
        val networks = groupWifiNetworks(listOf(
            ap("Example Home", "02:00:00:00:01:01", -61, band = WifiBand.Ghz24),
            ap("Example Home", "02:00:00:00:01:00", -48, band = WifiBand.Ghz5, connected = true),
            ap("Example Home", "02:00:00:00:01:02", -70, WifiSecurity.Wpa3, WifiBand.Ghz6),
            ap("Sample Guest", "02:00:00:00:02:00", -66, WifiSecurity.Open, WifiBand.Ghz24),
            ap(null, "02:00:00:00:05:01", -82), ap(null, "02:00:00:00:05:02", -80),
            ap("alpha", "02:00:00:00:06:00", null),
        ))
        // The connected network first, then by signal, unnamed signal last, names last of all.
        assertEquals(listOf("Example Home" to WifiSecurity.Wpa2, "Sample Guest" to WifiSecurity.Open, "Example Home" to WifiSecurity.Wpa3, null to WifiSecurity.Wpa2, null to WifiSecurity.Wpa2, "alpha" to WifiSecurity.Wpa2),
            networks.map { it.ssid to it.security })
        val home = networks.first()
        assertTrue(home.connected); assertEquals(-48, home.signalDbm)
        assertEquals(listOf("02:00:00:00:01:00", "02:00:00:00:01:01"), home.accessPoints.map { it.bssid }, "connected first, then by signal")
        assertEquals(listOf(WifiBand.Ghz24, WifiBand.Ghz5), home.bands)
        assertSame(home.accessPoints[0], home.strongest)
        // Hidden access points are never merged into one network.
        val hidden = networks.filter { it.hidden }
        assertEquals(2, hidden.size); assertTrue(hidden.all { it.accessPoints.size == 1 })
        assertNull(networks.last().signalDbm)
    }

    @Test fun aLiveScanKeepsReadingsAndForgetsConnectionsThatEnded() {
        val live = WifiLiveScan(BtSource.Local, 8000, Instant.EPOCH)
        live.adapters = listOf(WifiAdapter("wlan0", "Fixture"))
        live.cycle(listOf(ap("Example Home", "02:00:00:00:01:00", -50, connected = true), ap("Sample Guest", "02:00:00:00:02:00", -70)),
            listOf(WifiLink("wlan0", true, "Example Home", signalDbm = -50)), 1000)
        live.cycle(listOf(ap("Example Home", "02:00:00:00:01:00", -54, connected = true)), listOf(WifiLink("wlan0", true, "Example Home", signalDbm = -54)), 5000)
        val partial = live.snapshot(5000, finished = false)
        assertFalse(partial.finished); assertEquals(8000L, partial.windowMs); assertEquals(5000L, partial.elapsedMs)
        val home = partial.networks.first { it.ssid == "Example Home" }.accessPoints.single()
        assertEquals(-54, home.signalDbm); assertEquals(1000L, home.firstSeenMs); assertEquals(5000L, home.lastSeenMs)
        assertEquals(listOf(WifiSample(1000, -50), WifiSample(5000, -54)), home.samples)
        assertNotNull(home.smoothedDbm); assertTrue(home.smoothedDbm!! < -50f && home.smoothedDbm!! > -54.5f)
        val guest = partial.networks.first { it.ssid == "Sample Guest" }.accessPoints.single()
        assertEquals(1000L, guest.lastSeenMs, "an access point a later scan did not find keeps its older time and its last signal"); assertEquals(-70, guest.signalDbm)
        assertEquals(listOf(WifiSample(1000, -50), WifiSample(5000, -54)), partial.links.single().samples)
        // An access point the next scan no longer marks as connected stops being connected.
        live.cycle(listOf(ap("Example Home", "02:00:00:00:01:00", -55)), listOf(WifiLink("wlan0", false)), 9000)
        val last = live.snapshot(9000, finished = true)
        assertFalse(last.networks.first { it.ssid == "Example Home" }.connected)
        assertFalse(last.connectedLink != null); assertTrue(last.finished)
    }

    @Test fun readingsAreCapped() {
        val live = WifiLiveScan(BtSource.Local, 1_000_000, Instant.EPOCH)
        for (i in 0 until WIFI_SAMPLE_CAP + 40) live.cycle(listOf(ap("n", "02:00:00:00:01:00", -60 - i % 20)), emptyList(), i * 10L)
        val samples = live.snapshot(0, true).networks.single().accessPoints.single().samples
        assertEquals(WIFI_SAMPLE_CAP, samples.size); assertEquals(40 * 10L, samples.first().ms)
    }

    @Test fun everyIssueHasItsOwnSentenceThatNamesWhereItHappened() {
        val local = WifiIssue.entries.associateWith { it.sentence() }
        assertEquals(WifiIssue.entries.size, local.values.toSet().size, "sentences are distinct")
        assertTrue(local.values.all { it.isNotBlank() && it.endsWith(".") })
        val vm = BtSource("qemu/101", "VM 101 · fixture")
        assertEquals("No Wi-Fi adapter was found on this computer.", WifiIssue.NoAdapter.sentence())
        assertEquals("No Wi-Fi adapter was found in this VM.", WifiIssue.NoAdapter.sentence(vm))
        assertTrue("Location" in WifiIssue.LocationOff.sentence() && "Privacy & security" in WifiIssue.LocationOff.sentence(), "says exactly what to turn on")
        assertEquals(WIFI_LOCATION_OFF, WifiIssue.LocationOff.sentence(vm))
        assertEquals(BT_SCAN_ENDED_EARLY, WifiIssue.EndedEarly.sentence())
        val scan = wifiIssueScan(vm, WifiIssue.NoInterface, Instant.EPOCH)
        assertEquals(WifiIssue.NoInterface, scan.issue); assertEquals(WifiIssue.NoInterface.sentence(vm), scan.note); assertTrue(scan.networks.isEmpty())
    }

    @Test fun radioBlocksCombineSoftAndHardFlags() {
        assertEquals(RadioBlock.Unknown, radioBlockOf(null, null)); assertEquals(RadioBlock.None, radioBlockOf(false, false)); assertEquals(RadioBlock.None, radioBlockOf(false, null))
        assertEquals(RadioBlock.Software, radioBlockOf(true, false)); assertEquals(RadioBlock.Hardware, radioBlockOf(false, true)); assertEquals(RadioBlock.Both, radioBlockOf(true, true))
        assertEquals(RadioBlock.Hardware, radioBlockOf(null, true)); assertEquals(RadioBlock.Software, radioBlockOf(true, null))
    }

    @Test fun scanControlCancelsTheProcessItRegistered() {
        val control = WifiScanControl()
        var killed = 0
        control.exclusive {
            control.bind { killed++ }
            assertFalse(control.wasCancelled())
            control.cancel()
            assertTrue(control.wasCancelled()); assertEquals(1, killed)
            control.markTimedOut(); assertTrue(control.didTimeOut())
        }
        // The next scan starts clean and an old kill action is gone.
        control.exclusive { assertFalse(control.wasCancelled()); assertFalse(control.didTimeOut()); control.cancel(); assertEquals(1, killed) }
    }

    @Test fun theDemoScanIsInventedAndWellFormed() {
        val scan = sampleWifiScan(BtSource.Local, Instant.EPOCH)
        assertEquals("Sample data", scan.note); assertNull(scan.issue)
        assertEquals(listOf("Example Home" to WifiSecurity.Wpa2Wpa3, "Sample Guest" to WifiSecurity.Open, "Example Home" to WifiSecurity.Wpa3, "Sample Office" to WifiSecurity.Enterprise, null to WifiSecurity.Wpa2),
            scan.networks.map { it.ssid to it.security })
        val points = scan.networks.flatMap { it.accessPoints }
        assertTrue(points.all { it.bssid.startsWith("02:00:00:") }, "locally administered addresses only")
        assertEquals(setOf(WifiBand.Ghz24, WifiBand.Ghz5, WifiBand.Ghz6), points.mapNotNull { it.band }.toSet())
        assertTrue(points.all { it.channel == wifiChannelOfMhz(it.frequencyMhz!!) })
        val link = scan.connectedLink!!
        assertEquals("02:00:00:00:02:01", link.bssid); assertEquals("Wi-Fi 6", link.generation); assertTrue(scan.networks.first().connected)
        assertTrue(scan.adapters.single().address!!.startsWith("02:00:00:"))
        assertEquals(scan, SampleWifiScanner { Instant.EPOCH }.scan(8))
        val seen = mutableListOf<WifiScan>()
        SampleWifiScanner { Instant.EPOCH }.scan(8) { seen += it }
        assertEquals(1, seen.size)
    }
}
