package local.ringleader.desktop

import androidx.compose.ui.graphics.toComposeImageBitmap

import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.ringleader.core.*

fun main(args: Array<String>) {
    ConfigGuard().startup()
    val options = local.ringleader.desktop.web.parseWebArgs(args.toList())
    if (options != null) {
        System.setProperty("java.awt.headless", "true")
        Appearance.load()
        Appearance.useBrowserDisplay()
        local.ringleader.desktop.web.WebServer(options).start(true)
    } else desktopMain()
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun desktopMain() = application {
    remember { ConfigGuard().startup(); Appearance.load() }
    // The last placement when its display is still connected, otherwise a size that fits the primary display.
    val windowState = remember {
        val start = initialWindowBounds()
        WindowState(placement = if (WindowMemory.maximized()) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = WindowPosition(start.x.dp, start.y.dp), size = androidx.compose.ui.unit.DpSize(start.width.dp, start.height.dp))
    }
    val preferences = remember { MotionPreferences() }
    var reduce by remember { mutableStateOf(preferences.read()) }
    var settings by remember { mutableStateOf(false) }
    var preferenceError by remember { mutableStateOf(false) }
    Window(onCloseRequest = { ConsoleHost.close(); exitApplication() }, title = WindowTitle.text, icon = AppIcon.large, onPreviewKeyEvent = { desktopShortcuts?.invoke(it) ?: false },
        // Compose's own undecorated resizer is off (zero thickness): WindowResizeFrame below is the only resizer.
        decoration = WindowDecoration.Undecorated(0.dp), resizable = true, state = windowState) {
        InstallDisplayFit(window, windowState) { Appearance.useDisplay(it) }
        // Every maximise goes through here, so the frame has the work area of its current monitor first (Windows).
        fun toggleMaximized() {
            if (windowState.placement == WindowPlacement.Maximized) windowState.placement = WindowPlacement.Floating
            else { applyMaximizedBounds(window); windowState.placement = WindowPlacement.Maximized }
        }
        // Fullscreen is the immersive console. The placement from before it is restored on exit, and it is not written
        // into the maximise memory: a later start would otherwise come back floating. DisplayFit only re-maximises
        // while placement is Maximized, and only saves bounds while Floating, so Fullscreen needs no guard there.
        val immersive = remember {
            var before = windowState.placement
            ImmersiveState { full ->
                if (full) {
                    if (windowState.placement != WindowPlacement.Fullscreen) before = windowState.placement
                    windowState.placement = WindowPlacement.Fullscreen
                } else if (windowState.placement == WindowPlacement.Fullscreen) {
                    if (before == WindowPlacement.Maximized) { applyMaximizedBounds(window); windowState.placement = WindowPlacement.Maximized }
                    else windowState.placement = WindowPlacement.Floating
                }
            }
        }
        LaunchedEffect(windowState.placement) {
            if (windowState.placement != WindowPlacement.Fullscreen) WindowMemory.rememberMaximized(windowState.placement == WindowPlacement.Maximized)
        }
        var focused by remember { mutableStateOf(window.isFocused) }
        DisposableEffect(window) {
            val listener = object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent) { focused = true }
                override fun windowLostFocus(e: java.awt.event.WindowEvent) { focused = false }
            }
            window.addWindowFocusListener(listener)
            focused = window.isFocused
            onDispose { window.removeWindowFocusListener(listener) }
        }
        val host = remember { DesktopAppHost(WindowControls(
            minimize = { windowState.isMinimized = true },
            maximize = { toggleMaximized() },
            close = { ConsoleHost.close(); exitApplication() }
        )) }
        MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides reduce, LocalSetReduceMotion provides { value -> preferences.write(value); reduce=value }) { ThemeCrossfade(Modifier.fillMaxSize()) {  // theme changes crossfade the whole window
            val base = androidx.compose.ui.platform.LocalDensity.current
            // Interface size: scales layout and text together on top of the OS display scaling.
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density * Appearance.uiScale, base.fontScale * Appearance.textScale)) {
            BackdropHost { Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Transparent, contentColor = Tech.Ink) { Column {
                if (!immersive.active) WindowDraggableArea(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().background(Tech.Raised).height(32.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Observe presses without consuming them: a consuming tap detector here swallowed the
                        // press and WindowDraggableArea never started a drag (verified on Debian/Xfce 0.11.0).
                        Row(Modifier.weight(1f).pointerInput(Unit) {
                            awaitPointerEventScope {
                                var lastPress = 0L
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type != PointerEventType.Press) continue
                                    val at = event.changes.firstOrNull()?.uptimeMillis ?: continue
                                    if (at - lastPress <= viewConfiguration.doubleTapTimeoutMillis) {
                                        toggleMaximized()
                                        lastPress = 0L
                                    } else lastPress = at
                                }
                            }
                        }, verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.foundation.Image(AppIcon.small, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("RingMASTER", color = Tech.Amber)
                        }
                        WindowControl(WindowGlyph.Minimize) { windowState.isMinimized=true }
                        WindowControl(if (windowState.placement == WindowPlacement.Maximized) WindowGlyph.Restore else WindowGlyph.Maximize) { toggleMaximized() }
                        WindowControl(WindowGlyph.Close) { ConsoleHost.close();exitApplication() }
                    }
                }
                Box(Modifier.weight(1f)) { RingApp(host, windowState.isMinimized, focused, immersive) }
            } } } }
            // Resize edges only while floating; they sit outside the interface-size scaling so they keep their physical width.
            if (windowState.placement == WindowPlacement.Floating) WindowResizeFrame(window, titleBar = 32.dp * Appearance.uiScale)
        } } }
    }
}

