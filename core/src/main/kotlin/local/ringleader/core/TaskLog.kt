package local.ringleader.core

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** First page and each follow or load-more read. Matches the Proxmox task viewer page size. */
const val TASK_LOG_PAGE_LIMIT = 500

/** While a task is running, read status and any new lines on this interval. */
const val TASK_LOG_POLL_MS = 2_000L

const val TASK_LOG_DIRECT_ONLY =
    "Task logs are read from the node directly; open a direct Proxmox VE connection."

const val TASK_LOG_AUDIT_DENIED = "Not permitted for this token (needs Sys.Audit on the node)"

/** One log record. [n] is the server line number, starting at 1. [text] has no trailing newline. */
data class TaskLogLine(val n: Int, val text: String) {
    init {
        require(n > 0) { INVALID_TASK_LOG }
        require(text.none { it != '\t' && it.isISOControl() }) { INVALID_TASK_LOG }
        require(text.length <= 16_384) { INVALID_TASK_LOG }
    }
}

/**
 * One page from `GET /api2/json/nodes/{node}/tasks/{upid}/log`.
 * [total] is the server total when the response included one, otherwise the highest line number on this page.
 */
data class TaskLogPage(val lines: List<TaskLogLine>, val total: Int) {
    init {
        require(total >= 0) { INVALID_TASK_LOG }
    }
}

/**
 * One task status from `GET /api2/json/nodes/{node}/tasks/{upid}/status`.
 * [status] is `running` or `stopped`. [exitStatus] is kept only after the task has stopped.
 */
data class TaskLogStatus(
    val status: String,
    val exitStatus: String?,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val pid: Long?,
    val type: String,
    val id: String,
    val user: String,
    val node: String,
) {
    val running: Boolean get() = status == "running"
    init {
        require(status == "running" || status == "stopped") { INVALID_TASK_LOG }
        require(exitStatus?.none { it.isISOControl() } != false && (exitStatus?.length ?: 0) <= 512) { INVALID_TASK_LOG }
        require(type.isNotBlank() && type.length <= 128 && type.none { it.isISOControl() }) { INVALID_TASK_LOG }
        require(id.length <= 256 && id.none { it.isISOControl() }) { INVALID_TASK_LOG }
        require(user.length <= 256 && user.none { it.isISOControl() }) { INVALID_TASK_LOG }
        require(node.isNotBlank() && node.length <= 128 && node.none { it.isISOControl() }) { INVALID_TASK_LOG }
        require(pid == null || pid > 0) { INVALID_TASK_LOG }
        if (status == "running") require(exitStatus == null && endedAt == null) { INVALID_TASK_LOG }
    }
}

/** Open log for one Activity row. The raw UPID stays on [task] and is not a display field. */
data class TaskLogState(
    val task: ClusterTask,
    val lines: List<TaskLogLine> = emptyList(),
    val total: Int = 0,
    val status: TaskLogStatus? = null,
    val error: String? = null,
    val loading: Boolean = false,
    val following: Boolean = true,
)

/** Lines kept across polls. [running] is false once a stopped status has been applied. */
data class TaskLogSnapshot(val lines: List<TaskLogLine>, val total: Int, val running: Boolean)

/** A running task is polled again. A stopped task is not. */
fun taskLogKeepsPolling(status: TaskLogStatus): Boolean = status.status == "running"

/** Running / OK / Error. The error chip includes the server exit status when it sent one. */
fun taskLogChip(status: TaskLogStatus): String = when {
    status.status == "running" -> "Running"
    status.exitStatus == "OK" -> "OK"
    status.exitStatus?.trim()?.startsWith("WARNINGS", ignoreCase = true) == true -> "Warnings · " + status.exitStatus.trim().substringAfter(':').trim()
    status.exitStatus.isNullOrBlank() -> "Error"
    else -> "Error · ${status.exitStatus}"
}

fun taskLogHasMore(lines: List<TaskLogLine>, total: Int): Boolean {
    val last = lines.lastOrNull()?.n ?: 0
    return total > last
}

/** Append lines whose number has not been seen. A repeated page does not duplicate or replace text. */
fun mergeTaskLogLines(existing: List<TaskLogLine>, incoming: List<TaskLogLine>): List<TaskLogLine> {
    if (incoming.isEmpty()) return existing
    val seen = existing.mapTo(HashSet()) { it.n }
    val extra = incoming.filter { seen.add(it.n) }
    return if (extra.isEmpty()) existing else existing + extra
}

