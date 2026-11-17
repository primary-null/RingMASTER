package local.ringleader.core

import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.*

/** Stable guest identity. [scope] is empty for direct PVE. [Guest.node] is the mutable route and is not part of this key. */
data class ResourceId(val remote: String, val scope: String, val kind: String, val vmid: Int) {
    init {
        require(scope.isEmpty() || pdmScopeOrNull(scope) != null) { "The guest scope is not a PDM remote id." }
    }
}

const val DIRECT_REQUEST_REFUSAL = "A PDM connection cannot use a direct Proxmox VE request."
const val DIRECT_GUEST_REFUSAL = "A PDM guest cannot use a direct Proxmox VE request."

/** Metrics copied from a resource-list row. Absent fields stay null. [netIn] and [netOut] are counters, not rates. */
data class GuestListMetrics(
    val cpuFraction: Double? = null,
    val maxCpu: Double? = null,
    val memoryBytes: Long? = null,
    val memoryLimitBytes: Long? = null,
    val diskBytes: Long? = null,
    val diskLimitBytes: Long? = null,
    val uptimeSeconds: Long? = null,
    val netIn: Long? = null,
    val netOut: Long? = null,
)

data class Guest(
    val remote: String, val id: Int, val name: String, val node: String,
    val kind: String, val status: String, val template: Boolean = false,
    val scope: String = "",
    val metrics: GuestListMetrics? = null,
    val tags: List<String> = emptyList(),
    /** HA state from a qemu/lxc resource row (`hastate`). Null when absent, blank, or not plain text. */
    val haState: String? = null,
    /** Guest lock from a qemu/lxc resource row (`lock`), for example "backup". Null when absent, blank, or not plain text. */
    val lock: String? = null,
) {
    val resource get() = ResourceId(remote, scope, kind, id)
    init {
        require(serverOrigin(remote).toString() == remote) { "The guest is missing its connection identity." }
        require(scope.isEmpty() || pdmScopeOrNull(scope) != null) { "The guest scope is not a PDM remote id." }
    }
}

/** One PDM remote in a fleet. [stale] means the last good guests were kept after a remote error. */
data class FleetRemote(
    val scope: String,
    val product: String,
    val stale: Boolean = false,
    val error: String? = null,
)

data class Overview(
    val nodes: Int,
    val guests: List<Guest>,
    val remotes: List<FleetRemote> = emptyList(),
    val serverLabel: String? = null,
    val members: List<RecordedMember> = emptyList(),
    val storage: List<RecordedStorage> = emptyList(),
    val indexStale: Boolean = false,
    val indexError: String? = null,
)

data class GuestDetails(
    val guest: Guest, val status: String, val cpus: Int?, val cpuFraction: Double?,
    val memoryBytes: Long?, val memoryLimitBytes: Long?, val diskBytes: Long?, val diskLimitBytes: Long?,
    val uptimeSeconds: Long?, val networkInBytes: Long?, val networkOutBytes: Long?,
    val provenance: String? = null,
)

fun guestStatusPath(guest: Guest): String {
    require(guest.scope.isEmpty()) { DIRECT_GUEST_REFUSAL }
    require(guest.id > 0 && guest.node.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]*"))) { "The server returned an invalid guest identity." }
    val type = when (guest.kind) { "VM" -> "qemu"; "Container" -> "lxc"; else -> error("Unknown guest type.") }
    return "/api2/json/nodes/${guest.node}/$type/${guest.id}/status/current"
}

/** Route a detail read only when the guest belongs to this direct connection. Node is the route, not identity. */
fun boundGuestPath(connection: Connection, guest: Guest): String {
    requireDirectRequest(connection, guest)
    require(guest.remote == connection.remoteKey) { "This guest belongs to a different connection." }
    return guestStatusPath(guest)
}

fun parseDetails(guest: Guest, body: String): GuestDetails {
    val row = serverJson(body).jsonObject["data"]?.jsonObject ?: error("The server returned no guest details.")
    fun number(key: String) = row[key]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0 }
    val status = row["status"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: error("The server returned no guest state.")
    val cpu = row["cpu"]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }
    return GuestDetails(guest, status, row["cpus"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }, cpu,
        number("mem"), number("maxmem")?.takeIf { it > 0 },
        if (guest.kind == "Container") number("disk") else null, number("maxdisk")?.takeIf { it > 0 },
        number("uptime"), number("netin"), number("netout"))
}

internal val tokenIdPattern = Regex("[^\\s=]+@[^\\s=!]+![^\\s=]+")
internal val sha256PinPattern = Regex("[0-9a-f]{64}")

/**
 * Authorization value for one connection kind.
 * Direct PVE separates the secret with `=`. PDM separates it with `:` and does not rewrite the secret.
 */
fun authorizationHeader(kind: String, tokenId: String, secret: String): String {
    require(kind == DIRECT_PVE_KIND || kind == PDM_KIND) { "Choose a direct Proxmox VE or PDM connection." }
    require(tokenId.matches(tokenIdPattern)) { "Use a token ID in user@realm!token form." }
    require(secret.isNotBlank() && secret.none { it == '\r' || it == '\n' }) { "Enter the API token secret." }
    if (kind == PDM_KIND) require(!tokenId.contains(':')) { "A PDM token ID cannot contain a colon." }
    return when (kind) {
        DIRECT_PVE_KIND -> "PVEAPIToken=$tokenId=$secret"
        else -> "PDMAPIToken=$tokenId:$secret"
    }
}

/** Length 2..32, no whitespace, slash, or control character. This is a client bound, not a copy of SAFE_ID_REGEX. */
fun pdmScopeOrNull(value: String): String? {
    if (value.length !in 2..32) return null
    if (value.any { it.isWhitespace() || it == '/' || it.isISOControl() }) return null
    return value
}

fun requireDirectRequest(connection: Connection, guest: Guest? = null) {
    require(connection.kind == DIRECT_PVE_KIND) { DIRECT_REQUEST_REFUSAL }
    if (guest != null) require(guest.scope.isEmpty()) { DIRECT_GUEST_REFUSAL }
}

fun serverOrigin(value: String): URI {
    val uri = try { URI(value.trim()) } catch (_: Exception) { error("Enter a valid HTTPS server address.") }
    require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "Use an HTTPS server address, including its port." }
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in listOf("", "/")) {
        "Enter only the server address, without a login, page path, or query."
    }
    require(uri.port == -1 || uri.port in 1..65535) { "The server port is invalid." }
    return URI("https", null, uri.host, uri.port, null, null, null)
}

