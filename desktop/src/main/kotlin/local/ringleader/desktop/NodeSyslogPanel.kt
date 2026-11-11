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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale
import local.ringleader.core.SyslogLine
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState
import local.ringleader.core.filterSyslog

/**
 * A node's system log (`GET /nodes/{node}/syslog`, read-only), newest lines at the bottom. Opens on the newest 500 lines.
 * While this panel is on screen and Follow is on, it reads the tail every 5 seconds; it never downloads the whole log again.
 * Scrolling up with the mouse turns Follow off. "Earlier" adds the page before the oldest line shown. [service] limits the log to one unit.
 *
 * The list needs a bounded height, so [height] is fixed (the panel can sit in a scrolling inspector).
 */
@Composable fun NodeSyslogPanel(
    state: WorkspaceState, workspace: Workspace, node: String, service: String? = null, modifier: Modifier = Modifier, height: Dp = 320.dp,
) {
    val section = state.nodeSyslog
    val view = section.value?.takeIf { it.node == node && it.service == service }
    // Same pause as live refresh: an iconified main window stops the 5 s tail read. Restoring it sets the panel visible again, which catches up once.
    val iconified = rememberWindowIconified()
    DisposableEffect(iconified) {
        workspace.setSyslogVisible(!iconified)
        onDispose { workspace.setSyslogVisible(false) }
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
    val userScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // A pointer moving down reveals earlier lines: the reader left the tail, so stop pulling them back.
                if (!ownScroll.value && source == NestedScrollSource.UserInput && available.y > 0f && followingNow.value) workspace.setSyslogFollowing(false)
                return Offset.Zero
            }
        }
    }
    var placed by remember(node, service) { mutableStateOf(false) }
    LaunchedEffect(shown.size, shown.lastOrNull()?.n, following) {
        if (shown.isEmpty() || (!following && placed)) return@LaunchedEffect
        ownScroll.value = true
        try {
            if (reduce || !placed) listState.scrollToItem(shown.lastIndex) else listState.animateScrollToItem(shown.lastIndex)
            placed = true
        } finally { ownScroll.value = false }
    }
    val clipboard = LocalClipboardManager.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("Log", suffix = service)
            Spacer(Modifier.weight(1f))
            MiniAction(if (following) "Following" else "Follow", primary = following, enabled = view != null) { workspace.setSyslogFollowing(!following) }
            MiniAction("Refresh", enabled = view != null && !section.loading) { workspace.refreshNodeSyslog() }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniAction("Earlier", enabled = view?.canLoadEarlier == true && !section.loading) { workspace.loadEarlierSyslog() }
            MiniAction("Copy", enabled = shown.isNotEmpty()) { clipboard.setText(AnnotatedString(shown.joinToString("\n") { it.text })) }
            Spacer(Modifier.weight(1f))
            if (view != null) Text(lineCount(shown.size, view.lines.size, view.total), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1)
        }
        TechField(query, { query = it }, "Filter the lines on screen", placeholder = "Text to find")
        Box(Modifier.fillMaxWidth().height(height).border(1.dp, Tech.Edge, Tech.Control).background(Tech.Black.copy(alpha = .35f)).nestedScroll(userScroll)) {
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
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState) { items(shown, key = { it.n }) { LogRow(it) } }
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

// Whole words only: an underscore is a word character, so a SMART attribute such as Raw_Read_Error_Rate does not count as an error.
private val errorWords = Regex("\\b(error|errors|fail|failed|failure|failures|crit|critical|panic|fatal|segfault|denied|unable to)\\b", RegexOption.IGNORE_CASE)
private val warningWords = Regex("\\b(warn|warning|warnings|timeout|timeouts|timed out|retry|retrying)\\b", RegexOption.IGNORE_CASE)

/** Colour class of a journal line by plain keywords. A hint for scanning, not a severity from the journal. */
internal fun syslogTone(text: String): SyslogTone = when {
    errorWords.containsMatchIn(text) -> SyslogTone.Error
    warningWords.containsMatchIn(text) -> SyslogTone.Warning
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

@Composable private fun LogRow(line: SyslogLine) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
        Text(line.n.toString(), Modifier.width(46.dp).padding(end = 6.dp), fontFamily = Tech.Mono, fontSize = 10.sp, lineHeight = 14.sp, color = Tech.Muted, textAlign = TextAlign.End, maxLines = 1)
        Text(line.text.ifEmpty { " " }, Modifier.weight(1f).padding(end = 6.dp).identity(), fontFamily = Tech.Mono, fontSize = 10.5.sp, lineHeight = 14.sp, color = toneColor(syslogTone(line.text)))
    }
}
