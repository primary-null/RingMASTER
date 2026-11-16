@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.*
import androidx.compose.foundation.relocation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import local.ringleader.core.*
import kotlinx.coroutines.*

/**
 * The line over the results. A network that was only cut down (the /24 around this computer, of a /22) never ends as "Complete": the
 * line says what the sweep was a part of.
 */
internal fun scanStatusLine(p: NetworkProgress, running: Boolean, seconds: Long, proxmox: Int = p.hits.count { it.proxmox }): String {
    val cut = p.plan.networks.filter { it.cidr != null && it.realCidr != null && it.realCidr != it.cidr }.mapNotNull { it.realCidr }.distinct()
    val state = when {
        p.finished && cut.isEmpty() -> "Complete"
        p.finished -> "Finished \u00b7 only part of ${cut.joinToString(" and ")}"
        running -> when (p.phase) {
            ScanPhase.Sweeping -> "Scanning"
            ScanPhase.Settling -> "Waiting for the network's neighbour list"
            ScanPhase.Probing -> "Reading the ${p.alive} ${if (p.alive == 1) "device" else "devices"} that answered"
            ScanPhase.Done -> "Finishing"
        }
        else -> "Stopped"
    }
    // A host answers when a port opened, a reset came back or this computer's own neighbour table lists it; most offer no service a scan knows.
    val answering = maxOf(p.findings.size, p.hits.map { it.ip }.distinct().size)
    return "${p.scanned}/${p.total} addresses \u00b7 $answering ${if (answering == 1) "host" else "hosts"} answering \u00b7 $proxmox Proxmox \u00b7 ${seconds}s \u00b7 $state"
}

private val ScanKind.label get() = when (this) {
    ScanKind.PVE -> "Proxmox VE"; ScanKind.PBS -> "Proxmox Backup Server"; ScanKind.PDM -> "Proxmox Datacenter Manager"
    ScanKind.PIHOLE -> "Pi-hole"; ScanKind.ADGUARD -> "AdGuard Home"; ScanKind.HOME_ASSISTANT -> "Home Assistant"; ScanKind.WEB -> "Web page"
}

/** CN from an RFC 2253 subject. Parsed by hand: the packaged jlink runtime has no java.naming module. */
internal fun subjectCommonName(subject: String?): String? {
    if (subject.isNullOrBlank()) return null
    val parts = mutableListOf<String>(); val current = StringBuilder(); var escaped = false
    for (c in subject) when {
        escaped -> { current.append(c); escaped = false }
        c == '\\' -> escaped = true
        c == ',' || c == '+' -> { parts += current.toString(); current.clear() }
        else -> current.append(c)
    }
    parts += current.toString()
    return parts.map { it.trim() }.firstOrNull { it.startsWith("CN=", true) }?.substring(3)?.trim()?.takeIf { it.isNotBlank() }
}
/** Card title: certificate CN, else the page title before " - Product", else the address. */
internal fun scanHostname(hit: ScanHit): String = subjectCommonName(hit.subject)
    ?: hit.title?.substringBefore(" - ")?.trim()?.takeIf { it.isNotBlank() } ?: hit.ip
private val genericPageTitle = Regex("(?i)^([0-9]{3} )?(moved temporarily|moved permanently|found|redirect(ing)?|see other|temporary redirect|permanent redirect|index of /?|welcome to nginx!?|apache2 .*default page.*|it works!?)$")
/** Service row name: a real page or certificate name, else the service kind ("Web page"), never just the address. */
internal fun serviceName(hit: ScanHit): String = scanHostname(hit).takeUnless { it == hit.ip || genericPageTitle.matches(it) } ?: hit.kind.label
/** "ens18 ens18" -> "ens18"; Linux reports the same interface name twice. */
internal fun scanInterfaceLabel(name: String) = name.split(' ').filter { it.isNotBlank() }.distinct().joinToString(" ")
internal val ScanKind.short get() = when (this) { ScanKind.PVE -> "PVE"; ScanKind.PBS -> "PBS"; ScanKind.PDM -> "PDM"; ScanKind.PIHOLE, ScanKind.ADGUARD -> "DNS"; ScanKind.HOME_ASSISTANT -> "HA"; ScanKind.WEB -> "WEB" }
internal val ScanKind.tint get() = when (this) {
    ScanKind.PVE -> Tech.Amber; ScanKind.PDM -> Tech.Green; ScanKind.PBS -> if (Tech.Light) androidx.compose.ui.graphics.Color(0xFF185CA6) else androidx.compose.ui.graphics.Color(0xFF6BB8FF)
    ScanKind.PIHOLE, ScanKind.ADGUARD -> if (Tech.Light) androidx.compose.ui.graphics.Color(0xFF7540A0) else androidx.compose.ui.graphics.Color(0xFFB39DDB)
    ScanKind.HOME_ASSISTANT -> if (Tech.Light) androidx.compose.ui.graphics.Color(0xFF007379) else androidx.compose.ui.graphics.Color(0xFF4FC3F7); ScanKind.WEB -> Tech.Muted
}
internal fun scanCounts(hits: List<ScanHit>) = listOf(hits.size, hits.count { it.proxmox }, hits.count { !it.proxmox })
/** The counts on the filter chips: a machine is counted once however many addresses and services answered. */
internal fun scanDeviceCounts(devices: List<ScanDevice>, hits: List<ScanHit>) = listOf(devices.size + hits.count { !it.proxmox }, devices.size, hits.count { !it.proxmox })
internal fun sortedServices(hits: List<ScanHit>, address: Boolean) = hits.filter { !it.proxmox }.sortedBy { if(address) it.ip.split('.').joinToString(".") { part -> part.padStart(3,'0') } + ":" + it.port.toString().padStart(5,'0') else scanHostname(it).lowercase() }

