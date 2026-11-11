package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.ringleader.core.tools.*

/** Tools page state. Drafts and the confirmation choice last for this session, including page switches. */
class ToolsSession(private val scope: CoroutineScope) {
    var plugins by mutableStateOf<List<ToolPlugin>>(emptyList()); private set
    var skipped by mutableStateOf<List<SkippedPlugin>>(emptyList()); private set
    var selectedId by mutableStateOf("ping")
    var running by mutableStateOf(false); private set
    var status by mutableStateOf("Ready")
    var statusBad by mutableStateOf(false); private set
    var revision by mutableStateOf(0); private set
    /** In the browser without --allow-host-actions this is false; the UI sets it from LocalAppHost. */
    var hostActions: Boolean = true
    val lines = mutableStateListOf<String>()
    val samples = mutableStateListOf<ToolChartSample>()
    var confirmCommand by mutableStateOf<List<String>?>(null); private set
    private val drafts = mutableStateMapOf<String, androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>>()
    private val approvals = mutableMapOf<String, PluginApproval>()
    private val badges = mutableStateMapOf<String, ToolAvailability>()
    private var confirmId: String? = null
    private var confirmValues: Map<String, String>? = null
    private var confirmManifestCommand: List<String>? = null
    private var job: Job? = null

    init { reload() }

    fun reload() {
        if (running) return
        val catalog = loadToolCatalog()
        catalog.plugins.filterIsInstance<UserToolPlugin>().forEach { plugin ->
            val saved = approvals[plugin.id]
            if (saved != null && saved.command == plugin.manifestCommand) plugin.approval = saved
        }
        plugins = catalog.plugins
        skipped = catalog.skipped
        badges.clear()
        plugins.forEach { badges[it.id] = it.available() }
        if (plugins.none { it.id == selectedId }) selectedId = plugins.firstOrNull()?.id ?: "ping"
    }

    fun select(id: String) { if (!running) selectedId = id }

    fun draft(plugin: ToolPlugin): androidx.compose.runtime.snapshots.SnapshotStateMap<String, String> {
        val map = drafts.getOrPut(plugin.id) { mutableStateMapOf() }
        plugin.inputs.forEach { input ->
            val current = map[input.key]
            if (current == null || (input.kind == ToolInputKind.Choice && input.choices.isNotEmpty() && current !in input.choices)) {
                map[input.key] = input.default
            }
        }
        return map
    }

    fun badge(plugin: ToolPlugin): ToolAvailability {
        if (!hostActions) {
            val values = drafts[plugin.id]?.toMap() ?: emptyMap()
            browserRefusal(plugin, values)?.let { return ToolAvailability(false, it) }
        }
        return when (plugin) {
            is Iperf3Plugin -> plugin.availableFor(mapOf("executable" to drafts[plugin.id]?.get("executable").orEmpty()))
            is WireGuardToolPlugin -> plugin.available()
            else -> badges[plugin.id] ?: plugin.available()
        }
    }

    fun bump() { revision++ }

    fun showStatus(text: String, bad: Boolean) {
        status = text
        statusBad = bad
    }

    fun requestRun(plugin: ToolPlugin) {
        if (running) { stop(); return }
        val values = draft(plugin).toMap()
        if (!hostActions) {
            browserRefusal(plugin, values)?.let { status = it; statusBad = true; return }
        }
        val ready = badge(plugin)
        if (!ready.ready) { status = ready.reason ?: "This tool is not available."; statusBad = true; return }
        try {
            validateToolValues(plugin.inputs, values)
            if (plugin is UserToolPlugin) {
                val argv = plugin.commandFor(values)
                val a = plugin.approval
                if (a == null || a.command != plugin.manifestCommand || a.argv != argv) {
                    confirmCommand = argv
                    confirmId = plugin.id
                    confirmValues = values
                    confirmManifestCommand = plugin.manifestCommand
                    return
                }
            }
            if (plugin is Iperf3Plugin) {
                val raw = values["executable"].orEmpty()
                if (raw.isNotBlank()) {
                    val argv = plugin.previewCommand(values)
                    if (argv != null && plugin.approvedExecutable != argv.first()) {
                        confirmCommand = argv
                        confirmId = plugin.id
                        confirmValues = values
                        confirmManifestCommand = null
                        return
                    }
                }
            }
        } catch (e: ToolInputException) {
            status = e.message ?: "Check the inputs."; statusBad = true; return
        }
        start(plugin, values)
    }

