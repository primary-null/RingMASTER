package local.ringleader.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Fresh guest state read immediately before a scheduled dispatch. */
data class ScheduledGuestState(val status: String, val qmpStatus: String?, val lock: String?, val template: Boolean)

/** Nothing reached the server: connect or TLS failed (including a refused certificate) before the request was written. */
class ScheduleNotSentException(message: String) : java.io.IOException(message)
/** The server answered with a complete refusal ([status] 400/401/403, or another status for a read). */
class ScheduleRefusedException(val status: Int) : IllegalStateException("The server refused the request (HTTP $status).")
/** The request may have reached the server and the answer was lost or unclear. Never resent. */
class ScheduleUncertainException : IllegalStateException("The server did not confirm the request. It was not sent again.")

/**
 * Everything a background run does on the network, each call on its own private connection. Every mutation goes through
 * a one-shot transport: one TLS socket, no retry, reconnect, redirect or 401 replay.
 */
interface ScheduleGateway {
    suspend fun guests(connection: Connection): List<Guest>
    suspend fun identity(connection: Connection, node: String, kind: String, vmid: Int): GuestIdentityFacts
    suspend fun state(connection: Connection, node: String, kind: String, vmid: Int): ScheduledGuestState
    suspend fun permissions(connection: Connection): PermissionDump
    /** One POST. Returns the accepted UPID for exactly this node and guest. */
    suspend fun dispatch(connection: Connection, node: String, kind: String, vmid: Int, action: ScheduleAction): String
    suspend fun taskStatus(connection: Connection, node: String, upid: String): PowerTaskStatus
    suspend fun taskLog(connection: Connection, node: String, upid: String, start: Int, limit: Int): TaskLogPage
}

internal fun guestBasePath(node: String, kind: String, vmid: Int): String {
    require(pveNodeNameOrNull(node) == node) { "The guest route is not usable." }
    require(vmid > 0) { "The guest ID is not usable." }
    val api = when (kind) { "VM" -> "qemu"; "Container" -> "lxc"; else -> error("Unknown guest type.") }
    return "/api2/json/nodes/${encodePathSegment(node)}/$api/$vmid"
}

class LiveScheduleGateway internal constructor(private val transport: ProxmoxTransport) : ScheduleGateway {
    constructor(connectTimeoutMs: Int = 10_000, readTimeoutMs: Int = 20_000) : this(ProxmoxTransport(connectTimeoutMs, readTimeoutMs))

    private fun read(connection: Connection, path: String): String {
        require(!connection.passwordLogin) { "Background runs use an API token." }
        return try { transport.storageExchange(connection, path, "GET") }
        catch (e: SessionExpiredException) { throw ScheduleRefusedException(401) }
        catch (e: StorageRejected) { throw ScheduleRefusedException(e.status) }
        catch (e: StorageUnclear) { throw ScheduleUncertainException() }
        catch (e: java.io.IOException) { throw ScheduleNotSentException("The server could not be reached securely. Nothing was sent.") }
    }

    override suspend fun guests(connection: Connection): List<Guest> = runInterruptible(Dispatchers.IO) {
        requireDirectRequest(connection)
        parseOverview(read(connection, "/api2/json/cluster/resources"), connection.remoteKey).guests
    }

    override suspend fun identity(connection: Connection, node: String, kind: String, vmid: Int): GuestIdentityFacts = runInterruptible(Dispatchers.IO) {
        parseIdentityFacts(read(connection, guestBasePath(node, kind, vmid) + "/config"), kind)
    }

    override suspend fun state(connection: Connection, node: String, kind: String, vmid: Int): ScheduledGuestState = runInterruptible(Dispatchers.IO) {
        parseScheduledState(read(connection, guestBasePath(node, kind, vmid) + "/status/current"))
    }

    override suspend fun permissions(connection: Connection): PermissionDump = runInterruptible(Dispatchers.IO) {
        parsePermissions(read(connection, PERMISSIONS_PATH))
    }

    override suspend fun dispatch(connection: Connection, node: String, kind: String, vmid: Int, action: ScheduleAction): String = runInterruptible(Dispatchers.IO) {
        require(!connection.passwordLogin) { "Background runs use an API token." }
        val path = powerActionPath(node, kind, vmid, action.power)
        val body = powerRequestBody(action.power)
        val raw = try { transport.storageExchange(connection, path, "POST", body = body) }
            catch (e: SessionExpiredException) { throw ScheduleRefusedException(401) }
            catch (e: StorageRejected) { throw ScheduleRefusedException(e.status) }
            catch (e: StorageUnclear) { throw ScheduleUncertainException() }
            catch (e: StorageCancelledBeforeSend) { throw ScheduleNotSentException("The run was stopped before sending. Nothing was sent.") }
            catch (e: java.io.IOException) { throw ScheduleNotSentException("The server could not be reached securely. Nothing was sent.") }
        try { parseAcceptedUpid(raw, node, vmid) } catch (e: PowerResponseUnclearException) { throw ScheduleUncertainException() }
    }

    override suspend fun taskStatus(connection: Connection, node: String, upid: String): PowerTaskStatus = runInterruptible(Dispatchers.IO) {
        parsePowerTaskStatus(read(connection, taskStatusPath(node, upid)), node, upid)
    }

    override suspend fun taskLog(connection: Connection, node: String, upid: String, start: Int, limit: Int): TaskLogPage = runInterruptible(Dispatchers.IO) {
        parseTaskLogPage(read(connection, taskLogPath(node, upid, start, limit)))
    }
}

private fun dataObject(body: String): JsonObject =
    (runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject)?.get("data") as? JsonObject
        ?: error("The server returned an unreadable answer.")

private fun plain(row: JsonObject, key: String): String? {
    val value = row[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: return null
    return primitive.content.trim().takeIf { it.isNotEmpty() && it.length <= 512 && it.none(Char::isISOControl) }
}

private val uuidPattern = Regex("(?:^|,)uuid=([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?:,|$)")
private val ctimePattern = Regex("(?:^|,)ctime=([0-9]{1,12})(?:,|$)")

/**
 * Identity evidence from `GET .../config`: the SMBIOS UUID (QEMU `smbios1`), the creation time (QEMU `meta ctime`) and
 * a weak fallback of name, OS type and root disk volume. An all-zero UUID is ignored.
 */
fun parseIdentityFacts(body: String, kind: String): GuestIdentityFacts {
    val row = dataObject(body)
    val uuid = plain(row, "smbios1")?.let { uuidPattern.find(it)?.groupValues?.get(1) }?.takeIf { it.any { c -> c != '0' && c != '-' } }
    val ctime = plain(row, "meta")?.let { ctimePattern.find(it)?.groupValues?.get(1)?.toLongOrNull() }?.takeIf { it > 0 }
    val name = plain(row, if (kind == "Container") "hostname" else "name").orEmpty()
    val disk = (if (kind == "Container") listOf("rootfs") else listOf("scsi0", "virtio0", "sata0", "ide0"))
        .firstNotNullOfOrNull { plain(row, it) }?.substringBefore(',').orEmpty()
    val template = plain(row, "template") in setOf("1", "true")
    return GuestIdentityFacts(uuid, ctime, "$kind|$name|${plain(row, "ostype").orEmpty()}|$disk", plain(row, "lock"), template)
}

fun parseScheduledState(body: String): ScheduledGuestState {
    val row = dataObject(body)
    val status = plain(row, "status") ?: error("The server returned no guest state.")
    return ScheduledGuestState(status, plain(row, "qmpstatus"), plain(row, "lock"), plain(row, "template") in setOf("1", "true"))
}
