package local.ringleader.desktop

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/*
 * Page changes (rail pages, and the guest page opening from the fleet list).
 *
 * The old page is kept only as a picture: on a change the host takes a picture of what it shows, the old page leaves the
 * composition at once, the new page is composed underneath, and the picture fades out while the new page fades in (and,
 * on the Full tier, slides a few dp in the navigation direction). So at any moment there is exactly one live page: no
 * second console, poller, focus owner or pointer target on the way out, and the first frame after the change still shows
 * the old page (no blank frame). A change during a fade starts from what is on screen. Reduce motion cuts at once, as before.
 *
 * AnimatedContent (used here before) keeps the outgoing page composed and live for the whole exit; see PageTransitionTest
 * for what that meant for effects and input.
 */

internal const val PAGE_TRANSITION_MS = 180

/** Page change slide in dp: 12 on the Full tier, none on Lite (fade only) and none under Reduce motion. */
internal fun pageSlideDp(reduceMotion: Boolean, motion: RmMotion): Float = if (reduceMotion) 0f else motion.travel(12f)

/** How long a page crossfade takes: [PAGE_TRANSITION_MS] on Full, a quarter less on Lite, an instant cut under Reduce motion. */
internal fun pageFadeMillis(reduceMotion: Boolean, motion: RmMotion): Int = if (reduceMotion || motion.reduced) 0 else motion.ms(PAGE_TRANSITION_MS)

/** The Watcher page, listed in the rail after the fixed pages when the parent provides a watcher. */
internal const val WATCHER_PAGE = "Watcher"

/** Navigation order for slide direction: the rail order, then pages that are not in it (always after, in a fixed order). */
internal fun pageOrder(page: String): Int {
    val known = ringPages.indexOf(page)
    if (known >= 0) return known
    val extra = listOf(WATCHER_PAGE).indexOf(page)
    return ringPages.size + if (extra >= 0) extra else 50
}

/** -1, 0 or 1: the side the new page comes in from. Unknown and equal pages do not slide. */
internal fun pageDirection(from: String, to: String): Int = pageOrder(to).compareTo(pageOrder(from)).coerceIn(-1, 1)

/** The picture of the page that left and how much of it still shows; [progress] runs 0 to 1 over the change. */
@Stable internal class PageFade {
    var picture by mutableStateOf<ImageBitmap?>(null); internal set
    var progress by mutableFloatStateOf(1f); internal set
    var direction by mutableIntStateOf(0); internal set
    /** Changes started, for tests and probes. */
    var changes by mutableIntStateOf(0); internal set
}

/**
 * Shows [content] for [target], crossfading from the previous target's picture. [direction] gives the side the new
 * content enters from (-1, 0, 1). Each target gets its own composition (state is not carried between pages, as with
 * AnimatedContent before).
 */
@Composable internal fun <T> PageTransitionHost(
    target: T, modifier: Modifier = Modifier, direction: (from: T, to: T) -> Int = { _, _ -> 0 },
    fade: PageFade = remember { PageFade() }, content: @Composable (T) -> Unit,
) {
    val motion = LocalMotion.current
    val reduce = LocalReduceMotion.current
    val ms = pageFadeMillis(reduce, motion)
    val slidePx = with(LocalDensity.current) { pageSlideDp(reduce, motion).dp.toPx() }
    val layer = rememberGraphicsLayer()
    var shown by remember { mutableStateOf(target) }
    // A frozen picture must not retain identifying content after Showcase is enabled.
    var previousShowcase by remember { mutableStateOf(Appearance.showcase) }
    if (previousShowcase != Appearance.showcase) SideEffect {
        previousShowcase = Appearance.showcase
        fade.picture = null
        fade.progress = 1f
    }
    // Reduce motion: an instant cut in the same frame as the change (no picture, nothing to wait for).
    val instant = ms <= 0
    if (instant && shown != target) SideEffect { if (shown != target) { fade.changes++; fade.picture = null; fade.progress = 1f; shown = target } }
    val visible = if (instant) target else shown
    LaunchedEffect(target, instant, Appearance.showcase) {
        if (instant) { fade.picture = null; fade.progress = 1f; shown = target; return@LaunchedEffect }
        if (target == shown) return@LaunchedEffect
        fade.changes++
        val picture = freezePage(layer, fade)
        fade.direction = direction(shown, target)
        fade.picture = picture
        fade.progress = 0f
        shown = target
        animate(0f, 1f, animationSpec = tween(ms, easing = MotionEase.Standard)) { value, _ -> fade.progress = value }
        // Only a finished fade lets its picture go. A newer change cancels this one first and then folds this picture, at
        // its current strength, into its own (freezePage); clearing it on cancellation made an interrupted change jump.
        if (fade.picture === picture) { fade.picture = null; fade.progress = 1f }
    }
    Box(modifier.drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
        val picture = fade.picture
        val left = 1f - fade.progress
        if (picture != null && left > 0f) drawImage(picture, alpha = left)
    }) {
        key(visible) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                val p = fade.progress
                if (fade.picture != null && p < 1f) {
                    alpha = pageInAlpha(p)
                    translationX = fade.direction * slidePx * (1f - p)
                }
            }) { content(visible) }
        }
    }
}

/**
 * The new page's opacity at [progress]: ahead of the old picture's fade (1 - progress), so the two together never cover
 * less than about 85 % of the area (a plain crossfade dips to 75 % at the midpoint, where the backdrop shows through).
 */
internal fun pageInAlpha(progress: Float): Float { val left = 1f - progress.coerceIn(0f, 1f); return 1f - left * left }

/** Coverage of both layers at [progress], for tests: new page over nothing, old picture over that. */
internal fun pageCoverage(progress: Float): Float { val inA = pageInAlpha(progress); val outA = 1f - progress.coerceIn(0f, 1f); return inA + outA * (1f - inA) }

/** What the host shows now as one picture: its last frame, with an older picture still fading on top. */
private suspend fun freezePage(layer: GraphicsLayer, fade: PageFade): ImageBitmap? {
    if (layer.size.width <= 0 || layer.size.height <= 0) return null
    val base = try { layer.toImageBitmap() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { return null }
    val older = fade.picture?.takeIf { fade.progress < 1f } ?: return base
    return ImageBitmap(base.width, base.height).also { out ->
        Canvas(out).apply {
            drawImage(base, Offset.Zero, Paint())
            drawImage(older, Offset.Zero, Paint().apply { alpha = 1f - fade.progress })
        }
    }
}
