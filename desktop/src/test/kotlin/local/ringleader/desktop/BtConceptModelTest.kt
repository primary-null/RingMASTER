package local.ringleader.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import local.ringleader.core.BtDevice

class BtConceptModelTest {
    @Test fun thresholdsAreSignalBandsRatherThanDistances() {
        assertEquals(BtBand.Near, bandOf(-60f))
        assertEquals(BtBand.Mid, bandOf(-60.1f))
        assertEquals(BtBand.Mid, bandOf(-78f))
        assertEquals(BtBand.Far, bandOf(-78.1f))
    }

    @Test fun countsSeparateUnheardNewAndQuietDevices() {
        val facts = HeardFacts(listOf(
            BtDevice("02:00:00:00:00:01", "New", -40, firstSeenMs = 7_000, lastSeenMs = 8_000),
            BtDevice("02:00:00:00:00:02", "Quiet", -50, firstSeenMs = 100, lastSeenMs = 1_000),
            BtDevice("02:00:00:00:00:03", "Mid", -70, firstSeenMs = 100, lastSeenMs = 7_900),
            BtDevice("02:00:00:00:00:04", "Unheard", paired = true)
        ), 8_000)
        assertEquals(3, facts.heard.size)
        assertEquals(1, facts.near)
        assertEquals(1, facts.fresh)
        assertEquals(1, facts.quiet)
        assertTrue(facts.heard.first().fresh)
        assertFalse(facts.heard.last().quiet)
    }
}
