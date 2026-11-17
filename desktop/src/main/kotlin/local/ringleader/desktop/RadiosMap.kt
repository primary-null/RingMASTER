@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/*
 * The Radios map: this computer and each node as a plate with its adapters as chips, and on the right each guest that holds
 * one, joined by a line. Hovering or selecting any of them lights the whole path (adapter, line, guest). The same facts are
 * said in words below the map (RadiosInWords) for narrow windows and screen readers.
 */

internal data class MapRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right get() = x + w
    val bottom get() = y + h
    val midY get() = y + h / 2
}

/** A line from an adapter (or, for a SPICE channel, from this computer) to a guest. */
/** One line of the map. [state] is the adapter's, null for a SPICE channel. */
internal data class MapLink(val from: String, val to: String, val kind: RadioKind?, val spice: Boolean, val running: Boolean, val a: Offset, val b: Offset, val state: RadioState? = null) {
    val key get() = "$from>$to"
    /** Live: the guest is running and the adapter is in use. A SPICE channel is live while its guest runs. */
    val live get() = running && (spice || state == RadioState.Active)
    /** Unreachable: the adapter's node is offline. */
    val down get() = state == RadioState.Disabled
}

internal data class MapLayout(
    val width: Float, val height: Float,
    val hosts: List<Pair<RadioHost, MapRect>>,
    val chips: List<Pair<RadioCard, MapRect>>,
    val guests: List<Pair<RadioGuest, MapRect>>,
    val links: List<MapLink>,
)

internal const val MAP_MIN_WIDTH = 560f

/**
 * Where everything goes, in dp, for a map [width] wide at text [scale]. Hosts stack on the left; each guest sits on the right
 * as near the height of its adapters as the guests above it allow, so most lines run level.
 */
internal fun radiosMapLayout(board: RadiosBoard, width: Float, scale: Float = 1f): MapLayout {
    val s = scale.coerceIn(1f, 2f)
    val hostW = (width * .44f).coerceIn(250f, 400f)
    val guestW = (width * .30f).coerceIn(190f, 300f)
    val head = 44f * s; val chipH = 40f * s; val gap = 8f; val pad = 10f; val hostGap = 18f; val guestH = 52f * s
    val hosts = ArrayList<Pair<RadioHost, MapRect>>()
    val chips = ArrayList<Pair<RadioCard, MapRect>>()
    var y = 0f
    for (host in board.hosts) {
        val own = board.radiosOn(host.key)
        val rows = own.size.coerceAtLeast(1)
        val h = head + rows * chipH + (rows - 1) * gap + pad
        hosts += host to MapRect(0f, y, hostW, h)
        own.forEachIndexed { i, card -> chips += card to MapRect(pad, y + head + i * (chipH + gap), hostW - pad * 2, chipH) }
        y += h + hostGap
    }
    val hostsBottom = (y - hostGap).coerceAtLeast(0f)
    val chipOf = chips.associate { it.first.id to it.second }
    val thisComputer = hosts.firstOrNull { it.first.kind == RadioHostKind.ThisComputer }?.second
    // Each guest aims at the middle of its adapters (a SPICE-only guest at this computer's header), then is pushed below the one above.
    val wanted = board.guests.map { g ->
        val ys = g.radios.mapNotNull { chipOf[it.id]?.midY }
        g to (if (ys.isNotEmpty()) ys.average().toFloat() else thisComputer?.let { it.y + head / 2 } ?: 0f)
    }.sortedBy { it.second }
    val guests = ArrayList<Pair<RadioGuest, MapRect>>()
    var floor = 0f
    for ((g, target) in wanted) {
        val top = maxOf(target - guestH / 2, floor)
        guests += g to MapRect(width - guestW, top, guestW, guestH)
        floor = top + guestH + 12f
    }
    val links = ArrayList<MapLink>()
    for ((g, rect) in guests) {
        g.radios.forEach { card ->
            val c = chipOf[card.id] ?: return@forEach
            links += MapLink(radioItemKey(card), guestItemKey(g.ref), card.kind, false, g.ref.running, Offset(c.right, c.midY), Offset(rect.x, rect.midY), card.state)
        }
        if (g.channels.isNotEmpty() && thisComputer != null)
            links += MapLink(THIS_COMPUTER_KEY.let { "host:$it" }, guestItemKey(g.ref), null, true, g.ref.running,
                Offset(thisComputer.right, thisComputer.y + head / 2), Offset(rect.x, rect.midY))
    }
    val bottom = maxOf(hostsBottom, guests.maxOfOrNull { it.second.bottom } ?: 0f)
    return MapLayout(width, bottom, hosts, chips, guests, links)
}

