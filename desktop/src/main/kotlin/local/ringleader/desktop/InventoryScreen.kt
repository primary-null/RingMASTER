package local.ringleader.desktop

import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import local.ringleader.core.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun timestamp(time: Instant?) = time?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("HH:mm:ss")) ?: "—"
private fun bytes(value: Long?): String {
    if (value == null) return "Unavailable"
    return when {
        value >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f GiB", value / (1024.0 * 1024 * 1024))
        value >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MiB", value / (1024.0 * 1024))
        else -> "$value B"
    }
}
private fun uptime(value: Long?): String = if (value == null || value < 0L) "\u2014" else duration(value)

/** A PDM guest has no page of its own; double-click and pins only select it. */
internal fun guestHasPage(state: WorkspaceState, guest: Guest): Boolean = state.kind != PDM_KIND && guest.scope.isEmpty()

/** Commit button for an action that ends a guest at once or discards data: red, and never the suggested button. */
@Composable private fun DangerAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 32.dp), shape = Tech.Control,
        border = BorderStroke(1.dp, if (enabled) Tech.Red else Tech.Edge),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = androidx.compose.ui.graphics.Color.Transparent, contentColor = Tech.Red),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label.uppercase(), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.6.sp)
    }
}

/** The fleet screen. Motion: the task triggers and busy-guest map are provided here, once, for the list, the inspector and the detail page. */
@Composable fun InventoryScreen(
    state: WorkspaceState, workspace: Workspace, sessionTitle: String?, search: String, onSearch: (String) -> Unit,
    filter: String, onFilter: (String) -> Unit, onConnect: () -> Unit, onDisconnect: () -> Unit,
    pins: PinStore? = null, connectionKey: String? = null, fleetRequest: FleetRequest? = null,
    onFleetHandled: () -> Unit = {},
) = ProvideMotionBook(state) {
    InventoryScreenBody(state, workspace, sessionTitle, search, onSearch, filter, onFilter, onConnect, onDisconnect, pins, connectionKey, fleetRequest, onFleetHandled)
}

