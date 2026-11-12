@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.ringleader.core.BT_ADAPTER_OFF
import local.ringleader.core.BT_SCAN_ENDED_EARLY
import local.ringleader.core.BT_SCAN_SECONDS_DEFAULT
import local.ringleader.core.BluetoothScanner
import local.ringleader.core.BtAdapter
import local.ringleader.core.BtDevice
import local.ringleader.core.BtKind
import local.ringleader.core.BtScan
import local.ringleader.core.btDeviceOrder

private val btScanTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)
private const val ReadyCopy = "No scan yet. Scan to listen for nearby devices."
private const val ListeningCopy = "Listening… devices appear as they are heard."
private const val UnavailableCopy = "Bluetooth scanning is not available on this platform yet."

private data class BtCols(val address: Dp, val kind: Dp, val signal: Dp, val trace: Dp, val heard: Dp, val state: Dp, val gap: Dp = 8.dp) {
    val glyph = 22.dp
}

/** Columns for the table's own width. Narrow tables drop the trace, then the address, before the name gets squeezed. */
private fun btCols(width: Dp) = when {
    width >= 1100.dp -> BtCols(170.dp, 72.dp, 100.dp, 72.dp, 56.dp, 180.dp)
    width >= 860.dp -> BtCols(140.dp, 64.dp, 92.dp, 64.dp, 52.dp, 176.dp, gap = 6.dp)
    width >= 700.dp -> BtCols(132.dp, 60.dp, 88.dp, 0.dp, 48.dp, 172.dp, gap = 6.dp)
    else -> BtCols(0.dp, 56.dp, 84.dp, 0.dp, 46.dp, 168.dp, gap = 6.dp)
}

/**
 * This computer's Bluetooth page. [scanner] is null where no backend exists.
 * One scan runs at a time on [Dispatchers.IO]. Stop, leaving the page, or closing the app kills the helper
 * or bluetoothctl process tree. The page lists what a scan hears; it never pairs or connects.
 */
@Composable fun BluetoothScreen(scanner: BluetoothScanner?) {
    val scope = rememberCoroutineScope()
    var seconds by remember { mutableIntStateOf(BT_SCAN_SECONDS_DEFAULT) }
    var scanning by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf<BtScan?>(null) }
    var receivedAt by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(scanner) {
        if (scanner == null) return@DisposableEffect onDispose {}
        val hook = Thread({ scanner.cancel() }, "bt-scan-shutdown")
        runCatching { Runtime.getRuntime().addShutdownHook(hook) }
        onDispose {
            scanner.cancel()
            runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        }
    }
    fun start() {
        val backend = scanner ?: return
        if (scanning) return
        val chosen = seconds
        scanning = true; error = null; scan = null; receivedAt = System.nanoTime()
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    backend.scan(chosen) { partial -> if (!partial.finished) scope.launch { if (scanning) { scan = partial; receivedAt = System.nanoTime() } } }
                }
                if (isActive) { scan = result; receivedAt = System.nanoTime() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) error = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            } finally {
                scanning = false
            }
        }
    }
    fun stop() { scanner?.cancel() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
        // One fixed-height toolbar: the status lives here, so nothing is inserted above the list while a scan runs.
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniAction(if (scanning) "Stop" else "Scan", primary = scanner != null, enabled = scanner != null, onClick = { if (scanning) stop() else start() })
            listOf(5, 10, 20).forEach { n ->
                MiniAction("${n}s", primary = scanner != null && seconds == n, enabled = scanner != null && !scanning) { seconds = n }
            }
            Spacer(Modifier.width(8.dp))
            Text(error ?: scanStatus(scan, scanning), Modifier.weight(1f), color = if (error != null || scanning) Tech.Amber else Tech.Muted,
                fontFamily = Tech.Mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (scanner == null) Text(UnavailableCopy, color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 12.sp)
        else BtScanView(scan, scanning, Modifier.weight(1f).fillMaxWidth(), scanner.platform, receivedAt)
    }
}

private fun scanStatus(scan: BtScan?, scanning: Boolean): String {
    val heard = scan?.devices?.count { it.rssi != null } ?: 0
    val readings = scan?.devices?.sumOf { it.samples.size } ?: 0
    return when {
        scanning -> "LISTENING · $heard HEARD · $readings READINGS"
        scan == null -> ""
        scan.note == BT_SCAN_ENDED_EARLY -> "$heard HEARD" + (if (readings > 0) " · $readings READINGS" else "") +
            " · ${scan.elapsedMs?.let { it / 1000 } ?: 0} S · ENDED EARLY"
        else -> "$heard HEARD" + (if (readings > 0) " · $readings READINGS" else "") + (scan.windowMs?.let { " · ${it / 1000} S" } ?: "")
    }
}

