package local.ringleader.desktop

import java.nio.file.Files
import java.nio.file.Path
import local.ringleader.core.NoVault
import local.ringleader.core.SecretVault
import local.ringleader.core.VaultException
import local.ringleader.core.VaultStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppDataTest {
    @Test fun clearAllRemovesSettingsAndConsoleProfileButKeepsPlugins() {
        val root = Files.createTempDirectory("rm-wipe")
        val config = Files.createDirectories(root.resolve("config"))
        val cache = Files.createDirectories(root.resolve("cache"))
        Files.createDirectories(config.resolve("backups/old")).also { Files.writeString(it.resolve("profiles.json"), "backup") }
        Files.writeString(config.resolve("updates.properties"), "schema=1")
        Files.writeString(config.resolve("profiles.json"), "{}")
        Files.writeString(config.resolve("window.properties"), "last=1,2,3,4")
        Files.createDirectories(config.resolve("backgrounds")).also { Files.writeString(it.resolve("a.png"), "x") }
        Files.createDirectories(config.resolve("plugins")).also { Files.writeString(it.resolve("mine.json"), "{}") }
        Files.createDirectories(cache.resolve("chromium-profile/Default")).also { Files.writeString(it.resolve("Cookies"), "x") }
        Files.createDirectories(cache.resolve("jcef-152.0.6")).also { Files.writeString(it.resolve("engine"), "x") }

        assertEquals(emptyList(), AppData.clearAll(config, cache, NoVault("Remember is not set up.")))

        assertFalse(Files.exists(config.resolve("backups")))
        assertFalse(Files.exists(config.resolve("updates.properties")))
        assertFalse(Files.exists(config.resolve("profiles.json")))
        assertFalse(Files.exists(config.resolve("window.properties")))
        assertFalse(Files.exists(config.resolve("backgrounds")))
        assertTrue(Files.exists(config.resolve("plugins/mine.json")), "hand-written plugins stay")
        assertFalse(Files.exists(cache.resolve("chromium-profile")))
        assertTrue(Files.exists(cache.resolve("jcef-152.0.6/engine")), "an older engine download stays")
        assertTrue(AppData.KEPT_NOTE.contains("plugins"))
        assertTrue(AppData.KEPT_NOTE.contains("older Chromium engine"))
        assertTrue(AppData.removes.any { it.contains("leftover console browser profile") })
        assertTrue(AppData.removes.any { it.contains("password safe") })
    }

    @Test fun clearPreservesTheResidentLockAndRemovesScheduleHistory() {
        val root = Files.createTempDirectory("rm-wipe-lock")
        val config = Files.createDirectories(root.resolve("config"))
        val owner = InstanceLock.acquire(config) as InstanceLock.Outcome.Owner
        try {
            Files.writeString(config.resolve("schedules.json"), "private history")
            assertEquals(emptyList(), AppData.clearAll(config, root.resolve("cache"), NoVault("none")))
            assertFalse(Files.exists(config.resolve("schedules.json")))
            assertTrue(Files.exists(config.resolve("instance.lock")))
            assertEquals(InstanceLock.Outcome.Taken, InstanceLock.acquire(config))
            assertTrue(AppData.removes.any { it.contains("run history") })
        } finally { owner.lock.release() }
    }

    @Test fun clearAllForgetsRememberedSecretsBeforeTheFiles() {
        val root = Files.createTempDirectory("rm-wipe-vault")
        val config = Files.createDirectories(root.resolve("config"))
        val cache = Files.createDirectories(root.resolve("cache"))
        Files.writeString(config.resolve("profiles.json"), "{}")
        Files.writeString(config.resolve("remembered.json"), "{\"version\":1,\"profiles\":[]}")
        val vault = RecordingVault()
        assertEquals(emptyList(), AppData.clearAll(config, cache, vault))
        assertEquals(1, vault.forgetAllCount)
        assertFalse(Files.exists(config.resolve("profiles.json")))
        assertFalse(Files.exists(config.resolve("remembered.json")))
    }

    @Test fun clearAllKeepsWipingFilesWhenTheVaultFails() {
        val root = Files.createTempDirectory("rm-wipe-vault-fail")
        val config = Files.createDirectories(root.resolve("config"))
        val cache = Files.createDirectories(root.resolve("cache"))
        Files.writeString(config.resolve("profiles.json"), "{}")
        val vault = RecordingVault(fail = VaultException("the safe stayed locked"))
        val failed = AppData.clearAll(config, cache, vault)
        assertEquals(listOf("Remembered secrets in Windows Credential Manager (the safe stayed locked)"), failed)
        assertEquals(1, vault.forgetAllCount)
        assertFalse(Files.exists(config.resolve("profiles.json")))
    }
    @Test fun cacheDirectoryIgnoresBlankAndRelativeXdgCacheHome() {
        val home = Files.createTempDirectory("rm-home")
        val expected = home.resolve(".cache").resolve("ring-leader")
        assertEquals(expected, cacheDirectory(null, home))
        assertEquals(expected, cacheDirectory("", home))
        assertEquals(expected, cacheDirectory("   ", home))
        assertEquals(expected, cacheDirectory("relative/cache", home))
        val absolute = Files.createTempDirectory("rm-xdg")
        assertEquals(absolute.resolve("ring-leader"), cacheDirectory(absolute.toString(), home))
        assertEquals(absolute.resolve("ring-leader"), cacheDirectory("  ${absolute}  ", home))
        assertTrue(cacheDirectory("relative/cache", home).isAbsolute)
    }

    @Test fun clearAllOnAFreshComputerIsANoOp() {
        val root = Files.createTempDirectory("rm-wipe-empty")
        assertEquals(emptyList(), AppData.clearAll(root.resolve("missing"), root.resolve("missing-cache"), NoVault("Remember is not set up.")))
    }
}

private class RecordingVault(private val fail: VaultException? = null) : SecretVault {
    override val name = "Windows Credential Manager"
    var forgetAllCount = 0
    override fun status(): VaultStatus = VaultStatus.Available
    override fun store(profileId: String, login: String, label: String, secret: CharArray) = Unit
    override fun read(profileId: String): CharArray? = null
    override fun forget(profileId: String) = Unit
    override fun forgetAll() {
        forgetAllCount++
        fail?.let { throw it }
    }
}
