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
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.test.*
import kotlinx.coroutines.*
import local.ringleader.core.*

/** A link into another part of the app lands there: the page takes the request and lights the thing it names, then lets go. */
class TickerLandingTest {
    private class Landing(page: String, pages: TickerPages, val focus: PageFocus, context: CoroutineContext) {
        val scene = ImageComposeScene(1280, 720, coroutineContext = context) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides true, LocalPageFocus provides focus) {
                    Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) { Box(Modifier.padding(16.dp)) { pages.Content(page) } }
                }
            }
        }
        private var clock = 0L
        /** Frames at about the real rate for [millis]: the highlight's two seconds are a real delay, and the VPN page reads on a worker. */
        suspend fun run(millis: Long) { val end = System.nanoTime() + millis * 1_000_000; while (System.nanoTime() < end) { clock += 33_000_000L; scene.render(clock).close(); yield(); delay(30) } }
        /** How many pixels are the outline's amber. */
        fun amber(): Int {
            clock += 16_000_000L
            val image = scene.render(clock)
            val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
            val want = Tech.Amber.toArgb()
            var hit = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val c = bitmap.getColor(x, y)
                if (abs(((c shr 16) and 255) - ((want shr 16) and 255)) < 12 && abs(((c shr 8) and 255) - ((want shr 8) and 255)) < 12 && abs((c and 255) - (want and 255)) < 12) hit++
            }
            image.close()
            return hit
        }
        fun close() = scene.close()
    }

    private fun landing(page: String, block: suspend (Landing, MatrixLab) -> Unit) {
        val sandbox = Files.createTempDirectory("ticker-landing")
        val home = System.getProperty("user.home")
        System.setProperty("user.home", sandbox.toString())
        try {
            runSceneTest {
                Appearance.preview(false)
                val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
                val lab = MatrixLab(sandbox)
                val pages = TickerPages(scope, lab, sandbox)
                val l = Landing(page, pages, PageFocus(), coroutineContext)
                try { l.run(600); block(l, lab) } finally { l.close(); pages.close(); scope.cancel() }
            }
        } finally { System.setProperty("user.home", home); sandbox.toFile().deleteRecursively() }
    }

    /** Lit means clearly more amber than the page has by itself (an outline round a row or a card is hundreds of pixels), and afterwards the page is as it was. */
    private suspend fun assertLitThenNot(l: Landing, target: TickerTarget, what: String) {
        val before = l.amber()
        l.focus.request(target)
        l.run(500)
        assertNull(l.focus.pending, "$what: the page took the request")
        val lit = l.amber()
        assertTrue(lit > before + 300, "$what is lit: $before amber pixels before, $lit while lit")
        l.run(2_400)
        val after = l.amber()
        assertTrue(abs(after - before) < 60, "$what is no longer lit: $before before, $after after")
    }

    @Test fun activityLightsTheRowOfTheTaskAskedFor() = landing("Activity") { l, lab ->
        assertLitThenNot(l, TickerTarget.Activity(lab.tasks[3].upid), "the task's row")
    }

    @Test fun activityWithoutATaskOrWithOneItDoesNotHaveTakesTheRequestAndLightsNothing() = landing("Activity") { l, _ ->
        val before = l.amber()
        l.focus.request(TickerTarget.Activity(null)); l.run(400)
        assertNull(l.focus.pending); assertTrue(abs(l.amber() - before) < 60)
        l.focus.request(TickerTarget.Activity("UPID:pve-east:FFFFFFFF:gone")); l.run(400)
        assertNull(l.focus.pending, "a task that left the list is not waited for"); assertTrue(abs(l.amber() - before) < 60)
    }

    @Test fun scanLightsItsProgressLine() = landing("Scan") { l, _ ->
        assertLitThenNot(l, TickerTarget.Scan, "the scan's progress line")
    }

    @Test fun vpnLightsTheTunnelOfTheKindThatCarriesTheConnection() = landing("VPN") { l, _ ->
        // The page reads its tunnels on a worker first.
        l.run(900)
        assertLitThenNot(l, TickerTarget.Vpn(VpnKind.Tailscale), "the Tailscale card")
    }

    @Test fun vpnWithoutAKindLightsTheTunnelThatIsUp() = landing("VPN") { l, _ ->
        l.run(900)
        assertLitThenNot(l, TickerTarget.Vpn(), "the tunnel that is up")
    }

    @Test fun aPageLeavesARequestThatIsNotItsOwn() = landing("Scan") { l, _ ->
        l.focus.request(TickerTarget.Hood(HoodFocus.Latency)); l.run(300)
        assertEquals(TickerTarget.Hood(HoodFocus.Latency), l.focus.pending?.target)
    }
}