@Composable private fun InventoryScreenBody(
    state: WorkspaceState, workspace: Workspace, sessionTitle: String?, search: String, onSearch: (String) -> Unit,
    filter: String, onFilter: (String) -> Unit, onConnect: () -> Unit, onDisconnect: () -> Unit,
    pins: PinStore? = null, connectionKey: String? = null, fleetRequest: FleetRequest? = null,
    onFleetHandled: () -> Unit = {},
) {
    val motion = LocalMotion.current
    val sparks = LocalSparks.current
    val motionBook = LocalMotionBook.current
    // Where the Send button is, so the spark can leave from it after the dialog has closed.
    val sendButton = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    var detailOpen by remember(state.connectionEpoch) { mutableStateOf(false) }
    var detailTab by remember(state.connectionEpoch) { mutableStateOf("Summary") }
    var tabNonce by remember(state.connectionEpoch) { mutableStateOf(0L) }
    var detailResource by remember(state.connectionEpoch) { mutableStateOf<ResourceId?>(null) }
    var appliedNonce by remember(state.connectionEpoch) { mutableStateOf<Long?>(null) }
    var proofResource by remember { mutableStateOf<ResourceId?>(null) }
    var pendingPower by remember { mutableStateOf<PowerConfirmation?>(null) }
    val profileLabel = when {
        state.demo -> "Demonstration"
        sessionTitle != null -> sessionTitle.substringBefore(" · ").ifBlank { "Saved connection" }
        else -> "Manual connection"
    }
    LaunchedEffect(state.connectionEpoch, state.loading, state.stale, state.selected, state.overview, pendingPower) {
        val pending = pendingPower ?: return@LaunchedEffect
        if (powerRefusal(pending, state) != null) pendingPower = null
    }
    LaunchedEffect(state.connectionEpoch, state.selected?.resource, state.selectedNode) {
        if (proofResource != null && state.selected?.resource != proofResource) proofResource = null
    }
    LaunchedEffect(fleetRequest?.nonce, state.selected?.resource) {
        val req = fleetRequest
        if (req != null && req.nonce != appliedNonce) {
            val guest = state.overview?.guests?.firstOrNull { it.resource == req.resource } ?: return@LaunchedEffect
            // A PDM guest has no page of its own (the same rule as openGuest): a pin only selects it.
            if (!guestHasPage(state, guest)) {
                appliedNonce = req.nonce
                if (state.selected?.resource != guest.resource) workspace.select(guest)
                onFleetHandled()
                return@LaunchedEffect
            }
            // Record the target before select(). select() changes the effect key and can restart this
            // effect before the lines after it run.
            detailTab = req.tab
            tabNonce = req.nonce
            detailResource = guest.resource
            detailOpen = true
            appliedNonce = req.nonce
            if (state.selected?.resource != guest.resource) workspace.select(guest)
            workspace.inspectGuest()
            onFleetHandled()
            return@LaunchedEffect
        }
        if (req != null) onFleetHandled()
        if (detailOpen && state.selected?.resource != detailResource) detailOpen = false
    }
    // Double-click on a guest row: open the guest. A running guest opens on its console (as the Proxmox web interface
    // does), anything else on its summary. A PDM guest has no page of its own, so it is only selected.
    val openGuest: (Guest) -> Unit = { guest ->
        if (state.selected?.resource != guest.resource) workspace.select(guest)
        if (guestHasPage(state, guest)) {
            detailTab = guestOpenTab(guest, state.demo)
            tabNonce = System.nanoTime()
            detailResource = guest.resource
            detailOpen = true
            workspace.inspectGuest()
        }
    }
    pendingPower?.let { confirmation ->
        val destructive = powerActionDestructive(confirmation.action)
        val listed = state.overview?.guests?.firstOrNull { it.resource == confirmation.resource }
        val commit = powerCommitLabel(confirmation.action).let { if (state.demo) "Simulate ${it.lowercase()}" else it }
        val send: () -> Unit = {
            val from = sendButton[0]?.takeIf { it.isAttached }?.boundsInWindow()
            pendingPower = null
            // Nothing was sent when the workspace refuses (stale inventory, busy): then no spark leaves.
            if (workspace.submitPower(confirmation)) {
                val hot = confirmation.action == PowerAction.HardStop || confirmation.action == PowerAction.Reset
                sparks.ignite(motion, from, listOf(RowAnchor(confirmation.resource), TagAnchor(confirmation.resource), NamedAnchor("rail", "Activity")), hot) { motionBook?.land(confirmation.resource) }
            }
        }
        TechDialog("Confirm ${powerActionTitle(confirmation.action)}", { pendingPower = null }, width = 560.dp,
            subtitle = "Closing this confirmation does not change the guest.", actions = {
                // Cancel comes first. A destructive commit is red and never the suggested (amber) button.
                TechAction("Cancel", active = destructive) { pendingPower = null }
                val anchor = Modifier.pressDip().onGloballyPositioned { sendButton[0] = it }
                if (destructive) DangerAction(commit, anchor, enabled = !state.powerBusy, onClick = send)
                else TechAction(commit, anchor, active = true, enabled = !state.powerBusy, onClick = send)
            }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InspectorLine("Origin", confirmation.origin)
                InspectorLine("Profile", confirmation.profileLabel)
                InspectorLine("Node", confirmation.node)
                InspectorLine("VMID", confirmation.resource.vmid.toString())
                InspectorLine("Type", confirmation.kind)
                InspectorLine("Name", confirmation.name.ifBlank { "Guest ${confirmation.resource.vmid}" })
                InspectorLine("Action", powerActionTitle(confirmation.action))
                confirmation.snapshot?.let { request ->
                    InspectorLine("Snapshot", request.name)
                    if (confirmation.action == PowerAction.SnapshotCreate) {
                        if (confirmation.kind == "VM") InspectorLine("RAM", if (request.includeRam) "Included" else "Not included")
                        InspectorLine("Description", request.description.ifBlank { "None" })
                    }
                }
                listed?.haState?.let { InspectorLine("HA state", it) }
            }
            when (confirmation.action) {
                PowerAction.HardStop -> Text(HARD_STOP_CONSEQUENCE, color = Tech.Red, fontSize = 13.sp, lineHeight = 20.sp)
                PowerAction.Reset -> Text(RESET_CONSEQUENCE, color = Tech.Red, fontSize = 13.sp, lineHeight = 20.sp)
                PowerAction.Shutdown -> Text(SHUTDOWN_GUARANTEE, color = Tech.Amber, fontSize = 13.sp, lineHeight = 20.sp)
                PowerAction.SnapshotRollback -> Text(rollbackConsequence(confirmation.snapshot?.name.orEmpty(), confirmationSnapshot(confirmation, state)?.ram),
                    color = Tech.Red, fontSize = 13.sp, lineHeight = 20.sp)
                PowerAction.SnapshotDelete -> Text(SNAPSHOT_DELETE_CONSEQUENCE, color = Tech.Amber, fontSize = 13.sp, lineHeight = 20.sp)
                else -> {}
            }
            if (powerActionUsesHa(listed, confirmation.action)) Text(HA_ROUTING_NOTE, color = Tech.Amber, fontSize = 13.sp, lineHeight = 20.sp)
            if (state.demo) Text(SIMULATED_POWER_DETAIL, color = Tech.Amber, fontSize = 13.sp)
        }
    }
    if (detailOpen && state.selected != null) {
        GuestDetailPage(state,workspace, { detailOpen = false }, { action, snapshot -> pendingPower = workspace.capturePower(action,profileLabel,snapshot) }, initialTab = detailTab, tabNonce = tabNonce, pins = pins, connectionKey = connectionKey)
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = maxWidth < 900.dp
    val pageScroll = rememberScrollState()
    Column(Modifier.fillMaxSize().then(if (compact) Modifier.padding(end = 12.dp).verticalScroll(pageScroll) else Modifier), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
        TelemetryStrip(state, workspace, onDisconnect)
        if (state.loading) TechProgress()
        state.error?.let { Text(it, color = Tech.Red, fontSize = 13.sp) }
        if (state.connected && state.lostPowerResults.isNotEmpty()) LostPowerResults(state, workspace)
        val overview = state.overview
        if (overview == null) {
            if (!state.loading) TechPanel(Modifier.fillMaxWidth(), rail = Tech.Amber) {
                Text("Open your infrastructure workspace", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Text("Connect to a Proxmox server to inspect its guests, or explore the demo.", color = Tech.Muted)
                Spacer(Modifier.height(18.dp))
                TechAction("Desk", active = true, onClick = onConnect)
            }
        } else {
            val guests = overview.guests.filter { guestVisible(it, filter, search) }
            // 960x640 stacks the list above the inspector. 1600x900 shows them side by side.
            BoxWithConstraints((if (compact) Modifier.height(780.dp) else Modifier.weight(1f)).fillMaxWidth()) {
                val alongside = maxWidth >= 900.dp; val wide = maxWidth >= 1600.dp
                if (alongside) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        GuestList(guests, overview, state.selected, state.selectedNode, search, onSearch, filter, onFilter, workspace::select, { name -> proofResource = null; workspace.selectMember(name) }, Modifier.weight(1f).fillMaxHeight(),state.history, openGuest)
                        Surface(Modifier.width(if (wide) 400.dp else 340.dp).fillMaxHeight(), color = Tech.Panel, border = BorderStroke(1.dp, Tech.Edge), shape = Tech.Plate) {
                            GuestDetailsPanel(state, workspace, { detailTab = "Summary"; detailResource = state.selected?.resource; detailOpen = true; workspace.inspectGuest() }, { detailTab = "Console"; detailResource = state.selected?.resource; detailOpen = true; workspace.inspectGuest() }, { action -> workspace.capturePower(action, profileLabel)?.let { pendingPower = it } }, pins, connectionKey) { proofResource = it.resource }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Tech.Space12)) {
                        GuestList(guests, overview, state.selected, state.selectedNode, search, onSearch, filter, onFilter, workspace::select, { name -> proofResource = null; workspace.selectMember(name) }, Modifier.weight(1.15f).fillMaxWidth(),state.history, openGuest)
                        Surface(Modifier.weight(0.85f).fillMaxWidth(), color = Tech.Panel, border = BorderStroke(1.dp, Tech.Edge), shape = Tech.Plate) {
                            GuestDetailsPanel(state, workspace, { detailTab = "Summary"; detailResource = state.selected?.resource; detailOpen = true; workspace.inspectGuest() }, { detailTab = "Console"; detailResource = state.selected?.resource; detailOpen = true; workspace.inspectGuest() }, { action -> workspace.capturePower(action, profileLabel)?.let { pendingPower = it } }, pins, connectionKey) { proofResource = it.resource }
                        }
                    }
                }
            }
        }
    }
    if (compact) VerticalScrollbar(rememberScrollbarAdapter(pageScroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable private fun GuestList(
    guests: List<Guest>, overview: Overview, selected: Guest?, selectedNode: String?, search: String, onSearch: (String) -> Unit,
    filter: String, onFilter: (String) -> Unit, onSelectGuest: (Guest) -> Unit, onSelectMember: (String) -> Unit, modifier: Modifier, history: FleetHistory=FleetHistory(),
    onOpen: (Guest) -> Unit = {},
) {
    var sort by remember { mutableStateOf("ID") }; var descending by remember { mutableStateOf(false) }
    // One click selects a row; a second click on the same row within the system's double-click time opens the guest.
    // Clicking the row that is already selected reads nothing again: every click used to reload the inspector, so a
    // double-click looked like the panel refreshing over and over. The ↻ button is the way to read it again.
    val clicks = remember { RowClicks() }
    val currentSelection by rememberUpdatedState(selected)
    val onSelect: (Guest) -> Unit = { guest ->
        when (clicks.register(guest.resource, System.nanoTime() / 1_000_000L)) {
            RowClick.Open -> onOpen(guest)
            RowClick.Select -> if (currentSelection?.resource != guest.resource) onSelectGuest(guest)
        }
    }
    val total = overview.guests.size
    val steadyOrder = remember { SteadyGuestOrder() }
    val grouped = guests.groupBy { it.node }.entries.sortedBy { it.key }.map { (node, members) -> node to steadyOrder.order("node:$node", sort, descending, members, history) }
    BoxWithConstraints(modifier) { CompositionLocalProvider(LocalCols provides colsFor(maxWidth)) {
    val TableCols = LocalCols.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Tech.Space12)) {

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { CompactSearchField(search, onSearch) }
            SegmentedFilter(fleetSegments(overview.guests, search, filter), filter, onFilter)
        }
        val onSort: (String) -> Unit = { key -> if (sort == key) descending = !descending else { sort = key; descending = key in listOf("CPU", "Memory", "Net", "Uptime") } }
        Row(Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TableCols.gap)) {
            Spacer(Modifier.width(TableCols.tile))
            SortHeader("ID", "ID", sort, descending, Modifier.width(TableCols.id), onSort = onSort)
            SortHeader("NAME", "Name", sort, descending, Modifier.weight(1f), onSort = onSort)
            SortHeader("CPU", "CPU", sort, descending, Modifier.width(TableCols.cpu), onSort = onSort)
            SortHeader("MEMORY", "Memory", sort, descending, Modifier.width(TableCols.mem), onSort = onSort)
            if (TableCols.net > 0.dp) SortHeader("NET", "Net", sort, descending, Modifier.width(TableCols.net), onSort = onSort)
            if (TableCols.disk > 0.dp) Text("DISK", Modifier.width(TableCols.disk), fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Muted, fontFamily = Tech.Mono, maxLines = 1)
            SortHeader("UPTIME", "Uptime", sort, descending, Modifier.width(TableCols.up), onSort = onSort)
            Text("STATUS", Modifier.width(TableCols.status), fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Muted, fontFamily = Tech.Mono)
        }
        val narrowed = search.isNotBlank() || filter != "All"
        val remoteGroups = overview.remotes.filter { it.product != "pbs" }.associate { remote ->
            remote.scope to guests.filter { it.scope == remote.scope }.groupBy { it.node }.entries.sortedBy { it.key }
                .map { (node, members) -> node to steadyOrder.order("remote:${remote.scope}:$node", sort, descending, members, history) }
        }
        if (guests.isEmpty() && (overview.remotes.isEmpty() || narrowed)) Text(if (total == 0) "No guests are visible to this token." else "No guests match these filters.", color = Tech.Muted)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().border(1.dp, Tech.Edge.copy(alpha = .7f), Tech.Plate)) {
            if (overview.remotes.isEmpty()) {
                // The reveal stagger counts rows from the top of the list, across node groups.
                var offset = 0
                grouped.forEach { (node, ordered) ->
                    val base = offset; offset += ordered.size
                    item(key = "node:${ordered.first().remote}:$node") {
                        NodeHeaderRow(node, ordered.size, overview.members.firstOrNull { it.name == node }, history.nodes[node], selectedNode == node) { onSelectMember(node) }
                    }
                    itemsIndexed(ordered, key = { _, it -> it.resource }) { index, guest -> GuestRow(guest, selected, false, onSelect,history.guests[guest.resource], base + index, rowPlacement()) }
                }
            } else {
                overview.remotes.forEach { remote ->
                    val members = guests.filter { it.scope == remote.scope }
                    val show = members.isNotEmpty() || remote.error != null || remote.stale || (search.isBlank() && filter == "All")
                    if (!show) return@forEach
                    item(key = "remote:${remote.scope}") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SectionLabel(if (remote.product == "pbs") "PBS remote" else "PDM remote", suffix = remote.scope)
                            val remoteError = remote.error
                            if (remote.stale) Text("Stale. Last good rows kept. ${remoteError.orEmpty()}", color = Tech.Amber, fontSize = Tech.Label, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            else if (remoteError != null) Text(remoteError, color = Tech.Red, fontSize = Tech.Label, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (remote.product == "pbs") Text("Listed only. RingMASTER does not connect to this remote.", color = Tech.Muted, fontSize = Tech.Label)
                            else if (members.isEmpty() && remote.error == null) Text(if (search.isNotBlank() || filter != "All") "No guests match these filters." else "No guests.", color = Tech.Muted, fontSize = Tech.Label)
                        }
                    }
                    if (remote.product != "pbs") {
                        (remoteGroups[remote.scope] ?: emptyList()).forEach { (node, ordered) ->
                            item(key = "node:${remote.scope}:$node") {
                                SectionLabel("Node", suffix = "${node.ifBlank { "Unknown" }} · ${ordered.size}")
                            }
                            items(ordered, key = { it.resource }) { guest -> GuestRow(guest, selected, remote.stale, onSelect,history.guests[guest.resource], modifier = rowPlacement()) }
                        }
                    }
                }
            }
        }
    }
    } }
}

