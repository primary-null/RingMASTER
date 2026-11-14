package local.ringleader.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.time.Instant
import java.util.UUID

/*
 * The create flow around the pure model in GuestCreate.kt: the secrets holders, the one-use confirmation, the refusal
 * and availability rules, the gateway, the wizard's session state, the result of a create (with the maintainer's
 * "finished with warnings" outcome, D2), and the demo session.
 *
 * Specification: C0-CREATE-GUEST-DESIGN.md sections 4, 6.6 to 6.10, 7 and 8, with C1-CREATE-GUEST-BUILD.md D1 to D3.
 */

// ---------------------------------------------------------------------------------------------------------------------
// Secrets (design section 7)
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Secret characters (the container root password or the SSH key text) held in a CharArray from capture until the
 * request body is written. Not a data class: no generated toString, copy or equals. [close] fills the array with
 * `'\u0000'`; every later read throws. Never turn the characters into a String.
 */
class SecretChars private constructor(private var chars: CharArray?) : AutoCloseable {
    /** The number of characters, 0 after [close]. */
    val length: Int get() = synchronized(this) { chars?.size ?: 0 }

    val isEmpty: Boolean get() = length == 0

    val closed: Boolean get() = synchronized(this) { chars == null }

    /** Run [block] on the live characters. The block must not keep or copy the array. Throws IllegalStateException after [close]. */
    fun <T> use(block: (CharArray) -> T): T = synchronized(this) {
        block(chars ?: throw IllegalStateException("These characters were already wiped."))
    }

    /** Equal to [other], compared over the full length without stopping at the first difference. False after [close]. */
    fun matches(other: CharSequence): Boolean = synchronized(this) {
        val mine = chars ?: return false
        var difference = mine.size xor other.length
        for (i in 0 until maxOf(mine.size, other.length)) {
            val a = if (i < mine.size) mine[i].code else 0
            val b = if (i < other.length) other[i].code else 0
            difference = difference or (a xor b)
        }
        difference == 0
    }

    /** The number of non-blank lines (one SSH key per line). 0 after [close]. */
    fun nonBlankLines(): Int = synchronized(this) {
        val mine = chars ?: return 0
        var count = 0
        var lineHasText = false
        for (c in mine) {
            if (c == '\n' || c == '\r') {
                if (lineHasText) count++
                lineHasText = false
            } else if (!c.isWhitespace()) lineHasText = true
        }
        if (lineHasText) count++
        count
    }

    override fun close() {
        synchronized(this) {
            chars?.fill('\u0000')
            chars = null
        }
    }

    override fun toString(): String = "SecretChars(redacted)"

    companion object {
        /** Copy [text] character by character (never through `toString()`). The caller clears [text] itself when it should. */
        fun copyOf(text: CharSequence): SecretChars { require(text.length <= CREATE_SSH_KEYS_MAX_CHARS) { "Secret input is too large." }; return SecretChars(CharArray(text.length) { text[it] }) }
    }
}

/** Whether the secrets are set, for the confirmation and the Parameters panel. Never a value, a length or a hash. */
data class SecretState(val password: Boolean = false, val keyCount: Int = 0) {
    init { require(keyCount >= 0) { "A key count is never negative." } }
}

/**
 * The secrets of one create: the container root password and the SSH public keys (one per line). Both null for a VM.
 * They travel beside the [CreateConfirmation], never inside it. [toString] is redacted.
 */
data class CreateSecrets(val password: SecretChars? = null, val sshKeys: SecretChars? = null) : AutoCloseable {
    val state: SecretState get() = SecretState(
        password = password?.let { !it.closed && !it.isEmpty } == true,
        keyCount = sshKeys?.nonBlankLines() ?: 0,
    )

    /** Wipe both. */
    override fun close() {
        password?.close()
        sshKeys?.close()
    }

    override fun toString(): String = "CreateSecrets(redacted)"

    companion object {
        val None = CreateSecrets()
    }
}

/**
 * [text] with every occurrence of the password and of each SSH key line replaced by `•••`, for server text shown to the
 * user. Pure; reads the secrets without copying them into a String.
 */
