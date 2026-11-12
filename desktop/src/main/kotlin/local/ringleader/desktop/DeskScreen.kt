package local.ringleader.desktop

import androidx.compose.ui.draw.blur
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import local.ringleader.core.ConnectionProfile
import local.ringleader.core.DEFAULT_PASSWORD_USER
import local.ringleader.core.DIRECT_PVE_KIND
import local.ringleader.core.DeskState
import local.ringleader.core.VaultStatus
import local.ringleader.core.completeLoginUser
import local.ringleader.core.passwordUserAfterSwitch
import local.ringleader.core.OpenSession
import local.ringleader.core.PDM_KIND
import androidx.compose.runtime.collectAsState
import local.ringleader.core.ProfileDesk
import local.ringleader.core.ScanHit
import local.ringleader.core.ScanKind
import local.ringleader.core.WorkspaceState
import local.ringleader.core.connectionKindLabel

private val DeskContentWidth = 1180.dp
private val DeskSplitWidth = 1000.dp
private val ConnectPanelWidth = 520.dp

/**
 * The connection desk. [sessions] are the connections open at the same time (empty: the one workspace given is the only
 * one); each gets a card, and a new connection opens beside them.
 */
@Composable fun DeskScreen(
    workspaceState: WorkspaceState, deskState: DeskState, desk: ProfileDesk, scanned: ScanHit? = null, initialScroll: Int = 0, onScan: () -> Unit = {},
    sessions: List<OpenSession> = emptyList(), activeSession: Long = 0, sessionNotice: String? = null,
    onShowSession: (Long) -> Unit = {}, onDisconnectSession: (Long) -> Unit = {}, motion: MotionPreferences? = null, onOpenWorkspace: () -> Unit,
) {
    val motionPreferences = motion ?: remember { MotionPreferences() }
    val active = deskState.profiles.find { it.id == deskState.connectedProfileId }
    val scroll = rememberScrollState(initialScroll)
    val inViewOpen = workspaceState.connected || workspaceState.demo
    // Anything open counts, in view or not: the Connect button then adds a connection.
    val anyOpen = inViewOpen || sessions.any { it.id != activeSession }
    val connectedProfiles = sessions.mapNotNull { it.profileId }.toSet() + listOfNotNull(deskState.connectedProfileId)
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BoxWithConstraints(Modifier.widthIn(max = DeskContentWidth).fillMaxWidth()) {
                val split = maxWidth >= DeskSplitWidth
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tech.Space12)) {
                    if (sessions.isEmpty()) { if (inViewOpen) SessionCard(workspaceState, active, null, onOpenWorkspace) { desk.disconnect() } }
                    else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        sessions.forEachIndexed { index, open ->
                            key(open.id) {
                                val state by open.workspace.state.collectAsState()
                                val inView = open.id == activeSession
                                if (state.connected || state.demo) SessionCard(state, deskState.profiles.find { it.id == open.profileId }, inView.takeIf { sessions.size > 1 },
                                    { if (!inView) onShowSession(open.id); onOpenWorkspace() }, number = (index + 1).takeIf { sessions.size > 1 }) { onDisconnectSession(open.id) }
                            }
                        }
                    }
                    DeskMessages(deskState, workspaceState, sessionNotice)
                    if (split) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                            SavedConnections(Modifier.weight(1f), deskState, desk, onScan, onOpenWorkspace, anyOpen, connectedProfiles)
                            ManualConnection(Modifier.width(ConnectPanelWidth), anyOpen, deskState, desk, scanned, motionPreferences, onOpenWorkspace)
                        }
                    } else {
                        SavedConnections(Modifier.fillMaxWidth(), deskState, desk, onScan, onOpenWorkspace, anyOpen, connectedProfiles)
                        ManualConnection(Modifier.widthIn(max = ConnectPanelWidth).fillMaxWidth(), anyOpen, deskState, desk, scanned, motionPreferences, onOpenWorkspace)
                    }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable private fun DeskMessages(deskState: DeskState, workspaceState: WorkspaceState, sessionNotice: String? = null) {
    // While a profile is being edited, its error is shown beside the Save button instead of up here.
    val formError = deskState.formError.takeIf { !deskState.editing }
    if (formError == null && deskState.notice == null && workspaceState.error == null && sessionNotice == null) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        formError?.let { Text(it, color = Tech.Red, fontSize = 12.sp, fontFamily = Tech.Mono) }
        deskState.notice?.let { Text(it, color = Tech.Amber, fontSize = 12.sp, fontFamily = Tech.Mono) }
        sessionNotice?.let { Text(it, color = Tech.Amber, fontSize = 12.sp, fontFamily = Tech.Mono) }
        workspaceState.error?.let { Text(it, color = Tech.Red, fontSize = 12.sp, fontFamily = Tech.Mono) }
    }
}

