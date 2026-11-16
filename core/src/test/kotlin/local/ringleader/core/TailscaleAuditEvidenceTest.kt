package local.ringleader.core

import java.net.InetAddress
import java.time.Instant
import kotlin.test.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Evidence for the 2026-11-16 Tailscale / WireGuard / SSH audit. Each test pins today's behaviour so the report's claims
 * are reproducible; a test named `defect…` asserts the defective behaviour and is expected to flip when the defect is
 * fixed (update it then). Synthetic JSON only: documentation addresses, `example.ts.net`, no real tailnet data.
 * Field names follow tailscale/tailscale ipn/ipnstate PeerStatus (CurAddr, Relay, PeerRelay, PrimaryRoutes, Expired,
 * KeyExpiry, sshHostKeys, Location, ShareeNode) and Status (TUN, ExitNodeStatus, CertDomains).
 */
class TailscaleAuditEvidenceTest {
    private val at = Instant.parse("2026-11-16T10:00:00Z")

    private fun status(peers: String, extra: String = "") = """
        {"Version":"1.104.6","BackendState":"Running","TUN":true,$extra
         "CurrentTailnet":{"Name":"example","MagicDNSSuffix":"example.ts.net","MagicDNSEnabled":true},
         "Self":{"ID":"self","HostName":"desk","DNSName":"desk.example.ts.net.","TailscaleIPs":["100.64.0.1","fd7a:115c:a1e0::1"],"Online":true},
         "Peer":{$peers},"Health":[]}
    """.trimIndent()

    // ---------------------------------------------------------------- peer path

    @Test fun peerRelayIsReportedAsAPeerRelay() {
        // Fixed (W1). Tailscale >= 1.86 peer relays: CurAddr empty, PeerRelay "ip:port:vni", Relay still names the home
        // DERP region. `tailscale status` prints "peer-relay <addr>"; so does RingMASTER now, never "DERP fra".
        val s = parseTailscaleStatus(status(""""n1":{"ID":"n1","HostName":"pve1","TailscaleIPs":["100.64.0.10"],"Online":true,"Active":true,
            "CurAddr":"","Relay":"fra","PeerRelay":"198.51.100.7:40000:12"}"""))
        val path = tailscalePeerPath(s, "100.64.0.10")
        assertEquals(PeerPath.PeerRelayed("198.51.100.7:40000:12"), path)
        assertEquals("peer relay", peerPathLabel(path!!))
    }

    @Test fun idleDerpPeerIsNotClaimedAsRelayedByTheTagOrTheTable() {
        // Fixed (W2): the tag and the VPN page's peer table share peerPathLabel, so both say "idle" for this peer.
        val s = parseTailscaleStatus(status(""""n1":{"ID":"n1","HostName":"pve1","TailscaleIPs":["100.64.0.10"],"Online":true,"Active":false,"CurAddr":"","Relay":"fra"}"""))
        assertEquals(PeerPath.Idle, tailscalePeerPath(s, "100.64.0.10"))
        assertEquals("idle", peerPathLabel(peerPath(s.peers.single())))
    }

    @Test fun ipv6TailnetAddressMatchesThePeer() {
        val s = parseTailscaleStatus(status(""""n1":{"ID":"n1","HostName":"pve1","TailscaleIPs":["100.64.0.10","fd7a:115c:a1e0::a"],"Online":true,"Active":true,"CurAddr":"203.0.113.9:41641"}"""))
        assertEquals(PeerPath.Direct("203.0.113.9:41641"), tailscalePeerPath(s, "fd7a:115c:a1e0:0:0:0:0:a"))
        assertEquals(listOf("100.64.0.10", "fd7a:115c:a1e0::a"), s.peers.single().addresses, "IPv4 first")
    }

    @Test fun subnetRouterIsNamedAndAnExitNodeStaysNotPeer() {
        // Fixed (M6, subnet routers, 2026-11-16): an address behind a subnet router names that router (once the OS route
        // goes through Tailscale); an exit node in use still gives NotPeer.
        val s = parseTailscaleStatus(status("""
            "r":{"ID":"r","HostName":"router","TailscaleIPs":["100.64.0.20"],"Online":true,"Active":true,"AllowedIPs":["100.64.0.20/32","192.0.2.0/24"],"PrimaryRoutes":["192.0.2.0/24"]},
            "x":{"ID":"x","HostName":"exit","TailscaleIPs":["100.64.0.30"],"Online":true,"Active":true,"ExitNode":true,"ExitNodeOption":true,"AllowedIPs":["100.64.0.30/32","0.0.0.0/0","::/0"]}"""))
        assertEquals(PeerPath.SubnetRouter("router", "192.0.2.0/24", PeerPath.NotReported), tailscalePeerPath(s, "192.0.2.15"))      // behind the subnet router
        assertEquals(PeerPath.NotPeer, tailscalePeerPath(s, "203.0.113.50"))    // out through the exit node
    }

