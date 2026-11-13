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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntRect
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
    } else {
        // One process per config store owns the scheduler. A second launch asks the owner to show its window and exits.
        val resident = ResidentRuntime.start() ?: return
        desktopMain(resident)
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun desktopMain() = desktopMain(null)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun desktopMain(resident: ResidentRuntime?) = application {
    remember { ConfigGuard().startup(); Appearance.load() }
    // Every close path goes through the resident policy: hide to the tray when it is installed and enabled, otherwise quit.
    val closeWindow: () -> Unit = { if (resident != null) resident.closeRequested() else { ConsoleHost.close(); exitApplication() } }
    val quitApp: () -> Unit = { if (resident != null) resident.quit() else { ConsoleHost.close(); exitApplication() } }
    remember { resident?.attach(::exitApplication) }
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
    Window(onCloseRequest = closeWindow, visible = resident?.windowVisible ?: true, title = WindowTitle.text, icon = AppIcon.large, onPreviewKeyEvent = { desktopShortcuts?.invoke(it) ?: false },
        // Compose's own undecorated resizer is off (zero thickness): WindowResizeFrame below is the only resizer.
        decoration = WindowDecoration.Undecorated(0.dp), resizable = true, state = windowState) {
        InstallDisplayFit(window, windowState) { Appearance.useDisplay(it) }
        if (resident != null) {
            LaunchedEffect(Unit) { resident.installTray() }
            // Tray Open, tray Schedules and a second launch raise the window above others.
            LaunchedEffect(resident.raiseToken) {
                if (resident.raiseToken == 0) return@LaunchedEffect
                windowState.isMinimized = false
                window.toFront(); window.requestFocus()
            }
            val scheduled = resident.engine?.state?.collectAsState()
            LaunchedEffect(scheduled?.value?.document, Appearance.showcase, resident.trayStatus) { resident.refreshTray() }
        }
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
        // Windows snapping needs a real frame. Until that installs (or it is switched off, or it fails) the Compose
        // drag and resize path stays. A failed install is undone inside the attempt and never stops the window opening.
        val wantNative = remember { nativeFrameRequested() }
        var native by remember { mutableStateOf<WindowsNativeFrame?>(null) }
        var nativeFailed by remember { mutableStateOf(false) }
        var titleBarRect by remember { mutableStateOf<IntRect?>(null) }
        var maximizeRect by remember { mutableStateOf<IntRect?>(null) }
        var minimizeRect by remember { mutableStateOf<IntRect?>(null) }
        var closeRect by remember { mutableStateOf<IntRect?>(null) }
        var frameWidth by remember { mutableStateOf(0) }
        var frameHeight by remember { mutableStateOf(0) }
        DisposableEffect(window) {
            var frame: WindowsNativeFrame? = null
            fun attempt() {
                if (frame != null || nativeFailed) return
                val outcome = WindowsNativeFrame.attempt(window, windowState.placement)
                val installed = outcome.frame
                if (installed != null) {
                    installed.onMaximize = { toggleMaximized() }
                    frame = installed
                    native = installed
                } else if (outcome.giveUp) nativeFailed = true
            }
            val opener = object : java.awt.event.WindowAdapter() {
                override fun windowOpened(e: java.awt.event.WindowEvent) { attempt() }
            }
            window.addWindowListener(opener)
            attempt()
            onDispose {
                window.removeWindowListener(opener)
                frame?.uninstall()
                native = null
            }
        }
        val host = remember { DesktopAppHost(WindowControls(
            minimize = { windowState.isMinimized = true },
            maximize = { toggleMaximized() },
            close = closeWindow,
            quit = quitApp,
        )) }
        MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalResident provides resident, LocalReduceMotion provides reduce, LocalSetReduceMotion provides { value -> preferences.write(value); reduce=value }) { ThemeCrossfade(Modifier.fillMaxSize()) {  // theme changes crossfade the whole window
            val base = androidx.compose.ui.platform.LocalDensity.current
            val composeChrome = native == null && (nativeFailed || !wantNative)
            val fitted = native
            // Read during composition. SideEffect alone does not subscribe, so a later resize would keep stale hit targets.
            val publishedWidth = frameWidth
            val publishedHeight = frameHeight
            val publishedTitle = titleBarRect
            val publishedMaximize = maximizeRect
            val publishedMinimize = minimizeRect
            val publishedClose = closeRect
            val publishedPlacement = windowState.placement
            val publishedShown = !immersive.active
            val publishedDark = !Appearance.palette.light
            val publishedEdge = Appearance.palette.edge
            val publishedBand = (6f * base.density + 0.5f).toInt().coerceAtLeast(1)
            val publishedCorner = (14f * base.density + 0.5f).toInt().coerceAtLeast(1)
            SideEffect {
                val frame = fitted ?: return@SideEffect
                frame.publish(publishedPlacement, FrameRegions(
                    width = publishedWidth, height = publishedHeight,
                    titleBar = if (publishedShown) publishedTitle else null,
                    maximizeButton = if (publishedShown) publishedMaximize else null,
                    otherControls = if (publishedShown) listOfNotNull(publishedMinimize, publishedClose) else emptyList(),
                    resizeBandPx = publishedBand, resizeCornerPx = publishedCorner,
                    titleBarHeightPx = if (publishedShown) publishedTitle?.height ?: 0 else 0,
                ), dark = publishedDark, border = colorRef(publishedEdge))
            }
            // Interface size: scales layout and text together on top of the OS display scaling.
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density * Appearance.uiScale, base.fontScale * Appearance.textScale)) {
            BackdropHost { Surface(Modifier.fillMaxSize().onGloballyPositioned { frameWidth = it.size.width; frameHeight = it.size.height }, color = androidx.compose.ui.graphics.Color.Transparent, contentColor = Tech.Ink) { Column {
                if (!immersive.active) {
                    val row = @Composable {
                        Row(Modifier.fillMaxWidth().background(Tech.Raised).height(32.dp).padding(horizontal = 12.dp).onGloballyPositioned { titleBarRect = it.frameRect() }, verticalAlignment = Alignment.CenterVertically) {
                            // Observe presses without consuming them: a consuming tap detector here swallowed the
                            // press and WindowDraggableArea never started a drag (verified on Debian/Xfce 0.11.0).
                            // With the native frame, Windows owns the caption drag and the double-click.
                            val drag = if (composeChrome) Modifier.weight(1f).pointerInput(Unit) {
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
                            } else Modifier.weight(1f)
                            Row(drag, verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.foundation.Image(AppIcon.small, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("RingMASTER", color = Tech.Amber)
                            }
                            Box(Modifier.onGloballyPositioned { minimizeRect = it.frameRect() }) {
                                WindowControl(WindowGlyph.Minimize) { windowState.isMinimized=true }
                            }
                            val glyph = if (windowState.placement == WindowPlacement.Maximized) WindowGlyph.Restore else WindowGlyph.Maximize
                            Box(Modifier.onGloballyPositioned { maximizeRect = it.frameRect() }) {
                                if (fitted != null) WindowControl(glyph, onClick = null, outsideHovered = fitted.hovered.value, outsidePressed = fitted.pressed.value)
                                else WindowControl(glyph) { toggleMaximized() }
                            }
                            Box(Modifier.onGloballyPositioned { closeRect = it.frameRect() }) {
                                WindowControl(WindowGlyph.Close, onClick = closeWindow)
                            }
                        }
                    }
                    if (composeChrome) WindowDraggableArea(Modifier.fillMaxWidth()) { row() } else row()
                }
                Box(Modifier.weight(1f)) { RingApp(host, windowState.isMinimized, focused, immersive, hidden = !(resident?.windowVisible ?: true)) }
            } } } }
            // Resize edges only while floating; they sit outside the interface-size scaling so they keep their physical width.
            // The native frame answers the same zones from WM_NCHITTEST, so the Compose grabs stay off while it is in.
            if (windowState.placement == WindowPlacement.Floating && composeChrome) WindowResizeFrame(window, titleBar = 32.dp * Appearance.uiScale)
        } } }
    }
}

