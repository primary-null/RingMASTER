package local.ringleader.core.tools

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import local.ringleader.core.NameLookup
import local.ringleader.core.NameLookupTimedOutException

/** The DNS tool and the name-lookup hook: the user's own timeout is the hook's deadline for that lookup. */
class DnsToolBoundTest {
    @Test fun theLookupMayRunAsLongAsTheUserChose() = runBlocking {
        var seen: Long? = null
        val plugin = DnsPlugin(resolve = { seen = NameLookup.callerBound(); arrayOf(InetAddress.getLoopbackAddress()) })
        val result = plugin.run(mapOf("name" to "pve.example.test", "timeout" to "7")) {}
        assertTrue(result.ok, result.summary)
        assertEquals(7_000L, seen, "a 7 s timeout in the tool is a 7 s deadline in the hook, not the 5 s default")
        assertEquals(null, NameLookup.callerBound(), "and only for that lookup")
    }

    @Test fun theHooksOwnTimeoutReadsLikeTheToolsDeadline() = runBlocking {
        val plugin = DnsPlugin(resolve = { throw NameLookupTimedOutException(it, 3_000) })
        val result = plugin.run(mapOf("name" to "slow.example.test", "timeout" to "3")) {}
        assertFalse(result.ok)
        assertEquals("No answer for slow.example.test within 3 s.", result.summary)
    }
}
