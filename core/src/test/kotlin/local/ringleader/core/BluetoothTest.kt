package local.ringleader.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BluetoothTest {
    @Test fun smoothingFloorAppliesToTheBlend() {
        assertEquals(-80f, smoothBtRssi(null, null, -80, 0))
        // dt 0 used to bind coerceAtLeast to the exponent and jump most of the way. The floor is on the blend.
        val tight = smoothBtRssi(-80f, 0L, -40, 0)
        assertTrue(abs(tight - (-66f)) < 0.05f, "dt 0 should move 35 percent, was $tight")
        val settled = smoothBtRssi(-80f, 0L, -40, 10_000)
        assertTrue(abs(settled - (-40f)) < 0.05f, "a long gap should take the new reading, was $settled")
    }

    @Test fun smoothedRssiIsStoredOncePerSample() {
        val live = BtLiveScan(BtSource.Local, 10_000)
        live.sight(BtDevice("AA:BB:CC:DD:EE:01", rssi = -80), 0)
        live.sight(BtDevice("AA:BB:CC:DD:EE:01", rssi = -40), 0)
        val device = live.snapshot(0, false).devices.single()
        assertEquals(-40, device.rssi)
        assertTrue(abs((device.smoothedRssi ?: 0f) - (-66f)) < 0.05f)
        assertEquals(listOf(BtSample(0, -80), BtSample(0, -40)), device.samples)
    }

    @Test fun gateEmitsOnTheLeadingEdgeTheTrailingEdgeAndAFinalFlush() {
        val gate = LiveEmitGate(150)
        assertTrue(gate.onChange(0))
        assertFalse(gate.onChange(100))
        assertFalse(gate.onIdle(149))
        assertTrue(gate.onIdle(150))
        assertFalse(gate.onIdle(200))
        assertFalse(gate.onChange(200))
        assertTrue(gate.flush(210))
        assertFalse(gate.flush(210))
    }
}
