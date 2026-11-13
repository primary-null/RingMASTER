package local.ringleader.core

import java.time.Duration
import java.time.Instant

enum class WatchKind(val label: String) { Node("Node"), VM("VM"), Container("Container") }

/**
 * A node or guest inside one connection. [remoteScope] is the PDM remote id, empty for direct PVE and Demo.
 * A guest is its kind and VMID (its node is a route and may change); a node is its name.
 */
data class WatchResource(val kind: WatchKind, val remoteScope: String = "", val node: String? = null, val vmid: Int? = null) {
    init {
        require(remoteScope.isEmpty() || pdmScopeOrNull(remoteScope) != null) { "The watched resource is not usable." }
        if (kind == WatchKind.Node) require(node != null && pveNodeNameOrNull(node) == node && vmid == null) { "The watched node is not usable." }
        else require(node == null && vmid != null && vmid in 1..999_999_999) { "The watched guest is not usable." }
    }
    /** Stable text form, unique inside one connection. */
    val key: String get() = "${kind.name}/$remoteScope/${node ?: vmid}"
}

fun watchResourceOf(guest: Guest): WatchResource? = when (guest.kind) {
    "VM" -> WatchKind.VM
    "Container" -> WatchKind.Container
    else -> null
}?.let { kind -> runCatching { WatchResource(kind, guest.scope, vmid = guest.id) }.getOrNull() }

fun watchResourceOf(member: RecordedMember): WatchResource = WatchResource(WatchKind.Node, node = member.name)

internal val DEMO_WATCH_BINDING: String = sha256Hex("ringmaster-watch-demo-v1")

/**
 * Where a session is connected and as whom: the saved profile (if any), kind, origin, account (user or API token ID)
 * and certificate pin. No secret is part of it. [binding] is a digest of everything but the profile id: a watch made
 * under one binding is never evaluated with data from another, so a changed address, account or pin stops its alerts
 * until the user watches the resource again.
 */
class WatchScope private constructor(val profileId: String?, val kind: String, val origin: String, val account: String, val pin: String?, val demo: Boolean) {
    val binding: String = if (demo) DEMO_WATCH_BINDING else sha256Hex("ringmaster-watch-v1\n$kind\n$origin\n$account\n${pin ?: "pki"}")
    /** The remote key the inventory rows of this scope must carry. */
    val remote: String get() = if (demo) DEMO_REMOTE_KEY else origin

    override fun equals(other: Any?): Boolean = other is WatchScope && other.profileId == profileId && other.binding == binding
    override fun hashCode(): Int = 31 * binding.hashCode() + (profileId?.hashCode() ?: 0)
    override fun toString(): String = if (demo) "WatchScope(demo)" else "WatchScope(${binding.take(8)})"

    companion object {
        val Demo = WatchScope(null, "demo", DEMO_REMOTE_KEY, "demo", null, true)

        /** @throws IllegalArgumentException when a value is not a usable connection identity. */
        fun of(profileId: String?, kind: String, origin: String, account: String, pin: String?): WatchScope {
            require(profileId == null || isCanonicalUuid(profileId)) { "The connection is not usable." }
            require(kind == DIRECT_PVE_KIND || kind == PDM_KIND) { "The connection is not usable." }
            require(serverOrigin(origin).toString() == origin) { "The connection is not usable." }
            require(account.isNotBlank() && account.length <= 256 && account.none { it.isISOControl() || it.isWhitespace() }) { "The connection is not usable." }
            val canonicalPin = pin?.replace(":", "")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            require(canonicalPin == null || canonicalPin.matches(sha256PinPattern)) { "The connection is not usable." }
            return WatchScope(profileId, kind, origin, account, canonicalPin, false)
        }

        fun of(profile: ConnectionProfile): WatchScope = of(profile.id, profile.kind, profile.origin, profile.tokenId, profile.sha256Pin)
    }
}

/**
 * One connected session of a scope. [sessionId] names the open session (window tab) and [epoch] its connection
 * ([WorkspaceState.connectionEpoch]); data from any other pair is refused once this one is open.
 */
data class WatchSession(val scope: WatchScope, val sessionId: Long, val epoch: Long)

/** Uses only one immutable snapshot; never reads the UI-thread-owned Connection. */
fun watchScopeOf(state: WorkspaceState, profileId: String? = null): WatchScope? {
    if (state.demo) return WatchScope.Demo
    if (!state.connected) return null
    val identity = state.watchIdentity ?: return null
    return runCatching { WatchScope.of(profileId, identity.kind, identity.origin, identity.account, identity.pin) }.getOrNull()
}

