@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import java.awt.event.KeyEvent as AwtKey
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in: the console switcher and immersive mode against a live server, on the real Fleet screen opened on a VM's
 * Console tab. Needs two running VMs the tester owns. RINGMASTER_LIVE_PVE=1, RINGMASTER_LIVE_SWITCH_VMIDS=<a>,<b>,
 * PVE_HOST, PVE_TOKEN (id=secret), RINGMASTER_LIVE_PIN (the server certificate's SHA-256), RINGMASTER_LIVE_RENDER_DIR.
 * Nothing is typed into a guest except the release of Ctrl and Alt that a switch sends. Prints states only, never secrets.
 */
class LiveConsoleSwitchTest {
    private fun log(s: String) { println("LIVE| $s"); System.out.flush() }

    @Test fun switchesConsolesAndGoesImmersive() {
        if (System.getenv("RINGMASTER_LIVE_PVE") != "1") return
        val ids = System.getenv("RINGMASTER_LIVE_SWITCH_VMIDS")?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return
        require(ids.size == 2) { "two VMIDs" }
        val dir = File(System.getenv("RINGMASTER_LIVE_RENDER_DIR") ?: return).apply { mkdirs() }
        val host = System.getenv("PVE_HOST") ?: return
        val raw = System.getenv("PVE_TOKEN")?.removePrefix("PVEAPIToken=") ?: return
        val pin = System.getenv("RINGMASTER_LIVE_PIN") ?: return
        val origin = serverOrigin(if (host.startsWith("https://")) host else "https://$host")
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val workspace = Workspace(scope)
        val sceneThread = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            workspace.connect(origin.toString().trimEnd('/'), raw.substringBefore('='), raw.substringAfter('='), pin)
            runBlocking { withTimeout(30_000) { while (workspace.state.value.overview?.guests?.count { it.id in ids && it.status == "running" } != 2) delay(250) } }
            val (a, b) = ids.map { id -> workspace.state.value.overview!!.guests.single { it.id == id } }
            log("connected; both fixture VMs running")
            val immersive = ImmersiveState()
            var request by mutableStateOf<FleetRequest?>(FleetRequest(a.resource, "Console", System.nanoTime()))
            val scene = runBlocking(sceneThread) { ImageComposeScene(1600, 1000, Density(1f), coroutineContext = sceneThread) {
                val state by workspace.state.collectAsState()
                MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalImmersive provides immersive) {
                    Surface(Modifier.fillMaxSize()) {
                        InventoryScreen(state, workspace, null, "", {}, "All", {}, {}, {}, fleetRequest = request, onFleetHandled = { request = null })
                    }
                } }
            } }
            var t = 0L
            fun pump(ms: Long) { var left = ms; while (left > 0) { runBlocking(sceneThread) { scene.render(t) }; t += 50_000_000L; left -= 50; Thread.sleep(50) } }
            fun pumpUntil(limitMs: Long, done: () -> Boolean): Boolean { var left = limitMs; while (left > 0 && !done()) { pump(100); left -= 100 }; return done() }
            fun shot(name: String) { File(dir, name).writeBytes(runBlocking(sceneThread) { scene.render(t) }.use { it.encodeToData()!!.bytes }); log("wrote $name") }
            fun click(x: Float, y: Float) = runBlocking(sceneThread) {
                scene.sendPointerEvent(PointerEventType.Move, Offset(x, y)); scene.sendPointerEvent(PointerEventType.Press, Offset(x, y)); scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
            }
            fun move(x: Float, y: Float) = runBlocking(sceneThread) { scene.sendPointerEvent(PointerEventType.Move, Offset(x, y)) }
            val ctrlAlt = AwtKey.CTRL_DOWN_MASK or AwtKey.ALT_DOWN_MASK
            fun key(vk: Int, id: Int) = AwtKey(object : java.awt.Component() {}, id, 0L, ctrlAlt, vk, AwtKey.CHAR_UNDEFINED, AwtKey.KEY_LOCATION_STANDARD).toComposeEvent()
            fun press(vk: Int) = runBlocking(sceneThread) { scene.sendKeyEvent(key(vk, AwtKey.KEY_PRESSED)); scene.sendKeyEvent(key(vk, AwtKey.KEY_RELEASED)) }
            fun selected() = workspace.state.value.selected?.id
            try {
                assertTrue(pumpUntil(15_000) { selected() == a.id && LiveConsoles.isOpen(a.resource) }, "the page opened on A's console")
                pump(6_000)
                shot("switch-1-console-a.png")
                // Capture the keyboard with a click on the picture (right of the rail).
                click(1000f, 620f); pump(300)
                assertTrue(VncKeyboard.captured, "a click on the screen captures the keyboard")
                shot("switch-2-captured-hint.png")
                pump(3_400)
                shot("switch-3-hint-gone-chip.png")
                log("captured; hint shown then gone")
                // Captured: Ctrl+Alt+PageDown goes to the next guest in the rail.
                press(AwtKey.VK_PAGE_DOWN)
                assertTrue(pumpUntil(10_000) { selected() == b.id }, "Ctrl+Alt+PageDown moved to B")
                assertTrue(pumpUntil(10_000) { workspace.state.value.consoleStills[a.resource] != null }, "A's last frame became its thumbnail")
                pump(6_000)
                shot("switch-4-console-b-thumb-a.png")
                log("switched A to B while captured; A's frame kept: ${workspace.state.value.consoleStills[a.resource]?.let { "${it.width}x${it.height} blank=${it.blank}" }}")
                // Immersive with Ctrl+Alt+Enter, captured again first.
                click(1000f, 620f); pump(300)
                press(AwtKey.VK_ENTER); pump(600)
                assertTrue(immersive.active, "Ctrl+Alt+Enter turned immersive on")
                shot("switch-5-immersive-bar.png")
                pump(2_800)
                shot("switch-6-immersive-bar-hidden.png")
                move(800f, 1f); pump(500)
                shot("switch-7-immersive-bar-at-top-edge.png")
                move(800f, 500f); pump(300)
                press(AwtKey.VK_PAGE_UP)
                assertTrue(pumpUntil(10_000) { selected() == a.id }, "Ctrl+Alt+PageUp moved back to A in immersive")
                assertTrue(immersive.active, "immersive stays on across a switch")
                pump(5_000)
                shot("switch-8-immersive-a.png")
                click(1000f, 500f); pump(300)
                press(AwtKey.VK_ENTER); pump(600)
                assertFalse(immersive.active, "Ctrl+Alt+Enter turned immersive off")
                shot("switch-9-immersive-off.png")
                // Not captured: the window's key handler (Main.kt pageKeys) reaches the page through ConsoleKeys.
                // A press on the console toolbar (its status text) releases the keyboard; the page's top-left is "‹ Fleet".
                click(300f, 114f); pump(300)
                assertFalse(VncKeyboard.captured, "a click outside the screen released the keyboard")
                val handled = runBlocking(sceneThread) { ConsoleKeys.handler?.invoke(key(AwtKey.VK_PAGE_DOWN, AwtKey.KEY_PRESSED)) }
                assertEquals(true, handled, "the page takes Ctrl+Alt+PageDown when the console does not hold the keyboard")
                assertTrue(pumpUntil(10_000) { selected() == b.id }, "moved to B without capture")
                pump(3_000)
                shot("switch-10-uncaptured-b.png")
                assertNotNull(workspace.state.value.consoleStills[b.resource], "B's frame kept too")
                log("all steps passed")
            } finally { runBlocking(sceneThread) { scene.close() } }
        } finally { sceneThread.close(); workspace.close(); scope.cancel() }
    }
}
