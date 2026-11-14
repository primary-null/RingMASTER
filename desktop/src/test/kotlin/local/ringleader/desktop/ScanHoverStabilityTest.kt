package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import local.ringleader.core.*
import org.jetbrains.skia.Image
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The Scan page must not move when the pointer moves over it. It used to insert a line above the grid for the
 * hovered host and scroll its card into view, so cards, rows and dots shifted under the pointer and the page jumped
 * with every mouse move (measured on the installed 0.21.6: nine 20 px jumps in one slow sweep down the page).
 */
class ScanHoverStabilityTest {
    private val hits = (1..5).map { n -> ScanHit("192.0.2.${n + 10}", 8006, true, if (n == 3) ScanKind.PDM else ScanKind.PVE, "Proxmox", "AB:".repeat(31) + "CD", "CN=pve$n.example.test", n == 1) } +
        (1..8).map { n -> ScanHit("192.0.2.${n * 20}", if (n % 2 == 0) 443 else 80, n % 2 == 0, if (n == 3) ScanKind.HOME_ASSISTANT else ScanKind.WEB, "Service $n") }
    private val plan = NetworkPlan(listOf(ScanNetwork(ScanInterface("Ethernet", "192.0.2.2", 24), "192.0.2.0/24", null, (1..254).map { "192.0.2.$it" })))

    @Suppress("UNCHECKED_CAST")
    private fun session(): ScanSession = ScanSession(CoroutineScope(Dispatchers.Unconfined)).also { s ->
        s.javaClass.getDeclaredField("progress\$delegate").apply { isAccessible = true }.get(s).let { (it as MutableState<NetworkProgress?>).value = NetworkProgress(plan, 254, hits = hits, finished = true) }
    }

    /** Row-average brightness of the middle of the frame: a profile that moves when the layout moves, whatever is highlighted. */
    private fun profile(image: Image): IntArray = org.jetbrains.skia.Bitmap().use { bitmap ->
        bitmap.allocPixels(image.imageInfo); image.readPixels(bitmap)
        IntArray(image.height) { y -> var sum = 0; var n = 0; var x = image.width / 4; while (x < image.width * 3 / 4) { val c = bitmap.getColor(x, y); sum += (c shr 16 and 255) + (c shr 8 and 255) + (c and 255); n++; x += 3 }; sum / n }
    }
    /** The vertical shift, in pixels, that best maps profile [a] onto [b]; 0 when the layout did not move. */
    private fun shift(a: IntArray, b: IntArray): Int = (-40..40).minBy { d -> var total = 0; for (y in 40 until a.size - 40) total += abs(a[y] - b[y + d]); total + abs(d) }

    private fun sweep(width: Int, height: Int): List<Int> {
        val session = session()
        val scene = ImageComposeScene(width, height, Density(1f)) {
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                Box(Modifier.fillMaxSize().background(Tech.Canvas).padding(16.dp)) { ScanScreen(session, emptyList()) {} }
            } }
        }
        try {
            var time = 0L
            // Every rendered frame is freed at once: a few hundred unfreed 5 MB frames ran an 8 GB build machine out of memory.
            fun frame(): IntArray { time += 16_000_000L; return scene.render(time).use { profile(it) } }
            fun settle() { time += 16_000_000L; scene.render(time).close() }
            var last = frame()
            val shifts = mutableListOf<Int>()
            // Down the middle of the left column (cards, the gaps between them, then service rows), then along the grid.
            val path = (60 until height - 20 step 9).map { Offset(width * .22f, it.toFloat()) } + (20 until width - 20 step 14).map { Offset(it.toFloat(), 120f) }
            for (point in path) {
                scene.sendPointerEvent(PointerEventType.Move, point)
                settle(); val now = frame()
                shifts += shift(last, now); last = now
            }
            return shifts
        } finally { scene.close() }
    }

    @Test fun movingThePointerNeverMovesThePage() {
        for ((w, h) in listOf(1400 to 900, 960 to 640)) {
            val shifts = sweep(w, h)
            assertEquals(emptyList(), shifts.filter { it != 0 }, "the page moved while the pointer crossed it at ${w}x$h")
        }
    }

    @Test fun gridDotsAreFoundByDistanceInBothDirections() {
        val width = 1280f; val height = 64f
        val hit = hits.first()                                   // 192.0.2.11 -> column 11, row 0
        val centre = Offset((11 + .5f) * width / 64, .5f * height / 4)
        assertEquals(hit, gridHitAt(hits, centre, width, height, 12f))
        assertEquals(hit, gridHitAt(hits, centre + Offset(8f, 6f), width, height, 12f))
        // Same column, three rows down: the old test looked at the horizontal distance only and still matched.
        assertNotEquals(hit, gridHitAt(hits, centre + Offset(0f, 48f), width, height, 12f))
        assertNull(gridHitAt(hits, Offset(width - 3f, height - 3f), width, height, 12f))
        assertNull(gridHitAt(hits, null, width, height, 12f)); assertNull(gridHitAt(emptyList(), centre, width, height, 12f))
    }
}
