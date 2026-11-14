package local.ringleader.core

import java.io.IOException
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

/**
 * Guest one-shot. The whole script is under `timeout` when the guest has it, so a hung bluetoothctl
 * cannot outlive the scan. `list` and `show` each get 3 seconds. Discovery runs on the adapter that
 * reported `Powered: yes`, in one bluetoothctl session (`select`, then `scan on`).
 */
fun bluezScanScript(seconds: Int): String {
    val timeout = seconds.coerceIn(3, 30)
    val budget = timeout + 8
    val quoted = shellSingleQuote(bluezInner(timeout, live = false))
    return """
        if command -v timeout >/dev/null 2>&1; then
            timeout -k 2 $budget sh -c $quoted
        else
            sh -c $quoted
        fi
    """.trimIndent()
}

/** Shares exactly the same shell program between local and guest-agent execution. */
fun bluezScanCommand(seconds: Int): List<String> = listOf("sh", "-c", bluezScanScript(seconds))

/**
 * Local live scan. Same early markers as [bluezScanScript], then bluetoothctl's own lines (stdout kept, line-buffered
 * when stdbuf exists), then the same busctl snapshot. Guest scans keep [bluezScanScript].
 */
fun bluezLiveScript(seconds: Int): String = bluezInner(seconds.coerceIn(3, 30), live = true)

/**
 * Shared shell. [live] keeps bluetoothctl's lines; the guest script throws them away.
 * A TERM (from `timeout`) prints [BT_SCAN_ENDED_EARLY]'s marker and exits.
 */
private fun bluezInner(timeout: Int, live: Boolean): String {
    val discovery = if (live) """
                printf '%s\n' 'RINGMASTER-BLUEZ:LIVE'
                {
                    printf 'select %s\nscan on\n' "${'$'}powered"
                    sleep $timeout
                    printf 'quit\n'
                } | scan_with
    """ else """
                {
                    printf 'select %s\nscan on\n' "${'$'}powered"
                    sleep $timeout
                    printf 'quit\n'
                } | bluetoothctl --timeout $timeout >/dev/null 2>&1
    """
    val scanWith = if (!live) "" else """
        scan_with() {
            if command -v stdbuf >/dev/null 2>&1; then
                stdbuf -oL bluetoothctl --timeout $timeout
            else
                bluetoothctl --timeout $timeout | cat
            fi
        }
    """
    return """
        trap 'printf "%s\n" "RINGMASTER-BLUEZ:EARLY"; exit 0' TERM INT
        if ! command -v bluetoothctl >/dev/null 2>&1 || ! command -v busctl >/dev/null 2>&1; then
            printf '%s\n' 'RINGMASTER-BLUEZ:MISSING'
            exit 0
        fi
        if [ -z "${'$'}(ls -A /sys/class/bluetooth 2>/dev/null)" ]; then
            printf '%s\n' 'RINGMASTER-BLUEZ:NOADAPTER'
            exit 0
        fi
        bt_limited() {
            if command -v timeout >/dev/null 2>&1; then
                timeout 3 "${'$'}@"
            else
                "${'$'}@"
            fi
        }
        $scanWith
        powered=
        for adapter in ${'$'}(bt_limited bluetoothctl list 2>/dev/null | awk '/^Controller / {print ${'$'}2}'); do
            if bt_limited bluetoothctl show "${'$'}adapter" 2>/dev/null | grep -q 'Powered: yes'; then
                powered=${'$'}adapter
                break
            fi
        done
        if [ -n "${'$'}powered" ]; then
            $discovery
        fi
        if objects=${'$'}(busctl --json=short call org.bluez / org.freedesktop.DBus.ObjectManager GetManagedObjects 2>/dev/null); then
            printf '%s\n' 'RINGMASTER-BLUEZ:OBJECTS' "${'$'}objects"
        else
            printf '%s\n' 'RINGMASTER-BLUEZ:NODAEMON'
        fi
    """.trimIndent()
}

private fun shellSingleQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

/** Converts the script protocol to a scan, keeping expected unavailable states as notes. */
fun parseBluezScanOutput(stdout: String, source: BtSource, at: Instant): BtScan {
    val lines = stdout.lineSequence().map { it.trim() }.toList()
    val marker = lines.indexOfFirst { it.startsWith("RINGMASTER-BLUEZ:") }
    val note = when (lines.getOrNull(marker)) {
        "RINGMASTER-BLUEZ:MISSING" -> BLUEZ_MISSING_NOTE
        "RINGMASTER-BLUEZ:NODAEMON" -> BLUEZ_NODAEMON_NOTE
        // No /sys/class/bluetooth entries: the kernel sees no controller, so bluetoothd does not start either.
        "RINGMASTER-BLUEZ:NOADAPTER" -> BT_NO_ADAPTER
        "RINGMASTER-BLUEZ:EARLY" -> BT_SCAN_ENDED_EARLY
        "RINGMASTER-BLUEZ:OBJECTS" -> null
        else -> throw IllegalStateException("Invalid BlueZ scan output.")
    }
    if (note != null) return BtScan(source, emptyList(), emptyList(), at, note)
    val (adapters, devices) = parseBluezObjects(lines.drop(marker + 1).joinToString("\n"))
    return BtScan(source, adapters, devices, at, when {
        adapters.isEmpty() -> BT_NO_ADAPTER
        adapters.none { it.powered == true } -> BT_ADAPTER_OFF
        else -> null
    })
}

