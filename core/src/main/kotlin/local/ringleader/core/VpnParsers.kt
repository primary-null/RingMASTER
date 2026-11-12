package local.ringleader.core

import kotlinx.serialization.json.*
import java.time.Instant

internal fun JsonObject.vs(k: String) = (get(k) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
internal fun JsonObject.va(k: String): List<JsonElement> = when(val v = get(k)) { is JsonArray -> v.toList(); is JsonObject -> listOf(v); else -> emptyList() }
private fun JsonObject.strings(k: String) = va(k).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
fun parseTailscaleStatus(json: String): TailscaleStatus {
    val r = Json.parseToJsonElement(json).jsonObject
    fun instant(value: String?) = value?.takeUnless { it.startsWith("0001-") }?.let { runCatching { Instant.parse(it) }.getOrNull() }
    fun peer(p: JsonObject) = TailscalePeer(p.vs("ID").orEmpty(), p.vs("HostName").orEmpty(), p.vs("DNSName")?.trimEnd('.'), p.vs("OS"), p.vs("Online") == "true", p.strings("TailscaleIPs").sortedBy { ':' in it }, p.vs("ExitNodeOption") == "true", p.vs("ExitNode") == "true", p.vs("Relay"), p.vs("CurAddr"), instant(p.vs("LastSeen")), p.vs("RxBytes")?.toLongOrNull(), p.vs("TxBytes")?.toLongOrNull(), p.strings("Tags"), instant(p.vs("LastHandshake")))
    val tail = r["CurrentTailnet"] as? JsonObject
    val self = (r["Self"] as? JsonObject)?.let(::peer)?.let { if(it.addresses.isEmpty()) it.copy(addresses = r.strings("TailscaleIPs")) else it }
    return TailscaleStatus(r.vs("BackendState").orEmpty(), r.vs("Version"), tail?.vs("Name"), tail?.vs("MagicDNSSuffix") ?: r.vs("MagicDNSSuffix"), self, (r["Peer"] as? JsonObject)?.values.orEmpty().map { peer(it.jsonObject) }.sortedWith(compareBy({ !it.online }, { it.hostName.lowercase() })), r.vs("AuthURL"), r.strings("Health"))
}
fun parseWgDump(text: String): List<WireGuardInterface> {
    val interfaces = linkedMapOf<String, WireGuardInterface>()
    val peers = mutableMapOf<String, MutableList<WireGuardPeer>>()
    text.lineSequence().forEach { line ->
        val f = line.split('\t')
        fun nullable(i: Int) = f[i].takeUnless { it == "(none)" || it == "off" }
        if(f.size == 5) interfaces[f[0]] = WireGuardInterface(f[0], nullable(2), nullable(3)?.toIntOrNull(), emptyList())
        if(f.size == 9) peers.getOrPut(f[0]) { mutableListOf() } += WireGuardPeer(f[1], nullable(3), f[4].split(',').filter { it != "(none)" }, f[5].toLongOrNull()?.takeIf { it > 0 }, f[6].toLongOrNull() ?: 0, f[7].toLongOrNull() ?: 0, nullable(8)?.toIntOrNull())
    }
    return interfaces.values.map { it.copy(peers = peers[it.name].orEmpty()) }
}
fun splitNmFields(line: String): List<String> {
    val result = mutableListOf<String>(); val field = StringBuilder(); var escape = false
    for(c in line) { if(escape) { field.append(c); escape = false } else when(c) { '\\' -> escape = true; ':' -> { result += field.toString(); field.clear() }; else -> field.append(c) } }
    if(escape) field.append('\\'); result += field.toString(); return result
}
internal fun vpnKind(name: String) = when { name.contains("openvpn", true) -> VpnKind.OpenVpn; Regex("ipsec|strongswan|l2tp|ikev2|sstp|pptp", RegexOption.IGNORE_CASE).containsMatchIn(name) -> VpnKind.IpSec; else -> VpnKind.Other }
fun parseNmConnections(text: String): List<VpnTunnel> = text.lines().mapNotNull { line ->
    if(line.isBlank()) return@mapNotNull null
    val f = splitNmFields(line)
    // STATE is optional. When it is present only the English words from LC_ALL=C count; a translated word is Down
    // until the active-connection list says otherwise.
    if(f.size < 4 || f[2] !in listOf("wireguard", "vpn")) null else VpnTunnel("nm:${f[0]}", if(f[2] == "wireguard") VpnKind.WireGuard else vpnKind(f[1]), f[1], when(f.getOrNull(4)) { "activated" -> VpnState.Up; "activating" -> VpnState.Connecting; else -> VpnState.Down }, f[3].takeUnless { it.isBlank() || it == "--" }, canToggle = true, canRemove = f[2] == "wireguard")
}
/** `ip -j addr show type wireguard`. Some iproute2 versions print an empty `{}` for every filtered-out link; those are skipped. */
fun parseIpWireGuardJson(json: String): List<VpnTunnel> = Json.parseToJsonElement(json).jsonArray.mapNotNull { e ->
    val p = e.jsonObject; val name = p.vs("ifname")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    VpnTunnel("wg:$name", VpnKind.WireGuard, name, if(p.va("flags").any { it.jsonPrimitive.content == "UP" }) VpnState.Up else VpnState.Down, name, p.va("addr_info").mapNotNull { val a = it.jsonObject; a.vs("local")?.let { ip -> "$ip/${a.vs("prefixlen")}" } })
}
fun parseWindowsVpnJson(json: String): List<VpnTunnel> {
    val r = Json.parseToJsonElement(json).jsonObject
    fun addresses(alias: String) = r.va("addresses").map { it.jsonObject }.filter { it.vs("InterfaceAlias") == alias }.mapNotNull { a -> a.vs("IPAddress")?.let { "$it/${a.vs("PrefixLength")}" } }
    fun traffic(alias: String): Pair<Long?, Long?> {
        val row = r.va("statistics").map { it.jsonObject }.firstOrNull { it.vs("Name") == alias } ?: return null to null
        return row.vs("ReceivedBytes")?.toLongOrNull() to row.vs("SentBytes")?.toLongOrNull()
    }
    val adapters = r.va("wireguardAdapters").map { it.jsonObject }
    return r.va("services").map { it.jsonObject }.map { p ->
        val name = p.vs("Name").orEmpty().substringAfter('$'); val alias = adapters.firstOrNull { it.vs("Name") == name }?.vs("Name") ?: name; val (rx, tx) = traffic(alias)
        VpnTunnel("wg:$name", VpnKind.WireGuard, name, if(p.vs("Status") in listOf("Running", "4")) VpnState.Up else VpnState.Down, alias, addresses(alias), canToggle = true, toggleNeedsAdmin = true, canRemove = true, rxBytes = rx, txBytes = tx)
    } + r.va("ras").map { it.jsonObject }.distinctBy { it.vs("Name") }.map { p ->
        val name = p.vs("Name").orEmpty(); val (rx, tx) = traffic(name); VpnTunnel("ras:$name", vpnKind(p.vs("TunnelType").orEmpty()), name, if(p.vs("ConnectionStatus") == "Connected") VpnState.Up else VpnState.Down, name, addresses(name), p.vs("ServerAddress")?.let { "Server $it" }, canToggle = true, rxBytes = rx, txBytes = tx)
    } + r.va("adapters").map { it.jsonObject }.map { p -> val name = p.vs("Name").orEmpty(); val (rx, tx) = traffic(name); VpnTunnel("nic:$name", vpnKind(p.vs("InterfaceDescription").orEmpty()), name, if(p.vs("Status") == "Up") VpnState.Up else VpnState.Down, name, addresses(name), rxBytes = rx, txBytes = tx) }
}
fun matchRouteTunnel(alias: String?, address: String?, tunnels: List<VpnTunnel>): VpnTunnel? {
    val ts = tunnels.firstOrNull { it.kind == VpnKind.Tailscale && it.state == VpnState.Up }
    val ip = address?.let(ScanRules::ipv4)
    if(alias.equals("Tailscale", true) || alias == "tailscale0" || (ip != null && ip ushr 22 == 0x191L) || address?.lowercase()?.startsWith("fd7a:115c:a1e0:") == true) if(ts != null) return ts
    return alias?.let { a -> tunnels.firstOrNull { it.interfaceName == a } ?: tunnels.firstOrNull { it.name == a } }
}
