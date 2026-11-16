package local.ringleader.core

import kotlinx.serialization.json.*
import java.net.Inet4Address
import java.net.NetworkInterface

/*
 * Subnet routes on the tailnet: which private networks other machines (subnet routers) offer, whether THIS computer uses
 * them ("accept-routes"), and what the Proxmox search and the Scan page may reach through them. Reads only; the one change
 * (turning accept-routes on) is made by the backend after one confirmation.
 */

/** One IPv4 network this computer is directly attached to. [name] is the interface (an identity: hidden in Showcase). */
data class LocalNetwork(val name: String, val cidr: String)

/** An IPv4 network as (network address, prefix length), normalised so host bits are zero. */
data class Ipv4Net(val network: Long, val prefix: Int) {
    val size get() = 1L shl (32 - prefix)
    val last get() = network + size - 1
    fun contains(address: Long) = address in network..last
    fun overlaps(other: Ipv4Net) = network <= other.last && other.network <= last
    override fun toString() = "${ipv4Text(network)}/$prefix"
}


/** "a.b.c.d/p" (or a bare IPv4 as /32), normalised. Null for anything else, IPv6 included. */
fun parseIpv4Net(text: String): Ipv4Net? {
    val t = text.trim()
    val n = ScanRules.ipv4(t.substringBefore('/')) ?: return null
    val prefix = if ('/' in t) t.substringAfter('/').toIntOrNull()?.takeIf { it in 0..32 } ?: return null else 32
    val mask = if (prefix == 0) 0L else (0xffffffffL shl (32 - prefix)) and 0xffffffffL
    return Ipv4Net(n and mask, prefix)
}

private val PRIVATE_BLOCKS = listOf(parseIpv4Net("10.0.0.0/8")!!, parseIpv4Net("172.16.0.0/12")!!, parseIpv4Net("192.168.0.0/16")!!)

/** The whole network lies inside one RFC 1918 block. */
fun privateIpv4Net(net: Ipv4Net): Boolean = PRIVATE_BLOCKS.any { b -> net.prefix >= b.prefix && b.contains(net.network) }

/** A network a tailnet machine offers to the rest of the tailnet. */
data class TailnetSubnetRoute(
    /** Normalised "a.b.c.d/p". */
    val cidr: String,
    val routerId: String,
    /** The router's host name (an identity: hidden in Showcase). */
    val routerName: String,
    val routerOnline: Boolean,
    /** This computer itself offers the network. */
    val own: Boolean,
    /** From `PrimaryRoutes` (the router currently in charge); false when only `AllowedIPs` named it (older clients). */
    val primary: Boolean,
    /** A network this computer is directly attached to that overlaps this one, or null. */
    val overlap: LocalNetwork? = null,
    /** The router was shared in by someone else or belongs to another person (see [ownedBySomeoneElse]). */
    val sharedRouter: Boolean = false,
)

/**
 * The private IPv4 networks routers on the tailnet offer: each peer's `PrimaryRoutes`, or, where a peer reports none, its
 * `AllowedIPs` wider than a host route (older clients). Exit routes (`/0`), host routes (`/32`), Tailscale's own range and
 * anything not wholly inside RFC 1918 are left out. This computer's own offered networks are listed with [TailnetSubnetRoute.own].
 * Each route is checked against [local] networks for overlap. Sorted: online routers first, then by network.
 */
fun tailnetSubnetRoutes(status: TailscaleStatus?, local: List<LocalNetwork> = emptyList()): List<TailnetSubnetRoute> {
    status ?: return emptyList()
    val localNets = local.mapNotNull { l -> parseIpv4Net(l.cidr)?.let { it to l } }
    fun routes(p: TailscalePeer): List<Pair<Ipv4Net, Boolean>> {
        val primary = p.primaryRoutes.mapNotNull(::parseIpv4Net)
        val chosen = if (p.primaryRoutes.isNotEmpty()) primary.map { it to true } else p.allowedIps.mapNotNull(::parseIpv4Net).map { it to false }
        return chosen.filter { (n, _) -> n.prefix in 1..31 && privateIpv4Net(n) }
    }
    fun entries(p: TailscalePeer, own: Boolean) = routes(p).map { (net, primary) ->
        TailnetSubnetRoute(net.toString(), p.id, p.hostName, own || p.online, own, primary,
            localNets.firstOrNull { it.first.overlaps(net) }?.second, !own && ownedBySomeoneElse(p, status.self))
    }
    val list = status.peers.filter { it.location == null }.flatMap { entries(it, false) } + (status.self?.let { entries(it, true) } ?: emptyList())
    return list.distinctBy { it.cidr to it.routerId }
        .sortedWith(compareBy({ it.own }, { !it.routerOnline }, { parseIpv4Net(it.cidr)?.network ?: 0L }, { it.routerName.lowercase() }))
}

