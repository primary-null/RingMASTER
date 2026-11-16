package local.ringleader.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Opt-in measurements on this computer's own attached networks (`RM_SCAN_LIVE=1`). They read the adapters, the routes and the
 * neighbour table and may sweep this computer's first attached /24; they print counts, classes and seconds only, never an address,
 * a host name, a card name or a fingerprint. Without the variable every test returns at once.
 */
class NetworkScanLiveTest {
    private val live = System.getenv("RM_SCAN_LIVE") == "1"

    /** Reads the system's facts for real: how long it takes, what it finds, and what the plan makes of this computer. */
    @Test fun liveFactsAndPlan() {
        if (!live) return
        val cards = NetworkScanner.attachedInterfaces()
        val started = System.nanoTime()
        val read = runBlocking { LocalNetworkReader().read() }
        println("LIVE facts read took ${"%.2f".format((System.nanoTime() - started) / 1e9)} s; problem=${read.problem ?: "none"}")
        val facts = read.facts
        println("LIVE adapters=${facts?.adapters?.size} (virtual=${facts?.adapters?.count { it.virtual == true }}, hardware=${facts?.adapters?.count { it.hardware == true }}, public=${facts?.adapters?.count { it.isPublic }}, tailscale=${facts?.adapters?.count { it.isTailscale }}) routes=${facts?.routes?.size} defaults=${facts?.routes?.count { it.isDefault }}")
        println("LIVE cards=${cards.size} joined to facts=${cards.count { facts?.adapterFor(it) != null }}")
        val found = facts?.let { routeNetworks(it, cards) }
        println("LIVE route networks listed=${found?.networks?.size} (tailnet=${found?.networks?.count { it.source == PlanSource.Tailnet }}) leftOut=${found?.leftOut}")
        val view = ScanPlanner.plan(cards, facts, found?.networks.orEmpty(), problem = read.problem, routesLeftOut = found?.leftOut ?: 0)
        for (row in view.rows) println("LIVE plan row: source=${row.source} on=${row.on} sweepable=${row.sweepable} count=${row.count} realPrefix=${row.realPrefix} wider=${row.wider} reason=${row.reason?.let { reasonClass(it) }}")
        println("LIVE plan: ${view.swept.size} networks, ${view.addressCount} addresses, estimate ${view.estimateSeconds} s")
        val neighbours = runBlocking { val t = System.nanoTime(); LocalNeighbours().read().also { println("LIVE neighbour table read took ${"%.0f".format((System.nanoTime() - t) / 1e6)} ms") } }
        println("LIVE neighbours=${neighbours?.size}")
    }

    private fun reasonClass(reason: String) = when {
        "Public" in reason -> "windows-public"; "Virtual" in reason -> "virtual"; "Loopback" in reason -> "loopback"; "Link-local" in reason -> "link-local"
        "CGNAT" in reason -> "cgnat"; "Not private" in reason -> "not-private"; "VPN" in reason -> "route"; "Point-to-point" in reason -> "point-to-point"; else -> "other"
    }

    /** One sweep of this computer's first attached /24 with the engine as it is now. Prints seconds, counts and kinds. */
    @Test fun liveSweepOfTheFirstAttachedNetwork() {
        if (!live) return
        val cards = NetworkScanner.attachedInterfaces()
        val facts = runBlocking { LocalNetworkReader().read() }.facts
        val view = ScanPlanner.plan(cards, facts)
        val first = view.swept.firstOrNull { it.onLink } ?: return println("LIVE nothing to sweep")
        val one = NetworkPlan(listOf(ScanNetwork(ScanInterface(first.label, first.ip, first.realPrefix), first.sweep, null, first.addresses, realCidr = first.real, origin = first.source, onLink = true, ports = first.ports)))
        val times = mutableListOf<Double>()
        repeat((System.getenv("RM_SCAN_LIVE_RUNS") ?: "1").toInt()) {
            val t = System.nanoTime()
            val last = runBlocking { NetworkScanner().scan(one).toList().last() }
            val seconds = (System.nanoTime() - t) / 1e9
            times += seconds
            println("LIVE sweep: ${last.scanned}/${last.total} addresses in ${"%.1f".format(seconds)} s, hosts answering ${last.hits.map { it.ip }.distinct().size}, by kind ${last.hits.groupBy { it.kind }.mapValues { it.value.size }.toSortedMap()}")
        }
        println("LIVE sweep runs: ${times.joinToString(" ") { "%.1f".format(it) }} s")
    }
}
