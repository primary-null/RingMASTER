package local.ringleader.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import kotlinx.coroutines.delay
import local.ringleader.core.ResourceId

/*
 * The in-app notification center, its toasts and the notification preferences. Presentation only: the values come from
 * NotificationPresentation.kt, every change goes out through a callback. The layout follows the request for a calm center
 * with explicit actions and an unread badge; it is not a copy of any other app's design.
 */

internal const val NOTICE_CENTER_TAG = "notice-center"
internal const val NOTICE_TOASTS_TAG = "notice-toasts"

/** Severity colour: the rail edge of a row and its label. */
internal fun severityColor(severity: NoticeSeverity): Color = when (severity) {
    NoticeSeverity.Critical -> Tech.Red
    NoticeSeverity.Warning -> Tech.Amber
    NoticeSeverity.Success -> Tech.Green
    NoticeSeverity.Info -> Tech.Muted
}

/** Center tabs. Preferences only when the parent provides them. */
internal enum class NoticeTab(val label: String) { Inbox("Inbox"), Snoozed("Snoozed"), Preferences("Preferences") }

/**
 * The center's content, without the overlay around it (scene tests draw it directly). [canOpen] says whether the window
 * can route to a notice's target now; [onOpen] does the routing.
 */
@Composable internal fun NotificationCenterPanel(
    view: NotificationCenterView, actions: NotificationActions, canOpen: (NoticeView) -> Boolean, onOpen: (NoticeView) -> Unit,
    onClose: (() -> Unit)?, modifier: Modifier = Modifier, prefs: NotificationPrefsView? = null,
    onPrefChange: ((NotificationPrefChange) -> Unit)? = null, initialTab: NoticeTab = NoticeTab.Inbox,
    initialFilter: NoticeFilter = NoticeFilter(),
) {
    var tab by remember { mutableStateOf(initialTab) }
    var filter by remember { mutableStateOf(initialFilter) }
    val showcase = Appearance.showcase
    val unread = unreadNoticeCount(view.items, view.now)
    val snoozed = view.items.count { noticeSnoozed(it, view.now) }
    val tabs = buildList { add(NoticeTab.Inbox); if (snoozed > 0) add(NoticeTab.Snoozed); if (prefs != null && onPrefChange != null) add(NoticeTab.Preferences) }
    val current = if (tab in tabs) tab else NoticeTab.Inbox
    Column(modifier.testTag(NOTICE_CENTER_TAG), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).semantics { heading() }) { SectionLabel("Notifications", suffix = if (unread > 0) "$unread unread" else null) }
            if (onClose != null) HelpTip("Close · Esc") { CloseAction("Close notifications", onClose) }
        }
        if (view.privacy) Text("PRIVACY MODE · NAMES AND DETAILS HIDDEN", fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = .8.sp, color = Tech.Amber)
        view.problem?.let { Text(it, fontSize = 12.sp, lineHeight = 16.sp, color = Tech.Amber) }
        DesktopPauseLine(view, actions)
        TechTabs(tabs.map { if (it == NoticeTab.Snoozed) "${it.label} $snoozed" else it.label }, if (current == NoticeTab.Snoozed) "${current.label} $snoozed" else current.label,
            { label -> tab = tabs.first { t -> label.startsWith(t.label) } })
        when (current) {
            NoticeTab.Preferences -> if (prefs != null && onPrefChange != null) NotificationPreferencesPanel(prefs, onPrefChange, Modifier.weight(1f, fill = false))
            else -> {
                val inSnoozed = current == NoticeTab.Snoozed
                val shown = filterNotices(view.items, if (inSnoozed) NoticeFilter(snoozedOnly = true) else filter, view.now)
                if (!inSnoozed) NoticeFilterBar(view, filter, { filter = it }, privacy = view.privacy)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${shown.size} shown", fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, modifier = Modifier.weight(1f))
                    actions.onMarkAllRead?.let { MiniAction("Mark all read", enabled = unread > 0, onClick = it) }
                    actions.onClearRead?.let { MiniAction("Clear read", enabled = view.items.any { n -> n.read }, onClick = it) }
                    actions.onClearAll?.let { clearAll ->
                        // Clearing removes unread notices too, so it asks once, in place.
                        var confirm by remember { mutableStateOf(false) }
                        if (!confirm) MiniAction("Clear all", enabled = view.items.isNotEmpty()) { confirm = true }
                        else {
                            Text("Clear all?", fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Amber)
                            MiniAction("Clear") { confirm = false; clearAll() }
                            MiniAction("Keep") { confirm = false }
                        }
                    }
                }
                if (shown.isEmpty()) NoticeEmpty(view.items.isEmpty(), inSnoozed)
                else {
                    val list = rememberLazyListState()
                    Box(Modifier.weight(1f, fill = false)) {
                        LazyColumn(Modifier.fillMaxWidth().padding(end = 10.dp), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(shown, key = { it.id }) { notice ->
                                NoticeRow(notice, view, actions, canOpen(notice), { onOpen(notice) }, showcase)
                            }
                        }
                        VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight(), style = visibleScrollbarStyle())
                    }
                }
            }
        }
    }
}

