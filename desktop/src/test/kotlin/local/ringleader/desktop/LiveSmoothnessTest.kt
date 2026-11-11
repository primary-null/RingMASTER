package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import local.ringleader.core.ResourceId
import local.ringleader.core.Sample
import local.ringleader.core.Series
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Live data must read as one calm motion (user report: "live data appears to be jumpy"). Three causes were found:
 * samples the server had not refreshed were drawn as steps, the chart scale snapped on a new peak, and the "top"
 * lists re-sorted on every reading.
 */
class LiveSmoothnessTest {
    private fun sample(second: Long, cpu: Double?, held: Boolean = false, mem: Long? = null) = Sample(Instant.ofEpochSecond(second), cpu, mem, 100, null, null, held)

    @Test fun aLineRunsStraightThroughSamplesTheServerHadNotRefreshed() {
        val values = listOf(.1, .1, .3, .3, .2, .2)
        val held = listOf(false, true, false, true, false, true)
        // The first and the newest sample are always points; a held one in between is not.
        assertEquals(listOf(0, 2, 4, 5), sparkPoints(values, held))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), sparkPoints(values, null))
        // After a gap the line starts at the first sample that exists, held or not.
        assertEquals(listOf(0, 3, 4), sparkPoints(listOf(.1, null, null, .2, .2), listOf(false, false, false, true, false)))
        assertEquals(emptyList(), sparkPoints(listOf(null, Double.NaN, -1.0), null))
    }

    @Test fun theSampleThatScrollsOffIsKeptForTheSlide() {
        assertEquals(.5, sparkLead(listOf(.5, .1, .2), listOf(.1, .2, .3)))
        assertNull(sparkLead(listOf(.5, .1), listOf(.5, .1, .2)), "a buffer that is still filling drops nothing")
        assertNull(sparkLead(listOf(.5, .1, .2), listOf(.5, .1, .2)), "nothing new, nothing scrolled off")
        assertNull(sparkLead(listOf(null, .1, .2), listOf(.1, .2, .3)))
    }

    @Test fun aRankingFollowsTheLastMinuteNotOneReading() {
        val series = Series(listOf(sample(0, .9), sample(10, .1), sample(20, .1), sample(25, .1, held = true), sample(30, .1), sample(40, .1), sample(50, .1), sample(60, .7)))
        // The newest six refreshed samples: .7, .1, .1, .1, .1, .1 (the held repeat and the old .9 are left out).
        assertEquals(1.2 / 6, steadyValue(series, null) { it.cpu }!!, 1e-9)
        assertEquals(.4, steadyValue(Series(), .4) { it.cpu })
        assertNull(steadyValue(null, null) { it.cpu })
    }

    @Test fun aTopListHoldsStillUntilAnotherEntryClearlyLeads() {
        fun id(n: Int) = ResourceId("https://pve.example.test:8006", "", "VM", n)
        val ranking = SteadyRanking<ResourceId>()
        assertEquals(listOf(id(1), id(2), id(3)), ranking.rank(mapOf(id(1) to .30, id(2) to .28, id(3) to .05)))
        // 2 edges ahead of 1 by a few percent: the order stands (it used to flip on every reading like this).
        assertEquals(listOf(id(1), id(2), id(3)), ranking.rank(mapOf(id(1) to .28, id(2) to .30, id(3) to .05)))
        assertEquals(listOf(id(1), id(2), id(3)), ranking.rank(mapOf(id(1) to .29, id(2) to .31, id(3) to .05)))
        // 2 clearly leads: it moves up, once.
        assertEquals(listOf(id(2), id(1), id(3)), ranking.rank(mapOf(id(1) to .20, id(2) to .40, id(3) to .05)))
        // Idle guests a fraction of a percent apart never trade places.
        assertEquals(listOf(id(2), id(1), id(3)), ranking.rank(mapOf(id(1) to .20, id(2) to .40, id(3) to .058)))
        // A new guest enters by its score; one that is gone leaves.
        assertEquals(listOf(id(4), id(2), id(3)), ranking.rank(mapOf(id(2) to .40, id(3) to .05, id(4) to .90)))
    }

    /** Row of the brightest pixel in one column: where the line is drawn there. */
    private fun lineRow(scene: ImageComposeScene, time: Long, column: Int): Int = scene.render(time).use { image ->
        org.jetbrains.skia.Bitmap().use { bitmap ->
            bitmap.allocPixels(image.imageInfo); image.readPixels(bitmap)
            (0 until image.height).maxBy { y -> val c = bitmap.getColor(column, y); (c shr 16 and 255) + (c shr 8 and 255) + (c and 255) }
        }
    }

    @Test fun aNewPeakEasesTheScaleInsteadOfSnappingTheWholeLine() {
        var values by mutableStateOf(List(30) { .10 })
        val scene = ImageComposeScene(300, 100, Density(1f)) {
            CompositionLocalProvider(LocalReduceMotion provides false) {
                Sparkline(values, Modifier.fillMaxSize().background(Color.Black), color = Color.White, grid = false, pulse = false, stamp = values.hashCode())
            }
        }
        try {
            var time = 0L
            fun frame() = run { time += 16_000_000L; lineRow(scene, time, 30) }
            repeat(4) { frame() }
            val before = frame()
            values = values.drop(1) + .80                        // a spike arrives at the right edge
            val rows = List(40) { frame() }                      // 640 ms: longer than the settle time
            val after = rows.last()
            assertTrue(after - before > 40, "the flat part should end much lower once the scale has grown (was $before, is $after)")
            val biggestStep = (listOf(before) + rows).zipWithNext { a, b -> abs(b - a) }.max()
            assertTrue(biggestStep < (after - before) * .5, "the line moved ${biggestStep}px of ${after - before}px in one frame: the scale snapped")
            assertTrue(rows.toSet().size >= 5, "the scale should pass through in-between positions, saw ${rows.toSet()}")
            assertEquals(after, frame(), "and then it rests")
        } finally { scene.close() }
    }

    @Test fun reducedMotionAppliesANewScaleAtOnce() {
        var values by mutableStateOf(List(30) { .10 })
        val scene = ImageComposeScene(300, 100, Density(1f)) {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                Sparkline(values, Modifier.fillMaxSize().background(Color.Black), color = Color.White, grid = false, pulse = false, stamp = values.hashCode())
            }
        }
        try {
            var time = 0L
            fun frame() = run { time += 16_000_000L; lineRow(scene, time, 30) }
            repeat(4) { frame() }
            values = values.drop(1) + .80
            frame()
            val settled = frame()
            assertEquals(settled, List(20) { frame() }.last())
        } finally { scene.close() }
    }
}
