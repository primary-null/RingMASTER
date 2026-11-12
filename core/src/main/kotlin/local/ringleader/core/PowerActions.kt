package local.ringleader.core

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One guest power action. Shutdown is the graceful ACPI/container shutdown.
 * Hard stop is the immediate stop. This client never turns a shutdown into a hard stop.
 */
enum class PowerAction { Start, Shutdown, HardStop, Reboot, Suspend, Resume, Reset, SnapshotCreate, SnapshotRollback, SnapshotDelete }

/** What the server evidence supports. Accepted is not success. */
enum class PowerPhase { Accepted, Succeeded, Unsuccessful, Unknown, Rejected, Simulated }

/**
 * The server did not return a complete result after the request body was written.
 * The action is not sent again.
 */
class AmbiguousPowerResultException :
    java.io.IOException("The server did not confirm whether this action was accepted. It was not sent again.")

/** HTTP completed, but the body did not prove acceptance or completion. The action is not sent again. */
class PowerResponseUnclearException(message: String) : IllegalStateException(message)

const val POWER_CONTENT_TYPE = "application/json"
const val HARD_STOP_CONSEQUENCE =
    "Hard stop ends this guest immediately. It is like cutting the power and can damage guest data."
const val SHUTDOWN_GUARANTEE =
    "Requests graceful shutdown. RingMASTER does not retry it as a hard stop. For HA-managed guests, server HA policy may force a stop after its timeout."
const val SIMULATED_POWER_DETAIL = "Simulated on this computer. No request was sent to a server."
const val RESET_CONSEQUENCE =
    "Reset restarts this guest at once, without a shutdown. Unsaved work in the guest is lost and its data can be damaged."
const val SNAPSHOT_DELETE_CONSEQUENCE = "The snapshot is removed for good. The guest's current state does not change."
const val HA_ROUTING_NOTE =
    "This guest is managed by HA. Start and stop requests go through the HA manager, which decides when and how they run."

/** Rollback text. [ram] is whether the snapshot is known to include RAM; null when the snapshot list was not read. */
fun rollbackConsequence(snapshot: String, ram: Boolean?): String {
    val head = "Rolling back discards every change made since snapshot $snapshot. A running guest is stopped at once, like a hard stop."
    return head + " " + when (ram) {
        true -> "This snapshot includes RAM, so the guest starts again from that saved moment."
        false -> "This snapshot has no RAM, so the guest stays stopped afterwards."
        null -> "The guest stays stopped afterwards unless the snapshot includes RAM."
    }
}

private val routeNode = Regex("[A-Za-z0-9][A-Za-z0-9.-]*")
private val unreserved = BooleanArray(128).also { table ->
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~".forEach { table[it.code] = true }
}

/**
 * Immutable target captured when the user asks to confirm.
 * [node] is the route. [resource] is the guest identity. [epoch] is the connection session.
 */
data class PowerConfirmation(
    val epoch: Long,
    val remote: String,
    val origin: String,
    val profileLabel: String,
    val resource: ResourceId,
    val node: String,
    val name: String,
    val kind: String,
    val action: PowerAction,
    val status: String,
    val template: Boolean,
    val confirmationId: String = UUID.randomUUID().toString(),
    val snapshot: SnapshotRequest? = null,
) {
    init {
        require(profileLabel.isNotBlank() && profileLabel.none { it.isISOControl() }) { "The confirmation is missing its connection." }
        require(origin.isNotBlank() && origin.none { it.isISOControl() }) { "The confirmation is missing its server." }
        require(node.matches(routeNode) && !node.contains("..")) { "The confirmation route is not usable." }
        require(resource.vmid > 0 && resource.kind == kind && resource.remote == remote) { "The confirmation guest is not usable." }
    }
}

