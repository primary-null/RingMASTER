@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import local.ringleader.core.*
import java.time.ZoneOffset
import kotlin.test.*

class ConnectionRouteTagTest {
    private val s = RouteTagSamples
    private fun view(state: RouteTagState, showcase: Boolean = false) = routeTagView(state, showcase, s.now, ZoneOffset.UTC)!!
    private fun RouteTagView.row(name: String) = rows.firstOrNull { it.first == name }?.second

    @Test fun labelsSayWhatWasObserved() {
        assertEquals("DIRECT · ETHERNET", view(s.direct).label)
        assertEquals("DIRECT · WI-FI", view(s.wifi).label)
        assertEquals("VPN · WIREGUARD", view(s.wireguard).label)
        assertEquals("VPN · TAILSCALE", view(s.tailscale).label)
        assertEquals("ROUTE UNKNOWN", view(s.unknown).label)
        assertEquals("ROUTE UNKNOWN", view(s.failed).label)
        assertEquals("VIA PROXY", view(s.proxy).label)
        assertEquals("CHECKING ROUTE", view(s.checking).label)
        assertEquals("DEMO · NO ROUTE", view(RouteTagState.Demo).label)
        assertEquals("ROUTE NOT SHOWN", view(RouteTagState.Browser).label)
        assertNull(routeTagView(RouteTagState.Hidden, false, s.now))
        assertEquals(listOf("DIRECT", "VPN", "UNKNOWN", "CHECKING"), listOf(s.direct, s.wireguard, s.unknown, s.checking).map { view(it).short })
        // Routine answers are plain; only a tunnel is green and only a proxy is amber.
        assertEquals(RouteTone.Plain, view(s.direct).tone); assertEquals(RouteTone.Tunnel, view(s.wireguard).tone)
        assertEquals(RouteTone.Caution, view(s.proxy).tone); assertEquals(RouteTone.Quiet, view(s.unknown).tone)
    }

    @Test fun tooltipRowsCarryTheEvidenceAndItsLimits() {
        val direct = view(s.direct)
        assertEquals("pve-west-cluster.example.test:8006", direct.row("Server"))
        assertEquals("HTTPS over IPv4", direct.row("Transport"))
        assertEquals("192.0.2.15 · first of 2 DNS answers", direct.row("Address"))
        assertEquals("Ethernet · Ethernet 2", direct.row("Interface"))
        assertEquals("Complete", direct.row("VPN check"))
        assertEquals("10:00:00 · 12 s ago", direct.row("Observed"))
        assertTrue(ROUTE_DIRECT_NOTE in direct.notes && ROUTE_ESTIMATE_NOTE in direct.notes)
        val ts = view(s.tailscale)
        assertTrue(ts.row("Peer link")!!.startsWith("Direct UDP to the peer"), "a direct Tailscale peer link is still Tailscale")
        assertTrue(ts.row("Peer link")!!.endsWith("still inside Tailscale"))
        assertEquals("Relayed through Tailscale DERP fra", view(s.tailscaleRelayed).row("Peer link"))
        assertEquals("Tailscale", ts.row("Tunnel"), "the kind is not repeated as the name")
        assertEquals("WireGuard · home-office", view(s.wireguard).row("Tunnel"))
        assertEquals("Incomplete · Tailscale is installed but its status could not be read.", view(s.unknown).row("VPN check"))
        assertNotNull(view(s.unknown).row("Why"))
        assertFalse(ROUTE_ESTIMATE_NOTE in view(s.failed).notes, "no route was read, so there is no estimate to qualify")
        assertEquals("10:00:00 · 12 s ago · re-checking", view(s.refreshing).row("Observed"))
        assertNull(view(s.proxy).row("Interface")); assertNull(view(s.proxy).row("VPN check"))
        assertTrue(view(RouteTagState.Browser).notes.single().contains("RingMASTER host"), "browser mode names the scope")
        for ((_, state) in s.all) {
            val v = view(state)
            assertFalse(Regex("(?i)token|secret|bearer|ticket|password|authorization").containsMatchIn(v.description), "no auth detail in ${v.label}")
        }
    }

    @Test fun showcaseHidesEveryIdentityInTheTagTooltipAndDescription() {
        for ((name, state) in s.all) {
            val v = view(state, showcase = true)
            val text = listOf(v.label, v.short, v.description) + v.rows.flatMap { listOf(it.first, it.second) } + v.notes
            for (identity in s.identities) assertTrue(text.none { it.contains(identity, ignoreCase = true) }, "$name shows $identity in Showcase: $text")
        }
        // Outside Showcase the same values are shown.
        assertTrue(view(s.wireguard).description.contains("home-office"))
        assertEquals("HTTP proxy Hidden", view(s.proxy, showcase = true).row("Proxy"))
        assertEquals("Hidden · first of 2 DNS answers", view(s.direct, showcase = true).row("Address"))
    }

