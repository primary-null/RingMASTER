package local.ringleader.core

import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskLogTest {
    private val upid = "UPID:node-a:00000010:00000011:65ABCDEF:qmstart:100:tester@pve!read:"

    @Test fun logPageParsesTotalMissingTotalAndEmpty() {
        val withTotal = parseTaskLogPage("""{"data":[{"n":1,"t":"hello\n"}],"total":40}""")
        assertEquals(40, withTotal.total)
        assertEquals(listOf(TaskLogLine(1, "hello")), withTotal.lines)

        val noTotal = parseTaskLogPage("""{"data":[{"n":2,"t":"world\r\n"},{"n":3,"t":"next"}]}""")
        assertEquals(3, noTotal.total)
        assertEquals("world", noTotal.lines.first().text)
        assertEquals(listOf(2, 3), noTotal.lines.map { it.n })

        val empty = parseTaskLogPage("""{"data":[]}""")
        assertEquals(0, empty.total)
        assertTrue(empty.lines.isEmpty())
        assertFails { parseTaskLogPage("not-json") }
        assertFails { parseTaskLogPage("""{"data":[{"n":1}]}""") }
    }

    @Test fun statusParsesRunningOkAndErrorExit() {
        val running = parseTaskLogStatus("""{"data":{"status":"running","type":"vzdump","id":"121","user":"root@pam","node":"pve","pid":1234,"starttime":1700000000}}""")
        assertTrue(running.running)
        assertNull(running.exitStatus)
        assertNull(running.endedAt)
        assertEquals(1234L, running.pid)
        assertEquals(Instant.ofEpochSecond(1700000000), running.startedAt)
        assertEquals("Running", taskLogChip(running))

        val ok = parseTaskLogStatus("""{"data":{"status":"stopped","exitstatus":"OK","type":"qmstart","id":120,"user":"root@pam","node":"pve","pid":55,"starttime":1700000000,"endtime":1700000012}}""")
        assertFalse(ok.running)
        assertEquals("OK", ok.exitStatus)
        assertEquals("120", ok.id)
        assertEquals(Instant.ofEpochSecond(1700000012), ok.endedAt)
        assertEquals("OK", taskLogChip(ok))

        val failed = parseTaskLogStatus("""{"data":{"status":"stopped","exitstatus":"command 'qmstart' failed: exit code 1","type":"qmstart","id":"120","user":"root@pam","node":"pve","pid":55,"starttime":1700000000,"endtime":1700000012}}""")
        assertEquals("Error · command 'qmstart' failed: exit code 1", taskLogChip(failed))
        assertFalse(taskLogKeepsPolling(failed))
        assertTrue(taskLogKeepsPolling(running))
    }

    @Test fun upidValidationRejectsMalformedAndInjection() {
        val path = taskLogPath("node-a", upid, 0, 500)
        assertTrue(path.startsWith("/api2/json/nodes/node-a/tasks/"))
        assertTrue(path.endsWith("/log?start=0&limit=500"))
        assertTrue(path.contains(encodePathSegment(upid)))
        assertFalse(path.contains("UPID:"))
        assertFalse(path.contains("!"))
        assertFalse(path.contains("@"))
        assertTrue(path.contains("%3A"))
        assertTrue(path.contains("%40"))
        assertTrue(path.contains("%21"))
        assertEquals("/api2/json/nodes/node-a/tasks/${encodePathSegment(upid)}/status", taskStatusPath("node-a", upid))

        assertFails { taskLogPath("node-a", upid, -1, 500) }
        assertFails { taskLogPath("node-a", "not-a-task", 0, 500) }
        assertFails { taskLogPath("node-a", upid.dropLast(1), 0, 500) }
        assertFails { taskLogPath("../node-a", upid, 0, 500) }
        assertFails { taskLogPath("node-a/../etc", upid, 0, 500) }
        assertFails { taskLogPath("node-a", "$upid/../secret", 0, 500) }
        assertFails { taskLogPath("node-a", "$upid?download=1", 0, 500) }
        assertFails { taskLogPath("node-a", "UPID:node-a\n:00000010:00000011:65ABCDEF:qmstart:100:user:", 0, 500) }
        val hostile = "UPID:node-a:00000010:00000011:65ABCDEF:qmstart:../../x:user:"
        val hostilePath = taskLogPath("node-a", hostile, 0, 500)
        val segment = hostilePath.substringAfter("/tasks/").substringBefore("/log")
        assertFalse(segment.contains("/"))
        assertFalse(hostilePath.contains("../"))
        assertTrue(hostilePath.contains("%2F"))
        assertTrue(hostilePath.endsWith("/log?start=0&limit=500"))
        assertFails { taskStatusPath("node-a", "UPID:evil/node:00000010:00000011:65ABCDEF:qmstart:100:user:") }
        assertFails { taskLogPath("other-node", upid, 0, 500) }
    }

    @Test fun followAppendsOnlyNewLinesAndStopsAfterStopped() {
        val running = sampleStatus("running", null)
        val stopped = sampleStatus("stopped", "OK")
        val polls = listOf(
            running to TaskLogPage(listOf(line(1, "start"), line(2, "INFO: 10%")), 4),
            running to TaskLogPage(listOf(line(2, "INFO: 10% again"), line(3, "INFO: 50%")), 4),
            stopped to TaskLogPage(listOf(line(3, "INFO: 50% again"), line(4, "TASK OK")), 4),
            running to TaskLogPage(listOf(line(5, "must not append")), 5),
        )
        var snapshot = TaskLogSnapshot(emptyList(), 0, true)
        var applied = 0
        for ((status, page) in polls) {
            if (applied > 0 && !taskLogKeepsPolling(status) && !snapshot.running) break
            if (applied > 0 && !snapshot.running) break
            snapshot = applyTaskLogPoll(snapshot, status, page)
            applied++
            if (!snapshot.running) break
        }
        assertEquals(3, applied)
        assertEquals(listOf(1, 2, 3, 4), snapshot.lines.map { it.n })
        assertEquals(listOf("start", "INFO: 10%", "INFO: 50%", "TASK OK"), snapshot.lines.map { it.text })
        assertFalse(snapshot.running)
        assertFalse(taskLogKeepsPolling(stopped))
    }

    @Test fun demoFixturesCoverBackupStartAndError() {
        val backup = demoTaskLog(demoTasks.first { it.type == "vzdump" })
        assertTrue(backup.status!!.running)
        assertTrue(backup.lines.any { "50%" in it.text && "640.0 MiB" in it.text })
        assertTrue(backup.lines.none { it.text.contains("UPID:") })
        assertTrue(demoTaskLogTail(backup.task).any { it.text.contains("100%") })

        val started = demoTaskLog(demoTasks.first { it.type == "qmstart" })
        assertEquals("OK", started.status!!.exitStatus)
        assertEquals("TASK OK", started.lines.last().text)
        assertTrue(demoTaskLogTail(started.task).isEmpty())

        val failed = demoTaskLog(demoTasks.first { it.type == "lab-maintenance" })
        assertTrue(failed.lines.last().text.startsWith("TASK ERROR:"))
        assertTrue(failed.lines.any { it.text.startsWith("WARN:") })
        assertFalse(failed.status!!.running)
        assertTrue(taskLogChip(failed.status).startsWith("Error"))
    }

    private fun line(n: Int, text: String) = TaskLogLine(n, text)

    private fun sampleStatus(status: String, exitStatus: String?) = TaskLogStatus(
        status = status,
        exitStatus = if (status == "running") null else exitStatus,
        startedAt = Instant.ofEpochSecond(1700000000),
        endedAt = if (status == "running") null else Instant.ofEpochSecond(1700000012),
        pid = 10,
        type = "qmstart",
        id = "100",
        user = "tester@pve",
        node = "node-a",
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class TaskLogWorkspaceTest {
    private val remote = "https://example.test:8006"

    private class QuietInventory : InventoryGateway {
        override suspend fun overview(connection: Connection) = Overview(0, emptyList())
        override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
    }

    private class QuietPdm : PdmReadGateway {
        override suspend fun read(connection: Connection): PdmFleetDocument = error("PDM inventory is not part of this test.")
    }

    private class FakeLog : TaskLogGateway {
        var statusCalls = 0
        var pageCalls = 0
        val starts = mutableListOf<Int>()
        var statusBlock: suspend () -> TaskLogStatus = { error("unset") }
        var pageBlock: suspend (Int, Int) -> TaskLogPage = { _, _ -> error("unset") }
        override suspend fun status(connection: Connection, node: String, upid: String): TaskLogStatus {
            statusCalls++
            return statusBlock()
        }
        override suspend fun page(connection: Connection, node: String, upid: String, start: Int, limit: Int): TaskLogPage {
            pageCalls++
            starts += start
            assertEquals(TASK_LOG_PAGE_LIMIT, limit)
            return pageBlock(start, limit)
        }
    }

    @Test fun followPollAppendsNewLinesAndStopsAfterStopped() = runTest {
        val logs = FakeLog()
        var reads = 0
        logs.statusBlock = {
            reads++
            if (reads >= 3) stopped("OK") else running()
        }
        logs.pageBlock = { start, _ ->
            when (start) {
                0 -> TaskLogPage(listOf(TaskLogLine(1, "start"), TaskLogLine(2, "INFO: 10%")), 4)
                2 -> TaskLogPage(listOf(TaskLogLine(2, "INFO: 10% again"), TaskLogLine(3, "INFO: 50%")), 4)
                else -> TaskLogPage(listOf(TaskLogLine(3, "INFO: 50% again"), TaskLogLine(4, "TASK OK")), 4)
            }
        }
        val workspace = Workspace(this, QuietInventory(), taskLogGateway = logs, pdmGateway = QuietPdm())
        workspace.connect(Connection(remote, "tester@pve!read", "test-only-secret", ""))
        runCurrent()
        workspace.openTaskLog(taskOf())
        runCurrent()
        assertEquals(listOf(1, 2), workspace.state.value.taskLog!!.lines.map { it.n })
        assertTrue(workspace.state.value.taskLog!!.status!!.running)
        step(TASK_LOG_POLL_MS)
        assertEquals(listOf(1, 2, 3), workspace.state.value.taskLog!!.lines.map { it.n })
        assertEquals("INFO: 10%", workspace.state.value.taskLog!!.lines[1].text)
        step(TASK_LOG_POLL_MS)
        val done = workspace.state.value.taskLog!!
        assertEquals(listOf("start", "INFO: 10%", "INFO: 50%", "TASK OK"), done.lines.map { it.text })
        assertFalse(done.status!!.running)
        assertEquals("OK", done.status.exitStatus)
        val calls = logs.statusCalls
        val pages = logs.pageCalls
        step(TASK_LOG_POLL_MS * 2)
        assertEquals(calls, logs.statusCalls)
        assertEquals(pages, logs.pageCalls)
        assertFalse(workspace.state.value.toString().contains("test-only-secret"))
        workspace.close()
    }

    @Test fun permissionRefusalKeepsTheAuditMessageAndStops() = runTest {
        val logs = FakeLog()
        logs.statusBlock = { throw GuestPermissionException() }
        val workspace = Workspace(this, QuietInventory(), taskLogGateway = logs, pdmGateway = QuietPdm())
        workspace.connect(Connection(remote, "tester@pve!read", "test-only-secret", ""))
        runCurrent()
        workspace.openTaskLog(taskOf())
        runCurrent()
        val log = workspace.state.value.taskLog!!
        assertEquals(TASK_LOG_AUDIT_DENIED, log.error)
        assertTrue(log.lines.isEmpty())
        assertEquals(0, logs.pageCalls)
        step(TASK_LOG_POLL_MS * 2)
        assertEquals(1, logs.statusCalls)
        workspace.close()
    }

    @Test fun pdmConnectionDoesNotReadANodeLog() = runTest {
        val logs = FakeLog()
        val workspace = Workspace(this, QuietInventory(), taskLogGateway = logs, pdmGateway = QuietPdm())
        workspace.connect(Connection(remote, "tester@pdm!read", "test-only-secret", "", PDM_KIND))
        runCurrent()
        workspace.openTaskLog(taskOf())
        runCurrent()
        assertEquals(TASK_LOG_DIRECT_ONLY, workspace.state.value.taskLog!!.error)
        assertEquals(0, logs.statusCalls)
        assertEquals(0, logs.pageCalls)
        workspace.close()
    }

    @Test fun demoLogStaysLocalAndFinishesTheBackupSample() = runTest {
        val logs = FakeLog()
        val workspace = Workspace(this, QuietInventory(), taskLogGateway = logs, pdmGateway = QuietPdm())
        workspace.exploreDemo()
        runCurrent()
        val backup = demoTasks.first { it.type == "vzdump" }
        workspace.openTaskLog(backup)
        runCurrent()
        assertTrue(workspace.state.value.taskLog!!.lines.any { "75%" in it.text })
        assertEquals(0, logs.statusCalls)
        repeat(8) { step(TASK_LOG_POLL_MS) }
        val finished = workspace.state.value.taskLog!!
        assertEquals("TASK OK", finished.lines.last().text)
        assertEquals("OK", finished.status!!.exitStatus)
        assertFalse(finished.status.running)
        assertEquals(0, logs.pageCalls)
        workspace.openTaskLog(demoTasks.first { it.type == "lab-maintenance" })
        runCurrent()
        assertTrue(workspace.state.value.taskLog!!.lines.last().text.startsWith("TASK ERROR:"))
        workspace.closeTaskLog()
        assertNull(workspace.state.value.taskLog)
        workspace.close()
    }

    @Test fun loadMoreAppendsTheNextPageOnce() = runTest {
        val logs = FakeLog()
        logs.statusBlock = { stopped("OK") }
        logs.pageBlock = { start, _ ->
            if (start == 0) TaskLogPage((1..500).map { TaskLogLine(it, "line $it") }, 501)
            else TaskLogPage(listOf(TaskLogLine(501, "line 501")), 501)
        }
        val workspace = Workspace(this, QuietInventory(), taskLogGateway = logs, pdmGateway = QuietPdm())
        workspace.connect(Connection(remote, "tester@pve!read", "test-only-secret", ""))
        runCurrent()
        workspace.openTaskLog(taskOf())
        runCurrent()
        assertEquals(500, workspace.state.value.taskLog!!.lines.size)
        assertTrue(taskLogHasMore(workspace.state.value.taskLog!!.lines, workspace.state.value.taskLog!!.total))
        workspace.loadMoreTaskLog()
        runCurrent()
        assertEquals(501, workspace.state.value.taskLog!!.lines.size)
        assertEquals("line 501", workspace.state.value.taskLog!!.lines.last().text)
        assertEquals(listOf(0, 500), logs.starts)
        assertFalse(workspace.state.value.taskLog!!.loading)
        workspace.close()
    }

    private fun TestScope.step(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private fun running() = TaskLogStatus("running", null, Instant.ofEpochSecond(1700000000), null, 10, "qmstart", "100", "tester@pve", "node-a")

    private fun stopped(exitStatus: String) = TaskLogStatus(
        "stopped", exitStatus, Instant.ofEpochSecond(1700000000), Instant.ofEpochSecond(1700000012), 10, "qmstart", "100", "tester@pve", "node-a",
    )

    private fun taskOf(): ClusterTask {
        val id = "UPID:node-a:00000010:00000011:65ABCDEF:qmstart:100:tester@pve:"
        val body = """{"data":[{"upid":"$id","node":"node-a","type":"qmstart","id":"100","user":"tester@pve","starttime":1700000000,"endtime":1700000010,"status":"OK"}]}"""
        return parseClusterTasks(body, remote).single()
    }

    @Test fun warningsAreSuccessShownAsWarnings() {
        assertEquals(TaskOutcome.Succeeded, classifyTask("WARNINGS: 1", java.time.Instant.EPOCH))
        assertEquals(TaskOutcome.Unsuccessful, classifyTask("command failed", java.time.Instant.EPOCH))
        val chip = taskLogChip(TaskLogStatus("stopped", "WARNINGS: 1", null, null, null, "vzcreate", "115", "u@pve", "n1"))
        assertEquals("Warnings · 1", chip)
    }
}
