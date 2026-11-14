package local.ringleader.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.yield
import local.ringleader.core.Guest
import local.ringleader.core.InspectionSection
import local.ringleader.core.Overview
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState
import local.ringleader.core.demoInspection
import local.ringleader.core.parseHardware
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The console picture must not move while a guest's details load. Every switch in the console rail reads them again:
 * a loading bar that took a row of its own, or a toolbar that grew once the hardware was known, made the whole page
 * and the picture jump on each click.
 */
class ConsoleLayoutStableTest {
    private val origin = "https://192.0.2.10:8006"

    @Test fun thePictureStaysPutWhileDetailsLoadAndWhenASpiceDisplayIsFound() = runSceneTest {
        val a = Guest(origin, 120, "alpha", "node", "VM", "running")
        val b = Guest(origin, 130, "beta", "node", "VM", "running")
        val overview = Overview(1, listOf(a, b))
        val spice = demoInspection(b, "hour").copy(hardware = InspectionSection(parseHardware("""{"data":{"cores":2,"memory":2048,"vga":"qxl","serial0":"socket"}}""")))
        val plain = demoInspection(a, "hour")
        var state by mutableStateOf(WorkspaceState(connected = true, selected = a, overview = overview, inspection = plain))
        val seen = mutableListOf<Rect>()
        consolePictureProbe = { seen += it }
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = Workspace(scope)
        val scene = ImageComposeScene(1400, 900, Density(1f), coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                        Box(Modifier.fillMaxSize()) { GuestDetailPage(state, workspace, {}, { _, _ -> }, initialTab = "Console", onSwitchGuest = {}) }
                    }
                }
            }
        }
        try {
            var time = 0L
            suspend fun frames(count: Int) = repeat(count) { time += 16_000_000L; scene.render(time).close(); yield() }
            frames(4)
            assertTrue(seen.isNotEmpty(), "the picture area was laid out")
            val settled = seen.last()
            assertTrue(settled.width > 600f && settled.height > 400f, "the picture has room: $settled")
            // A switch to B: the selection moves, its details are cleared and start loading, then arrive with a SPICE display.
            state = state.copy(selected = b, inspection = null, inspectionLoading = true)
            frames(4)
            state = state.copy(inspection = spice.copy(hardware = InspectionSection()), inspectionLoading = true)
            frames(3)
            state = state.copy(inspection = spice, inspectionLoading = false)
            frames(4)
            // And back to A, which has no SPICE display.
            state = state.copy(selected = a, inspection = null, inspectionLoading = true)
            frames(4)
            state = state.copy(inspection = plain, inspectionLoading = false)
            frames(4)
            assertEquals(setOf(settled), seen.toSet(), "the picture area moved or changed size during a switch")
        } finally {
            consolePictureProbe = null
            scene.close(); workspace.close(); scope.cancel()
        }
    }
}
