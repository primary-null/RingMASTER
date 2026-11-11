package local.ringleader.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale
import local.ringleader.core.DEMO_REMOTE_KEY
import local.ringleader.core.PDM_KIND
import local.ringleader.core.RECORDED_CAPTION
import local.ringleader.core.RECORDED_SAMPLE_CAPTION
import local.ringleader.core.RecordedStorage
import local.ringleader.core.STORAGE_CONTENT_ACTIVATES
import local.ringleader.core.STORAGE_CONTENT_DIRECT_ONLY
import local.ringleader.core.STORAGE_VISIBILITY_NOTE
import local.ringleader.core.StorageKey
import local.ringleader.core.StorageVolume
import local.ringleader.core.VOLUME_TYPE_ALL
import local.ringleader.core.VOLUME_TYPE_OTHER
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState
import local.ringleader.core.filterVolumes
import local.ringleader.core.sortVolumes
import local.ringleader.core.volumeTypeCounts

/**
 * Storage, per node: the recorded storage list on the left (from the cluster resource list, with usage bars), and on the right
 * the contents of the storage you pick (ISO images, templates, backups, disks, snippets) with type chips and sortable columns.
 * Picking a storage reads its content list once, which asks that node to connect that one storage. The storage index is never read.
 *
 * Give it the whole page: it fills its parent. Wide (900dp and up) shows the two panes side by side, narrower stacks them.
 */
