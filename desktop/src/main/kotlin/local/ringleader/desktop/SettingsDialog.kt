package local.ringleader.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.ui.focus.focusRequester
import local.ringleader.core.*

internal enum class SettingsSection(val label: String) {
    Appearance("Appearance"), Size("Size and text"), Motion("Motion and refresh"), Background("Background and tray"), Browser("Browser access"), Updates("Updates"), About("About")
}

@Composable internal fun SettingsDialog(state: WorkspaceState, workspace: Workspace, section: SettingsSection,
    onSection: (SettingsSection) -> Unit, onClose: () -> Unit, onWiped: () -> Unit) {
    // Instant-apply settings, so a click on the scrim closes like Done. The dialog's child is the sheet at its own size:
    // with a full-window child there would be no outside to click. The active section tab takes the keyboard on open.
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    TechModal(onClose, Dismiss.Light, ModalKind.Settings, first) {
        BoxWithConstraints {
            val size = settingsSheetSize(maxWidth, maxHeight)
            // The sheet keeps its own picture for a theme change and fades with it; the window behind has its own.
            ThemeCrossfade(Modifier.size(size.width, size.height)) {
                SettingsSheet(state, workspace, section, onSection, onClose, onWiped, Modifier.fillMaxSize(), modal = true, initialFocus = first)
            }
        }
    }
}

/** 900 x 640 at most and 92 % of the window at most: the cap always binds, the sheet never fills the window. */
internal fun settingsSheetSize(maxWidth: androidx.compose.ui.unit.Dp, maxHeight: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.DpSize {
    fun cap(limit: androidx.compose.ui.unit.Dp, available: androidx.compose.ui.unit.Dp) =
        if (available == androidx.compose.ui.unit.Dp.Infinity || available.value.isNaN()) limit else minOf(limit, available * .92f)
    return androidx.compose.ui.unit.DpSize(cap(900.dp, maxWidth), cap(640.dp, maxHeight))
}

/** Shared by the modal and the offline probe: no native window or live connection required. */
@Composable internal fun SettingsSheet(state: WorkspaceState, workspace: Workspace, section: SettingsSection,
    onSection: (SettingsSection) -> Unit, onClose: () -> Unit, onWiped: () -> Unit, modifier: Modifier = Modifier, initialScroll: Int = 0, updatesChecker: UpdateChecker? = null, updatePreview: UpdateState? = null,
    modal: Boolean = false, initialFocus: androidx.compose.ui.focus.FocusRequester? = null) {
    val web = LocalAppHost.current.web
    val selected = if (web && (section == SettingsSection.Browser || section == SettingsSection.Background)) SettingsSection.Appearance else section
    // As a modal it carries TechDialog's 12dp shadow and amber rail, so it reads as above the page on Dark and Pitch Black.
    Surface(modifier, color = ringColors.surface, contentColor = Tech.Ink, shape = Tech.Plate, border = BorderStroke(1.dp, Tech.Edge), shadowElevation = if (modal) 12.dp else 0.dp) {
        Row {
        if (modal) Box(Modifier.width(3.dp).fillMaxHeight().background(Tech.Amber))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().background(Tech.Raised).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("RINGMASTER / PREFERENCES", fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted)
                }
                MiniAction("Done", primary = true, onClick = onClose)
            }
            Row(Modifier.weight(1f)) {
                val sectionScroll = rememberScrollState()
                Box(Modifier.width(176.dp).fillMaxHeight().background(Tech.Black)) {
                Column(Modifier.fillMaxSize().padding(end=10.dp).verticalScroll(sectionScroll).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsSection.entries.filter { !web || (it != SettingsSection.Browser && it != SettingsSection.Background) }.forEach { item ->
                        val active = selected == item
                        val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        val focused by interaction.collectIsFocusedAsState()
                        // Keyboard focus draws a ring 2dp outside the row, so it shows on the amber active row too.
                        Row(Modifier.then(if (active && initialFocus != null) Modifier.focusRequester(initialFocus) else Modifier).fillMaxWidth().height(IntrinsicSize.Min).focusRing(focused, Tech.Control, gap = 2.dp).border(1.dp, if (active) Tech.Amber else Tech.Edge, Tech.Control)
                            .background(if (active) Tech.AmberWash else Tech.Panel, Tech.Control)
                            .selectable(active, interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Tab, onClick = { onSection(item) }).heightIn(min = 52.dp)) {
                            Box(Modifier.width(3.dp).fillMaxHeight().background(if (active) Tech.Amber else Tech.Edge))
                            Text(item.label.uppercase(), Modifier.padding(12.dp), fontSize = 11.sp, fontFamily = Tech.Mono,
                                color = if (active) Tech.Amber else Tech.Ink, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(sectionScroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
                key(selected) {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState(initialScroll)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SectionLabel(selected.label)
                        when (selected) {
                            SettingsSection.Appearance -> AppearanceSettingsSection()
                            SettingsSection.Size -> SizeSettingsSection()
                            SettingsSection.Motion -> MotionSettingsSection(state, workspace)
                            SettingsSection.Background -> BackgroundSettingsSection(LocalResident.current)
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
    SectionLabel("Keep consoles open")
    val keep = ConsoleKeepPreference.minutes
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0 to "Off", 5 to "5 min", 10 to "10 min", 30 to "30 min", 60 to "1 hour").forEach { (value, label) ->
            MiniAction(label, primary = keep == value) {
                ConsoleKeepPreference.choose(value)
                workspace.consoleKeepMinutes = value
            }
        }
    }
    Text("A console you leave stays connected this long, so coming back is instant. It is paused meanwhile, and at most 4 are kept per connection.", fontSize = 12.sp, color = Tech.Muted)
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
