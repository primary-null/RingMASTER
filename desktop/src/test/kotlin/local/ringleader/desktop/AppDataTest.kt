package local.ringleader.desktop

import java.nio.file.Files
import java.nio.file.Path
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

        assertEquals(emptyList(), AppData.clearAll(config, cache))

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
        assertEquals(emptyList(), AppData.clearAll(root.resolve("missing"), root.resolve("missing-cache")))
    }
}