/** In-memory outcome. The server UPID is not part of this value and is not written to a profile. */
data class PowerSubmission(
    val localId: String,
    val remote: String,
    val node: String,
    val vmid: Int,
    val kind: String,
    val name: String,
    val action: PowerAction,
    val phase: PowerPhase,
    val detail: String,
    val simulated: Boolean,
    val checkable: Boolean,
    val checking: Boolean = false,
) {
    init {
        require(localId.startsWith("local-") && !localId.contains("UPID:")) { "The submission identity is not usable." }
        require(detail.none { it.isISOControl() }) { "The submission detail is not usable." }
        require(!simulated || (!checkable && phase == PowerPhase.Simulated)) { "A simulated action cannot look like a server task." }
        require(phase != PowerPhase.Succeeded || detail == "Stopped with exit status OK.") { "Success requires a stopped task whose exit status is OK." }
    }
}

data class PowerTaskStatus(val phase: PowerPhase, val detail: String)

fun liveActionsAvailable(state: WorkspaceState): Boolean =
    state.connected && !state.demo && state.kind == DIRECT_PVE_KIND && state.overview != null && !state.stale && !state.loading

fun actionMatches(guest: Guest, action: PowerAction): Boolean {
    if (guest.template) return false
    return when (action) {
        PowerAction.Start -> guest.status == "stopped"
        PowerAction.Shutdown, PowerAction.HardStop, PowerAction.Reboot -> guest.status == "running"
        PowerAction.Suspend, PowerAction.Reset -> guest.kind == "VM" && guest.status == "running"
        PowerAction.Resume -> guest.kind == "VM" && guest.status in listOf("paused", "suspended")
        PowerAction.SnapshotCreate, PowerAction.SnapshotRollback, PowerAction.SnapshotDelete -> guest.status in listOf("running", "stopped")
    }
}

fun powerActionTitle(action: PowerAction): String = when (action) {
    PowerAction.Start -> "Start"
    PowerAction.Shutdown -> "Shut down"
    PowerAction.HardStop -> "Hard stop"
    PowerAction.Reboot -> "Reboot"
    PowerAction.Suspend -> "Suspend"
    PowerAction.Resume -> "Resume"
    PowerAction.Reset -> "Reset"
    PowerAction.SnapshotCreate -> "Create snapshot"
    PowerAction.SnapshotRollback -> "Rollback snapshot"
    PowerAction.SnapshotDelete -> "Delete snapshot"
}

/** The confirmation's commit button names the action itself, never a generic Send. */
fun powerCommitLabel(action: PowerAction): String = when (action) {
    PowerAction.SnapshotRollback -> "Roll back"
    else -> powerActionTitle(action)
}

/** Actions that end a running guest at once or discard data. Their commit button is not the suggested one. */
fun powerActionDestructive(action: PowerAction): Boolean =
    action == PowerAction.HardStop || action == PowerAction.Reset || action == PowerAction.SnapshotRollback || action == PowerAction.SnapshotDelete

/** Start and stop of an HA-managed guest are run by the HA manager. */
fun powerActionUsesHa(guest: Guest?, action: PowerAction): Boolean =
    guest?.haState != null && action in listOf(PowerAction.Start, PowerAction.Shutdown, PowerAction.HardStop)

/**
 * Why Proxmox would refuse this action on a locked guest, as plain text such as "Locked: backup.", or null.
 * A hibernated VM carries the `suspended` lock and is resumed with Start, so that one combination is allowed.
 */
fun guestLockRefusal(guest: Guest, action: PowerAction): String? {
    val lock = guest.lock?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (lock == "suspended" && action == PowerAction.Start) return null
    return "Locked: $lock."
}

/** A snapshot whose `snapstate` is set (for example `prepare` or `delete`) is not complete and cannot be rolled back to. */
fun snapshotRollbackRefusal(snapshot: GuestSnapshot?): String? {
    val state = snapshot?.snapstate?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return "Snapshot ${snapshot.name} is not complete ($state)."
}

/** The snapshot this confirmation names, from the open guest inspection. Null when that list is not loaded. */
fun confirmationSnapshot(confirmation: PowerConfirmation, state: WorkspaceState): GuestSnapshot? {
    val name = confirmation.snapshot?.name ?: return null
    val inspection = state.inspection?.takeIf { it.resource == confirmation.resource } ?: return null
    return inspection.snapshots.value?.firstOrNull { it.name == name && !it.current }
}

