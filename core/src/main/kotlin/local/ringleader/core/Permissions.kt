package local.ringleader.core

import kotlinx.serialization.json.*

/*
 * Permission check (0.21.4, read-only).
 *
 * `GET /api2/json/access/permissions` with no parameter returns the effective privileges of the signed-in identity
 * (API token or ticket user) as { path: { privilege: propagate } }. Verified 2026-11-09 against:
 *   - the API viewer schema (permissions: user => all; params userid, path; returns "Map of path => (Map of privilege => propagate boolean)");
 *   - pve-access-control src/PVE/API2/AccessControl.pm (`permissions`), src/PVE/RPCEnvironment.pm (`get_effective_permissions`,
 *     `permissions`, `$compile_acl_path`) and src/PVE/AccessControl.pm (`roles`, `$privgroups`, `check_path`).
 *
 * What the response contains (RPCEnvironment::get_effective_permissions):
 *   - a fixed set of top-level paths (/, /access, /access/groups, /nodes, /pool, /sdn, /storage, /vms),
 *   - every path that has an ACL entry, and every /vms/{id} and /storage/{id} that a pool lists,
 *   - and only those where the identity has at least one privilege (empty paths are dropped).
 *   Each listed path is the effective set on that path. A privilege's flag says whether children inherit it.
 *   AccessControl::roles walks from / toward the checked path. On every path that is not the final one, a role applies
 *   only when Propagate is set, and an entry with no propagating role does not replace what was carried from above
 *   (`if ($new) { $roles = $new }`). [effectivePrivileges] does the same: a listed ancestor with no propagating privilege
 *   does not hide a privilege propagated from further up. A role set on the path itself still replaces what it would inherit.
 *
 * An empty path is omitted from the response, including a NoAccess path. Guests and storages in the inventory are already
 * audit-filtered, so answering an unlisted guest or storage from its parent is the same answer the server would give.
 * Nodes are not filtered that way. An online node row with no CPU, memory or uptime means no Sys.Audit
 * (extract_node_stats with exclude_stats). [evaluateFeatures] uses that signal and does not report a parent role as
 * Allowed on that node.
 */

const val PERMISSIONS_PATH = "/api2/json/access/permissions"
const val PVE_VERSION_PATH = "/api2/json/version"
const val PERMISSIONS_NOT_FOUND = "The permission read returned HTTP 404."
const val PERMISSIONS_REFUSED = "The permission read path was refused."
const val PERMISSIONS_EMPTY_BODY = "The server returned no permissions."
const val PERMISSIONS_TOO_LARGE = "The permission list is too large to show."
const val PERMISSIONS_DIRECT_ONLY = "Permission checks read a direct Proxmox VE connection. A PDM connection has its own access model."
const val PERMISSIONS_UNREAD = "Permissions have not been read."
const val PERMISSIONS_DENIED = "Not permitted to read this identity's permissions."

private const val PERMISSION_MAX_PATHS = 50_000
private const val PERMISSION_MAX_PRIVILEGES = 256

/**
 * Effective privileges per ACL path, as the server listed them. [paths] maps a path to its privileges, and each privilege
 * to its propagate flag. A privilege that is present is granted on that path whatever the flag says.
 */
data class PermissionDump(val paths: Map<String, Map<String, Boolean>>, val release: String? = null)

enum class PermissionIdentity { Token, User, Sample }

/** A dump plus who it is for. The identity text itself is never kept. */
data class PermissionsView(val dump: PermissionDump, val identity: PermissionIdentity)

private val aclSegment = Regex("[A-Za-z0-9._\\-]+")

/**
 * The ACL path as PVE's `normalize_path` would write it, or null when it cannot be one. Only ASCII letters, digits, dot,
 * dash and underscore are allowed in a segment; slashes are collapsed and a trailing slash is dropped.
 */
fun normalizedAclPathOrNull(raw: String): String? {
    if (raw.isEmpty() || raw.length > 256) return null
    var path = raw.replace(Regex("/+"), "/")
    if (path.length > 1) path = path.removeSuffix("/")
    if (!path.startsWith("/")) path = "/$path"
    if (path == "/") return path
    val parts = path.substring(1).split('/')
    return path.takeIf { parts.size <= 6 && parts.all { it.matches(aclSegment) && !it.all { c -> c == '.' } } }
}

private fun parentAclPath(path: String): String? = when {
    path == "/" -> null
    path.lastIndexOf('/') == 0 -> "/"
    else -> path.substring(0, path.lastIndexOf('/'))
}

