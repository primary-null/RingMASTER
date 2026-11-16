package local.ringleader.core

import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import local.ringleader.core.tools.PortOutcome
import local.ringleader.core.tools.connectPort
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * [connectAny]: every address of a name is tried, IPv6 and IPv4 interleaved, the first connection that succeeds wins and is
 * remembered, one deadline covers lookup and connections. The scripted tests run the real algorithm with scripted lookups and
 * connection attempts (nothing is contacted); the last ones use real loopback sockets.
 */
class ConnectAnyTest {
    private val host = "pve.example.test"
    private fun v4(last: Int): InetAddress = InetAddress.getByAddress(host, byteArrayOf(192.toByte(), 0, 2, last.toByte()))
    private fun v6(last: Int): InetAddress = InetAddress.getByAddress(host, ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[2] = 0x0d; it[3] = 0xb8.toByte(); it[15] = last.toByte() })

    private sealed interface Step {
        val delayMillis: Long
        class Connects(override val delayMillis: Long = 0) : Step
        class Fails(override val delayMillis: Long = 0, val error: () -> Exception) : Step
    }
    private fun refused() = ConnectException("Connection refused: getsockopt")
    private fun timedOut() = SocketTimeoutException("Connect timed out")

    private class Run(steps: Map<InetAddress, Step>) {
        val sockets = CopyOnWriteArrayList<Pair<InetAddress, Socket>>()
        val threads = CopyOnWriteArrayList<Thread>()
        val attempted = CopyOnWriteArrayList<InetAddress>()
        val connector: (InetAddress, Int, Int, (Socket) -> Boolean) -> Socket = { address, _, timeoutMs, register ->
            attempted += address
            threads += Thread.currentThread()
            val step = steps.getValue(address)
            val socket = Socket()
            sockets += address to socket
            if (!register(socket)) { socket.close(); throw SocketException("The connection was cancelled.") }
            // A pending connect ends when its socket is closed; wait the way one does.
            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(minOf(step.delayMillis, timeoutMs.toLong()))
            while (System.nanoTime() < until) { if (socket.isClosed) throw SocketException("Socket closed"); Thread.sleep(5) }
            if (step.delayMillis > timeoutMs) { socket.close(); throw SocketTimeoutException("Connect timed out") }
            when (step) {
                is Step.Fails -> { socket.close(); throw step.error() }
                is Step.Connects -> socket
            }
        }
        fun socketOf(address: InetAddress) = sockets.first { it.first == address }.second
    }

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "connect-any-test").apply { isDaemon = true } }
    private val remembered = CopyOnWriteArrayList<Pair<String, InetAddress>>()

    private fun call(run: Run, addresses: List<InetAddress>, timeoutMs: Int = 3000, stagger: Long = 100, cancel: ConnectCancel? = null, lookup: (String, Long) -> List<InetAddress> = { _, _ -> addresses }, name: String = host): Socket =
        connectAnyWith(name, 8006, timeoutMs, cancel, lookup, run.connector, stagger, { h, a -> remembered += h to a }, pool)

    private fun millis(block: () -> Unit): Long { val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1_000_000 }

    private fun awaitClosed(socket: Socket, what: String) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!socket.isClosed && System.nanoTime() < until) Thread.sleep(10)
        assertTrue(socket.isClosed, "$what must be closed")
    }

    // ---------------------------------------------------------------- order

    @Test fun familiesAreInterleavedStartingWithTheResolversFirst() {
        fun names(list: List<InetAddress>) = list.map { (if (it is Inet6Address) "6" else "4") + it.address.last().toInt() }
        assertEquals(listOf("41", "61", "42", "62"), names(interleave(listOf(v4(1), v4(2), v6(1), v6(2)))))
        assertEquals(listOf("61", "41", "62", "42"), names(interleave(listOf(v6(1), v6(2), v4(1), v4(2)))))
        assertEquals(listOf("41", "61", "42", "43"), names(interleave(listOf(v4(1), v4(2), v4(3), v6(1)))))
        assertEquals(listOf("61", "62"), names(interleave(listOf(v6(1), v6(2)))))
        assertEquals(emptyList(), interleave(emptyList()))
    }

    @Test fun theFirstAddressThatAnswersWinsAndIsRemembered() {
        val run = Run(mapOf(v4(1) to Step.Fails(0, ::refused), v6(1) to Step.Connects(0)))
        val socket = call(run, listOf(v4(1), v6(1)))
        assertSame(run.socketOf(v6(1)), socket)
        assertEquals(listOf(host to v6(1)), remembered.toList())
        assertFalse(socket.isClosed)
        socket.close()
    }

    @Test fun theNextAttemptStartsAtOnceWhenThePreviousOneFails() {
        val run = Run(mapOf(v4(1) to Step.Fails(40, ::refused), v6(1) to Step.Connects(0)))
        val took = millis { call(run, listOf(v4(1), v6(1)), stagger = 5_000).close() }
        assertTrue(took < 2_000, "took $took ms although the stagger was 5 s")
    }

    @Test fun aSlowFirstAttemptDoesNotHoldBackTheNextAndTheLoserIsClosed() {
        val run = Run(mapOf(v4(1) to Step.Connects(1_500), v6(1) to Step.Connects(30)))
        var socket: Socket? = null
        val took = millis { socket = call(run, listOf(v4(1), v6(1)), stagger = 100) }
        assertTrue(took < 1_200, "took $took ms")
        assertSame(run.socketOf(v6(1)), socket)
        awaitClosed(run.socketOf(v4(1)), "the attempt that lost the race")
        assertFalse(socket!!.isClosed)
        assertEquals(listOf(host to v6(1)), remembered.toList())
        socket!!.close()
    }

    @Test fun aSingleAddressIsConnectedOnTheCallersOwnThread() {
        val run = Run(mapOf(v4(1) to Step.Connects(0)))
        call(run, listOf(v4(1))).close()
        assertEquals(listOf(Thread.currentThread()), run.threads.toList(), "no thread hop for the common case")
    }

    @Test fun nothingIsRememberedForAnIpLiteralAndDuplicateAddressesCountOnce() {
        val run = Run(mapOf(v4(1) to Step.Connects(0)))
        call(run, listOf(v4(1), v4(1)), name = "192.0.2.1").close()
        assertEquals(1, run.attempted.size)
        assertTrue(remembered.isEmpty(), "a literal has no name to remember")
    }

    @Test fun atMostEightAddressesAreTried() {
        val addresses = (1..12).map { v4(it) }
        val run = Run(addresses.associateWith { Step.Fails(0, ::refused) })
        assertFailsWith<ConnectException> { call(run, addresses) }
        assertEquals(8, run.attempted.size)
    }

    // ---------------------------------------------------------------- time

    @Test fun oneDeadlineCoversTheWholeCallAndEverySocketIsClosedAfterwards() {
        val run = Run(mapOf(v4(1) to Step.Connects(10_000), v6(1) to Step.Connects(10_000)))
        val took = millis { assertFailsWith<SocketTimeoutException> { call(run, listOf(v4(1), v6(1)), timeoutMs = 500, stagger = 100) } }
        assertTrue(took in 400..2_000, "took $took ms for a 500 ms deadline")
        run.sockets.forEach { awaitClosed(it.second, "an attempt that did not finish") }
    }

    @Test fun theLookupCountsAgainstTheSameDeadline() {
        val run = Run(mapOf(v4(1) to Step.Connects(0)))
        val took = millis {
            assertFailsWith<SocketTimeoutException> { call(run, listOf(v4(1)), timeoutMs = 300, lookup = { _, _ -> Thread.sleep(400); listOf(v4(1)) }) }
        }
        assertTrue(took in 350..2_000, "took $took ms")
        assertTrue(run.attempted.isEmpty(), "no connection was started once the time was gone")
    }

    @Test fun theLookupIsToldTheTimeTheCallerHas() {
        var told = 0L
        call(Run(mapOf(v4(1) to Step.Connects(0))), listOf(v4(1)), timeoutMs = 1234, lookup = { _, bound -> told = bound; listOf(v4(1)) }).close()
        assertEquals(1234L, told)
    }

    // ---------------------------------------------------------------- what is thrown

    @Test fun aRefusalIsThePreferredFailureAndTheOthersAreKept() {
        val run = Run(mapOf(v4(1) to Step.Fails(0, ::timedOut), v6(1) to Step.Fails(0, ::refused)))
        val e = assertFailsWith<ConnectException> { call(run, listOf(v4(1), v6(1))) }
        assertTrue("refused" in e.message.orEmpty())
        assertEquals(1, e.suppressed.size)
        assertTrue(e.suppressed.single() is SocketTimeoutException)
    }

    @Test fun whenNothingAnswersTheTimeoutIsThrownRatherThanARoutingError() {
        val run = Run(mapOf(v4(1) to Step.Fails(0) { NoRouteToHostException("No route to host") }, v6(1) to Step.Fails(0, ::timedOut)))
        assertFailsWith<SocketTimeoutException> { call(run, listOf(v4(1), v6(1))) }
        val one = Run(mapOf(v4(1) to Step.Fails(0) { NoRouteToHostException("No route to host") }, v6(1) to Step.Fails(0) { SocketException("Network is unreachable") }))
        assertFailsWith<NoRouteToHostException> { call(one, listOf(v4(1), v6(1))) }
    }

    @Test fun aSingleAddressFailsExactlyAsAPlainConnectWould() {
        assertFailsWith<ConnectException> { call(Run(mapOf(v4(1) to Step.Fails(0, ::refused))), listOf(v4(1))) }
        assertFailsWith<SocketTimeoutException> { call(Run(mapOf(v4(1) to Step.Fails(0, ::timedOut))), listOf(v4(1))) }
    }

    @Test fun anUnknownNameAndAnEmptyAnswerAreUnknownHosts() {
        val run = Run(emptyMap())
        assertFailsWith<UnknownHostException> { call(run, emptyList(), lookup = { h, _ -> throw UnknownHostException(h) }) }
        assertFailsWith<UnknownHostException> { call(run, emptyList()) }
        val timedOut = assertFailsWith<NameLookupTimedOutException> { call(run, emptyList(), lookup = { h, bound -> throw NameLookupTimedOutException(h, bound) }, timeoutMs = 700) }
        assertEquals(700, timedOut.waitedMillis)
    }

    @Test fun cancellingStopsTheCallAndClosesEverySocket() {
        val run = Run(mapOf(v4(1) to Step.Connects(10_000), v6(1) to Step.Connects(10_000)))
        val cancel = ConnectCancel()
        var failure: Throwable? = null
        val caller = thread { try { call(run, listOf(v4(1), v6(1)), timeoutMs = 20_000, stagger = 50, cancel = cancel) } catch (t: Throwable) { failure = t } }
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (run.sockets.size < 2 && System.nanoTime() < until) Thread.sleep(10)
        val took = millis { cancel.cancel(); caller.join(5_000) }
        assertTrue(!caller.isAlive && took < 3_000, "the call ended ${took} ms after the cancel")
        assertTrue(failure is SocketException, "$failure")
        run.sockets.forEach { awaitClosed(it.second, "a socket of a cancelled call") }
    }

    @Test fun aCallThatWasCancelledBeforeItStartedConnectsNothing() {
        val cancel = ConnectCancel().also { it.cancel() }
        val run = Run(mapOf(v4(1) to Step.Connects(0), v6(1) to Step.Connects(0)))
        assertFailsWith<SocketException> { call(run, listOf(v4(1), v6(1)), cancel = cancel) }
        run.sockets.forEach { awaitClosed(it.second, "a socket of a cancelled call") }
    }

    // ---------------------------------------------------------------- real sockets on this computer's loopback

    @Test fun aListeningLoopbackPortIsReachedByLiteralAndByName() {
        ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).use { listener ->
            thread(isDaemon = true) { while (true) { try { listener.accept().close() } catch (_: Exception) { break } } }
            connectAny("127.0.0.1", listener.localPort, 2_000).use { assertTrue(it.isConnected) }
            try {
                connectAny("localhost", listener.localPort, 2_000).use { assertTrue(it.isConnected && it.inetAddress is Inet4Address) }
            } finally { NameLookup.memory.forget("localhost") }
        }
    }

    @Test fun aClosedPortIsARefusal() {
        val closed = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val e = assertFailsWith<ConnectException> { connectAny("127.0.0.1", closed, 2_000) }
        assertTrue(e.message.orEmpty().contains("refused", ignoreCase = true), e.message)
        assertTrue(connectPort("127.0.0.1", closed, 2_000) is PortOutcome.Closed)
    }

    /** Audit finding 13: `localhost` is [127.0.0.1, ::1] with the JDK default, and a plain connect stops at the first. */
    @Test fun localhostReachesAServiceThatListensOnTheSecondFamilyOnly() {
        val all = InetAddress.getAllByName("localhost")
        assumeTrue(all.size >= 2 && all[0] is Inet4Address && all.any { it is Inet6Address }, "localhost is not dual stack here: ${all.toList()}")
        val v6Only = runCatching { ServerSocket(0, 5, InetAddress.getByName("::1")) }.getOrNull()
        assumeTrue(v6Only != null, "no IPv6 loopback here")
        try {
            thread(isDaemon = true) { while (true) { try { v6Only!!.accept().close() } catch (_: Exception) { break } } }
            assertTrue(connectPort("::1", v6Only!!.localPort, 1000) is PortOutcome.Open, "sanity: the port is open on ::1")
            assertTrue(connectPort("localhost", v6Only.localPort, 1000) is PortOutcome.Open, "localhost has ::1 as its second address and the port is open there")
            connectAny("localhost", v6Only.localPort, 1000).use { assertTrue(it.inetAddress is Inet6Address, "the connection is to ${it.inetAddress}") }
        } finally {
            v6Only?.close()
            NameLookup.memory.forget("localhost")
        }
    }
}
