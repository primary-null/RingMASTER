package local.ringleader.core

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/**
 * The storage view's reads that activate nothing: the storage configuration and one storage's usage history. Direct Proxmox VE
 * only. The content listing stays [StorageContentGateway]; the write actions of a later step join this interface.
 */
interface StorageGateway {
    suspend fun config(connection: Connection): List<StorageConfigRow>
    suspend fun history(connection: Connection, node: String, storage: String, timeframe: String, cf: String): List<HistoryPoint>
}

class LiveStorageGateway(private val client: PveClient = PveClient()) : StorageGateway {
    override suspend fun config(connection: Connection): List<StorageConfigRow> {
        requireDirectRequest(connection)
        return runInterruptible(Dispatchers.IO) { client.storageConfig(connection) }
    }

    override suspend fun history(connection: Connection, node: String, storage: String, timeframe: String, cf: String): List<HistoryPoint> {
        requireDirectRequest(connection)
        return runInterruptible(Dispatchers.IO) { client.storageHistory(connection, node, storage, timeframe, cf) }
    }
}

/** Which history a request asked for. */
data class StorageHistoryRequest(val key: StorageKey, val timeframe: String, val cf: String)

/** One storage's usage over time: [points] carry `used` and `total` ([STORAGE_HISTORY_KEYS]). */
data class StorageHistoryView(val request: StorageHistoryRequest, val points: List<HistoryPoint>)

/**
 * The storage view's state, one field of [WorkspaceState]. Memory only, dropped with the session like the other read views.
 *
 * - [config]: `GET /storage` through the allowlist. Its failure is not shown as an error on the page; it only means no
 *   disabled cards and no retention facts. A sample in demo.
 * - [history]: the history of the storage the page shows, with the request it answers. [historyRequest] is the latest
 *   request, so the page can tell a chart that is loading for this storage from one left over from another.
 */
data class StorageViewState(
    val config: ReadSection<List<StorageConfigRow>> = ReadSection(),
    val historyRequest: StorageHistoryRequest? = null,
    val history: ReadSection<StorageHistoryView> = ReadSection(),
) {
    /** The history points when they answer [request]; null otherwise. */
    fun historyFor(request: StorageHistoryRequest): List<HistoryPoint>? = history.value?.takeIf { it.request == request }?.points
}

/**
 * Session logic for the storage view's reads, owned by [Workspace] and reached through its thin methods. It shares the
 * workspace's thread confinement: every call and every coroutine runs on [scope]'s one dispatcher. A result lands only while
 * the connection epoch it started under is current and no newer request of the same kind was made.
 */
