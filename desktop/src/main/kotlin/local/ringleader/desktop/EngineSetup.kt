package local.ringleader.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.ringleader.core.Guest
import local.ringleader.core.GuestAgentFailure
import local.ringleader.core.HardwareSummary
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState
import local.ringleader.core.guesthelper.ConfirmedPin
import local.ringleader.core.guesthelper.GUEST_HELPER_PORT
import local.ringleader.core.guesthelper.GuestHelperBundle
import local.ringleader.core.guesthelper.GuestHelperCertificate
import local.ringleader.core.guesthelper.GuestHelperException
import local.ringleader.core.guesthelper.GuestHelperInstalled
import local.ringleader.core.guesthelper.GuestHelperLink
import local.ringleader.core.guesthelper.GuestHelperProgress
import local.ringleader.core.guesthelper.OneTimeLink
import local.ringleader.core.guesthelper.guestHelperPlace
import local.ringleader.core.guesthelper.pairFromInstaller
import local.ringleader.core.readableError

/** Where the setup dialog is. */
internal sealed interface EngineStep {
    /** The ways in, best first. */
    data object Choose : EngineStep
    data class Installing(val progress: GuestHelperProgress) : EngineStep
    /** The one-time link is open; the command waits to be pasted in the guest. */
    data class Link(val link: OneTimeLink) : EngineStep
    /** The command for a guest that downloads from the release page. */
    data class Command(val command: String) : EngineStep
    /** Pairing by hand: the address and port to read the certificate from. */
    data object Address : EngineStep
    data class Fingerprint(val seen: GuestHelperCertificate) : EngineStep
    data class Token(val pin: ConfirmedPin) : EngineStep
    data class Done(val link: GuestHelperLink) : EngineStep
}

/** The first address of a guest as the inventory shows it ("192.0.2.7/24 (Tailscale)"), as a bare address. */
internal fun guestAddress(state: WorkspaceState, guest: Guest): String? {
    val shown = state.inspection?.takeIf { it.resource == guest.resource }?.addresses?.value.orEmpty()
    val bare = shown.map { it.substringBefore(' ').substringBefore('/') }
    return bare.firstOrNull { '.' in it } ?: bare.firstOrNull()
}

/** One sentence for a failure of any setup step. */
internal fun setupFailure(e: Exception): String = when (e) {
    is GuestAgentFailure -> e.refusal.sentence
    is GuestHelperException -> e.message ?: "The RingMASTER Engine did not answer as expected."
    is IOException -> readableError(e)
    else -> e.message ?: "The setup step failed."
}

/**
 * The setup of the Engine for one guest, step by step. Each step that reaches the guest or the network runs off the
 * window's thread and comes back with the next step or a sentence in [error]. Nothing is installed or trusted without
 * the user's click on that step.
 */
internal class EngineSetup(
    private val hub: EngineHub,
    private val workspace: Workspace,
    private val scope: CoroutineScope,
    val guest: Guest,
    hardware: HardwareSummary?,
    address: String?,
) {
    var step by mutableStateOf<EngineStep>(EngineStep.Choose)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var host by mutableStateOf(address.orEmpty())
    var port by mutableStateOf(GUEST_HELPER_PORT.toString())

    /** The guest's architecture from its configuration (an arm VM says so); x86_64 otherwise. */
    val machine: String? = hardware?.fields?.get("arch")

    /** Kept until pairing succeeds after an agent install, so a second address can be tried; wiped on [close]. */
    private var installed: GuestHelperInstalled? = null

    private fun run(block: suspend () -> EngineStep?) {
        busy = true
        error = null
        scope.launch {
            try { block()?.let { step = it } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = setupFailure(e) }
            finally { busy = false }
        }
    }

    /** The guest agent way: one click sent all of it. On success the pairing is made from what the installer printed. */
    fun installThroughAgent(bundle: GuestHelperBundle) = run {
        step = EngineStep.Installing(GuestHelperProgress(0, 1))
        val done = withContext(Dispatchers.IO) {
            workspace.onGuest(guest) { connection, live ->
                hub.installer.install(connection, live, bundle) { p -> scope.launch { step = EngineStep.Installing(p) } }
            }
        }
        installed = done
        done.addresses.firstOrNull()?.let { host = it }
        pairInstalled()
    }

    /** After an agent install: pairs at [host] with the installer's fingerprint and token. */
    fun retryPairing() = run { pairInstalled() }

    private suspend fun pairInstalled(): EngineStep {
        val done = installed ?: error("Install first.")
        val attempt = GuestHelperInstalled(done.version, done.port, done.fingerprint, done.token?.copyOf(), done.addresses)
        val link = withContext(Dispatchers.IO) { pairFromInstaller(hub.pairing, hub.pairings, guest, attempt, host.trim()) }
        wipeInstalled()
        hub.changed()
        return EngineStep.Done(link)
    }

    fun openLink(bundle: GuestHelperBundle) = run {
        val address = host.trim().ifEmpty { error("Enter the guest's address first. The link is served on the side of this computer facing it.") }
        EngineStep.Link(withContext(Dispatchers.IO) { OneTimeLink.open(bundle, address) })
    }

    fun showCommand(command: String) { step = EngineStep.Command(command) }

    /** From a link or a command to pairing by hand. The link stays open until it is used or expires. */
    fun toPairing() { error = null; step = EngineStep.Address }

    fun readCertificate() = run {
        val number = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: error("The port is a number from 1 to 65535.")
        EngineStep.Fingerprint(withContext(Dispatchers.IO) { hub.pairing.readCertificate(host.trim(), number) })
    }

    /** The user's answer to "Is this the fingerprint the installer showed?". No is the end: nothing was trusted. */
    fun answer(seen: GuestHelperCertificate, matches: Boolean) {
        error = null
        if (!matches) {
            error = "Pairing stopped. Nothing was trusted. Check that the address is the guest's, then try again."
            step = EngineStep.Address
            return
        }
        step = EngineStep.Token(hub.pairing.confirm(seen, userConfirmed = true))
    }

    /** Checks [token] (wiped) and saves the pairing. */
    fun pair(pin: ConfirmedPin, token: CharArray) = run {
        val link = withContext(Dispatchers.IO) {
            try {
                // checkToken wipes what it is given, so it gets a copy; save wipes the original.
                val identity = hub.pairing.checkToken(pin, token.copyOf())
                hub.pairings.save(hub.pairing.link(guestHelperPlace(guest), pin, identity), token).getOrThrow()
            } finally {
                token.fill(Char(0))
            }
        }
        hub.changed()
        EngineStep.Done(link)
    }

    fun back() { error = null; step = EngineStep.Choose }

    private fun wipeInstalled() {
        installed?.token?.fill(Char(0))
        installed = null
    }

    fun close() {
        (step as? EngineStep.Link)?.link?.close()
        wipeInstalled()
        hub.closeSetup()
    }
}