private var desktopShortcuts: ((KeyEvent) -> Boolean)? = null

@Composable fun RingApp(host: AppHost, minimized: Boolean = false, focused: Boolean = true, immersive: ImmersiveState? = null) {
    val immersiveState = immersive ?: remember { ImmersiveState() }
    if (!host.web) { CompositionLocalProvider(LocalAppHost provides host) { UpdateLifecycle() } }
    val motionPreferences = remember { MotionPreferences() }
    var webReduceMotion by remember { mutableStateOf(motionPreferences.read()) }
    val reduceMotion = if (host.web) webReduceMotion else LocalReduceMotion.current
    val setReduceMotion = if (host.web) { value: Boolean -> motionPreferences.write(value); webReduceMotion = value } else LocalSetReduceMotion.current
    CompositionLocalProvider(LocalAppHost provides host, LocalReduceMotion provides reduceMotion, LocalSetReduceMotion provides setReduceMotion, LocalImmersive provides immersiveState) {
    MaterialTheme(colorScheme = ringColors) {
    val base = androidx.compose.ui.platform.LocalDensity.current
    CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(if(host.web) base.density * Appearance.uiScale else base.density, if (host.web) base.fontScale * Appearance.textScale else base.fontScale)) {
    // The motion system (tier, tokens, operating-system preference, the spark overlay) wraps the desktop app.
    if (!host.web) ProvideMotionSystem { RingLeader(host, minimized, focused) } else ThemeCrossfade(Modifier.fillMaxSize()) { BackdropHost { Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Transparent, contentColor = Tech.Ink) { Column { if (!immersiveState.active) Row(Modifier.fillMaxWidth().background(Tech.Raised).padding(6.dp), verticalAlignment = Alignment.CenterVertically) { androidx.compose.foundation.Image(AppIcon.small, contentDescription = null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text("RingMASTER / Browser", color = Tech.Amber, fontSize = 11.sp) }; Box(Modifier.weight(1f)) { RingLeader(host, minimized, focused) } } } } }
    } } }
}

