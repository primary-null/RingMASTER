package local.ringleader.core

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

// What this computer's own network stack says about its adapters, routes and neighbours. Everything here is read without
// administrator rights, through argument lists (never a shell), with a time limit and an output cap, and parsed tolerantly:
// a line that is not understood is skipped, a document that is too large or not what was asked for is an empty answer.
// Nothing here is trusted beyond "this computer says so": the scan planner only uses it to decide what to offer.

/** An address as four decimal numbers: ASCII digits only, no sign, no spaces. Null for anything else. */
fun strictIpv4(text: String): Long? =
    if (STRICT_IPV4.matches(text)) ScanRules.ipv4(text) else null
private val STRICT_IPV4 = Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")

/** A prefix length as one or two ASCII digits, 0 to 32. Null for anything else. */
internal fun strictPrefix(text: String): Int? =
    if (STRICT_PREFIX.matches(text)) text.toInt().takeIf { it in 0..32 } else null
private val STRICT_PREFIX = Regex("[0-9]{1,2}")

/** The dotted text of an address held as a number. */
fun ipv4Text(n: Long): String = (3 downTo 0).joinToString(".") { ((n shr (it * 8)) and 255).toString() }

/** The network mask of [prefix] bits, as a number. */
fun prefixMask(prefix: Int): Long = if (prefix <= 0) 0L else (0xffffffffL shl (32 - prefix)) and 0xffffffffL

/** "a.b.c.d/p" or a bare "a.b.c.d" (a host route) as (network number, prefix). The host bits of a network are cleared. */
fun parseCidr(text: String): Pair<Long, Int>? {
    val trimmed = text.trim()
    val address = strictIpv4(trimmed.substringBefore('/')) ?: return null
    val prefix = if ('/' in trimmed) strictPrefix(trimmed.substringAfter('/')) ?: return null else 32
    return (address and prefixMask(prefix)) to prefix
}

fun cidrText(network: Long, prefix: Int): String = "${ipv4Text(network)}/$prefix"

/** Private space (RFC 1918). Nothing else is swept unless the person typed that exact network. */
internal fun isPrivateIpv4(n: Long): Boolean = n ushr 24 == 10L || n ushr 20 == 0xac1L || n ushr 16 == 0xc0a8L

/** 100.64.0.0/10: carrier-grade NAT, and the space Tailscale and Mullvad use for their own addresses. Never swept. */
internal fun isMeshIpv4(n: Long): Boolean = n ushr 22 == 0x191L

/** What the operating system says about one network adapter. A null field means "not known", never "no". */
data class AdapterFact(
    /** The system's interface number (Windows `ifIndex`). Null on Linux, where adapters are told apart by [name]. */
    val index: Int?,
    /** Windows alias (`Ethernet 2`) or Linux device name (`eth0`). */
    val name: String,
    val description: String? = null,
    /** Windows `Virtual`; on Linux an adapter with no hardware behind it. */
    val virtual: Boolean? = null,
    val hardware: Boolean? = null,
    /** The Windows connection profile's category: `Public`, `Private` or `DomainAuthenticated`. Null on Linux and when not read. */
    val category: String? = null,
) {
    val isPublic: Boolean get() = category.equals("Public", ignoreCase = true)
    val isTailscale: Boolean get() = name.startsWith("tailscale", ignoreCase = true) || description?.contains("Tailscale", ignoreCase = true) == true
}

/** One IPv4 route. [nextHop] is null for an on-link route (the destination is reached directly through the interface). */
data class RouteEntry(
    val network: Long,
    val prefix: Int,
    val nextHop: String? = null,
    val interfaceIndex: Int? = null,
    val interfaceName: String? = null,
    /** The interface's own address, where the system names that instead of the interface (`route print`). */
    val interfaceAddress: String? = null,
    val metric: Int? = null,
) {
    val cidr: String get() = cidrText(network, prefix)
    val isDefault: Boolean get() = prefix == 0
}

/** Adapters and routes as this computer reports them. */
class NetworkFacts(val adapters: List<AdapterFact>, val routes: List<RouteEntry>, val source: String = "", val note: String? = null) {
    /** The adapter behind [card], matched on the system's interface number, else on the device name. Null when it is not listed. */
    fun adapterFor(card: ScanInterface): AdapterFact? =
        adapters.firstOrNull { card.index != null && it.index == card.index }
            ?: adapters.firstOrNull { it.index == null && card.deviceName != null && it.name == card.deviceName }

