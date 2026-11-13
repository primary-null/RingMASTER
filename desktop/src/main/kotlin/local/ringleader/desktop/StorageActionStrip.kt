@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import local.ringleader.core.*

internal fun storageResultLabel(s: StorageSubmission): String = when {
    s.simulated || s.phase==PowerPhase.Simulated -> "Demo \u00b7 simulated"
    s.warningCount!=null -> "${s.warningCount} warnings \u00b7 check task log"
    s.phase==PowerPhase.Accepted -> if(s.checkable) "Following server task" else "Sending"
    s.phase==PowerPhase.Succeeded -> if(s.evidence==StorageSuccessEvidence.TerminalOk) "Terminal OK" else "Exact attributes read back"
    s.phase==PowerPhase.Unknown -> "Unknown \u00b7 check storage before trying again"
    s.phase==PowerPhase.Rejected -> "Rejected"
    else -> "Failed"
}
@Composable internal fun StorageActionStrip(state: WorkspaceState, workspace: Workspace, key: StorageKey? = null) {
    var stop by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        state.storageActions.submissions.filter { key==null || it.key==key }.forEach { s ->
            Column(Modifier.fillMaxWidth()) {
                Text("${storageActionTitle(s.action)} \u00b7 ${s.node} \u00b7 ${s.storage} \u00b7 ${s.subject}",Modifier.identity(),color=Tech.Ink)
                Text(storageResultLabel(s),color=when { s.warningCount!=null -> Tech.Amber; s.phase==PowerPhase.Succeeded -> Tech.Green; s.phase in listOf(PowerPhase.Rejected,PowerPhase.Unsuccessful) -> Tech.Red;else -> Tech.Amber })
                Text(s.detail,Modifier.identity(),color=Tech.Muted)
                s.totalBytes?.takeIf { !s.simulated && s.phase!=PowerPhase.Simulated }?.let { total ->
                    if(s.phase==PowerPhase.Accepted) TechProgress(fraction=if(total>0) s.bytesSent.toFloat()/total else null)
                    // Only after every byte has gone does the wait move to the server task.
                    val waiting=if(s.phase==PowerPhase.Accepted && s.bytesSent>=total) " \u00b7 awaiting server task completion" else ""
                    Text("${s.bytesSent} / $total file bytes sent$waiting",color=Tech.Muted)
                }
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    if(s.checkable) MiniAction("View task log") {workspace.openStorageTaskLog(s.localId)}
                    if(s.phase==PowerPhase.Unknown) MiniAction("I checked storage") {workspace.acknowledgeStorageResult(s.localId)}
                    if(s.action==StorageAction.Upload && s.phase==PowerPhase.Accepted && !s.checkable) MiniAction("Stop upload") {stop=s.localId}
                }
            }
        }
    }
    stop?.let { id -> StorageDialog("Stop upload?",{stop=null},actions={
        TechAction("Keep uploading",onClick={stop=null})
        TechAction("Stop upload",onClick={stop=null;workspace.cancelStorageUpload(id)})
    }) { Text("Bytes may already have reached the node. The outcome may be Unknown; check storage before trying again.",color=Tech.Amber) } }
}

@Composable internal fun StorageFileActions(state: WorkspaceState, workspace: Workspace, key: StorageKey, volume: StorageVolume? = null, profileLabel: String = "Active connection") {
    var action by remember(key,volume) { mutableStateOf<StorageAction?>(null) }
    val row=state.overview?.storage?.firstOrNull {it.remote==key.remote && it.node==key.node && it.id==key.storage} ?: return
    val browser=LocalAppHost.current.web
    val actions=storageCapabilities(row).filter {
        if(volume==null) it in listOf(StorageAction.TemplateDownload,StorageAction.UrlDownload,StorageAction.Upload)
        else when(it) { StorageAction.RemoveVolume -> volume.content in listOf("iso","vztmpl","import","snippets","backup");StorageAction.EditNotes,StorageAction.SetProtection -> volume.content=="backup";else -> false }
    }.filter {it!=StorageAction.Upload || !browser}
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        actions.forEach { a ->
            val refusal=storageActionGate(a,if(state.demo) null else state.permissions.value?.dump,key,volume)
                ?: if(a==StorageAction.RemoveVolume && volume?.protected==true) "Protected. Remove protection first." else null
            HelpTip(refusal ?: storageActionTitle(a),identity=refusal!=null) { // refusals name the storage path
                MiniAction(if(a==StorageAction.SetProtection) if(volume?.protected==true) "Unprotect" else "Protect" else storageActionTitle(a),
                    enabled=refusal==null && !state.storageContent.loading && !state.storageContent.stale && state.storageContent.error==null) {action=a}
            }
        }
    }
    action?.let { a -> StorageActionDialog(state,workspace,key,a,volume,profileLabel=profileLabel,onClose={action=null}) }
}