/** JSON true, a non-zero number or "1" propagate. Anything else that is present is granted without Propagate. */
private fun propagateFlag(value: JsonElement): Boolean? {
    if (value !is JsonPrimitive || value is JsonNull) return null
    value.booleanOrNull?.let { return it }
    value.longOrNull?.let { return it != 0L }
    return false
}

fun parsePermissions(body: String): PermissionDump {
    val root = try { Json.parseToJsonElement(body) as? JsonObject } catch (_: Exception) { null } ?: error(PERMISSIONS_EMPTY_BODY)
    val data = root["data"]
    if (data is JsonArray && data.isEmpty()) return PermissionDump(emptyMap())
    val tree = data as? JsonObject ?: error(PERMISSIONS_EMPTY_BODY)
    if (tree.size > PERMISSION_MAX_PATHS) error(PERMISSIONS_TOO_LARGE)
    val paths = LinkedHashMap<String, Map<String, Boolean>>()
    for ((rawPath, entry) in tree) {
        val path = normalizedAclPathOrNull(rawPath) ?: continue
        val privileges = entry as? JsonObject ?: continue
        if (privileges.size > PERMISSION_MAX_PRIVILEGES) error(PERMISSIONS_TOO_LARGE)
        val parsed = LinkedHashMap<String, Boolean>()
        for ((name, flag) in privileges) {
            if (!privilegeName.matches(name)) continue
            parsed[name] = propagateFlag(flag) ?: continue
        }
        if (parsed.isNotEmpty()) paths[path] = parsed
    }
    return PermissionDump(paths)
}

private val privilegeName = Regex("[A-Za-z][A-Za-z0-9]*(\\.[A-Za-z0-9]+)+")

/**
 * What the identity holds on one concrete [path]. [source] is the listed path the answer came from: the path itself, its
 * nearest listed parent, or null when neither is listed (no role applies). [notInherited] holds privileges that the parent
 * has without Propagate; they explain a "missing" that looks granted one level up.
 */
data class EffectivePrivileges(
    val path: String,
    val privileges: Set<String>,
    val source: String?,
    val notInherited: Set<String> = emptySet(),
    /** Privileges on a closer listed path that do not propagate. They do not replace [privileges]. */
    val blocked: Set<String> = emptySet(),
    val blockedFrom: String? = null,
) {
    val exact: Boolean get() = source == path
}

fun effectivePrivileges(dump: PermissionDump, rawPath: String): EffectivePrivileges {
    val path = normalizedAclPathOrNull(rawPath) ?: return EffectivePrivileges(rawPath, emptySet(), null)
    dump.paths[path]?.let { return EffectivePrivileges(path, it.keys, path) }
    var parent = parentAclPath(path)
    var blocked = emptySet<String>()
    var blockedFrom: String? = null
    while (parent != null) {
        val listed = dump.paths[parent]
        if (listed != null) {
            val propagating = listed.filterValues { it }.keys
            val localBlocked = listed.filterValues { !it }.keys
            // A listed ancestor with nothing propagating does not erase what a higher path propagated.
            if (propagating.isNotEmpty()) {
                return EffectivePrivileges(path, propagating, parent, localBlocked, blocked, blockedFrom)
            }
            if (blockedFrom == null) {
                blocked = localBlocked
                blockedFrom = parent
            }
        }
        parent = parentAclPath(parent)
    }
    return EffectivePrivileges(path, emptySet(), null, blocked = blocked, blockedFrom = blockedFrom)
}

/**
 * Privileges Proxmox VE defines (pve-access-control `$privgroups` plus Permissions.Modify, checked 2026-11-09 against the
 * upstream tree). [LEGACY_PRIVILEGES] are names older servers accepted that the current tree dropped.
 */