/** The submission that blocks other actions in this session: accepted and not finished, or with an unknown result. */
fun unresolvedSubmission(state: WorkspaceState): PowerSubmission? =
    state.submissions.firstOrNull { it.phase == PowerPhase.Accepted || it.phase == PowerPhase.Unknown }

/** Names the guest and the action that is still open, and what the user can do. */
fun unresolvedActionText(submission: PowerSubmission): String {
    val what = "${powerActionTitle(submission.action)} on ${submission.name.ifBlank { "guest" }} (${submission.vmid})"
    return if (submission.phase == PowerPhase.Accepted) {
        "$what is still running. RingMASTER follows it and allows another action when it ends."
    } else {
        "The result of $what is unknown. Check the guest on the server, then reconnect before another action."
    }
}

/** Requests no direct force-stop fallback; server HA policy can override shutdown behavior. */
fun powerRequestBody(action: PowerAction): ByteArray = when (action) {
    PowerAction.Shutdown -> """{"forceStop":false}"""
    else -> "{}"
}.toByteArray(Charsets.UTF_8)

fun powerActionPath(node: String, kind: String, vmid: Int, action: PowerAction): String {
    require(vmid > 0 && node.matches(routeNode) && !node.contains("..")) { "The guest route is not usable." }
    val api = when (kind) {
        "VM" -> "qemu"
        "Container" -> "lxc"
        else -> error("Unknown guest type.")
    }
    val leaf = when (action) {
        PowerAction.Start -> "start"
        PowerAction.Shutdown -> "shutdown"
        PowerAction.HardStop -> "stop"
        PowerAction.Reboot -> "reboot"
        PowerAction.Suspend -> "suspend"
        PowerAction.Resume -> "resume"
        PowerAction.Reset -> "reset"
        else -> error("Snapshot actions require a snapshot request.")
    }
    return "/api2/json/nodes/$node/$api/$vmid/status/$leaf"
}

fun encodePathSegment(value: String): String = buildString(value.length * 3) {
    value.toByteArray(Charsets.UTF_8).forEach { byte ->
        val code = byte.toInt() and 0xFF
        if (code < unreserved.size && unreserved[code]) append(code.toChar()) else append("%" + "%02X".format(code))
    }
}

/** Status route for the same origin, bound to the node that accepted the task and the encoded UPID. */
fun taskStatusPath(node: String, upid: String): String {
    require(node.matches(routeNode) && !node.contains("..")) { "The task route is not usable." }
    val parsed = parseServerUpid(upid) ?: error("The task identity is not usable.")
    require(parsed.node == node) { "The task belongs to a different node." }
    return "/api2/json/nodes/${encodePathSegment(node)}/tasks/${encodePathSegment(upid)}/status"
}

fun powerRefusal(confirmation: PowerConfirmation, state: WorkspaceState): String? {
    if (!validSnapshotRequest(confirmation)) return notSent("The snapshot request is invalid.")
    if (confirmation.epoch != state.connectionEpoch) return notSent("The connection changed.")
    if (state.kind == PDM_KIND || confirmation.resource.scope.isNotEmpty()) return notSent("Power actions are not available for a PDM connection.")
    if (state.demo) {
        if (confirmation.remote != DEMO_REMOTE_KEY || confirmation.origin != "Demonstration") return notSent("The connection changed.")
    } else if (!state.connected || confirmation.remote != state.address || confirmation.origin != state.address) {
        return notSent("The connection changed.")
    }
    unresolvedSubmission(state)?.let { return notSent(unresolvedActionText(it)) }
    if (state.loading || state.overview == null) return notSent("Inventory is still loading.")
    if (state.stale) return notSent("Inventory is stale.")
    val guest = state.overview.guests.singleOrNull { it.resource == confirmation.resource }
        ?: return notSent("That guest is no longer in this inventory.")
    if (confirmation.template || guest.template) return notSent("Templates have no power action.")
    if (guest.node != confirmation.node) return notSent("This guest moved to another node.")
    if (guest.name != confirmation.name || guest.kind != confirmation.kind || guest.status != confirmation.status) {
        return notSent("This guest changed in the inventory.")
    }
    if (!actionMatches(guest, confirmation.action)) return notSent("This action does not match the guest state.")
    guestLockRefusal(guest, confirmation.action)?.let { return notSent(it) }
    if (confirmation.action == PowerAction.SnapshotRollback) snapshotRollbackRefusal(confirmationSnapshot(confirmation, state))?.let { return notSent(it) }
    val selected = state.selected
    if (selected == null || selected.resource != confirmation.resource || selected.node != confirmation.node) {
        return notSent("The selection changed.")
    }
    return null
}

