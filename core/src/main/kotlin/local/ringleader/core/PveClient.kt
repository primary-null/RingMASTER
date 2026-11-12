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

/** Stable guest identity. [scope] is empty for direct PVE and demo. [Guest.node] is the mutable route and is not part of this key. */
data class ResourceId(val remote: String, val scope: String, val kind: String, val vmid: Int) {
    init {
        require(scope.isEmpty() || pdmScopeOrNull(scope) != null) { "The guest scope is not a PDM remote id." }
    }
}

const val DEMO_REMOTE_KEY = "demo"
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
        if (remote != DEMO_REMOTE_KEY) require(serverOrigin(remote).toString() == remote) { "The guest is missing its connection identity." }
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
    val row = Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject ?: error("The server returned no guest details.")
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
    val rows = Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: error("Server returned no resource list.")
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
        val base = boundGuestPath(connection, guest).removeSuffix("/status/current")
        require(guest.kind == "VM") { "Bluetooth scans inside a guest need a QEMU VM with the guest agent." }
        require(guest.status == "running" && !guest.template) { GUEST_BT_NOT_RUNNING }
        val window = seconds.coerceIn(3, 30)
        return connection.withSecret { secret ->
            try {
                val pid = parseAgentExecPid(http.direct(connection, "$base/agent/exec",
                    agentExecBody(bluezScanCommand(window)), badRequest = GUEST_BT_AGENT, missing = GUEST_BT_AGENT))
                val deadline = System.nanoTime() + (window + 25) * 1_000_000_000L
                sleep(window * 1000L)
                while (true) {
                    val status = parseAgentExecStatus(http.direct(connection,
                        "$base/agent/exec-status?pid=$pid", badRequest = GUEST_BT_AGENT, missing = GUEST_BT_AGENT))
                    if (status.exited) {
                        check(!status.truncated) { "The scan output from the VM was truncated." }
                        return@withSecret parseBluezScanOutput(status.out, guestBluetoothSource(guest), java.time.Instant.now())
                    }
                    check(System.nanoTime() < deadline) { GUEST_BT_TIMEOUT }
                    sleep(1000)
                }
                @Suppress("UNREACHABLE_CODE") error(GUEST_BT_TIMEOUT)
            } catch (e: GuestPermissionException) { error(GUEST_BT_NO_PERMISSION) }
            catch (e: AmbiguousPowerResultException) { error(GUEST_BT_AGENT) }
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
}

internal val PDM_READ_PATHS = setOf(
    "/api2/json/version",
    "/api2/json/remotes/remote",
    "/api2/json/resources/list?max-age=0",
)

internal class ProxmoxTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) {
    fun direct(connection: Connection, path: String, body: ByteArray? = null,
        missing: String = GUEST_NOT_FOUND, badRequest: String? = null, method: String = "POST"): String {
        require(isDirectPath(path)) { DIRECT_REQUEST_REFUSAL }
        require(!isForbiddenNodeRead(path)) { "The live node list and live storage index are not requested." }
        require(!isAptListRefresh(path, body != null)) { APT_REFRESH_REFUSAL }
        return connection.withSecret { exchange(connection.address, connection.tokenId, it, connection.fingerprint,
            path, body, connection.kind, missing, badRequest, method, connection) }
    }

    fun direct(
        address: String, tokenId: String, secret: String, fingerprint: String, path: String, body: ByteArray? = null,
        missing: String = GUEST_NOT_FOUND, badRequest: String? = null, method: String = "POST",
    ): String {
        require(isDirectPath(path)) { DIRECT_REQUEST_REFUSAL }
        require(!isForbiddenNodeRead(path)) { "The live node list and live storage index are not requested." }
        require(!isAptListRefresh(path, body != null)) { APT_REFRESH_REFUSAL }
        return exchange(address, tokenId, secret, fingerprint, path, body, DIRECT_PVE_KIND, missing, badRequest, method)
    }

    fun pdm(address: String, tokenId: String, secret: String, fingerprint: String, path: String): String {
        require(path in PDM_READ_PATHS) { "A PDM request cannot use a direct Proxmox VE path." }
        return exchange(address, tokenId, secret, fingerprint, path, null, PDM_KIND)
    }

