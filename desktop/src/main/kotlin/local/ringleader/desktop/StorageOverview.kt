@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import local.ringleader.core.*

internal fun storageVerdict(state: WorkspaceState, row: RecordedStorage): StorageVisibility =
    if(state.storageContent.value?.filter!=null || state.demo || state.permissions.value?.identity==PermissionIdentity.Sample || row.content.isNullOrBlank()) StorageVisibility.Unknown else storageVisibility(state.permissions.value?.dump,row.id,
        row.content.orEmpty().split(',').map { it.trim() }.toSet(),state.overview?.guests.orEmpty().map { it.id })

internal fun storageFilteredVolumes(volumes: List<StorageVolume>, type: String, guest: Int?, volume: String?): List<StorageVolume> =
    filterVolumes(volumes,type).filter { (guest==null || it.vmid==guest) && (volume==null || it.volid==volume) }

@Composable internal fun StorageOverview(state: WorkspaceState, workspace: Workspace, row: RecordedStorage,
    volumes: List<StorageVolume>?, modifier: Modifier=Modifier, onFilter: (String,Int?,String?) -> Unit) {
    val verdict=storageVerdict(state,row)
    val caption=(if(volumes.orEmpty().any {it.shownSize==null}) "Known-size subtotals · " else "") + if(verdict==StorageVisibility.Complete) "Listed volumes" else STORAGE_PARTIAL_CAPTION
    val scroll=rememberScrollState()
    BoxWithConstraints(modifier) {
        val wide=maxWidth>=760.dp
        val veryWide=maxWidth>=1500.dp
        Column(Modifier.fillMaxSize().verticalScroll(scroll),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(wide) Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                StorageUsage(row,volumes,Modifier.weight(1f))
                StorageHistory(state,workspace,row,Modifier.weight(2f))
            } else StorageUsage(row,volumes,Modifier.fillMaxWidth())
            state.storageView.config.value?.firstOrNull { it.storage==row.id }?.let { config ->
                Text(listOfNotNull(if(config.disabled) "Disabled in configuration" else "Enabled in configuration",
                    config.nodes?.let { "Node restriction: ${it.joinToString(", ")}" },config.pruneBackups?.let { "Retention: ${keepRulesText(it)}" },config.maxProtected?.let { "Protected backup limit: $it" }).joinToString(" · "),Modifier.identity(),color=Tech.Muted,fontSize=11.sp,lineHeight=15.sp)
            }
            if(volumes!=null && !state.storageContent.denied) StorageSpace(row,volumes,caption,onFilter)
            else if(state.storageContent.loading) Column { repeat(3) { Shimmer(Modifier.fillMaxWidth().height(52.dp).padding(vertical=4.dp)) } }
            else Text(if(state.storageContent.loading) "Loading contents…" else if(state.storageContent.denied) "Breakdowns are unavailable for this account." else "Read the contents to see what uses the space.",color=Tech.Muted,fontSize=11.sp,lineHeight=15.sp)
            if(!wide) StorageHistory(state,workspace,row,Modifier.fillMaxWidth())
            if(volumes!=null && !state.storageContent.denied) {
                if(veryWide) Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    StorageGuests(state,volumes,caption,Modifier.weight(1f),onFilter)
                    StorageLargest(volumes,caption,Modifier.weight(1f),onFilter)
                    StorageBackups(volumes,caption,Modifier.weight(1f),onFilter)
                } else {
                    StorageBackups(volumes,caption,Modifier.fillMaxWidth(),onFilter)
                    StorageGuests(state,volumes,caption,Modifier.fillMaxWidth(),onFilter)
                    StorageLargest(volumes,caption,Modifier.fillMaxWidth(),onFilter)
                }
                if(volumes.isEmpty()) Text(if(verdict==StorageVisibility.Complete) STORAGE_EMPTY_COMPLETE else STORAGE_EMPTY_FILTERED,color=Tech.Muted,fontSize=11.sp,lineHeight=15.sp)
                StorageVisibilityNote(verdict)
            }
        }
    }
}

@Composable private fun StorageBlock(title: String, modifier: Modifier=Modifier, caption: String?=null, content: @Composable ColumnScope.()->Unit) {
    Column(modifier.background(Tech.Panel).border(1.dp,Tech.Edge,Tech.Plate).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        SectionLabel(title)
        caption?.let { Text(it,color=Tech.Muted,fontSize=10.sp,lineHeight=14.sp) }
        content()
    }
}

