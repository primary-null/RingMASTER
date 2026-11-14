package local.ringleader.core

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Watcher: the watched nodes and guests, their load and state, and the events that follow from the inventory,
 * task and schedule snapshots the app already reads. It reads nothing from a server itself and holds no credential:
 * every fact comes in through [observeInventory], [observeTasks] and [observeSchedules], and leaves as a
 * [NotificationEvent] with a deterministic identity.
 *
 * Unknown stays unknown. A failed read, a stale or duplicate sample, a resource that is not listed, a value the server
 * did not send: none of them clears a stress state, announces a recovery or reports a state change. Only a fresh
 * reading that lists the resource with a known state can.
 *
 * Sessions. Data is accepted only from the one [WatchSession] opened last for its connection binding. A closed or
 * replaced session, or an older epoch, is refused, so a session that disconnected can never post again. Watches are
 * bound to the binding they were made under ([WatchScope.binding]).
 *
 * Thread-safe; every call is short and does no network I/O. [watch], [unwatch] and [forgetProfile] save one small file.
 */
class Watcher(
    private val store: WatchStore,
    private val sink: NotificationSink,
    private val clock: WatchClock = SystemWatchClock,
    private val rules: WatchRules = WatchRules(),
) {
    private class Track(var entry: WatchEntry) {
        var listedInSession = false
        var everListed = false
        var uncovered = false
        var missing = false
        var missingStreak = 0
        var unlistedNotified = false
        var status: String? = null
        var notifiedStatus: String? = null
        var lastStateAlert: Long? = null
        var stressed = false
        var stressedSince: Instant? = null
        var streak = 0
        var lastCounted: Long? = null
        var lastFingerprint: Any? = null
        var alerted = false
        var alertOnset: Instant? = null
        var lastStressAlert: Long? = null
        var calmSince: Long? = null
        var cpu: Double? = null
        var mem: Double? = null
        var lastReading = LoadReading.Unknown
        var lastSampleAt: Instant? = null

        /** A new or ended session: what was confirmed is kept, every count in progress starts again. */
        fun breakContinuity() {
            listedInSession = false; uncovered = false; missingStreak = 0; streak = 0; lastCounted = null; lastFingerprint = null; calmSince = null
        }
    }

    private class TaskMark(val outcome: TaskOutcome, val warnings: Boolean)

    private class Live(var session: WatchSession) {
        var lastReadAt: Instant? = null
        var lastAcceptedMono: Long? = null
        var failed = false
        var timedOut = false
        var lastTaskReadAt: Instant? = null
        var tasksSeeded = false
        val tasks = LinkedHashMap<String, TaskMark>()
    }

    private val lock = Any()
    private val mutable = MutableStateFlow(WatcherState())
    val state: StateFlow<WatcherState> = mutable.asStateFlow()
    private val tracks = LinkedHashMap<Pair<String, WatchResource>, Track>()
    private val sessions = HashMap<String, Live>()
    private val retired = HashMap<String, ArrayDeque<Pair<Long, Long>>>()
    /** Profile id -> the binding its latest opened session had. An entry saved under another binding is not evaluated. */
    private val profileBindings = HashMap<String, String>()
    private var loaded = false
    private var saveProblem: GuardedStoreException? = null
    /** Scheduled runs that reached their final phase before this moment are backlog on the first schedule snapshot. */
    private val baseline: Instant = clock.now()
    private var schedulesSeeded = false
    private val runPhases = HashMap<String, RunPhase>()

    /** Reads `watcher.json` once. On failure nothing is watched from disk, the file is left alone and the problem is reported. */
    fun load() = synchronized(lock) {
        if (loaded) return@synchronized
        try { store.load().forEach { tracks[it.binding to it.resource] = Track(it) } } catch (_: GuardedStoreException) { }
        loaded = true
        publish()
    }

    /** Tells the Watcher the current binding of saved profiles, so watches of an edited profile show as changed before it connects. */
    fun profilesChanged(profiles: List<ConnectionProfile>) = synchronized(lock) {
        profiles.forEach { profile -> runCatching { WatchScope.of(profile) }.getOrNull()?.let { profileBindings[profile.id] = it.binding } }
        publish()
    }

    /**
     * Starts watching [resource] of [scope]. Watches of a saved profile are written to `watcher.json` first; when that
     * fails nothing is added and the message says so. Demo watches and watches of a connection that is not a saved
     * profile last until RingMASTER quits (Demo: until the demo ends).
     */
    fun watch(scope: WatchScope, resource: WatchResource, name: String): WatchResult = synchronized(lock) {
        if (!loaded) return WatchResult.Error("The watch list is not loaded yet.")
        val key = scope.binding to resource
        if (key in tracks) return WatchResult.Ok
        if (tracks.size >= MAX_WATCHED) return WatchResult.Error("At most $MAX_WATCHED resources can be watched. Stop watching one first.")
        val entry = WatchEntry(scope.profileId, scope.binding, resource, notificationText(name, 128), clock.now())
        if (!scope.demo && scope.profileId != null) save(savedEntries() + entry)?.let { return WatchResult.Error(it) }
        tracks[key] = Track(entry)
        publish()
        WatchResult.Ok
    }

    /** Stops watching. For a saved watch the file is written first; when that fails the watch stays. */
    fun unwatch(binding: String, resource: WatchResource): WatchResult = synchronized(lock) {
        val track = tracks[binding to resource] ?: return WatchResult.Ok
        if (track.entry.profileId != null) save(savedEntries().filter { it !== track.entry })?.let { return WatchResult.Error(it) }
        tracks.remove(binding to resource)
        publish()
        WatchResult.Ok
    }

    /** A saved profile was deleted: its watches go with it. */
    fun forgetProfile(profileId: String): WatchResult = synchronized(lock) {
        val gone = tracks.filterValues { it.entry.profileId == profileId }.keys
        if (gone.isEmpty()) return WatchResult.Ok
        save(savedEntries().filter { it.profileId != profileId })?.let { return WatchResult.Error(it) }
        gone.forEach { tracks.remove(it) }
        profileBindings.remove(profileId)
        publish()
        WatchResult.Ok
    }

    /**
     * Explicit user action for an unreadable `watcher.json`: the file is moved aside unchanged and the watches in
     * memory are saved as the new file. Returns where the old file went.
     * @throws GuardedStoreException when the file is not unreadable or cannot be moved; nothing changes then.
     */
    fun setAsideAndStartEmpty(): Path = synchronized(lock) {
        val target = store.setAside(clock.now())
        saveProblem = null
        save(savedEntries())
        publish()
        target
    }

    /**
     * Makes [session] the one source for its connection. False when it was closed or replaced before, or when a newer
     * epoch of the same session is already open. Opening is not an observation: nothing is known until a reading arrives.
     */
    fun open(session: WatchSession): Boolean = synchronized(lock) {
        val binding = session.scope.binding
        val mark = session.sessionId to session.epoch
        if (retired[binding]?.contains(mark) == true) return false
        val current = sessions[binding]
        if (current != null) {
            if (current.session == session) return true
            if (current.session.sessionId == session.sessionId && current.session.epoch == session.epoch) {
                // The same connection, now known under another saved profile (or none): continue without a break.
                current.session = session
                session.scope.profileId?.let { profileBindings[it] = binding }
                publish()
                return true
            }
            if (current.session.sessionId == session.sessionId && current.session.epoch > session.epoch) return false
            retire(current.session)
        }
        sessions[binding] = Live(session)
        session.scope.profileId?.let { profileBindings[it] = binding }
        tracks.values.filter { it.entry.binding == binding }.forEach { it.breakContinuity() }
        publish()
        true
    }

    /** The session ended (disconnect, expiry, another connection in its place). Nothing from it is accepted afterwards. */
    fun close(session: WatchSession) = synchronized(lock) {
        val binding = session.scope.binding
        if (sessions[binding]?.session != session) { remember(session); return@synchronized }
        sessions.remove(binding)
        retire(session)
        if (session.scope.demo) tracks.keys.filter { it.first == binding }.forEach { tracks.remove(it) }
        else tracks.values.filter { it.entry.binding == binding }.forEach { it.breakContinuity() }
        publish()
    }

    /** One inventory reading of [session]. See [WatchObservation]. */
    fun observeInventory(session: WatchSession, reading: WatchInventoryReading): WatchObservation {
        val events = mutableListOf<NotificationEvent>()
        val result = synchronized(lock) {
            val live = sessions[session.scope.binding]?.takeIf { it.session == session } ?: return WatchObservation.NoSession
            val mine = tracks.values.filter { it.entry.binding == session.scope.binding }
            when (reading) {
                WatchInventoryReading.Failed -> {
                    if (!live.failed) { live.failed = true; mine.forEach { it.streak = 0; it.lastCounted = null; it.calmSince = null; it.missingStreak = 0 }; publish() }
                    WatchObservation.Accepted
                }
                is WatchInventoryReading.Fresh -> {
                    val last = live.lastReadAt
                    if (last != null && !reading.readAt.isAfter(last)) return WatchObservation.Duplicate
                    val age = Duration.between(reading.readAt, clock.now())
                    if (age > rules.maxSampleAge || age.negated() > rules.maxSampleAge) return WatchObservation.Stale
                    val remote = session.scope.remote
                    if (reading.guests.any { it.remote != remote } || reading.members.any { it.remote != remote }) return WatchObservation.WrongRemote
                    val mono = clock.monotonicMillis()
                    live.lastReadAt = reading.readAt; live.lastAcceptedMono = mono; live.failed = false; live.timedOut = false
                    if (mine.isNotEmpty()) {
                        // A resource listed twice is ambiguous: it is treated as not covered by this reading.
                        val guests = reading.guests.groupBy { watchResourceOf(it) }
                        val members = reading.members.groupBy { it.name }
                        mine.forEach { evaluate(it, session.scope, reading, guests, members, mono, events) }
                    }
                    publish()
                    WatchObservation.Accepted
                }
            }
        }
        events.forEach { sink.post(it) }
        return result
    }

    /**
     * One reading of the recent-task feed. The first one of a session only records what is there: tasks that finished
     * before RingMASTER looked are not announced. Later readings announce each task's change once.
     */
    fun observeTasks(session: WatchSession, reading: WatchTaskReading): WatchObservation {
        val events = mutableListOf<NotificationEvent>()
        synchronized(lock) {
            val live = sessions[session.scope.binding]?.takeIf { it.session == session } ?: return WatchObservation.NoSession
            if (reading !is WatchTaskReading.Fresh) return WatchObservation.Accepted
            val last = live.lastTaskReadAt
            if (last != null && !reading.readAt.isAfter(last)) return WatchObservation.Duplicate
            val age = Duration.between(reading.readAt, clock.now())
            if (age > rules.maxSampleAge || age.negated() > rules.maxSampleAge) return WatchObservation.Stale
            if (reading.tasks.any { it.remote != session.scope.remote }) return WatchObservation.WrongRemote
            live.lastTaskReadAt = reading.readAt
            val seeding = !live.tasksSeeded
            live.tasksSeeded = true
            reading.tasks.forEach { task ->
                val previous = live.tasks.put(task.upid, TaskMark(task.outcome, taskHasWarnings(task)))
                if (!seeding) events += taskEvents(session.scope, task, previous?.outcome, previous?.warnings == true, previous == null, reading.localGuests)
            }
            if (live.tasks.size > MAX_TRACKED_TASKS) {
                val current = reading.tasks.map { it.upid }.toSet()
                val drop = live.tasks.keys.filter { it !in current }.take(live.tasks.size - MAX_TRACKED_TASKS)
                drop.forEach { live.tasks.remove(it) }
            }
        }
        events.forEach { sink.post(it) }
        return WatchObservation.Accepted
    }

    /**
     * One scheduler snapshot. Each run is announced once, when it reaches a final phase. On the first snapshot, runs
     * that were final before this Watcher existed are history and stay silent; runs finalised since (an interrupted run
     * recovered at startup, a run missed while RingMASTER was closed) are announced.
     */
    fun observeSchedules(snapshot: SchedulerState) {
        val events = mutableListOf<NotificationEvent>()
        synchronized(lock) {
            if (!snapshot.loaded || snapshot.problem != null) return
            val names = snapshot.document.plans.associate { it.id to it.name }
            val seeding = !schedulesSeeded
            snapshot.document.runs.forEach { run ->
                val previous = runPhases.put(run.id, run.phase)
                if (run.phase.inFlight || previous == run.phase) return@forEach
                if (seeding && run.updatedAt.isBefore(baseline)) return@forEach
                events += scheduleEvent(run, names[run.planId])
            }
            schedulesSeeded = true
            val present = snapshot.document.runs.map { it.id }.toSet()
            runPhases.keys.retainAll(present)
        }
        events.forEach { sink.post(it) }
    }

    /** Call now and then (the runtime does, every few seconds): marks a session that stopped delivering readings as not updating. */
    fun tick() = synchronized(lock) {
        val mono = clock.monotonicMillis()
        var changed = false
        sessions.values.forEach { live ->
            val last = live.lastAcceptedMono ?: return@forEach
            if (!live.timedOut && mono - last > rules.maxGap.toMillis()) { live.timedOut = true; changed = true }
        }
        if (changed) publish()
    }

    private fun retire(session: WatchSession) {
        remember(session)
        if (sessions[session.scope.binding]?.session == session) sessions.remove(session.scope.binding)
    }

    private fun remember(session: WatchSession) {
        val list = retired.getOrPut(session.scope.binding) { ArrayDeque() }
        val mark = session.sessionId to session.epoch
        if (mark !in list) list.addLast(mark)
        while (list.size > 32) list.removeFirst()
    }

    private fun savedEntries(): List<WatchEntry> = tracks.values.map { it.entry }.filter { it.profileId != null && it.binding != DEMO_WATCH_BINDING }

    /** Null when saved; otherwise the message, with the problem kept for the state. */
    private fun save(entries: List<WatchEntry>): String? = try {
        store.save(entries)
        saveProblem = null
        null
    } catch (e: GuardedStoreException) {
        saveProblem = e
        publish()
        e.message ?: "The watch list could not be saved."
    } catch (e: Exception) {
        saveProblem = GuardedStoreException("The watch list could not be saved (${e.javaClass.simpleName}).")
        publish()
        saveProblem!!.message
    }

    private fun evaluate(
        track: Track, scope: WatchScope, reading: WatchInventoryReading.Fresh,
        guests: Map<WatchResource?, List<Guest>>, members: Map<String, List<RecordedMember>>, mono: Long, events: MutableList<NotificationEvent>,
    ) {
        val resource = track.entry.resource
        val node = resource.kind == WatchKind.Node
        val rows = if (node) members[resource.node].orEmpty().size else guests[resource].orEmpty().size
        val covered = rows <= 1 && (if (node) reading.nodesCovered else reading.coveredScopes?.contains(resource.remoteScope) != false)
        if (!covered) {
            // Not part of this read: nothing is known about it now. Counts in progress end; what was confirmed stays.
            track.uncovered = true; track.streak = 0; track.lastCounted = null; track.calmSince = null; track.missingStreak = 0
            return
        }
        track.uncovered = false
        val member = if (node) members[resource.node]?.singleOrNull() else null
        val guest = if (node) null else guests[resource]?.singleOrNull()
        if (member == null && guest == null) {
            track.streak = 0; track.lastCounted = null; track.calmSince = null
            track.missingStreak++
            if (track.missingStreak >= rules.missingReadings) {
                track.missing = true
                if (track.everListed && !track.unlistedNotified) {
                    track.unlistedNotified = true
                    events += watchEvent(scope, track, "unlisted@${reading.readAt.toEpochMilli()}", NotificationCategory.WatchState, NotificationSeverity.Warning,
                        "${label(track)} is no longer listed", "The server no longer lists it. Its state is not known. It is still watched.")
                }
            }
            return
        }
        track.missing = false; track.missingStreak = 0; track.listedInSession = true; track.everListed = true; track.lastSampleAt = reading.readAt
        guest?.name?.takeIf { it.isNotBlank() }?.let { name ->
            val clean = notificationText(name, 128)
            if (clean != track.entry.name) track.entry = track.entry.copy(name = clean)
        }
        if (track.unlistedNotified) {
            track.unlistedNotified = false
            events += watchEvent(scope, track, "relisted@${reading.readAt.toEpochMilli()}", NotificationCategory.WatchState, NotificationSeverity.Info,
                "${label(track)} is listed again", "The server lists it again.")
        }
        val status = (member?.status ?: guest!!.status).trim().lowercase()
        val known = status in (if (node) knownNodeStates else knownGuestStates)
        if (!known) {
            // "unknown", blank or a word RingMASTER does not know: no state change, and no judgement about load.
            track.cpu = null; track.mem = null; track.lastReading = LoadReading.Unknown; track.streak = 0; track.calmSince = null
            return
        }
        track.status = status
        val notified = track.notifiedStatus
        if (notified == null) track.notifiedStatus = status
        else if (notified != status && elapsed(track.lastStateAlert, mono, rules.stateCooldown)) {
            track.notifiedStatus = status; track.lastStateAlert = mono
            val severity = when (status) {
                "offline" -> NotificationSeverity.Error
                "running", "online" -> NotificationSeverity.Success
                else -> NotificationSeverity.Warning
            }
            events += watchEvent(scope, track, "state:$notified>$status@${reading.readAt.toEpochMilli()}", NotificationCategory.WatchState, severity,
                "${label(track)} is now $status", "It was $notified before.")
        }
        if (status != (if (node) "online" else "running")) {
            // A known fact: it is not running, so it is not under load. Cleared without a "back to normal" notice; the state change said it.
            track.stressed = false; track.stressedSince = null; track.alerted = false; track.alertOnset = null
            track.streak = 0; track.lastCounted = null; track.calmSince = null; track.cpu = null; track.mem = null; track.lastReading = LoadReading.Unknown
            return
        }
        val cpu = cpuPercentOf(member?.cpuFraction ?: guest?.metrics?.cpuFraction)
        val mem = if (node) usagePercent(member!!.memoryBytes, member.maxMemoryBytes) else usagePercent(guest!!.metrics?.memoryBytes, guest.metrics?.memoryLimitBytes)
        val load = loadReading(cpu, mem, rules)
        track.cpu = cpu; track.mem = mem; track.lastReading = load
        val fingerprint: Any = if (node) Triple(member!!.cpuFraction, member.memoryBytes, member.uptimeSeconds)
            else Triple(guest!!.metrics?.cpuFraction, guest.metrics?.memoryBytes, guest.metrics?.uptimeSeconds)
        count(track, load, fingerprint, mono, reading.readAt)
        // Even one hot reading interrupts the continuous calm period. It need not flip
        // the two-reading stress state to invalidate a pending recovery notice.
        if (load == LoadReading.Hot) track.calmSince = null
        when {
            load == LoadReading.Unknown -> track.calmSince = null
            load == LoadReading.Hot && track.stressed && !track.alerted && elapsed(track.lastStressAlert, mono, rules.stressCooldown) -> {
                val onset = track.stressedSince ?: reading.readAt
                track.alerted = true; track.alertOnset = onset; track.lastStressAlert = mono
                events += watchEvent(scope, track, "stress@${onset.toEpochMilli()}", NotificationCategory.WatchStress, NotificationSeverity.Warning,
                    "${label(track)} is under load", loadText(cpu, mem))
            }
            load == LoadReading.Calm && !track.stressed && track.alerted -> {
                val since = track.calmSince ?: mono.also { track.calmSince = it }
                if (mono - since >= rules.recoveryHold.toMillis()) {
                    val onset = track.alertOnset
                    track.alerted = false; track.alertOnset = null; track.calmSince = null
                    val minutes = onset?.let { Duration.between(it, reading.readAt).toMinutes() }?.takeIf { it in 1..100_000 }
                    events += watchEvent(scope, track, "recovered@${(onset ?: reading.readAt).toEpochMilli()}", NotificationCategory.WatchStress, NotificationSeverity.Success,
                        "${label(track)} is back to normal", listOfNotNull(loadText(cpu, mem), minutes?.let { "Under load for about $it min." }).joinToString(". "))
                }
            }
        }
    }

    /** Two-reading rule on a monotonic clock: repeated server readings and unknown readings are not counted. */
    private fun count(track: Track, load: LoadReading, fingerprint: Any, mono: Long, readAt: Instant) {
        if (load == LoadReading.Unknown) { track.streak = 0; return }
        val last = track.lastCounted
        if (last != null) {
            val gap = mono - last
            if (gap < rules.minGap.toMillis()) return
            if (fingerprint == track.lastFingerprint && gap < rules.heldWindow.toMillis()) return
            if (gap > rules.maxGap.toMillis()) track.streak = 0
        }
        track.lastCounted = mono; track.lastFingerprint = fingerprint
        val hot = load == LoadReading.Hot
        track.streak = if (hot == track.stressed) 0 else track.streak + 1
        if (track.streak >= rules.readings) {
            track.stressed = hot; track.streak = 0
            if (hot) { track.stressedSince = readAt; track.calmSince = null } else { track.stressedSince = null; track.calmSince = mono }
        }
    }

    private fun elapsed(since: Long?, mono: Long, needed: Duration): Boolean = since == null || mono - since >= needed.toMillis()

    private fun label(track: Track): String = notificationName(track.entry.label, 60)

    private fun loadText(cpu: Double?, mem: Double?): String =
        listOfNotNull(cpu?.let { "CPU ${it.roundToInt().coerceIn(0, 9999)} %" }, mem?.let { "memory ${it.roundToInt().coerceIn(0, 9999)} %" }).joinToString(", ")

    private fun watchEvent(scope: WatchScope, track: Track, occurrence: String, category: NotificationCategory, severity: NotificationSeverity, title: String, body: String) =
        NotificationEvent(NotificationIdentity("watch", scope.binding, track.entry.resource.key, occurrence), category, severity, title, body,
            NotificationTarget.Resource(scope.profileId, scope.binding, track.entry.resource), demo = scope.demo)

    /** Caller holds [lock]. */
    private fun publish() {
        val problem = store.problem() ?: saveProblem
        mutable.value = WatcherState(
            loaded = loaded,
            watched = tracks.values.map { track ->
                val live = sessions[track.entry.binding]
                val availability = when {
                    track.entry.profileId != null && profileBindings[track.entry.profileId]?.let { it != track.entry.binding } == true -> WatchAvailability.BindingChanged
                    live == null -> WatchAvailability.NotConnected
                    live.failed || live.timedOut || track.uncovered -> WatchAvailability.Stale
                    track.missing -> WatchAvailability.Missing
                    !track.listedInSession -> WatchAvailability.Waiting
                    else -> WatchAvailability.Live
                }
                WatchedStatus(track.entry, availability, track.status, track.stressed, track.stressedSince, track.cpu, track.mem, track.lastReading, track.lastSampleAt)
            },
            problem = problem?.message, unreadable = problem?.unreadable == true, readOnly = problem?.readOnly == true,
        )
    }
}

