package local.ringleader.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One open connection with its own [workspace]. [profileId] is the saved profile it was opened from, if any.
 * [identity] names the server and account ("kind|origin|user"), so connecting to the same pair again reuses the session.
 */
data class OpenSession(val id: Long, val workspace: Workspace, val profileId: String? = null, val identity: String? = null)

data class SessionsState(val open: List<OpenSession>, val activeId: Long, val notice: String? = null) {
    /** The session in view. */
    val active: OpenSession get() = open.first { it.id == activeId }
}

const val MAX_OPEN_SESSIONS = 6

/**
 * The connections that are open at the same time. Each has its own [Workspace] (its own inventory, live refresh,
 * tasks and secret); one is in view and the desk is attached to it.
 *
 * A new connection never replaces an open one. It reuses the session in view only when that session is not
 * connected, and it reuses a session to the same server and account. At most one session that is not connected is
 * kept, and only while it is the one in view. Called on the UI dispatcher, like [Workspace].
 */
class Sessions(private val scope: CoroutineScope, private val newWorkspace: () -> Workspace) : AutoCloseable {
    private var nextId = 1L
    private val watchers = mutableMapOf<Long, Job>()
    private val pausedLive = mutableMapOf<Long, Boolean>()
    private var desk: ProfileDesk? = null
    private val mutableState = MutableStateFlow(open().let { SessionsState(listOf(it), it.id) })
    val state = mutableState.asStateFlow()

    init { watch(mutableState.value.active) }

    /** Connect the desk: its connections are routed through [route], and a changed or deleted profile closes its other sessions. */
    fun bind(desk: ProfileDesk) {
        this.desk = desk
        desk.router = ::route
        desk.profileInvalidated = ::closeProfile
        mutableState.value.active.let { desk.attach(it.workspace, it.profileId) }
    }

    /**
     * Pick the session a new connection opens in, bring it into view and attach the desk to it.
     * @throws IllegalStateException when [MAX_OPEN_SESSIONS] other connections are already open.
     */
    fun route(connection: Connection, profileId: String?) {
        val identity = sessionIdentity(connection.kind, connection.address, connection.tokenId)
        val now = mutableState.value
        val target = now.open.firstOrNull { it.identity == identity && isOpen(it) }
            ?: now.active.takeIf { !isOpen(it) }
            ?: run {
                check(now.open.size < MAX_OPEN_SESSIONS) { "$MAX_OPEN_SESSIONS connections are open. Disconnect one before opening another." }
                open().also { fresh -> watch(fresh); mutableState.update { it.copy(open = it.open + fresh) } }
            }
        mutableState.update { state -> state.copy(open = state.open.map { if (it.id == target.id) it.copy(profileId = profileId, identity = identity) else it }, notice = null) }
        bringIntoView(target.id)
    }

    /** Bring an open session into view. A session that is not connected is dropped when the view leaves it. */
    fun show(id: Long) {
        if (mutableState.value.open.none { it.id == id }) return
        // The pages are rebuilt for the session coming into view, and the Connect form's secret field with them. A secret
        // typed before the switch must not stay behind an empty-looking field, where the next Connect would send it.
        if (id != mutableState.value.activeId) desk?.discardSecrets()
        mutableState.update { it.copy(notice = null) }
        bringIntoView(id)
    }

    /** Disconnect one session. When it was the one in view and others are open, the most recent of those comes into view. */
    fun disconnect(id: Long) {
        val now = mutableState.value
        val session = now.open.firstOrNull { it.id == id } ?: return
        if (id != now.activeId) { remove(session); return }
        desk?.disconnect() ?: session.workspace.disconnect()
        mutableState.update { state -> state.copy(open = state.open.map { if (it.id == id) it.copy(profileId = null, identity = null) else it }) }
        now.open.lastOrNull { it.id != id }?.let { bringIntoView(it.id) }
    }

    /** Window minimised: stop every session's live reads, remembering which were on. */
    fun pause() {
        mutableState.value.open.forEach { session ->
            pausedLive.getOrPut(session.id) { session.workspace.state.value.live }
            session.workspace.setLive(false)
            session.workspace.setLiveTasks(false)
        }
    }

    /** Window restored: turn the live reads that [pause] stopped back on. False when nothing had been paused. */
    fun resume(): Boolean {
        if (pausedLive.isEmpty()) return false
        mutableState.value.open.forEach { if (pausedLive[it.id] == true) it.workspace.resumeLive() }
        pausedLive.clear()
        return true
    }

