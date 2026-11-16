package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.ringleader.core.*

/*
 * "Open SSH" for a node, a guest or a tailnet peer whose address belongs to a peer running Tailscale SSH (the SSH contract).
 * The system terminal runs the system ssh against the peer's tailnet address, checking its host key against the keys the
 * tailnet publishes. Desktop only: in browser mode the terminal would open on the RingMASTER host, so nothing is offered.
 *
 * Hookups outside this file: Main.kt sets [TailnetSsh.session]; the node inspector (InventoryScreen.kt) places
 * [NodeSshButton] beside Shell and [NodeSshNote] under its action row; the guest page places [GuestSshButton] and [GuestSshNote].
 */

/** Where the Open SSH actions get Tailscale's status and backend. Memory only. */
object TailnetSsh {
    /** The desktop window's VPN session; null in browser mode and tests that do not set it. */
    var session by mutableStateOf<VpnSession?>(null)
    /** A status read for SSH matching when the VPN page is not polling. */
    private var own by mutableStateOf<TailscaleStatus?>(null)
    private var ownAt: Instant = Instant.EPOCH
    /** User names typed in Open SSH, per node, guest or peer. Memory only; gone when the app closes. */
    val users = mutableStateMapOf<String, String>()
    /** Replaced in tests. */
    var environment: () -> SshLaunchEnvironment = { defaultEnvironment }
    private val defaultEnvironment by lazy {
        SshLaunchEnvironment().also { e ->
            runCatching { e.store.cleanup() }
            Runtime.getRuntime().addShutdownHook(Thread({ runCatching { e.store.removeAll() } }, "ringmaster-ssh-known-hosts"))
        }
    }

    /** The newest status known: the VPN page's snapshot or this object's own read. */
    val status: TailscaleStatus? get() {
        val snap = session?.snapshot
        return if (snap?.tailscale != null && (own == null || !snap.at.isBefore(ownAt))) snap.tailscale else own ?: snap?.tailscale
    }

    /** Reads the status when nothing younger than 30 s is known. Blocks on Dispatchers.IO. */
    suspend fun refreshIfStale(now: Instant = Instant.now()) {
        val s = session ?: return
        val snapAt = s.snapshot?.at
        val newest = listOfNotNull(snapAt, ownAt.takeIf { own != null }).maxOrNull()
        if (newest != null && Duration.between(newest, now).seconds < 30) return
        ownAt = now
        own = withContext(Dispatchers.IO) { runCatching { s.backend?.tailscaleStatus() }.getOrNull() } ?: own
    }

    internal fun reset() { session = null; own = null; ownAt = Instant.EPOCH; users.clear(); environment = { defaultEnvironment } }
}

/** Where to look for a node in the tailnet: its network addresses, and the connection's host when it goes to that node. */
internal fun nodeSshInputs(state: WorkspaceState, node: String): Pair<List<String>, String?> {
    val addresses = state.nodeNetwork.value?.takeIf { state.nodeNetworkNode == node }.orEmpty()
        .flatMap { listOfNotNull(it.cidr, it.cidr6) }.map { it.substringBefore('/') }
    val host = if (state.kind == PDM_KIND) null else runCatching { java.net.URI(serverOrigin(state.address).toString()).host }.getOrNull()
    return addresses to host
}

/** A guest's reported addresses (agent or container config). */
internal fun guestSshAddresses(state: WorkspaceState, guest: Guest): List<String> =
    state.inspection?.takeIf { it.resource == guest.resource }?.addresses?.value.orEmpty().mapNotNull(::reportedHost)

@Composable private fun sshAvailable(): Boolean = !LocalAppHost.current.web && TailnetSsh.session?.backend != null

@Composable private fun rememberSshAvailability(addresses: List<String>, nodeName: String?, connectionHost: String?): SshAvailability? {
    if (!sshAvailable()) return null
    LaunchedEffect(Unit) { TailnetSsh.refreshIfStale() }
    val status = TailnetSsh.status
    return remember(status, addresses, nodeName, connectionHost) { sshAvailability(status, addresses, nodeName, connectionHost) }
}

/** Beside the node inspector's Shell action: Open SSH when the node is a Tailscale SSH peer, else nothing. */
@Composable fun NodeSshButton(state: WorkspaceState, node: String) {
    if (state.demo) return
    val (addresses, host) = nodeSshInputs(state, node)
    val a = rememberSshAvailability(addresses, node, host) as? SshAvailability.Ready ?: return
    SshButton(a, "root", "node:${state.address}:$node")
}

/** Under the node inspector's action row: why there is no Open SSH, when there is something to say. */
@Composable fun NodeSshNote(state: WorkspaceState, node: String) {
    if (state.demo) return
    val (addresses, host) = nodeSshInputs(state, node)
    SshNote(rememberSshAvailability(addresses, node, host))
}

/** Beside the guest page's Console action. */
@Composable fun GuestSshButton(state: WorkspaceState, guest: Guest) {
    if (state.demo) return
    val a = rememberSshAvailability(guestSshAddresses(state, guest), null, null) as? SshAvailability.Ready ?: return
    SshButton(a, "", "guest:${state.address}:${guest.resource}")
}

