package local.ringleader.core

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.*

class RadioScriptTest {
    private val bs = Char(92).toString()
    private val nonce = "0123456789abcdef"
    private val QUOTE = Char(34).toString()

    @Test fun theFrameIsReadStrictlyByHeaderAndToken() {
        val text = "\n\nRINGMASTER-RADIO 1 probe $nonce\n" +
            "$nonce\ttools\tnmcli\tiw\n" +
            "$nonce\twifi\tid=wlan0\tproduct=A=B maker\tflag\n" +
            "$nonce\tbegin\tfirst\nraw one\n$nonce-not-ours\tdone\n\n$nonce\tend\tfirst\n" +
            "$nonce\tbegin\tfirst\nraw two\n\n$nonce\tend\tfirst\n" +
            "$nonce\tbegin\tiw-link wlan0\nNot connected.\n\n$nonce\tend\tiw-link wlan0\n" +
            "stray line outside a section\n" +
            "$nonce\tdone\n$nonce\twifi\tid=after\n"
        val frame = parseRadioFrame(text, "probe")
        assertTrue(frame.complete); assertFalse(frame.early); assertEquals("probe", frame.kind)
        assertEquals(listOf("tools", "wifi"), frame.records.map { it.kind }, "nothing after the closing line is read")
        assertEquals(listOf("nmcli", "iw"), frame.records("tools").single().items)
        val wifi = frame.records("wifi").single()
        assertEquals("wlan0", wifi.field("id")); assertEquals("A=B maker", wifi.field("product"), "only the first = splits"); assertNull(wifi.field("flag"))
        assertEquals("raw one\n$nonce-not-ours\tdone", frame.section("first"), "a line with another nonce is raw text, and the blank line before the end marker is dropped")
        assertEquals(listOf("first", "first", "iw-link wlan0"), frame.sections.map { it.first })
        assertEquals(listOf("wlan0" to "Not connected."), frame.sectionsWith("iw-link"))
        assertTrue(frame.sectionsWith("iw-scan").isEmpty())
    }

    @Test fun aFrameThatWasCutOffIsKnownToBeIncomplete() {
        val early = parseRadioFrame("RINGMASTER-RADIO 1 wifi $nonce\n$nonce\tdone\tearly\n", "wifi")
        assertTrue(early.early); assertFalse(early.complete)
        val noEnd = parseRadioFrame("RINGMASTER-RADIO 1 wifi $nonce\n$nonce\tbegin\tnm-list\nsome rows", "wifi")
        assertFalse(noEnd.complete); assertEquals("some rows", noEnd.section("nm-list"), "a section that was never closed keeps what arrived")
        val crlf = parseRadioFrame("RINGMASTER-RADIO 1 wifi $nonce\r\n$nonce\ttools\tiw\r\n$nonce\tdone\r\n", "wifi")
        assertTrue(crlf.complete); assertEquals(listOf("iw"), crlf.records("tools").single().items)
    }

    @Test fun textThatIsNotAFrameIsRefused() {
        for (bad in listOf("", "   \n", "RINGMASTER-RADIO 1 wifi", "RINGMASTER-RADIO one wifi $nonce", "RINGMASTER-RADIO 1 wifi abc", "RINGMASTER-RADIO 1 WIFI $nonce",
            "x\nRINGMASTER-RADIO 1 wifi $nonce\n$nonce\tdone", "RINGMASTER-RADIO 1 probe $nonce\n$nonce\tdone")) {
            assertEquals(RADIO_OUTPUT_UNREADABLE, assertFailsWith<IllegalStateException>(bad) { parseRadioFrame(bad, "wifi") }.message, bad)
        }
        assertEquals(RADIO_OUTPUT_VERSION, assertFailsWith<IllegalStateException> { parseRadioFrame("RINGMASTER-RADIO 9 wifi $nonce", "wifi") }.message)
    }

    @Test fun theScriptsLoadWithUnixLineEndsAndStartWithTheSharedPart() {
        val dollar = "$"
        for (script in listOf(RadioScripts.probe, RadioScripts.wifiScan)) {
            assertFalse(Char(13) in script); assertTrue(script.startsWith("# RingMASTER radio scripts")); assertTrue("radio_rec()" in script && "wifi_ifaces()" in script)
        }
        assertTrue("printf 'RINGMASTER-RADIO 1 probe %s" + bs + "n' " + "\"" + dollar + "N\"" in RadioScripts.probe)
        assertTrue("printf 'RINGMASTER-RADIO 1 wifi %s" + bs + "n' " + "\"" + dollar + "N\"" in RadioScripts.wifiScan)
        assertTrue("BUDGET=" + dollar + "{1:-20}" in RadioScripts.wifiScan, "the scan budget is the first argument")
        assertTrue(RadioScripts.probe.length < 12_000 && RadioScripts.wifiScan.length < 12_000, "short scripts")
    }

