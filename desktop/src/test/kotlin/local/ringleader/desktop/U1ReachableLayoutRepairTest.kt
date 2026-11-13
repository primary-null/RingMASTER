@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import local.ringleader.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

/**
 * Reachable responsive-layout defects from the U1 final matrix, part A. Each scene uses the matrix's own window shell,
 * invented data and the reachable geometry named in the case ID. A check fails when the fact is clipped, scrolled out of
 * a strip without a route, or laid out beyond its line box; intended scrolling must come with a scroll route.
 */
class U1ReachableLayoutRepairTest {
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = buildList {
        fun visit(n: SemanticsNode) { add(n); n.children.forEach(::visit) }
        scene.semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun label(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
    private fun texts(scene: ImageComposeScene, match: (String) -> Boolean) = nodes(scene).filter { match(label(it)) }
    private fun text(scene: ImageComposeScene, wanted: String) = assertNotNull(texts(scene) { it.equals(wanted, true) }.firstOrNull(), "'$wanted' is not composed")
    private fun layouts(n: SemanticsNode) = mutableListOf<TextLayoutResult>().also { n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(it) }

    /** Lines squeezed below their own height: the box is shorter than the text (descenders and lines cut off). */
    private fun squeezed(t: TextLayoutResult) = t.size.height + .5f < t.multiParagraph.height
    /** Text that lost content: lines beyond the allowed count (clipped or ellipsized), squeezed lines, or glyphs past the right edge. */
    private fun cut(t: TextLayoutResult) = t.multiParagraph.didExceedMaxLines || squeezed(t) || (0 until t.lineCount).any { t.getLineRight(it) > t.size.width + 1f }
    private fun why(n: SemanticsNode) = layouts(n).map { "h=${it.didOverflowHeight} w=${it.didOverflowWidth} lines=${it.lineCount} exceeded=${it.multiParagraph.didExceedMaxLines} squeezed=${squeezed(it)} ellipsis=${(0 until it.lineCount).any { l -> it.isLineEllipsized(l) }} size=${it.size} para=${it.multiParagraph.width}x${it.multiParagraph.height}" }

    /** Fully drawn: inside the scene, not cut by an ancestor clip (a scroller or a fixed-height row), no text overflow. */
    private fun visible(n: SemanticsNode, w: Int, h: Int): Boolean {
        val b: Rect = n.boundsInWindow
        val whole = b.width + 1.5f >= n.size.width && b.height + 1.5f >= n.size.height && n.size.width > 2
        val inside = b.left >= -1f && b.top >= -1f && b.right <= w + 1f && b.bottom <= h + 1f
        return whole && inside && layouts(n).none(::cut)
    }
    private fun assertVisible(scene: ImageComposeScene, n: SemanticsNode, w: Int, h: Int, what: String = label(n)) {
        assertTrue(visible(n, w, h), "$what is not fully visible: bounds=${n.boundsInWindow} size=${n.size} text=${why(n)}")
    }

    private suspend fun scene(w: Int, h: Int, density: Float, text: Float, content: @Composable () -> Unit): Pair<ImageComposeScene, MatrixDriver> {
        val scene = ImageComposeScene(w, h, Density(density, text), coroutineContext = currentCoroutineContext()) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides true) { Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) { content() } }
            }
        }
        val driver = MatrixDriver(width = w, height = h)
        driver.frames(scene, 8)
        return scene to driver
    }

    private fun withLab(block: suspend CoroutineScope.(MatrixEnv, Path) -> Unit) = runSceneTest {
        val root = Files.createTempDirectory("u1-reachable")
        val home = System.getProperty("user.home")
        System.setProperty("user.home", root.toString())
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val ws = Workspace(scope)
        try {
            val lab = MatrixLab(root)
            block(MatrixEnv(lab, ws, ws, scope, root), root)
        } finally {
            ws.close(); scope.cancel()
            System.setProperty("user.home", home)
            root.toFile().deleteRecursively()
        }
    }

    @Composable private fun Fleet(e: MatrixEnv, guest: Guest?, rail: Boolean = false) {
        val s = e.state(guest)
        MatrixWindow("Fleet", s, e.idle, e, rail) {
            ChartLensHost(s, e.idle) { InventoryScreen(s, e.idle, "Lab cluster · ${e.lab.origin}", "", {}, "All", {}, {}, {}, e.lab.pins, e.lab.origin) }
        }
    }

    /** The topmost node with this exact text: the summary strip sits above the table, whose headers repeat some labels. */
    private fun topmost(scene: ImageComposeScene, wanted: String) =
        assertNotNull(texts(scene) { it.equals(wanted, true) }.minByOrNull { it.positionInWindow.y }, "'$wanted' is not composed")

    @Test fun fleetSummaryKeepsEveryUsageFactWholeAtLargeText() = withLab { e, _ ->
        // fleet/fleet-ct/dark-text150/1024x768 and fleet/fleet-vm/dark-text150/min-960x640
        for ((w, h) in listOf(1024 to 768, 960 to 640, 1366 to 768)) {
            val (scene, _) = scene(w, h, 1f, 1.5f) { Fleet(e, e.lab.container) }
            try {
                for (cell in listOf("CLUSTER CPU", "GUEST NETWORK", "BUSIEST GUEST", "MEMORY")) assertVisible(scene, topmost(scene, cell), w, h, "$cell at ${w}x$h")
                assertVisible(scene, topmost(scene, "OUT"), w, h, "Guest network OUT at ${w}x$h")
                // A count never leaves its noun on another line ("1" / "tpl").
                val split = assertNotNull(texts(scene) { it.contains("run") && it.contains("tpl") }.firstOrNull(), "guest split")
                layouts(split).forEach { layout ->
                    val all = layout.layoutInput.text.text
                    for (line in 0 until layout.lineCount) {
                        val part = all.substring(layout.getLineStart(line), layout.getLineEnd(line)).trim()
                        assertFalse(Regex("^(run|stop|tpl|other)\\b").containsMatchIn(part) || Regex("\\d$").containsMatchIn(part), "guest split breaks inside an item: '$part' at ${w}x$h")
                    }
                }
            } finally { scene.close() }
        }
    }

    @Test fun expandedRailLeavesTheMemoryValueWhole() = withLab { e, _ ->
        // fleet/fleet-vm-rail/dark/half-960x1040, fleet/fleet-vm-rail/dark-text085/min-960x640, fleet/fleet-vm-rail/dark-text150/1024x768
        for ((size, text) in listOf((960 to 1040) to 1f, (960 to 640) to .85f, (1024 to 768) to 1.5f)) {
            val (w, h) = size
            val (scene, _) = scene(w, h, 1f, text) { Fleet(e, e.lab.vm, rail = true) }
            try {
                val memory = topmost(scene, "MEMORY")
                assertVisible(scene, memory, w, h, "MEMORY at ${w}x$h text $text")
                val value = assertNotNull(texts(scene) { Regex("^[0-9.]+ / [0-9.]+ GiB$").matches(it) }
                    .filter { kotlin.math.abs(it.boundsInWindow.left - memory.boundsInWindow.left) < 4f }.minByOrNull { it.boundsInWindow.top }, "memory value at ${w}x$h")
                assertVisible(scene, value, w, h, "memory value at ${w}x$h text $text")
                assertVisible(scene, text(scene, "PINNED"), w, h, "PINNED heading at ${w}x$h text $text")
            } finally { scene.close() }
        }
    }

    @Test fun shortLaptopFleetShowsInventoryAndKeepsTheSummaryOneStepAway() = withLab { e, _ ->
        // fleet/fleet-vm/dark-ui200-text150/laptop-1366x728 and fleet/fleet-vm-stopped/dark-ui200/laptop-1366x728
        for (text in listOf(1f, 1.5f)) {
            val (scene, driver) = scene(1366, 728, 2f, text) { Fleet(e, e.lab.vm) }
            try {
                val names = e.lab.guests.map { it.name }.toSet() - e.lab.vm.name
                val rows = texts(scene) { it in names }
                assertTrue(rows.any { visible(it, 1366, 728) }, "no inventory row is visible without scrolling at UI 200% text $text: ${rows.take(3).map { "${label(it)} ${it.boundsInWindow}" }}")
                // Every summary fact and action stays reachable through one visible control.
                val disconnect = texts(scene) { it.equals("Disconnect", true) }.firstOrNull()
                if (disconnect == null || !visible(disconnect, 1366, 728)) {
                    driver.activateSemanticsRequired(scene, "short fleet", "Details")
                    driver.frames(scene, 4)
                }
                for (fact in listOf("Disconnect", "MEMORY", "BUSIEST GUEST")) assertNotNull(texts(scene) { it.equals(fact, true) }.firstOrNull(), "$fact after expanding the summary")
            } finally { scene.close() }
        }
    }

    @Test fun inspectorActionsAndSidePanelRowsAreNotSqueezedAtLargeText() = withLab { e, _ ->
        // fleet/fleet-ct/dark-text150/1280x720 (Close clipped) and fleet/fleet-closed-end/dark-text150/1024x768 (rows overlap)
        run {
            val (scene, _) = scene(1280, 720, 1f, 1.5f) { Fleet(e, e.lab.container) }
            try {
                val close = texts(scene) { it.equals("Close", true) }.maxByOrNull { it.boundsInWindow.left }
                assertVisible(scene, assertNotNull(close, "inspector Close"), 1280, 720, "inspector Close")
            } finally { scene.close() }
        }
        run {
            val (scene, _) = scene(1024, 768, 1f, 1.5f) { Fleet(e, null) }
            try {
                val names = e.lab.guests.filter { it.status == "running" }.map { it.name }.toSet()
                val cut = texts(scene) { it in names }.filter { n -> layouts(n).any(::squeezed) }
                assertTrue(cut.isEmpty(), "guest names overflow their row height: ${cut.map(::label)}")
            } finally { scene.close() }
        }
    }

    @Test fun savedConnectionRowsGrowWithTextAndKeepTheirAddress() = withLab { e, root ->
        // desk/desk-saved/dark-text150/1024x768 and desk/desk-connected/dark-text150/min-960x640
        val store = ProfileStore(Files.createDirectories(root.resolve("desk")).resolve("profiles.json"))
        val profiles = listOf(
            ConnectionProfile("00000000-0000-4000-8000-000000000021", "Sample PVE", DIRECT_PVE_KIND, "https://pve-east.example.test:8006", "root@pam!ringmaster", null),
            ConnectionProfile("00000000-0000-4000-8000-000000000040", "Sample PDM", PDM_KIND, "https://pdm.example.test:8443", "admin@pam!ringmaster", null))
        store.save(profiles)
        val desk = ProfileDesk(e.idle, store, vault = ReadmeVault("Windows Credential Manager"), rememberedProfiles = RememberedProfiles(root.resolve("desk/remembered.json")), io = Dispatchers.Unconfined)
        val motion = MotionPreferences(root.resolve("desk/motion.txt"))
        for ((w, h) in listOf(1024 to 768, 960 to 640)) {
            val (scene, _) = scene(w, h, 1f, 1.5f) {
                val deskState by desk.state.collectAsState()
                MatrixWindow("Desk", WorkspaceState(), e.idle, e, false) { DeskScreen(WorkspaceState(), deskState, desk, motion = motion) {} }
            }
            try {
                for (kind in listOf("PVE", "PDM")) assertVisible(scene, text(scene, kind), w, h, "$kind badge at ${w}x$h")
                for (origin in profiles.map { it.origin }) assertVisible(scene, text(scene, origin), w, h, "$origin at ${w}x$h")
                for (choice in listOf("DIRECT PVE", "API TOKEN")) texts(scene) { it.equals(choice, true) }.firstOrNull()?.let { n -> assertTrue(layouts(n).none(::cut), "$choice segment text is cut at ${w}x$h: ${why(n)}") }
            } finally { scene.close() }
        }
    }

    @Test fun chartLensTimeAxisIsReachableAtLargeText() = withLab { e, _ ->
        // dialogs/dlg-chart-lens/dark-text150/1024x768, 1280x720, 1366x768, min-960x640
        val clock = Regex("^\\d{2}:\\d{2}(:\\d{2})?$")
        for ((w, h) in listOf(1024 to 768, 1280 to 720, 960 to 640)) {
            val (scene, driver) = scene(w, h, 1f, 1.5f) {
                val s = e.state(e.lab.vm)
                MatrixWindow("Fleet", s, e.idle, e, false) {
                    ChartLensHost(s, e.idle) {
                        val lens = LocalChartLens.current
                        LaunchedEffect(Unit) { lens?.open(guestLens(e.lab.vm, LiveMetric.Cpu)) }
                        InventoryScreen(s, e.idle, "Lab", "", {}, "All", {}, {}, {}, e.lab.pins, e.lab.origin)
                    }
                }
            }
            try {
                driver.frames(scene, 8)
                fun axis() = texts(scene, clock::matches).filter { visible(it, w, h) }
                if (axis().isEmpty()) {
                    // Intended scrolling is acceptable only when the lens offers it: scroll the lens body to its end.
                    nodes(scene).filter { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                        .forEach { it.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, 5000f) }
                    driver.frames(scene, 4)
                }
                assertTrue(axis().size >= 2, "time axis labels are not reachable at ${w}x$h: ${texts(scene, clock::matches).map { "${label(it)} ${it.boundsInWindow} ${it.size}" }}")
            } finally { scene.close() }
        }
    }

    @Composable private fun PinForm(e: MatrixEnv) {
        val s = e.state(e.lab.vm)
        ServicePinForm(GuestHints(e.lab.vm.name, e.lab.vm.tags, s.inspection?.hardware?.value?.fields?.get("description")),
            s.inspection?.addresses?.value.orEmpty().mapNotNull(::reportedHost).distinct(), canProbe = false, onDismiss = {}, onSave = { _, _ -> null },
            probeUnavailable = "Network probes are disabled in the synthetic screenshot harness.")
    }

    @Test fun pinFormShowsItsFieldsAndAScrollRouteToEveryReportedAddress() = withLab { e, _ ->
        // dialogs/dlg-pin-service/dark-text150/min-960x640 and dialogs/dlg-pin-service/dark-ui200-text150/laptop-1366x728
        for ((geometry, density) in listOf((960 to 640) to 1f, (1366 to 728) to 2f)) {
            val (w, h) = geometry
            val (scene, driver) = scene(w, h, density, 1.5f) { PinForm(e) }
            try {
                assertVisible(scene, text(scene, "NAME"), w, h, "Name label at ${w}x$h")
                assertVisible(scene, text(scene, "ADDRESS"), w, h, "Address label at ${w}x$h")
                val input = nodes(scene).firstOrNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text?.startsWith("http") == true }
                assertVisible(scene, assertNotNull(input, "address input"), w, h, "Address input at ${w}x$h")
                for (action in listOf("Pin service", "Cancel")) assertVisible(scene, text(scene, action), w, h, "$action at ${w}x$h")
                val uses = texts(scene) { it.equals("Use", true) }
                assertTrue(uses.isNotEmpty(), "reported addresses are composed")
                if (!uses.all { visible(it, w, h) }) {
                    assertNotNull(nodes(scene).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "dialog-more-below" }, "hidden reported addresses have no scroll cue at ${w}x$h")
                    val scroller = assertNotNull(nodes(scene).firstOrNull { it.config.contains(SemanticsActions.ScrollBy) && it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }, "dialog scroller")
                    scroller.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 10000f)
                    driver.frames(scene, 4)
                    assertVisible(scene, texts(scene) { it.equals("Use", true) }.maxBy { it.boundsInWindow.top }, w, h, "last reported address Use at ${w}x$h")
                    assertNull(nodes(scene).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "dialog-more-below" }, "scroll cue remains at the end")
                }
            } finally { scene.close() }
        }
    }

    @Test fun clearConfirmationKeepsTheRequiredFieldBesideItsAction() = withLab { e, _ ->
        // dialogs/dlg-clear/dark-ui200/laptop-1366x728
        for (text in listOf(1f, 1.5f)) {
            val (scene, driver) = scene(1366, 728, 2f, text) {
                val s = e.state(e.lab.vm)
                SettingsDialog(s, e.idle, SettingsSection.About, {}, {}, {})
            }
            try {
                driver.activateSemanticsRequired(scene, "clear", "Clear everything…")
                driver.frames(scene, 4)
                assertVisible(scene, text(scene, "Clear everything?"), 1366, 728)
                assertVisible(scene, text(scene, "TYPE CLEAR TO CONFIRM"), 1366, 728, "required CLEAR field at text $text")
                for (action in listOf("Clear and quit", "Cancel")) assertVisible(scene, texts(scene) { it.equals(action, true) }.maxBy { it.boundsInWindow.top }, 1366, 728, "$action at text $text")
            } finally { scene.close() }
        }
    }

    @Test fun createVmShowsAGeneralFieldInTheReachableLaptopFrame() = withLab { e, _ ->
        // create/create-vm/dark-ui200/laptop-1366x728
        val s = e.lab.state(demo = true).copy(creation = demoCreateSession(NewGuestKind.Vm, "pve-east", 1, e.lab.now), connectionEpoch = 1)
        for (scale in listOf(1f, 1.5f)) {
        val (scene, driver) = scene(1366, 728, 2f, scale) {
            MatrixWindow("Fleet", s, e.idle, e, false) { CreateGuestHost(s, e.idle, "Demonstration", {}, onViewLog = {}) }
        }
        try {
            assertVisible(scene, text(scene, "NODE"), 1366, 728, "first General field label")
            assertVisible(scene, text(scene, "pve-east"), 1366, 728, "first General selection")
            for (action in listOf("Next", "Cancel", "Create VM 140…")) assertVisible(scene, text(scene, action), 1366, 728, action)
            assertVisible(scene, text(scene, DEMO_CREATE_SUBTITLE), 1366, 728, "demo note")
            driver.activateRequired(scene, "wizard options", "Options")
            assertVisible(scene, text(scene, "Show advanced"), 1366, 728)
            assertVisible(scene, text(scene, "Show parameters"), 1366, 728)
            driver.activateRequired(scene, "wizard options", "Done")
            assertVisible(scene, text(scene, "NODE"), 1366, 728)
        } finally { scene.close() }
        }
    }

    // ---- Part C overlaps -------------------------------------------------------------------------------------------

    @Test fun containerGeneralFieldsPrecedeRepeatedErrorSummary() = withLab { e, _ ->
        val s = e.lab.state(demo = true).copy(creation = demoCreateSession(NewGuestKind.Container, "pve-east", 1, e.lab.now), connectionEpoch = 1)
        val (scene, _) = scene(1366, 728, 2f, 1.5f) {
            MatrixWindow("Fleet", s, e.idle, e, false) { CreateGuestHost(s, e.idle, "Demonstration", {}, onViewLog = {}) }
        }
        try {
            assertVisible(scene, text(scene, "NODE"), 1366, 728)
            assertVisible(scene, text(scene, "pve-east"), 1366, 728)
        } finally { scene.close() }
    }

    @Test fun foldedConsoleSwitcherShowsEveryWholeVmid() = withLab { e, _ ->
        // guest/guest-console-rail-folded/dark/min-960x640 and dark-text150/1024x768: every folded row read "1".
        for (text in listOf(1f, 1.5f)) {
            val switcher = ConsoleSwitcherState().apply { expanded = false; runningOnly = false }
            val (scene, _) = scene(300, 640, 1f, text) {
                Box(Modifier.fillMaxHeight()) { ConsoleSwitcher(e.state(e.lab.vm), e.lab.vm, switcher, {}, Modifier.fillMaxHeight()) }
            }
            try {
                val ids = texts(scene) { Regex("^1[23][0-9]$").matches(it) }
                assertTrue(ids.size >= 5, "folded rows show VMIDs at text $text: ${nodes(scene).map(::label).filter { it.isNotBlank() }}")
                ids.filter { it.boundsInWindow.bottom < 600 }.forEach { assertVisible(scene, it, 300, 640, "folded VMID ${label(it)} at text $text") }
            } finally { scene.close() }
        }
    }

    @Test fun foldedConsoleSwitcherFitsLongIdsBesideKeptIndicator() = withLab { e, _ ->
        val guest = e.lab.vm.copy(id = 999999999)
        val base = e.state(e.lab.vm)
        val state = base.copy(overview = base.overview!!.copy(guests = listOf(e.lab.vm, guest)),
            keptConsoles = base.keptConsoles + guest.resource)
        for (scale in listOf(1f, 1.5f)) {
            val switcher = ConsoleSwitcherState().apply { expanded = false; runningOnly = false }
            val (scene, _) = scene(300, 640, 1f, scale) {
                ConsoleSwitcher(state, e.lab.vm, switcher, {}, Modifier.fillMaxHeight())
            }
            try { assertVisible(scene, text(scene, "999999999"), 300, 640, "long kept VMID at text $scale") }
            finally { scene.close() }
        }
    }

    @Test fun activityUsesReadableTaskCardsAtLargeInterfaceAndText() = withLab { e, _ ->
        val (scene, _) = scene(1366, 728, 2f, 1.5f) {
            val s = e.state()
            MatrixWindow("Activity", s, e.idle, e, false) { ActivityScreen(s, e.idle, "Lab", {}, {}) }
        }
        try {
            assertVisible(scene, text(scene, "Backup"), 1366, 728)
            assertVisible(scene, topmost(scene, "Duration"), 1366, 728)
        } finally { scene.close() }
    }

    @Test fun activityColumnsAndHardwareValuesKeepTheirWordsAtLargeText() = withLab { e, _ ->
        // pages/activity/dark-text150/1024x768 (DURATI) and guest/guest-hardware-end/dark-text150/1024x768 (VLAN)
        run {
            val (scene, _) = scene(1024, 768, 1f, 1.5f) { val s = e.state(); MatrixWindow("Activity", s, e.idle, e, false) { ActivityScreen(s, e.idle, "Lab", {}, {}) } }
            try { assertVisible(scene, text(scene, "DURATION"), 1024, 768, "Activity DURATION header") } finally { scene.close() }
        }
        run {
            val (scene, _) = scene(1024, 768, 1f, 1.5f) {
                val s = e.state(e.lab.vm)
                MatrixWindow("Fleet", s, e.idle, e, false) { GuestDetailPage(s, e.idle, {}, { _, _ -> }, initialTab = "Hardware", pins = e.lab.pins, connectionKey = e.lab.origin, onSwitchGuest = {}) }
            }
            try {
                val vlan = text(scene, "VLAN 10")
                assertTrue(layouts(vlan).none(::cut), "VLAN value is cut: ${why(vlan)}")
            } finally { scene.close() }
        }
        run {
            val (scene, _) = scene(960, 1040, 1f, 1.5f) {
                val s = e.state(e.lab.vm)
                MatrixWindow("Fleet", s, e.idle, e, false) { GuestDetailPage(s, e.idle, {}, { _, _ -> }, initialTab = "Snapshots", pins = e.lab.pins, connectionKey = e.lab.origin, onSwitchGuest = {}) }
            }
            try {
                val times = texts(scene) { Regex("""\d{2}:\d{2}:\d{2}.* · .*ago$""").containsMatchIn(it) }
                assertTrue(times.isNotEmpty(), "snapshot times are composed")
                times.forEach { t -> assertTrue(layouts(t).none(::cut), "snapshot time loses its age: '${label(t)}' ${why(t)}") }
            } finally { scene.close() }
        }
    }

    @Test fun chipsAndKindTilesGrowWithText() = withLab { _, _ ->
        // pages/storage/dark-text150 chips and fleet text150 VM/CT tiles were cut at their bottom edge.
        val (scene, _) = scene(400, 200, 1f, 1.5f) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NeutralTag("Shared · 2 nodes"); AttentionTag("Node offline"); IdentityTile("VM"); IdentityTile("CT", template = true) }
        }
        try {
            for (label in listOf("SHARED · 2 NODES", "NODE OFFLINE", "VM", "CT*")) assertVisible(scene, text(scene, label), 400, 200, label)
        } finally { scene.close() }
    }

    @Test fun hardStopConfirmationKeepsActionsAndARouteToItsWarningOnALaptop() = runSceneTest {
        // dialogs/dlg-power-hardstop/dark-ui200-text150/laptop-1366x728: Type, Name, Action and the warning were clipped with no cue.
        val root = Files.createTempDirectory("u1-hardstop")
        val home = System.getProperty("user.home"); System.setProperty("user.home", root.toString())
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val lab = MatrixLab(root)
        val ws = connectedWorkspace(scope, lab)
        val env = MatrixEnv(lab, ws, ws, scope, root)
        try {
            val (scene, driver) = scene(1366, 728, 2f, 1.5f) {
                LaunchedEffect(Unit) { if (ws.state.value.selected?.id != lab.vm.id) ws.select(lab.vm) }
                val shown by ws.state.collectAsState()
                val s = shown.copy(history = lab.history, tasks = lab.tasks, tasksUpdatedAt = lab.now)
                MatrixWindow("Fleet", s, ws, env, false) { ChartLensHost(s, ws) { InventoryScreen(s, ws, "Lab", "", {}, "All", {}, {}, {}, lab.pins, lab.origin) } }
            }
            try {
                driver.frames(scene, 8); driver.scrollToEnd(scene)
                driver.activateRequired(scene, "hard stop", "Hard stop")
                driver.frames(scene, 6)
                assertNotNull(texts(scene) { it.startsWith("Confirm") }.firstOrNull(), "confirmation opened")
                val actions = texts(scene) { it.equals("Hard stop", true) || it.equals("Cancel", true) }.filter { it.boundsInWindow.top > 364 }
                assertTrue(actions.size >= 2 && actions.all { visible(it, 1366, 728) }, "Cancel and Hard stop stay visible: ${actions.map { "${label(it)} ${it.boundsInWindow}" }}")
                val warning = assertNotNull(texts(scene) { it == HARD_STOP_CONSEQUENCE }.firstOrNull(), "warning composed")
                if (!visible(warning, 1366, 728)) {
                    assertNotNull(nodes(scene).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "dialog-more-below" }, "clipped warning has no scroll cue")
                    nodes(scene).filter { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null && it.boundsInWindow.top >= 0f }
                        .forEach { it.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, 10000f) }
                    driver.frames(scene, 4)
                    assertVisible(scene, texts(scene) { it == HARD_STOP_CONSEQUENCE }.first(), 1366, 728, "hard stop warning after scrolling")
                }
            } finally { scene.close() }
        } finally {
            ws.close(); scope.cancel(); System.setProperty("user.home", home); root.toFile().deleteRecursively()
        }
    }
}
