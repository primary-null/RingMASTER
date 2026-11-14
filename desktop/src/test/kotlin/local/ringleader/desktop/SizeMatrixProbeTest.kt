@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import local.ringleader.core.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.TimeZone
import kotlin.coroutines.CoroutineContext

/**
 * Opt-in and restartable. Set RINGMASTER_SIZE_MATRIX to an artifact directory and select a bounded subset with group,
 * state, look, size, or shard filters. Ordinary test runs return before constructing a scene or writing a file.
 *
 * `expected-matrix.tsv` is the complete contract. `valid-frames.tsv` contains only frames that passed state preparation,
 * semantic assertions, PNG encoding and dimension checks. `rejected-frames.tsv` records failed preparation instead of
 * letting an ordinary page masquerade as a dialog. `audit.tsv` records semantic clipping observations by case.
 *
 * RINGMASTER_SIZE_MODE=plan writes only the manifest. RINGMASTER_SIZE_MODE=verify compares the complete manifest with
 * valid PNGs already on disk. Render mode resumes valid cases unless RINGMASTER_SIZE_RERENDER=1. Use
 * RINGMASTER_SIZE_SHARD=I/N (zero based) for bounded final runs; selections over 128 cases are refused by default.
 */
class SizeMatrixProbeTest {
    @Test fun matrix() {
        val output = System.getenv("RINGMASTER_SIZE_MATRIX")?.takeIf { it.isNotBlank() } ?: return
        check(System.getenv("XDG_CONFIG_HOME").isNullOrBlank()) { "Unset XDG_CONFIG_HOME for the size matrix." }
        val requested = File(output)
        check(requested.isAbsolute) { "RINGMASTER_SIZE_MATRIX must be an absolute path so Gradle's module working directory cannot split the evidence." }
        val dir = requested.apply { mkdirs() }
        val home = System.getProperty("user.home")
        val sandbox = Files.createTempDirectory("ringmaster-matrix-home")
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        // Settings/About renders this value. Keep the evidence invented and deterministic; all fixture writes use the
        // explicit temporary [sandbox] paths passed into MatrixLab/MatrixEnv instead of this display-only home.
        System.setProperty("user.home", if (File.separatorChar == '\\') "C:\\Demo\\RingMASTER" else "/demo/ringmaster")
        try {
            runSceneTest { MatrixRun(dir, sandbox, coroutineContext).run() }
        } finally {
            TimeZone.setDefault(zone)
            System.setProperty("user.home", home)
            sandbox.toFile().deleteRecursively()
            Appearance.useBrowserDisplay()
            Appearance.preview(false)
        }
    }
}
internal val matrixSizes = listOf(
    MatrixSize("min-960x640", 960, 640, deliberateStress = true),
    MatrixSize("stress-960x600", 960, 600, deliberateStress = true),
    MatrixSize("1024x768", 1024, 768),
    MatrixSize("1280x720", 1280, 720),
    MatrixSize("1366x768", 1366, 768),
    MatrixSize("1600x1000", 1600, 1000),
    MatrixSize("1920x1080", 1920, 1080, matrixLargeDisplay),
    MatrixSize("2560x1440", 2560, 1440, matrixLargeDisplay),
    // At 100 % the app clamps a 640-wide tall snap to 960. One frame carries both labels; it is not counted twice.
    MatrixSize("half-960x1040", 960, 1040, aliases = setOf("tall-column-at-minimum-width"), deliberateStress = true),
    MatrixSize("stress-quarter-960x520", 960, 520, deliberateStress = true),
    MatrixSize("stress-tall-640x1040", 640, 1040, deliberateStress = true),
    MatrixSize("laptop-1366x728", 1366, 728, matrixLaptopDisplay, deliberateStress = true),
    MatrixSize("native-min-ui70-672x448", 672, 448),
)
internal fun matrixSize(name: String) = matrixSizes.single { it.name == name }