    fun acceptCommand() {
        val plugin = plugins.firstOrNull { it.id == confirmId } ?: return
        val argv = confirmCommand ?: return
        val savedValues = confirmValues ?: return
        if (!hostActions) {
            browserRefusal(plugin, savedValues)?.let {
                status = it; statusBad = true
                confirmCommand = null; confirmId = null; confirmValues = null; confirmManifestCommand = null
                return
            }
        }
        when (plugin) {
            is UserToolPlugin -> {
                val savedCommand = confirmManifestCommand ?: return
                if (plugin.manifestCommand != savedCommand) {
                    status = "The plug-in changed. Please try again."
                    statusBad = true
                    confirmCommand = null; confirmId = null; confirmValues = null; confirmManifestCommand = null
                    return
                }
                val approval = PluginApproval(savedCommand, argv)
                plugin.approval = approval
                approvals[plugin.id] = approval
                confirmCommand = null; confirmId = null; confirmValues = null; confirmManifestCommand = null
                start(plugin, savedValues)
            }
            is Iperf3Plugin -> {
                plugin.approvedExecutable = argv.first()
                confirmCommand = null; confirmId = null; confirmValues = null; confirmManifestCommand = null
                start(plugin, savedValues)
            }
            else -> {
                confirmCommand = null; confirmId = null; confirmValues = null; confirmManifestCommand = null
            }
        }
    }

    fun dismissCommand() { confirmCommand = null; confirmId = null; confirmValues = null; confirmManifestCommand = null }

    fun stop() { job?.cancel() }

    private fun start(plugin: ToolPlugin, values: Map<String, String>) {
        job?.cancel()
        lines.clear()
        samples.clear()
        running = true
        statusBad = false
        status = "Running…"
        val events = Channel<ToolEvent>(Channel.UNLIMITED)
        job = scope.launch {
            val pump = launch {
                for (event in events) apply(event)
            }
            try {
                val result = withContext(Dispatchers.IO) { plugin.run(values) { events.trySend(it) } }
                events.close()
                pump.join()
                if (lines.lastOrNull() != result.summary) apply(ToolEvent.Line(result.summary))
                status = oneLine(result.summary)
                statusBad = !result.ok
            } catch (e: CancellationException) {
                status = "Stopped"
                statusBad = false
            } catch (e: Exception) {
                status = oneLine(e.message ?: "The test failed.")
                statusBad = true
            } finally {
                events.close()
                pump.cancel()
                running = false
            }
        }
    }

    private fun apply(event: ToolEvent) {
        when (event) {
            is ToolEvent.Line -> {
                val text = event.text.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "").take(2000)
                if (lines.size >= 2000) lines.removeAt(0)
                lines += text
            }
            is ToolEvent.Status -> status = oneLine(event.text)
            is ToolEvent.Sample -> if (event.x.isFinite() && event.y.isFinite() && samples.size < 2000) {
                samples += ToolChartSample(event.series.ifBlank { "value" }, event.x, event.y, event.unit)
            }
        }
    }
}

@Composable fun ToolsScreen(session: ToolsSession) {
    val web = LocalAppHost.current.web
    session.hostActions = LocalAppHost.current.hostActions
    LaunchedEffect(Unit) { session.reload() }
    val plugin = session.plugins.firstOrNull { it.id == session.selectedId } ?: session.plugins.firstOrNull()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (web) Text("Tests run from the server", color = Tech.Amber, fontFamily = Tech.Mono, fontSize = 11.sp, maxLines = 1)
        if (plugin == null) {
            Text("No tools are available.", color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 12.sp)
            return@Column
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolList(session, plugin.id)
            ToolDetail(session, plugin, Modifier.weight(1f).fillMaxHeight())
        }
    }
    val command = session.confirmCommand
    val confirming = plugin
    if (command != null && confirming != null) {
        TechDialog("Run ${confirming.name}?", { session.dismissCommand() }, subtitle = "RingMASTER does not use a shell. Each line is one argument.", actions = {
            TechAction("Run", active = true) { session.acceptCommand() }
            TechAction("Cancel") { session.dismissCommand() }
        }) {
            Column(Modifier.fillMaxWidth().border(1.dp, Tech.Edge, Tech.Control).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                command.forEachIndexed { index, argument ->
                    Text("${index + 1}. $argument", fontFamily = Tech.Mono, fontSize = 12.sp, color = Tech.Ink)
                }
            }
        }
    }
}

