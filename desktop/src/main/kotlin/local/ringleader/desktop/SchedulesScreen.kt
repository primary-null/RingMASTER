package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch
import local.ringleader.core.*

/** Shared wording: what "closed to the tray" and "quit" mean for schedules. Never claims an OS wake or service. */
internal const val SCHEDULE_AVAILABILITY =
    "Schedules run only while RingMASTER is running, also when its window is closed to the notification area. " +
        "After Quit, or while the computer is off or asleep, nothing runs. When RingMASTER runs again, a run due in the last 15 minutes still starts; older ones are recorded as Missed."

internal fun trayLine(status: TrayStatus, closeToTray: Boolean): String = when (status) {
    TrayStatus.Installed -> if (closeToTray) "Closing the window keeps RingMASTER running in the notification area. Quit it from the icon's menu."
        else "Closing the window quits RingMASTER. Turn on Keep running in Settings > Background and tray."
    TrayStatus.Starting -> "The notification-area icon is starting."
    is TrayStatus.Unsupported -> "Closing the window quits RingMASTER. ${status.reason}"
    is TrayStatus.Failed -> "Closing the window quits RingMASTER. ${status.reason}"
}

@Composable internal fun SchedulesScreen(resident: ResidentRuntime?, onSettings: () -> Unit) {
    SchedulesContent(resident?.engine, resident?.engineProblem ?: if (resident == null) "Schedules are available in the desktop app." else null,
        resident?.trayStatus ?: TrayStatus.Unsupported("No notification area in this mode."), resident?.closeToTray ?: false, onSettings)
}

