package local.ringleader.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.test.*

/**
 * Open SSH over Tailscale SSH (the SSH contract): reading one peer's keys, known-hosts rendering for every key type,
 * validation, the ssh and terminal argument vectors on both platforms, which peer a node or guest is, the known-hosts files
 * and the launch. Invented data only (example.ts.net, 100.64.0.x, documentation ranges, private example ranges).
 */
class SshLaunchTest {
    private fun blob(type: String, extra: Int = 32): String {
        val t = type.toByteArray()
        val bytes = byteArrayOf(0, 0, 0, t.size.toByte()) + t + ByteArray(extra) { (it * 7).toByte() }
        return Base64.getEncoder().encodeToString(bytes)
    }
    private fun key(type: String, comment: String = "") = "$type ${blob(type)}" + if (comment.isNotEmpty()) " $comment" else ""

    private val ed = key("ssh-ed25519", "root@pve-alpha")
    private val ecdsa = key("ecdsa-sha2-nistp256")
    private val rsa = key("ssh-rsa")

    private fun statusJson(keys: List<String> = listOf(ed, ecdsa, rsa), online: Boolean = true, top: String = """"TUN":true,""") = """
        {"BackendState":"Running",$top"Self":{"ID":"self","HostName":"desk-01","TailscaleIPs":["100.64.0.1"],"UserID":7},
         "Peer":{
          "a":{"ID":"a","HostName":"pve-alpha","DNSName":"pve-alpha.example.ts.net.","TailscaleIPs":["100.64.0.10","fd7a:115c:a1e0::a"],"Online":$online,"UserID":7,
               "sshHostKeys":${keys.joinToString(",", "[", "]") { "\"$it\"" }}},
          "b":{"ID":"b","HostName":"pve-beta","TailscaleIPs":["100.64.0.11"],"Online":true,"UserID":7},
          "r":{"ID":"r","HostName":"subnet-gw","TailscaleIPs":["100.64.0.20"],"Online":true,"UserID":7,"PrimaryRoutes":["10.20.30.0/24"]},
          "m":{"ID":"m","HostName":"se-sto-wg-001","TailscaleIPs":["100.64.0.60"],"Online":true,"Location":{"Country":"Sweden"},"sshHostKeys":["$ed"]}
         }}"""

    // ------------------------------------------------------------------ the keys, read for one peer

    @Test fun oneFreshReadReturnsOnlyThatPeersKeysAndNeverPrintsThem() {
        val k = parsePeerSshHostKeys(statusJson(), "a")!!
        assertEquals(listOf(ed, ecdsa, rsa), k.keys); assertEquals("pve-alpha.example.ts.net", k.dnsName); assertEquals(listOf("100.64.0.10", "fd7a:115c:a1e0::a"), k.addresses)
        assertEquals("SshPeerKeys(peer=a, keys=3)", k.toString(), "keys never reach a log line or trace through toString")
        assertNull(parsePeerSshHostKeys(statusJson(), "gone"))
        // The status model keeps counts and types only.
        val s = parseTailscaleStatus(statusJson())
        assertFalse(s.toString().contains(blob("ssh-ed25519")), "the model never holds a key")
        assertEquals(3, s.peers.first { it.id == "a" }.sshHostKeyCount)
    }

    @Test fun theBackendReadsKeysWithOneStatusCall() {
        val calls = mutableListOf<List<String>>()
        val b = LocalVpnBackend("Linux", { c, _ -> calls += c; statusJson() }, { if (it == "tailscale") "/usr/bin/tailscale" else null })
        assertEquals(3, b.tailscaleSshHostKeys("a")!!.keys.size)
        assertEquals(listOf(listOf("/usr/bin/tailscale", "status", "--json")), calls)
        assertNull(LocalVpnBackend("Linux", { _, _ -> error("no") }, { null }).tailscaleSshHostKeys("a"), "no Tailscale, no keys")
    }

    // ------------------------------------------------------------------ known-hosts rendering and validation

    @Test fun knownHostsHasOneLinePerValidKeyForEveryType() {
        val types = SSH_HOST_KEY_TYPES.toList()
        val text = knownHostsText("pve-alpha.example.ts.net", types.map { key(it, "a comment") })
        val lines = text.trimEnd().lines()
        assertEquals(types.size, lines.size)
        for ((i, t) in types.withIndex()) assertEquals("pve-alpha.example.ts.net $t ${blob(t)}", lines[i], "the comment is dropped")
        assertTrue(text.endsWith("\n"))
    }

