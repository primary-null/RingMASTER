package local.ringleader.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import local.ringleader.core.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/** Opt-in render of the showcase blur: RINGMASTER_PROBE=1 and RINGMASTER_PROBE_DIR. */
class ShowcaseProbeTest {
    @Test fun probe() {
        if (System.getenv("RINGMASTER_PROBE") != "1") return
        val dir = File(System.getenv("RINGMASTER_PROBE_DIR") ?: return).apply { mkdirs() }
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = Workspace(scope); workspace.exploreDemo()
        val desk = ProfileDesk(workspace, ProfileStore(Files.createTempDirectory("rm").resolve("p.json")))
        val hit = ScanHit("192.168.50.21", 8006, true, ScanKind.PVE, "Proxmox VE", "AB:CD")
        val showcase = System.getenv("PROBE_SHOWCASE") != "0"
        if (System.getenv("PROBE_GRAPHS") == "1") {
            workspace.select(workspace.state.value.overview!!.guests.first()); workspace.inspectGuest("hour")
            runBlocking { delay(1500) }
        }
        val tools = ToolsSession(CoroutineScope(Dispatchers.Default + SupervisorJob())).also { it.reload() }
        val screens = if (System.getenv("PROBE_TOOLS") == "1") listOf<Pair<String, @androidx.compose.runtime.Composable () -> Unit>>(
            "tools" to { LaunchedEffect(Unit) { System.getenv("PROBE_TOOL")?.let { id -> tools.select(id); tools.draft(tools.plugins.first { it.id == id }).also { it["reverse"] = "true" }; return@LaunchedEffect }; tools.select("ping"); tools.draft(tools.plugins.first { it.id == "ping" }).also { it["host"] = "127.0.0.1"; it["count"] = "6" }; tools.requestRun(tools.plugins.first { it.id == "ping" }) }; ToolsScreen(tools) })
        else if (System.getenv("PROBE_GRAPHS") == "1") listOf<Pair<String, @androidx.compose.runtime.Composable () -> Unit>>(
            "graphs-guest" to { val st by workspace.state.collectAsState(); GuestDetailPage(st, workspace, {}, { _, _ -> }) },
            "graphs-node" to { val st by workspace.state.collectAsState(); LaunchedEffect(Unit) { workspace.selectMember(st.overview!!.members.first().name) }; InventoryScreen(st, workspace, null, "", {}, "All", {}, {}, {}) })
        else listOf<Pair<String, @androidx.compose.runtime.Composable () -> Unit>>(
            "desk" to { DeskScreen(workspace.state.value, desk.state.value, desk, hit) {} },
            "fleet" to { InventoryScreen(workspace.state.value, workspace, null, "", {}, "All", {}, {}, {}) })
        for ((name, content) in screens) {
            Appearance.preview(false, showcase = showcase)
            val scene = ImageComposeScene(1800, 1100, Density(1.25f)) {
                MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                    Surface(Modifier.fillMaxSize()) { androidx.compose.foundation.layout.Box(Modifier.padding(16.dp)) { content() } }
                } }
            }
            var t = 0L; repeat(if (name == "tools") 40 else 12) { scene.render(t); t += 250_000_000L; Thread.sleep(if (name == "tools") 200 else 120) }
            File(dir, "showcase-$name.png").writeBytes(scene.render(1_000_000_000).encodeToData()!!.bytes)
            scene.close()
        }
        Appearance.preview(false); desk.close(); workspace.close(); scope.cancel()
    }
}
