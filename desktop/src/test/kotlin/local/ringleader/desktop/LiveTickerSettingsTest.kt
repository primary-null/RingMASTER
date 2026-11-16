@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.nio.file.Files
import kotlin.test.*
import local.ringleader.core.*

/** Settings offers the tray's three modes in Motion and refresh and keeps the choice where the other motion preferences live. */
class LiveTickerSettingsTest {
    private fun withHome(block: suspend kotlinx.coroutines.CoroutineScope.(java.nio.file.Path) -> Unit) = runSceneTest {
        val home = System.getProperty("user.home")
        val sandbox = Files.createTempDirectory("ticker-settings-home")
        System.setProperty("user.home", sandbox.toString())
        try { block(sandbox) } finally { System.setProperty("user.home", home); LiveTickerPreference.preview(TickerMode.Automatic); sandbox.toFile().deleteRecursively() }
    }

    @Test fun theChoiceIsOfferedStoredAndReadBack() = withHome { sandbox ->
        Appearance.preview(false)
        LiveTickerPreference.preview(TickerMode.Automatic)
        val workspace = Workspace(this)
        val scene = ImageComposeScene(900, 800, coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) { SettingsSheet(WorkspaceState(), workspace, SettingsSection.Motion, {}, {}, {}) }
                }
            }
        }
        val ui = CreateScene(scene)
        try {
            ui.frames()
            ui.text("Live ticker"); ui.text("Automatic"); ui.text("Always open")
            // "Off" is also a choice of Keep consoles open, which comes first: the tray's is the last one on the page.
            suspend fun choose(label: String) {
                val node = ui.nodes().filter { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.equals(label, true) } == true }.last()
                val at = node.boundsInWindow.center
                scene.sendPointerEvent(PointerEventType.Move, at); scene.sendPointerEvent(PointerEventType.Press, at); scene.sendPointerEvent(PointerEventType.Release, at); ui.frames()
            }
            fun stored() = MotionPreferences().number(LiveTickerPreference.KEY, -1)
            choose("Always open")
            assertEquals(TickerMode.Always, LiveTickerPreference.mode); assertEquals(TickerMode.Always.stored, stored())
            choose("Off")
            assertEquals(TickerMode.Off, LiveTickerPreference.mode); assertEquals(TickerMode.Off.stored, stored())
            choose("Automatic")
            assertEquals(TickerMode.Automatic, LiveTickerPreference.mode); assertEquals(TickerMode.Automatic.stored, stored())
            assertTrue(Files.exists(sandbox.resolve(".config/ringmaster/motion.txt")), "kept beside the other motion preferences, in the sandbox home")
        } finally { scene.close(); workspace.close() }
    }

    @Test fun theBrowserHasNoUnderTheHoodAndSoNoSuchChoice() = withHome { _ ->
        Appearance.preview(false)
        val workspace = Workspace(this)
        val scene = ImageComposeScene(900, 800, coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides true, LocalAppHost provides AppHost(true)) {
                    Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) { SettingsSheet(WorkspaceState(), workspace, SettingsSection.Motion, {}, {}, {}) }
                }
            }
        }
        val ui = CreateScene(scene)
        try {
            ui.frames()
            ui.text("Keep consoles open")
            assertFails { ui.text("Live ticker") }
        } finally { scene.close(); workspace.close() }
    }
}
