package local.ringleader.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import local.ringleader.core.*

private const val ITEM_HEIGHT = 40
private const val PIN_ROW = 28
private const val PIN_MORE = 28
private const val PIN_HEAD = 18
private const val PIN_RULE = 7
private const val SESSION_ROW = 28
private const val SESSION_HEAD = 18

/** Rail order. Ctrl+1 opens the first name; Main maps the number keys to these names. */
internal val ringPages = listOf("Desk", "Fleet", "Storage", "Activity", "Scan", "Bluetooth", "VPN", "Tools", "Under the Hood")

/** Line icons drawn on a 20dp canvas, 1.5dp stroke, round caps. */
private fun DrawScope.railIcon(kind: String, c: Color) {
    val st = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)
    val w = size.width; val h = size.height
    when (kind) {
        "Desk" -> { // plug
            drawLine(c, Offset(w * .38f, 0f), Offset(w * .38f, h * .28f), st.width, StrokeCap.Round)
            drawLine(c, Offset(w * .62f, 0f), Offset(w * .62f, h * .28f), st.width, StrokeCap.Round)
            drawRoundRect(c, Offset(w * .22f, h * .28f), Size(w * .56f, h * .32f), androidx.compose.ui.geometry.CornerRadius(3f), style = st)
            drawLine(c, Offset(w * .5f, h * .6f), Offset(w * .5f, h * .78f), st.width, StrokeCap.Round)
            val tail = Path().apply { moveTo(w * .5f, h * .78f); quadraticTo(w * .5f, h, w * .78f, h * .96f) }
            drawPath(tail, c, style = st)
        }
        "Fleet" -> repeat(3) { n -> // stacked server slabs with status lights
            val top = n * h / 3 + 1f
            drawRoundRect(c, Offset(1f, top), Size(w - 2f, h / 3 - 3f), androidx.compose.ui.geometry.CornerRadius(2f), style = st)
            drawCircle(c, 1.2.dp.toPx(), Offset(w * .78f, top + (h / 3 - 3f) / 2))
        }
        "Storage" -> repeat(3) { n -> // stacked disk platters
            val top = h * (.1f + n * .28f)
            drawOval(c, Offset(w * .12f, top), Size(w * .76f, h * .22f), style = st)
            if (n == 2) { drawLine(c, Offset(w * .12f, top + h * .11f), Offset(w * .12f, h * .1f + h * .11f), st.width); drawLine(c, Offset(w * .88f, top + h * .11f), Offset(w * .88f, h * .1f + h * .11f), st.width) }
        }
        "Activity" -> { // pulse line
            val p = Path().apply { moveTo(0f, h * .55f); lineTo(w * .28f, h * .55f); lineTo(w * .4f, h * .15f); lineTo(w * .56f, h * .9f); lineTo(w * .68f, h * .55f); lineTo(w, h * .55f) }
            drawPath(p, c, style = st)
        }
        "Scan" -> { // radar
            drawCircle(c, w * .46f, center, style = st)
            drawCircle(c, w * .24f, center, style = Stroke(1.dp.toPx()))
            drawLine(c, center, Offset(w * .86f, h * .18f), st.width, StrokeCap.Round)
            drawCircle(c, 1.4.dp.toPx(), Offset(w * .3f, h * .64f))
        }
        "Bluetooth" -> { // bind rune: stem, right zigzag, two crossing strokes
            val x = w * .50f; val left = w * .28f; val right = w * .78f
            val y0 = h * .08f; val y1 = h * .29f; val y2 = h * .50f; val y3 = h * .71f; val y4 = h * .92f
            drawLine(c, Offset(x, y0), Offset(x, y4), st.width, StrokeCap.Round)
            drawLine(c, Offset(x, y0), Offset(right, y1), st.width, StrokeCap.Round)
            drawLine(c, Offset(right, y1), Offset(x, y2), st.width, StrokeCap.Round)
            drawLine(c, Offset(x, y2), Offset(right, y3), st.width, StrokeCap.Round)
            drawLine(c, Offset(right, y3), Offset(x, y4), st.width, StrokeCap.Round)
            drawLine(c, Offset(left, y1), Offset(right, y3), st.width, StrokeCap.Round)
            drawLine(c, Offset(left, y3), Offset(right, y1), st.width, StrokeCap.Round)
        }
        "VPN" -> { // shield with a keyhole
            val p = Path().apply { moveTo(w * .5f, h * .04f); lineTo(w * .88f, h * .18f); lineTo(w * .82f, h * .6f); lineTo(w * .5f, h * .96f); lineTo(w * .18f, h * .6f); lineTo(w * .12f, h * .18f); close() }
            drawPath(p, c, style = st)
            drawCircle(c, w * .09f, Offset(w * .5f, h * .42f), style = Stroke(1.2.dp.toPx()))
            drawLine(c, Offset(w * .5f, h * .51f), Offset(w * .5f, h * .68f), st.width, StrokeCap.Round)
        }
        "Under the Hood" -> { // microchip with a pulse trace
            val side = w * .6f
            val left = (w - side) / 2f
            val top = (h - side) / 2f
            drawRoundRect(c, Offset(left, top), Size(side, side), androidx.compose.ui.geometry.CornerRadius(2f), style = st)
            repeat(3) { i ->
                val y = top + side * (i + 1) / 4f
                val pin = w * .14f
                drawLine(c, Offset(left - pin, y), Offset(left, y), st.width, StrokeCap.Round)
                drawLine(c, Offset(left + side, y), Offset(left + side + pin, y), st.width, StrokeCap.Round)
            }
            val beat = Path().apply {
                moveTo(left + side * .12f, top + side * .55f)
                lineTo(left + side * .32f, top + side * .55f)
                lineTo(left + side * .42f, top + side * .28f)
                lineTo(left + side * .55f, top + side * .78f)
                lineTo(left + side * .66f, top + side * .55f)
                lineTo(left + side * .88f, top + side * .55f)
            }
            drawPath(beat, c, style = st)
        }
        "Tools" -> { // open-end wrench
            val jaw = Path().apply {
                moveTo(w * .58f, h * .08f); lineTo(w * .90f, h * .08f); lineTo(w * .90f, h * .36f); lineTo(w * .74f, h * .36f)
                lineTo(w * .74f, h * .22f); lineTo(w * .58f, h * .22f); close()
            }
            drawPath(jaw, c, style = st)
            drawLine(c, Offset(w * .70f, h * .36f), Offset(w * .30f, h * .76f), st.width, StrokeCap.Round)
            drawLine(c, Offset(w * .16f, h * .68f), Offset(w * .38f, h * .90f), st.width, StrokeCap.Round)
        }
        "Settings" -> { // gear
            drawCircle(c, w * .2f, center, style = st)
            repeat(8) { t ->
                val a = Math.toRadians(t * 45.0); val r1 = w * .32f; val r2 = w * .46f
                drawLine(c, center + Offset((r1 * Math.cos(a)).toFloat(), (r1 * Math.sin(a)).toFloat()), center + Offset((r2 * Math.cos(a)).toFloat(), (r2 * Math.sin(a)).toFloat()), st.width * 1.4f, StrokeCap.Round)
            }
        }
        "Collapse" -> { val p = Path().apply { moveTo(w * .62f, h * .22f); lineTo(w * .36f, h * .5f); lineTo(w * .62f, h * .78f) }; drawPath(p, c, style = st) }
        "Expand" -> { val p = Path().apply { moveTo(w * .38f, h * .22f); lineTo(w * .64f, h * .5f); lineTo(w * .38f, h * .78f) }; drawPath(p, c, style = st) }
    }
}