@Composable fun GuestSshNote(state: WorkspaceState, guest: Guest) {
    if (state.demo) return
    SshNote(rememberSshAvailability(guestSshAddresses(state, guest), null, null))
}

/** The muted line where the button would be. Only said where Tailscale runs here and the address is on (or behind) the tailnet. */
@Composable internal fun SshNote(a: SshAvailability?) {
    a ?: return
    if (a is SshAvailability.Unavailable && a.reason != TAILSCALE_USERSPACE_NOTE) return
    val showcase = Appearance.showcase
    val text = sshNoButtonReason(a) { if (showcase) "this machine" else it } ?: return
    Text(text, Modifier.fillMaxWidth(), fontFamily = Tech.Mono, fontSize = 10.sp, lineHeight = 14.sp, color = Tech.Muted, maxLines = 3)
}

/** The Open SSH action and its dialog. [defaultUser] is "root" for a Proxmox node, empty otherwise. */
@Composable internal fun SshButton(a: SshAvailability.Ready, defaultUser: String, userKey: String) {
    var open by remember(userKey) { mutableStateOf(false) }
    HelpTip(SSH_ACTION_HELP) { MiniAction("Open SSH") { open = true } }
    if (open) OpenSshDialog(a.peer, a.address, defaultUser, userKey, TailnetSsh.session?.backend) { open = false }
}

/**
 * The dialog: the machine, an editable user name (validated; remembered in memory for this target), what is checked, and
 * check mode. Open reads the peer's keys fresh, writes the known-hosts file and starts the terminal once.
 */
@Composable internal fun OpenSshDialog(peer: TailscalePeer, address: String, defaultUser: String, userKey: String, backend: VpnBackend?, onDismiss: () -> Unit) {
    var user by remember(userKey) { mutableStateOf(TailnetSsh.users[userKey] ?: defaultUser) }
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val valid = validSshUser(user)
    TechDialog("Open SSH", onDismiss, subtitle = SSH_ACTION_HELP, dismiss = Dismiss.Soft, initialFocus = first, actions = {
        TechAction(if (working) "Opening…" else "Open terminal", active = true, enabled = valid && !working) {
            TailnetSsh.users[userKey] = user
            working = true; result = null
            scope.launch {
                result = try {
                    true to withContext(Dispatchers.IO) {
                        val e = TailnetSsh.environment()
                        // Read at this moment, never from the page's status: the keys are not kept anywhere.
                        val keys = backend?.tailscaleSshHostKeys(peer.id)
                        launchTailscaleSsh(planTailscaleSsh(peer, address, keys, user, e), e)
                    }
                } catch (c: CancellationException) { throw c } catch (x: Exception) { false to (x.message ?: "The terminal could not be opened.") }
                working = false
            }
        }
        TechAction(if (result?.first == true) "Close" else "Cancel", onClick = onDismiss)
    }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("MACHINE", fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Muted)
            Text("${peer.hostName}  ·  $address", Modifier.weight(1f, fill = false).identity(), fontFamily = Tech.Mono, fontSize = 12.sp, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TechField(user, { user = it.trim().take(32) }, "User", Modifier.focusRequester(first), placeholder = "user name",
            error = if (valid || user.isEmpty()) null else SSH_USER_RULE, hint = if (user.isEmpty()) "Type the user to sign in as." else null)
        Text("Opens your system terminal running ssh to this machine's tailnet address. Its host key is checked against the " +
            "${peer.sshHostKeyCount} key${if (peer.sshHostKeyCount == 1) "" else "s"} your tailnet publishes for it" +
            (peer.sshHostKeyTypes.takeIf { it.isNotEmpty() }?.joinToString(", ", " (", ")") ?: "") +
            ", so there is no first-connection prompt and a different key is refused. RingMASTER handles no password or private key.",
            fontSize = 12.sp, lineHeight = 18.sp, color = Tech.Ink)
        Text(SSH_CHECK_MODE_NOTE, fontSize = 12.sp, lineHeight = 18.sp, color = Tech.Muted)
        result?.let { (ok, text) -> Text(text, fontFamily = Tech.Mono, fontSize = 11.sp, lineHeight = 16.sp, color = if (ok) Tech.Green else Tech.Red) }
    }
}

/** Under the VPN page's peer table: what Open SSH is, and why some peers have none. */
internal fun peerSshFootnote(peers: List<TailscalePeer>, web: Boolean): String? {
    val withKeys = peers.count { it.sshHostKeyCount > 0 }
    return when {
        web && withKeys > 0 -> "Open SSH starts a terminal on the computer RingMASTER runs on, so it is offered only in the desktop app."
        web -> null
        withKeys == 0 -> "No peer runs Tailscale SSH (none publishes SSH host keys), so there is no Open SSH. Plain SSH would need a first-connection trust prompt, which RingMASTER does not do."
        withKeys < peers.size -> "Open SSH ($SSH_ACTION_HELP) is offered for online peers that run Tailscale SSH. Others publish no host keys, and plain SSH would need a first-connection trust prompt, which RingMASTER does not do."
        else -> "Open SSH: $SSH_ACTION_HELP."
    }
}
