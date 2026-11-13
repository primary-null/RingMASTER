package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import local.ringleader.core.*

/** The VM editor. Pure-core seams let offline scene tests run before the core implementation is merged. */
@Composable fun CreateVmWizard(session: CreateSession, context: CreateContext, nodes: List<String>,
    onNode: (String) -> Unit, onRead: (CreateRead) -> Unit, onCreate: (CreateRequest) -> Unit,
    onCancel: () -> Unit, onResultAction: (ResultAction) -> Unit,
    onGetMedia: ((node: String, storage: String, kind: String) -> Unit)? = null,
    initialStep: String = CreateFields.GENERAL,
    initialAdvanced: Boolean? = null,
    rootPrivileges: Set<String>? = null,
    defaults: (String, CreateContext) -> VmDraft = ::defaultVmDraft,
    validate: (VmDraft, CreateContext) -> Map<FieldId, String> = ::validateVm,
    parameters: (VmDraft, CreateContext) -> List<ParamLine> = { d,c -> createParamLines(vmCreateParams(d,c), SecretState()) },
    freeze: (VmDraft, CreateContext) -> FreezeResult = ::freezeVm,
    osDefaults: (VmDraft, String, String?) -> Applied<VmDraft> = ::applyOsDefaults,
    archDefaults: (VmDraft, String?) -> Applied<VmDraft> = ::applyArch,
    driverDefaults: (VmDraft, Boolean) -> Applied<VmDraft> = ::applyDriverDisc,
    storageDefaults: (VmDisk, StorageChoice) -> VmDisk = ::applyStorage,
    busDefaults: (VmDisk, DiskBus, String?) -> VmDisk = ::applyBus,
    preferences: CreateWizardPreferences? = null,
    storageMediaReady: StorageMediaReady? = null, onStorageMediaApplied: () -> Unit = {},
) {
    var draft by remember(session.epoch) { mutableStateOf(defaults(session.node, context)) }
    var mediaSlot by remember { mutableStateOf("Install media") }
    var mediaBaseline by remember { mutableStateOf<CdDrive?>(draft.install) }
    var current by remember { mutableStateOf(initialStep) }
    val prefs = remember(preferences, initialAdvanced) {
        preferences ?: if (initialAdvanced == null) CreateWizardPreferences.stored() else CreateWizardPreferences.memory()
    }
    var advanced by remember { mutableStateOf(initialAdvanced ?: prefs.advanced) }
    var dirty by remember { mutableStateOf(false) }
    LaunchedEffect(storageMediaReady) {
        storageMediaReady?.let { ready ->
            if(mediaReadyMatches(ready,session) && draft.node==ready.node && mediaBaseline==(if(mediaSlot=="Driver disc") draft.driverDisc else draft.install)) {
                val media=CdMedia.Iso(ready.storage,ready.volid)
                draft=if(mediaSlot=="Driver disc") draft.copy(driverDisc=draft.driverDisc?.copy(media=media)) else draft.copy(isoStorage=ready.storage,install=draft.install.copy(media=media))
                draft=draft.copy(touched=draft.touched + if(mediaSlot=="Driver disc") CreateFields.DRIVER_DISC else CreateFields.INSTALL)
                dirty=true
            }
            onStorageMediaApplied()
        }
    }

    var start by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var dismissedResult by remember(session.result) { mutableStateOf(false) }
    var serverErrors by remember(session.result) { mutableStateOf((session.result as? CreateResult.Rejected)?.fieldErrors.orEmpty()) }
    val errors = validate(draft, context) + vmSelectionErrors(draft, context, session) + serverErrors
    fun edit(id: String, value: VmDraft) {
        serverErrors = serverErrors.filterKeys { it != id && !it.startsWith("$id.") }
        draft = value.copy(touched = value.touched + id); dirty = true
    }
    LaunchedEffect(session.node) { if (draft.node != session.node) draft = invalidateVmNode(draft, session.node) }
    LaunchedEffect(session.nextId.value) { if (draft.vmid.isBlank()) session.nextId.value?.let { draft = draft.copy(vmid = it.toString()) } }
    LaunchedEffect(session.generation, draft.install, draft.driverDisc) {
        listOfNotNull(draft.install.media as? CdMedia.Iso, draft.driverDisc?.media as? CdMedia.Iso)
            .map { it.storage }.filter { it.isNotBlank() }.distinct().forEach { storage ->
                if (session.volumes[storage to "iso"] == null) onRead(CreateRead.Volumes(storage, "iso"))
            }
    }
    LaunchedEffect(draft.arch, session.node) {
        if (CreateFields.ARCH in draft.touched) {
            onRead(CreateRead.CpuModels(draft.arch))
            onRead(CreateRead.CpuFlags(draft.arch, if (draft.arch == null) "kvm" else "tcg"))
        }
    }
    LaunchedEffect(draft.vmid) { delay(400); draft.vmid.toIntOrNull()?.let { onRead(CreateRead.CheckId(it)) } }
    LaunchedEffect(note) { if (note.isNotEmpty()) { delay(3000); note = "" } }
    val loading = session.nextId.loading || session.pools.loading || session.bridges.loading || session.cpuModels.loading || session.cpuFlags.loading || session.volumes.values.any { it.loading }
    val steps = CreateFields.VM_STEPS.map { id -> WizardStep(id, mapOf("os" to "OS", "cpu" to "CPU")[id] ?: id.replaceFirstChar { it.uppercase() }, errors.keys.count { CreateFields.step(it) == id }, when(id) { "general" -> session.nextId.loading || session.pools.loading; "os" -> session.volumes.values.any { it.loading }; "cpu" -> session.cpuModels.loading || session.cpuFlags.loading; "network" -> session.bridges.loading; else -> false }) }
    val preview = safeCreatePreview { parameters(draft, context) }
    val lines = preview.lines + if (start) listOf(ParamLine("start", "1")) else emptyList()
    CreateWizardFrame(NewGuestKind.Vm, if (session.sample) "New VM \u00b7 Demo \u00b7 sample lists" else "New VM", draft.node, draft.vmid,
        steps, current, { current = it }, lines, advanced, { advanced = it; prefs.advanced = it }, errors.isEmpty() && preview.problem == null && !loading && draft.node == session.node,
        "Create VM ${draft.vmid}\u2026", {
            if (errors.isNotEmpty() || preview.problem != null || loading || draft.node != session.node) return@CreateWizardFrame
            when(val frozen = safeFreezeCreate { freeze(draft, context) }) {
                is FreezeResult.Ready -> onCreate(frozen.request.copy(start = start))
                is FreezeResult.Invalid -> current = frozen.errors.keys.firstOrNull()?.let(CreateFields::step) ?: current
            }
        }, onCancel, dirty, if (dismissedResult) null else session.result, {
            if (it == ResultAction.Edit) dismissedResult = true else onResultAction(it)
        }) { columns ->
        preview.problem?.let { Text(it, color = Tech.Red) }
        SectionLabel(steps.first { it.id == current }.title)
        if (session.sample) Text(DEMO_CREATE_SUBTITLE, color = Tech.Amber, fontSize = 11.sp)
        if (note.isNotEmpty()) Text(note, Modifier.identity(), color = Tech.Amber, fontSize = 12.sp)
        @Composable fun field(id: String, label: String, value: String, change: (String) -> VmDraft) {
            TechField(value, { edit(id, change(it)) }, label, Modifier.identity(), error = errors[id])
        }
        @Composable fun number(id: String, label: String, value: String, unit: String? = null, enabled: Boolean = true, change: (String) -> VmDraft) {
            TechNumberField(value, { edit(id, change(it)) }, label, modifier = if (id == CreateFields.VMID) Modifier.identity() else Modifier, unit = unit, enabled = enabled, error = errors[id])
        }
        @Composable fun choice(id: String, label: String, values: List<CreateChoice>, selected: String?, change: (String?) -> VmDraft) {
            TechSelect(label, values, values.firstOrNull { it.value == selected }, { edit(id, change(it.value)) }, { it.label }, error = errors[id])
        }
        @Composable fun storage(label: String, selected: String, content: String = "images", change: (StorageChoice) -> Unit) {
            val rows = context.storages.filter { it.node == draft.node && content in it.content }
            TechSelect(label, rows, rows.firstOrNull { it.id == selected }, change,
                { "${it.id} \u00b7 ${it.pluginType ?: "unknown"} \u00b7 total minus used: ${it.totalMinusUsedBytes?.let { b -> "${b / 1073741824} GiB" } ?: "unknown"}${if (it.shared == true) " \u00b7 shared" else ""}" },
                emptyText = "No recorded storage for $content. Storage this account cannot audit is not listed.")
        }
        @Composable fun media(label: String, drive: CdDrive, change: (CdDrive) -> Unit) {
            Text(label, color = Tech.Muted)
            val selected = when(drive.media) { is CdMedia.Iso -> "ISO image"; CdMedia.Physical -> "Physical drive"; CdMedia.None -> "None" }
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ISO image", "Physical drive", "None").forEach { option ->
                    HelpTip(if (option == "Physical drive") "Needs Sys.Console on /." else option) {
                        TechAction(option, active = selected == option,
                            enabled = option != "Physical drive" || rootPrivileges == null || "Sys.Console" in rootPrivileges) {
                            change(drive.copy(media = when(option) { "ISO image" -> CdMedia.Iso(draft.isoStorage); "Physical drive" -> CdMedia.Physical; else -> CdMedia.None }))
                        }
                    }
                }
            }
            (drive.media as? CdMedia.Iso)?.let { media ->
                val iso = if (media.storage.isBlank()) media.copy(storage = draft.isoStorage) else media
                storage("ISO storage", iso.storage, "iso") { row -> change(drive.copy(media = CdMedia.Iso(row.id))); draft = draft.copy(isoStorage = row.id) }
                if (iso.storage.isNotBlank()) {
                    val state = session.volumes[iso.storage to "iso"] ?: ReadSection()
                    ListStateView(state, "Reading ISO images\u2026", "No ISO images on this storage. Upload one in Storage.", { onRead(CreateRead.Volumes(iso.storage, "iso")) }) { rows ->
                        TechSelect("ISO image", rows, rows.firstOrNull { it.volid == iso.volid }, { change(drive.copy(media = iso.copy(volid = it.volid))) }, { it.volid.substringAfter(":iso/") })
                    }
                    if (onGetMedia != null) MiniAction("Get ISO image") { mediaSlot=label;mediaBaseline=drive; onGetMedia(draft.node, iso.storage, "iso") }
                }
            }
        }
        VmFieldGrid(columns) {
        when(current) {
            "general" -> {
                TechSelect("Node", nodes, draft.node, { if (it != draft.node) { edit(CreateFields.NODE, invalidateVmNode(draft, it)); onNode(it) } })
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    number(CreateFields.VMID, "VM ID", draft.vmid) { draft.copy(vmid = it) }
                    Text(createIdCheckText(session.idCheck), Modifier.identity(), color = Tech.Muted, fontSize = 11.sp)
                }
                field(CreateFields.NAME, "Name", draft.name) { draft.copy(name = it) }
                ListStateView(session.pools, "Reading pools\u2026", "No resource pools available.", { onRead(CreateRead.Pools) }) { rows ->
                    TechSelect("Resource pool", listOf(PoolChoice("", "None")) + rows, rows.firstOrNull { it.id == draft.pool } ?: PoolChoice("", "None"), { edit(CreateFields.POOL, draft.copy(pool = it.id.ifBlank { null })) }, { it.id.ifBlank { "None" } })
                }
                if (context.release >= 9) TechCheck(draft.haManaged, { edit(CreateFields.HA, draft.copy(haManaged = it)) }, "Add to HA", enabled = rootPrivileges == null || "Sys.Console" in rootPrivileges)
                if (advanced) {
                    TechCheck(draft.onBoot, { edit(CreateFields.ON_BOOT, draft.copy(onBoot = it)) }, "Start at boot")
                    number(CreateFields.START_ORDER, "Start order", draft.startOrder, enabled = rootPrivileges == null || "Sys.Modify" in rootPrivileges) { draft.copy(startOrder = it) }
                    number(CreateFields.START_UP, "Startup delay", draft.startUp, "seconds", enabled = rootPrivileges == null || "Sys.Modify" in rootPrivileges) { draft.copy(startUp = it) }
                    number(CreateFields.START_DOWN, "Shutdown timeout", draft.startDown, "seconds", enabled = rootPrivileges == null || "Sys.Modify" in rootPrivileges) { draft.copy(startDown = it) }
                    if (context.release >= 9) choice(CreateFields.ARCH, "vCPU architecture", ARCH_CHOICES, draft.arch) { val applied = archDefaults(draft, it); note = applied.notes.joinToString(" "); applied.draft }
                    field(CreateFields.TAGS, "Tags (separate with semicolons)", draft.tags.joinToString(";")) { draft.copy(tags = it.split(';').filter(String::isNotBlank)) }
                }
            }
            "os" -> {
                media("Install media", draft.install) { edit(CreateFields.INSTALL, draft.copy(install = it)) }
                val families = if (draft.arch == "aarch64") listOf(OS_LINUX, OS_OTHER) else listOf(OS_LINUX, OS_WINDOWS, OS_SOLARIS, OS_OTHER)
                val family = OS_CHOICES.firstOrNull { it.value == draft.osType }?.family ?: OS_LINUX
                TechSelect("Guest OS type", families, family, { val applied = osDefaults(draft, OS_CHOICES.first { row -> row.family == it }.value, draft.arch); edit(CreateFields.OS_TYPE, applied.draft); note = applied.notes.joinToString(" ") })
                TechSelect("Version", OS_CHOICES.filter { it.family == family }, OS_CHOICES.firstOrNull { it.value == draft.osType }, { val applied = osDefaults(draft, it.value, draft.arch); edit(CreateFields.OS_TYPE, applied.draft); note = applied.notes.joinToString(" ") }, { it.label })
                if (family == OS_WINDOWS) {
                    TechCheck(draft.driverDisc != null, { edit(CreateFields.DRIVER_DISC, driverDefaults(draft, it).draft) }, "VirtIO driver disc")
                    draft.driverDisc?.let { media("Driver disc", it) { disc -> edit(CreateFields.DRIVER_DISC, draft.copy(driverDisc = disc)) } }
                }
            }
            "system" -> {
                choice(CreateFields.VGA, "Graphics", VGA_CHOICES, draft.vga) { draft.copy(vga = it) }
                choice(CreateFields.MACHINE, "Machine", if (draft.arch == "aarch64") listOf(CreateChoice(null, "Default (virt)")) else MACHINE_CHOICES, draft.machine) { draft.copy(machine = it) }
                choice(CreateFields.BIOS, "Firmware", if (draft.arch == "aarch64") BIOS_CHOICES.filter { it.value == "ovmf" } else BIOS_CHOICES, draft.bios) { draft.copy(bios = it) }
                if (draft.bios == "ovmf") {
                    TechCheck(draft.addEfi, { edit(CreateFields.EFI, draft.copy(addEfi = it)) }, "Add EFI disk")
                    if (draft.addEfi) {
                        storage("EFI storage", draft.efi.storage) { edit(CreateFields.EFI, draft.copy(efi = draft.efi.copy(storage = it.id, format = null, volume = null))) }
                        choice(CreateFields.EFI, "EFI format", context.storages.firstOrNull { it.id == draft.efi.storage }?.formats?.supported.orEmpty().map { CreateChoice(it,it) }, draft.efi.format) { draft.copy(efi = draft.efi.copy(format = it)) }
                        TechCheck(draft.efi.preEnrolledKeys, { edit(CreateFields.EFI, draft.copy(efi = draft.efi.copy(preEnrolledKeys = it))) }, "Pre-enrolled keys")
                    }
                }
                choice(CreateFields.SCSI_HW, "SCSI controller", if (draft.arch == "aarch64") SCSI_CONTROLLER_CHOICES.filter { it.value?.startsWith("virtio") == true } else SCSI_CONTROLLER_CHOICES, draft.scsiHw) { draft.copy(scsiHw = it) }
                TechCheck(draft.agent, { edit(CreateFields.AGENT, draft.copy(agent = it)) }, "QEMU guest agent")
                TechCheck(draft.tpm != null, { edit(CreateFields.TPM, draft.copy(tpm = if (it) TpmState() else null)) }, "Add TPM")
                draft.tpm?.let { tpm ->
                    storage("TPM storage", tpm.storage) { edit(CreateFields.TPM, draft.copy(tpm = tpm.copy(storage = it.id, format = null, volume = null))) }
                    choice(CreateFields.TPM, "TPM format", context.storages.firstOrNull { it.id == tpm.storage }?.formats?.supported.orEmpty().filter { context.release >= 9 || it == "raw" }.map { CreateChoice(it,it) }, tpm.format) { draft.copy(tpm = tpm.copy(format = it)) }
                    choice(CreateFields.TPM, "TPM version", if (draft.arch == "aarch64") TPM_VERSION_CHOICES.take(1) else TPM_VERSION_CHOICES, tpm.version) { draft.copy(tpm = tpm.copy(version = it ?: "v2.0")) }
                }
            }
            "disks" -> {
                if (draft.disks.isEmpty()) Text("No disks. The VM will boot from the install media only.", color = Tech.Muted)
                draft.disks.forEachIndexed { i, disk ->
                    fun update(value: VmDisk) { edit(CreateFields.disk(disk.key), draft.copy(disks = draft.disks.toMutableList().apply { set(i, value) })) }
                    SectionLabel(disk.key)
                    MiniAction("Remove ${disk.key}") { edit(CreateFields.DISKS, draft.copy(disks = draft.disks.filterIndexed { n,_ -> n != i })) }
                    TechSelect("Bus", DiskBus.entries.filter { draft.arch != "aarch64" || it != DiskBus.Ide }, disk.bus, { update(busDefaults(disk,it,draft.scsiHw)) }, { it.prefix })
                    TechNumberField(disk.index.toString(), { it.toIntOrNull()?.let { n -> update(disk.copy(index = n)) } }, "Index", error = errors[CreateFields.disk(disk.key,"index")])
                    when(val source = disk.source) {
                        is DiskSource.New -> {
                            storage("Storage", source.storage) { choice ->
                                val applied = storageDefaults(disk, choice)
                                update(if (choice.selectExisting) applied.copy(source = DiskSource.Existing("${choice.id}:")) else applied)
                            }
                            TechNumberField(source.sizeGib, { update(disk.copy(source = source.copy(sizeGib = it))) }, "Size", unit = "GiB", error = errors[CreateFields.disk(disk.key,"size")])

                        }
                        is DiskSource.Existing -> ExistingCreateVolumePicker(session, context, "images", source.volid, onRead, { update(disk.copy(source = source.copy(volid = it))) })
                        is DiskSource.Import -> {
                            storage("Source storage", source.fromVolid.substringBefore(':'), "import") { update(disk.copy(source = source.copy(fromVolid = "${it.id}:"))); onRead(CreateRead.Volumes(it.id,"import")) }
                            val rows = session.volumes[source.fromVolid.substringBefore(':') to "import"]?.value.orEmpty()
                            TechSelect("Import image", rows, rows.firstOrNull { it.volid == source.fromVolid }, { update(disk.copy(source = source.copy(fromVolid = it.volid))) }, { it.volid }, emptyText = "No disk images to import on this node.")
                            storage("Target storage", source.targetStorage) { choice -> update(applyImportStorage(disk, choice)) }
                        }
                    }
                    val target = when(val s = disk.source) { is DiskSource.New -> s.storage; is DiskSource.Import -> s.targetStorage; is DiskSource.Existing -> s.volid.substringBefore(':') }
                    val formats = context.storages.firstOrNull { it.id == target }?.formats?.supported.orEmpty()
                    if (formats.size > 1) TechSelect("Format", formats, disk.format, { update(disk.copy(format = it)) })
                    TechSelect("Cache", CACHE_CHOICES, CACHE_CHOICES.firstOrNull { it.value == disk.cache }, { update(disk.copy(cache = it.value)) }, { it.label })
                    TechCheck(disk.discard, { update(disk.copy(discard = it)) }, "Discard")
                    TechCheck(disk.ioThread, { update(disk.copy(ioThread = it)) }, "IO thread", enabled = disk.bus in listOf(DiskBus.Scsi, DiskBus.Virtio))
                    if (advanced) {
                        TechCheck(disk.ssd, { update(disk.copy(ssd = it)) }, "SSD emulation", enabled = disk.bus != DiskBus.Virtio)
                        TechCheck(disk.readOnly, { update(disk.copy(readOnly = it)) }, "Read-only", enabled = disk.bus in listOf(DiskBus.Scsi, DiskBus.Virtio))
                        TechCheck(disk.backup, { update(disk.copy(backup = it)) }, "Backup")
                        TechCheck(disk.skipReplication, { update(disk.copy(skipReplication = it)) }, "Skip replication")
                        TechSelect("Async IO", AIO_CHOICES, AIO_CHOICES.firstOrNull { it.value == disk.aio }, { update(disk.copy(aio = it.value)) }, { it.label })
                    }
                    var limits by remember(disk.key) { mutableStateOf(false) }
                    MiniAction(if (limits) "Hide limits" else "Limits") { limits = !limits }
                    if (limits) {
                        val l = disk.limits
                        listOf("Read MB/s" to l.mbpsRd, "Write MB/s" to l.mbpsWr, "Read IOPS" to l.iopsRd, "Write IOPS" to l.iopsWr, "Read burst MB/s" to l.mbpsRdMax, "Write burst MB/s" to l.mbpsWrMax, "Read burst IOPS" to l.iopsRdMax, "Write burst IOPS" to l.iopsWrMax).forEachIndexed { n,(label,value) ->
                            TechNumberField(value.orEmpty(), { value -> update(disk.copy(limits = when(n) { 0 -> l.copy(mbpsRd=value); 1 -> l.copy(mbpsWr=value); 2 -> l.copy(iopsRd=value); 3 -> l.copy(iopsWr=value); 4 -> l.copy(mbpsRdMax=value); 5 -> l.copy(mbpsWrMax=value); 6 -> l.copy(iopsRdMax=value); else -> l.copy(iopsWrMax=value) })) }, label)
                        }
                    }
                    errors.filterKeys { it.startsWith("disks.${disk.key}") }.values.distinct().forEach { Text(it, Modifier.identity(), color = Tech.Red) }
                }
                MiniAction("Add disk") {
                    val n = (0..30).firstOrNull { index -> draft.disks.none { it.bus == DiskBus.Scsi && it.index == index } && draft.install.slot != "scsi$index" } ?: 0
                    edit(CreateFields.DISKS, draft.copy(disks = draft.disks + VmDisk(index = n)))
                }
                if (context.release >= 9 && context.storages.any { "import" in it.content }) MiniAction("Import disk") {
                    val n = (0..30).firstOrNull { index -> draft.disks.none { it.bus == DiskBus.Scsi && it.index == index } && draft.install.slot != "scsi$index" } ?: 0
                    edit(CreateFields.DISKS, draft.copy(disks = draft.disks + VmDisk(index = n, source = DiskSource.Import("", ""))))
                }
            }
            "cpu" -> {
                number(CreateFields.SOCKETS, "Sockets", draft.sockets) { draft.copy(sockets = it) }
                number(CreateFields.CORES, "Cores", draft.cores) { draft.copy(cores = it) }
                val total = (draft.sockets.toLongOrNull() ?: 0) * (draft.cores.toLongOrNull() ?: 0)
                Text("Total: $total cores${context.nodeCpus?.let { if (total > it) " \u00b7 Overcommits $it host CPUs" else "" }.orEmpty()}", color = Tech.Amber)
                ListStateView(session.cpuModels, "Reading CPU models\u2026", "No CPU models available.", { onRead(CreateRead.CpuModels(draft.arch)) }) { rows -> TechSelect("Type", rows, rows.firstOrNull { it.name == draft.cpuType }, { edit(CreateFields.CPU_TYPE, draft.copy(cpuType = it.name)) }, { "${it.name} \u00b7 ${it.vendor.orEmpty()}" }) }
                if (advanced) {
                    number(CreateFields.VCPUS, "vCPUs", draft.vcpus) { draft.copy(vcpus = it) }
                    number(CreateFields.CPU_LIMIT, "CPU limit", draft.cpuLimit) { draft.copy(cpuLimit = it) }
                    Column {
                        TechField(draft.affinity, { edit(CreateFields.AFFINITY, draft.copy(affinity = it)) }, "CPU affinity", enabled = context.identityIsRootPam)
                        if (!context.identityIsRootPam) HelpTip("Only root@pam can set CPU affinity.") { Text("?", color = Tech.Amber) }
                    }
                    number(CreateFields.CPU_UNITS, "CPU units", draft.cpuUnits) { draft.copy(cpuUnits = it) }
                    TechCheck(draft.numa, { edit(CreateFields.NUMA, draft.copy(numa = it)) }, "NUMA")
                    ListStateView(session.cpuFlags, "Reading CPU flags\u2026", "No CPU flags available.", { onRead(CreateRead.CpuFlags(draft.arch, if (draft.arch == null) "kvm" else "tcg")) }) { rows -> rows.forEach { flag ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        HelpTip(flag.description ?: flag.name) { Text(flag.name, color = Tech.Muted) }
                        val value = draft.cpuFlags.firstOrNull { it.name == flag.name }?.state
                        TechChoice(listOf("Off", "Default", "On"), when(value) { '+' -> "On"; '-' -> "Off"; else -> "Default" }, { selected -> edit(CreateFields.CPU_FLAGS, draft.copy(cpuFlags = draft.cpuFlags.filter { it.name != flag.name } + if (selected == "Default") emptyList() else listOf(CpuFlag(flag.name, if (selected == "On") '+' else '-')))) })
                        }
                    } }
                }
            }
            "memory" -> {
                number(CreateFields.MEMORY_MIB, "Memory", draft.memoryMib, "MiB") { draft.copy(memoryMib = it) }
                context.nodeMemoryBytes?.let { Text("Host memory: ${it / 1048576} MiB \u00b7 used: ${(context.nodeMemoryUsedBytes ?: 0) / 1048576} MiB", color = Tech.Muted) }
                if (advanced) {
                    TechCheck(draft.ballooning, { edit(CreateFields.BALLOONING, draft.copy(ballooning = it)) }, "Ballooning")
                    if (draft.ballooning) {
                        number(CreateFields.BALLOON, "Minimum memory", draft.minMemoryMib, "MiB") { draft.copy(minMemoryMib = it) }
                        number(CreateFields.SHARES, "Shares", draft.shares) { draft.copy(shares = it) }
                    }
                    if (context.release >= 9) TechCheck(draft.allowKsm, { edit(CreateFields.KSM, draft.copy(allowKsm = it)) }, "Allow KSM")
                }
            }
            "network" -> {
                TechCheck(draft.nic == null, { edit(CreateFields.NIC, draft.copy(nic = if (it) null else VmNic())) }, "No network device")
                draft.nic?.let { nic ->
                    ListStateView(session.bridges, "Reading bridges\u2026", "No bridges available. This account may need SDN.Use.", { onRead(CreateRead.Bridges) }) { rows -> TechSelect("Bridge", rows, rows.firstOrNull { it.name == nic.bridge }, { edit(CreateFields.BRIDGE, draft.copy(nic = nic.copy(bridge = it.name))) }, { "${it.name}${if (it.vlanAware) " \u00b7 VLAN-aware" else ""}" }) }
                    number(CreateFields.VLAN, "VLAN tag", nic.vlan) { draft.copy(nic = nic.copy(vlan = it)) }
                    choice(CreateFields.MODEL, "Model", NIC_MODEL_CHOICES, nic.model) { draft.copy(nic = nic.copy(model = it ?: "virtio")) }
                    field(CreateFields.MAC, "MAC address (blank = automatic)", nic.mac) { draft.copy(nic = nic.copy(mac = it)) }
                    TechCheck(nic.firewall, { edit(CreateFields.NIC, draft.copy(nic = nic.copy(firewall = it))) }, "Firewall")
                    if (advanced) {
                        TechCheck(nic.disconnect, { edit(CreateFields.NIC, draft.copy(nic = nic.copy(disconnect = it))) }, "Disconnect")
                        number(CreateFields.MTU, "MTU", nic.mtu) { draft.copy(nic = nic.copy(mtu = it)) }
                        number(CreateFields.RATE, "Rate limit", nic.rate, "MB/s") { draft.copy(nic = nic.copy(rate = it)) }
                        number(CreateFields.QUEUES, "Multiqueue", nic.queues) { draft.copy(nic = nic.copy(queues = it)) }
                    }
                }
            }
        }
        }
        errors.filterKeys { CreateFields.step(it) == current }.values.distinct().forEach { Text(it, Modifier.identity(), color = Tech.Red, fontSize = 11.sp) }
        TechCheck(start, { start = it; dirty = true }, "Start after it is created")
    }
}