@Composable private fun RailRow(icon: String, label: String, expanded: Boolean, active: Boolean = false, hint: String? = null, badge: Int = 0, tooltip: String = label, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val reduce = LocalReduceMotion.current
    val bg by animateColorAsState(if (active) Tech.Raised else if (hovered || focused) Tech.Raised.copy(alpha = .6f) else Color.Transparent, tween(if (reduce) 0 else 120))
    val tint by animateColorAsState(if (active) Tech.Amber else if (hovered || focused) Tech.Ink else Tech.Muted, tween(if (reduce) 0 else 120))
    // The page you are on carries an amber edge; it grows in from the middle. Keyboard focus draws an amber outline.
    val edge by animateFloatAsState(if (active) 1f else if (hovered) .35f else 0f, tween(if (reduce) 0 else 160))
    val row = @Composable {
        Row(Modifier.fillMaxWidth().heightIn(min = ITEM_HEIGHT.dp).background(bg)
            .drawBehind {
                if (edge > 0f) { val h = size.height * .64f * edge; drawRect(if (active) Tech.Amber else Tech.Edge, Offset(0f, (size.height - h) / 2), androidx.compose.ui.geometry.Size(3.dp.toPx(), h)) }
                if (focused) drawRect(Tech.Amber, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }
            .hoverable(interaction).clickable(interactionSource = interaction, indication = null, role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick).padding(start = 20.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box {
                Canvas(Modifier.size(20.dp)) { railIcon(icon, tint) }
                if (badge > 0 && !expanded) Box(Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp).heightIn(min = 14.dp).background(Tech.Amber, Tech.Control).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                    Text("$badge", fontSize = 9.sp, color = Tech.OnAccent, fontWeight = FontWeight.Bold, fontFamily = Tech.Mono)
                }
            }
            AnimatedVisibility(expanded, enter = fadeIn(tween(if (reduce) 0 else 160, delayMillis = if (reduce) 0 else 120)), exit = fadeOut(tween(if (reduce) 0 else 60))) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, fontSize = 13.sp, color = if (active) Tech.Ink else tint, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (badge > 0) Box(Modifier.background(Tech.Amber, Tech.Control).padding(horizontal = 5.dp, vertical = 1.dp)) { Text("$badge", fontSize = 9.sp, color = Tech.OnAccent, fontWeight = FontWeight.Bold, fontFamily = Tech.Mono) }
                    hint?.let { Text(it, fontSize = 9.sp, color = Tech.Muted.copy(alpha = .7f), fontFamily = Tech.Mono) }
                }
            }
        }
    }
    if (expanded) row() else HelpTip(tooltip) { row() }
}