    @Test fun malformedKeysAreNotWritten() {
        assertNull(sshHostKeyEntry("ssh-dss ${blob("ssh-dss")}"), "an unknown type")
        assertNull(sshHostKeyEntry("ssh-ed25519 ${blob("ssh-rsa")}"), "the blob names another type")
        assertNull(sshHostKeyEntry("ssh-ed25519 not*base64*at*all"))
        assertNull(sshHostKeyEntry("ssh-ed25519 ${blob("ssh-ed25519")}\n@cert-authority * ssh-ed25519 AAAA"), "a second line")
        assertNull(sshHostKeyEntry("ssh-ed25519"))
        assertEquals("ssh-ed25519" to blob("ssh-ed25519"), sshHostKeyEntry("  ssh-ed25519   ${blob("ssh-ed25519")}  x  "))
        assertFailsWith<IllegalArgumentException> { knownHostsText("pve-alpha.example.ts.net", listOf("ssh-dss AAAA")) }
        for (alias in listOf("", "a b", "a,b", "*", "!x", "|1|x", "[100.64.0.10]:22", "#x")) assertFailsWith<IllegalArgumentException>(alias) { knownHostsText(alias, listOf(ed)) }
    }

    @Test fun theAliasIsThePlainDnsNameOrTheAddress() {
        assertEquals("pve-alpha.example.ts.net", sshHostAlias("PVE-Alpha.example.ts.net.", "100.64.0.10"))
        assertEquals("100.64.0.10", sshHostAlias(null, "100.64.0.10"))
        assertEquals("100.64.0.10", sshHostAlias("bad name.example.ts.net", "100.64.0.10"))
        assertEquals("100.64.0.10", sshHostAlias("-oProxyCommand=x", "100.64.0.10"))
        assertEquals("fd7a:115c:a1e0:0:0:0:0:a", sshHostAlias("*.example.ts.net", "fd7a:115c:a1e0::a"))
    }

    @Test fun userNamesFollowAStrictPattern() {
        for (ok in listOf("root", "ring", "_svc", "ring-1", "a".repeat(32), "user_2")) assertTrue(validSshUser(ok), ok)
        for (bad in listOf("", "-oProxyCommand=calc", "-l", "Root", "a b", "user;rm", "user@host", "a".repeat(33), "ränk", "1user", "user\n", "us\$er", "..", "user/x"))
            assertFalse(validSshUser(bad), bad)
    }

    @Test fun theSshArgumentVectorIsFixedAndCannotTakeAnOption() {
        assertEquals(listOf("/usr/bin/ssh", "-l", "root", "-o", "UserKnownHostsFile=/srv/people/alex/.config/ringmaster/ssh-known-hosts/kh-1.txt",
            "-o", "StrictHostKeyChecking=yes", "-o", "HostKeyAlias=pve-alpha.example.ts.net", "-o", "UpdateHostKeys=no", "-o", "CheckHostIP=no",
            "-o", "CanonicalizeHostname=no", "100.64.0.10"),
            sshArguments("/usr/bin/ssh", "/srv/people/alex/.config/ringmaster/ssh-known-hosts/kh-1.txt", "pve-alpha.example.ts.net", "100.64.0.10", "root"))
        assertEquals("fd7a:115c:a1e0:0:0:0:0:a", sshArguments("ssh", "k", "a", "fd7a:115c:a1e0::a", "root").last(), "an IPv6 literal, normalised")
        assertFailsWith<IllegalArgumentException> { sshArguments("ssh", "k", "a", "100.64.0.10", "-oProxyCommand=calc") }
        assertFailsWith<IllegalArgumentException> { sshArguments("ssh", "k", "a", "-oProxyCommand=calc", "root") }
        assertFailsWith<IllegalArgumentException> { sshArguments("ssh", "k", "a", "pve-alpha.example.ts.net", "root") }
        assertFailsWith<IllegalArgumentException> { sshArguments("ssh", "k", "-x", "100.64.0.10", "root") }
    }

