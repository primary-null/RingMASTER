package local.ringleader.desktop

import androidx.compose.runtime.*
import local.ringleader.core.*

/** Only a returned volume from one explicitly selected storage can enter an existing-volume draft. */
@Composable internal fun ExistingCreateVolumePicker(
    session: CreateSession, context: CreateContext, content: String, volume: String,
    onRead: (CreateRead) -> Unit, onVolume: (String) -> Unit, error: String? = null,
) {
    val stores = context.storages.filter { it.node == session.node && content in it.content }
    val selectedStorage = volume.substringBefore(':', "")
    TechSelect("Storage", stores, stores.firstOrNull { it.id == selectedStorage },
        { onVolume("${it.id}:") }, { it.id }, error = error)
    LaunchedEffect(session.generation, selectedStorage, content) {
        if (selectedStorage.isNotBlank() && stores.any { it.id == selectedStorage } && session.volumes[selectedStorage to content] == null)
            onRead(CreateRead.Volumes(selectedStorage, content))
    }
    if (selectedStorage.isNotBlank()) {
        val section = session.volumes[selectedStorage to content] ?: ReadSection()
        ListStateView(section, "Reading volumes\u2026", "No existing volumes on this storage.",
            { onRead(CreateRead.Volumes(selectedStorage, content)) }) { rows ->
            val choices = rows.filter { it.content == content && it.volid.substringBefore(':') == selectedStorage }
            TechSelect("Existing volume", choices, choices.firstOrNull { it.volid == volume },
                { onVolume(it.volid) }, { it.volid }, error = error)
        }
    }
}
