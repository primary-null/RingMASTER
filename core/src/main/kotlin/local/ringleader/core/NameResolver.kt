package local.ringleader.core

import java.net.InetAddress
import java.net.UnknownHostException
import java.net.spi.InetAddressResolver
import java.net.spi.InetAddressResolver.LookupPolicy
import java.net.spi.InetAddressResolverProvider
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.stream.Stream

/*
 * Name lookups with a deadline, and a memory of which address of a name last accepted a connection.
 *
 * The JDK calls one InetAddressResolver for every name lookup made by HttpsURLConnection, HttpClient and Socket. This file
 * registers such a resolver (META-INF/services/java.net.spi.InetAddressResolverProvider in this module). It wraps the JDK's own
 * resolver and changes one thing: a lookup that takes longer than a bound (5 s unless the caller sets another) is abandoned. The
 * caller gets a NameLookupTimedOutException instead of waiting for the operating system's own, much longer, timeouts. The JDK
 * cannot cancel getaddrinfo, so the lookup finishes on a daemon worker thread and its answer is dropped, or kept for a few
 * seconds (LATE_ANSWER_SECONDS) so that a retry right after the JDK's own failure cache lapses is instant.
 *
 * The JDK's sockets, HttpsURLConnection and HttpClient try only the first address of a name. When a name has several, the hook
 * lists first the address that last accepted a TCP connection for it (NameLookup.noteConnected, told by connectAny), so a
 * client that cannot try the next address still finds the one that works. The JVM keeps an answer for 30 s, so the order takes
 * effect at the next lookup after that; connectAny therefore asks the hook directly and does not fill the JVM's cache.
 *
 * It must never make resolution worse than the JDK's own. Everything that goes wrong inside the hook (no worker thread, a bug,
 * a missing class) falls back to the JDK resolver's own answer or exception; IP literals and the name `localhost` never wait; the
 * hook does not log and keeps looked-up names only in small bounded maps in memory. RINGMASTER_PLAIN_RESOLVER=1 turns it off.
 */

/**
 * The hook's own "took too long" exception. It is a plain [UnknownHostException] to every caller that does not know it.
 * The JDK drops the subclass on most paths (a `Socket`, an `HttpsURLConnection` and an `HttpClient` all report an ordinary unknown
 * host or an `UnresolvedAddressException`, and a repeat within 10 s comes from the JDK's own failure cache), so a failure
 * can also be asked about by name with [NameLookup.timedOutRecently] or [NameLookup.isLookupTimeout].
 */
class NameLookupTimedOutException(val host: String, val waitedMillis: Long) :
    UnknownHostException("$host: looking up the name took longer than ${(waitedMillis + 999) / 1000} s")

enum class ResolverStatus {
    /** The JDK has not asked for this resolver (yet), or another provider came first on the class path. */
    NotLoaded,
    /** The hook is the JVM's resolver. */
    Active,
    /** RINGMASTER_PLAIN_RESOLVER=1 (or -Dringmaster.plainResolver=true): the JDK's own resolver is in use. */
    PlainBySwitch,
    /** The hook could not be built; the JDK's own resolver is in use. */
    Failed,
}

/** Counters of what the hook has done in this JVM. Numbers only, no names. */
data class NameLookupStats(val lookups: Long, val timeouts: Long, val fallbacks: Long, val workersStarted: Long)

object NameLookup {
    /**
     * How long a lookup may take unless the caller says otherwise. Above the slowest answers that still work (a VPN or mDNS
     * lookup takes 1 to 3 s), below the 10 s connect timeouts and below the operating systems' own multi-retry resolver timeouts.
     */
    const val DEFAULT_DEADLINE_MILLIS = 5_000L
    private const val MAX_BOUND_MILLIS = 120_000L

    /** Where the hook keeps what it learned. One of each per JVM; tests build their own around a [RingResolver]. */
    internal val timeouts = TimeoutMemory()
    internal val memory = ConnectMemory()

    @Volatile private var active: RingResolver? = null
    @Volatile private var current: ResolverStatus = ResolverStatus.NotLoaded
    @Volatile internal var failure: String? = null
    @Volatile private var touched = false

    val status: ResolverStatus get() = current
    fun stats(): NameLookupStats? = active?.stats()