/** One open connection. [inView] is null when it is the only one; otherwise it says whether the pages show this one. */
@Composable private fun SessionCard(workspaceState: WorkspaceState, active: ConnectionProfile?, inView: Boolean?, onOpenWorkspace: () -> Unit, number: Int? = null, onDisconnect: () -> Unit) {
    val demo = workspaceState.demo
    TechPanel(Modifier.fillMaxWidth(), rail = if (demo) Tech.Amber else Tech.Green, padding = 8.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The number is the one the sidebar shows for this session.
            Text(if (number == null) "SESSION" else "SESSION $number", fontFamily = Tech.Mono, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Tech.Muted)
            SessionDot(demo = demo)
            Text(
                sessionText(workspaceState, active),
                Modifier.weight(1f).identity(),
                fontFamily = Tech.Mono,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (inView == true) NeutralTag("In view", help = "The pages show this connection. Open another one to switch.")
            MiniAction("Open workspace", primary = inView != false, onClick = onOpenWorkspace)
            MiniAction("Disconnect", onClick = onDisconnect)
        }
    }
}

@Composable private fun SessionDot(demo: Boolean) {
    val reduce = LocalReduceMotion.current
    val phase = LocalMotionPhase.current
    val pulse = !demo && !reduce && phase.active.value
    Canvas(Modifier.size(8.dp)) {
        val alpha = if (!pulse) 1f else 0.35f + 0.65f * ((phase.pulse.floatValue - 0.15f) / 0.45f).coerceIn(0f, 1f)
        drawCircle((if (demo) Tech.Amber else Tech.Green).copy(alpha = alpha))
    }
}

@Composable private fun SavedConnections(modifier: Modifier, deskState: DeskState, desk: ProfileDesk, onScan: () -> Unit, onOpenWorkspace: () -> Unit, connected: Boolean = false, connectedProfiles: Set<String> = emptySet()) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
        SectionLabel("Saved connections", suffix = deskState.profiles.size.takeIf { it > 0 }?.toString())
        if (deskState.profiles.isNotEmpty()) {
            // "Secrets stay in memory" alone stopped being the whole truth when Remember arrived. It still is where Remember
            // cannot be used: in the browser, and without a password safe.
            val canRemember = !LocalAppHost.current.web && deskState.vaultStatus is VaultStatus.Available
            HelpTip(if (canRemember) "Profiles save the server, token ID and optional verified certificate pin. A secret stays in memory unless you tick Remember on this computer for that connection; then ${deskState.vaultName} keeps it."
                else "Profiles save the server, token ID and optional verified certificate pin. Secrets stay in memory.") {
                Text(if (canRemember) "Secrets stay in memory unless you choose Remember" else "Secrets stay in memory", fontSize = 10.sp, color = Tech.Muted, fontFamily = Tech.Mono)
            }
        }
        deskState.storeMessage?.let {
            TechPanel(Modifier.fillMaxWidth(), rail = Tech.Red, padding = 8.dp) {
                Text(it, color = Tech.Red, fontSize = 12.sp)
                Text("You can still connect manually or explore the demo.", color = Tech.Muted, fontSize = Tech.Label)
            }
        }
        if (deskState.profiles.isEmpty() && deskState.storeMessage == null) EmptyConnections()
        deskState.profiles.forEach { profile ->
            key(profile.id) { ProfileRow(profile, deskState, desk, profile.id in connectedProfiles) }
        }
        // Scanning is always one click away here, whether or not a connection is open.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniAction("New profile", enabled = !deskState.saveBlocked && !deskState.editing, onClick = desk::beginCreate)
            HelpTip("Find Proxmox servers on your networks. No credentials are sent.") { MiniAction("Scan network", onClick = onScan) }
        }
        val pending = deskState.profiles.find { it.id == deskState.pendingDeleteId }
        if (pending != null) DeleteConfirmation(pending, pending.id == deskState.connectedProfileId, desk)
        if (deskState.editing) ProfileEditor(deskState, desk)
        else if (deskState.selectedId != null && pending == null) ProfileConnect(deskState, desk, onOpenWorkspace)
        if (deskState.profiles.isEmpty() && !deskState.editing && !connected) QuickStart(onScan)
    }
}