@Composable private fun NoticeFilterBar(view: NotificationCenterView, filter: NoticeFilter, onFilter: (NoticeFilter) -> Unit, privacy: Boolean) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        MiniAction("All", primary = filter.severities.isEmpty()) { onFilter(filter.copy(severities = emptySet())) }
        NoticeSeverity.entries.reversed().forEach { s ->
            val on = s in filter.severities
            val count = view.items.count { it.severity == s }
            MiniAction("${s.label} $count", primary = on, enabled = count > 0 || on) {
                onFilter(filter.copy(severities = if (on) filter.severities - s else filter.severities + s))
            }
        }
        val sources = noticeSources(view.items)
        if (sources.size > 1) {
            var open by remember { mutableStateOf(false) }
            val current = sources.firstOrNull { it.first == filter.sourceId }
            Box {
                // In privacy mode the sources are numbered instead of named.
                val label = when { current == null -> "Every server"; privacy -> "Server ${sources.indexOf(current) + 1}"; else -> current.second }
                Box(if (current != null && !privacy) Modifier.identity() else Modifier) { MiniAction("$label ▾", primary = current != null) { open = true } }
                DropdownMenu(open, { open = false }, containerColor = Tech.Raised) {
                    DropdownMenuItem(text = { Text("Every server", fontSize = 13.sp, color = Tech.Ink) }, onClick = { open = false; onFilter(filter.copy(sourceId = null)) })
                    sources.forEachIndexed { i, (id, name) ->
                        DropdownMenuItem(text = { Text(if (privacy) "Server ${i + 1}" else name, if (privacy) Modifier else Modifier.identity(), fontSize = 13.sp, color = Tech.Ink) },
                            onClick = { open = false; onFilter(filter.copy(sourceId = id)) })
                    }
                }
            }
        }
        TechCheck(filter.unreadOnly, { onFilter(filter.copy(unreadOnly = it)) }, "Unread only")
    }
}

