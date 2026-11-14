package local.ringleader.desktop

import local.ringleader.core.ClusterTask
import local.ringleader.core.PowerAction
import local.ringleader.core.PowerPhase
import local.ringleader.core.ResourceId
import local.ringleader.core.TaskIdentity
import local.ringleader.core.TaskLogLine
import local.ringleader.core.TaskOutcome
import local.ringleader.core.WorkspaceState

/*
 * When does a signature moment fire? Only on a real change of state: a submission appearing, a task or submission
 * reaching a terminal state, a guest changing between running and stopped. Never because the 5-second refresh redrew
 * the list. Everything here is a pure function of two snapshots, so it is tested without a window.
 */

/** One power or snapshot submission, reduced to what motion cares about. [id] is the local id, never the UPID. */
data class SubmissionFact(val id: String, val resource: ResourceId, val action: PowerAction, val phase: PowerPhase)

/** One task from the Activity feed. [resource] is the guest it belongs to, when that can be told. */
data class TaskFact(val resource: ResourceId?, val type: String, val outcome: TaskOutcome)

/** Everything the motion layer reads from a [WorkspaceState], in a form that compares by value. */
data class MotionFacts(
    val epoch: Long,
    /** Newest first, as the workspace keeps them. */
    val submissions: List<SubmissionFact> = emptyList(),
    val tasks: Map<TaskIdentity, TaskFact> = emptyMap(),
    val guestStatus: Map<ResourceId, String> = emptyMap(),
)

/** What can fire. [Landed] comes from the spark touching down, every other kind from comparing two [MotionFacts]. */
enum class MomentKind { Started, Landed, Succeeded, Failed, Arrived, Stopped, Snapshot }

/**
 * One moment. [resource] is the guest it is about, [source] names the submission (`submission:<local id>`) or the task
 * (`task:<upid>`) so a row can react to its own task only. [waitedMs] is how long the user waited (set by the book).
 */
data class MotionMoment(
    val kind: MomentKind,
    val resource: ResourceId?,
    val source: String,
    val action: PowerAction? = null,
    val hard: Boolean = false,
    val waitedMs: Long = 0L,
)

/** What a guest row or tile shows while something is going on with it. */
data class GuestActivity(val inFlight: Boolean, val action: PowerAction?, val failed: Boolean)

fun motionFacts(state: WorkspaceState): MotionFacts {
    val guests = state.overview?.guests.orEmpty()
    val directKinds = HashMap<Pair<String, Int>, String>(guests.size * 2)
    val status = LinkedHashMap<ResourceId, String>(guests.size * 2)
    for (guest in guests) {
        if (guest.scope.isEmpty()) directKinds[guest.remote to guest.id] = guest.kind
        status[guest.resource] = guest.status
    }
    val submissions = state.submissions.map {
        SubmissionFact(it.localId, ResourceId(it.remote, "", it.kind, it.vmid), it.action, it.phase)
    }
    val tasks = LinkedHashMap<TaskIdentity, TaskFact>()
    for (task in state.tasks.orEmpty()) tasks[task.identity] = TaskFact(taskResource(task, directKinds), task.type, task.outcome)
    return MotionFacts(state.connectionEpoch, submissions, tasks, status)
}

/** The guest a task belongs to: worker types say VM (`qm…`) or container (`vz…`); the rest (`vzdump`, resize…) are looked up. */
internal fun taskResource(task: ClusterTask, directKinds: Map<Pair<String, Int>, String>): ResourceId? {
    val vmid = task.subjectId.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val kind = when {
        task.type.startsWith("qm") -> "VM"
        task.type.startsWith("vz") && task.type != "vzdump" -> "Container"
        else -> directKinds[task.remote to vmid]
    } ?: return null
    return ResourceId(task.remote, "", kind, vmid)
}

/** The guest status each action is waiting for. Null: the status does not change (reboot, reset, snapshots). */
fun expectedStatus(action: PowerAction): Set<String>? = when (action) {
    PowerAction.Start, PowerAction.Resume -> setOf("running")
    PowerAction.Shutdown, PowerAction.HardStop -> setOf("stopped")
    PowerAction.Suspend -> setOf("paused", "suspended")
    else -> null
}

private fun SubmissionFact.unresolved() = phase == PowerPhase.Accepted || phase == PowerPhase.Unknown

/**
 * Which guests are busy. A submission the server accepted (or left unknown) keeps its guest busy until the guest reaches
 * the status that action waits for, or the submission is resolved. A running task in the feed keeps it busy too. Once
 * nothing is going on the indicator is still: stillness is the "done" signal.
 */
fun guestActivities(facts: MotionFacts): Map<ResourceId, GuestActivity> {
    val newest = HashMap<ResourceId, SubmissionFact>()
    for (s in facts.submissions) newest.putIfAbsent(s.resource, s)
    val result = LinkedHashMap<ResourceId, GuestActivity>()
    for (s in facts.submissions) {
        if (!s.unresolved()) continue
        val reached = expectedStatus(s.action)?.let { facts.guestStatus[s.resource] in it } == true
        if (reached) continue
        result[s.resource] = GuestActivity(true, result[s.resource]?.action ?: s.action, failed = false)
    }
    for (t in facts.tasks.values) {
        val resource = t.resource ?: continue
        if (t.outcome == TaskOutcome.Running && resource !in result) result[resource] = GuestActivity(true, null, failed = false)
    }
    for ((resource, s) in newest) {
        val failed = s.phase == PowerPhase.Unsuccessful || s.phase == PowerPhase.Rejected
        val current = result[resource]
        if (current != null) result[resource] = current.copy(failed = failed)
        else if (failed) result[resource] = GuestActivity(false, s.action, true)
    }
    return result
}

