package local.ringleader.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import local.ringleader.core.taskHasWarnings
import local.ringleader.core.ClusterTask
import local.ringleader.core.PDM_ACTIVITY_EXPLANATION
import local.ringleader.core.PDM_KIND
import local.ringleader.core.PowerPhase
import local.ringleader.core.PowerSubmission
import local.ringleader.core.TaskOutcome
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState
import local.ringleader.core.powerPhaseLabel
import local.ringleader.core.powerVisibleFields
import local.ringleader.core.taskListFields
import local.ringleader.core.taskDuration
import local.ringleader.core.LOST_POWER_RESULT_ADVICE
import local.ringleader.core.lostPowerResultText
import local.ringleader.core.unresolvedActionText
import local.ringleader.core.unresolvedSubmission

private val taskClock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
internal fun activityClock(time: Instant?): String = try {
    time?.atZone(ZoneId.systemDefault())?.format(taskClock) ?: "Unavailable"
} catch (_: DateTimeException) { "Unavailable" }
private fun taskSpan(task: ClusterTask) = "Start ${activityClock(task.startedAt)} · End ${activityClock(task.endedAt)}"
private fun statusColor(outcome: TaskOutcome) = when (outcome) {
    TaskOutcome.Succeeded -> Tech.Green
    TaskOutcome.Running -> Tech.Amber
    TaskOutcome.Unsuccessful -> Tech.Red
    TaskOutcome.Unknown -> Tech.Muted
}

