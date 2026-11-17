package local.ringleader.desktop

import local.ringleader.core.BtSource
import local.ringleader.core.LinuxWifiScanner
import local.ringleader.core.LiveEmitGate
import local.ringleader.core.Trace
import local.ringleader.core.TraceSource
import local.ringleader.core.WifiIssue
import local.ringleader.core.WifiScan
import local.ringleader.core.WifiScanControl
import local.ringleader.core.WifiScanner
import local.ringleader.core.WindowsWifiLiveParser
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val wifiOsName = System.getProperty("os.name").lowercase()

/**
 * The Wi-Fi scanner for the computer RingMASTER runs on: Windows (the bundled PowerShell script, WinRT and Native Wifi) or
 * Linux (NetworkManager, then iw). Returns null on platforms without a backend; the page then says scanning is unavailable here.
 */
fun localWifiScanner(): WifiScanner? = when {
    wifiOsName.startsWith("windows") -> WindowsWifiScanner()
    wifiOsName.contains("linux") -> LinuxWifiScanner()
    else -> null
}

/** The system page that fixes [issue], or null where this app should not launch one (Linux) or no page helps. */
internal fun wifiSettingsCommand(issue: WifiIssue?, windows: Boolean = wifiOsName.startsWith("windows")): List<String>? {
    if (!windows) return null
    val page = when (issue) {
        WifiIssue.LocationOff -> "ms-settings:privacy-location"
        WifiIssue.AirplaneMode -> "ms-settings:network-airplanemode"
        WifiIssue.AdapterOff, WifiIssue.HardwareSwitch -> "ms-settings:network-wifi"
        else -> return null
    }
    return listOf("cmd.exe", "/c", "start", "", page)
}

/** Opens the system page that fixes [issue] on Windows. Does nothing elsewhere. Never changes a setting itself. */
internal fun openWifiSettings(issue: WifiIssue?): Boolean {
    val command = wifiSettingsCommand(issue) ?: return false
    return runCatching {
        ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        true
    }.getOrDefault(false)
}

/**
 * The scan script with the window filled in. Comment lines, blank lines and indentation are dropped so the base64 text
 * stays well under the 32,767-character limit on a Windows command line.
 */
internal fun windowsWifiScript(seconds: Int): String {
    val raw = WindowsWifiScanner::class.java.getResourceAsStream("/wifi/wifi-scan.ps1")!!.use { it.readBytes().decodeToString() }
    return raw.replace("\r\n", "\n").lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.joinToString("\n")
        .replace("__SECONDS__", seconds.coerceIn(5, 60).toString())
}

private fun powershellExe(): String = (System.getenv("SystemRoot") ?: System.getenv("WINDIR"))
    ?.let { File(it, "System32\\WindowsPowerShell\\v1.0\\powershell.exe") }?.takeIf { it.isFile }?.path ?: "powershell.exe"

internal fun windowsWifiCommand(seconds: Int): List<String> = listOf(powershellExe(), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand",
    Base64.getEncoder().encodeToString(windowsWifiScript(seconds).toByteArray(Charsets.UTF_16LE)))

/**
 * Windows backend: `wifi/wifi-scan.ps1`. It lists the adapters (with their radio state and PCI or USB ids), the current link of
 * each, and, once Windows grants Location access, the networks in range, repeating scans for the window. While Location is off
 * it answers with a LOCATION_OFF note and what Windows still allows (radio state, channel, signal): never an empty list that
 * looks like "no networks". It never connects, disconnects or reads a saved profile or password.
 * [stream] runs a command and hands each stdout line to a callback; tests inject it.
 */
