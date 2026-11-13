@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import local.ringleader.core.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Final-source storage acceptance pictures. Every picture is the settled frame of the real component reached through its
 * real interaction path (form -> Review -> confirmation -> Simulate -> result, create host -> Get media -> sheet -> form).
 *
 * STORAGE_UI_SHOTS=<folder> writes PNGs plus manifest-<look>.tsv; STORAGE_UI_LOOKS=<id,id> limits the looks so a large
 * batch can be split across JVMs. Without STORAGE_UI_SHOTS the dark 960x640 look still runs every case and every check,
 * writing nothing.
 */
class StorageAcceptanceShotsTest {
    private val actions = StorageAction.entries
    private val formActions = listOf(StorageAction.TemplateDownload, StorageAction.UrlDownload, StorageAction.Upload, StorageAction.EditNotes)
    private val stateModes = listOf("sending", "following", "progress", "ok", "failed", "rejected", "unknown", "warnings", "demo", "loading", "error", "empty", "permission")

    private fun expectedCases(look: StorageLook): List<String> = buildList {
        formActions.forEach { add("action/form-$it/${look.id}"); add("action/form-$it-end/${look.id}") }
        actions.forEach { add("action/confirm-$it/${look.id}"); add("action/result-$it/${look.id}") }
        listOf("Vm", "Container").forEach { add("media/sheet-$it/${look.id}"); add("media/sheet-form-$it/${look.id}") }
        stateModes.forEach { add("state/$it/${look.id}") }
    }

