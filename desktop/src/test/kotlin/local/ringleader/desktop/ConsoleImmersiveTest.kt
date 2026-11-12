@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.Density
import java.awt.event.KeyEvent as AwtKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import local.ringleader.core.Guest
import local.ringleader.core.Overview
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Immersive console: the window flag, the bar clock, and the chrome that has to leave with it.
 * Scenes run in the test's own event loop (runBlocking), on one thread as in the app. With the default Unconfined context an
 * effect could resume on the AWT thread, where Compose Desktop sends snapshot notifications, while this thread laid the scene
 * out; the rail's scroll to the current guest then failed with "performMeasureAndLayout called during measure layout" and
 * recomposition stopped, so the header never came back.
 */
class ConsoleImmersiveTest {
    private val origin = "https://192.0.2.10:8006"

    private fun key(vk: Int, modifiers: Int = 0, location: Int = AwtKey.KEY_LOCATION_STANDARD, id: Int = AwtKey.KEY_PRESSED) =
        AwtKey(object : java.awt.Component() {}, id, 0L, modifiers, vk, AwtKey.CHAR_UNDEFINED, location).toComposeEvent()

    private val ctrlAlt = AwtKey.CTRL_DOWN_MASK or AwtKey.ALT_DOWN_MASK

    @Test fun immersiveStateTogglesFullscreenOnce() {
        val calls = mutableListOf<Boolean>()
        val state = ImmersiveState { calls.add(it) }
        assertFalse(state.active)
        state.enter()
        state.enter()
        assertTrue(state.active)
        assertEquals(listOf(true), calls)
        state.exit()
        state.exit()
        assertFalse(state.active)
        assertEquals(listOf(true, false), calls)
        state.toggle()
        state.toggle()
        assertEquals(listOf(true, false, true, false), calls)
        assertFalse(state.active)
    }

    @Test fun theBarFollowsTheHoldTheRestAndThePointer() {
        assertTrue(consoleBarShown(0, 0, null, false))
        assertTrue(consoleBarShown(CONSOLE_BAR_HOLD_MS - 1, 0, null, false))
        assertFalse(consoleBarShown(CONSOLE_BAR_HOLD_MS, 0, null, false))
        assertTrue(consoleBarShown(5_000, 0, null, overBar = true))
        // The rest is what brings the bar back after the hold. During the hold the bar stays, so this clock starts later.
        val restAt = 3_000L
        assertFalse(consoleBarShown(restAt + CONSOLE_BAR_REST_MS - 1, 0, restAt, false))
        assertTrue(consoleBarShown(restAt + CONSOLE_BAR_REST_MS, 0, restAt, false))
        assertFalse(consoleBarShown(5_000, 0, null, false), "a pointer that left the edge does not keep the bar")
    }

    @Test fun hostKeysAreF11EscAndTheConsoleShortcuts() {
        val down = consoleHostDecision(key(AwtKey.VK_F11), guestsOpen = false, immersiveActive = false, immersiveAvailable = true)
        assertEquals(ConsoleHostAction.ToggleImmersive, down.action)
        assertTrue(down.consume)
        val missing = consoleHostDecision(key(AwtKey.VK_F11), guestsOpen = false, immersiveActive = false, immersiveAvailable = false)
        assertNull(missing.action)
        assertFalse(missing.consume, "F11 does nothing when this host has no immersive state")
        assertEquals(ConsoleHostAction.ToggleImmersive, consoleHostDecision(key(AwtKey.VK_ENTER, ctrlAlt), false, false, false).action)
        assertTrue(consoleHostDecision(key(AwtKey.VK_ENTER, ctrlAlt), false, false, false).consume)
        assertNull(consoleHostDecision(key(AwtKey.VK_F11, AwtKey.SHIFT_DOWN_MASK), false, true, true).action)
        assertNull(consoleHostDecision(key(AwtKey.VK_F11, AwtKey.CTRL_DOWN_MASK), false, true, true).action)
        assertEquals(ConsoleHostAction.CloseGuests, consoleHostDecision(key(AwtKey.VK_ESCAPE), guestsOpen = true, immersiveActive = true, immersiveAvailable = true).action)
        assertEquals(ConsoleHostAction.ExitImmersive, consoleHostDecision(key(AwtKey.VK_ESCAPE), guestsOpen = false, immersiveActive = true, immersiveAvailable = true).action)
        assertNull(consoleHostDecision(key(AwtKey.VK_ESCAPE), guestsOpen = false, immersiveActive = false, immersiveAvailable = true).action)
        assertNull(consoleHostDecision(key(AwtKey.VK_ESCAPE, AwtKey.ALT_DOWN_MASK), guestsOpen = false, immersiveActive = true, immersiveAvailable = true).action)
        assertEquals(ConsoleHostAction.Previous, consoleHostDecision(key(AwtKey.VK_PAGE_UP, ctrlAlt), false, false, true).action)
        assertEquals(ConsoleHostAction.Next, consoleHostDecision(key(AwtKey.VK_PAGE_DOWN, ctrlAlt), false, true, true).action)
        val release = consoleHostDecision(key(AwtKey.VK_PAGE_UP, ctrlAlt, id = AwtKey.KEY_RELEASED), false, false, true)
        assertNull(release.action)
        assertTrue(release.consume)
        assertEquals(KeyEventType.KeyUp, key(AwtKey.VK_PAGE_UP, ctrlAlt, id = AwtKey.KEY_RELEASED).type)
        assertNull(consoleShortcut(key(AwtKey.VK_ESCAPE, 0)), "Esc stays with a captured guest")
        assertNull(consoleShortcut(key(AwtKey.VK_F11, 0)))
    }

