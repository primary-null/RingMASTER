package local.ringleader.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import local.ringleader.core.*

internal enum class SettingsSection(val label: String) {
    Appearance("Appearance"), Size("Size and text"), Motion("Motion and refresh"), Browser("Browser access"), Updates("Updates"), About("About")
}

@Composable internal fun SettingsDialog(state: WorkspaceState, workspace: Workspace, section: SettingsSection,
    onSection: (SettingsSection) -> Unit, onClose: () -> Unit, onWiped: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SettingsSheet(state, workspace, section, onSection, onClose, onWiped,
                Modifier.size(minOf(900.dp, maxWidth * .92f), minOf(640.dp, maxHeight * .92f)))
        }
    }
}

/** Shared by the modal and the offline probe: no native window or live connection required. */
@Composable internal fun SettingsSheet(state: WorkspaceState, workspace: Workspace, section: SettingsSection,
    onSection: (SettingsSection) -> Unit, onClose: () -> Unit, onWiped: () -> Unit, modifier: Modifier = Modifier, initialScroll: Int = 0, updatesChecker: UpdateChecker? = null, updatePreview: UpdateState? = null) {
    val web = LocalAppHost.current.web
    val selected = if (web && section == SettingsSection.Browser) SettingsSection.Appearance else section
    Surface(modifier, color = ringColors.surface, contentColor = Tech.Ink, shape = Tech.Plate, border = BorderStroke(1.dp, Tech.Edge)) {
        Column {
            Row(Modifier.fillMaxWidth().background(Tech.Raised).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("RINGMASTER / PREFERENCES", fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted)
                }
                MiniAction("Done", primary = true, onClick = onClose)
            }
            Row(Modifier.weight(1f)) {
                Column(Modifier.width(204.dp).fillMaxHeight().background(Tech.Black).verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsSection.entries.filter { !web || it != SettingsSection.Browser }.forEach { item ->
                        val active = selected == item
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).border(1.dp, if (active) Tech.Amber else Tech.Edge, Tech.Control)
                            .background(if (active) Tech.AmberWash else Tech.Panel, Tech.Control)
                            .selectable(active, role = Role.Tab, onClick = { onSection(item) }).heightIn(min = 52.dp)) {
                            Box(Modifier.width(3.dp).fillMaxHeight().background(if (active) Tech.Amber else Tech.Edge))
                            Text(item.label.uppercase(), Modifier.padding(12.dp), fontSize = 11.sp, fontFamily = Tech.Mono,
                                color = if (active) Tech.Amber else Tech.Ink, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                key(selected) {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState(initialScroll)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SectionLabel(selected.label)
                        when (selected) {
                            SettingsSection.Appearance -> AppearanceSettingsSection()
                            SettingsSection.Size -> SizeSettingsSection()
                            SettingsSection.Motion -> MotionSettingsSection(state, workspace)
                            SettingsSection.Browser -> BrowserSettingsSection()
                            SettingsSection.Updates -> UpdatesSettings(updatesChecker ?: DesktopUpdates.checker, updatePreview)
                            SettingsSection.About -> AboutSettingsSection(onWiped)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun MotionSettingsSection(state: WorkspaceState, workspace: Workspace) {
    val prefs = remember { MotionPreferences() }
    val reduce = LocalReduceMotion.current
    val setReduce = LocalSetReduceMotion.current
    SettingsToggle("Reduce motion", "Keep transitions and decorative motion still.", reduce, setReduce)
    SettingsToggle("Live data", "Refresh the connected workspace automatically.", state.live) {
        workspace.setLive(it); prefs.put("live", it)
    }
    SectionLabel("Refresh interval")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LIVE_INTERVAL_CHOICES.forEach { seconds ->
            MiniAction(liveIntervalLabel(seconds), primary = state.liveIntervalSeconds == seconds) {
                workspace.setLive(true, seconds); prefs.put("live", true); prefs.putNumber("liveInterval", seconds)
            }
        }
    }
    Text("Choosing an interval enables live data. Refresh pauses while the window is minimised.", fontSize = 12.sp, color = Tech.Muted)
}

@Composable private fun SettingsToggle(title: String, description: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TechSwitch(value, onChange)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 14.sp)
            Text(description, fontSize = 11.sp, color = Tech.Muted)
        }
    }
}
