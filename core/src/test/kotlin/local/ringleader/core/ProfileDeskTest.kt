package local.ringleader.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileDeskTest {
    private val origin = "https://pve.example.test:8006"
    private val token = "tester@pve!read"
    private val secret = "session-secret-value"

    @Test fun reselectingCurrentProfileKeepsSecretButChangingProfileClearsIt() = runTest {
        val (desk, file, workspace) = open(FakeGateway())
        try {
            val first = addProfile(desk, "First")
            val second = addProfile(desk, "Second")
            desk.selectProfile(first)
            desk.setProfileSecret(secret)
            val generation = desk.state.value.secretGeneration
            desk.selectProfile(first)
            assertTrue(desk.hasProfileSecret())
            assertEquals(generation, desk.state.value.secretGeneration)
            desk.selectProfile(second)
            assertFalse(desk.hasProfileSecret())
        } finally { desk.close(); workspace.close(); deleteParent(file) }
    }

    @Test fun profileIdStaysStableWhenEdited() = runTest {
        val (desk, file, workspace) = open(FakeGateway())
        try {
            desk.beginCreate()
            desk.updateDraft("Lab", origin, token, "")
            assertTrue(desk.saveDraft())
            val id = desk.state.value.profiles.single().id
            desk.beginEdit(id)
            desk.updateDraft("Renamed", "$origin/", " $token ", "")
            assertTrue(desk.saveDraft())
            val saved = desk.state.value.profiles.single()
            assertEquals(id, saved.id)
            assertEquals("Renamed", saved.displayName)
            assertEquals(origin, saved.origin)
            assertEquals(token, saved.tokenId)
            assertFalse(Files.readString(file).contains(secret))
            workspace.close()
        } finally { deleteParent(file) }
    }

    @Test fun switchingProfilesCannotShowPreviousInventory() = runTest {
        val gate = CompletableDeferred<Overview>()
        var calls = 0
        lateinit var firstConnection: Connection
        val fake = FakeGateway()
        fake.inventory = { connection ->
            if (++calls == 1) {
                firstConnection = connection
                withContext(NonCancellable) { gate.await() }
            } else Overview(1, listOf(guest(connection.remoteKey, 200, "second")))
        }
        val (desk, file, workspace) = open(fake)
        try {
            val first = addProfile(desk, "One", tokenId = "one@pve!read")
            val second = addProfile(desk, "Two", tokenId = "two@pve!read")
            assertNotEquals(first, second)
            desk.selectProfile(first)
            desk.setProfileSecret("first-session-secret")
            assertTrue(desk.connectSelected())
            runCurrent()
            desk.selectProfile(second)
            assertEquals(first, desk.state.value.connectedProfileId)
            assertFalse(desk.hasProfileSecret())
            assertEquals(origin, firstConnection.remoteKey)
            desk.setProfileSecret("second-session-secret")
            assertTrue(desk.connectSelected())
            runCurrent()
            gate.complete(Overview(1, listOf(guest(origin, 100, "first"))))
            runCurrent()
            val shown = workspace.state.value.overview!!.guests
            assertEquals(listOf(200), shown.map { it.id })
            assertEquals(origin, shown.single().remote)
            assertNotEquals(second, shown.single().remote)
            assertEquals(second, desk.state.value.connectedProfileId)
            assertFalse(desk.hasProfileSecret())
            assertFailsWith<IllegalStateException> { firstConnection.withSecret { it } }
            val text = Files.readString(file)
            assertFalse(text.contains("first-session-secret"))
            assertFalse(text.contains("second-session-secret"))
            workspace.close()
        } finally { deleteParent(file) }
    }

    @Test fun editingActiveConnectionDisconnectsButANameChangeDoesNot() = runTest {
        val fake = FakeGateway()
        lateinit var connection: Connection
        fake.inventory = { current ->
            connection = current
            Overview(1, listOf(guest(current.remoteKey, 100, "alpha")))
        }
        val (desk, file, workspace) = open(fake)
        try {
            val id = addProfile(desk, "Lab")
            desk.selectProfile(id)
            desk.setProfileSecret(secret)
            assertTrue(desk.connectSelected())
            runCurrent()
            assertFalse(desk.hasProfileSecret())
            desk.beginEdit(id)
            desk.updateDraft("Workbench", origin, token, "")
            assertTrue(desk.saveDraft())
            assertEquals(id, desk.state.value.profiles.single().id)
            assertEquals("Workbench", desk.state.value.profiles.single().displayName)
            assertEquals(listOf(100), workspace.state.value.overview!!.guests.map { it.id })
            assertEquals(secret, connection.withSecret { it })
            desk.beginEdit(id)
            desk.updateDraft("Workbench", origin, "other@pve!read", "")
            assertTrue(desk.saveDraft())
            assertEquals(id, desk.state.value.profiles.single().id)
            assertEquals("other@pve!read", desk.state.value.profiles.single().tokenId)
            assertFalse(workspace.state.value.connected)
            assertNull(workspace.state.value.overview)
            assertNull(desk.state.value.connectedProfileId)
            assertTrue(desk.state.value.notice!!.contains("disconnected"))
            assertFailsWith<IllegalStateException> { connection.withSecret { it } }
            workspace.close()
        } finally { deleteParent(file) }
    }

    @Test fun changingTheActiveKindDisconnectsAndClearsTheSecret() = runTest {
        val fake = FakeGateway()
        var calls = 0
        fake.inventory = { calls++; Overview(1, listOf(guest(it.remoteKey, 100, "alpha"))) }
        val (desk, file, workspace) = open(fake)
        try {
            val id = addProfile(desk, "Lab")
            desk.selectProfile(id)
            desk.setProfileSecret(secret)
            assertTrue(desk.connectSelected())
            runCurrent()
            desk.beginEdit(id)
            desk.updateDraft("Lab", origin, token, "", PDM_KIND)
            assertTrue(desk.saveDraft())
            assertEquals(PDM_KIND, desk.state.value.profiles.single().kind)
            assertFalse(workspace.state.value.connected)
            assertNull(workspace.state.value.overview)
            assertFalse(desk.hasProfileSecret())
            assertEquals(1, calls)
            assertTrue(desk.state.value.notice!!.contains("disconnected"))
            workspace.close()
        } finally { deleteParent(file) }
    }

    @Test fun deletionRequiresConfirmationAndActiveDeletionDisconnects() = runTest {
        val fake = FakeGateway()
        val (desk, file, workspace) = open(fake)
        try {
            val active = addProfile(desk, "Active")
            val other = addProfile(desk, "Other", origin = "https://other.example.test:8006", tokenId = "other@pve!read")
            desk.selectProfile(active)
            desk.setProfileSecret(secret)
            assertTrue(desk.connectSelected())
            runCurrent()
            desk.requestDelete(other)
            assertEquals(2, desk.state.value.profiles.size)
            assertFalse(desk.hasProfileSecret())
            desk.cancelDelete()
            assertEquals(setOf(active, other), desk.state.value.profiles.map { it.id }.toSet())
            assertEquals(active, desk.state.value.connectedProfileId)
            desk.requestDelete(other)
            assertTrue(desk.confirmDelete())
            assertEquals(listOf(active), desk.state.value.profiles.map { it.id })
            assertEquals(listOf(100), workspace.state.value.overview!!.guests.map { it.id })
            assertEquals(active, desk.state.value.connectedProfileId)
            desk.setProfileSecret(secret)
            desk.requestDelete(active)
            assertTrue(desk.confirmDelete())
            assertTrue(desk.state.value.profiles.isEmpty())
            assertFalse(workspace.state.value.connected)
            assertNull(workspace.state.value.overview)
            assertNull(desk.state.value.connectedProfileId)
            assertFalse(desk.hasProfileSecret())
            assertTrue(desk.state.value.notice!!.contains("disconnected"))
            assertFalse(Files.readString(file).contains(secret))
            workspace.close()
        } finally { deleteParent(file) }
    }

    @Test fun secretClearsOnProfileChangeConnectCancelAndNavigation() = runTest {
        val (desk, file, workspace) = open(FakeGateway())
        try {
            val first = addProfile(desk, "One")
            val second = addProfile(desk, "Two", tokenId = "two@pve!read")
            desk.selectProfile(first)
            desk.setProfileSecret(secret)
            desk.setManualSecret(secret)
            desk.selectProfile(second)
            assertFalse(desk.hasProfileSecret())
            assertFalse(desk.hasManualSecret())
            desk.setProfileSecret("bad\nsecret")
            assertFalse(desk.connectSelected())
            assertTrue(desk.hasProfileSecret())
            assertFalse(workspace.state.value.connected)
            desk.leaveDesk()
            assertFalse(desk.hasProfileSecret())
            desk.setProfileSecret(secret)
            assertTrue(desk.connectSelected())
            runCurrent()
            assertFalse(desk.hasProfileSecret())
            desk.setManualSecret(secret)
            desk.beginCreate()
            assertFalse(desk.hasManualSecret())
            desk.cancelEdit()
            desk.setManualSecret(secret)
            desk.leaveDesk()
            assertFalse(desk.hasManualSecret())
            assertFalse(Files.readString(file).contains(secret))
            assertFalse(desk.state.value.toString().contains(secret))
            workspace.close()
        } finally { deleteParent(file) }
    }

    @Test fun corruptStoreLeavesManualConnectionAndDemoUsable() = runTest {
        val directory = Files.createTempDirectory("ringmaster-desk")
        val file = directory.resolve("profiles.json")
        val bytes = """{"version":1,"profiles":[{"secret":"do-not-keep"}]}""".toByteArray()
        Files.write(file, bytes)
        val fake = FakeGateway()
        val workspace = Workspace(this, fake)
        val desk = ProfileDesk(workspace, ProfileStore(file))
        try {
            assertTrue(desk.state.value.saveBlocked)
            desk.beginCreate()
            assertFalse(desk.state.value.editing)
            desk.setManualSecret(secret)
            assertTrue(desk.connectManual(origin, token, ""))
            runCurrent()
            assertTrue(workspace.state.value.connected)
            assertEquals(origin, workspace.state.value.overview!!.guests.single().remote)
            assertNull(desk.state.value.connectedProfileId)
            assertFalse(desk.hasManualSecret())
            desk.exploreDemo()
            assertTrue(workspace.state.value.demo)
            assertEquals(DEMO_REMOTE_KEY, workspace.state.value.overview!!.guests.first().remote)
            assertFalse(desk.hasManualSecret())
            assertTrue(Files.readAllBytes(file).contentEquals(bytes))
            workspace.close()
        } finally {
            desk.close()
            delete(directory)
        }
    }

    @Test fun failedSaveDoesNotDropTheOpenSession() = runTest {
        val directory = Files.createTempDirectory("ringmaster-desk")
        val file = directory.resolve("profiles.json")
        var fail = false
        val store = ProfileStore(file) { temporary, target ->
            if (fail) throw java.io.IOException("disk") else atomicReplace(temporary, target)
        }
        val workspace = Workspace(this, FakeGateway())
        val desk = ProfileDesk(workspace, store)
        try {
            val id = addProfile(desk, "Lab")
            desk.selectProfile(id)
            desk.setProfileSecret(secret)
            assertTrue(desk.connectSelected())
            runCurrent()
            val bytes = Files.readAllBytes(file)
            fail = true
            desk.beginEdit(id)
            desk.updateDraft("Renamed", origin, token, "")
            assertFalse(desk.saveDraft())
            assertEquals("Lab", desk.state.value.profiles.single().displayName)
            assertEquals(id, desk.state.value.connectedProfileId)
            assertEquals(listOf(100), workspace.state.value.overview!!.guests.map { it.id })
            assertTrue(Files.readAllBytes(file).contentEquals(bytes))
            workspace.close()
        } finally {
            desk.close()
            delete(directory)
        }
    }

    @Test fun expiredSessionClearsTheConnectedProfile() = runTest {
        val fake = FakeGateway()
        val (desk, file, workspace) = open(fake)
        try {
            val id = addProfile(desk, "Lab")
            desk.selectProfile(id)
            desk.setProfileSecret(secret)
            assertTrue(desk.connectSelected())
            runCurrent()
            fake.inventory = { throw SessionExpiredException() }
            workspace.refresh()
            runCurrent()
            assertFalse(workspace.state.value.connected)
            assertNull(workspace.state.value.overview)
            desk.onWorkspace(workspace.state.value)
            assertNull(desk.state.value.connectedProfileId)
            workspace.close()
        } finally { deleteParent(file) }
    }

    private fun guest(remote: String, id: Int, name: String) = Guest(remote, id, name, "node-a", "VM", "running")

    private fun addProfile(desk: ProfileDesk, name: String, origin: String = this.origin, tokenId: String = token): String {
        desk.beginCreate()
        desk.updateDraft(name, origin, tokenId, "")
        assertTrue(desk.saveDraft())
        return desk.state.value.selectedId!!
    }

    private fun TestScope.open(fake: InventoryGateway): Triple<ProfileDesk, Path, Workspace> {
        val directory = Files.createTempDirectory("ringmaster-desk")
        val file = directory.resolve("profiles.json")
        val workspace = Workspace(this, fake)
        return Triple(ProfileDesk(workspace, ProfileStore(file)), file, workspace)
    }

    private fun deleteParent(file: Path) = delete(file.parent)

    private fun delete(directory: Path) {
        Files.walk(directory).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private class FakeGateway : InventoryGateway {
        var inventory: suspend (Connection) -> Overview = { connection ->
            Overview(1, listOf(Guest(connection.remoteKey, 100, "alpha", "node-a", "VM", "running")))
        }
        override suspend fun overview(connection: Connection) = inventory(connection)
        override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
    }
}
