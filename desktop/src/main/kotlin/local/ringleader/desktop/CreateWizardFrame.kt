package local.ringleader.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ringleader.core.*

data class WizardStep(val id: String, val title: String, val problems: Int, val loading: Boolean)
internal val LocalCreateTaskLogAvailable = staticCompositionLocalOf<Boolean?> { null }
internal val LocalCreatePreviewScroll = staticCompositionLocalOf { 0 }

enum class ResultAction { CheckId, OpenGuest, ViewLog, Edit, Close }

/** Full-window editor. Only the middle scrolls; no action can scroll out of reach. */
@Composable fun CreateWizardFrame(kind: NewGuestKind, title: String, headerNode: String, headerId: String,
    steps: List<WizardStep>, current: String, onStep: (String) -> Unit,
    params: List<ParamLine>, advanced: Boolean, onAdvanced: (Boolean) -> Unit,
    canCreate: Boolean, createLabel: String, onCreate: () -> Unit, onCancel: () -> Unit, dirty: Boolean,
    result: CreateResult?, onResultAction: (ResultAction) -> Unit,
    content: @Composable ColumnScope.(columns: Int) -> Unit) {
    var discard by remember { mutableStateOf(false) }
    var parameters by remember { mutableStateOf(false) }
    val cancel = { if (dirty && result == null) discard = true else onCancel() }
    // Own our entry so a nested dialog or picker owns shortcuts until it closes.
    val stack = LocalModalStack.current ?: remember { ModalStack() }
    val latestCancel = rememberUpdatedState(cancel)
    var entry by remember { mutableStateOf<ModalStack.Entry?>(null) }
    DisposableEffect(stack) {
        val registered = stack.push(ModalKind.Dialog) { latestCancel.value() }
        entry = registered
        onDispose { stack.remove(registered) }
    }
    fun ownsKeys() = stack.top === entry && !discard
    val editing = result == null
    val motion = LocalMotion.current
    val entrance = remember { Animatable(1f) }
    LaunchedEffect(current) {
        entrance.snapTo(0f)
        entrance.animateTo(1f, motion.enter(MotionDur.Short))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val stepFocus = remember(steps.size) { List(steps.size) { FocusRequester() } }
    val index = steps.indexOfFirst { it.id == current }.coerceAtLeast(0)
    fun move(delta: Int) { if (!editing || !ownsKeys()) return; steps.getOrNull(index + delta)?.let { onStep(it.id) } }
    CompositionLocalProvider(LocalModalStack provides stack) {
    BoxWithConstraints(Modifier.fillMaxSize().background(ringColors.surface).focusRequester(focus).focusable()
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown || !ownsKeys()) false else when {
                e.key == Key.Escape -> { cancel(); true }
                e.isCtrlPressed && e.key == Key.Enter -> { if (editing && canCreate) onCreate(); true }
                e.isCtrlPressed && e.key == Key.PageDown -> { move(1); true }
                e.isCtrlPressed && e.key == Key.PageUp -> { move(-1); true }
                e.isAltPressed && e.key in listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight) -> {
                    if (editing) steps.getOrNull(listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight).indexOf(e.key))?.let { onStep(it.id) }; true
                }
                else -> false
            }
        }.onKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && editing && ownsKeys()) {
                if (index < steps.lastIndex) move(1) else if (canCreate) onCreate()
                true
            } else false
        }.padding(16.dp)) {
        val wide = maxWidth >= 1180.dp
        val narrow = maxWidth < 900.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { HelpTip(title) {
                    Text(title.uppercase(), color = Tech.Amber, fontFamily = Tech.Mono, fontSize = 18.sp, maxLines=1, overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                } }
                HelpTip("$headerNode \u00b7 $headerId", identity=true) {
                    Text("$headerNode \u00b7 $headerId", Modifier.widthIn(max=180.dp).identity(), color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 12.sp, maxLines=1, overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                MiniAction("Close", onClick = cancel)
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.width(if (narrow) 48.dp else 164.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    steps.forEachIndexed { i, step ->
                        HelpTip(step.title) {
                            TechAction(if (narrow) "${i + 1}" else "${i + 1}  ${step.title}",
                                Modifier.focusRequester(stepFocus[i]).onPreviewKeyEvent { e ->
                                    if (editing && ownsKeys() && e.type == KeyEventType.KeyDown && !e.isCtrlPressed && e.key in listOf(Key.DirectionUp, Key.DirectionDown)) {
                                        stepFocus[(i + if (e.key == Key.DirectionDown) 1 else -1).coerceIn(0, steps.lastIndex)].requestFocus()
                                        true
                                    } else false
                                }, active = current == step.id,
                                enabled = editing, onClick = { if (editing && ownsKeys()) onStep(step.id) })
                        }
                        if (step.problems > 0 || step.loading) HelpTip(if(step.loading) "Reading" else "${step.problems} ${if(step.problems == 1) "problem" else "problems"}") {
                            Text(if (step.loading) (if(narrow) "\u2026" else "Reading\u2026") else if(narrow) "${step.problems} !" else "${step.problems} ${if(step.problems == 1) "problem" else "problems"}", color = if (step.loading) Tech.Amber else Tech.Red, fontSize = 10.sp)
                        }
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().border(1.dp, Tech.Edge, Tech.Plate).padding(16.dp)) {
                    val columns = if (maxWidth >= 760.dp) 2 else 1
                    val previewScroll = LocalCreatePreviewScroll.current
                    val scroll = rememberScrollState(previewScroll)
                    LaunchedEffect(current, previewScroll) { scroll.scrollTo(previewScroll) }
                    Column(Modifier.fillMaxSize().padding(end = 12.dp).verticalScroll(scroll).graphicsLayer {
                        alpha = entrance.value
                        translationY = (1f - entrance.value) * motion.travel(8f) * density
                    }, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (result != null) CreateResultView(result, onResultAction)
                        else if (parameters && !wide) ParametersPanel(params)
                        else content(columns)
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
                if (wide) Column(Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { ParametersPanel(params) }
            }
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TechCheck(advanced, { if (editing && ownsKeys()) onAdvanced(it) }, "Show advanced", enabled = editing)
                if (!wide) MiniAction(if (parameters) "Form" else "Parameters") { parameters = !parameters }
                MiniAction("Back", enabled = index > 0 && result == null) { move(-1) }
                MiniAction("Next", enabled = index < steps.lastIndex && result == null) { move(1) }
                TechAction(createLabel, Modifier.identity(), active = true, enabled = canCreate && result == null, onClick = { if (editing && ownsKeys() && canCreate) onCreate() })
                MiniAction("Cancel", onClick = cancel)
            }
        }
        if (discard) TechDialog("Discard this new ${kind.guestKind.lowercase()}?", onDismiss = { discard = false }, dismiss = Dismiss.Confirm) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TechAction("Keep editing", onClick = { discard = false })
                TechAction("Discard", onClick = onCancel)
            }
        }
    }
    }
}