internal val matrixLooks = listOf(
    MatrixLook("dark", ThemeMode.Dark, 1f, 1f),
    MatrixLook("dark-text150", ThemeMode.Dark, 1f, 1.5f),
    MatrixLook("dark-text085", ThemeMode.Dark, 1f, .85f),
    MatrixLook("dark-ui200", ThemeMode.Dark, 2f, 1f, matrixLaptopDisplay),
    MatrixLook("dark-ui200-text150", ThemeMode.Dark, 2f, 1.5f, matrixLaptopDisplay),
    MatrixLook("dark-ui70", ThemeMode.Dark, .7f, 1f),
    MatrixLook("dark-os125", ThemeMode.Dark, 1f, 1f, matrixFhd125Display),
    MatrixLook("light", ThemeMode.Light, 1f, 1f),
    MatrixLook("pitch", ThemeMode.PitchBlack, 1f, 1f),
)

private val ordinaryWindows = listOf(
    "min-960x640", "stress-960x600", "1024x768", "1280x720", "1366x768", "1600x1000", "1920x1080", "2560x1440",
    "half-960x1040", "stress-quarter-960x520", "stress-tall-640x1040", "laptop-1366x728",
)

/** Exact recipe expansion. No implicit cross product or hidden exclusion is allowed outside this table. */
internal val matrixSizesForLook: Map<String, List<String>> = linkedMapOf(
    "dark" to ordinaryWindows,
    "dark-text150" to listOf("min-960x640", "stress-960x600", "1024x768", "1280x720", "1366x768", "1600x1000", "half-960x1040", "stress-quarter-960x520", "stress-tall-640x1040"),
    "dark-text085" to listOf("min-960x640", "stress-quarter-960x520"),
    "dark-ui200" to listOf("laptop-1366x728", "min-960x640", "stress-960x600", "half-960x1040", "stress-quarter-960x520", "stress-tall-640x1040"),
    "dark-ui200-text150" to listOf("laptop-1366x728", "min-960x640", "stress-960x600", "half-960x1040", "stress-quarter-960x520", "stress-tall-640x1040"),
    "dark-ui70" to listOf("native-min-ui70-672x448", "min-960x640", "half-960x1040", "stress-quarter-960x520", "stress-tall-640x1040", "1920x1080", "2560x1440"),
    "dark-os125" to listOf("min-960x640", "1280x720"),
    "light" to listOf("1280x720"),
    "pitch" to listOf("1280x720"),
)

/** One screen or state. The semantic [contract] is checked after [prepare] and before any PNG is accepted. */
internal class MatrixShot(
    val name: String,
    val page: String,
    val rail: Boolean = false,
    val frames: Int = 6,
    val stepMs: Long = 60,
    val scrollEnd: Boolean = false,
    val prepare: (suspend (ImageComposeScene, MatrixDriver) -> Unit)? = null,
    val contract: MatrixStateContract = matrixStateContract(name),
    val content: @Composable (MatrixEnv) -> Unit,
) {
    val group: MatrixGroup get() = contract.group
}

/** Pointer driving stays in physical scene pixels. */
internal class MatrixDriver(private val clockStart: Long = 0, val width: Int = 960, val height: Int = 640) {
    var clock = clockStart
    suspend fun frames(scene: ImageComposeScene, count: Int, stepMs: Long = 60, pauseMs: Long = 10) = repeat(count) {
        clock += stepMs * 1_000_000L
        scene.render(clock).close()
        yield()
        if (pauseMs > 0) delay(pauseMs)
    }

    suspend fun scrollToEnd(scene: ImageComposeScene, width: Int = this.width, height: Int = this.height) {
        suspend fun wheel(at: Offset, times: Int) {
            scene.sendPointerEvent(PointerEventType.Move, at)
            repeat(times) {
                scene.sendPointerEvent(PointerEventType.Scroll, at, scrollDelta = Offset(0f, 4000f))
                frames(scene, 1, 30, 0)
            }
        }
        wheel(Offset(width * .5f, (130f * height / 640f).coerceAtMost(height * .3f)), 8)
        wheel(Offset(width * .7f, height * .7f), 12)
        wheel(Offset((width - 170f).coerceAtLeast(width * .6f), (height - 120f).coerceAtLeast(height * .6f)), 12)
        frames(scene, 3)
    }

    fun find(scene: ImageComposeScene, text: String, exact: Boolean = true): SemanticsNode? = allSemanticsNodes(scene).firstOrNull { node ->
        val value = semanticsText(node)
        if (exact) value.equals(text, ignoreCase = true) else value.contains(text, ignoreCase = true)
    }

