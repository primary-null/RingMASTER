@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The Wi-Fi view: where to scan, the adapters, the current link as a readout with a live signal line, the channels in use on
 * 2.4, 5 and 6 GHz, and the networks in range grouped by name. Network names and access-point addresses stay hidden until
 * the user turns on Show names; Showcase keeps them hidden. Nothing here is written to logs or diagnostics.
 */

private val wifiTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)

/** The Show names switch: off when the app starts, for this run only, and always off in Showcase. */
internal object WifiNames {
    var chosen by mutableStateOf(false)
    val shown: Boolean get() = chosen && !Appearance.showcase
}

internal const val ADDRESS_HIDDEN = "Address hidden"

/** Networks by strongest first; a hidden name becomes "Network A", "Network B"… in that order, so the picture and list agree. */
internal fun orderedNetworks(networks: List<WifiNetworkView>) = networks.sortedWith(compareByDescending<WifiNetworkView> { it.connected }.thenByDescending { it.best ?: -200 }.thenBy { it.ssid })

internal fun networkLetter(index: Int): String = if (index < 26) ('A' + index).toString() else "${'A' + index / 26 - 1}${'A' + index % 26}"

internal fun networkLabel(net: WifiNetworkView, index: Int, show: Boolean): String = when {
    !show -> "Network ${networkLetter(index)}"
    net.ssid.isBlank() -> "Hidden network"
    else -> net.ssid
}

internal fun apAddress(bssid: String?, show: Boolean): String = if (show && !bssid.isNullOrBlank()) bssid else ADDRESS_HIDDEN

internal fun rateText(mbps: Double?): String = when {
    mbps == null -> "—"
    mbps >= 1000 -> String.format(Locale.ROOT, "%.2f Gbit/s", mbps / 1000)
    else -> String.format(Locale.ROOT, "%.0f Mbit/s", mbps)
}

/** The whole Wi-Fi view, fed by [feed]. A scan inside a guest is confirmed first. */
@Composable fun WifiView(feed: WifiFeed, modifier: Modifier = Modifier) {
    val panel by feed.panel.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    val show = WifiNames.shown
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text("SOURCE", fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Muted)
            panel.sources.forEach { s ->
                SourceChip(s, s.key == panel.source) { if (s.key != panel.source) feed.choose(s.key) }
            }
            Spacer(Modifier.width(6.dp))
            val chosen = panel.chosen
            MiniAction(if (panel.scanning) "Scanning…" else "Scan", primary = true, enabled = !panel.scanning && chosen != null && chosen.refusal == null && panel.refusal == null) {
                if (chosen?.inGuest == true) confirm = true else feed.refresh()
            }
            ShowNamesSwitch()
        }
        Text(wifiStatus(panel), Modifier.fillMaxWidth().identityIf(panel.chosen?.inGuest == true), fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = if (panel.scanning) Tech.Amber else Tech.Muted, maxLines = 2)
        panel.error?.let { Text(it, fontSize = 12.sp, color = Tech.Amber) }
        panel.note?.let { RadioPanel(Modifier.widthIn(max = 760.dp).fillMaxWidth(), rail = Tech.Amber, padding = 10.dp) { RadioNoteLine(it) } }
        val refusal = panel.refusal ?: panel.chosen?.refusal
        if (refusal != null) RadioPanel(Modifier.widthIn(max = 760.dp).fillMaxWidth(), rail = Tech.Amber) { RadioNoteLine(refusal.copy(warning = true)) }
        else WifiReadout(panel, show, Modifier.fillMaxWidth())
    }
    val cancel = remember { FocusRequester() }
    if (confirm) panel.chosen?.let { source ->
        TechDialog("Scan for Wi-Fi inside this VM?", { confirm = false }, initialFocus = cancel, actions = {
            TechAction("Scan", active = true) { confirm = false; feed.refresh() }
            TechAction("Cancel", Modifier.focusRequester(cancel)) { confirm = false }
        }) { Text(GUEST_WIFI_CONFIRM, fontSize = 13.sp, lineHeight = 19.sp) }
    }
}

internal const val GUEST_WIFI_CONFIRM = "RingMASTER will list the Wi-Fi networks the VM's adapter can hear, through the QEMU guest agent. Nothing is joined or changed."