internal val PVE_PRIVILEGES: Set<String> = setOf(
    "VM.Config.Disk", "VM.Config.CPU", "VM.Config.Memory", "VM.Config.Network", "VM.Config.HWType", "VM.Config.Options",
    "VM.Allocate", "VM.Clone", "VM.GuestAgent.Unrestricted", "VM.Migrate", "VM.Replicate", "VM.Snapshot", "VM.Snapshot.Rollback",
    "VM.Config.CDROM", "VM.Config.Cloudinit", "VM.Console", "VM.Backup", "VM.GuestAgent.FileRead", "VM.GuestAgent.FileSystemMgmt",
    "VM.GuestAgent.FileWrite", "VM.PowerMgmt", "VM.Audit", "VM.GuestAgent.Audit",
    "Sys.PowerMgmt", "Sys.Modify", "Sys.Incoming", "Sys.AccessNetwork", "Sys.Console", "Sys.Syslog", "Sys.Audit",
    "Datastore.Allocate", "Datastore.AllocateTemplate", "Datastore.AllocateSpace", "Datastore.Audit",
    "SDN.Allocate", "SDN.Audit", "SDN.Use",
    "Realm.Allocate", "User.Modify", "Group.Allocate", "Realm.AllocateUser",
    "Pool.Allocate", "Pool.Audit",
    "Mapping.Modify", "Mapping.Use", "Mapping.Audit",
    "Permissions.Modify",
)

/** `VM.Monitor` guarded the guest agent on Proxmox VE 8 and earlier. Version 9 dropped it for VM.GuestAgent.* (pve-access-control, 2025-07-17). */
internal val LEGACY_PRIVILEGES: Set<String> = setOf("VM.Monitor")

/** The kind of ACL path a privilege is checked on. */
enum class PermissionScope(val template: String, val collection: String) {
    Root("/", "/"),
    Node("/nodes/{node}", "/nodes"),
    Guest("/vms/{vmid}", "/vms"),
    Storage("/storage/{storage}", "/storage"),
    Sdn("/sdn", "/sdn"),
    Notifications("/mapping/notifications", "/mapping/notifications"),
}

/**
 * One privilege check. With [any] one of [privileges] is enough, otherwise all are needed. [legacy] names privileges that
 * older servers accepted instead; holding only those is "unknown", because the server version decides. [guestKind] limits a
 * guest check to QEMU VMs ("VM") or containers ("Container").
 */
data class PrivilegeNeed(
    val scope: PermissionScope,
    val privileges: List<String>,
    val any: Boolean = false,
    val legacy: List<String> = emptyList(),
    val guestKind: String? = null,
    /** The call is open to any signed-in identity. [privileges] stays empty. */
    val open: Boolean = false,
) {
    fun label(): String {
        if (open) return "Any signed-in identity @ ${scope.template}"
        val names = privileges.joinToString(if (any) " or " else " + ")
        return "$names @ ${scope.template}" + if (legacy.isNotEmpty()) " (PVE 8: ${legacy.joinToString()})" else ""
    }
}

/**
 * A RingMASTER feature and the privileges the server checks for it. [api] names the calls it makes, which is where each
 * requirement was read from. [alsoNeeds] lists alternatives (each an AND of checks): the call works without them, but
 * results are filtered unless one alternative holds, and [alsoNote] says what is hidden.
 */
data class FeatureRequirement(
    val id: String,
    val title: String,
    val group: String,
    val needs: List<PrivilegeNeed>,
    val api: String,
    val note: String? = null,
    val planned: Boolean = false,
    val alsoNeeds: List<List<PrivilegeNeed>> = emptyList(),
    val alsoNote: String? = null,
    /** Extra checks that must all hold before the row is Allowed. Unlike [alsoNeeds], these are not alternatives. */
    val alsoRequires: List<PrivilegeNeed> = emptyList(),
)

const val GROUP_TODAY = "In use"
const val GROUP_PLANNED = "Planned"

private fun need(scope: PermissionScope, vararg privileges: String, any: Boolean = false, legacy: List<String> = emptyList(), kind: String? = null) =
    PrivilegeNeed(scope, privileges.toList(), any, legacy, kind)

