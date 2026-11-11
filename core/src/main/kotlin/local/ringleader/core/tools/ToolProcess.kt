package local.ringleader.core.tools

import local.ringleader.core.Trace
import local.ringleader.core.TraceSource

import java.io.File
import java.nio.charset.Charset
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

/**
 * Runs [command] as an argument list. Cancelling the coroutine destroys the process and its children.
 * The caller must already have validated every argument.
 */
internal suspend fun runToolProcess(command: List<String>, onLine: (String) -> Unit): Int = coroutineScope {
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
    val reader = launch(Dispatchers.IO) {
        process.inputStream.bufferedReader(Charset.defaultCharset()).use { reader ->
            while (isActive) {
                val line = reader.readLine() ?: break
                ensureActive()
                onLine(line)
            }
        }
    }
    try {
        while (process.isAlive) {
            ensureActive()
            delay(40)
        }
        reader.join()
        process.waitFor()
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

internal fun killProcessTree(process: Process) {
    runCatching { process.descendants().forEach { child -> runCatching { child.destroyForcibly() } } }
    runCatching { process.destroyForcibly() }
    runCatching { if (process.isAlive) process.waitFor(2, TimeUnit.SECONDS) }
}
