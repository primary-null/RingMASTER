@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import local.ringleader.core.Connection
import local.ringleader.core.Guest
import local.ringleader.core.GuestDetails
import local.ringleader.core.InventoryGateway
import local.ringleader.core.Overview
import local.ringleader.core.ResourceId
import local.ringleader.core.VncConsoleGateway
import local.ringleader.core.VncSession
import local.ringleader.core.Workspace
import local.ringleader.core.solidFrameSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A console that was left and opened again inside the keep time draws its live picture on the first frame,
 * without a second ticket and without the power-on reveal. Reconnect is the path that starts over.
 */
class WarmConsoleUiTest {
    private val origin = "https://192.0.2.10:8006"

    @Test fun aKeptConsoleReturnsWithItsPictureAndNoReveal() = runBlocking<Unit> {
        val savedKeep = ConsoleKeepPreference.minutes
        ConsoleKeepPreference.preview(10)
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val alpha = Guest(origin, 1, "alpha", "node", "VM", "running")
        val beta = Guest(origin, 2, "beta", "node", "VM", "running")
        val gateway = CountingGateway()
        val workspace = Workspace(scope, Inventory(listOf(alpha, beta)), vncGateway = gateway)
        val reveals = mutableListOf<Pair<ResourceId, Boolean>>()
        consoleRevealProbe = { id, fresh -> reveals += id to fresh }
        try {
            workspace.connect(Connection(origin, "tester@pve!read", "secret", ""))
            workspace.setLive(false)
            check(workspace.state.value.overview?.guests?.size == 2) {
                "overview did not load (${workspace.state.value.error})"
            }
            var current by mutableStateOf(alpha)
            val scene = ImageComposeScene(800, 500, Density(1f), coroutineContext = coroutineContext) {
                MaterialTheme(colorScheme = ringColors) {
                    CompositionLocalProvider(LocalReduceMotion provides true) {
                        Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                            val state by workspace.state.collectAsState()
                            Box(Modifier.fillMaxSize()) {
                                key(current.resource) {
                                    VncConsoleView(state, workspace, current, Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
            }
            try {
                var time = 0L
                suspend fun render() { time += 16_000_000L; scene.render(time).close(); yield() }
                suspend fun until(label: String, done: () -> Boolean) {
                    repeat(40) { if (done()) return; render() }
                    check(done()) { label }
                }
                until("A opened") { gateway.count(alpha.resource) == 1 }
                assertTrue(reveals.any { it.first == alpha.resource && it.second }, "the first connection asks for the reveal")
                current = beta
                until("B opened and A stayed kept") {
                    gateway.count(beta.resource) == 1 && alpha.resource in workspace.state.value.keptConsoles
                }
                assertEquals(1, gateway.count(alpha.resource), "leaving A did not open it again")
                current = alpha
                time += 16_000_000L
                val color = scene.render(time).use { image ->
                    org.jetbrains.skia.Bitmap.makeFromImage(image).use { bitmap -> bitmap.getColor(400, 260) }
                }
                val texts = semanticNodes(scene).map { semanticText(it) }
                assertFalse(
                    texts.any { it == "CONNECTING…" || it.startsWith("Connecting") },
                    "the kept return flashed a connecting state: ${texts.filter { it.isNotEmpty() }}",
                )
                assertEquals(1, gateway.count(alpha.resource), "coming back used the kept session")
                assertEquals(false, reveals.last { it.first == alpha.resource }.second, "a kept return does not replay the reveal")
                val red = color shr 16 and 255
                val green = color shr 8 and 255
                val blue = color and 255
                assertTrue(red > 150 && red > green && red > blue, "the first frame was not A's live picture (${color.toUInt().toString(16)})")
                yield()
                click(scene, "RECONNECT")
                until("Reconnect opened a fresh session") {
                    gateway.count(alpha.resource) == 2 && reveals.lastOrNull { it.first == alpha.resource }?.second == true
                }
            } finally { scene.close() }
        } finally {
            consoleRevealProbe = null
            ConsoleKeepPreference.preview(savedKeep)
            workspace.close()
            scope.cancel()
        }
    }

    private class Inventory(private val guests: List<Guest>) : InventoryGateway {
        override suspend fun overview(connection: Connection) = Overview(1, guests)
        override suspend fun details(connection: Connection, guest: Guest): GuestDetails = error("not used")
    }

    private class CountingGateway : VncConsoleGateway {
        val opens = mutableListOf<ResourceId>()
        override suspend fun open(connection: Connection, guest: Guest): VncSession {
            opens += guest.resource
            val color = if (guest.id == 1) 0xFFCC3344.toInt() else 0xFF2244CC.toInt()
            return solidFrameSession(argb = color)
        }
        fun count(id: ResourceId) = opens.count { it == id }
    }
}

private fun semanticNodes(scene: ImageComposeScene): List<SemanticsNode> {
    val out = ArrayList<SemanticsNode>()
    fun visit(node: SemanticsNode) {
        out.add(node)
        node.children.forEach { visit(it) }
    }
    scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
    return out
}

private fun semanticText(node: SemanticsNode): String =
    node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

private fun click(scene: ImageComposeScene, label: String) {
    val node = semanticNodes(scene).firstOrNull { semanticText(it) == label } ?: error("no $label")
    val at = node.boundsInRoot.center
    scene.sendPointerEvent(PointerEventType.Move, Offset(at.x, at.y))
    scene.sendPointerEvent(PointerEventType.Press, Offset(at.x, at.y))
    scene.sendPointerEvent(PointerEventType.Release, Offset(at.x, at.y))
}
