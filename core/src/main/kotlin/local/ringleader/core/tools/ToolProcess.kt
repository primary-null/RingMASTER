package local.ringleader.core.tools

import local.ringleader.core.Trace
import local.ringleader.core.TraceSource

import java.io.File
import java.io.IOException
import java.io.Reader
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object RunningTools {
    val pids = CopyOnWriteArrayList<Long>()
    @Volatile var lastCommand: List<String> = emptyList()
    fun add(pid: Long) { pids.add(pid) }
    fun remove(pid: Long) { pids.remove(pid) }
}

internal fun windowsOs(os: String = System.getProperty("os.name") ?: "") = os.startsWith("Windows", ignoreCase = true)

/** A longer line is cut here, before the UI sees it. */
internal const val TOOL_LINE_LIMIT = 8192
/** After this many characters of output the tool is stopped. */
internal const val TOOL_OUTPUT_LIMIT = 4L * 1024 * 1024

class ToolOutputLimitException : IOException("The tool printed more than 4 MB of output, so it was stopped.")

/**
 * Runs [command] as an argument list. Cancelling the coroutine destroys the process and its children.
 * The caller must already have validated every argument. Output is decoded with [charset]. A line longer than
 * [TOOL_LINE_LIMIT] is cut, and more than [TOOL_OUTPUT_LIMIT] characters stops the process with [ToolOutputLimitException].
 */
internal suspend fun runToolProcess(command: List<String>, charset: Charset = Charset.defaultCharset(), onLine: (String) -> Unit): Int = coroutineScope {
    require(command.isNotEmpty()) { "Missing command." }
    require(command.none { it.any { c -> c == '\u0000' || c == '\n' || c == '\r' } }) { "Command argument contains a control character." }
    val trace = Trace.begin(TraceSource.Tools, command.first(), local.ringleader.core.Redact.commandDetails(command), session=true)
    try {
    RunningTools.lastCommand = command
    val process = withContext(Dispatchers.IO) {
        ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File(if (windowsOs()) "NUL" else "/dev/null")))
            .start()
    }
    val pid = process.pid()
    RunningTools.add(pid)
    val limited = java.util.concurrent.atomic.AtomicBoolean(false)
    val reader = launch(Dispatchers.IO) {
        process.inputStream.reader(charset).use { input ->
            val over = readToolLines(input, TOOL_LINE_LIMIT, TOOL_OUTPUT_LIMIT, { isActive }) { line ->
                ensureActive()
                onLine(line)
            }
            if (over) { limited.set(true); killProcessTree(process) }
        }
    }
    try {
        while (process.isAlive) {
            ensureActive()
            delay(40)
        }
        reader.join()
        process.waitFor()
        if (limited.get()) throw ToolOutputLimitException()
        process.exitValue().also { trace.endProcess(it) }
    } catch (e: CancellationException) {
        killProcessTree(process)
        reader.cancel()
        throw e
    } finally {
        if (process.isAlive) killProcessTree(process)
        RunningTools.remove(pid)
        coroutineContext.ensureActive()
    }
    } catch(t: Throwable) { trace.end(error=t); throw t } finally { trace.end() }
}

/**
 * Splits [input] into lines the way [java.io.BufferedReader.readLine] does (`\n`, `\r` or `\r\n`), but never holds more than
 * [lineLimit] characters of one line: the rest of a longer line is dropped and the kept part ends with "…".
 * Returns true when more than [totalLimit] characters were read; reading stops there.
 */
internal fun readToolLines(input: Reader, lineLimit: Int, totalLimit: Long, active: () -> Boolean = { true }, onLine: (String) -> Unit): Boolean {
    val buffer = CharArray(4096)
    val line = StringBuilder()
    var cut = false
    var skipLf = false
    var total = 0L
    fun flush() { onLine(if (cut) line.append('…').toString() else line.toString()); line.setLength(0); cut = false }
    while (active()) {
        val n = input.read(buffer)
        if (n < 0) break
        total += n
        for (i in 0 until n) {
            val c = buffer[i]
            if (skipLf) { skipLf = false; if (c == '\n') continue }
            when {
                c == '\n' -> flush()
                c == '\r' -> { flush(); skipLf = true }
                line.length < lineLimit -> line.append(c)
                else -> cut = true
            }
        }
        if (total > totalLimit) {
            if (line.isNotEmpty() || cut) flush()
            return true
        }
    }
    if (line.isNotEmpty() || cut) flush()
    return false
}

