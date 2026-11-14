package local.ringleader.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.serialization.json.*

/*
 * The storage view's model (0.24, step 1). Pure: no I/O, no clock, no state. Built from the design in
 * reviews/2026-11-12-round3/ST0-STORAGE-DESIGN.md sections 1.1, 2.1, 5.2 and 5.3, on the research in ST0-STORAGE-RESEARCH.md.
 *
 * Where each number comes from:
 *  - used and total: the recorded row from `/cluster/resources` (`disk`, `maxdisk`), the node's last pvestatd sample.
 *  - free: total minus used of that row, named [freeTotalMinusUsed] everywhere. It is not Proxmox's `avail`: only the status
 *    reads return `avail`, and they activate every storage on the node, so they are never sent (see [isForbiddenNodeRead]).
 *  - usage over time: `GET /nodes/{node}/storage/{storage}/rrddata`, fields `used` and `total` ([parseStorageHistory]).
 *  - enabled, node restriction, retention: `GET /storage`, parsed through an allowlist ([parseStorageConfig]).
 *  - breakdowns: one content listing, so they are only as complete as the listing ([storageVisibility]).
 */

// ---- visibility -----------------------------------------------------------------------------------------------------

/** What the permission list says about how complete a listing of one storage is. */
sealed interface StorageVisibility {
    /** The permission list shows this identity sees every volume of the types this storage holds. Show nothing. */
    data object Complete : StorageVisibility
    /** Permissions not read, not readable, PDM, or demo without a sample dump. */
    data object Unknown : StorageVisibility
    /** Some content types or some guests' volumes are filtered out. [parts] is never empty. */
    data class Partial(val parts: List<HiddenPart>) : StorageVisibility {
        init { require(parts.isNotEmpty()) { "A partial verdict names what is hidden." } }
    }
}

/**
 * One reason volumes are hidden. [content] is the volume type, [missing] the privilege the identity lacks and [path] the ACL
 * path it lacks it on (`/storage/{id}`, `/vms` for a guest privilege on every guest, or `/vms/{id}` for one guest). [guests]
 * are the known guests that lack it. [allGuests] means it may apply to guests this identity cannot see at all.
 */
data class HiddenPart(val content: String, val missing: String, val path: String, val guests: List<Int> = emptyList(), val allGuests: Boolean = false)

/** The ACL path that stands for "any guest": an id that is never a guest, so the answer is what `/vms` and `/` propagate. */
private const val ANY_GUEST_PATH = "/vms/0"
private val listedGuestPath = Regex("^/vms/([1-9][0-9]{0,8})$")

/**
 * Which volumes a listing of [storage] leaves out for the identity in [dump], for the content types the storage holds.
 * Mirrors pve-storage `check_volume_access` (research section 5):
 *  - `Datastore.Allocate` on the storage shows everything.
 *  - `iso`, `vztmpl`, `import`: the listing itself needed Datastore.Audit or Datastore.AllocateSpace, so nothing is hidden.
 *  - `snippets`: shown only with Datastore.Allocate.
 *  - `backup`: Datastore.AllocateSpace on the storage, then VM.Backup on the guest.
 *  - `images`, `rootdir`: Datastore.Audit on the storage, then VM.Config.Disk on the guest.
 * A guest privilege is checked on every guest in [guestIds], on every `/vms/{id}` path the dump lists, and on a generic
 * guest path; when the generic path lacks it, [HiddenPart.allGuests] is set.
 *
 * Not covered, as the design's table decides: a backup or disk volume whose name names no owner is shown only with
 * Datastore.Allocate. Such volumes are rare (vzdump and the disk allocators always name the owner) and are not reported.
 */
