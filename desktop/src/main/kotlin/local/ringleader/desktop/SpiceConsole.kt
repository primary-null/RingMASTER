package local.ringleader.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.ringleader.core.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Finds remote-viewer and hands it one SPICE ticket. The `.vv` file holds a one-time password: it is written to a
 * private directory, remote-viewer deletes it after reading (`delete-this-file=1`), and RingMASTER removes it as well
 * when the viewer exits or when the app exits.
 */
object SpiceViewer {
    private val os = System.getProperty("os.name").lowercase()
    private val windows = os.startsWith("windows")
    private val mac = os.contains("mac")

    fun find(): File? {
        System.getenv("RINGMASTER_REMOTE_VIEWER")?.let(::File)?.takeIf { it.canExecute() }?.let { return it }
        val name = if (windows) "remote-viewer.exe" else "remote-viewer"
        System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
            .map { File(it, name) }.firstOrNull { it.canExecute() }?.let { return it }
        if (windows) listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)")).forEach { root ->
            File(root).listFiles { f -> f.isDirectory && f.name.startsWith("VirtViewer") }?.sortedByDescending { it.name }
                ?.map { File(it, "bin/remote-viewer.exe") }?.firstOrNull { it.canExecute() }?.let { return it }
        }
        if (mac) listOf("/opt/homebrew/bin/remote-viewer", "/usr/local/bin/remote-viewer", "/Applications/RemoteViewer.app/Contents/MacOS/RemoteViewer")
            .map(::File).firstOrNull { it.canExecute() }?.let { return it }
        return null
    }

    fun installHint(): String = when {
        windows -> "remote-viewer was not found. Install virt-viewer for Windows (virt-manager.org), then try again."
        mac -> "remote-viewer was not found. Install it with: brew install virt-viewer"
        else -> "remote-viewer was not found. Install the virt-viewer package, then try again."
    }

    /** Starts remote-viewer and waits briefly so an immediate failure can be reported. Returns once the viewer is up. */
    fun launch(ticket: SpiceTicket, onExit: () -> Unit = {}) = launch(ticket, find() ?: error(installHint()), onExit)

    /**
     * The `.vv` file is removed when the viewer exits (it usually removes it itself first, after reading it), or when
     * RingMASTER exits while a viewer is still open. It is never removed on a timer: a slow first start (antivirus,
     * SmartScreen) must still find it.
     */
    internal fun launch(ticket: SpiceTicket, viewer: File, onExit: () -> Unit = {}) {
        val trace = Trace.begin(TraceSource.Console, "SPICE remote-viewer", session=true)
        val dir = try { privateDirectory() } catch(t: Throwable) { trace.end(error=t); throw t }
        val file = dir.resolve("console.vv")
        val pending = dir to file
        try {
            remember(pending)
            createPrivateFile(file)
            Files.writeString(file, ticket.viewerFile())
            val process = ProcessBuilder(viewer.path, file.toString()).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(nullDevice())).start()
            val output = StringBuffer()
            Thread({ process.inputStream.bufferedReader().useLines { lines -> lines.forEach { if (output.length < 2000) output.append(it).append('\n') } } }, "remote-viewer-output").apply { isDaemon = true; start() }
            // When the viewer closes, remove the ticket file and report it on the UI thread (the guest page then
            // captures a fresh last known image).
            Thread({ process.waitFor(); cleanup(pending); trace.endProcess(process.exitValue()); javax.swing.SwingUtilities.invokeLater(onExit) }, "remote-viewer-exit").apply { isDaemon = true; start() }
            if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() != 0) {
                Thread.sleep(100)
                error(viewerExitMessage(process.exitValue(), output.toString(), ticket))
            }
        } catch (e: Exception) {
            trace.end(error=e)
            cleanup(pending)
            throw e
        }
    }

    /**
     * "remote-viewer exited (N): last line". The viewer's own words help (a missing display, a bad option), but a line
     * that repeats the password or proxy ticket, or that the redactor would change, is left out.
     */
    internal fun viewerExitMessage(code: Int, output: String, ticket: SpiceTicket): String {
        val reason = output.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.take(200)
            ?.takeIf { !ticket.mentionsSecret(it) && Redact.text(it) == it }
        return "remote-viewer exited ($code)" + (reason?.let { ": $it" } ?: ".")
    }

    /** Ticket files of viewers that are still open; removed by the viewer's exit thread or at app exit. */
    private val open = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Path, Path>>()
    private val exitHook by lazy { Runtime.getRuntime().addShutdownHook(Thread({ open.toList().forEach(::cleanup) }, "remote-viewer-files")) }
    private fun remember(pending: Pair<Path, Path>) { exitHook; open += pending }
    private fun cleanup(pending: Pair<Path, Path>) { open -= pending; cleanup(pending.first, pending.second) }

    private fun privateDirectory(): Path {
        val probe = Files.createTempDirectory("ringmaster-spice-")
        if (posix(probe)) Files.setPosixFilePermissions(probe, PosixFilePermissions.fromString("rwx------"))
        return probe
    }
    /** Creates an empty ticket file restricted to the current user before any secret is written. */
    internal fun createPrivateFile(file: Path) {
        if (posix(file)) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            Files.createFile(file)
            restrictToCurrentUser(file)
        }
    }
    private fun posix(path: Path) = "posix" in path.fileSystem.supportedFileAttributeViews()
    private fun nullDevice() = File(if (windows) "NUL" else "/dev/null")
    private fun cleanup(dir: Path, file: Path) { runCatching { Files.deleteIfExists(file) }; runCatching { Files.deleteIfExists(dir) } }
}

