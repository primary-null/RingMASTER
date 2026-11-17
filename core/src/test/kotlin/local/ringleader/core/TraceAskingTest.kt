package local.ringleader.core

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A read that asks what the account may see (the radios read asks for a node's USB devices) is often refused. That
 * refusal is shown to the user as a note where it belongs; in the request trace it is an answer, not an error.
 */
class TraceAskingTest {
    private fun ended(span: Trace.Span) = Trace.snapshot().single { it.spanId == span.id && it.kind == "end" }

    /** One finished request with [status], and an exception when the client threw for it. */
    private fun request(name: String, status: Int?, failed: Boolean = true): Trace.Span {
        val span = Trace.begin(TraceSource.PveApi, "GET /sample/$name")
        span.end(status = status, error = if (failed) IllegalStateException("sample") else null)
        return span
    }

    @Test fun aRefusalThatWasAskedForIsAnAnswer() {
        val asked = Trace.asking { request("asked", 403) }
        assertEquals(TraceOutcome.Info, ended(asked).outcome)
        assertEquals(403, ended(asked).status, "the trace still shows what the server said")
    }

    @Test fun theSameRefusalElsewhereIsAnError() {
        assertEquals(TraceOutcome.Error, ended(request("plain", 403)).outcome)
    }

    @Test fun onlyARefusalIsExcused() {
        Trace.asking {
            assertEquals(TraceOutcome.Error, ended(request("broken", 500)).outcome)
            assertEquals(TraceOutcome.Error, ended(request("timed-out", null)).outcome, "no answer at all is a fault")
            assertEquals(TraceOutcome.Ok, ended(request("fine", 200, failed = false)).outcome)
        }
    }

    @Test fun theMarkEndsWithItsBlockAndStaysOnItsThread() {
        Trace.asking {
            var elsewhere: Trace.Span? = null
            thread { elsewhere = request("other-thread", 403) }.join()
            assertEquals(TraceOutcome.Error, ended(elsewhere!!).outcome, "another thread did not ask")
        }
        assertEquals(TraceOutcome.Error, ended(request("afterwards", 403)).outcome)
        val nested = Trace.asking { Trace.asking { }; request("after-nested", 403) }
        assertEquals(TraceOutcome.Info, ended(nested).outcome, "an inner block does not end the outer one")
    }
}
