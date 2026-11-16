package local.ringleader.core

import java.net.InetAddress
import java.time.Instant
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Subnet routes (2026-11-16, Slice 2 of the Tailscale audit): the route list from status fixtures, accept-routes detection
 * per platform and client version with the OS-route fallback, the one confirmed change, the search through routers, the
 * Scan page adapter and the route tag's subnet-router path. Invented data only: example.ts.net, 100.64.0.x, documentation
 * ranges, and private example ranges where the private-only rule needs one.
 */
class SubnetRoutesTest {
    private val at = Instant.parse("2026-11-16T12:00:00Z")

    private fun status(peers: String, self: String = """"PrimaryRoutes":[]""", top: String = """"TUN":true,""") = parseTailscaleStatus("""
        {"BackendState":"Running",$top"Self":{"ID":"self","HostName":"desk-01","TailscaleIPs":["100.64.0.1"],"Online":true,"UserID":7,$self},
         "Peer":{$peers}}""")

    private val router = """"r":{"ID":"r","HostName":"subnet-gw","TailscaleIPs":["100.64.0.20"],"Online":true,"Active":true,"UserID":7,
        "AllowedIPs":["100.64.0.20/32","10.20.30.0/24"],"PrimaryRoutes":["10.20.30.0/24"]}"""
    private val lab = """"l":{"ID":"l","HostName":"lab-router","TailscaleIPs":["100.64.0.21"],"Online":false,"UserID":7,
        "AllowedIPs":["100.64.0.21/32","192.168.77.0/24"],"PrimaryRoutes":["192.168.77.0/24"]}"""
    private val exit = """"x":{"ID":"x","HostName":"exit-gw","TailscaleIPs":["100.64.0.70"],"Online":true,"ExitNodeOption":true,"UserID":7,
        "AllowedIPs":["100.64.0.70/32","0.0.0.0/0","::/0"]}"""
    private val docs = """"d":{"ID":"d","HostName":"docs-gw","TailscaleIPs":["100.64.0.22"],"Online":true,"UserID":7,"PrimaryRoutes":["192.0.2.0/24","10.40.0.7/32"]}"""
    private val old = """"o":{"ID":"o","HostName":"old-gw","TailscaleIPs":["100.64.0.23"],"Online":true,"UserID":7,"AllowedIPs":["100.64.0.23/32","172.20.1.0/24"]}"""
    private val shared = """"s":{"ID":"s","HostName":"friend-gw","TailscaleIPs":["100.64.0.40"],"Online":true,"ShareeNode":true,"UserID":99,"PrimaryRoutes":["10.99.0.0/24"]}"""
    private val mullvad = """"m":{"ID":"m","HostName":"se-sto-wg-001","TailscaleIPs":["100.64.0.60"],"Online":true,"Location":{"Country":"Sweden","CountryCode":"SE"},"AllowedIPs":["100.64.0.60/32","10.124.0.0/16"]}"""

    // ------------------------------------------------------------------ the route list

    @Test fun primaryRoutesAllowedIpsExitNodesAndNonPrivateNetworks() {
        val routes = tailnetSubnetRoutes(status("$router,$lab,$exit,$docs,$old,$mullvad"))
        assertEquals(listOf("10.20.30.0/24" to "subnet-gw", "172.20.1.0/24" to "old-gw", "192.168.77.0/24" to "lab-router"), routes.map { it.cidr to it.routerName },
            "online first, then by network; no 0.0.0.0/0, no documentation range, no host route, nothing from a location-based exit node")
        assertTrue(routes.first { it.routerName == "subnet-gw" }.primary)
        assertFalse(routes.first { it.routerName == "old-gw" }.primary, "an older client without PrimaryRoutes: from AllowedIPs wider than a host route")
        assertFalse(routes.first { it.routerName == "lab-router" }.routerOnline)
        assertTrue(routes.none { it.own })
    }

