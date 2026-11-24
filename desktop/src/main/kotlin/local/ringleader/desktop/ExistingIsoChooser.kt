package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import local.ringleader.core.*

private const val ALL_ISO_STORAGES = "All available ISO storages"

/** The user's scope is restricted to recorded, available ISO storage on the create node. */
internal fun isoPickerStorages(context: CreateContext, node: String): List<StorageChoice> =
    context.storages.filter { it.node == node && it.status == "available" && "iso" in it.content }.sortedBy { it.id }

internal fun currentIsoChoices(session: CreateSession, storages: List<StorageChoice>): List<Pair<String, StorageVolume>> =
    storages.flatMap { storage ->
        val section = session.volumes[storage.id to "iso"]
        if (section == null || section.loading || section.stale || section.error != null) emptyList()
        else section.value.orEmpty().filter { it.volid.startsWith("${storage.id}:iso/") }.map { storage.id to it }
    }

@Composable internal fun ExistingIsoChooser(
    state: WorkspaceState, workspace: Workspace, session: CreateSession, initialStorage: String,
    valid: Boolean, onReady: (StorageMediaReady) -> Unit,
) {
    val storages = isoPickerStorages(createContext(state, session), session.node)
    ExistingIsoChooserContent(session, storages, initialStorage, valid, { read ->
        if (read is CreateRead.Volumes) {
            workspace.refreshCreateMedia(session.epoch, session.generation, session.node, read.storage, read.content)
        }
    }, {
        val current = workspace.state.value.creation
        if (current?.epoch == session.epoch && current.generation == session.generation && current.node == session.node) {
            workspace.refresh(force = true)
        }
    }, { storage ->
        workspace.refreshCreateMedia(session.epoch, session.generation, session.node, storage, "iso")
    }, { ready ->
        workspace.state.value.creation?.takeIf { mediaReadyMatches(ready, it) }?.let { onReady(ready) }
    })
}

@Composable internal fun ExistingIsoChooserContent(
    session: CreateSession, storages: List<StorageChoice>, initialStorage: String, valid: Boolean,
    onRead: (CreateRead) -> Unit, onRefreshInventory: () -> Unit,
    onRefreshStorage: (String) -> Unit, onReady: (StorageMediaReady) -> Unit,
) {
    var scope by remember(session.epoch, session.generation, session.node) { mutableStateOf(initialStorage) }
    val effectiveScope = if (scope == ALL_ISO_STORAGES || storages.any { it.id == scope }) scope else storages.firstOrNull()?.id
    val selectedStorages = if (effectiveScope == ALL_ISO_STORAGES) storages else storages.filter { it.id == effectiveScope }
    var selected by remember(session.epoch, session.generation, session.node, effectiveScope) { mutableStateOf<String?>(null) }
    val choices = currentIsoChoices(session, selectedStorages)
    LaunchedEffect(session.epoch, session.generation, session.node, valid, selectedStorages.map { it.id }) {
        if (!valid) return@LaunchedEffect
        selectedStorages.forEach { storage ->
            if (session.volumes[storage.id to "iso"] == null) onRead(CreateRead.Volumes(storage.id, "iso"))
        }
    }
    Text("Node: ${session.node} · ISO images already on server storage", color = Tech.Ink)
    Text(
        "Local storage belongs to its node. An ISO only on another node must be copied or placed on shared storage first.",
        color = Tech.Muted,
    )
    val scopes = listOf(ALL_ISO_STORAGES) + storages.map { it.id }
    TechSelect("Storage scope", scopes, scopes.firstOrNull { it == effectiveScope }, { scope = it })
    MiniAction("Refresh ISO list", enabled = valid) {
        onRefreshInventory()
        selectedStorages.forEach { onRefreshStorage(it.id) }
    }
    if (storages.isEmpty()) Text("No available ISO storage is recorded for this node. Storage this account cannot audit is not listed.", color = Tech.Amber)
    selectedStorages.forEach { storage ->
        val section = session.volumes[storage.id to "iso"]
        when {
            section == null || section.loading -> Text("Reading ${storage.id}…", color = Tech.Muted)
            section.error != null || section.stale -> {
                Text("Could not read ${storage.id}. Retry to load its ISO images.", color = Tech.Amber)
                MiniAction("Retry ${storage.id}", enabled = valid) {
                    onRefreshStorage(storage.id)
                }
            }
            section.value.isNullOrEmpty() -> Text("No ISO images on ${storage.id}.", color = Tech.Muted)
        }
    }
    if (choices.isNotEmpty()) {
        TechSelect("Existing ISO image", choices, choices.firstOrNull { it.second.volid == selected },
            { selected = it.second.volid }, { (storage, volume) -> "${volume.volid.substringAfter(":iso/")} · $storage" })
        val chosen = choices.firstOrNull { it.second.volid == selected }
        MiniAction("Use selected ISO", primary = true, enabled = valid && chosen != null) {
            chosen?.let { (storage, volume) ->
                val ready = StorageMediaReady(session.epoch, session.generation, session.node, storage, volume.volid)
                if (mediaReadyMatches(ready, session)) onReady(ready)
            }
        }
    }
    Text(
        if (LocalAppHost.current.web) "Download a URL using the URL tab. To upload from this computer, use the desktop app."
        else "Upload from this computer or download a URL to server storage using the other tabs.",
        color = Tech.Muted,
    )
    Text("Your create form stays open.", color = Tech.Muted)
}
