@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.internal.undo.TextFieldEditUndoBehavior
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.yield
import local.ringleader.core.*
import java.nio.file.Files
import kotlin.test.*

class CreateIntegrationTest {
    private fun holders() = CreateSecrets(SecretChars.copyOf(java.nio.CharBuffer.wrap(charArrayOf('a','b','c'))))
    @Test fun pendingReplacementCancelAndOneShotConfirmWipe() {
        val pending = PendingCreate()
        val c = CreateConfirmation(1, DEMO_REMOTE_KEY, "Demo", "Demo", CreateRequest(NewGuestKind.Container, "demo-node-a", 140, "fixture", listOf(CreateParam("vmid",CreateValue.Int(140)))), SecretState(true))
        val first = holders(); pending.replace(c, first)
        val second = holders(); pending.replace(c, second); assertTrue(first.password!!.closed)
        pending.close(); assertTrue(second.password!!.closed); assertNull(pending.confirmation)
        val third = holders(); pending.replace(c, third)
        var sends = 0
        assertTrue(pending.confirm { _, transferred -> sends++; transferred.close(); true })
        assertFalse(pending.confirm { _, transferred -> sends++; transferred.close(); true })
        assertEquals(1, sends); assertTrue(third.password!!.closed)
        val refused = holders(); pending.replace(null, refused); assertTrue(refused.password!!.closed)
    }
    @Test fun wipeRemovesUserUndoAndRedoForBothEditors() {
        for (redo in listOf(false, true)) {
            val state = TextFieldState()
            state.editAsUser(inputTransformation = null, undoBehavior = TextFieldEditUndoBehavior.NeverMerge) { append('x'); append('y') }
            assertTrue(state.undoState.canUndo)
            if (redo) { state.undoState.undo(); assertTrue(state.undoState.canRedo) }
            wipeSecretInput(state)
            assertFalse(state.undoState.canUndo); assertFalse(state.undoState.canRedo)
            state.undoState.undo(); state.undoState.redo(); assertTrue(state.text.isEmpty())
        }
    }
    @Test fun multibyteKeyCapCountsBytes() {
        val file = Files.createTempFile("invented-key-byte-cap", ".pub")
        try {
            Files.write(file, byteArrayOf(0xc3.toByte(),0xa9.toByte(),0xc3.toByte(),0xa9.toByte()))
            assertFailsWith<java.io.IOException> { readSshKeyChars(file, maxBytes = 3) }
            val chars = readSshKeyChars(file, maxBytes = 4)
            try { assertEquals(2, chars.size) } finally { chars.fill('\u0000') }
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun transferredHoldersLiveUntilTheWorkspaceOwnsTheirSend() {
        val pending = PendingCreate(); val secrets = holders()
        val request = CreateRequest(NewGuestKind.Container,"demo-node-a",140,"fixture",listOf(CreateParam("vmid",CreateValue.Int(140))))
        pending.replace(CreateConfirmation(1,DEMO_REMOTE_KEY,"Demo","Demo",request,secrets.state),secrets)
        var sending: CreateSecrets? = null
        assertTrue(pending.confirm { _, transferred -> sending = transferred; true })
        pending.close(); assertFalse(secrets.password!!.closed)
        sending!!.close(); assertTrue(secrets.password!!.closed)
    }
    @Test fun editedFieldsDropOnlyTheirOwnServerErrorsAndNodeChoicesAreInvalidated() {
        val old = ctSceneDraft
        val errors = mapOf(CreateFields.NAME to "name refused",CreateFields.VMID to "ID refused")
        val kept = retainCtServerErrors(old,old.copy(hostname="new-name"),errors)
        assertEquals(mapOf(CreateFields.VMID to "ID refused"),kept)
        val next = invalidateCtNode(old,"new-node")
        assertEquals(old.hostname,next.hostname); assertEquals(old.memoryMib,next.memoryMib)
        assertEquals("",next.template); assertEquals("",next.templateStorage); assertEquals("",next.nic.bridge)
        assertTrue(next.mounts.all { (it.source as MountSource.New).storage.isEmpty() })
    }
    @Test fun containerAdvancedUsesSharedMemoryAndTemporaryPreferences() = runSceneTest {
        val dir = Files.createTempDirectory("ct-advanced")
        try {
            val file = dir.resolve("preferences")
            for (prefs in listOf(CreateWizardPreferences.memory(),CreateWizardPreferences.stored(MotionPreferences(file)))) {
                val session = demoCreateSession(NewGuestKind.Container,"docs-node",1,java.time.Instant.EPOCH)
                val scene = ImageComposeScene(1200,800,coroutineContext=coroutineContext) {
                    MaterialTheme(colorScheme=ringColors) { CreateCtWizard(session,ctSceneContext,ctSceneDraft,{_,s->s.close()},{},preferences=prefs) }
                }
                try {
                    CreateScene(scene).frames(); assertFalse(prefs.advanced)
                    CreateScene(scene).click("Show advanced"); assertTrue(prefs.advanced)
                } finally { scene.close() }
            }
            assertTrue(CreateWizardPreferences.stored(MotionPreferences(file)).advanced)
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun mediaRequestKeepsTheChosenStorageForBothWizards() = runSceneTest {
        for (kind in NewGuestKind.entries) {
            val session = demoCreateSession(kind,"docs-node",1,java.time.Instant.EPOCH).copy(volumes=emptyMap())
            val context = ctSceneContext.copy(storages=ctSceneContext.storages.map { it.copy(content=it.content+"iso"+"images") })
            var destination: Triple<String,String,String>? = null
            val navigate: (String,String,String)->Unit = { n,s,k -> destination=Triple(n,s,k) }
            val scene=ImageComposeScene(1600,1000,coroutineContext=coroutineContext) {
                MaterialTheme(colorScheme=ringColors) {
                    if(kind==NewGuestKind.Container) CreateCtWizard(session,context,ctSceneDraft.copy(templateStorage="archive",template=""),{_,s->s.close()},{},initialStep=CreateFields.TEMPLATE,onGetMedia=navigate,preferences=CreateWizardPreferences.memory())
                    else CreateVmWizard(session,context,listOf("docs-node"),{},{},{},{},{},onGetMedia=navigate,initialStep=CreateFields.OS,
                        defaults={ n,c -> defaultVmDraft(n,c).copy(install=CdDrive(media=CdMedia.Iso("archive"))) },preferences=CreateWizardPreferences.memory())
                }
            }
            try {
                CreateScene(scene).frames()
                click(scene,if(kind==NewGuestKind.Container) "GET TEMPLATE" else "GET ISO IMAGE",last=true)
                assertEquals(Triple("docs-node","archive",if(kind==NewGuestKind.Container) "vztmpl" else "iso"),destination)
            } finally { scene.close() }
        }
    }

    @Test fun inPlaceMediaPreservesTheCreateSessionDraftAndContainerKeyBuffer() = runSceneTest {
        for(kind in NewGuestKind.entries) {
            val workspace=Workspace(this,createGateway=FakeCreateGuestGateway())
            workspace.exploreDemo();assertTrue(workspace.openCreateSession(kind,"demo-node-a"))
            val session=workspace.state.value.creation!!
            val scene=ImageComposeScene(1600,1000,coroutineContext=coroutineContext) {
                val state by workspace.state.collectAsState()
                MaterialTheme(colorScheme=ringColors) {CreateGuestHost(state,workspace,"Synthetic profile",{},onViewLog={})}
            }
            val h=CreateScene(scene)
            try {
                h.frames();setText(scene,if(kind==NewGuestKind.Vm) "Name" else "Hostname","retained-fixture")
                if(kind==NewGuestKind.Container) setText(scene,"SSH public keys",inventedEd25519Key())
                h.frames();click(scene,if(kind==NewGuestKind.Vm) "2  OS" else "2  Template");h.frames()
                click(scene,if(kind==NewGuestKind.Vm) "GET ISO IMAGE" else "GET TEMPLATE",last=true);h.frames()
                assertEquals(session.epoch,workspace.state.value.creation!!.epoch)
                assertEquals(session.generation,workspace.state.value.creation!!.generation)
                assertTrue(allNodes(scene).any {it.config.getOrNull(SemanticsProperties.Text)?.any {t->t.text==if(kind==NewGuestKind.Vm) "Get ISO image" else "Get container template"}==true})
                click(scene,"CLOSE",last=true);h.frames();click(scene,"1  General");h.frames()
                assertTrue(allNodes(scene).any {it.config.getOrNull(SemanticsProperties.EditableText)?.text=="retained-fixture"})
                if(kind==NewGuestKind.Container) assertTrue(allNodes(scene).any {it.config.getOrNull(SemanticsProperties.EditableText)?.text==inventedEd25519Key()})
                assertTrue(workspace.state.value.submissions.isEmpty());assertTrue(workspace.state.value.storageActions.submissions.isEmpty())
            }finally{scene.close();workspace.close()}
        }
    }

    @Test fun contextRefreshPreservesEditedContainerDraft() = runSceneTest {
        var context by mutableStateOf(ctSceneContext)
        val session = demoCreateSession(NewGuestKind.Container,"docs-node",1,java.time.Instant.EPOCH)
        var seen: CtDraft? = null
        val scene=ImageComposeScene(1600,1000,coroutineContext=coroutineContext) {
            MaterialTheme(colorScheme=ringColors) { CreateCtWizard(session,context,defaultCtDraft(session.node,context),{_,s->s.close()},{},onDraftChange={seen=it},preferences=CreateWizardPreferences.memory()) }
        }
        try {
            CreateScene(scene).frames(); setText(scene,"Hostname","edited-hostname"); CreateScene(scene).frames()
            assertEquals("edited-hostname",seen!!.hostname)
            context=context.copy(storages=context.storages.reversed(),nodeMemoryUsedBytes=0)
            CreateScene(scene).frames()
            assertTrue(allNodes(scene).any { it.config.getOrNull(SemanticsProperties.EditableText)?.text=="edited-hostname" })
        } finally { scene.close() }
    }

    @Test fun existingVolumePickerReadsOneSelectedStorageAndAcceptsOnlyReturnedContent() = runSceneTest {
        for (content in listOf("images","rootdir")) {
            var session by mutableStateOf(demoCreateSession(NewGuestKind.Container,"docs-node",1,java.time.Instant.EPOCH).copy(volumes=emptyMap()))
            var volume by mutableStateOf("archive:")
            val reads=mutableListOf<CreateRead>()
            val context=ctSceneContext.copy(storages=ctSceneContext.storages.map { it.copy(content=it.content+content) })
            val scene=ImageComposeScene(1200,800,coroutineContext=coroutineContext) {
                MaterialTheme(colorScheme=ringColors) { androidx.compose.foundation.layout.Column {
                    ExistingCreateVolumePicker(session,context,content,volume,{reads+=it},{volume=it})
                } }
            }
            val h=CreateScene(scene)
            try {
                h.frames(); assertEquals(listOf<CreateRead>(CreateRead.Volumes("archive",content)),reads)
                val fixture=demoCreateVolumes.getValue("local" to "iso").first().copy(volid="archive:existing-volume",content=content)
                session=session.copy(volumes=mapOf(("archive" to content) to ReadSection.loaded(listOf(fixture,fixture.copy(volid="other:wrong-storage")),java.time.Instant.EPOCH)))
                h.frames();h.click("Choose\u2026")
                assertFalse(h.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { t->t.text=="other:wrong-storage" }==true })
                h.click("archive:existing-volume"); assertEquals("archive:existing-volume",volume)
                h.frames(); assertEquals(1,reads.size)
            } finally { scene.close() }
        }
    }
    @Test fun importTargetNormalizesFormatWithoutChangingItsSource() {
        val disk=VmDisk(source=DiskSource.Import("archive:import/appliance.qcow2","old"),format="qcow2")
        val target=StorageChoice("docs-node","raw-target","lvmthin",setOf("images"),false,null,null,"available")
        val updated=applyImportStorage(disk,target)
        assertEquals(DiskSource.Import("archive:import/appliance.qcow2","raw-target"),updated.source)
        assertEquals("raw",updated.format)
    }

    @Test fun compositionDisposalClearsRealEditorOwnerIncludingUndo() = runSceneTest {
        var visible by mutableStateOf(true)
        var captured: CreateSecretEditors? = null
        val scene=ImageComposeScene(960,640,coroutineContext=coroutineContext) {
            if(visible) captured=rememberCreateSecretEditors()
        }
        val h=CreateScene(scene)
        try {
            h.frames()
            val editors=assertNotNull(captured)
            for(state in listOf(editors.password,editors.confirmation,editors.sshKeys)) {
                state.editAsUser(inputTransformation=null,undoBehavior=TextFieldEditUndoBehavior.NeverMerge) { append('x') }
                assertTrue(state.undoState.canUndo)
            }
            visible=false;h.frames()
            for(state in listOf(editors.password,editors.confirmation,editors.sshKeys)) {
                assertTrue(state.text.isEmpty());assertFalse(state.undoState.canUndo);assertFalse(state.undoState.canRedo)
            }
        } finally { scene.close() }
    }

    @Test fun oversizedUserEditsAreRejectedBeforeHolderCopyWithoutTruncation() {
        for ((multiLine,count) in listOf(false to CREATE_PASSWORD_MAX_CHARS+1,true to CREATE_SSH_KEYS_MAX_CHARS+1,true to CREATE_SSH_KEY_LINE_MAX_CHARS+1)) {
            val state=TextFieldState(); state.edit { append('v') }
            var error: String?=null
            state.editAsUser(inputTransformation=secretInputTransformation(multiLine) { error=it },undoBehavior=TextFieldEditUndoBehavior.NeverMerge) {
                replace(0,length,"")
                repeat(count) { append('x'); if(count>CREATE_SSH_KEYS_MAX_CHARS && it%100==0) append('\n') }
            }
            assertEquals(1,state.text.length);assertEquals('v',state.text[0]);assertNotNull(error)
            wipeSecretInput(state)
        }
    }
    @Test fun oversizedKeyFileAndAppendKeepThePreviousEditor() {
        val file=Files.createTempFile("invented-oversized-key", ".pub")
        val state=TextFieldState();state.edit { append('v') }
        val oversized=CharArray(CREATE_SSH_KEY_LINE_MAX_CHARS+1) { 'x' }
        try {
            Files.write(file,ByteArray(CREATE_SSH_KEYS_MAX_FILE_BYTES+1) { 65 })
            assertFailsWith<java.io.IOException> { readSshKeyChars(file) }
            assertFailsWith<java.io.IOException> { appendSshKeyChars(state,oversized) }
            assertEquals(1,state.text.length);assertEquals('v',state.text[0])
        } finally { oversized.fill('\u0000');wipeSecretInput(state);Files.deleteIfExists(file) }
    }
    @Test fun incompletePreviewAndFreezeFailuresStayInTheForm() {
        val preview=safeCreatePreview { throw IllegalArgumentException("must never echo an invalid value") }
        assertTrue(preview.lines.isEmpty());assertNotNull(preview.problem)
        assertFalse(preview.problem!!.contains("echo"))
        assertIs<FreezeResult.Invalid>(safeFreezeCreate { throw IllegalArgumentException("invalid volume") })
    }

    @Test fun unknownCheckIsDisabledWhileItsReadIsInFlight() = runSceneTest {
        var result by mutableStateOf(CreateResult.Unknown(NewGuestKind.Container,"docs-node",140,"local-unknown","Reply lost."))
        var checks=0
        val scene=ImageComposeScene(1200,800,coroutineContext=coroutineContext) {
            MaterialTheme(colorScheme=ringColors) { androidx.compose.foundation.layout.Column {
                CreateResultView(result) { if(it==ResultAction.CheckId) checks++ }
            } }
        }
        val h=CreateScene(scene)
        try {
            h.frames();h.click("Check ID");assertEquals(1,checks)
            result=result.copy(checking=true);h.frames()
            h.click("Checking ID...");assertEquals(1,checks)
        } finally { scene.close() }
    }
    @Test fun invalidIntermediateDraftsNeverCrashTheirParameterPreview() = runSceneTest {
        for(kind in NewGuestKind.entries) {
            val workspace=Workspace(this,createGateway=FakeCreateGuestGateway())
            workspace.exploreDemo();workspace.openCreateSession(kind,"demo-node-a")
            val scene=ImageComposeScene(1600,1000,coroutineContext=coroutineContext) {
                val state by workspace.state.collectAsState()
                MaterialTheme(colorScheme=ringColors) { CreateGuestHost(state,workspace,"Demo",{},onViewLog={}) }
            }
            val h=CreateScene(scene)
            try {
                h.frames()
                setText(scene,if(kind==NewGuestKind.Vm) "VM ID" else "CT ID", "");h.frames()
                setText(scene,if(kind==NewGuestKind.Vm) "Name" else "Hostname", "invalid,name");h.frames()
                assertNull(workspace.state.value.creation!!.result)
                assertTrue(workspace.state.value.submissions.isEmpty())
            } finally { scene.close();workspace.close() }
        }
    }
    @Test fun vmAndContainerUseRealDemoCoreThroughConfirmation() = runSceneTest {
        for (kind in NewGuestKind.entries) {
            val gateway = FakeCreateGuestGateway()
            val workspace = Workspace(this, createGateway = gateway)
            workspace.exploreDemo(); assertTrue(workspace.openCreateSession(kind, "demo-node-a"))
            val scene = ImageComposeScene(1600,1000,coroutineContext = coroutineContext) {
                val state by workspace.state.collectAsState()
                MaterialTheme(colorScheme = ringColors) { CreateGuestHost(state,workspace,"Demo",{},onViewLog={}) }
            }
            suspend fun frame() { repeat(5) { scene.render((System.nanoTime())).close(); yield() } }
            try {
                frame()
                if (kind == NewGuestKind.Vm) {
                    setText(scene,"Name","fixture-vm")
                    click(scene,"2  OS"); frame(); click(scene,"NONE"); frame()
                } else {
                    setText(scene,"Hostname","fixture-ct"); frame()
                    setText(scene,"SSH public keys",inventedEd25519Key()); frame()
                    click(scene,"2  Template"); frame()
                    // Real picker: select the sample template, keeping the production defaults and validators.
                    click(scene,"Choose\u2026"); frame()
                    click(scene,"debian-13-standard",contains=true); frame()
                }
                click(scene, if(kind == NewGuestKind.Vm) "7  Network" else "6  Network"); frame()
                click(scene,"Choose\u2026"); frame()
                click(scene,"vmbr0",contains=true); frame()
                click(scene, if(kind == NewGuestKind.Vm) "CREATE VM 140" else "SIMULATE CREATE",contains=true)
                frame()
                assertNotNull(allNodes(scene).firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.contains("Create ${kind.guestKind} 140") } == true }, allNodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString(" | ") { it.text })
                // Dialog confirm button has the exact same label in both demo flows.
                click(scene,"SIMULATE CREATE",last=true); frame()
                assertIs<CreateResult.Simulated>(workspace.state.value.creation!!.result)
                assertEquals(1,workspace.state.value.submissions.size)
                assertTrue(gateway.calls.isEmpty()); assertTrue(gateway.submits.isEmpty())
            } finally { scene.close(); workspace.close() }
        }
    }
}

internal fun allNodes(scene: ImageComposeScene): List<SemanticsNode> = buildList {
    fun visit(n: SemanticsNode) { add(n); n.children.forEach(::visit) }
    scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
}
private fun click(scene: ImageComposeScene, text: String, contains: Boolean = false, last: Boolean = false) {
    val matches = allNodes(scene).filter { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> if(contains) t.text.contains(text,ignoreCase=true) else t.text.equals(text,ignoreCase=true) } == true }
    var node: SemanticsNode? = if(last) matches.lastOrNull() else matches.firstOrNull()
    assertNotNull(node,"Missing action $text")
    while(node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
    assertNotNull(node?.config?.getOrNull(SemanticsActions.OnClick)?.action,"No click for $text").invoke()
}
private fun setText(scene: ImageComposeScene, label: String, value: String) {
    val nodes = allNodes(scene)
    val text = assertNotNull(nodes.firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true } ?: nodes.firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.equals(label,ignoreCase=true) } == true },"Missing field $label")
    text.config.getOrNull(SemanticsActions.SetText)?.action?.let { it(AnnotatedString(value)); return }
    val labelBounds = text.boundsInWindow
    val target = nodes.filter { it.config.getOrNull(SemanticsActions.SetText) != null }
        .filter { it.boundsInWindow.top >= labelBounds.bottom - 2 && it.boundsInWindow.left <= labelBounds.left + 10 && it.boundsInWindow.right > labelBounds.left }
        .minByOrNull { it.boundsInWindow.top - labelBounds.bottom }
    assertNotNull(target?.config?.getOrNull(SemanticsActions.SetText)?.action,"No editor for $label").invoke(AnnotatedString(value))
}

private fun inventedEd25519Key(): String {
    val type="ssh-ed25519".toByteArray()
    val bytes=java.nio.ByteBuffer.allocate(4+type.size+4+32).putInt(type.size).put(type).putInt(32).put(ByteArray(32) { 1 }).array()
    return "ssh-ed25519 " + java.util.Base64.getEncoder().encodeToString(bytes) + " invented-public-key"
}
