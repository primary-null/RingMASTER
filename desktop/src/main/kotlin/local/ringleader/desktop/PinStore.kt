package local.ringleader.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.ringleader.core.ClusterTask
import local.ringleader.core.Guest
import local.ringleader.core.PowerPhase
import local.ringleader.core.PowerSubmission
import local.ringleader.core.ResourceId
import local.ringleader.core.TaskOutcome
import local.ringleader.core.pdmScopeOrNull
import local.ringleader.core.profileFile

/** Opens Fleet on this guest. [nonce] changes on every click so the same pin can be used twice. */
data class FleetRequest(val resource: ResourceId, val tab: String, val nonce: Long)

internal const val PIN_GUEST = "guest"
internal const val PIN_VIEW = "view"
internal const val PIN_SERVICE = "service"
internal const val PIN_CONNECTION_DEMO = "demo"

private const val PIN_VERSION = 1
private val pinTargets = setOf(PIN_GUEST, PIN_VIEW, PIN_SERVICE)
internal val pinTabs = setOf("Summary", "Console", "Hardware", "Snapshots", "Backups", "Bluetooth", "Actions")
private val pinGuestKinds = setOf("VM", "Container")
private val pinJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

/**
 * One saved pin. Guest identity is kind + vmid + PDM scope. The node is not stored: guests migrate,
 * and [Guest.resource] already ignores it. A service URL never carries user-info.
 */
data class Pin(
    val id: String,
    val guestKind: String,
    val vmid: Int,
    val scope: String,
    val guestName: String,
    val target: String,
    val tab: String? = null,
    val serviceName: String? = null,
    val serviceUrl: String? = null,
)

internal enum class PinLiveness { Missing, Running, Stopped, Busy }

/** Live dot for a pin. Amber wins while a task or submission for that guest is still in progress. */
internal fun pinLiveness(guest: Guest?, tasks: List<ClusterTask>?, submissions: List<PowerSubmission>): PinLiveness {
    if (guest == null) return PinLiveness.Missing
    val busyTask = tasks.orEmpty().any { it.outcome == TaskOutcome.Running && it.remote == guest.remote && it.subjectId == guest.id.toString() }
    val busySubmit = submissions.any {
        it.remote == guest.remote && it.vmid == guest.id && it.kind == guest.kind &&
            (it.checking || it.phase == PowerPhase.Accepted || it.phase == PowerPhase.Unknown)
    }
    if (busyTask || busySubmit) return PinLiveness.Busy
    return if (guest.status == "running") PinLiveness.Running else PinLiveness.Stopped
}

internal fun Pin.matches(guest: Guest) = guestKind == guest.kind && vmid == guest.id && scope == guest.scope

internal fun Pin.railLabel(guest: Guest?): String {
    val who = guest?.name?.takeIf { it.isNotBlank() } ?: guestName
    return when (target) {
        PIN_VIEW -> "$who \u00b7 ${tab.orEmpty()}"
        PIN_SERVICE -> "$who \u00b7 ${serviceName.orEmpty()}"
        else -> who
    }
}

internal fun pinLivenessLabel(liveness: PinLiveness, guest: Guest?): String = when (liveness) {
    PinLiveness.Missing -> "Not in this connection"
    PinLiveness.Busy -> "In progress"
    PinLiveness.Running -> "Running"
    PinLiveness.Stopped -> guest?.status?.replaceFirstChar { it.uppercase() }?.ifBlank { null } ?: "Stopped"
}

/**
 * How many pin rows fit in [room] pixels. A list longer than [cap], or longer than the room, keeps a
 * "+N more" row. Returns the number of pin rows, not counting that more-row.
 */
internal fun visiblePinCount(total: Int, room: Int, row: Int, more: Int, cap: Int = 8): Int {
    if (total <= 0 || row <= 0 || room <= 0) return 0
    var show = minOf(total, cap)
    while (show > 0) {
        val extra = if (show < total) more else 0
        if (show * row + extra <= room) return show
        show--
    }
    return 0
}

/** Profile id while a saved profile is connected, otherwise the origin. Demo never shares those keys. */
internal fun pinConnectionKey(demo: Boolean, profileId: String?, origin: String): String? = when {
    demo -> PIN_CONNECTION_DEMO
    !profileId.isNullOrBlank() -> profileId
    origin.isNotBlank() -> origin.trim()
    else -> null
}

/** Null when [name] can be stored. 1–32 printable characters. */
internal fun serviceNameError(name: String): String? {
    val trimmed = name.trim()
    if (trimmed.length !in 1..32 || trimmed.any { it.isISOControl() || it == '\u007f' }) return "Use 1 to 32 printable characters."
    return null
}

/**
 * Null when [url] is an http(s) address with a host, no user-info, and at most 200 characters.
 * Anything else is refused so a secret in the address is never written.
 */
