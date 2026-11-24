package local.ringleader.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.awt.ComposeWindow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import local.ringleader.core.*
import local.ringleader.core.guesthelper.*
import local.ringleader.core.integrations.ServiceWire
import kotlin.test.Test
import kotlin.test.assertTrue

/** Separate synthetic native window; never reads the owner's settings or contacts a server. */
class EngineFallbackNativeTest {
    @Test fun reviewFallback() = runBlocking {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("RINGMASTER_ENGINE_NATIVE") == "1")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val workspace = sampleWorkspace(scope)
        val done = CountDownLatch(1)
        workspace.state.first { it.overview != null }
        val guest = workspace.state.value.overview!!.guests.first { it.kind == "VM" && it.status == "running" }
        val hardware = parseHardware("""{"data":{"agent":"1","ostype":"l26"}}""")
        try { workspace.guestAgentAvailability.observe(guest) { throw GuestAgentFailure(GuestRefusal.NoPermission) } }
        catch (e: GuestAgentFailure) { check(e.refusal == GuestRefusal.NoPermission) }
        val wire = ServiceWire("RingMASTER/native-test")
        val bundles = System.getenv("RINGMASTER_NATIVE_BUNDLE")?.let {
            listOf(chosenGuestHelperBundle(java.nio.file.Path.of(it)).getOrThrow())
        }.orEmpty()
        val hub = EngineHub(GuestHelperPairings(GuestHelperStore(null), NoVault("Test"), wire), wire, { bundles })
        lateinit var window: ComposeWindow
        SwingUtilities.invokeAndWait {
            window = ComposeWindow()
            window.title = "RingMASTER Engine fallback review - synthetic"
            window.setSize(1100, 800)
            window.setLocation(100, 100)
            window.defaultCloseOperation = javax.swing.WindowConstants.DISPOSE_ON_CLOSE
            window.addWindowListener(object : WindowAdapter() {
                override fun windowClosed(e: WindowEvent) { done.countDown() }
            })
            window.setContent {
                MaterialTheme(colorScheme = ringColors) {
                    EngineSetupDialog(hub, workspace, workspace.state.value, guest, hardware) { window.dispose() }
                }
            }
            window.isVisible = true
        }
        try { assertTrue(done.await(5, TimeUnit.MINUTES), "Close the review window after inspection") }
        finally { SwingUtilities.invokeAndWait { window.dispose() }; workspace.close(); scope.cancel() }
    }
}