/**
 * This computer's directly attached IPv4 networks, from the JVM's interface list (no command). Loopback, down interfaces,
 * link-local, Tailscale's 100.64.0.0/10 and host-only (/31, /32) addresses are left out.
 */
fun localIpv4Networks(): List<LocalNetwork> = runCatching {
    NetworkInterface.networkInterfaces().toList().filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }.flatMap { nic ->
        nic.interfaceAddresses.filter { it.address is Inet4Address }.mapNotNull { a ->
            val ip = a.address.hostAddress
            val n = ScanRules.ipv4(ip) ?: return@mapNotNull null
            val prefix = a.networkPrefixLength.toInt()
            if (prefix !in 1..30 || n ushr 22 == 0x191L || n ushr 16 == 0xa9feL) null
            else LocalNetwork(nic.displayName ?: nic.name, parseIpv4Net("$ip/$prefix").toString())
        }
    }.distinctBy { it.cidr }
}.getOrDefault(emptyList())

// ---------------------------------------------------------------------------------------------------------------
// Accept-routes: does THIS computer use the tailnet's subnet routes?
// ---------------------------------------------------------------------------------------------------------------

enum class AcceptRoutes { Accepted, NotAccepted, Unknown }

/** Who decided the answer. */
enum class AcceptRoutesSource(val label: String) {
    /** `tailscale get --json` (Tailscale 1.102.1 and later): the preference itself. */
    Preference("Tailscale's own setting"),
    /** The operating system's route to an address inside an advertised network (older clients). */
    OsRoute("this computer's route into an advertised network"),
}

/** Which way the operating system sends a network that is both a tailnet route and directly attached here. */
enum class OverlapWinner { Local, Tailscale, Unknown }

data class AcceptRoutesReading(
    val answer: AcceptRoutes,
    val source: AcceptRoutesSource? = null,
    /** Why the answer is Unknown, or a note on how it was decided. Plain language; never an address in Showcase-sensitive form. */
    val reason: String? = null,
    /** Tailscale's operator user (`tailscale get operator`), when the preference was read. */
    val operator: String? = null,
    /** True when [operator] was read (an empty operator is a real answer: nobody but root). */
    val operatorKnown: Boolean = false,
    /** For overlapping networks, keyed by route CIDR: what the operating system's route read says wins. */
    val overlaps: Map<String, OverlapWinner> = emptyMap(),
) {
    val accepted get() = answer == AcceptRoutes.Accepted
    companion object {
        fun unknown(reason: String) = AcceptRoutesReading(AcceptRoutes.Unknown, reason = reason)
    }
}

/** The exact change the single confirmation names. */
const val ACCEPT_ROUTES_COMMAND = "tailscale set --accept-routes=true"

/**
 * `tailscale get --json`: an object keyed by setting name (`{"accept-routes": false, "operator": "", …}`;
 * cmd/tailscale/cli/get.go `getOutputJSON`). Returns accept-routes (null when absent or not a boolean) and the operator
 * (null when absent).
 */