@Composable fun StoragePage(state: WorkspaceState, workspace: Workspace, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= 900.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StorageListPane(state, workspace, Modifier.width(360.dp).fillMaxHeight())
                StorageContentPane(state, workspace, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StorageListPane(state, workspace, Modifier.fillMaxWidth().heightIn(max = 250.dp))
                StorageContentPane(state, workspace, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable private fun PaneFrame(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
    Column(modifier.background(Tech.Panel, Tech.Plate).border(1.dp, Tech.Edge, Tech.Plate).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)

// ---- left: the recorded storage list ----------------------------------------------------------------------------------

private fun recordedStorage(state: WorkspaceState): List<RecordedStorage> {
    val remote = if (state.demo) DEMO_REMOTE_KEY else state.address
    return state.overview?.storage.orEmpty().filter { it.remote == remote }
}

@Composable private fun StorageListPane(state: WorkspaceState, workspace: Workspace, modifier: Modifier) {
    val rows = recordedStorage(state)
    val members = state.overview?.members.orEmpty().associateBy { it.name }
    val grouped = rows.groupBy { it.node }.toSortedMap()
    PaneFrame(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Storage", suffix = if (rows.isEmpty()) null else "${rows.size} on ${grouped.size} ${if (grouped.size == 1) "node" else "nodes"}")
        }
        Text(if (state.demo) RECORDED_SAMPLE_CAPTION else RECORDED_CAPTION, fontFamily = Tech.Mono, fontSize = 10.sp, color = if (state.demo) Tech.Amber else Tech.Muted)
        if (state.stale) Text("The inventory is stale. These rows are from the last good read.", fontSize = 10.sp, color = Tech.Amber)
        state.overview?.indexError?.let { Text(it, fontSize = 10.sp, lineHeight = 13.sp, color = Tech.Amber, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        when {
            state.kind == PDM_KIND -> Text(STORAGE_CONTENT_DIRECT_ONLY, fontSize = 11.sp, lineHeight = 15.sp, color = Tech.Muted)
            state.overview == null -> if (state.loading) TechBusy() else Text("Connect to a Proxmox server to see its storage.", fontSize = 11.sp, color = Tech.Muted)
            rows.isEmpty() -> Text(
                "No recorded storage. The resource list shows only storage this identity can audit (Datastore.Audit), and not disabled storage.",
                fontSize = 11.sp, lineHeight = 15.sp, color = Tech.Muted,
            )
            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                grouped.forEach { (node, storages) ->
                    item(key = "node:$node") { NodeGroupHeader(node, members[node]?.status) }
                    items(storages.sortedBy { it.id }, key = { "${it.node}/${it.id}" }) { row ->
                        val key = StorageKey(row.remote, row.node, row.id)
                        StorageRow(row, selected = state.storageSelection == key, online = members[row.node]?.status == "online" || state.demo) {
                            workspace.selectStorage(row.node, row.id)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun NodeGroupHeader(node: String, status: String?) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(14.dp, 3.dp).background(Tech.Amber.copy(alpha = .7f)))
        Text(node.uppercase(), Modifier.identity(), fontFamily = Tech.Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (status != null && status != "online") NeutralTag(status)
    }
}

@Composable private fun StorageRow(row: RecordedStorage, selected: Boolean, online: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(if (selected) Tech.Raised else if (hovered) Tech.Raised.copy(alpha = .6f) else Tech.Panel, tween(if (LocalReduceMotion.current) 0 else 120))
    val fraction = memoryFraction(row.diskBytes, row.maxDiskBytes)
    Column(
        Modifier.fillMaxWidth().background(bg).hoverable(interaction).clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .drawBehind {
                if (selected) drawRect(Tech.Amber, size = Size(2.dp.toPx(), size.height))
                drawLine(Tech.Edge.copy(alpha = .5f), Offset(0f, size.height), Offset(size.width, size.height), 1f)
            }.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(row.id, Modifier.weight(1f, fill = false).identity(), fontFamily = Tech.Mono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = if (online) Tech.Ink else Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.pluginType?.let { NeutralTag(it) }
            if (row.shared == true) NeutralTag("Shared", help = "The same pool is listed once per node. Figures are as recorded on this node.")
            Spacer(Modifier.weight(1f))
            if (storageStatusInactive(row.status)) TechTag(row.status, storageStatusTint(row.status))
        }
        if (fraction != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UsageBar(fraction, Modifier.weight(1f))
                Text("${storageSize(row.diskBytes)} / ${storageSize(row.maxDiskBytes)}", fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Ink.copy(alpha = .85f), maxLines = 1)
            }
        } else {
            Text("No usage recorded", fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted)
        }
        row.content?.let { Text(it, fontFamily = Tech.Mono, fontSize = 9.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

// ---- right: the contents of the chosen storage ------------------------------------------------------------------------

internal fun volumeChipLabel(type: String): String = when (type) {
    VOLUME_TYPE_ALL -> "All"
    "iso" -> "ISO"
    "vztmpl" -> "Templates"
    "backup" -> "Backups"
    "images" -> "Disks"
    "rootdir" -> "Containers"
    "snippets" -> "Snippets"
    "import" -> "Import"
    VOLUME_TYPE_OTHER -> "Other"
    else -> type
}

@Composable private fun StorageContentPane(state: WorkspaceState, workspace: Workspace, modifier: Modifier) {
    val key = state.storageSelection
    val recorded = key?.let { k -> recordedStorage(state).firstOrNull { it.node == k.node && it.id == k.storage } }
    val section = state.storageContent
    val view = section.value?.takeIf { it.key == key }
    PaneFrame(modifier) {
        if (key == null || recorded == null) {
            SectionLabel("Contents")
            Text(
                if (key != null) "This storage is no longer in the inventory. Pick another one."
                else "Pick a storage on the left to read what it holds: disk images, ISOs, container templates, backups and snippets, with size, format, date and owner.",
                fontSize = 12.sp, lineHeight = 17.sp, color = Tech.Muted,
            )
            ReadNotices(section)
            Text(STORAGE_CONTENT_ACTIVATES, fontSize = 10.sp, lineHeight = 13.sp, color = Tech.Muted)
            return@PaneFrame
        }
        var type by remember(key) { mutableStateOf(VOLUME_TYPE_ALL) }
        var sort by remember(key) { mutableStateOf("Name") }
        var descending by remember(key) { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(recorded.id, Modifier.identity(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(recorded.node, recorded.pluginType, if (recorded.shared == true) "shared" else null).joinToString(" · "), Modifier.identity(),
                    fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            MiniAction("Refresh", enabled = !section.loading) { workspace.refreshStorageContent() }
        }
        Text(STORAGE_CONTENT_ACTIVATES, fontSize = 10.sp, lineHeight = 13.sp, color = Tech.Muted)
        ReadNotices(section)
        if (view == null) return@PaneFrame
        val counts = volumeTypeCounts(view.volumes)
        val activeType = counts.firstOrNull { it.first == type }?.first ?: VOLUME_TYPE_ALL
        val shown = remember(view.volumes, activeType, sort, descending) { sortVolumes(filterVolumes(view.volumes, activeType), sort, descending) }
        val showUsed = view.volumes.any { it.usedBytes != null }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            val chips = counts.map { volumeChipLabel(it.first) to it.second }
            SegmentedFilter(chips, volumeChipLabel(activeType)) { label -> type = counts.firstOrNull { volumeChipLabel(it.first) == label }?.first ?: VOLUME_TYPE_ALL }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val wide = maxWidth >= 700.dp
            val onSort: (String) -> Unit = { k -> if (sort == k) descending = !descending else { sort = k; descending = k in listOf("Size", "Date") } }
            Column(Modifier.fillMaxSize()) {
                if (wide) WideHeader(sort, descending, showUsed, onSort) else NarrowHeader(sort, descending, onSort)
                if (shown.isEmpty()) {
                    Text(if (view.volumes.isEmpty()) "The server listed no volumes for this identity." else "No volume of this type.", Modifier.padding(top = 10.dp),
                        fontSize = 12.sp, color = Tech.Muted)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(shown, key = { it.volid }) { if (wide) WideRow(it, showUsed) else NarrowRow(it) }
                    }
                }
            }
        }
        Text(
            "${NumberFormat.getIntegerInstance(Locale.ROOT).format(shown.size)} ${if (shown.size == 1) "volume" else "volumes"} · UPDATED ${readClock(section.updatedAt)}",
            fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted,
        )
        HelpTip(STORAGE_VISIBILITY_NOTE) { Text(STORAGE_VISIBILITY_NOTE, fontSize = 10.sp, lineHeight = 13.sp, color = Tech.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis) }
    }
}

private val sizeColumn = 84.dp
private val typeColumn = 78.dp
private val dateColumn = 122.dp
private val vmColumn = 50.dp

@Composable private fun WideHeader(sort: String, descending: Boolean, showUsed: Boolean, onSort: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 24.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SortHeader("NAME", "Name", sort, descending, Modifier.weight(1f), onSort = onSort)
        SortHeader("TYPE", "Type", sort, descending, Modifier.width(typeColumn), onSort = onSort)
        SortHeader("FORMAT", "Format", sort, descending, Modifier.width(typeColumn), onSort = onSort)
        SortHeader("SIZE", "Size", sort, descending, Modifier.width(sizeColumn), TextAlign.End, onSort)
        if (showUsed) Text("USED", Modifier.width(sizeColumn), fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Muted, fontFamily = Tech.Mono, textAlign = TextAlign.End)
        SortHeader("DATE", "Date", sort, descending, Modifier.width(dateColumn), onSort = onSort)
        SortHeader("VM", "VM", sort, descending, Modifier.width(vmColumn), onSort = onSort)
    }
}

@Composable private fun NarrowHeader(sort: String, descending: Boolean, onSort: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 24.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        SortHeader("NAME", "Name", sort, descending, Modifier.weight(1f), onSort = onSort)
        SortHeader("TYPE", "Type", sort, descending, onSort = onSort)
        SortHeader("SIZE", "Size", sort, descending, onSort = onSort)
        SortHeader("DATE", "Date", sort, descending, onSort = onSort)
    }
}

private fun Modifier.rowLine() = drawBehind { drawLine(Tech.Edge.copy(alpha = .5f), Offset(0f, size.height), Offset(size.width, size.height), 1f) }

@Composable private fun VolumeFlags(volume: StorageVolume) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (volume.protected) AttentionTag("Protected", help = "Protected from deletion and pruning.")
        volume.verification?.let { state ->
            when (state.lowercase()) {
                "ok" -> HealthyTag("Verified", help = "Last backup verification: ok.")
                "failed" -> ProblemTag("Verify failed", help = "Last backup verification failed.")
                else -> NeutralTag(state, help = "Last backup verification: $state.")
            }
        }
    }
}

private fun sizeText(volume: StorageVolume): String =
    if (volume.sizeBytes == null && volume.approximateSizeBytes != null) "≈ ${storageSize(volume.approximateSizeBytes)}" else storageSize(volume.shownSize)

@Composable private fun WideRow(volume: StorageVolume, showUsed: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 34.dp).rowLine().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(volume.volid, Modifier.identity(), fontFamily = Tech.Mono, fontSize = 11.5.sp, lineHeight = 15.sp, color = Tech.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val flagged = volume.protected || volume.verification != null
            if (flagged || volume.notes != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (flagged) VolumeFlags(volume)
                    volume.notes?.let { Text(it, Modifier.weight(1f, fill = false), fontSize = 10.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        Text(volume.content ?: "—", Modifier.width(typeColumn), fontFamily = Tech.Mono, fontSize = 10.5.sp, color = Tech.Muted, maxLines = 1)
        Text(volume.format ?: "—", Modifier.width(typeColumn), fontFamily = Tech.Mono, fontSize = 10.5.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sizeText(volume), Modifier.width(sizeColumn), fontFamily = Tech.Mono, fontSize = 10.5.sp, color = Tech.Ink, textAlign = TextAlign.End, maxLines = 1)
        if (showUsed) Text(storageSize(volume.usedBytes), Modifier.width(sizeColumn), fontFamily = Tech.Mono, fontSize = 10.5.sp, color = Tech.Muted, textAlign = TextAlign.End, maxLines = 1)
        Text(volumeDate(volume.createdAt), Modifier.width(dateColumn), fontFamily = Tech.Mono, fontSize = 10.5.sp, color = Tech.Muted, maxLines = 1)
        Text(volume.vmid?.toString() ?: "—", Modifier.width(vmColumn).identity(), fontFamily = Tech.Mono, fontSize = 10.5.sp, color = Tech.Muted, maxLines = 1)
    }
}

@Composable private fun NarrowRow(volume: StorageVolume) {
    Column(Modifier.fillMaxWidth().rowLine().padding(horizontal = 10.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(volume.volid, Modifier.weight(1f).identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sizeText(volume), fontFamily = Tech.Mono, fontSize = 10.5.sp, color = Tech.Ink, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(listOfNotNull(volume.content, volume.format, volume.vmid?.let { "VM $it" }, volume.createdAt?.let { volumeDate(it) }).joinToString(" · "),
                Modifier.weight(1f, fill = false).identity(), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            VolumeFlags(volume)
        }
        volume.notes?.let { Text(it, fontSize = 10.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
