package local.ringleader.core

import java.time.Instant
import kotlin.test.*

class ClusterTasksTest {
    private val remote = "https://example.test:8006"

    @Test fun readsSafeSummaryFieldsAndKeepsUnknownTypes() {
        val newer = record(id = "200", type = "lab-maintenance", start = 1700000200, extra = """"saved":true,"pid":99999,"pstart":11,"tokenid":"tester@pve!secret-token","log":"secret log line"""")
        val older = record(id = "100", pstart = "000000111")
        val numeric = """{"upid":"${upid(id = "112")}","node":"node-a","type":"qmstart","id":112,"user":"tester@pve","starttime":1700000100,"endtime":1700000110,"status":"OK"}"""
        val tasks = parseClusterTasks("""{"data":[$older,$newer,$numeric]}""", remote)
        assertEquals(listOf("200", "112", "100"), tasks.map { it.subjectId })
        val unknown = tasks.first()
        assertEquals("lab-maintenance", unknown.type)
        assertEquals(remote, unknown.remote)
        assertEquals(TaskIdentity(remote, upid(id = "200", type = "lab-maintenance")), unknown.identity)
        assertEquals("node-a", unknown.node)
        assertEquals("tester@pve", unknown.user)
        assertEquals(Instant.ofEpochSecond(1700000200), unknown.startedAt)
        assertEquals(Instant.ofEpochSecond(1700000010), unknown.endedAt)
        assertEquals("OK", unknown.statusText)
        assertFalse(unknown.toString().contains("secret-token"))
        assertFalse(unknown.toString().contains("secret log line"))
        assertFalse(unknown.toString().contains("99999"))
        assertFalse(unknown.toString().contains("tokenid"))
    }

    @Test fun missingStatusIsUnknownAndRunningRequiresEvidence() {
        val absent = parseClusterTasks("""{"data":[${record(status = null, end = null, start = null)}]}""", remote).single()
        assertNull(absent.startedAt)
        assertNull(absent.endedAt)
        assertNull(absent.statusText)
        assertEquals(TaskOutcome.Unknown, absent.outcome)
        assertEquals("Unknown", taskStatusLabel(absent))

        val endedWithoutStatus = parseClusterTasks("""{"data":[${record(status = null)}]}""", remote).single()
        assertEquals(Instant.ofEpochSecond(1700000010), endedWithoutStatus.endedAt)
        assertEquals(TaskOutcome.Unknown, endedWithoutStatus.outcome)
        assertNotEquals(TaskOutcome.Succeeded, endedWithoutStatus.outcome)

        val running = parseClusterTasks("""{"data":[${record(status = "Running", end = null)}]}""", remote).single()
        assertEquals(TaskOutcome.Running, running.outcome)
        assertEquals("running", taskStatusLabel(running))

        val conflict = parseClusterTasks("""{"data":[${record(status = "running")}]}""", remote).single()
        assertNotNull(conflict.endedAt)
        assertEquals(TaskOutcome.Unknown, conflict.outcome)
        assertEquals("Unknown", taskStatusLabel(conflict))

        val okWithoutEnd = parseClusterTasks("""{"data":[${record(status = "OK", end = null)}]}""", remote).single()
        assertEquals(TaskOutcome.Succeeded, okWithoutEnd.outcome)
        assertEquals("OK", taskStatusLabel(okWithoutEnd))

        val warning = parseClusterTasks("""{"data":[${record(status = "WARNINGS: 1")}]}""", remote).single()
        // Proxmox's own upid_status_is_error treats "WARNINGS: n" as success: the task finished, with warnings.
        assertEquals(TaskOutcome.Succeeded, warning.outcome)
        assertTrue(taskHasWarnings(warning))
        assertEquals("WARNINGS: 1", taskStatusLabel(warning))
        val lowercase = parseClusterTasks("""{"data":[${record(status = "ok")}]}""", remote).single()
        assertEquals(TaskOutcome.Unsuccessful, lowercase.outcome)
        assertNotEquals("OK", taskStatusLabel(lowercase))
    }

    @Test fun duplicateMismatchedAndBrokenFeedsAreRejected() {
        assertFailsWith<IllegalArgumentException> { parseClusterTasks("""{"data":[${record()},${record()}]}""", remote) }
        assertFailsWith<IllegalArgumentException> { parseClusterTasks("""{"data":[${record(jsonNode = "node-b")}]}""", remote) }
        assertFailsWith<IllegalArgumentException> { parseClusterTasks("""{"data":[${record(jsonId = "101")}]}""", remote) }
        assertFailsWith<IllegalArgumentException> { parseClusterTasks("""{"data":[{"upid":"not-a-task"}]}""", remote) }
        assertFailsWith<IllegalArgumentException> { parseClusterTasks("""{"data":{}}""", remote) }
        assertFailsWith<IllegalArgumentException> { parseClusterTasks("not-json", remote) }
        assertFailsWith<IllegalStateException> { parseClusterTasks("""{"errors":"denied"}""", remote) }
        assertEquals(emptyList(), parseClusterTasks("""{"data":[]}""", remote))
        assertFailsWith<IllegalArgumentException> { parseClusterTasks("""{"data":[]}""", "https://example.test:8006/") }
    }

    @Test fun claimedSuccessOrRunningWithoutEvidenceIsRejected() {
        val upid = upid()
        val start = Instant.ofEpochSecond(1700000000)
        assertFailsWith<IllegalArgumentException> {
            ClusterTask(remote, upid, "node-a", "qmstart", "100", "tester@pve", start, null, null, TaskOutcome.Succeeded)
        }
        assertFailsWith<IllegalArgumentException> {
            ClusterTask(remote, upid, "node-a", "qmstart", "100", "tester@pve", start, null, null, TaskOutcome.Running)
        }
    }

    @Test fun listTextOmitsTheUpidAndTokenName() {
        val split = parseClusterTasks("""{"data":[${record(user = "tester@pve", upidUser = "tester@pve!read", extra = """"tokenid":"leaked-token-name"""")}]}""", remote).single()
        val shown = taskListFields(split).joinToString(" ")
        assertEquals("tester@pve", taskUserLabel(split.user))
        assertFalse(shown.contains("UPID:"))
        assertFalse(shown.contains("!read"))
        assertFalse(shown.contains("leaked-token-name"))
        assertFalse(split.toString().contains("leaked-token-name"))
        val embedded = parseClusterTasks("""{"data":[${record(user = null, upidUser = "tester@pve!read")}]}""", remote).single()
        assertEquals("tester@pve!read", embedded.user)
        assertEquals("tester@pve", taskUserLabel(embedded.user))
        assertFalse(taskListFields(embedded).joinToString(" ").contains("!read"))
    }

    private fun upid(node: String = "node-a", type: String = "qmstart", id: String = "100", user: String = "tester@pve", pstart: String = "00000011") =
        "UPID:$node:00000010:$pstart:65ABCDEF:$type:$id:$user:"

    private fun record(node: String = "node-a", jsonNode: String = node, type: String = "qmstart", id: String = "100", jsonId: String? = id,
        user: String? = "tester@pve", upidUser: String = user ?: "tester@pve", start: Long? = 1700000000, end: Long? = 1700000010,
        status: String? = "OK", extra: String = "", pstart: String = "00000011"): String {
        val fields = mutableListOf(""""upid":"${upid(node, type, id, upidUser, pstart)}"""", """"node":"$jsonNode"""", """"type":"$type"""")
        if (jsonId != null) fields.add(""""id":"$jsonId"""")
        if (user != null) fields.add(""""user":"$user"""")
        if (start != null) fields.add(""""starttime":$start""")
        if (end != null) fields.add(""""endtime":$end""")
        if (status != null) fields.add(""""status":"$status"""")
        if (extra.isNotEmpty()) fields.add(extra)
        return "{${fields.joinToString(",")}}"
    }
}
