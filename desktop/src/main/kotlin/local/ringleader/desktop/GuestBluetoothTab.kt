@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import local.ringleader.core.*

/**
 * A VM's Bluetooth: what is attached to it (USB host devices, PVE mappings, SPICE USB channels, PCI cards), a
 * read-only BlueZ scan run inside the guest through the guest agent, and where pass-through will live.
 */
@Composable fun GuestBluetoothTab(state: WorkspaceState, workspace: Workspace, guest: Guest, hardware: HardwareSummary?) {
    val scope = rememberCoroutineScope()
    var scan by remember(guest.resource) { mutableStateOf<BtScan?>(null) }
    var scanning by remember(guest.resource) { mutableStateOf(false) }
    var error by remember(guest.resource) { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Attached to this VM", suffix = hardware?.let { "${it.usb.size + it.pci.size}" })
        if (hardware == null) Text("Loading the VM configuration…", fontSize = 11.sp, color = Tech.Muted)
        else if (hardware.usb.isEmpty() && hardware.pci.isEmpty()) Text("No USB or PCI devices are attached.", fontSize = 11.sp, fontFamily = Tech.Mono, color = Tech.Muted)
        hardware?.usb?.forEach { usb ->
            AttachmentRow(usb.key, when (usb.kind) { UsbKind.Spice -> "SPICE CHANNEL"; UsbKind.Host -> "HOST DEVICE"; UsbKind.Mapping -> "MAPPING"; UsbKind.Unknown -> "USB" },
                when (usb.kind) {
                    UsbKind.Spice -> "A USB device on the computer running the console can be redirected here"
                    UsbKind.Host -> usb.vendorId?.let { "USB %04x:%04x on the node".format(it, usb.productId) } ?: "USB port ${usb.target} on the node"
                    UsbKind.Mapping -> "PVE resource mapping “${usb.target}”"
                    UsbKind.Unknown -> "Unrecognised USB entry"
                }, if (usb.usb3) "USB 3" else null)
        }
        hardware?.pci?.forEach { pci -> AttachmentRow(pci.key, "PCI DEVICE", pci.volume ?: pci.properties["host"] ?: "PCI passthrough", null) }

        SectionLabel("Scan inside this VM")
        val refusal = guestBluetoothRefusal(guest, directPve = state.kind != PDM_KIND && guest.scope.isEmpty(), hardware = hardware)
        FlowRow(itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
            MiniAction(if (scanning) "Scanning…" else "Scan inside VM · ${BT_SCAN_SECONDS_DEFAULT}s", primary = true, enabled = refusal == null && !scanning) { confirm = true }
            Text(refusal ?: "Uses the VM's own Bluetooth adapter. Nothing is paired or connected.",
                fontSize = 10.sp, lineHeight = 14.sp, fontFamily = Tech.Mono, color = Tech.Muted)
        }
        error?.let { Text(it, fontSize = 11.sp, color = Tech.Amber) }
        if (scan != null || scanning) BtScanView(scan, scanning, Modifier.fillMaxWidth().height(520.dp), platform = "Guest agent · BlueZ")

    }
    val cancel = remember { androidx.compose.ui.focus.FocusRequester() }
    if (confirm) TechDialog("Scan inside ${guest.kind} ${guest.id}?", { confirm = false }, subtitle = guest.name, initialFocus = cancel, actions = {
        TechAction("Scan", active = true) {
            confirm = false; scanning = true; error = null
            scope.launch {
                try { scan = workspace.guestBluetoothScan(guest) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = if (e is java.io.IOException) readableError(e) else e.message ?: "The scan inside the VM failed." }
                finally { scanning = false }
            }
        }
        TechAction("Cancel", Modifier.focusRequester(cancel)) { confirm = false }
    }) { Text("RingMASTER will listen for Bluetooth devices for ${BT_SCAN_SECONDS_DEFAULT} seconds using the VM's adapter, through the QEMU guest agent. Nothing is paired or connected.", fontSize = 13.sp, lineHeight = 19.sp) }
}

@Composable private fun AttachmentRow(key: String, kind: String, detail: String, extra: String?) {
    Column(Modifier.fillMaxWidth().border(1.dp, Tech.Edge, Tech.Control).background(Tech.Black.copy(alpha = .3f)).padding(horizontal=10.dp,vertical=8.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
        FlowRow(itemVerticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(12.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(key, fontFamily=Tech.Mono, fontSize=11.sp, color=Tech.Amber)
            Text(kind, fontFamily=Tech.Mono, fontSize=9.sp, letterSpacing=1.sp, fontWeight=FontWeight.Bold, color=Tech.Ink)
            extra?.let { HealthyTag(it, help="This USB device is attached as USB 3.") }
        }
        Text(detail, Modifier.fillMaxWidth().identity(), fontFamily=Tech.Mono, fontSize=11.sp, color=Tech.Muted)
    }
}