@Composable fun ActivityScreen(state: WorkspaceState, workspace: Workspace, sessionTitle: String?, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    // The shell owns setLiveTasks for the visible page, including minimize. This screen only reads.
    // Fetch when Activity opens or the connection changes. Later recomposition does not fetch again.
    LaunchedEffect(state.connectionEpoch) { workspace.refreshTasks() }
    ProvideMotionBook(state) {
    // The one task whose log is open can report a percentage; its row traces it along the outline.
    val openLog = state.taskLog
    val tracedProgress = remember(openLog?.lines) { openLog?.lines?.let(::taskProgress) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The 960-wide window leaves this pane under 900dp, so each task stacks. 1600dp keeps one row.
        val compact = maxWidth < 900.dp
        // Wide panes keep the table at 760dp and put the log beside it. Narrow panes cover the table.
        val split = state.taskLog != null && maxWidth >= 1280.dp
        Row(Modifier.fillMaxSize()) {
        androidx.compose.runtime.CompositionLocalProvider(LocalActivitySplit provides split) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            item {
            Column(verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tech.Space12)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(if (state.demo) "DEMONSTRATION / SAMPLE TASKS" else sessionTitle ?: state.address.substringAfter("://").trimEnd('/').ifBlank { "NO ACTIVE CONNECTION" },
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(when {
                        state.demo -> "LOCAL SAMPLE · NO SERVER IS CONTACTED"
                        state.kind == PDM_KIND -> "PDM TASK FEED IS NOT AVAILABLE"
                        state.tasksStale -> "STALE ACTIVITY · LAST SUCCESS ${activityClock(state.tasksUpdatedAt)}"
                        else -> "SYNCED ${activityClock(state.tasksUpdatedAt)}"
                    }, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = if (state.tasksStale || state.demo || state.kind == PDM_KIND) Tech.Amber else Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                MiniAction("Sync · F5", primary = true, enabled = state.kind != PDM_KIND && (state.connected || state.demo) && !state.tasksLoading, onClick = workspace::refreshTasks)
                MiniAction("Disconnect", enabled = state.connected || state.demo, onClick = onDisconnect)
            }
            if (state.tasksLoading) TechProgress()
            if (!state.connected) state.error?.let { Text(it, color = Tech.Red, fontSize = 13.sp) }
            state.tasksError?.let { Text(it, color = Tech.Red, fontSize = 13.sp) }
            }
            }
            if (state.connected && state.lostPowerResults.isNotEmpty()) {
                item { LostPowerResults(state, workspace) }
            }
            if ((state.connected || state.demo) && state.submissions.isNotEmpty()) {
                item {
                Column(verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
                SectionLabel("Submitted actions", suffix = state.submissions.size.toString())
                Text(SUBMISSIONS_EXPLANATION, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Tech.Muted)
                unresolvedSubmission(state)?.let { Text(unresolvedActionText(it), color = Tech.Amber, fontSize = Tech.Label) }
                }
                }
                items(state.submissions, key = { "submission:${it.localId}" }) { submission ->
                    SubmissionRow(submission, state.submissions.any { it.checking }, workspace::checkPower)
                }
            }
            val tasks = state.tasks?.sortedBy { if(it.outcome == TaskOutcome.Running) 0 else 1 }
            when {
                state.kind == PDM_KIND -> item { TechPanel(Modifier.fillMaxWidth(), rail = Tech.Amber) {
                    Text("PDM task feed is not available", fontSize = Tech.Section, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(PDM_ACTIVITY_EXPLANATION, color = Tech.Muted, fontSize = 13.sp)
                } }
                !state.connected && !state.demo -> item { TechPanel(Modifier.fillMaxWidth(), rail = Tech.Amber) {
                    Text("Open your infrastructure workspace", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Text("Connect to a Proxmox server to read its recent tasks, or explore the demo.", color = Tech.Muted)
                    Spacer(Modifier.height(18.dp))
                    TechAction("Desk", active = true, onClick = onConnect)
                } }
                tasks == null && state.tasksLoading -> item { Text("Reading recent tasks.", color = Tech.Muted) }
                tasks == null -> item { TechPanel(Modifier.fillMaxWidth(), rail = Tech.Red) {
                    Text("Activity is unavailable for this session.", fontSize = Tech.Section, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Sync tries the read again. A permission refusal does not disconnect this session.", color = Tech.Muted, fontSize = 13.sp)
                } }
                else -> {
                    item {
                    Column(verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
                    SectionLabel("Recent tasks", suffix = if (state.demo) "${tasks.size} · SAMPLE" else tasks.size.toString())
                    if (tasks.isEmpty()) Text(if (state.demo) "No sample tasks." else "No recent tasks are visible to this token.", color = Tech.Muted)
                    else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val warned = tasks.count { it.outcome == TaskOutcome.Succeeded && taskHasWarnings(it) }
                        listOf(TaskOutcome.Running to "RUNNING", TaskOutcome.Succeeded to "OK", TaskOutcome.Unsuccessful to "FAILED", TaskOutcome.Unknown to "UNKNOWN").forEach { (outcome, label) ->
                            val n = tasks.count { it.outcome == outcome }
                            // The OK count includes tasks that finished with warnings; then it is amber, not green.
                            val tint = counterColor(outcome, warned)
                            if (n > 0 || outcome != TaskOutcome.Unknown) Row(Modifier.border(1.dp, tint.copy(alpha = if (n > 0) .6f else .2f), Tech.Control).padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(label, fontSize = 9.sp, letterSpacing = 1.sp, fontFamily = Tech.Mono, color = Tech.Muted)
                                Text("$n", fontSize = 12.sp, fontFamily = Tech.Mono, fontWeight = FontWeight.SemiBold, color = if (n > 0) tint else Tech.Muted)
                                if (outcome == TaskOutcome.Succeeded && warned > 0) Text("$warned WITH WARNINGS", fontSize = 9.sp, letterSpacing = 1.sp, fontFamily = Tech.Mono, color = Tech.Amber)
                            }
                        }
                    }
                    }
                    }
                    item { Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal=10.dp), verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Spacer(Modifier.width(18.dp))
                        Text("TYPE",Modifier.width(activityCols().type),fontSize=10.sp,color=Tech.Muted,fontFamily=Tech.Mono)
                        Text("TARGET",Modifier.weight(1f),fontSize=10.sp,color=Tech.Muted,fontFamily=Tech.Mono)
                        Text("STATUS",Modifier.width(activityCols().status),fontSize=10.sp,color=Tech.Muted,fontFamily=Tech.Mono)
                        if (!activityCols().compact) Text("USER",Modifier.width(activityCols().user),fontSize=10.sp,color=Tech.Muted,fontFamily=Tech.Mono)
                        Text("STARTED",Modifier.width(activityCols().started),fontSize=10.sp,color=Tech.Muted,fontFamily=Tech.Mono)
                        Text("DURATION",Modifier.width(activityCols().duration),fontSize=10.sp,color=Tech.Muted,fontFamily=Tech.Mono)
                    } }
                    items(tasks, key = { it.identity }) { task ->
                        val opened = state.taskLog?.task?.identity == task.identity
                        TaskRow(task, compact, workspace, opened, if (opened && openLog?.status?.running != false) tracedProgress else null)
                    }
                }
            }
        }
        if (state.taskLog != null && !split) TaskLogPanel(state, workspace, Modifier.fillMaxSize())
        }
        }
        if (split) TaskLogPanel(state, workspace, Modifier.width(520.dp).fillMaxHeight())
        }
    }
    }
}

