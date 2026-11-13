package local.ringleader.desktop

import kotlin.test.*

class BtKindRingsModelTest {
    @Test fun categoriesNeverUseTheReportedNameAsDirection() {
        assertEquals(KindSector.Audio, kindSector("audio-headphones"))
        assertEquals(KindSector.Input, kindSector("input-mouse"))
        assertEquals(KindSector.Personal, kindSector("computer-laptop"))
        assertEquals(KindSector.Other, kindSector("north bedroom"))
        assertEquals(KindSector.Other, kindSector(null))
        for (category in listOf("audio", "input", "phone", null)) {
            val angle = kindRingAngle("02:00:00:00:00:01",category) * 180 / Math.PI
            val start = -87 + kindSector(category).ordinal*90
            assertTrue(angle >= start && angle < start+84)
            assertEquals(angle,kindRingAngle("02:00:00:00:00:01",category)*180/Math.PI)
        }
    }
    @Test fun strongerSignalsMoveInwardWithoutClaimingMetres() {
        assertTrue(kindRingRadius(-45f) < kindRingRadius(-80f))
        assertEquals(1f,kindRingRadius(-120f))
        assertEquals(.08f,kindRingRadius(-10f))
    }
    @Test fun cachedHitsSelectTheNearestAndIgnoreEmptySpace() {
        val hits = FieldHits()
        assertNull(hits.nearest(0f,0f,12f))
        hits.update(arrayOf("first","second"),floatArrayOf(10f,20f),floatArrayOf(10f,10f))
        assertEquals("first",hits.nearest(11f,10f,12f))
        assertEquals("second",hits.nearest(19f,10f,12f))
        assertNull(hits.nearest(100f,100f,12f))
        hits.update(emptyArray(),floatArrayOf(),floatArrayOf())
        assertNull(hits.nearest(10f,10f,12f))
    }

    @Test fun showcaseCanvasLabelsNeverContainIdentity() {
        val device=local.ringleader.core.BtDevice("02:00:00:00:00:01","Secret room north",rssi=-52)
        assertEquals("-52 dBm",kindRingLabel(device,true))
        assertEquals("Secret room north  -52 dBm",kindRingLabel(device,false))
        assertEquals("",kindRingLabel(device.copy(rssi=null),true))
    }
    @Test fun simultaneousFixtureReallyContains120ReportingDevices() {
        val sim=BtSimulatedScan(120,simultaneous=true).apply { advanceTo(2000) }
        val first=sim.snapshot().devices
        assertEquals(120,first.size)
        assertTrue(first.all { it.rssi!=null && it.lastSeenMs!=null })
        sim.advanceTo(8000)
        assertTrue(sim.snapshot().devices.all { d->d.lastSeenMs!! > first.first { it.address==d.address }.lastSeenMs!! })
    }

    @Test fun simultaneousArrivalsKeepTheirRowsWhenSignalsChange() {
        val sim=BtSimulatedScan(120,simultaneous=true).apply { advanceTo(2000) }
        val first=sim.snapshot().devices
        sim.advanceTo(8000)
        val next=sim.snapshot().devices
        assertEquals(sortBtDevices(first,"heard",false).map { it.address },sortBtDevices(next,"heard",false).map { it.address })
        val bySignal=sortBtDevices(next,"signal",false)
        assertTrue(bySignal.zipWithNext().all { (a,b)->a.rssi!!>=b.rssi!! })
        assertEquals(bySignal.reversed(),sortBtDevices(next,"signal",true))
    }
}
