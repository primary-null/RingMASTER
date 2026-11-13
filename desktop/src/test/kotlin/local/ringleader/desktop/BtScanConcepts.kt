@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import local.ringleader.core.BtDevice
import local.ringleader.core.BtScan

/*
 * Three proposed pictures of a Bluetooth scan (round 3, B1). None is wired into the page: BtScanView shows one only when
 * a test sets [BtScanConcepts.preview]. Every picture says only what a scan knows: signal strength and how it moved,
 * when each device was heard, its kind and name when reported, paired and connected. Direction is never drawn as if
 * it were known.
 */

internal enum class BtConcept(val title: String) { KindRings("Kind rings"), SignalTimeline("Signal timeline"), ProximityLanes("Proximity lanes") }

/** Test-only switch. The app never sets it, so the page always shows today's view. */
internal object BtScanConcepts {
    @Volatile var preview: BtConcept? = null
    /** The device a picture starts with selected (the one the user cares about). */
    @Volatile var selected: String? = null
}

/** First heard this recently: new. */
internal const val BtNewMs = 3_000L
/** Not heard for this long: gone quiet. */
internal const val BtQuietMs = 5_000L

internal enum class BtBand(val label: String, val range: String) { Near("NEAR", "-60 dBm or more"), Mid("MID", "-60 to -78 dBm"), Far("FAR", "under -78 dBm") }

internal fun bandOf(signal: Float) = when { signal >= -60f -> BtBand.Near; signal >= -78f -> BtBand.Mid; else -> BtBand.Far }

/** What a picture may say about one heard device at one moment. */
@Immutable internal class HeardFact(val device: BtDevice, val signal: Float, val band: BtBand, val fresh: Boolean, val quiet: Boolean)

/** Facts for every heard device at [now] ms into the scan. */
internal class HeardFacts(devices: List<BtDevice>, val now: Long) {
    val heard: List<HeardFact> = devices.mapNotNull { d ->
        val s = d.fieldRssi() ?: return@mapNotNull null
        HeardFact(d, s, bandOf(s), fresh = d.firstSeenMs?.let { now - it in 0 until BtNewMs } == true, quiet = d.lastSeenMs?.let { now - it > BtQuietMs } == true)
    }
    val near = heard.count { it.band == BtBand.Near && !it.quiet }
    val fresh = heard.count { it.fresh }
    val quiet = heard.count { it.quiet }
}

private val conceptTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)

/** BtScanView with a concept in place of the field and timeline; the table is the page's own. */
@Composable internal fun ConceptScanView(concept: BtConcept, scan: BtScan?, scanning: Boolean, modifier: Modifier, platform: String?, receivedAt: Long) {
    val devices = scan?.devices.orEmpty()
    var focus by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(BtScanConcepts.selected) }
    val clock = rememberScanClock(scan, receivedAt, scanning)
    val now = clock.coarse.longValue
    val facts = remember(devices, now) { HeardFacts(devices, now) }
    val onFocus: (String?) -> Unit = { focus = it }
    val onSelect: (String) -> Unit = { selected = if (selected == it) null else it }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
        Text(if (scan == null) " " else listOfNotNull(scan.source.label, platform?.takeIf { it.isNotBlank() }, conceptTime.format(scan.at)).joinToString("  ·  ").uppercase(),
            modifier = Modifier.fillMaxWidth().identity(), fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val picture: @Composable (Modifier) -> Unit = { m ->
                when (concept) {
                    BtConcept.KindRings -> KindRings(facts, scan, clock, scanning, focus ?: selected, onFocus, onSelect, m)
                    BtConcept.SignalTimeline -> SignalTimelineConcept(facts, scan, clock, focus ?: selected, onFocus, onSelect, m)
                    BtConcept.ProximityLanes -> ProximityLanes(facts, focus ?: selected, onFocus, onSelect, m)
                }
            }
            val table: @Composable (Modifier) -> Unit = { m -> DeviceBrowser(devices, scanning, scan, clock, focus, selected, onFocus, onSelect, m) }
            val band = (maxHeight * .52f).coerceIn(200.dp, 600.dp)
            if (concept == BtConcept.KindRings && maxWidth >= 1100.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FieldPlate(Modifier.width(440.dp).fillMaxHeight()) { ConceptFrame(facts, devices.size, concept) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        picture(Modifier.fillMaxWidth().aspectRatio(1f))
                        RingsKey()
                        ClosestList(facts, focus ?: selected, onSelect, Modifier.weight(1f).fillMaxWidth())
                    }
                } }
                table(Modifier.weight(1f).fillMaxHeight())
            } else if (concept == BtConcept.KindRings) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldPlate(Modifier.fillMaxWidth().height(band)) { ConceptFrame(facts, devices.size, concept) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        picture(Modifier.fillMaxHeight().aspectRatio(1f))
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            RingsKey()
                            ClosestList(facts, focus ?: selected, onSelect, Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                } }
                table(Modifier.weight(1f).fillMaxWidth())
            } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldPlate(Modifier.fillMaxWidth().height(band)) { ConceptFrame(facts, devices.size, concept) { picture(Modifier.fillMaxSize()) } }
                table(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** The counts every concept answers first, the picture, and the one line that says what the picture does not know. */
@Composable private fun ConceptFrame(facts: HeardFacts, total: Int, concept: BtConcept, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Count("${facts.heard.size}", "HEARD", Tech.Amber)
            Count("${facts.near}", "NEAR", Tech.Green)
            Count("${facts.fresh}", "NEW", Tech.Amber)
            Count("${facts.quiet}", "QUIET", Tech.Muted)
            Spacer(Modifier.weight(1f))
            if (total > facts.heard.size) Text("${total - facts.heard.size} KNOWN, NOT HEARD", fontFamily = Tech.Mono, fontSize = 9.sp, letterSpacing = 1.sp, color = Tech.Muted, maxLines = 1)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { content() }
        Text(when (concept) {
            BtConcept.KindRings -> "Distance from the centre is signal strength. Sectors group devices by kind. Direction is not known."
            BtConcept.SignalTimeline -> "Each line is one device across the scan, strongest on top; thicker and greener is stronger. Direction is not known."
            BtConcept.ProximityLanes -> "Lanes group devices by signal strength; a tile moves when its signal does. Direction is not known."
        }, fontFamily = Tech.Mono, fontSize = 10.sp, lineHeight = 13.sp, color = Tech.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun Count(value: String, label: String, tint: Color) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, fontFamily = Tech.Mono, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = tint, maxLines = 1)
        Text(label, Modifier.padding(bottom = 2.dp), fontFamily = Tech.Mono, fontSize = 9.sp, letterSpacing = 1.sp, color = Tech.Muted, maxLines = 1)
    }
}

