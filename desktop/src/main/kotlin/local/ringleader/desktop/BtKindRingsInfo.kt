package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ringleader.core.BtDevice

/** Counts tick independently of geometry and table rows; no sorting or sample copies per tick. */
@Composable internal fun KindRingsSummary(devices: List<BtDevice>, clock: ScanClock) {
    val heard = remember(devices) { devices.filter { it.fieldRssi() != null } }
    val counts by remember(heard, clock) { derivedStateOf {
        val now = clock.coarse.longValue
        val strong = heard.count { it.fieldRssi()!! >= -60 && (it.lastSeenMs == null || now - it.lastSeenMs!! <= 5000) }
        val fresh = heard.count { it.firstSeenMs?.let { t -> now-t in 0 until 3000 } == true }
        val quiet = heard.count { it.lastSeenMs?.let { t -> now-t > 5000 } == true }
        "${heard.size} HEARD · $strong STRONG · $fresh NEW · $quiet QUIET"
    } }
    Text(counts, fontFamily=Tech.Mono, fontSize=10.sp, lineHeight=15.sp, color=Tech.Amber)
    if (devices.size > heard.size) Text("${devices.size-heard.size} known, not heard", fontFamily=Tech.Mono,fontSize=10.sp,color=Tech.Muted)
}

@Composable internal fun KindRingsKey() {
    Text("Kind rings", fontFamily=Tech.Mono, fontSize=12.sp, color=Tech.Ink)
    Text("Radius shows signal strength, not physical distance. Sectors group reported kinds; direction and location are unknown.",
        fontFamily=Tech.Mono,fontSize=10.sp,lineHeight=14.sp,color=Tech.Muted)
    Text("Strong ≥ −60 dBm · middle ≥ −78 · weaker outside. Amber halo: new <3 s. Hollow: quiet >5 s. Amber: paired; green: connected.",
        fontFamily=Tech.Mono,fontSize=10.sp,lineHeight=14.sp,color=Tech.Muted)
}

/** Short signal ranking; all devices remain in the searchable table. */
@Composable internal fun StrongestDevices(devices: List<BtDevice>, clock: ScanClock, mark: String?, onSelect: (String) -> Unit) {
    val strongest = remember(devices) { devices.filter { it.fieldRssi() != null }.sortedByDescending { it.fieldRssi() }.take(3) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("STRONGEST OBSERVED",fontFamily=Tech.Mono,fontSize=10.sp,color=Tech.Amber)
        strongest.forEach { d ->
            Row(Modifier.fillMaxWidth().clickable { onSelect(d.address) }.padding(4.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(d.name?.takeIf { it.isNotBlank() } ?: "Unnamed device",Modifier.weight(1f).identity(),fontFamily=Tech.Mono,fontSize=10.sp,color=if(d.address==mark) Tech.Amber else deviceTint(d),maxLines=1,overflow=TextOverflow.Ellipsis)
                Text("${d.fieldRssi()!!.toInt()} dBm",fontFamily=Tech.Mono,fontSize=10.sp,color=Tech.Muted)
                HeardAgo(d, clock, Modifier.width(42.dp))
            }
        }
    }
}