fun powerPhaseLabel(submission: PowerSubmission): String = when (submission.phase) {
    PowerPhase.Accepted -> "Accepted, not finished"
    PowerPhase.Succeeded -> "OK"
    PowerPhase.Unsuccessful -> submission.detail
    PowerPhase.Unknown -> "Unknown"
    PowerPhase.Rejected -> "Not accepted"
    PowerPhase.Simulated -> "Simulated"
}

/** Fields Activity may show. The UPID and token are not included. */
fun powerVisibleFields(submission: PowerSubmission): List<String> = listOf(
    powerActionTitle(submission.action),
    submission.node,
    submission.vmid.toString(),
    submission.kind,
    submission.name.ifBlank { "Guest ${submission.vmid}" },
    powerPhaseLabel(submission),
)

fun parseAcceptedUpid(body: String, node: String, vmid: Int): String {
    val primitive = jsonData(body) as? JsonPrimitive
    val value = primitive?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
        ?: throw PowerResponseUnclearException("The server response did not identify a task for this guest. It was not sent again.")
    val parsed = parseServerUpid(value)
        ?: throw PowerResponseUnclearException("The server response did not identify a task for this guest. It was not sent again.")
    if (parsed.node != node || parsed.subjectId != vmid.toString()) {
        throw PowerResponseUnclearException("The server response did not identify a task for this guest. It was not sent again.")
    }
    return value
}

fun parsePowerTaskStatus(body: String, node: String, upid: String): PowerTaskStatus {
    val row = jsonData(body) as? JsonObject
        ?: throw PowerResponseUnclearException("The task status response was incomplete. That is not success.")
    fun text(key: String): String? {
        val value = row[key] ?: return null
        if (value is JsonNull) return null
        val primitive = value as? JsonPrimitive ?: throw PowerResponseUnclearException("The task status response was incomplete. That is not success.")
        if (!primitive.isString) throw PowerResponseUnclearException("The task status response was incomplete. That is not success.")
        return cleanServerText(primitive.content)
    }
    val reportedNode = text("node")
    val reportedUpid = text("upid")
    val status = text("status")
    if (reportedNode != node || reportedUpid != upid || status == null) {
        throw PowerResponseUnclearException("The task status response did not match this task. That is not success.")
    }
    val exitStatus = if (row.containsKey("exitstatus")) text("exitstatus") else null
    return when (status) {
        "running" -> PowerTaskStatus(PowerPhase.Accepted, "The server accepted this action. It is still running.")
        "stopped" -> when (exitStatus) {
            "OK" -> PowerTaskStatus(PowerPhase.Succeeded, "Stopped with exit status OK.")
            null -> PowerTaskStatus(PowerPhase.Unknown, "The task stopped without an exit status. That is not success.")
            else -> PowerTaskStatus(PowerPhase.Unsuccessful, exitStatus)
        }
        else -> PowerTaskStatus(PowerPhase.Unknown, "The task status was not recognized. That is not success.")
    }
}

internal fun simulatedSubmission(confirmation: PowerConfirmation) = PowerSubmission(
    localId = newLocalId(),
    remote = confirmation.remote,
    node = confirmation.node,
    vmid = confirmation.resource.vmid,
    kind = confirmation.kind,
    name = confirmation.name,
    action = confirmation.action,
    phase = PowerPhase.Simulated,
    detail = SIMULATED_POWER_DETAIL,
    simulated = true,
    checkable = false,
)

internal fun newLocalId(): String = "local-${UUID.randomUUID()}"

