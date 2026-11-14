package local.ringleader.desktop

import java.time.Instant
import local.ringleader.core.*

/** Invented route states for the tag's tests and pictures. Documentation addresses (RFC 5737/3849) and example.test names only. */
internal object RouteTagSamples {
    val at: Instant = Instant.parse("2026-11-12T10:00:00Z")
    val now: Instant = at.plusSeconds(12)
    val target = routeTarget("https://pve-west-cluster.example.test:8006")!!
    val longTarget = routeTarget("https://storage-node-with-a-rather-long-host-name.example.test:8006")!!
    val key = RouteKey(1, 1, target.origin)
    private fun base(t: RouteTarget = target) = RouteObservation(t, at, RouteKind.Unknown, address = "192.0.2.15", family = IpFamily.V4, addressCount = 1,
        vpnComplete = true)

    val direct = RouteTagState.Observed(key, base().copy(kind = RouteKind.Direct, interfaceName = "Ethernet 2", medium = RouteMedium.Ethernet,
        source = "192.168.1.20", gateway = "192.168.1.1", addressCount = 2))
    val wifi = RouteTagState.Observed(key, base().copy(kind = RouteKind.Direct, interfaceName = "Wi-Fi", medium = RouteMedium.WiFi, gateway = "192.168.1.1"))
    val wireguard = RouteTagState.Observed(key, base().copy(kind = RouteKind.Vpn, vpnKind = VpnKind.WireGuard, tunnelName = "home-office", interfaceName = "wg-home",
        medium = RouteMedium.Tunnel, source = "10.8.0.2", evidence = RouteEvidence.InterfaceName))
    val tailscale = RouteTagState.Observed(key, base().copy(kind = RouteKind.Vpn, vpnKind = VpnKind.Tailscale, tunnelName = "Tailscale", interfaceName = "tailscale0",
        medium = RouteMedium.Tunnel, address = "100.70.1.5", source = "100.101.1.2", evidence = RouteEvidence.SourceAddress, peerPath = PeerPath.Direct("198.51.100.4:41641")))
    val tailscaleRelayed = RouteTagState.Observed(key, tailscale.observation.copy(peerPath = PeerPath.Relayed("fra")))
    val unknown = RouteTagState.Observed(key, base().copy(interfaceName = "eth9", source = "192.168.1.20", vpnComplete = false,
        vpnGaps = listOf("Tailscale is installed but its status could not be read."),
        reason = "The interface type was not reported and the VPN check was incomplete, so a VPN cannot be ruled out."))
    val failed = RouteTagState.Observed(key, base().copy(vpnComplete = false, vpnGaps = listOf("Not checked, because the route read failed."),
        reason = "The route check failed: no route to 192.0.2.15 via pve-west-cluster.example.test"))
    val proxy = RouteTagState.Observed(key, RouteObservation(target, at, RouteKind.Proxy, proxy = "HTTP proxy proxy.example.test:3128",
        reason = "Java's proxy settings send this connection to the proxy. The path beyond the proxy is not visible."))
    val checking = RouteTagState.Checking(key, target)
    val refreshing = direct.copy(refreshing = true)
    val longNames = RouteTagState.Observed(RouteKey(3, 1, longTarget.origin), base(longTarget).copy(kind = RouteKind.Vpn, vpnKind = VpnKind.OpenVpn,
        tunnelName = "corporate-datacenter-west-failover-tunnel", interfaceName = "OpenVPN Data Channel Offload for corporate-west", medium = RouteMedium.Tunnel,
        evidence = RouteEvidence.InterfaceName))

    /** Every identifying string the samples carry; none may appear in Showcase. */
    val identities = listOf("pve-west-cluster", "example.test", "192.0.2.15", "192.168.1.20", "192.168.1.1", "Ethernet 2", "wg-home", "home-office",
        "10.8.0.2", "100.70.1.5", "100.101.1.2", "198.51.100.4", "proxy.example", "tailscale0", "eth9", "corporate-datacenter", "storage-node")

    val all = listOf("direct" to direct, "wifi" to wifi, "wireguard" to wireguard, "tailscale" to tailscale, "tailscale-relayed" to tailscaleRelayed,
        "unknown" to unknown, "failed" to failed, "proxy" to proxy, "checking" to checking, "refreshing" to refreshing, "long-names" to longNames,
        "demo" to RouteTagState.Demo, "browser" to RouteTagState.Browser)
}