internal fun serviceAddressError(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.length > 200) return "Use at most 200 characters."
    if (trimmed.any { it.isISOControl() || it.isWhitespace() }) return "Use an http or https address with a host."
    val uri = try {
        URI(trimmed)
    } catch (_: Exception) {
        return "Use an http or https address with a host."
    }
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return "Use an http or https address with a host."
    if (!trimmed.startsWith("$scheme://")) return "Use an http or https address with a host."
    if (uri.rawUserInfo != null || uri.userInfo != null) return "An address cannot include a username or password."
    if (uri.host.isNullOrBlank()) return "Use an http or https address with a host."
    return null
}

/** Host portion of a reported guest address ("192.0.2.8", "192.0.2.8/24", "100.64.0.7 (Tailscale)"). */
internal fun reportedHost(address: String): String? {
    val bare = address.substringBefore(" (").substringBefore("/").trim()
    if (bare.isEmpty() || bare.any { it.isISOControl() || it.isWhitespace() }) return null
    return bare
}

internal fun serviceUrlForHost(host: String): String {
    val literal = if (':' in host && !host.startsWith("[")) "[$host]" else host
    return "http://$literal"
}

internal fun pinsFile(): Path = profileFile().resolveSibling("pins.json")

/**
 * Pins beside the profile file (`pins.json`). A missing or corrupt file leaves an empty list so the
 * app still opens. Writes are atomic (temp file, then ATOMIC_MOVE). A failed write is reported and
 * the in-memory list is kept until RingMASTER closes.
 *
 * Reorder is Move up / Move down on the row menu. Dragging inside the narrow rail was skipped.
 */
class PinStore(private val file: Path = pinsFile()) {
    var byConnection by mutableStateOf(load())
        private set

    fun list(connectionKey: String): List<Pin> = byConnection[connectionKey].orEmpty()

    fun hasGuest(connectionKey: String, guest: Guest) = list(connectionKey).any { it.target == PIN_GUEST && it.matches(guest) }

    fun hasView(connectionKey: String, guest: Guest, tab: String) =
        list(connectionKey).any { it.target == PIN_VIEW && it.tab == tab && it.matches(guest) }

    fun toggleGuest(connectionKey: String, guest: Guest) {
        if (!keyOk(connectionKey) || !guestOk(guest)) return
        val current = list(connectionKey)
        val next = if (current.any { it.target == PIN_GUEST && it.matches(guest) }) current.filterNot { it.target == PIN_GUEST && it.matches(guest) }
        else current + pin(guest, PIN_GUEST)
        replace(connectionKey, next)
    }

    fun toggleView(connectionKey: String, guest: Guest, tab: String) {
        if (!keyOk(connectionKey) || !guestOk(guest) || !tabOk(guest, tab)) return
        val current = list(connectionKey)
        val next = if (current.any { it.target == PIN_VIEW && it.tab == tab && it.matches(guest) }) {
            current.filterNot { it.target == PIN_VIEW && it.tab == tab && it.matches(guest) }
        } else current + pin(guest, PIN_VIEW, tab = tab)
        replace(connectionKey, next)
    }

    /** Null when the service was stored. A message when the name or address was refused. */
    fun addService(connectionKey: String, guest: Guest, name: String, url: String): String? {
        if (!keyOk(connectionKey) || !guestOk(guest)) return "This guest cannot be pinned."
        serviceNameError(name)?.let { return it }
        serviceAddressError(url)?.let { return it }
        val cleanName = name.trim()
        val cleanUrl = url.trim()
        val current = list(connectionKey)
        if (current.any { it.target == PIN_SERVICE && it.serviceUrl == cleanUrl && it.matches(guest) }) return "That address is already pinned for this guest."
        replace(connectionKey, current + pin(guest, PIN_SERVICE, serviceName = cleanName, serviceUrl = cleanUrl))
        return null
    }

    fun unpin(connectionKey: String, id: String) {
        if (!keyOk(connectionKey)) return
        replace(connectionKey, list(connectionKey).filterNot { it.id == id })
    }

    fun move(connectionKey: String, id: String, delta: Int) {
        if (!keyOk(connectionKey) || delta == 0) return
        val items = list(connectionKey).toMutableList()
        val from = items.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in items.indices) return
        val pin = items.removeAt(from)
        items.add(to, pin)
        replace(connectionKey, items)
    }

    /**
     * Moves the pins a manual connection kept under [origin] onto the saved profile [profileId] for that server, so
     * they stay on the rail once the profile is used. A pin the profile already has wins over the same pin from the
     * origin. The origin bucket is removed and the file is written once. Nothing is written when there is nothing to move.
     */
    fun adoptOriginPins(origin: String, profileId: String) {
        val from = origin.trim()
        if (from.isBlank() || from == profileId || from == PIN_CONNECTION_DEMO || profileId == PIN_CONNECTION_DEMO) return
        if (!keyOk(from) || !keyOk(profileId)) return
        val moving = list(from)
        if (moving.isEmpty()) return
        val merged = (list(profileId) + moving).distinctBy { listOf(it.guestKind, it.vmid, it.scope, it.target, it.tab, it.serviceUrl) }
        val next = byConnection.toMutableMap()
        next.remove(from)
        next[profileId] = merged
        byConnection = next
        persist()
    }

    private fun replace(connectionKey: String, pins: List<Pin>) {
        val next = byConnection.toMutableMap()
        if (pins.isEmpty()) next.remove(connectionKey) else next[connectionKey] = pins
        byConnection = next
        persist()
    }

    private fun persist() {
        if (!ConfigSchemas.writable(file)) return
        runCatching {
            val parent = file.parent ?: error("The pins file has no folder.")
            Files.createDirectories(parent)
            val temp = Files.createTempFile(parent, "pins-", ".tmp")
            try {
                Files.writeString(temp, encode(byConnection))
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temp)
            }
        }.onFailure { SaveStatus.report("Pins", it) }
    }

    private fun load(): Map<String, List<Pin>> = runCatching {
        if (!Files.isRegularFile(file)) return emptyMap()
        val text = Files.readString(file)
        val schema = pinJson.parseToJsonElement(text).jsonObject["version"]?.jsonPrimitive?.intOrNull ?: 1
        if (ConfigSchemas.newer(file, schema, PIN_VERSION)) return emptyMap()
        decode(text)
    }.getOrDefault(emptyMap())

    private fun pin(guest: Guest, target: String, tab: String? = null, serviceName: String? = null, serviceUrl: String? = null) = Pin(
        id = UUID.randomUUID().toString(),
        guestKind = guest.kind,
        vmid = guest.id,
        scope = guest.scope,
        guestName = guestLabel(guest),
        target = target,
        tab = tab,
        serviceName = serviceName,
        serviceUrl = serviceUrl,
    )
}