/** First-run guide shown while no profile is saved: token, access, certificate pin, discovery. */
@Composable private fun QuickStart(onScan: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    SectionLabel("Quick start")
    val steps = listOf(
        Triple("01", "SIGN IN", "Password · your Proxmox VE user, for example root@pam, with a two-factor code if the account uses one.\nAPI token · PVE: Datacenter → Permissions → API Tokens → Add. PDM: Configuration → Access Control → API Token.\nThe token ID looks like user@realm!name; copy the secret once, it is shown only at creation."),
        Triple("02", "GRANT ACCESS", "Read-only fleet view · PVEAuditor on /.\nPower and snapshots · add VM.PowerMgmt and VM.Snapshot where you want them.\nWith privilege separation on, the token needs its own permissions."),
        Triple("03", "PIN THE CERTIFICATE", "Self-signed servers · on the node run\nopenssl x509 -in /etc/pve/local/pve-ssl.pem -noout -fingerprint -sha256\nand paste the value after the = sign (colons are fine). Use pveproxy-ssl.pem if a custom certificate is installed."),
    )
    steps.forEach { (n, title, body) ->
        Row(Modifier.fillMaxWidth().border(1.dp, Tech.Edge, Tech.Control).background(Tech.Black.copy(alpha = .4f)).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(n, fontFamily = Tech.Mono, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Tech.Amber.copy(alpha = .8f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = Tech.Muted)
                Text(body, fontFamily = Tech.Mono, fontSize = 11.sp, lineHeight = 16.sp, color = Tech.Ink.copy(alpha = .85f))
            }
        }
    }
    Row(Modifier.fillMaxWidth().border(1.dp, Tech.Edge, Tech.Control).background(Tech.Black.copy(alpha = .4f)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("04", fontFamily = Tech.Mono, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Tech.Amber.copy(alpha = .8f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("NOT SURE OF THE ADDRESS?", fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = Tech.Muted)
            Text("Scan the local network for PVE (8006) and PDM (8443). No credentials are sent.", fontFamily = Tech.Mono, fontSize = 11.sp, lineHeight = 16.sp, color = Tech.Ink.copy(alpha = .85f))
        }
        MiniAction("Scan · Ctrl ${ringPages.indexOf("Scan") + 1}", primary = true, onClick = onScan)
    }
}

@Composable private fun ProfileRow(profile: ConnectionProfile, deskState: DeskState, desk: ProfileDesk, connected: Boolean = profile.id == deskState.connectedProfileId) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val selected = profile.id == deskState.selectedId
    val bg by animateColorAsState(
        if (selected) Tech.Raised else if (hovered) Tech.Raised.copy(alpha = .55f) else Tech.Black.copy(alpha = .4f),
        tween(if (LocalReduceMotion.current) 0 else 120),
    )
    Row(
        Modifier.fillMaxWidth().height(44.dp).hoverable(interaction).clip(Tech.Control).background(bg).border(1.dp, if (selected) Tech.Amber else Tech.Edge, Tech.Control).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KindBadge(if (profile.kind == PDM_KIND) "PDM" else "PVE")
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(profile.displayName, Modifier.identity(), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            HelpTip("${profile.origin} · ${profile.tokenId} · ${if (profile.sha256Pin == null) "No pin" else "Pin saved"}") {
                Text(profile.origin, Modifier.identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (profile.sha256Pin != null) NeutralTag("Pinned", help = "A SHA-256 certificate pin is saved. RingMASTER checks the server certificate against it.")
        // The browser host never uses the safe, so it does not advertise what is in it either.
        if (!LocalAppHost.current.web && profile.id in deskState.remembered) NeutralTag("Remembered", help = "Its secret is kept in ${deskState.vaultName}. Forget it from the Connect form.")
        if (connected) HealthyTag("Connected", help = "A connection opened from this profile is in use.")
        MiniAction("Connect", primary = true) { desk.selectProfile(profile.id) }
        if (hovered || selected) {
            MiniAction("Edit", enabled = !deskState.saveBlocked && !deskState.editing) { desk.selectProfile(profile.id); desk.beginEdit() }
            MiniAction("Delete", enabled = !deskState.saveBlocked) { desk.selectProfile(profile.id); desk.requestDelete() }
        }
    }
}

@Composable private fun KindBadge(label: String) {
    val pdm = label == "PDM"
    Box(
        Modifier.height(18.dp).widthIn(min = 34.dp).background(Tech.Raised, Tech.Control).border(1.dp, if (pdm) Tech.Amber else Tech.Green, Tech.Control).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Tech.Mono, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, color = if (pdm) Tech.Amber else Tech.Green)
    }
}

@Composable private fun EmptyConnections() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        EmptyRadar()
        Text("No saved connections", color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 12.sp)
    }
}

/** The app emblem, still. The empty state used to spin a radar forever although nothing was being scanned. */
@Composable private fun EmptyRadar() {
    androidx.compose.foundation.Image(AppIcon.large, contentDescription = null, modifier = Modifier.size(56.dp))
}

@Composable private fun DeleteConfirmation(profile: ConnectionProfile, active: Boolean, desk: ProfileDesk) {
    TechPanel(Modifier.widthIn(max = 560.dp).fillMaxWidth(), rail = Tech.Red) {
        Text("Delete ${profile.displayName}?", fontSize = Tech.Section)
        Text("This removes the saved server and user or token ID from this computer, and forgets its remembered secret if it has one. Passwords and token secrets are never stored in a profile.", color = Tech.Muted, fontSize = 13.sp)
        if (active) Text("This session will disconnect, and its inventory will close.", color = Tech.Amber, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tech.Space12), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
            TechAction("Delete profile", active = true) { desk.confirmDelete() }
            TechAction("Keep profile", onClick = desk::cancelDelete)
        }
    }
}