    @Test fun storageAcceptancePictures() = runSceneTest {
        val out = System.getenv("STORAGE_UI_SHOTS")?.let { File(it).absoluteFile }
        val filter = System.getenv("STORAGE_UI_LOOKS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val looks = storageLooks().filter { look -> if (out == null) look.id == "dark-960x640" else filter == null || look.id in filter }
        check(looks.isNotEmpty()) { "No storage looks match $filter" }
        val root = File(System.getProperty("user.dir")).let { if (File(it, "settings.gradle.kts").isFile) it else it.parentFile }
        val source = matrixSourceFingerprint(root)
        val scratch = Files.createTempDirectory("storage-ui-cases").toFile()
        val fixture = Files.createTempFile("storage-ui-fixture", ".iso").also { Files.write(it, ByteArray(1024 * 1024) { n -> (n % 251).toByte() }) }
        try {
            val crops = out?.let { it.mkdirs(); loadStorageCrops(it, source) } ?: mutableMapOf()
            for (look in looks) {
                val dir = out ?: File(scratch, look.id)
                check(out == null || !File(dir, "manifest-${look.id}.tsv").exists()) { "${look.id} already has a manifest in $dir" }
                val manifest = StorageManifest(dir, source, crops)
                actions.forEach { actionJourney(look, it, fixture, manifest, dir) }
                listOf(NewGuestKind.Vm, NewGuestKind.Container).forEach { mediaJourney(look, it, manifest, dir) }
                stateModes.forEach { stateCase(look, it, manifest, dir) }
                manifest.write(expectedCases(look), "manifest-${look.id}.tsv")
                println("storage-ui ${look.id}: ${manifest.ids.size} cases, ${manifest.retained.size} retained PNGs")
            }
        } finally { Appearance.preview(false); Files.deleteIfExists(fixture); scratch.deleteRecursively() }
    }

    private fun demoWorkspace(scope: CoroutineScope): Workspace = Workspace(scope).also { it.exploreDemo() }

    private suspend fun CoroutineScope.actionJourney(look: StorageLook, action: StorageAction, fixture: java.nio.file.Path, manifest: StorageManifest, dir: File) {
        val scope = CoroutineScope(coroutineContext + SupervisorJob()); val w = demoWorkspace(scope)
        val backupAction = action in listOf(StorageAction.EditNotes, StorageAction.SetProtection)
        val row = w.state.value.overview!!.storage.first { if (backupAction) "backup" in it.content.orEmpty() else it.id == "local" }
        w.selectStorage(row.node, row.id); yield()
        val volume = w.state.value.storageContent.value!!.volumes.firstOrNull { if (backupAction) it.content == "backup" && !it.protected else it.content == "iso" }
            .takeIf { action in listOf(StorageAction.RemoveVolume, StorageAction.EditNotes, StorageAction.SetProtection) }
        val upload = DesktopUpload(UploadSource.capture(fixture), "fixture-1mib.iso", fixture)
        var open by mutableStateOf(true)
        val scene = storageScene(look, coroutineContext) {
            val live by w.state.collectAsState()
            if (open) StorageActionDialog(live, w, live.storageSelection!!, action, volume, profileLabel = "Synthetic profile",
                initialUpload = if (action == StorageAction.Upload) upload else null, onClose = { open = false })
        }
        val d = StorageSceneDriver(scene, look)
        val header = "${row.node} · ${row.id}"
        fun record(state: String, contract: StorageCaseContract, frame: SettledFrame) =
            manifest.record("action/$state/${look.id}", "action", state, look, contract, d.verify("action/$state/${look.id}", contract, frame))
        try {
            d.settle(dir, "$action-open")
            if (action in formActions) {
                when (action) {
                    StorageAction.UrlDownload -> {
                        val fields = d.nodes().filter { it.config.getOrNull(SemanticsActions.SetText) != null }
                        fields[0].config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("https://mirror.example.test/images/fixture.iso?sig=canary-url-query"))
                        fields[1].config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("fixture.iso"))
                    }
                    StorageAction.TemplateDownload -> d.click("sample_1_amd64.tar.zst · architecture unknown")
                    else -> Unit
                }
                val formContract = StorageCaseContract(listOf(title(storageActionTitle(action)), footer("Cancel"), footer("Review"), body(header, true), body("Demo · no server requests", true)) + when (action) {
                    StorageAction.UrlDownload -> listOf(body("URL · fetched by the node"), body("Verify website certificate", true), body("A filtered listing cannot prove a filename is unused.", true))
                    StorageAction.Upload -> listOf(body("fixture-1mib.iso · 1048576 bytes", true), body("Sending all bytes is not task completion"))
                    StorageAction.TemplateDownload -> listOf(body("Host architecture unknown"), body("sample_1_amd64.tar.zst · architecture unknown", true))
                    else -> listOf(body("Full backup notes"))
                }, forbid = listOf("Reading attributes", "Storage read failed"))
                record("form-$action", formContract, d.settle(dir, "$action-form"))
                scrollToEnd(d)
                record("form-$action-end", formContract, d.settle(dir, "$action-form-end"))
                d.click("Review")
            }
            val facts = when (action) {
                StorageAction.UrlDownload -> listOf(body("fixture.iso"))
                StorageAction.Upload -> listOf(body("Bytes: 1048576", true), body("fixture-1mib.iso"))
                StorageAction.TemplateDownload -> listOf(body("sample_1_amd64.tar.zst"))
                StorageAction.RemoveVolume -> listOf(body(volume!!.volid), body("This cannot be undone."))
                StorageAction.EditNotes -> listOf(body(volume!!.volid))
                StorageAction.SetProtection -> listOf(body("Protected backups cannot be removed"))
            }
            record("confirm-$action", StorageCaseContract(listOf(title("Simulate storage action"), footer("Cancel"), footer("Simulate"), body("Synthetic profile · demo", true)) + facts),
                d.settle(dir, "$action-confirm"))
            check(w.state.value.storageActions.submissions.isEmpty()) { "$action: reviewing sent something" }
            d.click("Simulate")
            record("result-$action", StorageCaseContract(listOf(title("Storage action"), footer("Close"), body("Demo · simulated", true), body("${storageActionTitle(action)} · ${row.node} · ${row.id}")),
                forbid = listOf("Terminal OK", "Unknown", "awaiting server task completion")), d.settle(dir, "$action-result"))
            check(w.state.value.storageActions.submissions.size == 1) { "$action: expected exactly one simulated submission" }
        } finally { scene.close(); scope.cancel() }
    }

    private suspend fun scrollToEnd(d: StorageSceneDriver) {
        d.nodes().filter { n -> n.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { it.maxValue() > 0f } == true }
            .forEach { n -> n.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, 100_000f) }
        d.frames(2)
    }

    private suspend fun CoroutineScope.mediaJourney(look: StorageLook, kind: NewGuestKind, manifest: StorageManifest, dir: File) {
        val scope = CoroutineScope(coroutineContext + SupervisorJob()); val w = demoWorkspace(scope)
        val node = w.state.value.overview!!.storage.first { it.id == "local" }.node
        check(w.openCreateSession(kind, node))
        val scene = storageScene(look, coroutineContext) {
            val live by w.state.collectAsState()
            CreateGuestHost(live, w, "Synthetic profile", {}, onViewLog = {})
        }
        val d = StorageSceneDriver(scene, look)
        val vm = kind == NewGuestKind.Vm
        try {
            d.settle(dir, "media-$kind-host")
            val step = if (vm) "2  OS" else "2  Template"
            d.click(if (d.matches(step).isNotEmpty()) step else "2", last = false); d.settle(dir, "media-$kind-step") // the narrow rail shows numbers only
            d.click(if (vm) "Get ISO image" else "Get template")
            val sheetTitle = if (vm) "Get ISO image" else "Get container template"
            val sheet = StorageCaseContract(listOf(title(sheetTitle), footer("Close"), body("Your create form stays open"), body("Target storage"),
                body(if (vm) "Open URL" else "Open Catalogue", true), body("Only Terminal OK and the exact file in refreshed storage can select it")))
            manifest.record("media/sheet-$kind/${look.id}", "media", "sheet-$kind", look, sheet, d.verify("media/sheet-$kind", sheet, d.settle(dir, "media-$kind-sheet")))
            d.click(if (vm) "Open URL" else "Open Catalogue")
            val formTitle = if (vm) "Download from URL" else "Template catalogue"
            val form = StorageCaseContract(listOf(title(formTitle), footer("Cancel"), footer("Review"), body("$node · ")))
            manifest.record("media/sheet-form-$kind/${look.id}", "media", "sheet-form-$kind", look, form, d.verify("media/sheet-form-$kind", form, d.settle(dir, "media-$kind-form")))
            check(w.state.value.storageActions.submissions.isEmpty())
        } finally { scene.close(); w.closeCreateSession(); scope.cancel() }
    }

    private suspend fun CoroutineScope.stateCase(look: StorageLook, mode: String, manifest: StorageManifest, dir: File) {
        val scope = CoroutineScope(coroutineContext + SupervisorJob()); val w = demoWorkspace(scope)
        val backup = mode in listOf("loading", "error")
        val row = w.state.value.overview!!.storage.first { if (backup) "backup" in it.content.orEmpty() else it.id == "local" }
        w.selectStorage(row.node, row.id); yield()
        val base = w.state.value; val key = base.storageSelection!!
        val total = 1024L * 1024
        val phase = when (mode) { "ok" -> PowerPhase.Succeeded; "failed", "warnings" -> PowerPhase.Unsuccessful; "rejected" -> PowerPhase.Rejected; "unknown" -> PowerPhase.Unknown; "demo" -> PowerPhase.Simulated; else -> PowerPhase.Accepted }
        val detail = when (mode) { "failed" -> "Stopped with an error. Check the task log."; "rejected" -> "The server refused the request (HTTP 403)."; "unknown" -> "The reply was lost after sending."; else -> "Fixed safe storage detail" }
        val sub = StorageSubmission("local-fixture", key, StorageAction.Upload, phase, detail, simulated = mode == "demo", checkable = mode in listOf("following", "ok", "failed", "warnings"),
            warningCount = if (mode == "warnings") 3 else null, bytesSent = when (mode) { "progress" -> total / 2; "sending" -> 0; else -> total }, totalBytes = total,
            evidence = if (mode == "ok") StorageSuccessEvidence.TerminalOk else null, subject = "fixture-1mib.iso")
        val view = base.copy(storageActions = base.storageActions.copy(submissions = listOf(sub)))
        val backupVolume = base.storageContent.value!!.volumes.firstOrNull { it.content == "backup" }
        val frozen = when (mode) {
            "loading" -> base.copy(storageActions = base.storageActions.copy(readLoading = setOf("attributes"), attributes = null, attributesTarget = null))
            "error" -> base.copy(storageActions = base.storageActions.copy(readErrors = mapOf("attributes" to "Storage read failed. No action was sent."), attributes = null, attributesTarget = null))
            else -> base
        }
        val denied = base.copy(demo = false, permissions = ReadSection(value = PermissionsView(PermissionDump(emptyMap()), PermissionIdentity.Token)))
        val scene = storageScene(look, coroutineContext) {
            when (mode) {
                "loading", "error" -> StorageActionDialog(frozen, w, key, StorageAction.EditNotes, backupVolume, profileLabel = "Synthetic profile", onClose = {})
                "empty" -> { val live by w.state.collectAsState(); StorageActionDialog(live, w, live.storageSelection!!, StorageAction.TemplateDownload, profileLabel = "Synthetic profile", onClose = {}) }
                "permission" -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Text("Selected storage · ${key.storage}", color = Tech.Ink)
                    StorageFileActions(denied, w, key)
                }
                else -> StorageDialog("Storage action", {}, actions = { TechAction("Close", onClick = {}) }) { StorageActionStrip(view, w, key) }
            }
        }
        val d = StorageSceneDriver(scene, look)
        try {
            d.settle(dir, "state-$mode-open")
            val gate = storageActionGate(StorageAction.TemplateDownload, denied.permissions.value!!.dump, key)!!
            if (mode == "empty") {
                d.nodes().first { it.config.getOrNull(SemanticsActions.SetText) != null }.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("zz-no-such-template"))
            }
            if (mode == "permission") {
                val at = d.clickable(storageActionTitle(StorageAction.TemplateDownload)).boundsInWindow.center
                scene.sendPointerEvent(PointerEventType.Enter, at); scene.sendPointerEvent(PointerEventType.Move, at)
                d.frames(2); delay(900); d.frames(4) // TooltipArea waits in real time before it shows; a delay is not an invalidation.
            }
            val strip = listOf(title("Storage action"), footer("Close"), body("Upload file · ${key.node} · ${key.storage} · fixture-1mib.iso", true))
            val contract = when (mode) {
                "sending" -> StorageCaseContract(strip + listOf(body("Sending", true), body("0 / 1048576 file bytes sent", true), StorageExpect("Stop upload", control = true)), listOf("Terminal OK", "awaiting server task completion"))
                "following" -> StorageCaseContract(strip + listOf(body("Following server task", true), StorageExpect("View task log", control = true), body("1048576 / 1048576 file bytes sent · awaiting server task completion", true)), listOf("Terminal OK", "Stop upload"))
                "progress" -> StorageCaseContract(strip + listOf(body("Sending", true), body("524288 / 1048576 file bytes sent", true)), listOf("Terminal OK", "awaiting server task completion"))
                "ok" -> StorageCaseContract(strip + listOf(body("Terminal OK", true), body("1048576 / 1048576 file bytes sent", true)), listOf("Unknown", "awaiting", "Stop upload"))
                "failed" -> StorageCaseContract(strip + listOf(body("Failed", true), body(detail, true)), listOf("Terminal OK", "awaiting"))
                "rejected" -> StorageCaseContract(strip + listOf(body("Rejected", true), body(detail, true)), listOf("Terminal OK"))
                "unknown" -> StorageCaseContract(strip + listOf(body("Unknown · check storage before trying again", true), StorageExpect("I checked storage", control = true)), listOf("Terminal OK", "Stop upload", "Return to form"))
                "warnings" -> StorageCaseContract(strip + listOf(body("3 warnings · check task log", true)), listOf("Terminal OK"))
                "demo" -> StorageCaseContract(strip + listOf(body("Demo · simulated", true)), listOf("Terminal OK", "file bytes sent"))
                "loading" -> StorageCaseContract(listOf(title(storageActionTitle(StorageAction.EditNotes)), footer("Cancel"), footer("Review"), body("Reading attributes…", true), body("Read full notes before editing")), listOf("Full backup notes"))
                "error" -> StorageCaseContract(listOf(title(storageActionTitle(StorageAction.EditNotes)), footer("Cancel"), footer("Review"), body("Storage read failed. No action was sent.", true), StorageExpect("Retry full notes", control = true)), listOf("Full backup notes"))
                "empty" -> StorageCaseContract(listOf(title(storageActionTitle(StorageAction.TemplateDownload)), footer("Cancel"), footer("Review"), body("No matching catalogue templates.", true)), listOf("sample_1_amd64.tar.zst"))
                else -> StorageCaseContract(listOf(StorageExpect(gate, chrome = true), body(storageActionTitle(StorageAction.TemplateDownload), true)))
            }
            val frame = d.settle(dir, "state-$mode")
            manifest.record("state/$mode/${look.id}", "state", mode, look, contract, d.verify("state/$mode", contract, frame))
            if (mode == "permission") check(d.clickable(storageActionTitle(StorageAction.TemplateDownload)).config.getOrNull(SemanticsProperties.Disabled) != null) { "Refused action must be disabled" }
            check(w.state.value.storageActions.submissions.isEmpty())
        } finally { scene.close(); scope.cancel() }
    }
}
