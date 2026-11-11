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
) {
    /** Over a background image the window clears and surfaces turn glassy; edges and text keep full strength. */
    fun withBackdrop() = copy(canvas = Color.Transparent, panel = panel.copy(alpha = if (light) .80f else .74f), raised = raised.copy(alpha = if (light) .86f else .82f),
        materialPanel = panel, materialRaised = raised)
}

val DarkPalette = TechPalette(false,
    canvas = Color(0xFF050505), black = Color(0xFF050505), panel = Color(0xFF101111), raised = Color(0xFF1B1D1D), edge = Color(0xFF363A39),
    ink = Color(0xFFF0F1EE), muted = Color(0xFF939B97), amber = Color(0xFFFF9800), green = Color(0xFF69F0AE), red = Color(0xFFFF7772),
    onAccent = Color(0xFF050505), amberWash = Color(0xFF2B2216))

val LightPalette = TechPalette(true,
    canvas = Color(0xFFDDE0E3), black = Color(0xFFD3D6DA), panel = Color(0xFFEDEEF0), raised = Color(0xFFE0E3E6), edge = Color(0xFFADB2B8),
    ink = Color(0xFF1A1D20), muted = Color(0xFF575E66), amber = Color(0xFFC25400), green = Color(0xFF1B7A3A), red = Color(0xFFC62828),
    onAccent = Color(0xFFFFFFFF), amberWash = Color(0xFFF5E2CC))

enum class ThemeMode(val label: String) { System("System"), Dark("Dark"), Light("Light") }
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
    var palette by mutableStateOf(DarkPalette); private set
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
        systemLight = detectSystemLight()
        val p = ConfigSchemas.properties(file)
        theme = runCatching { ThemeMode.valueOf(p.getProperty("theme", "Dark")) }.getOrDefault(ThemeMode.Dark)
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
    internal fun preview(light: Boolean, showcase: Boolean = false, textScale: Float = 1f) {
        this.textScale = normalizeTextScale(textScale)
        loaded = true; theme = if (light) ThemeMode.Light else ThemeMode.Dark; this.showcase = showcase; background = null; refresh()
    }

    internal fun normalizeTextScale(value: Float): Float = if (!value.isFinite()) 1f else Math.round(value.coerceIn(.85f, 1.5f) * 20) / 20f
    fun chooseTextScale(value: Float) { textScale = normalizeTextScale(value); save() }

    fun chooseTheme(value: ThemeMode) { theme = value; refresh(); save() }
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
        val base = if (isLight) LightPalette else DarkPalette
        palette = if (background != null) base.withBackdrop() else base
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

    /** Windows: AppsUseLightTheme; Linux: the GNOME color-scheme. Anything else, or any failure, means dark. */
    private fun detectSystemLight(): Boolean = runCatching {
        val os = System.getProperty("os.name").lowercase()
        val command = when {
            os.startsWith("windows") -> listOf("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme")
            os.contains("linux") -> listOf("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")
            else -> return false
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        if (os.startsWith("windows")) Regex("AppsUseLightTheme\\s+REG_DWORD\\s+0x1").containsMatchIn(out) else out.contains("prefer-light") || out.contains("'default'")
    }.getOrDefault(false)
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
 * Full-window background: the picked image (cover), a scrim at the dim level (black in dark, the light canvas grey in
 * light), and a vignette toward the edges. Content draws on top; Tech surfaces are glassy while a background is set.
 */
@Composable fun BackdropHost(content: @Composable BoxScope.() -> Unit) {
    val name = Appearance.background
    val blur = Appearance.blur
    val image by produceState<ImageBitmap?>(null, name, blur) {
        value = if (name == null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Backgrounds.render(name, blur) }
    }
    val palette = Appearance.palette
    Box(Modifier.fillMaxSize().background(if (palette.light) LightPalette.canvas else DarkPalette.canvas)) {
        image?.let { bitmap ->
            Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            val scrim = if (palette.light) LightPalette.canvas else Color.Black
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