    @Test fun theKnownHostsPathIsPassedSoSshCannotSplitIt() {
        // Paths are this platform's own (the function runs where the file is).
        if (!System.getProperty("os.name").startsWith("Windows")) {
            val home = Path.of("/srv/people/alex")
            assertEquals("/srv/people/alex/.config/ringmaster/ssh-known-hosts/kh-1.txt", knownHostsArgument(Path.of("/srv/people/alex/.config/ringmaster/ssh-known-hosts/kh-1.txt"), home, false))
            assertEquals("/data/50%%/kh-1.txt", knownHostsArgument(Path.of("/data/50%/kh-1.txt"), home, false), "% is an ssh token")
            val spaced = Path.of("/srv/people/alex smith")
            assertEquals("~/.config/ringmaster/ssh-known-hosts/kh-1.txt", knownHostsArgument(spaced.resolve(".config/ringmaster/ssh-known-hosts/kh-1.txt"), spaced, false))
            assertNull(knownHostsArgument(Path.of("/srv/odd dir/kh-1.txt"), home, false), "a space outside home: nothing is launched")
            assertNull(knownHostsArgument(Path.of("/srv/o'dd/kh-1.txt"), home, false))
        } else {
            val home = Path.of("D:\\People\\alex")
            assertEquals("D:/People/alex/.config/ringmaster/ssh-known-hosts/kh-1.txt", knownHostsArgument(home.resolve(".config\\ringmaster\\ssh-known-hosts\\kh-1.txt"), home, true))
            assertEquals("C:/data/50%%/kh-1.txt", knownHostsArgument(Path.of("C:\\data\\50%\\kh-1.txt"), home, true), "% is an ssh token")
            val spaced = Path.of("D:\\People\\Alex Smith")
            assertEquals("~/.config/ringmaster/kh-1.txt", knownHostsArgument(spaced.resolve(".config\\ringmaster\\kh-1.txt"), spaced, true))
            assertNull(knownHostsArgument(Path.of("D:\\odd dir\\kh-1.txt"), home, true), "a space outside home: nothing is launched")
            assertNull(knownHostsArgument(Path.of("C:\\o'dd\\kh-1.txt"), home, true))
        }
    }

    // ------------------------------------------------------------------ terminals

    private val winEnv = mapOf("SystemRoot" to "C:\\Windows", "LOCALAPPDATA" to "D:\\People\\alex\\AppData\\Local")
    private val wt = "D:\\People\\alex\\AppData\\Local\\Microsoft\\WindowsApps\\wt.exe"
    private val conhost = "C:\\Windows\\System32\\conhost.exe"
    private val ssh = listOf("ssh", "-l", "root", "100.64.0.10")

    @Test fun windowsUsesWindowsTerminalElseAConsoleWindow() {
        assertEquals(TerminalChoice("Windows Terminal", wt, TerminalStyle.WindowsTerminal), findTerminal(true, winEnv::get, { it == wt || it == conhost }, { null }))
        assertEquals(TerminalChoice("a console window", conhost, TerminalStyle.Conhost), findTerminal(true, winEnv::get, { it == conhost }, { null }))
        assertNull(findTerminal(true, winEnv::get, { false }, { null }))
        assertEquals("C:\\Windows\\System32\\OpenSSH\\ssh.exe", windowsSshPath(winEnv::get))
        assertEquals(listOf(wt, "new-tab", "--title", "SSH") + ssh, terminalCommand(TerminalChoice("Windows Terminal", wt, TerminalStyle.WindowsTerminal), ssh))
        assertEquals(listOf(conhost) + ssh, terminalCommand(TerminalChoice("a console window", conhost, TerminalStyle.Conhost), ssh))
        assertFailsWith<IllegalArgumentException> { terminalCommand(TerminalChoice("Windows Terminal", wt, TerminalStyle.WindowsTerminal), ssh + "a;b") }
    }

