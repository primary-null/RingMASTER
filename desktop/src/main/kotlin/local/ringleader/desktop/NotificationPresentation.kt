package local.ringleader.desktop

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import java.time.Duration
import java.time.Instant
import local.ringleader.core.ResourceId

/*
 * UI-only presentation of notifications. The engine, its store, dedupe, rules and native delivery live elsewhere (core and
 * the native helper); the parent maps their state into these immutable values and these callbacks. Nothing here polls,
 * persists or delivers anything, and nothing here holds sample data.
 *
 * Text contract for the mapper:
 *  - [NoticeView.headline] and [NoticeView.category] are generic: they never name a guest, node, host, address or user
 *    ("Backup failed", "Node offline"). They are what privacy mode shows.
 *  - [NoticeView.detail], [NoticeView.sourceLabel] and [NoticeTarget.label] may identify things. Privacy mode drops them;
 *    Showcase blurs them and screen readers hear "Hidden".
 */

/** How serious a notice is. Ordered from least to most serious. */
enum class NoticeSeverity(val label: String) { Info("Info"), Success("OK"), Warning("Warning"), Critical("Critical") }

/** Where an incident stands. Independent of [NoticeView.read] and of what the operating system did with its toast. */
sealed interface NoticeIncident {
    /** A one-off event (task finished, schedule ran). */
    data object Event : NoticeIncident
    /** The condition is still true (node still offline). */
    data object Ongoing : NoticeIncident
    /** The condition cleared at [at]; [after] is how long it lasted when known. */
    data class Recovered(val at: Instant, val after: Duration? = null) : NoticeIncident
}

/** What a notice points at, and where the app can take the user for it. */
enum class NoticeTargetKind(val label: String) { Guest("Guest"), Node("Node"), Task("Task"), Storage("Storage"), Schedule("Schedule"), Connection("Connection") }

/** Where opening a target goes inside the window. [None]: the target cannot be opened here. */
sealed interface NoticeRoute {
    /** A guest of the connection with [sessionKey] (the parent's key), opened on [tab]. */
    data class Guest(val resource: ResourceId, val tab: String = "Summary") : NoticeRoute
    data class Node(val name: String) : NoticeRoute
    data object Activity : NoticeRoute
    data class Task(val upid: String) : NoticeRoute
    data object Storage : NoticeRoute
    data object Schedules : NoticeRoute
    data class ScheduleRun(val runId: String) : NoticeRoute
    data object Watcher : NoticeRoute
    data object None : NoticeRoute
}

@Immutable data class NoticeTarget(
    val kind: NoticeTargetKind,
    /** Identifying (a guest name, a node name). */
    val label: String,
    val route: NoticeRoute = NoticeRoute.None,
    /** The parent's connection key; null when the target belongs to whatever connection is in view. */
    val sessionKey: String? = null,
)

@Immutable data class NoticeView(
    val id: String,
    val severity: NoticeSeverity,
    /** Generic group, used for filters and privacy text ("Backups", "Nodes", "Tasks"). */
    val category: String,
    /** Generic one-line summary. Never identifying. */
    val headline: String,
    /** Identifying detail, one or more lines. */
    val detail: String? = null,
    /** The server or profile the notice came from (identifying); [sourceId] is its stable key for the filter. */
    val sourceId: String,
    val sourceLabel: String,
    val target: NoticeTarget? = null,
    val at: Instant,
    /** The user has seen it here (or marked it). */
    val read: Boolean = false,
    val incident: NoticeIncident = NoticeIncident.Event,
    /**
     * Desktop (OS) notifications about this notice's subject are paused until then. The notice itself stays in the inbox
     * and in the badge: snoozing silences, it does not hide (the engine's rule).
     */
    val snoozedUntil: Instant? = null,
    /** The operating system toast was closed by the user. That neither reads the notice nor ends the incident. */
    val osDismissed: Boolean = false,
    /** How many identical events the engine folded into this one. */
    val repeats: Int = 1,
)

/** Inbox filter. Empty [severities] and null [sourceId] mean all; [snoozedOnly] lists the silenced subjects. */
@Immutable data class NoticeFilter(
    val severities: Set<NoticeSeverity> = emptySet(),
    val sourceId: String? = null,
    val unreadOnly: Boolean = false,
    val snoozedOnly: Boolean = false,
)

