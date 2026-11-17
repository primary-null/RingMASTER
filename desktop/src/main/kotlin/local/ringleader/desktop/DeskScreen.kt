package local.ringleader.desktop

import androidx.compose.ui.draw.blur
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
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
import local.ringleader.core.certificateOrigin
import local.ringleader.core.DEFAULT_PASSWORD_USER
import local.ringleader.core.DIRECT_PVE_KIND
import local.ringleader.core.DeskState
import local.ringleader.core.ManualForm
import local.ringleader.core.ManualTrust
import local.ringleader.core.NormalTls
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
import local.ringleader.core.connectionFailed
import local.ringleader.core.connectionTrustLabel
import local.ringleader.core.connectionKindLabel
import local.ringleader.core.sessionOpen
import local.ringleader.core.completeServerAddress
import local.ringleader.core.canonicalOptionalPin
import local.ringleader.core.ProbedCertificate
import local.ringleader.core.ProfileDraft
import local.ringleader.core.forgetCertificateQuestion
import local.ringleader.core.NetworkFailure
import local.ringleader.core.certificateDay

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
    onShowSession: (Long) -> Unit = {}, onDisconnectSession: (Long) -> Unit = {}, motion: MotionPreferences? = null,
    /** When [scanned] was picked (System.nanoTime), so its certificate offer lapses five minutes after the pick. Null: now. */
    scannedAt: Long? = null,
    /** Called when the scanned certificate's offer is over for good: cancelled, edited away or used. The window then forgets the pick. */
    onScannedEnded: () -> Unit = {}, onOpenWorkspace: () -> Unit,
) {
    val motionPreferences = motion ?: remember { MotionPreferences() }
    val active = deskState.profiles.find { it.id == deskState.connectedProfileId }
    val scroll = rememberScrollState(initialScroll)
    // A connection whose first read failed (certificate refused, server not reached) is not an open one: it gets no
    // session card and does not turn Connect into Add connection. Its reason is in the messages below.
    val inViewOpen = sessionOpen(workspaceState)
    // Anything open counts, in view or not: the Connect button then adds a connection.
    val anyOpen = inViewOpen || sessions.any { it.id != activeSession }
    val connectedProfiles = sessions.mapNotNull { it.profileId }.toSet() + listOfNotNull(deskState.connectedProfileId)
    // The session in view was refused on the server's certificate (any certificate cause of NetworkFailure): the saved
    // connection's panel then reads that certificate.
    val certificateRefused = workspaceState.address.takeIf { workspaceState.failure?.certificate == true && it.isNotBlank() }
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
                                if (sessionOpen(state)) SessionCard(state, deskState.profiles.find { it.id == open.profileId }, inView.takeIf { sessions.size > 1 },
                                    { if (!inView) onShowSession(open.id); onOpenWorkspace() }, number = (index + 1).takeIf { sessions.size > 1 }) { onDisconnectSession(open.id) }
                            }
                        }
                    }
                    DeskMessages(deskState, workspaceState, sessionNotice)
                    if (split) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                            SavedConnections(Modifier.weight(1f), deskState, desk, onScan, onOpenWorkspace, anyOpen, connectedProfiles, showQuickStart = scanned == null, certificateRefused = certificateRefused)
                            ManualConnection(Modifier.width(ConnectPanelWidth), anyOpen, deskState, desk, scanned, scannedAt, motionPreferences, onScannedEnded, onOpenWorkspace)
                        }
                    } else {
                        if (scanned == null) SavedConnections(Modifier.fillMaxWidth(), deskState, desk, onScan, onOpenWorkspace, anyOpen, connectedProfiles, showQuickStart = scanned == null, certificateRefused = certificateRefused)
                        ManualConnection(Modifier.widthIn(max = ConnectPanelWidth).fillMaxWidth(), anyOpen, deskState, desk, scanned, scannedAt, motionPreferences, onScannedEnded, onOpenWorkspace)
                        if (scanned != null) SavedConnections(Modifier.fillMaxWidth(), deskState, desk, onScan, onOpenWorkspace, anyOpen, connectedProfiles, showQuickStart = scanned == null, certificateRefused = certificateRefused)
                    }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight(), style = visibleScrollbarStyle())
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
        workspaceState.error?.let { error ->
            // With several connections open, a refusal has to say which server it came from.
            val server = workspaceState.failedAddress.ifEmpty { workspaceState.address }
            if (connectionFailed(workspaceState) && server.isNotEmpty()) {
                Text("Connection to ${if (Appearance.showcase) "the server" else server} failed", Modifier.identity(), color = Tech.Red, fontSize = 12.sp, fontFamily = Tech.Mono)
            }
            Text(error, color = Tech.Red, fontSize = 12.sp, fontFamily = Tech.Mono)
            // Each cause has its own next step; a certificate cause is handled in the panels below as well.
            workspaceState.failure?.let { Text(it.nextStep, color = Tech.Muted, fontSize = 12.sp, lineHeight = 16.sp) }
        }
    }
}

