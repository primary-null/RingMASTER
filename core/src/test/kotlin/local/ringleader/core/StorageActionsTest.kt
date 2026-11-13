package local.ringleader.core

import java.io.*
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*

class StorageActionsTest {
    private val key = StorageKey("https://example.test:8006", "node-a", "local")
    private fun volume(type: String = "backup") = StorageVolume("local:$type/test", type, null, 10, null, null, null, 120, null, false, null)
    @Test fun strictValidationAndSecretSafeDiagnostics() {
        listOf("file:///tmp/a", "https://u:p@example.test/x", "https://example.test/a\n", "https://example.test/a#frag").forEach { assertNotNull(downloadUrlError(it)) }
        assertNull(downloadUrlError("https://example.test/a?token=secret"))
        assertTrue(storageCapabilities(RecordedStorage(key.remote,key.node,key.storage,false,1,2,"available","esxi","import")).isEmpty())
        assertFalse(StorageAction.Upload in storageCapabilities(RecordedStorage(key.remote,key.node,key.storage,true,1,2,"available","pbs","backup")))
        listOf("../a.iso", "a/b.iso", "a\r\nX:y.iso", "a\".iso", "x%0a.iso").forEach { assertNotNull(contentFileNameError("iso", it)) }
        assertNull(contentFileNameError("vztmpl", "test_1_amd64.tar.zst"))
        assertNotNull(checksumError(Checksum("sha256", "a")))
        assertNull(checksumError(Checksum("sha256", "a".repeat(64))))
        listOf("images", "rootdir", "unknown").forEach { assertNotNull(storageRequestError(StorageRequest.RemoveVolume(volume(it)))) }
        assertNotNull(storageRequestError(StorageRequest.RemoveVolume(volume().copy(protected = true))))
        assertEquals("UrlDownload(redacted)", StorageRequest.UrlDownload("https://example.test/?secret=yes", "iso", "a.iso").toString())
        assertFalse(StorageRequest.EditNotes(volume(), "secret-note").toString().contains("secret-note"))
        assertFalse(storageSafeLogText("wget https://example.test/a?secret=token /server/private/path").contains("token"))
        assertEquals("TASK OK",storageSafeLogText("TASK OK"))
        assertEquals("TASK WARNINGS: 2",storageSafeLogText("TASK WARNINGS: 2"))
    }
    @Test fun pathsBodiesAndUpidMatchExactSubjects() {
        val r = StorageRequest.RemoveVolume(volume("iso").copy(volid = "local:iso/a.iso", vmid = null))
        assertTrue(storageActionPath(key, r).endsWith("/local%3Aiso%2Fa.iso"))
        assertEquals(0, storageRequestBody(key, r).size)
        val u = "UPID:node-a:00000010:00000011:65ABCDEF:imgdel:local:test@pve:"
        assertEquals(u, parseStorageUpid("{\"data\":\"$u\"}", key, r))
        assertFailsWith<StorageUnclear> { parseStorageUpid("{\"data\":\"${u.replace("imgdel", "download")}\"}", key, r) }
        assertFailsWith<StorageUnclear> { parseStorageUpid("{\"data\":null,\"error\":\"sensitive\"}", key, r) }
        val attrs = parseStorageAttributes("{\"data\":{\"notes\":\"line1\\nline2\",\"protected\":1,\"path\":\"/secret\"}}")
        assertEquals("line1\nline2", attrs.notes); assertEquals(true, attrs.protected); assertFalse(attrs.toString().contains("secret"))
        assertNull(parseUrlMetadata("{\"data\":{\"filename\":\"../../secret\",\"size\":-1,\"mimetype\":\"secret text\"}}").fileName)
    }
    @Test fun ownedBackupDeleteDoesNotUseAllocateOverrideAndNetworkSupportsAlternatives() {
        val allocate = parsePermissions("{\"data\":{\"/storage/local\":{\"Datastore.Allocate\":1}}}")
        assertNotNull(storageActionGate(StorageAction.RemoveVolume, allocate, key, volume()))
        assertNull(storageActionGate(StorageAction.EditNotes, allocate, key, volume()))
        val network = parsePermissions("{\"data\":{\"/\":{\"Sys.Audit\":1,\"Sys.Modify\":1,\"Datastore.AllocateTemplate\":1}}}")
        assertNull(storageActionGate(StorageAction.UrlDownload, network, key))
    }
    @Test fun multipartIsFixedAndStreamingIsBoundedAndCancellable() {
        val f = uploadMultipart("a".repeat(32), "iso", Checksum("sha256", "b".repeat(64)), "a.iso")
        val h = f.head.decodeToString()
        assertTrue(h.indexOf("name=\"content\"") < h.indexOf("checksum-algorithm")); assertTrue(h.indexOf("checksum-algorithm") < h.indexOf("name=\"filename\""))
        assertEquals(f.head.size + 123L + f.tail.size, f.totalLength(123))
        val progress = mutableListOf<Long>(); var max = 0; var bytes = 0L
        val input = object: InputStream() { override fun read() = -1; override fun read(b: ByteArray, o: Int, n: Int): Int { max = maxOf(max,n); val count = minOf(n.toLong(), 5L*1024*1024-bytes).toInt(); if(count == 0) return -1; bytes += count; return count } }
        streamUpload(input, OutputStream.nullOutputStream(), 5L*1024*1024, { progress += it }, UploadCancel())
        assertEquals(65536, max); assertEquals(5L*1024*1024, progress.last()); assertTrue(progress.zipWithNext().all { it.first <= it.second })
        val cancel = UploadCancel(); cancel.cancel()
        assertFailsWith<java.util.concurrent.CancellationException> { streamUpload(ByteArrayInputStream(byteArrayOf(1)), OutputStream.nullOutputStream(), 1, {}, cancel) }
        val mid = UploadCancel()
        assertFailsWith<java.util.concurrent.CancellationException> { streamUpload(ByteArrayInputStream(ByteArray(131072)), OutputStream.nullOutputStream(), 131072, { if(it > 0) mid.cancel() }, mid) }
    }
    @Test fun catalogueAndDownloadEncodersKeepOnlyAllowedFields() {
        val catalogue=parseAplCatalogue("""{"data":[{"template":"test_1.tar.zst","package":"test","version":"1","type":"lxc","architecture":"amd64","location":"https://secret.example/?token=private","description":"Public description"},{"template":"../../bad.tar.zst","type":"lxc"},{"template":"news.tar.zst","type":"news"}]}""")
        assertEquals(1,catalogue.size); assertFalse(catalogue.toString().contains("private"))
        assertEquals("Public description",catalogue.single().description)
        assertEquals("""{"storage":"local","template":"test_1.tar.zst"}""",storageRequestBody(key,StorageRequest.TemplateDownload(catalogue.single())).decodeToString())
        val req=StorageRequest.UrlDownload("https://example.test/a?secret=yes","iso","a.iso",Checksum("sha256","a".repeat(64)),false,"gz")
        val body=storageRequestBody(key,req).decodeToString()
        assertTrue(body.contains("\"verify-certificates\":false")); assertTrue(body.contains("\"checksum-algorithm\":\"sha256\"")); assertTrue(body.contains("\"compression\":\"gz\""))
        assertEquals("{\"notes\":\"a\\nb\"}",storageRequestBody(key,StorageRequest.EditNotes(volume(),"a\nb")).decodeToString())
    }
    @Test fun sourceIdentityAndMtimeChangesAreRefused() {
        val p = Files.createTempFile("storage-source", ".iso")
        try { Files.write(p, byteArrayOf(1,2)); val source = UploadSource.capture(p); assertTrue(source.unchanged()); Files.write(p, byteArrayOf(2,1)); Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()+10000)); assertFalse(source.unchanged()); assertFalse(source.toString().contains(p.toString())) } finally { Files.deleteIfExists(p) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StorageActionWorkbenchTest {
    private val key = StorageKey("https://example.test:8006", "node-a", "local")
    private val row = StorageVolume("local:backup/a", "backup", null, 1, null, null, null, 120, "first", false, null)
    private class Gateway : StorageActionGateway {
        var meta: suspend (String) -> UrlMetadata = { UrlMetadata("a.iso",1,null) }; var sends = 0; var reads = 0; var statusNode: String? = null
        var statusResult=PowerTaskStatus(PowerPhase.Succeeded,"Stopped with exit status OK.")
        var submitter: suspend () -> String? = { null }
        var attr: suspend () -> StorageAttributes = { StorageAttributes("full\nnotes", true) }
        override suspend fun catalogue(connection: Connection,node: String): List<AplTemplate> { reads++; return emptyList() }
        override suspend fun metadata(connection: Connection,node: String,url: String,verify: Boolean): UrlMetadata { reads++; return meta(url) }
        override suspend fun attributes(connection: Connection,key: StorageKey,volid: String): StorageAttributes { reads++; return attr() }
        override suspend fun submit(connection: Connection,key: StorageKey,request: StorageRequest,progress: (Long)->Unit,cancel: UploadCancel): String? { sends++; return submitter() }
        override suspend fun status(connection: Connection,node: String,upid: String): PowerTaskStatus { statusNode=node; return statusResult }
    }
    private fun ready(demo: Boolean = false): WorkspaceState {
        val k = if(demo) key.copy(remote=DEMO_REMOTE_KEY) else key
        return WorkspaceState(connected=!demo, demo=demo, address=key.remote, connectionEpoch=1, storageSelection=k,
            overview=Overview(1,emptyList(),members=listOf(RecordedMember(k.remote,k.node,"online",null,null,null,null,null,null,null,null)),storage=listOf(RecordedStorage(k.remote,k.node,k.storage,false,1,10,"available","dir","iso,vztmpl,backup"))),
            storageContent=ReadSection(value=StorageContentView(k,null,listOf(row))))
    }
    @Test fun oneUseReadbackAndUnknownStorageBlocking() = runTest {
        val state=MutableStateFlow(ready()); val g=Gateway(); val conn=Connection(key.remote,"test@pve!api","fixture","")
        var lock: String?=null; var refreshed=0
        val w=StorageActionWorkbench(this,state,g,{conn},{ if(lock==null) {lock=it;true} else false },{if(lock==it) lock=null},{},{refreshed++})
        val c=assertNotNull(w.capture(key,StorageRequest.EditNotes(row,"full\nnotes"),"test"))
        assertTrue(c.facts.any { it == "Volume: ${row.volid}" })
        assertTrue(w.submit(c)); assertFalse(w.submit(c)); runCurrent()
        assertEquals(1,g.sends); assertEquals(row.volid,state.value.storageActions.submissions.first().subject); assertEquals(PowerPhase.Succeeded,state.value.storageActions.submissions.first().phase); assertEquals(1,refreshed); assertNull(lock)
        g.attr={ StorageAttributes("different",true) }
        assertTrue(w.submit(assertNotNull(w.capture(key,StorageRequest.EditNotes(row,"full\nnotes"),"test"))))
        runCurrent(); assertEquals(PowerPhase.Unknown,state.value.storageActions.submissions.first().phase)
        assertNull(w.capture(key,StorageRequest.SetProtection(row,true),"test")); assertEquals(2,g.sends)
        w.acknowledge(state.value.storageActions.submissions.first().localId)
        assertNotNull(w.capture(key,StorageRequest.SetProtection(row,true),"test")); w.cancel(); conn.close()
    }
    @Test fun uploadRequiresMatchingListingAndWarnsEvenWhenPermissionsHideCollisions() = runTest {
        val file=Files.createTempFile("storage-filter", ".iso"); Files.write(file,byteArrayOf(1))
        val state=MutableStateFlow(ready()); val gateway=Gateway()
        val conn=Connection(key.remote,"test@pve!api","fixture","")
        val work=StorageActionWorkbench(this,state,gateway,{conn},{true},{},{},{})
        val request=StorageRequest.Upload(UploadSource.capture(file),"iso","collision.iso")
        fun listing(filter:String?, rows:List<StorageVolume>) { state.value=ready().copy(storageContent=ReadSection(value=StorageContentView(key,filter,rows))) }
        try {
            listing("backup",listOf(row))
            assertNull(work.capture(key,request,"test"))
            val iso=row.copy(volid="local:iso/collision.iso",content="iso",vmid=null)
            listing("iso",listOf(iso))
            val warned=assertNotNull(work.capture(key,request,"test"))
            assertTrue(warned.replaces)
            assertTrue(warned.facts.any { it.contains("cannot be recovered") })
            listing("iso",emptyList())
            val hidden=assertNotNull(work.capture(key,request,"test"))
            assertFalse(hidden.replaces)
            assertTrue(hidden.facts.any { it.contains("hidden from this account") })
            // Equal empty row lists must not let a changed filter evade either fence.
            listing("backup",emptyList())
            assertFalse(work.submit(hidden))
            listing("iso",emptyList())
            assertTrue(work.submit(assertNotNull(work.capture(key,request,"test"))))
            listing("backup",emptyList()); runCurrent()
            assertEquals(0,gateway.sends)
        } finally { work.cancel();conn.close();Files.deleteIfExists(file) }
    }
    @Test fun selectionChangeBeforeLaunchAndEpochChangesFenceCallbacks() = runTest {
        val state=MutableStateFlow(ready()); val g=Gateway(); val conn=Connection(key.remote,"test@pve!api","fixture","")
        val w=StorageActionWorkbench(this,state,g,{conn},{true},{},{},{})
        val c=assertNotNull(w.capture(key,StorageRequest.SetProtection(row,true),"test")); assertTrue(w.submit(c)); state.value=state.value.copy(storageSelection=null); runCurrent(); assertEquals(0,g.sends)
        state.value=ready(); val deferred=CompletableDeferred<String?>(); g.submitter={withContext(NonCancellable){deferred.await()}}
        assertTrue(w.submit(assertNotNull(w.capture(key,StorageRequest.SetProtection(row,true),"test")))); runCurrent()
        w.cancel(); state.value=ready().copy(connectionEpoch=2); deferred.complete(null); runCurrent(); assertTrue(state.value.storageActions.submissions.isEmpty()); conn.close()
    }
    @Test fun explicitMetadataReadAndDemoMutationsUseNoGateway() = runTest {
        val state=MutableStateFlow(ready(true)); val g=Gateway(); val k=key.copy(remote=DEMO_REMOTE_KEY)
        val w=StorageActionWorkbench(this,state,g,{null},{true},{},{},{})
        assertEquals(0,g.reads); w.read(k,url="https://example.test/a?secret=yes"); runCurrent(); assertNotNull(state.value.storageActions.metadata)
        val reqs=listOf(StorageRequest.TemplateDownload(AplTemplate("test_1.tar.zst","test","1","lxc")),StorageRequest.UrlDownload("https://example.test/a","iso","a.iso"),StorageRequest.EditNotes(row,"notes"),StorageRequest.SetProtection(row,true),StorageRequest.RemoveVolume(row))
        for(r in reqs) { state.value=ready(true); assertTrue(w.submit(assertNotNull(w.capture(k,r,"demo")))); runCurrent(); assertEquals(PowerPhase.Simulated,state.value.storageActions.submissions.first().phase) }
        assertEquals(0,g.sends); assertEquals(0,g.reads); w.cancel()
    }
    @Test fun uploadFollowsWorkerNodeAndMapsExactLogWithoutExposingUpidInSubmission() = runTest {
        val p=Files.createTempFile("storage-worker", ".iso"); Files.write(p,byteArrayOf(1))
        try {
            val state=MutableStateFlow(ready()); val g=Gateway(); val conn=Connection(key.remote,"test@pve!api","fixture","")
            val upid="UPID:worker-b:00000010:00000011:65ABCDEF:imgcopy::test@pve:"
            g.submitter={upid}
            val w=StorageActionWorkbench(this,state,g,{conn},{true},{},{},{})
            val r=StorageRequest.Upload(UploadSource.capture(p),"iso","a.iso")
            assertTrue(w.submit(assertNotNull(w.capture(key,r,"test")))); runCurrent(); advanceTimeBy(1001); runCurrent()
            assertEquals("worker-b",g.statusNode)
            val sub=state.value.storageActions.submissions.first(); assertEquals(PowerPhase.Succeeded,sub.phase)
            assertEquals(upid,w.task(sub.localId)!!.upid); assertEquals("worker-b",w.task(sub.localId)!!.node)
            assertFalse(sub.toString().contains("UPID:")); w.cancel(); conn.close()
        } finally { Files.deleteIfExists(p) }
    }
    @Test fun reverseMetadataCompletionAndSelectionChangeNeverPublishOldResults() = runTest {
        val state=MutableStateFlow(ready()); val g=Gateway(); val conn=Connection(key.remote,"test@pve!api","fixture","")
        val first=CompletableDeferred<UrlMetadata>(); val second=CompletableDeferred<UrlMetadata>()
        g.meta={ if(it.endsWith("1")) first.await() else second.await() }
        val w=StorageActionWorkbench(this,state,g,{conn},{true},{},{},{})
        assertEquals(0,g.reads)
        w.read(key,url="https://example.test/1"); runCurrent(); w.read(key,url="https://example.test/2"); runCurrent()
        second.complete(UrlMetadata("new.iso",2,null)); runCurrent(); first.complete(UrlMetadata("old.iso",1,null)); runCurrent()
        assertEquals("new.iso",state.value.storageActions.metadata!!.fileName)
        w.selectionChanged(); assertNull(state.value.storageActions.metadata); assertEquals(0,g.sends); w.cancel(); conn.close()
    }
    @Test fun capturedOverwriteCannotChangeAndWrongEpochNeverSends() = runTest {
        val p=Files.createTempFile("storage-overwrite", ".iso"); Files.write(p,byteArrayOf(1))
        try {
            val state=MutableStateFlow(ready()); val g=Gateway(); val conn=Connection(key.remote,"test@pve!api","fixture","")
            val w=StorageActionWorkbench(this,state,g,{conn},{true},{},{},{})
            val req=StorageRequest.Upload(UploadSource.capture(p),"iso","a.iso")
            val c=assertNotNull(w.capture(key,req,"test")); assertFalse(c.replaces)
            state.value=state.value.copy(storageContent=ReadSection(value=StorageContentView(key,null,listOf(row,row.copy(volid="local:iso/a.iso",content="iso")))))
            assertFalse(w.submit(c)); assertEquals(0,g.sends)
            val overwrite=assertNotNull(w.capture(key,req,"test")); assertTrue(overwrite.replaces); assertTrue(overwrite.facts.any { it.contains("replaces") })
            state.value=state.value.copy(connectionEpoch=2); assertFalse(w.submit(overwrite)); assertEquals(0,g.sends); w.cancel(); conn.close()
        } finally { Files.deleteIfExists(p) }
    }

    @Test fun warningsReadbackFailureAndLegacyAmbiguityStayNonSuccess() = runTest {
        val conn=Connection(key.remote,"test@pve!api","fixture","")
        for(mode in listOf("warning","readback","lost")) {
            val state=MutableStateFlow(ready()); val g=Gateway()
            val w=StorageActionWorkbench(this,state,g,{conn},{true},{},{},{})
            val r=if(mode=="warning") StorageRequest.RemoveVolume(row) else StorageRequest.SetProtection(row,true)
            when(mode) {
                "warning" -> { g.submitter={"UPID:node-a:00000010:00000011:65ABCDEF:imgdel:120@local:test@pve:"}; g.statusResult=PowerTaskStatus(PowerPhase.Unsuccessful,"WARNINGS: 2") }
                "readback" -> g.attr={throw java.io.IOException("reflected private content")}
                else -> g.submitter={throw AmbiguousPowerResultException()}
            }
            assertTrue(w.submit(assertNotNull(w.capture(key,r,"test")))); runCurrent(); advanceTimeBy(1001); runCurrent()
            val sub=state.value.storageActions.submissions.first()
            assertEquals(if(mode=="warning") PowerPhase.Unsuccessful else PowerPhase.Unknown,sub.phase)
            if(mode=="warning") assertEquals(2,sub.warningCount)
            assertFalse(sub.toString().contains("private content")); assertEquals(1,g.sends); w.cancel()
        }
        conn.close()
    }
    @Test fun unknownBlocksTheSameStorageThroughAnotherNode() = runTest {
        val conn=Connection(key.remote,"test@pve!api","fixture","")
        val state=MutableStateFlow(ready()); val g=Gateway().apply { submitter={throw StorageUnclear()} }
        val w=StorageActionWorkbench(this,state,g,{conn},{true},{},{},{})
        assertTrue(w.submit(assertNotNull(w.capture(key,StorageRequest.SetProtection(row,true),"test")))); runCurrent()
        val other=key.copy(node="node-b"); val overview=state.value.overview!!
        state.value=state.value.copy(storageSelection=other,storageContent=ReadSection(value=StorageContentView(other,null,listOf(row))),overview=overview.copy(members=overview.members+overview.members.first().copy(name="node-b"),storage=overview.storage+overview.storage.first().copy(node="node-b")))
        assertNull(w.capture(other,StorageRequest.SetProtection(row,false),"test")); assertEquals(1,g.sends); w.cancel(); conn.close()
    }

    @Test fun demoUploadDoesNotReadFileBytesOrCallGatewayAndPendingBlocksCapture() = runTest {
        val p=Files.createTempFile("storage-demo", ".iso"); Files.write(p,byteArrayOf(1,2))
        try {
            val state=MutableStateFlow(ready(true)); val g=Gateway(); val k=key.copy(remote=DEMO_REMOTE_KEY)
            val w=StorageActionWorkbench(this,state,g,{null},{true},{},{},{})
            val r=StorageRequest.Upload(UploadSource.capture(p),"iso","a.iso")
            val c=assertNotNull(w.capture(k,r,"demo")); assertTrue(w.submit(c)); assertNull(w.capture(k,r,"demo")); runCurrent()
            assertEquals(PowerPhase.Simulated,state.value.storageActions.submissions.first().phase); assertEquals(0,g.sends); assertEquals(0,g.reads)
            assertTrue(state.value.storageContent.value!!.volumes.any { it.volid=="local:iso/a.iso" }); w.cancel()
        } finally { Files.deleteIfExists(p) }
    }
    @Test fun expiredMutationPublishesUncertaintyAndPropagatesTheCapturedEpoch() = runTest {
        val state=MutableStateFlow(ready()); val g=Gateway().apply { submitter={throw SessionExpiredException()} }
        val conn=Connection(key.remote,"test@pve!api","fixture",""); var expired:Long?=null
        val w=StorageActionWorkbench(this,state,g,{conn},{true},{},{expired=it},{})
        assertTrue(w.submit(assertNotNull(w.capture(key,StorageRequest.SetProtection(row,true),"test")))); runCurrent()
        assertEquals(1L,expired); assertEquals(PowerPhase.Unknown,state.value.storageActions.submissions.first().phase); assertEquals(1,g.sends); w.cancel(); conn.close()
    }

}