private fun keyOk(key: String) = key.length in 1..200 && key.none { it.isISOControl() }

private fun guestOk(guest: Guest) = guest.kind in pinGuestKinds && guest.id > 0 && (guest.scope.isEmpty() || pdmScopeOrNull(guest.scope) != null)

private fun tabOk(guest: Guest, tab: String) = tab in pinTabs && (tab != "Bluetooth" || guest.kind == "VM")

private fun guestLabel(guest: Guest): String {
    val raw = guest.name.trim().filterNot { it.isISOControl() }.take(80)
    return raw.ifBlank { "Guest ${guest.id}" }
}

private fun encode(connections: Map<String, List<Pin>>): String {
    val root = buildJsonObject {
        put("version", PIN_VERSION)
        put("connections", buildJsonObject {
            connections.forEach { (key, pins) ->
                put(key, buildJsonArray {
                    pins.forEach { pin ->
                        add(buildJsonObject {
                            put("id", pin.id)
                            put("guestKind", pin.guestKind)
                            put("vmid", pin.vmid)
                            put("scope", pin.scope)
                            put("guestName", pin.guestName)
                            put("target", pin.target)
                            pin.tab?.let { put("tab", it) }
                            pin.serviceName?.let { put("serviceName", it) }
                            pin.serviceUrl?.let { put("serviceUrl", it) }
                        })
                    }
                })
            }
        })
    }
    return pinJson.encodeToString(JsonElement.serializer(), root)
}

private fun decode(text: String): Map<String, List<Pin>> {
    val root = pinJson.parseToJsonElement(text).jsonObject
    if (root["version"]?.jsonPrimitive?.intOrNull != PIN_VERSION) return emptyMap()
    val connections = root["connections"] as? JsonObject ?: return emptyMap()
    val parsed = linkedMapOf<String, List<Pin>>()
    connections.forEach { (key, value) ->
        if (!keyOk(key)) return@forEach
        val pins = runCatching { value.jsonArray }.getOrNull()?.mapNotNull { element ->
            (element as? JsonObject)?.let(::parsePin)
        }.orEmpty()
        if (pins.isNotEmpty()) parsed[key] = pins
    }
    return parsed
}

private fun parsePin(row: JsonObject): Pin? {
    val id = row.text("id") ?: return null
    if (runCatching { UUID.fromString(id) }.isFailure) return null
    val guestKind = row.text("guestKind") ?: return null
    if (guestKind !in pinGuestKinds) return null
    val vmid = row["vmid"]?.jsonPrimitive?.intOrNull ?: return null
    if (vmid <= 0) return null
    val scope = row["scope"]?.jsonPrimitive?.contentOrNull ?: ""
    if (scope.isNotEmpty() && pdmScopeOrNull(scope) == null) return null
    val guestName = row.text("guestName") ?: return null
    if (guestName.length !in 1..80 || guestName.any { it.isISOControl() }) return null
    val target = row.text("target") ?: return null
    if (target !in pinTargets) return null
    val tab = row.text("tab")
    val serviceName = row.text("serviceName")
    val serviceUrl = row.text("serviceUrl")
    when (target) {
        PIN_GUEST -> if (tab != null || serviceName != null || serviceUrl != null) return null
        PIN_VIEW -> {
            if (tab == null || tab !in pinTabs || serviceName != null || serviceUrl != null) return null
            if (tab == "Bluetooth" && guestKind != "VM") return null
        }
        PIN_SERVICE -> {
            if (tab != null || serviceName == null || serviceUrl == null) return null
            if (serviceNameError(serviceName) != null || serviceAddressError(serviceUrl) != null) return null
        }
    }
    return Pin(id, guestKind, vmid, scope, guestName, target, tab, serviceName, serviceUrl)
}

private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
