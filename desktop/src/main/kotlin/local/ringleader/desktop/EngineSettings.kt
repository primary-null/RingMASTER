@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.ringleader.core.Guest
import local.ringleader.core.GuestAgentAvailability
import local.ringleader.core.HardwareSummary
import local.ringleader.core.PDM_KIND
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState
import local.ringleader.core.guestRadioRefusal
import local.ringleader.core.guesthelper.GUEST_HELPER_ABOUT
import local.ringleader.core.guesthelper.GUEST_HELPER_UNINSTALL
import local.ringleader.core.guesthelper.GuestHelperLink
import local.ringleader.core.guesthelper.GuestHelperPlace
import local.ringleader.core.guesthelper.guestHelperAgeOf
import local.ringleader.core.guesthelper.guestHelperAgeSentence
import local.ringleader.core.guesthelper.guestHelperPlace

/*
 * Settings › RingMASTER Engine: what it is, where it is paired, and the way to unpair or remove it. Removing runs the
 * uninstaller through the guest agent where that can be used (one confirmation), and otherwise shows the command.
 */

/** The app's Engine where Settings opens; null where there is none (scenes and tests). */
internal val LocalEngineHub = staticCompositionLocalOf<EngineHub?> { null }

internal const val ENGINE_SETTINGS_EMPTY =
    "No guest is paired yet. Open a guest's Radios tab and choose \"Set up RingMASTER Engine\" where it cannot look inside."
internal const val ENGINE_SETTINGS_USES =
    "Used by a guest's Radios tab: Look inside, the Bluetooth scan and the Wi-Fi scan. Without a pairing they use the guest agent."

/** Said when the token is kept in memory only: there is no password safe, or it did not take the token (its prompt was declined). */
internal const val ENGINE_TOKEN_THIS_RUN =
    "The password safe did not keep the token, so the pairing lasts only until RingMASTER closes."

/** How a pairing's place reads: "VM 112 on 192.0.2.1" or "Node pve-a". */
internal fun enginePlaceLabel(place: GuestHelperPlace): String = when (place) {
    is GuestHelperPlace.Guest -> {
        val kind = if (place.guest.kind == "VM") "VM" else "Container"
        val server = place.remote.substringAfter("://").substringBefore('/').ifBlank { null }
        listOfNotNull("$kind ${place.guest.vmid}", server?.let { "on $it" }).joinToString(" ")
    }
    is GuestHelperPlace.Node -> "Node ${place.node}"
}

/** The guest of [place] in this session's inventory, when it is there. */
internal fun inventoryGuest(state: WorkspaceState, place: GuestHelperPlace): Guest? =
    state.overview?.guests?.firstOrNull { guestHelperPlace(it) == place }

private val seenFormat = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm")
private fun seen(at: Instant?) = at?.let { "last seen " + seenFormat.format(it.atZone(ZoneId.systemDefault())) } ?: "not seen yet"

@Composable internal fun EngineSettingsSection(state: WorkspaceState, workspace: Workspace) {
    val hub = LocalEngineHub.current
    GUEST_HELPER_ABOUT.forEach { Text(it, fontSize = 12.sp, lineHeight = 17.sp, color = Tech.Ink) }
    Text(ENGINE_SETTINGS_USES, fontSize = 12.sp, lineHeight = 17.sp, color = Tech.Muted)
    SectionLabel("Paired")
    val version = hub?.version?.collectAsState()?.value
    val links = remember(hub, version) { hub?.pairings?.list().orEmpty() }
    hub?.pairings?.blockedReason?.let { Text(it, fontSize = 12.sp, color = Tech.Amber) }
    if (links.isEmpty()) Text(ENGINE_SETTINGS_EMPTY, fontSize = 12.sp, lineHeight = 17.sp, color = Tech.Muted)
    if (hub != null) links.forEach { link -> PairingRow(hub, workspace, state, link) }
}

