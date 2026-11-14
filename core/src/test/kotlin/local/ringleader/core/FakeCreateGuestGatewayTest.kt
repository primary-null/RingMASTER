package local.ringleader.core

import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The shared fake behaves as its scripts say, so phase A and the UI tests can rely on it. */
class FakeCreateGuestGatewayTest {
    private val connection = Connection("https://192.0.2.10:8006", "ring@pve!test", "test-only-secret", "")
    private fun request(kind: NewGuestKind = NewGuestKind.Vm, vmid: Int = 105) =
        CreateRequest(kind, "node-a", vmid, "web-1", listOf(CreateParam("vmid", CreateValue.Int(vmid.toLong()))))

    @Test fun listsLoadFailAndComeBackEmpty() = runTest {
        val fake = FakeCreateGuestGateway()
        assertEquals(NextIdReply.Free(DEMO_CREATE_NEXT_ID), fake.nextId(connection, null))
        assertEquals(NextIdReply.Free(150), fake.nextId(connection, 150))
        assertEquals(demoCreateBridges, fake.bridges(connection, "node-a"))
        assertEquals(3, fake.volumes(connection, "node-a", "local", "iso").size)
        assertEquals(emptyList(), fake.volumes(connection, "node-a", "other", "iso"))

        fake.pools = FakeRead.denied()
        fake.bridges = FakeRead.empty()
        fake.cpuModels = FakeRead.failure("boom")
        fake.clusterOptions = FakeRead.unreachable()
        fake.volumes["local" to "iso"] = FakeRead.expired()
        fake.idChecks[105] = FakeRead.loads(NextIdReply.Taken(105, "VM 105 already exists"))
        assertFailsWith<GuestPermissionException> { fake.pools(connection) }
        assertEquals(emptyList(), fake.bridges(connection, "node-a"))
        assertEquals("boom", assertFailsWith<IllegalStateException> { fake.cpuModels(connection, "node-a", null) }.message)
        assertFailsWith<IOException> { fake.clusterOptions(connection) }
        assertFailsWith<SessionExpiredException> { fake.volumes(connection, "node-a", "local", "iso") }
        assertEquals(NextIdReply.Taken(105, "VM 105 already exists"), fake.nextId(connection, 105))
        assertTrue("bridges node-a" in fake.calls && "nextId 105" in fake.calls)
        assertTrue(fake.calls.none { it.startsWith("storage") }, "No storage-index read exists (D1).")
    }

    @Test fun aWaitingListStaysInFlightUntilReleased() = runTest {
        val fake = FakeCreateGuestGateway()
        val gate = FakeRead.Waits<List<PoolChoice>>()
        fake.pools = gate
        val read = async { fake.pools(connection) }
        yield()
        assertFalse(read.isCompleted)
        gate.release(FakeRead.loads(listOf(PoolChoice("p1"))))
        assertEquals(listOf(PoolChoice("p1")), read.await())
    }

    @Test fun aCreateSucceedsAfterRunning() = runTest {
        val fake = FakeCreateGuestGateway().apply { create = FakeCreate.Succeeds(runningPolls = 1) }
        val secrets = CreateSecrets(SecretChars.copyOf("test-only-password"), null)
        val reply = assertIs<CreateReply.Accepted>(fake.submit(connection, request(), secrets))
        assertEquals(reply.upid, parseAcceptedUpid("""{"data":"${reply.upid}"}""", "node-a", 105))
        assertEquals(PowerPhase.Accepted, fake.status(connection, "node-a", reply.upid).phase)
        assertEquals(PowerTaskStatus(PowerPhase.Succeeded, "Stopped with exit status OK."), fake.status(connection, "node-a", reply.upid))
        assertEquals(listOf(FakeSubmit(NewGuestKind.Vm, "node-a", 105, request().params, false, SecretState(true, 0))), fake.submits)
        assertTrue(fake.lastSecrets === secrets)
        assertFalse(fake.submits.toString().contains("test-only-password"))
    }

