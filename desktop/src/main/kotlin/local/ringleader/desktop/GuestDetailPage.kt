@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.key.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ringleader.core.*

/** Set by a test. Runs while the guest header is composed, so immersive mode can prove the header left. */
internal var guestHeaderProbe: (() -> Unit)? = null

@Composable fun GuestDetailPage(state: WorkspaceState, workspace: Workspace, onBack: () -> Unit, onAction: (PowerAction, SnapshotRequest?) -> Unit, initialTab: String = "Summary", initialScroll: Int = 0, tabNonce: Long = 0, pins: PinStore? = null, connectionKey: String? = null, onSwitchGuest: ((Guest) -> Unit)? = null) {
    val guest = state.selected ?: return
    val tabs = if (guest.kind == "VM") listOf("Summary","Console","Hardware","Snapshots","Backups","Bluetooth","Actions") else listOf("Summary","Console","Hardware","Snapshots","Backups","Actions")
    var tab by remember { mutableStateOf(if (initialTab in tabs) initialTab else "Summary") }
    LaunchedEffect(tabs) { if (tab !in tabs) tab = "Summary" }
    LaunchedEffect(tabNonce) { if (tabNonce != 0L) tab = if (initialTab in tabs) initialTab else "Summary" }
    var pinMenu by remember { mutableStateOf(false) }
    var serviceDialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var ram by remember { mutableStateOf(false) }
    val created = state.submissions.filter { it.action == PowerAction.SnapshotCreate && it.phase == PowerPhase.Succeeded && it.remote == guest.remote && it.vmid == guest.id && it.kind == guest.kind }
    var seenCreates by remember(guest.resource) { mutableStateOf(created.map { it.localId }.toSet()) }
    LaunchedEffect(created.map { it.localId }) {
        val fresh = created.filter { it.localId !in seenCreates }
        if (fresh.isNotEmpty()) {
            name = ""; description = ""; ram = false; creating = false
            seenCreates = seenCreates + fresh.map { it.localId }
        }
    }
    val report = state.inspection?.takeIf { it.resource == guest.resource }
    val lockName = guest.lock?.takeIf { it.isNotBlank() }
    val unresolved = state.submissions.any { it.phase == PowerPhase.Accepted || it.phase == PowerPhase.Unknown }
    val ready = !state.powerBusy && !state.stale && !state.loading && !unresolved && lockName == null
    val blockedReason = when {
        lockName != null -> "Locked: $lockName."
        unresolved -> "An earlier action is unresolved. Check it in Activity before another action."
        else -> null
    }
    val scroll = rememberScrollState(initialScroll)
    // Remembered here, not inside the Console branch, so the call order stays put when the tab changes.
    val switcher = remember { ConsoleSwitcherState() }
    var guestsOpen by remember { mutableStateOf(false) }
    val immersive = LocalImmersive.current
    val consoleImmersive = immersive?.active == true && tab == "Console"
    val overlayClicks = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    // A dropped connection leaves immersive. Demo is not a connection, so it does not.
    LaunchedEffect(state.connected, state.demo) { if (!state.connected && !state.demo) immersive?.exit() }
    LaunchedEffect(consoleImmersive) { if (!consoleImmersive) guestsOpen = false }
    // The guests list takes the keyboard back from the console when it opens (the terminal's bar does not clear focus
    // as the VNC toolbar does), so Escape and the filter field reach the list rather than the guest.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(guestsOpen) { if (guestsOpen) focusManager.clearFocus(force = true) }
    val onShortcut = rememberUpdatedState<(ConsoleShortcut) -> Unit> { shortcut ->
        val list = switcherGuests(state, guest.resource, switcher.runningOnly, switcher.filter)
        when (shortcut) {
            ConsoleShortcut.PreviousGuest -> if (onSwitchGuest != null) neighbourGuest(list, guest.resource, -1)?.let(onSwitchGuest)
            ConsoleShortcut.NextGuest -> if (onSwitchGuest != null) neighbourGuest(list, guest.resource, 1)?.let(onSwitchGuest)
            ConsoleShortcut.Immersive -> immersive?.toggle()
        }
    }
    val shortcutHandler = rememberUpdatedState<(androidx.compose.ui.input.key.KeyEvent) -> Boolean> { event ->
        val decision = consoleHostDecision(event, guestsOpen, immersive?.active == true, immersive != null)
        if (!decision.consume) return@rememberUpdatedState false
        when (decision.action) {
            ConsoleHostAction.Previous -> onShortcut.value(ConsoleShortcut.PreviousGuest)
            ConsoleHostAction.Next -> onShortcut.value(ConsoleShortcut.NextGuest)
            ConsoleHostAction.ToggleImmersive -> immersive?.toggle()
            ConsoleHostAction.CloseGuests -> guestsOpen = false
            ConsoleHostAction.ExitImmersive -> { guestsOpen = false; immersive?.exit() }
            null -> Unit
        }
        true
    }
    DisposableEffect(tab) {
        // Captured here: onDispose runs after tab has already moved, and must still know this effect was the Console one.
        val onConsole = tab == "Console"
        if (onConsole) ConsoleKeys.handler = { shortcutHandler.value(it) }
        onDispose {
            ConsoleKeys.handler = null
            // Leaving the Console tab, or the page leaving the composition, cannot leave immersive on.
            if (onConsole) immersive?.exit()
        }
    }
    LayoutViewport(if (tab == "Console" && !consoleImmersive) 480.dp else 0.dp) {
    Column(Modifier.fillMaxSize().then(if (tab != "Console") Modifier.verticalScroll(scroll) else Modifier).padding(if (consoleImmersive) 0.dp else 16.dp),verticalArrangement = Arrangement.spacedBy(if (consoleImmersive) 0.dp else Tech.Space12)) {
        if (!consoleImmersive) {
        SideEffect { guestHeaderProbe?.invoke() }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HelpTip(guest.name, identity = true) {
                Text(guest.name, Modifier.fillMaxWidth().identity(), fontSize=18.sp, maxLines=1, overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Text("${guest.kind} ${guest.id} · ${guest.node}", Modifier.identity(), color=Tech.Muted, style=MaterialTheme.typography.labelSmall, maxLines=1, overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(6.dp), verticalArrangement=Arrangement.spacedBy(6.dp), itemVerticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            MiniAction("‹ Fleet", onClick = onBack)
            ActivityStatusTag(guest.status,guest.status=="running",guest.resource,anchor = TagAnchor(guest.resource))
            GuestMarks(guest, 2) { hidden ->
                HelpTip(hidden.joinToString(", ")) {
                    Text("+${hidden.size}", fontSize = 9.sp, color = Tech.Muted, fontFamily = Tech.Mono)
                }
            }
            if (pins != null && connectionKey != null) {
                PinGuestButton(pins, connectionKey, guest)
                Box {
                    MiniAction("\u25be", width = 30.dp) { pinMenu = true }
                    DropdownMenu(pinMenu, { pinMenu = false }, containerColor = Tech.Raised) {
                        val viewPinned = pins.hasView(connectionKey, guest, tab)
                        DropdownMenuItem(text = { Text(if (viewPinned) "Unpin this tab" else "Pin this tab", fontSize = 13.sp, color = Tech.Ink) }, onClick = {
                            pinMenu = false
                            pins.toggleView(connectionKey, guest, tab)
                        })
                        DropdownMenuItem(text = { Text("Pin a service\u2026", fontSize = 13.sp, color = Tech.Ink) }, onClick = {
                            pinMenu = false
                            serviceDialog = true
                        })
                    }
                }
            }
            // The tab strip already marks the open tab in amber; this shortcut lights up when the page opens, then settles.
            // A switch in the console rail is not an arrival: the page stays open on its Console tab, and a flash in the
            // header on every click read as the page reloading.
            var arrival by remember { mutableStateOf<Any>(guest.resource) }
            SideEffect { if (tab != "Console" && arrival != guest.resource) arrival = guest.resource }
            ArrivalAction("Console", arrival) { tab = "Console" }
            WebUiAction(if (state.demo || state.kind == PDM_KIND || guest.scope.isNotEmpty()) null else PveWebUi.guest(state.address, guest.kind, guest.id))
            HelpTip("Refresh") { MiniAction("↻", enabled = !state.inspectionLoading, width = 30.dp) { workspace.inspectGuest() } }
        }
        if (serviceDialog && pins != null && connectionKey != null) PinServiceDialog(guest, report, state.demo, { serviceDialog = false }) { serviceName, url ->
            val error = pins.addService(connectionKey, guest, serviceName, url)
            if (error == null) serviceDialog = false
            error
        }
        SpiceConsoleMessage(guest)
        if(state.demo) Text("SAMPLE DATA · SIMULATED ACTIONS",color = Tech.Amber,fontFamily=Tech.Mono,fontSize=10.sp,letterSpacing=1.sp)
        if(state.stale) Text("Inventory is stale. Sync in Fleet before actions.",color = Tech.Amber)
        // The loading bar is drawn over the tab strip's bottom edge. As a row of its own it pushed everything below down
        // by its height and the gap while a guest's details loaded, and back afterwards, so every switch in the console
        // rail made the page and the console picture jump twice.
        Box(Modifier.fillMaxWidth()) {
            TechTabs(tabs, tab, { tab = it })
            if (state.inspectionLoading) TechProgress(Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
        }
        state.detailsError?.let { Text(it,color = Tech.Red) }
        }
        when(tab) {
            "Summary" -> {
                // Compact facts strip: status, uptime, addresses, node, kind.
                Row(Modifier.fillMaxWidth().border(1.dp, Tech.Edge, Tech.Plate).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    @Composable fun fact(label: String, value: String, tint: androidx.compose.ui.graphics.Color = Tech.Ink) = Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(label, fontSize = 9.sp, letterSpacing = 1.sp, color = Tech.Muted, fontFamily = Tech.Mono); Text(value, fontSize = 12.sp, color = tint, fontFamily = Tech.Mono, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    fact("STATUS", (state.details?.status ?: guest.status).uppercase(), if (guest.status == "running") Tech.Green else Tech.Muted)
                    fact("UPTIME", if (guest.status == "running") duration(state.details?.uptimeSeconds ?: guest.metrics?.uptimeSeconds) else "—")
                    fact("NODE", guest.node)
                    fact("TYPE", if (guest.kind == "VM") "QEMU VM" else "LXC container")
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("ADDRESSES", fontSize = 9.sp, letterSpacing = 1.sp, color = Tech.Muted, fontFamily = Tech.Mono)
                        Text(when (report?.addresses?.error) { null -> report?.addresses?.value?.joinToString("  ·  ")?.ifEmpty { "None reported" } ?: "Loading"; GUEST_OFF_ADDRESSES -> "—"; else -> "Unavailable" }, Modifier.identity(), fontSize = 12.sp, fontFamily = Tech.Mono,
                            color = if (report?.addresses?.error != null) Tech.Muted else Tech.Ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                val live = state.history.guests[guest.resource] ?: Series()
                val runningGuest = guest.status == "running" && !guest.template
                val cpuNow = if (runningGuest) latestCpu(live, state.details?.cpuFraction ?: guest.metrics?.cpuFraction) else null
                val mem = if (runningGuest) latestMemory(live, state.details?.memoryBytes ?: guest.metrics?.memoryBytes) else null
                val cap = live.samples.lastOrNull()?.memMax ?: state.details?.memoryLimitBytes ?: guest.metrics?.memoryLimitBytes
                if (runningGuest && guest.kind == "VM") LaunchedEffect(guest.resource) { autoCaptureConsoleStill(state, workspace, guest) }
                if (runningGuest) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    if (guest.kind == "VM" && state.kind != PDM_KIND && !state.demo) ConsoleStillCard(state, workspace, guest, 300.dp, onOpenConsole = { tab = "Console" })
                    ArcGauge(cpuNow, "CPU", state.details?.cpus?.let { "$it vCPU" }, modifier = Modifier.opensLens { guestLens(guest, LiveMetric.Cpu) }, series = live, metric = { it.cpu })
                    ArcGauge(if (runningGuest) memoryFraction(mem, cap) else null, "Memory", "${iec(mem?.toDouble())} / ${iec(cap?.toDouble())}", modifier = Modifier.opensLens { guestLens(guest, LiveMetric.Memory) }, series = live, metric = { memoryFraction(it.mem, it.memMax) })
                    Sparkline(live, Modifier.opensLens { guestLens(guest, LiveMetric.Cpu) }.weight(1f).height(56.dp))
                }
                HistoryGroup(state, workspace)
            }
            "Hardware" -> {
                report?.hardware?.error?.let { Text(it,color = Tech.Amber) }
                if (report?.hardware?.value == null && report?.hardware?.error == null) Text("Loading hardware…", color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 12.sp)
                report?.hardware?.value?.let { hardware ->
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val columns = if (maxWidth >= 900.dp) 2 else 1
                        Column {
                            hardware.fields.entries.toList().chunked(columns).forEach { fields ->
                                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                    fields.forEach { (key, value) ->
                                        val change = hardware.pending.firstOrNull { it.key == key }
                                        Row(Modifier.weight(1f).height(30.dp).drawBehind { drawLine(Tech.Edge, Offset(0f, size.height), Offset(size.width, size.height), 1f) }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text(hardwareFieldLabel(key), Modifier.weight(.4f), color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 9.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                            Text(hardwareValueText(key, value) + (change?.let { "  ·  ${pendingCaption(it)}" } ?: ""), Modifier.weight(.6f), fontFamily = Tech.Mono, fontSize = 11.sp, color = if (change != null) Tech.Amber else Tech.Ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        }
                                    }
                                    if (fields.size < columns) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    if (hardware.disks.isNotEmpty()) SectionLabel("Disks", suffix = "${hardware.disks.size}")
                    hardware.disks.forEach { d ->
                        val change = hardware.pending.firstOrNull { it.key == d.key }
                        val volume = listOfNotNull(d.volume, mountCaption(d), change?.let { pendingCaption(it) }).joinToString("  ·  ").ifBlank { "—" }
                        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).drawBehind { drawLine(Tech.Edge, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(d.key, Modifier.width(88.dp), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Amber)
                            Text(volume, Modifier.weight(1f), fontFamily = Tech.Mono, fontSize = 11.sp, color = if (change != null) Tech.Amber else Tech.Ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(d.storage ?: "—", Modifier.widthIn(min = 120.dp), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted, maxLines = 1, softWrap = false)
                            Text(d.size ?: "—", Modifier.widthIn(min = 64.dp), maxLines = 1, softWrap = false, fontFamily = Tech.Mono, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        }
                    }
                    if (hardware.nics.isNotEmpty()) SectionLabel("Network", suffix = "${hardware.nics.size}")
                    hardware.nics.forEach { n ->
                        val model = nicModel(n.properties)
                        val mac = nicAddress(n.properties)
                        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).drawBehind { drawLine(Tech.Edge, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(n.key, Modifier.width(72.dp), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Amber)
                            Text(listOfNotNull(model ?: n.properties["name"], mac).joinToString("  ·  ").ifEmpty { "—" }, Modifier.weight(1f).identity(), fontFamily = Tech.Mono, fontSize = 11.sp, maxLines = 1)
                            // Minimum widths keep the columns aligned; at large text they grow instead of cutting "VLAN 10" to "VLAN".
                            Text(n.properties["bridge"]?.let { "bridge $it" } ?: "—", Modifier.widthIn(min = 120.dp), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted, maxLines = 1, softWrap = false)
                            Text(n.properties["tag"]?.let { "VLAN $it" } ?: "", Modifier.widthIn(min = 64.dp), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted, maxLines = 1, softWrap = false)
                            Text(nicExtras(n.properties), Modifier.width(150.dp).identity(), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            if (n.properties["link_down"] == "1") AttentionTag("Unplugged", help = "The virtual cable is unplugged (link_down=1): the guest sees no link on this NIC.")
                            if (n.properties["firewall"] == "1") HealthyTag("FW", help = "The Proxmox firewall is enabled on this NIC.")
                            hardware.pending.firstOrNull { it.key == n.key }?.let { Text(pendingCaption(it), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Amber, maxLines = 1) }
                        }
                    }
                    val shown = hardware.fields.keys + hardware.disks.map { it.key } + hardware.nics.map { it.key } + hardware.usb.map { it.key } + hardware.pci.map { it.key }
                    hardware.pending.filter { it.key !in shown }.forEach { change ->
                        Text("${change.key}  ·  ${pendingCaption(change)}", color = Tech.Amber, fontFamily = Tech.Mono, fontSize = 11.sp)
                    }
                }
            }
            "Snapshots" -> {
                blockedReason?.let { Text(it, color = Tech.Amber, fontFamily = Tech.Mono, fontSize = 12.sp) }
                val snaps = report?.snapshots
                snaps?.error?.let { Text(it, color = Tech.Amber) }
                TechAction(if (creating) "Close form" else "Create snapshot", active = true, enabled = ready || creating) { creating = !creating }
                if (creating) {
                // Camera brackets snap in around the form when a snapshot starts for this guest.
                Column(Modifier.shutterBrackets(guest.resource), verticalArrangement = Arrangement.spacedBy(Tech.Space12)) {
                TechField(name, { name = it }, "Snapshot name", Modifier.widthIn(max = 420.dp), placeholder = "before-upgrade",
                    error = if (name.isNotEmpty() && !snapshotNameValid(name)) "Start with a letter; letters, digits, - and _ only, 2 to 40 characters, not “current”, “vzdump”, or “pending”." else null)
                TechField(description, { description = it }, "Description (optional)", Modifier.widthIn(max = 420.dp), singleLine = false)
                if (guest.kind == "VM" && guest.status == "running") TechCheck(ram, { ram = it }, "Include RAM")
                TechAction("Create snapshot", enabled = ready && snapshotNameValid(name)) { onAction(PowerAction.SnapshotCreate, SnapshotRequest(name, description, ram && guest.kind == "VM" && guest.status == "running")) }
                }
                }
                val rows = orderedSnapshots(snaps?.value.orEmpty())
                when {
                    snaps == null || (snaps.value == null && snaps.error == null) -> Text("Loading snapshots…", color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 12.sp)
                    rows.isEmpty() && snaps.error == null -> Text("No snapshots.", color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 12.sp)
                }
                rows.forEach { snapshot ->
                    val depth = snapshotDepth(snapshot, rows)
                    val incomplete = !snapshot.snapstate.isNullOrBlank()
                    HelpTip(listOfNotNull(snapshot.description, snapshot.time?.let { dateAndAge(it) }, if (snapshot.current) "The guest is on this snapshot now." else null, if (snapshot.ram) "This snapshot includes the guest's memory." else null, snapshot.snapstate?.let { "Incomplete: $it" }).joinToString(" / ")) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).drawBehind { drawLine(Tech.Edge, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }.padding(start = (depth * 12).dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(snapshot.name, Modifier.weight(1f), fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            if (snapshot.current) HealthyTag("Now")
                            if (snapshot.ram) AttentionTag("RAM")
                            if (incomplete) Text("Incomplete: ${snapshot.snapstate}", Modifier.width(250.dp), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Amber, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            // As wide as the time and age need (at least the old column): the name gives up room first, not the age.
                            else Text(snapshot.time?.let { dateAndAge(it) } ?: "Current state", Modifier.widthIn(min = 250.dp), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1, softWrap = false)
                            if (!snapshot.current) {
                                MiniAction("Rollback", enabled = ready && snapshotRollbackAllowed(snapshot)) { onAction(PowerAction.SnapshotRollback, SnapshotRequest(snapshot.name)) }
                                MiniAction("Delete", enabled = ready) { onAction(PowerAction.SnapshotDelete, SnapshotRequest(snapshot.name)) }
                            }
                        }
                    }
                }
            }
            "Backups" -> {
                val backups = report?.backups
                when {
                    backups == null || (backups.value == null && backups.error == null) -> Text("Loading backups…", color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 12.sp)
                    backups.error == BACKUPS_NOT_SCANNED || backups.error == BACKUPS_NONE_ACTIVE -> Text(backups.error.orEmpty(), color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 12.sp)
                    else -> {
                        backups.error?.let { Text(it, color = Tech.Amber, fontFamily = Tech.Mono, fontSize = 12.sp) }
                        val rows = backups.value.orEmpty()
                        if (rows.isEmpty() && backups.error == null) Text("No backups for this guest.", color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 12.sp)
                        rows.forEach { backup ->
                            val (verify, warn) = backupVerificationText(backup)
                            TechPanel(Modifier.fillMaxWidth(), padding = 0.dp) {
                            FlowRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(Tech.Space12), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(dateAndAge(backup.time), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted)
                                Text(iec(backup.size?.toDouble()), fontSize = Tech.Label)
                                Text(backup.storage, Modifier.identity(), fontSize = Tech.Label)
                                Text(verify, fontFamily = Tech.Mono, fontSize = 10.sp, color = if (warn) Tech.Amber else Tech.Muted)
                                if (backup.protected) AttentionTag("Protected", help = "This backup is locked. Proxmox will not remove it until the protection is cleared.")
                                else Text("\u2014", fontSize = 10.sp, color = Tech.Muted)
                            }
                            backup.notes?.let { Text(it, fontSize = Tech.Label, color = Tech.Muted) }
                        } }
                    }
                }
            }
            "Bluetooth" -> GuestBluetoothTab(state, workspace, guest, report?.hardware?.value)
            "Console" -> Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(if (consoleImmersive) 0.dp else 8.dp)) {
                if (onSwitchGuest != null && !consoleImmersive) ConsoleSwitcher(state, guest, switcher, onSwitchGuest, Modifier.fillMaxHeight())
                // The weight lives on this box: key() does not keep the row's scope, so the console cannot take it itself.
                // Keyed by guest: every remembered console state belongs to one guest and starts empty for the next one.
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    key(guest.resource) {
                        val openGuests = if (onSwitchGuest != null) ({ guestsOpen = true }) else null
                        if (guest.kind == "VM") VncConsoleView(state, workspace, guest, Modifier.fillMaxSize(), onHostShortcut = { onShortcut.value(it) }, onGuests = openGuests)
                        else TerminalView(state, workspace, TerminalTarget.guest(guest), Modifier.fillMaxSize(), onHostShortcut = { onShortcut.value(it) }, onGuests = openGuests)
                    }
                    if (guestsOpen && consoleImmersive && onSwitchGuest != null) {
                        // Recorded as open: page shortcuts wait, and Escape at the window closes this list first.
                        RegisterModal(ModalKind.Overlay) { guestsOpen = false }
                        // No fill: a translucent scrim would sit on the live console, and Lite cannot afford one.
                        Box(Modifier.fillMaxSize().clickable(interactionSource = overlayClicks, indication = null) { guestsOpen = false })
                        ConsoleSwitcher(state, guest, switcher, { picked -> guestsOpen = false; onSwitchGuest(picked) },
                            Modifier.align(androidx.compose.ui.Alignment.CenterStart).fillMaxHeight().blockPointerPassThrough())
                    }
                }
            }
            "Actions" -> {
                blockedReason?.let { Text(it, color = Tech.Amber, fontFamily = Tech.Mono, fontSize = 12.sp) }
                SectionLabel("POWER")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(PowerAction.Start,PowerAction.Shutdown,PowerAction.Reboot,PowerAction.HardStop,PowerAction.Reset,PowerAction.Suspend,PowerAction.Resume).forEach { action ->
                        val enabled = ready && actionMatches(guest,action)
                        val tint = when(action) { PowerAction.HardStop -> Tech.Red; PowerAction.Reset -> Tech.Amber; else -> Tech.Edge }
                        val hint = when {
                            lockName != null -> "Locked: $lockName"
                            unresolved -> "Check Activity first."
                            else -> when (action) { PowerAction.Start -> "Power on"; PowerAction.Shutdown -> "Guest shutdown"; PowerAction.Reboot -> "Guest restart"; PowerAction.HardStop -> "Immediate power off"; PowerAction.Reset -> "Immediate reset"; PowerAction.Suspend -> "Pause execution"; else -> "Continue execution" }
                        }
                        ActionTile(powerActionTitle(action), hint, tint, enabled, { ActionGlyph(action, enabled) }) { onAction(action,null) }
                    }
                }
                state.powerNotice?.let { Text(it,color=Tech.Amber) }
                if (guest.kind == "VM") {
                    SectionLabel("CONSOLE")
                    ActionTile("Console", "View the VM display", Tech.Green, true, { ConsoleGlyph(true) }) { tab = "Console" }
                }
            }
        }
    }
    // The page scrolls as a whole (the console tab has its own surface): show it, since in a short window the header
    // and tabs can fill the first view.
    if (tab != "Console" && !consoleImmersive) VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(androidx.compose.ui.Alignment.CenterEnd).fillMaxHeight(), style = visibleScrollbarStyle())
}
}

/**
 * One action as a single bordered tile: the whole tile is the button. Neutral tiles light amber on hover;
 * tinted tiles (hard stop red, reset amber, console green) keep their colour and fill faintly on hover.
 */
@Composable private fun ActionTile(label: String, hint: String, tint: androidx.compose.ui.graphics.Color, enabled: Boolean, glyph: @Composable () -> Unit, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val accent = if (tint == Tech.Edge) Tech.Amber else tint
    val edge = when { !enabled -> tint.copy(alpha = .3f); hovered -> accent; tint == Tech.Edge -> Tech.Edge; else -> tint.copy(alpha = .85f) }
    Column(Modifier.width(154.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(34.dp).clip(Tech.Control)
                .background(if (enabled && hovered) accent.copy(alpha = .12f) else androidx.compose.ui.graphics.Color.Transparent)
                .border(1.dp, edge, Tech.Control)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
                .padding(horizontal = 10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            glyph()
            Text(label.uppercase(), fontFamily = Tech.Mono, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = .5.sp,
                color = if (enabled) Tech.Ink else Tech.Muted.copy(alpha = .5f), maxLines = 1)
        }
        Text(hint, color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 2)
    }
}

@Composable private fun ActionGlyph(action: PowerAction, enabled: Boolean) {
    Canvas(Modifier.size(14.dp)) {
        val c = (if(action == PowerAction.HardStop) Tech.Red else Tech.Amber).copy(alpha = if(enabled) 1f else .3f)
        val w = size.width; val h = size.height
        when(action) {
            PowerAction.Start, PowerAction.Resume -> {
                val path = androidx.compose.ui.graphics.Path().apply { moveTo(w*.25f,h*.15f); lineTo(w*.85f,h*.5f); lineTo(w*.25f,h*.85f); close() }
                drawPath(path,c)
            }
            PowerAction.HardStop -> drawRect(c, Offset(w*.2f,h*.2f), androidx.compose.ui.geometry.Size(w*.6f,h*.6f))
            PowerAction.Suspend -> { drawLine(c,Offset(w*.3f,h*.2f),Offset(w*.3f,h*.8f),3f); drawLine(c,Offset(w*.7f,h*.2f),Offset(w*.7f,h*.8f),3f) }
            PowerAction.Shutdown -> { drawArc(c,-45f,270f,false,Offset(w*.15f,h*.15f),androidx.compose.ui.geometry.Size(w*.7f,h*.7f),style=androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx())); drawLine(c,Offset(w*.5f,0f),Offset(w*.5f,h*.5f),1.5.dp.toPx()) }
            else -> { drawArc(c,30f,290f,false,Offset(w*.15f,h*.15f),androidx.compose.ui.geometry.Size(w*.7f,h*.7f),style=androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx())); drawLine(c,Offset(w*.8f,h*.15f),Offset(w*.85f,h*.45f),2f) }
        }
    }
}

internal fun hardwareFieldLabel(key: String) = when (key) {
    "ostype" -> "Operating system"
    "onboot" -> "Start at boot"
    "balloon" -> "Minimum memory"
    else -> key
}.uppercase()

internal fun hardwareValueText(key: String, value: String) = if (key == "ostype") osTypeName(value) else formatHardwareValue(key, value)

/** Proxmox VE `ostype` codes as names. Unknown codes stay raw. */
internal fun osTypeName(code: String): String = when (code) {
    "l26" -> "Linux 2.6 – 6.x"; "l24" -> "Linux 2.4"; "other" -> "Other"; "solaris" -> "Solaris"
    "win11" -> "Windows 11 / 2022 / 2025"; "win10" -> "Windows 10 / 2016 / 2019"; "win8" -> "Windows 8 / 2012"; "win7" -> "Windows 7 / 2008 R2"
    "wvista" -> "Windows Vista / 2008"; "w2k8" -> "Windows 2008"; "w2k3" -> "Windows 2003"; "w2k" -> "Windows 2000"; "wxp" -> "Windows XP"
    "debian" -> "Debian"; "ubuntu" -> "Ubuntu"; "centos" -> "CentOS"; "fedora" -> "Fedora"; "opensuse" -> "openSUSE"; "archlinux" -> "Arch Linux"
    "alpine" -> "Alpine"; "gentoo" -> "Gentoo"; "nixos" -> "NixOS"; "devuan" -> "Devuan"; "unmanaged" -> "Unmanaged"
    else -> code
}

/** The NIC details beyond model, bridge and VLAN: a container's address, a rate limit, a non-default MTU. */
internal fun nicExtras(p: Map<String, String>): String = listOfNotNull(
    p["ip"]?.let { if (it == "dhcp") "IP dhcp" else "IP $it" },
    p["ip6"]?.takeIf { it != "auto" && it != "manual" }?.let { "IPv6 $it" },
    p["rate"]?.let { "≤ $it MB/s" },
    p["mtu"]?.takeIf { it != "1500" && it != "1" }?.let { "MTU $it" },
).joinToString("  ·  ")

/**
 * Pin a service for this guest. The form (PinServiceForm.kt) guesses the address from the guest's notes, name and tags,
 * and can look at the guest's ports on request. Not offered with sample data or in browser mode.
 */
@Composable private fun PinServiceDialog(guest: Guest, report: GuestInspection?, demo: Boolean, onDismiss: () -> Unit, onPin: (String, String) -> String?) {
    val hosts = report?.addresses?.value.orEmpty().mapNotNull(::reportedHost).distinct()
    val hints = remember(guest.name, guest.tags, report?.hardware?.value) { GuestHints(guest.name, guest.tags, report?.hardware?.value?.fields?.get("description")) }
    val web = LocalAppHost.current.web
    key(guest.resource) {
        ServicePinForm(hints, hosts, canProbe = !demo && !web, onDismiss = onDismiss, onSave = onPin,
            probeUnavailable = if (demo) "Find services and Test are not offered with sample data." else "Find services and Test need the desktop app.")
    }
}
