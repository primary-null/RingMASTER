package local.ringleader.core

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DeskState(
    val profiles: List<ConnectionProfile> = emptyList(),
    val selectedId: String? = null,
    val editing: Boolean = false,
    val draft: ProfileDraft = ProfileDraft(null, "", "", "", ""),
    val pendingDeleteId: String? = null,
    val connectedProfileId: String? = null,
    val storeMessage: String? = null,
    val saveBlocked: Boolean = false,
    val formError: String? = null,
    val notice: String? = null,
    val manualSecretPresent: Boolean = false,
    val profileSecretPresent: Boolean = false,
    val secretGeneration: Int = 0,
)

/**
 * Desk workflows for saved profiles and the manual connection.
 * Secrets stay in transient character arrays and are cleared on profile change, connect, cancel and leaving the desk.
 * Editing connection-defining fields of the active profile, or deleting it, disconnects instead of rerouting the session.
 *
 * The desk works on one workspace at a time: the session in view. With several sessions open ([Sessions]), [router]
 * picks the workspace a new connection opens in and [attach] points the desk at it.
 */
class ProfileDesk(
    workspace: Workspace,
    private val store: ProfileStore,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : AutoCloseable {
    private var workspace: Workspace = workspace
    private val mutableState = MutableStateFlow(DeskState())
    val state = mutableState.asStateFlow()
    /**
     * Called right before a connection opens, with the connection and the saved profile it came from (both null for
     * the demo). It may [attach] another workspace, so the new connection opens beside the ones already open. It
     * refuses by throwing [IllegalStateException]; the message is shown on the desk and nothing is opened.
     */
    var router: ((Connection?, String?) -> Unit)? = null
    /** Called with a profile id when that profile is deleted or its connection details change: other sessions opened from it must close. */
    var profileInvalidated: ((String) -> Unit)? = null

    /** Point the desk at [workspace], the session now in view, opened from the saved profile [connectedProfileId] if any. */
    fun attach(workspace: Workspace, connectedProfileId: String?) {
        this.workspace = workspace
        mutableState.update { it.copy(connectedProfileId = connectedProfileId) }
    }
    private var manualSecret: CharArray? = null
    private var profileSecret: CharArray? = null

    init { reload() }

    fun reload() {
        try {
            val profiles = store.load()
            mutableState.update { it.copy(profiles = profiles, storeMessage = null, saveBlocked = false) }
        } catch (e: ProfileStoreException) {
            mutableState.update {
                it.copy(profiles = emptyList(), storeMessage = e.message, saveBlocked = true, editing = false, pendingDeleteId = null)
            }
        }
    }

    /** Drop a remembered profile id after the workspace itself has disconnected, including session expiry. */
    fun onWorkspace(workspaceState: WorkspaceState = workspace.state.value) {
        val live = workspace.state.value
        if (workspaceState.connected && live.connected) return
        if (!live.connected && mutableState.value.connectedProfileId != null) {
            mutableState.update { it.copy(connectedProfileId = null) }
        }
    }

    fun selectProfile(id: String) {
        if (mutableState.value.selectedId == id) return
        if (mutableState.value.profiles.none { it.id == id }) return
        discardSecrets()
        mutableState.update { it.copy(selectedId = id, editing = false, pendingDeleteId = null, formError = null) }
    }

    fun beginCreate() {
        if (refuseBlocked()) return
        discardSecrets()
        mutableState.update {
            it.copy(editing = true, pendingDeleteId = null, formError = null, notice = null, draft = ProfileDraft(newId(), "", "", "", ""))
        }
    }

    fun beginEdit(id: String? = mutableState.value.selectedId) {
        if (id == null) return
        if (refuseBlocked()) return
        val profile = mutableState.value.profiles.find { it.id == id } ?: return
        discardSecrets()
        mutableState.update {
            it.copy(
                editing = true, selectedId = id, pendingDeleteId = null, formError = null,
                draft = ProfileDraft(profile.id, profile.displayName, profile.origin, profile.tokenId, profile.sha256Pin.orEmpty(), profile.kind),
            )
        }
    }

    fun updateDraft(displayName: String, origin: String, tokenId: String, sha256Pin: String, kind: String = mutableState.value.draft.kind) {
        mutableState.update { it.copy(draft = it.draft.copy(displayName = displayName, origin = origin, tokenId = tokenId, sha256Pin = sha256Pin, kind = kind), formError = null) }
    }

    fun cancelEdit() {
        discardSecrets()
        mutableState.update { it.copy(editing = false, formError = null) }
    }

    fun saveDraft(): Boolean {
        onWorkspace(workspace.state.value)
        if (refuseBlocked()) return false
        val draft = mutableState.value.draft
        val profile = try {
            profileFromDraft(draft, draft.id ?: newId())
        } catch (e: IllegalArgumentException) {
            mutableState.update { it.copy(formError = e.message) }
            return false
        } catch (e: IllegalStateException) {
            mutableState.update { it.copy(formError = e.message) }
            return false
        }
        val current = mutableState.value.profiles
        val previous = current.find { it.id == profile.id }
        val activeId = mutableState.value.connectedProfileId
        val updated = if (previous == null) current + profile else current.map { if (it.id == profile.id) profile else it }
        if (!persist(updated)) return false
        val redefined = previous != null && !previous.definesSameConnection(profile)
        val disconnect = redefined && activeId == profile.id
        if (disconnect) workspace.disconnect()
        if (redefined) profileInvalidated?.invoke(profile.id)
        wipeSecrets()
        mutableState.update {
            it.copy(
                profiles = updated,
                editing = false,
                selectedId = profile.id,
                pendingDeleteId = null,
                formError = null,
                connectedProfileId = if (disconnect) null else it.connectedProfileId,
                manualSecretPresent = false,
                profileSecretPresent = false,
                secretGeneration = it.secretGeneration + 1,
                notice = if (disconnect) {
                    "The server, token ID, certificate pin, or connection kind changed, so the session was disconnected. Enter the token secret to connect again."
                } else {
                    "Saved ${profile.displayName}."
                },
            )
        }
        return true
    }

    fun requestDelete(id: String? = mutableState.value.selectedId) {
        if (id == null) return
        if (mutableState.value.saveBlocked || mutableState.value.profiles.none { it.id == id }) return
        discardSecrets()
        mutableState.update { it.copy(pendingDeleteId = id, editing = false, formError = null) }
    }

    fun cancelDelete() {
        discardSecrets()
        mutableState.update { it.copy(pendingDeleteId = null) }
    }

    fun confirmDelete(): Boolean {
        onWorkspace(workspace.state.value)
        val id = mutableState.value.pendingDeleteId ?: return false
        if (refuseBlocked()) return false
        val current = mutableState.value.profiles
        val victim = current.find { it.id == id } ?: run {
            mutableState.update { it.copy(pendingDeleteId = null) }
            return false
        }
        val updated = current.filter { it.id != id }
        if (!persist(updated)) return false
        val disconnect = mutableState.value.connectedProfileId == id
        if (disconnect) workspace.disconnect()
        profileInvalidated?.invoke(id)
        wipeSecrets()
        mutableState.update {
            it.copy(
                profiles = updated,
                pendingDeleteId = null,
                selectedId = if (it.selectedId == id) null else it.selectedId,
                editing = if (it.draft.id == id) false else it.editing,
                connectedProfileId = if (disconnect) null else it.connectedProfileId,
                formError = null,
                manualSecretPresent = false,
                profileSecretPresent = false,
                secretGeneration = it.secretGeneration + 1,
                notice = if (disconnect) "Deleted ${victim.displayName}. The session was disconnected." else "Deleted ${victim.displayName}.",
            )
        }
        return true
    }

    fun setProfileSecret(value: String) = setSecret(profile = true, value = value)

    fun setManualSecret(value: String) = setSecret(profile = false, value = value)

    fun discardSecrets() {
        wipeSecrets()
        mutableState.update { it.copy(manualSecretPresent = false, profileSecretPresent = false, secretGeneration = it.secretGeneration + 1) }
    }

    fun leaveDesk() = discardSecrets()

    fun connectSelected(otp: String? = null): Boolean {
        onWorkspace(workspace.state.value)
        val id = mutableState.value.selectedId ?: return false
        val profile = mutableState.value.profiles.find { it.id == id } ?: return false
        val chars = profileSecret?.copyOf() ?: CharArray(0)
        return try {
            val connection = Connection(profile.origin, profile.tokenId, chars.concatToString(), profile.sha256Pin.orEmpty(), profile.kind, otp)
            publishSecretClear(connectedProfileId = profile.id, notice = null, connect = connection)
            true
        } catch (e: IllegalArgumentException) {
            mutableState.update { it.copy(formError = e.message) }
            false
        } catch (e: IllegalStateException) {
            mutableState.update { it.copy(formError = e.message) }
            false
        } finally {
            chars.fill('\u0000')
        }
    }

    fun connectManual(address: String, tokenId: String, pin: String, kind: String = DIRECT_PVE_KIND, otp: String? = null): Boolean {
        onWorkspace(workspace.state.value)
        val chars = manualSecret?.copyOf() ?: CharArray(0)
        return try {
            val connection = Connection(address, tokenId.trim(), chars.concatToString(), pin, kind, otp)
            publishSecretClear(connectedProfileId = null, notice = null, connect = connection)
            true
        } catch (e: IllegalArgumentException) {
            mutableState.update { it.copy(formError = e.message) }
            false
        } catch (e: IllegalStateException) {
            mutableState.update { it.copy(formError = e.message) }
            false
        } finally {
            chars.fill('\u0000')
        }
    }

    fun exploreDemo(): Boolean {
        discardSecrets()
        try { router?.invoke(null, null) } catch (e: IllegalStateException) {
            mutableState.update { it.copy(formError = e.message) }
            return false
        }
        workspace.exploreDemo()
        mutableState.update { it.copy(connectedProfileId = null, formError = null, notice = null) }
        return true
    }

    fun disconnect() {
        discardSecrets()
        workspace.disconnect()
        mutableState.update { it.copy(connectedProfileId = null, notice = null) }
    }

    fun hasProfileSecret() = profileSecret != null

    fun hasManualSecret() = manualSecret != null

    override fun close() = wipeSecrets()

    private fun publishSecretClear(connectedProfileId: String?, notice: String?, connect: Connection) {
        // The router runs first: when it refuses, the typed secret stays in its field and only this copy is cleared.
        try { router?.invoke(connect, connectedProfileId) } catch (e: IllegalStateException) { connect.close(); throw e }
        wipeSecrets()
        workspace.connect(connect)
        mutableState.update {
            it.copy(
                connectedProfileId = connectedProfileId,
                formError = null,
                notice = notice,
                manualSecretPresent = false,
                profileSecretPresent = false,
                secretGeneration = it.secretGeneration + 1,
            )
        }
    }

    private fun setSecret(profile: Boolean, value: String) {
        val target = if (profile) profileSecret else manualSecret
        target?.fill('\u0000')
        val stored = value.takeIf { it.isNotEmpty() }?.toCharArray()
        if (profile) profileSecret = stored else manualSecret = stored
        mutableState.update {
            if (profile) it.copy(profileSecretPresent = stored != null, formError = null) else it.copy(manualSecretPresent = stored != null, formError = null)
        }
    }

    private fun wipeSecrets() {
        manualSecret?.fill('\u0000')
        profileSecret?.fill('\u0000')
        manualSecret = null
        profileSecret = null
    }

    private fun persist(profiles: List<ConnectionProfile>): Boolean = try {
        store.save(profiles)
        true
    } catch (e: ProfileStoreException) {
        mutableState.update { it.copy(formError = e.message, saveBlocked = it.saveBlocked || e.blocksSave, storeMessage = if (e.blocksSave) e.message else it.storeMessage) }
        false
    }

    private fun refuseBlocked(): Boolean {
        if (!mutableState.value.saveBlocked) return false
        mutableState.update { it.copy(formError = it.storeMessage ?: "Saved profiles could not be read. The file was left unchanged.") }
        return true
    }
}