internal fun wifiStatus(p: WifiPanel): String = listOfNotNull(
    p.chosen?.label?.uppercase(),
    when {
        p.scanning -> "SCANNING"
        p.at != null -> "${p.networks.size} NETWORKS · ${p.networks.sumOf { it.aps.size }} ACCESS POINTS · ${wifiTime.format(p.at)}"
        p.refusal == null && p.chosen?.refusal == null -> "NO SCAN YET"
        else -> null
    },
).joinToString("  ·  ")

@Composable private fun SourceChip(s: WifiSourceView, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.heightIn(min = 28.dp).border(1.dp, if (on) Tech.Amber else Tech.Edge, Tech.Control).background(if (on) Tech.AmberWash else Color.Transparent)
        .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (s.inGuest) IdentityTile("VM") else HostGlyph(RadioHostKind.ThisComputer, Modifier.size(14.dp))
        Text(s.label, Modifier.identityIf(s.inGuest), fontSize = 12.sp, color = if (on) Tech.Amber else Tech.Ink, maxLines = 1)
        s.detail?.let { Text(it, fontFamily = Tech.Mono, fontSize = 9.sp, color = Tech.Muted, maxLines = 1) }
    }
}

@Composable internal fun ShowNamesSwitch() {
    val showcase = Appearance.showcase
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TechSwitch(WifiNames.shown, if (showcase) null else ({ WifiNames.chosen = it }), enabled = !showcase)
        Text(if (showcase) "Show names (off in Showcase)" else "Show names", fontSize = 12.sp, color = Tech.Ink)
    }
}

/** Adapters, link, channels and networks of one scan. Used by the Wi-Fi view and by a guest's Radios tab. */
@Composable internal fun WifiReadout(panel: WifiPanel, show: Boolean, modifier: Modifier = Modifier) {
    val ordered = remember(panel.networks) { orderedNetworks(panel.networks) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 1100.dp
            val adapters: @Composable () -> Unit = {
                if (panel.adapters.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    panel.adapters.forEach { WifiAdapterCard(it, show) }
                }
            }
            val link: @Composable (Modifier) -> Unit = { m -> LinkReadout(panel.link, ordered, show, m) }
            val channels: @Composable (Modifier) -> Unit = { m -> ChannelOccupancy(ordered, show, m) }
            // Wide: the link and the adapters on the left, the channels beside them. Narrower: one column.
            if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { link(Modifier.fillMaxWidth()); adapters() }
                channels(Modifier.weight(1.25f))
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { adapters(); link(Modifier.fillMaxWidth()); channels(Modifier.fillMaxWidth()) }
        }
        NetworkList(ordered, show)
    }
}

@Composable private fun WifiAdapterCard(a: WifiAdapterView, show: Boolean) {
    RadioPanel(Modifier.widthIn(min = 260.dp, max = 420.dp), rail = radioTint(RadioKind.WiFi), padding = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioGlyph(RadioKind.WiFi, size = 16.dp)
            Text(a.description ?: a.name, Modifier.weight(1f, fill = false), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Tech.Ink, maxLines = 2)
            when { !a.enabled -> AttentionTag("Off"); a.connected -> HealthyTag("Connected"); else -> NeutralTag("Not connected") }
        }
        Text(listOfNotNull(a.name.takeIf { a.description != null }, a.driver?.let { "driver $it" }, a.bands.takeIf { it.isNotEmpty() }?.joinToString(" / ") { it.label },
            a.address?.let { if (show) it else ADDRESS_HIDDEN }).joinToString("  ·  "), fontFamily = Tech.Mono, fontSize = 10.sp, lineHeight = 14.sp, color = Tech.Muted)
    }
}