/** One follow step: keep prior lines, append only new numbers, and stop when the status is stopped. */
fun applyTaskLogPoll(current: TaskLogSnapshot, status: TaskLogStatus, page: TaskLogPage): TaskLogSnapshot {
    val lines = mergeTaskLogLines(current.lines, page.lines)
    val total = maxOf(current.total, page.total, lines.lastOrNull()?.n ?: 0)
    return TaskLogSnapshot(lines, total, taskLogKeepsPolling(status))
}

/**
 * Log route for one node and UPID.
 * The UPID is checked and then encoded as a single path segment. [start] is the last line already shown.
 */
fun taskLogPath(node: String, upid: String, start: Int, limit: Int): String {
    require(start >= 0 && limit in 1..10_000) { "The task log request is not usable." }
    val status = taskStatusPath(node, upid)
    require(status.startsWith("/api2/json/nodes/") && status.endsWith("/status")) { "The task log request is not usable." }
    return status.removeSuffix("/status") + "/log?start=$start&limit=$limit"
}

fun parseTaskLogPage(body: String): TaskLogPage {
    val payload = jsonObject(body)
    if ("data" !in payload || payload["data"] is JsonNull) invalidLog()
    val rows = payload["data"] as? JsonArray ?: invalidLog()
    val lines = rows.map { parseTaskLogLine(it) }
    val total = when (val raw = payload["total"]) {
        null, is JsonNull -> lines.maxOfOrNull { it.n } ?: 0
        is JsonPrimitive -> {
            if (raw.isString) invalidLog()
            val number = raw.longOrNull ?: invalidLog()
            if (number < 0 || number > Int.MAX_VALUE) invalidLog()
            number.toInt()
        }
        else -> invalidLog()
    }
    return TaskLogPage(lines, total)
}

fun parseTaskLogStatus(body: String): TaskLogStatus {
    val row = jsonObject(body)["data"] as? JsonObject ?: invalidLog()
    val status = row.text("status") ?: invalidLog()
    if (status != "running" && status != "stopped") invalidLog()
    val exitStatus = row.text("exitstatus")
    val endedAt = row.epoch("endtime")
    return TaskLogStatus(
        status = status,
        exitStatus = if (status == "running") null else exitStatus,
        startedAt = row.epoch("starttime"),
        endedAt = if (status == "running") null else endedAt,
        pid = row.whole("pid"),
        type = row.text("type") ?: invalidLog(),
        id = row.subjectId(),
        user = row.text("user") ?: "",
        node = row.text("node") ?: invalidLog(),
    )
}

/** Plain log body for the clipboard and Save. Line numbers stay in the gutter, not in the copy. */
fun taskLogText(lines: List<TaskLogLine>): String = lines.joinToString("\n") { it.text }

/** Local sample for a demo task. Nothing here is read from a server. */
fun demoTaskLog(task: ClusterTask): TaskLogState {
    val spec = demoSpec(task.type, task)
    val lines = spec.lines.numbered()
    return TaskLogState(
        task = task,
        lines = lines,
        total = lines.lastOrNull()?.n ?: 0,
        status = spec.status,
        error = null,
        loading = false,
        following = spec.status.running,
    )
}

/** Extra demo lines revealed one per poll. Empty when the sample is already finished. */
fun demoTaskLogTail(task: ClusterTask): List<TaskLogLine> {
    if (task.type != "vzdump") return emptyList()
    return vzdumpTail.numbered(demoTaskLog(task).lines.size + 1)
}

/** Status once the demo backup sample has played out. */
fun demoTaskLogFinished(task: ClusterTask): TaskLogStatus = TaskLogStatus(
    status = "stopped",
    exitStatus = "OK",
    startedAt = task.startedAt,
    endedAt = task.startedAt?.plusSeconds(72),
    pid = 8801,
    type = task.type,
    id = task.subjectId,
    user = task.user,
    node = task.node,
)

private data class DemoSpec(val lines: List<String>, val status: TaskLogStatus)

private fun demoSpec(type: String, task: ClusterTask): DemoSpec = when (type) {
    "vzdump" -> DemoSpec(vzdumpProgress, runningStatus(task, 8801))
    "qmstart" -> DemoSpec(qmstartLines, stoppedStatus(task, "OK", 1201, task.endedAt))
    "lab-maintenance" -> DemoSpec(
        maintenanceLines,
        stoppedStatus(task, "command 'lab-maintenance' failed: exit code 1", 4242, task.startedAt?.plusSeconds(40)),
    )
    else -> DemoSpec(listOf("INFO: sample task has no server log."), stoppedStatus(task, "OK", 1001, task.endedAt ?: task.startedAt))
}

