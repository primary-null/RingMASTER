package local.ringleader.desktop

import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.input.pointer.pointerHoverIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ringleader.core.platformLabel
import java.nio.file.Path

/** Version, where things live, licences, and the deliberate wipe. [onWiped] runs after Clear everything (the app quits). */
@Composable internal fun AboutSettingsSection(onWiped: () -> Unit) {
    val host = LocalAppHost.current
    var notices by remember { mutableStateOf<String?>(null) }
    var wiping by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("RingMASTER ${AppVersion.value}", fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Tech.Ink)
        Text("Desktop client for Proxmox VE and Proxmox Datacenter Manager", color = Tech.Muted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.foundation.Image(AppIcon.large, contentDescription = null, modifier = Modifier.size(56.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Fact("VERSION", AppVersion.value)
                Fact("PLATFORM", remember { platformLabel() })
                Fact("RUNTIME", "Java ${System.getProperty("java.version")} · ${System.getProperty("os.arch")}")
                Appearance.display?.let { Fact("DISPLAY", it.summary) }
                ProjectLinkRow()
            }
        }
        HorizontalDivider(color = Tech.Edge)
        SectionLabel("Stored on this computer")
        Place("Settings and connections", AppData.configDir, host.web)
        Place("Configuration backups", AppData.configDir.resolve("backups"), host.web)
        Place("Caches", AppData.cacheDir, host.web)
        Text("RingMASTER never writes a secret to disk. A connection asks for its secret when it opens, unless you chose Remember for it: then your password vault keeps it.", fontSize = 11.sp, color = Tech.Muted)
        HorizontalDivider(color = Tech.Edge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Licences", Modifier.weight(1f))
            MiniAction(if (notices == null) "Show notices" else "Hide notices") { notices = if (notices == null) AppData.notices() ?: "The notices ship with the installed app (THIRD-PARTY-NOTICES.txt in its resources folder)." else null }
        }
        Text("RingMASTER is open source under the Apache License 2.0. Built with Kotlin, Compose Multiplatform, Skiko, Ktor, JCEF and JediTerm (LGPL; replaceable, see the notices).", fontSize = 11.sp, color = Tech.Muted)
        notices?.let {
            SelectionContainer {
                Text(it, Modifier.fillMaxWidth().heightIn(max = 260.dp).border(1.dp, Tech.Edge, Tech.Control).verticalScroll(rememberScrollState()).padding(10.dp),
                    fontFamily = Tech.Mono, fontSize = 10.sp, lineHeight = 14.sp, color = Tech.Ink)
            }
        }
    }
    if (!host.web) MiniAction("Clear everything…") { wiping = true }
    if (wiping) ClearEverythingDialog({ wiping = false }, onWiped)
}

/** The project's home on GitHub: opens in the system browser; in browser mode it is plain selectable text. */
@Composable private fun ProjectLinkRow() {
    val web = LocalAppHost.current.web
    val scope = rememberCoroutineScope()
    var browserNote by remember { mutableStateOf<String?>(null) }
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("PROJECT", Modifier.width(100.dp), fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = .8.sp, color = Tech.Muted)
        if (web) SelectionContainer { Text(PROJECT_URL, fontFamily = Tech.Mono, fontSize = 12.sp, color = Tech.Amber) }
        else HelpTip(browserNote ?: "Open $PROJECT_URL in your browser") {
            Text(PROJECT_URL.removePrefix("https://") + "  ↗",
                Modifier.hoverable(interaction).pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                        scope.launch {
                            browserNote = when (kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { openInBrowser(PROJECT_URL) }) {
                                BrowserLaunch.Failed -> {
                                    runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(PROJECT_URL), null) }
                                    "No browser opened, so the link was copied."
                                }
                                BrowserLaunch.Pending -> "Browser launch still running."
                                BrowserLaunch.Accepted -> null
                            }
                        }
                    },
                fontFamily = Tech.Mono, fontSize = 12.sp, color = Tech.Amber,
                textDecoration = if (hovered) androidx.compose.ui.text.style.TextDecoration.Underline else null)
        }
    }
}

@Composable private fun Fact(label: String, value: String) = Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Text(label, Modifier.width(100.dp), fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = .8.sp, color = Tech.Muted)
    Text(value, Modifier.weight(1f), fontFamily = Tech.Mono, fontSize = 12.sp, color = Tech.Ink)
}

@Composable private fun Place(label: String, path: Path, web: Boolean) {
    var failed by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 12.sp, color = Tech.Ink)
            SelectionContainer { Text(if (failed) "Could not open $path" else path.toString(), Modifier.identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = if (failed) Tech.Amber else Tech.Muted, maxLines = 2) }
        }
        if (!web) MiniAction("Open") { failed = !AppData.open(path) }
    }
}

/** The held-back wipe: lists what goes, keeps plugins, and only runs after CLEAR is typed. */
@Composable private fun ClearEverythingDialog(onCancel: () -> Unit, onWiped: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf<List<String>?>(null) }
    var clearing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lines = remember {
        val name = runCatching { SystemVault.instance.name }.getOrNull()
        AppData.removes.map { if (it == AppData.REMEMBERED) AppData.rememberedLine(name) else it }
    }
    val cancel = remember { androidx.compose.ui.focus.FocusRequester() }
    // A confirmation over Settings: a click outside does nothing, and the first Escape closes only this dialog.
    TechDialog("Clear everything?", onCancel, width = 520.dp, subtitle = "RingMASTER quits afterwards and starts fresh next time.", initialFocus = cancel, actions = {
        TechAction("Clear and quit", active = true, enabled = typed == "CLEAR" && failed == null && !clearing) {
            clearing = true
            // Off the UI thread: forgetting remembered secrets can wait on the password safe.
            scope.launch {
                val left = withContext(Dispatchers.IO) { AppData.clearAll() }
                if (left.isEmpty()) onWiped() else {
                    failed = left
                    clearing = false
                }
            }
        }
        TechAction("Cancel", Modifier.focusRequester(cancel), onClick = onCancel)
    }) {
        lines.forEach { Text("·  $it", fontSize = 13.sp, color = Tech.Ink) }
        Text(AppData.KEPT_NOTE, fontSize = 12.sp, color = Tech.Muted)
        TechField(typed, { typed = it.uppercase().take(5) }, "Type CLEAR to confirm", placeholder = "CLEAR")
        if (clearing) Text("Clearing…", color = Tech.Muted, fontSize = 12.sp)
        failed?.let { left ->
            Text("Some items could not be removed. Close other RingMASTER windows and try again, or delete them by hand:", color = Tech.Red, fontSize = 12.sp)
            left.forEach { Text(it, Modifier.identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Red) }
        }
    }
}
