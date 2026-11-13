package local.ringleader.core

import java.net.URI
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.serialization.json.*

enum class StorageAction { TemplateDownload, UrlDownload, Upload, RemoveVolume, EditNotes, SetProtection }
enum class MediaKind(val content: String) { Iso("iso"), Template("vztmpl") }
data class MediaTarget(val key: StorageKey, val shared: Boolean)
data class Checksum(val algorithm: String, val hex: String)
data class AplTemplate(val template: String, val packageName: String, val version: String, val type: String,
    val os: String? = null, val section: String = "", val headline: String? = null, val description: String? = null,
    val architecture: String? = null)
data class UrlMetadata(val fileName: String?, val size: Long?, val mimeType: String?) { override fun toString() = "UrlMetadata(redacted)" }
/** Exact backup attributes; unlike a listing, notes are not truncated. No server path is retained. */
data class StorageAttributes(val notes: String?, val protected: Boolean?) {
    override fun toString() = "StorageAttributes(redacted)"
}
/** In-memory file identity. Deliberately not a data class: diagnostics must never render a local path. */
class UploadSource private constructor(internal val path: Path, val size: Long, private val modified: java.nio.file.attribute.FileTime, private val identity: Any?) {
    fun unchanged(): Boolean = try { val a = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS); a.isRegularFile && a.size() == size && a.lastModifiedTime() == modified && a.fileKey() == identity } catch (_: Exception) { false }
    internal fun open() = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)
    override fun toString() = "UploadSource(redacted)"
    companion object {
        fun capture(path: Path): UploadSource {
            val a = try { Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) } catch (_: Exception) { error("Cannot read the selected file.") }
            require(a.isRegularFile && a.size() > 0) { "Choose a nonempty regular file." }
            return UploadSource(path.toAbsolutePath().normalize(), a.size(), a.lastModifiedTime(), a.fileKey())
        }
    }
}
sealed interface StorageRequest {
    data class TemplateDownload(val template: AplTemplate) : StorageRequest
    class UrlDownload(val url: String, val content: String, val fileName: String, val checksum: Checksum? = null,
        val verifyCertificates: Boolean = true, val decompress: String? = null) : StorageRequest { override fun toString() = "UrlDownload(redacted)" }
    class Upload(val source: UploadSource, val content: String, val fileName: String, val checksum: Checksum? = null) : StorageRequest { override fun toString() = "Upload(redacted)" }
    data class RemoveVolume(val volume: StorageVolume) : StorageRequest { override fun toString() = "RemoveVolume(redacted)" }
    class EditNotes(val volume: StorageVolume, val notes: String) : StorageRequest { override fun toString() = "EditNotes(redacted)" }
    data class SetProtection(val volume: StorageVolume, val protect: Boolean) : StorageRequest { override fun toString() = "SetProtection(redacted)" }
}
val StorageRequest.action: StorageAction get() = when(this) {
    is StorageRequest.TemplateDownload -> StorageAction.TemplateDownload
    is StorageRequest.UrlDownload -> StorageAction.UrlDownload
    is StorageRequest.Upload -> StorageAction.Upload
    is StorageRequest.RemoveVolume -> StorageAction.RemoveVolume
    is StorageRequest.EditNotes -> StorageAction.EditNotes
    is StorageRequest.SetProtection -> StorageAction.SetProtection
}
internal val StorageRequest.volume: StorageVolume? get() = when(this) {
    is StorageRequest.RemoveVolume -> volume; is StorageRequest.EditNotes -> volume; is StorageRequest.SetProtection -> volume; else -> null
}
internal val StorageRequest.content: String? get() = when(this) {
    is StorageRequest.TemplateDownload -> "vztmpl"; is StorageRequest.UrlDownload -> content; is StorageRequest.Upload -> content; else -> null
}
internal val StorageRequest.fileName: String? get() = when(this) {
    is StorageRequest.TemplateDownload -> template.template; is StorageRequest.UrlDownload -> fileName; is StorageRequest.Upload -> fileName; else -> null
}
fun downloadUrlError(url: String): String? {
    val u = try { URI(url) } catch (_: Exception) { return "Use a valid HTTP or HTTPS address." }
    return if (url.length !in 1..2048 || url.any { it.isWhitespace() || it.isISOControl() } || u.scheme !in listOf("http", "https") || u.host.isNullOrBlank() || u.rawUserInfo != null || u.rawFragment != null || u.port !in -1..65535 || u.port == 0) "Use an HTTP or HTTPS address without a login or fragment." else null
}
fun contentFileNameError(content: String, name: String): String? {
    if (name.length !in 1..255 || !name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")) || name.contains("..")) return "Use a file name without paths or delimiters."
    val suffixes = when(content) { "iso" -> listOf(".iso", ".img"); "vztmpl" -> listOf(".tar", ".tar.gz", ".tar.xz", ".tar.zst", ".tar.bz2"); "import" -> listOf(".ova", ".qcow2", ".raw", ".vmdk"); else -> emptyList() }
    return if (suffixes.none { (if(content == "import") name else name.lowercase()).endsWith(it) }) "The file extension does not match the content type." else null
}
fun checksumError(checksum: Checksum?): String? {
    if (checksum == null) return null
    val length = mapOf("md5" to 32, "sha1" to 40, "sha224" to 56, "sha256" to 64, "sha384" to 96, "sha512" to 128)[checksum.algorithm]
    return if (length == null || checksum.hex.length != length || !checksum.hex.matches(Regex("[0-9a-fA-F]+"))) "The checksum algorithm or hex length is invalid." else null
}
fun storageRequestError(r: StorageRequest): String? {
    r.content?.let { contentFileNameError(it, r.fileName.orEmpty())?.let { return it } }
    return when(r) {
        is StorageRequest.TemplateDownload -> if (r.template.type !in listOf("lxc", "openvz")) "Choose a container template." else null
        is StorageRequest.UrlDownload -> downloadUrlError(r.url) ?: checksumError(r.checksum) ?: if (r.decompress != null && (r.content != "iso" || r.decompress !in listOf("gz", "lzo", "zst", "bz2"))) "Unsupported decompression." else null
        is StorageRequest.Upload -> checksumError(r.checksum) ?: if (r.content !in listOf("iso", "vztmpl")) "Upload accepts ISO images or templates." else if (r.source.size <= 0) "Choose a nonempty file." else null
        is StorageRequest.RemoveVolume -> if (r.volume.content !in setOf("iso", "vztmpl", "import", "snippets", "backup") || contentFromVolid(r.volume.volid) != r.volume.content || r.volume.protected) "Only unprotected file content can be removed. Disks are never removed." else null
        is StorageRequest.EditNotes -> if ((r.volume.content != "backup" || contentFromVolid(r.volume.volid) != "backup") || r.notes.length > 65536 || r.notes.any { it.isISOControl() && it != '\n' && it != '\t' }) "Use backup notes of at most 65536 characters." else null
        is StorageRequest.SetProtection -> if (r.volume.content != "backup" || contentFromVolid(r.volume.volid) != "backup") "Only backups support protection." else null
    }
}
fun expectedVolid(storage: String, kind: MediaKind, fileName: String): String {
    require(pveStorageIdOrNull(storage) != null && contentFileNameError(kind.content, fileName) == null)
    return "$storage:${kind.content}/$fileName"
}
fun storageVolumePath(key: StorageKey, volid: String): String {
    require(volid.startsWith("${key.storage}:") && cleanVolid(volid) == volid && !volid.contains("..") && volid.none { it == '\\' || it == '?' || it == '#' || it == '%' }) { "The listed volume identity is invalid." }
    return storageContentPath(key.node, key.storage) + "/" + encodePathSegment(volid)
}
internal fun storageBase(key: StorageKey) = storageContentPath(key.node, key.storage).removeSuffix("/content")
fun storageActionPath(key: StorageKey, r: StorageRequest): String = when(r) {
    is StorageRequest.TemplateDownload -> "/api2/json/nodes/${encodePathSegment(pveNodeNameOrNull(key.node) ?: error("Invalid node."))}/aplinfo"
    is StorageRequest.UrlDownload -> storageBase(key) + "/download-url"
    is StorageRequest.Upload -> storageBase(key) + "/upload"
    else -> storageVolumePath(key, r.volume!!.volid)
}
fun storageRequestBody(key: StorageKey, r: StorageRequest): ByteArray {
    require(pveStorageIdOrNull(key.storage) != null && pveNodeNameOrNull(key.node) != null) { "Invalid storage target." }
    require(storageRequestError(r) == null) { "Invalid storage request." }
    return buildJsonObject {
        when(r) {
            is StorageRequest.TemplateDownload -> { put("storage", key.storage); put("template", r.template.template) }
            is StorageRequest.UrlDownload -> { put("url", r.url); put("content", r.content); put("filename", r.fileName); put("verify-certificates", r.verifyCertificates); r.checksum?.let { put("checksum-algorithm", it.algorithm); put("checksum", it.hex) }; r.decompress?.let { put("compression", it) } }
            is StorageRequest.EditNotes -> put("notes", r.notes)
            is StorageRequest.SetProtection -> put("protected", r.protect)
            is StorageRequest.RemoveVolume -> return byteArrayOf() // mutation marker; transport never writes a DELETE body
            is StorageRequest.Upload -> error("Upload requires streaming.")
        }
    }.toString().toByteArray(Charsets.UTF_8)
}
fun storageActionGate(action: StorageAction, dump: PermissionDump?, key: StorageKey, volume: StorageVolume? = null, guest: Guest? = null): String? {
    if (dump == null) return null
    fun has(path: String, p: String) = p in effectivePrivileges(dump, path).privileges
    val s = "/storage/${key.storage}"
    val backup = has(s, "Datastore.AllocateSpace") && volume?.vmid?.let { has("/vms/$it", "VM.Backup") } == true
    val allowed = when(action) {
        StorageAction.TemplateDownload, StorageAction.Upload -> has(s, "Datastore.AllocateTemplate")
        StorageAction.UrlDownload -> has(s, "Datastore.AllocateTemplate") && (has("/nodes/${key.node}", "Sys.AccessNetwork") || (has("/", "Sys.Audit") && has("/", "Sys.Modify")))
        StorageAction.RemoveVolume -> if (volume?.content == "backup" && volume.vmid != null) backup else has(s, "Datastore.Allocate")
        StorageAction.EditNotes, StorageAction.SetProtection -> has(s, "Datastore.Allocate") || backup
    }
    return if (allowed) null else when(action) {
        StorageAction.TemplateDownload, StorageAction.Upload -> "Needs Datastore.AllocateTemplate on $s."
        StorageAction.UrlDownload -> "Needs Datastore.AllocateTemplate on $s and Sys.AccessNetwork on /nodes/${key.node} (or Sys.Audit and Sys.Modify on /)."
        StorageAction.RemoveVolume -> if (volume?.content == "backup" && volume.vmid != null) "Needs Datastore.AllocateSpace on $s and VM.Backup on /vms/${volume.vmid}." else "Needs Datastore.Allocate on $s."
        else -> "Needs Datastore.Allocate on $s, or Datastore.AllocateSpace there plus VM.Backup on the backup owner."
    }
}
fun mediaTargets(state: WorkspaceState, node: String, kind: MediaKind, preferred: String? = null): List<MediaTarget> =
    state.overview?.storage.orEmpty().filter { it.node == node && kind.content in it.content.orEmpty().split(',') && it.pluginType in setOf("dir", "nfs", "cifs", "cephfs", "btrfs") && storageActionGate(StorageAction.Upload, state.permissions.value?.dump, StorageKey(it.remote, node, it.id)) == null }
        .sortedWith(compareBy<RecordedStorage> { it.id != preferred }.thenBy { it.shared == true }.thenBy { it.id }).map { MediaTarget(StorageKey(it.remote, node, it.id), it.shared == true) }
/** Constructed only by capture; caller cannot forge or copy a confirmation. */
class StorageConfirmation internal constructor(val epoch: Long, val origin: String, val profileLabel: String, val key: StorageKey,
    val request: StorageRequest, facts: List<String>, internal val listing: List<StorageVolume>, val replaces: Boolean,
    val confirmationId: String = UUID.randomUUID().toString()) {
    val facts: List<String> = java.util.Collections.unmodifiableList(facts.toList())
    override fun toString() = "StorageConfirmation(redacted)"
}
data class StorageSubmission(val localId: String, val key: StorageKey, val action: StorageAction, val phase: PowerPhase,
    val detail: String, val simulated: Boolean = false, val checkable: Boolean = false, val upidNode: String? = null,
    val warningCount: Int? = null, val bytesSent: Long = 0, val totalBytes: Long? = null, val fieldErrors: Map<String, String> = emptyMap(), val httpStatus: Int? = null, val evidence: StorageSuccessEvidence? = null, val subject: String = "") {
    val remote: String get() = key.remote
    val node: String get() = key.node
    val storage: String get() = key.storage
    override fun toString() = "StorageSubmission(action=$action, phase=$phase)"
    init {
        require(localId.startsWith("local-") && !localId.contains("UPID:"))
        require(phase != PowerPhase.Succeeded || when(evidence) {
            StorageSuccessEvidence.AttributesReadBack -> action in setOf(StorageAction.EditNotes, StorageAction.SetProtection)
            StorageSuccessEvidence.TerminalOk -> action !in setOf(StorageAction.EditNotes, StorageAction.SetProtection)
            null -> false
        })
    }
}
enum class StorageSuccessEvidence { TerminalOk, AttributesReadBack }
internal class StorageCancelledBeforeSend : java.util.concurrent.CancellationException("Upload stopped before its body was sent.")
internal class StorageUnclear : java.io.IOException("The storage action outcome is unknown. Check the storage before trying again.")
internal class StorageRejected(val status: Int, val fields: Set<String> = emptySet()) : java.io.IOException("The server refused the storage request (HTTP $status).")
fun parseStorageUpid(raw: String, key: StorageKey, r: StorageRequest): String {
    try {
        val v = Json.parseToJsonElement(raw).jsonObject["data"] as? JsonPrimitive ?: throw StorageUnclear()
        if (!v.isString) throw StorageUnclear()
        val p = parseServerUpid(v.content) ?: throw StorageUnclear()
        val type = when(r) { is StorageRequest.Upload -> "imgcopy"; is StorageRequest.RemoveVolume -> "imgdel"; else -> "download" }
        val subject = when(r) { is StorageRequest.Upload -> ""; is StorageRequest.RemoveVolume -> (r.volume.vmid?.let { "$it@" } ?: "") + key.storage; else -> r.fileName!! }
        if (p.type != type || p.subjectId != subject || (r !is StorageRequest.Upload && p.node != key.node)) throw StorageUnclear()
        return v.content
    } catch (_: Exception) { throw StorageUnclear() }
}
internal fun storageData(raw: String): JsonElement = try { Json.parseToJsonElement(raw).jsonObject.getValue("data") } catch (_: Exception) { error("Invalid storage response.") }
fun parseStorageAttributes(raw: String): StorageAttributes {
    val d = storageData(raw) as? JsonObject ?: error("Invalid backup attributes.")
    val notes = d["notes"]?.let { require(it is JsonPrimitive && it.isString && it.content.length <= 65536); it.content }
    val protected = d["protected"]?.let { (it as? JsonPrimitive)?.let { p -> p.booleanOrNull ?: when(p.intOrNull) { 0 -> false; 1 -> true; else -> null } } ?: error("Invalid protection state.") }
    return StorageAttributes(notes, protected)
}
fun parseAplCatalogue(raw: String): List<AplTemplate> = (storageData(raw) as? JsonArray ?: error("Invalid template catalogue.")).mapNotNull { e ->
    val d = e as? JsonObject ?: return@mapNotNull null
    fun text(k: String) = (d[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= 1024 && it.none(Char::isISOControl) }
    val name = text("template") ?: return@mapNotNull null
    val type = text("type") ?: return@mapNotNull null
    if (type !in listOf("lxc", "openvz") || contentFileNameError("vztmpl", name) != null) return@mapNotNull null
    AplTemplate(name, text("package").orEmpty(), text("version").orEmpty(), type, text("os"), text("section").orEmpty(), text("headline"), (d["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= 8192 && it.none { c -> c.isISOControl() && c != '\n' && c != '\t' } }, text("architecture")?.takeIf { it.matches(Regex("[a-z0-9_]{1,16}")) })
}
fun parseUrlMetadata(raw: String): UrlMetadata {
    val d = storageData(raw) as? JsonObject ?: error("Invalid URL metadata.")
    val name = (d["filename"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.length <= 255 && it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")) }
    val size = (d["size"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
    val mime = (d["mimetype"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")) }
    return UrlMetadata(name, size, mime)
}

/** Storage plugin/content capability only; session, permission and target gates are applied separately. */
fun storageCapabilities(storage: RecordedStorage): Set<StorageAction> = buildSet {
    val types = storage.content.orEmpty().split(',').toSet()
    if (storage.pluginType in setOf("dir", "nfs", "cifs", "cephfs", "btrfs")) {
        if ("vztmpl" in types) add(StorageAction.TemplateDownload)
        if (types.any { it in setOf("iso", "vztmpl", "import") }) add(StorageAction.UrlDownload)
        if (types.any { it in setOf("iso", "vztmpl") }) add(StorageAction.Upload)
    }
    if (storage.pluginType in setOf("dir", "nfs", "cifs", "cephfs", "btrfs", "pbs")) {
        if (types.any { it in setOf("iso", "vztmpl", "import", "snippets", "backup") }) add(StorageAction.RemoveVolume)
        if ("backup" in types) { add(StorageAction.EditNotes); add(StorageAction.SetProtection) }
    }
}

/** Pure state-only submission fence. Source identity is separately checked at capture/send by the coordinator/transport. */
fun storageRefusal(c: StorageConfirmation, state: WorkspaceState): String? {
    if(c.epoch != state.connectionEpoch || (!state.demo && c.origin != state.address)) return "The connection changed. Nothing was sent."
    if((!state.connected && !state.demo) || state.kind == PDM_KIND) return "Open a direct Proxmox VE connection."
    val overview = state.overview ?: return "Wait for the inventory."
    if(state.stale || state.loading || overview.indexStale) return "The inventory is not current."
    if(overview.storage.none { it.remote == c.key.remote && it.node == c.key.node && it.id == c.key.storage }) return STORAGE_NOT_RECORDED
    if(overview.members.singleOrNull { it.remote == c.key.remote && it.name == c.key.node }?.status != "online") return STORAGE_NODE_NOT_ONLINE
    if(state.storageSelection != c.key || state.storageContent.loading || state.storageContent.stale || state.storageContent.error != null || state.storageContent.value?.key != c.key || state.storageContent.value.volumes != c.listing) return "The captured storage listing changed. Confirm again."
    if(c.request.content != null && state.storageContent.value.filter != null && state.storageContent.value.filter != c.request.content) return "Read this content type or all contents before confirming."
    if(state.storageActions.submissions.any { it.key.remote == c.key.remote && it.key.storage == c.key.storage && it.phase in setOf(PowerPhase.Accepted, PowerPhase.Unknown) }) return "An action on this storage is pending or unknown. Check it first."
    return null
}

/** Storage workers may echo signed URLs, server paths and metadata. Preserve only known terminal tokens. */
internal fun storageSafeLogText(text: String): String = when {
    text == "TASK OK" -> text
    Regex("TASK WARNINGS: [0-9]+").matches(text) -> text
    text.startsWith("TASK ERROR:") -> "TASK ERROR: Storage worker failed. Sensitive worker details are hidden."
    else -> "Storage worker output hidden because it may contain sensitive URLs, paths or metadata."
}
