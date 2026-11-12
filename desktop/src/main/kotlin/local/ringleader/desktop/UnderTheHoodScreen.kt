package local.ringleader.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import local.ringleader.core.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.lang.management.ManagementFactory

internal val LocalHoodProbe = staticCompositionLocalOf { false }
/** Actual backend, refreshed from this screen's own Compose window. */
internal object HoodRenderApi { var value by mutableStateOf("unknown") }

private data class HoodJvm(val used: Long, val max: Long, val threads: Int, val uptimeSeconds: Long)
private fun hoodJvm(): HoodJvm {
    val runtime=Runtime.getRuntime()
    return HoodJvm(runtime.totalMemory()-runtime.freeMemory(),runtime.maxMemory(),ManagementFactory.getThreadMXBean().threadCount,ManagementFactory.getRuntimeMXBean().uptime/1000)
}
private var hoodSplit = .65f
private val hoodTime = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable fun UnderTheHoodScreen(state: WorkspaceState) {
    if (!LocalAppHost.current.traceView) { Text("Not available in the browser"); return }
    val probe = LocalHoodProbe.current
    val window = androidx.compose.ui.awt.LocalAwtWindow.current as? androidx.compose.ui.awt.ComposeWindow
    val clipboard = LocalClipboardManager.current
    var jvm by remember { mutableStateOf(hoodJvm()) }
    var events by remember { mutableStateOf(Trace.snapshot()) }
    var vitals by remember { mutableStateOf(Trace.vitals()) }
    var metrics by remember { mutableStateOf(TraceMetrics.from(events, Instant.now())) }
    var now by remember { mutableStateOf(Instant.now()) }
    var paused by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf(emptySet<TraceSource>()) }
    var selected by remember { mutableStateOf<TraceEvent?>(events.lastOrNull()) }
    var revealId by remember { mutableStateOf<Long?>(null) }
    var split by remember { mutableStateOf(hoodSplit) }
    var exportStatus by remember { mutableStateOf("") }
    val list = rememberLazyListState(initialFirstVisibleItemIndex=(events.size-1).coerceAtLeast(0))
    var follow by remember { mutableStateOf(true) }
    var seen by remember { mutableStateOf(events.lastOrNull()?.id ?: 0) }
    val scope = rememberCoroutineScope()
    // Revision is conflated, then sampled at a frame boundary; producers never copy the ring.
    LaunchedEffect(paused) {
        if(probe) return@LaunchedEffect
        if (!paused) Trace.changes.collect { withFrameNanos { }; events=Trace.snapshot() }
    }
    LaunchedEffect(Unit) { if(probe) return@LaunchedEffect; while (isActive) { now=Instant.now(); vitals=Trace.vitals(now); metrics=TraceMetrics.from(Trace.snapshot(),now); jvm=hoodJvm(); HoodRenderApi.value=window?.renderApi?.name ?: "unknown"; delay(1000) } }
    val loop by Trace.liveLoop.collectAsState()
    LaunchedEffect(state.demo) { if(probe) return@LaunchedEffect; if(state.demo) { val job=Trace.demo(this); try { awaitCancellation() } finally { job.cancel() } } }
    LaunchedEffect(list) {
        if(probe) return@LaunchedEffect
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (scrolling,more) ->
            if(scrolling) follow=!more
        }
    }
    val filtered = remember(events, search, errors, sources) { events.filter {
        (sources.isEmpty() || it.source in sources) && (!errors || it.outcome==TraceOutcome.Error) &&
            (search.isBlank() || "${it.summary} ${it.source} ${it.detail}".contains(search,true))
    } }
    val currentSelection = selected?.let { chosen -> events.firstOrNull { it.id == chosen.id } }
    fun selectOperation(e: TraceEvent) {
        if(events.none { it.id==e.id }) events=Trace.snapshot()
        search=""; errors=false; sources=emptySet(); selected=e; follow=false
        revealId=e.id
    }
    LaunchedEffect(revealId, filtered) {
        revealId?.let { id ->
            val index=filtered.indexOfFirst { it.id==id }
            if(index>=0) { list.scrollToItem(index); revealId=null }
        }
    }
    val sourceCounts = remember(events) { events.groupingBy { it.source }.eachCount() }
    val activeIds = vitals.inFlight.map { it.spanId }.toSet()
    val pending = if(paused) 0 else events.count { it.id>seen }
    LaunchedEffect(filtered.lastOrNull()?.id,follow) {
        if(probe) return@LaunchedEffect
        if(follow && filtered.isNotEmpty()) { list.scrollToItem(filtered.lastIndex); seen=events.lastOrNull()?.id ?: seen }
    }
    // The page header already names the screen; this line only says what is being captured.
    Column(Modifier.fillMaxSize().background(Tech.Canvas).padding(top = 4.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            StatusTag(if(state.demo) "DEMO · SYNTHETIC" else "LIVE CAPTURE",healthy=true)
            Text("Every instrumented operation · redacted before capture · ${Trace.CAPACITY} operation rolling buffer",color=Tech.Muted,fontSize=11.sp,fontFamily=Tech.Mono)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val width=maxWidth.value
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(split).fillMaxHeight().background(Tech.Panel).padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    SectionLabel("STREAM",suffix="${filtered.size} OPERATIONS")
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        MiniAction(if(paused) "Resume" else "Pause") { paused=!paused }
                        MiniAction("Clear") {
                            // Operations still running keep their row, so the stream matches the in-flight count.
                            val removed=Trace.clear(); events=Trace.snapshot(); vitals=Trace.vitals()
                            selected=selected?.takeIf { chosen -> events.any { it.id==chosen.id } }
                            exportStatus=clearNotice(removed,events.size)
                        }
                        if(!LocalAppHost.current.web) MiniAction("Export…") {
                            val copy=events
                            val dialog=java.awt.FileDialog(null as java.awt.Frame?,"Export redacted trace",java.awt.FileDialog.SAVE)
                            dialog.file="ringmaster-trace.jsonl"; dialog.isVisible=true
                            if(dialog.file!=null) {
                                val target=java.io.File(dialog.directory,dialog.file)
                                scope.launch { exportStatus=withContext(Dispatchers.IO) { runCatching { target.bufferedWriter().use { out -> copy.forEach { out.appendLine(Trace.jsonLine(it)) } }; "Exported ${copy.size} operations" }.getOrElse { "Export failed" } } }
                            }
                            dialog.dispose()
                        }
                        TechCheck(errors,{ errors=it },"Errors only")
                    }
                    if(exportStatus.isNotEmpty()) DataText(exportStatus)
                    TechField(search,{search=it},"Search stream",placeholder="Source, action or detail…")
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(5.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        TraceSource.entries.forEach { source ->
                            val count=sourceCounts[source] ?: 0
                            MiniAction("${source.name} $count",primary=source in sources) {
                                sources=if(source in sources) sources-source else sources+source
                            }
                        }
                        MiniAction("All ${events.size}",primary=sources.isEmpty()) { sources=emptySet() }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        DataText("TIME",Modifier.width(94.dp)); DataText("SOURCE",Modifier.width(76.dp)); DataText("OPERATION",Modifier.weight(1f)); DataText("RESULT / MS / BYTES")
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=list,verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        items(filtered,key={it.id}) { e ->
                            val busy=e.kind=="begin" && e.spanId in activeIds
                            val ink=when(e.outcome) { TraceOutcome.Error->Tech.Red; TraceOutcome.Warn->Tech.Amber; TraceOutcome.Ok->Tech.Green; else->Tech.Muted }
                            Row(Modifier.fillMaxWidth().background(if(currentSelection?.id==e.id) Tech.Raised else Tech.Panel).clickable { selected=e }.padding(vertical=7.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                DataText(hoodTime.format(e.at),Modifier.width(90.dp))
                                TechTag(e.source.name,Tech.Muted,Modifier.width(72.dp))
                                Text(e.summary,Modifier.weight(1f).identity(),color=Tech.Ink,fontFamily=Tech.Mono,fontSize=11.sp,lineHeight=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                                if(busy) TechBusy()
                                Text(if(busy) "${java.time.Duration.between(e.at,now).seconds.coerceAtLeast(0)}s" else listOfNotNull(e.status?.toString() ?: e.outcome.name, e.durationMs?.takeIf { e.spanId != null }?.let { "${it}ms" }, (e.bytesIn ?: 0L).plus(e.bytesOut ?: 0L).takeIf { it > 0 }?.let { traceSize(it) }).joinToString("  "),color=ink,fontFamily=Tech.Mono,fontSize=10.sp,lineHeight=14.sp)
                            }
                        }
                    }
                    if(!follow) MiniAction("$pending new · Jump to live",primary=true) { follow=true; scope.launch { if(filtered.isNotEmpty()) list.scrollToItem(filtered.lastIndex) }; seen=events.lastOrNull()?.id ?: seen }
                    if(paused) DataText("VIEW PAUSED · capture continues")
                }
                Box(Modifier.width(10.dp).fillMaxHeight().pointerInput(width) {
                    detectHorizontalDragGestures { change, amount -> change.consume(); split=(split+amount/width).coerceIn(.40f,.76f); hoodSplit=split }
                }.padding(horizontal=4.dp).background(Tech.Edge))
                Column(Modifier.weight(1f-split).fillMaxHeight().background(Tech.Panel).padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    SectionLabel("ENGINE",suffix="1 HZ")
                    Column(Modifier.weight(3.2f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Vital("REQUESTS / MIN",vitals.requestsPerMinute.toString(),vitals.requests,Modifier.weight(1f))
                            Vital("ERRORS / MIN",vitals.errorsPerMinute.toString(),vitals.errors,Modifier.weight(1f))
                        }
                        DataText("${vitals.inFlight.size} IN FLIGHT · ${vitals.sessions.size} OPEN SESSIONS")
                        // Refresh loop and runtime facts sit near the top: they are one line each and the countdown is the point.
                        DataText("REFRESH  ${loop.state.uppercase()} · ${loop.intervalMs/1000}s" + (if (loop.backoffSeconds > 0) " · backoff ${loop.backoffSeconds}s" else "") +
                            (loop.nextTickAt?.let { " · next in ${java.time.Duration.between(now,it).seconds.coerceAtLeast(0)}s" } ?: ""))
                        DataText("HEAP ${traceSize(jvm.used)} / ${traceSize(jvm.max)} · THREADS ${jvm.threads} · UPTIME ${jvm.uptimeSeconds}s · RENDER ${HoodRenderApi.value}")
                        vitals.sessions.forEach { session ->
                            DataText("${session.event.summary} · ${java.time.Duration.between(session.event.at,now).seconds.coerceAtLeast(0)}s\nIN ${traceSize(session.bytesIn)}  OUT ${traceSize(session.bytesOut)}  ${session.framesPerSecond} fps",Modifier.identity())
                        }
                        SectionLabel("LATENCY",suffix="60 S / MS")
                        Row(horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                            DataText("P50  ${metrics.p50 ?: "?"} ms")
                            DataText("P95  ${metrics.p95 ?: "?"} ms")
                        }
                        LatencyHistogram(metrics.histogram)
                        SectionLabel("REQUESTS BY SOURCE",suffix="60 S")
                        if(metrics.sources.isEmpty()) DataText("No completed requests in this window")
                        metrics.sources.forEach { (source,count) ->
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                DataText(source.name,Modifier.width(65.dp))
                                Box(Modifier.weight(1f).height(12.dp).background(Tech.Raised)) {
                                    Box(Modifier.fillMaxWidth(count.toFloat()/(metrics.sources.firstOrNull()?.second ?: 1)).fillMaxHeight().background(Tech.Amber))
                                }
                                DataText(count.toString(),Modifier.width(36.dp))
                            }
                        }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                SectionLabel("SLOWEST 5",suffix="5 MIN")
                                if(metrics.slowest.isEmpty()) DataText("No completed operations")
                                metrics.slowest.forEach { operation -> EngineOperation(operation, "${operation.durationMs} ms") { selectOperation(operation) } }
                            }
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                SectionLabel("ERRORS",suffix="5 MIN / ${metrics.errors.size}")
                                if(metrics.errors.isEmpty()) DataText("No errors")
                                if(metrics.errors.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max=125.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                    items(metrics.errors,key={it.id}) { operation -> EngineOperation(operation, "${operation.status ?: "ERR"}") { selectOperation(operation) } }
                                }
                            }
                        }
                    }
                    HorizontalDividerLine()
                    SectionLabel("INSPECTOR")
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        currentSelection?.let { e ->
                            Text(e.summary,Modifier.identity(),color=Tech.Ink,fontFamily=Tech.Mono,fontSize=13.sp)
                            DataText("${e.at}\n${e.source} · ${e.kind} · ${e.outcome} · ${e.status ?: "—"}\nDuration ${e.durationMs ?: "—"} ms\nIn ${e.bytesIn ?: "—"} B · Out ${e.bytesOut ?: "—"} B\nSpan ${e.spanId ?: "—"} · ${e.correlation ?: "—"}",Modifier.identity())
                            SectionLabel("TIMELINE")
                            e.timeline.forEach { moment -> DataText("${hoodTime.format(moment.at)}  ${moment.kind.uppercase()}  ${moment.status ?: moment.outcome.name}") }
                            e.detail.forEach { (key,value) -> DataText("$key  $value",Modifier.identity()) }
                            MiniAction("Copy redacted event") { clipboard.setText(AnnotatedString(Trace.jsonLine(e))) }
                            SectionLabel("RELATED")
                            events.filter { it.id!=e.id && (e.spanId!=null && it.spanId==e.spanId || e.correlation!=null && it.correlation==e.correlation) }.takeLast(30).forEach { related ->
                                DataText("${hoodTime.format(related.at)} ${related.kind} · ${related.summary}",Modifier.identity().clickable { selected=related })
                            }
                        } ?: DataText("Select an event to inspect timing, metadata and related operations.")
                    }
                }
            }
        }
    }
}
@Composable private fun DataText(text: String, modifier: Modifier=Modifier) { Text(text,modifier,color=Tech.Muted,fontFamily=Tech.Mono,fontSize=11.sp,lineHeight=15.sp) }
@Composable private fun HorizontalDividerLine() { androidx.compose.material3.HorizontalDivider(color=Tech.Edge) }
@Composable private fun Vital(label: String,value: String,history: List<Int>,modifier: Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(5.dp)) {
        DataText(label); Text(value,color=Tech.Amber,fontFamily=Tech.Mono,fontSize=26.sp)
        Canvas(Modifier.fillMaxWidth().height(20.dp)) {
            val max=(history.maxOrNull() ?: 1).coerceAtLeast(1)
            history.zipWithNext().forEachIndexed { i,(a,b) -> drawLine(Tech.Amber,Offset(size.width*i/59,size.height-size.height*a/max),Offset(size.width*(i+1)/59,size.height-size.height*b/max),1.5f) }
        }
    }
}
private fun traceSize(bytes: Long): String = when { bytes>=1048576 -> "${bytes/1048576} MB"; bytes>=1024->"${bytes/1024} KB"; else->"$bytes B" }

