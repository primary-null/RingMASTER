@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ringleader.core.*

/*
 * The VPN page's subnet-route card: what the tailnet's routers offer, whether this computer uses it, and the one action
 * that turns accept-routes on (behind one confirmation, in VpnScreen). Names and networks are concealed in Showcase.
 */

/** "subnet-gw" or "subnet-gw, lab-router"; in Showcase "a subnet router" or "2 subnet routers". */
internal fun routersPhrase(routes: List<TailnetSubnetRoute>, showcase: Boolean): String {
    val names = routes.map { it.routerName }.distinct()
    return if (showcase) (if (names.size == 1) "a subnet router" else "${names.size} subnet routers") else names.joinToString(", ")
}

/** The plain sentence of the not-accepted card. */
internal fun routesNotUsedSentence(routes: List<TailnetSubnetRoute>, showcase: Boolean): String =
    "Your tailnet offers ${if (routes.size == 1) "this network" else "these networks"} through ${routersPhrase(routes, showcase)}; " +
        "this computer is not using ${if (routes.size == 1) "it" else "them"}, so servers there cannot be reached from here."

private fun hidden(value: String, showcase: Boolean, generic: String) = if (showcase) generic else value

@Composable internal fun SubnetRoutes(ts: TailscaleStatus, session: VpnSession, canAct: Boolean, onAccept: (List<TailnetSubnetRoute>) -> Unit) {
    val showcase = Appearance.showcase
    val routes = tailnetSubnetRoutes(ts, session.localNetworks)
    val own = routes.filter { it.own }
    val offered = routes.filter { !it.own }
    if (own.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("This computer offers to the tailnet:", fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted, maxLines = 1)
        Text(own.joinToString("  ·  ") { it.cidr }, Modifier.weight(1f, fill = false).identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (offered.isEmpty()) return
    val reading = session.acceptRoutes
    val platform = session.backend?.platform.orEmpty()
    when (reading?.answer) {
        AcceptRoutes.Accepted -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // Quiet: the networks are in use, nothing to do.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("USING TAILNET NETWORKS", fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Muted, maxLines = 1)
                val inUse = offered.filter { it.routerOnline && it.overlap == null }.ifEmpty { offered.filter { it.routerOnline } }
                Text(inUse.joinToString("  ·  ") { "${it.cidr} through ${it.routerName}" }.ifEmpty { "none: no router is online" }, Modifier.weight(1f, fill = false).identity(),
                    fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            offered.filter { !it.routerOnline }.takeIf { it.isNotEmpty() }?.let { off ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Router offline, not reachable now:", fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1)
                    Text(off.joinToString("  ·  ") { "${it.cidr} (${it.routerName})" }, Modifier.weight(1f, fill = false).identity(), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            OverlapLines(offered, reading, showcase)
        }
        AcceptRoutes.NotAccepted -> TechPanel(Modifier.fillMaxWidth(), rail = Tech.Amber, padding = 10.dp) {
            Text("TAILNET NETWORKS NOT IN USE", fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Muted)
            Spacer(Modifier.height(4.dp))
            Text(routesNotUsedSentence(offered, showcase), fontSize = 13.sp, lineHeight = 19.sp, color = Tech.Ink)
            Spacer(Modifier.height(6.dp))
            RouteRows(offered)
            OverlapLines(offered, reading, showcase)
            Spacer(Modifier.height(8.dp))
            if (acceptRoutesNeedsSudo(platform, reading)) SudoCommand(ACCEPT_ROUTES_SUDO_COMMAND,
                "On Linux only root or Tailscale's operator may change this, and this account is neither. Run in a terminal:")
            else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HostAction(if (session.busy == "routes") "Working…" else "Use these networks on this computer", primary = true,
                    enabled = canAct && session.busy == null, reason = if (canAct) null else VPN_HOST_ACTIONS_REASON) { onAccept(offered) }
                Text("Changes routing for the whole computer. Asks once before acting.", fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1)
            }
        }
        AcceptRoutes.Unknown, null -> TechPanel(Modifier.fillMaxWidth(), rail = Tech.Edge, padding = 10.dp) {
            Text("TAILNET NETWORKS", fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Muted)
            Spacer(Modifier.height(4.dp))
            Text(if (reading == null) "Checking whether this computer uses the networks ${routersPhrase(offered, showcase)} offers…"
                else "Your tailnet offers networks through ${routersPhrase(offered, showcase)}. Whether this computer uses them is not known. ${reading.reason.orEmpty()}".trim(),
                fontSize = 12.sp, lineHeight = 18.sp, color = Tech.Ink)
            Spacer(Modifier.height(6.dp))
            RouteRows(offered)
        }
    }
}

@Composable private fun RouteRows(routes: List<TailnetSubnetRoute>) {
    routes.forEach { r ->
        Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(r.cidr, Modifier.width(150.dp).identity(), fontFamily = Tech.Mono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Tech.Ink, maxLines = 1)
            Text("through", fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted, maxLines = 1)
            Text(r.routerName, Modifier.weight(1f, fill = false).identity(), fontFamily = Tech.Mono, fontSize = 12.sp, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TechTag(if (r.routerOnline) "Router online" else "Router offline", if (r.routerOnline) Tech.Green else Tech.Muted)
            if (r.overlap != null) TechTag("Local network too", Tech.Amber, help = "This network overlaps one this computer is directly attached to.")
            if (!r.primary) TechTag("From allowed IPs", Tech.Muted, help = "This Tailscale client does not report primary routes; the network comes from the router's allowed IPs.")
        }
    }
}

@Composable private fun OverlapLines(routes: List<TailnetSubnetRoute>, reading: AcceptRoutesReading, showcase: Boolean) {
    routes.filter { it.overlap != null }.forEach { r ->
        Text(overlapSentence(hidden(r.cidr, showcase, "A network"), reading.accepted, reading.overlaps[r.cidr], hidden(r.routerName, showcase, "the router"),
            hidden("${r.overlap!!.name}, ${r.overlap!!.cidr}", showcase, "hidden")), fontFamily = Tech.Mono, fontSize = 11.sp, lineHeight = 16.sp, color = Tech.Amber)
    }
}

/** The exact command, selectable as text, with a copy action. */
@Composable internal fun SudoCommand(command: String, lead: String) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember(command) { mutableStateOf(false) }
    Text(lead, fontSize = 12.sp, lineHeight = 17.sp, color = Tech.Ink)
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(command, fontFamily = Tech.Mono, fontSize = 12.sp, color = Tech.Ink, maxLines = 1)
        }
        MiniAction(if (copied) "Copied" else "Copy") { clipboard.setText(androidx.compose.ui.text.AnnotatedString(command)); copied = true }
    }
}