    private fun matchingNodesInBothTrees(scene: ImageComposeScene, text: String, exact: Boolean): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) {
            val value = semanticsText(node)
            if (if (exact) value.equals(text, ignoreCase = true) else value.contains(text, ignoreCase = true)) add(node)
            node.children.forEach(::visit)
        }
        scene.semanticsOwners.forEach { owner ->
            visit(owner.unmergedRootSemanticsNode)
            visit(owner.rootSemanticsNode)
        }
    }

    suspend fun click(scene: ImageComposeScene, text: String, exact: Boolean = true): Boolean {
        var node = find(scene, text, exact) ?: return false
        repeat(6) {
            val bounds = node.boundsInWindow
            if (bounds.top < 0 || bounds.bottom > height || node.touchBoundsInRoot.height + 2f < bounds.height) {
                var ancestor = node.parent
                while (ancestor != null && !ancestor.config.contains(androidx.compose.ui.semantics.SemanticsActions.ScrollBy)) ancestor = ancestor.parent
                ancestor?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsActions.ScrollBy)?.action?.invoke(0f, bounds.center.y - height * .6f)
                frames(scene, 3)
                node = find(scene, text, exact) ?: return false
            }
        }
        val at = node.boundsInWindow.center
        if (at.x !in 0f..width.toFloat() || at.y !in 0f..height.toFloat()) return false
        scene.sendPointerEvent(PointerEventType.Move, at)
        scene.sendPointerEvent(PointerEventType.Press, at)
        scene.sendPointerEvent(PointerEventType.Release, at)
        frames(scene, 4)
        return true
    }

    suspend fun clickRequired(scene: ImageComposeScene, shot: String, text: String, exact: Boolean = true) {
        check(click(scene, text, exact)) { "$shot: required control '$text' was not reachable" }
    }

    /**
     * Prepare an actual production state. Pointer activation is tried first. A deliberately below-minimum stress case may
     * clip a popup control outside the scene; then its real semantics OnClick is invoked and the post-state contract still
     * has to prove the intended overlay opened. This is fixture preparation, not a claim of pointer reachability.
     */
    suspend fun activateRequired(scene: ImageComposeScene, shot: String, text: String, exact: Boolean = true) {
        if (click(scene, text, exact)) return
        activateSemanticsRequired(scene, shot, text, exact)
    }

    /** Fixture preparation only; does not establish native pointer reachability. */
    suspend fun activateSemanticsRequired(scene: ImageComposeScene, shot: String, text: String, exact: Boolean = true) {
        val action = matchingNodesInBothTrees(scene, text, exact).asSequence().mapNotNull { match ->
            var node: SemanticsNode? = match
            while (node != null && node.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) == null) node = node.parent
            node?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.action
        }.firstOrNull()
        check(action != null && action.invoke()) { "$shot: required control '$text' had no invokable action" }
        frames(scene, 4)
    }
}

/** Invented-only state shared by fixture composables. */
internal class MatrixEnv(val lab: MatrixLab, val idle: Workspace, val connected: Workspace, val scope: CoroutineScope, val dir: Path) {
    fun state(guest: Guest? = null, node: String? = null) = lab.state(guest, node)
}

private class MatrixRun(private val dir: File, private val sandbox: Path, private val context: CoroutineContext) {
    private val validFile = File(dir, "valid-frames.tsv")
    private val rejectedFile = File(dir, "rejected-frames.tsv")
    private val auditFile = File(dir, "audit.tsv")
    private val valid = readValidFrames(validFile)
    private val rejected = readRejectedFrames(rejectedFile)
    private val audit = readAuditRows(auditFile)
    private var rendered = 0
    private var resumed = 0

