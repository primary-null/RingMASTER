package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import local.ringleader.core.TaskLogLine
import local.ringleader.core.TaskLogStatus
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState
import local.ringleader.core.duration
import local.ringleader.core.taskDuration
import local.ringleader.core.taskLogChip
import local.ringleader.core.taskLogHasMore
import local.ringleader.core.taskLogText
import local.ringleader.core.taskUserLabel
import local.ringleader.core.taskLogDroppedText
import local.ringleader.core.taskLogSaveText
import local.ringleader.core.writeOwnerOnlyText

/** Task log beside the Activity table, or over it when the pane is narrower than 1280dp. Heights stay fixed as lines arrive. */
@Composable fun TaskLogPanel(state: WorkspaceState, workspace: Workspace, modifier: Modifier = Modifier) {
    val log = state.taskLog ?: return
    val reduce = LocalReduceMotion.current
    val motion = LocalMotion.current
    val windowActive = LocalWindowActive.current
    val running = log.status?.running == true
    var tick by remember(log.task.identity) { mutableStateOf(Instant.now()) }
    // The once-a-second elapsed tick is a clock, not decoration, but it still stops with the window.
    LaunchedEffect(log.task.identity, running, reduce, windowActive) {
        if (!running || reduce || !windowActive) return@LaunchedEffect
        while (isActive) {
            delay(1000)
            tick = Instant.now()
        }
    }
    val now = if (running && !reduce) tick else (LocalNow.current ?: Instant.now())
    val started = log.task.startedAt
    val absolute = activityClock(started)
    val relative = started?.let { start ->
        val delta = now.epochSecond - start.epochSecond
        if (delta < 0) "in ${duration(-delta)}" else "${duration(delta)} ago"
    } ?: "Unavailable"
    val statusEnd = log.status?.endedAt
    val taskEnd = log.task.endedAt
    val end = when {
        running -> now
        statusEnd != null -> statusEnd
        taskEnd != null -> taskEnd
        else -> now
    }
    // A negative span means this computer's clock is behind the node's; taskDuration says Unavailable then.
    val elapsed = started?.let { taskDuration(java.time.Duration.between(it, end).seconds) } ?: "—"
    // A percentage the task printed into its log (backup, restore, clone...) is traced along the header outline. When the log
    // says the task just stopped, an OK blooms once (bigger the longer it was watched) and an error nudges the chip once.
    val progress = remember(log.lines) { taskProgress(log.lines) }
    val bloom = rememberPulse()
    val nudge = rememberPulse()
    val firstSeen = remember(log.task.identity) { System.nanoTime() }
    var wasRunning by remember(log.task.identity) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(log.status?.running, log.status?.exitStatus) {
        val nowRunning = log.status?.running
        val before = wasRunning
        wasRunning = nowRunning
        if (before == true && nowRunning == false && windowActive) {
            val exit = log.status?.exitStatus?.trim().orEmpty()
            if (exit == "OK" || exit.startsWith("WARNINGS", ignoreCase = true)) bloom.fireAfterglow((System.nanoTime() - firstSeen) / 1_000_000L)
            else if (!motion.reduced) nudge.fire(NUDGE_MS)
        }
    }
    val listState = rememberLazyListState()
    val following = log.following
    val followingNow = rememberUpdatedState(following)
    val ownScroll = remember { mutableStateOf(false) }
    val more = taskLogHasMore(log.lines, log.total)
    val userScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Positive y moves the pointer down and reveals earlier lines.
                if (!ownScroll.value && source == NestedScrollSource.UserInput && available.y > 0f && followingNow.value) {
                    workspace.setTaskLogFollowing(false)
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(log.lines.size, log.dropped, more, following, reduce) {
        if (!following || log.lines.isEmpty()) return@LaunchedEffect
        val notice = if (log.dropped > 0) 1 else 0
        val target = (log.lines.lastIndex + notice + if (more) 1 else 0).coerceAtLeast(0)
        ownScroll.value = true
        try {
            if (reduce) listState.scrollToItem(target) else listState.animateScrollToItem(target)
        } finally { ownScroll.value = false }
    }
    Column(modifier.fillMaxHeight().background(Tech.Canvas).border(1.dp, Tech.Edge)) {
        Column(Modifier.phosphor(bloom).progressTrace(progress.takeIf { running }, 12f).fillMaxWidth().height(148.dp).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(taskTypeName(log.task.type), Modifier.weight(1f), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MiniAction("Close") { workspace.closeTaskLog() }
            }
            Text("${log.task.subjectId.ifBlank { "—" }} · ${log.task.node}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${taskUserLabel(log.task.user)} · $absolute · $relative", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(elapsed + (progress?.takeIf { running }?.let { " · ${(it * 100f).toInt()}%" } ?: ""), Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = if (running) Tech.Amber else Tech.Ink, maxLines = 1)
                TechLogTag(log.status?.let { taskLogChip(it) } ?: "…", taskLogTint(log.status), Modifier.nudged(nudge))
            }
            Text(if (log.lines.isEmpty()) "" else log.error.orEmpty(), Modifier.height(16.dp), color = Tech.Red, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(color = Tech.Edge, thickness = 1.dp)
        Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(userScroll)) {
            if (log.lines.isEmpty()) {
                val message = log.error
                Text(
                    when {
                        log.loading -> "Reading the task log."
                        message != null -> message
                        else -> "No log lines."
                    },
                    Modifier.padding(12.dp),
                    color = if (log.error != null) Tech.Red else Tech.Muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    taskLogDroppedText(log.dropped)?.let { notice ->
                        item(key = "dropped") { Text(notice, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = Tech.Amber, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                    }
                    items(log.lines, key = { it.n }) { line -> LogLine(line) }
                    if (more) {
                        item {
                            Box(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                                MiniAction("Load more", enabled = !log.loading) { workspace.loadMoreTaskLog() }
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = Tech.Edge, thickness = 1.dp)
        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Following only means something while the task can still write lines.
            if (log.status?.running != false) MiniAction(if (following) "Following" else "Follow", primary = following) { workspace.setTaskLogFollowing(!following) }
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            MiniAction("Copy log", enabled = log.lines.isNotEmpty()) {
                clipboard.setText(AnnotatedString(taskLogText(log.lines)))
            }
            if (!LocalAppHost.current.web) MiniAction("Save…", enabled = log.lines.isNotEmpty()) { saveTaskLog(log.lines, log.dropped) }
            WebUiAction(if (state.demo || state.kind == local.ringleader.core.PDM_KIND) null else local.ringleader.core.PveWebUi.task(state.address, log.task.node, log.task.type, log.task.subjectId))
            Spacer(Modifier.weight(1f))
            if (log.loading) TechBusy()
        }
    }
}

@Composable private fun LogLine(line: TaskLogLine) {
    Row(Modifier.fillMaxWidth().heightIn(min = 18.dp).padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
        Text(line.n.toString(), Modifier.width(56.dp).padding(end = 8.dp), color = Tech.Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.End, maxLines = 1)
        SelectionContainer(Modifier.weight(1f)) {
            Text(line.text.ifEmpty { " " }, Modifier.padding(end = 8.dp), color = taskLogLineColor(line.text), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

private fun taskLogTint(status: TaskLogStatus?): Color = when {
    status == null -> Tech.Muted
    status.running -> Tech.Amber
    status.exitStatus == "OK" -> Tech.Green
    status.exitStatus?.trim()?.startsWith("WARNINGS", ignoreCase = true) == true -> Tech.Amber
    else -> Tech.Red
}

private fun taskLogLineColor(text: String): Color {
    val upper = text.uppercase()
    if (upper.contains("ERROR")) return Tech.Red
    if (upper.contains("WARN")) return Tech.Amber
    return Tech.Ink
}

/** Save the lines held, readable only by the current user (POSIX 0600, or an owner-only ACL on Windows). */
private fun saveTaskLog(lines: List<TaskLogLine>, dropped: Int) {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Save task log", java.awt.FileDialog.SAVE)
    try {
        dialog.file = "task-log.txt"
        dialog.isVisible = true
        val name = dialog.file ?: return
        writeOwnerOnlyText(java.io.File(dialog.directory, name).toPath(), taskLogSaveText(lines, dropped))
    } finally { dialog.dispose() }
}
