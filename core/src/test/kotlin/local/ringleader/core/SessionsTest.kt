package local.ringleader.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Several connections open at once: a new one never replaces an open one, and the desk follows the one in view. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsTest {
    private val first = "https://pve-a.example.test:8006"
    private val second = "https://pve-b.example.test:8006"
    private val token = "tester@pve!read"
    private val secret = "session-secret-value"

    private class Fixture(val sessions: Sessions, val desk: ProfileDesk, val gateway: FakeGateway, val directory: Path) {
        val state get() = sessions.state.value
        fun connect(origin: String, user: String = "tester@pve!read"): Boolean { desk.setManualSecret("session-secret-value"); return desk.connectManual(origin, user, "") }
        fun addresses() = state.open.map { it.workspace.state.value.address }
        fun close() { desk.close(); sessions.close(); Files.walk(directory).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }

    private fun TestScope.fixture(): Fixture {
        val directory = Files.createTempDirectory("ringmaster-sessions")
        val gateway = FakeGateway()
        val sessions = Sessions(this) { Workspace(this, gateway) }
        val desk = ProfileDesk(sessions.state.value.active.workspace, ProfileStore(directory.resolve("profiles.json"))).also(sessions::bind)
        return Fixture(sessions, desk, gateway, directory)
    }

    @Test fun aSecondConnectionOpensBesideTheFirst() = runTest {
        val f = fixture()
        try {
            assertEquals(1, f.state.open.size)
            assertTrue(f.connect(first)); runCurrent()
            assertEquals(1, f.state.open.size, "the empty session in view is used for the first connection")
            val one = f.state.active
            assertTrue(f.connect(second)); runCurrent()
            assertEquals(listOf(first, second), f.addresses())
            assertTrue(one.workspace.state.value.connected, "the first connection stays open")
            assertEquals(first, one.workspace.state.value.overview!!.guests.single().remote)
            assertEquals(second, f.state.active.workspace.state.value.address)
            assertFalse(one.workspace.inView); assertTrue(f.state.active.workspace.inView)
        } finally { f.close() }
    }

    @Test fun theSameServerAndAccountReusesItsSession() = runTest {
        val f = fixture()
        try {
            f.connect(first); runCurrent(); f.connect(second); runCurrent()
            val ids = f.state.open.map { it.id }
            assertTrue(f.connect(first)); runCurrent()
            assertEquals(ids, f.state.open.map { it.id })
            assertEquals(ids.first(), f.state.activeId)
            // Another account on the same server is its own session.
            assertTrue(f.connect(first, "root@pam")); runCurrent()
            assertEquals(listOf(first, second, first), f.addresses())
        } finally { f.close() }
    }

    @Test fun theDemoOpensBesideAConnectionAndOnlyOnce() = runTest {
        val f = fixture()
        try {
            f.connect(first); runCurrent()
            assertTrue(f.desk.exploreDemo()); runCurrent()
            assertEquals(2, f.state.open.size)
            assertTrue(f.state.active.workspace.state.value.demo)
            assertTrue(f.state.open.first().workspace.state.value.connected)
            f.sessions.show(f.state.open.first().id)
            assertTrue(f.desk.exploreDemo())
            assertEquals(2, f.state.open.size)
            assertTrue(f.state.active.workspace.state.value.demo)
        } finally { f.close() }
    }

    @Test fun aFailedFirstReadDoesNotKeepASession() = runTest {
        val f = fixture()
        try {
            f.connect(first); runCurrent()
            f.gateway.fail = second
            assertTrue(f.connect(second)); runCurrent()
            val failed = f.state.active
            assertNotNull(failed.workspace.state.value.error)
            assertFalse(sessionOpen(failed.workspace.state.value))
            // The next connection takes the failed session's place instead of adding a third.
            f.gateway.fail = null
            assertTrue(f.connect("https://pve-c.example.test:8006")); runCurrent()
            assertEquals(2, f.state.open.size)
            assertEquals(failed.id, f.state.activeId)
            // Leaving a session that is not connected drops it.
            f.sessions.disconnect(f.state.activeId); runCurrent()
            assertEquals(listOf(first), f.addresses())
        } finally { f.close() }
    }

    @Test fun showingASessionMovesTheDeskAndDisconnectingItShowsAnother() = runTest {
        val f = fixture()
        try {
            f.desk.beginCreate(); f.desk.updateDraft("Lab", first, token, ""); assertTrue(f.desk.saveDraft())
            val profile = f.desk.state.value.selectedId!!
            f.desk.setProfileSecret(secret); assertTrue(f.desk.connectSelected()); runCurrent()
            f.connect(second); runCurrent()
            assertNull(f.desk.state.value.connectedProfileId, "the manual connection in view has no profile")
            val (lab, manual) = f.state.open
            assertEquals(profile, lab.profileId)
            f.sessions.show(lab.id)
            assertEquals(profile, f.desk.state.value.connectedProfileId)
            // The desk's Disconnect acts on the session in view only.
            f.sessions.disconnect(lab.id); runCurrent()
            assertEquals(listOf(manual.id), f.state.open.map { it.id })
            assertEquals(manual.id, f.state.activeId)
            assertTrue(manual.workspace.state.value.connected)
            assertFalse(lab.workspace.state.value.connected)
            assertNull(f.desk.state.value.connectedProfileId)
            // The last one stays as an empty session, ready for the next connection.
            f.sessions.disconnect(manual.id); runCurrent()
            assertEquals(1, f.state.open.size)
            assertFalse(f.state.active.workspace.state.value.connected)
        } finally { f.close() }
    }

    @Test fun aBackgroundSessionCanBeDisconnectedWithoutTouchingTheOneInView() = runTest {
        val f = fixture()
        try {
            f.connect(first); runCurrent(); f.connect(second); runCurrent()
            val (back, front) = f.state.open
            f.sessions.disconnect(back.id); runCurrent()
            assertEquals(listOf(front.id), f.state.open.map { it.id })
            assertTrue(front.workspace.state.value.connected)
            assertFalse(back.workspace.state.value.connected)
        } finally { f.close() }
    }

    @Test fun aBackgroundSessionThatExpiresIsClosedWithANotice() = runTest {
        val f = fixture()
        try {
            f.connect(first); runCurrent(); f.connect(second); runCurrent()
            val (back, front) = f.state.open
            f.gateway.expire = first
            back.workspace.refresh(); runCurrent()
            assertEquals(listOf(front.id), f.state.open.map { it.id })
            assertTrue(f.state.notice!!.startsWith("A connection in the background ended."))
            assertTrue(front.workspace.state.value.connected)
        } finally { f.close() }
    }

    @Test fun changingAProfileClosesTheBackgroundSessionOpenedFromIt() = runTest {
        val f = fixture()
        try {
            f.desk.beginCreate(); f.desk.updateDraft("Lab", first, token, ""); assertTrue(f.desk.saveDraft())
            val profile = f.desk.state.value.selectedId!!
            f.desk.setProfileSecret(secret); f.desk.connectSelected(); runCurrent()
            f.connect(second); runCurrent()
            val (lab, manual) = f.state.open
            // A rename keeps it; a new server address does not.
            f.desk.beginEdit(profile); f.desk.updateDraft("Renamed", first, token, ""); assertTrue(f.desk.saveDraft())
            assertEquals(2, f.state.open.size)
            f.desk.beginEdit(profile); f.desk.updateDraft("Renamed", "https://pve-c.example.test:8006", token, ""); assertTrue(f.desk.saveDraft())
            assertEquals(listOf(manual.id), f.state.open.map { it.id })
            assertFalse(lab.workspace.state.value.connected)
            assertTrue(manual.workspace.state.value.connected)
        } finally { f.close() }
    }

    @Test fun theSeventhConnectionIsRefusedAndTheSecretStays() = runTest {
        val f = fixture()
        try {
            repeat(MAX_OPEN_SESSIONS) { n -> assertTrue(f.connect("https://pve-$n.example.test:8006")); runCurrent() }
            assertEquals(MAX_OPEN_SESSIONS, f.state.open.size)
            val inView = f.state.activeId
            assertFalse(f.connect("https://one-too-many.example.test:8006"))
            assertEquals("$MAX_OPEN_SESSIONS connections are open. Disconnect one before opening another.", f.desk.state.value.formError)
            assertTrue(f.desk.hasManualSecret(), "the typed secret is still there for a retry")
            assertEquals(MAX_OPEN_SESSIONS, f.state.open.size); assertEquals(inView, f.state.activeId)
            assertFalse(f.desk.exploreDemo())
        } finally { f.close() }
    }

    @Test fun minimisingPausesEverySessionAndRestoringResumesThem() = runTest {
        val f = fixture()
        try {
            assertFalse(f.sessions.resume(), "nothing was paused")
            f.connect(first); runCurrent(); f.connect(second); runCurrent()
            val (a, b) = f.state.open
            a.workspace.setLive(false)                       // the user paused this one
            f.sessions.pause()
            assertFalse(a.workspace.state.value.live); assertFalse(b.workspace.state.value.live)
            assertTrue(f.sessions.resume())
            assertFalse(a.workspace.state.value.live, "a session the user paused stays paused")
            assertTrue(b.workspace.state.value.live)
        } finally { f.close() }
    }

    private class FakeGateway : InventoryGateway {
        var fail: String? = null
        var expire: String? = null
        override suspend fun overview(connection: Connection): Overview {
            if (connection.address == expire) throw SessionExpiredException()
            if (connection.address == fail) throw java.io.IOException("unreachable")
            return Overview(1, listOf(Guest(connection.remoteKey, 100, "alpha", "node-a", "VM", "running")))
        }
        override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
    }
}
