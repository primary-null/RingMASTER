@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import local.ringleader.core.BluetoothScanner
import local.ringleader.core.TickerTarget

/*
 * The Radios page (it replaced the Bluetooth page in the rail): Map, Bluetooth and Wi-Fi. The Bluetooth view is the old
 * Bluetooth page, unchanged. Everything here reads; nothing attaches, detaches, pairs or connects.
 */

private val radiosTime = DateTimeFormatter.ofPattern("HH:mm 'UTC'").withZone(ZoneOffset.UTC)

/** The view last shown, kept while the app runs, so coming back to Radios lands where the user left it. */
internal object RadiosPageMemory { var view by mutableStateOf(RadiosView.Map) }

/**
 * The Radios page. [request] opens a view and lights an item (from the Fleet strip, a guest mark, a node or a guest page);
 * [onRequestHandled] clears it. The ticker's Bluetooth target opens the Bluetooth view.
 */
@Composable fun RadiosScreen(hub: RadiosHub, scanner: BluetoothScanner?, request: RadiosRequest? = null, onRequestHandled: () -> Unit = {}) {
    val board by hub.board.board.collectAsState()
    var view by remember { mutableStateOf(RadiosPageMemory.view) }
    LaunchedEffect(view) { RadiosPageMemory.view = view }
    var selected by remember { mutableStateOf<String?>(null) }
    var glow by remember { mutableStateOf<Pair<String, Long>?>(null) }
    LaunchedEffect(request?.nonce) {
        val r = request ?: return@LaunchedEffect
        view = r.target.view
        r.target.item?.let { selected = it; glow = it to r.nonce }
        onRequestHandled()
    }
    val pageFocus = LocalPageFocus.current
    val asked = pageFocus?.take { it is TickerTarget.Bluetooth }
    LaunchedEffect(asked?.token) { if (asked != null) { view = RadiosView.Bluetooth; pageFocus.consume(asked) } }
    val onScan: (RadioCard) -> Unit = { card ->
        when {
            card.scan == RadioScanRoute.ThisComputer && card.kind == RadioKind.Bluetooth -> view = RadiosView.Bluetooth
            card.scan == RadioScanRoute.ThisComputer -> { hub.wifi.choose(THIS_COMPUTER_KEY); view = RadiosView.WiFi }
            card.kind == RadioKind.WiFi -> card.holder?.let { hub.wifi.choose(it.guest.key); view = RadiosView.WiFi }
            else -> card.holder?.let { hub.nav.openGuest(it.guest, "Radios") }
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            RadiosViewSwitch(view, board) { view = it }
            if (view == RadiosView.Map) {
                Spacer(Modifier.width(4.dp))
                MiniAction(if (board.loading) "Reading…" else "Refresh", enabled = !board.loading) { hub.board.refresh() }
                Text(board.readAt?.let { "READ ${radiosTime.format(it)}" }.orEmpty(),
                    fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Muted)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (view) {
                RadiosView.Map -> RadiosMapView(board, selected, { selected = it }, glow, hub.nav, onScan)
                RadiosView.Bluetooth -> BluetoothScreen(scanner)
                RadiosView.WiFi -> WifiPage(hub.wifi)
            }
        }
    }
}

@Composable private fun WifiPage(feed: WifiFeed) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 12.dp)) { WifiView(feed) }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight(), style = visibleScrollbarStyle())
    }
}

/** Map, Bluetooth and Wi-Fi as three tabs, each with its glyph and how many adapters of that kind the board has. */
@Composable private fun RadiosViewSwitch(view: RadiosView, board: RadiosBoard, onView: (RadiosView) -> Unit) {
    Row(Modifier.drawBehind { drawLine(Tech.Edge, Offset(0f, size.height - .5f), Offset(size.width, size.height - .5f), 1f) }, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        RadiosView.entries.forEach { v ->
            val on = v == view
            val count = when (v) { RadiosView.Map -> board.radios.size; RadiosView.Bluetooth -> board.count(RadioKind.Bluetooth); RadiosView.WiFi -> board.count(RadioKind.WiFi) }
            Row(Modifier.heightIn(min = 32.dp).clickable(role = Role.Tab) { onView(v) }
                .drawBehind { if (on) drawRect(Tech.Amber, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx())) }
                .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (v) {
                    RadiosView.Map -> HostGlyph(RadioHostKind.Node, Modifier.size(14.dp))
                    RadiosView.Bluetooth -> RadioGlyph(RadioKind.Bluetooth, size = 14.dp)
                    RadiosView.WiFi -> RadioGlyph(RadioKind.WiFi, size = 14.dp)
                }
                Text(v.label.uppercase(), fontFamily = Tech.Mono, fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, letterSpacing = .8.sp, color = if (on) Tech.Amber else Tech.Muted, maxLines = 1)
                if (count > 0) Text("$count", fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Ink, maxLines = 1)
            }
        }
    }
}

