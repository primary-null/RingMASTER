package local.ringleader.core

import java.time.Instant
import kotlinx.serialization.json.*

/*
 * Storage contents (0.21.4, read-only). Verified 2026-11-09 against the API viewer schema and pve-storage
 * src/PVE/API2/Storage/Content.pm (`index`) and src/PVE/Storage.pm (`volume_list`, `check_volume_access`).
 *
 *  GET /nodes/{node}/storage/{storage}/content[?content=<type>]
 *    permission  Datastore.Audit or Datastore.AllocateSpace on /storage/{storage}.
 *    parameters  content (iso, vztmpl, backup, images, rootdir, snippets, import), vmid (not used here).
 *    returns     volid, format, size | approximate-size, used, ctime, vmid, notes, protected, verification {state, upid}, plus
 *                `content` (the volume type), which the plugins set although the schema does not list it.
 *    filtering   Every volume goes through check_volume_access and one the identity may not read is dropped without an error
 *                (see [STORAGE_VISIBILITY_NOTE]). An empty list therefore does not prove an empty storage.
 *    activation  volume_list calls activate_storage for this one storage, which can mount NFS or CIFS. That is why a read is
 *                a deliberate click on one storage. The storage index `GET /nodes/{node}/storage` activates every enabled
 *                storage and stays forbidden at the transport.
 */

const val STORAGE_CONTENT_NOT_FOUND = "The storage content read returned HTTP 404."
const val STORAGE_CONTENT_REFUSED = "The storage content path was refused."
const val STORAGE_CONTENT_UNAVAILABLE = "The node could not read this storage. It may be offline, disabled or not mounted."
const val STORAGE_CONTENT_TIMEOUT = "The node did not answer within 20 s. Reading a storage can take longer when it must be mounted."
const val STORAGE_CONTENT_EMPTY_BODY = "The server returned no storage content."
const val STORAGE_CONTENT_DENIED = "Not permitted. Listing a storage needs Datastore.Audit or Datastore.AllocateSpace on it."
const val STORAGE_CONTENT_DIRECT_ONLY = "Storage contents are read from the node directly. Open a direct Proxmox VE connection."
const val STORAGE_NOT_RECORDED = "This storage is not in the recorded inventory. Nothing was requested."
const val STORAGE_CONTENT_TYPE_REFUSED = "That content type is not one Proxmox VE lists. Nothing was requested."
const val STORAGE_NODE_NOT_ONLINE = "The node of this storage is not online in the last inventory. Nothing was requested."
const val STORAGE_CONTENT_ACTIVATES = "Reading a storage's contents asks its node to connect that storage, which can mount it."
const val STORAGE_VISIBILITY_NOTE =
    "The server lists only volumes this identity may read. ISOs and templates need Datastore.Audit or Datastore.AllocateSpace; " +
        "disk images need Datastore.Audit and VM.Config.Disk on the guest; backups need Datastore.AllocateSpace and VM.Backup on the guest; " +
        "snippets need Datastore.Allocate, which shows everything. An empty list does not prove the storage is empty."

/** Content types Proxmox VE knows for a storage, in the order the filter chips use. */
val STORAGE_CONTENT_TYPES = listOf("iso", "vztmpl", "backup", "images", "rootdir", "snippets", "import")

/** Identity of a storage on one node of one connection. A shared storage is one key per node. */
data class StorageKey(val remote: String, val node: String, val storage: String)

/**
 * One volume. [content] is the volume type, or null when neither the row nor the volume id says. [sizeBytes] is exact;
 * [approximateSizeBytes] stands in for it on storages that cannot measure (an upper bound). [verification] is the last
 * verification state and only Proxmox Backup Server storages report one.
 */
data class StorageVolume(
    val volid: String,
    val content: String?,
    val format: String?,
    val sizeBytes: Long?,
    val approximateSizeBytes: Long?,
    val usedBytes: Long?,
    val createdAt: Instant?,
    val vmid: Int?,
    val notes: String?,
    val protected: Boolean,
    val verification: String?,
) {
    val shownSize: Long? get() = sizeBytes ?: approximateSizeBytes
}

