package local.ringleader.core

import kotlinx.serialization.json.*

/*
 * Node services, system log and package updates (0.21.4, read-only). Verified 2026-11-09 against the API viewer schema and
 * pve-manager PVE/API2/Services.pm, PVE/API2/Nodes.pm (`syslog`), PVE/API2/APT.pm and pve-common PVE/Tools.pm (`dump_journal`).
 *
 *  GET /nodes/{node}/services        Sys.Audit on /nodes/{node}. Rows: service, name, desc, state, unit-state, active-state.
 *                                    The last two are new; older servers return state only. `state` is systemd's SubState
 *                                    (or the Result of a finished one-shot), `unit-state` UnitFileState, `active-state` ActiveState.
 *  GET /nodes/{node}/syslog          Sys.Syslog on /nodes/{node} (Sys.Audit is not enough). Params start, limit, service, since, until.
 *                                    The node runs `journalctl -o short` and numbers every line from 1; lines with n > start are
 *                                    returned, at most `limit` of them (limit 0 means 50). The top-level `total` counts every line of
 *                                    the whole output. An empty log answers one line "no content" with total 1.
 *                                    `since`/`until` are server-local date strings with at most second resolution, so follow uses
 *                                    `start`: it asks for the line it already holds plus what came after (see [checkSyslogFollow]).
 *  GET /nodes/{node}/apt/update      Sys.Modify on /nodes/{node}. The node's cached list of upgradable packages. RingMASTER never
 *                                    sends the POST on this path (it would run `apt-get update`); the transport refuses it.
 */

private const val NODE_API = "/api2/json/nodes/"

const val NODE_TOOLS_DIRECT_ONLY = "Services, the system log and package updates are read from the node directly. Open a direct Proxmox VE connection."
const val NODE_TOOLS_NODE_NOT_ONLINE = "This node is not online in the last inventory. Nothing was requested."
const val NODE_TOOLS_NODE_UNKNOWN = "This node is not in the recorded inventory. Nothing was requested."
const val NODE_SERVICES_NOT_FOUND = "The node services read returned HTTP 404."
const val NODE_SERVICES_REFUSED = "The node services path was refused."
const val NODE_SERVICES_EMPTY_BODY = "The server returned no service list."
const val NODE_SERVICES_DENIED = "Not permitted. Listing services needs Sys.Audit on the node."
const val NODE_SYSLOG_NOT_FOUND = "The system log read returned HTTP 404."
const val NODE_SYSLOG_REFUSED = "The system log request was refused."
const val NODE_SYSLOG_EMPTY_BODY = "The server returned no system log."
const val NODE_SYSLOG_DENIED = "Not permitted. Reading the system log needs Sys.Syslog on the node."
const val NODE_SYSLOG_RESYNCED = "The node's log changed underneath (rotation or clean-up), so the latest lines were loaded again."
const val NODE_UPDATES_NOT_FOUND = "The package update read returned HTTP 404."
const val NODE_UPDATES_REFUSED = "The package update path was refused."
const val NODE_UPDATES_EMPTY_BODY = "The server returned no package list."
const val NODE_UPDATES_DENIED = "Not permitted. Listing package updates needs Sys.Modify on the node."
const val NODE_UPDATES_NOTE = "From the node's last package-list refresh. RingMASTER only reads it and never refreshes it."

private val serviceUnitName = Regex("[A-Za-z0-9_.@:-]{1,128}")

private fun nodeSegment(node: String): String =
    encodePathSegment(pveNodeNameOrNull(node) ?: throw IllegalArgumentException("The node name cannot be requested."))

private fun cleanText(value: String?, max: Int): String? {
    val text = value?.filter { it == '\t' || !it.isISOControl() }?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    return text.take(max).ifEmpty { null }
}

private fun JsonObject.text(key: String, max: Int = 200): String? {
    val value = this[key] as? JsonPrimitive ?: return null
    if (value is JsonNull || !value.isString) return null
    return cleanText(value.content, max)
}

private fun body(raw: String, empty: String): JsonObject =
    (try { Json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }) ?: error(empty)

// ---- services ------------------------------------------------------------------------------------------------------

/**
 * One service row. [name] is the short identifier. [state] is systemd's SubState (running, dead, exited, failed ...),
 * [activeState] its ActiveState, [unitState] whether it is enabled. The last two are null on servers that do not send them.
 */