/** The page body, separate from the runtime so scene tests can drive it with a synthetic engine. */
@Composable internal fun SchedulesContent(engine: ScheduleEngine?, engineProblem: String?, tray: TrayStatus, closeToTray: Boolean, onSettings: () -> Unit,
    now: () -> Instant = { Instant.now() }, initialEditor: Boolean = false) {
    val scope = rememberCoroutineScope()
    val state = engine?.state?.collectAsState()?.value ?: SchedulerState()
    var editing by remember { mutableStateOf<String?>(if (initialEditor) "" else null) } // "" = new
    var deleting by remember { mutableStateOf<SchedulePlan?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var logFor by remember { mutableStateOf<ScheduleRun?>(null) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val off = engine == null || state.problem != null
            SchedulePanel(Modifier.fillMaxWidth(), rail = if (off) Tech.Amber else Tech.Green) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Chip(if (off) "SCHEDULER OFF" else if (state.loaded) "SCHEDULER RUNNING" else "SCHEDULER STARTING", if (off) Tech.Amber else Tech.Green)
                        Chip(when (tray) { TrayStatus.Installed -> if (closeToTray) "TRAY: KEEP RUNNING" else "TRAY: CLOSE QUITS"; TrayStatus.Starting -> "TRAY: STARTING"; else -> "NO TRAY" },
                            if (tray == TrayStatus.Installed && closeToTray) Tech.Green else Tech.Muted)
                    }
                    (engineProblem ?: state.problem)?.let { Text(it, fontSize = 13.sp, color = Tech.Amber) }
                    Text(trayLine(tray, closeToTray), fontSize = 13.sp, color = Tech.Ink)
                    Text(SCHEDULE_AVAILABILITY, fontSize = 12.sp, color = Tech.Muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MiniAction("New schedule", primary = true, enabled = !off && state.loaded) { notice = null; editing = "" }
                        MiniAction("Background settings", onClick = onSettings)
                        if (state.unreadable && engine != null) MiniAction("Set the file aside and start empty") {
                            scope.launch { notice = engine.setAsideUnreadable() ?: "The unreadable file was kept beside the new one." }
                        }
                    }
                }
            }
            notice?.let { Text(it, fontSize = 12.sp, color = Tech.Amber) }

            SectionLabel("Background access")
            Text("A background run signs in on its own, never through an open window. It may only use a secret you already chose to remember on this computer, and only after you allow it here for that connection. API tokens only.",
                fontSize = 12.sp, color = Tech.Muted)
            state.profileProblem?.let { Text(it, fontSize = 12.sp, color = Tech.Amber) }
            if (state.access.isEmpty() && state.profileProblem == null) Text("No saved connections yet. Save one on the Desk first.", fontSize = 12.sp, color = Tech.Muted)
            state.access.forEach { access ->
                SchedulePanel(Modifier.fillMaxWidth(), rail = if (access.ready) Tech.Green else Tech.Edge, padding = 10.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(access.name, Modifier.identity(), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(access.message, fontSize = 12.sp, color = if (access.ready) Tech.Green else Tech.Muted)
                        }
                        if (engine != null && access.canGrant) MiniAction("Allow") { scope.launch { notice = engine.grantAccess(access.profileId) } }
                        if (engine != null && (access.granted || access.ready)) MiniAction("Turn off") { scope.launch { notice = engine.revokeAccess(access.profileId) } }
                    }
                }
            }

            SectionLabel("Schedules", suffix = state.document.plans.size.takeIf { it > 0 }?.toString())
            if (state.document.plans.isEmpty()) Text("No schedules yet. A schedule starts or gracefully shuts down one guest at the times you choose.", fontSize = 12.sp, color = Tech.Muted)
            val instant = now()
            state.document.plans.sortedBy { it.name.lowercase() }.forEach { plan ->
                val ready = state.readiness[plan.id] ?: PlanReadiness(ReadyLevel.Blocked, "Checking.")
                val overlaps = scheduleOverlaps(plan, state.document.plans, instant)
                PlanRow(plan, ready, overlaps, state.document.runs.filter { it.planId == plan.id }.sortedByDescending { it.due }, plan.id in expanded,
                    onToggle = { expanded = if (plan.id in expanded) expanded - plan.id else expanded + plan.id },
                    onEdit = { notice = null; editing = plan.id },
                    onPause = { scope.launch { notice = engine?.setEnabled(plan.id, !plan.enabled) } },
                    onDelete = { deleting = plan },
                    onLog = { logFor = it })
            }

            val orphans = state.document.runs.filter { r -> state.document.plans.none { it.id == r.planId } }.sortedByDescending { it.due }
            if (orphans.isNotEmpty()) {
                SectionLabel("Runs of deleted schedules")
                orphans.take(10).forEach { RunRow(it, ZoneId.systemDefault()) { logFor = it } }
            }
            Spacer(Modifier.height(8.dp))
        }
        androidx.compose.foundation.VerticalScrollbar(androidx.compose.foundation.rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
    if (editing != null && engine != null) ScheduleEditor(engine, state, state.document.plans.find { it.id == editing }, now) { saved ->
        editing = null
        if (saved != null) notice = "Saved ${saved.name}. ${state.readiness[saved.id]?.message ?: ""}".trim()
    }
    deleting?.let { plan ->
        TechDialog("Delete schedule?", { deleting = null }, subtitle = plan.name, actions = {
            MiniAction("Cancel") { deleting = null }
            MiniAction("Delete", primary = true) { scope.launch { notice = engine?.delete(plan.id) ?: "Deleted ${plan.name}."; deleting = null } }
        }) { Text("Future runs are cancelled and its finished history is removed. A run that is already sending finishes and keeps its record. Nothing is sent to the server.", fontSize = 13.sp) }
    }
    logFor?.let { run -> TaskLogDialog(engine, run) { logFor = null } }
}

/**
 * TechPanel's look without its intrinsic-height row: wrapping rows (FlowRow) measured at enlarged interface sizes were
 * clipped by the intrinsic pass. The rail is drawn behind instead of measured.
 */
