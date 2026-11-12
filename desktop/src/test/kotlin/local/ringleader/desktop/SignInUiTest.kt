@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import local.ringleader.core.Connection
import local.ringleader.core.Guest
import local.ringleader.core.InventoryGateway
import local.ringleader.core.Overview
import local.ringleader.core.ProfileDesk
import local.ringleader.core.ProfileStore
import local.ringleader.core.Workspace
import local.ringleader.core.demoDetails
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sign-in defaults, Enter, focus, Caps Lock, Show/Hide, paste and the window title. No secret is printed. */
class SignInUiTest {
    private val origin = "https://pve.example.test:8006"
    private val marker = "pasted-marker"

    @Test fun enterSubmitsAndPasteKeysDoNot() {
        var calls = 0
        val submit: () -> Unit = { calls += 1 }
        assertFalse(submitOnEnter(KeyEvent(Key.V, KeyEventType.KeyDown, isCtrlPressed = true), submit))
        assertFalse(submitOnEnter(KeyEvent(Key.Insert, KeyEventType.KeyDown, isShiftPressed = true), submit))
        assertEquals(0, calls)
        assertTrue(submitOnEnter(KeyEvent(Key.Enter, KeyEventType.KeyDown), submit))
        assertTrue(submitOnEnter(KeyEvent(Key.NumPadEnter, KeyEventType.KeyDown), submit))
        assertEquals(2, calls)
        assertFalse(submitOnEnter(KeyEvent(Key.Enter, KeyEventType.KeyUp), submit))
        assertFalse(submitOnEnter(KeyEvent(Key.Enter, KeyEventType.KeyDown), null))
        assertEquals(2, calls)
    }

    @Test fun theWindowTitleDropsTheProfileWhenShowcaseIsOn() {
        Appearance.preview(false, showcase = false)
        WindowTitle.connectTo = null
        try {
            assertEquals("RingMASTER", WindowTitle.text)
            WindowTitle.connectTo = "Lab cluster"
            assertEquals("RingMASTER · Connect · Lab cluster", WindowTitle.text)
            Appearance.preview(false, showcase = true)
            assertEquals("RingMASTER", WindowTitle.text)
            Appearance.preview(false, showcase = false)
            assertEquals("RingMASTER · Connect · Lab cluster", WindowTitle.text)
            WindowTitle.connectTo = "Other bench"
            assertEquals("RingMASTER · Connect · Other bench", WindowTitle.text)
            WindowTitle.connectTo = null
            assertEquals("RingMASTER", WindowTitle.text)
        } finally {
            WindowTitle.connectTo = null
            Appearance.preview(false, showcase = false)
        }
    }

    @Test fun passwordModeFillsRootAndRemembersOnlyTheChoice() = runBlocking {
        val bench = Bench()
        val scene = bench.scene(coroutineContext)
        try {
            bench.settle(scene)
            assertNull(field(scene, "User"), "API token is the first-start choice")
            clickText(scene, "PASSWORD")
            bench.settle(scene)
            assertEquals(local.ringleader.core.DEFAULT_PASSWORD_USER, field(scene, "User"))
            assertTrue(Files.readString(bench.motionFile).contains("desk.passwordMode=true"))
            assertFalse(Files.readString(bench.motionFile).contains(marker))
            setField(scene, "User", "root")
            bench.settle(scene)
            assertNotNull(findTextPrefix(scene, "Signs in as root@pam (Linux PAM"))
            click(scene, assertNotNull(editableAfter(scene, "Server address")))
            Appearance.preview(false, showcase = true)
            bench.settle(scene)
            assertNotNull(findTextPrefix(scene, "Signs in as ${identityMask("root@pam")} (Linux PAM"))
            assertNull(findTextPrefix(scene, "Signs in as root@pam"))
            Appearance.preview(false, showcase = false)
            bench.settle(scene)
            clickText(scene, "API TOKEN")
            bench.settle(scene)
            assertTrue(Files.readString(bench.motionFile).contains("desk.passwordMode=false"))
            setField(scene, "API token ID", "root@pam!monitor")
            bench.settle(scene)
            clickText(scene, "PASSWORD")
            bench.settle(scene)
            assertEquals("root@pam", field(scene, "User"))
            clickText(scene, "NEW PROFILE")
            bench.settle(scene)
            clickText(scene, "PASSWORD")
            bench.settle(scene)
            assertEquals("root@pam", field(scene, "User"), "a new profile's Password choice fills root@pam")
        } finally { scene.close(); bench.close() }
    }

