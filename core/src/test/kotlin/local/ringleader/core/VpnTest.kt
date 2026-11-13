package local.ringleader.core

import kotlin.test.*
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList

class VpnTest {
    private val ts = """{"Version":"1.104","BackendState":"Running","CurrentTailnet":{"Name":"test"},"Self":{"ID":"s","TailscaleIPs":["100.64.0.1"]},"Peer":{"a":{"ID":"a","HostName":"Zulu","Online":false},"b":{"ID":"b","HostName":"alpha","DNSName":"alpha.test.","Online":true,"TailscaleIPs":["100.64.0.2"],"ExitNode":true,"CurAddr":"","LastSeen":"0001-01-01T00:00:00Z"}},"Health":null}"""
    @Test fun tailscaleParser() { val s = parseTailscaleStatus(ts); assertTrue(s.running); assertEquals("alpha", s.peers.first().hostName); assertEquals("alpha.test", s.exitNode?.dnsName); assertNull(s.exitNode?.direct); assertNull(s.exitNode?.lastSeen); assertTrue(s.health.isEmpty()) }
    @Test fun dumpParser() { val w = parseWgDump("wg0\tSECRET\tpublic\t51820\toff\nwg0\tpeer\tSECRET2\t(none)\t10.0.0.0/24\t0\t12\t34\toff").single(); assertEquals("public", w.publicKey); assertNull(w.peers.single().endpoint); assertNull(w.peers.single().lastHandshakeEpoch); assertFalse(w.toString().contains("SECRET")) }
    @Test fun nmParser() { val t = parseNmConnections("uuid:my\\:openvpn:vpn:tun0:activated\nx:wg:wireguard:wg0:activating\ny:ignored:tun:tun1:activated"); assertEquals(2,t.size); assertEquals("my:openvpn",t[0].name); assertEquals(VpnKind.OpenVpn,t[0].kind); assertEquals(VpnState.Connecting,t[1].state) }
    @Test fun ipParser() { val t = parseIpWireGuardJson("""[{"ifname":"wg0","flags":["UP"],"addr_info":[{"local":"10.0.0.1","prefixlen":24}]}]""").single(); assertEquals(listOf("10.0.0.1/24"),t.addresses); assertFalse(t.canToggle) }
    @Test fun windowsParser() { val t = parseWindowsVpnJson("""{"services":[{"Name":"WireGuardTunnel${'$'}home","Status":"Running"}],"wireguardAdapters":[{"Name":"home"}],"ras":[{"Name":"office","TunnelType":"Ikev2","ConnectionStatus":"Connected"}],"adapters":[{"Name":"OpenVPN","Status":"Up","InterfaceDescription":"OpenVPN"}],"addresses":[{"InterfaceAlias":"home","IPAddress":"10.0.0.1","PrefixLength":24}]}"""); assertEquals(3,t.size); assertTrue(t[0].toggleNeedsAdmin); assertTrue(t[0].canRemove); assertEquals(VpnKind.IpSec,t[1].kind); assertFalse(t[2].canToggle) }
    // Route facts only: the route's interface or its source address. A server address inside Tailscale's range used to
    // count as Tailscale on its own; it no longer does.
    @Test fun routeMatching() { val ts = VpnTunnel("tailscale",VpnKind.Tailscale,"Tailnet",VpnState.Up,"tailscale0"); val wg = VpnTunnel("wg:x",VpnKind.WireGuard,"x",VpnState.Up,"wg0"); assertEquals(ts,matchRouteTunnel(null,"100.127.255.254",listOf(ts,wg),listOf("100.127.255.254"))); assertEquals(ts,matchRouteTunnel(null,"fd7a:115c:a1e0::1",listOf(ts),listOf("fd7a:115c:a1e0:0:0:0:0:1"))); assertEquals(ts,matchRouteTunnel("tailscale0",null,listOf(ts,wg))); assertEquals(wg,matchRouteTunnel("wg0",null,listOf(ts,wg))); assertNull(matchRouteTunnel(null,"100.127.255.254",listOf(ts,wg))); assertNull(matchRouteTunnel(null,"100.128.0.1",listOf(ts))) }
    @Test fun linuxCommands() {
        val calls = mutableListOf<List<String>>()
        val backend = LocalVpnBackend("Linux", { c,_ -> calls += c; when(c.drop(1)) {
            listOf("status","--json") -> ts
            listOf("-j","addr","show","type","wireguard") -> "[]"
            listOf("show","all","dump") -> ""
            listOf("-t","--escape","yes","-f","UUID,NAME,TYPE,DEVICE","connection","show") -> "uuid:home:wireguard:wg0:aktiviert"
            listOf("-t","--escape","yes","-f","UUID","connection","show","--active") -> "uuid"
            else -> ""
        } }, { it }, hostActions = true)
        val s = backend.snapshot(); assertTrue(s.notes.isEmpty()); assertEquals(VpnState.Up, s.tunnels.first { it.id == "nm:uuid" }.state)
        backend.setTunnel(s.tunnels.first { it.id == "tailscale" },false); backend.setExitNode(s.tailscale!!.exitNode); backend.setExitNode(s.tailscale!!.exitNode, allowLanAccess = false); backend.setExitNode(null); val nm = s.tunnels.first { it.id == "nm:uuid" }; backend.setTunnel(nm,true); backend.removeWireGuard(nm)
        assertTrue(listOf("tailscale","down") in calls); assertTrue(listOf("tailscale","set","--exit-node=100.64.0.2","--exit-node-allow-lan-access=true") in calls); assertTrue(listOf("tailscale","set","--exit-node=100.64.0.2","--exit-node-allow-lan-access=false") in calls); assertTrue(listOf("tailscale","set","--exit-node=") in calls); assertTrue(listOf("nmcli","connection","up","uuid","uuid") in calls); assertTrue(listOf("nmcli","connection","delete","uuid","uuid") in calls)
    }
    @Test fun refusalAndNotes() { val b = LocalVpnBackend("Linux", { _,_ -> error("access denied") }, { if(it == "tailscale" || it == "wg") it else null }, hostActions = true); val notes = b.snapshot().notes; assertEquals(3, notes.size); assertTrue(notes.any { it.contains("access denied") }); assertTrue(assertFailsWith<IllegalStateException> { b.setExitNode(null) }.message!!.contains("operator=${'$'}USER")); assertFailsWith<IllegalArgumentException> { b.setTunnel(VpnTunnel("nic:a",VpnKind.Other,"a",VpnState.Up),false) }; assertFailsWith<IllegalArgumentException> { b.importWireGuard(java.nio.file.Path.of("missing"),"../bad") } }
    @Test fun importAndCleanup() { val f = Files.createTempFile("vpn-test", ".conf"); Files.writeString(f,"[Interface]\nPrivateKey = SECRET"); var copied: java.nio.file.Path? = null
        val after = mutableListOf<List<String>>()
        try { val b = LocalVpnBackend("Linux", { c,_ -> if(c.getOrNull(2) != "import") { after += c.drop(1); if(c.getOrNull(2) == "down") error("not active"); "" } else { assertEquals(listOf("nmcli","connection","import","type","wireguard","file"),c.take(6)); copied = java.nio.file.Path.of(c.last()); assertEquals("home.conf",copied!!.fileName.toString()); "Connection 'home' (12345678-1234-1234-1234-123456789abc) successfully added." } }, { it }, hostActions = true); assertEquals("nm:12345678-1234-1234-1234-123456789abc",b.importWireGuard(f,"home")); assertFalse(Files.exists(copied));
            // Importing must not leave the tunnel up or set to connect by itself; a failing "down" (never active) is fine.
            assertEquals(listOf(listOf("connection","modify","uuid","12345678-1234-1234-1234-123456789abc","connection.autoconnect","no"), listOf("connection","down","uuid","12345678-1234-1234-1234-123456789abc")), after); Files.writeString(f,"wrong"); assertFailsWith<IllegalArgumentException> { b.importWireGuard(f,"home") } } finally { Files.deleteIfExists(f) }
    }
    private fun decode(call: List<String>) = String(Base64.getDecoder().decode(call.last()),Charsets.UTF_16LE)
    /** The elevated script is carried, encoded again, inside the outer script's -ArgumentList. */
    private fun inner(call: List<String>) = String(Base64.getDecoder().decode(Regex("-EncodedCommand ([A-Za-z0-9+/=]+)").find(decode(call))!!.groupValues[1]),Charsets.UTF_16LE)
    private val wgExe = "C:\\Program Files\\WireGuard\\wireguard.exe"
    private fun windowsBackend(calls: MutableList<List<String>>, record: java.nio.file.Path) =
        LocalVpnBackend("Windows", { c,_ -> calls += c; "{}" }, { if(it == wgExe) it else null }, WireGuardImports(record), hostActions = true)

