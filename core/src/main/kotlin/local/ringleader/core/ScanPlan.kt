package local.ringleader.core

// The scan plan: every network this computer could sweep, each with where it came from, its real size, what a sweep would
// take and why it is off or cannot be swept. The plan is built before anything is sent, shown to the person, and only a
// press on Start sweeps what it ticks. Nothing here touches the network.
//
// Rules (owner, 2026-11-16):
// - On by default: the private networks of this computer's own cards (as before), private networks routed through its own
//   tailnet, and the /24 around each saved connection, open connection and earlier Proxmox hit when it is private.
// - Listed, off by default: a network on a card Windows calls Public, other VPN or static routes to private networks, the
//   wider part of a network that is wider than a /24, and anything the person has switched off.
// - Never swept: more than a /22 as one network, more than 4,096 addresses in one scan, Tailscale/CGNAT space, loopback,
//   anything that is not private unless the person typed that exact network this session.

/** The widest network swept as one: a /22 (1,022 addresses). Anything wider is cut or listed as too wide. */
const val MAX_NETWORK_HOSTS_PREFIX = 22
/** Addresses in one scan, over all networks. */
const val MAX_SCAN_ADDRESSES = 4096
/** Routes listed on the plan, most specific first. */
const val MAX_ROUTE_ROWS = 12

/** Where a network on the plan came from. [rank] decides which source speaks for a network several sources name. */
enum class PlanSource(val rank: Int, val label: String) {
    Typed(0, "Typed"), Attached(1, "This computer"), Tailnet(2, "Tailscale route"), Saved(3, "Saved connection"),
    Session(4, "Open connection"), Earlier(5, "Earlier result"), Route(6, "Route"),
}

/**
 * One row of the plan. [id] is the network that would be swept, as `a.b.c.d/p`, and is the key under which a choice is remembered.
 * A row with no [sweep] cannot be swept at all and says why in [reason]. [on] is what Start does: the person's choice, else the
 * default, unless a bigger ticked row already covers this one ([coveredBy]) or the scan would pass its address limit ([limited]).
 */
data class PlanNetwork(
    val id: String,
    val source: PlanSource,
    /** The card, router or server this network is named after. */
    val label: String,
    /** Other sources that name the same network, for example a saved connection inside an attached /24. */
    val alsoFrom: List<String> = emptyList(),
    /** The network as it really is. Larger than [sweep] when the sweep is cut down. */
    val real: String,
    val realPrefix: Int,
    /** Hosts in the real network. */
    val realCount: Int,
    val sweep: String?,
    val addresses: List<String> = emptyList(),
    val onLink: Boolean = false,
    val ports: List<Int> = LAN_SCAN_PORTS,
    val defaultOn: Boolean = false,
    val remembered: Boolean? = null,
    val on: Boolean = false,
    val coveredBy: String? = null,
    val limited: Boolean = false,
    /** Why this row cannot be swept, or why it is off by default. Null when it is simply on. */
    val reason: String? = null,
    /** What the sweep is a part of, in words: "the /24 around 10.20.5.7, of a /22". Null when the whole network is swept. */
    val note: String? = null,
    /** This row is the wider choice for the row above it. */
    val wider: Boolean = false,
    /** The card's own address, or the server's address a /24 is taken around. */
    val ip: String = "",
    /** Technical name of the card or tunnel, for a tooltip. */
    val detail: String? = null,
) {
    val count: Int get() = addresses.size
    val sweepable: Boolean get() = sweep != null
    /** True when Start sweeps this row's addresses. */
    val swept: Boolean get() = on && sweep != null && coveredBy == null && !limited
    /** The choice can be changed by the person: there is a network to sweep, and it is not the network typed itself (that is on while it is typed). */
    val choosable: Boolean get() = sweep != null && (source != PlanSource.Typed || wider)
    val seconds: Int get() = ScanEstimate.seconds(count, onLink, ports.size)
}

