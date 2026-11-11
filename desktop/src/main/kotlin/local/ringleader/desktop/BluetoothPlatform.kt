package local.ringleader.desktop

import local.ringleader.core.BluetoothScanner
import local.ringleader.core.BtScan
import local.ringleader.core.WinRtLiveParser
import local.ringleader.core.btDeviceOrder
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit

private val osName = System.getProperty("os.name").lowercase()

/**
 * The scanner for the computer RingMASTER runs on: Windows (WinRT through the native helper, or PowerShell when the
 * helper is absent) or Linux (BlueZ).
 * Returns null on platforms without a backend; the Bluetooth page then says scanning is unavailable here.
 */
fun localBluetoothScanner(): BluetoothScanner? = when {
    osName.startsWith("windows") -> WindowsBluetoothScanner()
    osName.contains("linux") -> local.ringleader.core.BluezScanner()
    else -> null
}

/**
 * The native Windows helper shipped in the app resources (`ringmaster-bt.exe`, Rust, native/ringmaster-bt), or null
 * when this build does not include it.
 */
internal fun bundledBluetoothHelper(): File? =
    System.getProperty("compose.application.resources.dir")?.let { File(it, "ringmaster-bt.exe") }?.takeIf { it.isFile }

/**
 * Windows backend. Prefers [helper], the native `ringmaster-bt.exe`, which calls the WinRT DeviceWatcher directly.
 * Without it, runs the bundled `bluetooth/winrt-scan.ps1` in Windows PowerShell 5.1 as -EncodedCommand. Both print the
 * same line-per-event protocol, read by one parser, and neither pairs, connects or toggles the radio.
 */
class WindowsBluetoothScanner(
    private val stream: (List<String>, Long, (String) -> Unit) -> Unit = ::streamScanProcess,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val helper: File? = bundledBluetoothHelper(),
) : BluetoothScanner {
    override val platform = if (helper != null) "Windows · WinRT native" else "Windows · WinRT"
    override fun scan(seconds: Int): BtScan = scan(seconds) {}

    /** Streams the watcher's events: partial snapshots at most every 150 ms while it listens, then the final scan. */
    override fun scan(seconds: Int, onUpdate: (BtScan) -> Unit): BtScan {
        val window = seconds.coerceIn(3, 30)
        val command = if (helper != null) listOf(helper.path, "--seconds", window.toString()) else {
            val template = WindowsBluetoothScanner::class.java.getResourceAsStream("/bluetooth/winrt-scan.ps1")!!.use { it.readBytes().decodeToString() }
            val script = template.replace("__SECONDS__", window.toString())
            listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand",
                Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)))
        }
        val parser = WinRtLiveParser(window * 1000L, Instant.now())
        val started = clock()
        var reported = 0L
        var dirty = false
        stream(command, if (helper != null) window + 15L else window + 45L) { line ->
            if (parser.accept(line)) dirty = true
            val now = clock() - started
            if (dirty && !parser.done && now - reported >= 150) { reported = now; dirty = false; onUpdate(parser.snapshot(parser.lastMs, finished = false)) }
        }
        val scan = parser.snapshot(maxOf(parser.lastMs, window * 1000L), finished = true)
        if (!parser.done && scan.note == null && scan.adapters.isEmpty()) error("The Windows Bluetooth scan returned unreadable output.")
        return scan.copy(devices = scan.devices.sortedWith(btDeviceOrder)).also(onUpdate)
    }
}

/** Runs a scan helper and hands each stdout line to [onLine] as it arrives. On failure the first stderr line becomes the message. */
internal fun streamScanProcess(command: List<String>, timeoutSeconds: Long, onLine: (String) -> Unit) {
    val process = ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(java.io.File(if (osName.startsWith("windows")) "NUL" else "/dev/null"))).start()
    val stderr = StringBuffer()
    Thread({ process.errorStream.bufferedReader().useLines { it.forEach { line -> if (stderr.length < 4000) stderr.append(line).append('\n') } } }, "bt-scan-stderr").apply { isDaemon = true; start() }
    val killer = Thread({ try { if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { process.descendants().forEach { it.destroyForcibly() }; process.destroyForcibly() } } catch (_: InterruptedException) {} }, "bt-scan-timeout").apply { isDaemon = true; start() }
    var any = false
    try {
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach { any = true; onLine(it) } }
        if (!process.waitFor(5, TimeUnit.SECONDS)) error("The Bluetooth scan timed out.")
    } finally {
        killer.interrupt()
        if (process.isAlive) { process.descendants().forEach { it.destroyForcibly() }; process.destroyForcibly() }
    }
    if (process.exitValue() != 0 && !any) {
        val reason = stderr.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("At ") && !it.startsWith("+") && !it.startsWith("#<") && !it.startsWith("<Objs") }
        error("The Bluetooth scan failed" + (reason?.let { ": ${it.take(200)}" } ?: "."))
    }
}
