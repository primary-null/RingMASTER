package local.ringleader.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Amber that announces and then gets out of the way: the console's focus edge and the Console shortcut light up, hold
 * for a moment and fade, instead of staying lit (user report: the constant orange was distracting).
 */
class ArrivalGlowTest {
    private class Probe(reduced: Boolean) : AutoCloseable {
        var lit by mutableStateOf(false)
        var arrival by mutableStateOf(0)
        var seen = -1f
        private var time = 0L
        private val scene = ImageComposeScene(64, 64, Density(1f)) {
            CompositionLocalProvider(LocalReduceMotion provides reduced) { val glow by rememberArrivalGlow(arrival, lit); seen = glow }
        }
        /** Advance the frame clock by [ms] in 16 ms frames and return the value on the last frame. */
        fun after(ms: Int): Float { repeat(ms / 16 + 1) { time += 16_000_000L; scene.render(time).close() }; return seen }
        override fun close() = scene.close()
    }

    @Test fun itLightsHoldsAndFadesAway() = Probe(reduced = false).use { probe ->
        assertEquals(0f, probe.after(100), "nothing is lit before anything arrives")
        probe.lit = true
        assertEquals(1f, probe.after(ARRIVAL_HOLD_MS - 150), "fully lit during the hold")
        val fading = probe.after(150 + ARRIVAL_FADE_MS / 2)
        assertTrue(fading > 0f && fading < 1f, "fading at the half-way point, was $fading")
        assertEquals(0f, probe.after(ARRIVAL_FADE_MS), "gone after the fade, and it stays gone")
        assertEquals(0f, probe.after(3000))
    }

    @Test fun aNewArrivalLightsItAgainAndLosingFocusClearsItAtOnce() = Probe(reduced = false).use { probe ->
        probe.lit = true
        assertEquals(0f, probe.after(ARRIVAL_HOLD_MS + ARRIVAL_FADE_MS + 200))
        probe.arrival = 1                                   // another guest was selected
        assertEquals(1f, probe.after(200))
        probe.lit = false                                   // the console lost the keyboard
        assertEquals(0f, probe.after(48))
    }

    @Test fun reducedMotionShowsItForTheHoldThenRemovesItWithoutAFade() = Probe(reduced = true).use { probe ->
        probe.lit = true
        assertEquals(1f, probe.after(ARRIVAL_HOLD_MS - 150))
        assertEquals(0f, probe.after(250))
    }
}
