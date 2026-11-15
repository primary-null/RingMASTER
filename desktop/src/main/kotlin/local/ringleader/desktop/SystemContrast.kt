package local.ringleader.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** WCAG contrast ratio between two opaque colours: 1 (the same) to 21 (black on white). */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance(); val lb = b.luminance()
    return (maxOf(la, lb) + .05f) / (minOf(la, lb) + .05f)
}

/** The colours a system contrast theme asks for. Windows names them Background, Text, Selected text and Hyperlink. */
internal data class SystemContrastColors(val window: Color, val text: Color, val highlight: Color, val highlightText: Color, val hotlight: Color)

/**
 * High contrast, built in. Grounds are pure black or white, every edge is drawn in the text colour, and each text or
 * accent colour keeps at least 7:1 against the ground. Nothing is translucent, so no background image is drawn.
 */
val HighContrastDarkPalette = TechPalette(false,
    canvas = Color(0xFF000000), black = Color(0xFF000000), panel = Color(0xFF000000), raised = Color(0xFF1C1C1C), edge = Color(0xFFFFFFFF),
    ink = Color(0xFFFFFFFF), muted = Color(0xFFD9D9D9), amber = Color(0xFFFFD60A), green = Color(0xFF5CFF8F), red = Color(0xFFFF8A84),
    onAccent = Color(0xFF000000), amberWash = Color(0xFF332B00), highContrast = true)

val HighContrastLightPalette = TechPalette(true,
    canvas = Color(0xFFFFFFFF), black = Color(0xFFFFFFFF), panel = Color(0xFFFFFFFF), raised = Color(0xFFEDEDED), edge = Color(0xFF000000),
    ink = Color(0xFF000000), muted = Color(0xFF2E2E2E), amber = Color(0xFF7A2E00), green = Color(0xFF0A5A24), red = Color(0xFFA00000),
    onAccent = Color(0xFFFFFFFF), amberWash = Color(0xFFFFE6D1), highContrast = true)

/**
 * The palette for a system that asks for high contrast. With the system's own colours (Windows contrast themes) the app
 * takes them: its ground and text, its selection colour as the accent. Status green and red have no system colour; they
 * come from the built-in set and fall back to the text colour where the theme's ground would make them hard to read.
 * Without system colours, or with a pair that cannot be read, the built-in set for [light] is used.
 */
internal fun contrastPalette(system: SystemContrastColors?, light: Boolean): TechPalette {
    if (system == null || contrastRatio(system.text, system.window) < 3f) return if (light) HighContrastLightPalette else HighContrastDarkPalette
    val ground = system.window; val ink = system.text
    val lightGround = ground.luminance() > ink.luminance()
    val model = if (lightGround) HighContrastLightPalette else HighContrastDarkPalette
    // Selected rows, menus and dialogs sit one barely lifted shade above the ground; text has to read on both.
    val raised = lerp(ground, ink, .1f)
    fun readable(color: Color, least: Float = 4.5f) = minOf(contrastRatio(color, ground), contrastRatio(color, raised)) >= least
    val accent = listOf(system.highlight, system.hotlight).firstOrNull { readable(it) } ?: ink
    val onAccent = system.highlightText.takeIf { contrastRatio(it, accent) >= 4.5f } ?: listOf(ground, ink).maxBy { contrastRatio(it, accent) }
    val muted = lerp(ink, ground, .2f).takeIf { readable(it, 7f) } ?: ink
    return TechPalette(lightGround, canvas = ground, black = ground, panel = ground, raised = raised, edge = ink,
        ink = ink, muted = muted, amber = accent, green = model.green.takeIf { readable(it) } ?: ink, red = model.red.takeIf { readable(it) } ?: ink,
        onAccent = onAccent, amberWash = lerp(ground, accent, .22f), highContrast = true)
}

/**
 * Whether the operating system asks for high contrast. Windows: the contrast themes switch, read through AWT with the
 * theme's colours and watched for changes. Linux: the freedesktop appearance portal's `contrast`, GNOME's accessibility
 * switch, then a GTK or Plasma theme whose name says so (HighContrast, HighContrastInverse, ContrastHigh,
 * BreezeHighContrast). Anything else, an absent tool or any failure means no.
 */