fun storageVisibility(dump: PermissionDump?, storage: String, contentTypes: Set<String>, guestIds: List<Int>): StorageVisibility {
    if (dump == null) return StorageVisibility.Unknown
    val storagePath = "/storage/$storage"
    fun has(path: String, privilege: String) = privilege in effectivePrivileges(dump, path).privileges
    if (has(storagePath, "Datastore.Allocate")) return StorageVisibility.Complete
    val listedGuests = dump.paths.keys.mapNotNull { listedGuestPath.find(it)?.groupValues?.get(1)?.toIntOrNull() }
    val guests = (guestIds.filter { it > 0 } + listedGuests).distinct().sorted()
    fun guestPart(content: String, storagePrivilege: String, guestPrivilege: String): HiddenPart? {
        if (!has(storagePath, storagePrivilege)) return HiddenPart(content, storagePrivilege, storagePath, allGuests = true)
        val lacking = guests.filter { !has("/vms/$it", guestPrivilege) }
        val generic = !has(ANY_GUEST_PATH, guestPrivilege)
        if (lacking.isEmpty() && !generic) return null
        val path = if (generic || lacking.size != 1) "/vms" else "/vms/${lacking.single()}"
        return HiddenPart(content, guestPrivilege, path, lacking, allGuests = generic)
    }
    val parts = STORAGE_CONTENT_TYPES.filter { it in contentTypes }.mapNotNull { type ->
        when (type) {
            "snippets" -> HiddenPart("snippets", "Datastore.Allocate", storagePath)
            "backup" -> guestPart("backup", "Datastore.AllocateSpace", "VM.Backup")
            "images", "rootdir" -> guestPart(type, "Datastore.Audit", "VM.Config.Disk")
            else -> null
        }
    }
    return if (parts.isEmpty()) StorageVisibility.Complete else StorageVisibility.Partial(parts)
}

/** The muted line for [StorageVisibility.Unknown]. The "Why?" tip beside it says [STORAGE_VISIBILITY_WHY]. */
const val STORAGE_VISIBILITY_UNKNOWN_LINE = "Showing what this account may see."
/** Empty listing, verdict [StorageVisibility.Complete]. */
const val STORAGE_EMPTY_COMPLETE = "This storage holds no volumes."
/** Empty listing, any other verdict. */
const val STORAGE_EMPTY_FILTERED = "The server listed no volumes for this account."
/** Caption of every breakdown panel when the verdict is not [StorageVisibility.Complete]. */
const val STORAGE_PARTIAL_CAPTION = "of what this account may see"
/** At most this many [hiddenPartText] lines are drawn; the rest go in the tip as "and N more". */
const val STORAGE_HIDDEN_LINES = 3

private fun contentNoun(content: String): String = when (content) {
    "backup" -> "Backups"
    "images" -> "Disk images"
    "rootdir" -> "Container volumes"
    "snippets" -> "Snippets"
    "iso" -> "ISO images"
    "vztmpl" -> "Container templates"
    "import" -> "Import files"
    else -> "Volumes"
}

private fun guestList(ids: List<Int>): String = when {
    ids.size > 3 -> "${ids.size} guests"
    ids.size == 1 -> ids.single().toString()
    else -> ids.dropLast(1).joinToString(", ") + " and " + ids.last()
}

/**
 * The plain sentence for one hidden part, as the design's section 1.2 words it. Guest IDs are in the text, so the page draws the
 * line through its showcase concealment. More than three guests become "on N guests".
 */
fun hiddenPartText(part: HiddenPart): String {
    val noun = contentNoun(part.content)
    val storage = part.path.removePrefix("/storage/")
    return when {
        part.missing.startsWith("Datastore.") && part.content == "snippets" -> "$noun are hidden: they need ${part.missing} on $storage."
        part.missing.startsWith("Datastore.") -> "$noun are hidden: this token lacks ${part.missing} on $storage."
        part.allGuests -> "$noun of guests this token cannot see may be hidden: it lacks ${part.missing} on /vms."
        else -> {
            val count = part.guests.size
            "$noun of $count ${if (count == 1) "guest" else "guests"} are hidden: this token lacks ${part.missing} on ${guestList(part.guests)}."
        }
    }
}

/**
 * Whether the storage list itself (from `/cluster/resources`) is known to be complete: it leaves out every storage without
 * Datastore.Audit. True when Audit propagates to any storage and no listed `/storage/{id}` path replaces it with a role that
 * lacks it; false when either fails; null when the permissions are not known.
 */
fun storageListComplete(dump: PermissionDump?): Boolean? {
    if (dump == null) return null
    val generic = "Datastore.Audit" in effectivePrivileges(dump, "/storage/0-any").privileges
    if (!generic) return false
    return dump.paths.keys.filter { it.startsWith("/storage/") }.all { "Datastore.Audit" in effectivePrivileges(dump, it).privileges }
}

// ---- fill levels and the thin rule ------------------------------------------------------------------------------------

/** How full a storage is: Normal below 85 %, High from 85 %, Critical from 95 %. */
enum class FillLevel { Normal, High, Critical }

