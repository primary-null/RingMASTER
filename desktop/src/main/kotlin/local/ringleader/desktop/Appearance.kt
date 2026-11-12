package local.ringleader.desktop

import androidx.compose.ui.layout.onSizeChanged

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import local.ringleader.core.profileFile
import org.jetbrains.skia.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

/**
 * One full colour set. Dark is the original Tech look. Light is designed, not inverted: soft greys (never pure white),
 * lighter panels sitting on a slightly darker window, and accents darkened so text and thin strokes keep contrast.
 */
data class TechPalette(
    val light: Boolean,
    val canvas: Color,     // window background behind everything
    val black: Color,      // deepest tone: wells, row bases, chart grounds
    val panel: Color,
    val raised: Color,
    val edge: Color,
    val ink: Color,
    val muted: Color,
    val amber: Color,
    val green: Color,
    val red: Color,
    val onAccent: Color,   // text on an amber/red/green fill
    val amberWash: Color,  // faint amber fill for an active control
    val materialPanel: Color = panel,  // always opaque: menus and dialogs stay readable over a background image
    val materialRaised: Color = raised,
    val ground: Color = canvas,        // always opaque: the window's own background, behind a background image too
) {
    /** Over a background image the window clears and surfaces turn glassy; edges and text keep full strength. */
    fun withBackdrop() = copy(canvas = Color.Transparent, panel = panel.copy(alpha = if (light) .80f else .74f), raised = raised.copy(alpha = if (light) .86f else .82f),
        materialPanel = panel, materialRaised = raised)
}

val DarkPalette = TechPalette(false,
    canvas = Color(0xFF050505), black = Color(0xFF050505), panel = Color(0xFF101111), raised = Color(0xFF1B1D1D), edge = Color(0xFF363A39),
    ink = Color(0xFFF0F1EE), muted = Color(0xFF939B97), amber = Color(0xFFFF9800), green = Color(0xFF69F0AE), red = Color(0xFFFF7772),
    onAccent = Color(0xFF050505), amberWash = Color(0xFF2B2216))

// Toned down one step (owner, 2026-11-11): panels were near white and glared on large screens. Accents and muted text
// were deepened with the grounds so every text/ground pair keeps at least its earlier WCAG contrast.
val LightPalette = TechPalette(true,
    canvas = Color(0xFFCDD1D5), black = Color(0xFFC6CACF), panel = Color(0xFFDFE2E5), raised = Color(0xFFD3D7DB), edge = Color(0xFF9EA4AB),
    ink = Color(0xFF1A1D20), muted = Color(0xFF4E555C), amber = Color(0xFFB34D00), green = Color(0xFF17703A), red = Color(0xFFB71C1C),
    onAccent = Color(0xFFFFFFFF), amberWash = Color(0xFFEBD6BF))

/**
 * Pitch Black (owner, 2026-11-11): true black window, panels and wells for OLED screens, so unlit pixels stay off.
 * Panels are told apart by their edges, a touch brighter than Dark's; one barely lifted shade marks surfaces that sit on
 * top (selected rows, menus, dialogs). Text and accents keep the Dark values, which read even stronger on black.
 */
val PitchBlackPalette = TechPalette(false,
    canvas = Color(0xFF000000), black = Color(0xFF000000), panel = Color(0xFF000000), raised = Color(0xFF0F1010), edge = Color(0xFF3A3F3E),
    ink = Color(0xFFF0F1EE), muted = Color(0xFF939B97), amber = Color(0xFFFF9800), green = Color(0xFF69F0AE), red = Color(0xFFFF7772),
    onAccent = Color(0xFF000000), amberWash = Color(0xFF241A0C),
    materialPanel = Color(0xFF0A0B0B), materialRaised = Color(0xFF151717))

enum class ThemeMode(val label: String) { System("System"), Dark("Dark"), Light("Light"), PitchBlack("Pitch Black") }