@Composable private fun LinkReadout(link: WifiLinkView?, ordered: List<WifiNetworkView>, show: Boolean, modifier: Modifier) {
    RadioPanel(modifier, rail = if (link != null) Tech.Green else Tech.Edge, padding = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Current link")
            if (link == null) { Text("Not connected to a network.", fontSize = 12.sp, color = Tech.Muted); return@Column }
            val index = ordered.indexOfFirst { it.connected }.coerceAtLeast(0)
            val name = ordered.getOrNull(index)?.let { networkLabel(it, index, show) } ?: (if (show) link.ssid.ifBlank { "Hidden network" } else "Network A")
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(name, Modifier.identityIf(show), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(apAddress(link.bssid, show), Modifier.identityIf(show), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SignalBars(link.signalDbm, width = 26.dp, height = 18.dp)
                        Text(link.signalDbm?.let { "$it dBm" } ?: "—", fontFamily = Tech.Mono, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = wifiSignalTint(link.signalDbm))
                    }
                    Text(signalWord(link.signalDbm) + (link.quality?.let { " · $it %" } ?: ""), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted)
                }
            }
            SignalLine(link.history, Modifier.fillMaxWidth().height(120.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailFact("Channel", listOfNotNull(link.channel?.toString(), link.band?.label, link.widthMhz?.let { "$it MHz" }).joinToString(" · ").ifBlank { "—" })
                DetailFact("Generation", link.generation ?: "—")
                DetailFact("Rate", "↓ ${rateText(link.rxMbps)}  ↑ ${rateText(link.txMbps)}")
                DetailFact("Security", link.security ?: "—")
            }
        }
    }
}

/**
 * The signal of the current link over its last readings, on a fixed -90 to -30 dBm axis so a fall reads as a fall. Hovering
 * shows the reading under the pointer.
 */
@Composable internal fun SignalLine(history: List<Int?>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    var hover by remember { mutableStateOf<Int?>(null) }
    val ink = Tech.Ink; val muted = Tech.Muted; val edge = Tech.Edge; val green = Tech.Green; val amber = Tech.Amber; val ground = Tech.Black
    val last = history.lastOrNull { it != null }
    Canvas(modifier.background(ground.copy(alpha = .35f)).semantics { contentDescription = "Signal over the last ${history.size} readings" + (last?.let { ", now $it dBm" } ?: "") }
        .pointerInput(history.size) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    val p = e.changes.firstOrNull()?.position
                    hover = if (e.type == PointerEventType.Exit || p == null || history.size < 2) null
                    else (((p.x - 40.dp.toPx()) / (size.width - 48.dp.toPx())) * (history.size - 1)).toInt().coerceIn(0, history.size - 1)
                }
            }
        }) {
        val label = TextStyle(fontFamily = Tech.Mono, fontSize = 9.sp, color = muted)
        val unit = measurer.measure("dBm", label)
        // The unit sits above the top tick, so the top of the plot leaves a line of text for it at any text size.
        val left = 40.dp.toPx(); val right = size.width - 8.dp.toPx(); val top = unit.size.height * 1.6f; val bottom = size.height - unit.size.height - 4f
        fun y(dbm: Int) = bottom - (dbm.coerceIn(-90, -30) + 90) / 60f * (bottom - top)
        for (tick in listOf(-90, -75, -60, -45, -30)) {
            val ty = y(tick)
            drawLine(edge, Offset(left, ty), Offset(right, ty), 1f, pathEffect = if (tick == -90) null else PathEffect.dashPathEffect(floatArrayOf(2f, 4f)))
            val t = measurer.measure("$tick", label)
            drawText(t, topLeft = Offset(left - t.size.width - 6f, ty - t.size.height / 2f))
        }
        drawText(unit, topLeft = Offset(left - unit.size.width - 6f, 0f))
        val older = measurer.measure("older", label)
        drawText(older, topLeft = Offset(left, bottom + 2f))
        val now = measurer.measure("now", label)
        drawText(now, topLeft = Offset(right - now.size.width, bottom + 2f))
        if (history.size < 2) return@Canvas
        fun x(i: Int) = left + (right - left) * i / (history.size - 1)
        val line = Path(); val area = Path(); var started = false
        history.forEachIndexed { i, v ->
            if (v == null) { started = false; return@forEachIndexed }
            if (!started) { line.moveTo(x(i), y(v)); area.moveTo(x(i), bottom); area.lineTo(x(i), y(v)); started = true }
            else { line.lineTo(x(i), y(v)); area.lineTo(x(i), y(v)) }
        }
        val lastIndex = history.indexOfLast { it != null }
        if (lastIndex >= 0) { area.lineTo(x(lastIndex), bottom); area.close() }
        val tone = if ((last ?: -100) >= -65) green else amber
        drawPath(area, Brush.verticalGradient(listOf(tone.copy(alpha = .28f), tone.copy(alpha = .02f)), top, bottom))
        drawPath(line, tone, style = Stroke(2.dp.toPx()))
        if (lastIndex >= 0) drawCircle(tone, 3.5.dp.toPx(), Offset(x(lastIndex), y(history[lastIndex]!!)))
        hover?.let { i ->
            val v = history.getOrNull(i) ?: return@let
            drawLine(ink.copy(alpha = .5f), Offset(x(i), top), Offset(x(i), bottom), 1f)
            drawCircle(ink, 3.dp.toPx(), Offset(x(i), y(v)))
            val t = measurer.measure("$v dBm", TextStyle(fontFamily = Tech.Mono, fontSize = 10.sp, color = ink, fontWeight = FontWeight.Bold))
            val tx = (x(i) + 6f).coerceAtMost(right - t.size.width)
            drawRect(ground, Offset(tx - 3f, top), Size(t.size.width + 6f, t.size.height.toFloat()))
            drawText(t, topLeft = Offset(tx, top))
        }
    }
}