/** Every key that lights with [key]: an adapter, its line and its guest, or a guest and all its adapters. */
internal fun mapPath(board: RadiosBoard, key: String?): Set<String> {
    if (key == null) return emptySet()
    val out = mutableSetOf(key)
    when {
        key.startsWith("radio:") -> board.radio(key.removePrefix("radio:"))?.holder?.let { out += guestItemKey(it.guest) }
        key.startsWith("guest:") -> board.guest(key.removePrefix("guest:"))?.let { g ->
            g.radios.forEach { out += radioItemKey(it) }
            if (g.channels.isNotEmpty()) out += "host:$THIS_COMPUTER_KEY"
        }
        key.startsWith("host:") -> board.radiosOn(key.removePrefix("host:")).forEach { out += radioItemKey(it) }
    }
    return out
}

/**
 * The drawn map. [selected] and [onSelect] are shared with the detail and the list; [glow] lights the item a request asked
 * for, once, when the page arrives.
 */
@Composable internal fun RadiosMap(board: RadiosBoard, selected: String?, onSelect: (String?) -> Unit, glow: Pair<String, Long>?, modifier: Modifier = Modifier) {
    var hovered by remember { mutableStateOf<String?>(null) }
    val scale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val layout = remember(board, maxWidth, scale) { radiosMapLayout(board, maxWidth.value, scale) }
        val lit = mapPath(board, hovered ?: selected)
        val motion = LocalMotion.current
        val animate = !LocalReduceMotion.current && !motion.reduced && !motion.lite
        // One clock for the whole map, read only while drawing: the packets move without recomposing a chip.
        val flow = if (animate) rememberInfiniteTransition(label = "radio-flow").animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "flow") else null
        val contrast = Appearance.palette.highContrast
        // How alive each line is, 0 (dormant) to 1 (live), eased: a guest that starts or stops is seen to change, and a
        // status that flickers does not strobe. And one sweep along a line at the moment it comes alive.
        val liveness = remember { HashMap<String, Animatable<Float, AnimationVector1D>>() }
        val surges = remember { HashMap<String, Animatable<Float, AnimationVector1D>>() }
        val targets = layout.links.associate { it.key to if (it.live) 1f else 0f }
        liveness.keys.retainAll(targets.keys); surges.keys.retainAll(targets.keys)
        for ((key, target) in targets) { liveness.getOrPut(key) { Animatable(target) }; surges.getOrPut(key) { Animatable(1f) } }
        LaunchedEffect(targets, animate) {
            for ((key, target) in targets) {
                val line = liveness[key] ?: continue
                if (line.targetValue == target && !line.isRunning) continue
                if (!animate) { line.snapTo(target); continue }
                launch { line.animateTo(target, tween(550)) }
                if (target == 1f) surges[key]?.let { sweep -> launch { sweep.snapTo(0f); sweep.animateTo(1f, tween(900, easing = LinearEasing)) } }
            }
        }
        Box(Modifier.fillMaxWidth().height(layout.height.dp)) {
            val edge = Tech.Edge; val amber = Tech.Amber; val muted = Tech.Muted
            Canvas(Modifier.matchParentSize()) {
                val u = 1.dp.toPx()
                for (link in layout.links) {
                    val a = Offset(link.a.x * u, link.a.y * u); val b = Offset(link.b.x * u, link.b.y * u)
                    val dx = (b.x - a.x) * .5f
                    val path = Path().apply { moveTo(a.x, a.y); cubicTo(a.x + dx, a.y, b.x - dx, b.y, b.x, b.y) }
                    val on = link.from in lit && link.to in lit
                    val alive = liveness[link.key]?.value ?: if (link.live) 1f else 0f
                    val ink = when { on -> amber; link.down -> muted; link.kind != null -> radioTint(link.kind); else -> muted }
                    val width = (if (on) 2.6f else if (contrast) 2f else 1.4f) * u
                    val strength = if (on || contrast) 1f else .45f + .4f * alive
                    // A live line is solid. A dormant one (its guest is stopped) is dashed; an unreachable one and a SPICE
                    // channel are dotted. None of it relies on colour. Between states the two drawings cross over.
                    val rest = if (link.spice || link.down) PathEffect.dashPathEffect(floatArrayOf(2f * u, 5f * u)) else PathEffect.dashPathEffect(floatArrayOf(7f * u, 5f * u))
                    if (link.spice) drawPath(path, ink.copy(alpha = strength), style = Stroke(width, pathEffect = rest))
                    else {
                        if (alive < 1f) drawPath(path, ink.copy(alpha = strength * (1f - alive)), style = Stroke(width, pathEffect = rest))
                        if (alive > 0f) drawPath(path, ink.copy(alpha = strength * alive), style = Stroke(width))
                    }
                    // Its ends: a dot where the line is live, a ring where it is not.
                    for (end in listOf(a, b)) {
                        if (alive < 1f) drawCircle(ink.copy(alpha = strength * (1f - alive)), 2.6f * u, end, style = Stroke(1.2f * u))
                        if (alive > 0f) drawCircle(ink.copy(alpha = alive), 3f * u, end)
                    }
                    if (alive > .02f && !link.spice) {
                        val measure = PathMeasure().apply { setPath(path, false) }
                        val len = measure.length
                        if (flow != null) repeat(2) { n ->
                            val t = (flow.value + n * .5f) % 1f
                            drawCircle(ink.copy(alpha = alive), (if (on) 3.2f else 2.4f) * u * alive, measure.getPosition(len * t))
                        }
                        // One bright sweep from the adapter to the guest at the moment the line comes alive.
                        val sweep = surges[link.key]?.value ?: 1f
                        if (sweep < 1f) {
                            val at = measure.getPosition(len * sweep)
                            drawCircle(ink.copy(alpha = .28f * (1f - sweep)), 10f * u, at)
                            drawCircle(ink, 4.4f * u * (1f - .35f * sweep), at)
                        }
                    }
                }
                // The dotted floor under the guest column, so the right side reads as one place.
                if (layout.guests.isNotEmpty()) {
                    val x = (layout.guests.first().second.x - 14f) * u
                    drawLine(edge, Offset(x, 0f), Offset(x, size.height), 1f * u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f * u, 6f * u)))
                }
            }
            for ((host, r) in layout.hosts) HostPlate(host, board.radiosOn(host.key).isEmpty(), hostItemKey(host) in lit, selected == hostItemKey(host),
                glow?.takeIf { it.first == hostItemKey(host) }?.second, { hovered = it }, onSelect, Modifier.offset(r.x.dp, r.y.dp).size(r.w.dp, r.h.dp))
            for ((card, r) in layout.chips) AdapterChip(card, board, radioItemKey(card) in lit, selected == radioItemKey(card),
                glow?.takeIf { it.first == radioItemKey(card) }?.second, { hovered = it }, onSelect, Modifier.offset(r.x.dp, r.y.dp).size(r.w.dp, r.h.dp), flow)
            for ((g, r) in layout.guests) GuestNode(g, guestItemKey(g.ref) in lit, selected == guestItemKey(g.ref),
                glow?.takeIf { it.first == guestItemKey(g.ref) }?.second, { hovered = it }, onSelect, Modifier.offset(r.x.dp, r.y.dp).size(r.w.dp, r.h.dp))
        }
    }
}

