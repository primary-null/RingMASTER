package local.ringleader.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import local.ringleader.core.BridgeChoice
import local.ringleader.core.CT_MOUNT_OPTIONS
import local.ringleader.core.CT_ROOTFS_MOUNT_OPTIONS
import local.ringleader.core.CreateContext
import local.ringleader.core.CreateFields
import local.ringleader.core.CreateRead
import local.ringleader.core.CreateRequest
import local.ringleader.core.CreateResult
import local.ringleader.core.CreateSecrets
import local.ringleader.core.CreateSession
import local.ringleader.core.CtDraft
import local.ringleader.core.CtMount
import local.ringleader.core.CtNic
import local.ringleader.core.FieldId
import local.ringleader.core.FreezeResult
import local.ringleader.core.Ip6Mode
import local.ringleader.core.IpMode
import local.ringleader.core.IdCheck
import local.ringleader.core.MountSource
import local.ringleader.core.NewGuestKind
import local.ringleader.core.ParamLine
import local.ringleader.core.ReadSection
import local.ringleader.core.SecretState
import local.ringleader.core.StorageChoice
import local.ringleader.core.StorageVolume
import local.ringleader.core.createParamLines
import local.ringleader.core.ctCreateParams
import local.ringleader.core.freezeCt
import local.ringleader.core.nextFreeMount
import local.ringleader.core.validateCt
import local.ringleader.core.validateCtSecrets

