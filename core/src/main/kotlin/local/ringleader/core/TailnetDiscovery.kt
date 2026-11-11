package local.ringleader.core
import kotlinx.coroutines.flow.*
fun findTailnetProxmox(status: TailscaleStatus, scanner: NetworkScanner = NetworkScanner(), origins: List<String> = emptyList()): Flow<List<TailnetService>> {
    val peers = status.peers.filter { it.online }
    val byIp = peers.flatMap { p -> p.addresses.filter { ScanRules.ipv4(it) != null }.map { it to p } }.toMap()
    val plan = NetworkPlan(listOf(ScanNetwork(ScanInterface("Tailscale", status.self?.addresses?.firstOrNull { ScanRules.ipv4(it) != null }.orEmpty(), 32), null, null, byIp.keys.toList())))
    return scanner.scan(plan, origins).map { p -> p.hits.filter { it.proxmox }.mapNotNull { hit -> byIp[hit.ip]?.let { TailnetService(it, hit) } } }
}