/** Hover sets the shared highlight; a click selects (a second click clears). */
@Composable private fun Modifier.mapItem(key: String, selected: Boolean, onHover: (String?) -> Unit, onSelect: (String?) -> Unit): Modifier {
    val interaction = remember(key) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) { onHover(if (hovered) key else null) }
    return this.hoverable(interaction).clickable(interactionSource = interaction, indication = null, role = Role.Button) { onSelect(if (selected) null else key) }
}

@Composable private fun HostPlate(host: RadioHost, empty: Boolean, lit: Boolean, selected: Boolean, glow: Long?, onHover: (String?) -> Unit, onSelect: (String?) -> Unit, modifier: Modifier) {
    val key = hostItemKey(host)
    val border = if (selected) Tech.Amber else if (lit) Tech.Amber.copy(alpha = .6f) else Tech.Edge
    Box(modifier.focusGlow(glow ?: 0L, glow != null).clip(Tech.Plate).background(Tech.Panel).border(if (selected) 2.dp else 1.dp, border, Tech.Plate)) {
        Row(Modifier.fillMaxWidth().height(44.dp * LocalDensity.current.fontScale.coerceIn(1f, 2f)).mapItem(key, selected, onHover, onSelect).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HostGlyph(host.kind)
            Column(Modifier.weight(1f)) {
                Text(host.name, Modifier.identityIf(host.isNode), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(hostCaption(host), fontFamily = Tech.Mono, fontSize = 9.sp, letterSpacing = .6.sp, color = if (host.online) Tech.Muted else Tech.Amber, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (host.notes.isNotEmpty()) AttentionTag("${host.notes.size} note" + if (host.notes.size == 1) "" else "s", help = host.notes.joinToString(" ") { it.text })
            host.link?.let { SignalBars(it.signalDbm) }
        }
        if (empty) Text("No radios found here", Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 16.dp), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted, maxLines = 1)
    }
}

internal fun hostCaption(host: RadioHost) = when {
    host.kind == RadioHostKind.ThisComputer -> "THIS COMPUTER"
    !host.online -> "NODE · OFFLINE"
    host.link != null -> "NODE · ON WI-FI"
    else -> "NODE"
}

@Composable internal fun HostGlyph(kind: RadioHostKind, modifier: Modifier = Modifier) {
    val ink = Tech.Ink
    Canvas(modifier.size(18.dp)) {
        val st = Stroke(1.5.dp.toPx())
        val w = size.width; val h = size.height
        if (kind == RadioHostKind.ThisComputer) { // a laptop
            drawRect(ink, Offset(w * .14f, h * .16f), androidx.compose.ui.geometry.Size(w * .72f, h * .5f), style = st)
            drawLine(ink, Offset(w * .02f, h * .82f), Offset(w * .98f, h * .82f), st.width)
        } else repeat(2) { n -> // two server slabs
            val top = h * (.12f + n * .42f)
            drawRect(ink, Offset(w * .08f, top), androidx.compose.ui.geometry.Size(w * .84f, h * .32f), style = st)
            drawCircle(ink, 1.3.dp.toPx(), Offset(w * .76f, top + h * .16f))
        }
    }
}

/** An adapter on its host. The plate, the edge and the word at the right all follow its state; [pulse] is the map's clock. */
@Composable private fun AdapterChip(card: RadioCard, board: RadiosBoard, lit: Boolean, selected: Boolean, glow: Long?, onHover: (String?) -> Unit, onSelect: (String?) -> Unit, modifier: Modifier, pulse: State<Float>? = null) {
    val shape = radioShape(card.kind)
    val edge = when { selected -> Tech.Amber; lit -> Tech.Amber.copy(alpha = .7f); else -> radioInk(card.kind, card.state).dimmed(.7f) }
    Row(modifier.focusGlow(glow ?: 0L, glow != null).clip(shape).background(if (selected || lit) Tech.Raised else Tech.Black.copy(alpha = .35f))
        .border(if (selected) 2.dp else 1.dp, edge, shape)
        .then(if (card.sure == RadioSure.Guess) Modifier.drawBehind { drawRect(Tech.Amber, size = androidx.compose.ui.geometry.Size(size.width, 1.5.dp.toPx()), topLeft = Offset(0f, size.height - 1.5.dp.toPx())) } else Modifier)
        .mapItem(radioItemKey(card), selected, onHover, onSelect).padding(start = 9.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RadioBadge(card.kind, size = 26.dp, state = card.state, pulse = pulse)
        Column(Modifier.weight(1f)) {
            Text(card.title, fontSize = 12.sp, lineHeight = 14.sp, color = if (card.state == RadioState.Disabled) Tech.Muted else Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(card.kind.short.uppercase(), card.bus.label.uppercase().takeIf { card.bus != RadioBus.Unknown }, card.holder?.entry, "FREE".takeIf { card.free && !card.own }).joinToString(" · "),
                fontFamily = Tech.Mono, fontSize = 9.sp, lineHeight = 11.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (card.sure == RadioSure.Guess) Text("?", fontFamily = Tech.Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Tech.Amber)
        RadioStateTag(card.state)
    }
}

@Composable private fun GuestNode(g: RadioGuest, lit: Boolean, selected: Boolean, glow: Long?, onHover: (String?) -> Unit, onSelect: (String?) -> Unit, modifier: Modifier) {
    val edge = when { selected -> Tech.Amber; lit -> Tech.Amber.copy(alpha = .7f); else -> Tech.Edge }
    Row(modifier.focusGlow(glow ?: 0L, glow != null).clip(Tech.Control).background(if (selected || lit) Tech.Raised else Tech.Panel)
        .border(if (selected) 2.dp else 1.dp, edge, Tech.Control).mapItem(guestItemKey(g.ref), selected, onHover, onSelect).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IdentityTile(if (g.ref.isVm) "VM" else "CT")
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(g.ref.title, Modifier.identity(), fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${g.ref.label} · ${if (g.ref.running) "RUNNING" else "STOPPED"}", fontFamily = Tech.Mono, fontSize = 9.sp, lineHeight = 11.sp,
                    color = if (g.ref.running) Tech.Green else Tech.Muted, maxLines = 1, softWrap = false)
                g.radios.map { it.kind }.distinct().forEach { RadioGlyph(it, size = 10.dp) }
            }
        }
    }
}

/** Showcase hides node names: they can name a place. */
@Composable internal fun Modifier.identityIf(on: Boolean): Modifier = if (on) this.identity() else this

// ---- detail of the selection ----

/** What the selected adapter, guest or host is, with its way onward. Nothing selected: a short how-to. */
@Composable internal fun RadioDetail(board: RadiosBoard, selected: String?, nav: RadiosNav, onScan: (RadioCard) -> Unit, modifier: Modifier = Modifier) {
    RadioPanel(modifier.fillMaxWidth(), rail = Tech.Amber, padding = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                selected == null -> {
                    SectionLabel("Selection")
                    Text("Select an adapter, a guest or a computer on the map to see what it is and where it leads.", fontSize = 12.sp, lineHeight = 17.sp, color = Tech.Muted)
                    RadiosKey()
                }
                selected.startsWith("radio:") -> board.radio(selected.removePrefix("radio:"))?.let { AdapterDetail(it, board, nav, onScan) } ?: Gone()
                selected.startsWith("guest:") -> board.guest(selected.removePrefix("guest:"))?.let { GuestDetail(it, board, nav) } ?: Gone()
                selected.startsWith("host:") -> board.host(selected.removePrefix("host:"))?.let { HostDetail(it, board, nav) } ?: Gone()
                else -> Gone()
            }
        }
    }
}

@Composable private fun Gone() = Text("This item is no longer on the board.", fontSize = 12.sp, color = Tech.Muted)

/** The key to the map's marks: shapes, lines and the guess bar, in words. */
@Composable internal fun RadiosKey() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { RadioKindChip(RadioKind.Bluetooth); Text("Bluetooth: cut corners", fontSize = 11.sp, color = Tech.Muted) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { RadioKindChip(RadioKind.WiFi); Text("Wi-Fi: round ends", fontSize = 11.sp, color = Tech.Muted) }
        KeyLine(null, "Solid line: given to a running guest")
        KeyLine(floatArrayOf(7f, 5f), "Dashed line: the guest is stopped")
        KeyLine(floatArrayOf(2f, 5f), "Dotted line: a SPICE channel from this computer")
        Text("? and an amber underline: the app is guessing.", fontSize = 11.sp, color = Tech.Muted)
    }
}