@Composable internal fun NavigationRail(
    state: WorkspaceState, page: String, platform: String, workspace: Workspace, toggle: Int = 0, initialExpanded: Boolean? = null,
    onSettings: () -> Unit = {}, onAbout: () -> Unit = {}, pins: PinStore? = null, connectionKey: String? = null, onPin: (Pin) -> Unit = {},
    updateAvailable: Boolean = false,
    sessions: List<OpenSession> = emptyList(), activeSession: Long = 0, profiles: List<ConnectionProfile> = emptyList(), onSession: (Long) -> Unit = {},
    onPage: (String) -> Unit,
) {
    val prefs = remember { MotionPreferences() }
    var expanded by remember { mutableStateOf(initialExpanded ?: prefs.option("expanded", false)) }
    LaunchedEffect(toggle) { if (toggle > 0) { expanded = !expanded; prefs.put("expanded", expanded) } }
    LaunchedEffect(state.connectionEpoch) { if (state.connected || state.demo) workspace.setLive(prefs.option("live", true), prefs.number("liveInterval", LIVE_INTERVAL_DEFAULT)) }
    val reduce = LocalReduceMotion.current
    val width by animateDpAsState(if (expanded) 208.dp else 60.dp, tween(if (reduce) 0 else 200, easing = FastOutSlowInEasing))
    val index = ringPages.indexOf(page)
    val offset by animateDpAsState(((index.coerceAtLeast(0)) * ITEM_HEIGHT + 8).dp, if (reduce) snap() else spring(dampingRatio = .75f, stiffness = Spring.StiffnessMediumLow))
    val running = state.tasks.orEmpty().count { it.outcome == TaskOutcome.Running }
    val backoff = state.liveBackoffSeconds
    val beacon = when { !state.connected && !state.demo -> Tech.Muted; backoff != null || state.stale -> Tech.Amber; state.demo -> Tech.Amber; state.live -> Tech.Green; else -> Tech.Muted }
    val pulseOn = !reduce && state.live && backoff == null && (state.connected || state.demo) && LocalMotionPhase.current.active.value
    val entries = if (pins != null && connectionKey != null) pins.list(connectionKey) else emptyList()
    var moreOpen by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.width(width).fillMaxHeight().background(Tech.Panel).drawBehindRight()) {
        val head = if (expanded && entries.isNotEmpty()) PIN_HEAD else 0
        val rule = if (entries.isNotEmpty()) PIN_RULE else 0
        // Open sessions are listed above Settings once there is more than one; pins get the room that is left.
        val sessionBlock = if (sessions.size > 1) 1 + (if (expanded) SESSION_HEAD else 4) + sessions.size * SESSION_ROW + 4 else 0
        val room = (maxHeight.value - (8 + ringPages.size * ITEM_HEIGHT) - (1 + ITEM_HEIGHT + 48 + ITEM_HEIGHT + 24) - head - rule - 4 - sessionBlock).toInt()
        val shown = visiblePinCount(entries.size, room, PIN_ROW, PIN_MORE)
        val hidden = (entries.size - shown).coerceAtLeast(0)
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(8.dp))
            Box {
                Column { ringPages.forEachIndexed { i, item ->
                    val label = if (item == "Tools") "Network tools" else item
                    RailRow(item, label, expanded, active = page == item, hint = "Ctrl ${i + 1}", badge = if (item == "Activity") running else 0,
                        tooltip = "$label  ·  Ctrl ${i + 1}" + if (item == "Activity" && running > 0) "  ·  $running running" else "") { onPage(item) }
                } }
                if (index >= 0) Box(Modifier.offset(y = offset).size(3.dp, 24.dp).background(Tech.Amber))
            }
            if (entries.isNotEmpty() && pins != null && connectionKey != null) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(Tech.Edge))
                if (expanded) Text("PINNED", Modifier.padding(start = 20.dp, top = 2.dp).height(16.dp), fontSize = 9.sp, letterSpacing = 1.2.sp, color = Tech.Muted, fontFamily = Tech.Mono)
                entries.take(shown).forEachIndexed { index, pin ->
                    PinRailRow(state, pin, index, entries.size, expanded, pins, connectionKey, onPin)
                }
                if (hidden > 0) PinMoreRow(hidden, expanded) { moreOpen = true }
            }
            Spacer(Modifier.weight(1f))
            if (sessions.size > 1) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Tech.Edge))
                if (expanded) Text("SESSIONS", Modifier.padding(start = 20.dp, top = 2.dp).height(16.dp), fontSize = 9.sp, letterSpacing = 1.2.sp, color = Tech.Muted, fontFamily = Tech.Mono)
                else Spacer(Modifier.height(4.dp))
                sessions.forEachIndexed { index, open ->
                    key(open.id) { SessionRailRow(open, index + 1, open.id == activeSession, expanded, profiles.find { it.id == open.profileId }) { onSession(open.id) } }
                }
                Spacer(Modifier.height(4.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tech.Edge))
            Box {
            RailRow("Settings", "Settings", expanded, tooltip = "Settings · Ctrl ,", onClick = onSettings)
                if (updateAvailable) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(5.dp).background(Tech.Amber, androidx.compose.foundation.shape.CircleShape))
            }
            BeaconStatus(state, expanded, beacon, pulseOn)
            RailRow(if (expanded) "Collapse" else "Expand", "Collapse", expanded, hint = "Ctrl B", tooltip = "Expand  ·  Ctrl B") { expanded = !expanded; prefs.put("expanded", expanded) }
            // The version stays visible collapsed too; the full line (and About) is one click away.
            Text((if (updateAvailable) "\u2022 " else "") + if (expanded) "${AppVersion.value} · $platform" else AppVersion.value, Modifier.padding(start = if (expanded) 20.dp else 0.dp, bottom = 8.dp).then(if (expanded) Modifier else Modifier.fillMaxWidth())
                .clickable(onClick = onAbout), fontSize = 9.sp, color = if (updateAvailable) Tech.Amber else Tech.Muted.copy(alpha = .7f), fontFamily = Tech.Mono, maxLines = 1,
                textAlign = if (expanded) null else androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (moreOpen && pins != null && connectionKey != null) PinMoreDialog(state, pins, connectionKey, entries.drop(shown), { moreOpen = false }, onPin)
    }
}

