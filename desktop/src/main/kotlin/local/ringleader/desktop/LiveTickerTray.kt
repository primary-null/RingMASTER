@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import kotlinx.coroutines.delay
import local.ringleader.core.*

/*
 * The live ticker tray: a small floating plate of tiles over the page area. Collapsed it is one slim pill with the single
 * most telling reading; expanded it shows up to four tiles, each a button to Under the Hood (and the area there that explains
 * it) with up to three links of its own. One tab stop for the whole tray, arrow keys between its parts.
 *
 * Nothing here runs per frame while idle: one reading a second, finite moments for opening, closing, arriving and leaving.
 */

internal const val TICKER_TAG = "live-ticker"
internal const val TICKER_PILL_TAG = "live-ticker-pill"
internal fun tickerTileTag(id: TickerTileId) = "live-ticker-tile-${id.name.lowercase()}"
internal fun tickerLinkTag(id: TickerTileId, index: Int) = "live-ticker-link-${id.name.lowercase()}-$index"

/** The tray is never wider than this at 100 % text (the brief: about 520 dp), and a tile never narrower than [TILE_MIN]. */
private val TRAY_MAX_WIDTH = 520.dp
private val TILE_MAX = 172.dp
private val TILE_MIN = 112.dp
private val NARROW_TILE = 244.dp
internal const val TICKER_HOVER_OPEN_MS = 350L

/**
 * Where the tray sits on a page: [fx] and [fy] pick a corner or edge of the page area (0 start or top, 1 end or bottom,
 * .5 the middle), and [dx] and [dy] say how far in from it. One table, here, for every page.
 */
internal data class TickerAnchor(val fx: Float, val fy: Float, val dx: Dp = 12.dp, val dy: Dp = 12.dp)

internal object TickerPlacement {
    /**
     * Below this page-area width (in text-sized units) the tray is a short column; from it to [WIDE] it is the plate with two tiles, about
     * 400 wide, which fits the stretches the wide table steps around; from [WIDE] up it is the full plate and the full table.
     */
    val NARROW = 640.dp
    val WIDE = 1100.dp

    fun compact(effectiveWidth: Dp) = effectiveWidth >= NARROW && effectiveWidth < WIDE

    private val TopEnd = TickerAnchor(1f, 0f)
    private val BottomStart = TickerAnchor(0f, 1f)
    private val BottomCenter = TickerAnchor(.5f, 1f)
    /** Top right, left of the page's own Sync and Disconnect buttons. */
    private val TopEndBesideButtons = TickerAnchor(1f, 0f, dx = 204.dp)
    /** Top right, left of the page's own Refresh button, which is about 90 dp wide. */
    private val TopEndBesideRefresh = TickerAnchor(1f, 0f, dx = 108.dp)

    fun anchor(page: String, pageWidth: Dp): TickerAnchor = when {
        pageWidth < NARROW -> narrowAnchor(page)
        pageWidth < WIDE -> compactAnchor(page)
        else -> wideAnchor(page)
    }

    /**
     * Chosen from the real layouts at 1280 by 720 (see the pictures): the pill must always sit on empty page, and the open plate
     * should cover labels or blank space, never a control.
     *  - Desk: the lower left is empty beside the Connect form.
     *  - Fleet: the inspector is on the right and the Disconnect strip above, so the plate sits over the foot of the list.
     *  - Storage: the top right of the selected storage's header, left of Refresh. The middle of that row holds the storage's name at
     *    1280 and the end of a help line at 1920; the right end holds neither. Activity: the same row, left of Sync and Disconnect.
     *  - Scan and VPN: the top right is empty above the grid and beside the tunnel cards; Schedules is all text, and there the top right
     *    covers the least.
     *  - Tools: the top right, where only the far ends of the Host and Count fields lie under it. The foot is the newest lines of the log,
     *    and a log line is short, so the middle of the foot (not the right of it, where toasts stack) is where the lines are.
     *  - Bluetooth: the foot of the device table, which is empty below its few rows (at the left the foot of the legend is text).
     *  - Watcher: the foot of the page, below the last rows.
     */
    private fun wideAnchor(page: String) = when (page) {
        "Desk", "Fleet", WATCHER_PAGE -> BottomStart
        "Storage" -> TopEndBesideRefresh
        "Activity" -> TopEndBesideButtons
        "Bluetooth" -> BottomCenter
        else -> TopEnd
    }

