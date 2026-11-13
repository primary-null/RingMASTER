package local.ringleader.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Proxmox `pve-node`: one alphanumeric, or an alphanumeric run with a trailing alphanumeric. No dot, underscore, slash, or scheme. */
private val pveNodeName = Regex("^([a-zA-Z0-9]([a-zA-Z0-9\\-]*[a-zA-Z0-9])?)\\z")

/** Proxmox `pve-storage-id`: length at least 2, letter first, alphanumeric last, either case. */
private val pveStorageId = Regex("^[A-Za-z][A-Za-z0-9\\-_.]*[A-Za-z0-9]\\z")

const val RECORDED_INDEX_ERROR =
    "The recorded node and storage index was not updated because two rows used the same identity. The previous index is unchanged."
const val LIVE_STATUS_NOT_REQUESTED = "Live status was not requested."
const val LIVE_STATUS_SAMPLE = "These figures are sample data. Live status was not requested."
const val NODE_STATUS_NOT_FOUND = "The node status read returned HTTP 404."
const val NODE_STATUS_REFUSED = "The node status path was refused."
const val NODE_STATUS_INCOMPLETE = "The node status read was missing a required field."
const val MEMBERSHIP_HA_NOTE = "Membership and HA are separate. HA is shown only when the recorded row included it."
const val SHARED_POOL_NOTE = "A shared figure is the pool as recorded on this node."
const val SHARED_DISAGREE_NOTE = "The nodes disagree."
const val RECORDED_CAPTION = "Recorded from the cluster resource list."
const val RECORDED_SAMPLE_CAPTION = "Recorded sample data."
const val LIVE_STATUS_CAPTION = "From the node status read."

fun pveNodeNameOrNull(value: String): String? = value.takeIf { it.matches(pveNodeName) }

fun pveStorageIdOrNull(value: String): String? = value.takeIf { it.length >= 2 && it.matches(pveStorageId) }

/**
 * Recorded cluster member. [disk] on the wire is root-filesystem bytes and is stored here as [rootBytes], never as storage.
 * Identity is [remote] plus [name]. Scope is empty. Absent metrics stay null. A present zero stays zero.
 */
data class RecordedMember(
    val remote: String,
    val name: String,
    val status: String,
    val haState: String?,
    val cpuFraction: Double?,
    val maxCpu: Double?,
    val memoryBytes: Long?,
    val maxMemoryBytes: Long?,
    val rootBytes: Long?,
    val maxRootBytes: Long?,
    val uptimeSeconds: Long?,
) {
    init {
        if (remote != DEMO_REMOTE_KEY) require(serverOrigin(remote).toString() == remote) { "The node is missing its connection identity." }
        require(pveNodeNameOrNull(name) != null) { "The node name cannot be recorded." }
    }
}

/**
 * Recorded storage on one node. [diskBytes] is storage bytes, not root-filesystem bytes.
 * Identity is [remote] plus [node] plus [id]. [shared] null means the flag was not JSON 0 or 1.
 */
data class RecordedStorage(
    val remote: String,
    val node: String,
    val id: String,
    val shared: Boolean?,
    val diskBytes: Long?,
    val maxDiskBytes: Long?,
    val status: String,
    val pluginType: String?,
    val content: String?,
) {
    init {
        if (remote != DEMO_REMOTE_KEY) require(serverOrigin(remote).toString() == remote) { "The storage row is missing its connection identity." }
        require(pveNodeNameOrNull(node) != null) { "The storage node cannot be recorded." }
        require(pveStorageIdOrNull(id) != null) { "The storage id cannot be recorded." }
    }
}

/** One live `GET /nodes/{node}/status` body. Root bytes stay separate from recorded storage bytes. */
data class NodeLiveStatus(
    val node: String,
    val cpuFraction: Double,
    val memoryUsedBytes: Long,
    val memoryTotalBytes: Long,
    val rootUsedBytes: Long,
    val rootTotalBytes: Long,
    val uptimeSeconds: Long,
    val pveVersion: String?,
) {
    init {
        require(pveNodeNameOrNull(node) != null) { "The node name cannot be recorded." }
        require(cpuFraction.isFinite() && cpuFraction >= 0.0) { NODE_STATUS_INCOMPLETE }
        require(memoryUsedBytes >= 0 && memoryTotalBytes >= 0 && rootUsedBytes >= 0 && rootTotalBytes >= 0 && uptimeSeconds >= 0) {
            NODE_STATUS_INCOMPLETE
        }
    }
}

