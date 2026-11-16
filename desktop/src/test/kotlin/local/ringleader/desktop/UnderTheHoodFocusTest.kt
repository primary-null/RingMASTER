@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.test.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import local.ringleader.core.*

/** A click on the live ticker lands on an area of Under the Hood: the stream is filtered or an operation selected, and the area is lit, then not. */
class UnderTheHoodFocusTest {
    private val marker = "GET /marker-operation"

    private class Hood(val focus: PageFocus, reduce: Boolean, context: CoroutineContext, activity: TickerActivity? = null) {
        val scene = ImageComposeScene(1280, 720, coroutineContext = context) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides reduce, LocalHoodProbe provides true, LocalPageFocus provides focus, LocalTickerActivity provides activity?.let { a -> { a } }) {
                    Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) { Box(Modifier.padding(16.dp)) { UnderTheHoodScreen(WorkspaceState(demo = true)) } }
                }
            }
        }
        val ui = CreateScene(scene)
        // After the frames CreateScene has drawn, so the scene's clock only ever moves forward.
        private var clock = 60_000_000_000L
        fun dump(name: String) { val image = scene.render(clock + 1_000_000L); java.io.File("build/test-debug").apply { mkdirs() }.resolve("$name.png").writeBytes(image.encodeToData()!!.bytes); image.close() }
        fun zone(z: HoodZone): SemanticsNode? = ui.nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == hoodZoneTag(z) }
        fun texts(): List<String> = ui.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
        /** Frames at about the real rate for [millis], because the highlight's hold is a real delay. */
        suspend fun run(millis: Long) { val end = System.nanoTime() + millis * 1_000_000; while (System.nanoTime() < end) { clock += 33_000_000L; scene.render(clock).close(); yield(); delay(30) } }
        /** The share of the top edge of [z] that is amber. */
        fun edge(z: HoodZone): Float {
            val bounds = zone(z)!!.boundsInWindow
            clock += 16_000_000L
            val image = scene.render(clock)
            val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
            val amber = Tech.Amber.toArgb(); val y = bounds.top.toInt() + 1
            var hit = 0; var total = 0
            for (x in (bounds.left.toInt() + 24)..(bounds.right.toInt() - 24) step 3) {
                val c = bitmap.getColor(x, y)
                total++
                if (abs(((c shr 16) and 255) - ((amber shr 16) and 255)) < 40 && abs(((c shr 8) and 255) - ((amber shr 8) and 255)) < 40 && abs((c and 255) - (amber and 255)) < 40) hit++
            }
            image.close()
            return hit.toFloat() / total.coerceAtLeast(1)
        }
        fun close() = scene.close()
    }

    private fun seed() {
        Trace.clear()
        repeat(6) { i -> Trace.emit(TraceSource.PveApi, "end", "GET /nodes/$i", durationMs = 10L + i, status = 200, outcome = TraceOutcome.Ok) }
        repeat(3) { i -> Trace.emit(TraceSource.PdmApi, "end", "GET /resources/$i", durationMs = 5L, status = 200, outcome = TraceOutcome.Ok) }
        Trace.emit(TraceSource.PveApi, "end", marker, durationMs = 77L, status = 503, outcome = TraceOutcome.Error)
        repeat(4) { i -> Trace.emit(TraceSource.PveApi, "end", "GET /after/$i", durationMs = 3L, status = 200, outcome = TraceOutcome.Ok) }
    }

    private suspend fun hood(reduce: Boolean = true, context: CoroutineContext, activity: TickerActivity? = null, block: suspend (Hood) -> Unit) {
        Appearance.preview(false); seed()
        val h = Hood(PageFocus(), reduce, context, activity)
        try { h.ui.frames(); block(h) } finally { h.close(); Trace.clear() }
    }

    @Test fun aSourceRequestFiltersTheStreamToThatSourceAndIsConsumed() = runSceneTest {
        hood(context = coroutineContext) { h ->
            assertTrue("PDMAPI" in h.texts(), "before: every source")
            h.focus.request(TickerTarget.Hood(HoodFocus.Stream(TraceSource.PveApi))); h.ui.frames()
            assertNull(h.focus.pending, "the page took the request")
            assertFalse("PDMAPI" in h.texts(), "after: only the PVE API rows"); assertTrue("PVEAPI" in h.texts())
            h.focus.request(TickerTarget.Hood(HoodFocus.Stream(null))); h.ui.frames()
            assertTrue("PDMAPI" in h.texts(), "no source: the whole stream again")
        }
    }

    @Test fun anOperationRequestSelectsItWithTheInspectorOpenOnIt() = runSceneTest {
        hood(context = coroutineContext) { h ->
            val id = Trace.snapshot().first { it.summary == marker }.id
            // The newest event is selected when the page opens, so the inspector starts on something else.
            // The marker took 77 ms and failed with 503; the newest event, which the inspector starts on, took 3 ms.
            assertFalse(h.texts().any { it.contains("Duration 77 ms") }, "at the start the inspector is not on the marker")
            h.focus.request(TickerTarget.Hood(HoodFocus.Operation(id))); h.ui.frames(); h.ui.frames()
            assertNull(h.focus.pending)
            assertTrue(h.texts().any { it.contains("Duration 77 ms") && it.contains("503") }, "the inspector is on the marker: ${h.texts().filter { "Duration" in it }}")
        }
    }

    @Test fun anOperationThatLeftTheBufferFallsBackToTheStream() = runSceneTest {
        hood(context = coroutineContext) { h ->
            h.focus.request(TickerTarget.Hood(HoodFocus.Operation(987_654_321L))); h.ui.frames()
            assertNull(h.focus.pending, "taken, not left waiting")
            assertTrue("PDMAPI" in h.texts(), "nothing was filtered")
        }
    }

    @Test fun aRequestForAnotherPageIsLeftAlone() = runSceneTest {
        hood(context = coroutineContext) { h ->
            h.focus.request(TickerTarget.Scan); h.ui.frames()
            assertEquals(TickerTarget.Scan, h.focus.pending?.target)
        }
    }

    @Test fun aStaleRequestIsNotActedOn() {
        var now = 0L
        val focus = PageFocus { now }
        focus.request(TickerTarget.Hood(HoodFocus.Engine))
        assertNotNull(focus.take { true })
        now += 5_000_000_000L
        assertNull(focus.take { true }, "older than a few seconds: a page opened much later must not light something nobody asked for")
        focus.request(TickerTarget.Scan)
        assertNull(focus.take { it is TickerTarget.Hood }); assertNotNull(focus.take { it is TickerTarget.Scan })
    }

    @Test fun theAreaIsLitThenNotAndAskingAgainLightsItAgain() = runSceneTest {
        val three = TickerActivity(listOf(TickerData.session(1, true), TickerData.session(2, false, label = "Office PVE", address = "https://pve-office.example.test:8006"), TickerData.session(3, false, label = "PDM", address = "https://pdm.example.test:8443")))
        hood(context = coroutineContext, activity = three) { h ->
            assertTrue(h.edge(HoodZone.Network) < .05f, "not lit before anyone asks")
            h.focus.request(TickerTarget.Hood(HoodFocus.Network)); h.ui.frames()
            h.run(500)
            if (h.edge(HoodZone.Network) <= .8f) h.dump("network-not-lit")
            assertTrue(h.edge(HoodZone.Network) > .8f, "lit: an amber outline along the area")
            h.run(2_300)
            assertTrue(h.edge(HoodZone.Network) < .05f, "and gone after about two seconds")
            h.focus.request(TickerTarget.Hood(HoodFocus.Network)); h.ui.frames(); h.run(500)
            assertTrue(h.edge(HoodZone.Network) > .8f, "the same area, asked again, is lit again")
        }
    }

    @Test fun underReduceMotionTheOutlineIsSteadyForTheSameTimeWithNoFade() = runSceneTest {
        hood(reduce = true, context = coroutineContext) { h ->
            h.focus.request(TickerTarget.Hood(HoodFocus.Errors)); h.ui.frames()
            h.run(300); val early = h.edge(HoodZone.Errors)
            h.run(1_000); val later = h.edge(HoodZone.Errors)
            assertTrue(early > .8f && later > .8f, "steady, not fading: $early then $later")
            assertEquals(early, later, .02f)
            h.run(1_000)
            assertTrue(h.edge(HoodZone.Errors) < .05f, "off, at once, after the two seconds")
        }
    }

    @Test fun theOutlineShowsInHighContrastToo() = runSceneTest {
        hood(reduce = true, context = coroutineContext) { h ->
            Appearance.preview(ThemeMode.HighContrast); h.ui.frames()
            h.focus.request(TickerTarget.Hood(HoodFocus.Latency)); h.ui.frames(); h.run(300)
            assertTrue(h.edge(HoodZone.Latency) > .8f)
            Appearance.preview(false)
        }
    }

    @Test fun everyAreaOfThePageIsReachableByAFocusRequest() = runSceneTest {
        val foci = listOf(HoodFocus.Engine to HoodZone.Engine, HoodFocus.Sessions to HoodZone.Sessions, HoodFocus.Network to HoodZone.Network, HoodFocus.Latency to HoodZone.Latency,
            HoodFocus.Requests to HoodZone.Requests, HoodFocus.Slowest to HoodZone.Slowest, HoodFocus.Errors to HoodZone.Errors)
        hood(context = coroutineContext) { h ->
            for ((focus, zone) in foci) {
                assertNotNull(h.zone(zone), "$zone exists on the page")
                h.focus.request(TickerTarget.Hood(focus)); h.ui.frames(); h.run(250)
                assertNull(h.focus.pending, focus.toString())
                assertTrue(h.edge(zone) > .8f, "$zone is lit by $focus")
                h.run(2_200)
            }
            assertNotNull(h.zone(HoodZone.Stream)); assertNotNull(h.zone(HoodZone.Inspector))
        }
    }

    @Test fun theNetworkAreaListsEveryConnectionAndMarksTheOneInView() = runSceneTest {
        val three = TickerActivity(listOf(TickerData.session(1, true), TickerData.session(2, false, label = "Office PVE", address = "https://pve-office.example.test:8006", pinned = false),
            TickerData.session(3, false, phase = TickerSessionPhase.Failed, label = "PDM", address = "https://pdm.example.test:8443", failure = TickerFailure.SignIn, route = null)))
        hood(context = coroutineContext, activity = three) { h ->
            val texts = h.texts()
            assertTrue("NETWORK" in texts.map { it.uppercase() } || texts.any { it.equals("NETWORK", true) })
            assertTrue(texts.any { it == "3 CONNECTIONS" })
            assertEquals(1, texts.count { it == "IN VIEW" })
            for (word in listOf("Pinned certificate", "Normal TLS", "Sign-in refused", "API token")) assertTrue(texts.any { it == word }, word)
            assertTrue(texts.any { it.startsWith("Direct") })
        }
    }

    @Test fun showcaseConcealsConnectionNamesAndAddressesButNotTheirFacts() = runSceneTest {
        val one = TickerActivity(listOf(TickerData.session(1, true, label = "Private lab name", address = "https://private-host.example.test:8006")))
        hood(context = coroutineContext, activity = one) { h ->
            Appearance.preview(false, showcase = true); h.ui.frames(); h.ui.frames()
            val visible = h.ui.nodes().filter { !h.ui.concealed(it) }.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
            assertFalse(visible.any { "Private lab name" in it || "private-host" in it }, "names and addresses are blurred: $visible")
            assertTrue(visible.any { it == "Pinned certificate" })
            Appearance.preview(false)
        }
    }

    @Test fun withNoConnectionTheNetworkAreaSaysSo() = runSceneTest {
        hood(context = coroutineContext, activity = TickerActivity()) { h ->
            assertTrue(h.texts().any { it.startsWith("No connection is open") })
        }
    }
}