/** Channel axis of a band: the first and last channel drawn and the labelled ticks. */
internal data class BandAxis(val band: WifiBand, val first: Int, val last: Int, val ticks: List<Int>)

internal fun bandAxis(band: WifiBand) = when (band) {
    WifiBand.B24 -> BandAxis(band, -1, 15, (1..13).toList())
    WifiBand.B5 -> BandAxis(band, 28, 173, listOf(36, 52, 68, 100, 116, 132, 149, 165))
    WifiBand.B6 -> BandAxis(band, -7, 241, listOf(1, 33, 65, 97, 129, 161, 193, 225))
}

/** How many channel numbers one side of a channel [widthMhz] wide covers (channel numbers are 5 MHz apart). */
internal fun channelHalfSpan(widthMhz: Int?) = (widthMhz ?: 20) / 10f

@Composable private fun ChannelOccupancy(ordered: List<WifiNetworkView>, show: Boolean, modifier: Modifier) {
    RadioPanel(modifier, padding = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Channels in use")
            for (band in WifiBand.entries) {
                val aps = ordered.flatMapIndexed { i, n -> n.aps.filter { it.band == band && it.channel != null }.map { Triple(i, n, it) } }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(band.label, Modifier.weight(1f), fontFamily = Tech.Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Tech.Ink)
                        Text(when (aps.size) { 0 -> "No access points heard"; 1 -> "1 access point"; else -> "${aps.size} access points" }, fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted)
                    }
                    ChannelBand(bandAxis(band), aps, show, Modifier.fillMaxWidth().height(if (band == WifiBand.B24) 120.dp else 96.dp))
                }
            }
        }
    }
}

