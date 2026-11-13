@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Page changes, frame by frame, in an offscreen scene at explicit frame times. Pages are plain coloured boxes that count
 * their own life: compositions alive, effect ticks ("pollers"), clicks and keys. The last test records how the
 * AnimatedContent used before behaved, so the hazards the new host removes are measured rather than assumed.
 */
class PageTransitionTest {
    private class Life {
        var alive = 0; var maxAlive = 0; var composed = HashMap<String, Int>(); var ticks = HashMap<String, Int>()
        var clicks = HashMap<String, Int>(); var keys = HashMap<String, Int>()
    }

    private val colors = mapOf("A" to Color.Red, "B" to Color.Blue, "C" to Color.Green)

    @Composable private fun Page(name: String, life: Life, focus: FocusRequester? = null) {
        DisposableEffect(Unit) {
            life.alive++; life.maxAlive = maxOf(life.maxAlive, life.alive); life.composed.merge(name, 1, Int::plus)
            onDispose { life.alive-- }
        }
        // A stand-in for a page's poller: it ticks every frame while the page is composed.
        LaunchedEffect(Unit) { while (true) { withFrameNanos { }; life.ticks.merge(name, 1, Int::plus) } }
        Box(Modifier.fillMaxSize().background(colors.getValue(name)).clickable { life.clicks.merge(name, 1, Int::plus) }
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier).focusable()
            .onKeyEvent { if (it.type == KeyEventType.KeyDown) life.keys.merge(name, 1, Int::plus); false })
        if (focus != null) LaunchedEffect(Unit) { focus.requestFocus() }
    }

    private class Run(val scene: ImageComposeScene) {
        var clock = 0L
        suspend fun frame(ms: Long = 16) { clock += ms * 1_000_000L; scene.render(clock).close(); yield() }
        /** RGBA of one pixel of the next frame, as floats 0..1. */
        suspend fun sample(x: Int = 100, y: Int = 50, ms: Long = 16): FloatArray {
            clock += ms * 1_000_000L
            val image = scene.render(clock)
            try {
                val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
                val c = bitmap.getColor(x, y); bitmap.close()
                yield()
                return floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, ((c ushr 24) and 0xFF) / 255f)
            } finally { image.close() }
        }
    }

    private fun host(target: MutableState<String>, life: Life, reduce: Boolean = false, tier: MotionTier? = null, focusA: FocusRequester? = null,
                     body: suspend Run.() -> Unit) = runSceneTest {
        val scene = ImageComposeScene(200, 100, Density(1f), coroutineContext = coroutineContext) {
            CompositionLocalProvider(LocalReduceMotion provides reduce) {
                val content = @Composable {
                    PageTransitionHost(target.value, Modifier.fillMaxSize(), direction = { a, b -> b.compareTo(a).coerceIn(-1, 1) }) { page ->
                        Page(page, life, if (page == "A") focusA else null)
                    }
                }
                if (tier != null) ProvideMotionSystem(tierOverride = tier) { content() } else content()
            }
        }
        try { Run(scene).apply { repeat(3) { frame() } }.body() } finally { scene.close() }
    }

    @Test fun onlyOnePageIsEverAliveAndTheOldOneStopsAtOnce() {
        val target = mutableStateOf("A"); val life = Life()
        host(target, life) {
            val ticksBefore = life.ticks["A"] ?: 0
            target.value = "B"
            frame(); frame()
            val aTicksAtChange = life.ticks["A"] ?: 0
            repeat(20) { frame(); assertEquals(1, life.alive, "one live page during the change") }
            assertEquals(1, life.maxAlive, "the outgoing page was never alive beside the incoming one")
            assertEquals(aTicksAtChange, life.ticks["A"] ?: 0, "the old page's poller stopped when the change began")
            assertTrue(aTicksAtChange - ticksBefore <= 2, "at most the frames before the change was applied")
            assertTrue((life.ticks["B"] ?: 0) > 5, "the new page runs from the start")
        }
    }

    @Test fun theFirstFrameStillShowsTheOldPageAndNoFrameIsBlank() {
        val target = mutableStateOf("A"); val life = Life()
        host(target, life) {
            target.value = "B"
            val trace = (0 until 16).map { sample() }
            val first = trace.first()
            assertTrue(first[0] > .9f && first[2] < .1f && first[3] > .95f, "first frame after the change is still page A: ${first.toList()}")
            trace.forEachIndexed { i, c -> assertTrue(c[3] >= .8f, "frame $i covers ${c[3]}: never a blank or see-through frame") }
            // Red goes down and blue goes up without a step back.
            trace.zipWithNext().forEach { (a, b) -> assertTrue(b[0] <= a[0] + .02f && b[2] >= a[2] - .02f, "monotonic: ${a.toList()} -> ${b.toList()}") }
            val last = trace.last()
            assertTrue(last[2] > .95f && last[0] < .05f, "ends on page B: ${last.toList()}")
        }
        // The analytic curve agrees: the two layers never cover less than 85 %.
        (0..100).forEach { assertTrue(pageCoverage(it / 100f) >= .85f, "coverage at ${it}%: ${pageCoverage(it / 100f)}") }
    }

    @Test fun aChangeDuringAFadeContinuesFromWhatIsOnScreen() {
        val target = mutableStateOf("A"); val life = Life()
        host(target, life) {
            target.value = "B"
            repeat(4) { sample() }
            val before = sample()
            target.value = "C"
            val after = sample()
            val next = sample()
            // The frames after the interruption continue the A/B mix (the old fade may advance one frame before the new
            // change takes its picture), never a jump to C or to nothing.
            for (c in listOf(after, next)) assertTrue(kotlin.math.abs(before[0] - c[0]) < .22f && kotlin.math.abs(before[2] - c[2]) < .22f && c[1] < .15f && c[3] > .8f,
                "continuous: ${before.toList()} -> ${c.toList()}")
            val trace = (0 until 16).map { sample() }
            trace.forEach { assertTrue(it[3] >= .8f) }
            val last = trace.last()
            assertTrue(last[1] > .45f && last[0] < .05f && last[2] < .05f, "ends on C: ${last.toList()}")
            assertEquals(1, life.alive)
            assertEquals(1, life.maxAlive)
        }
    }

    @Test fun rapidChangesInOneFrameSkipThePagesInBetween() {
        val target = mutableStateOf("A"); val life = Life()
        host(target, life) {
            target.value = "B"; target.value = "C"
            repeat(20) { frame() }
            assertEquals(null, life.composed["B"], "B was asked for and replaced before a frame: never composed")
            assertEquals(1, life.composed["C"])
            assertEquals(1, life.alive)
        }
    }

    @Test fun clicksAndKeysDuringTheFadeReachOnlyTheNewPage() {
        val target = mutableStateOf("A"); val life = Life(); val focusA = FocusRequester()
        host(target, life, focusA = focusA) {
            repeat(3) { frame() }
            scene.sendKeyEvent(KeyEvent(Key.Spacebar, KeyEventType.KeyDown))
            assertEquals(1, life.keys["A"], "A had the keyboard before the change")
            target.value = "B"
            frame(); frame()
            // Mid-fade: the old page is a picture. A click at its centre and a key both miss it.
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 50f)); scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 50f))
            scene.sendKeyEvent(KeyEvent(Key.Spacebar, KeyEventType.KeyDown))
            frame()
            assertEquals(null, life.clicks["A"], "no click reached the outgoing page")
            assertEquals(1, life.clicks["B"], "the click landed on the page that is coming in")
            assertEquals(1, life.keys["A"], "no key reached the outgoing page")
        }
    }

    @Test fun reduceMotionCutsAtOnce() {
        val target = mutableStateOf("A"); val life = Life()
        host(target, life, reduce = true) {
            target.value = "B"
            val c = sample()
            assertTrue(c[2] > .97f && c[0] < .03f, "the very first frame is page B alone: ${c.toList()}")
            assertEquals(1, life.maxAlive)
        }
    }

    /** Page B with a 4 px green stripe at x = 40 on nothing; where is the stripe one frame into the fade? */
    private fun stripeAt(tier: MotionTier): Int {
        var found = -1
        val target = mutableStateOf("A")
        runSceneTest {
            val scene = ImageComposeScene(200, 100, Density(1f), coroutineContext = coroutineContext) {
                CompositionLocalProvider(LocalReduceMotion provides false) {
                    ProvideMotionSystem(tierOverride = tier) {
                        PageTransitionHost(target.value, Modifier.fillMaxSize(), direction = { _, _ -> 1 }) { page ->
                            if (page == "B") Box(Modifier.fillMaxSize()) { Box(Modifier.offset(x = 40.dp).width(4.dp).fillMaxHeight().background(Color.Green)) }
                        }
                    }
                }
            }
            try {
                val run = Run(scene); repeat(3) { run.frame() }
                target.value = "B"
                run.frame(); run.frame()
                run.clock += 16_000_000L
                val image = scene.render(run.clock)
                val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
                found = (0 until 200).maxBy { x -> (bitmap.getColor(x, 50) shr 8) and 0xFF }
                bitmap.close(); image.close()
            } finally { scene.close() }
        }
        return found
    }

    @Test fun fullSlidesInTheNavigationDirectionLiteOnlyFades() {
        val full = stripeAt(MotionTier.Full)
        val lite = stripeAt(MotionTier.Lite)
        assertTrue(full in 44..52, "Full: the new page comes in from the right, a few dp from home (was at $full)")
        assertTrue(lite in 40..43, "Lite: no slide, a fade only (was at $lite)")
        assertEquals(0, pageFadeMillis(true, RmMotion(MotionTier.Full)))
        assertEquals(0, pageFadeMillis(false, RmMotion(MotionTier.Reduced)))
        assertEquals(PAGE_TRANSITION_MS, pageFadeMillis(false, RmMotion(MotionTier.Full)))
        assertTrue(pageFadeMillis(false, RmMotion(MotionTier.Lite)) < PAGE_TRANSITION_MS)
    }

    @Test fun everyPageHasAStableDirection() {
        val all = ringPages + WATCHER_PAGE
        // Each pair goes one way and the reverse goes the other; the order is the rail's, Watcher after it.
        for (a in all) for (b in all) if (a != b) assertEquals(-pageDirection(a, b), pageDirection(b, a), "$a <-> $b")
        assertEquals(1, pageDirection("Desk", "Fleet"))
        assertEquals(1, pageDirection("Storage", "Schedules"))
        assertEquals(-1, pageDirection("Schedules", "Storage"))
        assertEquals(1, pageDirection("Schedules", WATCHER_PAGE))
        assertEquals(-1, pageDirection(WATCHER_PAGE, "Desk"))
        assertEquals(0, pageDirection("Fleet", "Fleet"))
        assertTrue(pageOrder("Unknown page") > pageOrder(WATCHER_PAGE), "an unknown page sorts last instead of first (indexOf -1)")
    }

    /**
     * What the previous AnimatedContent (same spec as Main.kt had) does in the same situation. Recorded, not assumed:
     * both pages are alive during the exit and the outgoing page's poller keeps ticking.
     */
    @Test fun theOldAnimatedContentKeptTheOutgoingPageAlive() {
        val target = mutableStateOf("A"); val life = Life()
        runSceneTest {
            val scene = ImageComposeScene(200, 100, Density(1f), coroutineContext = coroutineContext) {
                AnimatedContent(target.value, transitionSpec = { (fadeIn(tween(180)) + slideInHorizontally(tween(180)) { 12 }) togetherWith fadeOut(tween(180)) }) { page -> Page(page, life) }
            }
            try {
                val run = Run(scene); repeat(3) { run.frame() }
                target.value = "B"
                run.frame(); run.frame()
                val aTicks = life.ticks["A"] ?: 0
                repeat(4) { run.frame() }
                assertEquals(2, life.alive, "both pages are composed during the exit")
                assertTrue((life.ticks["A"] ?: 0) > aTicks, "the outgoing page's effects keep running during the exit")
                repeat(20) { run.frame() }
                assertEquals(1, life.alive, "and the old one goes when the exit ends")
            } finally { scene.close() }
        }
    }
}