/** A watched resource as saved. [name] is the last name seen, for display while the resource is not listed. */
data class WatchEntry(val profileId: String?, val binding: String, val resource: WatchResource, val name: String, val addedAt: Instant) {
    init {
        require(profileId == null || isCanonicalUuid(profileId)) { "The watch connection is not usable." }
        require(binding.matches(sha256PinPattern)) { "The watch connection is not usable." }
        require(name.length <= 128 && name.none { it.isISOControl() }) { "The watch name is not usable." }
    }
    val label: String get() = when (resource.kind) {
        WatchKind.Node -> "Node ${resource.node}"
        else -> "${name.ifBlank { resource.kind.label }} (${resource.vmid})"
    }
}

const val MAX_WATCHED = 128

/** Watcher values: CPU above 75 %, memory above 95 % (Linux guests sit near 90 % from page cache alone). */
data class WatchRules(
    val cpuPercent: Double = 75.0,
    val memPercent: Double = 95.0,
    /** Consecutive counted readings needed before the stress state changes, in either direction. */
    val readings: Int = 2,
    /** Readings closer together than this are the same sample seen twice. */
    val minGap: Duration = Duration.ofMillis(2_000),
    /**
     * Proxmox refreshes its numbers about every 10 s. A reading with exactly the numbers of the one before it is that
     * same server reading and is not counted again until this much time has passed.
     */
    val heldWindow: Duration = Duration.ofSeconds(12),
    /** Readings further apart than this are not consecutive: the count starts again. */
    val maxGap: Duration = Duration.ofMinutes(5),
    /** A sample read longer ago than this (or stamped this far in the future) is refused as stale. */
    val maxSampleAge: Duration = Duration.ofSeconds(90),
    /** At most one "under load" notification per resource in this time. */
    val stressCooldown: Duration = Duration.ofMinutes(10),
    /** "Back to normal" is announced only after the resource stayed calm this long. A relapse before then is absorbed. */
    val recoveryHold: Duration = Duration.ofSeconds(60),
    /** At most one state-change notification per resource in this time; the state after it is reported once. */
    val stateCooldown: Duration = Duration.ofSeconds(60),
    /** Complete reads that must lack a resource before it is reported as no longer listed. */
    val missingReadings: Int = 2,
) {
    init { require(readings >= 1 && missingReadings >= 1 && cpuPercent.isFinite() && memPercent.isFinite()) }
}

/** Wall time for stamps and staleness, and a monotonic clock for gaps: a clock change must not fake or hide a streak. */
interface WatchClock {
    fun now(): Instant
    fun monotonicMillis(): Long
}

object SystemWatchClock : WatchClock {
    override fun now(): Instant = Instant.now()
    override fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
}

enum class WatchAvailability(val label: String) {
    /** No session of this connection is open. Nothing is known right now. */
    NotConnected("Not connected"),
    /** A session is open but no usable reading has arrived yet. */
    Waiting("Waiting for data"),
    Live("Live"),
    /** The last reads failed or are too old. The values shown are the last known ones. */
    Stale("Not updating"),
    /** Complete reads no longer list this resource. It stays watched. */
    Missing("Not listed"),
    /** The saved connection's address, account or pin changed since this was watched. Watch it again to resume. */
    BindingChanged("Connection changed"),
}

/** What one reading says about load. [Unknown] never changes the stress state. */
enum class LoadReading { Hot, Calm, Unknown }

data class WatchedStatus(
    val entry: WatchEntry,
    val availability: WatchAvailability,
    /** Last known state word from the server ("running", "stopped", "online", "offline"), or null when never known. */
    val status: String? = null,
    /** Last confirmed stress state. It is kept, not cleared, while readings are missing or unusable. */
    val stressed: Boolean = false,
    val stressedSince: Instant? = null,
    /** Last usable values, 0..100 and above; null when the server did not report them. */
    val cpuPercent: Double? = null,
    val memPercent: Double? = null,
    val lastReading: LoadReading = LoadReading.Unknown,
    /** When the last accepted reading that listed this resource was taken. */
    val lastSampleAt: Instant? = null,
) {
    /** True only while [stressed] is backed by live readings. */
    val stressedNow: Boolean get() = stressed && availability == WatchAvailability.Live
}

data class WatcherState(
    val loaded: Boolean = false,
    val watched: List<WatchedStatus> = emptyList(),
    /** Set while `watcher.json` cannot be used or the last save failed. */
    val problem: String? = null,
    val unreadable: Boolean = false,
    val readOnly: Boolean = false,
) {
    fun watching(binding: String, resource: WatchResource): Boolean = watched.any { it.entry.binding == binding && it.entry.resource == resource }
    val stressedNow: Int get() = watched.count { it.stressedNow }
}