fun parseOverview(body: String, remote: String): Overview {
    require(serverOrigin(remote).toString() == remote) { "The guest is missing its connection identity." }
    val rows = serverJson(body).jsonObject["data"]?.jsonArray ?: error("Server returned no resource list.")
    fun JsonObject.field(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    val guests = rows.mapNotNull {
        val row = it.jsonObject
        val kind = row.field("type")
        if (kind !in listOf("qemu", "lxc")) return@mapNotNull null
        Guest(remote, row["vmid"]?.jsonPrimitive?.intOrNull ?: error("Guest has no ID."), row.field("name"),
            row.field("node"), if (kind == "qemu") "VM" else "Container", row.field("status"), row.field("template") in listOf("1", "true"),
            metrics = listMetrics(row), tags = parseResourceTags(row["tags"]),
            haState = optionalPlain(row, "hastate"), lock = optionalPlain(row, "lock"))
    }.sortedBy { it.id }
    val index = recordedIndex(rows, remote)
    return if (index.error != null) {
        Overview(0, guests, indexStale = true, indexError = index.error)
    } else {
        Overview(index.members.size, guests, members = index.members, storage = index.storage)
    }
}

class PveClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) {
    private val http = ProxmoxTransport(connectTimeoutMs, readTimeoutMs)

    fun storageCatalogue(connection: Connection, node: String): List<AplTemplate> =
        parseAplCatalogue(http.storageExchange(connection, "/api2/json/nodes/${encodePathSegment(pveNodeNameOrNull(node) ?: error("Invalid node."))}/aplinfo"))
    fun storageUrlMetadata(connection: Connection, node: String, url: String, verifyCertificates: Boolean): UrlMetadata {
        require(downloadUrlError(url) == null) { "Invalid URL." }
        return parseUrlMetadata(http.storageExchange(connection, "/api2/json/nodes/${encodePathSegment(pveNodeNameOrNull(node) ?: error("Invalid node."))}/query-url-metadata?url=${encodePathSegment(url)}&verify-certificates=${if (verifyCertificates) 1 else 0}"))
    }
    fun storageAttributes(connection: Connection, key: StorageKey, volid: String): StorageAttributes {
        require(connection.remoteKey == key.remote) { "The storage belongs to another connection." }
        return parseStorageAttributes(http.storageExchange(connection, storageVolumePath(key, volid)))
    }
    fun submitStorage(connection: Connection, key: StorageKey, request: StorageRequest, progress: (Long) -> Unit = {}, cancel: UploadCancel = UploadCancel()): String? {
        require(connection.remoteKey == key.remote)
        require(storageRequestError(request) == null) { "Invalid storage request." }
        val path = storageActionPath(key, request)
        val raw = if (request is StorageRequest.Upload) {
            val frame = uploadMultipart(java.util.UUID.randomUUID().toString().replace("-", ""), request.content, request.checksum, request.fileName)
            http.storageExchange(connection, path, "POST", frame, request.source, progress, cancel)
        } else {
            val body = storageRequestBody(key, request)
            try { http.storageExchange(connection, path, when(request) { is StorageRequest.RemoveVolume -> "DELETE"; is StorageRequest.EditNotes, is StorageRequest.SetProtection -> "PUT"; else -> "POST" }, body = body) }
            finally { body.fill(0) }
        }
        if (request is StorageRequest.EditNotes || request is StorageRequest.SetProtection) {
            if (runCatching { storageData(raw) == JsonNull }.getOrDefault(false)) return null
            throw StorageUnclear()
        }
        return parseStorageUpid(raw, key, request)
    }

    private fun createRead(connection: Connection, path: String, readCreateErrors: Boolean = false): String {
        requireDirectRequest(connection)
        return http.direct(connection, path, readCreateErrors = readCreateErrors)
    }
    fun nextId(connection: Connection, vmid: Int? = null): NextIdReply = try {
        NextIdReply.Free(parseNextId(createRead(connection, nextIdPath(vmid), readCreateErrors = true)))
    } catch (e: CreateServerRefusal) { if (e.status == 403) throw GuestPermissionException(); if (e.status == 400 && vmid != null && "vmid" in e.fields) NextIdReply.Taken(vmid, "ID $vmid is in use.") else throw e }
    fun pools(connection: Connection): List<PoolChoice> = parsePools(createRead(connection, POOLS_PATH))
    fun bridges(connection: Connection, node: String): List<BridgeChoice> = parseBridges(createRead(connection, bridgesPath(node)))
    fun cpuModels(connection: Connection, node: String, arch: String?): List<CpuModelChoice> = parseCpuModels(createRead(connection, cpuModelsPath(node, arch)))
    fun cpuFlags(connection: Connection, node: String, arch: String?, accel: String): List<CpuFlagChoice> = parseCpuFlags(createRead(connection, cpuFlagsPath(node, arch, accel)))
    fun clusterOptions(connection: Connection): ClusterTagRules = parseClusterTagRules(createRead(connection, CLUSTER_OPTIONS_PATH))
    fun createGuest(connection: Connection, request: CreateRequest, secrets: CreateSecrets): CreateReply {
        var body: ByteArray? = null
        try {
            requireDirectRequest(connection)
            body = createBody(request, secrets)
            secrets.close()
            val response = http.direct(connection, createNodePath(request.node) + "/" + request.kind.apiSegment,
                body, contentType = "application/x-www-form-urlencoded", readCreateErrors = true)
            return CreateReply.Accepted(parseAcceptedUpid(response, request.node, request.vmid))
        } catch (e: CreateServerRefusal) {
            return CreateReply.Rejected(e.status, "The server refused the create request (HTTP ${e.status}).",
                e.fields.associateWith { "The server refused this field. Check its value and permissions." })
        } finally { body?.fill(0); secrets.close() }
    }

    fun overview(address: String, tokenId: String, secret: String, fingerprint: String = ""): Overview {
        val remote = serverOrigin(address).toString()
        return parseOverview(http.direct(address, tokenId, secret, fingerprint, "/api2/json/cluster/resources"), remote)
    }

    fun overview(connection: Connection): Overview {
        requireDirectRequest(connection)
        return parseOverview(http.direct(connection, "/api2/json/cluster/resources"), connection.remoteKey)
    }