/** The Map view: tallies, notes, the drawn map with the selection beside or below it, and the same facts in words. */
@Composable internal fun RadiosMapView(board: RadiosBoard, selected: String?, onSelect: (String?) -> Unit, glow: Pair<String, Long>?, nav: RadiosNav, onScan: (RadioCard) -> Unit) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RadiosTally(board)
            board.notes.forEach { note -> RadioPanel(Modifier.widthIn(max = 820.dp).fillMaxWidth(), rail = Tech.Amber, padding = 10.dp) { RadioNoteLine(note) } }
            when {
                board.loading && board.empty -> Text(RADIOS_LOADING, fontFamily = Tech.Mono, fontSize = 12.sp, color = Tech.Muted)
                board.empty -> RadioPanel(Modifier.widthIn(max = 820.dp).fillMaxWidth(), padding = 14.dp) {
                    Text(RADIOS_EMPTY, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Tech.Ink)
                    Spacer(Modifier.height(6.dp))
                    Text("Plug a Bluetooth or Wi-Fi adapter into a node, or check the notes below for what could not be read.", fontSize = 12.sp, lineHeight = 17.sp, color = Tech.Muted)
                    board.hosts.flatMap { h -> h.notes.map { h to it } }.forEach { (h, n) ->
                        Spacer(Modifier.height(6.dp))
                        Text(h.name, Modifier.identityIf(h.isNode), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted)
                        RadioNoteLine(n)
                    }
                }
            }
            if (!board.empty) BoxWithConstraints(Modifier.fillMaxWidth()) {
                val beside = maxWidth >= 1180.dp
                val mapWidth = if (beside) maxWidth - 352.dp else maxWidth
                val drawn = !board.empty && mapWidth.value >= MAP_MIN_WIDTH
                if (beside) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { if (drawn) MapPlate { RadiosMap(board, selected, onSelect, glow) } else if (!board.empty) NarrowNote() }
                    RadioDetail(board, selected, nav, onScan, Modifier.width(340.dp))
                } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (drawn) MapPlate { RadiosMap(board, selected, onSelect, glow) } else if (!board.empty) NarrowNote()
                    RadioDetail(board, selected, nav, onScan)
                }
            }
            // An empty board's panel above already says everything the words would.
            if (!board.empty) RadiosInWords(board, selected, onSelect)
            Spacer(Modifier.height(8.dp))
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight(), style = visibleScrollbarStyle())
    }
}

@Composable private fun NarrowNote() = Text("The window is too narrow for the map. The list below says the same.", fontSize = 12.sp, color = Tech.Muted)

@Composable private fun MapPlate(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().border(1.dp, Tech.Edge, Tech.Plate).background(Tech.Black.copy(alpha = .22f), Tech.Plate).padding(14.dp)) { content() }
}

/** Counts, each in words: Bluetooth, Wi-Fi, in guests, free and guesses. */
@Composable internal fun RadiosTally(board: RadiosBoard) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TallyTile("Bluetooth", board.count(RadioKind.Bluetooth), RadioKind.Bluetooth)
        TallyTile("Wi-Fi", board.count(RadioKind.WiFi), RadioKind.WiFi)
        TallyTile("In guests", board.inGuests, null)
        TallyTile("Free", board.radios.count { it.free && !it.own }, null)
        val guesses = board.radios.count { it.sure == RadioSure.Guess }
        if (guesses > 0) TallyTile("Guesses", guesses, null, warn = true)
        TallyTile(if (board.hosts.count { it.isNode } == 1) "Node" else "Nodes", board.hosts.count { it.isNode }, null)
    }
}

@Composable private fun TallyTile(label: String, value: Int, kind: RadioKind?, warn: Boolean = false) {
    Row(Modifier.widthIn(min = 120.dp).border(1.dp, if (warn) Tech.Amber else Tech.Edge, Tech.Control).background(Tech.Panel).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (kind != null) RadioGlyph(kind, size = 22.dp)
        Text("$value", fontFamily = Tech.Mono, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = if (warn) Tech.Amber else Tech.Ink)
        Text(label.uppercase(), fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Muted, maxLines = 1)
    }
}
