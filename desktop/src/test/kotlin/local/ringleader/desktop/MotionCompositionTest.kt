@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import local.ringleader.core.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** The two places where the motion system must behave whatever the app wraps around it. Composes in a scene, no window. */
class MotionCompositionTest {
    private fun compose(content: @Composable () -> Unit) {
        val scene = ImageComposeScene(200, 100, Density(1f), content = content)
        try { scene.render(0L).close() } finally { scene.close() }
    }

    @Test fun tokensFollowReduceMotionWhenNothingProvidesThem() {
        val seen = HashMap<String, MotionTier>()
        compose {
            CompositionLocalProvider(LocalReduceMotion provides true) { seen["reduce on"] = LocalMotion.current.tier }
            CompositionLocalProvider(LocalReduceMotion provides false) { seen["reduce off"] = LocalMotion.current.tier }
        }
        assertEquals(MotionTier.Reduced, seen["reduce on"], "Reduce motion is honoured even where the wrapper is left out (the browser-served scene)")
        assertEquals(MotionTier.Full, seen["reduce off"])
    }

    @Test fun theProvidedTierWinsOverTheFallback() {
        val seen = HashMap<String, MotionTier>()
        compose {
            CompositionLocalProvider(LocalReduceMotion provides false) {
                ProvideMotionSystem(tierOverride = MotionTier.Lite) { seen["lite"] = LocalMotion.current.tier }
            }
            CompositionLocalProvider(LocalReduceMotion provides false) {
                ProvideMotionSystem(tierOverride = MotionTier.Reduced) {
                    seen["reduced"] = LocalMotion.current.tier
                    seen["reduced effective"] = if (LocalReduceMotion.current) MotionTier.Reduced else MotionTier.Full
                }
            }
        }
        assertEquals(MotionTier.Lite, seen["lite"])
        assertEquals(MotionTier.Reduced, seen["reduced"])
        assertEquals(MotionTier.Reduced, seen["reduced effective"], "the effective Reduce motion value is what everything below reads")
    }

    @Test fun aScreenJoinsTheBookTheAppProvides() {
        val state = WorkspaceState(demo = true)
        val outer = arrayOfNulls<MotionBook>(1)
        val inner = arrayOfNulls<MotionBook>(1)
        val alone = arrayOfNulls<MotionBook>(1)
        compose {
            ProvideMotionBook(state) {
                outer[0] = LocalMotionBook.current
                ProvideMotionBook(state) { inner[0] = LocalMotionBook.current }
            }
        }
        compose { ProvideMotionBook(state) { alone[0] = LocalMotionBook.current } }
        assertNotNull(outer[0])
        assertSame(outer[0], inner[0], "a screen that asks for its own book shares the app's, so the wait survives a page change")
        assertNotNull(alone[0], "without an app-wide book the screen still gets one (tests, probes)")
        assertNotSame(outer[0], alone[0])
    }
}
