package local.ringleader.core

import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.*

/*
 * Route evidence for the connection tag: which way this computer sends traffic to the server in view. Everything here
 * is a read of the operating system's own route choice and interface facts; nothing sends packets, scans, or changes
 * anything. Only positive operating-system route evidence establishes a tunnel.
 */

/** The server a connection talks to. Only the origin's host and port; never a login, path or secret. */
data class RouteTarget(val origin: String, val host: String, val port: Int) {
    val hostPort get() = if (':' in host) "[$host]:$port" else "$host:$port"
}

/** The target for a workspace address, or null when it is not a valid HTTPS origin. */
fun routeTarget(address: String): RouteTarget? = runCatching {
    val origin = serverOrigin(address)
    val host = origin.host.removePrefix("[").removeSuffix("]")
    RouteTarget(origin.toString(), host, if (origin.port == -1) 443 else origin.port)
}.getOrNull()

/** What kind of link an interface is, only when the operating system reports it (never guessed from a name). */
enum class RouteMedium(val label: String) {
    Ethernet("Ethernet"), WiFi("Wi-Fi"), Mobile("Mobile broadband"), Virtual("Virtual adapter"),
    Tunnel("Tunnel adapter"), PointToPoint("Point-to-point link"), Loopback("Loopback"),
}

/**
 * The interface the operating system picked. [driverKind] is a tunnel family named by the interface's own driver
 * (Windows adapter description, Linux device type), not by its user-editable name.
 */
data class RouteInterface(
    val name: String,
    val index: Int? = null,
    val description: String? = null,
    val medium: RouteMedium? = null,
    val driverKind: VpnKind? = null,
)

/** One OS route answer for [address]: the interface, the local source address and the next hop (null when on-link). */
data class OsRoute(val address: String, val iface: RouteInterface?, val source: String?, val gateway: String?)

/** Reads the operating system's route for one address. Blocks; throws with a plain message when the read fails. */
interface RouteProbe {
    fun route(address: String): OsRoute
}

enum class RouteKind { Direct, Vpn, Proxy, Local, Unknown }

enum class IpFamily(val label: String) { V4("IPv4"), V6("IPv6") }

/** How a matched tunnel was tied to the route. */
enum class RouteEvidence(val label: String) {
    SourceAddress("the route's source address is on the tunnel"),
    InterfaceName("the route's interface is the tunnel's interface"),
    DialUpName("the route's interface is the VPN connection"),
    Driver("the interface driver is a tunnel driver"),
    TunnelAdapter("the interface is a tunnel adapter"),
}

/** Tailscale's own path to the peer that owns the server address. Only set when that address is a peer's address. */
sealed interface PeerPath {
    /** Tailscale reaches the peer straight over UDP. Still encrypted inside Tailscale. */
    data class Direct(val endpoint: String) : PeerPath
    /** Traffic is relayed through a Tailscale DERP server. */
    data class Relayed(val region: String) : PeerPath
    /** The peer is known but Tailscale reports no current path (idle or offline). */
    data object NotReported : PeerPath
    /** The address is not a tailnet peer's own address: a subnet route or an exit node carries it. */
    data object NotPeer : PeerPath
}

/** What the VPN inspection could see when the route was classified. */
data class VpnInventory(val snapshot: VpnSnapshot?, val problem: String? = null) {
    /** False when the tunnel list itself may be missing entries (a failed read, or no VPN inspection at all). */
    val complete get() = snapshot != null && problem == null && vpnListGaps(snapshot).isEmpty()
    val gaps get() = listOfNotNull(problem) + (snapshot?.let(::vpnListGaps) ?: if (problem == null) listOf("No VPN inspection is available.") else emptyList())
    companion object { val Unavailable = VpnInventory(null, "No VPN inspection is available on this computer.") }
}

/** Snapshot notes that mean a tunnel could be missing from the list. Missing WireGuard peer details do not. */
fun vpnListGaps(snapshot: VpnSnapshot): List<String> = snapshot.notes.filterNot { it.startsWith(WIREGUARD_PEER_NOTE) }