private fun recordedUsage(cpu: Double?, memory: Long?, maxMemory: Long?): String? {
    val percent = cpu?.let(::cpuUsagePercent)
    val cpuText = when {
        cpu == null -> null
        percent == null -> "CPU unavailable"
        else -> String.format(Locale.ROOT, "CPU %.1f%%", percent)
    }
    val memoryText = when {
        memory == null && maxMemory == null -> null
        memory == null -> "${bytes(maxMemory)} capacity"
        maxMemory == null -> "${bytes(memory)} used"
        else -> "${bytes(memory)} / ${bytes(maxMemory)}"
    }
    return listOfNotNull(cpuText, memoryText).joinToString(" · ").ifBlank { null }
}

/** The fleet filter segments with their counts. Counts honour the search, like the list. Other appears while a guest is in it (or it is selected). */
internal fun fleetSegments(guests: List<Guest>, search: String, filter: String): List<Pair<String, Int>> {
    fun count(name: String) = guests.count { guestVisible(it, name, search) }
    val segments = listOf("All", "Running", "Stopped", "Templates").map { it to count(it) }
    return if (filter == "Other" || guests.any(::guestOtherStatus)) segments + ("Other" to count("Other")) else segments
}

/** Used and total disk bytes when both are known and positive, else null. Proxmox leaves a VM's used disk at 0 without the guest agent. */
internal fun guestDiskUsage(used: Long?, limit: Long?): Pair<Long, Long>? =
    if (used != null && limit != null && used > 0L && limit > 0L) used to limit else null

/** A node CPU fact line, through [cpuShare] so it agrees with the gauge above it. */
internal fun cpuFact(fraction: Double?): String =
    cpuShare(fraction)?.let { String.format(Locale.ROOT, "%.1f%%", it * 100) } ?: "Unavailable"

/** Keyboard focus on a table row: a thin amber outline, drawn inside the row. The selected row keeps its solid edge. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.focusRing() {
    val w = 1.dp.toPx()
    drawRect(Tech.Amber.copy(alpha = .85f), topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
}

internal enum class RowClick { Select, Open }

/** The system's double-click time in milliseconds (Windows: Mouse settings), 500 when the desktop does not say. */
internal fun doubleClickMillis(): Long = runCatching {
    if (java.awt.GraphicsEnvironment.isHeadless()) null else (java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int)?.toLong()
}.getOrNull()?.coerceIn(200L, 900L) ?: 500L

/** Tells a double-click on one row from two clicks on different rows. A third quick click starts over, so it selects. */
internal class RowClicks(private val window: Long = doubleClickMillis()) {
    private var last: Any? = null
    private var at = 0L
    fun register(row: Any, nowMillis: Long): RowClick {
        val second = last == row && nowMillis - at <= window
        last = if (second) null else row
        at = nowMillis
        return if (second) RowClick.Open else RowClick.Select
    }
}

/** The tab a double-clicked guest opens on: the console while it runs, the summary otherwise. The demo has no consoles. */
internal fun guestOpenTab(guest: Guest, demo: Boolean = false) = if (guest.status == "running" && !guest.template && !demo) "Console" else "Summary"

