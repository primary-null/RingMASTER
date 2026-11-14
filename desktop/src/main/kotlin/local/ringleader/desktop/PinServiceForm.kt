package local.ringleader.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import local.ringleader.core.FindProgress
import local.ringleader.core.GuestHints
import local.ringleader.core.HintSource
import local.ringleader.core.SERVICE_CATALOGUE
import local.ringleader.core.ServiceEndpoint
import local.ringleader.core.ServiceProbe
import local.ringleader.core.ServiceSuggestion
import local.ringleader.core.ServiceTest
import local.ringleader.core.TestLevel
import local.ringleader.core.guessServices
import local.ringleader.core.notesAddresses
import local.ringleader.core.portsToFind
import local.ringleader.core.preferredHosts
import local.ringleader.core.rankSuggestions
import local.ringleader.core.serviceAddress

/** Find services looks at no more than this many of the guest's addresses. */
internal const val FIND_HOSTS = 3

/** What Find services and Test send, for the form's help text. It must match ServiceProbe. */
internal fun serviceProbeHelp(ports: Int, hosts: Int): String =
    "Find services connects from this computer to $ports ports on ${if (hosts == 1) "the guest's address" else "$hosts of the guest's addresses"}, " +
        "six at a time, and sends one plain GET to each open port (User-Agent RingMASTER, no sign-in, no cookies) to tell http " +
        "from https and read the page title. Test does the same for the address above. Certificates are checked as usual: " +
        "an untrusted one is reported, never accepted. Nothing is sent anywhere else."

/** The address with [host] in place of its host, keeping scheme, port and path. A bare `http://host` when [url] is not usable. */
internal fun addressWithHost(url: String, host: String): String {
    if (serviceAddressError(url) != null) return serviceUrlForHost(host)
    val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return serviceUrlForHost(host)
    val secure = uri.scheme.equals("https", true)
    val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
    return serviceAddress(host, ServiceEndpoint(port, secure, uri.rawPath?.takeIf { it.startsWith("/") } ?: "/"))
}

/** The host of a pinned address, for editing a pin whose guest has no reported addresses in view. */
internal fun pinnedHost(url: String?): String? = url?.let { runCatching { java.net.URI(it.trim()).host }.getOrNull() }?.removePrefix("[")?.removeSuffix("]")?.ifBlank { null }

/**
 * The Pin a service form, for a new pin and for Edit address. Suggestions come from the catalogue (the guest's notes,
 * name and tags), from addresses written in the notes, and, after Find services, from what the guest's ports answered.
 * A suggestion fills the fields; the typed address always wins and nothing is saved before [onSave]. [canProbe] is false
 * in browser mode and in the demo: Find services and Test are then not offered. A Soft dialog: a click outside is
 * ignored, Escape cancels. Addresses are concealed in showcase mode.
 */
