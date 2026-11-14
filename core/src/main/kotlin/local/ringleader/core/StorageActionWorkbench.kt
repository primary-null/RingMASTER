package local.ringleader.core

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Additive seam: existing StorageGateway implementations remain source compatible. */
interface StorageActionGateway {
    suspend fun catalogue(connection: Connection, node: String): List<AplTemplate>
    suspend fun metadata(connection: Connection, node: String, url: String, verify: Boolean): UrlMetadata
    suspend fun attributes(connection: Connection, key: StorageKey, volid: String): StorageAttributes
    suspend fun submit(connection: Connection, key: StorageKey, request: StorageRequest, progress: (Long) -> Unit, cancel: UploadCancel): String?
    suspend fun status(connection: Connection, node: String, upid: String): PowerTaskStatus
}
class LiveStorageActionGateway(private val client: PveClient = PveClient()) : StorageActionGateway {
    override suspend fun catalogue(connection: Connection, node: String) = runInterruptible(Dispatchers.IO) { client.storageCatalogue(connection, node) }
    override suspend fun metadata(connection: Connection, node: String, url: String, verify: Boolean) = runInterruptible(Dispatchers.IO) { client.storageUrlMetadata(connection, node, url, verify) }
    override suspend fun attributes(connection: Connection, key: StorageKey, volid: String) = runInterruptible(Dispatchers.IO) { client.storageAttributes(connection, key, volid) }
    override suspend fun submit(connection: Connection, key: StorageKey, request: StorageRequest, progress: (Long) -> Unit, cancel: UploadCancel) = runInterruptible(Dispatchers.IO) { client.submitStorage(connection, key, request, progress, cancel) }
    override suspend fun status(connection: Connection, node: String, upid: String) = runInterruptible(Dispatchers.IO) { client.powerStatus(connection, node, upid) }
}
/** In-memory read identity. URL including query never appears in diagnostics. */
class StorageReadIdentity internal constructor(val target: StorageKey, private val url: String?, private val verify: Boolean, val volume: String?) {
    fun matchesMetadata(key: StorageKey, value: String, certificates: Boolean) = target == key && url == value && verify == certificates
    override fun toString() = "StorageReadIdentity(redacted)"
}
data class StorageReadStatus(val identity: StorageReadIdentity, val loading: Boolean = false, val complete: Boolean = false, val error: String? = null)
data class StorageActionsState(
    val submissions: List<StorageSubmission> = emptyList(), val notice: String? = null,
    val catalogues: Map<String, List<AplTemplate>> = emptyMap(), val metadata: UrlMetadata? = null,
    val attributes: StorageAttributes? = null, val attributesTarget: Pair<StorageKey, String>? = null,
    val readStates: Map<String, StorageReadStatus> = emptyMap(),
    val readLoading: Set<String> = emptySet(), val readErrors: Map<String, String> = emptyMap(),
)
/** UI dispatcher confined, with fenced progress publication from the upload IO dispatcher. */
internal class StorageActionWorkbench(
    private val scope: CoroutineScope, private val state: MutableStateFlow<WorkspaceState>, private val gateway: StorageActionGateway,
    private val connection: () -> Connection?, private val acquire: (String) -> Boolean, private val release: (String) -> Unit,
    private val expire: (Long) -> Unit, private val refresh: (StorageKey) -> Unit,
) {
    @Volatile private var generation = 0L
    private val captured = mutableMapOf<String, StorageConfirmation>()
    private val jobs = mutableMapOf<String, Job>()
    private val cancels = mutableMapOf<String, UploadCancel>()
    private val tasks = mutableMapOf<String, Pair<Long, String>>()
    private val reads = mutableMapOf<String, Long>()
    private val readJobs = mutableMapOf<String, Job>()
    private var serial = 0L
    private val demoListings = mutableMapOf<StorageKey, List<StorageVolume>>()
    fun demoVolumes(key: StorageKey): List<StorageVolume>? = demoListings[key]
    private fun update(f: (StorageActionsState) -> StorageActionsState) = state.update { it.copy(storageActions = f(it.storageActions)) }
    private fun notice(text: String) { update { it.copy(notice = text) } }
    fun cancel() {
        generation++; captured.clear(); reads.clear(); tasks.clear(); demoListings.clear()
        readJobs.values.toList().forEach { it.cancel() }; readJobs.clear()
        cancels.values.toList().forEach { it.cancel() }; cancels.clear()
        jobs.values.toList().forEach { it.cancel() }; jobs.clear()
    }
    fun selectionChanged() {
        reads.clear()
        readJobs.values.toList().forEach { it.cancel() }; readJobs.clear()
        update { it.copy(metadata = null, attributes = null, attributesTarget = null, readLoading = emptySet(), readErrors = emptyMap(), readStates = emptyMap()) }
    }
    private fun refusal(key: StorageKey, request: StorageRequest? = null, own: String? = null): String? {
        val s = state.value
        if ((!s.connected && !s.demo) || s.kind == PDM_KIND || (!s.demo && connection()?.kind != DIRECT_PVE_KIND)) return "Open a direct Proxmox VE connection."
        if (s.stale || s.loading || s.overview?.indexStale != false) return "The recorded inventory is not current."
        if (key.remote != (if(s.demo) DEMO_REMOTE_KEY else connection()?.remoteKey)) return "The connection changed."
        val row = s.overview.storage.singleOrNull { it.remote == key.remote && it.node == key.node && it.id == key.storage } ?: return STORAGE_NOT_RECORDED
        if (s.overview.members.singleOrNull { it.remote == key.remote && it.name == key.node }?.status != "online") return STORAGE_NODE_NOT_ONLINE
        if (s.storageSelection != key || s.storageContent.loading || s.storageContent.stale || s.storageContent.error != null || s.storageContent.value?.key != key) return "Read the selected storage contents first."
        if (s.storageActions.submissions.any { it.localId != own && it.key.remote == key.remote && it.key.storage == key.storage && it.phase in setOf(PowerPhase.Accepted, PowerPhase.Unknown) }) return "An action on this storage is pending or unknown. Check it first."
        val config = s.storageView.config.value?.singleOrNull { it.storage == key.storage }
        if(config?.disabled == true || config?.nodes?.let { key.node !in it } == true) return "The storage configuration excludes this target."
        if (request == null) return null
        if(request.action !in storageCapabilities(row)) return "This storage plugin does not support that file action."
        storageRequestError(request)?.let { return it }
        if(request is StorageRequest.Upload && !request.source.unchanged()) return "The file changed. Choose it again. Nothing was sent."
        if (request is StorageRequest.TemplateDownload && !s.demo && s.storageActions.catalogues[key.node]?.contains(request.template) != true) return "Read the template catalogue and choose a current entry."
        request.volume?.let { v ->
            if (s.storageContent.value.volumes.singleOrNull { it.volid == v.volid } != v) return "The listed target changed. Read it again."
            try { storageVolumePath(key, v.volid) } catch (_: Exception) { return "Invalid listed volume identity." }
        }
        request.content?.let {
            if (s.storageContent.value.filter != null && s.storageContent.value.filter != it) return "Read this content type or all contents before confirming."
            if (row.pluginType !in setOf("dir", "nfs", "cifs", "cephfs", "btrfs") || it !in row.content.orEmpty().split(',')) return "This storage does not accept that file content."
        }
        return storageActionGate(request.action, if(s.demo) null else s.permissions.value?.dump, key, request.volume)
    }
    fun capture(key: StorageKey, r: StorageRequest, label: String): StorageConfirmation? {
        refusal(key, r)?.let { notice(it); return null }
        if (label.isBlank() || label.length > 200 || label.any(Char::isISOControl)) { notice("Choose a connection label."); return null }
        val s = state.value
        val listing = s.storageContent.value!!.volumes.toList()
        val replaces = r.fileName?.let { name -> listing.any { it.volid == "${key.storage}:${r.content}/$name" } } == true
        val facts = listOf("Node: ${key.node}", "Storage: ${key.storage}", "Action: ${r.action}") + when(r) {
            is StorageRequest.UrlDownload -> listOf("Source host: ${java.net.URI(r.url).host}", "Content: ${r.content}", "Website certificate checked: ${r.verifyCertificates}", "Decompression: ${r.decompress ?: "none"}", "Checksum: ${r.checksum?.algorithm ?: "none"}")
            is StorageRequest.Upload -> listOf("Content: ${r.content}", "Bytes: ${r.source.size}", "Checksum: ${r.checksum?.algorithm ?: "none"}")
            is StorageRequest.TemplateDownload -> listOf("Source: Proxmox template catalogue, server checksum verification")
            else -> emptyList()
        } +
            listOfNotNull(r.volume?.let { "Volume: ${it.volid}" }, r.fileName?.let { "File: $it" }, if (replaces) when(r) {
                    is StorageRequest.Upload -> "This upload replaces the existing file. Its old contents cannot be recovered."
                    is StorageRequest.UrlDownload -> "The file exists. The node refuses replacement unless its checksum already matches."
                    else -> "The template exists. The node checks its catalogue checksum and keeps a matching file."
                } else null,
                if (r is StorageRequest.Upload) "Files hidden from this account may already use this name. Uploading replaces an existing file permanently." else null,
                if (r is StorageRequest.EditNotes) "New notes captured (not shown in diagnostics)." else null,
                if (r is StorageRequest.SetProtection) "Protected: ${r.protect}" else null,
                if (r is StorageRequest.RemoveVolume) "Remove ${r.volume.volid} permanently. This cannot be undone." else null)
        val c = StorageConfirmation(s.connectionEpoch, connection()?.address ?: "demo", label, key, r, facts, listing, replaces)
        captured[c.confirmationId] = c
        return c
    }
    fun submit(c: StorageConfirmation): Boolean {
        val s = state.value
        if (captured.remove(c.confirmationId) !== c) { notice("This confirmation was already used or is no longer current."); return false }
        val reason = storageRefusal(c, s) ?: refusal(c.key, c.request)
        if (c.epoch != s.connectionEpoch || c.origin != (connection()?.address ?: "demo") || reason != null || c.listing != s.storageContent.value?.volumes) { notice(reason ?: "The captured selection changed. Confirm again."); return false }
        val id = "local-${UUID.randomUUID()}"
        if (!acquire(id)) { notice("An action is already being sent. Nothing else was sent."); return false }
        val token = generation
        val conn = connection()
        val cancel = UploadCancel(); cancels[id] = cancel
        fun live() = generation == token && state.value.connectionEpoch == c.epoch
        fun publish(phase: PowerPhase, detail: String, evidence: StorageSuccessEvidence? = null, upidNode: String? = null) {
            if (!live()) return
            update { a -> a.copy(submissions = a.submissions.map { if (it.localId == id) it.copy(phase = phase, detail = detail, evidence = evidence, upidNode = upidNode ?: it.upidNode, checkable = tasks.containsKey(id)) else it }) }
        }
        update { it.copy(notice = null, submissions = listOf(StorageSubmission(id, c.key, c.request.action, PowerPhase.Accepted, "Sending storage action.", subject = c.request.volume?.volid ?: c.request.fileName.orEmpty(), totalBytes = (c.request as? StorageRequest.Upload)?.source?.size)) + it.submissions) }
        jobs[id] = scope.launch {
            var accepted = false
            try {
                if (!live() || refusal(c.key, c.request, id) != null || c.listing != state.value.storageContent.value?.volumes) {
                    publish(PowerPhase.Rejected, "Selection changed before send. Nothing was sent."); return@launch
                }
                if (s.demo) {
                    val old = state.value.storageContent.value!!
                    val recorded = state.value.overview!!.storage.single { it.remote == c.key.remote && it.node == c.key.node && it.id == c.key.storage }
                    val allRows = ((demoListings[c.key] ?: demoStorageContent(recorded)) + old.volumes).associateBy { it.volid }.values.toList()
                    val rows = when(val r = c.request) {
                        is StorageRequest.RemoveVolume -> allRows.filter { it.volid != r.volume.volid }
                        is StorageRequest.EditNotes -> allRows.map { if(it.volid == r.volume.volid) it.copy(notes = r.notes.lineSequence().first()) else it }
                        is StorageRequest.SetProtection -> allRows.map { if(it.volid == r.volume.volid) it.copy(protected = r.protect) else it }
                        else -> allRows.filter { it.volid != "${c.key.storage}:${c.request.content}/${c.request.fileName}" } + StorageVolume("${c.key.storage}:${c.request.content}/${c.request.fileName}", c.request.content, null, null, null, null, null, null, null, false, null)
                    }
                    if(live()) demoListings[c.key] = rows.toList()
                    if(live()) state.update { it.copy(storageContent = it.storageContent.copy(value = old.copy(volumes = rows.filter { old.filter == null || it.content == old.filter })), storageActions = it.storageActions.copy(submissions = it.storageActions.submissions.map { sub -> if(sub.localId == id) sub.copy(phase = PowerPhase.Simulated, detail = SIMULATED_POWER_DETAIL, simulated = true) else sub })) }
                    return@launch
                }
                val upid = gateway.submit(conn!!, c.key, c.request, { bytes ->
                    if(live()) update { a -> a.copy(submissions = a.submissions.map { if(it.localId == id) it.copy(bytesSent = bytes.coerceIn(0, it.totalBytes ?: 0)) else it }) }
                }, cancel)
                if (!live()) return@launch
                accepted = true
                release(id)
                if (upid == null) {
                    if (c.request !is StorageRequest.EditNotes && c.request !is StorageRequest.SetProtection) throw StorageUnclear()
                    val attrs = try { gateway.attributes(conn, c.key, c.request.volume!!.volid) } catch(e: SessionExpiredException) { throw e } catch (_: Exception) { throw StorageUnclear() }
                    val matches = when(val r = c.request) { is StorageRequest.EditNotes -> attrs.notes == r.notes; is StorageRequest.SetProtection -> attrs.protected == r.protect; else -> false }
                    if (!matches) throw StorageUnclear()
                    publish(PowerPhase.Succeeded, "Exact backup attributes read back.", StorageSuccessEvidence.AttributesReadBack)
                } else {
                    // Validate gateways too, including test/plugin implementations.
                    parseStorageUpid(kotlinx.serialization.json.buildJsonObject { put("data", kotlinx.serialization.json.JsonPrimitive(upid)) }.toString(), c.key, c.request)
                    val p = parseServerUpid(upid)!!
                    tasks[id] = c.epoch to upid
                    publish(PowerPhase.Accepted, "Following storage task.", upidNode = p.node)
                    var attempt = 0
                    while(live()) {
                        delay(powerFollowDelay(attempt++))
                        val result = try { gateway.status(conn, p.node, upid) } catch(e: SessionExpiredException) { throw e } catch(e: CancellationException) { throw e } catch (_: Exception) { throw StorageUnclear() }
                        if(!live()) return@launch
                        if(result.phase == PowerPhase.Accepted) continue
                        if(result.phase == PowerPhase.Succeeded && result.detail == "Stopped with exit status OK.") publish(PowerPhase.Succeeded, result.detail, StorageSuccessEvidence.TerminalOk)
                        else if(result.phase == PowerPhase.Unsuccessful) {
                            val warnings = Regex("WARNINGS: ([0-9]+)").matchEntire(result.detail)?.groupValues?.get(1)?.toIntOrNull()
                            publish(PowerPhase.Unsuccessful, if(warnings != null) "Task finished with warnings. Check its log." else "Task did not finish with OK. Check its log.")
                            if(live() && warnings != null) update { a -> a.copy(submissions = a.submissions.map { if(it.localId == id) it.copy(warningCount = warnings) else it }) }
                        }
                        else throw StorageUnclear()
                        break
                    }
                }
                if(live()) refresh(c.key)
            } catch(e: SessionExpiredException) { publish(PowerPhase.Unknown, "Session expired. Check the storage before trying again."); expire(c.epoch) }
            catch(e: StorageRejected) {
                publish(PowerPhase.Rejected, if(e.status == 403) storageActionGate(c.request.action, PermissionDump(emptyMap()), c.key, c.request.volume).orEmpty() else "Storage request refused (HTTP ${e.status}). Check fields and permissions.")
                if(live()) update { a -> a.copy(submissions = a.submissions.map { if(it.localId == id) it.copy(httpStatus = e.status, fieldErrors = e.fields.associateWith { "The server refused this field." }) else it }) }
            }
            catch(e: StorageCancelledBeforeSend) { publish(PowerPhase.Rejected, "Upload stopped before its body was sent.") }
            catch(e: AmbiguousPowerResultException) { publish(PowerPhase.Unknown, "The outcome is unknown. Check the storage before trying again.") }
            catch(e: PowerResponseUnclearException) { publish(PowerPhase.Unknown, "The outcome is unknown. Check the storage before trying again.") }
            catch(e: StorageUnclear) { publish(PowerPhase.Unknown, "The outcome is unknown. Check the storage before trying again.") }
            catch(e: CancellationException) { publish(PowerPhase.Unknown, "The action was cancelled after submission began. Check the storage."); throw e }
            catch (_: Exception) { publish(if(accepted) PowerPhase.Unknown else PowerPhase.Rejected, if(accepted) "Readback failed. Check the storage." else "The request could not be sent. Nothing was replayed.") }
            finally { cancel.detach(); if(live()) { cancels.remove(id); jobs.remove(id); release(id) } }
        }
        return true
    }
    fun cancelUpload(id: String) { cancels[id]?.cancel() }
    fun acknowledge(id: String) { update { a -> a.copy(submissions = a.submissions.map { if(it.localId == id && it.phase == PowerPhase.Unknown) it.copy(phase = PowerPhase.Unsuccessful, detail = "User checked storage. No request was replayed.") else it }) } }
    fun ownsTask(task: ClusterTask): Boolean = tasks.values.any { it.first == state.value.connectionEpoch && it.second == task.upid } && task.remote == connection()?.remoteKey
    fun task(id: String): ClusterTask? {
        val (epoch, upid) = tasks[id] ?: return null
        if(epoch != state.value.connectionEpoch) return null
        val sub = state.value.storageActions.submissions.singleOrNull { it.localId == id } ?: return null
        val p = parseServerUpid(upid) ?: return null
        return ClusterTask(sub.key.remote, upid, p.node, p.type, p.subjectId, p.user, null, null, null, TaskOutcome.Unknown)
    }
    /** Explicit reads only. Each channel's latest request wins; every publication is session fenced. */
    fun read(key: StorageKey, url: String? = null, verify: Boolean = true, volid: String? = null) {
        val channel = if(url != null) "metadata" else if(volid != null) "attributes" else "catalogue:${key.node}"
        // Invalidate even an invalid replacement before checking gates or touching the gateway.
        val token = ++serial; reads[channel] = token
        readJobs.remove(channel)?.cancel()
        val identity = StorageReadIdentity(key,url,verify,volid)
        update { it.copy(metadata = if(url != null) null else it.metadata,
            attributes = if(volid != null) null else it.attributes,
            attributesTarget = if(volid != null) null else it.attributesTarget,
            catalogues = if(url == null && volid == null) it.catalogues - key.node else it.catalogues,
            readLoading = it.readLoading - channel, readErrors = it.readErrors - channel,
            readStates = it.readStates + (channel to StorageReadStatus(identity))) }
        val reason = refusal(key)
            ?: if(url != null && (downloadUrlError(url) != null || storageActionGate(StorageAction.UrlDownload, (if(state.value.demo) null else state.value.permissions.value?.dump), key) != null)) "URL metadata request refused." else null
            ?: if(volid != null && state.value.storageContent.value?.volumes?.none { it.volid == volid && it.content == "backup" } != false) "Choose a listed backup." else null
        if(reason != null) {
            update { it.copy(readErrors = it.readErrors + (channel to reason), readStates = it.readStates + (channel to StorageReadStatus(identity,error=reason))) };return
        }
        val epoch = state.value.connectionEpoch; val gen = generation
        val demo = state.value.demo; val conn = connection()
        update { it.copy(readLoading = it.readLoading + channel, readStates = it.readStates + (channel to StorageReadStatus(identity,loading=true))) }
        readJobs[channel] = scope.launch {
            fun live() = generation == gen && state.value.connectionEpoch == epoch && reads[channel] == token && state.value.storageSelection == key
            try {
                when {
                    url != null -> { val v = if(demo) UrlMetadata("sample.iso", 1048576, "application/octet-stream") else gateway.metadata(conn!!, key.node, url, verify); if(live()) update { it.copy(metadata = v) } }
                    volid != null -> { val v = if(demo) state.value.storageContent.value!!.volumes.single { it.volid == volid }.let { StorageAttributes(it.notes, it.protected) } else gateway.attributes(conn!!, key, volid); if(live()) update { it.copy(attributes = v, attributesTarget = key to volid) } }
                    else -> { val v = if(demo) listOf(AplTemplate("sample_1_amd64.tar.zst", "sample", "1", "lxc")) else gateway.catalogue(conn!!, key.node); if(live()) update { it.copy(catalogues = it.catalogues + (key.node to v.toList())) } }
                }
            } catch(e: SessionExpiredException) { expire(epoch) } catch(e: CancellationException) { throw e } catch (_: Exception) { if(live()) update { it.copy(readErrors = it.readErrors + (channel to "Storage read failed. No action was sent.")) } }
            finally { if(live()) update { it.copy(readLoading = it.readLoading - channel,
                readStates = it.readStates + (channel to StorageReadStatus(identity, complete = channel !in it.readErrors, error = it.readErrors[channel]))) } }
        }
    }
}