    /** True when the support switch asks for the JDK's own resolver: RINGMASTER_PLAIN_RESOLVER=1 or -Dringmaster.plainResolver=true. */
    fun plainRequested(): Boolean {
        fun on(value: String?) = value != null && value.trim().lowercase() in setOf("1", "true", "yes", "on")
        return runCatching { on(System.getenv("RINGMASTER_PLAIN_RESOLVER")) }.getOrDefault(false) ||
            runCatching { on(System.getProperty("ringmaster.plainResolver")) }.getOrDefault(false)
    }

    internal fun markPlain() { current = ResolverStatus.PlainBySwitch; active = null }
    internal fun markFailed(error: Throwable) { current = ResolverStatus.Failed; failure = error.javaClass.name; active = null }

    /** Builds the hook around [inner] (the JDK's resolver, or a scripted one in tests) and makes it the JVM's. */
    internal fun wrap(inner: InetAddressResolver, deadlineMillis: Long = DEFAULT_DEADLINE_MILLIS): InetAddressResolver {
        val resolver = RingResolver(inner, deadlineMillis, memory = memory, timeouts = timeouts)
        active = resolver
        current = ResolverStatus.Active
        return resolver
    }

    /**
     * Makes the JDK load its resolver now, instead of at some later first lookup. Never throws and waits at most 1.5 s. The JDK builds
     * the resolver, and so calls the provider, when the first name is looked up; this looks up `localhost.` on a short daemon thread
     * and gives it that long, so a lookup that is slow for some reason cannot hold up a start-up. The trailing dot keeps the plain
     * name `localhost` out of the JVM's address cache (the hook files both under `localhost`, the JVM files them apart), so the order
     * the hook gives the plain name is not fixed for 30 s by this call.
     */
    fun ensureLoaded() {
        try {
            val touch = Thread({ runCatching { InetAddress.getByName("localhost.") } }, "ringmaster-resolver-touch")
            touch.isDaemon = true
            touch.start()
            touch.join(1_500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Throwable) {
        }
    }

    private val bound = ThreadLocal<Long?>()

    /**
     * Runs [block], and every JDK name lookup this thread makes inside it, with [millis] as the lookup deadline instead of
     * the 5 s default. A caller that has its own time limit (a connect timeout, a probe) passes it here so a slow lookup cannot
     * outlast it. A larger value than the default is allowed (the DNS tool lets the user wait up to 30 s).
     */
    fun <T> within(millis: Long, block: () -> T): T {
        val previous = bound.get()
        bound.set(millis.coerceIn(1, MAX_BOUND_MILLIS))
        try { return block() } finally { if (previous == null) bound.remove() else bound.set(previous) }
    }

    internal fun callerBound(): Long? = bound.get()

    /** Records that [address] accepted a TCP connection for [host], so the next lookup of that name lists it first. */
    fun noteConnected(host: String, address: InetAddress) { memory.remember(host, address.address) }

    /**
     * All addresses of [host] for a caller that connects by itself (connectAny), at most [boundMillis] for the lookup. A literal is
     * parsed, never looked up. A name goes to the hook directly, so the JVM's own address cache is neither read nor filled: an answer
     * that the memory has not ordered yet must not be kept for 30 s under the plain clients' feet. Without the hook (the switch, another
     * provider) this is [InetAddress.getAllByName].
     */
    internal fun resolveAll(host: String, boundMillis: Long): List<InetAddress> {
        val bare = IpLiteral.unbracket(host.trim())
        if (IpLiteral.isLiteral(bare.substringBefore('%'))) return InetAddress.getAllByName(bare).toList()
        // The JDK builds its resolver at the first name lookup. If nothing has asked yet, ask once; if another provider came first on
        // the class path the status stays NotLoaded and there is nothing more to wait for.
        if (current == ResolverStatus.NotLoaded && !touched) { touched = true; ensureLoaded() }
        val hook = active
        if (hook == null || current != ResolverStatus.Active) return InetAddress.getAllByName(bare).toList()
        return hook.lookupAll(bare, boundMillis)
    }

    /**
     * How long the most recent lookup of [host] ran before the hook gave up, if that was a few seconds ago; null otherwise.
     * Lets a caller tell "took too long" from "no such name" when all the JDK gave it was an unknown host.
     */
    fun timedOutRecently(host: String): Long? = timeouts.recent(host)

    /**
     * True when [error] means a name lookup ran out of time: it (or a cause) is a [NameLookupTimedOutException], or it is an unknown
     * host or unresolved address and the hook gave up on [host] a moment ago.
     */
    fun isLookupTimeout(error: Throwable, host: String?): Boolean {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        if (chain.any { it is NameLookupTimedOutException }) return true
        val unresolved = chain.any { it is UnknownHostException || it is java.nio.channels.UnresolvedAddressException }
        return unresolved && host != null && timeouts.recent(host) != null
    }
}

/** The name a lookup is filed under: lower case, no brackets, no trailing dot. A literal has no name, so null. */
internal fun nameKey(host: String): String? {
    val text = IpLiteral.unbracket(host.trim()).trimEnd('.').lowercase()
    if (text.isEmpty() || IpLiteral.isLiteral(text.substringBefore('%'))) return null
    return text
}

/**
 * The address that last accepted a TCP connection for a name. Small, bounded (128 names), in memory only. An entry lapses after
 * 5 minutes: long enough for the plain clients to pick it up at their next lookup, short enough that an address that has since
 * died does not stay first for long when nothing renews it.
 */
internal class ConnectMemory(
    private val clock: () -> Long = System::nanoTime,
    private val capacity: Int = 128,
    private val ttlNanos: Long = TimeUnit.MINUTES.toNanos(5),
) {
    private class Entry(val address: ByteArray, val at: Long)
    private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > capacity
    }