    @Test fun spokenLabels() {
        assertEquals("Direct, Wi-Fi", spokenRouteLabel(view(s.wifi).label))
        assertEquals("VPN, WireGuard", spokenRouteLabel(view(s.wireguard).label))
        assertEquals("Route unknown", spokenRouteLabel(view(s.unknown).label))
        assertEquals("Demo, no route", spokenRouteLabel(view(RouteTagState.Demo).label))
    }

    @Test fun showcaseConcealsUnexpectedDiagnosticIdentities() {
        val original = s.unknown as RouteTagState.Observed
        val diagnostic = "adapter secret-office-adapter at unlisted.example.test failed"
        val state = original.copy(observation = original.observation.copy(vpnGaps = listOf(diagnostic), reason = diagnostic))
        val hidden = view(state, showcase = true)
        assertFalse(hidden.description.contains("secret-office-adapter"))
        assertFalse(hidden.description.contains("unlisted.example.test"))
        assertEquals("Incomplete · details hidden", hidden.row("VPN check"))
        assertTrue(view(state).description.contains(diagnostic))
    }

    @Test fun ageAndModeAndPlacement() {
        assertEquals("just now", routeAge(s.at, s.at.plusSeconds(2)))
        assertEquals("3 min ago", routeAge(s.at, s.at.plusSeconds(200)))
        assertEquals("just now", routeAge(s.at, s.at.minusSeconds(3)), "a clock step back is not a negative age")
        assertEquals(RouteTagMode.Full, routeTagMode(700.dp)); assertEquals(RouteTagMode.Short, routeTagMode(400.dp)); assertEquals(RouteTagMode.Glyph, routeTagMode(300.dp))
        val window = IntSize(1000, 600)
        assertEquals(androidx.compose.ui.unit.IntOffset(600, 46), routeTipOffset(IntRect(800, 20, 900, 40), window, IntSize(300, 200), 6), "below, right-aligned")
        assertEquals(androidx.compose.ui.unit.IntOffset(600, 294), routeTipOffset(IntRect(800, 500, 900, 520), window, IntSize(300, 200), 6), "above when there is no room below")
        assertEquals(0, routeTipOffset(IntRect(10, 20, 60, 40), window, IntSize(300, 200), 6).x, "never off the left edge")
        assertEquals(46, routeTipOffset(IntRect(10, 20, 60, 40), IntSize(200, 150), IntSize(300, 400), 6).y, "never over its own tag, even when nothing fits")
    }

    private class Scene(val scene: ImageComposeScene) {
        var time = 0L
        suspend fun frames(n: Int = 4) { repeat(n) { time += 16_000_000L; scene.render(time).close(); yield() } }
        fun nodes(): List<SemanticsNode> = buildList {
            fun visit(n: SemanticsNode) { add(n); n.children.forEach(::visit) }
            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
        }
        fun tagged(tag: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        suspend fun key(k: Key) { scene.sendKeyEvent(KeyEvent(k, KeyEventType.KeyDown)); scene.sendKeyEvent(KeyEvent(k, KeyEventType.KeyUp)); frames() }
        suspend fun move(at: Offset) { scene.sendPointerEvent(PointerEventType.Move, at); frames() }
        suspend fun click(at: Offset) { scene.sendPointerEvent(PointerEventType.Move, at); scene.sendPointerEvent(PointerEventType.Press, at); scene.sendPointerEvent(PointerEventType.Release, at); frames() }
    }