    @Test fun aSavedProfileFocusesTheSecretAndEnterConnects() = runBlocking {
        val bench = Bench()
        val scene = bench.scene(coroutineContext)
        val previousCaps = capsLockReader
        capsLockReader = { true }
        try {
            bench.desk.beginCreate()
            bench.desk.updateDraft("Lab cluster", origin, "root@pam", "")
            assertTrue(bench.desk.saveDraft(passwordLogin = true))
            bench.desk.beginCreate()
            bench.desk.updateDraft("Other bench", origin, "root@pam!monitor", "")
            assertTrue(bench.desk.saveDraft())
            bench.desk.selectProfile(bench.desk.state.value.profiles.first { it.displayName == "Lab cluster" }.id)
            bench.settle(scene)
            assertEquals("RingMASTER · Connect · Lab cluster", WindowTitle.text)
            assertEquals("Lab cluster", WindowTitle.connectTo)
            val secret = assertNotNull(editableAfter(scene, "Password"))
            assertEquals(true, secret.config.getOrNull(SemanticsProperties.Focused), "the secret field takes focus once")
            assertNotNull(findExact(scene, "Caps Lock is on"))
            scene.sendKeyEvent(KeyEvent(Key.ShiftLeft, KeyEventType.KeyDown))
            capsLockReader = { false }
            scene.sendKeyEvent(KeyEvent(Key.ShiftLeft, KeyEventType.KeyDown))
            bench.settle(scene)
            assertNull(findExact(scene, "Caps Lock is on"))
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown))
            bench.settle(scene)
            assertFalse(bench.workspace.state.value.connected, "Enter does nothing until a secret is present")
            assertNull(bench.desk.state.value.formError)
            val otp = assertNotNull(editableAfter(scene, "Two-factor"))
            click(scene, otp)
            bench.settle(scene)
            assertEquals(true, otpNode(scene).config.getOrNull(SemanticsProperties.Focused))
            assertNotEqualsFocused(secretNode(scene))
            clickText(scene, "Show")
            bench.settle(scene)
            assertNotNull(findExact(scene, "Hide"), "Show reveals the field without leaving it")
            bench.desk.discardSecrets()
            bench.settle(scene)
            assertNotNull(findExact(scene, "Show"), "Clear secret hides the field again")
            scene.sendKeyEvent(KeyEvent(Key.V, KeyEventType.KeyDown, isCtrlPressed = true))
            bench.settle(scene)
            assertTrue(bench.desk.hasProfileSecret(), "paste reaches the secret field")
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown))
            bench.settle(scene)
            assertTrue(bench.workspace.state.value.connected)
            var matched = false
            bench.seen?.withSecret { matched = it == marker }
            assertTrue(matched)
            bench.workspace.setLive(false)
            Appearance.preview(false, showcase = true)
            bench.settle(scene)
            assertEquals("RingMASTER", WindowTitle.text)
            assertNull(findExact(scene, "Show"))
            assertNull(findExact(scene, "Hide"))
            Appearance.preview(false, showcase = false)
            bench.desk.selectProfile(bench.desk.state.value.profiles.first { it.displayName == "Other bench" }.id)
            bench.settle(scene)
            assertEquals("RingMASTER · Connect · Other bench", WindowTitle.text)
            bench.desk.beginEdit()
            bench.settle(scene)
            assertEquals("RingMASTER", WindowTitle.text)
            assertNull(WindowTitle.connectTo)
        } finally {
            capsLockReader = previousCaps
            scene.close()
            bench.close()
        }
    }

    private fun secretNode(scene: ImageComposeScene) = assertNotNull(editableAfter(scene, "Password"))
    private fun otpNode(scene: ImageComposeScene) = assertNotNull(editableAfter(scene, "Two-factor"))
    private fun assertNotEqualsFocused(node: SemanticsNode) {
        assertFalse(node.config.getOrNull(SemanticsProperties.Focused) == true)
    }

    /** A stand-in clipboard. Paste must not read or write the operating-system clipboard. */
    private class MemoryClipboard(private val text: String) : Clipboard {
        override suspend fun getClipEntry(): ClipEntry = ClipEntry(java.awt.datatransfer.StringSelection(text))
        override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
    }

    private inner class Bench : AutoCloseable {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val directory = Files.createTempDirectory("sign-in-ui")
        val motionFile = directory.resolve("motion.txt")
        val motion = MotionPreferences(motionFile)
        var seen: Connection? = null
        val workspace = Workspace(scope, object : InventoryGateway {
            override suspend fun overview(connection: Connection): Overview {
                seen = connection
                return Overview(1, listOf(Guest(connection.remoteKey, 100, "alpha", "node-a", "VM", "running")))
            }
            override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
        })
        val desk = ProfileDesk(workspace, ProfileStore(directory.resolve("profiles.json")))
        @androidx.compose.runtime.Composable fun deskContent() {
            val state by workspace.state.collectAsState()
            val deskState by desk.state.collectAsState()
            Box(Modifier.fillMaxSize().padding(16.dp)) {
                DeskScreen(state, deskState, desk, motion = motion) {}
            }
        }
        val clipboard = MemoryClipboard(marker)
        fun scene(context: kotlin.coroutines.CoroutineContext) = ImageComposeScene(1400, 900, Density(1f), coroutineContext = context) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides true, LocalClipboard provides clipboard) {
                    Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) { deskContent() }
                }
            }
        }
        suspend fun settle(scene: ImageComposeScene) {
            repeat(6) {
                yield()
                // A session card appearing in the same frame can trip a one-shot layout bookkeeping error.
                runCatching { scene.render(System.nanoTime()).close() }
                    .onFailure { scene.render(System.nanoTime()).close() }
            }
        }
        override fun close() {
            desk.close()
            workspace.close()
            scope.cancel()
            WindowTitle.connectTo = null
            Appearance.preview(false, showcase = false)
            directory.toFile().deleteRecursively()
        }
    }
}

