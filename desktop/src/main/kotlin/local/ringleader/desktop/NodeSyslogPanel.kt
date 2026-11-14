package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState
import local.ringleader.core.filterSyslog

/**
 * A node's system log (`GET /nodes/{node}/syslog`, read-only), newest lines at the bottom. Opens on the newest 500 lines.
 * While this panel is on screen and Follow is on, it reads the tail. Leaving the bottom turns Follow off. Returning to the
 * bottom turns it on. "Earlier" adds the page before the oldest line shown. [service] limits the log to one unit.
 * Leaving this panel drops the buffered lines.
 *
 * The list needs a bounded height, so [height] is fixed (the panel can sit in a scrolling inspector).
 */
@Composable fun NodeSyslogPanel(
    state: WorkspaceState, workspace: Workspace, node: String, service: String? = null, modifier: Modifier = Modifier, height: Dp = 320.dp,
    onClearService: (() -> Unit)? = null,
) {
    val section = state.nodeSyslog
    val view = section.value?.takeIf { it.node == node && it.service == service }
    // Same pause as live refresh: an iconified main window stops the 5 s tail read. Restoring it sets the panel visible again, which catches up once.
    val iconified = rememberWindowIconified()
    DisposableEffect(iconified) {
        workspace.setSyslogVisible(!iconified)
        onDispose { workspace.setSyslogVisible(false) }
    }
    DisposableEffect(Unit) {
        onDispose { workspace.closeNodeSyslog() }
    }
    LaunchedEffect(node, service, state.connectionEpoch) {
        val held = state.nodeSyslog.value
        if (held == null || held.node != node || held.service != service) workspace.openNodeSyslog(node, service)
    }
    var query by remember(node, service) { mutableStateOf("") }
    val shown = remember(view?.lines, query) { view?.let { filterSyslog(it.lines, query) }.orEmpty() }
    val following = view?.following == true
    val reduce = LocalReduceMotion.current
    val listState = rememberLazyListState()
    val followingNow = rememberUpdatedState(following)
    val ownScroll = remember { mutableStateOf(false) }
    var placed by remember(node, service) { mutableStateOf(false) }
    val placedNow = rememberUpdatedState(placed)
    LaunchedEffect(shown.size, shown.lastOrNull()?.n, following) {
        if (shown.isEmpty() || (!following && placed)) return@LaunchedEffect
        ownScroll.value = true
        try {
            if (reduce || !placed) listState.scrollToItem(shown.lastIndex) else listState.animateScrollToItem(shown.lastIndex)
            placed = true
        } finally { ownScroll.value = false }
    }
    // At the bottom means Follow. Leaving the bottom turns it off. Coming back turns it on. The first layout and our own scroll do not count.
    LaunchedEffect(listState) {
        var wasAtBottom = true
        var primed = false
        snapshotFlow { listState.canScrollForward }
            .distinctUntilChanged()
            .collect { canScrollForward ->
                val atBottom = !canScrollForward
                if (!primed || !placedNow.value || ownScroll.value) {
                    primed = true
                    wasAtBottom = atBottom
                    return@collect
                }
                if (atBottom != wasAtBottom && atBottom != followingNow.value) workspace.setSyslogFollowing(atBottom)
                wasAtBottom = atBottom
            }
    }
    val clipboard = LocalClipboardManager.current
    val display = remember(shown, node) { shown.joinToString("\n") { syslogDisplayText(it.text, node) } }
    val tones = remember(shown) { shown.associate { it.n to syslogTone(it.text) } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("Log", suffix = service)
            if (service != null && onClearService != null) MiniAction("×") { onClearService() }
            Spacer(Modifier.weight(1f))
            MiniAction(if (following) "Following" else "Follow", primary = following, enabled = view != null) { workspace.setSyslogFollowing(!following) }
            MiniAction("Refresh", enabled = view != null && !section.loading) { workspace.refreshNodeSyslog() }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniAction("Earlier", enabled = view?.canLoadEarlier == true && !section.loading) { workspace.loadEarlierSyslog() }
            MiniAction("Copy", enabled = shown.isNotEmpty()) { clipboard.setText(AnnotatedString(display)) }
            if (!LocalAppHost.current.web) MiniAction("Save…", enabled = shown.isNotEmpty()) { saveSyslog(node, display) }
            Spacer(Modifier.weight(1f))
            if (view != null) Text(lineCount(shown.size, view.lines.size, view.total), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1)
        }
        TechField(query, { query = it }, "Filter the lines on screen", placeholder = "Text to find")
        Box(Modifier.fillMaxWidth().height(height).border(1.dp, Tech.Edge, Tech.Control).background(Tech.Black.copy(alpha = .35f))) {
            when {
                // The reason for a failure is shown once, under the box (see ReadNotices).
                view == null -> Text(
                    when { section.loading -> "Reading the log…"; section.error != null -> "No log lines were read."; else -> "—" },
                    Modifier.padding(10.dp), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted,
                )
                shown.isEmpty() -> Text(
                    if (query.isNotBlank()) "No line on screen contains that text." else "The node's log is empty.",
                    Modifier.padding(10.dp), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted,
                )
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(shown, key = { it.n }) { line -> LogRow(syslogDisplayText(line.text, node), tones[line.n] ?: SyslogTone.Plain) }
                }
            }
        }
        view?.notice?.let { Text(it, fontSize = 10.sp, lineHeight = 13.sp, color = Tech.Amber) }
        ReadNotices(section)
        if (view != null) Text("UPDATED ${readClock(section.updatedAt)}", fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted)
    }
}