    @Test fun ownRoutesAndOverlapsWithLocalNetworks() {
        val s = status("$router,$lab", self = """"PrimaryRoutes":["10.50.0.0/24"]""")
        val routes = tailnetSubnetRoutes(s, listOf(LocalNetwork("Ethernet", "192.168.77.40/24"), LocalNetwork("Wi-Fi", "10.1.0.5/16")))
        val own = routes.single { it.own }
        assertEquals("10.50.0.0/24", own.cidr); assertEquals("desk-01", own.routerName); assertEquals(routes.last(), own, "own routes last")
        assertEquals(LocalNetwork("Ethernet", "192.168.77.40/24"), routes.first { it.cidr == "192.168.77.0/24" }.overlap)
        assertNull(routes.first { it.cidr == "10.20.30.0/24" }.overlap)
        // A wider route overlaps a narrower local network and the other way round.
        assertNotNull(tailnetSubnetRoutes(status(router), listOf(LocalNetwork("x", "10.20.30.128/25"))).single().overlap)
        assertNotNull(tailnetSubnetRoutes(status(router), listOf(LocalNetwork("x", "10.0.0.1/8"))).single().overlap)
    }

    @Test fun cidrHelpers() {
        assertEquals("10.20.30.0/24", parseIpv4Net("10.20.30.77/24").toString())
        assertEquals("10.20.30.77/32", parseIpv4Net("10.20.30.77").toString())
        assertNull(parseIpv4Net("fd7a:115c:a1e0::/48")); assertNull(parseIpv4Net("10.0.0.0/33")); assertNull(parseIpv4Net("nonsense"))
        assertTrue(privateIpv4Net(parseIpv4Net("172.31.0.0/16")!!)); assertFalse(privateIpv4Net(parseIpv4Net("172.0.0.0/8")!!), "wider than the private block")
        assertFalse(privateIpv4Net(parseIpv4Net("100.64.0.0/10")!!)); assertFalse(privateIpv4Net(parseIpv4Net("198.51.100.0/24")!!))
    }

    @Test fun sharedRoutersAreMarked() {
        val r = tailnetSubnetRoutes(status("$router,$shared"))
        assertTrue(r.first { it.routerName == "friend-gw" }.sharedRouter); assertFalse(r.first { it.routerName == "subnet-gw" }.sharedRouter)
    }

    // ------------------------------------------------------------------ accept-routes detection

    private val ts = VpnTunnel("tailscale", VpnKind.Tailscale, "Tailscale", VpnState.Up, "tailscale0", listOf("100.64.0.1"))
    private fun viaTailscale(a: String) = OsRoute(a, RouteInterface("tailscale0", 9, null, RouteMedium.Tunnel), "100.64.0.1", null)
    private fun viaEthernet(a: String) = OsRoute(a, RouteInterface("eth0", 2, null, RouteMedium.Ethernet), "192.168.1.20", "192.168.1.1")
    private fun detect(platform: String, get: Result<String>?, s: TailscaleStatus = status("$router,$lab"), route: ((String) -> OsRoute)? = null, local: List<LocalNetwork> = emptyList()) =
        detectAcceptRoutes(platform, get, tailnetSubnetRoutes(s, local), listOf(ts), listOf("100.64.0.1"), route)

    @Test fun thePreferenceDecidesOnCurrentClients() {
        val off = detect("Linux", Result.success("""{"accept-routes": false, "operator": "", "hostname": ""}"""))
        assertEquals(AcceptRoutes.NotAccepted, off.answer); assertEquals(AcceptRoutesSource.Preference, off.source)
        assertTrue(off.operatorKnown); assertEquals("", off.operator)
        val on = detect("Windows", Result.success("{\n  \"accept-routes\": true\n}"))
        assertEquals(AcceptRoutes.Accepted, on.answer); assertFalse(on.operatorKnown)
        assertEquals(true to "ring", parseTailscaleGet("""{"accept-routes":true,"operator":"ring"}"""))
        assertEquals(null to null, parseTailscaleGet("""{"accept-routes":"true"}"""), "a string is not the boolean the CLI prints")
        assertEquals(null to null, parseTailscaleGet("[]"))
    }

