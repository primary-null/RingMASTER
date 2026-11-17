package local.ringleader.desktop

import local.ringleader.core.BT_SCAN_ENDED_EARLY
import local.ringleader.core.LinuxWifiScanner
import local.ringleader.core.WifiIssue
import local.ringleader.core.WifiScan
import local.ringleader.core.WifiSecurity
import java.util.Base64
import kotlin.test.*

class WindowsWifiScannerTest {
    private val bs = Char(92)
    private val guid = "{00000000-0000-0000-0000-0000000000A1}"
    private val pnp = "PCI${bs}${bs}VEN_8086&DEV_2725&SUBSYS_00000000&REV_1A${bs}${bs}0&00000000"

    // A capture of the script on a computer with Location off, with the identifying values replaced.
    private val locationOff = listOf(
        "#< CLIXML noise",
        """{"t":"adapter","id":"$guid","name":"Example Wi-Fi 6E Wireless LAN Card","address":"020000000021","status":"Up","state":"connected","radio":"on","hwRadio":"on","airplane":false,"rxMbps":866.7,"txMbps":866.7,"driver":"1.0.0.1","pnp":"$pnp","pci":"0000:03:00.0"}""",
        """{"t":"link","ms":1466,"adapter":"$guid","connected":true,"channel":157,"rssi":-50}""",
        """{"t":"note","note":"LOCATION_OFF","access":"DeniedBySystem"}""",
        """{"t":"cycle","ms":1542}""",
        """{"t":"done","ms":1542}""",
    )

    private fun ap(ms: Int, ssid: String, bssid: String, rssi: Double, khz: Int, phy: Int, auth: String, enc: String) =
        """{"t":"ap","ms":$ms,"adapter":"$guid","ssid":"$ssid","bssid":"$bssid","rssi":$rssi,"khz":$khz,"phy":$phy,"auth":"$auth","enc":"$enc"}"""

    private val allowed = listOf(
        locationOff[1],
        """{"t":"link","ms":1500,"adapter":"$guid","connected":true,"channel":36,"rssi":-52}""",
        ap(4300, "Example Home", "02:00:00:00:01:00", -52.0, 5180000, 10, "RsnaPsk", "Ccmp"),
        ap(4300, "Example Home", "02:00:00:00:01:02", -71.0, 6135000, 10, "Wpa3Sae", "Ccmp"),
        ap(4300, "Sample Guest", "02:00:00:00:02:00", -78.0, 2462000, 6, "Open80211", "None"),
        """{"t":"link","ms":4310,"adapter":"$guid","connected":true,"channel":36,"rssi":-52,"ssid":"Example Home","bssid":"02:00:00:00:01:00","phy":10,"quality":96,"rxMbps":1200.9,"txMbps":1441.1,"auth":"RsnaPsk","cipher":"Ccmp"}""",
        """{"t":"cycle","ms":4320}""",
        """{"t":"done","ms":4321}""",
    )

    /** A fake process: prints [lines] one by one and stops after the first one [stopAfter] says yes to, as a killed process would. */
    private class Run(val lines: List<String>, val stopAfter: (String) -> Boolean = { false }) {
        var command: List<String> = emptyList(); var timeout = 0L; var now = 0L
        val stream: (List<String>, Long, (String) -> Unit) -> Unit = { c, t, emit ->
            command = c; timeout = t
            for (line in lines) { now += 100; emit(line); if (stopAfter(line)) break }
        }
    }

    private fun scripted(run: Run) = WindowsWifiScanner(run.stream, { run.now })

