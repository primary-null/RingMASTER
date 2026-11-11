package local.ringleader.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ringleader.core.FEATURE_REQUIREMENTS
import local.ringleader.core.NODE_TOOLS_DIRECT_ONLY
import local.ringleader.core.NODE_TOOLS_NODE_NOT_ONLINE
import local.ringleader.core.PDM_KIND
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState

internal val NODE_TOOL_TABS = listOf("Services", "Log", "Updates")

private fun requirementLabel(id: String): String = FEATURE_REQUIREMENTS.single { it.id == id }.needs.joinToString("  +  ") { it.label() }

/**
 * Read-only node tabs for the node inspector: Services, Log and Updates. Put it below the node's facts in the Fleet inspector.
 * Each tab loads when first shown. The Log tab follows only while it is the tab on screen. Nothing here changes the node.
 */
@Composable fun NodeToolsPanel(
    state: WorkspaceState, workspace: Workspace, node: String, modifier: Modifier = Modifier,
    initialTab: String = NODE_TOOL_TABS.first(), logHeight: Dp = 320.dp,
) {
    var tab by remember(node) { mutableStateOf(initialTab) }
    var logUnit by remember(node) { mutableStateOf<String?>(null) }
    val member = state.overview?.members?.firstOrNull { it.name == node }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("Node tools")
        when {
            state.kind == PDM_KIND -> Text(NODE_TOOLS_DIRECT_ONLY, fontSize = 11.sp, lineHeight = 15.sp, color = Tech.Muted)
            !state.demo && member?.status != "online" -> Text(NODE_TOOLS_NODE_NOT_ONLINE, fontSize = 11.sp, lineHeight = 15.sp, color = Tech.Muted)
            else -> {
                TechTabs(NODE_TOOL_TABS, tab, { tab = it })
                Text("Needs " + requirementLabel(when (tab) { "Log" -> "syslog"; "Updates" -> "updates"; else -> "services" }),
                    fontFamily = Tech.Mono, fontSize = 9.5.sp, color = Tech.Muted)
                when (tab) {
                    "Log" -> NodeSyslogPanel(state, workspace, node, logUnit, height = logHeight)
                    "Updates" -> NodeUpdatesPanel(state, workspace, node)
                    else -> NodeServicesPanel(state, workspace, node, onShowLog = { unit -> logUnit = unit; tab = "Log" })
                }
            }
        }
    }
}
