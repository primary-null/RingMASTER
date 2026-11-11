package local.ringleader.desktop

/** Owns one process engine and a replaceable browser. Releases are scoped to a lease. */
internal class EngineSessions<E : Any, B : Any>(
    private val build: () -> E,
    private val open: (E) -> B,
    private val closeBrowser: (B) -> Unit,
    private val closeEngine: (E) -> Unit,
) {
    private val lock = Any()
    private val buildLock = Any()
    private var engine: E? = null
    private var browser: B? = null
    private var epoch = 0L
    private var closed = false

    fun begin(): Long = synchronized(lock) {
        check(!closed)
        clearBrowser()
        ++epoch
    }

    fun start(ticket: Long): B {
        synchronized(lock) { check(!closed && epoch == ticket) { "The local proof was closed." } }
        // Build once without blocking UI release. An abandoned build is still retained for reuse.
        synchronized(buildLock) {
            if (synchronized(lock) { engine == null && !closed }) {
                val built = build()
                synchronized(lock) {
                    if (closed) closeEngine(built) else engine = built
                }
            }
        }
        return synchronized(lock) {
            check(!closed && epoch == ticket) { "The local proof was closed." }
            browser ?: open(checkNotNull(engine)).also { browser = it }
        }
    }

    fun isCurrent(ticket: Long): Boolean = synchronized(lock) { !closed && epoch == ticket }

    fun release(ticket: Long? = null) = synchronized(lock) {
        if (ticket == null || epoch == ticket) {
            ++epoch
            clearBrowser()
        }
    }

    fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            ++epoch
            clearBrowser()
            engine?.let(closeEngine)
            engine = null
        }
    }

    private fun clearBrowser() {
        val old = browser
        browser = null
        old?.let(closeBrowser)
    }
}