    @Test fun theCommandRunsTheScriptUnderTimeoutAndQuotesItAndItsArguments() {
        val command = radioScriptCommand("echo 'hi' \"there\"", 7, "a b", "it's")
        assertEquals(listOf("sh", "-c"), command.take(2)); assertEquals(3, command.size)
        val quote = "'" + bs + "''"
        val quotedScript = "'echo ${quote}hi${quote} \"there\"'"
        assertTrue("timeout -k 2 7 sh -c $quotedScript ringmaster-radio 'a b' 'it${quote}s'" in command[2], command[2])
        assertTrue("\n    sh -c $quotedScript ringmaster-radio 'a b' 'it${quote}s'\n" in command[2], "without timeout the same command runs bare")
        assertTrue(command[2].startsWith("if command -v timeout"))
    }

    @Test fun theScriptsOnlyLookAtThingsAndNeverChangeOrReadSecrets() {
        val dollar = "$"
        val allowed = mapOf(
            "nmcli" to listOf("-t -f RUNNING general", "-t --escape yes -f DEVICE,TYPE,STATE,CONNECTION device status", "-t -f BANDWIDTH device wifi list --rescan no",
                "-t --escape yes -f " + QUOTE + "IN-USE,BSSID,SSID,MODE,CHAN,FREQ,RATE,SIGNAL,SECURITY,DEVICE" + dollar + "BW" + QUOTE + " device wifi list --rescan yes"),
            "iw" to listOf("dev", "dev " + QUOTE + dollar + "i" + QUOTE + " link", "dev " + QUOTE + dollar + "i" + QUOTE + " scan", "dev " + QUOTE + dollar + "i" + QUOTE + " scan dump"),
            "bluetoothctl" to listOf("list", "show " + QUOTE + dollar + "a" + QUOTE), "hciconfig" to listOf("-a"), "hcitool" to listOf("dev"), "btmgmt" to listOf("info"), "rfkill" to listOf("list"),
        )
        // Every tool runs through `t SECONDS tool args`, and its arguments are on the list. Nothing else may start a tool.
        val call = Regex(bs + "bt (?:" + QUOTE + bs + dollar + bs + "w+" + QUOTE + "|" + bs + "d+) (nmcli|iw|bluetoothctl|hciconfig|hcitool|btmgmt|rfkill)" + bs + "b([^|);" + bs + "n]*)")
        val atCommandStart = Regex("(?:^|[;&|(]|" + bs + "bdo|" + bs + "bthen|" + bs + "belse)" + bs + "s*(nmcli|iw|bluetoothctl|hciconfig|hcitool|btmgmt|rfkill)" + bs + "b(?!-)", RegexOption.MULTILINE)
        val redirect = Regex(bs + "s+" + bs + "d*>+" + bs + "s*" + bs + "S+")
        for ((name, script) in mapOf("probe" to RadioScripts.probe, "wifi" to RadioScripts.wifiScan)) {
            val joined = script.lines().filterNot { it.trim().startsWith("#") }.joinToString(Char(10).toString())
            val calls = call.findAll(joined).toList()
            assertTrue(calls.size >= 3, "$name: the calls were found")
            for (m in calls) {
                val args = redirect.replace(m.groupValues[2], "").trim()
                assertTrue(args in allowed.getValue(m.groupValues[1]), "$name: ${m.groupValues[1]} $args is not on the list of read-only calls")
            }
            assertTrue(atCommandStart.findAll(joined).none(), "$name starts a tool without the time limit: " + atCommandStart.find(joined)?.value)
            for (word in listOf("connect", "disconnect", "pair", "trust", "unblock", "block", "remove", "modify", "delete", "wpa_cli", "iwctl", "ifconfig", "dhclient", "--ask", "power on", "scan on", "password", "passwords", "secret", "secrets")) {
                assertFalse(Regex(bs + "b" + Regex.escape(word) + bs + "b", RegexOption.IGNORE_CASE).containsMatchIn(joined), "$name mentions $word")
            }
            // Nothing is written except to the null device and the standard streams.
            val targets = Regex(">" + bs + "s*([^" + bs + "s&|;)]+)").findAll(joined).map { it.groupValues[1] }.toSet()
            assertTrue(targets.all { it == "/dev/null" }, "$name writes to $targets")
        }
        // The shell forms that run a tool for real are covered: the wifi script uses nmcli, iw and rfkill; the probe uses the Bluetooth tools and iw.
        assertTrue(call.findAll(RadioScripts.wifiScan).any { it.groupValues[1] == "nmcli" } && call.findAll(RadioScripts.probe).any { it.groupValues[1] == "bluetoothctl" })
    }

    // A constructed sysfs tree and the real probe script, on Linux only -------------------------------------------------

    private fun write(path: Path, text: String) { Files.createDirectories(path.parent); Files.writeString(path, text + "\n") }
    private fun link(link: Path, target: String) { Files.createDirectories(link.parent); Files.createSymbolicLink(link, Path.of(target)) }