@Composable private fun ToolList(session: ToolsSession, selected: String) {
    Column(Modifier.width(248.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Tools", suffix = "${session.plugins.size}")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            session.plugins.forEach { plugin ->
                val ready = session.badge(plugin)
                TechPanel(Modifier.fillMaxWidth(), selected = plugin.id == selected, rail = if (ready.ready) Tech.Green else Tech.Amber, onClick = { session.select(plugin.id) }, padding = 8.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(plugin.name, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (ready.ready) "READY" else "UNAVAILABLE", fontFamily = Tech.Mono, fontSize = 9.sp, color = if (ready.ready) Tech.Green else Tech.Amber)
                    }
                    Text(plugin.summary, fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (session.skipped.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                SectionLabel("Skipped")
                session.skipped.forEach { skipped ->
                    Text("${skipped.file}: ${skipped.reason}", fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted)
                }
            }
            Text("Plug-in files go in the plugins folder next to profiles.json.", fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted)
        }
    }
}

@Composable private fun ToolDetail(session: ToolsSession, plugin: ToolPlugin, modifier: Modifier) {
    val reduce = LocalReduceMotion.current
    val inputs = remember(plugin.id, session.revision) { plugin.inputs }
    val draft = session.draft(plugin)
    val ready = session.badge(plugin)
    LaunchedEffect(plugin.id) {
        val wireguard = plugin as? WireGuardToolPlugin ?: return@LaunchedEffect
        if (!session.running) session.status = "Reading tunnels…"
        withContext(Dispatchers.IO) { wireguard.refreshSnapshot() }
        session.draft(wireguard)
        session.bump()
        if (!session.running) {
            val availability = wireguard.available()
            session.showStatus(availability.reason ?: "Ready", !availability.ready && availability.reason != null)
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(plugin.summary, color = Tech.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        // Tall enough for every built-in tool's inputs (iPerf3 has eight); a plug-in with more scrolls.
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            inputs.forEach { input ->
                ToolField(input, draft[input.key].orEmpty(), !session.running) { draft[input.key] = it }
            }
            if (plugin.id == "iperf3") Text(
                if (draft["server"] == "true") "This opens a listening port on this computer." else " ",
                Modifier.height(16.dp), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Amber, maxLines = 1,
            )
        }
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniAction(if (session.running) "Stop" else "Run", primary = !session.running, enabled = session.running || ready.ready) { session.requestRun(plugin) }
            Text(
                if (!session.running && !ready.ready) ready.reason ?: session.status else session.status,
                Modifier.weight(1f), fontFamily = Tech.Mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = when {
                    session.running -> Tech.Amber
                    session.statusBad || !ready.ready -> Tech.Red
                    session.status == "Ready" || session.status == "Stopped" -> Tech.Muted
                    else -> Tech.Green
                },
            )
        }
        ToolChart(session.samples, Modifier.fillMaxWidth().height(120.dp))
        val scroll = rememberScrollState()
        LaunchedEffect(session.lines.size, reduce) {
            if (reduce) scroll.scrollTo(scroll.maxValue) else scroll.animateScrollTo(scroll.maxValue)
        }
        SelectionContainer(Modifier.weight(1f).fillMaxWidth().border(1.dp, Tech.Edge, Tech.Control).background(Tech.Black.copy(alpha = .45f))) {
            Text(
                session.lines.joinToString("\n").ifEmpty { "Output appears here." },
                Modifier.fillMaxSize().verticalScroll(scroll).padding(8.dp),
                fontFamily = Tech.Mono, fontSize = 11.sp, color = if (session.lines.isEmpty()) Tech.Muted else Tech.Ink,
            )
        }
    }
}

@Composable private fun ToolField(input: ToolInput, value: String, enabled: Boolean, onValue: (String) -> Unit) {
    when (input.kind) {
        // Same label column as the text fields; the switch starts the field and its hint says what "on" does.
        ToolInputKind.Toggle -> Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(input.label, Modifier.width(120.dp), fontSize = 12.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TechSwitch(value == "true", { onValue(if (it) "true" else "false") }, enabled = enabled)
            if (input.hint.isNotBlank()) Text(input.hint, Modifier.padding(start = 4.dp), fontFamily = Tech.Mono, fontSize = 11.sp,
                color = if (value == "true" && enabled) Tech.Ink else Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ToolInputKind.Choice -> {
            var open by remember(input.key) { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(input.label, Modifier.width(120.dp), fontSize = 12.sp, color = Tech.Muted, maxLines = 1)
                if (input.choices.size in 1..4 && input.choices.all { input.display(it).length <= 16 }) {
                    input.choices.forEach { choice ->
                        MiniAction(input.display(choice), primary = value == choice, enabled = enabled) { onValue(choice) }
                    }
                } else Box {
                    MiniAction(input.display(value), enabled = enabled && input.choices.isNotEmpty(), width = 180.dp) { open = true }
                    DropdownMenu(open, { open = false }, containerColor = Tech.Raised) {
                        input.choices.forEach { choice ->
                            DropdownMenuItem(text = { Text(input.display(choice), fontFamily = Tech.Mono, fontSize = 12.sp) }, onClick = { onValue(choice); open = false })
                        }
                    }
                }
            }
        }
        else -> ToolText(input.label, value, input.hint.ifBlank { input.label }, enabled, onValue)
    }
}

@Composable private fun ToolText(label: String, value: String, hint: String, enabled: Boolean, onValue: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(120.dp), fontSize = 12.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicTextField(value, { if (enabled) onValue(it) }, singleLine = true, enabled = enabled,
            textStyle = TextStyle(color = if (enabled) Tech.Ink else Tech.Muted, fontSize = 12.sp, fontFamily = Tech.Mono),
            cursorBrush = SolidColor(Tech.Amber),
            modifier = Modifier.weight(1f).height(28.dp).border(1.dp, Tech.Edge, Tech.Control).background(Tech.Black.copy(alpha = .35f)),
            decorationBox = { inner ->
                Box(Modifier.fillMaxSize().padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(hint, color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            })
    }
}