    /** True when a default route (0.0.0.0/0) leaves through [adapter], or through [card]'s own address. */
    fun ownsDefaultRoute(adapter: AdapterFact?, card: ScanInterface? = null): Boolean = routes.any { r ->
        r.isDefault && ((adapter != null && ((adapter.index != null && r.interfaceIndex == adapter.index) || (adapter.index == null && r.interfaceName == adapter.name))) ||
            (card != null && r.interfaceAddress != null && r.interfaceAddress == card.ip) ||
            (card?.deviceName != null && r.interfaceName == card.deviceName))
    }

    /** True when the route [r] leaves through the Tailscale interface. */
    fun viaTailscale(r: RouteEntry): Boolean = r.interfaceName?.startsWith("tailscale", ignoreCase = true) == true ||
        adapters.any { a -> a.isTailscale && ((a.index != null && r.interfaceIndex == a.index) || (a.index == null && r.interfaceName == a.name)) }

    /** The adapter a route leaves through. */
    fun adapterOf(r: RouteEntry): AdapterFact? = adapters.firstOrNull { a -> (a.index != null && r.interfaceIndex == a.index) || (a.index == null && r.interfaceName != null && r.interfaceName == a.name) }
}

// -------------------------------------------------------------------------------------------------------------------
// Parsers. Hostile or odd input is skipped line by line; the counts are bounded.
// -------------------------------------------------------------------------------------------------------------------

private const val MAX_ADAPTERS = 512
private const val MAX_ROUTES = 4096
private const val MAX_NEIGHBOURS = 16384
private const val MAX_FIELD = 160

private fun clean(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }?.filter { !it.isISOControl() }?.take(MAX_FIELD)?.takeIf { it.isNotEmpty() }

/**
 * Parses the Windows script's output: one line per kind, `A [json]` for the adapters, `R [json]` for the IPv4 routes and
 * `P [json]` for the connection profiles (a single object is read as a list of one). Anything else (a banner, an error line,
 * half a line) is skipped, and so is a record that is not what it should be.
 */
