package local.ringleader.core

import kotlinx.serialization.json.*

/**
 * A node's network configuration from `GET /nodes/{node}/network` (needs Sys.Audit on the node): bridges, bonds,
 * VLANs, physical NICs and OVS objects, as Proxmox reports them in /etc/network/interfaces.
 */
data class NodeInterface(
    val name: String,
    /** bridge, bond, eth, vlan, alias, OVSBridge, OVSBond, OVSPort, OVSIntPort, unknown. */
    val type: String,
    val active: Boolean,
    val autostart: Boolean,
    val cidr: String?,
    val cidr6: String?,
    val gateway: String?,
    val gateway6: String?,
    /** Bridge ports or bond members. */
    val members: List<String>,
    val bondMode: String?,
    val vlanId: Int?,
    val vlanRaw: String?,
    val vlanAware: Boolean,
    val mtu: Int?,
    val comment: String?,
)

/** Display order: bridges, bonds, VLANs, physical NICs, the rest; then by name with numbers in numeric order. */
private val interfaceRank = listOf("bridge", "OVSBridge", "bond", "OVSBond", "vlan", "eth", "OVSPort", "OVSIntPort", "alias")

fun parseNodeNetwork(body: String): List<NodeInterface> {
    val rows = (Json.parseToJsonElement(body).jsonObject["data"] as? JsonArray).orEmpty()
    return rows.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        fun flag(k: String) = (o[k] as? JsonPrimitive)?.let { it.intOrNull == 1 || it.booleanOrNull == true || it.contentOrNull == "1" } == true
        val name = str("iface") ?: return@mapNotNull null
        val type = str("type") ?: "unknown"
        val members = (str("bridge_ports") ?: str("slaves") ?: str("ovs_ports") ?: str("ovs_bonds")).orEmpty()
            .split(' ', ',').map { it.trim() }.filter { it.isNotEmpty() && it != "none" }
        val cidr = str("cidr") ?: str("address")?.let { a -> str("netmask")?.let { m -> "$a/${netmaskBits(m) ?: m}" } ?: a }
        NodeInterface(name, type, flag("active"), flag("autostart"), cidr, str("cidr6") ?: str("address6")?.let { a -> str("netmask6")?.let { "$a/$it" } ?: a },
            str("gateway"), str("gateway6"), members, str("bond_mode"), str("vlan-id")?.toIntOrNull() ?: name.substringAfterLast('.', "").toIntOrNull()?.takeIf { type == "vlan" },
            str("vlan-raw-device") ?: name.substringBeforeLast('.', "").takeIf { type == "vlan" && it.isNotEmpty() }, flag("bridge_vlan_aware"),
            str("mtu")?.toIntOrNull(), str("comments"))
    }.sortedWith(compareBy<NodeInterface>({ interfaceRank.indexOf(it.type).let { r -> if (r < 0) interfaceRank.size else r } }, { naturalKey(it.name) }))
}

/** "255.255.255.0" -> 24; null when it is not a contiguous IPv4 mask. */
fun netmaskBits(mask: String): Int? {
    val n = ScanRules.ipv4(mask) ?: return null
    val bits = java.lang.Long.bitCount(n)
    return bits.takeIf { n == ((0xffffffffL shl (32 - bits)) and 0xffffffffL) || bits == 0 }
}

/** Sorts "vmbr2" before "vmbr10". */
private fun naturalKey(name: String): String = Regex("\\d+").replace(name) { it.value.padStart(6, '0') }

fun nodeNetworkPath(node: String): String {
    require(node.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,62}"))) { "Invalid node name." }
    return "/api2/json/nodes/${encodePathSegment(node)}/network"
}

/** Demo data for the network panel. */
val demoNodeNetwork: List<NodeInterface> = parseNodeNetwork("""{"data":[
 {"iface":"vmbr0","type":"bridge","active":1,"autostart":1,"cidr":"192.168.50.10/24","gateway":"192.168.50.1","bridge_ports":"bond0","bridge_vlan_aware":1,"comments":"LAN"},
 {"iface":"vmbr1","type":"bridge","active":1,"autostart":1,"bridge_ports":"none","comments":"Isolated lab"},
 {"iface":"bond0","type":"bond","active":1,"autostart":1,"slaves":"enp1s0 enp2s0","bond_mode":"802.3ad","mtu":9000},
 {"iface":"vmbr0.20","type":"vlan","active":1,"autostart":1,"cidr":"10.20.0.10/24","vlan-raw-device":"vmbr0"},
 {"iface":"enp1s0","type":"eth","active":1,"autostart":0},
 {"iface":"enp2s0","type":"eth","active":1,"autostart":0},
 {"iface":"wlp3s0","type":"eth","active":0,"autostart":0}
]}""")
