package local.ringleader.core.guesthelper

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import local.ringleader.core.BtScan
import local.ringleader.core.Connection
import local.ringleader.core.Guest
import local.ringleader.core.GuestBluetoothGateway
import local.ringleader.core.GuestRadioGateway
import local.ringleader.core.GuestRadioKind
import local.ringleader.core.GuestRadioProbe
import local.ringleader.core.HardwareSummary
import local.ringleader.core.WifiScan
import local.ringleader.core.guestBluetoothSource
import local.ringleader.core.guestRadioSource
import local.ringleader.core.integrations.GuestRef
import local.ringleader.core.parseBluezScanOutput
import local.ringleader.core.parseGuestRadioProbe
import local.ringleader.core.parseLinuxWifiScan

/*
 * The Engine as a second way into a guest. It runs the same radio scripts as the guest agent, and its text goes to the same
 * parsers. Which way a call goes is decided in one place, [guestRadioRoute]: a paired Engine for that guest first, the
 * guest agent otherwise.
 */

/** The pairing place of [guest]: its kind, id and scope, on its own server. */
fun guestHelperPlace(guest: Guest) = GuestHelperPlace.Guest(GuestRef(guest.kind, guest.id, guest.scope), guest.remote)

/** Which way a call into a guest goes. */
enum class GuestRadioRoute { Engine, GuestAgent }

/** THE choice: an Engine paired for the guest is used whenever there is one; otherwise the guest agent, with its own refusals. */
fun guestRadioRoute(pairing: GuestHelperLink?): GuestRadioRoute = if (pairing != null) GuestRadioRoute.Engine else GuestRadioRoute.GuestAgent

/** Finds the pairing of a guest, or null. [GuestHelperPairings.forPlace] in the app; a map in tests. */
fun interface GuestHelperFinder {
    fun pairingFor(guest: Guest): GuestHelperLink?
}

fun GuestHelperPairings.finder() = GuestHelperFinder { guest -> forPlace(guestHelperPlace(guest)) }

/** Opens a client for a pairing. [GuestHelperPairings.client] in the app; a fake Engine's client in tests. */
fun interface GuestHelperClients {
    fun clientFor(link: GuestHelperLink): GuestHelperClient
}

const val GUEST_HELPER_NOT_PAIRED = "No $GUEST_HELPER_NAME is paired for this guest."

/**
 * Inside a container, `/sys/class/bluetooth` is not separated from the node: it lists the node's controllers, which the
 * container cannot use. They are taken out of the container's own list and said in words instead.
 */
fun containerRadioProbe(probe: GuestRadioProbe): GuestRadioProbe {
    val borrowed = probe.radios.count { it.kind == GuestRadioKind.Bluetooth }
    if (borrowed == 0) return probe
    val controllers = if (borrowed == 1) "Bluetooth controller" else "$borrowed Bluetooth controllers"
    val sentence = "The node's $controllers show through here, but a container cannot use them unless the node passes one in."
    val own = probe.radios.filterNot { it.kind == GuestRadioKind.Bluetooth }
    val note = listOfNotNull(probe.note.takeIf { own.isNotEmpty() }, sentence).joinToString(" ")
    return probe.copy(radios = own, note = note)
}

/** The probe and the Wi-Fi scan through a paired Engine. Refuses with [GUEST_HELPER_NOT_PAIRED] when there is no pairing. */
class GuestHelperRadioGateway(
    private val finder: GuestHelperFinder,
    private val clients: GuestHelperClients,
    private val clock: () -> Instant = Instant::now,
) : GuestRadioGateway {
    override suspend fun probe(connection: Connection, guest: Guest, hardware: HardwareSummary?): GuestRadioProbe =
        runInterruptible(Dispatchers.IO) {
            val run = client(guest).radios()
            val probe = parseGuestRadioProbe(run.output, guestRadioSource(guest), clock())
            if (guest.kind == "VM") probe else containerRadioProbe(probe)
        }

    override suspend fun wifiScan(connection: Connection, guest: Guest, hardware: HardwareSummary?, seconds: Int): WifiScan =
        runInterruptible(Dispatchers.IO) {
            val window = seconds.coerceIn(GuestHelperClient.SCAN_SECONDS.first, GuestHelperClient.SCAN_SECONDS.last)
            val run = client(guest).scanWifi(window)
            parseLinuxWifiScan(run.output, guestRadioSource(guest), clock())
        }

    private fun client(guest: Guest): GuestHelperClient {
        val link = finder.pairingFor(guest) ?: throw GuestHelperException(GuestHelperFailure.Refused, GUEST_HELPER_NOT_PAIRED)
        return clients.clientFor(link)
    }
}

/** The Bluetooth scan through a paired Engine: its `bt-scan.sh` prints what the guest agent's scan prints, for the same parser. */
class GuestHelperBluetoothGateway(
    private val finder: GuestHelperFinder,
    private val clients: GuestHelperClients,
    private val clock: () -> Instant = Instant::now,
) : GuestBluetoothGateway {
    override suspend fun scan(connection: Connection, guest: Guest, seconds: Int): BtScan = runInterruptible(Dispatchers.IO) {
        val link = finder.pairingFor(guest) ?: throw GuestHelperException(GuestHelperFailure.Refused, GUEST_HELPER_NOT_PAIRED)
        val window = seconds.coerceIn(GuestHelperClient.SCAN_SECONDS.first, GuestHelperClient.SCAN_SECONDS.last)
        val run = clients.clientFor(link).scanBluetooth(window)
        parseBluezScanOutput(run.output, guestBluetoothSource(guest), clock())
    }
}

/** Probe and Wi-Fi scan by [guestRadioRoute]: the Engine when one is paired for the guest, [agent] otherwise. */
class RoutedGuestRadioGateway(
    private val engine: GuestRadioGateway,
    private val agent: GuestRadioGateway,
    private val finder: GuestHelperFinder,
) : GuestRadioGateway {
    private fun way(guest: Guest) = if (guestRadioRoute(finder.pairingFor(guest)) == GuestRadioRoute.Engine) engine else agent

    override suspend fun probe(connection: Connection, guest: Guest, hardware: HardwareSummary?): GuestRadioProbe =
        way(guest).probe(connection, guest, hardware)

    override suspend fun wifiScan(connection: Connection, guest: Guest, hardware: HardwareSummary?, seconds: Int): WifiScan =
        way(guest).wifiScan(connection, guest, hardware, seconds)
}

/** The Bluetooth scan inside a guest by [guestRadioRoute]. */
class RoutedGuestBluetoothGateway(
    private val engine: GuestBluetoothGateway,
    private val agent: GuestBluetoothGateway,
    private val finder: GuestHelperFinder,
) : GuestBluetoothGateway {
    override suspend fun scan(connection: Connection, guest: Guest, seconds: Int): BtScan {
        val way = if (guestRadioRoute(finder.pairingFor(guest)) == GuestRadioRoute.Engine) engine else agent
        return way.scan(connection, guest, seconds)
    }
}