    @Test fun theGuestHeaderLeavesWithImmersiveAndReturns() = runBlocking<Unit> {
        val immersive = ImmersiveState()
        val hits = intArrayOf(0)
        guestHeaderProbe = { hits[0]++ }
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = Workspace(scope)
        try {
            val scene = scene(workspace, immersive, connected = true, coroutineContext)
            val draw = frames(scene)
            try {
                draw(2)
                assertTrue(hits[0] > 0, "the header is composed before immersive")
                hits[0] = 0
                immersive.enter()
                draw(3)
                assertTrue(immersive.active)
                assertEquals(0, hits[0], "the header is not composed while immersive")
                immersive.exit()
                draw(3)
                assertFalse(immersive.active)
                assertTrue(hits[0] > 0, "the header comes back after exit")
            } finally { scene.close() }
        } finally {
            guestHeaderProbe = null
            workspace.close()
            scope.cancel()
        }
    }

    @Test fun leavingTheConsoleTabOrThePageOrTheConnectionExits() = runBlocking<Unit> {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = Workspace(scope)
        try {
            run {
                val immersive = ImmersiveState()
                var tab by mutableStateOf("Console")
                var nonce by mutableStateOf(0L)
                val scene = ImageComposeScene(900, 600, Density(1f), coroutineContext = coroutineContext) {
                    MaterialTheme(colorScheme = ringColors) {
                        CompositionLocalProvider(LocalReduceMotion provides true, LocalImmersive provides immersive) {
                            Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                                Box(Modifier.fillMaxSize()) {
                                    GuestDetailPage(pageState(true), workspace, {}, { _, _ -> }, initialTab = tab, tabNonce = nonce, onSwitchGuest = {})
                                }
                            }
                        }
                    }
                }
                val draw = frames(scene)
                try {
                    immersive.enter()
                    draw(3)
                    assertTrue(immersive.active)
                    tab = "Summary"
                    nonce = 1
                    draw(6)
                    assertFalse(immersive.active, "leaving the Console tab exits immersive")
                } finally { scene.close() }
            }
            run {
                val immersive = ImmersiveState()
                var shown by mutableStateOf(true)
                val scene = ImageComposeScene(900, 600, Density(1f), coroutineContext = coroutineContext) {
                    MaterialTheme(colorScheme = ringColors) {
                        CompositionLocalProvider(LocalReduceMotion provides true, LocalImmersive provides immersive) {
                            Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                                if (shown) Box(Modifier.fillMaxSize()) {
                                    GuestDetailPage(pageState(true), workspace, {}, { _, _ -> }, initialTab = "Console", onSwitchGuest = {})
                                }
                            }
                        }
                    }
                }
                val draw = frames(scene)
                try {
                    immersive.enter()
                    draw(3)
                    assertTrue(immersive.active)
                    shown = false
                    draw(3)
                    assertFalse(immersive.active, "the page leaving the composition exits immersive")
                } finally { scene.close() }
            }
            run {
                val immersive = ImmersiveState()
                var connected by mutableStateOf(true)
                val scene = ImageComposeScene(900, 600, Density(1f), coroutineContext = coroutineContext) {
                    MaterialTheme(colorScheme = ringColors) {
                        CompositionLocalProvider(LocalReduceMotion provides true, LocalImmersive provides immersive) {
                            Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                                Box(Modifier.fillMaxSize()) {
                                    GuestDetailPage(pageState(connected), workspace, {}, { _, _ -> }, initialTab = "Console", onSwitchGuest = {})
                                }
                            }
                        }
                    }
                }
                val draw = frames(scene)
                try {
                    immersive.enter()
                    draw(3)
                    assertTrue(immersive.active)
                    connected = false
                    draw(4)
                    assertFalse(immersive.active, "a disconnect exits immersive")
                } finally { scene.close() }
            }
        } finally {
            workspace.close()
            scope.cancel()
        }
    }

    private fun pageState(connected: Boolean): WorkspaceState {
        val guest = Guest(origin, 112, "work", "node", "VM", "running")
        return WorkspaceState(connected = connected, demo = false, selected = guest, overview = Overview(1, listOf(guest)))
    }

    private fun scene(workspace: Workspace, immersive: ImmersiveState, connected: Boolean, context: CoroutineContext) =
        ImageComposeScene(900, 600, Density(1f), coroutineContext = context) {
        MaterialTheme(colorScheme = ringColors) {
            CompositionLocalProvider(LocalReduceMotion provides true, LocalImmersive provides immersive) {
                Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                    Box(Modifier.fillMaxSize()) {
                        GuestDetailPage(pageState(connected), workspace, {}, { _, _ -> }, initialTab = "Console", onSwitchGuest = {})
                    }
                }
            }
        }
    }

    /** Draws frames 16 ms apart; the yield lets the event loop run what the frame dispatched. */
    private fun frames(scene: ImageComposeScene): suspend (Int) -> Unit {
        var time = 0L
        return { count -> repeat(count) { time += 16_000_000L; scene.render(time).close(); yield() } }
    }
}