private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
    val out = ArrayList<SemanticsNode>()
    fun visit(node: SemanticsNode) {
        out.add(node)
        node.children.forEach { visit(it) }
    }
    scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
    return out
}

private fun nodeText(node: SemanticsNode): String {
    node.config.getOrNull(SemanticsProperties.EditableText)?.let { return it.text }
    return node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
}

private fun findExact(scene: ImageComposeScene, text: String) = nodes(scene).firstOrNull { nodeText(it) == text }
private fun findTextPrefix(scene: ImageComposeScene, prefix: String) = nodes(scene).firstOrNull { nodeText(it).startsWith(prefix) }

private fun editableAfter(scene: ImageComposeScene, label: String): SemanticsNode? {
    val all = nodes(scene)
    val index = all.indexOfFirst { nodeText(it).startsWith(label) }
    if (index < 0) return null
    return all.drop(index + 1).firstOrNull { it.config.getOrNull(SemanticsProperties.EditableText) != null }
}

private fun field(scene: ImageComposeScene, label: String) = editableAfter(scene, label)?.let { nodeText(it) }

private fun setField(scene: ImageComposeScene, label: String, value: String) {
    val node = editableAfter(scene, label) ?: error("no field $label")
    val set = node.config.getOrNull(SemanticsActions.SetText) ?: error("field $label cannot be set")
    check(set.action?.invoke(AnnotatedString(value)) != false)
}

private fun click(scene: ImageComposeScene, node: SemanticsNode) {
    val at: Offset = node.boundsInRoot.center
    scene.sendPointerEvent(PointerEventType.Move, at)
    scene.sendPointerEvent(PointerEventType.Press, at)
    scene.sendPointerEvent(PointerEventType.Release, at)
}

private fun clickText(scene: ImageComposeScene, text: String) {
    click(scene, nodes(scene).firstOrNull { nodeText(it) == text } ?: error("no node $text"))
}
