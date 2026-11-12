package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ringleader.core.*

/** For this run only, like the history timeframe: the rail stays open or folded, and Running or All, as you move between guests. */
internal object ConsoleSwitcherPreference {
    var expanded = true
    var runningOnly = true
}

/** Persisted keep time. Anything other than Off, 5, 10, 30 or 60 minutes reads as 10. */
internal const val CONSOLE_KEEP_MINUTES_KEY = "console.keepMinutes"

internal fun consoleKeepMinutes(value: Int): Int =
    if (value == 0 || value == 5 || value == 10 || value == 30 || value == 60) value else CONSOLE_KEEP_DEFAULT_MINUTES

internal object ConsoleKeepPreference {
    var minutes by mutableStateOf(consoleKeepMinutes(runCatching {
        MotionPreferences().number(CONSOLE_KEEP_MINUTES_KEY, CONSOLE_KEEP_DEFAULT_MINUTES)
    }.getOrDefault(CONSOLE_KEEP_DEFAULT_MINUTES)))
        private set

    fun choose(value: Int, prefs: MotionPreferences = MotionPreferences()) {
        val next = consoleKeepMinutes(value)
        minutes = next
        prefs.putNumber(CONSOLE_KEEP_MINUTES_KEY, next)
    }

    /** In-memory only, so a test can force a keep time without writing the owner's preferences. */
    fun preview(value: Int) { minutes = consoleKeepMinutes(value) }
}

/** The rail's guests: this connection's guests with a page, templates left out, by node then VMID. The current guest is always listed. */
internal fun switcherGuests(state: WorkspaceState, current: ResourceId?, runningOnly: Boolean, filter: String): List<Guest> {
    val needle = filter.trim()
    return state.overview?.guests.orEmpty()
        .filter { guestHasPage(state, it) && !it.template }
        .filter { it.resource == current || ((!runningOnly || it.status == "running") &&
            (needle.isEmpty() || it.name.contains(needle, ignoreCase = true) || it.id.toString().startsWith(needle))) }
        .sortedWith(compareBy({ it.node }, { it.id }))
}

/** The guest [step] places from [current] in [list], wrapping; null when the list has no other guest. */
internal fun neighbourGuest(list: List<Guest>, current: ResourceId, step: Int): Guest? {
    if (list.size < 2) return null
    val at = list.indexOfFirst { it.resource == current }.takeIf { it >= 0 } ?: return list.first()
    return list[Math.floorMod(at + step, list.size)]
}

/** Ctrl+Alt+PageUp / PageDown / Enter. Enter is the immersive toggle. Nothing else is taken from the guest. */
internal enum class ConsoleShortcut { PreviousGuest, NextGuest, Immersive }

/**
 * Ctrl+Alt and neither Shift nor Meta. PageUp, PageDown and Enter produce no AltGr characters, so taking them does not
 * eat a letter. Any other key, including Ctrl+Alt+A, stays with the guest.
 */
internal fun consoleShortcut(event: KeyEvent): ConsoleShortcut? {
    if (!event.isCtrlPressed || !event.isAltPressed || event.isShiftPressed || event.isMetaPressed) return null
    return when (event.key) {
        Key.PageUp -> ConsoleShortcut.PreviousGuest
        Key.PageDown -> ConsoleShortcut.NextGuest
        Key.Enter, Key.NumPadEnter -> ConsoleShortcut.Immersive
        else -> null
    }
}

/**
 * Set while the guest page is on its Console tab, so a shortcut reaches the page when the console does not hold the
 * keyboard. Cleared when that tab leaves the composition.
 */
/**
 * Takes every press inside a panel that floats over a click-to-close layer, so a click on its header or padding stays
 * on the panel instead of reaching the layer beneath (the same guard as the chart lens panel). Nothing is consumed, so
 * the panel's own rows and buttons still get their clicks.
 */
internal fun Modifier.blockPointerPassThrough(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial) }
}

internal object ConsoleKeys {
    var handler: ((KeyEvent) -> Boolean)? = null
}

/** A copy of a shown frame, or null when the view never drew one. The view's own buffer is left untouched. */
internal fun consoleFrameToKeep(shown: Boolean, width: Int, height: Int, pixels: IntArray): IntArray? =
    if (!shown || width <= 0 || height <= 0 || pixels.size < width * height) null else pixels.copyOf(width * height)

/** Filter text and choices of one guest page's rail; remembered by the page so keyboard switching uses the same list as the rail. */
@Stable internal class ConsoleSwitcherState {
    var expanded by mutableStateOf(ConsoleSwitcherPreference.expanded)
    var runningOnly by mutableStateOf(ConsoleSwitcherPreference.runningOnly)
    var filter by mutableStateOf("")

    fun fold(open: Boolean) {
        expanded = open
        ConsoleSwitcherPreference.expanded = open
    }

    fun showRunning(only: Boolean) {
        runningOnly = only
        ConsoleSwitcherPreference.runningOnly = only
    }
}