const val FILL_HIGH_FRACTION = 0.85
const val FILL_CRITICAL_FRACTION = 0.95

/** used / total, clamped to 0..1 (an over-full sample is drawn full, not over). Null without both figures or with a zero total. */
fun usedFraction(used: Long?, total: Long?): Double? {
    if (used == null || total == null || total <= 0 || used < 0) return null
    return (used.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
}

fun fillLevel(used: Long?, total: Long?): FillLevel? {
    val fraction = usedFraction(used, total) ?: return null
    return when {
        fraction >= FILL_CRITICAL_FRACTION -> FillLevel.Critical
        fraction >= FILL_HIGH_FRACTION -> FillLevel.High
        else -> FillLevel.Normal
    }
}

fun fillLevel(row: RecordedStorage): FillLevel? = fillLevel(row.diskBytes, row.maxDiskBytes)

/** What the page calls the gap between total and used, so it is never read as Proxmox's "available". */
const val STORAGE_FREE_LABEL = "free (total − used)"

/**
 * Total minus used, floored at 0, from the same sample. This is not Proxmox's `avail`, which only the forbidden status reads
 * return; it can be larger than what a guest could write (reserved blocks, ZFS reservations).
 */
fun freeTotalMinusUsed(used: Long?, total: Long?): Long? {
    if (used == null || total == null || used < 0 || total < 0) return null
    return (total - used).coerceAtLeast(0)
}

/** Storage plugin types whose volumes are thin by their nature (research section 3). ZFS over iSCSI is `zfs`. */
val THIN_STORAGE_TYPES = setOf("lvmthin", "zfspool", "zfs", "rbd")

/** A type rule, said as "thin", never measured: a thin plugin type, or a qcow2 disk in the listing. */
fun isThinStorage(type: String?, volumes: List<StorageVolume>?): Boolean {
    if (type != null && type.lowercase() in THIN_STORAGE_TYPES) return true
    return volumes.orEmpty().any { it.format.equals("qcow2", ignoreCase = true) && it.content in DISK_CONTENT }
}

private val DISK_CONTENT = setOf("images", "rootdir")

/** Sum of the listed sizes of guest disks (`images`, `rootdir`). Other volumes and unknown sizes are left out. */
fun provisionedBytes(volumes: List<StorageVolume>): Long =
    volumes.filter { it.content in DISK_CONTENT }.sumOf { it.shownSize ?: 0L }

// ---- breakdowns -----------------------------------------------------------------------------------------------------

/**
 * Space per content type over one bar. [byContent] is largest first (unknown types under [VOLUME_TYPE_OTHER]); [listedBytes]
 * is their sum. [notListed] is used minus listed, floored at 0: files Proxmox does not list plus volumes hidden from this
 * account, never "free". [freeTotalMinusUsed] is total minus used. [scale] is what the bar is drawn over: the larger of the
 * total and the listed sum (a thin storage can list more than it holds; then [overProvisioned] is true).
 */
data class StorageBreakdown(
    val byContent: List<Pair<String, Long>>,
    val listedBytes: Long,
    val notListed: Long?,
    val freeTotalMinusUsed: Long?,
    val scale: Long,
    val overProvisioned: Boolean,
)

fun storageBreakdown(volumes: List<StorageVolume>, used: Long?, total: Long?): StorageBreakdown {
    val sums = LinkedHashMap<String, Long>()
    for (volume in volumes) {
        val size = volume.shownSize ?: continue
        val key = volume.content?.takeIf { it in STORAGE_CONTENT_TYPES } ?: VOLUME_TYPE_OTHER
        sums[key] = (sums[key] ?: 0L) + size
    }
    val byContent = sums.entries.filter { it.value > 0 }.map { it.key to it.value }
        .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
    val listed = byContent.sumOf { it.second }
    val cleanUsed = used?.takeIf { it >= 0 }
    val cleanTotal = total?.takeIf { it >= 0 }
    return StorageBreakdown(
        byContent = byContent,
        listedBytes = listed,
        notListed = cleanUsed?.let { (it - listed).coerceAtLeast(0) },
        freeTotalMinusUsed = freeTotalMinusUsed(cleanUsed, cleanTotal),
        scale = maxOf(cleanTotal ?: cleanUsed ?: 0L, listed),
        overProvisioned = cleanTotal != null && listed > cleanTotal,
    )
}

/** Disk space of one guest on this storage: the sum of its `images` and `rootdir` volumes. */
data class GuestUsage(val vmid: Int, val bytes: Long, val volumes: Int)

/** Per guest, largest first, then by VMID. Volumes without a VMID and every non-disk volume (backups too) are left out. */
fun guestUsage(volumes: List<StorageVolume>): List<GuestUsage> =
    volumes.filter { it.content in DISK_CONTENT && it.vmid != null }.groupBy { it.vmid!! }
        .map { (vmid, rows) -> GuestUsage(vmid, rows.sumOf { it.shownSize ?: 0L }, rows.size) }
        .sortedWith(compareByDescending<GuestUsage> { it.bytes }.thenBy { it.vmid })

/** The [limit] largest volumes with a known size, largest first, then by natural name. */
fun largestVolumes(volumes: List<StorageVolume>, limit: Int = 8): List<StorageVolume> {
    require(limit >= 0)
    return volumes.filter { it.shownSize != null }
        .sortedWith(compareByDescending<StorageVolume> { it.shownSize }.thenBy { naturalName(it.volid) })
        .take(limit)
}

/** One guest's backups on this storage. [times] are the known backup times, oldest first; a backup without one still counts. */
data class BackupSpan(
    val vmid: Int, val count: Int, val bytes: Long, val oldest: Instant?, val newest: Instant?,
    val protected: Int, val times: List<Instant>,
)

private val vzdumpName = Regex("vzdump-(?:qemu|lxc|openvz)-\\d+-(\\d{4})_(\\d{2})_(\\d{2})-(\\d{2})_(\\d{2})_(\\d{2})")
private val pbsName = Regex("/(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z)$")

/**
 * When a backup was made: the listing's `ctime` when present, else the time in its name. A Proxmox Backup Server name carries
 * UTC; a `vzdump-…-YYYY_MM_DD-HH_MM_SS` name carries the node's local time, which RingMASTER does not know, so it is read
 * as UTC and can be off by the node's offset. Null when neither says.
 */
fun backupTime(volume: StorageVolume): Instant? {
    volume.createdAt?.let { return it }
    pbsName.find(volume.volid)?.let { match -> runCatching { Instant.parse(match.groupValues[1]) }.getOrNull()?.let { return it } }
    val match = vzdumpName.find(volume.volid) ?: return null
    val (y, mo, d, h, mi, s) = match.destructured
    return runCatching { LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt()).toInstant(ZoneOffset.UTC) }.getOrNull()
}