@Composable private fun NoticeEmpty(nothingAtAll: Boolean, snoozed: Boolean) {
    TechPanel(Modifier.fillMaxWidth()) {
        Text(when { snoozed -> "Nothing is snoozed"; nothingAtAll -> "No notifications"; else -> "Nothing matches these filters" }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(when { snoozed -> "Snoozed notifications come back here and in the inbox when their time is up."
            nothingAtAll -> "Watched guests and nodes, failed tasks and schedule results are listed here as they happen."
            else -> "Change the severity or server filter, or turn off Unread only." }, fontSize = 12.sp, color = Tech.Muted, lineHeight = 17.sp)
    }
}

/** A small mono tag with a coloured outline, for the incident state and the operating-system note. */
@Composable internal fun NoticeTag(label: String, tint: Color) {
    Box(Modifier.border(1.dp, tint.copy(alpha = .8f), Tech.Control).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Text(label.uppercase(), fontFamily = Tech.Mono, fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = .6.sp, color = tint, maxLines = 1)
    }
}

@Composable private fun NoticeRow(notice: NoticeView, view: NotificationCenterView, actions: NotificationActions, openable: Boolean, onOpen: () -> Unit, showcase: Boolean) {
    val text = noticeText(notice, view.privacy, showcase)
    val tint = severityColor(notice.severity)
    TechPanel(Modifier.fillMaxWidth(), rail = tint, background = if (notice.read) Tech.Panel else Tech.Raised.copy(alpha = .55f), padding = 10.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!notice.read) Box(Modifier.size(6.dp).background(Tech.Amber).semantics { contentDescription = "Unread" })
            Text(notice.severity.label.uppercase(), fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = .8.sp, fontWeight = FontWeight.Bold, color = tint, maxLines = 1)
            Text(notice.category, fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (notice.repeats > 1) Text("×${notice.repeats}", fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted)
            Text(noticeAge(notice.at, view.now), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        Text(text.headline, fontSize = 13.sp, fontWeight = if (notice.read) FontWeight.Medium else FontWeight.SemiBold, lineHeight = 17.sp, color = Tech.Ink,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        text.detail?.let { Text(it, Modifier.identity(), fontSize = 12.sp, lineHeight = 16.sp, color = Tech.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        val where = listOfNotNull(text.target, text.source)
        if (where.isNotEmpty()) Text(where.joinToString("  ·  "), Modifier.identity(), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val incident = incidentLabel(notice.incident)
        val snoozedUntil = notice.snoozedUntil?.takeIf { noticeSnoozed(notice, view.now) }
        if (incident != null || notice.osDismissed || snoozedUntil != null) {
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                incident?.let { NoticeTag(it, if (notice.incident is NoticeIncident.Ongoing) tint else Tech.Green) }
                // The desktop toast was closed: that is not "read" and not "resolved", so it is shown on its own.
                if (notice.osDismissed) NoticeTag("Closed on desktop", Tech.Muted)
                snoozedUntil?.let { NoticeTag("Desktop alerts snoozed · ${compactDuration(java.time.Duration.between(view.now, it))}", Tech.Muted) }
            }
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            if (notice.target != null) {
                if (openable) MiniAction("Open ${notice.target.kind.label.lowercase()}", primary = !notice.read, onClick = onOpen)
                else Text(if (view.privacy) "Cannot be opened here" else "Not in the connection in view", fontSize = 10.sp, fontFamily = Tech.Mono, color = Tech.Muted)
            }
            if (notice.read) actions.onMarkUnread?.let { MiniAction("Mark unread") { it(notice.id) } }
            else actions.onMarkRead?.let { MiniAction("Mark read") { it(setOf(notice.id)) } }
            if (snoozedUntil != null) actions.onUnsnooze?.let { MiniAction("Unsnooze") { it(notice.id) } }
            else actions.onSnooze?.let { snooze ->
                var open by remember { mutableStateOf(false) }
                Box {
                    MiniAction("Snooze ▾") { open = true }
                    DropdownMenu(open, { open = false }, containerColor = Tech.Raised) {
                        actions.snoozeChoices.forEach { choice ->
                            DropdownMenuItem(text = { Text("Desktop alerts for ${choice.label}", fontSize = 13.sp, color = Tech.Ink) }, onClick = { open = false; snooze(notice.id, choice.duration) })
                        }
                    }
                }
            }
            actions.onDismiss?.let { MiniAction("Dismiss") { it(notice.id) } }
        }
    }
}

/** "Desktop notifications paused" with its end, and the pause and resume controls when the engine offers them. */
@Composable private fun DesktopPauseLine(view: NotificationCenterView, actions: NotificationActions) {
    val until = view.snoozeAllUntil?.takeIf { it.isAfter(view.now) }
    val pause = actions.onSnoozeAll
    if (until == null && pause == null) return
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        if (until != null) Text("DESKTOP NOTIFICATIONS PAUSED · ${compactDuration(java.time.Duration.between(view.now, until)).uppercase()} LEFT", fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = .8.sp, color = Tech.Amber)
        if (pause != null) {
            if (until != null) MiniAction("Resume") { pause(null) }
            else {
                var open by remember { mutableStateOf(false) }
                Box {
                    MiniAction("Pause desktop notifications \u25be") { open = true }
                    DropdownMenu(open, { open = false }, containerColor = Tech.Raised) {
                        actions.snoozeChoices.forEach { choice ->
                            DropdownMenuItem(text = { Text("For ${choice.label}", fontSize = 13.sp, color = Tech.Ink) }, onClick = { open = false; pause(choice.duration) })
                        }
                    }
                }
            }
        }
    }
}

/** A switch row for the preferences: label, a line of explanation, the switch at the end. */
@Composable private fun PrefRow(label: String, description: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row is the switch (label and track), so a screen reader hears one control with its name and state.
    Row(Modifier.fillMaxWidth().alpha(if (enabled) 1f else .5f)
        .toggleable(checked, enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 13.sp, color = Tech.Ink)
            description?.let { Text(it, fontSize = 11.sp, lineHeight = 15.sp, color = Tech.Muted) }
        }
        TechSwitch(checked, null, enabled = enabled)
    }
}

/** Master switch, toasts, desktop (native) delivery, privacy and the categories. Every change goes to [onChange]. */
@Composable internal fun NotificationPreferencesPanel(prefs: NotificationPrefsView, onChange: (NotificationPrefChange) -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(Modifier.fillMaxWidth().padding(end = 10.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrefRow("Notifications", "Off: nothing is collected for the center, shown or sent.", prefs.enabled, true) { onChange(NotificationPrefChange.Enabled(it)) }
            prefs.inAppToasts?.let { on -> PrefRow("In-app toasts", "A short card in the window corner for new notifications.", on, prefs.enabled) { onChange(NotificationPrefChange.InAppToasts(it)) } }
            PrefRow("Desktop notifications", prefs.native.unavailableReason?.takeIf { !prefs.native.available } ?: "Notifications from the operating system, also while the window is hidden.",
                prefs.native.enabled, prefs.enabled) { onChange(NotificationPrefChange.Native(it)) }
            if (prefs.canTestNative) MiniAction("Test desktop notification", enabled = prefs.enabled && prefs.native.enabled) { onChange(NotificationPrefChange.TestNative) }
            prefs.keepWatchingInBackground?.let { on ->
                PrefRow("Keep watching connected sessions while minimized or in the tray",
                    "Off: watching pauses while the window is minimized or hidden. On: connections that are already open keep reading. It never signs in and never uses saved secrets.",
                    on, prefs.enabled) { onChange(NotificationPrefChange.KeepWatchingInBackground(it)) }
            }
            PrefRow("Privacy mode", "Generic text only, here and on the desktop: no guest, node or server names. Showcase also hides them.", prefs.privacy, true) { onChange(NotificationPrefChange.Privacy(it)) }
            if (prefs.categories.isNotEmpty()) {
                SectionLabel("Categories")
                prefs.categories.forEach { c -> PrefRow(c.label, c.description, c.enabled, prefs.enabled) { onChange(NotificationPrefChange.Category(c.id, it)) } }
            }
        }
        if (scroll.maxValue > 0) VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight(), style = visibleScrollbarStyle())
    }
}

/**
 * The center as an in-window overlay at the right edge. It is a modal (page shortcuts wait and a console does not get the
 * keys while it is open); Escape, the close button and a click beside the panel close it.
 */
@Composable internal fun NotificationCenterOverlay(alerts: AlertsUi, canOpen: (NoticeView) -> Boolean, onOpen: (NoticeView) -> Unit, initialTab: NoticeTab = NoticeTab.Inbox, onClose: () -> Unit) {
    val view = alerts.notices ?: return
    TechModal(onClose, Dismiss.Light) {
        BoxWithConstraints(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)) {
            val width = minOf(maxWidth - 32.dp, 460.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f, 1.3f))
            Surface(Modifier.align(Alignment.CenterEnd).padding(top = 40.dp, bottom = 12.dp, end = 12.dp).width(width).fillMaxHeight()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                color = ringColors.surface, shape = Tech.Plate, border = BorderStroke(1.dp, Tech.Edge), shadowElevation = 12.dp) {
                NotificationCenterPanel(view, alerts.noticeActions, canOpen, onOpen, onClose, Modifier.padding(16.dp), alerts.prefs, alerts.onPrefChange, initialTab)
            }
        }
    }
}