@Composable private fun ProfileEditor(deskState: DeskState, desk: ProfileDesk) {
    val draft = deskState.draft
    val active = draft.id != null && draft.id == deskState.connectedProfileId
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
        SectionLabel(if (deskState.profiles.any { it.id == draft.id }) "Edit profile" else "New profile")
        var passwordMode by remember(draft.id) { mutableStateOf(draft.tokenId.isNotEmpty() && !draft.tokenId.contains('!')) }
        KindChoice(draft.kind) { if (it == PDM_KIND) passwordMode = false; desk.updateDraft(draft.displayName, draft.origin, draft.tokenId, draft.sha256Pin, it) }
        if (draft.kind == DIRECT_PVE_KIND) AuthChoice(passwordMode) { chosen ->
            passwordMode = chosen
            if (chosen) {
                val next = passwordUserAfterSwitch(draft.tokenId)
                if (next != draft.tokenId) desk.updateDraft(draft.displayName, draft.origin, next, draft.sha256Pin)
            }
        }
        CompactDeskField(draft.displayName, { desk.updateDraft(it, draft.origin, draft.tokenId, draft.sha256Pin) }, "Display name", identity = true)
        CompactDeskField(draft.origin, { desk.updateDraft(draft.displayName, it, draft.tokenId, draft.sha256Pin) }, "Server address", originHint(draft.kind), identity = true)
        CompactDeskField(draft.tokenId, { desk.updateDraft(draft.displayName, draft.origin, it, draft.sha256Pin) }, if (passwordMode) "User · user@realm, for example root@pam" else "API token ID · user@realm!token", identity = true, passwordUser = passwordMode && draft.kind == DIRECT_PVE_KIND)
        CompactDeskField(draft.sha256Pin, { desk.updateDraft(draft.displayName, draft.origin, draft.tokenId, it) }, "Trusted certificate SHA-256 · optional")
        Text("Passwords and token secrets are not part of a profile; you enter them when you connect. Saving a different server, user, token ID, certificate pin, or connection kind on the connection in use disconnects that session.", style = MaterialTheme.typography.bodySmall, color = Tech.Muted)
        if (draft.id != null && draft.id in deskState.remembered) {
            Text("Changing the server, kind, user or token ID, or pin forgets the remembered secret.", color = Tech.Muted, fontSize = 12.sp)
        }
        if (active) Text("This profile is in use. A connection change will disconnect it instead of switching the open session.", color = Tech.Amber, fontSize = 13.sp)
        deskState.formError?.let { Text(it, color = Tech.Red, fontSize = 12.sp, fontFamily = Tech.Mono) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tech.Space12), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
            TechAction("Save profile", active = true) {
                val login = passwordMode && draft.kind == DIRECT_PVE_KIND
                if (login) {
                    val completed = completeLoginUser(draft.tokenId)
                    if (completed != draft.tokenId) desk.updateDraft(draft.displayName, draft.origin, completed, draft.sha256Pin)
                }
                desk.saveDraft(passwordLogin = login)
            }
            TechAction("Cancel", onClick = desk::cancelEdit)
        }
    }
}

