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
 * private directory, remote-viewer deletes it after reading (`delete-this-file=1`), and RingMASTER removes it as well.
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

    internal fun launch(ticket: SpiceTicket, viewer: File, onExit: () -> Unit = {}) {
        val trace = Trace.begin(TraceSource.Console, "SPICE remote-viewer", session=true)
        val dir = try { privateDirectory() } catch(t: Throwable) { trace.end(error=t); throw t }
        val file = dir.resolve("console.vv")
        try {
            createPrivateFile(file)
            Files.writeString(file, ticket.viewerFile())
            val process = ProcessBuilder(viewer.path, file.toString()).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(nullDevice())).start()
            val output = StringBuffer()
            Thread({ process.inputStream.bufferedReader().useLines { lines -> lines.forEach { if (output.length < 2000) output.append(it).append('\n') } } }, "remote-viewer-output").apply { isDaemon = true; start() }
            Thread({ process.waitFor(30, TimeUnit.SECONDS); cleanup(dir, file) }, "remote-viewer-cleanup").apply { isDaemon = true; start() }
            // When the viewer closes, report it on the UI thread (the guest page then captures a fresh last known image).
            Thread({ process.waitFor(); trace.endProcess(process.exitValue()); javax.swing.SwingUtilities.invokeLater(onExit) }, "remote-viewer-exit").apply { isDaemon = true; start() }
            if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() != 0) {
                Thread.sleep(100)
                val reason = output.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.take(200)
                error("remote-viewer exited (${process.exitValue()})" + (reason?.let { ": $it" } ?: "."))
            }
        } catch (e: Exception) {
            trace.end(error=e)
            cleanup(dir, file)
            throw e
        }
    }

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
        try {
            val ticket = workspace.spiceTicket(guest)
            message = "Starting remote-viewer…"
            withContext(Dispatchers.IO) { SpiceViewer.launch(ticket) { workspace.captureConsoleStill(guest) } }
            message = "Console open · ${ticket.title.ifBlank { "VM ${guest.id}" }} · Shift+F11 full screen · Ctrl+Alt+R releases the cursor"
        } catch (e: CancellationException) { message = null; throw e }
        catch (e: SessionExpiredException) { failed = true; message = "The session expired. Reconnect to open a console." }
        catch (e: Exception) { failed = true; message = spiceError(e) }
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
