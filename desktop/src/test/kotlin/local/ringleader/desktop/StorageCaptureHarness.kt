@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.yield
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import java.awt.Rectangle
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Storage UI capture harness.
 *
 * Root cause of the earlier "partial" storage captures: nothing in ImageComposeScene paints dirty rectangles. Each render
 * clears and redraws the whole scene. The partial-looking pictures were frames taken during real transitions: a nested
 * dialog fades/scales in over about nine 16 ms frames while the dialog below dims, and the focused Cancel action animates
 * its amber ring after the dialog layer takes focus (one frame after its first composition). A fresh scene's first raster
 * is therefore the pre-focus state, and a picture taken a few frames after a click is a blend of two dialogs.
 *
 * [settle] renders 16 ms frames until the scene reports no pending invalidations AND the last [STABLE_FRAMES] frames are
 * pixel-identical, so the retained picture is the state the user rests on. It fails (keeping the last two frames as
 * evidence) when that never happens. [verify] then checks that the semantics of that same settled frame match the case
 * contract, that required header/footer controls are inside the scene, and that each of them really has ink in the PNG.
 */
internal data class StorageLook(
    val id: String, val light: Boolean, val logicalWidth: Int, val logicalHeight: Int,
    val osScale: Float, val ui: Float, val text: Float, val kind: String,
) {
    val density: Float get() = osScale * ui
    val pixelWidth: Int get() = (logicalWidth * osScale).roundToInt()
    val pixelHeight: Int get() = (logicalHeight * osScale).roundToInt()
    val layoutWidthDp: Float get() = logicalWidth / ui
    val layoutHeightDp: Float get() = logicalHeight / ui
    val theme: String get() = if (light) "light" else "dark"
    /** Minimum native window for this interface size on a 1920x1080 display at OS 125 % (work area 1536x832 logical). */
    val minimum: java.awt.Dimension get() = minimumWindowSize(ui, Rectangle(0, 0, 1536, 832))
    val reachability: String get() = if (logicalWidth >= minimum.width && logicalHeight >= minimum.height) "reachable" else
        "below-floor: native minimum is ${minimum.width}x${minimum.height} logical at interface ${(ui * 100).roundToInt()} %"
}

internal fun storageLooks(): List<StorageLook> = buildList {
    for (light in listOf(false, true)) for ((w, h) in listOf(960 to 640, 1280 to 800, 1600 to 1000, 2560 to 1440))
        add(StorageLook("${if (light) "light" else "dark"}-${w}x$h", light, w, h, 1.25f, 1f, 1f, "standard"))
    // Interface 200 % + text 150 % at the real minimum window on a 1920x1080 125 % display: the window cannot be smaller.
    for (light in listOf(false, true)) add(StorageLook("${if (light) "light" else "dark"}-ui200-text150-1536x832", light, 1536, 832, 1.25f, 2f, 1.5f, "stress-reachable"))
    // Deliberately below the window floor: 800x500 logical is 400x250 dp of layout, which no native window can reach.
    add(StorageLook("dark-ui200-text150-belowfloor-800x500", false, 800, 500, 1.25f, 2f, 1.5f, "stress-below-floor"))
}

internal class StorageExpect(val text: String, val exact: Boolean = true, val control: Boolean = false, val chrome: Boolean = false)
internal fun title(text: String) = StorageExpect(text, chrome = true)
internal fun footer(text: String) = StorageExpect(text, control = true, chrome = true)
internal fun body(text: String, exact: Boolean = false) = StorageExpect(text, exact)

internal class StorageCaseContract(val expect: List<StorageExpect>, val forbid: List<String> = emptyList())

internal const val STABLE_FRAMES = 3
internal const val MAX_SETTLE_FRAMES = 240

internal class SettledFrame(val png: ByteArray, val pixels: ByteArray, val width: Int, val height: Int, val frames: Int)

