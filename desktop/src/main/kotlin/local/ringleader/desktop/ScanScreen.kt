@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
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
import androidx.compose.ui.input.pointer.*
import androidx.compose.foundation.relocation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import local.ringleader.core.*
import kotlinx.coroutines.*

/** Holds one manual scan for the window's lifetime, so results survive moving to Desk and back. */
class ScanSession(private val scope: CoroutineScope) {
    var progress by mutableStateOf<NetworkProgress?>(null); private set
    var running by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var durationSeconds by mutableStateOf(0L); private set
    private var job: Job? = null

    fun start(origins: List<String>) {
        if (running) return
        running = true; message = ""; progress = null; durationSeconds = 0
        val started = System.nanoTime()
        job = scope.launch {
            // The elapsed time counts up while the scan runs, not only when it ends.
            val clock = launch { while (true) { delay(1000); durationSeconds = (System.nanoTime()-started)/1_000_000_000 } }
            try { NetworkScanner().scan(origins = origins).collect { progress = it } }
            catch (e: CancellationException) { message = "Cancelled"; throw e }
            catch (e: Exception) { message = "Scan failed: ${e.javaClass.simpleName}" }
            finally { clock.cancel(); durationSeconds = (System.nanoTime()-started)/1_000_000_000; running = false }
        }
    }
    fun cancel() { job?.cancel() }
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
    ScanKind.PVE -> Tech.Amber; ScanKind.PDM -> Tech.Green; ScanKind.PBS -> androidx.compose.ui.graphics.Color(0xFF6BB8FF)
    ScanKind.PIHOLE, ScanKind.ADGUARD -> androidx.compose.ui.graphics.Color(0xFFB39DDB); ScanKind.HOME_ASSISTANT -> androidx.compose.ui.graphics.Color(0xFF4FC3F7); ScanKind.WEB -> Tech.Muted
}
internal fun scanCounts(hits: List<ScanHit>) = listOf(hits.size, hits.count { it.proxmox }, hits.count { !it.proxmox })
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

