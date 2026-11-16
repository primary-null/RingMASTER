@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.*
import local.ringleader.core.*

/**
 * Scan page audit, 2026-11-16, inverted: what happens to a scanned certificate offer on the Desk, in a window that behaves as the
 * app's does. Main.kt puts a picked result into the Connect form when it is picked and forgets the pick when the offer is over
 * (cancelled, edited away or used); the rig below does the same, with a clock the test moves, so five minutes do not take five minutes.
 *
 * The trust contract says "Scan cancellation or address/port/kind editing permanently invalidates its offer" and "An offer expires five
 * minutes after the Desk receives it". Audit findings F-O1 (a cancelled offer came back after leaving the Desk), F-O2 (a typed
 * address was replaced by the old scan result) and F-O3 (the lapse was invisible until a click) are tests of the right behaviour here.
 */
class ScanOfferReentryAuditTest {
    private val hit = ScanHit("pve.example.test", 8006, true, ScanKind.PVE, fingerprint = "ab".repeat(32))

    /** The Desk as the window holds it: the pick and its time live outside the page, and the page tells the window when the offer is over. */
    internal class Rig(scope: CoroutineScope, context: CoroutineContext, passwordMode: Boolean = false) : AutoCloseable {
        val root: Path = Files.createTempDirectory("scan-offer")
        val workspace = Workspace(scope)
        val desk = ProfileDesk(workspace, ProfileStore(root.resolve("profiles.json")))
        val motion = MotionPreferences(root.resolve("motion")).also { if (passwordMode) it.put(DESK_PASSWORD_MODE, true) }
        var pick by mutableStateOf<ScanHit?>(null)
        var pickedAt by mutableStateOf<Long?>(null)
        var shown by mutableStateOf(true)
        var ended = 0
        var calls = 0
        fun pick(h: ScanHit) { pick = h; pickedAt = ScanOfferClock.now() }
        val scene = ImageComposeScene(1600, 900, coroutineContext = context) {
            val state by desk.state.collectAsState()
            remember(pickedAt) { pick?.let { applyScanPick(desk, it) } }
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                if (shown) DeskScreen(workspace.state.value, state, desk, scanned = pick, scannedAt = pickedAt, onScannedEnded = { ended++; pick = null; pickedAt = null }, motion = motion) {}
            } }
        }
        val ui = CreateScene(scene)
        init { desk.router = { _, _ -> calls++; error("Offline route reached") } }
        fun texts(): List<String> = ui.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
        fun has(text: String) = ui.nodes().any { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.equals(text, ignoreCase = true) } == true }
        fun editable() = ui.nodes().filter { it.config.getOrNull(SemanticsActions.SetText) != null }
        fun addressText() = editable().first().config.getOrNull(SemanticsProperties.EditableText)?.text
        suspend fun type(index: Int, value: String) { editable()[index].config[SemanticsActions.SetText].action!!(AnnotatedString(value)); ui.frames() }
        suspend fun leaveAndReturn() { shown = false; ui.frames(); shown = true; ui.frames() }
        /** Frames and short waits until [condition], for the ticker that runs in real time. */
        suspend fun until(what: String, timeoutMs: Long = 5000, condition: () -> Boolean) {
            val end = System.nanoTime() + timeoutMs * 1_000_000
            while (!condition()) { check(System.nanoTime() < end) { "Timed out waiting for $what: ${texts()}" }; ui.frames(); delay(15) }
        }
        override fun close() { scene.close(); desk.close(); workspace.close(); root.toFile().deleteRecursively() }
    }

    private suspend fun withClock(block: suspend (setSeconds: (Long) -> Unit) -> Unit) {
        var nanos = 1_000_000_000_000L
        val before = ScanOfferClock.now; val tick = ScanOfferClock.tickMillis
        ScanOfferClock.now = { nanos }; ScanOfferClock.tickMillis = 20
        try { block { seconds -> nanos = 1_000_000_000_000L + seconds * 1_000_000_000L } } finally { ScanOfferClock.now = before; ScanOfferClock.tickMillis = tick }
    }

    /** F-O1, inverted: cancelling the scanned certificate is for good; leaving the Desk and coming back does not bring the offer back. */
    @Test fun audit_o1_cancellingTheScannedCertificateIsForGoodAndSurvivesLeavingAndReturning() = runSceneTest {
        val rig = Rig(this, coroutineContext)
        try {
            rig.pick(hit); rig.ui.frames()
            rig.ui.text("Trust and connect")
            rig.ui.click("Cancel scanned certificate")
            assertFails { rig.ui.text("Trust and connect") }                          // cancelled: the offer is gone
            assertEquals(1, rig.ended); assertNull(rig.pick, "the window forgot the pick")
            rig.leaveAndReturn()                                                       // another page, and the Desk again
            assertFails { rig.ui.text("Trust and connect") }                          // still gone
            assertFalse(rig.has("Cancel scanned certificate")); assertTrue(rig.texts().none { it.startsWith("Observed SHA-256") })
            assertEquals(hit.origin, rig.addressText(), "the address stays, as an ordinary address")
            // A new pick of the same result is a new offer with its own five minutes.
            rig.pick(hit); rig.ui.frames(); rig.ui.text("Trust and connect")
        } finally { rig.close() }
    }

    /** F-O2, inverted: an address typed after picking a scan result is the person's own and is not replaced when the Desk is entered again. */
    @Test fun audit_o2_aTypedAddressIsKeptWhenTheDeskIsEnteredAgain() = runSceneTest {
        val rig = Rig(this, coroutineContext)
        try {
            rig.pick(hit); rig.ui.frames()
            assertEquals(hit.origin, rig.addressText())
            rig.type(0, "https://other.example.test:8006")
            assertEquals("https://other.example.test:8006", rig.addressText())
            assertEquals("https://other.example.test:8006", rig.desk.manualForm.address)
            assertEquals(1, rig.ended, "editing the address ends the offer"); assertFails { rig.ui.text("Trust and connect") }
            rig.leaveAndReturn()
            assertEquals("https://other.example.test:8006", rig.addressText())
            assertFails { rig.ui.text("Trust and connect") }
        } finally { rig.close() }
    }

    /**
     * F-O3, inverted, with a clock the test moves. The five minutes pass while the Desk is on screen and nothing is touched: the
     * "Scanned certificate" block, its Trust button and Cancel go away at that moment, and the amber line says why. Nothing is sent.
     */
    @Test fun audit_o3_theOfferLapsesOnScreenAtTheMomentItDoesAndSaysSo() = runSceneTest {
        withClock { setSeconds ->
            for (typeFirst in listOf(true, false)) {
                setSeconds(0)                                                  // each round starts at the same time of day
                val rig = Rig(this, coroutineContext, passwordMode = true)
                try {
                    rig.pick(hit); rig.ui.frames()
                    if (typeFirst) rig.type(2, "test-password")
                    rig.ui.text("Trust and connect")
                    setSeconds(305)                                            // five minutes and five seconds, nothing touched
                    rig.until("the approval to go") { !rig.has("Trust and connect") }
                    assertFalse(rig.has("Cancel scanned certificate"), "no consent block is left")
                    assertTrue(rig.texts().none { it.startsWith("Observed SHA-256") || it.startsWith("Compare with the server's fingerprint") })
                    assertTrue(rig.has("The scanned certificate is more than five minutes old. Check the server certificate again before trusting it."), rig.texts().toString())
                    rig.ui.text("Check server certificate")
                    assertEquals(0, rig.calls, "nothing was sent"); assertEquals(0, rig.ended, "a lapse is not the end of the pick: the amber line stays when the Desk is entered again")
                    rig.leaveAndReturn()
                    assertTrue(rig.has("The scanned certificate is more than five minutes old. Check the server certificate again before trusting it."))
                    assertFalse(rig.has("Trust and connect"))
                } finally { rig.close() }
            }
        }
    }
}
