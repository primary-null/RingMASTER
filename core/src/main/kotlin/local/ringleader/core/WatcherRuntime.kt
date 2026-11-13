package local.ringleader.core

import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Feeds the [Watcher] from snapshots the app already produces: every open session's [WorkspaceState] and the
 * scheduler's [SchedulerState]. It starts no poller, opens no connection and reads no secret: when a session stops
 * refreshing (window hidden, live reads paused, disconnected), the Watcher simply stops learning and says so.
 *
 * Lifecycle is the caller's: construct once per process, [start], attach the flows, and [close] on quit.
 */
class WatcherRuntime(
    val watcher: Watcher,
    val center: NotificationCenter,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val tickEvery: Duration = Duration.ofSeconds(15),
) : AutoCloseable {
    private val jobs = mutableListOf<Job>()
    private var started: Job? = null
    private val ready = CompletableDeferred<Unit>()

    /** Loads the notification history first (so nothing can post twice), then the watch list, then keeps time. Idempotent. */
    fun start(): Job = synchronized(jobs) {
        started ?: scope.launch(dispatcher) {
            center.load()
            watcher.load()
            ready.complete(Unit)
            while (isActive) { delay(tickEvery.toMillis()); watcher.tick() }
        }.also { started = it; jobs += it }
    }

    /** Follows the open sessions: each one's inventory and task snapshots are observed while it is connected. */
    fun attachSessions(sessions: StateFlow<SessionsState>): Job = track(scope.launch(dispatcher) {
        start(); ready.await()
        val followers = HashMap<Long, Pair<Job, MutableStateFlow<String?>>>()
        try {
            sessions.collect { state ->
                val open = state.open.associateBy { it.id }
                followers.keys.filter { it !in open }.forEach { id -> followers.remove(id)?.first?.cancel() }
                open.values.forEach { session ->
                    val existing = followers[session.id]
                    if (existing != null) existing.second.value = session.profileId
                    else {
                        val profile = MutableStateFlow(session.profileId)
                        followers[session.id] = launch { follow(session.id, session.workspace, profile) } to profile
                    }
                }
            }
        } finally { followers.values.forEach { it.first.cancel() } }
    })

    /** Follows one workspace outside [Sessions] (tests, a single-session host). [profileId] is its saved profile, if any. */
    fun attachWorkspace(sessionId: Long, workspace: Workspace, profileId: StateFlow<String?>): Job =
        track(scope.launch(dispatcher) { start(); ready.await(); follow(sessionId, workspace, profileId) })

    /** Announces scheduled runs as they finish. Being attached gives the Watcher no access the scheduler was granted. */
    fun attachScheduler(scheduler: StateFlow<SchedulerState>): Job = track(scope.launch(dispatcher) {
        start(); ready.await()
        scheduler.collect { watcher.observeSchedules(it) }
    })

    private suspend fun follow(sessionId: Long, workspace: Workspace, profileId: StateFlow<String?>) {
        var current: WatchSession? = null
        fun end(session: WatchSession) {
            watcher.close(session)
            if (session.scope.demo) center.dropDemo()
        }
        try {
            combine(workspace.state, profileId) { state, profile -> state to profile }.collect { (state, profile) ->
                val wanted = sessionOf(sessionId, workspace, state, profile)
                val held = current
                if (wanted != held) {
                    if (held != null && (wanted == null || wanted.epoch != held.epoch || wanted.scope.binding != held.scope.binding)) end(held)
                    current = wanted?.takeIf { watcher.open(it) }
                }
                val session = current ?: return@collect
                watchInventoryOf(state)?.let { watcher.observeInventory(session, it) }
                watchTasksOf(state)?.let { watcher.observeTasks(session, it) }
            }
        } finally { current?.let(::end) }
    }

    private fun sessionOf(sessionId: Long, workspace: Workspace, state: WorkspaceState, profileId: String?): WatchSession? {
        if (!state.connected && !state.demo) return null
        val scope = watchScopeOf(state, profileId) ?: return null
        // Refuse a snapshot already superseded by a different connection.
        val now = workspace.state.value
        if (now.connectionEpoch != state.connectionEpoch || scope.demo != state.demo) return null
        return WatchSession(scope, sessionId, state.connectionEpoch)
    }

    private fun track(job: Job): Job = synchronized(jobs) { jobs += job; job }

    /** Stops following. The Watcher keeps its last state; nothing more is posted from here. */
    override fun close() = synchronized(jobs) { jobs.forEach { it.cancel() }; jobs.clear(); started = null }
}
