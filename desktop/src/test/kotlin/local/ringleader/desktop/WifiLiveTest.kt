package local.ringleader.desktop

import java.time.Instant
import local.ringleader.core.AdapterHardware
import local.ringleader.core.BtSource
import local.ringleader.core.GuestRadio
import local.ringleader.core.GuestRadioKind
import local.ringleader.core.GuestRadioProbe
import local.ringleader.core.RadioBlock
import local.ringleader.core.WifiAdapter
import local.ringleader.core.WifiIssue
import local.ringleader.core.WifiLink
import local.ringleader.core.WifiScan
import local.ringleader.core.sampleWifiScan
import local.ringleader.core.wifiIssueScan
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The mapping from what core scans of Wi-Fi to the Wi-Fi view, the board's own-computer cards and the guest tab. */
class WifiLiveTest {
    private val at = Instant.parse("2026-11-16T10:00:00Z")
    private val sources = listOf(WifiSourceView(THIS_COMPUTER_KEY, THIS_COMPUTER_LABEL))
    private val card = AdapterHardware(
        "iwlwifi", "pci", pciAddress = "0000:03:00.0", pciVendorId = 0x8086, pciDeviceId = 0x2725,
        product = "Sample Wi-Fi 6E AX210", maker = "Sample Maker",
    )

    private fun adapter(powered: Boolean? = true, block: RadioBlock = RadioBlock.None, hasInterface: Boolean = true) =
        WifiAdapter("wlan0", "Sample AX210", "02:00:00:00:00:21", powered, block, card, hasInterface = hasInterface)

    @Test fun aLocalAdapterIsActiveReadyInactiveOrDisabledByRule() {
        val linked = WifiLink("wlan0", connected = true, ssid = "Harbour Lab", channel = 44)
        assertEquals(RadioState.Active, localWifiNow(adapter(), linked).first)
        assertEquals("Connected on channel 44.", localWifiNow(adapter(), linked).second)
        assertFalse("Harbour" in localWifiNow(adapter(), linked).second, "the sentence never names the network")
        assertEquals(RadioState.Neutral, localWifiNow(adapter(), WifiLink("wlan0", connected = false)).first)
        assertEquals(RadioState.Neutral, localWifiNow(adapter(), null).first)
        assertEquals(RadioState.Inactive, localWifiNow(adapter(powered = false), linked).first, "turned off wins over an old link")
        assertEquals(RadioState.Disabled, localWifiNow(adapter(powered = false, block = RadioBlock.Software), null).first)
        assertEquals(RadioState.Disabled, localWifiNow(adapter(block = RadioBlock.Hardware), linked).first)
        assertEquals(RadioState.Disabled, localWifiNow(adapter(block = RadioBlock.Both), null).first)
        assertEquals(RadioState.Neutral, localWifiNow(adapter(powered = null, block = RadioBlock.Unknown), null).first, "nothing known: nothing claimed")
        val why = listOf(localWifiNow(adapter(), linked), localWifiNow(adapter(), null), localWifiNow(adapter(powered = false), null),
            localWifiNow(adapter(block = RadioBlock.Software), null), localWifiNow(adapter(block = RadioBlock.Hardware), null)).map { it.second }
        assertEquals(why.size, why.distinct().size, "each state says why in its own words")
    }

    @Test fun thisComputersAdaptersBecomeCardsOfTheBoard() {
        val scan = WifiScan(BtSource.Local, listOf(adapter()), listOf(WifiLink("wlan0", true, channel = 157)), emptyList(), at)
        val card = localWifiCards(scan).single()
        assertEquals(THIS_COMPUTER_KEY, card.hostKey)
        assertEquals("Sample Wi-Fi 6E AX210", card.name)
        assertEquals("8086:2725", card.ids)
        assertEquals(RadioBus.Pci, card.bus)
        assertEquals("PCI 0000:03:00.0", card.port)
        assertEquals("iwlwifi", card.driver)
        assertEquals(RadioScanRoute.ThisComputer, card.scan)
        assertEquals(RadioState.Active, card.state)
        val noInterface = localWifiCards(scan.copy(adapters = listOf(adapter(hasInterface = false)), links = emptyList())).single()
        assertNull(noInterface.scan)
        assertNotNull(noInterface.scanRefusal)
        val usb = localWifiCards(scan.copy(adapters = listOf(adapter().copy(hardware = AdapterHardware("mt7921u", "usb", 0x0e8d, 0x7961))))).single()
        assertEquals("0e8d:7961", usb.ids)
        assertEquals(RadioBus.Usb, usb.bus)
        assertEquals("Sample AX210", usb.name, "no product string: the adapter's own name")
    }

