package local.ringleader.core

import java.nio.file.Path
import java.time.Instant

/**
 * VPN model for the computer RingMASTER runs on: Tailscale, WireGuard tunnels and the operating system's own VPN
 * profiles (Windows RAS / NetworkManager), in one shape. Read paths never change anything; [VpnBackend] mutations are
 * only called after the user confirms in the UI.
 */
enum class VpnKind { Tailscale, WireGuard, OpenVpn, IpSec, Other }
enum class VpnState { Up, Down, Connecting, NeedsLogin, Unknown }

/** One tunnel on this computer. */
data class VpnTunnel(
    /** Stable id: "tailscale", "wg:<name>", "nm:<uuid>", "ras:<name>", "nic:<interface>". */
    val id: String,
    val kind: VpnKind,
    val name: String,
    val state: VpnState,
    val interfaceName: String? = null,
    /** Addresses on the tunnel interface, plain or CIDR. */
    val addresses: List<String> = emptyList(),
    /** One plain line: "Exit node home-gw", "Endpoint 203.0.113.4:51820", "Server vpn.example.com". */
    val detail: String? = null,
    /** A backend exists to bring this tunnel up or down from the app. */
    val canToggle: Boolean = false,
    /** Toggling asks the operating system for administrator rights (UAC on Windows, polkit or pkexec on Linux). */
    val toggleNeedsAdmin: Boolean = false,
    /** Can be removed from the app (imported WireGuard tunnels). */
    val canRemove: Boolean = false,
    /**
     * Remove deletes the stored configuration. False for a Windows WireGuard tunnel RingMASTER did not import:
     * only the service is uninstalled and the configuration stays in WireGuard.
     */
    val removeDeletesConfig: Boolean = true,
    val rxBytes: Long? = null,
    val txBytes: Long? = null,
    val lastHandshakeEpoch: Long? = null,
)

data class TailscalePeer(
    val id: String,
    val hostName: String,
    /** MagicDNS name without the trailing dot: "pve1.example-tailnet.ts.net". */
    val dnsName: String?,
    val os: String?,
    val online: Boolean,
    /** Tailscale IPs, IPv4 first. */
    val addresses: List<String>,
    val exitNodeOption: Boolean,
    /** This peer is the exit node in use. */
    val exitNode: Boolean,
    /** The peer's home DERP region ("dfw"). Tailscale reports it even when the path is direct or idle. */
    val relay: String?,
    /** ip:port when the connection is direct, else null. */
    val direct: String?,
    val lastSeen: Instant?,
    val rxBytes: Long?,
    val txBytes: Long?,
    val tags: List<String> = emptyList(),
    /** Last successful handshake, when Tailscale reports one. */
    val lastHandshake: Instant? = null,
    /** Tailscale's "Active": traffic with this peer recently. Null when the status did not say. */
    val active: Boolean? = null,
    /** Advertised/allowed IPs (subnet routes or peer addresses, e.g. 192.168.20.0/24 or 100.x.y.z/32). */
    val allowedIps: List<String> = emptyList(),
    /** Tailscale's "PeerRelay": the peer relay carrying this peer's traffic ("ip:port:vni"), else null. */
    val peerRelay: String? = null,
    /** The peer's node key has expired ("Expired"). */
    val expired: Boolean = false,
    /** When the node key expired or will expire ("KeyExpiry"); null when it does not expire or was not reported. */
    val keyExpiry: Instant? = null,
    /** Subnet routes this peer is currently the primary router for ("PrimaryRoutes"). */
    val primaryRoutes: List<String> = emptyList(),
    /** The node is in this netmap because it was shared with this tailnet by someone else ("ShareeNode"). */
    val sharee: Boolean = false,
    /** The owning user's numeric id ("UserID"); null when not reported. */
    val userId: Long? = null,
    /** Set for location-based exit nodes such as Mullvad's ("Location"). */
    val location: TailscaleLocation? = null,
    /**
     * How many SSH host keys the peer publishes ("sshHostKeys"; non-empty when it runs Tailscale SSH) and their key
     * types ("ssh-ed25519"). The keys themselves are never kept.
     */
    val sshHostKeyCount: Int = 0,
    val sshHostKeyTypes: List<String> = emptyList(),
)

