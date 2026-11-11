package local.ringleader.core

import java.nio.file.Path
import java.time.Instant

/**
 * VPN model for the computer RingMASTER runs on: Tailscale, WireGuard tunnels and the operating system's own VPN
 * profiles (Windows RAS / NetworkManager), in one shape. Read paths never change anything; [VpnBackend] mutations are
 * only called after the user confirms in the UI.
 */
enum class VpnKind { Tailscale, WireGuard, OpenVpn, IpSec, Other }
enum class VpnState { Up, Down, Connecting, NeedsLogin, Unknown }

/** One tunnel on this computer. */
data class VpnTunnel(
    /** Stable id: "tailscale", "wg:<name>", "nm:<uuid>", "ras:<name>", "nic:<interface>". */
    val id: String,
    val kind: VpnKind,
    val name: String,
    val state: VpnState,
    val interfaceName: String? = null,
    /** Addresses on the tunnel interface, plain or CIDR. */
    val addresses: List<String> = emptyList(),
    /** One plain line: "Exit node home-gw", "Endpoint 203.0.113.4:51820", "Server vpn.example.com". */
    val detail: String? = null,
    /** A backend exists to bring this tunnel up or down from the app. */
    val canToggle: Boolean = false,
    /** Toggling asks the operating system for administrator rights (UAC on Windows, polkit or pkexec on Linux). */
    val toggleNeedsAdmin: Boolean = false,
    /** Can be removed from the app (imported WireGuard tunnels). */
    val canRemove: Boolean = false,
    val rxBytes: Long? = null,
    val txBytes: Long? = null,
    val lastHandshakeEpoch: Long? = null,
)

data class TailscalePeer(
    val id: String,
    val hostName: String,
    /** MagicDNS name without the trailing dot: "pve1.example-tailnet.ts.net". */
    val dnsName: String?,
    val os: String?,
    val online: Boolean,
    /** Tailscale IPs, IPv4 first. */
    val addresses: List<String>,
    val exitNodeOption: Boolean,
    /** This peer is the exit node in use. */
    val exitNode: Boolean,
    /** DERP region code when relayed ("dfw"), else null. */
    val relay: String?,
    /** ip:port when the connection is direct, else null. */
    val direct: String?,
    val lastSeen: Instant?,
    val rxBytes: Long?,
    val txBytes: Long?,
    val tags: List<String> = emptyList(),
)

data class TailscaleStatus(
    /** Running, Stopped, NeedsLogin, NeedsMachineAuth, Starting, NoState. */
    val backendState: String,
    val version: String?,
    /** Tailnet display name from CurrentTailnet.Name. */
    val tailnet: String?,
    val magicDnsSuffix: String?,
    val self: TailscalePeer?,
    val peers: List<TailscalePeer>,
    /** Present when the client needs a browser login. */
    val authUrl: String?,
    /** Health warnings Tailscale reports, verbatim. */
    val health: List<String>,
) {
    val running get() = backendState == "Running"
    val exitNode get() = peers.firstOrNull { it.exitNode }
}

data class WireGuardPeer(
    val publicKey: String,
    val endpoint: String?,
    val allowedIps: List<String>,
    val lastHandshakeEpoch: Long?,
    val rxBytes: Long,
    val txBytes: Long,
    val keepaliveSeconds: Int?,
)

data class WireGuardInterface(val name: String, val publicKey: String?, val listenPort: Int?, val peers: List<WireGuardPeer>)

data class VpnSnapshot(
    val at: Instant,
    val tunnels: List<VpnTunnel>,
    val tailscaleInstalled: Boolean,
    /** Null when Tailscale is not installed or its status could not be read (see [notes]). */
    val tailscale: TailscaleStatus?,
    val wireguardInstalled: Boolean,
    /** Live WireGuard details where readable; usually needs administrator or root rights. */
    val wireguard: List<WireGuardInterface>,
    /** Plain-language notes: "WireGuard details need administrator rights." */
    val notes: List<String>,
)

/** Which local interface carries traffic to [host], and whether that interface belongs to a tunnel. */
data class VpnRoute(
    val host: String,
    /** The resolved address, or null when the name did not resolve. */
    val address: String?,
    val interfaceName: String?,
    /** The [VpnTunnel.id] the route goes through, or null for an ordinary interface. */
    val tunnelId: String?,
    val tunnelName: String?,
)

/** A Proxmox-family service answering on a tailnet peer. */
data class TailnetService(val peer: TailscalePeer, val hit: ScanHit)

/** Platform backend. All calls block; run them off the UI thread. Mutations throw with a plain message on failure. */
interface VpnBackend {
    val platform: String
    fun snapshot(): VpnSnapshot
    fun routeTo(host: String, snapshot: VpnSnapshot? = null): VpnRoute
    fun setTunnel(tunnel: VpnTunnel, up: Boolean)
    /** Tailscale exit node by peer IP or name; null stops using one. */
    fun setExitNode(peer: TailscalePeer?)
    /** Imports a WireGuard .conf as tunnel [name] (letters, digits, "-", "_", at most 15 characters) and returns its id. */
    fun importWireGuard(config: Path, name: String): String
    fun removeWireGuard(tunnel: VpnTunnel)
}

/** WireGuard interface names: 1-15 characters of letters, digits, "-" and "_" (the Linux limit, also safe on Windows). */
fun validWireGuardName(name: String) = name.matches(Regex("[A-Za-z0-9_-]{1,15}"))