/** One classified observation. Identity fields (host, addresses, names) are shown only outside Showcase. */
data class RouteObservation(
    val target: RouteTarget,
    val at: Instant,
    val kind: RouteKind,
    /** The address the route was read for: the first one Java resolves, which its HTTPS connection uses. */
    val address: String? = null,
    val family: IpFamily? = null,
    /** How many addresses DNS returned in all. */
    val addressCount: Int = 0,
    val interfaceName: String? = null,
    val medium: RouteMedium? = null,
    val source: String? = null,
    val gateway: String? = null,
    val vpnKind: VpnKind? = null,
    val tunnelName: String? = null,
    val evidence: RouteEvidence? = null,
    val peerPath: PeerPath? = null,
    /** "HTTP proxy.example:3128" when Java's proxy settings send the connection through a proxy. */
    val proxy: String? = null,
    val vpnComplete: Boolean = false,
    val vpnGaps: List<String> = emptyList(),
    /** Why the kind is Unknown, or what failed. Plain language, already made safe to show. */
    val reason: String? = null,
)

/** Exact address comparison for IP literals; names never match (no DNS from here). */
fun sameAddress(a: String?, b: String?): Boolean {
    val x = ipLiteral(a ?: return false) ?: return false
    val y = ipLiteral(b ?: return false) ?: return false
    return x.address.contentEquals(y.address)
}

/** Parses an IPv4 or IPv6 literal without any name lookup. A CIDR suffix or IPv6 zone is dropped. */
internal fun ipLiteral(value: String): InetAddress? {
    val text = value.trim().substringBefore('/').substringBefore('%').removePrefix("[").removeSuffix("]")
    if (text.isEmpty()) return null
    val looksV4 = ScanRules.ipv4(text) != null
    val looksV6 = ':' in text && text.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }
    if (!looksV4 && !looksV6) return null
    return runCatching { InetAddress.getByName(text) }.getOrNull()
}

/**
 * The tunnel a route goes through, with the evidence. Only route facts count: the route's source address on a tunnel's
 * own addresses (or Tailscale's own addresses), the route's interface being the tunnel's interface, or on Windows the
 * interface being the dial-up VPN connection of that name. An address inside a VPN's range is not evidence on its own.
 */
fun matchRouteTunnelEvidence(alias: String?, source: String?, tunnels: List<VpnTunnel>, tailscaleSelf: List<String> = emptyList()): Pair<VpnTunnel, RouteEvidence>? {
    val up = tunnels.filter { it.state == VpnState.Up }
    if (source != null) up.firstOrNull { t ->
        t.addresses.any { sameAddress(it, source) } || (t.kind == VpnKind.Tailscale && tailscaleSelf.any { sameAddress(it, source) })
    }?.let { return it to RouteEvidence.SourceAddress }
    if (alias.isNullOrBlank()) return null
    // Interface names are compared exactly: Linux names are case-sensitive, and Windows reports its alias verbatim.
    (up.firstOrNull { it.interfaceName == alias } ?: tunnels.firstOrNull { it.interfaceName == alias && it.state != VpnState.Down })
        ?.let { return it to RouteEvidence.InterfaceName }
    up.firstOrNull { it.id.startsWith("ras:") && it.name == alias }?.let { return it to RouteEvidence.DialUpName }
    return null
}

/** The first non-direct proxy Java would use for [uri], as "HTTP host:port", or null when it connects directly. */
fun javaProxyFor(uri: URI, selector: ProxySelector? = ProxySelector.getDefault()): String? {
    val list = selector?.select(uri).orEmpty()
    val proxy = list.firstOrNull() ?: return null
    if (proxy.type() == Proxy.Type.DIRECT) return null
    val address = proxy.address() as? java.net.InetSocketAddress
    val where = address?.let { "${it.hostString}:${it.port}" } ?: "an unnamed address"
    return "${if (proxy.type() == Proxy.Type.SOCKS) "SOCKS" else "HTTP"} proxy $where"
}