/** Shared column widths so header and rows line up. Wider lists get wider CPU and memory cells and a network column. */
private data class Cols(val tile: Dp = 22.dp, val id: Dp = 40.dp, val cpu: Dp = 138.dp, val mem: Dp = 132.dp, val net: Dp = 0.dp, val disk: Dp = 0.dp, val up: Dp = 66.dp, val status: Dp = 84.dp, val gap: Dp = 12.dp)
private val LocalCols = staticCompositionLocalOf { Cols() }
private fun colsFor(width: Dp) = when {
    width >= 1250.dp -> Cols(cpu = 210.dp, mem = 170.dp, net = 100.dp, disk = 96.dp)
    width >= 1000.dp -> Cols(net = 100.dp)
    else -> Cols()
}

internal fun shortUptime(seconds: Long?): String = when {
    seconds == null || seconds < 0L -> "—"
    seconds >= 86400 -> "${seconds / 86400}d ${(seconds % 86400) / 3600}h"
    seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    else -> "${seconds / 60}m"
}

@Composable private fun NodeHeaderRow(node: String, count: Int, member: RecordedMember?, series: Series?, selected: Boolean, onClick: () -> Unit) {
    val TableCols = LocalCols.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val bg by animateColorAsState(if (selected) Tech.Raised else if (hovered) Tech.Raised.copy(alpha = .7f) else Tech.Black.copy(alpha = .55f), LocalMotion.current.fade(MotionDur.Quick))
    val online = member?.status == "online"
    val cpu = cpuShare(latestCpu(series, member?.cpuFraction))
    Row(Modifier.fillMaxWidth().height(30.dp).background(bg).hoverable(interaction).clickable(interactionSource = interaction, indication = null, onClick = onClick)
        .drawBehind { if (selected) drawRect(Tech.Amber, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height)); if (focused) focusRing() }.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TableCols.gap)) {
        Box(Modifier.width(TableCols.tile), contentAlignment = Alignment.Center) { Box(Modifier.size(7.dp).background(if (online) Tech.Green else if (member == null) Tech.Muted else Tech.Red)) }
        Text(node.uppercase().ifBlank { "UNKNOWN" }, fontFamily = Tech.Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.width(TableCols.id + 80.dp).identity(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$count guests", fontSize = 10.sp, color = Tech.Muted, fontFamily = Tech.Mono, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            member?.haState?.takeIf { !it.equals(member.status, ignoreCase = true) }?.let { state ->
                TechTag(state, haTagTint(state), help = "HA state. $MEMBERSHIP_HA_NOTE")
            }
        }
        Row(Modifier.width(TableCols.cpu), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (series != null && series.samples.isNotEmpty()) Sparkline(series, Modifier.weight(1f).height(14.dp), pulse = false) else Spacer(Modifier.weight(1f))
            Text(percent(cpu), fontSize = 10.sp, fontFamily = Tech.Mono, color = cpu?.let(::ramp) ?: Tech.Muted, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        val used = latestMemory(series, member?.memoryBytes)
        val cap = series?.samples?.lastOrNull()?.memMax ?: member?.maxMemoryBytes
        Text(when {
            used != null && cap != null && cap > 0L -> "${gib(used)} / ${gib(cap)} GiB"
            cap != null && cap > 0L -> "${gib(cap)} GiB"
            else -> "—"
        }, fontSize = 10.sp, fontFamily = Tech.Mono, color = Tech.Muted, modifier = Modifier.width(TableCols.mem), maxLines = 1)
        if (TableCols.net > 0.dp) Spacer(Modifier.width(TableCols.net))
        if (TableCols.disk > 0.dp) Spacer(Modifier.width(TableCols.disk))
        Spacer(Modifier.width(TableCols.up))
        Text(if (online) "ONLINE" else member?.status?.uppercase() ?: "", fontSize = 10.sp, fontFamily = Tech.Mono, color = if (online) Tech.Green else Tech.Muted, modifier = Modifier.width(TableCols.status))
    }
}

internal enum class GuestMarkTone { Healthy, Attention, Problem, Neutral }

internal data class GuestMark(val label: String, val tone: GuestMarkTone, val help: String)

internal data class GuestChipPlan(val marks: List<GuestMark>, val userTags: List<String>, val hiddenTags: List<String>)

/**
 * Status chips that share a row with user tags. Order is the priority: a template, then a failed HA state,
 * then a lock, then any other HA state. HA chips read "HA · <state>". Green is only the started state on a running
 * guest; red is error or fence; amber is recovery; every other HA state (stopped, disabled, ignored, a migration) is
 * neutral. [guestChipPlan] keeps at most [budget] chips and lets these marks take a slot ahead of a user tag.
 */
internal fun guestPriorityMarks(guest: Guest): List<GuestMark> {
    val ha = guestHaMark(guest)
    val lock = guestLockMark(guest)
    return listOfNotNull(
        if (guest.template) GuestMark("Template", GuestMarkTone.Neutral, "This guest is a template. It cannot be started.") else null,
        ha?.takeIf { it.tone == GuestMarkTone.Problem },
        lock,
        ha?.takeIf { it.tone == GuestMarkTone.Attention },
        ha?.takeIf { it.tone == GuestMarkTone.Healthy },
        ha?.takeIf { it.tone == GuestMarkTone.Neutral },
    )
}

internal fun guestChipPlan(guest: Guest, budget: Int): GuestChipPlan {
    val marks = guestPriorityMarks(guest).take(budget.coerceAtLeast(0))
    val room = (budget - marks.size).coerceAtLeast(0)
    return GuestChipPlan(marks, guest.tags.take(room), guest.tags.drop(room))
}

private fun guestHaMark(guest: Guest): GuestMark? {
    val state = guest.haState?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val word = state.lowercase()
    val label = "HA · $word"
    return when {
        word == "started" && guest.status == "running" ->
            GuestMark(label, GuestMarkTone.Healthy, "High availability is started on this running guest.")
        word == "error" || word == "fence" ->
            GuestMark(label, GuestMarkTone.Problem, "High availability reports $word.")
        word == "recovery" ->
            GuestMark(label, GuestMarkTone.Attention, "High availability is recovering this guest after a node failure.")
        else -> GuestMark(label, GuestMarkTone.Neutral, "High availability state: $word.")
    }
}

private fun guestLockMark(guest: Guest): GuestMark? {
    val lock = guest.lock?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return GuestMark("LOCK · $lock", GuestMarkTone.Attention, "Proxmox holds a $lock lock on this guest.")
}

@Composable internal fun GuestMarks(guest: Guest, budget: Int, overflow: @Composable (List<String>) -> Unit) {
    val plan = guestChipPlan(guest, budget)
    plan.marks.forEach { mark ->
        when (mark.tone) {
            GuestMarkTone.Healthy -> HealthyTag(mark.label, help = mark.help)
            GuestMarkTone.Attention -> AttentionTag(mark.label, help = mark.help)
            GuestMarkTone.Problem -> ProblemTag(mark.label, help = mark.help)
            GuestMarkTone.Neutral -> NeutralTag(mark.label, help = mark.help)
        }
    }
    plan.userTags.forEach { PveTag(it) }
    if (plan.hiddenTags.isNotEmpty()) overflow(plan.hiddenTags)
}

/**
 * A row that changes place (a list sorted by a live value re-orders as the values move, a filter removes rows) glides
 * there instead of jumping. Rows appear and disappear without a fade; the reveal on first load is the row's own.
 */
@Composable private fun androidx.compose.foundation.lazy.LazyItemScope.rowPlacement(): Modifier =
    if (LocalReduceMotion.current) Modifier else Modifier.animateItem(fadeInSpec = null, placementSpec = tween(SPARK_SETTLE_MS, easing = FastOutSlowInEasing), fadeOutSpec = null)

@Composable private fun GuestRow(guest: Guest, selected: Guest?, stale: Boolean, onSelect: (Guest) -> Unit, series:Series?=null, rowIndex:Int=99, modifier: Modifier = Modifier) {
    val TableCols = LocalCols.current
    val reduced = LocalReduceMotion.current
    var entered by remember(guest.resource) { mutableStateOf(reduced || rowIndex >= 14) }
    LaunchedEffect(guest.resource) { if (!entered) { delay(rowIndex * 18L); entered = true } }
    val motion = LocalMotion.current
    val reveal by animateFloatAsState(if (entered) 1f else 0f, motion.fade(MotionDur.Short))
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val isSelected = selected?.resource == guest.resource
    val background by animateColorAsState(if (isSelected) Tech.Raised else if (hovered) Tech.Raised.copy(alpha = .55f) else Tech.Panel, motion.fade(MotionDur.Quick))
    val failedEdge = LocalGuestActivity.current[guest.resource]?.failed == true
    val running = guest.status == "running"
    val metering = running && !guest.template
    val metrics = guest.metrics
    val cpu = if (metering) latestCpu(series, metrics?.cpuFraction) else null
    val mem = if (metering) latestMemory(series, metrics?.memoryBytes) else null
    val memMax = series?.samples?.lastOrNull()?.memMax ?: metrics?.memoryLimitBytes
    val fraction = memoryFraction(mem, memMax)
    Column(modifier.alpha(reveal).offset { IntOffset(0, ((1f-reveal)*motion.travel(6f).dp.toPx()).toInt()) }) {
        Row(Modifier.phosphorRow(guest.resource, failedEdge = failedEdge)
            .fillMaxWidth().height(36.dp).background(background).hoverable(interactions).clickable(interactionSource = interactions, indication = null) { onSelect(guest) }
            .drawBehind { if (isSelected) drawRect(Tech.Amber, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height)); if (focused) focusRing() }.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TableCols.gap)) {
            Box(Modifier.width(TableCols.tile)) { IdentityTile(if (guest.kind == "VM") "VM" else "CT", guest.template) }
            Text("${guest.id}", fontFamily = Tech.Mono, fontSize = 11.sp, color = if (isSelected) Tech.Amber else Tech.Muted, modifier = Modifier.width(TableCols.id).identity())
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(guest.name.ifBlank { "Guest ${guest.id}" }, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).identity())
                if (stale) Text("STALE", fontSize = 9.sp, fontFamily = Tech.Mono, color = Tech.Amber)
                GuestMarks(guest, if (guest.template) 2 else 3) { hidden ->
                    Text("+${hidden.size}", fontSize = 9.sp, color = Tech.Muted, fontFamily = Tech.Mono)
                }
            }
            Row(Modifier.width(TableCols.cpu), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (metering && series != null && series.samples.isNotEmpty()) Sparkline(series, Modifier.weight(1f).height(18.dp), pulse = false) else Spacer(Modifier.weight(1f))
                Text(percent(cpu), fontSize = 11.sp, fontFamily = Tech.Mono, color = if (cpu != null) ramp(cpu) else Tech.Muted,
                    modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Column(Modifier.width(TableCols.mem), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (fraction != null && mem != null && memMax != null) {
                    Text("${gib(mem)} / ${gib(memMax)} GiB", fontSize = 10.sp, fontFamily = Tech.Mono, color = Tech.Ink.copy(alpha = .85f), maxLines = 1)
                    UsageBar(fraction, Modifier.fillMaxWidth())
                } else Text(if (memMax != null && memMax > 0) "${gib(memMax)} GiB" else "—", fontSize = 10.sp, fontFamily = Tech.Mono, color = Tech.Muted)
            }
            if (TableCols.net > 0.dp) Column(Modifier.width(TableCols.net)) {
                val rates = series?.samples?.lastOrNull()
                if (metering && (rates?.netInRate != null || rates?.netOutRate != null)) {
                    RateLine("IN", rates?.netInRate, Tech.Green, 9.sp)
                    RateLine("OUT", rates?.netOutRate, Tech.Amber, 9.sp)
                } else Text("—", fontSize = 10.sp, fontFamily = Tech.Mono, color = Tech.Muted)
            }
            if (TableCols.disk > 0.dp) Column(Modifier.width(TableCols.disk), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val disk = if (metering) guestDiskUsage(metrics?.diskBytes, metrics?.diskLimitBytes) else null
                if (disk != null) {
                    Text("${gib(disk.first)}/${gib(disk.second)}G", fontSize = 10.sp, fontFamily = Tech.Mono, color = Tech.Ink.copy(alpha = .85f), maxLines = 1)
                    UsageBar(memoryFraction(disk.first, disk.second), Modifier.fillMaxWidth())
                } else Text(metrics?.diskLimitBytes?.takeIf { it > 0L }?.let { "${gib(it)} GiB" } ?: "—", fontSize = 10.sp, fontFamily = Tech.Mono, color = Tech.Muted, maxLines = 1)
            }
            Text(if (running) shortUptime(metrics?.uptimeSeconds) else "—", fontSize = 11.sp, fontFamily = Tech.Mono, color = Tech.Muted, modifier = Modifier.width(TableCols.up), maxLines = 1)
            Box(Modifier.width(TableCols.status)) { ActivityStatusTag(guest.status.ifBlank { "unknown" }, running, guest.resource, anchor = if (isSelected) RowAnchor(guest.resource) else null) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tech.Edge.copy(alpha = .45f)))
    }
}

