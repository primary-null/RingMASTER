package local.ringleader.desktop

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/**
 * How long a theme change takes. Lite (software drawing) is a little shorter; Reduce motion keeps a brief fade rather
 * than a cut, since a jump between Light and Pitch Black is exactly the jolt it is meant to avoid (colour fades are allowed).
 */
fun paletteFadeMillis(tier: MotionTier): Int = when (tier) {
    MotionTier.Full -> 450
    MotionTier.Lite -> 300
    MotionTier.Reduced -> 160
}

/** The picture of the old theme fading out over one surface, and how much of it still shows. */
@Stable class ThemeFade {
    var picture by mutableStateOf<ImageBitmap?>(null); internal set
    var alpha by mutableFloatStateOf(0f); internal set
}

/**
 * A crossfade for theme changes, as the desktop itself does it: when the theme (or the background image) changes, this
 * surface keeps a picture of what it showed, the colours switch underneath in one step, and the picture fades out on top.
 * Halfway through both looks show at half strength, so text and shapes never melt into one grey, and each frame only
 * draws one translucent picture: nothing is recoloured or recomposed for the fade, which keeps it smooth when drawing
 * is done in software.
 *
 * Use it as the root of the window and of a dialog that draws over it (each keeps its own picture). The first one on
 * screen switches the shared colours, two frames after the change so every surface has its picture first. A change
 * during a fade starts from what is on screen, so nothing jumps. Without any crossfade on screen (start-up, tests),
 * [Appearance] switches at once.
 */
@Composable fun ThemeCrossfade(modifier: Modifier = Modifier, fade: ThemeFade = remember { ThemeFade() }, content: @Composable BoxScope.() -> Unit) {
    val owner = remember { Any() }
    val layer = rememberGraphicsLayer()
    DisposableEffect(owner) {
        Appearance.attachTransition(owner)
        onDispose { Appearance.detachTransition(owner) }
    }
    // System theme: a window brought back to the front asks the operating system again, so a light/dark switch there fades in here.
    val active = LocalWindowActive.current
    LaunchedEffect(active) { if (active && Appearance.drivesTransition(owner)) Appearance.recheckSystemTheme() }
    val target = Appearance.targetPalette
    val tier = LocalMotion.current.tier
    LaunchedEffect(target) {
        if (Appearance.palette != target) {
            freeze(layer, fade)?.let { fade.picture = it; fade.alpha = 1f }
            if (Appearance.drivesTransition(owner)) {
                repeat(2) { withFrameNanos { } }  // let a dialog over the window take its picture too
                Appearance.showPalette(owner, target)
            } else snapshotFlow { Appearance.palette }.first { it == target }
        }
        val picture = fade.picture ?: return@LaunchedEffect
        val from = fade.alpha
        animate(from, 0f, animationSpec = tween((paletteFadeMillis(tier) * from).roundToInt().coerceAtLeast(1), easing = MotionEase.Crossfade)) { value, _ ->
            fade.alpha = value
        }
        if (fade.picture === picture) { fade.picture = null; fade.alpha = 0f }
    }
    Box(modifier.drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
        val picture = fade.picture
        if (picture != null && fade.alpha > 0f) drawImage(picture, alpha = fade.alpha)
    }, content = content)
}

/** What this surface shows now, as a picture: its last frame, with any older picture still fading on top. */
private suspend fun freeze(layer: GraphicsLayer, fade: ThemeFade): ImageBitmap? {
    if (layer.size.width <= 0 || layer.size.height <= 0) return null
    val base = runCatching { layer.toImageBitmap() }.getOrNull() ?: return null
    val older = fade.picture?.takeIf { fade.alpha > 0f } ?: return base
    return ImageBitmap(base.width, base.height).also { out ->
        Canvas(out).apply {
            drawImage(base, Offset.Zero, Paint())
            drawImage(older, Offset.Zero, Paint().apply { alpha = fade.alpha })
        }
    }
}