@Composable private fun SchedulePanel(modifier: Modifier = Modifier, rail: Color = Tech.Edge, padding: androidx.compose.ui.unit.Dp = Tech.Space12, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(Tech.Plate).background(Tech.Panel, Tech.Plate).border(1.dp, Tech.Edge, Tech.Plate)
        .drawBehind { drawRect(rail, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
        .padding(start = padding + 3.dp, top = padding, end = padding, bottom = padding), content = content)
}

@Composable private fun Chip(text: String, color: Color) {
    Text(text, Modifier.border(1.dp, color.copy(alpha = .7f), Tech.Control).padding(horizontal = 8.dp, vertical = 3.dp),
        fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = .8.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
}

internal fun phaseColor(phase: RunPhase): Color = when (phase) {
    RunPhase.Succeeded -> Tech.Green
    RunPhase.NoChange -> Tech.Green
    RunPhase.Warnings, RunPhase.Submitted, RunPhase.Unknown, RunPhase.Missed -> Tech.Amber
    RunPhase.Failed, RunPhase.Rejected -> Tech.Red
    RunPhase.Claimed, RunPhase.Dispatching, RunPhase.Accepted -> Tech.Amber
    else -> Tech.Muted
}

private fun readyColor(level: ReadyLevel): Color = when (level) {
    ReadyLevel.Ready -> Tech.Green
    ReadyLevel.Done, ReadyLevel.Paused -> Tech.Muted
    ReadyLevel.Blocked, ReadyLevel.Review -> Tech.Amber
}

@Composable private fun PlanRow(plan: SchedulePlan, ready: PlanReadiness, overlaps: List<SchedulePlan>, runs: List<ScheduleRun>, open: Boolean,
    onToggle: () -> Unit, onEdit: () -> Unit, onPause: () -> Unit, onDelete: () -> Unit, onLog: (ScheduleRun) -> Unit) {
    SchedulePanel(Modifier.fillMaxWidth(), rail = readyColor(ready.level), padding = 10.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(plan.name, Modifier.identity(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Chip(plan.action.title.uppercase(), Tech.Ink)
                Chip(when (ready.level) { ReadyLevel.Ready -> "ON"; ReadyLevel.Done -> "DONE"; ReadyLevel.Paused -> "PAUSED"; ReadyLevel.Blocked -> "BLOCKED"; ReadyLevel.Review -> "NEEDS REVIEW" }, readyColor(ready.level))
            }
            Text("${plan.target.label} · ${repeatLabel(plan.repeat)} at ${"%02d:%02d".format(plan.time.hour, plan.time.minute)} ${plan.zone.id}",
                Modifier.identity(), fontSize = 12.sp, color = Tech.Ink)
            Text(ready.message, fontSize = 12.sp, color = if (ready.level == ReadyLevel.Ready) Tech.Muted else Tech.Amber)
            Text("Identity: ${plan.target.identity.method.label}. Authorized ${scheduleStamp(plan.authorizedAt, plan.zone)}.", fontSize = 11.sp, color = Tech.Muted)
            if (overlaps.isNotEmpty()) Text("Overlaps with ${overlaps.joinToString { it.name }} on the same guest within 10 minutes. Runs on one guest never overlap; a later one waits up to 15 minutes, then is skipped.",
                Modifier.identity(), fontSize = 12.sp, color = Tech.Amber)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MiniAction("Edit", onClick = onEdit)
                MiniAction(if (plan.enabled) "Pause" else "Resume", onClick = onPause)
                MiniAction("Delete", onClick = onDelete)
                MiniAction(if (open) "Hide history" else "History (${runs.size})", onClick = onToggle)
            }
            if (open) {
                if (runs.isEmpty()) Text("No runs yet.", fontSize = 12.sp, color = Tech.Muted)
                runs.take(12).forEach { RunRow(it, plan.zone) { onLog(it) } }
            }
        }
    }
}

@Composable private fun RunRow(run: ScheduleRun, zone: ZoneId, onLog: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Tech.Black.copy(alpha = .25f), Tech.Control).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(3.dp).height(30.dp).background(phaseColor(run.phase)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(run.phase.label.uppercase(), fontFamily = Tech.Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = phaseColor(run.phase))
                Text("${run.action.title} · due ${scheduleStamp(run.due, zone)}", fontSize = 11.sp, color = Tech.Muted)
                Text(run.guest, Modifier.identity(), fontSize = 11.sp, color = Tech.Muted)
            }
            Text(run.detail + if (run.missedEarlier > 0) " ${run.missedEarlier} earlier occurrence${if (run.missedEarlier == 1) " was" else "s were"} missed too." else "", fontSize = 12.sp)
            run.upid?.let { Text("Task ${it.take(96)}", Modifier.identity(), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (run.upid != null) MiniAction("Task log", onClick = onLog)
    }
}

@Composable private fun TaskLogDialog(engine: ScheduleEngine?, run: ScheduleRun, onClose: () -> Unit) {
    var result by remember(run.id) { mutableStateOf<EngineResult<TaskLogPage>?>(null) }
    LaunchedEffect(run.id) { result = engine?.taskLog(run.id) ?: EngineResult.Error("Scheduling is off.") }
    TechDialog("Task log", onClose, width = 720.dp, subtitle = run.upid, dismiss = Dismiss.Light, actions = { MiniAction("Close", primary = true, onClick = onClose) }) {
        when (val r = result) {
            null -> Text("Reading the log with the background connection.", fontSize = 12.sp, color = Tech.Muted)
            is EngineResult.Error -> Text(r.message, fontSize = 12.sp, color = Tech.Amber)
            is EngineResult.Ok -> SelectionContainer {
                Column(Modifier.fillMaxWidth().background(Tech.Black, Tech.Control).padding(10.dp).horizontalScroll(rememberScrollState())) {
                    if (r.value.lines.isEmpty()) Text("The log is empty.", fontSize = 12.sp, color = Tech.Muted)
                    r.value.lines.forEach { Text(it.text, fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Ink, softWrap = false) }
                }
            }
        }
    }
}

private val weekdays = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

internal fun defaultScheduleZone(): String = ZoneId.systemDefault().id.takeIf { parseScheduleZone(it) != null } ?: "UTC"

/**
 * New or edited schedule. Guests and their identity are read with the connection's background access, so what is
 * authorized is exactly what a run will check. Saving with the authorization ticked is the consent for all future runs.
 */
@Composable internal fun ScheduleEditor(engine: ScheduleEngine, state: SchedulerState, plan: SchedulePlan?, now: () -> Instant = { Instant.now() }, onDone: (SchedulePlan?) -> Unit) {
    val scope = rememberCoroutineScope()
    val readyProfiles = state.access.filter { it.ready }
    var name by remember { mutableStateOf(plan?.name ?: "") }
    var action by remember { mutableStateOf(plan?.action ?: ScheduleAction.Start) }
    var repeat by remember { mutableStateOf(plan?.repeat?.kind ?: RepeatKind.Weekdays) }
    var days by remember { mutableStateOf(plan?.repeat?.days ?: setOf(DayOfWeek.MONDAY)) }
    var date by remember { mutableStateOf(plan?.repeat?.date?.toString() ?: now().atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1).toString()) }
    var time by remember { mutableStateOf(plan?.time?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "07:00") }
    var zone by remember { mutableStateOf(plan?.zone?.id ?: defaultScheduleZone()) }
    var profileId by remember { mutableStateOf(plan?.target?.profileId ?: readyProfiles.singleOrNull()?.profileId) }
    var guests by remember { mutableStateOf<EngineResult<List<Guest>>?>(null) }
    var check by remember { mutableStateOf<GuestCheck?>(null) }
    var checking by remember { mutableStateOf(false) }
    var checkError by remember { mutableStateOf<String?>(null) }
    var weak by remember { mutableStateOf(false) }
    var authorized by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var changeGuest by remember { mutableStateOf(plan == null || plan.invalidReason != null) }
    LaunchedEffect(profileId, changeGuest) {
        val id = profileId ?: return@LaunchedEffect
        if (!changeGuest) return@LaunchedEffect
        guests = null; check = null
        guests = engine.inspectGuests(id)
    }
    val draft = ScheduleDraft(name, action, repeat, days, date, time, zone)
    // Any change of what, which guest or when needs the authorization ticked again.
    val authKey = listOf(action, repeat, days, date, time, zone, profileId, check?.guest?.resource, weak)
    var lastAuthKey by remember { mutableStateOf(authKey) }
    if (authKey != lastAuthKey) { lastAuthKey = authKey; authorized = false }
    val targetLabel = check?.guest?.let { "${it.name.ifBlank { it.kind }} (${it.id})" } ?: plan?.target?.label ?: "the guest"
    val connectionLabel = state.access.find { it.profileId == profileId }?.name ?: "the connection"
    val preview = remember(draft) {
        val t = parseScheduleTime(time); val z = parseScheduleZone(zone)
        if (t == null || z == null || scheduleDraftErrors(draft, now()).isNotEmpty()) emptyList()
        else runCatching { occurrencesBetween(draft.toRepeat(), t, z, now(), now().plus(java.time.Duration.ofDays(400)), limit = 3) }.getOrDefault(emptyList()).map { scheduleStamp(it, z) }
    }
    TechDialog(if (plan == null) "New schedule" else "Edit schedule", { onDone(null) }, width = 660.dp,
        subtitle = "Start or gracefully shut down one guest at set times.", actions = {
            MiniAction("Cancel") { onDone(null) }
            MiniAction(if (saving) "Saving" else "Save schedule", primary = true, enabled = !saving) {
                saving = true; message = null
                scope.launch {
                    when (val r = engine.savePlan(plan?.id, draft, if (changeGuest) check else null, weak, authorized, profileId)) {
                        is EngineResult.Ok -> onDone(r.value)
                        is EngineResult.Error -> { errors = r.fields; message = r.message }
                    }
                    saving = false
                }
            }
        }) {
        TechField(name, { name = it }, "Name", placeholder = "Weekday lab start", error = errors["name"])
        if (changeGuest) {
            if (readyProfiles.isEmpty()) Text("No connection has background access yet. Allow it on the Schedules page first (it needs a remembered API token).", fontSize = 12.sp, color = Tech.Amber)
            TechSelect("Connection", readyProfiles, readyProfiles.find { it.profileId == profileId }, { profileId = it.profileId }, render = { it.name },
                emptyText = "No connection with background access.")
            val list = (guests as? EngineResult.Ok)?.value.orEmpty()
            (guests as? EngineResult.Error)?.let { Text(it.message, fontSize = 12.sp, color = Tech.Amber) }
            TechSelect("Guest", list, check?.guest, { g ->
                checking = true; checkError = null; check = null; weak = false
                scope.launch {
                    when (val r = engine.inspectGuest(profileId ?: return@launch, g)) {
                        is EngineResult.Ok -> check = r.value
                        is EngineResult.Error -> checkError = r.message
                    }
                    checking = false
                }
            }, render = { "${if (it.kind == "VM") "VM" else "CT"} ${it.id} · ${it.name.ifBlank { "unnamed" }} · ${it.node} · ${it.status}${if (it.template) " · template" else ""}" },
                enabled = profileId != null && guests is EngineResult.Ok, loading = profileId != null && guests == null, error = errors["guest"],
                emptyText = "No guests were read.")
            if (checking) Text("Reading the guest's identity and this token's permissions.", fontSize = 12.sp, color = Tech.Muted)
            checkError?.let { Text(it, fontSize = 12.sp, color = Tech.Amber) }
            check?.let { c ->
                Column(Modifier.fillMaxWidth().border(1.dp, Tech.Edge, Tech.Control).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (c.strong != null) "Identity: ${c.strong!!.method.label}. A run stops if it changes." else "This guest has no SMBIOS UUID or creation time.",
                        fontSize = 12.sp, color = if (c.strong != null) Tech.Ink else Tech.Amber)
                    if (c.strong == null) TechCheck(weak, { weak = it }, "Use the weak identity (name, OS type, root disk). A recreated guest with the same settings would also match.")
                    Text(if (c.powerMgmt) "VM.PowerMgmt: held on this guest." else "VM.PowerMgmt: missing. Runs would be refused, so this guest cannot be scheduled.",
                        fontSize = 12.sp, color = if (c.powerMgmt) Tech.Green else Tech.Red)
                    if (c.template) Text("This is a template. Templates cannot be started or shut down.", fontSize = 12.sp, color = Tech.Red)
                }
            }
        } else if (plan != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Guest: ${plan.target.label} on $connectionLabel", Modifier.weight(1f).identity(), fontSize = 13.sp)
                MiniAction("Choose another guest") { changeGuest = true }
            }
        }
        SectionLabel("Action")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ScheduleAction.entries.forEach { a -> TechAction(a.title, active = a == action) { action = a } } }
        Text(if (action == ScheduleAction.Shutdown) SHUTDOWN_GUARANTEE else "Starts the guest if it is stopped. Nothing is sent when it is already running.", fontSize = 11.sp, color = Tech.Muted)
        SectionLabel("Repeat")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(RepeatKind.Once to "Once", RepeatKind.Daily to "Daily", RepeatKind.Weekdays to "Weekdays", RepeatKind.Days to "Selected days").forEach { (k, l) ->
                TechAction(l, active = repeat == k) { repeat = k }
            }
        }
        if (repeat == RepeatKind.Days) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                weekdays.forEach { d -> TechCheck(d in days, { on -> days = if (on) days + d else days - d }, d.name.take(3).lowercase().replaceFirstChar(Char::uppercase)) }
            }
            errors["days"]?.let { Text(it, fontSize = 11.sp, color = Tech.Red) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (repeat == RepeatKind.Once) TechField(date, { date = it }, "Date", Modifier.width(150.dp), placeholder = "2026-12-24", error = errors["date"])
            TechField(time, { time = it }, "Time (24 h)", Modifier.width(110.dp), placeholder = "07:30", error = errors["time"])
            TechField(zone, { zone = it }, "Time zone", Modifier.width(240.dp), placeholder = "Europe/Berlin", error = errors["zone"])
        }
        Text("Daylight saving time: a time that does not exist that day (clocks jump forward) runs later by the jump, for example 02:30 runs at 03:30. A time that happens twice (clocks go back) runs once, the first time.",
            fontSize = 11.sp, color = Tech.Muted)
        if (preview.isNotEmpty()) Text("Next: ${preview.joinToString("  ·  ")}", fontSize = 12.sp, color = Tech.Ink)
        val needsAuth = plan == null || plan.invalidReason != null || changeGuest || run {
            val t = parseScheduleTime(time); val z = parseScheduleZone(zone)
            t == null || z == null || runCatching { authorizationChanged(plan, action, draft.toRepeat(), t, z, plan.target) }.getOrDefault(true)
        }
        if (needsAuth) {
            Column(Modifier.fillMaxWidth().border(1.dp, if (errors.containsKey("authorize") && !authorized) Tech.Red else Tech.Amber.copy(alpha = .6f), Tech.Control).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TechCheck(authorized, { authorized = it }, "I authorize RingMASTER to ${action.title.lowercase()} $targetLabel on $connectionLabel at these times, without asking each time.")
                Text("RingMASTER checks the connection, certificate, guest identity, permission and state before every run, sends nothing when the guest is already in that state, and stops this schedule if the connection or guest changes. Changing the guest, action or times asks again.",
                    fontSize = 11.sp, color = Tech.Muted)
            }
        } else Text("Only the name changed, so the existing authorization stays.", fontSize = 11.sp, color = Tech.Muted)
        message?.let { Text(it, fontSize = 12.sp, color = Tech.Red) }
    }
}

