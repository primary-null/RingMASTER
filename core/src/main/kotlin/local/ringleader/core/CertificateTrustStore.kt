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

private const val TRUST_VERSION = 1
private const val MAX_TRUST_BYTES = 64 * 1024
class CertificateTrustStoreException(message: String, val blocksSave: Boolean = false) : IllegalStateException(message)

/** No identities or secrets. An approval is scoped to a canonical HTTPS origin. */
data class ApprovedCertificate(val origin: String, val pin: String) {
    init {
        require(origin == certificateOrigin(origin)) { "Invalid trust origin." }
        require(canonicalOptionalPin(pin) == pin && pin.isNotEmpty()) { "Invalid certificate fingerprint." }
    }
}

fun certificateOrigin(address: String): String {
    val uri = serverOrigin(address)
    return java.net.URI("https", null, uri.host.lowercase(), if (uri.port == 443) -1 else uri.port, null, null, null).toString()
}

private fun encodeTrust(entries: List<ApprovedCertificate>): String = buildJsonObject {
    put("version", TRUST_VERSION)
    put("certificates", buildJsonArray { entries.forEach { e -> add(buildJsonObject { put("origin", e.origin); put("pin", e.pin) }) } })
}.toString() + "\n"

private fun decodeTrust(text: String): List<ApprovedCertificate> {
    try {
        rejectDuplicateKeys(text)
        val root = Json.parseToJsonElement(text) as? JsonObject ?: error("Invalid document")
        require(root.keys == setOf("version", "certificates"))
        val version = root["version"] as? JsonPrimitive ?: error("Missing version")
        require(!version.isString && version.intOrNull == TRUST_VERSION)
        val entries = root["certificates"] as? JsonArray ?: error("Missing certificates")
        require(entries.size <= 256)
        val result = entries.map {
            val entry = it as? JsonObject ?: error("Invalid certificate")
            require(entry.keys == setOf("origin", "pin"))
            fun field(key: String): String {
                val value = entry[key] as? JsonPrimitive ?: error("Invalid field")
                require(value.isString)
                return value.content
            }
            ApprovedCertificate(field("origin"), field("pin"))
        }
        require(result.map { it.origin }.distinct().size == result.size)
        return result
    } catch (_: Exception) {
        throw CertificateTrustStoreException("Remembered certificates are invalid or unsupported. Review certificates.json before connecting; the file was left unchanged.", true)
    }
}

class CertificateTrustStore(
    val file: Path,
    private val replace: (Path, Path) -> Unit = { temporary, target -> replaceTrustAtomically(temporary, target) },
) {
    var onNewerSchema: (String) -> Unit = {}
    private var newer = false
    private var snapshot: List<ApprovedCertificate> = emptyList()
    private var diskBytes: ByteArray? = null
    private var loaded = false
    private var blocked = false
    private var blockedMessage = "Saved certificates could not be read. The file was left unchanged."

    fun certificates(): List<ApprovedCertificate> = snapshot.toList()

    fun load(): List<ApprovedCertificate> {
        if (newer) throw CertificateTrustStoreException(blockedMessage, true)
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
                throw CertificateTrustStoreException("The trust file is a link and was left unchanged.", blocksSave = true)
            }
            val bytes = readBounded(file)
            val text = decodeUtf8(bytes)
            val schema = runCatching { (Json.parseToJsonElement(text) as? JsonObject)?.get("version")?.jsonPrimitive?.intOrNull }.getOrNull()
            if (schema != null && schema > TRUST_VERSION) {
                newer = true
                block("certificates.json was written by a newer RingMASTER and is left untouched.")
                onNewerSchema(blockedMessage)
                throw CertificateTrustStoreException(blockedMessage, true)
            }
            val certificates = decodeTrust(text)
            diskBytes = bytes
            snapshot = certificates
            blocked = false
            loaded = true
            return certificates.toList()
        } catch (e: CertificateTrustStoreException) {
            block(e.message)
            throw if (e.blocksSave) e else CertificateTrustStoreException(e.message ?: blockedMessage, blocksSave = true)
        } catch (_: Exception) {
            block(null)
            throw CertificateTrustStoreException(blockedMessage, blocksSave = true)
        }
    }

    fun save(certificates: List<ApprovedCertificate>): List<ApprovedCertificate> {
        if (!loaded) load()
        if (blocked) throw CertificateTrustStoreException(blockedMessage, blocksSave = true)
        require(certificates.size <= 256) { "At most 256 certificate approvals can be remembered. Forget an unused certificate first." }
        val payload = encodeTrust(certificates).toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_TRUST_BYTES) throw CertificateTrustStoreException("Certificates are too large to save. The previous certificates are unchanged.")
        if (certificates.map { it.origin }.distinct().size != certificates.size) throw CertificateTrustStoreException("Certificates contain duplicate identities. The previous certificates are unchanged.")
        val directory = file.parent ?: throw CertificateTrustStoreException("The trust location is invalid.")
        var temporary: Path? = null
        try {
            ensureDirectoryIsNotALink()
            if (Files.isSymbolicLink(file)) {
                throw CertificateTrustStoreException("The trust file is a link and was left unchanged.", blocksSave = true)
            }
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                val currentBytes = readBounded(file)
                if (diskBytes?.contentEquals(currentBytes) != true) {
                    decodeTrust(decodeUtf8(currentBytes))
                    throw CertificateTrustStoreException("Certificates changed on disk. Reload the application before saving; the file was left unchanged.")
                }
            }
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS) && diskBytes != null) throw CertificateTrustStoreException("Certificates changed on disk. Reload before saving.")
            createOwnerDirectory(directory)
            val temp = createTemporary(directory)
            temporary = temp
            Files.write(temp, payload, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            grantUserOnly(temp, directory = false)
            replace(temp, file)
            diskBytes = payload
            snapshot = certificates.toList()
            return snapshot.toList()
        } catch (e: CertificateTrustStoreException) {
            if (e.blocksSave) block(e.message)
            throw e
        } catch (_: Exception) {
            throw CertificateTrustStoreException("Certificates could not be saved. Check free space and config-folder permissions, then retry; no connection was opened. Previous certificates are unchanged.")
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
            throw CertificateTrustStoreException("The trust directory is a link. Saved certificates were left unchanged.", blocksSave = true)
        }
    }


}

