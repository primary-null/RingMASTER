package local.ringleader.core

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** The suffix of OpenSSH's own key type names (security-key types), kept apart so the names are not read as addresses. */
private const val OPENSSH_DOMAIN = "openssh" + ".com"

/*
 * "Open SSH" over Tailscale SSH (the SSH contract): the system terminal runs the system OpenSSH client against a tailnet
 * peer's own address, checking the host key against a known-hosts file written fresh from the keys the tailnet publishes
 * for that one peer. No trust on first use, no key or password handling, no embedded client. Everything is an argument
 * vector; nothing goes through a shell.
 */

/** Shown on and beside the action. */
const val SSH_ACTION_HELP = "SSH through Tailscale, verified by your tailnet"

/**
 * One peer's published SSH host keys, read at the moment of launch. [keys] are public keys, but they identify the machine:
 * they are never stored in state, traced or logged, and [toString] leaves them out.
 */
class SshPeerKeys(val peerId: String, val hostName: String, val dnsName: String?, val addresses: List<String>, val online: Boolean, val keys: List<String>) {
    override fun toString() = "SshPeerKeys(peer=$peerId, keys=${keys.size})"
}

/** The full `sshHostKeys` of the peer with [peerId] from `tailscale status --json`, or null when it is not there. */
fun parsePeerSshHostKeys(json: String, peerId: String): SshPeerKeys? {
    val root = Json.parseToJsonElement(json) as? JsonObject ?: return null
    val peer = (root["Peer"] as? JsonObject)?.values?.mapNotNull { it as? JsonObject }?.firstOrNull { it.vs("ID") == peerId } ?: return null
    fun strings(k: String) = peer.va(k).mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
    return SshPeerKeys(peerId, peer.vs("HostName").orEmpty(), peer.vs("DNSName")?.trimEnd('.'), strings("TailscaleIPs").sortedBy { ':' in it },
        peer.vs("Online") == "true", strings("sshHostKeys").map { it.trim() }.filter { it.isNotEmpty() })
}

// ---------------------------------------------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------------------------------------------

/** A login name ssh can take as one argument and never as an option: lower-case POSIX style, up to 32 characters. */
val SSH_USER_PATTERN = Regex("[a-z_][a-z0-9_-]{0,31}")
fun validSshUser(user: String) = SSH_USER_PATTERN.matches(user)
const val SSH_USER_RULE = "Use a user name of lower-case letters, digits, '_' and '-', starting with a letter or '_', up to 32 characters."

/** Host key types OpenSSH knows; anything else is not written. */
val SSH_HOST_KEY_TYPES = setOf("ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521", "ssh-rsa",
    "sk-ssh-ed25519@$OPENSSH_DOMAIN", "sk-ecdsa-sha2-nistp256@$OPENSSH_DOMAIN")
private val BASE64 = Regex("[A-Za-z0-9+/]{16,16384}={0,2}")

/**
 * (type, base64) for one published key ("ssh-ed25519 AAAA… comment"), or null when it is not a well-formed OpenSSH public
 * key: a known type, valid base64, and the key blob itself naming the same type (SSH wire format: uint32 length, type).
 * The comment is dropped.
 */
fun sshHostKeyEntry(key: String): Pair<String, String>? {
    if (key.any { it == '\n' || it == '\r' }) return null
    val parts = key.trim().split(Regex("\\s+"))
    if (parts.size < 2) return null
    val (type, blob) = parts
    if (type !in SSH_HOST_KEY_TYPES || !BASE64.matches(blob)) return null
    val bytes = runCatching { Base64.getDecoder().decode(blob) }.getOrNull() ?: return null
    if (bytes.size < 4 + type.length) return null
    val length = ((bytes[0].toInt() and 0xff) shl 24) or ((bytes[1].toInt() and 0xff) shl 16) or ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff)
    if (length != type.length || String(bytes, 4, length, Charsets.US_ASCII) != type) return null
    return type to blob
}

private val DNS_ALIAS = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}[A-Za-z0-9])?)*")

/**
 * The name ssh looks the host key up under (`HostKeyAlias`): the peer's MagicDNS name when it is a plain DNS name, else
 * the address. Never contains a space, comma, wildcard, `!`, `|` or brackets (all special in known-hosts files).
 */
fun sshHostAlias(dnsName: String?, address: String): String =
    dnsName?.trimEnd('.')?.takeIf { it.length <= 253 && DNS_ALIAS.matches(it) }?.lowercase() ?: ipLiteral(address)!!.hostAddress