@Composable private fun GuestDetailsPanel(state: WorkspaceState, workspace: Workspace, onOpenDetails: () -> Unit, onOpenConsole: () -> Unit, onPower: (PowerAction) -> Unit, pins: PinStore? = null, connectionKey: String? = null, onEngineProof: (Guest) -> Unit) {
    val guest = state.selected
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
        SectionLabel("Inspector")
        val member = selectedRecordedMember(state)
        if (member != null && guest == null) {
            NodeInspector(state, member, workspace)
        } else if (guest == null) {
            ClusterOverview(state)
            Text("Select a guest or node for details", fontSize = 11.sp, color = Tech.Muted)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tech.Space12)) {
                Text(guest.name.ifBlank { "Guest ${guest.id}" }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).identity())
                ActivityStatusTag(guest.status.ifBlank { "unknown" }, guest.status == "running", guest.resource, anchor = TagAnchor(guest.resource))
            }
            Text(listOfNotNull("${guest.kind.uppercase()} ${guest.id}", guest.node.ifBlank { "unknown node" }, guest.scope.takeIf { it.isNotEmpty() }?.let { "PDM remote $it" }).joinToString("  ·  "),
                Modifier.identity(), fontFamily = FontFamily.Monospace, fontSize = Tech.Label, color = Tech.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.kind != PDM_KIND && guest.scope.isEmpty()) MiniAction("Open details", primary = true, onClick = onOpenDetails)
                // Open details is the one amber action here. Console lights up when a guest is selected, then settles.
                if (guest.kind == "VM" && state.kind != PDM_KIND && guest.scope.isEmpty()) ArrivalAction("Console", guest.resource, onClick = onOpenConsole)
                HelpTip("Refresh details") { MiniAction("↻", enabled = !state.detailsLoading, width = 30.dp) { workspace.select(guest) } }
                if (pins != null && connectionKey != null) PinGuestButton(pins, connectionKey, guest)
                Spacer(Modifier.weight(1f))
                MiniAction("Close", onClick = workspace::clearSelection)
            }
            SpiceConsoleMessage(guest)
            ConsoleStillSlot(state, workspace, guest, androidx.compose.ui.unit.Dp.Unspecified, compact = true)
            HorizontalDivider(color=Tech.Edge)
            val live = state.history.guests[guest.resource]
            val sample = live?.samples?.lastOrNull()
            val running = guest.status == "running" && !guest.template
            val cpuNow = if (running) latestCpu(live, guest.metrics?.cpuFraction) else null
            val memNow = if (running) latestMemory(live, guest.metrics?.memoryBytes) else null
            val memMax = sample?.memMax ?: guest.metrics?.memoryLimitBytes
            val vcpus = guest.metrics?.maxCpu?.toInt() ?: state.details?.cpus
            if (running) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ArcGauge(cpuNow, "CPU", vcpus?.let { "$it vCPU" })
                ArcGauge(memoryFraction(memNow, memMax), "Memory", if (memMax != null && memMax > 0L) "${gib(memNow)} / ${gib(memMax)} GiB" else null)
            }
            // Off or template: empty gauges say nothing, so show what the guest is allocated instead.
            else Row(Modifier.fillMaxWidth().border(1.dp, Tech.Edge, Tech.Control).background(Tech.Black.copy(alpha = .35f)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (guest.template) "TEMPLATE" else "POWERED OFF", fontSize = 9.sp, letterSpacing = 1.sp, color = Tech.Muted, fontFamily = Tech.Mono)
                    Text("Allocated", fontSize = 11.sp, color = Tech.Muted, fontFamily = Tech.Mono)
                }
                listOf("CPU" to (vcpus?.let { "$it vCPU" } ?: "—"), "MEMORY" to (memMax?.takeIf { it > 0L }?.let { "${gib(it)} GiB" } ?: "—")).forEach { (label, value) ->
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(label, fontSize = 9.sp, letterSpacing = 1.sp, color = Tech.Muted, fontFamily = Tech.Mono)
                        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Tech.Mono, color = Tech.Ink.copy(alpha = .8f))
                    }
                }
            }
            if (running && live != null && live.samples.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row { Text("CPU · LAST ${live.samples.size} SAMPLES", fontSize = 9.sp, letterSpacing = 1.sp, color = Tech.Muted, fontFamily = Tech.Mono, modifier = Modifier.weight(1f)); Text(percent(cpuNow), fontSize = 9.sp, color = Tech.Muted, fontFamily = Tech.Mono) }
                    Sparkline(live, Modifier.fillMaxWidth().height(48.dp).background(Tech.Black.copy(alpha = .35f)))
                }
                MiniHeading("MEMORY", percent(memoryFraction(memNow, memMax)))
                Sparkline(live, Modifier.fillMaxWidth().height(32.dp).background(Tech.Black.copy(alpha = .35f)), floor = .1, pulse = false, ease = true) { memoryFraction(it.mem, it.memMax) }
                MiniHeading("NETWORK", "IN green · OUT amber")
                NetSparkline(live, Modifier.fillMaxWidth().height(32.dp).background(Tech.Black.copy(alpha = .35f)), pulse = false, ease = true)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) { Text("NET IN", fontSize = 9.sp, color = Tech.Muted, fontFamily = Tech.Mono); Text(formatRate(sample?.netInRate), fontSize = 12.sp, fontFamily = Tech.Mono) }
                    Column(Modifier.weight(1f)) { Text("NET OUT", fontSize = 9.sp, color = Tech.Muted, fontFamily = Tech.Mono); Text(formatRate(sample?.netOutRate), fontSize = 12.sp, fontFamily = Tech.Mono) }
                    Column(Modifier.weight(1f)) { Text("UPTIME", fontSize = 9.sp, color = Tech.Muted, fontFamily = Tech.Mono); Text(shortUptime(guest.metrics?.uptimeSeconds), fontSize = 12.sp, fontFamily = Tech.Mono) }
                }
            }
            SectionLabel("Power")
            PowerControls(state, guest, onPower)
            if (state.demo) Text("SAMPLE DETAILS", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Tech.Amber)
            state.overview?.remotes?.firstOrNull { it.scope == guest.scope && it.stale }?.let { remote ->
                Text("Stale remote data. ${remote.error.orEmpty()}", color = Tech.Amber, fontSize = Tech.Label, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (state.stale) Text("Inventory is stale. Sync to confirm the current location.", color = Tech.Amber, fontSize = Tech.Label)
            if (state.detailsLoading) TechProgress()
            state.detailsError?.let { Text(it, color = Tech.Red, fontSize = 13.sp) }
            val details = state.details
            val diskLimit = details?.diskLimitBytes ?: guest.metrics?.diskLimitBytes
            // Used space comes from the details read, or from the list row when the details do not carry it. No extra request.
            val disk = if (running) guestDiskUsage(details?.diskBytes?.takeIf { it > 0L } ?: guest.metrics?.diskBytes, diskLimit) else null
            val facts = listOf(Triple(if (disk != null) "Disk" else "Disk size", if (disk != null) "${bytes(disk.first)} / ${bytes(disk.second)}" else diskLimit?.takeIf { it > 0L }?.let(::bytes) ?: "—", disk?.let { memoryFraction(it.first, it.second) }),
                Triple("Node", guest.node, null), Triple("Template", if (guest.template) "Yes" else "No", null))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                facts.forEach { (label, value, fraction) -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label.uppercase(), fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = 1.sp, color = Tech.Muted, fontFamily = Tech.Mono)
                    Text(value, fontSize = 11.sp, lineHeight = 14.sp, fontFamily = Tech.Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (fraction != null) UsageBar(fraction, Modifier.fillMaxWidth())
                } }
            }
            if (guest.tags.isNotEmpty()) { MiniHeading("TAGS", "${guest.tags.size}"); FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { guest.tags.forEach { PveTag(it) } } }
            details?.provenance?.let { Text(it, fontSize = 11.sp, color = Tech.Muted); if (guest.kind == "VM") Text(PDM_DISK_NOTE, fontSize = 11.sp, color = Tech.Muted) }

        }
    }
}

