package local.ringleader.desktop
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ringleader.desktop.web.*
private var desktopServer: WebServer? = null
@Composable internal fun BrowserSettingsSection() {
    var server by remember { mutableStateOf(desktopServer) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    Text("Each browser tab has its own Desk. Clipboard transfer is unavailable.", color = Tech.Muted, fontSize = 12.sp)
    Row {
        TechAction(if (server == null) "Start" else "Stop", active = server == null) {
            copied = false; error = null
            if (server != null) { server?.close(); server = null; desktopServer = null }
            else try { val started = WebServer(WebOptions()); started.start(); server = started; desktopServer = started } catch (e: Exception) { error = "Could not start browser server: ${e.javaClass.simpleName}" }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusTag(if (server != null) "Serving" else "Stopped", server != null)
        server?.let { running ->
            Row(Modifier.fillMaxWidth().border(1.dp, Tech.Edge, Tech.Control).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(running.loginUrl, Modifier.weight(1f).identity(), fontFamily = Tech.Mono, fontSize = 12.sp, maxLines = 2)
                MiniAction(if (copied) "Copied" else "Copy link", primary = !copied) {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(running.loginUrl), null); copied = true
                }
            }
        }
        error?.let { Text(it, color = Tech.Red, fontSize = 12.sp) }
    }
}