data class NodeService(
    val name: String,
    val unit: String?,
    val description: String,
    val state: String?,
    val unitState: String?,
    val activeState: String?,
)

enum class ServiceHealth { Running, Exited, Failed, EnabledNotRunning, Stopped, NotInstalled, Transitioning, Unknown }

private val failedResults = setOf("failed", "exit-code", "signal", "core-dump", "watchdog", "start-limit-hit", "timeout", "resources", "protocol", "oom-kill")

fun serviceHealth(service: NodeService): ServiceHealth {
    val state = service.state?.lowercase()
    val active = service.activeState?.lowercase()
    val unit = service.unitState?.lowercase()
    return when {
        unit == "not-found" -> ServiceHealth.NotInstalled
        active == "failed" || state in failedResults || state?.startsWith("failed") == true -> ServiceHealth.Failed
        active in setOf("activating", "deactivating", "reloading", "refreshing", "maintenance") -> ServiceHealth.Transitioning
        state == "exited" || state == "success" -> ServiceHealth.Exited
        state == "running" || state == "listening" || state == "mounted" || (active == "active" && state != "dead") -> ServiceHealth.Running
        active == "inactive" || state == "dead" || state == "stopped" ->
            if (unit == "enabled" || unit == "enabled-runtime") ServiceHealth.EnabledNotRunning else ServiceHealth.Stopped
        else -> ServiceHealth.Unknown
    }
}

fun nodeServicesPath(node: String): String = "$NODE_API${nodeSegment(node)}/services"

fun parseNodeServices(raw: String): List<NodeService> {
    val rows = body(raw, NODE_SERVICES_EMPTY_BODY)["data"] as? JsonArray ?: error(NODE_SERVICES_EMPTY_BODY)
    return rows.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val name = row.text("name", 128) ?: row.text("service", 128) ?: return@mapNotNull null
        val unit = row.text("service", 128)?.takeIf { it != name }
        NodeService(name, unit, row.text("desc", 240).orEmpty(), row.text("state", 64), row.text("unit-state", 64), row.text("active-state", 64))
    }.distinctBy { it.name }.sortedBy { it.name.lowercase() }
}

data class NodeServicesView(val node: String, val services: List<NodeService>)

// ---- system log ----------------------------------------------------------------------------------------------------

/** Lines per request: the first tail, "load earlier" and every follow read. */
const val SYSLOG_PAGE = 500

/** While the panel is visible and Follow is on, the tail is read on this interval. */
const val SYSLOG_FOLLOW_MS = 5_000L

/** The most lines kept in memory. Following drops the oldest ones past this. */
const val SYSLOG_MAX_LINES = 5_000

/** One follow tick reads at most this many pages, so a busy node cannot hold the loop. The rest comes on the next tick. */
const val SYSLOG_CATCH_UP_PAGES = 4

private const val SYSLOG_LIMIT_MAX = 2_000
private const val SYSLOG_LINE_MAX = 8_192

/** One log record. [n] is the line number the node counts from the start of its whole journal output, from 1. */
data class SyslogLine(val n: Int, val text: String)

/** One response. [total] is the node's count of all lines for this filter, not the size of this page. */
data class SyslogPage(val lines: List<SyslogLine>, val total: Int)

data class SyslogView(
    val node: String,
    val service: String?,
    val lines: List<SyslogLine>,
    val total: Int,
    val following: Boolean = true,
    val notice: String? = null,
) {
    val canLoadEarlier: Boolean get() = lines.isNotEmpty() && lines.first().n > 1 && lines.size < SYSLOG_MAX_LINES
}

fun syslogServiceOrNull(value: String?): String? = value?.trim()?.takeIf { it.matches(serviceUnitName) }

/**
 * `start` is the number of lines to skip, so the first line returned is start + 1. [limit] must be 1 or more, because the
 * node reads 0 as 50. The node name is checked as a `pve-node` and encoded; [service] is limited to unit-name characters.
 */
fun syslogPath(node: String, start: Int, limit: Int, service: String? = null): String {
    require(start >= 0 && limit in 1..SYSLOG_LIMIT_MAX) { "The system log request is not usable." }
    val unit = if (service == null) null else syslogServiceOrNull(service) ?: throw IllegalArgumentException("The service name cannot be requested.")
    return "$NODE_API${nodeSegment(node)}/syslog?start=$start&limit=$limit" + (unit?.let { "&service=${encodePathSegment(it)}" } ?: "")
}