    fun details(connection: Connection, guest: Guest): GuestDetails {
        requireDirectRequest(connection, guest)
        val path = boundGuestPath(connection, guest)
        return connection.withSecret { secret ->
            parseDetails(guest, http.direct(connection, path))
        }
    }

    /** One live status read for a recorded direct-PVE member. The name is not taken from a guest route. */
    fun nodeStatus(connection: Connection, node: String): NodeLiveStatus {
        val path = directNodeStatusPath(connection, connection.remoteKey, node, "")
        require(!path.contains("/storage")) { "The live storage index is not requested." }
        return connection.withSecret { secret ->
            parseNodeStatus(node, http.direct(
                connection, path,
                missing = NODE_STATUS_NOT_FOUND, badRequest = NODE_STATUS_REFUSED,
            ))
        }
    }

    fun tasks(connection: Connection): List<ClusterTask> {
        requireDirectRequest(connection)
        return connection.withSecret { secret ->
            parseClusterTasks(http.direct(connection, CLUSTER_TASKS_PATH), connection.remoteKey)
        }
    }

    /** POST one power action. A returned UPID is acceptance, not completion. There is no retry. */
    fun submitPower(connection: Connection, guest: Guest, action: PowerAction): String {
        requireDirectRequest(connection, guest)
        require(guest.remote == connection.remoteKey) { "This guest belongs to a different connection." }
        val path = powerActionPath(guest.node, guest.kind, guest.id, action)
        val body = powerRequestBody(action)
        return connection.withSecret { secret ->
            parseAcceptedUpid(http.direct(connection, path, body), guest.node, guest.id)
        }
    }

    /**
     * The node's network configuration. Any signed-in user may call it. Bridges can be omitted without an error.
     * See [parseNodeNetworkReport].
     */
    fun nodeNetwork(connection: Connection, node: String): NodeNetworkReport {
        requireDirectRequest(connection)
        val path = nodeNetworkPath(node)
        return connection.withSecret { parseNodeNetworkReport(http.direct(connection, path)) }
    }

    fun nodeHistoryRead(connection: Connection, node: String, timeframe: String, cf: String): String {
        val suffix = historySuffix(timeframe, cf)
        require(node.isNotBlank())
        return connection.withSecret { http.direct(connection, "/api2/json/nodes/${encodePathSegment(node)}$suffix") }
    }

    fun guestRead(connection: Connection, guest: Guest, suffix: String): String {
        val base = boundGuestPath(connection, guest).removeSuffix("/status/current")
        return connection.withSecret { http.direct(connection, base + suffix,
            badRequest = if (suffix.contains("agent/")) "Guest agent not running" else null,
            missing = if (suffix.contains("agent/")) "Guest agent not running" else GUEST_NOT_FOUND) }
    }

    /**
     * One SPICE ticket for a running QEMU VM: a config read to confirm a QXL display, then `spiceproxy` with the proxy set
     * to the host this client connected to. The POST changes no guest state; a lost reply is reported, never resent.
     */
    fun spiceTicket(connection: Connection, guest: Guest): SpiceTicket {
        boundGuestPath(connection, guest)
        require(guest.kind == "VM") { SPICE_NOT_VM }
        require(guest.status == "running" && !guest.template) { SPICE_NOT_RUNNING }
        val path = spiceProxyPath(guest)
        return connection.withSecret { secret ->
            try {
                requireSpiceDisplay(spiceDisplayType(guestRead(connection, guest, "/config")))
                parseSpiceTicket(http.direct(connection, path, spiceRequestBody(connection.address)),
                    spiceProxyHost(connection.address))
            } catch (e: GuestPermissionException) { error(SPICE_NO_PERMISSION) }
            catch (e: AmbiguousPowerResultException) { error(SPICE_REQUEST_FAILED) }
        }
    }

    /** Captures one read-only VNC frame without depending on an external console viewer. */
    fun consoleStill(connection: Connection, guest: Guest): ConsoleStill {
        val base = boundGuestPath(connection, guest).removeSuffix("/status/current")
        require(guest.kind == "VM") { "Screen captures are for QEMU VMs." }
        require(guest.status == "running" && !guest.template) { "Start the VM to capture its screen." }
        return captureConsoleStill(connection, base) { secret ->
            try {
                http.direct(connection, "$base/vncproxy", consoleStillRequestBody())
            } catch (e: GuestPermissionException) { error(SPICE_NO_PERMISSION) }
            catch (e: AmbiguousPowerResultException) { error("The server did not open a console channel.") }
        }
    }

    /**
     * Opens a live VNC console for a running QEMU VM. The proxy POST is the same one-shot ticket used for a still;
     * the session keeps the websocket open. A refused VM.Console permission fails before any framebuffer is read.
     */
    fun terminalSession(connection: Connection, target: TerminalTarget): TerminalSession {
        requireDirectRequest(connection)
        val base = target.base()
        return openTerminalSession(connection, target) {
            try { http.direct(connection, "$base/termproxy", (if (target.kind == "qemu") "{\"serial\":\"serial0\"}" else "{}").toByteArray()) }
            catch (e: GuestPermissionException) { error(if (target.kind == "node") "This token may not open a node shell (needs Sys.Console)." else "This token lacks VM.Console on this ${if (target.kind == "lxc") "container" else "VM"}.") }
            catch (e: AmbiguousPowerResultException) { error("The server did not open a terminal channel.") }
        }
    }

    fun consoleSession(connection: Connection, guest: Guest): VncSession {
        val base = boundGuestPath(connection, guest).removeSuffix("/status/current")
        require(guest.kind == "VM") { VNC_NOT_VM }
        require(guest.status == "running" && !guest.template) { VNC_GUEST_STOPPED }
        return openVncSession(connection, base) { secret ->
            try {
                http.direct(connection, "$base/vncproxy", consoleStillRequestBody())
            } catch (e: GuestPermissionException) { error(VNC_NO_PERMISSION) }
            catch (e: AmbiguousPowerResultException) { error("The server did not open a console channel.") }
        }
    }