@Composable private fun ProfileConnect(deskState: DeskState, desk: ProfileDesk, onOpenWorkspace: () -> Unit) {
    val profile = deskState.profiles.find { it.id == deskState.selectedId } ?: return
    val secretFocus = remember { FocusRequester() }
    DisposableEffect(profile.id) {
        WindowTitle.connectTo = profile.displayName
        onDispose { WindowTitle.connectTo = null }
    }
    // Once per selection and per secret clear, after the field is attached. A later recomposition does not move the cursor.
    LaunchedEffect(profile.id, deskState.secretGeneration) {
        yield()
        runCatching { secretFocus.requestFocus() }
    }
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
        SectionLabel("Connect", suffix = profile.displayName)
        val password = profile.passwordLogin
        var otp by remember(profile.id, deskState.secretGeneration) { mutableStateOf("") }
        val vaultReady = deskState.vaultStatus is VaultStatus.Available
        val web = LocalAppHost.current.web
        val remembered = !web && profile.id in deskState.remembered
        val scope = rememberCoroutineScope()
        val submit = {
            when {
                deskState.profileSecretPresent -> { if (desk.connectSelected(otp = otp.ifBlank { null })) onOpenWorkspace() }
                remembered && vaultReady && !deskState.vaultBusy -> { scope.launch { if (desk.connectRemembered(otp.ifBlank { null })) onOpenWorkspace() } }
            }
        }
        val explanation = when {
            remembered && password -> "The password is remembered on this computer. Leave the field empty to use it, or type a new one."
            remembered -> "The token secret is remembered on this computer. Leave the field empty to use it, or type a new one."
            password -> "Enter the password for this user. It is cleared when you connect, cancel, choose another profile, or leave the desk."
            else -> "Enter the token secret for this session. It is cleared when you connect, cancel, choose another profile, or leave the desk."
        }
        Text(explanation, style = MaterialTheme.typography.bodySmall, color = Tech.Muted)
        SecretField(deskState.secretGeneration, if (password) "Password" else "API token secret", focusRequester = secretFocus, onSubmit = submit, capsLock = password) { desk.setProfileSecret(it) }
        if (password) CompactDeskField(otp, { otp = it.filter { c -> !c.isWhitespace() }.take(64) }, "Two-factor code · only if the account uses one", onSubmit = submit)
        if (!web) RememberRow(deskState, desk, profile.id, remembered)
        if (deskState.vaultBusy) Text("Reading the remembered secret…", color = Tech.Muted, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tech.Space12), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
            TechAction("Connect", active = true, enabled = deskState.profileSecretPresent || (remembered && vaultReady && !deskState.vaultBusy), onClick = submit)
            TechAction("Clear secret", onClick = desk::discardSecrets)
        }
    }
}

@Composable private fun RememberRow(deskState: DeskState, desk: ProfileDesk, profileId: String, remembered: Boolean) {
    val status = deskState.vaultStatus ?: return
    when {
        remembered -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Remembered in ${deskState.vaultName}.", color = Tech.Muted, fontSize = 12.sp)
                MiniAction("Forget") { desk.forgetRemembered(profileId) }
            }
            if (status is VaultStatus.Unavailable) Text(status.reason, color = Tech.Muted, fontSize = 12.sp)
        }
        status is VaultStatus.Available -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TechCheck(deskState.rememberChoice, desk::setRememberChoice, "Remember on this computer")
            Text(rememberHelp(deskState.vaultName), Modifier.weight(1f, fill = false), color = Tech.Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        status is VaultStatus.Unavailable -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TechCheck(false, {}, "Remember on this computer", enabled = false)
            Text(status.reason, color = Tech.Muted, fontSize = 12.sp)
        }
    }
}