private var desktopShortcuts: ((KeyEvent) -> Boolean)? = null

@Composable fun RingApp(host: AppHost, minimized: Boolean = false, focused: Boolean = true, immersive: ImmersiveState? = null, modals: ModalStack? = null, hidden: Boolean = false) {
    val immersiveState = immersive ?: remember { ImmersiveState() }
    // One record of the open overlays for the window shortcuts, the browser key path and the overlays themselves.
    val modalStack = modals ?: remember { ModalStack() }
    if (!host.web) { CompositionLocalProvider(LocalAppHost provides host, LocalModalStack provides modalStack) { UpdateLifecycle() } }
    val motionPreferences = remember { MotionPreferences() }
    var webReduceMotion by remember { mutableStateOf(motionPreferences.read()) }
    val reduceMotion = if (host.web) webReduceMotion else LocalReduceMotion.current
    val setReduceMotion = if (host.web) { value: Boolean -> motionPreferences.write(value); webReduceMotion = value } else LocalSetReduceMotion.current
    CompositionLocalProvider(LocalAppHost provides host, LocalReduceMotion provides reduceMotion, LocalSetReduceMotion provides setReduceMotion, LocalImmersive provides immersiveState, LocalModalStack provides modalStack) {
    MaterialTheme(colorScheme = ringColors) {
    val base = androidx.compose.ui.platform.LocalDensity.current
    CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(if(host.web) base.density * Appearance.uiScale else base.density, if (host.web) base.fontScale * Appearance.textScale else base.fontScale)) {
    // The motion system (tier, tokens, operating-system preference, the spark overlay) wraps the desktop app.
    if (!host.web) ProvideMotionSystem { RingLeader(host, minimized || hidden, focused, hidden) } else ThemeCrossfade(Modifier.fillMaxSize()) { BackdropHost { Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Transparent, contentColor = Tech.Ink) { Column { if (!immersiveState.active) Row(Modifier.fillMaxWidth().background(Tech.Raised).padding(6.dp), verticalAlignment = Alignment.CenterVertically) { androidx.compose.foundation.Image(AppIcon.small, contentDescription = null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text("RingMASTER / Browser", color = Tech.Amber, fontSize = 11.sp) }; Box(Modifier.weight(1f)) { RingLeader(host, minimized, focused) } } } } }
    } } }
}

