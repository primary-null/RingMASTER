package local.ringleader.core

// Where the scan plan learns of networks beyond this computer's own cards. Each source answers one question ("which networks do
// this computer's routes reach?", "where do the servers I know live?", "which subnets did my tailnet's routers advertise?") and
// the plan merges the answers by network, names each one's source and decides what is ticked (ScanPlanner). A source reads what
// is already in memory or already read; it never sends a packet and never starts a process.

/** What a source is given: this computer's own cards, and what the system said about adapters and routes (null when it could not be read). */
data class ReachableContext(val cards: List<ScanInterface>, val facts: NetworkFacts?)

/** What a source found: the networks, how many more it left off its list, and what went wrong in words. */
class ReachableRead(val networks: List<ReachableNetwork> = emptyList(), val leftOut: Int = 0, val problem: String? = null)

/**
 * A source of reachable networks. [networks] must be quick and must not block: it is asked every time the plan is rebuilt, which is
 * on every change of what the page shows. A source that needs a slow read does it elsewhere and answers from the result.
 */
fun interface ReachableNetworksSource {
    fun networks(context: ReachableContext): ReachableRead
}

/** The routing table: private networks routed to this computer other than its own cards' networks. Routes through Tailscale's interface are on, others off. */
class OsRoutesSource : ReachableNetworksSource {
    override fun networks(context: ReachableContext): ReachableRead {
        val facts = context.facts ?: return ReachableRead()
        val found = routeNetworks(facts, context.cards)
        return ReachableRead(found.networks, found.leftOut)
    }
}

/** The /24 around each server this computer already knows: saved connections, open connections, earlier results. On, when private. */
class KnownServersSource(private val known: () -> List<KnownServer>) : ReachableNetworksSource {
    override fun networks(context: ReachableContext): ReachableRead = ReachableRead(knownServerNetworks(known()))
}

/**
 * ADAPTER STUB for the tailnet's own subnet routes. It is complete and tested, and connected to nothing: the Tailscale status
 * parser (the peers' `PrimaryRoutes` and wider `AllowedIPs`) is being changed elsewhere, so this takes what that parser will hand over
 * as plain pairs, `(router name, CIDR)`, and nothing of Tailscale's own types.
 *
 * To connect it, once the peer type carries its routes (say `TailscalePeer.primaryRoutes`), give the Scan page's session one line
 * where the window creates it, after the VPN session exists:
 *
 * ```
 * scanSession.connect(TailnetSubnetRoutes { vpnSession.snapshot?.tailscale?.peers.orEmpty().flatMap { p -> p.primaryRoutes.map { p.hostName to it } } })
 * ```
 *
 * A route is on by default only when this computer can use it: the routing table must hold a route through Tailscale's interface that
 * covers it (on Linux that needs `tailscale up --accept-routes`). A route the table does not hold is listed off and says why; when
 * the table could not be read the route is listed off and says so. IPv6 routes, junk, host routes and anything wider than the plan
 * sweeps are dropped or listed as the plan lists them (a /16 is named, not swept).
 */
class TailnetSubnetRoutes(private val routes: () -> List<Pair<String, String>>) : ReachableNetworksSource {
    override fun networks(context: ReachableContext): ReachableRead {
        val facts = context.facts
        val found = linkedMapOf<String, ReachableNetwork>()
        for ((router, text) in routes().take(MAX_ADVERTISED)) {
            val (net, prefix) = parseCidr(text.trim()) ?: continue
            if (prefix >= 31 || net ushr 24 == 0L) continue
            val cidr = cidrText(net, prefix)
            val name = router.filter { !it.isISOControl() }.trim().take(80).ifEmpty { "a router" }
            val accepted = facts?.let { f -> f.routes.any { r -> f.viaTailscale(r) && r.prefix <= prefix && (net and prefixMask(r.prefix)) == r.network } }
            val reason = when (accepted) {
                true -> null
                false -> "$name advertises it, but this computer has no route to it through Tailscale (on Linux, tailscale up --accept-routes)."
                null -> "$name advertises it. This computer's routes could not be read, so it is not known whether it is routed here."
            }
            // The same network from two routers: one row, under the first router's name.
            if (cidr !in found) found[cidr] = ReachableNetwork(cidr, PlanSource.Tailnet, "Tailscale route via $name", null, accepted == true, reason, false, "advertised by $name")
        }
        return ReachableRead(found.values.toList())
    }
    private companion object { const val MAX_ADVERTISED = 256 }
}

object ReachableNetworks {
    /** The answers of [sources] together, in the order given. A source that fails is named in the problem and the others stand. */
    fun collect(sources: List<ReachableNetworksSource>, context: ReachableContext): ReachableRead {
        val networks = mutableListOf<ReachableNetwork>()
        var leftOut = 0
        val problems = mutableListOf<String>()
        for (source in sources) {
            val read = try { source.networks(context) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                problems += "${source.javaClass.simpleName.ifBlank { "A source of networks" }} could not be read."
                continue
            }
            networks += read.networks; leftOut += read.leftOut; read.problem?.let { problems += it }
        }
        return ReachableRead(networks, leftOut, problems.joinToString(" ").ifBlank { null })
    }
}