@Composable private fun KeyLine(dash: FloatArray?, text: String) {
    val ink = Tech.Ink
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.size(34.dp, 10.dp)) {
            val u = 1.dp.toPx()
            drawLine(ink, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.6f * u, pathEffect = dash?.let { PathEffect.dashPathEffect(floatArrayOf(it[0] * u, it[1] * u)) })
        }
        Text(text, fontSize = 11.sp, color = Tech.Muted)
    }
}

@Composable internal fun DetailFact(label: String, value: String, identity: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label.uppercase(), fontFamily = Tech.Mono, fontSize = 9.sp, letterSpacing = 1.sp, color = Tech.Muted)
        Text(value, Modifier.identityIf(identity), fontFamily = Tech.Mono, fontSize = 11.sp, lineHeight = 15.sp, color = Tech.Ink)
    }
}

/** A sentence with its optional action beside or under it. Lays out the same with or without the action. */
@Composable internal fun RadioNoteLine(note: RadioNote, modifier: Modifier = Modifier, size: Int = 12) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Text(note.text, Modifier.widthIn(max = 640.dp), fontSize = size.sp, lineHeight = (size + 5).sp, color = if (note.warning) Tech.Amber else Tech.Muted)
        note.action?.let { a -> MiniAction(a.label, onClick = a.run) }
    }
}

