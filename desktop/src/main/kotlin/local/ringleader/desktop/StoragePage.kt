@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import local.ringleader.core.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import local.ringleader.core.SHARED_DISAGREE_NOTE
import local.ringleader.core.STORAGE_CONTENT_ACTIVATES
import local.ringleader.core.STORAGE_CONTENT_DIRECT_ONLY
import local.ringleader.core.STORAGE_VISIBILITY_HELP
import local.ringleader.core.StorageKey
import local.ringleader.core.StorageVolume
import local.ringleader.core.VOLUME_TYPE_ALL
import local.ringleader.core.VOLUME_TYPE_OTHER
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState
import local.ringleader.core.filterVolumes
import local.ringleader.core.recordedStorageCount
import local.ringleader.core.sharedStorageReport
import local.ringleader.core.sortVolumes
import local.ringleader.core.volumeTypeCounts

/**
 * Storage, per node: the recorded storage list on the left (from the cluster resource list, with usage bars), and on the right
 * the contents of the storage you pick (ISO images, templates, backups, disks, snippets) with type chips and sortable columns.
 * Picking a storage reads its content list once, which asks that node to connect that one storage. The storage index is never read.
 *
 * Give it the whole page: it fills its parent. Wide (900dp and up) shows the two panes side by side, narrower stacks them.
 */
@Composable fun StoragePage(state: WorkspaceState, workspace: Workspace, modifier: Modifier = Modifier, profileLabel: String = "Active connection") {
    val searchFocus=remember { FocusRequester() }
    LaunchedEffect(state.connectionEpoch, state.demo) {
        if (state.kind != PDM_KIND) { workspace.loadStorageConfig(); if(state.permissions.value==null && !state.permissions.loading && state.permissions.error==null) workspace.loadPermissions() }
    }
    BoxWithConstraints(modifier.fillMaxSize().onPreviewKeyEvent {
        if(it.type==KeyEventType.KeyDown && it.isCtrlPressed && it.key==Key.F && recordedStorageCount(recordedStorage(state))>=STORAGE_SEARCH_FROM) {searchFocus.requestFocus();true} else false
    }) {
        if (maxWidth >= 900.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StorageListPane(state, workspace, Modifier.width(360.dp).fillMaxHeight(),searchFocus)
                Box(Modifier.weight(1f).fillMaxHeight()) { LayoutViewport(600.dp) { StorageContentPane(state, workspace, Modifier.fillMaxSize(),profileLabel) } }
            }
        } else {
            // Each pane needs a useful viewport. A weighted contents pane could collapse entirely in a short window.
            val contentsHeight = maxOf(600.dp, maxHeight - 262.dp)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StorageListPane(state, workspace, Modifier.fillMaxWidth().height(250.dp),searchFocus)
                StorageContentPane(state, workspace, Modifier.fillMaxWidth().height(contentsHeight),profileLabel)
            }
        }
        if(state.taskLog!=null) TaskLogPanel(state,workspace,Modifier.fillMaxSize())
    }
}

