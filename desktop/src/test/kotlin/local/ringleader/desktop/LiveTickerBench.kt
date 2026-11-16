@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package local.ringleader.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.*
import local.ringleader.core.*

/** One tray under test: its state on a clock the test moves, a feed that answers with whatever the test last said, and a recording navigator. */
internal class TickerBench(var now: Instant = TickerData.now) {
    val state = LiveTickerState({ now })
    var input: TickerInput = TickerData.calm
    val feed = TickerFeed({ input.activity }, { TraceReading(input.vitals, input.metrics, input.loop) }, { input.now })
    val went = mutableListOf<TickerTarget>()
    val pages = mutableListOf<String>()
    val focus = PageFocus()
    val nav = TickerNav({ pages += it }, { guest, console -> went += TickerTarget.Fleet(guest, console); pages += "Fleet" }, focus)
    /** Take one reading now, as the sampler would, with [mode]. */
    fun sample(next: TickerInput = input, mode: TickerMode = LiveTickerPreference.mode) { input = next; state.apply(next, mode) }
}

internal const val SHOT_TITLE = "Lab cluster · https://pve-east.example.test:8006"

/**
 * The window as Main.kt builds it (title bar, rail, heading, page area) around one page, with the tray in the page area where
 * Main.kt puts it. The heading and rail are the app's own composables; only the title bar's drag and native frame are left out.
 */
@Composable internal fun TickerShell(page: String, state: WorkspaceState, workspace: Workspace, lab: MatrixLab, rail: Boolean, bench: TickerBench, blocked: Boolean = false,
    period: Long = 0L, sessionChip: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.background(Tech.Canvas)) {
        Row(Modifier.fillMaxWidth().background(Tech.Raised).height(32.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Image(AppIcon.small, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("RingMASTER", color = Tech.Amber)
            }
            WindowControl(WindowGlyph.Minimize) {}; WindowControl(WindowGlyph.Maximize) {}; WindowControl(WindowGlyph.Close) {}
        }
        Row(Modifier.weight(1f).background(MaterialTheme.colorScheme.background)) {
            NavigationRail(state, page, "Windows 11", workspace, initialExpanded = rail, pins = lab.pins, connectionKey = lab.origin) {}
            Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
                WorkspaceHeading(page, state, sessionChip, null)
                HorizontalDivider(color = Tech.Edge)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    content()
                    LiveTickerHost(page, bench.state, bench.feed, bench.nav, blocked, period = period)
                }
            }
        }
    }
}
