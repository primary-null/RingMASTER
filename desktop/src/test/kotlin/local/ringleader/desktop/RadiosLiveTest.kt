package local.ringleader.desktop

import local.ringleader.core.FleetRadios
import local.ringleader.core.RADIOS_NEED_DIRECT
import local.ringleader.core.Radio
import local.ringleader.core.RadioCertainty
import local.ringleader.core.RadioChange
import local.ringleader.core.RadioMeans
import local.ringleader.core.RadioTie
import local.ringleader.core.RadioUse
import local.ringleader.core.ResourceId
import local.ringleader.core.sampleFleetRadios
import local.ringleader.core.guestsWithRadios
import local.ringleader.core.usersOf
import local.ringleader.core.RadioBus as CoreRadioBus
import local.ringleader.core.RadioKind as CoreRadioKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The mapping from the fleet model that core reads to the board the Radios screens show. */
class RadiosLiveTest {
    private val fleet = sampleFleetRadios()
    private val nodes = fleet.nodes.map { RadioNodeRow(it.node, true) }
    private fun board(fleet: FleetRadios? = this.fleet, connected: Boolean = true, pdm: Boolean = false, failure: String? = null, retry: (() -> Unit)? = null) =
        radiosBoard(fleet, nodes, "session-1", connected, pdm, failure = failure, retry = retry)

    @Test fun everyAdapterOfTheFleetIsACardOnItsNode() {
        val b = board()
        assertTrue(fleet.radios.isNotEmpty(), "the sample fleet has radios")
        assertEquals(fleet.radios.map { it.id }.toSet(), b.radios.map { it.id }.toSet())
        assertEquals(b.radios.size, b.radios.map { it.id }.distinct().size, "ids are unique on the board")
        assertEquals(listOf(THIS_COMPUTER_KEY) + nodes.map { it.name }, b.hosts.map { it.key })
        for (card in b.radios) assertNotNull(b.host(card.hostKey), "${card.id} sits on a host of the board")
        assertEquals(fleet.readAt, b.readAt)
    }

    @Test fun aCardNamesTheGuestThatHoldsItAndHowAndTheWayBack() {
        val b = board()
        var held = 0
        for (radio in fleet.radios) {
            val use = usersOf(fleet, radio).firstOrNull { it.tie == RadioTie.Exact || it.tie == RadioTie.OneOfSeveral }
            val card = b.radio(radio.id)!!
            if (use == null) { assertTrue(card.free, "${radio.id} is free"); assertEquals(RADIOS_NODE_SCAN, card.scanRefusal?.text); continue }
            held++
            val holder = card.holder!!
            assertEquals(use.guest, holder.guest.resource)
            assertEquals(use.key, holder.entry)
            assertEquals(radioVia(use.means), holder.via)
            assertEquals("session-1", holder.guest.session)
            assertEquals(use.running, holder.guest.running)
            assertTrue(b.guestFor(use.guest)!!.radios.any { it.id == radio.id }, "the guest lists the adapter it holds")
        }
        assertTrue(held > 0, "the sample fleet gives at least one adapter to a guest")
        assertEquals(guestsWithRadios(fleet).map { it.guest }.toSet(), b.guests.filter { it.radios.isNotEmpty() }.mapNotNull { it.ref.resource }.toSet())
    }

    @Test fun aScanIsOfferedOnlyWhereItCanRun() {
        for (card in board().radios) {
            val holder = card.holder
            when {
                holder == null -> assertNull(card.scan)
                !holder.guest.isVm -> assertEquals(RADIOS_CT_SCAN, card.scanRefusal?.text)
                !holder.guest.running -> assertEquals(RADIOS_VM_STOPPED, card.scanRefusal?.text)
                card.kind == RadioKind.WiFi -> assertEquals(WIFI_UNAVAILABLE, card.scanRefusal?.text, "no Wi-Fi scan inside a VM until its source is wired")
                else -> { assertEquals(RadioScanRoute.InGuest, card.scan); assertNull(card.scanRefusal) }
            }
        }
        val withGuestWifi = radiosBoard(fleet, nodes, null, connected = true, pdm = false, guestWifi = true)
        for (card in withGuestWifi.radios.filter { it.kind == RadioKind.WiFi && it.holder?.guest?.isVm == true && it.holder?.guest?.running == true })
            assertEquals(RadioScanRoute.InGuest, card.scan)
    }

