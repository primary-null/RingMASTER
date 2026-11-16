package local.ringleader.core

import java.net.URI

// Networks this computer can reach beyond its own cards: the routes in its routing table, and the networks of servers it already
// knows. Each becomes a [ReachableNetwork] and is offered on the plan, with its source named. Nothing here sends a packet.

/** A server this computer already knows by address: a saved connection, an open connection or an earlier result. */
data class KnownServer(val address: String, val label: String, val source: PlanSource)

/** The IPv4 literal in an origin such as `https://192.168.20.44:8006`, or null for a host name, an IPv6 address or text that is no origin. */
fun ipv4HostOf(origin: String): String? {
    val host = runCatching { URI(origin.trim()).host }.getOrNull() ?: return null
    return host.takeIf { strictIpv4(it) != null }
}

/** What a read of the routing table gave: the networks to offer, and how many more were left off the list. */
class RouteNetworks(val networks: List<ReachableNetwork>, val leftOut: Int)

private const val ROUTE_OFF_REASON = "A route added by a VPN or by hand. Tick it to scan it."

/**
 * The private networks the routing table reaches, other than the networks of this computer's own cards (those are the cards' own
 * rows). A route through Tailscale's interface is a subnet route this computer accepted and is on by default; any other route is
 * listed off. Default routes, host routes, multicast, loopback, link-local and public destinations are not listed. At most
 * [MAX_ROUTE_ROWS] are returned, tailnet routes first and then the most specific.
 */
fun routeNetworks(facts: NetworkFacts, cards: List<ScanInterface>): RouteNetworks {
    val own = cards.mapNotNull { c -> ScanRules.ipv4(c.ip)?.let { n -> (n and prefixMask(c.prefix.coerceIn(0, 32))) to c.prefix.coerceIn(0, 32) } }.toSet()
    val seen = linkedSetOf<String>()
    val found = mutableListOf<Pair<RouteEntry, ReachableNetwork>>()
    val ordered = facts.routes.sortedWith(compareByDescending<RouteEntry> { it.prefix }.thenBy { it.metric ?: Int.MAX_VALUE }.thenBy { it.network })
    for (r in ordered) {
        if (r.isDefault || r.prefix >= 31) continue
        if (r.network ushr 28 >= 0xeL || r.network ushr 24 == 127L || r.network ushr 24 == 0L || r.network ushr 16 == 0xa9feL) continue
        if (!isPrivateIpv4(r.network)) continue
        if ((r.network to r.prefix) in own) continue
        if (!seen.add(r.cidr)) continue
        val tailnet = facts.viaTailscale(r)
        val where = facts.adapterOf(r)?.name ?: r.interfaceName ?: r.interfaceAddress
        val hop = r.nextHop
        val label = when {
            tailnet -> "Tailscale route"
            hop != null && where != null -> "Route via $hop on $where"
            hop != null -> "Route via $hop"
            where != null -> "Route on $where"
            else -> "Route"
        }
        found += r to ReachableNetwork(r.cidr, if (tailnet) PlanSource.Tailnet else PlanSource.Route, label, null, defaultOn = tailnet,
            reason = if (tailnet) null else ROUTE_OFF_REASON, onLink = false, detail = listOfNotNull(hop?.let { "next hop $it" }, where?.let { "on $it" }, r.metric?.let { "metric $it" }).joinToString(", ").ifBlank { null })
    }
    val sorted = found.sortedBy { (_, n) -> if (n.source == PlanSource.Tailnet) 0 else 1 }.map { it.second }
    return RouteNetworks(sorted.take(MAX_ROUTE_ROWS), (sorted.size - MAX_ROUTE_ROWS).coerceAtLeast(0))
}

/**
 * The /24 around each known server. On by default when it is private (the planner leaves out what is not private or already
 * swept as a part of a bigger network). Servers saved by host name have no address to take a network from and are not here.
 */
fun knownServerNetworks(known: List<KnownServer>): List<ReachableNetwork> = known.mapNotNull { k ->
    val n = strictIpv4(k.address) ?: return@mapNotNull null
    ReachableNetwork(cidrText(n and prefixMask(24), 24), k.source, "${k.source.label} ${k.label}".trim(), anchor = k.address, defaultOn = true)
}