@Composable internal fun PairingRow(
    hub: EngineHub, workspace: Workspace, state: WorkspaceState, link: GuestHelperLink,
    agentAccess: GuestAgentAvailability = workspace.guestAgentAvailability,
    loadHardware: suspend (Workspace, Guest) -> local.ringleader.core.HardwareSummary? = { w, g ->
        agentAccess.hardware(w, g)
    },
) {
    val scope = rememberCoroutineScope()
    var said by remember(link.id) { mutableStateOf<String?>(null) }
    var busy by remember(link.id) { mutableStateOf(false) }
    var consent by remember(link.id) { mutableStateOf(agentAccess.revision()) }
    var removalHardware by remember(link.id) { mutableStateOf<HardwareSummary?>(null) }
    var removalGuest by remember(link.id) { mutableStateOf<Guest?>(null) }
    var confirmRemove by remember(link.id) { mutableStateOf(false) }
    var showCommand by remember(link.id) { mutableStateOf(false) }
    val guest = inventoryGuest(state, link.place)
    val availability by agentAccess.version.collectAsState()
    val revision = agentAccess.revision()
    var hardware by remember(agentAccess, workspace, revision, guest?.resource, guest?.node, guest?.name, guest?.status, guest?.template) {
        mutableStateOf<local.ringleader.core.HardwareSummary?>(null)
    }
    LaunchedEffect(agentAccess, workspace, revision, guest?.resource, guest?.node, guest?.name, guest?.status, guest?.template, state.inspectionLoading) {
        hardware = null
        if (guest != null && guest.kind == "VM" && guest.status == "running" && state.kind != PDM_KIND && guest.scope.isEmpty()) {
            try { hardware = loadHardware(workspace, guest) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { said = setupFailure(e); showCommand = true }
        }
    }
    val agentRefusal = remember(agentAccess, workspace, availability, guest, hardware, state) {
        guest?.let { agentAccess.refusal(it, hardware) }
    }
    val agentFree = guest != null && agentRefusal == null && hardware?.identity?.best(false) != null
    fun act(block: suspend () -> String?) {
        busy = true
        said = null
        scope.launch {
            try { said = block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { said = setupFailure(e); showCommand = true }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxWidth().border(1.dp, Tech.Edge, Tech.Control).background(Tech.Black.copy(alpha = .3f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        val title = listOf(enginePlaceLabel(link.place), link.hostName).filter { it.isNotBlank() }.joinToString(" · ")
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Tech.Ink)
        Text("${link.host}:${link.port} · Engine ${link.version} · ${seen(link.lastSeen)}", fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted)
        guestHelperAgeSentence(guestHelperAgeOf(link.version), link.version)?.let { Text(it, fontSize = 12.sp, color = Tech.Amber) }
        if (!link.tokenInSafe) {
            Text(ENGINE_TOKEN_THIS_RUN, fontSize = 12.sp, color = Tech.Amber)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniAction("Check now", enabled = !busy) { act { checkNow(hub, link) } }
            MiniAction("Unpair", enabled = !busy) { act { unpair(hub, link) } }
            if (agentFree) MiniAction("Remove…", enabled = !busy) {
                removalGuest = guest
                removalHardware = hardware
                consent = agentAccess.revision()
                confirmRemove = true
            }
            else MiniAction("How to remove it") { showCommand = !showCommand }
        }
        if (showCommand) RemoveCommand()
        said?.let { Text(it, fontSize = 12.sp, lineHeight = 17.sp, color = Tech.Muted) }
    }
    val confirmedHardware = removalHardware
    val confirmed = removalGuest
    if (confirmRemove && confirmed != null && confirmedHardware != null) {
        TechDialog("Remove the RingMASTER Engine?", { confirmRemove = false }, subtitle = enginePlaceLabel(link.place), actions = {
            TechAction("Remove", active = true) {
                confirmRemove = false
                act { removeThroughAgent(hub, workspace, confirmed, link, consent, confirmedHardware, agentAccess) }
            }
            TechAction("Cancel") { confirmRemove = false }
        }) {
            Text("RingMASTER will run the Engine's uninstaller in ${confirmed.kind} ${confirmed.id} through the QEMU guest agent. " +
                "It stops the service and removes the program, its folder and its account, then checks that nothing is left.",
                fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

@Composable private fun RemoveCommand() {
    val clipboard = LocalClipboardManager.current
    Text("Run this in the guest, as root, then unpair here:", fontSize = 12.sp, color = Tech.Ink)
    Text(GUEST_HELPER_UNINSTALL, Modifier.identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Amber)
    Row { MiniAction("Copy the command") { clipboard.setText(AnnotatedString(GUEST_HELPER_UNINSTALL)) } }
}

private suspend fun checkNow(hub: EngineHub, link: GuestHelperLink): String = withContext(Dispatchers.IO) {
    val identity = hub.pairings.client(link).whoami()
    hub.pairings.seen(link.id, identity).getOrThrow()
    hub.changed()
    "It answered: Engine ${identity.version} on ${identity.osName.ifBlank { identity.host }}."
}

private suspend fun unpair(hub: EngineHub, link: GuestHelperLink): String = withContext(Dispatchers.IO) {
    hub.pairings.unpair(link.id).getOrThrow()
    hub.changed()
    "Unpaired. The Engine is still installed in the guest until you remove it there."
}

private suspend fun removeThroughAgent(
    hub: EngineHub, workspace: Workspace, guest: Guest, link: GuestHelperLink, consent: Pair<Long, Long>,
    confirmedHardware: HardwareSummary, agentAccess: GuestAgentAvailability = workspace.guestAgentAvailability,
): String {
    withContext(Dispatchers.IO) {
        agentAccess.mutate(workspace, guest, consent, confirmedHardware) { connection, live -> hub.installer.remove(connection, live) }
    }
    unpair(hub, link)
    return "Removed from ${guest.kind} ${guest.id}, and unpaired. Nothing of the Engine is left there."
}