/** One open connection. [inView] is null when it is the only one; otherwise it says whether the pages show this one. */
@Composable private fun SessionCard(workspaceState: WorkspaceState, active: ConnectionProfile?, inView: Boolean?, onOpenWorkspace: () -> Unit, number: Int? = null, onDisconnect: () -> Unit) {
    TechPanel(Modifier.fillMaxWidth(), rail = Tech.Green, padding = 8.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The number is the one the sidebar shows for this session.
            Text(if (number == null) "SESSION" else "SESSION $number", fontFamily = Tech.Mono, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Tech.Muted)
            SessionDot()
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

@Composable private fun SessionDot() {
    val reduce = LocalReduceMotion.current
    val phase = LocalMotionPhase.current
    val pulse = !reduce && phase.active.value
    Canvas(Modifier.size(8.dp)) {
        val alpha = if (!pulse) 1f else 0.35f + 0.65f * ((phase.pulse.floatValue - 0.15f) / 0.45f).coerceIn(0f, 1f)
        drawCircle(Tech.Green.copy(alpha = alpha))
    }
}

@Composable private fun SavedConnections(modifier: Modifier, deskState: DeskState, desk: ProfileDesk, onScan: () -> Unit, onOpenWorkspace: () -> Unit, connected: Boolean = false, connectedProfiles: Set<String> = emptySet(), showQuickStart: Boolean = true, certificateRefused: String? = null) {
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
                Text("You can still connect manually.", color = Tech.Muted, fontSize = Tech.Label)
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
        else if (deskState.selectedId != null && pending == null) ProfileConnect(deskState, desk, certificateRefused, onOpenWorkspace)
        if (showQuickStart && deskState.profiles.isEmpty() && !deskState.editing && !connected) QuickStart(onScan)
    }
}

/** First-run guide shown while no profile is saved: token, access, certificate pin, discovery. */
@Composable private fun QuickStart(onScan: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    SectionLabel("Quick start")
    val steps = listOf(
        Triple("01", "SIGN IN", "Password · your Proxmox VE user, for example root@pam, with a two-factor code if the account uses one.\nAPI token · PVE: Datacenter → Permissions → API Tokens → Add. PDM: Configuration → Access Control → API Token.\nThe token ID looks like user@realm!name; copy the secret once, it is shown only at creation."),
        Triple("02", "GRANT ACCESS", "Read-only fleet view · PVEAuditor on /.\nPower and snapshots · add VM.PowerMgmt and VM.Snapshot where you want them.\nWith privilege separation on, the token needs its own permissions."),
        Triple("03", "PIN THE CERTIFICATE", "Self-signed servers · on the node run\nopenssl x509 -in /etc/pve/local/pve-ssl.pem -noout -fingerprint -sha256\nand paste the fingerprint; the whole output line is fine. Use pveproxy-ssl.pem if a custom certificate is installed."),
    )
    steps.forEach { (n, title, body) ->
        Row(Modifier.fillMaxWidth().border(1.dp, Tech.Edge, Tech.Control).background(Tech.Black.copy(alpha = .4f)).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(n, fontFamily = Tech.Mono, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Tech.Amber.dimmed(.8f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = Tech.Muted)
                Text(body, fontFamily = Tech.Mono, fontSize = 11.sp, lineHeight = 16.sp, color = Tech.Ink.dimmed(.85f))
            }
        }
    }
    Row(Modifier.fillMaxWidth().border(1.dp, Tech.Edge, Tech.Control).background(Tech.Black.copy(alpha = .4f)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("04", fontFamily = Tech.Mono, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Tech.Amber.dimmed(.8f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("NOT SURE OF THE ADDRESS?", fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = Tech.Muted)
            Text("Scan the local network for PVE (8006) and PDM (8443). No credentials are sent.", fontFamily = Tech.Mono, fontSize = 11.sp, lineHeight = 16.sp, color = Tech.Ink.dimmed(.85f))
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
        // The row grows with the text size: a fixed 44 units clipped the kind badge and hid the address line at 150 % text.
        Modifier.fillMaxWidth().heightIn(min = 44.dp).hoverable(interaction).clip(Tech.Control).background(bg).border(1.dp, if (selected) Tech.Amber else Tech.Edge, Tech.Control).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KindBadge(if (profile.kind == PDM_KIND) "PDM" else "PVE")
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(profile.displayName, Modifier.identity(), fontSize = 13.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            HelpTip("${profile.origin} · ${profile.tokenId} · ${if (profile.sha256Pin == null) "No pin" else "Pin saved"}") {
                Text(profile.origin, Modifier.identity(), fontFamily = Tech.Mono, fontSize = 11.sp, lineHeight = 15.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Modifier.heightIn(min = 18.dp).widthIn(min = 34.dp).background(Tech.Raised, Tech.Control).border(1.dp, if (pdm) Tech.Amber else Tech.Green, Tech.Control).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Tech.Mono, fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, color = if (pdm) Tech.Amber else Tech.Green)
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
    // Opening the editor brings it into view: below the saved list it started entirely under the fold in a short window.
    val reveal = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(draft.id) { reveal.bringIntoView() }
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().bringIntoViewRequester(reveal), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
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
        EditorCertificateCheck(draft, desk)
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

/**
 * The editor's certificate step (network audit W3): read the server's certificate, compare it with the draft's pin, and
 * "Use this certificate" fills the draft pin with it and its key. Nothing is trusted until Save profile, which stays the
 * approval. An out-of-date certificate cannot be used.
 */
@Composable private fun EditorCertificateCheck(draft: ProfileDraft, desk: ProfileDesk) {
    val scope = rememberCoroutineScope()
    val target = completeServerAddress(draft.origin, draft.kind)
    var read by remember(draft.id, target) { mutableStateOf<ProbedCertificate?>(null) }
    var checking by remember(draft.id) { mutableStateOf(false) }
    var problem by remember(draft.id, target) { mutableStateOf<String?>(null) }
    val shownServer = if (Appearance.showcase) "this server" else target
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MiniAction(if (read == null) "Check server certificate" else "Re-check server certificate", enabled = target.isNotBlank() && !checking) {
            scope.launch {
                checking = true; problem = null
                val certificate = try { certificateProbe(target) } finally { checking = false }
                if (certificate != null) read = certificate else problem = "No certificate could be read from $shownServer. Check the address and that this computer can reach it."
            }
        }
        if (checking) Text("Checking server certificate…", color = Tech.Muted, fontSize = 12.sp)
    }
    problem?.let { Text(it, color = Tech.Red, fontSize = 12.sp) }
    val certificate = read ?: return
    val known = runCatching { canonicalOptionalPin(draft.sha256Pin) }.getOrNull()
    val trust = ManualTrust(known = listOfNotNull(known), observed = certificate.fingerprint,
        knownKeys = listOfNotNull(draft.key?.let { it.pin to it.spki }).toMap(), certificate = certificate,
        observedOutOfDate = !certificate.validAt(java.time.Instant.now()))
    known?.let { Text("Known SHA-256: ${if (Appearance.showcase) "Hidden" else it}", Modifier.identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted) }
    Text("${if (trust.changed) "Certificate changed" else "Server certificate"} \u00b7 $shownServer", color = Tech.Amber, fontSize = 12.sp)
    Text("Observed SHA-256: ${if (Appearance.showcase) "Hidden" else certificate.fingerprint}", Modifier.identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Amber)
    CertificateDetails(trust)
    if (known == certificate.fingerprint) Text("The pin above is this server's certificate.", color = Tech.Muted, fontSize = 12.sp)
    else if (!trust.observedOutOfDate) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MiniAction("Use this certificate") { desk.useCertificateInDraft(certificate) }
        Text("Compare it with the server's fingerprint first. Nothing is trusted until you save the profile.", Modifier.weight(1f, fill = false), color = Tech.Muted, fontSize = 12.sp)
    }
}

@Composable private fun ProfileConnect(deskState: DeskState, desk: ProfileDesk, certificateRefused: String?, onOpenWorkspace: () -> Unit) {
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
        // A profile without its own certificate pin trusts what the Connect form trusts: the certificate approved for this
        // address and port. With none yet, the certificate is read first and trust stays an explicit click, as there.
        // A profile WITH its own pin gets the same step after a certificate refusal: its pin is the known certificate,
        // and an explicit approval here replaces it (same key: one click; otherwise with the replacement checkbox).
        val pinless = profile.sha256Pin == null
        var seenCertificate by remember(profile.id, profile.origin, profile.sha256Pin) { mutableStateOf<ProbedCertificate?>(null) }
        val seen = seenCertificate?.fingerprint
        var checking by remember(profile.id) { mutableStateOf(false) }
        var checkError by remember(profile.id, profile.origin) { mutableStateOf<String?>(null) }
        var trustRevision by remember(profile.id) { mutableStateOf(0) }
        var replacementChecked by remember(profile.id, seen) { mutableStateOf(false) }
        val trust = remember(profile, pinless, seen, deskState.profiles, deskState.saveBlocked, trustRevision, deskState.trustGeneration) {
            if (pinless) desk.manualTrust(profile.origin, seen, seenCertificate) else desk.profileTrust(profile, seenCertificate)
        }
        val canConnect = deskState.profileSecretPresent || (remembered && vaultReady && !deskState.vaultBusy)
        val shownServer = if (Appearance.showcase) "this server" else profile.origin
        val checkCertificate = {
            if (!checking) scope.launch {
                checking = true
                checkError = null
                val origin = profile.origin
                val certificate = try { certificateProbe(origin) } finally { checking = false }
                if (certificate != null) { seenCertificate = certificate; trustRevision++ }
                else checkError = "No certificate could be read from $shownServer. Check that this computer can reach it."
            }
        }
        val connect: (Boolean, Boolean) -> Unit = { approve, normalTls ->
            val observed = if (pinless && !normalTls) seen else null
            val approval = if (approve) trust else null
            when {
                deskState.profileSecretPresent -> { if (desk.connectSelected(otp = otp.ifBlank { null }, observed = observed, approval = approval)) onOpenWorkspace() }
                remembered && vaultReady && !deskState.vaultBusy -> { scope.launch { if (desk.connectRemembered(otp.ifBlank { null }, observed, approval)) onOpenWorkspace() } }
            }
            trustRevision++
        }
        // Enter connects as it always did, and never approves new or changed trust: once a certificate has been read and
        // needs approval, only the explicit button goes on. A server with a certificate this computer trusts needs no check.
        val submit = {
            when {
                !canConnect -> Unit
                trust.needsApproval || trust.conflict -> Unit
                else -> connect(false, false)
            }
        }
        // TLS refused this server's certificate on the last try: read it now, so the way forward is on screen.
        val refusedHere = certificateRefused != null && runCatching { certificateOrigin(certificateRefused) == certificateOrigin(profile.origin) }.getOrDefault(false)
        LaunchedEffect(profile.id, refusedHere) { if (refusedHere && seen == null) checkCertificate() }
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
        if (pinless || refusedHere || seen != null) {
            if (checking) Text("Checking server certificate…", color = Tech.Muted, fontSize = 12.sp)
            checkError?.let { Text(it, color = Tech.Red, fontSize = 12.sp) }
            trust.error?.let { Text(it, color = Tech.Red, fontSize = 12.sp) }
            trust.known.forEach { known ->
                Text("Known SHA-256: ${if (Appearance.showcase) "Hidden" else known}", Modifier.identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted)
            }
            if (trust.known.isNotEmpty()) Text(when {
                !pinless -> "This profile's pinned certificate."
                trust.savedPin -> "Trusted through another saved connection to this server."
                else -> "Approved certificate for this server address and port."
            }, color = Tech.Muted, fontSize = 11.sp)
            seen?.let { fingerprint ->
                Text("${if (trust.changed) "Certificate changed" else "Server certificate"} \u00b7 $shownServer", color = Tech.Amber, fontSize = 12.sp)
                Text("Observed SHA-256: ${if (Appearance.showcase) "Hidden" else fingerprint}", Modifier.identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Amber)
                SameCertificateAs(trust)
                CertificateDetails(trust)
                if (trust.needsApproval && trust.canApprove) Text(
                    if (pinless) "Compare with the server's fingerprint before trusting. Approval is remembered for this address and port, even if sign-in fails. No password is saved."
                    else "Compare with the server's fingerprint before trusting. Approval replaces this profile's pin, even if sign-in fails. No password is saved.",
                    color = Tech.Muted, fontSize = 12.sp)
            }
            if (trust.conflict) Text("Saved pins conflict. Review saved connections and remembered trust before connecting.", color = Tech.Red, fontSize = 12.sp)
            if (trust.changed && trust.canApprove && !trust.sameKey) TechCheck(replacementChecked, { replacementChecked = it }, "Replace the known certificate with this one")
            if (refusedHere && trust.known.isEmpty()) Text("The last connection was refused on the server's certificate. A self-signed server needs its certificate approved once.", color = Tech.Amber, fontSize = 12.sp)
            else if (pinless && seen == null && trust.known.isEmpty() && trust.error == null) Text("No certificate is approved for this server yet. A self-signed server, such as a new Proxmox VE install, needs its certificate checked and approved once.", color = Tech.Muted, fontSize = 12.sp)
            if (seen == null && !checking) MiniAction(if (trust.known.isEmpty() || !pinless) "Check server certificate" else "Re-check server certificate") { checkCertificate() }
        }
        // Normal TLS works only for a server whose certificate this computer already trusts, and a private one never is.
        // Once the certificate has been read the platform is asked; the button stays until it has said no.
        val normalTlsOffered = pinless && trust.needsApproval && trust.known.isEmpty() && trust.error == null
        val normalTls = rememberNormalTls(profile.origin, normalTlsOffered, profile.id, seen)
        val normalTlsRefused = normalTlsOffered && normalTls == NormalTls.Refused
        if (normalTlsRefused) NormalTlsRefusedNote()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tech.Space12), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
            if (trust.needsApproval) {
                TrustAction(trust, canConnect, replacementChecked) { connect(true, false) }
                if (pinless && trust.known.isEmpty() && trust.error == null && !normalTlsRefused) TechAction("Connect with normal TLS", enabled = canConnect) { connect(false, true) }
            } else {
                TechAction("Connect", active = true, enabled = canConnect && !trust.conflict, onClick = submit)
            }
            TechAction("Clear secret", onClick = desk::discardSecrets)
        }
    }
}

/**
 * The explicit approval button for a certificate that needs it: "Trust and connect" for a new one, "Replace trust and
 * connect" (with the replacement checkbox) for a changed one, and "Accept renewed certificate and connect" in one click
 * when the server kept its key. Disabled for an out-of-date certificate. Enter never reaches it.
 */
@Composable private fun TrustAction(trust: ManualTrust, canConnect: Boolean, replacementChecked: Boolean, onApprove: () -> Unit) {
    when {
        trust.sameKey -> TechAction("Accept renewed certificate and connect", active = true, enabled = canConnect && trust.canApprove, onClick = onApprove)
        trust.changed -> TechAction("Replace trust and connect", active = true, enabled = canConnect && trust.canApprove && replacementChecked, onClick = onApprove)
        else -> TechAction("Trust and connect", active = true, enabled = canConnect && trust.canApprove, onClick = onApprove)
    }
}

/**
 * What the certificate check read, to compare before trusting: subject, issuer, validity, and for a changed certificate
 * whether the server kept its key. An out-of-date certificate says its date and this computer's clock and is not offered.
 * Showcase hides the subject and issuer.
 */
@Composable internal fun CertificateDetails(trust: ManualTrust) {
    val certificate = trust.certificate ?: return
    val hide = Appearance.showcase
    Text("Subject: ${if (hide) "Hidden" else certificate.subject}", Modifier.identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted)
    Text("Issuer: ${if (hide) "Hidden" else certificate.issuer}", Modifier.identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted)
    Text("Valid ${certificateDay(certificate.notBefore)} to ${certificateDay(certificate.notAfter)}", fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted)
    if (trust.changed && !trust.conflict) Text("Same key as before: " + when {
        trust.sameKey -> "yes"
        trust.keyChanged -> "no"
        else -> "not known (no key was recorded with the approved certificate)"
    }, fontFamily = Tech.Mono, fontSize = 11.sp, color = if (trust.keyChanged) Tech.Red else Tech.Muted)
    if (trust.sameKey && trust.canApprove) Text("The server renewed its certificate with the same key. Proxmox does this when it renews its own certificate. " +
        "Compare the new fingerprint with the server's, then accept it with one click.", color = Tech.Amber, fontSize = 12.sp)
    if (trust.observedOutOfDate) {
        val now = java.time.Instant.now()
        val dates = NetworkFailure.CertificateDates(now.isAfter(certificate.notAfter), now, certificate.notBefore, certificate.notAfter)
        Text("${dates.sentence} It cannot be accepted: renew it on the server (pvecm updatecerts --force) or correct this computer's clock.", color = Tech.Red, fontSize = 12.sp)
    }
}

/**
 * The observed certificate is the one already approved or pinned for another address (network audit M3). It still needs
 * the explicit Trust and connect here: two names with one certificate may be two machines that share a cloned key.
 */
@Composable private fun SameCertificateAs(trust: ManualTrust) {
    if (trust.sameAs.isEmpty() || !trust.needsApproval) return
    val names = if (Appearance.showcase) "another server you approved" else trust.sameAs.take(3).joinToString(", ") + if (trust.sameAs.size > 3) " and ${trust.sameAs.size - 3} more" else ""
    Text("Same certificate as $names. Trusting it here is still your decision for this address.", if (Appearance.showcase) Modifier else Modifier.identity(), color = Tech.Muted, fontSize = 12.sp, lineHeight = 16.sp)
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

@Composable private fun ManualConnection(modifier: Modifier, anyOpen: Boolean, deskState: DeskState, desk: ProfileDesk, scanned: ScanHit?, scannedAt: Long?, motion: MotionPreferences, onScannedEnded: () -> Unit, onOpenWorkspace: () -> Unit) {
    // The form opens with what it held last (never a secret), so that a refused sign-in or a look at another page does not empty the server
    // address. A picked scan result was put into it when it was picked, so what is typed after that is the person's own and stays;
    // the pick fills the form here only where it holds nothing.
    val start = remember { scanFormStart(desk.manualForm, scanned) }
    var address by remember { mutableStateOf(start.address) }
    var tokenId by remember { mutableStateOf(desk.manualForm.tokenId) }
    // The first start keeps API token. Password mode fills the usual user; the choice itself is the only thing remembered.
    var passwordChoice by remember { mutableStateOf(motion.option(DESK_PASSWORD_MODE, false)) }
    var user by remember { mutableStateOf(desk.manualForm.user.ifBlank { if (passwordChoice) DEFAULT_PASSWORD_USER else "" }) }
    var otp by remember(deskState.secretGeneration) { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(start.kind) }
    val passwordMode = passwordChoice && kind == DIRECT_PVE_KIND
    androidx.compose.runtime.SideEffect { desk.manualForm = ManualForm(address, tokenId, user, kind) }
    // An edit permanently invalidates this scan offer, even if the address is later restored, and the window forgets the pick (so
    // leaving the Desk and coming back cannot bring the offer back). Timed from the pick, not from this composition.
    val scanOffer = remember(scanned, scannedAt) { ScannedPinOffer(scanned?.origin.orEmpty(), kind, scanned?.fingerprint, created = scannedAt) }
    var manualOffer by remember { mutableStateOf<ScannedPinOffer?>(null) }
    var probed by remember { mutableStateOf<ProbedCertificate?>(null) }
    var scanExpired by remember { mutableStateOf(false) }
    var scanValid by remember { mutableStateOf(true) }
    var probingCert by remember { mutableStateOf(false) }
    var probeError by remember { mutableStateOf<String?>(null) }
    var replacementChecked by remember(scanned, address, kind) { mutableStateOf(false) }
    var trustRevision by remember { mutableStateOf(0) }
    var forgetAsked by remember(address) { mutableStateOf(false) }
    val currentOffer = manualOffer ?: scanOffer
    // The offer is looked at again every second while it is on screen, and at the moment it lapses: its age and what is left are shown,
    // and when it lapses the approval goes away then, not on the next unrelated change.
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(currentOffer) {
        while (currentOffer.live()) {
            val untilLapse = currentOffer.remainingNanos() / 1_000_000L + 2
            kotlinx.coroutines.delay(minOf(ScanOfferClock.tickMillis, untilLapse).coerceAtLeast(1))
            tick++
        }
    }
    @Suppress("UNUSED_VARIABLE") val ticked = tick
    val observed = currentOffer.current(address, kind).takeIf { scanValid }
    val trust = remember(address, observed, probed, deskState.profiles, deskState.saveBlocked, trustRevision, deskState.trustGeneration) {
        desk.manualTrust(address, observed, probed?.takeIf { observed != null && it.fingerprint == runCatching { canonicalOptionalPin(observed) }.getOrNull() })
    }
    // A host typed without https://. The certificate check completes it; until then it is not yet a server address.
    val bare = address.isNotBlank() && "://" !in address
    val scope = rememberCoroutineScope()
    val probeAction = {
        // A bare host becomes https://host:8006 (8443 for PDM); an address with a scheme is checked as typed.
        val typed = address
        val probeKind = kind
        val target = completeServerAddress(typed, probeKind)
        if (target.isNotEmpty() && !probingCert) {
            scope.launch {
                probingCert = true
                probeError = null
                val read = try { certificateProbe(target) } finally { probingCert = false }
                // The answer belongs to the address and kind it was asked for; an edit made meanwhile wins.
                if (address != typed || kind != probeKind) return@launch
                address = target
                if (read != null) {
                    probed = read
                    manualOffer = ScannedPinOffer(target, probeKind, read.fingerprint.chunked(2).joinToString(":") { it.uppercase() })
                    scanValid = true
                    scanExpired = false
                    trustRevision++
                } else {
                    probeError = "No certificate could be read from ${if (Appearance.showcase) "this server" else target}. Check the address and port (Proxmox VE uses 8006), and that this computer can reach it."
                }
            }
        }
    }
    TechPanel(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
            SectionLabel("Connect")
            HelpTip("A new connection opens beside the ones already open; nothing is replaced. Verify private certificate fingerprints with your administrator.") {
                Text(if (anyOpen) "Opens beside the open connection · secrets stay in memory" else "Connect without saving a profile · secrets stay in memory", fontSize = 11.sp, lineHeight = 15.sp, color = Tech.Muted, fontFamily = Tech.Mono)
            }
            KindChoice(kind) { if (kind != it) { scanValid = false; scanOffer.invalidate(); manualOffer = null; fingerprint = ""; onScannedEnded() }; kind = it }
            if (kind == DIRECT_PVE_KIND) AuthChoice(passwordMode) { chosen ->
                passwordChoice = chosen
                motion.put(DESK_PASSWORD_MODE, chosen)
                if (chosen) user = passwordUserAfterSwitch(tokenId.ifBlank { user })
            }
            val login = passwordMode && kind == DIRECT_PVE_KIND
            val canConnect = address.isNotBlank() && (if (passwordMode) user else tokenId).isNotBlank() && deskState.manualSecretPresent
            val connect: (Boolean, Boolean) -> Unit = { approve, normalTls ->
                if (observed != null && currentOffer.current(address, kind) == null) {
                    scanExpired = true; scanValid = false
                } else if (canConnect) {
                    val completed = if (login) completeLoginUser(user) else tokenId
                    if (login && completed != user) user = completed
                    val opened = desk.connectManual(address, if (login) completed else tokenId, fingerprint, kind,
                            otp = if (passwordMode) otp.ifBlank { null } else null, passwordLogin = login,
                            observed = if (normalTls) null else observed, approval = if (approve) trust else null)
                    trustRevision++
                    if (opened) { onScannedEnded(); onOpenWorkspace() }
                }
            }
            // Enter in a secret field never approves new or changed trust; probes unknown cert first.
            // After a failed check the same button connects anyway, so the connection's own error is shown.
            val submit = {
                val unchecked = observed == null && trust.known.isEmpty() && fingerprint.isBlank() && address.isNotBlank() && probeError == null
                if (bare || unchecked) {
                    probeAction()
                } else if (!trust.needsApproval && !trust.conflict && trust.error == null) {
                    connect(false, false)
                }
            }
            CompactDeskField(address, {
                if (address != it) { scanValid = false; scanOffer.invalidate(); manualOffer = null; probeError = null; fingerprint = ""; onScannedEnded() }
                address = it
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
            if (probingCert) Text("Checking server certificate…", color = Tech.Muted, fontSize = 12.sp)
            probeError?.let { Text(it, color = Tech.Red, fontSize = 12.sp) }
            if (address.isNotBlank() && observed == null && !probingCert) {
                MiniAction(if (trust.known.isEmpty()) "Check server certificate" else "Re-check server certificate", onClick = probeAction)
            }
            if (scanExpired) Text("Scan offer expired. Scan again before trusting a certificate.", color = Tech.Red, fontSize = 12.sp)
            else if (manualOffer == null && scanValid && scanOffer.expired()) Text("The scanned certificate is more than five minutes old. Check the server certificate again before trusting it.", color = Tech.Amber, fontSize = 12.sp)
            if (address.isNotBlank()) {
                if (bare) Text("Will be checked as ${if (Appearance.showcase) "an HTTPS address" else completeServerAddress(address, kind)}", color = Tech.Muted, fontSize = 11.sp, fontFamily = Tech.Mono)
                else trust.error?.let { Text(it, color = Tech.Red, fontSize = 12.sp) }
                trust.known.forEach { known ->
                    Text("Known SHA-256: ${if (Appearance.showcase) "Hidden" else known}", fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted)
                }
                if (trust.known.isNotEmpty()) {
                    Text(if (trust.savedPin) "Includes saved-profile trust. Edit that profile to change its pin." else "Approved certificate for this server address and port.", color = Tech.Muted, fontSize = 11.sp)
                    // One question first (network audit F4); nothing is forgotten by the first click.
                    if (!forgetAsked) MiniAction("Forget remembered certificate") { forgetAsked = true }
                    else TechPanel(Modifier.fillMaxWidth(), rail = Tech.Amber, padding = 8.dp) {
                        Text(forgetCertificateQuestion(if (Appearance.showcase) "this server" else runCatching { certificateOrigin(address) }.getOrDefault(address)),
                            Modifier.identity(), color = Tech.Ink, fontSize = 12.sp, lineHeight = 16.sp)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tech.Space12), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
                            TechAction("Forget certificate", active = true) { desk.forgetManualTrust(address); forgetAsked = false; trustRevision++ }
                            TechAction("Keep") { forgetAsked = false }
                        }
                    }
                }
                observed?.let { seen ->
                    Text("${if (trust.changed) "Certificate changed" else if (manualOffer != null) "Server certificate" else "Scanned certificate"} \u00b7 ${if (Appearance.showcase) "Hidden server" else address}", color = Tech.Amber, fontSize = 12.sp)
                    Text("${if (manualOffer != null) "Checked" else "Scanned"} ${offerAgeText(currentOffer.ageNanos())}. This offer lapses in ${offerRemainingText(currentOffer.remainingNanos())}.", color = Tech.Muted, fontSize = 11.sp)
                    Text("Observed SHA-256: ${if (Appearance.showcase) "Hidden" else seen}", Modifier.identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Amber)
                    SameCertificateAs(trust)
                    CertificateDetails(trust)
                    if (trust.needsApproval) Text("Compare with the server's fingerprint before trusting. Approval is remembered for this address and port, even if sign-in fails. No password is saved.", color = Tech.Muted, fontSize = 12.sp)
                }
                if (trust.conflict) Text("Saved pins conflict. Review saved connections and remembered trust before connecting.", color = Tech.Red, fontSize = 12.sp)
                if (trust.changed && trust.canApprove && !trust.sameKey) TechCheck(replacementChecked, { replacementChecked = it }, "Replace the known certificate with this scanned certificate")
                if (trust.needsApproval) MiniAction("Cancel scanned certificate") { scanValid = false; scanOffer.invalidate(); manualOffer = null; replacementChecked = false; onScannedEnded() }
            }
            // As for a saved profile: normal TLS is offered until the platform has said it cannot verify this certificate.
            val normalTlsOffered = trust.needsApproval && trust.known.isEmpty() && trust.error == null
            val normalTls = rememberNormalTls(address, normalTlsOffered, observed, kind)
            val normalTlsRefused = normalTlsOffered && normalTls == NormalTls.Refused
            if (normalTlsRefused) NormalTlsRefusedNote()
            if (trust.needsApproval) Column(verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
                TrustAction(trust, canConnect, replacementChecked) { connect(true, false) }
                if (trust.known.isEmpty() && trust.error == null && !normalTlsRefused) TechAction("Connect with normal TLS", enabled = canConnect && fingerprint.isBlank()) { connect(false, true) }
            } else FlowRow(horizontalArrangement = Arrangement.spacedBy(Tech.Space12), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
                TechAction(if (anyOpen) "Add connection" else "Connect", active = true,
                    enabled = canConnect && !trust.conflict && (trust.error == null || bare), onClick = submit)
            }
        }
    }
}

/** Direct PVE only: sign in with an API token or with a user name and password. */
@Composable private fun AuthChoice(password: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).height(IntrinsicSize.Min).clip(Tech.Control).border(1.dp, Tech.Edge, Tech.Control)) {
        KindSegment("API token", !password, Modifier.weight(1f)) { onChange(false) }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Tech.Edge))
        KindSegment("Password", password, Modifier.weight(1f)) { onChange(true) }
    }
}

