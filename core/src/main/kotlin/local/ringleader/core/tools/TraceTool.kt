package local.ringleader.core.tools

import local.ringleader.core.findExecutable
import kotlinx.coroutines.CancellationException

class TraceroutePlugin(
    private val os: String = System.getProperty("os.name") ?: "",
    private val which: (String) -> String? = ::findExecutable,
) : ToolPlugin {
    override val id = "traceroute"
    override val name = "Traceroute"
    override val summary = "Each hop from this computer to a host."
    override val inputs = listOf(
        ToolInput("host", "Host", ToolInputKind.Host, "127.0.0.1", "Hostname or IP address"),
        ToolInput("timeout", "Timeout (ms)", ToolInputKind.Number, "1000", "How long to wait for each hop", min = 100, max = 10000),
        ToolInput("hops", "Max hops", ToolInputKind.Number, "15", "Where to stop", min = 1, max = 30),
    )

    override fun available(): ToolAvailability {
        val command = traceCommand(os, which, "127.0.0.1", 1000, 15)
        return if (command != null) ToolAvailability(true) else ToolAvailability(false, "traceroute was not found. On Linux install traceroute or tracepath.")
    }

    override suspend fun run(values: Map<String, String>, emit: (ToolEvent) -> Unit): ToolResult {
        val clean = try { validateToolValues(inputs, values) } catch (e: ToolInputException) { return ToolResult(false, e.message ?: "Check the inputs.") }
        val host = clean.getValue("host")
        val command = traceCommand(os, which, host, clean.getValue("timeout").toInt(), clean.getValue("hops").toInt())
            ?: return ToolResult(false, "traceroute was not found. On Linux install traceroute or tracepath.")
        if (traceIgnoresTimeout(command)) {
            emit(ToolEvent.Status("Tracing $host. $TRACEPATH_NO_TIMEOUT"))
            emit(ToolEvent.Line(TRACEPATH_NO_TIMEOUT))
        } else emit(ToolEvent.Status("Tracing $host"))
        val hops = mutableListOf<TraceHop>()
        val code = try {
            // Windows tracert writes the console code page, not UTF-8.
            runToolProcess(command, consoleOutputCharset(os)) { line ->
                emit(ToolEvent.Line(line))
                parseTraceHop(line)?.let { hop ->
                    hops += hop
                    hop.rttMs?.let { emit(ToolEvent.Sample("rtt", hop.hop.toDouble(), it, "ms")) }
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return ToolResult(false, oneLine(e.message ?: "Traceroute failed."))
        }
        val last = hops.lastOrNull { it.address != null }
        val summary = when {
            hops.isEmpty() -> "No hops returned"
            last != null -> "${hops.size} hops, last ${last.address}"
            else -> "${hops.size} hops, no address returned"
        }
        return ToolResult(code == 0 || hops.isNotEmpty(), summary, mapOf("hops" to hops.size.toString(), "last" to (last?.address ?: "")))
    }
}

/** Windows `tracert`, otherwise `traceroute -n`, otherwise `tracepath -n`. Null when none of them exist. */
internal fun traceCommand(os: String, which: (String) -> String?, host: String, timeoutMs: Int, maxHops: Int): List<String>? {
    if (windowsOs(os)) {
        val exe = which("tracert") ?: return null
        return listOf(exe, "-d", "-w", timeoutMs.toString(), "-h", maxHops.toString(), host)
    }
    which("traceroute")?.let { return listOf(it, "-n", "-w", ((timeoutMs + 999) / 1000).coerceAtLeast(1).toString(), "-m", maxHops.toString(), host) }
    which("tracepath")?.let { return listOf(it, "-n", "-m", maxHops.toString(), host) }
    return null
}

internal const val TRACEPATH_NO_TIMEOUT = "tracepath has no per-hop timeout, so the Timeout field is not used."

/** tracepath (used when traceroute is not installed) takes no timeout argument. */
internal fun traceIgnoresTimeout(command: List<String>): Boolean =
    command.first().substringAfterLast('/').substringAfterLast('\\').equals("tracepath", ignoreCase = true)

internal data class TraceHop(val hop: Int, val address: String?, val rttMs: Double?, val timeout: Boolean)

internal fun parseTraceHop(line: String): TraceHop? {
    val match = Regex("""^\s*(\d+)\s*[:.]?\s*(.*)$""").find(line) ?: return null
    val hop = match.groupValues[1].toIntOrNull() ?: return null
    if (hop !in 1..64) return null
    val rest = match.groupValues[2]
    if (rest.isBlank()) return null
    val times = HOP_TIME.findAll(rest).map { found ->
        val number = found.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@map null
        if (found.value.trimStart().startsWith("<")) 0.0 else number
    }.filterNotNull().toList()
    val address = ADDRESS.findAll(rest).map { it.value }.lastOrNull { isIpv4(it) || isIpv6(it) }
    val timeout = times.isEmpty() && ('*' in rest || rest.contains("timed out", ignoreCase = true))
    if (times.isEmpty() && address == null && !timeout) return null
    return TraceHop(hop, address, times.minOrNull(), timeout && times.isEmpty())
}

private val HOP_TIME = Regex("""(?:<\s*)?([0-9]+(?:[.,][0-9]+)?)\s*ms""", RegexOption.IGNORE_CASE)
private val ADDRESS = Regex("""(?:\d{1,3}\.){3}\d{1,3}|[0-9A-Fa-f:]+:[0-9A-Fa-f:]+""")