private fun MotionFacts.unresolvedFor(resource: ResourceId?): List<SubmissionFact> =
    if (resource == null) emptyList() else submissions.filter { it.resource == resource && it.unresolved() }

/**
 * Which moments fire between two snapshots of the same connection. A new connection, or no earlier snapshot, only sets
 * the baseline: nothing fires for guests and tasks that were simply already there.
 */
fun momentsBetween(old: MotionFacts?, new: MotionFacts): List<MotionMoment> {
    if (old == null || old.epoch != new.epoch || old == new) return emptyList()
    val out = ArrayList<MotionMoment>()
    val before = old.submissions.associateBy { it.id }
    for (s in new.submissions) {
        val source = "submission:${s.id}"
        val earlier = before[s.id]
        if (earlier == null) when (s.phase) {
            PowerPhase.Accepted, PowerPhase.Simulated, PowerPhase.Unknown -> {
                out += MotionMoment(MomentKind.Started, s.resource, source, s.action)
                if (s.action == PowerAction.SnapshotCreate) out += MotionMoment(MomentKind.Snapshot, s.resource, source, s.action)
            }
            PowerPhase.Rejected, PowerPhase.Unsuccessful -> out += MotionMoment(MomentKind.Failed, s.resource, source, s.action)
            PowerPhase.Succeeded -> out += MotionMoment(MomentKind.Succeeded, s.resource, source, s.action)
        } else if (earlier.phase != s.phase) when (s.phase) {
            PowerPhase.Succeeded -> out += MotionMoment(MomentKind.Succeeded, s.resource, source, s.action)
            PowerPhase.Unsuccessful, PowerPhase.Rejected -> out += MotionMoment(MomentKind.Failed, s.resource, source, s.action)
            else -> Unit
        }
    }
    for ((identity, task) in new.tasks) {
        val source = "task:${identity.upid}"
        val earlier = old.tasks[identity]
        val ended = task.outcome == TaskOutcome.Succeeded || task.outcome == TaskOutcome.Unsuccessful
        // A task that shows up already finished still counts when the user had an action pending on that guest: the 10 s
        // feed can easily miss the running state of a quick start.
        val mine = earlier == null && old.unresolvedFor(task.resource).isNotEmpty()
        val finishedNow = ended && ((earlier != null && earlier.outcome == TaskOutcome.Running) || mine)
        if (!finishedNow) continue
        val kind = if (task.outcome == TaskOutcome.Succeeded) MomentKind.Succeeded else MomentKind.Failed
        out += MotionMoment(kind, task.resource, source)
    }
    for ((resource, status) in new.guestStatus) {
        val was = old.guestStatus[resource] ?: continue
        if (was == status) continue
        val pending = old.unresolvedFor(resource)
        if (was == "running" && status == "stopped") {
            val hard = pending.any { it.action == PowerAction.HardStop }
            out += MotionMoment(MomentKind.Stopped, resource, pending.firstOrNull()?.let { "submission:${it.id}" } ?: "guest", pending.firstOrNull()?.action, hard)
        } else if (status == "running") {
            val arrived = pending.firstOrNull { expectedStatus(it.action)?.contains(status) == true }
            if (arrived != null) out += MotionMoment(MomentKind.Arrived, resource, "submission:${arrived.id}", arrived.action)
        }
    }
    // A terminal success for a guest already says it: the guest reaching its status in the same step is not announced twice.
    val succeeded = out.filter { it.kind == MomentKind.Succeeded }.mapNotNull { it.resource }.toSet()
    out.removeAll { it.kind == MomentKind.Arrived && it.resource in succeeded }
    return out
}

/** Sources that count as "the user is waiting on this": unresolved submissions and running tasks. */
fun inFlightSources(facts: MotionFacts): Set<String> = buildSet {
    for (s in facts.submissions) if (s.unresolved()) add("submission:${s.id}")
    for ((identity, t) in facts.tasks) if (t.outcome == TaskOutcome.Running) add("task:${identity.upid}")
}

private val percentPattern = Regex("""(?<![\w.])(\d{1,3}(?:\.\d+)?)\s*%""")

/**
 * The newest percentage a task has printed into its log (vzdump prints `INFO:  12% (3.4 GiB of 28.0 GiB) in 3s …`,
 * restores and clones print similar lines), as 0..1. Null while no line carries one. The core task-log parser does not
 * expose progress yet, so this reads the lines the workspace already streams.
 */
fun taskProgress(lines: List<TaskLogLine>): Float? {
    for (line in lines.asReversed()) {
        val text = line.text
        if (text.startsWith("TASK")) continue
        val value = percentPattern.findAll(text).mapNotNull { it.groupValues[1].toFloatOrNull() }.lastOrNull { it in 0f..100f } ?: continue
        return value / 100f
    }
    return null
}