/** A network a source found that this computer can reach. The planner decides the rest. */
data class ReachableNetwork(
    val cidr: String,
    val source: PlanSource,
    val label: String,
    /** An address inside the network that a cut is taken around (a saved server), if there is one. */
    val anchor: String? = null,
    val defaultOn: Boolean,
    /** Why it is off by default. */
    val reason: String? = null,
    val onLink: Boolean = false,
    val detail: String? = null,
)

/** The text of the typed-network field, as the plan understands it. */
sealed class TypedNetwork {
    object None : TypedNetwork()
    class Rejected(val reason: String) : TypedNetwork()
    class Rows(val rows: List<PlanNetwork>) : TypedNetwork()
}

/** The plan, for the page and for the scanner. */
data class ScanPlanView(
    val rows: List<PlanNetwork>,
    /** Why the typed text is not a network, in words. Blank when it is, or when nothing is typed. */
    val typedProblem: String = "",
    /** What went wrong reading this computer's routes and adapters, in words. Null when it was read, or not asked for yet. */
    val problem: String? = null,
    /** Routes beyond the first [MAX_ROUTE_ROWS], left off the list. */
    val routesLeftOut: Int = 0,
) {
    val swept: List<PlanNetwork> get() = rows.filter { it.swept }
    val skipped: List<PlanNetwork> get() = rows.filter { !it.sweepable }
    val addressCount: Int get() = swept.sumOf { it.count }
    val estimateSeconds: Int get() = ScanEstimate.total(swept)
    val hasWork: Boolean get() = addressCount > 0

    /** The plan as the scanner takes it: what is ticked, then what cannot be swept (for the record). */
    fun toNetworkPlan(): NetworkPlan = NetworkPlan(rows.mapNotNull { r ->
        when {
            r.swept -> ScanNetwork(ScanInterface(r.label, r.ip, r.realPrefix), r.sweep, null, r.addresses, realCidr = r.real, origin = r.source, onLink = r.onLink, ports = r.ports)
            !r.sweepable -> ScanNetwork(ScanInterface(r.label, r.ip, r.realPrefix), null, r.reason ?: "Not swept")
            else -> null
        }
    })
}

/**
 * What a sweep of so many addresses costs, from the engine's own constants ([ScanTuning]): the cheap pass is one connect on each Proxmox
 * port of each address, [ScanTuning.quickInFlight] at a time, each costing the wait of a silent address; across a router the pace
 * ([ScanTuning.routedPerSecond]) can be the slower of the two. On this computer's own networks the settle, the neighbour table and the
 * services of the hosts that answered come on top.
 */
object ScanEstimate {
    private val tuning = ScanTuning()
    /** What the settle, the neighbour read and the reading of the hosts that answered take, on a network this computer is attached to. */
    private const val ON_LINK_EXTRA = 4.0
    /** The reading of the hosts that answered, across a router. */
    private const val ROUTED_EXTRA = 2.0

    /** Seconds of the cheap pass over [count] addresses on [portCount] ports. */
    internal fun cheapPass(count: Int, onLink: Boolean, portCount: Int): Double {
        val connects = count.toDouble() * minOf(portCount.coerceAtLeast(1), PROXMOX_PORTS.size)
        val wait = (if (onLink) tuning.onLinkQuickMs else tuning.routedQuickMs) / 1000.0
        val byGate = connects * wait / tuning.quickInFlight
        val byPace = if (onLink) 0.0 else connects / tuning.routedPerSecond
        return maxOf(byGate, byPace)
    }

    /** Seconds for [count] addresses on [portCount] ports, alone. Rounded up, at least one. */
    fun seconds(count: Int, onLink: Boolean, portCount: Int): Int =
        if (count <= 0) 0 else kotlin.math.ceil(cheapPass(count, onLink, portCount) + if (onLink) ON_LINK_EXTRA else ROUTED_EXTRA).toInt().coerceAtLeast(1)