/** Reading order is row-major at both breakpoints; the scroll owner stays in the shared frame. */
@Composable private fun VmFieldGrid(columns: Int, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content = content) { children, constraints ->
        val gap = 14.dp.roundToPx()
        val count = columns.coerceIn(1,2)
        val width = ((constraints.maxWidth - gap * (count - 1)) / count).coerceAtLeast(0)
        val measured = children.map { it.measure(androidx.compose.ui.unit.Constraints(maxWidth = width)) }
        val rows = measured.chunked(count)
        val height = rows.sumOf { row -> row.maxOfOrNull { it.height } ?: 0 } + gap * (rows.size - 1).coerceAtLeast(0)
        layout(constraints.maxWidth, height) {
            var y = 0
            rows.forEach { row ->
                row.forEachIndexed { index, item -> item.placeRelative(index * (width + gap), y) }
                y += (row.maxOfOrNull { it.height } ?: 0) + gap
            }
        }
    }
}

/** Node-scoped defaults and explicit selections must be chosen again, even on shared storage. */
internal fun invalidateVmNode(d: VmDraft, node: String): VmDraft = d.copy(
    node = node, isoStorage = "", install = d.install.copy(media = if (d.install.media is CdMedia.Iso) CdMedia.Iso() else d.install.media),
    driverDisc = d.driverDisc?.copy(media = CdMedia.Iso()),
    efi = d.efi.copy(storage = "", volume = null, format = null),
    tpm = d.tpm?.copy(storage = "", volume = null, format = null),
    disks = d.disks.map { disk -> disk.copy(format = null, source = when(val source = disk.source) {
        is DiskSource.New -> source.copy(storage = "")
        is DiskSource.Existing -> DiskSource.Existing("")
        is DiskSource.Import -> DiskSource.Import("", "")
    }) }, cpuType = "", cpuFlags = emptyList(), nic = d.nic?.copy(bridge = "")
)

