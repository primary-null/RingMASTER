package local.ringleader.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Direct Proxmox VE. The wire value is exactly [DIRECT_PVE_KIND]. */
const val DIRECT_PVE_KIND = "directPVE"

/** Proxmox Datacenter Manager. The wire value is exactly [PDM_KIND]. */
const val PDM_KIND = "pdm"

private val profileKinds = setOf(DIRECT_PVE_KIND, PDM_KIND)

private const val PROFILE_VERSION = 1
private const val MAX_PROFILE_BYTES = 256 * 1024
private val documentKeys = setOf("version", "profiles")
private val profileKeys = setOf("id", "displayName", "kind", "origin", "tokenId", "sha256Pin")
private val requiredProfileKeys = setOf("id", "displayName", "kind", "origin", "tokenId")

/**
 * Saved connection metadata. There is no secret field: the token secret exists only in a live [Connection].
 * [id] is the persistence identity. [origin] is the HTTPS origin, so two profiles may share an origin.
 * [kind] is [DIRECT_PVE_KIND] or [PDM_KIND]. The token secret is not stored.
 */
data class ConnectionProfile(
    val id: String,
    val displayName: String,
    val kind: String,
    val origin: String,
    val tokenId: String,
    val sha256Pin: String?,
) {
    val passwordLogin: Boolean get() = '!' !in tokenId
    init {
        require(canonicalProfileId(id) == id) { "The profile identity is invalid." }
        require(kind in profileKinds) { "Only a direct Proxmox VE or PDM profile can be saved." }
        require(displayName.isNotEmpty() && displayName.length <= 80 && displayName == displayName.trim() && displayName.none { it.isISOControl() }) {
            "Enter a profile name up to 80 characters."
        }
        require(origin == serverOrigin(origin).toString()) { "Use an HTTPS server address, including its port." }
        validateIdentity(tokenId, kind)
        require(sha256Pin == null || sha256Pin.matches(sha256PinPattern)) { "Use a 64-digit SHA-256 certificate fingerprint." }
    }

    fun definesSameConnection(other: ConnectionProfile) =
        kind == other.kind && origin == other.origin && tokenId == other.tokenId && sha256Pin == other.sha256Pin
}

data class ProfileDraft(
    val id: String?,
    val displayName: String,
    val origin: String,
    val tokenId: String,
    val sha256Pin: String,
    val kind: String = DIRECT_PVE_KIND,
)

class ProfileStoreException(message: String, val blocksSave: Boolean = false) : IllegalStateException(message)

/** Absolute profile file. Relative XDG values are ignored so a save cannot land in the working directory. */
fun profileFile(env: Map<String, String> = System.getenv(), home: Path = Path.of(System.getProperty("user.home"))): Path {
    val configured = env["XDG_CONFIG_HOME"]?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
    val base = if (configured != null && configured.isAbsolute) configured.normalize() else home.resolve(".config")
    val file = base.resolve("ringmaster").resolve("profiles.json").normalize()
    require(file.isAbsolute) { "The profile location must be an absolute path." }
    return file
}

/** Build a profile from editor input. Canonicalizes the origin, token ID and pin without creating a [Connection]. Kind is taken from the draft and is not inferred from the port. */
fun profileFromDraft(draft: ProfileDraft, id: String = draft.id ?: UUID.randomUUID().toString()): ConnectionProfile {
    val name = draft.displayName.trim()
    require(name.isNotEmpty() && name.length <= 80 && name.none { it.isISOControl() }) { "Enter a profile name up to 80 characters." }
    require(draft.kind in profileKinds) { "Choose a direct Proxmox VE or PDM connection." }
    val origin = serverOrigin(draft.origin).toString()
    val token = draft.tokenId.trim()
    validateIdentity(token, draft.kind)
    return ConnectionProfile(canonicalProfileId(id), name, draft.kind, origin, token, canonicalOptionalPin(draft.sha256Pin))
}

fun connectionKindLabel(kind: String): String = when (kind) {
    PDM_KIND -> "PDM"
    DIRECT_PVE_KIND -> "Direct PVE"
    else -> "Connection"
}

fun canonicalOptionalPin(value: String): String? {
    val pin = value.replace(":", "").trim().lowercase()
    if (pin.isEmpty()) return null
    require(pin.matches(sha256PinPattern)) { "Use a 64-digit SHA-256 certificate fingerprint." }
    return pin
}

private fun canonicalProfileId(id: String): String = try {
    UUID.fromString(id).toString()
} catch (_: IllegalArgumentException) {
    throw IllegalArgumentException("The profile identity is invalid.")
}

