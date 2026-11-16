package local.ringleader.core
import kotlinx.coroutines.flow.*

/**
 * The tailnet search: online peers' IPv4 addresses, the single hosts they route for and, when this computer accepts subnet
 * routes, the networks behind routers; Proxmox ports only, with the longer tailnet connect timeout.
 */
internal fun tailnetScanner() = NetworkScanner(TAILNET_CONNECT_TIMEOUT_MS)

/**
 * What the tailnet search will probe, and the online peers it leaves out. Location-based exit nodes (Mullvad's, with
 * `Location`) are never probed: they are a third party's servers. Machines shared into the tailnet (`ShareeNode`) and
 * machines owned by another person (a different `UserID`, untagged) are probed only when [includeShared] is set.
 */
data class TailnetSearchPlan(
    val targets: Map<String, TailscalePeer>,
    /** Location-based exit nodes; never probed. */
    val skippedLocation: Int,
    /** Shared in, or owned by another user; probed only when included. */
    val skippedShared: Int,
    val includeShared: Boolean,
    /**
     * Networks behind subnet routers, each planned by the scan's own planner ([ScanRules.planSubnet], so the same size
     * limit as the Scan page), paired with the route. Empty when routes are not accepted.
     */
    val subnets: List<Pair<TailnetSubnetRoute, ScanNetwork>> = emptyList(),
    /** Why networks behind routers are not searched, or null. */
    val subnetsOff: String? = null,
)

/**
 * Peers that belong to someone else: shared into this tailnet, or owned by another person. A tagged peer belongs to the
 * tailnet, not to a person, so it is never "another user's"; when this computer is itself tagged (or its user is not
 * reported) people cannot be compared and only `ShareeNode` counts.
 */
fun ownedBySomeoneElse(peer: TailscalePeer, self: TailscalePeer?): Boolean {
    if (peer.sharee) return true
    val mine = self?.userId?.takeIf { self.tags.isEmpty() } ?: return false
    return peer.tags.isEmpty() && peer.userId != null && peer.userId != mine
}

fun planTailnetSearch(status: TailscaleStatus, includeShared: Boolean = false, accept: AcceptRoutesReading? = null,
    local: List<LocalNetwork> = emptyList()): TailnetSearchPlan {
    val online = status.peers.filter { it.online }
    val located = online.filter { it.location != null }
    val shared = online.filter { it.location == null && ownedBySomeoneElse(it, status.self) }
    val peers = online.filter { it.location == null && (includeShared || !ownedBySomeoneElse(it, status.self)) }
    val byIp = peers.flatMap { p ->
        val peerIps = p.addresses.filter { ScanRules.ipv4(it) != null }
        // Single hosts a peer routes for (a /32 it advertises). A routed subnet or an exit node's 0.0.0.0/0 is not a
        // host: its network address is never probed. Routed networks are planned below, only when routes are accepted.
        val routedHosts = p.allowedIps.filter { it.endsWith("/32") }.map { it.substringBefore('/') }.filter { ScanRules.ipv4(it)?.let { n -> n != 0L } == true }
        (peerIps + routedHosts).distinct().map { it to p }
    }.toMap()
    val routes = tailnetSubnetRoutes(status, local)
    // In userspace mode the operating system has no route into the tailnet, so a router's network could only time out.
    val subnets = if (status.userspace) emptyList() else reachableTailnetRoutes(routes, accept, includeShared).mapNotNull { r ->
        ScanRules.planSubnet(r.cidr, "Through ${r.routerName}")?.let { r to it.copy(addresses = it.addresses.filterNot { a -> a in byIp }) }
    }
    return TailnetSearchPlan(byIp, located.size, if (includeShared) 0 else shared.size, includeShared, subnets,
        if (status.userspace && routes.any { !it.own }) "Networks behind subnet routers are not searched: $TAILSCALE_USERSPACE_NOTE" else subnetSearchOffReason(routes, accept))
}

/** The page's sentence about peers the search left out, or null when none was. Counts only; never names. */
fun tailnetSkipNote(plan: TailnetSearchPlan): String? {
    fun n(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"
    val parts = listOfNotNull(
        plan.skippedLocation.takeIf { it > 0 }?.let { n(it, "location-based exit node", "location-based exit nodes") + " (a VPN provider's servers, never searched)" },
        plan.skippedShared.takeIf { it > 0 }?.let { n(it, "machine", "machines") + " shared by others or owned by another user (tick the box to include them)" },
    )
    return if (parts.isEmpty()) null else "Not searched: " + parts.joinToString("; ") + "."
}

fun findTailnetProxmox(status: TailscaleStatus, scanner: NetworkScanner = tailnetScanner(), origins: List<String> = emptyList(),
    plan: TailnetSearchPlan = planTailnetSearch(status)): Flow<List<TailnetService>> {
    val byIp = plan.targets
    val peers = ScanNetwork(ScanInterface("Tailscale", status.self?.addresses?.firstOrNull { ScanRules.ipv4(it) != null }.orEmpty(), 32), null, null, byIp.keys.toList())
    // Hosts behind a router: the first route that covers an address names it (routes are distinct networks).
    val routed = plan.subnets.flatMap { (route, network) -> network.addresses.map { it to route } }.toMap()
    val routers = status.peers.associateBy { it.id }
    val net = NetworkPlan(listOf(peers) + plan.subnets.map { it.second })
    return scanner.scan(net, origins, ports = PROXMOX_PORTS).map { p -> p.hits.filter { it.proxmox }.mapNotNull { hit ->
        byIp[hit.ip]?.let { TailnetService(it, hit) } ?: routed[hit.ip]?.let { r -> routers[r.routerId]?.let { TailnetService(it, hit, r) } }
    } }
}