fun redactSecrets(text: String, secrets: CreateSecrets): String = run {
    var result = text
    for (secret in listOfNotNull(secrets.password, secrets.sshKeys)) if (!secret.closed) secret.use { chars ->
        val ranges = if (secret === secrets.password) listOf(0 until chars.size) else buildList { var start = 0; for (i in chars.indices) if (chars[i] == '\n') { add(start until i); start = i + 1 }; add(start until chars.size) }
        for (range in ranges.filter { !it.isEmpty() }) {
            val out = StringBuilder(); var i = 0
            while (i < result.length) { if (i + range.count() <= result.length && range.all { result[i + it - range.first] == chars[it] }) { out.append("[redacted]"); i += range.count() } else out.append(result[i++]) }
            result = out.toString()
        }
    }
    result.filter { !it.isISOControl() || it == '\n' }.take(4096)
}

// ---------------------------------------------------------------------------------------------------------------------
// 6.6 Confirmation and refusal
// ---------------------------------------------------------------------------------------------------------------------

/**
 * The one-use confirmation captured when the user asks to create. Not a data class and it holds no secret, so it can be
 * shown, kept in state or used as a test value. [start] is the request's "Start after it is created".
 * [epoch] is the connection session; [remote] and [origin] the server (`DEMO_REMOTE_KEY` and "Demonstration" in demo).
 */
class CreateConfirmation(
    val epoch: Long,
    val remote: String,
    val origin: String,
    val profileLabel: String,
    val request: CreateRequest,
    val secretsSet: SecretState,
    val confirmationId: String = UUID.randomUUID().toString(),
) {
    val start: Boolean get() = request.start

    init {
        require(profileLabel.isNotBlank() && profileLabel.none { it.isISOControl() }) { "The confirmation is missing its connection." }
        require(origin.isNotBlank() && origin.none { it.isISOControl() }) { "The confirmation is missing its server." }
        require(remote.isNotBlank()) { "The confirmation is missing its server." }
    }

    override fun toString(): String = "CreateConfirmation(${request.kind.guestKind} ${request.vmid} on ${request.node})"
}

/**
 * Why this confirmation must not be sent now, or null. Every text ends "Nothing was sent.". Reasons (design 4.1 step 1):
 * the connection epoch or origin changed, a PDM connection, a demo mismatch, the inventory is loading or stale, an
 * unresolved submission exists, another send is in flight, the node is no longer online in the overview, the ID now
 * belongs to a guest in the overview, or a container has neither a password nor a key.
 * (The one-use check lives in the workspace, as for power actions.)
 */
fun createRefusal(confirmation: CreateConfirmation, state: WorkspaceState): String? = run {
    val reason = when {
        confirmation.epoch != state.connectionEpoch -> "The connection changed."
        confirmation.remote != (if (state.demo) DEMO_REMOTE_KEY else state.address) || confirmation.origin != (if (state.demo) "Demonstration" else state.address) -> "The connection changed."
        !createAvailability(state, confirmation.request.kind).available -> createAvailability(state, confirmation.request.kind).reason
        state.overview?.members?.none { it.name == confirmation.request.node && it.status == "online" } != false -> "That node is not online."
        state.overview.guests.any { it.id == confirmation.request.vmid } -> "That ID is already in the inventory."
        confirmation.request.kind == NewGuestKind.Container && !confirmation.secretsSet.password && confirmation.secretsSet.keyCount == 0 -> "Set a password or SSH public key."
        else -> null
    }
    reason?.let { "$it Nothing was sent." }
}

// ---------------------------------------------------------------------------------------------------------------------
// 6.7 Availability and privilege hints
// ---------------------------------------------------------------------------------------------------------------------

/** Whether "New VM" / "New container" is enabled, and the plain reason when it is not (design 2.2). */
data class CreateAvailability(val available: Boolean, val reason: String? = null) {
    init { require(available == (reason == null)) { "An unavailable control says why; an available one does not." } }
}

/**
 * The one rule the Fleet header, the node inspector and the keyboard shortcut share (design 2.2): not connected, PDM,
 * inventory loading or stale, an unresolved mutation, no `VM.Allocate` on `/vms` or any listed pool, no online node.
 * Unread permissions leave the controls enabled.
 */
