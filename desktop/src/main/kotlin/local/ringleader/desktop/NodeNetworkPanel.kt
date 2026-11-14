package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ringleader.core.NODE_NETWORK_DENIED
import local.ringleader.core.NodeInterface
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState

/**
 * A node's network: bridges, bonds, VLANs and the NICs in use, one row each. Physical NICs that are down and not
 * part of a bridge or bond fold into one line, since most servers carry several unused ports. A staged
 * `/etc/network/interfaces.new` is named above the list. The last rows stay up while Refresh runs.
 */
@Composable fun NodeNetworkPanel(state: WorkspaceState, workspace: Workspace, node: String) {
    val interfaces = state.nodeNetwork.value?.takeIf { state.nodeNetworkNode == node }
    val pending = state.nodeNetworkPending?.takeIf { state.nodeNetworkNode == node }
    val error = state.nodeNetwork.error?.takeIf { state.nodeNetworkNode == node }
    val loading = state.nodeNetworkLoading && state.nodeNetworkNode == node
    var showIdle by remember(node) { mutableStateOf(false) }
    var showDiff by remember(node, pending) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Network", suffix = interfaces?.count { it.active }?.let { "$it active" })
            Spacer(Modifier.weight(1f))
            MiniAction("Refresh", enabled = !loading) { workspace.loadNodeNetwork(node) }
        }
        if (!pending.isNullOrBlank()) {
            Text(
                if (showDiff) "Hide pending changes" else "Pending network changes, not applied",
                Modifier.clickable { showDiff = !showDiff }.padding(vertical = 2.dp),
                color = Tech.Amber, fontSize = 11.sp, lineHeight = 14.sp,
            )
            if (showDiff) Text(pending, fontFamily = Tech.Mono, fontSize = 10.sp, lineHeight = 13.sp, color = Tech.Ink)
        }
        if (error != null) Text(error, color = if (error == NODE_NETWORK_DENIED) Tech.Amber else Tech.Muted, fontFamily = Tech.Mono, fontSize = 11.sp)
        when {
            interfaces == null -> Text(if (loading) "Reading the network configuration…" else "—", color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 11.sp)
            interfaces.isEmpty() -> Text("No interfaces reported.", color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 11.sp)
            else -> {
                val used = interfaces.flatMap { it.members }.toSet()
                val idle = interfaces.filter { it.type == "eth" && !it.active && it.name !in used && it.cidr == null }
                interfaces.filter { it !in idle }.forEach { InterfaceRow(it, it.name in used) }
                if (idle.isNotEmpty()) {
                    Text(if (showIdle) "Hide ${idle.size} unused NICs" else "${idle.size} unused NICs · ${idle.joinToString(" ") { it.name }}",
                        Modifier.clickable { showIdle = !showIdle }.padding(vertical = 4.dp), color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 10.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (showIdle) idle.forEach { InterfaceRow(it, false) }
                }
            }
        }
    }
}

/** Address, DHCP, and gateway text for one interface row. */
internal fun interfaceAddressLine(i: NodeInterface): String {
    val addresses = listOfNotNull(i.cidr, i.cidr6)
    val dhcp = buildList {
        if (i.method.equals("dhcp", ignoreCase = true) && i.cidr == null) add("DHCP")
        if (i.method6.equals("dhcp", ignoreCase = true) && i.cidr6 == null) add("DHCPv6")
    }
    val gateways = listOfNotNull(i.gateway?.let { "gw $it" }, i.gateway6?.let { "gw6 $it" })
    return (addresses + dhcp + gateways).joinToString("  ·  ")
}

@Composable private fun InterfaceRow(i: NodeInterface, enslaved: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(7.dp).background(if (i.active) Tech.Green else Tech.Edge, CircleShape))
        Text(i.name, Modifier.width(92.dp), fontFamily = Tech.Mono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (i.active) Tech.Ink else Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        TechTypeTag(typeLabel(i.type), if (i.type.contains("bridge", true)) Tech.Amber else Tech.Muted)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            val primary = interfaceAddressLine(i)
            if (primary.isNotEmpty()) Text(primary, Modifier.identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(
                i.members.takeIf { it.isNotEmpty() }?.let { (if (i.type.contains("bond", true)) "members " else "ports ") + it.joinToString(" ") },
                if (i.type.contains("bridge", true) && i.members.isEmpty()) "no ports · internal only" else null,
                i.bondMode?.let { "mode $it" },
                i.vlanId?.let { id -> "VLAN $id" + (i.vlanRaw?.let { " on $it" } ?: "") },
                if (i.vlanAware) "VLAN aware" else null,
                i.mtu?.takeIf { it != 1500 }?.let { "MTU $it" },
                if (enslaved) "in use" else null,
                if (!i.autostart && i.type != "eth") "no autostart" else null,
                if (i.exists == false) "missing hardware" else null,
                i.comment,
            ).joinToString("  ·  ")
            if (detail.isNotEmpty()) Text(detail, fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun typeLabel(type: String) = when (type) {
    "bridge" -> "Bridge"; "OVSBridge" -> "OVS bridge"; "bond" -> "Bond"; "OVSBond" -> "OVS bond"; "vlan" -> "VLAN"
    "eth" -> "NIC"; "OVSPort" -> "OVS port"; "OVSIntPort" -> "OVS int"; "alias" -> "Alias"; else -> type
}
