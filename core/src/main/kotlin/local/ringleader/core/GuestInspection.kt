package local.ringleader.core

import kotlinx.serialization.json.*

class GuestPermissionException : IllegalStateException("Permission was refused. Check this token's resource permissions.")
data class SnapshotRequest(val name: String, val description: String = "", val includeRam: Boolean = false)
data class GuestMutation(val method: String, val path: String, val body: ByteArray)
fun snapshotNameValid(name: String) = name.matches(Regex("[A-Za-z][A-Za-z0-9_-]{1,39}")) && name !in listOf("current", "vzdump")
fun validSnapshotRequest(c: PowerConfirmation): Boolean {
    val snapshotAction = c.action in listOf(PowerAction.SnapshotCreate, PowerAction.SnapshotRollback, PowerAction.SnapshotDelete)
    val r = c.snapshot ?: return !snapshotAction
    return snapshotAction && snapshotNameValid(r.name) && (!r.includeRam || (c.action == PowerAction.SnapshotCreate && c.kind == "VM" && c.status == "running")) &&
        (c.action == PowerAction.SnapshotCreate || (r.description.isEmpty() && !r.includeRam))
}
fun snapshotMutation(c: PowerConfirmation): GuestMutation {
    require(validSnapshotRequest(c))
    val r = requireNotNull(c.snapshot)
    val base = guestStatusPath(confirmedGuest(c)).removeSuffix("/status/current") + "/snapshot"
    val path = when(c.action) {
        PowerAction.SnapshotCreate -> base
        PowerAction.SnapshotRollback -> "$base/${encodePathSegment(r.name)}/rollback"
        PowerAction.SnapshotDelete -> "$base/${encodePathSegment(r.name)}"
        else -> error("Not a snapshot action")
    }
    val body = if(c.action == PowerAction.SnapshotCreate) buildJsonObject {
        put("snapname", r.name)
        if(r.description.isNotEmpty()) put("description", r.description)
        if(c.kind == "VM") put("vmstate", r.includeRam)
    }.toString() else "{}"
    return GuestMutation(if(c.action == PowerAction.SnapshotDelete) "DELETE" else "POST", path, body.toByteArray(Charsets.UTF_8))
}

data class HardwareDevice(val key: String, val volume: String?, val storage: String?, val size: String?, val properties: Map<String,String>)
data class HardwareSummary(val fields: Map<String,String>, val disks: List<HardwareDevice>, val nics: List<HardwareDevice>, val pending: String?,
    val usb: List<UsbAttachment> = emptyList(), val pci: List<HardwareDevice> = emptyList())