/** The known-hosts file for one peer: "alias type base64" per valid key. Throws when no key is usable. */
fun knownHostsText(alias: String, keys: List<String>): String {
    require(alias.isNotEmpty() && alias.none { it.isWhitespace() || it in ",*?!|[]#" }) { "The machine's name cannot be used for SSH." }
    val lines = keys.mapNotNull(::sshHostKeyEntry).distinct().map { (type, blob) -> "$alias $type $blob" }
    require(lines.isNotEmpty()) { "The machine publishes no usable SSH host key." }
    return lines.joinToString("\n", postfix = "\n")
}

/**
 * The `UserKnownHostsFile` value ssh splits on whitespace and expands `%` tokens and `~` in. The absolute path is used when it
 * has nothing ssh would split or misread (`%` doubled; forward slashes on Windows); otherwise `~/<path under home>` when
 * that part is plain. Null when neither works (then nothing is launched).
 */
fun knownHostsArgument(file: Path, home: Path, windows: Boolean): String? {
    fun plain(text: String) = text.isNotEmpty() && text.none { it.isWhitespace() || it.isISOControl() || it in "\"'\\;" }
    fun slash(p: Path) = if (windows) p.toString().replace('\\', '/') else p.toString()
    val absolute = slash(file.toAbsolutePath().normalize())
    if (plain(absolute)) return absolute.replace("%", "%%")
    val h = home.toAbsolutePath().normalize()
    val f = file.toAbsolutePath().normalize()
    if (f.startsWith(h)) {
        val rest = h.relativize(f).toString().replace('\\', '/')
        if (plain(rest) && !rest.startsWith("..")) return "~/" + rest.replace("%", "%%")
    }
    return null
}

/**
 * The OpenSSH client's argument vector. The destination is always the peer's tailnet IP literal and the user goes through
 * `-l` after [validSshUser], so neither can be read as an option. Host key checking: the app's known-hosts file only,
 * `StrictHostKeyChecking=yes` (never adds or accepts an unknown key), `HostKeyAlias` so the lookup uses the peer's name,
 * `UpdateHostKeys=no` (the file stays as written), `CheckHostIP=no` and `CanonicalizeHostname=no` (the address is not
 * looked up or added separately). Matches what `tailscale ssh` passes (cmd/tailscale/cli/ssh.go), without its ProxyCommand.
 */
fun sshArguments(ssh: String, knownHosts: String, alias: String, address: String, user: String): List<String> {
    require(validSshUser(user)) { SSH_USER_RULE }
    val ip = ipLiteral(address) ?: throw IllegalArgumentException("Not an IP address.")
    require(!address.startsWith("-") && !alias.startsWith("-"))
    return listOf(ssh, "-l", user,
        "-o", "UserKnownHostsFile=$knownHosts",
        "-o", "StrictHostKeyChecking=yes",
        "-o", "HostKeyAlias=$alias",
        "-o", "UpdateHostKeys=no",
        "-o", "CheckHostIP=no",
        "-o", "CanonicalizeHostname=no",
        ip.hostAddress)
}

// ---------------------------------------------------------------------------------------------------------------
// Terminals
// ---------------------------------------------------------------------------------------------------------------

/** How a terminal takes the program to run as separate arguments (never one shell string). */
enum class TerminalStyle {
    /** `term -e prog args…` (Debian's x-terminal-emulator policy: execvp, no shell; xterm, konsole, alacritty: must be last). */
    DashE,
    /** `term -- prog args…` (gnome-terminal, ptyxis). */
    DoubleDash,
    /** `term -x prog args…` (xfce4-terminal, mate-terminal: "the remainder of the command line"). */
    DashX,
    /** `term prog args…` (kitty, foot, xdg-terminal-exec). */
    Positional,
    /** `wt.exe new-tab --title <t> prog args…` (Windows Terminal). */
    WindowsTerminal,
    /** `conhost.exe prog args…` (a classic console window). */
    Conhost,
}

data class TerminalChoice(val name: String, val path: String, val style: TerminalStyle)

/** Linux terminals in the order tried: the desktop's default first, then the common ones. */
val LINUX_TERMINALS = listOf(
    "x-terminal-emulator" to TerminalStyle.DashE, "xdg-terminal-exec" to TerminalStyle.Positional,
    "gnome-terminal" to TerminalStyle.DoubleDash, "ptyxis" to TerminalStyle.DoubleDash, "konsole" to TerminalStyle.DashE,
    "xfce4-terminal" to TerminalStyle.DashX, "mate-terminal" to TerminalStyle.DashX, "kitty" to TerminalStyle.Positional,
    "alacritty" to TerminalStyle.DashE, "foot" to TerminalStyle.Positional, "xterm" to TerminalStyle.DashE,
)