/** No capacity total. [summedBytes] is always null so shared pools are not added across nodes. */
data class SharedStorageReport(val disagreements: Set<String>, val summedBytes: Long?)

fun sharedStorageReport(rows: List<RecordedStorage>): SharedStorageReport {
    val disagreements = rows.filter { it.shared == true }.groupBy { it.id }.filter { (_, group) ->
        group.map { it.diskBytes to it.maxDiskBytes }.toSet().size > 1
    }.keys
    return SharedStorageReport(disagreements, summedBytes = null)
}

/** Percent only for a fraction from 0 through 1. A larger finite value is unavailable, not a percent over 100. */
fun cpuUsagePercent(fraction: Double): Double? {
    if (!fraction.isFinite() || fraction < 0.0 || fraction > 1.0) return null
    return fraction * 100.0
}

internal data class RecordedIndex(val members: List<RecordedMember>, val storage: List<RecordedStorage>, val error: String?)

/**
 * Build the recorded index from cluster resource rows.
 * A bad name is skipped. A duplicate node or storage identity fails the whole index and does not return the new rows.
 */
internal fun recordedIndex(rows: JsonArray, remote: String): RecordedIndex {
    val members = mutableListOf<RecordedMember>()
    val storage = mutableListOf<RecordedStorage>()
    val memberNames = mutableSetOf<String>()
    val storageKeys = mutableSetOf<Pair<String, String>>()
    var duplicate = false
    rows.forEach { element ->
        val row = element as? JsonObject ?: return@forEach
        when (plainText(row, "type")) {
            "node" -> {
                val name = pveNodeNameOrNull(plainText(row, "node").orEmpty()) ?: return@forEach
                if (!memberNames.add(name)) {
                    duplicate = true
                    return@forEach
                }
                members += RecordedMember(
                    remote, name, plainStatus(row, "status"), optionalText(row, "hastate"),
                    jsonFraction(row, "cpu"), jsonFraction(row, "maxcpu"),
                    jsonWhole(row, "mem"), jsonWhole(row, "maxmem"),
                    jsonWhole(row, "disk"), jsonWhole(row, "maxdisk"),
                    jsonWhole(row, "uptime"),
                )
            }
            "storage" -> {
                val node = pveNodeNameOrNull(plainText(row, "node").orEmpty()) ?: return@forEach
                val id = pveStorageIdOrNull(plainText(row, "storage").orEmpty()) ?: return@forEach
                if (!storageKeys.add(node to id)) {
                    duplicate = true
                    return@forEach
                }
                storage += RecordedStorage(
                    remote, node, id, jsonZeroOne(row, "shared"),
                    jsonWhole(row, "disk"), jsonWhole(row, "maxdisk"),
                    plainStatus(row, "status"), optionalText(row, "plugintype"), optionalText(row, "content"),
                )
            }
        }
    }
    return if (duplicate) RecordedIndex(emptyList(), emptyList(), RECORDED_INDEX_ERROR) else RecordedIndex(members, storage, null)
}

/**
 * Direct live status path. PDM, a non-empty scope, a foreign origin, and a name that is not `pve-node` fail before the path is built.
 * The node argument is the recorded member name. It is not a guest route and not an address.
 */
fun directNodeStatusPath(connection: Connection, origin: String, node: String, scope: String): String {
    requireDirectRequest(connection)
    require(scope.isEmpty()) { DIRECT_GUEST_REFUSAL }
    require(origin == connection.remoteKey) { "This node belongs to a different connection." }
    val name = pveNodeNameOrNull(node) ?: throw IllegalArgumentException("The node name cannot be requested.")
    return "/api2/json/nodes/${encodePathSegment(name)}/status"
}

/** Offline, unknown, and every other membership build no status path. */
fun nodeStatusPathForMember(member: RecordedMember): String? {
    if (member.remote == DEMO_REMOTE_KEY || member.status != "online") return null
    val name = pveNodeNameOrNull(member.name) ?: return null
    return "/api2/json/nodes/${encodePathSegment(name)}/status"
}

