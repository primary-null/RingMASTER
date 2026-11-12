package local.ringleader.core

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A console the page holds. [fresh] is true when this lease opened a new session, so the page plays its reveal. */
class ConsoleLease internal constructor(
    val session: VncSession,
    val fresh: Boolean,
    internal val guest: ResourceId,
    private val era: Int,
    private val onRelease: (ConsoleLease, Boolean) -> Unit,
) {
    private val done = AtomicBoolean(false)
    internal fun sameEra(current: Int) = era == current

    /** The page is leaving. [keep] false closes the session now (Reconnect, a stopped guest, a failed session). */
    fun release(keep: Boolean = true) { if (done.compareAndSet(false, true)) onRelease(this, keep) }
}

internal class ConsoleKeeper(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val keepMillis: () -> Long,
    private val limit: Int = CONSOLE_KEEP_LIMIT,
    private val changed: (Set<ResourceId>) -> Unit,
) {
    private class Kept(val session: VncSession, val leftAt: Long)

    private val lock = Any()
    private val kept = LinkedHashMap<ResourceId, Kept>()
    private val outstanding = HashMap<ResourceId, MutableSet<ConsoleLease>>()
    private var era = 0
    private var armToken = 0
    private var expiryJob: Job? = null
    private var published: Set<ResourceId> = emptySet()
    private val publishLock = Any()

    /** A kept, still healthy session for [guest], taken out of the kept set and resumed. Never blocks and never opens anything. */
    fun take(guest: ResourceId): ConsoleLease? {
        val session = synchronized(lock) {
            if (outstanding[guest]?.isNotEmpty() == true) return null
            kept.remove(guest)?.session
        } ?: return null
        publish()
        arm()
        if (!healthy(session)) {
            session.close()
            return null
        }
        session.onUpdate = null
        session.onClosed = null
        session.resume()
        return track(guest, session, fresh = false)
    }

    /** Wraps a session the workspace just opened. */
    fun adopt(guest: ResourceId, session: VncSession): ConsoleLease = track(guest, session, fresh = true)

    /** Stopped, gone, or turned into a template. Closes only that guest's kept session. */
    fun closeGuest(guest: ResourceId) {
        val session = synchronized(lock) { kept.remove(guest)?.session }
        session?.let { runCatching { it.close() } }
        publish()
        arm()
    }

    /**
     * Disconnect, reconnect, expiry, app exit, or keeping turned off.
     * [sealOutstanding] makes a lease that is still on screen close on release, so a disconnect cannot keep the
     * session it just dropped.
     */
    fun closeAll(sealOutstanding: Boolean = false) {
        val sessions = synchronized(lock) {
            if (sealOutstanding) era++
            val all = kept.values.map { it.session }
            kept.clear()
            expiryJob?.cancel()
            expiryJob = null
            armToken++
            all
        }
        sessions.forEach { runCatching { it.close() } }
        publish()
    }

    /** The keep time changed. Due sessions close on the next check, which is at most a second away. */
    fun wake() = arm()

    private fun track(guest: ResourceId, session: VncSession, fresh: Boolean): ConsoleLease {
        val lease: ConsoleLease
        synchronized(lock) {
            lease = ConsoleLease(session, fresh, guest, era) { held, keep -> release(held, keep) }
            outstanding.getOrPut(guest) { linkedSetOf() }.add(lease)
        }
        return lease
    }

    private fun release(lease: ConsoleLease, keep: Boolean) {
        val session = lease.session
        val guest = lease.guest
        val sealed = synchronized(lock) {
            outstanding[guest]?.remove(lease)
            if (outstanding[guest].isNullOrEmpty()) outstanding.remove(guest)
            !lease.sameEra(era)
        }
        if (sealed || !keep || keepMillis() <= 0L || !healthy(session)) {
            session.close()
            return
        }
        // Release what the guest sees as held before the session stops asking for frames.
        session.releaseInput()
        session.pause()
        session.onUpdate = null
        session.onClosed = { _ -> dropIfKept(guest, session) }
        val closing = ArrayList<VncSession>()
        val stored = synchronized(lock) {
            if (!lease.sameEra(era) || keepMillis() <= 0L || !healthy(session)) false
            else {
                kept[guest]?.session?.let { if (it !== session) closing += it }
                kept[guest] = Kept(session, now())
                while (kept.size > limit) {
                    val oldest = kept.entries.minBy { it.value.leftAt }
                    closing += oldest.value.session
                    kept.remove(oldest.key)
                }
                true
            }
        }
        if (!stored) session.close()
        closing.forEach { runCatching { it.close() } }
        publish()
        arm()
    }

    private fun dropIfKept(guest: ResourceId, session: VncSession) {
        val removed = synchronized(lock) {
            val current = kept[guest]
            if (current != null && current.session === session) {
                kept.remove(guest)
                true
            } else false
        }
        if (removed) {
            publish()
            arm()
        }
    }

    private fun expireDue() {
        val millis = keepMillis()
        val closing = ArrayList<VncSession>()
        synchronized(lock) {
            if (millis <= 0L) {
                closing += kept.values.map { it.session }
                kept.clear()
            } else {
                val t = now()
                val entries = kept.entries.iterator()
                while (entries.hasNext()) {
                    val entry = entries.next()
                    if (entry.value.leftAt + millis <= t) {
                        closing += entry.value.session
                        entries.remove()
                    }
                }
            }
        }
        closing.forEach { runCatching { it.close() } }
        publish()
        arm()
    }

    /**
     * Sleeps until the earliest deadline, and wakes at least once a second so a kept session cannot outlive its time
     * by more than a second when the clock moves. [close] is never called while [lock] is held.
     */
    private fun arm() {
        val wait: Long
        val token: Int
        synchronized(lock) {
            expiryJob?.cancel()
            expiryJob = null
            token = ++armToken
            val millis = keepMillis()
            if (millis <= 0L || kept.isEmpty()) return
            wait = (kept.values.minOf { it.leftAt } + millis - now()).coerceAtLeast(0L)
        }
        val step = wait.coerceAtMost(1_000L)
        val job = scope.launch {
            delay(step)
            val mine = synchronized(lock) { token == armToken }
            if (!mine) return@launch
            val due = synchronized(lock) {
                val millis = keepMillis()
                millis <= 0L || kept.values.any { it.leftAt + millis <= now() }
            }
            if (due) expireDue() else arm()
        }
        synchronized(lock) {
            if (token != armToken) job.cancel() else expiryJob = job
        }
    }

    /**
     * Calls come from the UI thread, a session's reader thread and the expiry job. Reading the set and reporting it
     * happen under one lock of their own, so an older set can never be reported after a newer one.
     */
    private fun publish() = synchronized(publishLock) {
        val ids = synchronized(lock) { kept.keys.toSet() }
        if (ids != published) {
            published = ids
            changed(ids)
        }
    }

    private fun healthy(session: VncSession) = session.ready && !session.isClosed() && session.reason == null
}

const val CONSOLE_KEEP_LIMIT = 4
const val CONSOLE_KEEP_DEFAULT_MINUTES = 10