    /** The networks side by side share the connects in flight, so their cheap passes add up and the rest is paid once. */
    fun total(rows: List<PlanNetwork>): Int {
        val ticked = rows.filter { it.count > 0 }
        if (ticked.isEmpty()) return 0
        val extra = if (ticked.any { it.onLink }) ON_LINK_EXTRA else ROUTED_EXTRA
        return kotlin.math.ceil(ticked.sumOf { cheapPass(it.count, it.onLink, it.ports.size) } + extra).toInt().coerceAtLeast(1)
    }

    /** "about 8 s", "about 2 min". */
    fun text(seconds: Int): String = when {
        seconds <= 0 -> "no time"
        seconds < 90 -> "about $seconds s"
        else -> "about ${(seconds + 30) / 60} min"
    }
}

object ScanPlanner {
    /**
     * Builds the plan. [interfaces] are this computer's own cards, [facts] what the system says about them (null: not read, so the
     * old name rule decides what is virtual), [reachable] what the sources found, [typed] the text of the subnet field and [choices]
     * the person's remembered tick or untick per network.
     */
    fun plan(
        interfaces: List<ScanInterface>,
        facts: NetworkFacts? = null,
        reachable: List<ReachableNetwork> = emptyList(),
        typed: String = "",
        choices: Map<String, Boolean> = emptyMap(),
        problem: String? = null,
        routesLeftOut: Int = 0,
    ): ScanPlanView {
        val typedResult = ScanTyped.parse(typed)
        val rows = mutableListOf<PlanNetwork>()
        if (typedResult is TypedNetwork.Rows) rows += typedResult.rows
        interfaces.forEach { rows += attached(it, facts) }
        reachable.forEach { rows += reachableRows(it) }
        val merged = merge(rows)
        return finish(merged, choices, (typedResult as? TypedNetwork.Rejected)?.reason.orEmpty(), problem, routesLeftOut)
    }

    // -- attached cards ------------------------------------------------------------------------------------------------

    private fun skippedRow(id: String, source: PlanSource, label: String, real: String, realPrefix: Int, reason: String, ip: String = "", detail: String? = null) =
        PlanNetwork(id, source, label, real = real, realPrefix = realPrefix, realCount = 0, sweep = null, reason = reason, ip = ip, detail = detail)