internal fun viaSentence(h: RadioHolder) = "Given to ${h.guest.label} ${h.guest.title} ${h.via.phrase}" + (h.entry?.let { " ($it)" } ?: "") + "."

@Composable private fun AdapterDetail(card: RadioCard, board: RadiosBoard, nav: RadiosNav, onScan: (RadioCard) -> Unit) {
    val host = board.host(card.hostKey)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RadioBadge(card.kind, size = 30.dp, state = card.state)
        Text(card.title, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Tech.Ink)
        RadioKindChip(card.kind)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Text(card.sureText, fontSize = 12.sp, color = if (card.sure == RadioSure.Guess) Tech.Amber else Tech.Ink)
        RadioSureTag(card)
    }
    // What it is doing now, as a sign, a word and the sentence that says why.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RadioStateTag(card.state)
        card.stateWhy?.let { Text(it, Modifier.weight(1f), fontSize = 11.sp, lineHeight = 15.sp, color = Tech.Muted) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        card.vendor?.let { DetailFact("Maker", it) }
        card.ids?.let { DetailFact("IDs", it) }
        DetailFact("Plugged in", listOfNotNull(card.port ?: card.bus.label, host?.name?.let { "on $it" }).joinToString(" "), identity = host?.isNode == true)
        card.driver?.let { DetailFact("Driver", it) }
    }
    val holder = card.holder
    val whose = when {
        holder != null -> viaSentence(holder)
        card.own -> "This computer's own adapter."
        else -> "Free: no guest has it."
    }
    Text(whose, Modifier.identityIf(holder != null), fontSize = 12.sp, lineHeight = 17.sp, color = Tech.Ink)
    holder?.let { Text(it.via.help, fontSize = 11.sp, lineHeight = 15.sp, color = Tech.Muted) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (holder != null) MiniAction("Open guest", primary = true) { nav.openGuest(holder.guest, "Radios") }
        if (host?.isNode == true) MiniAction("Open node") { nav.openNode(host) }
        if (card.scan != null) MiniAction("Scan with it") { onScan(card) }
    }
    if (card.scan == null) card.scanRefusal?.let { RadioNoteLine(it, size = 11) }
}

