package local.ringleader.core

import java.time.Duration
import java.time.Instant

/** The person's choice for the live ticker tray. Stored as a number; an unknown number reads as [Automatic]. */
enum class TickerMode(val stored: Int, val label: String) {
    Automatic(0, "Automatic"), Always(1, "Always open"), Off(2, "Off");

    companion object { fun fromStored(value: Int?): TickerMode = entries.firstOrNull { it.stored == value } ?: Automatic }
}

/**
 * Whether the tray is open, decided from the clock it is handed, so a test clock drives it. Nothing here runs by itself:
 * the caller feeds it cues and the pointer or keyboard state, and asks [settlesAt] when to look again.
 *
 * Automatic: closed (a slim pill) while things are calm; a cue opens it for [settle]; holding it (pointer over it, focus
 * inside it) keeps it open and, on letting go, for [linger] more; a pin keeps it open until unpinned; dismissing hides it
 * until [restore]. Always: open. Off: not shown at all. [collapse] (Escape) closes it whatever the mode, until the next cue,
 * hold or [open].
 */
class TickerPresence(private val settle: Duration = Duration.ofSeconds(7), private val linger: Duration = Duration.ofSeconds(3)) {
    private var until: Instant? = null
    private var holding = false
    private var collapsed = false
    var pinned = false
    var dismissed = false
        private set

    /** Something worth showing started: open for [settle] from [now] (a later cue extends it, never shortens it). */
    fun cue(now: Instant) {
        if (dismissed) return
        collapsed = false
        extend(now.plus(settle))
    }

    /** A click on the pill: open it the way a cue does. */
    fun open(now: Instant) = cue(now)

    /** The pointer is over the tray or the keyboard is inside it ([on]), or has just left. */
    fun hold(on: Boolean, now: Instant) {
        if (on) collapsed = false
        if (holding && !on) extend(now.plus(linger))
        holding = on
    }

    /** Escape: closed now, whatever kept it open, until something opens it again. */
    fun collapse() { collapsed = true; pinned = false; until = null; holding = false }

    fun dismiss() { dismissed = true; pinned = false; until = null; holding = false; collapsed = false }
    fun restore() { dismissed = false }

    fun visible(mode: TickerMode, hasTiles: Boolean): Boolean = mode != TickerMode.Off && !dismissed && hasTiles

    fun expanded(mode: TickerMode, now: Instant): Boolean = mode != TickerMode.Off && !dismissed && !collapsed &&
        (mode == TickerMode.Always || pinned || holding || until?.isAfter(now) == true)

    /** When an automatic opening ends, or null when nothing is waiting to end (pinned, held, always open, or already closed). */
    fun settlesAt(mode: TickerMode, now: Instant): Instant? =
        if (mode != TickerMode.Automatic || pinned || holding || dismissed || collapsed) null else until?.takeIf { it.isAfter(now) }

    private fun extend(end: Instant) { until = until?.let { if (it.isAfter(end)) it else end } ?: end }
}