/** Fail closed before capture too: injected encoders cannot accidentally freeze stale list choices. */
internal fun vmSelectionErrors(d: VmDraft, c: CreateContext, s: CreateSession): Map<FieldId, String> = buildMap {
    fun storage(id: String, content: String, field: FieldId) {
        if (c.storages.none { it.node == d.node && it.id == id && content in it.content }) put(field, "Choose a recorded storage on this node.")
    }
    fun media(drive: CdDrive, field: FieldId) {
        val iso = drive.media as? CdMedia.Iso ?: return
        storage(iso.storage, "iso", field)
        if (s.volumes[iso.storage to "iso"]?.value.orEmpty().none { it.volid == iso.volid }) put(field, "Choose an ISO image on this node.")
    }
    media(d.install, CreateFields.INSTALL)
    d.driverDisc?.let { media(it, CreateFields.DRIVER_DISC) }
    if (d.bios == "ovmf" && d.addEfi) storage(d.efi.storage, "images", CreateFields.EFI)
    d.tpm?.let { storage(it.storage, "images", CreateFields.TPM) }
    d.disks.forEach { disk ->
        val target = when(val source = disk.source) {
            is DiskSource.New -> source.storage
            is DiskSource.Existing -> source.volid.substringBefore(':')
            is DiskSource.Import -> {
                val origin = source.fromVolid.substringBefore(':')
                storage(origin, "import", CreateFields.disk(disk.key))
                if (s.volumes[origin to "import"]?.value.orEmpty().none { it.volid == source.fromVolid }) put(CreateFields.disk(disk.key), "Choose an import image on this node.")
                source.targetStorage
            }
        }
        storage(target, "images", CreateFields.disk(disk.key))
        if (disk.format != null && c.storages.firstOrNull { it.node == d.node && it.id == target }?.formats?.supported?.contains(disk.format) != true)
            put(CreateFields.disk(disk.key), "Choose a format supported by this storage.")
    }
    if (s.cpuModels.value.orEmpty().none { it.name == d.cpuType }) put(CreateFields.CPU_TYPE, "Choose a CPU model on this node.")
    if (d.cpuFlags.any { flag -> s.cpuFlags.value.orEmpty().none { it.name == flag.name } }) put(CreateFields.CPU_FLAGS, "Choose CPU flags supported by this node.")
    d.nic?.let { nic -> if (s.bridges.value.orEmpty().none { it.name == nic.bridge }) put(CreateFields.BRIDGE, "Choose a bridge on this node.") }
}