internal fun parseWindowsNetworkFacts(text: String): NetworkFacts {
    val adapters = mutableListOf<AdapterFact>()
    val categories = mutableMapOf<Int, String>()
    val routes = mutableListOf<RouteEntry>()
    for (raw in text.lineSequence().take(MAX_ROUTES + 2 * MAX_ADAPTERS + 16)) {
        val line = raw.trim().trimStart('\uFEFF')
        if (line.length < 4 || line[1] != ' ') continue
        val payload = runCatching { Json.parseToJsonElement(line.substring(2)) }.getOrNull() ?: continue
        val records = when (payload) { is JsonArray -> payload.take(MAX_ROUTES); is JsonObject -> listOf(payload); else -> continue }
        for (record in records) {
            val obj = record as? JsonObject ?: continue
            when (line[0]) {
                'A' -> if (adapters.size < MAX_ADAPTERS) {
                    val index = obj.int("i") ?: continue
                    val name = clean(obj.string("n")) ?: continue
                    adapters += AdapterFact(index, name, clean(obj.string("d")), obj.bool("v"), obj.bool("h"))
                }
                'P' -> obj.int("i")?.let { index -> clean(obj.string("c"))?.let { categories.putIfAbsent(index, it) } }
                'R' -> if (routes.size < MAX_ROUTES) {
                    val (network, prefix) = parseCidr(obj.string("p") ?: continue) ?: continue
                    val hop = obj.string("h")?.trim()?.let { strictIpv4(it) }?.takeIf { it != 0L }?.let { ipv4Text(it) }
                    routes += RouteEntry(network, prefix, hop, obj.int("i"), null, null, obj.int("m"))
                }
            }
        }
    }
    return NetworkFacts(adapters.map { a -> a.index?.let { categories[it] }?.let { a.copy(category = it) } ?: a }, routes, "Windows")
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

/**
 * The PowerShell that the Windows read runs. Fixed text: nothing typed or read from the network ever reaches it. The three reads are
 * queries only (adapters, IPv4 routes, connection profiles) and run side by side in their own runspaces: one after another each
 * loads its own module and the whole took about six seconds on a real computer, side by side about one and a half.
 */
internal fun windowsNetworkFactsScript(): String = psUtf8Prefix() + """
${'$'}ErrorActionPreference = 'SilentlyContinue'
${'$'}ProgressPreference = 'SilentlyContinue'
${'$'}work = @(
  @{ t = 'A'; s = { Get-NetAdapter | Select-Object @{n='i';e={[int]${'$'}_.ifIndex}}, @{n='n';e={[string]${'$'}_.Name}}, @{n='d';e={[string]${'$'}_.InterfaceDescription}}, @{n='v';e={[bool]${'$'}_.Virtual}}, @{n='h';e={[bool]${'$'}_.HardwareInterface}} } },
  @{ t = 'R'; s = { Get-NetRoute -AddressFamily IPv4 | Select-Object @{n='p';e={[string]${'$'}_.DestinationPrefix}}, @{n='h';e={[string]${'$'}_.NextHop}}, @{n='i';e={[int]${'$'}_.InterfaceIndex}}, @{n='m';e={[int]${'$'}_.RouteMetric}} } },
  @{ t = 'P'; s = { Get-NetConnectionProfile | Select-Object @{n='i';e={[int]${'$'}_.InterfaceIndex}}, @{n='c';e={[string]${'$'}_.NetworkCategory}} } }
)
${'$'}jobs = foreach (${'$'}w in ${'$'}work) { ${'$'}ps = [powershell]::Create(); [void]${'$'}ps.AddScript(${'$'}w.s.ToString()); [pscustomobject]@{ t = ${'$'}w.t; ps = ${'$'}ps; h = ${'$'}ps.BeginInvoke() } }
foreach (${'$'}j in ${'$'}jobs) {
  ${'$'}rows = @(); try { ${'$'}rows = @(${'$'}j.ps.EndInvoke(${'$'}j.h)) } catch {}
  ${'$'}j.t + ' ' + (ConvertTo-Json -InputObject ${'$'}rows -Compress); ${'$'}j.ps.Dispose()
}
""".trimIndent()

/**
 * `route print -4` rows: destination, mask, gateway (a word such as `On-link`, translated by the system), the interface's own
 * address and a metric. Only the numbers are read, so the language of the words does not matter. Rows of the persistent-route
 * list have no interface column and are not taken.
 */
internal fun parseWindowsRoutePrint(text: String): List<RouteEntry> {
    val routes = mutableListOf<RouteEntry>()
    for (line in text.lineSequence().take(MAX_ROUTES * 2)) {
        if (routes.size >= MAX_ROUTES) break
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 5) continue
        val network = strictIpv4(parts[0]) ?: continue
        val mask = strictIpv4(parts[1]) ?: continue
        val prefix = (0..32).firstOrNull { prefixMask(it) == mask } ?: continue
        val address = strictIpv4(parts[parts.size - 2]) ?: continue
        val metric = parts.last().takeIf { Regex("[0-9]{1,9}").matches(it) }?.toInt() ?: continue
        val gateway = strictIpv4(parts.subList(2, parts.size - 2).joinToString(" "))?.takeIf { it != 0L }
        routes += RouteEntry(network and prefixMask(prefix), prefix, gateway?.let { ipv4Text(it) }, null, null, ipv4Text(address), metric)
    }
    return routes
}

private val NON_UNICAST = setOf("local", "broadcast", "multicast", "anycast", "unreachable", "blackhole", "prohibit", "throw", "nat")

/** `ip -j -4 route show table all`. Rows of the `local` table and every route type but a normal one are dropped. */
internal fun parseIpRouteJson(text: String): List<RouteEntry>? {
    if (text.length > 4 * 1024 * 1024) return null
    val root = runCatching { Json.parseToJsonElement(text.trim()) as? JsonArray }.getOrNull() ?: return null
    val routes = mutableListOf<RouteEntry>()
    for (element in root) {
        if (routes.size >= MAX_ROUTES) break
        val obj = element as? JsonObject ?: continue
        if ((obj.string("type") ?: "unicast") in NON_UNICAST) continue
        if (obj.string("table") == "local") continue
        val first = (obj["nexthops"] as? JsonArray)?.firstOrNull() as? JsonObject
        val device = clean(obj.string("dev") ?: first?.string("dev"))
        val gateway = (obj.string("gateway") ?: first?.string("gateway"))?.trim()?.let { strictIpv4(it) }?.let { ipv4Text(it) }
        val destination = obj.string("dst")?.trim() ?: continue
        val (network, prefix) = if (destination == "default") 0L to 0 else parseCidr(destination) ?: continue
        routes += RouteEntry(network, prefix, gateway, null, device, null, obj.int("metric"))
    }
    return routes
}

