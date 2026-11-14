package local.ringleader.core

import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    val vaultStatus: VaultStatus? = null,
    val vaultName: String = "",
    val remembered: Set<String> = emptySet(),
    val rememberChoice: Boolean = false,
    val vaultBusy: Boolean = false,
)

/**
 * The Password / API token choice must agree with the id: an API token ID has a '!', a user does not.
 * Returns the message to show when they disagree, or null. Only direct PVE has the choice.
 */
fun authChoiceMismatch(passwordLogin: Boolean, identity: String, kind: String): String? {
    val id = identity.trim()
    return when {
        kind != DIRECT_PVE_KIND || id.isEmpty() -> null
        passwordLogin && '!' in id -> "Password is selected, but this is an API token ID. Enter a user such as root@pam, or choose API token."
        !passwordLogin && '!' !in id -> "API token is selected, but this is a user. Enter a token ID such as root@pam!monitor, or choose Password."
        else -> null
    }
}

/** The usual Proxmox VE user when Password is chosen and the field is blank. */
const val DEFAULT_PASSWORD_USER = "root@pam"

/** The user field after switching to Password: blank becomes root@pam, a token ID keeps its user part. */
fun passwordUserAfterSwitch(field: String): String {
    val id = field.trim()
    return when {
        id.isEmpty() -> DEFAULT_PASSWORD_USER
        '!' in id -> id.substringBefore('!').ifBlank { DEFAULT_PASSWORD_USER }
        else -> id
    }
}

/**
 * A direct-PVE password user with no realm signs in on Linux PAM, the Proxmox default.
 * Blank stays blank. A value that contains '!' is left for [authChoiceMismatch].
 * [Connection] rejects a user without '@', so callers complete the name before building one.
 */
