package local.ringleader.core

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/*
 * A TCP connect that tries every address of a name.
 *
 * The JDK's Socket, HttpsURLConnection and HttpClient resolve a name and use only its first address. A name with a dead first
 * address, or a service that listens on one family only (localhost is both 127.0.0.1 and ::1), is then unreachable by name although
 * it answers by literal. connectAny does what curl and the browsers do (RFC 8305, "Happy Eyeballs"): the addresses are interleaved
 * (the resolver's first family first, then the other, one by one), the next attempt starts when the previous one fails or has not
 * answered within 250 ms, and the first connection that succeeds wins; the others are closed. The winner is remembered
 * (NameLookup.noteConnected) so the next lookup of that name lists it first for the clients that cannot try the next address.
 *
 * The timeout is one deadline for the whole call, name lookup included.
 */

/** Stops a [connectAny] that is still running, from another thread, and closes the socket it returned. Used by callers that must be able to abort mid-connect or mid-read. */
class ConnectCancel {
    private val lock = Any()
    private val sockets = ArrayList<Socket>()
    @Volatile var cancelled = false
        private set

    fun cancel() {
        val open = synchronized(lock) { cancelled = true; sockets.toList().also { sockets.clear() } }
        open.forEach { runCatching { it.close() } }
    }

    /** Registers [socket] to be closed by [cancel]. False when this handle was cancelled already: the caller closes the socket itself. */
    internal fun track(socket: Socket): Boolean = synchronized(lock) { if (cancelled) false else { sockets += socket; true } }
}

/** The next attempt starts after this long when the previous one has neither connected nor failed (RFC 8305 recommends 250 ms). */
internal const val CONNECT_STAGGER_MILLIS = 250L
private const val MAX_ATTEMPTS = 8

private val attemptNumber = AtomicInteger()
private val attemptThreads = ThreadPoolExecutor(0, 32, 20, TimeUnit.SECONDS, SynchronousQueue<Runnable>()) { task ->
    Thread(null, task, "ringmaster-connect-${attemptNumber.incrementAndGet()}").apply { isDaemon = true }
}

/**
 * Opens a TCP connection to [host] (a name or an IP literal) on [port], waiting at most [timeoutMs] in all. Returns the connected
 * socket. Throws what a plain connect would throw: [UnknownHostException] (a [NameLookupTimedOutException] when the lookup ran out of
 * time), [ConnectException] when a host answered "refused", [SocketTimeoutException] when none answered in time, another
 * [IOException] otherwise. With several failures the most telling one is thrown (a refusal says the host is up) and the others
 * are attached as suppressed. A [cancel] handle lets another thread abort the call and close the socket it returned.
 */
fun connectAny(host: String, port: Int, timeoutMs: Int, cancel: ConnectCancel? = null): Socket =
    connectAnyWith(host, port, timeoutMs, cancel, NameLookup::resolveAll, ::connectSocket, CONNECT_STAGGER_MILLIS, NameLookup::noteConnected, attemptThreads)

