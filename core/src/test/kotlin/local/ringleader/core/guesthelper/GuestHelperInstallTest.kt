package local.ringleader.core.guesthelper

import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import local.ringleader.core.AgentExecStatus
import local.ringleader.core.AmbiguousPowerResultException
import local.ringleader.core.Connection
import local.ringleader.core.Guest
import local.ringleader.core.GuestRefusal
import kotlin.test.*

/** Where the bundles come from, the install through the guest agent, the one-time link and the ways in. */
class GuestHelperInstallTest {
    private val dir: Path = Files.createTempDirectory("ring-engine-bundles")
    private val connection = Connection("https://192.0.2.1:8006", "user@pam!t", "secret-for-tests", "")
    private val vm = Guest("https://192.0.2.1:8006", 990, "lab-vm", "node-a", "VM", "running")
    private val ct = Guest("https://192.0.2.1:8006", 991, "lab-ct", "node-a", "Container", "running")
    private val fingerprint = "ab".repeat(32)
    private val pairingLine = "RINGMASTER-ENGINE-PAIRING version=0.1.0 port=8696 sha256=$fingerprint token=AAAAA-BBBBB-CCCCC-DDDDD-EEEEE"

    @AfterTest fun clean() { dir.toFile().deleteRecursively() }

    private fun bundle(bytes: ByteArray = ByteArray(150_000) { (it % 251).toByte() }, arch: String = "x86_64"): Path {
        val file = dir.resolve("ringmaster-engine-0.1.0-linux-$arch.tar.gz")
        Files.write(file, bytes)
        return file
    }

    private fun sums(vararg files: Path) =
        Files.writeString(dir.resolve("ringmaster-engine-0.1.0-SHA256SUMS"), files.joinToString("") { "${sha256Of(it)}  ${it.fileName}\n" })

    // Bundles.

    @Test fun packagedBundlesAreCheckedAgainstTheirSums() {
        val good = bundle()
        val other = bundle(byteArrayOf(1, 2, 3), "aarch64")
        sums(good, other)
        Files.write(other, byteArrayOf(9, 9, 9)) // damaged after the sums were written
        val found = packagedGuestHelperBundles(dir)
        assertEquals(listOf("x86_64"), found.map { it.arch })
        assertTrue(found.single().packaged)
        assertEquals("0.1.0", found.single().version)
    }

    @Test fun withoutSumsOrAFolderThereIsNoBundle() {
        bundle()
        assertEquals(emptyList(), packagedGuestHelperBundles(dir))
        assertEquals(emptyList(), packagedGuestHelperBundles(null))
        assertEquals(emptyList(), packagedGuestHelperBundles(dir.resolve("absent")))
    }

    @Test fun aChosenBundleIsNeverAReleaseFile() {
        val chosen = chosenGuestHelperBundle(bundle()).getOrThrow()
        assertFalse(chosen.packaged)
        assertEquals(64, chosen.sha256.length)
        assertTrue(chosenGuestHelperBundle(dir.resolve("engine.tgz")).isFailure)
    }

    @Test fun theBundleFitsTheGuestsArchitecture() {
        val x86 = GuestHelperBundle("x86_64", "0.1.0", dir, "0".repeat(64), true)
        val arm = GuestHelperBundle("aarch64", "0.1.0", dir, "1".repeat(64), true)
        assertEquals(arm, bundleFor(listOf(x86, arm), "aarch64"))
        assertEquals(x86, bundleFor(listOf(x86, arm), "amd64"))
        assertEquals(x86, bundleFor(listOf(arm, x86), null))
        assertNull(bundleFor(listOf(x86), "riscv64"))
    }

    // What the installer prints.

    @Test fun theInstallersLastLinesAreRead() {
        val text = "plan...\n$pairingLine\nRINGMASTER-ENGINE-ADDRESSES 127.0.0.1 fe80::1 fd00::5 192.0.2.7 \n"
        val installed = parseInstallerOutput(text)!!
        assertEquals(8696, installed.port)
        assertEquals(fingerprint, installed.fingerprint)
        assertEquals("AAAAA-BBBBB-CCCCC-DDDDD-EEEEE", installed.token?.concatToString())
        assertEquals(listOf("192.0.2.7", "fd00::5"), installed.addresses)
        assertFalse("AAAAA" in installed.toString(), "the token never shows in a log line")
    }

