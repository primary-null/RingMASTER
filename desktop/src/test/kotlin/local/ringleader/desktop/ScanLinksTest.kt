@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import local.ringleader.core.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.test.*

// Hits for these tests. Documentation addresses only (RFC 5737); nothing here is a real host.
private val FINGERPRINT = "AB:".repeat(31) + "CD"
/** A fingerprint of its own for a host: one machine presents one certificate, so hosts that share one are one device. */
private fun fingerprintOf(octet: Int) = "AB:".repeat(30) + "%02X:CD".format(octet)
private fun proxmox(octet: Int, kind: ScanKind = ScanKind.PVE, name: String = "pve-$octet", fingerprint: String? = FINGERPRINT, saved: Boolean = false) =
    ScanHit("192.0.2.$octet", if (kind == ScanKind.PDM) 8443 else if (kind == ScanKind.PBS) 8007 else 8006, true, kind, "Proxmox 9.2", fingerprint,
        "CN=$name.example.test,O=Proxmox Virtual Environment,OU=PVE Cluster Node", saved)
private val ADGUARD = ScanHit("192.0.2.10", 3000, false, ScanKind.ADGUARD, "AdGuard Home")          // http://192.0.2.10:3000
private val ROUTER = ScanHit("192.0.2.1", 443, true, ScanKind.WEB, "Router")                          // https://192.0.2.1:443
private val PLAN = NetworkPlan(listOf(ScanNetwork(ScanInterface("Ethernet", "192.0.2.2", 24), "192.0.2.0/24", null, (1..254).map { "192.0.2.$it" })))
private val ACTIONS = setOf("open", "copy", "web ui", "copy address", "copy fingerprint", "use")

/** A clipboard that remembers what was put on it. A test must never touch the real one. */
private class MemoryClipboard : ClipboardManager {
    val copies = CopyOnWriteArrayList<String>()
    @Volatile var failing = false
    override fun setText(annotatedString: AnnotatedString) { check(!failing) { "the clipboard is busy" }; copies += annotatedString.text }
    override fun getText(): AnnotatedString? = copies.lastOrNull()?.let { AnnotatedString(it) }
}

private fun awaitTrue(what: String, timeoutMs: Long = 5000, condition: () -> Boolean) {
    val end = System.nanoTime() + timeoutMs * 1_000_000
    while (!condition()) { check(System.nanoTime() < end) { "Timed out waiting for $what" }; Thread.sleep(10) }
}