@Composable internal fun ConsoleSwitcher(
    state: WorkspaceState, current: Guest, switcher: ConsoleSwitcherState, onSwitch: (Guest) -> Unit, modifier: Modifier = Modifier,
) {
    val pool = switcherGuests(state, current.resource, switcher.runningOnly, "")
    val shown = if (switcher.filter.isBlank()) pool else switcherGuests(state, current.resource, switcher.runningOnly, switcher.filter)
    val reduce = LocalReduceMotion.current
    val listState = rememberLazyListState()
    val index = shown.indexOfFirst { it.resource == current.resource }
    LaunchedEffect(current.resource, reduce) {
        if (index >= 0) { if (reduce) listState.scrollToItem(index) else listState.animateScrollToItem(index) }
    }
    // A bordered column, not TechPanel: that panel sizes to its content, and a lazy list needs a bounded height.
    Row(modifier.width(if (switcher.expanded) 232.dp else 44.dp).fillMaxHeight().background(Tech.Panel).border(1.dp, Tech.Edge, Tech.Plate)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(Tech.Edge))
        Column(Modifier.weight(1f).fillMaxHeight().padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (switcher.expanded) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Guests", Modifier.weight(1f))
                MiniAction("‹", width = 28.dp) { switcher.fold(false) }
            }
            SwitcherSegments(
                listOf("running" to "Running", "all" to "All"),
                if (switcher.runningOnly) "running" else "all",
            ) { switcher.showRunning(it == "running") }
            if (pool.size > 8) TechField(switcher.filter, { switcher.filter = it }, "Filter guests", placeholder = "Name or VMID")
        } else {
            MiniAction("›", width = 28.dp) { switcher.fold(true) }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(shown, key = { _, guest -> guest.resource }) { _, guest ->
                SwitcherRow(state, guest, current = guest.resource == current.resource, expanded = switcher.expanded) {
                    if (guest.resource != current.resource) onSwitch(guest)
                }
            }
        }
        }
    }
}

@Composable private fun SwitcherSegments(items: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().height(26.dp).border(1.dp, Tech.Edge, Tech.Control)) {
        items.forEachIndexed { i, (key, label) ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Tech.Edge))
            val active = key == selected
            Box(Modifier.weight(1f).fillMaxHeight().background(if (active) Tech.Amber.copy(alpha = .16f) else Color.Transparent)
                .clickable { onSelect(key) }, contentAlignment = Alignment.Center) {
                Text(label, fontSize = 11.sp, fontFamily = Tech.Mono, color = if (active) Tech.Amber else Tech.Ink, maxLines = 1)
            }
        }
    }
}

@Composable private fun SwitcherRow(state: WorkspaceState, guest: Guest, current: Boolean, expanded: Boolean, onClick: () -> Unit) {
    val interactions = remember(guest.resource) { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val background = when {
        current -> Tech.AmberWash
        hovered -> Tech.Raised
        else -> Color.Transparent
    }
    val row = Modifier.fillMaxWidth().height(if (expanded) 52.dp else 28.dp)
        .background(background)
        .drawBehind { if (current) drawRect(Tech.Amber, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height)) }
        .hoverable(interactions)
        .pointerHoverIcon(PointerIcon.Hand)
        .clickable(interactionSource = interactions, indication = null, enabled = !current, onClick = onClick)
        .padding(horizontal = 4.dp, vertical = 4.dp)
    val kept = !current && guest.resource in state.keptConsoles
    if (!expanded) {
        HelpTip(guest.name.ifBlank { "Guest ${guest.id}" }, identity = true) {
            Row(row, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusDot(guest.status)
                if (kept) KeptDot()
                Text("${guest.id}", fontFamily = Tech.Mono, fontSize = 10.sp, color = if (current) Tech.Amber else Tech.Muted, maxLines = 1)
            }
        }
        return
    }
    Row(row, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            GuestThumb(state, guest)
            if (kept) KeptDot(Modifier.align(Alignment.TopEnd))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(guest.name.ifBlank { "Guest ${guest.id}" }, Modifier.identity(), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Tech.Ink)
            Text("${if (guest.kind == "VM") "VM" else "CT"} ${guest.id} · ${guest.node}", Modifier.identity(), color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (guest.status == "running") Sparkline(state.history.guests[guest.resource] ?: Series(), Modifier.size(48.dp, 10.dp), grid = false, pulse = false)
        }
        StatusDot(guest.status)
    }
}

@Composable private fun KeptDot(modifier: Modifier = Modifier) {
    HelpTip("Console kept open. Switching back is instant.") {
        Box(modifier.size(6.dp).background(Tech.Green, CircleShape).semantics {
            contentDescription = "Console kept open. Switching back is instant."
        })
    }
}

@Composable private fun GuestThumb(state: WorkspaceState, guest: Guest) {
    val bitmap = if (guest.kind == "VM") rememberStillBitmap(state.consoleStills[guest.resource]) else null
    Box(Modifier.size(64.dp, 40.dp).alpha(if (guest.status == "stopped") 0.55f else 1f).background(Tech.Black), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap, "Last known screen",
                Modifier.fillMaxSize().then(if (Appearance.showcase) Modifier.blur(8.dp) else Modifier),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(if (guest.kind == "VM") "VM" else "CT", color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 10.sp)
        }
        if (guest.kind == "VM" && guest.status == "running") Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(Tech.Green))
    }
}

@Composable private fun StatusDot(status: String) {
    val color = when (status) { "running" -> Tech.Green; "stopped" -> Tech.Muted; else -> Tech.Amber }
    Box(Modifier.size(6.dp).background(color, CircleShape))
}