/**
 * The charset Windows console programs (ping, tracert) write when their output is a pipe: the console's code page,
 * which is the OEM code page unless this process has a console of its own. Other systems use the default charset.
 */
internal fun consoleOutputCharset(os: String = System.getProperty("os.name") ?: ""): Charset =
    if (windowsOs(os)) WindowsConsoleCharset.value else Charset.defaultCharset()

private object WindowsConsoleCharset {
    val value: Charset by lazy {
        val own = if (System.console() != null) (System.getProperty("stdout.encoding") ?: System.getProperty("sun.stdout.encoding")) else null
        own?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: registryOemCodePage()?.let(::codePageCharset)
            ?: Charset.defaultCharset()
    }
}

/** Java's name for a Windows code page number, or null when this runtime does not have it. */
internal fun codePageCharset(codePage: Int): Charset? {
    if (codePage == 65001) return Charsets.UTF_8
    for (name in listOf("Cp$codePage", "IBM$codePage", "x-IBM$codePage", "MS$codePage", "windows-$codePage")) {
        runCatching { return Charset.forName(name) }
    }
    return null
}

/** OEMCP from `reg query` output. */
internal fun parseOemCodePage(text: String): Int? =
    Regex("""OEMCP\s+REG_SZ\s+(\d{2,5})""").find(text)?.groupValues?.get(1)?.toIntOrNull()

private fun registryOemCodePage(): Int? = runCatching {
    val root = System.getenv("SystemRoot")?.takeIf { it.isNotBlank() } ?: "C:\\Windows"
    val reg = Path.of(root, "System32", "reg.exe").toString()
    val process = ProcessBuilder(reg, "query", "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Nls\\CodePage", "/v", "OEMCP")
        .redirectErrorStream(true)
        .redirectInput(ProcessBuilder.Redirect.from(File("NUL")))
        .start()
    if (!process.waitFor(3, TimeUnit.SECONDS)) { process.destroyForcibly(); return@runCatching null }
    parseOemCodePage(process.inputStream.readBytes().toString(Charsets.US_ASCII))
}.getOrNull()

internal fun killProcessTree(process: Process) {
    killTree(process.toHandle(), destroyRoot = { runCatching { process.destroyForcibly() } })
    runCatching { if (process.isAlive) process.waitFor(2, TimeUnit.SECONDS) }
}

/**
 * Kills [root]'s descendants, looking again while new ones appear (the root may still be starting them), then the root,
 * then anything the killed descendants started in the meantime. Best effort: a child started in the last instant can escape.
 */
internal fun killTree(root: ProcessHandle, rounds: Int = 20, pause: () -> Unit = { Thread.sleep(25) }, destroyRoot: () -> Unit = { root.destroyForcibly() }) {
    val known = LinkedHashMap<Long, ProcessHandle>()
    fun alive(handle: ProcessHandle) = runCatching { handle.isAlive }.getOrDefault(false)
    fun tree(handle: ProcessHandle) = runCatching { handle.descendants().toList() }.getOrDefault(emptyList())
    /** Kills every live descendant it can see; returns how many were not seen before. */
    fun sweep(): Int {
        val found = (tree(root) + known.values.toList().flatMap(::tree)).filter(::alive)
        var fresh = 0
        for (handle in found) {
            if (known.put(handle.pid(), handle) == null) fresh++
            runCatching { handle.destroyForcibly() }
        }
        return fresh
    }
    var round = 0
    while (round < rounds && sweep() > 0) { round++; pause() }
    destroyRoot()
    round = 0
    while (round < 3 && sweep() > 0) { round++; pause() }
}
