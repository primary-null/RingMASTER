@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import local.ringleader.core.*
import java.io.File
import kotlin.test.*

/** What Fleet shows after a refused or expired sign-in closed the session, and what the same page shows before any connection. */
class ConnectionFailedCardTest {
    private val refused = "The server refused this user name or password."

    @Test fun aClosedSessionSaysWhyAndWhichServerAndOffersTheWayBack() = runSceneTest {
        val workspace = Workspace(this)
        var desk = 0
        var state by mutableStateOf(WorkspaceState(error = refused, failedAddress = "https://pve.example.test:8006"))
        val scene = ImageComposeScene(1280, 720, coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    Box(Modifier.fillMaxSize().background(Tech.Canvas).padding(16.dp)) { InventoryScreen(state, workspace, null, "", {}, "All", {}, { desk++ }, {}) }
                }
            }
        }
        val ui = CreateScene(scene)
        try {
            ui.frames()
            ui.text("Connection failed"); ui.text("https://pve.example.test:8006"); ui.text("Export diagnostics…")
            // The reason is said once, on the card.
            assertEquals(1, ui.nodes().count { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == refused } == true })
            // Nothing claims a live connection.
            assertFails { ui.text("Open your infrastructure workspace") }
            System.getenv("RM_FIELD_SHOTS")?.let { folder -> scene.render(900_000_000).use { img -> img.encodeToData()!!.use { File(folder, "connection-failed-1280x720.png").writeBytes(it.bytes) } } }
            ui.click("Return to Desk")
            assertEquals(1, desk)
            // Before any connection the page is the plain invitation, not a failure.
            state = WorkspaceState(); ui.frames()
            ui.text("Open your infrastructure workspace")
            assertFails { ui.text("Connection failed") }
        } finally { scene.close(); workspace.close() }
    }

    private fun card(state: () -> WorkspaceState, workspace: Workspace, others: Boolean = false, onDesk: () -> Unit = {}, context: kotlin.coroutines.CoroutineContext) =
        ImageComposeScene(1280, 720, coroutineContext = context) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides true, LocalOtherSessionsOpen provides others) {
                    Box(Modifier.fillMaxSize().background(Tech.Canvas).padding(16.dp)) { InventoryScreen(state(), workspace, null, "", {}, "All", {}, onDesk, {}) }
                }
            }
        }

    /**
     * Field report, 0.25.9: a second server opened with normal TLS. The session stayed "connected" with no inventory, and
     * Fleet showed the invitation panel under a small red line and a retry status. It is a failed connection and reads as one.
     */
    @Test fun aRefusedCertificateOnTheFirstReadShowsTheFailureCardNotAnEmptyWorkspace() = runSceneTest {
        val workspace = Workspace(this)
        var desk = 0
        val state = WorkspaceState(connected = true, address = "https://pve-b.example.test:8006", account = "root@pam",
            error = NetworkFailure.Untrusted.sentence, failure = NetworkFailure.Untrusted, live = true, liveBackoffSeconds = 10)
        assertTrue(connectionFailed(state))
        val scene = card({ state }, workspace, others = true, onDesk = { desk++ }, context = coroutineContext)
        val ui = CreateScene(scene)
        try {
            ui.frames()
            ui.text("Connection failed"); ui.text("https://pve-b.example.test:8006"); ui.text("User tried: root@pam")
            // The reason once, then what to do about it.
            assertEquals(1, ui.nodes().count { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == NetworkFailure.Untrusted.sentence } == true })
            assertTrue("Trust and connect" in connectionFailureAdvice(state)!! && "normal TLS" in connectionFailureAdvice(state)!!)
            ui.text(connectionFailureAdvice(state)!!)
            ui.text("Your other open connections are not affected.")
            ui.text("Trying again in the background, every 10 s.")
            ui.text("Try again"); ui.text("Export diagnostics…")
            assertFails { ui.text("Open your infrastructure workspace") }
            System.getenv("RM_FIELD_SHOTS")?.let { folder -> scene.render(900_000_000).use { img -> img.encodeToData()!!.use { File(folder, "connection-failed-certificate-1280x720.png").writeBytes(it.bytes) } } }
            ui.click("Return to Desk")
            assertEquals(1, desk)
        } finally { scene.close(); workspace.close() }
    }

    /** Network audit W4: each of the eleven causes has its own sentence and its own next step on the card, in every theme. */
    @Test fun everyCauseOnTheFailureCardSaysItsOwnSentenceAndNextStep() = runSceneTest {
        val workspace = Workspace(this)
        var state by mutableStateOf(WorkspaceState())
        val scene = card({ state }, workspace, context = coroutineContext)
        val ui = CreateScene(scene)
        try {
            val causes = NetworkFailure.examples(java.time.Instant.parse("2026-11-16T09:12:00Z"))
            for (failure in causes) {
                state = WorkspaceState(connected = true, address = "https://pve-b.example.test:8006", account = "root@pam", error = failure.sentence, failure = failure)
                ui.frames()
                ui.text("Connection failed"); ui.text(failure.sentence); ui.text(failure.nextStep)
                assertEquals(failure.nextStep, connectionFailureAdvice(state))
                saveTrustShot(scene, "card-${failure::class.simpleName}")
            }
            for ((theme, apply) in trustShotThemes) {
                apply()
                for (failure in listOf(causes[1], causes[3])) {
                    state = WorkspaceState(connected = true, address = "https://pve-b.example.test:8006", account = "root@pam", error = failure.sentence, failure = failure)
                    ui.frames()
                    ui.text(failure.sentence)
                    saveTrustShot(scene, "card-${failure::class.simpleName}-$theme")
                }
            }
            // Showcase hides the server and the account; the sentences carry neither.
            Appearance.preview(ThemeMode.Dark, showcase = true)
            ui.frames()
            assertFails { ui.text("https://pve-b.example.test:8006") }
            assertFails { ui.text("User tried: root@pam") }
        } finally { Appearance.preview(false); scene.close(); workspace.close() }
    }

    @Test fun aRefusedPasswordNamesTheUserThatWasSentAndWhatToCheck() = runSceneTest {
        val workspace = Workspace(this)
        val state = WorkspaceState(error = LOGIN_REFUSED, failedAddress = "https://pve-b.example.test:8006", account = "root@pam")
        val scene = card({ state }, workspace, context = coroutineContext)
        val ui = CreateScene(scene)
        try {
            ui.frames()
            ui.text("Connection failed"); ui.text("User tried: root@pam"); ui.text(connectionFailureAdvice(state)!!)
            // A closed session has nothing to try again with (the secret is gone), and with one connection nothing else to mention.
            assertFails { ui.text("Try again") }
            assertFails { ui.text("Your other open connections are not affected.") }
            System.getenv("RM_FIELD_SHOTS")?.let { folder -> scene.render(900_000_000).use { img -> img.encodeToData()!!.use { File(folder, "connection-failed-password-1280x720.png").writeBytes(it.bytes) } } }
        } finally { scene.close(); workspace.close() }
    }
}