fun parseTailscaleGet(json: String): Pair<Boolean?, String?> {
    val o = Json.parseToJsonElement(json.trim()) as? JsonObject ?: return null to null
    val accept = (o["accept-routes"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toBooleanStrictOrNull()
    val operator = (o["operator"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    return accept to operator
}

/** The CLI does not know `get` (older than 1.102.1) or that setting. Other failures (daemon down) are not about the version. */
fun tailscaleGetUnsupported(message: String): Boolean =
    Regex("unknown (sub)?command|unknown setting|flag provided but not defined|no such command", RegexOption.IGNORE_CASE).containsMatchIn(message)

/** An address inside [route] to read the operating system's route for: the first host that is not on a local network. */
fun acceptRoutesProbeAddress(route: TailnetSubnetRoute): String? {
    val net = parseIpv4Net(route.cidr) ?: return null
    if (net.size < 4) return null
    return ipv4Text(net.network + 1)
}

/** True when [route]'s answer for [address] goes through Tailscale on this computer (interface, source address or driver). */
fun routeGoesThroughTailscale(route: OsRoute, tunnels: List<VpnTunnel>, tailscaleSelf: List<String>): Boolean {
    val match = matchRouteTunnelEvidence(route.iface?.name, route.source, tunnels, tailscaleSelf)
    return match?.first?.kind == VpnKind.Tailscale || (match == null && route.iface?.driverKind == VpnKind.Tailscale)
}

/**
 * Decides accept-routes. [get] is the result of `tailscale get --json` (null when it was not run). When the preference
 * cannot be read (an older client), the operating system's route to an address inside an online router's network that
 * does not overlap a local network decides it ([readRoute]); a network that overlaps a local one cannot tell the two apart.
 * Platform defaults (Windows, macOS app and phones accept routes by default, Linux does not; ipn/prefs.go
 * `DefaultRouteAll`) are never taken as the answer: the setting can be changed on every platform.
 */
fun detectAcceptRoutes(
    platform: String,
    get: Result<String>?,
    routes: List<TailnetSubnetRoute>,
    tunnels: List<VpnTunnel>,
    tailscaleSelf: List<String>,
    readRoute: ((String) -> OsRoute)?,
): AcceptRoutesReading {
    var why: String? = null
    var operator: String? = null
    var operatorKnown = false
    get?.fold({ text ->
        val (accept, op) = runCatching { parseTailscaleGet(text) }.getOrDefault(null to null)
        operator = op; operatorKnown = op != null
        if (accept != null) return AcceptRoutesReading(if (accept) AcceptRoutes.Accepted else AcceptRoutes.NotAccepted, AcceptRoutesSource.Preference, operator = op, operatorKnown = op != null)
        why = "Tailscale's settings did not include accept-routes."
    }, { e ->
        why = if (tailscaleGetUnsupported(e.message.orEmpty())) "This Tailscale version cannot report the setting ('tailscale get' arrived in 1.102.1)."
        else "Tailscale's settings could not be read (${vpnFailureMessage(e.message.orEmpty())})."
    })
    val candidate = routes.firstOrNull { !it.own && it.routerOnline && it.overlap == null }
    val fallbackOff = when {
        readRoute == null -> "Route checks are not available on this platform."
        candidate == null -> if (routes.any { !it.own && it.overlap == null }) "No subnet router is online to test the route." else "No advertised network can be tested without touching the local network."
        else -> null
    }
    val default = if (platform.equals("Linux", true)) " On Linux, Tailscale does not use subnet routes unless accept-routes is turned on."
        else " Tailscale on Windows uses subnet routes unless 'Use Tailscale subnets' was turned off in its menu."
    if (fallbackOff != null || candidate == null || readRoute == null)
        return AcceptRoutesReading(AcceptRoutes.Unknown, reason = listOfNotNull(why, fallbackOff).joinToString(" ") + default, operator = operator, operatorKnown = operatorKnown)
    val probe = acceptRoutesProbeAddress(candidate) ?: return AcceptRoutesReading.unknown((why ?: "") + " The advertised network is too small to test.")
    val route = runCatching { readRoute(probe) }.getOrElse {
        return AcceptRoutesReading(AcceptRoutes.Unknown, reason = listOfNotNull(why, "The route check failed.").joinToString(" ") + default, operator = operator, operatorKnown = operatorKnown)
    }
    if (route.iface == null) return AcceptRoutesReading(AcceptRoutes.Unknown, reason = listOfNotNull(why, "The operating system named no interface for the test route.").joinToString(" "), operator = operator, operatorKnown = operatorKnown)
    val through = routeGoesThroughTailscale(route, tunnels, tailscaleSelf)
    return AcceptRoutesReading(if (through) AcceptRoutes.Accepted else AcceptRoutes.NotAccepted, AcceptRoutesSource.OsRoute,
        reason = why, operator = operator, operatorKnown = operatorKnown)
}

/** For each overlapping route, which way the operating system sends an address in the overlap (read only when routes are accepted). */
fun overlapWinners(routes: List<TailnetSubnetRoute>, tunnels: List<VpnTunnel>, tailscaleSelf: List<String>, readRoute: ((String) -> OsRoute)?): Map<String, OverlapWinner> =
    routes.filter { !it.own && it.overlap != null }.associate { r ->
        val net = parseIpv4Net(r.cidr)!!
        val localNet = parseIpv4Net(r.overlap!!.cidr)
        // An address in both networks; the network address itself is skipped.
        val start = maxOf(net.network, localNet?.network ?: net.network) + 1
        val winner = if (readRoute == null || !(net.contains(start) && (localNet?.contains(start) != false))) OverlapWinner.Unknown
        else runCatching { readRoute(ipv4Text(start)) }.map { route ->
            when { route.iface == null -> OverlapWinner.Unknown; routeGoesThroughTailscale(route, tunnels, tailscaleSelf) -> OverlapWinner.Tailscale; else -> OverlapWinner.Local }
        }.getOrDefault(OverlapWinner.Unknown)
        r.cidr to winner
    }

/** The confirmed change still applies: routes are offered and this computer still does not use them. */
fun acceptRoutesStillApplies(reading: AcceptRoutesReading?, routes: List<TailnetSubnetRoute>): Boolean =
    reading?.answer == AcceptRoutes.NotAccepted && routes.any { !it.own }

/**
 * On Linux only root or Tailscale's operator may change a preference. True when the operator was read and it is
 * someone else (then the page shows the exact command instead of a button that would be refused).
 */
fun acceptRoutesNeedsSudo(platform: String, reading: AcceptRoutesReading?, user: String = System.getProperty("user.name").orEmpty()): Boolean =
    platform.equals("Linux", true) && reading?.operatorKnown == true && user != "root" && reading.operator != user

/** The command a Linux user without operator rights runs instead. */
const val ACCEPT_ROUTES_SUDO_COMMAND = "sudo $ACCEPT_ROUTES_COMMAND"

/** One plain sentence for an overlapping network. [cidr], [router] and [local] are already concealed where needed. */
fun overlapSentence(cidr: String, accepted: Boolean, winner: OverlapWinner?, router: String, local: String): String = when {
    !accepted -> "$cidr is also this computer's local network ($local), so it is reached directly here."
    winner == OverlapWinner.Local -> "$cidr is also this computer's local network ($local): the local network wins, so servers in it are reached directly, not through $router."
    winner == OverlapWinner.Tailscale -> "$cidr is also this computer's local network ($local), but the operating system sends it through $router."
    else -> "$cidr is also this computer's local network ($local); which way the operating system sends it was not read."
}

// ---------------------------------------------------------------------------------------------------------------
// What can be reached through routers: the Proxmox search on the VPN page and the Scan page's reachable networks.
// ---------------------------------------------------------------------------------------------------------------

/**
 * Networks behind subnet routers this computer can actually reach: accept-routes on, an online router that is not this
 * computer, no overlap with a local network (the local sweep covers those), and routers owned by someone else only when
 * [includeShared]. Empty unless routes are accepted.
 */
fun reachableTailnetRoutes(routes: List<TailnetSubnetRoute>, accept: AcceptRoutesReading?, includeShared: Boolean = false): List<TailnetSubnetRoute> =
    if (accept?.accepted != true) emptyList()
    else routes.filter { !it.own && it.routerOnline && it.overlap == null && (includeShared || !it.sharedRouter) }

/**
 * The Scan page's "reachable networks" source: (router name, CIDR) for each network this computer reaches through a
 * subnet router, accepted routes only (see [reachableTailnetRoutes]). Empty when Tailscale is not running, routes are not
 * accepted, or nothing is offered.
 */
fun tailnetReachableNetworks(status: TailscaleStatus?, accept: AcceptRoutesReading?, local: List<LocalNetwork> = localIpv4Networks()): List<Pair<String, String>> =
    if (status?.running != true || status.userspace) emptyList()
    else reachableTailnetRoutes(tailnetSubnetRoutes(status, local), accept).map { it.routerName to it.cidr }

/** Why the search does not cover networks behind routers, or null when it does (or none are offered). */
fun subnetSearchOffReason(routes: List<TailnetSubnetRoute>, accept: AcceptRoutesReading?): String? {
    val offered = routes.filter { !it.own }
    if (offered.isEmpty()) return null
    return when (accept?.answer) {
        AcceptRoutes.Accepted -> if (offered.none { it.routerOnline }) "Networks behind subnet routers are not searched: no router is online." else null
        AcceptRoutes.NotAccepted -> "Networks behind subnet routers are not searched: this computer does not use the tailnet's subnet routes, so it could only time out."
        AcceptRoutes.Unknown, null -> "Networks behind subnet routers are not searched: it is not known whether this computer uses them."
    }
}

/** The confirmation's main text: the exact change, its reach, and how to undo it. */
fun acceptRoutesConfirmBody(): String =
    "This runs: $ACCEPT_ROUTES_COMMAND\n\nTailscale then sends traffic for the networks below through their subnet routers. " +
        "It changes routing for this whole computer, not only RingMASTER. To undo it, run: tailscale set --accept-routes=false"

/** The sentence after the change, from the setting read back. Still not accepted is a failure. */
fun acceptRoutesResult(after: AcceptRoutesReading): String = when (after.answer) {
    AcceptRoutes.Accepted -> "This computer now uses the tailnet's networks."
    AcceptRoutes.NotAccepted -> error("Tailscale took the change but still reports that this computer does not use subnet routes.")
    AcceptRoutes.Unknown -> "Tailscale took the change; the setting could not be read back." + (after.reason?.let { " $it" } ?: "")
}
