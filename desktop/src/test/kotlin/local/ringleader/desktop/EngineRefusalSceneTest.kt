@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield
import local.ringleader.core.*
import local.ringleader.core.guesthelper.*
import local.ringleader.core.integrations.ServiceWire
import kotlin.test.*

class EngineRefusalSceneTest {
    @Test fun deniedVmOffersManualWaysAtBothSizes() = runSceneTest {
        val workspace = sampleWorkspace(this)
        val file = Files.createTempFile("engine-scene-", ".tgz")
        try {
            Files.writeString(file, "Synthetic scene fixture; never installed")
            workspace.state.first { it.overview != null }
            val guest = workspace.state.value.overview!!.guests.first { it.kind == "VM" && it.status == "running" }
            val config = parseHardware("""{"data":{"agent":"1","ostype":"l26"}}""")
            assertFailsWith<GuestAgentFailure> {
                workspace.guestAgentAvailability.observe(guest) { throw GuestAgentFailure(GuestRefusal.NoPermission) }
            }
            val wire = ServiceWire("RingMASTER/scene")
            val bundle = GuestHelperBundle("x86_64", "0.1.0", file, "a".repeat(64), true)
            val hub = EngineHub(GuestHelperPairings(GuestHelperStore(null), NoVault("Test"), wire), wire, { listOf(bundle) })
            for ((width, height) in listOf(1600 to 900, 960 to 640)) {
                val scene = ImageComposeScene(width, height, coroutineContext = coroutineContext) {
                    MaterialTheme(colorScheme = ringColors) {
                        CompositionLocalProvider(LocalReduceMotion provides true) {
                            EngineSetupDialog(hub, workspace, workspace.state.value, guest, config)
                        }
                    }
                }
                try {
                    repeat(4) { scene.render((it + 1) * 16_000_000L).close(); yield() }
                    val nodes = mutableListOf<SemanticsNode>()
                    val texts = mutableListOf<String>()
                    fun visit(node: SemanticsNode) {
                        nodes += node
                        node.config.getOrNull(SemanticsProperties.Text)?.forEach { texts += it.text }
                        node.children.forEach(::visit)
                    }
                    scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
                    assertFalse(texts.any { it.equals("Install through the guest agent", ignoreCase = true) })
                    assertTrue(texts.any { it.equals("Start a one-time link", ignoreCase = true) })
                    assertTrue(texts.any { it.equals("Pair by hand", ignoreCase = true) })
                    assertTrue(texts.any { it.contains(GuestRefusal.NoPermission.sentence) })
                    val folder = java.io.File("../artifacts/engine-fallback/after-denied").apply { mkdirs() }
                    java.io.File(folder, "vm-${width}x$height.png").writeBytes(scene.render().encodeToData()!!.bytes)
                    nodes.firstOrNull { it.config.getOrNull(SemanticsActions.ScrollBy) != null }?.let {
                        it.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 10000f)
                        repeat(4) { n -> scene.render((n + 10) * 16_000_000L).close(); yield() }
                        java.io.File(folder, "vm-${width}x$height-bottom.png").writeBytes(scene.render().encodeToData()!!.bytes)
                    }
                } finally { scene.close() }
            }
        } finally { workspace.close(); Files.deleteIfExists(file) }
    }
}