/** The symbols of the kind rings. */
@Composable private fun RingsKey() {
    val muted = Tech.Muted; val ink = Tech.Ink; val amber = Tech.Amber; val green = Tech.Green
    val items: List<Pair<String, DrawScope.() -> Unit>> = listOf(
        "heard" to { drawCircle(ink, 3.5.dp.toPx()) },
        "unnamed" to { drawCircle(muted, 3.5.dp.toPx()) },
        "quiet 5 s+" to { drawCircle(muted, 3.5.dp.toPx(), style = Stroke(1.dp.toPx())) },
        "new 3 s" to { drawCircle(ink, 3.dp.toPx()); drawCircle(amber, 6.dp.toPx(), style = Stroke(1.5.dp.toPx())) },
        "paired" to { drawCircle(amber, 4.5.dp.toPx()) },
        "connected" to { drawCircle(green, 4.5.dp.toPx()) },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (label, glyph) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Spacer(Modifier.size(14.dp).drawBehind(glyph))
                Text(label, fontFamily = Tech.Mono, fontSize = 9.sp, lineHeight = 11.sp, color = Tech.Muted, maxLines = 1)
            }
        }
    }
}

/** The strongest devices in words: what "close" means without reading the picture. Quiet devices sink to the end. */
@Composable private fun ClosestList(facts: HeardFacts, mark: String?, onSelect: (String) -> Unit, modifier: Modifier) {
    val ranked = remember(facts) { facts.heard.sortedByDescending { if (it.quiet) it.signal - 100f else it.signal } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("CLOSEST", fontFamily = Tech.Mono, fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = 1.sp, color = Tech.Amber, maxLines = 1)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val fit = (maxHeight / 18.dp).toInt().coerceAtLeast(0)
            Column {
                ranked.take(fit).forEach { f ->
                    val d = f.device
                    Row(Modifier.fillMaxWidth().height(18.dp).background(if (d.address == mark) Tech.Raised else Color.Transparent).clickable { onSelect(d.address) }.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(6.dp).background(if (f.quiet) Color.Transparent else stateTint(d), CircleShape).border(1.dp, if (f.quiet) Tech.Muted else Color.Transparent, CircleShape))
                        Text(d.name?.takeIf { it.isNotBlank() } ?: "Unnamed device", Modifier.weight(1f).identity(), fontFamily = Tech.Mono, fontSize = 10.sp, lineHeight = 12.sp,
                            color = if (d.address == mark) Tech.Amber else if (f.quiet || d.name.isNullOrBlank()) Tech.Muted else Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (f.fresh) Text("NEW", fontFamily = Tech.Mono, fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold, color = Tech.Amber)
                        if (f.quiet) Text("QUIET", fontFamily = Tech.Mono, fontSize = 9.sp, lineHeight = 11.sp, color = Tech.Muted)
                        Text("${f.signal.roundToInt()} dBm", fontFamily = Tech.Mono, fontSize = 10.sp, lineHeight = 12.sp, color = lerp(Tech.Muted, Tech.Green, signalShare(f.signal)), maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Stable 0..1 from an address: spreads dots within a sector and keeps each in the same place for the whole scan. */
private fun addressFraction(address: String) = ((address.hashCode().toLong() and 0xffffffffL) * 0.6180339887 % 1.0)

private fun stateTint(d: BtDevice) = tintPalette()[deviceTintClass(d)]

// ---------------------------------------------------------------------------------------------------------------------
// A. Kind rings: the radial field kept, with angle given a real meaning (kind) and distance spelled out as bands.
// ---------------------------------------------------------------------------------------------------------------------

private enum class Sector(val label: String, val short: String) { Audio("AUDIO", "AUDIO"), Input("INPUT", "INPUT"), Personal("PHONES · COMPUTERS", "PHONE·PC"), Other("OTHER · UNKNOWN", "OTHER") }

private fun sectorOf(category: String?) = when {
    category == null -> Sector.Other
    category.startsWith("audio") -> Sector.Audio
    category.startsWith("input") -> Sector.Input
    category == "phone" || category.startsWith("computer") -> Sector.Personal
    else -> Sector.Other
}

/** -30 dBm at the centre, -100 at the rim. */
private fun ringShare(signal: Float) = ((-signal - 30f) / 70f).coerceIn(.08f, 1f)

@Composable private fun KindRings(facts: HeardFacts, scan: BtScan?, clock: ScanClock, live: Boolean, mark: String?, onFocus: (String?) -> Unit, onSelect: (String) -> Unit, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val small = remember { TextStyle(fontFamily = Tech.Mono, fontSize = 9.sp, letterSpacing = 1.sp) }
    val name = remember { TextStyle(fontFamily = Tech.Mono, fontSize = 10.sp) }
    val bold = remember { TextStyle(fontFamily = Tech.Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
    val showcase = Appearance.showcase
    val window = scan?.windowMs?.takeIf { it > 0 }
    val hits = remember { FieldHits() }
    val onFocusNow by rememberUpdatedState(onFocus)
    val onSelectNow by rememberUpdatedState(onSelect)
    Box(modifier.drawWithCache {
        val c = size.center
        val radius = size.minDimension / 2f - 30.dp.toPx()
        val edge = Tech.Edge; val muted = Tech.Muted; val amber = Tech.Amber; val green = Tech.Green; val ink = Tech.Ink; val panel = Tech.Panel
        val gap = 3f
        val sectors = Sector.entries
        val span = 360f / sectors.size
        // Sector of each device, and its angle inside it.
        val placed = facts.heard.map { f ->
            val s = sectorOf(f.device.category)
            val start = -90f + s.ordinal * span + gap
            val deg = start + addressFraction(f.device.address).toFloat() * (span - 2 * gap)
            val a = deg * PI / 180.0
            val r = ringShare(f.signal) * radius
            f to Offset(c.x + (cos(a) * r).toFloat(), c.y + (sin(a) * r).toFloat())
        }
        hits.update(placed.map { it.first.device.address }.toTypedArray(), FloatArray(placed.size) { placed[it].second.x }, FloatArray(placed.size) { placed[it].second.y })
        val nearR = ringShare(-60f) * radius; val midR = ringShare(-78f) * radius
        val nearGlow = Brush.radialGradient(listOf(green.copy(alpha = .14f), green.copy(alpha = .03f)), c, nearR)
        val dashed = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f)))
        val thin = Stroke(1.dp.toPx())
        val bandLabels = listOf("NEAR" to nearR, "MID" to midR, "FAR" to radius).map { (l, r) -> measurer.measure(l, small) to r }
        val roomy = radius >= 130.dp.toPx()
        val sectorLabels = sectors.map { s ->
            val counted = facts.heard.count { sectorOf(it.device.category) == s }
            measurer.measure("${if (roomy) s.label else s.short} $counted", small)
        }
        // Labels: the strongest named devices that are still talking, and the marked one; a label never covers another.
        val taken = mutableListOf<Rect>()
        val labels = (placed.filter { it.first.device.address == mark } + placed.filter { !it.first.quiet && it.first.device.address != mark }
            .sortedByDescending { it.first.signal }.take(if (roomy) 6 else 2)).mapNotNull { (f, p) ->
            val text = listOfNotNull(if (showcase) null else f.device.name?.takeIf { it.isNotBlank() }, "${f.signal.roundToInt()}").joinToString("  ")
            val t = measurer.measure(text, if (f.device.address == mark) bold else name)
            val x = (p.x + 8f).coerceAtMost(size.width - t.size.width - 2f).coerceAtLeast(2f)
            val y = (p.y - t.size.height / 2f).coerceIn(2f, size.height - t.size.height - 2f)
            val box = Rect(x - 3f, y - 1f, x + t.size.width + 3f, y + t.size.height + 1f)
            if (taken.any { it.overlaps(box) }) null else { taken += box; Triple(t, Offset(x, y), f.device.address == mark) }
        }
        val scale = (radius / 150.dp.toPx()).coerceIn(.6f, 1f)
        val dot = 3.5.dp.toPx() * scale; val big = 4.5.dp.toPx() * scale; val freshR = 7.dp.toPx(); val ringStroke = Stroke(1.5.dp.toPx())
        onDrawBehind {
            drawCircle(nearGlow, nearR, c)
            sectors.forEachIndexed { i, _ ->
                drawArc(edge.copy(alpha = if (i % 2 == 0) .16f else .08f), -90f + i * span + gap, span - 2 * gap, true, Offset(c.x - radius, c.y - radius), Size(radius * 2, radius * 2))
            }
            drawCircle(edge.copy(alpha = .7f), nearR, c, style = dashed)
            drawCircle(edge.copy(alpha = .7f), midR, c, style = dashed)
            drawCircle(edge, radius, c, style = thin)
            bandLabels.forEach { (t, r) -> drawText(t, color = muted, topLeft = Offset(c.x + 4f, c.y - r + 2f)) }
            sectorLabels.forEachIndexed { i, t ->
                val a = (-90f + (i + .5f) * span) * PI / 180.0
                val lr = radius + 14.dp.toPx()
                val px = c.x + (cos(a) * lr).toFloat(); val py = c.y + (sin(a) * lr).toFloat()
                drawText(t, color = muted, topLeft = Offset((px - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), (py - t.size.height / 2f).coerceIn(0f, size.height - t.size.height)))
            }
            placed.sortedBy { it.first.signal }.forEach { (f, p) ->
                val tint = stateTint(f.device)
                val r = if (f.device.connected == true || f.device.paired == true) big else dot
                if (f.quiet) drawCircle(muted.copy(alpha = .8f), r, p, style = thin)
                else drawCircle(tint, r, p)
                if (f.fresh) drawCircle(amber, freshR, p, style = ringStroke)
                if (f.device.address == mark) drawCircle(amber, r + 5.dp.toPx(), p, style = ringStroke)
            }
            labels.forEach { (t, o, marked) ->
                drawRoundRect(panel.copy(alpha = .88f), Offset(o.x - 3f, o.y - 1f), Size(t.size.width + 6f, t.size.height + 2f), CornerRadius(3f))
                drawText(t, color = if (marked) amber else ink, topLeft = o)
            }
            drawCircle(amber.copy(alpha = .25f), 8.dp.toPx(), c)
            drawCircle(amber, 3.5.dp.toPx(), c)
        }
    }) {
        // Only the listening arc moves.
        if (window != null) Spacer(Modifier.matchParentSize().graphicsLayer().drawWithCache {
            val radius = size.minDimension / 2f - 30.dp.toPx()
            val tl = Offset(size.center.x - radius, size.center.y - radius); val sz = Size(radius * 2, radius * 2)
            val amber = Tech.Amber; val arc = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
            onDrawBehind { drawArc(if (live) amber else amber.copy(alpha = .45f), -90f, 360f * (clock.ms.longValue.toFloat() / window).coerceIn(0f, 1f), false, tl, sz, style = arc) }
        })
        Spacer(Modifier.matchParentSize().pointerInput(hits) {
            val within = 12.dp.toPx()
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    val p = e.changes.firstOrNull()?.position ?: continue
                    val hit = hits.nearest(p.x, p.y, within)
                    when (e.type) { PointerEventType.Exit -> onFocusNow(null); PointerEventType.Press -> hit?.let(onSelectNow); else -> onFocusNow(hit) }
                }
            }
        })
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// B. Signal timeline: one line per device across the listening window, strongest on top.
// ---------------------------------------------------------------------------------------------------------------------

private fun signalShare(signal: Float) = ((signal + 95f) / 50f).coerceIn(0f, 1f)

@Composable private fun SignalTimelineConcept(facts: HeardFacts, scan: BtScan?, clock: ScanClock, mark: String?, onFocus: (String?) -> Unit, onSelect: (String) -> Unit, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val small = remember { TextStyle(fontFamily = Tech.Mono, fontSize = 9.sp) }
    val name = remember { TextStyle(fontFamily = Tech.Mono, fontSize = 10.sp) }
    val showcase = Appearance.showcase
    val window = scan?.windowMs?.takeIf { it > 0 } ?: 1L
    val ranking = remember { SteadyRanking<String>(margin = .08, floor = 2.0) }
    val order = remember(facts) { ranking.rank(facts.heard.associate { it.device.address to (it.signal + 110.0) }) }
    val byAddress = remember(facts) { facts.heard.associateBy { it.device.address } }
    val rows = remember(order, byAddress) { order.mapNotNull { byAddress[it] } }
    val drawn = remember { arrayOf<List<HeardFact>>(emptyList()) }
    val geometry = remember { FloatArray(4) }
    val onFocusNow by rememberUpdatedState(onFocus)
    val onSelectNow by rememberUpdatedState(onSelect)
    Box(modifier.drawWithCache {
        val w = size.width; val h = size.height
        val axis = 16.dp.toPx()
        val gutter = if (w >= 700.dp.toPx()) 190.dp.toPx() else 120.dp.toPx()
        val values = 70.dp.toPx()
        val plotL = gutter; val plotR = w - values; val plotW = (plotR - plotL).coerceAtLeast(1f)
        val plotH = h - axis
        val minRow = 4.dp.toPx(); val maxRow = 22.dp.toPx()
        val rowH = (plotH / rows.size.coerceAtLeast(1)).coerceIn(minRow, maxRow)
        val fit = floor(plotH / rowH).toInt()
        val shown = if (rows.size <= fit) rows.size else (fit - 2).coerceAtLeast(0)
        // The device the user cares about is never in the hidden rest: it takes the last visible row.
        val markAt = rows.indexOfFirst { it.device.address == mark }
        val rows = if (markAt >= shown && shown > 0) rows.toMutableList().apply { add(shown - 1, removeAt(markAt)) } else rows
        drawn[0] = rows
        val rest = rows.size - shown
        geometry[0] = axis; geometry[1] = rowH; geometry[2] = plotL; geometry[3] = shown.toFloat()
        fun x(ms: Long) = plotL + (ms.toFloat() / window).coerceIn(0f, 1f) * plotW
        val edge = Tech.Edge; val muted = Tech.Muted; val green = Tech.Green; val amber = Tech.Amber; val ink = Tech.Ink; val raised = Tech.Raised
        val labels = rowH >= 9.dp.toPx()
        val rowText = if (rowH >= 13.dp.toPx()) name else small
        val names = if (!labels) emptyList() else rows.take(shown).map { f ->
            measurer.measure(if (showcase) "••••••" else f.device.name?.takeIf { it.isNotBlank() } ?: "Unnamed device", rowText,
                overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1, constraints = Constraints(maxWidth = (gutter - 20.dp.toPx()).toInt().coerceAtLeast(1)))
        }
        val dbm = if (!labels) emptyList() else rows.take(shown).map { measurer.measure("${it.signal.roundToInt()} dBm", small) }
        val newText = measurer.measure("NEW", small)
        val restText = if (rest > 0) measurer.measure("+$rest weaker or quieter", small) else null
        val ticks = (0..(window / 5000).toInt()).map { s -> measurer.measure("${s * 5} s", small) to x(s * 5000L) }
        val dashed = PathEffect.dashPathEffect(floatArrayOf(2f, 4f))
        val sc = (rowH * .6f).coerceIn(1.5f, 10.dp.toPx())
        onDrawBehind {
            ticks.forEach { (t, tx) -> drawLine(edge.copy(alpha = .35f), Offset(tx, axis), Offset(tx, h), 1f); drawText(t, color = muted, topLeft = Offset((tx - t.size.width / 2f).coerceIn(plotL, w - t.size.width), 0f)) }
            for (i in 0 until shown) {
                val f = rows[i]; val d = f.device
                val top = axis + i * rowH; val cy = top + rowH / 2f
                if (d.address == mark) drawRect(raised, Offset(0f, top), Size(w, rowH))
                if (rowH >= 8.dp.toPx()) drawLine(edge.copy(alpha = .25f), Offset(plotL, cy), Offset(plotR, cy), 1f)
                val s = d.samples
                for (k in s.indices) {
                    val a = s[k]
                    val b = s.getOrNull(k + 1)
                    val t = signalShare(a.rssi.toFloat())
                    val col = lerp(muted, green, t).copy(alpha = if (f.quiet) .55f else 1f)
                    if (b != null && b.ms - a.ms <= 2500) drawLine(col, Offset(x(a.ms), cy), Offset(x(b.ms), cy), sc * (.45f + .55f * t), StrokeCap.Butt)
                    else drawLine(col, Offset(x(a.ms), cy), Offset(x(a.ms) + 2f, cy), sc * (.45f + .55f * t))
                }
                // Gone quiet: a dotted thread from the last reading to now.
                val last = d.lastSeenMs
                if (f.quiet && last != null) drawLine(muted.copy(alpha = .6f), Offset(x(last), cy), Offset(x(facts.now), cy), 1f, pathEffect = dashed)
                d.firstSeenMs?.let { drawLine(if (f.fresh) amber else edge, Offset(x(it), top + 1f), Offset(x(it), top + rowH - 1f), if (f.fresh) 2.dp.toPx() else 1f) }
                if (labels) {
                    val tint = stateTint(d)
                    if (d.connected == true || d.paired == true) drawCircle(tint, 3.dp.toPx(), Offset(6.dp.toPx(), cy))
                    val t = names[i]
                    drawText(t, color = if (f.quiet) muted else ink, topLeft = Offset(14.dp.toPx(), cy - t.size.height / 2f))
                    val v = dbm[i]
                    drawText(v, color = if (f.quiet) muted else lerp(muted, green, signalShare(f.signal)), topLeft = Offset(plotR + 8.dp.toPx(), cy - v.size.height / 2f))
                    if (f.fresh) drawText(newText, color = amber, topLeft = Offset(w - newText.size.width - 2f, cy - newText.size.height / 2f))
                } else {
                    // Thin rows: a state colour mark in the gutter still shows paired and connected devices.
                    if (d.connected == true || d.paired == true) drawRect(stateTint(d), Offset(gutter - 10.dp.toPx(), top), Size(6.dp.toPx(), (rowH - 1f).coerceAtLeast(1f)))
                    if (f.fresh) drawRect(amber, Offset(plotR + 4.dp.toPx(), top), Size(6.dp.toPx(), (rowH - 1f).coerceAtLeast(1f)))
                }
            }
            if (!labels) rows.take(shown).indexOfFirst { it.device.address == mark }.takeIf { it >= 0 }?.let { i ->
                val t = measurer.measure(if (showcase) "••••••" else rows[i].device.name?.takeIf { it.isNotBlank() } ?: "Unnamed device", name)
                val top = (axis + i * rowH + rowH / 2f - t.size.height / 2f).coerceIn(axis, h - t.size.height)
                drawRect(raised, Offset(0f, top), Size(gutter - 12.dp.toPx(), t.size.height.toFloat()))
                drawText(t, color = amber, topLeft = Offset(14.dp.toPx(), top))
            }
            if (!labels && shown > 0) {
                drawText(measurer.measure("${shown} STRONGEST", small), color = muted, topLeft = Offset(0f, axis))
            }
            if (restText != null) {
                val top = axis + shown * rowH + 4f
                drawText(restText, color = muted, topLeft = Offset(0f, top))
                // Their readings as one density band.
                rows.drop(shown).forEach { f -> f.device.samples.forEach { drawLine(muted.copy(alpha = .35f), Offset(x(it.ms), top + 2f), Offset(x(it.ms), top + rowH * 1.6f), 1f) } }
            }
        }
    }.pointerInput(rows) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                val p = e.changes.firstOrNull()?.position ?: continue
                val axis = geometry[0]; val rowH = geometry[1]; val shown = geometry[3].toInt()
                val i = ((p.y - axis) / rowH).toInt()
                val hit = if (p.y >= axis && i in 0 until shown) drawn[0].getOrNull(i)?.device?.address else null
                when (e.type) { PointerEventType.Exit -> onFocusNow(null); PointerEventType.Press -> hit?.let(onSelectNow); else -> onFocusNow(hit) }
            }
        }
    }) {
        // The only moving part: "now".
        Spacer(Modifier.matchParentSize().graphicsLayer().drawWithCache {
            val w = size.width
            val gutter = if (w >= 700.dp.toPx()) 190.dp.toPx() else 120.dp.toPx()
            val plotW = (w - 70.dp.toPx() - gutter).coerceAtLeast(1f)
            val amber = Tech.Amber; val axis = 16.dp.toPx()
            onDrawBehind {
                val now = clock.ms.longValue
                if (now < window) { val nx = gutter + (now.toFloat() / window).coerceIn(0f, 1f) * plotW; drawLine(amber.copy(alpha = .7f), Offset(nx, axis), Offset(nx, size.height), 1.5f) }
            }
        })
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// C. Proximity lanes: near, mid and far lanes of tiles; a tile slides to another lane when its signal moves.
// ---------------------------------------------------------------------------------------------------------------------

private enum class TileMode(val width: Dp, val height: Dp) { Full(184.dp, 44.dp), Compact(128.dp, 22.dp) }

private class LanePlan(val band: BtBand, val y: Dp, val height: Dp, val mode: TileMode, val cols: Int, val items: List<HeardFact>, val hidden: Int)

private const val LaneHeader = 20f
private const val TileGap = 6f

private fun planLanes(lanes: Map<BtBand, List<HeardFact>>, width: Dp, height: Dp, mark: String?): List<LanePlan> {
    val bands = BtBand.entries
    val modes = bands.associateWith { TileMode.Full }.toMutableMap()
    fun cols(m: TileMode) = floor((width.value - 8f + TileGap) / (m.width.value + TileGap)).toInt().coerceAtLeast(1)
    fun need(b: BtBand, m: TileMode): Float {
        val n = lanes[b].orEmpty().size
        val rows = if (n == 0) 1 else ceil(n / cols(m).toFloat()).toInt()
        return LaneHeader + rows * (m.height.value + TileGap) + 4f
    }
    fun total() = bands.sumOf { need(it, modes.getValue(it)).toDouble() }.toFloat()
    // Far lanes give up their full tiles first; the near lane keeps them longest.
    for (b in bands.reversed()) if (total() > height.value) modes[b] = TileMode.Compact
    var y = 0f
    val plans = mutableListOf<LanePlan>()
    val over = total() > height.value
    val budget = height.value
    bands.forEach { b ->
        val m = modes.getValue(b); val c = cols(m)
        val items = lanes[b].orEmpty()
        val h = if (!over) need(b, m) else {
            // Everything compact and still too tall: share the height by need, at least one row each.
            val share = need(b, m) / total() * budget
            share.coerceAtLeast(LaneHeader + m.height.value + TileGap + 4f)
        }
        val rows = floor((h - LaneHeader - 4f) / (m.height.value + TileGap)).toInt().coerceAtLeast(1)
        val capacity = rows * c
        val shown = if (items.size <= capacity) items else items.take(capacity - 1).let { kept ->
            val marked = items.firstOrNull { it.device.address == mark }
            if (marked == null || marked in kept || kept.isEmpty()) kept else kept.dropLast(1) + marked
        }
        plans += LanePlan(b, y.dp, h.dp, m, c, shown, items.size - shown.size)
        y += h
    }
    return plans
}

@Composable private fun ProximityLanes(facts: HeardFacts, mark: String?, onFocus: (String?) -> Unit, onSelect: (String) -> Unit, modifier: Modifier) {
    val ranking = remember { SteadyRanking<String>(margin = .08, floor = 2.0) }
    val order = remember(facts) { ranking.rank(facts.heard.associate { it.device.address to (it.signal + 110.0) }) }
    val byAddress = remember(facts) { facts.heard.associateBy { it.device.address } }
    val lanes = remember(order, byAddress) { order.mapNotNull { byAddress[it] }.groupBy { it.band } }
    val reduce = LocalMotion.current.reduced
    BoxWithConstraints(modifier.clipToBounds()) {
        val plans = remember(lanes, maxWidth, maxHeight, mark) { planLanes(lanes, maxWidth, maxHeight, mark) }
        plans.forEach { plan ->
            val rail = when (plan.band) { BtBand.Near -> Tech.Green; BtBand.Mid -> Tech.Ink; BtBand.Far -> Tech.Muted }
            Box(Modifier.offset(y = plan.y).fillMaxWidth().height(plan.height - 2.dp).background(Tech.Black.copy(alpha = .18f)).drawBehind {
                drawRect(rail.copy(alpha = .8f), size = Size(2.dp.toPx(), size.height))
            }) {
                Row(Modifier.padding(start = 10.dp, top = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(plan.band.label, fontFamily = Tech.Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = rail, maxLines = 1)
                    Text("${lanes[plan.band].orEmpty().size} · ${plan.band.range}", fontFamily = Tech.Mono, fontSize = 9.sp, color = Tech.Muted, maxLines = 1)
                }
            }
        }
        // Tiles are children of the whole area, keyed by address, so one can slide from a lane to another.
        plans.forEach { plan ->
            plan.items.forEachIndexed { i, f ->
                key(f.device.address) {
                    val col = i % plan.cols; val row = i / plan.cols
                    val target = with(androidx.compose.ui.platform.LocalDensity.current) {
                        IntOffset((8f + col * (plan.mode.width.value + TileGap)).dp.roundToPx(), (plan.y.value + LaneHeader + row * (plan.mode.height.value + TileGap)).dp.roundToPx())
                    }
                    val offset by animateIntOffsetAsState(target, if (reduce) snap() else spring(.9f, 500f), label = "tile")
                    Tile(f, plan.mode, f.device.address == mark, onFocus, onSelect, Modifier.offset { offset }.size(plan.mode.width, plan.mode.height))
                }
            }
            if (plan.hidden > 0) {
                val i = plan.items.size
                val col = i % plan.cols; val row = i / plan.cols
                Box(Modifier.offset((8f + col * (plan.mode.width.value + TileGap)).dp, (plan.y.value + LaneHeader + row * (plan.mode.height.value + TileGap)).dp)
                    .size(plan.mode.width, plan.mode.height).border(1.dp, Tech.Edge, Tech.Control), contentAlignment = Alignment.Center) {
                    Text("+${plan.hidden} more", fontFamily = Tech.Mono, fontSize = 10.sp, lineHeight = 12.sp, color = Tech.Muted, maxLines = 1)
                }
            }
        }
    }
}

@Composable private fun Tile(f: HeardFact, mode: TileMode, marked: Boolean, onFocus: (String?) -> Unit, onSelect: (String) -> Unit, modifier: Modifier) {
    val d = f.device
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onFocus(d.address) else if (marked) onFocus(null) }
    val tint = stateTint(d)
    val unnamed = d.name.isNullOrBlank()
    Box(modifier.alpha(if (f.quiet) .5f else 1f).background(if (marked || hovered) Tech.Raised else Tech.Panel, Tech.Control)
        .border(1.dp, if (marked) Tech.Amber else if (f.fresh) Tech.Amber.copy(alpha = .6f) else Tech.Edge, Tech.Control)
        .hoverable(interaction).clickable(interactionSource = interaction, indication = null) { onSelect(d.address) }) {
        when (mode) {
            TileMode.Full -> Row(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(tint, androidx.compose.foundation.shape.CircleShape))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (unnamed) "Unnamed device" else d.name.orEmpty(), Modifier.identity(), fontFamily = Tech.Mono, fontSize = 11.sp, lineHeight = 14.sp, color = if (unnamed) Tech.Muted else Tech.Ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${f.signal.roundToInt()} dBm", fontFamily = Tech.Mono, fontSize = 9.sp, lineHeight = 11.sp, color = lerp(Tech.Muted, Tech.Green, signalShare(f.signal)), maxLines = 1)
                        MiniTrend(d, Modifier.width(44.dp).height(10.dp))
                    }
                }
                when {
                    f.fresh -> Text("NEW", fontFamily = Tech.Mono, fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold, color = Tech.Amber)
                    f.quiet -> Text("QUIET", fontFamily = Tech.Mono, fontSize = 9.sp, lineHeight = 11.sp, color = Tech.Muted)
                    d.connected == true -> Text("CONN", fontFamily = Tech.Mono, fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold, color = Tech.Green)
                    d.paired == true -> Text("PAIRED", fontFamily = Tech.Mono, fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold, color = Tech.Amber)
                }
            }
            TileMode.Compact -> Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(6.dp).background(if (f.fresh) Tech.Amber else tint, androidx.compose.foundation.shape.CircleShape))
                Text(if (unnamed) "Unnamed" else d.name.orEmpty(), Modifier.weight(1f).identity(), fontFamily = Tech.Mono, fontSize = 9.sp, lineHeight = 11.sp, color = if (unnamed) Tech.Muted else Tech.Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${f.signal.roundToInt()}", fontFamily = Tech.Mono, fontSize = 9.sp, lineHeight = 11.sp, color = lerp(Tech.Muted, Tech.Green, signalShare(f.signal)), maxLines = 1)
            }
        }
    }
}

/** The last readings as a short line: how the signal moved, which way it is heading. */
@Composable private fun MiniTrend(d: BtDevice, modifier: Modifier) {
    val s = d.samples.takeLast(16)
    Spacer(modifier.drawWithCache {
        val green = Tech.Green; val edge = Tech.Edge
        val pts = s.mapIndexed { i, v -> Offset(if (s.size == 1) size.width else size.width * i / (s.size - 1), size.height - size.height * ((v.rssi + 100).coerceIn(0, 70) / 70f)) }
        onDrawBehind {
            drawLine(edge.copy(alpha = .5f), Offset(0f, size.height), Offset(size.width, size.height), 1f)
            pts.zipWithNext { a, b -> drawLine(green, a, b, 1.dp.toPx()) }
            pts.lastOrNull()?.let { drawCircle(green, 1.5.dp.toPx(), it) }
        }
    })
}
