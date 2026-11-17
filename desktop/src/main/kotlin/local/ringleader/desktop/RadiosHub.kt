package local.ringleader.desktop

import local.ringleader.core.PDM_KIND
import local.ringleader.core.WorkspaceState

/**
 * The board of a session that has no workspace to read through (a preview): this computer and the server's nodes, nothing
 * read. A PDM connection says it cannot be read.
 */
internal fun unreadRadiosBoard(state: WorkspaceState): RadiosBoard {
    val pdm = state.kind == PDM_KIND
    val nodes = if (pdm) emptyList() else state.overview?.members.orEmpty().map { RadioHost(it.name, it.name, RadioHostKind.Node, it.status == "online") }
    return RadiosBoard(
        hosts = listOf(RadioHost(THIS_COMPUTER_KEY, THIS_COMPUTER_LABEL, RadioHostKind.ThisComputer)) + nodes,
        notes = listOf(RadioNote(if (pdm) RADIOS_PDM else "Radios on the server are not read in this build.")),
    )
}

/** The hub of a session that has no workspace to read through: the unread board, and refusals for everything else. */
internal fun unreadRadiosHub(state: WorkspaceState, nav: RadiosNav) = RadiosHub(
    FixedRadiosFeed(unreadRadiosBoard(state)),
    FixedWifiFeed(WifiPanel(listOf(WifiSourceView(THIS_COMPUTER_KEY, THIS_COMPUTER_LABEL)), THIS_COMPUTER_KEY, refusal = RadioNote(WIFI_UNAVAILABLE))),
    BoardGuestRadios(), nav)

/**
 * The hub for the session in view: read through [workspace] (RadiosLive.kt), or the unread board without one.
 */
@androidx.compose.runtime.Composable internal fun rememberRadiosHub(
    state: WorkspaceState, nav: RadiosNav, workspace: local.ringleader.core.Workspace? = null, session: String? = null, active: Boolean = false,
    radiosOpen: Boolean = false, scanner: local.ringleader.core.WifiScanner? = null,
): RadiosHub {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return if (workspace != null) rememberLiveRadiosHub(state, workspace, session, nav, active, radiosOpen, scanner)
    else androidx.compose.runtime.remember(nav, state.kind, state.overview?.members) { unreadRadiosHub(state, nav) }
}