data class StorageContentView(val key: StorageKey, val filter: String?, val volumes: List<StorageVolume>)

/**
 * Path of one storage's content list. The node must be a `pve-node` and the storage a `pve-storage-id`; both are encoded.
 * This is never the storage index (`/nodes/{node}/storage`), which would activate every storage on the node.
 */
fun storageContentPath(node: String, storage: String, content: String? = null): String {
    val nodeName = pveNodeNameOrNull(node) ?: throw IllegalArgumentException("The node name cannot be requested.")
    val storageId = pveStorageIdOrNull(storage) ?: throw IllegalArgumentException("The storage id cannot be requested.")
    val type = content?.also { require(it in STORAGE_CONTENT_TYPES) { "The content type cannot be requested." } }
    return "/api2/json/nodes/${encodePathSegment(nodeName)}/storage/${encodePathSegment(storageId)}/content" + (type?.let { "?content=$it" } ?: "")
}

private val collapsedSpace = Regex("\\s+")
private val contentTypeName = Regex("[a-z][a-z0-9-]{0,31}")
private val digits = Regex("\\d+")

private fun cleanLine(value: String?, max: Int): String? {
    val firstLine = value?.lineSequence()?.firstOrNull().orEmpty()
    val text = firstLine.filter { it == '\t' || !it.isISOControl() }.replace(collapsedSpace, " ").trim()
    return text.take(max).ifEmpty { null }
}

private fun JsonObject.str(key: String): String? {
    val value = this[key] as? JsonPrimitive ?: return null
    return if (value is JsonNull || !value.isString) null else value.content
}

private fun JsonObject.whole(key: String): Long? {
    val value = this[key] as? JsonPrimitive ?: return null
    if (value is JsonNull) return null
    return value.longOrNull?.takeIf { it >= 0 }
}

private fun JsonObject.flag(key: String): Boolean {
    val value = this[key] as? JsonPrimitive ?: return false
    if (value is JsonNull) return false
    value.booleanOrNull?.let { return it }
    return value.longOrNull == 1L
}

/** Volume type from the volume id when the row did not carry one: `storage:iso/x.iso`, `storage:backup/...`, `storage:subvol-...`. */
internal fun contentFromVolid(volid: String): String? {
    val name = volid.substringAfter(':', "")
    val prefix = name.substringBefore('/', "")
    return when {
        prefix in setOf("iso", "vztmpl", "backup", "snippets", "import") -> prefix
        name.startsWith("subvol-") || name.substringAfter('/').startsWith("subvol-") -> "rootdir"
        name.startsWith("vm-") || name.startsWith("base-") || name.substringAfter('/').let { it.startsWith("vm-") || it.startsWith("base-") } -> "images"
        else -> null
    }
}

fun parseStorageContent(raw: String): List<StorageVolume> {
    val root = (try { Json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }) ?: error(STORAGE_CONTENT_EMPTY_BODY)
    if (!root.containsKey("data")) error(STORAGE_CONTENT_EMPTY_BODY)
    val rows = when (val data = root["data"]) {
        is JsonNull -> return emptyList()
        is JsonArray -> data
        else -> error(STORAGE_CONTENT_EMPTY_BODY)
    }
    val seen = HashSet<String>()
    return rows.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val volid = row.str("volid")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 512 && it.none { c -> c.isISOControl() } } ?: return@mapNotNull null
        if (!seen.add(volid)) return@mapNotNull null
        val declared = row.str("content")?.trim()?.lowercase()?.takeIf { it.matches(contentTypeName) }
        val verification = (row["verification"] as? JsonObject)?.str("state")?.let { cleanLine(it, 40) }
        StorageVolume(
            volid = volid,
            content = declared ?: contentFromVolid(volid),
            format = cleanLine(row.str("format"), 32),
            sizeBytes = row.whole("size"),
            approximateSizeBytes = row.whole("approximate-size"),
            usedBytes = row.whole("used"),
            createdAt = row.whole("ctime")?.let { runCatching { Instant.ofEpochSecond(it) }.getOrNull() },
            vmid = row.whole("vmid")?.takeIf { it in 1..Int.MAX_VALUE }?.toInt(),
            notes = cleanLine(row.str("notes"), 240),
            protected = row.flag("protected"),
            verification = verification,
        )
    }
}

