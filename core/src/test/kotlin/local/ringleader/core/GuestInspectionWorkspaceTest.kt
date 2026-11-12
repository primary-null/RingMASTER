package local.ringleader.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class GuestInspectionWorkspaceTest {
    private val remote = "https://example.test:8006"
    private val vm = Guest(remote,100,"fixture","node-a","VM","running")
    private val inventory = object: InventoryGateway {
        override suspend fun overview(connection: Connection) = Overview(1,listOf(vm,vm.copy(id=101)))
        override suspend fun details(connection: Connection,guest: Guest) = GuestDetails(guest,"running",2,null,null,null,null,null,null,null,null)
    }
    private fun connection() = Connection(remote,"tester@pve!fixture","fixture-secret","")
    @Test fun lateReadAfterSelectionChangeIsDiscarded() = runTest {
        val gate = CompletableDeferred<Unit>()
        val reader = object: GuestInspectionGateway {
            override suspend fun read(connection: Connection,guest: Guest,storages: List<String>,timeframe: String): GuestInspection = withContext(NonCancellable) { gate.await(); demoInspection(guest,timeframe) }
        }
        val w = Workspace(this,gateway=inventory,inspectionGateway=reader)
        w.connect(connection()); runCurrent(); w.select(vm); runCurrent(); w.inspectGuest(); runCurrent()
        w.select(vm.copy(id=101)); runCurrent(); gate.complete(Unit); runCurrent()
        assertNull(w.state.value.inspection); assertEquals(101,w.state.value.selected?.id); w.close()
    }
    @Test fun sectionPermissionDoesNotExpireSessionBut401Does() = runTest {
        var expired = false
        val reader = object: GuestInspectionGateway {
            override suspend fun read(connection: Connection,guest: Guest,storages: List<String>,timeframe: String): GuestInspection {
                if(expired) throw SessionExpiredException()
                return demoInspection(guest,timeframe).copy(addresses=InspectionSection(error="Not permitted for this token"))
            }
        }
        val w = Workspace(this,gateway=inventory,inspectionGateway=reader)
        w.connect(connection()); runCurrent(); w.select(vm); runCurrent(); w.inspectGuest(); runCurrent()
        assertTrue(w.state.value.connected); assertNotNull(w.state.value.inspection?.hardware?.value)
        assertEquals("Not permitted for this token",w.state.value.inspection?.addresses?.error)
        expired=true; w.inspectGuest(); runCurrent(); assertFalse(w.state.value.connected); w.close()
    }
    @Test fun everyNewActionUsesOneUseAndAmbiguityGating() = runTest {
        val actions = listOf(PowerAction.Reboot,PowerAction.Suspend,PowerAction.Reset,PowerAction.SnapshotCreate,PowerAction.SnapshotRollback,PowerAction.SnapshotDelete)
        for(action in actions) {
            var calls=0
            val power = object: PowerGateway {
                override suspend fun submit(connection: Connection,guest: Guest,action: PowerAction): String { calls++; throw AmbiguousPowerResultException() }
                override suspend fun status(connection: Connection,node: String,upid: String) = error("No task")
            }
            val w=Workspace(this,gateway=inventory,powerGateway=power)
            w.connect(connection()); runCurrent(); w.select(vm); runCurrent()
            val request = if(action.name.startsWith("Snapshot")) SnapshotRequest("baseline") else null
            val c=assertNotNull(w.capturePower(action,"Fixture",request))
            assertTrue(w.submitPower(c)); assertFalse(w.submitPower(c)); runCurrent()
            assertEquals(1,calls); assertEquals(PowerPhase.Unknown,w.state.value.submissions.single().phase)
            assertNull(w.capturePower(action,"Fixture",request)); w.close()
        }
    }
    @Test fun movedAndStaleSnapshotConfirmationsAreRefused() {
        val c=PowerConfirmation(1,remote,remote,"Fixture",vm.resource,vm.node,vm.name,vm.kind,PowerAction.SnapshotDelete,vm.status,false,snapshot=SnapshotRequest("baseline"))
        val state=WorkspaceState(connected=true,address=remote,overview=Overview(1,listOf(vm)),selected=vm,connectionEpoch=1)
        assertNull(powerRefusal(c,state))
        assertNotNull(powerRefusal(c,state.copy(stale=true)))
        assertNotNull(powerRefusal(c,state.copy(overview=Overview(1,listOf(vm.copy(node="node-b"))))))
        assertNotNull(powerRefusal(c,state.copy(kind=PDM_KIND)))
    }

    @Test fun refreshKeepsPreviousSectionsUntilReplacementsArrive() = runTest {
        val gate = CompletableDeferred<Unit>()
        var pass = 0
        val reader = object : GuestInspectionGateway {
            override suspend fun read(connection: Connection, guest: Guest, storages: List<String>, timeframe: String) = error("unused")
            override suspend fun readPublishing(connection: Connection, guest: Guest, storages: List<String>, emit: (GuestInspection) -> Unit, backupKnown: Boolean, timeframe: String) {
                pass++
                val base = demoInspection(guest, timeframe)
                if (pass == 1) { emit(base); return }
                emit(base.copy(snapshots = InspectionSection(), backups = InspectionSection(), addresses = InspectionSection(), history = InspectionSection()))
                gate.await()
                emit(base.copy(snapshots = InspectionSection(listOf(GuestSnapshot("after", null, 1, null, false, false)))))
            }
        }
        val w = Workspace(this, gateway = inventory, inspectionGateway = reader)
        w.connect(connection()); runCurrent(); w.select(vm); runCurrent(); w.inspectGuest(); runCurrent()
        assertEquals("baseline", w.state.value.inspection?.snapshots?.value?.first()?.name)
        w.inspectGuest(); runCurrent()
        assertEquals("baseline", w.state.value.inspection?.snapshots?.value?.first()?.name)
        assertTrue(w.state.value.inspectionLoading)
        gate.complete(Unit); runCurrent()
        assertEquals("after", w.state.value.inspection?.snapshots?.value?.single()?.name)
        assertFalse(w.state.value.inspectionLoading)
        w.close()
    }

    @Test fun migrationKeepsInspectionAndReadsAgain() = runTest {
        var node = "node-a"
        var reads = 0
        val moving = object : InventoryGateway {
            override suspend fun overview(connection: Connection) = Overview(1, listOf(vm.copy(node = node), vm.copy(id = 101)))
            override suspend fun details(connection: Connection, guest: Guest) = GuestDetails(guest, "running", 2, null, null, null, null, null, null, null, null)
        }
        val reader = object : GuestInspectionGateway {
            override suspend fun read(connection: Connection, guest: Guest, storages: List<String>, timeframe: String): GuestInspection { reads++; return demoInspection(guest, timeframe) }
        }
        val w = Workspace(this, gateway = moving, inspectionGateway = reader)
        w.connect(connection()); runCurrent(); w.select(vm); runCurrent(); w.inspectGuest(); runCurrent()
        assertEquals(1, reads)
        node = "node-b"
        w.refresh(); runCurrent()
        assertEquals("node-b", w.state.value.selected?.node)
        assertNotNull(w.state.value.inspection?.hardware?.value)
        assertTrue(reads >= 2)
        w.close()
    }

    @Test fun finishedSnapshotTaskRefreshesInspection() = runTest {
        var reads = 0
        val reader = object : GuestInspectionGateway {
            override suspend fun read(connection: Connection, guest: Guest, storages: List<String>, timeframe: String): GuestInspection { reads++; return demoInspection(guest, timeframe) }
        }
        val power = object : PowerGateway {
            override suspend fun submit(connection: Connection, guest: Guest, action: PowerAction) = "UPID:node-a:00000001:00000002:00000003:qmsnapshot:100:tester@pve:"
            override suspend fun status(connection: Connection, node: String, upid: String) = PowerTaskStatus(PowerPhase.Succeeded, "Stopped with exit status OK.")
        }
        val w = Workspace(this, gateway = inventory, inspectionGateway = reader, powerGateway = power)
        w.connect(connection()); runCurrent(); w.select(vm); runCurrent(); w.inspectGuest(); runCurrent()
        val before = reads
        val confirmation = assertNotNull(w.capturePower(PowerAction.SnapshotCreate, "Fixture", SnapshotRequest("baseline")))
        assertTrue(w.submitPower(confirmation)); runCurrent()
        assertEquals(PowerPhase.Accepted, w.state.value.submissions.single().phase)
        assertEquals(before, reads)
        w.checkPower(w.state.value.submissions.single().localId); runCurrent()
        assertEquals(PowerPhase.Succeeded, w.state.value.submissions.single().phase)
        assertTrue(reads > before)
        w.close()
    }
}