/**
 * Classifies one observation from its parts. [addresses] null means DNS was not asked (a proxy was found first);
 * [route] null means the route was not read.
 */
fun classifyConnectionRoute(
    target: RouteTarget,
    at: Instant,
    proxy: String?,
    addresses: Result<List<InetAddress>>?,
    route: Result<OsRoute>?,
    vpn: VpnInventory,
): RouteObservation {
    val base = RouteObservation(target, at, RouteKind.Unknown, vpnComplete = vpn.complete, vpnGaps = vpn.gaps)
    if (proxy != null) return base.copy(kind = RouteKind.Proxy, proxy = proxy,
        reason = "Java's proxy settings send this connection to the proxy. The path beyond the proxy is not visible.")
    val list = addresses?.getOrElse { return base.copy(reason = "The server name does not resolve on this computer.") }
        ?: return base.copy(reason = "The address was not looked up.")
    val first = list.firstOrNull() ?: return base.copy(reason = "The server name does not resolve on this computer.")
    val address = first.hostAddress.substringBefore('%')
    val family = if (first is Inet6Address) IpFamily.V6 else IpFamily.V4
    val located = base.copy(address = address, family = family, addressCount = list.size)
    if (first.isLoopbackAddress) return located.copy(kind = RouteKind.Local, medium = RouteMedium.Loopback,
        reason = "The server address is this computer. A local forwarder (an SSH tunnel or a proxy) may carry it further; that is not visible.")
    val os = route?.getOrElse { return located.copy(reason = routeFailure(it)) } ?: return located.copy(reason = "The route was not read.")
    val iface = os.iface ?: return located.copy(source = os.source, gateway = os.gateway, reason = "The operating system did not name an interface for this route.")
    val seen = located.copy(interfaceName = iface.name, medium = iface.medium, source = os.source, gateway = os.gateway)
    if (iface.medium == RouteMedium.Loopback) return seen.copy(kind = RouteKind.Local,
        reason = "The route stays on this computer. A local forwarder may carry it further; that is not visible.")
    val snapshot = vpn.snapshot
    val match = matchRouteTunnelEvidence(iface.name, os.source, snapshot?.tunnels.orEmpty(), snapshot?.tailscale?.self?.addresses.orEmpty())
    val physical = iface.medium == RouteMedium.Ethernet || iface.medium == RouteMedium.WiFi || iface.medium == RouteMedium.Mobile
    fun vpnResult(kind: VpnKind, name: String?, evidence: RouteEvidence) = seen.copy(kind = RouteKind.Vpn, vpnKind = kind, tunnelName = name, evidence = evidence,
        peerPath = if (kind == VpnKind.Tailscale) tailscalePeerPath(snapshot?.tailscale, address) else null)
    return when {
        // A VPN that shares a physical interface (policy-based IPsec on the parent device) cannot be told apart from an
        // ordinary route to an address outside its networks, so neither answer is claimed.
        match != null && physical -> seen.copy(tunnelName = match.first.name, vpnKind = match.first.kind,
            reason = "The route uses an ordinary ${iface.medium!!.label} interface that an active VPN also uses. A tunnel without its own interface cannot be told apart from an ordinary route.")
        match != null -> vpnResult(match.first.kind, match.first.name, match.second)
        iface.driverKind != null -> vpnResult(iface.driverKind, null, RouteEvidence.Driver)
        iface.medium == RouteMedium.Tunnel -> vpnResult(VpnKind.Other, null, RouteEvidence.TunnelAdapter)
        (physical || iface.medium == RouteMedium.Virtual) && !vpn.complete -> seen.copy(
            reason = "The VPN check was incomplete, so this interface cannot be confirmed as a direct connection.")
        physical || iface.medium == RouteMedium.Virtual -> seen.copy(kind = RouteKind.Direct)
        iface.medium == RouteMedium.PointToPoint -> seen.copy(reason = "The route uses a point-to-point link (dial-up, PPPoE or a VPN) that no VPN in the list matches.")
        vpn.complete -> seen.copy(kind = RouteKind.Direct)
        else -> seen.copy(reason = "The interface type was not reported and the VPN check was incomplete, so a VPN cannot be ruled out.")
    }
}

