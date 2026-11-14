package local.ringleader.core

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.time.Instant
import java.util.Base64
import kotlin.test.*

/** Route classification from fake route answers and VPN snapshots. Nothing here touches the network or the OS. */
class ConnectionRouteTest {
    private val at = Instant.parse("2026-11-12T10:00:00Z")
    private val target = routeTarget("https://pve.example.test:8006")!!
    private fun ip(text: String) = InetAddress.getByName(text)  // literals only: no lookup
    private fun snap(vararg tunnels: VpnTunnel, notes: List<String> = emptyList(), tailscale: TailscaleStatus? = null) =
        VpnSnapshot(at, tunnels.toList(), tailscale != null, tailscale, false, emptyList(), notes)
    private fun peer(name: String, address: String, direct: String? = null, relay: String? = null, active: Boolean? = true, online: Boolean = true, exit: Boolean = false) =
        TailscalePeer("id-$name", name, null, null, online, listOf(address), false, exit, relay, direct, null, null, null, active = active)
    private fun tsStatus(self: String, vararg peers: TailscalePeer) =
        TailscaleStatus("Running", null, null, null, peer("self", self), peers.toList(), null, emptyList())
    private val tailscaleUp = VpnTunnel("tailscale", VpnKind.Tailscale, "Tailscale", VpnState.Up, "tailscale0", listOf("100.101.1.2"))
    private val wgUp = VpnTunnel("wg:home", VpnKind.WireGuard, "home", VpnState.Up, "wg0", listOf("10.8.0.2/24"))
    private val ethernet = RouteInterface("eth0", 2, null, RouteMedium.Ethernet)

    private fun classify(route: OsRoute?, vpn: VpnInventory, address: String = "192.0.2.15", failure: Throwable? = null, addresses: List<InetAddress>? = null) =
        classifyConnectionRoute(target, at, null, Result.success(addresses ?: listOf(ip(address))), failure?.let { Result.failure(it) } ?: route?.let { Result.success(it) }, vpn)

    @Test fun anUnrelatedActiveVpnDoesNotMakeAnOrdinaryRouteAVpn() {
        val o = classify(OsRoute("192.0.2.15", ethernet, "192.168.1.20", "192.168.1.1"), VpnInventory(snap(tailscaleUp, wgUp)))
        assertEquals(RouteKind.Direct, o.kind)
        assertEquals(RouteMedium.Ethernet, o.medium)
        assertNull(o.vpnKind); assertNull(o.tunnelName)
        assertTrue(o.vpnComplete)
    }

    @Test fun incompleteVpnChecksCannotConfirmOrdinaryOrVirtualRoutes() {
        for (medium in listOf(RouteMedium.Ethernet, RouteMedium.WiFi, RouteMedium.Virtual)) {
            val route = OsRoute("192.0.2.15", RouteInterface("test0", medium = medium), null, null)
            assertEquals(RouteKind.Unknown, classify(route, VpnInventory.Unavailable).kind)
            assertEquals(RouteKind.Direct, classify(route, VpnInventory(snap())).kind)
        }
    }