    @Synchronized fun remember(host: String, address: ByteArray) {
        val key = nameKey(host) ?: return
        map[key] = Entry(address.clone(), clock())
    }

    @Synchronized fun lastGood(host: String): ByteArray? {
        val key = nameKey(host) ?: return null
        val entry = map[key] ?: return null
        if (clock() - entry.at > ttlNanos) { map.remove(key); return null }
        return entry.address
    }

    @Synchronized fun forget(host: String) { nameKey(host)?.let(map::remove) }
    @Synchronized fun size(): Int = map.size
    @Synchronized fun clear() = map.clear()
}

/** Names whose lookup the hook abandoned recently. Small, bounded, in memory only, entries expire. */
internal class TimeoutMemory(
    private val clock: () -> Long = System::nanoTime,
    private val capacity: Int = 64,
    private val ttlNanos: Long = TimeUnit.SECONDS.toNanos(30),
) {
    private class Entry(val waitedMillis: Long, val at: Long)
    private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > capacity
    }

    @Synchronized fun record(host: String, waitedMillis: Long) { nameKey(host)?.let { map[it] = Entry(waitedMillis, clock()) } }
    @Synchronized fun clear(host: String) { nameKey(host)?.let(map::remove) }
    @Synchronized fun recent(host: String): Long? {
        val key = nameKey(host) ?: return null
        val entry = map[key] ?: return null
        if (clock() - entry.at > ttlNanos) { map.remove(key); return null }
        return entry.waitedMillis
    }
    @Synchronized fun size(): Int = map.size
}

/**
 * The hook. [inner] is the resolver underneath (the JDK's own). Nothing here throws except what [inner] throws, plus
 * [NameLookupTimedOutException]; any other trouble is answered by calling [inner] directly on the caller's thread.
 */