internal class StorageWorkbench(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<WorkspaceState>,
    private val gateway: StorageGateway,
    private val clock: () -> Instant,
    private val connection: () -> Connection?,
    /** Ends the session after HTTP 401 when [Long] is still the current connection epoch. */
    private val expire: (Long) -> Unit,
) {
    private var configGeneration = 0L
    private var configJob: Job? = null
    private var historyGeneration = 0L
    private var historyJob: Job? = null

    /** The session ended: drop every read in flight. The state itself is replaced by the workspace. */
    fun cancel() {
        configGeneration++; historyGeneration++
        configJob?.cancel(); historyJob?.cancel()
    }

    /** No storage is shown any more: drop its history and any read of it. */
    fun clearHistory() {
        historyGeneration++
        historyJob?.cancel()
        state.update { it.copy(storageView = it.storageView.copy(historyRequest = null, history = ReadSection())) }
    }

    private fun direct(): Connection? = connection()?.takeIf { it.kind == DIRECT_PVE_KIND && state.value.kind != PDM_KIND }

    private fun updateView(change: (StorageViewState) -> StorageViewState) = state.update { it.copy(storageView = change(it.storageView)) }

    /** Read the storage configuration. A second call while one runs is ignored; a later call reads it again. */
    fun loadConfig() {
        val now = state.value
        if (!now.connected && !now.demo) return
        if (now.storageView.config.loading) return
        if (now.demo) {
            updateView { it.copy(config = ReadSection.loaded(demoStorageConfig, clock(), sample = true)) }
            return
        }
        val current = direct()
        if (current == null) {
            updateView { it.copy(config = ReadSection<List<StorageConfigRow>>().failed(STORAGE_VIEW_DIRECT_ONLY)) }
            return
        }
        val expected = ++configGeneration
        val epoch = now.connectionEpoch
        configJob?.cancel()
        updateView { it.copy(config = it.config.started()) }
        configJob = scope.launch {
            fun live() = configGeneration == expected && state.value.connectionEpoch == epoch
            try {
                val rows = gateway.config(current)
                if (live()) updateView { it.copy(config = ReadSection.loaded(rows, clock())) }
            } catch (e: CancellationException) { throw e }
            catch (e: SessionExpiredException) { expire(epoch) }
            catch (e: GuestPermissionException) { if (live()) updateView { it.copy(config = it.config.failed(STORAGE_CONFIG_DENIED, denied = true)) } }
            catch (e: Exception) { if (live()) updateView { it.copy(config = it.config.failed(readableError(e))) } }
        }
    }

    /** Null when a history read may be sent for [key]; otherwise the sentence to show, and nothing is requested. */
    private fun historyRefusal(now: WorkspaceState, key: StorageKey): String? {
        if (now.kind == PDM_KIND || direct() == null) return HISTORY_DIRECT_ONLY
        val overview = now.overview ?: return STORAGE_NOT_RECORDED
        if (overview.storage.none { it.remote == key.remote && it.node == key.node && it.id == key.storage }) return STORAGE_NOT_RECORDED
        if (now.stale || overview.indexStale) return HISTORY_STALE
        val member = overview.members.singleOrNull { it.remote == key.remote && it.name == key.node }
        if (member?.status != "online") return STORAGE_NODE_NOT_ONLINE
        return null
    }

    private fun recordedDemo(now: WorkspaceState, node: String, storage: String): RecordedStorage? =
        now.overview?.storage?.singleOrNull { it.remote == DEMO_REMOTE_KEY && it.node == node && it.id == storage }

    private fun remote(now: WorkspaceState): String? = if (now.demo) DEMO_REMOTE_KEY else connection()?.remoteKey

    /**
     * Read one recorded storage's history into [StorageViewState.history]. The same storage's last series stays on screen,
     * marked stale, while a new read runs or when it fails. [timeframe] and [cf] must be ones [historySuffix] accepts.
     */
    fun loadHistory(node: String, storage: String, timeframe: String, cf: String) {
        historySuffix(timeframe, cf)
        val now = state.value
        if (!now.connected && !now.demo) return
        val remote = remote(now) ?: return
        val request = StorageHistoryRequest(StorageKey(remote, node, storage), timeframe, cf)
        historyJob?.cancel()
        val expected = ++historyGeneration
        val previous = now.storageView.history.takeIf { it.value?.request?.key == request.key } ?: ReadSection()
        if (now.demo) {
            val row = recordedDemo(now, node, storage)
            val section = if (row == null) ReadSection<StorageHistoryView>().failed(STORAGE_NOT_RECORDED)
            else ReadSection.loaded(StorageHistoryView(request, demoStorageHistory(row, timeframe, cf, clock())), clock(), sample = true)
            updateView { it.copy(historyRequest = request, history = section) }
            return
        }
        val refusal = historyRefusal(now, request.key)
        val current = direct()
        if (refusal != null || current == null) {
            updateView { it.copy(historyRequest = request, history = previous.failed(refusal ?: HISTORY_NOT_CONNECTED)) }
            return
        }
        val epoch = now.connectionEpoch
        updateView { it.copy(historyRequest = request, history = previous.started()) }
        historyJob = scope.launch {
            fun live() = historyGeneration == expected && state.value.connectionEpoch == epoch
            try {
                val points = gateway.history(current, node, storage, timeframe, cf)
                if (live()) updateView { it.copy(history = ReadSection.loaded(StorageHistoryView(request, points), clock())) }
            } catch (e: CancellationException) { throw e }
            catch (e: SessionExpiredException) { expire(epoch) }
            catch (e: GuestPermissionException) { if (live()) updateView { it.copy(history = it.history.failed(STORAGE_HISTORY_DENIED, denied = true)) } }
            catch (e: Exception) { if (live()) updateView { it.copy(history = it.history.failed(readableError(e))) } }
        }
    }

    /**
     * One history read for the chart lens. It leaves [StorageViewState.history] alone, so the page behind the lens keeps its
     * chart. The same refusals apply; the demo returns demo history.
     */
    suspend fun read(node: String, storage: String, timeframe: String, cf: String): InspectionSection<List<HistoryPoint>> {
        historySuffix(timeframe, cf)
        val now = state.value
        if (now.demo) {
            val row = recordedDemo(now, node, storage) ?: return InspectionSection(error = STORAGE_NOT_RECORDED)
            return InspectionSection(demoStorageHistory(row, timeframe, cf, clock()))
        }
        if (!now.connected) return InspectionSection(error = HISTORY_NOT_CONNECTED)
        val remote = remote(now) ?: return InspectionSection(error = HISTORY_NOT_CONNECTED)
        val refusal = historyRefusal(now, StorageKey(remote, node, storage))
        val current = direct()
        if (refusal != null || current == null) return InspectionSection(error = refusal ?: HISTORY_NOT_CONNECTED)
        val epoch = now.connectionEpoch
        return try {
            InspectionSection(gateway.history(current, node, storage, timeframe, cf))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionExpiredException) {
            expire(epoch)
            InspectionSection(error = e.message ?: "The server refused this session. Connect again.")
        } catch (e: GuestPermissionException) {
            InspectionSection(error = STORAGE_HISTORY_DENIED)
        } catch (e: Exception) {
            InspectionSection(error = readableError(e))
        }
    }
}
