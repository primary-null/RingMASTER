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
import local.ringleader.core.NodeInterface

/**
 * A node's network: bridges, bonds, VLANs and the NICs in use, one row each. Physical NICs that are down and not
 * part of a bridge or bond fold into one line, since most servers carry several unused ports.
 */
@Composable fun NodeNetworkPanel(interfaces: List<NodeInterface>?, loading: Boolean, error: String?) {
    var showIdle by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("Network", suffix = interfaces?.count { it.active }?.let { "$it active" })
        when {
            error != null -> Text(error, color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 11.sp)
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

@Composable private fun InterfaceRow(i: NodeInterface, enslaved: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(7.dp).background(if (i.active) Tech.Green else Tech.Edge, CircleShape))
        Text(i.name, Modifier.width(92.dp), fontFamily = Tech.Mono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (i.active) Tech.Ink else Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        TechTypeTag(typeLabel(i.type), if (i.type.contains("bridge", true)) Tech.Amber else Tech.Muted)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            val primary = listOfNotNull(i.cidr, i.cidr6).joinToString("  ·  ")
            if (primary.isNotEmpty()) Text(primary + (i.gateway?.let { "  →  gw $it" } ?: ""), Modifier.identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(
                i.members.takeIf { it.isNotEmpty() }?.let { (if (i.type.contains("bond", true)) "members " else "ports ") + it.joinToString(" ") },
                if (i.type.contains("bridge", true) && i.members.isEmpty()) "no ports · internal only" else null,
                i.bondMode?.let { "mode $it" },
                i.vlanId?.let { id -> "VLAN $id" + (i.vlanRaw?.let { " on $it" } ?: "") },
                if (i.vlanAware) "VLAN aware" else null,
                i.mtu?.takeIf { it != 1500 }?.let { "MTU $it" },
                if (enslaved) "in use" else null,
                if (!i.autostart && i.type != "eth") "no autostart" else null,
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
