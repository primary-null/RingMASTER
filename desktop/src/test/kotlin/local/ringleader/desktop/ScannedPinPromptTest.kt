package local.ringleader.desktop

import kotlin.test.*

class ScannedPinPromptTest {
    @Test fun offerIsBoundToExactAddressPortAndKind() {
        val offer=ScannedPinOffer("https://example.test:8006","directPVE","ab".repeat(32))
        assertNotNull(offer.current("https://example.test:8006","directPVE"))
        assertNull(offer.current("https://other.test:8006","directPVE"))
        assertNull(offer.current("https://example.test:8443","directPVE"))
        assertNull(offer.current("https://example.test:8006","pdm"))
    }
    @Test fun cancelledOrEditedOfferCannotBeRestored() {
        val offer=ScannedPinOffer("https://example.test:8006","directPVE","ab".repeat(32))
        offer.invalidate()
        assertNull(offer.current("https://example.test:8006","directPVE"))
    }
    @Test fun staleOfferExpiresWithoutChangingTrust() {
        var now=0L
        val offer=ScannedPinOffer("https://example.test:8006","directPVE","ab".repeat(32)) { now }
        assertNotNull(offer.current("https://example.test:8006","directPVE"))
        now=300_000_000_001L
        assertNull(offer.current("https://example.test:8006","directPVE"))
    }
    /** The form is rebuilt on every page and session change; the age of the offer must come from the pick, not from the rebuild. */
    @Test fun anOfferRebuiltLaterKeepsTheAgeOfThePick() {
        var now=0L
        val pickedAt=now
        now=200_000_000_000L
        val rebuilt=ScannedPinOffer("https://example.test:8006","directPVE","ab".repeat(32), created=pickedAt) { now }
        assertNotNull(rebuilt.current("https://example.test:8006","directPVE")); assertFalse(rebuilt.expired())
        now=SCAN_OFFER_NANOS+1
        // Built again after the limit, as when the person comes back to the Desk: still the same pick, so no offer.
        val late=ScannedPinOffer("https://example.test:8006","directPVE","ab".repeat(32), created=pickedAt) { now }
        assertNull(late.current("https://example.test:8006","directPVE")); assertTrue(late.expired())
        // Nothing was on offer without a certificate, and a cancelled offer is cancelled, not expired.
        assertFalse(ScannedPinOffer("https://example.test:8006","directPVE",null, created=pickedAt) { now }.expired())
        late.invalidate(); assertFalse(late.expired())
    }
}