    @Test fun spiceSlotsBecomeChannelsOfTheirGuest() {
        val expected = fleet.guests.flatMap { g -> g.spiceSlots.map { g.guest to it } }.toSet()
        assertEquals(expected, board().channels.map { it.guest.resource!! to it.entry }.toSet())
    }

    @Test fun nodeNotesStayWithTheirNode() {
        val sample = listOf("This token may not list USB devices (sample).")
        val noted = fleet.copy(nodes = fleet.nodes.mapIndexed { i, n -> if (i == 0) n.copy(notes = sample) else n })
        val b = board(noted)
        assertEquals(listOf("This token may not list USB devices (sample)."), b.host(nodes.first().name)!!.notes.map { it.text })
        for (node in fleet.nodes.drop(1)) assertEquals(node.notes, b.host(node.node)!!.notes.map { it.text }, "${node.node} keeps its own notes")
        assertTrue(b.host(THIS_COMPUTER_KEY)!!.notes.isEmpty())
    }

    @Test fun beforeTheFirstReadTheNodesAreShownEmpty() {
        val b = board(fleet = null)
        assertEquals(nodes.size + 1, b.hosts.size)
        assertTrue(b.radios.isEmpty() && b.notes.isEmpty())
        assertNull(b.readAt)
    }

    @Test fun notConnectedAndPdmSayWhyAndShowNoNode() {
        val closed = board(fleet = null, connected = false)
        assertEquals(listOf(RADIOS_NOT_CONNECTED), closed.notes.map { it.text })
        assertEquals(listOf(THIS_COMPUTER_KEY), closed.hosts.map { it.key })
        val pdm = board(fleet = FleetRadios("remote", unavailable = RADIOS_NEED_DIRECT), pdm = true)
        assertEquals(listOf(RADIOS_PDM), pdm.notes.map { it.text })
        assertEquals(listOf(THIS_COMPUTER_KEY), pdm.hosts.map { it.key })
        assertTrue(pdm.radios.isEmpty())
    }

    @Test fun aFailedReadKeepsTheLastAnswerAndOffersToTryAgain() {
        var tried = 0
        val b = board(failure = "The connection timed out.", retry = { tried++ })
        assertEquals(fleet.radios.size, b.radios.size, "the last answer stays on the board")
        val note = b.notes.single { it.warning }
        assertEquals("The connection timed out.", note.text)
        assertEquals("Try again", note.action?.label)
        note.action!!.run()
        assertEquals(1, tried)
        assertFalse(board().notes.any { it.warning })
    }

    @Test fun anAdapterKnownOnlyAsAnInterfaceIsNotShownByItsHardwareAddress() {
        fun nic(name: String, bus: CoreRadioBus?) = Radio("r|n|net:$name", "r", "n", CoreRadioKind.WiFi, bus,
            "Wi-Fi interface $name", RadioCertainty.Likely, "sample", interfaceName = name)
        val usb = nic("wlx0200000a1f60", CoreRadioBus.Usb)
        assertEquals("USB Wi-Fi adapter …1f60", radioName(usb))
        assertEquals("interface wlx…1f60", radioPort(usb))
        assertFalse("0200000a1f60" in radioName(usb) + radioPort(usb))
        val builtIn = nic("wlo1", null)
        assertEquals("Wi-Fi adapter wlo1", radioName(builtIn))
        assertEquals("interface wlo1", radioPort(builtIn))
        assertEquals("wlp3s0", shortInterface("wlp3s0"))
        val named = usb.copy(name = "Sample AX200")
        assertEquals("Sample AX200", radioName(named), "a real product name is kept")
    }

    @Test fun thisComputersOwnAdaptersComeFirst() {
        val own = RadioCard("local/bt0", RadioKind.Bluetooth, "Sample Bluetooth", THIS_COMPUTER_KEY, scan = RadioScanRoute.ThisComputer)
        val b = radiosBoard(fleet, nodes, null, connected = true, pdm = false, local = listOf(own))
        assertEquals("local/bt0", b.radios.first().id)
        assertEquals(1, b.radiosOn(THIS_COMPUTER_KEY).size)
    }

    private fun sampleCard(up: Boolean? = null) = Radio("r|n|pci:0000:00:14.3", "r", "n", CoreRadioKind.WiFi,
        CoreRadioBus.Pci, "Sample card", RadioCertainty.Certain, "sample", address = "0000:00:14.3", interfaceName = "wlo1", interfaceActive = up)
    private fun sampleUse(change: RadioChange = RadioChange.None) = RadioUse(
        ResourceId("r", "", "VM", 101), "sample-vm", "VM", "n", true, "hostpci0", "0000:00:14.3", RadioMeans.PciDevice,
        "r|n|pci:0000:00:14.3", RadioTie.Exact, change)