internal const val MAX_TRACKED_TASKS = 2000

internal val NEW_GUEST_TASKS = setOf("qmcreate", "vzcreate", "qmclone", "vzclone")
internal val MIGRATION_TASKS = setOf("qmigrate", "vzmigrate")
internal val PACKAGE_TASKS = setOf("aptupdate", "apt-update", "aptupgrade", "apt-upgrade")
internal val GUEST_POWER_TASKS = setOf(
    "qmstart", "qmstop", "qmshutdown", "qmreboot", "qmreset", "qmsuspend", "qmresume",
    "vzstart", "vzstop", "vzshutdown", "vzreboot", "vzsuspend", "vzresume",
)

private fun taskSubject(task: ClusterTask): String {
    val id = notificationName(task.subjectId, 40)
    return if (id.isEmpty()) "Node ${task.node}" else "$id on node ${task.node}"
}

/**
 * The events one task row gives, given what the feed showed for it before ([previous] outcome, null when [isNew]).
 * Built only from the feed's own fields (type, subject id, node, account and outcome class). The server's status text
 * is not copied: it is free text and may quote paths or commands; the Activity page shows it for the task itself.
 * Only an exit status of OK (or OK with warnings) is ever described as finished.
 */
internal fun taskEvents(scope: WatchScope, task: ClusterTask, previous: TaskOutcome?, previousWarnings: Boolean, isNew: Boolean, localGuests: Set<Int>): List<NotificationEvent> {
    val type = task.type.trim().lowercase()
    val shownType = notificationName(task.type, 32)
    val warnings = taskHasWarnings(task)
    val target = runCatching { NotificationTarget.Task(scope.profileId, scope.binding, task.upid) }.getOrDefault(NotificationTarget.None)
    fun event(kind: String, category: NotificationCategory, severity: NotificationSeverity, title: String, body: String) =
        NotificationEvent(NotificationIdentity("task", scope.binding, task.upid, kind), category, severity, title, body, target, demo = scope.demo)
    val out = mutableListOf<NotificationEvent>()
    if (task.outcome == TaskOutcome.Unsuccessful && previous != TaskOutcome.Unsuccessful) {
        out += if (type == "vzdump") event("failed", NotificationCategory.BackupFailure, NotificationSeverity.Error, "Backup failed", "${taskSubject(task)}. Open Activity for the task's status.")
            else event("failed", NotificationCategory.TaskFailure, NotificationSeverity.Error, "Task failed: $shownType", "${taskSubject(task)}. Open Activity for the task's status.")
    }
    if (task.outcome == TaskOutcome.Succeeded && previous != TaskOutcome.Succeeded) {
        if (type in NEW_GUEST_TASKS) {
            val what = if (type.startsWith("qm")) "VM" else "container"
            out += event("created", NotificationCategory.NewGuest, NotificationSeverity.Info, "New $what ${notificationName(task.subjectId, 20)}".trim(),
                "The ${if (type.endsWith("clone")) "clone" else "create"} task finished on node ${task.node}${if (warnings) " with warnings" else ""}.")
        }
        if (type in MIGRATION_TASKS) {
            out += event("migrated", NotificationCategory.Migration, if (warnings) NotificationSeverity.Warning else NotificationSeverity.Success, "Migration finished: ${notificationName(task.subjectId, 20)}".trim().trimEnd(':'),
                "The migration task started on node ${task.node} finished${if (warnings) " with warnings" else ""}.")
        }
    }
    if (task.outcome == TaskOutcome.Succeeded && warnings && !(previous == TaskOutcome.Succeeded && previousWarnings)) {
        out += event("warnings", NotificationCategory.TaskWarnings, NotificationSeverity.Warning, "Task finished with warnings: $shownType", "${taskSubject(task)}.")
    }
    if (isNew) {
        if ("fence" in type) out += event("fence", NotificationCategory.HaFencing, NotificationSeverity.Error, "Fencing task seen", "A task of type $shownType appeared on node ${task.node}.")
        if (type in PACKAGE_TASKS) out += event("package", NotificationCategory.PackageUpdates, NotificationSeverity.Info, "Package task seen", "A task of type $shownType appeared on node ${task.node}.")
        val vmid = task.subjectId.toIntOrNull()
        if (type in GUEST_POWER_TASKS && vmid != null && task.user != scope.account && vmid !in localGuests) {
            out += event("power", NotificationCategory.ExternalPower, NotificationSeverity.Info, "Guest $vmid: $shownType by another account",
                "Started by ${notificationName(taskUserLabel(task.user), 60)} on node ${task.node}.")
        }
    }
    return out
}

