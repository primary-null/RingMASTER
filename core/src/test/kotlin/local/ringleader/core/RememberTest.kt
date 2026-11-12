package local.ringleader.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RememberTest {
    private val origin = "https://pve.example.test:8006"
    private val token = "tester@pve!read"
    private val secret = "remember-secret-xyzzy-9f3a"
    private val otp = "654321"

    @Test fun rememberOffNeverStores() = runTest {
        val vault = FakeVault()
        val rig = open(vault)
        try {
            val id = addProfile(rig.desk, "Lab")
            rig.desk.selectProfile(id)
            rig.desk.setProfileSecret(secret)
            assertTrue(rig.desk.connectSelected(otp = otp))
            runCurrent()
            assertTrue(rig.workspace.state.value.overview != null)
            assertTrue(vault.stores.isEmpty())
            assertFalse(rig.desk.state.value.remembered.contains(id))
        } finally { rig.close() }
    }

    @Test fun rememberOnStoresAfterTheFirstInventoryAndWipesTheCopy() = runTest {
        val vault = FakeVault()
        val rig = open(vault)
        try {
            val id = addProfile(rig.desk, "Lab")
            rig.desk.selectProfile(id)
            rig.desk.setRememberChoice(true)
            rig.desk.setProfileSecret(secret)
            assertTrue(rig.desk.connectSelected(otp = otp))
            runCurrent()
            val call = vault.stores.single()
            assertEquals(id, call.profileId)
            assertEquals(token, call.login)
            assertEquals("RingMASTER: Lab", call.label)
            assertContentEquals(secret.toCharArray(), call.snapshot)
            assertTrue(call.passed.all { it == '\u0000' })
            assertFalse(call.snapshot.concatToString().contains(otp))
            assertEquals(setOf(id), rig.desk.state.value.remembered)
            assertEquals("Remembered on this computer.", rig.desk.state.value.notice)
            assertEquals(setOf(id), RememberedProfiles(rig.directory.resolve("remembered.json")).load())
            assertSecretAbsent(rig.directory, secret)
        } finally { rig.close() }
    }

    @Test fun aFailedSignInStoresNothing() = runTest {
        val vault = FakeVault()
        val fake = FakeGateway()
        fake.inventory = { throw IllegalStateException("unreachable") }
        val rig = open(vault, fake)
        try {
            val id = addProfile(rig.desk, "Lab")
            rig.desk.selectProfile(id)
            rig.desk.setRememberChoice(true)
            rig.desk.setProfileSecret(secret)
            assertTrue(rig.desk.connectSelected())
            runCurrent()
            assertTrue(vault.stores.isEmpty())
            assertTrue(rig.desk.state.value.notice!!.contains("not remembered"))
            assertFalse(rig.desk.state.value.remembered.contains(id))
        } finally { rig.close() }
    }

    @Test fun disconnectOrANewEpochBeforeSuccessStoresNothing() = runTest {
        val vault = FakeVault()
        val gate = CompletableDeferred<Overview>()
        val fake = FakeGateway()
        fake.inventory = { gate.await() }
        val rig = open(vault, fake)
        try {
            val id = addProfile(rig.desk, "Lab")
            rig.desk.selectProfile(id)
            rig.desk.setRememberChoice(true)
            rig.desk.setProfileSecret(secret)
            assertTrue(rig.desk.connectSelected())
            runCurrent()
            rig.workspace.disconnect()
            runCurrent()
            assertTrue(vault.stores.isEmpty())
            rig.desk.setRememberChoice(true)
            rig.desk.setProfileSecret(secret)
            assertTrue(rig.desk.connectSelected())
            runCurrent()
            rig.desk.setProfileSecret(secret)
            assertTrue(rig.desk.connectSelected())
            runCurrent()
            assertTrue(vault.stores.isEmpty())
            assertTrue(rig.desk.state.value.notice!!.contains("not remembered"))
        } finally { rig.close() }
    }

    @Test fun aSecretThatNeverFinishesIsDroppedAndWiped() = runTest {
        val vault = FakeVault()
        val fake = FakeGateway()
        fake.inventory = { CompletableDeferred<Overview>().await() }
        val rig = open(vault, fake, waitMs = 40)
        val wiped = mutableListOf<CharArray>()
        rig.desk.onSecretReleased = { wiped += it }
        try {
            val id = addProfile(rig.desk, "Lab")
            rig.desk.selectProfile(id)
            rig.desk.setRememberChoice(true)
            rig.desk.setProfileSecret(secret)
            assertTrue(rig.desk.connectSelected())
            runCurrent()
            advanceTimeBy(41)
            runCurrent()
            assertTrue(vault.stores.isEmpty())
            assertTrue(rig.desk.state.value.notice!!.contains("not remembered"))
            assertTrue(wiped.any { it.size == secret.length && it.all { char -> char == '\u0000' } })
        } finally { rig.close() }
    }

    @Test fun connectRememberedReadsAndWipesAndReportsAMissingOrFailedEntry() = runTest {
        val vault = FakeVault()
        val rig = open(vault)
        try {
            val id = addProfile(rig.desk, "Lab")
            rig.desk.selectProfile(id)
            rig.desk.setRememberChoice(true)
            rig.desk.setProfileSecret(secret)
            assertTrue(rig.desk.connectSelected())
            runCurrent()
            rig.workspace.disconnect()
            runCurrent()
            assertTrue(rig.desk.connectRemembered())
            runCurrent()
            assertTrue(rig.workspace.state.value.connected)
            val read = assertNotNull(vault.lastRead)
            assertTrue(read.all { it == '\u0000' })
            assertFalse(rig.desk.hasProfileSecret())
            rig.workspace.disconnect()
            runCurrent()
            vault.drop(id)
            assertFalse(rig.desk.connectRemembered())
            assertFalse(rig.workspace.state.value.connected)
            assertFalse(id in rig.desk.state.value.remembered)
            assertTrue(rig.desk.state.value.formError!!.contains("no longer"))
            RememberedProfiles(rig.directory.resolve("remembered.json")).save(setOf(id))
            val again = open(vault, directory = rig.directory)
            try {
                again.desk.selectProfile(id)
                vault.readException = VaultException("the safe stayed locked")
                assertFalse(again.desk.connectRemembered())
                assertFalse(again.workspace.state.value.connected)
                assertTrue(id in again.desk.state.value.remembered)
                assertTrue(again.desk.state.value.formError!!.contains("could not be read"))
            } finally { again.close() }
        } finally { rig.close() }
    }

    @Test fun aTypedSecretReplacesTheRememberedOneAfterSuccess() = runTest {
        val vault = FakeVault()
        val rig = open(vault)
        val replacement = "remember-secret-replaced-22c"
        try {
            val id = addProfile(rig.desk, "Lab")
            rig.desk.selectProfile(id)
            rig.desk.setRememberChoice(true)
            rig.desk.setProfileSecret(secret)
            assertTrue(rig.desk.connectSelected())
            runCurrent()
            rig.workspace.disconnect()
            runCurrent()
            rig.desk.selectProfile(id)
            rig.desk.setProfileSecret(replacement)
            assertTrue(rig.desk.connectSelected())
            runCurrent()
            assertEquals(2, vault.stores.size)
            assertContentEquals(replacement.toCharArray(), vault.stores.last().snapshot)
            assertTrue(vault.stores.last().passed.all { it == '\u0000' })
            assertEquals(setOf(id), rig.desk.state.value.remembered)
        } finally { rig.close() }
    }

    @Test fun redefineForgetsAndRenameKeeps() = runTest {
        val vault = FakeVault()
        val rig = open(vault)
        val pin = "ab".repeat(32)
        try {
            val id = addProfile(rig.desk, "Lab")
            remember(rig, id)
            rig.desk.beginEdit(id)
            rig.desk.updateDraft("Renamed", origin, token, "")
            assertTrue(rig.desk.saveDraft())
            runCurrent()
            assertTrue(vault.forgets.isEmpty())
            assertTrue(id in rig.desk.state.value.remembered)
            rig.desk.beginEdit(id)
            rig.desk.updateDraft("Renamed", "https://other.example.test:8006", token, "")
            assertTrue(rig.desk.saveDraft())
            runCurrent()
            assertEquals(listOf(id), vault.forgets)
            assertFalse(id in rig.desk.state.value.remembered)
            assertTrue(rig.desk.state.value.notice!!.contains("forgotten because the server"))
            remember(rig, id)
            rig.desk.beginEdit(id)
            rig.desk.updateDraft("Renamed", "https://other.example.test:8006", "other@pve!read", "")
            assertTrue(rig.desk.saveDraft())
            runCurrent()
            assertFalse(id in rig.desk.state.value.remembered)
            remember(rig, id)
            rig.desk.beginEdit(id)
            rig.desk.updateDraft("Renamed", "https://other.example.test:8006", "other@pve!read", pin)
            assertTrue(rig.desk.saveDraft())
            runCurrent()
            assertFalse(id in rig.desk.state.value.remembered)
            remember(rig, id)
            rig.desk.beginEdit(id)
            rig.desk.updateDraft("Renamed", "https://other.example.test:8006", "other@pve!read", pin, PDM_KIND)
            assertTrue(rig.desk.saveDraft())
            runCurrent()
            assertFalse(id in rig.desk.state.value.remembered)
            assertEquals(4, vault.forgets.size)
        } finally { rig.close() }
    }

    @Test fun aSafeThatRefusesToForgetStillLosesTheMarkerAtOnce() = runTest {
        val vault = FakeVault()
        val rig = open(vault)
        try {
            val id = addProfile(rig.desk, "Lab")
            remember(rig, id)
            vault.forgetException = IllegalStateException("native failure")
            rig.desk.beginEdit(id)
            rig.desk.updateDraft("Lab", "https://other.example.test:8006", token, "")
            assertTrue(rig.desk.saveDraft())
            // Before any background work runs: the old secret can no longer be offered to the changed server.
            assertFalse(id in rig.desk.state.value.remembered)
            assertFalse(id in RememberedProfiles(rig.directory.resolve("remembered.json")).load())
            assertFalse(rig.desk.connectRemembered())
            runCurrent()
            assertFalse(rig.workspace.state.value.connected)
            assertNull(vault.lastRead, "the safe was not read for the changed profile")
            assertEquals(listOf(id), vault.forgets)
            assertTrue(rig.desk.state.value.notice!!.contains("could not be removed"))
        } finally { rig.close() }
    }

    @Test fun aProfileEditedWhileTheSafeIsReadDropsTheSecret() = runTest {
        val vault = FakeVault()
        val rig = open(vault)
        try {
            val id = addProfile(rig.desk, "Lab")
            remember(rig, id)
            vault.duringRead = {
                rig.desk.beginEdit(id)
                rig.desk.updateDraft("Lab", "https://other.example.test:8006", token, "")
                assertTrue(rig.desk.saveDraft())
            }
            assertFalse(rig.desk.connectRemembered())
            runCurrent()
            assertFalse(rig.workspace.state.value.connected, "the old secret was not sent to the changed server")
            assertTrue(assertNotNull(vault.lastRead).all { it == '\u0000' })
            assertFalse(id in rig.desk.state.value.remembered)
        } finally { rig.close() }
    }

    @Test fun deleteAlwaysForgetsEvenWithoutAMarker() = runTest {
        val vault = FakeVault()
        val rig = open(vault)
        try {
            val id = addProfile(rig.desk, "Lab")
            rig.desk.requestDelete(id)
            assertTrue(rig.desk.confirmDelete())
            runCurrent()
            assertEquals(listOf(id), vault.forgets)
            val kept = addProfile(rig.desk, "Kept")
            remember(rig, kept)
            rig.desk.requestDelete(kept)
            assertTrue(rig.desk.confirmDelete())
            runCurrent()
            assertTrue(kept in vault.forgets)
            assertFalse(kept in rig.desk.state.value.remembered)
        } finally { rig.close() }
    }

    @Test fun anUnavailableVaultIgnoresTheBoxAndStillConnects() = runTest {
        val vault = FakeVault(VaultStatus.Unavailable("Windows does not allow saved credentials for this account."))
        val rig = open(vault)
        try {
            val id = addProfile(rig.desk, "Lab")
            rig.desk.selectProfile(id)
            rig.desk.setRememberChoice(true)
            assertFalse(rig.desk.state.value.rememberChoice)
            rig.desk.setProfileSecret(secret)
            assertTrue(rig.desk.connectSelected())
            runCurrent()
            assertTrue(rig.workspace.state.value.connected)
            assertTrue(vault.stores.isEmpty())
        } finally { rig.close() }
    }

    @Test fun aChangedSelectionDropsTheReadSecret() = runTest {
        val vault = FakeVault()
        val rig = open(vault)
        try {
            val first = addProfile(rig.desk, "Lab")
            val second = addProfile(rig.desk, "Other", tokenId = "other@pve!read")
            remember(rig, first)
            rig.workspace.disconnect()
            runCurrent()
            rig.desk.selectProfile(first)
            vault.duringRead = { rig.desk.selectProfile(second) }
            assertFalse(rig.desk.connectRemembered())
            assertFalse(rig.workspace.state.value.connected)
            assertTrue(assertNotNull(vault.lastRead).all { it == '\u0000' })
        } finally { rig.close() }
    }

    @Test fun routerRefusalStoresNothing() = runTest {
        val vault = FakeVault()
        val rig = open(vault)
        val wiped = mutableListOf<Int>()
        rig.desk.onSecretReleased = { wiped += it.size }
        try {
            val id = addProfile(rig.desk, "Lab")
            rig.desk.selectProfile(id)
            rig.desk.setRememberChoice(true)
            rig.desk.setProfileSecret(secret)
            rig.desk.router = { _, _ -> throw IllegalStateException("No room for another connection.") }
            assertFalse(rig.desk.connectSelected())
            runCurrent()
            assertTrue(vault.stores.isEmpty())
            assertTrue(rig.desk.hasProfileSecret())
            assertTrue(wiped.any { it == secret.length })
        } finally { rig.close() }
    }

    @Test fun forgetOnlyVaultAndNoVault() {
        val inner = FakeVault()
        inner.remember("profile-a", "stored-secret")
        val browser = ForgetOnlyVault(inner)
        assertTrue(browser.status() is VaultStatus.Unavailable)
        assertFailsWith<VaultException> { browser.read("profile-a") }
        assertFailsWith<VaultException> { browser.store("profile-a", "root@pam", "label", charArrayOf('x')) }
        browser.forget("profile-a")
        assertEquals(listOf("profile-a"), inner.forgets)
        browser.forgetAll()
        assertEquals(1, inner.forgetAllCount)
        val none = NoVault("Remember is not set up.")
        assertFailsWith<VaultException> { none.read("profile-a") }
        assertFailsWith<VaultException> { none.store("profile-a", "root@pam", "label", charArrayOf('x')) }
        none.forget("profile-a")
        none.forgetAll()
    }

    @Test fun rememberedProfilesRoundTripCorruptAndNewer() {
        val directory = Files.createTempDirectory("ringmaster-markers")
        try {
            val file = directory.resolve("remembered.json")
            val ids = setOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
            RememberedProfiles(file).save(ids)
            assertEquals(ids, RememberedProfiles(file).load())
            val memory = RememberedProfiles(null)
            memory.save(ids)
            assertEquals(ids, memory.load())
            Files.writeString(file, "{not json")
            val corrupt = RememberedProfiles(file)
            assertTrue(corrupt.load().isEmpty())
            assertNull(corrupt.blockedReason())
            corrupt.save(setOf(ids.first()))
            assertEquals(setOf(ids.first()), RememberedProfiles(file).load())
            val newerBytes = "{\"version\":2,\"profiles\":[\"keep-me\"]}\n".toByteArray(Charsets.UTF_8)
            Files.write(file, newerBytes)
            val newer = RememberedProfiles(file)
            assertTrue(newer.load().isEmpty())
            assertEquals("remembered.json was written by a newer RingMASTER.", newer.blockedReason())
            newer.save(setOf(UUID.randomUUID().toString()))
            assertContentEquals(newerBytes, Files.readAllBytes(file))
        } finally { delete(directory) }
    }

    @Test fun aNewerMarkerFileDisablesRemember() = runTest {
        val directory = Files.createTempDirectory("ringmaster-newer")
        try {
            Files.writeString(directory.resolve("remembered.json"), "{\"version\":2,\"profiles\":[]}")
            val rig = open(FakeVault(), directory = directory)
            try {
                val status = rig.desk.state.value.vaultStatus
                assertTrue(status is VaultStatus.Unavailable)
                assertEquals("remembered.json was written by a newer RingMASTER.", (status as VaultStatus.Unavailable).reason)
                rig.desk.setRememberChoice(true)
                assertFalse(rig.desk.state.value.rememberChoice)
                assertContentEquals("{\"version\":2,\"profiles\":[]}".toByteArray(), Files.readAllBytes(directory.resolve("remembered.json")))
            } finally { rig.close() }
        } finally { delete(directory) }
    }

    private fun TestScope.remember(rig: Rig, id: String) {
        rig.desk.selectProfile(id)
        rig.desk.setRememberChoice(true)
        rig.desk.setProfileSecret(secret)
        assertTrue(rig.desk.connectSelected())
        runCurrent()
        rig.workspace.disconnect()
        runCurrent()
        assertTrue(id in rig.desk.state.value.remembered)
    }

    private fun TestScope.addProfile(desk: ProfileDesk, name: String, origin: String = this@RememberTest.origin, tokenId: String = token): String {
        desk.beginCreate()
        desk.updateDraft(name, origin, tokenId, "")
        assertTrue(desk.saveDraft())
        return desk.state.value.selectedId!!
    }

    private fun TestScope.open(
        vault: FakeVault,
        fake: InventoryGateway = FakeGateway(),
        waitMs: Long = 90_000,
        directory: Path = Files.createTempDirectory("ringmaster-remember"),
    ): Rig {
        val workspace = Workspace(this, fake)
        val desk = ProfileDesk(
            workspace,
            ProfileStore(directory.resolve("profiles.json")),
            vault = vault,
            rememberedProfiles = RememberedProfiles(directory.resolve("remembered.json")),
            io = coroutineContext,
            rememberWaitMs = waitMs,
        )
        runCurrent()
        return Rig(desk, workspace, directory)
    }

    private fun assertSecretAbsent(directory: Path, value: String) {
        val needle = value.toByteArray(Charsets.UTF_8)
        Files.walk(directory).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { file ->
                assertFalse(containsSequence(Files.readAllBytes(file), needle), file.fileName.toString())
            }
        }
    }

    private fun containsSequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        for (start in 0..haystack.size - needle.size) {
            var same = true
            for (index in needle.indices) if (haystack[start + index] != needle[index]) { same = false; break }
            if (same) return true
        }
        return false
    }

    private fun delete(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private class Rig(val desk: ProfileDesk, val workspace: Workspace, val directory: Path) : AutoCloseable {
        override fun close() {
            desk.close()
            workspace.close()
            if (!Files.exists(directory)) return
            Files.walk(directory).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private class FakeGateway : InventoryGateway {
        var inventory: suspend (Connection) -> Overview = { connection ->
            Overview(1, listOf(Guest(connection.remoteKey, 100, "alpha", "node-a", "VM", "running")))
        }
        override suspend fun overview(connection: Connection) = inventory(connection)
        override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
    }
}

private class FakeVault(private val initial: VaultStatus = VaultStatus.Available) : SecretVault {
    override val name = "Windows Credential Manager"
    val stores = mutableListOf<Store>()
    val forgets = mutableListOf<String>()
    var forgetAllCount = 0
    var readException: VaultException? = null
    var forgetException: Throwable? = null
    var duringRead: (() -> Unit)? = null
    var lastRead: CharArray? = null
    private val secrets = mutableMapOf<String, CharArray>()

    class Store(val profileId: String, val login: String, val label: String, val snapshot: CharArray, val passed: CharArray)

    fun remember(profileId: String, value: String) { secrets[profileId] = value.toCharArray() }
    fun drop(profileId: String) { secrets.remove(profileId)?.fill('\u0000') }

    override fun status() = initial
    override fun store(profileId: String, login: String, label: String, secret: CharArray) {
        val snapshot = secret.copyOf()
        stores += Store(profileId, login, label, snapshot, secret)
        secrets[profileId] = snapshot.copyOf()
    }
    override fun read(profileId: String): CharArray? {
        readException?.let { throw it }
        val fresh = secrets[profileId]?.copyOf()
        lastRead = fresh
        duringRead?.invoke()
        return fresh
    }
    override fun forget(profileId: String) {
        forgets += profileId
        forgetException?.let { throw it }
        secrets.remove(profileId)?.fill('\u0000')
    }
    override fun forgetAll() {
        forgetAllCount++
        secrets.values.forEach { it.fill('\u0000') }
        secrets.clear()
    }
}
