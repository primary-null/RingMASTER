@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.ringleader.core.*

/**
 * State for the VPN page, kept across page switches. Reads run on [Dispatchers.IO]; every change goes through [act],
 * which the UI only calls after the user confirms, and refreshes afterwards so the page shows what actually happened.
 */
class VpnSession(private val scope: CoroutineScope, val backend: VpnBackend?, val hostActions: Boolean = false, private val scanner: NetworkScanner? = null,
    private val readLocalNetworks: () -> List<LocalNetwork> = ::localIpv4Networks) {
    var snapshot by mutableStateOf<VpnSnapshot?>(null); private set
    /** True only while a manual Refresh is in flight. Background polls do not flip the toolbar. */
    var reading by mutableStateOf(false); private set
    var route by mutableStateOf<VpnRoute?>(null); private set
    /** Result of the last change, plain language. */
    var message by mutableStateOf<String?>(null); private set
    var failed by mutableStateOf(false); private set
    /** What a running change is working on: a tunnel id, "exit" or "import". */
    var busy by mutableStateOf<String?>(null); private set
    var services by mutableStateOf<List<TailnetService>>(emptyList()); private set
    var finding by mutableStateOf(false); private set
    var searched by mutableStateOf(false); private set
    /** "Include machines shared by others" for the tailnet search. Off at every start; never saved. */
    var includeShared by mutableStateOf(false)
    /** What the last search probed and left out. */
    var searchPlan by mutableStateOf<TailnetSearchPlan?>(null); private set
    /** Set from the window. Background polls wait while the window is minimised. */
    var minimized by mutableStateOf(false)
    /** Counts VPN changes, at their start and at their end, so route observers can drop what they saw before. */
    var changes by mutableStateOf(0); private set
    /** This computer's directly attached IPv4 networks, read with each refresh (JVM interface list, no command). */
    var localNetworks by mutableStateOf<List<LocalNetwork>>(emptyList()); private set
    /** Whether this computer uses the tailnet's subnet routes. Read only while some router offers a network. */
    var acceptRoutes by mutableStateOf<AcceptRoutesReading?>(null); private set
    // A reading that needed operating-system route reads (PowerShell on Windows) is kept a minute, unless a change ran.
    private var routesCache: Triple<Long, Pair<Int, List<String>>, AcceptRoutesReading>? = null
    private var generation = 0
    private var findJob: Job? = null

    /**
     * [manual] is a click on Refresh and is the only refresh that shows "Reading…".
     * [force] runs after a change even while the window is minimised.
     * A newer refresh wins: an older snapshot cannot overwrite it.
     */
    fun refresh(host: String?, manual: Boolean = false, force: Boolean = false) {
        val b = backend ?: return
        if (minimized && !manual && !force) return
        val ticket = synchronized(this) { ++generation }
        if (manual) reading = true
        // A route read for another server is never shown for this one.
        if (route != null && route?.host != host) route = null
        scope.launch {
            try {
                val s = withContext(Dispatchers.IO) { b.snapshot() }
                if (!isCurrent(ticket)) return@launch
                val local = withContext(Dispatchers.IO) { runCatching { readLocalNetworks() }.getOrDefault(emptyList()) }
                val reading = withContext(Dispatchers.IO) { readAcceptRoutes(b, s.tailscale, local) }
                if (!isCurrent(ticket)) return@launch
                snapshot = s; localNetworks = local; acceptRoutes = reading
                val next = host?.let { h -> withContext(Dispatchers.IO) { runCatching { b.routeTo(h, s) }.getOrNull() } }
                if (!isCurrent(ticket)) return@launch
                route = next
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                // The route came from the snapshot that just failed to refresh; it is not kept as if it were current.
                if (isCurrent(ticket)) route = null
            } finally { if (isCurrent(ticket)) reading = false }
        }
    }

    private fun isCurrent(ticket: Int) = synchronized(this) { ticket == generation }

    private fun readAcceptRoutes(b: VpnBackend, ts: TailscaleStatus?, local: List<LocalNetwork>): AcceptRoutesReading? {
        if (ts?.running != true) return null
        val offered = tailnetSubnetRoutes(ts, local).filter { !it.own }
        if (offered.isEmpty()) return null
        val key = changes to offered.map { "${it.cidr}@${it.routerId}:${it.routerOnline}:${it.overlap?.cidr}" }
        routesCache?.let { (at, k, r) -> if (k == key && System.nanoTime() - at < 60_000_000_000L) return r }
        val r = runCatching { b.acceptRoutes(ts, local) }.getOrElse { AcceptRoutesReading.unknown("The route settings could not be read.") }
        routesCache = if (r.source == AcceptRoutesSource.Preference && r.overlaps.isEmpty()) null else Triple(System.nanoTime(), key, r)
        return r
    }

    fun notice(text: String) { message = text; failed = false }

    fun act(key: String, host: String?, done: String, block: (VpnBackend) -> Unit) = actReporting(key, host) { block(it); done }

    /** [act], with the result sentence chosen by [block] from what it read back after the change. */
    fun actReporting(key: String, host: String?, block: (VpnBackend) -> String) {
        val b = backend ?: return
        if (!hostActions) { failed = true; message = VPN_HOST_ACTIONS_REASON; return }
        if (busy != null) return
        busy = key; message = null; failed = false
        changes++
        scope.launch {
            try { message = withContext(Dispatchers.IO) { block(b) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed = true; message = e.message?.takeIf { it.isNotBlank() } ?: "The change did not complete." }
            finally { busy = null; changes++; refresh(host, force = true) }
        }
    }

    fun findProxmox(origins: List<String>) {
        val status = snapshot?.tailscale ?: return
        findJob?.cancel()
        finding = true; searched = true; services = emptyList()
        val plan = planTailnetSearch(status, includeShared, acceptRoutes, localNetworks)
        searchPlan = plan
        findJob = scope.launch {
            try { (if (scanner != null) findTailnetProxmox(status, scanner, origins, plan) else findTailnetProxmox(status, origins = origins, plan = plan)).collect { services = it } }
            catch (e: CancellationException) { throw e } catch (_: Exception) {}
            finally { finding = false }
        }
    }
}

private val vpnClock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun hostOf(address: String?): String? = address?.takeIf { it.isNotBlank() }?.let { runCatching { java.net.URI(if ("://" in it) it else "https://$it").host }.getOrNull() }

/**
 * Tunnels on this computer (Tailscale, WireGuard, the OS's VPN profiles), the route RingMASTER's current connection
 * takes, and the tailnet with a search for Proxmox servers on it. [onUse] hands a found server to the connection desk.
 */
@Composable fun VpnScreen(session: VpnSession, currentAddress: String?, origins: List<String>, onUse: (ScanHit) -> Unit) {
    val appHost = LocalAppHost.current
    val canAct = session.hostActions
    val host = hostOf(currentAddress)
    LaunchedEffect(host, session.minimized) {
        if (session.minimized) return@LaunchedEffect
        while (true) { session.refresh(host); delay(10_000) }
    }
    var confirm by remember { mutableStateOf<VpnConfirm?>(null) }
    var allowLan by remember { mutableStateOf(true) }
    var importing by remember { mutableStateOf<Path?>(null) }
    val snap = session.snapshot
    val backend = session.backend
    // A click on the live ticker can ask for the tunnel that carries the connection: it is brought into view and lit for a moment.
    val pageFocus = LocalPageFocus.current
    val askedVpn = pageFocus?.take { it is TickerTarget.Vpn }
    var litTunnel by remember { mutableStateOf<Pair<String, Long>?>(null) }
    LaunchedEffect(askedVpn?.token, snap) {
        val request = askedVpn ?: return@LaunchedEffect
        val tunnels = snap?.tunnels ?: return@LaunchedEffect
        pageFocus.consume(request)
        val kind = (request.target as TickerTarget.Vpn).kind
        val id = tunnels.firstOrNull { kind != null && it.kind == kind && it.state == VpnState.Up }?.id
            ?: session.route?.tunnelId?.takeIf { carrying -> tunnels.any { it.id == carrying } } ?: tunnels.firstOrNull { it.state == VpnState.Up }?.id
        litTunnel = id?.let { it to request.token }
    }
    LayoutViewport(360.dp) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniAction(if (session.reading) "Reading…" else "Refresh", enabled = backend != null && !session.reading) { session.refresh(host, manual = true) }
            HostAction("Import WireGuard…", enabled = appHost.filePicker && backend != null && session.busy == null && canAct, reason = importReason(appHost.filePicker, backend != null, canAct)) { appHost.pickFile("Choose a WireGuard configuration", setOf("conf"))?.let { importing = it } }
            Spacer(Modifier.width(8.dp))
            // One status line in the fixed-height toolbar: a running change, the last change's result, or the read time.
            Text(when {
                session.busy != null -> "Working…"
                // The full sentence of a failure is shown under the toolbar.
                session.failed && session.message != null -> "The change did not complete."
                session.message != null -> session.message!!
                !canAct && backend != null -> VPN_HOST_ACTIONS_REASON
                backend == null -> "VPN controls are not available on this platform."
                snap == null -> "Reading tunnels…"
                else -> "UPDATED ${vpnClock.format(snap.at)} · ${backend.platform.uppercase()}"
            }, Modifier.weight(1f), fontFamily = Tech.Mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = when { session.busy != null -> Tech.Amber; session.message == null -> Tech.Muted; session.failed -> Tech.Red; else -> Tech.Green })
        }
        if (snap == null) return@Column
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // A failed change can say more than the one-line toolbar holds (what Tailscale reports now, what to do).
            if (session.failed && session.busy == null) session.message?.let { Text(it, Modifier.fillMaxWidth(), fontFamily = Tech.Mono, fontSize = 11.sp, lineHeight = 16.sp, color = Tech.Red) }
            if (host != null) RouteBanner(host, session.route, snap)
            SectionLabel("Tunnels", suffix = "${snap.tunnels.size}")
            if (snap.tunnels.isEmpty()) Text(buildString {
                append("No tunnels found on this computer.")
                append(if (snap.tailscaleInstalled) "" else " Tailscale is not installed.")
                append(if (snap.wireguardInstalled) "" else " WireGuard is not installed.")
            }, fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                snap.tunnels.forEach { t ->
                    TunnelCard(t, session.busy == t.id, session.route?.tunnelId == t.id, canAct, litToken = litTunnel?.takeIf { it.first == t.id }?.second ?: 0L,
                        onToggle = { confirm = VpnConfirm.Toggle(t, up = t.state != VpnState.Up) },
                        onRemove = { confirm = VpnConfirm.Remove(t, backend?.platform ?: "") })
                }
            }
            snap.tailscale?.let { ts -> Tailnet(ts, session, origins, canAct, onUse, onExit = { peer -> allowLan = exitNodeLanDefault(host, session.route); confirm = VpnConfirm.Exit(peer) },
                onAcceptRoutes = { routes -> confirm = VpnConfirm.AcceptRoutes(routes, backend?.platform.orEmpty()) }) }
            if (snap.notes.isNotEmpty() || snap.tailscaleCli != null) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                snap.notes.forEach { Text(it, fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted) }
                // Which binary was used, so a second install (snap, custom folder) is visible. A path can name the user.
                snap.tailscaleCli?.let { cli -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Tailscale CLI:", fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted)
                    Text(cli, Modifier.identity(), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    confirm?.let { c ->
        val throughIt = c is VpnConfirm.Toggle && session.route?.tunnelId == c.tunnel.id && !c.up
        val lanRisk = c is VpnConfirm.Exit && c.peer != null && !allowLan && privateLanAddress(session.route?.address ?: host)
        val cancel = remember { androidx.compose.ui.focus.FocusRequester() }
        TechDialog(c.title, { confirm = null }, initialFocus = cancel, actions = {
            TechAction(c.action, active = true) {
                when (c) {
                    is VpnConfirm.Toggle -> {
                        val live = session.snapshot?.tunnels?.firstOrNull { it.id == c.tunnel.id }
                        if (!toggleStillApplies(c.up, live)) { confirm = null; session.notice("That tunnel changed. Nothing was changed."); session.refresh(host, force = true); return@TechAction }
                        confirm = null
                        session.act(c.tunnel.id, host, "${c.tunnel.name} is ${if (c.up) "coming up" else "going down"}.") { backend ->
                            val fresh = backend.snapshot().tunnels.firstOrNull { it.id == c.tunnel.id }
                            if (!toggleStillApplies(c.up, fresh)) error("That tunnel changed. Nothing was changed.")
                            backend.setTunnel(c.tunnel, c.up)
                        }
                    }
                    is VpnConfirm.Remove -> {
                        val live = session.snapshot?.tunnels?.firstOrNull { it.id == c.tunnel.id }
                        if (!removeStillApplies(live)) { confirm = null; session.notice("That tunnel changed. Nothing was removed."); session.refresh(host, force = true); return@TechAction }
                        confirm = null
                        session.act(c.tunnel.id, host, "${c.tunnel.name} was removed.") { backend ->
                            val fresh = backend.snapshot().tunnels.firstOrNull { it.id == c.tunnel.id }
                            if (!removeStillApplies(fresh)) error("That tunnel changed. Nothing was removed.")
                            backend.removeWireGuard(c.tunnel)
                        }
                    }
                    is VpnConfirm.AcceptRoutes -> {
                        val local = session.localNetworks
                        if (!acceptRoutesStillApplies(session.acceptRoutes, tailnetSubnetRoutes(session.snapshot?.tailscale, local))) {
                            confirm = null; session.notice("The routes changed. Nothing was changed."); session.refresh(host, force = true); return@TechAction
                        }
                        confirm = null
                        session.actReporting("routes", host) { backend ->
                            // Read again right before acting (the page's reading can be seconds old), act once, then read back.
                            val fresh = backend.tailscaleStatus()
                            if (!acceptRoutesStillApplies(backend.acceptRoutes(fresh, local), tailnetSubnetRoutes(fresh, local)))
                                error("This computer already uses the tailnet's networks, or none are offered now. Nothing was changed.")
                            backend.setAcceptRoutes(true)
                            acceptRoutesResult(backend.acceptRoutes(backend.tailscaleStatus(), local))
                        }
                    }
                    is VpnConfirm.Exit -> {
                        if (!exitNodeStillApplies(c.peer, session.snapshot?.tailscale)) { confirm = null; session.notice("The exit node changed. Nothing was changed."); session.refresh(host, force = true); return@TechAction }
                        confirm = null
                        val lan = allowLan
                        session.act("exit", host, c.peer?.let { "Using ${it.hostName} as the exit node." } ?: "Stopped using an exit node.") { backend ->
                            if (!exitNodeStillApplies(c.peer, backend.snapshot().tailscale)) error("The exit node changed. Nothing was changed.")
                            backend.setExitNode(c.peer, lan)
                        }
                    }
                }
            }
            TechAction("Cancel", Modifier.focusRequester(cancel)) { confirm = null }
        }) {
            Text(c.body, fontSize = 13.sp, lineHeight = 19.sp)
            if (c is VpnConfirm.Exit && c.peer != null) TechCheck(allowLan, { allowLan = it }, "Keep this computer's local network reachable")
            if (c is VpnConfirm.AcceptRoutes) AcceptRoutesDetails(c.routes, c.platform)
            if (throughIt) Text("RingMASTER reaches $host through this tunnel, so the connection will drop.", fontSize = 13.sp, lineHeight = 19.sp, color = Tech.Amber)
            if (lanRisk) Text("The current server is on the local network. With this off, that connection can drop.", fontSize = 13.sp, lineHeight = 19.sp, color = Tech.Amber)
            if (c.admin) Text("The system will ask for administrator approval.", fontSize = 12.sp, color = Tech.Muted)
        }
    }
    importing?.let { file -> ImportWireGuardDialog(file, windows = backend?.platform == "Windows", backend = backend, onDismiss = { importing = null }) { name, scriptsAccepted ->
        importing = null
        session.act("import", host, "Tunnel $name was imported.") { it.importWireGuardReviewed(file, name, scriptsAccepted) }
    } }
}

}
private sealed class VpnConfirm(val title: String, val body: String, val action: String, val admin: Boolean) {
    class Toggle(val tunnel: VpnTunnel, val up: Boolean) : VpnConfirm(
        "${if (up) "Bring up" else "Bring down"} ${tunnel.name}?",
        if (up) "Traffic for the networks this tunnel carries will go through it." else "Traffic that uses this tunnel will stop until it is brought up again.",
        if (up) "Bring up" else "Bring down", tunnel.toggleNeedsAdmin)
    class Remove(val tunnel: VpnTunnel, platform: String) : VpnConfirm("Remove ${tunnel.name}?", vpnRemoveBody(platform, tunnel), "Remove", tunnel.toggleNeedsAdmin)
    class AcceptRoutes(val routes: List<TailnetSubnetRoute>, val platform: String) : VpnConfirm(
        "Use the tailnet's networks on this computer?", acceptRoutesConfirmBody(), "Use these networks", false)
    class Exit(val peer: TailscalePeer?) : VpnConfirm(
        if (peer == null) "Stop using an exit node?" else "Use ${peer.hostName} as the exit node?",
        if (peer == null) "Internet traffic goes out through this computer's own connection again." else "All internet traffic from this computer will go out through ${peer.hostName}. Local network access depends on the choice below.",
        if (peer == null) "Stop" else "Use exit node", false)
}

@Composable private fun RouteBanner(host: String, route: VpnRoute?, snap: VpnSnapshot) {
    val tunnel = route?.tunnelId?.let { id -> snap.tunnels.firstOrNull { it.id == id } }
    // The OS route to a tailnet address in userspace mode is the default route, which cannot carry it: no claim.
    // Interface names are identities (the connection route contract, Privacy): not shown in Showcase.
    val shownInterface = route?.interfaceName?.takeUnless { Appearance.showcase }
    val userspace = tunnel == null && route?.address != null && snap.tailscale?.let { it.userspace && tailnetDestination(route.address!!, it) } == true
    val router = route?.address?.takeIf { tunnel?.kind == VpnKind.Tailscale }?.let { tailscalePeerPath(snap.tailscale, it) as? PeerPath.SubnetRouter }
    TechPanel(Modifier.fillMaxWidth(), rail = if (tunnel != null) Tech.Green else Tech.Edge, padding = 10.dp) {
        Text("ROUTE TO CURRENT SERVER", fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Muted)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(host, Modifier.identity(), fontFamily = Tech.Mono, fontSize = 13.sp, color = Tech.Ink, maxLines = 1)
            Text("→", fontFamily = Tech.Mono, fontSize = 13.sp, color = Tech.Muted)
            Text(when {
                route == null -> "Checking…"
                route.address == null -> "The name does not resolve from this computer."
                // The OS route goes through Tailscale and the status names the router for the address: both agree.
                tunnel != null && router != null -> "Through Tailscale \u00b7 subnet router" + (if (Appearance.showcase) "" else " ${router.router}")
                tunnel != null -> "Through ${if (Appearance.showcase) tunnel.kind.label else tunnel.name}" + (shownInterface?.takeIf { !it.equals(tunnel.name, true) }?.let { " · $it" } ?: "")
                userspace -> "Not known: Tailscale runs in userspace mode"
                else -> "Ordinary network" + (shownInterface?.let { " · $it" } ?: "") + " · no tunnel"
            }, fontFamily = Tech.Mono, fontSize = 13.sp, color = if (tunnel != null) Tech.Green else if (userspace) Tech.Amber else Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        route?.address?.takeIf { it != host }?.let { Text(it, Modifier.identity(), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted) }
    }
}

@Composable private fun TunnelCard(t: VpnTunnel, busy: Boolean, carriesServer: Boolean, canAct: Boolean, litToken: Long = 0L, onToggle: () -> Unit, onRemove: () -> Unit) {
    val tint = when (t.state) { VpnState.Up -> Tech.Green; VpnState.Connecting, VpnState.NeedsLogin -> Tech.Amber; else -> Tech.Muted }
    TechPanel(Modifier.width(340.dp).focusGlow(litToken, litToken != 0L), rail = tint, padding = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Canvas(Modifier.size(16.dp)) { tunnelGlyph(t.kind, Tech.Ink) }
            Text(t.name, Modifier.weight(1f).identity(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TechTag(when (t.state) { VpnState.Up -> "Up"; VpnState.Down -> "Down"; VpnState.Connecting -> "Connecting"; VpnState.NeedsLogin -> "Needs login"; VpnState.Unknown -> "Unknown" }, tint)
        }
        Spacer(Modifier.height(4.dp))
        // The interface name is an identity (it is often the tunnel's own name), so Showcase leaves it out.
        Text(listOfNotNull(t.kind.label, t.interfaceName?.takeIf { !it.equals(t.kind.label, true) && !Appearance.showcase }).joinToString("  ·  ").uppercase(), fontFamily = Tech.Mono, fontSize = 9.sp, letterSpacing = 1.sp, color = Tech.Muted, maxLines = 1)
        if (t.addresses.isNotEmpty()) Text(t.addresses.joinToString("  ·  "), Modifier.identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        t.detail?.let { Text(it, Modifier.identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        val traffic = listOfNotNull(t.rxBytes?.let { "↓ ${iec(it.toDouble())}" }, t.txBytes?.let { "↑ ${iec(it.toDouble())}" },
            t.lastHandshakeEpoch?.let { "handshake ${duration(Instant.now().epochSecond - it)} ago" })
        if (traffic.isNotEmpty()) Text(traffic.joinToString("   "), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1)
        if (carriesServer) Text("Carries the current server connection", fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Green)
        if (t.canToggle || t.canRemove) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (t.canToggle && t.state != VpnState.Connecting) HostAction(if (busy) "Working…" else if (t.state == VpnState.Up) "Bring down" else "Bring up", primary = t.state != VpnState.Up, enabled = !busy && canAct, reason = if (canAct) null else VPN_HOST_ACTIONS_REASON, onClick = onToggle)
                if (t.canRemove && t.state != VpnState.Up) HostAction("Remove", enabled = !busy && canAct, reason = if (canAct) null else VPN_HOST_ACTIONS_REASON, onClick = onRemove)
            }
        }
    }
}

/** The largest whole unit only: "45s", "12m", "14h", "57d". */
internal fun agoShort(seconds: Long): String { val s = seconds.coerceAtLeast(0); return when { s < 60 -> "${s}s"; s < 3600 -> "${s / 60}m"; s < 86400 -> "${s / 3600}h"; else -> "${s / 86400}d" } }

private val VpnKind.label get() = when (this) { VpnKind.Tailscale -> "Tailscale"; VpnKind.WireGuard -> "WireGuard"; VpnKind.OpenVpn -> "OpenVPN"; VpnKind.IpSec -> "IPsec"; VpnKind.Other -> "VPN" }

@Composable private fun Tailnet(ts: TailscaleStatus, session: VpnSession, origins: List<String>, canAct: Boolean, onUse: (ScanHit) -> Unit, onExit: (TailscalePeer?) -> Unit,
    onAcceptRoutes: (List<TailnetSubnetRoute>) -> Unit = {}) {
    var exitMenu by remember { mutableStateOf(false) }
    var sshPeer by remember { mutableStateOf<TailscalePeer?>(null) }
    val web = LocalAppHost.current.web
    val online = ts.peers.count { it.online }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Tailnet", suffix = "$online / ${ts.peers.size} online")
            ts.tailnet?.let { Text(it, Modifier.identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted, maxLines = 1) }
            Spacer(Modifier.weight(1f))
            if (ts.running) Box {
                HostAction("Exit node · ${ts.exitNode?.hostName ?: "none"}", enabled = session.busy == null && canAct, reason = if (canAct) null else VPN_HOST_ACTIONS_REASON) { exitMenu = true }
                DropdownMenu(exitMenu, { exitMenu = false }, containerColor = Tech.Raised) {
                    DropdownMenuItem(text = { Text("None", fontSize = 13.sp) }, onClick = { exitMenu = false; if (ts.exitNode != null) onExit(null) })
                    ts.peers.filter { it.exitNodeOption && it.online }.forEach { p ->
                        DropdownMenuItem(text = { Text(p.hostName, Modifier.identity(), fontSize = 13.sp, color = if (p.exitNode) Tech.Amber else Tech.Ink) }, onClick = { exitMenu = false; if (!p.exitNode) onExit(p) })
                    }
                }
            }
            if (ts.running) TechCheck(session.includeShared, { session.includeShared = it }, "Include machines shared by others", enabled = !session.finding)
            if (ts.running) MiniAction(if (session.finding) "Searching…" else "Find Proxmox", primary = true, enabled = !session.finding) { session.findProxmox(origins) }
        }
        // Without the link (browser mode without host actions) the advice is the one for no link.
        val linkShown = !LocalAppHost.current.web || canAct
        tailscaleStateAdvice(if (linkShown) ts else ts.copy(authUrl = null), session.backend?.platform.orEmpty())?.let { Text(it, fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Amber) }
        if (!ts.running) signInLink(ts)?.let { SignInLink(it, canAct) }
        ts.self?.let { self ->
            Text("This computer: ${self.hostName} · ${self.addresses.joinToString(" · ")}", Modifier.identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Said before any search: in userspace mode the search would only time out.
        if (ts.userspace) Text(TAILSCALE_USERSPACE_NOTE, fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Amber)
        if (ts.running) SubnetRoutes(ts, session, canAct, onAcceptRoutes)
        ts.health.take(3).forEach { Text(it, fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Amber, maxLines = 2) }
        if (ts.unreadablePeers > 0) Text("${ts.unreadablePeers} peer ${if (ts.unreadablePeers == 1) "entry was" else "entries were"} not readable and ${if (ts.unreadablePeers == 1) "is" else "are"} not listed.",
            fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted)
        if (session.searched) FoundServers(session, onUse)
        // Open SSH launches a terminal on this computer: desktop only, and only where the OS can reach tailnet addresses.
        PeerTable(ts.peers, session.services, onSsh = if (!web && ts.running && !ts.userspace && session.backend != null) { p -> sshPeer = p } else null)
        peerSshFootnote(ts.peers.filter { it.location == null }, web)?.let { Text(it, fontFamily = Tech.Mono, fontSize = 10.sp, lineHeight = 14.sp, color = Tech.Muted) }
    }
    sshPeer?.let { p ->
        val address = p.addresses.firstOrNull { ScanRules.ipv4(it) != null } ?: p.addresses.firstOrNull()
        if (address == null) sshPeer = null
        else OpenSshDialog(p, address, "", "peer:${p.id}", session.backend) { sshPeer = null }
    }
}

/**
 * Tailscale's sign-in link. On the desktop it opens in the browser only when the user clicks (off the UI thread); in
 * browser mode it would open on the RingMASTER host, so only a copy action is offered there. The link text is concealed
 * in Showcase.
 */
@Composable private fun SignInLink(url: String, canAct: Boolean) {
    val web = LocalAppHost.current.web
    // Whoever finishes the sign-in decides which tailnet this computer joins, so in browser mode the link is a host change.
    if (web && !canAct) { Text(SIGN_IN_HOST_ACTIONS_REASON, fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted); return }
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var note by remember(url) { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!web) MiniAction("Open sign-in link", primary = true) {
            scope.launch {
                note = when (withContext(Dispatchers.IO) { openInBrowser(url) }) {
                    BrowserLaunch.Accepted -> "Opened in your browser."
                    BrowserLaunch.Pending -> "The browser is still starting."
                    BrowserLaunch.Failed -> { clipboard.setText(androidx.compose.ui.text.AnnotatedString(url)); "No browser opened, so the link was copied." }
                }
            }
        }
        MiniAction("Copy sign-in link", primary = web) { clipboard.setText(androidx.compose.ui.text.AnnotatedString(url)); note = "Sign-in link copied." }
        if (!web) Text(url, Modifier.weight(1f, fill = false).identity(), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        note?.let { Text(it, fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1) }
    }
}

internal const val SIGN_IN_HOST_ACTIONS_REASON = "The sign-in link decides which tailnet this computer joins. Start with --allow-host-actions to offer it in the browser."

@Composable private fun FoundServers(session: VpnSession, onUse: (ScanHit) -> Unit) {
    val found = session.services
    TechPanel(Modifier.fillMaxWidth(), rail = if (found.isNotEmpty()) Tech.Green else Tech.Edge, padding = 10.dp) {
        val plan = session.searchPlan
        Text(tailnetSearchHeader(session.finding, found.size, plan?.subnets?.size ?: 0), fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Muted)
        if (found.isEmpty() && !session.finding) Text("No Proxmox servers answered on the tailnet.", fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted)
        plan?.let(::tailnetSkipNote)?.let { Text(it, fontFamily = Tech.Mono, fontSize = 11.sp, lineHeight = 16.sp, color = Tech.Muted) }
        plan?.subnets?.takeIf { it.isNotEmpty() }?.let { nets ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Also searched through routers:", fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted)
                Text(nets.joinToString("  \u00b7  ") { (r, n) -> "${n.cidr} (${r.routerName})" }, Modifier.weight(1f, fill = false).identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted, maxLines = 2)
            }
        }
        plan?.subnetsOff?.let { Text(it, fontFamily = Tech.Mono, fontSize = 11.sp, lineHeight = 16.sp, color = Tech.Muted) }
        found.forEach { s ->
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(when (s.hit.kind) { ScanKind.PVE -> "PVE"; ScanKind.PDM -> "PDM"; ScanKind.PBS -> "PBS"; else -> s.hit.kind.name }, Modifier.width(36.dp), fontFamily = Tech.Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tech.Amber)
                if (s.through != null) Row(Modifier.width(180.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("through", fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted, maxLines = 1)
                    Text(s.peer.hostName, Modifier.weight(1f, fill = false).identity(), fontFamily = Tech.Mono, fontSize = 12.sp, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                else Text(s.peer.hostName, Modifier.width(180.dp).identity(), fontFamily = Tech.Mono, fontSize = 12.sp, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${s.hit.ip}:${s.hit.port}", Modifier.weight(1f).identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted, maxLines = 1)
                if (s.hit.saved) TechTag("Saved", Tech.Muted)
                MiniAction("Use") { onUse(s.hit) }
            }
        }
    }
}

/** Room for the Proxmox, exit-node and peer-fact tags of one row. */
private const val PEER_TAGS_WIDTH = 300
/** Room for Open SSH. */
private const val PEER_SSH_WIDTH = 96

@Composable private fun PeerTable(peers: List<TailscalePeer>, services: List<TailnetService>, onSsh: ((TailscalePeer) -> Unit)? = null) {
    val now = Instant.now()
    Surface(Modifier.fillMaxWidth(), color = Tech.Panel, shape = Tech.Plate, border = BorderStroke(1.dp, Tech.Edge)) {
        Column {
            Row(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("PEER" to null, "OS" to 72, "ADDRESS" to 128, "PATH" to 96, "SEEN" to 76, "" to PEER_TAGS_WIDTH) + listOfNotNull(onSsh?.let { "" to PEER_SSH_WIDTH })).forEach { (label, w) ->
                    Text(label, if (w == null) Modifier.weight(1f) else Modifier.width(w.dp), fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Muted, maxLines = 1)
                }
            }
            peers.forEachIndexed { i, p ->
                HorizontalDivider(color = Tech.Edge.copy(alpha = .55f))
                val proxmox = services.filter { it.peer.id == p.id }
                Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(7.dp).background(if (p.online) Tech.Green else Tech.Edge, androidx.compose.foundation.shape.CircleShape))
                        Text(p.hostName, Modifier.identity(), fontFamily = Tech.Mono, fontSize = 12.sp, color = if (p.online) Tech.Ink else Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(p.os ?: "—", Modifier.width(72.dp), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1)
                    Text(p.addresses.firstOrNull() ?: "—", Modifier.width(128.dp).identity(), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1)
                    // The same label the route tag's tooltip starts with (core peerPathLabel).
                    val path = peerPath(p)
                    Text(peerPathLabel(path), Modifier.width(96.dp), fontFamily = Tech.Mono, fontSize = 10.sp,
                        color = if (path is PeerPath.Direct) Tech.Green else Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (p.online) "now" else p.lastSeen?.let { "${agoShort(Duration.between(it, now).seconds)} ago" } ?: "—", Modifier.width(76.dp), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.width(PEER_TAGS_WIDTH.dp).clipToBounds(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        proxmox.map { it.hit.kind }.distinct().forEach { TechTag(it.name, Tech.Amber) }
                        if (p.exitNode) TechTag("Exit", Tech.Green) else if (p.exitNodeOption) TechTag("Exit option", Tech.Muted)
                        tailscalePeerTags(p, now).forEach { tag ->
                            TechTag(tag.label, when (tag.tone) { PeerTagTone.Warning -> Tech.Red; PeerTagTone.Caution -> Tech.Amber; PeerTagTone.Info -> Tech.Muted }, help = tag.help)
                        }
                    }
                    // Only for a peer that publishes host keys: plain sshd would need trust on first use.
                    if (onSsh != null) Box(Modifier.width(PEER_SSH_WIDTH.dp), contentAlignment = Alignment.CenterEnd) {
                        if (p.online && p.sshHostKeyCount > 0 && p.location == null) HelpTip(SSH_ACTION_HELP) { MiniAction("Open SSH") { onSsh(p) } }
                    }
                }
            }
        }
    }
}

/**
 * The single import confirmation. It reviews the file first (bounded read, off the UI thread) and lists, by key name only,
 * what the file does beyond a plain tunnel: scripts, Table, SaveConfig and full-tunnel AllowedIPs. No key or command line
 * is shown. When the scripts would run as Local System, Import waits for the box that says so.
 */
@Composable private fun ImportWireGuardDialog(file: Path, windows: Boolean, backend: VpnBackend?, onDismiss: () -> Unit, onImport: (String, Boolean) -> Unit) {
    val suggested = remember(file) { file.fileName.toString().substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "-").take(15).ifBlank { "wg0" } }
    var name by remember(file) { mutableStateOf(suggested) }
    var accepted by remember(file) { mutableStateOf(false) }
    val review by produceState<Pair<WireGuardConfigReview, WireGuardScriptPolicy>?>(null, file, backend) {
        value = withContext(Dispatchers.IO) {
            val r = runCatching { readWireGuardReview(file) }.getOrElse { WireGuardConfigReview(isConfig = false) }
            r to (if (r.scripts.isEmpty()) WireGuardScriptPolicy.NotRun else runCatching { backend?.wireGuardScriptPolicy() }.getOrNull() ?: WireGuardScriptPolicy.Unknown)
        }
    }
    val valid = validWireGuardName(name)
    val consent = review?.let { (r, p) -> wireGuardScriptsNeedConsent(r, p) } == true
    val ready = review?.first?.isConfig == true && valid && (!consent || accepted)
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    // A form: a stray click outside keeps the typed name. Escape is Cancel.
    TechDialog("Import WireGuard tunnel", onDismiss, subtitle = file.fileName.toString(), dismiss = Dismiss.Soft, initialFocus = first, actions = {
        TechAction("Import", active = true, enabled = ready) { onImport(name, consent && accepted) }
        TechAction("Cancel", onClick = onDismiss)
    }) {
        TechField(name, { name = it.take(15) }, "Tunnel name", Modifier.focusRequester(first),
            error = if (valid) null else "Use letters, digits, - and _ only, up to 15 characters.",
            hint = "Letters, digits, - and _, up to 15 characters.")
        Text(if (windows) "The configuration goes into WireGuard's own protected store, so it also appears in the WireGuard app. Windows asks for administrator approval. The tunnel is not brought up yet."
            else "NetworkManager stores the tunnel. RingMASTER turns its auto-connect off, so it stays down until you bring it up.", fontSize = 11.sp, color = Tech.Muted)
        val current = review
        when {
            current == null -> Text("Checking the file\u2026", fontSize = 11.sp, color = Tech.Muted)
            current.first.tooLarge -> Text("That file is too large to be a WireGuard configuration.", fontSize = 12.sp, color = Tech.Red)
            !current.first.isConfig -> Text("That file is not a WireGuard configuration (no [Interface] section).", fontSize = 12.sp, color = Tech.Red)
            current.first.hasFindings -> {
                val lines = wireGuardReviewLines(current.first, current.second)
                TechPanel(Modifier.fillMaxWidth(), rail = if (consent) Tech.Red else Tech.Amber, padding = 10.dp) {
                    Text("THIS FILE ALSO", fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Muted)
                    Spacer(Modifier.height(4.dp))
                    lines.forEach { Text("\u00b7 $it", fontSize = 12.sp, lineHeight = 17.sp, color = Tech.Ink) }
                }
                if (consent) TechCheck(accepted, { accepted = it }, "I understand these scripts will run as Local System")
            }
        }
    }
}

private fun importReason(filePicker: Boolean, hasBackend: Boolean, canAct: Boolean): String? = when {
    !hasBackend -> null
    !canAct -> VPN_HOST_ACTIONS_REASON
    !filePicker -> "Import needs the desktop app. The browser cannot choose a file."
    else -> null
}

@Composable internal fun HostAction(label: String, primary: Boolean = false, enabled: Boolean, reason: String? = null, onClick: () -> Unit) {
    if (!enabled && reason != null) HelpTip(reason) { MiniAction(label, primary = primary, enabled = false, onClick = onClick) }
    else MiniAction(label, primary = primary, enabled = enabled, onClick = onClick)
}

/** Generic line glyphs (not product logos): a mesh of three nodes, a tunnel arch, a shield. */
private fun DrawScope.tunnelGlyph(kind: VpnKind, c: Color) {
    val st = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)
    val w = size.width; val h = size.height
    when (kind) {
        VpnKind.Tailscale -> {
            val a = Offset(w * .2f, h * .75f); val b = Offset(w * .8f, h * .75f); val t = Offset(w * .5f, h * .22f)
            drawLine(c, a, b, st.width); drawLine(c, a, t, st.width); drawLine(c, b, t, st.width)
            listOf(a, b, t).forEach { drawCircle(c, 2.2.dp.toPx(), it) }
        }
        VpnKind.WireGuard -> {
            drawArc(c, 180f, 180f, false, Offset(w * .14f, h * .18f), Size(w * .72f, h * .72f), style = st)
            drawLine(c, Offset(w * .14f, h * .54f), Offset(w * .14f, h * .9f), st.width, StrokeCap.Round)
            drawLine(c, Offset(w * .86f, h * .54f), Offset(w * .86f, h * .9f), st.width, StrokeCap.Round)
            drawLine(c, Offset(w * .36f, h * .9f), Offset(w * .64f, h * .9f), st.width, StrokeCap.Round)
        }
        else -> {
            val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * .5f, h * .06f); lineTo(w * .86f, h * .2f); lineTo(w * .8f, h * .62f); lineTo(w * .5f, h * .94f); lineTo(w * .2f, h * .62f); lineTo(w * .14f, h * .2f); close() }
            drawPath(p, c, style = st)
        }
    }
}
