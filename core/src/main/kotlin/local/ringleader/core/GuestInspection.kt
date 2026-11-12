package local.ringleader.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class GuestPermissionException : IllegalStateException("Permission was refused. Check this token's resource permissions.")
data class SnapshotRequest(val name: String, val description: String = "", val includeRam: Boolean = false)
data class GuestMutation(val method: String, val path: String, val body: ByteArray)
fun snapshotNameValid(name: String) = name.matches(Regex("[A-Za-z][A-Za-z0-9_-]{1,39}")) && name.lowercase() !in setOf("current", "vzdump", "pending")
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
/** One row from `GET /pending`. [value] is the pending text. [delete] means the live entry is going away. */
data class PendingChange(val key: String, val value: String?, val delete: Boolean)
data class HardwareSummary(val fields: Map<String,String>, val disks: List<HardwareDevice>, val nics: List<HardwareDevice>, val pending: List<PendingChange> = emptyList(),
    val usb: List<UsbAttachment> = emptyList(), val pci: List<HardwareDevice> = emptyList())
data class GuestSnapshot(val name: String, val parent: String?, val time: Long?, val description: String?, val ram: Boolean, val current: Boolean,
    /** Proxmox `snapstate` (`prepare`, `delete`) while a snapshot is not complete; null for a complete one. */
    val snapstate: String? = null)
data class GuestBackup(
    val volume: String, val storage: String, val time: Long?, val size: Long?, val notes: String?, val protected: Boolean,
    /** PBS `verification.state`: "ok", "failed", or null when the backup has none. */
    val verification: String? = null,
    /** Verify task start, from the verification UPID. Null when the server did not send one. */
    val verifiedAt: Long? = null,
)
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
private val hardwareFieldKeys = listOf(
    "cores", "sockets", "cpu", "memory", "balloon", "swap", "machine", "bios", "agent", "scsihw",
    "boot", "ostype", "onboot", "vga", "serial0", "serial1", "serial2", "serial3", "tags", "description",
)
private val diskKey = Regex("(?:ide|sata|scsi|virtio|unused|mp|efidisk|tpmstate)[0-9]+|rootfs")
private val deviceOrder = compareBy<HardwareDevice>(
    { if (it.key == "rootfs") 0 else 1 },
    { it.key.takeWhile { ch -> !ch.isDigit() } },
    { it.key.dropWhile { ch -> !ch.isDigit() }.toIntOrNull() ?: 0 },
    { it.key },
)
/** Proxmox `Network.pm` model names. Match is on the property key, so the short names do not hide the longer ones. */
val nicModels = listOf(
    "e1000", "e1000-82540em", "e1000-82544gc", "e1000-82545em", "e1000e",
    "i82551", "i82557b", "i82559er", "ne2k_isa", "ne2k_pci", "pcnet", "rtl8139", "virtio", "vmxnet3",
)
fun nicModel(properties: Map<String, String>) = nicModels.firstOrNull { it in properties }
fun nicAddress(properties: Map<String, String>): String? {
    val model = nicModel(properties)
    return (model?.let { properties[it] } ?: properties["macaddr"] ?: properties["hwaddr"])?.takeIf { it.isNotBlank() }
}

/** MiB from a bare number or Proxmox's `[current=]<MiB>` memory property string. */
fun memoryMib(value: String): Double? {
    value.trim().toDoubleOrNull()?.let { return it }
    val parts = value.split(',')
    parts.firstNotNullOfOrNull { part ->
        val text = part.trim()
        if (text.startsWith("current=")) text.removePrefix("current=").trim().toDoubleOrNull() else null
    }?.let { return it }
    return parts.firstNotNullOfOrNull { it.trim().toDoubleOrNull() }
}
fun formatHardwareValue(key: String, value: String): String = when {
    key == "balloon" && value.trim() == "0" -> "Off"
    key in setOf("memory", "balloon", "swap") -> memoryMib(value)?.let { iec(it * 1048576.0) } ?: value
    key == "onboot" -> yesNo(value == "1")
    key == "boot" -> value.removePrefix("order=").replace(";", " → ")
    else -> value
}
fun pendingCaption(change: PendingChange): String = when {
    change.delete -> "Pending removal"
    change.value.isNullOrBlank() -> "Pending"
    else -> "Pending ${formatHardwareValue(change.key, change.value)}"
}
/** Mount path and the flags Proxmox stores on an `mp*` entry. */
fun mountCaption(device: HardwareDevice): String? {
    if (!device.key.startsWith("mp")) return null
    val flags = buildList {
        if (device.properties["ro"] == "1") add("read-only")
        when (device.properties["backup"]) { "0" -> add("no backup"); "1" -> add("backup") }
        if (device.properties["shared"] == "1") add("shared")
    }
    val path = device.properties["mp"]?.takeIf { it.isNotBlank() }
    if (path == null && flags.isEmpty()) return null
    return listOfNotNull(path?.let { "mount $it" }, flags.takeIf { it.isNotEmpty() }?.joinToString(", ")).joinToString(" · ")
}

