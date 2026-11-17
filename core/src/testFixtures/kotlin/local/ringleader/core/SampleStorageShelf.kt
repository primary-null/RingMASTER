package local.ringleader.core

/*
 * The sample fleet's storage, as fakes: what each storage holds, kept in memory so that an action a test confirms
 * shows in the next read, and a gateway that carries those actions out on that memory. Nothing is sent anywhere.
 */

/** What the sample storages hold. A storage starts with the sample content and keeps the changes made to it. */
class SampleStorageShelf {
    private val changed = HashMap<Pair<String, String>, List<StorageVolume>>()

    fun volumes(remote: String, node: String, storage: String): List<StorageVolume> {
        val recorded = sampleOverview(remote).storage.firstOrNull { it.node == node && it.id == storage } ?: return emptyList()
        return changed[node to storage] ?: sampleStorageContent(recorded)
    }

    /** Carries [request] out on the storage [key] names: a volume goes, changes, or a new file appears. */
    fun apply(key: StorageKey, request: StorageRequest) {
        val before = volumes(key.remote, key.node, key.storage)
        changed[key.node to key.storage] = when (request) {
            is StorageRequest.RemoveVolume -> before.filter { it.volid != request.volume.volid }
            is StorageRequest.EditNotes -> before.map { if (it.volid == request.volume.volid) it.copy(notes = request.notes) else it }
            is StorageRequest.SetProtection -> before.map { if (it.volid == request.volume.volid) it.copy(protected = request.protect) else it }
            else -> {
                val volid = "${key.storage}:${request.content}/${request.fileName}"
                val arrived = StorageVolume(volid, request.content, null, null, null, null, null, null, null, false, null)
                before.filter { it.volid != volid } + arrived
            }
        }
    }
}

class SampleStorageContent(private val shelf: SampleStorageShelf = SampleStorageShelf()) : StorageContentGateway {
    override suspend fun read(connection: Connection, node: String, storage: String, content: String?): List<StorageVolume> =
        shelf.volumes(connection.remoteKey, node, storage).filter { content == null || it.content == content }
}

/**
 * Storage actions on the sample shelf. A notes or protection edit is done at once and reports no task, as on a server; a
 * download, upload or removal reports a task that has already ended well.
 */
class SampleStorageActions(private val shelf: SampleStorageShelf = SampleStorageShelf()) : StorageActionGateway {
    /** What was sent, in order, so a test can say "exactly once". */
    val sent = mutableListOf<StorageRequest>()

    override suspend fun catalogue(connection: Connection, node: String): List<AplTemplate> =
        listOf(AplTemplate("sample_1_amd64.tar.zst", "sample", "1", "lxc"))

    override suspend fun metadata(connection: Connection, node: String, url: String, verify: Boolean): UrlMetadata =
        UrlMetadata("sample.iso", 1_048_576, "application/octet-stream")

    override suspend fun attributes(connection: Connection, key: StorageKey, volid: String): StorageAttributes {
        val volume = shelf.volumes(key.remote, key.node, key.storage).single { it.volid == volid }
        return StorageAttributes(volume.notes, volume.protected)
    }

    override suspend fun submit(connection: Connection, key: StorageKey, request: StorageRequest, progress: (Long) -> Unit, cancel: UploadCancel): String? {
        sent += request
        shelf.apply(key, request)
        // The task id carries what the server would put in it: the workbench checks it against the request.
        return when (request) {
            is StorageRequest.EditNotes, is StorageRequest.SetProtection -> null
            is StorageRequest.Upload -> sampleUpid("imgcopy", "", key.node, sent.size)
            is StorageRequest.RemoveVolume -> sampleUpid("imgdel", (request.volume.vmid?.let { "$it@" } ?: "") + key.storage, key.node, sent.size)
            else -> sampleUpid("download", request.fileName.orEmpty(), key.node, sent.size)
        }
    }

    override suspend fun status(connection: Connection, node: String, upid: String): PowerTaskStatus =
        PowerTaskStatus(PowerPhase.Succeeded, "Stopped with exit status OK.")
}