@Composable private fun KindChoice(kind: String, onKind: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).height(IntrinsicSize.Min).clip(Tech.Control).border(1.dp, Tech.Edge, Tech.Control)) {
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
        modifier.fillMaxHeight().heightIn(min = 28.dp).background(when {
            selected -> Tech.AmberWash
            hovered -> Tech.Raised
            else -> Color.Transparent
        }).clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick).border(1.dp, if (focused) tint else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), Modifier.padding(vertical = 3.dp), fontFamily = Tech.Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp, color = if (selected) Tech.Amber else Tech.Ink)
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
        Modifier.heightIn(min = 26.dp).border(1.dp, Tech.Edge, Tech.Control)
            .focusProperties { canFocus = false }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Tech.Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.5.sp, color = Tech.Ink)
    }
}

private fun sessionText(state: WorkspaceState, active: ConnectionProfile?): String {
    val base = when {
        active != null && state.connected -> "Connected with ${active.displayName} · ${connectionKindLabel(active.kind)} · ${active.origin}"
        else -> "Current connection: ${connectionKindLabel(state.kind)} · ${state.address}"
    }
    // How the server is trusted: a pin, or normal TLS and which store accepted it (PlatformTrust).
    return connectionTrustLabel(state)?.let { "$base · $it" } ?: base
}

/**
 * A one-line field. [identity] fields (addresses, user and token IDs, profile names) show a bullet mask under the
 * showcase blur until they are focused for editing, so the real value is never on screen while presenting.
 */
@Composable private fun CompactDeskField(value: String, onValue: (String) -> Unit, label: String, placeholder: String? = null, identity: Boolean = false, onSubmit: (() -> Unit)? = null, passwordUser: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val concealed = identity && identityFieldConcealed(Appearance.showcase, focused, value)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.fillMaxWidth().height(30.dp).border(1.dp, Tech.Edge, Tech.Control).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty() && placeholder != null) {
                Text(placeholder, fontFamily = Tech.Mono, fontSize = 12.sp, color = Tech.Muted.dimmed(.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