fun createAvailability(state: WorkspaceState, kind: NewGuestKind): CreateAvailability = run {
    val reason = when {
        !state.demo && !state.connected -> "Connect to a Proxmox VE server first."
        state.kind == PDM_KIND -> "Creating guests needs a direct Proxmox VE connection."
        state.loading || state.overview == null -> "Wait for the inventory to load."
        state.stale -> "The inventory is stale. Refresh first."
        state.powerBusy -> "Another action is being sent."
        unresolvedSubmission(state) != null -> unresolvedActionText(unresolvedSubmission(state)!!)
        createStartNode(state) == null -> "No node is online."
        state.permissions.value?.dump?.let { dump -> "VM.Allocate" !in effectivePrivileges(dump, "/vms").privileges && dump.paths.keys.filter { it.startsWith("/pool/") }.none { "VM.Allocate" in effectivePrivileges(dump, it).privileges } } == true -> "This token cannot create guests. It needs VM.Allocate on /vms or on a pool."
        else -> null
    }
    CreateAvailability(reason == null, reason)
}

/** The node a new wizard starts on (design 2.1): the selected node, else the selected guest's node, else the first online node. Null when no node is online. */
fun createStartNode(state: WorkspaceState): String? = state.overview?.members?.filter { it.status == "online" }?.let { nodes -> listOfNotNull(state.selectedNode, state.selected?.node).firstOrNull { n -> nodes.any { it.name == n } } ?: nodes.firstOrNull()?.name }

/**
 * Plain warnings computed from the permission dump for this request (design 6.7, R§8), such as
 * "May lack VM.Config.Disk on /vms/105." or "This token may lack Datastore.AllocateSpace on local-lvm. The server will
 * refuse the disk.". They never block: the server decides.
 */
fun createPrivilegeHints(dump: PermissionDump, request: CreateRequest, identity: PermissionIdentity): List<String> = buildList {
    val vm = "/vms/${request.vmid}"
    fun need(path: String, privilege: String) { if (privilege !in effectivePrivileges(dump, path).privileges) add("May lack $privilege on $path.") }
    val pool = request.params.firstOrNull { it.key == "pool" }?.value?.let { createValueText(it) }
    if (pool == null || "VM.Allocate" !in effectivePrivileges(dump, "/pool/$pool").privileges) need(vm, "VM.Allocate")
    listOf("Disk", "CPU", "Memory", "Network", "Options").forEach { need(vm, "VM.Config.$it") }
    if (request.kind == NewGuestKind.Vm) need(vm, "VM.Config.HWType")
    if (request.start) need(vm, "VM.PowerMgmt")
    if (request.params.any { it.key == "startup" || (it.key == "unprivileged" && it.value == CreateValue.Int(0)) }) need("/", "Sys.Modify")
    if (request.params.any { it.key == "ha-managed" }) need("/", "Sys.Console")
    request.params.firstOrNull { it.key == "cpu" }?.let { cpu -> val model = createValueText(cpu.value).substringBefore(','); if (model.startsWith("custom-")) need("/mapping/cpu/${model.removePrefix("custom-")}", "Mapping.Use") }
    if (request.params.any { it.key.startsWith("ide") && createValueText(it.value).startsWith("cdrom,") }) need("/", "Sys.Console")
    val diskKeys = Regex("(?:scsi|ide|sata|virtio|mp)[0-9]+|rootfs|efidisk0|tpmstate0")
    request.params.filter { diskKeys.matches(it.key) }.map { createValueText(it.value).substringBefore(':') }.distinct().forEach { need("/storage/$it", "Datastore.AllocateSpace") }
    request.params.firstOrNull { it.key == "net0" }?.let { p -> val props = createValueText(p.value).split(',').associate { it.substringBefore('=') to it.substringAfter('=', "") }; props["bridge"]?.let { need("/sdn/zones/localnetwork/$it" + props["tag"]?.let { tag -> "/$tag" }.orEmpty(), "SDN.Use") } }
}

// ---------------------------------------------------------------------------------------------------------------------
// The gateway (6.9). Every read is a GET; [submit] is the one POST. Nothing here retries.
// ---------------------------------------------------------------------------------------------------------------------

