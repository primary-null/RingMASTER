package local.ringleader.core.tools

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.PatternSyntaxException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import local.ringleader.core.findExecutable

data class SkippedPlugin(val file: String, val reason: String)

/** What the user confirmed: the manifest [command], the substituted [argv], and the file [program] resolved to. */
data class PluginApproval(val command: List<String>, val argv: List<String>, val program: String)

internal const val BATCH_FILE_REFUSED = "Batch files (.bat, .cmd) cannot be the program. Use the program itself."
/** Patterns run on at most this many characters of a line. */
internal const val PATTERN_LINE_LIMIT = 1000
/** Time one pattern may spend on one line. */
internal const val PATTERN_BUDGET_MS = 50L
/** After this many slow lines the pattern is switched off for the run. */
internal const val PATTERN_SLOW_LINES = 3

/**
 * A command the user described in JSON. The first run in a session must have [approval]
 * set after the person has seen [commandFor]. Arguments are substituted one element at a time.
 */
class UserToolPlugin(
    override val id: String,
    override val name: String,
    override val summary: String,
    override val inputs: List<ToolInput>,
    private val command: List<String>,
    private val linePattern: Regex?,
    val source: String,
    private val which: (String) -> String? = ::findExecutable,
) : ToolPlugin {
    @Volatile var approval: PluginApproval? = null
    val manifestCommand: List<String> get() = command

    override fun available(): ToolAvailability {
        if (isBatchFile(command.first())) return ToolAvailability(false, BATCH_FILE_REFUSED)
        val program = resolveProgram(command.first(), which)
        return if (program != null) ToolAvailability(true) else ToolAvailability(false, "${command.first()} was not found. Use a full path or install it on PATH.")
    }

    fun commandFor(values: Map<String, String>): List<String> {
        val clean = validateToolValues(inputs, values)
        return substitutePlaceholders(command, clean)
    }

    /**
     * What a confirmation of [values] would approve, with the program resolved to the file that would run.
     * Null when the program is not found.
     */
    fun approvalFor(values: Map<String, String>): PluginApproval? {
        val argv = commandFor(values)
        if (isBatchFile(argv.first())) return null
        val program = resolveProgram(argv.first(), which) ?: return null
        return PluginApproval(command, argv, program)
    }

    override suspend fun run(values: Map<String, String>, emit: (ToolEvent) -> Unit): ToolResult {
        callerRefusal(this, values)?.let { return ToolResult(false, it) }
        val argv = try { commandFor(values) } catch (e: ToolInputException) { return ToolResult(false, e.message ?: "Check the inputs.") }
        val a = approval
        if (a == null || a.command != command || a.argv != argv) return ToolResult(false, "Confirm this command before running it.")
        if (isBatchFile(argv.first())) return ToolResult(false, BATCH_FILE_REFUSED)
        val program = resolveProgram(argv.first(), which) ?: return ToolResult(false, "${argv.first()} was not found. Use a full path or install it on PATH.")
        // The confirmation named a file. If PATH now finds a different one, ask again.
        if (program != a.program) return ToolResult(false, "The program changed since you confirmed it. Confirm this command before running it.")
        val full = listOf(program) + argv.drop(1)
        emit(ToolEvent.Status("Running $name"))
        var seq = 0
        var code = 1
        var slow = 0
        try {
            code = runToolProcess(full) { line ->
                emit(ToolEvent.Line(line))
                val pattern = linePattern?.takeIf { slow < PATTERN_SLOW_LINES } ?: return@runToolProcess
                val match = when (val found = findWithBudget(pattern, line)) {
                    is LineMatch.Found -> found.match
                    LineMatch.None -> return@runToolProcess
                    LineMatch.TooSlow -> {
                        slow += 1
                        if (slow == PATTERN_SLOW_LINES) emit(ToolEvent.Line("The lines pattern is too slow, so the chart is off for this run."))
                        return@runToolProcess
                    }
                }
                val sample = match.groups["value"]?.value?.replace(',', '.')?.toDoubleOrNull() ?: return@runToolProcess
                seq += 1
                val series = match.groups["series"]?.value?.takeIf { it.isNotBlank() } ?: "value"
                val x = match.groups["x"]?.value?.toDoubleOrNull() ?: seq.toDouble()
                val unit = match.groups["unit"]?.value ?: ""
                emit(ToolEvent.Sample(series, x, sample, unit))
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return ToolResult(false, oneLine(e.message ?: "The plug-in failed."))
        }
        val summary = if (code == 0) "$name finished" else "$name exited with status $code"
        return ToolResult(code == 0, summary, mapOf("exit" to code.toString()))
    }
}

fun loadUserPlugins(directory: Path, reserved: Set<String> = emptySet(), which: (String) -> String? = ::findExecutable): Pair<List<UserToolPlugin>, List<SkippedPlugin>> {
    if (!Files.isDirectory(directory)) return emptyList<UserToolPlugin>() to emptyList()
    val plugins = mutableListOf<UserToolPlugin>()
    val skipped = mutableListOf<SkippedPlugin>()
    val seen = reserved.toMutableSet()
    Files.newDirectoryStream(directory, "*.json").use { stream ->
        stream.sortedBy { it.fileName.toString().lowercase() }.forEach { path ->
            val file = path.fileName.toString()
            val size = runCatching { Files.size(path) }.getOrDefault(Long.MAX_VALUE)
            if (size > 65_536) {
                skipped += SkippedPlugin(file, "The manifest is too large.")
                return@forEach
            }
            val text = runCatching { Files.readString(path) }.getOrElse {
                skipped += SkippedPlugin(file, "The manifest could not be read.")
                return@forEach
            }
            when (val parsed = readUserPlugin(file, text, which)) {
                is ManifestRead.Ok -> {
                    if (parsed.plugin.id in seen) {
                        val reason = if (parsed.plugin.id in reserved) "This id is reserved." else "A plug-in with this id is already loaded."
                        skipped += SkippedPlugin(file, reason)
                    } else {
                        seen += parsed.plugin.id
                        plugins += parsed.plugin
                    }
                }
                is ManifestRead.Bad -> skipped += SkippedPlugin(file, parsed.reason)
            }
        }
    }
    return plugins.sortedBy { it.name.lowercase() } to skipped
}

sealed class ManifestRead {
    data class Ok(val plugin: UserToolPlugin) : ManifestRead()
    data class Bad(val reason: String) : ManifestRead()
}

fun readUserPlugin(fileName: String, text: String, which: (String) -> String? = ::findExecutable): ManifestRead {
    val root = try { Json.parseToJsonElement(text) } catch (_: Exception) { return ManifestRead.Bad("This file is not valid JSON.") }
    val obj = root as? JsonObject ?: return ManifestRead.Bad("The manifest must be a JSON object.")
    val id = obj.string("id") ?: return ManifestRead.Bad("id is missing.")
    if (!id.matches(Regex("[a-z][a-z0-9-]{0,40}"))) return ManifestRead.Bad("id must be lowercase letters, digits and hyphens.")
    val name = obj.string("name") ?: return ManifestRead.Bad("name is missing.")
    val summary = obj.string("summary") ?: return ManifestRead.Bad("summary is missing.")
    if (name.length > 60 || summary.length > 200 || name.any { it.isISOControl() } || summary.any { it.isISOControl() }) {
        return ManifestRead.Bad("name or summary is not valid.")
    }
    val commandArray = obj["command"] as? JsonArray ?: return ManifestRead.Bad("command must be a list of arguments.")
    if (commandArray.isEmpty() || commandArray.size > 20) return ManifestRead.Bad("command must be a list of arguments.")
    val command = commandArray.map { element ->
        (element as? JsonPrimitive)?.contentOrNull ?: return ManifestRead.Bad("command must be a list of arguments.")
    }
    if (command.any { it.isBlank() || it.length > 400 || it.any { c -> c == '\u0000' || c == '\n' || c == '\r' } }) {
        return ManifestRead.Bad("command must be a list of arguments.")
    }
    if ('{' in command.first()) return ManifestRead.Bad("The program cannot be a placeholder.")
    if (!validExecutableToken(command.first())) return ManifestRead.Bad("The program must be an absolute path or a program name.")
    if (isBatchFile(command.first())) return ManifestRead.Bad(BATCH_FILE_REFUSED)
    val inputs = when (val parsed = parseInputs(obj["inputs"] as? JsonArray)) {
        is InputRead.Ok -> parsed.inputs
        is InputRead.Bad -> return ManifestRead.Bad(parsed.reason)
    }
    val keys = inputs.map { it.key }.toSet()
    if (keys.size != inputs.size) return ManifestRead.Bad("Each input needs its own key.")
    val placeholders = PLACEHOLDER.findAll(command.drop(1).joinToString("\u0000")).map { it.groupValues[1] }.toSet()
    val missing = placeholders.firstOrNull { it !in keys }
    if (missing != null) return ManifestRead.Bad("Placeholder {$missing} has no input.")
    val pattern = obj.string("lines")
    val regex = if (pattern.isNullOrBlank()) null else try {
        if (pattern.length > 200) return ManifestRead.Bad("The lines pattern is not valid.")
        val compiled = Regex(pattern)
        if ("value" !in compiled.toPattern().namedGroups()) return ManifestRead.Bad("The lines pattern needs a named group called value.")
        compiled
    } catch (_: PatternSyntaxException) {
        return ManifestRead.Bad("The lines pattern is not valid.")
    }
    return ManifestRead.Ok(UserToolPlugin(id, name, summary, inputs, command, regex, fileName, which))
}

internal fun substitutePlaceholders(command: List<String>, values: Map<String, String>): List<String> =
    command.map { element ->
        val matches = PLACEHOLDER.findAll(element).toList()
        if (matches.isEmpty()) element else buildString {
            var cursor = 0
            for (match in matches) {
                append(element, cursor, match.range.first)
                val replacement = values[match.groupValues[1]] ?: throw ToolInputException("Missing {${match.groupValues[1]}}.")
                append(replacement)
                cursor = match.range.last + 1
            }
            append(element, cursor, element.length)
        }
    }

internal fun validExecutableToken(token: String): Boolean {
    if (token.isBlank() || token.any { it.isISOControl() }) return false
    val path = runCatching { Path.of(token) }.getOrNull() ?: return false
    if (path.isAbsolute || '/' in token || '\\' in token) return path.isAbsolute && ".." !in token
    return token.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,40}"))
}

