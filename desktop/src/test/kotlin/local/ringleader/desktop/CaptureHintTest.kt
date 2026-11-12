package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The capture hint names the keyboard grab, then leaves the picture. Measured on the pixels it draws: amber text on a
 * black plate, against a black ground, advanced one rendered frame at a time.
 */
class CaptureHintTest {
    private class Probe(reduced: Boolean) : AutoCloseable {
        var captured by mutableStateOf(false)
        private var time = 0L
        private val frameNs = 16_000_000L
        private val scene = ImageComposeScene(480, 72, Density(1f)) {
            CompositionLocalProvider(LocalReduceMotion provides reduced) {
                Box(Modifier.fillMaxSize().background(Color.Black)) { CaptureHint(captured) }
            }
        }

        /** One 16 ms frame. The brightest red is the amber hint; the black ground stays at 0. */
        fun frame(): Int {
            time += frameNs
            return scene.render(time).use { image ->
                val bitmap = Bitmap.makeFromImage(image)
                var maxRed = 0
                for (y in 0 until bitmap.height) {
                    for (x in 0 until bitmap.width) {
                        val red = bitmap.getColor(x, y) shr 16 and 255
                        if (red > maxRed) maxRed = red
                    }
                }
                maxRed
            }
        }

        /** Advance [millis] in 16 ms frames and return the brightest red on the last frame. */
        fun after(millis: Int): Int {
            var last = 0
            repeat(millis / 16) { last = frame() }
            return last
        }

        /** The brightest red of each frame across [millis]. */
        fun samples(millis: Int): List<Int> = List(millis / 16) { frame() }

        override fun close() = scene.close()
    }

    @Test fun itShowsThenFadesAndStaysGoneWhileCaptured() = Probe(reduced = false).use { probe ->
        assertTrue(probe.after(48) < 15, "nothing is drawn before the keyboard is captured")
        probe.captured = true
        val shown = probe.after(200)
        assertTrue(shown > 180, "visible at about 200 ms, brightest red was $shown")
        val goneBy = (CAPTURE_HINT_HOLD_MS + CAPTURE_HINT_FADE_MS + 100 - 200).toInt()
        val gone = probe.after(goneBy)
        assertTrue(gone < 15, "fully gone by hold + fade + 100 ms, brightest red was $gone")
        val still = probe.after(400)
        assertTrue(still < 15, "nothing is drawn while the keyboard stays captured, brightest red was $still")
    }

    @Test fun itShowsAgainTheNextTimeTheKeyboardIsCaptured() = Probe(reduced = false).use { probe ->
        probe.captured = true
        probe.after((CAPTURE_HINT_HOLD_MS + CAPTURE_HINT_FADE_MS + 100).toInt())
        probe.captured = false
        assertTrue(probe.after(64) < 15, "releasing the keyboard clears the hint at once")
        probe.captured = true
        val again = probe.after(200)
        assertTrue(again > 180, "the next capture shows the hint again, brightest red was $again")
    }

    @Test fun reducedMotionHoldsThenRemovesItWithNoFade() = Probe(reduced = true).use { probe ->
        probe.captured = true
        val opening = probe.samples(200)
        assertTrue(opening.any { it > 180 }, "shown during the hold, reds were $opening")
        assertTrue(opening.none { it in 30..170 }, "no partly faded frame on the way in: $opening")
        val during = probe.after((CAPTURE_HINT_HOLD_MS - 400).toInt())
        assertTrue(during > 180, "still fully shown near the end of the hold, brightest red was $during")
        val closing = probe.samples(600)
        assertTrue(closing.none { it in 30..170 }, "no partly faded frame on the way out: $closing")
        assertTrue(closing.last() < 15, "gone after the hold, brightest red was ${closing.last()}")
        assertTrue(probe.after(300) < 15, "it stays gone while the keyboard stays captured")
    }
}
