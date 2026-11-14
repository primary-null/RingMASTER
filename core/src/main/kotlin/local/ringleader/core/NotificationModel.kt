package local.ringleader.core

import java.time.Instant

/**
 * What a notification is about. Each category has its own switch. [genericTitle] is the only text shown while privacy
 * is on: it names the kind of event and nothing about the server, guest, node or task.
 */
enum class NotificationCategory(val title: String, val defaultOn: Boolean, val genericTitle: String) {
    /** A watched node or guest held high CPU or memory, and its return to normal. */
    WatchStress("Watched resources under load", true, "A watched resource changed load"),
    /** A watched node or guest changed to another known state, or is no longer listed. */
    WatchState("Watched resource state changes", true, "A watched resource changed state"),
    TaskFailure("Failed tasks", true, "A task failed"),
    TaskWarnings("Tasks finished with warnings", false, "A task finished with warnings"),
    BackupFailure("Failed backups", true, "A backup failed"),
    NewGuest("New guests", true, "A guest was created"),
    Migration("Finished migrations", true, "A migration finished"),
    /** A task whose type names fencing appeared in the task feed. Nothing else about HA is known to RingMASTER. */
    HaFencing("Fencing tasks", true, "A fencing task was seen"),
    PackageUpdates("Package tasks", false, "A package task was seen"),
    /** A guest power task started by another account than this connection's. */
    ExternalPower("Guest power tasks by other accounts", false, "A guest power task was seen"),
    /** A scheduled run that finished OK or found nothing to do. */
    ScheduleOutcome("Finished schedules", true, "A schedule ran"),
    /** A scheduled run that failed, was refused, missed, skipped, or whose result is not confirmed. */
    ScheduleProblem("Schedule problems", true, "A schedule needs attention"),
}

enum class NotificationSeverity { Info, Success, Warning, Error }

const val NOTIFICATION_TITLE_MAX = 96
const val NOTIFICATION_BODY_MAX = 240
const val NOTIFICATION_GENERIC_BODY = "Open RingMASTER for details."
internal val notificationIdPattern = Regex("[0-9a-f]{32}")

/**
 * Deterministic identity of one event. [source] is the producer ("watch", "task", "schedule"), [scope] the connection
 * binding digest (or "demo" / "local"), [subject] the thing (a resource, a UPID, a plan) and [occurrence] what tells one
 * event about that thing from the next (a revision and due time, an outcome, an onset time).
 * The same four values always give the same [id], so a repeated snapshot can never post twice.
 */
data class NotificationIdentity(val source: String, val scope: String, val subject: String, val occurrence: String) {
    init {
        require(listOf(source, scope, subject, occurrence).all { it.isNotEmpty() && it.length <= 1100 && it.none { c -> c == '\n' || c == '\r' } }) { "The notification identity is not usable." }
    }
    val id: String get() = sha256Hex("ringmaster-notify-v1\n$source\n$scope\n$subject\n$occurrence").take(32)
    /** Identity of the thing alone, for snoozing every later event about it. */
    val subjectId: String get() = sha256Hex("ringmaster-notify-subject-v1\n$source\n$scope\n$subject").take(32)
}

/**
 * Where a notification points, as internal identity only. It is never a URL, a path or a command, and nothing here is
 * executed: the window resolves it against the open sessions and saved profiles, and shows nothing when it no longer
 * resolves. [binding] is the connection digest ([WatchScope.binding]); the server address itself is not kept.
 */
sealed interface NotificationTarget {
    data object None : NotificationTarget
    data class Resource(val profileId: String?, val binding: String, val resource: WatchResource) : NotificationTarget {
        init { requireTargetScope(profileId, binding) }
    }
    data class Task(val profileId: String?, val binding: String, val upid: String) : NotificationTarget {
        init {
            requireTargetScope(profileId, binding)
            require(parseServerUpid(upid) != null || (binding == DEMO_WATCH_BINDING && upid.startsWith("demo:") && upid.length <= 200 && upid.none { it.isWhitespace() || it.isISOControl() })) { "The notification task is not usable." }
        }
    }
    data class ScheduleRun(val planId: String, val runId: String) : NotificationTarget {
        init { require(isCanonicalUuid(planId) && isCanonicalUuid(runId)) { "The notification schedule is not usable." } }
    }
}