    @Test fun aCreateEndsWithWarnings() = runTest {
        val fake = FakeCreateGuestGateway().apply { create = FakeCreate.EndsWithWarnings(listOf("nesting is off", "systemd is old")) }
        val reply = assertIs<CreateReply.Accepted>(fake.submit(connection, request(NewGuestKind.Container), CreateSecrets.None))
        assertTrue(":vzcreate:105:" in reply.upid)
        assertEquals(PowerTaskStatus(PowerPhase.Unsuccessful, "WARNINGS: 2"), fake.status(connection, "node-a", reply.upid))
        val log = fake.taskLog(connection, "node-a", reply.upid, 0, 500)
        assertEquals(listOf("Creating guest", "WARN: nesting is off", "WARN: systemd is old", "TASK WARNINGS: 2"), log.lines.map { it.text })
        assertEquals(4, log.total)
    }

    @Test fun aCreateFailsItsTaskOrEndsUnknown() = runTest {
        val fake = FakeCreateGuestGateway().apply { create = FakeCreate.TaskFails("unable to create CT 105 - no space", listOf("extracting archive")) }
        val failed = assertIs<CreateReply.Accepted>(fake.submit(connection, request(), CreateSecrets.None))
        assertEquals(PowerTaskStatus(PowerPhase.Unsuccessful, "unable to create CT 105 - no space"), fake.status(connection, "node-a", failed.upid))
        assertEquals("TASK ERROR: unable to create CT 105 - no space", fake.taskLog(connection, "node-a", failed.upid, 0, 10).lines.last().text)
        fake.create = FakeCreate.TaskEndsUnknown
        val unknown = assertIs<CreateReply.Accepted>(fake.submit(connection, request(vmid = 106), CreateSecrets.None))
        assertEquals(PowerPhase.Unknown, fake.status(connection, "node-a", unknown.upid).phase)
    }

    @Test fun aCreateIsRejectedWithFieldErrors() = runTest {
        val fake = FakeCreateGuestGateway().apply { create = FakeCreate.fieldErrors("scsi0" to "storage 'local-lvm' is full", "vmid" to "VM 105 already exists") }
        assertEquals(
            CreateReply.Rejected(400, "Parameter verification failed.", mapOf("scsi0" to "storage 'local-lvm' is full", "vmid" to "VM 105 already exists")),
            fake.submit(connection, request(), CreateSecrets.None),
        )
        fake.create = FakeCreate.forbidden()
        assertEquals(403, assertIs<CreateReply.Rejected>(fake.submit(connection, request(), CreateSecrets.None)).status)
    }

    @Test fun aCreateLosesItsReplyOrIsNotSent() = runTest {
        val fake = FakeCreateGuestGateway().apply { create = FakeCreate.LosesReply }
        assertFailsWith<AmbiguousPowerResultException> { fake.submit(connection, request(), CreateSecrets.None) }
        fake.create = FakeCreate.UnclearReply
        assertFailsWith<PowerResponseUnclearException> { fake.submit(connection, request(), CreateSecrets.None) }
        fake.create = FakeCreate.NotSent
        val notSent = assertFailsWith<IOException> { fake.submit(connection, request(), CreateSecrets.None) }
        assertFalse(notSent is AmbiguousPowerResultException)
        fake.create = FakeCreate.SessionExpires
        assertFailsWith<SessionExpiredException> { fake.submit(connection, request(), CreateSecrets.None) }
        assertEquals(4, fake.submits.size)
    }

    @Test fun aWaitingCreateStaysInFlightUntilReleased() = runTest {
        val gate = FakeCreate.Waits()
        val fake = FakeCreateGuestGateway().apply { create = gate }
        val send = async { fake.submit(connection, request(), CreateSecrets.None) }
        yield()
        assertFalse(send.isCompleted)
        assertEquals(1, fake.submits.size)
        gate.release(FakeCreate.Succeeds())
        assertIs<CreateReply.Accepted>(send.await())
    }

    @Test fun fakeUpidsParseForTheirNodeAndId() {
        val upid = fakeCreateUpid("node-a", 105, NewGuestKind.Vm)
        val parsed = parseServerUpid(upid)!!
        assertEquals("node-a", parsed.node)
        assertEquals("105", parsed.subjectId)
    }
}