/** A location-based exit node's place, from Tailscale's "Location". */
data class TailscaleLocation(val country: String?, val countryCode: String?, val city: String?)

/** Tailscale's "ExitNodeStatus": the exit node in use. Absent when none is set. */
data class TailscaleExitNodeStatus(val id: String, val online: Boolean, val addresses: List<String>)

data class TailscaleStatus(
    /** Running, Stopped, NeedsLogin, NeedsMachineAuth, Starting, NoState. */
    val backendState: String,
    val version: String?,
    /** Tailnet display name from CurrentTailnet.Name. */
    val tailnet: String?,
    val magicDnsSuffix: String?,
    val self: TailscalePeer?,
    val peers: List<TailscalePeer>,
    /** Present when the client needs a browser login. */
    val authUrl: String?,
    /** Health warnings Tailscale reports, verbatim. */
    val health: List<String>,
    /**
     * Tailscale's "TUN": true when it uses a kernel interface, false in userspace-networking mode (then ordinary programs
     * on this computer cannot reach tailnet addresses), null when the status did not say.
     */
    val tun: Boolean? = null,
    val exitNodeStatus: TailscaleExitNodeStatus? = null,
    /** Peer entries that were not readable (not a JSON object) and were left out. */
    val unreadablePeers: Int = 0,
) {
    val running get() = backendState == "Running"
    val exitNode get() = peers.firstOrNull { it.exitNode }
    /** Userspace-networking mode: Tailscale runs, but the operating system has no route into the tailnet. */
    val userspace get() = tun == false
}

/** Said by the route tag and the VPN page when Tailscale reports userspace-networking mode. */
const val TAILSCALE_USERSPACE_NOTE = "Tailscale runs in userspace mode on this computer; ordinary programs cannot reach tailnet addresses."

enum class PeerTagTone { Warning, Caution, Info }

/** A short tag on a tailnet peer row. [help] explains it; no tag carries a name or an address. */
data class TailscalePeerTag(val label: String, val tone: PeerTagTone, val help: String)

/** Key expiry within this many days earns an "Expires in N d" tag. */
const val PEER_EXPIRY_WARNING_DAYS = 30L

/**
 * The facts a peer row shows as tags, most urgent first: an expired key, a key expiring within
 * [PEER_EXPIRY_WARNING_DAYS] days, Tailscale SSH, a node shared in by someone else, and a subnet router.
 */
fun tailscalePeerTags(peer: TailscalePeer, now: Instant): List<TailscalePeerTag> = buildList {
    val expiry = peer.keyExpiry
    if (peer.expired || (expiry != null && !expiry.isAfter(now))) add(TailscalePeerTag("Key expired", PeerTagTone.Warning,
        "This machine's Tailscale key has expired, so it cannot connect until it signs in again or its expiry is turned off in the admin console."))
    else if (expiry != null) {
        val days = java.time.Duration.between(now, expiry).toDays()
        val within = if (days == 0L) "today" else "in $days day${if (days == 1L) "" else "s"}"
        if (days <= PEER_EXPIRY_WARNING_DAYS) add(TailscalePeerTag(if (days == 0L) "Expires today" else "Expires in $days d", PeerTagTone.Caution,
            "This machine's Tailscale key expires $within. Servers usually have key expiry turned off in the admin console."))
    }
    if (peer.sshHostKeyCount > 0) add(TailscalePeerTag("SSH", PeerTagTone.Info, "Publishes SSH host keys: it runs Tailscale SSH."))
    if (peer.sharee) add(TailscalePeerTag("Shared", PeerTagTone.Info, "Shared into this tailnet by someone else."))
    if (subnetRoutes(peer).isNotEmpty()) add(TailscalePeerTag("Subnet router", PeerTagTone.Info, "The tailnet's current router for one or more local networks."))
}

/** A peer's primary subnet routes, without exit routes. */
internal fun subnetRoutes(peer: TailscalePeer): List<String> = peer.primaryRoutes.filterNot { it.endsWith("/0") }