    @Test fun locationOffIsAnAnswerWithTheLinkWindowsStillGivesNotAnEmptyList() {
        val run = Run(locationOff)
        val updates = mutableListOf<WifiScan>()
        val scanner = scripted(run)
        val scan = scanner.scan(8) { updates += it }
        assertEquals(WifiIssue.LocationOff, scan.issue); assertEquals(WifiIssue.LocationOff.sentence(), scan.note)
        assertTrue(scan.networks.isEmpty()); assertEquals(guid, scan.adapters.single().id); assertEquals("0000:03:00.0", scan.adapters.single().hardware.pciAddress)
        assertTrue(scan.connectedLink!!.connected); assertEquals(157, scan.connectedLink!!.channel); assertEquals(-50, scan.connectedLink!!.signalDbm)
        assertTrue(scan.finished); assertEquals(8000L, scan.windowMs); assertSame(scan, updates.last()); assertTrue(updates.dropLast(1).all { !it.finished })
        assertEquals("Windows · WinRT and Native Wifi", scanner.platform)
        assertEquals(8L + 45L, run.timeout)
    }

    @Test fun anAllowedScanFillsTheNetworksAndTheLink() {
        val run = Run(allowed)
        val scan = scripted(run).scan(8)
        assertNull(scan.issue); assertNull(scan.note)
        assertEquals(setOf("Example Home" to WifiSecurity.Wpa2, "Example Home" to WifiSecurity.Wpa3, "Sample Guest" to WifiSecurity.Open), scan.networks.map { it.ssid to it.security }.toSet())
        assertEquals("Example Home", scan.connectedLink!!.ssid); assertEquals(5180, scan.connectedLink!!.frequencyMhz)
    }

    @Test fun theCommandIsPowerShellWithTheWindowAndStaysUnderTheCommandLineLimit() {
        val run = Run(locationOff)
        scripted(run).scan(12)
        assertTrue(run.command.first().lowercase().endsWith("powershell.exe"))
        assertEquals(listOf("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand"), run.command.drop(1).dropLast(1))
        val script = String(Base64.getDecoder().decode(run.command.last()), Charsets.UTF_16LE)
        assertTrue("\$Seconds = 12" in script); assertFalse("__SECONDS__" in script)
        assertEquals(script, windowsWifiScript(12))
        assertTrue(run.command.sumOf { it.length } < 30_000, "Windows allows 32,767 characters on a command line; this one is ${run.command.sumOf { it.length }}")
        assertTrue(windowsWifiCommand(60).sumOf { it.length } < 30_000)
        assertFalse(script.lines().any { it.trim().startsWith("#") || it.isBlank() || it != it.trim() }, "comments, blank lines and indentation are dropped")
        for ((requested, written) in listOf(1 to 5, 5 to 5, 30 to 30, 999 to 60)) {
            val r = Run(locationOff); scripted(r).scan(requested)
            assertTrue("\$Seconds = $written" in String(Base64.getDecoder().decode(r.command.last()), Charsets.UTF_16LE), "window $requested")
        }
    }

    @Test fun theScriptOnlyReadsAndNeverConnectsOrTouchesAProfile() {
        val script = windowsWifiScript(8)
        for (word in listOf("ConnectAsync", "Disconnect", "WlanConnect", "WlanDisconnect", "WlanSetProfile", "WlanDeleteProfile", "WlanGetProfile", "WlanSetInterface", "WlanScan(",
            "GetProfile", "ProfileXml", "keyMaterial", "key=clear", "netsh", "Set-NetAdapter", "Enable-NetAdapter", "Disable-NetAdapter", "Restart-NetAdapter", "Start-Process", "Remove-Item", "Set-Item", "New-Item", "Set-ItemProperty", "Out-File", "Set-Content")) {
            assertFalse(word in script, word)
        }
        val functions = Regex("\\bWlan[A-Z][A-Za-z]+\\b").findAll(script).map { it.value }.toSet()
        assertEquals(setOf("WlanOpenHandle", "WlanCloseHandle", "WlanEnumInterfaces", "WlanQueryInterface", "WlanFreeMemory"), functions, "only the read calls of the Native Wifi API")
        // The one query that names a profile is read past, never read: its name field is skipped.
        assertTrue("profile name 8 (not read)" in windowsWifiScriptRaw())
        assertTrue("[Console]::OutputEncoding" in script && "UTF8Encoding" in script)
    }