    @Test fun anUpgradeHasNoTokenAndOtherTextIsNothing() {
        val ready = parseInstallerOutput("RINGMASTER-ENGINE-READY version=0.1.0 port=8696 sha256=$fingerprint\n")!!
        assertNull(ready.token)
        assertNull(parseInstallerOutput("RingMASTER Engine was not installed: run this as root."))
        assertNull(parseInstallerOutput("RINGMASTER-ENGINE-PAIRING version=0.1.0 port=8696 sha256=short token=x"))
    }

    @Test fun theInstallScriptChecksTheHashBeforeItRunsAnything() {
        val script = installScript("/var/tmp/b.tar.gz", "c".repeat(64), assumeYes = true)
        val check = script.lines().indexOfFirst { "sha256sum -c -" in it }
        val run = script.lines().indexOfFirst { "install.sh" in it }
        assertTrue(check in 0 until run, script)
        assertTrue(script.lines()[run].endsWith("install.sh --yes"))
        assertFalse("--yes" in installScript("/var/tmp/b.tar.gz", "c".repeat(64), assumeYes = false))
        assertFailsWith<IllegalArgumentException> { installScript("/x", "not-a-hash", true) }
    }

    @Test fun theLinkCommandIsOneLineThatFetchesChecksAndInstalls() {
        val command = linkCommand("http://192.0.2.9:41234/abc/ringmaster-engine-0.1.0-linux-x86_64.tar.gz", "d".repeat(64))
        assertFalse('\n' in command)
        assertTrue(command.startsWith("( set -e; umask 077; work=$(mktemp -d "))
        assertTrue(command.indexOf("mktemp -d") < command.indexOf("wget -qO"))
        assertFalse("/var/tmp/rme.tgz" in command)
        assertTrue("curl -fsSo \"\$work/bundle.tgz\"" in command)
        assertTrue("d".repeat(64) in command)
    }

    // The guest agent way.

    private class Channel(val status: AgentExecStatus = AgentExecStatus(true, 0, "", "", false), val lostAt: Int = -1) : GuestAgentChannel {
        val files = mutableListOf<Pair<String, String>>()
        val commands = mutableListOf<List<String>>()
        override fun writeFile(connection: Connection, guest: Guest, path: String, base64: String) {
            if (files.size == lostAt) throw AmbiguousPowerResultException()
            files += path to base64
        }
        override fun run(connection: Connection, guest: Guest, command: List<String>, firstPollSeconds: Int, budgetSeconds: Int): AgentExecStatus {
            commands += command
            return if (command.last().startsWith("umask 077; mkdir") || command.last().startsWith("rm -rf --")) {
                AgentExecStatus(true, 0, "", "", false)
            } else status
        }
    }

    private fun installed(out: String) = AgentExecStatus(true, 0, "$out\nRINGMASTER-ENGINE-ADDRESSES 192.0.2.7\n", "", false)

    @Test fun theBundleGoesInAsPartsThatJoinBackToItself() {
        val bytes = ByteArray(150_000) { (it * 7 % 256).toByte() }
        val file = bundle(bytes)
        val channel = Channel(installed(pairingLine))
        val progress = mutableListOf<GuestHelperProgress>()
        val result = GuestAgentInstaller(channel).install(connection, vm, chosenGuestHelperBundle(file).getOrThrow()) { progress += it }
        assertEquals(4, channel.files.size, "200,000 base64 characters in parts of 61,440")
        assertTrue(channel.files.all { it.second.length <= GUEST_AGENT_PART_CHARS && it.second.length % 4 == 0 || it == channel.files.last() })
        val joined = channel.files.flatMap { Base64.getDecoder().decode(it.second).toList() }.toByteArray()
        assertContentEquals(bytes, joined)
        assertEquals(channel.files.map { it.first }.sorted(), channel.files.map { it.first }, "part names sort in order")
        val script = channel.commands.last().last()
        assertTrue("sha256sum -c -" in script && "install.sh --yes" in script, script)
        assertEquals(listOf("192.0.2.7"), result.addresses)
        assertEquals(GuestHelperProgress(4, 4, installing = true), progress.last())
    }

