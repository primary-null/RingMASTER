package local.ringleader.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class StorageInterlockTest {
    private val remote="https://example.test:8006"
    private val backup=StorageVolume("shared-data:backup/a","backup",null,1,null,null,null,120,null,false,null)
    private class Storage : StorageActionGateway {
        val gate=CompletableDeferred<String?>(); var sends=0
        override suspend fun catalogue(connection: Connection,node: String)=emptyList<AplTemplate>()
        override suspend fun metadata(connection: Connection,node: String,url: String,verify: Boolean)=UrlMetadata(null,null,null)
        override suspend fun attributes(connection: Connection,key: StorageKey,volid: String)=StorageAttributes(null,true)
        override suspend fun submit(connection: Connection,key: StorageKey,request: StorageRequest,progress:(Long)->Unit,cancel:UploadCancel): String? { sends++; return gate.await() }
        override suspend fun status(connection: Connection,node: String,upid: String)=PowerTaskStatus(PowerPhase.Succeeded,"Stopped with exit status OK.")
    }
    private class Power : PowerGateway {
        val gate=CompletableDeferred<String>(); var sends=0
        override suspend fun submit(connection:Connection,guest:Guest,action:PowerAction):String { sends++; return gate.await() }
        override suspend fun status(connection:Connection,node:String,upid:String)=PowerTaskStatus(PowerPhase.Accepted,"Running.")
    }
    private fun TestScope.ready(storage:Storage,power:Power,create:CreateGuestGateway):Workspace {
        val w=Workspace(this,gateway=object:InventoryGateway {
            override suspend fun overview(connection:Connection)=demoOverview.copy(guests=demoOverview.guests.map { it.copy(remote=remote,node="node-a") },members=demoOverview.members.map { it.copy(remote=remote,name=if(it.name.endsWith('a')) "node-a" else "node-b") },storage=demoOverview.storage.map { it.copy(remote=remote,node=if(it.node.endsWith('a')) "node-a" else "node-b") })
            override suspend fun details(connection:Connection,guest:Guest)=demoDetails(guest)
        },powerGateway=power,createGateway=create,storageActionGateway=storage,
            storageContentGateway=object:StorageContentGateway { override suspend fun read(connection:Connection,node:String,storage:String,content:String?)=listOf(backup) },
            taskGateway=object:TaskGateway { override suspend fun tasks(connection:Connection)=emptyList<ClusterTask>() })
        w.lostResults=LostPowerResultStore(); w.connect(Connection(remote,"fixture@pve!api","test", "")); runCurrent(); w.setLive(false)
        w.select(w.state.value.overview!!.guests.first()); runCurrent()
        w.selectStorage("node-a","shared-data"); runCurrent(); return w
    }
    private fun request()=CreateRequest(NewGuestKind.Container,"node-a",140,"fixture",listOf(CreateParam("vmid",CreateValue.Int(140))))
    @Test fun storageAndPowerMutuallyExcludeSends()=runTest {
        val s=Storage(); val p=Power(); val w=ready(s,p,FakeCreateGuestGateway())
        val power=assertNotNull(w.capturePower(PowerAction.Shutdown,"fixture"))
        val storage=assertNotNull(w.captureStorage("node-a","shared-data",StorageRequest.SetProtection(backup,true),"fixture"))
        assertTrue(w.submitStorage(storage)); runCurrent(); assertFalse(w.submitPower(power)); assertEquals(0,p.sends)
        s.gate.complete(null); runCurrent(); assertFalse(w.state.value.powerBusy)
        assertTrue(w.submitPower(power)); runCurrent()
        assertFalse(w.submitStorage(assertNotNull(w.captureStorage("node-a","shared-data",StorageRequest.SetProtection(backup,false),"fixture"))))
        assertEquals(1,s.sends); w.close()
    }
    @Test fun storageAndCreateMutuallyExcludeSends()=runTest {
        val s=Storage(); val p=Power(); val fake=FakeCreateGuestGateway(); val waits=FakeCreate.Waits(); fake.create=waits
        val w=ready(s,p,fake); assertTrue(w.openCreateSession(NewGuestKind.Container,"node-a")); runCurrent()
        val secret=CreateSecrets(SecretChars.copyOf("fixture-password")); val create=assertNotNull(w.captureCreate(request(),"fixture",secret.state))
        val storage=assertNotNull(w.captureStorage("node-a","shared-data",StorageRequest.SetProtection(backup,true),"fixture"))
        assertTrue(w.submitStorage(storage)); runCurrent(); assertFalse(w.submitCreate(create,secret)); assertTrue(fake.submits.isEmpty())
        s.gate.complete(null); runCurrent()
        val s2=CreateSecrets(SecretChars.copyOf("fixture-password")); val c2=assertNotNull(w.captureCreate(request(),"fixture",s2.state))
        assertTrue(w.submitCreate(c2,s2)); runCurrent()
        assertFalse(w.submitStorage(assertNotNull(w.captureStorage("node-a","shared-data",StorageRequest.SetProtection(backup,false),"fixture"))))
        assertEquals(1,s.sends); w.close()
    }
    @Test fun lateCreateFailureCannotClearTheNewStorageSendLock()=runTest {
        val s=Storage(); val p=Power(); val fake=FakeCreateGuestGateway(); val late=CompletableDeferred<PowerTaskStatus>()
        val create=object:CreateGuestGateway by fake { override suspend fun status(connection:Connection,node:String,upid:String)=late.await() }
        val w=ready(s,p,create); assertTrue(w.openCreateSession(NewGuestKind.Container,"node-a")); runCurrent()
        val secret=CreateSecrets(SecretChars.copyOf("fixture-password")); assertTrue(w.submitCreate(assertNotNull(w.captureCreate(request(),"fixture",secret.state)),secret)); runCurrent()
        advanceTimeBy(1001); runCurrent()
        assertTrue(w.submitStorage(assertNotNull(w.captureStorage("node-a","shared-data",StorageRequest.SetProtection(backup,true),"fixture")))); runCurrent(); assertTrue(w.state.value.powerBusy)
        // Invalid success evidence throws in the pre-existing PowerSubmission guard, entering create's outer failure path.
        late.complete(PowerTaskStatus(PowerPhase.Succeeded,"not terminal OK evidence")); runCurrent()
        assertTrue(w.state.value.powerBusy)
        s.gate.complete(null); runCurrent(); assertFalse(w.state.value.powerBusy); w.close()
    }

    @Test fun demoChangesSurviveStorageSwitchesAndKeepContentFilters()=runTest {
        val storage=Storage(); val w=ready(storage,Power(),FakeCreateGuestGateway()); w.exploreDemo()
        w.selectStorage("demo-node-a","shared-data","backup")
        val target=w.state.value.storageContent.value!!.volumes.first()
        assertTrue(w.submitStorage(assertNotNull(w.captureStorage("demo-node-a","shared-data",StorageRequest.SetProtection(target,true),"demo")))); runCurrent()
        w.selectStorage("demo-node-a","local","iso")
        w.selectStorage("demo-node-a","shared-data","images")
        assertTrue(w.state.value.storageContent.value!!.volumes.all { it.content == "images" })
        w.selectStorage("demo-node-a","shared-data","backup")
        assertTrue(w.state.value.storageContent.value!!.volumes.single { it.volid == target.volid }.protected)
        assertEquals(0,storage.sends); w.close()
    }

}
