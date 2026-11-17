package local.ringleader.core

import java.time.Instant

/*
 * The logs of the sample fleet's tasks: a backup that is still running (with a tail that can be revealed line by line),
 * a start that ended well and a maintenance task that failed.
 */

/** The log of a sample task as it stands. Nothing here is read from a server. */
fun sampleTaskLog(task: ClusterTask): TaskLogState {
    val spec = sampleSpec(task.type, task)
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

/** The lines the running backup writes next. Empty for a task that has already ended. */
fun sampleTaskLogTail(task: ClusterTask): List<TaskLogLine> {
    if (task.type != "vzdump") return emptyList()
    return vzdumpTail.numbered(sampleTaskLog(task).lines.size + 1)
}

/** The backup's status once its tail has been written. */
fun sampleTaskLogFinished(task: ClusterTask): TaskLogStatus = TaskLogStatus(
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

private data class SampleSpec(val lines: List<String>, val status: TaskLogStatus)

private fun sampleSpec(type: String, task: ClusterTask): SampleSpec = when (type) {
    "vzdump" -> SampleSpec(vzdumpProgress, runningStatus(task, 8801))
    "qmstart" -> SampleSpec(qmstartLines, stoppedStatus(task, "OK", 1201, task.endedAt))
    "lab-maintenance" -> SampleSpec(
        maintenanceLines,
        stoppedStatus(task, "command 'lab-maintenance' failed: exit code 1", 4242, task.startedAt?.plusSeconds(40)),
    )
    else -> SampleSpec(listOf("INFO: sample task has no server log."), stoppedStatus(task, "OK", 1001, task.endedAt ?: task.startedAt))
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
