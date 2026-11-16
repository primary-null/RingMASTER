package local.ringleader.core

// "Why isn't it here?": a person types the address of a server the scan did not show, and the page says in plain words which planned
// network would have contained it, or that none did and why. It reads the plan and the last scan and nothing else: no packet is sent,
// no name is looked up, no route is read again.

/** What the answer is about, so the page can offer the right next step. */
enum class WhyKind {
    /** Not an address the check can read. */
    Invalid,
    /** The last scan found it. */
    Found,
    /** The last scan swept its network and the device proved to be there, but nothing a scan knows answered on it. */
    Silent,
    /** It was in a network that was swept (or is ticked, so the next scan sweeps it), and nothing answered where the sweep asked. */
    Swept,
    /** It is in a network the plan lists but did not sweep: not ticked. [WhyAnswer.tick] names it. */
    Off,
    /** Its network is bigger than what was swept (the /24 around this computer, of a /22): the wider choice would include it. */
    Cut,
    /** Its network is ticked but over the limit of one scan. */
    Limited,
    /** Its network is on the plan and cannot be swept (a virtual adapter, a route too wide to sweep). */
    Skipped,
    /** No network on the plan contains it. [WhyAnswer.typed] is the network to type to scan it, where that is allowed. */
    Outside,
}

/**
 * The answer. [text] is a short paragraph; [tick] a network on the plan to tick ([PlanNetwork.id]); [typed] a network to type into the
 * subnet field; [found] the hit when the scan has it; [address] the address the answer is about.
 */
class WhyAnswer(val kind: WhyKind, val text: String, val tick: String? = null, val typed: String? = null, val found: ScanHit? = null, val address: String? = null)

object ScanExplain {
    /** The ports a scan asks, in words, for the answers that name them. */
    private const val PORTS = "8006, 8007 and 8443 first, then 80, 443, 3000 and 8123 on a device that answered"

    /** The IPv4 address in what was typed: a bare address, `address:port`, or a URL. Null when there is none. */
    internal fun addressIn(text: String): String? {
        var t = text.trim().substringAfter("://").substringBefore('/').substringBefore('?')
        if (t.startsWith('[') || t.count { it == ':' } > 1) return null
        t = t.substringBefore(':')
        return t.takeIf { strictIpv4(it) != null }
    }

    /**
     * [text] is what was typed, [plan] what the page offers now, [scan] the last scan with its own plan (what it really swept), if there
     * has been one. The answer always names a network by its CIDR and the card, router or server it came from.
     */
    fun explain(text: String, plan: ScanPlanView, scan: NetworkProgress? = null): WhyAnswer {
        val typed = text.trim()
        if (typed.isEmpty()) return WhyAnswer(WhyKind.Invalid, "Type the address of the server that is missing, for example 10.20.30.5.")
        if (typed.startsWith('[') || typed.substringAfter("://").count { it == ':' } > 1) return WhyAnswer(WhyKind.Invalid, "IPv6 addresses are not scanned. Type an IPv4 address, for example 10.20.30.5.")
        val ip = addressIn(typed) ?: return WhyAnswer(WhyKind.Invalid, "That is not an IPv4 address. A host name is not looked up here: type the address, for example 10.20.30.5.")
        val n = strictIpv4(ip)!!
        scan?.hits?.firstOrNull { it.ip == ip }?.let { return WhyAnswer(WhyKind.Found, "It was found: ${it.kind.wording} at ${it.ip}:${it.port}. Its card or row is on this page.", found = it, address = ip) }
        fun inside(cidr: String?): Boolean = cidr?.let(::parseCidr)?.let { (net, prefix) -> (n and prefixMask(prefix)) == net } == true
        val note = plan.problem?.let { " $it The plan may be missing a routed network." }.orEmpty()

        // 1. The last scan asked it.
        if (scan != null && ip in scan.plan.addresses) {
            val where = scan.plan.networks.firstOrNull { ip in it.addresses }
            val place = "${where?.cidr ?: "a swept network"}${where?.source?.name?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}"
            val proxmoxOnly = where?.ports?.let { ports -> ports.all { it in PROXMOX_PORTS } } == true
            val asked = if (proxmoxOnly) "8006, 8007 and 8443, the only ports asked outside private space" else PORTS
            if (scan.findings.any { it.ip == ip }) return WhyAnswer(WhyKind.Silent,
                "It is there: it answered the network inside $place. But nothing answered on the ports a scan asks ($asked). The server may listen on another port, or a firewall on it drops these.$note", address = ip)
            val ended = if (scan.finished) "The last scan swept $place and nothing answered at this address on the ports a scan asks ($asked). "
                else "It is inside $place. The last scan did not finish, so it may not have been asked. "
            return WhyAnswer(WhyKind.Swept, ended + "The server may be off, on another address, or behind a firewall that drops these ports.$note", address = ip)
        }

        // 2. The plan sweeps it next time. The first and last address of a network are the network's own, not a device's.
        plan.swept.firstOrNull { inside(it.sweep) }?.let { row ->
            if (ip !in row.addresses) return WhyAnswer(WhyKind.Invalid, "$ip is the network or broadcast address of ${row.sweep}, not the address of a device.", address = ip)
            return WhyAnswer(WhyKind.Swept, "It is inside ${row.sweep} (${row.label}), which is ticked: the next scan asks it.$note", address = ip)
        }

        // 3. A network on the plan would contain it, and is not swept. The most specific one speaks.
        val sweepable = plan.rows.filter { it.sweepable && inside(it.sweep) }.sortedByDescending { parseCidr(it.sweep!!)?.second ?: 0 }
        sweepable.firstOrNull { it.limited }?.let {
            return WhyAnswer(WhyKind.Limited, "It is inside ${it.sweep} (${it.label}), which is ticked but left out: one scan sweeps at most ${MAX_SCAN_ADDRESSES.thousands()} addresses. Untick another network, then scan again.$note", address = ip)
        }
        sweepable.firstOrNull { !it.wider && !it.on }?.let {
            val why = it.reason?.let { r -> ": ${r.trimEnd('.')}." } ?: "."
            return WhyAnswer(WhyKind.Off, "It is inside ${it.sweep} (${it.label}), which is not ticked$why Tick it to include it in the next scan.$note", it.id, address = ip)
        }
        // The wider choice of a network that is swept only as the /24 around this computer.
        sweepable.firstOrNull { it.wider && !it.on }?.let {
            val part = plan.rows.firstOrNull { r -> !r.wider && r.real == it.real && r.source == it.source && r.label == it.label && r.swept }
            val reason = if (part != null) "Only ${part.sweep} is swept of it, and this address is outside that part" else "It is not ticked"
            return WhyAnswer(WhyKind.Cut, "It is inside ${it.real} (${it.label}). $reason. The wider choice, ${it.sweep} with ${it.count.thousands()} addresses, includes it: tick \"Whole network\".$note", it.id, address = ip)
        }
        // Inside a network that is wider than anything a scan sweeps of it.
        plan.rows.filter { it.sweepable && inside(it.real) }.maxByOrNull { it.realPrefix }?.let {
            val around = cidrText(n and prefixMask(24), 24)
            return WhyAnswer(WhyKind.Cut, "It is inside ${it.real} (${it.label}), but a scan sweeps at most a /$MAX_NETWORK_HOSTS_PREFIX of a network and this address is outside that part. Type $around to scan the /24 around it.$note", typed = around, address = ip)
        }
        plan.rows.filter { !it.sweepable && inside(it.real) }.maxByOrNull { it.realPrefix }?.let {
            val reason = (it.reason ?: "it cannot be swept").trimEnd('.')
            val around = typedNetworkFor(n)
            val next = if (around != null) " If the server is yours, type $around to scan the /24 around it." else ""
            return WhyAnswer(WhyKind.Skipped, "It is inside ${it.real} (${it.label}), which is not swept: $reason.$next$note", typed = around, address = ip)
        }

        // 4. No network on the plan contains it.
        return outside(n, ip, note)
    }

