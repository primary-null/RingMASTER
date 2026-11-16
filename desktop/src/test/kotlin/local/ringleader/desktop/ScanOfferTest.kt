@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import kotlin.test.*
import kotlinx.coroutines.*
import local.ringleader.core.*

/** The scanned certificate's offer on the Connect form: what it says about its age, when it lapses, and when it is over for good. */
class ScanOfferTest {
    private val hit = ScanHit("pve.example.test", 8006, true, ScanKind.PVE, fingerprint = "ab".repeat(32))
    private val pdm = ScanHit("pdm.example.test", 8443, true, ScanKind.PDM, fingerprint = "cd".repeat(32))
    private val seconds = 1_000_000_000L

    @Test fun ageAndWhatIsLeftAreSaidInWords() {
        for ((age, text) in listOf(0L to "just now", 4_900_000_000L to "just now", 5 * seconds to "5 s ago", 59 * seconds to "59 s ago", 60 * seconds to "1 min ago",
            119 * seconds to "1 min ago", 120 * seconds to "2 min ago", 299 * seconds to "4 min ago", 3600 * seconds to "60 min ago")) assertEquals(text, offerAgeText(age), "$age")
        for ((left, text) in listOf(300 * seconds to "5:00", 299_100_000_000L to "5:00", 192 * seconds to "3:12", 191_000_000_001L to "3:12", 7 * seconds to "0:07", 1L to "0:01", 0L to "0:00", -5 * seconds to "0:00"))
            assertEquals(text, offerRemainingText(left), "$left")
    }

    @Test fun anOfferKnowsItsAgeAndWhatIsLeftAndWhenItIsLive() {
        var now = 10 * seconds
        val offer = ScannedPinOffer("https://a.example.test:8006", "directPVE", "ab".repeat(32), created = 10 * seconds) { now }
        assertTrue(offer.live()); assertEquals(0, offer.ageNanos()); assertEquals(SCAN_OFFER_NANOS, offer.remainingNanos())
        now += 120 * seconds
        assertTrue(offer.live()); assertEquals(120 * seconds, offer.ageNanos()); assertEquals(180 * seconds, offer.remainingNanos())
        now += 181 * seconds
        assertFalse(offer.live()); assertTrue(offer.expired()); assertTrue(offer.remainingNanos() < 0)
        // A cancelled offer is not live, and neither is one with no certificate to offer.
        now = 10 * seconds
        val cancelled = ScannedPinOffer("https://a.example.test:8006", "directPVE", "ab".repeat(32), created = 10 * seconds) { now }.also { it.invalidate() }
        assertFalse(cancelled.live()); assertFalse(ScannedPinOffer("https://a.example.test:8006", "directPVE", null, created = 10 * seconds) { now }.live())
        // The clock is the window's shared one unless given another.
        val before = ScanOfferClock.now
        try { ScanOfferClock.now = { 77 * seconds }; assertEquals(0, ScannedPinOffer("a", "b", "c").ageNanos()); assertEquals(77 * seconds, ScannedPinOffer("a", "b", "c", created = 0).ageNanos()) } finally { ScanOfferClock.now = before }
    }

    @Test fun thePickGoesIntoTheFormAtOnceAndNothingTypedLaterIsReplaced() {
        val blank = ManualForm()
        assertEquals(ManualForm("https://pve.example.test:8006", "", "", DIRECT_PVE_KIND), scanFormStart(blank, hit))
        assertEquals(PDM_KIND, scanFormStart(blank, pdm).kind)
        // A form that holds an address keeps it (the person typed it), and with no pick nothing changes.
        val typed = ManualForm("https://typed.example.test:8006", "root@pam!t", "", PDM_KIND)
        assertEquals(typed, scanFormStart(typed, hit)); assertEquals(typed, scanFormStart(typed, null)); assertEquals(blank, scanFormStart(blank, null))
        // A pick made on the Scan page replaces what was in the form (it was asked for) and keeps the account.
        val root = java.nio.file.Files.createTempDirectory("scan-pick")
        val workspace = Workspace(CoroutineScope(Dispatchers.Unconfined + SupervisorJob()))
        val desk = ProfileDesk(workspace, ProfileStore(root.resolve("profiles.json")))
        try {
            desk.manualForm = ManualForm("https://old.example.test:8006", "root@pam!old", "root@pam", DIRECT_PVE_KIND)
            applyScanPick(desk, pdm)
            assertEquals(ManualForm("https://pdm.example.test:8443", "root@pam!old", "root@pam", PDM_KIND), desk.manualForm)
        } finally { desk.close(); workspace.close(); root.toFile().deleteRecursively() }
    }

