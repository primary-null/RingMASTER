package local.ringleader.core

import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [items] are newest first. [problem] is set while the file cannot be used or the last save failed; with [unsaved]
 * the window still shows everything, but what it shows is only in memory. [unreadable] offers
 * [NotificationCenter.setAsideAndStartEmpty]; [readOnly] (a newer file, a link, a file changed outside) offers nothing
 * but a restart.
 */
data class NotificationCenterState(
    val loaded: Boolean = false,
    val items: List<AppNotification> = emptyList(),
    val preferences: NotificationPreferences = NotificationPreferences(),
    val snoozeAllUntil: Instant? = null,
    val snoozes: Map<String, Instant> = emptyMap(),
    val problem: String? = null,
    val unreadable: Boolean = false,
    val readOnly: Boolean = false,
    val unsaved: Boolean = false,
) {
    val unread: Int get() = items.count { !it.read }
    fun snoozed(notification: AppNotification, now: Instant): Boolean =
        snoozeAllUntil?.isAfter(now) == true || snoozes[notification.subjectId]?.isAfter(now) == true
}

enum class PostResult {
    /** Recorded (and offered to the OS when allowed). */
    Posted,
    /** This identity was posted before. Nothing changed. */
    Duplicate,
    /** The master switch or the category is off. Nothing was recorded. */
    Disabled,
    /** [NotificationCenter.load] has not run: what was posted before is not known yet. Nothing was recorded. */
    NotLoaded,
}

/** Where producers ([Watcher]) hand their events. */
fun interface NotificationSink { fun post(event: NotificationEvent): PostResult }

/**
 * The in-app notification center and the single gate in front of OS notifications.
 *
 * - Identity: an event whose [NotificationIdentity.id] was posted before is dropped, also after its notification was
 *   dismissed, cleared or trimmed (the last [MAX_NOTIFICATION_SEEN] identities are kept on disk).
 * - History: at most [MAX_NOTIFICATIONS], newest first.
 * - Snooze: a snoozed event is still recorded; it only does not reach the OS.
 * - Storage: every change is saved before the call returns. A failed load or save never discards what is in memory
 *   and never overwrites a file that could not be read; it is reported in [NotificationCenterState.problem].
 * - Demo events are kept in memory only and never reach the OS.
 *
 * Thread-safe. Saving is small, synchronous file I/O: call from a background dispatcher, not from the UI thread, when
 * that matters. [native] and [posted] drop the oldest entry when nobody collects fast enough; neither replays.
 */
class NotificationCenter(private val store: NotificationStore, private val now: () -> Instant = { Instant.now() }) : NotificationSink {
    private val lock = Any()
    private val mutable = MutableStateFlow(NotificationCenterState())
    val state: StateFlow<NotificationCenterState> = mutable.asStateFlow()
    private val nativeFlow = MutableSharedFlow<NativeNotificationRequest>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** OS notification requests. The platform layer collects this; without a collector nothing is delivered. */
    val native: SharedFlow<NativeNotificationRequest> = nativeFlow.asSharedFlow()
    private val postedFlow = MutableSharedFlow<AppNotification>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Every newly recorded notification, for in-window motion. */
    val posted: SharedFlow<AppNotification> = postedFlow.asSharedFlow()
    private var seen = ArrayDeque<String>()
    private var seenSet = HashSet<String>()
    /** Demo identities never reach the file. */
    private val demoSeen = HashSet<String>()

    /** Reads the file once. On failure the center works in memory, reports the problem and leaves the file alone. */
    fun load() = synchronized(lock) {
        if (mutable.value.loaded) return@synchronized
        try {
            val document = store.load()
            val at = now()
            seen = ArrayDeque(document.seen); seenSet = HashSet(document.seen)
            document.items.forEach { remember(it.id) }
            mutable.value = NotificationCenterState(loaded = true, items = document.items.sortedByDescending { it.createdAt }.take(MAX_NOTIFICATIONS),
                preferences = document.preferences, snoozeAllUntil = document.snoozeAllUntil?.takeIf { it.isAfter(at) },
                snoozes = document.snoozes.filterValues { it.isAfter(at) })
        } catch (e: GuardedStoreException) {
            mutable.value = mutable.value.copy(loaded = true, problem = e.message, unreadable = e.unreadable, readOnly = e.readOnly, unsaved = true)
        }
    }

    override fun post(event: NotificationEvent): PostResult {
        val request: NativeNotificationRequest?
        val notification: AppNotification
        synchronized(lock) {
            val current = mutable.value
            if (!current.loaded) return PostResult.NotLoaded
            if (!current.preferences.master || !current.preferences.enabled(event.category)) return PostResult.Disabled
            val id = event.identity.id
            if (id in seenSet || id in demoSeen) return PostResult.Duplicate
            val at = now()
            notification = AppNotification(id, event.identity.subjectId, event.category, event.severity,
                notificationText(event.title, NOTIFICATION_TITLE_MAX).ifBlank { event.category.genericTitle },
                notificationText(event.body, NOTIFICATION_BODY_MAX), event.target, at, demo = event.demo)
            if (event.demo) { if (demoSeen.size >= MAX_NOTIFICATION_SEEN) demoSeen.clear(); demoSeen += id } else remember(id)
            val next = current.copy(items = (listOf(notification) + current.items).take(MAX_NOTIFICATIONS))
            mutable.value = next
            if (!event.demo) persist()
            val deliver = event.native && !event.demo && next.preferences.native && !next.snoozed(notification, at)
            request = if (!deliver) null else presentNotification(notification, next.preferences.privacy).let {
                NativeNotificationRequest(id, event.category, event.severity, it.title, it.body, next.preferences.privacy, event.target)
            }
        }
        postedFlow.tryEmit(notification)
        request?.let { nativeFlow.tryEmit(it) }
        return PostResult.Posted
    }