/** Backups per guest, by VMID. Backups without a VMID are left out. */
fun backupSpans(volumes: List<StorageVolume>): List<BackupSpan> =
    volumes.filter { it.content == "backup" && it.vmid != null }.groupBy { it.vmid!! }.toSortedMap().map { (vmid, rows) ->
        val times = rows.mapNotNull(::backupTime).sorted()
        BackupSpan(vmid, rows.size, rows.sumOf { it.shownSize ?: 0L }, times.firstOrNull(), times.lastOrNull(), rows.count { it.protected }, times)
    }

// ---- the list of storages: per node, and shared ids grouped ----------------------------------------------------------

/**
 * One shared storage id drawn once. [rows] is what each node reported, sorted by node name (the per-node truth; nothing is
 * added). [shown] is the row whose figures the card uses: the first online node, by name, with a sample; else the first
 * node with a sample (its last recorded figures); else the first online node; else the first row. [disagree] means that two nodes with a sample report a different
 * `disk` or `maxdisk`. [usedRange] spans the used fractions of the sampled rows, for a ring that shows the range.
 */
data class SharedStorageGroup(
    val id: String,
    val rows: List<RecordedStorage>,
    val shown: RecordedStorage,
    val disagree: Boolean,
    val usedRange: ClosedFloatingPointRange<Double>?,
) {
    val nodes: List<String> get() = rows.map { it.node }
}

/** One node's local storages (shared flag 0 or not recorded), sorted by natural name. [online] is the recorded membership. */
data class NodeStorageGroup(val node: String, val online: Boolean, val rows: List<RecordedStorage>)

/**
 * Both views of the recorded storage list. [perNode] is every row as the server reported it, one per node and id. [shared]
 * and [nodes] are the page's grouping: each shared id once, then each node's local storages. [disabled] are configuration
 * rows marked disabled, which the resource list leaves out; empty when the configuration was not read.
 */