    @Test fun olderClientsFallBackToTheOsRoute() {
        val unsupported = Result.failure<String>(IllegalStateException("unknown subcommand \"get\""))
        val read = mutableListOf<String>()
        val accepted = detect("Linux", unsupported, route = { read += it; viaTailscale(it) })
        assertEquals(AcceptRoutes.Accepted, accepted.answer); assertEquals(AcceptRoutesSource.OsRoute, accepted.source)
        assertEquals(listOf("10.20.30.1"), read, "the first host of the online router's network, the offline router is not used")
        assertTrue(accepted.reason!!.contains("1.102.1"))
        assertEquals(AcceptRoutes.NotAccepted, detect("Linux", unsupported, route = ::viaEthernet).answer)
        // Windows: the route names the Tailscale adapter by its driver.
        val windows = detect("Windows", unsupported, route = { OsRoute(it, RouteInterface("Tailscale", 12, "Tailscale Tunnel", RouteMedium.Tunnel, VpnKind.Tailscale), null, null) })
        assertEquals(AcceptRoutes.Accepted, windows.answer)
    }

    @Test fun unknownCoversWhatCannotBeTold() {
        val unsupported = Result.failure<String>(IllegalStateException("flag provided but not defined: -json"))
        val noProbe = detect("Linux", unsupported, route = null)
        assertEquals(AcceptRoutes.Unknown, noProbe.answer)
        assertTrue(noProbe.reason!!.contains("Route checks are not available") && noProbe.reason!!.contains("On Linux, Tailscale does not use subnet routes"), noProbe.reason)
        val onlyOverlap = detect("Windows", unsupported, s = status(router), route = ::viaTailscale, local = listOf(LocalNetwork("Ethernet", "10.20.30.9/24")))
        assertEquals(AcceptRoutes.Unknown, onlyOverlap.answer, "an overlapping network cannot tell the two apart")
        assertTrue(onlyOverlap.reason!!.contains("Use Tailscale subnets"), onlyOverlap.reason)
        val offline = detect("Linux", unsupported, s = status(lab), route = ::viaTailscale)
        assertEquals(AcceptRoutes.Unknown, offline.answer); assertTrue(offline.reason!!.contains("No subnet router is online"))
        val failed = detect("Linux", unsupported, route = { error("ip: boom") })
        assertEquals(AcceptRoutes.Unknown, failed.answer); assertTrue(failed.reason!!.contains("The route check failed"))
        val noInterface = detect("Linux", unsupported, route = { OsRoute(it, null, null, null) })
        assertEquals(AcceptRoutes.Unknown, noInterface.answer)
        // The daemon is down: not a version problem, said as such, and the fallback still runs.
        val down = detect("Linux", Result.failure(IllegalStateException("failed to connect to local tailscaled")), route = ::viaEthernet)
        assertEquals(AcceptRoutes.NotAccepted, down.answer); assertTrue(down.reason!!.contains("could not be read"))
        assertTrue(tailscaleGetUnsupported("unknown setting \"accept-routes\"; see \"tailscale set --help\""))
        assertFalse(tailscaleGetUnsupported("Access denied: prefs read access denied"))
    }

    @Test fun overlapWinnerComesFromTheOsRoute() {
        val routes = tailnetSubnetRoutes(status(router), listOf(LocalNetwork("Ethernet", "10.20.30.9/24")))
        val asked = mutableListOf<String>()
        assertEquals(mapOf("10.20.30.0/24" to OverlapWinner.Local), overlapWinners(routes, listOf(ts), listOf("100.64.0.1")) { asked += it; viaEthernet(it) })
        assertEquals(listOf("10.20.30.1"), asked)
        assertEquals(OverlapWinner.Tailscale, overlapWinners(routes, listOf(ts), listOf("100.64.0.1"), ::viaTailscale).values.single())
        assertEquals(OverlapWinner.Unknown, overlapWinners(routes, listOf(ts), listOf("100.64.0.1")) { error("x") }.values.single())
        assertEquals(OverlapWinner.Unknown, overlapWinners(routes, listOf(ts), listOf("100.64.0.1"), null).values.single())
        val r = routes.single()
        assertTrue(overlapSentence(r.cidr, false, null, "subnet-gw", "Ethernet").contains("reached directly here"))
        assertTrue(overlapSentence(r.cidr, true, OverlapWinner.Local, "subnet-gw", "Ethernet").contains("the local network wins"))
        assertTrue(overlapSentence(r.cidr, true, OverlapWinner.Tailscale, "subnet-gw", "Ethernet").contains("sends it through subnet-gw"))
        assertTrue(overlapSentence(r.cidr, true, OverlapWinner.Unknown, "subnet-gw", "Ethernet").contains("was not read"))
    }

