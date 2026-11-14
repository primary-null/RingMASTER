package local.ringleader.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ringleader.core.*
import java.time.Duration
import java.time.Instant

/** "12S AGO", "4M AGO", "2H AGO", "3D AGO" — the age badge on a last known console image. */
internal fun stillAge(at: Instant, now: Instant): String {
    val s = Duration.between(at, now).seconds.coerceAtLeast(0)
    return when { s < 60 -> "${s}S AGO"; s < 3600 -> "${s / 60}M AGO"; s < 86_400 -> "${s / 3600}H AGO"; else -> "${s / 86_400}D AGO" }
}

/**
 * Guests with a live console open in this app: the in-app VNC view or a SPICE remote-viewer. Counted, because two
 * views of one guest can overlap for a moment while one replaces the other.
 */
object LiveConsoles {
    private val open = mutableMapOf<ResourceId, Int>()
    @Synchronized fun open(id: ResourceId) { open[id] = (open[id] ?: 0) + 1 }
    @Synchronized fun close(id: ResourceId) { val n = (open[id] ?: return) - 1; if (n <= 0) open.remove(id) else open[id] = n }
    @Synchronized fun isOpen(id: ResourceId): Boolean = id in open
}

/** Minutes after which opening the guest's Summary captures a fresh still. */
internal const val STILL_AUTO_CAPTURE_MINUTES = 10L

/**
 * True when opening the Summary should capture a still: none yet, or the last one is old, and no live console for the
 * guest is open (that would be one more vncproxy ticket and console task for a screen the user is already watching).
 */
internal fun shouldAutoCaptureStill(last: Instant?, now: Instant, liveConsole: Boolean): Boolean =
    !liveConsole && (last == null || Duration.between(last, now).toMinutes() >= STILL_AUTO_CAPTURE_MINUTES)

/** The Summary page's automatic capture. A manual capture (the ↻ button) is always allowed. */
fun autoCaptureConsoleStill(state: WorkspaceState, workspace: Workspace, guest: Guest) {
    if (shouldAutoCaptureStill(state.consoleStills[guest.resource]?.at, Instant.now(), LiveConsoles.isOpen(guest.resource))) workspace.captureConsoleStill(guest)
}

@Composable internal fun rememberStillBitmap(still: ConsoleStill?): ImageBitmap? = remember(still?.at, still?.png?.size) {
    still?.let { runCatching { org.jetbrains.skia.Image.makeFromEncoded(it.png).toComposeImageBitmap() }.getOrNull() }
}

/**
 * The guest's last known screen (16:10): the captured frame dimmed slightly, an amber age badge, an Open console
 * plate and a recapture action. In showcase mode the frame is blurred hard, since a console can show anything.
 */
@Composable fun ConsoleStillCard(state: WorkspaceState, workspace: Workspace, guest: Guest, width: Dp, compact: Boolean = false, onOpenConsole: (() -> Unit)? = null) {
    val still = state.consoleStills[guest.resource]
    val busy = guest.resource in state.consoleStillBusy
    val error = state.consoleStillErrors[guest.resource]
    val bitmap = rememberStillBitmap(still)
    val now = LocalNow.current ?: Instant.now()
    Column(if (width == Dp.Unspecified) Modifier.fillMaxWidth() else Modifier.width(width), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(Tech.Plate).background(Color.Black).border(1.dp, Tech.Edge, Tech.Plate)) {
            if (bitmap != null) Image(bitmap, "Last known screen of ${guest.kind} ${guest.id}", Modifier.fillMaxSize().alpha(.78f)
                .then(if (Appearance.showcase) Modifier.blur(18.dp) else Modifier), contentScale = ContentScale.Crop)
            else Text(when { busy -> "CAPTURING…"; still?.blank == true -> "SCREEN IS BLANK"; else -> "NO CAPTURE YET" },
                Modifier.align(Alignment.Center), fontSize = 10.sp, letterSpacing = 1.sp, fontFamily = Tech.Mono, color = Color(0xFF8A918D))
            if (still != null) Text(if (busy) "CAPTURING…" else stillAge(still.at, now), Modifier.align(Alignment.TopEnd).padding(8.dp)
                .background(Color.Black.copy(alpha = .6f), Tech.Control).padding(horizontal = 6.dp, vertical = 2.dp),
                fontSize = 9.sp, letterSpacing = 1.sp, fontFamily = Tech.Mono, fontWeight = FontWeight.Bold, color = Color(0xFFFF9800))
            if (!compact && onOpenConsole != null) Box(Modifier.align(Alignment.Center).background(ringColors.surface, Tech.Control)) {
                HelpTip("Open the console") { MiniAction("Open console", primary = true, onClick = onOpenConsole) }
            }
            if (!compact) Box(Modifier.align(Alignment.BottomStart).padding(8.dp).background(ringColors.surface, Tech.Control)) {
                HelpTip("Capture the screen now (read-only: nothing is typed or clicked in the VM)") { MiniAction(if (busy) "…" else "↻ Capture", enabled = !busy) { workspace.captureConsoleStill(guest) } }
            }
        }
        if (!compact && error != null) Text(error, fontSize = 9.sp, lineHeight = 12.sp, fontFamily = Tech.Mono, color = Tech.Amber, maxLines = 2)
    }
}

/**
 * The console still shown while the guest runs, with the CRT switch-off when it stops: the picture squashes to a bright
 * line, the line narrows to a dot and decays (a hard stop is the faster, harder cut). Under Reduced it fades.
 */
@Composable fun ConsoleStillSlot(state: WorkspaceState, workspace: Workspace, guest: Guest, width: Dp, compact: Boolean = false, onOpenConsole: (() -> Unit)? = null) {
    val visible = state.consoleStills[guest.resource] != null && guest.status == "running"
    CrtCollapseHost(guest.resource, visible) { ConsoleStillCard(state, workspace, guest, width, compact, onOpenConsole) }
}