/**
 * Tailscale's path to the peer whose own Tailscale address is [address]. A subnet route or an exit node is not a peer
 * address, so no peer's path is attached to it.
 */
fun tailscalePeerPath(status: TailscaleStatus?, address: String): PeerPath? {
    status ?: return null
    val peer = status.peers.firstOrNull { p -> p.addresses.any { sameAddress(it, address) } } ?: return PeerPath.NotPeer
    val direct = peer.direct?.takeIf { it.isNotBlank() }
    val relay = peer.relay?.takeIf { it.isNotBlank() }
    return when {
        direct != null -> PeerPath.Direct(direct)
        // Relay is the peer's home DERP region and is reported even when idle; it is the path only while active.
        relay != null && peer.active == true && peer.online -> PeerPath.Relayed(relay)
        else -> PeerPath.NotReported
    }
}

private fun routeFailure(error: Throwable): String {
    val text = vpnFailureMessage(error.message.orEmpty())
    return when {
        Regex("unreachable|no route", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "The operating system has no route to this address."
        text == "VPN command failed." || text.isBlank() -> "The route check failed."
        else -> "The route check failed: $text"
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Operating-system probes. One short read per call: Find-NetRoute plus the chosen adapter's facts on Windows,
// `ip -j route get` plus /sys/class/net on Linux. No administrator rights; no packets are sent.
// ---------------------------------------------------------------------------------------------------------------

/** PowerShell for one route read. The adapter facts come from the interface the route names, by index. */
internal fun windowsRouteDetailScript(address: String) = psUtf8Prefix() + """
${'$'}found = @(Find-NetRoute -RemoteIPAddress ${psQuote(address)} -ErrorAction Stop)
${'$'}ip = ${'$'}found | Where-Object { ${'$'}_.CimClass.CimClassName -eq 'MSFT_NetIPAddress' } | Select-Object -First 1
${'$'}route = ${'$'}found | Where-Object { ${'$'}_.CimClass.CimClassName -eq 'MSFT_NetRoute' } | Select-Object -First 1
${'$'}index = if (${'$'}route) { ${'$'}route.InterfaceIndex } elseif (${'$'}ip) { ${'$'}ip.InterfaceIndex } else { ${'$'}null }
${'$'}adapter = ${'$'}null; ${'$'}net = ${'$'}null
if (${'$'}index -ne ${'$'}null) {
  ${'$'}adapter = Get-NetAdapter -InterfaceIndex ${'$'}index -IncludeHidden -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not ${'$'}adapter) {
    foreach (${'$'}n in [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
      ${'$'}i = ${'$'}null
      try { ${'$'}i = ${'$'}n.GetIPProperties().GetIPv4Properties().Index } catch {}
      if (${'$'}i -eq ${'$'}null) { try { ${'$'}i = ${'$'}n.GetIPProperties().GetIPv6Properties().Index } catch {} }
      if (${'$'}i -eq ${'$'}index) { ${'$'}net = ${'$'}n; break }
    }
  }
}
${'$'}out = [ordered]@{ alias = ${'$'}null; index = ${'$'}index; source = ${'$'}null; nextHop = ${'$'}null; description = ${'$'}null; ifType = ${'$'}null; medium = ${'$'}null; hardware = ${'$'}null; virtual = ${'$'}null }
if (${'$'}route) { ${'$'}out.alias = ${'$'}route.InterfaceAlias; ${'$'}out.nextHop = ${'$'}route.NextHop } elseif (${'$'}ip) { ${'$'}out.alias = ${'$'}ip.InterfaceAlias }
if (${'$'}ip) { ${'$'}out.source = ${'$'}ip.IPAddress }
if (${'$'}adapter) { ${'$'}out.description = ${'$'}adapter.InterfaceDescription; ${'$'}out.ifType = [int]${'$'}adapter.InterfaceType; ${'$'}out.medium = [int]${'$'}adapter.NdisPhysicalMedium; ${'$'}out.hardware = [bool]${'$'}adapter.HardwareInterface; ${'$'}out.virtual = [bool]${'$'}adapter.Virtual }
elseif (${'$'}net) { ${'$'}out.description = ${'$'}net.Description; ${'$'}out.ifType = [int]${'$'}net.NetworkInterfaceType }
${'$'}out | ConvertTo-Json -Compress
""".trimIndent()

/** Parses [windowsRouteDetailScript]'s output. */
fun parseWindowsRouteDetail(address: String, json: String): OsRoute {
    val o = Json.parseToJsonElement(json.trim()).jsonObject
    val alias = o.vs("alias")
    val description = o.vs("description")
    val ifType = o.vs("ifType")?.toIntOrNull()
    val medium = windowsMedium(ifType, o.vs("medium")?.toIntOrNull(), o.vs("hardware")?.toBooleanStrictOrNull(), o.vs("virtual")?.toBooleanStrictOrNull())
    val gateway = o.vs("nextHop")?.takeUnless { it == "0.0.0.0" || it == "::" }
    return OsRoute(address, alias?.let { RouteInterface(it, o.vs("index")?.toIntOrNull(), description, medium, windowsDriverKind(description)) }, o.vs("source"), gateway)
}

/**
 * Medium from Windows interface facts: IANA ifType ([ifType]), NDIS physical medium ([ndis]) and the hardware flag.
 * 6 ethernetCsmacd, 71 ieee80211, 23 ppp, 24 loopback, 53 propVirtual (Wintun and other layer-3 drivers),
 * 131 tunnel, 243/244 mobile broadband. NDIS 9 is native 802.11, 8 wireless WAN, 14 is 802.3.
 */
internal fun windowsMedium(ifType: Int?, ndis: Int?, hardware: Boolean?, virtual: Boolean?): RouteMedium? = when {
    ifType == 24 -> RouteMedium.Loopback
    ifType == 71 || ndis == 9 || ndis == 1 -> RouteMedium.WiFi
    ifType == 243 || ifType == 244 || ndis == 8 -> RouteMedium.Mobile
    ifType == 131 || ifType == 53 -> RouteMedium.Tunnel
    ifType == 23 -> RouteMedium.PointToPoint
    ifType == 6 && hardware == true && virtual != true -> RouteMedium.Ethernet
    ifType == 6 && (hardware == false || virtual == true) -> RouteMedium.Virtual
    else -> null
}

/** Tunnel family from a Windows adapter's driver description (set by the driver, not the user). */
internal fun windowsDriverKind(description: String?): VpnKind? {
    val d = description ?: return null
    return when {
        d.startsWith("Tailscale Tunnel", true) -> VpnKind.Tailscale
        d.startsWith("WireGuard Tunnel", true) -> VpnKind.WireGuard
        Regex("TAP-Windows|OpenVPN", RegexOption.IGNORE_CASE).containsMatchIn(d) -> VpnKind.OpenVpn
        Regex("Cisco AnyConnect|Cisco Secure Client|Fortinet|GlobalProtect|PANGP|ZeroTier", RegexOption.IGNORE_CASE).containsMatchIn(d) -> VpnKind.Other
        else -> null
    }
}

/** Read-only view of /sys/class/net for one interface. */
interface SysNet {
    fun read(iface: String, file: String): String?
    fun exists(iface: String, entry: String): Boolean
}

object LinuxSysNet : SysNet {
    private fun dir(iface: String): Path? = if (iface.matches(SAFE_IFACE)) Path.of("/sys/class/net", iface) else null
    override fun read(iface: String, file: String) = dir(iface)?.let { runCatching { Files.readString(it.resolve(file)).trim() }.getOrNull() }
    override fun exists(iface: String, entry: String) = dir(iface)?.let { Files.exists(it.resolve(entry)) } ?: false
}

private val SAFE_IFACE = Regex("[A-Za-z0-9_.:@-]{1,15}")

/** Parses `ip -j route get <address>`. */
fun parseLinuxRouteGet(address: String, json: String, sys: SysNet): OsRoute {
    val row = Json.parseToJsonElement(json.trim()).jsonArray.firstOrNull()?.jsonObject ?: error("No route was reported.")
    if (row.vs("type") in setOf("unreachable", "blackhole", "prohibit")) error("Network is unreachable.")
    val dev = row.vs("dev")?.takeIf { it.matches(SAFE_IFACE) }
    return OsRoute(address, dev?.let { linuxInterface(it, sys) }, row.vs("prefsrc"), row.vs("gateway"))
}

/**
 * Linux interface facts from sysfs: `type` (ARPHRD: 1 ether, 512 ppp, 772 loopback, 65534 none), `uevent` DEVTYPE
 * (wlan, wwan, wireguard, bridge, vlan, bond), `phy80211`/`wireless` for Wi-Fi, `tun_flags` for tun/tap, and the
 * `device` link that only hardware has.
 */
internal fun linuxInterface(name: String, sys: SysNet): RouteInterface {
    val type = sys.read(name, "type")?.toIntOrNull()
    val devType = sys.read(name, "uevent")?.lineSequence()?.firstOrNull { it.startsWith("DEVTYPE=") }?.substringAfter('=')?.trim()
    val tun = sys.exists(name, "tun_flags")
    val medium = when {
        type == 772 -> RouteMedium.Loopback
        devType == "wireguard" -> RouteMedium.Tunnel
        devType == "wlan" || sys.exists(name, "phy80211") || sys.exists(name, "wireless") -> RouteMedium.WiFi
        devType == "wwan" -> RouteMedium.Mobile
        type == 65534 && tun -> RouteMedium.Tunnel
        type == 65534 || type == 768 || type == 776 || type == 778 -> RouteMedium.Tunnel
        type == 512 -> RouteMedium.PointToPoint
        type == 1 && devType == null && !tun && sys.exists(name, "device") -> RouteMedium.Ethernet
        type == 1 -> RouteMedium.Virtual
        else -> null
    }
    return RouteInterface(name, sys.read(name, "ifindex")?.toIntOrNull(), null, medium, if (devType == "wireguard") VpnKind.WireGuard else null)
}

/** The local operating system's route reads. Commands run through [run] with a short timeout. */
class LocalRouteProbe(
    os: String = System.getProperty("os.name"),
    private val run: (List<String>, Long) -> String = ::runVpnCommand,
    private val which: (String) -> String? = ::findExecutable,
    private val sys: SysNet = LinuxSysNet,
    private val timeoutSeconds: Long = 15,
) : RouteProbe {
    private val windows = os.startsWith("Windows", true)
    override fun route(address: String): OsRoute {
        require(ipLiteral(address) != null) { "Not an IP address." }
        return if (windows) parseWindowsRouteDetail(address, run(vpnPowerShell(windowsRouteDetailScript(address)), timeoutSeconds))
        else parseLinuxRouteGet(address, run(listOf(which("ip") ?: "ip", "-j", "route", "get", address), timeoutSeconds), sys)
    }
}

/** A route probe for this platform, or null where there is none (macOS and others). */
fun localRouteProbe(): RouteProbe? = System.getProperty("os.name").let { if (it.startsWith("Windows", true) || it.equals("Linux", true)) LocalRouteProbe(it) else null }

/** Java's resolver order. Java's HTTPS connection uses the first address of this list. */
fun javaResolve(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()

/** A cheap fingerprint of this computer's interfaces (names, up state, addresses) to notice network changes. */
fun networkFingerprint(): String? = runCatching {
    java.net.NetworkInterface.networkInterfaces().toList().filter { runCatching { it.isUp }.getOrDefault(false) }
        .map { n -> n.name + "=" + n.inetAddresses().toList().map { it.hostAddress }.sorted().joinToString(",") }
        .sorted().joinToString(";").hashCode().toString(16)
}.getOrNull()