@Composable private fun PaneFrame(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
    Column(modifier.background(Tech.Panel, Tech.Plate).border(1.dp, Tech.Edge, Tech.Plate).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)

// ---- left: the recorded storage list ----------------------------------------------------------------------------------

private fun recordedStorage(state: WorkspaceState): List<RecordedStorage> {
    val remote = if (state.demo) DEMO_REMOTE_KEY else state.address
    return state.overview?.storage.orEmpty().filter { it.remote == remote }
}

@Composable private fun StorageListPane(state: WorkspaceState, workspace: Workspace, modifier: Modifier, searchFocus: FocusRequester) {
    val rows = recordedStorage(state)
    val members = state.overview?.members.orEmpty().associateBy { it.name }
    val model = storageListModel(rows, state.overview?.members.orEmpty(), state.storageView.config.value)
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(StorageSort.Name) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    val listState=rememberLazyListState()
    val scope=rememberCoroutineScope()
    var cursor by remember { mutableStateOf<RecordedStorage?>(null) }
    val candidates=sortStorageRows(model.shared.map { it.shown },sort) + model.nodes.flatMap { sortStorageRows(it.rows,sort) }
    val visible=candidates.filter { storageMatches(it,query) }
    val count = model.shared.size + model.nodes.sumOf { it.rows.size }
    val keyboardNavigation=Modifier.onPreviewKeyEvent { event ->
        if(event.type!=KeyEventType.KeyDown) false
        else if(event.isCtrlPressed && event.key==Key.F && count>=STORAGE_SEARCH_FROM) { searchFocus.requestFocus(); true }
        else if(!event.isCtrlPressed && event.key in listOf(Key.DirectionDown,Key.DirectionUp,Key.MoveHome,Key.MoveEnd) && visible.isNotEmpty()) {
            val current=visible.indexOfFirst { it.node==cursor?.node && it.id==cursor?.id }
            val index=when(event.key) { Key.MoveHome -> 0; Key.MoveEnd -> visible.lastIndex; Key.DirectionUp -> if(current<0) visible.lastIndex else (current-1+visible.size)%visible.size; else -> (current+1)%visible.size }
            cursor=visible[index]
            expanded=model.nodes.map { it.node }.toSet()
            val target=cursor!!
            val keys=buildList<String> {
                if(model.shared.isNotEmpty()) add("shared-header")
                sortStorageRows(model.shared.filter { g -> g.rows.any { storageMatches(it,query) } }.map { it.shown },sort).forEach { add("${it.node}/${it.id}") }
                model.nodes.forEach { group ->
                    val filtered=sortStorageRows(group.rows.filter { storageMatches(it,query) },sort)
                    if(filtered.isNotEmpty()) {add("node:${group.node}");filtered.forEach {add("${it.node}/${it.id}")} }
                }
            }
            val item=keys.indexOf("${target.node}/${target.id}")
            if(item>=0) scope.launch {yield();listState.scrollToItem(item)}
            true
        } else if(event.key==Key.Enter && cursor!=null) { cursor?.let { workspace.selectStorage(it.node,it.id) }; true } else false
    }
    PaneFrame(modifier) {
        SectionLabel("Storage", suffix = "$count recorded")
        Text(if (state.demo) RECORDED_SAMPLE_CAPTION else RECORDED_CAPTION, fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted)
        if (state.stale || state.overview?.indexStale == true) Text("Inventory is stale. Last recorded rows are shown.", color=Tech.Amber,fontSize=10.sp)
        state.overview?.indexError?.let { Text(it,color=Tech.Amber,fontSize=10.sp) }
        when {
            state.kind == PDM_KIND -> Text(STORAGE_CONTENT_DIRECT_ONLY,color=Tech.Muted,fontSize=11.sp)
            state.overview == null -> if(state.loading) TechBusy() else Text("Connect to a Proxmox server to see its storage.",color=Tech.Muted,fontSize=11.sp)
            else -> {
                if(count>=STORAGE_SEARCH_FROM) {
                    TechField(query,{query=it},"Search storage · node · type",Modifier.focusRequester(searchFocus).identity())
                    FlowRow { StorageSort.entries.forEach { order -> MiniAction(when(order) { StorageSort.Name -> "Name"; StorageSort.UsedPercent -> "Used %"; StorageSort.Free -> "Free" },primary=sort==order) {sort=order} } }
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f).then(keyboardNavigation),state=listState,verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    if(model.shared.isNotEmpty()) item { SectionLabel("Shared") }
                    items(sortStorageRows(model.shared.filter { g -> g.rows.any { storageMatches(it,query) } }.map { it.shown },sort),key={"shared:${it.id}"}) { shown ->
                        val group=model.shared.first { it.id==shown.id }
                        val selected=state.storageSelection
                        val chosen=group.rows.firstOrNull { it.node==selected?.node } ?: shown
                        StorageCard(shown,selected?.storage==shown.id && group.rows.any { it.node==selected.node },members[shown.node]?.status=="online",group,keyboardFocused=cursor?.id==shown.id) { workspace.selectStorage(chosen.node,chosen.id) }
                    }
                    model.nodes.forEach { group ->
                        val matching=group.rows.filter { storageMatches(it,query) }
                        val sorted=sortStorageRows(matching,sort)
                        val folded=matching.size>8 && group.node !in expanded && query.isBlank()
                        if(matching.isNotEmpty()) item(key="node:${group.node}") { NodeGroupHeader(group.node,members[group.node]?.status) }
                        items(if(folded) sortStorageRows(matching,StorageSort.UsedPercent).take(8) else sorted,key={"${it.node}/${it.id}"}) { row ->
                            StorageCard(row,state.storageSelection==StorageKey(row.remote,row.node,row.id),group.online, keyboardFocused=cursor?.node==row.node && cursor?.id==row.id) { workspace.selectStorage(row.node,row.id) }
                        }
                        if(folded) item { Box(Modifier.onFocusChanged { if(it.hasFocus) cursor=null }) { MiniAction("Show all ${matching.size}") { expanded=expanded+group.node } } }
                    }
                    model.disabled.filter { query.isBlank() || it.storage.contains(query,true) }.forEach { config -> item(key="disabled:${config.storage}") {
                        Column(Modifier.fillMaxWidth().border(1.dp,Tech.Edge).padding(8.dp)) {
                            Text(config.storage,Modifier.identity(),color=Tech.Muted,fontFamily=Tech.Mono,fontSize=12.sp)
                            NeutralTag("Disabled")
                            Text("Disabled in the storage configuration · ${config.type ?: "type unknown"}",color=Tech.Muted,fontSize=10.sp)
                        }
                    } }
                    if(rows.isEmpty()) item { Text("No recorded storage this account can audit. Disabled storage comes from configuration.",color=Tech.Muted,fontSize=11.sp) }
                    item { Text("Capacities are not added across storage IDs or nodes.",color=Tech.Muted,fontSize=10.sp) }
                    if(storageListComplete(state.permissions.value?.dump)!=true || state.demo) item { Text("Storage this account cannot audit is not listed.",color=Tech.Muted,fontSize=10.sp) }
                    if(model.disabled.isNotEmpty()) item { Text("Disabled storage comes from the storage configuration.",color=Tech.Muted,fontSize=10.sp) }
                    if(state.storageView.config.error!=null) item { Text(STORAGE_CONFIG_UNREAD,color=Tech.Muted,fontSize=10.sp) }
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

@Composable private fun StorageContentPane(state: WorkspaceState, workspace: Workspace, modifier: Modifier, profileLabel: String) {
    var tab by remember(state.connectionEpoch) { mutableStateOf("Overview") }
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
            ReadNotices(section,Modifier.identity())
            Text(STORAGE_CONTENT_ACTIVATES, fontSize = 10.sp, lineHeight = 13.sp, color = Tech.Muted)
            return@PaneFrame
        }
        var guestFilter by remember(key) { mutableStateOf<Int?>(null) }
        var volumeFilter by remember(key) { mutableStateOf<String?>(null) }
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
        StorageFileActions(state,workspace,key,profileLabel=profileLabel)
        Box(Modifier.heightIn(max=160.dp).verticalScroll(rememberScrollState())) { StorageActionStrip(state,workspace,key) }
        Text(STORAGE_CONTENT_ACTIVATES, fontSize = 10.sp, lineHeight = 13.sp, color = Tech.Muted)
        val sharedRows=recordedStorage(state).filter { it.id==recorded.id && it.shared==true }
        if(sharedRows.size>1) FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            sharedRows.forEach { sample -> HelpTip("${sample.node}: ${storageSize(sample.diskBytes)} used / ${storageSize(sample.maxDiskBytes)} total",identity=true) {
                Box(Modifier.identity()) { MiniAction(sample.node,primary=key.node==sample.node) { workspace.selectStorage(sample.node,sample.id) } }
            } }
        }
        TechTabs(listOf("Overview","Contents"),tab,{tab=it},Modifier.onPreviewKeyEvent { if(it.type==KeyEventType.KeyDown && it.key in listOf(Key.DirectionLeft,Key.DirectionRight)) {tab=if(tab=="Overview") "Contents" else "Overview"; true} else false })
        ReadNotices(section,Modifier.identity())
        view?.filter?.let { Text("Listing filtered to ${volumeChipLabel(it)}.",color=Tech.Muted,fontSize=10.sp) }
        if(tab=="Overview") {
            StorageOverview(state,workspace,recorded,view?.volumes,Modifier.fillMaxWidth().weight(1f)) { content,guest,volume ->
                type=content; guestFilter=guest; volumeFilter=volume; tab="Contents"
            }
            return@PaneFrame
        }
        if (view == null) { if(section.loading) repeat(4) { Shimmer(Modifier.fillMaxWidth().height(34.dp)) }; return@PaneFrame }
        val counts = volumeTypeCounts(view.volumes)
        val activeType = counts.firstOrNull { it.first == type }?.first ?: VOLUME_TYPE_ALL
        val shown = remember(view.volumes, activeType, sort, descending,guestFilter,volumeFilter) { sortVolumes(storageFilteredVolumes(view.volumes, activeType,guestFilter,volumeFilter), sort, descending) }
        val showUsed = view.volumes.any { it.usedBytes != null }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            val chips = counts.map { volumeChipLabel(it.first) to it.second }
            SegmentedFilter(chips, volumeChipLabel(activeType)) { label -> type = counts.firstOrNull { volumeChipLabel(it.first) == label }?.first ?: VOLUME_TYPE_ALL }
        }
        if(guestFilter!=null || volumeFilter!=null) FlowRow {
            Text(listOfNotNull(guestFilter?.let { "Guest $it" },volumeFilter).joinToString(" · "),Modifier.identity(),color=Tech.Muted,fontSize=11.sp)
            MiniAction("Clear filter") { guestFilter=null; volumeFilter=null }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val wide = maxWidth >= 900.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale
            val onSort: (String) -> Unit = { k -> if (sort == k) descending = !descending else { sort = k; descending = k in listOf("Size", "Date") } }
            Column(Modifier.fillMaxSize()) {
                if (wide) WideHeader(sort, descending, showUsed, onSort) else NarrowHeader(sort, descending, onSort)
                if (shown.isEmpty()) {
                    Text(if (view.volumes.isEmpty()) if(storageVerdict(state,recorded)==StorageVisibility.Complete) STORAGE_EMPTY_COMPLETE else STORAGE_EMPTY_FILTERED else "No volume of this type.", Modifier.padding(top = 10.dp),
                        fontSize = 12.sp, color = Tech.Muted)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(shown, key = { it.volid }) { volume ->
                            Column { if (wide) WideRow(volume, showUsed) else NarrowRow(volume)
                                StorageFileActions(state,workspace,key,volume,profileLabel)
                            }
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${NumberFormat.getIntegerInstance(Locale.ROOT).format(shown.size)} ${if (shown.size == 1) "volume" else "volumes"} · UPDATED ${readClock(section.updatedAt)}",
                fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted,
            )

        }
        StorageVisibilityNote(storageVerdict(state,recorded))
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
                    volume.notes?.let { Text(it, Modifier.identity().weight(1f, fill = false), fontSize = 10.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
        volume.notes?.let { Text(it, Modifier.identity(), fontSize = 10.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
