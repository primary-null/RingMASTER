package local.ringleader.desktop

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ringleader.core.ResourceId

/*
 * The Watcher page: guests and nodes the engine keeps an eye on, across every saved server. Rows use the fleet's own
 * pieces (identity tile, mono ID, semibold name, status tag, red failure edge). A target that went away stays listed,
 * dimmed, with why and when it was last seen, until it is unwatched. This page polls nothing; it shows [WatcherView].
 */

internal const val WATCHER_LIST_TAG = "watcher-list"

/**
 * [guestPresent] says whether a guest is in the inventory of the connection in view (only then can Open route to it);
 * [onOpen] does the routing.
 */
@Composable internal fun WatcherScreen(
    view: WatcherView, actions: WatcherActions, guestPresent: (ResourceId) -> Boolean, onOpen: (WatchTargetView) -> Unit,
    modifier: Modifier = Modifier, unreadNotices: Int = 0, onNotifications: (() -> Unit)? = null,
) {
    val counts = watchCounts(view.targets)
    val ordered = remember(view.targets) { orderWatchTargets(view.targets) }
    val sources = ordered.map { it.sourceId }.distinct()
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Watched", suffix = "${counts.total}")
            Text(watcherActivityLabel(view.activity).uppercase(), fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = .8.sp,
                color = if (view.activity is WatcherActivity.Watching) Tech.Green else Tech.Amber)
            if (counts.incidents > 0) NoticeTag("${counts.incidents} incident${if (counts.incidents == 1) "" else "s"}", Tech.Red)
            if (counts.unavailable > 0) NoticeTag("${counts.unavailable} unavailable", Tech.Amber)
            if (onNotifications != null) MiniAction(if (unreadNotices > 0) "Notifications $unreadNotices" else "Notifications", primary = unreadNotices > 0, onClick = onNotifications)
        }
        view.problem?.let { Text(it, fontSize = 12.sp, lineHeight = 16.sp, color = Tech.Amber) }
        if (ordered.isEmpty()) {
            TechPanel(Modifier.fillMaxWidth(), rail = Tech.Amber) {
                Text("Nothing is watched yet", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(if (actions.onWatchGuest != null) "Choose Watch on a guest's page or in the fleet inspector. Its state changes are then reported in notifications, also while the window is hidden."
                    else "Watched guests and nodes appear here.", fontSize = 12.sp, lineHeight = 17.sp, color = Tech.Muted)
            }
            return@Column
        }
        val list = rememberLazyListState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp).testTag(WATCHER_LIST_TAG), state = list) {
                // Several servers: a light heading per server, in the order of their most urgent target.
                sources.forEach { source ->
                    val group = ordered.filter { it.sourceId == source }
                    if (sources.size > 1) item(key = "source:$source") {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(6.dp).background(Tech.Muted))
                            Text(group.first().sourceLabel, Modifier.identity(), fontFamily = Tech.Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${group.size}", fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted)
                        }
                    }
                    items(group, key = { it.key }) { target ->
                        WatchRow(target, view, actions, watchTargetOpenable(target, guestPresent)) { onOpen(target) }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight(), style = visibleScrollbarStyle())
        }
    }
}

@Composable private fun WatchRow(target: WatchTargetView, view: WatcherView, actions: WatcherActions, openable: Boolean, onOpen: () -> Unit) {
    val unavailable = target.availability is WatchTargetState.Unavailable
    val pending = target.availability == WatchTargetState.Pending
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val status = when {
        unavailable -> "unavailable"
        pending -> "waiting"
        else -> target.status?.ifBlank { null } ?: "unknown"
    }
    Column(Modifier.fillMaxWidth().hoverable(interaction)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 620.dp
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).background(if (hovered) Tech.Raised.copy(alpha = .55f) else Tech.Panel)
                .drawBehind { if (target.incident) drawRect(Tech.Red, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height)) }
                .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // The identity part dims when the target is gone; the actions keep full strength so Unwatch reads as available.
                Row(Modifier.weight(1f).alpha(if (unavailable) .55f else 1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.width(34.dp)) { IdentityTile(if (target.kind == WatchTargetKind.Node) "NODE" else target.guestKind ?: "VM", target.template) }
                    if (!narrow) Text(target.vmid?.toString() ?: "", Modifier.width(44.dp).identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted, maxLines = 1)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f, fill = false)) {
                                HelpTip(target.name.ifBlank { "Unnamed" }, identity = true) {
                                    Text(target.name.ifBlank { target.vmid?.let { "Guest $it" } ?: "Unnamed" }, Modifier.identity(), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (narrow && target.vmid != null) Text("${target.vmid}", Modifier.identity(), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted)
                        }
                        val where = listOfNotNull(target.node?.takeIf { it.isNotBlank() })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (where.isNotEmpty()) Text(where.joinToString(), Modifier.identity(), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1)
                            Text(watchStatusLine(target, view.now), fontFamily = Tech.Mono, fontSize = 10.sp, color = if (unavailable) Tech.Amber else Tech.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        }
                    }
                    if (target.unread > 0) HelpTip("${target.unread} unread notification${if (target.unread == 1) "" else "s"}") {
                        Box(Modifier.background(Tech.Amber, Tech.Control).padding(horizontal = 5.dp, vertical = 1.dp)) {
                            Text("${target.unread}", fontSize = 9.sp, color = Tech.OnAccent, fontWeight = FontWeight.Bold, fontFamily = Tech.Mono)
                        }
                    }
                    if (target.incident) NoticeTag("Incident", Tech.Red)
                    Box(Modifier.widthIn(min = if (narrow) 0.dp else 104.dp)) {
                        if (target.kind == WatchTargetKind.Guest && !unavailable && !pending) ActivityStatusTag(status, target.healthy, target.resource)
                        else StatusTag(status, healthy = target.healthy && !unavailable && !pending)
                    }
                }
                if (openable) MiniAction("Open", onClick = onOpen)
                else HelpTip(when { unavailable -> "Not available now"; pending -> "Not read yet"; else -> "Not in the connection in view" }) { MiniAction("Open", enabled = false) {} }
                actions.onUnwatch?.let { unwatch -> MiniAction("Unwatch") { unwatch(target.key) } }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tech.Edge.copy(alpha = .45f)))
    }
}