fun parseNodeStatus(node: String, body: String): NodeLiveStatus {
    val name = pveNodeNameOrNull(node) ?: error("The node name cannot be requested.")
    val root = try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: error("The server returned no node status.")
    val row = root["data"] as? JsonObject ?: error("The server returned no node status.")
    val memory = row["memory"] as? JsonObject ?: error(NODE_STATUS_INCOMPLETE)
    val rootfs = row["rootfs"] as? JsonObject ?: error(NODE_STATUS_INCOMPLETE)
    return NodeLiveStatus(
        name,
        jsonFraction(row, "cpu") ?: error(NODE_STATUS_INCOMPLETE),
        jsonWhole(memory, "used") ?: error(NODE_STATUS_INCOMPLETE),
        jsonWhole(memory, "total") ?: error(NODE_STATUS_INCOMPLETE),
        jsonWhole(rootfs, "used") ?: error(NODE_STATUS_INCOMPLETE),
        jsonWhole(rootfs, "total") ?: error(NODE_STATUS_INCOMPLETE),
        jsonWhole(row, "uptime") ?: error(NODE_STATUS_INCOMPLETE),
        optionalText(row, "pveversion"),
    )
}

/**
 * Exact `/nodes`, `/nodes/{node}/storage` and `/nodes/{node}/storage/{storage}/status` reads, never sent as direct paths. The
 * storage index activates every enabled storage on the node. So does one storage's status read: pve-storage `read_status`
 * calls `storage_info` for every id and then picks one (research section 6). A trailing slash does not make either allowed.
 */
internal fun isForbiddenNodeRead(path: String): Boolean {
    val bare = path.substringBefore('?').trimEnd('/')
    if (bare == "/api2/json/nodes") return true
    return bare.matches(forbiddenStorageRead)
}

private val forbiddenStorageRead = Regex("^/api2/json/nodes/[^/]+/storage(/[^/]+/status)?$")

fun selectedRecordedMember(state: WorkspaceState): RecordedMember? {
    val name = state.selectedNode ?: return null
    val remote = if (state.demo) DEMO_REMOTE_KEY else state.address
    if (remote.isBlank()) return null
    return state.overview?.members?.singleOrNull { it.remote == remote && it.name == name }
}

/** Null means one live status read is allowed. Any other result must not open HTTP. */
internal fun nodeStatusRefusal(state: WorkspaceState, member: RecordedMember): String? {
    if (state.demo || member.remote == DEMO_REMOTE_KEY) return LIVE_STATUS_SAMPLE
    if (state.kind != DIRECT_PVE_KIND || !state.connected || state.address.isBlank() || member.remote != state.address) return LIVE_STATUS_NOT_REQUESTED
    if (state.stale || state.loading || state.overview == null) return LIVE_STATUS_NOT_REQUESTED
    if (state.overview.indexStale || state.overview.indexError != null) return LIVE_STATUS_NOT_REQUESTED
    if (member.status != "online" || pveNodeNameOrNull(member.name) == null) return LIVE_STATUS_NOT_REQUESTED
    val current = state.overview.members.singleOrNull { it.remote == member.remote && it.name == member.name }
    if (current == null || current.status != "online") return LIVE_STATUS_NOT_REQUESTED
    return null
}

fun liveNodeReadAvailable(state: WorkspaceState): Boolean {
    val member = selectedRecordedMember(state) ?: return false
    return nodeStatusRefusal(state, member) == null
}

private fun plainText(row: JsonObject, key: String): String? {
    val value = row[key] as? JsonPrimitive ?: return null
    if (!value.isString) return null
    return value.content
}

private fun plainStatus(row: JsonObject, key: String): String {
    val text = plainText(row, key)?.trim().orEmpty()
    if (text.isEmpty() || text.any { it.isISOControl() }) return ""
    return text.take(80)
}

private fun optionalText(row: JsonObject, key: String): String? {
    val text = plainText(row, key)?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    if (text.isEmpty() || text.any { it.isISOControl() }) return null
    return text.take(160)
}

private fun jsonWhole(row: JsonObject, key: String): Long? = jsonWhole(row[key])

private fun jsonWhole(element: JsonElement?): Long? {
    val value = element as? JsonPrimitive ?: return null
    if (value.isString) return null
    val content = value.content
    if (content.any { it == '.' || it == 'e' || it == 'E' }) return null
    return value.longOrNull?.takeIf { it >= 0 }
}

private fun jsonFraction(row: JsonObject, key: String): Double? {
    val value = row[key] as? JsonPrimitive ?: return null
    if (value.isString) return null
    val number = value.doubleOrNull ?: return null
    if (!number.isFinite() || number < 0.0) return null
    return number
}

/** JSON 0 or 1 only. A string, boolean, or other number stays unknown. */
private fun jsonZeroOne(row: JsonObject, key: String): Boolean? {
    val value = row[key] as? JsonPrimitive ?: return null
    if (value.isString) return null
    val content = value.content
    if (content.any { it == '.' || it == 'e' || it == 'E' }) return null
    return when (value.longOrNull) {
        0L -> false
        1L -> true
        else -> null
    }
}