    private fun attached(card: ScanInterface, facts: NetworkFacts?): List<PlanNetwork> {
        val fact = facts?.adapterFor(card)
        val label = cardLabel(card, fact)
        val detail = card.name.takeIf { it.isNotBlank() }
        val n = ScanRules.ipv4(card.ip)
        val skipId = "skipped:${card.ip}/${card.prefix}:$label"
        fun skip(reason: String) = skippedRow(skipId, PlanSource.Attached, label, if (n != null) cidrText(n and prefixMask(card.prefix.coerceIn(0, 32)), card.prefix.coerceIn(0, 32)) else card.ip, card.prefix, reason, card.ip, detail)
        // The system's own facts decide: a virtual adapter is skipped unless it carries the default route (a Hyper-V external switch
        // moves the computer's real LAN address onto one). The old name rule is the fallback when the facts are not there.
        val decided = facts != null && fact != null && (fact.virtual != null || fact.hardware != null)
        val virtual = if (decided) (fact.virtual == true || fact.hardware == false) && !facts.ownsDefaultRoute(fact, card)
        else VIRTUAL_ADAPTER.containsMatchIn(card.name)
        val reason = when {
            !card.up -> "Interface down"
            n == null -> "Invalid IPv4"
            isMeshIpv4(n) -> "Tailscale / CGNAT space is never swept"
            n ushr 24 == 127L -> "Loopback"
            n ushr 16 == 0xa9feL -> "Link-local"
            virtual -> if (decided) "Virtual adapter that does not carry the default route" else "Virtual-switch adapter"
            !isPrivateIpv4(n) -> "Not private space"
            card.prefix !in 1..30 -> "Point-to-point or invalid prefix"
            else -> null
        }
        if (reason != null) return listOf(skip(reason))
        val address = n!!
        val sweepPrefix = maxOf(24, card.prefix)
        val sweepNet = address and prefixMask(sweepPrefix)
        val realNet = address and prefixMask(card.prefix)
        val publicNet = fact?.isPublic == true
        val publicReason = "Windows calls this network Public. Tick it to scan it anyway."
        val real = cidrText(realNet, card.prefix)
        val main = PlanNetwork(
            id = cidrText(sweepNet, sweepPrefix), source = PlanSource.Attached, label = label, real = real, realPrefix = card.prefix, realCount = hostCount(card.prefix),
            sweep = cidrText(sweepNet, sweepPrefix), addresses = hostsOf(sweepNet, sweepPrefix), onLink = true, defaultOn = !publicNet,
            reason = if (publicNet) publicReason else null, ip = card.ip, detail = detail,
            note = if (card.prefix < 24) "the /24 around ${card.ip}, of a /${card.prefix} (${hostCount(card.prefix).thousands()} addresses)" else null,
        )
        if (card.prefix >= 24) return listOf(main)
        // The wider choice: the whole network up to a /22, never on by default.
        val widerPrefix = maxOf(card.prefix, MAX_NETWORK_HOSTS_PREFIX)
        val widerNet = address and prefixMask(widerPrefix)
        val wider = main.copy(
            id = cidrText(widerNet, widerPrefix), sweep = cidrText(widerNet, widerPrefix), addresses = hostsOf(widerNet, widerPrefix), defaultOn = false, wider = true,
            reason = if (publicNet) publicReason else null,
            note = if (widerPrefix == card.prefix) "the whole network (${hostCount(widerPrefix).thousands()} addresses)"
                else "the /$widerPrefix around ${card.ip}, the most one network is swept, of a /${card.prefix} (${hostCount(card.prefix).thousands()} addresses)",
            realCount = hostCount(card.prefix),
        )
        return listOf(main, wider)
    }

    /** The card as a person knows it: the system's name for it where that is known, else the description Java reports. */
    internal fun cardLabel(card: ScanInterface, fact: AdapterFact?): String {
        fact?.name?.takeIf { it.isNotBlank() }?.let { return it }
        val text = card.name.trim()
        val head = text.substringBefore(' ')
        val rest = text.substringAfter(' ', "").trim()
        return when {
            text.isEmpty() -> card.ip
            rest.isEmpty() || rest == head -> head
            // Java 21 names a Windows card `ethernet_32772 <description>`: the description is the part a person recognises.
            Regex("[a-z]+[0-9]*_[0-9]+", RegexOption.IGNORE_CASE).matches(head) -> rest
            else -> text
        }
    }

    // -- reachable networks (routes, tailnet, known servers) ---------------------------------------------------------------

    private fun reachableRows(r: ReachableNetwork): List<PlanNetwork> {
        val (net, prefix) = parseCidr(r.cidr) ?: return emptyList()
        val real = cidrText(net, prefix)
        val skipId = "skipped:$real:${r.label}"
        fun skip(reason: String) = skippedRow(skipId, r.source, r.label, real, prefix, reason, r.anchor.orEmpty(), r.detail)
        if (isMeshIpv4(net)) return listOf(skip("Tailscale / CGNAT space is never swept. Search your tailnet from the VPN page."))
        if (net ushr 24 == 127L || net ushr 28 >= 0xeL || net ushr 24 == 0L || net ushr 16 == 0xa9feL) return emptyList()
        if (!isPrivateIpv4(net)) return listOf(skip("Not private space"))
        if (prefix < MAX_NETWORK_HOSTS_PREFIX) return listOf(skip("Too wide to sweep as a whole (${hostCount(prefix).thousands()} addresses). Type a network inside it, or save a server there first."))
        if (prefix > 30) return emptyList()
        return listOf(PlanNetwork(
            id = real, source = r.source, label = r.label, real = real, realPrefix = prefix, realCount = hostCount(prefix), sweep = real, addresses = hostsOf(net, prefix),
            onLink = r.onLink, defaultOn = r.defaultOn, reason = r.reason, ip = r.anchor.orEmpty(), detail = r.detail,
            note = r.anchor?.let { "the /$prefix around $it" },
        ))
    }

