@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.toComposeEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import local.ringleader.core.Workspace
import local.ringleader.core.serverOrigin
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Opt-in, read-only: chart hover and the chart lens on the real Fleet screen against a live server. It selects a running
 * guest and a node in the app (nothing is sent to the guests) and reads history. RINGMASTER_LIVE_PVE=1, PVE_HOST,
 * PVE_TOKEN (id=secret), RINGMASTER_LIVE_PIN, RINGMASTER_LIVE_RENDER_DIR, RINGMASTER_LIVE_LENS_VMID (the guest to inspect).
 * Without RINGMASTER_LIVE_LENS_POINTS it only writes layout pictures; with "x,y;x,y;x,y[;x,y]" (the guest CPU sparkline, a
 * Fleet row sparkline, the node CPU gauge, and optionally the lens's Day range) it hovers and clicks them. Prints states only, never secrets.
 */
class LiveChartLensTest {
    private fun log(s: String) { println("LIVE| $s"); System.out.flush() }

    @Test fun hoverAndLensOnRealCharts() {
        if (System.getenv("RINGMASTER_LIVE_PVE") != "1") return
        val dir = File(System.getenv("RINGMASTER_LIVE_RENDER_DIR") ?: return).apply { mkdirs() }
        val host = System.getenv("PVE_HOST") ?: return
        val raw = System.getenv("PVE_TOKEN")?.removePrefix("PVEAPIToken=") ?: return
        val pin = System.getenv("RINGMASTER_LIVE_PIN") ?: return
        val vmid = System.getenv("RINGMASTER_LIVE_LENS_VMID")?.toIntOrNull() ?: return
        val points = System.getenv("RINGMASTER_LIVE_LENS_POINTS")?.split(';')?.map { p -> p.split(',').let { Offset(it[0].trim().toFloat(), it[1].trim().toFloat()) } }
        val origin = serverOrigin(if (host.startsWith("https://")) host else "https://$host")
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val workspace = Workspace(scope)
        val sceneThread = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            workspace.connect(origin.toString().trimEnd('/'), raw.substringBefore('='), raw.substringAfter('='), pin)
            runBlocking { withTimeout(30_000) { while (workspace.state.value.overview == null) delay(250) } }
            val guest = workspace.state.value.overview!!.guests.single { it.id == vmid }
            log("connected; inspecting ${guest.kind} (status ${guest.status})")
            val scene = runBlocking(sceneThread) { ImageComposeScene(1600, 1000, Density(1f), coroutineContext = sceneThread) {
                val state by workspace.state.collectAsState()
                MaterialTheme(colorScheme = ringColors) {
                    Surface(Modifier.fillMaxSize()) { ChartLensHost(state, workspace) { InventoryScreen(state, workspace, null, "", {}, "All", {}, {}, {}) } }
                }
            } }
            var t = 0L
            fun pump(ms: Long) { var left = ms; while (left > 0) { runBlocking(sceneThread) { scene.render(t) }; t += 50_000_000L; left -= 50; Thread.sleep(50) } }
            fun shot(name: String) { File(dir, name).writeBytes(runBlocking(sceneThread) { scene.render(t) }.use { it.encodeToData()!!.bytes }); log("wrote $name") }
            fun move(p: Offset) = runBlocking(sceneThread) { scene.sendPointerEvent(PointerEventType.Move, p) }
            fun click(p: Offset) = runBlocking(sceneThread) { scene.sendPointerEvent(PointerEventType.Move, p); scene.sendPointerEvent(PointerEventType.Press, p); scene.sendPointerEvent(PointerEventType.Release, p) }
            fun key(vk: Int) = runBlocking(sceneThread) {
                val e = java.awt.event.KeyEvent(object : java.awt.Component() {}, java.awt.event.KeyEvent.KEY_PRESSED, 0L, 0, vk, java.awt.event.KeyEvent.CHAR_UNDEFINED, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD)
                scene.sendKeyEvent(e.toComposeEvent())
            }
            try {
                // Live samples need a few refreshes before the small charts have a line.
                runBlocking(sceneThread) { workspace.select(guest) }
                pump(40_000)
                shot("lens-0-guest-inspector-layout.png")
                if (points == null) {
                    runBlocking(sceneThread) { workspace.selectMember(guest.node) }
                    pump(6_000)
                    shot("lens-0-node-inspector-layout.png")
                    log("layout only; set RINGMASTER_LIVE_LENS_POINTS to hover and click")
                    return
                }
                val (cpu, row, nodeGauge) = points
                move(cpu); pump(400)
                shot("lens-1-hover-guest-cpu.png")
                click(cpu); pump(800)
                shot("lens-2-lens-guest-cpu-live.png")
                log("lens opened on the guest CPU chart")
                // The fourth point is the lens's "Day" range: a real RRD read through Workspace.readHistory.
                points.getOrNull(3)?.let { day -> click(day); pump(4_000); shot("lens-2b-lens-guest-cpu-day.png"); log("Day range shown") }
                key(java.awt.event.KeyEvent.VK_ESCAPE); pump(600)
                shot("lens-3-after-esc.png")
                move(row); pump(400)
                shot("lens-4-hover-fleet-row.png")
                val before = workspace.state.value.selected?.id
                click(row); pump(1_500)
                log("row click: selected ${if (workspace.state.value.selected != null) "a guest" else "nothing"} (was ${if (before != null) "a guest" else "nothing"})")
                runBlocking(sceneThread) { workspace.selectMember(guest.node) }
                pump(6_000)
                click(nodeGauge); pump(800)
                shot("lens-5-lens-node-cpu.png")
                log("lens opened on the node CPU gauge")
            } finally { runBlocking(sceneThread) { scene.close() } }
        } finally { sceneThread.close(); workspace.close(); scope.cancel() }
    }
}