@Composable private fun RingLeader(host: AppHost, minimized:Boolean, focused:Boolean) {
    val platform = remember { platformLabel() }
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    // Several connections can be open at once. Each has its own workspace; one is in view and the desk is attached to it.
    val sessions = remember { Sessions(scope) { Workspace(scope) } }
    val desk = remember { ProfileDesk(sessions.state.value.active.workspace, ProfileStore(profileFile()).apply { onNewerSchema = SaveStatus::notice }).also(sessions::bind) }
    DisposableEffect(sessions) { onDispose { desk.close(); sessions.close() } }
    val sessionsState by sessions.state.collectAsState()
    var scannedSelection by remember { mutableStateOf<ScanHit?>(null) }
    val scanSession = remember { ScanSession(scope) }
    val bluetoothScanner = remember { localBluetoothScanner() }
    val vpnSession = remember { VpnSession(scope, local.ringleader.core.localVpnBackend(host.hostActions), host.hostActions) }
    SideEffect { vpnSession.minimized = minimized }
    val toolsSession = remember { ToolsSession(scope) }
    var page by remember { mutableStateOf("Desk") }
    var fleetRequest by remember { mutableStateOf<FleetRequest?>(null) }
    val pins = remember { PinStore() }
    // The last pin key each open session used, so a manual connection that becomes its saved profile keeps its pins.
    val lastPinKeys = remember { mutableMapOf<Long, String?>() }
    var railToggle by remember { mutableStateOf(0) }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsSection by remember { mutableStateOf(SettingsSection.Appearance) }
    // A minimised window stops every session's live reads; restoring it turns back on the ones that were on.
    LaunchedEffect(minimized) {
        if (minimized) sessions.pause()
        else if (sessions.resume()) sessions.state.value.active.workspace.setLiveTasks(page == "Activity")
    }
    // Everything below belongs to the session in view. The key gives each session its own remembered page state
    // (selection, open guest, search), so nothing from one connection is ever applied to another.
    val session = sessionsState.active
    key(session.id) {
    val workspace = session.workspace
    val state by workspace.state.collectAsState()
    val deskState by desk.state.collectAsState()
    SideEffect { desk.onWorkspace(state) }
    if (settingsOpen) SettingsDialog(state, workspace, settingsSection, { settingsSection = it }, { settingsOpen = false }) { host.windowControls?.close?.invoke() }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var seenEpoch by remember { mutableStateOf(state.connectionEpoch) }
    if (seenEpoch != state.connectionEpoch) {
        seenEpoch = state.connectionEpoch
        search = ""
        filter = "All"
    }
    LaunchedEffect(state.connectionEpoch) { if (!host.web) ConsoleHost.release() }
    LaunchedEffect(page) { if (!minimized) workspace.setLiveTasks(page == "Activity"); if (page != "Desk") desk.leaveDesk() }
    val searchFocus=remember { androidx.compose.ui.focus.FocusRequester() }
    CompositionLocalProvider(LocalSearchFocus provides searchFocus) {
    ProvideDeskClock(animations = !reduceMotion && focused && !minimized, tickSeconds = !minimized) {
    // Task moments (spark, breathing dot, glow, nudge) fire from real changes in this state, never from a refresh.
    ProvideMotionBook(state) {
    // Shortcuts are handled at the window, so they work even when nothing inside the page has focus.
    val pageKeys by rememberUpdatedState<(androidx.compose.ui.input.key.KeyEvent) -> Boolean>({
        if (page == "Fleet" && ConsoleKeys.handler?.invoke(it) == true) true
        else if (it.type == KeyEventType.KeyDown && it.key == Key.F5) {
            when (page) {
                "Fleet" -> { workspace.refresh(); true }
                "Storage" -> { workspace.refreshStorageContent(); true }
                "Activity" -> { workspace.refreshTasks(); true }
                else -> false
            }
        } else if(it.type == KeyEventType.KeyDown && it.isCtrlPressed) {
            val target=when(it.key) { Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine -> pagesFor(host).getOrNull(listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine).indexOf(it.key)); else -> null }
            if(it.key==Key.Comma) { settingsOpen = true;true } else if(it.key==Key.F && page=="Fleet") { runCatching { searchFocus.requestFocus() };true } else if(target!=null) { page=target;true } else if(it.key==Key.B && it.isShiftPressed) { Appearance.toggleShowcase();true } else if(it.key==Key.Equals || it.key==Key.Plus || it.key==Key.NumPadAdd) { Appearance.zoom(1);true } else if(it.key==Key.Minus || it.key==Key.NumPadSubtract) { Appearance.zoom(-1);true } else if(it.key==Key.Zero || it.key==Key.NumPad0) { Appearance.chooseScale(null);true } else if(it.key==Key.B) { railToggle++;true } else false
        } else false
    })
    DisposableEffect(host) { host.shortcuts = { Shortcuts.handle(it, pageKeys) }; if (!host.web) desktopShortcuts = host.shortcuts; onDispose { host.shortcuts = null; if(!host.web) desktopShortcuts = null } }
    val connectionKey = pinConnectionKey(state.demo, deskState.connectedProfileId, state.address)
    // Pins saved on a manual connection live under the origin. When this session moves from that manual connection to
    // the saved profile for the same server, kind and account, they move onto the profile once. Profile to profile never adopts.
    LaunchedEffect(connectionKey) {
        val previous = lastPinKeys.put(session.id, connectionKey)
        lastPinKeys.keys.retainAll(sessionsState.open.map { it.id }.toSet())
        val profileId = deskState.connectedProfileId
        if (state.demo || profileId.isNullOrBlank() || connectionKey != profileId) return@LaunchedEffect
        val profile = deskState.profiles.find { it.id == profileId } ?: return@LaunchedEffect
        val origin = profile.origin.trim()
        if (origin != state.address.trim() || profile.kind != state.kind) return@LaunchedEffect
        // The session identity is "kind|origin|token id" (Sessions.route); it never holds the secret.
        val identity = session.identity?.split('|', limit = 3) ?: return@LaunchedEffect
        if (identity.size != 3 || identity[0] != state.kind || identity[1] != state.address || identity[2] != profile.tokenId) return@LaunchedEffect
        if (previous != origin) return@LaunchedEffect
        pins.adoptOriginPins(origin, profileId)
    }
    val several = sessionsState.open.size > 1
    val disconnectInView = { sessions.disconnect(session.id); if (several) page = "Desk" }
    var linkNote by remember { mutableStateOf<String?>(null) }
    fun openPin(pin: Pin) {
        if (pin.target == PIN_SERVICE) {
            val url = pin.serviceUrl ?: return
            scope.launch {
                linkNote = when (withContext(kotlinx.coroutines.Dispatchers.IO) { openInBrowser(url) }) {
                    BrowserLaunch.Failed -> {
                        runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(url), null) }
                        "No browser opened, so the link was copied."
                    }
                    BrowserLaunch.Pending -> "Browser launch still running."
                    BrowserLaunch.Accepted -> null
                }
            }
            return
        }
        val guest = workspace.state.value.overview?.guests?.firstOrNull { pin.matches(it) } ?: return
        page = "Fleet"
        fleetRequest = FleetRequest(guest.resource, if (pin.target == PIN_VIEW) pin.tab ?: "Summary" else "Summary", System.nanoTime())
    }
    val consoleImmersive = LocalImmersive.current?.active == true
    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val updateState by DesktopUpdates.checker.state.collectAsState()
        if (!consoleImmersive) NavigationRail(state,page,platform,workspace,railToggle, updateAvailable = !host.web && updateState is UpdateState.Available, onSettings = { settingsOpen = true }, onAbout = { settingsSection = SettingsSection.About; settingsOpen = true }, pins = pins, connectionKey = connectionKey, onPin = ::openPin,
            sessions = sessionsState.open, activeSession = session.id, profiles = deskState.profiles, onSession = sessions::show) { page=it }
        Column(Modifier.weight(1f).fillMaxHeight().padding(if (consoleImmersive) 0.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(if (consoleImmersive) 0.dp else Tech.Space8)) {
            if (!consoleImmersive) {
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // With several connections open, the heading names the one in view.
                    if (several && page in sessionPages) Text(sessionLabel(state, deskState.profiles.find { it.id == session.profileId }).uppercase(), Modifier.widthIn(max = 260.dp).border(1.dp, Tech.Amber.copy(alpha = .5f), Tech.Control).padding(horizontal = 6.dp, vertical = 2.dp).identity(),
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Amber, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text("WORKSPACE / ${page.uppercase()}${if(page == "Fleet" && state.selected != null) " / ${state.selected!!.kind} ${state.selected!!.id}" else if(page == "Fleet" && state.selectedNode != null) " / ${state.selectedNode}" else ""}", fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.5.sp, color = Tech.Muted)
                    Text(when (page) { "Fleet" -> state.selected?.name ?: state.selectedNode ?: "Fleet overview"; "Storage" -> "Storage contents"; "Activity" -> "Recent activity"; "Scan" -> "Network scan"; "Bluetooth" -> "Nearby devices"; "VPN" -> "Tunnels and VPN"; "Tools" -> "Network tools"; "Under the Hood" -> "Under the Hood"; else -> "Connection desk" }, modifier=Modifier.weight(1f).then(if (page == "Fleet" && (state.selected != null || state.selectedNode != null)) Modifier.identity() else Modifier), fontSize = Tech.Title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(color = Tech.Edge)
            SaveStatus.problem?.let { problem ->
                Row(Modifier.fillMaxWidth().border(1.dp, Tech.Amber, Tech.Control).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("SAVE FAILED", fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = .8.sp, fontWeight = FontWeight.Bold, color = Tech.Amber)
                    Text(problem, Modifier.weight(1f), fontSize = 12.sp, color = Tech.Ink, maxLines = 2)
                    MiniAction("Dismiss", onClick = SaveStatus::dismiss)
                }
            }
            linkNote?.let { Text(it, fontSize = 12.sp, color = Tech.Muted) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val activeProfile = deskState.profiles.find { it.id == deskState.connectedProfileId }
                // Fade plus a 12dp slide in the navigation direction; a fade only on Lite, nothing under Reduce motion.
                val motion = LocalMotion.current
                val pageMs = if (reduceMotion || motion.reduced) 0 else PAGE_TRANSITION_MS
                val slidePx = with(androidx.compose.ui.platform.LocalDensity.current) { pageSlideDp(reduceMotion, motion).dp.roundToPx() }
                AnimatedContent(page,transitionSpec = { (fadeIn(tween(pageMs, easing = MotionEase.Standard)) + slideInHorizontally(tween(pageMs, easing = MotionEase.Standard)) { if(ringPages.indexOf(targetState)>ringPages.indexOf(initialState)) slidePx else -slidePx }) togetherWith fadeOut(tween(pageMs)) }) { targetPage ->
                when (targetPage) {
                    "Fleet" -> ChartLensHost(state, workspace) { InventoryScreen(state, workspace, activeProfile?.let { "${it.displayName} · ${it.origin}" }, search, { search = it }, filter, { filter = it }, { page = "Desk" }, disconnectInView, pins, connectionKey, fleetRequest) { fleetRequest = null } }
                    "Activity" -> ActivityScreen(state, workspace, activeProfile?.let { "${it.displayName} · ${it.origin}" }, { page = "Desk" }, disconnectInView)
                    "Scan" -> ScanScreen(scanSession, deskState.profiles.map { it.origin }) { scannedSelection = it; desk.discardSecrets(); page = "Desk" }
                    "Bluetooth" -> BluetoothScreen(bluetoothScanner)
                    "VPN" -> VpnScreen(vpnSession, if (state.connected) state.address else null, deskState.profiles.map { it.origin }) { scannedSelection = it; desk.discardSecrets(); page = "Desk" }
                    "Tools" -> ToolsScreen(toolsSession)
                    "Under the Hood" -> UnderTheHoodScreen(state)
                    "Storage" -> StoragePage(state, workspace)
                    else -> DeskScreen(state, deskState, desk, scannedSelection, onScan = { page = "Scan" }, sessions = sessionsState.open, activeSession = session.id, sessionNotice = sessionsState.notice,
                        onShowSession = sessions::show, onDisconnectSession = sessions::disconnect) { page = "Fleet" }
                }
                }
            }
        }
    }
    }
    }
}
    }
}