/**
 * One [BtScan], live or finished, from this computer or from inside a guest. Read-only: chips describe the report.
 * [receivedAt] is the [System.nanoTime] when [scan] arrived; live views animate from it between updates.
 */
@Composable fun BtScanView(scan: BtScan?, scanning: Boolean, modifier: Modifier = Modifier, platform: String? = null, receivedAt: Long = 0L) {
    val devices = scan?.devices.orEmpty()
    val note = scan?.note?.takeIf { it.isNotBlank() }
    var focus by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    val clock = rememberScanClock(scan, receivedAt, scanning)
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
        Text(if (scan == null) " " else listOfNotNull(scan.source.label, platform?.takeIf { it.isNotBlank() }, btScanTime.format(scan.at)).joinToString("  ·  ").uppercase(),
            modifier = Modifier.fillMaxWidth().identity(),
            fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (note != null && devices.isEmpty()) { NoteBanner(note, Modifier.widthIn(max = 720.dp).fillMaxWidth(), platform); return@Column }
        if (note != null) NoteBanner(note, Modifier.fillMaxWidth(), platform)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val tall = maxHeight >= 560.dp
            val field: @Composable (Modifier) -> Unit = { m -> SignalField(devices, scan, clock, scanning, focus, selected, { focus = it }, { selected = if (selected == it) null else it }, m) }
            val timeline: @Composable (Modifier) -> Unit = { m -> HeardTimeline(devices, scan, clock, focus ?: selected, m) }
            val table: @Composable (Modifier) -> Unit = { m -> DeviceBrowser(devices, scanning, scan, clock, focus, selected, { focus = it }, { selected = if (selected == it) null else it }, m) }
            if (maxWidth >= 1100.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(380.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldPlate { field(Modifier.fillMaxWidth().aspectRatio(1f)) }
                    if (scan == null || scan.windowMs != null) FieldPlate { timeline(Modifier.fillMaxWidth().height(118.dp)) }
                    scan?.adapters?.takeIf { it.isNotEmpty() }?.let { AdapterLine(it) }
                    (selected ?: focus)?.let { a -> devices.firstOrNull { it.address == a } }?.let { DeviceDetail(it, scan) }
                }
                table(Modifier.weight(1f).fillMaxHeight())
            } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().height(if (tall) 220.dp else 180.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldPlate(Modifier.fillMaxHeight().aspectRatio(1f)) { field(Modifier.fillMaxSize()) }
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (scan == null || scan.windowMs != null) FieldPlate(Modifier.weight(1f).fillMaxWidth()) { timeline(Modifier.fillMaxSize()) }
                        else (selected ?: focus)?.let { a -> devices.firstOrNull { it.address == a } }?.let { DeviceDetail(it, scan) }
                        scan?.adapters?.takeIf { it.isNotEmpty() }?.let { AdapterLine(it) }
                    }
                }
                table(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable private fun FieldPlate(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.border(1.dp, Tech.Edge, Tech.Plate).background(Tech.Black.copy(alpha = .35f), Tech.Plate).padding(8.dp)) { content() }
}

/**
 * Milliseconds into the scan. While a live scan streams, it runs on with the frame clock between updates so the field
 * and timeline move continuously; otherwise it is the last reported position. Read it only inside draw blocks.
 */
@Composable private fun rememberScanClock(scan: BtScan?, receivedAt: Long, live: Boolean): MutableLongState {
    val clock = remember { mutableLongStateOf(scan?.elapsedMs ?: scan?.windowMs ?: 0L) }
    val reduce = LocalReduceMotion.current
    val base = rememberUpdatedState(scan?.elapsedMs ?: scan?.windowMs ?: 0L)
    val window = rememberUpdatedState(scan?.windowMs ?: Long.MAX_VALUE)
    val received = rememberUpdatedState(receivedAt)
    val running = rememberUpdatedState(live && !reduce && scan != null)
    LaunchedEffect(Unit) {
        while (isActive) {
            if (!running.value) {
                clock.longValue = base.value
                kotlinx.coroutines.delay(100)
            } else withFrameNanos { frame ->
                clock.longValue = (base.value + (frame - received.value).coerceAtLeast(0L) / 1_000_000L).coerceAtMost(window.value)
            }
        }
    }
    return clock
}

/** Smoothed signal stored when the sample arrived, or the raw reading for a one-shot result. */
private fun BtDevice.fieldRssi(): Float? = smoothedRssi ?: rssi?.takeIf { it < 0 }?.toFloat()

/** Radius share for a signal: about -35 dBm sits near the centre, -100 dBm on the edge. */
private fun fieldRadius(rssi: Float) = ((-rssi - 33f) / 67f).coerceIn(.1f, 1f)

/** A stable bearing per address. Direction is not measured; the angle only keeps dots apart and in the same place. */
private fun fieldAngle(address: String): Double = ((address.hashCode().toLong() and 0xffffffffL) * 0.6180339887 % 1.0) * 2 * PI

private fun deviceTint(d: BtDevice) = when {
    d.connected == true -> Tech.Green
    d.paired == true -> Tech.Amber
    d.name.isNullOrBlank() -> Tech.Muted
    else -> Tech.Ink
}

/**
 * The listening field. The centre is this computer's adapter. Each heard device is a dot whose distance follows its
 * smoothed signal (rings at -50, -65, -80, -95 dBm); its bearing is arbitrary but stable. A ripple marks the moment a
 * device is first heard, a flash marks each new reading, dots fade when a device goes quiet, and the outer arc is the
 * real listening time.
 */
@Composable private fun SignalField(devices: List<BtDevice>, scan: BtScan?, clock: MutableLongState, live: Boolean, focus: String?, selected: String?,
    onFocus: (String?) -> Unit, onSelect: (String) -> Unit, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val plotted = devices.filter { it.rssi != null }
    val label = TextStyle(fontFamily = Tech.Mono, fontSize = 9.sp, color = Tech.Muted)
    val strong = TextStyle(fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Ink, fontWeight = FontWeight.Bold)
    val showcase = Appearance.showcase
    fun position(d: BtDevice, w: Float, h: Float): Offset? {
        val r = d.fieldRssi() ?: return null
        val radius = minOf(w, h) / 2f - 14f
        val a = fieldAngle(d.address)
        return Offset(w / 2f + (cos(a) * fieldRadius(r) * radius).toFloat(), h / 2f + (sin(a) * fieldRadius(r) * radius).toFloat())
    }
    Canvas(modifier.pointerInput(plotted) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                val p = e.changes.firstOrNull()?.position ?: continue
                val hit = plotted.mapNotNull { d -> position(d, size.width.toFloat(), size.height.toFloat())?.let { d to hypot(it.x - p.x, it.y - p.y) } }
                    .filter { it.second < 12.dp.toPx() }.minByOrNull { it.second }?.first?.address
                when (e.type) {
                    PointerEventType.Exit -> onFocus(null)
                    PointerEventType.Press -> { onFocus(hit); hit?.let(onSelect) }
                    else -> onFocus(hit)
                }
            }
        }
    }) {
        val now = clock.longValue
        val c = center
        val radius = size.minDimension / 2f - 14f
        val thin = Stroke(1.dp.toPx())
        // Rings and their dBm marks.
        listOf(-50, -65, -80, -95).forEach { dbm ->
            val r = fieldRadius(dbm.toFloat()) * radius
            drawCircle(Tech.Edge.copy(alpha = if (dbm == -95) .9f else .55f), r, c, style = Stroke(1.dp.toPx(), pathEffect = if (dbm == -95) null else PathEffect.dashPathEffect(floatArrayOf(3f, 5f))))
            val text = measurer.measure("$dbm", label)
            drawText(text, topLeft = Offset(c.x + r - text.size.width - 2f, c.y + 2f))
        }
        drawLine(Tech.Edge.copy(alpha = .3f), Offset(c.x - radius, c.y), Offset(c.x + radius, c.y), 1f)
        drawLine(Tech.Edge.copy(alpha = .3f), Offset(c.x, c.y - radius), Offset(c.x, c.y + radius), 1f)
        // Listening time: the arc is how much of the window has passed.
        val window = scan?.windowMs
        if (window != null && window > 0) {
            val frac = (now.toFloat() / window).coerceIn(0f, 1f)
            val outer = radius + 7f
            val tl = Offset(c.x - outer, c.y - outer); val sz = Size(outer * 2, outer * 2)
            drawArc(Tech.Edge.copy(alpha = .5f), -90f, 360f, false, tl, sz, style = thin)
            drawArc(if (live) Tech.Amber else Tech.Amber.copy(alpha = .45f), -90f, 360f * frac, false, tl, sz, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            if (live) {
                val a = (-90f + 360f * frac) * PI / 180.0
                drawCircle(Tech.Amber, 3.dp.toPx(), Offset(c.x + (cos(a) * outer).toFloat(), c.y + (sin(a) * outer).toFloat()))
            }
        }
        // Devices: quiet ones first so active ones draw on top.
        val ordered = plotted.sortedBy { it.lastSeenMs ?: 0L }
        ordered.forEach { d ->
            val p = position(d, size.width, size.height) ?: return@forEach
            val tint = deviceTint(d)
            val quiet = d.lastSeenMs?.let { (now - it).coerceAtLeast(0L) } ?: 0L
            val alpha = if (d.lastSeenMs == null) .8f else (1f - (quiet - 2500f).coerceAtLeast(0f) / 9000f).coerceIn(.28f, 1f)
            val first = d.firstSeenMs?.let { now - it }
            if (live && first != null && first in 0..1400) {
                val t = first / 1400f
                drawCircle(tint.copy(alpha = (1f - t) * .8f), 4.dp.toPx() + t * 22.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
            }
            val reading = d.lastSeenMs?.let { now - it }
            if (live && reading != null && reading in 0..450) drawCircle(tint.copy(alpha = (1f - reading / 450f) * .35f), 9.dp.toPx(), p)
            val r = if (d.connected == true || d.paired == true) 4.dp.toPx() else 3.dp.toPx()
            drawCircle(tint.copy(alpha = alpha), r, p)
            if (d.address == focus || d.address == selected) {
                drawCircle(Tech.Amber, r + 4.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
                val name = if (showcase) null else d.name?.takeIf { it.isNotBlank() }
                val text = measurer.measure(listOfNotNull(name, d.fieldRssi()?.let { "${it.toInt()} dBm" }).joinToString("  "), strong)
                val x = (p.x + 10f).coerceAtMost(size.width - text.size.width - 2f).coerceAtLeast(2f)
                val y = (p.y - text.size.height - 6f).coerceAtLeast(2f)
                drawRoundRect(Tech.Panel.copy(alpha = .92f), Offset(x - 4f, y - 2f), Size(text.size.width + 8f, text.size.height + 4f), CornerRadius(3f))
                drawText(text, topLeft = Offset(x, y))
            }
        }
        // This computer.
        drawCircle(Tech.Amber.copy(alpha = .25f), 7.dp.toPx(), c)
        drawCircle(Tech.Amber, 3.dp.toPx(), c)
        if (plotted.isEmpty()) {
            val text = measurer.measure(if (live) "LISTENING" else if (scan == null) "READY" else "NOTHING HEARD", label)
            drawText(text, topLeft = Offset(c.x - text.size.width / 2f, c.y + 12.dp.toPx()))
        }
    }
}

/**
 * Discovery over the listening window: the stepped line is how many devices had been heard by each moment, the ticks
 * along the bottom are individual readings, and the focused device's signal is traced in green.
 */
@Composable private fun HeardTimeline(devices: List<BtDevice>, scan: BtScan?, clock: MutableLongState, focus: String?, modifier: Modifier) {
    val firsts = devices.mapNotNull { it.firstSeenMs }.sorted()
    val readings = devices.flatMap { d -> d.samples.map { it.ms to deviceTint(d) } }
    val focused = focus?.let { a -> devices.firstOrNull { it.address == a } }
    val window = scan?.windowMs
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("HEARD ${firsts.size}", fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Amber, maxLines = 1)
            Text(if (focused != null && focused.samples.isNotEmpty()) "${focused.samples.size} READINGS · THIS DEVICE" else "${readings.size} READINGS",
                fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = if (focused != null) Tech.Green else Tech.Muted, maxLines = 1)
        }
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            val w = size.width; val h = size.height
            val band = 8.dp.toPx()
            val plotH = h - band - 3.dp.toPx()
            drawLine(Tech.Edge, Offset(0f, plotH), Offset(w, plotH), 1f)
            if (window == null || window <= 0) {
                return@Canvas
            }
            val now = clock.longValue.coerceAtMost(window)
            fun x(ms: Long) = (ms.toFloat() / window).coerceIn(0f, 1f) * w
            val total = maxOf(devices.size, firsts.size, 1)
            // Cumulative heard, stepped.
            val path = Path().apply { moveTo(0f, plotH) }
            var count = 0
            firsts.filter { it <= now }.forEach { ms -> val y0 = plotH - plotH * count / total; count++; path.lineTo(x(ms), y0); path.lineTo(x(ms), plotH - plotH * count / total) }
            path.lineTo(x(now), plotH - plotH * count / total)
            val area = Path().apply { addPath(path); lineTo(x(now), plotH); close() }
            drawPath(area, Tech.Amber.copy(alpha = .12f))
            drawPath(path, Tech.Amber, style = Stroke(1.5.dp.toPx()))
            // Readings.
            readings.filter { it.first <= now }.forEach { (ms, tint) -> drawLine(tint.copy(alpha = .55f), Offset(x(ms), h - band), Offset(x(ms), h), 1f) }
            // Focused device: signal trace, -100 dBm at the bottom, -30 at the top.
            focused?.samples?.filter { it.ms <= now }?.takeIf { it.isNotEmpty() }?.let { s ->
                fun y(rssi: Int) = plotH - plotH * ((rssi + 100).coerceIn(0, 70) / 70f)
                val trace = Path().apply { s.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.ms), y(p.rssi)) else lineTo(x(p.ms), y(p.rssi)) } }
                drawPath(trace, Tech.Green, style = Stroke(1.5.dp.toPx()))
                s.forEach { drawCircle(Tech.Green, 2.dp.toPx(), Offset(x(it.ms), y(it.rssi))) }
            }
            if (now < window) drawLine(Tech.Amber.copy(alpha = .6f), Offset(x(now), 0f), Offset(x(now), h), 1f)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0 s", fontFamily = Tech.Mono, fontSize = 9.sp, color = Tech.Muted)
            Text(window?.let { "${it / 1000} s" } ?: "—", fontFamily = Tech.Mono, fontSize = 9.sp, color = Tech.Muted)
        }
    }
}

