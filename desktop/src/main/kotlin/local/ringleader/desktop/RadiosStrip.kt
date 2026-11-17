package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ringleader.core.Guest

/*
 * Radios on the Fleet page: a one-line strip with the counts and the first adapters, and a small mark on each guest that
 * holds one. Every chip opens the Radios map with that item lit.
 */

/** The strip. Nothing is drawn while the board has no radios and nothing to say. */
@Composable internal fun RadiosStrip(modifier: Modifier = Modifier) {
    val hub = LocalRadios.current ?: return
    val board by hub.board.board.collectAsState()
    if (board.empty && board.notes.isEmpty()) return
    RadiosStripRow(board, hub.nav, modifier)
}

@Composable internal fun RadiosStripRow(board: RadiosBoard, nav: RadiosNav, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val scale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
        // The counts take about 380 units; each chip about 220. Room left over decides how many chips show.
        val budget = (((maxWidth.value / scale) - 400f) / 220f).toInt().coerceIn(0, 6)
        val shown = board.radios.sortedWith(compareBy<RadioCard> { it.free }.thenBy { it.hostKey }).take(budget)
        Row(Modifier.fillMaxWidth().heightIn(min = 38.dp).clip(Tech.Control).border(1.dp, Tech.Edge, Tech.Control).background(Tech.Panel)
            .clickable(role = Role.Button) { nav.openRadios(RadiosTarget(RadiosView.Map)) }.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("RADIOS", fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = Tech.Muted, maxLines = 1, softWrap = false)
            Count(RadioKind.Bluetooth, board.count(RadioKind.Bluetooth), "Bluetooth")
            Count(RadioKind.WiFi, board.count(RadioKind.WiFi), "Wi-Fi")
            Text("${board.inGuests} in guests", fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Ink, maxLines = 1, softWrap = false)
            if (board.empty) board.notes.firstOrNull()?.let { Text(it.text, Modifier.weight(1f), fontSize = 11.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            else Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                shown.forEach { card -> StripChip(card) { nav.openRadios(RadiosTarget(RadiosView.Map, radioItemKey(card))) } }
                val more = board.radios.size - shown.size
                if (more > 0) Text("+$more", fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1, softWrap = false)
            }
            Text("MAP ›", fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Amber, maxLines = 1, softWrap = false)
        }
    }
}

@Composable private fun Count(kind: RadioKind, n: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        RadioGlyph(kind, size = 13.dp)
        Text("$n $label", fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Ink, maxLines = 1, softWrap = false)
    }
}

@Composable private fun StripChip(card: RadioCard, onClick: () -> Unit) {
    val shape = radioShape(card.kind, 5.dp)
    Row(Modifier.widthIn(max = 220.dp).clip(shape).border(1.dp, radioTint(card.kind).dimmed(.7f), shape).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        RadioGlyph(card.kind, size = 11.dp, state = card.state)
        Text(card.title, Modifier.weight(1f, fill = false), fontSize = 11.sp, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(card.holder?.guest?.let { "→ ${it.label}" } ?: "free", fontFamily = Tech.Mono, fontSize = 9.sp, color = Tech.Muted, maxLines = 1, softWrap = false)
    }
}

/** The tooltip and spoken name of a guest's radio mark: what it has, by kind and name. */
internal fun guestRadioMarkText(g: RadioGuest): String =
    "Radios: " + (g.radios.map { "${it.title} (${it.kind.label}" + (it.holder?.entry?.let { e -> ", $e" } ?: "") + ")" } +
        g.channels.map { "SPICE channel (${it.entry})" }).joinToString(", ")

/** A small mark beside a guest's name when it holds a Bluetooth or Wi-Fi adapter; a click opens it on the Radios map. */
@Composable internal fun GuestRadioMark(guest: Guest) {
    val hub = LocalRadios.current ?: return
    val board by hub.board.board.collectAsState()
    val g = board.guestFor(guest.resource) ?: return
    if (g.radios.isEmpty()) return
    val text = guestRadioMarkText(g)
    HelpTip(text) {
        Row(Modifier.clip(Tech.Control).border(1.dp, Tech.Edge, Tech.Control).clickable(role = Role.Button) { hub.nav.openRadios(RadiosTarget(RadiosView.Map, guestItemKey(g.ref))) }
            .semantics { contentDescription = text }.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            g.radios.map { it.kind }.distinct().sorted().forEach { RadioGlyph(it, size = 11.dp) }
        }
    }
}