    override fun close() {
        watchers.values.forEach { it.cancel() }
        watchers.clear()
        mutableState.value.open.forEach { it.workspace.close() }
    }

    private fun open() = OpenSession(nextId++, newWorkspace())

    private fun isOpen(session: OpenSession) = sessionOpen(session.workspace.state.value)

    private fun bringIntoView(id: Long) {
        val previous = mutableState.value.active
        if (previous.id != id) {
            previous.workspace.setLiveTasks(false)
            previous.workspace.inView = false
            mutableState.update { it.copy(activeId = id) }
            if (!isOpen(previous)) remove(previous)
        }
        val active = mutableState.value.active
        active.workspace.inView = true
        desk?.attach(active.workspace, active.profileId.takeIf { isOpen(active) })
    }

    private fun closeProfile(profileId: String) {
        val now = mutableState.value
        now.open.filter { it.profileId == profileId && it.id != now.activeId }.forEach(::remove)
        mutableState.update { state -> state.copy(open = state.open.map { if (it.profileId == profileId) it.copy(profileId = null) else it }) }
    }

    /** Close and forget a session that is not in view. */
    private fun remove(session: OpenSession) {
        watchers.remove(session.id)?.cancel()
        pausedLive.remove(session.id)
        mutableState.update { it.copy(open = it.open.filter { open -> open.id != session.id }) }
        session.workspace.close()
    }

    /**
     * A session that ends by itself (its login expired) and is not in view is closed, with a notice for the desk.
     * The one in view stays, so its own message is shown where the user is looking.
     */
    private fun watch(session: OpenSession) {
        watchers[session.id] = scope.launch {
            var wasOpen = false
            session.workspace.state.collect { state ->
                val nowOpen = state.connected
                if (wasOpen && !nowOpen) {
                    val current = mutableState.value
                    if (current.open.any { it.id == session.id }) {
                        if (current.activeId == session.id) mutableState.update { all -> all.copy(open = all.open.map { if (it.id == session.id) it.copy(profileId = null, identity = null) else it }) }
                        else {
                            val message = state.error?.let { "A connection in the background ended. $it" }
                            // Not from inside this collector: remove() cancels it.
                            scope.launch { remove(session); if (message != null) mutableState.update { it.copy(notice = message) } }
                        }
                    }
                }
                wasOpen = nowOpen
            }
        }
    }
}

/**
 * How an open connection trusts its server, for the session card: "Certificate pin", or normal TLS with the store that
 * accepted the certificate ("Normal TLS · trusted by this computer" or "Normal TLS · public certificate"). Null when
 * nothing is connected.
 */
fun connectionTrustLabel(state: WorkspaceState): String? = when {
    !state.connected -> null
    state.pinned -> "Certificate pin"
    state.tlsTrust != null -> "Normal TLS · ${state.tlsTrust.label}"
    else -> "Normal TLS"
}

internal fun sessionIdentity(kind: String, origin: String, user: String) = "$kind|$origin|$user"

/**
 * True for a session worth keeping: a connection that has loaded or is still loading. A connection whose
 * first read failed (wrong password, unreachable server) has nothing to show, so the next connection may take its place.
 */
fun sessionOpen(state: WorkspaceState) = (state.connected && !(state.overview == null && state.error != null && !state.loading))

/**
 * True when a connection has nothing to show and a reason why: the server refused the sign-in and the session closed,
 * or the first read failed (certificate refused, server not reached) and the session is only trying again. The pages
 * then show the failure, with the reason, instead of an empty workspace.
 */
fun connectionFailed(state: WorkspaceState) = !sessionOpen(state) && state.error != null

/**
 * What the person can do about a failed connection, in one short paragraph for the failure card. A network or certificate
 * failure has its own next step ([NetworkFailure.nextStep]); a refused sign-in says what was sent. Null when the reason
 * says it all.
 */
fun connectionFailureAdvice(state: WorkspaceState): String? = when {
    !connectionFailed(state) -> null
    state.failure != null -> state.failure.nextStep
    state.error == CERTIFICATE_ERROR ->
        "The certificate could not be verified. Return to the Desk and check the server certificate before trusting it."
    state.error == LOGIN_REFUSED ->
        "The server answered and refused this sign-in. The app sent the user above and the password as typed. " +
            "Every server has its own accounts: root@pam is that node’s own root password, and a user made in Proxmox is user@pve. " +
            "Try the same user and password in the server’s own web page."
    state.error == SESSION_REFUSED && '!' in state.account && !state.connected ->
        "For a new connection this means the server does not accept the API token ID or its secret. Every server has its own tokens."
    else -> null
}
