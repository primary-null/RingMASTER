@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import local.ringleader.core.*

/*
 * A guest's Radios tab (it was the Bluetooth tab, and VMs only): the adapters the guest was given, with the host device
 * behind each and a link to the map; what the guest itself sees, read through the guest agent or a paired RingMASTER
 * Engine when the user asks; the Bluetooth scan inside it; and a Wi-Fi scan when the guest has a Wi-Fi adapter. Every
 * command inside the guest is confirmed first.
 */

private val noRadios = RadiosHub(FixedRadiosFeed(RadiosBoard()), FixedWifiFeed(WifiPanel()), BoardGuestRadios())

internal const val GUEST_PROBE_CONFIRM =
    "RingMASTER will list the Bluetooth controllers and Wi-Fi interfaces inside the VM, through the QEMU guest agent. Nothing is changed."
internal const val ENGINE_PROBE_CONFIRM =
    "RingMASTER will ask the RingMASTER Engine in this guest which Bluetooth controllers and Wi-Fi interfaces it sees. Nothing is changed."
internal const val ENGINE_WIFI_CONFIRM =
    "RingMASTER will ask the RingMASTER Engine in this guest for the Wi-Fi networks its adapter can hear. Nothing is joined or changed."

private fun btScanConfirm(engine: Boolean): String {
    val way = if (engine) "the RingMASTER Engine in this guest" else "the VM's adapter, through the QEMU guest agent"
    return "RingMASTER will listen for Bluetooth devices for $BT_SCAN_SECONDS_DEFAULT seconds using $way. Nothing is paired or connected."
}

/** A call into the guest that runs after a confirmation, with its busy flag, error and result. */
private class GuestCall<T>(val failed: String) {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var result by mutableStateOf<T?>(null)
}

@Composable fun GuestRadiosTab(state: WorkspaceState, workspace: Workspace, guest: Guest, hardware: HardwareSummary?) {
    val hub = LocalRadios.current ?: noRadios
    val board by hub.board.board.collectAsState()
    val pairings = hub.engine?.version?.collectAsState()?.value
    val view = remember(guest, hardware, board, pairings) { hub.guests.view(guest, hardware, board) }
    val probe = remember(guest.resource) { GuestCall<GuestProbeView>("Looking inside the guest failed.") }
    val wifi = remember(guest.resource) { GuestCall<WifiPanel>("The Wi-Fi scan inside the guest failed.") }
    val bt = remember(guest.resource) { GuestCall<BtScan>("The scan inside the guest failed.") }
    var confirm by remember { mutableStateOf<String?>(null) }
    val vm = guest.kind == "VM"
    val inside = vm || view.throughEngine
    val what = if (vm) "VM" else "container"
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GivenRadios(view, what) { id -> hub.nav.openRadios(RadiosTarget(RadiosView.Map, "radio:$id")) }
        if (vm) AttachedDevices(view, hardware)
        LookInside(view, what, inside, probe) { confirm = "probe" }
        if (inside) {
            val directPve = state.kind != PDM_KIND && guest.scope.isEmpty()
            val refusal = if (view.throughEngine) null else guestBluetoothRefusal(guest, directPve, hardware)
            BluetoothInside(what, refusal, bt, view.throughEngine) { confirm = "bt" }
        }
        if (view.hasWifi) WifiInside(view, what, wifi) { confirm = "wifi" }
    }
    val scope = rememberCoroutineScope()
    fun <T> start(call: GuestCall<T>, block: suspend () -> T) {
        confirm = null
        call.busy = true
        call.error = null
        scope.launch {
            try { call.result = block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { call.error = if (e is java.io.IOException) readableError(e) else e.message ?: call.failed }
            finally { call.busy = false }
        }
    }
    val engine = view.throughEngine
    when (confirm) {
        "probe" -> GuestCallConfirm("Look inside ${guest.kind} ${guest.id}?", guest, "Look inside",
            if (engine) ENGINE_PROBE_CONFIRM else GUEST_PROBE_CONFIRM, { confirm = null }) { start(probe) { hub.guests.probe(guest) } }
        "bt" -> GuestCallConfirm("Scan inside ${guest.kind} ${guest.id}?", guest, "Scan", btScanConfirm(engine), { confirm = null }) {
            start(bt) { workspace.guestBluetoothScan(guest) }
        }
        "wifi" -> GuestCallConfirm("Scan for Wi-Fi inside ${guest.kind} ${guest.id}?", guest, "Scan",
            if (engine) ENGINE_WIFI_CONFIRM else GUEST_WIFI_CONFIRM, { confirm = null }) { start(wifi) { hub.guests.wifiScan(guest) } }
    }
    val setupFor = hub.engine?.setup?.collectAsState()?.value
    if (setupFor?.resource == guest.resource) EngineSetupDialog(hub.engine!!, workspace, state, guest, hardware)
}

