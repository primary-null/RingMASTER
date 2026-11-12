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
import local.ringleader.core.DIRECT_PVE_KIND
import local.ringleader.core.Guest
import local.ringleader.core.Overview
import local.ringleader.core.PDM_KIND
import local.ringleader.core.ResourceId
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Who the console rail lists, and which keys it takes from the guest. */
class ConsoleSwitcherTest {
    private val origin = "https://192.0.2.10:8006"

    @AfterTest fun resetPreference() {
        ConsoleSwitcherPreference.expanded = true
        ConsoleSwitcherPreference.runningOnly = true
    }

    private fun guest(id: Int, name: String, node: String, status: String = "running", kind: String = "VM", template: Boolean = false, scope: String = "") =
        Guest(origin, id, name, node, kind, status, template = template, scope = scope)

    private fun state(vararg guests: Guest, kind: String = DIRECT_PVE_KIND) =
        WorkspaceState(kind = kind, overview = Overview(guests.map { it.node }.distinct().size, guests.toList()))

    private fun key(vk: Int, modifiers: Int, location: Int = AwtKey.KEY_LOCATION_STANDARD, id: Int = AwtKey.KEY_PRESSED) =
        AwtKey(object : java.awt.Component() {}, id, 0L, modifiers, vk, AwtKey.CHAR_UNDEFINED, location).toComposeEvent()

    private val ctrlAlt = AwtKey.CTRL_DOWN_MASK or AwtKey.ALT_DOWN_MASK

    @Test fun shortcutsAreOnlyCtrlAltPageUpAndPageDown() {
        assertEquals(ConsoleShortcut.PreviousGuest, consoleShortcut(key(AwtKey.VK_PAGE_UP, ctrlAlt)))
        assertEquals(ConsoleShortcut.NextGuest, consoleShortcut(key(AwtKey.VK_PAGE_DOWN, ctrlAlt)))
        assertEquals(ConsoleShortcut.PreviousGuest, consoleShortcut(key(AwtKey.VK_PAGE_UP, ctrlAlt, id = AwtKey.KEY_RELEASED)))
        assertNull(consoleShortcut(key(AwtKey.VK_A, ctrlAlt)), "Ctrl+Alt+A is an AltGr letter and stays with the guest")
        assertNull(consoleShortcut(key(AwtKey.VK_PAGE_UP, ctrlAlt or AwtKey.SHIFT_DOWN_MASK)))
        assertNull(consoleShortcut(key(AwtKey.VK_PAGE_UP, ctrlAlt or AwtKey.META_DOWN_MASK)))
        assertNull(consoleShortcut(key(AwtKey.VK_PAGE_UP, AwtKey.CTRL_DOWN_MASK)))
        assertNull(consoleShortcut(key(AwtKey.VK_PAGE_DOWN, AwtKey.ALT_DOWN_MASK)))
        assertNull(consoleShortcut(key(AwtKey.VK_PAGE_UP, 0)))
        assertEquals(ConsoleShortcut.Immersive, consoleShortcut(key(AwtKey.VK_ENTER, ctrlAlt)))
        assertEquals(ConsoleShortcut.Immersive, consoleShortcut(key(AwtKey.VK_ENTER, ctrlAlt, location = AwtKey.KEY_LOCATION_NUMPAD)))
        assertNull(consoleShortcut(key(AwtKey.VK_ENTER, ctrlAlt or AwtKey.SHIFT_DOWN_MASK)))
        assertEquals(KeyEventType.KeyDown, key(AwtKey.VK_PAGE_UP, ctrlAlt).type)
    }

    @Test fun neighbourWrapsAndGivesUpOnAShortList() {
        val a = guest(1, "a", "n")
        val b = guest(2, "b", "n")
        val c = guest(3, "c", "n")
        val list = listOf(a, b, c)
        assertNull(neighbourGuest(emptyList(), a.resource, 1))
        assertNull(neighbourGuest(listOf(a), a.resource, 1))
        assertNull(neighbourGuest(listOf(a), a.resource, -1))
        assertEquals(b, neighbourGuest(list, a.resource, 1))
        assertEquals(c, neighbourGuest(list, a.resource, -1))
        assertEquals(a, neighbourGuest(list, c.resource, 1))
        assertEquals(c, neighbourGuest(list, a.resource, 2))
        assertEquals(list.first(), neighbourGuest(list, ResourceId(origin, "", "VM", 99), 1))
    }