@Composable private fun RingLeader(host: AppHost, minimized:Boolean, focused:Boolean, hidden: Boolean = false) {
    val platform = remember { platformLabel() }
    val reduceMotion = LocalReduceMotion.current
    val modals = LocalModalStack.current
    val scope = rememberCoroutineScope()
    val resident = if (host.web) null else LocalResident.current
    val residentAlerts = resident?.alerts
    // Several connections can be open at once. Each has its own workspace; one is in view and the desk is attached to it.
    val sessions = remember { Sessions(scope) { Workspace(scope) } }
    val desk = remember { ProfileDesk(sessions.state.value.active.workspace, ProfileStore(profileFile()).apply { onNewerSchema = SaveStatus::notice }, vault = SystemVault.forHost(host.web), rememberedProfiles = RememberedProfiles(profileFile().resolveSibling("remembered.json"))).also(sessions::bind) }
    DisposableEffect(sessions) { onDispose { desk.close(); sessions.close() } }
    val sessionsState by sessions.state.collectAsState()
    DisposableEffect(residentAlerts, sessions) {
        val job = residentAlerts?.attach(sessions)
        onDispose { job?.cancel() }
    }
    val notificationState = residentAlerts?.center?.state?.collectAsState()?.value
    val nativeDelivery = residentAlerts?.native?.state?.collectAsState()?.value
    val nativeOpenId = residentAlerts?.openRequest?.collectAsState()?.value
    SideEffect { residentAlerts?.native?.setShowcase(Appearance.showcase) }
    var scannedSelection by remember { mutableStateOf<ScanHit?>(null) }
    val scanSession = remember { ScanSession(scope) }
    val bluetoothScanner = remember { localBluetoothScanner() }
    val vpnSession = remember { VpnSession(scope, local.ringleader.core.localVpnBackend(host.hostActions), host.hostActions) }
    SideEffect { vpnSession.minimized = minimized }
    // The heading's connection-route tag (ConnectionRouteTag.kt). Browser sessions never run host route reads for it.
    val routeObserver = remember { ConnectionRouteObserver(scope, if (host.web) null else localRouteProbe(),
        VpnInventorySource({ vpnSession.snapshot }, vpnSession.backend?.takeUnless { host.web }?.let { backend -> { backend.snapshot() } })) }
    DisposableEffect(routeObserver) { onDispose { routeObserver.close() } }
    LaunchedEffect(vpnSession.changes) { if (vpnSession.changes != 0) routeObserver.invalidate() }
    val routeState by routeObserver.state.collectAsState()
    val toolsSession = remember { ToolsSession(scope) }
    var page by remember { mutableStateOf("Desk") }
    var fleetRequest by remember { mutableStateOf<FleetRequest?>(null) }
    var scheduleRunRequest by remember { mutableStateOf<String?>(null) }
    val pins = remember { PinStore() }
    // The last pin key each open session used, so a manual connection that becomes its saved profile keeps its pins.
    val lastPinKeys = remember { mutableMapOf<Long, String?>() }
    var railToggle by remember { mutableStateOf(0) }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsSection by remember { mutableStateOf(SettingsSection.Appearance) }
    var noticesOpen by remember { mutableStateOf(false) }
    // A minimised window stops every session's live reads; restoring it turns back on the ones that were on.
    LaunchedEffect(minimized, resident?.keepWatching, notificationState?.preferences, sessionsState.open, sessionsState.activeId, page) {
        configureAlertReads(sessions, minimized, resident?.keepWatching == true,
            taskAlertsEnabled(notificationState?.preferences), page == "Activity")
    }
    // Everything below belongs to the session in view. The key gives each session its own remembered page state
    // (selection, open guest, search), so nothing from one connection is ever applied to another.
    val session = sessionsState.active
    key(session.id) {
    val workspace = session.workspace
    val state by workspace.state.collectAsState()
    val deskState by desk.state.collectAsState()
    SideEffect { desk.onWorkspace(state) }
    // Notifications and the Watcher for the connection in view: LocalAlerts when the parent maps them itself, otherwise the
    // engine from LocalAlertsSource, mapped here (AlertsAdapter.kt). With neither there is no bell and no Watcher page.
    val inViewScope = remember(session.id, state.connectionEpoch, state.connected, state.demo, session.profileId) { watchScopeOf(state, session.profileId) }
    val alertsSource = if (host.web) null else LocalAlertsSource.current ?: residentAlerts?.let { owner ->
        remember(owner, resident?.keepWatching, nativeDelivery) {
            AlertsSource(owner.center, owner.watcher, owner.io,
                native = nativeDelivery ?: NativeDeliveryView(false, false, "Checking desktop notifications."),
                keepWatchingInBackground = resident?.keepWatching == true,
                onKeepWatchingInBackground = { resident?.chooseKeepWatching(it) },
                onTestNative = owner.native::testDelivery,
                onProblem = { message -> javax.swing.SwingUtilities.invokeLater { SaveStatus.notice(message) } })
        }
    }
    LaunchedEffect(residentAlerts, deskState.profiles) {
        residentAlerts?.let { owner -> withContext(kotlinx.coroutines.Dispatchers.IO) { owner.watcher.profilesChanged(deskState.profiles) } }
    }
    val alerts = LocalAlerts.current ?: alertsSource?.let { rememberAlertsUi(it, inViewScope, deskState.profiles) }
    val watcherOn = alerts?.watcher != null
    LaunchedEffect(watcherOn) { if (!watcherOn && page == WATCHER_PAGE) page = "Desk" }
    LaunchedEffect(alerts?.notices != null) { if (alerts?.notices == null) noticesOpen = false }
    if (settingsOpen) SettingsDialog(state, workspace, settingsSection, { settingsSection = it }, { settingsOpen = false }) { host.windowControls?.quit?.invoke() }
    if (resident != null) {
        // Tray "Schedules" and a second launch can ask for a page.
        LaunchedEffect(resident.pageRequest) {
            val wanted = resident.pageRequest ?: return@LaunchedEffect
            resident.pageRequest = null
            if (wanted in pagesFor(host) || (wanted == WATCHER_PAGE && watcherOn)) page = wanted
        }
        if (resident.quitPrompt) QuitPrompt(resident)
    }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var seenEpoch by remember { mutableStateOf(state.connectionEpoch) }
    if (seenEpoch != state.connectionEpoch) {
        seenEpoch = state.connectionEpoch
        search = ""
        filter = "All"
    }
    LaunchedEffect(state.connectionEpoch) { if (!host.web) ConsoleHost.release() }
    LaunchedEffect(page) { if (page != "Desk") desk.leaveDesk() }
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
            if(it.key==Key.N && page=="Fleet") { openCreateFromFleet(state, workspace, if(it.isShiftPressed) local.ringleader.core.NewGuestKind.Container else local.ringleader.core.NewGuestKind.Vm);true } else if(it.key==Key.Comma) { settingsOpen = true;true } else if(it.key==Key.F && page=="Fleet") { runCatching { searchFocus.requestFocus() };true } else if(target!=null) { page=target;true } else if(it.key==Key.B && it.isShiftPressed) { Appearance.toggleShowcase();true } else if(it.key==Key.Equals || it.key==Key.Plus || it.key==Key.NumPadAdd) { Appearance.zoom(1);true } else if(it.key==Key.Minus || it.key==Key.NumPadSubtract) { Appearance.zoom(-1);true } else if(it.key==Key.Zero || it.key==Key.NumPad0) { Appearance.chooseScale(null);true } else if(it.key==Key.B) { railToggle++;true } else false
        } else false
    })
    DisposableEffect(host) { host.shortcuts = { Shortcuts.handle(it, pageKeys, modals) }; if (!host.web) desktopShortcuts = host.shortcuts; onDispose { host.shortcuts = null; if(!host.web) desktopShortcuts = null } }
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
    // Notice and Watcher targets route inside the window the way pins do; a guest must be in this connection's inventory.
    val guestPresent: (ResourceId) -> Boolean = { resource -> state.overview?.guests?.any { it.resource == resource } == true }
    fun routeTo(route: NoticeRoute): Boolean = when (route) {
        is NoticeRoute.Guest -> {
            val guest = state.overview?.guests?.firstOrNull { it.resource == route.resource }
            if (guest != null) { page = "Fleet"; fleetRequest = FleetRequest(guest.resource, route.tab, System.nanoTime()) }
            guest != null
        }
        is NoticeRoute.Node -> if (state.overview?.members?.any { it.name == route.name } == true) { page = "Fleet"; workspace.selectMember(route.name); true } else false
        NoticeRoute.Activity -> { page = "Activity"; true }
        is NoticeRoute.Task -> {
            val parsed = parseServerUpid(route.upid)
            if (parsed != null && inViewScope != null && state.kind != PDM_KIND) {
                page = "Activity"
                workspace.openTaskLog(ClusterTask(inViewScope.remote, route.upid, parsed.node, parsed.type, parsed.subjectId,
                    parsed.user, null, null, null, TaskOutcome.Unknown))
                true
            } else false
        }
        NoticeRoute.Storage -> { page = "Storage"; true }
        NoticeRoute.Schedules -> if ("Schedules" in pagesFor(host)) { page = "Schedules"; true } else false
        is NoticeRoute.ScheduleRun -> if ("Schedules" in pagesFor(host)) { scheduleRunRequest = route.runId; page = "Schedules"; true } else false
        NoticeRoute.Watcher -> if (watcherOn) { page = WATCHER_PAGE; true } else false
        NoticeRoute.None -> false
    }
    // A target's session key is its connection binding (WatchScope.binding); only the connection in view can route.
    val canOpenNotice: (NoticeView) -> Boolean = { notice -> noticeTargetOpenable(notice.target, guestPresent, inViewScope?.binding) }
    val openNotice: (NoticeView) -> Unit = { notice ->
        val target = notice.target
        if (target != null && canOpenNotice(notice) && routeTo(target.route)) { noticesOpen = false; alerts?.noticeActions?.onOpenTarget?.invoke(notice) }
    }
    val openWatched: (WatchTargetView) -> Unit = { target ->
        val routed = when (target.kind) {
            WatchTargetKind.Guest -> target.resource?.let { routeTo(NoticeRoute.Guest(it)) } == true
            WatchTargetKind.Node -> routeTo(NoticeRoute.Node(target.name))
        }
        if (routed) alerts?.watcherActions?.onOpened?.invoke(target)
    }
    LaunchedEffect(nativeOpenId, session.id, alerts?.notices) {
        val id = nativeOpenId ?: return@LaunchedEffect
        val item = residentAlerts?.center?.state?.value?.items?.firstOrNull { it.id == id }
        val binding = when (val target = item?.target) {
            is NotificationTarget.Resource -> target.binding
            is NotificationTarget.Task -> target.binding
            else -> null
        }
        if (binding != null && binding != inViewScope?.binding) {
            val matching = sessionsState.open.firstOrNull { watchScopeOf(it.workspace.state.value, it.profileId)?.binding == binding }
            if (matching != null) { sessions.show(matching.id); return@LaunchedEffect }
        }
        val notice = alerts?.notices?.items?.firstOrNull { it.id == id }
        if (notice != null && canOpenNotice(notice)) openNotice(notice)
        else noticesOpen = true
        residentAlerts?.openRequest?.value = null
    }
    if (noticesOpen && alerts?.notices != null) NotificationCenterOverlay(alerts, canOpenNotice, openNotice) { noticesOpen = false }
    val consoleImmersive = LocalImmersive.current?.active == true
    // The route tag follows the session in view; it reads only while its heading is on screen.
    val routeContext = when { state.demo -> RouteContext.Demo; host.web -> RouteContext.Browser
        state.connected && state.address.isNotBlank() -> RouteContext.Server(session.id, state.connectionEpoch, state.address); else -> RouteContext.None }
    LaunchedEffect(routeContext, page, minimized, consoleImmersive) { routeObserver.update(routeContext, page in sessionPages && !minimized && !consoleImmersive) }
    CompositionLocalProvider(LocalAlerts provides alerts) {
    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val updateState by DesktopUpdates.checker.state.collectAsState()
        if (!consoleImmersive) NavigationRail(state,page,platform,workspace,railToggle, updateAvailable = !host.web && updateState is UpdateState.Available, onSettings = { settingsOpen = true }, onAbout = { settingsSection = SettingsSection.About; settingsOpen = true }, pins = pins, connectionKey = connectionKey, onPin = ::openPin,
            sessions = sessionsState.open, activeSession = session.id, profiles = deskState.profiles, onSession = sessions::show,
            onNotifications = { noticesOpen = true }) { page=it }
        Column(Modifier.weight(1f).fillMaxHeight().padding(if (consoleImmersive) 0.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(if (consoleImmersive) 0.dp else Tech.Space8)) {
            if (!consoleImmersive) {
            WorkspaceHeading(page, state, if (several && page in sessionPages) sessionLabel(state, deskState.profiles.find { it.id == session.profileId }) else null,
                route = if (page in sessionPages) routeState.forContext(routeContext) else null)
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
                // A picture crossfade (MotionPages.kt): the old page leaves the composition at once and fades out as a picture
                // while the new one fades in, with a 12dp slide in the navigation direction on Full; an instant cut under Reduce motion.
                // Hidden in the notification area: the page leaves composition like switching away (an open console is kept
                // paused for the Keep consoles time, then closed), and nothing on it animates. Sessions and the scheduler stay.
                if (!hidden) PageTransitionHost(page, Modifier.fillMaxSize(), direction = ::pageDirection) { targetPage ->
                when (targetPage) {
                    "Fleet" -> ChartLensHost(state, workspace) { InventoryScreen(state, workspace, activeProfile?.let { "${it.displayName} · ${it.origin}" }, search, { search = it }, filter, { filter = it }, { page = "Desk" }, disconnectInView, pins, connectionKey, fleetRequest) { fleetRequest = null } }
                    "Activity" -> ActivityScreen(state, workspace, activeProfile?.let { "${it.displayName} · ${it.origin}" }, { page = "Desk" }, disconnectInView)
                    "Scan" -> ScanScreen(scanSession, deskState.profiles.map { it.origin }) { scannedSelection = it; desk.discardSecrets(); page = "Desk" }
                    "Bluetooth" -> BluetoothScreen(bluetoothScanner)
                    "VPN" -> VpnScreen(vpnSession, if (state.connected) state.address else null, deskState.profiles.map { it.origin }) { scannedSelection = it; desk.discardSecrets(); page = "Desk" }
                    "Tools" -> ToolsScreen(toolsSession)
                    "Under the Hood" -> UnderTheHoodScreen(state)
                    "Storage" -> StoragePage(state, workspace,profileLabel=activeProfile?.displayName ?: "Manual connection")
                    "Schedules" -> SchedulesScreen(resident, onSettings = { settingsSection = SettingsSection.Background; settingsOpen = true },
                        requestedRun = scheduleRunRequest, onRunOpened = { scheduleRunRequest = null })
                    WATCHER_PAGE -> alerts?.watcher?.let { watcher ->
                        WatcherScreen(watcher, alerts.watcherActions, guestPresent, openWatched, unreadNotices = rememberNoticeBadge(alerts),
                            onNotifications = if (alerts.notices != null) ({ noticesOpen = true }) else null)
                    }
                    else -> DeskScreen(state, deskState, desk, scannedSelection, onScan = { page = "Scan" }, sessions = sessionsState.open, activeSession = session.id, sessionNotice = sessionsState.notice,
                        onShowSession = sessions::show, onDisconnectSession = sessions::disconnect) { page = "Fleet" }
                }
                }
                CreateGuestHost(state, workspace, activeProfile?.displayName ?: "Manual connection",
                    onOpenGuest = { guest -> fleetRequest = FleetRequest(guest.resource, "Summary", System.nanoTime()); page = "Fleet" },
                    onViewLog = { page = "Activity" })
                // New notifications as a short stack in the corner. Not over a full-screen console, not while the center is open.
                alerts?.notices?.let { notices ->
                    val toastsOff = alerts.prefs?.let { !it.enabled || it.inAppToasts == false } == true
                    NotificationToasts(notices, privacy = notices.privacy || Appearance.showcase, suppressed = hidden || noticesOpen || consoleImmersive || toastsOff,
                        canOpen = canOpenNotice, onOpen = openNotice, onOpenCenter = { noticesOpen = true },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp))
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
 * True when a console that has the keyboard keeps every key. A dialog, Settings or an in-page overlay on top of it holds
 * the keyboard instead, so the guest cannot receive keys and the window handles them. The node shell is itself the
 * console ([ModalKind.Shell]), so it keeps them.
 */
internal fun consoleOwnsKeys(captured: Boolean, top: ModalKind?): Boolean = captured && (top == null || top == ModalKind.Shell)

/** Window shortcuts. Page keys wait while an overlay is open; Ctrl+, closes Settings when it is on top. */
object Shortcuts {
    fun handle(e: androidx.compose.ui.input.key.KeyEvent, page: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null, modals: ModalStack? = null): Boolean {
        if (e.key == Key.AltLeft || e.key == Key.AltRight) Appearance.revealHeld = e.type == KeyEventType.KeyDown
        if (e.type != KeyEventType.KeyDown) return false
        val top = modals?.top
        // While a VM console or the node shell has the keyboard, every key belongs to it (Ctrl+B, Ctrl+1-9, F5, zoom and
        // Ctrl+, included), unless a dialog or overlay is open above it.
        if (consoleOwnsKeys(VncKeyboard.captured, top?.kind)) return false
        if (e.isCtrlPressed) when {
            e.key == Key.B && e.isShiftPressed -> { Appearance.toggleShowcase(); return true }
            e.key == Key.Equals || e.key == Key.Plus || e.key == Key.NumPadAdd -> { Appearance.zoom(1); return true }
            e.key == Key.Minus || e.key == Key.NumPadSubtract -> { Appearance.zoom(-1); return true }
            e.key == Key.Zero || e.key == Key.NumPad0 -> { Appearance.chooseScale(null); return true }
        }
        if (top != null) {
            if (e.isCtrlPressed && e.key == Key.Comma) { if (top.kind == ModalKind.Settings) modals.closeTop(); return true }
            // An in-page overlay (the guests list) has no dialog layer to take Escape, so it is closed from here.
            if (e.key == Key.Escape && !e.isCtrlPressed && !e.isAltPressed && !e.isShiftPressed && !e.isMetaPressed && top.kind == ModalKind.Overlay) return modals.closeTop()
            return false
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