    private suspend fun withClock(block: suspend (setSeconds: (Long) -> Unit) -> Unit) {
        var nanos = 5_000_000_000_000L
        val before = ScanOfferClock.now; val tick = ScanOfferClock.tickMillis
        ScanOfferClock.now = { nanos }; ScanOfferClock.tickMillis = 20
        try { block { s -> nanos = 5_000_000_000_000L + s * seconds } } finally { ScanOfferClock.now = before; ScanOfferClock.tickMillis = tick }
    }

    @Test fun theFormShowsHowOldTheScanIsAndWhenTheOfferLapsesAndCountsDown() = runSceneTest {
        withClock { setSeconds ->
            val rig = ScanOfferReentryAuditTest.Rig(this, coroutineContext)
            try {
                rig.pick(hit); rig.ui.frames()
                assertTrue(rig.has("Scanned just now. This offer lapses in 5:00."), rig.texts().toString())
                setSeconds(125)
                rig.until("the countdown to move") { rig.has("Scanned 2 min ago. This offer lapses in 2:55.") }
                setSeconds(299)
                rig.until("the last seconds") { rig.has("Scanned 4 min ago. This offer lapses in 0:01.") }
                assertTrue(rig.has("Trust and connect"), "still on offer a second before it lapses")
                setSeconds(301)
                rig.until("the lapse") { !rig.has("Trust and connect") }
                assertTrue(rig.texts().none { it.startsWith("Scanned ") && "lapses" in it }, "no countdown for an offer that is over")
            } finally { rig.close() }
        }
    }

    @Test fun anOfferTimedFromAnEarlierPickLapsesWhenTheDeskIsEnteredAfterwards() = runSceneTest {
        withClock { setSeconds ->
            val rig = ScanOfferReentryAuditTest.Rig(this, coroutineContext)
            try {
                rig.pick(hit); setSeconds(400)                    // the pick was made on the Scan page and the Desk is entered 400 s later
                rig.ui.frames()
                assertFalse(rig.has("Trust and connect")); assertTrue(rig.has("The scanned certificate is more than five minutes old. Check the server certificate again before trusting it."))
                assertTrue(rig.texts().none { it.startsWith("Scanned ") && "lapses" in it })
            } finally { rig.close() }
        }
    }

    @Test fun changingTheKindEndsTheOfferToo() = runSceneTest {
        withClock {
            val rig = ScanOfferReentryAuditTest.Rig(this, coroutineContext)
            try {
                rig.pick(hit); rig.ui.frames(); rig.ui.text("Trust and connect")
                rig.ui.click("PDM")
                assertEquals(1, rig.ended); assertFalse(rig.has("Trust and connect")); assertNull(rig.pick)
                rig.leaveAndReturn(); assertFalse(rig.has("Trust and connect"))
            } finally { rig.close() }
        }
    }

    @Test fun showcaseDoesNotChangeWhatTheOfferSays() = runSceneTest {
        withClock {
            Appearance.preview(false, showcase = true)
            val rig = ScanOfferReentryAuditTest.Rig(this, coroutineContext)
            try {
                rig.pick(hit); rig.ui.frames()
                assertTrue(rig.has("Scanned just now. This offer lapses in 5:00."), "the age of a scan is not an identity")
                assertTrue(rig.has("Scanned certificate · Hidden server"), rig.texts().toString())
            } finally { Appearance.preview(false); rig.close() }
        }
    }
}
