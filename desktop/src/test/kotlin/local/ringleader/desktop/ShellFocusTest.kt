package local.ringleader.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Keyboard focus shows on the shell's own controls, the switch is a 24dp target, and pins move from the keyboard. */
class ShellFocusTest {
    @Test fun pinRowKeys() {
        assertEquals(PinRowKey.MoveUp, pinRowKey(Key.DirectionUp, alt = true, shift = false))
        assertEquals(PinRowKey.MoveDown, pinRowKey(Key.DirectionDown, alt = true, shift = false))
        assertEquals(PinRowKey.Menu, pinRowKey(Key.F10, alt = false, shift = true))
        assertEquals(PinRowKey.Menu, pinRowKey(Key.Menu, alt = false, shift = false))
        // Plain arrows stay with focus movement; plain F10 is not the menu.
        assertNull(pinRowKey(Key.DirectionUp, alt = false, shift = false))
        assertNull(pinRowKey(Key.F10, alt = false, shift = false))
    }

    private val amber get() = Appearance.palette.amber
    private fun near(argb: Int, color: androidx.compose.ui.graphics.Color): Boolean {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        return kotlin.math.abs(r - (color.red * 255).toInt()) < 40 && kotlin.math.abs(g - (color.green * 255).toInt()) < 40 && kotlin.math.abs(b - (color.blue * 255).toInt()) < 40
    }

    /** Renders [content] at 1x with [focus] requested or not, and returns the pixels. */
    private fun render(focus: Boolean, content: @androidx.compose.runtime.Composable (Modifier) -> Unit): org.jetbrains.skia.Bitmap = runBlocking {
        val requester = FocusRequester()
        val scene = ImageComposeScene(200, 80, Density(1f), coroutineContext = coroutineContext) {
            Box(Modifier.padding(20.dp)) { content(Modifier.focusRequester(requester)) }
            LaunchedEffect(Unit) { if (focus) requester.requestFocus() }
        }
        try {
            repeat(4) { scene.render(System.nanoTime()).close(); yield() }
            scene.render(System.nanoTime()).use { org.jetbrains.skia.Bitmap.makeFromImage(it) }
        } finally { scene.close() }
    }

    @Test fun aFocusedCheckBoxShowsARingOutsideTheBox() {
        // The 16dp box sits at (20, 26) inside a 28dp row; the ring is 3dp outside it, at x = 17.
        val plain = render(false) { m -> TechCheck(false, {}, "Option", m) }
        val focused = render(true) { m -> TechCheck(false, {}, "Option", m) }
        assertFalse(near(plain.getColor(17, 34), amber), "no ring without focus")
        assertTrue(near(focused.getColor(17, 34), amber), "ring with focus, was ${Integer.toHexString(focused.getColor(17, 34))}")
    }

    @Test fun aFocusedTabShowsARing() {
        val plain = render(false) { m -> TechTabs(listOf("Summary", "Console"), "Console", {}, m) }
        val focused = render(true) { m -> TechTabs(listOf("Summary", "Console"), "Console", {}, m) }
        // Left edge of the first (not selected) tab, half way down.
        assertFalse(near(plain.getColor(20, 36), amber))
        assertTrue(near(focused.getColor(20, 36), amber), "was ${Integer.toHexString(focused.getColor(20, 36))}")
    }

    @Test fun theRestoreMarkIsNotTheMaximiseMark() {
        val ink = androidx.compose.ui.graphics.Color.White
        fun glyph(g: WindowGlyph) = render(false) { m -> androidx.compose.foundation.Canvas(m.size(10.dp)) { drawWindowGlyph(g, ink) } }
        val max = glyph(WindowGlyph.Maximize); val restore = glyph(WindowGlyph.Restore); val min = glyph(WindowGlyph.Minimize)
        // Maximise has its top-left corner lit; Restore's front square starts lower, so that corner is dark.
        assertTrue(near(max.getColor(20, 20), ink)); assertFalse(near(restore.getColor(20, 20), ink))
        // The minimise bar is one solid pixel row.
        assertTrue(near(min.getColor(25, 25), ink), "was ${Integer.toHexString(min.getColor(25, 25))}")
    }

    @Test fun anInteractiveSwitchIsA24dpTarget() {
        var height = 0; var shown = 0
        render(false) { m -> TechSwitch(true, {}, m.onSizeChanged { height = it.height }) }
        render(false) { m -> TechSwitch(true, null, m.onSizeChanged { shown = it.height }) }
        assertEquals(SWITCH_TARGET, height)
        assertEquals(18, shown, "a switch that only shows state keeps its drawn size")
    }
}
