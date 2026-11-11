package local.ringleader.core

import java.io.IOException
import java.time.Instant
import javax.net.ssl.SSLException
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        tickets.clear()
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
    val kind: String = DIRECT_PVE_KIND,
    val selectedNode: String? = null, val nodeStatus: NodeLiveStatus? = null, val nodeStatusLoading: Boolean = false,
    val nodeStatusError: String? = null, val nodeStatusStale: Boolean = false, val nodeStatusUpdatedAt: Instant? = null,
    val nodeStatusNote: String? = null,
    val live: Boolean = false,
    val liveIntervalSeconds: Int = LIVE_INTERVAL_DEFAULT,
    val liveBackoffSeconds: Int? = null,
    val chartHistory: InspectionSection<List<HistoryPoint>> = InspectionSection(), val chartHistoryLoading: Boolean = false,
    val nodeNetwork: InspectionSection<List<NodeInterface>> = InspectionSection(), val nodeNetworkLoading: Boolean = false, val nodeNetworkNode: String? = null,
    val history: FleetHistory = FleetHistory(),
    /** Last known console frames, memory only (never written to disk), newest last, at most [CONSOLE_STILL_LIMIT]. */
    val consoleStills: Map<ResourceId, ConsoleStill> = emptyMap(),
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

/** Called on the UI dispatcher. Generation checks discard late results even if I/O ignores cancellation. */
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
) : AutoCloseable {
    private val mutableState = MutableStateFlow(WorkspaceState())
    val state = mutableState.asStateFlow()
    private var connection: Connection? = null
    private var generation = 0L
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
            live = true, liveIntervalSeconds = LIVE_INTERVAL_DEFAULT,
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
            reconcile(DEMO_REMOTE_KEY, demoOverview.guests)
            holdMember(DEMO_REMOTE_KEY)
            return
        }
        val current = connection ?: return
        if (inventoryJob?.isActive == true || liveTick?.isActive == true) return
        val expected = generation
        val remote = current.remoteKey
        dropNodeRead()
        mutableState.update { it.copy(loading = true, error = null, nodeStatusLoading = false) }
        inventoryJob = scope.launch {
            try {
                val overview = if (current.kind == PDM_KIND) {
                    val document = pdmGateway.read(current)
                    if (generation != expected) return@launch
                    mergePdmFleet(mutableState.value.overview, remote, document)
                } else {
                    val fetched = gateway.overview(current)
                    if (generation != expected) return@launch
                    requireActiveRemote(remote, adoptRecordedIndex(remote, fetched), DIRECT_PVE_KIND)
                }
                if (generation != expected) return@launch
                val selected = mutableState.value.selected
                val readAt = clock()
                mutableState.update { it.copy(overview = overview, loading = false, stale = false, error = null, updatedAt = readAt, nodeStatusLoading = false, liveBackoffSeconds = null) }
                recordHistory(overview, readAt)
                if (selected != null) {
                    val fresh = findCurrentGuest(remote, selected, overview.guests)
                    if (fresh == null) clearSelection() else select(fresh)
                }
                if (current.kind != PDM_KIND) holdMember(remote)
            } catch (e: CancellationException) { throw e }
            catch (e: SessionExpiredException) { if (generation == expected) expire() }
            catch (e: Exception) {
                if (generation == expected) mutableState.update {
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
        taskLogJob = scope.launch {
            try {
                val status = taskLogGateway.status(current, task.node, task.upid)
                val page = taskLogGateway.page(current, task.node, task.upid, 0, TASK_LOG_PAGE_LIMIT)
                if (!logCurrent(token, session, epoch, task)) return@launch
                publishLog(token, session, epoch, task, status, page)
                var currentStatus = status
                while (isActive && taskLogKeepsPolling(currentStatus)) {
                    delay(TASK_LOG_POLL_MS)
                    if (!logCurrent(token, session, epoch, task)) return@launch
                    currentStatus = taskLogGateway.status(current, task.node, task.upid)
                    if (!logCurrent(token, session, epoch, task)) return@launch
                    val start = mutableState.value.taskLog?.lines?.lastOrNull()?.n ?: 0
                    val next = taskLogGateway.page(current, task.node, task.upid, start, TASK_LOG_PAGE_LIMIT)
                    if (!logCurrent(token, session, epoch, task)) return@launch
                    publishLog(token, session, epoch, task, currentStatus, next)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: SessionExpiredException) { if (generation == session) expire() }
            catch (e: GuestPermissionException) { failLog(token, session, epoch, task, TASK_LOG_AUDIT_DENIED) }
            catch (e: Exception) { failLog(token, session, epoch, task, readableError(e)) }
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
            catch (e: Exception) { failLog(token, session, epoch, task, readableError(e)) }
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
            val lines = mergeTaskLogLines(log.lines, page.lines)
            val total = maxOf(log.total, page.total, lines.lastOrNull()?.n ?: 0)
            state.copy(taskLog = log.copy(lines = lines, total = total, status = status ?: log.status, error = null, loading = false))
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
        return if (powerRefusal(bound, current) == null) bound else null
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
        powerBusy = true
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
                    mutableState.update { if (it.connectionEpoch == confirmation.epoch) it.copy(powerBusy = false) else it }
                }
            }
        }
        return true
    }

    /** One manual status read for a task this session submitted. This does not poll and does not send the action again. */
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
                mutableState.update { state ->
                    if (state.connectionEpoch != epoch) state
                    else state.copy(submissions = state.submissions.map { submission ->
                        if (submission.localId != localId) submission
                        else submission.copy(checking = false, phase = status.phase, detail = status.detail)
                    })
                }
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

    private fun rememberSubmission(submission: PowerSubmission, epoch: Long, upid: String?) {
        if (upid != null) submittedUpids[submission.localId] = upid
        mutableState.update { state ->
            if (state.connectionEpoch != epoch) {
                submittedUpids.remove(submission.localId)
                state
            } else state.copy(submissions = listOf(submission) + state.submissions, powerNotice = null, powerBusy = false)
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
        dropInspection()
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
            return
        }
        detailJob?.cancel()
        val expected = ++detailGeneration
        val session = generation
        val resource = guest.resource
        mutableState.update { it.copy(selected = guest, details = null, detailsLoading = true, detailsError = null, detailsUpdatedAt = null) }
        if (mutableState.value.demo) {
            mutableState.update { it.copy(details = demoDetails(guest), detailsLoading = false, detailsUpdatedAt = Instant.now()) }
            return
        }
        val current = connection ?: run { clearSelection(); return }
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
        scope.launch {
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

    /** Reads [node]'s network configuration into [WorkspaceState.nodeNetwork]; demo mode shows sample interfaces. */
    fun loadNodeNetwork(node: String) {
        val state = mutableState.value
        val expected = ++networkGeneration
        val session = generation
        mutableState.update { it.copy(nodeNetwork = InspectionSection(), nodeNetworkLoading = true, nodeNetworkNode = node) }
        if (state.demo) { mutableState.update { it.copy(nodeNetwork = InspectionSection(demoNodeNetwork), nodeNetworkLoading = false) }; return }
        val current = connection ?: run { mutableState.update { it.copy(nodeNetworkLoading = false) }; return }
        if (state.kind == PDM_KIND) { mutableState.update { it.copy(nodeNetworkLoading = false) }; return }
        scope.launch {
            val result = try {
                InspectionSection(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PveClient().nodeNetwork(current, node) })
            } catch (e: CancellationException) { throw e }
              catch (e: SessionExpiredException) { if (session == generation) expire(); return@launch }
              catch (e: GuestPermissionException) { InspectionSection<List<NodeInterface>>(error = "Not permitted for this token") }
              catch (e: Exception) { InspectionSection<List<NodeInterface>>(error = e.message ?: "Unavailable") }
            if (session == generation && expected == networkGeneration && mutableState.value.nodeNetworkNode == node)
                mutableState.update { it.copy(nodeNetwork = result, nodeNetworkLoading = false) }
        }
    }

    fun inspectHistory(timeframe: String, cf: String, node: String? = null) {
        historySuffix(timeframe, cf)
        val state = mutableState.value
        val guest = state.selected
        val expected = ++historyGeneration
        val session = generation
        mutableState.update { it.copy(chartHistory = InspectionSection(), chartHistoryLoading = true) }
        if(state.demo) {
            val points = if(node != null) demoNodeHistory(timeframe,cf) else guest?.let { demoInspection(it,timeframe).history.value.orEmpty().map { p -> p.copy(values=p.values.mapValues { (k,v) -> if(cf == "MAX" && k !in listOf("maxmem","maxcpu")) v?.times(1.25) else v }) } }.orEmpty()
            mutableState.update { it.copy(chartHistory=InspectionSection(points), chartHistoryLoading=false) }; return
        }
        val current = connection ?: return
        if(state.kind == PDM_KIND || state.stale || (node == null && (guest == null || guest.scope.isNotEmpty()))) return
        scope.launch {
            val result = try {
                val points = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val client = PveClient()
                    parseHistory(if(node != null) client.nodeHistoryRead(current,node,timeframe,cf) else client.guestRead(current,requireNotNull(guest),historySuffix(timeframe,cf)))
                }
                InspectionSection(points)
            } catch(e: CancellationException) { throw e }
              catch(e: SessionExpiredException) { if(session == generation) expire(); return@launch }
              catch(e: GuestPermissionException) { InspectionSection<List<HistoryPoint>>(error="Not permitted for this token") }
              catch(e: Exception) { InspectionSection<List<HistoryPoint>>(error=e.message ?: "Unavailable") }
            if(session == generation && expected == historyGeneration && (if(node != null) mutableState.value.selectedNode == node else mutableState.value.selected?.resource == guest?.resource))
                mutableState.update { it.copy(chartHistory=result, chartHistoryLoading=false) }
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
        if (state.demo) { mutableState.update { it.copy(inspection = demoInspection(guest,timeframe), inspectionLoading = false) }; return }
        val current = connection ?: return
        val storages = state.overview?.storage.orEmpty().filter { it.node == guest.node && it.remote == guest.remote && "backup" in it.content.orEmpty().split(',') }.map { it.id }.distinct()
        mutableState.update { it.copy(inspection = null, inspectionLoading = true) }
        inspectionJob = scope.launch {
            try {
                val result = inspectionGateway.read(current,guest,storages,timeframe)
                if(generation == session && inspectionGeneration == expected && mutableState.value.selected?.resource == guest.resource && mutableState.value.selected?.node == guest.node)
                    mutableState.update { it.copy(inspection = result, inspectionLoading = false) }
            } catch(e: CancellationException) { throw e }
            catch(e: SessionExpiredException) { if(generation == session) expire() }
            catch(e: Exception) { if(generation == session && inspectionGeneration == expected) mutableState.update { it.copy(inspectionLoading = false, detailsError = readableError(e)) } }
        }
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
        dropInspection()
        pendingNodeSelection = null
        usedConfirmations.clear()
        generation++; detailGeneration++; taskGeneration++; nodeGeneration++
        taskLogToken++
        powerBusy = false
        submittedUpids.clear()
        inventoryJob?.cancel(); detailJob?.cancel(); taskJob?.cancel(); powerJob?.cancel(); statusJob?.cancel(); nodeJob?.cancel()
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

    private fun startLiveLoop() {
        liveLoop?.cancel()
        liveTick?.cancel()
        val state = mutableState.value
        if (!state.live || (!state.connected && !state.demo)) { traceLoop { TraceLiveLoop(state = if (state.connected || state.demo) "paused" else "idle") }; return }
        val session = generation
        val epoch = state.connectionEpoch
        liveLoop = scope.launch {
            while (isActive && liveCurrent(session, epoch)) {
                val wait = (mutableState.value.liveBackoffSeconds ?: mutableState.value.liveIntervalSeconds).coerceIn(LIVE_INTERVAL_MIN, LIVE_INTERVAL_MAX)
                val backoff = mutableState.value.liveBackoffSeconds
                traceLoop { TraceLiveLoop(state = if (backoff != null) "backoff" else "waiting", nextTickAt = java.time.Instant.now().plusSeconds(wait.toLong()),
                    intervalMs = mutableState.value.liveIntervalSeconds * 1000L, backoffSeconds = backoff?.toLong() ?: 0L) }
                delay(wait * 1000L)
                if (!isActive || !liveCurrent(session, epoch)) break
                if (inventoryJob?.isActive == true || detailJob?.isActive == true || nodeJob?.isActive == true || liveTick?.isActive == true) continue
                traceLoop { it.copy(state = "refreshing", nextTickAt = null) }
                Trace.emit(TraceSource.LiveLoop, "tick", if (mutableState.value.demo) "Refresh tick · sample data" else "Refresh tick")
                val tick = launch { quietTick(session, epoch) }
                liveTick = tick
                tick.join()
            }
        }
    }

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

    /** Inventory plus the selected guest and selected member. Does not set loading or clear the open inspector. */
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
        } catch (e: Exception) {
            if (generation == session && mutableState.value.connectionEpoch == epoch) markLiveFailure(e)
        }
    }

    private fun demoQuietTick(session: Long, epoch: Long) {
        if (!liveCurrent(session, epoch)) return
        val readAt = clock()
        demoCursor = stepDemoOverview(demoCursor, demoRandom)
        val overview = demoCursor
        publishOverview(overview, readAt)
        quietReconcile(DEMO_REMOTE_KEY, overview.guests)
        recordHistory(overview, readAt)
        val selected = mutableState.value.selected
        if (selected != null) {
            mutableState.update { state ->
                val guest = state.selected ?: return@update state
                if (state.connectionEpoch != epoch || guest.resource != selected.resource) state
                else state.copy(details = demoLiveDetails(guest), detailsError = null, detailsUpdatedAt = readAt)
            }
        }
        quietHoldMember(DEMO_REMOTE_KEY)
        if (liveCurrent(session, epoch)) markLiveSuccess(readAt)
    }

    private suspend fun directQuietTick(current: Connection, session: Long, epoch: Long) {
        val remote = current.remoteKey
        val fetched = gateway.overview(current)
        currentCoroutineContext().ensureActive()
        if (!liveCurrent(session, epoch)) return
        val overview = requireActiveRemote(remote, adoptRecordedIndex(remote, fetched), DIRECT_PVE_KIND)
        if (!liveCurrent(session, epoch)) return
        val readAt = clock()
        publishOverview(overview, readAt)
        quietReconcile(remote, overview.guests)
        recordHistory(overview, readAt)
        quietGuestDetails(current, session, epoch, readAt)
        quietHoldMember(remote)
        quietMemberStatus(current, session, epoch, readAt)
        if (!liveCurrent(session, epoch)) return
        markLiveSuccess(readAt)
    }

    private suspend fun pdmQuietTick(current: Connection, session: Long, epoch: Long) {
        val remote = current.remoteKey
        val document = pdmGateway.read(current)
        currentCoroutineContext().ensureActive()
        if (!liveCurrent(session, epoch)) return
        val overview = mergePdmFleet(mutableState.value.overview, remote, document)
        if (!liveCurrent(session, epoch)) return
        val readAt = clock()
        publishOverview(overview, readAt)
        quietReconcile(remote, overview.guests)
        recordHistory(overview, readAt)
        quietGuestDetails(current, session, epoch, readAt)
        if (!liveCurrent(session, epoch)) return
        markLiveSuccess(readAt)
    }

    private fun publishOverview(overview: Overview, readAt: Instant) {
        mutableState.update { it.copy(overview = overview, updatedAt = readAt) }
    }

    private fun markLiveSuccess(readAt: Instant) {
        mutableState.update { it.copy(stale = false, error = null, liveBackoffSeconds = null, updatedAt = readAt) }
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

    /** Keep the same guest identity. Replace the row, and drop the selection only when that guest is gone. */
    private fun quietReconcile(remote: String, guests: List<Guest>) {
        val selected = mutableState.value.selected ?: return
        val fresh = findCurrentGuest(remote, selected, guests)
        if (fresh == null) clearSelection()
        else if (fresh != selected) mutableState.update { it.copy(selected = fresh) }
    }

    private fun quietHoldMember(remote: String) {
        val name = mutableState.value.selectedNode ?: return
        val present = mutableState.value.overview?.members?.any { it.remote == remote && it.name == name } == true
        if (!present) clearMember()
    }

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
        val details = gateway.details(current, guest)
        currentCoroutineContext().ensureActive()
        if (!liveCurrent(session, epoch) || detailGeneration != expectedDetail) return
        if (mutableState.value.selected?.resource != resource) return
        mutableState.update { state ->
            if (state.connectionEpoch != epoch || state.selected?.resource != resource || detailGeneration != expectedDetail) state
            else state.copy(details = details, detailsError = null, detailsUpdatedAt = readAt)
        }
    }

    private suspend fun quietMemberStatus(current: Connection, session: Long, epoch: Long, readAt: Instant) {
        if (current.kind != DIRECT_PVE_KIND) return
        val state = mutableState.value
        val member = selectedRecordedMember(state) ?: return
        if (member.remote != current.remoteKey) return
        if (nodeStatusRefusal(state.copy(stale = false, loading = false), member) != null) return
        if (nodeJob?.isActive == true) return
        val expected = nodeGeneration
        val name = member.name
        val status = nodeStatusGateway.status(current, member.remote, name, "")
        currentCoroutineContext().ensureActive()
        if (!liveCurrent(session, epoch) || nodeGeneration != expected || mutableState.value.selectedNode != name) return
        mutableState.update {
            if (it.connectionEpoch != epoch || it.selectedNode != name || nodeGeneration != expected) it
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
    private var demoSyslogTotal = DEMO_SYSLOG_START_TOTAL
    private var storageGeneration = 0L
    private var storageJob: Job? = null

    private fun cancelReadViews() {
        permissionsGeneration++; servicesGeneration++; updatesGeneration++; syslogGeneration++; storageGeneration++
        permissionsJob?.cancel(); servicesJob?.cancel(); updatesJob?.cancel(); storageJob?.cancel()
        syslogJob?.cancel(); syslogMoreJob?.cancel(); syslogLoop?.cancel()
        syslogFollowing = true; syslogCatchUp = false; demoSyslogTotal = DEMO_SYSLOG_START_TOTAL
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
            val rows = demoStorageContent(recorded).filter { content == null || it.content == content }
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
    }

    // ---- System log ----

    private fun syslogCurrent(session: Long, epoch: Long, expected: Long) = readCurrent(session, epoch) && syslogGeneration == expected

    /** The newest [SYSLOG_PAGE] lines: one read for the total, one for the tail. */
    private suspend fun readSyslogTail(current: Connection, node: String, service: String?): SyslogPage {
        val head = nodeToolsGateway.syslog(current, node, 0, 1, service)
        if (head.total <= 0) return SyslogPage(emptyList(), 0)
        return nodeToolsGateway.syslog(current, node, syslogTailStart(head.total), SYSLOG_PAGE, service)
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
                mutableState.update { it.copy(nodeSyslog = ReadSection.loaded(SyslogView(node, unit, page.lines, page.total, syslogFollowing), clock())) }
                syslogCatchUp = false
                restartSyslogFollow()
            } catch (e: CancellationException) { throw e }
            catch (e: SessionExpiredException) { if (generation == session) expire() }
            catch (e: GuestPermissionException) { failSyslog(session, epoch, expected, NODE_SYSLOG_DENIED, true) }
            catch (e: Exception) { failSyslog(session, epoch, expected, readableError(e), false) }
        }
    }

    /** Tell the workspace whether the log panel is on screen. Following reads only while it is. Showing it again reads at once. */
    fun setSyslogVisible(visible: Boolean) {
        if (syslogVisible == visible) return
        syslogVisible = visible
        if (visible) syslogCatchUp = true
        restartSyslogFollow()
    }

    /** Follow on: read the tail every [SYSLOG_FOLLOW_MS] ms while visible. Follow off: no reads until it is turned on or Refresh is used. */
    fun setSyslogFollowing(following: Boolean) {
        syslogFollowing = following
        mutableState.update { state ->
            val section = state.nodeSyslog
            section.value?.let { state.copy(nodeSyslog = section.copy(value = it.copy(following = following))) } ?: state
        }
        if (following) syslogCatchUp = true
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

    /** Read the page of lines before the oldest one on screen. */
    fun loadEarlierSyslog() {
        val state = mutableState.value
        val view = state.nodeSyslog.value ?: return
        val (start, limit) = syslogEarlierRequest(view.lines) ?: return
        if (syslogMoreJob?.isActive == true) return
        val expected = syslogGeneration
        val session = generation
        val epoch = state.connectionEpoch
        if (state.demo) {
            val older = applySyslogEarlier(view.lines, demoSyslogPage(view.node, start, limit, demoSyslogTotal)) ?: return
            mutableState.update { it.copy(nodeSyslog = it.nodeSyslog.copy(value = view.copy(lines = older))) }
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
                if (!immediate) delay(SYSLOG_FOLLOW_MS)
                immediate = false
                if (!isActive || !syslogCurrent(session, epoch, expected) || !syslogVisible || !syslogFollowing) break
                pollSyslog(view.node, view.service, session, epoch, expected)
            }
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
     * One tail read: the line already held is read again with what follows it and must come back unchanged, otherwise the node's
     * numbering moved and the tail is loaded again (see [checkSyslogFollow]). Reads up to a few pages to catch up.
     */
    private suspend fun pollSyslogLocked(node: String, service: String?, session: Long, epoch: Long, expected: Long) {
        try {
            if (!syslogCurrent(session, epoch, expected)) return
            if (mutableState.value.demo) {
                demoSyslogTotal += 2
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
            var pages = 0
            while (pages++ < SYSLOG_CATCH_UP_PAGES) {
                val held = mutableState.value.nodeSyslog.value?.takeIf { it.node == node && it.service == service } ?: return
                val last = held.lines.lastOrNull()
                val request = syslogFollowRequest(held.lines)
                if (last == null || request == null) {
                    reloadSyslogTail(current, node, service, session, epoch, expected, null)
                    return
                }
                val page = nodeToolsGateway.syslog(current, node, request.first, request.second, service)
                if (!syslogCurrent(session, epoch, expected)) return
                when (val result = checkSyslogFollow(last, page)) {
                    is SyslogFollowResult.NothingNew -> {
                        mutableState.update { s ->
                            val section = s.nodeSyslog
                            val now = section.value?.takeIf { it.node == node && it.service == service } ?: return@update s
                            s.copy(nodeSyslog = ReadSection.loaded(now.copy(total = result.total, notice = null), clock(), section.sample))
                        }
                        return
                    }
                    is SyslogFollowResult.Appended -> {
                        mutableState.update { s ->
                            val section = s.nodeSyslog
                            val now = section.value?.takeIf { it.node == node && it.service == service } ?: return@update s
                            s.copy(nodeSyslog = ReadSection.loaded(now.copy(lines = appendSyslog(now.lines, result.lines), total = result.total, notice = null), clock(), section.sample))
                        }
                        if (!result.more) return
                    }
                    SyslogFollowResult.Resync -> {
                        reloadSyslogTail(current, node, service, session, epoch, expected, NODE_SYSLOG_RESYNCED)
                        return
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: SessionExpiredException) { if (generation == session) expire() }
        catch (e: GuestPermissionException) { if (syslogCurrent(session, epoch, expected)) stopSyslogFollow(NODE_SYSLOG_DENIED, true) }
        catch (e: Exception) { failSyslog(session, epoch, expected, readableError(e), false) }
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

    private fun expire() {
        closeSession()
        mutableState.value = WorkspaceState(error = SessionExpiredException().message)
    }
    override fun close() = disconnect()
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
