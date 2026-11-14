package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A fleet guest row is 36 dp tall and clips its children. The hover readout used to be one of those children,
 * so it never painted outside the row. This scene is the row, not a chart that fills the window.
 */
class ChartHoverClipTest {
    private val at = Instant.parse("2026-11-11T14:03:25Z")

    @Test fun readoutPaintsOutsideAClippedRowAndClearsOnExit() {
        val scene = ImageComposeScene(400, 200, Density(1f)) {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                Column(Modifier.fillMaxSize().background(Color.Black)) {
                    Spacer(Modifier.height(80.dp))
                    Box(Modifier.fillMaxWidth().height(36.dp).clipToBounds(), contentAlignment = Alignment.CenterStart) {
                        Sparkline(listOf(0.1, 0.42, 0.2, 0.3, 0.5), Modifier.width(160.dp).height(18.dp), color = Color(0xFF1B5E20), pulse = false,
                            times = List(5) { at }, label = { "61 % · 2.4 / 4.0 GiB" })
                    }
                }
            }
        }
        try {
            var now = 0L
            fun lightOutside(): Int {
                now += 16
                return scene.render(now * 1_000_000L).use { image ->
                    org.jetbrains.skia.Bitmap.makeFromImage(image).use { bitmap ->
                        var n = 0
                        for (y in 0 until image.height) {
                            if (y in 80 until 116) continue
                            for (x in 0 until image.width) {
                                val c = bitmap.getColor(x, y)
                                if (minOf(c shr 16 and 255, c shr 8 and 255, c and 255) > 180) n++
                            }
                        }
                        n
                    }
                }
            }
            repeat(4) { lightOutside() }
            val before = lightOutside()
            val over = Offset(40f, 98f)
            scene.sendPointerEvent(PointerEventType.Enter, over)
            scene.sendPointerEvent(PointerEventType.Move, over)
            val shown = List(6) { lightOutside() }.last()
            assertTrue(shown > before + 15, "readout text should paint outside the 36 dp row (was $before, then $shown)")
            scene.sendPointerEvent(PointerEventType.Exit, over)
            val gone = List(4) { lightOutside() }.last()
            assertTrue(gone <= before + 2, "leaving should clear the readout (shown $shown, left $gone, before $before)")
        } finally { scene.close() }
    }
}