private fun requireTargetScope(profileId: String?, binding: String) {
    require(profileId == null || isCanonicalUuid(profileId)) { "The notification connection is not usable." }
    require(binding.matches(sha256PinPattern)) { "The notification connection is not usable." }
}

internal fun isCanonicalUuid(value: String): Boolean = runCatching { java.util.UUID.fromString(value).toString() == value }.getOrDefault(false)

/**
 * One stored notification. [title] and [body] are built by RingMASTER from validated fields (names, IDs, node names,
 * fixed outcome words) and bounded by [notificationText]; a server error text, a task log or an exception message is
 * never part of them. [demo] notifications come from Demo mode: they are never saved and never reach the OS.
 */
data class AppNotification(
    val id: String,
    val subjectId: String,
    val category: NotificationCategory,
    val severity: NotificationSeverity,
    val title: String,
    val body: String,
    val target: NotificationTarget,
    val createdAt: Instant,
    val read: Boolean = false,
    val demo: Boolean = false,
) {
    init {
        require(id.matches(notificationIdPattern) && subjectId.matches(notificationIdPattern)) { "The notification identity is not usable." }
        require(title.isNotBlank() && title.length <= NOTIFICATION_TITLE_MAX && title.none { it.isISOControl() }) { "The notification title is not usable." }
        require(body.length <= NOTIFICATION_BODY_MAX && body.none { it.isISOControl() }) { "The notification text is not usable." }
    }
}

/** What a producer hands to the [NotificationCenter]. [native] false keeps a low-value event inside the window only. */
data class NotificationEvent(
    val identity: NotificationIdentity,
    val category: NotificationCategory,
    val severity: NotificationSeverity,
    val title: String,
    val body: String,
    val target: NotificationTarget = NotificationTarget.None,
    val demo: Boolean = false,
    val native: Boolean = true,
)

/**
 * [master] off: nothing is recorded or shown. [native] off: notifications stay inside the window. [privacy] on: the
 * OS notification (and any surface that asks for the private presentation) shows only the category's generic text.
 * A category missing from [categories] uses its default.
 */
data class NotificationPreferences(
    val master: Boolean = true,
    val native: Boolean = true,
    val privacy: Boolean = false,
    val categories: Map<NotificationCategory, Boolean> = emptyMap(),
) {
    fun enabled(category: NotificationCategory): Boolean = categories[category] ?: category.defaultOn
    fun with(category: NotificationCategory, on: Boolean): NotificationPreferences =
        copy(categories = if (on == category.defaultOn) categories - category else categories + (category to on))
}

data class NotificationPresentation(val title: String, val body: String)

/** The text to show. With [private] set nothing but the category's generic wording is returned. */
fun presentNotification(notification: AppNotification, private: Boolean): NotificationPresentation =
    if (private) NotificationPresentation(notification.category.genericTitle, NOTIFICATION_GENERIC_BODY)
    else NotificationPresentation(notification.title, notification.body)

/**
 * A request for one OS notification. [title] and [body] are already the private wording when privacy is on.
 * [target] is identity for the click handler; it must not be turned into a command line or a link by the platform code.
 */
data class NativeNotificationRequest(
    val id: String,
    val category: NotificationCategory,
    val severity: NotificationSeverity,
    val title: String,
    val body: String,
    val private: Boolean,
    val target: NotificationTarget,
)

/**
 * Bounded, single-line, redacted text for a title or body: control characters and line breaks become spaces, runs of
 * whitespace collapse, credential-shaped fragments are masked by [Redact] and the result is cut to [max] with an ellipsis.
 */
fun notificationText(raw: String, max: Int): String {
    require(max >= 1)
    val flat = Redact.text(raw).map { if (it.isISOControl() || it == ' ' || it == ' ') ' ' else it }.joinToString("")
        .replace(Regex("\\s+"), " ").trim()
    if (flat.length <= max) return flat
    var cut = flat.take(max - 1)
    // Never leave half of a surrogate pair at the cut.
    if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut = cut.dropLast(1)
    return cut.trimEnd() + "…"
}

/** A server-provided name inside a sentence: redacted, flattened and at most [max] characters. */
internal fun notificationName(raw: String, max: Int = 48): String = notificationText(raw, max)
