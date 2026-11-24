@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.yield
import local.ringleader.core.*
import local.ringleader.core.guesthelper.GuestHelperPairings
import local.ringleader.core.guesthelper.GuestHelperStore
import local.ringleader.core.integrations.ServiceWire
import kotlin.test.Test

/** Opt-in before/after evidence; all data and sessions are synthetic. */
class AuditRepairShotsTest {
    @Test fun affectedScreens() = runSceneTest {
        val output = System.getenv("RINGMASTER_AUDIT_SHOTS")?.let(Path::of) ?: return@runSceneTest
        Files.createDirectories(output)
        val workspace = Workspace(this)
        val node = "docs-node"
        val session = sampleCreateSession(NewGuestKind.Vm, node, 1, Instant.EPOCH)
        val storage = StorageChoice(node, "local", "dir", setOf("iso", "images"), false, 100L, 1000L, "available")
        val context = CreateContext(storages = listOf(storage))
        val iso = StorageVolume("local:iso/debian-13-amd64.iso", "iso", null, 1024, null, null, null, null, null, false, null)
        val populated = session.copy(volumes = mapOf(("local" to "iso") to ReadSection.loaded(listOf(iso), Instant.EPOCH)))
        val guest = Guest("https://pve.example.test:8006", 991, "sample-container", node, "Container", "running")
        val wire = ServiceWire("RingMASTER/audit-fixture")
        val hub = EngineHub(GuestHelperPairings(GuestHelperStore(null), NoVault("Synthetic vault"), wire), wire, { emptyList() })
        val screens = linkedMapOf<String, @Composable () -> Unit>(
            "existing-iso" to {
                CreateVmWizard(session, context, listOf(node), {}, {}, {}, {}, {},
                    onGetMedia = { _, _, _ -> }, initialStep = "os", preferences = CreateWizardPreferences.memory())
            },
            "iso-chooser" to {
                StorageDialog("Get ISO image", {}, width = 640.dp, actions = { TechAction("Close", onClick = {}) }) {
                    TechTabs(listOf("Existing ISOs", "URL", "Upload"), "Existing ISOs", onSelect = {})
                    ExistingIsoChooserContent(populated, listOf(storage), "local", true, {}, {}, {}, {})
                }
            },
            "engine-desktop" to { EngineSetupDialog(hub, workspace, WorkspaceState(), guest, null) },
            "engine-browser" to {
                CompositionLocalProvider(LocalAppHost provides AppHost(true)) {
                    EngineSetupDialog(hub, workspace, WorkspaceState(), guest, null)
                }
            },
        )
        try {
            for ((name, content) in screens) for ((width, height) in listOf(1600 to 900, 960 to 640)) {
                val scene = ImageComposeScene(width, height, coroutineContext = coroutineContext) {
                    MaterialTheme(colorScheme = ringColors) {
                        CompositionLocalProvider(LocalReduceMotion provides true, content = content)
                    }
                }
                try {
                    repeat(4) { scene.render((it + 1) * 16_000_000L).close(); yield() }
                    scene.render(100_000_000L).use { frame ->
                        frame.encodeToData()!!.use { data ->
                            Files.write(output.resolve("$name-${width}x$height.png"), data.bytes)
                        }
                    }
                } finally { scene.close() }
            }
        } finally { workspace.close() }
    }
}