internal object SystemContrast {
    /** Portal `Settings.Read`: `uint32 1` is higher contrast, `0` no preference. */
    fun parsePortal(output: String): Boolean? = when (Regex("""uint32\s+(\d+)""").find(output)?.groupValues?.get(1)?.toIntOrNull()) {
        1 -> true
        0 -> false
        else -> null
    }

    /** `gsettings get org.gnome.desktop.a11y.interface high-contrast`. */
    fun parseGnome(output: String): Boolean? = when (output.trim()) { "true" -> true; "false" -> false; else -> null }

    /** A theme or colour scheme name, with or without gsettings' quotes. */
    fun parseThemeName(output: String): Boolean? = output.trim().trim('\'').takeIf { it.isNotEmpty() }?.contains("contrast", ignoreCase = true)

    /** Linux only. [run] returns a tool's output, or null when it is missing, failed or timed out. [desktop] is `XDG_CURRENT_DESKTOP`. */
    fun detect(osName: String, desktop: String?, run: (List<String>) -> String?): Boolean {
        if (!osName.lowercase().contains("linux")) return false
        if (run(listOf("gdbus", "call", "--session", "--timeout", "2", "--dest", "org.freedesktop.portal.Desktop", "--object-path", "/org/freedesktop/portal/desktop",
                "--method", "org.freedesktop.portal.Settings.Read", "org.freedesktop.appearance", "contrast"))?.let(::parsePortal) == true) return true
        val where = desktop.orEmpty()
        val theme = when {
            where.contains("xfce", ignoreCase = true) -> run(listOf("xfconf-query", "-c", "xsettings", "-p", "/Net/ThemeName"))
            where.contains("kde", ignoreCase = true) -> run(listOf("kreadconfig6", "--group", "General", "--key", "ColorScheme"))
                ?: run(listOf("kreadconfig5", "--group", "General", "--key", "ColorScheme"))
            else -> {
                if (run(listOf("gsettings", "get", "org.gnome.desktop.a11y.interface", "high-contrast"))?.let(::parseGnome) == true) return true
                run(listOf("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme"))
            }
        }
        return theme?.let(::parseThemeName) ?: false
    }

    fun detect(): Boolean = runCatching { detect(System.getProperty("os.name").orEmpty(), System.getenv("XDG_CURRENT_DESKTOP"), SystemTheme::runTool) }.getOrDefault(false)

    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    private val watched = listOf("win.highContrast.on", "win.frame.backgroundColor", "win.frame.textColor", "win.item.highlightColor",
        "win.item.highlightTextColor", "win.item.hotTrackingColor")

    /** Windows contrast themes: the theme's colours while one is on. Null when off, not Windows, or unreadable. */
    fun windowsColors(): SystemContrastColors? = runCatching {
        if (!windows || java.awt.GraphicsEnvironment.isHeadless()) return null
        val kit = java.awt.Toolkit.getDefaultToolkit()
        if (kit.getDesktopProperty("win.highContrast.on") != true) return null
        fun color(key: String, fallback: java.awt.Color) = ((kit.getDesktopProperty(key) as? java.awt.Color) ?: fallback).let { Color(it.red, it.green, it.blue) }
        val highlight = color("win.item.highlightColor", java.awt.SystemColor.textHighlight)
        SystemContrastColors(color("win.frame.backgroundColor", java.awt.SystemColor.window), color("win.frame.textColor", java.awt.SystemColor.windowText),
            highlight, color("win.item.highlightTextColor", java.awt.SystemColor.textHighlightText),
            (kit.getDesktopProperty("win.item.hotTrackingColor") as? java.awt.Color)?.let { Color(it.red, it.green, it.blue) } ?: highlight)
    }.getOrNull()

    /** Calls [changed] on the event thread when Windows turns a contrast theme on or off, or switches to another one. */
    fun watchWindows(changed: () -> Unit) {
        if (!windows || java.awt.GraphicsEnvironment.isHeadless()) return
        runCatching {
            val kit = java.awt.Toolkit.getDefaultToolkit()
            // A desktop property reports changes only after it has been read once.
            watched.forEach { key -> kit.getDesktopProperty(key); kit.addPropertyChangeListener(key) { javax.swing.SwingUtilities.invokeLater(changed) } }
        }
    }
}
