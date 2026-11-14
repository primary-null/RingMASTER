package local.ringleader.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import local.ringleader.core.Workspace
import java.io.File
import kotlin.test.Test

/**
 * Opt-in pictures of the themes and of a theme change as it fades: RINGMASTER_THEME_SHOTS=<folder>. Demo data only;
 * never connects to a server. Writes fleet-<theme>.png, appearance-<theme>.png and fade/frame-NNN.png (33 ms apart).
 */
class ThemeShotsTest {
    @Test fun shots() {
        val out = System.getenv("RINGMASTER_THEME_SHOTS")?.let(::File) ?: return
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = Workspace(scope)
        workspace.exploreDemo(); workspace.setLive(false)
        val ms = 1_000_000L
        try {
            out.mkdirs()
            for (theme in listOf(ThemeMode.Dark, ThemeMode.PitchBlack, ThemeMode.Light)) {
                Appearance.preview(theme)
                val name = theme.name.lowercase()
                render(1440, 900) { val state by workspace.state.collectAsState(); InventoryScreen(state, workspace, null, "", {}, "All", {}, {}, {}) }
                    .let { File(out, "fleet-$name.png").writeBytes(it) }
                render(760, 820) { Column(Modifier.width(720.dp).verticalScroll(rememberScrollState())) { AppearanceSettingsSection() } }
                    .let { File(out, "appearance-$name.png").writeBytes(it) }
            }
            // The fade itself: Dark, then Pitch Black, then Light, then back to Dark, as the window would draw it.
            Appearance.preview(ThemeMode.Dark)
            val fade = File(out, "fade").apply { mkdirs() }
            val scene = ImageComposeScene(1440, 900, Density(1f)) {
                MaterialTheme(colorScheme = ringColors) {
                    ThemeCrossfade(Modifier.fillMaxSize()) {
                        Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                            val state by workspace.state.collectAsState()
                            Box(Modifier.fillMaxSize().padding(16.dp)) { InventoryScreen(state, workspace, null, "", {}, "All", {}, {}, {}) }
                        }
                    }
                }
            }
            try {
                var frame = 0; var time = 0L
                fun hold(millis: Int) { repeat(millis / 33) { time += 33; scene.render(time * ms).use { File(fade, "frame-%03d.png".format(frame++)).writeBytes(it.encodeToData()!!.bytes) } } }
                hold(400)
                for (next in listOf(ThemeMode.PitchBlack, ThemeMode.Light, ThemeMode.Dark)) {
                    Appearance.preview(next, ease = true)
                    hold(paletteFadeMillis(MotionTier.Full) + 600)
                }
            } finally { scene.close() }
        } finally { Appearance.preview(false); workspace.close(); scope.cancel() }
    }

    private fun render(w: Int, h: Int, content: @androidx.compose.runtime.Composable () -> Unit): ByteArray {
        val scene = ImageComposeScene(w, h, Density(1f)) {
            MaterialTheme(colorScheme = ringColors) {
                Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) { Box(Modifier.fillMaxSize().padding(16.dp)) { content() } }
            }
        }
        return try {
            var image = scene.render(0)
            repeat(3) { i -> image.close(); image = scene.render((i + 1) * 16_000_000L) }
            image.use { it.encodeToData()!!.bytes }
        } finally { scene.close() }
    }
}
