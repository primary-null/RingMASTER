package local.ringleader.desktop

import java.nio.file.Path
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import local.ringleader.core.Guest
import local.ringleader.core.GuestBluetoothGateway
import local.ringleader.core.GuestRadioGateway
import local.ringleader.core.LiveGuestBluetoothGateway
import local.ringleader.core.LiveGuestRadioGateway
import local.ringleader.core.SecretVault
import local.ringleader.core.guesthelper.GuestAgentInstaller
import local.ringleader.core.guesthelper.GuestHelperBluetoothGateway
import local.ringleader.core.guesthelper.GuestHelperBundle
import local.ringleader.core.guesthelper.GuestHelperClients
import local.ringleader.core.guesthelper.GuestHelperLink
import local.ringleader.core.guesthelper.GuestHelperPairing
import local.ringleader.core.guesthelper.GuestHelperPairings
import local.ringleader.core.guesthelper.GuestHelperRadioGateway
import local.ringleader.core.guesthelper.GuestHelperStore
import local.ringleader.core.guesthelper.RoutedGuestBluetoothGateway
import local.ringleader.core.guesthelper.RoutedGuestRadioGateway
import local.ringleader.core.guesthelper.bundleFor
import local.ringleader.core.guesthelper.chosenGuestHelperBundle
import local.ringleader.core.guesthelper.finder
import local.ringleader.core.guesthelper.guestHelperPlace
import local.ringleader.core.guesthelper.packagedGuestHelperBundles
import local.ringleader.core.integrations.ServiceWire

/** The button every refusal the Engine would get around carries. Once paired, the buttons simply work. */
internal const val ENGINE_SETUP_LABEL = "Set up RingMASTER Engine"

/**
 * The RingMASTER Engine in this app: the pairings, the bundles it can install, and which guest's setup dialog is open.
 * There is one for the whole app ([GuestEngine.hub]); tests build their own.
 */
class EngineHub(
    val pairings: GuestHelperPairings,
    val wire: ServiceWire,
    /** The bundles this build packed, read once. */
    packaged: () -> List<GuestHelperBundle>,
    val installer: GuestAgentInstaller = GuestAgentInstaller(),
) {
    val pairing = GuestHelperPairing(wire)
    private val packedBundles by lazy(packaged)
    private val changes = MutableStateFlow(0)
    private val open = MutableStateFlow<Guest?>(null)
    private val chosen = MutableStateFlow<GuestHelperBundle?>(null)

    /** Moves on every pairing or unpairing, so a view that shows a pairing reads it again. */
    val version: StateFlow<Int> = changes.asStateFlow()
    /** The guest whose setup dialog is open, or null. */
    val setup: StateFlow<Guest?> = open.asStateFlow()
    /** A bundle file a developer chose in the dialog for this run. */
    val chosenBundle: StateFlow<GuestHelperBundle?> = chosen.asStateFlow()

    val packagedBundles: List<GuestHelperBundle> get() = packedBundles

    fun pairingFor(guest: Guest): GuestHelperLink? = pairings.forPlace(guestHelperPlace(guest))
    fun offer(guest: Guest) { open.value = guest }
    fun closeSetup() { open.value = null }
    fun changed() { changes.value += 1 }
    fun choose(bundle: GuestHelperBundle) { chosen.value = bundle }

    /** The bundle to install in a guest whose `uname -m` is [machine]: the developer's choice first, else this build's. */
    fun bundleFor(machine: String?): GuestHelperBundle? = chosen.value ?: bundleFor(packedBundles, machine)

    private val clients = GuestHelperClients { link -> pairings.client(link) }

    /** The probe and Wi-Fi scan for the workspace: a paired Engine first, [agent] otherwise. */
    fun radioGateway(agent: GuestRadioGateway = LiveGuestRadioGateway()): GuestRadioGateway =
        RoutedGuestRadioGateway(GuestHelperRadioGateway(pairings.finder(), clients), agent, pairings.finder())

    /** The Bluetooth scan inside a guest for the workspace, by the same rule. */
    fun bluetoothGateway(agent: GuestBluetoothGateway = LiveGuestBluetoothGateway()): GuestBluetoothGateway =
        RoutedGuestBluetoothGateway(GuestHelperBluetoothGateway(pairings.finder(), clients), agent, pairings.finder())
}

/** The app's one [EngineHub]. */
internal object GuestEngine {
    val hub: EngineHub by lazy { create(SystemVault.instance) }

    fun create(vault: SecretVault): EngineHub {
        val wire = ServiceWire("RingMASTER/" + userAgentVersion(AppVersion.value))
        return EngineHub(GuestHelperPairings(GuestHelperStore(), vault, wire), wire, ::bundles)
    }

    /** The version as the wire's user agent allows it: letters, digits and . + - only. */
    internal fun userAgentVersion(version: String): String =
        version.filter { it.isLetterOrDigit() || it in ".+-" }.take(32).ifEmpty { "0" }

    private fun bundles(): List<GuestHelperBundle> {
        val dir = System.getProperty("compose.application.resources.dir")?.let { Path.of(it, "ringmaster-engine") }
        val packed = packagedGuestHelperBundles(dir)
        // A developer may name one bundle file for this run. It is shown as not a release file, like a chosen one.
        val named = System.getenv("RINGMASTER_ENGINE_BUNDLE")?.takeIf { it.isNotBlank() } ?: return packed
        val result = chosenGuestHelperBundle(Path.of(named))
        result.exceptionOrNull()?.let { GuestEngineProblems.report("RINGMASTER_ENGINE_BUNDLE: " + (it.message ?: "not a bundle.")) }
        return packed + listOfNotNull(result.getOrNull())
    }
}