/**
 * Requirements per feature. Every row was read from the API viewer schema (permissions block of the named call) and, where
 * the schema prose is not enough, from the Perl handler. Sources, 2026-11-09:
 *  - GET /cluster/resources: user => all; pve-manager PVE/API2/Cluster.pm filters guests by VM.Audit on /vms/{vmid},
 *    node figures by Sys.Audit on /nodes/{node} and storage rows by Datastore.Audit on /storage/{id} (AllocateSpace alone is not enough).
 *  - GET /cluster/tasks: user => all; shows every task only with Sys.Audit on /.
 *  - POST .../status/{start,shutdown,stop,reboot,reset,suspend,resume}: VM.PowerMgmt on /vms/{vmid}.
 *  - POST/DELETE .../snapshot: VM.Snapshot; POST .../snapshot/{name}/rollback: VM.Snapshot or VM.Snapshot.Rollback.
 *  - POST .../vncproxy, spiceproxy, termproxy: VM.Console; POST /nodes/{node}/termproxy: Sys.Console on the node.
 *  - GET .../agent/network-get-interfaces: VM.GuestAgent.Audit or VM.GuestAgent.Unrestricted; POST .../agent/exec and GET .../agent/exec-status: VM.GuestAgent.Unrestricted.
 *  - GET /nodes/{node}/storage/{storage}/content: Datastore.Audit or Datastore.AllocateSpace on the storage; pve-storage
 *    check_volume_access then drops volumes the identity may not read (see the alsoNote).
 *  - GET /nodes/{node}/services: Sys.Audit; GET /nodes/{node}/syslog: Sys.Syslog; GET /nodes/{node}/apt/update: Sys.Modify (a write privilege for a read).
 *  - POST /nodes/{node}/vzdump: VM.Backup on the guest and Datastore.AllocateSpace on the backup storage.
 *  - GET /nodes/{node}/hardware/usb: Sys.Modify on / (not on the node); GET .../hardware/pci: Sys.Audit or Sys.Modify on /.
 *  - GET /cluster/notifications/endpoints/{gotify,webhook}: Mapping.Audit on /mapping/notifications (sendmail and smtp also accept Mapping.Modify).
 */