/** One snapshot of the inbox for the center, the badge and the toasts. */
@Immutable data class NotificationCenterView(
    val items: List<NoticeView>,
    val privacy: Boolean,
    val now: Instant,
    /** Every desktop notification is paused until then (the items are still recorded and shown here). */
    val snoozeAllUntil: Instant? = null,
    /** The history cannot be read or saved; what is shown may be in memory only. Generic text, shown as is. */
    val problem: String? = null,
)

/** A choice for snoozing; the parent decides the durations it supports. */
@Immutable data class SnoozeChoice(val label: String, val duration: Duration)

val DefaultSnoozeChoices = listOf(SnoozeChoice("1 hour", Duration.ofHours(1)), SnoozeChoice("4 hours", Duration.ofHours(4)), SnoozeChoice("1 day", Duration.ofDays(1)))

/**
 * What the center can ask the engine to do. A null action hides its control, so nothing is shown that the engine
 * cannot do.
 */
@Immutable class NotificationActions(
    val onMarkRead: ((ids: Set<String>) -> Unit)? = null,
    val onMarkUnread: ((id: String) -> Unit)? = null,
    val onMarkAllRead: (() -> Unit)? = null,
    /** Removes one notice from the inbox. */
    val onDismiss: ((id: String) -> Unit)? = null,
    /** Removes every read notice (the unread ones stay). */
    val onClearRead: (() -> Unit)? = null,
    /** Empties the whole history, unread included. */
    val onClearAll: (() -> Unit)? = null,
    /** Pauses every desktop notification for a duration; null ends the pause. */
    val onSnoozeAll: ((duration: Duration?) -> Unit)? = null,
    val onSnooze: ((id: String, duration: Duration) -> Unit)? = null,
    val onUnsnooze: ((id: String) -> Unit)? = null,
    /** Told after the window routed to a target (mark read, switch session); the routing itself is done by the window. */
    val onOpenTarget: ((NoticeView) -> Unit)? = null,
    val snoozeChoices: List<SnoozeChoice> = DefaultSnoozeChoices,
)

/** Native delivery as the preferences show it. The delivery itself is not this file's business. */
@Immutable data class NativeDeliveryView(val available: Boolean, val enabled: Boolean, val unavailableReason: String? = null)

@Immutable data class NoticeCategoryPreference(val id: String, val label: String, val description: String? = null, val enabled: Boolean)

@Immutable data class NotificationPrefsView(
    val enabled: Boolean,
    /** Null: the engine has no such setting; the row is not shown and toasts follow [enabled]. */
    val inAppToasts: Boolean?,
    val native: NativeDeliveryView,
    val privacy: Boolean,
    val categories: List<NoticeCategoryPreference>,
    /**
     * "Keep watching connected sessions while minimized or in the tray". Default off. Null hides the row. It only keeps
     * sessions that are already connected reading; it never signs in and never opens the vault.
     */
    val keepWatchingInBackground: Boolean? = null,
    val canTestNative: Boolean = false,
)

sealed interface NotificationPrefChange {
    data object TestNative : NotificationPrefChange
    data class Enabled(val on: Boolean) : NotificationPrefChange
    data class InAppToasts(val on: Boolean) : NotificationPrefChange
    data class Native(val on: Boolean) : NotificationPrefChange
    data class Privacy(val on: Boolean) : NotificationPrefChange
    data class Category(val id: String, val on: Boolean) : NotificationPrefChange
    data class KeepWatchingInBackground(val on: Boolean) : NotificationPrefChange
}

/**
 * Everything the parent wires from the engine. Provided through [LocalAlerts]; null pieces switch their UI off (no bell
 * without [notices], no Watcher page without [watcher], no preferences tab without [prefs]).
 */
@Immutable class AlertsUi(
    val notices: NotificationCenterView? = null,
    val noticeActions: NotificationActions = NotificationActions(),
    val prefs: NotificationPrefsView? = null,
    val onPrefChange: ((NotificationPrefChange) -> Unit)? = null,
    val watcher: WatcherView? = null,
    val watcherActions: WatcherActions = WatcherActions(),
)