    @Test fun windowsCommands() { val calls = mutableListOf<List<String>>(); val record = Files.createTempDirectory("wg-rec").resolve("imports.json"); val b = windowsBackend(calls, record); b.snapshot(); assertEquals(1,calls.size); val script = decode(calls[0]); assertTrue(script.contains("Get-VpnConnection -AllUserConnection")); b.setTunnel(VpnTunnel("ras:a'b",VpnKind.Other,"a'b",VpnState.Down,canToggle=true),true); assertEquals(listOf(windowsRasdial(),"a'b"),calls.last()); b.setTunnel(VpnTunnel("wg:home",VpnKind.WireGuard,"home",VpnState.Down,canToggle=true),true); val outer = decode(calls.last()); assertTrue(outer.contains("-Verb RunAs")); assertTrue(outer.contains(psQuote(windowsPowerShell()))); assertEquals(windowsPowerShell(), calls[0][0]); assertTrue(decode(calls[0]).startsWith("[Console]::OutputEncoding")); assertEquals("'a''b'",psQuote("a'b")) }

    @Test fun windowsImportGoesStraightIntoWireGuardsStoreAndIsRemembered() {
        val dir = Files.createTempDirectory("wg-import"); val conf = dir.resolve("office.conf"); Files.writeString(conf,"[Interface]\nPrivateKey = SECRET")
        val calls = mutableListOf<List<String>>(); val record = dir.resolve("imports.json"); val b = windowsBackend(calls, record)
        assertEquals("wg:office", b.importWireGuard(conf,"office"))
        val script = inner(calls.single())
        assertTrue("C:\\Program Files\\WireGuard\\Data\\Configurations" in script, script)
        assertTrue("Copy-Item -LiteralPath \$source" in script)
        assertFalse(conf.toAbsolutePath().toString() in script, "the source path must not appear in the script text")
        assertFalse("installtunnelservice" in script, "importing must not bring the tunnel up")
        assertFalse("SECRET" in script || "SECRET" in decode(calls.single()), "the configuration's contents never enter a command line")
        assertTrue("already exists" in decode(calls.single()), "exit codes are explained")
        assertEquals(listOf("office"), WireGuardImports(record).names())
        assertFalse("SECRET" in Files.readString(record))
        // Nothing was staged beside the record or anywhere else RingMASTER writes.
        assertEquals(setOf("office.conf","imports.json"), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList().toSet() })
        assertFailsWith<IllegalArgumentException> { b.importWireGuard(conf,"office") }
        // Down with no service: listed from the record, and Up installs the service from the store.
        val pending = b.snapshot().tunnels.single { it.id == "wg:office" }
        assertEquals(VpnState.Down, pending.state); assertTrue(pending.canToggle && pending.canRemove && pending.toggleNeedsAdmin)
        b.setTunnel(pending, true); val up = inner(calls.last())
        assertTrue("Start-Service -Name 'WireGuardTunnel\$office'" in up && "office.conf.dpapi" in up && "/installtunnelservice" in up)
        assertTrue("Start-Process -FilePath '$wgExe'" in up && "-Wait -PassThru" in up, "wireguard.exe is a GUI program: it must be waited for")
        b.setTunnel(pending.copy(state = VpnState.Up), false); val down = inner(calls.last())
        assertTrue("/uninstalltunnelservice" in down && "Stop-Service" !in down, "an imported tunnel is deactivated the way WireGuard does it")
        b.removeWireGuard(pending); val remove = inner(calls.last())
        assertTrue("/uninstalltunnelservice" in remove && "Remove-Item" in remove && "office.conf.dpapi" in remove)
        assertEquals(emptyList(), WireGuardImports(record).names())
    }

    @Test fun windowsLeavesOtherPeoplesTunnelConfigurationsAlone() {
        val calls = mutableListOf<List<String>>(); val record = Files.createTempDirectory("wg-foreign").resolve("imports.json"); val b = windowsBackend(calls, record)
        val foreign = VpnTunnel("wg:site",VpnKind.WireGuard,"site",VpnState.Up,canToggle=true,canRemove=true)
        b.setTunnel(foreign,false); val down = inner(calls.last()); assertTrue("Stop-Service -Name 'WireGuardTunnel\$site'" in down && "uninstalltunnelservice" !in down)
        b.removeWireGuard(foreign); val remove = inner(calls.last()); assertTrue("/uninstalltunnelservice" in remove); assertFalse("Remove-Item" in remove)
        assertFailsWith<IllegalArgumentException> { b.setTunnel(VpnTunnel("wg:bad name'; x",VpnKind.WireGuard,"x",VpnState.Down,canToggle=true),true) }
    }

    /** Opt-in: writes the real elevated scripts for a fake install so they can be run unelevated (RINGMASTER_WG_PROBE=<dir>). */
    @Test fun writeScriptsForDryRun() {
        val dir = java.nio.file.Path.of(System.getenv("RINGMASTER_WG_PROBE") ?: return); val exe = dir.resolve("WireGuard").resolve("wireguard.exe").toString() // the probe folder may contain spaces, like Program Files
        Files.writeString(dir.resolve("import.ps1"), wireGuardImportScript(exe, "office", dir.resolve("source.conf").toString()))
        Files.writeString(dir.resolve("up.ps1"), wireGuardUpScript(exe, "office"))
        Files.writeString(dir.resolve("down.ps1"), wireGuardDownScript(exe, "office", true))
        Files.writeString(dir.resolve("remove.ps1"), wireGuardRemoveScript(exe, "office", true))
    }

    @Test fun importRecordIgnoresJunkAndNeverOverwritesANewerFile() {
        val file = Files.createTempDirectory("wg-record").resolve("imports.json")
        Files.writeString(file, """{"schema":1,"names":["ok","bad name","ok","way-too-long-a-name-for-wireguard"]}""")
        assertEquals(listOf("ok"), WireGuardImports(file).names())
        Files.writeString(file, "not json"); assertEquals(emptyList(), WireGuardImports(file).names())
        val newer = """{"schema":2,"names":["future"],"more":true}"""; Files.writeString(file, newer)
        val record = WireGuardImports(file); assertEquals(emptyList(), record.names()); assertFailsWith<IllegalStateException> { record.add("mine") }
        assertEquals(newer, Files.readString(file))
    }
    @Test fun psQuoteDoublesEachPowerShellApostrophe() {
        assertEquals("'a''b'", psQuote("a'b"))
        assertEquals("'a\u2018\u2018b'", psQuote("a\u2018b"))
        assertEquals("'a\u2019\u2019b'", psQuote("a\u2019b"))
        assertEquals("'a\u201A\u201Ab'", psQuote("a\u201Ab"))
        assertEquals("'a\u201B\u201Bb'", psQuote("a\u201Bb"))
        assertEquals("'plain path 1.2.3'", psQuote("plain path 1.2.3"))
    }

    @Test fun wireGuardImportHidesTheSourcePathBehindBase64() {
        // A path with a typographic apostrophe and a space, both of which would otherwise need shaping to live safely in script text.
        val source = "C:\\Tunnels\\A\u2019B\\My Dir\\office.conf"
        val script = wireGuardImportScript(wgExe, "office", source)
        assertFalse(source in script, "the source path must not appear in the script text")
        val b64 = Regex("FromBase64String\\('([A-Za-z0-9+/=]+)'\\)").findAll(script).toList().single().groupValues[1]
        assertEquals(source, String(Base64.getDecoder().decode(b64), Charsets.UTF_16LE))
    }

    @Test fun discovery() = runBlocking { val status = parseTailscaleStatus(ts); val seen = mutableSetOf<String>(); val scanner = NetworkScanner { ip,port -> seen += ip; if(port == 8006) listOf(ScanHit(ip,port,true,ScanKind.PVE)) else emptyList() }; val result = findTailnetProxmox(status,scanner).toList().last(); assertEquals(1,result.size); assertEquals(setOf("100.64.0.2"),seen) }

    @Test fun failureMessagesNeverCarryKeyMaterial() {
        assertEquals("changing settings via 'tailscale up' requires mentioning all non-default flags", vpnFailureMessage("Error: changing settings via 'tailscale up' requires mentioning all non-default flags"))
        assertEquals("VPN command failed.", vpnFailureMessage("PrivateKey = AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="))
        assertEquals("bad peer … in line 4", vpnFailureMessage("bad peer ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8= in line 4"))
        assertEquals("Administrator approval was cancelled.", vpnFailureMessage("The operation was canceled by the user."))
        assertEquals("VPN command failed.", vpnFailureMessage(""))
    }

    @Test fun missingAbsoluteExecutableIsNullNotACrash() {
        assertNull(findExecutable(if (System.getProperty("os.name").startsWith("Windows")) "C:\\Program Files\\NoSuchVendor\\none.exe" else "/opt/no-such-vendor/none"))
        assertNull(findExecutable("ringmaster-no-such-tool"))
    }

    @Test fun emptyLinkObjectsFromIpAreSkipped() {
        assertEquals(emptyList(), parseIpWireGuardJson("[{},{}]"))
        assertEquals(listOf("wg0"), parseIpWireGuardJson("""[{},{"ifname":"wg0","flags":["UP"],"addr_info":[{"local":"10.8.0.2","prefixlen":24}]}]""").map { it.name })
    }

    @Test fun translatedNmStateIsNotUpUntilTheActiveListSaysSo() {
        val down = parseNmConnections("u1:home:wireguard:wg0:aktiviert").single()
        assertEquals(VpnState.Down, down.state)
        assertEquals("Deletes this NetworkManager profile and its key. RingMASTER keeps no copy of the key.", vpnRemoveBody("Linux", down))
    }

    @Test fun windowsParserKeepsNonAsciiNamesAndAdapterCounters() {
        val t = parseWindowsVpnJson("""{"services":[{"Name":"WireGuardTunnel${'$'}B\u00fcro","Status":"Running"}],"wireguardAdapters":[{"Name":"B\u00fcro"}],"ras":[],"adapters":[],"addresses":[],"statistics":[{"Name":"B\u00fcro","ReceivedBytes":40,"SentBytes":9}]}""")
        assertEquals("B\u00fcro", t.single().name)
        assertEquals(40L, t.single().rxBytes)
        assertEquals(9L, t.single().txBytes)
        assertTrue(windowsVpnReadScript().contains("Get-NetAdapterStatistics"))
        assertTrue(windowsRouteScript("10.0.0.1").contains("[Console]::OutputEncoding"))
        assertEquals("C", vpnCommandEnvironment(listOf("nmcli", "connection", "show"))["LC_ALL"])
        assertEquals("C", vpnCommandEnvironment(listOf("/usr/sbin/ip", "-j", "route"))["LANG"])
        assertTrue(vpnCommandEnvironment(listOf(windowsPowerShell(), "-NoProfile")).isEmpty())
        val builder = ProcessBuilder(listOf("wg", "show"))
        applyVpnCommandEnvironment(builder, builder.command())
        assertEquals("C", builder.environment()["LC_ALL"])
    }

    @Test fun mutationsFailClosedUnlessHostActionsAreAllowed() {
        var calls = 0
        val blocked = LocalVpnBackend("Linux", { _, _ -> calls++; "" }, { it })
        val tunnel = VpnTunnel("nm:u", VpnKind.WireGuard, "home", VpnState.Down, canToggle = true, canRemove = true)
        assertTrue(assertFailsWith<IllegalStateException> { blocked.setTunnel(tunnel, true) }.message!!.contains("--allow-host-actions"))
        assertFailsWith<IllegalStateException> { blocked.setExitNode(null) }
        assertFailsWith<IllegalStateException> { blocked.removeWireGuard(tunnel) }
        assertFailsWith<IllegalStateException> { blocked.importWireGuard(java.nio.file.Path.of("missing.conf"), "home") }
        assertEquals(0, calls)
    }

    @Test fun linuxImportReportsAFailedSafetyStepAndStagesUnderTheGivenRoot() {
        val root = Files.createTempDirectory("xdg-stage")
        val f = Files.createTempFile("vpn-stage", ".conf")
        Files.writeString(f, "[Interface]\nPrivateKey = SECRET")
        var copied: java.nio.file.Path? = null
        val b = LocalVpnBackend("Linux", { c, _ ->
            when (c.getOrNull(2)) {
                "import" -> { copied = java.nio.file.Path.of(c.last()); "Connection 'home' (12345678-1234-1234-1234-123456789abc) successfully added." }
                "modify" -> error("permission denied")
                "down" -> error("device busy")
                else -> ""
            }
        }, { it }, hostActions = true, stagingRoot = root)
        val failure = assertFailsWith<IllegalStateException> { b.importWireGuard(f, "home") }
        assertTrue(failure.message!!.contains("was imported"), failure.message)
        assertTrue(failure.message!!.contains("Auto-connect could not be turned off"), failure.message)
        assertTrue(failure.message!!.contains("could not be brought down"), failure.message)
        assertTrue(copied!!.startsWith(root))
        assertFalse(Files.exists(copied))
        assertEquals(0, Files.list(root).use { it.count() })
        assertEquals(java.nio.file.Path.of("/run/user/1"), linuxKeyStagingParent(mapOf("XDG_RUNTIME_DIR" to "/run/user/1")) { true })
        assertEquals(java.nio.file.Path.of("/dev/shm"), linuxKeyStagingParent(emptyMap()) { it == java.nio.file.Path.of("/dev/shm") })
        assertNull(linuxKeyStagingParent(emptyMap()) { false })
    }

    @Test fun windowsForeignTunnelDoesNotClaimToDeleteTheConfiguration() {
        val record = Files.createTempDirectory("wg-foreign-text").resolve("imports.json")
        val json = """{"services":[{"Name":"WireGuardTunnel${'$'}site","Status":"Running"}],"wireguardAdapters":[{"Name":"site"}],"ras":[],"adapters":[],"addresses":[],"statistics":[{"Name":"site","ReceivedBytes":10,"SentBytes":4}]}"""
        val b = LocalVpnBackend("Windows", { _, _ -> json }, { null }, WireGuardImports(record), hostActions = true)
        val tunnel = b.snapshot().tunnels.single { it.id == "wg:site" }
        assertFalse(tunnel.removeDeletesConfig)
        assertEquals(10L, tunnel.rxBytes)
        assertEquals(4L, tunnel.txBytes)
        assertEquals("Stops and uninstalls the WireGuard service. The configuration stays in WireGuard.", vpnRemoveBody("Windows", tunnel))
        val timeouts = mutableListOf<Long>()
        val calls = mutableListOf<List<String>>()
        val elevated = LocalVpnBackend("Windows", { c, t -> calls += c; timeouts += t; "{}" }, { if (it == wgExe) it else null }, WireGuardImports(record), hostActions = true)
        elevated.snapshot()
        assertEquals(30L, timeouts.single())
        elevated.setTunnel(tunnel.copy(canToggle = true), true)
        assertEquals(180L, timeouts.last())
    }

    @Test fun tailscaleCardUsesPeerBytesAndHandshake() {
        val json = """{"BackendState":"Running","Self":{"ID":"s","TailscaleIPs":["100.64.0.1"]},"Peer":{"a":{"ID":"a","HostName":"a","Online":true,"TailscaleIPs":["100.64.0.2"],"RxBytes":10,"TxBytes":4,"LastHandshake":"2026-01-01T00:00:00Z"},"b":{"ID":"b","HostName":"b","Online":false,"TailscaleIPs":["100.64.0.3"],"LastHandshake":"0001-01-01T00:00:00Z"}}}"""
        val parsed = parseTailscaleStatus(json)
        assertEquals(java.time.Instant.parse("2026-01-01T00:00:00Z"), parsed.peers.first().lastHandshake)
        assertNull(parsed.peers.last().lastHandshake)
        val b = LocalVpnBackend("Linux", { c, _ -> if (c.last() == "--json") json else "[]" }, { if (it == "tailscale") it else null }, hostActions = true)
        val tunnel = b.snapshot().tunnels.single { it.id == "tailscale" }
        assertEquals(10L, tunnel.rxBytes)
        assertEquals(4L, tunnel.txBytes)
        assertEquals(java.time.Instant.parse("2026-01-01T00:00:00Z").epochSecond, tunnel.lastHandshakeEpoch)
    }

    @Test fun linuxTunnelsReadUnprivilegedCounters() {
        val b = LocalVpnBackend("Linux", { c, _ -> when (c.drop(1)) {
            listOf("-j", "addr", "show", "type", "wireguard") -> """[{"ifname":"wg0","flags":["UP"],"addr_info":[]}]"""
            listOf("show", "all", "dump") -> "wg0\tpub\tpub\t51820\toff\nwg0\tpeer\tpsk\t203.0.113.9:51820\t10.8.0.2/32\t1700000000\t1\t2\toff"
            else -> ""
        } }, { if (it == "ip" || it == "wg") it else null }, counters = { if (it == "wg0") 30L to 8L else null to null })
        val wg = b.snapshot().tunnels.single { it.id == "wg:wg0" }
        assertEquals(30L, wg.rxBytes)
        assertEquals(8L, wg.txBytes)
        assertEquals(1700000000L, wg.lastHandshakeEpoch)
        assertEquals(null to null, interfaceCounters("../etc"))
    }

    @Test fun confirmHelpersAndLanDefault() {
        val down = VpnTunnel("wg:a", VpnKind.WireGuard, "a", VpnState.Down, canToggle = true, canRemove = true)
        val up = down.copy(state = VpnState.Up)
        assertTrue(toggleStillApplies(true, down))
        assertFalse(toggleStillApplies(true, up))
        assertFalse(toggleStillApplies(false, down.copy(state = VpnState.Connecting)))
        assertTrue(removeStillApplies(down))
        assertFalse(removeStillApplies(up))
        val peer = TailscalePeer("p", "home", null, null, true, listOf("100.64.0.2"), true, false, null, null, null, null, null)
        val other = peer.copy(id = "q", hostName = "other", exitNode = true)
        val status = TailscaleStatus("Running", null, null, null, null, listOf(peer, other), null, emptyList())
        assertTrue(exitNodeStillApplies(peer, status))
        assertFalse(exitNodeStillApplies(other, status))
        assertTrue(exitNodeStillApplies(null, status))
        assertFalse(exitNodeStillApplies(null, status.copy(peers = listOf(peer))))
        assertTrue(privateLanAddress("10.1.2.3"))
        assertTrue(privateLanAddress("192.168.0.9"))
        assertFalse(privateLanAddress("100.64.0.1"))
        assertTrue(exitNodeLanDefault("10.0.0.5", VpnRoute("h", "10.0.0.5", "eth0", null, null)))
        assertFalse(exitNodeLanDefault("10.0.0.5", VpnRoute("h", "10.0.0.5", "tailscale0", "tailscale", "Tailscale")))
        assertEquals("SEARCHING ONLINE PEERS ON 8006 · 8007 · 8443", tailnetSearchHeader(true, 0))
        assertEquals("PROXMOX ON THE TAILNET · 2", tailnetSearchHeader(false, 2))
    }

    @Test fun findExecutableAlsoSearchesLinuxAdminPaths() {
        val root = Files.createTempDirectory("vpn-path")
        val sbin = Files.createDirectories(root.resolve("sbin"))
        val tool = sbin.resolve("wg")
        Files.writeString(tool, "x")
        assertEquals(tool.toString(), findExecutableIn("wg", "", listOf(sbin.toString())))
        val earlier = Files.createDirectories(root.resolve("bin"))
        val preferred = earlier.resolve("wg")
        Files.writeString(preferred, "y")
        assertEquals(preferred.toString(), findExecutableIn("wg", earlier.toString(), listOf(sbin.toString())))
        val extras = extraExecutableDirs()
        if (System.getProperty("os.name").startsWith("Windows", true)) assertTrue(extras.isEmpty())
        else assertTrue(extras.containsAll(listOf("/usr/local/bin", "/usr/sbin", "/sbin")))
    }
}