/** The pure part: what each launch result means, and how the shared note behaves. No window, no browser, no clipboard. */
class ScanLinkActionsTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val clipboard = MemoryClipboard()
    private val opened = CopyOnWriteArrayList<String>()
    private val url = "http://192.0.2.10:3000"
    private fun actions(launch: () -> BrowserLaunch = { BrowserLaunch.Accepted }, copiedMillis: Long = SCAN_COPIED_MS, io: CoroutineContext = Dispatchers.Unconfined) =
        ScanLinkActions(scope, { opened += it; launch() }, { clipboard.setText(AnnotatedString(it)) }, io, copiedMillis)
    @AfterTest fun stop() { scope.cancel() }

    @Test fun eachLaunchResultHasItsOwnMeaning() {
        assertEquals(BrowserOutcome(null, false), browserOutcome(BrowserLaunch.Accepted))
        assertEquals(BrowserOutcome("Browser launch still running.", false), browserOutcome(BrowserLaunch.Pending))
        assertEquals(BrowserOutcome("No browser opened, so the link was copied.", true), browserOutcome(BrowserLaunch.Failed))
        // Only a failed launch ever touches the clipboard.
        assertEquals(listOf(BrowserLaunch.Failed), BrowserLaunch.entries.filter { browserOutcome(it).copyLink })
    }

    @Test fun anAcceptedOpenHandsOverExactlyTheLinkAndSaysNothing() {
        val links = actions()
        links.open(url)
        assertEquals(listOf(url), opened)
        assertNull(links.note); assertEquals(emptyList(), clipboard.copies)
    }

    @Test fun aFailedOpenCopiesTheLinkAndSaysSo() {
        val links = actions({ BrowserLaunch.Failed })
        links.open(url)
        assertEquals(listOf(url), clipboard.copies)
        assertEquals("No browser opened, so the link was copied.", links.noteFor(url))
        assertNull(links.noteFor("http://192.0.2.11:3000"), "the note belongs to the address it was about")
    }

    @Test fun aFailedOpenThatCannotCopyEitherDoesNotClaimItDid() {
        clipboard.failing = true
        val links = actions({ BrowserLaunch.Failed })
        links.open(url)
        assertEquals("No browser opened, and the link could not be copied.", links.noteFor(url))
    }

    @Test fun aPendingOpenSaysSoAndCopiesNothing() {
        val links = actions({ BrowserLaunch.Pending })
        links.open(url)
        assertEquals("Browser launch still running.", links.noteFor(url)); assertEquals(emptyList(), clipboard.copies)
    }

    @Test fun anOpenerThatThrowsIsAFailedLaunch() {
        val links = ScanLinkActions(scope, { error("no desktop") }, { clipboard.setText(AnnotatedString(it)) }, Dispatchers.Unconfined)
        links.open(url)
        assertEquals(listOf(url), clipboard.copies); assertEquals("No browser opened, so the link was copied.", links.noteFor(url))
    }

    @Test fun copyPutsTheTextOnTheClipboardAndConfirmsItForTwoSeconds() {
        assertEquals(2000L, SCAN_COPIED_MS)
        val links = actions()
        links.copy(url)
        assertEquals(listOf(url), clipboard.copies); assertEquals("Copied", links.noteFor(url))
        assertEquals(emptyList(), opened, "copying never opens anything")
        // Another text for the same address (a card copies its fingerprint) is confirmed on that address.
        links.copy(url, FINGERPRINT)
        assertEquals(listOf(url, FINGERPRINT), clipboard.copies); assertEquals("Copied", links.noteFor(url))
    }

    @Test fun theConfirmationGoesAwayByItself() {
        val links = actions(copiedMillis = 40)
        links.copy(url)
        assertEquals("Copied", links.noteFor(url))
        awaitTrue("the confirmation to go away") { links.note == null }
    }

    @Test fun aCopyThatFailedIsNotConfirmedAndTheNoteStays() {
        clipboard.failing = true
        val links = actions(copiedMillis = 40)
        links.copy(url)
        assertEquals("Could not copy.", links.noteFor(url))
        assertEquals(emptyList(), clipboard.copies.toList())
        Thread.sleep(200)                                // well past the confirmation time
        assertEquals("Could not copy.", links.noteFor(url), "an error is not taken down by itself")
    }

    @Test fun theNextActionReplacesTheNoteAndClearForgetsIt() {
        val other = "https://192.0.2.1:443"
        val links = actions({ BrowserLaunch.Failed })
        links.open(url)
        assertNotNull(links.noteFor(url))
        links.copy(other)
        assertNull(links.noteFor(url)); assertEquals("Copied", links.noteFor(other))
        links.clear()
        assertNull(links.note)
        links.open(url); links.open(other)
        assertNull(links.noteFor(url)); assertNotNull(links.noteFor(other))
    }

    @Test fun aSecondOpenWhileTheFirstIsStillStartingIsIgnored() {
        val release = CountDownLatch(1)
        val finished = AtomicInteger()
        val links = actions({ release.await(5, TimeUnit.SECONDS); finished.incrementAndGet(); BrowserLaunch.Accepted }, io = Dispatchers.IO)
        links.open(url); links.open(url); links.open(url)
        // A different page is not held up by it.
        links.open("https://192.0.2.1:443")
        awaitTrue("both launches to start") { opened.size == 2 }
        release.countDown()
        awaitTrue("both launches to finish") { finished.get() == 2 }
        Thread.sleep(150)                                // the finished launches hand their result back
        assertEquals(1, opened.count { it == url }, "the repeated clicks started one launch")
        links.open(url)
        awaitTrue("the same page to open again") { opened.count { it == url } == 2 }
        assertEquals(1, opened.count { it == "https://192.0.2.1:443" })
    }

    @Test fun anOlderConfirmationNeverTakesDownANewerNote() {
        val release = CountDownLatch(1)
        val links = actions({ release.await(5, TimeUnit.SECONDS); BrowserLaunch.Failed }, copiedMillis = 300, io = Dispatchers.IO)
        links.open(url)                                  // still starting
        links.copy("https://192.0.2.1:443")              // its own confirmation, with a timer
        assertEquals("Copied", links.note?.text)
        release.countDown()
        awaitTrue("the launch result") { links.note?.text == "No browser opened, so the link was copied." }
        Thread.sleep(700)                                // well past the confirmation's timer
        assertEquals("No browser opened, so the link was copied.", links.noteFor(url), "the failure note stays until the next action")
    }

    @Test fun showcaseKeepsTheAddressOutOfTheSpokenLabel() {
        assertEquals("Open http://192.0.2.10:3000 in the browser", openLinkDescription(url, showcase = false))
        assertEquals("Open in the browser", openLinkDescription(url, showcase = true))
    }
}