// ---- sorting and filtering -----------------------------------------------------------------------------------------

private fun naturalName(value: String): String = digits.replace(value.lowercase()) { it.value.padStart(12, '0') }

private fun <T : Comparable<T>> ordered(
    rows: List<StorageVolume>,
    descending: Boolean,
    nameOf: (StorageVolume) -> String,
    selector: (StorageVolume) -> T?,
): List<StorageVolume> {
    val present = rows.filter { selector(it) != null }
    val absent = rows.filter { selector(it) == null }.sortedBy(nameOf)
    val sorted = present.sortedWith(compareBy<StorageVolume> { selector(it) }.thenBy(nameOf))
    return (if (descending) sorted.reversed() else sorted) + absent
}

/** Sort keys match the column headers: Name, Type, Format, Size, Date, VM. A missing value sorts last in both directions. */
fun sortVolumes(rows: List<StorageVolume>, key: String, descending: Boolean): List<StorageVolume> {
    val names = HashMap<StorageVolume, String>(rows.size)
    val nameOf: (StorageVolume) -> String = { row -> names.getOrPut(row) { naturalName(row.volid) } }
    return when (key) {
        "Type" -> ordered(rows, descending, nameOf) { it.content }
        "Format" -> ordered(rows, descending, nameOf) { it.format }
        "Size" -> ordered(rows, descending, nameOf) { it.shownSize }
        "Date" -> ordered(rows, descending, nameOf) { it.createdAt }
        "VM" -> ordered(rows, descending, nameOf) { it.vmid }
        else -> ordered(rows, descending, nameOf) { nameOf(it) }
    }
}

/**
 * How many storages a header should name. A shared id counts once across nodes. A row that is not shared, including one
 * whose shared flag was not recorded, counts on its own. Shared byte totals are never added; see [sharedStorageReport].
 */
fun recordedStorageCount(rows: List<RecordedStorage>): Int {
    val sharedIds = rows.filter { it.shared == true }.map { it.id }.toSet()
    return sharedIds.size + rows.count { it.shared != true }
}

const val VOLUME_TYPE_ALL = "all"
const val VOLUME_TYPE_OTHER = "other"

/** [type] is [VOLUME_TYPE_ALL], [VOLUME_TYPE_OTHER] (anything not in [STORAGE_CONTENT_TYPES]) or one content type. */
fun filterVolumes(rows: List<StorageVolume>, type: String): List<StorageVolume> = when (type) {
    VOLUME_TYPE_ALL -> rows
    VOLUME_TYPE_OTHER -> rows.filter { it.content == null || it.content !in STORAGE_CONTENT_TYPES }
    else -> rows.filter { it.content == type }
}

/** Chips for the loaded rows: "all" first, then each known type that has rows, then "other" when needed. */
fun volumeTypeCounts(rows: List<StorageVolume>): List<Pair<String, Int>> {
    val known = STORAGE_CONTENT_TYPES.map { it to rows.count { row -> row.content == it } }.filter { it.second > 0 }
    val other = rows.count { it.content == null || it.content !in STORAGE_CONTENT_TYPES }
    return listOf(VOLUME_TYPE_ALL to rows.size) + known + (if (other > 0) listOf(VOLUME_TYPE_OTHER to other) else emptyList())
}

// ---- demo ----------------------------------------------------------------------------------------------------------

private const val DEMO_MIB = 1024L * 1024
private const val DEMO_GIB_BYTES = 1024L * DEMO_MIB
private const val DEMO_EPOCH = 1_794_000_000L

/**
 * Sample volumes for a recorded demo storage, one set per content type the row lists. Deterministic and labelled as sample
 * data by the caller. Proxmox Backup Server rows get verification states, as that is the only plugin that reports them.
 */