/**
 * Container creation form. [onCreate] takes ownership of [CreateSecrets] and must close it after the one send attempt.
 * The editor buffers are cleared immediately after hand-off, on cancel, and whenever this composable leaves the tree.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateCtWizard(
    session: CreateSession,
    context: CreateContext,
    initialDraft: CtDraft,
    onCreate: (CreateRequest, CreateSecrets) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    initialStep: String = CreateFields.GENERAL,
    initialAdvanced: Boolean? = null,
    previewScroll: Int = 0,
    onDraftChange: (CtDraft) -> Unit = {},
    onRead: (CreateRead) -> Unit = {},
    onGetMedia: ((node: String, storage: String, kind: String) -> Unit)? = null,
    nodes: List<String> = listOf(session.node),
    onNode: (String) -> Unit = {},
    onResultAction: (ResultAction) -> Unit = {},
    validateDraft: (CtDraft, CreateContext) -> Map<FieldId, String> = ::validateCt,
    validateSecrets: (CharSequence, CharSequence, CharSequence) -> Map<FieldId, String> = ::validateCtSecrets,
    paramsFor: (CtDraft, CreateContext, SecretState) -> List<ParamLine> = { draft, ctx, secrets ->
        createParamLines(ctCreateParams(draft, ctx), secrets)
    },
    freezeDraft: (CtDraft, CreateContext) -> FreezeResult = ::freezeCt,
    nextMount: (CtDraft) -> CtMount? = ::nextFreeMount,
    preferences: CreateWizardPreferences? = null,
    storageMediaReady: StorageMediaReady? = null, onStorageMediaApplied: () -> Unit = {},
) {
    var draft by remember(session.epoch, session.generation) { mutableStateOf(initialDraft) }
    var mediaBaseline by remember { mutableStateOf(draft.templateStorage to draft.template) }
    LaunchedEffect(storageMediaReady) {
        storageMediaReady?.let { ready ->
            if(mediaReadyMatches(ready,session) && draft.node==ready.node && mediaBaseline==(draft.templateStorage to draft.template)) {
                draft=draft.copy(templateStorage=ready.storage,template=ready.volid,touched=draft.touched + CreateFields.TEMPLATE_STORAGE + CreateFields.TEMPLATE_VOLUME);onDraftChange(draft)
            }
            onStorageMediaApplied()
        }
    }
    var current by remember(initialStep) { mutableStateOf(initialStep) }
    val prefs = remember(preferences, initialAdvanced) {
        preferences ?: if (initialAdvanced == null) CreateWizardPreferences.stored() else CreateWizardPreferences.memory()
    }
    var advanced by remember { mutableStateOf(initialAdvanced ?: prefs.advanced) }
    val editors = androidx.compose.runtime.key(session.epoch, session.generation) { rememberCreateSecretEditors() }
    val password = editors.password
    val confirmation = editors.confirmation
    val sshKeys = editors.sshKeys
    var fileError by remember { mutableStateOf<String?>(null) }

    var start by remember(session.generation) { mutableStateOf(false) }
    var dismissedResult by remember(session.result) { mutableStateOf(false) }
    var serverErrors by remember(session.result) { mutableStateOf((session.result as? CreateResult.Rejected)?.fieldErrors.orEmpty()) }
    fun update(value: CtDraft) {
        serverErrors = retainCtServerErrors(draft, value, serverErrors)
        draft = value
        onDraftChange(value)
    }
    fun wipeEditors() = wipeCreateSecretInputs(password, confirmation, sshKeys)

    LaunchedEffect(session.nextId.value) {
        if (draft.vmid.isBlank()) session.nextId.value?.let { update(draft.copy(vmid = it.toString())) }
    }
    LaunchedEffect(session.generation, session.node) {
        if (draft.node != session.node) { wipeEditors(); update(invalidateCtNode(draft, session.node)) }
        if (session.tagRules.value == null && !session.tagRules.loading) onRead(CreateRead.TagRules)
    }
    LaunchedEffect(draft.vmid) {
        val vmid = draft.vmid.toIntOrNull() ?: return@LaunchedEffect
        delay(400)
        onRead(CreateRead.CheckId(vmid))
    }
    LaunchedEffect(draft.templateStorage) {
        if (draft.templateStorage.isNotBlank() && session.volumes[draft.templateStorage to "vztmpl"] == null) {
            onRead(CreateRead.Volumes(draft.templateStorage, "vztmpl"))
        }
    }

    val secretState = SecretState(password = password.text.isNotEmpty(), keyCount = nonBlankSecretLines(sshKeys.text))
    LaunchedEffect(secretState.password, secretState.keyCount) {
        if (secretState.password) serverErrors = serverErrors - CreateFields.PASSWORD - CreateFields.PASSWORD_CONFIRM
        if (secretState.keyCount > 0) serverErrors = serverErrors - CreateFields.SSH_KEYS
    }
    val draftErrors = validateDraft(draft, context)
    val secretErrors = validateSecrets(password.text, confirmation.text, sshKeys.text)
    val errors = draftErrors + secretErrors + serverErrors
    val preview = safeCreatePreview { paramsFor(draft, context, secretState) }
    val params = preview.lines + if (start) listOf(ParamLine("start", "1")) else emptyList()
    val steps = CT_STEP_TITLES.map { (id, title) ->
        WizardStep(id, title, errors.keys.count { CreateFields.step(it) == id }, stepLoading(id, session))
    }
    val ready = draft.node == session.node && errors.isEmpty() && preview.problem == null && safeFreezeCreate { freezeDraft(draft, context) } is FreezeResult.Ready

    MaterialTheme(colorScheme = ringColors) {
        Box(modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalCreatePreviewScroll provides previewScroll) { CreateWizardFrame(
                kind = NewGuestKind.Container,
                title = "Create container",
                headerNode = draft.node,
                headerId = draft.vmid.ifBlank { "—" },
                steps = steps,
                current = current,
                onStep = { current = it },
                params = params,
                advanced = advanced,
                onAdvanced = { advanced = it; prefs.advanced = it },
                canCreate = ready,
                createLabel = if (session.sample) "Simulate create" else "Create container ${draft.vmid.ifBlank { "…" }}…",
                onCreate = {
                    val frozen = safeFreezeCreate { freezeDraft(draft, context) }
                    if (!ready || errors.isNotEmpty() || frozen !is FreezeResult.Ready) {
                        current = errors.keys.firstOrNull()?.let(CreateFields::step) ?: CreateFields.GENERAL
                    } else {
                        handOffCreateSecrets(password, sshKeys) { secrets -> onCreate(frozen.request.copy(start = start), secrets) }
                        wipeSecretInput(confirmation)
                    }
                },
                onCancel = { wipeEditors(); onCancel() },
                dirty = draft != initialDraft || password.text.isNotEmpty() || confirmation.text.isNotEmpty() || sshKeys.text.isNotEmpty(),
                result = if (dismissedResult) null else session.result,
                onResultAction = { if (it == ResultAction.Edit) { dismissedResult = true; current = serverErrors.keys.firstOrNull()?.let(CreateFields::step) ?: current } else onResultAction(it) },
            ) { columns ->
                preview.problem?.let { Text(it, color = Tech.Red) }
                errors.filterKeys { CreateFields.step(it) == current }.values.distinct().forEach { Text(it, color = Tech.Red) }
                if (session.sample) Text("Demo mode. Nothing is sent.", color = Tech.Amber)
                SwitchLine("Start after created", "The server starts the new container as part of creation.", start) { start = it }
                when (current) {
                    CreateFields.GENERAL -> CtGeneralStep(draft, ::update, session, errors, advanced, password, confirmation, sshKeys, fileError, { fileError = it }, onRead, nodes) { node -> wipeEditors(); onNode(node) }
                    CreateFields.TEMPLATE -> CtTemplateStep(draft, ::update, session, context, errors, onRead, onGetMedia?.let { request -> { n,s,k -> mediaBaseline=draft.templateStorage to draft.template;request(n,s,k) } })
                    CreateFields.DISKS -> CtDisksStep(draft, ::update, context, errors, advanced, nextMount, session, onRead)
                    CreateFields.CPU -> CtCpuStep(draft, ::update, errors, advanced, columns)
                    CreateFields.MEMORY -> CtMemoryStep(draft, ::update, context, errors, columns)
                    CreateFields.NETWORK -> CtNetworkStep(draft, ::update, session, context, errors, advanced, columns, onRead)
                    CreateFields.DNS -> CtDnsStep(draft, ::update, errors, columns)
                }
            } }
        }
    }
}

private val CT_STEP_TITLES = listOf(
    CreateFields.GENERAL to "General",
    CreateFields.TEMPLATE to "Template",
    CreateFields.DISKS to "Disks",
    CreateFields.CPU to "CPU",
    CreateFields.MEMORY to "Memory",
    CreateFields.NETWORK to "Network",
    CreateFields.DNS to "DNS",
)

private fun stepLoading(step: String, session: CreateSession): Boolean = when (step) {
    CreateFields.GENERAL -> session.nextId.loading || session.pools.loading || session.tagRules.loading
    CreateFields.TEMPLATE -> session.volumes.values.any { it.loading }
    CreateFields.NETWORK -> session.bridges.loading
    else -> false
}

@Composable
private fun CtGeneralStep(
    draft: CtDraft,
    update: (CtDraft) -> Unit,
    session: CreateSession,
    errors: Map<FieldId, String>,
    advanced: Boolean,
    password: TextFieldState,
    confirmation: TextFieldState,
    sshKeys: TextFieldState,
    fileError: String?,
    onFileError: (String?) -> Unit,
    onRead: (CreateRead) -> Unit,
    nodes: List<String>,
    onNode: (String) -> Unit,
) {
    StepHeading("General", "Identity, isolation and access")
    FieldPair(
        { TechSelect("Node", nodes, draft.node, onNode, { it }, error = errors[CreateFields.NODE]) },
        { TechNumberField(draft.vmid, { update(draft.copy(vmid = it)) }, "CT ID", error = errors[CreateFields.VMID], hint = if (Appearance.showcase) null else idHint(session), modifier = Modifier.identity()) },
    )
    TechField(draft.hostname, { update(draft.copy(hostname = it)) }, "Hostname", error = errors[CreateFields.NAME], placeholder = "files.example.test", modifier = Modifier.identity())
    SwitchLine("Unprivileged", "Recommended isolation; on by default.", draft.unprivileged) {
        update(draft.copy(unprivileged = it, nesting = if (it) draft.nesting else false))
    }
    if (!draft.unprivileged) WarningLine("A privileged container needs Sys.Modify on /.")
    SwitchLine("Nesting", "Allow nested workloads inside the container.", draft.nesting, enabled = draft.unprivileged) {
        update(draft.copy(nesting = it))
    }
    SwitchLine("Add to HA", "Ask the cluster to manage the new container.", draft.haManaged) { update(draft.copy(haManaged = it)) }
    val pools = listOf(local.ringleader.core.CreateChoice(null, "None")) + session.pools.value.orEmpty().map { local.ringleader.core.CreateChoice(it.id, it.id) }
    TechSelect("Resource pool", pools, pools.firstOrNull { it.value == draft.pool }, { update(draft.copy(pool = it.value)) }, { it.label }, loading = session.pools.loading, error = errors[CreateFields.POOL] ?: session.pools.error)
    if (session.pools.error != null) MiniAction("Retry pools") { onRead(CreateRead.Pools) }
    FieldPair(
        { SecretInput(password, "Root password", error = errors[CreateFields.PASSWORD]) },
        { SecretInput(confirmation, "Confirm password", error = errors[CreateFields.PASSWORD_CONFIRM]) },
    )
    SecretInput(sshKeys, "SSH public keys", multiLine = true, error = errors[CreateFields.SSH_KEYS])
    SshKeyFile(sshKeys, fileError, onFileError)
    if (advanced) {
        val tags = draft.tags.joinToString("; ")
        TechField(tags, { value -> update(draft.copy(tags = value.split(';').map(String::trim).filter(String::isNotEmpty))) }, "Tags", error = errors[CreateFields.TAGS], hint = "Separate tags with semicolons.", modifier = Modifier.identity())
    }
}

@Composable
private fun CtTemplateStep(
    draft: CtDraft,
    update: (CtDraft) -> Unit,
    session: CreateSession,
    context: CreateContext,
    errors: Map<FieldId, String>,
    onRead: (CreateRead) -> Unit,
    onGetMedia: ((String, String, String) -> Unit)?,
) {
    var showAll by remember { mutableStateOf(false) }
    val stores = context.storages.filter { "vztmpl" in it.content }
    TechSelect("Storage", stores, stores.firstOrNull { it.id == draft.templateStorage }, { choice ->
        val id = choice.id
        update(draft.copy(templateStorage = id, template = ""))
        if (id.isNotEmpty()) onRead(CreateRead.Volumes(id, "vztmpl"))
    }, { storageLabel(it) }, error = errors[CreateFields.TEMPLATE_STORAGE], emptyText = "No container-template storage is recorded on this node.")
    val section = session.volumes[draft.templateStorage to "vztmpl"] ?: ReadSection()
    val rows = section.value.orEmpty().filter { showAll || templateMatchesHost(it, context.hostArch) }
    ListStateView(section.copy(value = rows), "Reading templates\u2026", "No container templates on this storage. Download one in Storage.", onRetry = {
        if (draft.templateStorage.isNotBlank()) onRead(CreateRead.Volumes(draft.templateStorage, "vztmpl"))
    }) {
        TechSelect("Template", rows, rows.firstOrNull { it.volid == draft.template }, { update(draft.copy(template = it.volid)) }, ::templateLabel, error = errors[CreateFields.TEMPLATE_VOLUME])
    }
    TechCheck(showAll, { showAll = it }, "Show all architectures")
    if (onGetMedia != null) MiniAction("Get template") { onGetMedia(draft.node, draft.templateStorage, "vztmpl") }
}

@Composable
private fun CtDisksStep(
    draft: CtDraft,
    update: (CtDraft) -> Unit,
    context: CreateContext,
    errors: Map<FieldId, String>,
    advanced: Boolean,
    nextMount: (CtDraft) -> CtMount?,
    session: CreateSession,
    onRead: (CreateRead) -> Unit,
) {
    StepHeading("Disks", "Root volume and mount points")
    draft.mounts.forEachIndexed { index, mount ->
        val root = mount.key == "rootfs"
        Column(Modifier.fillMaxWidth().border(1.dp, Tech.Edge, Tech.Plate).padding(11.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (root) "ROOT DISK" else "MOUNT ${mount.key.removePrefix("mp")}", Modifier.weight(1f), color = Tech.Ink, fontFamily = Tech.Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                if (!root) MiniAction("Remove") { update(draft.copy(mounts = draft.mounts.filterIndexed { at, _ -> at != index })) }
            }
            errors[CreateFields.disk(mount.key)]?.let { WarningLine(it) }
            Spacer(Modifier.height(8.dp))
            MountSourceFields(mount, context, errors, session, onRead) { changed -> updateMount(draft, index, changed, update) }
            if (!root) {
                Spacer(Modifier.height(8.dp))
                TechField(mount.path, { updateMount(draft, index, mount.copy(path = it), update) }, "Path", error = errors[CreateFields.disk(mount.key, "path")], placeholder = "/srv/data", modifier = Modifier.identity())
                TechCheck(mount.backup, { updateMount(draft, index, mount.copy(backup = it), update) }, "Include in backup")
            }
            if (advanced) MountAdvanced(draft, mount, context, errors) { changed -> updateMount(draft, index, changed, update) }
        }
    }
    MiniAction("Add mount point", enabled = nextMount(draft) != null) {
        nextMount(draft)?.let { update(draft.copy(mounts = draft.mounts + it)) }
    }
}

@Composable
private fun MountSourceFields(mount: CtMount, context: CreateContext, errors: Map<FieldId, String>, session: CreateSession, onRead: (CreateRead) -> Unit, onMount: (CtMount) -> Unit) {
    val source = mount.source
    val stores = context.storages.filter { "rootdir" in it.content }
    TechChoice(listOf("New volume", "Existing volume"), if (source is MountSource.Existing) "Existing volume" else "New volume", onSelect = { mode ->
        onMount(mount.copy(source = if (mode == "Existing volume") MountSource.Existing((source as? MountSource.New)?.storage?.takeIf { it.isNotBlank() }?.let { "$it:" }.orEmpty()) else MountSource.New()))
    })
    when (source) {
        is MountSource.New -> FieldPair(
            { TechSelect("Storage", stores, stores.firstOrNull { it.id == source.storage }, { onMount(mount.copy(source = source.copy(storage = it.id))) }, { storageLabel(it) }, error = errors[CreateFields.disk(mount.key, "storage")]) },
            { TechNumberField(source.sizeGib, { onMount(mount.copy(source = source.copy(sizeGib = it))) }, "Size (GiB)", error = errors[CreateFields.disk(mount.key, "size")], modifier = Modifier.identity()) },
        )
        is MountSource.Existing -> ExistingCreateVolumePicker(session, context, "rootdir", source.volid, onRead, { onMount(mount.copy(source = MountSource.Existing(it))) }, errors[CreateFields.disk(mount.key, "volume")])
    }
}

@Composable
private fun MountAdvanced(draft: CtDraft, mount: CtMount, context: CreateContext, errors: Map<FieldId, String>, onMount: (CtMount) -> Unit) {
    Spacer(Modifier.height(10.dp))
    SectionLabel("Advanced mount options")
    val storage = (mount.source as? MountSource.New)?.storage
    val plugin = context.storages.firstOrNull { it.id == storage }?.pluginType.orEmpty().lowercase()
    val quotaEnabled = !draft.unprivileged && plugin !in setOf("zfs", "zfspool")
    TechCheck(mount.quota, { onMount(mount.copy(quota = it)) }, "Quota", enabled = quotaEnabled)
    if (!quotaEnabled) Text("Quotas need a privileged container and a non-ZFS storage.", color = Tech.Muted, fontSize = 11.sp)
    if (mount.key != "rootfs") TechCheck(mount.readOnly, { onMount(mount.copy(readOnly = it)) }, "Read-only")
    Text("MOUNT OPTIONS", color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = .8.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        (if (mount.key == "rootfs") CT_ROOTFS_MOUNT_OPTIONS else CT_MOUNT_OPTIONS).forEach { option ->
            TechCheck(option in mount.mountOptions, { on -> onMount(mount.copy(mountOptions = if (on) mount.mountOptions + option else mount.mountOptions - option)) }, option)
        }
    }
    TechChoice(listOf("ACL default", "ACL on", "ACL off"), when (mount.acl) { null -> "ACL default"; true -> "ACL on"; false -> "ACL off" }, onSelect = { onMount(mount.copy(acl = when (it) { "ACL on" -> true; "ACL off" -> false; else -> null })) })
    TechCheck(mount.skipReplication, { onMount(mount.copy(skipReplication = it)) }, "Skip replication")
    if (mount.key != "rootfs") TechCheck(mount.keepAttrs, { onMount(mount.copy(keepAttrs = it)) }, "Keep attributes")
    if (context.release >= 9) IdMapEditor(mount, onMount, error = errors[CreateFields.disk(mount.key, "idmap")])
}

@Composable
private fun CtCpuStep(draft: CtDraft, update: (CtDraft) -> Unit, errors: Map<FieldId, String>, advanced: Boolean, columns: Int) {
    StepHeading("CPU", "Container scheduling limits")
    AdaptiveFields(columns,
        { TechNumberField(draft.cores, { update(draft.copy(cores = it)) }, "Cores", error = errors[CreateFields.CORES], hint = "The server may schedule these across the node.", modifier = Modifier.identity()) },
        if (advanced) ({ TechNumberField(draft.cpuLimit, { update(draft.copy(cpuLimit = it)) }, "CPU limit", error = errors[CreateFields.CPU_LIMIT], hint = "Blank means no hard cap.", modifier = Modifier.identity()) }) else null,
        if (advanced) ({ TechNumberField(draft.cpuUnits, { update(draft.copy(cpuUnits = it)) }, "CPU units", error = errors[CreateFields.CPU_UNITS], hint = "Blank uses the server default.", modifier = Modifier.identity()) }) else null,
    )
}

@Composable
private fun CtMemoryStep(draft: CtDraft, update: (CtDraft) -> Unit, context: CreateContext, errors: Map<FieldId, String>, columns: Int) {
    StepHeading("Memory", "RAM and swap in MiB")
    AdaptiveFields(columns,
        { TechNumberField(draft.memoryMib, { update(draft.copy(memoryMib = it)) }, "Memory (MiB)", error = errors[CreateFields.MEMORY_MIB], modifier = Modifier.identity()) },
        { TechNumberField(draft.swapMib, { update(draft.copy(swapMib = it)) }, "Swap (MiB)", error = errors[CreateFields.SWAP], modifier = Modifier.identity()) },
    )
    val free = context.nodeMemoryBytes?.let { total -> total - (context.nodeMemoryUsedBytes ?: 0) }
    free?.let { Text("The recorded node has ${formatBytes(it.coerceAtLeast(0))} not in use. Thin and shared workloads may overcommit it.", color = Tech.Muted, fontSize = 11.sp) }
}

@Composable
private fun CtNetworkStep(
    draft: CtDraft,
    update: (CtDraft) -> Unit,
    session: CreateSession,
    context: CreateContext,
    errors: Map<FieldId, String>,
    advanced: Boolean,
    columns: Int,
    onRead: (CreateRead) -> Unit,
) {
    val nic = draft.nic
    fun change(value: CtNic) = update(draft.copy(nic = value))
    StepHeading("Network", "One container interface")
    AdaptiveFields(columns,
        { TechField(nic.name, { change(nic.copy(name = it)) }, "Name", error = errors[CreateFields.NIC_NAME], modifier = Modifier.identity()) },
        { TechField(nic.mac, { change(nic.copy(mac = it)) }, "MAC address", error = errors[CreateFields.MAC], placeholder = "auto", modifier = Modifier.identity()) },
    )
    val bridges = session.bridges.value.orEmpty()
    TechSelect("Bridge", bridges, bridges.firstOrNull { it.name == nic.bridge }, { change(nic.copy(bridge = it.name)) }, ::bridgeLabel, loading = session.bridges.loading, error = errors[CreateFields.BRIDGE] ?: session.bridges.error, emptyText = "No bridges you may use on this node. Bridges need SDN.Use or SDN.Audit on /sdn/zones/localnetwork.")
    if (session.bridges.error != null) MiniAction("Retry bridges") { onRead(CreateRead.Bridges) }
    FieldPair(
        { TechField(nic.vlan, { change(nic.copy(vlan = it)) }, "VLAN tag", error = errors[CreateFields.VLAN], hint = vlanHint(nic, bridges), modifier = Modifier.identity()) },
        { SwitchLine("Firewall", "Apply the bridge firewall.", nic.firewall) { change(nic.copy(firewall = it)) } },
    )
    SectionLabel("IPv4")
    TechChoice(listOf("Static", "DHCP"), if (nic.ipv4 is IpMode.Dhcp) "DHCP" else "Static", onSelect = { mode -> change(nic.copy(ipv4 = if (mode == "DHCP") IpMode.Dhcp else IpMode.Static())) })
    (nic.ipv4 as? IpMode.Static)?.let { ipv4 ->
        AdaptiveFields(columns,
            { TechField(ipv4.cidr, { change(nic.copy(ipv4 = ipv4.copy(cidr = it))) }, "Address / CIDR", error = errors[CreateFields.IP4], placeholder = "192.0.2.20/24", modifier = Modifier.identity()) },
            { TechField(ipv4.gateway, { change(nic.copy(ipv4 = ipv4.copy(gateway = it))) }, "Gateway", error = errors[CreateFields.GW4], placeholder = "192.0.2.1", modifier = Modifier.identity()) },
        )
    }
    SectionLabel("IPv6")
    TechChoice(listOf("Static", "DHCP", "SLAAC"), when (nic.ipv6) { is Ip6Mode.Dhcp -> "DHCP"; is Ip6Mode.Slaac -> "SLAAC"; else -> "Static" }, onSelect = { mode ->
        change(nic.copy(ipv6 = when (mode) { "DHCP" -> Ip6Mode.Dhcp; "SLAAC" -> Ip6Mode.Slaac; else -> Ip6Mode.Static() }))
    })
    (nic.ipv6 as? Ip6Mode.Static)?.let { ipv6 ->
        AdaptiveFields(columns,
            { TechField(ipv6.cidr, { change(nic.copy(ipv6 = ipv6.copy(cidr = it))) }, "Address / CIDR", error = errors[CreateFields.IP6], placeholder = "2001:db8::20/64", modifier = Modifier.identity()) },
            { TechField(ipv6.gateway, { change(nic.copy(ipv6 = ipv6.copy(gateway = it))) }, "Gateway", error = errors[CreateFields.GW6], placeholder = "2001:db8::1", modifier = Modifier.identity()) },
        )
    }
    if (advanced) {
        SwitchLine("Disconnect", "Create the interface without a live link.", nic.disconnect) { change(nic.copy(disconnect = it)) }
        AdaptiveFields(columns,
            { TechField(nic.mtu, { change(nic.copy(mtu = it)) }, "MTU", error = errors[CreateFields.MTU], hint = "1 keeps the bridge MTU.", modifier = Modifier.identity()) },
            { TechField(nic.rate, { change(nic.copy(rate = it)) }, "Rate limit (MB/s)", error = errors[CreateFields.RATE], modifier = Modifier.identity()) },
        )
        if (context.release >= 9) SwitchLine("Host-managed", "Let the host manage this interface.", nic.hostManaged) { change(nic.copy(hostManaged = it)) }
    }
}

@Composable
private fun CtDnsStep(draft: CtDraft, update: (CtDraft) -> Unit, errors: Map<FieldId, String>, columns: Int) {
    StepHeading("DNS", "Leave blank to use the host's settings")
    AdaptiveFields(columns,
        { TechField(draft.searchDomain, { update(draft.copy(searchDomain = it)) }, "DNS domain", error = errors[CreateFields.SEARCH_DOMAIN], placeholder = "Use the host's settings", modifier = Modifier.identity()) },
        { TechField(draft.nameservers, { update(draft.copy(nameservers = it)) }, "DNS servers", error = errors[CreateFields.NAMESERVERS], placeholder = "Use the host's settings", hint = "Separate multiple addresses with spaces.", modifier = Modifier.identity()) },
    )
}

@Composable
private fun StepHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title.uppercase(), color = Tech.Ink, fontFamily = Tech.Mono, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
        Text(subtitle, color = Tech.Muted, fontSize = 12.sp)
    }
}

@Composable
private fun FieldPair(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 620.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) { first() }
            Column(Modifier.weight(1f)) { second() }
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) { first(); second() }
    }
}

@Composable
private fun AdaptiveFields(columns: Int, vararg fields: (@Composable () -> Unit)?) {
    val present = fields.filterNotNull()
    if (columns > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        present.forEach { field -> Column(Modifier.weight(1f)) { field() } }
    } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) { present.forEach { it() } }
}

@Composable
private fun SwitchLine(label: String, hint: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 34.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (enabled) Tech.Ink else Tech.Muted, fontSize = 13.sp)
            Text(hint, color = Tech.Muted, fontSize = 11.sp)
        }
        TechSwitch(checked, onChange, enabled = enabled)
    }
}

@Composable private fun WarningLine(text: String) = Text(text, color = Tech.Amber, fontSize = 11.sp)

private fun updateMount(draft: CtDraft, index: Int, mount: CtMount, update: (CtDraft) -> Unit) =
    update(draft.copy(mounts = draft.mounts.mapIndexed { at, old -> if (at == index) mount else old }))

private fun nonBlankSecretLines(text: CharSequence): Int {
    var count = 0
    var line = false
    for (char in text) if (char == '\n' || char == '\r') { if (line) count++; line = false } else if (!char.isWhitespace()) line = true
    if (line) count++
    return count
}

private fun idHint(session: CreateSession): String? = when (val check = session.idCheck) {
    is IdCheck.Checking -> "Checking…"
    is IdCheck.Free -> "${check.id} is free."
    is IdCheck.Taken -> check.text
    is IdCheck.Failed -> check.text
    IdCheck.Idle -> when {
        session.nextId.loading -> "Finding the next free ID…"
        session.nextId.error != null -> session.nextId.error
        session.nextId.value != null -> "Suggested: ${session.nextId.value}"
        else -> null
    }
}

private fun storageLabel(storage: StorageChoice): String = buildString {
    append(storage.id)
    storage.pluginType?.let { append(" · "); append(it) }
    storage.totalMinusUsedBytes?.let { append(" · "); append(formatBytes(it)); append(" free") }
}

private fun templateLabel(volume: StorageVolume?): String = volume?.let {
    val name = it.volid.substringAfterLast('/')
    val oci = if (name.contains("oci", ignoreCase = true)) " · OCI" else ""
    "$name · ${formatBytes(it.sizeBytes ?: it.approximateSizeBytes ?: 0)}$oci"
} ?: "Choose template"

private fun templateMatchesHost(volume: StorageVolume, host: String): Boolean {
    val name = volume.volid.lowercase()
    val arm = "arm64" in name || "aarch64" in name
    return if (host.lowercase() in setOf("arm64", "aarch64")) arm else !arm
}

private fun bridgeLabel(bridge: BridgeChoice?): String = bridge?.let {
    buildString { append(it.name); append(" · "); append(it.type); if (it.vlanAware) append(" · VLAN-aware"); it.comment?.let { note -> append(" · "); append(note) } }
} ?: "Choose bridge"

private fun vlanHint(nic: CtNic, bridges: List<BridgeChoice>): String? = if (nic.vlan.isNotBlank() && bridges.firstOrNull { it.name == nic.bridge }?.vlanAware == false) "The bridge is not VLAN-aware." else null

private fun formatBytes(value: Long): String {
    if (value <= 0) return "0 B"
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var amount = value.toDouble(); var unit = 0
    while (amount >= 1024 && unit < units.lastIndex) { amount /= 1024; unit++ }
    return if (amount >= 10 || unit == 0) "${amount.toInt()} ${units[unit]}" else "${"%.1f".format(java.util.Locale.ROOT, amount)} ${units[unit]}"
}


/** Keep typed sizing and identity; node-scoped choices must be chosen against the new lists. */
internal fun invalidateCtNode(draft: CtDraft, node: String): CtDraft = draft.copy(
    node = node, templateStorage = "", template = "", nic = draft.nic.copy(bridge = ""),
    mounts = draft.mounts.map { mount -> mount.copy(source = when(val source = mount.source) {
        is MountSource.New -> source.copy(storage = "")
        is MountSource.Existing -> source.copy(volid = "")
    }) },
)