@Composable private fun GuestDetail(g: RadioGuest, board: RadiosBoard, nav: RadiosNav) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IdentityTile(if (g.ref.isVm) "VM" else "CT")
        Column(Modifier.weight(1f)) {
            Text(g.ref.title, Modifier.identity(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Tech.Ink)
            Text("${g.ref.label} · ${g.ref.node} · ${if (g.ref.running) "running" else "stopped"}", Modifier.identity(), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted)
        }
    }
    Text("Radios of this guest", fontFamily = Tech.Mono, fontSize = 9.sp, letterSpacing = 1.sp, color = Tech.Muted)
    g.radios.forEach { card ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioGlyph(card.kind, size = 14.dp, state = card.state)
            Text("${card.title} · ${card.kind.label} · ${card.holder?.via?.label}" + (card.holder?.entry?.let { " ($it)" } ?: ""),
                Modifier.weight(1f), fontSize = 12.sp, lineHeight = 16.sp, color = Tech.Ink)
            RadioSureTag(card)
        }
        Text("From ${board.host(card.hostKey)?.name ?: card.hostKey}" + (card.port?.let { ", $it" } ?: ""), Modifier.padding(start = 22.dp).identity(), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted)
    }
    g.channels.forEach { ch ->
        Text("SPICE channel (${ch.entry}): a USB adapter on this computer can be redirected here from the console.", fontSize = 12.sp, lineHeight = 16.sp, color = Tech.Ink)
    }
    if (!g.ref.running && g.radios.isNotEmpty()) Text("The guest is stopped, so its adapters are unused.", fontSize = 11.sp, color = Tech.Amber)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MiniAction("Open guest", primary = true) { nav.openGuest(g.ref, "Radios") }
        board.host(g.ref.node)?.let { node -> MiniAction("Open node") { nav.openNode(node) } }
    }
}