private fun rememberHelp(vaultName: String): String = when {
    vaultName.startsWith("Windows") -> "Kept in Windows Credential Manager for your Windows account, and only after the sign-in works. Programs you run can read it."
    else -> "Kept in your password safe, and only after the sign-in works. Programs you run can read it while the safe is unlocked."
}

@Composable private fun ManualConnection(modifier: Modifier, anyOpen: Boolean, deskState: DeskState, desk: ProfileDesk, scanned: ScanHit?, motion: MotionPreferences, onOpenWorkspace: () -> Unit) {
    var address by remember(scanned) { mutableStateOf(scanned?.origin.orEmpty()) }
    var tokenId by remember { mutableStateOf("") }
    // The first start keeps API token. Password mode fills the usual user; the choice itself is the only thing remembered.
    var passwordChoice by remember { mutableStateOf(motion.option(DESK_PASSWORD_MODE, false)) }
    var user by remember { mutableStateOf(if (passwordChoice) DEFAULT_PASSWORD_USER else "") }
    var otp by remember(deskState.secretGeneration) { mutableStateOf("") }
    var fingerprint by remember(scanned) { mutableStateOf("") }
    var kind by remember(scanned) { mutableStateOf(if (scanned?.kind == ScanKind.PDM) PDM_KIND else DIRECT_PVE_KIND) }
    val passwordMode = passwordChoice && kind == DIRECT_PVE_KIND
    val pinPrompt = remember(scanned) { ScannedPinPrompt().also { p -> scanned?.fingerprint?.let { p.offer(scanned.origin, it) } } }
    var pinConfirmShowing by remember(scanned) { mutableStateOf(false) }
    TechPanel(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
            SectionLabel("Connect")
            HelpTip("A new connection opens beside the ones already open; nothing is replaced. Verify private certificate fingerprints with your administrator.") {
                Text(if (anyOpen) "Opens beside the open connection · secrets stay in memory" else "Connect without saving a profile · secrets stay in memory", fontSize = 11.sp, color = Tech.Muted, fontFamily = Tech.Mono)
            }
            KindChoice(kind) { kind = it }
            if (kind == DIRECT_PVE_KIND) AuthChoice(passwordMode) { chosen ->
                passwordChoice = chosen
                motion.put(DESK_PASSWORD_MODE, chosen)
                if (chosen) user = passwordUserAfterSwitch(tokenId.ifBlank { user })
            }
            val login = passwordMode && kind == DIRECT_PVE_KIND
            val canConnect = address.isNotBlank() && (if (passwordMode) user else tokenId).isNotBlank() && deskState.manualSecretPresent
            val submit = {
                if (canConnect) {
                    val completed = if (login) completeLoginUser(user) else tokenId
                    if (login && completed != user) user = completed
                    if (desk.connectManual(address, if (login) completed else tokenId, fingerprint, kind, otp = if (passwordMode) otp.ifBlank { null } else null,
                            passwordLogin = login)) onOpenWorkspace()
                }
            }
            // Tab order is composition order. These fields stay consecutive:
            // Server address, user or token ID, password or secret, (two-factor code), Trusted certificate SHA-256.
            CompactDeskField(address, {
                address = it
                // A pin confirmed from the scan belongs to the scanned address only.
                if (pinPrompt.addressChanged(it, fingerprint)) fingerprint = ""
                if (!pinPrompt.pending) pinConfirmShowing = false
            }, "Server address", identity = true)
            if (passwordMode) {
                CompactDeskField(user, { user = it.trim() }, "User · user@realm, for example root@pam", identity = true, passwordUser = true)
                SecretField(deskState.secretGeneration, "Password", onSubmit = submit, capsLock = true) { desk.setManualSecret(it) }
                CompactDeskField(otp, { otp = it.filter { c -> !c.isWhitespace() }.take(64) }, "Two-factor code · only if the account uses one", onSubmit = submit)
            } else {
                CompactDeskField(tokenId, { tokenId = it }, "API token ID · user@realm!token", identity = true)
                SecretField(deskState.secretGeneration, "API token secret", onSubmit = submit) { desk.setManualSecret(it) }
            }
            CompactDeskField(fingerprint, { fingerprint = it }, "Trusted certificate SHA-256 · optional")
            if (scanned != null && address == scanned.origin) {
                scanned.fingerprint?.let { seen ->
                    Text("Scanned SHA-256: $seen", fontFamily = FontFamily.Monospace, fontSize = Tech.Label, color = Tech.Amber)
                    Text("Compare with the server's real fingerprint before trusting it. Proxmox: node › System › Certificates.", color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 11.sp, lineHeight = 15.sp)
                    // Offered again here: an earlier confirm or address edit may have dropped the offer.
                    TechAction("Use scanned fingerprint…") { pinPrompt.offer(scanned.origin, seen); pinPrompt.request(); pinConfirmShowing = true }
                }
            }
            if (pinConfirmShowing) ScannedPinConfirmation(
                address = pinPrompt.address ?: address,
                fp = pinPrompt.fingerprint ?: scanned?.fingerprint.orEmpty(),
                onPin = { pinPrompt.confirm()?.let { fingerprint = it }; pinConfirmShowing = false },
                onCancel = { pinPrompt.cancel(); pinConfirmShowing = false },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tech.Space12), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
                TechAction(if (anyOpen) "Add connection" else "Connect", active = true, enabled = canConnect, onClick = submit)
                TechAction("Explore demo") { if (desk.exploreDemo()) onOpenWorkspace() }
            }
        }
    }
}