    /**
     * Runs the read-only BlueZ scan script inside a running VM through the QEMU guest agent and parses its output.
     * One exec, then status polls on that pid only; the exec is never resent if its reply is lost.
     */
    fun guestBluetoothScan(connection: Connection, guest: Guest, seconds: Int, sleep: (Long) -> Unit = Thread::sleep): BtScan {
        boundGuestPath(connection, guest)
        require(guest.kind == "VM") { "Bluetooth scans inside a guest need a QEMU VM with the guest agent." }
        require(guest.status == "running" && !guest.template) { GUEST_BT_NOT_RUNNING }
        val window = seconds.coerceIn(3, 30)
        val status = guestAgentRun(connection, guest, bluezScanCommand(window), window, window + 25, sleep)
        check(!status.truncated) { "The scan output from the VM was truncated." }
        return parseBluezScanOutput(status.out, guestBluetoothSource(guest), java.time.Instant.now())
    }

    /**
     * Runs one command inside a running VM through the QEMU guest agent and returns how it ended. One exec, then status
     * polls on that pid only; the exec is never resent if its reply is lost. The first poll waits [firstPollSeconds], then
     * one poll a second until [budgetSeconds] have passed since the exec was accepted. The call must be one the user
     * started. A failure is a [GuestAgentFailure] that carries its [GuestRefusal].
     */
    fun guestAgentRun(connection: Connection, guest: Guest, command: List<String>, firstPollSeconds: Int, budgetSeconds: Int, sleep: (Long) -> Unit = Thread::sleep): AgentExecStatus {
        val base = boundGuestPath(connection, guest).removeSuffix("/status/current")
        return connection.withSecret { secret ->
            try {
                val pid = parseAgentExecPid(http.direct(connection, "$base/agent/exec",
                    agentExecBody(command), badRequest = GUEST_BT_AGENT, missing = GUEST_BT_AGENT))
                val deadline = System.nanoTime() + budgetSeconds * 1_000_000_000L
                sleep(firstPollSeconds * 1000L)
                while (true) {
                    val status = parseAgentExecStatus(http.direct(connection,
                        "$base/agent/exec-status?pid=$pid", badRequest = GUEST_BT_AGENT, missing = GUEST_BT_AGENT))
                    if (status.exited) return@withSecret status
                    if (System.nanoTime() >= deadline) throw GuestAgentFailure(GuestRefusal.TimedOut)
                    sleep(1000)
                }
                @Suppress("UNREACHABLE_CODE") error(GUEST_BT_TIMEOUT)
            } catch (e: GuestPermissionException) { throw GuestAgentFailure(GuestRefusal.NoPermission) }
            catch (e: AmbiguousPowerResultException) { throw GuestAgentFailure(GuestRefusal.AgentNotAnswering) }
            // The transport answers an unusable request with this sentence; make it typed too.
            catch (e: IllegalStateException) { if (e !is GuestAgentFailure && e.message == GUEST_BT_AGENT) throw GuestAgentFailure(GuestRefusal.AgentNotAnswering) else throw e }
        }
    }

    fun backupRead(connection: Connection, guest: Guest, storage: String): String {
        boundGuestPath(connection, guest)
        val path = "/api2/json/nodes/${encodePathSegment(guest.node)}/storage/${encodePathSegment(storage)}/content?content=backup&vmid=${guest.id}"
        return connection.withSecret { http.direct(connection, path) }
    }

    fun submitConfirmed(connection: Connection, confirmation: PowerConfirmation): String {
        val guest = confirmedGuest(confirmation)
        boundGuestPath(connection, guest)
        require(validSnapshotRequest(confirmation))
        if (confirmation.snapshot == null) return submitPower(connection, guest, confirmation.action)
        val request = snapshotMutation(confirmation)
        return connection.withSecret { parseAcceptedUpid(http.direct(connection, request.path, request.body, method = request.method), guest.node, guest.id) }
    }

    /** One status read for a task this session submitted. The path is bound to that node and UPID. */
    fun powerStatus(connection: Connection, node: String, upid: String): PowerTaskStatus {
        requireDirectRequest(connection)
        val path = taskStatusPath(node, upid)
        return connection.withSecret { secret ->
            parsePowerTaskStatus(http.direct(connection, path), node, upid)
        }
    }

    /** Task status for the Activity log. The path is the encoded node and UPID, and the body is not a log. */
    fun readTaskLogStatus(connection: Connection, node: String, upid: String): TaskLogStatus {
        requireDirectRequest(connection)
        val path = taskStatusPath(node, upid)
        return connection.withSecret { parseTaskLogStatus(http.direct(connection, path), node, upid) }
    }

    /** One page of a task log. [start] is the last line already shown. The UPID is one encoded path segment. */
    fun readTaskLog(connection: Connection, node: String, upid: String, start: Int, limit: Int): TaskLogPage {
        requireDirectRequest(connection)
        val path = taskLogPath(node, upid, start, limit)
        return connection.withSecret { parseTaskLogPage(http.direct(connection, path)) }
    }

    /**
     * The signed-in identity's effective privileges per ACL path: `GET /access/permissions`, no parameters.
     * `GET /version` is open to any authenticated identity and is read first so guest-agent privileges can follow the
     * server release. A failed version read leaves the release unknown. An expired session still ends the call.
     */
    fun permissions(connection: Connection): PermissionDump {
        requireDirectRequest(connection)
        val release = readReleaseOrNull(connection)
        return connection.withSecret {
            parsePermissions(http.direct(connection, PERMISSIONS_PATH, missing = PERMISSIONS_NOT_FOUND, badRequest = PERMISSIONS_REFUSED))
                .copy(release = release)
        }
    }

