package local.ringleader.core

import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val NOTIFICATION_STORE_VERSION = 1
/** Stored notifications, newest first. Older ones are dropped. */
const val MAX_NOTIFICATIONS = 200
/** Identities remembered after their notification left the history, so a dismissed or trimmed event cannot post again. */
const val MAX_NOTIFICATION_SEEN = 1000
const val MAX_NOTIFICATION_SNOOZES = 64
internal const val MAX_NOTIFICATION_BYTES = 1024 * 1024

/**
 * Everything the notification center persists: preferences, snoozes, the bounded history and the bounded list of
 * identities already posted. No secret, token, password, server address, task log or server error text is part of it.
 * [items] are newest first; [seen] is oldest first.
 */
data class NotificationDocument(
    val preferences: NotificationPreferences = NotificationPreferences(),
    val snoozeAllUntil: Instant? = null,
    val snoozes: Map<String, Instant> = emptyMap(),
    val items: List<AppNotification> = emptyList(),
    val seen: List<String> = emptyList(),
)

/** `notifications.json` beside `profiles.json`. See [GuardedFile] for the write and failure rules. */
class NotificationStore internal constructor(private val guarded: GuardedFile) {
    constructor(file: Path) : this(GuardedFile(file, MAX_NOTIFICATION_BYTES))
    /** For tests: [replace] stands in for the atomic move. */
    constructor(file: Path, replace: (Path, Path) -> Unit) : this(GuardedFile(file, MAX_NOTIFICATION_BYTES, replace))

    val file: Path get() = guarded.file
    fun problem(): GuardedStoreException? = guarded.problem()
    fun load(): NotificationDocument = guarded.load(NotificationDocument(), ::decodeNotifications)
    fun save(document: NotificationDocument) = guarded.save(encodeNotifications(document))
    fun setAside(now: Instant): Path = guarded.setAside(now)
}

internal fun encodeNotifications(document: NotificationDocument): String = buildJsonObject {
    put("version", NOTIFICATION_STORE_VERSION)
    put("preferences", buildJsonObject {
        val p = document.preferences
        put("master", p.master); put("native", p.native); put("privacy", p.privacy)
        put("categories", buildJsonObject { p.categories.toSortedMap().forEach { (category, on) -> put(category.name, on) } })
    })
    document.snoozeAllUntil?.let { put("snoozeAllUntil", it.toString()) }
    put("snoozes", buildJsonObject { document.snoozes.toSortedMap().forEach { (subject, until) -> put(subject, until.toString()) } })
    put("items", buildJsonArray {
        document.items.filter { !it.demo }.forEach { n ->
            add(buildJsonObject {
                put("id", n.id); put("subject", n.subjectId); put("category", n.category.name); put("severity", n.severity.name)
                put("title", n.title); put("body", n.body); put("createdAt", n.createdAt.toString()); put("read", n.read)
                encodeTarget(n.target)?.let { put("target", it) }
            })
        }
    })
    put("seen", buildJsonArray { document.seen.forEach { add(JsonPrimitive(it)) } })
}.toString() + "\n"

private fun encodeTarget(target: NotificationTarget): JsonObject? = when (target) {
    NotificationTarget.None -> null
    is NotificationTarget.Resource -> buildJsonObject {
        put("type", "resource"); target.profileId?.let { put("profileId", it) }; put("binding", target.binding)
        put("resource", encodeWatchResource(target.resource))
    }
    is NotificationTarget.Task -> buildJsonObject {
        put("type", "task"); target.profileId?.let { put("profileId", it) }; put("binding", target.binding); put("upid", target.upid)
    }
    is NotificationTarget.ScheduleRun -> buildJsonObject { put("type", "scheduleRun"); put("planId", target.planId); put("runId", target.runId) }
}

internal fun encodeWatchResource(resource: WatchResource): JsonObject = buildJsonObject {
    put("kind", resource.kind.name); put("remoteScope", resource.remoteScope)
    resource.node?.let { put("node", it) }
    resource.vmid?.let { put("vmid", it) }
}