/** The reply to the create POST. HTTP 400 and 403 are [Rejected]: nothing was created. */
sealed interface CreateReply {
    /** HTTP 200 with a UPID whose node is the target and whose subject is the new ID. Accepted is not success. */
    data class Accepted(val upid: String) : CreateReply
    /** [fieldErrors] keys are Proxmox parameter names (`vmid`, `scsi0`, `storage`, …), values the cleaned messages. */
    data class Rejected(val status: Int, val message: String, val fieldErrors: Map<String, String> = emptyMap()) : CreateReply
}

/**
 * What the workspace needs from the server to create a guest. Functions throw as the existing gateways do:
 * [SessionExpiredException] on HTTP 401, [GuestPermissionException] when a read is refused (403), and for [submit]
 * [AmbiguousPowerResultException] (no complete answer after the body was written, or 3xx/5xx after it) and
 * [PowerResponseUnclearException] (HTTP 200 without a matching UPID). A failure before the body was written is an
 * IOException: nothing was sent.
 *
 * There is no storage-index read (D1): storage choices come from the inventory; [volumes] is the existing
 * single-storage content read.
 */
interface CreateGuestGateway {
    /** `GET /cluster/nextid` ([vmid] null) or `GET /cluster/nextid?vmid=N` (a taken ID is [NextIdReply.Taken], not an exception). */
    suspend fun nextId(connection: Connection, vmid: Int?): NextIdReply
    suspend fun pools(connection: Connection): List<PoolChoice>
    suspend fun bridges(connection: Connection, node: String): List<BridgeChoice>
    suspend fun cpuModels(connection: Connection, node: String, arch: String?): List<CpuModelChoice>
    suspend fun cpuFlags(connection: Connection, node: String, arch: String?, accel: String): List<CpuFlagChoice>
    suspend fun clusterOptions(connection: Connection): ClusterTagRules
    /** One storage's ISO images, templates or import images (`content` = `iso`, `vztmpl` or `import`). */
    suspend fun volumes(connection: Connection, node: String, storage: String, content: String): List<StorageVolume>
    /** The one create POST. Sent once; never retried. The caller wipes [secrets] afterwards. */
    suspend fun submit(connection: Connection, request: CreateRequest, secrets: CreateSecrets): CreateReply
    /** The accepted task's status (as [PowerGateway.status]). */
    suspend fun status(connection: Connection, node: String, upid: String): PowerTaskStatus
    /** One page of the task log, for the warning lines and the failure tail. */
    suspend fun taskLog(connection: Connection, node: String, upid: String, start: Int, limit: Int): TaskLogPage
}

/** The live gateway over [PveClient], on Dispatchers.IO like [LivePowerGateway]. Phase A writes its bodies. */
class LiveCreateGuestGateway(@Suppress("unused") private val client: PveClient = PveClient()) : CreateGuestGateway {
    override suspend fun nextId(connection: Connection, vmid: Int?): NextIdReply = runInterruptible(Dispatchers.IO) { client.nextId(connection, vmid) }
    override suspend fun pools(connection: Connection): List<PoolChoice> = runInterruptible(Dispatchers.IO) { client.pools(connection) }
    override suspend fun bridges(connection: Connection, node: String): List<BridgeChoice> = runInterruptible(Dispatchers.IO) { client.bridges(connection, node) }
    override suspend fun cpuModels(connection: Connection, node: String, arch: String?): List<CpuModelChoice> = runInterruptible(Dispatchers.IO) { client.cpuModels(connection, node, arch) }
    override suspend fun cpuFlags(connection: Connection, node: String, arch: String?, accel: String): List<CpuFlagChoice> = runInterruptible(Dispatchers.IO) { client.cpuFlags(connection, node, arch, accel) }
    override suspend fun clusterOptions(connection: Connection): ClusterTagRules = runInterruptible(Dispatchers.IO) { client.clusterOptions(connection) }
    override suspend fun volumes(connection: Connection, node: String, storage: String, content: String): List<StorageVolume> = runInterruptible(Dispatchers.IO) { client.storageContent(connection, node, storage, content) }
    override suspend fun submit(connection: Connection, request: CreateRequest, secrets: CreateSecrets): CreateReply = runInterruptible(Dispatchers.IO) { client.createGuest(connection, request, secrets) }
    override suspend fun status(connection: Connection, node: String, upid: String): PowerTaskStatus = runInterruptible(Dispatchers.IO) { client.powerStatus(connection, node, upid) }
    override suspend fun taskLog(connection: Connection, node: String, upid: String, start: Int, limit: Int): TaskLogPage = runInterruptible(Dispatchers.IO) { client.readTaskLog(connection, node, upid, start, limit) }
}

