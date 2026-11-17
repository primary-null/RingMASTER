@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ringleader.core.Guest
import local.ringleader.core.HardwareSummary
import local.ringleader.core.PDM_KIND
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState
import local.ringleader.core.guestRadioRefusal
import local.ringleader.core.guesthelper.GUEST_HELPER_ABOUT
import local.ringleader.core.guesthelper.GUEST_HELPER_NO_BUNDLE
import local.ringleader.core.guesthelper.GUEST_HELPER_PLAN
import local.ringleader.core.guesthelper.GuestHelperBundle
import local.ringleader.core.guesthelper.GuestHelperWay
import local.ringleader.core.guesthelper.OneTimeLinkState
import local.ringleader.core.guesthelper.chosenGuestHelperBundle
import local.ringleader.core.guesthelper.guestHelperWays
import local.ringleader.core.guesthelper.releaseCommand

/*
 * "Set up RingMASTER Engine" for one guest: what it is, what will be installed where, and the ways in, best first. The
 * guest agent way is one confirmed click and pairs by itself; the others end in pairing by hand: the address, the
 * fingerprint compared with the installer's, the token.
 */

internal const val ENGINE_FIREWALL_NOTE =
    "This computer's firewall may ask once whether RingMASTER may accept a connection. The link closes after one download or ten minutes."
internal const val ENGINE_FINGERPRINT_QUESTION = "Is this the fingerprint the installer showed?"

@Composable internal fun EngineSetupDialog(hub: EngineHub, workspace: Workspace, state: WorkspaceState, guest: Guest, hardware: HardwareSummary?) {
    val scope = rememberCoroutineScope()
    val setup = remember(guest.resource) { EngineSetup(hub, workspace, scope, guest, hardware, guestAddress(state, guest)) }
    DisposableEffect(setup) { onDispose { setup.close() } }
    val chosen by hub.chosenBundle.collectAsState()
    val bundle = chosen ?: hub.bundleFor(setup.machine)
    TechDialog("Set up RingMASTER Engine", { setup.close() }, width = 680.dp, subtitle = "${guest.kind} ${guest.id} · ${guest.name}",
        dismiss = Dismiss.Confirm, actions = { SetupActions(setup) }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (val step = setup.step) {
                EngineStep.Choose -> ChooseWay(setup, state, guest, hardware, bundle, hub)
                is EngineStep.Installing -> Installing(step)
                is EngineStep.Link -> LinkStep(setup, step)
                is EngineStep.Command -> CommandStep(setup, step.command)
                EngineStep.Address -> AddressStep(setup)
                is EngineStep.Fingerprint -> FingerprintStep(setup, step)
                is EngineStep.Token -> TokenStep(setup, step)
                is EngineStep.Done -> DoneStep(step)
            }
            setup.error?.let { Text(it, fontSize = 12.sp, lineHeight = 17.sp, color = Tech.Amber) }
        }
    }
}

@Composable private fun RowScope.SetupActions(setup: EngineSetup) {
    val step = setup.step
    if (step !is EngineStep.Choose && step !is EngineStep.Done && step !is EngineStep.Installing) {
        TechAction("Back", enabled = !setup.busy) { setup.back() }
    }
    TechAction(if (step is EngineStep.Done) "Done" else "Close", active = step is EngineStep.Done) { setup.close() }
}

@Composable private fun Sentences(lines: List<String>, bullet: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { line -> Text(if (bullet) "·  $line" else line, fontSize = 12.sp, lineHeight = 17.sp, color = Tech.Ink) }
    }
}