@Composable private fun SubmissionRow(submission: PowerSubmission, busy: Boolean, onCheck: (String) -> Unit) {
    val fields = powerVisibleFields(submission)
    val action = fields[0]
    val node = fields[1]
    val vmid = fields[2]
    val kind = fields[3]
    val name = fields[4]
    val status = fields[5]
    val color = when (submission.phase) {
        PowerPhase.Succeeded -> Tech.Green
        PowerPhase.Accepted -> Tech.Amber
        PowerPhase.Unsuccessful, PowerPhase.Rejected -> Tech.Red
        PowerPhase.Unknown, PowerPhase.Simulated -> Tech.Muted
    }
    // A submission that ends well glows once; one that fails nudges its status line once and keeps the red rail.
    val source = "submission:${submission.localId}"
    val motion = LocalMotion.current
    val nudge = rememberPulse()
    OnMoment(null, source) { if (it.kind == MomentKind.Failed && !motion.reduced) nudge.fire(NUDGE_MS) }
    TechPanel(Modifier.fillMaxWidth().clip(Tech.Plate).phosphorRow(null, source), rail = color) {
        Text("$action · $kind $vmid", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$name · Node $node", fontFamily = FontFamily.Monospace, fontSize = Tech.Label, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(status, Modifier.nudged(nudge), color = color, fontFamily = FontFamily.Monospace, fontSize = Tech.Label)
        if (submission.detail != powerPhaseLabel(submission)) Text(submission.detail, color = Tech.Muted, fontSize = Tech.Label)
        if (submission.checkable) TechAction("Check status", enabled = !submission.checking && !busy) { onCheck(submission.localId) }
    }
}

/** Readable names for Proxmox VE worker types (pve-manager / qemu-server / pve-container task names). Unknown types stay raw. */
private val taskTypeNames = mapOf(
    "qmstart" to "Start VM", "qmstop" to "Stop VM", "qmshutdown" to "Shut down VM", "qmreboot" to "Reboot VM", "qmreset" to "Reset VM",
    "qmsuspend" to "Suspend VM", "qmresume" to "Resume VM", "qmpause" to "Pause VM", "qmcreate" to "Create VM", "qmdestroy" to "Destroy VM",
    "qmclone" to "Clone VM", "qmconfig" to "Configure VM", "qmigrate" to "Migrate VM", "qmmove" to "Move VM disk", "qmrestore" to "Restore VM",
    "qmsnapshot" to "Create snapshot", "qmrollback" to "Roll back snapshot", "qmdelsnapshot" to "Delete snapshot", "qmtemplate" to "Convert to template",
    "vzstart" to "Start container", "vzstop" to "Stop container", "vzshutdown" to "Shut down container", "vzreboot" to "Reboot container",
    "vzcreate" to "Create container", "vzdestroy" to "Destroy container", "vzrestore" to "Restore container", "vzmigrate" to "Migrate container",
    "vzclone" to "Clone container", "vzsnapshot" to "Create snapshot", "vzrollback" to "Roll back snapshot", "vzdelsnapshot" to "Delete snapshot",
    "vztemplate" to "Convert to template", "vzdump" to "Backup", "move_volume" to "Move volume", "resize" to "Resize disk",
    "vncproxy" to "Console", "vncshell" to "Node shell", "termproxy" to "Console (terminal)", "spiceproxy" to "Console (SPICE)", "spiceshell" to "Node shell",
    "imgdel" to "Delete volume", "imgcopy" to "Copy volume", "download" to "Download", "upload" to "Upload",
    "startall" to "Start all guests", "stopall" to "Stop all guests", "migrateall" to "Migrate all guests",
    "aptupdate" to "Update package lists", "srvstart" to "Start service", "srvstop" to "Stop service", "srvrestart" to "Restart service", "srvreload" to "Reload service",
    "hamigrate" to "HA migrate", "harelocate" to "HA relocate", "hastart" to "HA start", "hastop" to "HA stop",
)
internal fun taskTypeName(type: String): String = taskTypeNames[type] ?: type
@Composable private fun TaskRow(task: ClusterTask, compact: Boolean, workspace: Workspace, opened: Boolean, progress: Float? = null) {
    val (_, node, subject, user, status) = taskListFields(task)
    val color = if (taskHasWarnings(task)) Tech.Amber else statusColor(task.outcome)
    val interaction = remember(task.identity) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        if (opened) Tech.Raised else if (hovered) Tech.Raised.copy(alpha = .55f) else Color.Transparent,
        LocalMotion.current.fade(MotionDur.Quick),
    )
    val source = "task:${task.upid}"
    Column {
        // Success blooms once; a failed task keeps a red edge; a task whose log reports a percentage traces it along the outline.
        Row(Modifier.phosphorRow(null, source, failedEdge = task.outcome == TaskOutcome.Unsuccessful).progressTrace(progress, 8f)
            .fillMaxWidth().height(36.dp).background(background).hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) { workspace.openTaskLog(task) }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal=10.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            TaskPip(task.outcome, source)
            HelpTip(task.type) { Text(taskTypeName(task.type),Modifier.width(activityCols().type),color=if(task.outcome == TaskOutcome.Unsuccessful) Tech.Red else Tech.Ink,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis) }
            Text("$subject · $node",Modifier.weight(1f).identity(),fontFamily=Tech.Mono,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            HelpTip(status) { Text(status,Modifier.width(activityCols().status),fontFamily=Tech.Mono,fontSize=10.sp,color=if(task.outcome == TaskOutcome.Succeeded && !taskHasWarnings(task)) Tech.Muted else color,maxLines=1,overflow=TextOverflow.Ellipsis) }
            if (!activityCols().compact) Text(user,Modifier.width(activityCols().user).identity(),fontFamily=Tech.Mono,fontSize=10.sp,color=Tech.Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
            TaskStarted(task)
            TaskDurationText(task, color)
        }
        // The breathing pip is the one working indicator, so the line under a running task is a still hairline.
        if(task.outcome==TaskOutcome.Running) Box(Modifier.fillMaxWidth().height(2.dp).background(Tech.Amber.copy(alpha = .35f)))
        androidx.compose.material3.HorizontalDivider(color=Tech.Edge,thickness=1.dp)
    }
}
@Composable private fun TaskStarted(task: ClusterTask) {
    val now = LocalNow.current ?: Instant.now()
    HelpTip(taskSpan(task)) { Text(local.ringleader.core.dateAndAge(task.startedAt?.epochSecond, now).let { if (activityCols().compact) it.substringAfter(" · ") else it },Modifier.width(activityCols().started),fontFamily=Tech.Mono,fontSize=10.sp,color=Tech.Muted,maxLines=1) }
}

@Composable private fun TaskDurationText(task: ClusterTask, color: androidx.compose.ui.graphics.Color) {
    Text(activityElapsed(task.startedAt, task.endedAt, LocalNow.current ?: Instant.now()),Modifier.width(activityCols().duration),fontFamily=Tech.Mono,fontSize=10.sp,color=color,maxLines=1)
}

/**
 * Elapsed time for one task row. A running task is measured against this computer's clock; when that clock is behind
 * the node's the difference is negative, so the row says Unavailable instead of a misleading 0s.
 */
internal fun activityElapsed(startedAt: Instant?, endedAt: Instant?, now: Instant): String {
    val start = startedAt ?: return "—"
    val seconds = java.time.Duration.between(start, endedAt ?: now).seconds
    return if (seconds < 0) "Unavailable" else taskDuration(seconds)
}

/** Counter tint. OK is green only when no task in it finished with warnings. */
internal fun counterColor(outcome: TaskOutcome, warned: Int): Color =
    if (outcome == TaskOutcome.Succeeded && warned > 0) Tech.Amber else statusColor(outcome)

internal const val SUBMISSIONS_EXPLANATION =
    "Accepted is not finished. RingMASTER reads the status of each accepted task until it stops. Check status reads it again now. A task is complete only when its status is stopped and its exit status is OK."

/** Actions from an earlier session to this server whose result was lost. Dismiss only hides the note; nothing is sent. */
@Composable internal fun LostPowerResults(state: WorkspaceState, workspace: Workspace) {
    TechPanel(Modifier.fillMaxWidth(), rail = Tech.Amber) {
        SectionLabel("Result not confirmed", suffix = state.lostPowerResults.size.toString())
        Spacer(Modifier.height(6.dp))
        state.lostPowerResults.forEach { item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tech.Space12)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(lostPowerResultText(item), color = Tech.Amber, fontSize = Tech.Label)
                    Text("$LOST_POWER_RESULT_ADVICE Noted ${activityClock(item.at)}.", color = Tech.Muted, fontSize = 11.sp)
                }
                MiniAction("Dismiss") { workspace.dismissLostPowerResult(item.id) }
            }
        }
    }
}

private data class ActivityColumns(val type: androidx.compose.ui.unit.Dp, val status: androidx.compose.ui.unit.Dp, val user: androidx.compose.ui.unit.Dp,
    val started: androidx.compose.ui.unit.Dp, val duration: androidx.compose.ui.unit.Dp, val compact: Boolean)
private val WideActivity = ActivityColumns(190.dp, 150.dp, 130.dp, 230.dp, 70.dp, false)
private val CompactActivity = ActivityColumns(150.dp, 110.dp, 0.dp, 96.dp, 60.dp, true)
/** Narrow windows drop the user column and show only the age, so the target column keeps its room. */
/** True while the task log panel shares the row with the table, so the table uses its compact columns. */
private val LocalActivitySplit = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable private fun activityCols(): ActivityColumns {
    val width = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp() }
    return if (width < 1300.dp || LocalActivitySplit.current) CompactActivity else WideActivity
}