    fun markRead(id: String) = change { state -> state.copy(items = state.items.map { if (it.id == id) it.copy(read = true) else it }) }
    fun markAllRead() = change { state -> state.copy(items = state.items.map { it.copy(read = true) }) }
    /** Removes one notification. Its identity stays known, so the same event cannot come back. */
    fun dismiss(id: String) = change { state -> state.copy(items = state.items.filter { it.id != id }) }
    /** Empties the history. Identities stay known. */
    fun clear() = change { state -> state.copy(items = emptyList()) }
    /** Drops what Demo mode posted. Called when the demo session ends. */
    fun dropDemo() = synchronized(lock) {
        // Demo items were never on disk, so there is nothing to save.
        demoSeen.clear()
        val current = mutable.value
        if (current.items.any { it.demo }) mutable.value = current.copy(items = current.items.filter { !it.demo })
    }

    /** No OS notifications until [until]. Events are still recorded. A time in the past ends the snooze. */
    fun snoozeAll(until: Instant?) = change { state -> state.copy(snoozeAllUntil = until?.takeIf { it.isAfter(now()) }) }

    /** No OS notifications about this notification's subject (the same resource, task or schedule) until [until]. */
    fun snooze(id: String, until: Instant?) = change { state ->
        val subject = state.items.firstOrNull { it.id == id }?.subjectId ?: return@change state
        val at = now()
        val kept = state.snoozes.filter { (key, value) -> key != subject && value.isAfter(at) }
        val next = if (until != null && until.isAfter(at)) kept + (subject to until) else kept
        // Bounded: the snoozes ending soonest are given up first.
        state.copy(snoozes = next.entries.sortedByDescending { it.value }.take(MAX_NOTIFICATION_SNOOZES).associate { it.key to it.value })
    }

    fun setPreferences(transform: (NotificationPreferences) -> NotificationPreferences) = change { it.copy(preferences = transform(it.preferences)) }

    /** Recheck queued delivery against current settings immediately before calling the OS. */
    fun prepareNative(request: NativeNotificationRequest, showcase: Boolean = false): NativeNotificationRequest? = synchronized(lock) {
        val current = mutable.value
        val item = current.items.firstOrNull { it.id == request.id } ?: return@synchronized null
        if (item.demo || !current.preferences.master || !current.preferences.native ||
            !current.preferences.enabled(item.category) || current.snoozed(item, now())) return@synchronized null
        val private = current.preferences.privacy || showcase
        val text = presentNotification(item, private)
        request.copy(title = text.title, body = text.body, private = private)
    }

    /** Tries to write what is in memory again after a failed save. True when it is on disk now. */
    fun retrySave(): Boolean = synchronized(lock) { mutable.value.loaded && persist() }

    /**
     * Explicit user action for an unreadable `notifications.json`: the file is moved aside unchanged and what is in
     * memory is saved as the new file. Returns where the old file went.
     * @throws GuardedStoreException when the file is not unreadable or cannot be moved; nothing changes then.
     */
    fun setAsideAndStartEmpty(): Path = synchronized(lock) {
        val target = store.setAside(now())
        mutable.value = mutable.value.copy(problem = null, unreadable = false, readOnly = false)
        persist()
        target
    }

    private fun change(transform: (NotificationCenterState) -> NotificationCenterState) = synchronized(lock) {
        val current = mutable.value
        val next = transform(current)
        if (next == current) return@synchronized
        mutable.value = next
        if (current.loaded) persist()
    }

    private fun remember(id: String) {
        if (!seenSet.add(id)) return
        seen.addLast(id)
        while (seen.size > MAX_NOTIFICATION_SEEN) seenSet.remove(seen.removeFirst())
    }

    /** Caller holds [lock]. Never throws: a failure is recorded in the state and what is in memory is kept. */
    private fun persist(): Boolean {
        val state = mutable.value
        store.problem()?.let { e ->
            mutable.value = state.copy(problem = e.message, unreadable = e.unreadable, readOnly = e.readOnly, unsaved = true)
            return false
        }
        return try {
            store.save(NotificationDocument(state.preferences, state.snoozeAllUntil, state.snoozes, state.items.filter { !it.demo }, seen.toList()))
            mutable.value = state.copy(problem = null, unreadable = false, readOnly = false, unsaved = false)
            true
        } catch (e: GuardedStoreException) {
            mutable.value = state.copy(problem = e.message, unreadable = e.unreadable, readOnly = e.readOnly, unsaved = true)
            false
        } catch (e: Exception) {
            mutable.value = state.copy(problem = "Notifications could not be saved (${e.javaClass.simpleName}).", unsaved = true)
            false
        }
    }
}
