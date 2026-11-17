package local.ringleader.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The board's host for a node of the Fleet inspector, by key or by name. */
internal fun RadiosBoard.nodeHost(node: String) = hosts.firstOrNull { it.isNode && (it.key == node || it.name == node) }

/** "Radios on this node" in the node inspector: each adapter, free or the guest that has it, and the node's own Wi-Fi. */
@Composable internal fun NodeRadiosPanel(node: String) {
    val hub = LocalRadios.current ?: return
    val board by hub.board.board.collectAsState()
    NodeRadiosSection(board, node, hub.nav)
}

@Composable internal fun NodeRadiosSection(board: RadiosBoard, node: String, nav: RadiosNav) {
    val host = board.nodeHost(node)
    val own = host?.let { board.radiosOn(it.key) }.orEmpty()
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("RADIOS ON THIS NODE", Modifier.weight(1f), fontSize = 9.sp, letterSpacing = 1.sp, color = Tech.Muted, fontFamily = Tech.Mono, maxLines = 1)
            if (host != null) Text("${own.size}", fontSize = 9.sp, color = Tech.Muted, fontFamily = Tech.Mono)
            if (host != null) MiniAction("Map") { nav.openRadios(RadiosTarget(RadiosView.Map, hostItemKey(host))) }
        }
        when {
            host == null -> (board.notes.firstOrNull() ?: RadioNote("Radios on this node were not read.")).let { RadioNoteLine(it, size = 11) }
            own.isEmpty() -> Text("No radios found on this node.", fontSize = 11.sp, color = Tech.Muted)
        }
        own.forEach { card ->
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { nav.openRadios(RadiosTarget(RadiosView.Map, radioItemKey(card))) }.padding(vertical = 3.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RadioGlyph(card.kind, size = 13.dp, state = card.state)
                    Text(card.title, Modifier.weight(1f), fontSize = 12.sp, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    RadioStateTag(card.state)
                    RadioSureTag(card)
                }
                val holder = card.holder
                if (holder == null) Text("${card.kind.label} · ${card.port ?: card.bus.label} · free", Modifier.padding(start = 19.dp), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted)
                else Row(Modifier.padding(start = 19.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${card.kind.label} · ${holder.via.label} →", fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1, softWrap = false)
                    Text("${holder.guest.label} ${holder.guest.title}", Modifier.weight(1f, fill = false).clickable(role = Role.Button) { nav.openGuest(holder.guest, "Radios") }.identity(),
                        fontFamily = Tech.Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Tech.Amber, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        host?.link?.let { Text("Own Wi-Fi: ${hostLinkText(it)}", fontFamily = Tech.Mono, fontSize = 10.sp, lineHeight = 14.sp, color = Tech.Ink) }
        host?.notes?.forEach { RadioNoteLine(it, size = 11) }
    }
}
