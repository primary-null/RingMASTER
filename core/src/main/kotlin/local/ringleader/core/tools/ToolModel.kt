package local.ringleader.core.tools

import local.ringleader.core.IpLiteral

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

/** Hostname, IPv4 or IPv6 only. Shell metacharacters and any other punctuation are rejected, with a sentence that says why. */
fun validateHost(value: String, label: String = "Host"): String {
    val host = value.trim()
    hostRefusal(host, label)?.let { throw ToolInputException(it) }
    return host
}

fun isHostToken(value: String): Boolean = hostRefusal(value, "Host") == null

/**
 * Why [value] is not a host a tool may be given, or null when it is one: a name, an IPv4 address (four numbers, no leading zeros)
 * or an IPv6 address in any valid text form (`::`, eight groups, an IPv4 tail), a link-local one with a zone (`fe80::1%12`; on
 * Windows the zone is the interface number, elsewhere an interface name also works). It never starts with a hyphen, so no tool
 * can read it as an option.
 */
internal fun hostRefusal(value: String, label: String = "Host", namedZones: Boolean = !windowsOs()): String? {
    val generic = "$label must be a hostname or an IP address."
    if (value.isEmpty()) return "Enter ${label.lowercase()}."
    if (value.length > 253) return "$label is too long."
    if (value.any { it.isISOControl() }) return generic
    if (value.startsWith("-")) return "$label cannot start with a hyphen: a program would read it as an option."
    if (value.startsWith("[") || value.endsWith("]")) {
        return if (IpLiteral.parseV6(IpLiteral.unbracket(value).substringBefore('%')) != null) "$label takes an IPv6 address without square brackets, like 2001:db8::1." else generic
    }
    val percent = value.indexOf('%')
    if (percent >= 0) return zoneRefusal(value.substring(0, percent), value.substring(percent + 1), namedZones)
    if (value.any { it in HOST_REJECTED }) return generic
    if (':' in value) return if (IpLiteral.parseV6(value) != null) null else "That is not a valid IPv6 address: up to eight groups of one to four hex digits, and \"::\" only once."
    if ('.' in value && value.all { it in '0'..'9' || it == '.' }) {
        if (IpLiteral.parseV4(value) != null) return null
        return "That is not a valid IPv4 address: four numbers from 0 to 255 separated by dots, without leading zeros (some programs read a leading zero as octal)."
    }
    return if (isDnsName(value)) null else generic
}

private fun zoneRefusal(address: String, zone: String, namedZones: Boolean): String? {
    val bytes = IpLiteral.parseV6(address) ?: return "A % can only follow an IPv6 address, as a zone like fe80::1%12."
    val linkLocal = (bytes[0].toInt() and 255) == 0xfe && (bytes[1].toInt() and 0xc0) == 0x80
    if (!linkLocal) return "A zone (%...) belongs to a link-local address (fe80::...)."
    if (zone.isEmpty()) return "The zone after % is empty: give the interface number, like fe80::1%12."
    if (zone.length <= 10 && zone.all { it in '0'..'9' }) return null
    if (!namedZones) return "On Windows the zone is the interface number, like fe80::1%12."
    val name = zone.length <= 15 && zone[0] != '-' && zone.all { (it in '0'..'9') || (it in 'a'..'z') || (it in 'A'..'Z') || it == '_' || it == '.' || it == '-' }
    return if (name) null else "The zone after % must be an interface number or name."
}

/** Four numbers from 0 to 255, ASCII digits only, no leading zeros. */
fun isIpv4(value: String): Boolean = IpLiteral.parseV4(value) != null

/** Any valid IPv6 text form without brackets or zone: eight groups, `::` for one or more zero groups, an IPv4 tail. */
fun isIpv6(value: String): Boolean = IpLiteral.parseV6(value) != null

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
