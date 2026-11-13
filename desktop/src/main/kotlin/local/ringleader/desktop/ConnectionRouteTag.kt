package local.ringleader.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import local.ringleader.core.*

/** How much of the tag fits: the full label, one word, or the glyph alone (the details stay in the tooltip). */
internal enum class RouteTagMode { Full, Short, Glyph }

internal enum class RouteTone { Plain, Tunnel, Caution, Quiet }

internal enum class RouteGlyph { Direct, Tunnel, Proxy, Local, Unknown, Pending, None }

/** Everything the tag and its tooltip show, already concealed for Showcase. */
internal data class RouteTagView(
    val label: String,
    val short: String,
    val tone: RouteTone,
    val glyph: RouteGlyph,
    val rows: List<Pair<String, String>>,
    val notes: List<String>,
) {
    /** One spoken sentence set for screen readers, built from the same concealed text. */
    val description: String get() = buildString {
        append("Route to server: ").append(spokenRouteLabel(label)).append('.')
        rows.forEach { (k, v) -> append(' ').append(k).append(": ").append(v).append('.') }
        notes.forEach { append(' ').append(it) }
    }
}

private val spokenWords = mapOf("VPN" to "VPN", "WI-FI" to "Wi-Fi", "IPSEC" to "IPsec", "OPENVPN" to "OpenVPN", "WIREGUARD" to "WireGuard",
    "TAILSCALE" to "Tailscale", "ETHERNET" to "Ethernet")

/** "VPN · TAILSCALE" read aloud as "VPN, Tailscale"; "ROUTE UNKNOWN" as "Route unknown". */
internal fun spokenRouteLabel(label: String): String = label.split(" · ").mapIndexed { i, part ->
    spokenWords[part] ?: part.lowercase().let { if (i == 0) it.replaceFirstChar { c -> c.uppercase() } else it }
}.joinToString(", ")

internal const val ROUTE_ESTIMATE_NOTE = "This is the operating system's route for the address Java resolves first. It is an estimate, not a reading of the live connection."
internal const val ROUTE_DIRECT_NOTE = "Direct means this computer's own route uses an ordinary interface with no tunnel on this computer. VPNs, tunnels or proxies on routers beyond it, and IPsec policies without their own interface, are not visible."
internal const val ROUTE_HIDDEN = "Hidden"

internal fun vpnKindLabel(kind: VpnKind?): String? = when (kind) {
    VpnKind.Tailscale -> "Tailscale"; VpnKind.WireGuard -> "WireGuard"; VpnKind.OpenVpn -> "OpenVPN"; VpnKind.IpSec -> "IPsec"
    VpnKind.Other, null -> null
}

private val routeClock = DateTimeFormatter.ofPattern("HH:mm:ss")

internal fun routeAge(at: Instant, now: Instant): String {
    val s = Duration.between(at, now).seconds.coerceAtLeast(0)
    return when { s < 5 -> "just now"; s < 60 -> "$s s ago"; s < 3600 -> "${s / 60} min ago"; else -> "${s / 3600} h ago" }
}

/** The tag's text for [state]. Null when nothing is shown (no connection in view). */
internal fun routeTagView(state: RouteTagState, showcase: Boolean, now: Instant, zone: ZoneId = ZoneId.systemDefault()): RouteTagView? {
    fun hide(value: String) = if (showcase) ROUTE_HIDDEN else value
    return when (state) {
        RouteTagState.Hidden -> null
        RouteTagState.Demo -> RouteTagView("DEMO · NO ROUTE", "DEMO", RouteTone.Quiet, RouteGlyph.None,
            listOf("Server" to "Demonstration data"), listOf("There is no server connection, so there is no route to show."))
        RouteTagState.Browser -> RouteTagView("ROUTE NOT SHOWN", "N/A", RouteTone.Quiet, RouteGlyph.Unknown,
            listOf("Scope" to "RingMASTER host to server"),
            listOf("In browser mode the RingMASTER host makes this connection, not your browser. Its route is not inspected from browser sessions."))
        is RouteTagState.Checking -> RouteTagView("CHECKING ROUTE", "CHECKING", RouteTone.Quiet, RouteGlyph.Pending,
            listOf("Server" to hide(state.target.hostPort), "Transport" to "HTTPS"), listOf("Reading this computer's route to the server."))
        is RouteTagState.Observed -> observedView(state.observation, state.refreshing, showcase, now, zone)
    }
}

