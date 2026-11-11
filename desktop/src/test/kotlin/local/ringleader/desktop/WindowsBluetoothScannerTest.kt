package local.ringleader.desktop

import local.ringleader.core.BtKind
import java.util.Base64
import kotlin.test.*

class WindowsBluetoothScannerTest {
    private val lines = listOf(
        "#< CLIXML noise",
        """{"t":"adapter","id":"USB\\VID_13D3&PID_3585\\1","name":"PC","address":"60FF9E000001","powered":true}""",
        """{"t":"add","ms":25,"id":"ep-1","name":"Fixture","address":"aa:bb:cc:00:00:01","paired":false,"connected":false,"rssi":-50,"protocol":"{bb7bb05e-5972-42b5-94fc-76eaa7084d49}"}""",
        """{"t":"add","ms":30,"id":"ep-2","name":"Fixture","address":"aa:bb:cc:00:00:01","paired":false,"connected":false,"rssi":0,"protocol":"{e0cbf06c-cd8b-4647-bb8a-263b43f0f974}"}""",
        """{"t":"add","ms":31,"id":"ep-3","name":"Keyboard","address":"aa:bb:cc:00:00:02","paired":true,"connected":true,"rssi":0,"protocol":"{e0cbf06c-cd8b-4647-bb8a-263b43f0f974}","major":5,"minor":16}""",
        """{"t":"upd","ms":900,"id":"ep-1","SignalStrength":-61}""",
        """{"t":"upd","ms":1800,"id":"ep-1","SignalStrength":-55,"IsPresent":true}""",
        """{"t":"upd","ms":1900,"id":"unknown","SignalStrength":-40}""",
        """{"t":"done","ms":12010}""",
    )

    @Test fun streamsTheBundledScriptAndMergesEndpoints() {
        var command: List<String> = emptyList()
        var timeout = 0L
        var now = 0L
        val updates = mutableListOf<local.ringleader.core.BtScan>()
        val scanner = WindowsBluetoothScanner({ c, t, onLine -> command = c; timeout = t; lines.forEach { now += 100; onLine(it) } }, { now }, helper = null)
        val scan = scanner.scan(12) { updates += it }
        assertEquals(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand"), command.dropLast(1))
        val script = String(Base64.getDecoder().decode(command.last()), Charsets.UTF_16LE)
        assertTrue("\$Seconds = 12" in script); assertFalse("__SECONDS__" in script)
        // Read-only: the script must not pair, connect or switch the radio.
        listOf("PairAsync", "ConnectAsync", "SetStateAsync", "RequestAccessAsync").forEach { assertFalse(it in script, it) }
        assertEquals(57L, timeout)
        assertEquals("Windows · WinRT", scanner.platform)
        val fixture = scan.devices.single { it.address == "AA:BB:CC:00:00:01" }
        assertEquals(BtKind.Dual, fixture.kind)
        assertEquals(listOf(25L to -50, 900L to -61, 1800L to -55), fixture.samples.map { it.ms to it.rssi })
        assertEquals(25L, fixture.firstSeenMs); assertEquals(1800L, fixture.lastSeenMs); assertEquals(-55, fixture.rssi)
        val keyboard = scan.devices.single { it.address == "AA:BB:CC:00:00:02" }
        assertNull(keyboard.firstSeenMs, "0 dBm means not heard")
        assertEquals("input-keyboard", keyboard.category)
        assertEquals(0x13D3, scan.adapters.single().usbVendorId)
        assertTrue(scan.finished); assertEquals(12000L, scan.windowMs)
        assertTrue(updates.dropLast(1).all { !it.finished }, "partial snapshots come first")
        assertTrue(updates.size >= 3); assertSame(scan, updates.last())
        // Connected first in the final order.
        assertEquals("AA:BB:CC:00:00:02", scan.devices.first().address)
    }

    @Test fun windowIsClampedAndNotesPassThrough() {
        var script = ""
        val scan = WindowsBluetoothScanner({ c, _, onLine -> script = String(Base64.getDecoder().decode(c.last()), Charsets.UTF_16LE); onLine("""{"t":"note","note":"NO_ADAPTER"}""") }, helper = null).scan(500)
        assertTrue("\$Seconds = 30" in script)
        assertEquals(local.ringleader.core.BT_NO_ADAPTER, scan.note)
    }

    @Test fun unreadableOutputFails() {
        assertFailsWith<IllegalStateException> { WindowsBluetoothScanner({ _, _, onLine -> onLine("garbage") }, helper = null).scan(5) }
    }

    @Test fun nativeHelperIsPreferredWithTheSameProtocol() {
        val exe = java.io.File.createTempFile("ringmaster-bt", ".exe")
        try {
            var command: List<String> = emptyList(); var timeout = 0L
            val scanner = WindowsBluetoothScanner({ c, t, onLine -> command = c; timeout = t; lines.forEach(onLine) }, helper = exe)
            val scan = scanner.scan(12)
            assertEquals(listOf(exe.path, "--seconds", "12"), command)
            assertEquals(27L, timeout)
            assertEquals("Windows · WinRT native", scanner.platform)
            assertEquals(BtKind.Dual, scan.devices.single { it.address == "AA:BB:CC:00:00:01" }.kind)
        } finally { exe.delete() }
    }

    /** Opt-in live scan of the Rust helper: RINGMASTER_LIVE_BT=1 with desktop/resources/windows/ringmaster-bt.exe built. */
    @Test fun liveNativeHelperWhenRequested() {
        val exe = java.io.File("resources/windows/ringmaster-bt.exe")
        if (System.getenv("RINGMASTER_LIVE_BT") != "1" || !exe.isFile) return
        var partial = 0
        val scan = WindowsBluetoothScanner(helper = exe).scan(6) { if (!it.finished) partial++ }
        println("LIVE-NATIVE devices=${scan.devices.size} heard=${scan.devices.count { it.firstSeenMs != null }} samples=${scan.devices.sumOf { it.samples.size }} partialUpdates=$partial adapters=${scan.adapters.size} note=${scan.note}")
        assertTrue(scan.adapters.isNotEmpty() || scan.note != null)
    }

    /** Opt-in live scan on this machine's adapter: RINGMASTER_LIVE_BT=1 gradlew :desktop:test --tests *WindowsBluetoothScannerTest* */
    @Test fun liveScanWhenRequested() {
        if (System.getenv("RINGMASTER_LIVE_BT") != "1" || !System.getProperty("os.name").startsWith("Windows")) return
        var partial = 0
        val scan = WindowsBluetoothScanner(helper = null).scan(6) { if (!it.finished) partial++ }
        println("LIVE adapters=${scan.adapters.map { "${it.name} powered=${it.powered} usb=${it.usbVendorId?.toString(16)}:${it.usbProductId?.toString(16)}" }} devices=${scan.devices.size} " +
            "named=${scan.devices.count { it.name != null }} withSignal=${scan.devices.count { it.rssi != null }} connected=${scan.devices.count { it.connected == true }} note=${scan.note} partialUpdates=$partial heard=${scan.devices.count { it.firstSeenMs != null }} samples=${scan.devices.sumOf { it.samples.size }}")
        assertTrue(scan.note != null || scan.adapters.isNotEmpty())
    }
}