/**
 * The notification for one finished scheduled run. Every phase keeps its own wording: only [RunPhase.Succeeded] says
 * OK; Submitted, Unknown (interrupted), Failed, Rejected, Missed, Skipped and Not sent are never merged or softened.
 * The run's free-text detail is not copied.
 */
internal fun scheduleEvent(run: ScheduleRun, planName: String?): NotificationEvent {
    val name = notificationName(planName ?: "Schedule", 60)
    val guest = notificationName(run.guest, 80)
    val action = run.action.title
    data class Copy(val category: NotificationCategory, val severity: NotificationSeverity, val title: String, val body: String, val native: Boolean = true)
    val problem = NotificationCategory.ScheduleProblem
    val copy = when (run.phase) {
        RunPhase.Succeeded -> Copy(NotificationCategory.ScheduleOutcome, NotificationSeverity.Success, "$name: $action finished OK", "$guest.")
        RunPhase.NoChange -> Copy(NotificationCategory.ScheduleOutcome, NotificationSeverity.Info, "$name: nothing to do", "$guest was already in that state. Nothing was sent.", native = false)
        RunPhase.Cancelled -> Copy(NotificationCategory.ScheduleOutcome, NotificationSeverity.Info, "$name: run cancelled", "$guest. The schedule was edited, paused or deleted before sending. Nothing was sent.", native = false)
        RunPhase.Warnings -> Copy(problem, NotificationSeverity.Warning, "$name: $action finished with warnings", "$guest. Open the task log to see them.")
        RunPhase.Failed -> Copy(problem, NotificationSeverity.Error, "$name: $action failed", "$guest. Open Schedules for the run.")
        RunPhase.Rejected -> Copy(problem, NotificationSeverity.Error, "$name: $action refused by the server", "$guest. Nothing was started.")
        RunPhase.Submitted -> Copy(problem, NotificationSeverity.Warning, "$name: $action submitted, not confirmed", "$guest. The server accepted the task; its result is not known. Check the task list.")
        RunPhase.Unknown -> Copy(problem, NotificationSeverity.Error, "$name: $action interrupted, result unknown", "$guest. The request may have reached the server. It was not sent again. Check the task list.")
        RunPhase.Missed -> Copy(problem, NotificationSeverity.Warning, "$name: $action missed",
            "$guest. It did not start within 15 minutes of its due time.${if (run.missedEarlier > 0) " ${run.missedEarlier} earlier run${if (run.missedEarlier == 1) "" else "s"} also missed." else ""}")
        RunPhase.Skipped -> Copy(problem, NotificationSeverity.Warning, "$name: $action skipped", "$guest. A check before the run did not pass. Nothing was sent.")
        RunPhase.NotSent -> Copy(problem, NotificationSeverity.Warning, "$name: $action not sent", "$guest. Nothing was sent.")
        RunPhase.Claimed, RunPhase.Dispatching, RunPhase.Accepted -> error("A run in flight has no result yet.")
    }
    val target = runCatching { NotificationTarget.ScheduleRun(run.planId, run.id) }.getOrDefault(NotificationTarget.None)
    return NotificationEvent(NotificationIdentity("schedule", "local", run.planId, "r${run.revision}|${run.due.toEpochMilli()}|${run.phase.name}"),
        copy.category, copy.severity, copy.title, copy.body, target, native = copy.native)
}
