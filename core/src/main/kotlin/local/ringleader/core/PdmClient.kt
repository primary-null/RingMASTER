package local.ringleader.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

const val PDM_LIST_PROVENANCE = "From the PDM resource list. No guest status request was sent."
const val PDM_DISK_NOTE = "Reported disk is not guest filesystem usage."
const val PDM_ACTIVITY_EXPLANATION =
    "This PDM connection does not include a task feed. RingMASTER does not call the direct cluster task list."
const val PDM_REQUEST_REFUSAL = "A direct connection cannot use the PDM resource list."

data class PdmRemoteRecord(val id: String, val product: String, val error: String? = null)

data class PdmResourceGroup(val scope: String, val error: String?, val guests: List<Guest>)

data class PdmFleetDocument(
    val version: String,
    val release: String,
    val repoid: String,
    val remotes: List<PdmRemoteRecord>,
    val groups: List<PdmResourceGroup>,
) {
    val serverLabel: String get() = "$version $release ($repoid)"
}

fun requirePdmRequest(connection: Connection) {
    require(connection.kind == PDM_KIND) { PDM_REQUEST_REFUSAL }
}

class PdmClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) {
    private val http = ProxmoxTransport(connectTimeoutMs, readTimeoutMs)

    private fun tracedPdm(address: String, tokenId: String, secret: String, pin: String, path: String): String {
        val span=Trace.begin(TraceSource.PdmApi, "GET $path", mapOf("url" to Redact.url(address + path)))
        try {
            return http.pdm(address, tokenId, secret, pin, path).also { span.end(status=200,bytesIn=it.toByteArray(Charsets.UTF_8).size.toLong()) }
        } catch(t: Throwable) { span.end(error=t); throw t }
    }

    fun read(connection: Connection): PdmFleetDocument {
        requirePdmRequest(connection)
        return connection.withSecret { secret ->
            val address = connection.address
            val tokenId = connection.tokenId
            val pin = connection.fingerprint
            val version = parsePdmVersion(tracedPdm(address, tokenId, secret, pin, "/api2/json/version"))
            val remotes = parsePdmRemotes(tracedPdm(address, tokenId, secret, pin, "/api2/json/remotes/remote"))
            val groups = parsePdmResources(
                tracedPdm(address, tokenId, secret, pin, "/api2/json/resources/list?max-age=0"),
                connection.remoteKey,
            )
            PdmFleetDocument(version.first, version.second, version.third, remotes, groups)
        }
    }
}

fun parsePdmVersion(body: String): Triple<String, String, String> {
    val data = objectData(body, "The server returned no version.")
    return Triple(requiredString(data, "version"), requiredString(data, "release"), requiredString(data, "repoid"))
}

/** Keeps id and type only. Token, authid, nodes, fingerprints, and web-url are not copied. */
fun parsePdmRemotes(body: String): List<PdmRemoteRecord> {
    val rows = arrayData(body, "The server returned an invalid remote list.")
    val records = rows.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val id = stringField(row, "id")?.let(::pdmScopeOrNull) ?: return@mapNotNull null
        val product = stringField(row, "type")
        if (product != "pve" && product != "pbs") return@mapNotNull PdmRemoteRecord(id, "pve", "This remote returned invalid metadata.")
        PdmRemoteRecord(id, product)
    }
    if (records.map { it.id }.distinct().size != records.size) error("The server returned duplicate remote identities.")
    return records
}

fun parsePdmResources(body: String, origin: String): List<PdmResourceGroup> {
    require(serverOrigin(origin).toString() == origin) { "The guest is missing its connection identity." }
    val rows = arrayData(body, "The server returned an invalid resource list.")
    val groups = rows.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val scope = stringField(row, "remote")?.let(::pdmScopeOrNull) ?: return@mapNotNull null
        try { parseGroup(row, origin) } catch (_: IllegalStateException) {
            PdmResourceGroup(scope, "This remote returned an invalid resource list.", emptyList())
        }
    }
    if (groups.map { it.scope }.distinct().size != groups.size) error("The server returned duplicate remote identities.")
    val guests = groups.filter { it.error == null }.flatMap { it.guests }
    if (guests.map { it.resource }.distinct().size != guests.size) error("The server returned duplicate guest identities.")
    guests.forEach { guest ->
        require(guest.remote == origin) { "Inventory included a guest from another connection." }
    }
    return groups
}

