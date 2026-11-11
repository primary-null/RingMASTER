package local.ringleader.core

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.Comparator
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionProfilesTest {
    private val origin = "https://pve.example.test:8006"
    private val otherOrigin = "https://other.example.test:8006"
    private val pin = "ab".repeat(32)
    private val token = "tester@pve!read"

    @Test fun roundTripStoresMetadataOnly() {
        val directory = tempDirectory()
        try {
            val file = directory.resolve("profiles.json")
            val withPin = profile(displayName = "Lab \"east\"", sha256Pin = pin)
            val withoutPin = profile(origin = otherOrigin, tokenId = "other@pve!token", displayName = "Other")
            val store = ProfileStore(file)
            store.save(listOf(withPin))
            store.save(listOf(withPin, withoutPin))
            val text = Files.readString(file)
            assertFalse(text.contains("secret"))
            assertFalse(text.contains("password"))
            assertFalse(text.contains("tokenSecret"))
            assertFalse(ConnectionProfile::class.java.declaredFields.any { it.name.contains("secret", true) || it.name.contains("password", true) })
            val root = Json.parseToJsonElement(text).jsonObject
            assertEquals(setOf("version", "profiles"), root.keys)
            val version = root["version"]!!.jsonPrimitive
            assertFalse(version.isString)
            assertEquals("1", version.content)
            val encoded = root["profiles"]!!.jsonArray
            assertEquals(setOf("id", "displayName", "kind", "origin", "tokenId", "sha256Pin"), encoded[0].jsonObject.keys)
            assertEquals(DIRECT_PVE_KIND, encoded[0].jsonObject["kind"]!!.jsonPrimitive.content)
            assertFalse(encoded[1].jsonObject.containsKey("sha256Pin"))
            assertEquals(listOf(withPin, withoutPin), ProfileStore(file).load())
            assertFalse(Files.exists(file.resolveSibling(".profiles.json.bak"), LinkOption.NOFOLLOW_LINKS))
        } finally { delete(directory) }
    }

    @Test fun unknownAndSecretFieldsAreRejected() {
        val id = UUID.randomUUID().toString()
        val valid = """{"id":"$id","displayName":"Lab","kind":"directPVE","origin":"$origin","tokenId":"$token"}"""
        val payloads = listOf(
            """{"version":1,"profiles":[${valid.dropLast(1)},"secret":"session-secret"}]}""",
            """{"version":1,"profiles":[${valid.dropLast(1)},"password":"session-secret"}]}""",
            """{"version":1,"profiles":[${valid.dropLast(1)},"tokenSecret":"session-secret"}]}""",
            """{"version":1,"profiles":[${valid.dropLast(1)},"note":"extra"}]}""",
            """{"version":1,"secret":"session-secret","profiles":[$valid]}""",
            """{"version":1,"profiles":[$valid],"password":"session-secret"}""",
            """{"version":1,"profiles":[],"version":1}""",
            """{"version":"1","profiles":[]}""",
            """{"profiles":[]}""",
            "not json",
            "",
        )
        payloads.forEach { payload ->
            val directory = tempDirectory()
            try {
                val file = directory.resolve("profiles.json")
                val bytes = payload.toByteArray()
                Files.write(file, bytes)
                val store = ProfileStore(file)
                val failure = assertFailsWith<ProfileStoreException> { store.load() }
                assertTrue(failure.blocksSave)
                assertTrue(failure.message!!.contains("unchanged"))
                assertTrue(Files.readAllBytes(file).contentEquals(bytes))
                val saveFailure = assertFailsWith<ProfileStoreException> { store.save(listOf(profile())) }
                assertTrue(saveFailure.blocksSave)
                assertTrue(Files.readAllBytes(file).contentEquals(bytes))
            } finally { delete(directory) }
        }
    }

    @Test fun invalidOriginPinAndTokenIdAreRejected() {
        val directory = tempDirectory()
        try {
            val file = directory.resolve("profiles.json")
            listOf(
                profileJson(origin = "http://pve.example.test:8006"),
                profileJson(origin = "https://pve.example.test:8006/vms"),
                profileJson(origin = "https://user@pve.example.test:8006"),
                profileJson(pin = "abcd"),
                profileJson(pin = pin.uppercase()),
                profileJson(pin = pin.chunked(2).joinToString(":")),
                profileJson(tokenId = "nope"),
                profileJson(tokenId = "user@realm", kind = PDM_KIND),
                profileJson(kind = "other"),
            ).forEach { payload ->
                Files.writeString(file, payload)
                val bytes = Files.readAllBytes(file)
                assertFailsWith<ProfileStoreException> { ProfileStore(file).load() }
                assertTrue(Files.readAllBytes(file).contentEquals(bytes))
            }
            assertFailsWith<IllegalArgumentException> { profileFromDraft(ProfileDraft(null, "Lab", "http://pve.example.test", token, "")) }
            assertFailsWith<IllegalArgumentException> { profileFromDraft(ProfileDraft(null, "Lab", origin, "not a token", "")) }
            assertFailsWith<IllegalArgumentException> { profileFromDraft(ProfileDraft(null, "Lab", origin, token, "abcd")) }
            val canonical = profileFromDraft(ProfileDraft(null, "  Lab  ", "$origin/", " $token ", pin.chunked(2).joinToString(":")))
            assertEquals("Lab", canonical.displayName)
            assertEquals(origin, canonical.origin)
            assertEquals(token, canonical.tokenId)
            assertEquals(pin, canonical.sha256Pin)
            Files.delete(file)
            val fresh = ProfileStore(file)
            fresh.save(listOf(canonical))
            assertEquals(canonical, fresh.load().single())
            assertFalse(Files.readString(file).contains(pin.chunked(2).joinToString(":")))
        } finally { delete(directory) }
    }

    @Test fun corruptStorePreservesOriginalBytesAndBlocksSave() {
        val directory = tempDirectory()
        try {
            val file = directory.resolve("profiles.json")
            val bytes = "{".toByteArray()
            Files.write(file, bytes)
            val store = ProfileStore(file)
            assertFailsWith<ProfileStoreException> { store.load() }
            assertFailsWith<ProfileStoreException> { store.save(listOf(profile())) }
            assertTrue(Files.readAllBytes(file).contentEquals(bytes))
            assertTrue(store.profiles().isEmpty())
        } finally { delete(directory) }
    }

    @Test fun failedReplaceKeepsPriorProfiles() {
        val directory = tempDirectory()
        try {
            val file = directory.resolve("profiles.json")
            var fail = false
            val store = ProfileStore(file) { temporary, target ->
                if (fail) throw java.io.IOException("disk") else atomicReplace(temporary, target)
            }
            val original = profile(displayName = "Lab")
            store.save(listOf(original))
            val bytes = Files.readAllBytes(file)
            fail = true
            val changed = original.copy(displayName = "Renamed")
            val failure = assertFailsWith<ProfileStoreException> { store.save(listOf(changed)) }
            assertFalse(failure.blocksSave)
            assertTrue(failure.message!!.contains("unchanged"))
            assertTrue(Files.readAllBytes(file).contentEquals(bytes))
            assertEquals(listOf(original), store.profiles())
            assertEquals(listOf(file.fileName.toString()), names(file.parent))
            fail = false
            store.save(listOf(changed))
            assertEquals(listOf(changed), ProfileStore(file).load())
        } finally { delete(directory) }
    }

    @Test fun loadDoesNotMoveOrDeleteUnrelatedBackupFiles() {
        val directory = tempDirectory()
        try {
            val file = directory.resolve("profiles.json")
            val backup = file.resolveSibling(".profiles.json.bak")
            Files.writeString(backup, "unrelated")
            assertTrue(ProfileStore(file).load().isEmpty())
            assertFalse(Files.exists(file))
            assertEquals("unrelated", Files.readString(backup))
            val saved = profile()
            ProfileStore(file).save(listOf(saved))
            assertEquals(listOf(saved), ProfileStore(file).load())
            assertEquals("unrelated", Files.readString(backup))
        } finally { delete(directory) }
    }

    @Test fun nestedAndOversizedFilesArePreservedAndBlockSave() {
        val directory = tempDirectory()
        try {
            val file = directory.resolve("profiles.json")
            val payloads = listOf("{\"profiles\":" + "[".repeat(5000) + "0" + "]".repeat(5000) + "}", "x".repeat(256 * 1024 + 1))
            for (payload in payloads) {
                Files.writeString(file, payload)
                val store = ProfileStore(file)
                assertTrue(assertFailsWith<ProfileStoreException> { store.load() }.blocksSave)
                assertFailsWith<ProfileStoreException> { store.save(listOf(profile())) }
                assertEquals(payload, Files.readString(file))
            }
        } finally { delete(directory) }
    }

    @Test fun oversizedSaveKeepsPriorSnapshotAndAllowsCorrection() {
        val directory = tempDirectory()
        try {
            val file = directory.resolve("profiles.json")
            val store = ProfileStore(file)
            val saved = profile()
            store.save(listOf(saved))
            val bytes = Files.readAllBytes(file)
            val oversized = saved.copy(tokenId = "x".repeat(256 * 1024) + "@pve!read")
            assertFalse(assertFailsWith<ProfileStoreException> { store.save(listOf(oversized)) }.blocksSave)
            assertTrue(bytes.contentEquals(Files.readAllBytes(file)))
            assertEquals(listOf(saved), store.profiles())
            store.save(listOf(saved.copy(displayName = "Corrected")))
            assertEquals("Corrected", store.load().single().displayName)
        } finally { delete(directory) }
    }

    @Test fun outsideEditsAndCorruptionCannotBeOverwrittenAfterLoad() {
        val directory = tempDirectory()
        try {
            val file = directory.resolve("profiles.json")
            val saved = profile()
            val store = ProfileStore(file)
            store.save(listOf(saved))
            val edited = encodeProfiles(listOf(saved.copy(displayName = "Outside edit")))
            Files.writeString(file, edited)
            assertFalse(assertFailsWith<ProfileStoreException> { store.save(emptyList()) }.blocksSave)
            assertEquals(edited, Files.readString(file))
            assertEquals(listOf(saved), store.profiles())
            store.load()
            store.save(emptyList())
            Files.writeString(file, "{")
            assertTrue(assertFailsWith<ProfileStoreException> { store.save(listOf(saved)) }.blocksSave)
            assertEquals("{", Files.readString(file))
        } finally { delete(directory) }
    }

    @Test fun nonRegularProfilePathIsRejected() {
        val directory = tempDirectory()
        try {
            val file = Files.createDirectory(directory.resolve("profiles.json"))
            assertTrue(assertFailsWith<ProfileStoreException> { ProfileStore(file).load() }.blocksSave)
            assertTrue(Files.isDirectory(file))
        } finally { delete(directory) }
    }

    @Test fun symbolicProfileFileIsNotFollowedOrReplaced() {
        val directory = tempDirectory()
        try {
            val outside = directory.resolve("outside.json")
            Files.writeString(outside, "keep")
            val link = directory.resolve("profiles.json")
            try {
                Files.createSymbolicLink(link, outside)
            } catch (_: Exception) {
                return
            }
            val store = ProfileStore(link)
            assertFailsWith<ProfileStoreException> { store.load() }
            assertFailsWith<ProfileStoreException> { store.save(listOf(profile())) }
            assertEquals("keep", Files.readString(outside))
        } finally { delete(directory) }
    }

    @Test fun savedFileIsUserOnlyWhenPosixPermissionsExist() {
        val directory = tempDirectory()
        try {
            if (!directory.fileSystem.supportedFileAttributeViews().contains("posix")) return
            val file = directory.resolve("ringmaster").resolve("profiles.json")
            ProfileStore(file).save(listOf(profile()))
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file))
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
                Files.getPosixFilePermissions(file.parent),
            )
        } finally { delete(directory) }
    }

    @Test fun savedFileHasOneAllowEntryForTheUserOnAnAclStore() {
        val directory = tempDirectory()
        try {
            if (directory.fileSystem.supportedFileAttributeViews().contains("posix")) return
            if (Files.getFileAttributeView(directory, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) == null) return
            val file = directory.resolve("ringmaster").resolve("profiles.json")
            val first = profile(displayName = "Lab")
            val store = ProfileStore(file)
            store.save(listOf(first))
            assertSingleAllowEntry(file)
            // Saving over an existing file must keep the single ALLOW entry and leave the user able to read, write, and delete.
            val second = first.copy(displayName = "Renamed")
            store.save(listOf(second))
            assertSingleAllowEntry(file)
            assertEquals(listOf(second), ProfileStore(file).load())
            Files.writeString(file, Files.readString(file))
            Files.delete(file)
            assertFalse(Files.exists(file))
        } finally { delete(directory) }
    }

    private fun assertSingleAllowEntry(file: Path) {
        val entries = Files.getFileAttributeView(file, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)!!.acl
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals(AclEntryType.ALLOW, entry.type())
        assertEquals(AclEntryPermission.values().toSet(), entry.permissions())
    }

    @Test fun profileFileUsesXdgThenHomeAndNeverARelativeWorkingDirectory() {
        val home = tempDirectory()
        val xdg = tempDirectory()
        try {
            val expectedXdg = xdg.resolve("ringmaster").resolve("profiles.json")
            val expectedHome = home.resolve(".config").resolve("ringmaster").resolve("profiles.json")
            assertEquals(expectedXdg, profileFile(mapOf("XDG_CONFIG_HOME" to xdg.toString()), home))
            assertEquals(expectedHome, profileFile(emptyMap(), home))
            assertEquals(expectedHome, profileFile(mapOf("XDG_CONFIG_HOME" to "  "), home))
            assertEquals(expectedHome, profileFile(mapOf("XDG_CONFIG_HOME" to "relative/config"), home))
            assertFalse(Files.exists(expectedXdg))
            assertFalse(Files.exists(expectedHome))
            assertTrue(profileFile(emptyMap(), home).isAbsolute)
        } finally {
            delete(home); delete(xdg)
        }
    }

    @Test fun pdmProfileRoundTripsBesideADirectProfile() {
        val directory = tempDirectory()
        try {
            val file = directory.resolve("profiles.json")
            val direct = profile()
            val pdm = profile(displayName = "Datacenter", origin = "https://pdm.example.test", tokenId = "reader@pam!fleet", kind = PDM_KIND)
            val store = ProfileStore(file)
            store.save(listOf(direct, pdm))
            val text = Files.readString(file)
            assertFalse(text.contains("secret"))
            assertFalse(text.contains("8443"))
            assertEquals(listOf(direct, pdm), ProfileStore(file).load())
            val implicit = profileFromDraft(ProfileDraft(null, "Lab", "https://pdm.example.test:8443", token, ""))
            assertEquals(DIRECT_PVE_KIND, implicit.kind)
            assertEquals("https://pdm.example.test:8443", implicit.origin)
            val explicit = profileFromDraft(ProfileDraft(null, "Datacenter", "https://pdm.example.test", "reader@pam!fleet", "", PDM_KIND))
            assertEquals(PDM_KIND, explicit.kind)
            assertEquals("https://pdm.example.test", explicit.origin)
        } finally { delete(directory) }
    }

    private fun profile(
        displayName: String = "Lab",
        origin: String = this.origin,
        tokenId: String = token,
        sha256Pin: String? = null,
        kind: String = DIRECT_PVE_KIND,
    ) = ConnectionProfile(UUID.randomUUID().toString(), displayName, kind, origin, tokenId, sha256Pin)

    private fun profileJson(
        origin: String = this.origin,
        tokenId: String = token,
        pin: String? = null,
        kind: String = DIRECT_PVE_KIND,
    ): String {
        val pinField = if (pin == null) "" else ""","sha256Pin":"$pin""""
        return """{"version":1,"profiles":[{"id":"${UUID.randomUUID()}","displayName":"Lab","kind":"$kind","origin":"$origin","tokenId":"$tokenId"$pinField}]}"""
    }

    private fun names(directory: Path) = Files.list(directory).use { it.map { path -> path.fileName.toString() }.toList() }

    private fun tempDirectory() = Files.createTempDirectory("ringmaster-profiles")

    private fun delete(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}
