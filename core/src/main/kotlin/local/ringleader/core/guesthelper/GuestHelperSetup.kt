package local.ringleader.core.guesthelper

import local.ringleader.core.Guest
import local.ringleader.core.GuestRefusal

/*
 * Setting up the Engine for one guest: which ways in are open, best first, and the pairing that follows an install through
 * the guest agent, which needs no question because the fingerprint and token came back over Proxmox itself.
 */

/** A way to get the Engine into a guest, best first. */
enum class GuestHelperWay(val label: String) {
    /** The app sends the bundle and runs the installer through the QEMU guest agent, then pairs by itself. */
    GuestAgent("Through the guest agent"),
    /** The app serves the bundle once on this computer; the user pastes one command in the guest. */
    OneTimeLink("One-time link from this computer"),
    /** The guest downloads the bundle from the public release page; the user pastes the command. */
    ReleaseCommand("Command to copy"),
}

/**
 * The ways open for [guest], best first. [agentRefusal] is why the guest agent cannot be used (null: it may be tried;
 * the token's permission shows only when it is). Without a bundle nothing can be installed. The release page has only the
 * bundles this app's own release packed, so a bundle a developer chose cannot be fetched from there.
 */
fun guestHelperWays(guest: Guest, agentRefusal: GuestRefusal?, bundle: GuestHelperBundle?): List<GuestHelperWay> {
    if (bundle == null) return emptyList()
    return buildList {
        if (guest.kind == "VM" && agentRefusal == null) add(GuestHelperWay.GuestAgent)
        add(GuestHelperWay.OneTimeLink)
        if (bundle.packaged) add(GuestHelperWay.ReleaseCommand)
    }
}

const val GUEST_HELPER_NO_ADDRESS = "The installer did not say where the guest can be reached. Enter its address to pair."

/**
 * Pairs [guest] with what its installer printed: the certificate is read on the network and must be the one the installer
 * made, then the token is checked and the pairing saved. [host] defaults to the first address the guest printed.
 * The token in [installed] is wiped either way.
 */
fun pairFromInstaller(
    pairing: GuestHelperPairing,
    pairings: GuestHelperPairings,
    guest: Guest,
    installed: GuestHelperInstalled,
    host: String? = installed.addresses.firstOrNull(),
): GuestHelperLink {
    val token = installed.token ?: throw GuestHelperException(GuestHelperFailure.WrongToken,
        "The Engine was already installed, so no new token was shown. Remove it and install again, or pair with its token.")
    try {
        val address = host ?: throw GuestHelperException(GuestHelperFailure.Unreachable, GUEST_HELPER_NO_ADDRESS)
        val seen = pairing.readCertificate(address, installed.port, expected = installed.fingerprint)
        // The user confirmed the install; the fingerprint came back over Proxmox and must match, which confirm() checks.
        val pin = pairing.confirm(seen, userConfirmed = true)
        val identity = pairing.checkToken(pin, token.copyOf())
        val link = pairing.link(guestHelperPlace(guest), pin, identity)
        return pairings.save(link, token.copyOf()).getOrThrow()
    } finally {
        token.fill(Char(0))
    }
}