    @Test fun enlargedTooltipStaysInBoundsAndKeyboardCanReachItsEnd() = runSceneTest {
        val scene = ImageComposeScene(960, 640, Density(2f, 1.5f), coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    ConnectionRouteTag(s.wireguard, RouteTagMode.Glyph, clock = { s.now })
                }
            }
        }
        val h = Scene(scene)
        try {
            h.frames(); h.key(Key.Tab)
            val tip = assertNotNull(h.tagged(ROUTE_TIP_TEST_TAG))
            assertTrue(tip.boundsInWindow.bottom <= 640f, "The tooltip must fit inside the available height")
            val top = scene.render(++h.time).use { it.encodeToData()!!.bytes }
            h.key(Key.MoveEnd)
            val bottom = scene.render(++h.time).use { it.encodeToData()!!.bytes }
            assertFalse(top.contentEquals(bottom), "End must reveal the remaining detail rows")
            System.getenv("RINGMASTER_ROUTE_SCROLL_SHOTS")?.let { folder ->
                java.io.File(folder).mkdirs()
                java.io.File(folder, "tooltip-top.png").writeBytes(top)
                java.io.File(folder, "tooltip-bottom.png").writeBytes(bottom)
            }
        } finally { scene.close() }
    }

    @Test fun hoverAndKeyboardFocusShowTheDetailsAndClicksStillReachThePage() = runSceneTest {
        Appearance.preview(light = false)
        var clicks = 0
        var route by mutableStateOf<RouteTagState>(s.direct)
        val scene = ImageComposeScene(900, 420, Density(1f), coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        WorkspaceHeading("Fleet", WorkspaceState(connected = true, address = s.target.origin), null, route = route)
                        Box(Modifier.fillMaxWidth().weight(1f).clickable { clicks++ })
                    }
                }
            } }
        }
        val h = Scene(scene)
        try {
            h.frames()
            val tag = assertNotNull(h.tagged(ROUTE_TAG_TEST_TAG), "the tag is on the heading")
            assertTrue(tag.config.getOrNull(SemanticsProperties.ContentDescription)!!.single().startsWith("Route to server: Direct, Ethernet."))
            assertNull(h.tagged(ROUTE_TIP_TEST_TAG))
            // Hover: nothing at once, the details after the hover delay.
            h.move(tag.boundsInWindow.center)
            assertNull(h.tagged(ROUTE_TIP_TEST_TAG), "the tooltip waits for the hover delay")
            delay(ROUTE_TIP_DELAY_MS + 200); h.frames()
            val tip = assertNotNull(h.tagged(ROUTE_TIP_TEST_TAG), "hover shows the details")
            assertTrue(tip.boundsInWindow.top >= tag.boundsInWindow.bottom, "below the tag")
            assertTrue(tip.boundsInWindow.right <= 900f && tip.boundsInWindow.left >= 0f, "inside the window")
            h.move(Offset(30f, 380f))
            delay(200); h.frames()
            assertNull(h.tagged(ROUTE_TIP_TEST_TAG), "leaving the tag closes it")
            // Keyboard: Tab focuses the tag and opens the details at once; Escape closes them.
            h.key(Key.Tab)
            assertNotNull(h.tagged(ROUTE_TIP_TEST_TAG), "keyboard focus shows the details")
            // A click on the page under the open tooltip's level still reaches the page: the tooltip takes no clicks.
            h.click(Offset(30f, 380f))
            assertEquals(1, clicks)
            h.frames()
            if (h.tagged(ROUTE_TIP_TEST_TAG) != null) { h.key(Key.Escape); assertNull(h.tagged(ROUTE_TIP_TEST_TAG), "Escape closes the details") }
            // A state change while open updates the tag in place: no stale answer.
            route = s.wireguard; h.frames()
            assertTrue(h.tagged(ROUTE_TAG_TEST_TAG)!!.config.getOrNull(SemanticsProperties.ContentDescription)!!.single().contains("WireGuard"))
            route = RouteTagState.Hidden; h.frames()
            assertNull(h.tagged(ROUTE_TAG_TEST_TAG), "no connection, no tag")
        } finally { scene.close() }
    }

    @Test fun escapeClosesFocusedDetails() = runSceneTest {
        Appearance.preview(light = true)
        val scene = ImageComposeScene(900, 420, Density(1f), coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) { WorkspaceHeading("Activity", WorkspaceState(connected = true, address = s.target.origin), null, route = s.unknown) }
                }
            } }
        }
        val h = Scene(scene)
        try {
            h.frames(); h.key(Key.Tab)
            assertNotNull(h.tagged(ROUTE_TIP_TEST_TAG))
            h.key(Key.Escape)
            assertNull(h.tagged(ROUTE_TIP_TEST_TAG))
        } finally { scene.close(); Appearance.preview(light = false) }
    }

    @Test fun showcaseDescriptionOnTheRenderedTag() = runSceneTest {
        Appearance.preview(light = false, showcase = true)
        val scene = ImageComposeScene(900, 200, Density(1f), coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) { WorkspaceHeading("Fleet", WorkspaceState(connected = true, address = s.target.origin), null, route = s.tailscale) }
                }
            } }
        }
        val h = Scene(scene)
        try {
            h.frames()
            val text = h.nodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            assertTrue(text.any { it.startsWith("Route to server: VPN, Tailscale.") })
            for (identity in s.identities) assertTrue(text.none { it.contains(identity, ignoreCase = true) }, "Showcase leaks $identity")
        } finally { scene.close(); Appearance.preview(light = false) }
    }

    @Test fun narrowHeadingsKeepTheTagVisible() = runSceneTest {
        Appearance.preview(light = false)
        for ((width, density) in listOf(900 to 1f, 450 to 1f, 560 to 2f)) {
            val scene = ImageComposeScene(width, 200, Density(density, 1.5f), coroutineContext = coroutineContext) {
                MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                    Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                        Column(Modifier.fillMaxSize().padding(8.dp)) { WorkspaceHeading("Fleet", WorkspaceState(connected = true, address = s.target.origin), "Datacenter west production cluster", route = s.direct) }
                    }
                } }
            }
            val h = Scene(scene)
            try {
                h.frames()
                val tag = assertNotNull(h.tagged(ROUTE_TAG_TEST_TAG), "tag missing at $width px x$density")
                assertTrue(tag.boundsInWindow.width >= 16f * density, "tag squeezed at $width px x$density: ${tag.boundsInWindow}")
                assertTrue(tag.boundsInWindow.right <= width.toFloat() + 0.5f, "tag past the edge at $width px")
                val description = tag.config.getOrNull(SemanticsProperties.ContentDescription)!!.single()
                assertTrue(description.startsWith("Route to server: Direct, Ethernet."), "the full details stay available in every mode")
            } finally { scene.close() }
        }
    }
}