    /** The /24 around an address, as the subnet field takes it; null where a typed network is never swept. */
    private fun typedNetworkFor(n: Long): String? =
        if (isMeshIpv4(n) || n ushr 24 == 127L || n ushr 24 == 0L || n ushr 28 >= 0xeL) null else cidrText(n and prefixMask(24), 24)

    private fun outside(n: Long, ip: String, note: String): WhyAnswer {
        val around = cidrText(n and prefixMask(24), 24)
        return when {
            isMeshIpv4(n) -> WhyAnswer(WhyKind.Outside, "100.64.0.0/10 is Tailscale, Mullvad and carrier-grade NAT space, which is never swept. Devices on your tailnet are searched from the VPN page.$note", address = ip)
            n ushr 24 == 127L -> WhyAnswer(WhyKind.Outside, "127.x is this computer itself, not a network a scan reaches.$note", address = ip)
            n ushr 24 == 0L || n ushr 28 >= 0xeL -> WhyAnswer(WhyKind.Outside, "That is not the address of a device: it is reserved or a multicast address.$note", address = ip)
            n ushr 16 == 0xa9feL -> WhyAnswer(WhyKind.Outside, "169.254.x.x is link-local space: it is reachable only on the same cable, and none of this computer's swept networks is in it. Type $around to scan it (the Proxmox ports only).$note", typed = around, address = ip)
            !isPrivateIpv4(n) -> WhyAnswer(WhyKind.Outside, "It is not in private space, and a scan sweeps only private networks unless you type the network yourself. Type $around to scan it: only the Proxmox ports are asked, and at most a /24.$note", typed = around, address = ip)
            else -> WhyAnswer(WhyKind.Outside, "None of this computer's networks, routes or known servers include it, so no scan asked it. If this computer can reach it (through a router or a VPN), type its network: $around.$note", typed = around, address = ip)
        }
    }

    private val ScanKind.wording get() = when (this) {
        ScanKind.PVE -> "Proxmox VE"; ScanKind.PBS -> "Proxmox Backup Server"; ScanKind.PDM -> "Proxmox Datacenter Manager"; ScanKind.PIHOLE -> "Pi-hole"
        ScanKind.ADGUARD -> "AdGuard Home"; ScanKind.HOME_ASSISTANT -> "Home Assistant"; ScanKind.WEB -> "a web page"
    }
}
