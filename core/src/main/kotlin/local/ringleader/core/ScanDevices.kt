package local.ringleader.core

// One card per machine. A Proxmox node answers on several ports (the web interface on 8006, a backup server on 8007) and often
// on several addresses (its LAN address, a second network, a tailnet address) with the same certificate. The scan finds each of
// those as a separate hit; this puts the hits of one machine together, so the page shows it once and says where else it answers.
//
// What counts as the same machine, and nothing more:
//  - the same address (a host with several ports), and
//  - the same certificate fingerprint on a Proxmox service (a node reached on several addresses). The certificate a node
//    presents is its identity as far as a pin is concerned, so two addresses that present it are one server to RingMASTER too.
// Names never join anything: node names are not unique, and a certificate's common name is whatever its maker typed.

/** How a device is saved: by an address a saved connection uses, or by a certificate a saved connection or an approval pins. Never by name. */
enum class SavedBy { No, Address, Pin }

class SavedMark(val by: SavedBy, /** The saved connection, or what approved the certificate, in words. */ val name: String? = null) {
    val saved: Boolean get() = by != SavedBy.No
    override fun equals(other: Any?) = other is SavedMark && by == other.by && name == other.name
    override fun hashCode() = by.hashCode() * 31 + (name?.hashCode() ?: 0)
    override fun toString() = "SavedMark($by, $name)"
    companion object { val NO = SavedMark(SavedBy.No) }
}

/** What the app already has saved: connection origins (address and port) and certificate pins (canonical hex, lower case, no colons) with their names. */
data class KnownSaved(val origins: List<String> = emptyList(), val pins: Map<String, String> = emptyMap()) {
    companion object { val NONE = KnownSaved() }
}

/** The SHA-256 of a certificate as a pin is written: hex digits only, lower case. Null when [fingerprint] is not 64 hex digits. */
fun pinKey(fingerprint: String?): String? {
    val hex = fingerprint?.replace(":", "")?.trim()?.lowercase() ?: return null
    return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
}

/**
 * One machine the scan found. [primary] is its main service (a Proxmox VE node before a Datacenter Manager before a Backup Server);
 * [choices] is that service on each of the machine's addresses, the address on a network this computer is attached to first;
 * [others] are its other Proxmox services; [saved] says whether and how it is saved.
 */
class ScanDevice(val primary: ScanHit, val choices: List<ScanHit>, val others: List<ScanHit>, val saved: SavedMark) {
    /** Every Proxmox service of this device. */
    val hits: List<ScanHit> get() = choices + others
    /** The addresses the device answers on, the preferred first. */
    val addresses: List<String> get() = (choices + others).map { it.ip }.distinct()
    /** The primary service's other addresses, for "also reachable at". */
    val alsoAt: List<ScanHit> get() = choices.drop(1)
    /** True when [origin] is one of this device's services (a click on the discovery grid names one). */
    fun owns(origin: String) = hits.any { it.origin == origin }
}

object ScanDevices {
    private val kindOrder = listOf(ScanKind.PVE, ScanKind.PDM, ScanKind.PBS)

    /**
     * The Proxmox hits of [hits] as devices, in the order their first hit came. [attached] are this computer's own networks as
     * `a.b.c.d/p`: a device answering there is reached through it by preference. Services that are not Proxmox are not devices
     * and are left for the page's list of services.
     */
    fun group(hits: List<ScanHit>, known: KnownSaved = KnownSaved.NONE, attached: List<String> = emptyList()): List<ScanDevice> {
        val proxmox = hits.filter { it.proxmox }
        if (proxmox.isEmpty()) return emptyList()
        // Union-find over the hits: the same address, or the same certificate on a Proxmox service.
        val parent = IntArray(proxmox.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb) }
        val byIp = HashMap<String, Int>(); val byPin = HashMap<String, Int>()
        proxmox.forEachIndexed { i, hit ->
            byIp[hit.ip]?.let { union(i, it) } ?: run { byIp[hit.ip] = i }
            pinKey(hit.fingerprint)?.let { pin -> byPin[pin]?.let { union(i, it) } ?: run { byPin[pin] = i } }
        }
        val networks = attached.mapNotNull { parseCidr(it) }
        fun isAttached(ip: String) = strictIpv4(ip)?.let { n -> networks.any { (net, prefix) -> (n and prefixMask(prefix)) == net } } == true
        val groups = proxmox.indices.groupBy { find(it) }
        return groups.values.sortedBy { it.first() }.map { members ->
            val all = members.map { proxmox[it] }
            val primaryKind = kindOrder.first { k -> all.any { it.kind == k } }
            // The primary service on each address: the one on an attached network first, then the secure one, then the order found.
            val ofKind = all.filter { it.kind == primaryKind }
            val choices = ofKind.groupBy { it.ip }.values.map { sameIp -> sameIp.sortedWith(compareBy({ !it.secure }, { it.port })).first() }
                .sortedWith(compareBy({ !isAttached(it.ip) }, { ofKind.indexOf(it) }))
            val others = all.filter { o -> choices.none { it.ip == o.ip && it.kind == o.kind } }.sortedWith(compareBy({ kindOrder.indexOf(it.kind) }, { all.indexOf(it) }))
            ScanDevice(choices.first(), choices, others, savedMark(all, known))
        }
    }

    /** Saved by an address a saved connection uses (the address and port), else by a pinned certificate. */
    internal fun savedMark(hits: List<ScanHit>, known: KnownSaved): SavedMark {
        if (hits.any { ScanRules.saved(it, known.origins) }) return SavedMark(SavedBy.Address)
        for (hit in hits) pinKey(hit.fingerprint)?.let { pin -> known.pins[pin]?.let { return SavedMark(SavedBy.Pin, it) } }
        return SavedMark.NO
    }
}