    private fun readReleaseOrNull(connection: Connection): String? = try {
        connection.withSecret {
            parsePveRelease(http.direct(connection, PVE_VERSION_PATH, missing = PERMISSIONS_NOT_FOUND, badRequest = PERMISSIONS_REFUSED))
        }
    } catch (e: SessionExpiredException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Service list of one recorded node: `GET /nodes/{node}/services`. */
    fun nodeServices(connection: Connection, node: String): List<NodeService> {
        requireDirectRequest(connection)
        val path = nodeServicesPath(node)
        return connection.withSecret {
            parseNodeServices(http.direct(connection, path, missing = NODE_SERVICES_NOT_FOUND, badRequest = NODE_SERVICES_REFUSED))
        }
    }

    /** One page of a node's system log: `GET /nodes/{node}/syslog?start=&limit=`. [start] lines are skipped; see [syslogPath]. */
    fun nodeSyslog(connection: Connection, node: String, start: Int, limit: Int, service: String? = null): SyslogPage {
        requireDirectRequest(connection)
        val path = syslogPath(node, start, limit, service)
        return connection.withSecret {
            parseSyslogPage(http.direct(connection, path, missing = NODE_SYSLOG_NOT_FOUND, badRequest = NODE_SYSLOG_REFUSED))
        }
    }

    /** The node's cached package list: `GET /nodes/{node}/apt/update`. The GET can rebuild that cache from local apt data. It does not run apt update. */
    fun nodeUpdates(connection: Connection, node: String): List<PackageUpdate> {
        requireDirectRequest(connection)
        val path = aptUpdatePath(node)
        return connection.withSecret {
            parsePackageUpdates(http.direct(connection, path, missing = NODE_UPDATES_NOT_FOUND, badRequest = NODE_UPDATES_REFUSED))
        }
    }

    /**
     * One storage's content list: `GET /nodes/{node}/storage/{storage}/content[?content=]`. The node asks that one storage to
     * connect, so call it for a storage the user chose. A server error (offline, disabled) gets a plain sentence.
     */
    fun storageContent(connection: Connection, node: String, storage: String, content: String? = null): List<StorageVolume> {
        requireDirectRequest(connection)
        val path = storageContentPath(node, storage, content)
        return connection.withSecret {
            try {
                parseStorageContent(http.direct(connection, path, missing = STORAGE_CONTENT_NOT_FOUND, badRequest = STORAGE_CONTENT_REFUSED))
            } catch (_: SocketTimeoutException) {
                error(STORAGE_CONTENT_TIMEOUT)
            } catch (e: IllegalStateException) {
                if (e is SessionExpiredException || e is GuestPermissionException || e.message?.startsWith("The server returned HTTP 5") != true) throw e
                error(STORAGE_CONTENT_UNAVAILABLE)
            }
        }
    }

    /**
     * The cluster's storage configuration: `GET /storage`, no parameters. It reads storage.cfg and activates nothing. The body
     * can name servers, paths, exports and users; [parseStorageConfig] keeps only the allowlisted fields, and the raw body is
     * neither stored nor traced (the trace records the path and the byte count only).
     */
    fun storageConfig(connection: Connection): List<StorageConfigRow> {
        requireDirectRequest(connection)
        return connection.withSecret {
            parseStorageConfig(http.direct(connection, STORAGE_CONFIG_PATH, missing = STORAGE_CONFIG_NOT_FOUND, badRequest = STORAGE_CONFIG_REFUSED))
        }
    }

    /**
     * One storage's usage history: `GET /nodes/{node}/storage/{storage}/rrddata?timeframe=&cf=`. It reads the node's RRD file
     * and activates nothing. Only `used` and `total` are kept ([parseStorageHistory]).
     */
    fun storageHistory(connection: Connection, node: String, storage: String, timeframe: String, cf: String): List<HistoryPoint> {
        requireDirectRequest(connection)
        val path = storageHistoryPath(node, storage, timeframe, cf)
        return connection.withSecret {
            parseStorageHistory(http.direct(connection, path, missing = STORAGE_HISTORY_NOT_FOUND, badRequest = STORAGE_HISTORY_REFUSED))
        }
    }
}

internal val PDM_READ_PATHS = setOf(
    "/api2/json/version",
    "/api2/json/remotes/remote",
    "/api2/json/resources/list?max-age=0",
)

internal class ProxmoxTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
    private val storageAfterHeaders: () -> Unit = {},
) {
    fun direct(connection: Connection, path: String, body: ByteArray? = null,
        missing: String = GUEST_NOT_FOUND, badRequest: String? = null, method: String = "POST", contentType: String = POWER_CONTENT_TYPE, readCreateErrors: Boolean = false): String {
        require(isDirectPath(path)) { DIRECT_REQUEST_REFUSAL }
        require(!isForbiddenNodeRead(path)) { FORBIDDEN_NODE_READ }
        require(!isAptListRefresh(path, body != null)) { APT_REFRESH_REFUSAL }
        return connection.withSecret { exchange(connection.address, connection.tokenId, it, connection.fingerprint,
            path, body, connection.kind, missing, badRequest, method, connection, contentType = contentType, readCreateErrors = readCreateErrors) }
    }

    fun direct(
        address: String, tokenId: String, secret: String, fingerprint: String, path: String, body: ByteArray? = null,
        missing: String = GUEST_NOT_FOUND, badRequest: String? = null, method: String = "POST",
    ): String {
        require(isDirectPath(path)) { DIRECT_REQUEST_REFUSAL }
        require(!isForbiddenNodeRead(path)) { FORBIDDEN_NODE_READ }
        require(!isAptListRefresh(path, body != null)) { APT_REFRESH_REFUSAL }
        return exchange(address, tokenId, secret, fingerprint, path, body, DIRECT_PVE_KIND, missing, badRequest, method)
    }

    fun pdm(address: String, tokenId: String, secret: String, fingerprint: String, path: String): String {
        require(path in PDM_READ_PATHS) { "A PDM request cannot use a direct Proxmox VE path." }
        return exchange(address, tokenId, secret, fingerprint, path, null, PDM_KIND)
    }

    /** Storage-only bounded, one-shot transport. Never replays authorization or follows a redirect. */
    fun storageExchange(auth: Connection, path: String, method: String = "GET", frame: MultipartFrame? = null,
        source: UploadSource? = null, progress: (Long) -> Unit = {}, cancel: UploadCancel = UploadCancel(), body: ByteArray? = null): String {
        requireDirectRequest(auth)
        require(isDirectPath(path) && !isForbiddenNodeRead(path))
        require(method in setOf("GET", "POST", "PUT", "DELETE"))
        require((source == null) == (frame == null))
        require(source == null || (method == "POST" && body == null))
        require(method != "DELETE" || body == null || body.isEmpty())
        try { cancel.check() } catch (_: java.util.concurrent.CancellationException) { throw StorageCancelledBeforeSend() }
        if (source != null) require(source.unchanged()) { "The file changed. Choose it again. Nothing was sent." }
        return auth.withSecret { secret ->
            val header = if (auth.passwordLogin) null else authorizationHeader(auth.kind, auth.tokenId, secret)
            var sent = false
            val trace = Trace.begin(TraceSource.PveApi, "$method ${path.substringBefore('?')}")
            var status: Int? = null
            try {
                val headers = mutableMapOf<String,String>()
                if(header != null) headers["Authorization"] = header
                val ticket = if (auth.passwordLogin) auth.tickets.get(auth) else null
                if (ticket != null) {
                    headers["Cookie"] = ticket.cookie
                    if (method != "GET") headers["CSRFPreventionToken"] = ticket.csrf
                }
                cancel.check()
                source?.open().use { input ->
                val length = if(source != null && frame != null) frame.totalLength(source.size)
                    else if(method == "GET") null else (body?.size ?: 0).toLong()
                val contentType = if(frame != null) "multipart/form-data; boundary=${frame.boundary}" else POWER_CONTENT_TYPE
                val write: ((java.io.OutputStream) -> Unit)? = if(method == "GET" || method == "DELETE") null else { out ->
                    if(source != null && frame != null) {
                        out.write(frame.head)
                        streamUpload(input!!,out,source.size,progress,cancel)
                        check(source.unchanged()) { "Selected file changed during upload." }
                        out.write(frame.tail)
                    } else out.write(body ?: byteArrayOf())
                }
                storageWire(serverOrigin(auth.address),auth.fingerprint,path,method,headers,
                    if(method == "GET") null else contentType,length,write,connectTimeoutMs,
                    if(source != null) 120000 else readTimeoutMs,cancel,
                    beforeHeaders = {
                        cancel.check()
                        require(source == null || source.unchanged()) { "The file changed before send." }
                        sent = method != "GET"
                    }, afterHeaders = storageAfterHeaders) { code, response ->
                status = code
                when(code) {
                    200 -> try { readAnswer(response, answerLimit(path)) } catch (e: AnswerRefusedException) { if (sent) throw StorageUnclear() else throw e }
                    401 -> { ticket?.let { auth.tickets.clear(it) }; throw SessionExpiredException() }
                    400, 403 -> {
                        val raw = response.readNBytes(65536)
                        val fields = try { raw?.let { createErrorKeys(it) }.orEmpty().intersect(setOf("url", "filename", "content", "checksum", "checksum-algorithm", "notes", "protected", "storage", "template")) } catch (_: Exception) { emptySet() } finally { raw?.fill(0) }
                        throw StorageRejected(code, fields)
                    }
                    else -> if (sent) throw StorageUnclear() else throw StorageRejected(code)
                }
                }
                }
            } catch (e: SessionExpiredException) { throw e }
            catch (e: AnswerRefusedException) { throw e }
            catch (e: StorageRejected) { throw e }
            catch (e: StorageUnclear) { throw e }
            catch (_: java.util.concurrent.CancellationException) { if (sent) throw StorageUnclear() else throw StorageCancelledBeforeSend() }
            catch (e: Exception) { if (sent) throw StorageUnclear() else throw java.io.IOException("Storage request stopped before its body was sent.", e) }
            finally { cancel.detach(); trace.end(status = status) }
        }
    }

    /**
     * One API call. A GET goes through [HttpsURLConnection]: the JDK may re-send a GET once when the connection drops before
     * the status line, which is harmless for a read. A request with a body (every POST and PUT, and every DELETE, whose
     * empty body only marks it as a mutation) goes through [storageWire] instead: one TLS socket, `Connection: close`, no
     * retry, reconnect or redirect layer, so it reaches the server at most once. Both answer through [mapReply].
     */
    private fun exchange(
        address: String, tokenId: String, secret: String, fingerprint: String, path: String, body: ByteArray?, kind: String,
        missing: String = GUEST_NOT_FOUND, badRequest: String? = null, method: String = "POST", auth: Connection? = null, retry: Boolean = true, contentType: String = POWER_CONTENT_TYPE, readCreateErrors: Boolean = false,
    ): String {
        if (auth == null && '!' !in tokenId) return Connection(address, tokenId, secret, fingerprint, kind).use {
            exchange(address, tokenId, secret, fingerprint, path, body, kind, missing, badRequest, method, it, contentType = contentType, readCreateErrors = readCreateErrors)
        }
        val mutation = body != null
        // Under the Hood: one span per direct PVE call (PdmClient traces its own). Path only, never headers or bodies.
        val trace = if (kind == PDM_KIND) null else Trace.begin(TraceSource.PveApi, "${if (body == null) "GET" else method} ${Redact.url(path)}",
            mapOf("url" to Redact.url(address.trimEnd('/') + path)))
        var traceStatus: Int? = null
        var traceBytesIn: Long? = null
        var traceFailure: Throwable? = null
        try {
            val header = if (auth?.passwordLogin == true) null else authorizationHeader(kind, tokenId, secret)
            val origin = serverOrigin(address)
            validateIdentity(tokenId, DIRECT_PVE_KIND)
            require(secret.isNotBlank() && secret.none { it == '\r' || it == '\n' }) { if ('!' !in tokenId) "Enter the password." else "Enter the API token secret." }
            pinnedSslContext(fingerprint) // A malformed pin is refused before any login or connection.
            val headers = linkedMapOf<String, String>()
            if (header != null) headers["Authorization"] = header
            val ticket = if (auth?.passwordLogin == true) auth.tickets.get(auth) else null
            if (ticket != null) {
                headers["Cookie"] = ticket.cookie
                if (mutation) headers["CSRFPreventionToken"] = ticket.csrf
            }
            val reply = if (body != null) sendOnce(origin, fingerprint, path, method, headers, body, contentType, readCreateErrors)
                else readOnce(origin, fingerprint, path, headers, readCreateErrors)
            traceStatus = reply.code
            reply.text?.let { traceBytesIn = it.length.toLong() }
            return mapReply(reply, mutation, kind, path, missing, badRequest, readCreateErrors, auth, ticket, retry) ?: run {
                // A refused ticket on a read: log in once more and read once more. A mutation never gets here.
                try { auth!!.tickets.get(auth) } catch (e: LoginRefusedException) { throw SessionExpiredException() }
                exchange(address, tokenId, secret, fingerprint, path, null, kind, missing, badRequest, method, auth, false, contentType, readCreateErrors)
            }
        } catch (t: Throwable) {
            traceFailure = t
            throw t
        } finally {
            trace?.end(status = traceStatus, bytesIn = traceBytesIn, bytesOut = body?.size?.toLong(), error = traceFailure)
        }
    }

    /** What one exchange brought back: the status, the 200 body, and the field names of a create refusal (never their values). */
    private class Reply(val code: Int, val text: String? = null, val fields: Set<String> = emptySet())

    /** A read. An I/O failure is thrown as it is: nothing changed on the server. */
    private fun readOnce(origin: URI, fingerprint: String, path: String, headers: Map<String, String>, readCreateErrors: Boolean): Reply {
        val connection = openPinnedHttps(origin.resolve(path), fingerprint, connectTimeoutMs, readTimeoutMs)
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        try {
            connectAboveTlsFloor(connection)
            return when (val code = connection.responseCode) {
                200 -> Reply(200, text = connection.inputStream.use { readAnswer(it, answerLimit(path)) })
                400 -> Reply(400, fields = if (readCreateErrors) connection.errorStream?.use { createErrorFields(it) }.orEmpty() else emptySet())
                else -> Reply(code)
            }
        } finally { connection.disconnect() }
    }

    /**
     * A mutation, written exactly once on its own TLS socket. The pin (or platform TLS with host name checks) is verified in
     * the handshake, before any byte of the request is written. Once the request head starts out, any failure to read a
     * complete answer is [AmbiguousPowerResultException]. Proxmox answers HTTP 501 to a DELETE that declares a body, so a
     * DELETE carries no body and no body headers at all, as it did through [HttpsURLConnection].
     */
    private fun sendOnce(origin: URI, fingerprint: String, path: String, method: String, headers: Map<String, String>,
        body: ByteArray, contentType: String, readCreateErrors: Boolean): Reply {
        require(method in setOf("POST", "PUT", "DELETE")) { "Unsupported request method." }
        val bodyless = method == "DELETE"
        var sent = false
        try {
            return storageWire(origin, fingerprint, path, method, headers,
                if (bodyless) null else contentType, if (bodyless) null else body.size.toLong(),
                if (bodyless) null else { out -> try { out.write(body); out.flush() } finally { if (readCreateErrors) body.fill(0) } },
                connectTimeoutMs, readTimeoutMs, UploadCancel(),
                beforeHeaders = { sent = true }) { code, response ->
                when (code) {
                    200 -> Reply(200, text = readAnswer(response, answerLimit(path))) // Too large or too deep after the send: ambiguous.
                    400 -> Reply(400, fields = if (readCreateErrors) createErrorFields(response) else emptySet())
                    else -> Reply(code)
                }
            }
        } catch (e: Exception) {
            // Before the head went out nothing reached the server (connect, TLS or pin refusal): report it as it is.
            if (sent) throw AmbiguousPowerResultException() else throw e
        } finally { if (readCreateErrors) body.fill(0) }
    }

    /**
     * The status rules for both paths. Returns the 200 body, or null when a read that met a refused ticket should be read once
     * more under a fresh one. A sent mutation is never replayed: a redirect, a 5xx, or (for a create) a 404 or an unexpected
     * status after the send is [AmbiguousPowerResultException].
     */
    private fun mapReply(reply: Reply, mutation: Boolean, kind: String, path: String, missing: String, badRequest: String?,
        readCreateErrors: Boolean, auth: Connection?, ticket: PveTicket?, retry: Boolean): String? = when (val code = reply.code) {
        200 -> reply.text!!
        401 -> {
            if (auth?.passwordLogin == true && retry) {
                ticket?.let { auth.tickets.clear(it) }
                // A sent mutation may have run before the 401; never replay it under a fresh ticket.
                if (mutation) throw SessionExpiredException()
                null
            } else throw SessionExpiredException()
        }
        403 -> { if (readCreateErrors) throw CreateServerRefusal(403, emptySet()); throw GuestPermissionException() }
        400 -> {
            if (readCreateErrors) throw CreateServerRefusal(400, reply.fields)
            error(badRequest ?: "The server returned HTTP 400.")
        }
        404 -> { if (readCreateErrors && mutation) throw AmbiguousPowerResultException(); error(if (kind == PDM_KIND) "The PDM read returned HTTP 404." else missing) }
        in 300..399 -> {
            if (mutation) throw AmbiguousPowerResultException()
            error("The server redirected the request. Enter the server's direct HTTPS address.")
        }
        in 500..599 -> {
            if (!mutation && path.endsWith("/agent/network-get-interfaces")) error("Guest agent not running")
            if (mutation) throw AmbiguousPowerResultException()
            error("The server returned HTTP $code.")
        }
        else -> { if (readCreateErrors && mutation) throw AmbiguousPowerResultException(); error("The server returned HTTP $code.") }
    }
}