internal const val PAGE_TRANSITION_MS = 180

/** Page change slide in dp: 12 on the Full tier, none on Lite (fade only) and none under Reduce motion. */
internal fun pageSlideDp(reduceMotion: Boolean, motion: RmMotion): Float = if (reduceMotion) 0f else motion.travel(12f)

/** Pages that show one connection's data. The heading names the session in view on these when several are open. */
private val sessionPages = setOf("Fleet", "Storage", "Activity", "Under the Hood")

/** Short name of a session for the rail and headings: the saved profile's name, else the server's host and port. */
internal fun sessionLabel(state: WorkspaceState, profile: ConnectionProfile?): String = when {
    state.demo -> "Demonstration"
    profile != null -> profile.displayName
    state.address.isNotBlank() -> state.address.removePrefix("https://")
    else -> "New connection"
}

/**
 * Window-level keyboard shortcuts. Global ones (zoom, showcase) are handled here; page ones (Ctrl 1-9, Ctrl F,
 * Ctrl B, F5) are registered by the workspace. Handling them at the window means they work with no focused field.
 */
object Shortcuts {
    fun handle(e: androidx.compose.ui.input.key.KeyEvent, page: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null): Boolean {
        if (e.key == Key.AltLeft || e.key == Key.AltRight) Appearance.revealHeld = e.type == KeyEventType.KeyDown
        if (e.type != KeyEventType.KeyDown) return false
        // While a VM console has the keyboard, every key belongs to the guest (Ctrl+B, Ctrl+1-9, F5 included).
        if (VncKeyboard.captured) return false
        if (e.isCtrlPressed) when {
            e.key == Key.B && e.isShiftPressed -> { Appearance.toggleShowcase(); return true }
            e.key == Key.Equals || e.key == Key.Plus || e.key == Key.NumPadAdd -> { Appearance.zoom(1); return true }
            e.key == Key.Minus || e.key == Key.NumPadSubtract -> { Appearance.zoom(-1); return true }
            e.key == Key.Zero || e.key == Key.NumPad0 -> { Appearance.chooseScale(null); return true }
        }
        return page?.invoke(e) ?: false
    }
}

/** The app icon (design/icon/make_final.py): the detailed emblem for the window, the small-size cut for the title bar. */
object AppIcon {
    private fun load(name: String): androidx.compose.ui.graphics.painter.Painter {
        val bytes = AppIcon::class.java.getResourceAsStream("/$name")!!.use { it.readBytes() }
        return androidx.compose.ui.graphics.painter.BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
    }
    val large by lazy { load("ringmaster-icon.png") }
    val small by lazy { load("ringmaster-icon-small.png") }
}