/** `%SystemRoot%\System32\OpenSSH\ssh.exe`: the OpenSSH client Windows installs as an optional feature. */
fun windowsSshPath(env: (String) -> String?): String = windowsRoot(env) + "\\System32\\OpenSSH\\ssh.exe"
private fun windowsRoot(env: (String) -> String?) = env("SystemRoot")?.takeIf { it.isNotBlank() } ?: env("WINDIR")?.takeIf { it.isNotBlank() } ?: "C:\\Windows"
/** Windows Terminal's app execution alias. */
fun windowsTerminalPath(env: (String) -> String?): String? = env("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { it.trimEnd('\\') + "\\Microsoft\\WindowsApps\\wt.exe" }

const val SSH_MISSING_WINDOWS = "The OpenSSH client is not installed. Add \"OpenSSH Client\" in Settings \u203a System \u203a Optional features, then try again."
const val SSH_MISSING_LINUX = "The ssh program is not installed. Install your distribution's OpenSSH client package, then try again."
val TERMINAL_MISSING_LINUX = "No terminal program was found. RingMASTER looks for ${LINUX_TERMINALS.joinToString(", ") { it.first }}."

/** The terminal to use: Windows Terminal, else a console window; on Linux the first of [LINUX_TERMINALS] found. */
fun findTerminal(windows: Boolean, env: (String) -> String?, exists: (String) -> Boolean, which: (String) -> String?): TerminalChoice? =
    if (windows) windowsTerminalPath(env)?.takeIf(exists)?.let { TerminalChoice("Windows Terminal", it, TerminalStyle.WindowsTerminal) }
        ?: (windowsRoot(env) + "\\System32\\conhost.exe").takeIf(exists)?.let { TerminalChoice("a console window", it, TerminalStyle.Conhost) }
    else LINUX_TERMINALS.firstNotNullOfOrNull { (name, style) -> which(name)?.takeIf(::absoluteProgram)?.let { TerminalChoice(name, it, style) } }

/** An absolute program path ("/usr/bin/ssh", "C:\…"); a relative one is never started. */
internal fun absoluteProgram(path: String) = path.startsWith("/") || runCatching { Path.of(path).isAbsolute }.getOrDefault(false)

/** The full argument vector: [terminal] running [program]. */
fun terminalCommand(terminal: TerminalChoice, program: List<String>): List<String> {
    require(program.isNotEmpty())
    // Windows Terminal splits its own command line on ';'. Nothing the app builds has one; refuse rather than misparse.
    if (terminal.style == TerminalStyle.WindowsTerminal) require(program.none { ';' in it }) { "This command cannot be passed to Windows Terminal." }
    return when (terminal.style) {
        TerminalStyle.DashE -> listOf(terminal.path, "-e") + program
        TerminalStyle.DoubleDash -> listOf(terminal.path, "--") + program
        TerminalStyle.DashX -> listOf(terminal.path, "-x") + program
        TerminalStyle.Positional -> listOf(terminal.path) + program
        TerminalStyle.WindowsTerminal -> listOf(terminal.path, "new-tab", "--title", "SSH") + program
        TerminalStyle.Conhost -> listOf(terminal.path) + program
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Which peer a node or guest is
// ---------------------------------------------------------------------------------------------------------------

sealed interface SshAvailability {
    /** The peer runs Tailscale SSH: Open SSH is offered. */
    data class Ready(val peer: TailscalePeer, val address: String) : SshAvailability
    /** The address is a peer that publishes no SSH host keys: plain sshd would need trust on first use. */
    data class NoKeys(val peer: TailscalePeer) : SshAvailability
    data class Offline(val peer: TailscalePeer) : SshAvailability
    /** The address lies behind a subnet router: there is no Tailscale on the server itself. */
    data class ThroughRouter(val route: TailnetSubnetRoute) : SshAvailability
    /** Tailscale on this computer cannot be used (not installed, not running, userspace mode). */
    data class Unavailable(val reason: String) : SshAvailability
    /** None of the addresses is on the tailnet. */
    data object NotOnTailnet : SshAvailability
}

/**
 * Which tailnet peer a node or guest is, by address only: one of [addresses] equal to a peer's tailnet address, or — for a
 * node — the peer whose host name is [nodeName] when the connection itself goes to that peer ([connectionHost] is the
 * peer's address or MagicDNS name). Names alone are never enough. Location-based exit nodes are never offered.
 */
fun sshAvailability(status: TailscaleStatus?, addresses: List<String>, nodeName: String? = null, connectionHost: String? = null,
    accept: AcceptRoutesReading? = null): SshAvailability {
    if (status == null) return SshAvailability.Unavailable("Tailscale is not available on this computer.")
    if (!status.running) return SshAvailability.Unavailable("Tailscale is not running on this computer.")
    if (status.userspace) return SshAvailability.Unavailable(TAILSCALE_USERSPACE_NOTE)
    val peers = status.peers.filter { it.location == null }
    val byAddress = peers.firstOrNull { p -> p.addresses.any { a -> addresses.any { sameAddress(a, it) } } }
    val byConnection = if (byAddress == null && nodeName != null && connectionHost != null) {
        val host = connectionHost.trim().removePrefix("[").removeSuffix("]").trimEnd('.').lowercase()
        peers.firstOrNull { p -> p.addresses.any { sameAddress(it, host) } || p.dnsName?.lowercase() == host }
            ?.takeIf { p -> p.hostName.equals(nodeName, true) || p.dnsName?.substringBefore('.')?.equals(nodeName, true) == true }
    } else null
    val peer = byAddress ?: byConnection
    if (peer != null) {
        val address = peer.addresses.firstOrNull { ScanRules.ipv4(it) != null } ?: peer.addresses.firstOrNull()
        return when {
            peer.sshHostKeyCount == 0 -> SshAvailability.NoKeys(peer)
            !peer.online -> SshAvailability.Offline(peer)
            address == null -> SshAvailability.NoKeys(peer)
            else -> SshAvailability.Ready(peer, address)
        }
    }
    val routes = tailnetSubnetRoutes(status).filter { !it.own }
    val routed = addresses.firstNotNullOfOrNull { a -> ScanRules.ipv4(a.substringBefore('/'))?.let { n -> routes.firstOrNull { r -> parseIpv4Net(r.cidr)?.contains(n) == true } } }
    if (routed != null) return SshAvailability.ThroughRouter(routed)
    return SshAvailability.NotOnTailnet
}

/** The muted line shown where the button would be, or null when there is a button (or nothing to say). [hide] conceals names. */
fun sshNoButtonReason(a: SshAvailability, hide: (String) -> String = { it }): String? = when (a) {
    is SshAvailability.Ready -> null
    is SshAvailability.NoKeys -> "No Open SSH: ${hide(a.peer.hostName)} does not run Tailscale SSH (it publishes no host keys). Plain SSH would need a first-connection trust prompt, which RingMASTER does not do."
    is SshAvailability.Offline -> "No Open SSH: ${hide(a.peer.hostName)} runs Tailscale SSH but is offline."
    is SshAvailability.ThroughRouter -> "No Open SSH: this server is reached through the subnet router ${hide(a.route.routerName)}, so Tailscale is not on the server itself and there is no Tailscale SSH."
    is SshAvailability.Unavailable -> "No Open SSH: ${a.reason}"
    SshAvailability.NotOnTailnet -> null
}

/** What check mode looks like, said in the dialog. Nothing is built for it: Tailscale SSH sends it as an SSH banner. */
const val SSH_CHECK_MODE_NOTE = "If your tailnet's policy uses check mode, the terminal shows a link: open it in your browser and sign in, and the session continues."

// ---------------------------------------------------------------------------------------------------------------
// The known-hosts files and the launch
// ---------------------------------------------------------------------------------------------------------------

/**
 * One known-hosts file per launch, in the app's private data folder (beside the profile file), owner-only. Files older than
 * a day are removed when the store starts, and every file when the app exits ([removeAll]).
 */
class SshKnownHostsStore(val directory: Path = profileFile().resolveSibling("ssh-known-hosts"), private val clock: () -> Instant = Instant::now) {
    fun write(text: String): Path {
        createOwnerOnlyDirectories(directory)
        val file = directory.resolve("kh-${UUID.randomUUID()}.txt")
        if ("posix" in file.fileSystem.supportedFileAttributeViews()) Files.createFile(file, PosixFilePermissions.asFileAttribute(ownerFilePermissions()))
        else { Files.createFile(file); grantOwnerOnly(file) }
        Files.writeString(file, text, Charsets.US_ASCII)
        return file
    }

    /** Removes known-hosts files older than [maxAge]. Returns how many were removed. */
    fun cleanup(maxAge: Duration = Duration.ofDays(1)): Int = files().count { f ->
        val old = runCatching { Files.getLastModifiedTime(f, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(clock().minus(maxAge)) }.getOrDefault(false)
        old && runCatching { Files.deleteIfExists(f) }.getOrDefault(false)
    }

    fun removeAll(): Int = files().count { runCatching { Files.deleteIfExists(it) }.getOrDefault(false) }

    private fun files(): List<Path> = runCatching {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory)) emptyList()
        else Files.list(directory).use { s -> s.filter { it.fileName.toString().matches(Regex("kh-[0-9a-f-]{36}\\.txt")) && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList() }
    }.getOrDefault(emptyList())
}

/** What the launch needs from the computer. Tests replace every part. */
class SshLaunchEnvironment(
    val windows: Boolean = System.getProperty("os.name").startsWith("Windows", true),
    val env: (String) -> String? = System::getenv,
    val exists: (String) -> Boolean = { runCatching { Files.exists(Path.of(it)) }.getOrDefault(false) },
    val which: (String) -> String? = ::findExecutable,
    val home: Path = Path.of(System.getProperty("user.home")),
    val store: SshKnownHostsStore = SshKnownHostsStore(),
    /** Starts the terminal and returns its exit code if it ended within a moment, or null while it runs. */
    val start: (List<String>) -> Int? = ::startDetached,
)

/** The argument vector and the terminal used; the known-hosts file has been written. */
data class SshLaunchPlan(val command: List<String>, val terminal: TerminalChoice, val knownHosts: Path)

/**
 * Prepares a launch for [expected] (from the status the page showed) with [keys] read fresh right now: the peer must be the
 * same, online, still at the same tailnet address and still publish usable keys. Writes the known-hosts file. Throws with a
 * plain sentence otherwise; nothing is written then.
 */
fun planTailscaleSsh(expected: TailscalePeer, address: String, keys: SshPeerKeys?, user: String, e: SshLaunchEnvironment): SshLaunchPlan {
    require(validSshUser(user)) { SSH_USER_RULE }
    keys ?: error("The machine is no longer on the tailnet. Nothing was opened.")
    check(keys.peerId == expected.id) { "The machine changed. Nothing was opened." }
    check(keys.online) { "The machine is offline now. Nothing was opened." }
    check(keys.addresses.any { sameAddress(it, address) }) { "The machine's tailnet address changed. Nothing was opened." }
    val text = runCatching { knownHostsText(sshHostAlias(keys.dnsName, address), keys.keys) }.getOrElse { error("The machine no longer publishes usable SSH host keys. Nothing was opened.") }
    val ssh = if (e.windows) windowsSshPath(e.env).takeIf(e.exists) ?: error(SSH_MISSING_WINDOWS)
        else e.which("ssh")?.takeIf(::absoluteProgram) ?: error(SSH_MISSING_LINUX)
    val terminal = findTerminal(e.windows, e.env, e.exists, e.which) ?: error(if (e.windows) "No console window program was found." else TERMINAL_MISSING_LINUX)
    val file = e.store.write(text)
    try {
        val arg = knownHostsArgument(file, e.home, e.windows) ?: error("The app's data folder has characters ssh cannot take in a path. Nothing was opened.")
        return SshLaunchPlan(terminalCommand(terminal, sshArguments(ssh, arg, sshHostAlias(keys.dnsName, address), address, user)), terminal, file)
    } catch (t: Throwable) { runCatching { Files.deleteIfExists(file) }; throw t }
}

/** Starts [plan] once (never retried) and returns the sentence for the page. */
fun launchTailscaleSsh(plan: SshLaunchPlan, e: SshLaunchEnvironment): String {
    Trace.emit(TraceSource.Vpn, "ssh", "Open SSH in the system terminal")
    val exit = try { e.start(plan.command) } catch (x: Exception) {
        runCatching { Files.deleteIfExists(plan.knownHosts) }
        error("${plan.terminal.name.replaceFirstChar { it.uppercase() }} could not be started.")
    }
    if (exit != null && exit != 0) error("${plan.terminal.name.replaceFirstChar { it.uppercase() }} closed at once (exit $exit).")
    return "Opened in ${plan.terminal.name}."
}

/** Starts [command] with no input or output attached and waits a moment: the exit code if it ended, else null. */
fun startDetached(command: List<String>): Int? {
    val p = ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(java.io.File(if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null")))
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    return if (p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) p.exitValue() else null
}