/** One read the wizard asks the workspace for ([Workspace.readForCreate]): a first load, a Retry, or an on-demand list. */
sealed interface CreateRead {
    data object NextId : CreateRead
    /** The typed-ID check, sent 400 ms after the ID field stops changing. */
    data class CheckId(val vmid: Int) : CreateRead
    data object Pools : CreateRead
    data object Bridges : CreateRead
    data class CpuModels(val arch: String?) : CreateRead
    data class CpuFlags(val arch: String?, val accel: String) : CreateRead
    data object TagRules : CreateRead
    /** One storage's content of type [content] (`iso`, `vztmpl` or `import`). */
    data class Volumes(val storage: String, val content: String) : CreateRead
}

// ---------------------------------------------------------------------------------------------------------------------
// Outcomes (design 4, 5; maintainer decision D2)
// ---------------------------------------------------------------------------------------------------------------------

/** Whether the new guest is in the inventory yet, after the task ended (the resource list lags 2 to 10 s). */
enum class GuestAppearance {
    /** Still within the catch-up window ([CREATE_APPEAR_DEADLINE_MS]). */
    Waiting,
    /** The overview contains the new guest. */
    Appeared,
    /** Not in the overview after the catch-up window. */
    NotYet,
}

/**
 * What a terminal task status means for a create. [FinishedWithWarnings] is D2's third outcome: used only for create,
 * shown amber, never the green success state, never counted as `OK` anywhere else, and it never blocks the session.
 */
sealed interface CreateTaskOutcome {
    data object Running : CreateTaskOutcome
    /** Exit status `OK`. */
    data object Succeeded : CreateTaskOutcome
    /** Exit status `WARNINGS: N`. */
    data class FinishedWithWarnings(val count: Int) : CreateTaskOutcome
    /** Any other exit status text. */
    data class Failed(val text: String) : CreateTaskOutcome
    /** Stopped without an exit status, or unreadable. */
    data class Unknown(val text: String) : CreateTaskOutcome
}

/**
 * Classify a task status of a create: [PowerPhase.Succeeded] → Succeeded; [PowerPhase.Unsuccessful] with detail
 * `WARNINGS: N` (N ≥ 1) → FinishedWithWarnings(N); other Unsuccessful → Failed; Accepted → Running; Unknown → Unknown.
 */
fun createTaskOutcome(status: PowerTaskStatus): CreateTaskOutcome = when (status.phase) {
    PowerPhase.Succeeded -> CreateTaskOutcome.Succeeded
    PowerPhase.Accepted -> CreateTaskOutcome.Running
    PowerPhase.Unsuccessful -> Regex("WARNINGS: ([1-9][0-9]*)").matchEntire(status.detail)?.groupValues?.get(1)?.toIntOrNull()?.let { CreateTaskOutcome.FinishedWithWarnings(it) } ?: CreateTaskOutcome.Failed(status.detail)
    else -> CreateTaskOutcome.Unknown(status.detail)
}

/** True for a create submission whose task ended `WARNINGS: N`, so Activity can show it amber rather than red. */
fun createFinishedWithWarnings(submission: PowerSubmission): Boolean = isCreateSubmission(submission) && createTaskOutcome(PowerTaskStatus(submission.phase, submission.detail)) is CreateTaskOutcome.FinishedWithWarnings

/** True when [submission] records a guest creation, so screens need not name [PowerAction.Create] themselves. */
fun isCreateSubmission(submission: PowerSubmission): Boolean = submission.action == PowerAction.Create

/**
 * The warning lines of a create task log: lines starting with `WARN`, or else the last lines before `TASK WARNINGS: N`.
 * Cleaned of control characters, at most 300 characters each.
 */
fun createWarningLines(lines: List<TaskLogLine>): List<String> = lines.map { it.text.filter { c -> !c.isISOControl() }.take(300) }.let { text -> text.filter { it.startsWith("WARN") }.ifEmpty { text.filterNot { it.startsWith("TASK ") }.takeLast(10) } }