/**
 * Apply one successful PDM read to the previous fleet.
 * A remote error keeps that scope's last guests. A successful empty group clears it.
 * A scope missing from both lists is dropped. Structural identity problems throw and keep the caller on the previous snapshot.
 */
fun mergePdmFleet(previous: Overview?, origin: String, document: PdmFleetDocument): Overview {
    require(serverOrigin(origin).toString() == origin) { "The guest is missing its connection identity." }
    if (document.remotes.map { it.id }.distinct().size != document.remotes.size) error("The server returned duplicate remote identities.")
    if (document.groups.map { it.scope }.distinct().size != document.groups.size) error("The server returned duplicate remote identities.")
    document.remotes.forEach { record ->
        require(pdmScopeOrNull(record.id) != null) { "The server returned an invalid remote identity." }
        require(record.product == "pve" || record.product == "pbs") { "The server returned an invalid remote identity." }
    }
    val installed = document.groups.filter { it.error == null }.flatMap { it.guests }
    if (installed.map { it.resource }.distinct().size != installed.size) error("The server returned duplicate guest identities.")
    document.groups.forEach { group ->
        require(pdmScopeOrNull(group.scope) != null) { "The server returned an invalid remote identity." }
        group.guests.forEach { guest ->
            require(guest.remote == origin && guest.scope == group.scope) { "Inventory included a guest from another connection." }
        }
    }
    val remoteById = document.remotes.associateBy { it.id }
    val groupById = document.groups.associateBy { it.scope }
    val previousGuests = previous?.guests.orEmpty().filter { it.remote == origin }.groupBy { it.scope }
    val previousProduct = previous?.remotes.orEmpty().associate { it.scope to it.product }
    val scopes = LinkedHashSet<String>()
    document.remotes.forEach { scopes += it.id }
    document.groups.forEach { scopes += it.scope }
    val guests = mutableListOf<Guest>()
    val remotes = mutableListOf<FleetRemote>()
    for (scope in scopes) {
        val record = remoteById[scope]
        val group = groupById[scope]
        val product = record?.product ?: previousProduct[scope] ?: "pve"
        when {
            group?.error != null || record?.error != null -> {
                guests += previousGuests[scope].orEmpty()
                remotes += FleetRemote(scope, product, stale = true, error = group?.error ?: record?.error)
            }
            group != null -> {
                guests += group.guests
                remotes += FleetRemote(scope, product, stale = false, error = null)
            }
            record != null -> remotes += FleetRemote(scope, record.product, stale = false, error = null)
        }
    }
    val overview = Overview(
        nodes = guests.filter { it.node.isNotBlank() }.map { it.scope to it.node }.distinct().size,
        guests = guests.sortedWith(compareBy<Guest> { it.scope }.thenBy { it.id }.thenBy { it.kind }),
        remotes = remotes,
        serverLabel = document.serverLabel,
    )
    return requireActiveRemote(origin, overview, PDM_KIND)
}

fun pdmListDetails(guest: Guest): GuestDetails {
    val metrics = guest.metrics
    val maxCpu = metrics?.maxCpu
    val cpus = maxCpu?.takeIf { it.isFinite() && it >= 1.0 && it <= Int.MAX_VALUE.toDouble() && it % 1.0 == 0.0 }?.toInt()
    return GuestDetails(
        guest, guest.status.ifBlank { "unknown" }, cpus, metrics?.cpuFraction,
        metrics?.memoryBytes, metrics?.memoryLimitBytes?.takeIf { it > 0 },
        metrics?.diskBytes, metrics?.diskLimitBytes?.takeIf { it > 0 },
        metrics?.uptimeSeconds, null, null, PDM_LIST_PROVENANCE,
    )
}

private fun parseGroup(element: JsonElement, origin: String): PdmResourceGroup {
    val row = element as? JsonObject ?: error("The server returned an invalid remote identity.")
    val scope = requiredScope(row, "remote")
    val errorText = when (val err = row["error"]) {
        null, is JsonNull -> null
        is JsonPrimitive -> if (err.isString) cleanRemoteError(err.content) else error("The server returned an invalid remote identity.")
        else -> error("The server returned an invalid remote identity.")
    }
    if (errorText != null) return PdmResourceGroup(scope, errorText, emptyList())
    val guests = when (val resources = row["resources"]) {
        null -> emptyList()
        is JsonArray -> resources.map { parsePdmGuest(it, origin, scope) }.filterNotNull()
        else -> error("The server returned an invalid resource list.")
    }
    return PdmResourceGroup(scope, null, guests)
}

