@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
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
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    BoxWithConstraints(modifier) {
        val viewportHeight = maxHeight
        val pageScroll = rememberScrollState()
        Column(Modifier.fillMaxSize().verticalScroll(pageScroll).padding(end = 10.dp)) {
            Column(Modifier.fillMaxWidth().height(viewportHeight.coerceAtLeast(620.dp * LocalDensity.current.fontScale)), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
                Text(if (scan == null) " " else listOfNotNull(scan.source.label, platform?.takeIf { it.isNotBlank() }, btScanTime.format(scan.at)).joinToString("  ·  ").uppercase(),
                modifier = Modifier.fillMaxWidth().identity(),
                fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (note != null && devices.isEmpty()) { NoteBanner(note, Modifier.widthIn(max = 720.dp).fillMaxWidth(), platform); return@Column }
                if (note != null) NoteBanner(note, Modifier.fillMaxWidth(), platform)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val tall = maxHeight >= 560.dp
                    val field: @Composable (Modifier) -> Unit = { m -> KindRings(devices, scan, clock, scanning, focus, selected, { focus = it }, { selected = if (selected == it) null else it }, m) }
                    val timeline: @Composable (Modifier) -> Unit = { m -> HeardTimeline(devices, scan, clock, focus ?: selected, m) }
                    val table: @Composable (Modifier) -> Unit = { m -> DeviceBrowser(devices, scanning, scan, clock, focus, selected, { focus = it }, { selected = if (selected == it) null else it }, m) }
                    if (maxWidth >= 1100.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BtScrollPane(Modifier.width(380.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            KindRingsSummary(devices, clock)
                            FieldPlate { field(Modifier.fillMaxWidth().aspectRatio(1f)) }
                            KindRingsKey()
                            StrongestDevices(devices, clock, focus ?: selected) { selected = if (selected == it) null else it }
                            if (scan == null || scan.windowMs != null) FieldPlate { timeline(Modifier.fillMaxWidth().height(118.dp)) }
                            scan?.adapters?.takeIf { it.isNotEmpty() }?.let { AdapterLine(it) }
                            (selected ?: focus)?.let { a -> devices.firstOrNull { it.address == a } }?.let { DeviceDetail(it, scan) }
                        }
                        table(Modifier.weight(1f).fillMaxHeight())
                    } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // The summary, key and strongest list beside the radar are text: their row grows with the text size
                        // (the page scrolls), so the strongest entries are not hidden below the pane's fold at 150 %.
                        Row(Modifier.fillMaxWidth().height((if (tall) 220.dp else 180.dp) * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FieldPlate(Modifier.fillMaxHeight().aspectRatio(1f)) { field(Modifier.fillMaxSize()) }
                            BtScrollPane(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                KindRingsSummary(devices, clock)
                                KindRingsKey()
                                StrongestDevices(devices, clock, focus ?: selected) { selected = if (selected == it) null else it }
                                if (scan == null || scan.windowMs != null) FieldPlate { timeline(Modifier.fillMaxWidth().height(92.dp)) }
                                (selected ?: focus)?.let { a -> devices.firstOrNull { it.address == a } }?.let { DeviceDetail(it, scan) }
                                scan?.adapters?.takeIf { it.isNotEmpty() }?.let { AdapterLine(it) }
                            }
                        }
                        table(Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(pageScroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight(), style=btScrollbarStyle())
    }
}

@Composable internal fun FieldPlate(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.border(1.dp, Tech.Edge, Tech.Plate).background(Tech.Black.copy(alpha = .35f), Tech.Plate).padding(8.dp)) { content() }
}

@Composable private fun AdapterLine(adapters: List<BtAdapter>) {
    adapters.forEach { adapter ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(adapter.name.ifBlank { adapter.id }, Modifier.fillMaxWidth().identity(), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Ink, softWrap = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            adapter.address?.let { Text(it, Modifier.identity(), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1, softWrap = false) }
            when (adapter.powered) { true -> TechTag("Powered", Tech.Green); false -> TechTag("Off", Tech.Amber); null -> Unit }
            if (adapter.discovering == true) AttentionTag("Scanning", help = "This adapter is looking for devices.")
            }
        }
    }
}

@Composable private fun DeviceDetail(d: BtDevice, scan: BtScan?) {
    val s = d.samples.map { it.rssi }
    TechPanel(Modifier.fillMaxWidth(), rail = deviceTint(d), padding = 10.dp) {
        Text(d.name?.takeIf { it.isNotBlank() } ?: "Unnamed device", Modifier.identity(), fontFamily = Tech.Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Tech.Ink)
        Text(listOfNotNull(d.address, d.kind.chip(), d.category).joinToString("  ·  "), Modifier.identity(), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted)
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
internal fun sortBtDevices(devices: List<BtDevice>, sort: String, descending: Boolean): List<BtDevice> {
    val base = when (sort) {
        "signal" -> compareByDescending<BtDevice> { it.rssi ?: Int.MIN_VALUE }.thenBy { it.address }
        "name" -> compareBy<BtDevice> { it.name?.lowercase() ?: "￿" }.thenBy { it.address }
        // Known-but-unheard devices (paired, out of range) are reported at the start, so they lead; heard devices
        // follow in the order they were first heard. A live list therefore only ever grows at the bottom.
        else -> compareBy<BtDevice> { it.firstSeenMs ?: -1L }.thenByDescending { it.connected == true }.thenByDescending { it.paired == true }.thenBy { it.address }
    }
    return devices.sortedWith(if (descending) base.reversed() else base)
}

@Composable internal fun DeviceBrowser(devices: List<BtDevice>, scanning: Boolean, scan: BtScan?, clock: ScanClock, focus: String?, selected: String?,
    onFocus: (String?) -> Unit, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var sort by remember { mutableStateOf("heard") }
    var descending by remember { mutableStateOf(false) }
    val shown = remember(devices, filter, query, sort, descending) {
        sortBtDevices(devices.filter { matches(it, filter, query) }, sort, descending)
    }
    BoxWithConstraints(modifier) {
        val font = LocalDensity.current.fontScale
        val tableWidth = maxWidth.coerceAtLeast(1100.dp * font)
        val cols = btCols(tableWidth / font).let { it.copy(address=it.address*font,kind=it.kind*font,signal=it.signal*font,trace=it.trace*font,heard=it.heard*font,state=it.state*font) }
        val horizontal = rememberScrollState()
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(Modifier.fillMaxWidth(), itemVerticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(8.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                SectionLabel("Devices", suffix = "${devices.size}")
                Box(Modifier.widthIn(max=240.dp)) { BtSearch(query) { query=it } }
                SegmentedFilter(listOf("All" to devices.size, "Paired" to devices.count { it.paired == true }, "Nearby" to devices.count { it.rssi != null }), filter) { filter=it }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // The bar sits above the column headers: below the table it fell under the page fold at large text,
                // so the columns beyond the edge had no visible route.
                Box(Modifier.fillMaxSize().padding(top=12.dp).horizontalScroll(horizontal)) {
                    Column(Modifier.width(tableWidth).fillMaxHeight(), verticalArrangement=Arrangement.spacedBy(6.dp)) {
            val onSort: (String) -> Unit = { key -> if (sort == key) descending = !descending else { sort = key; descending = false } }
            Row(Modifier.fillMaxWidth().heightIn(min = 22.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(cols.gap)) {
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
                        Column(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = if (LocalMotion.current.reduced || LocalMotion.current.lite) null else androidx.compose.animation.core.tween(220))) {
                            DeviceRow(device, cols, scan?.windowMs, clock, device.address == focus, device.address == selected, onFocus, onSelect)
                            HorizontalDivider(color = Tech.Edge.copy(alpha = .55f))
                        }
                    }
                }
            }
                    }
                }
                HorizontalScrollbar(rememberScrollbarAdapter(horizontal),Modifier.align(Alignment.TopStart).fillMaxWidth(),style=btScrollbarStyle())
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
            .fillMaxWidth().height(30.dp * LocalDensity.current.fontScale).border(1.dp, Tech.Edge, Tech.Control).background(Tech.Black.copy(alpha = .4f))
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

/**
 * Rows have a fixed height and an instant highlight; the field and the list share focus and selection.
 * Every parameter is stable or compared by identity, so a scan update recomposes only the rows whose device changed.
 */
@Composable private fun DeviceRow(device: BtDevice, cols: BtCols, window: Long?, clock: ScanClock, focused: Boolean, selected: Boolean,
    onFocus: (String?) -> Unit, onSelect: (String) -> Unit) {
    BtScanStats.rowCompositions++
    val interaction = remember(device.address) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onFocus(device.address) else if (focused) onFocus(null) }
    val unnamed = device.name.isNullOrBlank()
    Row(Modifier.fillMaxWidth().height(26.dp * LocalDensity.current.fontScale).background(if (selected || focused || hovered) Tech.Raised else Color.Transparent)
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
        if (cols.trace > 0.dp) SignalTrace(device, window, Modifier.width(cols.trace).height(14.dp))
        HeardAgo(device, clock, Modifier.width(cols.heard))
        Row(Modifier.width(cols.state), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (device.paired == true) TechTag("Paired", Tech.Amber)
            if (device.connected == true) TechTag("Connected", Tech.Green)
            if (device.paired != true && device.connected != true) Text("—", color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 10.sp)
        }
    }
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

/** Independent scroll regions always expose a thumb, including short-window overview details. */
@Composable private fun BtScrollPane(modifier: Modifier, verticalArrangement: Arrangement.Vertical, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end=10.dp), verticalArrangement=verticalArrangement, content=content)
        VerticalScrollbar(rememberScrollbarAdapter(scroll),Modifier.align(Alignment.CenterEnd).fillMaxHeight(),style=btScrollbarStyle())
    }
}

@Composable private fun btScrollbarStyle() = LocalScrollbarStyle.current.copy(unhoverColor=Tech.Muted.copy(alpha=.7f), hoverColor=Tech.Amber)
