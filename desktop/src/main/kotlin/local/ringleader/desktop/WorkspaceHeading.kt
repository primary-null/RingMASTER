package local.ringleader.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ringleader.core.RouteTagState
import local.ringleader.core.WorkspaceState

/**
 * The heading row above every page: the session chip when several connections are open ([sessionChip], already
 * decided by the caller), the breadcrumb, the page title and, on connected session pages, the connection-route tag
 * ([route]; null hides it). The tag is measured before the title, so it stays visible in a narrow window; its label
 * shortens with the heading's width ([routeTagMode]).
 */
@Composable internal fun WorkspaceHeading(page: String, state: WorkspaceState, sessionChip: String?, route: RouteTagState? = null) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    // The session chip takes up to 260dp of the same row, so the tag shortens sooner beside it; larger text needs more room.
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val mode = routeTagMode((if (sessionChip != null) maxWidth - 140.dp else maxWidth) / fontScale)
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // With several connections open, the heading names the one in view.
            if (sessionChip != null) Text(sessionChip.uppercase(), Modifier.widthIn(max = 260.dp).border(1.dp, Tech.Amber.copy(alpha = .5f), Tech.Control).padding(horizontal = 6.dp, vertical = 2.dp).identity(),
                fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Amber, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("WORKSPACE / ${page.uppercase()}${if(page == "Fleet" && state.selected != null) " / ${state.selected!!.kind} ${state.selected!!.id}" else if(page == "Fleet" && state.selectedNode != null) " / ${state.selectedNode}" else ""}", fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.5.sp, color = Tech.Muted)
            Text(when (page) { "Fleet" -> state.selected?.name ?: state.selectedNode ?: "Fleet overview"; "Storage" -> "Storage contents"; "Activity" -> "Recent activity"; "Scan" -> "Network scan"; "Bluetooth" -> "Nearby devices"; "VPN" -> "Tunnels and VPN"; "Tools" -> "Network tools"; "Under the Hood" -> "Under the Hood"; "Schedules" -> "Schedules"; else -> "Connection desk" }, modifier=Modifier.weight(1f).then(if (page == "Fleet" && (state.selected != null || state.selectedNode != null)) Modifier.identity() else Modifier), fontSize = Tech.Title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (route != null && route != RouteTagState.Hidden) ConnectionRouteTag(route, mode)
    }
    }
}

/** Full label from 560dp of heading (per unit of text scale), one word from 380dp, the glyph alone below that. */
internal fun routeTagMode(headingWidth: Dp): RouteTagMode = when {
    headingWidth >= 560.dp -> RouteTagMode.Full
    headingWidth >= 380.dp -> RouteTagMode.Short
    else -> RouteTagMode.Glyph
}