    @Test fun linuxWithoutOperatorRightsGetsTheCommand() {
        val notOperator = AcceptRoutesReading(AcceptRoutes.NotAccepted, AcceptRoutesSource.Preference, operator = "", operatorKnown = true)
        assertTrue(acceptRoutesNeedsSudo("Linux", notOperator, "ring"))
        assertFalse(acceptRoutesNeedsSudo("Linux", notOperator.copy(operator = "ring"), "ring"), "the operator may change it")
        assertFalse(acceptRoutesNeedsSudo("Linux", notOperator, "root"))
        assertFalse(acceptRoutesNeedsSudo("Linux", notOperator.copy(operatorKnown = false), "ring"), "unknown: offer the button, the refusal names the command")
        assertFalse(acceptRoutesNeedsSudo("Windows", notOperator, "ring"))
        assertEquals("sudo tailscale set --accept-routes=true", ACCEPT_ROUTES_SUDO_COMMAND)
    }

    // ------------------------------------------------------------------ the backend: reads and the one change

    private val statusJson = """{"BackendState":"Running","TUN":true,"Self":{"ID":"self","TailscaleIPs":["100.64.0.1"]},"Peer":{$router}}"""

    @Test fun theReadIsTailscaleGetJson() {
        val calls = mutableListOf<List<String>>()
        val b = LocalVpnBackend("Linux", { c, _ -> calls += c; if (c[1] == "get") """{"accept-routes": false, "operator": ""}""" else statusJson }, { if (it == "tailscale") "/usr/bin/tailscale" else null },
            routes = object : RouteProbe { override fun route(address: String): OsRoute = error("not used when the preference answers") })
        val r = b.acceptRoutes(parseTailscaleStatus(statusJson))
        assertEquals(AcceptRoutes.NotAccepted, r.answer)
        assertEquals(listOf(listOf("/usr/bin/tailscale", "get", "--json")), calls, "one read, no change")
    }

    @Test fun theChangeIsOneSetCommandAndIsNeverRetried() {
        val calls = mutableListOf<List<String>>()
        val ok = LocalVpnBackend("Windows", { c, _ -> calls += c; "" }, { if (it.endsWith("tailscale.exe")) it else null }, hostActions = true, env = { null })
        ok.setAcceptRoutes(true)
        assertEquals(listOf(listOf("C:\\Program Files\\Tailscale\\tailscale.exe", "set", "--accept-routes=true")), calls)
        assertFailsWith<IllegalStateException> { LocalVpnBackend("Windows", { _, _ -> error("no") }, { it }, hostActions = false).setAcceptRoutes(true) }
            .also { assertEquals(VPN_HOST_ACTIONS_REASON, it.message) }
    }

    @Test fun aLinuxRefusalNamesTheExactSudoCommandAfterReReadingTheState() {
        val calls = mutableListOf<List<String>>()
        val b = LocalVpnBackend("Linux", { c, _ -> calls += c
            if (c[1] == "set") throw IllegalStateException(vpnFailureMessage("Access denied: prefs write access denied")) else statusJson }, { if (it == "tailscale") "/usr/bin/tailscale" else null }, hostActions = true)
        val e = assertFailsWith<IllegalStateException> { b.setAcceptRoutes(true) }
        assertEquals("Tailscale only lets root or its operator change this. Run in a terminal: sudo tailscale set --accept-routes=true (or once: sudo tailscale set --operator=\$USER). Tailscale now reports: running.", e.message)
        assertEquals(listOf(listOf("/usr/bin/tailscale", "set", "--accept-routes=true"), listOf("/usr/bin/tailscale", "status", "--json")), calls,
            "one attempt, then a read of the state (never a retry)")
    }