val FEATURE_REQUIREMENTS: List<FeatureRequirement> = listOf(
    FeatureRequirement("fleet-guests", "Fleet: guest list", GROUP_TODAY, listOf(need(PermissionScope.Guest, "VM.Audit")),
        "GET /cluster/resources; GET .../status/current, .../config, .../snapshot",
        "The server leaves out guests this identity cannot audit instead of refusing the call."),
    FeatureRequirement("fleet-nodes", "Fleet: node figures", GROUP_TODAY, listOf(need(PermissionScope.Node, "Sys.Audit")),
        "GET /cluster/resources (node rows); GET /nodes/{node}/status, .../rrddata",
        "A node without it is still listed, but with no CPU, memory or disk figures."),
    FeatureRequirement("activity", "Activity: everyone's tasks", GROUP_TODAY, listOf(need(PermissionScope.Root, "Sys.Audit")),
        "GET /cluster/tasks",
        "Without it the task feed holds only this identity's own tasks. Another user's task log also needs Sys.Audit on its node."),
    FeatureRequirement("power", "Power actions", GROUP_TODAY, listOf(need(PermissionScope.Guest, "VM.PowerMgmt")),
        "POST .../status/start, shutdown, stop, reboot, reset, suspend, resume"),
    FeatureRequirement("snapshots", "Snapshots: create, delete", GROUP_TODAY, listOf(need(PermissionScope.Guest, "VM.Snapshot")),
        "POST .../snapshot; DELETE .../snapshot/{name}",
        "Listing snapshots needs only VM.Audit."),
    FeatureRequirement("rollback", "Snapshots: roll back", GROUP_TODAY,
        listOf(need(PermissionScope.Guest, "VM.Snapshot", "VM.Snapshot.Rollback", any = true)),
        "POST .../snapshot/{name}/rollback"),
    FeatureRequirement("console", "Guest console", GROUP_TODAY, listOf(need(PermissionScope.Guest, "VM.Console")),
        "POST .../vncproxy, .../spiceproxy, .../termproxy"),
    FeatureRequirement("node-shell", "Node shell", GROUP_TODAY, listOf(need(PermissionScope.Node, "Sys.Console")),
        "POST /nodes/{node}/termproxy"),
    FeatureRequirement("agent-addresses", "Guest addresses (agent)", GROUP_TODAY,
        listOf(need(PermissionScope.Guest, "VM.GuestAgent.Audit", "VM.GuestAgent.Unrestricted", any = true, legacy = listOf("VM.Monitor"), kind = "VM")),
        "GET .../agent/network-get-interfaces",
        "Proxmox VE 9 replaced VM.Monitor with VM.GuestAgent.*. Containers do not use the agent."),
    FeatureRequirement("bluetooth", "Bluetooth scan in a VM", GROUP_TODAY,
        listOf(need(PermissionScope.Guest, "VM.GuestAgent.Unrestricted", legacy = listOf("VM.Monitor"), kind = "VM")),
        "POST .../agent/exec; GET .../agent/exec-status",
        "The scan runs a command in the guest, which only the unrestricted agent privilege allows."),
    FeatureRequirement("storage", "Backups and storage contents", GROUP_TODAY, listOf(need(PermissionScope.Storage, "Datastore.Audit")),
        "GET /cluster/resources (storage rows); GET /nodes/{node}/storage/{storage}/content",
        "The content call also accepts Datastore.AllocateSpace, but RingMASTER lists only the storages the resource list shows, and that list needs Datastore.Audit.",
        alsoNeeds = listOf(
            listOf(need(PermissionScope.Storage, "Datastore.Allocate")),
            listOf(need(PermissionScope.Storage, "Datastore.AllocateSpace"), need(PermissionScope.Guest, "VM.Backup")),
        ),
        alsoNote = "Disk images need VM.Config.Disk on the guest. Backup archives list only with Datastore.AllocateSpace on the storage and VM.Backup on the guest (or Datastore.Allocate on the storage). Snippets need Datastore.Allocate. The server omits the rest without an error.",
        alsoRequires = listOf(need(PermissionScope.Guest, "VM.Config.Disk"))),
    FeatureRequirement("services", "Node services", GROUP_TODAY, listOf(need(PermissionScope.Node, "Sys.Audit")),
        "GET /nodes/{node}/services"),
    FeatureRequirement("node-network", "Node network", GROUP_TODAY,
        listOf(PrivilegeNeed(PermissionScope.Node, emptyList(), open = true)),
        "GET /nodes/{node}/network",
        "Any signed-in identity may read the list. It does not need Sys.Audit.",
        alsoNeeds = listOf(listOf(need(PermissionScope.Sdn, "SDN.Audit", "SDN.Use", any = true))),
        alsoNote = "Bridges are left out unless SDN.Audit or SDN.Use is held on that bridge. The list itself still succeeds."),
    FeatureRequirement("syslog", "System log", GROUP_TODAY, listOf(need(PermissionScope.Node, "Sys.Syslog")),
        "GET /nodes/{node}/syslog",
        "Sys.Audit does not include the log."),
    FeatureRequirement("updates", "Package updates", GROUP_TODAY, listOf(need(PermissionScope.Node, "Sys.Modify")),
        "GET /nodes/{node}/apt/update",
        "Proxmox ties even this list to Sys.Modify, a write privilege. RingMASTER only reads the list and never refreshes it."),
    FeatureRequirement("backup-now", "Backup now", GROUP_PLANNED,
        listOf(need(PermissionScope.Guest, "VM.Backup"), need(PermissionScope.Storage, "Datastore.AllocateSpace")),
        "POST /nodes/{node}/vzdump", planned = true),
    FeatureRequirement("usb", "USB devices on a node", GROUP_PLANNED, listOf(need(PermissionScope.Root, "Sys.Modify")),
        "GET /nodes/{node}/hardware/usb",
        "Proxmox checks Sys.Modify on /, not on the node.", planned = true),
    FeatureRequirement("pci", "PCI devices on a node", GROUP_PLANNED,
        listOf(need(PermissionScope.Root, "Sys.Audit", "Sys.Modify", any = true)),
        "GET /nodes/{node}/hardware/pci", planned = true),
    FeatureRequirement("notifications", "Notification settings", GROUP_PLANNED, listOf(need(PermissionScope.Notifications, "Mapping.Audit")),
        "GET /cluster/notifications/endpoints/*, .../matchers, .../targets",
        "Mapping.Modify alone lists only some endpoint types.", planned = true),
)

/** The nodes, guests and storages the checks run against. Built from the recorded inventory, not from the server's tree. */
/**
 * What one recorded node row says about Sys.Audit. [showsUsage] is true when CPU, memory or uptime is present.
 * An online node with none of those has no Sys.Audit. A present zero still counts as present.
 */
data class NodeAuditSignal(val online: Boolean, val showsUsage: Boolean)

data class PermissionTargets(
    val nodes: List<String> = emptyList(),
    val guests: List<Pair<Int, String>> = emptyList(),
    val storages: List<String> = emptyList(),
    val nodeAudit: Map<String, NodeAuditSignal> = emptyMap(),
)