@Composable private fun StorageUsage(row: RecordedStorage, volumes: List<StorageVolume>?, modifier: Modifier) {
    StorageBlock("Usage",modifier) {
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            HelpTip("Recorded used ${row.diskBytes ?: "unknown"} bytes; total ${row.maxDiskBytes ?: "unknown"} bytes.") {
                StorageRing(usedFraction(row.diskBytes,row.maxDiskBytes),fillLevel(row),84.dp)
            }
            Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text("${storageSize(row.diskBytes)} used",color=Tech.Ink,fontFamily=Tech.Mono,fontSize=13.sp,lineHeight=17.sp)
                Text("${storageSize(row.maxDiskBytes)} total",color=Tech.Muted,fontFamily=Tech.Mono,fontSize=11.sp,lineHeight=15.sp)
                Text("${storageSize(freeTotalMinusUsed(row.diskBytes,row.maxDiskBytes))} $STORAGE_FREE_LABEL",color=Tech.Muted,fontSize=11.sp,lineHeight=15.sp)
            }
        }
        Text("Recorded sample · not live availability",color=Tech.Muted,fontSize=10.sp,lineHeight=14.sp)
        if(isThinStorage(row.pluginType,volumes)) Text("Thin by plugin or disk format. Listed disk sizes may exceed written blocks.",color=Tech.Muted,fontSize=11.sp,lineHeight=15.sp)
        if(volumes!=null) Text("Listed provisioned disks (known-size subtotal): ${storageSize(provisionedBytes(volumes))}",color=Tech.Muted,fontSize=11.sp,lineHeight=15.sp)
    }
}

private fun storageContentTint(content: String): Color = when(content) {
    "images" -> Tech.Green
    "rootdir" -> Tech.Amber
    "backup" -> if(Appearance.isLight) Color(0xff185ca6) else Color(0xff66b5ff)
    "iso" -> if(Appearance.isLight) Color(0xff7540a0) else Color(0xffca99ff)
    "vztmpl" -> if(Appearance.isLight) Color(0xff007379) else Color(0xff58c9cd)
    else -> Tech.Ink
}

@Composable private fun StorageSpace(row: RecordedStorage, volumes: List<StorageVolume>, caption: String, onFilter: (String,Int?,String?)->Unit) {
    val data=storageBreakdown(volumes,row.diskBytes,row.maxDiskBytes)
    StorageBlock("What uses the space",Modifier.fillMaxWidth(),caption) {
        Canvas(Modifier.fillMaxWidth().height(18.dp)) {
            drawRect(Tech.Edge)
            val scale=data.scale.coerceAtLeast(1).toDouble()
            var x=0f
            data.byContent.forEach { (type,bytes) ->
                val width=(bytes/scale*size.width).toFloat()
                drawRect(storageContentTint(type),Offset(x,0f),Size(width,size.height));x+=width
            }
            val other=((data.notListed ?: 0L)/scale*size.width).toFloat().coerceAtMost(size.width-x)
            if(other>0) {
                drawRect(Tech.Muted.copy(alpha=.35f),Offset(x,0f),Size(other,size.height))
                var stripe=x
                while(stripe<x+other) { drawLine(Tech.Muted,Offset(stripe,0f),Offset((stripe+5).coerceAtMost(x+other),size.height),1f);stripe+=8f }
            }
            row.maxDiskBytes?.let { total ->
                if(data.listedBytes>total) {
                    val marker=(total/scale*size.width).toFloat()
                    drawLine(Tech.Ink,Offset(marker,0f),Offset(marker,size.height),2f,pathEffect=PathEffect.dashPathEffect(floatArrayOf(3f,2f)))
                }
            }
        }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            data.byContent.forEach { (type,bytes) ->
                HelpTip("${volumeChipLabel(type)}: $bytes listed bytes · ${volumes.count { it.content==type }} volumes") {
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(storageContentTint(type)))
                        MiniAction("${volumeChipLabel(type)} ${storageSize(bytes)}") { onFilter(type,null,null) }
                    }
                }
            }
        }
        Text("Not listed / other: ${storageSize(data.notListed)} · ${storageSize(data.freeTotalMinusUsed)} free (total − used)",color=Tech.Muted,fontSize=11.sp,lineHeight=15.sp)
        if(data.listedBytes>(row.diskBytes ?: Long.MAX_VALUE)) Text("Listed sizes exceed recorded use. Sizes can be provisioned or sparse, and the recorded sample may be older than the listing.",color=Tech.Muted,fontSize=11.sp,lineHeight=15.sp)
        if(data.overProvisioned) Text("Dashed marker: recorded total. The bar spans the larger of total and listed sizes.",color=Tech.Muted,fontSize=10.sp,lineHeight=14.sp)
        if(volumes.any { it.shownSize==null || it.approximateSizeBytes!=null }) Text("Calculations omit unknown sizes and include reported approximate upper bounds.",color=Tech.Amber,fontSize=10.sp,lineHeight=14.sp)
    }
}

@Composable private fun StorageGuests(state: WorkspaceState, volumes: List<StorageVolume>, caption: String, modifier: Modifier, filter: (String,Int?,String?)->Unit) {
    val usage=guestUsage(volumes)
    if(usage.isEmpty()) return
    StorageBlock("By guest",modifier,caption) {
        usage.take(8).forEach { guest ->
            val name=state.overview?.guests?.firstOrNull { it.id==guest.vmid }?.name.orEmpty()
            StorageRank("${guest.vmid} $name",guest.bytes,usage.first().bytes,true) { filter(VOLUME_TYPE_ALL,guest.vmid,null) }
        }
        if(usage.size>8) MiniAction("${usage.size-8} more · Contents") { filter(VOLUME_TYPE_ALL,null,null) }
    }
}

