@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.yield
import local.ringleader.core.*
import local.ringleader.core.guesthelper.GuestHelperPairings
import local.ringleader.core.guesthelper.GuestHelperStore
import local.ringleader.core.integrations.ServiceWire
import kotlin.test.*

class EngineRadiosStateSceneTest {
    @Test fun fixedGuestAndBoardFollowConnectionAvailabilityAndHubChanges() = runSceneTest {
        val workspace = Workspace(this)
        val guest = Guest("https://pve.example.test:8006", 991, "fixture", "node", "VM", "running")
        val hardware = parseHardware("""{"data":{"agent":"1","ostype":"l26","meta":"ctime=1"}}""")
        val board = RadiosBoard()
        val base = WorkspaceState(connected = true, address = guest.remote, connectionEpoch = 4)
        val wire = ServiceWire("RingMASTER/state-scene")
        val engine = EngineHub(GuestHelperPairings(GuestHelperStore(null), NoVault("Test"), wire), wire, { emptyList() })
        try {
            for ((width, height) in listOf(1600 to 900, 960 to 640)) {
                var state by mutableStateOf(base.copy(loading = true))
                val availability = GuestAgentAvailability({ state }, { 0L })
                val live = LiveGuestRadios(LiveRadiosFeed(this, workspace, null), workspace, engine, availability)
                val feed = FixedRadiosFeed(board)
                val wifi = FixedWifiFeed(WifiPanel())
                val hub = RadiosHub(feed, wifi, live, engine = engine)
                var shownHub by mutableStateOf(hub)
                val scene = ImageComposeScene(width, height, coroutineContext = coroutineContext) {
                    MaterialTheme(colorScheme = ringColors) {
                        CompositionLocalProvider(LocalReduceMotion provides true, LocalRadios provides shownHub) {
                            GuestRadiosTab(state, workspace, guest, hardware)
                        }
                    }
                }
                try {
                    var tick = 0L
                    suspend fun frame() {
                        repeat(4) { scene.render(++tick * 16_000_000L).close(); yield() }
                    }
                    fun controls(enabled: Boolean) {
                        for (label in listOf("LOOK INSIDE THE VM", "BLUETOOTH SCAN")) {
                            var action = allNodes(scene).firstOrNull { node ->
                                node.config.getOrNull(SemanticsProperties.Text)?.any {
                                    it.text.startsWith(label, ignoreCase = true)
                                } == true
                            }
                            while (action != null && action.config.getOrNull(SemanticsActions.OnClick) == null) {
                                action = action.parent
                            }
                            val button = assertNotNull(action, "Missing $label")
                            assertEquals(!enabled, button.config.getOrNull(SemanticsProperties.Disabled) != null, label)
                        }
                    }
                    fun setupOffered() = allNodes(scene).any { node ->
                        node.config.getOrNull(SemanticsProperties.Text)?.any {
                            it.text.equals(ENGINE_SETUP_LABEL, ignoreCase = true)
                        } == true
                    }
                    frame()
                    controls(enabled = false)
                    assertFalse(setupOffered(), "Loading does not justify Engine setup")
                    state = base
                    frame()
                    controls(enabled = true)
                    for (unavailable in listOf(base.copy(stale = true), base.copy(loading = true), base.copy(connected = false))) {
                        state = unavailable
                        frame()
                        controls(enabled = false)
                        assertFalse(setupOffered(), "Unknown connection state must not offer setup")
                        state = base
                        frame()
                        controls(enabled = true)
                    }
                    assertFailsWith<GuestAgentFailure> {
                        availability.observe(guest) { throw GuestAgentFailure(GuestRefusal.NoPermission) }
                    }
                    frame()
                    controls(enabled = false)
                    assertTrue(setupOffered(), "A typed guest-agent denial offers Engine setup")
                    val replacement = GuestAgentAvailability({ state }, { 0L })
                    replacement.invalidate()
                    assertEquals(availability.version.value, replacement.version.value)
                    shownHub = RadiosHub(feed, wifi,
                        LiveGuestRadios(LiveRadiosFeed(this, workspace, null), workspace, engine, replacement), engine = engine)
                    frame()
                    controls(enabled = true)
                    assertFalse(setupOffered(), "A new hub does not reuse the old hub's refusal")
                    shownHub = hub
                    frame()
                    controls(enabled = false)
                    assertTrue(setupOffered())
                } finally { scene.close() }
            }
        } finally { workspace.close() }
    }
}
