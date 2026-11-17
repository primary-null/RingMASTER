package local.ringleader.core.guesthelper

import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import local.ringleader.core.SecretVault
import local.ringleader.core.VaultStatus
import local.ringleader.core.createOwnerOnlyDirectories
import local.ringleader.core.integrations.GuestRef
import local.ringleader.core.integrations.ServiceOrigin
import local.ringleader.core.profileFile
import local.ringleader.core.writeOwnerOnlyText

/**
 * Where a helper is installed: inside a guest, or on a node. [remote] is the server the guest or node belongs to (its
 * origin), because two servers can both have a VM 100.
 */
sealed interface GuestHelperPlace {
    data class Guest(val guest: GuestRef, val remote: String = "") : GuestHelperPlace
    /** [scope] is the PDM remote or "" for a direct connection, as in [GuestRef]. */
    data class Node(val node: String, val scope: String = "", val remote: String = "") : GuestHelperPlace
}

/**
 * A paired helper. Non-secret only: the token is in the OS password safe under [GuestHelperVault.key]. [pin] is the
 * certificate's SHA-256 the user confirmed. [version] and [lastSeen] are from the last good `whoami`.
 */
data class GuestHelperLink(
    val id: String,
    val place: GuestHelperPlace,
    val host: String,
    val port: Int,
    val pin: String,
    val version: String,
    val hostName: String,
    val pairedAt: Instant,
    val lastSeen: Instant?,
    /** False when the token is kept only in memory for this run (no password safe here). */
    val tokenInSafe: Boolean = true,
)

/**
 * `guest-helpers.json` beside `services.json`: the paired helpers, nothing secret. Written atomically and owner-only,
 * read with the same rules as the linked services' file: a missing file is empty; a corrupt or newer file blocks saving
 * and is left untouched, with the reason in [blockedReason].
 */
class GuestHelperStore(private val file: Path? = defaultFile()) {
    private var memory: List<GuestHelperLink> = emptyList()
    var blockedReason: String? = null
        private set

    fun load(): List<GuestHelperLink> {
        val path = file ?: return memory
        blockedReason = null
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        if (Files.isSymbolicLink(path)) return block("guest-helpers.json is a link to another file and is left untouched.")
        val bytes = try {
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_BYTES + 1) }
        } catch (_: java.io.IOException) {
            return block(UNREADABLE)
        }
        if (bytes.size > MAX_BYTES) return block(UNREADABLE)
        val root = runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: return block(UNREADABLE)
        val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: return block(UNREADABLE)
        if (version > VERSION) return block("guest-helpers.json was written by a newer RingMASTER and is left untouched.")
        if (version != VERSION) return block(UNREADABLE)
        return (root["helpers"] as? JsonArray ?: return block(UNREADABLE)).map { decode(it as? JsonObject) ?: return block(UNREADABLE) }
    }

    private fun block(reason: String): List<GuestHelperLink> { blockedReason = reason; return emptyList() }

    fun save(links: List<GuestHelperLink>) {
        blockedReason?.let { throw IllegalStateException(it) }
        val path = file ?: run { memory = links.toList(); return }
        if (Files.isSymbolicLink(path)) throw IllegalStateException("guest-helpers.json is a link to another file and is left untouched.")
        val directory = path.parent ?: throw IllegalStateException("guest-helpers.json has no folder.")
        createOwnerOnlyDirectories(directory)
        val temp = Files.createTempFile(directory, ".guest-helpers-", ".tmp")
        try {
            writeOwnerOnlyText(temp, encode(links))
            FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            throw IllegalStateException("The Engine pairings could not be saved safely.")
        } finally {
            // After a good move the temp file is gone; after a failure the save has already reported it, and a stray
            // owner-only temp file in the config folder harms nothing.
            runCatching { Files.deleteIfExists(temp) }
        }
    }

    companion object {
        const val VERSION = 1
        private const val MAX_BYTES = 1024 * 1024
        private const val UNREADABLE = "guest-helpers.json could not be read and is left untouched."
        fun defaultFile(): Path = profileFile().resolveSibling("guest-helpers.json")
        private val ID = Regex("[A-Za-z0-9-]{1,64}")
        private val PIN = Regex("[0-9a-f]{64}")

        internal fun encode(links: List<GuestHelperLink>): String = buildJsonObject {
            put("version", VERSION)
            put("helpers", buildJsonArray {
                for (l in links) add(buildJsonObject {
                    put("id", l.id)
                    when (val p = l.place) {
                        is GuestHelperPlace.Guest -> put("guest", buildJsonObject {
                            put("kind", p.guest.kind)
                            put("vmid", p.guest.vmid)
                            put("scope", p.guest.scope)
                            put("remote", p.remote)
                        })
                        is GuestHelperPlace.Node -> put("node", buildJsonObject {
                            put("name", p.node)
                            put("scope", p.scope)
                            put("remote", p.remote)
                        })
                    }
                    put("host", l.host); put("port", l.port); put("pin", l.pin); put("version", l.version); put("hostName", l.hostName)
                    put("pairedAt", l.pairedAt.toString())
                    l.lastSeen?.let { put("lastSeen", it.toString()) }
                    put("tokenInSafe", l.tokenInSafe)
                })
            })
        }.toString()

        private fun decode(o: JsonObject?): GuestHelperLink? {
            o ?: return null
            fun s(k: String, from: JsonObject = o) = (from[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val place = (o["guest"] as? JsonObject)?.let { g ->
                val kind = s("kind", g) ?: return null
                val vmid = (g["vmid"] as? JsonPrimitive)?.intOrNull ?: return null
                GuestHelperPlace.Guest(GuestRef(kind, vmid, s("scope", g) ?: ""), s("remote", g) ?: "")
            }
                ?: (o["node"] as? JsonObject)?.let { n -> GuestHelperPlace.Node(s("name", n) ?: return null, s("scope", n) ?: "", s("remote", n) ?: "") }
                ?: return null
            val host = s("host")?.let { ServiceOrigin.canonicalHost(it) } ?: return null
            val port = (o["port"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..65535 } ?: return null
            val pin = s("pin")?.takeIf { PIN.matches(it) } ?: return null
            fun instant(k: String) = s(k)?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val id = s("id")?.takeIf { ID.matches(it) } ?: return null
            val version = s("version") ?: return null
            val pairedAt = instant("pairedAt") ?: return null
            val inSafe = (o["tokenInSafe"] as? JsonPrimitive)?.booleanOrNull ?: true
            return GuestHelperLink(id, place, host, port, pin, version, s("hostName") ?: "", pairedAt, instant("lastSeen"), inSafe)
        }
    }
}

/** The OS password safe for pairing tokens: the `guesthelper:<id>` namespace, apart from profiles and linked services. */
class GuestHelperVault(private val inner: SecretVault) {
    val status: VaultStatus get() = runCatching { inner.status() }.getOrElse { VaultStatus.Unavailable("The password safe could not be used.") }
    fun key(id: String) = "guesthelper:$id"
    fun store(link: GuestHelperLink, token: CharArray) =
        inner.store(key(link.id), "pairing token", "RingMASTER Engine · ${link.hostName.ifBlank { link.host }}", token)
    /** A fresh array the caller wipes, or null. */
    fun read(id: String): CharArray? = inner.read(key(id))
    fun forget(id: String) = inner.forget(key(id))
}
