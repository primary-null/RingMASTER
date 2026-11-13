package local.ringleader.core

import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val WATCH_STORE_VERSION = 1
internal const val MAX_WATCH_BYTES = 256 * 1024

/**
 * `watcher.json` beside `profiles.json`: the watched resources of saved connections. Stored per entry: the profile id,
 * the connection binding digest, the resource identity, its last seen name and when it was added. Never stored: the
 * server address, account, pin, any secret, any metric or state. Watches of Demo mode and of connections that were
 * not saved as a profile stay in memory. See [GuardedFile] for the write and failure rules.
 */
class WatchStore internal constructor(private val guarded: GuardedFile) {
    constructor(file: Path) : this(GuardedFile(file, MAX_WATCH_BYTES))
    /** For tests: [replace] stands in for the atomic move. */
    constructor(file: Path, replace: (Path, Path) -> Unit) : this(GuardedFile(file, MAX_WATCH_BYTES, replace))

    val file: Path get() = guarded.file
    fun problem(): GuardedStoreException? = guarded.problem()
    fun load(): List<WatchEntry> = guarded.load(emptyList(), ::decodeWatched)
    fun save(entries: List<WatchEntry>) = guarded.save(encodeWatched(entries))
    fun setAside(now: Instant): Path = guarded.setAside(now)
}

internal fun encodeWatched(entries: List<WatchEntry>): String = buildJsonObject {
    put("version", WATCH_STORE_VERSION)
    put("watched", buildJsonArray {
        entries.filter { it.profileId != null }.forEach { e ->
            add(buildJsonObject {
                put("profileId", e.profileId); put("binding", e.binding); put("resource", encodeWatchResource(e.resource))
                put("name", e.name); put("addedAt", e.addedAt.toString())
            })
        }
    })
}.toString() + "\n"

internal fun decodeWatched(text: String): List<WatchEntry> {
    val root = Json.parseToJsonElement(text) as? JsonObject ?: throw StrictJson.Bad()
    StrictJson.version(root, WATCH_STORE_VERSION)
    StrictJson.obj(root, setOf("version", "watched"))
    val entries = StrictJson.array(root["watched"], MAX_WATCHED).map { element ->
        val o = StrictJson.obj(element, setOf("profileId", "binding", "resource", "name", "addedAt"))
        WatchEntry(StrictJson.str(o, "profileId"), StrictJson.str(o, "binding"), decodeWatchResource(o["resource"]), StrictJson.str(o, "name"), StrictJson.instant(o, "addedAt"))
    }
    if (entries.any { it.binding == DEMO_WATCH_BINDING } || entries.map { it.binding to it.resource }.distinct().size != entries.size) throw StrictJson.Bad()
    return entries
}