val LocalAlerts = compositionLocalOf<AlertsUi?> { null }

// ---- Pure presentation logic (tested without Compose) ----

/** True while [notice] is snoozed at [now]. */
fun noticeSnoozed(notice: NoticeView, now: Instant): Boolean = notice.snoozedUntil?.isAfter(now) == true

/** The badge number: every unread notice. Snoozing silences desktop delivery only, so a snoozed one still counts. */
@Suppress("UNUSED_PARAMETER")
fun unreadNoticeCount(items: List<NoticeView>, now: Instant): Int = items.count { !it.read }

/** The badge text: "99+" above 99, nothing at zero. */
fun noticeBadgeText(count: Int): String? = when { count <= 0 -> null; count > 99 -> "99+"; else -> "$count" }

/** Inbox order: unread first, then most serious, then newest. */
fun filterNotices(items: List<NoticeView>, filter: NoticeFilter, now: Instant): List<NoticeView> = items.asSequence()
    .filter { !filter.snoozedOnly || noticeSnoozed(it, now) }
    .filter { filter.severities.isEmpty() || it.severity in filter.severities }
    .filter { filter.sourceId == null || it.sourceId == filter.sourceId }
    .filter { !filter.unreadOnly || !it.read }
    .sortedWith(compareBy<NoticeView> { it.read }.thenByDescending { it.severity.ordinal }.thenByDescending { it.at })
    .toList()

/** The sources for the filter, in first-seen order, with their (identifying) labels. */
fun noticeSources(items: List<NoticeView>): List<Pair<String, String>> = items.distinctBy { it.sourceId }.map { it.sourceId to it.sourceLabel }

/** How a notice reads in the window. Identifying parts are null in privacy mode. */
@Immutable data class NoticeText(
    val headline: String,
    val detail: String?,
    val source: String?,
    val target: String?,
    /** What a screen reader hears for the whole row: never more than what is visible. */
    val spoken: String,
)

const val PRIVATE_NOTICE_SOURCE = "Hidden server"

/**
 * The text a notice shows. Privacy mode (or Showcase, which the window passes as [privacy] for toasts) keeps only the
 * generic headline and category. Showcase in the center keeps the layout and blurs the identifying parts instead, so
 * [spoken] there is built with [showcase] = true and leaves them out.
 */
fun noticeText(notice: NoticeView, privacy: Boolean, showcase: Boolean = false): NoticeText {
    val hideIds = privacy || showcase
    val detail = notice.detail?.takeIf { !privacy && it.isNotBlank() }
    val source = if (privacy) null else notice.sourceLabel
    val target = notice.target?.let { if (privacy) null else "${it.kind.label} ${it.label}" }
    val spoken = buildList {
        add(notice.severity.label)
        add(notice.headline)
        if (!hideIds) { detail?.let(::add); target?.let(::add); source?.let { add("from $it") } }
        else if (!privacy) add("details hidden")
        incidentLabel(notice.incident)?.let(::add)
        if (!notice.read) add("unread")
    }.joinToString(", ")
    return NoticeText(notice.headline, detail, source, target, spoken)
}

/** The incident chip: "ONGOING", "RECOVERED · 12 m", or nothing for a one-off event. */
fun incidentLabel(incident: NoticeIncident): String? = when (incident) {
    NoticeIncident.Event -> null
    NoticeIncident.Ongoing -> "Ongoing"
    is NoticeIncident.Recovered -> "Recovered" + (incident.after?.let { " after ${compactDuration(it)}" } ?: "")
}

/** "45 s", "12 m", "3 h", "2 d". */
fun compactDuration(d: Duration): String {
    val s = d.seconds.coerceAtLeast(0)
    return when { s < 60 -> "$s s"; s < 3600 -> "${s / 60} m"; s < 86_400 -> "${s / 3600} h"; else -> "${s / 86_400} d" }
}

/** "now", "5 m ago", "3 h ago", "2 d ago". A time ahead of [now] (clock skew) reads as now. */
fun noticeAge(at: Instant, now: Instant): String {
    val s = Duration.between(at, now).seconds
    return if (s < 45) "now" else "${compactDuration(Duration.ofSeconds(s))} ago"
}