data class StorageListModel(
    val perNode: List<RecordedStorage>,
    val shared: List<SharedStorageGroup>,
    val nodes: List<NodeStorageGroup>,
    val disabled: List<StorageConfigRow>,
)

/** Two figures that differ by any byte disagree, as the contract says. Rows without a sample take no part. */
private fun hasSample(row: RecordedStorage) = row.diskBytes != null || row.maxDiskBytes != null

fun storageListModel(rows: List<RecordedStorage>, members: List<RecordedMember>, config: List<StorageConfigRow>? = null): StorageListModel {
    val online = members.filter { it.status == "online" }.map { it.remote to it.name }.toSet()
    fun isOnline(row: RecordedStorage) = (row.remote to row.node) in online
    val byNode = compareBy<RecordedStorage>({ naturalName(it.node) }, { it.node })
    val sorted = rows.sortedWith(byNode.thenBy { naturalName(it.id) }.thenBy { it.id })
    val shared = sorted.filter { it.shared == true }.groupBy { it.remote to it.id }.map { (key, group) ->
        val ordered = group.sortedWith(byNode)
        val sampled = ordered.filter(::hasSample)
        val shown = sampled.firstOrNull(::isOnline) ?: sampled.firstOrNull() ?: ordered.firstOrNull(::isOnline) ?: ordered.first()
        val fractions = sampled.mapNotNull { usedFraction(it.diskBytes, it.maxDiskBytes) }
        SharedStorageGroup(
            id = key.second,
            rows = ordered,
            shown = shown,
            disagree = sampled.map { it.diskBytes to it.maxDiskBytes }.toSet().size > 1,
            usedRange = if (fractions.isEmpty()) null else fractions.min()..fractions.max(),
        )
    }.sortedWith(compareBy({ naturalName(it.id) }, { it.id }))
    val locals = sorted.filter { it.shared != true }
    val nodes = locals.groupBy { it.node }.map { (name, group) -> NodeStorageGroup(name, group.any(::isOnline), group) }
    val recordedIds = rows.map { it.id }.toSet()
    val disabled = config.orEmpty().filter { it.disabled && it.storage !in recordedIds }
        .sortedWith(compareBy({ naturalName(it.storage) }, { it.storage }))
    return StorageListModel(sorted, shared, nodes, disabled)
}

/** Sort orders of the storage search (shown from [STORAGE_SEARCH_FROM] storages). */
enum class StorageSort { Name, UsedPercent, Free }

/** The list gets a search field and a sort control from this many storages (a shared id counts once). */
const val STORAGE_SEARCH_FROM = 10

/** A row matches when every word of [query] is in its id, node or plugin type, ignoring case. A blank query matches all. */
fun storageMatches(row: RecordedStorage, query: String): Boolean {
    val words = query.lowercase().split(' ', '\t', '·').map { it.trim() }.filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val haystack = listOfNotNull(row.id, row.node, row.pluginType, row.content).joinToString(" ").lowercase()
    return words.all { it in haystack }
}

/** Name: natural name. Used %: fullest first. Free: most total − used first. Unknown figures sort last; ties by name. */
fun sortStorageRows(rows: List<RecordedStorage>, sort: StorageSort): List<RecordedStorage> {
    val byName = compareBy<RecordedStorage>({ naturalName(it.id) }, { naturalName(it.node) })
    return when (sort) {
        StorageSort.Name -> rows.sortedWith(byName)
        StorageSort.UsedPercent -> rows.sortedWith(compareBy<RecordedStorage> { usedFraction(it.diskBytes, it.maxDiskBytes)?.let { f -> -f } ?: Double.MAX_VALUE }.then(byName))
        StorageSort.Free -> rows.sortedWith(compareBy<RecordedStorage> { freeTotalMinusUsed(it.diskBytes, it.maxDiskBytes)?.let { f -> -f } ?: Long.MAX_VALUE }.then(byName))
    }
}

// ---- the storage configuration (GET /storage) -------------------------------------------------------------------------

const val STORAGE_CONFIG_PATH = "/api2/json/storage"
const val STORAGE_CONFIG_NOT_FOUND = "The storage configuration read returned HTTP 404."
const val STORAGE_CONFIG_REFUSED = "The storage configuration path was refused."
const val STORAGE_CONFIG_EMPTY_BODY = "The server returned no storage configuration."
const val STORAGE_CONFIG_DENIED = "Not permitted. The storage configuration needs Datastore.Audit or Datastore.AllocateSpace."
const val STORAGE_CONFIG_UNREAD = "Storage configuration could not be read."
const val STORAGE_VIEW_DIRECT_ONLY = "The storage configuration and history are read from Proxmox VE directly. Open a direct Proxmox VE connection."