    /**
     * A 960 dp window leaves the page about 870 dp. With two tiles the plate is about 400 wide, so the top right still clears the Scan
     * page's subnet field and the VPN banner's text; only Activity's title and buttons leave too little room, so it sits at the foot.
     * Bluetooth's table fills the window at that height, so the foot would cover the signal of its lowest rows; at the top right the plate
     * covers the legend's text instead.
     */
    private fun compactAnchor(page: String) = when (page) {
        "Activity" -> BottomStart
        "Bluetooth" -> TopEnd
        else -> wideAnchor(page)
    }

    /** In a narrow window the tray is a column; the foot of the page is the quietest place for it on every page. */
    private fun narrowAnchor(page: String) = when (page) {
        else -> BottomStart
    }
}

// ---- the host ----

/**
 * The tray over the page area: samples once a second while it may be shown, opens itself for the things worth seeing, and
 * moves to the anchor of [page]. [blocked] is everything that must keep it away: an immersive console, a minimised or hidden
 * window, Under the Hood itself, a dialog, the create wizard. [period] is the sampling interval (a test turns the loop off with 0).
 */
@Composable internal fun LiveTickerHost(page: String, state: LiveTickerState, feed: TickerFeed, nav: TickerNav, blocked: Boolean, modifier: Modifier = Modifier, period: Long = 1000L) {
    val mode = LiveTickerPreference.mode
    val showcase = Appearance.showcase
    val names = page in TICKER_NAME_PAGES && !showcase
    // A console on screen on Fleet's guest page (its tab sets a key handler) is never covered: the tray steps aside while it shows.
    var consoleOn by remember { mutableStateOf(false) }
    LaunchedEffect(page, blocked, mode) {
        // Only while the tray could show on that page at all: a quarter-second look at one field, nothing per frame.
        if (page != "Fleet" || blocked || mode == TickerMode.Off) { consoleOn = false; return@LaunchedEffect }
        while (true) { consoleOn = ConsoleKeys.handler != null; delay(250) }
    }
    val watching = !blocked && !consoleOn && mode != TickerMode.Off && !state.dismissed
    val latest by rememberUpdatedState(Triple(mode, names, showcase))
    LaunchedEffect(watching, period) {
        if (!watching) { state.forget(); return@LaunchedEffect }
        if (period <= 0) return@LaunchedEffect
        while (true) {
            val (m, n, s) = latest
            // A reading that fails (the window's state moved under it) is skipped: a ticker must never take the window down with it.
            try { state.apply(feed.read(n, s), m) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
            delay(period)
        }
    }
    // A mode chosen in Settings also brings back a tray that was dismissed.
    LaunchedEffect(mode) { state.restore(mode) }
    // One wake-up for exactly when an opening ends, never a poll.
    LaunchedEffect(state.expanded, state.revision, mode) {
        val end = state.settlesAt(mode) ?: return@LaunchedEffect
        delay(Duration.between(state.clock(), end).toMillis().coerceAtLeast(1))
        state.refresh(mode)
    }
    val shown = watching && state.visible(mode)
    val motion = LocalMotion.current
    val presence = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        val to = if (shown) 1f else 0f
        if (motion.reduced) presence.snapTo(to) else presence.animateTo(to, if (shown) motion.enter(MotionDur.Standard) else motion.exit(MotionDur.Standard))
    }
    // The tiles stay on screen while the tray fades out.
    val held = remember { arrayOfNulls<TickerModel>(1) }
    if (shown) held[0] = state.model
    if (!shown && presence.value <= 0f) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val narrow = maxWidth < TickerPlacement.NARROW
        // Larger text makes the page's own titles and buttons wider and the plate wider with them: the room is judged in text-sized units,
        // and the offsets that keep clear of a button grow with it.
        val text = LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
        val anchor = TickerPlacement.anchor(page, maxWidth / text)
        val spec = motion.spatial<Float>(MotionSpring.SpatialDefault)
        val fx = animateFloatAsState(anchor.fx, spec, label = "ticker-fx")
        val fy = animateFloatAsState(anchor.fy, spec, label = "ticker-fy")
        val dx = animateFloatAsState(anchor.dx.value * text, spec, label = "ticker-dx")
        val dy = animateFloatAsState(anchor.dy.value * text, spec, label = "ticker-dy")
        val density = LocalDensity.current
        Layout(
            content = { TickerChrome(state, if (shown) state.model else held[0] ?: state.model, narrow, maxWidth, maxHeight, nav, mode, anchor, Modifier.graphicsLayer { alpha = presence.value }) },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val inset = with(density) { dx.value.dp.roundToPx() } to with(density) { dy.value.dp.roundToPx() }
            val placeable = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = (constraints.maxWidth - 2 * inset.first).coerceAtLeast(0), maxHeight = (constraints.maxHeight - 2 * inset.second).coerceAtLeast(0)))
            layout(constraints.maxWidth, constraints.maxHeight) {
                val x = inset.first + ((constraints.maxWidth - 2 * inset.first - placeable.width) * fx.value).toInt()
                val y = inset.second + ((constraints.maxHeight - 2 * inset.second - placeable.height) * fy.value).toInt()
                placeable.place(IntOffset(x, y))
            }
        }
    }
}

