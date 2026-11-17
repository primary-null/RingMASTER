package local.ringleader.core.guesthelper

import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import local.ringleader.core.AuditCerts
import local.ringleader.core.BT_NO_ADAPTER
import local.ringleader.core.BtScan
import local.ringleader.core.Connection
import local.ringleader.core.Guest
import local.ringleader.core.GuestBluetoothGateway
import local.ringleader.core.GuestRadioGateway
import local.ringleader.core.GuestRadioKind
import local.ringleader.core.GuestRadioProbe
import local.ringleader.core.HardwareSummary
import local.ringleader.core.NoVault
import local.ringleader.core.WifiScan
import local.ringleader.core.guestRadioSource
import local.ringleader.core.integrations.GuestRef
import local.ringleader.core.integrations.Reply
import local.ringleader.core.integrations.ScriptedServer
import local.ringleader.core.integrations.ServiceWire
import kotlin.test.*

/** The Engine as a way into a guest: the one routing rule, the container rule, and the gateways over a fake Engine. */
class GuestHelperGatewaysTest {
    private companion object {
        val store by lazy { AuditCerts.generate("engine-gw") }
    }

    private val pin = AuditCerts.pin(AuditCerts.cert(store))
    private val wire = ServiceWire("RingMASTER/0.0.0-test")
    private val token = "AAAAA-BBBBB-CCCCC-DDDDD-EEEEE"
    private val at = Instant.parse("2026-01-02T03:04:05Z")
    private val connection = Connection("https://192.0.2.1:8006", "user@pam!t", "secret-for-tests", "")
    private val vm = Guest("https://192.0.2.1:8006", 990, "lab-vm", "node-a", "VM", "running")
    private val ct = Guest("https://192.0.2.1:8006", 991, "lab-ct", "node-a", "Container", "running")

    private fun link(guest: Guest, port: Int) =
        GuestHelperLink("id-${guest.id}", guestHelperPlace(guest), "127.0.0.1", port, pin, GUEST_HELPER_VERSION, guest.name, at, null)

    /** One run answer as the Engine writes it, with [output] as the script's text. */
    private fun run(mode: String, output: String): String =
        """{"api":1,"mode":"$mode","seconds":4,"exit":0,"timed_out":false,"truncated":false,"ms":5,""" +
            """"probe_sha256":"${GuestHelperScripts.probeSha256}","output":${JsonPrimitive(output)}}"""

    private val probeText = listOf(
        "RINGMASTER-RADIO 1 probe 0a1b",
        "0a1b\ttools\tbluetoothctl",
        "0a1b\tbt\tid=hci0\tdriver=btusb\tbus=usb",
        "0a1b\tbt\tid=hci1\tdriver=btusb\tbus=usb",
        "0a1b\twifi\tid=wlan0\tphy=phy0\tmac=02:00:00:00:00:31\toperstate=up\tcarrier=1",
        "0a1b\tdone",
    ).joinToString("\n", postfix = "\n")

    private fun engine(block: (ScriptedServer) -> Unit) = ScriptedServer(AuditCerts.serverContext(store)) { r ->
        when {
            r.line == "GET /v1/radios" -> Reply.json(200, run("probe", probeText))
            r.line.startsWith("POST /v1/scan/bluetooth?seconds=") -> Reply.json(200, run("bt-scan", "RINGMASTER-BLUEZ:NOADAPTER\n"))
            r.line.startsWith("POST /v1/scan/wifi?seconds=") ->
                Reply.json(200, run("wifi-scan", "RINGMASTER-RADIO 1 wifi 0a1b\n0a1b\tdone\n"))
            else -> Reply.json(404, "{}")
        }
    }.use(block)

    private fun clients() = GuestHelperClients { l -> GuestHelperClient(l.host, l.port, l.pin, wire) { token.toCharArray() } }

    @Test fun aPairedEngineIsUsedAndOtherwiseTheGuestAgent() {
        assertEquals(GuestRadioRoute.GuestAgent, guestRadioRoute(null))
        assertEquals(GuestRadioRoute.Engine, guestRadioRoute(link(vm, 1)))
        val agent = Recording()
        val engine = Recording()
        val paired = setOf(guestHelperPlace(vm))
        val finder = GuestHelperFinder { g -> if (guestHelperPlace(g) in paired) link(g, 1) else null }
        val routed = RoutedGuestRadioGateway(engine, agent, finder)
        runBlocking {
            routed.probe(connection, vm, null)
            routed.probe(connection, ct, null)
            routed.wifiScan(connection, ct, null, 10)
        }
        assertEquals(listOf("probe 990"), engine.calls)
        assertEquals(listOf("probe 991", "wifi 991"), agent.calls)
    }