@Composable private fun ChannelBand(axis: BandAxis, aps: List<Triple<Int, WifiNetworkView, WifiApView>>, show: Boolean, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val muted = Tech.Muted; val edge = Tech.Edge; val amber = Tech.Amber; val ground = Tech.Black; val ink = Tech.Ink
    val colors = MetricColors.categorical.drop(1)
    val contrast = Appearance.palette.highContrast
    val described = aps.joinToString(", ") { (i, n, ap) -> "${networkLabel(n, i, show)} on channel ${ap.channel}" }
    Canvas(modifier.background(ground.copy(alpha = .35f)).semantics { contentDescription = "${axis.band.label}: ${described.ifBlank { "none" }}" }) {
        val left = 6.dp.toPx(); val right = size.width - 6.dp.toPx(); val top = 14.dp.toPx(); val bottom = size.height - 14.dp.toPx()
        fun x(ch: Float) = left + (right - left) * (ch - axis.first) / (axis.last - axis.first)
        fun y(dbm: Int) = bottom - (dbm.coerceIn(-95, -30) + 95) / 65f * (bottom - top)
        drawLine(edge, Offset(left, bottom), Offset(right, bottom), 1f)
        val tickStyle = TextStyle(fontFamily = Tech.Mono, fontSize = 8.sp, color = muted)
        for (t in axis.ticks) {
            drawLine(edge, Offset(x(t.toFloat()), bottom), Offset(x(t.toFloat()), bottom + 3f), 1f)
            val m = measurer.measure("$t", tickStyle)
            drawText(m, topLeft = Offset(x(t.toFloat()) - m.size.width / 2f, bottom + 3f))
        }
        // Weakest first, so a strong network is drawn over the ones it drowns out.
        clipRect(left, 0f, right, bottom + 1f) { for ((i, _, ap) in aps.sortedBy { it.third.signalDbm ?: -100 }) {
            val c = ap.channel!!.toFloat(); val half = channelHalfSpan(ap.widthMhz)
            val peak = y(ap.signalDbm ?: -95)
            val tint = if (ap.connected) amber else colors[i % colors.size]
            val shape = Path().apply {
                moveTo(x(c - half - 1f), bottom)
                cubicTo(x(c - half), bottom, x(c - half), peak, x(c - half * .5f), peak)
                lineTo(x(c + half * .5f), peak)
                cubicTo(x(c + half), peak, x(c + half), bottom, x(c + half + 1f), bottom)
            }
            drawPath(shape, tint.copy(alpha = if (contrast) .10f else .16f))
            drawPath(shape, tint, style = Stroke((if (ap.connected) 2.4f else 1.3f).dp.toPx(), pathEffect = if (ap.connected) null else if (contrast) PathEffect.dashPathEffect(floatArrayOf(5f, 3f)) else null))
        } }
        // Labels strongest first: one that would cover another moves up a line; with no room left it is left out (the list names it).
        val placed = ArrayList<androidx.compose.ui.geometry.Rect>()
        for ((i, n, ap) in aps.sortedByDescending { it.third.signalDbm ?: -100 }) {
            val label = (if (show) networkLabel(n, i, true).take(14) else networkLetter(i)) + if (ap.connected) " ●" else ""
            val m = measurer.measure(label, TextStyle(fontFamily = Tech.Mono, fontSize = 9.sp, color = if (ap.connected) amber else ink, fontWeight = if (ap.connected) FontWeight.Bold else FontWeight.Normal))
            val lx = (x(ap.channel!!.toFloat()) - m.size.width / 2f).coerceIn(0f, size.width - m.size.width)
            var ly = y(ap.signalDbm ?: -95) - m.size.height - 1f
            fun box() = androidx.compose.ui.geometry.Rect(lx - 2f, ly, lx + m.size.width + 2f, ly + m.size.height)
            while (ly >= 0f && placed.any { it.overlaps(box()) }) ly -= m.size.height
            if (ly < 0f) continue
            placed += box()
            drawText(m, topLeft = Offset(lx, ly))
        }
        if (aps.isEmpty()) drawRoundRect(edge.copy(alpha = .4f), Offset(left, bottom - 2f), Size(right - left, 2f), CornerRadius(1f))
    }
}

@Composable private fun NetworkList(ordered: List<WifiNetworkView>, show: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("Networks in range", suffix = "${ordered.size}")
        if (ordered.isEmpty()) Text("No networks heard.", fontSize = 12.sp, color = Tech.Muted)
        ordered.forEachIndexed { i, n ->
            Column(Modifier.fillMaxWidth().border(1.dp, if (n.connected) Tech.Green else Tech.Edge, Tech.Control).background(Tech.Black.copy(alpha = .25f)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(networkLetter(i), Modifier.width(22.dp), fontFamily = Tech.Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (n.connected) Tech.Amber else MetricColors.categorical.drop(1)[i % 4])
                    Text(networkLabel(n, i, show), Modifier.widthIn(min = 140.dp).identityIf(show), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Tech.Ink)
                    if (n.connected) HealthyTag("Connected")
                    n.security?.let { if (it.equals("Open", ignoreCase = true)) AttentionTag("Open", help = "This network has no password: anyone nearby can read what is sent on it.") else NeutralTag(it) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SignalBars(n.best)
                        Text("${n.best?.let { "$it dBm" } ?: "—"} · ${signalWord(n.best)}", fontFamily = Tech.Mono, fontSize = 10.sp, color = wifiSignalTint(n.best))
                    }
                    Text(n.bands.joinToString(" + ") { it.label } + " · " + if (n.aps.size == 1) "1 access point" else "${n.aps.size} access points", fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted)
                }
                n.aps.sortedByDescending { it.signalDbm ?: -200 }.forEach { ap ->
                    Text(listOfNotNull(apAddress(ap.bssid, show), ap.channel?.let { "ch $it" }, ap.band?.label, ap.widthMhz?.let { "$it MHz" }, ap.generation, ap.signalDbm?.let { "$it dBm" },
                        if (ap.connected) "in use" else null).joinToString("  ·  "),
                        Modifier.padding(start = 32.dp).identityIf(show), fontFamily = Tech.Mono, fontSize = 10.sp, lineHeight = 14.sp, color = Tech.Muted)
                }
            }
        }
    }
}