    suspend fun run() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "core/src").isDirectory && File(it, "desktop/src").isDirectory }
        bindMatrixSource(dir, matrixSourceFingerprint(root))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val lab = MatrixLab(sandbox)
        val idle = Workspace(scope)
        val connected = connectedWorkspace(scope, lab)
        val env = MatrixEnv(lab, idle, connected, scope, sandbox)
        try {
            val shots = matrixShots(scope, lab, env)
            check(shots.map { it.name }.distinct().size == shots.size) { "Matrix state names must be unique." }
            val missingStates = u1RequiredStateNames - shots.map { it.name }.toSet()
            check(missingStates.isEmpty()) { "The matrix silently omitted required U1 states: ${missingStates.sorted().joinToString()}" }
            val all = matrixCases(shots)
            check(all.map { it.id }.distinct().size == all.size) { "Matrix case IDs must be unique." }
            writeMatrixFile(File(dir, "expected-matrix.tsv"), matrixManifestHeader() + "\n" + all.joinToString("\n") { it.manifestLine() } + "\n")

            when (System.getenv("RINGMASTER_SIZE_MODE")?.lowercase() ?: "render") {
                "plan" -> return
                "verify" -> {
                    verifyComplete(all)
                    return
                }
                "render" -> Unit
                else -> error("RINGMASTER_SIZE_MODE must be plan, render, or verify.")
            }

            val selected = selectCases(all, shots)
            writeMatrixFile(File(dir, "selected-matrix.tsv"), matrixManifestHeader() + "\n" + selected.joinToString("\n") { it.manifestLine() } + "\n")
            val limit = System.getenv("RINGMASTER_SIZE_MAX_CASES")?.toIntOrNull() ?: 128
            check(selected.size <= limit) {
                "Selected ${selected.size} cases; the bounded limit is $limit. Use RINGMASTER_SIZE_SHARD=I/N or narrower filters."
            }
            check(selected.isNotEmpty()) { "No matrix case matched the filters." }

            val rerender = System.getenv("RINGMASTER_SIZE_RERENDER") == "1"
            for (case in selected) {
                val existing = valid[case.id]
                if (!rerender && existing != null && validFrameOnDisk(dir, case, existing)) {
                    resumed++
                    continue
                }
                try {
                    draw(env, case)
                } catch (failure: IllegalStateException) {
                    // draw has durably recorded the rejected case. Collect independent cases before failing
                    // the shard's final completeness check; a bad fixture must not hide all later states.
                    println("Rejected ${case.id}: ${failure.message}")
                }
            }
            val missing = selected.filterNot { case -> valid[case.id]?.let { validFrameOnDisk(dir, case, it) } == true }
            flushEvidence()
            writeCoverage(all)
            check(missing.isEmpty()) { "Selected cases missing valid frames: ${missing.joinToString { it.id }}" }
            println("size matrix: selected=${selected.size}, rendered=$rendered, resumed=$resumed, valid-total=${valid.size}, in=$dir")
        } finally {
            connected.close()
            idle.close()
            scope.cancel()
        }
    }

    private fun selectCases(all: List<MatrixCase>, shots: List<MatrixShot>): List<MatrixCase> {
        val groupValues = csv("RINGMASTER_SIZE_GROUPS")
        val screenValues = csv("RINGMASTER_SIZE_SCREENS")
        val sizeValues = csv("RINGMASTER_SIZE_SIZES")
        val lookValues = csv("RINGMASTER_SIZE_LOOKS")
        val shardValue = System.getenv("RINGMASTER_SIZE_SHARD")?.trim()?.takeIf { it.isNotEmpty() }
        check(listOf(groupValues, screenValues, sizeValues, lookValues).any { it != null } || shardValue != null) {
            "A bounded render needs RINGMASTER_SIZE_GROUPS, RINGMASTER_SIZE_SCREENS, RINGMASTER_SIZE_SIZES, RINGMASTER_SIZE_LOOKS, or RINGMASTER_SIZE_SHARD."
        }

        groupValues?.let { requested ->
            val known = MatrixGroup.entries.map { it.id }.toSet()
            check(requested.all { it in known }) { "Unknown matrix group(s): ${(requested - known).joinToString()}" }
        }
        sizeValues?.let { requested ->
            val known = matrixSizes.map { it.name }.toSet()
            check(requested.all { it in known }) { "Unknown matrix size(s): ${(requested - known).joinToString()}" }
        }
        lookValues?.let { requested ->
            val known = matrixLooks.map { it.name }.toSet()
            check(requested.all { it in known }) { "Unknown matrix look(s): ${(requested - known).joinToString()}" }
        }
        val screenPatterns = screenValues?.map { value ->
            val regex = Regex(value)
            check(shots.any { regex.containsMatchIn(it.name) }) { "Screen filter '$value' matches no state." }
            regex
        }

        var selected = all.filter { case ->
            (groupValues == null || case.shot.group.id in groupValues) &&
                (screenPatterns == null || screenPatterns.any { it.containsMatchIn(case.shot.name) }) &&
                (sizeValues == null || case.size.name in sizeValues) &&
                (lookValues == null || case.look.name in lookValues)
        }
        if (shardValue != null) {
            val match = Regex("([0-9]+)/([1-9][0-9]*)").matchEntire(shardValue)
                ?: error("RINGMASTER_SIZE_SHARD must be zero-based I/N, for example 0/32.")
            val index = match.groupValues[1].toInt()
            val count = match.groupValues[2].toInt()
            check(index in 0 until count) { "Shard index $index must be in 0 until $count." }
            selected = selected.withIndex().filter { it.index % count == index }.map { it.value }
        }
        return selected
    }

    private suspend fun draw(env: MatrixEnv, case: MatrixCase) {
        val geometry = case.geometry
        Appearance.useDisplay(geometry.display.info())
        Appearance.chooseScale(case.look.ui)
        Appearance.preview(case.look.theme, showcase = false, textScale = case.look.text)
        val driver = MatrixDriver(width = geometry.sceneWidthPx, height = geometry.sceneHeightPx)
        val scene = ImageComposeScene(
            geometry.sceneWidthPx,
            geometry.sceneHeightPx,
            Density(geometry.density, case.look.text),
            coroutineContext = context,
        ) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) { case.shot.content(env) }
                }
            }
        }
        val target = File(dir, case.relativePath)
        try {
            driver.frames(scene, case.shot.frames, case.shot.stepMs)
            case.shot.prepare?.invoke(scene, driver)
            if (case.shot.scrollEnd) driver.scrollToEnd(scene)
            driver.frames(scene, 3, case.shot.stepMs)
            assertMatrixState(scene, case.shot)

            audit[case.id] = auditScene(scene).map { hit ->
                listOf(
                    case.id, hit.kind, if (hit.control) "control" else "text", hit.label.replace('\t', ' ').replace('\n', ' ').take(120),
                    "${hit.bounds.left.toInt()},${hit.bounds.top.toInt()},${hit.bounds.width.toInt()}x${hit.bounds.height.toInt()}",
                    "${hit.visible.width.toInt()}x${hit.visible.height.toInt()}",
                ).joinToString("\t")
            }

            target.parentFile.mkdirs()
            val image = scene.render(driver.clock + 16_000_000L)
            try {
                image.encodeToData()!!.use { data -> target.writeBytes(data.bytes) }
            } finally {
                image.close()
            }
            val frame = ValidFrame(case.id, case.relativePath, geometry.sceneWidthPx, geometry.sceneHeightPx, sha256(target))
            check(validFrameOnDisk(dir, case, frame)) { "${case.id}: encoded PNG failed its dimension/hash check" }
            valid[case.id] = frame
            rejected.remove(case.id)
            rendered++
            flushEvidence()
        } catch (failure: Throwable) {
            target.delete()
            valid.remove(case.id)
            rejected[case.id] = failure.message ?: failure::class.simpleName.orEmpty()
            flushEvidence()
            throw failure
        } finally {
            scene.close()
        }
        yield()
        if (rendered % 8 == 0) System.gc()
    }

    private fun verifyComplete(all: List<MatrixCase>) {
        val expected = all.associateBy { it.id }
        val stale = valid.keys - expected.keys
        val invalid = all.filterNot { case -> valid[case.id]?.let { validFrameOnDisk(dir, case, it) } == true }
        writeCoverage(all)
        check(stale.isEmpty()) { "valid-frames.tsv contains cases outside the expected manifest: ${stale.joinToString()}" }
        check(invalid.isEmpty()) { "Complete matrix is missing or invalid for ${invalid.size} cases. First: ${invalid.take(12).joinToString { it.id }}" }
        check(rejected.keys.intersect(expected.keys).isEmpty()) { "Expected cases remain rejected: ${rejected.keys.intersect(expected.keys).joinToString()}" }
        println("size matrix complete: ${all.size} expected frames match valid PNGs")
    }

    /** Always compare the complete expected manifest with the durable valid-frame ledger, even during partial batches. */
    private fun writeCoverage(all: List<MatrixCase>) {
        writeMatrixFile(
            File(dir, "coverage.tsv"),
            "case_id\tstatus\tpath\n" + all.joinToString("\n") { case ->
                val ok = valid[case.id]?.let { validFrameOnDisk(dir, case, it) } == true
                "${case.id}\t${if (ok) "valid" else "missing-or-invalid"}\t${case.relativePath}"
            } + "\n",
        )
    }

    private fun flushEvidence() {
        writeMatrixFile(validFile, VALID_FRAME_HEADER + "\n" + valid.values.sortedBy { it.caseId }.joinToString("\n") { it.line() } + if (valid.isEmpty()) "" else "\n")
        writeMatrixFile(rejectedFile, "case_id\treason\n" + rejected.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}\t${it.value.replace('\t', ' ').replace('\n', ' ')}" } + if (rejected.isEmpty()) "" else "\n")
        writeMatrixFile(auditFile, "case_id\tkind\telement\tlabel\tbounds\tvisible\n" + audit.toSortedMap().values.flatten().joinToString("\n") + if (audit.isEmpty()) "" else "\n")
    }

    private fun csv(name: String): Set<String>? = System.getenv(name)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()?.takeIf { it.isNotEmpty() }
}