/** The scan's help text. The port list matches [LAN_SCAN_PORTS]. */
internal val SCAN_HELP = "Manual discovery: Proxmox ports 8006, 8007 and 8443, web ports 80 and 443, AdGuard Home setup on 3000 and Home Assistant on 8123. " +
    "Only answers on 8006, 8007 and 8443 are named as Proxmox. No credentials or tokens are sent. Scan certificates are observations, not trust decisions."

/** The line under a scan card's fingerprint: what was observed about the certificate. Nothing here is verified. */
internal fun certificateNote(hit: ScanHit, now: Long = System.currentTimeMillis()): String {
    if (hit.fingerprint == null) return "Plain or unreadable TLS"
    val parts = mutableListOf("Unverified certificate")
    hit.notAfter?.let { end ->
        val day = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.format(java.time.Instant.ofEpochMilli(end).atOffset(java.time.ZoneOffset.UTC))
        parts += if (end < now) "expired $day" else "expires $day"
    }
    hit.issuer?.takeIf { it.isNotBlank() }?.let { issuer ->
        parts += if (issuer == hit.subject) "self-signed" else "issuer " + (subjectCommonName(issuer) ?: issuer.take(48))
    }
    return parts.joinToString(" · ")
}

/** The width of a network field: 220 dp, wider with larger text so its example still fits. */
@Composable internal fun scanFieldWidth() = (220f * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f, 2f)).dp

/** What a key does on the Scan page: F5, or Enter where nothing else took it (the subnet field does not), starts a scan. */
internal fun startsScan(event: KeyEvent): Boolean =
    event.type == KeyEventType.KeyDown && !event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed && !event.isMetaPressed &&
        (event.key == Key.F5 || event.key == Key.Enter || event.key == Key.NumPadEnter)

/** The edge of a network chip: in the text colour in high contrast (the page's other edges are), amber when the network was swept, quiet when it was not. */
internal fun scanChipEdge(swept: Boolean, highContrast: Boolean): Color = when { highContrast -> Tech.Ink; swept -> Tech.Amber.copy(alpha = .5f); else -> Tech.Edge }

/**
 * What the page says after a scan was cancelled, stopped by an error, or not started because nothing is ticked: the fact first, then
 * what to do. Results are mentioned only when there are some, and only then are they said to stay.
 */
internal fun scanNoticeLines(notice: ScanNotice, message: String, p: NetworkProgress?, seconds: Long): List<String> = when (notice) {
    ScanNotice.Cancelled -> listOf(
        if (p != null && p.scanned > 0) "Cancelled after ${seconds}s: ${"%,d".format(java.util.Locale.ROOT, p.scanned)} of ${addressCountText(p.total)} were checked." + if (p.hits.isNotEmpty()) " What was found stays below." else ""
        else "Cancelled before anything was checked.",
        "Start scan runs it again.")
    ScanNotice.Failed -> buildList {
        add(message.ifBlank { "The scan stopped." })
        if (p != null && p.hits.isNotEmpty()) add("What was found before it stopped stays below.")
        add("Start scan tries again. If it keeps stopping, check that this computer is on a network; Under the Hood has the details.")
    }
    ScanNotice.NothingTicked -> listOf(message)
    ScanNotice.None -> emptyList()
}

