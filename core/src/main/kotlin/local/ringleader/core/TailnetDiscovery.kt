package local.ringleader.core
import kotlinx.coroutines.flow.*

/** The tailnet search: online peers' IPv4 addresses and the single hosts they route for, Proxmox ports only, with the longer tailnet connect timeout. */
internal fun tailnetScanner() = NetworkScanner(TAILNET_CONNECT_TIMEOUT_MS)

fun findTailnetProxmox(status: TailscaleStatus, scanner: NetworkScanner = tailnetScanner(), origins: List<String> = emptyList()): Flow<List<TailnetService>> {
    val peers = status.peers.filter { it.online }
    val byIp = peers.flatMap { p ->
        val peerIps = p.addresses.filter { ScanRules.ipv4(it) != null }
        // Single hosts a peer routes for (a /32 it advertises). A routed subnet or an exit node's 0.0.0.0/0 is not a
        // host: its network address is never probed. Sweeping a routed subnet is the scan page's Additional subnet.
        val routedHosts = p.allowedIps.filter { it.endsWith("/32") }.map { it.substringBefore('/') }.filter { ScanRules.ipv4(it)?.let { n -> n != 0L } == true }
        (peerIps + routedHosts).distinct().map { it to p }
    }.toMap()
    val plan = NetworkPlan(listOf(ScanNetwork(ScanInterface("Tailscale", status.self?.addresses?.firstOrNull { ScanRules.ipv4(it) != null }.orEmpty(), 32), null, null, byIp.keys.toList())))
    return scanner.scan(plan, origins, ports = PROXMOX_PORTS).map { p -> p.hits.filter { it.proxmox }.mapNotNull { hit -> byIp[hit.ip]?.let { TailnetService(it, hit) } } }
}