fun demoStorageContent(storage: RecordedStorage): List<StorageVolume> {
    fun volume(volid: String, content: String, format: String, size: Long?, vmid: Int? = null, ageDays: Int? = null, used: Long? = null,
        notes: String? = null, protected: Boolean = false, verification: String? = null) =
        StorageVolume("${storage.id}:$volid", content, format, size, null, used, ageDays?.let { Instant.ofEpochSecond(DEMO_EPOCH - it * 86_400L) },
            vmid, notes, protected, verification)
    val types = storage.content.orEmpty().split(',').map { it.trim() }.filter { it in STORAGE_CONTENT_TYPES }
    val pbs = storage.pluginType == "pbs"
    return types.flatMap { type ->
        when (type) {
            "images" -> listOf(
                volume("vm-120-disk-0", "images", "raw", 32 * DEMO_GIB_BYTES, 120, used = 11 * DEMO_GIB_BYTES),
                volume("vm-120-disk-1", "images", "raw", 128 * DEMO_GIB_BYTES, 120, 30, used = 40 * DEMO_GIB_BYTES),
                volume("vm-130-disk-0", "images", "raw", 16 * DEMO_GIB_BYTES, 130, 12, used = 3 * DEMO_GIB_BYTES),
            )
            "rootdir" -> listOf(volume("subvol-121-disk-0", "rootdir", "subvol", 8 * DEMO_GIB_BYTES, 121, 40, used = 2 * DEMO_GIB_BYTES))
            "iso" -> listOf(
                volume("iso/debian-13.1.0-amd64-netinst.iso", "iso", "iso", 783 * DEMO_MIB, ageDays = 9),
                volume("iso/ubuntu-26.04-live-server-amd64.iso", "iso", "iso", 2_100 * DEMO_MIB, ageDays = 3),
                volume("iso/virtio-win-0.1.266.iso", "iso", "iso", 708 * DEMO_MIB, ageDays = 160, notes = "Windows drivers"),
            )
            "vztmpl" -> listOf(
                volume("vztmpl/debian-13-standard_13.0-1_amd64.tar.zst", "vztmpl", "tzst", 123 * DEMO_MIB, ageDays = 21),
                volume("vztmpl/ubuntu-24.04-standard_24.04-2_amd64.tar.zst", "vztmpl", "tzst", 133 * DEMO_MIB, ageDays = 75),
            )
            "backup" -> if (pbs) listOf(
                volume("backup/vm/120/2026-11-07T02:00:01Z", "backup", "pbs-vm", 2_300 * DEMO_MIB, 120, 2, notes = "nightly", verification = "ok"),
                volume("backup/ct/121/2026-11-06T02:00:02Z", "backup", "pbs-ct", 540 * DEMO_MIB, 121, 3, protected = true, notes = "before upgrade", verification = "ok"),
                volume("backup/vm/130/2026-11-01T02:00:05Z", "backup", "pbs-vm", 1_200 * DEMO_MIB, 130, 8, verification = "failed"),
            ) else listOf(
                volume("backup/vzdump-qemu-120-2026_11_07-02_00_01.vma.zst", "backup", "vma.zst", 2_300 * DEMO_MIB, 120, 2, notes = "nightly"),
                volume("backup/vzdump-lxc-121-2026_11_06-02_00_02.tar.zst", "backup", "tar.zst", 540 * DEMO_MIB, 121, 3, notes = "before upgrade", protected = true),
                volume("backup/vzdump-qemu-130-2026_11_01-02_00_05.vma.zst", "backup", "vma.zst", 1_200 * DEMO_MIB, 130, 8),
            )
            "snippets" -> listOf(
                volume("snippets/user-data.yaml", "snippets", "snippet", 2 * 1024L, ageDays = 14),
                volume("snippets/hookscript.pl", "snippets", "snippet", 3 * 1024L, ageDays = 50, notes = "Pre-start check"),
            )
            "import" -> listOf(volume("import/debian-12-genericcloud-amd64.qcow2", "import", "qcow2", 420 * DEMO_MIB, ageDays = 33))
            else -> emptyList()
        }
    }
}