    @Test fun proxySelectorFailureIsNotReportedAsDirect() {
        val selector = object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = error("Cannot read proxy configuration")
            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
        }
        assertFailsWith<IllegalStateException> { javaProxyFor(URI(target.origin), selector) }
    }

    @Test fun theRoutedTunnelIsNamedWithItsEvidence() {
        val wg = classify(OsRoute("192.0.2.15", RouteInterface("wg0", medium = RouteMedium.Tunnel, driverKind = VpnKind.WireGuard), "10.8.0.9", null), VpnInventory(snap(tailscaleUp, wgUp)))
        assertEquals(RouteKind.Vpn, wg.kind); assertEquals(VpnKind.WireGuard, wg.vpnKind); assertEquals("home", wg.tunnelName)
        assertEquals(RouteEvidence.InterfaceName, wg.evidence)
        // The source address on Tailscale's own address ties the route to Tailscale whatever the interface is called.
        val ts = classify(OsRoute("100.70.1.5", RouteInterface("utun7", medium = RouteMedium.Tunnel), "100.101.1.2", null),
            VpnInventory(snap(tailscaleUp.copy(addresses = emptyList()), tailscale = tsStatus("100.101.1.2"))), "100.70.1.5")
        assertEquals(VpnKind.Tailscale, ts.vpnKind); assertEquals(RouteEvidence.SourceAddress, ts.evidence)
    }

    @Test fun aTailscaleRangeAddressIsNotProofOfTailscale() {
        val o = classify(OsRoute("100.100.5.5", ethernet, "192.168.1.20", "192.168.1.1"), VpnInventory(snap(tailscaleUp)), "100.100.5.5")
        assertEquals(RouteKind.Direct, o.kind)
        assertNull(matchRouteTunnel(null, null, listOf(tailscaleUp)), "no route facts, no match")
        assertNull(matchRouteTunnel("Ethernet", "192.168.1.20", listOf(tailscaleUp, wgUp)))
    }

    @Test fun aProfileNameIsNotMatchedAgainstAnInterfaceName() {
        val nm = VpnTunnel("nm:1", VpnKind.OpenVpn, "eth0", VpnState.Up, "tun0")
        assertNull(matchRouteTunnel("eth0", null, listOf(nm)), "an NM profile called eth0 is not the eth0 interface")
        val ras = VpnTunnel("ras:Office", VpnKind.IpSec, "Office", VpnState.Up, null)
        assertEquals(ras to RouteEvidence.DialUpName, matchRouteTunnelEvidence("Office", null, listOf(ras)))
        val down = VpnTunnel("ras:Office", VpnKind.IpSec, "Office", VpnState.Down, null)
        assertNull(matchRouteTunnelEvidence("Office", null, listOf(down)), "a dial-up VPN that is down is not the route")
    }

    @Test fun failedCommandsAndMissingFactsAreUnknownNeverDirect() {
        val vpn = VpnInventory(snap())
        val unreachable = classify(null, vpn, failure = IllegalStateException("RTNETLINK answers: Network is unreachable"))
        assertEquals(RouteKind.Unknown, unreachable.kind); assertEquals("The operating system has no route to this address.", unreachable.reason)
        val failed = classify(null, vpn, failure = IllegalStateException("Find-NetRoute : access denied"))
        assertEquals(RouteKind.Unknown, failed.kind); assertTrue(failed.reason!!.startsWith("The route check failed"))
        val keyed = classify(null, vpn, failure = IllegalStateException("PrivateKey = abc"))
        assertEquals("The route check failed.", keyed.reason, "a key line is never quoted")
        val noInterface = classify(OsRoute("192.0.2.15", null, null, null), vpn)
        assertEquals(RouteKind.Unknown, noInterface.kind)
        val notRead = classify(null, vpn)
        assertEquals(RouteKind.Unknown, notRead.kind)
    }

    @Test fun anIncompleteVpnCheckDoesNotProveThereIsNoVpn() {
        val unknownType = OsRoute("192.0.2.15", RouteInterface("eth9"), "192.168.1.20", null)
        val gap = VpnInventory(snap(notes = listOf("Tailscale is installed but its status could not be read.")))
        assertEquals(RouteKind.Unknown, classify(unknownType, gap).kind)
        assertEquals(RouteKind.Unknown, classify(unknownType, VpnInventory.Unavailable).kind)
        assertEquals(RouteKind.Unknown, classify(unknownType, VpnInventory(null, "The VPN check failed.")).kind)
        assertEquals(RouteKind.Direct, classify(unknownType, VpnInventory(snap())).kind, "complete list, no tunnel on the route")
        val peerOnly = VpnInventory(snap(notes = listOf("$WIREGUARD_PEER_NOTE Permission denied.")))
        assertTrue(peerOnly.complete, "missing WireGuard peer details do not hide a tunnel")
        // An ordinary interface does not compensate for incomplete VPN evidence.
        val physical = classify(OsRoute("192.0.2.15", ethernet, null, null), gap)
        assertEquals(RouteKind.Unknown, physical.kind); assertFalse(physical.vpnComplete); assertTrue(physical.vpnGaps.isNotEmpty())
    }

    @Test fun aVpnSharingThePhysicalInterfaceIsNotClaimedEitherWay() {
        val ipsec = VpnTunnel("nm:abc", VpnKind.IpSec, "Office", VpnState.Up, "eth0")
        val o = classify(OsRoute("192.0.2.15", ethernet, "192.168.1.20", "192.168.1.1"), VpnInventory(snap(ipsec)))
        assertEquals(RouteKind.Unknown, o.kind); assertEquals("Office", o.tunnelName)
    }

    @Test fun driverAndAdapterFactsNameUnlistedTunnels() {
        val driver = classify(OsRoute("192.0.2.15", RouteInterface("wg7", medium = RouteMedium.Tunnel, driverKind = VpnKind.WireGuard), null, null), VpnInventory(snap()))
        assertEquals(RouteKind.Vpn, driver.kind); assertEquals(RouteEvidence.Driver, driver.evidence); assertNull(driver.tunnelName)
        val adapter = classify(OsRoute("192.0.2.15", RouteInterface("tun3", medium = RouteMedium.Tunnel), null, null), VpnInventory(snap()))
        assertEquals(VpnKind.Other, adapter.vpnKind); assertEquals(RouteEvidence.TunnelAdapter, adapter.evidence)
        val ppp = classify(OsRoute("192.0.2.15", RouteInterface("ppp0", medium = RouteMedium.PointToPoint), null, null), VpnInventory(snap()))
        assertEquals(RouteKind.Unknown, ppp.kind, "PPPoE is a point-to-point link too")
        val virtual = classify(OsRoute("192.0.2.15", RouteInterface("vEthernet (Default Switch)", medium = RouteMedium.Virtual), null, null), VpnInventory(snap()))
        assertEquals(RouteKind.Direct, virtual.kind)
    }

    @Test fun dualStackUsesTheFirstAddressJavaReturns() {
        val o = classify(OsRoute("2001:db8::15", RouteInterface("eth0", medium = RouteMedium.Ethernet), "2001:db8::2", null), VpnInventory(snap()),
            addresses = listOf(ip("2001:db8::15"), ip("192.0.2.15")))
        assertEquals("2001:db8:0:0:0:0:0:15", o.address); assertEquals(IpFamily.V6, o.family); assertEquals(2, o.addressCount)
        assertNull(o.gateway)
    }

    @Test fun dnsFailureLoopbackAndProxyAreTheirOwnAnswers() {
        val dns = classifyConnectionRoute(target, at, null, Result.failure(java.net.UnknownHostException("pve.example.test")), null, VpnInventory(snap()))
        assertEquals(RouteKind.Unknown, dns.kind); assertEquals("The server name does not resolve on this computer.", dns.reason)
        val local = classifyConnectionRoute(target, at, null, Result.success(listOf(ip("127.0.0.1"))), null, VpnInventory.Unavailable)
        assertEquals(RouteKind.Local, local.kind)
        val proxy = classifyConnectionRoute(target, at, "HTTP proxy proxy.example.test:3128", null, null, VpnInventory.Unavailable)
        assertEquals(RouteKind.Proxy, proxy.kind); assertNull(proxy.interfaceName)
    }

    @Test fun tailscalePeerPathOnlyForThePeersOwnAddress() {
        val direct = tsStatus("100.101.1.2", peer("pve", "100.70.1.5", direct = "198.51.100.4:41641", relay = "fra"))
        assertEquals(PeerPath.Direct("198.51.100.4:41641"), tailscalePeerPath(direct, "100.70.1.5"))
        val relayed = tsStatus("100.101.1.2", peer("pve", "100.70.1.5", relay = "fra"))
        assertEquals(PeerPath.Relayed("fra"), tailscalePeerPath(relayed, "100.70.1.5"))
        val idle = tsStatus("100.101.1.2", peer("pve", "100.70.1.5", relay = "fra", active = false))
        assertEquals(PeerPath.NotReported, tailscalePeerPath(idle, "100.70.1.5"), "the home region is not a path while idle")
        val exit = tsStatus("100.101.1.2", peer("gw", "100.70.9.9", direct = "198.51.100.9:41641", exit = true))
        assertEquals(PeerPath.NotPeer, tailscalePeerPath(exit, "192.168.50.10"), "the exit node's path is not attached to a subnet address")
        val o = classify(OsRoute("192.168.50.10", RouteInterface("tailscale0", medium = RouteMedium.Tunnel), "100.101.1.2", null),
            VpnInventory(snap(tailscaleUp, tailscale = exit)), "192.168.50.10")
        assertEquals(VpnKind.Tailscale, o.vpnKind); assertEquals(PeerPath.NotPeer, o.peerPath)
    }

    @Test fun targetsTakeOnlyTheOriginsHostAndPort() {
        val v6 = routeTarget("https://[fd00::5]:8006")!!
        assertEquals("fd00::5", v6.host); assertEquals(8006, v6.port); assertEquals("[fd00::5]:8006", v6.hostPort)
        assertEquals(443, routeTarget("https://pve.example.test")!!.port)
        assertNull(routeTarget("http://pve.example.test:8006"))
        assertNull(routeTarget("https://user:secret@pve.example.test:8006"))
        assertNull(routeTarget("https://pve.example.test:8006/api2/json?token=x"))
    }

    @Test fun addressesCompareByValueAndNamesNeverMatch() {
        assertTrue(sameAddress("fd7a:115c:a1e0::1", "FD7A:115C:A1E0:0:0:0:0:1"))
        assertTrue(sameAddress("10.0.0.1/24", "10.0.0.1"))
        assertTrue(sameAddress("fe80::1%12", "fe80::1"))
        assertFalse(sameAddress("pve.example.test", "pve.example.test"))
        assertFalse(sameAddress("10.0.0.1", "10.0.0.2"))
        assertNull(ipLiteral("deadbeef.example"))
    }

    @Test fun windowsRouteDetailsAreParsed() {
        val json = """{"alias":"Ethernet 2","index":12,"source":"192.168.1.20","nextHop":"192.168.1.1","description":"Intel(R) Ethernet Controller","ifType":6,"medium":14,"hardware":true,"virtual":false}"""
        val r = parseWindowsRouteDetail("192.0.2.15", json)
        assertEquals("Ethernet 2", r.iface?.name); assertEquals(12, r.iface?.index); assertEquals(RouteMedium.Ethernet, r.iface?.medium)
        assertEquals("192.168.1.20", r.source); assertEquals("192.168.1.1", r.gateway); assertNull(r.iface?.driverKind)
        val onLink = parseWindowsRouteDetail("192.0.2.15", """{"alias":"WireGuard home","index":30,"source":"10.8.0.2","nextHop":"0.0.0.0","description":"WireGuard Tunnel","ifType":53,"medium":0,"hardware":false,"virtual":true}""")
        assertNull(onLink.gateway); assertEquals(RouteMedium.Tunnel, onLink.iface?.medium); assertEquals(VpnKind.WireGuard, onLink.iface?.driverKind)
        val ras = parseWindowsRouteDetail("192.0.2.15", """{"alias":"Office","index":40,"source":"10.20.0.5","nextHop":"::","description":"Office","ifType":23,"medium":null,"hardware":null,"virtual":null}""")
        assertEquals(RouteMedium.PointToPoint, ras.iface?.medium)
        val bare = parseWindowsRouteDetail("192.0.2.15", """{"alias":null,"index":null,"source":null,"nextHop":null,"description":null,"ifType":null,"medium":null,"hardware":null,"virtual":null}""")
        assertNull(bare.iface)
    }

    @Test fun windowsMediumComesOnlyFromInterfaceFacts() {
        assertEquals(RouteMedium.WiFi, windowsMedium(71, 9, true, false))
        assertEquals(RouteMedium.Ethernet, windowsMedium(6, 0, true, false), "USB adapters report no NDIS medium")
        assertEquals(RouteMedium.Virtual, windowsMedium(6, 0, false, true))
        assertEquals(RouteMedium.Tunnel, windowsMedium(131, 0, false, true))
        assertEquals(RouteMedium.Mobile, windowsMedium(243, null, null, null))
        assertEquals(RouteMedium.Loopback, windowsMedium(24, null, null, null))
        assertNull(windowsMedium(6, null, null, null), "ethernet type without the hardware flag is not claimed")
        assertNull(windowsMedium(null, null, null, null))
        assertEquals(VpnKind.Tailscale, windowsDriverKind("Tailscale Tunnel"))
        assertEquals(VpnKind.OpenVpn, windowsDriverKind("TAP-Windows Adapter V9"))
        assertNull(windowsDriverKind("Realtek PCIe GbE Family Controller"))
    }

    private class FakeSys(val files: Map<String, String>, val entries: Set<String>) : SysNet {
        override fun read(iface: String, file: String) = files["$iface/$file"]
        override fun exists(iface: String, entry: String) = "$iface/$entry" in entries || "$iface/$entry" in files
    }

    @Test fun linuxRouteAndSysfsFacts() {
        val sys = FakeSys(mapOf(
            "eth0/type" to "1", "eth0/uevent" to "INTERFACE=eth0\nIFINDEX=2",
            "wlp3s0/type" to "1", "wlp3s0/uevent" to "DEVTYPE=wlan\nINTERFACE=wlp3s0",
            "wg0/type" to "65534", "wg0/uevent" to "DEVTYPE=wireguard",
            "tailscale0/type" to "65534", "tailscale0/tun_flags" to "0x1001",
            "br0/type" to "1", "br0/uevent" to "DEVTYPE=bridge",
            "ppp0/type" to "512", "lo/type" to "772",
        ), setOf("eth0/device", "wlp3s0/device", "wlp3s0/phy80211"))
        val r = parseLinuxRouteGet("192.0.2.15", """[{"dst":"192.0.2.15","gateway":"192.168.1.1","dev":"eth0","prefsrc":"192.168.1.20","flags":[],"uid":1000,"cache":[]}]""", sys)
        assertEquals(RouteMedium.Ethernet, r.iface?.medium); assertEquals("192.168.1.20", r.source); assertEquals("192.168.1.1", r.gateway)
        assertEquals(RouteMedium.WiFi, linuxInterface("wlp3s0", sys).medium)
        assertEquals(VpnKind.WireGuard, linuxInterface("wg0", sys).driverKind)
        assertEquals(RouteMedium.Tunnel, linuxInterface("tailscale0", sys).medium)
        assertEquals(RouteMedium.Virtual, linuxInterface("br0", sys).medium)
        assertEquals(RouteMedium.PointToPoint, linuxInterface("ppp0", sys).medium)
        assertEquals(RouteMedium.Loopback, linuxInterface("lo", sys).medium)
        assertNull(linuxInterface("enp9s0", sys).medium, "no sysfs facts, no medium")
        assertFailsWith<IllegalStateException> { parseLinuxRouteGet("192.0.2.15", """[{"type":"unreachable","dst":"192.0.2.15"}]""", sys) }
        assertNull(parseLinuxRouteGet("192.0.2.15", """[{"dst":"192.0.2.15","dev":"../../etc"}]""", sys).iface, "an unsafe name never reaches sysfs")
    }

    @Test fun javaProxySettingsAreHonoured() {
        fun selector(vararg proxies: Proxy) = object : ProxySelector() {
            override fun select(uri: URI?) = proxies.toList()
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
        }
        val uri = URI(target.origin)
        assertEquals("HTTP proxy proxy.example.test:3128", javaProxyFor(uri, selector(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example.test", 3128)))))
        assertEquals("SOCKS proxy socks.example.test:1080", javaProxyFor(uri, selector(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("socks.example.test", 1080)))))
        assertNull(javaProxyFor(uri, selector(Proxy.NO_PROXY)))
        assertNull(javaProxyFor(uri, null))
        assertFailsWith<IllegalStateException> { javaProxyFor(uri, object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = throw IllegalStateException("broken")
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
        }) }
    }

    @Test fun localProbeRunsOneReadOnlyCommandPerPlatform() {
        val commands = mutableListOf<List<String>>()
        val linux = LocalRouteProbe("Linux", run = { c, _ -> commands += c; """[{"dst":"192.0.2.15","dev":"eth0"}]""" }, which = { "/usr/sbin/$it" }, sys = FakeSys(emptyMap(), emptySet()))
        assertEquals("eth0", linux.route("192.0.2.15").iface?.name)
        assertEquals(listOf("/usr/sbin/ip", "-j", "route", "get", "192.0.2.15"), commands.single())
        commands.clear()
        val windows = LocalRouteProbe("Windows 11", run = { c, _ -> commands += c; """{"alias":"Wi-Fi","index":5,"source":"192.168.1.20","nextHop":"192.168.1.1","description":"Intel Wi-Fi","ifType":71,"medium":9,"hardware":true,"virtual":false}""" })
        assertEquals(RouteMedium.WiFi, windows.route("2001:db8::15").iface?.medium)
        val script = String(Base64.getDecoder().decode(commands.single().last()), Charsets.UTF_16LE)
        assertTrue("Find-NetRoute -RemoteIPAddress '2001:db8::15'" in script)
        assertFalse(Regex("Set-|New-|Remove-|Enable-|Disable-|Start-").containsMatchIn(script), "read-only cmdlets only")
        commands.clear()
        assertFailsWith<IllegalArgumentException> { linux.route("pve.example.test; reboot") }
        assertTrue(commands.isEmpty(), "a name is never passed to a command")
    }

    @Test fun backendRouteUsesTheSameEvidence() {
        val routes = object : RouteProbe { override fun route(address: String) = OsRoute(address, ethernet, "192.168.1.20", "192.168.1.1") }
        val backend = LocalVpnBackend("Linux", run = { _, _ -> error("no command") }, which = { null }, routes = routes)
        val route = backend.routeTo("100.100.5.5", snap(tailscaleUp))
        assertNull(route.tunnelId, "a Tailscale-range server on an ordinary route is not Tailscale")
        assertEquals("eth0", route.interfaceName); assertEquals("192.168.1.20", route.source)
        val failing = LocalVpnBackend("Linux", run = { _, _ -> error("no command") }, which = { null }, routes = object : RouteProbe { override fun route(address: String): OsRoute = error("boom") })
        val none = failing.routeTo("100.100.5.5", snap(tailscaleUp))
        assertNull(none.interfaceName); assertNull(none.tunnelId, "a failed route read never names a tunnel")
    }
}