private fun replaceTrustAtomically(temporary: Path, target: Path) {
    if (Files.isSymbolicLink(target)) {
        throw CertificateTrustStoreException("The trust file is a link and was left unchanged.", blocksSave = true)
    }
    moveAtomic(temporary, target)
}

private fun moveAtomic(source: Path, target: Path) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        throw CertificateTrustStoreException("Certificates could not be saved safely. The previous certificates are unchanged.")
    }
}

private fun readBounded(file: Path): ByteArray {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        throw CertificateTrustStoreException("The trust path is not a regular file. It was left unchanged.", blocksSave = true)
    }
    val bytes = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_TRUST_BYTES + 1) }
    if (bytes.size > MAX_TRUST_BYTES) {
        throw CertificateTrustStoreException("Saved certificates are too large to read. The file was left unchanged.", blocksSave = true)
    }
    return bytes
}

private fun createOwnerDirectory(directory: Path) {
    if (Files.isSymbolicLink(directory)) {
        throw CertificateTrustStoreException("The trust directory is a link. Saved certificates were left unchanged.", blocksSave = true)
    }
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
        if (supportsPosix(directory)) Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(directoryPermissions()))
        else Files.createDirectories(directory)
    }
    grantUserOnly(directory, directory = true)
}

private fun createTemporary(directory: Path): Path {
    val temporary = if (supportsPosix(directory)) {
        Files.createTempFile(directory, ".certificates-", ".tmp", PosixFilePermissions.asFileAttribute(filePermissions()))
    } else {
        Files.createTempFile(directory, ".certificates-", ".tmp")
    }
    if (Files.isSymbolicLink(temporary)) {
        try { Files.deleteIfExists(temporary) } catch (_: Exception) {}
        throw CertificateTrustStoreException("Certificates could not be saved. Check free space and config-folder permissions, then retry; no connection was opened. Previous certificates are unchanged.")
    }
    return temporary
}

private fun supportsPosix(path: Path) = path.fileSystem.supportedFileAttributeViews().contains("posix")

private fun directoryPermissions() = setOf(
    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
)

private fun filePermissions() = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

private fun grantUserOnly(path: Path, directory: Boolean) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) return
    if (supportsPosix(path)) {
        Files.setPosixFilePermissions(path, if (directory) directoryPermissions() else filePermissions())
    } else if (!directory) {
        restrictToCurrentUser(path)
    }
}

private fun decodeUtf8(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    throw CertificateTrustStoreException("Saved certificates are not valid text. The file was left unchanged.", blocksSave = true)
}