private fun pixelsOf(image: Image): ByteArray = Bitmap.makeFromImage(image).let { b -> try { b.readPixels()!! } finally { b.close() } }
private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal class StorageSceneDriver(val scene: ImageComposeScene, val look: StorageLook) {
    var clock = 0L
        private set
    suspend fun frame() { clock += 16_000_000L; scene.render(clock).close(); yield() }
    suspend fun frames(n: Int) = repeat(n) { frame() }

    /** Render until the picture and the composition rest. Throws with before/after evidence when they do not. */
    suspend fun settle(evidence: File? = null, name: String = "unsettled"): SettledFrame {
        var last: String? = null; var same = 0; var previousPng: ByteArray? = null
        for (i in 1..MAX_SETTLE_FRAMES) {
            clock += 16_000_000L
            val image = scene.render(clock)
            try {
                val pixels = pixelsOf(image); val hash = sha(pixels)
                same = if (hash == last) same + 1 else 0; last = hash
                yield()
                if (same >= STABLE_FRAMES - 1 && !scene.hasInvalidations())
                    return SettledFrame(image.encodeToData()!!.bytes, pixels, image.width, image.height, i)
                if (i >= MAX_SETTLE_FRAMES - 1) previousPng = image.encodeToData()!!.bytes
            } finally { image.close() }
        }
        evidence?.let { dir -> dir.mkdirs(); previousPng?.let { File(dir, "${name}__before.png").writeBytes(it) }
            scene.render(clock + 16_000_000L).use { File(dir, "${name}__after.png").writeBytes(it.encodeToData()!!.bytes) } }
        error("$name: the scene did not settle within $MAX_SETTLE_FRAMES frames")
    }

    fun nodes(): List<SemanticsNode> = allSemanticsNodes(scene)
    fun matches(text: String, exact: Boolean = true) = nodes().filter { n ->
        val v = semanticsText(n); if (exact) v.equals(text, true) else v.contains(text, true)
    }
    fun clickable(text: String, last: Boolean = true): SemanticsNode {
        val found = matches(text).let { if (last) it.reversed() else it }.firstNotNullOfOrNull { n ->
            var p: SemanticsNode? = n; while (p != null && p.config.getOrNull(SemanticsActions.OnClick) == null) p = p.parent; p
        }
        return checkNotNull(found) { "No clickable '$text' in ${nodes().map(::semanticsText).filter { it.isNotBlank() }}" }
    }
    fun click(text: String, last: Boolean = true) {
        val n = clickable(text, last)
        check(n.config.getOrNull(SemanticsProperties.Disabled) == null) { "'$text' is disabled" }
        check(n.config[SemanticsActions.OnClick].action!!.invoke()) { "'$text' refused the click" }
    }
}

internal fun storageScene(look: StorageLook, context: kotlin.coroutines.CoroutineContext, content: @Composable () -> Unit): ImageComposeScene {
    Appearance.preview(look.light, textScale = look.text)
    return ImageComposeScene(look.pixelWidth, look.pixelHeight, Density(look.density, look.text), coroutineContext = context) {
        MaterialTheme(colorScheme = ringColors) {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) { content() }
            }
        }
    }
}

/** A required visible control must contain drawn ink that differs from the surrounding plate. */
internal fun inkFraction(frame: SettledFrame, r: Rect): Double {
    val x0 = r.left.toInt().coerceIn(0, frame.width - 1); val x1 = r.right.toInt().coerceIn(0, frame.width)
    val y0 = r.top.toInt().coerceIn(0, frame.height - 1); val y1 = r.bottom.toInt().coerceIn(0, frame.height)
    if (x1 <= x0 || y1 <= y0) return 0.0
    fun lum(x: Int, y: Int): Int { val i = (y * frame.width + x) * 4; val b = frame.pixels[i].toInt() and 255; val g = frame.pixels[i + 1].toInt() and 255; val rr = frame.pixels[i + 2].toInt() and 255; return (rr * 3 + g * 6 + b) / 10 }
    val counts = IntArray(256); var total = 0
    for (y in y0 until y1) for (x in x0 until x1) { counts[lum(x, y)]++; total++ }
    val background = counts.indices.maxBy { counts[it] }
    var ink = 0
    for (y in y0 until y1) for (x in x0 until x1) if (abs(lum(x, y) - background) > 40) ink++
    return ink.toDouble() / total
}