    // ---------------------------------------------------------------- parser robustness

    @Test fun aNullPeerEntryCostsOnlyThatRow() {
        // Fixed (F1): one bad entry is skipped and counted; the rest of the status stays readable.
        val s = parseTailscaleStatus(status(""""n1":null,"n2":{"ID":"n2","HostName":"ok","Online":true}"""))
        assertEquals(listOf("ok"), s.peers.map { it.hostName }); assertEquals(1, s.unreadablePeers)
    }

    @Test fun nullAndMissingFieldsAreTolerated() {
        val s = parseTailscaleStatus("""{"BackendState":"NeedsLogin","AuthURL":"https://login.example/a/x","Self":null,"Peer":null,"Health":null,"TailscaleIPs":null}""")
        assertEquals("NeedsLogin", s.backendState); assertNull(s.self); assertTrue(s.peers.isEmpty()); assertTrue(s.health.isEmpty())
        assertEquals("https://login.example/a/x", s.authUrl)
        // Fixed (W4): the link is offered for the user to open, with per-state advice instead of "Open the Tailscale app".
        assertEquals("https://login.example/a/x", signInLink(s))
        assertTrue(tailscaleStateAdvice(s, "Linux")!!.contains("sign-in link"))
        for (state in listOf("NoState", "NeedsMachineAuth", "Stopped", "Starting")) assertEquals(state, parseTailscaleStatus("""{"BackendState":"$state"}""").backendState)
    }

    @Test fun expiryUserAndSshFactsAreKept() {
        // Fixed (parsing part of M2/M3): Expired, KeyExpiry, sshHostKeys (count and types only), ShareeNode reach the
        // model, so an expired Proxmox host no longer reads exactly like one that is switched off.
        val p = parseTailscaleStatus(status(""""n1":{"ID":"n1","HostName":"pve1","TailscaleIPs":["100.64.0.10"],"Online":false,"Expired":true,
            "KeyExpiry":"2026-11-01T00:00:00Z","sshHostKeys":["ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample"],"ShareeNode":true}""", extra = """"CertDomains":["desk.example.ts.net"],""")).peers.single()
        assertTrue(p.expired); assertEquals(Instant.parse("2026-11-01T00:00:00Z"), p.keyExpiry)
        assertEquals(1, p.sshHostKeyCount); assertEquals(listOf("ssh-ed25519"), p.sshHostKeyTypes); assertTrue(p.sharee)
        assertFalse(p.toString().contains("AAAAC3"), "the host key itself is not kept")
        assertEquals(listOf("Key expired", "SSH", "Shared"), tailscalePeerTags(p, at).map { it.label })
    }

    @Test fun largeTailnetParses() {
        val peers = (1..600).joinToString(",") { i -> """"n$i":{"ID":"n$i","HostName":"h$i","TailscaleIPs":["100.64.${i / 250}.${i % 250 + 1}"],"Online":${i % 2 == 0}}""" }
        val s = parseTailscaleStatus(status(peers))
        assertEquals(600, s.peers.size); assertTrue(s.peers.first().online, "online peers sort first")
    }

    // ---------------------------------------------------------------- tailnet Proxmox search

    private suspend fun probed(json: String): Set<String> {
        val seen = mutableSetOf<String>()
        val scanner = NetworkScanner { ip, _ -> synchronized(seen) { seen += ip }; emptyList() }
        findTailnetProxmox(parseTailscaleStatus(json), scanner).toList()
        return seen
    }

    @Test fun defectProxmoxBehindASubnetRouterIsNeverProbed() = runBlocking {
        // The common lab shape: one subnet router advertising the LAN /24 that the Proxmox hosts live on.
        val seen = probed(status(""""r":{"ID":"r","HostName":"router","TailscaleIPs":["100.64.0.20"],"Online":true,
            "AllowedIPs":["100.64.0.20/32","192.0.2.0/24"],"PrimaryRoutes":["192.0.2.0/24"]}"""))
        assertEquals(setOf("100.64.0.20"), seen, "DEFECT (missing): nothing in 192.0.2.0/24 is offered or probed")
    }