internal fun encodeProfiles(profiles: List<ConnectionProfile>): String {
    val document = buildJsonObject {
        put("version", PROFILE_VERSION)
        put("profiles", buildJsonArray {
            profiles.forEach { profile ->
                add(buildJsonObject {
                    put("id", profile.id)
                    put("displayName", profile.displayName)
                    put("kind", profile.kind)
                    put("origin", profile.origin)
                    put("tokenId", profile.tokenId)
                    if (profile.sha256Pin != null) put("sha256Pin", profile.sha256Pin)
                })
            }
        })
    }
    return document.toString() + "\n"
}

internal fun decodeProfiles(text: String): List<ConnectionProfile> {
    rejectDuplicateKeys(text)
    val root = try {
        Json.parseToJsonElement(text)
    } catch (_: Exception) {
        throw ProfileStoreException("Saved profiles are not valid JSON. The file was left unchanged.", blocksSave = true)
    }
    val document = root as? JsonObject
        ?: throw ProfileStoreException("Saved profiles are not valid JSON. The file was left unchanged.", blocksSave = true)
    val unknown = document.keys - documentKeys
    if (unknown.isNotEmpty()) throw ProfileStoreException(unsupported(unknown), blocksSave = true)
    if ("version" !in document || "profiles" !in document) {
        throw ProfileStoreException("Saved profiles are missing a supported version. The file was left unchanged.", blocksSave = true)
    }
    val versionElement = document["version"] ?: throw ProfileStoreException("Saved profiles are missing a supported version. The file was left unchanged.", true)
    val versionPrimitive = versionElement as? JsonPrimitive
    if (versionPrimitive == null || versionPrimitive.isString || versionPrimitive.intOrNull != PROFILE_VERSION) {
        throw ProfileStoreException("Saved profiles use an unsupported version. The file was left unchanged.", blocksSave = true)
    }
    val entries = document["profiles"] as? JsonArray
        ?: throw ProfileStoreException("Saved profiles are not in a supported form. The file was left unchanged.", blocksSave = true)
    val profiles = entries.map { decodeProfile(it) }
    if (profiles.map { it.id }.distinct().size != profiles.size) {
        throw ProfileStoreException("Saved profiles contain a duplicate identity. The file was left unchanged.", blocksSave = true)
    }
    return profiles
}

private fun unsupported(keys: Set<String>) =
    "Saved profiles contain unsupported data (${keys.sorted().joinToString()}). The file was left unchanged."

private fun decodeProfile(element: JsonElement): ConnectionProfile {
    val profile = element as? JsonObject
        ?: throw ProfileStoreException("Saved profiles are not in a supported form. The file was left unchanged.", blocksSave = true)
    val unknown = profile.keys - profileKeys
    if (unknown.isNotEmpty()) throw ProfileStoreException(unsupported(unknown), blocksSave = true)
    val missing = requiredProfileKeys - profile.keys
    if (missing.isNotEmpty()) {
        throw ProfileStoreException("A saved profile is missing ${missing.sorted().joinToString()}. The file was left unchanged.", blocksSave = true)
    }
    try {
        return ConnectionProfile(
            id = requiredText(profile, "id"),
            displayName = requiredText(profile, "displayName"),
            kind = requiredText(profile, "kind"),
            origin = requiredText(profile, "origin"),
            tokenId = requiredText(profile, "tokenId"),
            sha256Pin = optionalPin(profile),
        )
    } catch (e: ProfileStoreException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw ProfileStoreException("A saved profile is invalid (${e.message}). The file was left unchanged.", blocksSave = true)
    } catch (e: IllegalStateException) {
        throw ProfileStoreException("A saved profile is invalid (${e.message}). The file was left unchanged.", blocksSave = true)
    }
}

private fun requiredText(profile: JsonObject, key: String): String {
    val primitive = profile[key] as? JsonPrimitive
    if (primitive == null || !primitive.isString) {
        throw ProfileStoreException("A saved profile has an invalid $key. The file was left unchanged.", blocksSave = true)
    }
    return primitive.content
}

private fun optionalPin(profile: JsonObject): String? {
    if ("sha256Pin" !in profile) return null
    val primitive = profile["sha256Pin"] as? JsonPrimitive
    if (primitive == null || !primitive.isString || primitive.contentOrNull == null) {
        throw ProfileStoreException("A saved profile has an invalid certificate pin. The file was left unchanged.", blocksSave = true)
    }
    return primitive.content
}

/**
 * User-config profile file. Writes a sibling temp file and replaces the destination in the same directory.
 * A malformed or unsupported document is never rewritten. Link destinations are not opened for writing.
 */