    // -- merging, coverage, limits --------------------------------------------------------------------------------------

    /** Rows that name the same network become one, spoken for by the source of the best rank. */
    private fun merge(rows: List<PlanNetwork>): List<PlanNetwork> {
        val byId = linkedMapOf<String, PlanNetwork>()
        for (row in rows) {
            val have = byId[row.id]
            if (have == null || !row.sweepable) { byId[if (have == null) row.id else row.id + "#" + byId.size] = row; continue }
            val (best, other) = if (row.source.rank < have.source.rank) row to have else have to row
            // A route the tailnet's router advertises and the same route in the routing table are one source saying it twice.
            val extra = if (best.source == PlanSource.Tailnet && other.source == PlanSource.Tailnet) emptyList() else listOf(other.describeSource())
            val also = (best.alsoFrom + other.alsoFrom + extra).filter { it != best.describeSource() }.distinct()
            byId[row.id] = best.copy(alsoFrom = also, onLink = best.onLink || other.onLink)
        }
        return byId.values.toList()
    }

    private fun PlanNetwork.describeSource(): String = if (source == PlanSource.Typed) "typed network" else label

    private fun finish(merged: List<PlanNetwork>, choices: Map<String, Boolean>, typedProblem: String, problem: String?, routesLeftOut: Int): ScanPlanView {
        // Display order: typed, this computer (each card's wider choice right after it), tailnet, saved, open, earlier, routes; what cannot be swept last.
        val order = merged.withIndex().sortedWith(compareBy({ !it.value.sweepable }, { it.value.source.rank }, { it.index })).map { it.value }
        val withChoice = order.map { r ->
            val remembered = if (r.choosable) choices[r.id] else null
            val on = r.sweepable && ((r.source == PlanSource.Typed && !r.wider) || (remembered ?: r.defaultOn))
            r.copy(remembered = remembered, on = on)
        }
        // A ticked row that lies inside a bigger ticked row is already swept by it: the bigger network comes first.
        val bySize = withChoice.filter { it.on && it.sweepable }.sortedBy { parseCidr(it.sweep!!)!!.second }
        val covered = mutableMapOf<String, String>()
        val taken = mutableListOf<Pair<Long, Int>>()
        for (row in bySize) {
            val (net, prefix) = parseCidr(row.sweep!!)!!
            val cover = taken.firstOrNull { (n, p) -> p < prefix && (net and prefixMask(p)) == n }
            if (cover != null) covered[row.id] = cidrText(cover.first, cover.second) else taken += net to prefix
        }
        var total = 0
        val result = withChoice.map { r ->
            val coveredBy = covered[r.id]
            if (!r.on || r.sweep == null || coveredBy != null) return@map r.copy(coveredBy = coveredBy)
            if (total + r.count > MAX_SCAN_ADDRESSES) r.copy(limited = true, reason = "Over the limit of ${MAX_SCAN_ADDRESSES.thousands()} addresses in one scan. Untick another network first.")
            else { total += r.count; r }
        }
        return ScanPlanView(result, typedProblem, problem, routesLeftOut)
    }
}

internal fun Int.thousands(): String = "%,d".format(java.util.Locale.ROOT, this)

