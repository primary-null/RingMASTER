package local.ringleader.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import org.jetbrains.skia.Bitmap
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Bluetooth and Wi-Fi marks at every size the screens use, at each display scale: each stays inside its box (the old
 * Wi-Fi arcs spilled over their neighbours), inks enough of it to be seen beside bold text, and a weak signal keeps the
 * outline of a full one. RINGMASTER_ICON_SHEET=<folder> also saves a sheet to look at.
 */
class RadioMarksTest {
    private val sizes = listOf(10, 11, 12, 13, 14, 16, 18, 20, 24, 32, 64)
    private val scales = listOf(1f, 1.25f, 1.5f, 2f)
    private val margin = 6
    private val ink = Color.White
    private val ground = Color.Black

    /** Draws [mark] in a [dp] box at [scale] with a margin round it; returns pixels brighter than [floor] inside the box and outside it. */
    private fun draw(dp: Int, scale: Float, floor: Int = 40, mark: DrawScope.() -> Unit): Pair<Int, Int> {
        val box = (dp * scale).roundToInt()
        val side = box + 2 * (margin * scale).roundToInt()
        val scene = ImageComposeScene(side, side, Density(scale)) {
            Box(Modifier.fillMaxSize().background(ground).padding(margin.dp)) { Canvas(Modifier.size(dp.dp)) { mark() } }
        }
        try {
            scene.render(0).close()
            scene.render(16_000_000).use { image ->
                val bitmap = Bitmap.makeFromImage(image)
                val edge = (margin * scale).roundToInt()
                var inside = 0; var outside = 0
                for (y in 0 until side) for (x in 0 until side) {
                    val lit = (bitmap.getColor(x, y) and 0xFF) > floor
                    if (!lit) continue
                    // One pixel of slack: a round stroke end may shade the pixel beside the box.
                    if (x in edge - 1 until edge + box + 1 && y in edge - 1 until edge + box + 1) inside++ else outside++
                }
                return inside to outside
            }
        } finally { scene.close() }
    }

    @Test fun bothMarksStayInsideTheirBoxAndCarryWeight() = runSceneTest {
        for (scale in scales) for (dp in sizes) {
            val box = (dp * scale).roundToInt()
            for ((name, mark) in listOf<Pair<String, DrawScope.() -> Unit>>("Bluetooth" to { bluetoothMark(ink) }, "Wi-Fi" to { wifiMark(ink) })) {
                val (inside, outside) = draw(dp, scale, mark = mark)
                assertEquals(0, outside, "$name at $dp dp x$scale draws outside its box")
                val share = inside * 100 / (box * box)
                assertTrue(share >= 12, "$name at $dp dp x$scale inks only $share % of its box")
            }
        }
    }

    @Test fun aWeakSignalKeepsTheOutlineOfAFullOne() = runSceneTest {
        for (dp in sizes) {
            val full = draw(dp, 1f, floor = 8) { wifiMark(ink) }.first
            for (bars in 0..2) {
                // A dimmed band loses only the faintest pixels of its soft edge.
                val weak = draw(dp, 1f, floor = 8) { wifiMark(ink, bars) }.first
                assertTrue(weak * 100 >= full * 80 && weak <= full, "the same shape is drawn at $dp dp with $bars bars, only dimmer ($weak of $full pixels)")
            }
        }
    }

    @Test fun sheet() = runSceneTest {
        val folder = System.getenv("RINGMASTER_ICON_SHEET") ?: return@runSceneTest
        File(folder).mkdirs()
        for ((name, mode) in listOf("dark" to ThemeMode.Dark, "light" to ThemeMode.Light)) for (scale in listOf(1f, 1.5f)) {
            Appearance.preview(mode)
            val scene = ImageComposeScene((1180 * scale).toInt(), (230 * scale).toInt(), Density(scale)) {
                Column(Modifier.fillMaxSize().background(Tech.Canvas).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (kind in RadioKind.entries) Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (s in sizes) RadioGlyph(kind, size = s.dp)
                        RadioKindChip(kind)
                        for (state in RadioState.entries) {
                            RadioBadge(kind, size = 30.dp, state = state)
                            RadioGlyph(kind, size = 14.dp, state = state)
                            RadioStateTag(state)
                        }
                    }
                }
            }
            try {
                scene.render(0).close()
                val file = File(folder, "marks-$name-${(scale * 100).toInt()}.png")
                scene.render(16_000_000).use { image -> image.encodeToData()!!.use { file.writeBytes(it.bytes) } }
            } finally { scene.close() }
        }
    }

    @Test fun eachStateIsDrawnDifferentlyNotOnlyTinted() = runSceneTest {
        // The same ink for every state: what differs here is the drawing itself.
        for (dp in listOf(16, 20, 26, 32)) for ((name, mark) in listOf<Pair<String, DrawScope.(RadioState) -> Unit>>(
            "Bluetooth" to { bluetoothMark(ink, it) }, "Wi-Fi" to { wifiMark(ink, 3, it) })) {
            fun count(state: RadioState) = draw(dp, 1f, floor = 8) { mark(state) }.first
            val ready = count(RadioState.Neutral)
            assertEquals(ready, count(RadioState.Inactive), "$name at $dp dp: ready and inactive share a drawing and differ by ink")
            assertTrue(count(RadioState.Disabled) > ready, "$name at $dp dp: a disabled mark is struck through")
            if (dp >= 20) assertTrue(count(RadioState.Active) > ready, "$name at $dp dp: only an active mark is filled")
        }
        for (dp in listOf(10, 12, 14)) {
            fun bluetooth(state: RadioState) = draw(dp, 1f) { bluetoothMark(ink, state) }.first
            fun wifi(state: RadioState) = draw(dp, 1f) { wifiMark(ink, 3, state) }.first
            assertTrue(bluetooth(RadioState.Disabled) > bluetooth(RadioState.Neutral), "Bluetooth is struck through at $dp dp too")
            assertTrue(wifi(RadioState.Disabled) > wifi(RadioState.Neutral), "Wi-Fi is struck through at $dp dp too")
        }
    }

    @Test fun anActiveMarkMovesWithThePulseAndTheOthersDoNot() = runSceneTest {
        fun lit(state: RadioState, pulse: Float?) = draw(26, 1f, floor = 150) { wifiMark(ink, 3, state, pulse) }.first
        assertTrue(lit(RadioState.Active, .1f) != lit(RadioState.Active, .6f), "the bands of an active mark light in turn")
        for (state in listOf(RadioState.Neutral, RadioState.Inactive, RadioState.Disabled)) assertEquals(lit(state, .1f), lit(state, .6f), "$state stays still")
    }
}