data class WireGuardPeer(
    val publicKey: String,
    val endpoint: String?,
    val allowedIps: List<String>,
    val lastHandshakeEpoch: Long?,
    val rxBytes: Long,
    val txBytes: Long,
    val keepaliveSeconds: Int?,
)

data class WireGuardInterface(val name: String, val publicKey: String?, val listenPort: Int?, val peers: List<WireGuardPeer>)

data class VpnSnapshot(
    val at: Instant,
    val tunnels: List<VpnTunnel>,
    val tailscaleInstalled: Boolean,
    /** Null when Tailscale is not installed or its status could not be read (see [notes]). */
    val tailscale: TailscaleStatus?,
    val wireguardInstalled: Boolean,
    /** Live WireGuard details where readable; usually needs administrator or root rights. */
    val wireguard: List<WireGuardInterface>,
    /** Plain-language notes: "WireGuard details need administrator rights." */
    val notes: List<String>,
    /** The Tailscale CLI this snapshot used (absolute path), or null when none was found. */
    val tailscaleCli: String? = null,
)

/** Which local interface carries traffic to [host], and whether that interface belongs to a tunnel. */
data class VpnRoute(
    val host: String,
    /** The resolved address, or null when the name did not resolve. */
    val address: String?,
    val interfaceName: String?,
    /** The [VpnTunnel.id] the route goes through, or null for an ordinary interface. */
    val tunnelId: String?,
    val tunnelName: String?,
    /** The local source address the route uses, when the operating system reported it. */
    val source: String? = null,
)

/**
 * A Proxmox-family service answering on a tailnet peer, or on a host in a network behind a subnet router ([through]; then
 * [peer] is that router).
 */
data class TailnetService(val peer: TailscalePeer, val hit: ScanHit, val through: TailnetSubnetRoute? = null)

/** Platform backend. All calls block; run them off the UI thread. Mutations throw with a plain message on failure. */
interface VpnBackend {
    val platform: String
    fun snapshot(): VpnSnapshot
    /**
     * Tailscale's status alone: one `tailscale status --json`, no PowerShell, so it is cheap enough for every sample of a
     * peer path. Null when Tailscale is not installed. Throws when the read fails.
     */
    fun tailscaleStatus(): TailscaleStatus? = snapshot().tailscale
    fun routeTo(host: String, snapshot: VpnSnapshot? = null): VpnRoute
    fun setTunnel(tunnel: VpnTunnel, up: Boolean)
    /**
     * Tailscale exit node by peer IP; null stops using one.
     * [allowLanAccess] maps to `--exit-node-allow-lan-access` so the local network can stay reachable.
     */
    fun setExitNode(peer: TailscalePeer?, allowLanAccess: Boolean = true)
    /**
     * Imports a WireGuard .conf as tunnel [name] (letters, digits, "-", "_", at most 15 characters) and returns its id.
     * A file whose scripts would run as Local System is refused: that needs [importWireGuardReviewed].
     */
    fun importWireGuard(config: Path, name: String): String
    /**
     * [importWireGuard] after the user saw the file's review ([readWireGuardReview]) in the confirmation and, when its
     * scripts would run as Local System, accepted that ([scriptsAccepted]).
     */
    fun importWireGuardReviewed(config: Path, name: String, scriptsAccepted: Boolean): String = importWireGuard(config, name)
    /** Whether imported scripts would run here. Blocks (a registry read on Windows); run it off the UI thread. */
    fun wireGuardScriptPolicy(): WireGuardScriptPolicy = WireGuardScriptPolicy.Unknown
    fun removeWireGuard(tunnel: VpnTunnel)
    /**
     * Whether this computer uses the tailnet's subnet routes (see [detectAcceptRoutes]); for networks that overlap one of
     * [local], which way the operating system sends them once routes are accepted. Reads only. Blocks.
     */
    fun acceptRoutes(status: TailscaleStatus?, local: List<LocalNetwork> = emptyList()): AcceptRoutesReading =
        AcceptRoutesReading.unknown("Route settings cannot be read on this platform.")
    /** `tailscale set --accept-routes=<accept>`, run once (never retried). Throws with a plain message on failure. */
    fun setAcceptRoutes(accept: Boolean): Unit = error("Changing Tailscale's routes is not available on this platform.")
    /**
     * One peer's published SSH host keys, read fresh from `tailscale status --json` at the moment of use. Never cached,
     * stored in state, traced or logged. Null when Tailscale is not installed or the peer is not in the status.
     */
    fun tailscaleSshHostKeys(peerId: String): SshPeerKeys? = null
}

