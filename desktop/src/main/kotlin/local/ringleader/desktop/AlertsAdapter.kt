package local.ringleader.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import local.ringleader.core.AppNotification
import local.ringleader.core.ConnectionProfile
import local.ringleader.core.NotificationCategory
import local.ringleader.core.NotificationCenterState
import local.ringleader.core.NotificationPreferences
import local.ringleader.core.NotificationSeverity
import local.ringleader.core.NotificationTarget
import local.ringleader.core.ResourceId
import local.ringleader.core.WatchResource
import local.ringleader.core.WatchResult
import local.ringleader.core.WatchScope
import local.ringleader.core.WatchedStatus
import local.ringleader.core.WatcherState
import local.ringleader.core.NotificationCenter as CoreNotificationCenter
import local.ringleader.core.Watcher as CoreWatcher
import local.ringleader.core.WatchAvailability as CoreAvailability
import local.ringleader.core.WatchKind as CoreWatchKind

/*
 * UI-only mapping from the core engine (NotificationCenter, Watcher) to the presentation models. It reads their state
 * flows and sends user actions back; it constructs nothing, polls nothing and reads no secret. Every store call runs on
 * the parent's background scope ([AlertsSource.io]), because the stores write small files synchronously.
 */

/**
 * What the parent hands the window (through [LocalAlertsSource]): the engine objects it owns, a background scope for
 * their calls, and the platform facts the window cannot know. The window maps it per connection in view.
 */
@Stable class AlertsSource(
    val center: CoreNotificationCenter,
    val watcher: CoreWatcher? = null,
    /** Runs every store call; a background dispatcher, never the UI thread. */
    val io: CoroutineScope,
    /** Whether the desktop notification helper exists here; [NativeDeliveryView.enabled] is taken from the preferences. */
    val native: NativeDeliveryView = NativeDeliveryView(available = false, enabled = false, unavailableReason = "Desktop notifications are not available here."),
    /** "Keep watching connected sessions while minimized or in the tray"; default off; null hides the row. */
    val keepWatchingInBackground: Boolean? = null,
    val onKeepWatchingInBackground: ((Boolean) -> Unit)? = null,
    /** A watch or unwatch the engine refused, in its own words (for example the watch limit). */
    val onProblem: ((String) -> Unit)? = null,
    val clock: () -> Instant = Instant::now,
    val onTestNative: (() -> Unit)? = null,
)

val LocalAlertsSource = compositionLocalOf<AlertsSource?> { null }

/** How the window names a connection: the saved profile's name, "Demonstration", or a generic word. Never the address. */
internal class ConnectionNames(private val profiles: List<ConnectionProfile>) {
    fun label(profileId: String?, binding: String?): String = when {
        binding == WatchScope.Demo.binding -> "Demonstration"
        profileId != null -> profiles.firstOrNull { it.id == profileId }?.displayName ?: "Removed profile"
        binding != null -> "Manual connection"
        else -> "RingMASTER"
    }
}

internal fun noticeSeverityOf(severity: NotificationSeverity): NoticeSeverity = when (severity) {
    NotificationSeverity.Info -> NoticeSeverity.Info
    NotificationSeverity.Success -> NoticeSeverity.Success
    NotificationSeverity.Warning -> NoticeSeverity.Warning
    NotificationSeverity.Error -> NoticeSeverity.Critical
}

/** The inventory identity of a watched guest in a connection whose rows carry [remote]; null for a node. */
internal fun resourceIdOf(resource: WatchResource, remote: String): ResourceId? = when (resource.kind) {
    CoreWatchKind.Node -> null
    CoreWatchKind.VM -> resource.vmid?.let { ResourceId(remote, resource.remoteScope, "VM", it) }
    CoreWatchKind.Container -> resource.vmid?.let { ResourceId(remote, resource.remoteScope, "Container", it) }
}