/** Header pin toggle. Reads the store so the label follows the saved guest pin. */
@Composable internal fun PinGuestButton(store: PinStore, connectionKey: String, guest: Guest) {
    val on = store.hasGuest(connectionKey, guest)
    MiniAction(if (on) "Pinned" else "Pin", primary = on) { store.toggleGuest(connectionKey, guest) }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable private fun PinRailRow(state: WorkspaceState, pin: Pin, index: Int, total: Int, expanded: Boolean, store: PinStore, connectionKey: String, onPin: (Pin) -> Unit) {
    val guest = state.overview?.guests?.firstOrNull { pin.matches(it) }
    val liveness = pinLiveness(guest, state.tasks, state.submissions)
    val missing = liveness == PinLiveness.Missing
    val label = pin.railLabel(guest)
    val status = pinLivenessLabel(liveness, guest)
    val dot = when (liveness) { PinLiveness.Running -> Tech.Green; PinLiveness.Busy -> Tech.Amber; else -> Tech.Muted }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var menu by remember { mutableStateOf(false) }
    val reduce = LocalReduceMotion.current
    val bg by animateColorAsState(if (hovered) Tech.Raised.copy(alpha = .6f) else Color.Transparent, tween(if (reduce) 0 else 120))
    val row = @Composable {
        Row(Modifier.fillMaxWidth().height(PIN_ROW.dp).background(bg).alpha(if (missing) .45f else 1f)
            .hoverable(interaction)
            .onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Initial) { if (it.button == PointerButton.Secondary) menu = true }
            .clickable(interactionSource = interaction, indication = null, enabled = !missing || pin.target == PIN_SERVICE) {
                if (!missing || pin.target == PIN_SERVICE) onPin(pin)
            }
            .padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(16.dp)) {
                if (pin.target == PIN_GUEST) Box(Modifier.fillMaxSize().background(Tech.Raised, Tech.Control).border(1.dp, Tech.Edge, Tech.Control), contentAlignment = Alignment.Center) {
                    Text(if (pin.guestKind == "VM") "VM" else "CT", fontSize = 6.sp, fontFamily = Tech.Mono, color = Tech.Muted, maxLines = 1)
                } else Canvas(Modifier.fillMaxSize()) { pinGlyph(if (pin.target == PIN_SERVICE) "service" else if (pin.tab == "Console") "console" else "tab", if (missing) Tech.Muted else Tech.Ink) }
                if (!expanded) Canvas(Modifier.align(Alignment.BottomEnd).size(5.dp)) { drawCircle(dot) }
            }
            if (expanded) {
                Text(label, Modifier.weight(1f).identity(), fontSize = 12.sp, color = if (missing) Tech.Muted else Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Canvas(Modifier.size(6.dp)) { drawCircle(dot) }
                // The slot is always there, so showing the unpin mark on hover does not shift the label or the status dot.
                Box(Modifier.size(14.dp).then(if (hovered) Modifier.clickable { store.unpin(connectionKey, pin.id) } else Modifier), contentAlignment = Alignment.Center) {
                    if (hovered) Text("\u00d7", fontSize = 12.sp, lineHeight = 12.sp, color = Tech.Muted)
                }
            }
        }
    }
    Box {
        val tip = when {
            missing -> "$label\nNot in this connection"
            !expanded -> "$label\n$status"
            else -> null
        }
        if (tip == null) row() else HelpTip(tip, identity = true) { row() }
        DropdownMenu(menu, { menu = false }, containerColor = Tech.Raised) {
            DropdownMenuItem(text = { Text("Move up", fontSize = 13.sp, color = Tech.Ink) }, enabled = index > 0, onClick = { menu = false; store.move(connectionKey, pin.id, -1) })
            DropdownMenuItem(text = { Text("Move down", fontSize = 13.sp, color = Tech.Ink) }, enabled = index < total - 1, onClick = { menu = false; store.move(connectionKey, pin.id, 1) })
            DropdownMenuItem(text = { Text("Unpin", fontSize = 13.sp, color = Tech.Ink) }, onClick = { menu = false; store.unpin(connectionKey, pin.id) })
        }
    }
}