fun parseHardware(body: String): HardwareSummary = hardwareFromConfig(payload(body) as? JsonObject ?: error("Guest configuration was not an object."))

/** Live values from `GET /pending`, plus a mark wherever `pending` or `delete` is set. */
fun hardwareFromPending(body: String): HardwareSummary {
    val rows = payload(body) as? JsonArray ?: error("Pending configuration was not a list.")
    val current = buildJsonObject {
        rows.forEach { element ->
            val row = element as? JsonObject ?: return@forEach
            val key = row.text("key") ?: return@forEach
            val value = row["value"] ?: return@forEach
            if (value !is JsonNull) put(key, value)
        }
    }
    val changes = rows.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val key = row.text("key") ?: return@mapNotNull null
        val pending = row["pending"]?.let { if (it is JsonNull) null else (it as? JsonPrimitive)?.content }
        val delete = pendingDelete(row)
        if (pending == null && !delete) null else PendingChange(key, pending, delete)
    }
    return hardwareFromConfig(current, changes)
}

private fun hardwareFromConfig(row: JsonObject, pending: List<PendingChange> = emptyList()): HardwareSummary {
    fun devices(pattern: Regex) = row.entries.filter { it.key.matches(pattern) }.map { (key, value) ->
        val raw = (value as? JsonPrimitive)?.contentOrNull.orEmpty()
        val parts = raw.split(','); val props = parts.filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val volume = parts.firstOrNull()?.takeIf { '=' !in it }
        HardwareDevice(key, volume, volume?.substringBefore(':')?.takeIf { ':' in volume }, props["size"], props)
    }.sortedWith(deviceOrder)
    val fields = hardwareFieldKeys.mapNotNull { key -> row.text(key)?.let { key to it } }.toMap()
    return HardwareSummary(fields, devices(diskKey), devices(Regex("net[0-9]+")), pending, parseUsbAttachments(row), devices(Regex("hostpci[0-9]+")))
}

private fun pendingDelete(row: JsonObject): Boolean {
    val raw = row["delete"] ?: return false
    if (raw is JsonNull) return false
    val primitive = raw as? JsonPrimitive ?: return false
    val number = primitive.intOrNull ?: primitive.content.toIntOrNull()
    return number == 1 || number == 2 || primitive.content == "true"
}

fun parseSnapshots(body: String): List<GuestSnapshot> = payload(body).jsonArray.map { it.jsonObject }.map {
    GuestSnapshot(it.text("name").orEmpty(),it.text("parent"),it.long("snaptime"),it.text("description"),it.flag("vmstate"),it.text("name") == "current",it.text("snapstate")?.takeIf { s -> s.isNotBlank() })
}
/** Depth-first. Siblings are ordered by snaptime, and `current` is last among its siblings. */
fun orderedSnapshots(rows: List<GuestSnapshot>): List<GuestSnapshot> {
    if (rows.isEmpty()) return rows
    val names = rows.map { it.name }.toSet()
    val byParent = rows.groupBy { row ->
        val parent = row.parent
        if (parent != null && parent != row.name && parent in names) parent else null
    }
    val order = Comparator<GuestSnapshot> { a, b ->
        val current = (if (a.current || a.name == "current") 1 else 0) - (if (b.current || b.name == "current") 1 else 0)
        if (current != 0) current else {
            val time = (a.time ?: Long.MAX_VALUE).compareTo(b.time ?: Long.MAX_VALUE)
            if (time != 0) time else a.name.compareTo(b.name)
        }
    }
    val out = mutableListOf<GuestSnapshot>()
    val seen = mutableSetOf<String>()
    fun walk(parent: String?) {
        for (row in byParent[parent].orEmpty().sortedWith(order)) {
            if (!seen.add(row.name)) continue
            out += row
            walk(row.name)
        }
    }
    walk(null)
    if (out.size < rows.size) rows.sortedWith(order).forEach { if (seen.add(it.name)) { out += it; walk(it.name) } }
    return out
}
fun snapshotRollbackAllowed(snapshot: GuestSnapshot) = !snapshot.current && snapshot.snapstate.isNullOrBlank()
/** Cycles and orphan parents remain displayable; never recursively trust server trees. */
fun snapshotDepth(snapshot: GuestSnapshot, rows: List<GuestSnapshot>): Int {
    val seen = mutableSetOf(snapshot.name); var parent = snapshot.parent; var depth = 0
    while(parent != null && seen.add(parent) && depth < 20) { val p = rows.firstOrNull { it.name == parent } ?: break; depth++; parent = p.parent }
    return depth
}
const val BACKUPS_NOT_SCANNED = "Not scanned"
const val BACKUPS_NONE_ACTIVE = "Not scanned. No active backup storage."
const val BACKUP_VERIFY_STALE_SECONDS = 30L * 24 * 60 * 60