private fun observedView(o: RouteObservation, refreshing: Boolean, showcase: Boolean, now: Instant, zone: ZoneId): RouteTagView {
    fun hide(value: String) = if (showcase) ROUTE_HIDDEN else value
    val rows = mutableListOf<Pair<String, String>>()
    val notes = mutableListOf<String>()
    rows += "Server" to hide(o.target.hostPort)
    rows += "Transport" to ("HTTPS" + (o.family?.let { " over ${it.label}" } ?: ""))
    o.address?.let { a -> rows += "Address" to (hide(a) + if (o.addressCount > 1) " · first of ${o.addressCount} DNS answers" else "") }
    o.proxy?.let { p -> rows += "Proxy" to (if (showcase) p.substringBefore(" proxy") + " proxy " + ROUTE_HIDDEN else p) }
    o.interfaceName?.let { name -> rows += "Interface" to listOfNotNull(o.medium?.label ?: "Type not reported", hide(name)).joinToString(" · ") }
    if (o.interfaceName != null) rows += "Next hop" to (o.gateway?.let(::hide) ?: "On-link (no gateway)")
    if (o.kind == RouteKind.Vpn || o.tunnelName != null) {
        val kind = vpnKindLabel(o.vpnKind) ?: if (o.kind == RouteKind.Vpn) "VPN" else null
        val name = o.tunnelName?.takeUnless { it.equals(kind, ignoreCase = true) }?.let(::hide) ?: if (o.kind == RouteKind.Vpn && o.tunnelName == null) "not named in the VPN list" else null
        rows += "Tunnel" to listOfNotNull(kind, name).joinToString(" · ")
        o.evidence?.let { rows += "Matched" to it.label.replaceFirstChar { c -> c.uppercase() } }
    }
    when (val peer = o.peerPath) {
        is PeerPath.Direct -> rows += "Peer link" to "Direct UDP to the peer (${hide(peer.endpoint)}), still inside Tailscale"
        is PeerPath.Relayed -> rows += "Peer link" to "Relayed through Tailscale DERP ${peer.region}"
        PeerPath.NotReported -> rows += "Peer link" to "No current path reported by Tailscale"
        PeerPath.NotPeer -> rows += "Peer link" to "Not a peer's own address (subnet route or exit node); no peer path is shown"
        null -> {}
    }
    // The estimate note belongs to a route that was actually read (an interface was named).
    val routeRead = o.interfaceName != null
    if (o.kind != RouteKind.Proxy && o.kind != RouteKind.Local && o.address != null) {
        rows += "VPN check" to if (o.vpnComplete) "Complete" else if (showcase) "Incomplete · details hidden" else "Incomplete · ${o.vpnGaps.firstOrNull() ?: "not read"}"
    }
    rows += "Observed" to "${routeClock.format(o.at.atZone(zone))} · ${routeAge(o.at, now)}" + if (refreshing) " · re-checking" else ""
    o.reason?.let { rows += (if (o.kind == RouteKind.Unknown) "Why" else "Note") to concealReason(it, showcase) }
    when (o.kind) {
        RouteKind.Direct -> notes += ROUTE_DIRECT_NOTE
        RouteKind.Vpn -> if (o.evidence == RouteEvidence.Driver || o.evidence == RouteEvidence.TunnelAdapter) notes += "The VPN list did not name this tunnel. Its type comes from the interface itself."
        else -> {}
    }
    if (routeRead) notes += ROUTE_ESTIMATE_NOTE
    val medium = when (o.medium) { RouteMedium.Ethernet -> "ETHERNET"; RouteMedium.WiFi -> "WI-FI"; RouteMedium.Mobile -> "MOBILE"; RouteMedium.Virtual -> "VIRTUAL"; else -> null }
    return when (o.kind) {
        RouteKind.Direct -> RouteTagView(listOfNotNull("DIRECT", medium).joinToString(" · "), "DIRECT", RouteTone.Plain, RouteGlyph.Direct, rows, notes)
        RouteKind.Vpn -> RouteTagView(listOfNotNull("VPN", vpnKindLabel(o.vpnKind)?.uppercase()).joinToString(" · "), "VPN", RouteTone.Tunnel, RouteGlyph.Tunnel, rows, notes)
        RouteKind.Proxy -> RouteTagView("VIA PROXY", "PROXY", RouteTone.Caution, RouteGlyph.Proxy, rows, notes)
        RouteKind.Local -> RouteTagView("LOCALHOST", "LOCAL", RouteTone.Quiet, RouteGlyph.Local, rows, notes)
        RouteKind.Unknown -> RouteTagView("ROUTE UNKNOWN", "UNKNOWN", RouteTone.Quiet, RouteGlyph.Unknown, rows, notes)
    }
}

/** Tool diagnostics can quote identities that are not otherwise in this observation. */
private fun concealReason(reason: String, showcase: Boolean): String =
    if (showcase) "Details hidden in Showcase." else reason