    private fun exchange(
        address: String, tokenId: String, secret: String, fingerprint: String, path: String, body: ByteArray?, kind: String,
        missing: String = GUEST_NOT_FOUND, badRequest: String? = null, method: String = "POST", auth: Connection? = null, retry: Boolean = true,
    ): String {
        if (auth == null && '!' !in tokenId) return Connection(address, tokenId, secret, fingerprint, kind).use {
            exchange(address, tokenId, secret, fingerprint, path, body, kind, missing, badRequest, method, it)
        }
        // Under the Hood: one span per direct PVE call (PdmClient traces its own). Path only, never headers or bodies.
        val trace = if (kind == PDM_KIND) null else Trace.begin(TraceSource.PveApi, "${if (body == null) "GET" else method} ${Redact.url(path)}",
            mapOf("url" to Redact.url(address.trimEnd('/') + path)))
        var traceStatus: Int? = null
        var traceBytesIn: Long? = null
        var traceFailure: Throwable? = null
        try {
        val header = if (auth?.passwordLogin == true) null else authorizationHeader(kind, tokenId, secret)
        val connection = open(address, tokenId, secret, fingerprint, path, header)
        if (auth?.passwordLogin == true) {
            val ticket = auth.tickets.get(auth)
            connection.setRequestProperty("Cookie", ticket.cookie)
            if (body != null) connection.setRequestProperty("CSRFPreventionToken", ticket.csrf)
        }
        var sent = false
        try {
            if (body != null && method == "DELETE") {
                // Proxmox rejects any DELETE request body with HTTP 501; parameters belong in the path.
                // The empty body still marks this as a mutation, so a lost reply stays ambiguous.
                connection.requestMethod = method
                if (header != null) connection.setRequestProperty("Authorization", header)
                connection.connect()
                sent = true
            } else if (body != null) {
                connection.requestMethod = method
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.setRequestProperty("Content-Type", POWER_CONTENT_TYPE)
                if (header != null) connection.setRequestProperty("Authorization", header)
                val output = connection.outputStream
                sent = true
                output.use { it.write(body); it.flush() }
            }
            return when (val code = connection.responseCode.also { traceStatus = it }) {
                200 -> connection.inputStream.bufferedReader().use { it.readText() }.also { traceBytesIn = it.length.toLong() }
                401 -> {
                    if (auth?.passwordLogin == true && retry) {
                        auth.tickets.clear()
                        // A sent mutation may have run before the 401; never replay it under a fresh ticket.
                        if (body != null && sent) throw SessionExpiredException()
                        try { auth.tickets.get(auth) } catch (e: LoginRefusedException) { throw SessionExpiredException() }
                        return exchange(address, tokenId, secret, fingerprint, path, body, kind, missing, badRequest, method, auth, false)
                    }
                    throw SessionExpiredException()
                }
                403 -> throw GuestPermissionException()
                400 -> error(badRequest ?: "The server returned HTTP 400.")
                404 -> error(if (kind == PDM_KIND) "The PDM read returned HTTP 404." else missing)
                in 300..399 -> {
                    if (body != null && sent) throw AmbiguousPowerResultException()
                    error("The server redirected the request. Enter the server's direct HTTPS address.")
                }
                in 500..599 -> {
                    if (body == null && path.endsWith("/agent/network-get-interfaces")) error("Guest agent not running")
                    if (body != null && sent) throw AmbiguousPowerResultException()
                    error("The server returned HTTP $code.")
                }
                else -> error("The server returned HTTP ${connection.responseCode}.")
            }
        } catch (e: AmbiguousPowerResultException) {
            throw e
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: java.io.IOException) {
            if (body != null && sent) throw AmbiguousPowerResultException() else throw e
        } finally { connection.disconnect() }
        } catch (t: Throwable) {
            traceFailure = t
            throw t
        } finally {
            trace?.end(status = traceStatus, bytesIn = traceBytesIn, bytesOut = body?.size?.toLong(), error = traceFailure)
        }
    }

    private fun open(address: String, tokenId: String, secret: String, fingerprint: String, path: String, header: String?): HttpsURLConnection {
        val origin = serverOrigin(address)
        validateIdentity(tokenId, DIRECT_PVE_KIND)
        require(secret.isNotBlank() && secret.none { it == '\r' || it == '\n' }) { if ('!' !in tokenId) "Enter the password." else "Enter the API token secret." }
        val context = pinnedSslContext(fingerprint)
        val connection = origin.resolve(path).toURL().openConnection() as HttpsURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = false
        if (context != null) {
            connection.sslSocketFactory = context.socketFactory
            connection.setHostnameVerifier { _, session -> certificateMatches(session.peerCertificates[0] as X509Certificate, fingerprint) }
        }
        if (header != null) connection.setRequestProperty("Authorization", header)
        return connection
    }
}

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

private const val DEMO_GIB = 1024L * 1024 * 1024

val demoOverview = Overview(
    2,
    listOf(
        Guest(DEMO_REMOTE_KEY, 120, "debian-workbench", "demo-node-a", "VM", "running"),
        Guest(DEMO_REMOTE_KEY, 121, "web-services", "demo-node-a", "Container", "running", haState = "started"),
        Guest(DEMO_REMOTE_KEY, 130, "backup-lab", "demo-node-b", "VM", "stopped", lock = "backup"),
    ),
    members = listOf(
        RecordedMember(DEMO_REMOTE_KEY, "demo-node-a", "online", null, 0.25, 4.0, 2 * DEMO_GIB, 8 * DEMO_GIB, 5 * DEMO_GIB, 32 * DEMO_GIB, 3600),
        RecordedMember(DEMO_REMOTE_KEY, "demo-node-b", "online", "maintenance", 0.0, 4.0, null, 8 * DEMO_GIB, 4 * DEMO_GIB, 32 * DEMO_GIB, 7200),
    ),
    storage = listOf(
        RecordedStorage(DEMO_REMOTE_KEY, "demo-node-a", "local-lvm", false, 10 * DEMO_GIB, 40 * DEMO_GIB, "available", "lvmthin", "images"),
        RecordedStorage(DEMO_REMOTE_KEY, "demo-node-a", "shared-data", true, 20 * DEMO_GIB, 100 * DEMO_GIB, "available", "directory", "images,backup"),
        RecordedStorage(DEMO_REMOTE_KEY, "demo-node-b", "shared-data", true, 20 * DEMO_GIB, 100 * DEMO_GIB, "available", "directory", "images,backup"),
    ),
)

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
            val leaf = chain?.firstOrNull() ?: throw CertificateException("No server certificate")
            if (!certificateMatches(leaf, pin)) throw CertificateException("Certificate fingerprint changed")
            leaf.checkValidity()
        }
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?, s: java.net.Socket?) = checkServerTrusted(c, a)
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?, e: javax.net.ssl.SSLEngine?) = checkServerTrusted(c, a)
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?, s: java.net.Socket?) = checkClientTrusted(c, a)
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?, e: javax.net.ssl.SSLEngine?) = checkClientTrusted(c, a)
    }
    return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
}