@Composable fun ParametersPanel(params: List<ParamLine>) {
    SectionLabel("Parameters")
    Spacer(Modifier.height(12.dp))
    params.forEach { line ->
        val glow by rememberArrivalGlow(line.value)
        Column(Modifier.fillMaxWidth().background(Tech.Amber.copy(alpha = glow * .08f)).padding(vertical = 5.dp)) {
            Text(line.key, color = Tech.Amber, fontFamily = Tech.Mono, fontSize = 11.sp)
            Text(line.value, Modifier.identity(), color = Tech.Ink, fontFamily = Tech.Mono, fontSize = 12.sp)
        }
    }
}

/** Confirmation consumes no secrets: only SecretState reaches this view. */
@Composable fun CreateConfirmDialog(confirmation: CreateConfirmation, params: List<ParamLine>, sample: Boolean,
    onConfirm: () -> Unit, onCancel: () -> Unit, privilegeHints: List<String> = emptyList()) {
    val cancelFocus = remember { FocusRequester() }
    TechDialog(if (sample) "Simulate create" else "Create ${confirmation.request.kind.guestKind}", onDismiss = onCancel,
        dismiss = Dismiss.Confirm, initialFocus = cancelFocus) {
        Text("${confirmation.profileLabel} \u00b7 ${confirmation.origin}", Modifier.identity(), color = Tech.Muted)
        Text("Create ${confirmation.request.kind.guestKind} ${confirmation.request.vmid} \u00b7 ${confirmation.request.name} on ${confirmation.request.node}", Modifier.identity(), color = Tech.Ink)
        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) { ParametersPanel(params) }
        privilegeHints.forEach { Text(it, Modifier.identity(), color = Tech.Amber, fontSize = 11.sp) }
        if (confirmation.request.kind == NewGuestKind.Container) {
            Text("Root password: ${if (confirmation.secretsSet.password) "set" else "not set"}", color = Tech.Muted)
            Text("SSH keys: ${confirmation.secretsSet.keyCount}", color = Tech.Muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TechAction("Cancel", Modifier.focusRequester(cancelFocus), onClick = onCancel)
            TechAction(if (sample) "Simulate create" else "Create", active = true, onClick = onConfirm)
        }
    }
}

