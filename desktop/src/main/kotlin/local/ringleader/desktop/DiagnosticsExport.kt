package local.ringleader.desktop

import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.ringleader.core.*
import java.io.File

/**
 * The open sessions as a diagnostics bundle describes them, read when the export starts. Main provides it; the
 * default (previews, tests) has none.
 */
val LocalDiagnosticSessions = androidx.compose.runtime.staticCompositionLocalOf<() -> List<DiagnosticSessionInfo>> { { emptyList() } }

/** True while connections other than the one in view are open, so a failure card can say they are not affected. */
val LocalOtherSessionsOpen = androidx.compose.runtime.compositionLocalOf { false }

/** [session] with the heading's route observation added, when that observation is for the same server. */
fun withRoute(session: DiagnosticSessionInfo, route: RouteTagState): DiagnosticSessionInfo {
    val seen = (route as? RouteTagState.Observed)?.observation?.takeIf { it.target.origin == session.address } ?: return session
    return session.copy(
        routeKind = listOfNotNull(seen.kind.name, seen.vpnKind?.name, seen.proxy?.let { "proxy" }).joinToString(" / "),
        routeInterface = listOfNotNull(seen.interfaceName, seen.medium?.label).joinToString(" · ").ifEmpty { null },
        routeGateway = seen.gateway,
        isVpn = seen.kind == RouteKind.Vpn,
    )
}

/**
 * Builds the diagnostics bundle and saves it (file dialog on the desktop, clipboard in browser mode).
 * It holds server addresses, certificate fingerprints, the sign-in type and the redacted trace; never a user name
 * typed here, a password, a token or a ticket. [onStatus] receives the progress and result lines.
 */
fun exportDiagnosticsBundle(
    scope: CoroutineScope,
    appHost: AppHost,
    clipboard: ClipboardManager,
    sessions: List<DiagnosticSessionInfo>,
    onStatus: (String) -> Unit = {},
) {
    scope.launch {
        onStatus("Checking each open server and building the bundle…")
        val report = withContext(Dispatchers.IO) {
            createDiagnosticReport(
                appVersion = AppVersion.value,
                renderApi = HoodRenderApi.value,
                sessions = sessions,
                recentTraceLimit = 80,
            )
        }
        val json = report.toJsonString()

        if (appHost.web) {
            clipboard.setText(AnnotatedString(json))
            onStatus("Diagnostics bundle copied to clipboard.")
            return@launch
        }

        val defaultName = "ringmaster-diagnostics-${System.currentTimeMillis() / 1000}.json"
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Export diagnostics bundle", java.awt.FileDialog.SAVE)
        dialog.file = defaultName
        dialog.isVisible = true

        val chosenFile = dialog.file
        val chosenDir = dialog.directory
        dialog.dispose()

        if (chosenFile != null && chosenDir != null) {
            val target = File(chosenDir, chosenFile)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    target.writeText(json, Charsets.UTF_8)
                    "Diagnostics exported to ${target.name}"
                }.getOrElse { "Failed to export diagnostics: ${it.message}" }
            }
            onStatus(result)
        } else {
            clipboard.setText(AnnotatedString(json))
            onStatus("Save cancelled; diagnostics copied to clipboard.")
        }
    }
}