/** One open connection in the rail: its number, a state dot and its name. The one in view carries the amber edge. */
@Composable private fun SessionRailRow(session: OpenSession, number: Int, active: Boolean, expanded: Boolean, profile: ConnectionProfile?, onClick: () -> Unit) {
    val state by session.workspace.state.collectAsState()
    val label = sessionLabel(state, profile)
    val open = sessionOpen(state)
    val status = when { !open -> if (state.error != null) "Not connected" else "Not connected yet"; state.liveBackoffSeconds != null -> "Retrying"; state.stale -> "Stale"; state.demo -> "Sample data"; state.live -> "Live"; else -> "Paused" }
    val dot = when { !open -> Tech.Muted; state.liveBackoffSeconds != null || state.stale || state.demo -> Tech.Amber; state.live -> Tech.Green; else -> Tech.Muted }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val reduce = LocalReduceMotion.current
    val bg by animateColorAsState(if (active) Tech.Raised else if (hovered) Tech.Raised.copy(alpha = .6f) else Color.Transparent, tween(if (reduce) 0 else 120))
    val row = @Composable {
        Row(Modifier.fillMaxWidth().height(SESSION_ROW.dp).background(bg)
            .drawBehind { if (active) drawRect(Tech.Amber, Offset(0f, size.height * .18f), Size(3.dp.toPx(), size.height * .64f)) }
            .hoverable(interaction).clickable(interactionSource = interaction, indication = null, role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick)
            .padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(16.dp)) {
                Box(Modifier.fillMaxSize().background(Tech.Raised, Tech.Control).border(1.dp, if (active) Tech.Amber else Tech.Edge, Tech.Control), contentAlignment = Alignment.Center) {
                    Text("$number", fontSize = 9.sp, lineHeight = 9.sp, fontFamily = Tech.Mono, fontWeight = FontWeight.Bold, color = if (active) Tech.Amber else Tech.Muted, maxLines = 1)
                }
                if (!expanded) Canvas(Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp).size(5.dp)) { drawCircle(dot) }
            }
            if (expanded) {
                Text(label, Modifier.weight(1f).identity(), fontSize = 12.sp, color = if (active) Tech.Ink else Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Canvas(Modifier.size(6.dp)) { drawCircle(dot) }
            }
        }
    }
    HelpTip(if (active) "$label\n$status · in view" else "$label\n$status · click to show", identity = true) { row() }
}

