package local.ringleader.core

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Saved connections, read-only. Throws when the profile store cannot be read. */
fun interface ScheduleProfiles { fun load(): List<ConnectionProfile> }

/** The OS vault, reused read-only. [remembered] answers from the marker list without touching the vault. */
interface ScheduleSecrets {
    val vaultName: String
    fun status(): VaultStatus
    fun remembered(profileId: String): Boolean
    /** A fresh array the caller wipes, or null when the vault has no entry. May block on an unlock prompt. */
    fun read(profileId: String): CharArray?
}

enum class ReadyLevel { Ready, Done, Paused, Blocked, Review }

data class PlanReadiness(val level: ReadyLevel, val message: String)

data class AccessStatus(val profileId: String, val name: String, val granted: Boolean, val ready: Boolean, val canGrant: Boolean, val message: String)

data class SchedulerState(
    val loaded: Boolean = false,
    val document: ScheduleDocument = ScheduleDocument(),
    /** Store problem: scheduling (and saving) is off while it is set. */
    val problem: String? = null,
    val unreadable: Boolean = false,
    val profiles: List<ConnectionProfile> = emptyList(),
    val profileProblem: String? = null,
    val readiness: Map<String, PlanReadiness> = emptyMap(),
    val access: List<AccessStatus> = emptyList(),
    val vaultName: String = "",
    val running: Boolean = false,
    val stopped: Boolean = false,
    val checkedAt: Instant? = null,
)

/** A guest offered by the editor, read with the background connection. */
data class GuestCheck(val guest: Guest, val strong: GuestIdentity?, val fallback: GuestIdentity, val powerMgmt: Boolean, val template: Boolean, val binding: String)

sealed interface EngineResult<out T> {
    data class Ok<T>(val value: T) : EngineResult<T>
    data class Error(val message: String, val fields: Map<String, String> = emptyMap()) : EngineResult<Nothing>
}

/**
 * The resident calendar scheduler. Independent of any window: the desktop creates one per
 * process (single instance per config store) and never in browser mode.
 *
 * Claims: an occurrence is claimed by advancing its plan's [SchedulePlan.lastHandledDue] and writing a Claimed run in one
 * atomic save, before any network work. Dispatching is saved before the POST. A failed save sends nothing.
 */