/** [connectAny] with its parts passed in, so a test can script the lookup, each connection attempt and the clock of the stagger. */
internal fun connectAnyWith(
    host: String,
    port: Int,
    timeoutMs: Int,
    cancel: ConnectCancel?,
    resolve: (String, Long) -> List<InetAddress>,
    connector: (InetAddress, Int, Int, (Socket) -> Boolean) -> Socket,
    staggerMillis: Long,
    remember: (String, InetAddress) -> Unit,
    executor: Executor,
): Socket {
    require(port in 1..65535) { "port out of range: $port" }
    require(timeoutMs > 0) { "timeout must be positive" }
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
    fun leftMillis(): Int = ((deadline - System.nanoTime()) / 1_000_000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    val handle = cancel ?: ConnectCancel()
    val addresses = resolve(host, timeoutMs.toLong()).distinctBy { it.address.toList() }
    if (addresses.isEmpty()) throw UnknownHostException(host)
    val order = interleave(addresses).take(MAX_ATTEMPTS)
    // An IP literal has no name to remember.
    fun connected(address: InetAddress) { if (nameKey(host) != null) remember(host, address) }

    if (order.size == 1) {
        if (leftMillis() <= 0) throw SocketTimeoutException("Connect timed out")
        val socket = connector(order[0], port, leftMillis().coerceAtLeast(1), handle::track)
        connected(order[0])
        return socket
    }

    class Outcome(val address: InetAddress, val socket: Socket?, val error: Exception?)
    val results = LinkedBlockingQueue<Outcome>()
    val settled = AtomicBoolean(false)
    // The socket that won, set before [settled]: a worker that sees the race settled must never close the winner, its own included.
    val won = java.util.concurrent.atomic.AtomicReference<Socket?>(null)
    val attempts = Attempts()
    val register: (Socket) -> Boolean = { socket -> attempts.add(socket) && handle.track(socket) }
    val failures = ArrayList<Exception>()
    var started = 0
    var running = 0

    fun launch() {
        val address = order[started++]
        running++
        val task = Runnable {
            val outcome = try {
                Outcome(address, connector(address, port, leftMillis().coerceAtLeast(1), register), null)
            } catch (e: Exception) {
                Outcome(address, null, e)
            } catch (t: Throwable) {
                Outcome(address, null, IOException(t))
            }
            results.put(outcome)
            // A connection that finished after the race was decided must not stay open. The winner's own worker gets here
            // too, often just after the race was settled in its favour: it must leave its socket alone.
            if (outcome.socket != null && settled.get() && won.get() !== outcome.socket) runCatching { outcome.socket.close() }
        }
        try { executor.execute(task) } catch (e: RejectedExecutionException) { task.run() }
    }

    var winner: Outcome? = null
    var interrupted = false
    try {
        launch()
        while (winner == null && !handle.cancelled) {
            val left = deadline - System.nanoTime()
            if (left <= 0) break
            val wait = if (started < order.size) minOf(left, TimeUnit.MILLISECONDS.toNanos(staggerMillis)) else left
            val outcome = results.poll(wait, TimeUnit.NANOSECONDS)
            if (outcome == null) {
                if (started < order.size) launch()
                continue
            }
            running--
            if (outcome.socket != null) { winner = outcome; break }
            failures += outcome.error!!
            if (started < order.size) launch() else if (running == 0) break
        }
    } catch (e: InterruptedException) {
        interrupted = true
    } finally {
        won.set(winner?.socket)
        settled.set(true)
        attempts.closeAllExcept(winner?.socket)
        while (true) { val late = results.poll() ?: break; if (late.socket !== winner?.socket) late.socket?.let { runCatching { it.close() } } }
    }
    if (interrupted) { Thread.currentThread().interrupt(); throw InterruptedIOException("The connection attempt was interrupted.") }
    winner?.let { connected(it.address); return it.socket!! }
    if (handle.cancelled) throw SocketException("The connection was cancelled.")
    throw mostTelling(failures)
}

/** The resolver's first family first, then the other one, alternating (RFC 8305 section 4). */
internal fun interleave(addresses: List<InetAddress>): List<InetAddress> {
    if (addresses.size < 2) return addresses
    val firstIsV6 = addresses.first() is Inet6Address
    val same = addresses.filter { (it is Inet6Address) == firstIsV6 }
    val other = addresses.filter { (it is Inet6Address) != firstIsV6 }
    val out = ArrayList<InetAddress>(addresses.size)
    var i = 0
    while (i < same.size || i < other.size) {
        same.getOrNull(i)?.let(out::add)
        other.getOrNull(i)?.let(out::add)
        i++
    }
    return out
}

/** A refusal says the host is up and the port closed; a timeout says nothing answered; anything else is the first failure. No failure at all means the deadline ran out first. */
private fun mostTelling(failures: List<Exception>): IOException {
    val chosen = failures.firstOrNull { it is ConnectException && it.message.orEmpty().contains("refused", ignoreCase = true) }
        ?: failures.firstOrNull { it is SocketTimeoutException }
        ?: failures.firstOrNull()
        ?: return SocketTimeoutException("Connect timed out")
    val error = chosen as? IOException ?: IOException(chosen)
    failures.forEach { if (it !== chosen) error.addSuppressed(it) }
    return error
}

/** The sockets of one call's attempts: [closeAllExcept] closes the losers and refuses any that are registered afterwards. */
private class Attempts {
    private val lock = Any()
    private val sockets = ArrayList<Socket>()
    private var closed = false

    fun add(socket: Socket): Boolean = synchronized(lock) { if (closed) false else { sockets += socket; true } }

    fun closeAllExcept(keep: Socket?) {
        val open = synchronized(lock) { closed = true; sockets.toList().also { sockets.clear() } }
        open.forEach { if (it !== keep) runCatching { it.close() } }
    }
}

private fun connectSocket(address: InetAddress, port: Int, timeoutMs: Int, register: (Socket) -> Boolean): Socket {
    val socket = Socket()
    if (!register(socket)) { runCatching { socket.close() }; throw SocketException("The connection was cancelled.") }
    try {
        socket.connect(InetSocketAddress(address, port), timeoutMs)
        return socket
    } catch (t: Throwable) {
        runCatching { socket.close() }
        throw t
    }
}