@Composable private fun ChooseWay(setup: EngineSetup, state: WorkspaceState, guest: Guest, hardware: HardwareSummary?, bundle: GuestHelperBundle?,
    hub: EngineHub) {
    Sentences(GUEST_HELPER_ABOUT)
    SectionLabel("What is installed")
    Sentences(GUEST_HELPER_PLAN, bullet = true)
    BundleLine(bundle, hub)
    val agentRefusal = guestRadioRefusal(guest, state.kind != PDM_KIND && guest.scope.isEmpty(), hardware)
    val ways = guestHelperWays(guest, agentRefusal, bundle)
    if (ways.isNotEmpty()) SectionLabel("Ways in, best first")
    ways.forEachIndexed { index, way -> WayRow(setup, way, bundle!!, best = index == 0) }
    if (GuestHelperWay.GuestAgent !in ways && agentRefusal != null && bundle != null) {
        Hint("Not through the guest agent: ${agentRefusal.sentence}")
    }
    SectionLabel("Already installed?")
    Row { MiniAction("Pair by hand") { setup.toPairing() } }
}

@Composable private fun BundleLine(bundle: GuestHelperBundle?, hub: EngineHub) {
    when {
        bundle == null -> Text(GUEST_HELPER_NO_BUNDLE, fontSize = 12.sp, lineHeight = 17.sp, color = Tech.Amber)
        bundle.packaged -> Hint("Engine ${bundle.version} for ${bundle.arch}, packed with this app. SHA-256 ${bundle.sha256.take(16)}…")
        else -> Text("Engine ${bundle.version} for ${bundle.arch} from a file you chose: not a release file. SHA-256 ${bundle.sha256}",
            fontSize = 11.sp, lineHeight = 16.sp, color = Tech.Amber)
    }
    GuestEngineProblems.last?.let { Text(it, fontSize = 11.sp, color = Tech.Amber) }
    if (bundle == null || !bundle.packaged) Row { MiniAction("Choose a bundle file…") { chooseBundle(hub) } }
}

/** Opens the system's file chooser for a bundle; a file that is not one is said in a sentence, never taken. */
private fun chooseBundle(hub: EngineHub) {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Choose a RingMASTER Engine bundle", java.awt.FileDialog.LOAD)
    dialog.isVisible = true
    val name = dialog.file ?: return
    chosenGuestHelperBundle(java.nio.file.Path.of(dialog.directory, name))
        .onSuccess { GuestEngineProblems.clear(); hub.choose(it) }
        .onFailure { GuestEngineProblems.report(it.message ?: "That file is not an Engine bundle.") }
}

@Composable private fun Hint(text: String) {
    Text(text, fontSize = 11.sp, lineHeight = 16.sp, fontFamily = Tech.Mono, color = Tech.Muted)
}

@Composable private fun WayRow(setup: EngineSetup, way: GuestHelperWay, bundle: GuestHelperBundle, best: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (way) {
            GuestHelperWay.GuestAgent -> {
                MiniAction("Install through the guest agent", primary = best, enabled = !setup.busy) { setup.installThroughAgent(bundle) }
                Hint("One click sends the Engine, installs it and pairs it. The token is read back; you type nothing.")
            }
            GuestHelperWay.OneTimeLink -> {
                TechField(setup.host, { setup.host = it }, "Guest address", Modifier.width(220.dp))
                MiniAction("Start a one-time link", primary = best, enabled = !setup.busy) { setup.openLink(bundle) }
                Hint("Then paste one command in the guest, as root. Its installer asks before it changes anything.")
            }
            GuestHelperWay.ReleaseCommand -> {
                MiniAction("Show the command") { setup.showCommand(releaseCommand(AppVersion.value, bundle)) }
                Hint("For a guest with internet: it downloads this version's Engine from the release page and checks its hash.")
            }
        }
    }
}

@Composable private fun Installing(step: EngineStep.Installing) {
    val p = step.progress
    val text = if (p.installing) "Installing and pairing…" else "Sending the Engine: part ${p.partsSent} of ${p.parts}…"
    Text(text, fontSize = 13.sp, color = Tech.Ink)
}

@Composable private fun CommandBox(command: String) {
    val clipboard = LocalClipboardManager.current
    SelectionContainer {
        Text(command, Modifier.fillMaxWidth().identity(), fontFamily = Tech.Mono, fontSize = 11.sp, lineHeight = 15.sp, color = Tech.Amber)
    }
    Row { MiniAction("Copy the command") { clipboard.setText(AnnotatedString(command)) } }
}