internal fun retainCtServerErrors(old: CtDraft, edited: CtDraft, errors: Map<FieldId, String>): Map<FieldId, String> {
    fun values(d: CtDraft): Map<FieldId, Any?> = mapOf(
        CreateFields.VMID to d.vmid, CreateFields.NODE to d.node, CreateFields.NAME to d.hostname,
        CreateFields.POOL to d.pool, CreateFields.TAGS to d.tags, CreateFields.HA to d.haManaged,
        CreateFields.UNPRIVILEGED to d.unprivileged, CreateFields.NESTING to d.nesting,
        CreateFields.TEMPLATE_STORAGE to d.templateStorage, CreateFields.TEMPLATE_VOLUME to d.template,
        CreateFields.CORES to d.cores, CreateFields.CPU_LIMIT to d.cpuLimit, CreateFields.CPU_UNITS to d.cpuUnits,
        CreateFields.MEMORY_MIB to d.memoryMib, CreateFields.SWAP to d.swapMib,
        CreateFields.SEARCH_DOMAIN to d.searchDomain, CreateFields.NAMESERVERS to d.nameservers,
        CreateFields.NETWORK to d.nic, CreateFields.DISKS to d.mounts.map { it.key },
    ) + d.mounts.associate { CreateFields.disk(it.key) to it }
    val before = values(old); val after = values(edited)
    val changed = before.keys.filter { before[it] != after[it] }
    return errors.filterKeys { field -> changed.none { field == it || field.startsWith("$it.") } }
}