internal class RingResolver(
    private val inner: InetAddressResolver,
    private val defaultDeadlineMillis: Long = NameLookup.DEFAULT_DEADLINE_MILLIS,
    maxWorkers: Int = 16,
    private val clock: () -> Long = System::nanoTime,
    private val memory: ConnectMemory = ConnectMemory(),
    private val timeouts: TimeoutMemory = TimeoutMemory(),
    private val workerStarter: ((Runnable) -> Unit)? = null,
) : InetAddressResolver {
    private class Flight(val key: String) {
        val future = CompletableFuture<List<InetAddress>>()
        @Volatile var abandoned = false
    }

    /** The inner resolver failed with something that is not an unknown host; passed on as it is. */
    private class InnerFailure(val original: Throwable) : RuntimeException(original)
    /** The hook's own machinery failed (no thread, a bug); the caller falls back to [inner]. */
    private class MachineryFailure(cause: Throwable) : RuntimeException(cause)

    private val threadNumber = AtomicInteger()
    private val pool = ThreadPoolExecutor(0, maxWorkers, 20, TimeUnit.SECONDS, SynchronousQueue<Runnable>()) { task ->
        Thread(null, task, "ringmaster-resolver-${threadNumber.incrementAndGet()}").apply { isDaemon = true }
    }
    private val flights = ConcurrentHashMap<String, Flight>()
    private val late = object : LinkedHashMap<String, Pair<List<InetAddress>, Long>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<List<InetAddress>, Long>>?) = size > 32
    }
    private val inside = ThreadLocal<Boolean>()
    @Volatile private var platformPolicy: LookupPolicy? = null

    private val lookups = AtomicLong()
    private val timedOut = AtomicLong()
    private val fellBack = AtomicLong()
    private val started = AtomicLong()

    fun stats() = NameLookupStats(lookups.get(), timedOut.get(), fellBack.get(), started.get())

    override fun lookupByAddress(addr: ByteArray): String = inner.lookupByAddress(addr)

    override fun lookupByName(host: String, policy: LookupPolicy): Stream<InetAddress> {
        lookups.incrementAndGet()
        platformPolicy = policy
        return try {
            resolve(host, policy, NameLookup.callerBound()).stream()
        } catch (e: UnknownHostException) {
            throw e
        } catch (e: InnerFailure) {
            throw e.original
        } catch (t: Throwable) {
            // The hook itself failed. Whatever the JDK's resolver says is the answer.
            fellBack.incrementAndGet()
            inner.lookupByName(host, policy)
        }
    }

    /** For a caller that connects by itself: the addresses of [host], ordered, at most [boundMillis] for the lookup; the JVM's cache is not involved. */
    fun lookupAll(host: String, boundMillis: Long): List<InetAddress> {
        lookups.incrementAndGet()
        val policy = platformPolicy ?: LookupPolicy.of(LookupPolicy.IPV4 or LookupPolicy.IPV6 or LookupPolicy.IPV4_FIRST)
        val answers = try {
            resolve(host, policy, boundMillis)
        } catch (e: UnknownHostException) {
            throw e
        } catch (e: InnerFailure) {
            throw e.original
        } catch (t: Throwable) {
            fellBack.incrementAndGet()
            inner.lookupByName(host, policy).toList()
        }
        if (answers.isEmpty()) throw UnknownHostException(host)
        return answers
    }

    private fun resolve(host: String, policy: LookupPolicy, boundMillis: Long?): List<InetAddress> {
        val bare = IpLiteral.unbracket(host.trim())
        // A literal is never looked up, `localhost` is answered by the hosts file, and a lookup made by the hook's own thread or by
        // the inner resolver calling back in must not wait for another worker. Only the exact name `localhost` is trusted to be answered
        // locally: `localhost.` with a dot is an ordinary name to some resolvers, which then ask DNS, so it gets the deadline.
        if (IpLiteral.isLiteral(bare.substringBefore('%'))) return listFromInner(host, policy)
        val key = nameKey(bare) ?: return listFromInner(host, policy)
        if (bare.equals("localhost", ignoreCase = true) || inside.get() == true) return order(key, listFromInner(host, policy))
        val bound = boundMillis ?: defaultDeadlineMillis
        val deadline = clock() + TimeUnit.MILLISECONDS.toNanos(bound)
        val answers = await(join(key, host, policy), bare, deadline, bound)
        timeouts.clear(bare)
        return order(key, answers)
    }

    private fun listFromInner(host: String, policy: LookupPolicy): List<InetAddress> {
        val was = inside.get()
        inside.set(true)
        try { return inner.lookupByName(host, policy).toList() } finally { if (was == null) inside.remove() else inside.set(was) }
    }

    /** The lookup already running for this name and policy, or a new one on a worker thread. */
    private fun join(key: String, host: String, policy: LookupPolicy): Flight {
        val flightKey = "$key|${policy.characteristics()}"
        val fresh = synchronized(flights) {
            // The worker publishes late answers and retires its flight under this same lock.
            takeLate(key)?.let { answers -> return Flight(flightKey).apply { future.complete(answers) } }
            flights[flightKey]?.let { if (!it.future.isDone) return it }
            Flight(flightKey).also { flights[flightKey] = it }
        }
        val task = Runnable { runLookup(fresh, key, host, policy) }
        val start: (Runnable) -> Unit = workerStarter ?: { pool.execute(it) }
        try {
            started.incrementAndGet()
            start(task)
        } catch (t: Throwable) {
            flights.remove(flightKey, fresh)
            fresh.future.completeExceptionally(MachineryFailure(t))
        }
        return fresh
    }

    private fun runLookup(flight: Flight, key: String, host: String, policy: LookupPolicy) {
        inside.set(true)
        try {
            val answers = inner.lookupByName(host, policy).toList()
            synchronized(flights) {
                if (flight.abandoned) keepLate(key, answers)
                flight.future.complete(answers)
                flights.remove(flight.key, flight)
            }
        } catch (t: Throwable) {
            synchronized(flights) {
                flight.future.completeExceptionally(t)
                flights.remove(flight.key, flight)
            }
        } finally {
            inside.remove()
        }
    }

    /** Waits for [flight] until [deadline], ignoring interrupts (the JDK's own lookup does) and handing the flag back afterwards. */
    private fun await(flight: Flight, host: String, deadline: Long, boundMillis: Long): List<InetAddress> {
        var interrupted = false
        try {
            while (true) {
                val left = deadline - clock()
                if (left <= 0) {
                    val timeout = synchronized(flights) {
                        if (flight.future.isDone) null else giveUp(flight, host, boundMillis)
                    }
                    if (timeout != null) throw timeout
                }
                try {
                    return flight.future.get(left.coerceAtLeast(1), TimeUnit.NANOSECONDS)
                } catch (e: TimeoutException) {
                    continue
                } catch (e: InterruptedException) {
                    interrupted = true
                } catch (e: ExecutionException) {
                    val cause = e.cause ?: throw MachineryFailure(e)
                    throw when (cause) {
                        is UnknownHostException -> cause
                        is MachineryFailure -> cause
                        else -> InnerFailure(cause)
                    }
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun giveUp(flight: Flight, host: String, boundMillis: Long): NameLookupTimedOutException {
        flight.abandoned = true
        timedOut.incrementAndGet()
        timeouts.record(host, boundMillis)
        return NameLookupTimedOutException(host, boundMillis)
    }

    /** What a lookup that nobody waited for any more found, kept for a few seconds so a retry after the JDK's own failure cache lapses is instant. */
    private fun keepLate(key: String, answers: List<InetAddress>) {
        if (answers.isEmpty()) return
        synchronized(late) { late[key] = answers to clock() + TimeUnit.SECONDS.toNanos(LATE_ANSWER_SECONDS) }
    }

    private fun takeLate(key: String): List<InetAddress>? = synchronized(late) {
        val entry = late[key] ?: return null
        if (clock() > entry.second) { late.remove(key); null } else entry.first
    }

    /** The address that last accepted a connection first, the rest in the resolver's own order. An address the answer does not contain is ignored. */
    private fun order(key: String, answers: List<InetAddress>): List<InetAddress> {
        if (answers.size < 2) return answers
        val good = memory.lastGood(key) ?: return answers
        val at = answers.indexOfFirst { it.address.contentEquals(good) }
        if (at <= 0) return answers
        return ArrayList<InetAddress>(answers.size).also { out -> out += answers[at]; answers.forEachIndexed { i, a -> if (i != at) out += a } }
    }

    private companion object { const val LATE_ANSWER_SECONDS = 20L }
}

/**
 * Registered in META-INF/services/java.net.spi.InetAddressResolverProvider. The JDK builds it once, at the first name lookup,
 * and uses the first provider it finds on the class path. It never fails: on any trouble the JDK's own resolver is returned.
 */
class RingResolverProvider : InetAddressResolverProvider() {
    override fun get(configuration: Configuration): InetAddressResolver {
        val builtin = configuration.builtinResolver()
        return try {
            if (NameLookup.plainRequested()) { NameLookup.markPlain(); builtin } else NameLookup.wrap(builtin)
        } catch (t: Throwable) {
            runCatching { NameLookup.markFailed(t) }
            builtin
        }
    }

    override fun name(): String = "RingMASTER bounded resolver"
}
