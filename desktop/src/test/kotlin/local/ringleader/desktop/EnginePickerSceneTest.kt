@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.yield
import local.ringleader.core.Guest
import local.ringleader.core.NoVault
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState
import local.ringleader.core.guesthelper.GuestHelperPairings
import local.ringleader.core.guesthelper.GuestHelperStore
import local.ringleader.core.integrations.ServiceWire
import kotlin.test.Test
import kotlin.test.assertEquals

class EnginePickerSceneTest {
    @Test fun bundlePickerIsOnlyInDesktopScene() = runSceneTest {
        val workspace = Workspace(this)
        val guest = Guest("https://pve.example.test:8006", 991, "sample", "node", "Container", "running")
        val wire = ServiceWire("RingMASTER/test")
        val hub = EngineHub(GuestHelperPairings(GuestHelperStore(null), NoVault("Test"), wire), wire, { emptyList() })
        try {
            for (web in listOf(false, true)) {
                val scene = ImageComposeScene(960, 640, coroutineContext = coroutineContext) {
                    MaterialTheme(colorScheme = ringColors) {
                        CompositionLocalProvider(LocalReduceMotion provides true, LocalAppHost provides AppHost(web)) {
                            EngineSetupDialog(hub, workspace, WorkspaceState(), guest, null)
                        }
                    }
                }
                try {
                    repeat(4) { scene.render((it + 1) * 16_000_000L).close(); yield() }
                    val texts = mutableListOf<String>()
                    fun visit(node: SemanticsNode) {
                        node.config.getOrNull(SemanticsProperties.Text)?.forEach { texts += it.text }
                        node.children.forEach(::visit)
                    }
                    scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
                    assertEquals(!web, texts.any { it.startsWith("Choose a bundle file", ignoreCase = true) })
                } finally { scene.close() }
            }
        } finally { workspace.close() }
    }
}