fun completeLoginUser(user: String): String {
    val id = user.trim()
    if (id.isEmpty() || '@' in id || '!' in id) return id
    return "$id@pam"
}

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
    private val vault: SecretVault = NoVault("Remember is not set up."),
    private val rememberedProfiles: RememberedProfiles = RememberedProfiles(null),
    private val io: CoroutineContext = Dispatchers.IO,
    /** How long a just-typed secret may wait for the first inventory before it is dropped unremembered. */
    private val rememberWaitMs: Long = 90_000,
    private val certificateStore: CertificateTrustStore = CertificateTrustStore(store.file.resolveSibling("certificates.json")),
) : AutoCloseable {
    private var workspace: Workspace = workspace
    private val mutableState = MutableStateFlow(DeskState())
    val state = mutableState.asStateFlow()
    private val dispatcher = io.minusKey(Job)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val gate = Any()
    private val rememberedIds = mutableSetOf<String>()
    private var pending: PendingRemember? = null
    /** Test hook. Called with a secret copy after it has been zeroed. */
    internal var onSecretReleased: ((CharArray) -> Unit)? = null
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

    init {
        reload()
        val loaded = runCatching { rememberedProfiles.load() }.getOrDefault(emptySet())
        synchronized(gate) { rememberedIds.addAll(loaded) }
        mutableState.update { it.copy(remembered = loaded, vaultName = vault.name) }
        val blocked = rememberedProfiles.blockedReason()
        if (blocked != null) {
            mutableState.update { it.copy(vaultStatus = VaultStatus.Unavailable(blocked)) }
        } else {
            scope.launch {
                val status = try {
                    vault.status()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    VaultStatus.Unavailable(vaultFailureReason(t))
                }
                mutableState.update { it.copy(vaultStatus = status, vaultName = vault.name) }
                if (status !is VaultStatus.Available) dropPending()
            }
        }
    }

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
        mutableState.update { it.copy(selectedId = id, editing = false, pendingDeleteId = null, formError = null, rememberChoice = false) }
    }

    fun beginCreate() {
        if (refuseBlocked()) return
        discardSecrets()
        mutableState.update {
            it.copy(editing = true, pendingDeleteId = null, formError = null, notice = null, rememberChoice = false, draft = ProfileDraft(newId(), "", "", "", ""))
        }
    }

    fun beginEdit(id: String? = mutableState.value.selectedId) {
        if (id == null) return
        if (refuseBlocked()) return
        val profile = mutableState.value.profiles.find { it.id == id } ?: return
        discardSecrets()
        mutableState.update {
            it.copy(
                editing = true, selectedId = id, pendingDeleteId = null, formError = null, rememberChoice = false,
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

    /** [passwordLogin] is the editor's Password / API token choice; null skips that check. */
    fun saveDraft(passwordLogin: Boolean? = null): Boolean {
        onWorkspace(workspace.state.value)
        if (refuseBlocked()) return false
        var draft = mutableState.value.draft
        if (passwordLogin == true && draft.kind == DIRECT_PVE_KIND) {
            val completed = completeLoginUser(draft.tokenId)
            if (completed != draft.tokenId) {
                draft = draft.copy(tokenId = completed)
                mutableState.update { it.copy(draft = draft) }
            }
        }
        val profile = try {
            profileFromDraft(draft, draft.id ?: newId())
        } catch (e: IllegalArgumentException) {
            mutableState.update { it.copy(formError = e.message) }
            return false
        } catch (e: IllegalStateException) {
            mutableState.update { it.copy(formError = e.message) }
            return false
        }
        passwordLogin?.let { authChoiceMismatch(it, profile.tokenId, profile.kind) }?.let { message ->
            mutableState.update { it.copy(formError = message) }
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
        val hadRemembered = redefined && (mutableState.value.remembered.contains(profile.id) || synchronized(gate) { pending?.profile?.id == profile.id })
        if (redefined) forgetVaultEntry(profile.id)
        wipeSecrets()
        val savedNotice = if (disconnect) {
            "The server, token ID, certificate pin, or connection kind changed, so the session was disconnected. Enter the token secret to connect again."
        } else {
            "Saved ${profile.displayName}."
        }
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
                rememberChoice = false,
                notice = if (hadRemembered) "$savedNotice The remembered secret was forgotten because the server, user or token ID, pin or kind changed." else savedNotice,
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
        forgetVaultEntry(id)
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
        mutableState.update { it.copy(manualSecretPresent = false, profileSecretPresent = false, secretGeneration = it.secretGeneration + 1, rememberChoice = false) }
    }

    /** The Remember box on the Connect form. Ignored until the password safe is available. */
    fun setRememberChoice(on: Boolean) {
        if (mutableState.value.vaultStatus !is VaultStatus.Available) return
        mutableState.update { it.copy(rememberChoice = on) }
    }

    fun leaveDesk() = discardSecrets()

    fun connectSelected(otp: String? = null): Boolean {
        onWorkspace(workspace.state.value)
        val id = mutableState.value.selectedId ?: return false
        val profile = mutableState.value.profiles.find { it.id == id } ?: return false
        val chars = profileSecret?.copyOf() ?: CharArray(0)
        val now = mutableState.value
        val shouldRemember = chars.isNotEmpty() && now.vaultStatus is VaultStatus.Available &&
            (now.rememberChoice || profile.id in now.remembered)
        val rememberCopy = if (shouldRemember) chars.copyOf() else null
        var handed = false
        return try {
            val connection = Connection(profile.origin, profile.tokenId, chars.concatToString(), profile.sha256Pin.orEmpty(), profile.kind, otp)
            publishSecretClear(connectedProfileId = profile.id, notice = null, connect = connection)
            rememberCopy?.let { watchForSuccess(PendingRemember(profile, it, workspace, workspace.state.value.connectionEpoch)) }
            handed = true
            true
        } catch (e: IllegalArgumentException) {
            mutableState.update { it.copy(formError = e.message) }
            false
        } catch (e: IllegalStateException) {
            mutableState.update { it.copy(formError = e.message) }
            false
        } finally {
            chars.fill('\u0000')
            if (!handed) rememberCopy?.let { release(it) }
        }
    }

    /**
     * Connect with the secret already in the password safe. The caller runs this on the UI thread: the read waits on
     * [dispatcher], then [Workspace.connect] runs here, as a typed connect does. Returns false when nothing was opened.
     * A remembered secret is never copied into the secret field.
     */
    suspend fun connectRemembered(otp: String? = null): Boolean {
        val now = mutableState.value
        val id = now.selectedId ?: return false
        if (id !in now.remembered || now.vaultStatus !is VaultStatus.Available || now.vaultBusy) return false
        val profile = now.profiles.find { it.id == id } ?: return false
        mutableState.update { it.copy(vaultBusy = true, formError = null) }
        var read: CharArray? = null
        return try {
            try {
                read = withContext(dispatcher) { vault.read(profile.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                mutableState.update { it.copy(formError = "The remembered secret could not be read: ${vaultFailureReason(t)} Type it to connect.") }
                return false
            }
            val secret = read
            if (secret == null) {
                synchronized(gate) { rememberedIds.remove(profile.id) }
                persistMarkers()
                val ids = synchronized(gate) { rememberedIds.toSet() }
                mutableState.update { it.copy(remembered = ids, formError = "The remembered secret is no longer in ${vault.name}. Type it to connect.") }
                return false
            }
            // The read can wait on an unlock prompt. Meanwhile the selection may have moved, or the profile may have been
            // edited or forgotten: the secret then belongs to nothing on the desk and is dropped.
            val after = mutableState.value
            val current = after.profiles.find { it.id == profile.id }
            if (after.selectedId != profile.id || current == null || !current.definesSameConnection(profile) || profile.id !in after.remembered) return false
            val connection = Connection(profile.origin, profile.tokenId, secret.concatToString(), profile.sha256Pin.orEmpty(), profile.kind, otp)
            publishSecretClear(connectedProfileId = profile.id, notice = null, connect = connection)
            true
        } catch (e: IllegalArgumentException) {
            mutableState.update { it.copy(formError = e.message) }
            false
        } catch (e: IllegalStateException) {
            mutableState.update { it.copy(formError = e.message) }
            false
        } finally {
            read?.let { release(it) }
            mutableState.update { it.copy(vaultBusy = false) }
        }
    }

    /** Drop one profile's remembered secret. The marker stays when the password safe refuses, so the user can try again. */
    fun forgetRemembered(profileId: String) {
        scope.launch {
            try {
                vault.forget(profileId)
                synchronized(gate) { rememberedIds.remove(profileId) }
                persistMarkers()
                dropPendingFor(profileId)
                val ids = synchronized(gate) { rememberedIds.toSet() }
                mutableState.update { it.copy(remembered = ids, notice = "Forgot the remembered secret.", rememberChoice = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                mutableState.update { it.copy(notice = "The remembered secret could not be removed: ${vaultFailureReason(t)}") }
            }
        }
    }

    /** Clear every remembered secret and the marker list. Null on success, otherwise one plain sentence. */
    fun forgetAllRemembered(): String? = try {
        vault.forgetAll()
        dropPending()
        synchronized(gate) { rememberedIds.clear() }
        persistMarkers()
        mutableState.update { it.copy(remembered = emptySet(), rememberChoice = false) }
        null
    } catch (t: Throwable) {
        val reason = (t.message ?: "the password safe failed.").take(180)
        "Remembered secrets in ${vault.name} ($reason)"
    }

    /** [passwordLogin] is the Password / API token choice; null skips that check. */
    /** Resolves only certificate identity, never usernames or credential memory. */
    fun manualTrust(address: String, observed: String? = null): ManualTrust = try {
        check(!state.value.saveBlocked && store.trustReadError() == null) { "Saved profiles could not be read. Resolve that error before connecting manually." }
        val origin = certificateOrigin(address)
        val remembered = certificateStore.load().find { it.origin == origin }?.pin
        val saved = state.value.profiles.filter { certificateOrigin(it.origin) == origin }.mapNotNull { it.sha256Pin }.toSet()
        val known = (saved + listOfNotNull(remembered)).sorted()
        ManualTrust(origin, known, observed?.let { canonicalOptionalPin(it) }, saved.isNotEmpty())
    } catch (e: IllegalStateException) { ManualTrust(error = e.message ?: "Could not read remembered certificates.") }
      catch (e: IllegalArgumentException) { ManualTrust(error = e.message ?: "Invalid server address.") }

    fun forgetManualTrust(address: String): Boolean = try {
        val origin = certificateOrigin(address)
        val entries = certificateStore.load()
        certificateStore.save(entries.filterNot { it.origin == origin })
        mutableState.update { it.copy(formError = null, notice = "Remembered certificate forgotten. Saved-profile pins are unchanged.") }
        true
    } catch (e: IllegalStateException) {
        mutableState.update { it.copy(formError = e.message) }; false
    } catch (e: IllegalArgumentException) {
        mutableState.update { it.copy(formError = e.message) }; false
    }

    fun connectManual(address: String, tokenId: String, pin: String, kind: String = DIRECT_PVE_KIND, otp: String? = null,
                      passwordLogin: Boolean? = null, observed: String? = null, approval: ManualTrust? = null): Boolean {
        onWorkspace(workspace.state.value)
        val identity = if (passwordLogin == true && kind == DIRECT_PVE_KIND) completeLoginUser(tokenId) else tokenId.trim()
        passwordLogin?.let { authChoiceMismatch(it, identity, kind) }?.let { message ->
            mutableState.update { it.copy(formError = message) }; return false
        }
        val chars = manualSecret?.copyOf() ?: CharArray(0)
        var connection: Connection? = null
        return try {
            val trust = manualTrust(address, observed)
            check(trust.error == null) { trust.error.orEmpty() }
            val pasted = canonicalOptionalPin(pin)
            val selectedPin = if (approval != null) {
                check(approval == trust && trust.canApprove) { "Certificate approval changed. Review the current scan and saved pins." }
                check(pasted == null || pasted == trust.observed) { "The pasted fingerprint differs from the scan." }
                trust.observed!!
            } else {
                check(!trust.conflict) { "Saved certificate pins conflict. Review the saved connections before connecting." }
                val known = trust.known.singleOrNull()
                check(trust.observed == null || trust.observed == known) { "Review the scanned certificate and use Trust and connect explicitly." }
                check(known == null || pasted == null || pasted == known) { "The pasted fingerprint differs from known trust." }
                known ?: pasted
            }
            // Validate the entire action before changing disk. Approval survives authentication failure.
            connection = Connection(address, identity, chars.concatToString(), selectedPin.orEmpty(), kind, otp)
            if (approval != null) {
                val entries = certificateStore.certificates()
                certificateStore.save(entries.filterNot { it.origin == trust.origin } + ApprovedCertificate(trust.origin!!, selectedPin!!))
            }
            publishSecretClear(connectedProfileId = null, notice = null, connect = connection)
            connection = null // ownership transferred to the workspace
            true
        } catch (e: IllegalArgumentException) {
            mutableState.update { it.copy(formError = e.message) }; false
        } catch (e: IllegalStateException) {
            mutableState.update { it.copy(formError = e.message) }; false
        } finally { connection?.close(); chars.fill('\u0000') }
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

    override fun close() {
        val owned = synchronized(gate) { pending.also { pending = null } }
        scope.cancel()
        wipeSecrets()
        owned?.let { release(it.secret) }
    }

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
                rememberChoice = false,
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

    private class PendingRemember(val profile: ConnectionProfile, val secret: CharArray, val workspace: Workspace, val epoch: Long)

    private fun watchForSuccess(next: PendingRemember) {
        val previous = synchronized(gate) { pending.also { pending = next } }
        previous?.let { release(it.secret) }
        scope.launch {
            val seen = try {
                withTimeoutOrNull(rememberWaitMs) {
                    next.workspace.state.first { it.connectionEpoch != next.epoch || !it.connected || it.overview != null || it.error != null }
                }
            } catch (e: CancellationException) {
                dropPending(next)
                throw e
            }
            val worked = seen != null && seen.connectionEpoch == next.epoch && seen.connected && seen.overview != null && seen.error == null
            if (worked) storePending(next) else dropPending(next, "The secret was not remembered because the sign-in did not finish.")
        }
    }

    private fun storePending(target: PendingRemember) {
        val owned = synchronized(gate) { if (pending === target) pending.also { pending = null } else null } ?: return
        try {
            if (mutableState.value.vaultStatus !is VaultStatus.Available) return
            val current = mutableState.value.profiles.find { it.id == owned.profile.id }
            if (current == null || !current.definesSameConnection(owned.profile)) return
            vault.store(current.id, current.tokenId, "RingMASTER: ${current.displayName}", owned.secret)
            synchronized(gate) { rememberedIds.add(owned.profile.id) }
            persistMarkers()
            val ids = synchronized(gate) { rememberedIds.toSet() }
            mutableState.update { it.copy(remembered = ids, notice = "Remembered on this computer.", rememberChoice = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: VaultException) {
            mutableState.update { it.copy(notice = "The secret could not be remembered: ${e.message}") }
        } catch (_: Throwable) {
            mutableState.update { it.copy(notice = "The secret could not be remembered: the password safe failed.") }
        } finally {
            release(owned.secret)
        }
    }

    private fun dropPending(target: PendingRemember? = null, notice: String? = null) {
        val owned = synchronized(gate) {
            when {
                target == null -> pending.also { pending = null }
                pending === target -> pending.also { pending = null }
                else -> null
            }
        }
        owned?.let { release(it.secret) }
        if (notice != null && owned != null) mutableState.update { it.copy(notice = notice) }
    }

    private fun dropPendingFor(profileId: String) {
        val owned = synchronized(gate) {
            val current = pending
            if (current != null && current.profile.id == profileId) pending.also { pending = null } else null
        }
        owned?.let { release(it.secret) }
    }

    /**
     * For a deleted or redefined profile. The marker and any pending copy go at once, before this returns: a marker is
     * what lets Connect read the safe, and the old secret must never be offered to a changed server. The safe is then
     * asked to forget, marker or not, so no entry is left behind. If it refuses, the entry stays in the safe unused
     * until Clear everything, and the desk says so.
     */
    private fun forgetVaultEntry(profileId: String) {
        dropPendingFor(profileId)
        val had = synchronized(gate) { rememberedIds.remove(profileId) }
        if (had) persistMarkers()
        val ids = synchronized(gate) { rememberedIds.toSet() }
        mutableState.update { it.copy(remembered = ids) }
        scope.launch {
            try {
                vault.forget(profileId)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (had) mutableState.update { it.copy(notice = "The remembered secret could not be removed from ${vault.name}: ${vaultFailureReason(t)} Clear everything in About removes what is left.") }
            }
        }
    }

    private fun persistMarkers() {
        val ids = synchronized(gate) { rememberedIds.toSet() }
        runCatching { rememberedProfiles.save(ids) }
    }

    private fun release(chars: CharArray) {
        chars.fill('\u0000')
        onSecretReleased?.invoke(chars)
    }

    private fun vaultFailureReason(error: Throwable): String = when (error) {
        is VaultException -> error.message ?: "The password safe could not be used."
        is UnsatisfiedLinkError, is NoClassDefFoundError -> "The password safe could not be loaded."
        else -> "The password safe could not be used."
    }
}
