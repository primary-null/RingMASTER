package local.ringleader.desktop.web

/**
 * Who may try a login key, and when.
 *
 * Failures are counted per remote address, so one peer that guesses badly waits alone: it can no longer stop every other
 * browser from signing in, as the old single global counter let anyone do (network audit N-09). A small ceiling on all
 * failures together ([WebLimits.globalFailuresBeforeLock] in one [WebLimits.lockMs] window) still slows a guesser who spreads
 * its attempts over many addresses.
 *
 * What a lock does NOT stop is the live one-time link: it is 256 random bits that nobody can guess, so making it wait would
 * protect nothing, and it would let a peer that trips a lock keep the owner out. The caller checks that key first and asks the
 * gate only for any other key (the operator's fixed key, or a guess). Behind a reverse proxy every browser has the proxy's
 * address, so there the per-address count is one count for all; the proxy's own limits are the better defence.
 */
internal class LoginGate(private val limits: WebLimits, private val now: () -> Long = System::currentTimeMillis) {
    private class Record(var failures: Int = 0, var lockedUntil: Long = 0L, var lastSeen: Long = 0L)
    private val records = HashMap<String, Record>()
    private var windowStart = 0L
    private var windowFailures = 0
    private var everyoneLockedUntil = 0L

    /** Milliseconds [address] must wait before a key other than the live one-time link may be tried; 0 when it may try now. */
    @Synchronized fun waitMillis(address: String): Long {
        val t = now()
        return maxOf(0L, (records[address]?.lockedUntil ?: 0L) - t, everyoneLockedUntil - t)
    }

    /** One failed attempt from [address]. */
    @Synchronized fun failed(address: String) {
        val t = now()
        prune(t)
        val record = records.getOrPut(address) { Record() }
        record.lastSeen = t
        if (++record.failures >= limits.failuresBeforeLock) { record.failures = 0; record.lockedUntil = t + limits.lockMs }
        if (t - windowStart > limits.lockMs) { windowStart = t; windowFailures = 0 }
        if (++windowFailures >= limits.globalFailuresBeforeLock) { windowFailures = 0; windowStart = t; everyoneLockedUntil = t + limits.lockMs }
    }

    /** [address] signed in: its failures are forgotten. */
    @Synchronized fun succeeded(address: String) { records.remove(address) }

    /** Number of addresses remembered. */
    @get:Synchronized val remembered: Int get() = records.size

    /** Forgets quiet addresses, and the oldest ones when there are too many, so a flood from many addresses cannot grow memory. */
    private fun prune(t: Long) {
        if (records.size < MAX_ADDRESSES) return
        records.values.removeIf { it.lockedUntil <= t && t - it.lastSeen > limits.lockMs }
        while (records.size >= MAX_ADDRESSES) records.entries.minByOrNull { it.value.lastSeen }?.let { records.remove(it.key) } ?: break
    }

    private companion object { const val MAX_ADDRESSES = 1024 }
}
