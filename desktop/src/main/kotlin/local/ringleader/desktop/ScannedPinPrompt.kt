package local.ringleader.desktop

import local.ringleader.core.DIRECT_PVE_KIND
import local.ringleader.core.ManualForm
import local.ringleader.core.PDM_KIND
import local.ringleader.core.ProfileDesk
import local.ringleader.core.ScanHit
import local.ringleader.core.ScanKind

/** How long a certificate read from the network stays on offer for approval: five minutes, in nanoseconds. */
const val SCAN_OFFER_NANOS = 300_000_000_000L

/** The clock the offers are timed by, and how often the form looks at it. Tests replace both so that five minutes do not take five minutes. */
internal object ScanOfferClock {
    @Volatile var now: () -> Long = System::nanoTime
    @Volatile var tickMillis: Long = 1000
}

/**
 * Discovery evidence is an offer, never approval. Edits/cancel permanently invalidate it, and it lapses
 * [SCAN_OFFER_NANOS] after [created]: the moment the result was picked or read, which the caller passes in. The form that
 * holds the offer is rebuilt whenever the page or the session in view changes, so an offer that timed itself from its own
 * construction never lapsed (found 2026-11-16).
 */
class ScannedPinOffer(
    private val address: String,
    private val kind: String,
    private val pin: String?,
    created: Long? = null,
    private val clock: () -> Long = { ScanOfferClock.now() },
) {
    private val created = created ?: clock()
    private var valid = true
    fun invalidate() { valid = false }
    /** True once a certificate that was on offer is too old to approve. Never true for an offer without one. */
    fun expired(): Boolean = pin != null && valid && clock() - created !in 0..SCAN_OFFER_NANOS
    /** True while a certificate is on offer that is neither cancelled nor too old. */
    fun live(): Boolean = pin != null && valid && clock() - created in 0..SCAN_OFFER_NANOS
    /** How long ago the certificate was read, in nanoseconds. */
    fun ageNanos(): Long = (clock() - created).coerceAtLeast(0)
    /** What is left of the five minutes, in nanoseconds; zero or less once it has lapsed. */
    fun remainingNanos(): Long = SCAN_OFFER_NANOS - (clock() - created)
    fun current(address: String, kind: String): String? = pin.takeIf {
        valid && this.address == address && this.kind == kind && clock() - created in 0..SCAN_OFFER_NANOS
    }
}

/** "just now", "40 s ago", "2 min ago". */
internal fun offerAgeText(ageNanos: Long): String {
    val seconds = ageNanos / 1_000_000_000L
    return when { seconds < 5 -> "just now"; seconds < 60 -> "$seconds s ago"; else -> "${seconds / 60} min ago" }
}

/** What is left of the offer as minutes and seconds: "5:00", "3:12", "0:07". Never negative. */
internal fun offerRemainingText(remainingNanos: Long): String {
    val seconds = ((remainingNanos.coerceAtLeast(0) + 999_999_999L) / 1_000_000_000L).toInt()
    return "${seconds / 60}:${"%02d".format(seconds % 60)}"
}

/** The server kind a picked scan result is connected as. */
internal fun scanKindOf(hit: ScanHit): String = if (hit.kind == ScanKind.PDM) PDM_KIND else DIRECT_PVE_KIND

/**
 * What the Connect form starts with: what it already holds (an address typed since a pick is the person's own), and the picked
 * result only where it holds nothing. The window puts a pick into the form when it is made ([applyScanPick]).
 */
internal fun scanFormStart(form: ManualForm, scanned: ScanHit?): ManualForm =
    if (form.address.isNotBlank() || scanned == null) form else form.copy(address = scanned.origin, kind = scanKindOf(scanned))

/** A result picked on the Scan page becomes the form's address and kind at once; whatever is typed after it is the person's and is never replaced. */
internal fun applyScanPick(desk: ProfileDesk, hit: ScanHit) { desk.manualForm = desk.manualForm.copy(address = hit.origin, kind = scanKindOf(hit)) }
