@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.*
import local.ringleader.core.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.*

class StorageDesktopTest {
    private fun nodes(scene:ImageComposeScene):List<SemanticsNode> {
        val result=mutableListOf<SemanticsNode>();fun visit(n:SemanticsNode) {result+=n;n.children.forEach(::visit)}
        scene.semanticsOwners.forEach {visit(it.rootSemanticsNode)};return result
    }
    private fun label(n:SemanticsNode)=n.config.getOrNull(SemanticsProperties.Text)?.joinToString(""){it.text}.orEmpty()
    private fun click(scene:ImageComposeScene,text:String) {
        val n=nodes(scene).last {label(it)==text.uppercase() && it.config.getOrNull(SemanticsActions.OnClick)!=null}
        assertTrue(n.config[SemanticsActions.OnClick].action!!.invoke())
    }
    private suspend fun settle(scene:ImageComposeScene) { repeat(5) {scene.render((it+1)*100_000_000L).close();yield()} }
    @Test fun urlCheckIsExplicitAndCancelNeverSendsAndConfirmIsSingleUse()=runSceneTest {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val w=Workspace(scope);w.exploreDemo()
        val row=w.state.value.overview!!.storage.first {"iso" in it.content.orEmpty()};w.selectStorage(row.node,row.id);yield()
        var open by mutableStateOf(true)
        val scene=ImageComposeScene(1200,800,coroutineContext=coroutineContext) {
            val state by w.state.collectAsState()
            MaterialTheme(colorScheme=ringColors) { if(open) StorageActionDialog(state,w,state.storageSelection!!,StorageAction.UrlDownload,onClose={open=false}) }
        }
        try {
            settle(scene);assertNull(w.state.value.storageActions.metadata)
            val fields=nodes(scene).filter {it.config.getOrNull(SemanticsActions.SetText)!=null}
            fields[0].config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("https://example.test/a?secret=yes"))
            fields[1].config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("fixture.iso"))
            settle(scene);assertNull(w.state.value.storageActions.metadata);assertTrue(w.state.value.storageActions.submissions.isEmpty())
            click(scene,"Check URL");settle(scene);assertNotNull(w.state.value.storageActions.metadata)
            click(scene,"Review");settle(scene);assertTrue(w.state.value.storageActions.submissions.isEmpty())
            click(scene,"Cancel");settle(scene);assertTrue(w.state.value.storageActions.submissions.isEmpty())
            click(scene,"Review");settle(scene);click(scene,"Simulate");settle(scene)
            assertEquals(1,w.state.value.storageActions.submissions.size);assertEquals(PowerPhase.Simulated,w.state.value.storageActions.submissions.single().phase)
        } finally {scene.close();scope.cancel()}
    }
    @Test fun hostFileRefusalAndBoundedHashingAndSourceChange(): Unit=runBlocking {
        assertFailsWith<IllegalArgumentException> {chooseStorageUpload(true)}
        val p=Files.createTempFile("storage-ui-fixture", ".iso")
        try {
            Files.write(p,ByteArray(196613){it.toByte()});val file=DesktopUpload(UploadSource.capture(p),"fixture.iso",p)
            val progress=mutableListOf<Long>();val hash=hashStorageUpload(file){progress+=it}
            assertEquals(64,hash.length);assertEquals(file.source.size,progress.last());assertTrue(progress.zipWithNext().all {it.second-it.first<=65536})
            assertFalse(file.toString().contains(p.toString()));Files.write(p,byteArrayOf(1));assertFailsWith<IllegalArgumentException>{hashStorageUpload(file){}}
        } finally {Files.deleteIfExists(p)}
    }
    @Test fun mediaNeverReadyAtAcceptanceMissingListingUnknownOrOldNode() {
        val key=StorageKey(DEMO_REMOTE_KEY,"node-a","local");val expected="local:iso/fixture.iso"
        val volume=StorageVolume(expected,"iso",null,1,null,null,null,null,null,false,null)
        val state=WorkspaceState(storageSelection=key,storageContent=ReadSection(value=StorageContentView(key,null,listOf(volume))))
        val accepted=StorageSubmission("local-test",key,StorageAction.UrlDownload,PowerPhase.Accepted,"Sending",subject="fixture.iso")
        assertFalse(mediaSubmissionReady(accepted,key,expected,state))
        assertFalse(mediaSubmissionReady(accepted.copy(phase=PowerPhase.Unknown),key,expected,state))
        val ok=accepted.copy(phase=PowerPhase.Succeeded,evidence=StorageSuccessEvidence.TerminalOk)
        assertTrue(mediaSubmissionReady(ok,key,expected,state));assertFalse(mediaSubmissionReady(ok,key,expected,state.copy(storageContent=ReadSection())))
        val session=CreateSession(NewGuestKind.Vm,"node-a",1,3,volumes=mapOf(("local" to "iso") to ReadSection(value=listOf(volume))))
        val ready=StorageMediaReady(1,3,"node-a","local",expected)
        assertTrue(mediaReadyMatches(ready,session));assertFalse(mediaReadyMatches(ready,session.copy(node="node-b")));assertFalse(mediaReadyMatches(ready,session.copy(generation=4)))
        assertFalse(mediaReadyMatches(ready,session.copy(volumes=emptyMap())))
    }
    @Test fun warningAndUnknownLabelsNeverClaimSuccess() {
        val key=StorageKey(DEMO_REMOTE_KEY,"node-a","local")
        val warning=StorageSubmission("local-test",key,StorageAction.Upload,PowerPhase.Unsuccessful,"safe",warningCount=2)
        assertTrue(storageResultLabel(warning).contains("2 warnings"))
        assertTrue(storageResultLabel(warning.copy(phase=PowerPhase.Unknown,warningCount=null)).startsWith("Unknown"))
    }
    @Test fun bothWizardsRetainDraftAndApplyOnlyExactRefreshedMedia()=runSceneTest {
        val volid="local:iso/exact.iso"
        val volume=StorageVolume(volid,"iso",null,1,null,null,null,null,null,false,null)
        val session=CreateSession(NewGuestKind.Vm,"docs-node",7,3,volumes=mapOf(("local" to "iso") to ReadSection(value=listOf(volume))))
        var ready by mutableStateOf<StorageMediaReady?>(null)
        var observed:VmDraft?=null
        var sheet by mutableStateOf(false)
        val scene=ImageComposeScene(1200,800,coroutineContext=coroutineContext) {
            MaterialTheme(colorScheme=ringColors) {
                CreateVmWizard(session,ctSceneContext,listOf("docs-node"),{},{},{},{},{},initialAdvanced=false,
                    defaults={_,_->VmDraft("docs-node",name="retained-name",vmid="140")},
                    parameters={d,_->observed=d;emptyList()},storageMediaReady=ready,onStorageMediaApplied={ready=null},preferences=CreateWizardPreferences.memory())
                if(sheet) StorageDialog("Media overlay",{sheet=false},actions={TechAction("Close",onClick={sheet=false})}) { androidx.compose.material3.Text("Synthetic sheet") }
            }
        }
        try {
            settle(scene);sheet=true;settle(scene);sheet=false;settle(scene)
            assertEquals("retained-name",observed!!.name)
            ready=StorageMediaReady(7,3,"old-node","local",volid);settle(scene);assertEquals("retained-name",observed!!.name);assertFalse((observed!!.install.media as? CdMedia.Iso)?.volid==volid)
            ready=StorageMediaReady(7,3,"docs-node","local",volid);settle(scene)
            assertEquals("retained-name",observed!!.name);assertEquals(volid,(observed!!.install.media as CdMedia.Iso).volid)
        } finally {scene.close()}
        val ctVol=volume.copy(volid="local:vztmpl/exact.tar.zst",content="vztmpl")
        val ctSession=session.copy(kind=NewGuestKind.Container,volumes=mapOf(("local" to "vztmpl") to ReadSection(value=listOf(ctVol))))
        var ctReady by mutableStateOf<StorageMediaReady?>(null);var ctObserved=ctSceneDraft
        val ct=ImageComposeScene(1200,800,coroutineContext=coroutineContext) {
            MaterialTheme(colorScheme=ringColors) {CreateCtWizard(ctSession,ctSceneContext,ctSceneDraft,{_,secrets->secrets.close()},{},
                initialAdvanced=false,onDraftChange={ctObserved=it},storageMediaReady=ctReady,onStorageMediaApplied={ctReady=null},preferences=CreateWizardPreferences.memory())}
        }
        try {settle(ct);ctReady=StorageMediaReady(7,3,"docs-node","local",ctVol.volid);settle(ct)
            assertEquals(ctSceneDraft.hostname,ctObserved.hostname);assertEquals(ctVol.volid,ctObserved.template);assertEquals("local",ctObserved.templateStorage)
        } finally {ct.close()}
    }
    @Test fun fullNotesRefreshDoesNotOverwriteEdits()=runSceneTest {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val w=Workspace(scope);w.exploreDemo()
        val row=w.state.value.overview!!.storage.first {"backup" in it.content.orEmpty()};w.selectStorage(row.node,row.id);yield()
        val backup=w.state.value.storageContent.value!!.volumes.first {it.content=="backup"}
        val scene=ImageComposeScene(1200,800,coroutineContext=coroutineContext) {
            val state by w.state.collectAsState()
            MaterialTheme(colorScheme=ringColors) {StorageActionDialog(state,w,state.storageSelection!!,StorageAction.EditNotes,backup,onClose={})}
        }
        try {settle(scene)
            val field=nodes(scene).first {it.config.getOrNull(SemanticsActions.SetText)!=null}
            field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("edited full notes"));settle(scene)
            w.readStorageAttributes(row.node,row.id,backup.volid);settle(scene)
            assertTrue(nodes(scene).any {it.config.getOrNull(SemanticsProperties.EditableText)?.text=="edited full notes"})
            assertTrue(w.state.value.storageActions.submissions.isEmpty())
        }finally{scene.close();scope.cancel()}
    }
    @Test fun browserSyntheticUploadHasNoChooserAndCannotReview()=runSceneTest {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val w=Workspace(scope);w.exploreDemo()
        val row=w.state.value.overview!!.storage.first {it.id=="local"};w.selectStorage(row.node,row.id);yield()
        val p=Files.createTempFile("storage-browser-fixture", ".iso");Files.write(p,byteArrayOf(1))
        val fixture=DesktopUpload(UploadSource.capture(p),"fixture.iso",p)
        val scene=ImageComposeScene(1200,800,coroutineContext=coroutineContext) {
            val state by w.state.collectAsState()
            CompositionLocalProvider(LocalAppHost provides AppHost(true)) {MaterialTheme(colorScheme=ringColors) {StorageActionDialog(state,w,state.storageSelection!!,StorageAction.Upload,initialUpload=fixture,onClose={})}}
        }
        try {settle(scene)
            assertFalse(nodes(scene).any {label(it)=="CHOOSE FILE"})
            val review=nodes(scene).first {label(it)=="REVIEW"};assertNotNull(review.config.getOrNull(SemanticsProperties.Disabled))
            assertNull(w.captureStorage(row.node,row.id,StorageRequest.Upload(fixture.source,"iso","fixture.iso"),"fixture",browser=true))
            assertTrue(w.state.value.storageActions.submissions.isEmpty())
        }finally{scene.close();scope.cancel();Files.deleteIfExists(p)}
    }
    @Test fun forceRefreshPreservesCreateGenerationAndRefusesOldNode()=runSceneTest {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val w=Workspace(scope);w.exploreDemo()
        val row=w.state.value.overview!!.storage.first {it.id=="local"};assertTrue(w.openCreateSession(NewGuestKind.Vm,row.node));yield()
        val original=w.state.value.creation!!;w.selectStorage(row.node,row.id);yield()
        assertFalse(w.refreshCreateMedia(original.epoch,original.generation,"old-node",row.id,"iso"))
        assertFalse(w.refreshCreateMedia(original.epoch,original.generation+1,row.node,row.id,"iso"))
        assertTrue(w.refreshCreateMedia(original.epoch,original.generation,row.node,row.id,"iso"))
        val refreshed=w.state.value.creation!!;assertEquals(original.generation,refreshed.generation);assertEquals(original.epoch,refreshed.epoch)
        assertEquals(w.state.value.storageContent.value!!.volumes.filter {it.content=="iso"},refreshed.volumes[row.id to "iso"]!!.value)
        scope.cancel()
    }
}
