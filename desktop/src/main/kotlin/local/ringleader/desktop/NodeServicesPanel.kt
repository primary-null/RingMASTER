package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ringleader.core.NodeService
import local.ringleader.core.ServiceHealth
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState
import local.ringleader.core.serviceHealth

/**
 * Services of one node (`GET /nodes/{node}/services`, read-only): name, description, state and whether the unit is enabled.
 * Loads once when shown for a node and on Refresh. Pass [onShowLog] to add a "Log" action per row that opens that unit's log.
 * Needs no fixed height: the list is a plain column.
 */
@Composable fun NodeServicesPanel(
    state: WorkspaceState, workspace: Workspace, node: String, modifier: Modifier = Modifier, onShowLog: ((String) -> Unit)? = null,
) {
    val section = state.nodeServices
    val view = section.value?.takeIf { it.node == node }
    LaunchedEffect(node, state.connectionEpoch) {
        if (state.nodeServices.value?.node != node) workspace.loadNodeServices(node)
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Services", suffix = view?.let { "${it.services.size}" })
            Spacer(Modifier.weight(1f))
            MiniAction("Refresh", enabled = !section.loading) { workspace.loadNodeServices(node) }
        }
        view?.let { serviceProblems(it.services) }?.let { (text, failed) ->
            Text(text, fontFamily = Tech.Mono, fontSize = 10.5.sp, color = if (failed) Tech.Red else Tech.Amber)
        }
        ReadNotices(section)
        when {
            view == null -> if (!section.loading && section.error == null) Text("—", color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 11.sp)
            view.services.isEmpty() -> Text("The node listed no services.", color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 11.sp)
            else -> view.services.forEach { ServiceRow(it, onShowLog) }
        }
    }
}

/** "1 failed · 1 enabled but not running", and whether anything failed. Null when every service is fine. */
internal fun serviceProblems(services: List<NodeService>): Pair<String, Boolean>? {
    val health = services.map { serviceHealth(it) }
    val failed = health.count { it == ServiceHealth.Failed }
    val idle = health.count { it == ServiceHealth.EnabledNotRunning }
    val text = listOfNotNull(failed.takeIf { it > 0 }?.let { "$it failed" }, idle.takeIf { it > 0 }?.let { "$it enabled but not running" }).joinToString(" · ")
    return if (text.isEmpty()) null else text to (failed > 0)
}

internal fun serviceHealthLabel(health: ServiceHealth): String = when (health) {
    ServiceHealth.Running -> "Running"
    ServiceHealth.Exited -> "Exited"
    ServiceHealth.Failed -> "Failed"
    ServiceHealth.EnabledNotRunning -> "Not running"
    ServiceHealth.Stopped -> "Stopped"
    ServiceHealth.NotInstalled -> "Not installed"
    ServiceHealth.Transitioning -> "Changing"
    ServiceHealth.Unknown -> "Unknown"
}

internal fun serviceHealthTone(health: ServiceHealth): Color = when (health) {
    ServiceHealth.Running -> Tech.Green
    ServiceHealth.Failed -> Tech.Red
    ServiceHealth.EnabledNotRunning, ServiceHealth.Transitioning -> Tech.Amber
    ServiceHealth.Exited, ServiceHealth.Stopped, ServiceHealth.NotInstalled, ServiceHealth.Unknown -> Tech.Muted
}

@Composable private fun ServiceRow(service: NodeService, onShowLog: ((String) -> Unit)?) {
    val health = serviceHealth(service)
    val tone = serviceHealthTone(health)
    val detail = "state ${service.state ?: "—"} · active ${service.activeState ?: "—"} · unit ${service.unitState ?: "—"}"
    Row(
        Modifier.fillMaxWidth().heightIn(min = 30.dp).drawBehind { drawLine(Tech.Edge.copy(alpha = .6f), Offset(0f, size.height), Offset(size.width, size.height), 1f) }.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(7.dp).background(tone))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(service.name, fontFamily = Tech.Mono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (service.description.isNotBlank()) Text(service.description, fontSize = 10.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TechTag(serviceHealthLabel(health), tone, help = detail)
        service.unitState?.takeIf { it != "enabled" }?.let { Text(it, fontFamily = Tech.Mono, fontSize = 9.sp, color = Tech.Muted, maxLines = 1) }
        if (onShowLog != null) Box(Modifier.clickable(role = Role.Button) { onShowLog(service.unit ?: service.name) }.pointerHoverIcon(PointerIcon.Hand)) {
            TechTag("Log", Tech.Muted, help = "Open this unit's log")
        }
    }
}
