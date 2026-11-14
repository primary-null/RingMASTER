package local.ringleader.desktop

import local.ringleader.core.UpdateAsset
import local.ringleader.core.UpdateState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdatesCopyTest {
    @Test fun consentNoSaysChecksAreOff() {
        assertEquals("Update checks are off.", updateStateWords(UpdateState.Idle(null), "no"))
        assertEquals("Ready to check for updates.", updateStateWords(UpdateState.Idle(null), "yes"))
        assertEquals("Ready to check for updates.", updateStateWords(UpdateState.Idle(null), null))
        assertEquals("Update checks are disabled by your packager.", updateStateWords(UpdateState.Disabled, "no"))
    }

    @Test fun consentTextMatchesTheReadmeUserAgentClaim() {
        assertTrue(UPDATE_CONSENT.contains("User-Agent RingMASTER/<version>"))
        assertTrue(UPDATE_CONSENT.contains("nothing about you or your servers"))
        assertFalse(UPDATE_CONSENT.contains("No account or app identifiers"))
        val readme = Files.readString(repoRoot().resolve("README.md"))
        assertTrue(readme.contains("User-Agent: RingMASTER/<version>"))
        assertTrue(readme.contains("nothing about you or your servers"))
        assertTrue(readme.contains("## Removing RingMASTER"))
        assertTrue(readme.contains("~/.config/ringmaster"))
        assertTrue(readme.contains("~/.cache/ring-leader"))
        assertTrue(readme.contains("%USERPROFILE%\\.cache\\ring-leader"))
        assertFalse(readme.contains("Clear everything removes them"))
        assertFalse(readme.contains("downloaded into your user cache the first time you open a console"))
    }

    @Test fun offeredAssetIsNameSizeAndHash() {
        val lines = offeredAssetLines(UpdateAsset("debian-13-amd64-deb", "https://example.com/files/app.deb", "ab".repeat(32), 1048576))
        assertEquals(listOf("app.deb", "1048576 bytes", "ab".repeat(32)), lines)
    }

    @Test fun archDesktopEntryAndFixScript() {
        val root = repoRoot()
        val desktop = Files.readString(root.resolve("packaging/arch/ringmaster.desktop"))
        assertTrue(desktop.lines().any { it == "Name=RingMASTER" })
        assertTrue(desktop.lines().any { it == "Categories=System;" })
        assertTrue(desktop.lines().any { it == "StartupWMClass=local-ringleader-desktop-MainKt" })
        val script = root.resolve("packaging/linux/fix-desktop-entry.sh")
        val sh = listOf("sh", "C:/Program Files/Git/bin/sh.exe", "C:/Program Files/Git/usr/bin/sh.exe")
            .firstOrNull { candidate ->
                if (candidate == "sh") runCatching { ProcessBuilder("sh", "-c", "exit 0").start().waitFor() == 0 }.getOrDefault(false)
                else Files.exists(Path.of(candidate))
            } ?: return
        val proc = ProcessBuilder(sh, script.toString(), "--self-check")
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, proc.waitFor(), output)
    }

    private fun repoRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }
}
