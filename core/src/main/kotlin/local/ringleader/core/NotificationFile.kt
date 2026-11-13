package local.ringleader.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A notification or watch-list file could not be used.
 * [readOnly]: a newer file, a link, or a file that changed under us; it is never overwritten.
 * [unreadable]: a malformed file; it is never overwritten implicitly, only set aside by an explicit user action.
 * Neither set: one save failed and the previous file is unchanged; a later save may succeed.
 */
class GuardedStoreException(message: String, val readOnly: Boolean = false, val unreadable: Boolean = false) : IllegalStateException(message)

/** Thrown by a decoder for a file written by a newer version. */
internal class NewerStoreVersion : Exception()

/**
 * One small JSON file in the owner-only config folder, with the same rules as `schedules.json`: strict UTF-8, bounded
 * size, owner-only temporary file in the same directory moved atomically with no non-atomic fallback, and fail closed
 * when the file on disk is not the one that was read. [name] is the file name used in messages.
 */
internal class GuardedFile(
    val file: Path,
    private val maxBytes: Int,
    private val replace: (Path, Path) -> Unit = { temporary, target ->
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { throw GuardedStoreException("${target.fileName} could not be saved safely. The previous file is unchanged.") }
    },
) {
    private val name: String = file.fileName.toString()
    private var diskDigest: String? = null
    private var loaded = false
    private var blocked: GuardedStoreException? = null

    fun problem(): GuardedStoreException? = blocked

    /** [empty] when the file does not exist. [decode] throws [NewerStoreVersion] or any other exception for bad data. */
    fun <T> load(empty: T, decode: (String) -> T): T {
        try {
            if (Files.isSymbolicLink(file)) throw GuardedStoreException("$name is a link and was left unchanged.", readOnly = true)
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                diskDigest = null; loaded = true; blocked = null
                return empty
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw GuardedStoreException("$name is not a regular file and was left unchanged.", readOnly = true)
            val bytes = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(maxBytes + 1) }
            if (bytes.size > maxBytes) throw GuardedStoreException("$name is too large and was left unchanged.", unreadable = true)
            val text = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) { throw GuardedStoreException("$name is not valid UTF-8 and was left unchanged.", unreadable = true) }
            val value = try { rejectDuplicateKeys(text); decode(text) }
                catch (_: NewerStoreVersion) { throw GuardedStoreException("$name was written by a newer RingMASTER and is left untouched.", readOnly = true) }
                catch (_: Exception) { throw GuardedStoreException("$name contains unsupported or invalid data and was left unchanged.", unreadable = true) }
            diskDigest = digest(bytes); loaded = true; blocked = null
            return value
        } catch (e: GuardedStoreException) {
            blocked = e; loaded = true
            throw e
        } catch (_: Exception) {
            val e = GuardedStoreException("$name could not be read and was left unchanged.", unreadable = true)
            blocked = e; loaded = true
            throw e
        }
    }

    fun save(text: String) {
        check(loaded) { "Load $name before saving." }
        blocked?.let { throw it }
        val payload = text.toByteArray(Charsets.UTF_8)
        if (payload.size > maxBytes) throw GuardedStoreException("$name would be too large to save. The previous file is unchanged.")
        val directory = file.parent ?: throw GuardedStoreException("The location of $name is invalid.")
        var temporary: Path? = null
        var replaced = false
        try {
            createOwnerOnlyDirectories(directory)
            if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(file)) throw GuardedStoreException("The location of $name is a link. Nothing was saved.", readOnly = true).also { blocked = it }
            val current = if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { digest(it.readNBytes(maxBytes + 1)) } else null
            if (current != diskDigest) {
                throw GuardedStoreException("$name changed outside RingMASTER and is left untouched. Quit and relaunch RingMASTER to reload it.", readOnly = true).also { blocked = it }
            }
            val temp = Files.createTempFile(directory, ".${name.substringBeforeLast('.')}-", ".tmp")
            temporary = temp
            grantOwnerOnly(temp)
            Files.write(temp, payload)
            replace(temp, file)
            replaced = true
            temporary = null
            // The new content is on disk from here on, also when tightening its permissions fails below.
            diskDigest = digest(payload)
            grantOwnerOnly(file)
        } catch (e: GuardedStoreException) { throw e }
        catch (e: Exception) {
            throw GuardedStoreException(if (replaced) "$name was saved, but securing it failed (${e.javaClass.simpleName})."
                else "$name could not be saved (${e.javaClass.simpleName}). The previous file is unchanged.")
        } finally { temporary?.let { runCatching { Files.deleteIfExists(it) } } }
    }

    /** Explicit user action for an unreadable file: move it aside, keeping its content, and start empty. */
    fun setAside(now: Instant): Path {
        val problem = blocked ?: throw GuardedStoreException("$name is readable; nothing was moved.")
        if (!problem.unreadable) throw GuardedStoreException("Only an unreadable $name can be set aside.")
        val stamp = now.toString().replace(Regex("[^0-9TZ]"), "")
        val target = file.resolveSibling("${name.substringBeforeLast('.')}.unreadable-$stamp.json")
        try { Files.move(file, target, StandardCopyOption.ATOMIC_MOVE) }
        catch (e: Exception) { throw GuardedStoreException("$name could not be set aside (${e.javaClass.simpleName}). It is unchanged.", unreadable = true) }
        diskDigest = null; blocked = null; loaded = true
        return target
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Strict field readers for the guarded files. Every failure is one exception type; the caller reports the file as unreadable. */
internal object StrictJson {
    class Bad : Exception()

    fun version(root: JsonObject, supported: Int) {
        val version = (root["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw Bad()
        if (version > supported) throw NewerStoreVersion()
        if (version != supported.toLong()) throw Bad()
    }
    fun array(element: JsonElement?, max: Int): JsonArray = (element as? JsonArray)?.takeIf { it.size <= max } ?: throw Bad()
    fun obj(element: JsonElement?, allowed: Set<String>, required: Set<String> = allowed): JsonObject {
        val o = element as? JsonObject ?: throw Bad()
        if (!allowed.containsAll(o.keys) || !o.keys.containsAll(required)) throw Bad()
        return o
    }
    fun prim(element: JsonElement?): String {
        val p = element as? JsonPrimitive ?: throw Bad()
        if (!p.isString || p is JsonNull) throw Bad()
        return p.content
    }
    fun str(o: JsonObject, key: String): String = prim(o[key])
    fun strOrNull(o: JsonObject, key: String): String? = if (key in o) str(o, key) else null
    fun int(o: JsonObject, key: String): Int {
        val p = o[key] as? JsonPrimitive ?: throw Bad()
        if (p.isString) throw Bad()
        val value = p.longOrNull ?: throw Bad()
        if (value !in Int.MIN_VALUE..Int.MAX_VALUE) throw Bad()
        return value.toInt()
    }
    fun bool(o: JsonObject, key: String): Boolean {
        val p = o[key] as? JsonPrimitive ?: throw Bad()
        if (p.isString) throw Bad()
        return p.booleanOrNull ?: throw Bad()
    }
    fun instant(o: JsonObject, key: String): Instant = Instant.parse(str(o, key))
    inline fun <reified E : Enum<E>> enum(o: JsonObject, key: String): E = enumValueOf(str(o, key))
}