/** The same table in `ip`'s plain text, for an `ip` that does not know `-j`. One route per line; the first word may name a type. */
internal fun parseIpRouteText(text: String): List<RouteEntry> {
    val routes = mutableListOf<RouteEntry>()
    for (line in text.lineSequence().take(MAX_ROUTES * 2)) {
        if (routes.size >= MAX_ROUTES) break
        val words = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) continue
        var at = 0
        if (words[0] in NON_UNICAST) continue
        if (words[0] == "unicast") at = 1
        val destination = words.getOrNull(at) ?: continue
        val (network, prefix) = if (destination == "default") 0L to 0 else parseCidr(destination) ?: continue
        var gateway: String? = null; var device: String? = null; var metric: Int? = null; var table: String? = null
        var i = at + 1
        while (i + 1 < words.size) {
            when (words[i]) {
                "via" -> { gateway = strictIpv4(words[i + 1])?.let { ipv4Text(it) }; i += 2 }
                "dev" -> { device = clean(words[i + 1]); i += 2 }
                "metric" -> { metric = words[i + 1].takeIf { Regex("[0-9]{1,9}").matches(it) }?.toInt(); i += 2 }
                "table" -> { table = words[i + 1]; i += 2 }
                else -> i++
            }
        }
        if (table == "local") continue
        routes += RouteEntry(network, prefix, gateway, null, device, null, metric)
    }
    return routes
}

/** Alive neighbours from `arp -a`: an address with a physical address that is neither empty, broadcast nor multicast. */
internal fun parseArpTable(text: String): Set<String> {
    val alive = linkedSetOf<String>()
    for (line in text.lineSequence().take(MAX_NEIGHBOURS)) {
        if (alive.size >= MAX_NEIGHBOURS) break
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 2) continue
        val address = strictIpv4(parts[0]) ?: continue
        val mac = parts[1].lowercase()
        if (!Regex("[0-9a-f]{2}([-:][0-9a-f]{2}){5}").matches(mac)) continue
        val octets = mac.split('-', ':')
        if (octets.all { it == "00" } || octets.all { it == "ff" } || octets[0] == "01" || (octets[0].toInt(16) and 1) == 1) continue
        alive += ipv4Text(address)
    }
    return alive
}

private val ALIVE_STATES = setOf("REACHABLE", "STALE", "DELAY", "PROBE")

/** Alive neighbours from `ip -j -4 neigh`. The state is a list in current iproute2 and a word in older ones. */
internal fun parseIpNeighJson(text: String): Set<String>? {
    if (text.length > 4 * 1024 * 1024) return null
    val root = runCatching { Json.parseToJsonElement(text.trim()) as? JsonArray }.getOrNull() ?: return null
    val alive = linkedSetOf<String>()
    for (element in root.take(MAX_NEIGHBOURS)) {
        val obj = element as? JsonObject ?: continue
        val address = obj.string("dst")?.trim()?.let { strictIpv4(it) } ?: continue
        if (obj.string("lladdr").isNullOrBlank()) continue
        val states = when (val s = obj["state"]) {
            is JsonArray -> s.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(s.contentOrNull)
            else -> emptyList()
        }
        if (states.any { it.uppercase() in ALIVE_STATES }) alive += ipv4Text(address)
    }
    return alive
}

/** The same from plain `ip -4 neigh show` lines: `192.0.2.1 dev eth0 lladdr aa:bb:cc:dd:ee:ff REACHABLE`. */
internal fun parseIpNeighText(text: String): Set<String> {
    val alive = linkedSetOf<String>()
    for (line in text.lineSequence().take(MAX_NEIGHBOURS)) {
        val words = line.trim().split(Regex("\\s+"))
        val address = words.firstOrNull()?.let { strictIpv4(it) } ?: continue
        if ("lladdr" !in words) continue
        if (words.last().uppercase() in ALIVE_STATES) alive += ipv4Text(address)
    }
    return alive
}

// -------------------------------------------------------------------------------------------------------------------
// Running the system's tools: argument lists, no shell, no working directory, a time limit and an output cap.
// -------------------------------------------------------------------------------------------------------------------

/** What a tool run gave back. */
internal sealed class ToolResult {
    class Text(val text: String) : ToolResult()
    class Failed(val reason: String) : ToolResult()
}

/** Runs [command] (argument list, executable first) for at most [timeoutMs] and returns its standard output. */
internal typealias ToolRunner = suspend (command: List<String>, timeoutMs: Long) -> ToolResult

