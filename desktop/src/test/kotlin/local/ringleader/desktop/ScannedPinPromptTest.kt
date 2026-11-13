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
}
