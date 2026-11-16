package local.ringleader.core

import java.net.InetAddress
import java.net.UnknownHostException
import java.net.spi.InetAddressResolver
import java.net.spi.InetAddressResolver.LookupPolicy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The name-lookup hook ([RingResolver]) around scripted resolvers. Nothing here installs anything JVM-wide:
 * [NameResolverJvmTest] shows the same hook loading through the real service lookup, in child JVMs.
 */
class NameResolverTest {
    private val policy = LookupPolicy.of(LookupPolicy.IPV4 or LookupPolicy.IPV6 or LookupPolicy.IPV4_FIRST)

    private fun v4(host: String, a: Int, b: Int, c: Int, d: Int): InetAddress = InetAddress.getByAddress(host, byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))

    /** A resolver that records who asked and answers from [handler]. */
    private class Scripted(val handler: (String) -> List<InetAddress>) : InetAddressResolver {
        val calls = CopyOnWriteArrayList<String>()
        val threads = CopyOnWriteArrayList<Thread>()
        val policies = CopyOnWriteArrayList<Int>()
        override fun lookupByName(host: String, policy: LookupPolicy): Stream<InetAddress> {
            calls += host; threads += Thread.currentThread(); policies += policy.characteristics()
            return handler(host).stream()
        }
        override fun lookupByAddress(addr: ByteArray): String = throw UnknownHostException()
    }

    private fun millis(block: () -> Unit): Long { val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1_000_000 }

    // ---------------------------------------------------------------- the deadline

    @Test fun aSlowLookupIsAbandonedAtTheDeadlineWithTheDedicatedException() {
        val release = CountDownLatch(1)
        val inner = Scripted { host -> release.await(); listOf(v4(host, 192, 0, 2, 10)) }
        val resolver = RingResolver(inner, defaultDeadlineMillis = 300)
        try {
            val failure = assertFailsWith<NameLookupTimedOutException> { val took = millis { resolver.lookupByName("slow.example.test", policy) }; println("took $took ms") }
            assertEquals("slow.example.test", failure.host)
            assertEquals(300, failure.waitedMillis)
            assertTrue(UnknownHostException::class.java.isInstance(failure), "to every other caller it is an ordinary unknown host")
            val elapsed = millis { runCatching { resolver.lookupByName("slow2.example.test", policy) } }
            assertTrue(elapsed in 250..3000, "returned after $elapsed ms")
            assertEquals(2, resolver.stats().timeouts)
        } finally { release.countDown() }
    }

    @Test fun aLookupThatFinishesInTimeIsReturnedAsTheInnerResolverGaveIt() {
        val inner = Scripted { host -> Thread.sleep(80); listOf(v4(host, 192, 0, 2, 10), v4(host, 192, 0, 2, 11)) }
        val resolver = RingResolver(inner, defaultDeadlineMillis = 2000)
        val answers = resolver.lookupByName("pve.example.test", policy).toList()
        assertEquals(listOf("192.0.2.10", "192.0.2.11"), answers.map { it.hostAddress })
        assertEquals("pve.example.test", answers.first().hostName, "the address objects are the inner resolver's own")
        assertEquals(0, resolver.stats().timeouts)
    }

    @Test fun aCallerBoundReplacesTheDefaultAndGoesAwayAfterwards() {
        val release = CountDownLatch(1)
        val slowThenAnswer = Scripted { host -> release.await(2, TimeUnit.SECONDS); listOf(v4(host, 192, 0, 2, 10)) }
        val resolver = RingResolver(slowThenAnswer, defaultDeadlineMillis = 10_000)
        try {
            val lower = millis { assertFailsWith<NameLookupTimedOutException> { NameLookup.within(250) { resolver.lookupByName("slow.example.test", policy) } } }
            assertTrue(lower in 200..3000, "a caller that allows 250 ms waited $lower ms, not the 10 s default")
            assertNull(NameLookup.callerBound(), "the bound ends with the block")
            NameLookup.within(5_000) {
                assertEquals(5_000L, NameLookup.callerBound())
                NameLookup.within(300) { assertEquals(300L, NameLookup.callerBound()) }
                assertEquals(5_000L, NameLookup.callerBound(), "bounds nest")
            }
            release.countDown()
            val higher = Scripted { host -> Thread.sleep(500); listOf(v4(host, 192, 0, 2, 10)) }
            val patient = RingResolver(higher, defaultDeadlineMillis = 200)
            assertFailsWith<NameLookupTimedOutException> { patient.lookupByName("a.example.test", policy) }
            assertEquals(1, NameLookup.within(5_000) { patient.lookupByName("b.example.test", policy).toList().size }, "a caller may wait longer than the default")
        } finally { release.countDown() }
    }

    @Test fun anAnswerThatArrivesAfterTheCallerGaveUpIsKeptForAShortWhile() {
        val release = CountDownLatch(1)
        val offset = AtomicLong()
        val inner = Scripted { host -> release.await(); listOf(v4(host, 192, 0, 2, 10)) }
        val resolver = RingResolver(inner, defaultDeadlineMillis = 150, clock = { System.nanoTime() + offset.get() })
        assertFailsWith<NameLookupTimedOutException> { resolver.lookupByName("slow.example.test", policy) }
        release.countDown()
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (resolver.lookupByName("slow.example.test", policy).toList().isEmpty() && System.nanoTime() < until) Thread.sleep(10)
        assertEquals(1, inner.calls.size, "the retry was answered from what the abandoned lookup found")
        offset.addAndGet(TimeUnit.SECONDS.toNanos(60))
        resolver.lookupByName("slow.example.test", policy)
        assertEquals(2, inner.calls.size, "an old late answer is not served")
    }

    // ---------------------------------------------------------------- what never waits

    @Test fun literalsAndLocalhostNeverWaitAndNeverUseAWorker() {
        val never = CountDownLatch(1)
        val inner = Scripted { host -> if (host.contains("example")) { never.await(); emptyList() } else listOf(v4(host, 127, 0, 0, 1)) }
        val resolver = RingResolver(inner, defaultDeadlineMillis = 10_000)
        try {
            for (host in listOf("192.0.2.10", "::1", "[2001:db8::1]", "fe80::1%12", "2001:DB8::10", "localhost", "LOCALHOST", "Localhost")) {
                val took = millis { assertEquals(1, resolver.lookupByName(host, policy).toList().size, host) }
                assertTrue(took < 1500, "$host took $took ms")
            }
            assertEquals(0, resolver.stats().workersStarted, "no worker thread was needed")
            assertTrue(inner.threads.all { it === Thread.currentThread() }, "the inner resolver ran on the caller's own thread")
        } finally { never.countDown() }
    }

    /** Only the exact name is trusted to be answered by the hosts file. With a trailing dot some resolvers ask DNS, so it gets the deadline like any name. */
    @Test fun localhostWithATrailingDotIsAnOrdinaryNameAndGetsTheDeadline() {
        val release = CountDownLatch(1)
        val inner = Scripted { host -> if (host.endsWith(".")) { release.await(); emptyList() } else listOf(v4(host, 127, 0, 0, 1)) }
        val resolver = RingResolver(inner, defaultDeadlineMillis = 250)
        try {
            assertFailsWith<NameLookupTimedOutException> { resolver.lookupByName("localhost.", policy) }
            assertEquals(1, resolver.stats().workersStarted, "it went through a worker")
            assertEquals(1, resolver.lookupByName("localhost", policy).toList().size, "the exact name still answers at once")
        } finally { release.countDown() }
    }

    @Test fun theStartUpTouchIsBoundedAndItsThreadIsADaemon() {
        // ensureLoaded asks the JDK for a name on a daemon thread and joins it for 1.5 s at most: a lookup that hangs cannot hold up a start-up.
        val took = millis { NameLookup.ensureLoaded() }
        assertTrue(took < 3_000, "ensureLoaded took $took ms")
        Thread.getAllStackTraces().keys.filter { it.name == "ringmaster-resolver-touch" }.forEach { assertTrue(it.isDaemon, "the touch thread must not keep the JVM alive") }
    }

    @Test fun aBrokenDottedQuadIsAName() {
        // The JDK hands such text to the resolver, and Windows asks DNS for it: it must get the deadline like any name.
        val inner = Scripted { host -> Thread.sleep(2_000); listOf(v4(host, 192, 0, 2, 10)) }
        val resolver = RingResolver(inner, defaultDeadlineMillis = 200)
        for (broken in listOf("999.1.1.1", "192.0.002.010", "1.2.3")) {
            assertFailsWith<NameLookupTimedOutException>(broken) { resolver.lookupByName(broken, policy) }
        }
    }

    // ---------------------------------------------------------------- never worse than the JDK's own resolver

    @Test fun anUnknownHostFromTheInnerResolverIsPassedOnAsItIs() {
        val inner = Scripted { host -> throw UnknownHostException("$host: Name or service not known") }
        val resolver = RingResolver(inner)
        val e = assertFailsWith<UnknownHostException> { resolver.lookupByName("nope.example.test", policy) }
        assertEquals("nope.example.test: Name or service not known", e.message)
        assertFalse(e is NameLookupTimedOutException)
        assertEquals(0, resolver.stats().fallbacks)
        assertEquals(0, resolver.stats().timeouts)
    }

    @Test fun anotherFailureOfTheInnerResolverIsPassedOnAsItIs() {
        val boom = IllegalStateException("inner resolver failed")
        val resolver = RingResolver(Scripted { throw boom })
        assertSame(boom, assertFailsWith<IllegalStateException> { resolver.lookupByName("x.example.test", policy) })
        assertEquals(0, resolver.stats().fallbacks, "not the hook's failure, so nothing to fall back from")
    }

    @Test fun whenTheHookCannotStartAWorkerTheInnerResolversOwnAnswerIsUsed() {
        val inner = Scripted { host -> listOf(v4(host, 192, 0, 2, 10)) }
        val resolver = RingResolver(inner, workerStarter = { throw OutOfMemoryError("unable to create native thread") })
        assertEquals(listOf("192.0.2.10"), resolver.lookupByName("pve.example.test", policy).toList().map { it.hostAddress })
        assertEquals(1, resolver.stats().fallbacks)
        assertEquals(listOf("pve.example.test"), inner.calls.toList())
        // And the inner resolver's own exception is what a failing fallback gives.
        val missing = RingResolver(Scripted { host -> throw UnknownHostException(host) }, workerStarter = { throw IllegalStateException("no thread") })
        assertFailsWith<UnknownHostException> { missing.lookupByName("nope.example.test", policy) }
    }

    @Test fun workerThreadsAreDaemonsAndBoundedInNumber() {
        val release = CountDownLatch(1)
        val inner = Scripted { host -> if (host.startsWith("busy")) { release.await(); emptyList() } else listOf(v4(host, 192, 0, 2, 10)) }
        val resolver = RingResolver(inner, defaultDeadlineMillis = 5_000, maxWorkers = 2)
        val callers = Executors.newFixedThreadPool(3)
        try {
            val results = (1..3).map { i -> callers.submit<Int> { runCatching { resolver.lookupByName("busy$i.example.test", policy).toList().size }.getOrDefault(-1) } }
            // The third name finds both workers busy: the hook falls back to the inner resolver on that caller's thread instead of queuing or failing.
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while ((resolver.stats().fallbacks < 1 || inner.calls.size < 3) && System.nanoTime() < until) Thread.sleep(10)
            assertEquals(1, resolver.stats().fallbacks)
            assertEquals(3, inner.calls.size, "all three names reached the inner resolver")
            // The threads that ran this resolver's lookups: two workers (daemons), and the third lookup on a caller's own thread.
            val workers = inner.threads.filter { it.name.startsWith("ringmaster-resolver-") }.distinct()
            assertEquals(2, workers.size, "worker threads: ${workers.map { it.name }}")
            assertTrue(workers.all { it.isDaemon }, "worker threads must not keep the JVM alive")
            assertEquals(1, inner.threads.count { !it.name.startsWith("ringmaster-resolver-") }, "the third lookup fell back to the caller's thread")
            release.countDown()
            results.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally { release.countDown(); callers.shutdownNow() }
    }

    @Test fun lookupsOfOneNameShareOneWorker() {
        val release = CountDownLatch(1)
        val inner = Scripted { host -> release.await(); listOf(v4(host, 192, 0, 2, 10)) }
        val resolver = RingResolver(inner, defaultDeadlineMillis = 10_000)
        val callers = Executors.newFixedThreadPool(4)
        try {
            val results = (1..4).map { callers.submit<List<String>> { resolver.lookupByName("pve.example.test", policy).toList().map { it.hostAddress } } }
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (inner.calls.isEmpty() && System.nanoTime() < until) Thread.sleep(10)
            Thread.sleep(150)
            release.countDown()
            results.forEach { assertEquals(listOf("192.0.2.10"), it.get(10, TimeUnit.SECONDS)) }
            assertEquals(1, inner.calls.size, "four callers, one lookup")
            assertEquals(1, resolver.stats().workersStarted)
        } finally { release.countDown(); callers.shutdownNow() }
    }

    @Test fun aLookupThatCallsBackIntoTheHookDoesNotWaitForAnotherWorker() {
        // With one worker, a re-entrant lookup that needed a second worker would wait for itself.
        lateinit var resolver: RingResolver
        val inner = Scripted { host -> if (host == "outer.example.test") resolver.lookupByName("inner.example.test", policy).toList() else listOf(v4(host, 192, 0, 2, 10)) }
        resolver = RingResolver(inner, defaultDeadlineMillis = 5_000, maxWorkers = 1)
        val took = millis { assertEquals(1, resolver.lookupByName("outer.example.test", policy).toList().size) }
        assertTrue(took < 4_000, "took $took ms")
        assertEquals(listOf("outer.example.test", "inner.example.test"), inner.calls.toList())
    }

    @Test fun anInterruptedCallerStillGetsTheAnswerAndKeepsItsInterruptFlag() {
        val inner = Scripted { host -> Thread.sleep(250); listOf(v4(host, 192, 0, 2, 10)) }
        val resolver = RingResolver(inner, defaultDeadlineMillis = 5_000)
        Thread.currentThread().interrupt()
        try {
            assertEquals(1, resolver.lookupByName("pve.example.test", policy).toList().size, "the JDK's own lookup does not stop for an interrupt either")
            assertTrue(Thread.currentThread().isInterrupted, "the interrupt is still there for the caller to see")
        } finally { Thread.interrupted() }
    }

    @Test fun reverseLookupsGoStraightToTheInnerResolver() {
        val inner = object : InetAddressResolver {
            override fun lookupByName(host: String, policy: LookupPolicy): Stream<InetAddress> = Stream.empty()
            override fun lookupByAddress(addr: ByteArray): String = "reverse.example.test"
        }
        assertEquals("reverse.example.test", RingResolver(inner).lookupByAddress(byteArrayOf(192.toByte(), 0, 2, 10)))
    }

    // ---------------------------------------------------------------- the order of the answers

    @Test fun theAddressThatLastAcceptedAConnectionComesFirst() {
        val memory = ConnectMemory()
        val inner = Scripted { host -> listOf(v4(host, 192, 0, 2, 1), v4(host, 192, 0, 2, 2), v4(host, 192, 0, 2, 3)) }
        val resolver = RingResolver(inner, memory = memory)
        assertEquals(listOf("192.0.2.1", "192.0.2.2", "192.0.2.3"), resolver.lookupByName("pve.example.test", policy).toList().map { it.hostAddress }, "nothing remembered: the resolver's own order")
        // The name is filed in lower case without brackets or a trailing dot, so every spelling of it finds the entry.
        memory.remember("PVE.Example.Test.", byteArrayOf(192.toByte(), 0, 2, 3))
        assertEquals(listOf("192.0.2.3", "192.0.2.1", "192.0.2.2"), resolver.lookupByName("pve.example.test", policy).toList().map { it.hostAddress })
        assertEquals(listOf("192.0.2.3", "192.0.2.1", "192.0.2.2"), resolver.lookupByName("PVE.EXAMPLE.TEST", policy).toList().map { it.hostAddress })
        assertEquals(listOf("192.0.2.1", "192.0.2.2", "192.0.2.3"), resolver.lookupByName("other.example.test", policy).toList().map { it.hostAddress }, "another name is not affected")
    }

    @Test fun anAddressTheAnswerNoLongerHasIsIgnored() {
        val memory = ConnectMemory()
        memory.remember("pve.example.test", byteArrayOf(192.toByte(), 0, 2, 9))
        val resolver = RingResolver(Scripted { host -> listOf(v4(host, 192, 0, 2, 1), v4(host, 192, 0, 2, 2)) }, memory = memory)
        assertEquals(listOf("192.0.2.1", "192.0.2.2"), resolver.lookupByName("pve.example.test", policy).toList().map { it.hostAddress }, "the memory never invents an address")
    }

    @Test fun localhostIsOrderedWithoutAWorker() {
        val memory = ConnectMemory()
        val inner = Scripted { host -> listOf(v4(host, 127, 0, 0, 1), InetAddress.getByAddress(host, ByteArray(16).also { it[15] = 1 })) }
        val resolver = RingResolver(inner, memory = memory)
        memory.remember("localhost", ByteArray(16).also { it[15] = 1 })
        assertEquals(listOf("0:0:0:0:0:0:0:1", "127.0.0.1"), resolver.lookupByName("localhost", policy).toList().map { it.hostAddress })
        assertEquals(0, resolver.stats().workersStarted)
    }

    @Test fun theMemoryIsSmallBoundedAndForgets() {
        val offset = AtomicLong()
        val memory = ConnectMemory(clock = { System.nanoTime() + offset.get() }, capacity = 3, ttlNanos = TimeUnit.MINUTES.toNanos(5))
        for (i in 1..5) memory.remember("host$i.example.test", byteArrayOf(192.toByte(), 0, 2, i.toByte()))
        assertEquals(3, memory.size())
        assertNull(memory.lastGood("host1.example.test"), "the least recently used names go first")
        assertContentEquals(byteArrayOf(192.toByte(), 0, 2, 5), memory.lastGood("HOST5.example.test."))
        memory.remember("192.0.2.1", byteArrayOf(192.toByte(), 0, 2, 1)); memory.remember("[2001:db8::1]", ByteArray(16))
        assertEquals(3, memory.size(), "an IP literal has no name to remember")
        offset.addAndGet(TimeUnit.MINUTES.toNanos(6))
        assertNull(memory.lastGood("host5.example.test"), "an entry lapses after five minutes")
        memory.remember("again.example.test", byteArrayOf(1, 2, 3, 4)); memory.forget("again.example.test")
        assertNull(memory.lastGood("again.example.test"))
    }

    @Test fun aCallerThatConnectsByItselfGetsTheOrderedAnswerAndTheJvmsCacheIsNotInvolved() {
        val memory = ConnectMemory()
        val inner = Scripted { host -> listOf(v4(host, 192, 0, 2, 1), v4(host, 192, 0, 2, 2)) }
        val resolver = RingResolver(inner, memory = memory)
        val seen = LookupPolicy.of(LookupPolicy.IPV6 or LookupPolicy.IPV6_FIRST)
        resolver.lookupByName("warm.example.test", seen)
        memory.remember("pve.example.test", byteArrayOf(192.toByte(), 0, 2, 2))
        assertEquals(listOf("192.0.2.2", "192.0.2.1"), resolver.lookupAll("pve.example.test", 2_000).map { it.hostAddress })
        assertEquals(seen.characteristics(), inner.policies.last(), "it asks the way the JDK last asked, so the user's address-family flags still apply")
        assertFailsWith<UnknownHostException> { RingResolver(Scripted { emptyList() }).lookupAll("nothing.example.test", 1_000) }
    }

    // ---------------------------------------------------------------- the side channel

    @Test fun aGivenUpLookupCanBeToldFromAnUnknownNameByNameAfterTheSubclassIsLost() {
        val release = CountDownLatch(1)
        val timeouts = TimeoutMemory()
        val resolver = RingResolver(Scripted { host -> release.await(); listOf(v4(host, 192, 0, 2, 10)) }, defaultDeadlineMillis = 150, timeouts = timeouts)
        try {
            assertFailsWith<NameLookupTimedOutException> { resolver.lookupByName("Slow.Example.Test.", policy) }
            assertEquals(150L, timeouts.recent("slow.example.test"), "the name is filed in lower case without the trailing dot")
            assertNull(timeouts.recent("other.example.test"))
            assertNull(timeouts.recent("192.0.2.10"), "a literal has no name to remember")
        } finally { release.countDown() }
    }

    @Test fun theTimeoutMemoryIsBoundedAndExpires() {
        val offset = AtomicLong()
        val memory = TimeoutMemory(clock = { System.nanoTime() + offset.get() }, capacity = 3, ttlNanos = TimeUnit.SECONDS.toNanos(30))
        for (i in 1..5) memory.record("host$i.example.test", i * 100L)
        assertEquals(3, memory.size(), "a small bounded map")
        assertNull(memory.recent("host1.example.test"), "the oldest entries were dropped")
        assertEquals(500L, memory.recent("host5.example.test"))
        offset.addAndGet(TimeUnit.SECONDS.toNanos(31))
        assertNull(memory.recent("host5.example.test"), "entries expire")
        memory.record("again.example.test", 7)
        memory.clear("again.example.test")
        assertNull(memory.recent("again.example.test"))
    }

    @Test fun isLookupTimeoutReadsTheExceptionChainAndTheMemory() {
        val host = "side-channel-${System.nanoTime()}.example.test"
        val plain = UnknownHostException(host)
        assertFalse(NameLookup.isLookupTimeout(plain, host))
        assertTrue(NameLookup.isLookupTimeout(java.net.ConnectException("x").apply { initCause(NameLookupTimedOutException(host, 5000)) }, host), "the dedicated exception anywhere in the chain")
        NameLookup.timeouts.record(host, 5000)
        try {
            assertEquals(5000L, NameLookup.timedOutRecently(host))
            assertTrue(NameLookup.isLookupTimeout(plain, host), "what the JDK makes of a repeat")
            assertTrue(NameLookup.isLookupTimeout(java.net.ConnectException().apply { initCause(java.nio.channels.UnresolvedAddressException()) }, host), "what HttpClient makes of it")
            assertFalse(NameLookup.isLookupTimeout(java.net.ConnectException("Connection refused"), host), "a refused port is not a lookup problem")
            assertFalse(NameLookup.isLookupTimeout(plain, "another-${host}"), "only for the name that timed out")
        } finally { NameLookup.timeouts.clear(host) }
    }

    @Test fun theStatisticsHoldNumbersAndNoNames() {
        val resolver = RingResolver(Scripted { host -> listOf(v4(host, 192, 0, 2, 10)) })
        resolver.lookupByName("private-name.example.test", policy)
        assertEquals(NameLookupStats(lookups = 1, timeouts = 0, fallbacks = 0, workersStarted = 1), resolver.stats())
        assertFalse("private-name" in resolver.stats().toString())
    }

    /** The hook sees every name this computer looks up. It must not write one anywhere: no log, no console, no file, no trace. */
    @Test fun theHookNeverLogsOrStoresAName() {
        val forbidden = listOf("System.out", "System.err", "println(", "print(", "Logger", "java.util.logging", "printStackTrace", "Files.", "File(", "FileWriter", "FileOutputStream", "Trace.", "Telemetry")
        val source = java.io.File("src/main/kotlin/local/ringleader/core/NameResolver.kt")
        assertTrue(source.isFile, "Missing ${source.path}")
        val offenders = source.readLines(Charsets.UTF_8).withIndex().filter { (_, line) -> !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//") && forbidden.any { it in line } }
        assertTrue(offenders.isEmpty(), "NameResolver.kt must not log or store names: ${offenders.map { "line ${it.index + 1}: ${it.value.trim()}" }}")
    }
}