/** Bounding box of every pixel that differs from the top-left canvas/scrim pixel, and the hash of that crop. */
internal fun contentCrop(frame: SettledFrame): Pair<IntArray, String> {
    val w = frame.width; val h = frame.height; val p = frame.pixels
    fun same(i: Int) = p[i] == p[0] && p[i + 1] == p[1] && p[i + 2] == p[2] && p[i + 3] == p[3]
    var minX = w; var minY = h; var maxX = -1; var maxY = -1
    for (y in 0 until h) for (x in 0 until w) if (!same((y * w + x) * 4)) { if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y }
    if (maxX < 0) return intArrayOf(0, 0, 0, 0) to "empty"
    val d = MessageDigest.getInstance("SHA-256")
    for (y in minY..maxY) d.update(p, (y * w + minX) * 4, (maxX - minX + 1) * 4)
    return intArrayOf(minX, minY, maxX, maxY) to d.digest().joinToString("") { "%02x".format(it) }
}

internal class StorageVerified(val frame: SettledFrame, val scrollable: Boolean, val chromeInk: Map<String, Double>)

/** Semantic contract, chrome reachability and pixel ink of the settled frame. */
internal fun StorageSceneDriver.verify(caseId: String, contract: StorageCaseContract, frame: SettledFrame): StorageVerified {
    val all = nodes()
    val sceneRect = Rect(0f, 0f, frame.width.toFloat(), frame.height.toFloat())
    val ink = linkedMapOf<String, Double>()
    contract.expect.forEach { e ->
        val found = all.filter { n -> val v = semanticsText(n); if (e.exact) v.equals(e.text, true) else v.contains(e.text, true) }
        check(found.isNotEmpty()) { "$caseId: missing ${if (e.exact) "exact" else "containing"} '${e.text}'. Present: ${all.map(::semanticsText).filter { it.isNotBlank() }.distinct()}" }
        if (e.control) check(found.any { it.hasClickAction() }) { "$caseId: '${e.text}' is not a control" }
        if (e.chrome) {
            val node = found.last()
            val b = node.boundsInWindow
            check(b.width > 0 && b.height > 0 && b.left >= sceneRect.left - .5f && b.top >= sceneRect.top - .5f && b.right <= sceneRect.right + .5f && b.bottom <= sceneRect.bottom + .5f) {
                "$caseId: '${e.text}' is outside the ${frame.width}x${frame.height} scene at $b" }
            val f = inkFraction(frame, b)
            check(f > 0.01) { "$caseId: semantics place '${e.text}' at $b but the picture has no ink there (${"%.4f".format(f)})" }
            ink[e.text] = f
        }
    }
    val texts = all.map(::semanticsText)
    contract.forbid.forEach { bad -> check(texts.none { it.contains(bad, true) }) { "$caseId: forbidden '$bad' is present" } }
    val scrollers = all.filter { n -> n.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { it.maxValue() > 0f } == true }
    val scrollable = scrollers.isNotEmpty()
    // Every scrolling modal body's 12 dp scrollbar gutter must end at its plate's 16 dp inner edge, not wherever the
    // body's longest line ends. The plate is the modal's focusable frame (TechModal), the nearest focusable ancestor.
    scrollers.forEach { n ->
        var p = n.parent
        while (p != null && p.config.getOrNull(SemanticsProperties.Focused) == null) p = p.parent
        val plate = p?.boundsInWindow ?: return@forEach
        if (plate.left <= 0.5f || plate.right >= frame.width - 0.5f) return@forEach // a page (the wizard), not a modal plate
        val gutterEnd = n.boundsInWindow.right + 12 * look.density
        check(gutterEnd >= plate.right - 18 * look.density) { "$caseId: scrollbar gutter ends at $gutterEnd, its plate edge is ${plate.right}" }
    }
    return StorageVerified(frame, scrollable, ink)
}