/** The watch identity of a guest row, or null for anything that is not a VM or container. */
internal fun watchResourceOfId(id: ResourceId): WatchResource? = when (id.kind) {
    "VM" -> CoreWatchKind.VM
    "Container" -> CoreWatchKind.Container
    else -> null
}?.let { kind -> runCatching { WatchResource(kind, id.scope, vmid = id.vmid) }.getOrNull() }

/** Where a core target points, resolved against the connection in view: only that connection's targets can route. */
internal fun noticeTargetOf(target: NotificationTarget, inView: WatchScope?): NoticeTarget? = when (target) {
    NotificationTarget.None -> null
    is NotificationTarget.Resource -> {
        val here = inView?.takeIf { it.binding == target.binding }
        val r = target.resource
        if (r.kind == CoreWatchKind.Node) NoticeTarget(NoticeTargetKind.Node, r.node.orEmpty(), if (here != null) NoticeRoute.Node(r.node.orEmpty()) else NoticeRoute.None, target.binding)
        else NoticeTarget(NoticeTargetKind.Guest, "${r.kind.label} ${r.vmid}",
            here?.let { s -> resourceIdOf(r, s.remote)?.let { NoticeRoute.Guest(it) } } ?: NoticeRoute.None, target.binding)
    }
    is NotificationTarget.Task -> NoticeTarget(NoticeTargetKind.Task, "Task", if (inView?.binding == target.binding) NoticeRoute.Task(target.upid) else NoticeRoute.None, target.binding)
    is NotificationTarget.ScheduleRun -> NoticeTarget(NoticeTargetKind.Schedule, "Schedule", NoticeRoute.ScheduleRun(target.runId))
}

private fun bindingOf(target: NotificationTarget): Pair<String?, String?> = when (target) {
    is NotificationTarget.Resource -> target.profileId to target.binding
    is NotificationTarget.Task -> target.profileId to target.binding
    else -> null to null
}

/**
 * One core notification as the center shows it. The bold line is the category's generic wording (safe in privacy
 * mode); RingMASTER's own title and body (they name guests and nodes) are the identifying detail. The engine has no
 * incident state: a recovery is its own notification, so every item is a one-off event here.
 */
internal fun noticeViewOf(n: AppNotification, state: NotificationCenterState, names: ConnectionNames, inView: WatchScope?, now: Instant): NoticeView {
    val (profileId, binding) = bindingOf(n.target)
    return NoticeView(
        id = n.id, severity = noticeSeverityOf(n.severity), category = n.category.title, headline = n.category.genericTitle,
        detail = listOf(n.title, n.body).filter { it.isNotBlank() && it != n.category.genericTitle }.joinToString("\n").ifBlank { null },
        sourceId = profileId ?: binding ?: "local", sourceLabel = names.label(profileId, binding),
        target = noticeTargetOf(n.target, inView), at = n.createdAt, read = n.read,
        snoozedUntil = state.snoozes[n.subjectId]?.takeIf { it.isAfter(now) },
    )
}

internal fun notificationCenterViewOf(state: NotificationCenterState, names: ConnectionNames, inView: WatchScope?, now: Instant) = NotificationCenterView(
    items = state.items.map { noticeViewOf(it, state, names, inView, now) }, privacy = state.preferences.privacy, now = now,
    snoozeAllUntil = state.snoozeAllUntil?.takeIf { it.isAfter(now) }, problem = state.problem)

internal fun notificationPrefsViewOf(prefs: NotificationPreferences, native: NativeDeliveryView, keepWatching: Boolean?) = NotificationPrefsView(
    enabled = prefs.master, inAppToasts = null, native = native.copy(enabled = prefs.native), privacy = prefs.privacy,
    categories = NotificationCategory.entries.map { NoticeCategoryPreference(it.name, it.title, null, prefs.enabled(it)) },
    keepWatchingInBackground = keepWatching)

