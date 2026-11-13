package local.ringleader.desktop

import java.nio.file.Path
import java.time.Duration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import local.ringleader.core.*

/** Owned by the resident lock holder. Never constructed by browser mode or screenshot fixtures. */
internal class DesktopAlerts(directory: Path, scheduler: StateFlow<SchedulerState>?) {
    private val owner = SupervisorJob()
    val io = CoroutineScope(owner + Dispatchers.IO)
    val center = NotificationCenter(NotificationStore(directory.resolve("notifications.json")))
    val watcher = Watcher(WatchStore(directory.resolve("watcher.json")), center)
    val openRequest = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    var onOpen: (() -> Unit)? = null
    val native = NativeAlertBridge(center, NativeNotifications.forCurrentPlatform(), io) { id ->
        openRequest.value = id
        onOpen?.invoke()
    }
    private val runtime = WatcherRuntime(watcher, center, io)

    init {
        runtime.start()
        scheduler?.let(runtime::attachScheduler)
    }

    fun attach(sessions: Sessions): Job = runtime.attachSessions(sessions.state)

    /** Joining prevents an already-started synchronous store write from recreating wiped files. */
    suspend fun shutdown(timeout: Duration): Boolean {
        native.close()
        runtime.close()
        owner.cancel()
        return withTimeoutOrNull(timeout.toMillis().coerceAtLeast(1)) { owner.join(); true } ?: false
    }
}

/** Called on the workspace owner (UI) dispatcher. No new connections, secrets or polling loops. */
internal fun configureAlertReads(sessions: Sessions, minimized: Boolean, keepWatching: Boolean, taskAlerts: Boolean, activity: Boolean) {
    if (minimized && !keepWatching) sessions.pause() else sessions.resume()
    val state = sessions.state.value
    state.open.forEach { session ->
        session.workspace.setLiveTasks((!minimized || keepWatching) && (taskAlerts || (activity && session.id == state.activeId)))
    }
}

internal fun taskAlertsEnabled(p: NotificationPreferences?): Boolean = p?.let {
    it.master && NotificationCategory.entries.any { c -> c !in setOf(NotificationCategory.WatchStress, NotificationCategory.WatchState,
        NotificationCategory.ScheduleOutcome, NotificationCategory.ScheduleProblem) && it.enabled(c) }
} == true