@Composable private fun HostDetail(host: RadioHost, board: RadiosBoard, nav: RadiosNav) {
    val own = board.radiosOn(host.key)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HostGlyph(host.kind)
        Text(host.name, Modifier.weight(1f).identityIf(host.isNode), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Tech.Ink)
    }
    Text(when (own.size) { 0 -> "No radios found here."; 1 -> "1 adapter."; else -> "${own.size} adapters." } +
        own.count { !it.free }.let { if (it > 0) " $it given to guests." else "" }, fontSize = 12.sp, color = Tech.Ink)
    host.link?.let { DetailFact("Own Wi-Fi", hostLinkText(it)) }
    host.notes.forEach { RadioNoteLine(it, size = 11) }
    if (host.isNode) MiniAction("Open node", primary = true) { nav.openNode(host) }
}

internal fun hostLinkText(l: RadioHostLink) = listOfNotNull("Connected", l.signalDbm?.let { "$it dBm (${signalWord(it).lowercase()})" }, l.band?.label, l.channel?.let { "channel $it" }, l.generation).joinToString(" · ")

// ---- the same, in words ----

/** The board as sentences, grouped by computer then by guest. Each line selects its item on the map. */
@Composable internal fun RadiosInWords(board: RadiosBoard, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("In words")
        for (host in board.hosts) {
            val own = board.radiosOn(host.key)
            WordsLine(hostItemKey(host), selected, onSelect, bold = true) {
                Text("${host.name}: " + when (own.size) { 0 -> "no radios found"; 1 -> "1 adapter"; else -> "${own.size} adapters" } + if (host.kind == RadioHostKind.ThisComputer) "" else if (!host.online) " (offline)" else "",
                    Modifier.identityIf(host.isNode), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Tech.Ink)
            }
            host.notes.forEach { RadioNoteLine(it, Modifier.padding(start = 16.dp), size = 11) }
            own.forEach { card ->
                WordsLine(radioItemKey(card), selected, onSelect) {
                    RadioGlyph(card.kind, size = 12.dp, state = card.state)
                    Text(listOfNotNull("${card.sureText}: ${card.title}", card.port, card.holder?.let { viaSentence(it).removeSuffix(".").replaceFirstChar { c -> c.lowercase() } } ?: "free".takeIf { !card.own }, card.state.label.lowercase()).joinToString(" · "),
                        Modifier.weight(1f).identityIf(card.holder != null), fontSize = 12.sp, lineHeight = 16.sp, color = Tech.Ink)
                }
            }
        }
        if (board.guests.isNotEmpty()) Spacer(Modifier.height(6.dp))
        board.guests.forEach { g ->
            WordsLine(guestItemKey(g.ref), selected, onSelect) {
                IdentityTile(if (g.ref.isVm) "VM" else "CT")
                Text("${g.ref.label} ${g.ref.title} (${if (g.ref.running) "running" else "stopped"}) has " +
                    (g.radios.map { "${it.title} (${it.kind.label})" } + g.channels.map { "a SPICE channel (${it.entry})" }).joinToString(", "),
                    Modifier.weight(1f).identity(), fontSize = 12.sp, lineHeight = 16.sp, color = Tech.Ink)
            }
        }
    }
}

@Composable private fun WordsLine(key: String, selected: String?, onSelect: (String?) -> Unit, bold: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val on = key == selected
    Row(Modifier.fillMaxWidth().heightIn(min = 26.dp).background(if (on) Tech.Raised else Color.Transparent)
        .drawBehind { if (on) drawRect(Tech.Amber, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height)) }
        .clickable(role = Role.Button) { onSelect(if (on) null else key) }.padding(start = if (bold) 6.dp else 22.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