fun permissionTargets(overview: Overview?): PermissionTargets {
    if (overview == null) return PermissionTargets()
    return PermissionTargets(
        nodes = overview.members.mapNotNull { pveNodeNameOrNull(it.name) }.distinct().sorted(),
        guests = overview.guests.filter { it.scope.isEmpty() && it.id >= 100 }.map { it.id to it.kind }.distinct().sortedBy { it.first },
        storages = overview.storage.mapNotNull { pveStorageIdOrNull(it.id) }.distinct().sorted(),
        nodeAudit = overview.members.mapNotNull { member ->
            val name = pveNodeNameOrNull(member.name) ?: return@mapNotNull null
            name to NodeAuditSignal(
                online = member.status == "online",
                showsUsage = member.cpuFraction != null || member.memoryBytes != null || member.uptimeSeconds != null,
            )
        }.toMap(),
    )
}

enum class FeatureStatus { Allowed, Partial, Missing, Unknown }

/** One table row. [needs] holds one "privilege @ path" line per check. [summary] is the one-line reason. */
data class FeatureCheck(
    val requirement: FeatureRequirement,
    val status: FeatureStatus,
    val needs: List<String>,
    val summary: String,
)

internal const val PATH_CHECK_LIMIT = 2_000

private fun allNeedPaths(need: PrivilegeNeed, targets: PermissionTargets): List<String> = when (need.scope) {
    PermissionScope.Root -> listOf("/")
    PermissionScope.Sdn -> listOf("/sdn")
    PermissionScope.Notifications -> listOf(need.scope.template)
    PermissionScope.Node -> targets.nodes.map { "/nodes/$it" }
    PermissionScope.Guest -> targets.guests.filter { need.guestKind == null || it.second == need.guestKind }.map { "/vms/${it.first}" }
    PermissionScope.Storage -> targets.storages.map { "/storage/$it" }
}.ifEmpty { listOf(need.scope.collection) }

internal fun needPaths(need: PrivilegeNeed, targets: PermissionTargets): List<String> =
    allNeedPaths(need, targets).take(PATH_CHECK_LIMIT)

private data class PathVerdict(val path: String, val held: Boolean, val legacyOnly: Boolean, val reason: String)

private class NeedOutcome(
    val need: PrivilegeNeed,
    val paths: List<String>,
    val held: Int,
    val failures: List<PathVerdict>,
    val total: Int,
) {
    val checked: Int get() = paths.size
    val truncated: Boolean get() = total > checked
    val status: FeatureStatus get() = when {
        truncated && held == checked -> FeatureStatus.Unknown
        held == checked -> FeatureStatus.Allowed
        failures.all { it.legacyOnly } -> FeatureStatus.Unknown
        held == 0 -> FeatureStatus.Missing
        else -> FeatureStatus.Partial
    }
}

private fun privilegeText(names: List<String>, any: Boolean) =
    if (names.size == 1) names[0] else names.joinToString(if (any) " or " else " and ")

private fun whyNotHeld(effective: EffectivePrivileges, missing: List<String>, any: Boolean): String {
    val wanted = privilegeText(missing, any)
    val closer = missing.filter { it in effective.blocked }
    if (closer.isNotEmpty() && effective.blockedFrom != null) {
        return "${privilegeText(closer, any)} is set on ${effective.blockedFrom} without Propagate, so ${effective.path} does not inherit it."
    }
    val source = effective.source
    return when {
        source == null -> "No role applies on ${effective.path} or its parents, so $wanted is not granted."
        effective.exact -> "The roles on ${effective.path} do not include $wanted."
        missing.any { it in effective.notInherited } -> {
            val unspread = missing.filter { it in effective.notInherited }
            "${privilegeText(unspread, any)} is set on $source without Propagate, so ${effective.path} does not inherit it."
        }
        else -> "${effective.path} inherits from $source, whose roles do not include $wanted."
    }
}

/**
 * Online node rows are a second answer. No CPU, memory or uptime means no Sys.Audit, so a missing node path must not
 * inherit Allowed from a parent. Usage on an online node means Sys.Audit is held; if the permission list disagrees, the
 * row is Unknown.
 */