class WindowsWifiScanner(
    private val stream: ((List<String>, Long, (String) -> Unit) -> Unit)? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val control: WifiScanControl = WifiScanControl(),
) : WifiScanner {
    override val platform = "Windows · WinRT and Native Wifi"
    override fun cancel() = control.cancel()
    override fun scan(seconds: Int): WifiScan = scan(seconds) {}

    private val pump: (List<String>, Long, (String) -> Unit) -> Unit = stream ?: { command, timeout, onLine -> streamWifiProcess(command, timeout, onLine, control) }

    override fun scan(seconds: Int, onUpdate: (WifiScan) -> Unit): WifiScan = control.exclusive {
        val window = seconds.coerceIn(5, 60)
        val parser = WindowsWifiLiveParser(window * 1000L, Instant.now())
        val trace = Trace.begin(TraceSource.Tools, "Wi-Fi scan · PowerShell", emptyMap())
        try {
            val started = clock()
            val gate = LiveEmitGate()
            pump(windowsWifiCommand(window), window + 45L) { line ->
                if (parser.accept(line)) {
                    val now = clock() - started
                    if (!parser.done && gate.onChange(now)) onUpdate(parser.snapshot(maxOf(parser.lastMs, now), finished = false))
                }
            }
            val elapsed = maxOf(parser.lastMs, clock() - started)
            var result = parser.snapshot(elapsed, finished = true)
            if (control.wasCancelled() || !parser.done) {
                if (result.issue == null && result.adapters.isEmpty() && result.networks.isEmpty() && !control.wasCancelled()) error("The Windows Wi-Fi scan returned unreadable output.")
                if (result.issue == null) result = result.copy(issue = WifiIssue.EndedEarly, note = WifiIssue.EndedEarly.sentence(BtSource.Local))
            }
            onUpdate(result)
            result
        } catch (t: Throwable) { trace.end(error = t); throw t } finally { trace.end() }
    }
}

/**
 * Runs a scan helper and hands each stdout line to [onLine] as it arrives. [control] kills the process tree. On a start
 * failure the first stderr line becomes the message.
 */
internal fun streamWifiProcess(command: List<String>, timeoutSeconds: Long, onLine: (String) -> Unit, control: WifiScanControl) {
    val process = ProcessBuilder(command).redirectInput(File("NUL")).start()
    val stderr = StringBuffer()
    Thread({
        runCatching { process.errorStream.bufferedReader().useLines { it.forEach { line -> if (stderr.length < 4000) stderr.append(line).append('\n') } } }
    }, "wifi-scan-stderr").apply { isDaemon = true; start() }
    val timedOut = AtomicBoolean(false)
    val lines = ArrayBlockingQueue<String>(4096)
    val doneReading = AtomicBoolean(false)
    val reader = Thread({
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { lines.put(it) }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        } finally {
            doneReading.set(true)
        }
    }, "wifi-scan-stdout")
    reader.isDaemon = true
    reader.start()
    val kill = {
        process.descendants().forEach { runCatching { it.destroyForcibly() } }
        if (process.isAlive) process.destroyForcibly()
    }
    control.bind(kill)
    if (control.wasCancelled()) kill()
    val waiter = Thread({
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                timedOut.set(true)
                control.markTimedOut()
                kill()
            }
        } catch (_: InterruptedException) {
        }
    }, "wifi-scan-timeout")
    waiter.isDaemon = true
    waiter.start()
    var any = false
    try {
        while (true) {
            if (control.wasCancelled()) break
            val line = lines.poll(20, TimeUnit.MILLISECONDS)
            if (line != null) { any = true; onLine(line) }
            else if (doneReading.get() && lines.isEmpty()) break
            else if (timedOut.get() && lines.isEmpty()) break
        }
    } catch (_: InterruptedException) {
        control.cancel()
    } finally {
        kill()
        waiter.join(2_000)
        reader.join(2_000)
    }
    if (control.wasCancelled()) return
    if (!process.waitFor(5, TimeUnit.SECONDS)) error("The Wi-Fi scan timed out.")
    if (timedOut.get() && !any) error("The Wi-Fi scan timed out.")
    if (process.exitValue() != 0 && !any) {
        val reason = stderr.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("At ") && !it.startsWith("+") && !it.startsWith("#<") && !it.startsWith("<Objs") }
        error("The Windows Wi-Fi scan failed" + (reason?.let { ": ${it.take(200)}" } ?: "."))
    }
}