@Composable private fun AdapterLine(adapters: List<BtAdapter>) {
    adapters.forEach { adapter ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(adapter.name.ifBlank { adapter.id }, Modifier.weight(1f, fill = false).identity(), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            adapter.address?.let { Text(it, Modifier.identity(), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1, softWrap = false) }
            when (adapter.powered) { true -> TechTag("Powered", Tech.Green); false -> TechTag("Off", Tech.Amber); null -> Unit }
            if (adapter.discovering == true) AttentionTag("Scanning", help = "This adapter is looking for devices.")
        }
    }
}

@Composable private fun DeviceDetail(d: BtDevice, scan: BtScan?) {
    val s = d.samples.map { it.rssi }
    TechPanel(Modifier.fillMaxWidth(), rail = deviceTint(d), padding = 10.dp) {
        Text(d.name?.takeIf { it.isNotBlank() } ?: "Unnamed device", Modifier.identity(), fontFamily = Tech.Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(d.address, d.kind.chip(), d.category).joinToString("  ·  "), Modifier.identity(), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(when {
            s.isNotEmpty() -> "${s.size} readings · ${s.min()} to ${s.max()} dBm · avg ${s.average().toInt()}" + (d.firstSeenMs?.let { " · first heard at ${"%.1f".format(it / 1000.0)} s" } ?: "")
            d.rssi != null -> "${d.rssi} dBm"
            scan?.finished == true -> "Not heard in this scan"
            else -> "Not heard yet"
        }, fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 2)
    }
}

@Composable private fun NoteBanner(note: String, modifier: Modifier = Modifier, platform: String? = null) {
    val windowsSettings = note == BT_ADAPTER_OFF && platform?.startsWith("Windows") == true && bluetoothSettingsCommand() != null
    val linuxRadio = note == BT_ADAPTER_OFF && platform?.startsWith("Linux") == true
    TechPanel(modifier, rail = Tech.Amber) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(note, color = Tech.Amber, fontFamily = Tech.Mono, fontSize = 12.sp, lineHeight = 16.sp)
            if (windowsSettings) MiniAction("Open Bluetooth settings") { openBluetoothSettings() }
            if (linuxRadio) Text("This app will not turn the radio on. Use the system settings, rfkill, or bluetoothctl.",
                color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

/** "heard", "signal", "name". */
private fun sortDevices(devices: List<BtDevice>, sort: String, descending: Boolean): List<BtDevice> {
    val base = when (sort) {
        "signal" -> compareByDescending<BtDevice> { it.rssi ?: Int.MIN_VALUE }.thenBy { it.address }
        "name" -> compareBy<BtDevice> { it.name?.lowercase() ?: "￿" }.thenBy { it.address }
        // Known-but-unheard devices (paired, out of range) are reported at the start, so they lead; heard devices
        // follow in the order they were first heard. A live list therefore only ever grows at the bottom.
        else -> compareBy<BtDevice> { it.firstSeenMs ?: -1L }.then(btDeviceOrder).thenBy { it.address }
    }
    return devices.sortedWith(if (descending) base.reversed() else base)
}

@Composable private fun DeviceBrowser(devices: List<BtDevice>, scanning: Boolean, scan: BtScan?, clock: MutableLongState, focus: String?, selected: String?,
    onFocus: (String?) -> Unit, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var sort by remember { mutableStateOf("heard") }
    var descending by remember { mutableStateOf(false) }
    val shown = remember(devices, filter, query, sort, descending) {
        sortDevices(devices.filter { matches(it, filter, query) }, sort, descending)
    }
    BoxWithConstraints(modifier) {
        val cols = btCols(maxWidth)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Devices", suffix = "${devices.size}")
                Box(Modifier.weight(1f)) { BtSearch(query) { query = it } }
                SegmentedFilter(listOf("All" to devices.size, "Paired" to devices.count { it.paired == true }, "Nearby" to devices.count { it.rssi != null }), filter) { filter = it }
            }
            val onSort: (String) -> Unit = { key -> if (sort == key) descending = !descending else { sort = key; descending = false } }
            Row(Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(cols.gap)) {
                Spacer(Modifier.width(cols.glyph))
                SortHeader("DEVICE", "name", sort, descending, Modifier.weight(1f), onSort = onSort)
                if (cols.address > 0.dp) HeaderCell("Address", Modifier.width(cols.address))
                HeaderCell("Kind", Modifier.width(cols.kind))
                SortHeader("SIGNAL", "signal", sort, descending, Modifier.width(cols.signal), onSort = onSort)
                if (cols.trace > 0.dp) HeaderCell("Trace", Modifier.width(cols.trace))
                SortHeader("HEARD", "heard", sort, descending, Modifier.width(cols.heard), onSort = onSort)
                HeaderCell("State", Modifier.width(cols.state))
            }
            Surface(Modifier.weight(1f).fillMaxWidth(), color = Tech.Panel, shape = Tech.Plate, border = BorderStroke(1.dp, Tech.Edge)) {
                if (shown.isEmpty()) Text(if (devices.isEmpty()) (if (scanning) ListeningCopy else ReadyCopy) else "No devices match.", Modifier.padding(10.dp), color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 11.sp)
                else LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.address }) { device ->
                        Column(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = if (LocalReduceMotion.current) null else androidx.compose.animation.core.tween(220))) {
                            DeviceRow(device, cols, scan, clock, device.address == focus, device.address == selected, onFocus, onSelect)
                            HorizontalDivider(color = Tech.Edge.copy(alpha = .55f))
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun HeaderCell(label: String, modifier: Modifier) {
    Text(label.uppercase(), modifier, fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Muted, maxLines = 1)
}

@Composable private fun BtSearch(value: String, onValue: (String) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val concealed = identityFieldConcealed(Appearance.showcase, focused, value)
    BasicTextField(value, onValue, singleLine = true, interactionSource = interaction,
        textStyle = TextStyle(color = Tech.Ink, fontSize = 12.sp, fontFamily = Tech.Mono), cursorBrush = SolidColor(Tech.Amber),
        visualTransformation = if (concealed) VisualTransformation { text ->
            TransformedText(AnnotatedString(identityMask(text.text)), OffsetMapping.Identity)
        } else VisualTransformation.None,
        modifier = Modifier.onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onValue(""); true } else false }
            .fillMaxWidth().height(30.dp).border(1.dp, Tech.Edge, Tech.Control).background(Tech.Black.copy(alpha = .4f))
            .then(if (concealed) Modifier.blur(3.dp, BlurredEdgeTreatment.Unbounded).semantics { contentDescription = "Hidden" } else Modifier),
        decorationBox = { inner ->
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text("Name or address", color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            }
        })
}

/** Rows have a fixed height and an instant highlight; the field and the list share focus and selection. */
@Composable private fun DeviceRow(device: BtDevice, cols: BtCols, scan: BtScan?, clock: MutableLongState, focused: Boolean, selected: Boolean,
    onFocus: (String?) -> Unit, onSelect: (String) -> Unit) {
    val interaction = remember(device.address) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onFocus(device.address) else if (focused) onFocus(null) }
    val unnamed = device.name.isNullOrBlank()
    Row(Modifier.fillMaxWidth().height(26.dp).background(if (selected || focused || hovered) Tech.Raised else Color.Transparent)
        .hoverable(interaction).clickable(interactionSource = interaction, indication = null) { onSelect(device.address) }
        .drawBehind {
            if (selected) drawRect(Tech.Amber, size = Size(2.dp.toPx(), size.height))
            else if (device.connected == true) drawRect(Tech.Green, size = Size(2.dp.toPx(), size.height))
        }
        .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(cols.gap)) {
        Box(Modifier.width(cols.glyph), contentAlignment = Alignment.Center) { Canvas(Modifier.size(14.dp)) { btGlyph(device.category) } }
        Text(if (unnamed) "Unnamed device" else device.name.orEmpty(), Modifier.weight(1f).identity(), color = if (unnamed) Tech.Muted else Tech.Ink,
            fontFamily = Tech.Mono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (cols.address > 0.dp) Text(device.address, Modifier.width(cols.address).identity(), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        Box(Modifier.width(cols.kind), contentAlignment = Alignment.CenterStart) {
            val kind = device.kind.chip()
            if (kind == null) Text("—", color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 10.sp) else TechTag(kind, Tech.Ink)
        }
        SignalMeter(device.rssi, Modifier.width(cols.signal))
        if (cols.trace > 0.dp) SignalTrace(device, scan, Modifier.width(cols.trace).height(14.dp))
        HeardAgo(device, scan, clock, Modifier.width(cols.heard))
        Row(Modifier.width(cols.state), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (device.paired == true) TechTag("Paired", Tech.Amber)
            if (device.connected == true) TechTag("Connected", Tech.Green)
            if (device.paired != true && device.connected != true) Text("—", color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 10.sp)
        }
    }
}

/** The device's readings across the window as a small line, -100 dBm at the bottom. */
@Composable private fun SignalTrace(device: BtDevice, scan: BtScan?, modifier: Modifier) {
    val window = scan?.windowMs
    Canvas(modifier) {
        drawLine(Tech.Edge.copy(alpha = .6f), Offset(0f, size.height - .5f), Offset(size.width, size.height - .5f), 1f)
        val s = device.samples
        if (s.isEmpty() || window == null || window <= 0) return@Canvas
        fun x(ms: Long) = (ms.toFloat() / window).coerceIn(0f, 1f) * size.width
        fun y(rssi: Int) = size.height - size.height * ((rssi + 100).coerceIn(0, 70) / 70f)
        val tint = deviceTint(device)
        if (s.size == 1) { drawCircle(tint, 1.5.dp.toPx(), Offset(x(s[0].ms), y(s[0].rssi))); return@Canvas }
        val p = Path().apply { s.forEachIndexed { i, v -> if (i == 0) moveTo(x(v.ms), y(v.rssi)) else lineTo(x(v.ms), y(v.rssi)) } }
        drawPath(p, tint, style = Stroke(1.dp.toPx()))
    }
}

/** How long ago the device was last heard: "now" within a second; a dash when it was never heard. */
@Composable private fun HeardAgo(device: BtDevice, scan: BtScan?, clock: MutableLongState, modifier: Modifier) {
    val last = device.lastSeenMs
    if (last == null) { Text(if (device.rssi != null) "·" else "—", modifier, color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 10.sp, maxLines = 1); return }
    // Seconds granularity keeps this cheap: it recomposes when the second changes, not every frame.
    val ago by remember(last) { androidx.compose.runtime.derivedStateOf { ((clock.longValue - last).coerceAtLeast(0L) / 1000L).toInt() } }
    Text(if (ago < 1) "now" else "${ago}s", modifier, color = if (ago < 3) Tech.Green else Tech.Muted, fontFamily = Tech.Mono, fontSize = 10.sp, maxLines = 1)
}

private fun matches(device: BtDevice, filter: String, query: String): Boolean {
    val mode = when (filter) {
        "Paired" -> device.paired == true
        "Nearby" -> device.rssi != null
        else -> true
    }
    if (!mode) return false
    val q = query.trim()
    if (q.isEmpty()) return true
    return device.name?.contains(q, ignoreCase = true) == true || device.address.contains(q, ignoreCase = true)
}

/** Four bars across -100..-40 dBm. Below the floor is empty; -40 fills the meter. */
private fun signalBars(rssi: Int) = ((rssi.coerceIn(-100, -40) + 100) * 4) / 60

private fun signalTint(bars: Int) = when {
    bars >= 3 -> Tech.Green
    bars == 2 -> Tech.Amber
    else -> Tech.Muted
}

@Composable private fun SignalMeter(rssi: Int?, modifier: Modifier) {
    val bars = rssi?.let(::signalBars)
    val tint = if (bars == null) Tech.Muted else signalTint(bars)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(16.dp, 12.dp)) {
            val gap = 1.5.dp.toPx()
            val bw = (size.width - gap * 3) / 4f
            repeat(4) { i ->
                val h = size.height * (0.35f + 0.65f * i / 3f)
                drawRect(if (bars != null && i < bars) tint else Tech.Edge, Offset(i * (bw + gap), size.height - h), Size(bw, h))
            }
        }
        Text(if (rssi == null) "—" else "$rssi dBm", color = tint, fontFamily = Tech.Mono, fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

private fun BtKind.chip() = when (this) {
    BtKind.Classic -> "Classic"
    BtKind.LowEnergy -> "LE"
    BtKind.Dual -> "Dual"
    BtKind.Unknown -> null
}

private fun DrawScope.btGlyph(category: String?) {
    val c = Tech.Ink
    val st = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)
    val w = size.width
    val h = size.height
    when (category) {
        "audio-headset", "audio-headphones" -> {
            drawArc(c, 200f, 140f, false, Offset(w * .16f, h * .02f), Size(w * .68f, h * .62f), style = st)
            drawRoundRect(c, Offset(w * .08f, h * .40f), Size(w * .20f, h * .42f), CornerRadius(2f), style = st)
            drawRoundRect(c, Offset(w * .72f, h * .40f), Size(w * .20f, h * .42f), CornerRadius(2f), style = st)
        }
        "audio-card" -> {
            drawRoundRect(c, Offset(w * .22f, h * .12f), Size(w * .56f, h * .76f), CornerRadius(2f), style = st)
            drawCircle(c, w * .14f, Offset(w * .50f, h * .42f), style = st)
            drawLine(c, Offset(w * .50f, h * .60f), Offset(w * .50f, h * .76f), st.width, StrokeCap.Round)
        }
        "input-keyboard" -> {
            drawRoundRect(c, Offset(w * .06f, h * .28f), Size(w * .88f, h * .48f), CornerRadius(2f), style = st)
            repeat(2) { row ->
                val y = h * (0.44f + row * 0.16f)
                repeat(4) { col ->
                    val x = w * (0.20f + col * 0.18f)
                    drawLine(c, Offset(x, y), Offset(x + w * .08f, y), st.width, StrokeCap.Round)
                }
            }
        }
        "input-mouse" -> {
            drawOval(c, Offset(w * .30f, h * .06f), Size(w * .40f, h * .88f), style = st)
            drawLine(c, Offset(w * .50f, h * .06f), Offset(w * .50f, h * .40f), st.width, StrokeCap.Round)
            drawLine(c, Offset(w * .32f, h * .40f), Offset(w * .68f, h * .40f), st.width, StrokeCap.Round)
        }
        "phone" -> {
            drawRoundRect(c, Offset(w * .30f, h * .04f), Size(w * .40f, h * .92f), CornerRadius(3f), style = st)
            drawCircle(c, 1.2.dp.toPx(), Offset(w * .50f, h * .82f))
        }
        "video-display" -> { // TV or monitor on a stand
            drawRoundRect(c, Offset(w * .06f, h * .14f), Size(w * .88f, h * .56f), CornerRadius(2f), style = st)
            drawLine(c, Offset(w * .50f, h * .70f), Offset(w * .50f, h * .84f), st.width, StrokeCap.Round)
            drawLine(c, Offset(w * .30f, h * .86f), Offset(w * .70f, h * .86f), st.width, StrokeCap.Round)
        }
        "computer", "computer-laptop" -> {
            drawRoundRect(c, Offset(w * .16f, h * .10f), Size(w * .68f, h * .48f), CornerRadius(2f), style = st)
            drawLine(c, Offset(w * .28f, h * .58f), Offset(w * .14f, h * .78f), st.width, StrokeCap.Round)
            drawLine(c, Offset(w * .72f, h * .58f), Offset(w * .86f, h * .78f), st.width, StrokeCap.Round)
            drawLine(c, Offset(w * .08f, h * .78f), Offset(w * .92f, h * .78f), st.width, StrokeCap.Round)
        }
        else -> {
            drawCircle(c, w * .28f, center, style = st)
            drawCircle(c, 1.3.dp.toPx(), center)
        }
    }
}
