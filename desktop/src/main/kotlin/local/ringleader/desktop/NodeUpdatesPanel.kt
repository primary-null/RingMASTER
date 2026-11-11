package local.ringleader.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ringleader.core.NODE_UPDATES_NOTE
import local.ringleader.core.PackageUpdate
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState

private const val UPDATE_ROWS_SHOWN = 100

/**
 * Packages the node lists as upgradable (`GET /nodes/{node}/apt/update`, read-only). It is the node's cached list from its last
 * package-list refresh; this panel never refreshes it. Shows the first 100 packages and a button for the rest.
 */
@Composable fun NodeUpdatesPanel(state: WorkspaceState, workspace: Workspace, node: String, modifier: Modifier = Modifier) {
    val section = state.nodeUpdates
    val view = section.value?.takeIf { it.node == node }
    LaunchedEffect(node, state.connectionEpoch) {
        if (state.nodeUpdates.value?.node != node) workspace.loadNodeUpdates(node)
    }
    var showAll by remember(node) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Updates", suffix = view?.let { "${it.updates.size} available" })
            Spacer(Modifier.weight(1f))
            MiniAction("Refresh", enabled = !section.loading) { workspace.loadNodeUpdates(node) }
        }
        Text(NODE_UPDATES_NOTE, fontSize = 10.sp, lineHeight = 13.sp, color = Tech.Muted)
        ReadNotices(section)
        when {
            view == null -> if (!section.loading && section.error == null) Text("—", color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 11.sp)
            view.updates.isEmpty() -> Text("No package updates are listed for this node.", color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 11.sp)
            else -> {
                view.updates.take(if (showAll) Int.MAX_VALUE else UPDATE_ROWS_SHOWN).forEach { UpdateRow(it) }
                if (!showAll && view.updates.size > UPDATE_ROWS_SHOWN) MiniAction("Show all ${view.updates.size}") { showAll = true }
                Text("UPDATED ${readClock(section.updatedAt)}", fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted)
            }
        }
    }
}

internal fun updateVersionText(update: PackageUpdate): String = when {
    update.newVersion == null -> update.oldVersion ?: "—"
    update.oldVersion == null -> "new ${update.newVersion}"
    else -> "${update.oldVersion} → ${update.newVersion}"
}

@Composable private fun UpdateRow(update: PackageUpdate) {
    Column(
        Modifier.fillMaxWidth().drawBehind { drawLine(Tech.Edge.copy(alpha = .6f), Offset(0f, size.height), Offset(size.width, size.height), 1f) }.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(update.name, Modifier.weight(1f, fill = false), fontFamily = Tech.Mono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Tech.Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                update.origin?.let { if (it.equals("Proxmox", ignoreCase = true)) AttentionTag(it) else NeutralTag(it) }
            }
            Text(updateVersionText(update), fontFamily = Tech.Mono, fontSize = 10.5.sp, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        (update.description ?: update.title)?.let { Text(it, fontSize = 10.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
