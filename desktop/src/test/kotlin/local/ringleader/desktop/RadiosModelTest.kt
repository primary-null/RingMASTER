package local.ringleader.desktop

import local.ringleader.core.*
import kotlin.test.*

/** The Radios display rules that need no drawing: the map layout, what lights together, names hidden, demo coherence. */
/** A second connection's key, to show that pins of one connection are not another's. */
private const val OTHER_CONNECTION = "other-connection"

class RadiosModelTest {
    @Test fun mapLayoutKeepsGuestsApartAndEveryLinkOnItsChip() {
        for (width in listOf(560f, 900f, 1500f, 2400f)) for (scale in listOf(1f, 1.5f)) {
            val board = RadiosScenesTest.busyBoard()
            val l = radiosMapLayout(board, width, scale)
            assertEquals(board.radios.size, l.chips.size)
            assertEquals(board.guests.size, l.guests.size)
            val g = l.guests.map { it.second }.sortedBy { it.y }
            g.zipWithNext().forEach { (a, b) -> assertTrue(b.y >= a.bottom, "guests overlap at $width x $scale") }
            assertTrue(g.all { it.right <= width + .5f && it.x > l.hosts.first().second.right }, "guests sit right of the hosts")
            assertEquals(board.radios.count { !it.free }, l.links.count { !it.spice })
            l.links.filter { !it.spice }.forEach { link ->
                val chip = l.chips.first { radioItemKey(it.first) == link.from }.second
                assertEquals(chip.right, link.a.x); assertEquals(chip.midY, link.a.y)
            }
            assertTrue(l.height >= l.hosts.last().second.bottom && l.height >= g.last().bottom)
        }
    }

    @Test fun aSpiceChannelRunsFromThisComputerDotted() {
        val l = radiosMapLayout(demoRadiosBoard(), 1000f)
        val spice = l.links.single { it.spice }
        assertEquals("host:$THIS_COMPUTER_KEY", spice.from)
        assertEquals(guestItemKey(demoWorkbench), spice.to)
        assertFalse(l.links.single { it.to == guestItemKey(demoBackupLab) }.running, "a stopped guest's line is dashed")
    }

    @Test fun selectionLightsTheWholePath() {
        val b = demoRadiosBoard()
        assertEquals(setOf("radio:demo-node-a/usb/1-4", guestItemKey(demoWorkbench)), mapPath(b, "radio:demo-node-a/usb/1-4"))
        assertEquals(setOf(guestItemKey(demoWorkbench), "radio:demo-node-a/usb/1-4", "radio:demo-node-a/usb/2-1", "host:$THIS_COMPUTER_KEY"), mapPath(b, guestItemKey(demoWorkbench)))
        assertEquals(setOf("radio:local/bt0"), mapPath(b, "radio:local/bt0"), "a free adapter lights alone")
        assertTrue(mapPath(b, null).isEmpty())
    }

    @Test fun demoBoardMatchesTheDemoFleet() {
        val b = demoRadiosBoard()
        val fleet = sampleOverview().guests.map { it.resource }.toSet()
        assertTrue(b.guests.all { it.ref.resource in fleet }, "every demo holder is a demo guest")
        assertTrue(b.hosts.filter { it.isNode }.all { h -> sampleOverview().members.any { it.name == h.name } })
        assertEquals(b.radios.size, b.radios.map { it.id }.toSet().size, "ids are unique")
        assertEquals(4, b.inGuests)
        assertTrue(b.radios.all { c -> c.scan != null || c.scanRefusal != null }, "every adapter says whether it can scan")
    }

    @Test fun sureTextSaysHowSure() {
        val c = RadioCard("x", RadioKind.Bluetooth, "", "h")
        assertEquals("Bluetooth adapter", c.sureText)
        assertEquals("Probably a Wi-Fi adapter", c.copy(kind = RadioKind.WiFi, sure = RadioSure.Likely).sureText)
        assertEquals("Looks like a Bluetooth adapter", c.copy(sure = RadioSure.Guess).sureText)
        assertEquals("Bluetooth adapter", c.title, "a blank name says what it is")
    }