@Composable private fun ScannedPinConfirmation(address: String, fp: String, onPin: () -> Unit, onCancel: () -> Unit) {
    TechPanel(Modifier.widthIn(max = 560.dp).fillMaxWidth(), rail = Tech.Amber) {
        Text("Pin this fingerprint?", fontSize = Tech.Section)
        Text("A scan cannot verify the server. Whoever answers on this address can present their own certificate.", color = Tech.Muted, fontSize = 13.sp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(address, fontFamily = Tech.Mono, fontSize = 12.sp, color = Tech.Ink)
            Text(fp, fontFamily = Tech.Mono, fontSize = 12.sp, color = Tech.Amber)
        }
        Text("Compare with the fingerprint the server itself shows. Proxmox: node › System › Certificates.", color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 11.sp, lineHeight = 15.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tech.Space12), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
            TechAction("Pin this fingerprint", onClick = onPin)
            TechAction("Cancel", active = true, onClick = onCancel)
        }
    }
}

/** Direct PVE only: sign in with an API token or with a user name and password. */
@Composable private fun AuthChoice(password: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(28.dp).clip(Tech.Control).border(1.dp, Tech.Edge, Tech.Control)) {
        KindSegment("API token", !password, Modifier.weight(1f)) { onChange(false) }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Tech.Edge))
        KindSegment("Password", password, Modifier.weight(1f)) { onChange(true) }
    }
}

@Composable private fun KindChoice(kind: String, onKind: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().height(28.dp).clip(Tech.Control).border(1.dp, Tech.Edge, Tech.Control)) {
        KindSegment("Direct PVE", kind == DIRECT_PVE_KIND, Modifier.weight(1f)) { onKind(DIRECT_PVE_KIND) }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Tech.Edge))
        KindSegment("PDM", kind == PDM_KIND, Modifier.weight(1f)) { onKind(PDM_KIND) }
    }
    HelpTip("Include the port explicitly. The server address is used exactly; port 8443 is not added.") {
            }
}

@Composable private fun KindSegment(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val tint = if (selected || focused) Tech.Amber else Tech.Edge
    Box(
        modifier.fillMaxHeight().background(when {
            selected -> Tech.AmberWash
            hovered -> Tech.Raised
            else -> Color.Transparent
        }).clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick).border(1.dp, if (focused) tint else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), fontFamily = Tech.Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.6.sp, color = if (selected) Tech.Amber else Tech.Ink)
    }
}

private fun originHint(kind: String) = if (kind == PDM_KIND) "https://pdm.example.test:8443" else "https://pve.example.test:8006"

/** Caps Lock is unsupported on some Linux desktops; then the warning stays hidden. Tests can replace the reader. */
internal var capsLockReader: () -> Boolean = {
    runCatching { java.awt.Toolkit.getDefaultToolkit().getLockingKeyState(java.awt.event.KeyEvent.VK_CAPS_LOCK) }.getOrDefault(false)
}

internal fun capsLockOn(): Boolean = capsLockReader()

