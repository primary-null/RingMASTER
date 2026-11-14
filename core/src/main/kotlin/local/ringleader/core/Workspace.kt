package local.ringleader.core

import java.io.IOException
import java.time.Instant
import javax.net.ssl.SSLException
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SessionExpiredException : IllegalStateException("The server refused this session. Connect again.")

/** Intentionally not a data class: secret values must never enter generated toString/copy/state. */
class Connection(
    address: String,
    val tokenId: String,
    secret: String,
    fingerprint: String,
    val kind: String = DIRECT_PVE_KIND,
    otp: String? = null,
) : AutoCloseable {
    val address = serverOrigin(address).toString()
    /** Canonical HTTPS origin. Stable for this server; node routing and PDM remote id are not part of it. */
    val remoteKey: String get() = address
    val fingerprint = fingerprint.replace(":", "").trim().lowercase()
    val passwordLogin: Boolean get() = '!' !in tokenId
    internal val tickets = TicketStore()
    private var code: CharArray? = otp?.toCharArray()
    internal fun takeOtp(): String? = synchronized(this) {
        code?.concatToString().also { code?.fill('\u0000'); code = null }
    }
    private var token: CharArray? = secret.toCharArray()
    init {
        require(kind == DIRECT_PVE_KIND || kind == PDM_KIND) { "Choose a direct Proxmox VE or PDM connection." }
        validateIdentity(tokenId, kind)
        require(secret.isNotBlank() && secret.none { it == '\r' || it == '\n' }) { if (passwordLogin) "Enter the password." else "Enter the API token secret." }
        require(this.fingerprint.isEmpty() || this.fingerprint.matches(sha256PinPattern)) { "Use a 64-digit SHA-256 certificate fingerprint." }
    }
    fun <T> withSecret(action: (String) -> T): T {
        val transient = synchronized(this) { token?.concatToString() ?: error("This connection is closed.") }
        return action(transient)
    }
    override fun close() {
        synchronized(this) { token?.fill('\u0000'); token = null; code?.fill('\u0000'); code = null }
        tickets.close()
    }
    override fun toString() = "Connection(redacted)"
}

interface InventoryGateway {
    suspend fun overview(connection: Connection): Overview
    suspend fun details(connection: Connection, guest: Guest): GuestDetails
}

interface NodeStatusGateway {
    suspend fun status(connection: Connection, origin: String, node: String, scope: String): NodeLiveStatus
}

class LiveNodeStatusGateway(private val client: PveClient = PveClient()) : NodeStatusGateway {
    override suspend fun status(connection: Connection, origin: String, node: String, scope: String): NodeLiveStatus {
        directNodeStatusPath(connection, origin, node, scope)
        return runInterruptible(Dispatchers.IO) { client.nodeStatus(connection, node) }
    }
}

class LiveInventoryGateway(private val client: PveClient = PveClient()) : InventoryGateway {
    override suspend fun overview(connection: Connection): Overview {
        requireDirectRequest(connection)
        return runInterruptible(Dispatchers.IO) { client.overview(connection) }
    }
    override suspend fun details(connection: Connection, guest: Guest): GuestDetails {
        requireDirectRequest(connection, guest)
        return runInterruptible(Dispatchers.IO) { client.details(connection, guest) }
    }
}

interface PdmReadGateway {
    suspend fun read(connection: Connection): PdmFleetDocument
}

class LivePdmReadGateway(private val client: PdmClient = PdmClient()) : PdmReadGateway {
    override suspend fun read(connection: Connection): PdmFleetDocument {
        requirePdmRequest(connection)
        return runInterruptible(Dispatchers.IO) { client.read(connection) }
    }
}

interface TaskGateway {
    suspend fun tasks(connection: Connection): List<ClusterTask>
}

class LiveTaskGateway(private val client: PveClient = PveClient()) : TaskGateway {
    override suspend fun tasks(connection: Connection): List<ClusterTask> {
        requireDirectRequest(connection)
        return runInterruptible(Dispatchers.IO) { client.tasks(connection) }
    }
}

interface TaskLogGateway {
    suspend fun status(connection: Connection, node: String, upid: String): TaskLogStatus
    suspend fun page(connection: Connection, node: String, upid: String, start: Int, limit: Int): TaskLogPage
}

class LiveTaskLogGateway(private val client: PveClient = PveClient()) : TaskLogGateway {
    override suspend fun status(connection: Connection, node: String, upid: String): TaskLogStatus {
        requireDirectRequest(connection)
        return runInterruptible(Dispatchers.IO) { client.readTaskLogStatus(connection, node, upid) }
    }
    override suspend fun page(connection: Connection, node: String, upid: String, start: Int, limit: Int): TaskLogPage {
        requireDirectRequest(connection)
        return runInterruptible(Dispatchers.IO) { client.readTaskLog(connection, node, upid, start, limit) }
    }
}

interface PowerGateway {
    suspend fun submitConfirmed(connection: Connection, confirmation: PowerConfirmation): String = submit(connection, confirmedGuest(confirmation), confirmation.action)
    suspend fun submit(connection: Connection, guest: Guest, action: PowerAction): String
    suspend fun status(connection: Connection, node: String, upid: String): PowerTaskStatus
}

class LivePowerGateway(private val client: PveClient = PveClient()) : PowerGateway {
    override suspend fun submitConfirmed(connection: Connection, confirmation: PowerConfirmation): String = runInterruptible(Dispatchers.IO) { client.submitConfirmed(connection, confirmation) }
    override suspend fun submit(connection: Connection, guest: Guest, action: PowerAction): String {
        requireDirectRequest(connection, guest)
        return runInterruptible(Dispatchers.IO) { client.submitPower(connection, guest, action) }
    }
    override suspend fun status(connection: Connection, node: String, upid: String): PowerTaskStatus {
        requireDirectRequest(connection)
        return runInterruptible(Dispatchers.IO) { client.powerStatus(connection, node, upid) }
    }
}

data class WorkspaceState(
    val inspection: GuestInspection? = null, val inspectionLoading: Boolean = false,
    val connected: Boolean = false, val demo: Boolean = false, val address: String = "",
    val overview: Overview? = null, val loading: Boolean = false, val updatedAt: Instant? = null,
    val error: String? = null, val stale: Boolean = false, val selected: Guest? = null,
    val details: GuestDetails? = null, val detailsLoading: Boolean = false,
    val detailsError: String? = null, val detailsUpdatedAt: Instant? = null,
    val connectionEpoch: Long = 0,
    val tasks: List<ClusterTask>? = null, val tasksLoading: Boolean = false,
    val tasksError: String? = null, val tasksStale: Boolean = false, val tasksUpdatedAt: Instant? = null,
    val submissions: List<PowerSubmission> = emptyList(), val powerBusy: Boolean = false, val powerNotice: String? = null,
    /** Actions from an earlier session to this server whose result was lost. Memory only; shown until dismissed. */
    val lostPowerResults: List<LostPowerResult> = emptyList(),
    /** The open create wizard's reads and result (GuestCreateFlow.kt), or null. */
    val creation: CreateSession? = null,
    val kind: String = DIRECT_PVE_KIND,
    val selectedNode: String? = null, val nodeStatus: NodeLiveStatus? = null, val nodeStatusLoading: Boolean = false,
    val nodeStatusError: String? = null, val nodeStatusStale: Boolean = false, val nodeStatusUpdatedAt: Instant? = null,
    val nodeStatusNote: String? = null,
    val live: Boolean = false,
    val liveIntervalSeconds: Int = LIVE_INTERVAL_DEFAULT,
    val liveBackoffSeconds: Int? = null,
    val chartHistory: InspectionSection<List<HistoryPoint>> = InspectionSection(), val chartHistoryLoading: Boolean = false,
    val nodeNetwork: InspectionSection<List<NodeInterface>> = InspectionSection(), val nodeNetworkPending: String? = null,
    val nodeNetworkLoading: Boolean = false, val nodeNetworkNode: String? = null,
    val history: FleetHistory = FleetHistory(),
    /** Last known console frames, memory only (never written to disk), newest last, at most [CONSOLE_STILL_LIMIT]. */
    val consoleStills: Map<ResourceId, ConsoleStill> = emptyMap(),
    /** Consoles paused in memory after their page was left. Empty on a new connection. */
    val keptConsoles: Set<ResourceId> = emptySet(),
    val consoleStillBusy: Set<ResourceId> = emptySet(),
    val consoleStillErrors: Map<ResourceId, String> = emptyMap(),
    /** Open task log. Memory only. Cleared with the session. */
    val taskLog: TaskLogState? = null,
    /** Read-only views (0.21.4). Each keeps its last good value, marked stale when a refresh fails, and is dropped with the session. */
    val permissions: ReadSection<PermissionsView> = ReadSection(),
    val nodeServices: ReadSection<NodeServicesView> = ReadSection(),
    val nodeSyslog: ReadSection<SyslogView> = ReadSection(),
    val nodeUpdates: ReadSection<NodeUpdatesView> = ReadSection(),
    val storageSelection: StorageKey? = null,
    val storageContent: ReadSection<StorageContentView> = ReadSection(),
    /** The storage view's configuration and history reads ([StorageWorkbench]). Dropped with the session. */
    val storageView: StorageViewState = StorageViewState(),
    val storageActions: StorageActionsState = StorageActionsState(),
    /** Immutable non-secret Watcher identity, published atomically with the connection epoch. */
    val watchIdentity: WatchScope? = null,
)

fun requireActiveRemote(remote: String, overview: Overview, kind: String = DIRECT_PVE_KIND): Overview {
    require(overview.guests.all { it.remote == remote }) { "Inventory included a guest from another connection." }
    require(overview.guests.map { it.resource }.distinct().size == overview.guests.size) { "Inventory included duplicate guest identities." }
    if (kind == PDM_KIND) require(overview.guests.all { it.scope.isNotEmpty() }) { "Inventory included a guest from another connection." }
    else require(overview.guests.all { it.scope.isEmpty() }) { "Inventory included a guest from another connection." }
    require(overview.members.all { it.remote == remote }) { "Inventory included a node from another connection." }
    require(overview.storage.all { it.remote == remote }) { "Inventory included storage from another connection." }
    require(overview.members.map { it.name }.distinct().size == overview.members.size) { "Inventory included duplicate node identities." }
    require(overview.storage.map { it.node to it.id }.distinct().size == overview.storage.size) { "Inventory included duplicate storage identities." }
    return overview
}

/** Match a guest by remote, PDM scope, kind, and VMID. A node change on that identity is the same guest. */
fun findCurrentGuest(remote: String, selected: Guest, guests: List<Guest>): Guest? {
    if (selected.remote != remote) return null
    return guests.singleOrNull { it.resource == selected.resource }
}

/**
 * Called on the UI dispatcher. Generation checks discard late results even if I/O ignores cancellation.
 *
 * Thread confinement: every public function and every coroutine this class launches must run on the one dispatcher of
 * [scope] (the Compose UI dispatcher in the app, the test dispatcher in tests). The session fields (generation counters,
 * [connection], the job fields, the history counters, the confirmation and task id maps) have no lock and rely on that.
 * Blocking I/O goes through `runInterruptible(Dispatchers.IO)` and resumes on the caller. Do not call a mutator from a
 * `withContext(Dispatchers.IO)` block or a callback thread; post it to the UI dispatcher first (as the SPICE exit callback does).
 */