    @Test fun stillAppliesAndResultSentences() {
        val routes = tailnetSubnetRoutes(status(router))
        assertTrue(acceptRoutesStillApplies(AcceptRoutesReading(AcceptRoutes.NotAccepted), routes))
        assertFalse(acceptRoutesStillApplies(AcceptRoutesReading(AcceptRoutes.Accepted), routes))
        assertFalse(acceptRoutesStillApplies(AcceptRoutesReading.unknown("x"), routes))
        assertFalse(acceptRoutesStillApplies(AcceptRoutesReading(AcceptRoutes.NotAccepted), emptyList()))
        assertFalse(acceptRoutesStillApplies(null, routes))
        assertEquals("This computer now uses the tailnet's networks.", acceptRoutesResult(AcceptRoutesReading(AcceptRoutes.Accepted)))
        assertFailsWith<IllegalStateException> { acceptRoutesResult(AcceptRoutesReading(AcceptRoutes.NotAccepted)) }
        assertTrue(acceptRoutesResult(AcceptRoutesReading.unknown("Why.")).startsWith("Tailscale took the change; the setting could not be read back. Why."))
        assertTrue(acceptRoutesConfirmBody().contains("This runs: tailscale set --accept-routes=true"))
        assertTrue(acceptRoutesConfirmBody().contains("whole computer"))
    }

    // ------------------------------------------------------------------ the search through routers and the adapter

    private val accepted = AcceptRoutesReading(AcceptRoutes.Accepted, AcceptRoutesSource.Preference)

    @Test fun searchPlanningThroughRoutersIsGatedOnAcceptRoutes() {
        val s = status("$router,$lab,$shared")
        val on = planTailnetSearch(s, accept = accepted)
        assertEquals(listOf("10.20.30.0/24"), on.subnets.map { it.second.cidr }, "online, own and not shared only")
        assertEquals(ScanRules.planSubnet("10.20.30.0/24")!!.addresses, on.subnets.single().second.addresses, "the scan's own planner and limits")
        assertEquals("Through subnet-gw", on.subnets.single().second.source.name)
        assertNull(on.subnetsOff)
        assertEquals(2, planTailnetSearch(s, includeShared = true, accept = accepted).subnets.size, "a shared router's network only when shared machines are included")
        val off = planTailnetSearch(s, accept = AcceptRoutesReading(AcceptRoutes.NotAccepted))
        assertTrue(off.subnets.isEmpty()); assertTrue(off.subnetsOff!!.contains("does not use the tailnet's subnet routes"))
        val unknown = planTailnetSearch(s, accept = AcceptRoutesReading.unknown("x"))
        assertTrue(unknown.subnets.isEmpty()); assertTrue(unknown.subnetsOff!!.contains("not known"))
        assertTrue(planTailnetSearch(s).subnets.isEmpty(), "not read yet: off")
        val overlap = planTailnetSearch(s, accept = accepted, local = listOf(LocalNetwork("Ethernet", "10.20.30.9/24")))
        assertTrue(overlap.subnets.isEmpty(), "a network that is also local is the Scan page's local sweep")
        val userspace = planTailnetSearch(status("$router", top = """"TUN":false,"""), accept = accepted)
        assertTrue(userspace.subnets.isEmpty()); assertTrue(userspace.subnetsOff!!.contains("userspace"))
        assertNull(planTailnetSearch(status(exit), accept = AcceptRoutesReading(AcceptRoutes.NotAccepted)).subnetsOff, "nothing offered, nothing to explain")
        // A wide route is cut by the scan planner, never swept whole.
        val wide = planTailnetSearch(status(""""w":{"ID":"w","HostName":"wide-gw","TailscaleIPs":["100.64.0.24"],"Online":true,"UserID":7,"PrimaryRoutes":["10.0.0.0/8"]}"""), accept = accepted)
        assertEquals(ScanRules.planSubnet("10.0.0.0/8")!!.addresses.size, wide.subnets.single().second.addresses.size)
    }