    @Test fun linuxTriesTheDesktopsTerminalFirstAndEachGetsItsOwnForm() {
        val all = LINUX_TERMINALS.associate { (name, _) -> name to "/usr/bin/$name" }
        assertEquals("x-terminal-emulator", findTerminal(false, { null }, { false }, all::get)!!.name)
        assertEquals("xterm", findTerminal(false, { null }, { false }, { if (it == "xterm") "/usr/bin/xterm" else null })!!.name)
        assertNull(findTerminal(false, { null }, { false }, { if (it == "xterm") "bin/xterm" else null }), "a relative path is never used")
        assertNull(findTerminal(false, { null }, { false }, { null }))
        val expected = mapOf(
            "x-terminal-emulator" to listOf("-e"), "xdg-terminal-exec" to emptyList(), "gnome-terminal" to listOf("--"), "ptyxis" to listOf("--"),
            "konsole" to listOf("-e"), "xfce4-terminal" to listOf("-x"), "mate-terminal" to listOf("-x"), "kitty" to emptyList(),
            "alacritty" to listOf("-e"), "foot" to emptyList(), "xterm" to listOf("-e"))
        assertEquals(expected.keys.toList(), LINUX_TERMINALS.map { it.first })
        for ((name, style) in LINUX_TERMINALS)
            assertEquals(listOf("/usr/bin/$name") + expected.getValue(name) + ssh, terminalCommand(TerminalChoice(name, "/usr/bin/$name", style), ssh), name)
    }

    // ------------------------------------------------------------------ which peer a node or guest is

    @Test fun matchingIsByAddressOrByTheConnectionsOwnPeer() {
        val s = parseTailscaleStatus(statusJson())
        val ready = assertIs<SshAvailability.Ready>(sshAvailability(s, listOf("192.168.1.5", "100.64.0.10")))
        assertEquals("a", ready.peer.id); assertEquals("100.64.0.10", ready.address)
        assertIs<SshAvailability.Ready>(sshAvailability(s, listOf("fd7a:115c:a1e0:0:0:0:0:a")), "an IPv6 tailnet address matches")
        assertIs<SshAvailability.Ready>(sshAvailability(s, emptyList(), "pve-alpha", "100.64.0.10"), "the connection goes to the node's own peer")
        assertIs<SshAvailability.Ready>(sshAvailability(s, emptyList(), "pve-alpha", "pve-alpha.example.ts.net"), "or to its MagicDNS name")
        assertEquals(SshAvailability.NotOnTailnet, sshAvailability(s, emptyList(), "pve-alpha", "192.168.1.5"), "a name alone is never enough")
        assertEquals(SshAvailability.NotOnTailnet, sshAvailability(s, emptyList(), "pve-gamma", "100.64.0.10"), "the connection's peer is another machine")
        assertIs<SshAvailability.NoKeys>(sshAvailability(s, listOf("100.64.0.11")))
        assertIs<SshAvailability.Offline>(sshAvailability(parseTailscaleStatus(statusJson(online = false)), listOf("100.64.0.10")))
        assertEquals("subnet-gw", assertIs<SshAvailability.ThroughRouter>(sshAvailability(s, listOf("10.20.30.15"))).route.routerName)
        assertEquals(SshAvailability.NotOnTailnet, sshAvailability(s, listOf("100.64.0.60")), "a location-based exit node is never offered")
        assertEquals(SshAvailability.NotOnTailnet, sshAvailability(s, listOf("192.168.1.5")))
        assertIs<SshAvailability.Unavailable>(sshAvailability(null, listOf("100.64.0.10")))
        assertIs<SshAvailability.Unavailable>(sshAvailability(parseTailscaleStatus(statusJson().replace("\"Running\"", "\"Stopped\"")), listOf("100.64.0.10")))
        assertEquals(TAILSCALE_USERSPACE_NOTE, (sshAvailability(parseTailscaleStatus(statusJson(top = """"TUN":false,""")), listOf("100.64.0.10")) as SshAvailability.Unavailable).reason)
    }

    @Test fun theNoButtonLineSaysWhy() {
        val s = parseTailscaleStatus(statusJson())
        assertNull(sshNoButtonReason(sshAvailability(s, listOf("100.64.0.10"))))
        assertTrue(sshNoButtonReason(sshAvailability(s, listOf("100.64.0.11")))!!.contains("pve-beta does not run Tailscale SSH"))
        assertTrue(sshNoButtonReason(sshAvailability(s, listOf("100.64.0.11"))) { "this machine" }!!.contains("this machine does not run"), "names can be concealed")
        assertTrue(sshNoButtonReason(sshAvailability(s, listOf("10.20.30.15")))!!.contains("subnet router subnet-gw"))
        assertNull(sshNoButtonReason(SshAvailability.NotOnTailnet))
        assertTrue(SSH_CHECK_MODE_NOTE.contains("check mode") && SSH_CHECK_MODE_NOTE.contains("browser"))
    }