internal fun confirmedGuest(confirmation: PowerConfirmation) = Guest(
    confirmation.remote, confirmation.resource.vmid, confirmation.name, confirmation.node,
    confirmation.kind, confirmation.status, confirmation.template, confirmation.resource.scope,
)

private fun notSent(reason: String) = "$reason Nothing was sent."

private fun cleanServerText(value: String): String? {
    val cleaned = value.replace(Regex("\\s+"), " ").trim()
    if (cleaned.isEmpty() || cleaned.length > 512 || cleaned.any { it.isISOControl() }) return null
    return cleaned
}

private fun jsonData(body: String): kotlinx.serialization.json.JsonElement {
    val root = try {
        Json.parseToJsonElement(body)
    } catch (_: Exception) {
        throw PowerResponseUnclearException("The server response could not be read. It was not sent again.")
    } as? JsonObject ?: throw PowerResponseUnclearException("The server response could not be read. It was not sent again.")
    if ("data" !in root || root["data"] is JsonNull) {
        throw PowerResponseUnclearException("The server response could not be read. It was not sent again.")
    }
    return root["data"] ?: throw PowerResponseUnclearException("The server response could not be read. It was not sent again.")
}

/** First wait before the automatic status read of an accepted task. Each later wait is one second longer, up to [POWER_FOLLOW_MAX_MS]. */
const val POWER_FOLLOW_FIRST_MS = 1_000L
const val POWER_FOLLOW_MAX_MS = 5_000L
/** After a task ends, wait this long before reading the resource list once; the list lags the task by a few seconds. */
const val POWER_CATCH_UP_DELAY_MS = 3_000L

/** Wait before the next automatic status read. [attempt] counts from 0. */
fun powerFollowDelay(attempt: Int): Long = (POWER_FOLLOW_FIRST_MS * (attempt.coerceAtLeast(0) + 1)).coerceAtMost(POWER_FOLLOW_MAX_MS)

/**
 * A sent action whose outcome was not read before its session ended: the server accepted it and it had not finished
 * ([accepted]), or the answer was lost after the request was sent. Memory only. It holds no UPID, token or secret.
 */
data class LostPowerResult(
    val id: String,
    val remote: String,
    val node: String,
    val vmid: Int,
    val kind: String,
    val name: String,
    val action: PowerAction,
    val accepted: Boolean,
    val at: java.time.Instant,
)

fun lostPowerResultText(item: LostPowerResult): String {
    val what = "${powerActionTitle(item.action)} on ${item.name.ifBlank { "guest" }} (${item.vmid}, node ${item.node})"
    return if (item.accepted) "$what was accepted by the server, but the session ended before it finished. It may still be running or may have finished."
    else "$what may have run. The session ended before the server's answer arrived. It was not sent again."
}

const val LOST_POWER_RESULT_ADVICE = "Check the guest before you send another action."

/**
 * Lost results for this app run, shared by every session window, keyed by server origin. Kept in memory only and
 * never written to disk. A dismissed item is gone; dismissing sends nothing to the server.
 */
class LostPowerResultStore {
    private val items = mutableListOf<LostPowerResult>()

    @Synchronized fun add(item: LostPowerResult) {
        items.removeAll { it.remote == item.remote && it.vmid == item.vmid && it.kind == item.kind && it.action == item.action }
        items.add(0, item)
        while (items.size > LOST_POWER_RESULT_LIMIT) items.removeAt(items.lastIndex)
    }

    @Synchronized fun forRemote(remote: String): List<LostPowerResult> = items.filter { it.remote == remote }

    @Synchronized fun dismiss(id: String) { items.removeAll { it.id == id } }

    companion object {
        const val LOST_POWER_RESULT_LIMIT = 20
        /** The store for this app run. */
        val shared = LostPowerResultStore()
    }
}

/** After this many automatic status reads fail in a row, say so once. The follow keeps trying. */
const val POWER_FOLLOW_NOTICE_FAILURES = 3
const val POWER_FOLLOW_DENIED = "This token cannot read the task status, so RingMASTER cannot follow it. Use Check status in Activity."
