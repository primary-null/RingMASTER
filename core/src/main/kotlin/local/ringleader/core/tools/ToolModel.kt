package local.ringleader.core.tools

/**
 * A network tool RingMASTER can run. Built-in tools and JSON plug-ins share this shape.
 * [run] receives already-split arguments only. Nothing in this package builds a shell command.
 */
interface ToolPlugin {
    val id: String
    val name: String
    val summary: String
    val inputs: List<ToolInput>
    fun available(): ToolAvailability
    suspend fun run(values: Map<String, String>, emit: (ToolEvent) -> Unit): ToolResult
}

enum class ToolInputKind { Text, Host, Port, Number, Choice, Toggle }

/**
 * One field. [min] and [max] bound numbers. [choices] bound Choice fields.
 * Host, port and number rules are enforced by [validateToolValues] for every plug-in.
 */
data class ToolInput(
    val key: String,
    val label: String,
    val kind: ToolInputKind,
    val default: String = "",
    val hint: String = "",
    val choices: List<String> = emptyList(),
    val choiceLabels: List<String> = emptyList(),
    val min: Long? = null,
    val max: Long? = null,
    val required: Boolean = true,
) {
    fun display(value: String): String {
        val index = choices.indexOf(value)
        return choiceLabels.getOrNull(index)?.takeIf { it.isNotBlank() } ?: value.ifBlank { label }
    }
}

data class ToolAvailability(val ready: Boolean, val reason: String? = null)

sealed class ToolEvent {
    data class Line(val text: String) : ToolEvent()
    /** [x] is the sample index or time. [y] is latency in ms or throughput in Mbit/s. */
    data class Sample(val series: String, val x: Double, val y: Double, val unit: String) : ToolEvent()
    data class Status(val text: String) : ToolEvent()
}

data class ToolResult(val ok: Boolean, val summary: String, val metrics: Map<String, String> = emptyMap())

class ToolInputException(message: String) : IllegalArgumentException(message)

/** Collapse a message so it fits the single status line. */
fun oneLine(text: String): String = text.replace(Regex("\\s+"), " ").trim().take(240)

fun validateToolValues(inputs: List<ToolInput>, values: Map<String, String>): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (input in inputs) {
        val raw = if (input.key in values) values[input.key].orEmpty() else input.default
        out[input.key] = validateToolValue(input, raw)
    }
    return out
}

fun validateToolValue(input: ToolInput, raw: String): String {
    val value = raw.trim()
    if (value.isEmpty()) {
        if (!input.required) return ""
        throw ToolInputException("Enter ${input.label.lowercase()}.")
    }
    if (value.any { it.isISOControl() }) throw ToolInputException("${input.label} contains a control character.")
    if (value.length > 2000) throw ToolInputException("${input.label} is too long.")
    return when (input.kind) {
        ToolInputKind.Host -> validateHost(value, input.label)
        ToolInputKind.Port -> validatePort(value, input.label).toString()
        ToolInputKind.Number -> validateNumber(value, input).toString()
        ToolInputKind.Choice -> {
            if (input.choices.isEmpty()) throw ToolInputException("No ${input.label.lowercase()} is available.")
            if (value !in input.choices) throw ToolInputException("Choose a ${input.label.lowercase()}.")
            value
        }
        ToolInputKind.Toggle -> if (truthy(value)) "true" else "false"
        ToolInputKind.Text -> value
    }
}

internal fun truthy(value: String) = value.equals("true", true) || value.equals("yes", true) || value.equals("on", true) || value == "1"

/** Hostname, IPv4 or IPv6 only. Shell metacharacters and any other punctuation are rejected. */
fun validateHost(value: String, label: String = "Host"): String {
    val host = value.trim()
    if (host.isEmpty()) throw ToolInputException("Enter ${label.lowercase()}.")
    if (!isHostToken(host)) throw ToolInputException("$label must be a hostname or an IP address.")
    return host
}

fun isHostToken(value: String): Boolean {
    if (value.isEmpty() || value.length > 253) return false
    if (value.any { it.isISOControl() || it in HOST_REJECTED }) return false
    return isIpv4(value) || isIpv6(value) || isDnsName(value)
}

fun isIpv4(value: String): Boolean {
    val parts = value.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        if (part.isEmpty() || part.length > 3) return false
        if (part.length > 1 && part[0] == '0') return false
        part.toIntOrNull() in 0..255
    }
}

fun isIpv6(value: String): Boolean {
    if (value.length > 39 || value.any { it !in "0123456789abcdefABCDEF:" }) return false
    if (value.count { it == ':' } !in 2..7) return false
    if (value.split("::").size > 2) return false
    fun groups(text: String): Int? {
        if (text.isEmpty()) return 0
        val parts = text.split(':')
        if (parts.any { it.isEmpty() || it.length > 4 || it.any { c -> c !in "0123456789abcdefABCDEF" } }) return null
        return parts.size
    }
    return if ("::" in value) {
        val sides = value.split("::", limit = 2)
        val left = groups(sides[0]) ?: return false
        val right = groups(sides.getOrElse(1) { "" }) ?: return false
        left + right < 8
    } else groups(value) == 8
}

fun isDnsName(value: String): Boolean {
    val name = value.removeSuffix(".")
    if (name.isEmpty() || name.length > 253 || name.contains("..")) return false
    val labels = name.split('.')
    if (labels.all { label -> label.isNotEmpty() && label.all { it.isDigit() } }) return false
    return labels.all { label ->
        label.length in 1..63 && label.first().isLetterOrDigit() && label.last().isLetterOrDigit() && label.all { it.isLetterOrDigit() || it == '-' }
    }
}

fun validatePort(value: String, label: String = "Port"): Int {
    if (value.length > 1 && value[0] == '0') throw ToolInputException("$label must be a whole number from 1 to 65535.")
    if (!value.matches(DIGITS)) throw ToolInputException("$label must be a whole number from 1 to 65535.")
    val port = value.toIntOrNull() ?: throw ToolInputException("$label must be a whole number from 1 to 65535.")
    if (port !in 1..65535) throw ToolInputException("$label must be a whole number from 1 to 65535.")
    return port
}

/** Comma-separated ports. At most 32. The whole field is rejected when any entry is not a port. */
fun parsePortList(value: String): List<Int> {
    val parts = value.split(',')
    if (parts.any { it.isBlank() }) throw ToolInputException("Enter ports separated by commas.")
    if (parts.size > 32) throw ToolInputException("Enter at most 32 ports.")
    return parts.map { validatePort(it.trim(), "Port") }
}

private fun validateNumber(value: String, input: ToolInput): Long {
    val min = input.min ?: 0L
    val max = input.max ?: 1_000_000L
    if (max < min) throw ToolInputException("${input.label} is not available.")
    if (value.length > 1 && value[0] == '0') throw ToolInputException("${input.label} must be a whole number from $min to $max.")
    if (!value.matches(DIGITS)) throw ToolInputException("${input.label} must be a whole number from $min to $max.")
    val number = value.toLongOrNull() ?: throw ToolInputException("${input.label} must be a whole number from $min to $max.")
    if (number !in min..max) throw ToolInputException("${input.label} must be a whole number from $min to $max.")
    return number
}

private val DIGITS = Regex("[0-9]{1,9}")
private const val HOST_REJECTED = ";|&$`\\<>(){}!#*?\"' \t,%[]=+"
