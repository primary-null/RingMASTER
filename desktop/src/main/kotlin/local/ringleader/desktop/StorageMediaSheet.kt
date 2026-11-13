@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import local.ringleader.core.*

class StorageMediaReady(val epoch: Long, val generation: Long, val node: String, val storage: String, val volid: String) {
    override fun toString() = "StorageMediaReady(redacted)"
}
internal fun mediaReadyMatches(ready: StorageMediaReady, session: CreateSession): Boolean =
    ready.epoch==session.epoch && ready.generation==session.generation && ready.node==session.node &&
        ready.volid.startsWith("${ready.storage}:") && session.volumes[ready.storage to ready.volid.substringAfter(':').substringBefore('/')]?.let { !it.loading && !it.stale && it.error==null && it.value?.any {v -> v.volid==ready.volid}==true }==true
internal fun mediaSubmissionReady(sub: StorageSubmission?, key: StorageKey, expected: String?, state: WorkspaceState): Boolean =
    expected != null && sub?.key==key && sub.subject==expected.substringAfter('/') &&
        (sub.phase==PowerPhase.Succeeded && sub.evidence==StorageSuccessEvidence.TerminalOk || sub.phase==PowerPhase.Simulated) &&
        state.storageSelection==key && !state.storageContent.loading && !state.storageContent.stale && state.storageContent.error==null &&
        state.storageContent.value?.let {it.key==key && it.volumes.any {v -> v.volid==expected}}==true

/** Drawn above the still-composed wizard. It never owns, clears or replaces its draft or secret buffers. */
@Composable internal fun StorageMediaSheet(state: WorkspaceState, workspace: Workspace, session: CreateSession,
    preferred: String, kind: MediaKind, profileLabel: String, onReady: (StorageMediaReady)->Unit, onClose: ()->Unit) {
    val browser=LocalAppHost.current.web
    val targets=mediaTargets(state,session.node,kind,preferred).filter {
        !browser || kind!=MediaKind.Iso || storageActionGate(StorageAction.UrlDownload,if(state.demo) null else state.permissions.value?.dump,it.key)==null
    }
    var target by remember {mutableStateOf(targets.firstOrNull()?.key)}
    var tab by remember {mutableStateOf(if(kind==MediaKind.Template) "Catalogue" else "URL")}
    var editing by remember {mutableStateOf(false)}
    var submitted by remember {mutableStateOf<String?>(null)}
    var expected by remember {mutableStateOf<String?>(null)}
    var refreshing by remember {mutableStateOf(false)}
    var closeUpload by remember {mutableStateOf(false)}
    val sub=state.storageActions.submissions.firstOrNull {it.localId==submitted}
    val epoch=remember {session.epoch}; val generation=remember {session.generation};val node=remember {session.node}
    val valid=state.creation?.let {it.epoch==epoch && it.generation==generation && it.node==node}==true
    LaunchedEffect(target) {target?.let {workspace.selectStorage(it.node,it.storage)}}
    LaunchedEffect(valid) {if(!valid) onClose()}
    LaunchedEffect(sub,state.storageContent,state.creation?.volumes) {
        val key=target ?: return@LaunchedEffect
        if(valid && mediaSubmissionReady(sub,key,expected,state)) {
            val ready=StorageMediaReady(epoch,generation,node,key.storage,expected!!)
            if(!refreshing) {refreshing=true;workspace.refreshCreateMedia(epoch,generation,node,key.storage,kind.content)}
            else state.creation?.takeIf {mediaReadyMatches(ready,it)}?.let {onReady(ready)}
        }
    }
    fun close() {
        if(sub?.action==StorageAction.Upload && sub.phase==PowerPhase.Accepted && !sub.checkable) closeUpload=true else onClose()
    }
    StorageDialog(if(kind==MediaKind.Iso) "Get ISO image" else "Get container template",::close,width=640.dp,actions={TechAction(if(sub?.phase==PowerPhase.Accepted) "Started \u00b7 close" else "Close",onClick=::close)}) {
        if(targets.isEmpty()) Text("No write-capable target is recorded on this node. A supported file storage with ${kind.content} content and Datastore.AllocateTemplate is required. Browser ISO downloads also need network permission on this node.",color=Tech.Amber)
        else {
            TechSelect("Target storage",targets,targets.firstOrNull {it.key==target},{if(submitted==null) target=it.key},{it.key.storage},enabled=submitted==null)
            TechTabs(if(kind==MediaKind.Template) listOf("Catalogue","URL") + if(browser) emptyList() else listOf("Upload") else listOf("URL") + if(browser) emptyList() else listOf("Upload"),tab,{tab=it})
            if(submitted==null) MiniAction("Open $tab",enabled=valid && !state.storageContent.loading && state.storageContent.error==null) {editing=true}
            Text("Your create form stays open. A task being accepted does not make media ready. Only Terminal OK and the exact file in refreshed storage can select it.",color=Tech.Muted)
            Box(Modifier.heightIn(max=260.dp)) {StorageActionStrip(state,workspace,target)}
            if(sub?.phase==PowerPhase.Succeeded || sub?.phase==PowerPhase.Simulated) {
                Text("Checking exact file and refreshing create choices\u2026",color=Tech.Amber)
                if(state.creation?.volumes?.get(target!!.storage to kind.content)?.error != null)
                    Text("Could not refresh the create choices. Refresh storage and choices to try again.",color=Tech.Amber)
                MiniAction("Refresh storage and choices",enabled=!state.storageContent.loading) {
                    refreshing=false
                    workspace.refreshStorageContent()
                }
            }
            if(sub?.phase in listOf(PowerPhase.Rejected,PowerPhase.Unsuccessful)) MiniAction("Return to form") {submitted=null;expected=null;refreshing=false}
        }
    }
    if(editing && target!=null) StorageActionDialog(state,workspace,target!!,when(tab) {"Catalogue" -> StorageAction.TemplateDownload;"Upload" -> StorageAction.Upload;else -> StorageAction.UrlDownload},contentHint=kind.content,profileLabel=profileLabel,
        onSubmitted={ c ->
            val name=when(val r=c.request) {is StorageRequest.TemplateDownload -> r.template.template;is StorageRequest.UrlDownload -> r.fileName;is StorageRequest.Upload -> r.fileName;else -> ""}
            expected=expectedVolid(c.key.storage,kind,name)
            submitted=workspace.state.value.storageActions.submissions.firstOrNull()?.localId
        },onClose={editing=false})
    if(state.taskLog!=null) TaskLogPanel(state,workspace,Modifier.fillMaxSize())
    if(closeUpload) StorageDialog("Close upload?",{closeUpload=false},actions={
        TechAction("Keep open",onClick={closeUpload=false})
        TechAction("Continue in activity",onClick=onClose)
        TechAction("Stop upload",onClick={submitted?.let(workspace::cancelStorageUpload);onClose()})
    }) {Text("Stopping may leave an Unknown result. Closing with Continue leaves the upload running.",color=Tech.Amber)}
}