/**
 * An HTTPS connection for one read or one login: no redirects, the given timeouts, and either the pinned context (whose trust
 * manager compares the exact leaf; the host name check then accepts only that leaf) or platform TLS with its host name check.
 */
internal fun openPinnedHttps(target: URI, fingerprint: String, connectTimeoutMs: Int, readTimeoutMs: Int): HttpsURLConnection {
    val context = pinnedSslContext(fingerprint)
    val connection = target.toURL().openConnection() as HttpsURLConnection
    connection.connectTimeout = connectTimeoutMs
    connection.readTimeout = readTimeoutMs
    connection.instanceFollowRedirects = false
    // A new wrapper per call: HttpsURLConnection keys kept-alive sockets by factory identity, so none is ever shared.
    connection.sslSocketFactory = TlsFloorSocketFactory(context?.socketFactory ?: platformSslContext().socketFactory)
    if (context != null) {
        connection.setHostnameVerifier { _, session -> certificateMatches(session.peerCertificates[0] as X509Certificate, fingerprint) }
    }
    return connection
}

/**
 * Connects (TCP and the TLS handshake; HttpsURLConnection writes no request byte here) and refuses a protocol below the floor.
 * The factory already enables only the floor; this also holds if `https.protocols` overrode it.
 */
internal fun connectAboveTlsFloor(connection: HttpsURLConnection) {
    connection.connect()
    try { requireTlsFloor(connection.sslSession.map { it.protocol }.orElse(null)) }
    catch (e: Exception) { connection.disconnect(); throw e }
}

