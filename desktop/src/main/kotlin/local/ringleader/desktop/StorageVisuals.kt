@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import local.ringleader.core.*
import kotlin.math.roundToInt

internal fun fillTint(level: FillLevel?): Color = when(level) {
    FillLevel.High -> Tech.Amber
    FillLevel.Critical -> Tech.Red
    else -> Tech.Green
}

@Composable internal fun StorageRing(fraction: Double?, level: FillLevel?, size: Dp = 44.dp, range: ClosedFloatingPointRange<Double>? = null) {
    val value by animateFloatAsState((fraction ?: 0.0).toFloat(),tween(if(LocalReduceMotion.current) 0 else 300))
    val label = fraction?.let { "${(it*100).roundToInt()}%" } ?: "—"
    Box(Modifier.size(size).semantics { contentDescription="Recorded usage $label" },contentAlignment=Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(3.dp)) {
            drawArc(Tech.Edge,-90f,360f,false,style=Stroke(4.dp.toPx()))
            drawArc(fillTint(level),-90f,360f*value,false,style=Stroke(4.dp.toPx()))
            range?.takeIf { it.endInclusive > it.start }?.let {
                drawArc(Tech.Amber,-90f+360f*it.start.toFloat(),360f*(it.endInclusive-it.start).toFloat(),false,style=Stroke(7.dp.toPx()))
            }
        }
        Text(label,fontFamily=Tech.Mono,fontSize=10.sp,lineHeight=14.sp,color=Tech.Ink)
    }
}

@Composable internal fun StorageCard(row: RecordedStorage, selected: Boolean, online: Boolean, group: SharedStorageGroup? = null, keyboardFocused: Boolean = false, onClick: () -> Unit) {
    val interaction=remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bring=remember { BringIntoViewRequester() }
    LaunchedEffect(keyboardFocused) { if(keyboardFocused) bring.bringIntoView() }
    Row(Modifier.fillMaxWidth().bringIntoViewRequester(bring).background(if(selected) Tech.Raised else Tech.Panel)
        .border(if(selected || focused || keyboardFocused) 2.dp else 1.dp,if(selected || focused || keyboardFocused) Tech.Amber else Tech.Edge,Tech.Control)
        .clickable(interactionSource=interaction,indication=null,onClick=onClick).semantics { this.selected=selected }
        .padding(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        StorageRing(if(online) usedFraction(row.diskBytes,row.maxDiskBytes) else null,fillLevel(row),range=if(online) group?.usedRange else null)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            HelpTip(row.id,identity=true) { Text(row.id,Modifier.identity(),color=Tech.Ink,fontFamily=Tech.Mono,fontSize=12.sp,lineHeight=16.sp) }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                NeutralTag(row.pluginType ?: "Type unknown")
                if(isThinStorage(row.pluginType,null)) NeutralTag("Thin")
                if(row.shared==true) NeutralTag("Shared · ${group?.rows?.size ?: 1} nodes")
                if(!online) NeutralTag("Node offline")
                if(fillLevel(row)==FillLevel.High) AttentionTag("Nearly full")
                if(fillLevel(row)==FillLevel.Critical) ProblemTag("Nearly full")
                if(storageStatusInactive(row.status)) NeutralTag(row.status)
            }
            Text(if(row.diskBytes==null || row.maxDiskBytes==null) "No usage recorded" else
                "${storageSize(row.diskBytes)} used of ${storageSize(row.maxDiskBytes)} · ${storageSize(freeTotalMinusUsed(row.diskBytes,row.maxDiskBytes))} free (total − used)" + if(!online) " · last recorded" else "",
                color=if(online) Tech.Ink else Tech.Muted,fontFamily=Tech.Mono,fontSize=10.sp,lineHeight=14.sp)
            Text(row.content ?: "Content types unknown",color=Tech.Muted,fontSize=10.sp,lineHeight=14.sp)
            if(group!=null) {
                Text("As recorded on ${row.node}",Modifier.identity(),color=Tech.Muted,fontSize=10.sp,lineHeight=14.sp)
                HelpTip(group.rows.joinToString("\n") { "${it.node}: ${storageSize(it.diskBytes)} used (${it.diskBytes ?: "unknown"} bytes) / ${storageSize(it.maxDiskBytes)} total (${it.maxDiskBytes ?: "unknown"} bytes)" },identity=true) {
                    Text(if(group.disagree) "Differing recorded samples · per-node values" else "Per-node recorded values",color=Tech.Muted,fontSize=10.sp,lineHeight=14.sp)
                }
            }
        }
    }
}

@Composable internal fun StorageVisibilityNote(verdict: StorageVisibility) {
    if(verdict==StorageVisibility.Complete) return
    var why by remember { mutableStateOf(false) }
    Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
        if(verdict is StorageVisibility.Partial) verdict.parts.take(STORAGE_HIDDEN_LINES).forEach {
            Text(hiddenPartText(it),Modifier.identity(),color=Tech.Amber,fontSize=10.sp,lineHeight=14.sp)
        } else Text(STORAGE_VISIBILITY_UNKNOWN_LINE,color=Tech.Muted,fontSize=10.sp,lineHeight=14.sp)
        HelpTip(STORAGE_VISIBILITY_WHY + if(verdict is StorageVisibility.Partial && verdict.parts.size>3) "\nAnd ${verdict.parts.size-3} more content restrictions." else "") {
            MiniAction(if(why) "Hide why" else "Why?") { why=!why }
        }
        if(why) Text(STORAGE_VISIBILITY_WHY,color=Tech.Muted,fontSize=11.sp,lineHeight=15.sp)
    }
}

@Composable internal fun StorageRank(label: String, bytes: Long, maximum: Long, identity: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=4.dp)) {
        Text("$label · ${storageSize(bytes)}",if(identity) Modifier.identity() else Modifier,color=Tech.Ink,fontFamily=Tech.Mono,fontSize=11.sp,lineHeight=15.sp)
        UsageBar(if(maximum>0) (bytes.toDouble()/maximum).coerceIn(0.0,1.0) else 0.0,Modifier.fillMaxWidth())
    }
}

@Composable internal fun BackupStrip(span: BackupSpan, start: Long, end: Long, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=4.dp)) {
        Text("Guest ${span.vmid} · ${span.count} backups · ${storageSize(span.bytes)} · ${span.protected} protected",Modifier.identity(),color=Tech.Ink,fontFamily=Tech.Mono,fontSize=11.sp,lineHeight=15.sp)
        Canvas(Modifier.fillMaxWidth().height(22.dp).semantics { contentDescription="${span.count} backup dates on shared time axis" }) {
            drawLine(Tech.Edge,Offset(4f,size.height/2),Offset(size.width-4f,size.height/2),2f)
            span.times.forEach { time ->
                val f=((time.epochSecond-start).toDouble()/(end-start).coerceAtLeast(1)).coerceIn(0.0,1.0)
                drawCircle(Tech.Amber,3.dp.toPx(),Offset(4f+(size.width-8f)*f.toFloat(),size.height/2))
            }
        }
        Text("Oldest ${volumeDate(span.oldest)} · newest ${volumeDate(span.newest)} · ${span.newest?.let { ((end-it.epochSecond)/86400).coerceAtLeast(0) }} days since newest",color=Tech.Muted,fontSize=10.sp,lineHeight=14.sp)
    }
}
