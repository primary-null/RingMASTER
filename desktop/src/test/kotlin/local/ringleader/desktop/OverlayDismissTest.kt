@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState
import local.ringleader.desktop.web.escapeFallback

/**
 * Overlay dismiss classes, measured in a scene like the browser session: no platform back key, so Escape must be
 * handled inside each overlay. Light closes on a full click outside; Soft and Confirm ignore it; Escape closes only
 * the top overlay and never commits; the page behind never sees a dismissing click.
 */
class OverlayDismissTest {
    private class Host(val scene: ImageComposeScene) {
        var now = 0L
        suspend fun step(frames: Int = 4) = repeat(frames) { now += 16; scene.render(now * 1_000_000L).close(); yield() }
        suspend fun click(at: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at)
            scene.sendPointerEvent(PointerEventType.Release, at)
            step()
        }
        suspend fun escape() { scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown)); scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyUp)); step() }

        /** Every semantics node of every layer (the page and each open dialog), merged as a screen reader sees them. */
        fun nodes(): List<SemanticsNode> {
            val out = ArrayList<SemanticsNode>()
            fun visit(node: SemanticsNode) { out.add(node); node.children.forEach { visit(it) } }
            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
            return out
        }
        fun find(text: String): SemanticsNode? = nodes().firstOrNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == text }
    }

    /** [VncKeyboard.captured] is process-wide; set it for one test and put it back. */
    private fun withCapture(captured: Boolean, body: () -> Unit) {
        val before = VncKeyboard.captured
        VncKeyboard.captured = captured
        try { body() } finally { VncKeyboard.captured = before }
    }

    /** 1000 x 700 at density 1 with a clickable page behind, Reduce motion on, and a modal stack like the app's. */
    private fun withScene(stack: ModalStack, page: () -> Unit, content: @Composable () -> Unit, body: suspend Host.() -> Unit): Unit = runBlocking {
        val scene = ImageComposeScene(1000, 700, Density(1f), coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides true, LocalModalStack provides stack) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { page() })
                        content()
                    }
                }
            }
        }
        try { Host(scene).apply { step(6) }.body() } finally { scene.close() }
    }

    private val outside = Offset(4f, 4f)

    @Test fun settingsClosesOnAnOutsideClickButNotOnTheSheet() {
        val workspace = Workspace(CoroutineScope(Dispatchers.Unconfined))
        val stack = ModalStack()
        var open by mutableStateOf(true)
        var page = 0
        try {
            withScene(stack, { page++ }, {
                if (open) SettingsDialog(WorkspaceState(), workspace, SettingsSection.Size, {}, { open = false }, {})
            }) {
                assertEquals(ModalKind.Settings, stack.top?.kind, "Settings is recorded as the top overlay")
                // The sheet is 900 x 640, centred: its header row starts at y 30. The title area is not a control.
                click(Offset(300f, 50f))
                assertTrue(open, "a click on the sheet keeps Settings open")
                click(outside)
                assertFalse(open, "a click on the scrim closes Settings")
                assertEquals(0, page, "the dismissing click never reaches the page")
                step()
                assertFalse(stack.open, "closing removes it from the stack")
            }
        } finally { workspace.close() }
    }

    @Test fun escapeClosesSettingsInTheScene() {
        val workspace = Workspace(CoroutineScope(Dispatchers.Unconfined))
        var open by mutableStateOf(true)
        try {
            withScene(ModalStack(), {}, {
                if (open) SettingsDialog(WorkspaceState(), workspace, SettingsSection.Size, {}, { open = false }, {})
            }) {
                escape()
                assertFalse(open, "Escape closes Settings without a platform back key")
            }
        } finally { workspace.close() }
    }

    @Test fun aConfirmationOverSettingsTakesTheFirstEscapeAndIgnoresOutsideClicks() {
        val workspace = Workspace(CoroutineScope(Dispatchers.Unconfined))
        val stack = ModalStack()
        var settings by mutableStateOf(true)
        var inner by mutableStateOf(true)
        var committed = 0
        var page = 0
        try {
            withScene(stack, { page++ }, {
                if (settings) SettingsDialog(WorkspaceState(), workspace, SettingsSection.Size, {}, { settings = false }, {})
                // Composed after Settings, as Clear everything is from inside it: a Confirm dialog on top.
                if (inner) TechDialog("Clear everything?", { inner = false }, actions = {
                    TechAction("Clear and quit", active = true) { committed++ }
                    TechAction("Cancel") { inner = false }
                }) { Text("Nothing happens until CLEAR is typed.") }
            }) {
                assertEquals(ModalKind.Dialog, stack.top?.kind)
                click(outside)
                assertTrue(inner, "a click outside a confirmation does nothing")
                assertTrue(settings, "and does not reach Settings beneath it")
                assertEquals(0, page)
                escape()
                assertFalse(inner, "the first Escape closes only the confirmation")
                assertTrue(settings, "Settings stays open after the first Escape")
                step()
                escape()
                assertFalse(settings, "the second Escape closes Settings")
                assertEquals(0, committed, "Escape never commits")
            }
        } finally { workspace.close() }
    }

    @Test fun confirmIgnoresOutsideClicksAndEscapeCancels() {
        var dismissed = 0
        var committed = 0
        var open by mutableStateOf(true)
        withScene(ModalStack(), {}, {
            if (open) TechDialog("Confirm Hard stop", { dismissed++; open = false }, actions = {
                TechAction("Hard stop", active = true) { committed++ }
                TechAction("Cancel") { open = false }
            }) { Text("Cuts power immediately.") }
        }) {
            click(outside)
            assertEquals(0, dismissed, "a click outside a confirmation never calls onDismiss")
            assertTrue(open)
            escape()
            assertEquals(1, dismissed, "Escape cancels")
            assertEquals(0, committed, "Escape never runs the commit button")
        }
    }

    @Test fun softFormsKeepTypedInputAcrossAStrayClick() {
        var dismissed = 0
        withScene(ModalStack(), {}, {
            TechDialog("Pin a service", { dismissed++ }, dismiss = Dismiss.Soft, actions = { TechAction("Cancel") { dismissed++ } }) {
                TechField("AdGuard UI", {}, "Name")
            }
        }) {
            click(outside)
            assertEquals(0, dismissed, "a click outside a form does not dismiss it")
            escape()
            assertEquals(1, dismissed, "Escape is Cancel")
        }
    }

    @Test fun lightDialogsCloseOnAnOutsideClick() {
        var page = 0
        var open by mutableStateOf(true)
        withScene(ModalStack(), { page++ }, {
            if (open) TechDialog("Pinned", { open = false }, width = 420.dp, dismiss = Dismiss.Light, actions = { TechAction("Close") { open = false } }) {
                Text("VM 101 · web")
            }
        }) {
            click(Offset(500f, 350f))
            assertTrue(open, "a click on the dialog keeps it open")
            click(outside)
            assertFalse(open, "a click outside a light dialog closes it")
            assertEquals(0, page, "the dismissing click is swallowed")
        }
    }

    @Test fun updateConsentNeverSavesNoFromAnOutsideClickOrEscape() {
        val answers = mutableListOf<Boolean>()
        var closed = 0
        var open by mutableStateOf(true)
        withScene(ModalStack(), {}, {
            if (open) UpdateConsentDialog({ answers += it }) { closed++; open = false }
        }) {
            click(outside)
            assertTrue(open, "a click outside leaves the question up")
            assertEquals(emptyList(), answers)
            escape()
            assertFalse(open, "Escape puts the question away")
            assertEquals(1, closed)
            assertEquals(emptyList(), answers, "no answer is saved, so it is asked again later")
        }
    }

    @Test fun pageShortcutsWaitWhileAnOverlayIsOpen() {
        val stack = ModalStack()
        var pageCalls = 0
        val page: (KeyEvent) -> Boolean = { pageCalls++; true }
        val ctrl2 = KeyEvent(Key.Two, KeyEventType.KeyDown, isCtrlPressed = true)
        val comma = KeyEvent(Key.Comma, KeyEventType.KeyDown, isCtrlPressed = true)
        assertTrue(Shortcuts.handle(ctrl2, page, stack), "with nothing open the page takes Ctrl+2")
        assertEquals(1, pageCalls)

        var settingsClosed = 0
        val settings = stack.push(ModalKind.Settings) { settingsClosed++ }
        assertFalse(Shortcuts.handle(ctrl2, page, stack), "Ctrl+2 does nothing behind Settings")
        for (key in listOf(KeyEvent(Key.F5, KeyEventType.KeyDown), KeyEvent(Key.F11, KeyEventType.KeyDown), KeyEvent(Key.Escape, KeyEventType.KeyDown),
            KeyEvent(Key.B, KeyEventType.KeyDown, isCtrlPressed = true), KeyEvent(Key.F, KeyEventType.KeyDown, isCtrlPressed = true),
            KeyEvent(Key.PageDown, KeyEventType.KeyDown, isCtrlPressed = true, isAltPressed = true))) Shortcuts.handle(key, page, stack)
        assertEquals(1, pageCalls, "no page shortcut runs behind an overlay, console Escape included")
        assertEquals(0, settingsClosed, "Escape is left to the dialog in the scene")

        val confirm = stack.push(ModalKind.Dialog) {}
        assertTrue(Shortcuts.handle(comma, page, stack))
        assertEquals(0, settingsClosed, "Ctrl+, does not close Settings beneath a confirmation")
        stack.remove(confirm)
        assertTrue(Shortcuts.handle(comma, page, stack))
        assertEquals(1, settingsClosed, "Ctrl+, closes Settings when it is on top")
        stack.remove(settings)

        var guestsClosed = 0
        val guests = stack.push(ModalKind.Overlay) { guestsClosed++ }
        assertTrue(Shortcuts.handle(KeyEvent(Key.Escape, KeyEventType.KeyDown), page, stack))
        assertEquals(1, guestsClosed, "Escape at the window closes the in-page guests list")
        stack.remove(guests)
        assertEquals(1, pageCalls)
    }

    @Test fun theBrowserEscapeFallbackClosesOnlyAClosableTop() {
        val stack = ModalStack()
        val esc = KeyEvent(Key.Escape, KeyEventType.KeyDown)
        assertFalse(escapeFallback(esc, stack), "nothing open, nothing closed")
        var closed = 0
        stack.push(ModalKind.Settings) { closed++ }
        assertFalse(escapeFallback(KeyEvent(Key.Escape, KeyEventType.KeyUp), stack))
        assertTrue(escapeFallback(esc, stack))
        assertEquals(1, closed)
        stack.push(ModalKind.Shell, null)
        assertFalse(escapeFallback(esc, stack), "the node shell owns Escape; nothing beneath it is closed")
        assertEquals(1, closed)
    }

    @Test fun dialogPropertiesFollowTheDismissClassAndReduceMotion() {
        val light = techDialogProperties(Dismiss.Light, reduce = false)
        assertTrue(light.dismissOnClickOutside && light.dismissOnBackPress && light.animateTransition)
        assertFalse(techDialogProperties(Dismiss.Soft, reduce = false).dismissOnClickOutside)
        assertFalse(techDialogProperties(Dismiss.Confirm, reduce = false).dismissOnClickOutside)
        assertTrue(techDialogProperties(Dismiss.Confirm, reduce = false).dismissOnBackPress)
        for (d in Dismiss.entries) assertFalse(techDialogProperties(d, reduce = true).animateTransition, "no dialog animation under Reduce motion ($d)")
    }

    @Test fun theSettingsSheetCapAlwaysBinds() {
        assertEquals(900.dp, settingsSheetSize(1400.dp, 900.dp).width)
        assertEquals(640.dp, settingsSheetSize(1400.dp, 900.dp).height)
        val small = settingsSheetSize(800.dp, 500.dp)
        assertEquals(736f, small.width.value, .01f)
        assertEquals(460f, small.height.value, .01f)
        assertEquals(900.dp, settingsSheetSize(Dp.Infinity, Dp.Infinity).width)
    }

    @Test fun aClickOnTheGuestsPanelHeaderStaysOnThePanel() {
        var closed = 0
        withScene(ModalStack(), {}, {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { closed++ })
                Box(Modifier.offset(0.dp, 0.dp).size(232.dp, 700.dp).background(Tech.Panel).blockPointerPassThrough()) {
                    SectionLabel("Guests", Modifier.offset(12.dp, 12.dp))
                }
            }
        }) {
            click(Offset(40f, 18f))
            assertEquals(0, closed, "a click on the GUESTS label does not close the list")
            click(Offset(120f, 400f))
            assertEquals(0, closed, "nor does a click on the panel's padding")
            click(Offset(700f, 400f))
            assertEquals(1, closed, "a click beside the panel still closes it")
        }
    }

    @Test fun theRealClearEverythingOverSettingsTakesTheFirstEscape() {
        val workspace = Workspace(CoroutineScope(Dispatchers.Unconfined))
        val stack = ModalStack()
        var settings by mutableStateOf(true)
        var wiped = 0
        try {
            withScene(stack, {}, {
                if (settings) SettingsDialog(WorkspaceState(), workspace, SettingsSection.About, {}, { settings = false }) { wiped++ }
            }) {
                assertNull(find("Clear everything?"), "the wipe dialog starts closed")
                // Opened through the button's own click action: a pointer click could land on Open or the project link.
                val button = assertNotNull(nodes().firstOrNull { node ->
                    node.config.getOrNull(SemanticsActions.OnClick) != null &&
                        node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == "CLEAR EVERYTHING…"
                }, "the About section shows Clear everything")
                button.config[SemanticsActions.OnClick].action!!.invoke()
                step(6)
                assertNotNull(find("Clear everything?"), "the real Clear everything dialog is open")
                assertEquals(ModalKind.Dialog, stack.top?.kind, "and it is the top overlay, over Settings")
                click(Offset(4f, 4f))
                assertNotNull(find("Clear everything?"), "a click outside the confirmation does nothing")
                assertTrue(settings, "and does not reach Settings")
                escape()
                assertNull(find("Clear everything?"), "the first Escape closes only Clear everything")
                assertTrue(settings, "Settings stays open")
                assertEquals(ModalKind.Settings, stack.top?.kind)
                step()
                escape()
                assertFalse(settings, "the second Escape closes Settings")
                assertEquals(0, wiped, "nothing was cleared")
            }
        } finally { workspace.close() }
    }

    @Test fun theChartLensHoldsPageShortcutsWhileOpen() {
        val workspace = Workspace(CoroutineScope(Dispatchers.Unconfined))
        val stack = ModalStack()
        var lens: ChartLensState? = null
        var pageCalls = 0
        val page: (KeyEvent) -> Boolean = { pageCalls++; true }
        val ctrl2 = KeyEvent(Key.Two, KeyEventType.KeyDown, isCtrlPressed = true)
        try {
            withScene(stack, {}, {
                ChartLensHost(WorkspaceState(), workspace) { lens = LocalChartLens.current }
            }) {
                assertFalse(stack.open)
                lens!!.open(LensRequest(LensSubject.Cluster, lensChart(LiveMetric.Cpu, false)))
                step(12)
                assertEquals(ModalKind.Overlay, stack.top?.kind, "the open lens is recorded")
                assertFalse(Shortcuts.handle(ctrl2, page, stack), "Ctrl+2 does nothing behind the lens")
                assertEquals(0, pageCalls)
                assertTrue(Shortcuts.handle(KeyEvent(Key.Escape, KeyEventType.KeyDown), page, stack), "Escape at the window closes it")
                step(12)
                assertEquals(null, lens!!.request)
                assertFalse(stack.open, "a closed lens leaves the stack")
                assertTrue(Shortcuts.handle(ctrl2, page, stack))
                assertEquals(1, pageCalls, "page shortcuts are back")
            }
        } finally { workspace.close() }
    }

    @Test fun aCapturedConsoleKeepsKeysOnlyWithNothingOrTheShellOnTop() {
        // Pure decision first: zoom keys are never sent where they would be handled (they save real settings).
        assertFalse(consoleOwnsKeys(false, null))
        assertTrue(consoleOwnsKeys(true, null))
        assertTrue(consoleOwnsKeys(true, ModalKind.Shell))
        for (kind in listOf(ModalKind.Settings, ModalKind.Dialog, ModalKind.Overlay)) assertFalse(consoleOwnsKeys(true, kind), "$kind holds the keyboard")
        val comma = KeyEvent(Key.Comma, KeyEventType.KeyDown, isCtrlPressed = true)
        var pageCalls = 0
        val page: (KeyEvent) -> Boolean = { pageCalls++; true }
        withCapture(true) {
            val stack = ModalStack()
            assertFalse(Shortcuts.handle(KeyEvent(Key.Two, KeyEventType.KeyDown, isCtrlPressed = true), page, stack), "with nothing open the guest keeps Ctrl+2")
            assertFalse(Shortcuts.handle(KeyEvent(Key.Equals, KeyEventType.KeyDown, isCtrlPressed = true), page, stack), "and zoom keys")
            var settingsClosed = 0
            val settings = stack.push(ModalKind.Settings) { settingsClosed++ }
            assertTrue(Shortcuts.handle(comma, page, stack), "Ctrl+, over a captured console reaches Settings")
            assertEquals(1, settingsClosed)
            var guestsClosed = 0
            val guests = stack.push(ModalKind.Overlay) { guestsClosed++ }
            assertTrue(Shortcuts.handle(KeyEvent(Key.Escape, KeyEventType.KeyDown), page, stack))
            assertEquals(1, guestsClosed, "Escape closes the guests list even while the console had the keyboard")
            stack.remove(guests)
            val shell = stack.push(ModalKind.Shell, null)
            assertFalse(Shortcuts.handle(comma, page, stack), "the node shell keeps Ctrl+,")
            assertFalse(Shortcuts.handle(KeyEvent(Key.Equals, KeyEventType.KeyDown, isCtrlPressed = true), page, stack), "and zoom keys")
            assertFalse(Shortcuts.handle(KeyEvent(Key.Escape, KeyEventType.KeyDown), page, stack), "and Escape")
            assertEquals(1, settingsClosed)
            stack.remove(shell); stack.remove(settings)
        }
        assertEquals(0, pageCalls, "no page shortcut ran")
    }
}