/** The engine preferences after [change], or null when the change is not an engine preference. */
internal fun preferencesAfter(prefs: NotificationPreferences, change: NotificationPrefChange): NotificationPreferences? = when (change) {
    is NotificationPrefChange.Enabled -> prefs.copy(master = change.on)
    is NotificationPrefChange.Native -> prefs.copy(native = change.on)
    is NotificationPrefChange.Privacy -> prefs.copy(privacy = change.on)
    is NotificationPrefChange.Category -> NotificationCategory.entries.firstOrNull { it.name == change.id }?.let { prefs.with(it, change.on) }
    is NotificationPrefChange.InAppToasts, is NotificationPrefChange.KeepWatchingInBackground, NotificationPrefChange.TestNative -> null
}

internal fun notificationActionsOf(source: AlertsSource): NotificationActions {
    val center = source.center
    fun io(block: () -> Unit) { source.io.launch { block() } }
    return NotificationActions(
        onMarkRead = { ids -> io { ids.forEach(center::markRead) } },
        onMarkAllRead = { io { center.markAllRead() } },
        onDismiss = { id -> io { center.dismiss(id) } },
        onClearAll = { io { center.clear() } },
        onSnooze = { id, d -> io { center.snooze(id, source.clock().plus(d)) } },
        onUnsnooze = { id -> io { center.snooze(id, null) } },
        onSnoozeAll = { d -> io { center.snoozeAll(d?.let { source.clock().plus(it) }) } },
        onOpenTarget = { n -> if (!n.read) io { center.markRead(n.id) } },
    )
}

internal fun applyPrefChange(source: AlertsSource, change: NotificationPrefChange) {
    if (change == NotificationPrefChange.TestNative) { source.onTestNative?.invoke(); return }
    if (change is NotificationPrefChange.KeepWatchingInBackground) { source.onKeepWatchingInBackground?.invoke(change.on); return }
    source.io.launch { source.center.setPreferences { prefs -> preferencesAfter(prefs, change) ?: prefs } }
}

internal fun watchKeyOf(status: WatchedStatus): String = "${status.entry.binding}|${status.entry.resource.key}"

private fun Double.percent() = "${Math.round(this)} %"

internal fun watchTargetViewOf(status: WatchedStatus, names: ConnectionNames, inView: WatchScope?, unread: Int, now: Instant): WatchTargetView {
    val entry = status.entry
    val r = entry.resource
    val here = inView?.takeIf { it.binding == entry.binding }
    val availability = when (status.availability) {
        CoreAvailability.Live -> WatchTargetState.Live
        CoreAvailability.Waiting -> WatchTargetState.Pending
        CoreAvailability.NotConnected -> WatchTargetState.Unavailable("Not connected")
        CoreAvailability.Stale -> WatchTargetState.Unavailable("Not updating", status.lastSampleAt)
        CoreAvailability.Missing -> WatchTargetState.Unavailable("Not listed", status.lastSampleAt)
        CoreAvailability.BindingChanged -> WatchTargetState.Unavailable("Connection changed · watch it again")
    }
    val load = listOfNotNull(status.cpuPercent?.let { "CPU ${it.percent()}" }, status.memPercent?.let { "memory ${it.percent()}" }).joinToString(" · ")
    return WatchTargetView(
        key = watchKeyOf(status), kind = if (r.kind == CoreWatchKind.Node) WatchTargetKind.Node else WatchTargetKind.Guest,
        name = r.node ?: entry.name.ifBlank { r.kind.label }, sourceId = entry.profileId ?: entry.binding, sourceLabel = names.label(entry.profileId, entry.binding),
        guestKind = when (r.kind) { CoreWatchKind.VM -> "VM"; CoreWatchKind.Container -> "CT"; CoreWatchKind.Node -> null }, vmid = r.vmid,
        resource = here?.let { resourceIdOf(r, it.remote) }, availability = availability, status = status.status,
        healthy = status.status in setOf("running", "online") && !status.stressedNow,
        unread = unread, incident = status.stressedNow,
        lastChange = if (status.stressedNow) "Under load" + if (load.isNotEmpty()) " · $load" else "" else null,
        lastChangeAt = if (status.stressedNow) status.stressedSince else null, since = entry.addedAt, inView = here != null,
    )
}

