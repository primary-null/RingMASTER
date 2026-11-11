package local.ringleader.core.tools

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import local.ringleader.core.findExecutable
import org.junit.jupiter.api.Test

class UserPluginApprovalTest {

    @Test fun unapprovedRunRefused() = runBlocking {
        val plugin = UserToolPlugin("unapproved", "Unapproved", "Test",
            listOf(ToolInput("host", "Host", ToolInputKind.Host)),
            listOf("fake-program", "{host}"), null, "unapproved.json") { null }
        val result = plugin.run(mapOf("host" to "127.0.0.1")) {}
        assertFalse(result.ok)
        assertTrue(result.summary.contains("Confirm"))
    }

    @Test fun approvedRunSucceeds() = runBlocking {
        val ping = findExecutable("ping") ?: return@runBlocking
        val command = if (windowsOs()) listOf(ping, "-n", "{count}", "-w", "1000", "{host}")
                      else listOf(ping, "-c", "{count}", "-W", "1", "{host}")
        val inputs = listOf(
            ToolInput("host", "Host", ToolInputKind.Host, default = "127.0.0.1"),
            ToolInput("count", "Count", ToolInputKind.Number, default = "1", min = 1, max = 5),
        )
        val plugin = UserToolPlugin("approved", "Approved", "Test", inputs, command, null, "approved.json")
        val values = mapOf("host" to "127.0.0.1", "count" to "1")
        plugin.approval = PluginApproval(plugin.manifestCommand, plugin.commandFor(values))
        val result = plugin.run(values) {}
        assertTrue(result.ok, result.summary)
    }

    @Test fun differentArgvAsksAgain() = runBlocking {
        val plugin = UserToolPlugin("reask", "Reask", "Test",
            listOf(ToolInput("host", "Host", ToolInputKind.Host)),
            listOf("fake-program", "{host}"), null, "reask.json") { null }
        val values1 = mapOf("host" to "127.0.0.1")
        plugin.approval = PluginApproval(plugin.manifestCommand, plugin.commandFor(values1))
        val values2 = mapOf("host" to "pve.example")
        val result = plugin.run(values2) {}
        assertFalse(result.ok)
        assertTrue(result.summary.contains("Confirm"))
    }

    @Test fun sameIdDifferentCommandsNeedSeparateApproval() = runBlocking {
        val pluginA = UserToolPlugin("shared-id", "Tool A", "Test",
            listOf(ToolInput("host", "Host", ToolInputKind.Host)),
            listOf("program-a", "{host}"), null, "a.json") { null }
        val pluginB = UserToolPlugin("shared-id", "Tool B", "Test",
            listOf(ToolInput("host", "Host", ToolInputKind.Host)),
            listOf("program-b", "{host}"), null, "b.json") { null }
        val values = mapOf("host" to "127.0.0.1")
        pluginA.approval = PluginApproval(pluginA.manifestCommand, pluginA.commandFor(values))
        pluginB.approval = pluginA.approval
        val result = pluginB.run(values) {}
        assertFalse(result.ok)
        assertTrue(result.summary.contains("Confirm"), "Different command template must require re-confirmation")
    }

    // -- Batch file rejection --

    @Test fun batchFilesTruthTable() {
        assertTrue(isBatchFile("script.bat"))
        assertTrue(isBatchFile("script.cmd"))
        assertTrue(isBatchFile("SCRIPT.BAT"))
        assertTrue(isBatchFile("Script.Cmd"))
        assertTrue(isBatchFile("C:\\path\\script.bat"))
        assertTrue(isBatchFile("/usr/bin/script.cmd"))
        assertTrue(isBatchFile("script.bat.  "))
        assertTrue(isBatchFile("script.cmd. "))
        assertTrue(isBatchFile(".bat"))
        assertTrue(isBatchFile(".cmd"))
        assertFalse(isBatchFile("script.batch"))
        assertFalse(isBatchFile("mybat"))
        assertFalse(isBatchFile("ping"))
        assertFalse(isBatchFile("script.exe"))
        assertFalse(isBatchFile(""))
    }

    @Test fun readUserPluginRejectsBatchProgram() {
        val bat = readUserPlugin("bat.json", """{"id":"bat","name":"Bat","summary":"Bad","command":["script.bat"],"inputs":[]}""")
        assertTrue(bat is ManifestRead.Bad)
        assertEquals("Batch files (.bat, .cmd) cannot be the program. Use the program itself.", (bat as ManifestRead.Bad).reason)
        val cmd = readUserPlugin("cmd.json", """{"id":"cmd","name":"Cmd","summary":"Bad","command":["run.CMD"],"inputs":[]}""")
        assertTrue(cmd is ManifestRead.Bad)
        assertTrue((cmd as ManifestRead.Bad).reason.contains("Batch"))
    }

    @Test fun resolveProgramRejectsBatch() {
        assertNull(resolveProgram("script.bat") { "C:\\script.bat" })
        assertNull(resolveProgram("mybat") { "C:\\Windows\\mybat.bat" })
        assertNull(resolveProgram("nonexistent") { null })
    }

    @Test fun runRefusesBatchAfterApproval() = runBlocking {
        val plugin = UserToolPlugin("bat-run", "Bat run", "Test",
            listOf(ToolInput("host", "Host", ToolInputKind.Host)),
            listOf("script.bat", "{host}"), null, "bat-run.json") { "C:\\script.bat" }
        val values = mapOf("host" to "127.0.0.1")
        plugin.approval = PluginApproval(plugin.manifestCommand, plugin.commandFor(values))
        val result = plugin.run(values) {}
        assertFalse(result.ok)
        assertTrue(result.summary.contains("Batch"), result.summary)
    }
}
