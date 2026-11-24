package local.ringleader.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForegroundPowerEvidenceTest {
    private val key = GuestKey("https://example.test:8006", "VM", 100)

    @Test fun scheduledClaimBeforeAtomicForegroundEntryDoesNotConsumeConfirmation() {
        val leases = MutationLeases()
        val used = mutableSetOf<String>()
        assertFalse(leases.scheduledHolds(key)) // Preliminary UI check can already be stale at entry.
        assertNull(leases.acquireScheduled(key, "scheduler"))
        var marked = false
        assertNull(leases.confirmForeground(key, used, "consent") { marked = true })
        assertFalse(marked)
        assertTrue(used.isEmpty())
        leases.releaseScheduled(key, "scheduler")
        assertEquals(true, leases.confirmForeground(key, used, "consent") { marked = true })
        assertTrue(marked)
        assertEquals(false, leases.confirmForeground(key, used, "consent") { error("duplicate send") })
    }

    @Test fun scheduledClaimCannotSlipBetweenConsumptionAndMarkingInFlight() {
        val leases = MutationLeases()
        val marked = AtomicBoolean(false)
        val provider = ForegroundMutations { if (marked.get()) "Foreground send in flight" else null }
        leases.register(provider)
        val consuming = CountDownLatch(1)
        val attempting = CountDownLatch(1)
        val used = object : HashSet<String>() {
            override fun add(element: String): Boolean {
                consuming.countDown()
                check(attempting.await(5, TimeUnit.SECONDS))
                return super.add(element)
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val scheduled = executor.submit<String?> {
                check(consuming.await(5, TimeUnit.SECONDS))
                attempting.countDown()
                leases.acquireScheduled(key, "scheduler")
            }
            assertEquals(true, leases.confirmForeground(key, used, "consent") { marked.set(true) })
            assertEquals("Foreground send in flight", scheduled.get(5, TimeUnit.SECONDS))
            assertFalse(leases.scheduledHolds(key))
            assertEquals(setOf("consent"), used)
        } finally {
            executor.shutdownNow()
            leases.unregister(provider)
        }
    }
}