/** The palette a theme draws with, before any background image. System follows the operating system's light or dark. */
fun basePalette(theme: ThemeMode, systemLight: Boolean): TechPalette = when (theme) {
    ThemeMode.Light -> LightPalette
    ThemeMode.PitchBlack -> PitchBlackPalette
    ThemeMode.Dark -> DarkPalette
    ThemeMode.System -> if (systemLight) LightPalette else DarkPalette
}
/** Blur radius as a fraction of the text size, so large titles hide as well as small labels. */
enum class ShowcaseStrength(val label: String, val factor: Float) { Light("Light", .22f), Medium("Medium", .32f), Strong("Strong", .45f) }

/** Share of the full blur kept while peeking. */
const val SHOWCASE_PEEK = .4f

/**
 * App-wide appearance state, persisted next to the profile file. Fields are snapshot state, so reading them in
 * composition or drawing recomposes or redraws when they change.
 */
object Appearance {
    private val file: Path get() = profileFile().resolveSibling("appearance.properties")
    val backgroundDir: Path get() = profileFile().resolveSibling("backgrounds")

    var textScale by mutableStateOf(1f); private set
    var theme by mutableStateOf(ThemeMode.Dark); private set
    var showcase by mutableStateOf(false); private set
    var strength by mutableStateOf(ShowcaseStrength.Strong); private set
    var background by mutableStateOf<String?>(null); private set   // file name inside backgroundDir
    var blur by mutableStateOf(22f); private set                   // px at 720px working size, 0..48
    var dim by mutableStateOf(.42f); private set                   // 0..0.8
    var vignette by mutableStateOf(.55f); private set              // 0..0.8
    /** What is drawn now. On a theme change it follows [targetPalette] once every [ThemeCrossfade] on screen has its picture of the old look. */
    var palette by mutableStateOf(DarkPalette); private set
    /** The palette the chosen theme and background ask for. */
    var targetPalette by mutableStateOf(DarkPalette); private set
    /** The one crossfade that switches [palette]; browser sessions compose their own copies, and only one may switch it. */
    private var transitionOwner: Any? = null
    /**
     * Whole-interface zoom on top of the OS scaling (Windows 125 % makes everything 1.25x), chosen per display: a 4K
     * screen, a 3K screen and a portrait 1080p screen each keep their own. A display nobody chose for uses Auto
     * ([autoInterfaceScale]). [displayKey] is the display the main window is on; browser mode uses "browser".
     */
    private val displayScales = androidx.compose.runtime.mutableStateMapOf<String, Float>()
    var displayKey by mutableStateOf<String?>(null); private set
    var display by mutableStateOf<DisplayInfo?>(null); private set
    private var autoScale by mutableStateOf(1f)
    val uiScale: Float get() = displayKey?.let { displayScales[it] } ?: autoScale
    val scaleIsAuto: Boolean get() = displayKey?.let { displayScales[it] } == null
    val autoScaleValue: Float get() = autoScale
    /** Alt held down: hovering a blurred value reveals it fully. Window key handling keeps it current. */
    var revealHeld by mutableStateOf(false)
    private var systemLight = false
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        val p = ConfigSchemas.properties(file)
        theme = runCatching { ThemeMode.valueOf(p.getProperty("theme", "Dark")) }.getOrDefault(ThemeMode.Dark)
        // Start-up waits a moment for the OS theme only when it is used; a slow tool never holds the window for long.
        detectSystemLight(if (theme == ThemeMode.System) 400 else 0)
        textScale = normalizeTextScale(p.getProperty("textScale")?.toFloatOrNull() ?: 1f)
        showcase = p.getProperty("showcase") == "true"
        strength = runCatching { ShowcaseStrength.valueOf(p.getProperty("strength", "Strong")) }.getOrDefault(ShowcaseStrength.Strong)
        background = p.getProperty("background")?.takeIf { it.matches(Regex("[0-9a-f]{16}\\.(jpg|png)")) && Files.isRegularFile(backgroundDir.resolve(it)) }
        blur = p.getProperty("blur")?.toFloatOrNull()?.coerceIn(0f, 48f) ?: 22f
        dim = p.getProperty("dim")?.toFloatOrNull()?.coerceIn(0f, .8f) ?: .42f
        vignette = p.getProperty("vignette")?.toFloatOrNull()?.coerceIn(0f, .8f) ?: .55f
        displayScales.clear()
        p.stringPropertyNames().filter { it.startsWith("uiScale.") }.forEach { k ->
            p.getProperty(k).toFloatOrNull()?.let { displayScales[k.removePrefix("uiScale.")] = it.coerceIn(MIN_SCALE, MAX_SCALE) }
        }
        // Before per-display sizes there was one value; it carries over to browser mode, where it applied as well.
        p.getProperty("uiScale")?.toFloatOrNull()?.takeIf { "browser" !in displayScales && it != 1f }?.let { displayScales["browser"] = it.coerceIn(MIN_SCALE, MAX_SCALE) }
        refresh()
    }

    val isLight get() = theme == ThemeMode.Light || (theme == ThemeMode.System && systemLight)

    /** Screenshot tests: switch theme and showcase in memory only; nothing is read from or written to disk. */
    internal fun preview(light: Boolean, showcase: Boolean = false, textScale: Float = 1f) =
        preview(if (light) ThemeMode.Light else ThemeMode.Dark, showcase, textScale)

    /** As [preview], for any theme. [ease] lets an attached [ThemeCrossfade] fade to it instead of switching at once. */
    internal fun preview(theme: ThemeMode, showcase: Boolean = false, textScale: Float = 1f, ease: Boolean = false) {
        this.textScale = normalizeTextScale(textScale)
        loaded = true; this.theme = theme; this.showcase = showcase; background = null; refresh()
        if (!ease) palette = targetPalette
    }

    internal fun attachTransition(owner: Any): Boolean = synchronized(this) {
        if (transitionOwner == null) transitionOwner = owner
        transitionOwner === owner
    }
    /** The switching crossfade left: the palette lands on the target at once. */
    internal fun detachTransition(owner: Any) = synchronized(this) {
        if (transitionOwner === owner) { transitionOwner = null; palette = targetPalette }
    }
    internal fun drivesTransition(owner: Any): Boolean = transitionOwner === owner
    internal fun showPalette(owner: Any, value: TechPalette) { if (transitionOwner === owner) palette = value }

    internal fun normalizeTextScale(value: Float): Float = if (!value.isFinite()) 1f else Math.round(value.coerceIn(.85f, 1.5f) * 20) / 20f
    fun chooseTextScale(value: Float) { textScale = normalizeTextScale(value); save() }

    private var systemCheckedAt = 0L
    /** System theme: ask the operating system again (the window came back to the front), at most every 20 s. A changed answer fades in. */
    fun recheckSystemTheme(now: Long = System.currentTimeMillis()) {
        if (theme != ThemeMode.System || now - systemCheckedAt < 20_000) return
        systemCheckedAt = now
        detectSystemLight(0)
    }

    fun chooseTheme(value: ThemeMode) { theme = value; refresh(); save(); if (value == ThemeMode.System) detectSystemLight(0) }
    fun chooseShowcase(on: Boolean) { showcase = on; save() }
    fun toggleShowcase() = chooseShowcase(!showcase)
    fun chooseStrength(value: ShowcaseStrength) { strength = value; save() }
    fun setBackdrop(blur: Float = this.blur, dim: Float = this.dim, vignette: Float = this.vignette, persist: Boolean = true) {
        this.blur = blur.coerceIn(0f, 48f); this.dim = dim.coerceIn(0f, .8f); this.vignette = vignette.coerceIn(0f, .8f)
        if (persist) save()
    }
    fun chooseBackground(name: String?) { background = name; refresh(); save() }
    /** The main window is now on [info]: its own size applies, or Auto for it. */
    fun useDisplay(info: DisplayInfo) {
        display = info; displayKey = info.key; autoScale = autoInterfaceScale(info.pixelWidth, info.pixelHeight, info.scale)
    }
    /** Browser mode has no monitor of its own; its size is kept under "browser". */
    fun useBrowserDisplay() { display = null; displayKey = "browser"; autoScale = 1f }
    /** A size for the current display; null returns it to Auto. */
    fun chooseScale(value: Float?) {
        val key = displayKey ?: return
        if (value == null) displayScales.remove(key) else displayScales[key] = Math.round(value.coerceIn(MIN_SCALE, MAX_SCALE) * 20) / 20f
        save()
    }
    fun zoom(steps: Int) = chooseScale(uiScale + steps * .05f)
    const val MIN_SCALE = .7f
    const val MAX_SCALE = 2f

    private fun refresh() {
        val base = basePalette(theme, systemLight)
        targetPalette = if (background != null) base.withBackdrop() else base
        // Without a driver (start-up, tests, a window that is not composed) the palette switches at once.
        if (transitionOwner == null) palette = targetPalette
    }

    private fun save() = runCatching {
        if (!loaded) load()
        if (!ConfigSchemas.writable(file)) return@runCatching
        val p = Properties()
        p.setProperty("schema", "1")
        p.setProperty("textScale", textScale.toString())
        p.setProperty("theme", theme.name); p.setProperty("showcase", showcase.toString()); p.setProperty("strength", strength.name)
        background?.let { p.setProperty("background", it) }
        p.setProperty("blur", blur.toString()); p.setProperty("dim", dim.toString()); p.setProperty("vignette", vignette.toString())
        displayScales.forEach { (k, v) -> p.setProperty("uiScale.$k", v.toString()) }
        Files.createDirectories(file.parent)
        val temp = Files.createTempFile(file.parent, "appearance-", ".tmp")
        try { Files.newBufferedWriter(temp).use { p.store(it, null) }; Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        finally { Files.deleteIfExists(temp) }
    }.onFailure { SaveStatus.report("Appearance", it) }

    /**
     * Reads the operating system theme on a background thread. [wait] is how long the caller may block for the answer
     * (start-up waits briefly only when the theme is System, so the first frame is already right); a slower answer
     * applies when it arrives. The tools themselves are killed after a few seconds, so nothing is left hanging.
     */
    private fun detectSystemLight(wait: Long) {
        val pending = java.util.concurrent.CompletableFuture.supplyAsync { SystemTheme.detect() }
        if (wait > 0) runCatching { systemLight = pending.get(wait, java.util.concurrent.TimeUnit.MILLISECONDS) }
        pending.thenAccept { light -> javax.swing.SwingUtilities.invokeLater { if (light != systemLight) { systemLight = light; refresh() } } }
    }
}