/** Cluster storage is usable when its recorded status is available (or the storage API's "active"). */
fun backupStorageActive(status: String) = status.equals("available", ignoreCase = true) || status.equals("active", ignoreCase = true)

fun parseBackups(body: String, storage: String, vmid: Int): List<GuestBackup> = payload(body).jsonArray.map { it.jsonObject }.filter {
    it.text("content") == "backup" && it.long("vmid") == vmid.toLong()
}.map { row ->
    val verify = row["verification"] as? JsonObject
    val state = verify?.text("state")?.lowercase()?.let { if (it == "ok" || it == "failed") it else null }
    GuestBackup(row.text("volid").orEmpty(), storage, row.long("ctime"), row.long("size"), row.text("notes"), row.flag("protected"), state, upidStartEpoch(verify?.text("upid")))
}.sortedByDescending { it.time ?: Long.MIN_VALUE }

/** Reads each active storage on its own. A failure keeps the rows already read and names the storage. */
fun collectBackups(known: Boolean, active: List<String>, read: (String) -> List<GuestBackup>): InspectionSection<List<GuestBackup>> {
    if (!known) return InspectionSection(emptyList(), BACKUPS_NOT_SCANNED)
    if (active.isEmpty()) return InspectionSection(emptyList(), BACKUPS_NONE_ACTIVE)
    val rows = mutableListOf<GuestBackup>()
    val failures = mutableListOf<String>()
    for (id in active) {
        try { rows += read(id) }
        catch (e: CancellationException) { throw e }
        catch (e: SessionExpiredException) { throw e }
        catch (e: GuestPermissionException) { failures += "$id: Not permitted for this token" }
        catch (e: Exception) { failures += "$id: ${e.message ?: "Unavailable"}" }
    }
    val error = failures.takeIf { it.isNotEmpty() }?.joinToString("; ") { "Could not read $it" }
    return InspectionSection(rows.sortedByDescending { it.time ?: Long.MIN_VALUE }, error)
}

/** Label and whether the row should be drawn as a warning. None, failed, and a verify older than 30 days are the three cases. */
fun backupVerificationText(backup: GuestBackup, nowEpoch: Long = java.time.Instant.now().epochSecond): Pair<String, Boolean> = when (backup.verification) {
    null -> "Not verified" to false
    "failed" -> "Verification failed" to true
    "ok" -> {
        val stale = backup.verifiedAt != null && nowEpoch - backup.verifiedAt > BACKUP_VERIFY_STALE_SECONDS
        (if (stale) "Verified, older than 30 days" else "Verified") to stale
    }
    else -> backup.verification to false
}

private fun upidStartEpoch(upid: String?): Long? {
    if (upid.isNullOrBlank()) return null
    val match = Regex("^UPID:[^:]+:[0-9A-Fa-f]+:[0-9A-Fa-f]+:([0-9A-Fa-f]+):").find(upid) ?: return null
    return match.groupValues[1].toLongOrNull(16)
}

