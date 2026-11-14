@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import local.ringleader.core.Guest
import local.ringleader.core.InspectionSection
import local.ringleader.core.Overview
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState
import local.ringleader.core.demoInspection
import local.ringleader.core.parseHardware
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for fullscreen VM console immersive behavior at InventoryScreen integration level.
 *
 * Verifies:
 * 1. Creation row ("New VM" / "New container") disappears when immersive mode is active in InventoryScreen.
 * 2. Console picture reaches the true top edge (y=0) when immersive is active.
 * 3. Creation actions return when exiting immersive mode.
 * 4. Console state (e.g. fit toggle) is preserved across immersive enter/exit cycles.
 * 5. Toolbar auto-hides after initial hold, and hovering the top edge reveals the toolbar.
 * 6. Fullscreen exit toolbar control is visible, on-screen, and clickable via mouse pointer at narrow and high-DPI widths
 *    (preventing the regression where it was pushed offscreen inside the horizontal-scrolling row).
 */
class InventoryConsoleImmersiveTest {
    private val origin = "https://192.0.2.10:8006"

    private fun allNodes(scene: ImageComposeScene): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) {
            add(node)
            node.children.forEach(::visit)
        }
        scene.semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }

    private fun nodeText(node: SemanticsNode): String =
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun creationNodes(scene: ImageComposeScene): List<SemanticsNode> =
        allNodes(scene).filter { node ->
            val text = nodeText(node)
            text.contains("New VM", ignoreCase = true) || text.contains("New container", ignoreCase = true)
        }

    private fun findAction(scene: ImageComposeScene, label: String): SemanticsNode? =
        allNodes(scene).firstOrNull { node ->
            nodeText(node).equals(label, ignoreCase = true) && hasClick(node)
        }

    private fun findActionContaining(scene: ImageComposeScene, substring: String): SemanticsNode? =
        allNodes(scene).firstOrNull { node ->
            nodeText(node).contains(substring, ignoreCase = true) && hasClick(node)
        }

    private fun hasClick(node: SemanticsNode): Boolean {
        var current: SemanticsNode? = node
        while (current != null) {
            if (current.config.getOrNull(SemanticsActions.OnClick) != null) return true
            current = current.parent
        }
        return false
    }

    private fun performClick(node: SemanticsNode): Boolean {
        var current: SemanticsNode? = node
        while (current != null) {
            val click = current.config.getOrNull(SemanticsActions.OnClick)
            if (click != null) return click.action!!.invoke()
            current = current.parent
        }
        return false
    }

    @Test
    fun creationRowLeavesWithImmersiveAndConsoleReachesTopEdge() = runSceneTest {
        val vm = Guest(origin, 120, "alpha-vm", "pve1", "VM", "running")
        val overview = Overview(1, listOf(vm))
        val inspection = demoInspection(vm, "hour")
        val state by mutableStateOf(WorkspaceState(connected = true, selected = vm, overview = overview, inspection = inspection))
        val immersive = ImmersiveState()
        var fleetRequest by mutableStateOf<FleetRequest?>(FleetRequest(vm.resource, "Console", 1L))
        val probeHistory = mutableListOf<Rect>()
        consolePictureProbe = { probeHistory += it }

        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = Workspace(scope)
        val scene = ImageComposeScene(1400, 900, Density(1f), coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides true, LocalImmersive provides immersive) {
                    Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                        InventoryScreen(
                            state = state,
                            workspace = workspace,
                            sessionTitle = null,
                            search = "",
                            onSearch = {},
                            filter = "All",
                            onFilter = {},
                            onConnect = {},
                            onDisconnect = {},
                            fleetRequest = fleetRequest,
                            onFleetHandled = { fleetRequest = null }
                        )
                    }
                }
            }
        }

        try {
            var time = 0L
            suspend fun frames(count: Int) = repeat(count) { time += 16_000_000L; scene.render(time).close(); yield() }

            frames(6)

            // 1. In normal mode: creation actions are present and console picture is below headers/creation row
            val initialCreation = creationNodes(scene)
            assertTrue(initialCreation.isNotEmpty(), "Creation actions ('New VM' / 'New container') must be visible in normal mode")
            assertTrue(probeHistory.isNotEmpty(), "Console picture should be probed in normal mode")
            val normalTop = probeHistory.last().top
            assertTrue(normalTop > 20f, "Console picture top must be below the creation row and headers in normal mode: top=$normalTop")

            // 2. In immersive mode: creation row disappears and console picture reaches the true top edge (y=0)
            immersive.enter()
            frames(6)

            assertTrue(immersive.active, "Immersive state should be active")
            val immersiveCreation = creationNodes(scene)
            assertTrue(immersiveCreation.isEmpty(), "Creation row must disappear in immersive mode, but found: ${immersiveCreation.map(::nodeText)}")
            val immersiveTop = probeHistory.last().top
            assertEquals(0f, immersiveTop, "Console picture must reach the true top edge (y=0) in immersive mode")

            // 3. Exiting immersive mode: creation actions return and console picture moves back down
            immersive.exit()
            frames(6)

            assertFalse(immersive.active, "Immersive state should no longer be active")
            val returnedCreation = creationNodes(scene)
            assertTrue(returnedCreation.isNotEmpty(), "Creation actions ('New VM' / 'New container') must return when exiting immersive mode")
            val returnedTop = probeHistory.last().top
            assertTrue(returnedTop > 20f, "Console picture must return below the creation row after exiting immersive mode: top=$returnedTop")
        } finally {
            consolePictureProbe = null
            scene.close()
            workspace.close()
            scope.cancel()
        }
    }

    @Test
    fun consoleStatePreservedAcrossImmersiveTransitions() = runSceneTest {
        val vm = Guest(origin, 120, "alpha-vm", "pve1", "VM", "running")
        val overview = Overview(1, listOf(vm))
        val inspection = demoInspection(vm, "hour")
        val state by mutableStateOf(WorkspaceState(connected = true, selected = vm, overview = overview, inspection = inspection))
        val immersive = ImmersiveState()
        var fleetRequest by mutableStateOf<FleetRequest?>(FleetRequest(vm.resource, "Console", 1L))
        val probeHistory = mutableListOf<Rect>()
        consolePictureProbe = { probeHistory += it }

        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = Workspace(scope)
        val scene = ImageComposeScene(1400, 900, Density(1f), coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides true, LocalImmersive provides immersive) {
                    Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                        InventoryScreen(
                            state = state,
                            workspace = workspace,
                            sessionTitle = null,
                            search = "",
                            onSearch = {},
                            filter = "All",
                            onFilter = {},
                            onConnect = {},
                            onDisconnect = {},
                            fleetRequest = fleetRequest,
                            onFleetHandled = { fleetRequest = null }
                        )
                    }
                }
            }
        }

        try {
            var time = 0L
            suspend fun frames(count: Int) = repeat(count) { time += 16_000_000L; scene.render(time).close(); yield() }

            frames(6)

            // Initially fit button is "FIT"
            val fitAction = findAction(scene, "FIT")
            assertNotNull(fitAction, "'FIT' action should be present on the console toolbar initially")

            // Toggle fit state -> flips to "100 %"
            assertTrue(performClick(fitAction), "Clicking FIT action should succeed")
            frames(4)

            val hundredAction = findAction(scene, "100 %")
            assertNotNull(hundredAction, "Toolbar should display '100 %' after toggling fit off")

            // Enter immersive
            immersive.enter()
            frames(6)

            // Exit immersive
            immersive.exit()
            frames(6)

            // Verify console state was preserved: fit setting is still "100 %", not reset to default "FIT"
            val hundredAfterExit = findAction(scene, "100 %")
            assertNotNull(hundredAfterExit, "Console state ('100 %' fit setting) must be preserved across immersive enter/exit")
            assertTrue(probeHistory.isNotEmpty(), "Console picture should remain laid out")
        } finally {
            consolePictureProbe = null
            scene.close()
            workspace.close()
            scope.cancel()
        }
    }

    @Test
    fun exitToolbarControlAccessibleAtNarrowAndHighDpiWidthsWithHoverAndPointerClick() = runSceneTest {
        val vm = Guest(origin, 120, "alpha-vm", "pve1", "VM", "running")
        val overview = Overview(1, listOf(vm))
        // Enrich hardware with serial0 and spice to render complete toolbar item set
        val hwJson = """{"data":{"cores":2,"memory":2048,"vga":"qxl","serial0":"socket"}}"""
        val inspection = demoInspection(vm, "hour").copy(hardware = InspectionSection(parseHardware(hwJson)))
        val state by mutableStateOf(WorkspaceState(connected = true, selected = vm, overview = overview, inspection = inspection))
        val immersive = ImmersiveState()
        var fleetRequest by mutableStateOf<FleetRequest?>(FleetRequest(vm.resource, "Console", 1L))

        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = Workspace(scope)
        // Narrow width (700px) at high DPI (1.5x density): in the old layout where all items were in the
        // horizontalScroll row, the toolbar width exceeded 700px, pushing "Exit immersive" offscreen.
        val sceneWidth = 700
        val sceneHeight = 600
        val scene = ImageComposeScene(sceneWidth, sceneHeight, Density(1.5f), coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides true, LocalImmersive provides immersive) {
                    Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                        InventoryScreen(
                            state = state,
                            workspace = workspace,
                            sessionTitle = null,
                            search = "",
                            onSearch = {},
                            filter = "All",
                            onFilter = {},
                            onConnect = {},
                            onDisconnect = {},
                            fleetRequest = fleetRequest,
                            onFleetHandled = { fleetRequest = null }
                        )
                    }
                }
            }
        }

        try {
            var time = 0L
            suspend fun pump(ms: Long) {
                var left = ms
                while (left > 0) {
                    time += 16_000_000L
                    scene.render(time).close()
                    yield()
                    delay(20)
                    left -= 20
                }
            }

            pump(200)

            // 1. Enter immersive mode: bar is initially shown during CONSOLE_BAR_HOLD_MS
            immersive.enter()
            pump(100)
            assertTrue(immersive.active, "Immersive mode should be active")

            val initialExitAction = findActionContaining(scene, "Exit immersive")
            assertNotNull(initialExitAction, "Exit immersive control should be initially composed during hold")

            // 2. Move pointer away from top edge and wait for hold (CONSOLE_BAR_HOLD_MS = 2000ms) to expire
            scene.sendPointerEvent(PointerEventType.Move, Offset(200f, 250f))
            pump(CONSOLE_BAR_HOLD_MS + 200)

            // Bar has slid up and left composition
            val hiddenExitAction = findActionContaining(scene, "Exit immersive")
            assertNull(hiddenExitAction, "Immersive toolbar should auto-hide after hold timer expires when pointer is away")

            // 3. Move pointer to the top edge (y <= 4dp = 6px at 1.5x density) to trigger top-edge hover rest
            scene.sendPointerEvent(PointerEventType.Move, Offset(200f, 2f))
            pump(CONSOLE_BAR_REST_MS + 100)

            // Bar is revealed by resting on the top edge
            val revealedExitAction = findActionContaining(scene, "Exit immersive")
            assertNotNull(revealedExitAction, "Top-edge hover rest must reveal the immersive toolbar and Exit immersive action")

            // 4. Bounds check at narrow/high-DPI: Exit control must be visible and within the viewport
            val bounds = revealedExitAction.boundsInWindow
            assertTrue(
                bounds.right <= sceneWidth,
                "Exit control right edge (${bounds.right}) must be <= scene width ($sceneWidth) so it is not offscreen"
            )
            assertTrue(bounds.left >= 0f, "Exit control left edge (${bounds.left}) must be >= 0")
            assertTrue(bounds.top >= 0f && bounds.bottom <= sceneHeight, "Exit control must be vertically visible: $bounds")
            assertTrue(bounds.width > 20f && bounds.height > 15f, "Exit control must have valid clickable dimensions: $bounds")

            // 5. Send actual pointer click (Move, Press, Release) to the center of the Exit action
            val center = Offset(bounds.left + bounds.width / 2f, bounds.top + bounds.height / 2f)
            scene.sendPointerEvent(PointerEventType.Move, center)
            scene.sendPointerEvent(PointerEventType.Press, center)
            scene.sendPointerEvent(PointerEventType.Release, center)
            pump(150)

            // 6. Immersive mode must exit cleanly upon pointer click
            assertFalse(immersive.active, "Pointer click on Exit immersive action must exit immersive mode")
        } finally {
            consolePictureProbe = null
            scene.close()
            workspace.close()
            scope.cancel()
        }
    }

    @Test
    fun containerConsoleHidesCreationRowInImmersiveAndRestoresOnExit() = runSceneTest {
        val ct = Guest(origin, 200, "beta-ct", "pve1", "Container", "running")
        val overview = Overview(1, listOf(ct))
        val inspection = demoInspection(ct, "hour")
        val state by mutableStateOf(WorkspaceState(connected = true, selected = ct, overview = overview, inspection = inspection))
        val immersive = ImmersiveState()
        var fleetRequest by mutableStateOf<FleetRequest?>(FleetRequest(ct.resource, "Console", 1L))

        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = Workspace(scope)
        val scene = ImageComposeScene(1200, 800, Density(1f), coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides true, LocalImmersive provides immersive) {
                    Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                        InventoryScreen(
                            state = state,
                            workspace = workspace,
                            sessionTitle = null,
                            search = "",
                            onSearch = {},
                            filter = "All",
                            onFilter = {},
                            onConnect = {},
                            onDisconnect = {},
                            fleetRequest = fleetRequest,
                            onFleetHandled = { fleetRequest = null }
                        )
                    }
                }
            }
        }

        try {
            var time = 0L
            suspend fun frames(count: Int) = repeat(count) { time += 16_000_000L; scene.render(time).close(); yield() }

            frames(6)

            // Normal mode
            val normalCreation = creationNodes(scene)
            assertTrue(normalCreation.isNotEmpty(), "Creation row must be visible initially for container")

            // Immersive mode
            immersive.enter()
            frames(6)

            val immersiveCreation = creationNodes(scene)
            assertTrue(immersiveCreation.isEmpty(), "Creation row must disappear for container console in immersive mode")

            // Exit immersive
            immersive.exit()
            frames(6)

            val returnedCreation = creationNodes(scene)
            assertTrue(returnedCreation.isNotEmpty(), "Creation row must return after exiting immersive mode")
        } finally {
            scene.close()
            workspace.close()
            scope.cancel()
        }
    }
}