/** The wizard's result view, kept in [CreateSession.result]. */
sealed interface CreateResult {
    val kind: NewGuestKind
    val node: String
    val vmid: Int

    /** The POST is in flight. */
    data class Sending(override val kind: NewGuestKind, override val node: String, override val vmid: Int) : CreateResult

    /** Accepted; the task is being followed ("Creating VM 105 on pve-a…"). [localId] is the submission in Activity. */
    data class Following(
        override val kind: NewGuestKind, override val node: String, override val vmid: Int, val localId: String,
    ) : CreateResult

    /**
     * Nothing was created: HTTP 400 or 403, or ([status] null) the request could not be sent at all ("Could not reach the
     * server. Nothing was sent."). The form comes back with what was typed, secrets cleared. [fieldErrors] are already
     * mapped to draft fields with [createErrorTarget] and redacted; [serverSaid] holds the keys no field takes.
     */
    data class Rejected(
        override val kind: NewGuestKind, override val node: String, override val vmid: Int,
        val status: Int?, val message: String,
        val fieldErrors: Map<FieldId, String> = emptyMap(),
        val serverSaid: Map<String, String> = emptyMap(),
    ) : CreateResult

    /** Exit status `OK` ("VM 105 was created." and "and started" when [started]). */
    data class Succeeded(
        override val kind: NewGuestKind, override val node: String, override val vmid: Int, val localId: String,
        val started: Boolean, val appearance: GuestAppearance = GuestAppearance.Waiting,
    ) : CreateResult

    /**
     * D2: exit status `WARNINGS: N`. "Finished with 2 warnings." in amber, with [warnings] (the task-log warning lines)
     * shown and the full log one click away. Then the inventory decides: when the guest appears, "Container 105 now exists
     * on node-a" with an offer to open it; after the catch-up window without it, "The task finished with warnings and the
     * guest is not in the inventory yet. Check the server."
     */
    data class FinishedWithWarnings(
        override val kind: NewGuestKind, override val node: String, override val vmid: Int, val localId: String,
        val count: Int, val warnings: List<String>, val appearance: GuestAppearance = GuestAppearance.Waiting,
    ) : CreateResult {
        init { require(count >= 1) { "Finished with warnings has at least one warning." } }
    }

    /**
     * Any other exit status: "Creating VM 105 failed: <text>." with the last task-log lines. A failure can still leave a
     * guest (a container whose start failed): [appearance] Appeared then offers to open it instead of the form.
     */
    data class Failed(
        override val kind: NewGuestKind, override val node: String, override val vmid: Int, val localId: String,
        val text: String, val logTail: List<String> = emptyList(), val appearance: GuestAppearance = GuestAppearance.Waiting,
    ) : CreateResult

    /**
     * The outcome is not known (lost reply, 3xx/5xx after sending, 200 without a matching UPID, a task that stopped
     * without an exit status): "… It was not sent again. Check the server before trying again." [check] is the
     * "Check ID" read (design 4.5). [localId] is null when no submission was recorded.
     */
    data class Unknown(
        override val kind: NewGuestKind, override val node: String, override val vmid: Int, val localId: String?,
        val text: String, val check: IdCheck = IdCheck.Idle, val checking: Boolean = false,
    ) : CreateResult

    /** Demo: "Simulated. Nothing was sent to a server." */
    data class Simulated(override val kind: NewGuestKind, override val node: String, override val vmid: Int) : CreateResult
}

/** After the task ends, quiet resource-list reads at these offsets until the new guest appears (design 5). */
val CREATE_APPEAR_READS_MS: List<Long> = listOf(3_000L, 6_000L, 10_000L, 15_000L)
const val CREATE_APPEAR_DEADLINE_MS = 15_000L

// ---------------------------------------------------------------------------------------------------------------------
// 6.10 The wizard's session state
// ---------------------------------------------------------------------------------------------------------------------

/**
 * The server reads and the result of one open create wizard, kept as `WorkspaceState.creation`. The draft is not here:
 * it stays in the desktop and changes on every keystroke. A node change bumps [generation] and reads again; late
 * results of an older generation are discarded.
 *
 * D1: there is no storage list in the session. Storage choices are computed from the inventory with [storageChoices];
 * only [volumes] (one storage's content, on demand) is read from the server.
 */