    @Test fun theRailListsPagesInNodeOrderAndKeepsTheCurrentGuest() {
        val template = guest(1, "base", "a-node", template = true)
        val later = guest(20, "later", "a-node")
        val first = guest(5, "first", "a-node")
        val other = guest(10, "other", "b-node", status = "stopped")
        val container = guest(12, "web", "b-node", kind = "Container")
        val scoped = guest(14, "remote", "a-node", scope = "lab")
        val fleet = state(template, later, other, first, container, scoped)
        assertEquals(listOf(5, 20, 12), switcherGuests(fleet, null, runningOnly = true, filter = "").map { it.id })
        assertEquals(listOf(5, 20, 10, 12), switcherGuests(fleet, null, runningOnly = false, filter = "").map { it.id })
        assertEquals(listOf(5, 20, 10, 12), switcherGuests(fleet, other.resource, runningOnly = true, filter = "").map { it.id }, "the open guest stays listed when it stops")
        assertEquals(listOf(10, 12), switcherGuests(fleet, other.resource, runningOnly = true, filter = "WEB").map { it.id }, "a name match, plus the open guest even when the text misses it")
        assertEquals(listOf(10, 12), switcherGuests(fleet, null, runningOnly = false, filter = "1").map { it.id })
        assertEquals(listOf(other.resource), switcherGuests(fleet, other.resource, runningOnly = true, filter = "no-such").map { it.resource })
        assertTrue(switcherGuests(fleet.copy(kind = PDM_KIND), first.resource, runningOnly = false, filter = "").isEmpty())
        assertTrue(template.resource !in switcherGuests(fleet, template.resource, runningOnly = false, filter = "").map { it.resource })
    }

    // The scene runs in this test's event loop, so the rail's scroll effect cannot resume on the AWT thread mid-layout (see ConsoleImmersiveTest).
    @Test fun aKeyedConsoleStartsEmptyForTheNextGuest() = runBlocking<Unit> {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = Workspace(scope)
        val seen = mutableListOf<ResourceId>()
        consoleViewBirthProbe = { seen.add(it) }
        try {
            workspace.exploreDemo()
            workspace.setLive(false)
            val first = workspace.state.value.overview!!.guests.first { it.id == 120 }
            val second = workspace.state.value.overview!!.guests.first { it.id == 130 }
            workspace.select(first)
            val scene = ImageComposeScene(900, 600, Density(1f), coroutineContext = coroutineContext) {
                MaterialTheme(colorScheme = ringColors) {
                    CompositionLocalProvider(LocalReduceMotion provides true) {
                        Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                            val state by workspace.state.collectAsState()
                            Box(Modifier.fillMaxSize()) {
                                GuestDetailPage(state, workspace, {}, { _, _ -> }, initialTab = "Console", onSwitchGuest = {})
                            }
                        }
                    }
                }
            }
            try {
                var time = 0L
                suspend fun frames(count: Int) = repeat(count) { time += 16_000_000L; scene.render(time).close(); yield() }
                frames(4)
                assertEquals(listOf(first.resource), seen.toList(), "the first console view is born for the open guest")
                workspace.select(second)
                frames(4)
                assertEquals(listOf(first.resource, second.resource), seen.toList(), "the next guest gets its own view, which starts empty")
            } finally { scene.close() }
        } finally {
            consoleViewBirthProbe = null
            workspace.close()
            scope.cancel()
        }
    }

    @Test fun aFrameIsCopiedOnlyAfterOneWasShown() {
        val pixels = intArrayOf(1, 2, 3, 4)
        assertNull(consoleFrameToKeep(shown = false, 2, 2, pixels))
        assertNull(consoleFrameToKeep(shown = true, 0, 2, pixels))
        assertNull(consoleFrameToKeep(shown = true, 2, 2, intArrayOf(1)))
        val copy = consoleFrameToKeep(shown = true, 2, 2, pixels)
        assertTrue(copy!!.contentEquals(pixels))
        copy[0] = 9
        assertEquals(1, pixels[0], "the view buffer is not the copy that is kept")
    }
}