/** WireGuard interface names: 1-15 characters of letters, digits, "-" and "_" (the Linux limit, also safe on Windows). */
fun validWireGuardName(name: String) = name.matches(Regex("[A-Za-z0-9_-]{1,15}"))

/** Shown when a VPN change is refused because this process was not allowed to act on the host. */
const val VPN_HOST_ACTIONS_REASON = "Start with --allow-host-actions to change VPN tunnels."

/** True when [current] is still on the opposite side of [up], so the confirmed action would change something. */
fun toggleStillApplies(up: Boolean, current: VpnTunnel?): Boolean =
    current != null && current.state != VpnState.Connecting && (current.state == VpnState.Up) != up

/** True when Remove would still uninstall a tunnel that is not up. */
fun removeStillApplies(current: VpnTunnel?): Boolean =
    current != null && current.canRemove && current.state != VpnState.Up && current.state != VpnState.Connecting

/** True when the exit-node choice is not already the one Tailscale is using. */
fun exitNodeStillApplies(peer: TailscalePeer?, status: TailscaleStatus?): Boolean {
    val current = status?.exitNode
    return if (peer == null) current != null else status != null && current?.id != peer.id && status.peers.any { it.id == peer.id }
}

/** RFC1918 or link-local. Tailscale's CGNAT range is not the local network. */
fun privateLanAddress(host: String?): Boolean {
    val n = host?.substringBefore('%')?.let(ScanRules::ipv4) ?: return false
    return n ushr 24 == 10L || n ushr 20 == 0xac1L || n ushr 16 == 0xc0a8L || n ushr 16 == 0xa9feL
}

/**
 * The exit-node checkbox starts checked when the current server is on the local network and the route to it is not
 * already Tailscale. Otherwise it starts unchecked.
 */
fun exitNodeLanDefault(serverHost: String?, route: VpnRoute?): Boolean {
    if (route?.tunnelId == "tailscale") return false
    return privateLanAddress(route?.address ?: serverHost)
}

/** The Remove confirmation, naming only what this platform actually deletes. */
fun vpnRemoveBody(platform: String, tunnel: VpnTunnel): String = when {
    platform.equals("Windows", true) && tunnel.kind == VpnKind.WireGuard && !tunnel.removeDeletesConfig ->
        "Stops and uninstalls the WireGuard service. The configuration stays in WireGuard."
    tunnel.id.startsWith("nm:") ->
        "Deletes this NetworkManager profile and its key. RingMASTER keeps no copy of the key."
    else ->
        "The tunnel and its keys are deleted from this computer. Keep a copy of the configuration file if you need it again."
}

/** How long Bring up waits for Tailscale to reach Running before the status is read back instead. */
const val TAILSCALE_UP_SECONDS = 20L

/** Tailscale's own advice for a refused change (cmd/tailscale/cli: "To not require root, use 'sudo tailscale set --operator=$USER' once."). */
const val TAILSCALE_OPERATOR_HINT = "Tailscale refused the change. On Linux run: sudo tailscale set --operator=${'$'}USER"

/**
 * A plain sentence for a refused or failed Tailscale change, from the CLI's first error line [error] (already made safe by
 * [vpnFailureMessage]) and the status read right after it, [now]. The operator advice is given only for the CLI's real
 * `Access denied:` prefix on Linux; Windows' "in use by another user", the always-on policy and the exit-node conflict get
 * their own sentences (without the user name the CLI quotes). A sign-in or approval wait is reported as that, never as a timeout.
 */