/** A refresh keeps any section the new read has not filled in yet, so the page does not go blank. */
fun mergeGuestInspection(previous: GuestInspection?, update: GuestInspection): GuestInspection {
    if (previous == null || previous.resource != update.resource) return update
    return update.copy(
        hardware = update.hardware.orPrevious(previous.hardware),
        snapshots = update.snapshots.orPrevious(previous.snapshots),
        backups = update.backups.orPrevious(previous.backups),
        history = update.history.orPrevious(previous.history),
        addresses = update.addresses.orPrevious(previous.addresses),
    )
}
private fun <T> InspectionSection<T>.orPrevious(previous: InspectionSection<T>) = if (value == null && error == null) previous else this
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

    /**
     * Publishes the report as each section arrives. Hardware is emitted first.
     * [storages] are active backup storage ids. [backupKnown] is false when this node has no backup storage on record.
     * The guest chart reads history on its own, so this read does not call rrddata.
     */
    suspend fun readPublishing(
        connection: Connection,
        guest: Guest,
        storages: List<String>,
        emit: (GuestInspection) -> Unit,
        backupKnown: Boolean = storages.isNotEmpty(),
        timeframe: String = "hour",
    ) {
        emit(read(connection, guest, storages, timeframe))
    }
}
class LiveGuestInspectionGateway(private val client: PveClient = PveClient()): GuestInspectionGateway {
    override suspend fun read(connection: Connection, guest: Guest, storages: List<String>, timeframe: String): GuestInspection {
        var last: GuestInspection? = null
        readPublishing(connection, guest, storages, { last = it }, backupKnown = storages.isNotEmpty(), timeframe = timeframe)
        return checkNotNull(last)
    }

    override suspend fun readPublishing(
        connection: Connection,
        guest: Guest,
        storages: List<String>,
        emit: (GuestInspection) -> Unit,
        backupKnown: Boolean,
        timeframe: String,
    ) {
        require(timeframe in historyTimeframes)
        boundGuestPath(connection, guest)
        coroutineScope {
            val hardware = async { io { hardwareSection(connection, guest) } }
            val snapshots = async { io { section { orderedSnapshots(parseSnapshots(client.guestRead(connection, guest, "/snapshot"))) } } }
            val backups = async { io { backupSection(connection, guest, storages, backupKnown) } }
            val addresses = async { io { addressSection(connection, guest) } }
            var current = GuestInspection(
                guest.resource, guest.node, timeframe,
                hardware.await(), InspectionSection(), InspectionSection(), InspectionSection(), InspectionSection(),
            )
            emit(current)
            val gate = Mutex()
            listOf(
                async { val value = snapshots.await(); gate.withLock { current = current.copy(snapshots = value); emit(current) } },
                async { val value = backups.await(); gate.withLock { current = current.copy(backups = value); emit(current) } },
                async { val value = addresses.await(); gate.withLock { current = current.copy(addresses = value); emit(current) } },
            ).awaitAll()
        }
    }

    private fun hardwareSection(connection: Connection, guest: Guest) = section {
        try { hardwareFromPending(client.guestRead(connection, guest, "/pending")) }
        catch (e: CancellationException) { throw e }
        catch (e: SessionExpiredException) { throw e }
        catch (e: GuestPermissionException) { throw e }
        catch (e: Exception) { parseHardware(client.guestRead(connection, guest, "/config?current=1")) }
    }

    private fun backupSection(connection: Connection, guest: Guest, storages: List<String>, known: Boolean) =
        collectBackups(known, storages) { parseBackups(client.backupRead(connection, guest, it), it, guest.id) }

    private fun addressSection(connection: Connection, guest: Guest): InspectionSection<List<String>> =
        // A stopped guest has no agent or interfaces to ask; PVE may answer that call with a refusal that reads as a permission error.
        if (guest.status != "running") InspectionSection(error = GUEST_OFF_ADDRESSES)
        else section { parseAddresses(client.guestRead(connection, guest, if (guest.kind == "VM") "/agent/network-get-interfaces" else "/interfaces"), guest.kind == "VM") }

    private suspend fun <T> io(block: () -> T): T = runInterruptible(Dispatchers.IO) { block() }

    private fun <T> section(block: () -> T): InspectionSection<T> = try { InspectionSection(block()) }
        catch (e: CancellationException) { throw e }
        catch (e: SessionExpiredException) { throw e }
        catch (e: GuestPermissionException) { InspectionSection(error = "Not permitted for this token") }
        catch (e: Exception) { InspectionSection(error = e.message ?: "Unavailable") }
}
fun demoInspection(guest: Guest, timeframe: String) = GuestInspection(guest.resource,guest.node,timeframe,
    InspectionSection(parseHardware("""{"data":{"cores":2,"memory":2048,"ostype":"l26","agent":"1","scsi0":"sample:vm-120-disk-0,size=16G","net0":"virtio=02:00:00:00:00:01,bridge=vmbr0,tag=10,firewall=1","description":"Sample notes","onboot":1,"tags":"demo"}}""")),
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