internal fun watcherViewOf(state: WatcherState, center: NotificationCenterState?, names: ConnectionNames, inView: WatchScope?, now: Instant): WatcherView {
    val unread = center?.items.orEmpty().filter { !it.read }.mapNotNull { (it.target as? NotificationTarget.Resource)?.let { t -> t.binding to t.resource } }.groupingBy { it }.eachCount()
    val targets = state.watched.map { s -> watchTargetViewOf(s, names, inView, unread[s.entry.binding to s.entry.resource] ?: 0, now) }
    val mine = state.watched.filter { inView != null && it.entry.binding == inView.binding }.map { it.entry.resource }
    val activity = when {
        state.watched.isEmpty() -> WatcherActivity.Off
        state.watched.any { it.availability == CoreAvailability.Live } -> WatcherActivity.Watching()
        else -> WatcherActivity.Paused("No connected session is reading them")
    }
    return WatcherView(targets, activity, now,
        watchedGuests = if (inView == null) emptySet() else mine.mapNotNull { resourceIdOf(it, inView.remote) }.toSet(),
        watchedNodes = mine.mapNotNull { it.node }.toSet(), problem = state.problem)
}

internal fun watcherActionsOf(source: AlertsSource, inView: () -> WatchScope?): WatcherActions {
    val watcher = source.watcher ?: return WatcherActions()
    fun io(block: () -> WatchResult) { source.io.launch { (block() as? WatchResult.Error)?.let { source.onProblem?.invoke(it.message) } } }
    return WatcherActions(
        onUnwatch = { key -> io { watcher.state.value.watched.firstOrNull { watchKeyOf(it) == key }?.let { watcher.unwatch(it.entry.binding, it.entry.resource) } ?: WatchResult.Ok } },
        onWatchGuest = { id, name, on ->
            val scope = inView()
            val r = watchResourceOfId(id)
            // Only a guest of the connection in view, identified by that connection's own remote key.
            if (scope != null && r != null && id.remote == scope.remote) io { if (on) watcher.watch(scope, r, name) else watcher.unwatch(scope.binding, r) }
        },
        onWatchNode = { node, on ->
            val scope = inView()
            val r = runCatching { WatchResource(CoreWatchKind.Node, node = node) }.getOrNull()
            if (scope != null && r != null) io { if (on) watcher.watch(scope, r, node) else watcher.unwatch(scope.binding, r) }
        },
    )
}

/**
 * The presentation for the connection in view, from the engine's flows. Re-mapped when either state changes, the
 * connection in view changes, or the minute turns (ages and snooze ends).
 */
@Composable internal fun rememberAlertsUi(source: AlertsSource, inView: WatchScope?, profiles: List<ConnectionProfile>): AlertsUi {
    val centerState by source.center.state.collectAsState()
    val watcherState = source.watcher?.state?.collectAsState()?.value
    val minute = (LocalNow.current ?: source.clock()).truncatedTo(ChronoUnit.MINUTES)
    val names = remember(profiles) { ConnectionNames(profiles) }
    val scope = rememberUpdatedScope(inView)
    return remember(source, centerState, watcherState, inView, names, minute) {
        AlertsUi(
            notices = if (centerState.loaded) notificationCenterViewOf(centerState, names, inView, minute) else null,
            noticeActions = notificationActionsOf(source),
            prefs = if (centerState.loaded) notificationPrefsViewOf(centerState.preferences, source.native, source.keepWatchingInBackground).copy(canTestNative = source.onTestNative != null) else null,
            onPrefChange = { applyPrefChange(source, it) },
            watcher = watcherState?.takeIf { it.loaded }?.let { watcherViewOf(it, centerState, names, inView, minute) },
            watcherActions = watcherActionsOf(source) { scope.value },
        )
    }
}

@Composable private fun rememberUpdatedScope(inView: WatchScope?) = androidx.compose.runtime.rememberUpdatedState(inView)