// ---- the chrome: one tab stop, pill or plate ----

private enum class Control { Pin, Collapse, Dismiss }

private sealed interface TrayItem {
    data class Body(val tile: Int) : TrayItem
    data class Link(val tile: Int, val link: Int) : TrayItem
    data class Part(val control: Control) : TrayItem
}

@Composable private fun TickerChrome(state: LiveTickerState, model: TickerModel, narrow: Boolean, maxWidth: Dp, maxHeight: Dp, nav: TickerNav, mode: TickerMode, anchor: TickerAnchor, modifier: Modifier) {
    val motion = LocalMotion.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    var cursor by remember { mutableIntStateOf(0) }
    val expanded = state.expanded
    // Pointer over it, or the keyboard inside it, keeps it open. Over the collapsed pill the pointer must rest a moment first.
    LaunchedEffect(hovered, focused) {
        if (hovered && !focused && !expanded) delay(TICKER_HOVER_OPEN_MS)
        state.hold(hovered || focused, mode)
    }
    // The width each tile gets is decided here so that the links the keyboard walks are the ones the tiles draw.
    val scale = LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
    val tiles = tilesThatFit(model.tiles, narrow, TickerPlacement.compact(maxWidth / scale), maxHeight)
    val tileWidth = trayTileWidth(tiles.size, narrow, maxWidth, scale)
    val fontScale = LocalDensity.current.fontScale
    val shownLinks = tiles.map { fittingLinks(it.links, tileWidth, fontScale) }
    val items = remember(tiles, shownLinks) {
        buildList {
            tiles.indices.forEach { i -> add(TrayItem.Body(i)); shownLinks[i].indices.forEach { add(TrayItem.Link(i, it)) } }
            Control.entries.forEach { add(TrayItem.Part(it)) }
        }
    }
    fun activate(item: TrayItem) = when (item) {
        is TrayItem.Body -> nav.go(tiles[item.tile].target)
        is TrayItem.Link -> nav.go(shownLinks[item.tile][item.link].target)
        is TrayItem.Part -> when (item.control) {
            Control.Pin -> state.togglePin(mode)
            Control.Collapse -> state.collapse(mode)
            Control.Dismiss -> state.dismiss(mode)
        }
    }
    LaunchedEffect(items.size) { cursor = cursor.coerceIn(0, (items.size - 1).coerceAtLeast(0)) }
    // Expanding or collapsing: a finite move (a fade and a small pop from the anchor corner), snapped under Reduce motion.
    val progress = remember { Animatable(if (expanded) 1f else 0f) }
    LaunchedEffect(expanded) {
        val to = if (expanded) 1f else 0f
        if (motion.reduced) progress.snapTo(to) else progress.animateTo(to, if (expanded) motion.enter(MotionDur.Standard) else motion.exit(MotionDur.Standard))
    }
    val shape = Tech.Plate
    Box(modifier
        .testTag(TICKER_TAG)
        .semantics { contentDescription = "Live ticker. ${model.pill?.reading.orEmpty()}. ${if (expanded) "Open" else "Collapsed"}." }
        .focusRequester(focus)
        .onFocusChanged { focused = it.isFocused }
        .focusable()
        .hoverable(interaction)
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.Escape -> if (expanded) { state.collapse(mode); true } else false
                Key.DirectionRight, Key.DirectionDown -> { if (!expanded) { state.open(mode); cursor = 0 } else cursor = (cursor + 1).coerceAtMost(items.lastIndex); true }
                Key.DirectionLeft, Key.DirectionUp -> if (expanded) { cursor = (cursor - 1).coerceAtLeast(0); true } else false
                Key.MoveHome -> if (expanded) { cursor = 0; true } else false
                Key.MoveEnd -> if (expanded) { cursor = items.lastIndex; true } else false
                Key.Enter, Key.NumPadEnter, Key.Spacebar -> { if (!expanded) { state.open(mode); cursor = 0 } else items.getOrNull(cursor)?.let(::activate); true }
                else -> false
            }
        }) {
        // The pill and the plate share the corner or edge the page's anchor names, so the pill does not hop while the plate opens from it.
        val corner = androidx.compose.ui.BiasAlignment(anchor.fx * 2 - 1, anchor.fy * 2 - 1)
        Box(contentAlignment = corner) {
            if (progress.value > 0f) {
                val s = if (motion.full) .94f + .06f * progress.value else 1f
                Box(Modifier.graphicsLayer { alpha = progress.value; scaleX = s; scaleY = s; transformOrigin = TransformOrigin(anchor.fx, anchor.fy) }) {
                    Tray(state, model, tiles, narrow, maxWidth, nav, mode, focused, cursor, items, ::activate)
                }
            }
            if (progress.value < 1f) {
                Box(Modifier.graphicsLayer { alpha = 1f - progress.value }) { Pill(state, model, focused && !expanded, mode) }
            }
        }
    }
}