data class GuestSnapshot(val name: String, val parent: String?, val time: Long?, val description: String?, val ram: Boolean, val current: Boolean)
data class GuestBackup(val volume: String, val storage: String, val time: Long?, val size: Long?, val notes: String?, val protected: Boolean)
data class HistoryPoint(val time: Double, val values: Map<String,Double?>)
data class InspectionSection<T>(val value: T? = null, val error: String? = null)
data class GuestInspection(
    val resource: ResourceId, val node: String, val timeframe: String,
    val hardware: InspectionSection<HardwareSummary>, val snapshots: InspectionSection<List<GuestSnapshot>>,
    val backups: InspectionSection<List<GuestBackup>>, val history: InspectionSection<List<HistoryPoint>>,
    val addresses: InspectionSection<List<String>>,
)
private fun payload(body: String) = Json.parseToJsonElement(body).jsonObject.getValue("data")
private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
private fun JsonObject.flag(key: String) = text(key) in listOf("1", "true")
fun parseHardware(body: String): HardwareSummary {
    val row = payload(body).jsonObject
    fun devices(pattern: Regex) = row.entries.filter { it.key.matches(pattern) }.map { (key,value) ->
        val raw = (value as? JsonPrimitive)?.contentOrNull.orEmpty()
        val parts = raw.split(','); val props = parts.filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val volume = parts.firstOrNull()?.takeIf { '=' !in it }
        HardwareDevice(key,volume,volume?.substringBefore(':')?.takeIf { ':' in volume },props["size"],props)
    }.sortedBy { it.key }
    val fields = listOf("cores","sockets","memory","balloon","swap","boot","ostype","onboot","vga","tags","description").mapNotNull { key -> row.text(key)?.let { key to it } }.toMap()
    return HardwareSummary(fields, devices(Regex("(?:ide|sata|scsi|virtio|unused|mp)[0-9]+|rootfs")), devices(Regex("net[0-9]+")), row["pending"]?.takeUnless { it is JsonNull }?.toString(),
        parseUsbAttachments(row), devices(Regex("hostpci[0-9]+")))
}
fun parseSnapshots(body: String): List<GuestSnapshot> = payload(body).jsonArray.map { it.jsonObject }.map {
    GuestSnapshot(it.text("name").orEmpty(),it.text("parent"),it.long("snaptime"),it.text("description"),it.flag("vmstate"),it.text("name") == "current")
}
/** Cycles and orphan parents remain displayable; never recursively trust server trees. */
fun snapshotDepth(snapshot: GuestSnapshot, rows: List<GuestSnapshot>): Int {
    val seen = mutableSetOf(snapshot.name); var parent = snapshot.parent; var depth = 0
    while(parent != null && seen.add(parent) && depth < 20) { val p = rows.firstOrNull { it.name == parent } ?: break; depth++; parent = p.parent }
    return depth
}
fun parseBackups(body: String, storage: String, vmid: Int): List<GuestBackup> = payload(body).jsonArray.map { it.jsonObject }.filter {
    it.text("content") == "backup" && it.long("vmid") == vmid.toLong()
}.map { GuestBackup(it.text("volid").orEmpty(),storage,it.long("ctime"),it.long("size"),it.text("notes"),it.flag("protected")) }.sortedByDescending { it.time }
val historyKeys = listOf("cpu","maxcpu","mem","maxmem","netin","netout","diskread","diskwrite","iowait","loadavg","memused","memtotal","swapused","swaptotal","pressurecpusome","pressureiosome","pressureiofull","pressurememorysome","pressurememoryfull")
fun parseHistory(body: String): List<HistoryPoint> = payload(body).jsonArray.mapNotNull {
    val row = it.jsonObject; val time = (row["time"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() } ?: return@mapNotNull null
    HistoryPoint(time,historyKeys.associateWith { key -> (row[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 } })
}.sortedBy { it.time }
/**
 * Guest addresses for display. Loopback and link-local addresses are left out; addresses on tunnel interfaces carry
 * the tunnel in brackets ("100.64.0.7 (Tailscale)") so a guest that is on a tailnet or WireGuard shows it.
 */
fun parseAddresses(body: String, vm: Boolean): List<String> {
    val data = payload(body)
    // A stopped container answers /interfaces with "data": null.
    val rows = if(vm) (data as? JsonObject)?.get("result") as? JsonArray ?: error("Guest agent not running")
        else data as? JsonArray ?: error("Container not running")
    return rows.flatMap { item ->
        val row = item.jsonObject
        val tunnel = guestTunnelLabel(row.text("name"))
        val ips = if(vm) (row["ip-addresses"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("ip-address") }
            else listOfNotNull(row.text("inet"),row.text("inet6"))
        ips.filterNot { ip -> ip.substringBefore('/').let { it.startsWith("127.") || it == "::1" || it.lowercase().startsWith("fe80:") } }
            .map { if (tunnel != null) "$it ($tunnel)" else it }
    }.distinct()
}

/** Tunnel software behind a guest interface name, or null for an ordinary interface. */
fun guestTunnelLabel(name: String?): String? {
    val n = name?.lowercase() ?: return null
    return when {
        n.startsWith("tailscale") -> "Tailscale"
        n.matches(Regex("wg[\\w.-]*")) -> "WireGuard"
        n.startsWith("zt") -> "ZeroTier"
        n.matches(Regex("tun\\d+")) -> "VPN"
        else -> null
    }
}
const val GUEST_OFF_ADDRESSES = "Guest is off"
interface GuestInspectionGateway {
    suspend fun read(connection: Connection, guest: Guest, storages: List<String>, timeframe: String): GuestInspection
}
class LiveGuestInspectionGateway(private val client: PveClient = PveClient()): GuestInspectionGateway {
    override suspend fun read(connection: Connection, guest: Guest, storages: List<String>, timeframe: String): GuestInspection = kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
        require(timeframe in historyTimeframes); boundGuestPath(connection,guest)
        fun <T> section(block: () -> T): InspectionSection<T> = try { InspectionSection(block()) }
        catch(e: SessionExpiredException) { throw e }
        catch(e: GuestPermissionException) { InspectionSection(error = "Not permitted for this token") }
        catch(e: Exception) { InspectionSection(error = e.message ?: "Unavailable") }
        GuestInspection(guest.resource,guest.node,timeframe,
            section { parseHardware(client.guestRead(connection,guest,"/config")) },
            section { parseSnapshots(client.guestRead(connection,guest,"/snapshot")) },
            section { storages.flatMap { parseBackups(client.backupRead(connection,guest,it),it,guest.id) } },
            section { parseHistory(client.guestRead(connection,guest,historySuffix(timeframe,"AVERAGE"))) },
            // A stopped guest has no agent or interfaces to ask; PVE may answer that call with a refusal that reads as a permission error.
            if(guest.status != "running") InspectionSection(error = GUEST_OFF_ADDRESSES)
            else section { parseAddresses(client.guestRead(connection,guest,if(guest.kind == "VM") "/agent/network-get-interfaces" else "/interfaces"),guest.kind == "VM") })
    }
}
fun demoInspection(guest: Guest, timeframe: String) = GuestInspection(guest.resource,guest.node,timeframe,
    InspectionSection(parseHardware("""{"data":{"cores":2,"memory":2048,"scsi0":"sample:vm-120-disk-0,size=16G","net0":"virtio=02:00:00:00:00:01,bridge=vmbr0,tag=10,firewall=1","description":"Sample notes","onboot":1,"tags":"demo"}}""")),
    InspectionSection(listOf(GuestSnapshot("baseline",null,1794000000,"Sample snapshot",false,false),GuestSnapshot("current","baseline",null,"Current sample state",false,true))),
    InspectionSection(listOf(GuestBackup("sample:backup/example","sample",1794000000,104857600,"Sample backup",true))),
    InspectionSection((0..59).map { n ->
        val now = java.time.Instant.now().epochSecond.toDouble(); val wave = kotlin.math.sin(n / 6.0)
        HistoryPoint(now - (59 - n) * historySeconds(timeframe) / 59.0, mapOf(
            "maxcpu" to 2.0, "maxmem" to 2147483648.0, "cpu" to (0.12 + 0.08 * wave + (n % 9) / 300.0), "mem" to (1.6e9 + 2.0e8 * wave),
            "netin" to (18_000.0 + 9_000.0 * kotlin.math.abs(wave)), "netout" to (6_000.0 + 3_000.0 * kotlin.math.abs(wave)),
            "diskread" to (40_000.0 + 25_000.0 * (n % 5)), "diskwrite" to (22_000.0 + 12_000.0 * (n % 4))))
    }),
    InspectionSection(listOf("192.0.2.10 (sample)")))
