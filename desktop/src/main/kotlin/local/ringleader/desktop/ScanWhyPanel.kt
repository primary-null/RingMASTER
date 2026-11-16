@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import local.ringleader.core.*

/**
 * "Why isn't it here?": the person types the address of a server the scan did not show, and the page says which planned network would
 * have contained it, or that none did and why ([ScanExplain]). Nothing is sent. The answer follows the plan and the last scan, so
 * ticking the network it names, or typing the one it suggests, changes the answer at once.
 */
@Composable internal fun ScanWhyPanel(session: ScanSession) {
    var typed by remember { mutableStateOf("") }
    var asked by remember { mutableStateOf("") }
    val answer = remember(asked, session.plan, session.progress) { if (asked.isBlank()) null else ScanExplain.explain(asked, session.plan, session.progress) }
    val check = { asked = typed.trim() }
    TechPanel(Modifier.fillMaxWidth()) {
        SectionLabel("Why isn't it here?")
        Spacer(Modifier.height(4.dp))
        Text("Type the address of a server you expected to find. Nothing is sent: the answer is read from the networks on this page and from the last scan.", color = Tech.Muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tech.Space8), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.Bottom) {
            // Enter checks the address; the page's own Enter (start a scan) is not for this field.
            TechField(typed, { typed = it }, "Address", placeholder = "e.g. 10.20.30.5", modifier = Modifier.width(scanFieldWidth()).identity()
                .onKeyEvent { if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) { check(); true } else false })
            MiniAction("Check", primary = true, onClick = check)
        }
        answer?.let { a ->
            Spacer(Modifier.height(8.dp))
            // The answer names networks, cards and addresses: identities, like the rest of the page.
            Text(a.text, Modifier.identity().semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 12.sp, color = Tech.Ink)
            if (a.tick != null || a.typed != null) {
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Tech.Space8), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    a.tick?.let { id -> Box(Modifier.identity()) { MiniAction("Tick ${session.plan.rows.firstOrNull { it.id == id }?.sweep ?: id}") { session.setChoice(id, true) } } }
                    a.typed?.let { network -> Box(Modifier.identity()) { MiniAction("Type $network") { session.extraSubnet = network } } }
                }
            }
        }
    }
}