/**
 * Backup retention as `prune-backups` writes it. Every field null means keep all (also `keep-all=1`).
 */
data class KeepRules(
    val last: Int? = null, val hourly: Int? = null, val daily: Int? = null,
    val weekly: Int? = null, val monthly: Int? = null, val yearly: Int? = null,
) {
    val keepAll: Boolean get() = listOf(last, hourly, daily, weekly, monthly, yearly).all { it == null }
}

/**
 * One storage definition, through the allowlist. Only these fields are kept: id, plugin type, content types, the disable
 * flag, the shared flag, the node restriction, the retention rules and the protected-backup limit. Everything that can name
 * a server, path, export, portal, user, pool, datastore, namespace, fingerprint or key is dropped by not being read.
 *
 * [nodes] null means every node; an empty set means the field was present but named no node this client can read.
 * [maxProtected] null means not set (Proxmox's default applies: unlimited with Datastore.Allocate, else 5) or unlimited (-1).
 */
data class StorageConfigRow(
    val storage: String,
    val type: String?,
    val content: Set<String>,
    val disabled: Boolean,
    val shared: Boolean?,
    val nodes: Set<String>?,
    val pruneBackups: KeepRules?,
    val maxProtected: Int?,
)

private val pluginTypeName = Regex("[a-z][a-z0-9]{0,31}")
private val contentToken = Regex("[a-z][a-z0-9-]{0,31}")
private val keepRule = Regex("(keep-(?:all|last|hourly|daily|weekly|monthly|yearly))=(\\d{1,6})")

private fun JsonObject.plain(key: String): String? {
    val value = this[key] as? JsonPrimitive ?: return null
    if (value is JsonNull || !value.isString) return null
    return value.content.takeIf { text -> text.none { it.isISOControl() } }
}

/** JSON 0/1, `true`/`false`, or the strings "0"/"1". Anything else is null. */
private fun JsonObject.zeroOne(key: String): Boolean? {
    val value = this[key] as? JsonPrimitive ?: return null
    if (value is JsonNull) return null
    value.booleanOrNull?.takeIf { !value.isString }?.let { return it }
    return when (value.content) { "0" -> false; "1" -> true; else -> null }
}

private fun JsonObject.integer(key: String): Long? {
    val value = this[key] as? JsonPrimitive ?: return null
    if (value is JsonNull) return null
    val text = value.content.trim()
    return text.takeIf { it.matches(Regex("-?\\d{1,9}")) }?.toLong()
}

/** `keep-last=3,keep-daily=7` → KeepRules(last = 3, daily = 7). `keep-all=1` → keep all. An unknown key or a bad value → null. */
fun parseKeepRules(text: String?): KeepRules? {
    val parts = text?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return null
    if (parts.isEmpty()) return null
    var rules = KeepRules()
    var keepAll = false
    for (part in parts) {
        val match = keepRule.matchEntire(part) ?: return null
        val n = match.groupValues[2].toInt()
        rules = when (match.groupValues[1]) {
            "keep-all" -> { keepAll = n == 1; rules }
            "keep-last" -> rules.copy(last = n)
            "keep-hourly" -> rules.copy(hourly = n)
            "keep-daily" -> rules.copy(daily = n)
            "keep-weekly" -> rules.copy(weekly = n)
            "keep-monthly" -> rules.copy(monthly = n)
            else -> rules.copy(yearly = n)
        }
    }
    return if (keepAll) KeepRules() else rules
}

/** `keep-last=3,keep-weekly=2`, in Proxmox's order. Keep all is `keep-all=1`. */
fun keepRulesText(rules: KeepRules): String {
    if (rules.keepAll) return "keep-all=1"
    return listOf("last" to rules.last, "hourly" to rules.hourly, "daily" to rules.daily, "weekly" to rules.weekly,
        "monthly" to rules.monthly, "yearly" to rules.yearly).filter { it.second != null }.joinToString(",") { "keep-${it.first}=${it.second}" }
}

/**
 * Parse `GET /storage` through the allowlist. A row whose id is not a `pve-storage-id` is skipped, and so is every row of an
 * id that appears twice. A body without a `data` array fails ([STORAGE_CONFIG_EMPTY_BODY]); `data: null` is an empty list.
 */