/** Reads only adapter/device interfaces; unfamiliar variants remain unavailable fields. */
fun parseBluezObjects(json: String): Pair<List<BtAdapter>, List<BtDevice>> {
    try {
        val root = Json.parseToJsonElement(json).jsonObject
        val objects = (root["data"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw IllegalStateException("Invalid BlueZ objects.")
        val adapters = mutableListOf<BtAdapter>()
        val devices = mutableListOf<BtDevice>()
        for ((path, value) in objects) {
            val interfaces = value as? JsonObject ?: continue
            (interfaces["org.bluez.Adapter1"] as? JsonObject)?.let { p ->
                val id = path.substringAfterLast('/')
                adapters += BtAdapter(id, p.string("Alias") ?: p.string("Name") ?: id,
                    normalizeBtAddress(p.string("Address")), p.boolean("Powered"), p.boolean("Discovering"))
            }
            (interfaces["org.bluez.Device1"] as? JsonObject)?.let { p ->
                val address = normalizeBtAddress(p.string("Address")) ?: return@let
                val alias = p.string("Alias")?.takeUnless { normalizeBtAddress(it) == address }
                devices += BtDevice(address, p.string("Name") ?: alias, p.primitive("RSSI")?.intOrNull,
                    p.boolean("Paired"), p.boolean("Connected"), when {
                        "Class" in p -> BtKind.Classic
                        "AddressType" in p -> BtKind.LowEnergy
                        else -> BtKind.Unknown
                    }, p.string("Icon"), p.string("Adapter")?.substringAfterLast('/'))
            }
        }
        return adapters to devices
    } catch (e: Exception) {
        throw IllegalStateException("Invalid BlueZ objects.", e)
    }
}

private fun JsonObject.primitive(key: String): JsonPrimitive? =
    (get(key) as? JsonObject)?.get("data") as? JsonPrimitive
private fun JsonObject.string(key: String): String? = primitive(key)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.boolean(key: String): Boolean? = primitive(key)?.booleanOrNull

/**
 * Executes the common BlueZ protocol locally. [run] stays last so `BluezScanner { command, timeout -> }` still injects
 * the one-shot runner. [control] is first so the default stream can kill that scan's process tree.
 */
class BluezScanner(
    private val control: BtScanControl = BtScanControl(),
    private val stream: (List<String>, Long, (String) -> Unit) -> Unit = { command, timeout, onLine -> streamProcess(command, timeout, onLine, control) },
    private val clock: () -> Long = System::nanoTime,
    private val now: () -> Instant = Instant::now,
    private val run: (List<String>, Long) -> String = defaultRunProcess,
) : BluetoothScanner {
    override val platform = "Linux · BlueZ"
    override fun cancel() = control.cancel()

    override fun scan(seconds: Int): BtScan = scan(seconds) {}

    override fun scan(seconds: Int, onUpdate: (BtScan) -> Unit): BtScan = control.exclusive {
        val trace = Trace.begin(TraceSource.Bluetooth, "BlueZ scan · sh helper", emptyMap())
        try {
        // Existing tests inject only the one-shot runner.
        if (run !== defaultRunProcess) {
            val result = parseBluezScanOutput(run(bluezScanCommand(seconds), bluezBudget(seconds)), BtSource.Local, now())
            onUpdate(result)
            return@exclusive result
        }
        val bounded = seconds.coerceIn(3, 30)
        val started = clock()
        val parser = BluezLiveParser(BtSource.Local, bounded * 1000L, now())
        val gate = LiveEmitGate()
        control.onIdle {
            val elapsed = millisSince(started)
            if (gate.onIdle(elapsed)) onUpdate(parser.partial(elapsed))
        }
        stream(listOf("sh", "-c", bluezLiveScript(seconds)), bluezBudget(seconds)) { line ->
            val elapsed = millisSince(started)
            if (parser.accept(line, elapsed) && gate.onChange(elapsed)) onUpdate(parser.partial(elapsed))
        }
        val elapsed = millisSince(started)
        if (gate.flush(elapsed)) onUpdate(parser.partial(elapsed))
        val finished = try {
            parser.finish(elapsed)
        } catch (e: IllegalStateException) {
            if (control.wasCancelled()) stoppedScan(elapsed, bounded * 1000L).also(onUpdate)
            else if (control.didTimeOut()) throw IllegalStateException("Bluetooth scan timed out.")
            else throw e
        }
        val result = if ((control.wasCancelled() || control.didTimeOut()) && finished.note == null) {
            finished.copy(note = BT_SCAN_ENDED_EARLY)
        } else finished
        onUpdate(result)
        result
        } catch (t: Throwable) { trace.end(error=t); throw t } finally { trace.end() }
    }

    private fun stoppedScan(elapsedMs: Long, windowMs: Long) =
        BtScan(BtSource.Local, emptyList(), emptyList(), now(), BT_SCAN_ENDED_EARLY, windowMs, elapsedMs, finished = true)

    private fun millisSince(started: Long): Long = (clock() - started).coerceAtLeast(0L) / 1_000_000L
}

/**
 * Incremental reader for [bluezLiveScript]. [accept] is true when the partial snapshot changed.
 * [finish] folds in the busctl dump without dropping live sightings.
 */
class BluezLiveParser(source: BtSource, windowMs: Long, at: Instant = Instant.now()) {
    private val accumulated = BtLiveScan(source, windowMs, at)
    private val objects = StringBuilder()
    private var phase = Phase.Markers
    private var sawMarker = false

    fun accept(line: String, ms: Long): Boolean {
        if (phase == Phase.Objects) {
            val clean = line.replace(bluezAnsi, "").replace("\r", "").trim()
            if (clean.isNotEmpty()) objects.append(clean).append('\n')
            return false
        }
        val clean = stripBluezNoise(line)
        if (clean.isEmpty()) return false
        val before = fingerprint()
        when (clean) {
            "RINGMASTER-BLUEZ:MISSING" -> { sawMarker = true; accumulated.note = BLUEZ_MISSING_NOTE }
            "RINGMASTER-BLUEZ:NODAEMON" -> { sawMarker = true; accumulated.note = BLUEZ_NODAEMON_NOTE }
            "RINGMASTER-BLUEZ:NOADAPTER" -> { sawMarker = true; accumulated.note = BT_NO_ADAPTER }
            "RINGMASTER-BLUEZ:EARLY" -> { sawMarker = true; accumulated.note = BT_SCAN_ENDED_EARLY }
            "RINGMASTER-BLUEZ:LIVE" -> { sawMarker = true; phase = Phase.Live }
            "RINGMASTER-BLUEZ:OBJECTS" -> { sawMarker = true; phase = Phase.Objects }
            else -> if (phase == Phase.Live) readLiveLine(clean, ms)
        }
        return fingerprint() != before
    }

    fun partial(elapsedMs: Long): BtScan = accumulated.snapshot(elapsedMs, finished = false)

    fun finish(elapsedMs: Long): BtScan {
        if (!sawMarker) throw IllegalStateException("Invalid BlueZ scan output.")
        val partial = accumulated.snapshot(elapsedMs, finished = true)
        // LIVE without the busctl dump means bluetoothctl ended before the script finished.
        if (phase != Phase.Objects) return if (partial.note != null) partial else partial.copy(note = BT_SCAN_ENDED_EARLY)
        val (adapters, busDevices) = parseBluezObjects(objects.toString())
        val note = when {
            adapters.isEmpty() -> BT_NO_ADAPTER
            adapters.none { it.powered == true } -> BT_ADAPTER_OFF
            else -> null
        }
        return partial.copy(adapters = adapters, devices = mergeDevices(partial.devices, busDevices, elapsedMs), note = note)
    }

    private fun readLiveLine(clean: String, ms: Long) {
        val match = bluezDeviceLine.matchEntire(clean) ?: return
        val event = match.groupValues[1]
        if (event == "DEL" || match.groupValues[2] != "Device") return
        val address = normalizeBtAddress(match.groupValues[3]) ?: return
        val rest = match.groupValues[4]
        if (event == "NEW") {
            accumulated.sight(BtDevice(address, name = bluezDisplayName(rest, address)), ms)
            return
        }
        val property = bluezProperty.matchEntire(rest) ?: return
        val value = property.groupValues[2].trim()
        val update = when (property.groupValues[1]) {
            "RSSI" -> BtDevice(address, rssi = parseBluezRssi(value))
            "Name", "Alias" -> BtDevice(address, name = bluezDisplayName(value, address))
            "Connected" -> BtDevice(address, connected = parseYesNo(value))
            "Paired" -> BtDevice(address, paired = parseYesNo(value))
            "Icon" -> BtDevice(address, category = value.takeIf { it.isNotBlank() })
            else -> return
        }
        accumulated.sight(update, ms)
    }

    private fun fingerprint(): List<Any?> {
        val shot = accumulated.snapshot(0, false)
        return listOf(shot.note, shot.adapters, shot.devices)
    }

    private enum class Phase { Markers, Live, Objects }
}

internal fun runProcess(command: List<String>, timeoutSeconds: Long): String {
    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
    try {
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            throw IllegalStateException("Bluetooth scan timed out.")
        }
        return output.get(timeoutSeconds, TimeUnit.SECONDS)
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}

/**
 * Reads stdout as it arrives. A timeout or [control] cancel kills the process tree.
 * While the pipe is quiet, [BtScanControl.idle] runs so the caller can flush a trailing update.
 * Stderr is discarded. Stdin stays a pipe and is closed when the scan stops.
 */
internal fun streamProcess(command: List<String>, timeoutSeconds: Long, onLine: (String) -> Unit, control: BtScanControl = BtScanControl()) {
    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
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
    if (timedOut.get() && !any && !control.wasCancelled()) throw IllegalStateException("Bluetooth scan timed out.")
}

private const val BLUEZ_MISSING_NOTE = "BlueZ tools are not installed (install the bluez package)."
private const val BLUEZ_NODAEMON_NOTE = "The Bluetooth service (bluetoothd) is not running."

private val defaultRunProcess: (List<String>, Long) -> String = ::runProcess

private fun bluezBudget(seconds: Int): Long = seconds.coerceIn(3, 30).toLong() + 20

private val bluezAnsi = Regex("""\u001B\[[0-9;?]*[A-Za-z]""")
private val bluezPrompt = Regex("""\[bluetooth(?:ctl)?]#\s*""")
private val bluezDeviceLine = Regex("""^\[(NEW|CHG|DEL)]\s+(Device|Controller)\s+(\S+)\s*(.*)$""")
private val bluezProperty = Regex("""^([A-Za-z][A-Za-z0-9]*):\s*(.*)$""")
private val bluezRssiParen = Regex("""\((-?\d+)\)""")

private fun stripBluezNoise(line: String): String =
    bluezPrompt.replace(bluezAnsi.replace(line, "").replace("\r", ""), "").trim()

/** A name that is only the address (colon or dash form) is unnamed. */
private fun bluezDisplayName(raw: String?, address: String): String? {
    val name = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return name.takeUnless { normalizeBtAddress(it) == address }
}

private fun parseYesNo(value: String): Boolean? = when (value.trim().lowercase()) {
    "yes", "true", "on" -> true
    "no", "false", "off" -> false
    else -> null
}

/** Signed dBm, the parenthetical in `0xffffffbd (-67)`, or a 32-bit hex word. */
private fun parseBluezRssi(value: String): Int? {
    val text = value.trim()
    bluezRssiParen.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    text.toIntOrNull()?.let { return it }
    val hex = text.substringBefore(' ').removePrefix("0x").removePrefix("0X")
    if (!hex.matches(Regex("[0-9A-Fa-f]{3,8}"))) return null
    return (hex.toLong(16) and 0xFFFF_FFFFL).toInt()
}

private fun mergeDevices(liveDevices: List<BtDevice>, busDevices: List<BtDevice>, elapsedMs: Long): List<BtDevice> {
    val ordered = LinkedHashMap<String, BtDevice>()
    for (device in liveDevices) ordered[device.address] = device
    for (bus in busDevices) {
        val previous = ordered[bus.address]
        ordered[bus.address] = if (previous == null) heardOnlyAtEnd(bus, elapsedMs) else mergeAuthoritative(previous, bus)
    }
    return ordered.values.toList()
}

/** Busctl is authoritative for identity fields. Live first/last seen and samples stay. */
private fun mergeAuthoritative(live: BtDevice, bus: BtDevice): BtDevice = live.copy(
    name = bus.name?.takeIf { it.isNotBlank() } ?: live.name,
    rssi = bus.rssi?.takeIf { it < 0 } ?: live.rssi,
    paired = bus.paired ?: live.paired,
    connected = bus.connected ?: live.connected,
    kind = if (bus.kind != BtKind.Unknown) bus.kind else live.kind,
    category = bus.category ?: live.category,
    adapterId = bus.adapterId ?: live.adapterId,
)

private fun heardOnlyAtEnd(bus: BtDevice, elapsedMs: Long): BtDevice {
    val rssi = bus.rssi?.takeIf { it < 0 }
        ?: return bus.copy(samples = emptyList(), firstSeenMs = null, lastSeenMs = null)
    return bus.copy(rssi = rssi, smoothedRssi = rssi.toFloat(), firstSeenMs = elapsedMs, lastSeenMs = elapsedMs, samples = listOf(BtSample(elapsedMs, rssi)))
}
