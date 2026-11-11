package local.ringleader.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import kotlin.test.*

class EngineSessionsTest {
    @Test fun abandonedBuildIsReusedAndStaleReleaseCannotCloseNewBrowser() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        var builds = 0
        var engineCloses = 0
        var browserCloses = 0
        val sessions = EngineSessions(
            build = { builds++; entered.countDown(); check(proceed.await(5, TimeUnit.SECONDS)); Any() },
            open = { _: Any -> Any() }, closeBrowser = { _: Any -> browserCloses++ },
            closeEngine = { _: Any -> engineCloses++ },
        )
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = sessions.begin()
            val abandoned = pool.submit<Boolean> { try { sessions.start(first); false } catch (_: IllegalStateException) { true } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            sessions.release(first)
            val second = sessions.begin()
            val next = pool.submit<Any> { sessions.start(second) }
            proceed.countDown()
            assertTrue(abandoned.get(5, TimeUnit.SECONDS))
            val browser = next.get(5, TimeUnit.SECONDS)
            assertEquals(1, builds)
            assertEquals(0, engineCloses)
            sessions.release(first)
            assertSame(browser, sessions.start(second))
            assertEquals(0, browserCloses)
            sessions.release(second)
            assertEquals(1, browserCloses)
            sessions.close()
            assertEquals(1, engineCloses)
        } finally { proceed.countDown(); pool.shutdownNow() }
    }

    @Test fun windowCloseDuringBuildDisposesEngineOnce() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        var closes = 0
        val sessions = EngineSessions(
            build = { entered.countDown(); check(proceed.await(5, TimeUnit.SECONDS)); Any() },
            open = { _: Any -> Any() }, closeBrowser = { _: Any -> }, closeEngine = { _: Any -> closes++ },
        )
        val pool = Executors.newSingleThreadExecutor()
        try {
            val ticket = sessions.begin()
            val job = pool.submit<Boolean> { try { sessions.start(ticket); false } catch (_: IllegalStateException) { true } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            sessions.close()
            proceed.countDown()
            assertTrue(job.get(5, TimeUnit.SECONDS))
            assertEquals(1, closes)
            assertFailsWith<IllegalStateException> { sessions.begin() }
        } finally { proceed.countDown(); pool.shutdownNow() }
    }
}