@Composable fun CreateResultView(result: CreateResult, onAction: (ResultAction) -> Unit) {
    val color = when(result) {
        is CreateResult.Succeeded -> Tech.Green
        is CreateResult.Failed, is CreateResult.Rejected -> Tech.Red
        else -> Tech.Amber
    }
    val title = when(result) {
        is CreateResult.Sending -> "Sending create\u2026"
        is CreateResult.Following -> "Creating ${result.kind.guestKind}\u2026"
        is CreateResult.Succeeded -> "${result.kind.guestKind} was created."
        is CreateResult.FinishedWithWarnings -> "Finished with ${result.count} warnings."
        is CreateResult.Failed -> "Create failed: ${result.text}"
        is CreateResult.Rejected -> result.message
        is CreateResult.Unknown -> "Outcome unknown. ${result.text} It was not sent again. Check the server before trying again."
        is CreateResult.Simulated -> DEMO_CREATE_SIMULATED
    }
    Text(title, Modifier.identity(), color = color, fontSize = 20.sp)
    Text("${result.kind.guestKind} ${result.vmid} \u00b7 ${result.node}", Modifier.identity(), color = Tech.Muted)
    val appearance = when(result) {
        is CreateResult.Succeeded -> result.appearance
        is CreateResult.FinishedWithWarnings -> result.appearance
        is CreateResult.Failed -> result.appearance
        else -> null
    }
    when(appearance) {
        GuestAppearance.Appeared -> { Text("${result.kind.guestKind} ${result.vmid} now exists on ${result.node}.", Modifier.identity(), color = Tech.Ink); MiniAction("Open guest") { onAction(ResultAction.OpenGuest) } }
        GuestAppearance.Waiting -> Text("Waiting for the guest to appear in the inventory\u2026", color = Tech.Muted)
        GuestAppearance.NotYet -> Text("The guest is not in the inventory yet. Check the server.", color = Tech.Amber)
        null -> Unit
    }
    when(result) {
        is CreateResult.Sending, is CreateResult.Following -> TechProgress()
        is CreateResult.Unknown -> {
            MiniAction(if (result.checking) "Checking ID..." else "Check ID", enabled = !result.checking) { onAction(ResultAction.CheckId) }
            Text(createIdCheckText(result.check), Modifier.identity(), color = Tech.Amber)
            if (result.check is IdCheck.Free) MiniAction("Return to form") { onAction(ResultAction.Edit) }
        }
        is CreateResult.FinishedWithWarnings -> result.warnings.forEach { Text(it, Modifier.identity(), color = Tech.Amber) }
        is CreateResult.Failed -> {
            result.logTail.forEach { Text(it, Modifier.identity(), color = Tech.Muted, fontFamily = Tech.Mono) }
            if (result.appearance == GuestAppearance.NotYet) MiniAction("Return to form") { onAction(ResultAction.Edit) }
        }
        is CreateResult.Rejected -> { result.serverSaid.forEach { (k,v) -> Text("$k: $v", Modifier.identity(), color = Tech.Red) }; MiniAction("Return to form") { onAction(ResultAction.Edit) } }
        else -> Unit
    }
    if (LocalCreateTaskLogAvailable.current ?: (result is CreateResult.Following || result is CreateResult.Succeeded || result is CreateResult.FinishedWithWarnings || (result is CreateResult.Failed && result.localId != null))) MiniAction("Full task log") { onAction(ResultAction.ViewLog) }
}