/** [opener] hands a link to the system browser (a recorder in tests, so a test never starts one). */
@Composable internal fun ScanScreen(session: ScanSession, origins: List<String>, initialScroll: Int = 0, opener: (String) -> BrowserLaunch = ::openInBrowser, known: ScanKnown = ScanKnown.NONE, onUse: (ScanHit) -> Unit) {
    var filter by remember { mutableStateOf(0) }
    var addressSort by remember { mutableStateOf(false) }
    // Hovering only highlights: it never scrolls and never changes the layout. (It used to insert a line above the grid
    // and scroll the page, so the content moved under the pointer and the page jumped with every mouse move.)
    var hovered by remember { mutableStateOf<String?>(null) }
    // A click on a grid dot asks for its card or row to be brought into view. The counter lets the same dot be clicked again.
    var picked by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var info by remember { mutableStateOf(false) }
    var why by remember { mutableStateOf(false) }
    var networksOpen by remember { mutableStateOf(false) }
    // A click on the live ticker can ask for the scan's progress line: it is brought into view and lit for a moment.
    val tickerFocus = LocalPageFocus.current
    val askedScan = tickerFocus?.take { it is TickerTarget.Scan }
    var scanLit by remember { mutableStateOf(0L) }
    LaunchedEffect(askedScan?.token) { askedScan?.let { scanLit = it.token; tickerFocus.consume(it) } }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val web = LocalAppHost.current.web
    // Open and Copy for every address on the page share one note. The opener and clipboard are read at the moment of the
    // click: the holder outlives recompositions, so it must not keep the first ones it saw.
    val currentOpener by rememberUpdatedState(opener)
    val currentClipboard by rememberUpdatedState(clipboard)
    val scope = rememberCoroutineScope()
    val links = remember(scope) { ScanLinkActions(scope, { currentOpener(it) }, { currentClipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) }) }
    // A new scan starts without the last one's note.
    LaunchedEffect(session.running) { if (session.running) links.clear() }
    // What the window knows about servers feeds the plan. The saved origins alone are enough when the window passes nothing more.
    val knownNow = if (known == ScanKnown.NONE) ScanKnown(origins = origins) else known
    SideEffect { session.known = knownNow }
    // The plan is read once when the page opens with nothing scanned yet; a finished scan keeps its page, and Start reads again when the last read is old.
    LaunchedEffect(Unit) { if (session.progress == null && !session.running) session.refresh() }
    // The page takes the keyboard when it opens, so F5 and Enter reach it; Tab goes on from there.
    val pageFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { pageFocus.requestFocus() } }
    Column(Modifier.fillMaxSize().onKeyEvent { if (startsScan(it) && session.canStart) { session.start(origins); true } else false }
        .focusRequester(pageFocus).focusable().verticalScroll(rememberScrollState(initialScroll)), verticalArrangement = Arrangement.spacedBy(Tech.Space12)) {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text("Discover devices on your private networks", fontSize=12.sp,color=Tech.Muted)
            HelpTip(SCAN_HELP) { MiniAction(if (info) "Hide trust info" else "Scan & trust info", primary = info) { info = !info } }
        }
        if(info) Text(SCAN_HELP,color=Tech.Muted)
        // The buttons sit level with the field's box, not with its label.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tech.Space8), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = androidx.compose.ui.Alignment.Bottom) {
            TechAction("Start scan", enabled = session.canStart) { session.start(origins) }
            TechAction("Cancel", enabled = session.running) { session.cancel() }
            TechField(session.extraSubnet, { session.extraSubnet = it }, "Additional subnet", placeholder = "e.g. 192.168.20.0/24", modifier = Modifier.width(scanFieldWidth()).identity())
            HelpTip("Type the address of a server you expected to find. The page says which network would have contained it, or why none did. Nothing is sent.") {
                MiniAction("Why isn't it here?", primary = why) { why = !why }
            }
        }
        if (session.extraNote.isNotEmpty()) Text(session.extraNote, Modifier.identity(), color = Tech.Amber, fontSize = 12.sp)
        if (why) ScanWhyPanel(session)
        // The plan panel shown before a scan already says there is nothing to sweep; the notice would say it twice.
        if (session.notice != ScanNotice.None && !(session.notice == ScanNotice.NothingTicked && session.progress == null)) ScanNoticePanel(session)
        if (session.progress == null) ScanPlanPanel(session, editable = !session.running)
        else {
            // The plan of the next scan stays one click away, without taking the results' place.
            MiniAction(if (networksOpen) "Hide networks" else "Change networks") { networksOpen = !networksOpen }
            if (networksOpen) ScanPlanPanel(session, editable = !session.running)
        }
        session.progress?.let { p ->
            val saved = session.saved
            val attached = remember(p.plan) { p.plan.networks.filter { it.onLink }.mapNotNull { it.cidr } }
            val devices = remember(p.hits, saved, attached) { ScanDevices.group(p.hits, saved, attached) }
            // One network: its grid stays on top. Several: the results come first and the grids follow them, so what was found is not pushed off the screen.
            val several = p.plan.networks.count { it.cidr != null } > 1
            val grid = @Composable {
                ScanGrid(p,session.running,hovered, onHover = { hovered=it }) { origin ->
                    p.hits.firstOrNull { it.origin == origin }?.let { hit ->
                        if ((hit.proxmox && filter == 2) || (!hit.proxmox && filter == 1)) filter = 0
                        picked = origin to ((picked?.second ?: 0) + 1)
                    }
                }
            }
            if (!several) grid()
            Column(Modifier.focusGlow(scanLit, scanLit != 0L), verticalArrangement = Arrangement.spacedBy(Tech.Space12)) {
                Text(scanStatusLine(p, session.running, session.durationSeconds, devices.size), style=MaterialTheme.typography.bodySmall)
                p.notes.forEach { Text(it, color = Tech.Amber, fontSize = 12.sp) }
                TechProgress(fraction = if (p.total == 0) 0f else p.scanned.toFloat() / p.total)
            }
            ScanNetworkChips(p, session.running)
            Row(horizontalArrangement=Arrangement.spacedBy(Tech.Space8)) { listOf("All","Proxmox","Services").forEachIndexed { i,label -> MiniAction("$label ${scanDeviceCounts(devices, p.hits)[i]}",primary=filter==i) {filter=i} } }
            if (p.hits.isEmpty()) ScanEmptyPanel(p, session.running, session.durationSeconds, onWhy = { why = true }, onNetworks = { networksOpen = true })
            if(filter != 2) {
                BoxWithConstraints {
                    val columns=if(maxWidth >= 1300.dp) 3 else if(maxWidth >= 1000.dp) 2 else 1
                    Column(verticalArrangement=Arrangement.spacedBy(Tech.Space12)) {
                        devices.chunked(columns).forEach { row ->
                            Row(horizontalArrangement=Arrangement.spacedBy(Tech.Space12)) {
                                row.forEach { device ->
                                    val requester=remember(device.primary.origin) { BringIntoViewRequester() }
                                    LaunchedEffect(picked) { if(picked?.first?.let { device.owns(it) } == true) requester.bringIntoView() }
                                    ScanDeviceCard(device, hovered?.let { device.owns(it) } == true, Modifier.weight(1f).bringIntoViewRequester(requester)
                                        .onPointerEvent(PointerEventType.Enter) { hovered=device.primary.origin }.onPointerEvent(PointerEventType.Exit) { hovered=null },
                                        links, onUse=onUse)
                                }
                                repeat(columns-row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
            val services = sortedServices(p.hits,addressSort)
            if(filter != 1 && services.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { SectionLabel("Services"); MiniAction("Sort: ${if(addressSort) "Address" else "Name"}") { addressSort = !addressSort } }
                TechPanel(Modifier.fillMaxWidth()) {
                    services.forEachIndexed { i, hit ->
                        if (i > 0) HorizontalDivider(color=Tech.Edge.copy(alpha=.6f))
                        val requester=remember(hit.origin) { BringIntoViewRequester() }
                        LaunchedEffect(picked) { if(picked?.first==hit.origin) requester.bringIntoView() }
                        FlowRow(Modifier.fillMaxWidth().bringIntoViewRequester(requester).background(if (hovered==hit.origin) Tech.Raised else androidx.compose.ui.graphics.Color.Transparent)
                            .onPointerEvent(PointerEventType.Enter) { hovered=hit.origin }.onPointerEvent(PointerEventType.Exit) { hovered=null }
                            .heightIn(min=32.dp).padding(horizontal=4.dp, vertical=4.dp), itemVerticalAlignment=androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(Tech.Space12), verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            KindGlyph(hit.kind, 28.dp)
                            // A fixed width, so the status, address and actions line up from one row to the next. 260 keeps the
                            // actions on the row at the smallest window (about 685 dp of row beside the open rail).
                            Column(Modifier.width(260.dp)) {
                                val name = serviceName(hit)
                                Text(name, Modifier.identity(), fontSize=Tech.Body, maxLines=1, overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(if (name == hit.kind.label) "No page title" else hit.kind.label, fontSize=Tech.Label, color=Tech.Muted)
                            }
                            Text(if (hit.secure) "TLS" else "HTTP", fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace, fontSize=Tech.Label, color=if (hit.secure) Tech.Green else Tech.Muted, modifier=Modifier.width(40.dp))
                            ScanAddress(hit, links, web)
                            // The browser belongs to the computer the window is on, so a window shown in a browser has no Open.
                            if (!web) HelpTip("Open ${hit.origin} in your browser", identity=true) { MiniAction("Open") { links.open(hit.origin) } }
                            HelpTip("Copy ${hit.origin}", identity=true) { MiniAction("Copy") { links.copy(hit.origin) } }
                            ScanLinkNoteText(links.noteFor(hit.origin))
                        }
                    }
                }
            }
            if (several) grid()
        }
    }
}

/** The result of a scan that ended other than by finishing, or did not start: what happened, then what to do. Never claims results that are not there. */
@Composable private fun ScanNoticePanel(session: ScanSession) {
    val lines = scanNoticeLines(session.notice, session.message, session.progress, session.durationSeconds)
    TechPanel(Modifier.fillMaxWidth(), rail = if (session.notice == ScanNotice.Failed) Tech.Red else Tech.Amber) {
        lines.forEachIndexed { i, line -> Text(line, color = if (i == 0) Tech.Ink else Tech.Muted, fontSize = if (i == 0) 13.sp else 12.sp) }
    }
}

/** A scan that found nothing says what was checked and what to try, instead of "No services found". */
@Composable private fun ScanEmptyPanel(p: NetworkProgress, running: Boolean, seconds: Long, onWhy: () -> Unit, onNetworks: () -> Unit) {
    TechPanel(Modifier.fillMaxWidth()) {
        if (!p.finished && running) {
            Text("Waiting for devices")
            Text("No device has answered yet.", color = Tech.Muted, fontSize = 12.sp)
            return@TechPanel
        }
        val networks = p.plan.networks.count { it.cidr != null }
        val answered = maxOf(p.findings.size, p.alive)
        Text(if (p.finished) "Nothing to show" else "The scan was stopped before a service answered")
        Text("${addressCountText(p.scanned)} on $networks ${if (networks == 1) "network" else "networks"} were checked in ${seconds}s. " +
            if (answered > 0) "$answered ${if (answered == 1) "device" else "devices"} answered the network, and none offers a service RingMASTER knows." else "No device answered.", color = Tech.Muted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Text("What to check", fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        listOf("Is this computer on the same network as the servers? A different Wi-Fi or a VPN that is down hides them.",
            "Is the server on another network? Tick it in Change networks, or type it in Additional subnet.",
            "Does it listen on another port? A scan asks 8006, 8007 and 8443, then 80, 443, 3000 and 8123 on a device that answered.")
            .forEach { Text("• $it", color = Tech.Muted, fontSize = 12.sp) }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tech.Space8), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniAction("Why isn't it here?", primary = true, onClick = onWhy)
            MiniAction("Change networks", onClick = onNetworks)
        }
    }
}

/** The networks of the last scan as chips: where each came from, the real network when only part of it was swept, and how far it is. */
@Composable private fun ScanNetworkChips(p: NetworkProgress, running: Boolean) {
    FlowRow(horizontalArrangement=Arrangement.spacedBy(Tech.Space8), verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("NETWORKS", fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace, fontSize=Tech.Label, color=Tech.Muted, modifier=Modifier.align(androidx.compose.ui.Alignment.CenterVertically))
        p.plan.networks.forEach { n ->
            val swept = n.cidr != null
            val tally = p.tallies.firstOrNull { it.id == (n.cidr ?: n.source.name) }
            val cut = n.realCidr != null && n.realCidr != n.cidr
            val tooltip = when {
                !swept -> "${n.source.ip}/${n.source.prefix} not swept: ${n.reason.orEmpty()}"
                cut -> "Swept ${n.cidr}, the part of ${n.realCidr} around ${n.source.ip}. The rest of that network is not swept."
                else -> "Swept ${n.cidr}"
            }
            val done = tally != null && (tally.done || p.finished)
            val progress = when {
                !swept || tally == null -> null
                done -> addressCountText(tally.total) + if (tally.answered > 0) " · ${tally.answered} answered" else ""
                else -> "${tally.scanned}/${tally.total}"
            }
            HelpTip(tooltip, identity = true) {
                Column(Modifier.border(1.dp, scanChipEdge(swept, Appearance.palette.highContrast), Tech.Control).semantics(mergeDescendants = true) { contentDescription = if (Appearance.showcase) "Network" else tooltip }) {
                    Row(Modifier.padding(horizontal=10.dp, vertical=4.dp), verticalAlignment=androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(scanInterfaceLabel(n.source.name), Modifier.identity(), fontSize=Tech.Label, color=if (swept) Tech.Ink else Tech.Muted)
                        Text(if (swept) (if (cut) "${n.cidr} of ${n.realCidr}" else n.cidr.orEmpty()) else n.reason.orEmpty().lowercase(), Modifier.identity(), fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace, fontSize=Tech.Label, color=Tech.Muted)
                        progress?.let { Text(it, fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace, fontSize=Tech.Label, color=Tech.Muted) }
                    }
                    if (swept && tally != null) Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                        drawRect(Tech.Edge)
                        drawRect(Tech.Amber, size = androidx.compose.ui.geometry.Size(size.width * (if (done) 1f else if (tally.total == 0) 0f else tally.scanned.toFloat() / tally.total), size.height))
                    }
                }
            }
        }
    }
}

@Composable private fun KindGlyph(kind: ScanKind, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).background(kind.tint.copy(alpha=.14f), Tech.Control).border(1.dp, kind.tint.copy(alpha=.6f), Tech.Control),
        contentAlignment=androidx.compose.ui.Alignment.Center) {
        Text(kind.short, fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace, fontSize=if (size < 36.dp) 9.sp else 11.sp,
            fontWeight=androidx.compose.ui.text.font.FontWeight.Bold, color=kind.tint)
    }
}

/** The desk can connect to Proxmox VE and Datacenter Manager; other hits are listed for reference only. */
internal fun scanConnectable(hit: ScanHit) = hit.kind == ScanKind.PVE || hit.kind == ScanKind.PDM


/** Width reserved for an address on a service row: the longest one (`255.255.255.255:65535`) fits, and the actions after it line up. */
private val SCAN_ADDRESS_MIN = 148.dp

/**
 * A scanned address on a service row. On the desktop it is a link: Enter, Space or a click opens the page in the default
 * browser, and it is underlined while the pointer or the keyboard is on it. In a browser-hosted window it is plain text.
 */
@Composable private fun ScanAddress(hit: ScanHit, links: ScanLinkActions, web: Boolean) {
    val address = "${hit.ip}:${hit.port}"
    val mono = androidx.compose.ui.text.font.FontFamily.Monospace
    val ellipsis = androidx.compose.ui.text.style.TextOverflow.Ellipsis
    // The reserved width grows with the text size, so the longest address still fits and the actions stay in line at 150 %.
    val reserved = SCAN_ADDRESS_MIN * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
    if (web) {
        HelpTip(address, identity=true) { Text(address, fontFamily=mono, fontSize=Tech.Label, color=Tech.Ink, modifier=Modifier.widthIn(min=reserved).identity(), maxLines=1, overflow=ellipsis) }
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val lit = hovered || focused
    HelpTip("Open in your browser") {
        Text(address, fontFamily=mono, fontSize=Tech.Label, color=if (lit) Tech.Amber else Tech.Ink, textDecoration=if (lit) TextDecoration.Underline else null, maxLines=1, overflow=ellipsis,
            modifier=Modifier.widthIn(min=reserved).focusRing(focused, gap=2.dp).hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource=interaction, indication=null, role=Role.Button, onClickLabel="Open in the browser") { links.open(hit.origin) }
                .semantics { contentDescription = openLinkDescription(hit.origin, Appearance.showcase) }
                .identity())
    }
}

/** What an Open or Copy left behind, as a short line after the actions. Announced politely, and gone with the next action. */
@Composable private fun ScanLinkNoteText(text: String?) {
    if (text == null) return
    Text(text, fontSize=11.sp, color=Tech.Muted, modifier=Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

/**
 * Compact device card: identity row, certificate row, then the actions (Use, Web UI, Copy address, Copy fingerprint). One card is one
 * machine: when it answers on several addresses the card says where else, and Use offers the choice. The actions are the first
 * (preferred) address's; [onUse] gets the address that was chosen.
 */
@Composable private fun ScanDeviceCard(device: ScanDevice, highlighted: Boolean, modifier: Modifier, links: ScanLinkActions, onUse: (ScanHit) -> Unit) {
    val hit = device.primary
    var expanded by remember(hit.origin) { mutableStateOf(false) }
    var choosing by remember(hit.origin) { mutableStateOf(false) }
    val web = LocalAppHost.current.web
    TechPanel(modifier, padding=12.dp, rail=hit.kind.tint, selected=highlighted) {
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(Tech.Space12)) {
            KindGlyph(hit.kind, 40.dp)
            Column(Modifier.weight(1f)) {
                Text(scanHostname(hit), Modifier.identity(), fontSize=Tech.Section, fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines=1, overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text("${hit.ip}:${hit.port}  ·  ${hit.kind.label}", Modifier.identity(), fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace, fontSize=Tech.Label, color=Tech.Muted, maxLines=1, overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            if (device.saved.saved) HelpTip(savedTagHelp(device.saved), identity=true) { StatusTag("Saved", true) }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color=Tech.Edge.copy(alpha=.6f))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(Tech.Space8)) {
            Column(Modifier.weight(1f)) {
                val fp = hit.fingerprint
                HelpTip(if (fp == null) "No certificate was observed on this port." else "Unverified. Compare with the server's own fingerprint (node > System > Certificates) before trusting it.") {
                    Text(if (fp == null) "No certificate" else "SHA-256  " + (if (expanded) fp else fp.take(23) + "…"),
                        Modifier.clickable(enabled=fp != null) { expanded = !expanded }.identity(),
                        fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace, fontSize=Tech.Label, color=Tech.Ink.dimmed(.85f))
                }
                Text(certificateNote(hit), Modifier.identity(), fontSize=11.sp, color=if ((hit.notAfter ?: Long.MAX_VALUE) < System.currentTimeMillis()) Tech.Amber else Tech.Muted,
                    maxLines=2, overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            // Only Proxmox VE and Datacenter Manager can be connected from the desk; the rest are for reference.
            if (scanConnectable(hit)) MiniAction("Use", primary=true) { onUse(hit) }
        }
        // The same machine on other addresses, and its other Proxmox services: where else it answers, never a second card.
        if (device.alsoAt.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Also reachable at ${device.alsoAt.joinToString(", ") { "${it.ip}:${it.port}" }}", Modifier.identity(), fontSize=11.sp, color=Tech.Muted)
            if (scanConnectable(hit)) {
                Text("Use connects to ${hit.ip}:${hit.port}.", Modifier.identity(), fontSize=11.sp, color=Tech.Muted)
                MiniAction(if (choosing) "Hide other addresses" else "Other address") { choosing = !choosing }
                if (choosing) FlowRow(horizontalArrangement=Arrangement.spacedBy(Tech.Space8), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    device.alsoAt.forEach { other -> Box(Modifier.identity()) { MiniAction("Use ${other.ip}:${other.port}") { onUse(other) } } }
                }
            }
        }
        if (device.others.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Also on this device: " + device.others.joinToString(", ") { "${it.kind.label} ${it.ip}:${it.port}" }, Modifier.identity(), fontSize=11.sp, color=Tech.Muted)
        }
        Spacer(Modifier.height(8.dp))
        // A row that wraps: the three actions fit one line at the narrowest three-column card, and fall to a second line at large text sizes.
        FlowRow(horizontalArrangement=Arrangement.spacedBy(Tech.Space8), verticalArrangement=Arrangement.spacedBy(6.dp), itemVerticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            // The server's own page, in the default browser. Not offered when the window is itself shown in a browser.
            if (!web) HelpTip("Open this page in your browser. It asks for its own login.") { MiniAction("Web UI") { links.open(hit.origin) } }
            MiniAction("Copy address") { links.copy(hit.origin) }
            MiniAction("Copy fingerprint", enabled=hit.fingerprint != null) { hit.fingerprint?.let { links.copy(hit.origin, it) } }
            ScanLinkNoteText(links.noteFor(hit.origin))
        }
        if (!scanConnectable(hit)) Text(if (hit.kind == ScanKind.PBS) "Found on the network. RingMASTER does not connect to Backup Server yet."
            else "Found on the network. RingMASTER does not manage ${hit.kind.label}.", fontSize=11.sp, color=Tech.Muted, modifier=Modifier.padding(top=6.dp))
    }
}

/** What the Saved tag says it is saved by. */
internal fun savedTagHelp(mark: SavedMark): String = when (mark.by) {
    SavedBy.Address -> "A saved connection uses this address."
    SavedBy.Pin -> "Its certificate is pinned: ${mark.name ?: "a saved connection"}."
    SavedBy.No -> ""
}

/**
 * Where addresses sit on the discovery grid: one 64 x 4 strip per swept network, in plan order, so two /24s never share dots.
 * An address outside every swept network falls back to its last octet in the first strip.
 */
internal class ScanGridLayout(plan: NetworkPlan) {
    private val networks: List<LongRange> = plan.networks.mapNotNull { it.cidr }.distinct().mapNotNull { cidr ->
        val base = ScanRules.ipv4(cidr.substringBefore('/')) ?: return@mapNotNull null
        val prefix = cidr.substringAfter('/').toIntOrNull()?.takeIf { it in 24..32 } ?: return@mapNotNull null
        base until base + (1L shl (32 - prefix))
    }
    val strips = maxOf(1, networks.size)
    val rows = strips * 4
    /** Column (0-63) and row of [ip], in grid units. */
    fun cell(ip: String): Pair<Int, Int> {
        val n = ScanRules.ipv4(ip)
        if (n != null) networks.forEachIndexed { i, range ->
            if (n in range) { val offset = (n - range.first).toInt().coerceIn(0, 255); return offset % 64 to i * 4 + offset / 64 }
        }
        val octet = ip.substringAfterLast('.').toIntOrNull()?.coerceIn(0, 255) ?: 0
        return octet % 64 to octet / 64
    }
    /** Cell numbers (row * 64 + column) in the order the scanner takes the addresses. */
    val order: IntArray = plan.addresses.map { cell(it).let { (col, row) -> row * 64 + col } }.toIntArray()
}
private val SINGLE_GRID = ScanGridLayout(NetworkPlan(emptyList()))
private const val DOT_FADE_MS = 150L

/** The hit whose dot is within [reach] pixels of [pos] on a grid canvas of [width] x [height], nearest first. */
internal fun gridHitAt(hits: List<ScanHit>, pos: Offset?, width: Float, height: Float, reach: Float, layout: ScanGridLayout = SINGLE_GRID): ScanHit? {
    if (pos == null || width <= 0f || height <= 0f) return null
    fun distance(hit: ScanHit): Float { val (col, row) = layout.cell(hit.ip); return kotlin.math.hypot((col + .5f) * width / 64 - pos.x, (row + .5f) * height / layout.rows - pos.y) }
    return hits.minByOrNull(::distance)?.takeIf { distance(it) <= reach }
}

@Composable private fun ScanGrid(p: NetworkProgress, running: Boolean, hovered: String?, onHover: (String?) -> Unit, onPick: (String) -> Unit) {
    val motion = LocalMotion.current
    val progress by animateFloatAsState(if(p.total == 0) 0f else p.scanned.toFloat()/p.total, if (motion.reduced) snap() else motion.productive(MotionDur.Standard))
    // Radar ping: every new hit fades in and sends out one ring (at most 12 alive, 6 on Lite, none under Reduced, one per 30 ms).
    // Hits already listed when this page opens are not announced again, and the frame loop ends with the last ring.
    val field = remember(motion.ringBudget) { PingField(motion.ringBudget) }
    val layout = remember(p.plan) { ScanGridLayout(p.plan) }
    val known = remember { HashSet<String>().also { set -> p.hits.forEach { set.add(it.origin) } } }
    val births = remember { HashMap<String, Long>() }
    var frameMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(p.hits.size, field) {
        if (p.hits.isEmpty()) known.clear()
        val fresh = p.hits.filter { known.add(it.origin) }
        if (fresh.isEmpty()) return@LaunchedEffect
        val kinds = ScanKind.entries
        val first = withFrameNanos { it } / 1_000_000L
        for (hit in fresh) {
            births[hit.origin] = first
            val (col, row) = layout.cell(hit.ip)
            field.enqueue((col + .5f) / 64f, (row + .5f) / layout.rows, kinds.indexOf(hit.kind))
        }
        var now = first
        while (isActive && (field.busy || now - first < DOT_FADE_MS)) {
            field.step(now); frameMs = now
            now = withFrameNanos { it } / 1_000_000L
        }
        frameMs = now
    }
    TechPanel(Modifier.fillMaxWidth(), padding=8.dp) {
        var canvasWidth by remember { mutableStateOf(1f) }
        var canvasHeight by remember { mutableStateOf(1f) }
        // The readout shares the heading's line and that line is always there: showing a name must never move the page.
        val readout = hovered?.let { origin -> p.hits.firstOrNull { it.origin==origin } }
        Row(Modifier.fillMaxWidth().heightIn(min=18.dp), verticalAlignment=androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            SectionLabel("Discovery grid",suffix=if (layout.strips > 1) "${layout.strips} networks · PVE / PDM / PBS / WEB" else "PVE / PDM / PBS / WEB")
            Text(readout?.let { "${scanHostname(it)} · ${it.ip}:${it.port}" } ?: if (p.hits.isEmpty()) "" else "Hover a dot to name it · click to show it below",
                Modifier.weight(1f).then(if (readout != null) Modifier.identity() else Modifier), fontFamily=Tech.Mono, fontSize=11.sp, color=if (readout != null) Tech.Ink else Tech.Muted,
                maxLines=1, overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign=androidx.compose.ui.text.style.TextAlign.End)
        }
        val hits by rememberUpdatedState(p.hits)
        val grid by rememberUpdatedState(layout)
        // One 64 x 4 strip per swept network, each as tall as the single grid was.
        Canvas(Modifier.fillMaxWidth().height(64.dp * layout.strips).onSizeChanged { canvasWidth=it.width.toFloat();canvasHeight=it.height.toFloat() }
            .pointerHoverIcon(if (readout != null) PointerIcon.Hand else PointerIcon.Default)
            .onPointerEvent(PointerEventType.Move) { event -> onHover(gridHitAt(hits, event.changes.firstOrNull()?.position, canvasWidth, canvasHeight, 12.dp.toPx(), grid)?.origin) }
            .onPointerEvent(PointerEventType.Exit) { onHover(null) }
            .pointerInput(Unit) { detectTapGestures(onTap = { pos -> gridHitAt(hits, pos, canvasWidth, canvasHeight, 12.dp.toPx(), grid)?.let { onPick(it.origin) } }) }) {
            val cols=64; val rows=layout.rows
            // Amber marks the addresses already taken, in the scanner's order.
            val swept = BooleanArray(cols*rows)
            val done = (progress * layout.order.size).toInt().coerceIn(0, layout.order.size)
            for (i in 0 until done) layout.order[i].takeIf { it in swept.indices }?.let { swept[it] = true }
            repeat(cols*rows) { n ->
                val x=(n%cols+.5f)*size.width/cols; val y=(n/cols+.5f)*size.height/rows
                drawCircle(if(swept[n]) Tech.Amber.copy(alpha=.6f) else Tech.Edge,1.dp.toPx(),Offset(x,y))
            }
            for (s in 1 until layout.strips) { val y = s*size.height/layout.strips; drawLine(Tech.Edge, Offset(0f,y), Offset(size.width,y), 1.dp.toPx()) }
            val clock = frameMs
            p.hits.forEach { hit ->
                val (col, row) = layout.cell(hit.ip)
                val color=hit.kind.tint
                val center=Offset((col+.5f)*size.width/cols,(row+.5f)*size.height/rows)
                val born = births[hit.origin]
                val fade = if (born == null) 1f else ((clock - born).toFloat() / DOT_FADE_MS).coerceIn(0f, 1f)
                // Sizes in dp, so the dots keep their size on high-density displays and at larger interface sizes.
                drawCircle(color.copy(alpha=fade),if(hovered==hit.origin) 5.dp.toPx() else 3.dp.toPx(),center); drawCircle(color.copy(alpha=(if(hovered==hit.origin) .7f else .3f)*fade),5.5.dp.toPx(),center,style=Stroke(1.dp.toPx()))
            }
            // One thin ring per new hit, growing from 0.85 to 2.5 times the dot while it fades (Polaris pulse), additive light.
            if (field.busy) {
                val kinds = ScanKind.entries
                val gain = motion.lightGain * lightScale()
                field.forEachAlive(clock) { x, y, kind, t ->
                    drawCircle(kinds[kind.coerceIn(0, kinds.lastIndex)].tint, 6.dp.toPx() * pingScale(t), Offset(x * size.width, y * size.height), alpha = pingAlpha(t) * gain, style = Stroke(1.dp.toPx()), blendMode = lightBlend())
                }
            }
            if(running) {
                val total = progress * layout.strips
                val strip = total.toInt().coerceIn(0, layout.strips - 1)
                val x = (total - strip).coerceIn(0f, 1f) * size.width; val top = strip * size.height / layout.strips
                drawLine(Tech.Amber,Offset(x,top),Offset(x,top + size.height/layout.strips),1.dp.toPx())
            }
        }
    }
}
