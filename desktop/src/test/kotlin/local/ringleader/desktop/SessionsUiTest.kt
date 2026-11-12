package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import local.ringleader.core.*
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fleet list's clicks and the desk and rail with several connections open, driven through rendered scenes.
 * Scenes run in the test's own event loop (runBlocking) with a yield after each frame, on one thread as in the app. On the
 * default Unconfined context an effect could resume on the AWT thread while this thread laid out or closed the scene,
 * and Compose then threw an IllegalArgumentException (seen on a Linux build runner, at scene.close()).
 */
class SessionsUiTest {
    private class FakeGateway : InventoryGateway {
        override suspend fun overview(connection: Connection) = Overview(1, listOf(Guest(connection.remoteKey, 100, "alpha", "node-a", "VM", "running")))
        override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
    }

    @Test fun aDoubleClickOpensTheGuestAndAClickOnTheSelectedRowReadsNothingAgain() = runBlocking<Unit> {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = Workspace(scope)
        workspace.exploreDemo(); workspace.setLive(false)
        val scene = ImageComposeScene(1400, 900, Density(1f), coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                    val state by workspace.state.collectAsState()
                    Box(Modifier.fillMaxSize().padding(16.dp)) { InventoryScreen(state, workspace, null, "", {}, "All", {}, {}, {}) }
                }
            } }
        }
        try {
            var time = 0L
            suspend fun settle() { repeat(3) { time += 16_000_000L; scene.render(time).close(); yield() } }
            suspend fun click(at: Offset) { scene.sendPointerEvent(PointerEventType.Move, at); scene.sendPointerEvent(PointerEventType.Press, at); scene.sendPointerEvent(PointerEventType.Release, at); settle() }
            settle()
            // Find the row of a stopped guest: walk down the list one click at a time, slower than a double-click.
            val pause = doubleClickMillis() + 60
            var row: Offset? = null
            for (y in 150 until 860 step 18) {
                Thread.sleep(pause)
                click(Offset(300f, y.toFloat()))
                val picked = workspace.state.value.selected
                if (picked != null && picked.status != "running" && !picked.template) { row = Offset(300f, y.toFloat()); break }
            }
            val at = assertNotNull(row, "no stopped guest row was found in the list")
            val guest = workspace.state.value.selected!!
            assertNull(workspace.state.value.inspection, "single clicks only select")
            // A slow second click on the selected row: nothing is read again and nothing opens.
            val readAt = workspace.state.value.detailsUpdatedAt
            Thread.sleep(pause); click(at)
            assertEquals(readAt, workspace.state.value.detailsUpdatedAt, "the selected row was read again")
            assertNull(workspace.state.value.inspection)
            // Two quick clicks: the guest's page opens (its inspection is requested) and the selection stays.
            Thread.sleep(pause); click(at); click(at)
            assertEquals(guest.resource, workspace.state.value.selected?.resource)
            assertEquals(guest.resource, workspace.state.value.inspection?.resource, "the double-click did not open the guest")
            assertEquals(readAt, workspace.state.value.detailsUpdatedAt, "the double-click read the details again")
        } finally { scene.close(); workspace.close(); scope.cancel() }
    }

    @Test fun theDeskAndTheRailListEveryOpenConnection() = runBlocking<Unit> {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val directory = Files.createTempDirectory("sessions-ui")
        val sessions = Sessions(scope) { Workspace(scope, FakeGateway()) }
        val desk = ProfileDesk(sessions.state.value.active.workspace, ProfileStore(directory.resolve("profiles.json"))).also(sessions::bind)
        try {
            desk.beginCreate(); desk.updateDraft("Lab cluster", "https://pve.example.test:8006", "tester@pve!read", ""); assertTrue(desk.saveDraft())
            desk.setProfileSecret("session-secret-value"); assertTrue(desk.connectSelected())
            desk.setManualSecret("session-secret-value"); assertTrue(desk.connectManual("https://192.0.2.15:8006", "root@pam", ""))
            assertTrue(desk.exploreDemo())
            val open = sessions.state.value
            assertEquals(3, open.open.size)
            for ((name, expanded) in listOf("expanded" to true, "collapsed" to false)) {
                val scene = ImageComposeScene(1500, 900, Density(1f), coroutineContext = coroutineContext) {
                    MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                        Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                            val all by sessions.state.collectAsState()
                            val deskState by desk.state.collectAsState()
                            val state by all.active.workspace.state.collectAsState()
                            Row {
                                NavigationRail(state, "Desk", "Test", all.active.workspace, initialExpanded = expanded, sessions = all.open, activeSession = all.activeId, profiles = deskState.profiles, onSession = sessions::show) {}
                                Box(Modifier.weight(1f).padding(16.dp)) {
                                    DeskScreen(state, deskState, desk, sessions = all.open, activeSession = all.activeId, sessionNotice = all.notice, onShowSession = sessions::show, onDisconnectSession = sessions::disconnect) {}
                                }
                            }
                        }
                    } }
                }
                try {
                    var time = 0L
                    suspend fun frame() = run { time += 16_000_000L; scene.render(time).also { yield() } }
                    repeat(3) { frame().close() }
                    val file = File("build/probe/sessions-$name.png"); file.parentFile.mkdirs()
                    frame().use { file.writeBytes(it.encodeToData()!!.bytes) }
                    if (expanded) {
                        // The desk shows a card per connection; Disconnect on the first card (not in view) closes only that one.
                        val first = open.open.first()
                        sessions.disconnect(first.id); repeat(3) { frame().close() }
                        assertEquals(open.open.drop(1).map { it.id }, sessions.state.value.open.map { it.id })
                        assertTrue(sessions.state.value.active.workspace.state.value.demo)
                    }
                } finally { scene.close() }
            }
        } finally { desk.close(); sessions.close(); scope.cancel(); directory.toFile().deleteRecursively() }
    }
}
