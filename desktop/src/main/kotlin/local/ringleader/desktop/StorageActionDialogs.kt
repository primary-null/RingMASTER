@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import local.ringleader.core.*

internal fun storageActionTitle(action: StorageAction): String = when(action) {
    StorageAction.TemplateDownload -> "Template catalogue"
    StorageAction.UrlDownload -> "Download from URL"
    StorageAction.Upload -> "Upload file"
    StorageAction.RemoveVolume -> "Remove file"
    StorageAction.EditNotes -> "Edit backup notes"
    StorageAction.SetProtection -> "Backup protection"
}

@Composable internal fun StorageConfirmDialog(c: StorageConfirmation, sample: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit, contextFacts: List<String> = emptyList()) {
    val cancel = remember { FocusRequester() }
    StorageDialog(if(sample) "Simulate storage action" else storageActionTitle(c.request.action), onCancel,
        initialFocus = cancel, actions = {
            TechAction("Cancel", Modifier.focusRequester(cancel), onClick = onCancel)
            TechAction(if(sample) "Simulate" else "Confirm", active = true, onClick = onConfirm)
        }) {
        Text("${c.profileLabel} \u00b7 ${c.origin}", Modifier.identity(), color = Tech.Ink)
        c.facts.forEach { Text(if(it == "Action: ${c.request.action}") "Action: ${storageActionTitle(c.request.action)}" else it, Modifier.identity(), color = if(c.replaces || c.request is StorageRequest.RemoveVolume) Tech.Amber else Tech.Ink) }
        contextFacts.forEach {Text(it,Modifier.identity(),color=Tech.Amber)}
        if(c.request is StorageRequest.SetProtection) Text(if((c.request as StorageRequest.SetProtection).protect) "Protected backups cannot be removed and are skipped by pruning." else "Without protection this backup can be removed and pruned.",color=Tech.Amber)
        if(c.request is StorageRequest.RemoveVolume) Text("Guests still using this media may fail to boot. Shared files disappear for every node. This cannot be undone.", color = Tech.Red)
    }
}