class ScheduleEngine(
    private val store: ScheduleStore,
    private val profiles: ScheduleProfiles,
    private val secrets: ScheduleSecrets,
    private val gateway: ScheduleGateway,
    private val leases: MutationLeases,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxWait: Duration = Duration.ofSeconds(60),
    private val busyRetry: Duration = Duration.ofSeconds(20),
    private val follow: Duration = SCHEDULE_FOLLOW,
    private val parallelRuns: Int = 2,
) {
    private val mutable = MutableStateFlow(SchedulerState())
    val state: StateFlow<SchedulerState> = mutable.asStateFlow()
    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val runSlots = Semaphore(parallelRuns)
    private var document = ScheduleDocument()
    private var problem: ScheduleStoreException? = null
    private var loop: Job? = null
    private val runJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    @Volatile private var stopping = false

    /** Loads, recovers interrupted runs and starts the timer. Idempotent. */
    fun start() {
        if (loop != null) return
        loop = scope.launch(io) {
            mutex.withLock { loadAndRecover() }
            while (isActive && !stopping) {
                val wait = tick()
                withTimeoutOrNull(wait.toMillis().coerceAtLeast(1)) { wake.receive() }
            }
        }
    }

    /** Re-evaluate now (edit, resume from sleep, profile change). */
    fun poke() { wake.trySend(Unit) }

    /**
     * Stops new work, cancels runs and waits up to [timeout]. A run cancelled while sending records Unknown; one cancelled
     * while following records Submitted. A run still blocked in I/O stays Dispatching on disk and is recovered as
     * Unknown on the next start.
     */
    suspend fun shutdown(timeout: Duration = Duration.ofSeconds(5)): Boolean {
        stopping = true
        loop?.cancel()
        runJobs.values.forEach { it.cancel() }
        val stopped = withTimeoutOrNull(timeout.toMillis()) {
            loop?.join()
            val jobs = runJobs.values.toList()
            jobs.forEach { it.cancel() }
            jobs.forEach { it.join() }
            true
        } == true
        mutable.update { it.copy(running = false, stopped = true) }
        return stopped
    }

    private fun loadAndRecover() {
        try {
            document = store.load()
            problem = null
        } catch (e: ScheduleStoreException) {
            problem = e
            document = ScheduleDocument()
            publish()
            return
        }
        val now = clock.instant()
        val recovered = document.runs.map { run ->
            when (run.phase) {
                RunPhase.Claimed -> run.copy(phase = RunPhase.NotSent, detail = "RingMASTER stopped before this run was sent. Nothing was sent.", updatedAt = now)
                RunPhase.Dispatching -> run.copy(phase = RunPhase.Unknown, detail = "RingMASTER stopped while sending. The server may have received it; it was not sent again. Check the task list.", updatedAt = now)
                RunPhase.Accepted -> run.copy(phase = RunPhase.Submitted, detail = "RingMASTER stopped while following the task. Open its log to see how it ended.", updatedAt = now)
                else -> run
            }
        }
        if (recovered != document.runs) commit(document.copy(runs = recovered))
        publish()
    }

    /** One evaluation. Returns how long to wait before the next one. */
    private suspend fun tick(): Duration {
        val claimed = mutableListOf<ScheduleRun>()
        val wait = mutex.withLock {
            val now = clock.instant()
            if (problem != null) { publish(now); return@withLock maxWait }
            val saved = loadProfiles()
            val newRuns = mutableListOf<ScheduleRun>()
            val plans = document.plans.map { plan ->
                if (!plan.enabled) return@map plan
                val floor = listOfNotNull(plan.notBefore.minusNanos(1), plan.lastHandledDue).max()
                val due = occurrencesBetween(plan.repeat, plan.time, plan.zone, floor, now)
                if (due.isEmpty()) return@map plan
                val runIt = due.lastOrNull { !it.isBefore(now.minus(SCHEDULE_GRACE)) }
                val missed = due.filter { it != runIt }
                var next = plan
                if (missed.isNotEmpty()) {
                    newRuns += newRun(plan, missed.last(), RunPhase.Missed, missedText(missed.last(), plan), now, missed.size - 1)
                    next = next.copy(lastHandledDue = missed.last())
                }
                if (runIt != null) {
                    val ready = readiness(plan, saved, now)
                    if (ready.level != ReadyLevel.Ready && ready.level != ReadyLevel.Done) {
                        newRuns += newRun(plan, runIt, RunPhase.Skipped, ready.message.take(300), now)
                    } else {
                        val run = newRun(plan, runIt, RunPhase.Claimed, "Checking the guest before sending.", now)
                        newRuns += run
                        claimed += run
                    }
                    next = next.copy(lastHandledDue = runIt)
                }
                next
            }
            if (newRuns.isNotEmpty() && !commit(document.copy(plans = plans, runs = trimRuns(document.runs + newRuns)))) {
                claimed.clear() // Nothing was claimed on disk, so nothing may run.
            }
            publish(now, saved)
            val nextDue = document.plans.filter { it.enabled && it.invalidReason == null }.mapNotNull { nextRun(it, now) }.minOrNull()
            val untilDue = nextDue?.let { Duration.between(now, it) } ?: maxWait
            minOf(maxWait, untilDue.plusMillis(250)).coerceAtLeast(Duration.ofMillis(250))
        }
        claimed.forEach(::launchRun)
        return wait
    }

    private fun missedText(due: Instant, plan: SchedulePlan) =
        "Due ${scheduleStamp(due, plan.zone)}. RingMASTER was not running, or the computer was asleep, until more than ${SCHEDULE_GRACE.toMinutes()} minutes later. Nothing was sent."

    private fun newRun(plan: SchedulePlan, due: Instant, phase: RunPhase, detail: String, now: Instant, missedEarlier: Int = 0) = ScheduleRun(
        UUID.randomUUID().toString(), plan.id, plan.revision, due, plan.action, plan.target.label.take(160), phase, detail, now,
        missedEarlier = missedEarlier,
    )

    private fun trimRuns(runs: List<ScheduleRun>): List<ScheduleRun> {
        val live = runs.filter { it.phase.inFlight }
        val done = runs.filterNot { it.phase.inFlight }.sortedByDescending { it.updatedAt }.take((MAX_SCHEDULE_RUNS - live.size).coerceAtLeast(0))
        return (live + done).sortedBy { it.due }
    }

    /** Atomic save; on failure the in-memory document is unchanged and scheduling stops with a visible problem. */
    private fun commit(next: ScheduleDocument): Boolean = try {
        store.save(next)
        document = next
        true
    } catch (e: ScheduleStoreException) {
        problem = e
        false
    } catch (e: Exception) {
        problem = ScheduleStoreException("Schedules could not be saved. Scheduling stopped.")
        false
    }

    private fun loadProfiles(): List<ConnectionProfile>? = try { profiles.load() } catch (_: Exception) { null }

    private fun publish(now: Instant = clock.instant(), saved: List<ConnectionProfile>? = loadProfiles()) {
        val status = runCatching { secrets.status() }.getOrElse { VaultStatus.Unavailable("The password safe could not be checked.") }
        mutable.value = SchedulerState(
            loaded = true, document = document, problem = problem?.message, unreadable = problem?.unreadable == true,
            profiles = saved.orEmpty(), profileProblem = if (saved == null) "Saved connections could not be read." else null,
            readiness = document.plans.associate { it.id to readiness(it, saved, now, status) },
            access = saved.orEmpty().map { accessStatus(it, status) }, vaultName = secrets.vaultName,
            running = problem == null && !stopping, stopped = stopping, checkedAt = now,
        )
    }

    /** No network: everything that can block a run before it connects. */
    internal fun readiness(plan: SchedulePlan, saved: List<ConnectionProfile>?, now: Instant,
        status: VaultStatus = runCatching { secrets.status() }.getOrElse { VaultStatus.Unavailable("The password safe could not be checked.") }): PlanReadiness {
        plan.invalidReason?.let { return PlanReadiness(ReadyLevel.Review, it) }
        if (!plan.enabled) return PlanReadiness(ReadyLevel.Paused, "Paused. Nothing runs until you resume it.")
        if (saved == null) return PlanReadiness(ReadyLevel.Blocked, "Saved connections could not be read.")
        val profile = saved.find { it.id == plan.target.profileId }
            ?: return PlanReadiness(ReadyLevel.Review, "Its saved connection was deleted. Choose a connection and authorize it again.")
        if (profileBinding(profile) != plan.target.binding)
            return PlanReadiness(ReadyLevel.Review, "Its saved connection changed (server, token ID, certificate pin or kind). Edit it and authorize again.")
        val access = accessStatus(profile, status)
        if (!access.ready) return PlanReadiness(ReadyLevel.Blocked, access.message)
        val next = nextRun(plan, now) ?: return PlanReadiness(ReadyLevel.Done, "Finished. A one-time schedule runs once.")
        return PlanReadiness(ReadyLevel.Ready, "Next run ${scheduleStamp(next, plan.zone)}.")
    }

    private fun accessStatus(profile: ConnectionProfile, status: VaultStatus): AccessStatus {
        val grant = document.access.find { it.profileId == profile.id }
        val granted = grant != null && grant.binding == profileBinding(profile)
        fun result(ready: Boolean, canGrant: Boolean, message: String) = AccessStatus(profile.id, profile.displayName, granted, ready, canGrant, message)
        return when {
            profile.kind != DIRECT_PVE_KIND -> result(false, false, "Background runs support direct Proxmox VE connections only.")
            profile.passwordLogin -> result(false, false, "Background runs need an API token. Passwords and two-factor codes are never replayed unattended.")
            grant != null && !granted -> result(false, false, "The connection changed after background access was allowed. Allow it again.")
            status is VaultStatus.Unavailable -> result(false, false, status.reason)
            !runCatching { secrets.remembered(profile.id) }.getOrDefault(false) ->
                result(false, false, "No secret is remembered for this connection. Connect once with Remember on this computer ticked.")
            granted -> result(true, false, "Background runs may use the secret remembered in ${secrets.vaultName}.")
            else -> result(false, true, "Background access is off. Schedules for this connection do not run.")
        }
    }

    // ---- User actions (call from any coroutine; they run on the engine's I/O dispatcher). ----

    suspend fun refresh() = withContext(io) { mutex.withLock { publish() } }

    suspend fun grantAccess(profileId: String): String? = withContext(io) {
        mutex.withLock {
            if (problem != null) return@withLock problem!!.message
            val profile = loadProfiles()?.find { it.id == profileId } ?: return@withLock "That connection no longer exists."
            val status = runCatching { secrets.status() }.getOrElse { VaultStatus.Unavailable("The password safe could not be checked.") }
            val current = accessStatus(profile, status)
            if (!current.canGrant && !(current.granted && current.ready)) return@withLock current.message
            val access = document.access.filterNot { it.profileId == profileId } + BackgroundAccess(profileId, profileBinding(profile), clock.instant())
            if (!commit(document.copy(access = access))) return@withLock problem?.message
            publish(); poke(); null
        }
    }

    suspend fun revokeAccess(profileId: String): String? = withContext(io) {
        mutex.withLock {
            if (problem != null) return@withLock problem!!.message
            if (!commit(document.copy(access = document.access.filterNot { it.profileId == profileId }))) return@withLock problem?.message
            publish(); null
        }
    }

    /** Opens the background connection for the editor: proves the remembered secret works and lists guests. */
    suspend fun inspectGuests(profileId: String): EngineResult<List<Guest>> = withContext(io) {
        val profile = loadProfiles()?.find { it.id == profileId } ?: return@withContext EngineResult.Error("That connection no longer exists.")
        val access = accessStatus(profile, runCatching { secrets.status() }.getOrElse { VaultStatus.Unavailable("The password safe could not be checked.") })
        if (!access.ready) return@withContext EngineResult.Error(access.message)
        backgroundConnection(profile) { connection ->
            EngineResult.Ok(gateway.guests(connection).filter { it.scope.isEmpty() }.sortedBy { it.id })
        }
    }

    /** Identity, template and permission evidence for one guest, for the editor's review. */
    suspend fun inspectGuest(profileId: String, guest: Guest): EngineResult<GuestCheck> = withContext(io) {
        val profile = loadProfiles()?.find { it.id == profileId } ?: return@withContext EngineResult.Error("That connection no longer exists.")
        backgroundConnection(profile) { connection ->
            val facts = gateway.identity(connection, guest.node, guest.kind, guest.id)
            val dump = gateway.permissions(connection)
            val power = "VM.PowerMgmt" in effectivePrivileges(dump, "/vms/${guest.id}").privileges
            EngineResult.Ok(GuestCheck(guest, facts.best(false), facts.with(IdentityMethod.Fallback)!!, power, facts.template || guest.template, profileBinding(profile)))
        }
    }

    private suspend fun <T> backgroundConnection(profile: ConnectionProfile, block: suspend (Connection) -> EngineResult<T>): EngineResult<T> {
        val access = accessStatus(profile, runCatching { secrets.status() }.getOrElse { VaultStatus.Unavailable("The password safe could not be checked.") })
        if (!access.ready) return EngineResult.Error(access.message)
        val secret = try { secrets.read(profile.id) } catch (e: Exception) {
            return EngineResult.Error("The password safe could not be read: ${(e.message ?: "it failed").take(160)}")
        } ?: return EngineResult.Error("The remembered secret is no longer in ${secrets.vaultName}. Connect with Remember on this computer again.")
        val connection = try { Connection(profile.origin, profile.tokenId, String(secret), profile.sha256Pin.orEmpty(), profile.kind) }
            catch (e: Exception) { return EngineResult.Error(e.message ?: "The saved connection is not usable.") }
            finally { secret.fill('\u0000') }
        return connection.use {
            try { block(it) } catch (e: CancellationException) { throw e } catch (e: Exception) { EngineResult.Error(readError(e)) }
        }
    }

    /**
     * Saves a new or edited schedule. [authorized] is the user's explicit consent for every future run; it is required
     * for a new plan and for any change of target, action or timing, and for a plan that needs review.
     */
    suspend fun savePlan(existingId: String?, draft: ScheduleDraft, check: GuestCheck?, allowFallback: Boolean, authorized: Boolean, profileId: String?): EngineResult<SchedulePlan> = withContext(io) {
        mutex.withLock {
            val now = clock.instant()
            if (problem != null) return@withLock EngineResult.Error(problem!!.message ?: "Schedules cannot be saved.")
            val errors = scheduleDraftErrors(draft, now).toMutableMap()
            val old = existingId?.let { id -> document.plans.find { it.id == id } ?: return@withLock EngineResult.Error("That schedule no longer exists.") }
            if (old == null && document.plans.size >= MAX_SCHEDULES) return@withLock EngineResult.Error("At most $MAX_SCHEDULES schedules can be kept.")
            val saved = loadProfiles() ?: return@withLock EngineResult.Error("Saved connections could not be read.")
            val target: ScheduleTarget? = when {
                check != null -> {
                    val profile = saved.find { it.id == profileId }
                    when {
                        profile == null -> { errors["guest"] = "Choose a saved connection."; null }
                        profileBinding(profile) != check.binding -> { errors["guest"] = "The connection changed while you were editing. Load the guests again."; null }
                        check.template -> { errors["guest"] = "Templates cannot be started or shut down."; null }
                        !check.powerMgmt -> { errors["guest"] = "This token lacks VM.PowerMgmt on the guest, so runs would be refused."; null }
                        check.strong == null && !allowFallback -> { errors["guest"] = "This guest has no stable identity. Allow the weak identity to continue."; null }
                        else -> ScheduleTarget(profile.id, check.binding, check.guest.node, check.guest.kind, check.guest.id, check.guest.name.take(128),
                            check.strong ?: check.fallback)
                    }
                }
                old != null && old.invalidReason != null -> { errors["guest"] = "Load the guest again to review its identity and permissions."; null }
                old != null -> old.target
                else -> { errors["guest"] = "Choose a guest."; null }
            }
            if (errors.isNotEmpty() || target == null) return@withLock EngineResult.Error("Check the highlighted fields.", errors)
            val repeat = draft.toRepeat()
            val time = parseScheduleTime(draft.time)!!
            val zone = parseScheduleZone(draft.zone)!!
            val profile = saved.find { it.id == target.profileId }
            val bindingChanged = profile == null || profileBinding(profile) != target.binding
            if (bindingChanged) return@withLock EngineResult.Error("Its saved connection changed. Choose the guest again.", mapOf("guest" to "Load the guests again."))
            val needsAuthorization = old == null || old.invalidReason != null || authorizationChanged(old, draft.action, repeat, time, zone, target)
            if (needsAuthorization && !authorized) return@withLock EngineResult.Error("Confirm that RingMASTER may run this schedule without asking.", mapOf("authorize" to "Required."))
            val plan = if (old == null) SchedulePlan(UUID.randomUUID().toString(), draft.name.trim(), 1, draft.action, repeat, time, zone, target, true, now, now)
                else if (needsAuthorization) old.copy(name = draft.name.trim(), revision = old.revision + 1, action = draft.action, repeat = repeat, time = time, zone = zone,
                    target = target, enabled = old.enabled, authorizedAt = now, notBefore = now, invalidReason = null)
                else old.copy(name = draft.name.trim())
            val plans = if (old == null) document.plans + plan else document.plans.map { if (it.id == plan.id) plan else it }
            if (!commit(document.copy(plans = plans))) return@withLock EngineResult.Error(problem?.message ?: "Schedules could not be saved.")
            publish(now); poke()
            EngineResult.Ok(plan)
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean): String? = withContext(io) {
        mutex.withLock {
            if (problem != null) return@withLock problem!!.message
            val now = clock.instant()
            val plans = document.plans.map { if (it.id == id) it.copy(enabled = enabled, notBefore = if (enabled && !it.enabled) now else it.notBefore) else it }
            if (!commit(document.copy(plans = plans))) return@withLock problem?.message
            publish(now); poke(); null
        }
    }

    /** Deletes the plan and its finished history. A run already sending finishes and keeps its record. */
    suspend fun delete(id: String): String? = withContext(io) {
        mutex.withLock {
            if (problem != null) return@withLock problem!!.message
            val next = document.copy(plans = document.plans.filterNot { it.id == id }, runs = document.runs.filterNot { it.planId == id && !it.phase.inFlight })
            if (!commit(next)) return@withLock problem?.message
            publish(); null
        }
    }

    /** Explicit recovery from an unreadable file. */
    suspend fun setAsideUnreadable(): String? = withContext(io) {
        mutex.withLock {
            try {
                store.setAside(clock.instant())
                document = ScheduleDocument(); problem = null
                publish(); poke(); null
            } catch (e: Exception) { e.message ?: "The file could not be moved." }
        }
    }

    /** First lines of a run's exact task log, read with the background connection. */
    suspend fun taskLog(runId: String, limit: Int = 500): EngineResult<TaskLogPage> = withContext(io) {
        val run = document.runs.find { it.id == runId } ?: return@withContext EngineResult.Error("That run is no longer in the history.")
        val upid = run.upid ?: return@withContext EngineResult.Error("This run has no server task.")
        val node = run.node ?: parseServerUpid(upid)?.node ?: return@withContext EngineResult.Error("This run has no server task.")
        val plan = document.plans.find { it.id == run.planId } ?: return@withContext EngineResult.Error("Its schedule was deleted, so its connection is unknown.")
        val profile = loadProfiles()?.find { it.id == plan.target.profileId && profileBinding(it) == plan.target.binding }
            ?: return@withContext EngineResult.Error("Its saved connection changed or was deleted.")
        if (!accessStatus(profile, runCatching { secrets.status() }.getOrElse { VaultStatus.Unavailable("The password safe could not be checked.") }).ready)
            return@withContext EngineResult.Error("Background access for this connection is off.")
        backgroundConnection(profile) { EngineResult.Ok(gateway.taskLog(it, node, upid, 0, limit)) }
    }

    // ---- Runs ----

    private fun launchRun(run: ScheduleRun) {
        if (stopping) return
        val job = scope.launch(io, start = CoroutineStart.LAZY) {
            try { execute(run) }
            finally { runJobs.remove(run.id) }
        }
        runJobs[run.id] = job
        job.start()
    }

    /** Persists one run's new phase. False when the save failed (the caller must not send). */
    private suspend fun record(run: ScheduleRun, phase: RunPhase, detail: String, upid: String? = run.upid, node: String? = run.node,
        invalidate: String? = null): Pair<ScheduleRun, Boolean> = record(run, phase, detail, upid, node, invalidate, requireCurrent = false)!!

    /** With [requireCurrent], returns null (and records nothing) when the plan was edited, paused, invalidated or deleted. */
    private suspend fun record(run: ScheduleRun, phase: RunPhase, detail: String, upid: String? = run.upid, node: String? = run.node,
        invalidate: String? = null, requireCurrent: Boolean): Pair<ScheduleRun, Boolean>? = withContext(NonCancellable) {
        mutex.withLock {
            if (requireCurrent) {
                if (stopping) return@withLock null
                val current = document.plans.find { it.id == run.planId && it.revision == run.revision && it.enabled && it.invalidReason == null }
                    ?: return@withLock null
                // Recheck consent and profile identity after vault/preflight/busy waits, under the dispatch lock.
                val ready = readiness(current, loadProfiles(), clock.instant())
                if (ready.level != ReadyLevel.Ready && ready.level != ReadyLevel.Done) return@withLock null
            }
            val now = clock.instant()
            val updated = run.copy(phase = phase, detail = detail.replace(Regex("\\s+"), " ").trim().take(300), upid = upid, node = node, updatedAt = now)
            val plans = if (invalidate == null) document.plans else document.plans.map {
                if (it.id == run.planId && it.revision == run.revision) it.copy(invalidReason = invalidate) else it
            }
            val ok = problem == null && commit(document.copy(plans = plans, runs = trimRuns(document.runs.map { if (it.id == run.id) updated else it })))
            if (!ok && problem != null) document = document.copy(runs = document.runs.map { if (it.id == run.id) updated else it })
            publish(now)
            updated to ok
        }
    }

    private suspend fun currentPlan(run: ScheduleRun): SchedulePlan? = mutex.withLock {
        document.plans.find { it.id == run.planId && it.revision == run.revision && it.enabled && it.invalidReason == null }
    }

    private suspend fun execute(claimed: ScheduleRun) {
        var run = claimed
        suspend fun finish(phase: RunPhase, detail: String, invalidate: String? = null) { run = record(run, phase, detail, invalidate = invalidate).first }
        val plan = currentPlan(run) ?: return finish(RunPhase.Cancelled, "The schedule or background access changed, or RingMASTER stopped before sending. Nothing was sent.")
        val saved = loadProfiles() ?: return finish(RunPhase.Skipped, "Saved connections could not be read. Nothing was sent.")
        val ready = readiness(plan, saved, clock.instant())
        if (ready.level != ReadyLevel.Ready && ready.level != ReadyLevel.Done) return finish(RunPhase.Skipped, "${ready.message} Nothing was sent.")
        val profile = saved.first { it.id == plan.target.profileId }
        val secret = try { secrets.read(profile.id) } catch (e: Exception) {
            return finish(RunPhase.Skipped, "The password safe could not be read: ${(e.message ?: "it failed").take(120)} Nothing was sent.")
        } ?: return finish(RunPhase.Skipped, "The remembered secret is no longer in ${secrets.vaultName}. Nothing was sent.")
        val connection = try { Connection(profile.origin, profile.tokenId, String(secret), profile.sha256Pin.orEmpty(), profile.kind) }
            catch (e: Exception) { return finish(RunPhase.Skipped, "The saved connection is not usable. Nothing was sent.") }
            finally { secret.fill('\u0000') }
        connection.use { conn -> executeConnected(run, plan, profile, conn) { phase, detail, invalidate -> finish(phase, detail, invalidate) }.also { run = it ?: run } }
    }

    private suspend fun executeConnected(start: ScheduleRun, plan: SchedulePlan, profile: ConnectionProfile, conn: Connection,
        finish: suspend (RunPhase, String, String?) -> Unit): ScheduleRun? {
        var run = start
        val target = plan.target
        val deadline = run.due.plus(SCHEDULE_GRACE)
        // Preflight reads. Any failure here sends nothing.
        val guest: Guest
        try {
            guest = runSlots.withPermit { gateway.guests(conn) }.singleOrNull { it.id == target.vmid && it.kind == target.kind && it.scope.isEmpty() }
                ?: run { finish(RunPhase.Skipped, "The guest is no longer on this server. Nothing was sent.", null); return null }
            if (guest.template) { finish(RunPhase.Skipped, "The guest is now a template. Nothing was sent.", null); return null }
            val facts = runSlots.withPermit { gateway.identity(conn, guest.node, target.kind, target.vmid) }
            if (facts.with(target.identity.method) != target.identity) {
                finish(RunPhase.Skipped, "The guest's identity (${target.identity.method.label}) changed. Nothing was sent; the schedule needs review.",
                    "The guest's identity changed. Check that it is the right guest, then edit and authorize the schedule again.")
                return null
            }
            if (facts.template) { finish(RunPhase.Skipped, "The guest is now a template. Nothing was sent.", null); return null }
            val dump = runSlots.withPermit { gateway.permissions(conn) }
            if ("VM.PowerMgmt" !in effectivePrivileges(dump, "/vms/${target.vmid}").privileges) {
                finish(RunPhase.Skipped, "The token lacks VM.PowerMgmt on this guest. Nothing was sent.", null); return null
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { finish(RunPhase.NotSent, "RingMASTER stopped before sending. Nothing was sent.", null) }; throw e
        } catch (e: Exception) {
            finish(RunPhase.Skipped, "${readError(e)} Nothing was sent.", null); return null
        }
        val key = GuestKey(profile.origin, target.kind, target.vmid)
        try {
            while (true) {
                val busy = leases.acquireScheduled(key, run.id) ?: break
                if (!clock.instant().isBefore(deadline)) {
                    finish(RunPhase.Skipped, "Another action on this guest was still open when the grace window ended ($busy) Nothing was sent.", null); return null
                }
                delay(busyRetry.toMillis())
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { finish(RunPhase.NotSent, "RingMASTER stopped before sending. Nothing was sent.", null) }; throw e
        }
        try {
            return runSlots.withPermit {
                val state = try { gateway.state(conn, guest.node, target.kind, target.vmid) }
                    catch (e: CancellationException) { withContext(NonCancellable) { finish(RunPhase.NotSent, "RingMASTER stopped before sending. Nothing was sent.", null) }; throw e }
                    catch (e: Exception) { finish(RunPhase.Skipped, "${readError(e)} Nothing was sent.", null); return null }
                if (state.template) { finish(RunPhase.Skipped, "The guest is now a template. Nothing was sent.", null); return null }
                state.lock?.let { finish(RunPhase.Skipped, "The guest is locked ($it). Nothing was sent.", null); return null }
                when (plan.action) {
                    ScheduleAction.Start -> when (state.status) {
                        "running" -> { finish(RunPhase.NoChange, "The guest was already running. Nothing was sent.", null); return null }
                        "stopped" -> {}
                        else -> { finish(RunPhase.Skipped, "The guest state is ${state.status.take(20)}, not stopped. Nothing was sent.", null); return null }
                    }
                    ScheduleAction.Shutdown -> when {
                        state.status == "stopped" -> { finish(RunPhase.NoChange, "The guest was already stopped. Nothing was sent.", null); return null }
                        state.status == "running" && (state.qmpStatus == null || state.qmpStatus == "running") -> {}
                        else -> { finish(RunPhase.Skipped, "The guest is ${state.qmpStatus ?: state.status}; a graceful shutdown would fail. Nothing was sent.", null); return null }
                    }
                }
                if (!clock.instant().isBefore(deadline)) { finish(RunPhase.Skipped, "The grace window ended before sending. Nothing was sent.", null); return null }
                // One lock: the plan is still this revision and enabled, and Dispatching is on disk, before the POST.
                val (dispatching, saved) = record(run, RunPhase.Dispatching, "Sending ${plan.action.title.lowercase()} to the server.", node = guest.node, requireCurrent = true)
                    ?: run { finish(RunPhase.Cancelled, "The schedule or background access changed, or RingMASTER stopped before sending. Nothing was sent.", null); return null }
                run = dispatching
                if (!saved) { run = record(run, RunPhase.NotSent, "The run could not be recorded before sending, so nothing was sent.").first; return run }
                val upid = try {
                    gateway.dispatch(conn, guest.node, target.kind, target.vmid, plan.action)
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { run = record(run, RunPhase.Unknown, "RingMASTER stopped while sending. The server may have received it; it was not sent again.").first }
                    throw e
                } catch (e: ScheduleNotSentException) {
                    run = record(run, RunPhase.NotSent, e.message ?: "Nothing was sent.").first; return run
                } catch (e: IllegalArgumentException) {
                    run = record(run, RunPhase.NotSent, "The request could not be built. Nothing was sent.").first; return run
                } catch (e: ScheduleRefusedException) {
                    val why = when (e.status) {
                        401 -> "The server refused the saved token (HTTP 401). Nothing ran, and it was not sent again."
                        403 -> "The server refused the action (HTTP 403, permission). It was not sent again."
                        else -> "The server refused the action (HTTP ${e.status}). It was not sent again."
                    }
                    run = record(run, RunPhase.Rejected, why).first; return run
                } catch (e: Exception) {
                    run = record(run, RunPhase.Unknown, "The server did not confirm whether it accepted the action. It was not sent again. Check the task list.").first; return run
                }
                run = record(run, RunPhase.Accepted, "Accepted by the server. Following the task.", upid = upid, node = guest.node).first
                followTask(run, conn, guest.node, upid)
            }
        } finally {
            leases.releaseScheduled(key, run.id)
        }
    }

    private suspend fun followTask(start: ScheduleRun, conn: Connection, node: String, upid: String): ScheduleRun {
        var run = start
        var waited = 0L
        var attempt = 0
        try {
            while (waited < follow.toMillis()) {
                val pause = powerFollowDelay(attempt++).coerceAtMost(follow.toMillis() - waited).coerceAtLeast(1)
                delay(pause)
                waited += pause
                val status = try { gateway.taskStatus(conn, node, upid) } catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
                when (status.phase) {
                    PowerPhase.Succeeded -> return if (parseServerUpid(upid)?.type in setOf("hastart", "hastop"))
                        record(run, RunPhase.Submitted, "The HA manager accepted the request (task exit status OK). The guest's final state has not been confirmed.").first
                        else record(run, RunPhase.Succeeded, "Stopped with exit status OK.").first
                    PowerPhase.Unsuccessful -> return if (status.detail.startsWith("WARNINGS")) record(run, RunPhase.Warnings, "Finished with ${status.detail.take(40)}. Not counted as OK.").first
                        else record(run, RunPhase.Failed, "The task failed: ${status.detail.take(200)}").first
                    PowerPhase.Unknown -> return record(run, RunPhase.Unknown, status.detail).first
                    else -> {}
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { run = record(run, RunPhase.Submitted, "RingMASTER stopped before the task finished. Open its log to see how it ended.").first }
            throw e
        }
        return record(run, RunPhase.Submitted, "The task had not finished after ${follow.toMinutes()} minutes. Open its log to see how it ended.").first
    }

    private fun readError(e: Exception): String = when (e) {
        is ScheduleRefusedException -> when (e.status) {
            401 -> "The server refused the saved token (HTTP 401)."
            403 -> "The token may not read what the run needs (HTTP 403)."
            else -> "The server refused a read (HTTP ${e.status})."
        }
        is ScheduleNotSentException -> e.message ?: "The server could not be reached."
        is ScheduleUncertainException -> "The server's answer was incomplete."
        else -> "A server read failed (${e.javaClass.simpleName})."
    }
}