internal fun openCreateFromFleet(state: WorkspaceState, workspace: Workspace, kind: NewGuestKind, node: String? = null): Boolean {
    if (!createAvailability(state, kind).available) return false
    val target = node ?: createStartNode(state) ?: return false
    return workspace.openCreateSession(kind, target)
}

@Composable fun CreateGuestActions(state: WorkspaceState, workspace: Workspace, node: String? = null,
    availability: (WorkspaceState, NewGuestKind) -> CreateAvailability = ::createAvailability) {
    NewGuestKind.entries.forEach { kind ->
        // Fail closed while the parallel core implementation is absent from this checkout.
        val allowed = try { availability(state, kind) } catch (_: NotImplementedError) {
            CreateAvailability(false, "Guest creation is unavailable.")
        }
        HelpTip(allowed.reason ?: "Create a new ${kind.guestKind.lowercase()}") {
            MiniAction(if (kind == NewGuestKind.Vm) "New VM" else "New container", enabled = allowed.available) {
                openCreateFromFleet(state, workspace, kind, node)
            }
        }
    }
}

/** Owns pending holders outside observable UI state. Only the redacted confirmation is observable. */
internal class PendingCreate : AutoCloseable {
    var confirmation by mutableStateOf<CreateConfirmation?>(null)
        private set
    private var secrets: CreateSecrets? = null
    fun replace(value: CreateConfirmation?, holders: CreateSecrets) {
        close()
        if (value == null) holders.close() else { secrets = holders; confirmation = value }
    }
    fun confirm(send: (CreateConfirmation, CreateSecrets) -> Boolean): Boolean {
        val captured = confirmation ?: return false
        val holders = secrets ?: return false
        confirmation = null; secrets = null
        return try {
            send(captured, holders).also { accepted -> if (!accepted) holders.close() }
        } catch (failure: Throwable) { holders.close(); throw failure }
    }
    override fun close() { secrets?.close(); secrets = null; confirmation = null }
}