    @Test fun hiddenNamesAreLettersInSignalOrder() {
        val nets = orderedNetworks(demoWifiPanel().networks)
        assertTrue(nets.first().connected, "the connected network leads")
        assertEquals("Network A", networkLabel(nets[0], 0, false))
        assertEquals("Network B", networkLabel(nets[1], 1, false))
        assertEquals("Hidden network", networkLabel(WifiNetworkView(""), 3, true))
        assertEquals("AA", networkLetter(26))
        assertEquals(ADDRESS_HIDDEN, apAddress("02:00:00:00:00:01", false))
        assertEquals(ADDRESS_HIDDEN, apAddress(null, true))
    }

    @Test fun showcaseKeepsNamesHiddenEvenWhenChosen() {
        try {
            WifiNames.chosen = true
            Appearance.preview(ThemeMode.Dark, showcase = true)
            assertFalse(WifiNames.shown)
            Appearance.preview(ThemeMode.Dark, showcase = false)
            assertTrue(WifiNames.shown)
        } finally { WifiNames.chosen = false; Appearance.preview(false) }
    }

    @Test fun signalBarsAndWords() {
        assertEquals(0, wifiBars(null)); assertEquals(0, wifiBars(-95)); assertEquals(1, wifiBars(-85)); assertEquals(4, wifiBars(-50)); assertEquals(4, wifiBars(-30))
        assertEquals("Excellent", signalWord(-50)); assertEquals("Good", signalWord(-61)); assertEquals("Fair", signalWord(-70)); assertEquals("Weak", signalWord(-82))
        assertEquals("1.20 Gbit/s", rateText(1201.0)); assertEquals("865 Mbit/s", rateText(864.5)); assertEquals("—", rateText(null))
    }

    @Test fun channelSpansFollowTheWidth() {
        assertEquals(2f, channelHalfSpan(20)); assertEquals(8f, channelHalfSpan(80)); assertEquals(2f, channelHalfSpan(null))
        for (band in WifiBand.entries) bandAxis(band).let { a -> assertTrue(a.ticks.all { it in a.first..a.last }) }
    }

    @Test fun pdmAndLiveBoardsSayTheyWereNotRead() {
        assertEquals(RADIOS_PDM, unreadRadiosBoard(WorkspaceState(kind = PDM_KIND)).notes.single().text)
        val live = unreadRadiosBoard(WorkspaceState(overview = sampleOverview()))
        assertEquals(listOf(THIS_COMPUTER_LABEL, "demo-node-a", "demo-node-b"), live.hosts.map { it.name })
        assertTrue(live.empty)
    }

    @Test fun guestLinesComeFromTheBoard() {
        val guests = BoardGuestRadios()
        val vm = guests.view(RadiosScenesTest.demoGuest, null, demoRadiosBoard())
        assertEquals(listOf("usb0", "usb1", "usb2"), vm.lines.map { it.entry })
        assertTrue(vm.hasWifi)
        assertEquals(GUEST_PROBE_UNAVAILABLE, vm.probeRefusal?.text)
        val ct = guests.view(RadiosScenesTest.demoContainer, null, demoRadiosBoard())
        assertEquals(RADIOS_CT_PROBE, ct.probeRefusal?.text)
        assertEquals(RadioSure.Guess, ct.lines.single().sure)
        assertTrue(guests.view(sampleOverview().guests.first { it.id == 130 }.copy(id = 999), null, demoRadiosBoard()).lines.isEmpty())
    }

    @Test fun markTextNamesKindAndEntry() {
        val g = demoRadiosBoard().guest(demoBackupLab.key)!!
        assertEquals("Radios: ASUS USB-BT500 (Bluetooth, usb0)", guestRadioMarkText(g))
    }

    @Test fun aPinnedBluetoothTabOpensRadiosAndContainersCanPinIt() {
        val file = java.nio.file.Files.createTempDirectory("ringmaster-pins").resolve("pins.json")
        val store = PinStore(file)
        store.toggleView(OTHER_CONNECTION, RadiosScenesTest.demoGuest, "Radios")
        store.toggleView(OTHER_CONNECTION, RadiosScenesTest.demoContainer, "Radios")
        assertEquals(listOf("Radios", "Radios"), store.list(OTHER_CONNECTION).map { it.tab }, "a container has the tab too")
        // A pin saved by an earlier version names the old tab.
        java.nio.file.Files.writeString(file, java.nio.file.Files.readString(file).replace("\"Radios\"", "\"Bluetooth\""))
        assertEquals(listOf("Radios", "Radios"), PinStore(file).list(OTHER_CONNECTION).map { it.tab })
    }
}