/** Enter connects when [onSubmit] is set. Paste and every other key are left for the text field. */
internal fun submitOnEnter(event: KeyEvent, onSubmit: (() -> Unit)?): Boolean {
    return if (onSubmit != null && event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
        onSubmit()
        true
    } else false
}

@Composable private fun SecretField(
    generation: Int,
    label: String,
    focusRequester: FocusRequester? = null,
    onSubmit: (() -> Unit)? = null,
    capsLock: Boolean = false,
    onValue: (String) -> Unit,
) {
    var value by remember(generation) { mutableStateOf("") }
    var revealed by remember(generation) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var caps by remember { mutableStateOf(false) }
    val showcase = Appearance.showcase
    LaunchedEffect(focused, capsLock) {
        if (!focused) revealed = false
        if (focused && capsLock) caps = capsLockOn()
    }
    LaunchedEffect(showcase) { if (showcase) revealed = false }
    val hidden = showcase || !revealed
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).height(30.dp).border(1.dp, Tech.Edge, Tech.Control).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value,
                    { value = it; onValue(it) },
                    Modifier.fillMaxWidth()
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                        .onPreviewKeyEvent { event ->
                            if (capsLock) caps = capsLockOn()
                            submitOnEnter(event, onSubmit)
                        },
                    singleLine = true,
                    visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
                    textStyle = TextStyle(color = Tech.Ink, fontSize = 12.sp),
                    cursorBrush = SolidColor(Tech.Amber),
                    interactionSource = interaction,
                )
            }
            if (!showcase) SecretToggle(if (revealed) "Hide" else "Show") { revealed = !revealed }
        }
        if (capsLock && focused && caps) Text("Caps Lock is on", fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Amber)
    }
}

/** Show / Hide sits at the end of a secret field and does not take focus, so the password stays visible while it is used. */
@Composable private fun SecretToggle(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.height(26.dp).border(1.dp, Tech.Edge, Tech.Control)
            .focusProperties { canFocus = false }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Tech.Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.5.sp, color = Tech.Ink)
    }
}

private fun sessionText(state: WorkspaceState, active: ConnectionProfile?) = when {
    state.demo -> "Demo workspace is open."
    active != null && state.connected -> "Connected with ${active.displayName} · ${connectionKindLabel(active.kind)} · ${active.origin}"
    else -> "Current connection: ${connectionKindLabel(state.kind)} · ${state.address}"
}

/**
 * A one-line field. [identity] fields (addresses, user and token IDs, profile names) show a bullet mask under the
 * showcase blur until they are focused for editing, so the real value is never on screen during a demo.
 */
@Composable private fun CompactDeskField(value: String, onValue: (String) -> Unit, label: String, placeholder: String? = null, identity: Boolean = false, onSubmit: (() -> Unit)? = null, passwordUser: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val concealed = identity && identityFieldConcealed(Appearance.showcase, focused, value)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.fillMaxWidth().height(30.dp).border(1.dp, Tech.Edge, Tech.Control).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty() && placeholder != null) {
                Text(placeholder, fontFamily = Tech.Mono, fontSize = 12.sp, color = Tech.Muted.copy(alpha = .55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(value, onValue, Modifier.fillMaxWidth()
                .then(if (concealed) Modifier.blur(3.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded).semantics { contentDescription = "Hidden" } else Modifier)
                .onPreviewKeyEvent { submitOnEnter(it, onSubmit) },
                singleLine = true, textStyle = TextStyle(color = Tech.Ink, fontSize = 12.sp, fontFamily = Tech.Mono), cursorBrush = SolidColor(Tech.Amber), interactionSource = interaction,
                visualTransformation = if (concealed) VisualTransformation { t -> androidx.compose.ui.text.input.TransformedText(androidx.compose.ui.text.AnnotatedString(identityMask(t.text)), androidx.compose.ui.text.input.OffsetMapping.Identity) } else VisualTransformation.None)
        }
        if (passwordUser) {
            val typed = value.trim()
            if (typed.isNotEmpty() && '@' !in typed && '!' !in typed) {
                val completed = completeLoginUser(typed)
                val hideName = identityFieldConcealed(Appearance.showcase, focused, typed)
                Text(
                    "Signs in as ${if (hideName) identityMask(completed) else completed} (Linux PAM, the Proxmox default realm)",
                    Modifier.identity(),
                    fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted,
                )
            }
        }
    }
}
