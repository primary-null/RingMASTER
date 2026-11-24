package local.ringleader.core.guesthelper

import local.ringleader.core.RELEASE_PAGE

/*
 * What installing the Engine means, in the words the setup dialog shows before anything is sent, and the shell lines each
 * way in runs. The installer itself (native/ringmaster-engine/install/install.sh) prints the same plan in the guest.
 */

/** What the Engine is and is not, in a few plain sentences. */
val GUEST_HELPER_ABOUT = listOf(
    "The $GUEST_HELPER_NAME is a small program that runs inside the guest and answers RingMASTER over an encrypted connection.",
    "It only reads: which Bluetooth and Wi-Fi radios the guest has, and a scan when you ask for one.",
    "It has no shell and runs nothing it is sent. It never reads a saved Wi-Fi password.",
    "It runs as its own account, without root.",
)

/** Exactly what the installer puts where. */
val GUEST_HELPER_PLAN = listOf(
    "A system account ringmaster-engine, with no home and no login shell.",
    "The program in /usr/local/lib/ringmaster-engine/.",
    "Settings, its own certificate, key and pairing token in /etc/ringmaster-engine/.",
    "A service (systemd or OpenRC) that answers on TCP port $GUEST_HELPER_PORT, only from local networks and tailnets.",
)

const val GUEST_HELPER_UNINSTALL = "sh /usr/local/lib/ringmaster-engine/uninstall.sh"

/** What the installer printed at its end. [token] is set only when it made a new one; the caller wipes it. */
class GuestHelperInstalled(
    val version: String,
    val port: Int,
    val fingerprint: String,
    val token: CharArray?,
    /** The guest's own addresses, printed by the install script, best first. */
    val addresses: List<String>,
) {
    override fun toString() = "GuestHelperInstalled($version, $port, ${fingerprint.take(8)}..., token hidden)"
}

private val FIELD = Regex("([a-z0-9]+)=(\\S+)")
private const val PAIRING = "RINGMASTER-ENGINE-PAIRING "
private const val READY = "RINGMASTER-ENGINE-READY "
private const val ADDRESSES = "RINGMASTER-ENGINE-ADDRESSES"
const val GUEST_HELPER_REMOVED = "RINGMASTER-ENGINE-REMOVED"

/** The installer's machine-readable last lines, or null when they are not there (it stopped, or something else answered). */
fun parseInstallerOutput(text: String): GuestHelperInstalled? {
    val lines = text.lineSequence().map { it.trim() }.toList()
    val result = lines.lastOrNull { it.startsWith(PAIRING) || it.startsWith(READY) } ?: return null
    val fields = FIELD.findAll(result).associate { it.groupValues[1] to it.groupValues[2] }
    val version = fields["version"]?.takeIf { it.matches(Regex("[0-9.]{1,32}")) } ?: return null
    val port = fields["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    val fingerprint = fields["sha256"]?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
    val token = fields["token"]?.toCharArray()
    val addresses = lines.lastOrNull { it.startsWith(ADDRESSES) }?.removePrefix(ADDRESSES).orEmpty()
        .split(' ').map { it.trim() }.filter { it.isNotEmpty() && it.length <= 45 }
    return GuestHelperInstalled(version, port, fingerprint, token, preferredAddresses(addresses))
}

/** IPv4 before IPv6, and no link-local or loopback address: the one RingMASTER can reach first. */
internal fun preferredAddresses(addresses: List<String>): List<String> {
    val usable = addresses.filterNot { it.startsWith("127.") || it == "::1" || it.lowercase().startsWith("fe80:") }
    return usable.sortedBy { if (':' in it) 1 else 0 }
}

private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"

/**
 * The shell that unpacks a bundle at [bundlePath] and runs its installer, after checking the bundle's [sha256]. It then
 * prints the guest's addresses, so the app knows where to pair. [assumeYes] is for the guest agent way, where the user
 * already confirmed in the app; a person pasting into a console answers the installer's own question.
 */
fun installScript(bundlePath: String, sha256: String, assumeYes: Boolean): String {
    require(sha256.matches(Regex("[0-9a-f]{64}"))) { "The bundle hash is 64 hex digits." }
    val yes = if (assumeYes) " --yes" else ""
    return listOf(
        "set -e",
        privateInstallDirectory(),
        "cp -- ${quote(bundlePath)} \"\$work/bundle.tgz\"",
        stagedInstallScript(sha256, yes),
    ).joinToString("\n")
}

/** Create the private directory before any bundle bytes are staged. */
internal fun privateInstallDirectory() = listOf(
    "umask 077",
    "work=$(mktemp -d /var/tmp/ringmaster-engine.XXXXXXXXXXXX)",
    "trap 'rm -rf -- \"\$work\"' EXIT",
).joinToString("\n")

/** Runs only inside the directory created and owned by this install. */
internal fun stagedInstallScript(sha256: String, yes: String): String {
    require(sha256.matches(Regex("[0-9a-f]{64}"))) { "The bundle hash is 64 hex digits." }
    return listOf(
        "printf '%s  %s\\n' '$sha256' \"\$work/bundle.tgz\" | sha256sum -c -",
        "tar -xzf \"\$work/bundle.tgz\" -C \"\$work\"",
        "rm -f -- \"\$work/bundle.tgz\"",
        "sh \"\$work\"/ringmaster-engine-*/install.sh$yes",
        "printf '%s %s\\n' $ADDRESSES \"$(hostname -I 2>/dev/null || true)\"",
    ).joinToString("\n")
}

/** The one line a person pastes into the guest for the one-time link. It downloads, checks the hash, and installs. */
fun linkCommand(url: String, sha256: String): String {
    val fetch = "(wget -qO \"\$work/bundle.tgz\" ${quote(url)} || curl -fsSo \"\$work/bundle.tgz\" ${quote(url)})"
    val script = listOf("set -e", privateInstallDirectory(), fetch, stagedInstallScript(sha256, ""))
        .joinToString("\n").replace("\n", "; ")
    return "( $script )"
}

/** The command for a guest with internet: the bundle from the public release of this app's version, checked by its hash. */
fun releaseCommand(appVersion: String, bundle: GuestHelperBundle): String {
    val name = bundle.file.fileName.toString()
    val url = RELEASE_PAGE.removeSuffix("/latest") + "/download/v$appVersion/$name"
    return linkCommand(url, bundle.sha256)
}