    @Test fun theBluetoothScanFollowsTheSameRule() {
        val agent = RecordingBt()
        val engine = RecordingBt()
        val routed = RoutedGuestBluetoothGateway(engine, agent) { g -> if (g.id == 991) link(g, 1) else null }
        runBlocking {
            routed.scan(connection, vm, 10)
            routed.scan(connection, ct, 10)
        }
        assertEquals(listOf(990), agent.ids)
        assertEquals(listOf(991), engine.ids)
    }

    @Test fun aPlaceIsBoundToItsServer() {
        val elsewhere = vm.copy(remote = "https://192.0.2.99:8006")
        assertNotEquals(guestHelperPlace(vm), guestHelperPlace(elsewhere))
        assertEquals(GuestHelperPlace.Guest(GuestRef("VM", 990), "https://192.0.2.1:8006"), guestHelperPlace(vm))
    }

    @Test fun aVmProbeThroughTheEngineIsReadByTheSharedParser() = engine { s ->
        val gateway = GuestHelperRadioGateway({ g -> link(g, s.port) }, clients()) { at }
        val probe = runBlocking { gateway.probe(connection, vm, null) }
        assertEquals(listOf("hci0", "hci1", "wlan0"), probe.radios.map { it.id })
        assertEquals(at, probe.at)
    }

    @Test fun aContainerSaysTheNodesControllersAreNotItsOwn() = engine { s ->
        val gateway = GuestHelperRadioGateway({ g -> link(g, s.port) }, clients()) { at }
        val probe = runBlocking { gateway.probe(connection, ct, null) }
        assertEquals(listOf("wlan0"), probe.radios.map { it.id })
        assertTrue(probe.radios.none { it.kind == GuestRadioKind.Bluetooth })
        val expected = "The node's 2 Bluetooth controllers show through here, but a container cannot use them unless the node passes one in."
        assertEquals(expected, probe.note)
    }

    @Test fun theContainerRuleLeavesAProbeWithoutBluetoothAlone() {
        val none = GuestRadioProbe(guestRadioSource(ct), emptyList(), at, note = "nothing")
        assertSame(none, containerRadioProbe(none))
    }

    @Test fun theEngineScansGoToTheSharedParsers() = engine { s ->
        val finder = GuestHelperFinder { g -> link(g, s.port) }
        val bt = runBlocking { GuestHelperBluetoothGateway(finder, clients()) { at }.scan(connection, ct, 2) }
        assertEquals(BT_NO_ADAPTER, bt.note)
        assertTrue(s.received.any { it.line == "POST /v1/scan/bluetooth?seconds=4" }, "seconds are raised to the Engine's least")
        val wifi = runBlocking { GuestHelperRadioGateway(finder, clients()) { at }.wifiScan(connection, vm, null, 90) }
        assertEquals(at, wifi.at)
        assertTrue(s.received.any { it.line == "POST /v1/scan/wifi?seconds=30" }, "and lowered to its most")
    }

    @Test fun withoutAPairingTheEngineGatewaySaysSo() {
        val gateway = GuestHelperRadioGateway({ null }, clients())
        val e = assertFailsWith<GuestHelperException> { runBlocking { gateway.probe(connection, vm, null) } }
        assertEquals(GUEST_HELPER_NOT_PAIRED, e.message)
    }

    @Test fun withoutAPasswordSafeTheTokenIsKeptForThisRunAndTheRecordSaysSo() {
        val pairings = GuestHelperPairings(GuestHelperStore(null), NoVault("No safe in this test."), wire)
        val saved = pairings.save(link(vm, 1), token.toCharArray()).getOrThrow()
        assertFalse(saved.tokenInSafe)
        assertEquals(token, pairings.token(saved.id)?.concatToString())
        pairings.unpair(saved.id).getOrThrow()
        assertNull(pairings.token(saved.id))
    }

    private class Recording : GuestRadioGateway {
        val calls = mutableListOf<String>()
        override suspend fun probe(connection: Connection, guest: Guest, hardware: HardwareSummary?): GuestRadioProbe {
            calls += "probe ${guest.id}"
            return GuestRadioProbe(guestRadioSource(guest), emptyList(), Instant.EPOCH)
        }
        override suspend fun wifiScan(connection: Connection, guest: Guest, hardware: HardwareSummary?, seconds: Int): WifiScan {
            calls += "wifi ${guest.id}"
            return local.ringleader.core.sampleWifiScan(guestRadioSource(guest), Instant.EPOCH)
        }
    }

    private class RecordingBt : GuestBluetoothGateway {
        val ids = mutableListOf<Int>()
        override suspend fun scan(connection: Connection, guest: Guest, seconds: Int): BtScan {
            ids += guest.id
            return local.ringleader.core.sampleGuestBluetoothScan(guest)
        }
    }
}
