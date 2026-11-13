package local.ringleader.core

import java.lang.ref.WeakReference

/** One guest on one server. Node is routing and is not part of the key. */
data class GuestKey(val origin: String, val kind: String, val vmid: Int)

/**
 * A foreground session's view of its own mutations, read by the scheduler before it claims a guest. Returns a short
 * reason when the session has a mutation for [key] in flight or unresolved, or any mutation request to that origin
 * still being sent (power, snapshot, create or storage), otherwise null. Called from a scheduler thread.
 */
fun interface ForegroundMutations {
    fun busy(key: GuestKey): String?
}

/**
 * Process-wide interlock between scheduled runs and foreground sessions.
 *
 * - A scheduled run claims its guest with [acquireScheduled] before its final state read and holds it through dispatch
 *   and the task follow. The claim fails while any registered foreground session reports the guest (or a send to the
 *   same origin) busy, or another scheduled run holds the guest.
 * - A foreground power or snapshot submission runs its "mark in flight" step through [foreground]; the step is skipped
 *   (and nothing is sent) while a scheduled run holds that guest. Checking and marking happen under one lock, so
 *   neither side can slip between the other's check and claim.
 * - Foreground sessions never block each other here; their existing per-session interlock is unchanged.
 */
class MutationLeases {
    private val lock = Any()
    private val providers = mutableListOf<WeakReference<ForegroundMutations>>()
    private val scheduled = mutableMapOf<GuestKey, String>()

    fun register(provider: ForegroundMutations) = synchronized(lock) {
        providers.removeAll { it.get() == null || it.get() === provider }
        providers += WeakReference(provider)
    }

    fun unregister(provider: ForegroundMutations) = synchronized(lock) {
        providers.removeAll { it.get() == null || it.get() === provider }
    }

    /** Null when [owner] now holds [key]; otherwise why it could not (nothing was claimed). */
    fun acquireScheduled(key: GuestKey, owner: String): String? = synchronized(lock) {
        scheduled[key]?.let { return if (it == owner) null else "Another scheduled action is running on this guest." }
        providers.removeAll { it.get() == null }
        providers.forEach { ref -> ref.get()?.busy(key)?.let { return it } }
        scheduled[key] = owner
        null
    }

    /** Owner-checked: a stale release never frees a newer holder. */
    fun releaseScheduled(key: GuestKey, owner: String) = synchronized(lock) {
        if (scheduled[key] == owner) scheduled.remove(key)
    }

    fun scheduledHolds(key: GuestKey): Boolean = synchronized(lock) { key in scheduled }

    /** Runs [mark] (which must only flag the foreground mutation as in flight) unless a scheduled run holds [key]. */
    fun <T> foreground(key: GuestKey, mark: () -> T): T? = synchronized(lock) {
        if (key in scheduled) null else mark()
    }

    companion object {
        /** The registry for this app run. */
        val shared = MutationLeases()
        const val SCHEDULED_REFUSAL = "A scheduled action is running on this guest. Nothing was sent; try again when it ends."
    }
}