/** Field names of a create refusal from at most 64 KiB of the error answer. The buffer is wiped; values are never decoded. */
private fun createErrorFields(input: java.io.InputStream): Set<String> {
    val raw = input.readNBytes(65536)
    return try { createErrorKeys(raw) } catch (_: Exception) { emptySet() } finally { raw.fill(0) }
}

/** The live node list, the storage index and a storage's status read are refused before HTTP ([isForbiddenNodeRead]). */
internal const val FORBIDDEN_NODE_READ = "The live node list, the live storage index and live storage status are not requested."

internal const val GUEST_NOT_FOUND = "This guest is no longer at that node. Refresh the inventory."

/**
 * Sending a body to `/nodes/{node}/apt/update` would run `apt-get update` on the node. A GET never does that.
 * A GET can rebuild the cached list from the local apt cache and does not use the network. Lift the POST only
 * behind its own confirmation.
 */
internal const val APT_REFRESH_REFUSAL = "Refreshing the package lists is not requested. Only the cached list is read."

/** Optional resource-row text. Blank and non-strings are absent. A control character rejects the value. */
private fun optionalPlain(row: JsonObject, key: String): String? {
    val value = row[key] as? JsonPrimitive ?: return null
    if (!value.isString) return null
    val text = value.content.trim()
    if (text.isEmpty() || text.any { it.isISOControl() }) return null
    return text.take(32)
}