    @Test fun aScanBecomesThePanel() {
        val scan = sampleWifiScan(BtSource.Local, at)
        val panel = wifiPanel(scan, sources, THIS_COMPUTER_KEY, history = listOf(-60, -58, -57))
        assertEquals(scan.adapters.size, panel.adapters.size)
        assertEquals(scan.networks.size, panel.networks.size)
        assertEquals(scan.networks.sumOf { it.accessPoints.size }, panel.networks.sumOf { it.aps.size })
        val link = scan.connectedLink!!
        assertEquals(link.ssid, panel.link!!.ssid)
        assertEquals(link.signalDbm, panel.link!!.signalDbm)
        assertEquals(link.channel, panel.link!!.channel)
        assertEquals(listOf<Int?>(-60, -58, -57), panel.link!!.history)
        assertTrue(panel.networks.any { it.connected }, "the connected access point is marked")
        assertEquals(scan.networks.count { it.hidden }, panel.networks.count { it.ssid.isEmpty() }, "a hidden network has a blank name")
        assertEquals(scan.note, panel.note?.text); assertNull(panel.refusal)
        assertEquals(at, panel.at)
    }

    @Test fun whatTheSystemSaidIsShownAndNeverBlocksAnotherTry() {
        var opened: WifiIssue? = null
        val off = wifiIssueScan(BtSource.Local, WifiIssue.LocationOff, at, listOf(adapter()), listOf(WifiLink("wlan0", true, channel = 157, signalDbm = -50)))
        val panel = wifiPanel(off, sources, THIS_COMPUTER_KEY, fix = { issue -> if (issue == WifiIssue.LocationOff) ({ opened = issue }) else null })
        assertNull(panel.refusal, "the Scan button stays usable")
        assertEquals(WifiIssue.LocationOff.sentence(BtSource.Local), panel.note!!.text)
        assertEquals("Open settings", panel.note!!.action!!.label)
        panel.note!!.action!!.run()
        assertEquals(WifiIssue.LocationOff, opened)
        assertEquals(157, panel.link!!.channel, "what could be read is still shown")
        assertEquals("", panel.link!!.ssid, "the name Windows withheld stays blank")
        val none = wifiPanel(wifiIssueScan(BtSource.Local, WifiIssue.NoAdapter, at), sources, THIS_COMPUTER_KEY, fix = { null })
        assertEquals(WifiIssue.NoAdapter.sentence(BtSource.Local), none.note!!.text)
        assertNull(none.note!!.action, "no settings page helps: no button")
    }

    @Test fun whatAVmSeesBecomesTheGuestView() {
        val probe = GuestRadioProbe(BtSource("r//VM/120", "VM 120"), listOf(
            GuestRadio(GuestRadioKind.Bluetooth, "hci0", "sample-bt", "02:00:00:00:00:10", true, RadioBlock.None, null, AdapterHardware("btusb", "usb")),
            GuestRadio(GuestRadioKind.WiFi, "wlan0", "wlan0", "02:00:00:00:00:11", false, RadioBlock.Software, false, AdapterHardware("mt7921u", "usb")),
        ), at, note = "sample note")
        val view = guestProbeView(probe)
        val bt = view.bluetooth.single(); val wifi = view.wifi.single()
        assertTrue(bt.name.startsWith("hci0") && "sample-bt" in bt.name)
        assertEquals("btusb", bt.driver); assertEquals(true, bt.powered); assertEquals(false, bt.blocked)
        assertEquals("wlan0", wifi.name, "a name that repeats the interface is said once")
        assertEquals(true, wifi.blocked); assertEquals(false, wifi.up); assertEquals(false, wifi.connected)
        assertEquals("sample note", view.note!!.text)
        assertEquals(at, view.at)
    }

    @Test fun whileTheViewIsShownThisComputerIsReadAgainAndAgain() = kotlinx.coroutines.runBlocking {
        val job = kotlinx.coroutines.Job()
        val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + job)
        val scans = java.util.concurrent.atomic.AtomicInteger()
        val scanner = object : local.ringleader.core.WifiScanner {
            override val platform = "fake"
            override fun scan(seconds: Int): WifiScan {
                val link = WifiLink("wlan0", true, signalDbm = -60 + scans.incrementAndGet(), channel = 44)
                return WifiScan(BtSource.Local, listOf(adapter()), listOf(link), emptyList(), at)
            }
        }
        val boards = mutableListOf<List<RadioCard>>()
        val feed = LiveWifiFeed(scope, scanner, followMillis = 20, onScan = { boards += localWifiCards(it) })
        kotlinx.coroutines.delay(120)
        assertEquals(0, scans.get(), "nothing is read while the view is not shown")
        val view = scope.launch { feed.panel.collect { } }
        kotlinx.coroutines.withTimeout(5_000) { while (feed.panel.value.link?.history.orEmpty().size < 3) kotlinx.coroutines.delay(10) }
        val history = feed.panel.value.link!!.history
        assertEquals(history.sortedBy { it }, history, "one reading per pass, in order: $history")
        assertEquals(history.distinct(), history, "no reading is counted twice")
        assertTrue(boards.isNotEmpty() && boards.last().single().state == RadioState.Active, "the board hears of this computer's adapter")
        view.cancel()
        kotlinx.coroutines.delay(150)
        val after = scans.get()
        kotlinx.coroutines.delay(250)
        assertTrue(scans.get() <= after + 1, "reading stops when the view goes away: $after then ${scans.get()}")
        job.cancel()
    }
}