@Composable private fun LinkStep(setup: EngineSetup, step: EngineStep.Link) {
    val status by step.link.status.collectAsState()
    Text("Paste this in the guest's console, as root:", fontSize = 12.sp, color = Tech.Ink)
    CommandBox(step.link.command)
    Hint(ENGINE_FIREWALL_NOTE)
    val said = when (status) {
        OneTimeLinkState.Waiting -> "Waiting for the guest to download it…"
        OneTimeLinkState.Served -> "The guest downloaded the Engine. When its installer has finished, pair it."
        OneTimeLinkState.Expired -> "The link has closed without a download. Go back to start a new one."
        OneTimeLinkState.Failed -> "The link stopped working. Go back to start a new one."
    }
    Text(said, fontSize = 12.sp, color = if (status == OneTimeLinkState.Served) Tech.Green else Tech.Muted)
    Row { MiniAction("Pair it", primary = status == OneTimeLinkState.Served) { setup.toPairing() } }
}

@Composable private fun CommandStep(setup: EngineSetup, command: String) {
    Text("Paste this in the guest's console, as root:", fontSize = 12.sp, color = Tech.Ink)
    CommandBox(command)
    Row { MiniAction("Pair it", primary = true) { setup.toPairing() } }
}

@Composable private fun AddressStep(setup: EngineSetup) {
    Text("Where does the Engine answer? The installer printed its port; the address is the guest's.", fontSize = 12.sp, color = Tech.Ink)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TechField(setup.host, { setup.host = it }, "Address", Modifier.width(260.dp))
        TechField(setup.port, { setup.port = it }, "Port", Modifier.width(110.dp))
    }
    Row { MiniAction(if (setup.busy) "Reading…" else "Read its certificate", primary = true, enabled = !setup.busy) { setup.readCertificate() } }
    Hint("Reading the certificate trusts nothing and sends no token.")
}

@Composable private fun FingerprintStep(setup: EngineSetup, step: EngineStep.Fingerprint) {
    Text(ENGINE_FINGERPRINT_QUESTION, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Tech.Ink)
    val pairs = step.seen.shown.split(':').chunked(16).map { it.joinToString(":") }
    Column(Modifier.identity()) { pairs.forEach { Text(it, fontFamily = Tech.Mono, fontSize = 13.sp, color = Tech.Amber) } }
    Hint("Compare it with the line \"Certificate SHA-256\" in the installer's output. Only a match is trusted.")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MiniAction("Yes, it matches", primary = true) { setup.answer(step.seen, matches = true) }
        MiniAction("No") { setup.answer(step.seen, matches = false) }
    }
}

@Composable private fun TokenStep(setup: EngineSetup, step: EngineStep.Token) {
    val field = remember { TextFieldState() }
    fun submit() {
        val token = field.text.toString().toCharArray()
        field.clearText()
        setup.pair(step.pin, token)
    }
    Text("Enter the pairing token the installer showed once.", fontSize = 12.sp, color = Tech.Ink)
    SecretInput(field, "Pairing token (25 letters and digits)", onSubmit = ::submit)
    Row { MiniAction(if (setup.busy) "Pairing…" else "Pair", primary = true, enabled = !setup.busy) { submit() } }
    Hint("The system's password safe may ask once to keep the token. Without it, the pairing lasts until RingMASTER closes.")
}

@Composable private fun DoneStep(step: EngineStep.Done) {
    Text("Paired. The Radios tab of this guest now looks inside through the RingMASTER Engine.", fontSize = 13.sp, color = Tech.Green)
    Hint("Engine ${step.link.version} at ${step.link.host}:${step.link.port}. Settings › RingMASTER Engine lists it.")
    if (!step.link.tokenInSafe) {
        Text(ENGINE_TOKEN_THIS_RUN, fontSize = 12.sp, color = Tech.Amber)
    }
}

/** A sentence about a bundle file that cannot be used (chosen, or named by RINGMASTER_ENGINE_BUNDLE), shown in the setup dialog. */
internal object GuestEngineProblems {
    var last by mutableStateOf<String?>(null)
        private set
    fun report(sentence: String) { last = sentence }
    fun clear() { last = null }
}