/** Whether the window can route to [target] now. A guest must be in the current inventory of the connection in view. */
fun noticeTargetOpenable(target: NoticeTarget?, guestPresent: (ResourceId) -> Boolean, inViewSession: String?): Boolean {
    target ?: return false
    if (target.sessionKey != null && target.sessionKey != inViewSession) return false
    return when (val route = target.route) {
        is NoticeRoute.Guest -> guestPresent(route.resource)
        NoticeRoute.None -> false
        else -> true
    }
}

// ---- Toasts ----

/** How long a toast stays: info briefly, warnings longer, critical until it is closed or read. */
fun toastLifetime(severity: NoticeSeverity): Duration? = when (severity) {
    NoticeSeverity.Info, NoticeSeverity.Success -> Duration.ofSeconds(6)
    NoticeSeverity.Warning -> Duration.ofSeconds(10)
    NoticeSeverity.Critical -> null
}

const val TOAST_VISIBLE_MAX = 3

/**
 * The toast stack's own bookkeeping: which notices are showing, since when, and which were closed. It is window state,
 * not a store: closing or expiring a toast never reads or dismisses the notice (the center still has it unread).
 * Only notices that arrive while the window is up become toasts; the inbox at start-up is the center's business.
 */
class ToastQueue(private val visibleMax: Int = TOAST_VISIBLE_MAX) {
    private val known = HashSet<String>()
    private var primed = false
    private val shownAt = LinkedHashMap<String, Instant>()
    private val closed = HashSet<String>()
    private val paused = HashSet<String>()

    /**
     * Takes the current inbox. New unread notices that are not snoozed queue up (newest on top); ones read, dismissed or
     * snoozed elsewhere leave the stack. The first call only learns what already exists.
     */
    fun offer(items: List<NoticeView>, now: Instant, enabled: Boolean = true) {
        val ids = items.associateBy { it.id }
        if (!primed) { primed = true; known += ids.keys; return }
        for (n in items.sortedBy { it.at }) {
            if (known.add(n.id) && enabled && !n.read && !noticeSnoozed(n, now)) shownAt[n.id] = now
        }
        shownAt.keys.retainAll { id -> ids[id]?.let { !it.read && !noticeSnoozed(it, now) } == true }
        known.retainAll(ids.keys)
        closed.retainAll(ids.keys)
    }

    /** Closes one toast (the notice stays unread). */
    fun close(id: String) { shownAt.remove(id); closed += id; paused -= id }

    /** Closes every toast, for "open the center" and for a window that went away. */
    fun closeAll() { closed += shownAt.keys; shownAt.clear(); paused.clear() }

    /** Pointer over or keyboard in a toast: its time does not run out. */
    fun pause(id: String, on: Boolean) { if (on) paused += id else paused -= id }

    /** Drops toasts whose lifetime ran out. A paused toast keeps restarting its time, so it gets a full lifetime once released. */
    fun expire(items: List<NoticeView>, now: Instant) {
        val byId = items.associateBy { it.id }
        for (id in paused) if (id in shownAt) shownAt[id] = now
        shownAt.entries.removeIf { (id, since) ->
            id !in paused && byId[id]?.let { toastLifetime(it.severity) }?.let { !since.plus(it).isAfter(now) } == true
        }
    }

    /** The next time [expire] has something to do, or null when nothing will run out. */
    fun nextExpiry(items: List<NoticeView>): Instant? {
        val byId = items.associateBy { it.id }
        return shownAt.mapNotNull { (id, since) -> if (id in paused) null else byId[id]?.let { toastLifetime(it.severity) }?.let { since.plus(it) } }.minOrNull()
    }

    /** Newest first, at most [max] (never more than the queue's own limit); the rest is counted in [overflow]. */
    fun visible(items: List<NoticeView>, max: Int = visibleMax): List<NoticeView> {
        val byId = items.associateBy { it.id }
        return shownAt.keys.reversed().mapNotNull { byId[it] }.take(max.coerceIn(1, visibleMax))
    }

    fun overflow(max: Int = visibleMax): Int = (shownAt.size - max.coerceIn(1, visibleMax)).coerceAtLeast(0)
    val showing: Int get() = shownAt.size
}