private fun writeMatrixFile(file: File, text: String) {
    file.parentFile.mkdirs()
    val temp = Files.createTempFile(file.parentFile.toPath(), file.name, ".tmp")
    try {
        Files.writeString(temp, text)
        runCatching { Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            .getOrElse { Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    } finally {
        Files.deleteIfExists(temp)
    }
}

private fun readRejectedFrames(file: File): LinkedHashMap<String, String> = linkedMapOf<String, String>().apply {
    if (!file.isFile) return@apply
    file.useLines { lines -> lines.drop(1).forEach { line ->
        val fields = line.split('\t', limit = 2)
        if (fields.size == 2 && fields[0].isNotBlank()) put(fields[0], fields[1])
    } }
}

private fun readAuditRows(file: File): LinkedHashMap<String, List<String>> = linkedMapOf<String, List<String>>().apply {
    if (!file.isFile) return@apply
    file.useLines { lines -> lines.drop(1).filter { it.isNotBlank() }.groupBy { it.substringBefore('\t') }.forEach(::put) }
}

/** The exact custom chrome around the app page; scene dimensions are native-window dimensions, title bar included. */
@Composable internal fun MatrixWindow(page: String, state: WorkspaceState, workspace: Workspace, env: MatrixEnv, rail: Boolean, content: @Composable () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().background(Tech.Raised).height(MATRIX_TITLE_BAR_DP.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Image(AppIcon.small, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("RingMASTER", color = Tech.Amber)
            }
            WindowControl(WindowGlyph.Minimize) {}
            WindowControl(WindowGlyph.Maximize) {}
            WindowControl(WindowGlyph.Close) {}
        }
        Row(Modifier.weight(1f).background(MaterialTheme.colorScheme.background)) {
            NavigationRail(state, page, "Windows 11", workspace, initialExpanded = rail, pins = env.lab.pins, connectionKey = env.lab.origin) {}
            Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
                Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "WORKSPACE / ${page.uppercase()}${if (page == "Fleet" && state.selected != null) " / ${state.selected!!.kind} ${state.selected!!.id}" else if (page == "Fleet" && state.selectedNode != null) " / ${state.selectedNode}" else ""}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            letterSpacing = 1.5.sp,
                            color = Tech.Muted,
                        )
                        Text(
                            when (page) {
                                "Fleet" -> state.selected?.name ?: state.selectedNode ?: "Fleet overview"
                                "Storage" -> "Storage contents"
                                "Activity" -> "Recent activity"
                                "Scan" -> "Network scan"
                                "Bluetooth" -> "Nearby devices"
                                "VPN" -> "Tunnels and VPN"
                                "Tools" -> "Network tools"
                                "Under the Hood" -> "Under the Hood"
                                else -> "Connection desk"
                            },
                            modifier = Modifier.weight(1f).then(if (page == "Fleet" && (state.selected != null || state.selectedNode != null)) Modifier.identity() else Modifier),
                            fontSize = Tech.Title,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider(color = Tech.Edge)
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
        }
    }
}
