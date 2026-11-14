package local.ringleader.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class GuestCreateWorkspaceTest {
    private val remote = "https://example.test:8006"
    private open class Inventory : InventoryGateway {
        var created = false
        override suspend fun overview(connection: Connection) = demoOverview.copy(guests = if (created) listOf(Guest(connection.remoteKey, 140, "fixture", "node-a", "Container", "stopped")) else emptyList(), members = demoOverview.members.map { it.copy(remote = connection.remoteKey, name = if (it.name.endsWith('a')) "node-a" else "node-b") }, storage = demoOverview.storage.map { it.copy(remote = connection.remoteKey, node = if (it.node.endsWith('a')) "node-a" else "node-b") })
        override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
    }
    private fun TestScope.ready(fake: FakeCreateGuestGateway, inv: Inventory = Inventory(), power: PowerGateway = LivePowerGateway(), logs: TaskLogGateway = object : TaskLogGateway {
        override suspend fun status(connection: Connection, node: String, upid: String): TaskLogStatus = error("Unexpected task log read")
        override suspend fun page(connection: Connection, node: String, upid: String, start: Int, limit: Int): TaskLogPage = error("Unexpected task log read")
    }): Workspace {
        val w = Workspace(this, gateway = inv, powerGateway = power, createGateway = fake, taskLogGateway = logs, taskGateway = object : TaskGateway { override suspend fun tasks(connection: Connection) = emptyList<ClusterTask>() }, permissionsGateway = object : PermissionsGateway { override suspend fun read(connection: Connection) = PermissionDump(mapOf("/" to mapOf("VM.Allocate" to true))) })
        w.lostResults = LostPowerResultStore()
        w.connect(Connection(remote, "fixture@pve!test", "test-only", "")); runCurrent(); w.setLive(false)
        assertTrue(w.openCreateSession(NewGuestKind.Container, "node-a")); runCurrent()
        return w
    }
    private fun request() = CreateRequest(NewGuestKind.Container, "node-a", 140, "fixture", listOf(CreateParam("vmid", CreateValue.Int(140))))
    private fun secret() = CreateSecrets(SecretChars.copyOf("fixture-password"))
    @Test fun oneConfirmationOneSendAndTerminalOkCatchup() = runTest {
        val fake = FakeCreateGuestGateway(); val inv = Inventory(); val w = ready(fake, inv)
        val s = secret(); val c = assertNotNull(w.captureCreate(request(), "Fixture", s.state))
        assertTrue(w.submitCreate(c, s)); runCurrent(); assertTrue(s.password!!.closed)
        assertTrue(w.state.value.creation!!.result is CreateResult.Following)
        assertFalse(w.submitCreate(c, secret())); assertEquals(1, fake.submits.size)
        inv.created = true; advanceTimeBy(4001); runCurrent()
        val r = assertIs<CreateResult.Succeeded>(w.state.value.creation!!.result); assertEquals(GuestAppearance.Appeared, r.appearance)
        assertNull(unresolvedSubmission(w.state.value)); w.close()
    }
    @Test fun warningsAreAmberUnsuccessfulAndUnblock() = runTest {
        val fake = FakeCreateGuestGateway().apply { create = FakeCreate.EndsWithWarnings(listOf("fixture warning")) }; val w = ready(fake)
        val s = secret(); assertTrue(w.submitCreate(assertNotNull(w.captureCreate(request(), "Fixture", s.state)), s)); runCurrent()
        advanceTimeBy(1001); runCurrent()
        val r = assertIs<CreateResult.FinishedWithWarnings>(w.state.value.creation!!.result); assertEquals(1, r.count); assertTrue(r.warnings.single().contains("fixture warning"))
        assertTrue(createFinishedWithWarnings(w.state.value.submissions.single())); assertNull(unresolvedSubmission(w.state.value)); w.close()
    }
    @Test fun lostReplyBlocksUntilExplicitFreeIdCheckAndNeverResends() = runTest {
        val fake = FakeCreateGuestGateway().apply { create = FakeCreate.LosesReply }; val w = ready(fake)
        val s = secret(); val c = assertNotNull(w.captureCreate(request(), "Fixture", s.state)); w.submitCreate(c, s); runCurrent()
        assertIs<CreateResult.Unknown>(w.state.value.creation!!.result); assertNotNull(unresolvedSubmission(w.state.value)); assertTrue(s.password!!.closed)
        w.checkCreateOutcome(w.state.value.submissions.single().localId); runCurrent()
        assertNull(unresolvedSubmission(w.state.value)); assertTrue(w.state.value.submissions.single().resolvedByCheck); assertEquals(1, fake.submits.size)
        assertTrue(fake.calls.contains("nextId 140")); w.close()
    }
    @Test fun rejectedAndFailedRemainDistinctAndClearSecrets() = runTest {
        for (script in listOf(FakeCreate.NotSent, FakeCreate.Rejected(), FakeCreate.TaskFails())) {
            val fake = FakeCreateGuestGateway().apply { create = script }; val w = ready(fake); val s = secret()
            w.submitCreate(assertNotNull(w.captureCreate(request(), "Fixture", s.state)), s); runCurrent(); advanceTimeBy(1001); runCurrent()
            assertTrue(s.password!!.closed)
            assertTrue(w.state.value.creation!!.result is CreateResult.Rejected || w.state.value.creation!!.result is CreateResult.Failed)
            assertNull(unresolvedSubmission(w.state.value)); w.close()
        }
    }
    @Test fun lateReadsCannotCrossNodeChangeOrClose() = runTest {
        val waiting = FakeRead.Waits<List<PoolChoice>>()
        val fake = FakeCreateGuestGateway().apply { pools = waiting }; val w = ready(fake)
        w.changeCreateNode("node-b"); runCurrent(); w.closeCreateSession()
        waiting.release(FakeRead.loads(listOf(PoolChoice("late")))); runCurrent()
        assertNull(w.state.value.creation); w.disconnect(); w.close()
    }
    @Test fun demoUsesNoGateway() = runTest {
        val fake = FakeCreateGuestGateway(); val w = Workspace(this, createGateway = fake)
        w.exploreDemo(); assertTrue(w.openCreateSession(NewGuestKind.Container, "demo-node-a"))
        val s = secret(); val c = assertNotNull(w.captureCreate(request().copy(node = "demo-node-a"), "Demo", s.state)); assertTrue(w.submitCreate(c, s))
        assertIs<CreateResult.Simulated>(w.state.value.creation!!.result); assertTrue(fake.calls.isEmpty()); assertTrue(s.password!!.closed); w.close()
    }
    @Test fun disconnectDuringSendWipesAndRecordsLostResult() = runTest {
        val waiting = FakeCreate.Waits(); val fake = FakeCreateGuestGateway().apply { create = waiting }; val w = ready(fake)
        val store = w.lostResults; val secrets = secret()
        w.submitCreate(assertNotNull(w.captureCreate(request(), "Fixture", secrets.state)), secrets); runCurrent()
        w.disconnect(); runCurrent()
        assertTrue(secrets.password!!.closed); assertNull(w.state.value.creation)
        assertEquals(1, store.forRemote(remote).size)
        waiting.release(FakeCreate.Succeeds()); runCurrent(); assertTrue(w.state.value.submissions.isEmpty()); w.close()
    }
    @Test fun closingWizardInvalidatesCapturedConfirmation() = runTest {
        val fake = FakeCreateGuestGateway(); val w = ready(fake); val secrets = secret()
        val c = assertNotNull(w.captureCreate(request(), "Fixture", secrets.state))
        w.closeCreateSession(); assertTrue(w.openCreateSession(NewGuestKind.Container, "node-a")); runCurrent()
        assertFalse(w.submitCreate(c, secrets)); assertTrue(secrets.password!!.closed); assertTrue(fake.submits.isEmpty()); w.close()
    }

    @Test fun createSendSharesThePowerMutationLock() = runTest {
        val waiting = FakeCreate.Waits(); val fake = FakeCreateGuestGateway().apply { create = waiting }; val w = ready(fake)
        val secrets = secret(); w.submitCreate(assertNotNull(w.captureCreate(request(), "Fixture", secrets.state)), secrets); runCurrent()
        // Even an earlier valid power confirmation cannot race an in-flight create POST.
        val power = PowerConfirmation(w.state.value.connectionEpoch, remote, remote, "Fixture", ResourceId(remote, "", "VM", 100), "node-a", "existing", "VM", PowerAction.Start, "stopped", false)
        assertFalse(w.submitPower(power)); assertTrue(w.state.value.powerNotice!!.contains("already being sent"))
        w.disconnect(); waiting.release(FakeCreate.Succeeds()); runCurrent(); w.close()
    }

    @Test fun exactCreateLogResolvesOnlyCurrentAcceptedSubmission() = runTest {
        val calls = mutableListOf<Pair<String,String>>()
        val logs = object : TaskLogGateway {
            override suspend fun status(connection: Connection, node: String, upid: String): TaskLogStatus {
                calls += node to upid
                val p = parseServerUpid(upid)!!
                return TaskLogStatus("stopped","OK",null,null,null,p.type,p.subjectId,p.user,node)
            }
            override suspend fun page(connection: Connection, node: String, upid: String, start: Int, limit: Int): TaskLogPage {
                calls += node to upid
                return TaskLogPage(listOf(TaskLogLine(1,"TASK OK")),1)
            }
        }
        val fake = FakeCreateGuestGateway(); val w = ready(fake, logs=logs)
        assertFalse(w.openCreateTaskLog("local-missing"))
        val s = secret()
        w.submitCreate(assertNotNull(w.captureCreate(request(), "Fixture", s.state)), s); runCurrent()
        val submission = w.state.value.submissions.single()
        assertTrue(w.openCreateTaskLog(submission.localId))
        val task = assertNotNull(w.state.value.taskLog).task
        assertEquals(remote, task.remote); assertEquals("node-a", task.node)
        assertEquals("140", task.subjectId)
        assertTrue(task.upid.startsWith("UPID:node-a:"))
        runCurrent()
        assertEquals(listOf("node-a" to task.upid,"node-a" to task.upid),calls)
        assertEquals("TASK OK",w.state.value.taskLog!!.lines.single().text)
        w.disconnect(); runCurrent()
        assertFalse(w.openCreateTaskLog(submission.localId)); w.close()
    }
    @Test fun rejectedAndSimulatedCreatesHaveNoLog() = runTest {
        val fake = FakeCreateGuestGateway().apply { create = FakeCreate.Rejected() }; val w = ready(fake)
        val s = secret(); w.submitCreate(assertNotNull(w.captureCreate(request(), "Fixture", s.state)), s); runCurrent()
        assertFalse(w.openCreateTaskLog(w.state.value.submissions.single().localId)); w.close()
    }
    @Test fun duplicateCheckIsSingleFlightAndCannotDecorateReopenedWizard() = runTest {
        val fake = FakeCreateGuestGateway().apply { create = FakeCreate.LosesReply }; val w = ready(fake)
        val secrets = secret(); w.submitCreate(assertNotNull(w.captureCreate(request(), "Fixture", secrets.state)), secrets); runCurrent()
        val id = w.state.value.submissions.single().localId
        val gate = FakeRead.Waits<NextIdReply>(); fake.idChecks[140] = gate
        w.checkCreateOutcome(id); w.checkCreateOutcome(id); runCurrent()
        assertTrue(assertIs<CreateResult.Unknown>(w.state.value.creation!!.result).checking)
        assertEquals(1, fake.calls.count { it == "nextId 140" })
        w.closeCreateSession()
        gate.release(FakeRead.loads(NextIdReply.Free(140))); runCurrent()
        assertTrue(w.openCreateSession(NewGuestKind.Container, "node-a")); runCurrent()
        val next = secret(); w.submitCreate(assertNotNull(w.captureCreate(request().copy(vmid = 141, params = listOf(CreateParam("vmid", CreateValue.Int(141)))), "Fixture", next.state)), next); runCurrent()
        val result = assertIs<CreateResult.Unknown>(w.state.value.creation!!.result)
        assertEquals(141, result.vmid); assertEquals(IdCheck.Idle, result.check); assertFalse(result.checking)
        assertEquals(PowerPhase.Unknown, w.state.value.submissions.last().phase); w.close()
    }
    @Test fun checkingCancellationClearsBusyAnd401Expires() = runTest {
        for (error in listOf(kotlinx.coroutines.CancellationException(), SessionExpiredException())) {
            val fake = FakeCreateGuestGateway().apply { create = FakeCreate.LosesReply }; val w = ready(fake)
            val secrets = secret(); w.submitCreate(assertNotNull(w.captureCreate(request(), "Fixture", secrets.state)), secrets); runCurrent()
            fake.idChecks[140] = FakeRead.Fails(error)
            w.checkCreateOutcome(w.state.value.submissions.single().localId); runCurrent()
            if (error is SessionExpiredException) assertFalse(w.state.value.connected)
            else assertFalse(assertIs<CreateResult.Unknown>(w.state.value.creation!!.result).checking)
            w.close()
        }
    }
    @Test fun defaultMediaReadIsOncePerNodeGenerationEvenWithUiEffect() = runTest {
        val fake = FakeCreateGuestGateway(); val inv = object : Inventory() { override suspend fun overview(connection: Connection): Overview {
            val base = super.overview(connection)
            val local = base.storage.first { it.id == "local" }
            return base.copy(storage = base.storage.filter { it.id != "local" } + local.copy(node = "node-a") + local.copy(node = "node-b"))
        } }; val w = ready(fake, inv)
        w.readForCreate(CreateRead.Volumes("local", "vztmpl")); runCurrent()
        assertEquals(1, fake.calls.count { it == "volumes node-a local vztmpl" })
        w.changeCreateNode("node-b"); w.readForCreate(CreateRead.Volumes("local", "vztmpl")); runCurrent()
        assertEquals(1, fake.calls.count { it == "volumes node-b local vztmpl" })
        w.closeCreateSession(); w.openCreateSession(NewGuestKind.Vm, "node-a"); runCurrent()
        w.readForCreate(CreateRead.Volumes("local", "iso")); runCurrent()
        assertEquals(1, fake.calls.count { it == "volumes node-a local iso" }); w.close()
    }
    @Test fun catchupCannotOverwriteNewerManualRefresh() = runTest {
        val old = kotlinx.coroutines.CompletableDeferred<Overview>(); var reads = 0
        val inv = object : Inventory() { override suspend fun overview(connection: Connection): Overview {
            val base = super.overview(connection)
            return when (++reads) { 2 -> old.await(); 3 -> base.copy(guests = listOf(Guest(connection.remoteKey, 999, "newer", "node-a", "VM", "stopped"))); else -> base }
        } }
        val fake = FakeCreateGuestGateway(); val w = ready(fake, inv)
        val secrets = secret(); w.submitCreate(assertNotNull(w.captureCreate(request(), "Fixture", secrets.state)), secrets); runCurrent()
        advanceTimeBy(4001); runCurrent(); assertEquals(2, reads)
        w.refresh(); runCurrent(); assertEquals(999, w.state.value.overview!!.guests.single().id)
        old.complete(inv.overview(Connection(remote, "fixture@pve!test", "test-only", ""))); runCurrent()
        assertEquals(999, w.state.value.overview!!.guests.single().id); w.close()
    }
    @Test fun catchupRetainsMalformedIndexAndExpires401AndRethrowsCancellation() = runTest {
        for (mode in 0..2) {
            var reads = 0
            val inv = object : Inventory() { override suspend fun overview(connection: Connection): Overview {
                val base = super.overview(connection)
                if (++reads == 1) return base
                return when(mode) { 0 -> base.copy(members = emptyList(), storage = emptyList(), indexStale = true, indexError = "fixture"); 1 -> throw SessionExpiredException(); else -> throw kotlinx.coroutines.CancellationException() }
            } }
            val w = ready(FakeCreateGuestGateway(), inv); val secrets = secret()
            w.submitCreate(assertNotNull(w.captureCreate(request(), "Fixture", secrets.state)), secrets); runCurrent(); advanceTimeBy(4001); runCurrent()
            when(mode) { 0 -> { assertTrue(w.state.value.overview!!.members.isNotEmpty()); assertTrue(w.state.value.overview!!.storage.isNotEmpty()) }; 1 -> assertFalse(w.state.value.connected); else -> { advanceTimeBy(20000); runCurrent(); assertEquals(2, reads) } }
            w.close()
        }
    }

    @Test fun powerSendBlocksAnEarlierCreateConfirmation() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<String>()
        val power = object : PowerGateway {
            override suspend fun submit(connection: Connection, guest: Guest, action: PowerAction) = gate.await()
            override suspend fun status(connection: Connection, node: String, upid: String) = PowerTaskStatus(PowerPhase.Succeeded, "OK")
        }
        val fake = FakeCreateGuestGateway(); val w = ready(fake, Inventory().apply { created = true }, power)
        val secrets = secret()
        val request = request().copy(vmid = 141, params = listOf(CreateParam("vmid", CreateValue.Int(141))))
        val create = assertNotNull(w.captureCreate(request, "Fixture", secrets.state))
        w.select(w.state.value.overview!!.guests.single()); runCurrent()
        val confirmation = assertNotNull(w.capturePower(PowerAction.Start, "Fixture"))
        assertTrue(w.submitPower(confirmation)); runCurrent()
        assertFalse(w.submitCreate(create, secrets)); assertTrue(fake.submits.isEmpty()); assertTrue(secrets.password!!.closed)
        w.disconnect(); gate.complete("UPID:node-a:00000001:00000001:00000001:vzstart:140:fixture@pve:"); runCurrent(); w.close()
    }

}