/** Holds only transient form data. Core capture remains the authority for every send. */
@Composable internal fun StorageActionDialog(state: WorkspaceState, workspace: Workspace, key: StorageKey,
    action: StorageAction, volume: StorageVolume? = null, contentHint: String? = null,
    profileLabel: String = "Active connection", initialUpload: DesktopUpload? = null, initialUrl: String = "", initialName: String? = null, initialTemplate: AplTemplate? = null, initialNotes: String? = null, onSubmitted: (StorageConfirmation) -> Unit = {}, onClose: () -> Unit) {
    val browser = LocalAppHost.current.web
    val picker = LocalStorageFilePicker.current
    val scope = rememberCoroutineScope()
    val row = state.overview?.storage?.firstOrNull { it.node == key.node && it.id == key.storage && it.remote == key.remote }
    var content by remember { mutableStateOf(contentHint ?: row?.content.orEmpty().split(',').firstOrNull { it in listOf("iso", "vztmpl", "import") } ?: "iso") }
    var url by remember { mutableStateOf(initialUrl) }
    var name by remember { mutableStateOf(initialName ?: initialUpload?.basename.orEmpty()) }
    var algorithm by remember { mutableStateOf("none") }
    var hex by remember { mutableStateOf("") }
    var compression by remember { mutableStateOf("none") }
    var verify by remember { mutableStateOf(true) }
    var search by remember { mutableStateOf("") }
    var template by remember { mutableStateOf<AplTemplate?>(initialTemplate) }
    var source by remember { mutableStateOf<DesktopUpload?>(initialUpload) }
    var ioJob by remember { mutableStateOf<Job?>(null) }
    var ioBusy by remember { mutableStateOf(false) }
    // Written from the IO thread once per 64 KiB chunk; a StateFlow keeps at most one pending UI update.
    val ioProgress = remember { kotlinx.coroutines.flow.MutableStateFlow(0L) }
    val ioBytes by ioProgress.collectAsState()
    val firstField = remember { FocusRequester() }
    var notes by remember { mutableStateOf(initialNotes.orEmpty()) }
    var notesLoaded by remember { mutableStateOf(initialNotes!=null && initialNotes==state.storageActions.attributes?.notes && state.storageActions.attributesTarget==(key to volume?.volid)) }
    var checkedInput by remember { mutableStateOf<Pair<String,Boolean>?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<StorageConfirmation?>(null) }
    var submittedId by remember { mutableStateOf<String?>(null) }
    var closingUpload by remember { mutableStateOf(false) }
    val submission=state.storageActions.submissions.firstOrNull {it.localId==submittedId}
    if(submittedId!=null) {
        fun closeResult() {if(submission?.action==StorageAction.Upload && submission.phase==PowerPhase.Accepted && !submission.checkable) closingUpload=true else onClose()}
        val resultFocus = remember(submittedId) { FocusRequester() }
        StorageDialog("Storage action",::closeResult,initialFocus=resultFocus,actions={
            TechAction(if(submission?.phase==PowerPhase.Accepted) "Started - close" else "Close",Modifier.focusRequester(resultFocus),onClick=::closeResult)
            if(submission?.phase in listOf(PowerPhase.Rejected,PowerPhase.Unsuccessful)) TechAction("Return to form",onClick={submittedId=null})
        }) { StorageActionStrip(state,workspace,key) }
        if(closingUpload) StorageDialog("Close upload?",{closingUpload=false},actions={
            TechAction("Keep open",onClick={closingUpload=false})
            TechAction("Continue in activity",onClick=onClose)
            TechAction("Stop upload",onClick={workspace.cancelStorageUpload(submittedId!!);onClose()})
        }) {Text("Stopping can leave an Unknown result. Check storage before trying again.",color=Tech.Amber)}
        return
    }
    val current by rememberUpdatedState(state)
    var initialRead by remember { mutableStateOf(false) }
    LaunchedEffect(key, action, volume?.volid, state.storageContent.loading) {
        if(state.storageSelection != key || state.storageContent.value?.filter != null) workspace.selectStorage(key.node,key.storage)
        if(!state.storageContent.loading && state.storageSelection==key && state.storageContent.value?.filter==null && !initialRead) {
            initialRead=true
            if(action == StorageAction.TemplateDownload) workspace.loadStorageCatalogue(key.node,key.storage)
            if(action == StorageAction.EditNotes && volume != null) workspace.readStorageAttributes(key.node,key.storage,volume.volid)
        }
    }
    LaunchedEffect(state.storageActions.attributes, state.storageActions.attributesTarget) {
        if(!notesLoaded && state.storageActions.attributesTarget == (key to volume?.volid) && state.storageActions.attributes != null) {
            notes = state.storageActions.attributes!!.notes.orEmpty(); notesLoaded = true
        }
    }
    // The notes editor appears only after the full-notes read; give it the keyboard then (the frame keeps it until).
    LaunchedEffect(notesLoaded) { if(notesLoaded && action==StorageAction.EditNotes) { withFrameNanos {}; runCatching { firstField.requestFocus() } } }
    DisposableEffect(Unit) { onDispose { ioJob?.cancel() } }
    fun request(): StorageRequest? {
        val checksum = if(algorithm == "none" && hex.isBlank()) null else Checksum(algorithm,hex)
        return when(action) {
            StorageAction.TemplateDownload -> template?.let { StorageRequest.TemplateDownload(it) }
            StorageAction.UrlDownload -> StorageRequest.UrlDownload(url,content,name,checksum,verify,compression.takeIf { content == "iso" && it != "none" })
            StorageAction.Upload -> source?.let { StorageRequest.Upload(it.source,content,name,checksum) }
            StorageAction.RemoveVolume -> volume?.let { StorageRequest.RemoveVolume(it) }
            StorageAction.EditNotes -> if(notesLoaded) volume?.let { StorageRequest.EditNotes(it,notes) } else null
            StorageAction.SetProtection -> volume?.let { StorageRequest.SetProtection(it,!it.protected) }
        }
    }
    fun capture() {
        val r = request() ?: return
        localError = storageRequestError(r)
        if(localError == null) confirmation = workspace.captureStorage(key.node,key.storage,r,profileLabel,browser)
    }
    // Remove/protect have no preliminary mutation confirmation: this is the sole review.
    if(action in listOf(StorageAction.RemoveVolume,StorageAction.SetProtection)) {
        LaunchedEffect(Unit) { capture() }
        if(confirmation == null) StorageDialog(storageActionTitle(action), onClose, actions={ TechAction("Close",onClick=onClose) }) {
            Text(state.storageActions.notice ?: "Checking selected file\u2026",Modifier.identity(),color=Tech.Amber)
        }
    } else StorageDialog(storageActionTitle(action), onClose, width=600.dp,
        initialFocus=firstField.takeIf { action in listOf(StorageAction.TemplateDownload,StorageAction.UrlDownload,StorageAction.EditNotes) },
        context={ Text("${key.node} \u00b7 ${key.storage}",Modifier.identity(),color=Tech.Ink) }, actions={
        TechAction("Cancel",onClick=onClose)
        TechAction("Review", active=true, enabled=request()?.let { storageRequestError(it)==null } == true && !ioBusy && (action != StorageAction.Upload || !browser), onClick=::capture)
    }) {
        if(state.demo) Text("Demo \u00b7 no server requests",color=Tech.Amber)
        when(action) {
            StorageAction.TemplateDownload -> {
                MiniAction("Refresh catalogue",enabled="catalogue:${key.node}" !in state.storageActions.readLoading) { workspace.loadStorageCatalogue(key.node,key.storage) }
                Text("The node's cached catalogue. Host architecture unknown; all architectures are shown.",color=Tech.Muted)
                TechField(search,{search=it},"Search templates",Modifier.focusRequester(firstField).identity())
                val rows=state.storageActions.catalogues[key.node].orEmpty().filter { (it.template+it.section+it.os).contains(search,true) }
                rows.groupBy { it.section.ifBlank { "Templates" } }.forEach { (group, entries) ->
                    SectionLabel(group)
                    entries.forEach { entry ->
                        TechCheck(template==entry,{template=entry},"${entry.template} \u00b7 ${entry.architecture ?: "architecture unknown"}",Modifier.identity())
                        if(state.storageContent.value?.volumes?.any { it.volid == "${key.storage}:vztmpl/${entry.template}" } == true) Text("Already on storage; downloading checks and keeps a matching file.",color=Tech.Amber)
                    }
                }
                if(rows.isEmpty() && state.storageActions.readStates["catalogue:${key.node}"]?.complete==true) Text("No matching catalogue templates.",color=Tech.Muted)
            }
            StorageAction.UrlDownload, StorageAction.Upload -> {
                if(action == StorageAction.UrlDownload) {
                    TechField(url,{url=it; checkedInput=null},"URL \u00b7 fetched by the node",Modifier.focusRequester(firstField).identity(),error=url.takeIf { it.isNotBlank() }?.let(::downloadUrlError))
                    MiniAction("Check URL",enabled=downloadUrlError(url)==null && "metadata" !in state.storageActions.readLoading) {
                        checkedInput=url to verify; workspace.queryStorageUrlMetadata(key.node,key.storage,url,verify)
                    }
                    if(checkedInput == (url to verify) && state.storageActions.readStates["metadata"]?.let { it.complete && it.identity.matchesMetadata(key,url,verify) } == true) state.storageActions.metadata?.let { m ->
                        Text("${m.size ?: "Unknown"} bytes \u00b7 ${m.mimeType ?: "Unknown type"}",color=Tech.Muted)
                        m.fileName?.let { suggested -> MiniAction("Use checked filename",enabled=contentFileNameError(content,suggested)==null) { name=suggested } }
                    }
                    TechCheck(verify,{verify=it;checkedInput=null},"Verify website certificate")
                    Text(if(verify) "The node checks the download website's certificate." else "The node will accept any certificate from this website. Use a checksum. RingMASTER's server TLS is unchanged.",color=if(verify) Tech.Muted else Tech.Amber)
                } else if(browser) Text("Browser mode cannot read files on this computer.",color=Tech.Amber)
                else {
                    MiniAction("Choose file",enabled=!ioBusy) {
                        if(ioBusy) return@MiniAction
                        ioBusy=true // before launch: a second activation in the same frame must not open a second chooser
                        ioJob=scope.launch {
                            try { chooseStorageUpload(browser,picker)?.let { source=it;name=it.basename;hex="";localError=null } }
                            catch(_: CancellationException) { throw CancellationException() }
                            catch(_: Exception) { localError="Cannot read the selected file." }
                            finally {ioBusy=false}
                        }
                    }
                    source?.let { chosen ->
                        Text("${chosen.basename} \u00b7 ${chosen.source.size} bytes",Modifier.identity(),color=Tech.Ink)
                        MiniAction("Compute SHA256",enabled=!ioBusy) {
                            if(ioBusy) return@MiniAction
                            ioBusy=true;ioProgress.value=0
                            ioJob=scope.launch {
                                try { val result=hashStorageUpload(chosen) { bytes -> ioProgress.value=bytes };algorithm="sha256";hex=result }
                                catch(e:CancellationException) { throw e }
                                catch(_:Exception) {localError="The file changed or could not be read. Choose it again."}
                                finally {ioBusy=false}
                            }
                        }
                    }
                    if(ioBusy) { Text("Reading file \u00b7 $ioBytes bytes",color=Tech.Amber);MiniAction("Stop reading") {ioJob?.cancel()} }
                    Text("Upload bytes are sent first to the connected node, then checked and copied to storage. Both need space. Sending all bytes is not task completion.",color=Tech.Muted)
                }
                val types=row?.content.orEmpty().split(',').filter { contentHint==null || it==contentHint }.filter { it in if(action==StorageAction.Upload) listOf("iso","vztmpl") else listOf("iso","vztmpl","import") }
                TechSelect("Content",types,content,{content=it;compression="none"},{it})
                TechField(name,{name=it},"File name",Modifier.identity(),error=name.takeIf { it.isNotEmpty() }?.let {contentFileNameError(content,it)})
                TechSelect("Checksum algorithm",listOf("none","md5","sha1","sha224","sha256","sha384","sha512"),algorithm,{algorithm=it;hex=""},{it})
                if(algorithm!="none") TechField(hex,{hex=it},"Checksum",Modifier.identity(),error=checksumError(Checksum(algorithm,hex)))
                if(action==StorageAction.UrlDownload && content=="iso") TechSelect("Decompress",listOf("none","gz","lzo","zst","bz2"),compression,{compression=it},{it})
                if(state.storageContent.value?.volumes?.any {it.volid=="${key.storage}:$content/$name"}==true) Text(if(action==StorageAction.Upload) "Uploading replaces this existing file permanently." else "File exists. The node refuses replacement unless its checksum matches.",color=Tech.Amber)
                Text("A filtered listing cannot prove a filename is unused.",color=Tech.Muted)
            }
            StorageAction.EditNotes -> {
                if(notesLoaded) TechField(notes,{if(it.length<=65536) notes=it},"Full backup notes \u00b7 ${notes.length}/65536",Modifier.focusRequester(firstField).identity(),singleLine=false)
                else Text("Read full notes before editing. Listing notes contain only the first line.",color=Tech.Muted)
                if("attributes" in state.storageActions.readErrors) MiniAction("Retry full notes") {workspace.readStorageAttributes(key.node,key.storage,volume!!.volid)}
            }
            else -> Unit
        }
        state.storageActions.readLoading.forEach { Text("Reading $it\u2026",color=Tech.Amber) }
        state.storageActions.readErrors.values.forEach { Text(it,color=Tech.Red) }
        (localError ?: state.storageActions.notice)?.let {Text(it,Modifier.identity(),color=Tech.Amber)}
    }
    confirmation?.let { c -> StorageConfirmDialog(c,state.demo,{
        confirmation=null
        val previous=workspace.state.value.storageActions.submissions.map {it.localId}.toSet()
        if(workspace.submitStorage(c)) {
            submittedId=workspace.state.value.storageActions.submissions.firstOrNull {it.localId !in previous && it.key==c.key}?.localId
            onSubmitted(c)
        }
        else localError=current.storageActions.notice ?: "Selection changed. Review again."
    },{ confirmation=null;if(action in listOf(StorageAction.RemoveVolume,StorageAction.SetProtection)) onClose() },
        contextFacts = if(action==StorageAction.SetProtection) listOfNotNull(state.storageView.config.value?.firstOrNull {it.storage==key.storage}?.maxProtected?.let { "Storage limit: $it protected backups per guest. This listing shows ${state.storageContent.value?.volumes.orEmpty().count { it.vmid==volume?.vmid && it.protected }} for this guest; visibility can be limited." }) else emptyList()) }
}
