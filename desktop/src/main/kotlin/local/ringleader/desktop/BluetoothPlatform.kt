package local.ringleader.desktop

import local.ringleader.core.BT_SCAN_ENDED_EARLY
import local.ringleader.core.BluetoothScanner
import local.ringleader.core.BtScan
import local.ringleader.core.BtScanControl
import local.ringleader.core.LiveEmitGate
import local.ringleader.core.WinRtLiveParser
import local.ringleader.core.btDeviceOrder
import java.io.IOException
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val osName = System.getProperty("os.name").lowercase()

/**
 * The scanner for the computer RingMASTER runs on: Windows (WinRT through the native helper, or PowerShell when the
 * helper is absent or fails to start) or Linux (BlueZ).
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
internal fun bundledBluetoothHelper(): java.io.File? =
    System.getProperty("compose.application.resources.dir")?.let { java.io.File(it, "ringmaster-bt.exe") }?.takeIf { it.isFile }

/** Command that opens the system Bluetooth page, or null where this app should not launch one. */
internal fun bluetoothSettingsCommand(): List<String>? = when {
    osName.startsWith("windows") -> listOf("cmd.exe", "/c", "start", "", "ms-settings:bluetooth")
    else -> null
}

/** Opens the system Bluetooth page on Windows. Does nothing on other systems. Never changes the radio itself. */
internal fun openBluetoothSettings(): Boolean {
    val command = bluetoothSettingsCommand() ?: return false
    return runCatching {
        ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        true
    }.getOrDefault(false)
}

/** The helper exited before it reported an adapter or a note. The PowerShell script may still run once. */
internal class HelperStartFailed(message: String) : IllegalStateException(message)

/**
 * Windows backend. Prefers [helper], the native `ringmaster-bt.exe`, which calls the WinRT DeviceWatcher directly.
 * If that helper is missing, or it exits with no adapter and no note, runs `bluetooth/winrt-scan.ps1` once.
 * Both print the same line-per-event protocol. Neither pairs, connects or toggles the radio.
 */
class WindowsBluetoothScanner(
    private val stream: ((List<String>, Long, (String) -> Unit) -> Unit)? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val helper: java.io.File? = bundledBluetoothHelper(),
    private val control: BtScanControl = BtScanControl(),
) : BluetoothScanner {
    override val platform = if (helper != null) "Windows · WinRT native" else "Windows · WinRT"
    override fun cancel() = control.cancel()
    override fun scan(seconds: Int): BtScan = scan(seconds) {}

    private val pump: (List<String>, Long, (String) -> Unit) -> Unit =
        stream ?: { command, timeout, onLine -> streamScanProcess(command, timeout, onLine, control) }

    /** Streams the watcher's events. Partials are at most every 150 ms, with a trailing flush when the pipe goes quiet. */
    override fun scan(seconds: Int, onUpdate: (BtScan) -> Unit): BtScan = control.exclusive {
        val window = seconds.coerceIn(3, 30)
        if (helper != null) {
            try {
                return@exclusive runOnce(listOf(helper.path, "--seconds", window.toString()), window + 15L, true, window, onUpdate)
            } catch (e: HelperStartFailed) {
                if (control.wasCancelled()) throw e
            }
        }
        val template = WindowsBluetoothScanner::class.java.getResourceAsStream("/bluetooth/winrt-scan.ps1")!!.use { it.readBytes().decodeToString() }
        val script = template.replace("__SECONDS__", window.toString())
        val command = listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand",
            Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)))
        runOnce(command, window + 45L, false, window, onUpdate)
    }

    private fun runOnce(command: List<String>, budget: Long, native: Boolean, window: Int, onUpdate: (BtScan) -> Unit): BtScan {
        val label = if (native) "Windows scan · native helper" else "Windows scan · PowerShell"
        val parser = WinRtLiveParser(window * 1000L, Instant.now(), label)
        var failure: Throwable? = null
        try {
            val started = clock()
            val gate = LiveEmitGate()
            control.onIdle {
                val now = clock() - started
                if (!parser.done && gate.onIdle(now)) onUpdate(parser.snapshot(maxOf(parser.lastMs, now), finished = false))
            }
            pump(command, budget) { line ->
                if (parser.accept(line)) {
                    val now = clock() - started
                    if (!parser.done && gate.onChange(now)) onUpdate(parser.snapshot(maxOf(parser.lastMs, now), finished = false))
                }
            }
            val now = clock() - started
            if (!parser.done && gate.flush(now)) onUpdate(parser.snapshot(maxOf(parser.lastMs, now), finished = false))
            val elapsed = maxOf(parser.lastMs, now)
            if (control.wasCancelled()) {
                val stopped = parser.snapshot(elapsed, finished = true).let { if (it.note == null) it.copy(note = BT_SCAN_ENDED_EARLY) else it }
                return stopped.copy(devices = stopped.devices.sortedWith(btDeviceOrder)).also(onUpdate)
            }
            var scan = parser.snapshot(elapsed, finished = true)
            if (!parser.done && scan.note == null && scan.adapters.isEmpty() && scan.devices.isEmpty()) {
                throw HelperStartFailed("The Windows Bluetooth scan returned unreadable output.")
            }
            if (!parser.done && scan.note == null) scan = scan.copy(note = BT_SCAN_ENDED_EARLY)
            return scan.copy(devices = scan.devices.sortedWith(btDeviceOrder)).also(onUpdate)
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            parser.close(failure)
        }
    }
}

/**
 * Runs a scan helper and hands each stdout line to [onLine] as it arrives.
 * Stdin stays open so the native helper can exit when the pipe closes. [control] kills the process tree.
 * On a start failure the first stderr line becomes the message.
 */
internal fun streamScanProcess(command: List<String>, timeoutSeconds: Long, onLine: (String) -> Unit, control: BtScanControl = BtScanControl()) {
    val process = ProcessBuilder(command).start()
    val stderr = StringBuffer()
    Thread({
        runCatching { process.errorStream.bufferedReader().useLines { it.forEach { line -> if (stderr.length < 4000) stderr.append(line).append('\n') } } }
    }, "bt-scan-stderr").apply { isDaemon = true; start() }
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
    }, "bt-scan-stdout")
    reader.isDaemon = true
    reader.start()
    val kill = {
        process.descendants().forEach { runCatching { it.destroyForcibly() } }
        runCatching { process.outputStream.close() }
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
    }, "bt-scan-timeout")
    waiter.isDaemon = true
    waiter.start()
    var any = false
    try {
        while (true) {
            if (control.wasCancelled()) break
            val line = lines.poll(20, TimeUnit.MILLISECONDS)
            if (line != null) { any = true; onLine(line) }
            else if (doneReading.get() && lines.isEmpty()) break
            else {
                control.idle()
                if (timedOut.get() && lines.isEmpty()) break
            }
        }
    } catch (_: InterruptedException) {
        control.cancel()
    } finally {
        kill()
        waiter.join(2_000)
        reader.join(2_000)
    }
    if (control.wasCancelled()) return
    if (!process.waitFor(5, TimeUnit.SECONDS)) error("The Bluetooth scan timed out.")
    if (timedOut.get() && !any) error("The Bluetooth scan timed out.")
    if (process.exitValue() != 0 && !any) {
        val reason = stderr.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("At ") && !it.startsWith("+") && !it.startsWith("#<") && !it.startsWith("<Objs") }
        throw HelperStartFailed("The Bluetooth scan failed" + (reason?.let { ": ${it.take(200)}" } ?: "."))
    }
}
