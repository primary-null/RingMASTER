package local.ringleader.desktop

import local.ringleader.core.BT_ADAPTER_OFF
import local.ringleader.core.BT_SCAN_ENDED_EARLY
import local.ringleader.core.BtKind
import local.ringleader.core.BtScanControl
import local.ringleader.core.Trace
import local.ringleader.core.TraceOutcome
import local.ringleader.core.TraceSource
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
        assertTrue("[Console]::OutputEncoding" in script)
        assertTrue("UTF8Encoding" in script)
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
        val before = Trace.snapshot().maxOfOrNull { it.id } ?: 0L
        assertFailsWith<IllegalStateException> { WindowsBluetoothScanner({ _, _, onLine -> onLine("garbage") }, helper = null).scan(5) }
        val row = Trace.snapshot().filter { it.id > before && it.source == TraceSource.Bluetooth }.single()
        assertEquals("Windows scan · PowerShell", row.summary)
        assertEquals("end", row.kind)
        assertEquals(TraceOutcome.Error, row.outcome)
    }

    @Test fun missingDoneIsAnEarlyEndAndDoesNotStretchTheWindow() {
        val scan = WindowsBluetoothScanner({ _, _, onLine ->
            onLine("""{"t":"adapter","id":"USB\\VID_13D3&PID_3585\\1","name":"PC","address":"60FF9E000001","powered":true}""")
            onLine("""{"t":"add","ms":25,"id":"ep-1","name":"Fixture","address":"aa:bb:cc:00:00:01","rssi":-50,"protocol":"{bb7bb05e-5972-42b5-94fc-76eaa7084d49}"}""")
        }, clock = { 0L }, helper = null).scan(12)
        assertEquals(BT_SCAN_ENDED_EARLY, scan.note)
        assertTrue(scan.finished)
        assertEquals(25L, scan.elapsedMs)
        assertEquals(12_000L, scan.windowMs)
        assertEquals(-50, scan.devices.single().rssi)
    }

    @Test fun anAdapterNoteIsKeptWhenDoneIsMissing() {
        val scan = WindowsBluetoothScanner({ _, _, onLine ->
            onLine("""{"t":"note","note":"ADAPTER_OFF"}""")
        }, helper = null).scan(5)
        assertEquals(BT_ADAPTER_OFF, scan.note)
    }

    @Test fun aHelperThatNeverReportsAnAdapterFallsBackToPowerShellOnce() {
        val exe = java.io.File.createTempFile("ringmaster-bt", ".exe")
        val commands = mutableListOf<String>()
        val overlap = AtomicInteger(0)
        val maxOverlap = AtomicInteger(0)
        val before = Trace.snapshot().maxOfOrNull { it.id } ?: 0L
        try {
            val scanner = WindowsBluetoothScanner({ command, _, onLine ->
                val n = overlap.incrementAndGet()
                maxOverlap.updateAndGet { m -> maxOf(m, n) }
                commands += command.first()
                if (command.first() == exe.path) onLine("""{"t":"hello","v":"0.1.0"}""")
                else lines.forEach(onLine)
                overlap.decrementAndGet()
            }, helper = exe)
            val scan = scanner.scan(12)
            assertEquals(listOf(exe.path, "powershell.exe"), commands)
            assertEquals(1, maxOverlap.get())
            assertTrue(scan.finished)
            assertNull(scan.note)
            assertEquals(BtKind.Dual, scan.devices.single { it.address == "AA:BB:CC:00:00:01" }.kind)
            val rows = Trace.snapshot().filter { it.id > before && it.source == TraceSource.Bluetooth }
            assertEquals(listOf("Windows scan · native helper", "Windows scan · PowerShell"), rows.map { it.summary })
            assertEquals(TraceOutcome.Error, rows[0].outcome)
            assertEquals("end", rows[0].kind)
            assertEquals(TraceOutcome.Ok, rows[1].outcome)
        } finally { exe.delete() }
    }

    @Test fun aQuietPipeFlushesTheTrailingSighting() {
        var now = 0L
        val control = BtScanControl()
        val updates = mutableListOf<local.ringleader.core.BtScan>()
        val scanner = WindowsBluetoothScanner({ _, _, onLine ->
            now = 10
            onLine("""{"t":"adapter","id":"USB\\VID_13D3&PID_3585\\1","name":"PC","powered":true}""")
            now = 20
            onLine("""{"t":"add","ms":20,"id":"ep-1","name":"Fixture","address":"aa:bb:cc:00:00:01","rssi":-50,"protocol":"{bb7bb05e-5972-42b5-94fc-76eaa7084d49}"}""")
            now = 200
            control.idle()
            onLine("""{"t":"done","ms":200}""")
        }, clock = { now }, helper = null, control = control)
        scanner.scan(5) { updates += it }
        val partials = updates.filter { !it.finished }
        assertTrue(partials.size >= 2, "leading and trailing partials, saw ${partials.map { it.elapsedMs }}")
        assertEquals(-50, partials.last().devices.single().rssi)
    }

    @Test fun aSecondScanDoesNotStartBesideARunningOne() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val running = AtomicInteger(0)
        val max = AtomicInteger(0)
        val scanner = WindowsBluetoothScanner({ _, _, _ ->
            val n = running.incrementAndGet()
            max.updateAndGet { m -> kotlin.math.max(m, n) }
            entered.countDown()
            assertTrue(release.await(20, TimeUnit.SECONDS))
            running.decrementAndGet()
        }, helper = null)
        val first = Thread { runCatching { scanner.scan(5) } }
        first.isDaemon = true
        first.start()
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val second = assertFailsWith<IllegalStateException> { scanner.scan(5) }
        assertContains(second.message.orEmpty(), "already running")
        release.countDown()
        first.join(10_000)
        assertEquals(1, max.get())
    }

    @Test fun cancelStopsTheHelperProcess() {
        val control = BtScanControl()
        val started = CountDownLatch(1)
        val thread = Thread {
            streamScanProcess(slowCommand(), 30, { started.countDown() }, control)
        }
        thread.isDaemon = true
        thread.start()
        assertTrue(started.await(15, TimeUnit.SECONDS))
        control.cancel()
        thread.join(10_000)
        assertFalse(thread.isAlive, "cancel left the helper process running")
    }

    @Test fun windowsSettingsCommandDoesNotChangeTheRadio() {
        val command = bluetoothSettingsCommand()
        if (System.getProperty("os.name").startsWith("Windows")) {
            assertEquals(listOf("cmd.exe", "/c", "start", "", "ms-settings:bluetooth"), command)
        } else {
            assertNull(command)
        }
    }

    private fun slowCommand(): List<String> =
        if (System.getProperty("os.name").startsWith("Windows")) listOf("cmd.exe", "/c", "echo started & ping -n 30 127.0.0.1 >nul")
        else listOf("sh", "-c", "echo started; sleep 30")

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
