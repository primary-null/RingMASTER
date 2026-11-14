package local.ringleader.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import local.ringleader.core.*
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.*

/**
 * Opt-in: renders the real guest page on its Console tab against a live server. RINGMASTER_LIVE_PVE=1,
 * RINGMASTER_LIVE_VNC_VMID, PVE_HOST, PVE_TOKEN, RINGMASTER_LIVE_RENDER_DIR. Prints sizes only, never secrets.
 */
class LiveConsoleRenderTest {
    @Test fun consoleTabRendersALiveScreen() {
        if (System.getenv("RINGMASTER_LIVE_PVE") != "1") return
        val vmid = System.getenv("RINGMASTER_LIVE_VNC_VMID")?.toIntOrNull() ?: return
        val dir = File(System.getenv("RINGMASTER_LIVE_RENDER_DIR") ?: return).apply { mkdirs() }
        val host = System.getenv("PVE_HOST") ?: return
        val raw = System.getenv("PVE_TOKEN")?.removePrefix("PVEAPIToken=") ?: return
        val origin = serverOrigin(if (host.startsWith("https://")) host else "https://$host")
        val port = if (origin.port > 0) origin.port else 8006
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        })
        val ctx = SSLContext.getInstance("TLS").apply { init(null, trustAll, null) }
        val pin = (ctx.socketFactory.createSocket(origin.host, port) as SSLSocket).use { s ->
            s.startHandshake(); MessageDigest.getInstance("SHA-256").digest(s.session.peerCertificates[0].encoded).joinToString("") { "%02x".format(it) }
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val workspace = Workspace(scope)
        try {
            workspace.connect("https://${origin.host}:$port", raw.substringBefore('='), raw.substringAfter('='), pin)
            runBlocking { withTimeout(20_000) { while (workspace.state.value.overview == null) delay(200) } }
            val guest = workspace.state.value.overview!!.guests.single { it.id == vmid }
            workspace.select(guest); workspace.inspectGuest("hour")
            Appearance.preview(false)
            val tab = System.getenv("RINGMASTER_LIVE_TAB") ?: "Console"
            val nodeView = tab == "Node"
            // Like browser mode: one thread both runs the scene's effects and draws it.
            val sceneThread = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val scene = runBlocking(sceneThread) { ImageComposeScene(1800, 1100, Density(1.25f), coroutineContext = sceneThread) {
                val state by workspace.state.collectAsState()
                MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                    Surface(Modifier.fillMaxSize()) { androidx.compose.foundation.layout.Box(Modifier.padding(16.dp)) {
                        if (tab == "Activity") {
                            androidx.compose.runtime.LaunchedEffect(Unit) {
                                workspace.refreshTasks()
                                kotlinx.coroutines.delay(4000)
                                workspace.state.value.tasks.orEmpty().firstOrNull { it.type == "vzcreate" && it.subjectId == guest.id.toString() }?.let { workspace.openTaskLog(it) }
                            }
                            ActivityScreen(state, workspace, null, {}, {})
                        } else if (nodeView) { androidx.compose.runtime.LaunchedEffect(Unit) { workspace.selectMember(guest.node) }; InventoryScreen(state, workspace, null, "", {}, "All", {}, {}, {}) }
                        else GuestDetailPage(state, workspace, {}, { _, _ -> }, initialTab = tab)
                    } }
                } }
            } }
            try {
                var t = 0L
                repeat(60) { runBlocking(sceneThread) { scene.render(t) }; t += 200_000_000L; Thread.sleep(200) }
                val name = "live-${tab.lowercase()}.png"
                File(dir, name).writeBytes(runBlocking(sceneThread) { scene.render(t) }.encodeToData()!!.bytes)
                println("LIVE-RENDER wrote $name")
            } finally { runBlocking(sceneThread) { scene.close() }; sceneThread.close() }
        } finally { workspace.close(); scope.cancel() }
    }
}
