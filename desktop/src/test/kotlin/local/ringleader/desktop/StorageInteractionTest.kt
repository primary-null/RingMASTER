@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.*
import local.ringleader.core.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.ActiveEvent
import java.awt.Component
import java.awt.FileDialog
import java.awt.Toolkit
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.test.*

private val LOOK = storageLooks().first { it.id == "dark-960x640" }
private const val ORIGIN = "https://192.0.2.10:8006"

/** Runs AWT work queued behind the scene test (which itself occupies the event thread), as the app's event loop would. */
internal val awtFailures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
internal fun pumpAwt() {
    val queue = Toolkit.getDefaultToolkit().systemEventQueue
    repeat(500) {
        queue.peekEvent() ?: return
        // As on the real event thread, an exception ends only that event; it never reaches the code that queued it.
        try {
            val e: java.awt.AWTEvent = queue.nextEvent
            if (e is ActiveEvent) e.dispatch() else (e.getSource() as? Component)?.dispatchEvent(e)
        } catch (t: Throwable) { awtFailures += t }
    }
}

internal suspend fun StorageSceneDriver.until(what: String, ms: Long = 15_000, condition: () -> Boolean) {
    val end = System.nanoTime() + ms * 1_000_000
    while (!condition()) {
        check(System.nanoTime() < end) { "Timed out waiting for $what. Present: ${nodes().map(::semanticsText).filter { it.isNotBlank() }.distinct()}" }
        pumpAwt(); frame(); delay(10)
    }
}
internal suspend fun StorageSceneDriver.key(k: Key, shift: Boolean = false) {
    scene.sendKeyEvent(KeyEvent(k, KeyEventType.KeyDown, isShiftPressed = shift)); scene.sendKeyEvent(KeyEvent(k, KeyEventType.KeyUp, isShiftPressed = shift)); frames(3)
}
private fun SemanticsNode.label(): String {
    config.getOrNull(SemanticsProperties.EditableText)?.let { return it.text }
    semanticsText(this).takeIf { it.isNotBlank() }?.let { return it }
    return children.asSequence().map { it.label() }.firstOrNull { it.isNotBlank() }.orEmpty()
}
private fun StorageSceneDriver.focused(): SemanticsNode? = nodes().lastOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }
private fun StorageSceneDriver.editors() = nodes().filter { it.config.getOrNull(SemanticsActions.SetText) != null }
private fun StorageSceneDriver.setText(index: Int, value: String) = editors()[index].config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))
private fun StorageSceneDriver.hasText(text: String, exact: Boolean = true) = matches(text, exact).isNotEmpty() ||
    nodes().any { n -> n.config.getOrNull(SemanticsProperties.EditableText)?.text?.let { if (exact) it == text else it.contains(text) } == true }