@Composable private fun PinMoreRow(hidden: Int, expanded: Boolean, onClick: () -> Unit) {
    val label = "+$hidden more"
    val row = @Composable {
        Row(Modifier.fillMaxWidth().height(PIN_MORE.dp).clickable(onClick = onClick).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (expanded) label else "+$hidden", fontSize = 11.sp, color = Tech.Amber, fontFamily = Tech.Mono, maxLines = 1)
        }
    }
    if (expanded) row() else HelpTip(label) { row() }
}

@Composable private fun PinMoreDialog(state: WorkspaceState, store: PinStore, connectionKey: String, hidden: List<Pin>, onDismiss: () -> Unit, onPin: (Pin) -> Unit) {
    TechDialog("Pinned", onDismiss, width = 420.dp, subtitle = "The rail is showing the first pins.", actions = {
        TechAction("Close", onClick = onDismiss)
    }) {
        hidden.forEach { pin ->
            val guest = state.overview?.guests?.firstOrNull { pin.matches(it) }
            val liveness = pinLiveness(guest, state.tasks, state.submissions)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(pin.railLabel(guest), Modifier.weight(1f).identity(), fontSize = 12.sp, color = if (liveness == PinLiveness.Missing) Tech.Muted else Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pinLivenessLabel(liveness, guest), fontSize = 10.sp, color = Tech.Muted, fontFamily = Tech.Mono, maxLines = 1)
                MiniAction("Up") { store.move(connectionKey, pin.id, -1) }
                MiniAction("Down") { store.move(connectionKey, pin.id, 1) }
                MiniAction("Open", enabled = liveness != PinLiveness.Missing || pin.target == PIN_SERVICE) {
                    if (liveness != PinLiveness.Missing || pin.target == PIN_SERVICE) { onPin(pin); onDismiss() }
                }
                MiniAction("Unpin") { store.unpin(connectionKey, pin.id) }
            }
        }
    }
}