fun parseSyslogPage(raw: String): SyslogPage {
    val root = body(raw, NODE_SYSLOG_EMPTY_BODY)
    val rows = root["data"] as? JsonArray ?: error(NODE_SYSLOG_EMPTY_BODY)
    val lines = ArrayList<SyslogLine>(rows.size)
    for (element in rows) {
        val row = element as? JsonObject ?: continue
        val n = (row["n"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 1..Int.MAX_VALUE } ?: continue
        val text = (row["t"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
        lines += SyslogLine(n.toInt(), text.trimEnd('\r', '\n').filter { it == '\t' || !it.isISOControl() }.take(SYSLOG_LINE_MAX))
    }
    val reported = (root["total"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
    // An empty journal answers a single "no content" line so a table widget has a row. It is not a log line.
    if (lines.size == 1 && lines[0].n == 1 && lines[0].text == "no content" && (reported ?: 1) <= 1) return SyslogPage(emptyList(), 0)
    val sorted = lines.sortedBy { it.n }.distinctBy { it.n }
    return SyslogPage(sorted, maxOf(reported ?: 0, sorted.lastOrNull()?.n ?: 0))
}

/** Where the last [page] lines of a log with [total] lines begin. */
fun syslogTailStart(total: Int, page: Int = SYSLOG_PAGE): Int = maxOf(0, total - page)

/** Request that re-reads the newest line already held and everything after it, or null when nothing is held. */
fun syslogFollowRequest(current: List<SyslogLine>): Pair<Int, Int>? {
    val last = current.lastOrNull() ?: return null
    return maxOf(0, last.n - 1) to (SYSLOG_PAGE + 1)
}

sealed interface SyslogFollowResult {
    /** [lines] are new and contiguous with the held tail. [more] means the node has further lines to read now. */
    data class Appended(val lines: List<SyslogLine>, val total: Int, val more: Boolean) : SyslogFollowResult
    data class NothingNew(val total: Int) : SyslogFollowResult
    /** The numbering no longer matches what is held (rotation or clean-up). Load the tail again. */
    data object Resync : SyslogFollowResult
}

/**
 * Judge one follow read made with [syslogFollowRequest]. The first returned line must be the held tail line itself, same
 * number and same text; anything else means the node's numbering moved. A total below the held line number, or a gap after
 * it, also means the log changed. Only a verified read appends.
 */
fun checkSyslogFollow(last: SyslogLine, page: SyslogPage): SyslogFollowResult {
    if (page.total < last.n) return SyslogFollowResult.Resync
    val first = page.lines.firstOrNull() ?: return SyslogFollowResult.Resync
    if (first.n != last.n || first.text != last.text) return SyslogFollowResult.Resync
    val fresh = page.lines.drop(1)
    if (fresh.isEmpty()) return SyslogFollowResult.NothingNew(page.total)
    if (fresh.first().n != last.n + 1 || fresh.zipWithNext().any { (a, b) -> b.n != a.n + 1 }) return SyslogFollowResult.Resync
    return SyslogFollowResult.Appended(fresh, page.total, more = fresh.last().n < page.total)
}

/** Adds the lines newer than the last one held. A page that was already applied (two reads raced) adds nothing, so numbers never repeat. */
fun appendSyslog(current: List<SyslogLine>, fresh: List<SyslogLine>): List<SyslogLine> {
    val last = current.lastOrNull()?.n ?: 0
    val added = fresh.filter { it.n > last }
    return if (added.isEmpty()) current else (current + added).takeLast(SYSLOG_MAX_LINES)
}

/** Request for the page just before the oldest line held, or null at the start of the log or when the buffer is full. */
fun syslogEarlierRequest(current: List<SyslogLine>): Pair<Int, Int>? {
    val first = current.firstOrNull() ?: return null
    if (first.n <= 1 || current.size >= SYSLOG_MAX_LINES) return null
    val start = maxOf(0, first.n - 1 - SYSLOG_PAGE)
    return start to (first.n - 1 - start)
}

/** The lines before [current] from one earlier read, or null when they do not end right before it. */
fun applySyslogEarlier(current: List<SyslogLine>, page: SyslogPage): List<SyslogLine>? {
    val first = current.firstOrNull() ?: return null
    val older = page.lines.filter { it.n < first.n }
    if (older.isEmpty() || older.last().n != first.n - 1 || older.zipWithNext().any { (a, b) -> b.n != a.n + 1 }) return null
    return older + current
}

/** Substring filter for the lines on screen. Matching is case-insensitive; a blank query keeps everything. */
fun filterSyslog(lines: List<SyslogLine>, query: String): List<SyslogLine> {
    val needle = query.trim()
    return if (needle.isEmpty()) lines else lines.filter { it.text.contains(needle, ignoreCase = true) }
}

// ---- package updates -----------------------------------------------------------------------------------------------

/** One upgradable package from the node's cached list. Versions are shown as sent. */
data class PackageUpdate(
    val name: String,
    val title: String?,
    val description: String?,
    val newVersion: String?,
    val oldVersion: String?,
    val arch: String?,
    val origin: String?,
    val priority: String?,
    val section: String?,
)

data class NodeUpdatesView(val node: String, val updates: List<PackageUpdate>)

/** The GET path only. The same path with POST refreshes the lists and is never built by this app. */
fun aptUpdatePath(node: String): String = "$NODE_API${nodeSegment(node)}/apt/update"

internal fun isAptListRefresh(path: String, hasBody: Boolean): Boolean = hasBody && path.substringBefore('?').endsWith("/apt/update")

fun parsePackageUpdates(raw: String): List<PackageUpdate> {
    val root = body(raw, NODE_UPDATES_EMPTY_BODY)
    if (!root.containsKey("data")) error(NODE_UPDATES_EMPTY_BODY)
    val rows = when (val data = root["data"]) {
        is JsonNull -> return emptyList()
        is JsonArray -> data
        else -> error(NODE_UPDATES_EMPTY_BODY)
    }
    return rows.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val name = row.text("Package", 128) ?: return@mapNotNull null
        PackageUpdate(name, row.text("Title", 240), row.text("Description", 600), row.text("Version", 96), row.text("OldVersion", 96),
            row.text("Arch", 32), row.text("Origin", 64), row.text("Priority", 32), row.text("Section", 64))
    }.sortedWith(compareBy<PackageUpdate>({ if (it.origin.equals("Proxmox", ignoreCase = true)) 0 else 1 }, { it.name.lowercase() }))
}

// ---- demo ----------------------------------------------------------------------------------------------------------

/** Handwritten sample rows in the shape of the services call. Not a captured response. */
val demoNodeServices: List<NodeService> = parseNodeServices("""{"data":[
 {"service":"pveproxy","name":"pveproxy","desc":"PVE API Proxy Server","state":"running","unit-state":"enabled","active-state":"active"},
 {"service":"pvedaemon","name":"pvedaemon","desc":"PVE API Daemon","state":"running","unit-state":"enabled","active-state":"active"},
 {"service":"pvestatd","name":"pvestatd","desc":"PVE Status Daemon","state":"running","unit-state":"enabled","active-state":"active"},
 {"service":"pve-cluster","name":"pve-cluster","desc":"PVE Cluster Filesystem","state":"running","unit-state":"enabled","active-state":"active"},
 {"service":"corosync","name":"corosync","desc":"Corosync Cluster Engine","state":"dead","unit-state":"disabled","active-state":"inactive"},
 {"service":"pve-firewall","name":"pve-firewall","desc":"Proxmox VE firewall","state":"running","unit-state":"enabled","active-state":"active"},
 {"service":"pve-ha-crm","name":"pve-ha-crm","desc":"PVE Cluster HA Resource Manager Daemon","state":"running","unit-state":"enabled","active-state":"active"},
 {"service":"pve-ha-lrm","name":"pve-ha-lrm","desc":"PVE Local HA Resource Manager Daemon","state":"dead","unit-state":"enabled","active-state":"inactive"},
 {"service":"pvescheduler","name":"pvescheduler","desc":"Proxmox VE scheduler","state":"running","unit-state":"enabled","active-state":"active"},
 {"service":"spiceproxy","name":"spiceproxy","desc":"PVE SPICE Proxy Server","state":"running","unit-state":"enabled","active-state":"active"},
 {"service":"pve-guests","name":"pve-guests","desc":"PVE guests","state":"exited","unit-state":"enabled","active-state":"active"},
 {"service":"sshd","name":"sshd","desc":"OpenBSD Secure Shell server","state":"running","unit-state":"enabled","active-state":"active"},
 {"service":"chrony","name":"chrony","desc":"chrony, an NTP client/server","state":"running","unit-state":"enabled","active-state":"active"},
 {"service":"smartd","name":"smartd","desc":"Self Monitoring and Reporting Technology (SMART) Daemon","state":"failed","unit-state":"enabled","active-state":"failed"}
]}""")

const val DEMO_SYSLOG_START_TOTAL = 1_240
private val demoSyslogTemplates = listOf(
    "pvedaemon[2184]: <root@pam> successful auth for user 'root@pam'",
    "pvestatd[1190]: status update time (4.121 seconds)",
    "pveproxy[2201]: worker 2210 started",
    "systemd[1]: Started user@1000.service - User Manager for UID 1000.",
    "pvedaemon[2185]: <root@pam> starting task UPID:%NODE%:00002A3B:0003F0A1:6911F2C0:vncproxy:120:root@pam:",
    "pmxcfs[1042]: [status] notice: received log",
    "kernel: vmbr0: port 3(tap120i0) entered forwarding state",
    "pve-firewall[1183]: firewall update time (0.012 seconds)",
    "smartd[884]: Device: /dev/sda [SAT], SMART Prefailure Attribute: 1 Raw_Read_Error_Rate changed from 80 to 79",
    "cron[860]: (root) CMD (command -v debian-sa1 > /dev/null && debian-sa1 1 1)",
)

/** Sample line [n] of a node's log. Deterministic, so the demo can grow without storing anything. */
fun demoSyslogLine(node: String, n: Int): SyslogLine {
    val second = n % 60
    val minute = (n / 60) % 60
    val text = String.format(java.util.Locale.ROOT, "Nov 09 14:%02d:%02d %s %s", minute, second, node,
        demoSyslogTemplates[n % demoSyslogTemplates.size].replace("%NODE%", node))
    return SyslogLine(n, text)
}

fun demoSyslogPage(node: String, start: Int, limit: Int, total: Int): SyslogPage =
    SyslogPage(((start + 1)..minOf(total, start + limit)).map { demoSyslogLine(node, it) }, total)

/** Handwritten sample rows in the shape of the update list. Not a captured response. */
val demoPackageUpdates: List<PackageUpdate> = parsePackageUpdates("""{"data":[
 {"Package":"pve-manager","Title":"pve-manager","Description":"Proxmox Virtual Environment Management Tools","Version":"9.2.23","OldVersion":"9.2.21","Arch":"all","Origin":"Proxmox","Priority":"optional","Section":"admin"},
 {"Package":"proxmox-kernel-6.14","Title":"proxmox-kernel-6.14","Description":"Latest Proxmox Kernel Image","Version":"6.14.11-6","OldVersion":"6.14.11-4","Arch":"all","Origin":"Proxmox","Priority":"optional","Section":"admin"},
 {"Package":"qemu-server","Title":"qemu-server","Description":"Qemu Server Tools","Version":"9.2.6","OldVersion":"9.2.4","Arch":"amd64","Origin":"Proxmox","Priority":"optional","Section":"admin"},
 {"Package":"libpve-common-perl","Title":"libpve-common-perl","Description":"Proxmox VE base library","Version":"9.2.9","OldVersion":"9.2.8","Arch":"all","Origin":"Proxmox","Priority":"optional","Section":"perl"},
 {"Package":"openssl","Title":"openssl","Description":"Secure Sockets Layer toolkit - cryptographic utility","Version":"3.5.4-1~deb13u1","OldVersion":"3.5.1-1","Arch":"amd64","Origin":"Debian","Priority":"optional","Section":"utils"},
 {"Package":"curl","Title":"curl","Description":"command line tool for transferring data with URL syntax","Version":"8.14.1-2+deb13u2","OldVersion":"8.14.1-2","Arch":"amd64","Origin":"Debian","Priority":"optional","Section":"web"},
 {"Package":"tzdata","Title":"tzdata","Description":"time zone and daylight-saving time data","Version":"2025c-0+deb13u1","OldVersion":"2025b-5","Arch":"all","Origin":"Debian","Priority":"required","Section":"localization"}
]}""")