/**
 * Whether the operating system asks for a light theme. Windows: `AppsUseLightTheme`. Linux: the freedesktop appearance
 * portal's `color-scheme` (GNOME, KDE and others answer it), then Xfce's theme name, then the GNOME `color-scheme`
 * setting. Anything else, an absent tool or any failure means dark.
 */
internal object SystemTheme {
    /** `reg query ... /v AppsUseLightTheme`: 0x1 is light. */
    fun parseWindows(output: String): Boolean? =
        Regex("""AppsUseLightTheme\s+REG_DWORD\s+0x([0-9A-Fa-f]+)""").find(output)?.groupValues?.get(1)?.toIntOrNull(16)?.let { it != 0 }

    /** Portal `Settings.Read` or `ReadOne`: `uint32 1` is dark, `2` light, `0` no preference (null). */
    fun parsePortal(output: String): Boolean? = when (Regex("""uint32\s+(\d+)""").find(output)?.groupValues?.get(1)?.toIntOrNull()) {
        1 -> false
        2 -> true
        else -> null
    }

    /** `gsettings get org.gnome.desktop.interface color-scheme`: GNOME's 'default' is its light look. */
    fun parseGnome(output: String): Boolean? = when {
        output.contains("prefer-dark") -> false
        output.contains("prefer-light") || output.contains("'default'") -> true
        else -> null
    }