    @Test fun hitsBehindARouterAreLabelledWithIt() = runBlocking {
        val s = status(router)
        val seen = mutableSetOf<String>()
        val scanner = NetworkScanner { ip, port -> synchronized(seen) { seen += ip }
            if (ip == "10.20.30.15" && port == 8006) listOf(ScanHit(ip, port, true, ScanKind.PVE)) else emptyList() }
        val found = findTailnetProxmox(s, scanner, plan = planTailnetSearch(s, accept = accepted)).last()
        val hit = found.single()
        assertEquals("10.20.30.15", hit.hit.ip); assertEquals("subnet-gw", hit.peer.hostName); assertEquals("10.20.30.0/24", hit.through?.cidr)
        assertTrue("100.64.0.20" in seen && "10.20.30.200" in seen)
        assertEquals("SEARCHING ONLINE PEERS AND 1 ROUTED NETWORK ON 8006 \u00b7 8007 \u00b7 8443", tailnetSearchHeader(true, 0, 1))
        assertEquals("SEARCHING ONLINE PEERS ON 8006 \u00b7 8007 \u00b7 8443", tailnetSearchHeader(true, 0))
    }

    @Test fun theScanPageAdapterListsAcceptedRoutesOnly() {
        val s = status("$router,$lab,$shared", self = """"PrimaryRoutes":["10.50.0.0/24"]""")
        assertEquals(listOf("subnet-gw" to "10.20.30.0/24"), tailnetReachableNetworks(s, accepted, emptyList()))
        assertEquals(emptyList(), tailnetReachableNetworks(s, AcceptRoutesReading(AcceptRoutes.NotAccepted), emptyList()))
        assertEquals(emptyList(), tailnetReachableNetworks(s, AcceptRoutesReading.unknown("x"), emptyList()))
        assertEquals(emptyList(), tailnetReachableNetworks(s, accepted, listOf(LocalNetwork("Ethernet", "10.20.30.9/24"))))
        assertEquals(emptyList(), tailnetReachableNetworks(null, accepted, emptyList()))
        assertEquals(emptyList(), tailnetReachableNetworks(status(router, top = """"TUN":false,"""), accepted, emptyList()))
    }

    // ------------------------------------------------------------------ the route tag

    private fun classify(s: TailscaleStatus, address: String, route: OsRoute) = classifyConnectionRoute(routeTarget("https://$address:8006")!!, at, null,
        Result.success(listOf(InetAddress.getByName(address))), Result.success(route), VpnInventory(VpnSnapshot(at, listOf(ts), true, s, false, emptyList(), emptyList())))

    @Test fun theTagNamesTheRouterOnlyWhenTheOsRouteAgrees() {
        val s = status("$router,$exit")
        val o = classify(s, "10.20.30.15", viaTailscale("10.20.30.15"))
        assertEquals(RouteKind.Vpn, o.kind)
        val path = assertIs<PeerPath.SubnetRouter>(o.peerPath)
        assertEquals("subnet-gw", path.router); assertEquals("10.20.30.0/24", path.cidr); assertEquals(PeerPath.NotReported, path.path)
        assertEquals("subnet router", peerPathLabel(path))
        // The same address over the local network: the range alone is never evidence, no router is named.
        val direct = classify(s, "10.20.30.15", viaEthernet("10.20.30.15"))
        assertEquals(RouteKind.Direct, direct.kind); assertNull(direct.peerPath)
        // An exit node still carries no router.
        assertEquals(PeerPath.NotPeer, classify(s, "203.0.113.50", viaTailscale("203.0.113.50")).peerPath)
    }

    @Test fun theNarrowestRouteWinsAndOwnRoutesAreNotRouters() {
        val s = status("""$router,"w":{"ID":"w","HostName":"wide-gw","TailscaleIPs":["100.64.0.24"],"Online":true,"PrimaryRoutes":["10.0.0.0/8"]}""",
            self = """"PrimaryRoutes":["10.60.0.0/24"]""")
        assertEquals("subnet-gw", (tailscalePeerPath(s, "10.20.30.15") as PeerPath.SubnetRouter).router)
        assertEquals("wide-gw", (tailscalePeerPath(s, "10.21.0.1") as PeerPath.SubnetRouter).router)
        assertEquals("wide-gw", (tailscalePeerPath(s, "10.60.0.5") as PeerPath.SubnetRouter).router, "this computer's own route is not a router for itself")
        assertEquals(PeerPath.NotPeer, tailscalePeerPath(s, "192.168.200.1"))
    }
}
