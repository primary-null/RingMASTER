package local.ringleader.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.yield
import local.ringleader.core.BtDevice
import local.ringleader.core.BtLiveScan
import local.ringleader.core.BtScan
import local.ringleader.core.BtSource

/**
 * What a live Bluetooth scan asks of the page, stated without timing (the timing is BluetoothBenchTest's, opt-in).
 * The owner saw the dots and the drawing lag during a scan; these hold the fixes in place. Invented devices only.
 */
class BtScanViewWorkTest {
    private class Harness(var scan: BtScan?, val live: Boolean, reduce: Boolean = false, context: kotlin.coroutines.CoroutineContext) {
        var state by mutableStateOf(scan)
        var received by mutableLongStateOf(0L)
        var time = 0L
        val scene = ImageComposeScene(1600, 1000, Density(1f), coroutineContext = context) {
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides reduce) {
                Surface(Modifier.fillMaxSize(), color = Tech.Canvas) { BtScanView(state, scanning = live, modifier = Modifier.padding(16.dp), platform = "Test", receivedAt = received) }
            } }
        }
        suspend fun frame() { time += 16_000_000L; scene.render(time).close(); yield() }
        suspend fun frames(n: Int) = repeat(n) { frame() }
        fun update(next: BtScan) { state = next; received = time }
    }

    private fun harness(scan: BtScan?, live: Boolean, reduce: Boolean = false, block: suspend Harness.() -> Unit) {
        Appearance.preview(ThemeMode.Dark)
        runSceneTest {
            val h = Harness(scan, live, reduce, coroutineContext)
            try { h.block() } finally { h.scene.close() }
        }
    }

    @Test fun aLiveScanLaysOutNoTextAfterTheFirstFrames() {
        val sim = BtSimulatedScan(40).apply { advanceTo(4_000) }
        harness(sim.snapshot(), live = true) {
            frames(5)
            BtScanStats.reset()
            // 100 frames with a new snapshot every 12 of them: the field's rings, labels and the timeline are prepared once.
            repeat(100) { i ->
                if (i % 12 == 0) { sim.advanceTo(4_000L + i * 16L); update(sim.snapshot()) }
                frame()
            }
            assertEquals(0, BtScanStats.textMeasures, "text was laid out again during a live scan")
        }
    }

    @Test fun aScanUpdateThatChangesOneDeviceRecomposesOneRow() {
        val live = BtLiveScan(BtSource.Local, 20_000, Instant.parse("2026-11-12T09:30:00Z"))
        val devices = (0 until 10).map { BtDevice("02:1A:00:00:00:%02X".format(it), "Test device $it") }
        devices.forEachIndexed { i, d -> live.sight(d.copy(rssi = -50 - i * 3), 100L * i) }
        harness(live.snapshot(1_200, false), live = true) {
            frames(6)
            BtScanStats.reset()
            live.sight(devices[3].copy(rssi = -61), 1_300)
            update(live.snapshot(1_300, false))
            frames(6)
            assertEquals(1, BtScanStats.rowCompositions, "only the device with a new reading should recompose its row")
        }
    }

    @Test fun pointerMovesOverTheFieldDoNotRebuildThePositions() {
        val sim = BtSimulatedScan(120).apply { advanceTo(8_000) }
        harness(sim.snapshot(), live = true) {
            frames(5)
            BtScanStats.reset()
            // The field: 16 dp page padding, a header line, an 8 dp plate inset, a 364 dp square.
            repeat(60) { i ->
                val a = i * .2
                scene.sendPointerEvent(PointerEventType.Move, Offset((206 + cos(a) * 120).toFloat(), (228 + sin(a) * 120).toFloat()))
                frame()
            }
            assertEquals(0, BtScanStats.hitPositions, "a pointer move rebuilt the dot positions")
        }
    }

    @Test fun movingFramesReuseStaticFieldDrawing() {
        val sim = BtSimulatedScan(120).apply { advanceTo(8_000) }
        harness(sim.snapshot(), live = true) {
            frames(8)
            BtScanStats.reset()
            frames(100)
            assertEquals(0, BtScanStats.staticFieldCaches)
            assertEquals(0, BtScanStats.hitPositions)
            assertEquals(0, BtScanStats.textMeasures)
            assertEquals(0, BtScanStats.rowCompositions)
            assertTrue(BtScanStats.movingFieldDraws > 0)
        }
    }

    @Test fun aFinishedScanAsksForNoFrames() {
        val sim = BtSimulatedScan(40).apply { advanceTo(20_000) }
        harness(sim.snapshot(finished = true), live = false) {
            frames(8)
            assertFalse(scene.hasInvalidations(), "a finished scan should leave the page idle")
        }
    }

    @Test fun reduceMotionHoldsTheClockBetweenUpdates() {
        val sim = BtSimulatedScan(40).apply { advanceTo(4_000) }
        harness(sim.snapshot(), live = true, reduce = true) {
            frames(8)
            assertFalse(scene.hasInvalidations(), "under Reduce motion a live scan moves only when a reading arrives")
        }
        // Without Reduce motion the same scan runs on the frame clock between updates.
        harness(sim.snapshot(), live = true) {
            frames(8)
            assertTrue(scene.hasInvalidations(), "a live scan should animate between updates")
        }
    }
}