class ProfileStore(
    val file: Path,
    private val replace: (Path, Path) -> Unit = { temporary, target -> atomicReplace(temporary, target) },
) {
    var onNewerSchema: (String) -> Unit = {}
    private var newer = false
    private var snapshot: List<ConnectionProfile> = emptyList()
    private var diskBytes: ByteArray? = null
    private var loaded = false
    private var blocked = false
    private var blockedMessage = "Saved profiles could not be read. The file was left unchanged."

    fun profiles(): List<ConnectionProfile> = snapshot.toList()

    fun load(): List<ConnectionProfile> {
        if (newer) return emptyList()
        try {
            ensureDirectoryIsNotALink()
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                diskBytes = null
                snapshot = emptyList()
                blocked = false
                loaded = true
                return emptyList()
            }
            if (Files.isSymbolicLink(file)) {
                throw ProfileStoreException("The profile file is a link and was left unchanged.", blocksSave = true)
            }
            val bytes = readBounded(file)
            val text = decodeUtf8(bytes)
            val schema = runCatching { (Json.parseToJsonElement(text) as? JsonObject)?.get("version")?.jsonPrimitive?.intOrNull }.getOrNull()
            if (schema != null && schema > PROFILE_VERSION) {
                newer = true
                block("profiles.json was written by a newer RingMASTER and is left untouched.")
                onNewerSchema(blockedMessage)
                snapshot = emptyList()
                return emptyList()
            }
            val profiles = decodeProfiles(text)
            diskBytes = bytes
            snapshot = profiles
            blocked = false
            loaded = true
            return profiles.toList()
        } catch (e: ProfileStoreException) {
            block(e.message)
            throw if (e.blocksSave) e else ProfileStoreException(e.message ?: blockedMessage, blocksSave = true)
        } catch (_: Exception) {
            block(null)
            throw ProfileStoreException(blockedMessage, blocksSave = true)
        }
    }

    fun save(profiles: List<ConnectionProfile>): List<ConnectionProfile> {
        if (!loaded) load()
        if (blocked) throw ProfileStoreException(blockedMessage, blocksSave = true)
        val payload = encodeProfiles(profiles).toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_PROFILE_BYTES) throw ProfileStoreException("Profiles are too large to save. The previous profiles are unchanged.")
        if (profiles.map { it.id }.distinct().size != profiles.size) throw ProfileStoreException("Profiles contain duplicate identities. The previous profiles are unchanged.")
        val directory = file.parent ?: throw ProfileStoreException("The profile location is invalid.")
        var temporary: Path? = null
        try {
            ensureDirectoryIsNotALink()
            if (Files.isSymbolicLink(file)) {
                throw ProfileStoreException("The profile file is a link and was left unchanged.", blocksSave = true)
            }
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                val currentBytes = readBounded(file)
                if (diskBytes?.contentEquals(currentBytes) != true) {
                    decodeProfiles(decodeUtf8(currentBytes))
                    throw ProfileStoreException("Profiles changed on disk. Reload the application before saving; the file was left unchanged.")
                }
            }
            createOwnerDirectory(directory)
            val temp = createTemporary(directory)
            temporary = temp
            Files.write(temp, payload, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            grantUserOnly(temp, directory = false)
            replace(temp, file)
            diskBytes = payload
            snapshot = profiles.toList()
            return snapshot.toList()
        } catch (e: ProfileStoreException) {
            if (e.blocksSave) block(e.message)
            throw e
        } catch (_: Exception) {
            throw ProfileStoreException("Profiles could not be saved. The previous profiles are unchanged.")
        } finally {
            temporary?.let {
                try { Files.deleteIfExists(it) } catch (_: Exception) {}
            }
        }
    }

    private fun block(message: String?) {
        blocked = true
        loaded = true
        if (!message.isNullOrBlank()) blockedMessage = message
    }

    private fun ensureDirectoryIsNotALink() {
        val directory = file.parent ?: return
        if (Files.isSymbolicLink(directory)) {
            throw ProfileStoreException("The profile directory is a link. Saved profiles were left unchanged.", blocksSave = true)
        }
    }


}

internal fun atomicReplace(temporary: Path, target: Path) {
    if (Files.isSymbolicLink(target)) {
        throw ProfileStoreException("The profile file is a link and was left unchanged.", blocksSave = true)
    }
    moveAtomic(temporary, target)
}

private fun moveAtomic(source: Path, target: Path) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        throw ProfileStoreException("Profiles could not be saved safely. The previous profiles are unchanged.")
    }
}

private fun readBounded(file: Path): ByteArray {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        throw ProfileStoreException("The profile path is not a regular file. It was left unchanged.", blocksSave = true)
    }
    val bytes = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_PROFILE_BYTES + 1) }
    if (bytes.size > MAX_PROFILE_BYTES) {
        throw ProfileStoreException("Saved profiles are too large to read. The file was left unchanged.", blocksSave = true)
    }
    return bytes
}

