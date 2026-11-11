package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import local.ringleader.core.DEMO_REMOTE_KEY
import local.ringleader.core.Guest

@Composable fun LocalEngineProof(guest: Guest) {
    if (!LocalAppHost.current.embeddedConsole) { Text("Not available in the browser"); return }
    val scope = rememberCoroutineScope()
    var browser by remember(guest.resource) { mutableStateOf<org.cef.browser.CefBrowser?>(null) }
    var busy by remember(guest.resource) { mutableStateOf(false) }
    var error by remember(guest.resource) { mutableStateOf("") }
    val ticket = remember(guest.resource) { ConsoleHost.begin() }
    DisposableEffect(ticket) { onDispose { ConsoleHost.release(ticket) } }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionLabel("Local engine proof")
        Text("${guest.kind.uppercase()} ${guest.id} · ${guest.name.ifBlank { "Guest ${guest.id}" }}", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text("Route ${guest.node} · ${if (guest.remote == DEMO_REMOTE_KEY) "Sample data" else guest.remote}",
            fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Tech.Muted)
        Text("This is a local keyboard echo inside RingMASTER. It is not a live console, shell, or guest display. Nothing is sent to the guest. The engine starts only when you ask, and the first start can download Chromium into your user cache.")
        val current = browser
        if (current == null) {
            TechAction(if (busy) "Starting local engine…" else "Start local engine", active = true, enabled = !busy, onClick = {
                busy = true
                error = ""
                scope.launch {
                    try {
                        val started = withContext(Dispatchers.IO) { ConsoleHost.start(ticket) }
                        if (!isActive || !ConsoleHost.isCurrent(ticket)) ConsoleHost.release(ticket)
                        else browser = started
                    } catch (e: CancellationException) {
                        ConsoleHost.release(ticket)
                        throw e
                    } catch (_: Exception) {
                        if (isActive) error = "The local engine could not start. Check network access and Chromium runtime dependencies."
                    } finally { busy = false }
                }
            })
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        } else SwingPanel(factory = { current.uiComponent }, modifier = Modifier.fillMaxWidth().weight(1f))
    }
}
