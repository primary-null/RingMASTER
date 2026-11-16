package local.ringleader.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

// Open and copy for the addresses the Scan page lists. Both are the person's own request: nothing here runs by itself,
// trusts a certificate, stores anything or contacts the server. The only thing handed to the system is the hit's own
// http(s) origin (scheme, address and port as the scan observed them), which the system opens in its default browser.

/** How long a "Copied" confirmation stays beside the address it was made for. */
internal const val SCAN_COPIED_MS = 2000L
internal const val SCAN_COPIED_NOTE = "Copied"
internal const val SCAN_COPY_FAILED_NOTE = "Could not copy."
internal const val SCAN_NO_BROWSER_NOTE = "No browser opened, so the link was copied."
internal const val SCAN_NO_BROWSER_NO_COPY_NOTE = "No browser opened, and the link could not be copied."
internal const val SCAN_BROWSER_PENDING_NOTE = "Browser launch still running."

/** What to tell the person after the system was asked to open a link, and whether the link goes to the clipboard instead. */
internal data class BrowserOutcome(val note: String?, val copyLink: Boolean)

internal fun browserOutcome(launch: BrowserLaunch): BrowserOutcome = when (launch) {
    BrowserLaunch.Accepted -> BrowserOutcome(null, false)
    BrowserLaunch.Pending -> BrowserOutcome(SCAN_BROWSER_PENDING_NOTE, false)
    BrowserLaunch.Failed -> BrowserOutcome(SCAN_NO_BROWSER_NOTE, true)
}

/** The one line the Scan page shows after an Open or Copy: [origin] is the address it belongs to. */
internal class ScanLinkNote(val origin: String, val text: String)

/**
 * The Open and Copy actions of the Scan page, with the note they leave behind. There is one note for the whole page:
 * every new action clears the one before it, and a "Copied" note also goes away by itself after [copiedMillis].
 *
 * [opener] hands a link to the system browser and blocks for a few seconds, so it runs on [io], never on the UI thread
 * ([openInBrowser] in the app, a recorder in tests, so a test never starts a browser). [copier] puts text on the clipboard
 * and may throw. Both are called on behalf of [scope], which is the page's own: leaving the page stops the timer.
 */
internal class ScanLinkActions(
    private val scope: CoroutineScope,
    private val opener: (String) -> BrowserLaunch,
    private val copier: (String) -> Unit,
    private val io: CoroutineContext = Dispatchers.IO,
    private val copiedMillis: Long = SCAN_COPIED_MS,
) {
    var note by mutableStateOf<ScanLinkNote?>(null); private set
    private var timer: Job? = null
    /** Links being opened right now: a second click while the system is still starting the browser must not open a second page. */
    private val opening: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** The note for [origin], or null when the page has none for it. */
    fun noteFor(origin: String): String? = note?.takeIf { it.origin == origin }?.text

    /** Forget the note: the next action, or a new scan, starts clean. */
    fun clear() { timer?.cancel(); timer = null; note = null }

    /** Open [origin] in the default browser. If the system cannot, the link is copied instead and the note says so. */
    fun open(origin: String) {
        if (!opening.add(origin)) return
        clear()
        scope.launch {
            try {
                val launch = withContext(io) { runCatching { opener(origin) }.getOrDefault(BrowserLaunch.Failed) }
                val outcome = browserOutcome(launch)
                val copied = !outcome.copyLink || tryCopy(origin)
                // A launch that went through says nothing, and leaves alone whatever a newer action put up meanwhile.
                (if (copied) outcome.note else SCAN_NO_BROWSER_NO_COPY_NOTE)?.let { say(origin, it, expires = false) }
            } finally { opening.remove(origin) }
        }
    }

    /**
     * Copy [text] (the address by default) for [origin], and confirm it beside that address for a moment. A copy that did not
     * work is said too, and that note stays until the next action: nobody should miss that nothing is on the clipboard.
     */
    fun copy(origin: String, text: String = origin) {
        clear()
        if (tryCopy(text)) say(origin, SCAN_COPIED_NOTE, expires = true) else say(origin, SCAN_COPY_FAILED_NOTE, expires = false)
    }

    private fun tryCopy(text: String): Boolean = runCatching { copier(text) }.isSuccess

    private fun say(origin: String, text: String, expires: Boolean) {
        val mine = ScanLinkNote(origin, text)
        note = mine
        // Only this note is taken down: by then a newer one may be showing.
        if (expires) timer = scope.launch { delay(copiedMillis); if (note === mine) note = null }
    }
}

/**
 * The label a keyboard or screen-reader user hears for an address that opens on activation. The address is left out while
 * the showcase hides identities, exactly as [identity] hides it on screen.
 */
internal fun openLinkDescription(origin: String, showcase: Boolean): String =
    if (showcase) "Open in the browser" else "Open $origin in the browser"
