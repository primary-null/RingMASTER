package local.ringleader.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScannedPinPromptTest {
    private val addr = "https://pve.example:8006"
    private val fp = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"

    @Test fun requestDoesNotYieldAPin() {
        val prompt = ScannedPinPrompt()
        prompt.offer(addr, fp)
        assertNull(prompt.request())
        assertTrue(prompt.pending)
    }

    @Test fun confirmReturnsThePinOnce() {
        val prompt = ScannedPinPrompt()
        prompt.offer(addr, fp)
        prompt.request()
        assertEquals(fp, prompt.confirm())
        assertNull(prompt.confirm())
        assertFalse(prompt.pending)
    }

    @Test fun cancelDropsThePendingPin() {
        val prompt = ScannedPinPrompt()
        prompt.offer(addr, fp)
        prompt.request()
        prompt.cancel()
        assertFalse(prompt.pending)
        assertNull(prompt.confirm())
    }

    @Test fun newOfferDropsPendingRequest() {
        val prompt = ScannedPinPrompt()
        prompt.offer(addr, fp)
        prompt.request()
        assertTrue(prompt.pending)
        prompt.offer("https://other.example:8006", "FF:EE:DD")
        assertFalse(prompt.pending)
        assertNull(prompt.confirm())
    }

    @Test fun addressChangeClearsPending() {
        val prompt = ScannedPinPrompt()
        prompt.offer(addr, fp)
        prompt.request()
        assertTrue(prompt.pending)
        prompt.addressChanged("https://changed.example:8006")
        assertFalse(prompt.pending)
        assertNull(prompt.confirm())
    }

    @Test fun sameAddressDoesNotClear() {
        val prompt = ScannedPinPrompt()
        prompt.offer(addr, fp)
        prompt.request()
        prompt.addressChanged(addr)
        assertTrue(prompt.pending)
        assertEquals(fp, prompt.confirm())
    }

    @Test fun confirmWithoutRequestReturnsNull() {
        val prompt = ScannedPinPrompt()
        prompt.offer(addr, fp)
        assertNull(prompt.confirm())
    }

    @Test fun requestWithoutOfferDoesNothing() {
        val prompt = ScannedPinPrompt()
        assertNull(prompt.request())
        assertFalse(prompt.pending)
    }

    @Test fun addressChangedWithNoOfferDoesNothing() {
        val prompt = ScannedPinPrompt()
        prompt.addressChanged("https://edge.example:8006")
        assertFalse(prompt.pending)
    }
}