private fun lineCount(shown: Int, held: Int, total: Int): String {
    val fmt = NumberFormat.getIntegerInstance(Locale.ROOT)
    return if (shown == held) "${fmt.format(held)} of ${fmt.format(total)} lines" else "${fmt.format(shown)} match · ${fmt.format(held)} on screen"
}

internal enum class SyslogTone { Error, Warning, Plain }

private val monthStamp = Regex("""^(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+\d{1,2}(?:\s+\d{2}:\d{2}:\d{2})?(?:\s+\S+)?\s+""")
private val hostLine = Regex("""^((?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+\d{1,2}\s+\d{2}:\d{2}:\d{2})\s+(\S+)\s+(.*)$""")

// Whole words only: an underscore is a word character, so a SMART attribute such as Raw_Read_Error_Rate does not count as an error.
private val errorWords = Regex(
    """\b(error|errors|fail|failed|failure|failures|crit|critical|panic|fatal|segfault|denied|unable to|oom|oom-kill|out of memory|hung task)\b|\bBUG:|\bCall Trace\b|blocked for more than""",
    RegexOption.IGNORE_CASE,
)
private val warningWords = Regex("\\b(warn|warning|warnings|timeout|timeouts|timed out|retry|retrying)\\b", RegexOption.IGNORE_CASE)

/** Drop this node's hostname from a `-o short` line. The timestamp and the message stay. */
internal fun syslogDisplayText(text: String, node: String): String {
    val match = hostLine.matchEntire(text) ?: return text
    return if (match.groupValues[2] == node) "${match.groupValues[1]} ${match.groupValues[3]}" else text
}

/** The message after the identifier, so a hostname or unit name does not pick the colour. */
internal fun syslogMessage(text: String): String {
    val body = monthStamp.replaceFirst(text, "")
    val split = body.indexOf(": ")
    val message = if (split >= 0) body.substring(split + 2) else body
    return message.ifBlank { text }
}

/** Colour class of a journal line by plain keywords in the message. A hint for scanning, not a severity from the journal. */
internal fun syslogTone(text: String): SyslogTone = when {
    errorWords.containsMatchIn(syslogMessage(text)) -> SyslogTone.Error
    warningWords.containsMatchIn(syslogMessage(text)) -> SyslogTone.Warning
    else -> SyslogTone.Plain
}

private fun toneColor(tone: SyslogTone): Color = when (tone) {
    SyslogTone.Error -> Tech.Red
    SyslogTone.Warning -> Tech.Amber
    SyslogTone.Plain -> Tech.Ink
}

/**
 * True while the Compose window is iconified. Screenshot scenes have no window, so the panel keeps following.
 * [LocalWindowInfo.isWindowFocused] is a different signal and is not used here.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable private fun rememberWindowIconified(): Boolean {
    val window = androidx.compose.ui.awt.LocalAwtWindow.current as? java.awt.Frame
    var iconified by remember { mutableStateOf(false) }
    DisposableEffect(window) {
        if (window == null) {
            iconified = false
            return@DisposableEffect onDispose {}
        }
        fun publish(state: Int) { iconified = (state and java.awt.Frame.ICONIFIED) != 0 }
        publish(window.extendedState)
        val listener = java.awt.event.WindowStateListener { event -> publish(event.newState) }
        window.addWindowStateListener(listener)
        onDispose { window.removeWindowStateListener(listener) }
    }
    return iconified
}

private fun saveSyslog(node: String, text: String) {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Save system log", java.awt.FileDialog.SAVE)
    try {
        dialog.file = "$node-system-log.txt"
        dialog.isVisible = true
        val name = dialog.file ?: return
        java.io.File(dialog.directory, name).writeText(text)
    } finally { dialog.dispose() }
}

@Composable private fun LogRow(text: String, tone: SyslogTone) {
    Text(text.ifEmpty { " " }, Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp).identity(), fontFamily = Tech.Mono, fontSize = 10.5.sp, lineHeight = 14.sp, color = toneColor(tone))
}
