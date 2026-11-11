package local.ringleader.core.tools

import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path
import local.ringleader.core.findExecutable
import kotlinx.coroutines.CancellationException

class Iperf3Plugin(private val which: (String) -> String? = ::findExecutable) : ToolPlugin {
    override val id = "iperf3"
    override val name = "iPerf3"
    override val summary = "Throughput to an iPerf3 server, or one local server test."
    override val inputs = listOf(
        ToolInput("server", "Server", ToolInputKind.Toggle, "false", "Listen here for one test, then exit"),
        ToolInput("host", "Host", ToolInputKind.Host, "", "Server to test", required = false),
        ToolInput("port", "Port", ToolInputKind.Port, "5201", "iPerf3 port"),
        ToolInput("duration", "Duration (s)", ToolInputKind.Number, "10", "Length of a client test", min = 1, max = 120),
        ToolInput("parallel", "Streams", ToolInputKind.Number, "1", "Parallel streams", min = 1, max = 32),
        ToolInput("reverse", "Reverse", ToolInputKind.Toggle, "false", "Server sends, this computer receives"),
        ToolInput("udp", "UDP", ToolInputKind.Toggle, "false", "Use UDP instead of TCP"),
        ToolInput("executable", "iperf3 path", ToolInputKind.Text, "", "Leave empty to use PATH", required = false),
    )

    override fun available(): ToolAvailability = availableFor(emptyMap())

    fun availableFor(values: Map<String, String>): ToolAvailability {
        val exe = resolveIperf(values["executable"].orEmpty(), which)
        return if (exe != null) ToolAvailability(true) else ToolAvailability(false, IPERF_MISSING)
    }

    override suspend fun run(values: Map<String, String>, emit: (ToolEvent) -> Unit): ToolResult {
        val clean = try { validateToolValues(inputs, values) } catch (e: ToolInputException) { return ToolResult(false, e.message ?: "Check the inputs.") }
        val server = clean["server"] == "true"
        val host = clean["host"].orEmpty()
        if (!server && host.isEmpty()) return ToolResult(false, "Enter a host.")
        if (!server && !isHostToken(host)) return ToolResult(false, "Host must be a hostname or an IP address.")
        val exe = resolveIperf(clean["executable"].orEmpty(), which) ?: return ToolResult(false, IPERF_MISSING)
        val command = iperfCommand(
            exe, server, host, clean.getValue("port").toInt(), clean.getValue("duration").toInt(),
            clean.getValue("parallel").toInt(), clean["reverse"] == "true", clean["udp"] == "true",
        )
        if (server) {
            emit(ToolEvent.Status("Listening on port ${clean.getValue("port")}"))
            emit(ToolEvent.Line("This opens a listening port on this computer. It accepts one test, then exits."))
            emit(ToolEvent.Line("This computer: ${localAddresses().joinToString(" ")}"))
        } else emit(ToolEvent.Status("Testing $host"))
        var receiver: Double? = null
        var sender: Double? = null
        var last: Double? = null
        val code = try {
            runToolProcess(command) { line ->
                emit(ToolEvent.Line(line))
                val parsed = parseIperfLine(line) ?: return@runToolProcess
                val megabits = parsed.bitsPerSecond / 1_000_000.0
                if (parsed.summary) {
                    when {
                        line.contains("receiver", ignoreCase = true) -> receiver = megabits
                        line.contains("sender", ignoreCase = true) -> sender = megabits
                        receiver == null && sender == null -> sender = megabits
                    }
                } else {
                    last = megabits
                    emit(ToolEvent.Sample("throughput", parsed.end, megabits, "Mbit/s"))
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return ToolResult(false, oneLine(e.message ?: "iperf3 failed."))
        }
        val total = receiver ?: sender ?: last
        val summary = when {
            server && code == 0 -> "Server test finished"
            server -> "Server stopped"
            total != null -> "${formatNumber(total)} Mbit/s"
            else -> "iperf3 finished"
        }
        return ToolResult(code == 0, summary, mapOf("mbps" to (total?.let(::formatNumber) ?: ""), "exit" to code.toString()))
    }
}

internal const val IPERF_MISSING = "iperf3 was not found. Install it with winget install iperf3 or apt install iperf3."

internal fun resolveIperf(configured: String, which: (String) -> String?): String? {
    val path = configured.trim()
    if (path.isEmpty()) return which("iperf3")
    if (path.any { it.isISOControl() || it in "\"';|&$`<>" }) return null
    val file = runCatching { Path.of(path) }.getOrNull() ?: return null
    if (!file.isAbsolute || !Files.isRegularFile(file)) return null
    return file.toString()
}

internal fun iperfCommand(exe: String, server: Boolean, host: String, port: Int, seconds: Int, parallel: Int, reverse: Boolean, udp: Boolean): List<String> {
    if (server) return listOf(exe, "-s", "-1", "-p", port.toString(), "--forceflush")
    val command = mutableListOf(exe, "-c", host, "-p", port.toString(), "-t", seconds.toString(), "-P", parallel.toString(), "--forceflush")
    if (reverse) command += "-R"
    if (udp) command += "-u"
    return command
}

internal data class IperfSample(val id: String, val start: Double, val end: Double, val bitsPerSecond: Double, val summary: Boolean)

internal fun parseIperfLine(line: String): IperfSample? {
    val match = IPERF.find(line) ?: return null
    val scale = when (match.groupValues[5].uppercase()) {
        "K" -> 1_000.0
        "M" -> 1_000_000.0
        "G" -> 1_000_000_000.0
        else -> 1.0
    }
    val start = match.groupValues[2].toDouble()
    val end = match.groupValues[3].toDouble()
    val bits = match.groupValues[4].toDouble() * scale
    val summary = end - start > 1.5 || line.contains("sender", ignoreCase = true) || line.contains("receiver", ignoreCase = true)
    return IperfSample(match.groupValues[1], start, end, bits, summary)
}

internal fun localAddresses(): List<String> {
    val found = mutableListOf("127.0.0.1")
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return found
    for (nic in interfaces) {
        if (!nic.isUp) continue
        for (address in nic.inetAddresses) {
            if (address.isAnyLocalAddress || address.isMulticastAddress) continue
            val host = address.hostAddress?.substringBefore('%') ?: continue
            if (host != "127.0.0.1") found += host
        }
    }
    return found.distinct()
}

private val IPERF = Regex(
    """\[\s*(SUM|\d+)\]\s+([0-9.]+)-([0-9.]+)\s+sec\s+[0-9.]+\s+[KMG]?Bytes\s+([0-9.]+)\s+([KMG])?bits/sec""",
    RegexOption.IGNORE_CASE,
)