internal fun decodeWatchResource(element: JsonElement?): WatchResource {
    val o = StrictJson.obj(element, setOf("kind", "remoteScope", "node", "vmid"), required = setOf("kind", "remoteScope"))
    return WatchResource(StrictJson.enum(o, "kind"), StrictJson.str(o, "remoteScope"), StrictJson.strOrNull(o, "node"), if ("vmid" in o) StrictJson.int(o, "vmid") else null)
}

internal fun decodeNotifications(text: String): NotificationDocument {
    val root = Json.parseToJsonElement(text) as? JsonObject ?: throw StrictJson.Bad()
    StrictJson.version(root, NOTIFICATION_STORE_VERSION)
    val all = setOf("version", "preferences", "snoozeAllUntil", "snoozes", "items", "seen")
    StrictJson.obj(root, all, required = all - "snoozeAllUntil")
    val p = StrictJson.obj(root["preferences"], setOf("master", "native", "privacy", "categories"))
    val categories = (p["categories"] as? JsonObject ?: throw StrictJson.Bad()).let { o ->
        o.keys.associate { key -> NotificationCategory.valueOf(key) to StrictJson.bool(o, key) }
    }
    val snoozes = (root["snoozes"] as? JsonObject ?: throw StrictJson.Bad()).let { o ->
        if (o.size > MAX_NOTIFICATION_SNOOZES) throw StrictJson.Bad()
        o.keys.associateWith { key -> if (!key.matches(notificationIdPattern)) throw StrictJson.Bad(); StrictJson.instant(o, key) }
    }
    val items = StrictJson.array(root["items"], MAX_NOTIFICATIONS).map { element ->
        val o = StrictJson.obj(element, setOf("id", "subject", "category", "severity", "title", "body", "createdAt", "read", "target"),
            required = setOf("id", "subject", "category", "severity", "title", "body", "createdAt", "read"))
        AppNotification(
            id = StrictJson.str(o, "id"), subjectId = StrictJson.str(o, "subject"), category = StrictJson.enum(o, "category"),
            severity = StrictJson.enum(o, "severity"), title = StrictJson.str(o, "title"), body = StrictJson.str(o, "body"),
            target = if ("target" in o) decodeTarget(o["target"]) else NotificationTarget.None,
            createdAt = StrictJson.instant(o, "createdAt"), read = StrictJson.bool(o, "read"),
        )
    }
    val seen = StrictJson.array(root["seen"], MAX_NOTIFICATION_SEEN).map { StrictJson.prim(it).also { id -> if (!id.matches(notificationIdPattern)) throw StrictJson.Bad() } }
    if (items.map { it.id }.distinct().size != items.size || seen.distinct().size != seen.size) throw StrictJson.Bad()
    return NotificationDocument(
        NotificationPreferences(StrictJson.bool(p, "master"), StrictJson.bool(p, "native"), StrictJson.bool(p, "privacy"), categories),
        if ("snoozeAllUntil" in root) StrictJson.instant(root, "snoozeAllUntil") else null, snoozes, items, seen,
    )
}

private fun decodeTarget(element: JsonElement?): NotificationTarget {
    val type = StrictJson.str(element as? JsonObject ?: throw StrictJson.Bad(), "type")
    return when (type) {
        "resource" -> {
            val o = StrictJson.obj(element, setOf("type", "profileId", "binding", "resource"), required = setOf("type", "binding", "resource"))
            NotificationTarget.Resource(StrictJson.strOrNull(o, "profileId"), StrictJson.str(o, "binding"), decodeWatchResource(o["resource"]))
        }
        "task" -> {
            val o = StrictJson.obj(element, setOf("type", "profileId", "binding", "upid"), required = setOf("type", "binding", "upid"))
            NotificationTarget.Task(StrictJson.strOrNull(o, "profileId"), StrictJson.str(o, "binding"), StrictJson.str(o, "upid"))
        }
        "scheduleRun" -> {
            val o = StrictJson.obj(element, setOf("type", "planId", "runId"))
            NotificationTarget.ScheduleRun(StrictJson.str(o, "planId"), StrictJson.str(o, "runId"))
        }
        else -> throw StrictJson.Bad()
    }
}