private fun StorageSceneDriver.enabled(text: String) = clickable(text).config.getOrNull(SemanticsProperties.Disabled) == null
/** Set the editor drawn below the label whose text is [label]. */
private fun StorageSceneDriver.setField(label: String, value: String) {
    val all = nodes(); val l = all.first { semanticsText(it).equals(label, true) }.boundsInWindow
    all.filter { it.config.getOrNull(SemanticsActions.SetText) != null && it.boundsInWindow.top >= l.bottom - 2 }.minBy { it.boundsInWindow.top - l.bottom }
        .config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))
}
private fun sha256(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
private fun demo(scope: CoroutineScope, backup: Boolean = false): Workspace = Workspace(scope).also { w ->
    w.exploreDemo(); val row = w.state.value.overview!!.storage.first { if (backup) "backup" in it.content.orEmpty() else it.id == "local" }; w.selectStorage(row.node, row.id)
}

/** Counts every dispatch into the scene's event loop. */
private class CountingDispatcher(val base: CoroutineDispatcher) : CoroutineDispatcher() {
    val count = AtomicInteger()
    override fun dispatch(context: CoroutineContext, block: Runnable) { count.incrementAndGet(); base.dispatch(context, block) }
}

/** A connected workspace whose every used gateway is an in-memory stand-in. Nothing reaches a network. */
private class Connected(scope: CoroutineScope) {
    val iso = StorageVolume("local:iso/existing.iso", "iso", null, 1, null, null, null, null, null, false, null)
    val fixture = StorageVolume("local:iso/fixture.iso", "iso", null, 2, null, null, null, null, null, false, null)
    @Volatile var listing = listOf(iso)
    val sends = AtomicInteger()
    @Volatile var cancelled = false
    @Volatile var onSubmit: (StorageRequest) -> Unit = {}
    val create = FakeCreateGuestGateway().apply { volumes["local" to "iso"] = FakeRead.loads(listOf(iso)) }
    val overview = Overview(1, emptyList(), members = listOf(RecordedMember(ORIGIN, "node-a", "online", null, null, null, null, null, null, null, null)),
        storage = listOf(RecordedStorage(ORIGIN, "node-a", "local", false, 1, 10, "available", "dir", "iso,vztmpl,backup")))
    val dump = PermissionDump(mapOf("/" to mapOf("Sys.Audit" to true), "/vms" to mapOf("VM.Allocate" to true), "/nodes/node-a" to mapOf("Sys.AccessNetwork" to true, "Sys.Audit" to true),
        "/storage/local" to mapOf("Datastore.AllocateTemplate" to true, "Datastore.Audit" to true, "Datastore.AllocateSpace" to true)))
    val actions = object : StorageActionGateway {
        override suspend fun catalogue(connection: Connection, node: String) = listOf(AplTemplate("sample_1_amd64.tar.zst", "sample", "1", "lxc"))
        override suspend fun metadata(connection: Connection, node: String, url: String, verify: Boolean) = UrlMetadata("fixture.iso", 2, null)
        override suspend fun attributes(connection: Connection, key: StorageKey, volid: String) = StorageAttributes(null, false)
        override suspend fun submit(connection: Connection, key: StorageKey, request: StorageRequest, progress: (Long) -> Unit, cancel: UploadCancel): String? {
            sends.incrementAndGet(); onSubmit(request)
            if (request is StorageRequest.Upload) {
                progress(request.source.size / 2)
                val stopped = UploadCancel::class.java.getDeclaredField("stopped").apply { isAccessible = true }
                while (!(stopped.get(cancel) as Boolean)) delay(20)
                cancelled = true
                throw AmbiguousPowerResultException()
            }
            listing = listing + fixture
            return "UPID:node-a:00000010:00000011:65ABCDEF:download:fixture.iso:test@pve:"
        }
        override suspend fun status(connection: Connection, node: String, upid: String) = PowerTaskStatus(PowerPhase.Succeeded, "Stopped with exit status OK.")
    }
    val workspace = Workspace(scope,
        gateway = object : InventoryGateway {
            override suspend fun overview(connection: Connection) = overview
            override suspend fun details(connection: Connection, guest: Guest) = error("not used")
        },
        taskGateway = object : TaskGateway { override suspend fun tasks(connection: Connection) = emptyList<ClusterTask>() },
        nodeStatusGateway = object : NodeStatusGateway { override suspend fun status(connection: Connection, origin: String, node: String, scope: String): NodeLiveStatus = error("not used") },
        permissionsGateway = object : PermissionsGateway { override suspend fun read(connection: Connection) = dump },
        storageContentGateway = object : StorageContentGateway { override suspend fun read(connection: Connection, node: String, storage: String, content: String?) = listing.filter { content == null || it.content == content } },
        storageGateway = object : StorageGateway {
            override suspend fun config(connection: Connection) = emptyList<StorageConfigRow>()
            override suspend fun history(connection: Connection, node: String, storage: String, timeframe: String, cf: String) = emptyList<HistoryPoint>()
        },
        createGateway = create, storageActionGateway = actions)
    init { workspace.connect(Connection(ORIGIN, "test@pve!fixture", "invented-fixture-secret", "")); workspace.setLive(false) }
}

class StorageInteractionTest {
    private suspend fun CoroutineScope.urlForm(w: Workspace, onClose: () -> Unit, context: CoroutineContext = coroutineContext): StorageSceneDriver {
        val scene = storageScene(LOOK, context) { val live by w.state.collectAsState(); StorageActionDialog(live, w, live.storageSelection!!, StorageAction.UrlDownload, profileLabel = "Synthetic profile", onClose = onClose) }
        return StorageSceneDriver(scene, LOOK).also { it.settle() }
    }

    @Test fun confirmationKeyboardJourneyEscapesBackAndSubmitsExactlyOnce() = runSceneTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob()); val w = demo(scope); yield()
        var open by mutableStateOf(true); var closed = 0
        val scene = storageScene(LOOK, coroutineContext) { val live by w.state.collectAsState(); if (open) StorageActionDialog(live, w, live.storageSelection!!, StorageAction.UrlDownload, profileLabel = "Synthetic profile", onClose = { closed++; open = false }) }
        val d = StorageSceneDriver(scene, LOOK)
        try {
            d.settle()
            assertEquals("", d.focused()?.label().orEmpty().takeIf { it == "CANCEL" }.orEmpty(), "A form must not start on Cancel")
            d.setText(0, "https://mirror.example.test/fixture.iso"); d.setText(1, "fixture.iso"); d.settle()
            d.click("Review"); d.settle()
            assertEquals("CANCEL", d.focused()?.label(), "The sole confirmation starts on Cancel")
            assertTrue(d.hasText("Action: Download from URL"))
            assertFalse(d.hasText("Action: UrlDownload"))
            d.key(Key.Tab); assertEquals("SIMULATE", d.focused()?.label())
            d.key(Key.Tab, shift = true); assertEquals("CANCEL", d.focused()?.label())
            d.key(Key.Escape); d.settle()
            assertFalse(d.hasText("Simulate storage action")); assertTrue(d.hasText("Download from URL")); assertEquals(0, closed)
            assertTrue(d.hasText("https://mirror.example.test/fixture.iso"), "Escape on the confirmation returns to the same filled form")
            assertTrue(w.state.value.storageActions.submissions.isEmpty())
            d.click("Review"); d.settle(); d.key(Key.Tab); assertEquals("SIMULATE", d.focused()?.label())
            d.key(Key.Enter); d.settle()
            assertEquals(1, w.state.value.storageActions.submissions.size, "Enter on the focused confirm submits once")
            assertTrue(d.hasText("Storage action")); assertTrue(d.hasText("Demo · simulated"))
            d.key(Key.Escape); d.settle(); assertEquals(1, closed); assertFalse(open)
            // Double activation of one confirmation, before any frame: the captured confirmation is single-use.
            open = true; d.settle()
            d.setText(0, "https://mirror.example.test/second.iso"); d.setText(1, "second.iso"); d.settle(); d.click("Review"); d.settle()
            val confirm = d.clickable("Simulate").config[SemanticsActions.OnClick].action!!
            confirm(); confirm(); d.settle()
            assertEquals(2, w.state.value.storageActions.submissions.size, "a second activation of the same confirmation must not send")
        } finally { scene.close(); scope.cancel() }
    }

    @Test fun formTabOrderKeepsFocusVisibleShiftTabReversesAndEscapeClosesWithoutSending() = runSceneTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob()); val w = demo(scope); yield()
        var closed = 0
        val d = urlForm(w, { closed++ })
        try {
            assertEquals("", d.focused()?.config?.getOrNull(SemanticsProperties.EditableText)?.text, "The URL field takes the keyboard when the form opens")
            d.setText(0, "https://mirror.example.test/fixture.iso"); d.setText(1, "fixture.iso"); d.settle()
            val order = mutableListOf<String>()
            repeat(8) {
                d.key(Key.Tab); d.settle()
                val f = assertNotNull(d.focused(), "Tab lost focus"); order += f.label()
                val b = f.boundsInWindow
                assertTrue(b.top >= 0f && b.bottom <= LOOK.pixelHeight + .5f, "focused '${f.label()}' is off screen at $b")
            }
            val expected = listOf("CHECK URL", "Verify website certificate", "iso", "fixture.iso", "none", "none", "CANCEL", "REVIEW")
            assertEquals(expected, order, "Tab order")
            // Past the last action, focus stays in the modal: its focusable frame (which carries Escape), then the first field.
            val urlText = "https://mirror.example.test/fixture.iso"
            fun onUrl() = d.focused()?.config?.getOrNull(SemanticsProperties.EditableText)?.text == urlText
            d.key(Key.Tab); d.settle(); if (!onUrl()) { assertNotNull(d.focused(), "focus left the modal"); d.key(Key.Tab); d.settle() }
            assertTrue(onUrl(), "Tab wraps inside the modal")
            d.key(Key.Tab, shift = true); d.settle(); if (d.focused()?.label() != "REVIEW") { d.key(Key.Tab, shift = true); d.settle() }
            assertEquals("REVIEW", d.focused()?.label())
            d.key(Key.Tab, shift = true); d.settle(); assertEquals("CANCEL", d.focused()?.label())
            d.key(Key.Tab, shift = true); d.settle(); assertEquals("none", d.focused()?.label())
            d.key(Key.Escape); d.settle(); assertEquals(1, closed)
            assertTrue(w.state.value.storageActions.submissions.isEmpty())
        } finally { d.scene.close(); scope.cancel() }
    }

    @Test fun notesEditorTakesTheKeyboardOnceTheFullNotesAreRead() = runSceneTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob()); val w = demo(scope, backup = true); yield()
        val backup = w.state.value.storageContent.value!!.volumes.first { it.content == "backup" }
        val scene = storageScene(LOOK, coroutineContext) { val live by w.state.collectAsState(); StorageActionDialog(live, w, live.storageSelection!!, StorageAction.EditNotes, backup, onClose = {}) }
        val d = StorageSceneDriver(scene, LOOK)
        try {
            d.settle()
            val editor = d.nodes().single { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertEquals(true, editor.config.getOrNull(SemanticsProperties.Focused), "the loaded notes editor has the keyboard")
            assertTrue(w.state.value.storageActions.submissions.isEmpty())
        } finally { scene.close(); scope.cancel() }
    }

    @Test fun storageTargetStaysVisibleWhenTheFocusedStressFormScrolls() = runSceneTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob()); val w = demo(scope); yield()
        val look = storageLooks().first { it.id == "dark-ui200-text150-1536x832" }
        val key = w.state.value.storageSelection!!
        val target = "${key.node} \u00b7 ${key.storage}"
        val scene = storageScene(look, coroutineContext) {
            val live by w.state.collectAsState()
            StorageActionDialog(live, w, key, StorageAction.UrlDownload, onClose = {})
        }
        val d = StorageSceneDriver(scene, look)
        try {
            d.settle()
            val before = d.matches(target).single().boundsInWindow
            assertTrue(before.top >= 0 && before.bottom < look.pixelHeight)
            d.nodes().filter { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { r -> r.maxValue() > 0 } == true }
                .forEach { it.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, 100_000f) }
            d.settle()
            assertEquals(before, d.matches(target).single().boundsInWindow, "Scrolling concealed the selected target")
            assertTrue(w.state.value.storageActions.submissions.isEmpty())
        } finally { scene.close(); scope.cancel() }
    }

    @Test fun pickerOpensByKeyAndPointerSelectsAndTheBodyScrollsUnderPinnedActions() = runSceneTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob()); val w = demo(scope); yield()
        var closed = 0
        val d = urlForm(w, { closed++ })
        try {
            d.setText(0, "https://mirror.example.test/fixture.iso"); d.setText(1, "fixture.iso"); d.settle()
            // Keyboard: focus the checksum picker with Tab, open it with Enter, move with Down, choose with Enter.
            val editors = d.editors().size
            repeat(5) { d.key(Key.Tab) }; d.settle(); assertEquals("none", d.focused()?.label())
            d.key(Key.Enter); d.settle()
            assertTrue(listOf("md5", "sha1", "sha256", "sha512").all { d.hasText(it) }, "picker options are visible")
            repeat(4) { d.key(Key.DirectionDown) }; d.key(Key.Enter); d.settle()
            assertTrue(d.hasText("sha256")); assertFalse(d.hasText("sha512")); assertEquals(editors + 1, d.editors().size, "choosing an algorithm adds the checksum field")
            // Escape inside the open popup closes only the popup.
            d.click("sha256"); d.settle(); assertTrue(d.hasText("sha512")); d.key(Key.Escape); d.settle()
            assertFalse(d.hasText("sha512")); assertTrue(d.hasText("Download from URL")); assertEquals(0, closed)
            // Pointer: open and choose another value.
            d.click("sha256"); d.settle(); d.click("sha512"); d.settle(); assertTrue(d.hasText("sha512")); assertFalse(d.hasText("md5"))
            // Wheel scrolling moves the body; the footer stays where it was.
            fun range() = d.nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) }.first { it.maxValue() > 0f }
            val review = d.clickable("Review").boundsInWindow
            val body = d.nodes().first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { r -> r.maxValue() > 0f } == true }.boundsInWindow
            val before = range().value()
            repeat(6) { d.scene.sendPointerEvent(PointerEventType.Scroll, body.center, scrollDelta = Offset(0f, 3f)); d.frames(2) }
            d.settle()
            assertTrue(range().value() > before, "the wheel did not scroll the form body")
            assertEquals(review, d.clickable("Review").boundsInWindow, "the footer moved")
            val last = d.matches("A filtered listing cannot prove a filename is unused.").single().boundsInWindow
            assertTrue(last.bottom <= review.top, "the end of the body is reachable above the footer")
            assertTrue(w.state.value.storageActions.submissions.isEmpty())
        } finally { d.scene.close(); scope.cancel() }
    }

    @Test fun boundedScrollbarIsVisibleOnTheDarkPlate() = runSceneTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob()); val w = demo(scope); yield()
        val d = urlForm(w, {})
        try {
            val frame = d.settle()
            val body = d.nodes().first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { r -> r.maxValue() > 0f } == true }.boundsInWindow
            // The scrollbar is drawn in the 12 dp gutter to the right of the scrolling column.
            val gutter = Rect(body.right, body.top, body.right + 12 * LOOK.density, body.bottom)
            fun lum(x: Int, y: Int): Int { val i = (y * frame.width + x) * 4; return ((frame.pixels[i + 2].toInt() and 255) * 3 + (frame.pixels[i + 1].toInt() and 255) * 6 + (frame.pixels[i].toInt() and 255)) / 10 }
            val plate = lum((body.right + 6 * LOOK.density).toInt(), (body.top - 4 * LOOK.density).toInt()) // plate between title and body
            var strongest = 0
            for (y in gutter.top.toInt() until gutter.bottom.toInt()) for (x in gutter.left.toInt() until gutter.right.toInt()) strongest = maxOf(strongest, abs(lum(x, y) - plate))
            assertTrue(strongest >= 40, "scrollbar thumb contrast against the plate is only $strongest")
        } finally { d.scene.close(); scope.cancel() }
    }

    @Test fun stubbedChooserCancelSelectFailureAndDoubleActivationNeverLeaveReadingBusy() = runSceneTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob()); val w = demo(scope); yield()
        val file = Files.createTempFile("storage-pick-", ".iso").also { Files.write(it, ByteArray(1024 * 1024) { n -> (n * 7).toByte() }) }
        var calls = 0; var mode = "cancel"
        val picker = StorageFilePicker { _ -> calls++; when (mode) { "cancel" -> null; "fail" -> throw IllegalStateException("native chooser failed"); else -> file } }
        val scene = storageScene(LOOK, coroutineContext) {
            val live by w.state.collectAsState()
            CompositionLocalProvider(LocalStorageFilePicker provides picker) { StorageActionDialog(live, w, live.storageSelection!!, StorageAction.Upload, profileLabel = "Synthetic profile", onClose = {}) }
        }
        val d = StorageSceneDriver(scene, LOOK)
        try {
            d.settle()
            d.click("Choose file"); d.until("cancelled chooser") { calls == 1 && d.enabled("Choose file") }
            assertFalse(d.nodes().any { semanticsText(it).endsWith(" bytes") }); assertFalse(d.hasText("Cannot read the selected file."))
            mode = "fail"; d.click("Choose file")
            d.until("a failed chooser to release the form", 5_000) { d.enabled("Choose file") && d.hasText("Cannot read the selected file.") }
            assertEquals(2, calls)
            mode = "select"
            val choose = d.clickable("Choose file").config[SemanticsActions.OnClick].action!!
            choose(); choose()
            d.until("the chosen file") { d.hasText("${file.fileName} · 1048576 bytes") && d.enabled("Choose file") }
            assertEquals(3, calls, "a double activation opened two choosers")
            assertTrue(d.hasText(file.fileName.toString()))
            d.click("Compute SHA256"); d.until("the checksum") { d.hasText(sha256(file)) && d.enabled("Compute SHA256") }
            d.click("Review"); d.settle()
            assertTrue(d.hasText("Simulate storage action")); assertTrue(d.nodes().any { semanticsText(it).contains("sha256") })
            assertTrue(w.state.value.storageActions.submissions.isEmpty())
        } finally { scene.close(); scope.cancel(); Files.deleteIfExists(file) }
    }

    @Test fun hashingProgressQueuesBoundedUiWorkWhileTheUiIsBusy() = runSceneTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob()); val w = demo(scope); yield()
        val file = Files.createTempFile("storage-hash-", ".iso").also { Files.write(it, ByteArray(16 * 1024 * 1024) { n -> n.toByte() }) }
        val counting = CountingDispatcher(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
        val upload = DesktopUpload(UploadSource.capture(file), "storage-hash.iso", file)
        val scene = storageScene(LOOK, coroutineContext + counting) {
            val live by w.state.collectAsState(); StorageActionDialog(live, w, live.storageSelection!!, StorageAction.Upload, initialUpload = upload, onClose = {})
        }
        val d = StorageSceneDriver(scene, LOOK)
        try {
            d.settle(); d.click("Compute SHA256"); d.frames(2)
            val before = counting.count.get()
            Thread.sleep(1500) // the UI loop is blocked while 256 chunks are read on IO
            val queued = counting.count.get() - before
            d.until("the checksum") { d.hasText(sha256(file)) }
            assertTrue(queued <= 16, "hashing queued $queued UI dispatches for 256 chunks while the UI was busy")
        } finally { scene.close(); scope.cancel(); Files.deleteIfExists(file) }
    }

    @Test fun acceptedUploadCloseKeepsContinuesOrStopsCoherently() = runSceneTest {
        for (path in listOf("continue", "stop")) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob()); val c = Connected(scope)
            val w = c.workspace
            val file = Files.createTempFile("storage-upload-", ".iso").also { Files.write(it, ByteArray(1024 * 1024)) }
            var open by mutableStateOf(true)
            val scene = storageScene(LOOK, coroutineContext) {
                val live by w.state.collectAsState()
                if (open && live.storageSelection != null && live.storageContent.value != null) StorageActionDialog(live, w, live.storageSelection!!, StorageAction.Upload,
                    initialUpload = DesktopUpload(UploadSource.capture(file), "fixture.iso", file), profileLabel = "Synthetic profile", onClose = { open = false })
                if (!open) StorageActionStrip(live, w)
            }
            val d = StorageSceneDriver(scene, LOOK)
            try {
                d.until("the connected overview") { w.state.value.overview != null }
                w.loadPermissions(); d.until("permissions") { w.state.value.permissions.value != null }
                w.selectStorage("node-a", "local"); d.until("the upload form") { d.hasText("Upload file") && d.enabled("Review") }
                d.click("Review"); d.until("the confirmation") { d.hasText("Confirm") }
                d.click("Confirm"); d.until("bytes in flight") { d.hasText("524288 / 1048576 file bytes sent", false) }
                assertTrue(d.hasText("Sending")); assertTrue(d.hasText("Started - close")); assertFalse(d.hasText("Terminal OK"))
                d.click("Started - close"); d.settle()
                assertTrue(listOf("Close upload?", "Keep open", "Continue in activity", "Stop upload").all { d.hasText(it) })
                d.click("Keep open"); d.settle(); assertFalse(d.hasText("Close upload?")); assertTrue(open)
                d.click("Started - close"); d.settle()
                if (path == "continue") {
                    d.click("Continue in activity"); d.settle(); assertFalse(open)
                    assertEquals(PowerPhase.Accepted, w.state.value.storageActions.submissions.single().phase); assertFalse(c.cancelled)
                    // Activity keeps the running upload; its own Stop needs a second explicit choice.
                    d.click("Stop upload"); d.settle(); assertTrue(d.hasText("Stop upload?")); assertFalse(c.cancelled)
                    d.click("Stop upload"); d.until("Unknown after stop") { w.state.value.storageActions.submissions.single().phase == PowerPhase.Unknown }
                } else {
                    d.click("Stop upload"); d.until("Unknown after stop") { w.state.value.storageActions.submissions.single().phase == PowerPhase.Unknown }
                    assertFalse(open)
                }
                d.settle()
                assertTrue(c.cancelled); assertTrue(d.hasText("Unknown · check storage before trying again")); assertTrue(d.hasText("I checked storage"))
                assertFalse(d.hasText("Terminal OK")); assertEquals(1, c.sends.get(), "stopping must never resend")
                d.click("I checked storage"); d.settle(); assertFalse(d.hasText("I checked storage")); assertEquals(1, c.sends.get())
            } finally { scene.close(); scope.cancel(); w.close(); Files.deleteIfExists(file) }
        }
    }

    @Test fun failedCreateChoiceRefreshRecoversExactMediaWithoutResendOrClosingTheWizard() = runSceneTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob()); val c = Connected(scope); val w = c.workspace
        c.onSubmit = { c.create.volumes["local" to "iso"] = FakeRead.failure("storage 'local' is not online") }
        val scene = storageScene(LOOK.copy(logicalWidth = 1280, logicalHeight = 800), coroutineContext) {
            val live by w.state.collectAsState(); if (live.creation != null) CreateGuestHost(live, w, "Synthetic profile", {}, onViewLog = {})
        }
        val d = StorageSceneDriver(scene, LOOK)
        try {
            d.until("the connected overview") { w.state.value.overview != null && !w.state.value.loading }
            assertTrue(w.openCreateSession(NewGuestKind.Vm, "node-a"))
            d.until("permissions and the wizard") { w.state.value.permissions.value != null && d.hasText("1  General") }
            val session = w.state.value.creation!!
            d.setField("Name", "retained-name"); d.settle()
            d.click("2  OS", last = false); d.until("existing ISO choices") { d.hasText("Get ISO image") }
            d.click("Get ISO image"); d.until("the media sheet") { d.hasText("Open URL") && d.enabled("Open URL") }
            d.click("Open URL"); d.until("the URL form") { d.hasText("Download from URL") }
            d.setField("URL · fetched by the node", "https://mirror.example.test/fixture.iso")
            d.settle(); d.setField("File name", "fixture.iso"); d.until("Review") { d.enabled("Review") }
            d.click("Review"); d.until("confirmation") { d.hasText("Confirm") }; d.click("Confirm")
            d.until("terminal OK in the result") { d.hasText("Terminal OK") }
            d.click("Close"); d.until("the failed create-choice read") { d.hasText("Could not refresh the create choices. Refresh storage and choices to try again.") }
            assertTrue(d.hasText("Your create form stays open", false)); assertEquals(1, c.sends.get())
            assertEquals(session.generation, w.state.value.creation!!.generation)
            assertTrue(w.state.value.creation!!.volumes["local" to "iso"]!!.error != null)
            c.create.volumes["local" to "iso"] = FakeRead.loads(listOf(c.iso, c.fixture))
            d.click("Refresh storage and choices")
            d.until("the sheet to close") { !d.hasText("Your create form stays open", false) }
            d.until("the wizard to show the exact ISO") { d.hasText("fixture.iso") }
            assertEquals(1, c.sends.get(), "retry is read-only; the download was never resent")
            assertTrue(c.create.submits.isEmpty())
            val now = w.state.value.creation!!; assertEquals(session.epoch, now.epoch); assertEquals(session.generation, now.generation)
            assertTrue(c.create.calls.count { it == "volumes node-a local iso" } >= 3, c.create.calls.toString())
            d.click("1  General", last = false); d.settle(); assertTrue(d.hasText("retained-name"))
        } finally { scene.close(); scope.cancel(); w.close() }
    }

    @Test fun closeBackAndResultPathsKeepDraftsAndContainerKeyThenApplyExactMedia() = runSceneTest {
        for (kind in NewGuestKind.entries) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob()); val w = Workspace(scope); w.exploreDemo()
            assertTrue(w.openCreateSession(kind, "demo-node-a")); val session = w.state.value.creation!!
            val scene = storageScene(LOOK.copy(logicalWidth = 1280, logicalHeight = 800), coroutineContext) { val live by w.state.collectAsState(); if (live.creation != null) CreateGuestHost(live, w, "Synthetic profile", {}, onViewLog = {}) }
            val d = StorageSceneDriver(scene, LOOK)
            val vm = kind == NewGuestKind.Vm
            val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEB invented@fixture"
            try {
                d.settle(); d.setField(if (vm) "Name" else "Hostname", "retained-fixture")
                if (!vm) d.setField("SSH public keys", key)
                d.settle(); d.click(if (vm) "2  OS" else "2  Template", last = false); d.settle()
                val get = if (vm) "Get ISO image" else "Get template"; val sheet = if (vm) "Get ISO image" else "Get container template"
                // Back from the sheet itself.
                val sheetOpen = { d.hasText("Your create form stays open", false) }
                d.click(get); d.settle(); assertTrue(d.hasText(sheet) && sheetOpen()); d.key(Key.Escape); d.settle(); assertFalse(sheetOpen(), "Escape closes the sheet")
                d.click(get); d.settle()
                // Back from the form with Cancel, then the real form -> Escape on confirmation -> submit -> result -> Close.
                val open = "Open ${if (vm) "URL" else "Catalogue"}"
                d.click(open); d.settle(); d.click("Cancel"); d.settle(); assertTrue(d.hasText(open))
                d.click(open); d.settle()
                if (vm) { d.setField("URL · fetched by the node", "https://mirror.example.test/fixture.iso"); d.settle(); d.setField("File name", "fixture.iso") }
                else d.until("catalogue") { d.hasText("sample_1_amd64.tar.zst · architecture unknown") }.also { d.click("sample_1_amd64.tar.zst · architecture unknown") }
                d.settle(); d.click("Review"); d.settle(); d.key(Key.Escape); d.settle(); assertTrue(d.hasText("Review"))
                d.click("Review"); d.settle(); d.click("Simulate"); d.settle()
                // In demo the simulated download is terminal at once and its file is in the listing, so Ready closes the
                // sheet (and the result frame drawn inside it) as soon as the choices refresh. The wizard is the result.
                d.until("Ready to close the sheet") { !sheetOpen() }
                val volid = if (vm) "fixture.iso" else "sample_1_amd64.tar.zst"
                d.until("exact media in the wizard") { d.nodes().any { semanticsText(it).contains(volid) } }
                assertEquals(session.epoch, w.state.value.creation!!.epoch); assertEquals(session.generation, w.state.value.creation!!.generation)
                assertEquals(1, w.state.value.storageActions.submissions.size); assertTrue(w.state.value.submissions.isEmpty())
                d.click("1  General", last = false); d.settle(); assertTrue(d.hasText("retained-fixture"))
                if (!vm) assertTrue(d.hasText(key), "the container key buffer survived the sheet, form, confirmation and result")
            } finally { scene.close(); w.closeCreateSession(); scope.cancel() }
        }
    }

    @Test fun readyNeverOverwritesAChoiceTheUserChangedAfterRequestingMedia() = runSceneTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob()); val w = Workspace(scope); w.exploreDemo()
        assertTrue(w.openCreateSession(NewGuestKind.Vm, "demo-node-a")); val session = w.state.value.creation!!; val context = createContext(w.state.value, session)
        val rows = session.volumes["local" to "iso"]!!.value!!; assertTrue(rows.size >= 2, "demo needs two ISO images")
        var ready by mutableStateOf<StorageMediaReady?>(null); var observed: VmDraft? = null; var requested = 0
        val scene = storageScene(LOOK.copy(logicalWidth = 1280, logicalHeight = 800), coroutineContext) {
            CreateVmWizard(session, context, listOf(session.node), {}, {}, {}, {}, {}, initialAdvanced = false, onGetMedia = { _, _, _ -> requested++ },
                parameters = { dr, _ -> observed = dr; emptyList() }, storageMediaReady = ready, onStorageMediaApplied = { ready = null }, preferences = CreateWizardPreferences.memory())
        }
        val d = StorageSceneDriver(scene, LOOK)
        try {
            d.settle(); d.click("2  OS", last = false); d.settle()
            d.click("Get ISO image"); d.settle(); assertEquals(1, requested)
            val current = (observed!!.install.media as? CdMedia.Iso)?.volid
            val chosen = rows.first { it.volid != current }
            d.click(current?.takeIf { it.isNotEmpty() }?.substringAfter(":iso/") ?: "Choose…", last = false); d.settle() // the ISO picker shows its value
            d.click(chosen.volid.substringAfter(":iso/")); d.settle()
            assertEquals(chosen.volid, (observed!!.install.media as CdMedia.Iso).volid)
            ready = StorageMediaReady(session.epoch, session.generation, session.node, "local", rows.first { it.volid != chosen.volid }.volid); d.settle()
            assertNull(ready, "the wizard consumes the Ready signal")
            assertEquals(chosen.volid, (observed!!.install.media as CdMedia.Iso).volid, "Ready overwrote a choice changed after the request")
        } finally { scene.close(); w.closeCreateSession(); scope.cancel() }
    }

    @Test fun showcaseBlursStorageIdentityPixelsAndQueryNeverReachesOtherSemantics() = runSceneTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val canaryQuery = "canary-secret-query"
        val url = "https://canary-host.example.test/p/canary-path.iso?token=$canaryQuery"
        fun edge(frame: SettledFrame, r: Rect): Double {
            var sum = 0L; var n = 0
            for (y in r.top.toInt().coerceAtLeast(0) until r.bottom.toInt().coerceAtMost(frame.height)) for (x in r.left.toInt().coerceAtLeast(0) until (r.right.toInt() - 1).coerceAtMost(frame.width - 1)) {
                val i = (y * frame.width + x) * 4; val j = i + 4
                sum += abs((frame.pixels[i + 1].toInt() and 255) - (frame.pixels[j + 1].toInt() and 255)); n++
            }
            return if (n == 0) 0.0 else sum.toDouble() / n
        }
        val report = StringBuilder()
        suspend fun compare(name: String, d: StorageSceneDriver, masked: (SemanticsNode) -> Boolean, sharp: String) {
            Appearance.preview(false, showcase = true); val on = d.settle(); val nodesOn = d.nodes()
            Appearance.preview(false, showcase = false); val off = d.settle()
            val targets = d.nodes().filter(masked); assertTrue(targets.isNotEmpty(), "$name: no identity canaries")
            targets.forEach { t ->
                val b = t.boundsInWindow; val ratio = edge(on, b) / edge(off, b).coerceAtLeast(1e-6)
                report.appendLine("$name\t${t.label().take(60)}\t${"%.2f".format(ratio)}")
                assertTrue(ratio < 0.5, "$name: '${t.label()}' stays legible under Showcase (edge ratio ${"%.2f".format(ratio)})")
                // Modifier.identity marks its own node; a field's editor is a descendant, so look for a Hidden node covering it.
                val hidden = nodesOn.any { h -> h.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Hidden") == true &&
                    h.boundsInWindow.let { hb -> hb.left <= b.left + 1 && hb.top <= b.top + 1 && hb.right >= b.right - 1 && hb.bottom >= b.bottom - 1 } }
                assertTrue(hidden, "$name: '${t.label()}' is not marked Hidden for assistive technology")
            }
            val s = nodesOn.first { semanticsText(it).equals(sharp, true) }.boundsInWindow
            assertTrue(edge(on, s) / edge(off, s) > 0.9, "$name: chrome '$sharp' should stay sharp")
            val leaks = nodesOn.filter { n -> n.config.getOrNull(SemanticsProperties.EditableText)?.text?.contains(canaryQuery) != true &&
                (semanticsText(n).contains(canaryQuery) || n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.contains(canaryQuery) }) }
            assertTrue(leaks.isEmpty(), "$name: the URL query reached other semantics: ${leaks.map { it.label() }}")
        }
        val w = demo(scope); yield()
        var open by mutableStateOf(true)
        val scene = storageScene(LOOK, coroutineContext) { val live by w.state.collectAsState(); if (open) StorageActionDialog(live, w, live.storageSelection!!, StorageAction.UrlDownload, profileLabel = "Synthetic profile", onClose = { open = false }) }
        val d = StorageSceneDriver(scene, LOOK)
        val wb = demo(scope, backup = true); yield()
        val backup = wb.state.value.storageContent.value!!.volumes.first { it.content == "backup" }
        val notes = storageScene(LOOK, coroutineContext) { val live by wb.state.collectAsState(); StorageActionDialog(live, wb, live.storageSelection!!, StorageAction.EditNotes, backup, profileLabel = "Synthetic profile", onClose = {}) }
        val dn = StorageSceneDriver(notes, LOOK)
        val key = w.state.value.storageSelection!!
        val denied = w.state.value.copy(demo = false, permissions = ReadSection(value = PermissionsView(PermissionDump(emptyMap()), PermissionIdentity.Token)))
        val tip = storageScene(LOOK, coroutineContext) { StorageFileActions(denied, w, key) }
        val dt = StorageSceneDriver(tip, LOOK)
        try {
            d.settle(); d.setText(0, url); d.setText(1, "canary-file.iso"); d.settle()
            compare("url-form", d, { n -> semanticsText(n) == "${key.node} · ${key.storage}" || n.config.getOrNull(SemanticsProperties.EditableText)?.text?.contains("canary") == true }, "Download from URL")
            d.click("Review"); d.settle()
            compare("url-confirm", d, { n -> semanticsText(n).let { it.contains("canary") || it.startsWith("Synthetic profile") } }, "Simulate storage action")
            d.click("Simulate"); d.settle()
            compare("url-result", d, { n -> semanticsText(n).contains("canary-file.iso") }, "Storage action")
            dn.settle(); dn.setText(0, "canary-backup-note"); dn.settle()
            compare("notes-form", dn, { n -> n.config.getOrNull(SemanticsProperties.EditableText)?.text == "canary-backup-note" }, "Edit backup notes")
            dt.settle(); val at = dt.clickable(storageActionTitle(StorageAction.TemplateDownload)).boundsInWindow.center
            tip.sendPointerEvent(PointerEventType.Enter, at); tip.sendPointerEvent(PointerEventType.Move, at); dt.frames(2); delay(900); dt.frames(4)
            compare("refusal-tooltip", dt, { n -> semanticsText(n).contains("/storage/${key.storage}") }, storageActionTitle(StorageAction.UrlDownload).uppercase())
            println("showcase-edge-ratios\n$report")
        } finally { Appearance.preview(false); scene.close(); notes.close(); tip.close(); scope.cancel() }
    }

    /** Opens the real OS chooser. Off by default: it shows a window on the desktop. Selection would need OS input and is not driven. */
    @Test fun nativeFileDialogOpensAndBothCancelPathsReturnWithoutHanging() {
        assumeTrue(System.getenv("STORAGE_NATIVE_PICKER") == "1" && !java.awt.GraphicsEnvironment.isHeadless())
        val evidence = StringBuilder()
        fun showing() = Window.getWindows().filterIsInstance<FileDialog>().filter { it.isShowing && it.title == STORAGE_CHOOSER_TITLE }
        fun waitFor(ms: Long, condition: () -> Boolean): Boolean { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { if (condition()) return true; Thread.sleep(25) }; return condition() }
        // 1. The dialog is closed without a choice (what Cancel does): the chooser returns null.
        val closer = Thread {
            check(waitFor(15_000) { showing().isNotEmpty() }) { "No native chooser appeared" }
            val dialog = showing().single()
            evidence.appendLine("dismiss: visible=${dialog.isShowing} modal=${dialog.isModal} mode=${if (dialog.mode == FileDialog.LOAD) "LOAD" else "SAVE"} class=${dialog.javaClass.name}")
            java.awt.EventQueue.invokeLater { dialog.dispose() }
        }.apply { isDaemon = true; start() }
        val started = System.nanoTime()
        val result = runBlocking { withTimeout(20_000) { chooseStorageUpload(false) } }
        closer.join(5_000)
        assertNull(result); evidence.appendLine("dismiss: returned null after ${(System.nanoTime() - started) / 1_000_000} ms")
        assertTrue(waitFor(5_000) { showing().isEmpty() })
        // 2. The caller is cancelled while the dialog is open: the dialog closes and the coroutine finishes.
        runBlocking {
            val job = launch(Dispatchers.Default) { chooseStorageUpload(false) }
            assertTrue(withContext(Dispatchers.IO) { waitFor(15_000) { showing().isNotEmpty() } }, "No native chooser appeared")
            evidence.appendLine("cancel: visible before cancel=${showing().size}")
            job.cancel(); withTimeout(10_000) { job.join() }
            assertTrue(withContext(Dispatchers.IO) { waitFor(5_000) { showing().isEmpty() } }, "the native chooser stayed open after cancellation")
            evidence.appendLine("cancel: job cancelled=${job.isCancelled}; chooser closed")
        }
        System.getenv("STORAGE_NATIVE_EVIDENCE")?.let { java.io.File(it).apply { parentFile.mkdirs() }.writeText(evidence.toString()) }
        println(evidence)
    }
}