    /** `xfconf-query -c xsettings -p /Net/ThemeName`: a theme with "dark" in its name is dark, any other is light. */
    fun parseXfceTheme(output: String): Boolean? = output.trim().takeIf { it.isNotEmpty() }?.let { !it.contains("dark", ignoreCase = true) }

    /** [run] returns a tool's output, or null when it is missing, failed or timed out. [desktop] is `XDG_CURRENT_DESKTOP`. */
    fun detect(osName: String, desktop: String?, run: (List<String>) -> String?): Boolean {
        val os = osName.lowercase()
        if (os.startsWith("windows")) {
            val reg = System.getenv("SystemRoot")?.let { java.io.File(it, "System32/reg.exe") }?.takeIf { it.isFile }?.path ?: "reg"
            return run(listOf(reg, "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme"))?.let(::parseWindows) ?: false
        }
        if (!os.contains("linux")) return false
        run(listOf("gdbus", "call", "--session", "--timeout", "2", "--dest", "org.freedesktop.portal.Desktop", "--object-path", "/org/freedesktop/portal/desktop",
            "--method", "org.freedesktop.portal.Settings.Read", "org.freedesktop.appearance", "color-scheme"))?.let(::parsePortal)?.let { return it }
        if (desktop.orEmpty().contains("xfce", ignoreCase = true))
            run(listOf("xfconf-query", "-c", "xsettings", "-p", "/Net/ThemeName"))?.let(::parseXfceTheme)?.let { return it }
        return run(listOf("gsettings", "get", "org.gnome.desktop.interface", "color-scheme"))?.let(::parseGnome) ?: false
    }