internal fun resolveProgram(token: String, which: (String) -> String?): String? {
    if (!validExecutableToken(token)) return null
    if (isBatchFile(token)) return null
    if ('/' in token || '\\' in token || runCatching { Path.of(token).isAbsolute }.getOrDefault(false)) {
        val path = Path.of(token)
        return path.toString().takeIf { path.isAbsolute && Files.isRegularFile(path) }
    }
    val resolved = which(token) ?: return null
    if (isBatchFile(resolved)) return null
    return resolved
}

internal sealed class LineMatch {
    data class Found(val match: MatchResult) : LineMatch()
    data object None : LineMatch()
    /** The pattern ran past its time budget on this line. */
    data object TooSlow : LineMatch()
}

/**
 * [pattern] on at most [PATTERN_LINE_LIMIT] characters of [line], stopped after [budgetMs].
 * A pattern that backtracks badly cannot stall the output reader or Stop.
 */
internal fun findWithBudget(pattern: Regex, line: String, budgetMs: Long = PATTERN_BUDGET_MS): LineMatch {
    val text = DeadlineChars(line.take(PATTERN_LINE_LIMIT), System.nanoTime() + budgetMs * 1_000_000)
    return try { pattern.find(text)?.let { LineMatch.Found(it) } ?: LineMatch.None } catch (_: PatternBudgetExceeded) { LineMatch.TooSlow }
}