    @Test fun aLostPartStopsTheInstallAndNothingRuns() {
        val channel = Channel(installed(pairingLine), lostAt = 1)
        val e = assertFailsWith<IllegalStateException> {
            GuestAgentInstaller(channel).install(connection, vm, chosenGuestHelperBundle(bundle()).getOrThrow())
        }
        assertEquals(GUEST_HELPER_DELIVERY_LOST, e.message)
        assertEquals(1, channel.files.size, "the lost part is not sent again")
        assertEquals(2, channel.commands.size, "create and clean only this staging directory; no installer exec")
        assertTrue(channel.commands.first().last().contains("mkdir -m 700"))
        assertTrue(channel.commands.last().last().startsWith("rm -rf --"))
    }

    @Test fun anInstallerThatStoppedIsReportedWithItsLastLine() {
        val failed = AgentExecStatus(true, 1, "", "RingMASTER Engine was not installed: run this as root.\n", false)
        val e = assertFailsWith<IllegalStateException> {
            GuestAgentInstaller(Channel(failed)).install(connection, vm, chosenGuestHelperBundle(bundle()).getOrThrow())
        }
        assertEquals("The installer did not finish. Its last line: RingMASTER Engine was not installed: run this as root.", e.message)
    }

    @Test fun removalNeedsTheUninstallersMarker() {
        val removed = Channel(AgentExecStatus(true, 0, "...\n$GUEST_HELPER_REMOVED\n", "", false))
        GuestAgentInstaller(removed).remove(connection, vm)
        assertEquals(listOf("sh", "-c", "$GUEST_HELPER_UNINSTALL --yes"), removed.commands.single())
        val left = Channel(AgentExecStatus(true, 1, "Not everything could be removed: account:ringmaster-engine\n", "", false))
        assertFailsWith<IllegalStateException> { GuestAgentInstaller(left).remove(connection, vm) }
    }

    // The one-time link.

    private fun get(url: String): Pair<Int, ByteArray> {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 3_000
        c.readTimeout = 3_000
        return try {
            c.responseCode to (if (c.responseCode == 200) c.inputStream.readAllBytes() else ByteArray(0))
        } finally { c.disconnect() }
    }

    private fun waitFor(link: OneTimeLink, state: OneTimeLinkState) {
        val end = System.nanoTime() + 5_000_000_000L
        while (link.status.value != state && System.nanoTime() < end) Thread.sleep(20)
        assertEquals(state, link.status.value)
    }

    @Test fun theLinkServesTheBundleOnceAtItsPathAndCloses() {
        val bytes = ByteArray(70_000) { (it % 13).toByte() }
        val chosen = chosenGuestHelperBundle(bundle(bytes)).getOrThrow()
        OneTimeLink.open(chosen, "127.0.0.1").use { link ->
            assertTrue(link.url.startsWith("http://127.0.0.1:"))
            assertTrue(link.url.endsWith("/ringmaster-engine-0.1.0-linux-x86_64.tar.gz"))
            assertEquals(404, get(link.url.substringBeforeLast('/') + "x/other").first, "a wrong path learns nothing")
            assertEquals(OneTimeLinkState.Waiting, link.status.value)
            val (code, body) = get(link.url)
            assertEquals(200, code)
            assertContentEquals(bytes, body)
            waitFor(link, OneTimeLinkState.Served)
            assertFailsWith<java.io.IOException> { get(link.url) }
            assertTrue(chosen.sha256 in link.command)
        }
    }

    @Test fun anUnusedLinkExpires() {
        val chosen = chosenGuestHelperBundle(bundle()).getOrThrow()
        OneTimeLink.open(chosen, "127.0.0.1", Duration.ofMillis(300)).use { link -> waitFor(link, OneTimeLinkState.Expired) }
    }

    // The ways in.

    @Test fun theGuestAgentComesFirstWhereItMayBeUsed() {
        val packaged = GuestHelperBundle("x86_64", "0.1.0", dir, "0".repeat(64), packaged = true)
        val chosen = packaged.copy(packaged = false)
        val all = listOf(GuestHelperWay.GuestAgent, GuestHelperWay.OneTimeLink, GuestHelperWay.ReleaseCommand)
        assertEquals(all, guestHelperWays(vm, null, packaged))
        assertEquals(all.drop(1), guestHelperWays(vm, GuestRefusal.AgentOff, packaged))
        assertEquals(all.drop(1), guestHelperWays(ct, GuestRefusal.Container, packaged))
        assertEquals(listOf(GuestHelperWay.GuestAgent, GuestHelperWay.OneTimeLink), guestHelperWays(vm, null, chosen))
        assertEquals(emptyList(), guestHelperWays(vm, null, null))
    }
}