private fun isDirectPath(path: String): Boolean {
    // PDM uses the same path through its own client. A direct PVE session may read it for the release.
    if (path == PVE_VERSION_PATH) return true
    if (path in PDM_READ_PATHS) return false
    if (path.startsWith("/api2/json/pve/")) return false
    if (path.startsWith("/api2/json/remotes/")) return false
    if (path.startsWith("/api2/json/resources/")) return false
    return path.startsWith("/api2/json/")
}

internal fun certificateMatches(certificate: X509Certificate, fingerprint: String): Boolean =
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) } ==
        fingerprint.replace(":", "").trim().lowercase()

/** One context per canonical pin: the trust manager is stateless, so a 5-second poll does not rebuild it. */
private val pinnedContexts = java.util.concurrent.ConcurrentHashMap<String, SSLContext>()
private const val PINNED_CONTEXTS_MAX = 64

/**
 * Exact leaf pin, including private-IP origins; absent pins retain platform TLS validation.
 * The pin trust manager is the only one in the context and does no hostname check.
 */
internal fun pinnedSslContext(fingerprint: String): SSLContext? {
    val pin = fingerprint.replace(":", "").trim().lowercase()
    require(pin.isEmpty() || pin.matches(sha256PinPattern)) { "The SHA-256 certificate fingerprint must contain 64 hexadecimal digits." }
    if (pin.isEmpty()) return null
    pinnedContexts[pin]?.let { return it }
    if (pinnedContexts.size >= PINNED_CONTEXTS_MAX) pinnedContexts.clear()
    return pinnedContexts.computeIfAbsent(pin, ::newPinnedSslContext)
}

private fun newPinnedSslContext(pin: String): SSLContext {
    val trust = object : javax.net.ssl.X509ExtendedTrustManager() {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?): Unit = throw CertificateException("Client trust is not supported")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw PinMismatchException(null, null)
            // Typed refusals (NetworkFailure.kt): the user sees "certificate changed" or the dates, never a generic sentence.
            if (!certificateMatches(leaf, pin)) throw PinMismatchException(ProbedCertificate.of(leaf).fingerprint, spkiSha256(leaf))
            try { leaf.checkValidity() } catch (e: CertificateException) { throw CertificateDateException.of(leaf, e) }
            recordPinnedKey(pin, leaf)
        }
        // The JDK calls these two for an extended manager. After the unchanged certificate check, the negotiated protocol must
        // meet the floor, so every user of a pinned context (the consoles' java.net.http clients too) has it.
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?, s: java.net.Socket?) {
            checkServerTrusted(c, a); floorOf((s as? javax.net.ssl.SSLSocket)?.handshakeSession?.protocol)
        }
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?, e: javax.net.ssl.SSLEngine?) {
            checkServerTrusted(c, a); floorOf(e?.handshakeSession?.protocol)
        }
        private fun floorOf(protocol: String?) {
            if (protocol != null && protocol !in TLS_FLOOR) throw CertificateException("The server negotiated $protocol. TLS 1.2 or newer is required.")
        }
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?, s: java.net.Socket?) = checkClientTrusted(c, a)
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?, e: javax.net.ssl.SSLEngine?) = checkClientTrusted(c, a)
    }
    return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
}

internal class CreateServerRefusal(val status: Int, val fields: Set<String>) : IllegalStateException("The server refused the request.")

/** Inspect only JSON object keys; reflected error VALUES are never decoded to immutable Strings. */
private fun createErrorKeys(bytes: ByteArray): Set<String> {
    val found = mutableSetOf<String>()
    var i = 0; var depth = 0; var errorsDepth = -1
    fun whitespace(n: Int): Int { var p = n; while (p < bytes.size && bytes[p].toInt() in listOf(9, 10, 13, 32)) p++; return p }
    while (i < bytes.size) {
        when (bytes[i].toInt()) {
            123 -> { depth++; i++ }
            125 -> { if (depth == errorsDepth) errorsDepth = -1; depth--; i++ }
            34 -> {
                val start = ++i; var escaped = false
                while (i < bytes.size && bytes[i].toInt() != 34) { if (bytes[i].toInt() == 92) { escaped = true; i++ }; i++ }
                val end = i; i++
                val colon = whitespace(i)
                if (!escaped && colon < bytes.size && bytes[colon].toInt() == 58) {
                    if (depth == 1 && end - start == 6 && "errors".indices.all { bytes[start + it].toInt() == "errors"[it].code }) {
                        val value = whitespace(colon + 1)
                        if (value < bytes.size && bytes[value].toInt() == 123) errorsDepth = depth + 1
                    } else if (depth == errorsDepth && end - start in 1..64) {
                        // Compare bytes with known field names before constructing any key text.
                        val known = listOf("vmid", "name", "hostname", "node", "pool", "tags", "ostype", "memory", "balloon", "shares", "sockets", "cores", "vcpus", "cpulimit", "cpuunits", "cpu", "vga", "machine", "bios", "scsihw", "swap", "searchdomain", "nameserver", "startup", "arch", "affinity", "efidisk0", "tpmstate0", "storage", "ostemplate", "password", "ssh-public-keys", "ha-managed", "agent", "rootfs") +
                            listOf("ide" to 3, "sata" to 5, "scsi" to 30, "virtio" to 15, "mp" to 255, "net" to 31).flatMap { (prefix, max) -> (0..max).map { prefix + it } }
                        known.firstOrNull { k -> k.length == end - start && k.indices.all { bytes[start + it].toInt() == k[it].code } }?.let { found += it }
                    }
                }
            }
            else -> i++
        }
    }
    return found
}
