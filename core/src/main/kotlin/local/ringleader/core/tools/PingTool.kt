package local.ringleader.core.tools

import local.ringleader.core.findExecutable
import kotlinx.coroutines.CancellationException

class PingPlugin(
    private val os: String = System.getProperty("os.name") ?: "",
    private val which: (String) -> String? = ::findExecutable,
) : ToolPlugin {
    override val id = "ping"
    override val name = "Ping"
    override val summary = "Round-trip time and loss, using this computer's ping."
    override val inputs = listOf(
        ToolInput("host", "Host", ToolInputKind.Host, "127.0.0.1", "Hostname or IP address"),
        ToolInput("count", "Count", ToolInputKind.Number, "4", "How many probes", min = 1, max = 100),
        ToolInput("timeout", "Timeout (ms)", ToolInputKind.Number, "1000", "How long to wait for each reply", min = 100, max = 10000),
    )

    override fun available(): ToolAvailability =
        if (which("ping") != null) ToolAvailability(true) else ToolAvailability(false, "ping was not found on PATH.")

    override suspend fun run(values: Map<String, String>, emit: (ToolEvent) -> Unit): ToolResult {
        val clean = try { validateToolValues(inputs, values) } catch (e: ToolInputException) { return ToolResult(false, e.message ?: "Check the inputs.") }
        val exe = which("ping") ?: return ToolResult(false, "ping was not found on PATH.")
        val host = clean.getValue("host")
        val count = clean.getValue("count").toInt()
        val timeout = clean.getValue("timeout").toInt()
        val command = pingCommand(os, exe, host, count, timeout)
        val lines = mutableListOf<String>()
        emit(ToolEvent.Status("Pinging $host"))
        var seq = 0
        val code = try {
            // Windows ping writes the console code page, not UTF-8.
            runToolProcess(command, consoleOutputCharset(os)) { line ->
                lines += line
                emit(ToolEvent.Line(line))
                parsePingLatency(line)?.let { ms ->
                    seq += 1
                    emit(ToolEvent.Sample("latency", seq.toDouble(), ms, "ms"))
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return ToolResult(false, oneLine(e.message ?: "Ping failed."))
        }
        val report = parsePingReport(lines.joinToString("\n"), count)
        val summary = pingSummary(report)
        return ToolResult(code == 0 || report.received > 0, summary, report.metrics())
    }
}

internal fun pingCommand(os: String, exe: String, host: String, count: Int, timeoutMs: Int): List<String> =
    if (windowsOs(os)) listOf(exe, "-n", count.toString(), "-w", timeoutMs.toString(), host)
    else listOf(exe, "-c", count.toString(), "-W", ((timeoutMs + 999) / 1000).coerceAtLeast(1).toString(), host)

internal data class PingReport(
    val sent: Int,
    val received: Int,
    val lossPercent: Double,
    val minMs: Double?,
    val avgMs: Double?,
    val maxMs: Double?,
    val samples: List<Double>,
) {
    fun metrics(): Map<String, String> = mapOf(
        "sent" to sent.toString(),
        "received" to received.toString(),
        "loss" to formatNumber(lossPercent),
        "min" to (minMs?.let(::formatNumber) ?: ""),
        "avg" to (avgMs?.let(::formatNumber) ?: ""),
        "max" to (maxMs?.let(::formatNumber) ?: ""),
    )
}

internal fun pingSummary(report: PingReport): String = buildString {
    append("${report.sent} sent, ${report.received} received, ${formatNumber(report.lossPercent)}% loss")
    if (report.minMs != null && report.avgMs != null && report.maxMs != null) {
        append(", min ${formatNumber(report.minMs)} ms, avg ${formatNumber(report.avgMs)} ms, max ${formatNumber(report.maxMs)} ms")
    }
}

/** One reply's latency, in milliseconds. `time<1ms` is 0, matching the Windows summary. Null when the line is not a reply. */
internal fun parsePingLatency(line: String): Double? {
    if (!line.contains("ttl", ignoreCase = true) && !line.contains("icmp_seq", ignoreCase = true)) return null
    val match = LATENCY.find(line) ?: return null
    val number = match.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
    return if (match.groupValues[1] == "<") 0.0 else number
}

internal fun parsePingReport(text: String, requested: Int): PingReport {
    val samples = text.lineSequence().mapNotNull(::parsePingLatency).toList()
    val linux = LINUX_PACKETS.find(text)
    val packets = windowsPacketCounts(text)
    val sent: Int
    val received: Int
    val loss: Double
    when {
        linux != null -> {
            sent = linux.groupValues[1].toInt()
            received = linux.groupValues[2].toInt()
            loss = linux.groupValues[3].replace(',', '.').toDouble()
        }
        packets != null -> {
            sent = packets.first
            received = packets.second
            loss = if (sent == 0) 0.0 else (sent - received) * 100.0 / sent
        }
        else -> {
            received = samples.size
            sent = requested.coerceAtLeast(received)
            loss = if (sent == 0) 0.0 else (sent - received) * 100.0 / sent
        }
    }
    val rtt = RTT.find(text)
    val windowsTimes = windowsTimeLine(text)
    val (min, avg, max) = when {
        rtt != null -> Triple(num(rtt.groupValues[1]), num(rtt.groupValues[2]), num(rtt.groupValues[3]))
        windowsTimes != null -> windowsTimes
        samples.isEmpty() -> Triple(null, null, null)
        else -> Triple(samples.min(), samples.average(), samples.max())
    }
    return PingReport(sent, received, loss, min, avg, max, samples)
}

private fun windowsPacketCounts(text: String): Pair<Int, Int>? {
    for (line in text.lines()) {
        if (line.contains("ms", ignoreCase = true)) continue
        val match = COUNTS.find(line) ?: continue
        val sent = match.groupValues[1].toInt()
        val received = match.groupValues[2].toInt()
        val lost = match.groupValues[3].toInt()
        if (sent in 0..100000 && received in 0..sent && lost in 0..sent && received + lost == sent) return sent to received
    }
    return null
}

/** Windows prints minimum, maximum, then average, in that order, in every locale we have seen. */
private fun windowsTimeLine(text: String): Triple<Double, Double, Double>? {
    for (line in text.lines()) {
        if ('/' in line) continue
        val numbers = MS_NUMBER.findAll(line).map { num(it.groupValues[1]) }.toList()
        if (numbers.size == 3) return Triple(numbers[0], numbers[2], numbers[1])
    }
    return null
}

private fun num(value: String) = value.replace(',', '.').toDouble()

internal fun formatNumber(value: Double): String = when {
    !value.isFinite() -> ""
    value == kotlin.math.floor(value) && kotlin.math.abs(value) < 100000 -> String.format(java.util.Locale.ROOT, "%.0f", value)
    kotlin.math.abs(value) >= 10 -> String.format(java.util.Locale.ROOT, "%.1f", value)
    else -> String.format(java.util.Locale.ROOT, "%.2f", value)
}

private val LATENCY = Regex("""(?i)([<=])\s*([0-9]+(?:[.,][0-9]+)?)\s*ms""")
private val LINUX_PACKETS = Regex("""(\d+)\s+\S+\s+\S+,\s*(\d+)\s+\S+,\s*([0-9]+(?:[.,][0-9]+)?)\s*%""")
private val COUNTS = Regex("""=\s*(\d+)\s*(?:,|\([^)]*\))?\s*[^=]{0,40}=\s*(\d+)\s*(?:,|\([^)]*\))?\s*[^=]{0,40}=\s*(\d+)""")
private val RTT = Regex("""=\s*([0-9]+(?:[.,][0-9]+)?)\s*/\s*([0-9]+(?:[.,][0-9]+)?)\s*/\s*([0-9]+(?:[.,][0-9]+)?)""")
private val MS_NUMBER = Regex("""([0-9]+(?:[.,][0-9]+)?)\s*ms""", RegexOption.IGNORE_CASE)