private fun nodeInventoryVerdict(
    need: PrivilegeNeed,
    path: String,
    effective: EffectivePrivileges,
    targets: PermissionTargets,
): PathVerdict? {
    if (need.scope != PermissionScope.Node || need.open) return null
    if (!path.startsWith("/nodes/")) return null
    val name = path.removePrefix("/nodes/")
    if (name.isEmpty() || '/' in name) return null
    val signal = targets.nodeAudit[name] ?: return null
    if (!signal.online) return null
    val auditCheck = need.privileges == listOf("Sys.Audit")
    if (!signal.showsUsage) {
        if (!effective.exact) {
            return PathVerdict(path, false, false,
                "$path is not in the permission list. $name is online with no CPU, memory or uptime, so a role on a parent does not apply.")
        }
        if (auditCheck && "Sys.Audit" in effective.privileges) {
            return PathVerdict(path, false, true,
                "$name is online with no CPU, memory or uptime, so it has no Sys.Audit, but the permission list grants it. The two answers disagree.")
        }
        return null
    }
    if (auditCheck && "Sys.Audit" !in effective.privileges) {
        return PathVerdict(path, false, true,
            "$name is online and the inventory includes CPU, memory or uptime, which needs Sys.Audit, but the permission list does not grant it. The two answers disagree.")
    }
    return null
}

private fun verdict(dump: PermissionDump, need: PrivilegeNeed, path: String, targets: PermissionTargets): PathVerdict {
    if (need.open) return PathVerdict(path, true, false, "")
    val effective = effectivePrivileges(dump, path)
    nodeInventoryVerdict(need, path, effective, targets)?.let { return it }
    val have = effective.privileges
    val satisfied = if (need.any) need.privileges.any { it in have } else need.privileges.all { it in have }
    if (satisfied) return PathVerdict(path, true, false, "")
    val missing = if (need.any) need.privileges else need.privileges.filter { it !in have }
    val legacy = need.legacy.filter { it in have }
    if (legacy.isNotEmpty()) {
        return when (releaseBeforeNine(dump.release)) {
            true -> PathVerdict(path, true, false, "")
            false -> PathVerdict(path, false, false,
                "$path has ${legacy.joinToString()}, which Proxmox VE 9 does not accept. It needs ${privilegeText(need.privileges, need.any)}.")
            null -> PathVerdict(path, false, true,
                "$path has ${legacy.joinToString()}, which Proxmox VE 8 and earlier accept. Version 9 needs ${privilegeText(need.privileges, need.any)}.")
        }
    }
    return PathVerdict(path, false, false, whyNotHeld(effective, missing, need.any))
}

private fun evaluateNeed(dump: PermissionDump, need: PrivilegeNeed, targets: PermissionTargets): NeedOutcome {
    val all = allNeedPaths(need, targets)
    val paths = all.take(PATH_CHECK_LIMIT)
    val verdicts = paths.map { verdict(dump, need, it, targets) }
    return NeedOutcome(need, paths, verdicts.count { it.held }, verdicts.filterNot { it.held }, all.size)
}

private fun noun(scope: PermissionScope, many: Boolean) = when (scope) {
    PermissionScope.Guest -> if (many) "guests" else "guest"
    PermissionScope.Node -> if (many) "nodes" else "node"
    PermissionScope.Storage -> if (many) "storages" else "storage"
    else -> "path"
}

private fun seenPhrase(scope: PermissionScope): String = if (scope == PermissionScope.Guest) " this identity can see" else ""

private fun describe(outcome: NeedOutcome): String {
    val first = outcome.failures.firstOrNull()?.reason.orEmpty()
    val many = outcome.checked > 1
    val see = seenPhrase(outcome.need.scope)
    val word = noun(outcome.need.scope, true)
    val cap = if (outcome.truncated) " ${outcome.total - outcome.checked} more were not checked." else ""
    return when (outcome.status) {
        FeatureStatus.Allowed ->
            if (many) "Allowed on all ${outcome.checked} $word$see." else "Allowed on ${outcome.paths.single()}."
        FeatureStatus.Missing -> (if (many) "Missing on all ${outcome.checked} $word$see. $first" else first) + cap
        FeatureStatus.Partial -> "Missing on ${outcome.failures.size} of ${outcome.checked} $word$see. $first$cap"
        FeatureStatus.Unknown -> when {
            outcome.truncated && outcome.failures.isEmpty() ->
                "Allowed on ${outcome.checked} of ${outcome.total} $word$see.$cap"
            outcome.truncated -> (if (many) "Checked ${outcome.checked} of ${outcome.total} $word$see. $first" else first) + cap
            else -> first
        }
    }
}

private val severity = mapOf(FeatureStatus.Missing to 3, FeatureStatus.Partial to 2, FeatureStatus.Unknown to 1, FeatureStatus.Allowed to 0)

/**
 * Evaluate every requirement against one dump. A null [dump] (not read yet) and a non-direct connection give
 * [FeatureStatus.Unknown] rows that say why.
 */