/** Output beyond this is not read: the process is stopped and the run counts as failed. */
internal const val TOOL_OUTPUT_CAP = 1 shl 20

/**
 * The runner used by the app. Only an absolute path to an existing file runs: a bare name or a relative one would be looked up in
 * the working directory on Windows. Standard input is the null device, the error stream is discarded, `ip` runs in the C locale,
 * the wait is bounded, and cancelling the caller kills the whole process tree.
 */
internal suspend fun runBoundedTool(command: List<String>, timeoutMs: Long, maxBytes: Int = TOOL_OUTPUT_CAP): ToolResult = withContext(Dispatchers.IO) {
    val exe = command.firstOrNull() ?: return@withContext ToolResult.Failed("No program to run.")
    val program = runCatching { Path.of(exe) }.getOrNull()
    if (program == null || !program.isAbsolute || !Files.isRegularFile(program)) return@withContext ToolResult.Failed("${exe.substringAfterLast('\\').substringAfterLast('/')} was not found.")
    if (command.any { arg -> arg.any { it == '\u0000' } }) return@withContext ToolResult.Failed("A command argument is not valid.")
    val trace = Trace.begin(TraceSource.Scan, program.fileName.toString(), Redact.commandDetails(command))
    try {
        val windows = System.getProperty("os.name").startsWith("Windows", true)
        val builder = ProcessBuilder(command).redirectInput(java.io.File(if (windows) "NUL" else "/dev/null")).redirectError(ProcessBuilder.Redirect.DISCARD)
        applyVpnCommandEnvironment(builder, command)
        val process = builder.start()
        try {
            coroutineScope {
                var text: ToolResult? = null
                val reader = launch {
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    val input = process.inputStream
                    var over = false
                    try {
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (out.size() + n > maxBytes) { over = true; break }
                            out.write(buffer, 0, n)
                        }
                    } catch (_: java.io.IOException) { /* the pipe closed under a killed process: what was read is what there is */ }
                    text = if (over) ToolResult.Failed("The answer was too large.") else ToolResult.Text(out.toString(Charsets.UTF_8))
                }
                val end = System.nanoTime() + timeoutMs * 1_000_000
                try {
                    while (reader.isActive) {
                        ensureActive()
                        if (System.nanoTime() > end) { killTree(process); reader.cancel(); trace.end(error = IllegalStateException("timed out")); return@coroutineScope ToolResult.Failed("It did not answer in time.") }
                        kotlinx.coroutines.delay(15)
                    }
                } catch (e: CancellationException) {
                    // The reader is blocked in a read that cancellation cannot interrupt: stopping the process ends it.
                    killTree(process)
                    throw e
                }
                if (text is ToolResult.Failed) { killTree(process); return@coroutineScope text!! }
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                if (process.isAlive) { killTree(process); return@coroutineScope ToolResult.Failed("It did not finish.") }
                if (process.exitValue() != 0) ToolResult.Failed("It stopped with code ${process.exitValue()}.")
                else { trace.endProcess(0); text ?: ToolResult.Failed("No answer.") }
            }
        } catch (e: CancellationException) { killTree(process); throw e }
        finally { if (process.isAlive) killTree(process) }
    } catch (e: CancellationException) { trace.end(error = e); throw e }
    catch (e: Exception) { trace.end(error = e); ToolResult.Failed("It could not be started.") }
    finally { trace.end() }
}

private fun killTree(process: Process) {
    runCatching { process.descendants().forEach { it.destroyForcibly() } }
    runCatching { process.destroyForcibly() }
}

// -------------------------------------------------------------------------------------------------------------------
// The reader: one read of adapters and routes, one of neighbours.
// -------------------------------------------------------------------------------------------------------------------

/** The outcome of reading this computer's network facts: [facts] when there are any, and [problem] in words when something failed. */
class NetworkFactsRead(val facts: NetworkFacts?, val problem: String?)

/**
 * Reads adapters and routes. Windows: one PowerShell start for adapters (with their virtual and hardware flags), IPv4 routes and
 * connection profiles; when that fails, `route print -4` for the routes alone. Linux: `ip -j -4 route show table all`, or the same
 * in plain text for an older `ip`, and the device list of `/sys/class/net` (a `device` entry means real hardware).
 * A failed read is reported and never thrown: the attached networks still work without it.
 */