/** Settings > Background and tray. */
@Composable internal fun BackgroundSettingsSection(resident: ResidentRuntime?) {
    if (resident == null) {
        Text("Running in the background is available in the desktop app.", fontSize = 13.sp, color = Tech.Muted)
        Text(SCHEDULE_AVAILABILITY, fontSize = 12.sp, color = Tech.Muted)
        return
    }
    BackgroundSettingsBody(resident.trayStatus, resident.closeToTray, resident.engineProblem) { resident.chooseCloseToTray(it) }
}

@Composable internal fun BackgroundSettingsBody(tray: TrayStatus, closeToTray: Boolean, engineProblem: String?, onCloseToTray: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TechSwitch(closeToTray && tray == TrayStatus.Installed, onCloseToTray, enabled = tray == TrayStatus.Installed)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Keep running in the notification area when the window is closed", fontSize = 14.sp)
            Text(trayLine(tray, closeToTray), fontSize = 11.sp, color = if (tray == TrayStatus.Installed) Tech.Muted else Tech.Amber)
        }
    }
    SectionLabel("Close or quit")
    Text("Close (the window's X, Alt+F4 or the taskbar's Close) hides the window while this is on. Open it again from the icon, or by starting RingMASTER again. Quit RingMASTER in the icon's menu exits completely: schedules stop and open consoles close.",
        fontSize = 12.sp, color = Tech.Ink)
    Text("While hidden, live refresh pauses and an open console is kept paused for your Keep consoles open time, then closed. Connections and schedules stay.", fontSize = 12.sp, color = Tech.Muted)
    SectionLabel("When schedules run")
    Text(SCHEDULE_AVAILABILITY, fontSize = 12.sp, color = Tech.Ink)
    Text("RingMASTER does not start itself when you sign in and cannot wake the computer. Start it after signing in if schedules should run.", fontSize = 12.sp, color = Tech.Muted)
    engineProblem?.let { Text(it, fontSize = 12.sp, color = Tech.Amber) }
}

/** Shown instead of quitting at once when closing would stop enabled schedules. */
@Composable internal fun QuitPrompt(resident: ResidentRuntime) {
    val count = resident.engine?.state?.collectAsState()?.value?.document?.plans?.count { it.enabled && it.invalidReason == null } ?: 0
    TechDialog("Quit RingMASTER?", { resident.quitPrompt = false }, subtitle = "$count schedule${if (count == 1) " is" else "s are"} on", actions = {
        MiniAction("Cancel") { resident.quitPrompt = false }
        if (resident.trayStatus == TrayStatus.Installed) MiniAction("Keep running in the notification area") {
            resident.quitPrompt = false; resident.chooseCloseToTray(true); resident.hide()
        }
        MiniAction("Quit", primary = true) { resident.quit() }
    }) {
        Text("While RingMASTER is closed, schedules do not run. When it starts again, a run due in the last 15 minutes still starts; older ones are recorded as Missed.", fontSize = 13.sp)
    }
}
