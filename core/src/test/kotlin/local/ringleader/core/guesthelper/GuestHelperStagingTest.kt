package local.ringleader.core.guesthelper

import java.nio.file.Files
import local.ringleader.core.AgentExecStatus
import local.ringleader.core.AmbiguousPowerResultException
import local.ringleader.core.Connection
import local.ringleader.core.Guest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuestHelperStagingTest {
    private val dir = Files.createTempDirectory("engine-staging")
    private val connection = Connection("https://192.0.2.1:8006", "fixture@pam!fixture", "fixture-secret", "")
    private val guest = Guest(connection.address, 900, "fixture", "node", "VM", "running")

    @AfterTest fun clean() { dir.toFile().deleteRecursively() }

    private fun bundle(): GuestHelperBundle {
        val file = dir.resolve("ringmaster-engine-0.1.0-linux-x86_64.tar.gz")
        Files.write(file, ByteArray(100))
        return chosenGuestHelperBundle(file).getOrThrow()
    }

    private class Channel(val prepareExit: Int = 0, val failUpload: Boolean = false) : GuestAgentChannel {
        val events = mutableListOf<String>()
        override fun writeFile(connection: Connection, guest: Guest, path: String, base64: String) {
            events += "write:$path"
            if (failUpload) throw AmbiguousPowerResultException()
        }
        override fun run(
            connection: Connection, guest: Guest, command: List<String>, firstPollSeconds: Int, budgetSeconds: Int,
        ): AgentExecStatus {
            val script = command.last()
            events += script
            val code = if (script.startsWith("umask 077; mkdir")) prepareExit else 0
            val ready = "RINGMASTER-ENGINE-READY version=0.1.0 port=8696 sha256=${"ab".repeat(32)}"
            return AgentExecStatus(true, code, ready, "", false)
        }
    }

    @Test fun uploadCannotStartUntilAnOwnerOnlyDirectoryIsCreated() {
        val channel = Channel()
        GuestAgentInstaller(channel).install(connection, guest, bundle())
        val setup = channel.events.first()
        assertTrue(setup.startsWith("umask 077; mkdir -m 700 -- '/var/tmp/ringmaster-engine-"))
        val work = setup.substringAfter("-- '").removeSuffix("'")
        assertTrue(channel.events[1].startsWith("write:$work/part-"))
        val install = channel.events.last()
        assertTrue("work='$work'" in install)
        assertTrue("trap 'rm -rf -- \"\$work\"' EXIT" in install)
        assertTrue(install.indexOf("sha256sum -c -") < install.indexOf("tar -xzf"))
        assertFalse("find /var/tmp" in install)
    }

    @Test fun aFailedDirectoryCreationDoesNotUploadOrRemoveAnExistingPath() {
        val channel = Channel(prepareExit = 1)
        assertFailsWith<IllegalStateException> { GuestAgentInstaller(channel).install(connection, guest, bundle()) }
        assertEquals(1, channel.events.size)
    }

    @Test fun aFailedUploadCleansOnlyItsOwnDirectoryAndNeverRetries() {
        val first = Channel(failUpload = true)
        val second = Channel(failUpload = true)
        for (channel in listOf(first, second)) {
            assertFailsWith<IllegalStateException> { GuestAgentInstaller(channel).install(connection, guest, bundle()) }
            assertEquals(3, channel.events.size)
            val work = channel.events.first().substringAfter("-- '").removeSuffix("'")
            assertEquals("rm -rf -- '$work'", channel.events.last())
            assertEquals(1, channel.events.count { it.startsWith("write:") })
        }
        assertTrue(first.events.first() != second.events.first())
        assertFalse(first.events.last() in second.events)
    }

    @Test fun aDownloadStagesEverythingPrivatelyAndQuotesTheUrl() {
        val command = linkCommand("http://192.0.2.1/quote'\$(id);bundle", "ab".repeat(32))
        assertTrue(command.indexOf("umask 077") < command.indexOf("mktemp"))
        assertTrue(command.indexOf("mktemp") < command.indexOf("wget"))
        assertTrue("quote'\\''\$(id);bundle'" in command)
        assertTrue("\"\$work/bundle.tgz\"" in command)
        assertFalse("/var/tmp/rme.tgz" in command)
    }
}
