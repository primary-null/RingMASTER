package local.ringleader.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import local.ringleader.core.*
import local.ringleader.core.guesthelper.GuestHelperPairings
import local.ringleader.core.guesthelper.GuestHelperStore
import local.ringleader.core.integrations.ServiceWire
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opt-in native window for manual input review; only synthetic data, no server operations. */
class AuditRepairNativeTest {
    @Test fun reviewNativeWindow() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("RINGMASTER_AUDIT_NATIVE") == "1")
        val finished = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val workspace = Workspace(scope)
        val wire = ServiceWire("RingMASTER/audit-fixture")
        val hub = EngineHub(GuestHelperPairings(GuestHelperStore(null), NoVault("Synthetic vault"), wire), wire, { emptyList() })
        val storage = StorageChoice("docs-node", "local", "dir", setOf("iso"), false, null, null, "available")
        val volume = StorageVolume("local:iso/debian-13-amd64.iso", "iso", null, 1024, null, null, null, null, null, false, null)
        val session = CreateSession(NewGuestKind.Vm, "docs-node", 1, volumes = mapOf(
            ("local" to "iso") to ReadSection.loaded(listOf(volume), Instant.EPOCH),
        ))
        val guest = Guest("https://pve.example.test:8006", 991, "sample-container", "docs-node", "Container", "running")
        lateinit var window: ComposeWindow
        var selected = false
        var refreshed = false
        SwingUtilities.invokeAndWait {
            window = ComposeWindow().apply {
                title = "RingMASTER audit repair review - synthetic data"
                setSize(1100, 800)
                defaultCloseOperation = javax.swing.WindowConstants.DISPOSE_ON_CLOSE
                addWindowListener(object : WindowAdapter() {
                    override fun windowClosed(e: WindowEvent) { finished.countDown() }
                })
                setContent {
                    MaterialTheme(colorScheme = ringColors) {
                        var screen by remember { mutableStateOf("ISO chooser") }
                        var status by remember { mutableStateOf("Choose an existing ISO, then refresh.") }
                        Column {
                            TechTabs(listOf("ISO chooser", "Engine browser"), screen, onSelect = { screen = it })
                            Text(status)
                            if (screen == "ISO chooser") {
                                StorageDialog("Get ISO image", {}, width = 640.dp,
                                    actions = { TechAction("Finish review", onClick = { window.dispose() }) }) {
                                    TechTabs(listOf("Existing ISOs", "URL", "Upload"), "Existing ISOs", onSelect = {})
                                    ExistingIsoChooserContent(session, listOf(storage), "local", true, {}, {}, {
                                        refreshed = true
                                        status = "Refreshed local ISO storage."
                                    }, {
                                        selected = it.volid == volume.volid
                                        status = "Selected ${it.volid}"
                                    })
                                    MiniAction("Review browser Engine") { screen = "Engine browser" }
                                }
                            } else CompositionLocalProvider(LocalAppHost provides AppHost(true)) {
                                EngineSetupDialog(hub, workspace, WorkspaceState(), guest, null) { window.dispose() }
                            }
                        }
                    }
                }
                isVisible = true
            }
        }
        try {
            assertTrue(finished.await(10, TimeUnit.MINUTES), "Native review was not completed")
            assertTrue(selected, "Existing ISO was selected with native input")
            assertTrue(refreshed, "Refresh was invoked with native input")
        } finally {
            SwingUtilities.invokeAndWait { window.dispose() }
            workspace.close()
            scope.cancel()
        }
    }
}