/** The tiles that fit: at most four, two in a compact window, and in the narrow column as many as the height allows. */
private fun tilesThatFit(tiles: List<TickerTile>, narrow: Boolean, compact: Boolean, maxHeight: Dp): List<TickerTile> {
    if (!narrow) return tiles.take(if (compact) 2 else HoodTicker.MAX_TILES)
    val rows = ((maxHeight.value * .45f) / 58f).toInt().coerceIn(1, HoodTicker.MAX_TILES)
    return tiles.take(minOf(rows, HoodTicker.MAX_TILES))
}

// ---- the pill ----

@Composable private fun Pill(state: LiveTickerState, model: TickerModel, ring: Boolean, mode: TickerMode) {
    val pill = model.pill ?: return
    val tone = model.tone
    val edge by animateColorAsState(if (tone >= TickerTone.Warning) toneInk(tone).copy(alpha = .7f) else Tech.Edge, LocalMotion.current.fade(MotionDur.Quick))
    HelpTip("Live ticker · open the tiles") {
        Row(Modifier.testTag(TICKER_PILL_TAG)
            .semantics(mergeDescendants = true) { contentDescription = "Live ticker: ${pill.reading}. Open the tiles." }
            .focusRing(ring, Tech.Control, gap = 2.dp)
            .background(Tech.Panel, Tech.Control).border(1.dp, edge, Tech.Control)
            .clickable(role = Role.Button, onClickLabel = "Open the live ticker") { state.open(mode) }
            .pointerHoverIcon(PointerIcon.Hand)
            .heightIn(min = 26.dp).padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            ToneDot(tone)
            Text(pill.reading, fontFamily = Tech.Mono, fontSize = 11.sp, lineHeight = 14.sp, color = Tech.Ink, maxLines = 1, softWrap = false)
        }
    }
}

// ---- the plate ----

