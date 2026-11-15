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
}