private fun parsePdmGuest(element: JsonElement, origin: String, scope: String): Guest? {
    val row = element as? JsonObject ?: error("The server returned an invalid resource list.")
    val kind = when (stringField(row, "type")) {
        "pve-qemu" -> "VM"
        "pve-lxc" -> "Container"
        else -> return null
    }
    val vmid = jsonInteger(row, "vmid") ?: return null
    if (vmid !in 1..Int.MAX_VALUE) return null
    val template = when (val flag = row["template"]) {
        null, is JsonNull -> false
        is JsonPrimitive -> {
            if (flag.isString) return null
            flag.booleanOrNull ?: return null
        }
        else -> return null
    }
    val name = stringField(row, "name").orEmpty()
    val node = stringField(row, "node").orEmpty().let { if (it.any { char -> char.isISOControl() }) "" else it }
    val status = stringField(row, "status").orEmpty()
    return Guest(origin, vmid.toInt(), name, node, kind, status, template, scope, listMetrics(row), parseResourceTags(row["tags"]))
}

/** Resource-list metrics (cpu, mem, disk, uptime, netin/netout counters). Shared by PDM and direct PVE `cluster/resources` rows. */
internal fun listMetrics(row: JsonObject): GuestListMetrics {
    val maxMem = jsonWhole(row, "maxmem")
    val maxDisk = jsonWhole(row, "maxdisk")
    return GuestListMetrics(
        cpuFraction = jsonFraction(row, "cpu"),
        maxCpu = jsonFraction(row, "maxcpu"),
        memoryBytes = jsonWhole(row, "mem"),
        memoryLimitBytes = maxMem,
        diskBytes = jsonWhole(row, "disk"),
        diskLimitBytes = maxDisk,
        uptimeSeconds = jsonWhole(row, "uptime"),
        netIn = jsonWhole(row, "netin"),
        netOut = jsonWhole(row, "netout"),
    )
}

private fun objectData(body: String, failure: String): JsonObject {
    val data = dataElement(body, failure)
    return data as? JsonObject ?: error(failure)
}

private fun arrayData(body: String, failure: String): JsonArray {
    val data = dataElement(body, failure)
    return data as? JsonArray ?: error(failure)
}

private fun dataElement(body: String, failure: String): JsonElement {
    val root = try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: error(failure)
    return root["data"] ?: error(failure)
}

private fun requiredString(row: JsonObject, key: String): String {
    val value = stringField(row, key)?.takeIf { it.isNotBlank() } ?: error("The server returned no version.")
    return value
}

private fun requiredScope(row: JsonObject, key: String): String {
    val value = stringField(row, key) ?: error("The server returned an invalid remote identity.")
    return pdmScopeOrNull(value) ?: error("The server returned an invalid remote identity.")
}

private fun stringField(row: JsonObject, key: String): String? {
    val value = row[key] as? JsonPrimitive ?: return null
    if (!value.isString) return null
    return value.content
}

private fun jsonInteger(row: JsonObject, key: String): Long? {
    val value = row[key] as? JsonPrimitive ?: return null
    if (value.isString) return null
    val content = value.content
    if (content.any { it == '.' || it == 'e' || it == 'E' }) return null
    return value.longOrNull
}

private fun jsonWhole(row: JsonObject, key: String): Long? = jsonInteger(row, key)?.takeIf { it >= 0 }

private fun jsonFraction(row: JsonObject, key: String): Double? {
    val value = row[key] as? JsonPrimitive ?: return null
    if (value.isString) return null
    val number = value.doubleOrNull ?: return null
    if (!number.isFinite() || number < 0) return null
    return number
}

private fun cleanRemoteError(value: String): String {
    val cleaned = value.replace(Regex("\\s+"), " ").trim()
    if (cleaned.isEmpty()) return "This remote could not be read."
    return cleaned.take(240)
}