    fun detect(): Boolean = runCatching { detect(System.getProperty("os.name").orEmpty(), System.getenv("XDG_CURRENT_DESKTOP"), ::runTool) }.getOrDefault(false)

    private fun runTool(command: List<String>): String? = try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.outputStream.close()
        if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); null }
        else if (process.exitValue() != 0) null
        else process.inputStream.bufferedReader().use { it.readText() }
    } catch (_: java.io.IOException) { null } catch (_: SecurityException) { null }
}

/**
 * Showcase blur for identifying text (guest, node and host names, IDs, addresses, token IDs, device names) so the
 * app can be shown or recorded safely. The radius follows the text size: the element's height stands in for its font
 * size (one line is about 1.35 x the font), capped so a multi-line block is not smeared. Hovering peeks at 40 %; hovering
 * while holding Alt reveals the value fully (deliberate, so a resting pointer never exposes anything during a demo).
 * Screen readers hear "Hidden" until it is revealed.
 */
fun Modifier.identity(): Modifier = composed {
    val on = Appearance.showcase
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var heightPx by remember { mutableStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fontDp = with(density) { (heightPx / 1.35f).toDp().value }.coerceIn(8f, 34f)
    val full = fontDp * Appearance.strength.factor
    val revealed = hovered && Appearance.revealHeld
    val target = when { !on || revealed -> 0f; hovered -> full * SHOWCASE_PEEK; else -> full }
    val radius by animateDpAsState(target.dp, tween(if (LocalReduceMotion.current) 0 else 320))
    val measured = this.onSizeChanged { heightPx = it.height }
    if (!on && radius.value == 0f) measured
    else measured.hoverable(interaction).then(if (on && !revealed) Modifier.semantics { contentDescription = "Hidden" } else Modifier)
        .blur(radius, BlurredEdgeTreatment.Unbounded)
}

/** Same-length bullet mask for identity input fields, so the caret mapping stays one to one. */
fun identityMask(text: String): String = text.map { if (it == ' ') ' ' else '•' }.joinToString("")

/** True when an identity input field should hide its value: showcase on, not being edited, not empty. */
fun identityFieldConcealed(showcase: Boolean, focused: Boolean, value: String): Boolean = showcase && !focused && value.isNotEmpty()

object Backgrounds {
    const val MAX_BYTES = 64L * 1024 * 1024
    private const val STORE_EDGE = 2560
    private const val WORK_EDGE = 720

    /**
     * Copies a picked image into the app's background folder: decoded with Skia (PNG, JPEG, WebP, GIF, BMP), scaled
     * to at most 2560 px on the long edge, saved as JPEG (PNG if it has transparency). Named by content hash, so the
     * same picture is stored once. Returns the stored file name.
     */
    fun import(source: Path): String {
        val size = Files.size(source)
        require(size in 1..MAX_BYTES) { "That image is larger than 64 MB." }
        val bytes = Files.readAllBytes(source)
        val image = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: error("That file is not an image RingMASTER can read.")
        val scale = minOf(1f, STORE_EDGE.toFloat() / maxOf(image.width, image.height))
        val w = (image.width * scale).toInt().coerceAtLeast(1); val h = (image.height * scale).toInt().coerceAtLeast(1)
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.drawImageRect(image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()), SamplingMode.MITCHELL, null, true)
        val scaled = surface.makeImageSnapshot()
        val alpha = image.imageInfo.colorAlphaType != ColorAlphaType.OPAQUE && hasTransparency(scaled)
        val data = scaled.encodeToData(if (alpha) EncodedImageFormat.PNG else EncodedImageFormat.JPEG, 90) ?: error("The image could not be saved.")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
        val name = "$hash.${if (alpha) "png" else "jpg"}"
        val dir = Appearance.backgroundDir
        Files.createDirectories(dir)
        val target = dir.resolve(name)
        if (!Files.exists(target)) {
            val temp = Files.createTempFile(dir, "bg-", ".tmp")
            try { Files.write(temp, data.bytes); Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE) } finally { Files.deleteIfExists(temp) }
        }
        return name
    }

    /** Removes stored backgrounds other than [keep]. */
    fun prune(keep: String?) = runCatching {
        Files.list(Appearance.backgroundDir).use { files -> files.filter { it.fileName.toString() != keep }.forEach { Files.deleteIfExists(it) } }
    }

    /** The stored image, blurred on the CPU once at a 720 px working size (a big blur on a small image looks the same and is cheap). */
    fun render(name: String, blur: Float): ImageBitmap? = runCatching {
        val image = Image.makeFromEncoded(Files.readAllBytes(Appearance.backgroundDir.resolve(name)))
        if (blur < .5f) return@runCatching image.toComposeImageBitmap()
        val scale = minOf(1f, WORK_EDGE.toFloat() / maxOf(image.width, image.height))
        val w = (image.width * scale).toInt().coerceAtLeast(1); val h = (image.height * scale).toInt().coerceAtLeast(1)
        val surface = Surface.makeRasterN32Premul(w, h)
        val paint = Paint().apply { imageFilter = ImageFilter.makeBlur(blur / 2f, blur / 2f, FilterTileMode.CLAMP) }
        surface.canvas.drawImageRect(image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()), SamplingMode.LINEAR, paint, true)
        surface.makeImageSnapshot().toComposeImageBitmap()
    }.getOrNull()

    private fun hasTransparency(image: Image): Boolean {
        val bitmap = Bitmap(); bitmap.allocN32Pixels(image.width, image.height); image.readPixels(bitmap)
        val stepX = maxOf(1, image.width / 64); val stepY = maxOf(1, image.height / 64)
        for (y in 0 until image.height step stepY) for (x in 0 until image.width step stepX) if ((bitmap.getColor(x, y) ushr 24) < 250) return true
        return false
    }
}

/**
 * Full-window background: the picked image (cover), a scrim at the dim level (black in the dark themes, the light canvas
 * grey in light), and a vignette toward the edges. The ground under it is the theme's own and fades with a theme change. Content draws on top; Tech surfaces are glassy while a background is set.
 */
@Composable fun BackdropHost(content: @Composable BoxScope.() -> Unit) {
    val name = Appearance.background
    val blur = Appearance.blur
    val image by produceState<ImageBitmap?>(null, name, blur) {
        value = if (name == null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Backgrounds.render(name, blur) }
    }
    val palette = Appearance.palette
    Box(Modifier.fillMaxSize().background(palette.ground)) {
        image?.let { bitmap ->
            Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            val scrim = if (palette.light) palette.ground else Color.Black
            val dim = Appearance.dim; val vignette = Appearance.vignette
            Box(Modifier.fillMaxSize().drawBehind {
                drawRect(scrim.copy(alpha = dim))
                val radius = maxOf(size.width, size.height) * .72f
                drawRect(Brush.radialGradient(0f to Color.Transparent, .55f to Color.Transparent, 1f to Color.Black.copy(alpha = vignette), center = center, radius = radius))
            })
        }
        content()
    }
}