@Composable private fun GuestCallConfirm(title: String, guest: Guest, go: String, text: String, onCancel: () -> Unit, onGo: () -> Unit) {
    val cancel = remember { androidx.compose.ui.focus.FocusRequester() }
    TechDialog(title, onCancel, subtitle = guest.name, initialFocus = cancel, actions = {
        TechAction(go, active = true, onClick = onGo)
        TechAction("Cancel", Modifier.focusRequester(cancel), onClick = onCancel)
    }) { Text(text, fontSize = 13.sp, lineHeight = 19.sp) }
}

private fun Modifier.radioRow(edge: androidx.compose.ui.graphics.Color): Modifier =
    fillMaxWidth().border(1.dp, edge, Tech.Control).background(Tech.Black.copy(alpha = .3f)).padding(horizontal = 10.dp, vertical = 8.dp)

@Composable private fun ActionRow(content: @Composable () -> Unit) {
    FlowRow(
        itemVerticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable private fun Hint(text: String) {
    Text(text, fontSize = 10.sp, lineHeight = 14.sp, fontFamily = Tech.Mono, color = Tech.Muted)
}

@Composable private fun GivenRadios(view: GuestRadiosView, what: String, onMap: (String) -> Unit) {
    SectionLabel("Radios given to this $what", suffix = "${view.lines.size}")
    if (view.lines.isEmpty()) {
        Text("No Bluetooth or Wi-Fi adapter was given to this $what.", fontSize = 11.sp, fontFamily = Tech.Mono, color = Tech.Muted)
    }
    view.lines.forEach { line -> RadioLineRow(line, onMap) }
}

@Composable private fun AttachedDevices(view: GuestRadiosView, hardware: HardwareSummary?) {
    val radioEntries = view.lines.map { it.entry }.toSet()
    val usb = hardware?.usb.orEmpty().filter { it.key !in radioEntries }
    val pci = hardware?.pci.orEmpty().filter { it.key !in radioEntries }
    SectionLabel("Attached to this VM", suffix = hardware?.let { "${usb.size + pci.size}" })
    if (hardware == null) Text("Loading the VM configuration…", fontSize = 11.sp, color = Tech.Muted)
    else if (usb.isEmpty() && pci.isEmpty()) {
        Text("No other USB or PCI devices are attached.", fontSize = 11.sp, fontFamily = Tech.Mono, color = Tech.Muted)
    }
    usb.forEach { u -> AttachmentRow(u.key, usbKindLabel(u.kind), usbDetail(u), if (u.usb3) "USB 3" else null) }
    pci.forEach { p -> AttachmentRow(p.key, "PCI DEVICE", p.volume ?: p.properties["host"] ?: "PCI passthrough", null) }
}

private fun usbKindLabel(kind: UsbKind) = when (kind) {
    UsbKind.Spice -> "SPICE CHANNEL"
    UsbKind.Host -> "HOST DEVICE"
    UsbKind.Mapping -> "MAPPING"
    UsbKind.Unknown -> "USB"
}

private fun usbDetail(u: UsbAttachment) = when (u.kind) {
    UsbKind.Spice -> "A USB device on the computer running the console can be redirected here"
    UsbKind.Host -> u.vendorId?.let { "USB %04x:%04x on the node".format(it, u.productId) } ?: "USB port ${u.target} on the node"
    UsbKind.Mapping -> "PVE resource mapping “${u.target}”"
    UsbKind.Unknown -> "Unrecognised USB entry"
}

@Composable private fun LookInside(view: GuestRadiosView, what: String, inside: Boolean, call: GuestCall<GuestProbeView>, onAsk: () -> Unit) {
    SectionLabel("Inside the $what")
    ActionRow {
        if (inside) MiniAction(if (call.busy) "Looking…" else "Look inside the $what", primary = true, enabled = view.probeRefusal == null && !call.busy) {
            onAsk()
        }
        if (view.probeRefusal == null) Hint("Lists the Bluetooth controllers and Wi-Fi interfaces the $what itself sees. Nothing is changed.")
    }
    if (view.throughEngine) Hint("Through the RingMASTER Engine paired for this guest.")
    view.probeRefusal?.let { RadioNoteLine(it, size = 11) }
    call.error?.let { Text(it, fontSize = 11.sp, color = Tech.Amber) }
    call.result?.let { GuestProbeResult(it, what) }
}

@Composable private fun BluetoothInside(what: String, refusal: String?, call: GuestCall<BtScan>, engine: Boolean, onAsk: () -> Unit) {
    SectionLabel("Scan inside this $what")
    ActionRow {
        val label = if (call.busy) "Scanning…" else "Bluetooth scan · ${BT_SCAN_SECONDS_DEFAULT}s"
        MiniAction(label, primary = true, enabled = refusal == null && !call.busy, onClick = onAsk)
        Hint(refusal ?: "Uses the $what's own Bluetooth adapter. Nothing is paired or connected.")
    }
    call.error?.let { Text(it, fontSize = 11.sp, color = Tech.Amber) }
    val platform = if (engine) "RingMASTER Engine · BlueZ" else "Guest agent · BlueZ"
    if (call.result != null || call.busy) BtScanView(call.result, call.busy, Modifier.fillMaxWidth().height(520.dp), platform = platform)
}

@Composable private fun WifiInside(view: GuestRadiosView, what: String, call: GuestCall<WifiPanel>, onAsk: () -> Unit) {
    SectionLabel("Wi-Fi inside this $what")
    ActionRow {
        MiniAction(if (call.busy) "Scanning…" else "Wi-Fi scan", primary = true, enabled = view.wifiRefusal == null && !call.busy, onClick = onAsk)
        ShowNamesSwitch()
    }
    view.wifiRefusal?.let { RadioNoteLine(it, size = 11) }
    call.error?.let { Text(it, fontSize = 11.sp, color = Tech.Amber) }
    call.result?.let { WifiReadout(it, WifiNames.shown, Modifier.fillMaxWidth()) }
}

@Composable private fun RadioLineRow(line: GuestRadioLine, onMap: (String) -> Unit) {
    val edge = line.kind?.let { radioTint(it).dimmed(.6f) } ?: Tech.Edge
    Column(Modifier.radioRow(edge), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        ActionRow {
            if (line.kind != null) RadioKindChip(line.kind)
            Text(line.entry, fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Amber)
            Text(line.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Tech.Ink)
            val via = line.via.label.uppercase()
            Text(via, fontFamily = Tech.Mono, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = Tech.Ink)
            val kindName = line.kind?.label ?: "radio"
            when (line.sure) {
                RadioSure.Certain -> Unit
                RadioSure.Likely -> NeutralTag("Likely", help = "Probably a $kindName adapter, from its ids and driver.")
                RadioSure.Guess -> AttentionTag("Guess", help = "Looks like a $kindName adapter. The app could not confirm it.")
            }
        }
        val detail = listOfNotNull(line.detail, line.hostName?.let { "on $it" }).joinToString("  ·  ").ifBlank { line.via.help }
        Text(detail, Modifier.fillMaxWidth().identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted)
        line.radioId?.let { id -> Row { MiniAction("Show on map") { onMap(id) } } }
    }
}

@Composable private fun GuestProbeResult(p: GuestProbeView, what: String) {
    RadioPanel(Modifier.fillMaxWidth(), rail = Tech.Green, padding = 10.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("WHAT THE ${what.uppercase()} SEES", fontFamily = Tech.Mono, fontSize = 9.sp, letterSpacing = 1.sp, color = Tech.Muted)
            p.note?.let { RadioNoteLine(it, size = 11) }
            if (p.bluetooth.isEmpty() && p.wifi.isEmpty()) {
                Text("The $what sees no Bluetooth controller and no Wi-Fi interface.", fontSize = 12.sp, color = Tech.Ink)
            }
            p.bluetooth.forEach { c -> ProbeRadioRow(RadioKind.Bluetooth, c.name, c.driver, c.address, c.state, c.stateWhy) }
            p.wifi.forEach { w -> ProbeRadioRow(RadioKind.WiFi, w.name, w.driver, w.address, w.state, w.stateWhy) }
        }
    }
}

@Composable private fun ProbeRadioRow(kind: RadioKind, name: String, driver: String?, address: String?, state: RadioState, why: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioGlyph(kind)
            val title = listOfNotNull(name, driver?.let { "driver $it" }).joinToString(" · ")
            Text(title, fontFamily = Tech.Mono, fontSize = 12.sp, color = Tech.Ink)
            address?.let { Text(it, Modifier.identity(), fontFamily = Tech.Mono, fontSize = 10.sp, color = Tech.Muted) }
            RadioStateTag(state)
        }
        if (why.isNotEmpty()) Text(why, Modifier.padding(start = 26.dp), fontSize = 11.sp, color = Tech.Muted)
    }
}

@Composable private fun AttachmentRow(key: String, kind: String, detail: String, extra: String?) {
    Column(Modifier.radioRow(Tech.Edge), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ActionRow {
            Text(key, fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Amber)
            Text(kind, fontFamily = Tech.Mono, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = Tech.Ink)
            extra?.let { HealthyTag(it, help = "This USB device is attached as USB 3.") }
        }
        Text(detail, Modifier.fillMaxWidth().identity(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted)
    }
}
