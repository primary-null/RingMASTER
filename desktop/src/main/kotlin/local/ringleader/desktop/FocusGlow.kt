@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package local.ringleader.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** How long the arrival highlight is on, in milliseconds. */
internal const val FOCUS_LIGHT_MS = 2000
private const val FOCUS_LIGHT_HOLD_MS = 1000

/**
 * What a click on the live ticker does to the thing it lands on: it is brought into view (when [bringIntoView]) and drawn with an
 * amber outline and a soft wash that fade after about two seconds. Under Reduce motion the outline is steady for the same
 * time and then gone, with no fade. In high contrast the outline is thicker and the wash stronger, so it never relies on a tint.
 *
 * It lights when [lit] and a new [token] arrive together, so asking for the same thing again lights it again.
 */
@Composable internal fun Modifier.focusGlow(token: Long, lit: Boolean, bringIntoView: Boolean = true): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val glow = remember { Animatable(0f) }
    val reduce = LocalReduceMotion.current
    val motion = LocalMotion.current
    LaunchedEffect(token, lit) {
        if (!lit || token == 0L) return@LaunchedEffect
        // The page may have only just arrived: let it lay out, then bring the thing into view before lighting it.
        withFrameNanosTwice()
        if (bringIntoView) runCatching { requester.bringIntoView() }
        if (reduce) {
            glow.snapTo(1f); delay(FOCUS_LIGHT_MS.toLong()); glow.snapTo(0f)
        } else {
            glow.snapTo(0f)
            glow.animateTo(1f, tween(motion.ms(MotionDur.Short), easing = MotionEase.Enter))
            delay(FOCUS_LIGHT_HOLD_MS.toLong())
            glow.animateTo(0f, tween(motion.ms(FOCUS_LIGHT_MS - FOCUS_LIGHT_HOLD_MS - MotionDur.Short), easing = MotionEase.Standard))
        }
    }
    val contrast = Appearance.palette.highContrast
    return this.then(if (bringIntoView) Modifier.bringIntoViewRequester(requester) else Modifier).drawWithContent {
        drawContent()
        val a = glow.value
        if (a > 0f) {
            val stroke = (if (contrast) 3.dp else 2.dp).toPx()
            drawRect(Tech.Amber.copy(alpha = (if (contrast) .16f else .09f) * a))
            // A second, wider ring just inside the outline stands in for a glow.
            drawRect(Tech.Amber.copy(alpha = .28f * a), Offset(stroke * 1.4f, stroke * 1.4f), Size(size.width - stroke * 2.8f, size.height - stroke * 2.8f), style = Stroke(stroke * 1.4f))
            drawRect(Tech.Amber.copy(alpha = a), Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
        }
    }
}

private suspend fun withFrameNanosTwice() { androidx.compose.runtime.withFrameNanos { }; androidx.compose.runtime.withFrameNanos { } }