@Composable fun ScanScreen(session: ScanSession, origins: List<String>, initialScroll: Int = 0, onUse: (ScanHit) -> Unit) {
    var filter by remember { mutableStateOf(0) }
    var addressSort by remember { mutableStateOf(false) }
    // Hovering only highlights: it never scrolls and never changes the layout. (It used to insert a line above the grid
    // and scroll the page, so the content moved under the pointer and the page jumped with every mouse move.)
    var hovered by remember { mutableStateOf<String?>(null) }
    // A click on a grid dot asks for its card or row to be brought into view. The counter lets the same dot be clicked again.
    var picked by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var info by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(initialScroll)), verticalArrangement = Arrangement.spacedBy(Tech.Space12)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Discover devices on your private networks", modifier=Modifier.weight(1f),color=Tech.Muted)
            HelpTip(SCAN_HELP) { MiniAction(if (info) "Hide trust info" else "Scan & trust info", primary = info) { info = !info } }
        }
        if(info) Text(SCAN_HELP,color=Tech.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(Tech.Space8)) {
            TechAction("Start scan", enabled = !session.running) { session.start(origins) }
            TechAction("Cancel", enabled = session.running) { session.cancel() }
        }
        if (session.message.isNotEmpty()) TechPanel(Modifier.fillMaxWidth()) { Text(session.message); Text("Results remain available. Start a new scan when ready.",color=Tech.Muted) }
        if(session.progress == null) TechPanel(Modifier.fillMaxWidth()) { SectionLabel("Ready to discover"); Text("Start a scan to find Proxmox servers and other services.",color=Tech.Muted,fontFamily=Tech.Mono,fontSize=12.sp) }
        session.progress?.let { p ->
            ScanGrid(p,session.running,hovered, onHover = { hovered=it }) { origin ->
                p.hits.firstOrNull { it.origin == origin }?.let { hit ->
                    if ((hit.proxmox && filter == 2) || (!hit.proxmox && filter == 1)) filter = 0
                    picked = origin to ((picked?.second ?: 0) + 1)
                }
            }
            Text("${p.scanned}/${p.total} addresses · ${p.hits.map { it.ip }.distinct().size} hosts answering · ${p.hits.count { it.proxmox }} Proxmox · ${session.durationSeconds}s · ${if(p.finished) "Complete" else if(session.running) "Scanning" else "Stopped"}",style=MaterialTheme.typography.bodySmall)
            TechProgress(fraction = if (p.total == 0) 0f else p.scanned.toFloat() / p.total)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(Tech.Space8), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("NETWORKS", fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace, fontSize=Tech.Label, color=Tech.Muted, modifier=Modifier.align(androidx.compose.ui.Alignment.CenterVertically))
                p.plan.networks.forEach { n ->
                    val swept = n.cidr != null
                    val tooltip = when {
                        !swept -> "${n.source.ip}/${n.source.prefix} not swept: ${n.reason.orEmpty()}"
                        n.source.prefix < 24 -> "Swept ${n.cidr} from ${n.source.ip}/${n.source.prefix}; limited to the local /24."
                        else -> "Swept ${n.cidr} from ${n.source.ip}/${n.source.prefix}"
                    }
                    HelpTip(tooltip) {
                        Row(Modifier.border(1.dp, if (swept) Tech.Amber.copy(alpha=.5f) else Tech.Edge, Tech.Control).padding(horizontal=10.dp, vertical=4.dp),
                            verticalAlignment=androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(scanInterfaceLabel(n.source.name), fontSize=Tech.Label, color=if (swept) Tech.Ink else Tech.Muted)
                            Text(n.cidr ?: n.reason.orEmpty().lowercase(), fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace, fontSize=Tech.Label, color=Tech.Muted)
                        }
                    }
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(Tech.Space8)) { listOf("All","Proxmox","Services").forEachIndexed { i,label -> MiniAction("$label ${scanCounts(p.hits)[i]}",primary=filter==i) {filter=i} } }
            if(p.hits.isEmpty()) TechPanel(Modifier.fillMaxWidth()) { Text(if(p.finished) "No services found" else "Waiting for devices"); Text("Only answering services on the listed ports appear here.",color=Tech.Muted) }
            if(filter != 2) {
                BoxWithConstraints {
                    val columns=if(maxWidth >= 1300.dp) 3 else if(maxWidth >= 1000.dp) 2 else 1
                    Column(verticalArrangement=Arrangement.spacedBy(Tech.Space12)) {
                        p.hits.filter { it.proxmox }.chunked(columns).forEach { row ->
                            Row(horizontalArrangement=Arrangement.spacedBy(Tech.Space12)) {
                                row.forEach { hit ->
                                    val requester=remember { BringIntoViewRequester() }
                                    LaunchedEffect(picked) { if(picked?.first==hit.origin) requester.bringIntoView() }
                                    ScanDeviceCard(hit, hovered==hit.origin, Modifier.weight(1f).bringIntoViewRequester(requester)
                                        .onPointerEvent(PointerEventType.Enter) { hovered=hit.origin }.onPointerEvent(PointerEventType.Exit) { hovered=null },
                                        onUse={ onUse(hit) }, onCopy={ hit.fingerprint?.let { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) } })
                                }
                                repeat(columns-row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
            if(filter != 1) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { SectionLabel("Services"); MiniAction("Sort: ${if(addressSort) "Address" else "Name"}") { addressSort = !addressSort } }
                TechPanel(Modifier.fillMaxWidth()) {
                    val services = sortedServices(p.hits,addressSort)
                    if (services.isEmpty()) Text("No other services answered.", color=Tech.Muted, fontSize=Tech.Body)
                    services.forEachIndexed { i, hit ->
                        if (i > 0) HorizontalDivider(color=Tech.Edge.copy(alpha=.6f))
                        val requester=remember(hit.origin) { BringIntoViewRequester() }
                        LaunchedEffect(picked) { if(picked?.first==hit.origin) requester.bringIntoView() }
                        Row(Modifier.fillMaxWidth().bringIntoViewRequester(requester).background(if (hovered==hit.origin) Tech.Raised else androidx.compose.ui.graphics.Color.Transparent)
                            .onPointerEvent(PointerEventType.Enter) { hovered=hit.origin }.onPointerEvent(PointerEventType.Exit) { hovered=null }
                            .height(32.dp).padding(horizontal=4.dp), verticalAlignment=androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(Tech.Space12)) {
                            KindGlyph(hit.kind, 28.dp)
                            Column(Modifier.weight(1f)) {
                                val name = serviceName(hit)
                                Text(name, fontSize=Tech.Body, maxLines=1, overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(if (name == hit.kind.label) "No page title" else hit.kind.label, fontSize=Tech.Label, color=Tech.Muted)
                            }
                            Text(if (hit.secure) "TLS" else "HTTP", fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace, fontSize=Tech.Label, color=if (hit.secure) Tech.Green else Tech.Muted, modifier=Modifier.width(40.dp))
                            Text("${hit.ip}:${hit.port}", fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace, fontSize=Tech.Label, color=Tech.Ink, modifier=Modifier.width(150.dp))
                        }
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


/** Compact device card: identity row, certificate row, actions. */
@Composable private fun ScanDeviceCard(hit: ScanHit, highlighted: Boolean, modifier: Modifier, onUse: () -> Unit, onCopy: () -> Unit) {
    var expanded by remember(hit.origin) { mutableStateOf(false) }
    var browserNote by remember(hit.origin) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    TechPanel(modifier, padding=12.dp, rail=hit.kind.tint, selected=highlighted) {
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(Tech.Space12)) {
            KindGlyph(hit.kind, 40.dp)
            Column(Modifier.weight(1f)) {
                Text(scanHostname(hit), Modifier.identity(), fontSize=Tech.Section, fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines=1, overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text("${hit.ip}:${hit.port}  ·  ${hit.kind.label}", Modifier.identity(), fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace, fontSize=Tech.Label, color=Tech.Muted, maxLines=1, overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            if (hit.saved) StatusTag("Saved", true)
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color=Tech.Edge.copy(alpha=.6f))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(Tech.Space8)) {
            Column(Modifier.weight(1f)) {
                val fp = hit.fingerprint
                HelpTip(if (fp == null) "No certificate was observed on this port." else "Unverified. Compare with the server's own fingerprint (node > System > Certificates) before trusting it.") {
                    Text(if (fp == null) "No certificate" else "SHA-256  " + (if (expanded) fp else fp.take(23) + "…"),
                        fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace, fontSize=Tech.Label, color=Tech.Ink.copy(alpha=.85f),
                        modifier=Modifier.clickable(enabled=fp != null) { expanded = !expanded })
                }
                Text(certificateNote(hit), fontSize=11.sp, color=if ((hit.notAfter ?: Long.MAX_VALUE) < System.currentTimeMillis()) Tech.Amber else Tech.Muted,
                    maxLines=2, overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            MiniAction("Copy", enabled=hit.fingerprint != null, onClick=onCopy)
            // Only Proxmox VE and Datacenter Manager can be connected from the desk; anything else opens in the browser.
            if (scanConnectable(hit)) MiniAction("Use", primary=true, onClick=onUse)
            else if (!LocalAppHost.current.web) MiniAction("Open", onClick={
                scope.launch {
                    browserNote = when (withContext(Dispatchers.IO) { openInBrowser(hit.origin) }) {
                        BrowserLaunch.Failed -> {
                            runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(hit.origin), null) }
                            "No browser opened, so the link was copied."
                        }
                        BrowserLaunch.Pending -> "Browser launch still running."
                        BrowserLaunch.Accepted -> null
                    }
                }
            })
        }
        if (!scanConnectable(hit)) Text(browserNote ?: if (hit.kind == ScanKind.PBS) "Found on the network. RingMASTER does not connect to Backup Server yet."
            else "Found on the network. RingMASTER does not manage ${hit.kind.label}.", fontSize=11.sp, color=Tech.Muted, modifier=Modifier.padding(top=6.dp))
    }
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