internal fun routeToneColor(tone: RouteTone): Color = when (tone) {
    RouteTone.Plain -> Tech.Ink
    RouteTone.Tunnel -> Tech.Green
    RouteTone.Caution -> Tech.Amber
    RouteTone.Quiet -> Tech.Muted
}

internal const val ROUTE_TAG_TEST_TAG = "connection-route-tag"
/** The tooltip's text is read from the tag's description, so the tooltip itself carries only this marker. */
internal const val ROUTE_TIP_TEST_TAG = "connection-route-tip"

/** Hover delay before the tooltip opens, as the app's other hover tips. Keyboard focus opens it at once. */
internal const val ROUTE_TIP_DELAY_MS = 450L

private val routeTipProperties = PopupProperties(
    focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false,
    clippingEnabled = false, usePlatformDefaultWidth = false, usePlatformInsets = false,
)

/**
 * Below the tag and right-aligned to it; above it when only that fits. It never covers the tag: a tooltip over its own
 * anchor takes the hover and closes itself. When neither side fits (a very large interface or text size) it stays below
 * and the window clips it; the tag's description still carries every line.
 */
internal class RouteTipPosition(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        routeTipOffset(anchorBounds, windowSize, popupContentSize, gap)
}

internal fun routeTipOffset(anchor: IntRect, window: IntSize, popup: IntSize, gap: Int): IntOffset {
    val x = (anchor.right - popup.width).coerceAtMost(window.width - popup.width).coerceAtLeast(0)
    val below = anchor.bottom + gap
    val y = when {
        below + popup.height <= window.height || window.height <= 0 -> below
        anchor.top - gap - popup.height >= 0 -> anchor.top - gap - popup.height
        else -> below
    }
    return IntOffset(x, y)
}

/**
 * The connection-route tag: a small bordered mono label like the session chip, with a drawn path glyph. Hovering (after
 * [ROUTE_TIP_DELAY_MS]) or keyboard focus shows the details in a scene-layer tooltip that never takes focus or clicks.
 * Escape closes the tooltip while the tag has focus. Nothing animates.
 */