@Composable private fun StorageLargest(volumes: List<StorageVolume>, caption: String, modifier: Modifier, filter: (String,Int?,String?)->Unit) {
    StorageBlock("Largest volumes",modifier,caption) {
        val largest=largestVolumes(volumes)
        largest.forEach { volume ->
            StorageRank(volume.volid + " · ${volume.format ?: "format unknown"}" + (volume.usedBytes?.let { " (${storageSize(it)} used)" } ?: ""),volume.shownSize!!,largest.first().shownSize!!,true) { filter(VOLUME_TYPE_ALL,null,volume.volid) }
        }
        if(largest.isEmpty()) Text("Nothing listed with a known size.",color=Tech.Muted,fontSize=11.sp,lineHeight=15.sp)
        MiniAction("Open Contents") { filter(VOLUME_TYPE_ALL,null,null) }
    }
}

@Composable private fun StorageBackups(volumes: List<StorageVolume>, caption: String, modifier: Modifier, filter: (String,Int?,String?)->Unit) {
    val spans=backupSpans(volumes)
    if(spans.isEmpty()) return
    val start=spans.mapNotNull { it.oldest?.epochSecond }.minOrNull() ?: 0L
    val end=maxOf(java.time.Instant.now().epochSecond,spans.mapNotNull { it.newest?.epochSecond }.maxOrNull() ?: start)
    StorageBlock("Backups by guest",modifier,caption) {
        spans.forEach { span -> BackupStrip(span,start,end) { filter("backup",span.vmid,null) } }
        Text("${volumeDate(java.time.Instant.ofEpochSecond(start))} — ${volumeDate(java.time.Instant.ofEpochSecond(end))}",color=Tech.Muted,fontSize=10.sp,lineHeight=14.sp)
        Text("Shared date axis · filename dates assume UTC when no creation time was reported.",color=Tech.Muted,fontSize=10.sp,lineHeight=14.sp)
    }
}

@Composable internal fun StorageHistory(state: WorkspaceState, workspace: Workspace, row: RecordedStorage, modifier: Modifier) {
    var timeframe by remember { mutableStateOf("hour") }
    var cf by remember { mutableStateOf("AVERAGE") }
    LaunchedEffect(row.remote,row.node,row.id,timeframe,cf) { workspace.loadStorageHistory(row.node,row.id,timeframe,cf) }
    val request=StorageHistoryRequest(StorageKey(row.remote,row.node,row.id),timeframe,cf)
    val section=state.storageView.history
    val interaction=remember(row.remote,row.node,row.id) { HistoryInteraction() }
    LaunchedEffect(timeframe,cf) { interaction.zoom=null }
    val all=state.storageView.historyFor(request).orEmpty()
    val zoom=interaction.zoom
    val points=if(zoom==null) all else all.filter {it.time in zoom}
    val lens=LocalChartLens.current
    val scope=rememberCoroutineScope()
    var exported by remember { mutableStateOf<String?>(null) }
    StorageBlock("Usage over time",modifier) {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            historyTimeframes.forEach { range -> MiniAction(range.replaceFirstChar(Char::uppercase),primary=timeframe==range) { timeframe=range } }
            HistorySegments(listOf("AVERAGE" to "Average","MAX" to "Maximum"),cf) { cf=it }
            if(interaction.zoom!=null) MiniAction("Reset zoom") {interaction.zoom=null}
            if(lens!=null) MiniAction("Open large") {lens.open(LensRequest(LensSubject.StorageSubject(row.node,row.id),LensChart("Storage usage",listOf("used","total"),null),timeframe,cf))}
            if(!LocalAppHost.current.web) MiniAction("CSV",enabled=points.isNotEmpty() && !Appearance.showcase) {
                val dialog=java.awt.FileDialog(null as java.awt.Frame?,"Export storage history",java.awt.FileDialog.SAVE)
                val target=try { dialog.file="storage-history.csv"; dialog.isVisible=true; dialog.file?.let { java.io.File(dialog.directory,it) } } finally { dialog.dispose() }
                if(target!=null) scope.launch {
                    val success=withContext(Dispatchers.IO) { runCatching { target.writeText(historyCsv(points,listOf("used","total"))) }.isSuccess }
                    exported=if(success) "Saved history CSV." else "Could not save history CSV."
                }
            }
        }
        if(Appearance.showcase) Text("CSV is unavailable in Showcase.",color=Tech.Muted,fontSize=10.sp,lineHeight=14.sp)
        exported?.let { Text(it,color=Tech.Muted,fontSize=10.sp,lineHeight=14.sp) }
        ReadNotices(section,Modifier.identity())
        HistoryChartPanel("Usage",points,listOf("used","total"),if(section.loading) "Loading history…" else "No samples in this window. Storage is recorded only while active.",120.dp,interaction)
    }
}
