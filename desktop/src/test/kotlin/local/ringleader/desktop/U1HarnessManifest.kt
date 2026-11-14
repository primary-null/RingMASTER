package local.ringleader.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.awt.Rectangle
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.roundToInt

internal const val MATRIX_TITLE_BAR_DP = 32f

/** Bind resumed evidence to actual sources, including uncommitted changes and fixture/resources changes. */
internal fun matrixSourceFingerprint(root: File): String {
    val inputs = listOf("core/src", "desktop/src", "gradle", "build.gradle.kts", "settings.gradle.kts",
        "gradle.properties", "core/build.gradle.kts", "desktop/build.gradle.kts").flatMap { path ->
        val file = File(root, path)
        if (file.isDirectory) file.walkTopDown().filter { it.isFile }.toList() else listOfNotNull(file.takeIf { it.isFile })
    }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }
    check(inputs.isNotEmpty()) { "No matrix sources found." }
    val digest = MessageDigest.getInstance("SHA-256")
    for (file in inputs) {
        digest.update((file.relativeTo(root).invariantSeparatorsPath + "\u0000" + file.length() + "\u0000").toByteArray(Charsets.UTF_8))
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun bindMatrixSource(dir: File, fingerprint: String) {
    val marker = File(dir, "source-sha256.txt")
    if (marker.exists()) {
        check(marker.readText().trim() == fingerprint) {
            "Matrix sources changed. Use a new output directory; previous frames remain evidence for their original sources."
        }
    } else {
        check(!File(dir, "valid-frames.tsv").exists()) {
            "Existing frames have no source fingerprint. Use a new output directory."
        }
        dir.mkdirs()
        marker.writeText("$fingerprint\n")
    }
}

/** The AWT logical work area used when deciding whether a window recipe can really be reached. */
internal data class MatrixDisplay(
    val name: String,
    val osScale: Float,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val workWidth: Int,
    val workHeight: Int,
) {
    init {
        require(osScale > 0f && osScale.isFinite())
        require(pixelWidth > 0 && pixelHeight > 0 && workWidth > 0 && workHeight > 0)
    }

    fun info() = DisplayInfo(
        key = "matrix-$name",
        pixelWidth = pixelWidth,
        pixelHeight = pixelHeight,
        scale = osScale.toDouble(),
        refreshHz = 60,
        bounds = Rectangle(0, 0, (pixelWidth / osScale).roundToInt(), (pixelHeight / osScale).roundToInt()),
        work = Rectangle(0, 0, workWidth, workHeight),
    )
}

internal val matrixFhdDisplay = MatrixDisplay("fhd-os100", 1f, 1920, 1080, 1920, 1040)
internal val matrixLargeDisplay = MatrixDisplay("large-os100", 1f, 3840, 2160, 3840, 2120)
internal val matrixLaptopDisplay = MatrixDisplay("laptop-os100", 1f, 1366, 768, 1366, 728)
internal val matrixFhd125Display = MatrixDisplay("fhd-os125", 1.25f, 1920, 1080, 1536, 832)

/**
 * A native window recipe. Width and height are AWT logical window units and INCLUDE the custom title bar, just like
 * Window.minimumSize and a Windows snap rectangle. [aliases] records shapes that collapse to this exact geometry rather
 * than emitting duplicate PNGs (at 100 %, a 640-wide tall column is clamped to the same 960-wide window as the half snap).
 */
internal data class MatrixSize(
    val name: String,
    val windowWidth: Int,
    val windowHeight: Int,
    val display: MatrixDisplay = matrixFhdDisplay,
    val deliberateStress: Boolean = false,
    val aliases: Set<String> = emptySet(),
)

/** Theme, OS scale/display, interface size and text size. */
internal data class MatrixLook(
    val name: String,
    val theme: ThemeMode,
    val ui: Float,
    val text: Float,
    val displayOverride: MatrixDisplay? = null,
)

internal data class MatrixGeometry(
    val size: MatrixSize,
    val look: MatrixLook,
    val display: MatrixDisplay,
    val sceneWidthPx: Int,
    val sceneHeightPx: Int,
    val titleBarLogical: Float,
    val contentWidthLogical: Float,
    val contentHeightLogical: Float,
    val layoutWidthDp: Float,
    val layoutContentHeightDp: Float,
    val minimumWidthLogical: Int,
    val minimumHeightLogical: Int,
    val reachable: Boolean,
    val reachabilityReason: String,
) {
    val density: Float get() = display.osScale * look.ui
    val visualSignature: String get() = listOf(
        sceneWidthPx, sceneHeightPx, "%.3f".format(density), "%.3f".format(look.text), look.theme.name,
        "%.3f".format(layoutWidthDp), "%.3f".format(layoutContentHeightDp),
    ).joinToString("|")
}

internal fun matrixGeometry(size: MatrixSize, look: MatrixLook): MatrixGeometry {
    val display = look.displayOverride ?: size.display
    val title = MATRIX_TITLE_BAR_DP * look.ui
    val minimum = minimumWindowSize(look.ui, Rectangle(0, 0, display.workWidth, display.workHeight))
    val belowMinimum = size.windowWidth < minimum.width || size.windowHeight < minimum.height
    val beyondWork = size.windowWidth > display.workWidth || size.windowHeight > display.workHeight
    val reachable = !belowMinimum && !beyondWork
    val reason = when {
        reachable -> "reachable"
        belowMinimum && beyondWork -> "deliberate stress: below minimum and beyond work area"
        belowMinimum -> "deliberate stress: below minimum"
        else -> "deliberate stress: beyond work area"
    }
    check(reachable || size.deliberateStress) {
        "${look.name}/${size.name} is not reachable ($reason), but the recipe is not labelled deliberateStress"
    }
    return MatrixGeometry(
        size = size,
        look = look,
        display = display,
        sceneWidthPx = (size.windowWidth * display.osScale).roundToInt(),
        sceneHeightPx = (size.windowHeight * display.osScale).roundToInt(),
        titleBarLogical = title,
        contentWidthLogical = size.windowWidth.toFloat(),
        contentHeightLogical = (size.windowHeight - title).coerceAtLeast(0f),
        layoutWidthDp = size.windowWidth / look.ui,
        layoutContentHeightDp = (size.windowHeight / look.ui - MATRIX_TITLE_BAR_DP).coerceAtLeast(0f),
        minimumWidthLogical = minimum.width,
        minimumHeightLogical = minimum.height,
        reachable = reachable,
        reachabilityReason = reason,
    )
}

internal enum class MatrixGroup(val id: String) {
    Desk("desk"), Fleet("fleet"), Guest("guest"), Pages("pages"), Settings("settings"), Dialogs("dialogs"), Create("create")
}

/** The named U1 acceptance states. Extra stress/end states may be present, but none of these may disappear silently. */
internal val u1RequiredStateNames = setOf(
    "desk-saved", "desk-selected", "desk-manual", "desk-new-profile", "desk-editor",
    "fleet-vm", "fleet-ct", "fleet-node", "fleet-closed", "fleet-vm-rail",
    "guest-summary", "guest-console-rail-open", "guest-console-rail-folded", "guest-hardware", "guest-snapshots", "guest-backups", "guest-bluetooth", "guest-actions", "guest-ct-console",
    "storage", "activity-log", "scan-results", "bluetooth-results", "vpn", "tools-result", "hood",
    "settings-appearance", "settings-size", "settings-motion", "settings-background", "settings-browser", "settings-updates", "settings-about",
    "dlg-power-hardstop", "dlg-pin-service", "dlg-clear", "dlg-chart-lens", "dlg-update-prompt",
    "create-vm", "create-ct",
)

internal data class MatrixExpectation(val text: String, val exact: Boolean = true, val control: Boolean = false)

internal data class MatrixStateContract(
    val group: MatrixGroup,
    val expectations: List<MatrixExpectation>,
    val limitation: String = "",
)

private fun exact(text: String, control: Boolean = false) = MatrixExpectation(text, exact = true, control = control)
private fun contains(text: String, control: Boolean = false) = MatrixExpectation(text, exact = false, control = control)

/** Every fixture has a semantic contract; an unknown name is a harness error, not an unverified screenshot. */
internal fun matrixStateContract(name: String): MatrixStateContract {
    fun state(group: MatrixGroup, vararg expectations: MatrixExpectation, limitation: String = "") =
        MatrixStateContract(group, expectations.toList(), limitation)
    return when {
        name == "desk-saved" -> state(MatrixGroup.Desk, exact("Saved connections"), exact("Sample PVE"))
        name == "desk-selected" -> state(MatrixGroup.Desk, exact("Remember on this computer"), exact("Connect", control = true))
        name == "desk-empty" -> state(MatrixGroup.Desk, exact("Quick start"), exact("No saved connections"))
        name == "desk-manual" -> state(MatrixGroup.Desk, exact("Connect"), contains("Connect without saving a profile"))
        name == "desk-new-profile" -> state(MatrixGroup.Desk, exact("New profile"), contains("Display name"))
        name == "desk-editor" -> state(MatrixGroup.Desk, exact("Edit profile"), contains("Display name"))
        name == "desk-connected" -> state(MatrixGroup.Desk, exact("Open workspace", control = true), exact("Disconnect", control = true))

        name == "fleet-closed" || name == "fleet-closed-end" -> state(MatrixGroup.Fleet, exact("Fleet overview"), exact("New VM", control = true))
        name.startsWith("fleet-node") -> state(MatrixGroup.Fleet, exact("Close", control = true), contains("pve-"))
        name.startsWith("fleet-") -> state(MatrixGroup.Fleet, exact("Open details", control = true), exact("Close", control = true))

        name == "guest-summary" || name == "guest-summary-long" || name == "guest-summary-stopped" || name == "guest-summary-end" ->
            state(MatrixGroup.Guest, exact("Summary"), exact("Addresses"))
        name == "guest-hardware" || name == "guest-hardware-end" || name == "guest-ct-hardware" ->
            state(MatrixGroup.Guest, exact("Hardware"), exact("Disks"))
        name == "guest-snapshots" -> state(MatrixGroup.Guest, exact("Snapshots"), exact("Create snapshot", control = true))
        name == "guest-snapshots-form" -> state(MatrixGroup.Guest, exact("Close form", control = true), contains("Snapshot name"))
        name == "guest-backups" -> state(MatrixGroup.Guest, exact("Backups"), contains("shared-backup-storage-primary"))
        name == "guest-bluetooth" -> state(MatrixGroup.Guest, exact("Bluetooth"), contains("Attached to this VM"), contains("Scan inside this VM"))
        name == "guest-actions" || name == "guest-ct-actions" -> state(MatrixGroup.Guest, exact("Actions"), exact("Hard stop", control = true))
        name == "guest-ct-summary" || name == "guest-ct-long-summary" -> state(MatrixGroup.Guest, exact("Summary"), exact("Addresses"))
        name == "guest-console-rail-open" ->
            state(MatrixGroup.Guest, exact("Console"), exact("Guests"), exact("‹", control = true))
        name == "guest-console-rail-folded" ->
            state(MatrixGroup.Guest, exact("Console"), exact("›", control = true))
        name == "guest-ct-console" -> state(
            MatrixGroup.Guest,
            exact("Console"), contains("Connecting"),
            limitation = "Synthetic CT console covers the guest-page connecting state only; it does not prove a live terminal session, native keyboard input, or server transport.",
        )

        name == "storage" -> state(MatrixGroup.Pages, exact("Storage"), exact("local-lvm"), exact("Refresh", control = true))
        name == "storage-empty" -> state(MatrixGroup.Pages, exact("Storage"), contains("Pick a storage"))
        name == "activity" -> state(MatrixGroup.Pages, exact("Recent tasks"), exact("Disconnect", control = true))
        name == "activity-log" -> state(MatrixGroup.Pages, exact("Backup"), contains("INFO: starting new backup job"), exact("Close", control = true))
        name == "scan-results" -> state(MatrixGroup.Pages, exact("Services"), contains("Complete"))
        name == "scan-idle" -> state(MatrixGroup.Pages, exact("Ready to discover"), contains("Start a scan"))
        name == "bluetooth-results" -> state(MatrixGroup.Pages, exact("Devices"), exact("Keychron K2"))
        name == "bluetooth-empty" -> state(MatrixGroup.Pages, contains("Bluetooth"), contains("scan"))
        name == "vpn" -> state(MatrixGroup.Pages, exact("Tunnels"), exact("Tailnet"))
        name == "tools-result" -> state(MatrixGroup.Pages, exact("Tools"), contains("Ping statistics"))
        name == "tools-idle" -> state(MatrixGroup.Pages, exact("Tools"))
        name == "hood" -> state(MatrixGroup.Pages, contains("Every instrumented operation"), exact("Pause", control = true))

        name == "settings-updates-available" -> state(MatrixGroup.Settings, exact("Settings"), exact("Updates"), contains("RingMASTER 0.24.0"), exact("Done", control = true))
        name == "settings-background" -> state(MatrixGroup.Settings, exact("Settings"), exact("Background and tray"),
            exact("Keep running in the notification area when the window is closed"), exact("Done", control = true))
        name.startsWith("settings-") -> {
            val heading = when (name.removePrefix("settings-")) {
                "appearance" -> "Appearance"
                "size" -> "Size and text"
                "motion" -> "Motion and refresh"
                "browser" -> "Browser access"
                "updates" -> "Updates"
                "about" -> "About"
                else -> error("No Settings heading contract for '$name'")
            }
            state(MatrixGroup.Settings, exact("Settings"), exact(heading), exact("Done", control = true))
        }
        name == "dlg-pin-service" -> state(MatrixGroup.Dialogs, exact("Pin a service"), exact("Cancel", control = true))
        name == "dlg-clear" -> state(MatrixGroup.Dialogs, exact("Clear everything?"), exact("Clear and quit", control = true))
        name == "dlg-update-prompt" -> state(MatrixGroup.Dialogs, exact("Check for updates?"), exact("Not now", control = true))
        name == "dlg-chart-lens" -> state(MatrixGroup.Dialogs, contains("CPU"), exact("Live", control = true))
        name == "dlg-power-hardstop" -> state(MatrixGroup.Dialogs, exact("Confirm Hard stop"), exact("Hard stop", control = true))

        name == "create-vm" -> state(MatrixGroup.Create, contains("New VM"), exact("Create VM 140…", control = true))
        name == "create-ct" -> state(MatrixGroup.Create, contains("Create container"), exact("Simulate create", control = true))
        else -> error("No U1 state contract for '$name'")
    }
}

internal fun allSemanticsNodes(scene: ImageComposeScene): List<SemanticsNode> = buildList {
    fun visit(node: SemanticsNode) {
        add(node)
        node.children.forEach(::visit)
    }
    // Keep the real parent/child relationship. In the merged tree a menu item's text can be copied onto a synthetic
    // node that has no path back to the row's OnClick action, which makes clipped stress-state preparation impossible.
    scene.semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
}

internal fun semanticsText(node: SemanticsNode): String =
    node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

internal fun SemanticsNode.hasClickAction(): Boolean {
    var current: SemanticsNode? = this
    while (current != null) {
        if (current.config.getOrNull(SemanticsActions.OnClick) != null) return true
        current = current.parent
    }
    return false
}

internal fun assertMatrixState(scene: ImageComposeScene, shot: MatrixShot): List<SemanticsNode> {
    val nodes = allSemanticsNodes(scene)
    shot.contract.expectations.forEach { expected ->
        val matches = nodes.filter { node ->
            val value = semanticsText(node)
            if (expected.exact) value.equals(expected.text, ignoreCase = true)
            else value.contains(expected.text, ignoreCase = true)
        }
        check(matches.isNotEmpty()) { "${shot.name}: expected ${if (expected.exact) "exact" else "containing"} text '${expected.text}'" }
        check(!expected.control || matches.any { it.hasClickAction() }) { "${shot.name}: '${expected.text}' is present but is not an enabled or disabled control" }
    }
    return nodes
}

internal data class MatrixCase(val shot: MatrixShot, val look: MatrixLook, val size: MatrixSize, val geometry: MatrixGeometry) {
    val id: String = "${shot.group.id}/${shot.name}/${look.name}/${size.name}"
    val relativePath: String = "${shot.name}/${look.name}__${size.name}.png"
}

internal fun matrixCases(shots: List<MatrixShot>): List<MatrixCase> = buildList {
    matrixLooks.forEach { look ->
        matrixSizesForLook.getValue(look.name).forEach { sizeName ->
            val size = matrixSize(sizeName)
            shots.forEach { shot -> add(MatrixCase(shot, look, size, matrixGeometry(size, look))) }
        }
    }
}.sortedBy { it.id }

internal fun matrixManifestHeader() = listOf(
    "case_id", "group", "state", "page", "theme", "os_scale", "interface_scale", "text_scale", "window_logical_width", "window_logical_height",
    "title_bar_logical_height", "content_logical_width", "content_logical_height", "layout_width_dp", "layout_content_height_dp", "scene_pixel_width",
    "scene_pixel_height", "work_logical_width", "work_logical_height", "minimum_logical_width", "minimum_logical_height", "reachability", "aliases", "limitation", "path",
).joinToString("\t")

private fun tsv(value: Any?): String = value?.toString().orEmpty().replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')

internal fun MatrixCase.manifestLine(): String {
    val g = geometry
    return listOf(
        id, shot.group.id, shot.name, shot.page, look.theme.name, g.display.osScale, look.ui, look.text, size.windowWidth, size.windowHeight,
        g.titleBarLogical, g.contentWidthLogical, g.contentHeightLogical, g.layoutWidthDp, g.layoutContentHeightDp, g.sceneWidthPx, g.sceneHeightPx,
        g.display.workWidth, g.display.workHeight, g.minimumWidthLogical, g.minimumHeightLogical,
        if (g.reachable) "reachable" else g.reachabilityReason, size.aliases.sorted().joinToString(","), shot.contract.limitation, relativePath,
    ).joinToString("\t", transform = ::tsv)
}

internal data class ValidFrame(val caseId: String, val path: String, val width: Int, val height: Int, val sha256: String) {
    fun line() = listOf(caseId, path, width, height, sha256).joinToString("\t")
}

internal const val VALID_FRAME_HEADER = "case_id\tpath\twidth\theight\tsha256"

internal fun readValidFrames(file: File): MutableMap<String, ValidFrame> {
    if (!file.isFile) return linkedMapOf()
    return file.readLines().drop(1).filter { it.isNotBlank() }.associateTo(linkedMapOf()) { line ->
        val p = line.split('\t')
        check(p.size == 5) { "Malformed valid frame row: $line" }
        p[0] to ValidFrame(p[0], p[1], p[2].toInt(), p[3].toInt(), p[4])
    }
}

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun validFrameOnDisk(root: File, expected: MatrixCase, frame: ValidFrame): Boolean {
    if (frame.caseId != expected.id || frame.path != expected.relativePath) return false
    if (frame.width != expected.geometry.sceneWidthPx || frame.height != expected.geometry.sceneHeightPx) return false
    val file = File(root, frame.path)
    if (!file.isFile || sha256(file) != frame.sha256) return false
    val image = ImageIO.read(file) ?: return false
    return image.width == frame.width && image.height == frame.height
}