/** The Scan page with its actions: service rows, Proxmox cards, keyboard, browser-hosted windows, layout and pictures. */
class ScanLinksSceneTest {
    private val width = 1280
    private val tall = 1700

    /** One Scan page in a scene, with a recording opener and a memory clipboard in place of the real ones. */
    private class Bench(context: CoroutineContext, width: Int, height: Int, hits: List<ScanHit>, web: Boolean = false, initialScroll: Int = 0, fontScale: Float = 1f) : AutoCloseable {
        val opened = CopyOnWriteArrayList<String>()
        val used = mutableListOf<ScanHit>()
        val clipboard = MemoryClipboard()
        @Volatile var launch = BrowserLaunch.Accepted
        private val job = SupervisorJob()
        val session = quietSession(CoroutineScope(Dispatchers.Unconfined + job))
        private var time = 0L
        val scene = ImageComposeScene(width, height, Density(1f, fontScale), coroutineContext = context) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides true, LocalAppHost provides AppHost(web), LocalClipboardManager provides clipboard) {
                    Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                        Box(Modifier.fillMaxSize().padding(16.dp)) { ScanScreen(session, emptyList(), initialScroll, opener = { opened += it; launch }) { used += it } }
                    }
                }
            }
        }
        init { setHits(hits) }

        @Suppress("UNCHECKED_CAST")
        private fun <T> state(name: String) = session.javaClass.getDeclaredField("$name\$delegate").apply { isAccessible = true }.get(session) as MutableState<T>
        fun setHits(hits: List<ScanHit>) { state<NetworkProgress?>("progress").value = NetworkProgress(PLAN, 254, hits = hits, finished = true) }
        fun setRunning(on: Boolean) { state<Boolean>("running").value = on }

        suspend fun frames(count: Int = 6) { repeat(count) { time += 50_000_000; scene.render(time).close(); yield() } }
        fun nodes(): List<SemanticsNode> = buildList {
            fun visit(n: SemanticsNode) { add(n); n.children.forEach(::visit) }
            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
        }
        fun texts(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        fun labelled(text: String) = nodes().filter { n -> texts(n).any { it.equals(text, ignoreCase = true) } }
        fun centreY(n: SemanticsNode) = (n.boundsInWindow.top + n.boundsInWindow.bottom) / 2
        /** The address of a service row, and a row's own button or note: the one on the same line as that address. */
        fun address(text: String) = labelled(text).single()
        fun rowButton(at: String, label: String): SemanticsNode { val a = address(at); return labelled(label).single { abs(centreY(it) - centreY(a)) < 10 } }
        fun onRow(at: String, text: String): List<SemanticsNode> { val a = address(at); return labelled(text).filter { abs(centreY(it) - centreY(a)) < 12 } }

        suspend fun click(node: SemanticsNode) {
            val at = node.boundsInWindow.center
            scene.sendPointerEvent(PointerEventType.Move, at); scene.sendPointerEvent(PointerEventType.Press, at); scene.sendPointerEvent(PointerEventType.Release, at)
            frames(3)
        }
        suspend fun click(label: String) = click(labelled(label).single())
        suspend fun key(k: Key) { scene.sendKeyEvent(KeyEvent(k, KeyEventType.KeyDown)); scene.sendKeyEvent(KeyEvent(k, KeyEventType.KeyUp)); frames(3) }
        fun focused(): SemanticsNode? = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }
        suspend fun tabTo(what: String, matches: (SemanticsNode) -> Boolean) {
            repeat(150) { key(Key.Tab); if (focused()?.let(matches) == true) return }
            error("Tab never reached $what; focus is on ${focused()?.let { texts(it) + (it.config.getOrNull(SemanticsProperties.ContentDescription) ?: emptyList()) }}")
        }
        /** The open, copy and note work runs on other threads and comes back to the window's own loop. */
        suspend fun until(what: String, timeoutMs: Long = 5000, condition: () -> Boolean) {
            val end = System.nanoTime() + timeoutMs * 1_000_000
            while (!condition()) { check(System.nanoTime() < end) { "Timed out waiting for $what" }; frames(2); delay(10) }
        }
        /** Lets a finished launch hand its result back, so the same page can be opened again (a repeated click is ignored until then). */
        suspend fun settle() { delay(60); frames(2) }
        fun accessibleText(): List<String> = nodes().flatMap { n -> texts(n) + n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        fun picture(folder: File, name: String) = scene.render(time + 16_000_000).use { image -> image.encodeToData()!!.use { File(folder, "$name.png").writeBytes(it.bytes) } }
        override fun close() { scene.close(); job.cancel() }
    }

    private suspend fun CoroutineScope.bench(hits: List<ScanHit>, web: Boolean = false, w: Int = width, h: Int = tall, scroll: Int = 0, fontScale: Float = 1f) =
        Bench(this.coroutineContext, w, h, hits, web, scroll, fontScale).also { it.frames() }

    @Test fun everyServiceRowOffersOpenAndCopyAndTheCardsKeepTheirOwnActions() = runSceneTest {
        val b = bench(listOf(proxmox(21), ADGUARD, ROUTER))
        try {
            for (hit in listOf(ADGUARD, ROUTER)) { val a = "${hit.ip}:${hit.port}"; b.rowButton(a, "Open"); b.rowButton(a, "Copy") }
            // The Proxmox hit is a card: it has no row button called plain "Open" or "Copy".
            assertEquals(2, b.labelled("Open").size); assertEquals(2, b.labelled("Copy").size)
            // Proxmox stays on top, services below.
            assertTrue(b.labelled("Copy fingerprint").single().boundsInWindow.bottom < b.address("192.0.2.10:3000").boundsInWindow.top)
        } finally { b.close() }
    }

    @Test fun copyPutsExactlyTheUrlOnTheClipboardAndConfirmsOnThatRowOnly() = runSceneTest {
        val b = bench(listOf(ADGUARD, ROUTER))
        try {
            b.click(b.rowButton("192.0.2.10:3000", "Copy"))
            assertEquals(listOf("http://192.0.2.10:3000"), b.clipboard.copies.toList())
            assertEquals(1, b.labelled("Copied").size)
            assertEquals(1, b.onRow("192.0.2.10:3000", "Copied").size, "the confirmation is on the row that was copied")
            // Another row: a TLS hit copies its https form, and the confirmation moves there.
            b.click(b.rowButton("192.0.2.1:443", "Copy"))
            assertEquals(listOf("http://192.0.2.10:3000", "https://192.0.2.1:443"), b.clipboard.copies.toList())
            assertEquals(1, b.labelled("Copied").size); assertEquals(1, b.onRow("192.0.2.1:443", "Copied").size)
            assertEquals(emptyList(), b.opened.toList(), "Copy never opens a browser")
        } finally { b.close() }
    }

    @Test fun theConfirmationLeavesAfterAboutTwoSeconds() = runSceneTest {
        val b = bench(listOf(ADGUARD))
        try {
            b.click(b.rowButton("192.0.2.10:3000", "Copy"))
            assertEquals(1, b.labelled("Copied").size)
            delay(500); b.frames()
            assertEquals(1, b.labelled("Copied").size, "still there after half a second")
            delay(2000); b.frames()
            assertEquals(0, b.labelled("Copied").size, "gone after about two seconds")
        } finally { b.close() }
    }

    @Test fun openHandsTheBrowserExactlyThatUrlAndNothingElse() = runSceneTest {
        val b = bench(listOf(ADGUARD, ROUTER))
        try {
            b.click(b.rowButton("192.0.2.10:3000", "Open")); b.until("the first open") { b.opened.size == 1 }
            assertEquals(listOf("http://192.0.2.10:3000"), b.opened.toList())
            b.click(b.rowButton("192.0.2.1:443", "Open")); b.until("the second open") { b.opened.size == 2 }
            assertEquals(listOf("http://192.0.2.10:3000", "https://192.0.2.1:443"), b.opened.toList())
            assertEquals(emptyList(), b.clipboard.copies.toList(), "a launch that worked copies nothing")
            assertEquals(0, b.labelled("Copied").size); assertTrue(b.accessibleText().none { "No browser" in it })
        } finally { b.close() }
    }

    @Test fun clickingTheAddressOpensItToo() = runSceneTest {
        val b = bench(listOf(ADGUARD))
        try {
            b.click(b.address("192.0.2.10:3000")); b.until("the address to open") { b.opened.size == 1 }
            assertEquals(listOf("http://192.0.2.10:3000"), b.opened.toList())
            assertEquals(emptyList(), b.clipboard.copies.toList())
            val link = b.address("192.0.2.10:3000")
            assertEquals(Role.Button, link.config.getOrNull(SemanticsProperties.Role))
            assertEquals(listOf("Open http://192.0.2.10:3000 in the browser"), link.config.getOrNull(SemanticsProperties.ContentDescription))
            assertNotNull(link.config.getOrNull(SemanticsActions.OnClick))
        } finally { b.close() }
    }

    @Test fun aFailedOpenCopiesTheLinkAndSaysSoOnThatRow() = runSceneTest {
        val b = bench(listOf(ADGUARD, ROUTER))
        try {
            b.launch = BrowserLaunch.Failed
            b.click(b.rowButton("192.0.2.10:3000", "Open")); b.until("the note") { b.labelled("No browser opened, so the link was copied.").isNotEmpty() }
            assertEquals(listOf("http://192.0.2.10:3000"), b.clipboard.copies.toList())
            assertEquals(1, b.onRow("192.0.2.10:3000", "No browser opened, so the link was copied.").size)
            // It stays until the next action, and the next action clears it.
            delay(2300); b.frames()
            assertEquals(1, b.labelled("No browser opened, so the link was copied.").size)
            b.click(b.rowButton("192.0.2.1:443", "Copy"))
            assertEquals(0, b.labelled("No browser opened, so the link was copied.").size); assertEquals(1, b.labelled("Copied").size)
            // A launch still running is said, not guessed, and copies nothing more.
            b.launch = BrowserLaunch.Pending; val copies = b.clipboard.copies.size
            b.click(b.rowButton("192.0.2.1:443", "Open")); b.until("the pending note") { b.labelled("Browser launch still running.").isNotEmpty() }
            assertEquals(copies, b.clipboard.copies.size)
        } finally { b.close() }
    }

    @Test fun aClipboardThatRefusesIsSaidNotHidden() = runSceneTest {
        val b = bench(listOf(ADGUARD))
        try {
            b.clipboard.failing = true
            b.click(b.rowButton("192.0.2.10:3000", "Copy"))
            assertEquals(emptyList(), b.clipboard.copies.toList())
            assertEquals(0, b.labelled("Copied").size); assertEquals(1, b.onRow("192.0.2.10:3000", "Could not copy.").size)
            // And a browser that cannot open the page, with a clipboard that refuses too, does not claim the link was copied.
            b.launch = BrowserLaunch.Failed
            b.click(b.rowButton("192.0.2.10:3000", "Open")); b.until("the note") { b.labelled("No browser opened, and the link could not be copied.").isNotEmpty() }
            assertEquals(0, b.labelled("No browser opened, so the link was copied.").size)
        } finally { b.close() }
    }

    @Test fun aNewScanStartsWithoutTheLastNote() = runSceneTest {
        val b = bench(listOf(ADGUARD))
        try {
            b.launch = BrowserLaunch.Failed
            b.click(b.rowButton("192.0.2.10:3000", "Open")); b.until("the note") { b.labelled("No browser opened, so the link was copied.").isNotEmpty() }
            b.setRunning(true); b.frames()
            assertEquals(0, b.labelled("No browser opened, so the link was copied.").size)
            b.setRunning(false); b.frames()
        } finally { b.close() }
    }

    @Test fun aBrowserHostedWindowHasCopyButNoOpen() = runSceneTest {
        val b = bench(listOf(proxmox(21), ADGUARD), web = true)
        try {
            assertEquals(0, b.labelled("Open").size); assertEquals(0, b.labelled("Web UI").size)
            val address = b.address("192.0.2.10:3000")
            assertNull(address.config.getOrNull(SemanticsActions.OnClick), "the address is plain text there")
            b.click(b.rowButton("192.0.2.10:3000", "Copy"))
            assertEquals(listOf("http://192.0.2.10:3000"), b.clipboard.copies.toList()); assertEquals(1, b.labelled("Copied").size)
            b.click(b.address("192.0.2.10:3000"))
            assertEquals(emptyList(), b.opened.toList())
            // The card keeps its own copies and Use; only the browser launch is gone.
            b.click("Copy address"); assertEquals("https://192.0.2.21:8006", b.clipboard.copies.last())
            b.click("Copy fingerprint"); assertEquals(FINGERPRINT, b.clipboard.copies.last())
            b.click("Use"); assertEquals(listOf(proxmox(21)), b.used)
        } finally { b.close() }
    }

    @Test fun theAddressAndBothButtonsWorkFromTheKeyboard() = runSceneTest {
        val b = bench(listOf(ADGUARD))
        try {
            b.tabTo("the address") { "192.0.2.10:3000" in b.texts(it) }
            assertEquals(Role.Button, b.focused()!!.config.getOrNull(SemanticsProperties.Role))
            b.key(Key.Enter); b.until("Enter on the address") { b.opened.size == 1 }; b.settle()
            b.key(Key.Tab); assertTrue(b.focused()!!.let { n -> b.texts(n).any { it.equals("Open", true) } }, "Tab goes from the address to Open")
            b.key(Key.Spacebar); b.until("Space on Open") { b.opened.size == 2 }
            b.key(Key.Tab); assertTrue(b.focused()!!.let { n -> b.texts(n).any { it.equals("Copy", true) } }, "then to Copy")
            b.key(Key.Enter)
            assertEquals(listOf("http://192.0.2.10:3000"), b.clipboard.copies.toList())
            b.key(Key.Spacebar)
            assertEquals(listOf("http://192.0.2.10:3000", "http://192.0.2.10:3000"), b.clipboard.copies.toList())
            assertEquals(listOf("http://192.0.2.10:3000", "http://192.0.2.10:3000"), b.opened.toList())
        } finally { b.close() }
    }

    @Test fun showcaseKeepsTheAddressOutOfTheLinksSpokenLabelAndItStillOpens() = runSceneTest {
        Appearance.preview(false, showcase = true)
        val b = bench(listOf(ADGUARD))
        try {
            val spoken = b.accessibleText()
            assertTrue(spoken.none { "192.0.2.10" in it }, "the address leaked: $spoken")
            val link = b.nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Open in the browser") }
            assertEquals(Role.Button, link.config.getOrNull(SemanticsProperties.Role))
            b.click(link); b.until("the hidden link to open") { b.opened.size == 1 }
            assertEquals(listOf("http://192.0.2.10:3000"), b.opened.toList())
            Appearance.preview(false, showcase = false); b.frames()
            assertTrue(b.accessibleText().any { "Open http://192.0.2.10:3000 in the browser" == it })
        } finally { Appearance.preview(false); b.close() }
    }

    @Test fun aProxmoxCardHasUseWebUiCopyFingerprintAndCopyAddressAndEachDoesWhatItSays() = runSceneTest {
        val hit = proxmox(21, saved = true)
        val b = bench(listOf(hit))
        try {
            for (label in listOf("Use", "Web UI", "Copy fingerprint", "Copy address")) assertEquals(1, b.labelled(label).size, label)
            assertEquals(0, b.labelled("Open").size, "the old Open and bare Copy are gone from cards")
            assertEquals(0, b.labelled("Copy").size)
            assertTrue(b.labelled("Copy fingerprint").single().config.getOrNull(SemanticsProperties.Disabled) == null)
            b.click("Web UI"); b.until("the card's page to open") { b.opened.size == 1 }
            assertEquals(listOf("https://192.0.2.21:8006"), b.opened.toList())
            assertEquals(emptyList(), b.clipboard.copies.toList())
            b.click("Copy address")
            assertEquals(listOf("https://192.0.2.21:8006"), b.clipboard.copies.toList()); assertEquals(1, b.labelled("Copied").size)
            b.click("Copy fingerprint")
            assertEquals(listOf("https://192.0.2.21:8006", FINGERPRINT), b.clipboard.copies.toList()); assertEquals(1, b.labelled("Copied").size)
            assertEquals(1, b.opened.size, "copying opens nothing")
            assertEquals(emptyList(), b.used, "nothing connects by itself")
            b.click("Use"); assertEquals(listOf(hit), b.used)
            assertEquals(1, b.opened.size)
        } finally { b.close() }
    }

    @Test fun aCardWithoutACertificateCannotCopyANonexistentFingerprint() = runSceneTest {
        val b = bench(listOf(proxmox(23, fingerprint = null)))
        try {
            val copy = b.labelled("Copy fingerprint").single()
            assertNotNull(copy.config.getOrNull(SemanticsProperties.Disabled))
            b.click(copy)
            assertEquals(emptyList(), b.clipboard.copies.toList()); assertEquals(0, b.labelled("Copied").size)
            b.click("Copy address"); assertEquals(listOf("https://192.0.2.23:8006"), b.clipboard.copies.toList())
        } finally { b.close() }
    }

    @Test fun aBackupServerCardOffersItsPageInPlaceOfUse() = runSceneTest {
        val b = bench(listOf(proxmox(90, ScanKind.PBS, "backup")))
        try {
            assertEquals(0, b.labelled("Use").size)
            for (label in listOf("Web UI", "Copy address", "Copy fingerprint")) assertEquals(1, b.labelled(label).size, label)
            assertEquals(1, b.labelled("Found on the network. RingMASTER does not connect to Backup Server yet.").size)
            b.launch = BrowserLaunch.Failed
            b.click("Web UI"); b.until("the card's note") { b.labelled("No browser opened, so the link was copied.").isNotEmpty() }
            assertEquals(listOf("https://192.0.2.90:8007"), b.opened.toList()); assertEquals(listOf("https://192.0.2.90:8007"), b.clipboard.copies.toList())
            assertEquals(1, b.labelled("Found on the network. RingMASTER does not connect to Backup Server yet.").size, "the standing line is not replaced")
        } finally { b.close() }
    }

    @Test fun onlyTheHitsOwnHttpOrHttpsOriginIsEverHandedToTheBrowser() = runSceneTest {
        val hits = listOf(proxmox(21), proxmox(40, ScanKind.PDM), proxmox(90, ScanKind.PBS), ADGUARD, ROUTER, ScanHit("192.0.2.201", 80, false, ScanKind.WEB, "Printer"))
        val b = bench(hits)
        try {
            for ((n, hit) in hits.filter { !it.proxmox }.withIndex()) {
                val at = "${hit.ip}:${hit.port}"
                b.click(b.rowButton(at, "Open")); b.until("the button's open") { b.opened.size == 2 * n + 1 }; b.settle()
                b.click(b.address(at)); b.until("the address's open") { b.opened.size == 2 * n + 2 }; b.settle()
            }
            assertEquals(hits.filter { !it.proxmox }.flatMap { listOf(it.origin, it.origin) }, b.opened.toList())
            for (url in b.opened) assertTrue(Regex("^https?://\\d{1,3}(\\.\\d{1,3}){3}:\\d{1,5}$").matches(url), url)
        } finally { b.close() }
    }

    private data class Case(val label: String, val w: Int, val h: Int, val fontScale: Float = 1f, val wraps: Boolean = false)

    private val mixed = listOf(
        proxmox(21, name = "pve-east", saved = true, fingerprint = fingerprintOf(21)), proxmox(22, name = "pve-west", fingerprint = fingerprintOf(22)), proxmox(23, name = "pve-lab", fingerprint = null),
        proxmox(40, ScanKind.PDM, "pdm", fingerprint = fingerprintOf(40)), proxmox(90, ScanKind.PBS, "backup", fingerprint = fingerprintOf(90)),
        ROUTER, ScanHit("192.0.2.53", 80, false, ScanKind.PIHOLE, "Pi-hole"), ScanHit("192.0.2.123", 8123, false, ScanKind.HOME_ASSISTANT, "Home Assistant"),
        ADGUARD, ScanHit("192.0.2.201", 80, false, ScanKind.WEB), ScanHit("192.0.2.240", 443, true, ScanKind.WEB, "NAS with a long product name and firmware banner"))

    @Test fun nothingClipsOverlapsOrMisalignsAtTheWidthsTheWindowCanHave() = runSceneTest {
        val folder = System.getenv("RM_FIELD_SHOTS")?.let { File(it).apply { mkdirs() } }
        // 960: the page at 960 px content width (one column of cards, so a taller scene). 1332: the narrowest three-column page,
        // cards about 400 px wide inside. 150 % text on a wide window: the columns still line up. 752: the smallest window beside
        // the open rail (720 px of page): a row may wrap there, depending on the platform's monospace font, so only the checks
        // that hold either way are made.
        val cases = listOf(Case("960-content", 992, 2400), Case("1280", 1280, tall), Case("1332-narrowest-3col", 1332, tall), Case("1920", 1920, tall),
            Case("text-150", 1280, 2600, fontScale = 1.5f), Case("smallest-window", 752, 2600, wraps = true))
        for ((label, w, h, fontScale, wraps) in cases) {
            val b = bench(mixed, w = w, h = h, fontScale = fontScale)
            try {
                // The picture first: a failing size still leaves the evidence.
                folder?.let { b.picture(it, "scan-actions-full-$label") }
                val actions = b.nodes().filter { n -> b.texts(n).any { it.lowercase() in ACTIONS } }
                for (n in actions) assertFalse(n.boundsInWindow.isEmpty, "$label: ${b.texts(n)} was never placed, the scene is too short")
                // 6 service rows -> 6 each of Open and Copy; 5 cards -> 5 each of Web UI, Copy address and Copy fingerprint, and 4 Use (three PVE, one PDM; the Backup Server has none).
                for ((name, count) in mapOf("open" to 6, "copy" to 6, "web ui" to 5, "copy address" to 5, "copy fingerprint" to 5, "use" to 4))
                    assertEquals(count, actions.count { n -> b.texts(n).any { it.equals(name, true) } }, "$label: $name")
                for (n in actions) assertTrue(n.boundsInWindow.left >= 0f && n.boundsInWindow.right <= w, "$label: ${b.texts(n)} leaves the window: ${n.boundsInWindow}")
                for (i in actions.indices) for (j in i + 1 until actions.size)
                    assertFalse(actions[i].boundsInWindow.overlaps(actions[j].boundsInWindow), "$label: ${b.texts(actions[i])} overlaps ${b.texts(actions[j])}")
                // Every service row lines up: the Open buttons share one column, the Copy buttons another, the addresses a third.
                val opens = b.labelled("Open"); val copies = b.labelled("Copy")
                val addresses = b.nodes().filter { n -> b.texts(n).any { Regex("^192\\.0\\.2\\.\\d+:\\d+$").matches(it) } }
                assertEquals(6, addresses.size)
                if (!wraps) {
                    assertEquals(1, opens.map { it.boundsInWindow.left.toInt() }.distinct().size, "$label: Open buttons are not in one column: ${opens.map { it.boundsInWindow }}")
                    assertEquals(1, copies.map { it.boundsInWindow.left.toInt() }.distinct().size, "$label: Copy buttons are not in one column: ${copies.map { it.boundsInWindow }}")
                    assertEquals(1, addresses.map { it.boundsInWindow.left.toInt() }.distinct().size, "$label: addresses are not in one column: ${addresses.map { it.boundsInWindow }}")
                    for (row in addresses) assertTrue(opens.any { abs(b.centreY(it) - b.centreY(row)) < 10 }, "$label: a row has no Open on its line")
                }
                // The layout audit finds nothing cut or crushed among the new controls and the addresses.
                val cut = auditScene(b.scene).filter { hit -> hit.label.lowercase() in ACTIONS || "192.0.2." in hit.label || hit.label == "Copied" }
                assertEquals(emptyList(), cut.filter { it.kind != AuditKind.Ellipsis || "192.0.2." in it.label }, "$label:\n" + cut.describe())
                // Cards above, services below.
                assertTrue(b.labelled("Copy fingerprint").maxOf { it.boundsInWindow.bottom } < addresses.minOf { it.boundsInWindow.top }, "$label: services must stay below the cards")
            } finally { b.close() }
        }
    }

    @Test fun picturesAtTheCommonWindowSizesAndWithNotesShowing() = runSceneTest {
        val folder = System.getenv("RM_FIELD_SHOTS")?.let { File(it).apply { mkdirs() } } ?: return@runSceneTest
        for ((w, h) in listOf(1280 to 720, 1920 to 1080)) {
            for ((scroll, label) in listOf(0 to "top", 100_000 to "services")) {
                val b = bench(mixed, w = w, h = h, scroll = scroll)
                try { b.picture(folder, "scan-actions-${w}x$h-$label") } finally { b.close() }
            }
        }
        val b = bench(mixed, w = 1280, h = tall)
        try {
            b.click(b.rowButton("192.0.2.10:3000", "Copy")); b.picture(folder, "scan-actions-note-copied-row")
            b.click(b.labelled("Copy address").first()); b.picture(folder, "scan-actions-note-copied-card")
            b.launch = BrowserLaunch.Failed
            val failed = "No browser opened, so the link was copied."
            b.click(b.rowButton("192.0.2.1:443", "Open")); b.until("the row's note") { b.labelled(failed).isNotEmpty() }; b.frames(); b.picture(folder, "scan-actions-note-failed-row")
            b.click(b.labelled("Web UI").first()); b.until("the card's note") { b.labelled(failed).isNotEmpty() }; b.frames(); b.picture(folder, "scan-actions-note-failed-card")
        } finally { b.close() }
        // The pointer resting on an address: underline and amber text, and after the tooltip delay the tip.
        val hover = bench(mixed, w = 1280, h = tall)
        try {
            hover.scene.sendPointerEvent(PointerEventType.Move, hover.address("192.0.2.10:3000").boundsInWindow.center)
            hover.frames(); hover.frames()
            hover.picture(folder, "scan-actions-hover-address")
        } finally { hover.close() }
        // The keyboard focus mark on an address, and a note, in each look (the focus ring and the link underline).
        for ((theme, name) in listOf(ThemeMode.Dark to "dark", ThemeMode.Light to "light", ThemeMode.HighContrast to "high-contrast")) {
            Appearance.preview(theme)
            val t = bench(mixed, w = 1280, h = 1500)
            try {
                t.tabTo("an address") { n -> t.texts(n).any { Regex("^192\\.0\\.2\\.\\d+:\\d+$").matches(it) } }
                t.picture(folder, "scan-actions-focus-$name")
                t.click(t.rowButton("192.0.2.1:443", "Copy")); t.picture(folder, "scan-actions-copied-$name")
            } finally { Appearance.preview(false); t.close() }
        }
    }
}
