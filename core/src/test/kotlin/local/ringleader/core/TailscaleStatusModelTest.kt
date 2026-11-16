package local.ringleader.core

import java.net.InetAddress
import java.time.Instant
import kotlin.test.*

/**
 * Tailscale status model v2: the fields added after the 2026-11-16 audit, the shared peer-path label, the peer-row tags
 * and the userspace rule. Synthetic JSON only (documentation addresses, example.ts.net).
 */
class TailscaleStatusModelTest {
    private val now = Instant.parse("2026-11-16T10:00:00Z")

    private fun status(peers: String, top: String = "") = parseTailscaleStatus("""
        {"BackendState":"Running",$top"Self":{"ID":"self","HostName":"desk","TailscaleIPs":["100.64.0.1"],"Online":true,"UserID":7},"Peer":{$peers}}
    """.trimIndent())
    private fun one(fields: String) = status(""""n":{"ID":"n","HostName":"pve1","TailscaleIPs":["100.64.0.10"],"Online":true$fields}""").peers.single()

    // ------------------------------------------------------------------ parser: every new field, present, null, missing, odd

    @Test fun peerRelayIsParsed() {
        assertEquals("198.51.100.7:40000:12", one(""","PeerRelay":"198.51.100.7:40000:12"""").peerRelay)
        assertNull(one(""","PeerRelay":""""").peerRelay, "empty is absent")
        assertNull(one(""","PeerRelay":null""").peerRelay)
        assertNull(one("").peerRelay)
        assertNull(one(""","PeerRelay":{"x":1}""").peerRelay, "an object is not a relay address")
    }

    @Test fun tunIsParsedAndUserspaceFollowsIt() {
        assertEquals(true, status("", """"TUN":true,""").tun); assertFalse(status("", """"TUN":true,""").userspace)
        assertEquals(false, status("", """"TUN":false,""").tun); assertTrue(status("", """"TUN":false,""").userspace)
        assertNull(status("").tun); assertFalse(status("").userspace, "missing TUN (older clients, Headscale builds) claims nothing")
        assertNull(status("", """"TUN":null,""").tun)
        assertNull(status("", """"TUN":"yes",""").tun, "an odd value is not a boolean")
    }

    @Test fun expiryIsParsed() {
        val p = one(""","Expired":true,"KeyExpiry":"2026-11-01T00:00:00Z"""")
        assertTrue(p.expired); assertEquals(Instant.parse("2026-11-01T00:00:00Z"), p.keyExpiry)
        assertFalse(one("").expired); assertNull(one("").keyExpiry)
        assertFalse(one(""","Expired":null,"KeyExpiry":null""").expired)
        assertNull(one(""","KeyExpiry":"not a time"""").keyExpiry)
        assertNull(one(""","KeyExpiry":"0001-01-01T00:00:00Z"""").keyExpiry, "Go's zero time is no expiry")
        assertFalse(one(""","Expired":"maybe"""").expired)
    }

    @Test fun primaryRoutesAreParsed() {
        assertEquals(listOf("192.0.2.0/24"), one(""","PrimaryRoutes":["192.0.2.0/24"]""").primaryRoutes)
        assertEquals(emptyList(), one(""","PrimaryRoutes":null""").primaryRoutes)
        assertEquals(emptyList(), one("").primaryRoutes)
        assertEquals(listOf("192.0.2.0/24"), one(""","PrimaryRoutes":["192.0.2.0/24",7,null,{"a":1}]""").primaryRoutes.filter { '/' in it })
    }

    @Test fun shareeUserAndLocationAreParsed() {
        val p = one(""","ShareeNode":true,"UserID":12345678901,"Location":{"Country":"Sweden","CountryCode":"SE","City":"Stockholm","Priority":5}""")
        assertTrue(p.sharee); assertEquals(12345678901L, p.userId)
        assertEquals(TailscaleLocation("Sweden", "SE", "Stockholm"), p.location)
        val bare = one("")
        assertFalse(bare.sharee); assertNull(bare.userId); assertNull(bare.location)
        assertNull(one(""","UserID":0""").userId, "zero is Go's unset value")
        assertNull(one(""","UserID":"abc","Location":"Sweden"""").let { it.userId ?: it.location })
        assertEquals(TailscaleLocation(null, null, null), one(""","Location":{}""").location, "an empty Location still marks a location-based node")
        assertNull(one(""","Location":null""").location)
    }

    @Test fun sshHostKeysAreReducedToCountAndTypes() {
        val secretish = "AAAAC3NzaC1lZDI1NTE5AAAAIExampleOnlyNotARealKey0000000000000000"
        val p = one(""","sshHostKeys":["ssh-ed25519 $secretish","ecdsa-sha2-nistp256 AAAAE2VjZHNh","ssh-rsa AAAAB3NzaC1yc2E","ssh-ed25519 AAAAsecond"]""")
        assertEquals(4, p.sshHostKeyCount)
        assertEquals(listOf("ssh-ed25519", "ecdsa-sha2-nistp256", "ssh-rsa"), p.sshHostKeyTypes)
        assertFalse(p.toString().contains("AAAA"), "no key material is kept in the model")
        assertEquals(0, one(""","sshHostKeys":null""").sshHostKeyCount)
        assertEquals(0, one("").sshHostKeyCount)
        assertEquals(0, one(""","sshHostKeys":"ssh-ed25519 AAAA"""").sshHostKeyCount, "a string instead of a list is ignored")
        val odd = one(""","sshHostKeys":["",7,null,"WEIRD TYPE!","ssh-ed25519 AAAA"]""")
        assertEquals(2, odd.sshHostKeyCount); assertEquals(listOf("ssh-ed25519"), odd.sshHostKeyTypes, "an odd type token is counted but not shown")
    }

    @Test fun exitNodeStatusIsParsed() {
        val s = status("", """"ExitNodeStatus":{"ID":"nX1","Online":true,"TailscaleIPs":["100.64.0.30/32","fd7a:115c:a1e0::30/128"]},""")
        assertEquals(TailscaleExitNodeStatus("nX1", true, listOf("100.64.0.30", "fd7a:115c:a1e0::30")), s.exitNodeStatus)
        assertNull(status("").exitNodeStatus, "absent when no exit node is set")
        assertNull(status("", """"ExitNodeStatus":null,""").exitNodeStatus)
        assertNull(status("", """"ExitNodeStatus":{"Online":true},""").exitNodeStatus, "no ID, no status")
        assertEquals(false, status("", """"ExitNodeStatus":{"ID":"x"},""").exitNodeStatus?.online)
    }

    @Test fun malformedPeersAreSkippedOneByOne() {
        val s = status(""""a":null,"b":"text","c":[1,2],"d":{"ID":"d","HostName":"ok","Online":true,"TailscaleIPs":["100.64.0.4"]},"e":{"ID":"e","HostName":"odd","Online":"yes","TailscaleIPs":"100.64.0.5","Tags":{"x":1},"RxBytes":"lots"}""")
        assertEquals(listOf("ok", "odd"), s.peers.map { it.hostName })
        assertEquals(3, s.unreadablePeers)
        val odd = s.peers.single { it.id == "e" }
        assertFalse(odd.online); assertTrue(odd.addresses.isEmpty()); assertNull(odd.rxBytes)
        assertEquals(0, status(""""d":{"ID":"d"}""").unreadablePeers)
        assertNull(parseTailscaleStatus("""{"BackendState":"Running","Self":"broken"}""").self)
    }

    @Test fun selfCarriesItsUserId() = assertEquals(7L, status("").self?.userId)

    // ------------------------------------------------------------------ the shared path label

    private fun p(online: Boolean = true, active: Boolean? = true, cur: String? = null, peerRelay: String? = null, relay: String? = "fra") =
        TailscalePeer("n", "pve1", null, null, online, listOf("100.64.0.10"), false, false, relay, cur, null, null, null, active = active, peerRelay = peerRelay)

    @Test fun peerPathFollowsTailscaleStatusOrder() {
        val table = listOf(
            p(cur = "203.0.113.9:41641") to PeerPath.Direct("203.0.113.9:41641"),
            p(peerRelay = "198.51.100.7:40000:12") to PeerPath.PeerRelayed("198.51.100.7:40000:12"),
            p() to PeerPath.Relayed("fra"),
            p(cur = "203.0.113.9:41641", peerRelay = "198.51.100.7:40000:12") to PeerPath.Direct("203.0.113.9:41641"),
            p(active = false, cur = "203.0.113.9:41641") to PeerPath.Idle,
            p(active = false) to PeerPath.Idle,
            p(online = false, active = false) to PeerPath.Offline,
            p(online = false) to PeerPath.Offline,
            p(active = null) to PeerPath.NotReported,
            p(active = null, cur = "203.0.113.9:41641") to PeerPath.Direct("203.0.113.9:41641"),
            p(relay = null) to PeerPath.NotReported,
        )
        for ((peer, expected) in table) assertEquals(expected, peerPath(peer), "$peer")
    }

    @Test fun pathLabelTable() {
        assertEquals("direct", peerPathLabel(PeerPath.Direct("203.0.113.9:41641")))
        assertEquals("peer relay", peerPathLabel(PeerPath.PeerRelayed("198.51.100.7:40000:12")))
        assertEquals("DERP fra", peerPathLabel(PeerPath.Relayed("fra")))
        assertEquals("idle", peerPathLabel(PeerPath.Idle))
        assertEquals("offline", peerPathLabel(PeerPath.Offline))
        assertEquals("no path", peerPathLabel(PeerPath.NotReported))
        assertEquals("not a peer", peerPathLabel(PeerPath.NotPeer))
        for (path in listOf(PeerPath.Direct("203.0.113.9:41641"), PeerPath.PeerRelayed("198.51.100.7:40000:12")))
            assertFalse(peerPathLabel(path).any { it.isDigit() }, "the label never carries an address")
    }

    @Test fun theTagAndTheTableUseTheSamePath() {
        val s = status(""""n":{"ID":"n","HostName":"pve1","TailscaleIPs":["100.64.0.10"],"Online":true,"Active":true,"CurAddr":"","Relay":"fra","PeerRelay":"198.51.100.7:40000:12"}""")
        assertEquals(peerPath(s.peers.single()), tailscalePeerPath(s, "100.64.0.10"))
    }

    // ------------------------------------------------------------------ peer-row tags

    private fun tags(fields: String) = tailscalePeerTags(one(fields), now).map { it.label }

    @Test fun peerTags() {
        assertEquals(listOf("Key expired"), tags(""","Expired":true"""))
        assertEquals(listOf("Key expired"), tags(""","KeyExpiry":"2026-11-15T00:00:00Z""""), "a past expiry is expired even without the flag")
        assertEquals(listOf("Expires in 5 d"), tags(""","KeyExpiry":"2026-11-21T10:00:00Z""""))
        assertEquals(listOf("Expires in 30 d"), tags(""","KeyExpiry":"2026-12-16T10:00:00Z""""))
        assertEquals(emptyList(), tags(""","KeyExpiry":"2026-12-17T10:00:01Z""""), "more than 30 days is not tagged")
        assertEquals(listOf("Expires today"), tags(""","KeyExpiry":"2026-11-16T20:00:00Z""""))
        assertEquals(listOf("SSH"), tags(""","sshHostKeys":["ssh-ed25519 AAAA"]"""))
        assertEquals(listOf("Shared"), tags(""","ShareeNode":true"""))
        assertEquals(listOf("Subnet router"), tags(""","PrimaryRoutes":["192.0.2.0/24"]"""))
        assertEquals(emptyList(), tags(""","PrimaryRoutes":["0.0.0.0/0","::/0"]"""), "exit routes are not a subnet router")
        assertEquals(listOf("Key expired", "SSH", "Shared", "Subnet router"),
            tags(""","Expired":true,"sshHostKeys":["ssh-ed25519 AAAA"],"ShareeNode":true,"PrimaryRoutes":["192.0.2.0/24"]"""))
        assertEquals(emptyList(), tags(""))
        val all = tailscalePeerTags(one(""","Expired":true,"sshHostKeys":["ssh-ed25519 AAAA"],"ShareeNode":true,"PrimaryRoutes":["192.0.2.0/24"]"""), now)
        assertTrue(all.none { t -> listOf("pve1", "100.64", "192.0.2").any { it in t.label || it in t.help } }, "tags carry no names or addresses")
        assertEquals(PeerTagTone.Warning, all.first().tone)
    }

    // ------------------------------------------------------------------ userspace and the route tag

    private val at = Instant.parse("2026-11-16T10:00:00Z")
    private fun classify(address: String, tun: Boolean?, iface: RouteInterface = RouteInterface("eth0", 2, null, RouteMedium.Ethernet), source: String = "192.0.2.20"): RouteObservation {
        val s = status(""""n":{"ID":"n","HostName":"pve1","TailscaleIPs":["100.64.0.10"],"Online":true}""", tun?.let { """"TUN":$it,""" }.orEmpty())
        val ts = VpnTunnel("tailscale", VpnKind.Tailscale, "Tailscale", VpnState.Up, "tailscale0", listOf("100.64.0.1"))
        val snap = VpnSnapshot(at, listOf(ts), true, s, false, emptyList(), emptyList())
        return classifyConnectionRoute(routeTarget("https://${if (':' in address) "[$address]" else address}:8006")!!, at, null, Result.success(listOf(InetAddress.getByName(address))),
            Result.success(OsRoute(address, iface, source, "192.0.2.1")), VpnInventory(snap))
    }

    @Test fun userspaceWithholdsDirectOnlyForTailnetDestinations() {
        val peer = classify("100.64.0.10", tun = false)
        assertEquals(RouteKind.Unknown, peer.kind); assertEquals(TAILSCALE_USERSPACE_NOTE, peer.reason)
        assertNull(peer.vpnKind, "withholding never claims a tunnel")
        assertEquals(RouteKind.Unknown, classify("100.100.1.1", tun = false).kind, "inside 100.64.0.0/10")
        assertEquals(RouteKind.Unknown, classify("fd7a:115c:a1e0::99", tun = false).kind, "inside fd7a:115c:a1e0::/48")
        assertEquals(RouteKind.Direct, classify("192.0.2.15", tun = false).kind, "a LAN server is still direct")
        assertEquals(RouteKind.Direct, classify("100.128.0.1", tun = false).kind, "just outside the range")
        assertEquals(RouteKind.Direct, classify("100.64.0.10", tun = true).kind, "kernel mode changes nothing")
        assertEquals(RouteKind.Direct, classify("100.64.0.10", tun = null).kind, "no TUN field changes nothing")
        // A tunnel match is evidence and stays.
        val wg = classify("100.64.0.10", tun = false, iface = RouteInterface("wg0", 5, null, RouteMedium.Tunnel, VpnKind.WireGuard), source = "10.8.0.2")
        assertEquals(RouteKind.Vpn, wg.kind)
    }

    @Test fun tailnetDestinationIsNeverRouteEvidence() {
        val s = status("")
        assertTrue(tailnetDestination("100.64.0.0", s)); assertTrue(tailnetDestination("100.127.255.255", s))
        assertFalse(tailnetDestination("100.63.255.255", s)); assertFalse(tailnetDestination("100.128.0.0", s))
        assertTrue(tailnetDestination("fd7a:115c:a1e0:ab12::1", s)); assertFalse(tailnetDestination("fd7a:115c:a1e1::1", s))
        assertFalse(tailnetDestination("not-an-address", s))
        // Kernel-mode Tailscale with a 100.x destination and an Ethernet route is still Direct: the range is not evidence.
        assertEquals(RouteKind.Direct, classify("100.100.1.1", tun = true).kind)
    }
}