    @Test fun anAdapterFollowsItsGuestItsNodeAndItsInterface() {
        val guest = RadioGuestRef("r/VM/101", 101, "sample-vm", "VM", "n", running = true)
        fun now(up: Boolean? = null, held: Boolean = true, running: Boolean = true, online: Boolean = true, change: RadioChange = RadioChange.None) =
            radioNow(sampleCard(up), sampleUse(change).takeIf { held }, guest.copy(running = running).takeIf { held }, online)
        assertEquals(RadioState.Active, now().first, "its guest is running")
        assertEquals(RadioState.Inactive, now(running = false).first, "its guest is stopped")
        assertEquals(RadioState.Neutral, now(change = RadioChange.AtNextStart).first, "set for the next start, not in use yet")
        assertEquals(RadioState.Active, now(change = RadioChange.RemovedAtNextStart).first, "still in use until the guest restarts")
        assertTrue("taken away" in now(change = RadioChange.RemovedAtNextStart).second)
        assertEquals(RadioState.Active, now(up = true, held = false).first, "free, and the node has its interface up")
        assertEquals(RadioState.Neutral, now(up = false, held = false).first, "free, interface down")
        assertEquals(RadioState.Neutral, now(up = null, held = false).first, "free, nothing known about an interface")
        for (held in listOf(true, false)) {
            assertEquals(RadioState.Disabled, now(up = true, held = held, online = false).first, "an offline node wins over everything")
        }
        val cases = listOf(now(), now(running = false), now(up = true, held = false), now(up = false, held = false), now(held = false), now(online = false))
        val sentences = cases.map { it.second }
        assertTrue(sentences.all { it.isNotBlank() && it.endsWith(".") }, "each state says why: $sentences")
        assertEquals(sentences.size, sentences.distinct().size, "each in its own words: $sentences")
    }

    @Test fun theInventoryStatusWinsOverTheOlderRead() {
        val radio = fleet.radios.first { r -> usersOf(fleet, r).any { it.tie == RadioTie.Exact || it.tie == RadioTie.OneOfSeveral } }
        val use = usersOf(fleet, radio).first { it.tie == RadioTie.Exact || it.tie == RadioTie.OneOfSeveral }
        fun card(running: Boolean) = radiosBoard(fleet, nodes, null, connected = true, pdm = false, running = mapOf(use.guest to running)).radio(radio.id)!!
        assertEquals(RadioState.Inactive, card(false).state)
        assertFalse(card(false).holder!!.guest.running)
        assertEquals(RadioState.Active, card(true).state)
        assertTrue(card(true).holder!!.guest.running)
        assertEquals(use.running, board().radio(radio.id)!!.holder!!.guest.running, "with no newer status, the read's own answer stands")
    }

    @Test fun anOfflineNodeDisablesItsAdapters() {
        val b = radiosBoard(fleet, nodes.map { it.copy(online = it.name != nodes.first().name) }, null, connected = true, pdm = false)
        val onIt = b.radiosOn(nodes.first().name)
        assertTrue(onIt.isNotEmpty() && onIt.all { it.state == RadioState.Disabled }, "${onIt.map { it.state }}")
        assertTrue(b.radios.filter { it.hostKey != nodes.first().name }.none { it.state == RadioState.Disabled })
    }

    @Test fun aGuestsNameComesFromTheInventoryWhenItIsNewer() {
        val radio = fleet.radios.first { r -> usersOf(fleet, r).any { it.tie == RadioTie.Exact || it.tie == RadioTie.OneOfSeveral } }
        val use = usersOf(fleet, radio).first { it.tie == RadioTie.Exact || it.tie == RadioTie.OneOfSeveral }
        fun name(names: Map<ResourceId, String>): String {
            val board = radiosBoard(fleet, nodes, null, connected = true, pdm = false, names = names)
            return board.radio(radio.id)!!.holder!!.guest.name
        }
        assertEquals("renamed-guest", name(mapOf(use.guest to "renamed-guest")), "a guest renamed since the read shows its new name")
        assertEquals(use.guestName, name(emptyMap()), "with nothing newer, the name from the read stands")
        assertEquals(use.guestName, name(mapOf(use.guest to "")), "a blank name never replaces a known one")
    }
}
