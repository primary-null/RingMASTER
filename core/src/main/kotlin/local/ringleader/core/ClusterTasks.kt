package local.ringleader.core

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Direct recent-task feed. This list route is not a log, a single-task status, or an action. */
const val CLUSTER_TASKS_PATH = "/api2/json/cluster/tasks"

/** Parsed server UPID. The raw value stays out of profile files. */
data class ServerUpid(val node: String, val type: String, val subjectId: String, val user: String)

/** Remote origin plus the server UPID. Node is routing information and is not part of this key. */
data class TaskIdentity(val remote: String, val upid: String)

/**
 * How much of the outcome is actually evidenced.
 * Missing status is [Unknown]. [Running] requires an explicit running status and no end time.
 * [Succeeded] requires the exact server success token. Other text is reported as-is and is not success.
 */
enum class TaskOutcome { Running, Succeeded, Unsuccessful, Unknown }

/**
 * One recent task. Token ids, process ids, and task logs are not retained.
 * [statusText] is the server's status field when it sent one, not a log body.
 */
data class ClusterTask(
    val remote: String,
    val upid: String,
    val node: String,
    val type: String,
    val subjectId: String,
    val user: String,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val statusText: String?,
    val outcome: TaskOutcome,
) {
    val identity get() = TaskIdentity(remote, upid)
    init {
        require(node.matches(nodePattern) && type.isNotBlank() && type.none { it.isISOControl() }) { "The server returned an invalid task record." }
        require(subjectId.none { it.isISOControl() } && user.none { it.isISOControl() }) { "The server returned an invalid task record." }
        require(statusText?.none { it.isISOControl() } != false) { "The server returned an invalid task record." }
        require(outcome == classifyTask(statusText, endedAt)) { "The server returned an invalid task record." }
        if (remote == DEMO_REMOTE_KEY) {
            require(upid.startsWith("demo:") && upid.none { it.isWhitespace() || it.isISOControl() }) { "The server returned an invalid task record." }
        } else {
            require(serverOrigin(remote).toString() == remote) { "The task is missing its connection identity." }
            val parts = parseUpid(upid) ?: throw IllegalArgumentException("The server returned an invalid task record.")
            require(parts.node == node) { "The server returned an invalid task record." }
        }
    }
}

fun classifyTask(statusText: String?, endedAt: Instant?): TaskOutcome {
    val status = statusText?.trim().orEmpty()
    if (status.equals("running", ignoreCase = true)) return if (endedAt == null) TaskOutcome.Running else TaskOutcome.Unknown
    if (status == "OK") return TaskOutcome.Succeeded
    // Proxmox ends a task that completed with warnings as "WARNINGS: n": it succeeded.
    if (status.startsWith("WARNINGS", ignoreCase = true)) return TaskOutcome.Succeeded
    if (status.isNotEmpty()) return TaskOutcome.Unsuccessful
    return TaskOutcome.Unknown
}

/** A successful task that Proxmox finished with "WARNINGS: n". */
fun taskHasWarnings(task: ClusterTask): Boolean = task.outcome == TaskOutcome.Succeeded && task.statusText?.trim()?.startsWith("WARNINGS", ignoreCase = true) == true

/** Readable status. Never promotes a missing or conflicting record to running or success. */
fun taskStatusLabel(task: ClusterTask): String = when (task.outcome) {
    TaskOutcome.Running -> "running"
    TaskOutcome.Succeeded -> if (taskHasWarnings(task)) task.statusText!!.trim() else "OK"
    TaskOutcome.Unsuccessful -> task.statusText?.takeIf { it.isNotBlank() } ?: "Unknown"
    TaskOutcome.Unknown -> "Unknown"
}

/** Account only. A token name appended after '!' is not shown in the list. */
fun taskUserLabel(user: String): String = user.substringBefore('!').trim().ifBlank { "—" }

/** Fields the Activity list may show. The UPID and token id are not included. */
fun taskListFields(task: ClusterTask) = listOf(
    task.type.ifBlank { "Unknown" }, task.node, task.subjectId.ifBlank { "—" }, taskUserLabel(task.user), taskStatusLabel(task),
)

fun requireActiveTasks(remote: String, tasks: List<ClusterTask>): List<ClusterTask> {
    require(tasks.all { it.remote == remote }) { "Activity included a task from another connection." }
    require(tasks.map { it.identity }.distinct().size == tasks.size) { "Activity included duplicate task identities." }
    return tasks
}

