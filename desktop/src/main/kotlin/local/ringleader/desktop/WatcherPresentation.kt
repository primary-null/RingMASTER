package local.ringleader.desktop

import androidx.compose.runtime.Immutable
import java.time.Instant
import local.ringleader.core.ResourceId

/*
 * UI-only presentation of watched guests and nodes, kept running on the desktop. The engine polls;
 * this screen only shows what the parent maps from its state and calls back. It never starts a poll of its own.
 */

enum class WatchTargetKind { Guest, Node }

/** Whether the engine can see a watched target now. A target that disappeared stays listed until it is unwatched. */
sealed interface WatchTargetState {
    data object Live : WatchTargetState
    /** Not read yet since the watch started or the app started. */
    data object Pending : WatchTargetState
    /** Gone from the inventory, or its server cannot be reached. [lastSeen] when known. */
    data class Unavailable(val reason: String, val lastSeen: Instant? = null) : WatchTargetState
}

@Immutable data class WatchTargetView(
    /** The engine's stable key; passed back in every callback. */
    val key: String,
    val kind: WatchTargetKind,
    /** Identifying: the guest or node name. */
    val name: String,
    /** The server or profile (identifying) and its stable key. */
    val sourceId: String,
    val sourceLabel: String,
    /** Guests: "VM" or "CT" and the VMID. */
    val guestKind: String? = null,
    val vmid: Int? = null,
    val template: Boolean = false,
    /** Guests: the node it runs on (identifying). */
    val node: String? = null,
    val resource: ResourceId? = null,
    val availability: WatchTargetState = WatchTargetState.Pending,
    /** The last state read ("running", "stopped", "online", "offline"). */
    val status: String? = null,
    val healthy: Boolean = false,
    /** Notices about this target that are still unread. */
    val unread: Int = 0,
    /** An incident is open on it now (node offline, guest stopped unexpectedly). */
    val incident: Boolean = false,
    /** Generic summary of the last change ("Stopped", "Back online") and when. */
    val lastChange: String? = null,
    val lastChangeAt: Instant? = null,
    /** Since when the engine has watched it. */
    val since: Instant? = null,
    /** It belongs to the connection in view (only then can Open route to it). */
    val inView: Boolean = true,
)

/** What the engine is doing, in a few words for the header. */
sealed interface WatcherActivity {
    /** At least one watched target is read live. [intervalSeconds] when the refresh interval is known. */
    data class Watching(val intervalSeconds: Int? = null) : WatcherActivity
    data class Paused(val reason: String) : WatcherActivity
    data object Off : WatcherActivity
}

@Immutable data class WatcherView(
    val targets: List<WatchTargetView>,
    val activity: WatcherActivity,
    val now: Instant,
    /** Guest and node keys watched, for the Watch toggles on the guest page and inspector. */
    val watchedGuests: Set<ResourceId> = targets.mapNotNull { it.resource }.toSet(),
    val watchedNodes: Set<String> = emptySet(),
    /** The watch list cannot be read or saved. Generic text, shown as is. */
    val problem: String? = null,
)

/** What the Watcher UI can ask for. A null action hides its control. */
@Immutable class WatcherActions(
    val onUnwatch: ((key: String) -> Unit)? = null,
    /** Called with the guest and its connection key (null: the connection in view). */
    val onWatchGuest: ((resource: ResourceId, name: String, watch: Boolean) -> Unit)? = null,
    val onWatchNode: ((node: String, watch: Boolean) -> Unit)? = null,
    /** Told after the window routed to a target, for the engine to mark its notices read. */
    val onOpened: ((WatchTargetView) -> Unit)? = null,
)

// ---- Pure presentation logic ----

/** Order: open incidents first, then unavailable, then the rest by name. Stable for equal names by key. */
fun orderWatchTargets(targets: List<WatchTargetView>): List<WatchTargetView> = targets.sortedWith(
    compareByDescending<WatchTargetView> { it.incident }
        .thenByDescending { it.availability is WatchTargetState.Unavailable }
        .thenBy { it.kind }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        .thenBy { it.key })

/** Header counts: watched, with incidents, unavailable. */
data class WatchCounts(val total: Int, val incidents: Int, val unavailable: Int)

fun watchCounts(targets: List<WatchTargetView>) = WatchCounts(targets.size, targets.count { it.incident },
    targets.count { it.availability is WatchTargetState.Unavailable })

/** The status line under a target's name; generic words only (names are shown, and blurred, separately). */
fun watchStatusLine(target: WatchTargetView, now: Instant): String = when (val a = target.availability) {
    WatchTargetState.Pending -> "Waiting for the first read"
    is WatchTargetState.Unavailable -> a.reason + (a.lastSeen?.let { " · last seen ${noticeAge(it, now)}" } ?: "")
    WatchTargetState.Live -> listOfNotNull(target.lastChange?.let { c -> c + (target.lastChangeAt?.let { " ${noticeAge(it, now)}" } ?: "") },
        target.since?.let { "watched since ${noticeAge(it, now).removeSuffix(" ago")}" }.takeIf { target.lastChange == null }).joinToString(" · ").ifEmpty { "No change seen" }
}

/** The header line: "Watching every 15 s", "Paused · Window hidden", "Off". */
fun watcherActivityLabel(activity: WatcherActivity): String = when (activity) {
    is WatcherActivity.Watching -> activity.intervalSeconds?.let { "Watching every $it s" } ?: "Watching"
    is WatcherActivity.Paused -> "Paused · ${activity.reason}"
    WatcherActivity.Off -> "Off"
}

/** True when a target can be opened in the window: live, and (for a guest) present in the connection in view. */
fun watchTargetOpenable(target: WatchTargetView, guestPresent: (ResourceId) -> Boolean): Boolean = when {
    target.availability !is WatchTargetState.Live || !target.inView -> false
    target.kind == WatchTargetKind.Guest -> target.resource?.let(guestPresent) == true
    else -> true
}