    // ------------------------------------------------------------------ files and the launch

    private fun store(dir: Path, clock: () -> Instant = Instant::now) = SshKnownHostsStore(dir.resolve("ssh-known-hosts"), clock)

    @Test fun knownHostsFilesAreOwnerOnlyAndCleanedUp() {
        val dir = Files.createTempDirectory("ssh-store")
        try {
            val st = store(dir)
            val f = st.write("pve-alpha.example.ts.net ssh-ed25519 AAAA\n")
            assertEquals("pve-alpha.example.ts.net ssh-ed25519 AAAA\n", Files.readString(f))
            assertTrue(f.fileName.toString().matches(Regex("kh-[0-9a-f-]{36}\\.txt")))
            if ("posix" in f.fileSystem.supportedFileAttributeViews()) {
                assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(f))
                assertEquals(ownerDirectoryPermissions(), Files.getPosixFilePermissions(st.directory))
            } else {
                val acl = Files.getFileAttributeView(f, AclFileAttributeView::class.java).acl
                assertEquals(1, acl.size, "one entry: the current user")
                assertTrue(acl.single().principal().name.substringAfterLast('\\').equals(System.getProperty("user.name"), ignoreCase = true) ||
                    acl.single().principal() == Files.getOwner(f), "${acl.single().principal().name}")
            }
            val second = st.write("x\n")
            assertNotEquals(f, second, "one file per launch")
            val other = st.directory.resolve("keep.txt").also { Files.writeString(it, "not ours") }
            Files.setLastModifiedTime(f, FileTime.from(Instant.now().minus(Duration.ofHours(25))))
            assertEquals(1, st.cleanup()); assertFalse(Files.exists(f)); assertTrue(Files.exists(second))
            assertEquals(1, st.removeAll()); assertFalse(Files.exists(second)); assertTrue(Files.exists(other), "only the app's own files are removed")
        } finally { dir.toFile().deleteRecursively() }
    }

    private fun env(dir: Path, windows: Boolean, present: Set<String> = emptySet(), which: Map<String, String> = emptyMap(), started: MutableList<List<String>> = mutableListOf(), exit: Int? = null) =
        SshLaunchEnvironment(windows, winEnv::get, { it in present }, which::get, dir, store(dir), { started += it; exit })

    @Test fun aWindowsLaunchWritesTheFileAndStartsWindowsTerminalOnce() {
        val dir = Files.createTempDirectory("ssh-launch")
        try {
            val started = mutableListOf<List<String>>()
            val e = env(dir, true, setOf(wt, conhost, "C:\\Windows\\System32\\OpenSSH\\ssh.exe"), started = started)
            val peer = parseTailscaleStatus(statusJson()).peers.first { it.id == "a" }
            val plan = planTailscaleSsh(peer, "100.64.0.10", parsePeerSshHostKeys(statusJson(), "a"), "root", e)
            val kh = knownHostsArgument(plan.knownHosts, dir, true)!!
            assertEquals(listOf(wt, "new-tab", "--title", "SSH", "C:\\Windows\\System32\\OpenSSH\\ssh.exe", "-l", "root", "-o", "UserKnownHostsFile=$kh",
                "-o", "StrictHostKeyChecking=yes", "-o", "HostKeyAlias=pve-alpha.example.ts.net", "-o", "UpdateHostKeys=no", "-o", "CheckHostIP=no",
                "-o", "CanonicalizeHostname=no", "100.64.0.10"), plan.command)
            assertEquals(listOf(ed, ecdsa, rsa).map { "pve-alpha.example.ts.net " + it.split(' ').take(2).joinToString(" ") }.joinToString("\n", postfix = "\n"), Files.readString(plan.knownHosts))
            assertEquals("Opened in Windows Terminal.", launchTailscaleSsh(plan, e))
            assertEquals(listOf(plan.command), started, "started once")
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun aLinuxLaunchUsesTheSystemSshAndTheFirstTerminal() {
        val dir = Files.createTempDirectory("ssh-launch")
        try {
            val e = env(dir, false, which = mapOf("ssh" to "/usr/bin/ssh", "gnome-terminal" to "/usr/bin/gnome-terminal", "xterm" to "/usr/bin/xterm"))
            val peer = parseTailscaleStatus(statusJson()).peers.first { it.id == "a" }
            val plan = planTailscaleSsh(peer, "100.64.0.10", parsePeerSshHostKeys(statusJson(), "a"), "ring", e)
            assertEquals(listOf("/usr/bin/gnome-terminal", "--", "/usr/bin/ssh", "-l", "ring"), plan.command.take(5))
            assertEquals("100.64.0.10", plan.command.last())
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun nothingIsWrittenOrStartedWhenTheFreshReadDisagrees() {
        val dir = Files.createTempDirectory("ssh-launch")
        try {
            val started = mutableListOf<List<String>>()
            val e = env(dir, true, setOf(wt, "C:\\Windows\\System32\\OpenSSH\\ssh.exe"), started = started)
            val peer = parseTailscaleStatus(statusJson()).peers.first { it.id == "a" }
            val keys = parsePeerSshHostKeys(statusJson(), "a")!!
            fun fails(expect: String, block: () -> Unit) = assertTrue(assertFails(block).message!!.contains(expect), expect)
            fails("no longer on the tailnet") { planTailscaleSsh(peer, "100.64.0.10", null, "root", e) }
            fails("offline now") { planTailscaleSsh(peer, "100.64.0.10", parsePeerSshHostKeys(statusJson(online = false), "a"), "root", e) }
            fails("address changed") { planTailscaleSsh(peer, "100.64.0.99", keys, "root", e) }
            fails("machine changed") { planTailscaleSsh(peer.copy(id = "z"), "100.64.0.10", keys, "root", e) }
            fails("no longer publishes usable SSH host keys") { planTailscaleSsh(peer, "100.64.0.10", parsePeerSshHostKeys(statusJson(keys = listOf("ssh-dss AAAA")), "a"), "root", e) }
            fails("lower-case") { planTailscaleSsh(peer, "100.64.0.10", keys, "-oProxyCommand=calc", e) }
            fails("OpenSSH Client") { planTailscaleSsh(peer, "100.64.0.10", keys, "root", env(dir, true, setOf(wt))) }
            fails("ssh program is not installed") { planTailscaleSsh(peer, "100.64.0.10", keys, "root", env(dir, false, which = mapOf("xterm" to "/usr/bin/xterm"))) }
            fails("No terminal program") { planTailscaleSsh(peer, "100.64.0.10", keys, "root", env(dir, false, which = mapOf("ssh" to "/usr/bin/ssh"))) }
            assertTrue(started.isEmpty())
            assertEquals(0, store(dir).removeAll(), "no known-hosts file was left behind")
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun aTerminalThatFailsIsReportedAndNotRetried() {
        val dir = Files.createTempDirectory("ssh-launch")
        try {
            val started = mutableListOf<List<String>>()
            val e = env(dir, false, which = mapOf("ssh" to "/usr/bin/ssh", "xterm" to "/usr/bin/xterm"), started = started, exit = 2)
            val peer = parseTailscaleStatus(statusJson()).peers.first { it.id == "a" }
            val plan = planTailscaleSsh(peer, "100.64.0.10", parsePeerSshHostKeys(statusJson(), "a"), "root", e)
            assertEquals("Xterm closed at once (exit 2).", assertFails { launchTailscaleSsh(plan, e) }.message)
            assertEquals(1, started.size)
            val throwing = SshLaunchEnvironment(false, { null }, { false }, { null }, dir, store(dir), { error("exec failed") })
            val plan2 = planTailscaleSsh(peer, "100.64.0.10", parsePeerSshHostKeys(statusJson(), "a"), "root", e)
            assertEquals("Xterm could not be started.", assertFails { launchTailscaleSsh(plan2, throwing) }.message)
            assertFalse(Files.exists(plan2.knownHosts), "the file of a launch that never started is removed")
        } finally { dir.toFile().deleteRecursively() }
    }
}