private fun DrawScope.pinGlyph(kind: String, c: Color) {
    val st = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)
    val w = size.width; val h = size.height
    when (kind) {
        "console" -> {
            drawRoundRect(c, Offset(w * .08f, h * .18f), Size(w * .84f, h * .64f), androidx.compose.ui.geometry.CornerRadius(2f), style = st)
            drawLine(c, Offset(w * .22f, h * .58f), Offset(w * .38f, h * .42f), st.width, StrokeCap.Round)
            drawLine(c, Offset(w * .38f, h * .42f), Offset(w * .22f, h * .30f), st.width, StrokeCap.Round)
            drawLine(c, Offset(w * .46f, h * .58f), Offset(w * .72f, h * .58f), st.width, StrokeCap.Round)
        }
        "service" -> {
            drawCircle(c, w * .28f, center, style = st)
            drawLine(c, Offset(w * .22f, h * .5f), Offset(w * .78f, h * .5f), st.width, StrokeCap.Round)
            drawLine(c, Offset(center.x, h * .22f), Offset(center.x, h * .78f), st.width, StrokeCap.Round)
        }
        else -> {
            val tab = Path().apply {
                moveTo(w * .12f, h * .32f); lineTo(w * .40f, h * .32f); lineTo(w * .50f, h * .16f); lineTo(w * .88f, h * .16f)
                lineTo(w * .88f, h * .84f); lineTo(w * .12f, h * .84f); close()
            }
            drawPath(tab, c, style = st)
        }
    }
}

/** Beacon dot and the once-per-second age line. Isolated so a clock tick does not recompose the rail. */
@Composable private fun BeaconStatus(state: WorkspaceState, expanded: Boolean, beacon: Color, pulseOn: Boolean) {
    val now = LocalNow.current ?: java.time.Instant.now()
    val ago = syncedAgo(state.updatedAt, now)
    val backoff = state.liveBackoffSeconds
    val phase = LocalMotionPhase.current
    val beaconText = when { !state.connected && !state.demo -> "Offline"; backoff != null -> "Retrying in ${backoff}s"; state.live -> "Live ${liveIntervalLabel(state.liveIntervalSeconds)} · $ago"; else -> "Paused · $ago" }
    val beaconRow = @Composable {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 22.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Canvas(Modifier.size(16.dp)) {
                val alpha = if (pulseOn) phase.pulse.floatValue else 0.25f
                drawCircle(beacon, size.minDimension / 2, alpha = alpha); drawCircle(beacon, 3.dp.toPx())
            }
            if (expanded) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (state.demo) "Demonstration" else state.address.removePrefix("https://").ifBlank { "Not connected" }, Modifier.identity(), fontSize = 11.sp, fontFamily = Tech.Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(beaconText, fontSize = 10.sp, color = if (backoff != null) Tech.Amber else Tech.Muted, fontFamily = Tech.Mono, maxLines = 1)
            }
        }
    }
    if (expanded) beaconRow() else HelpTip("${state.address.ifBlank { "Not connected" }}\n$beaconText") { beaconRow() }
}

/** 1dp edge on the rail's right side. */
private fun Modifier.drawBehindRight() = this.drawBehind { drawRect(Tech.Edge, Offset(size.width - 1f, 0f), Size(1f, size.height)) }