// ---- Toasts ----

/**
 * New notifications as a short stack of cards in the bottom-right corner. Bounded: [TOAST_VISIBLE_MAX] cards, then one
 * "+n more" line that opens the center. Cards never take the keyboard; a pointer over one, or keyboard focus in it,
 * holds its time. Closing a card does not read the notice. With [suppressed] (center open, console full screen, toasts
 * off) nothing new is shown and the stack empties. [privacy] (privacy mode or Showcase) shows the generic headline only.
 */
@Composable internal fun NotificationToasts(
    view: NotificationCenterView, privacy: Boolean, suppressed: Boolean, canOpen: (NoticeView) -> Boolean, onOpen: (NoticeView) -> Unit,
    onOpenCenter: () -> Unit, modifier: Modifier = Modifier, clock: () -> Instant = Instant::now, queue: ToastQueue = remember { ToastQueue() },
) {
    var version by remember { mutableIntStateOf(0) }
    val items = view.items
    // Learn the inbox on every change: new unread notices queue, read or snoozed ones leave.
    LaunchedEffect(items, suppressed) {
        queue.offer(items, clock(), enabled = !suppressed)
        if (suppressed) queue.closeAll()
        version++
    }
    LaunchedEffect(items, version) {
        val next = queue.nextExpiry(items) ?: return@LaunchedEffect
        delay(java.time.Duration.between(clock(), next).toMillis().coerceAtLeast(16))
        queue.expire(items, clock())
        version++
    }
    // The queue is plain bookkeeping, not snapshot state: reading [version] here is what re-runs this when it changes.
    if (queue.showing == 0 && version >= 0) return
    // As many cards as the height allows (a short window or large text shows fewer), at most TOAST_VISIBLE_MAX.
    BoxWithConstraints(modifier.testTag(NOTICE_TOASTS_TAG).widthIn(max = 360.dp)) {
    val perCard = 150.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f, 2f)
    val fit = if (maxHeight == Dp.Infinity) TOAST_VISIBLE_MAX else (maxHeight / perCard).toInt().coerceIn(1, TOAST_VISIBLE_MAX)
    val shown = remember(items, version, fit) { queue.visible(items, fit) }
    val more = remember(items, version, fit) { queue.overflow(fit) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
        if (more > 0) Text("+$more more · open notifications", Modifier.clickable(onClick = onOpenCenter).padding(4.dp), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Amber)
        shown.forEach { notice ->
            key(notice.id) {
                NoticeToast(notice, privacy, canOpen(notice), { queue.close(notice.id); version++; onOpen(notice) }, { queue.close(notice.id); version++ },
                    { on -> queue.pause(notice.id, on); version++ })
            }
        }
    }
    }
}

