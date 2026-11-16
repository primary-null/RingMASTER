package local.ringleader.core

import java.time.Duration
import java.time.Instant
import kotlin.test.*

/** When the tray is open: driven by a clock the test hands in. */
class TickerPresenceTest {
    private val t0: Instant = Instant.parse("2026-11-16T10:00:00Z")
    private fun at(seconds: Long) = t0.plusSeconds(seconds)

    @Test fun automaticIsClosedUntilACueOpensItForAWhile() {
        val p = TickerPresence(Duration.ofSeconds(7), Duration.ofSeconds(3))
        assertFalse(p.expanded(TickerMode.Automatic, at(0)))
        p.cue(at(10))
        assertTrue(p.expanded(TickerMode.Automatic, at(10))); assertTrue(p.expanded(TickerMode.Automatic, at(16)))
        assertFalse(p.expanded(TickerMode.Automatic, at(17)), "settles back after seven seconds")
        assertEquals(at(17), TickerPresence().apply { cue(at(10)) }.settlesAt(TickerMode.Automatic, at(11)))
    }

    @Test fun aLaterCueExtendsAndNeverShortens() {
        val p = TickerPresence(Duration.ofSeconds(7), Duration.ofSeconds(3))
        p.cue(at(0)); p.cue(at(5))
        assertTrue(p.expanded(TickerMode.Automatic, at(11))); assertFalse(p.expanded(TickerMode.Automatic, at(12)))
        p.cue(at(1))
        assertTrue(p.expanded(TickerMode.Automatic, at(11)), "an older cue cannot shorten it")
    }

    @Test fun holdingKeepsItOpenAndLingersAfterLettingGo() {
        val p = TickerPresence(Duration.ofSeconds(7), Duration.ofSeconds(3))
        p.cue(at(0))
        p.hold(true, at(5))
        assertTrue(p.expanded(TickerMode.Automatic, at(60)), "hover or focus keeps it open however long")
        assertNull(p.settlesAt(TickerMode.Automatic, at(60)))
        p.hold(false, at(60))
        assertTrue(p.expanded(TickerMode.Automatic, at(62))); assertFalse(p.expanded(TickerMode.Automatic, at(63)))
        // Holding without having been opened opens it too (focus arrives on a collapsed tray).
        val q = TickerPresence(); q.hold(true, at(0)); assertTrue(q.expanded(TickerMode.Automatic, at(1)))
    }

    @Test fun pinKeepsItOpenUntilUnpinned() {
        val p = TickerPresence()
        p.pinned = true
        assertTrue(p.expanded(TickerMode.Automatic, at(1000)))
        p.pinned = false
        assertFalse(p.expanded(TickerMode.Automatic, at(1000)))
    }

    @Test fun alwaysIsOpenAndOffIsNeverShown() {
        val p = TickerPresence()
        assertTrue(p.expanded(TickerMode.Always, at(0)))
        assertTrue(p.visible(TickerMode.Always, hasTiles = true)); assertFalse(p.visible(TickerMode.Always, hasTiles = false))
        p.pinned = true; p.cue(at(0))
        assertFalse(p.expanded(TickerMode.Off, at(0))); assertFalse(p.visible(TickerMode.Off, hasTiles = true))
        assertNull(p.settlesAt(TickerMode.Always, at(0)))
    }

    @Test fun dismissingHidesItForTheSessionUntilRestored() {
        val p = TickerPresence()
        p.pinned = true; p.cue(at(0)); p.dismiss()
        assertTrue(p.dismissed); assertFalse(p.pinned)
        assertFalse(p.visible(TickerMode.Automatic, hasTiles = true)); assertFalse(p.expanded(TickerMode.Always, at(1)))
        p.cue(at(2))
        assertFalse(p.expanded(TickerMode.Automatic, at(3)), "cues do not bring it back")
        p.restore()
        assertTrue(p.visible(TickerMode.Automatic, hasTiles = true)); assertFalse(p.expanded(TickerMode.Automatic, at(4)))
    }

    @Test fun escapeCollapsesUntilSomethingOpensItAgain() {
        val p = TickerPresence(Duration.ofSeconds(7), Duration.ofSeconds(3))
        p.pinned = true; p.cue(at(0)); p.hold(true, at(1))
        p.collapse()
        assertFalse(p.pinned); assertFalse(p.expanded(TickerMode.Automatic, at(2))); assertFalse(p.expanded(TickerMode.Always, at(2)))
        assertNull(p.settlesAt(TickerMode.Automatic, at(2)))
        p.cue(at(10)); assertTrue(p.expanded(TickerMode.Automatic, at(11)))
        p.collapse(); p.hold(true, at(12)); assertTrue(p.expanded(TickerMode.Always, at(13)), "hovering opens it again")
        p.hold(false, at(14)); p.collapse(); p.open(at(20)); assertTrue(p.expanded(TickerMode.Automatic, at(21)))
    }

    @Test fun theModeIsStoredAsANumberAndUnknownsAreAutomatic() {
        TickerMode.entries.forEach { assertEquals(it, TickerMode.fromStored(it.stored)) }
        assertEquals(TickerMode.Automatic, TickerMode.fromStored(null)); assertEquals(TickerMode.Automatic, TickerMode.fromStored(99))
        assertEquals(listOf("Automatic", "Always open", "Off"), TickerMode.entries.map { it.label })
    }
}