@Composable private fun MiniHeading(label: String, trailing: String? = null) = Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label, Modifier.weight(1f), fontSize = 9.sp, letterSpacing = 1.sp, color = Tech.Muted, fontFamily = Tech.Mono, maxLines = 1)
    trailing?.let { Text(it, fontSize = 9.sp, color = Tech.Muted, fontFamily = Tech.Mono, maxLines = 1) }
}

/** Nothing selected: cluster gauges, live CPU and network trends, busiest guests by CPU and by memory. */
@Composable private fun ClusterOverview(state: WorkspaceState) {
    val overview = state.overview ?: return
    val total = fleetTotals(overview)
    val cluster = state.history.cluster
    val last = cluster.samples.lastOrNull()
    SectionLabel("Cluster overview")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        ArcGauge(total.cpuFraction, "CPU", "${total.nodes} ${if (total.nodes == 1) "node" else "nodes"}")
        ArcGauge(memoryFraction(total.memoryBytes, total.memoryLimitBytes), "Memory",
            if ((total.memoryLimitBytes ?: 0L) > 0L) "${gib(total.memoryBytes)} / ${gib(total.memoryLimitBytes)} GiB" else null)
    }
    MiniHeading("CPU · LAST ${cluster.samples.size} SAMPLES", percent(total.cpuFraction))
    Sparkline(cluster, Modifier.fillMaxWidth().height(56.dp).background(Tech.Black.copy(alpha = .35f)))
    MiniHeading("GUEST NETWORK")
    NetSparkline(cluster, Modifier.fillMaxWidth().height(40.dp).background(Tech.Black.copy(alpha = .35f)))
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f)) { RateLine("IN", last?.netInRate, Tech.Green, 11.sp) }
        Box(Modifier.weight(1f)) { RateLine("OUT", last?.netOutRate, Tech.Amber, 11.sp) }
    }
    val running = overview.guests.filter { it.status == "running" && !it.template }
    // Ranked by the last minute, and a row only moves up when it clearly leads: the lists used to re-sort on every
    // reading, so rows swapped places every few seconds.
    val cpuRanking = remember(state.connectionEpoch) { SteadyRanking<ResourceId>() }
    val memoryRanking = remember(state.connectionEpoch) { SteadyRanking<ResourceId>() }
    val byResource = running.associateBy { it.resource }
    MiniHeading("TOP CPU", "${running.size} running")
    cpuRanking.rank(running.associate { it.resource to (steadyValue(state.history.guests[it.resource], it.metrics?.cpuFraction) { s -> s.cpu } ?: 0.0) }).take(5).mapNotNull(byResource::get).forEach { row ->
        val series = state.history.guests[row.resource]
        val cpuNow = latestCpu(series, row.metrics?.cpuFraction)
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.name.ifBlank { "Guest ${row.id}" }, Modifier.weight(1f).identity(), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Sparkline(series ?: Series(), Modifier.size(96.dp, 16.dp), pulse = false)
            Text(percent(cpuNow), Modifier.width(40.dp), fontFamily = Tech.Mono, fontSize = 11.sp, textAlign = TextAlign.End, color = if (cpuNow != null) ramp(cpuNow) else Tech.Muted)
        }
    }
    MiniHeading("TOP MEMORY")
    memoryRanking.rank(running.associate { row ->
        row.resource to (steadyValue(state.history.guests[row.resource], memoryFraction(row.metrics?.memoryBytes, row.metrics?.memoryLimitBytes)) { s -> memoryFraction(s.mem, s.memMax) } ?: 0.0)
    }).take(5).mapNotNull(byResource::get).map { row ->
        val series = state.history.guests[row.resource]
        Triple(row, latestMemory(series, row.metrics?.memoryBytes), series?.samples?.lastOrNull()?.memMax ?: row.metrics?.memoryLimitBytes)
    }.forEach { (row, used, cap) ->
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.name.ifBlank { "Guest ${row.id}" }, Modifier.weight(1f).identity(), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            UsageBar(memoryFraction(used, cap), Modifier.width(96.dp))
            Text(if (used != null && cap != null) "${gib(used)}/${gib(cap)}G" else "—", Modifier.width(64.dp), fontFamily = Tech.Mono, fontSize = 10.sp, textAlign = TextAlign.End, color = Tech.Muted, maxLines = 1)
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable private fun NodeInspector(state: WorkspaceState, member: RecordedMember, workspace: Workspace) {
    var shell by remember(member.name) { mutableStateOf(false) }
    // Drawn inside the main window. Escape and outside clicks belong to the terminal, so only Close ends it.
    if (shell) androidx.compose.ui.window.Dialog(onDismissRequest = { shell = false },
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.9f).fillMaxHeight(.88f).widthIn(max = 1400.dp), color = ringColors.surface, shape = Tech.Plate,
            border = androidx.compose.foundation.BorderStroke(1.dp, Tech.Edge), shadowElevation = 12.dp) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(14.dp, 3.dp).background(Tech.Amber))
                    Text("NODE SHELL", fontFamily = Tech.Mono, fontSize = 12.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = Tech.Muted)
                    Text(member.name, Modifier.weight(1f).identity(), fontFamily = Tech.Mono, fontSize = 12.sp, color = Tech.Ink)
                    MiniAction("Close") { shell = false }
                }
                TerminalView(state, workspace, TerminalTarget(member.name), Modifier.weight(1f))
            }
        }
    }
    val storage = state.overview?.storage.orEmpty().filter { it.remote == member.remote && it.node == member.name }
    val report = sharedStorageReport(state.overview?.storage.orEmpty().filter { it.remote == member.remote })
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(member.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false, modifier = Modifier.weight(1f, fill = false).identity())
            member.haState?.takeIf { !it.equals(member.status, ignoreCase = true) }?.let { state ->
                TechTag(state, haTagTint(state), help = "HA state. $MEMBERSHIP_HA_NOTE")
            }
            StatusTag(member.status.ifBlank { "unknown" }, member.status == "online")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (liveNodeReadAvailable(state)) MiniAction("Refresh", primary = true, enabled = !state.nodeStatusLoading, onClick = workspace::refreshNodeStatus)
            Spacer(Modifier.weight(1f))
            if (state.kind != PDM_KIND && !state.demo && member.status == "online") MiniAction("Shell") { shell = true }
            WebUiAction(if (state.demo || state.kind == PDM_KIND) null else PveWebUi.node(state.address, member.name))
            MiniAction("Close", onClick = workspace::clearMember)
        }
        if (state.demo) Text(RECORDED_SAMPLE_CAPTION, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Tech.Amber)
        HorizontalDivider(color = Tech.Edge)
        val nodeSeries = state.history.nodes[member.name]
        val sample = nodeSeries?.samples?.lastOrNull()
        val used = latestMemory(nodeSeries, member.memoryBytes)
        val capacity = sample?.memMax ?: member.maxMemoryBytes
        val cpuNow = cpuShare(latestCpu(nodeSeries, member.cpuFraction))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ArcGauge(cpuNow, "CPU", member.maxCpu?.let { "${it.toInt()} cores" })
            ArcGauge(memoryFraction(used, capacity), "Memory", if ((capacity ?: 0L) > 0L) "${gib(used)} / ${gib(capacity)} GiB" else null)
        }
        HistoryGroup(state, workspace, member.name)
        LaunchedEffect(member.name, state.connectionEpoch) { workspace.loadNodeNetwork(member.name) }
        Spacer(Modifier.height(6.dp))
        NodeNetworkPanel(state, workspace, member.name)
        MiniHeading("NODE")
        FactLine("CPU", cpuFact(member.cpuFraction))
        member.maxCpu?.let { FactLine("CPU cores", if (it % 1.0 == 0.0) it.toLong().toString() else String.format(Locale.ROOT, "%.1f", it)) }
        FactLine("Memory", when {
            member.memoryBytes == null && member.maxMemoryBytes == null -> "Unavailable"
            member.memoryBytes == null -> "${bytes(member.maxMemoryBytes)} capacity"
            member.maxMemoryBytes == null -> "${bytes(member.memoryBytes)} used"
            else -> "${bytes(member.memoryBytes)} / ${bytes(member.maxMemoryBytes)}"
        })
        FactLine("Root filesystem", "${bytes(member.rootBytes)} / ${bytes(member.maxRootBytes)}")
        FactLine("Uptime", uptime(member.uptimeSeconds))
        MiniHeading("STORAGE", "${storage.size}")
        if (storage.isEmpty()) Text("No recorded storage for this node was in the sample.", color = Tech.Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        storage.forEach { row ->
            val fraction = memoryFraction(row.diskBytes, row.maxDiskBytes)
            Column(Modifier.fillMaxWidth().drawBehind { drawLine(Tech.Edge, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), 1f) }.padding(vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(row.id, Modifier.weight(1f), fontFamily = Tech.Mono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
                    row.pluginType?.let {
                        Text(it, fontSize = 9.sp, lineHeight = 11.sp, fontFamily = Tech.Mono, color = Tech.Muted, maxLines = 1,
                            modifier = Modifier.border(1.dp, Tech.Edge, Tech.Control).padding(start = 4.dp, end = 7.dp, top = 1.dp, bottom = 1.dp))
                    }
                    if (row.shared == true) NeutralTag("Shared", help = SHARED_POOL_NOTE)
                    val status = row.status.ifBlank { "unknown" }
                    if (storageStatusInactive(status)) TechTag(status, storageStatusTint(status), help = "Recorded storage status: $status. This pool is not available for new disks.")
                    else Text(status, fontSize = 9.sp, fontFamily = Tech.Mono, color = if (status == "available") Tech.Green else Tech.Muted, maxLines = 1)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UsageBar(fraction, Modifier.weight(1f))
                    Text("${storageSize(row.diskBytes)} / ${storageSize(row.maxDiskBytes)}", fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Ink.copy(alpha = .85f), maxLines = 1)
                }
                row.content?.let { Text(it, fontFamily = Tech.Mono, fontSize = 9.sp, lineHeight = 11.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (row.shared == true && row.id in report.disagreements) {
                    Text(SHARED_DISAGREE_NOTE, fontSize = 10.sp, color = Tech.Amber, lineHeight = 13.sp)
                    state.overview?.storage.orEmpty().filter { it.remote == member.remote && it.shared == true && it.id == row.id }.forEach { peer ->
                        Text("${peer.node}: ${storageSize(peer.diskBytes)} / ${storageSize(peer.maxDiskBytes)}", fontFamily = Tech.Mono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
                    }
                }
            }
        }
        HorizontalDivider(color = Tech.Edge)
        if (state.nodeStatusLoading) TechProgress()
        state.nodeStatusNote?.let { Text(it, color = if (state.demo) Tech.Amber else Tech.Muted, fontSize = Tech.Label, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        if (state.nodeStatusStale) Text("The last live status for this node is stale.", color = Tech.Amber, fontSize = Tech.Label, maxLines = 2, overflow = TextOverflow.Ellipsis)
        state.nodeStatusError?.let { Text(it, color = Tech.Red, fontSize = Tech.Label, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        state.nodeStatus?.takeIf { it.node == member.name }?.let { live ->
            MiniHeading("LIVE STATUS")
            FactLine("CPU", cpuFact(live.cpuFraction))
            FactLine("Memory", "${bytes(live.memoryUsedBytes)} / ${bytes(live.memoryTotalBytes)}")
            FactLine("Root filesystem", "${bytes(live.rootUsedBytes)} / ${bytes(live.rootTotalBytes)}")
            FactLine("Uptime", uptime(live.uptimeSeconds))
            live.pveVersion?.let { FactLine("PVE version", it) }
            Text("UPDATED ${timestamp(state.nodeStatusUpdatedAt)}", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Tech.Muted)
        }
        HorizontalDivider(color = Tech.Edge)
        NodeToolsPanel(state, workspace, member.name)
    }
}

@Composable private fun PowerControls(state: WorkspaceState, guest: Guest, onPower: (PowerAction) -> Unit) {
    if (state.kind == PDM_KIND || guest.scope.isNotEmpty()) {
        Text("Power actions are not available for a PDM guest.", color = Tech.Muted, fontSize = Tech.Label)
        return
    }
    val actionsAvailable = liveActionsAvailable(state) || (state.demo && state.overview != null && !state.loading && !state.stale)
    val outcome = state.submissions.firstOrNull { it.remote == guest.remote && it.vmid == guest.id && it.kind == guest.kind }
    when {
        guest.template -> Text("Templates have no power action.", color = Tech.Muted, fontSize = Tech.Label)
        state.stale -> Text("Inventory is stale. Sync before sending an action.", color = Tech.Amber, fontSize = Tech.Label)
        unresolvedSubmission(state) != null -> Text(unresolvedActionText(unresolvedSubmission(state)!!), color = Tech.Amber, fontSize = Tech.Label)
        state.loading -> Text("Inventory is still loading. Power actions are unavailable.", color = Tech.Muted, fontSize = Tech.Label)
        !actionsAvailable -> Text("Power actions are unavailable for this session.", color = Tech.Muted, fontSize = Tech.Label)
        !actionMatches(guest, PowerAction.Start) && !actionMatches(guest, PowerAction.Shutdown) ->
            Text("This state has no start, shutdown, or hard stop action.", color = Tech.Muted, fontSize = Tech.Label)
        else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (actionMatches(guest, PowerAction.Start)) MiniAction("Start", primary = true, enabled = !state.powerBusy) { onPower(PowerAction.Start) }
            if (actionMatches(guest, PowerAction.Shutdown)) MiniAction("Shut down", enabled = !state.powerBusy) { onPower(PowerAction.Shutdown) }
            if (actionMatches(guest, PowerAction.HardStop)) MiniAction("Hard stop", enabled = !state.powerBusy) { onPower(PowerAction.HardStop) }
        }
    }
    state.powerNotice?.let { Text(it, color = Tech.Amber, fontSize = Tech.Label) }
    outcome?.let {
        Text("${powerActionTitle(it.action)} · ${powerPhaseLabel(it)}", fontFamily = FontFamily.Monospace, fontSize = Tech.Label, color = Tech.Ink)
        if (it.detail != powerPhaseLabel(it)) Text(it.detail, color = Tech.Muted, fontSize = Tech.Label)
        Text("The outcome stays in Activity if you select another guest.", color = Tech.Muted, fontSize = 11.sp)
    }
}

@Composable private fun MetricValue(label: String, value: String) {
    Text(label, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Tech.Muted)
    Text(value, fontFamily = FontFamily.Monospace, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
}

/** Dense label/value row for the node inspector. */
@Composable private fun FactLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label.uppercase(), fontSize = 9.sp, letterSpacing = .8.sp, color = Tech.Muted, fontFamily = Tech.Mono, maxLines = 1, softWrap = false)
        Text(value, Modifier.weight(1f), fontFamily = Tech.Mono, fontSize = 11.sp, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun InspectorLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tech.Space8)) {
        Text(label, modifier = Modifier.width(IntrinsicSize.Max), fontSize = Tech.Label, color = Tech.Muted, softWrap = false)
        Text(value, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = Tech.Label,
            textAlign = TextAlign.End, softWrap = true, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}