fun tailscaleChangeFailure(error: String, timedOut: Boolean, timeoutSeconds: Long, now: TailscaleStatus?, windows: Boolean, sudoCommand: String? = null): String {
    when (now?.backendState) {
        "NeedsLogin" -> return "Tailscale needs a sign-in before it can connect." + if (signInLink(now) != null) " Open the sign-in link on this page." else ""
        "NeedsMachineAuth" -> return "Tailscale is signed in, but an administrator must approve this computer in the Tailscale admin console."
    }
    val text = error.trim()
    val refusal = when {
        text.startsWith("Access denied:") -> when {
            windows -> "Tailscale refused the change: access denied."
            // The exact change, so the user can run it as is (or become the operator once).
            sudoCommand != null -> "Tailscale only lets root or its operator change this. Run in a terminal: $sudoCommand (or once: sudo tailscale set --operator=${'$'}USER)."
            else -> TAILSCALE_OPERATOR_HINT
        }
        text.contains("already in use", ignoreCase = true) -> "Tailscale is in use by another user signed in to this computer. Only that user can change it now."
        text.contains("always-on mode", ignoreCase = true) -> "Tailscale is kept always on by your organisation's policy, so it cannot be brought down here."
        text.contains("disconnect not allowed: reason required", ignoreCase = true) -> "Your organisation's policy asks for a reason to disconnect. Bring Tailscale down from its own app, which asks for one."
        text.contains("advertise an exit node and use", ignoreCase = true) -> "This computer offers itself as an exit node, so it cannot use another one. Turn off running as an exit node in Tailscale first."
        timedOut -> "Tailscale did not finish the change within $timeoutSeconds seconds."
        text.isEmpty() || text == "VPN command failed." -> "The Tailscale command failed."
        else -> text
    }
    val state = when (now?.backendState) {
        null -> "Its current state could not be read."
        "Running" -> "Tailscale now reports: running."
        "Stopped" -> "Tailscale now reports: stopped."
        "Starting" -> "Tailscale now reports: starting."
        "" -> "Tailscale reported no state."
        else -> "Tailscale now reports: ${now.backendState}."
    }
    return "$refusal $state"
}

/** The status's sign-in link, only when it is a plain https URL. It is shown for the user to open; the app never opens it itself. */
fun signInLink(status: TailscaleStatus?): String? = status?.authUrl?.trim()?.takeIf { url ->
    url.startsWith("https://") && url.length <= 2048 && url.none { it.isWhitespace() || it.isISOControl() } &&
        runCatching { java.net.URI(url).host }.getOrNull()?.isNotBlank() == true
}

/** What to do when Tailscale is not running, per state and per operating system. Null while it runs. */
fun tailscaleStateAdvice(status: TailscaleStatus, platform: String): String? {
    val windows = platform.equals("Windows", ignoreCase = true)
    return when (status.backendState) {
        "Running" -> null
        "NeedsLogin" -> when {
            signInLink(status) != null -> "Tailscale needs you to sign in. Open the sign-in link below in your browser; this computer joins the tailnet when you finish."
            windows -> "Tailscale needs you to sign in. Choose Log in from the Tailscale icon in the notification area."
            else -> "Tailscale needs you to sign in. Run 'sudo tailscale login' in a terminal (no sudo needed if you are Tailscale's operator) and open the link it prints."
        }
        "NeedsMachineAuth" -> "This computer is signed in but waits for approval: an administrator must approve it in the Tailscale admin console (Machines)."
        "Stopped" -> "Tailscale is stopped. Bring it up above to reach the tailnet."
        "Starting" -> "Tailscale is starting."
        "" -> "Tailscale reported no state."
        else -> "Tailscale is ${status.backendState.lowercase()}."
    }
}

/** Header for the tailnet Proxmox search. The probe list is 8006, 8007 and 8443. [networks] counts networks behind routers. */
fun tailnetSearchHeader(searching: Boolean, found: Int, networks: Int = 0): String = when {
    !searching -> "PROXMOX ON THE TAILNET · $found"
    networks > 0 -> "SEARCHING ONLINE PEERS AND $networks ROUTED NETWORK${if (networks == 1) "" else "S"} ON 8006 · 8007 · 8443"
    else -> "SEARCHING ONLINE PEERS ON 8006 · 8007 · 8443"
}