@Composable internal fun ServicePinForm(
    hints: GuestHints,
    hosts: List<String>,
    canProbe: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String) -> String?,
    initialName: String = "",
    initialAddress: String = "",
    editing: Boolean = false,
    probeUnavailable: String = "Find services and Test need the desktop app and a live connection.",
    probe: ServiceProbe = remember { ServiceProbe() },
    /** Tests replace the ports Find services looks at. */
    findPorts: List<Int>? = null,
) {
    var name by remember { mutableStateOf(initialName) }
    var address by remember { mutableStateOf(initialAddress) }
    var refused by remember { mutableStateOf<String?>(null) }
    var filledFrom by remember { mutableStateOf<ServiceSuggestion?>(null) }
    // True while the address is the form's own first guess, so a better guess may still replace it.
    var autoFilled by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf<FindProgress?>(null) }
    var findJob by remember { mutableStateOf<Job?>(null) }
    var findFailed by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    var tested by remember { mutableStateOf<Pair<String, ServiceTest>?>(null) }
    val scope = rememberCoroutineScope()
    val ordered = remember(hosts) { preferredHosts(hosts) }
    val guesses = remember(hints) { guessServices(hints) }
    val notes = remember(hints, ordered) { notesAddresses(hints.description, ordered) }
    val answers = finding?.answers
    val suggestions = remember(guesses, ordered, notes, answers) { rankSuggestions(guesses, ordered, notes, answers) }
    val probeHosts = remember(ordered, address) { ordered.take(FIND_HOSTS).ifEmpty { listOfNotNull(pinnedHost(address)) } }
    val ports = remember(guesses, findPorts) { findPorts ?: portsToFind(guesses) }

    fun use(s: ServiceSuggestion) {
        autoFilled = false
        address = s.url
        if (name.isBlank() || name == filledFrom?.name?.take(32)) name = s.name.take(32)
        filledFrom = s; refused = null
    }
    // A first guess for a new pin, once the guest's addresses are known: the best suggestion, or the bare host. The
    // notes can arrive after the addresses, so a guess the user has not touched is replaced by a better one.
    LaunchedEffect(ordered, guesses) {
        if (editing || (address.isNotEmpty() && !autoFilled)) return@LaunchedEffect
        val best = suggestions.firstOrNull { it.score >= 55 }
        if (best != null) use(best) else ordered.firstOrNull()?.let { address = serviceUrlForHost(it) }
        autoFilled = address.isNotEmpty()
    }

    val nameError = if (name.isEmpty()) null else serviceNameError(name)
    val addressError = if (address.isEmpty()) null else serviceAddressError(address)
    val stripped = if (addressError != null) withoutSecretParts(address) else null
    val ready = serviceNameError(name) == null && serviceAddressError(address) == null
    val bareGuess = addressError == null && ordered.any { address.trim() == serviceUrlForHost(it) } && suggestions.none { it.url == address.trim() }
    val hint = when {
        addressError != null || refused != null -> null
        bareGuess -> "The port is a guess: most services do not listen on port 80. Pick a suggestion${if (canProbe) " or use Find services" else ""}."
        filledFrom?.url == address.trim() -> "From ${filledFrom!!.source.label}. ${if (canProbe) "Test it before pinning." else "Check it opens before relying on it."}"
        else -> "http or https, with a host."
    }
    val first = remember { FocusRequester() }
    TechDialog(if (editing) "Edit pinned service" else "Pin a service", onDismiss, width = 480.dp,
        subtitle = "Opens in the system browser. Saved without a username, password, query or token.", dismiss = Dismiss.Soft, initialFocus = first, actions = {
            TechAction(if (editing) "Save" else "Pin service", active = true, enabled = ready) { refused = onSave(name.trim(), address.trim()) }
            TechAction("Cancel", onClick = onDismiss)
        }) {
        TechField(name, { name = it.take(32) }, "Name", Modifier.focusRequester(first).testTag("service-name"), placeholder = "AdGuard UI", error = nameError)
        Box(Modifier.fillMaxWidth().identity()) {
            TechField(address, { address = it.take(200); autoFilled = false; refused = null; tested = null }, "Address", Modifier.testTag("service-address"),
                placeholder = "http://192.0.2.8:3000", error = addressError ?: refused, hint = hint)
        }
        if (stripped != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("A token there would be saved in plain text.", Modifier.weight(1f), fontSize = 11.sp, color = Tech.Muted)
            MiniAction("Remove it") { address = stripped; refused = null; tested = null }
        }
        if (canProbe) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniAction(if (testing) "Testing" else "Test", enabled = !testing && serviceAddressError(address) == null) {
                val target = address.trim()
                testJob?.cancel()
                testing = true
                testJob = scope.launch {
                    try {
                        tested = target to probe.test(target)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        tested = target to ServiceTest(TestLevel.Bad, "The test could not run: ${e.message ?: "unknown error"}.")
                    } finally { testing = false }
                }
            }
            val result = tested?.takeIf { it.first == address.trim() }?.second
            if (result != null) {
                val tint = when (result.level) { TestLevel.Good -> Tech.Green; TestLevel.Warn -> Tech.Amber; TestLevel.Bad -> Tech.Red }
                Text(result.message, Modifier.weight(1f).testTag("service-test").then(if (result.fromPage) Modifier.identity() else Modifier), fontSize = 12.sp, color = tint)
                result.fix?.let { fix -> MiniAction("Use it") { address = fix; tested = null; refused = null } }
            } else if (testing) Text("Checking the address…", Modifier.weight(1f), fontSize = 12.sp, color = Tech.Muted)
        }
        if (suggestions.isNotEmpty()) {
            Text("SUGGESTIONS", fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = .8.sp, color = Tech.Muted)
            suggestions.forEach { s -> SuggestionRow(s, chosen = s.url == address.trim()) { use(s) } }
        } else if (ordered.isEmpty() && !editing) {
            Text("The guest reports no address, so there is nothing to suggest. Type the address.", fontSize = 11.sp, color = Tech.Muted)
        }
        if (canProbe) {
            val progress = finding
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val running = findJob?.isActive == true && progress?.finished != true
                MiniAction(if (running) "Stop" else if (progress?.finished == true) "Find again" else "Find services", enabled = running || probeHosts.isNotEmpty()) {
                    if (running) { findJob?.cancel(); findJob = null; return@MiniAction }
                    findFailed = null
                    finding = FindProgress(0, probeHosts.size * ports.size, emptyList())
                    findJob = scope.launch {
                        try {
                            val hintFor = { port: Int -> (guesses.map { it.service } + SERVICE_CATALOGUE).flatMap { it.endpoints }.firstOrNull { it.port == port } }
                            probe.find(probeHosts, ports, hintFor).collect { finding = it }
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { findFailed = "Find services stopped: ${e.message ?: "unknown error"}." }
                    }
                }
                val status = when {
                    findFailed != null -> findFailed!!
                    progress == null -> if (probeHosts.isEmpty()) "Needs an address on the guest." else "Looks at ${ports.size} ports from this computer."
                    progress.finished -> progress.answers.count { it.secure != null }.let { if (it == 0) "No web page answered on ${progress.total} ports." else "$it web ${if (it == 1) "page" else "pages"} answered." }
                    findJob?.isActive != true -> "Stopped after ${progress.checked} of ${progress.total}."
                    else -> "Checked ${progress.checked} of ${progress.total}…"
                }
                Text(status, Modifier.weight(1f).testTag("service-find"), fontSize = 11.sp, color = if (findFailed != null) Tech.Red else Tech.Muted, maxLines = 2)
            }
            if (progress != null && !progress.finished && findJob?.isActive == true) TechProgress(fraction = if (progress.total == 0) 0f else progress.checked / progress.total.toFloat())
            Text(serviceProbeHelp(ports.size, probeHosts.size.coerceAtLeast(1)), fontSize = 10.sp, lineHeight = 13.sp, color = Tech.Muted.copy(alpha = .8f))
        } else {
            Text(probeUnavailable, fontSize = 11.sp, color = Tech.Muted)
        }
        if (ordered.isNotEmpty()) {
            Text("REPORTED ADDRESSES", fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = .8.sp, color = Tech.Muted)
            ordered.forEach { host ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(host, Modifier.weight(1f).identity(), fontFamily = Tech.Mono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    MiniAction("Use") { address = addressWithHost(address, host); refused = null; tested = null }
                }
            }
        }
    }
}