    private fun windowsWifiScriptRaw() = WindowsWifiScanner::class.java.getResourceAsStream("/wifi/wifi-scan.ps1")!!.use { it.readBytes().decodeToString() }

    @Test fun cancellingEndsTheScanEarlyAndSaysSo() {
        lateinit var scanner: WindowsWifiScanner
        val run = Run(locationOff.drop(1), stopAfter = { line -> if ("\"link\"" in line) { scanner.cancel(); true } else false })
        scanner = scripted(run)
        val scan = scanner.scan(8)
        assertEquals(WifiIssue.EndedEarly, scan.issue); assertEquals(BT_SCAN_ENDED_EARLY, scan.note); assertTrue(scan.finished)
        assertEquals(1, scan.adapters.size)
    }

    @Test fun aScriptThatEndsWithoutADoneLineIsMarkedEndedEarlyButOneThatSaidNothingUseableIsAnError() {
        val cut = scripted(Run(allowed.dropLast(1))).scan(8)
        assertEquals(WifiIssue.EndedEarly, cut.issue); assertTrue(cut.networks.isNotEmpty(), "what arrived is kept")
        val failure = assertFailsWith<IllegalStateException> { scripted(Run(listOf("#< CLIXML", "Cannot find type", "{broken"))).scan(8) }
        assertEquals("The Windows Wi-Fi scan returned unreadable output.", failure.message)
        // And the scanner is usable again afterwards.
        assertEquals(WifiIssue.LocationOff, scripted(Run(locationOff)).scan(8).issue)
    }

    @Test fun theSystemPageThatFixesAProblemOpensWithoutChangingAnything() {
        val open = { issue: WifiIssue? -> wifiSettingsCommand(issue, windows = true) }
        assertEquals(listOf("cmd.exe", "/c", "start", "", "ms-settings:privacy-location"), open(WifiIssue.LocationOff))
        assertEquals("ms-settings:network-airplanemode", open(WifiIssue.AirplaneMode)!!.last())
        assertEquals("ms-settings:network-wifi", open(WifiIssue.AdapterOff)!!.last()); assertEquals("ms-settings:network-wifi", open(WifiIssue.HardwareSwitch)!!.last())
        for (issue in listOf(WifiIssue.NoAdapter, WifiIssue.NoScanTool, WifiIssue.EndedEarly, WifiIssue.ServiceOff, null)) assertNull(open(issue), "$issue")
        assertNull(wifiSettingsCommand(WifiIssue.LocationOff, windows = false), "this app opens no page on Linux")
    }

    @Test fun theBundledScriptRunsForRealOnWindowsAsTheAppSendsIt() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").lowercase().startsWith("windows"), "needs Windows PowerShell")
        // The slimmed, encoded script, through the scanner's own process code. It needs no adapter: without one it says so and ends.
        val scan = WindowsWifiScanner().scan(5)
        assertTrue(scan.finished)
        assertNotEquals(WifiIssue.EndedEarly, scan.issue, "the script reached its done line")
        assertTrue(scan.adapters.isNotEmpty() || scan.issue in setOf(WifiIssue.NoAdapter, WifiIssue.ServiceOff), "adapters, or the typed reason there are none: ${scan.issue}")
        for (adapter in scan.adapters) assertTrue(adapter.id.startsWith("{") && adapter.name.isNotBlank())
        for (link in scan.links) if (!link.connected) assertNull(link.ssid)
    }

    @Test fun theScannerForThisComputerIsChosenByPlatform() {
        val os = System.getProperty("os.name").lowercase()
        val scanner = localWifiScanner()
        when {
            os.startsWith("windows") -> assertIs<WindowsWifiScanner>(scanner)
            os.contains("linux") -> assertIs<LinuxWifiScanner>(scanner)
            else -> assertNull(scanner)
        }
    }
}
