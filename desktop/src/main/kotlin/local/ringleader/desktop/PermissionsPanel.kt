package local.ringleader.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ringleader.core.FeatureCheck
import local.ringleader.core.FeatureStatus
import local.ringleader.core.GROUP_PLANNED
import local.ringleader.core.GROUP_TODAY
import local.ringleader.core.PDM_KIND
import local.ringleader.core.PermissionIdentity
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState
import local.ringleader.core.evaluateFeatures
import local.ringleader.core.featureStatusCounts
import local.ringleader.core.permissionTargets

/**
 * What the signed-in identity may do, feature by feature. Reads `GET /access/permissions` once when first shown and on Refresh.
 * Each row names the privilege and path Proxmox checks, a status tag, and for anything not allowed one line saying why.
 *
 * Host it where there is room for about 520dp (a dialog or a page); below that the rows stack. It needs no fixed height.
 * [framed] draws it as a Tech panel. Pass false inside a [TechDialog], which already has a frame.
 */
@Composable fun PermissionsPanel(state: WorkspaceState, workspace: Workspace, modifier: Modifier = Modifier, framed: Boolean = true) {
    val section = state.permissions
    LaunchedEffect(state.connectionEpoch, state.connected, state.demo) {
        if ((state.connected || state.demo) && section.value == null && !section.loading && section.error == null) workspace.loadPermissions()
    }
    val direct = state.kind != PDM_KIND
    val checks = remember(section.value?.dump, state.overview, direct) {
        evaluateFeatures(section.value?.dump, permissionTargets(state.overview), direct)
    }
    val counts = featureStatusCounts(checks)
    // TechPanel measures its content's intrinsic height, which BoxWithConstraints cannot answer, so the width is read after layout.
    var width by remember { mutableStateOf(0) }
    val wide = with(LocalDensity.current) { width.toDp() } >= 520.dp
    val body: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // A dialog already carries the title, so there the counts take the label's place.
            if (framed) SectionLabel("Permissions")
            else Text(section.value?.let { statusSummary(counts) } ?: "Not read yet", fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Ink)
            Spacer(Modifier.weight(1f))
            when (section.value?.identity) {
                PermissionIdentity.Token -> NeutralTag("API token", help = "Read for the API token this session signed in with.")
                PermissionIdentity.User -> NeutralTag("User", help = "Read for the user this session signed in as.")
                else -> {}
            }
            MiniAction("Refresh", enabled = !section.loading && direct && (state.connected || state.demo)) { workspace.loadPermissions() }
        }
        if (framed && section.value != null) Text(statusSummary(counts), Modifier.padding(top = 2.dp), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Ink)
        ReadNotices(section, Modifier.padding(top = 4.dp))
        Text(
            "Read from the server for this identity. A path inherits a privilege from its parents only when it was set with Propagate. " +
                "For a token with separate privileges Proxmox already shows the intersection of the token's and its owner's rights.",
            Modifier.padding(top = 4.dp, bottom = 6.dp), fontSize = 11.sp, lineHeight = 15.sp, color = Tech.Muted,
        )
        Column(Modifier.fillMaxWidth().onSizeChanged { width = it.width }) {
            listOf(GROUP_TODAY, GROUP_PLANNED).forEach { group ->
                val rows = checks.filter { it.requirement.group == group }
                if (rows.isNotEmpty()) {
                    Text(group.uppercase(), Modifier.padding(top = 8.dp, bottom = 2.dp), fontFamily = Tech.Mono, fontSize = 9.sp, letterSpacing = 1.sp, color = Tech.Muted)
                    rows.forEach { FeatureRow(it, wide) }
                }
            }
        }
    }
    if (framed) TechPanel(modifier.fillMaxWidth(), rail = Tech.Amber, content = body) else Column(modifier.fillMaxWidth(), content = body)
}

private fun statusSummary(counts: Map<FeatureStatus, Int>): String = listOf(
    FeatureStatus.Allowed to "allowed", FeatureStatus.Partial to "partial", FeatureStatus.Missing to "missing", FeatureStatus.Unknown to "unknown",
).filter { (counts[it.first] ?: 0) > 0 }.joinToString(" · ") { "${counts[it.first]} ${it.second}" }

internal fun permissionTone(status: FeatureStatus): Color = when (status) {
    FeatureStatus.Allowed -> Tech.Green
    FeatureStatus.Partial -> Tech.Amber
    FeatureStatus.Missing -> Tech.Red
    FeatureStatus.Unknown -> Tech.Muted
}

@Composable private fun StatusChip(status: FeatureStatus) = when (status) {
    FeatureStatus.Allowed -> HealthyTag("Allowed")
    FeatureStatus.Partial -> AttentionTag("Partial")
    FeatureStatus.Missing -> ProblemTag("Missing")
    FeatureStatus.Unknown -> NeutralTag("Unknown")
}

@Composable private fun FeatureRow(check: FeatureCheck, wide: Boolean) {
    val requirement = check.requirement
    val help = listOfNotNull(requirement.api, requirement.note).joinToString("\n")
    Column(
        Modifier.fillMaxWidth().drawBehind { drawLine(Tech.Edge.copy(alpha = .6f), Offset(0f, size.height), Offset(size.width, size.height), 1f) }.padding(vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (wide) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { HelpTip(help) { FeatureTitle(requirement.title) } }
                Column(Modifier.weight(1.35f)) { check.needs.forEach { NeedLine(it) } }
                Box(Modifier.width(72.dp), contentAlignment = Alignment.CenterEnd) { StatusChip(check.status) }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { HelpTip(help) { FeatureTitle(requirement.title) } }
                StatusChip(check.status)
            }
            check.needs.forEach { NeedLine(it) }
        }
        if (check.status != FeatureStatus.Allowed) {
            Text(check.summary, Modifier.identity(), fontSize = 10.5.sp, lineHeight = 14.sp, color = permissionTone(check.status), maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun FeatureTitle(title: String) =
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable private fun NeedLine(text: String) =
    Text(text, fontFamily = Tech.Mono, fontSize = 10.sp, lineHeight = 13.sp, color = Tech.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