private fun createOwnerDirectory(directory: Path) {
    if (Files.isSymbolicLink(directory)) {
        throw ProfileStoreException("The profile directory is a link. Saved profiles were left unchanged.", blocksSave = true)
    }
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
        if (supportsPosix(directory)) Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(directoryPermissions()))
        else Files.createDirectories(directory)
    }
    grantUserOnly(directory, directory = true)
}

private fun createTemporary(directory: Path): Path {
    val temporary = if (supportsPosix(directory)) {
        Files.createTempFile(directory, ".profiles-", ".tmp", PosixFilePermissions.asFileAttribute(filePermissions()))
    } else {
        Files.createTempFile(directory, ".profiles-", ".tmp")
    }
    if (Files.isSymbolicLink(temporary)) {
        try { Files.deleteIfExists(temporary) } catch (_: Exception) {}
        throw ProfileStoreException("Profiles could not be saved. The previous profiles are unchanged.")
    }
    return temporary
}

private fun supportsPosix(path: Path) = path.fileSystem.supportedFileAttributeViews().contains("posix")

private fun directoryPermissions() = setOf(
    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
)

private fun filePermissions() = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

private fun grantUserOnly(path: Path, directory: Boolean) {
    if (!supportsPosix(path) || !Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) return
    Files.setPosixFilePermissions(path, if (directory) directoryPermissions() else filePermissions())
}

private fun decodeUtf8(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    throw ProfileStoreException("Saved profiles are not valid text. The file was left unchanged.", blocksSave = true)
}

private fun rejectDuplicateKeys(text: String) {
    JsonStructure(text).readDocument()
}

private class JsonStructure(private val text: String) {
    private var index = 0
    private val invalid = ProfileStoreException("Saved profiles are not valid JSON. The file was left unchanged.", blocksSave = true)

    fun readDocument() {
        skipWhitespace()
        if (index >= text.length || text[index] != '{') fail()
        parseObject(0)
        skipWhitespace()
        if (index != text.length) fail()
    }

    private fun fail(): Nothing = throw invalid

    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) index++
    }

    private fun parseString(): String {
        if (index >= text.length || text[index] != '"') fail()
        index++
        val out = StringBuilder()
        while (index < text.length) {
            when (val current = text[index++]) {
                '"' -> return out.toString()
                '\\' -> {
                    if (index >= text.length) fail()
                    when (val escaped = text[index++]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (index + 4 > text.length) fail()
                            val hex = text.substring(index, index + 4)
                            index += 4
                            out.append((hex.toIntOrNull(16) ?: fail()).toChar())
                        }
                        else -> fail()
                    }
                }
                else -> {
                    if (current.code < 0x20) fail()
                    out.append(current)
                }
            }
        }
        fail()
    }

    private fun literal(word: String) {
        if (!text.startsWith(word, index)) fail()
        index += word.length
    }

    private fun skipNumber() {
        if (index < text.length && text[index] == '-') index++
        if (index >= text.length || !text[index].isDigit()) fail()
        if (text[index] == '0') index++ else while (index < text.length && text[index].isDigit()) index++
        if (index < text.length && text[index] == '.') {
            index++
            if (index >= text.length || !text[index].isDigit()) fail()
            while (index < text.length && text[index].isDigit()) index++
        }
        if (index < text.length && text[index] in "eE") {
            index++
            if (index < text.length && text[index] in "+-") index++
            if (index >= text.length || !text[index].isDigit()) fail()
            while (index < text.length && text[index].isDigit()) index++
        }
    }

    private fun skipValue(depth: Int) {
        skipWhitespace()
        if (index >= text.length) fail()
        when (text[index]) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> parseString()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> skipNumber()
        }
    }

    private fun parseObject(depth: Int) {
        if (depth > 8) fail()
        index++
        val keys = HashSet<String>()
        skipWhitespace()
        if (index < text.length && text[index] == '}') { index++; return }
        while (true) {
            skipWhitespace()
            val key = parseString()
            if (!keys.add(key)) throw ProfileStoreException("Saved profiles contain duplicate data ($key). The file was left unchanged.", true)
            skipWhitespace()
            if (index >= text.length || text[index] != ':') fail()
            index++
            skipValue(depth + 1)
            skipWhitespace()
            if (index >= text.length) fail()
            when (text[index++]) {
                '}' -> return
                ',' -> continue
                else -> fail()
            }
        }
    }

    private fun parseArray(depth: Int) {
        if (depth > 8) fail()
        index++
        skipWhitespace()
        if (index < text.length && text[index] == ']') { index++; return }
        while (true) {
            skipValue(depth + 1)
            skipWhitespace()
            if (index >= text.length) fail()
            when (text[index++]) {
                ']' -> return
                ',' -> continue
                else -> fail()
            }
        }
    }
}
