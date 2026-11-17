@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.time.Duration
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import local.ringleader.core.*

/** The tray's behaviour in a scene: what a click raises, what the keyboard does, when it opens and settles, and when it is not there. */
class LiveTickerTest {
    private class Bed(val bench: TickerBench, blocked: Boolean = false, reduce: Boolean = true, page: String = "Fleet", width: Int = 1280, height: Int = 720,
        fontScale: Float = 1f, context: CoroutineContext) {
        var blockedNow by mutableStateOf(blocked)
        var pageNow by mutableStateOf(page)
        val scene = ImageComposeScene(width, height, Density(1f, fontScale), coroutineContext = context) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides reduce, LocalPageFocus provides bench.focus) {
                    Box(Modifier.fillMaxSize().background(Tech.Canvas)) { LiveTickerHost(pageNow, bench.state, bench.feed, bench.nav, blockedNow, period = 0L) }
                }
            }
        }
        val ui = CreateScene(scene)
        fun tray(): SemanticsNode? = ui.nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == TICKER_TAG }
        fun described(text: String) = ui.nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith(text) } == true }
        fun hasText(text: String) = ui.nodes().any { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.equals(text, true) } == true }
        fun focused() = ui.nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }
        suspend fun clickDescribed(text: String) {
            val at = described(text)!!.boundsInWindow.center
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, at)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, at)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, at)
            ui.frames()
        }
        fun close() = scene.close()
    }

    private suspend fun bed(mode: TickerMode, input: TickerInput = TickerData.calm, context: CoroutineContext, block: suspend (Bed) -> Unit) {
        LiveTickerPreference.preview(mode)
        Appearance.preview(false)
        val bench = TickerBench()
        bench.sample(input, mode)
        val b = Bed(bench, context = context)
        try { b.ui.frames(); block(b) } finally { b.close(); LiveTickerPreference.preview(TickerMode.Automatic) }
    }

    @Test fun aTileClickRaisesItsFocusRequestAndGoesToUnderTheHood() = runSceneTest {
        bed(TickerMode.Always, context = coroutineContext) { b ->
            b.ui.click("LATENCY")
            assertEquals(TickerTarget.Hood(HoodFocus.Latency), b.bench.focus.pending?.target)
            assertEquals(listOf("Under the Hood"), b.bench.pages)
            b.ui.click("REQUESTS")
            assertEquals(TickerTarget.Hood(HoodFocus.Requests), b.bench.focus.pending?.target)
            b.ui.click("REFRESH")
            assertEquals(TickerTarget.Hood(HoodFocus.Engine), b.bench.focus.pending?.target)
            assertEquals(3, b.bench.pages.size)
        }
    }

    @Test fun everyLinkRaisesItsOwnRequest() = runSceneTest {
        bed(TickerMode.Always, context = coroutineContext) { b ->
            val expected = listOf("PVE API" to HoodFocus.Stream(TraceSource.PveApi), "SIGN-IN" to HoodFocus.Stream(TraceSource.Auth), "SLOWEST" to HoodFocus.Operation(77),
                "ALL SLOW" to HoodFocus.Slowest, "ENGINE" to HoodFocus.Engine)
            for ((label, focus) in expected) {
                b.ui.click(label)
                assertEquals(TickerTarget.Hood(focus), b.bench.focus.pending?.target, label)
            }
            assertEquals(expected.size, b.bench.pages.size)
            assertTrue(b.bench.pages.all { it == "Under the Hood" })
        }
    }

    /** A reading with nothing but [activity] and the trace's sessions: one tile, so it is wide enough for all three links. */
    private fun alone(activity: TickerActivity, sessions: List<TraceSource> = emptyList()) = TickerData.input(TickerData.vitals(perMinute = 0, sessions = sessions),
        TickerData.metrics(p50 = null, p95 = null, sources = emptyList()), TraceLiveLoop("idle"), activity)

    @Test fun linksToOtherPartsOfTheAppGoThereAndAskForTheirHighlight() = runSceneTest {
        val task = TickerTask("UPID:pve-east:0007", "Backup", "build-runner", TickerData.now.minusSeconds(60))
        val activity = TickerActivity(listOf(TickerData.session()), scan = TickerScan(42, 5), tasks = listOf(task), failedTask = task, uploading = true, uploadPercent = 10,
            toolRunning = true, vpnBusy = true, kept = listOf(TickerConsole(TickerData.guest, "build-runner")))
        // Now, in the tile order: upload, task, scan, tool, VPN: the first three links show; the others are reached by finishing the first.
        val running = alone(activity)
        bed(TickerMode.Always, running, coroutineContext) { b ->
            b.ui.click("STORAGE"); assertEquals("Storage", b.bench.pages.last())
            b.ui.click("ACTIVITY"); assertEquals("Activity", b.bench.pages.last()); assertEquals(TickerTarget.Activity(task.upid), b.bench.focus.pending?.target)
            b.ui.click("SCAN"); assertEquals("Scan", b.bench.pages.last()); assertEquals(TickerTarget.Scan, b.bench.focus.pending?.target)
        }
        val others = alone(activity.copy(uploading = false, tasks = emptyList(), scan = null), listOf(TraceSource.Console))
        bed(TickerMode.Always, others, coroutineContext) { b ->
            b.ui.click("TOOLS"); assertEquals("Tools", b.bench.pages.last())
            b.ui.click("VPN"); assertEquals("VPN", b.bench.pages.last()); assertEquals(TickerTarget.Vpn(), b.bench.focus.pending?.target)
        }
        val console = alone(TickerActivity(listOf(TickerData.session()), kept = listOf(TickerConsole(TickerData.guest))), listOf(TraceSource.Console))
        bed(TickerMode.Always, console, coroutineContext) { b ->
            b.ui.click("GUEST")
            assertEquals(listOf<TickerTarget>(TickerTarget.Fleet(TickerData.guest, console = true)), b.bench.went)
            assertEquals("Fleet", b.bench.pages.last())
        }
        val failed = TickerData.failedConnection
        bed(TickerMode.Always, failed, coroutineContext) { b ->
            b.ui.click("DESK"); assertEquals("Desk", b.bench.pages.last())
        }
    }

    @Test fun anErrorTileOffersThePageItBelongsTo() = runSceneTest {
        // Errors alone: one tile, wide enough for all three of its links.
        val input = TickerData.input(TickerData.vitals(perMinute = 0), TickerData.metrics(p50 = null, p95 = null, sources = emptyList(), errors = listOf(TickerData.failing(TraceSource.Vpn, "vpn change", status = 1))),
            TraceLiveLoop("idle"), TickerActivity(listOf(TickerData.session())))
        bed(TickerMode.Always, input, coroutineContext) { b ->
            b.ui.click("LATEST"); assertEquals(TickerTarget.Hood(HoodFocus.Operation(31)), b.bench.focus.pending?.target)
            b.ui.click("VPN"); assertEquals("VPN", b.bench.pages.last())
            b.ui.click("ALL VPN"); assertEquals(TickerTarget.Hood(HoodFocus.Stream(TraceSource.Vpn)), b.bench.focus.pending?.target)
            b.ui.click("ERRORS · 5 MIN"); assertEquals(TickerTarget.Hood(HoodFocus.Errors), b.bench.focus.pending?.target)
        }
    }

    @Test fun oneTabStopAndArrowKeysMoveThroughTilesAndLinks() = runSceneTest {
        bed(TickerMode.Always, context = coroutineContext) { b ->
            assertNull(b.focused())
            b.ui.key(Key.Tab)
            assertEquals(TICKER_TAG, b.focused()?.config?.getOrNull(SemanticsProperties.TestTag), "the tray is the one tab stop")
            // The first item is the first tile; Enter opens its area.
            b.ui.key(Key.Enter)
            assertEquals(TickerTarget.Hood(HoodFocus.Requests), b.bench.focus.pending?.target)
            // Right moves to that tile's first link, Space activates it.
            b.ui.key(Key.DirectionRight); b.ui.key(Key.Spacebar)
            assertEquals(TickerTarget.Hood(HoodFocus.Stream(TraceSource.PveApi)), b.bench.focus.pending?.target)
            // Down is the same step as right; left goes back.
            b.ui.key(Key.DirectionDown); b.ui.key(Key.Enter)
            assertEquals(TickerTarget.Hood(HoodFocus.Stream(TraceSource.Auth)), b.bench.focus.pending?.target)
            b.ui.key(Key.DirectionLeft); b.ui.key(Key.DirectionLeft); b.ui.key(Key.Enter)
            assertEquals(TickerTarget.Hood(HoodFocus.Requests), b.bench.focus.pending?.target)
            // End is the last control (Hide), Home the first tile.
            b.ui.key(Key.MoveEnd); b.ui.key(Key.Enter)
            assertTrue(b.bench.state.dismissed)
        }
        bed(TickerMode.Always, context = coroutineContext) { b ->
            // Tiles, links and controls are not tab stops of their own: Tab goes round the one stop it has, the tray, however often it is pressed.
            repeat(4) { b.ui.key(Key.Tab); assertEquals(TICKER_TAG, b.focused()?.config?.getOrNull(SemanticsProperties.TestTag)) }
            b.ui.key(Key.Tab, shift = true); assertEquals(TICKER_TAG, b.focused()?.config?.getOrNull(SemanticsProperties.TestTag))
        }
    }

    @Test fun escapeCollapsesAndEnterOpensAgain() = runSceneTest {
        bed(TickerMode.Always, context = coroutineContext) { b ->
            assertTrue(b.bench.state.expanded)
            b.ui.key(Key.Tab); b.ui.key(Key.Escape)
            assertFalse(b.bench.state.expanded)
            assertTrue(b.hasText("12/min") && !b.hasText("LATENCY"), "collapsed: only the pill's reading")
            b.ui.key(Key.Enter)
            assertTrue(b.bench.state.expanded); assertTrue(b.hasText("LATENCY"))
        }
    }

    @Test fun controlsPinCollapseAndHide() = runSceneTest {
        bed(TickerMode.Automatic, context = coroutineContext) { b ->
            assertFalse(b.bench.state.expanded, "calm: only the pill")
            b.ui.click("12/min")
            assertTrue(b.bench.state.expanded, "a click on the pill opens it")
            b.clickDescribed("Keep the live ticker open")
            assertTrue(b.bench.state.pinned)
            b.bench.now = b.bench.now.plusSeconds(600); b.bench.state.refresh(TickerMode.Automatic)
            assertTrue(b.bench.state.expanded, "pinned: no settling")
            b.clickDescribed("Keep the live ticker open")
            assertFalse(b.bench.state.pinned)
            b.clickDescribed("Collapse")
            assertFalse(b.bench.state.expanded)
            b.ui.click("12/min"); assertTrue(b.bench.state.expanded)
            b.clickDescribed("Hide it for this session")
            assertTrue(b.bench.state.dismissed); assertNull(b.tray(), "dismissed: not even the pill")
            // A mode chosen in Settings brings it back.
            LiveTickerPreference.preview(TickerMode.Always); b.ui.frames()
            assertFalse(b.bench.state.dismissed); assertNotNull(b.tray())
        }
    }

    @Test fun automaticOpensOnACueAndSettlesWhenTheClockMovesOn() = runSceneTest {
        bed(TickerMode.Automatic, context = coroutineContext) { b ->
            val state = b.bench.state
            assertFalse(state.expanded); assertTrue(b.hasText("12/min"))
            b.bench.sample(TickerData.error, TickerMode.Automatic); b.ui.frames()
            assertTrue(state.expanded, "an error opens it"); assertTrue(b.hasText("ERRORS · 5 MIN"))
            b.bench.now = b.bench.now.plusSeconds(6); state.refresh(TickerMode.Automatic); b.ui.frames()
            assertTrue(state.expanded, "still inside its few seconds")
            b.bench.now = b.bench.now.plusSeconds(2); state.refresh(TickerMode.Automatic); b.ui.frames()
            assertFalse(state.expanded, "settled back"); assertTrue(b.hasText("2 errors"), "the pill leads with the error")
            // A long operation starting opens it again; the same running thing continuing does not.
            b.bench.sample(TickerData.longRunning, TickerMode.Automatic); assertTrue(state.expanded)
            b.bench.now = b.bench.now.plusSeconds(20); state.refresh(TickerMode.Automatic); assertFalse(state.expanded)
            b.bench.sample(TickerData.longRunning, TickerMode.Automatic); assertFalse(state.expanded)
            // Finishing it, back-off and the first data of a new connection open it too.
            b.bench.sample(TickerData.calm, TickerMode.Automatic); assertTrue(state.expanded, "finished")
            b.bench.now = b.bench.now.plusSeconds(20); state.refresh(TickerMode.Automatic)
            b.bench.sample(TickerData.backoff, TickerMode.Automatic); assertTrue(state.expanded, "backing off")
        }
    }

    @Test fun hoveringKeepsItOpenPastItsFewSeconds() = runSceneTest {
        bed(TickerMode.Automatic, TickerData.error, coroutineContext) { b ->
            val state = b.bench.state
            b.bench.sample(TickerData.calm, TickerMode.Automatic); b.bench.sample(TickerData.error, TickerMode.Automatic); b.ui.frames()
            assertTrue(state.expanded)
            val at = b.tray()!!.boundsInWindow.center
            b.scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, at); b.ui.frames()
            b.bench.now = b.bench.now.plusSeconds(60); state.refresh(TickerMode.Automatic)
            assertTrue(state.expanded, "held by the pointer")
            b.scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, androidx.compose.ui.geometry.Offset(2f, 2f)); b.ui.frames()
            b.bench.now = b.bench.now.plusSeconds(1); state.refresh(TickerMode.Automatic); assertTrue(state.expanded, "it lingers a moment after the pointer leaves")
            b.bench.now = b.bench.now.plusSeconds(10); state.refresh(TickerMode.Automatic); assertFalse(state.expanded)
        }
    }

    @Test fun offHidesItAndNothingIsSampled() = runSceneTest {
        bed(TickerMode.Off, context = coroutineContext) { b ->
            assertNull(b.tray())
            b.bench.sample(TickerData.error, TickerMode.Off); b.ui.frames()
            assertNull(b.tray(), "an error does not bring it back")
            assertFalse(b.bench.state.expanded)
        }
    }

    @Test fun blockedAndEmptyShowNothingAndComeBack() = runSceneTest {
        bed(TickerMode.Always, context = coroutineContext) { b ->
            assertNotNull(b.tray())
            b.blockedNow = true; b.ui.frames(); b.ui.frames()
            assertNull(b.tray(), "blocked: away")
            b.blockedNow = false; b.ui.frames(); b.ui.frames()
            assertNotNull(b.tray())
            // Nothing to say: no tray, not even the pill.
            b.bench.sample(TickerData.input(TickerData.vitals(perMinute = 0), TickerData.metrics(p50 = null, p95 = null, sources = emptyList()), TraceLiveLoop("idle"), TickerActivity()), TickerMode.Always)
            b.ui.frames(); b.ui.frames()
            assertNull(b.tray())
        }
    }

    @Test fun navigationMapsEveryTargetToAPageAndAsksForAHighlightWhereThePageTakesOne() {
        val pages = mutableListOf<String>(); val guests = mutableListOf<Pair<ResourceId, Boolean>>()
        val focus = PageFocus(); val nav = TickerNav({ pages += it }, { resource, console -> guests += resource to console }, focus)
        fun go(target: TickerTarget) = nav.go(target).let { pages.lastOrNull() to focus.pending?.target }.also { focus.pending?.let(focus::consume) }
        assertEquals("Under the Hood" to TickerTarget.Hood(HoodFocus.Network), go(TickerTarget.Hood(HoodFocus.Network)))
        assertEquals("Desk" to null, go(TickerTarget.Desk))
        assertEquals("Scan" to TickerTarget.Scan, go(TickerTarget.Scan))
        assertEquals("VPN" to TickerTarget.Vpn(VpnKind.Tailscale), go(TickerTarget.Vpn(VpnKind.Tailscale)))
        assertEquals("Tools" to null, go(TickerTarget.Tools))
        assertEquals("Storage" to null, go(TickerTarget.Storage))
        assertEquals("Radios" to TickerTarget.Bluetooth, go(TickerTarget.Bluetooth), "the Radios page, on its Bluetooth view")
        assertEquals("Activity" to TickerTarget.Activity("UPID:x"), go(TickerTarget.Activity("UPID:x")))
        assertEquals("Fleet" to null, go(TickerTarget.Fleet()), "no guest: the Fleet page")
        assertTrue(guests.isEmpty())
        pages.clear(); nav.go(TickerTarget.Fleet(TickerData.guest, console = true)); nav.go(TickerTarget.Fleet(TickerData.guest, console = false))
        assertEquals(listOf(TickerData.guest to true, TickerData.guest to false), guests); assertTrue(pages.isEmpty(), "a guest is shown by the window, which also changes the page")
        assertNull(focus.pending)
    }

    @Test fun theTrayIsBlockedWhereItWouldBeInTheWay() {
        fun blocked(web: Boolean = false, hood: Boolean = true, minimized: Boolean = false, immersive: Boolean = false, page: String = "Fleet", modal: Boolean = false,
            creating: Boolean = false, notices: Boolean = false) = tickerBlocked(web, hood, minimized, immersive, page, modal, creating, notices)
        assertFalse(blocked())
        assertTrue(blocked(web = true), "browser-hosted mode")
        assertTrue(blocked(hood = false), "no Under the Hood to lead to")
        assertTrue(blocked(minimized = true), "minimised or hidden in the notification area")
        assertTrue(blocked(immersive = true), "a full-screen console")
        assertTrue(blocked(page = "Under the Hood"), "Under the Hood itself")
        assertTrue(blocked(modal = true), "a dialog, Settings, the chart lens")
        assertTrue(blocked(creating = true), "the create wizard")
        assertTrue(blocked(notices = true), "the notification centre")
        assertTrue(ringPages.filter { it != "Under the Hood" }.all { !blocked(page = it) })
    }

    @Test fun aCompactWindowShowsTwoTilesAndAWideOneFour() = runSceneTest {
        for ((width, shown) in listOf(1280 to 4, 900 to 2)) {
            LiveTickerPreference.preview(TickerMode.Always); Appearance.preview(false)
            val bench = TickerBench(); bench.sample(TickerData.error, TickerMode.Always)
            val bed = Bed(bench, width = width, context = coroutineContext)
            try {
                bed.ui.frames()
                val labels = listOf("ERRORS · 5 MIN", "REQUESTS", "LATENCY", "REFRESH")
                assertEquals(shown, labels.count { bed.hasText(it) }, "$width wide")
                assertTrue(bed.hasText("ERRORS · 5 MIN") && bed.hasText("REQUESTS"), "the most telling tiles are the ones kept")
            } finally { bed.close(); LiveTickerPreference.preview(TickerMode.Automatic) }
        }
    }

    @Test fun everyPageHasAnAnchorAndTheNarrowOnesAreColumns() {
        for (page in ringPages + WATCHER_PAGE) {
            for (width in listOf(1400.dp, 900.dp, 500.dp)) {
                val anchor = TickerPlacement.anchor(page, width)
                assertTrue(anchor.fx in 0f..1f && anchor.fy in 0f..1f, "$page at $width")
                // Never in the bottom end corner, where the notification toasts stack.
                assertFalse(anchor.fx == 1f && anchor.fy == 1f, "$page at $width")
            }
            // A narrow window gets the column at the foot of the page whatever the page.
            assertEquals(TickerAnchor(0f, 1f), TickerPlacement.anchor(page, 500.dp), page)
        }
        assertTrue(TickerPlacement.anchor("Desk", 1400.dp) != TickerPlacement.anchor("Scan", 1400.dp), "placement differs between pages")
        assertTrue(TickerPlacement.anchor("Activity", 1400.dp) != TickerPlacement.anchor("Activity", 900.dp), "the wide layout's stretch is not there at 900")
    }

    @Test fun reduceMotionAppearsAtOnceWhereFullMotionFades() = runSceneTest {
        val shown = mutableMapOf<Boolean, Int>()
        for (reduce in listOf(true, false)) {
            LiveTickerPreference.preview(TickerMode.Always); Appearance.preview(false)
            val bench = TickerBench(); bench.sample(TickerData.calm, TickerMode.Always)
            val bed = Bed(bench, reduce = reduce, context = coroutineContext)
            try {
                // Two frames of 16 ms: the first lets the sampler's state in, the second draws it.
                var t = 0L
                repeat(3) { t += 16_000_000L; bed.scene.render(t).close(); kotlinx.coroutines.yield() }
                val node = bed.tray() ?: error("no tray")
                val at = node.boundsInWindow.let { androidx.compose.ui.geometry.Offset(it.left + 4f, it.top + 4f) }
                val image = bed.scene.render(t + 16_000_000L)
                val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
                shown[reduce] = bitmap.getColor(at.x.toInt(), at.y.toInt())
                image.close()
            } finally { bed.close(); LiveTickerPreference.preview(TickerMode.Automatic) }
        }
        val panel = Tech.Panel.toArgb()
        assertEquals(panel, shown[true], "Reduce motion: the plate is whole at once")
        assertNotEquals(panel, shown[false], "Full motion: it is still fading in")
    }
}