class Workspace(
    private val scope: CoroutineScope,
    private val gateway: InventoryGateway = LiveInventoryGateway(),
    private val taskGateway: TaskGateway = LiveTaskGateway(),
    private val powerGateway: PowerGateway = LivePowerGateway(),
    private val pdmGateway: PdmReadGateway = LivePdmReadGateway(),
    private val nodeStatusGateway: NodeStatusGateway = LiveNodeStatusGateway(),
    private val inspectionGateway: GuestInspectionGateway = LiveGuestInspectionGateway(),
    private val clock: () -> Instant = { Instant.now() },
    private val demoSeed: Long = LIVE_DEMO_SEED,
    private val spiceGateway: SpiceGateway = LiveSpiceGateway(),
    private val guestBluetoothGateway: GuestBluetoothGateway = LiveGuestBluetoothGateway(),
    private val consoleStillGateway: ConsoleStillGateway = LiveConsoleStillGateway(),
    private val vncGateway: VncConsoleGateway = LiveVncConsoleGateway(),
    private val taskLogGateway: TaskLogGateway = LiveTaskLogGateway(),
    private val permissionsGateway: PermissionsGateway = LivePermissionsGateway(),
    private val nodeToolsGateway: NodeToolsGateway = LiveNodeToolsGateway(),
    private val storageContentGateway: StorageContentGateway = LiveStorageContentGateway(),
    private val storageGateway: StorageGateway = LiveStorageGateway(),
    @Suppress("unused") private val createGateway: CreateGuestGateway = LiveCreateGuestGateway(),
    private val storageActionGateway: StorageActionGateway = LiveStorageActionGateway(),
) : AutoCloseable {
    private val mutableState = MutableStateFlow(WorkspaceState())
    val state = mutableState.asStateFlow()

    /**
     * Where this session is connected and as whom, for the Watcher: kind, origin, account and certificate pin. No secret.
     * Null when not connected. Pair it with [WorkspaceState.connectionEpoch] read before and after the call.
     */
    fun watchScope(profileId: String? = null): WatchScope? {
        return watchScopeOf(mutableState.value, profileId)
    }
    private val storageWorkbench = StorageWorkbench(scope, mutableState, storageGateway, clock, { connection },
        { epoch -> if (mutableState.value.connectionEpoch == epoch) expire() })
    @Volatile private var storageSendOwner: String? = null
    private val storageActionWorkbench = StorageActionWorkbench(scope, mutableState, storageActionGateway, { connection },
        { id -> if (powerBusy || storageSendOwner != null) false else { storageSendOwner = id; powerBusy = true; mutableState.update { it.copy(powerBusy = true) }; true } },
        { id -> if (storageSendOwner == id) { storageSendOwner = null; powerBusy = false; mutableState.update { it.copy(powerBusy = false) } } },
        { epoch -> if (mutableState.value.connectionEpoch == epoch) expire() },
        { key -> if (mutableState.value.storageSelection == key) refreshStorageContent() })
    fun captureStorage(node: String, storage: String, request: StorageRequest, profileLabel: String, browser: Boolean = false): StorageConfirmation? {
        if (browser && request is StorageRequest.Upload) { mutableState.update { it.copy(storageActions = it.storageActions.copy(notice = "Browser mode cannot upload a local file.")) }; return null }
        return storageActionWorkbench.capture(StorageKey(if (mutableState.value.demo) DEMO_REMOTE_KEY else connection?.remoteKey.orEmpty(), node, storage), request, profileLabel)
    }
    fun submitStorage(confirmation: StorageConfirmation): Boolean = storageActionWorkbench.submit(confirmation)
    fun loadStorageCatalogue(node: String, storage: String) = storageActionRead(node, storage)
    fun queryStorageUrlMetadata(node: String, storage: String, url: String, verifyCertificates: Boolean = true) = storageActionRead(node, storage, url, verifyCertificates)
    fun readStorageAttributes(node: String, storage: String, volid: String) = storageActionRead(node, storage, volid = volid)
    private fun storageActionRead(node: String, storage: String, url: String? = null, verify: Boolean = true, volid: String? = null) =
        storageActionWorkbench.read(StorageKey(if (mutableState.value.demo) DEMO_REMOTE_KEY else connection?.remoteKey.orEmpty(), node, storage), url, verify, volid)
    fun cancelStorageUpload(localId: String) = storageActionWorkbench.cancelUpload(localId)
    fun acknowledgeStorageResult(localId: String) = storageActionWorkbench.acknowledge(localId)
    fun openStorageTaskLog(localId: String): Boolean { val task = storageActionWorkbench.task(localId) ?: return false; openTaskLog(task); return true }
    // Read by the keeper from the reader thread and the expiry job as well as the UI thread.
    @Volatile private var consoleKeepMinutesField = CONSOLE_KEEP_DEFAULT_MINUTES
    private val consoleKeeper = ConsoleKeeper(
        scope = scope,
        now = { clock().toEpochMilli() },
        keepMillis = { consoleKeepMinutesField.coerceAtLeast(0).toLong() * 60_000L },
        changed = { ids -> mutableState.update { it.copy(keptConsoles = ids) } },
    )

    /** Minutes a console stays open after its page is left. 0 turns keeping off and closes the kept ones. */
    var consoleKeepMinutes: Int
        get() = consoleKeepMinutesField
        set(value) {
            val next = value.coerceAtLeast(0)
            if (next == consoleKeepMinutesField) return
            consoleKeepMinutesField = next
            if (next == 0) consoleKeeper.closeAll() else consoleKeeper.wake()
        }
    @Volatile private var connection: Connection? = null
    private var generation = 0L
    private var inventoryOrder = 0L
    private var detailGeneration = 0L
    private var taskGeneration = 0L
    private var nodeGeneration = 0L
    private var pendingNodeSelection: String? = null
    private var epochCounter = 0L
    private var inventoryJob: Job? = null
    private var detailJob: Job? = null
    private var taskJob: Job? = null
    private var nodeJob: Job? = null
    private var powerJob: Job? = null
    private var statusJob: Job? = null
    private val usedConfirmations = mutableSetOf<String>()
    private var powerBusy = false
    /** Server task ids for this session only. Cleared with the secret. Never written to a profile. */
    private val submittedUpids = mutableMapOf<String, String>()
    /** Automatic status reads of accepted tasks, by submission id. GET only; nothing is sent again. */
    private val powerFollowJobs = mutableMapOf<String, Job>()
    private var powerCatchUpJob: Job? = null
    /** The confirmation whose POST is in flight, so a session that ends before its answer can say it may have run. */
    @Volatile private var powerInFlight: PowerConfirmation? = null
    /** Read by scheduled runs (another thread) through [MutationLeases]: this session's in-flight or unresolved mutations. */
    private val foregroundView = ForegroundMutations { key -> foregroundBusy(key) }
    /** Process-wide interlock with scheduled runs. Tests may replace it; the session re-registers itself. */
    internal var mutationLeases: MutationLeases = MutationLeases.shared
        set(value) { field.unregister(foregroundView); field = value; value.register(foregroundView) }
    init { mutationLeases.register(foregroundView) }
    private fun foregroundBusy(key: GuestKey): String? {
        val origin = connection?.remoteKey ?: return null
        if (origin != key.origin) return null
        powerInFlight?.let { if (it.resource.vmid == key.vmid && it.kind == key.kind) return "A foreground action on this guest is being sent." }
        if (createInFlight != null || storageSendOwner != null) return "A foreground change is being sent to this server."
        val open = mutableState.value.submissions.firstOrNull {
            (it.phase == PowerPhase.Accepted || it.phase == PowerPhase.Unknown) && !it.resolvedByCheck && it.remote == origin && it.vmid == key.vmid && it.kind == key.kind
        } ?: return null
        return if (open.phase == PowerPhase.Accepted) "A foreground action on this guest is still running." else "A foreground action on this guest has an unknown result."
    }
    /** Lost results for this app run. Shared by every session window; memory only. */
    internal var lostResults: LostPowerResultStore = LostPowerResultStore.shared
    private var captureNotice: String? = null
    private var liveLoop: Job? = null
    private var liveTick: Job? = null
    private var taskLoop: Job? = null
    private var taskTick: Job? = null
    /** Kept across disconnect so Activity can resume the 10 s task read after the next connect. */
    private var tasksLive = false
    private var taskLogJob: Job? = null
    private var taskLogMoreJob: Job? = null
    private var taskLogToken = 0L
    private var netCounters: Map<ResourceId, NetCounters> = emptyMap()
    private var historyReadings: Map<Any, Any?> = emptyMap()
    private var demoRandom = Random(demoSeed)
    private var demoCursor: Overview = demoOverview

    /**
     * False while another open session is the one in view. Under the Hood has one live-loop readout, and only the
     * session in view writes it; a session coming into view shows its own state at once.
     */
    var inView = true
        set(value) {
            val arrived = value && !field
            field = value
            if (arrived) mutableState.value.let { now ->
                Trace.liveLoop.value = TraceLiveLoop(state = when { !now.connected && !now.demo -> "idle"; !now.live -> "paused"; else -> "waiting" }, intervalMs = now.liveIntervalSeconds * 1000L)
            }
        }
    private inline fun traceLoop(next: (TraceLiveLoop) -> TraceLiveLoop) { if (inView) Trace.liveLoop.value = next(Trace.liveLoop.value) }

    fun connect(address: String, tokenId: String, secret: String, fingerprint: String, kind: String = DIRECT_PVE_KIND, otp: String? = null) =
        connect(Connection(address, tokenId, secret, fingerprint, kind, otp))

    fun connect(value: Connection) {
        closeSession()
        Trace.emit(TraceSource.App, "connect", "Connecting", mapOf("url" to Redact.url(value.address), "kind" to value.kind))
        connection = value
        mutableState.value = WorkspaceState(
            connected = true, address = value.address, kind = value.kind, connectionEpoch = ++epochCounter,
            watchIdentity = WatchScope.of(null, value.kind, value.address, value.tokenId, value.fingerprint.ifEmpty { null }),
            live = true, liveIntervalSeconds = LIVE_INTERVAL_DEFAULT,
            lostPowerResults = lostResults.forRemote(value.address),
        )
        refresh()
        startLiveLoop()
        if (tasksLive) startTaskLoop()
    }

    fun disconnect() {
        closeSession()
        mutableState.value = WorkspaceState()
        Trace.emit(TraceSource.App, "disconnect", "Disconnected")
    }

    fun exploreDemo() {
        closeSession()
        resetDemoMotion()
        val now = clock()
        mutableState.value = WorkspaceState(
            demo = true, overview = demoOverview, updatedAt = now,
            tasks = demoTasks, tasksUpdatedAt = now, connectionEpoch = ++epochCounter,
            live = true, liveIntervalSeconds = LIVE_INTERVAL_DEFAULT,
        )
        recordHistory(demoOverview, now)
        startLiveLoop()
        if (tasksLive) startTaskLoop()
    }

    /**
     * Turn the quiet inventory loop on or off. The interval is clamped to 2..120 seconds.
     * Omitting the interval keeps the current one, so a minimize pause can call `setLive(false)` and later `setLive(true)`.
     */
    fun setLive(enabled: Boolean, intervalSeconds: Int = mutableState.value.liveIntervalSeconds) {
        val clamped = clampLiveInterval(intervalSeconds)
        if (!mutableState.value.connected && !mutableState.value.demo) return
        mutableState.update { it.copy(live = enabled, liveIntervalSeconds = clamped, liveBackoffSeconds = null) }
        startLiveLoop()
    }

    /** [setLive] on, with a jittered first wait, so sessions restored together (window un-minimised) do not poll in the same second. */
    internal fun resumeLive() {
        if (!mutableState.value.connected && !mutableState.value.demo) return
        mutableState.update { it.copy(live = true, liveBackoffSeconds = null) }
        startLiveLoop(stagger = true)
    }

    /** While enabled, repeat the direct cluster task read every 10 seconds. PDM does not call that feed. */
    fun setLiveTasks(enabled: Boolean) {
        tasksLive = enabled
        taskLoop?.cancel()
        taskTick?.cancel()
        if (enabled) startTaskLoop()
    }

    fun refresh() {
        if (mutableState.value.demo) {
            dropNodeRead()
            resetDemoMotion()
            val readAt = clock()
            mutableState.update { it.copy(overview = demoOverview, updatedAt = readAt, stale = false, error = null, loading = false, nodeStatusLoading = false, liveBackoffSeconds = null) }
            recordHistory(demoOverview, readAt)
            retireKeptConsoles(demoOverview)
            reconcile(DEMO_REMOTE_KEY, demoOverview.guests)
            holdMember(DEMO_REMOTE_KEY)
            return
        }
        val current = connection ?: return
        if (inventoryJob?.isActive == true || liveTick?.isActive == true) return
        val expected = generation
        val order = ++inventoryOrder
        val remote = current.remoteKey
        dropNodeRead()
        mutableState.update { it.copy(loading = true, error = null, nodeStatusLoading = false) }
        inventoryJob = scope.launch {
            try {
                val overview = if (current.kind == PDM_KIND) {
                    val document = pdmGateway.read(current)
                    if (generation != expected || inventoryOrder != order) return@launch
                    mergePdmFleet(mutableState.value.overview, remote, document)
                } else {
                    val fetched = gateway.overview(current)
                    if (generation != expected || inventoryOrder != order) return@launch
                    requireActiveRemote(remote, adoptRecordedIndex(remote, fetched), DIRECT_PVE_KIND)
                }
                if (generation != expected || inventoryOrder != order) return@launch
                val selected = mutableState.value.selected
                publishTick(remote, overview, clock())
                selected?.let { findCurrentGuest(remote, it, overview.guests) }?.let { select(it) }
                mutableState.update { it.copy(loading = false, nodeStatusLoading = false) }
                if (current.kind != PDM_KIND) holdMember(remote)
            } catch (e: CancellationException) { throw e }
            catch (e: SessionExpiredException) { if (generation == expected) expire() }
            catch (e: TwoFactorRequiredException) { if (generation == expected) expire(e.message) }
            catch (e: Exception) {
                if (generation == expected && inventoryOrder == order) mutableState.update {
                    it.copy(loading = false, stale = it.overview != null, error = readableError(e), nodeStatusLoading = false, nodeStatusStale = it.nodeStatus != null || it.nodeStatusStale)
                }
            }
        }
    }

    /** Manual Activity read. Independent of inventory refresh, and a duplicate call does not queue another request. */
    fun refreshTasks() {
        if (taskJob?.isActive == true || taskTick?.isActive == true) return
        if (mutableState.value.demo) {
            mutableState.update { it.copy(tasks = demoTasks, tasksLoading = false, tasksStale = false, tasksError = null, tasksUpdatedAt = Instant.now()) }
            return
        }
        val current = connection ?: return
        if (current.kind == PDM_KIND) {
            mutableState.update { it.copy(tasks = null, tasksLoading = false, tasksStale = false, tasksError = null, tasksUpdatedAt = null) }
            return
        }
        val session = generation
        val expectedTask = taskGeneration
        val remote = current.remoteKey
        mutableState.update { it.copy(tasksLoading = true, tasksError = null) }
        taskJob = scope.launch {
            try {
                val fetched = taskGateway.tasks(current)
                if (generation != session || taskGeneration != expectedTask) return@launch
                val tasks = requireActiveTasks(remote, fetched)
                mutableState.update { it.copy(tasks = tasks, tasksLoading = false, tasksStale = false, tasksError = null, tasksUpdatedAt = Instant.now()) }
            } catch (e: CancellationException) { throw e }
            catch (e: SessionExpiredException) { if (generation == session) expire() }
            catch (e: Exception) {
                if (generation == session && taskGeneration == expectedTask) mutableState.update {
                    it.copy(tasksLoading = false, tasksStale = it.tasks != null, tasksError = readableError(e))
                }
            }
        }
    }

    /** Resolve only a current creation submission; never guess a task from the recent feed. */
    fun openCreateTaskLog(localId: String): Boolean {
        val state = mutableState.value
        val submission = state.submissions.singleOrNull { it.localId == localId && it.action == PowerAction.Create } ?: return false
        val current = connection ?: return false
        if (!state.connected || state.demo || current.kind != DIRECT_PVE_KIND || submission.remote != current.remoteKey) return false
        val upid = submittedUpids[localId] ?: return false
        val parsed = parseServerUpid(upid) ?: return false
        if (parsed.node != submission.node || parsed.subjectId != submission.vmid.toString()) return false
        openTaskLog(ClusterTask(submission.remote, upid, parsed.node, parsed.type, parsed.subjectId,
            parsed.user, null, null, null, TaskOutcome.Unknown))
        return true
    }

    /** Open one task log. A PDM connection is refused before any node path is built. Demo samples never leave this computer. */
    fun openTaskLog(task: ClusterTask) {
        taskLogJob?.cancel()
        taskLogMoreJob?.cancel()
        val token = ++taskLogToken
        val snapshot = mutableState.value
        if (snapshot.kind == PDM_KIND || connection?.kind == PDM_KIND) {
            mutableState.update { it.copy(taskLog = TaskLogState(task, error = TASK_LOG_DIRECT_ONLY, loading = false, following = false)) }
            return
        }
        if (snapshot.demo || task.remote == DEMO_REMOTE_KEY) {
            if (!snapshot.demo || task.remote != DEMO_REMOTE_KEY) {
                mutableState.update { it.copy(taskLog = TaskLogState(task, error = "The connection changed.", loading = false, following = false)) }
                return
            }
            val session = generation
            val epoch = snapshot.connectionEpoch
            val fixture = demoTaskLog(task)
            mutableState.update { it.copy(taskLog = fixture) }
            if (fixture.status?.running == true) startDemoTaskLog(token, session, epoch, task)
            return
        }
        val current = connection
        if (current == null || !snapshot.connected || current.kind != DIRECT_PVE_KIND || task.remote != current.remoteKey) {
            mutableState.update { it.copy(taskLog = TaskLogState(task, error = "The connection changed.", loading = false, following = false)) }
            return
        }
        val session = generation
        val epoch = snapshot.connectionEpoch
        mutableState.update { it.copy(taskLog = TaskLogState(task, loading = true, following = true)) }
        // Read status and the lines after the last one held, every 2 s while the task runs. A failed read keeps the
        // lines, says so, and tries again after a longer wait (up to 30 s). Only permission denial or session expiry stops it.
        taskLogJob = scope.launch {
            var failures = 0
            var first = true
            while (isActive) {
                if (!first || failures > 0) delay(taskLogRetryDelay(failures))
                if (!logCurrent(token, session, epoch, task)) return@launch
                try {
                    val status = taskLogGateway.status(current, task.node, task.upid)
                    if (!logCurrent(token, session, epoch, task)) return@launch
                    val start = mutableState.value.taskLog?.lines?.lastOrNull()?.n ?: 0
                    val page = taskLogGateway.page(current, task.node, task.upid, start, TASK_LOG_PAGE_LIMIT)
                    if (!logCurrent(token, session, epoch, task)) return@launch
                    publishLog(token, session, epoch, task, status, page)
                    first = false
                    failures = 0
                    if (!taskLogKeepsPolling(status)) return@launch
                } catch (e: CancellationException) { throw e }
                catch (e: SessionExpiredException) { if (generation == session) expire(); return@launch }
                catch (e: GuestPermissionException) { failLog(token, session, epoch, task, TASK_LOG_AUDIT_DENIED); return@launch }
                catch (e: Exception) {
                    failures++
                    val wait = taskLogRetryDelay(failures) / 1000
                    retryLog(token, session, epoch, task, "${readableError(e)} Trying again in $wait s.")
                }
            }
        }
    }

    fun closeTaskLog() {
        taskLogToken++
        taskLogJob?.cancel()
        taskLogMoreJob?.cancel()
        taskLogJob = null
        taskLogMoreJob = null
        mutableState.update { it.copy(taskLog = null) }
    }

    /** Resume auto-scroll. The follow poll keeps running until the task stops, whether or not this is on. */
    fun setTaskLogFollowing(following: Boolean) {
        mutableState.update { state ->
            val log = state.taskLog ?: return@update state
            if (log.following == following) state else state.copy(taskLog = log.copy(following = following))
        }
    }

    /** Next page after the lines already shown. A running follow also appends on its own interval. */
    fun loadMoreTaskLog() {
        val snapshot = mutableState.value
        val log = snapshot.taskLog ?: return
        if (log.loading || snapshot.demo || !taskLogHasMore(log.lines, log.total)) return
        val current = connection ?: return
        if (current.kind != DIRECT_PVE_KIND || log.task.remote != current.remoteKey) return
        if (taskLogMoreJob?.isActive == true) return
        val token = taskLogToken
        val session = generation
        val epoch = snapshot.connectionEpoch
        val task = log.task
        val start = log.lines.lastOrNull()?.n ?: 0
        mutableState.update { state ->
            val open = state.taskLog ?: return@update state
            if (open.task.identity != task.identity) state else state.copy(taskLog = open.copy(loading = true))
        }
        taskLogMoreJob = scope.launch {
            try {
                val page = taskLogGateway.page(current, task.node, task.upid, start, TASK_LOG_PAGE_LIMIT)
                if (!logCurrent(token, session, epoch, task)) return@launch
                publishLog(token, session, epoch, task, mutableState.value.taskLog?.status, page)
            } catch (e: CancellationException) { throw e }
            catch (e: SessionExpiredException) { if (generation == session) expire() }
            catch (e: GuestPermissionException) { failLog(token, session, epoch, task, TASK_LOG_AUDIT_DENIED) }
            // One failed Load more keeps the lines and any running follow; the button can be used again.
            catch (e: Exception) { retryLog(token, session, epoch, task, readableError(e)) }
        }
    }

    private fun startDemoTaskLog(token: Long, session: Long, epoch: Long, task: ClusterTask) {
        val tail = demoTaskLogTail(task)
        if (tail.isEmpty()) return
        taskLogJob = scope.launch {
            var index = 0
            while (isActive && index < tail.size) {
                delay(TASK_LOG_POLL_MS)
                if (!logCurrent(token, session, epoch, task)) return@launch
                val line = tail[index++]
                val finished = index == tail.size
                mutableState.update { state ->
                    if (taskLogToken != token || generation != session || state.connectionEpoch != epoch) return@update state
                    val log = state.taskLog ?: return@update state
                    if (log.task.identity != task.identity) return@update state
                    val lines = mergeTaskLogLines(log.lines, listOf(line))
                    state.copy(taskLog = log.copy(
                        lines = lines,
                        total = lines.lastOrNull()?.n ?: lines.size,
                        status = if (finished) demoTaskLogFinished(task) else log.status,
                        loading = false,
                        error = null,
                    ))
                }
            }
        }
    }

    private fun logCurrent(token: Long, session: Long, epoch: Long, task: ClusterTask): Boolean {
        if (taskLogToken != token || generation != session) return false
        val state = mutableState.value
        return state.connectionEpoch == epoch && state.taskLog?.task?.identity == task.identity
    }

    private fun publishLog(token: Long, session: Long, epoch: Long, task: ClusterTask, status: TaskLogStatus?, page: TaskLogPage) {
        mutableState.update { state ->
            if (taskLogToken != token || generation != session || state.connectionEpoch != epoch) return@update state
            val log = state.taskLog ?: return@update state
            if (log.task.identity != task.identity) return@update state
            val storageTask = storageActionWorkbench.ownsTask(task)
            val safePage = if (storageTask) page.lines.map { it.copy(text = storageSafeLogText(it.text)) } else page.lines
            val safeStatus = if (storageTask && status != null && status.exitStatus != null && status.exitStatus != "OK" && !Regex("WARNINGS: [0-9]+").matches(status.exitStatus)) status.copy(exitStatus = "Storage task did not finish with OK.") else status
            val merged = mergeTaskLogLines(log.lines, safePage)
            val total = maxOf(log.total, page.total, merged.lastOrNull()?.n ?: 0)
            val (lines, dropped) = capTaskLogLines(merged)
            state.copy(taskLog = log.copy(lines = lines, total = total, status = safeStatus ?: log.status, error = null, loading = false, dropped = log.dropped + dropped))
        }
    }

    /** A follow read failed. Keep the lines and the follow; show the reason until the next good read. */
    private fun retryLog(token: Long, session: Long, epoch: Long, task: ClusterTask, message: String) {
        mutableState.update { state ->
            if (taskLogToken != token || generation != session || state.connectionEpoch != epoch) return@update state
            val log = state.taskLog ?: return@update state
            if (log.task.identity != task.identity) return@update state
            state.copy(taskLog = log.copy(loading = false, error = message))
        }
    }

    private fun failLog(token: Long, session: Long, epoch: Long, task: ClusterTask, message: String) {
        mutableState.update { state ->
            if (taskLogToken != token || generation != session || state.connectionEpoch != epoch) return@update state
            val log = state.taskLog ?: return@update state
            if (log.task.identity != task.identity) return@update state
            state.copy(taskLog = log.copy(loading = false, error = message, following = false))
        }
    }

    /** Capture the current guest, route, and session. Returns no confirmation when the action is not available. */
    fun capturePower(action: PowerAction, profileLabel: String, snapshot: SnapshotRequest? = null): PowerConfirmation? {
        val current = mutableState.value
        val guest = current.selected ?: return null
        if (current.kind == PDM_KIND || guest.scope.isNotEmpty()) return null
        if (!actionMatches(guest, action) || current.loading || current.stale || current.overview == null) return null
        val listed = current.overview.guests.singleOrNull {
            it.resource == guest.resource && it.node == guest.node && it.status == guest.status && it.name == guest.name && it.template == guest.template
        } ?: return null
        val label = profileLabel.replace(Regex("\\s+"), " ").trim().take(160)
        if (label.isEmpty() || label.any { it.isISOControl() }) return null
        val confirmation = if (current.demo) {
            PowerConfirmation(current.connectionEpoch, DEMO_REMOTE_KEY, "Demonstration", label, listed.resource, listed.node, listed.name, listed.kind, action, listed.status, listed.template)
        } else if (current.connected && current.address.isNotBlank()) {
            PowerConfirmation(current.connectionEpoch, current.address, current.address, label, listed.resource, listed.node, listed.name, listed.kind, action, listed.status, listed.template)
        } else return null
        val bound = confirmation.copy(snapshot = snapshot)
        val refusal = powerRefusal(bound, current)
        // A refused click says why instead of doing nothing. A later successful capture clears that sentence.
        val previous = captureNotice
        captureNotice = refusal
        if (refusal != null) mutableState.update { it.copy(powerNotice = refusal) }
        else if (previous != null) mutableState.update { if (it.powerNotice == previous) it.copy(powerNotice = null) else it }
        return if (refusal == null) bound else null
    }

    /** Forget one lost result. Nothing is sent to the server. */
    fun dismissLostPowerResult(id: String) {
        lostResults.dismiss(id)
        mutableState.update { state -> state.copy(lostPowerResults = state.lostPowerResults.filterNot { it.id == id }) }
    }

    /**
     * Revalidate the captured confirmation and send at most one action.
     * A later disconnect ignores the result and does not claim the remote task stopped.
     */
    fun submitPower(confirmation: PowerConfirmation): Boolean {
        if (confirmation.resource.scope.isNotEmpty() || connection?.kind == PDM_KIND || mutableState.value.kind == PDM_KIND) {
            mutableState.update { it.copy(powerNotice = "Power actions are not available for a PDM connection. Nothing was sent.") }
            return false
        }
        if (powerBusy) {
            mutableState.update { it.copy(powerNotice = "An action is already being sent. Nothing else was sent.") }
            return false
        }
        val refusal = powerRefusal(confirmation, mutableState.value)
        if (refusal != null) {
            mutableState.update { it.copy(powerNotice = refusal) }
            return false
        }
        if (!mutableState.value.demo && connection?.let { mutationLeases.scheduledHolds(GuestKey(it.remoteKey, confirmation.kind, confirmation.resource.vmid)) } == true) {
            mutableState.update { it.copy(powerNotice = MutationLeases.SCHEDULED_REFUSAL) }
            return false
        }
        if (!usedConfirmations.add(confirmation.confirmationId)) {
            mutableState.update { it.copy(powerNotice = "This confirmation was already used. Nothing else was sent.") }
            return false
        }
        if (mutableState.value.demo) {
            rememberSubmission(simulatedSubmission(confirmation), confirmation.epoch, null)
            return true
        }
        val current = connection ?: run {
            mutableState.update { it.copy(powerNotice = "The connection changed. Nothing was sent.") }
            return false
        }
        // Checked and marked under the scheduler interlock's lock, so a scheduled run cannot claim the guest in between.
        if (mutationLeases.foreground(GuestKey(current.remoteKey, confirmation.kind, confirmation.resource.vmid)) { powerBusy = true; powerInFlight = confirmation; true } == null) {
            mutableState.update { it.copy(powerNotice = MutationLeases.SCHEDULED_REFUSAL) }
            return false
        }
        val session = generation
        mutableState.update { it.copy(powerBusy = true, powerNotice = null) }
        powerJob = scope.launch {
            try {
                val latest = powerRefusal(confirmation, mutableState.value)
                if (latest != null || generation != session || current.kind != DIRECT_PVE_KIND || confirmation.resource.scope.isNotEmpty()) {
                    if (generation == session && latest != null) mutableState.update { it.copy(powerNotice = latest) }
                    return@launch
                }
                val upid = powerGateway.submitConfirmed(current, confirmation)
                if (generation != session) return@launch
                val submission = PowerSubmission(
                    newLocalId(), confirmation.remote, confirmation.node, confirmation.resource.vmid, confirmation.kind,
                    confirmation.name, confirmation.action, PowerPhase.Accepted,
                    "The server accepted this action. It is not finished.", false, true,
                )
                rememberSubmission(submission, confirmation.epoch, upid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                if (generation == session) expire()
            } catch (e: AmbiguousPowerResultException) {
                if (generation == session) rememberOutcome(confirmation, PowerPhase.Unknown, e.message ?: "The result is unknown. It was not sent again.", false)
            } catch (e: PowerResponseUnclearException) {
                if (generation == session) rememberOutcome(confirmation, PowerPhase.Unknown, e.message ?: "The result is unknown. It was not sent again.", false)
            } catch (e: Exception) {
                if (generation == session) rememberOutcome(confirmation, PowerPhase.Rejected, readableError(e), false)
            } finally {
                if (generation == session) {
                    powerBusy = false
                    powerInFlight = null
                    mutableState.update { if (it.connectionEpoch == confirmation.epoch) it.copy(powerBusy = false) else it }
                }
            }
        }
        return true
    }

    /**
     * One manual status read for a task this session submitted. It never sends the action again.
     * Accepted tasks are also followed automatically; this reads once more on demand.
     */
    fun checkPower(localId: String) {
        if (mutableState.value.demo || statusJob?.isActive == true) return
        val item = mutableState.value.submissions.find { it.localId == localId } ?: return
        if (!item.checkable || item.checking || item.simulated) return
        val upid = submittedUpids[localId] ?: return
        val current = connection ?: return
        if (current.kind != DIRECT_PVE_KIND) return
        val session = generation
        val epoch = mutableState.value.connectionEpoch
        mutableState.update { state ->
            state.copy(submissions = state.submissions.map { if (it.localId == localId) it.copy(checking = true) else it })
        }
        statusJob = scope.launch {
            try {
                val status = powerGateway.status(current, item.node, upid)
                if (generation != session) return@launch
                applyPowerStatus(localId, epoch, session, status, manual = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                if (generation == session) expire()
            } catch (e: Exception) {
                if (generation != session) return@launch
                val detail = when (e) {
                    is AmbiguousPowerResultException, is PowerResponseUnclearException -> e.message ?: "The status check did not finish. It was not sent again."
                    else -> readableError(e)
                }
                mutableState.update { state ->
                    if (state.connectionEpoch != epoch) state
                    else state.copy(
                        powerNotice = "The latest status check failed. The earlier result stands; the action was not sent again. $detail",
                        submissions = state.submissions.map { submission ->
                            if (submission.localId == localId) submission.copy(checking = false) else submission
                        },
                    )
                }
            }
        }
    }

    // Guest creation (GuestCreateFlow.kt). Phase A0: signatures only. No screen calls these yet, and none sends anything.

    /**
     * Open the create wizard's session for [kind] on [node] and start its reads (next ID, pools, bridges, and for a VM
     * the CPU models and flags); in demo mode the session is [demoCreateSession]. Returns false, with nothing read, when
     * [createAvailability] says no or [node] is not online. Opening again replaces the previous session.
     */
    private var createJob: Job? = null
    private var createSerial = 0L
    private val createReads = mutableMapOf<String, Job>()
    private val createTokens = mutableMapOf<String, Long>()
    private val capturedCreates = mutableMapOf<String, Long>()
    private val usedCreateConfirmations = mutableSetOf<String>()
    private var createSecretsInFlight: CreateSecrets? = null
    @Volatile private var createInFlight: CreateConfirmation? = null

    fun openCreateSession(kind: NewGuestKind, node: String): Boolean {
        val state = mutableState.value
        if (!createAvailability(state, kind).available || state.overview?.members?.none { it.name == node && it.status == "online" } != false) return false
        closeCreateSession()
        val serial = ++createSerial
        mutableState.update { it.copy(creation = if (it.demo) demoCreateSession(kind, node, it.connectionEpoch, clock()).copy(generation = serial) else CreateSession(kind, node, it.connectionEpoch, serial, identityIsRootPam = connection?.let { c -> c.passwordLogin && c.tokenId == "root@pam" } == true)) }
        if (!state.demo) {
            val content = if (kind == NewGuestKind.Vm) "iso" else "vztmpl"
            storageChoices(state.overview.storage, node, content).firstOrNull()?.let { readForCreate(CreateRead.Volumes(it.id, content)) }
            readForCreate(CreateRead.NextId); readForCreate(CreateRead.Pools); readForCreate(CreateRead.Bridges)
            if (kind == NewGuestKind.Vm) { readForCreate(CreateRead.CpuModels(null)); readForCreate(CreateRead.CpuFlags(null, "kvm")) }
            if (state.permissions.updatedAt?.let { java.time.Duration.between(it, clock()).seconds < 60 } != true) loadPermissions()
        }
        return true
    }
    fun changeCreateNode(node: String) {
        val c = mutableState.value.creation ?: return
        if (c.result is CreateResult.Sending || c.result is CreateResult.Following) return
        openCreateSession(c.kind, node)
    }
    fun closeCreateSession() {
        createSerial++
        createReads.values.toList().forEach { it.cancel() }; createReads.clear(); createTokens.clear(); capturedCreates.clear()
        createSecretsInFlight?.close(); createSecretsInFlight = null
        mutableState.update { it.copy(creation = null) }
    }
    /** Refresh only media choices in the same editor generation; never replaces the draft session. */
    fun refreshCreateMedia(epoch: Long, generation: Long, node: String, storage: String, content: String): Boolean {
        val c = mutableState.value.creation ?: return false
        if (c.epoch != epoch || c.generation != generation || c.node != node || content !in listOf("iso", "vztmpl")) return false
        val key = storage to content
        if (c.sample) {
            val rows = mutableState.value.storageContent.value?.takeIf { it.key.node == node && it.key.storage == storage }?.volumes ?: return false
            mutableState.update { it.copy(creation = c.copy(volumes = c.volumes + (key to ReadSection.loaded(rows.filter { it.content == content }, clock(), sample = true)))) }
        } else {
            mutableState.update { it.copy(creation = c.copy(volumes = c.volumes + (key to ReadSection()))) }
            readForCreate(CreateRead.Volumes(storage, content))
        }
        return true
    }
    fun readForCreate(read: CreateRead) {
        val c = mutableState.value.creation ?: return
        if (c.sample) return
        val conn = connection ?: return
        if (read is CreateRead.Volumes) {
            val section = c.volumes[read.storage to read.content]
            if (section?.loading == true || (section?.value != null && section.error == null && !section.stale)) return
        }
        val key = when (read) { is CreateRead.CpuModels -> "cpu"; is CreateRead.CpuFlags -> "flags"; is CreateRead.CheckId -> "check"; else -> read.toString() }
        createReads.remove(key)?.cancel()
        val token = (createTokens[key] ?: 0) + 1; createTokens[key] = token
        fun apply(change: (CreateSession) -> CreateSession) { mutableState.update { st -> if (st.creation?.generation == c.generation && st.connectionEpoch == c.epoch && createTokens[key] == token) st.copy(creation = change(st.creation)) else st } }
        fun section(old: CreateSession, value: Any? = null, error: String? = null, started: Boolean = false): CreateSession {
            fun <T> next(previous: ReadSection<T>, v: T?): ReadSection<T> = when { started -> previous.started(); error != null -> previous.failed(error); v != null -> ReadSection.loaded(v, clock()); else -> previous }
            @Suppress("UNCHECKED_CAST")
            return when (read) {
                CreateRead.NextId -> old.copy(nextId = next(old.nextId, (value as? NextIdReply.Free)?.id))
                is CreateRead.CheckId -> old.copy(idCheck = when { started -> IdCheck.Checking(read.vmid); error != null -> IdCheck.Failed(read.vmid, error); value is NextIdReply.Free -> IdCheck.Free(read.vmid); else -> IdCheck.Taken(read.vmid, "ID ${read.vmid} is in use.") })
                CreateRead.Pools -> old.copy(pools = next(old.pools, value as? List<PoolChoice>))
                CreateRead.Bridges -> old.copy(bridges = next(old.bridges, value as? List<BridgeChoice>))
                is CreateRead.CpuModels -> old.copy(cpuModels = next(old.cpuModels, value as? List<CpuModelChoice>))
                is CreateRead.CpuFlags -> old.copy(cpuFlags = next(old.cpuFlags, value as? List<CpuFlagChoice>))
                CreateRead.TagRules -> old.copy(tagRules = next(old.tagRules, value as? ClusterTagRules))
                is CreateRead.Volumes -> { val k = read.storage to read.content; old.copy(volumes = old.volumes + (k to next(old.volumes[k] ?: ReadSection(), value as? List<StorageVolume>))) }
            }
        }
        apply { section(it, started = true) }
        createReads[key] = scope.launch {
            try {
                if (read is CreateRead.CheckId) delay(400)
                val value = when (read) {
                    CreateRead.NextId -> createGateway.nextId(conn, null)
                    is CreateRead.CheckId -> createGateway.nextId(conn, read.vmid)
                    CreateRead.Pools -> createGateway.pools(conn)
                    CreateRead.Bridges -> createGateway.bridges(conn, c.node)
                    is CreateRead.CpuModels -> createGateway.cpuModels(conn, c.node, read.arch)
                    is CreateRead.CpuFlags -> createGateway.cpuFlags(conn, c.node, read.arch, read.accel)
                    CreateRead.TagRules -> createGateway.clusterOptions(conn)
                    is CreateRead.Volumes -> {
                        require(read.content in listOf("iso", "vztmpl", "import", "images", "rootdir"))
                        require(storageChoices(mutableState.value.overview?.storage.orEmpty(), c.node, read.content).any { it.id == read.storage })
                        createGateway.volumes(conn, c.node, read.storage, read.content)
                    }
                }
                apply { section(it, value) }
            } catch (e: CancellationException) { throw e }
            catch (e: SessionExpiredException) { if (mutableState.value.connectionEpoch == c.epoch) expire() }
            catch (_: Exception) { apply { section(it, error = "Could not read this list. Retry when the connection is available.") } }
        }
    }
    fun captureCreate(request: CreateRequest, profileLabel: String, secrets: SecretState): CreateConfirmation? {
        val st = mutableState.value
        val c = st.creation ?: return null
        if (c.node != request.node || c.kind != request.kind) return null
        val confirmation = CreateConfirmation(st.connectionEpoch, if (st.demo) DEMO_REMOTE_KEY else st.address,
            if (st.demo) "Demonstration" else st.address, profileLabel, request.copy(params = request.params.toList()), secrets)
        val refusal = createRefusal(confirmation, st)
        if (refusal != null) { mutableState.update { it.copy(powerNotice = refusal) }; return null }
        capturedCreates[confirmation.confirmationId] = c.generation
        return confirmation
    }
    fun submitCreate(confirmation: CreateConfirmation, secrets: CreateSecrets): Boolean {
        val st = mutableState.value
        val c = st.creation
        if (c == null || capturedCreates[confirmation.confirmationId] != c.generation || c.kind != confirmation.request.kind || c.node != confirmation.request.node || createRefusal(confirmation, st) != null || secrets.state != confirmation.secretsSet || !usedCreateConfirmations.add(confirmation.confirmationId)) { secrets.close(); mutableState.update { it.copy(powerNotice = createRefusal(confirmation, st) ?: "This confirmation is no longer usable. Nothing was sent.") }; return false }
        val r = confirmation.request
        val id = newLocalId()
        fun result(value: CreateResult) { mutableState.update { if (it.connectionEpoch == confirmation.epoch && it.creation?.generation == c.generation) it.copy(creation = it.creation.copy(result = value)) else it } }
        fun submission(phase: PowerPhase, detail: String, upid: String? = null) {
            if (mutableState.value.connectionEpoch == confirmation.epoch && createInFlight === confirmation) powerBusy = false
            if (upid != null) submittedUpids[id] = upid
            mutableState.update { if (it.connectionEpoch == confirmation.epoch) it.copy(powerBusy = if (createInFlight === confirmation || st.demo) false else it.powerBusy, submissions = listOf(PowerSubmission(id, confirmation.remote, r.node, r.vmid, r.kind.guestKind, r.name, PowerAction.Create, phase, detail, st.demo, upid != null)) + it.submissions) else it }
        }
        if (st.demo) { secrets.close(); submission(PowerPhase.Simulated, "Simulated. Nothing was sent."); result(CreateResult.Simulated(r.kind, r.node, r.vmid)); return true }
        val conn = connection ?: run { secrets.close(); return false }
        powerBusy = true
        createInFlight = confirmation; createSecretsInFlight = secrets
        mutableState.update { it.copy(powerBusy = true) }; result(CreateResult.Sending(r.kind, r.node, r.vmid))
        createJob = scope.launch {
            var upid: String? = null
            try {
                val reply = try { createGateway.submit(conn, r, secrets) } finally { secrets.close(); createSecretsInFlight = null }
                if (mutableState.value.connectionEpoch != confirmation.epoch) return@launch
                when (reply) {
                    is CreateReply.Rejected -> {
                        fun target(key: String): FieldId? {
                            val diskKey = Regex("(?:ide|sata|scsi|virtio)[0-9]+|rootfs|mp[0-9]+")
                            val parameter = r.params.firstOrNull { it.key == key }
                            if (diskKey.matches(key) && parameter != null) {
                                return if (createValueText(parameter.value).contains(",media=cdrom")) {
                                    if (key == "ide0") CreateFields.DRIVER_DISC else CreateFields.INSTALL
                                } else CreateFields.disk(key)
                            }
                            if (key == "storage") return r.params.firstOrNull { diskKey.matches(it.key) }?.let { CreateFields.disk(it.key, "storage") }
                            return createErrorTarget(key)
                        }
                        val fields = reply.fieldErrors.keys.mapNotNull { k -> target(k)?.let { it to "The server refused this field." } }.toMap()
                        val serverSaid = reply.fieldErrors.keys.filter { target(it) == null }.associateWith { "The server refused this parameter." }
                        submission(PowerPhase.Rejected, "The server refused the create request.")
                        result(CreateResult.Rejected(r.kind, r.node, r.vmid, reply.status, "The server refused the create request.", fields, serverSaid))
                        return@launch
                    }
                    is CreateReply.Accepted -> { upid = reply.upid; submission(PowerPhase.Accepted, "Creating guest.", upid); createInFlight = null; result(CreateResult.Following(r.kind, r.node, r.vmid, id)) }
                }
                var attempt = 0
                var failures = 0
                while (mutableState.value.connectionEpoch == confirmation.epoch) {
                    delay(powerFollowDelay(attempt++))
                    val status = try { createGateway.status(conn, r.node, upid!!) } catch (e: SessionExpiredException) { throw e } catch (e: CancellationException) { throw e } catch (e: GuestPermissionException) {
                        mutableState.update { it.copy(powerNotice = POWER_FOLLOW_DENIED) }; return@launch
                    } catch (_: Exception) {
                        if (++failures >= POWER_FOLLOW_NOTICE_FAILURES) mutableState.update { it.copy(powerNotice = "Reading the task status failed. The create was not sent again.") }
                        continue
                    }
                    failures = 0
                    if (mutableState.value.connectionEpoch != confirmation.epoch) return@launch
                    if (status.phase == PowerPhase.Accepted) continue
                    mutableState.update { it.copy(submissions = it.submissions.map { sub -> if (sub.localId == id) sub.copy(phase = status.phase, detail = status.detail) else sub }) }
                    val lines = try {
                        val first = createGateway.taskLog(conn, r.node, upid, 0, 500)
                        if (first.total > 500) first.lines + createGateway.taskLog(conn, r.node, upid, (first.total - 500).coerceAtLeast(0), 500).lines else first.lines
                    } catch (e: CancellationException) { throw e } catch (e: SessionExpiredException) { throw e } catch (_: Exception) { emptyList() }
                    if (mutableState.value.connectionEpoch != confirmation.epoch) return@launch
                    val outcome = createTaskOutcome(status)
                    when (outcome) {
                        CreateTaskOutcome.Succeeded -> result(CreateResult.Succeeded(r.kind, r.node, r.vmid, id, r.start))
                        is CreateTaskOutcome.FinishedWithWarnings -> result(CreateResult.FinishedWithWarnings(r.kind, r.node, r.vmid, id, outcome.count, createWarningLines(lines)))
                        is CreateTaskOutcome.Failed -> result(CreateResult.Failed(r.kind, r.node, r.vmid, id, outcome.text, lines.takeLast(20).map { it.text.take(300) }))
                        else -> { result(CreateResult.Unknown(r.kind, r.node, r.vmid, id, "The task outcome is unknown. It was not sent again.")); return@launch }
                    }
                    var last = 0L
                    for (offset in CREATE_APPEAR_READS_MS) {
                        delay(offset - last); last = offset
                        if (mutableState.value.connectionEpoch != confirmation.epoch) return@launch
                        val overview = refreshCreateInventory(conn, confirmation.epoch)
                        if (mutableState.value.connectionEpoch != confirmation.epoch) return@launch
                        val appeared = overview?.guests?.any { it.id == r.vmid && it.node == r.node && it.kind == r.kind.guestKind } == true

                        val appearance = if (appeared) GuestAppearance.Appeared else if (offset == CREATE_APPEAR_DEADLINE_MS) GuestAppearance.NotYet else GuestAppearance.Waiting
                        mutableState.update { state -> val current = state.creation; if (current?.generation != c.generation) state else state.copy(creation = current.copy(result = when (val old = current.result) { is CreateResult.Succeeded -> old.copy(appearance = appearance); is CreateResult.FinishedWithWarnings -> old.copy(appearance = appearance); is CreateResult.Failed -> old.copy(appearance = appearance); else -> old })) }
                        if (appeared) break
                    }
                    break
                }
            } catch (e: SessionExpiredException) { if (mutableState.value.connectionEpoch == confirmation.epoch) expire() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val unknown = e is AmbiguousPowerResultException || e is PowerResponseUnclearException || upid != null
                submission(if (unknown) PowerPhase.Unknown else PowerPhase.Rejected, if (unknown) "Outcome unknown. It was not sent again." else "Nothing was sent.")
                result(if (unknown) CreateResult.Unknown(r.kind, r.node, r.vmid, id, "The outcome is unknown. It was not sent again. Check the server before trying again.") else CreateResult.Rejected(r.kind, r.node, r.vmid, null, "Could not reach the server. Nothing was sent."))
            } finally { secrets.close(); if (createInFlight === confirmation) { createInFlight = null; createSecretsInFlight = null; if (mutableState.value.connectionEpoch == confirmation.epoch) { powerBusy = false; mutableState.update { it.copy(powerBusy = false) } } } }
        }
        return true
    }
    private val createChecks = mutableMapOf<String, Any>()

    /** Shared ordering with manual/live inventory reads; never publishes an older completion. */
    private suspend fun refreshCreateInventory(conn: Connection, epoch: Long): Overview? {
        val session = generation
        val order = ++inventoryOrder
        try {
            val fetched = gateway.overview(conn)
            currentCoroutineContext().ensureActive()
            if (generation != session || mutableState.value.connectionEpoch != epoch || order != inventoryOrder) return null
            val overview = requireActiveRemote(conn.remoteKey, adoptRecordedIndex(conn.remoteKey, fetched), DIRECT_PVE_KIND)
            publishTick(conn.remoteKey, overview, clock())
            mutableState.update { it.copy(loading = false) }
            return overview
        } catch (e: CancellationException) { throw e }
        catch (e: SessionExpiredException) { if (generation == session && mutableState.value.connectionEpoch == epoch) expire(); throw e }
        catch (e: Exception) {
            if (generation == session && mutableState.value.connectionEpoch == epoch && order == inventoryOrder)
                mutableState.update { it.copy(loading = false, stale = it.overview != null, error = readableError(e)) }
            return null
        }
    }

    fun checkCreateOutcome(localId: String) {
        val st = mutableState.value
        val sub = st.submissions.firstOrNull { it.localId == localId && isCreateSubmission(it) && it.phase == PowerPhase.Unknown && !it.resolvedByCheck } ?: return
        val conn = connection ?: return
        if (localId in createChecks) return
        val token = Any(); createChecks[localId] = token
        val epoch = st.connectionEpoch
        val serial = st.creation?.generation
        fun update(check: IdCheck?, checking: Boolean) {
            mutableState.update { state ->
                val c = state.creation
                val old = c?.result as? CreateResult.Unknown
                if (state.connectionEpoch != epoch || c?.generation != serial || old?.localId != localId || old.vmid != sub.vmid || createChecks[localId] !== token) state
                else state.copy(creation = c.copy(result = old.copy(checking = checking, check = check ?: old.check)))
            }
        }
        update(null, true)
        scope.launch {
            try {
                val reply = createGateway.nextId(conn, sub.vmid)
                val overview = if (reply is NextIdReply.Taken) refreshCreateInventory(conn, epoch) else null
                if (mutableState.value.connectionEpoch != epoch) return@launch
                val exists = overview?.guests?.any { it.id == sub.vmid } == true
                val check = if (reply is NextIdReply.Free) IdCheck.Free(sub.vmid) else IdCheck.Taken(sub.vmid, "ID ${sub.vmid} is in use. Look at it in the Fleet before trying again.")
                mutableState.update { it.copy(submissions = it.submissions.map { s -> if (s.localId == localId && (reply is NextIdReply.Free || exists)) s.copy(resolvedByCheck = true) else s }) }
                update(check, false)
            } catch (e: CancellationException) { throw e }
            catch (e: SessionExpiredException) { if (mutableState.value.connectionEpoch == epoch) expire() }
            catch (_: Exception) { update(IdCheck.Failed(sub.vmid, "The check did not answer. The create was not sent again."), false) }
            finally { update(null, false); if (createChecks[localId] === token) createChecks.remove(localId) }
        }
    }

    private fun rememberSubmission(submission: PowerSubmission, epoch: Long, upid: String?) {
        if (upid != null) submittedUpids[submission.localId] = upid
        var stored = false
        mutableState.update { state ->
            if (state.connectionEpoch != epoch) {
                submittedUpids.remove(submission.localId)
                stored = false
                state
            } else {
                stored = true
                state.copy(submissions = listOf(submission) + state.submissions, powerNotice = null, powerBusy = false)
            }
        }
        if (stored && upid != null && submission.phase == PowerPhase.Accepted && submission.checkable) followPower(submission.localId, submission.node, upid, epoch)
        // A snapshot outcome that is already final (simulated in demo, or refused) refreshes the open guest page now.
        if (stored) snapshotInspectionRefresh(submission)
    }

    /**
     * Apply one status read to a submission, the same way for the automatic follow and the manual check. When an
     * accepted task reaches another phase the block on other actions lifts and the resource list is read once.
     */
    private fun applyPowerStatus(localId: String, epoch: Long, session: Long, status: PowerTaskStatus, manual: Boolean) {
        var finished = false
        var changed: PowerSubmission? = null
        mutableState.update { state ->
            if (state.connectionEpoch != epoch) return@update state
            finished = false
            changed = null
            state.copy(submissions = state.submissions.map { submission ->
                if (submission.localId != localId) submission
                else {
                    finished = submission.phase == PowerPhase.Accepted && status.phase != PowerPhase.Accepted
                    submission.copy(checking = if (manual) false else submission.checking, phase = status.phase, detail = status.detail)
                        .also { if (it.phase != submission.phase) changed = it }
                }
            })
        }
        if (finished) {
            if (manual) powerFollowJobs.remove(localId)?.cancel()
            catchUpAfterPower(session, epoch)
        }
        // A snapshot task that reached its end refreshes the open guest page (followed or checked by hand).
        changed?.let { snapshotInspectionRefresh(it) }
    }

    /**
     * Follow an accepted task with GET of its status: after 1 s, then each wait one second longer up to 5 s, until it
     * stops. Paused while live reads are off (the window is minimised); stopped on disconnect or session change.
     * A failed read keeps the earlier phase and tries again. Permission denial stops the follow; Check status stays.
     */
    private fun followPower(localId: String, node: String, upid: String, epoch: Long) {
        val current = connection ?: return
        if (current.kind != DIRECT_PVE_KIND) return
        val session = generation
        powerFollowJobs.remove(localId)?.cancel()
        val job = scope.launch {
            var attempt = 0
            var failures = 0
            while (isActive) {
                delay(powerFollowDelay(attempt++))
                if (!powerFollowCurrent(session, epoch, localId)) break
                if (!mutableState.value.live) {
                    // Paused: wait for live reads to come back (or the session to change), then read at once.
                    mutableState.first { it.connectionEpoch != epoch || it.live }
                    if (!powerFollowCurrent(session, epoch, localId)) break
                }
                if (mutableState.value.submissions.firstOrNull { it.localId == localId }?.checking == true) continue
                try {
                    val status = powerGateway.status(current, node, upid)
                    if (!powerFollowCurrent(session, epoch, localId)) break
                    failures = 0
                    applyPowerStatus(localId, epoch, session, status, manual = false)
                    if (status.phase != PowerPhase.Accepted) break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SessionExpiredException) {
                    if (generation == session) expire()
                    break
                } catch (e: GuestPermissionException) {
                    if (powerFollowCurrent(session, epoch, localId)) mutableState.update {
                        it.copy(powerNotice = POWER_FOLLOW_DENIED)
                    }
                    break
                } catch (e: Exception) {
                    if (!powerFollowCurrent(session, epoch, localId)) break
                    if (++failures == POWER_FOLLOW_NOTICE_FAILURES) mutableState.update {
                        it.copy(powerNotice = "Reading the task status failed. RingMASTER keeps trying; the action was not sent again. ${readableError(e)}")
                    }
                }
            }
        }
        powerFollowJobs[localId] = job
        job.invokeOnCompletion { if (powerFollowJobs[localId] === job) powerFollowJobs.remove(localId) }
    }

    private fun powerFollowCurrent(session: Long, epoch: Long, localId: String): Boolean {
        if (generation != session) return false
        val state = mutableState.value
        return state.connectionEpoch == epoch && state.submissions.any { it.localId == localId && it.phase == PowerPhase.Accepted }
    }

    /** Read the resource list once, a few seconds after a task ends, so the guest row catches up with the task. */
    private fun catchUpAfterPower(session: Long, epoch: Long) {
        powerCatchUpJob?.cancel()
        powerCatchUpJob = scope.launch {
            delay(POWER_CATCH_UP_DELAY_MS)
            if (generation != session || mutableState.value.connectionEpoch != epoch) return@launch
            mutableState.first { it.connectionEpoch != epoch || it.live }
            inventoryJob?.join()
            liveTick?.join()
            if (!liveCurrent(session, epoch)) return@launch
            val tick = launch { quietTick(session, epoch) }
            liveTick = tick
            tick.join()
        }
    }

    /** Keep what this app cannot prove about a sent action when its session ends, so the next connection can say so. */
    private fun recordLostResults() {
        val current = connection ?: return
        val state = mutableState.value
        if (state.demo || !state.connected) return
        val now = clock()
        state.submissions.filter { !it.simulated && (it.phase == PowerPhase.Accepted || it.phase == PowerPhase.Unknown) }.forEach {
            lostResults.add(LostPowerResult(newLocalId(), current.address, it.node, it.vmid, it.kind, it.name, it.action, it.phase == PowerPhase.Accepted, now))
        }
        createInFlight?.let { c -> lostResults.add(LostPowerResult(newLocalId(), current.address, c.request.node, c.request.vmid, c.request.kind.guestKind, c.request.name, PowerAction.Create, false, now)) }
        val inFlight = powerInFlight
        if (powerBusy && inFlight != null) {
            lostResults.add(LostPowerResult(newLocalId(), current.address, inFlight.node, inFlight.resource.vmid, inFlight.kind, inFlight.name, inFlight.action, false, now))
        }
    }

    private fun rememberOutcome(confirmation: PowerConfirmation, phase: PowerPhase, detail: String, checkable: Boolean) {
        val submission = PowerSubmission(
            newLocalId(), confirmation.remote, confirmation.node, confirmation.resource.vmid, confirmation.kind,
            confirmation.name, confirmation.action, phase, detail, false, checkable,
        )
        rememberSubmission(submission, confirmation.epoch, null)
    }

    /** Select a recorded member in the inspector. Clears the guest selection. Does not read every node. */
    fun selectMember(name: String) {
        val remote = currentRemote() ?: return
        val overview = mutableState.value.overview ?: return
        val member = overview.members.singleOrNull { it.remote == remote && it.name == name && pveNodeNameOrNull(it.name) != null } ?: return
        clearGuestSelection()
        nodeJob?.cancel()
        val expected = ++nodeGeneration
        val session = generation
        val epoch = mutableState.value.connectionEpoch
        val switching = mutableState.value.selectedNode != member.name
        mutableState.update {
            it.copy(
                selectedNode = member.name,
                nodeStatus = if (switching) null else it.nodeStatus?.takeIf { status -> status.node == member.name },
                nodeStatusLoading = false,
                nodeStatusError = null,
                nodeStatusStale = false,
                nodeStatusUpdatedAt = if (switching) null else it.nodeStatusUpdatedAt,
                nodeStatusNote = null,
            )
        }
        val state = mutableState.value
        pendingNodeSelection = member.name.takeIf { state.loading && nodeStatusRefusal(state.copy(loading = false), member) == null }
        beginNodeStatus(member, expected, session, epoch)
    }

    /** One explicit live status read for the selected member. Manual inventory refresh does not call this. A quiet live tick reads it on its own. */
    fun refreshNodeStatus() {
        val state = mutableState.value
        val member = selectedRecordedMember(state) ?: return
        if (member.remote != currentRemote()) return
        nodeJob?.cancel()
        val expected = ++nodeGeneration
        beginNodeStatus(member, expected, generation, state.connectionEpoch)
    }

    fun clearMember() {
        dropNodeRead()
        mutableState.update {
            it.copy(selectedNode = null, nodeStatus = null, nodeStatusLoading = false, nodeStatusError = null, nodeStatusStale = false, nodeStatusUpdatedAt = null, nodeStatusNote = null)
        }
    }

    fun select(guest: Guest) {
        val previous = mutableState.value.selected
        if (previous?.resource != guest.resource) dropInspection()
        clearMember()
        val remote = currentRemote() ?: run { clearSelection(); return }
        require(guest.remote == remote) { "This guest belongs to a different connection." }
        val currentConnection = connection
        if (!mutableState.value.demo && (currentConnection?.kind == PDM_KIND || guest.scope.isNotEmpty())) {
            require(currentConnection != null && currentConnection.kind == PDM_KIND && guest.scope.isNotEmpty()) {
                "This guest belongs to a different connection."
            }
            detailJob?.cancel()
            detailGeneration++
            mutableState.update {
                val staleRemote = it.overview?.remotes?.firstOrNull { remote -> remote.scope == guest.scope }?.stale == true
                val readAt = if (staleRemote) {
                    if (it.selected?.resource == guest.resource) it.detailsUpdatedAt else null
                } else it.updatedAt
                it.copy(selected = guest, details = pdmListDetails(guest), detailsLoading = false, detailsError = null, detailsUpdatedAt = readAt)
            }
            refreshInspectionIfGuestMoved(previous, guest)
            return
        }
        detailJob?.cancel()
        val expected = ++detailGeneration
        val session = generation
        val resource = guest.resource
        mutableState.update { it.copy(selected = guest, details = null, detailsLoading = true, detailsError = null, detailsUpdatedAt = null) }
        if (mutableState.value.demo) {
            mutableState.update { it.copy(details = demoDetails(guest), detailsLoading = false, detailsUpdatedAt = Instant.now()) }
            refreshInspectionIfGuestMoved(previous, guest)
            return
        }
        val current = connection ?: run { clearSelection(); return }
        refreshInspectionIfGuestMoved(previous, guest)
        detailJob = scope.launch {
            try {
                val details = gateway.details(current, guest)
                if (generation == session && detailGeneration == expected && mutableState.value.selected?.resource == resource) mutableState.update {
                    it.copy(details = details, detailsLoading = false, detailsError = null, detailsUpdatedAt = Instant.now())
                }
            } catch (e: CancellationException) { throw e }
            catch (e: SessionExpiredException) { if (generation == session) expire() }
            catch (e: Exception) {
                if (generation == session && detailGeneration == expected && mutableState.value.selected?.resource == resource) mutableState.update {
                    it.copy(detailsLoading = false, detailsError = readableError(e))
                }
            }
        }
    }

    /**
     * One SPICE ticket for a guest in the current direct-PVE inventory. The ticket holds a one-time password,
     * so it is returned to the caller and never kept in state.
     */
    suspend fun spiceTicket(guest: Guest): SpiceTicket {
        val state = mutableState.value
        check(!state.demo) { SPICE_DEMO }
        val current = connection ?: error("Connect to a server first.")
        require(current.kind != PDM_KIND && guest.scope.isEmpty()) { DIRECT_GUEST_REFUSAL }
        val remote = currentRemote() ?: error("Connect to a server first.")
        val live = state.overview?.guests?.let { findCurrentGuest(remote, guest, it) } ?: error("This guest is no longer in the inventory. Sync and try again.")
        val session = generation
        try { return spiceGateway.ticket(current, live) }
        catch (e: SessionExpiredException) { if (generation == session) expire(); throw e }
    }

    /** One Bluetooth scan inside a guest of the current direct-PVE inventory, through the guest agent. Demo returns sample data. */
    suspend fun guestBluetoothScan(guest: Guest, seconds: Int = BT_SCAN_SECONDS_DEFAULT): BtScan {
        val state = mutableState.value
        if (state.demo) return demoGuestBluetoothScan(guest)
        val current = connection ?: error("Connect to a server first.")
        require(current.kind != PDM_KIND && guest.scope.isEmpty()) { DIRECT_GUEST_REFUSAL }
        val remote = currentRemote() ?: error("Connect to a server first.")
        val live = state.overview?.guests?.let { findCurrentGuest(remote, guest, it) } ?: error("This guest is no longer in the inventory. Sync and try again.")
        val session = generation
        try { return guestBluetoothGateway.scan(current, live, seconds) }
        catch (e: SessionExpiredException) { if (generation == session) expire(); throw e }
    }

    /**
     * Captures one read-only console frame for a running VM and keeps it as the guest's last known image. A blank
     * frame (a sleeping or black screen) does not replace an earlier useful one. Demo and PDM do nothing.
     */
    fun captureConsoleStill(guest: Guest) {
        val state = mutableState.value
        if (state.demo || guest.kind != "VM" || guest.status != "running" || guest.template || guest.resource in state.consoleStillBusy) return
        val current = connection ?: return
        if (current.kind == PDM_KIND || guest.scope.isNotEmpty()) return
        val remote = currentRemote() ?: return
        val live = state.overview?.guests?.let { findCurrentGuest(remote, guest, it) } ?: return
        val session = generation
        val id = live.resource
        mutableState.update { it.copy(consoleStillBusy = it.consoleStillBusy + id, consoleStillErrors = it.consoleStillErrors - id) }
        consoleStillJobs[id] = scope.launch {
            try {
                val still = consoleStillGateway.capture(current, live)
                if (generation == session) mutableState.update {
                    val keep = still.blank && it.consoleStills[id]?.blank == false
                    val stills = if (keep) it.consoleStills else (it.consoleStills - id + (id to still)).entries.toList().takeLast(CONSOLE_STILL_LIMIT).associate { e -> e.key to e.value }
                    it.copy(consoleStills = stills, consoleStillBusy = it.consoleStillBusy - id)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: SessionExpiredException) { if (generation == session) expire() }
            catch (e: Exception) {
                if (generation == session) mutableState.update { it.copy(consoleStillBusy = it.consoleStillBusy - id, consoleStillErrors = it.consoleStillErrors + (id to (if (e is java.io.IOException) readableError(e) else e.message ?: "The screen could not be captured."))) }
            } finally {
                if (consoleStillJobs[id] === currentCoroutineContext()[Job]) consoleStillJobs.remove(id)
            }
        }
    }
    /** Console captures in flight, one per guest at most (the busy set). Cancelled with the session. */
    private val consoleStillJobs = mutableMapOf<ResourceId, Job>()

    /**
     * Encodes one live console frame off the UI thread. Tests replace it. The default resumes on [scope]'s dispatcher
     * before the caller reads [generation] or updates state.
     */
    internal var consoleFrameEncode: suspend (Int, Int, IntArray) -> ConsoleStill? = { width, height, argb ->
        withContext(Dispatchers.Default) { runCatching { frameStill(RfbFrame(width, height, argb)) }.getOrNull() }
    }

    /**
     * Keeps the last frame of a live in-app console as the guest's last known screen, so the console switcher shows where
     * you left it. Memory only, like captured stills. Same rules: a blank frame never replaces a useful still; at most
     * [CONSOLE_STILL_LIMIT]. Demo, PDM and a stopped guest do nothing; a frame from an older connection is dropped.
     */
    fun keepConsoleFrame(guest: Guest, width: Int, height: Int, argb: IntArray) {
        val state = mutableState.value
        if (state.demo || guest.kind != "VM" || guest.status != "running") return
        if (width <= 0 || height <= 0 || argb.size < width * height) return
        if (connection?.kind == PDM_KIND || guest.scope.isNotEmpty()) return
        // The view reuses its buffer; this copy is the one the encode reads after the caller has moved on.
        val copy = argb.copyOf(width * height)
        val session = generation
        val id = guest.resource
        val encode = consoleFrameEncode
        scope.launch {
            val still = encode(width, height, copy) ?: return@launch
            if (generation != session) return@launch
            mutableState.update {
                val keep = still.blank && it.consoleStills[id]?.blank == false
                val stills = if (keep) it.consoleStills else (it.consoleStills - id + (id to still)).entries.toList().takeLast(CONSOLE_STILL_LIMIT).associate { entry -> entry.key to entry.value }
                it.copy(consoleStills = stills, consoleStillErrors = it.consoleStillErrors - id)
            }
        }
    }

    /**
     * Opens a live console for a VM in the current direct-PVE inventory. The session holds the websocket;
     * the caller closes it. The one-time ticket never enters [WorkspaceState].
     */
    suspend fun openTerminal(target: TerminalTarget): TerminalSession {
        val state = mutableState.value
        check(!state.demo) { "Terminals need a live connection." }
        val current = connection ?: error("Connect to a server first.")
        require(current.kind != PDM_KIND) { DIRECT_GUEST_REFUSAL }
        target.base()
        if (target.kind != "node") {
            val live = state.overview?.guests?.firstOrNull { it.node == target.node && it.id.toString() == target.vmid.toString() && it.scope.isEmpty() && (it.kind == "VM") == (target.kind == "qemu") }
                ?: error("This guest is no longer in the inventory. Sync and try again.")
            require(live.status == "running" && !live.template) { if (target.kind == "lxc") "The container is stopped." else "The VM is stopped." }
            if (target.kind == "qemu") require(state.inspection?.takeIf { it.resource == live.resource && it.node == live.node }?.hardware?.value?.fields?.containsKey("serial0") == true) { "This VM has no serial0 device." }
        }
        val epoch = generation
        var acquired: TerminalSession? = null
        val opened = try { kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) { PveClient().terminalSession(current, target).also { acquired = it } } }
        catch (e: kotlinx.coroutines.CancellationException) { acquired?.close(); throw e }
        catch (e: SessionExpiredException) { if (epoch == generation) expire(); throw e }
        if (generation != epoch) { opened.close(); error("The connection changed.") }
        return opened
    }

    /**
     * A kept console for [guest], if one is waiting. Never blocks: call it during composition so the picture is there
     * in the first frame. The same refusals as [openConsole] close the kept session and return null.
     */
    fun takeKeptConsole(guest: Guest): ConsoleLease? {
        fun drop(): ConsoleLease? {
            consoleKeeper.closeGuest(guest.resource)
            return null
        }
        val state = mutableState.value
        if (state.demo) return drop()
        val current = connection ?: return drop()
        if (current.kind == PDM_KIND || guest.scope.isNotEmpty()) return drop()
        if (guest.kind != "VM") return drop()
        val remote = currentRemote() ?: return drop()
        val live = state.overview?.guests?.let { findCurrentGuest(remote, guest, it) } ?: return drop()
        if (live.status != "running" || live.template) return drop()
        return consoleKeeper.take(guest.resource)
    }

    /** Opens a new console and hands it to the keeper. Same refusals as [openConsole]. */
    suspend fun leaseConsole(guest: Guest): ConsoleLease {
        val session = openConsole(guest)
        try {
            currentCoroutineContext().ensureActive()
        } catch (e: CancellationException) {
            session.close()
            throw e
        }
        return consoleKeeper.adopt(guest.resource, session)
    }

    suspend fun openConsole(guest: Guest): VncSession {
        val state = mutableState.value
        check(!state.demo) { SPICE_DEMO }
        val current = connection ?: error("Connect to a server first.")
        require(current.kind != PDM_KIND && guest.scope.isEmpty()) { DIRECT_GUEST_REFUSAL }
        require(guest.kind == "VM") { VNC_NOT_VM }
        val remote = currentRemote() ?: error("Connect to a server first.")
        val live = state.overview?.guests?.let { findCurrentGuest(remote, guest, it) } ?: error("This guest is no longer in the inventory. Sync and try again.")
        require(live.status == "running" && !live.template) { VNC_GUEST_STOPPED }
        val session = generation
        try { return vncGateway.open(current, live) }
        catch (e: SessionExpiredException) { if (generation == session) expire(); throw e }
    }

    private var historyGeneration = 0L
    private var networkGeneration = 0L
    private var historyJob: Job? = null
    private var networkJob: Job? = null
    /** The chart in [WorkspaceState.chartHistory]: target (guest or node), timeframe and consolidation. Null when it holds no good series. */
    private var historyShown: Any? = null
    /** One RRD read, a node's when the node is given, else the guest's. A GET; tests replace it. */
    internal var historyRead: suspend (Connection, String?, Guest?, String, String) -> List<HistoryPoint> = { current, node, guest, timeframe, cf ->
        runInterruptible(Dispatchers.IO) {
            val client = PveClient()
            parseHistory(if (node != null) client.nodeHistoryRead(current, node, timeframe, cf) else client.guestRead(current, requireNotNull(guest), historySuffix(timeframe, cf)))
        }
    }

    /**
     * Reads [node]'s network configuration into [WorkspaceState.nodeNetwork]. A pending `/etc/network/interfaces.new`
     * is kept beside the rows, not shown as the live network. The same node's last rows stay on screen while a refresh runs.
     * Demo mode shows sample interfaces. An offline or unknown node is not requested.
     */
    fun loadNodeNetwork(node: String) {
        val state = mutableState.value
        if (!state.connected && !state.demo) return
        val expected = ++networkGeneration
        val session = generation
        networkJob?.cancel()
        val keep = state.nodeNetworkNode == node && state.nodeNetwork.value != null
        mutableState.update {
            it.copy(
                nodeNetwork = if (keep) state.nodeNetwork.copy(error = null) else InspectionSection(),
                nodeNetworkPending = if (keep) state.nodeNetworkPending else null,
                nodeNetworkLoading = true,
                nodeNetworkNode = node,
            )
        }
        val refusal = if (state.demo) {
            if (pveNodeNameOrNull(node) == null) NODE_TOOLS_NODE_UNKNOWN else null
        } else nodeToolsRefusal(state, node)
        if (state.demo && refusal == null) {
            mutableState.update { it.copy(nodeNetwork = InspectionSection(demoNodeNetwork), nodeNetworkPending = null, nodeNetworkLoading = false, nodeNetworkNode = node) }
            return
        }
        if (refusal != null) {
            mutableState.update { it.copy(nodeNetwork = InspectionSection(error = refusal), nodeNetworkPending = null, nodeNetworkLoading = false, nodeNetworkNode = node) }
            return
        }
        val current = connection ?: run { mutableState.update { it.copy(nodeNetworkLoading = false) }; return }
        networkJob = scope.launch {
            try {
                val report = nodeToolsGateway.network(current, node)
                if (session == generation && expected == networkGeneration && mutableState.value.nodeNetworkNode == node) {
                    mutableState.update { it.copy(nodeNetwork = InspectionSection(report.interfaces), nodeNetworkPending = report.pendingChanges, nodeNetworkLoading = false) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: SessionExpiredException) { if (session == generation) expire() }
            catch (e: GuestPermissionException) { failNodeNetwork(session, expected, node, NODE_NETWORK_DENIED) }
            catch (e: Exception) { failNodeNetwork(session, expected, node, readableError(e)) }
        }
    }

    private fun failNodeNetwork(session: Long, expected: Long, node: String, message: String) {
        if (session != generation || expected != networkGeneration || mutableState.value.nodeNetworkNode != node) return
        mutableState.update { it.copy(nodeNetwork = it.nodeNetwork.copy(error = message), nodeNetworkLoading = false) }
    }

    /**
     * Reads one RRD history into [WorkspaceState.chartHistory]. When it cannot be read now (no connection, PDM, a stale
     * inventory, nothing selected) the loading flag is cleared at once: the same chart keeps its last good series, another
     * chart shows why it is empty. A reload of the same chart keeps its series on screen until the new one arrives.
     */
    fun inspectHistory(timeframe: String, cf: String, node: String? = null) {
        historySuffix(timeframe, cf)
        val state = mutableState.value
        val guest = state.selected
        val expected = ++historyGeneration
        val session = generation
        historyJob?.cancel()
        val key = listOf(node?.let { "node" to it } ?: guest?.resource, timeframe, cf)
        if(state.demo) {
            val points = if(node != null) demoNodeHistory(timeframe,cf) else guest?.let { demoInspection(it,timeframe).history.value.orEmpty().map { p -> p.copy(values=p.values.mapValues { (k,v) -> if(cf == "MAX" && k !in listOf("maxmem","maxcpu")) v?.times(1.25) else v }) } }.orEmpty()
            historyShown = key
            mutableState.update { it.copy(chartHistory=InspectionSection(points), chartHistoryLoading=false) }; return
        }
        val current = connection
        val refusal = when {
            current == null -> HISTORY_NOT_CONNECTED
            state.kind == PDM_KIND || current.kind == PDM_KIND || (node == null && guest != null && guest.scope.isNotEmpty()) -> HISTORY_DIRECT_ONLY
            node == null && guest == null -> HISTORY_NOTHING_SELECTED
            state.stale -> HISTORY_STALE
            else -> null
        }
        val keep = historyShown == key && state.chartHistory.value != null
        if (refusal != null || current == null) {
            if (!keep) historyShown = null
            mutableState.update { if (keep) it.copy(chartHistoryLoading = false) else it.copy(chartHistory = InspectionSection(error = refusal ?: HISTORY_NOT_CONNECTED), chartHistoryLoading = false) }
            return
        }
        if (!keep) historyShown = null
        mutableState.update { it.copy(chartHistory = if (keep) it.chartHistory else InspectionSection(), chartHistoryLoading = true) }
        historyJob = scope.launch {
            val result = try {
                InspectionSection(historyRead(current, node, guest, timeframe, cf))
            } catch(e: CancellationException) { throw e }
              catch(e: SessionExpiredException) { if(session == generation) expire(); return@launch }
              catch(e: GuestPermissionException) { InspectionSection<List<HistoryPoint>>(error="Not permitted for this token") }
              catch(e: Exception) { InspectionSection<List<HistoryPoint>>(error=e.message ?: "Unavailable") }
            if(session == generation && expected == historyGeneration && (if(node != null) mutableState.value.selectedNode == node else mutableState.value.selected?.resource == guest?.resource)) {
                historyShown = key.takeIf { result.value != null }
                mutableState.update { it.copy(chartHistory=result, chartHistoryLoading=false) }
            }
        }
    }

    /**
     * One RRD read for the chart lens. Unlike [inspectHistory] it leaves [WorkspaceState.chartHistory] alone, so the page
     * behind the lens keeps its own charts. The same refusals apply (not connected, PDM or a PDM guest, a stale inventory);
     * the demo returns demo history. Exactly one of [guest] and [node] is given. Call it on the UI dispatcher.
     */
    suspend fun readHistory(guest: Guest?, node: String?, timeframe: String, cf: String): InspectionSection<List<HistoryPoint>> {
        historySuffix(timeframe, cf)
        if (node == null && guest == null) return InspectionSection(error = HISTORY_NOTHING_SELECTED)
        val state = mutableState.value
        if (state.demo) {
            val points = if (node != null) demoNodeHistory(timeframe, cf)
            else demoInspection(requireNotNull(guest), timeframe).history.value.orEmpty().map { point ->
                point.copy(values = point.values.mapValues { (key, value) -> if (cf == "MAX" && key !in listOf("maxmem", "maxcpu")) value?.times(1.25) else value })
            }
            return InspectionSection(points)
        }
        val current = connection
        val refusal = when {
            current == null -> HISTORY_NOT_CONNECTED
            state.kind == PDM_KIND || current.kind == PDM_KIND || (node == null && guest != null && guest.scope.isNotEmpty()) -> HISTORY_DIRECT_ONLY
            state.stale -> HISTORY_STALE
            else -> null
        }
        if (refusal != null || current == null) return InspectionSection(error = refusal ?: HISTORY_NOT_CONNECTED)
        val session = generation
        return try {
            // A node wins inside historyRead when both are passed. The lens passes one.
            InspectionSection(historyRead(current, node, guest, timeframe, cf))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionExpiredException) {
            if (session == generation) expire()
            InspectionSection(error = e.message ?: "The server refused this session. Connect again.")
        } catch (e: GuestPermissionException) {
            InspectionSection(error = "Not permitted for this token")
        } catch (e: Exception) {
            InspectionSection(error = e.message ?: "Unavailable")
        }
    }

    private var inspectionGeneration = 0L
    private var inspectionJob: Job? = null
    fun inspectGuest(timeframe: String = "hour") {
        require(timeframe in historyTimeframes)
        val state = mutableState.value
        val guest = state.selected ?: return
        if (state.kind == PDM_KIND || guest.scope.isNotEmpty() || state.stale || state.loading) return
        inspectionJob?.cancel()
        val expected = ++inspectionGeneration
        val session = generation
        val epoch = state.connectionEpoch
        if (state.demo) { mutableState.update { it.copy(inspection = demoInspection(guest,timeframe), inspectionLoading = false) }; return }
        val current = connection ?: return
        val recorded = state.overview?.storage.orEmpty().filter { storage ->
            storage.node == guest.node && storage.remote == guest.remote &&
                storage.content.orEmpty().split(',').any { it.trim() == "backup" }
        }
        val active = recorded.filter { backupStorageActive(it.status) }.map { it.id }.distinct()
        mutableState.update {
            val keep = it.inspection?.takeIf { report -> report.resource == guest.resource }
            it.copy(inspection = keep, inspectionLoading = true, detailsError = null)
        }
        inspectionJob = scope.launch {
            try {
                inspectionGateway.readPublishing(current, guest, active, { partial ->
                    if (generation != session || inspectionGeneration != expected) return@readPublishing
                    val selected = mutableState.value.selected
                    if (selected?.resource != guest.resource || selected.node != guest.node) return@readPublishing
                    mutableState.update { state ->
                        val live = state.selected
                        if (state.connectionEpoch != epoch || live?.resource != guest.resource || live.node != guest.node) state
                        else state.copy(inspection = mergeGuestInspection(state.inspection, partial), inspectionLoading = true)
                    }
                }, backupKnown = recorded.isNotEmpty(), timeframe = timeframe)
                if (generation == session && inspectionGeneration == expected && mutableState.value.selected?.resource == guest.resource && mutableState.value.selected?.node == guest.node)
                    mutableState.update { it.copy(inspectionLoading = false) }
            } catch(e: CancellationException) { throw e }
            catch(e: SessionExpiredException) { if(generation == session) expire() }
            catch(e: Exception) { if(generation == session && inspectionGeneration == expected) mutableState.update { it.copy(inspectionLoading = false, detailsError = readableError(e)) } }
        }
    }

    /** Same guest, new node: read again, and leave the previous sections up until the new ones arrive. */
    private fun refreshInspectionIfGuestMoved(previous: Guest?, guest: Guest) {
        if (previous?.resource != guest.resource || previous.node == guest.node) return
        if (mutableState.value.inspection?.resource != guest.resource) return
        inspectGuest()
    }

    /** A snapshot task is finished only at a terminal phase. Accepted and unknown do not refresh the page. */
    private fun snapshotInspectionRefresh(submission: PowerSubmission) {
        if (submission.action !in listOf(PowerAction.SnapshotCreate, PowerAction.SnapshotRollback, PowerAction.SnapshotDelete)) return
        if (submission.phase != PowerPhase.Succeeded && submission.phase != PowerPhase.Unsuccessful && submission.phase != PowerPhase.Simulated) return
        val guest = mutableState.value.selected ?: return
        if (submission.remote != guest.remote || submission.vmid != guest.id || submission.kind != guest.kind) return
        inspectGuest()
    }

    private fun dropInspection() {
        inspectionGeneration++; inspectionJob?.cancel()
        mutableState.update { it.copy(inspection = null, inspectionLoading = false) }
    }

    fun clearSelection() {
        dropInspection()
        detailGeneration++; detailJob?.cancel()
        mutableState.update { it.copy(selected = null, details = null, detailsLoading = false, detailsError = null, detailsUpdatedAt = null) }
    }

    private fun clearGuestSelection() {
        dropInspection()
        detailGeneration++; detailJob?.cancel()
        mutableState.update { it.copy(selected = null, details = null, detailsLoading = false, detailsError = null, detailsUpdatedAt = null) }
    }

    /**
     * A duplicate identity keeps the previous index for this origin only.
     * Guests from the new body stay in [fetched]. A different origin does not keep the old index.
     */
    private fun adoptRecordedIndex(remote: String, fetched: Overview): Overview {
        if (fetched.indexError == null && !fetched.indexStale) return fetched
        val retained = mutableState.value.overview?.takeIf { overview ->
            overview.members.all { it.remote == remote } && overview.storage.all { it.remote == remote }
        }
        val members = retained?.members ?: emptyList()
        val storage = retained?.storage ?: emptyList()
        return fetched.copy(nodes = members.size, members = members, storage = storage, indexStale = true, indexError = fetched.indexError ?: RECORDED_INDEX_ERROR)
    }

    private fun holdMember(remote: String) {
        val name = mutableState.value.selectedNode ?: return
        val member = mutableState.value.overview?.members?.singleOrNull { it.remote == remote && it.name == name }
        if (member == null) {
            clearMember()
            return
        }
        val state = mutableState.value
        val overview = state.overview
        val blocked = state.demo || state.stale || state.kind != DIRECT_PVE_KIND || overview?.indexStale == true || overview?.indexError != null || member.status != "online"
        if (!blocked) {
            mutableState.update { it.copy(nodeStatusLoading = false, nodeStatusNote = null, nodeStatus = it.nodeStatus?.takeIf { status -> status.node == member.name }) }
            if (pendingNodeSelection == member.name) {
                pendingNodeSelection = null
                beginNodeStatus(member, ++nodeGeneration, generation, state.connectionEpoch)
            }
            return
        }
        pendingNodeSelection = null
        val sample = state.demo || member.remote == DEMO_REMOTE_KEY
        mutableState.update {
            val keep = if (sample || member.status != "online") null else it.nodeStatus?.takeIf { status -> status.node == member.name }
            it.copy(
                nodeStatus = keep,
                nodeStatusLoading = false,
                nodeStatusError = if (keep == null) null else it.nodeStatusError,
                nodeStatusStale = keep != null,
                nodeStatusNote = if (keep != null) null else if (sample) LIVE_STATUS_SAMPLE else LIVE_STATUS_NOT_REQUESTED,
            )
        }
    }

    private fun beginNodeStatus(member: RecordedMember, expected: Long, session: Long, epoch: Long) {
        val refusal = nodeStatusRefusal(mutableState.value, member)
        if (refusal != null) {
            applyNodeRefusal(member.name, epoch, refusal)
            return
        }
        val current = connection ?: return
        if (current.kind != DIRECT_PVE_KIND || current.remoteKey != member.remote) {
            applyNodeRefusal(member.name, epoch, LIVE_STATUS_NOT_REQUESTED)
            return
        }
        val previous = mutableState.value.nodeStatus?.takeIf { it.node == member.name }
        mutableState.update {
            if (it.connectionEpoch != epoch || it.selectedNode != member.name) it
            else it.copy(nodeStatusLoading = true, nodeStatusError = null, nodeStatusNote = null)
        }
        nodeJob = scope.launch {
            try {
                if (!nodeCurrent(expected, session, epoch, member.name)) return@launch
                val latest = selectedRecordedMember(mutableState.value)
                if (latest == null || latest.name != member.name || latest.remote != member.remote || nodeStatusRefusal(mutableState.value, latest) != null) {
                    if (nodeCurrent(expected, session, epoch, member.name)) applyNodeRefusal(member.name, epoch, LIVE_STATUS_NOT_REQUESTED)
                    return@launch
                }
                val status = nodeStatusGateway.status(current, latest.remote, latest.name, "")
                if (!nodeCurrent(expected, session, epoch, member.name)) return@launch
                mutableState.update {
                    if (it.connectionEpoch != epoch || it.selectedNode != member.name) it
                    else it.copy(nodeStatus = status, nodeStatusLoading = false, nodeStatusError = null, nodeStatusStale = false, nodeStatusNote = null, nodeStatusUpdatedAt = Instant.now())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                if (generation == session) expire()
            } catch (e: Exception) {
                if (!nodeCurrent(expected, session, epoch, member.name)) return@launch
                mutableState.update {
                    if (it.connectionEpoch != epoch || it.selectedNode != member.name) it
                    else it.copy(nodeStatus = previous, nodeStatusLoading = false, nodeStatusStale = previous != null, nodeStatusError = readableError(e), nodeStatusNote = null)
                }
            }
        }
    }

    private fun applyNodeRefusal(name: String, epoch: Long, refusal: String) {
        mutableState.update {
            if (it.connectionEpoch != epoch || it.selectedNode != name) it
            else {
                val drop = refusal == LIVE_STATUS_SAMPLE || it.overview?.members?.singleOrNull { member -> member.name == name }?.status != "online"
                it.copy(
                    nodeStatus = if (drop) null else it.nodeStatus?.takeIf { status -> status.node == name },
                    nodeStatusLoading = false,
                    nodeStatusError = if (drop || it.nodeStatus?.node != name) null else it.nodeStatusError,
                    nodeStatusStale = !drop && it.nodeStatus?.node == name,
                    nodeStatusNote = if (!drop && it.nodeStatus?.node == name) null else refusal,
                    nodeStatusUpdatedAt = if (drop) null else it.nodeStatusUpdatedAt,
                )
            }
        }
    }

    private fun nodeCurrent(expected: Long, session: Long, epoch: Long, name: String): Boolean {
        val state = mutableState.value
        return generation == session && nodeGeneration == expected && state.connectionEpoch == epoch && state.selectedNode == name
    }

    private fun dropNodeRead() {
        pendingNodeSelection = null
        nodeGeneration++
        nodeJob?.cancel()
    }

    private fun reconcile(remote: String, guests: List<Guest>) {
        val selected = mutableState.value.selected ?: return
        val fresh = findCurrentGuest(remote, selected, guests)
        if (fresh == null) clearSelection() else select(fresh)
    }

    private fun currentRemote(): String? = when {
        mutableState.value.demo -> DEMO_REMOTE_KEY
        else -> connection?.remoteKey
    }

    private fun closeSession() {
        closeCreateSession()
        createSecretsInFlight?.close()
        createSecretsInFlight = null
        consoleKeeper.closeAll(sealOutstanding = true)
        recordLostResults()
        createInFlight = null
        createJob?.cancel(); createJob = null
        powerInFlight = null
        captureNotice = null
        powerFollowJobs.values.toList().forEach { it.cancel() }
        powerFollowJobs.clear()
        powerCatchUpJob?.cancel()
        dropInspection()
        pendingNodeSelection = null
        usedConfirmations.clear()
        generation++; detailGeneration++; taskGeneration++; nodeGeneration++
        taskLogToken++
        powerBusy = false
        submittedUpids.clear()
        inventoryJob?.cancel(); detailJob?.cancel(); taskJob?.cancel(); powerJob?.cancel(); statusJob?.cancel(); nodeJob?.cancel()
        historyJob?.cancel(); networkJob?.cancel(); historyShown = null
        consoleStillJobs.values.toList().forEach { it.cancel() }; consoleStillJobs.clear()
        liveLoop?.cancel(); liveTick?.cancel(); taskLoop?.cancel(); taskTick?.cancel(); taskLogJob?.cancel(); taskLogMoreJob?.cancel()
        netCounters = emptyMap()
        historyReadings = emptyMap()
        resetDemoMotion()
        cancelReadViews()
        connection?.close(); connection = null
        traceLoop { TraceLiveLoop() }
    }

    private fun resetDemoMotion() {
        demoRandom = Random(demoSeed)
        demoCursor = demoOverview
    }

    private fun liveCurrent(session: Long, epoch: Long): Boolean {
        val state = mutableState.value
        return generation == session && state.connectionEpoch == epoch && state.live && (state.connected || state.demo)
    }

    /** -1..1, scaled by [liveWaitMillis]. Tests replace it to make the error and resume waits exact. */
    internal var liveJitter: () -> Double = { Random.nextDouble(-1.0, 1.0) }

    /**
     * The quiet inventory loop. It waits [liveWaitMillis]: the chosen interval, or the larger error backoff, never less
     * than the interval. After a failure, and on the first wait after [resumeLive], the wait is jittered so sessions that
     * failed or resumed together spread out. A tick that comes due while another read is running looks again after
     * [LIVE_BUSY_RECHECK_MS] instead of waiting a whole interval; when a manual refresh read the inventory meanwhile, the
     * interval starts again. Every live read is a GET and may be repeated; no mutation is sent from this loop.
     */
    private fun startLiveLoop(stagger: Boolean = false) {
        liveLoop?.cancel()
        liveTick?.cancel()
        val state = mutableState.value
        if (!state.live || (!state.connected && !state.demo)) { traceLoop { TraceLiveLoop(state = if (state.connected || state.demo) "paused" else "idle") }; return }
        val session = generation
        val epoch = state.connectionEpoch
        liveLoop = scope.launch {
            var jitterNext = stagger
            while (isActive && liveCurrent(session, epoch)) {
                val backoff = mutableState.value.liveBackoffSeconds
                val interval = mutableState.value.liveIntervalSeconds
                val wait = liveWaitMillis(interval, backoff, if (backoff != null || jitterNext) liveJitter() else 0.0)
                jitterNext = false
                traceLoop { TraceLiveLoop(state = if (backoff != null) "backoff" else "waiting", nextTickAt = java.time.Instant.now().plusMillis(wait),
                    intervalMs = interval * 1000L, backoffSeconds = backoff?.toLong() ?: 0L) }
                delay(wait)
                if (!isActive || !liveCurrent(session, epoch)) break
                val readBefore = mutableState.value.updatedAt
                var waited = false
                while (liveBusy()) {
                    waited = true
                    traceLoop { it.copy(state = "waiting", nextTickAt = java.time.Instant.now().plusMillis(LIVE_BUSY_RECHECK_MS)) }
                    delay(LIVE_BUSY_RECHECK_MS)
                    if (!isActive || !liveCurrent(session, epoch)) return@launch
                }
                if (waited && mutableState.value.updatedAt != readBefore) continue
                traceLoop { it.copy(state = "refreshing", nextTickAt = null) }
                Trace.emit(TraceSource.LiveLoop, "tick", if (mutableState.value.demo) "Refresh tick · sample data" else "Refresh tick")
                val tick = launch { quietTick(session, epoch) }
                liveTick = tick
                tick.join()
            }
        }
    }

    private fun liveBusy() = inventoryJob?.isActive == true || detailJob?.isActive == true || nodeJob?.isActive == true || liveTick?.isActive == true

    private fun startTaskLoop() {
        taskLoop?.cancel()
        taskTick?.cancel()
        if (!tasksLive) return
        val state = mutableState.value
        if (!state.connected && !state.demo) return
        val session = generation
        val epoch = state.connectionEpoch
        taskLoop = scope.launch {
            while (isActive && tasksLive && generation == session && mutableState.value.connectionEpoch == epoch) {
                delay(LIVE_TASK_INTERVAL_SECONDS * 1000L)
                if (!isActive || !tasksLive || generation != session || mutableState.value.connectionEpoch != epoch) break
                if (taskJob?.isActive == true || taskTick?.isActive == true) continue
                Trace.emit(TraceSource.Task, "poll", "Poll cluster tasks")
                val tick = launch { quietTasks(session, epoch) }
                taskTick = tick
                tick.join()
            }
        }
    }

    /**
     * Inventory plus the selected guest and selected member. Does not set loading or clear the open inspector.
     * Only a failed inventory read marks the fleet stale and backs off; the side reads keep their failures on their own fields.
     */
    private suspend fun quietTick(session: Long, epoch: Long) {
        try {
            if (!liveCurrent(session, epoch)) return
            if (mutableState.value.demo) {
                demoQuietTick(session, epoch)
                return
            }
            val current = connection ?: return
            if (current.kind == PDM_KIND) pdmQuietTick(current, session, epoch) else directQuietTick(current, session, epoch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionExpiredException) {
            if (generation == session) expire()
        } catch (e: TwoFactorRequiredException) {
            if (generation == session) expire(e.message)
        } catch (e: Exception) {
            if (generation == session && mutableState.value.connectionEpoch == epoch) markLiveFailure(e)
        }
    }

    private fun demoQuietTick(session: Long, epoch: Long) {
        if (!liveCurrent(session, epoch)) return
        val readAt = clock()
        demoCursor = stepDemoOverview(demoCursor, demoRandom)
        val overview = demoCursor
        publishTick(DEMO_REMOTE_KEY, overview, readAt)
        val selected = mutableState.value.selected
        if (selected != null) {
            mutableState.update { state ->
                val guest = state.selected ?: return@update state
                if (state.connectionEpoch != epoch || guest.resource != selected.resource) state
                else state.copy(details = demoLiveDetails(guest), detailsError = null, detailsUpdatedAt = readAt)
            }
        }
        quietHoldMember(DEMO_REMOTE_KEY)
    }

    /**
     * The inventory is published, with its history step, as soon as it arrives. The selected guest's and node's status are
     * read after it, and only for the session in view: a session in the background keeps its rail live with the one
     * inventory read and catches up on the side reads when it comes into view.
     */
    private suspend fun directQuietTick(current: Connection, session: Long, epoch: Long) {
        val remote = current.remoteKey
        val order = ++inventoryOrder
        val fetched = gateway.overview(current)
        currentCoroutineContext().ensureActive()
        if (!liveCurrent(session, epoch) || order != inventoryOrder) return
        val overview = requireActiveRemote(remote, adoptRecordedIndex(remote, fetched), DIRECT_PVE_KIND)
        if (!liveCurrent(session, epoch)) return
        val readAt = clock()
        publishTick(remote, overview, readAt)
        quietHoldMember(remote)
        if (!inView) return
        quietGuestDetails(current, session, epoch, readAt)
        quietMemberStatus(current, session, epoch, readAt)
    }

    private suspend fun pdmQuietTick(current: Connection, session: Long, epoch: Long) {
        val remote = current.remoteKey
        val document = pdmGateway.read(current)
        currentCoroutineContext().ensureActive()
        if (!liveCurrent(session, epoch)) return
        val overview = mergePdmFleet(mutableState.value.overview, remote, document)
        if (!liveCurrent(session, epoch)) return
        val readAt = clock()
        publishTick(remote, overview, readAt)
        quietGuestDetails(current, session, epoch, readAt)
    }

    /**
     * One state update for a successful inventory read: the overview, the selected guest's new row, the history step and
     * the cleared error and backoff, so the fleet redraws once per read. A selected guest that left the inventory is
     * dropped after it. No suspension in between; runs on the UI dispatcher like every other state write.
     */
    private fun publishTick(remote: String, overview: Overview, readAt: Instant) {
        val step = nextFleetHistory(mutableState.value.history, netCounters, overview, readAt, historyReadings)
        netCounters = step.counters
        historyReadings = step.readings
        val selected = mutableState.value.selected
        val fresh = selected?.let { findCurrentGuest(remote, it, overview.guests) }
        mutableState.update {
            it.copy(
                overview = overview, updatedAt = readAt, loading = false,
                // Nothing recorded when the server had refreshed nothing since the last read: the charts move only with new data.
                history = if (step.fresh) step.history else it.history,
                selected = if (fresh != null && it.selected?.resource == fresh.resource) fresh else it.selected,
                stale = false, error = null, liveBackoffSeconds = null,
            )
        }
        if (selected != null && fresh == null) clearSelection()
        // Same guest on a new node (migration): read its page again, keeping the old sections up meanwhile.
        else if (selected != null && fresh != null) refreshInspectionIfGuestMoved(selected, fresh)
        retireKeptConsoles(overview)
    }

    /** Drops kept consoles whose guest stopped, became a template, or left the inventory. */
    private fun retireKeptConsoles(overview: Overview) {
        val gone = mutableState.value.keptConsoles.filter { id ->
            val guest = overview.guests.singleOrNull { it.resource == id }
            guest == null || guest.status != "running" || guest.template
        }
        gone.forEach { consoleKeeper.closeGuest(it) }
    }

    private fun markLiveFailure(error: Exception) {
        mutableState.update {
            it.copy(
                stale = it.overview != null,
                error = readableError(error),
                liveBackoffSeconds = nextLiveBackoff(it.liveBackoffSeconds),
                nodeStatusStale = it.nodeStatus != null || it.nodeStatusStale,
            )
        }
    }

    private fun quietHoldMember(remote: String) {
        val name = mutableState.value.selectedNode ?: return
        val present = mutableState.value.overview?.members?.any { it.remote == remote && it.name == name } == true
        if (!present) clearMember()
    }

    /** A failed read keeps the last details and shows the error on them; it does not touch the fleet. A lost login still ends the session. */
    private suspend fun quietGuestDetails(current: Connection, session: Long, epoch: Long, readAt: Instant) {
        val guest = mutableState.value.selected ?: return
        if (guest.remote != current.remoteKey && !mutableState.value.demo) return
        if (current.kind == PDM_KIND || guest.scope.isNotEmpty()) {
            if (!liveCurrent(session, epoch)) return
            mutableState.update { state ->
                val selected = state.selected ?: return@update state
                if (state.connectionEpoch != epoch || selected.resource != guest.resource) state
                else state.copy(details = pdmListDetails(selected), detailsError = null, detailsUpdatedAt = readAt)
            }
            return
        }
        if (detailJob?.isActive == true) return
        val expectedDetail = detailGeneration
        val resource = guest.resource
        fun stillCurrent(state: WorkspaceState) =
            liveCurrent(session, epoch) && state.connectionEpoch == epoch && state.selected?.resource == resource && detailGeneration == expectedDetail
        val details = try {
            gateway.details(current, guest)
        } catch (e: CancellationException) { throw e }
        catch (e: SessionExpiredException) { throw e }
        catch (e: Exception) {
            if (stillCurrent(mutableState.value)) mutableState.update { state -> if (!stillCurrent(state)) state else state.copy(detailsError = readableError(e)) }
            return
        }
        currentCoroutineContext().ensureActive()
        if (!stillCurrent(mutableState.value)) return
        mutableState.update { state ->
            if (!stillCurrent(state)) state
            else state.copy(details = details, detailsError = null, detailsUpdatedAt = readAt)
        }
    }

    /** Like [quietGuestDetails]: a failure stays on the node status fields, the last status kept and marked stale. */
    private suspend fun quietMemberStatus(current: Connection, session: Long, epoch: Long, readAt: Instant) {
        if (current.kind != DIRECT_PVE_KIND) return
        val state = mutableState.value
        val member = selectedRecordedMember(state) ?: return
        if (member.remote != current.remoteKey) return
        if (nodeStatusRefusal(state.copy(stale = false, loading = false), member) != null) return
        if (nodeJob?.isActive == true) return
        val expected = nodeGeneration
        val name = member.name
        fun stillCurrent(state: WorkspaceState) =
            liveCurrent(session, epoch) && state.connectionEpoch == epoch && state.selectedNode == name && nodeGeneration == expected
        val status = try {
            nodeStatusGateway.status(current, member.remote, name, "")
        } catch (e: CancellationException) { throw e }
        catch (e: SessionExpiredException) { throw e }
        catch (e: Exception) {
            if (stillCurrent(mutableState.value)) mutableState.update {
                if (!stillCurrent(it)) it
                else it.copy(nodeStatusError = readableError(e), nodeStatusStale = it.nodeStatus?.node == name, nodeStatusNote = null)
            }
            return
        }
        currentCoroutineContext().ensureActive()
        if (!stillCurrent(mutableState.value)) return
        mutableState.update {
            if (!stillCurrent(it)) it
            else it.copy(nodeStatus = status, nodeStatusError = null, nodeStatusStale = false, nodeStatusNote = null, nodeStatusUpdatedAt = readAt)
        }
    }

    private suspend fun quietTasks(session: Long, epoch: Long) {
        try {
            if (generation != session || mutableState.value.connectionEpoch != epoch) return
            if (mutableState.value.demo) {
                mutableState.update {
                    if (it.connectionEpoch != epoch) it
                    else it.copy(tasks = demoTasks, tasksStale = false, tasksError = null, tasksUpdatedAt = clock())
                }
                return
            }
            val current = connection ?: return
            if (current.kind == PDM_KIND) return
            if (taskJob?.isActive == true) return
            val fetched = taskGateway.tasks(current)
            currentCoroutineContext().ensureActive()
            if (generation != session || mutableState.value.connectionEpoch != epoch) return
            val tasks = requireActiveTasks(current.remoteKey, fetched)
            mutableState.update {
                if (it.connectionEpoch != epoch) it
                else it.copy(tasks = tasks, tasksStale = false, tasksError = null, tasksUpdatedAt = clock())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionExpiredException) {
            if (generation == session) expire()
        } catch (e: Exception) {
            if (generation == session && mutableState.value.connectionEpoch == epoch) {
                mutableState.update { it.copy(tasksStale = it.tasks != null, tasksError = readableError(e)) }
            }
        }
    }

    private fun recordHistory(overview: Overview, at: Instant) {
        val step = nextFleetHistory(mutableState.value.history, netCounters, overview, at, historyReadings)
        netCounters = step.counters
        historyReadings = step.readings
        // Nothing recorded when the server had refreshed nothing since the last read: the charts move only with new data.
        if (step.fresh) mutableState.update { it.copy(history = step.history) }
    }

    // ---- Read-only views (0.21.4) ----------------------------------------------------------------------------------------
    // Permissions, node services, system log, package updates and storage contents. Every one is a GET on a direct connection.
    // Demo returns labelled sample data and contacts no server. A result is applied only while the session, the connection epoch
    // and that view's own generation still match, so a late reply never lands on another node or storage. HTTP 403 shows
    // "not permitted" in the view and keeps the session; HTTP 401 ends it; a failed refresh keeps the last data, marked stale.

    private var permissionsGeneration = 0L
    private var permissionsJob: Job? = null
    private var servicesGeneration = 0L
    private var servicesJob: Job? = null
    private var updatesGeneration = 0L
    private var updatesJob: Job? = null
    private var syslogGeneration = 0L
    private var syslogJob: Job? = null
    private var syslogMoreJob: Job? = null
    private var syslogLoop: Job? = null
    private var syslogVisible = false
    private var syslogFollowing = true
    private var syslogCatchUp = false
    private var syslogFailures = 0
    private var lastSyslogDurationMs = 0L
    private var syslogSawSlow = false
    private var demoSyslogTotal = DEMO_SYSLOG_START_TOTAL
    private var storageGeneration = 0L
    private var storageJob: Job? = null

    private fun cancelReadViews() {
        permissionsGeneration++; servicesGeneration++; updatesGeneration++; syslogGeneration++; storageGeneration++
        permissionsJob?.cancel(); servicesJob?.cancel(); updatesJob?.cancel(); storageJob?.cancel()
        storageWorkbench.cancel()
        storageActionWorkbench.cancel()
        storageSendOwner = null
        syslogJob?.cancel(); syslogMoreJob?.cancel(); syslogLoop?.cancel()
        syslogFollowing = true; syslogCatchUp = false; syslogFailures = 0; lastSyslogDurationMs = 0; syslogSawSlow = false; demoSyslogTotal = DEMO_SYSLOG_START_TOTAL
    }

    private fun readCurrent(session: Long, epoch: Long) = generation == session && mutableState.value.connectionEpoch == epoch

    /** One read for one view. The result, a "not permitted" or a failure is applied only while [wanted] still holds. */
    private fun <T> launchRead(
        session: Long, epoch: Long, wanted: () -> Boolean, deniedMessage: String,
        read: suspend () -> T, publish: (T) -> Unit, fail: (String, Boolean) -> Unit,
    ): Job = scope.launch {
        fun live() = readCurrent(session, epoch) && wanted()
        try {
            val value = read()
            if (live()) publish(value)
        } catch (e: CancellationException) { throw e }
        catch (e: SessionExpiredException) { if (generation == session) expire() }
        catch (e: GuestPermissionException) { if (live()) fail(deniedMessage, true) }
        catch (e: Exception) { if (live()) fail(readableError(e), false) }
    }

    /** Null when a node read may be sent for [node]. Otherwise the sentence to show, and nothing is requested. */
    private fun nodeToolsRefusal(state: WorkspaceState, node: String): String? {
        val current = connection ?: return NODE_TOOLS_DIRECT_ONLY
        if (state.kind == PDM_KIND || current.kind != DIRECT_PVE_KIND) return NODE_TOOLS_DIRECT_ONLY
        if (pveNodeNameOrNull(node) == null) return NODE_TOOLS_NODE_UNKNOWN
        val member = state.overview?.members?.singleOrNull { it.remote == current.remoteKey && it.name == node } ?: return NODE_TOOLS_NODE_UNKNOWN
        return if (member.status == "online") null else NODE_TOOLS_NODE_NOT_ONLINE
    }

    /** Read the signed-in identity's privileges (`GET /access/permissions`). A second call while one runs is ignored. */
    fun loadPermissions() {
        val state = mutableState.value
        if (!state.connected && !state.demo) return
        if (permissionsJob?.isActive == true) return
        val expected = ++permissionsGeneration
        if (state.demo) {
            mutableState.update { it.copy(permissions = ReadSection.loaded(PermissionsView(demoPermissions, PermissionIdentity.Sample), clock(), sample = true)) }
            return
        }
        val current = connection ?: return
        if (state.kind == PDM_KIND || current.kind != DIRECT_PVE_KIND) {
            mutableState.update { it.copy(permissions = ReadSection<PermissionsView>().failed(PERMISSIONS_DIRECT_ONLY)) }
            return
        }
        val identity = if (current.passwordLogin) PermissionIdentity.User else PermissionIdentity.Token
        mutableState.update { it.copy(permissions = it.permissions.started()) }
        permissionsJob = launchRead(generation, state.connectionEpoch, { permissionsGeneration == expected }, PERMISSIONS_DENIED,
            { permissionsGateway.read(current) },
            { dump -> mutableState.update { it.copy(permissions = ReadSection.loaded(PermissionsView(dump, identity), clock())) } },
            { message, denied -> mutableState.update { it.copy(permissions = it.permissions.failed(message, denied)) } })
    }

    /** Services of one recorded, online node (`GET /nodes/{node}/services`). */
    fun loadNodeServices(node: String) {
        val state = mutableState.value
        if (!state.connected && !state.demo) return
        servicesJob?.cancel()
        val expected = ++servicesGeneration
        val previous = state.nodeServices.takeIf { it.value?.node == node } ?: ReadSection()
        if (state.demo) {
            val refusal = if (pveNodeNameOrNull(node) == null) NODE_TOOLS_NODE_UNKNOWN else null
            mutableState.update { it.copy(nodeServices = if (refusal != null) ReadSection<NodeServicesView>().failed(refusal)
                else ReadSection.loaded(NodeServicesView(node, demoNodeServices), clock(), sample = true)) }
            return
        }
        val refusal = nodeToolsRefusal(state, node)
        if (refusal != null) {
            mutableState.update { it.copy(nodeServices = ReadSection<NodeServicesView>().failed(refusal)) }
            return
        }
        val current = connection ?: return
        mutableState.update { it.copy(nodeServices = previous.started()) }
        servicesJob = launchRead(generation, state.connectionEpoch, { servicesGeneration == expected }, NODE_SERVICES_DENIED,
            { nodeToolsGateway.services(current, node) },
            { rows -> mutableState.update { it.copy(nodeServices = ReadSection.loaded(NodeServicesView(node, rows), clock())) } },
            { message, denied -> mutableState.update { it.copy(nodeServices = it.nodeServices.failed(message, denied)) } })
    }

    /** The node's cached package update list (`GET /nodes/{node}/apt/update`). The POST that refreshes the lists is never sent. */
    fun loadNodeUpdates(node: String) {
        val state = mutableState.value
        if (!state.connected && !state.demo) return
        updatesJob?.cancel()
        val expected = ++updatesGeneration
        val previous = state.nodeUpdates.takeIf { it.value?.node == node } ?: ReadSection()
        if (state.demo) {
            val refusal = if (pveNodeNameOrNull(node) == null) NODE_TOOLS_NODE_UNKNOWN else null
            mutableState.update { it.copy(nodeUpdates = if (refusal != null) ReadSection<NodeUpdatesView>().failed(refusal)
                else ReadSection.loaded(NodeUpdatesView(node, demoPackageUpdates), clock(), sample = true)) }
            return
        }
        val refusal = nodeToolsRefusal(state, node)
        if (refusal != null) {
            mutableState.update { it.copy(nodeUpdates = ReadSection<NodeUpdatesView>().failed(refusal)) }
            return
        }
        val current = connection ?: return
        mutableState.update { it.copy(nodeUpdates = previous.started()) }
        updatesJob = launchRead(generation, state.connectionEpoch, { updatesGeneration == expected }, NODE_UPDATES_DENIED,
            { nodeToolsGateway.updates(current, node) },
            { rows -> mutableState.update { it.copy(nodeUpdates = ReadSection.loaded(NodeUpdatesView(node, rows), clock())) } },
            { message, denied -> mutableState.update { it.copy(nodeUpdates = it.nodeUpdates.failed(message, denied)) } })
    }

    /**
     * Content of one storage that the recorded inventory lists (`GET /nodes/{node}/storage/{storage}/content`). The node connects
     * that one storage to read it, so this runs on a click and never in a loop. The storage index is not called.
     */
    fun selectStorage(node: String, storage: String, content: String? = null) {
        storageActionWorkbench.selectionChanged()
        val state = mutableState.value
        if (!state.connected && !state.demo) return
        val remote = currentRemote() ?: return
        storageJob?.cancel()
        val expected = ++storageGeneration
        if (!state.demo && (state.kind == PDM_KIND || connection?.kind != DIRECT_PVE_KIND)) {
            mutableState.update { it.copy(storageSelection = null, storageContent = ReadSection<StorageContentView>().failed(STORAGE_CONTENT_DIRECT_ONLY)) }
            return
        }
        if (content != null && content !in STORAGE_CONTENT_TYPES) {
            mutableState.update { it.copy(storageSelection = null, storageContent = ReadSection<StorageContentView>().failed(STORAGE_CONTENT_TYPE_REFUSED)) }
            return
        }
        val recorded = state.overview?.storage?.singleOrNull { it.remote == remote && it.node == node && it.id == storage }
        if (recorded == null) {
            mutableState.update { it.copy(storageSelection = null, storageContent = ReadSection<StorageContentView>().failed(STORAGE_NOT_RECORDED)) }
            return
        }
        val key = StorageKey(remote, node, storage)
        val previous = state.storageContent.takeIf { section -> section.value.let { it != null && it.key == key && it.filter == content } } ?: ReadSection()
        if (state.demo) {
            val rows = (storageActionWorkbench.demoVolumes(key) ?: demoStorageContent(recorded)).filter { content == null || it.content == content }
            mutableState.update { it.copy(storageSelection = key, storageContent = ReadSection.loaded(StorageContentView(key, content, rows), clock(), sample = true)) }
            return
        }
        val current = connection ?: return
        val member = state.overview.members.singleOrNull { it.remote == remote && it.name == node }
        val refusal = when {
            state.kind == PDM_KIND || current.kind != DIRECT_PVE_KIND -> STORAGE_CONTENT_DIRECT_ONLY
            member?.status != "online" -> STORAGE_NODE_NOT_ONLINE
            else -> null
        }
        if (refusal != null) {
            mutableState.update { it.copy(storageSelection = key, storageContent = ReadSection<StorageContentView>().failed(refusal)) }
            return
        }
        mutableState.update { it.copy(storageSelection = key, storageContent = previous.started()) }
        storageJob = launchRead(generation, state.connectionEpoch, { storageGeneration == expected }, STORAGE_CONTENT_DENIED,
            { storageContentGateway.read(current, node, storage, content) },
            { rows -> mutableState.update { it.copy(storageContent = ReadSection.loaded(StorageContentView(key, content, rows), clock())) } },
            { message, denied -> mutableState.update { it.copy(storageContent = it.storageContent.failed(message, denied)) } })
    }

    /** Read the selected storage again. */
    fun refreshStorageContent() {
        val state = mutableState.value
        val key = state.storageSelection ?: return
        selectStorage(key.node, key.storage, state.storageContent.value?.filter)
    }

    fun clearStorageSelection() {
        storageGeneration++
        storageJob?.cancel()
        mutableState.update { it.copy(storageSelection = null, storageContent = ReadSection()) }
        storageWorkbench.clearHistory()
    }

    /**
     * Read the storage configuration (`GET /storage`, allowlisted fields only) into [StorageViewState.config]. It activates
     * nothing. Demo gives sample rows; PDM is refused. A call while one runs is ignored.
     */
    fun loadStorageConfig() = storageWorkbench.loadConfig()

    /**
     * Read one recorded storage's usage history (`GET /nodes/{node}/storage/{storage}/rrddata`) into
     * [StorageViewState.history]. It activates nothing. Refused before HTTP for PDM, a storage the inventory does not list, a
     * stale inventory and a node that is not online.
     */
    fun loadStorageHistory(node: String, storage: String, timeframe: String = "hour", cf: String = "AVERAGE") =
        storageWorkbench.loadHistory(node, storage, timeframe, cf)

    /** One storage history read for the chart lens, same shape as [readHistory]. It leaves the page's state alone. */
    suspend fun readStorageHistory(node: String, storage: String, timeframe: String, cf: String): InspectionSection<List<HistoryPoint>> =
        storageWorkbench.read(node, storage, timeframe, cf)

    // ---- System log ----

    private fun syslogCurrent(session: Long, epoch: Long, expected: Long) = readCurrent(session, epoch) && syslogGeneration == expected

    /** The newest [SYSLOG_PAGE] lines: one read for the total, one for the tail. Each call is timed for the follow pace. */
    private suspend fun readSyslogTail(current: Connection, node: String, service: String?): SyslogPage {
        val head = timedSyslog { nodeToolsGateway.syslog(current, node, 0, 1, service) }
        if (head.total <= 0) return SyslogPage(emptyList(), 0)
        return timedSyslog { nodeToolsGateway.syslog(current, node, syslogTailStart(head.total), SYSLOG_PAGE, service) }
    }

    /** Record how long one syslog call took. A call of about 10 seconds or more stops Follow after this read. */
    private suspend fun <T> timedSyslog(block: suspend () -> T): T {
        val started = clock()
        try {
            return block()
        } finally {
            val elapsed = java.time.Duration.between(started, clock()).toMillis().coerceAtLeast(0)
            lastSyslogDurationMs = elapsed
            if (syslogCallTooSlow(elapsed)) syslogSawSlow = true
        }
    }

    private fun failSyslog(session: Long, epoch: Long, expected: Long, message: String, denied: Boolean) {
        if (!syslogCurrent(session, epoch, expected)) return
        mutableState.update { it.copy(nodeSyslog = it.nodeSyslog.failed(message, denied)) }
    }

    /**
     * Open (or reopen) the log of one recorded, online node, optionally for one unit. Reads the newest lines and, while the panel is
     * visible and Follow is on, keeps reading the tail every [SYSLOG_FOLLOW_MS] ms. Reopening the same node and unit keeps what is on screen.
     */
    fun openNodeSyslog(node: String, service: String? = null) {
        val state = mutableState.value
        if (!state.connected && !state.demo) return
        syslogJob?.cancel(); syslogMoreJob?.cancel(); syslogLoop?.cancel()
        val expected = ++syslogGeneration
        syslogSawSlow = false
        val unit = syslogServiceOrNull(service)
        if (pveNodeNameOrNull(node) == null || (service != null && unit == null)) {
            mutableState.update { it.copy(nodeSyslog = ReadSection<SyslogView>().failed(if (unit == null && service != null) NODE_SYSLOG_REFUSED else NODE_TOOLS_NODE_UNKNOWN)) }
            return
        }
        val previous = state.nodeSyslog.takeIf { section -> section.value.let { it != null && it.node == node && it.service == unit } } ?: ReadSection()
        if (state.demo) {
            val page = demoSyslogPage(node, syslogTailStart(demoSyslogTotal), SYSLOG_PAGE, demoSyslogTotal)
            mutableState.update { it.copy(nodeSyslog = ReadSection.loaded(SyslogView(node, unit, page.lines, page.total, syslogFollowing), clock(), sample = true)) }
            syslogCatchUp = false
            restartSyslogFollow()
            return
        }
        val refusal = nodeToolsRefusal(state, node)
        if (refusal != null) {
            mutableState.update { it.copy(nodeSyslog = ReadSection<SyslogView>().failed(refusal)) }
            return
        }
        val current = connection ?: return
        val session = generation
        val epoch = state.connectionEpoch
        mutableState.update { it.copy(nodeSyslog = previous.started()) }
        syslogJob = scope.launch {
            try {
                val page = readSyslogTail(current, node, unit)
                if (!syslogCurrent(session, epoch, expected)) return@launch
                val follow = syslogFollowing && !syslogSawSlow
                if (!follow) syslogFollowing = false
                val notice = if (syslogSawSlow) NODE_SYSLOG_FOLLOW_PAUSED else null
                mutableState.update { it.copy(nodeSyslog = ReadSection.loaded(SyslogView(node, unit, page.lines, page.total, follow, notice), clock())) }
                syslogCatchUp = false
                syslogFailures = 0
                if (follow) restartSyslogFollow()
            } catch (e: CancellationException) { throw e }
            catch (e: SessionExpiredException) { if (generation == session) expire() }
            catch (e: GuestPermissionException) { failSyslog(session, epoch, expected, NODE_SYSLOG_DENIED, true) }
            catch (e: Exception) {
                if (!syslogCurrent(session, epoch, expected)) return@launch
                if (syslogSawSlow) pauseSyslogFollow(NODE_SYSLOG_FOLLOW_PAUSED) else failSyslog(session, epoch, expected, readableError(e), false)
            }
        }
    }

    /** Tell the workspace whether the log panel is on screen. Following reads only while it is. Showing it again reads at once. */
    fun setSyslogVisible(visible: Boolean) {
        if (syslogVisible == visible) return
        syslogVisible = visible
        if (visible) syslogCatchUp = true
        restartSyslogFollow()
    }

    /** Follow on: read the tail while visible, waiting longer after a slow or failed read. Follow off: no reads until it is turned on or Refresh is used. */
    fun setSyslogFollowing(following: Boolean) {
        if (syslogFollowing == following) return
        syslogFollowing = following
        mutableState.update { state ->
            val section = state.nodeSyslog
            section.value?.let { state.copy(nodeSyslog = section.copy(value = it.copy(following = following))) } ?: state
        }
        if (following) {
            syslogFailures = 0
            syslogCatchUp = true
        }
        restartSyslogFollow()
    }

    /** One manual tail read. It appends what is new; it does not reload the log. */
    fun refreshNodeSyslog() {
        val state = mutableState.value
        val view = state.nodeSyslog.value ?: return
        if (syslogMoreJob?.isActive == true) return
        val expected = syslogGeneration
        val session = generation
        val epoch = state.connectionEpoch
        syslogMoreJob = scope.launch { pollSyslog(view.node, view.service, session, epoch, expected) }
    }

    /** Read the page of lines before the oldest one on screen. Follow turns off first, so the view stays on the older lines. */
    fun loadEarlierSyslog() {
        val state = mutableState.value
        val view = state.nodeSyslog.value ?: return
        val (start, limit) = syslogEarlierRequest(view.lines) ?: return
        if (syslogFollowing) setSyslogFollowing(false)
        if (syslogMoreJob?.isActive == true) return
        val expected = syslogGeneration
        val session = generation
        val epoch = state.connectionEpoch
        if (state.demo) {
            val older = applySyslogEarlier(view.lines, demoSyslogPage(view.node, start, limit, demoSyslogTotal)) ?: return
            mutableState.update { s ->
                val section = s.nodeSyslog
                val held = section.value ?: return@update s
                s.copy(nodeSyslog = section.copy(value = held.copy(lines = older, following = false)))
            }
            return
        }
        val current = connection ?: return
        syslogMoreJob = scope.launch {
            try {
                val page = nodeToolsGateway.syslog(current, view.node, start, limit, view.service)
                if (!syslogCurrent(session, epoch, expected)) return@launch
                val latest = mutableState.value.nodeSyslog.value ?: return@launch
                if (latest.node != view.node || latest.service != view.service) return@launch
                if (applySyslogEarlier(latest.lines, page) == null) {
                    // The node's numbering moved while we were away from the top; load the tail again.
                    openNodeSyslog(latest.node, latest.service)
                    return@launch
                }
                mutableState.update { s ->
                    val section = s.nodeSyslog
                    val held = section.value ?: return@update s
                    s.copy(nodeSyslog = section.copy(value = held.copy(lines = applySyslogEarlier(held.lines, page) ?: held.lines)))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: SessionExpiredException) { if (generation == session) expire() }
            catch (e: GuestPermissionException) { failSyslog(session, epoch, expected, NODE_SYSLOG_DENIED, true) }
            catch (e: Exception) { failSyslog(session, epoch, expected, readableError(e), false) }
        }
    }

    /** Stop reading and drop the log from state. */
    fun closeNodeSyslog() {
        syslogGeneration++
        syslogJob?.cancel(); syslogMoreJob?.cancel(); syslogLoop?.cancel()
        syslogFailures = 0
        lastSyslogDurationMs = 0
        syslogSawSlow = false
        mutableState.update { it.copy(nodeSyslog = ReadSection()) }
    }

    private fun restartSyslogFollow() {
        syslogLoop?.cancel()
        syslogLoop = null
        val state = mutableState.value
        val view = state.nodeSyslog.value ?: return
        if (!syslogVisible || !syslogFollowing || (!state.connected && !state.demo)) return
        val session = generation
        val epoch = state.connectionEpoch
        val expected = syslogGeneration
        var immediate = syslogCatchUp
        syslogCatchUp = false
        syslogLoop = scope.launch {
            while (isActive && syslogCurrent(session, epoch, expected) && syslogVisible && syslogFollowing) {
                if (!immediate) delay(syslogFollowDelayMs(lastSyslogDurationMs, syslogFailures))
                immediate = false
                if (!isActive || !syslogCurrent(session, epoch, expected) || !syslogVisible || !syslogFollowing) break
                pollSyslog(view.node, view.service, session, epoch, expected)
            }
        }
    }

    /** Stop Follow and say why. The lines already read stay. */
    private fun pauseSyslogFollow(notice: String) {
        syslogFollowing = false
        syslogFailures = 0
        mutableState.update { state ->
            val section = state.nodeSyslog
            val held = section.value
            if (held == null) return@update state.copy(nodeSyslog = section.failed(notice))
            state.copy(nodeSyslog = section.copy(value = held.copy(following = false, notice = notice), error = null, denied = false, stale = false, loading = false))
        }
    }

    private fun stopSyslogFollow(message: String, denied: Boolean) {
        syslogFollowing = false
        mutableState.update { state ->
            val section = state.nodeSyslog
            val held = section.value ?: return@update state
            state.copy(nodeSyslog = section.copy(value = held.copy(following = false)).failed(message, denied))
        }
    }

    /** The follow loop and a manual Refresh never read the tail at the same time, so one page is never applied twice. */
    private val syslogPollLock = Mutex()

    private suspend fun pollSyslog(node: String, service: String?, session: Long, epoch: Long, expected: Long) =
        syslogPollLock.withLock { pollSyslogLocked(node, service, session, epoch, expected) }

    /**
     * One tail read: the last few held lines are read again and must come back unchanged, otherwise the node's numbering
     * moved and the tail is loaded again (see [checkSyslogFollow]). Reads up to a few pages to catch up. A call of about
     * 10 seconds stops Follow. A failed read waits longer the next time.
     */
    private suspend fun pollSyslogLocked(node: String, service: String?, session: Long, epoch: Long, expected: Long) {
        try {
            if (!syslogCurrent(session, epoch, expected)) return
            if (mutableState.value.demo) {
                demoSyslogTotal += 2
                syslogFailures = 0
                mutableState.update { s ->
                    val section = s.nodeSyslog
                    val held = section.value?.takeIf { it.node == node && it.service == service } ?: return@update s
                    val last = held.lines.lastOrNull()?.n ?: 0
                    val fresh = demoSyslogPage(node, last, demoSyslogTotal - last, demoSyslogTotal).lines
                    s.copy(nodeSyslog = ReadSection.loaded(held.copy(lines = appendSyslog(held.lines, fresh), total = demoSyslogTotal, notice = null), clock(), sample = true))
                }
                return
            }
            val current = connection ?: return
            nodeToolsRefusal(mutableState.value, node)?.let { stopSyslogFollow(it, false); return }
            syslogSawSlow = false
            var pages = 0
            while (pages++ < SYSLOG_CATCH_UP_PAGES) {
                val held = mutableState.value.nodeSyslog.value?.takeIf { it.node == node && it.service == service } ?: return
                val request = syslogFollowRequest(held.lines)
                if (held.lines.isEmpty() || request == null) {
                    reloadSyslogTail(current, node, service, session, epoch, expected, null)
                    if (syslogSawSlow) pauseSyslogFollow(NODE_SYSLOG_FOLLOW_PAUSED) else syslogFailures = 0
                    return
                }
                val page = timedSyslog { nodeToolsGateway.syslog(current, node, request.first, request.second, service) }
                if (!syslogCurrent(session, epoch, expected)) return
                when (val result = checkSyslogFollow(held.lines, page)) {
                    is SyslogFollowResult.NothingNew -> {
                        mutableState.update { s ->
                            val section = s.nodeSyslog
                            val now = section.value?.takeIf { it.node == node && it.service == service } ?: return@update s
                            s.copy(nodeSyslog = ReadSection.loaded(now.copy(total = result.total, notice = null), clock(), section.sample))
                        }
                        if (syslogSawSlow) pauseSyslogFollow(NODE_SYSLOG_FOLLOW_PAUSED) else syslogFailures = 0
                        return
                    }
                    is SyslogFollowResult.Appended -> {
                        mutableState.update { s ->
                            val section = s.nodeSyslog
                            val now = section.value?.takeIf { it.node == node && it.service == service } ?: return@update s
                            s.copy(nodeSyslog = ReadSection.loaded(now.copy(lines = appendSyslog(now.lines, result.lines), total = result.total, notice = null), clock(), section.sample))
                        }
                        if (syslogSawSlow) { pauseSyslogFollow(NODE_SYSLOG_FOLLOW_PAUSED); return }
                        if (!result.more) { syslogFailures = 0; return }
                    }
                    SyslogFollowResult.Resync -> {
                        reloadSyslogTail(current, node, service, session, epoch, expected, NODE_SYSLOG_RESYNCED)
                        if (syslogSawSlow) pauseSyslogFollow(NODE_SYSLOG_FOLLOW_PAUSED) else syslogFailures = 0
                        return
                    }
                }
            }
            if (syslogSawSlow) pauseSyslogFollow(NODE_SYSLOG_FOLLOW_PAUSED) else syslogFailures = 0
        } catch (e: CancellationException) { throw e }
        catch (e: SessionExpiredException) { if (generation == session) expire() }
        catch (e: GuestPermissionException) { if (syslogCurrent(session, epoch, expected)) stopSyslogFollow(NODE_SYSLOG_DENIED, true) }
        catch (e: Exception) {
            if (!syslogCurrent(session, epoch, expected)) return
            if (syslogSawSlow) pauseSyslogFollow(NODE_SYSLOG_FOLLOW_PAUSED)
            else { syslogFailures++; failSyslog(session, epoch, expected, readableError(e), false) }
        }
    }

    private suspend fun reloadSyslogTail(current: Connection, node: String, service: String?, session: Long, epoch: Long, expected: Long, notice: String?) {
        val page = readSyslogTail(current, node, service)
        if (!syslogCurrent(session, epoch, expected)) return
        mutableState.update { s ->
            val section = s.nodeSyslog
            val held = section.value?.takeIf { it.node == node && it.service == service } ?: return@update s
            s.copy(nodeSyslog = ReadSection.loaded(held.copy(lines = page.lines, total = page.total, notice = notice), clock(), section.sample))
        }
    }

    /**
     * The session cannot go on: the server refused it, or a new login needs a two-factor code the app no longer has
     * (the code is used once). Close it like Disconnect and show [message], so the desk's code field is usable again.
     */
    private fun expire(message: String? = SessionExpiredException().message) {
        closeSession()
        mutableState.value = WorkspaceState(error = message)
    }
    override fun close() { mutationLeases.unregister(foregroundView); disconnect() }
}

fun readableError(error: Exception): String = when (error) {
    is SSLException -> "The server certificate is untrusted, expired, or does not match the supplied fingerprint."
    is IOException -> "The server could not be reached. Check the network and try again."
    is IllegalArgumentException -> "The server response or connection settings were invalid."
    is IllegalStateException -> error.message ?: "The request could not be completed."
    else -> "The request could not be completed. Try again."
}

fun demoDetails(guest: Guest) = GuestDetails(guest, guest.status, 4, if (guest.status == "running") 0.12 else 0.0,
    if (guest.status == "running") 2L * 1024 * 1024 * 1024 else 0, 8L * 1024 * 1024 * 1024,
    if (guest.kind == "Container") 3L * 1024 * 1024 * 1024 else null, 64L * 1024 * 1024 * 1024,
    if (guest.status == "running") 86400 else 0, 10L * 1024 * 1024, 4L * 1024 * 1024)