@Composable internal fun ConnectionRouteTag(state: RouteTagState, mode: RouteTagMode, modifier: Modifier = Modifier, clock: () -> Instant = Instant::now) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val tipInteraction = remember { MutableInteractionSource() }
    val tipHovered by tipInteraction.collectIsHoveredAsState()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val density = LocalDensity.current
    val tipHeight = with(density) { maxOf(windowHeight - anchor.bottom - 12, anchor.top - 12).coerceAtLeast(1).toDp() }
    var hoverReady by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(hovered, tipHovered) {
        if (hovered) { delay(ROUTE_TIP_DELAY_MS); hoverReady = true }
        else if (!tipHovered) { delay(150); hoverReady = false }
    }
    LaunchedEffect(hovered, tipHovered, focused) { if (!hovered && !tipHovered && !focused) dismissed = false }
    val open = (hoverReady || focused) && !dismissed && state != RouteTagState.Hidden
    var now by remember { mutableStateOf(clock()) }
    LaunchedEffect(open, state) { now = clock(); while (open) { delay(1_000); now = clock() } }
    val view = routeTagView(state, Appearance.showcase, now) ?: return
    val color = routeToneColor(view.tone)
    Box(modifier.onGloballyPositioned { anchor = it.boundsInWindow().roundToIntRect() }) {
        Row(Modifier
            .clearAndSetSemantics { contentDescription = view.description; testTag = ROUTE_TAG_TEST_TAG }
            .hoverable(interaction)
            .focusable(interactionSource = interaction)
            .onPreviewKeyEvent {
                if (!open || it.type != KeyEventType.KeyDown) false
                else if (it.key == Key.Escape) { dismissed = true; true }
                else if (it.key in listOf(Key.DirectionDown, Key.DirectionUp, Key.PageDown, Key.PageUp, Key.MoveHome, Key.MoveEnd)) {
                    scope.launch {
                        when (it.key) {
                            Key.MoveHome -> scroll.scrollTo(0)
                            Key.MoveEnd -> scroll.scrollTo(scroll.maxValue)
                            else -> scroll.scrollBy(when (it.key) { Key.DirectionDown -> 48f; Key.DirectionUp -> -48f; Key.PageDown -> windowHeight * .5f; else -> -windowHeight * .5f })
                        }
                    }; true
                } else false
            }
            .focusRing(focused, Tech.Control, gap = 2.dp)
            .border(1.dp, color.copy(alpha = if (view.tone == RouteTone.Quiet) .45f else .6f), Tech.Control)
            .heightIn(min = 18.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Canvas(Modifier.size(width = 16.dp, height = 10.dp)) { drawRouteGlyph(view.glyph, color) }
            if (mode != RouteTagMode.Glyph) Text(if (mode == RouteTagMode.Full) view.label else view.short, fontFamily = Tech.Mono, fontSize = 10.sp,
                letterSpacing = 1.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (open) RouteTip(view, color, tipHeight, scroll, tipInteraction)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun RouteTip(view: RouteTagView, color: Color, maxHeight: Dp, scroll: androidx.compose.foundation.ScrollState, interaction: MutableInteractionSource) {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val maxWidth = with(density) { (window.width.takeIf { it > 24 } ?: 480).toDp() - 16.dp }.coerceAtMost(400.dp).coerceAtLeast(160.dp)
    val position = remember(density) { RouteTipPosition(with(density) { 6.dp.roundToPx() }) }
    Popup(popupPositionProvider = position, onDismissRequest = null, properties = routeTipProperties) {
        Surface(color = Tech.Raised, shape = Tech.Control, border = BorderStroke(1.dp, Tech.Edge), shadowElevation = if (LocalMotion.current.lite) 0.dp else 4.dp) {
            Box(Modifier.widthIn(max = maxWidth).heightIn(max = maxHeight).hoverable(interaction).clearAndSetSemantics { testTag = ROUTE_TIP_TEST_TAG }) {
            Column(Modifier.padding(end = 12.dp).verticalScroll(scroll).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Canvas(Modifier.size(width = 16.dp, height = 10.dp)) { drawRouteGlyph(view.glyph, color) }
                        Text("ROUTE TO SERVER", fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Muted)
                    }
                    Text(view.label, fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold,
                        color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(2.dp))
                view.rows.forEach { (label, value) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(label.uppercase(), Modifier.width(78.dp), fontFamily = Tech.Mono, fontSize = 9.sp, letterSpacing = .6.sp, color = Tech.Muted, lineHeight = 14.sp)
                        Text(value, fontSize = 11.sp, lineHeight = 14.sp, color = if (value == ROUTE_HIDDEN || value.contains(ROUTE_HIDDEN)) Tech.Muted else Tech.Ink)
                    }
                }
                if (view.notes.isNotEmpty()) Spacer(Modifier.height(2.dp))
                view.notes.forEach { Text(it, fontSize = 10.sp, lineHeight = 13.sp, color = Tech.Muted) }
            }
            Box(Modifier.matchParentSize()) {
                VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
            }
        }
    }
}

/**
 * Two end points and the path between them, in 1dp strokes like the window glyphs: a straight line (direct), a line
 * through a box (tunnel), a line through a ring (proxy), a short loop (localhost), a dashed line (unknown) and open
 * points (checking).
 */
internal fun DrawScope.drawRouteGlyph(glyph: RouteGlyph, color: Color) {
    val stroke = 1.dp.toPx().coerceAtLeast(1f)
    val r = 1.6.dp.toPx()
    val w = size.width; val h = size.height; val cy = h / 2
    val left = Offset(r + stroke, cy); val right = Offset(w - r - stroke, cy)
    fun dots(filled: Boolean) {
        if (filled) { drawCircle(color, r, left); drawCircle(color, r, right) }
        else { drawCircle(color, r, left, style = Stroke(stroke)); drawCircle(color, r, right, style = Stroke(stroke)) }
    }
    when (glyph) {
        RouteGlyph.Direct -> { drawLine(color, left, right, stroke); dots(true) }
        RouteGlyph.Tunnel -> {
            val box = Size(w * .42f, h * .78f)
            val topLeft = Offset((w - box.width) / 2, (h - box.height) / 2)
            drawLine(color, left, right, stroke)
            drawRect(color, topLeft, box, style = Stroke(stroke))
            dots(true)
        }
        RouteGlyph.Proxy -> {
            drawLine(color, left, Offset(w / 2 - h * .32f, cy), stroke); drawLine(color, Offset(w / 2 + h * .32f, cy), right, stroke)
            drawCircle(color, h * .32f, Offset(w / 2, cy), style = Stroke(stroke)); dots(true)
        }
        RouteGlyph.Local -> { drawCircle(color, h * .34f, Offset(w / 2, cy), style = Stroke(stroke)); drawCircle(color, r, Offset(w / 2 - h * .34f, cy)) }
        RouteGlyph.Unknown -> {
            drawLine(color, left, right, stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx())))
            dots(true)
        }
        RouteGlyph.Pending -> { drawLine(color.copy(alpha = .5f), left, right, stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 2.dp.toPx()))); dots(false) }
        RouteGlyph.None -> drawCircle(color, r, Offset(w / 2, cy), style = Stroke(stroke))
    }
}