fun parseClusterTasks(body: String, remote: String): List<ClusterTask> {
    require(serverOrigin(remote).toString() == remote) { "The task is missing its connection identity." }
    val root = try { Json.parseToJsonElement(body) } catch (_: Exception) { invalidTask() }
    val payload = root as? JsonObject ?: invalidTask()
    if ("data" !in payload || payload["data"] is JsonNull) error("Server returned no task list.")
    val rows = payload["data"] as? JsonArray ?: invalidTask()
    val tasks = rows.map { parseTaskRow(it, remote) }
    require(tasks.map { it.identity }.distinct().size == tasks.size) { "Activity included duplicate task identities." }
    return tasks.sortedWith(compareByDescending<ClusterTask> { it.startedAt ?: Instant.EPOCH }.thenBy { it.upid })
}

private fun parseTaskRow(element: JsonElement, remote: String): ClusterTask {
    val row = element as? JsonObject ?: invalidTask()
    // tokenid, saved, pid, pstart, and any log payload are intentionally unread.
    val upid = row.text("upid")?.takeIf { it.isNotEmpty() } ?: invalidTask()
    if (upid.length > 1024) invalidTask()
    val parts = parseUpid(upid) ?: invalidTask()
    val node = row.text("node")?.takeIf { it.isNotEmpty() } ?: parts.node
    if (node != parts.node) invalidTask()
    val type = row.text("type")?.takeIf { it.isNotEmpty() } ?: parts.type
    if (type != parts.type || type.length > 128) invalidTask()
    val subjectId = if (!row.containsKey("id") || row["id"] is JsonNull) parts.subjectId else row.subject()
    if (subjectId != parts.subjectId || subjectId.length > 256) invalidTask()
    val user = row.text("user")?.takeIf { it.isNotEmpty() } ?: parts.user
    if (user.length > 256) invalidTask()
    val startedAt = row.epoch("starttime")
    val endedAt = row.epoch("endtime")
    val status = row.text("status")?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
    if (status != null && status.length > 512) invalidTask()
    return ClusterTask(remote, upid, node, type, subjectId, user, startedAt, endedAt, status, classifyTask(status, endedAt))
}

private data class UpidParts(val node: String, val type: String, val subjectId: String, val user: String)

private val nodePattern = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")

/** Official PVE UPID shape, with hex widths widened so a longer process field is not mistaken for a broken feed. */
private val upidPattern = Regex("^UPID:([A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?):([0-9A-Fa-f]{8,16}):([0-9A-Fa-f]{8,16}):([0-9A-Fa-f]{8,16}):([^:\\s]+):([^:\\s]*):([^:\\s]+):$")

fun parseServerUpid(value: String): ServerUpid? {
    if (value.length > 1024) return null
    val match = upidPattern.matchEntire(value) ?: return null
    return ServerUpid(match.groupValues[1], match.groupValues[5], match.groupValues[6], match.groupValues[7])
}

private fun parseUpid(value: String): UpidParts? {
    val parsed = parseServerUpid(value) ?: return null
    return UpidParts(parsed.node, parsed.type, parsed.subjectId, parsed.user)
}

private fun JsonObject.text(key: String): String? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: invalidTask()
    if (!primitive.isString) invalidTask()
    return primitive.content.trim()
}

private fun JsonObject.subject(): String {
    val value = this["id"] ?: invalidTask()
    if (value is JsonNull) invalidTask()
    val primitive = value as? JsonPrimitive ?: invalidTask()
    if (primitive.isString) return primitive.content.trim()
    val number = primitive.longOrNull ?: invalidTask()
    if (number < 0) invalidTask()
    return number.toString()
}

private fun JsonObject.epoch(key: String): Instant? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: invalidTask()
    if (primitive.isString) invalidTask()
    val seconds = primitive.longOrNull ?: invalidTask()
    if (seconds < 0) invalidTask()
    return try { Instant.ofEpochSecond(seconds) } catch (_: Exception) { invalidTask() }
}

private fun invalidTask(): Nothing = throw IllegalArgumentException("The server returned an invalid task record.")

private fun demoTask(upid: String, node: String, type: String, subjectId: String, startedAt: Instant, endedAt: Instant?, statusText: String?) =
    ClusterTask(DEMO_REMOTE_KEY, upid, node, type, subjectId, "demo@ringmaster", startedAt, endedAt, statusText, classifyTask(statusText, endedAt))

/** Labeled local sample tasks. They are never requested from a server. */
val demoTasks: List<ClusterTask> = listOf(
    demoTask("demo:vzdump:121", "demo-node-a", "vzdump", "121", Instant.parse("2026-11-07T15:04:00Z"), null, "running"),
    demoTask("demo:qmstart:120", "demo-node-a", "qmstart", "120", Instant.parse("2026-11-07T15:00:00Z"), Instant.parse("2026-11-07T15:00:12Z"), "OK"),
    demoTask("demo:lab-maintenance:130", "demo-node-b", "lab-maintenance", "130", Instant.parse("2026-11-07T14:00:00Z"), null, null),
)