/** A square close control with a drawn cross (a mono "×" is too small at 11 sp); [label] is what screen readers hear. */
@Composable internal fun CloseAction(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val ink = if (hovered || focused) Tech.Amber else Tech.Ink
    Box(Modifier.size(28.dp).focusRing(focused, Tech.Control, gap = 2.dp).border(1.dp, if (hovered || focused) Tech.Amber else Tech.Edge, Tech.Control)
        .hoverable(interaction).clickable(interactionSource = interaction, indication = null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(9.dp)) { drawWindowGlyph(WindowGlyph.Close, ink) }
    }
}

@Composable private fun NoticeToast(notice: NoticeView, privacy: Boolean, openable: Boolean, onOpen: () -> Unit, onClose: () -> Unit, onHold: (Boolean) -> Unit) {
    val motion = LocalMotion.current
    val enter = remember { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (enter.value < 1f) enter.animateTo(1f, motion.enter(MotionDur.Standard)) }
    val rise = motion.travel(8f)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focusInside by remember { mutableStateOf(false) }
    LaunchedEffect(hovered, focusInside) { onHold(hovered || focusInside) }
    val text = noticeText(notice, privacy, showcase = privacy)
    val tint = severityColor(notice.severity)
    Box(Modifier.fillMaxWidth().graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * rise.dp.toPx() }
        .hoverable(interaction).onFocusChanged { focusInside = it.hasFocus }) {
        TechPanel(Modifier.fillMaxWidth(), rail = tint, background = Tech.Raised, padding = 10.dp) {
            // One spoken line, built from what is shown: identifying parts are already gone in privacy mode.
            Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = text.spoken; liveRegion = LiveRegionMode.Polite }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(notice.severity.label.uppercase(), fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = .8.sp, fontWeight = FontWeight.Bold, color = tint)
                    Text(notice.category, fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
                Text(text.headline, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                text.detail?.let { Text(it, fontSize = 12.sp, lineHeight = 16.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                incidentLabel(notice.incident)?.let { Spacer(Modifier.height(4.dp)); NoticeTag(it, if (notice.incident is NoticeIncident.Ongoing) tint else Tech.Green) }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (openable && notice.target != null) MiniAction("Open ${notice.target.kind.label.lowercase()}", primary = true, onClick = onOpen)
                Spacer(Modifier.weight(1f))
                HelpTip("Close · stays unread in notifications") { CloseAction("Close", onClose) }
            }
        }
    }
}

/** The badge on the rail's bell: the unread count, or nothing. Static (no pulse). */
@Composable internal fun rememberNoticeBadge(alerts: AlertsUi?): Int = alerts?.notices?.let { unreadNoticeCount(it.items, it.now) } ?: 0

/** Watch toggle for a guest page or the inspector. Shown only when the parent wired the Watcher's guest action. */
@Composable internal fun WatchGuestButton(resource: ResourceId, name: String) {
    val alerts = LocalAlerts.current ?: return
    val watcher = alerts.watcher ?: return
    val toggle = alerts.watcherActions.onWatchGuest ?: return
    val on = resource in watcher.watchedGuests
    HelpTip(if (on) "Watched: changes are reported in notifications. Click to stop." else "Report changes to this guest in notifications") {
        MiniAction(if (on) "Watching" else "Watch", primary = on) { toggle(resource, name, !on) }
    }
}

/** Watch toggle for a node in the fleet inspector. Shown only when the parent wired the Watcher's node action. */
@Composable internal fun WatchNodeButton(node: String) {
    val alerts = LocalAlerts.current ?: return
    val watcher = alerts.watcher ?: return
    val toggle = alerts.watcherActions.onWatchNode ?: return
    val on = node in watcher.watchedNodes
    HelpTip(if (on) "Watched: changes are reported in notifications. Click to stop." else "Report changes to this node in notifications") {
        MiniAction(if (on) "Watching" else "Watch", primary = on) { toggle(node, !on) }
    }
}