private class PatternBudgetExceeded : RuntimeException(null, null, false, false)

/** Character access that fails once the deadline has passed. The regex engine reads every character through [get]. */
private class DeadlineChars(private val text: CharSequence, private val deadline: Long) : CharSequence {
    private var reads = 0
    override val length: Int get() = text.length
    override fun get(index: Int): Char {
        if ((++reads and 0x3FF) == 0 && System.nanoTime() > deadline) throw PatternBudgetExceeded()
        return text[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)
    override fun toString(): String = text.toString()
}

internal fun isBatchFile(token: String): Boolean {
    val name = token.substringAfterLast('/').substringAfterLast('\\').trimEnd(' ', '.').lowercase()
    return name.endsWith(".bat") || name.endsWith(".cmd")
}

private sealed class InputRead {
    data class Ok(val inputs: List<ToolInput>) : InputRead()
    data class Bad(val reason: String) : InputRead()
}

private fun parseInputs(array: JsonArray?): InputRead {
    if (array == null) return InputRead.Bad("inputs must be a list.")
    if (array.size > 16) return InputRead.Bad("A plug-in can have at most 16 inputs.")
    val inputs = array.map { element ->
        val obj = element as? JsonObject ?: return InputRead.Bad("inputs must be a list.")
        val key = obj.string("key") ?: return InputRead.Bad("An input is missing a key.")
        if (!key.matches(Regex("[a-z][a-z0-9_]{0,30}"))) return InputRead.Bad("An input key must be lowercase letters, digits and underscores.")
        val label = obj.string("label") ?: return InputRead.Bad("An input is missing a label.")
        val kind = obj.string("kind")?.let { raw -> ToolInputKind.entries.firstOrNull { it.name.equals(raw, true) } }
            ?: return InputRead.Bad("kind must be Text, Host, Port, Number, Choice or Toggle.")
        val default = obj.string("default") ?: ""
        val hint = obj.string("hint") ?: ""
        val choices = (obj["choices"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        if (kind == ToolInputKind.Choice && choices.isEmpty()) return InputRead.Bad("choices are missing.")
        val min = obj.long("min")
        val max = obj.long("max")
        if (kind == ToolInputKind.Number) {
            val lo = min ?: 0L
            val hi = max ?: 1_000_000L
            if (hi < lo || hi > 1_000_000_000L || hi - lo > 1_000_000_000L) return InputRead.Bad("Number range is too wide.")
        }
        val resolvedDefault = if (kind == ToolInputKind.Toggle && default.isBlank()) "false" else default
        val required = (obj["required"] as? JsonPrimitive)?.booleanOrNull ?: (kind != ToolInputKind.Toggle)
        ToolInput(key, label, kind, resolvedDefault, hint, choices, emptyList(), min, max, required)
    }
    return InputRead.Ok(inputs)
}

private fun JsonObject.string(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive.isString) return primitive.content.takeIf { it.isNotBlank() }
    return primitive.contentOrNull?.takeIf { it.isNotBlank() }
}

private fun JsonObject.long(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.longOrNull ?: primitive.doubleOrNull?.toLong()
}

private val PLACEHOLDER = Regex("""\{([A-Za-z][A-Za-z0-9_]*)\}""")