@Composable private fun EngineOperation(e: TraceEvent, result: String, select: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Tech.Raised).clickable(onClick=select).padding(3.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(e.summary,Modifier.weight(1f).identity(),color=Tech.Ink,fontFamily=Tech.Mono,fontSize=10.sp,lineHeight=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        Text(result,color=if(e.outcome==TraceOutcome.Error) Tech.Red else Tech.Amber,fontFamily=Tech.Mono,fontSize=10.sp,lineHeight=13.sp)
    }
}
@Composable private fun LatencyHistogram(buckets: List<Int>) {
    Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
        Canvas(Modifier.fillMaxWidth().height(24.dp)) {
            val peak=(buckets.maxOrNull() ?: 1).coerceAtLeast(1)
            buckets.forEachIndexed { i,count ->
                val width=size.width/6
                val height=size.height*count/peak
                drawRect(Tech.Raised,Offset(i*width,0f),androidx.compose.ui.geometry.Size(width-4,size.height))
                drawRect(Tech.Amber,Offset(i*width,size.height-height),androidx.compose.ui.geometry.Size(width-4,height))
            }
        }
        Row(Modifier.fillMaxWidth()) { TraceMetrics.bucketLabels.forEachIndexed { i,label -> DataText("$label (${buckets[i]})",Modifier.weight(1f)) } }
    }
}
/** What Clear did: completed rows are removed, rows of operations still running stay. */
internal fun clearNotice(removed: Int, kept: Int): String =
    "Cleared ${removed} ${if (removed == 1) "operation" else "operations"}." +
        if (kept > 0) " Kept ${kept} still running." else ""
