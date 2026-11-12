package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Bitmap
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PitchBlackThemeTest {
    @AfterTest fun reset() = Appearance.preview(false)

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test fun pitchBlackGroundsAreTrueBlack() {
        val p = PitchBlackPalette
        listOf(p.canvas, p.black, p.panel, p.ground).forEach { assertEquals(Color(0xFF000000), it) }
        assertFalse(p.light)
        // Surfaces that sit on top are lifted only barely, so the screen stays black.
        listOf(p.raised, p.materialPanel, p.materialRaised).forEach { assertTrue(it.luminance() < 0.01f, "$it is more than a barely lifted shade") }
    }

    @Test fun pitchBlackReadsAtLeastAsWellAsDark() {
        val p = PitchBlackPalette; val d = DarkPalette
        listOf(p.ink to d.ink, p.muted to d.muted, p.amber to d.amber, p.green to d.green, p.red to d.red).forEach { (pb, dk) ->
            assertTrue(contrast(pb, p.panel) >= contrast(dk, d.panel), "text on Pitch Black must read at least as well as on Dark")
            assertTrue(contrast(pb, p.raised) >= contrast(dk, d.raised))
        }
        // Panels are told apart by their edges alone, so the edge is at least as visible as Dark's.
        assertTrue(contrast(p.edge, p.panel) >= contrast(d.edge, d.panel))
        assertTrue(contrast(p.onAccent, p.amber) >= 4.5)
    }

    @Test fun themesMapToTheirPalettes() {
        assertEquals(PitchBlackPalette, basePalette(ThemeMode.PitchBlack, systemLight = true))
        assertEquals(DarkPalette, basePalette(ThemeMode.Dark, systemLight = true))
        assertEquals(LightPalette, basePalette(ThemeMode.Light, systemLight = false))
        assertEquals(LightPalette, basePalette(ThemeMode.System, systemLight = true))
        assertEquals(DarkPalette, basePalette(ThemeMode.System, systemLight = false))
        assertEquals("Pitch Black", ThemeMode.PitchBlack.label)
        assertEquals(ThemeMode.PitchBlack, ThemeMode.valueOf("PitchBlack"))
        // A background image keeps the opaque ground, so the window behind it is still the theme's own black.
        assertEquals(Color(0xFF000000), PitchBlackPalette.withBackdrop().ground)
        assertEquals(Color.Transparent, PitchBlackPalette.withBackdrop().canvas)
    }

    @Test fun previewSwitchesAtOnceWithoutACrossfade() {
        Appearance.preview(ThemeMode.PitchBlack)
        assertEquals(PitchBlackPalette, Appearance.palette)
        assertEquals(PitchBlackPalette, Appearance.targetPalette)
    }

    @Test fun reduceMotionKeepsABriefFadeAndSoftwareDrawingAShorterOne() {
        assertTrue(paletteFadeMillis(MotionTier.Reduced) in 1 until paletteFadeMillis(MotionTier.Lite))
        assertTrue(paletteFadeMillis(MotionTier.Lite) < paletteFadeMillis(MotionTier.Full))
        assertTrue(paletteFadeMillis(MotionTier.Full) <= 500, "a theme change should be graceful, not slow")
    }

    // ---- The crossfade, measured on the pixels it draws ----------------------------------------------------------

    private val ms = 1_000_000L

    /** A window-sized ground with a block of text colour on it, inside a crossfade. */
    private fun scene(fade: ThemeFade, reduce: Boolean = false) = ImageComposeScene(120, 80, Density(1f)) {
        CompositionLocalProvider(LocalReduceMotion provides reduce) {
            ThemeCrossfade(Modifier.fillMaxSize(), fade) {
                Box(Modifier.fillMaxSize().background(Tech.Canvas)) { Box(Modifier.size(30.dp).background(Tech.Ink)) }
            }
        }
    }

    /** Brightness 0..255 of the ground (far corner) and of the text block, as drawn at [timeMs]. */
    private fun sample(scene: ImageComposeScene, timeMs: Long): Pair<Int, Int> = scene.render(timeMs * ms).use { image ->
        val bitmap = Bitmap.makeFromImage(image)
        fun grey(x: Int, y: Int) = bitmap.getColor(x, y).let { c -> ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000 }
        grey(110, 70) to grey(10, 10)
    }

    private class Run(val ground: List<Int>, val text: List<Int>)

    /** Renders 16 ms frames from [fromMs] to [toMs] and keeps what each frame showed. */
    private fun run(scene: ImageComposeScene, fromMs: Long, toMs: Long): Run {
        val g = mutableListOf<Int>(); val t = mutableListOf<Int>()
        var at = fromMs
        while (at <= toMs) { sample(scene, at).let { (a, b) -> g += a; t += b }; at += 16 }
        return Run(g, t)
    }

    @Test fun aThemeChangeCrossfadesAndLandsOnTheNewLook() {
        Appearance.preview(ThemeMode.PitchBlack)
        val fade = ThemeFade()
        val s = scene(fade)
        try {
            run(s, 0, 32)
            Appearance.preview(ThemeMode.Light, ease = true)
            val r = run(s, 48, 900)
            assertEquals(LightPalette, Appearance.palette)
            assertNull(fade.picture, "the old picture is gone once the fade ends")
            val black = 0; val light = r.ground.last()
            assertTrue(light > 180, "ends on the Light ground")
            // No flash: the first frames still show the old look, then it moves one way only, in small steps.
            assertTrue(r.ground.first() < 20, "the first frame after the change still shows Pitch Black")
            val steps = r.ground.zipWithNext { a, b -> b - a }
            assertTrue(steps.all { it >= -1 }, "the fade never goes back on itself: ${r.ground}")
            assertTrue(steps.max() <= (light - black) / 8, "no frame carries more than an eighth of the change: ${r.ground}")
            assertTrue(r.ground.count { it in 30..(light - 30) } >= 8, "the change is spread over many frames, not cut: ${r.ground}")
        } finally { s.close() }
    }

    @Test fun textIsFaintOnlyForAnInstantDuringTheFade() {
        // The hardest case: light text on black becoming dark text on light grey. Any fade between opposite looks has an
        // instant where a text pixel and its ground blend to the same grey; the crossfade passes it at full speed (the
        // middle of its curve), so it lasts a few frames, while panels and accents, which cross at other moments, stay
        // visible. Measured at 60 frames a second.
        Appearance.preview(ThemeMode.PitchBlack)
        val s = scene(ThemeFade())
        try {
            run(s, 0, 32)
            Appearance.preview(ThemeMode.Light, ease = true)
            val r = run(s, 48, 900)
            val gaps = r.ground.zip(r.text) { g, t -> abs(t - g) }
            assertTrue(gaps.count { it < 40 } <= 3, "text was faint for ${gaps.count { it < 40 }} frames: $gaps")
            assertTrue(gaps.first() > 150 && gaps.last() > 150, "full contrast before and after: $gaps")
        } finally { s.close() }
    }

    @Test fun aChangeDuringAFadeCarriesOnFromWhatIsOnScreen() {
        Appearance.preview(ThemeMode.PitchBlack)
        val fade = ThemeFade()
        val s = scene(fade)
        try {
            run(s, 0, 32)
            Appearance.preview(ThemeMode.Light, ease = true)
            val before = run(s, 48, 240).ground
            Appearance.preview(ThemeMode.Dark, ease = true)
            val after = run(s, 256, 1200).ground
            val end = sample(s, 1216).first
            assertEquals(DarkPalette, Appearance.palette)
            // The frame right after the second change continues from the last one: no jump to either end.
            assertTrue(abs(after.first() - before.last()) <= 200 / 8, "jumped from ${before.last()} to ${after.first()}")
            assertTrue(abs(end - 5) <= 6, "ends on the Dark ground ($end)")
            assertNull(fade.picture)
        } finally { s.close() }
    }

    @Test fun reduceMotionStillFadesButBriefly() {
        Appearance.preview(ThemeMode.Light)
        val fade = ThemeFade()
        val s = scene(fade, reduce = true)
        try {
            run(s, 0, 32)
            Appearance.preview(ThemeMode.PitchBlack, ease = true)
            val r = run(s, 48, 400)
            assertTrue(r.ground.count { it in 30..170 } >= 2, "a brief fade, not a cut: ${r.ground}")
            assertTrue(r.ground.indexOfFirst { it < 10 } in 1..((paletteFadeMillis(MotionTier.Reduced) + 80) / 16), "done within the reduced fade: ${r.ground}")
            assertNull(fade.picture)
        } finally { s.close() }
    }

    @Test fun onlyOneCrossfadeSwitchesTheColoursAndEveryOneFades() {
        Appearance.preview(ThemeMode.Dark)
        val windowFade = ThemeFade(); val dialogFade = ThemeFade()
        val window = scene(windowFade); val dialog = scene(dialogFade)
        try {
            run(window, 0, 32); run(dialog, 0, 32)
            Appearance.preview(ThemeMode.Light, ease = true)
            run(dialog, 48, 120)
            assertEquals(DarkPalette, Appearance.palette, "the dialog's crossfade does not switch the colours")
            assertNotNull(dialogFade.picture, "but it has its picture of the old look ready")
            run(window, 48, 120)
            assertEquals(LightPalette, Appearance.palette, "the window's crossfade switches them")
            run(window, 136, 900); run(dialog, 136, 900)
            assertNull(windowFade.picture); assertNull(dialogFade.picture)
        } finally { window.close(); dialog.close() }
    }

    @Test fun leavingInTheMiddleLandsOnTheTarget() {
        Appearance.preview(ThemeMode.Dark)
        val s = scene(ThemeFade())
        run(s, 0, 32)
        Appearance.preview(ThemeMode.PitchBlack, ease = true)
        s.close()
        assertEquals(PitchBlackPalette, Appearance.palette)
    }
}