/** Hosts in a network of [prefix] bits: all but the network and broadcast address. A /31 is its two, a /32 its one. */
internal fun hostCount(prefix: Int): Int = when { prefix >= 32 -> 1; prefix == 31 -> 2; prefix <= 0 -> Int.MAX_VALUE; else -> ((1L shl (32 - prefix)) - 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }

/** The addresses a sweep of [network]/[prefix] takes: every host, never the network or broadcast address (a /31 or /32 is its hosts). */
internal fun hostsOf(network: Long, prefix: Int): List<String> = when {
    prefix >= 32 -> listOf(ipv4Text(network))
    prefix == 31 -> listOf(ipv4Text(network), ipv4Text(network + 1))
    else -> (network + 1 until (network or (prefixMask(prefix).inv() and 0xffffffffL))).map(::ipv4Text)
}

/** Interface names of virtual switches and container or VM bridges. The fallback when the system's own facts are not available. */
internal val VIRTUAL_ADAPTER = Regex(
    "vethernet|hyper-v|wsl|virtual|docker|vmware|vbox|virbr\\d*|lxcbr\\d*|lxdbr\\d*|\\bcni\\d+|\\bpodman\\d+|\\bbr-[0-9a-f]{12}\\b",
    RegexOption.IGNORE_CASE)

/** The subnet field. A typed network is the person's own explicit action this session; it is never remembered. */
object ScanTyped {
    fun parse(text: String): TypedNetwork {
        val typed = text.trim()
        if (typed.isEmpty()) return TypedNetwork.None
        if (':' in typed) return TypedNetwork.Rejected("IPv6 networks are not scanned. Use an IPv4 form like 192.168.20.0/24.")
        val address = strictIpv4(typed.substringBefore('/')) ?: return TypedNetwork.Rejected("Not an IPv4 network. Use a form like 192.168.20.0/24, or one address like 192.168.20.7/32.")
        val asked = if ('/' in typed) strictPrefix(typed.substringAfter('/')) ?: return TypedNetwork.Rejected("The prefix after the slash must be a number from 0 to 32.") else 24
        return network(address, asked)
    }

    /**
     * What [address]/[asked] means as a typed network, or why it is refused. A `/32` is that one host and a `/31` its pair. A private
     * network is swept as typed down to a /22; a wider one is cut to the /24 around the address, with the /22 offered, never ticked.
     * A network that is not private is swept only on the Proxmox ports and at most as a /24.
     */
    internal fun network(address: Long, asked: Int, private: Boolean = isPrivateIpv4(address)): TypedNetwork {
        when {
            isMeshIpv4(address) -> return TypedNetwork.Rejected("100.64.0.0/10 is Tailscale, Mullvad and carrier-grade NAT space. It is never swept; search your tailnet from the VPN page.")
            address ushr 24 == 127L -> return TypedNetwork.Rejected("127.x is this computer itself, not a network.")
            address ushr 24 == 0L || address ushr 28 >= 0xeL -> return TypedNetwork.Rejected("That is not a unicast network.")
        }
        val ports = if (private) LAN_SCAN_PORTS else PROXMOX_PORTS
        val realText = cidrText(address and prefixMask(asked), asked)
        val realCount = hostCount(asked)
        val here = ipv4Text(address)
        fun row(prefix: Int, wider: Boolean, note: String?) = PlanNetwork(
            id = cidrText(address and prefixMask(prefix), prefix), source = PlanSource.Typed, label = "Typed network", real = realText, realPrefix = asked, realCount = realCount,
            sweep = cidrText(address and prefixMask(prefix), prefix), addresses = hostsOf(address and prefixMask(prefix), prefix), onLink = false, ports = ports,
            defaultOn = !wider, wider = wider, reason = if (!private) "Not private space: only the Proxmox ports are probed." else null, note = note, ip = here,
        )
        val widest = if (private) MAX_NETWORK_HOSTS_PREFIX else 24
        if (asked >= widest) return TypedNetwork.Rows(listOf(row(asked, false, when (asked) { 32 -> "one address"; 31 -> "two addresses"; else -> null })))
        val rows = mutableListOf(row(24, false, "the /24 around $here, of the /$asked you typed (${realCount.thousands()} addresses)"))
        if (private) rows += row(MAX_NETWORK_HOSTS_PREFIX, true, "the /$MAX_NETWORK_HOSTS_PREFIX around $here, the most one network is swept, of the /$asked you typed")
        return TypedNetwork.Rows(rows)
    }
}