data class CreateSession(
    val kind: NewGuestKind,
    val node: String,
    val epoch: Long,
    val generation: Long = 0,
    val nextId: ReadSection<Int> = ReadSection(),
    val idCheck: IdCheck = IdCheck.Idle,
    val pools: ReadSection<List<PoolChoice>> = ReadSection(),
    /** By (storage, content), for example ("local", "iso"). */
    val volumes: Map<Pair<String, String>, ReadSection<List<StorageVolume>>> = emptyMap(),
    val bridges: ReadSection<List<BridgeChoice>> = ReadSection(),
    val cpuModels: ReadSection<List<CpuModelChoice>> = ReadSection(),
    val cpuFlags: ReadSection<List<CpuFlagChoice>> = ReadSection(),
    val tagRules: ReadSection<ClusterTagRules> = ReadSection(),
    val result: CreateResult? = null,
    /** Demo mode: every list is sample data and nothing is sent. */
    val sample: Boolean = false,
    val identityIsRootPam: Boolean = false,
)

/** The [CreateContext] for the open session: release from the permission read, the node's row, the overview's IDs, the inventory storages. */
fun createContext(state: WorkspaceState, session: CreateSession): CreateContext = CreateContext(release = state.permissions.value?.dump?.release?.substringBefore('.')?.toIntOrNull() ?: 9,
    storages = state.overview?.storage.orEmpty().filter { it.node == session.node && it.status == "available" }.sortedBy { it.id }.map { StorageChoice(it.node, it.id, it.pluginType, it.content.orEmpty().split(',').toSet(), it.shared, it.diskBytes, it.maxDiskBytes, it.status) },
    identityIsRootPam = session.identityIsRootPam, nodeMemoryBytes = state.overview?.members?.firstOrNull { it.name == session.node }?.maxMemoryBytes, nodeMemoryUsedBytes = state.overview?.members?.firstOrNull { it.name == session.node }?.memoryBytes,
    usedIds = state.overview?.guests.orEmpty().map { it.id }.toSet(), nodeCpus = state.overview?.members?.firstOrNull { it.name == session.node }?.maxCpu?.toInt(), idCheck = session.idCheck, tagRules = session.tagRules.value,
    choiceNode = session.node,
    volumes = session.volumes.filterValues { !it.loading && it.error == null }.mapValues { it.value.value.orEmpty() },
    bridges = session.bridges.takeIf { !it.loading && it.error == null }?.value.orEmpty(),
    cpuModels = session.cpuModels.takeIf { !it.loading && it.error == null }?.value.orEmpty(),
    cpuFlags = session.cpuFlags.takeIf { !it.loading && it.error == null }?.value.orEmpty())

// ---------------------------------------------------------------------------------------------------------------------
// Demo (design 8)
// ---------------------------------------------------------------------------------------------------------------------

const val DEMO_CREATE_NEXT_ID = 140
const val DEMO_CREATE_LABEL = "Demo · sample lists"
const val DEMO_CREATE_COMMIT = "Simulate create"
const val DEMO_CREATE_SUBTITLE = "Demo mode. Nothing is sent."
const val DEMO_CREATE_SIMULATED = "Simulated. Nothing was sent to a server."

private fun demoVolume(volid: String, content: String, format: String, sizeBytes: Long) = StorageVolume(
    volid = volid, content = content, format = format, sizeBytes = sizeBytes, approximateSizeBytes = null, usedBytes = null,
    createdAt = null, vmid = null, notes = null, protected = false, verification = null,
)

private const val MIB = 1024L * 1024