fun evaluateFeatures(
    dump: PermissionDump?,
    targets: PermissionTargets,
    direct: Boolean = true,
    requirements: List<FeatureRequirement> = FEATURE_REQUIREMENTS,
): List<FeatureCheck> = requirements.map { requirement ->
    val labels = requirement.needs.map { it.label() }
    if (!direct) return@map FeatureCheck(requirement, FeatureStatus.Unknown, labels, PERMISSIONS_DIRECT_ONLY)
    if (dump == null) return@map FeatureCheck(requirement, FeatureStatus.Unknown, labels, PERMISSIONS_UNREAD)
    val outcomes = requirement.needs.map { evaluateNeed(dump, it, targets) }
    val worst = outcomes.maxBy { severity.getValue(it.status) }
    val status = outcomes.map { it.status }.maxBy { severity.getValue(it) }
    if (status != FeatureStatus.Allowed) return@map FeatureCheck(requirement, status, labels, describe(worst))
    val hidden = requirement.alsoNeeds.isNotEmpty() && requirement.alsoNeeds.none { alternative ->
        alternative.all { evaluateNeed(dump, it, targets).status == FeatureStatus.Allowed }
    }
    val requiredMissing = requirement.alsoRequires.any { evaluateNeed(dump, it, targets).status != FeatureStatus.Allowed }
    if (hidden || requiredMissing) return@map FeatureCheck(requirement, FeatureStatus.Partial, labels, requirement.alsoNote.orEmpty())
    FeatureCheck(requirement, FeatureStatus.Allowed, labels, describe(worst))
}

private val releasePattern = Regex("""^(\d+)\.(\d+)""")

/** `x.y` from a `GET /version` body. The repo id is dropped. Null when the body has no release. */
fun parsePveRelease(body: String): String? {
    val root = try { Json.parseToJsonElement(body) as? JsonObject } catch (_: Exception) { null } ?: return null
    val data = root["data"] as? JsonObject ?: return null
    val raw = (data["release"] as? JsonPrimitive)?.contentOrNull
        ?: (data["version"] as? JsonPrimitive)?.contentOrNull
        ?: return null
    val match = releasePattern.find(raw.trim()) ?: return null
    return "${match.groupValues[1]}.${match.groupValues[2]}"
}

/** True on Proxmox VE 8 and earlier, false on 9 and later, null when [release] is missing. */
internal fun releaseBeforeNine(release: String?): Boolean? {
    val major = release?.substringBefore('.')?.toIntOrNull() ?: return null
    return major < 9
}

/** Counts per status, for the table header. */
fun featureStatusCounts(checks: List<FeatureCheck>): Map<FeatureStatus, Int> =
    FeatureStatus.entries.associateWith { status -> checks.count { it.status == status } }

/** The PVEAuditor role as pve-access-control builds it (the `audit` group of every category), set with Propagate. */
private const val DEMO_AUDITOR =
    "\"Sys.Audit\":1,\"VM.Audit\":1,\"VM.GuestAgent.Audit\":1,\"Datastore.Audit\":1,\"Pool.Audit\":1,\"SDN.Audit\":1,\"Mapping.Audit\":1"

/**
 * Handwritten sample for demo mode, shaped like the real response: PVEAuditor on / (so the fixed top-level paths list the
 * auditor set), part of PVEVMUser added on /vms, and three explicit paths without Propagate. A path that is not listed
 * (such as /vms/120 or /mapping/notifications) is answered from its parent. The sample shows every state in the table.
 * It is not a captured response.
 */
val demoPermissions: PermissionDump = parsePermissions("""{"data":{
 "/":{$DEMO_AUDITOR},
 "/access":{$DEMO_AUDITOR},
 "/nodes":{$DEMO_AUDITOR},
 "/pool":{$DEMO_AUDITOR},
 "/sdn":{$DEMO_AUDITOR},
 "/storage":{$DEMO_AUDITOR},
 "/vms":{$DEMO_AUDITOR,"VM.Console":1,"VM.PowerMgmt":1,"VM.Backup":1,"VM.Config.CDROM":1,"VM.Config.Cloudinit":1},
 "/nodes/demo-node-a":{"Sys.Audit":0,"Sys.Console":0,"Sys.Syslog":0},
 "/storage/shared-data":{"Datastore.Audit":0,"Datastore.AllocateSpace":0},
 "/vms/130":{"VM.Audit":0,"VM.GuestAgent.Audit":0}
}}""")
