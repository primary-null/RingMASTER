package local.ringleader.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class StorageReadIdentityTest {
    private val key=StorageKey("https://example.test:8006","node-a","local")
    private val a=StorageVolume("local:backup/a","backup",null,1,null,null,null,120,"first",false,null)
    private val b=a.copy(volid="local:backup/b")
    private fun state()=WorkspaceState(connected=true,address=key.remote,connectionEpoch=1,storageSelection=key,
        overview=Overview(1,emptyList(),members=listOf(RecordedMember(key.remote,key.node,"online",null,null,null,null,null,null,null,null)),storage=listOf(RecordedStorage(key.remote,key.node,key.storage,false,1,10,"available","dir","iso,vztmpl,backup"))),
        storageContent=ReadSection(value=StorageContentView(key,null,listOf(a,b))))
    private class Gateway: StorageActionGateway {
        var meta: suspend ()->UrlMetadata={UrlMetadata("a.iso",1,null)}
        var cat: suspend ()->List<AplTemplate> = {emptyList()}
        var attr: suspend (String)->StorageAttributes={StorageAttributes(it,false)}
        var calls=0
        override suspend fun metadata(connection:Connection,node:String,url:String,verify:Boolean):UrlMetadata {calls++;return meta()}
        override suspend fun catalogue(connection:Connection,node:String)=cat()
        override suspend fun attributes(connection:Connection,key:StorageKey,volid:String)=attr(volid)
        override suspend fun submit(connection:Connection,key:StorageKey,request:StorageRequest,progress:(Long)->Unit,cancel:UploadCancel):String?=error("No mutations")
        override suspend fun status(connection:Connection,node:String,upid:String):PowerTaskStatus=error("No tasks")
    }
    @Test fun invalidReplacementFencesDeferredAndCompletedMetadata()=runTest {
        val s=MutableStateFlow(state());val g=Gateway();val conn=Connection(key.remote,"test@pve!api","fixture","")
        val w=StorageActionWorkbench(this,s,g,{conn},{true},{},{},{})
        val deferred=CompletableDeferred<UrlMetadata>();g.meta={withContext(NonCancellable){deferred.await()}}
        w.read(key,url="https://example.test/a?secret=yes");runCurrent()
        w.read(key,url="invalid");assertNull(s.value.storageActions.metadata)
        deferred.complete(UrlMetadata("old.iso",1,null));runCurrent()
        assertNull(s.value.storageActions.metadata);assertNotNull(s.value.storageActions.readStates["metadata"]!!.error)
        g.meta={UrlMetadata("valid.iso",2,null)};w.read(key,url="https://example.test/a");runCurrent()
        assertNotNull(s.value.storageActions.metadata)
        w.read(key,url="invalid");assertNull(s.value.storageActions.metadata)
        assertEquals(2,g.calls);w.cancel();conn.close()
    }
    @Test fun identityIncludesCertificateChoiceAndIsRedacted()=runTest {
        val s=MutableStateFlow(state());val g=Gateway();val conn=Connection(key.remote,"test@pve!api","fixture","")
        val w=StorageActionWorkbench(this,s,g,{conn},{true},{},{},{})
        val url="https://example.test/a?secret=yes"
        w.read(key,url=url,verify=true);runCurrent()
        val first=s.value.storageActions.readStates["metadata"]!!
        assertTrue(first.complete);assertTrue(first.identity.matchesMetadata(key,url,true));assertFalse(first.identity.matchesMetadata(key,url,false))
        assertFalse(first.toString().contains("secret"));assertFalse(first.toString().contains("example"))
        w.read(key,url=url,verify=false);assertNull(s.value.storageActions.metadata);runCurrent()
        assertTrue(s.value.storageActions.readStates["metadata"]!!.identity.matchesMetadata(key,url,false))
        w.cancel();conn.close()
    }
    @Test fun emptyCatalogueIsCompleteButFailedRefreshIsNot()=runTest {
        val s=MutableStateFlow(state());val g=Gateway();val conn=Connection(key.remote,"test@pve!api","fixture","")
        val w=StorageActionWorkbench(this,s,g,{conn},{true},{},{},{})
        w.read(key);runCurrent();assertEquals(emptyList(),s.value.storageActions.catalogues[key.node]);assertTrue(s.value.storageActions.readStates["catalogue:node-a"]!!.complete)
        g.cat={error("sensitive reflected error")};w.read(key);assertNull(s.value.storageActions.catalogues[key.node]);runCurrent()
        val result=s.value.storageActions.readStates["catalogue:node-a"]!!
        assertFalse(result.complete);assertNotNull(result.error);assertFalse(result.toString().contains("sensitive"));w.cancel();conn.close()
    }
    @Test fun attributeSwitchCannotPublishOldFullNotes()=runTest {
        val s=MutableStateFlow(state());val g=Gateway();val conn=Connection(key.remote,"test@pve!api","fixture","")
        val w=StorageActionWorkbench(this,s,g,{conn},{true},{},{},{})
        val deferred=CompletableDeferred<StorageAttributes>()
        g.attr={if(it==a.volid) withContext(NonCancellable){deferred.await()} else StorageAttributes("new full notes",false)}
        w.read(key,volid=a.volid);runCurrent();w.read(key,volid=b.volid);assertNull(s.value.storageActions.attributesTarget);runCurrent()
        deferred.complete(StorageAttributes("old notes",false));runCurrent()
        assertEquals(key to b.volid,s.value.storageActions.attributesTarget);assertEquals("new full notes",s.value.storageActions.attributes!!.notes)
        assertEquals(b.volid,s.value.storageActions.readStates["attributes"]!!.identity.volume);w.cancel();conn.close()
    }
}