/** Why the console button is off, or null when a SPICE console can be requested. */
fun spiceRefusal(state: WorkspaceState, guest: Guest): String? = when {
    state.demo -> SPICE_DEMO
    state.kind == PDM_KIND || guest.scope.isNotEmpty() -> "Consoles need a direct Proxmox VE connection."
    guest.kind != "VM" -> SPICE_NOT_VM
    guest.template || guest.status != "running" -> SPICE_NOT_RUNNING
    !state.connected -> "Connect to a server first."
    state.stale -> "Inventory is stale. Sync before opening a console."
    else -> null
}

/** Per-guest launch feedback, shared by the inspector, the detail header and the Actions tab. */
class SpiceLaunch {
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var failed by mutableStateOf(false)
}

private val launches = mutableMapOf<String, SpiceLaunch>()
@Composable fun rememberSpiceLaunch(guest: Guest): SpiceLaunch = remember(guest.resource) { launches.getOrPut(guest.resource.toString()) { SpiceLaunch() } }

private fun spiceError(e: Exception): String = when (e) {
    is SSLException, is java.io.IOException -> readableError(e)
    else -> e.message ?: "The console could not be opened."
}

@Composable fun SpiceConsoleButton(state: WorkspaceState, workspace: Workspace, guest: Guest, label: String = "Console", primary: Boolean = false) {
    if (!LocalAppHost.current.nativeSpice) { HelpTip("Not available in the browser") { androidx.compose.material3.Text("Console unavailable") }; return }
    val launch = rememberSpiceLaunch(guest)
    val scope = rememberCoroutineScope()
    val refusal = spiceRefusal(state, guest)
    HelpTip(refusal ?: "Open the SPICE console in remote-viewer · Shift+F11 full screen · Ctrl+Alt+R releases the cursor") {
        MiniAction(if (launch.busy) "Opening…" else label, primary = primary, enabled = refusal == null && !launch.busy) { launch.open(scope, workspace, guest) }
    }
}

/** Requests a ticket and starts remote-viewer, reporting progress on this guest's shared status line. */
fun SpiceLaunch.open(scope: kotlinx.coroutines.CoroutineScope, workspace: Workspace, guest: Guest) {
    if (busy) return
    busy = true; failed = false; message = "Requesting a SPICE ticket…"
    scope.launch {
        // While the viewer runs the guest has a live console: no automatic still capture until it exits.
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        fun release() { if (released.compareAndSet(false, true)) LiveConsoles.close(guest.resource) }
        var started = false
        try {
            val ticket = workspace.spiceTicket(guest)
            message = "Starting remote-viewer…"
            LiveConsoles.open(guest.resource)
            started = true
            withContext(Dispatchers.IO) { SpiceViewer.launch(ticket) { release(); workspace.captureConsoleStill(guest) } }
            message = "Console open · ${ticket.title.ifBlank { "VM ${guest.id}" }} · Shift+F11 full screen · Ctrl+Alt+R releases the cursor"
        } catch (e: CancellationException) { if (started) release(); message = null; throw e }
        catch (e: SessionExpiredException) { failed = true; message = "The session expired. Reconnect to open a console." }
        catch (e: Exception) { if (started) release(); failed = true; message = spiceError(e) }
        finally { busy = false }
    }
}

@Composable fun SpiceConsoleMessage(guest: Guest) {
    val launch = rememberSpiceLaunch(guest)
    launch.message?.let { Text(it, fontFamily = Tech.Mono, fontSize = 10.sp, lineHeight = 13.sp, color = if (launch.failed) Tech.Amber else Tech.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis) }
}

/** Small monitor glyph for the console action. */
@Composable fun ConsoleGlyph(enabled: Boolean) {
    Canvas(Modifier.size(14.dp)) {
        val c = Tech.Green.copy(alpha = if (enabled) 1f else .3f)
        val w = size.width; val h = size.height
        drawRoundRect(c, Offset(w * .08f, h * .12f), Size(w * .84f, h * .58f), CornerRadius(2f), style = Stroke(1.4.dp.toPx()))
        drawLine(c, Offset(w * .5f, h * .7f), Offset(w * .5f, h * .86f), 1.4.dp.toPx())
        drawLine(c, Offset(w * .28f, h * .9f), Offset(w * .72f, h * .9f), 1.4.dp.toPx())
        drawLine(c, Offset(w * .24f, h * .32f), Offset(w * .38f, h * .41f), 1.2.dp.toPx())
        drawLine(c, Offset(w * .38f, h * .41f), Offset(w * .24f, h * .5f), 1.2.dp.toPx())
    }
}