    @Test fun mullvadIsNeverProbedAndSharedNodesOnlyWhenIncluded() = runBlocking {
        // Fixed (U1). Mullvad exit nodes (Location set) are never probed; nodes shared into the tailnet only when the user
        // ticks "Include machines shared by others".
        val json = status("""
            "m":{"ID":"m","HostName":"se-sto-wg-001","DNSName":"se-sto-wg-001.example.ts.net.","TailscaleIPs":["100.64.1.5"],"Online":true,"ExitNodeOption":true,"Location":{"Country":"Sweden","CountryCode":"SE","City":"Stockholm"}},
            "s":{"ID":"s","HostName":"friend-nas","TailscaleIPs":["100.64.1.6"],"Online":true,"ShareeNode":true}""")
        assertEquals(emptySet(), probed(json))
        val s = parseTailscaleStatus(json)
        val seen = mutableSetOf<String>()
        findTailnetProxmox(s, NetworkScanner { ip, _ -> synchronized(seen) { seen += ip }; emptyList() }, plan = planTailnetSearch(s, includeShared = true)).toList()
        assertEquals(setOf("100.64.1.6"), seen)
    }

    @Test fun offlinePeersAndExitRoutesAreNotProbed() = runBlocking {
        val seen = probed(status("""
            "o":{"ID":"o","HostName":"off","TailscaleIPs":["100.64.0.40"],"Online":false},
            "x":{"ID":"x","HostName":"exit","TailscaleIPs":["100.64.0.30"],"Online":true,"AllowedIPs":["100.64.0.30/32","0.0.0.0/0","::/0"]},
            "h":{"ID":"h","HostName":"one","TailscaleIPs":["100.64.0.50"],"Online":true,"AllowedIPs":["100.64.0.50/32","192.0.2.77/32"]}"""))
        assertEquals(setOf("100.64.0.30", "100.64.0.50", "192.0.2.77"), seen)
    }

    @Test fun defectIpv6OnlyPeerIsSkipped() = runBlocking {
        // A node with IPv4 disabled by a nodeAttr has only its fd7a: address; the search never tries it.
        assertTrue(probed(status(""""v6":{"ID":"v6","HostName":"pve6","TailscaleIPs":["fd7a:115c:a1e0::66"],"Online":true}""")).isEmpty())
    }

    // ---------------------------------------------------------------- mutations

    @Test fun tailscaleUpIsBoundedAndALoginWaitIsReportedAsASignIn() {
        // Fixed (W3). `tailscale up` with no flags blocks until Running (cmd/tailscale/cli/up.go: --timeout default 0
        // "blocks forever"). The wait is now bounded at 20 s by the runner and, on any failure, the status is read once
        // more and reported: a sign-in wait is "needs a sign-in" with the link, never "timed out". The command stays bare
        // `up`: any flag, --timeout included, turns it into a full up that refuses to run while a setting such as an exit
        // node differs from its default (checkForAccidentalSettingReverts), so `up --timeout=20s` would break Bring up.
        val calls = mutableListOf<List<String>>()
        var state = "Stopped"
        fun status() = """{"BackendState":"$state","AuthURL":${if (state == "NeedsLogin") "\"https://login.example/a/x\"" else "null"},"Self":{"ID":"s","TailscaleIPs":["100.64.0.1"]},"Peer":{}}"""
        val backend = LocalVpnBackend("Linux", { c, timeout ->
            calls += c + "timeout=$timeout"
            when {
                c.drop(1) == listOf("status", "--json") -> status()
                c.drop(1) == listOf("up") -> { state = "NeedsLogin"; throw VpnCommandTimeoutException() }
                else -> "[]"
            }
        }, { it }, hostActions = true)
        val ts = backend.snapshot().tunnels.first { it.id == "tailscale" }
        assertTrue(ts.canToggle)
        calls.clear()
        val e = assertFailsWith<IllegalStateException> { backend.setTunnel(ts, true) }
        assertEquals(listOf(listOf("tailscale", "up", "timeout=20"), listOf("tailscale", "status", "--json", "timeout=30")), calls, "one bounded up, then one status read; no retry")
        assertEquals("Tailscale needs a sign-in before it can connect. Open the sign-in link on this page.", e.message)
        assertFalse(e.message!!.contains("timed out"))
    }