@Composable fun CreateGuestHost(state: WorkspaceState, workspace: Workspace, profileLabel: String,
    onOpenGuest: (Guest) -> Unit, onGetMedia: ((String, String, String) -> Unit)? = null, onViewLog: () -> Unit) {
    val session = state.creation ?: return
    key(session.epoch, session.generation, session.kind, profileLabel) {
        val stack = LocalModalStack.current ?: remember { ModalStack() }
        val resultId = when(val result = session.result) {
            is CreateResult.Following -> result.localId
            is CreateResult.Succeeded -> result.localId
            is CreateResult.FinishedWithWarnings -> result.localId
            is CreateResult.Failed -> result.localId
            is CreateResult.Unknown -> result.localId
            else -> null
        }
        val hasLog = state.submissions.any { it.localId == resultId && it.checkable }
        CompositionLocalProvider(LocalModalStack provides stack, LocalCreateTaskLogAvailable provides hasLog) {
        val context = createContext(state, session)
        val pending = remember(session.generation) { PendingCreate() }
        DisposableEffect(pending) { onDispose { pending.close() } }
        val close = { pending.close(); workspace.closeCreateSession() }
        LaunchedEffect(session.result, state.overview) {
            val result = session.result
            if (result is CreateResult.Succeeded && result.appearance == GuestAppearance.Appeared) {
                state.overview?.guests?.firstOrNull { it.node == result.node && it.id == result.vmid && it.kind == result.kind.guestKind }?.let { close(); onOpenGuest(it) }
            }
        }
        val nodes = state.overview?.members.orEmpty().filter { it.status == "online" }.map { it.name }
        val node: (String) -> Unit = { pending.close(); workspace.changeCreateNode(it) }
        var mediaIntent by remember { mutableStateOf<Triple<String,String,String>?>(null) }
        var mediaReady by remember { mutableStateOf<StorageMediaReady?>(null) }
        val media: (String, String, String) -> Unit = { n, storage, kind ->
            pending.close(); mediaIntent=Triple(n,storage,kind)
        }
        val action: (ResultAction) -> Unit = { action ->
            val result = session.result
            val localId = when(result) {
                is CreateResult.Following -> result.localId
                is CreateResult.Succeeded -> result.localId
                is CreateResult.FinishedWithWarnings -> result.localId
                is CreateResult.Failed -> result.localId
                is CreateResult.Unknown -> result.localId
                else -> null
            }
            when(action) {
                ResultAction.CheckId -> if (localId != null) workspace.checkCreateOutcome(localId) else result?.let { workspace.readForCreate(CreateRead.CheckId(it.vmid)) }
                ResultAction.OpenGuest -> state.overview?.guests?.firstOrNull { it.node == result?.node && it.id == result.vmid && it.kind == result.kind.guestKind }?.let { close(); onOpenGuest(it) }
                ResultAction.ViewLog -> if (localId != null && workspace.openCreateTaskLog(localId)) { close(); onViewLog() }
                ResultAction.Close -> close()
                ResultAction.Edit -> Unit // the wizard retains and reopens its draft
            }
        }
        val capture: (CreateRequest, CreateSecrets) -> Unit = { request, holders ->
            val current = workspace.state.value.creation
            if (current?.epoch != session.epoch || current.generation != session.generation) {
                holders.close(); pending.close()
            } else pending.replace(workspace.captureCreate(request, profileLabel, holders.state), holders)
        }
        if (session.kind == NewGuestKind.Vm) CreateVmWizard(session, context, nodes,
            node, workspace::readForCreate, { capture(it, CreateSecrets.None) }, close, action, media,
            rootPrivileges = state.permissions.value?.let { effectivePrivileges(it.dump, "/").privileges },
            storageMediaReady=mediaReady, onStorageMediaApplied={mediaReady=null})
        else CreateCtWizard(session, context, remember { defaultCtDraft(session.node, context) }, capture, close,
            onRead = workspace::readForCreate, onGetMedia = media, nodes = nodes, onNode = node, onResultAction = action, storageMediaReady=mediaReady, onStorageMediaApplied={mediaReady=null})
        mediaIntent?.let { intent ->
            StorageMediaSheet(state,workspace,session,intent.second,if(intent.third=="iso") MediaKind.Iso else MediaKind.Template,profileLabel,
                onReady={ ready -> if(mediaReadyMatches(ready,session)) {mediaReady=ready;mediaIntent=null} },onClose={mediaIntent=null})
        }
        pending.confirmation?.let { captured ->
            CreateConfirmDialog(captured, createParamLines(captured.request.params, captured.secretsSet, captured.start), session.sample,
                { pending.confirm(workspace::submitCreate) }, pending::close,
                state.permissions.value?.let { createPrivilegeHints(it.dump, captured.request, it.identity) }.orEmpty())
        }
    }
    }
}

internal fun createIdCheckText(check: IdCheck): String = when(check) {
    IdCheck.Idle -> "ID not checked yet."
    is IdCheck.Checking -> "Checking ID ${check.id}..."
    is IdCheck.Free -> "ID ${check.id} is free."
    is IdCheck.Taken -> "ID ${check.id} is in use. ${check.text}"
    is IdCheck.Failed -> "Could not check ID ${check.id}. ${check.text}"
}

/** Shared by VM and container editors. Only this non-identifying Boolean is persisted.
 * Phase C: remember one instance; initialize advanced from it, then assign in onAdvanced.
 * Scene tests inject memory() or a MotionPreferences backed by a temporary path.
 */
class CreateWizardPreferences(private val read: () -> Boolean, private val write: (Boolean) -> Unit) {
    var advanced: Boolean
        get() = read()
        set(value) = write(value)
    companion object {
        fun stored(store: MotionPreferences = MotionPreferences()): CreateWizardPreferences =
            CreateWizardPreferences({ store.option("create.advanced", false) }, { store.put("create.advanced", it) })
        fun memory(initial: Boolean = false): CreateWizardPreferences {
            var value = initial
            return CreateWizardPreferences({ value }, { value = it })
        }
    }
}