    private fun constructedTree(root: Path) {
        val sys = root.resolve("sys")
        val pci = sys.resolve("devices/pci0000:00/0000:00:1c.2/0000:03:00.0")
        write(pci.resolve("vendor"), "0x8086"); write(pci.resolve("device"), "0x2725")
        link(pci.resolve("driver"), "../../../../../bus/pci/drivers/iwlwifi"); link(pci.resolve("subsystem"), "../../../../../bus/pci")
        val net = sys.resolve("class/net/wlp3s0")
        write(net.resolve("address"), "02:00:00:00:00:01"); write(net.resolve("operstate"), "up"); write(net.resolve("carrier"), "1"); Files.createDirectories(net.resolve("wireless"))
        link(net.resolve("device"), "../../../devices/pci0000:00/0000:00:1c.2/0000:03:00.0")
        Files.createDirectories(sys.resolve("devices/virtual/ieee80211/phy0")); Files.createDirectories(sys.resolve("devices/virtual/ieee80211/phy1"))
        link(sys.resolve("class/ieee80211/phy0"), "../../devices/virtual/ieee80211/phy0"); link(sys.resolve("class/ieee80211/phy1"), "../../devices/virtual/ieee80211/phy1")
        link(net.resolve("phy80211"), "../../ieee80211/phy0")
        write(sys.resolve("class/net/enp5s0/address"), "02:00:00:00:00:99")
        val usb = sys.resolve("devices/pci0000:00/0000:00:14.0/usb1/1-2")
        write(usb.resolve("idVendor"), "13d3"); write(usb.resolve("idProduct"), "3585"); write(usb.resolve("product"), "Wireless_Device"); write(usb.resolve("manufacturer"), "Example Maker")
        Files.createDirectories(sys.resolve("bus/usb/drivers/btusb")); Files.createDirectories(usb.resolve("1-2:1.0/bluetooth/hci0"))
        link(usb.resolve("1-2:1.0/driver"), "../../../../../../bus/usb/drivers/btusb"); link(usb.resolve("1-2:1.0/subsystem"), "../../../../../../bus/usb")
        link(usb.resolve("1-2:1.0/bluetooth/hci0/device"), usb.resolve("1-2:1.0").toString())
        link(sys.resolve("class/bluetooth/hci0"), "../../devices/pci0000:00/0000:00:14.0/usb1/1-2/1-2:1.0/bluetooth/hci0")
        Files.createDirectories(sys.resolve("class/bluetooth/hci0:256"))
        for ((n, entry) in listOf(Triple("phy0", "wlan", "0"), Triple("hci0", "bluetooth", "1")).withIndex()) {
            val d = sys.resolve("class/rfkill/rfkill$n")
            write(d.resolve("name"), entry.first); write(d.resolve("type"), entry.second); write(d.resolve("soft"), entry.third); write(d.resolve("hard"), "0")
        }
        write(root.resolve("proc/net/wireless"), "Inter-| sta-|   Quality\n face | tus | link level noise\nwlp3s0: 0000   59.  -51.  -256 0 0 0 0 0 0")
    }

    @Test fun theRealProbeScriptRunsUnderShAgainstAConstructedTree() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"), "needs a Linux shell")
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "no /bin/sh")
        val root = Files.createTempDirectory("ring-radio-tree")
        try {
            constructedTree(root)
            val builder = ProcessBuilder(guestRadioProbeCommand()).redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.environment()["RINGMASTER_SYS"] = root.resolve("sys").toString(); builder.environment()["RINGMASTER_PROC"] = root.resolve("proc").toString()
            val process = builder.start()
            val out = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
            assertTrue(process.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, process.exitValue())
            assertTrue(out.length < 20_000, "the output is bounded")
            val probe = parseGuestRadioProbe(out, BtSource.Local, Instant.EPOCH)
            assertTrue(probe.complete)
            val wifi = probe.wifi.first { it.id == "wlp3s0" }
            assertEquals("02:00:00:00:00:01", wifi.address); assertEquals("phy0", wifi.phy); assertEquals(RadioBlock.None, wifi.block); assertEquals(true, wifi.powered)
            assertEquals(AdapterHardware(driver = "iwlwifi", bus = "pci", pciAddress = "0000:03:00.0", pciVendorId = 0x8086, pciDeviceId = 0x2725), wifi.hardware)
            assertFalse(probe.wifi.first { it.id == "phy1" }.hasInterface)
            val bt = probe.bluetooth.single()
            assertEquals("hci0", bt.id); assertEquals("btusb", bt.hardware.driver); assertEquals("usb", bt.hardware.bus); assertEquals(0x13d3, bt.hardware.usbVendorId); assertEquals(0x3585, bt.hardware.usbProductId)
            assertEquals(RadioBlock.Software, bt.block); assertEquals(false, bt.powered); assertEquals("Example Maker", bt.hardware.maker)
            assertTrue(probe.radios.none { it.id == "hci0:256" || it.id == "enp5s0" })
        } finally { root.toFile().deleteRecursively() }
    }
}
