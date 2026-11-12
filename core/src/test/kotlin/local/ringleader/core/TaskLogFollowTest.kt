package local.ringleader.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskLogRulesTest {
    private val upid = "UPID:node-a:00000010:00000011:65ABCDEF:qmstart:100:tester@pve:"

    @Test fun emptyLogPlaceholderIsAnEmptyPage() {
        val empty = parseTaskLogPage("""{"data":[{"n":1,"t":"no content"}],"total":1}""")
        assertTrue(empty.lines.isEmpty())
        assertEquals(0, empty.total)
        assertTrue(parseTaskLogPage("""{"data":[{"n":1,"t":"no content"}]}""").lines.isEmpty())
        // A real line that happens to read the same is kept when the log has more lines.
        assertEquals(1, parseTaskLogPage("""{"data":[{"n":1,"t":"no content"}],"total":3}""").lines.size)
        assertEquals(1, parseTaskLogPage("""{"data":[{"n":2,"t":"no content"}],"total":2}""").lines.size)
    }

    @Test fun statusForAnotherNodeOrTaskIsRefused() {
        val body = """{"data":{"status":"running","type":"qmstart","id":"100","user":"tester@pve","node":"node-a","upid":"$upid","pid":16,"starttime":1700000000}}"""
        assertEquals("node-a", parseTaskLogStatus(body, "node-a", upid).node)
        assertFailsWith<IllegalArgumentException> { parseTaskLogStatus(body, "node-b", upid) }
        assertFailsWith<IllegalArgumentException> { parseTaskLogStatus(body, "node-a", upid.replace("qmstart", "qmstop")) }
        // Without a request route the parser keeps its old behaviour, and a body without upid is still read.
        assertEquals("node-a", parseTaskLogStatus(body).node)
        val noUpid = body.replace(""","upid":"$upid"""", "")
        assertEquals("node-a", parseTaskLogStatus(noUpid, "node-a", upid).node)
    }

    @Test fun retryDelayIsBoundedAndCapKeepsTheNewestLines() {
        assertEquals(listOf(2_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (0..6).map(::taskLogRetryDelay))
        assertEquals(30_000L, taskLogRetryDelay(Int.MAX_VALUE))
        val lines = (1..7).map { TaskLogLine(it, "line $it") }
        val (kept, dropped) = capTaskLogLines(lines, 5)
        assertEquals(listOf(3, 4, 5, 6, 7), kept.map { it.n })
        assertEquals(2, dropped)
        assertEquals(lines to 0, capTaskLogLines(lines, 7))
        assertNull(taskLogDroppedText(0))
        assertTrue(taskLogDroppedText(2)!!.startsWith("2 older lines are not kept"))
        assertTrue(taskLogSaveText(kept, 2).startsWith("[RingMASTER: 2 older lines"))
        assertEquals(taskLogText(kept), taskLogSaveText(kept, 0))
    }

    @Test fun savedLogIsOwnerOnly() {
        val directory = Files.createTempDirectory("ringmaster-tasklog")
        try {
            val file = directory.resolve("task-log.txt")
            writeOwnerOnlyText(file, "first")
            writeOwnerOnlyText(file, "TASK OK")
            assertEquals("TASK OK", Files.readString(file))
            if ("posix" in file.fileSystem.supportedFileAttributeViews()) {
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
            } else {
                val view = Files.getFileAttributeView(file, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                if (view != null) assertEquals(1, view.acl.size)
            }
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TaskLogFollowWorkspaceTest {
    private val remote = "https://example.test:8006"

    private class QuietInventory : InventoryGateway {
        override suspend fun overview(connection: Connection) = Overview(0, emptyList())
        override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
    }

    private class ScriptedLog : TaskLogGateway {
        var statusCalls = 0
        val statuses = ArrayDeque<() -> TaskLogStatus>()
        var pageBlock: (Int) -> TaskLogPage = { TaskLogPage(emptyList(), 0) }
        override suspend fun status(connection: Connection, node: String, upid: String): TaskLogStatus {
            statusCalls++
            return (statuses.removeFirstOrNull() ?: error("no status scripted"))()
        }
        override suspend fun page(connection: Connection, node: String, upid: String, start: Int, limit: Int) = pageBlock(start)
    }

    private fun TestScope.step(millis: Long) { advanceTimeBy(millis); runCurrent() }

    private fun status(running: Boolean) = TaskLogStatus(
        if (running) "running" else "stopped", if (running) null else "OK", Instant.ofEpochSecond(1700000000),
        if (running) null else Instant.ofEpochSecond(1700000010), 10, "vzdump", "100", "tester@pve", "node-a",
    )

    private fun task(): ClusterTask {
        val id = "UPID:node-a:00000010:00000011:65ABCDEF:vzdump:100:tester@pve:"
        return parseClusterTasks("""{"data":[{"upid":"$id","node":"node-a","type":"vzdump","id":"100","user":"tester@pve","starttime":1700000000}]}""", remote).single()
    }

    @Test fun transientFailureKeepsFollowingWithBackoff() = runTest {
        val logs = ScriptedLog()
        logs.statuses.addAll(listOf({ status(true) }, { throw IOException("blip") }, { throw IOException("blip") }, { status(true) }, { status(false) }))
        logs.pageBlock = { start -> if (start == 0) TaskLogPage(listOf(TaskLogLine(1, "INFO: start")), 1) else TaskLogPage(listOf(TaskLogLine(start + 1, "INFO: line ${start + 1}")), start + 1) }
        val workspace = Workspace(this, QuietInventory(), taskLogGateway = logs)
        workspace.connect(Connection(remote, "tester@pve!read", "test-only-secret", "")); runCurrent()
        workspace.openTaskLog(task()); runCurrent()
        assertEquals(1, logs.statusCalls)
        step(TASK_LOG_POLL_MS)
        assertEquals(2, logs.statusCalls)
        val failed = workspace.state.value.taskLog!!
        assertTrue(failed.error!!.contains("Trying again in 2 s"), failed.error)
        assertEquals(listOf(1), failed.lines.map { it.n })
        step(2_000)
        assertEquals(3, logs.statusCalls)
        assertTrue(workspace.state.value.taskLog!!.error!!.contains("Trying again in 4 s"))
        step(3_999)
        assertEquals(3, logs.statusCalls)
        step(1)
        assertEquals(4, logs.statusCalls)
        assertNull(workspace.state.value.taskLog!!.error)
        assertEquals(listOf(1, 2), workspace.state.value.taskLog!!.lines.map { it.n })
        step(TASK_LOG_POLL_MS)
        assertEquals(5, logs.statusCalls)
        step(TASK_LOG_POLL_MS * 5)
        assertEquals(5, logs.statusCalls)
        workspace.close()
    }

    @Test fun followKeepsOnlyTheNewestLinesAndCountsTheRest() = runTest {
        val logs = ScriptedLog()
        logs.statuses.addAll(List(12) { { status(true) } } + listOf({ status(false) }))
        logs.pageBlock = { start -> TaskLogPage((start + 1..start + TASK_LOG_PAGE_LIMIT).map { TaskLogLine(it, "line $it") }, start + TASK_LOG_PAGE_LIMIT) }
        val workspace = Workspace(this, QuietInventory(), taskLogGateway = logs)
        workspace.connect(Connection(remote, "tester@pve!read", "test-only-secret", "")); runCurrent()
        workspace.openTaskLog(task()); runCurrent()
        repeat(12) { step(TASK_LOG_POLL_MS) }
        val log = workspace.state.value.taskLog!!
        assertEquals(TASK_LOG_KEEP_LINES, log.lines.size)
        assertEquals(13 * TASK_LOG_PAGE_LIMIT - TASK_LOG_KEEP_LINES, log.dropped)
        assertEquals(13 * TASK_LOG_PAGE_LIMIT, log.lines.last().n)
        assertEquals(log.dropped + 1, log.lines.first().n)
        workspace.close()
    }
}