/** One inventory read handed to the [Watcher]. */
sealed interface WatchInventoryReading {
    /**
     * A successful read taken at [readAt]. [nodesCovered] is false when the node list is not from this read.
     * [coveredScopes] null means every guest row is from this read; otherwise only guests of those PDM remotes are,
     * and the others are kept rows whose state is not known now.
     */
    data class Fresh(
        val readAt: Instant,
        val guests: List<Guest>,
        val members: List<RecordedMember>,
        val nodesCovered: Boolean,
        val coveredScopes: Set<String>? = null,
    ) : WatchInventoryReading
    /** The latest read failed, or what the session holds is marked stale. Nothing is known now. */
    data object Failed : WatchInventoryReading
}

/** One read of the recent-task feed. [localGuests] are VMIDs this session itself sent an action to. */
sealed interface WatchTaskReading {
    data class Fresh(val readAt: Instant, val tasks: List<ClusterTask>, val localGuests: Set<Int> = emptySet()) : WatchTaskReading
    data object Failed : WatchTaskReading
}

enum class WatchObservation {
    Accepted,
    /** Already seen (same read time, or older than the last one). Nothing was counted. */
    Duplicate,
    /** Read too long ago, or stamped in the future. Nothing was counted. */
    Stale,
    /** This session is not the open one for its connection (closed, replaced, or an older epoch). */
    NoSession,
    /** The reading carries rows of another connection. Nothing was counted. */
    WrongRemote,
}

sealed interface WatchResult {
    data object Ok : WatchResult
    data class Error(val message: String) : WatchResult
}

/** Percent of [used] in [total], or null when either is missing or not usable. */
internal fun usagePercent(used: Long?, total: Long?): Double? =
    if (used == null || total == null || total <= 0L || used < 0L) null else used * 100.0 / total

/** Percent from an API 0..1 fraction, or null for an absent, NaN, infinite or negative value. */
internal fun cpuPercentOf(fraction: Double?): Double? = fraction?.takeIf { it.isFinite() && it >= 0.0 }?.times(100.0)

/**
 * Hot when any known value is above its limit. Calm only when both values are known and neither is: a missing value
 * cannot vouch for a resource that may be stressed on exactly that value.
 */
internal fun loadReading(cpuPercent: Double?, memPercent: Double?, rules: WatchRules): LoadReading = when {
    (cpuPercent != null && cpuPercent > rules.cpuPercent) || (memPercent != null && memPercent > rules.memPercent) -> LoadReading.Hot
    cpuPercent != null && memPercent != null -> LoadReading.Calm
    else -> LoadReading.Unknown
}

internal val knownGuestStates = setOf("running", "stopped", "paused", "suspended")
internal val knownNodeStates = setOf("online", "offline")

/**
 * The reading a session's state holds, or null when it holds none (not connected, nothing read yet).
 * A state marked stale or carrying a read error is [WatchInventoryReading.Failed]: its rows are the last good ones,
 * not current facts. The read time is [WorkspaceState.updatedAt], which changes only with a successful read, so a
 * recomposition or an unrelated state change yields the same reading and is counted once.
 */
fun watchInventoryOf(state: WorkspaceState): WatchInventoryReading? {
    if (!state.connected && !state.demo) return null
    val overview = state.overview
    val readAt = state.updatedAt
    if (state.stale || (state.error != null && !state.loading)) return WatchInventoryReading.Failed
    if (overview == null || readAt == null) return null
    val pdm = state.kind == PDM_KIND && !state.demo
    return WatchInventoryReading.Fresh(
        readAt = readAt, guests = overview.guests, members = overview.members,
        // A PDM fleet read carries no node rows; a direct read whose recorded index failed keeps the previous ones.
        nodesCovered = !pdm && !overview.indexStale && overview.indexError == null,
        coveredScopes = if (pdm) overview.remotes.filter { !it.stale && it.error == null }.map { it.scope }.toSet() else null,
    )
}

/** The task-feed reading a session's state holds, or null when it holds none (PDM sessions never read that feed). */
fun watchTasksOf(state: WorkspaceState): WatchTaskReading? {
    if (!state.connected && !state.demo) return null
    if (state.tasksStale || state.tasksError != null) return WatchTaskReading.Failed
    val tasks = state.tasks ?: return null
    val readAt = state.tasksUpdatedAt ?: return null
    return WatchTaskReading.Fresh(readAt, tasks, state.submissions.filter { !it.simulated }.map { it.vmid }.toSet())
}
