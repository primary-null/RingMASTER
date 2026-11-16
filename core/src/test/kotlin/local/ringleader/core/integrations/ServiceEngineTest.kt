package local.ringleader.core.integrations

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Polling on a virtual clock: interest, intervals with jitter, back-off, Retry-After, the kill switch and the latches. */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceEngineTest {
    private val template = ServiceTemplateParser.parse("t.json", ServiceTemplateParserTest.GOOD)
    private val variant = template.variants.single()
    private val params = mapOf("pick" to listOf("abc"))
    private val good = """{"total": 10, "part": 4, "state": "on", "h": [], "list": []}"""

    private class Fake(var outcome: (String) -> Pair<String?, ServiceProblem?>) : LinkFetcher {
        val calls = mutableListOf<String>(); val logouts = AtomicInteger(); val inFlight = AtomicInteger(); var maxInFlight = 0
        lateinit var now: () -> Instant
        override fun fetch(requestId: String, params: Map<String, List<String>>): Fetched {
            maxInFlight = maxOf(maxInFlight, inFlight.incrementAndGet())
            try {
                calls += requestId
                val (body, problem) = outcome(requestId)
                return Fetched(body?.let { ResponseValue.Json(Json.parseToJsonElement(it)) }, if (problem == null) 200 else problem.status, problem, null, now())
            } finally { inFlight.decrementAndGet() }
        }
        override fun logout(): Boolean { logouts.incrementAndGet(); return true }
    }

    private fun TestScope.runner(fake: Fake, onFirst: () -> Unit = {}): ServiceRunner {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = { Instant.ofEpochMilli(testScheduler.currentTime) }
        fake.now = clock
        return ServiceRunner("l1", template, variant, fake, params, backgroundScope, clock, dispatcher, Random(7), onFirstSuccess = onFirst).start()
    }

    private fun TestScope.seconds(n: Long) { advanceTimeBy(n * 1000); runCurrent() }

    @Test fun nothingPollsWhileNothingIsVisible() = runTest {
        val fake = Fake { good to null }
        val r = runner(fake)
        seconds(3600)
        assertTrue(fake.calls.isEmpty())
        r.setInterest(Interest.Compact); seconds(1)
        assertEquals(listOf("stats"), fake.calls, "the pin needs only its own request")
        r.setInterest(Interest.Full); seconds(1)
        assertEquals(listOf("stats", "items"), fake.calls)
        r.setInterest(Interest.None); val before = fake.calls.size; seconds(3600)
        assertEquals(before, fake.calls.size, "hidden again: nothing more")
    }

    @Test fun eachRequestKeepsItsIntervalWithJitter() = runTest {
        val fake = Fake { good to null }
        val r = runner(fake); r.setInterest(Interest.Compact); seconds(1)
        seconds(25); assertEquals(1, fake.calls.size, "not before 27 s (30 s minus 10 %)")
        seconds(10); assertEquals(2, fake.calls.size, "by 34 s (30 s plus 10 %)")
        seconds(600); assertTrue(fake.calls.size in 19..25, "about one every 30 s: ${fake.calls.size}")
        assertEquals(1, fake.maxInFlight, "one request in flight per link")
    }

    @Test fun failuresBackOffDoublingUpToTenMinutes() = runTest {
        val down = ServiceProblem(ProblemKind.NotReachable, "down")
        val fake = Fake { null to down }
        val r = runner(fake); r.setInterest(Interest.Compact); seconds(1)
        val times = mutableListOf<Long>()
        var last = 1
        repeat(3 * 3600) { seconds(1); if (fake.calls.size > last) { last = fake.calls.size; times += testScheduler.currentTime / 1000 } }
        val gaps = listOf(1L) .let { (listOf(1L) + times).zipWithNext { a, b -> b - a } }
        assertTrue(gaps[0] in 54..66, "first back-off about 60 s: $gaps")
        assertTrue(gaps[1] in 108..132, "then about 120 s: $gaps")
        assertTrue(gaps.drop(4).all { it in 540..660 }, "capped at about 10 min: $gaps")
        assertEquals(PanelStatus.Unreachable, r.state.value.status)
    }

    @Test fun retryAfterIsHonoured() = runTest {
        var limited = true
        val fake = Fake { if (limited) null to ServiceProblem(ProblemKind.RateLimited, "wait", 429, 900) else good to null }
        val r = runner(fake); r.setInterest(Interest.Compact); seconds(1)
        limited = false
        seconds(800); assertEquals(1, fake.calls.size, "nothing before Retry-After minus jitter")
        seconds(200); assertTrue(fake.calls.size >= 2, "asked again once Retry-After has passed")
        assertEquals(PanelStatus.Ok, r.state.value.status)
    }

    @Test fun theKillSwitchStopsPollingAndLogsOut() = runTest {
        val fake = Fake { good to null }
        val r = runner(fake); r.setInterest(Interest.Full); seconds(1)
        r.pause(); seconds(1)
        assertEquals(1, fake.logouts.get(), "a paused link drops its session")
        assertEquals(PanelStatus.Paused, r.state.value.status)
        val n = fake.calls.size; seconds(3600); assertEquals(n, fake.calls.size)
        r.resume(); seconds(40); assertTrue(fake.calls.size > n)
    }

    @Test fun aProblemOnlyTheOwnerCanFixStopsPollingUntilRestart() = runTest {
        var refused = true
        val fake = Fake { if (refused) null to ServiceProblem(ProblemKind.CredentialRefused, "refused", 401) else good to null }
        val r = runner(fake); r.setInterest(Interest.Full); seconds(1)
        seconds(7200)
        assertEquals(1, fake.calls.size, "no retry loop against a refused credential")
        assertEquals(PanelStatus.Unauthorised, r.state.value.status)
        refused = false; r.restart(); seconds(1)
        assertEquals(PanelStatus.Ok, r.state.value.status)
    }

    @Test fun threeChangedAnswersStopTheRequest() = runTest {
        val fake = Fake { """{"renamed": 1}""" to null }
        val r = runner(fake); r.setInterest(Interest.Compact)
        seconds(3600)
        assertEquals(3, fake.calls.size)
        assertEquals(PanelStatus.ApiChanged, r.state.value.status)
        assertTrue(r.state.value.requests.getValue("stats").stopped)
    }

    @Test fun theFirstSuccessIsReportedOnce() = runTest {
        val count = AtomicInteger()
        var ok = false
        val fake = Fake { if (ok) good to null else null to ServiceProblem(ProblemKind.Timeout, "slow") }
        val r = runner(fake) { count.incrementAndGet() }; r.setInterest(Interest.Compact); seconds(1)
        assertEquals(0, count.get(), "a failure is not a success")
        ok = true; seconds(3600)
        assertEquals(1, count.get())
    }

    @Test fun sampledSeriesKeepTheirLastValues() = runTest {
        var n = 0
        val fake = Fake { """{"total": ${++n}, "part": 1, "state": "on", "h": []}""" to null }
        val r = runner(fake); r.setInterest(Interest.Compact); seconds(1); seconds(31 * 15)
        val line = r.state.value.series("totals")!!.lines.single().points
        assertEquals(10, line.size, "keep = 10")
        assertEquals(r.state.value.reading("total")!!.number, line.last().value)
        assertEquals(1.0, line.zipWithNext { x, y -> y.value!! - x.value!! }.distinct().single(), "one point per poll, in order")
    }
}