@Composable private fun Tray(state: LiveTickerState, model: TickerModel, tiles: List<TickerTile>, narrow: Boolean, maxWidth: Dp, nav: TickerNav, mode: TickerMode,
    focused: Boolean, cursor: Int, items: List<TrayItem>, activate: (TrayItem) -> Unit) {
    val motion = LocalMotion.current
    val scale = LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
    val edge by animateColorAsState(if (model.tone >= TickerTone.Warning) toneInk(model.tone).copy(alpha = .75f) else Tech.Edge, motion.fade(MotionDur.Quick))
    // Tiles come and go with a short fade and slide; a tile that leaves is kept on screen until it has faded.
    val shownTiles = rememberSlots(tiles)
    val count = shownTiles.size.coerceAtLeast(1)
    val tileWidth = trayTileWidth(count, narrow, maxWidth, scale)
    val cursorItem = items.getOrNull(cursor)
    Surface(Modifier.animateTrayWidth(motion), shape = Tech.Plate, color = Tech.Panel, border = androidx.compose.foundation.BorderStroke(1.dp, edge),
        shadowElevation = if (motion.lite || motion.reduced) 0.dp else 8.dp) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val layout: @Composable () -> Unit = {
                shownTiles.forEach { slot ->
                    key(slot.id) {
                        TileSlot(slot, tileWidth, equal = !narrow) { tile ->
                            TileView(tile, tileWidth, narrow, nav, focused, cursorItem, tiles.indexOfFirst { it.id == tile.id }, motion)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // The tiles of a row are as tall as the tallest, so a tile with a wrapped link does not leave the others ragged.
                if (narrow) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { layout() } else EqualHeightRow(tileWidth, 6.dp, layout)
                Controls(state, mode, focused, cursorItem)
            }
            val hint = if (focused) cursorItem?.let { hintFor(it, tiles, tiles.map { tile -> fittingLinks(tile.links, tileWidth, LocalDensity.current.fontScale) }) } else null
            if (hint != null) Text(hint, Modifier.padding(horizontal = 4.dp), fontFamily = Tech.Mono, fontSize = 10.sp, lineHeight = 13.sp, color = Tech.Amber, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A row of tiles of one [width], all as tall as the tallest. A tile whose links wrapped is taller; asking each its intrinsic height at its own
 * width (it is known here) keeps the others from ending short, which an IntrinsicSize modifier cannot, because the row itself has no width yet.
 */
@Composable private fun EqualHeightRow(width: Dp, gap: Dp, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val w = width.roundToPx(); val g = gap.roundToPx()
        val h = measurables.maxOfOrNull { it.maxIntrinsicHeight(w) } ?: 0
        val placeables = measurables.map { it.measure(androidx.compose.ui.unit.Constraints.fixed(w, h)) }
        layout(placeables.size * w + (placeables.size - 1).coerceAtLeast(0) * g, h) {
            placeables.forEachIndexed { i, p -> p.place(i * (w + g), 0) }
        }
    }
}

/** A tile's width: the plate is at most 520 dp (more at large text), shared by the tiles, never narrower than [TILE_MIN]. */
internal fun trayTileWidth(count: Int, narrow: Boolean, maxWidth: Dp, scale: Float): Dp {
    if (narrow) return NARROW_TILE
    val limit = minOf(TRAY_MAX_WIDTH * scale, maxWidth)
    return ((limit - 6.dp * 3 - 24.dp - 6.dp * (count.coerceAtLeast(1) - 1)) / count.coerceAtLeast(1)).coerceIn(TILE_MIN, TILE_MAX * scale)
}

/** The plate grows and shrinks in width as tiles come and go, in one short move (none under Reduce motion). */
@Composable private fun Modifier.animateTrayWidth(motion: RmMotion): Modifier =
    if (motion.reduced) this else this.animateContentSize(tween(motion.ms(MotionDur.Short), easing = MotionEase.Standard))

private fun hintFor(item: TrayItem, tiles: List<TickerTile>, shown: List<List<TickerLink>>): String? = when (item) {
    is TrayItem.Body -> tiles.getOrNull(item.tile)?.target?.describe()
    is TrayItem.Link -> shown.getOrNull(item.tile)?.getOrNull(item.link)?.target?.describe()
    is TrayItem.Part -> when (item.control) { Control.Pin -> "Keep it open"; Control.Collapse -> "Collapse (Escape)"; Control.Dismiss -> "Hide it for this session" }
}

// ---- tiles arriving and leaving ----

private class TrayTileSlot(val id: TickerTileId, tile: TickerTile, val enter: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>) {
    var tile by mutableStateOf(tile)
    var leaving by mutableStateOf(false)
}

/** The tiles to draw: the current ones in order, each kept until its own exit has finished. */
@Composable private fun rememberSlots(tiles: List<TickerTile>): List<TrayTileSlot> {
    val motion = LocalMotion.current
    // The tiles that are there when the plate first appears come with it; only later arrivals and departures are animated.
    val slots = remember { mutableStateListOf<TrayTileSlot>().apply { tiles.forEach { add(TrayTileSlot(it.id, it, Animatable(1f))) } } }
    LaunchedEffect(tiles) {
        val present = tiles.map { it.id }
        tiles.forEach { tile ->
            val have = slots.firstOrNull { it.id == tile.id }
            if (have == null) {
                val at = tiles.indexOf(tile).coerceAtMost(slots.size)
                slots.add(at, TrayTileSlot(tile.id, tile, Animatable(if (motion.reduced || slots.isEmpty()) 1f else 0f)))
            } else { have.tile = tile; have.leaving = false }
        }
        // Keep the order of the model; a tile that is leaving stays where it was until it has gone.
        val order = slots.sortedBy { slot -> present.indexOf(slot.id).let { if (it < 0) Int.MAX_VALUE else it } }
        if (order != slots.toList()) { slots.clear(); slots.addAll(order) }
        slots.filter { it.id !in present && !it.leaving }.forEach { it.leaving = true }
    }
    // Entrances and exits are plain finite animations, one per tile.
    slots.forEach { slot ->
        LaunchedEffect(slot, slot.leaving) {
            val to = if (slot.leaving) 0f else 1f
            if (motion.reduced) slot.enter.snapTo(to) else slot.enter.animateTo(to, if (slot.leaving) motion.exit(MotionDur.Short) else motion.enter(MotionDur.Standard))
            if (slot.leaving) slots.remove(slot)
        }
    }
    return slots.toList()
}

@Composable private fun TileSlot(slot: TrayTileSlot, width: Dp, equal: Boolean, content: @Composable (TickerTile) -> Unit) {
    val motion = LocalMotion.current
    val rise = with(LocalDensity.current) { motion.travel(6f).dp.toPx() }
    // In a row the tiles are made as tall as the tallest (EqualHeightRow gives them that height); in a column each is as tall as it needs.
    Box(Modifier.width(width).then(if (equal) Modifier.fillMaxHeight() else Modifier).graphicsLayer { alpha = slot.enter.value; translationY = (1f - slot.enter.value) * rise }) { content(slot.tile) }
}

// ---- one tile ----

@Composable private fun TileView(tile: TickerTile, width: Dp, narrow: Boolean, nav: TickerNav, trayFocused: Boolean, cursorItem: TrayItem?, index: Int, motion: RmMotion) {
    val ink = toneInk(tile.tone)
    val rail by animateColorAsState(when (tile.tone) { TickerTone.Calm -> Tech.Edge; TickerTone.Busy -> Tech.Amber.copy(alpha = .75f); else -> ink }, motion.fade(MotionDur.Short))
    val bodyRing = trayFocused && cursorItem == TrayItem.Body(index)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val wash by animateColorAsState(if (hovered || bodyRing) Tech.Raised else Tech.Black.copy(alpha = if (Tech.Light) .04f else .22f), motion.fade(MotionDur.Quick))
    Row(Modifier.testTag(tickerTileTag(tile.id)).then(if (narrow) Modifier.height(IntrinsicSize.Min) else Modifier.fillMaxHeight()).background(wash, Tech.Control).border(1.dp, if (hovered) Tech.Amber.copy(alpha = .55f) else Tech.Edge, Tech.Control)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(rail))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            HelpTip(tile.target.describe()) {
                Column(Modifier.fillMaxWidth()
                    .focusRing(bodyRing, Tech.Control, gap = 2.dp)
                    .hoverable(interaction)
                    .semantics(mergeDescendants = true) { contentDescription = "${tile.label}: ${tile.headline}${tile.detail?.let { ". $it" }.orEmpty()}. ${tile.target.describe()}" }
                    .focusProperties { canFocus = false }
                    .clickable(role = Role.Button, onClickLabel = tile.target.describe()) { nav.go(tile.target) }
                    .pointerHoverIcon(PointerIcon.Hand)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(tile.label, Modifier.weight(1f, fill = false), fontFamily = Tech.Mono, fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = .9.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ToneDot(tile.tone)
                    }
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // The headline keeps its own width (smaller when the tile is narrow and the words are long); the sparkline takes what is left.
                        TickText(tile.headline, headlineSize(tile.headline, width - 24.dp, LocalDensity.current.fontScale), ink, Modifier, motion)
                        if (tile.history.size > 1 && !narrow) TickerSpark(tile.history, ink, Modifier.weight(1f).height(16.dp))
                    }
                    tile.detail?.let { Text(it, fontFamily = Tech.Mono, fontSize = 10.sp, lineHeight = 14.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            // Links are one line: as many as the tile's width holds (all three on a wide tile). The keyboard walks the same list.
            val links = fittingLinks(tile.links, width, LocalDensity.current.fontScale)
            if (links.isNotEmpty()) Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(LINK_GAP.dp)) {
                links.forEachIndexed { i, link -> LinkText(tile, i, link, nav, trayFocused && cursorItem == TrayItem.Link(index, i)) }
            }
        }
    }
}

@Composable private fun LinkText(tile: TickerTile, index: Int, link: TickerLink, nav: TickerNav, ring: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val line by animateColorAsState(if (hovered || ring) Tech.Amber else Tech.Amber.dimmed(.4f), LocalMotion.current.fade(MotionDur.Micro))
    HelpTip(link.target.describe()) {
        Text(link.label.uppercase(), Modifier.testTag(tickerLinkTag(tile.id, index))
            .focusRing(ring, Tech.Control, gap = 2.dp)
            .hoverable(interaction)
            .drawBehind { drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .semantics { contentDescription = "${link.label}. ${link.target.describe()}" }
            .focusProperties { canFocus = false }
            .clickable(role = Role.Button, onClickLabel = link.target.describe()) { nav.go(link.target) }
            .pointerHoverIcon(PointerIcon.Hand).padding(vertical = 1.dp),
            fontFamily = Tech.Mono, fontSize = 9.5.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium, color = Tech.Amber, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

/** The headline. When its words change (not on first sight) it flashes once towards amber and settles: a tick, not a bounce. */
@Composable private fun TickText(text: String, size: androidx.compose.ui.unit.TextUnit, ink: Color, modifier: Modifier, motion: RmMotion) {
    val flash = remember { Animatable(0f) }
    var seen by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(text) {
        val first = seen == null
        seen = text
        // Software drawing and Reduce motion get the plain change: no frames for a flourish.
        if (!first && motion.full) { flash.snapTo(1f); flash.animateTo(0f, tween(MotionDur.Emphasis, easing = MotionEase.Standard)) }
    }
    Text(text, modifier, fontFamily = Tech.Mono, fontSize = size, lineHeight = (size.value + 4).sp, fontWeight = FontWeight.SemiBold,
        color = lerp(ink, Tech.Amber, flash.value * .6f), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
}

private const val LINK_GAP = 9
/** The tile's inner width: its border, rail and padding are 21 dp. */
private const val TILE_FRAME = 21

/**
 * The links of a tile that fit its width on one line, in order, always at least the first. Monospace is about .6 em a character, so the
 * width of a label is known without measuring it; the keyboard and the drawing both use this list.
 */
internal fun fittingLinks(links: List<TickerLink>, tileWidth: Dp, fontScale: Float): List<TickerLink> {
    val room = tileWidth.value - TILE_FRAME
    val out = ArrayList<TickerLink>(links.size)
    var used = 0f
    for (link in links) {
        val need = (if (out.isEmpty()) 0f else used + LINK_GAP) + link.label.length * 5.8f * fontScale.coerceAtLeast(1f)
        if (out.isNotEmpty() && need > room) break
        out += link; used = need
    }
    return out
}

/** 18 sp where the words fit, down to 12 sp where they would not (monospace is about .6 em a character; text scale widens it). */
internal fun headlineSize(text: String, inner: Dp, fontScale: Float): androidx.compose.ui.unit.TextUnit =
    (inner.value / fontScale.coerceAtLeast(1f) / (text.length.coerceAtLeast(1) * .62f)).coerceIn(12f, 18f).sp

// ---- the small parts ----

@Composable private fun ToneDot(tone: TickerTone) {
    val color by animateColorAsState(when (tone) { TickerTone.Calm -> Tech.Green; TickerTone.Busy -> Tech.Amber; TickerTone.Warning -> hotAmber(); TickerTone.Error -> Tech.Red }, LocalMotion.current.fade(MotionDur.Short))
    Canvas(Modifier.size(6.dp)) { drawRect(color) }
}

/** A hairline of the last minute with a faint fill under it. Drawn once a second at most. */
@Composable private fun TickerSpark(values: List<Int>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val peak = (values.maxOrNull() ?: 0).coerceAtLeast(1)
        val step = size.width / (values.size - 1).coerceAtLeast(1)
        val pad = 1.5.dp.toPx()
        fun y(v: Int) = size.height - pad - (size.height - 2 * pad) * v / peak
        val line = Path().apply { values.forEachIndexed { i, v -> if (i == 0) moveTo(0f, y(v)) else lineTo(step * i, y(v)) } }
        val fill = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(fill, color.copy(alpha = .12f), style = Fill)
        drawPath(line, color.copy(alpha = .85f), style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable private fun Controls(state: LiveTickerState, mode: TickerMode, trayFocused: Boolean, cursorItem: TrayItem?) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        ControlButton(Control.Pin, state.pinned, "Keep it open", "Let it settle", trayFocused && cursorItem == TrayItem.Part(Control.Pin)) { state.togglePin(mode) }
        ControlButton(Control.Collapse, false, "Collapse", "Collapse", trayFocused && cursorItem == TrayItem.Part(Control.Collapse)) { state.collapse(mode) }
        ControlButton(Control.Dismiss, false, "Hide it for this session", "Hide it for this session", trayFocused && cursorItem == TrayItem.Part(Control.Dismiss)) { state.dismiss(mode) }
    }
}

@Composable private fun ControlButton(kind: Control, on: Boolean, label: String, onLabel: String, ring: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val ink = if (on || hovered || ring) Tech.Amber else Tech.Muted
    HelpTip(if (on) onLabel else label) {
        Box(Modifier.size(20.dp).focusRing(ring, Tech.Control, gap = 2.dp).hoverable(interaction)
            .border(1.dp, if (on) Tech.Amber else if (hovered) Tech.Amber.copy(alpha = .5f) else Tech.Edge, Tech.Control)
            .background(if (on) Tech.AmberWash else Color.Transparent, Tech.Control)
            .semantics { contentDescription = if (kind == Control.Pin) "Keep the live ticker open" else label; role = if (kind == Control.Pin) Role.Switch else Role.Button
                if (kind == Control.Pin) stateDescription = if (on) "Pinned" else "Not pinned" }
            .focusProperties { canFocus = false }
            .clickable(role = if (kind == Control.Pin) Role.Switch else Role.Button, onClickLabel = label, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(10.dp)) {
                val stroke = 1.2.dp.toPx()
                when (kind) {
                    // An upright drawing pin: a wide head, a shaft and the needle; filled when it is on.
                    Control.Pin -> {
                        val w = size.width; val h = size.height
                        val head = androidx.compose.ui.geometry.Size(w * .72f, h * .2f)
                        val shaft = androidx.compose.ui.geometry.Size(w * .34f, h * .34f)
                        if (on) {
                            drawRect(ink, Offset(w * .14f, h * .04f), head)
                            drawRect(ink, Offset(w * .33f, h * .24f), shaft)
                        } else {
                            drawRect(ink, Offset(w * .14f, h * .04f), head, style = Stroke(stroke))
                            drawRect(ink, Offset(w * .33f, h * .24f), shaft, style = Stroke(stroke))
                        }
                        drawLine(ink, Offset(w * .5f, h * .58f), Offset(w * .5f, h * .96f), stroke, StrokeCap.Round)
                    }
                    Control.Collapse -> {
                        drawLine(ink, Offset(size.width * .12f, size.height * .36f), Offset(size.width * .5f, size.height * .72f), stroke, StrokeCap.Round)
                        drawLine(ink, Offset(size.width * .5f, size.height * .72f), Offset(size.width * .88f, size.height * .36f), stroke, StrokeCap.Round)
                    }
                    Control.Dismiss -> drawWindowGlyph(WindowGlyph.Close, ink)
                }
            }
        }
    }
}
