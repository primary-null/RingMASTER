@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import local.ringleader.core.sampleWorkspace
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import local.ringleader.core.Workspace
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** High contrast: the built-in palettes, a system contrast theme's own colours, and how the system's request is found. */
class HighContrastTest {
    @AfterTest fun reset() = Appearance.preview(false)

    // Windows 11 contrast themes and the older High Contrast set: Background, Text, Selected, Selected text, Hyperlink.
    private val windowsThemes = mapOf(
        "Aquatic" to SystemContrastColors(Color(0xFF202020), Color(0xFFFFFFFF), Color(0xFF8EE3F0), Color(0xFF263B50), Color(0xFF75E9FC)),
        "Desert" to SystemContrastColors(Color(0xFFFFFAEF), Color(0xFF3D3D3D), Color(0xFF903909), Color(0xFFFFF5E3), Color(0xFF1C5E75)),
        "Dusk" to SystemContrastColors(Color(0xFF2D3236), Color(0xFFFFFFFF), Color(0xFFA1BFDE), Color(0xFF212D3B), Color(0xFF70EBDE)),
        "Night sky" to SystemContrastColors(Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFFD6B4FD), Color(0xFF2B2B2B), Color(0xFF8080FF)),
        "High Contrast Black" to SystemContrastColors(Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFF1AEBFF), Color(0xFF000000), Color(0xFFFFFF00)),
        "High Contrast White" to SystemContrastColors(Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFF37006E), Color(0xFFFFFFFF), Color(0xFF00009F)),
        // Selection colours too dark to read as text on black: the hyperlink colour becomes the accent.
        "High Contrast #1" to SystemContrastColors(Color(0xFF000000), Color(0xFFFFFF00), Color(0xFF008000), Color(0xFFFFFFFF), Color(0xFF8080FF)),
        "High Contrast #2" to SystemContrastColors(Color(0xFF000000), Color(0xFF00FF00), Color(0xFF0000FF), Color(0xFFFFFFFF), Color(0xFF8080FF)),
    )

    private fun checkReadable(name: String, p: TechPalette, text: Float) {
        assertTrue(p.highContrast, name)
        for (ground in listOf(p.canvas, p.black, p.panel, p.raised, p.materialPanel, p.materialRaised, p.ground)) {
            assertEquals(1f, ground.alpha, "$name: every surface is opaque")
            for ((what, color) in listOf("ink" to p.ink, "muted" to p.muted, "accent" to p.amber, "green" to p.green, "red" to p.red)) {
                assertTrue(contrastRatio(color, ground) >= text, "$name: $what on $ground is ${contrastRatio(color, ground)}")
            }
        }
        // Edges are as strong as the text, and text on an accent fill reads.
        assertTrue(contrastRatio(p.edge, p.panel) >= text, "$name: edge")
        assertTrue(contrastRatio(p.onAccent, p.amber) >= 4.5f, "$name: text on the accent is ${contrastRatio(p.onAccent, p.amber)}")
        // Text and the accent on an active control's wash.
        assertTrue(contrastRatio(p.ink, p.amberWash) >= 4.5f, "$name: ink on wash")
        assertTrue(contrastRatio(p.amber, p.amberWash) >= 3f, "$name: accent on wash is ${contrastRatio(p.amber, p.amberWash)}")
    }

    @Test fun builtInPalettesKeepSevenToOne() {
        checkReadable("dark", HighContrastDarkPalette, 7f)
        checkReadable("light", HighContrastLightPalette, 7f)
        assertEquals(Color(0xFF000000), HighContrastDarkPalette.canvas)
        assertEquals(Color(0xFFFFFFFF), HighContrastLightPalette.canvas)
        assertFalse(HighContrastDarkPalette.light); assertTrue(HighContrastLightPalette.light)
        // The other themes are not high contrast.
        listOf(DarkPalette, LightPalette, PitchBlackPalette).forEach { assertFalse(it.highContrast) }
        assertEquals(HighContrastDarkPalette, basePalette(ThemeMode.HighContrast, systemLight = false))
        assertEquals(HighContrastLightPalette, basePalette(ThemeMode.HighContrast, systemLight = true))
        assertEquals(ThemeMode.HighContrast, ThemeMode.valueOf("HighContrast"))
    }

    @Test fun aSystemContrastThemeGivesItsOwnColours() {
        for ((name, colors) in windowsThemes) {
            val p = contrastPalette(colors, light = false)
            assertEquals(colors.window, p.canvas, name); assertEquals(colors.window, p.panel, name)
            assertEquals(colors.text, p.ink, name); assertEquals(colors.text, p.edge, name)
            assertEquals(name in setOf("Desert", "High Contrast White"), p.light, name)
            checkReadable(name, p, 4.5f)
        }
        // The selection colour is the accent where it can be read on the ground; otherwise the hyperlink colour.
        assertEquals(Color(0xFF8EE3F0), contrastPalette(windowsThemes.getValue("Aquatic"), false).amber)
        assertEquals(Color(0xFF263B50), contrastPalette(windowsThemes.getValue("Aquatic"), false).onAccent)
        assertEquals(Color(0xFF8080FF), contrastPalette(windowsThemes.getValue("High Contrast #1"), false).amber)
        assertEquals(Color(0xFF000000), contrastPalette(windowsThemes.getValue("High Contrast #1"), false).onAccent)
    }

    @Test fun withoutUsableSystemColoursTheBuiltInSetIsUsed() {
        assertEquals(HighContrastDarkPalette, contrastPalette(null, light = false))
        assertEquals(HighContrastLightPalette, contrastPalette(null, light = true))
        val unreadable = SystemContrastColors(Color(0xFF202020), Color(0xFF303030), Color(0xFF8EE3F0), Color(0xFF263B50), Color(0xFF75E9FC))
        assertEquals(HighContrastDarkPalette, contrastPalette(unreadable, light = false))
    }

    @Test fun theSystemsRequestWinsOverTheThemeUnlessTurnedOff() {
        val desert = windowsThemes.getValue("Desert")
        for (theme in ThemeMode.entries) {
            Appearance.previewSystemContrast(desert, theme = theme)
            assertEquals(contrastPalette(desert, false), Appearance.palette, "$theme")
            assertTrue(Appearance.isLight)
        }
        // A Linux desktop gives no colours: the built-in set for its light or dark.
        Appearance.previewSystemContrast(null, light = true); assertEquals(HighContrastLightPalette, Appearance.palette)
        Appearance.previewSystemContrast(null, light = false); assertEquals(HighContrastDarkPalette, Appearance.palette)
        // Turned off, the chosen theme stays.
        Appearance.previewSystemContrast(desert, theme = ThemeMode.PitchBlack, follow = false)
        assertEquals(PitchBlackPalette, Appearance.palette)
        assertTrue(Appearance.systemContrast)
        // A preview never keeps an earlier request.
        Appearance.preview(ThemeMode.Dark)
        assertEquals(DarkPalette, Appearance.palette); assertFalse(Appearance.systemContrast)
        // Nor an earlier system's light look: the next test on the same JVM starts from a dark system.
        Appearance.previewSystemContrast(null, light = true); Appearance.preview(ThemeMode.HighContrast)
        assertEquals(HighContrastDarkPalette, Appearance.palette)
        Appearance.preview(ThemeMode.System); assertEquals(DarkPalette, Appearance.palette)
    }

    @Test fun highContrastDrawsNoBackgroundImageAndDoesNotDimText() {
        assertEquals(HighContrastDarkPalette, shownPalette(HighContrastDarkPalette, backgroundImage = true))
        assertEquals(DarkPalette.withBackdrop(), shownPalette(DarkPalette, backgroundImage = true))
        assertEquals(DarkPalette, shownPalette(DarkPalette, backgroundImage = false))
        Appearance.preview(ThemeMode.HighContrast)
        assertEquals(Color(0xFFFFFFFF), Color(0xFFFFFFFF).dimmed(.55f))
        Appearance.preview(ThemeMode.Dark)
        assertEquals(.55f, Color(0xFFFFFFFF).dimmed(.55f).alpha, .01f)
        assertEquals(.5f, Tech.Disabled.alpha, .01f)
    }

    @Test fun aDisabledLabelStaysReadableInHighContrast() {
        val looks = listOf<() -> Unit>({ Appearance.preview(ThemeMode.HighContrast) }, { Appearance.previewSystemContrast(null, light = true) }) +
            windowsThemes.values.map { colors -> { Appearance.previewSystemContrast(colors) } }
        for (look in looks) {
            look()
            val ratio = contrastRatio(Tech.Disabled, Tech.Canvas)
            assertTrue(ratio >= 4.5f, "disabled label is $ratio on ${Tech.Canvas}")
            assertTrue(ratio < contrastRatio(Tech.Ink, Tech.Canvas), "and still weaker than an enabled one")
        }
    }

    @Test fun parsers() {
        assertEquals(true, SystemContrast.parsePortal("(<<uint32 1>>,)"))
        assertEquals(false, SystemContrast.parsePortal("(<uint32 0>,)"))
        assertNull(SystemContrast.parsePortal("Error: GDBus.Error:org.freedesktop.portal.Error.NotFound: Requested setting not found"))
        assertEquals(true, SystemContrast.parseGnome("true\n")); assertEquals(false, SystemContrast.parseGnome("false\n")); assertNull(SystemContrast.parseGnome("No such schema"))
        for (name in listOf("HighContrast\n", "'HighContrastInverse'\n", "ContrastHigh", "BreezeHighContrast")) assertEquals(true, SystemContrast.parseThemeName(name), name)
        for (name in listOf("Greybird\n", "'Adwaita-dark'\n", "BreezeDark")) assertEquals(false, SystemContrast.parseThemeName(name), name)
        assertNull(SystemContrast.parseThemeName("  \n"))
        // The inverse high-contrast theme is a dark one.
        assertEquals(false, SystemTheme.parseXfceTheme("HighContrastInverse\n"))
        assertEquals(true, SystemTheme.parseXfceTheme("HighContrast\n"))
    }

    /** A fake tool runner: the first entry whose words all appear in the command answers; no entry is a missing tool. */
    private fun tools(vararg answers: Pair<String, String>): (List<String>) -> String? = { command ->
        val line = command.joinToString(" ") { File(it).name }
        answers.firstOrNull { (words, _) -> words.split(' ').all { it in line } }?.second
    }

    @Test fun linuxAsksThePortalThenTheDesktop() {
        // The portal's higher-contrast answer is enough on any desktop.
        assertTrue(SystemContrast.detect("Linux", "GNOME", tools("gdbus contrast" to "(<<uint32 1>>,)")))
        assertTrue(SystemContrast.detect("Linux", "KDE", tools("gdbus contrast" to "(<<uint32 1>>,)")))
        // No preference from the portal: GNOME's accessibility switch, then the GTK theme name.
        assertTrue(SystemContrast.detect("Linux", "ubuntu:GNOME", tools("gdbus contrast" to "(<<uint32 0>>,)", "gsettings high-contrast" to "true")))
        assertTrue(SystemContrast.detect("Linux", "GNOME", tools("gsettings high-contrast" to "false", "gsettings gtk-theme" to "'HighContrast'")))
        assertFalse(SystemContrast.detect("Linux", "GNOME", tools("gsettings high-contrast" to "false", "gsettings gtk-theme" to "'Adwaita-dark'")))
        // Xfce: its own theme name, not the GNOME schema (which may say anything there).
        assertTrue(SystemContrast.detect("Linux", "XFCE", tools("xfconf-query" to "HighContrastInverse", "gsettings high-contrast" to "false")))
        assertFalse(SystemContrast.detect("Linux", "XFCE", tools("xfconf-query" to "Greybird", "gsettings high-contrast" to "true", "gsettings gtk-theme" to "'HighContrast'")))
        // Plasma: the colour scheme, from whichever kreadconfig exists.
        assertTrue(SystemContrast.detect("Linux", "KDE", tools("kreadconfig5 ColorScheme" to "BreezeHighContrast")))
        assertFalse(SystemContrast.detect("Linux", "KDE", tools("kreadconfig6 ColorScheme" to "BreezeDark")))
        // Nothing answers, or another system: no.
        assertFalse(SystemContrast.detect("Linux", null, tools()))
        assertFalse(SystemContrast.detect("Windows 11", null, tools("gdbus contrast" to "(<<uint32 1>>,)")))
        assertFalse(SystemContrast.detect("Mac OS X", null, tools("gdbus contrast" to "(<<uint32 1>>,)")))
    }

    /** Fleet with the demo data and the Appearance settings, drawn in each kind of high contrast. RINGMASTER_CONTRAST_SHOTS saves the pictures. */
    @Test fun screensDrawOnTheContrastGround() = runSceneTest {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = sampleWorkspace(scope)
        try {
            delay(800)
            val state = workspace.state.value
            val looks = listOf<Pair<String, () -> Unit>>(
                "built-in-dark" to { Appearance.preview(ThemeMode.HighContrast) },
                "built-in-light" to { Appearance.previewSystemContrast(null, light = true) },
                "windows-aquatic" to { Appearance.previewSystemContrast(windowsThemes.getValue("Aquatic")) },
                "windows-desert" to { Appearance.previewSystemContrast(windowsThemes.getValue("Desert")) },
                "windows-night-sky" to { Appearance.previewSystemContrast(windowsThemes.getValue("Night sky")) },
            )
            for ((name, apply) in looks) {
                apply()
                val palette = Appearance.palette
                for (page in listOf("fleet", "settings")) {
                    val scene = ImageComposeScene(1440, 900, coroutineContext = coroutineContext) {
                        MaterialTheme(colorScheme = ringColors) {
                            CompositionLocalProvider(LocalReduceMotion provides true) {
                                // As the app's window: the theme's ground, and its text colour for text that names none.
                                BackdropHost {
                                    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = Tech.Ink) {
                                        Box(Modifier.fillMaxSize().padding(16.dp)) {
                                            if (page == "fleet") InventoryScreen(state, workspace, null, "", {}, "All", {}, {}, {})
                                            else Column(Modifier.background(Tech.Panel).padding(16.dp)) { AppearanceSettingsSection() }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    try {
                        CreateScene(scene).frames()
                        scene.render(900_000_000).use { image ->
                            val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
                            // The window's own ground, in the padding around the page.
                            for ((x, y) in listOf(4 to 4, 1435 to 4, 4 to 895, 1435 to 895)) {
                                assertEquals(palette.canvas.toArgb(), bitmap.getColor(x, y), "$name $page ground at $x,$y")
                            }
                            System.getenv("RINGMASTER_CONTRAST_SHOTS")?.let { folder ->
                                File(folder).mkdirs()
                                image.encodeToData()!!.use { File(folder, "$page-$name.png").writeBytes(it.bytes) }
                            }
                        }
                    } finally { scene.close() }
                }
            }
        } finally { workspace.close(); scope.cancel() }
    }
}
