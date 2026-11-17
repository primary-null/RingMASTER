package local.ringleader.desktop

import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import local.ringleader.core.AdapterHardware
import local.ringleader.core.Guest
import local.ringleader.core.GuestAgentFailure
import local.ringleader.core.GuestInspection
import local.ringleader.core.GuestRadio
import local.ringleader.core.GuestRadioKind
import local.ringleader.core.GuestRefusal
import local.ringleader.core.InspectionSection
import local.ringleader.core.NoVault
import local.ringleader.core.RadioBlock
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState
import local.ringleader.core.guesthelper.GuestHelperFailure
import local.ringleader.core.guesthelper.GuestHelperException
import local.ringleader.core.guesthelper.GuestHelperLink
import local.ringleader.core.guesthelper.GuestHelperPairings
import local.ringleader.core.guesthelper.GuestHelperPlace
import local.ringleader.core.guesthelper.GuestHelperStore
import local.ringleader.core.guesthelper.guestHelperPlace
import local.ringleader.core.integrations.GuestRef
import local.ringleader.core.integrations.ServiceWire
import kotlin.test.*

/** The Engine in the app's screens: the offer on refusals, the state of a guest's radios, and the small helpers. */
class EngineUiTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wire = ServiceWire("RingMASTER/0.0.0-test")
    private val hub = EngineHub(GuestHelperPairings(GuestHelperStore(null), NoVault("No safe in tests."), wire), wire, { emptyList() })
    private val ct = Guest("https://192.0.2.1:8006", 991, "lab-ct", "node-a", "Container", "running")
    private val stopped = Guest("https://192.0.2.1:8006", 992, "lab-off", "node-a", "VM", "stopped")

    @AfterTest fun stop() { scope.cancel() }

    private fun guests(): LiveGuestRadios {
        val workspace = Workspace(scope)
        return LiveGuestRadios(LiveRadiosFeed(scope, workspace, null), workspace, hub)
    }

    private fun pair(guest: Guest) {
        val link = GuestHelperLink("id-${guest.id}", guestHelperPlace(guest), "192.0.2.7", 8696, "a".repeat(64), "0.1.0", "", Instant.EPOCH, null)
        hub.pairings.save(link, "AAAAA-BBBBB-CCCCC-DDDDD-EEEEE".toCharArray()).getOrThrow()
        hub.changed()
    }

    @Test fun aRefusalTheEngineWouldGetAroundOffersToSetItUp() {
        val note = guests().probeRefusal(ct)!!
        assertEquals(RADIOS_CT_PROBE, note.text)
        assertEquals(ENGINE_SETUP_LABEL, note.action?.label)
        note.action!!.run()
        assertEquals(ct, hub.setup.value, "the action opens the setup for that guest")
    }

    @Test fun aRefusalItCannotHelpWithOffersNothing() {
        val note = guests().probeRefusal(stopped)!!
        assertEquals(GuestRefusal.NotRunning.sentence, note.text)
        assertNull(note.action)
    }

    @Test fun onceAnEngineIsPairedTheButtonsSimplyWork() {
        pair(ct)
        val radios = guests()
        assertNull(radios.probeRefusal(ct))
        assertTrue(radios.view(ct, null, RadiosBoard()).throughEngine)
        assertNull(radios.view(ct, null, RadiosBoard()).probeRefusal)
    }

    @Test fun aGuestsRadiosHaveTheirStateByTheSharedRule() {
        val bt = GuestRadio(GuestRadioKind.Bluetooth, "hci0", "", powered = true, block = RadioBlock.None)
        assertEquals(RadioState.Neutral to "On and ready.", guestRadioNow(bt))
        assertEquals(RadioState.Inactive to "Bluetooth is turned off in the guest.", guestRadioNow(bt.copy(powered = false)))
        assertEquals(RadioState.Disabled, guestRadioNow(bt.copy(block = RadioBlock.Software)).first)
        assertEquals("The system did not say whether it is on.", guestRadioNow(bt.copy(powered = null)).second)
        val wifi = GuestRadio(GuestRadioKind.WiFi, "wlan0", "", powered = true, block = RadioBlock.None, connected = true,
            hardware = AdapterHardware(driver = "iwlwifi"))
        assertEquals(RadioState.Active to "Connected.", guestRadioNow(wifi))
        assertEquals(RadioState.Neutral, guestRadioNow(wifi.copy(hasInterface = false)).first)
    }

    @Test fun aPlaceReadsAsTheUserKnowsIt() {
        assertEquals("VM 112 on 192.0.2.1:8006", enginePlaceLabel(GuestHelperPlace.Guest(GuestRef("VM", 112), "https://192.0.2.1:8006")))
        assertEquals("Container 991", enginePlaceLabel(GuestHelperPlace.Guest(GuestRef("Container", 991))))
        assertEquals("Node pve-a", enginePlaceLabel(GuestHelperPlace.Node("pve-a")))
    }

    @Test fun theGuestsAddressIsTakenBareFromTheInventory() {
        val shown = listOf("fd00::7/64", "192.0.2.7/24 (Tailscale)")
        val inspection = GuestInspection(ct.resource, "node-a", "hour", InspectionSection(), InspectionSection(), InspectionSection(),
            InspectionSection(), InspectionSection(shown))
        assertEquals("192.0.2.7", guestAddress(WorkspaceState(inspection = inspection), ct))
        assertNull(guestAddress(WorkspaceState(), ct))
    }

    @Test fun everyFailureBecomesASentence() {
        assertEquals(GuestRefusal.NoPermission.sentence, setupFailure(GuestAgentFailure(GuestRefusal.NoPermission)))
        assertEquals("Busy.", setupFailure(GuestHelperException(GuestHelperFailure.Busy, "Busy.")))
        assertEquals("The setup step failed.", setupFailure(IllegalStateException()))
    }

    @Test fun theUserAgentVersionKeepsOnlyWhatTheWireAllows() {
        assertEquals("0.26.0", GuestEngine.userAgentVersion("0.26.0"))
        assertEquals("0.26.0dev", GuestEngine.userAgentVersion("0.26.0 (dev)"))
        assertEquals("0", GuestEngine.userAgentVersion("  "))
    }
}
