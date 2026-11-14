package local.ringleader.core

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Profile ids that have a remembered secret. The secret itself is never in this file: a Linux vault lookup can raise
 * an unlock prompt, so the desk must know which rows to offer without reading the safe.
 *
 * A null [file] keeps the set in memory, for tests and the desk's default argument.
 * A missing, unreadable or corrupt file is an empty set and is rewritten on the next [save].
 * A newer [version] is an empty set and is never overwritten.
 */
class RememberedProfiles(private val file: Path?) {
    private var memory: Set<String> = emptySet()
    private var newer = false

    /** Set by [load] when the file belongs to a newer RingMASTER. [save] then does nothing. */
    fun blockedReason(): String? = if (newer) "remembered.json was written by a newer RingMASTER." else null

    fun load(): Set<String> {
        val path = file ?: return memory
        newer = false
        if (!Files.exists(path) || Files.isSymbolicLink(path)) return emptySet()
        val bytes = try {
            Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_BYTES + 1) }
        } catch (_: Exception) {
            return emptySet()
        }
        if (bytes.size > MAX_BYTES) return emptySet()
        val root = try {
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
        } catch (_: Exception) {
            return emptySet()
        } ?: return emptySet()
        val version = (root["version"] as? JsonPrimitive)?.intOrNull
        if (version != null && version > VERSION) {
            newer = true
            return emptySet()
        }
        if (version != VERSION) return emptySet()
        val listed = root["profiles"] as? JsonArray ?: return emptySet()
        return listed.mapNotNull { item ->
            val text = (item as? JsonPrimitive)?.contentOrNull ?: return emptySet()
            text.takeIf { it.isNotEmpty() && it.none { char -> char == '"' || char == '\\' || char.isISOControl() } }
        }.toSet()
    }

    fun save(ids: Set<String>) {
        val path = file
        if (path == null) {
            memory = ids.toSet()
            return
        }
        if (newer || Files.isSymbolicLink(path)) return
        val directory = path.parent ?: return
        val payload = encode(ids)
        var temporary: Path? = null
        try {
            createOwnerOnlyDirectories(directory)
            if (Files.isSymbolicLink(path)) return
            val temp = Files.createTempFile(directory, ".remembered-", ".tmp")
            temporary = temp
            writeOwnerOnlyText(temp, payload)
            if (Files.isSymbolicLink(path)) return
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            temporary = null
        } catch (_: AtomicMoveNotSupportedException) {
            throw IllegalStateException("Remembered profiles could not be saved safely.")
        } finally {
            temporary?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun encode(ids: Set<String>): String {
        val body = ids.sorted().joinToString(",") { id ->
            require(id.none { it == '"' || it == '\\' || it.isISOControl() }) { "The profile identity is invalid." }
            "\"$id\""
        }
        return "{\"version\":$VERSION,\"profiles\":[$body]}"
    }

    private companion object {
        const val VERSION = 1
        const val MAX_BYTES = 256 * 1024
    }
}