/** Crop signatures of pictures already retained in [root] by earlier shards of the same source; other sources are refused. */
internal fun loadStorageCrops(root: File, source: String): MutableMap<String, String> {
    val crops = mutableMapOf<String, String>()
    root.listFiles { f -> f.name.startsWith("manifest-") && f.name.endsWith(".tsv") }.orEmpty().forEach { m ->
        val lines = m.readLines(); val head = lines.first().split('	')
        lines.drop(1).filter { it.isNotBlank() }.map { l -> head.zip(l.split('	')).toMap() }.forEach { r ->
            check(r["source_sha256"] == source) { "${m.name} was made from other sources. Use a new output folder." }
            val png = File(root, r.getValue("path"))
            if (r["alias_of"].isNullOrEmpty()) { check(png.isFile && sha(png.readBytes()) == r["frame_sha256"]) { "${png.name} does not match ${m.name}" }; crops.putIfAbsent(r.getValue("signature"), r.getValue("case_id")) }
        }
    }
    return crops
}

internal class StorageManifest(private val root: File, private val source: String, private val crops: MutableMap<String, String> = mutableMapOf()) {
    private val rows = mutableListOf<String>()
    val retained = mutableListOf<File>()
    val ids = mutableListOf<String>()
    val header = listOf("case_id", "group", "state", "theme", "look_kind", "os_scale", "interface_scale", "text_scale", "density",
        "logical_width", "logical_height", "layout_width_dp", "layout_height_dp", "physical_width_px", "physical_height_px",
        "minimum_window_logical", "reachability", "frames_to_settle", "body_scrolls", "expectations", "forbidden", "chrome_ink",
        "source_sha256", "frame_sha256", "crop_box", "crop_sha256", "signature", "alias_of", "path").joinToString("\t")

    fun record(caseId: String, group: String, state: String, look: StorageLook, contract: StorageCaseContract, v: StorageVerified) {
        check(caseId !in ids) { "Duplicate case $caseId" }
        val frame = v.frame
        check(frame.width == look.pixelWidth && frame.height == look.pixelHeight) { "$caseId: frame ${frame.width}x${frame.height} is not ${look.pixelWidth}x${look.pixelHeight}" }
        val (box, crop) = contentCrop(frame)
        // Identical content on an identical uniform canvas at the same density/text/theme: keep one picture, record the alias.
        // A scrolled "-end" picture identical to its unscrolled state (nothing to scroll) is an alias of it as well.
        val corner = frame.pixels.copyOfRange(0, 4).joinToString(",")
        val signature = listOf(group, state.removeSuffix("-end"), look.theme, look.density, look.text, corner, crop).joinToString("|")
        val key = sha(signature.toByteArray())
        val alias = if (box[0] > 0 && box[1] > 0 && box[2] < frame.width - 1 && box[3] < frame.height - 1) crops[key] else null
        val relative = if (alias == null) "$group/$state/${look.id}.png" else ""
        if (alias == null) {
            crops.putIfAbsent(key, caseId)
            val file = File(root, relative).apply { parentFile.mkdirs(); writeBytes(frame.png) }
            retained += file
        }
        ids += caseId
        val cell = { s: Any? -> s.toString().replace('\t', ' ').replace('\n', ' ') }
        rows += listOf(caseId, group, state, look.theme, look.kind, look.osScale, look.ui, look.text, look.density,
            look.logicalWidth, look.logicalHeight, look.layoutWidthDp, look.layoutHeightDp, look.pixelWidth, look.pixelHeight,
            "${look.minimum.width}x${look.minimum.height}", look.reachability, frame.frames, v.scrollable,
            contract.expect.joinToString(";") { (if (it.exact) "=" else "~") + it.text + (if (it.chrome) "@chrome" else "") },
            contract.forbid.joinToString(";"), v.chromeInk.entries.joinToString(";") { "${it.key}=${"%.3f".format(it.value)}" },
            source, sha(frame.png), box.joinToString(","), crop, sha(signature.toByteArray()), alias.orEmpty(), relative).joinToString("\t", transform = cell)
    }

    fun write(expected: List<String>, name: String = "manifest.tsv") {
        val missing = expected - ids.toSet(); val extra = ids.toSet() - expected.toSet()
        check(missing.isEmpty()) { "Missing storage cases: $missing" }
        check(extra.isEmpty()) { "Unexpected storage cases: $extra" }
        File(root, name).writeText((listOf(header) + rows).joinToString("\n", postfix = "\n"))
    }
}