private fun runningStatus(task: ClusterTask, pid: Long) = TaskLogStatus(
    status = "running",
    exitStatus = null,
    startedAt = task.startedAt,
    endedAt = null,
    pid = pid,
    type = task.type,
    id = task.subjectId,
    user = task.user,
    node = task.node,
)

private fun stoppedStatus(task: ClusterTask, exitStatus: String, pid: Long, endedAt: Instant?) = TaskLogStatus(
    status = "stopped",
    exitStatus = exitStatus,
    startedAt = task.startedAt,
    endedAt = endedAt,
    pid = pid,
    type = task.type,
    id = task.subjectId,
    user = task.user,
    node = task.node,
)

private val vzdumpProgress = listOf(
    "INFO: starting new backup job: vzdump --mode snapshot 121",
    "INFO: Starting Backup of VM 121 (lxc)",
    "INFO: Backup started at 2026-11-07 15:04:00",
    "INFO: status = running",
    "INFO: backup mode: snapshot",
    "INFO: ionice priority: 7",
    "INFO: creating vzdump archive '/var/lib/vz/dump/vzdump-lxc-121-2026_11_07-15_04_00.tar.zst'",
    "INFO: 0%: 0 B",
    "INFO: 10%: 128.0 MiB",
    "INFO: 25%: 320.0 MiB",
    "INFO: 50%: 640.0 MiB",
    "INFO: 75%: 960.0 MiB",
)

private val vzdumpTail = listOf(
    "INFO: 90%: 1.1 GiB",
    "INFO: 100%: 1.2 GiB",
    "INFO: archive file size: 1.2 GiB",
    "INFO: Finished Backup of VM 121 (00:01:12)",
    "INFO: Backup finished at 2026-11-07 15:05:12",
    "TASK OK",
)

private val qmstartLines = listOf(
    "NOTICE: starting VM 120 on node demo-node-a",
    "INFO: VM 120 started on demo-node-a",
    "TASK OK",
)

private val maintenanceLines = listOf(
    "INFO: starting maintenance for guest 130",
    "INFO: checking storage shared-data",
    "WARN: storage shared-data reported a delayed response",
    "ERROR: hook script exited with status 1",
    "TASK ERROR: command 'lab-maintenance' failed: exit code 1",
)

private fun List<String>.numbered(start: Int = 1): List<TaskLogLine> = mapIndexed { index, text -> TaskLogLine(start + index, text) }

private const val INVALID_TASK_LOG = "The server returned an invalid task log."

private fun invalidLog(): Nothing = throw IllegalArgumentException(INVALID_TASK_LOG)

private fun jsonObject(body: String): JsonObject {
    val root = try {
        Json.parseToJsonElement(body)
    } catch (_: Exception) {
        invalidLog()
    }
    return root as? JsonObject ?: invalidLog()
}

private fun parseTaskLogLine(element: JsonElement): TaskLogLine {
    val row = element as? JsonObject ?: invalidLog()
    val number = row.whole("n") ?: invalidLog()
    if (number > Int.MAX_VALUE) invalidLog()
    val raw = row.rawText("t") ?: invalidLog()
    val text = raw.trimEnd('\r', '\n').filter { it == '\t' || !it.isISOControl() }.take(16_384)
    return TaskLogLine(number.toInt(), text)
}

private fun JsonObject.text(key: String): String? {
    val value = rawText(key) ?: return null
    val cleaned = value.replace(Regex("\\s+"), " ").trim()
    if (cleaned.isEmpty()) return null
    if (cleaned.length > 512 || cleaned.any { it.isISOControl() }) invalidLog()
    return cleaned
}

private fun JsonObject.rawText(key: String): String? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: invalidLog()
    if (!primitive.isString) invalidLog()
    return primitive.content
}

private fun JsonObject.whole(key: String): Long? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: invalidLog()
    if (primitive.isString) invalidLog()
    return primitive.longOrNull ?: invalidLog()
}

private fun JsonObject.subjectId(): String {
    val value = this["id"] ?: return ""
    if (value is JsonNull) return ""
    val primitive = value as? JsonPrimitive ?: invalidLog()
    if (primitive.isString) {
        val text = primitive.content.trim()
        if (text.any { it.isISOControl() }) invalidLog()
        return text
    }
    val number = primitive.longOrNull ?: invalidLog()
    if (number < 0) invalidLog()
    return number.toString()
}

private fun JsonObject.epoch(key: String): Instant? {
    val seconds = whole(key) ?: return null
    if (seconds < 0) invalidLog()
    return try {
        Instant.ofEpochSecond(seconds)
    } catch (_: Exception) {
        invalidLog()
    }
}