/** Under the confirmation's text: the networks, overlap warnings and the Linux rights note. */
@Composable internal fun AcceptRoutesDetails(routes: List<TailnetSubnetRoute>, platform: String) {
    val showcase = Appearance.showcase
    TechPanel(Modifier.fillMaxWidth(), rail = Tech.Amber, padding = 10.dp) {
        Text("NETWORKS", fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Muted)
        Spacer(Modifier.height(4.dp))
        routes.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(r.cidr, Modifier.identity(), fontFamily = Tech.Mono, fontSize = 12.sp, color = Tech.Ink, maxLines = 1)
                Text("through", fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted, maxLines = 1)
                Text(r.routerName, Modifier.weight(1f, fill = false).identity(), fontFamily = Tech.Mono, fontSize = 12.sp, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    routes.filter { it.overlap != null }.forEach { r ->
        Text("${hidden(r.cidr, showcase, "One network")} is also this computer's local network. Afterwards the operating system may send it through " +
            "${hidden(r.routerName, showcase, "the router")} instead of directly; RingMASTER reads which way wins after the change.",
            fontSize = 12.sp, lineHeight = 18.sp, color = Tech.Amber)
    }
    if (platform.equals("Linux", true)) Text("On Linux this needs root or Tailscale's operator. If Tailscale refuses, the exact command to run is shown instead.",
        fontSize = 12.sp, color = Tech.Muted)
}