/** Sample ISO images, templates and import images on the demo storage `local`, by (storage, content). */
val demoCreateVolumes: Map<Pair<String, String>, List<StorageVolume>> = mapOf(
    ("local" to "iso") to listOf(
        demoVolume("local:iso/debian-13.2.0-amd64-netinst.iso", "iso", "iso", 754 * MIB),
        demoVolume("local:iso/ubuntu-26.04-live-server-amd64.iso", "iso", "iso", 3_100 * MIB),
        demoVolume("local:iso/virtio-win-0.1.285.iso", "iso", "iso", 754 * MIB),
    ),
    ("local" to "vztmpl") to listOf(
        demoVolume("local:vztmpl/debian-13-standard_13.1-1_amd64.tar.zst", "vztmpl", "tzst", 126 * MIB),
        demoVolume("local:vztmpl/alpine-3.22-default_20250617_amd64.tar.xz", "vztmpl", "txz", 3 * MIB),
        demoVolume("local:vztmpl/ubuntu-26.04-standard_26.04-1_arm64.tar.zst", "vztmpl", "tzst", 140 * MIB),
    ),
    ("local" to "import") to listOf(
        demoVolume("local:import/appliance-disk.qcow2", "import", "qcow2", 2_048 * MIB),
    ),
)

val demoCreateBridges: List<BridgeChoice> = listOf(
    BridgeChoice("vmbr0", "bridge", active = true, vlanAware = true, comment = "LAN"),
    BridgeChoice("vmbr1", "bridge", active = true, comment = "Lab"),
    BridgeChoice("lab10", "vnet", active = true, comment = "SDN vnet"),
)

val demoCreateCpuModels: List<CpuModelChoice> = listOf(
    CpuModelChoice("x86-64-v2-AES", "default", abstract = true),
    CpuModelChoice("x86-64-v3", "default", abstract = true),
    CpuModelChoice("host", "default"),
    CpuModelChoice("kvm64", "default"),
    CpuModelChoice("qemu64", "default"),
)

val demoCreateCpuFlags: List<CpuFlagChoice> = listOf(
    CpuFlagChoice("md-clear", "Required to let the guest OS know if MDS is mitigated correctly", listOf("demo-node-a", "demo-node-b")),
    CpuFlagChoice("pcid", "Meltdown fix cost reduction on Westmere, Sandy- and Ivy Bridge Intel CPUs", listOf("demo-node-a", "demo-node-b")),
    CpuFlagChoice("spec-ctrl", "Allows improved Spectre mitigation with Intel CPUs", listOf("demo-node-a", "demo-node-b")),
    CpuFlagChoice("ssbd", "Protection for Speculative Store Bypass for Intel models", listOf("demo-node-a", "demo-node-b")),
    CpuFlagChoice("ibpb", "Allows improved Spectre mitigation with AMD CPUs", listOf("demo-node-a", "demo-node-b")),
    CpuFlagChoice("virt-ssbd", "Basis for Speculative Store Bypass protection for AMD models", listOf("demo-node-a", "demo-node-b")),
    CpuFlagChoice("pdpe1gb", "Allow the guest OS to use 1 GB pages, if the host supports it", listOf("demo-node-a")),
    CpuFlagChoice("aes", "Activate the AES instruction set for hardware acceleration", listOf("demo-node-a", "demo-node-b")),
)

/**
 * The demo wizard's session: next ID 140, pool `lab`, bridges `vmbr0` (VLAN-aware), `vmbr1` and vnet `lab10`, five CPU
 * models, eight flags, free tags, and the sample volumes of [demoCreateVolumes]; every section marked as a sample.
 * Storage choices come from the demo inventory, as for a server (D1).
 */
fun demoCreateSession(kind: NewGuestKind, node: String, epoch: Long, at: Instant): CreateSession = CreateSession(
    kind = kind,
    node = node,
    epoch = epoch,
    nextId = ReadSection.loaded(DEMO_CREATE_NEXT_ID, at, sample = true),
    pools = ReadSection.loaded(listOf(PoolChoice("lab", "Sample pool")), at, sample = true),
    volumes = demoCreateVolumes.mapValues { (_, rows) -> ReadSection.loaded(rows, at, sample = true) },
    bridges = ReadSection.loaded(demoCreateBridges, at, sample = true),
    cpuModels = ReadSection.loaded(if (kind == NewGuestKind.Vm) demoCreateCpuModels else emptyList(), at, sample = true),
    cpuFlags = ReadSection.loaded(if (kind == NewGuestKind.Vm) demoCreateCpuFlags else emptyList(), at, sample = true),
    tagRules = ReadSection.loaded(ClusterTagRules(userAllow = "free"), at, sample = true),
    sample = true,
)