fun parseStorageConfig(body: String): List<StorageConfigRow> {
    val root = (try { Json.parseToJsonElement(body) as? JsonObject } catch (_: Exception) { null }) ?: error(STORAGE_CONFIG_EMPTY_BODY)
    if (!root.containsKey("data")) error(STORAGE_CONFIG_EMPTY_BODY)
    val rows = when (val data = root["data"]) {
        is JsonNull -> return emptyList()
        is JsonArray -> data
        else -> error(STORAGE_CONFIG_EMPTY_BODY)
    }
    val parsed = rows.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val id = row.plain("storage")?.let(::pveStorageIdOrNull) ?: return@mapNotNull null
        val nodes = row.plain("nodes")?.let { text ->
            text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull(::pveNodeNameOrNull).toSet()
        } ?: if (row.containsKey("nodes") && row["nodes"] !is JsonNull) emptySet() else null
        StorageConfigRow(
            storage = id,
            type = row.plain("type")?.trim()?.lowercase()?.takeIf { it.matches(pluginTypeName) },
            content = row.plain("content").orEmpty().split(',').map { it.trim().lowercase() }
                .filter { it.matches(contentToken) && it != "none" }.toSet(),
            disabled = row.zeroOne("disable") == true,
            shared = row.zeroOne("shared"),
            nodes = nodes,
            pruneBackups = parseKeepRules(row.plain("prune-backups")),
            maxProtected = row.integer("max-protected-backups")?.takeIf { it in 0..Int.MAX_VALUE }?.toInt(),
        )
    }
    val duplicated = parsed.groupBy { it.storage }.filterValues { it.size > 1 }.keys
    return parsed.filter { it.storage !in duplicated }
}

/** Whether a configuration row lets [node] use the storage. Null [StorageConfigRow.nodes] means every node. */
fun storageEnabledOn(row: StorageConfigRow, node: String): Boolean = !row.disabled && (row.nodes == null || node in row.nodes)

// ---- the storage history (GET /nodes/{node}/storage/{storage}/rrddata) -------------------------------------------------

const val STORAGE_HISTORY_NOT_FOUND = "The storage history read returned HTTP 404."
const val STORAGE_HISTORY_REFUSED = "The storage history path was refused."
const val STORAGE_HISTORY_EMPTY_BODY = "The server returned no storage history."
const val STORAGE_HISTORY_DENIED = "Not permitted for this token."
const val STORAGE_HISTORY_EMPTY = "No samples in this window. The node records a storage only while it is active."

/** The two series a storage records. `total` is drawn as the capacity line. */
val STORAGE_HISTORY_KEYS = listOf("used", "total")

/** `GET /nodes/{node}/storage/{storage}/rrddata?timeframe=&cf=`. Reads the RRD file only; it activates nothing. */
fun storageHistoryPath(node: String, storage: String, timeframe: String, cf: String): String {
    val nodeName = pveNodeNameOrNull(node) ?: throw IllegalArgumentException("The node name cannot be requested.")
    val storageId = pveStorageIdOrNull(storage) ?: throw IllegalArgumentException("The storage id cannot be requested.")
    return "/api2/json/nodes/${encodePathSegment(nodeName)}/storage/${encodePathSegment(storageId)}" + historySuffix(timeframe, cf)
}

/**
 * Parse a storage's rrddata: only `used` and `total` are kept; a negative or non-finite value becomes null (a gap, as for the
 * periods the storage was inactive). Rows without a finite time are dropped. Sorted by time. `data: null` is no samples.
 */
fun parseStorageHistory(body: String): List<HistoryPoint> {
    val root = (try { Json.parseToJsonElement(body) as? JsonObject } catch (_: Exception) { null }) ?: error(STORAGE_HISTORY_EMPTY_BODY)
    if (!root.containsKey("data")) error(STORAGE_HISTORY_EMPTY_BODY)
    val rows = when (val data = root["data"]) {
        is JsonNull -> return emptyList()
        is JsonArray -> data
        else -> error(STORAGE_HISTORY_EMPTY_BODY)
    }
    return rows.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val time = (row["time"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
        HistoryPoint(time, STORAGE_HISTORY_KEYS.associateWith { key ->
            (row[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }
        })
    }.sortedBy { it.time }
}