class LocalNetworkReader internal constructor(
    private val windows: Boolean,
    private val run: ToolRunner,
    private val which: (String) -> String?,
    private val devices: () -> List<Pair<String, Boolean>>,
) {
    constructor() : this(System.getProperty("os.name").startsWith("Windows", true), ::runBoundedTool, ::findExecutable, ::linuxDevices)

    suspend fun read(): NetworkFactsRead = if (windows) readWindows() else readLinux()

    private suspend fun readWindows(): NetworkFactsRead {
        val first = run(vpnPowerShell(windowsNetworkFactsScript()), 20_000)
        if (first is ToolResult.Text) {
            val facts = parseWindowsNetworkFacts(first.text)
            if (facts.routes.isNotEmpty() || facts.adapters.isNotEmpty()) return NetworkFactsRead(facts, null)
        }
        val reason = (first as? ToolResult.Failed)?.reason ?: "It gave no adapters or routes."
        val print = which(windowsSystemRoot() + "\\System32\\route.exe")?.let { run(listOf(it, "print", "-4"), 8_000) }
        if (print is ToolResult.Text) {
            val routes = parseWindowsRoutePrint(print.text)
            if (routes.isNotEmpty()) return NetworkFactsRead(NetworkFacts(emptyList(), routes, "Windows route table", "Adapter details were not available ($reason)"),
                "Adapter details could not be read ($reason). Routes were read from the route table instead.")
        }
        return NetworkFactsRead(null, "This computer's routes could not be read ($reason).")
    }

    private suspend fun readLinux(): NetworkFactsRead {
        val ip = which("ip") ?: return NetworkFactsRead(null, "This computer's routes could not be read (the ip tool was not found).")
        val adapters = devices().map { (name, hardware) -> AdapterFact(null, name, null, !hardware, hardware) }
        val json = run(listOf(ip, "-j", "-4", "route", "show", "table", "all"), 8_000)
        val parsed = (json as? ToolResult.Text)?.let { parseIpRouteJson(it.text) }
        if (parsed != null) return NetworkFactsRead(NetworkFacts(adapters, parsed, "Linux"), null)
        val text = run(listOf(ip, "-4", "route", "show", "table", "all"), 8_000)
        if (text is ToolResult.Text) {
            val routes = parseIpRouteText(text.text)
            if (routes.isNotEmpty()) return NetworkFactsRead(NetworkFacts(adapters, routes, "Linux"), null)
        }
        val reason = (text as? ToolResult.Failed)?.reason ?: (json as? ToolResult.Failed)?.reason ?: "It gave no routes."
        return NetworkFactsRead(null, "This computer's routes could not be read ($reason).")
    }
}

/** The adapters of `/sys/class/net` and whether each has hardware behind it. Empty when the folder cannot be read. */
internal fun linuxDevices(): List<Pair<String, Boolean>> = runCatching {
    Files.list(Path.of("/sys/class/net")).use { stream ->
        stream.limit(512).map { it.fileName.toString() to Files.exists(it.resolve("device")) }.toList()
    }
}.getOrDefault(emptyList())

/**
 * The addresses of neighbours this computer has heard from on its own networks (its ARP table). An address with an entry is alive;
 * an address without one may be dead or may not have been asked yet. Null when the table cannot be read.
 */
fun interface NeighbourSource {
    suspend fun read(): Set<String>?
    companion object {
        /** No neighbour table: the sweep then cannot prune by it. */
        val NONE = NeighbourSource { null }
    }
}

/** `arp -a` on Windows, `ip -j -4 neigh` on Linux. Not elevated, a few milliseconds, one short process. */
class LocalNeighbours internal constructor(
    private val windows: Boolean,
    private val run: ToolRunner,
    private val which: (String) -> String?,
) : NeighbourSource {
    constructor() : this(System.getProperty("os.name").startsWith("Windows", true), ::runBoundedTool, ::findExecutable)

    override suspend fun read(): Set<String>? {
        if (windows) {
            val arp = which(windowsSystemRoot() + "\\System32\\ARP.EXE") ?: return null
            return (run(listOf(arp, "-a"), 5_000) as? ToolResult.Text)?.let { parseArpTable(it.text) }
        }
        val ip = which("ip") ?: return null
        val json = (run(listOf(ip, "-j", "-4", "neigh", "show"), 5_000) as? ToolResult.Text)?.let { parseIpNeighJson(it.text) }
        if (json != null) return json
        return (run(listOf(ip, "-4", "neigh", "show"), 5_000) as? ToolResult.Text)?.let { parseIpNeighText(it.text) }
    }
}