/** One suggestion: name, address and where it came from. A click fills the form. */
@Composable private fun SuggestionRow(s: ServiceSuggestion, chosen: Boolean, onUse: () -> Unit) {
    // A name taken from a page title, and the quoted title, can name a host; showcase mode conceals them.
    val pageText = s.source == HintSource.Found && SERVICE_CATALOGUE.none { it.name == s.name }
    Row(Modifier.fillMaxWidth().border(1.dp, if (chosen) Tech.Amber else Tech.Edge, Tech.Control).clickable(onClick = onUse)
        .padding(horizontal = 8.dp, vertical = 5.dp).testTag("service-suggestion"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, Modifier.weight(1f, fill = false).then(if (pageText) Modifier.identity() else Modifier), fontSize = 12.sp, color = Tech.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (s.source == HintSource.Found) HealthyTag("Answered") else NeutralTag(sourceTag(s.source))
            }
            Text(s.url, Modifier.identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.detail, Modifier.then(if (pageText || s.source == HintSource.Found) Modifier.identity() else Modifier), fontSize = 10.sp, color = Tech.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        MiniAction(if (chosen) "In use" else "Use", primary = chosen, onClick = onUse)
    }
}

internal fun sourceTag(source: HintSource): String = when (source) {
    HintSource.Found -> "Answered"
    HintSource.NotesAddress, HintSource.NotesScript, HintSource.Notes -> "Notes"
    HintSource.Name -> "Name"
    HintSource.Tag -> "Tag"
    HintSource.Port -> "Port"
}

/**
 * Edit address for a pinned service in the rail. The guest's hints come from the inventory and, when that guest is the
 * one inspected, its notes and reported addresses; otherwise the pin's own host is used.
 */
@Composable internal fun ServicePinEditor(state: local.ringleader.core.WorkspaceState, store: PinStore, connectionKey: String, pin: Pin, onDismiss: () -> Unit) {
    val guest = state.overview?.guests?.firstOrNull { pin.matches(it) }
    val report = state.inspection?.takeIf { guest != null && it.resource == guest.resource }
    val hosts = report?.addresses?.value.orEmpty().mapNotNull(::reportedHost).distinct().ifEmpty { listOfNotNull(pinnedHost(pin.serviceUrl)) }
    val hints = GuestHints(guest?.name ?: pin.guestName, guest?.tags.orEmpty(), report?.hardware?.value?.fields?.get("description"))
    val web = LocalAppHost.current.web
    ServicePinForm(hints, hosts, canProbe = !state.demo && !web, onDismiss = onDismiss, initialName = pin.serviceName.orEmpty(),
        initialAddress = pin.serviceUrl.orEmpty(), editing = true,
        probeUnavailable = if (state.demo) "Find services and Test are not offered with sample data." else "Find services and Test need the desktop app.",
        onSave = { n, u -> store.updateService(connectionKey, pin.id, n, u).also { if (it == null) onDismiss() } })
}