    @Test fun onlyTheCliAccessDeniedPrefixGetsTheOperatorHint() {
        // Fixed (F2). The CLI prints "Access denied: prefs write access denied" then the sudo hint (cmd/tailscale/cli/cli.go,
        // client/local AccessDeniedError). The status read afterwards adds what Tailscale reports now.
        val running = """{"BackendState":"Running","Peer":{}}"""
        val backend = LocalVpnBackend("Linux", { c, _ -> if (c.getOrNull(1) == "set") error("Access denied: prefs write access denied") else running }, { it }, hostActions = true)
        val e = assertFailsWith<IllegalStateException> { backend.setExitNode(null) }
        assertEquals("Tailscale refused the change. On Linux run: sudo tailscale set --operator=\$USER Tailscale now reports: running.", e.message)
        // A socket "permission denied" is not the operator case: it is shown as it is.
        val socket = LocalVpnBackend("Linux", { _, _ -> error("dial unix /var/run/tailscale/tailscaled.sock: connect: permission denied") }, { it }, hostActions = true)
        val m = assertFailsWith<IllegalStateException> { socket.setExitNode(null) }.message!!
        assertFalse(m.contains("operator")); assertTrue(m.startsWith("dial unix /var/run/tailscale/tailscaled.sock: connect: permission denied"))
    }

    @Test fun exitNodeChoiceIsRecheckedAgainstAFreshStatus() {
        val s = parseTailscaleStatus(status(""""x":{"ID":"x","HostName":"exit","TailscaleIPs":["100.64.0.30"],"Online":true,"ExitNodeOption":true}"""))
        val x = s.peers.single()
        assertTrue(exitNodeStillApplies(x, s)); assertFalse(exitNodeStillApplies(null, s))
        assertFalse(exitNodeStillApplies(x, s.copy(peers = listOf(x.copy(exitNode = true)))))
        assertFalse(exitNodeStillApplies(x, s.copy(peers = emptyList())), "a peer that left is not chosen")
    }

    // ---------------------------------------------------------------- route tag

    @Test fun userspaceTailscaleWithholdsDirectForATailnetAddress() {
        // Fixed (W5). TUN=false (userspace networking: containers, some NAS and LXC installs) means the OS has no route
        // into the tailnet. The OS route for 100.64.0.10 is then the default route over Ethernet; the tag now withholds
        // Direct and says why, without claiming a tunnel.
        val s = parseTailscaleStatus("""{"BackendState":"Running","TUN":false,"Self":{"ID":"s","TailscaleIPs":["100.64.0.1"]},"Peer":{"n":{"ID":"n","HostName":"pve1","TailscaleIPs":["100.64.0.10"],"Online":true}}}""")
        val ts = VpnTunnel("tailscale", VpnKind.Tailscale, "Tailscale", VpnState.Up, "tailscale0", listOf("100.64.0.1"))
        val snap = VpnSnapshot(at, listOf(ts), true, s, false, emptyList(), emptyList())
        val o = classifyConnectionRoute(routeTarget("https://100.64.0.10:8006")!!, at, null, Result.success(listOf(InetAddress.getByName("100.64.0.10"))),
            Result.success(OsRoute("100.64.0.10", RouteInterface("eth0", 2, null, RouteMedium.Ethernet), "192.0.2.20", "192.0.2.1")), VpnInventory(snap))
        assertEquals(RouteKind.Unknown, o.kind); assertEquals(TAILSCALE_USERSPACE_NOTE, o.reason); assertNull(o.vpnKind)
    }

    @Test fun tailscaleSourceAddressIsRouteEvidence() {
        val ts = VpnTunnel("tailscale", VpnKind.Tailscale, "Tailscale", VpnState.Up, "tailscale0", listOf("100.64.0.1", "fd7a:115c:a1e0::1"))
        val s = parseTailscaleStatus(status(""""r":{"ID":"r","HostName":"router","TailscaleIPs":["100.64.0.20"],"Online":true,"AllowedIPs":["100.64.0.20/32","192.0.2.0/24"],"PrimaryRoutes":["192.0.2.0/24"]}"""))
        val snap = VpnSnapshot(at, listOf(ts), true, s, false, emptyList(), emptyList())
        // A Proxmox host behind the subnet router: Linux reports dev tailscale0 src <own tailnet IP> from table 52.
        val o = classifyConnectionRoute(routeTarget("https://192.0.2.15:8006")!!, at, null, Result.success(listOf(InetAddress.getByName("192.0.2.15"))),
            Result.success(OsRoute("192.0.2.15", RouteInterface("tailscale0", 9, null, RouteMedium.Tunnel), "100.64.0.1", null)), VpnInventory(snap))
        assertEquals(RouteKind.Vpn, o.kind); assertEquals(VpnKind.Tailscale, o.vpnKind); assertEquals(RouteEvidence.SourceAddress, o.evidence)
        // The OS route and the status agree, so the router is named; no peer's own path is invented for the server.
        assertEquals(PeerPath.SubnetRouter("router", "192.0.2.0/24", PeerPath.NotReported), o.peerPath)
    }
}
