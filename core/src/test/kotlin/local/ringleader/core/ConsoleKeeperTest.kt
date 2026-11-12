package local.ringleader.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConsoleKeeperTest {
    private val origin = "https://example.test:8006"
    private fun guest(id: Int) = Guest(origin, id, "guest$id", "node", "VM", "running")

    private fun keeper(
        scope: CoroutineScope,
        now: () -> Long = { 0L },
        keep: () -> Long = { 60_000L },
        limit: Int = CONSOLE_KEEP_LIMIT,
        onChange: (Set<ResourceId>) -> Unit = {},
    ) = ConsoleKeeper(scope, now, keep, limit, onChange)

    /** A ready session. [onWrite] sees each client message, including the key-up from [VncSession.releaseInput]. */
    private fun readySession(onWrite: (ByteArray) -> Unit = {}): VncSession {
        val bin = ByteArrayOutputStream()
        val data = DataOutputStream(bin)
        data.writeBytes("RFB 003.008\n")
        data.writeByte(1); data.writeByte(2); data.write(ByteArray(16)); data.writeInt(0)
        data.writeShort(4); data.writeShort(2); data.write(ByteArray(16)); data.writeInt(4); data.writeBytes("test")
        data.flush()
        val session = VncSession(object : RfbChannel {
            private val input = bin.toByteArray().inputStream()
            override fun read(n: Int): ByteArray {
                val got = input.readNBytes(n)
                if (got.size != n) error(VNC_LOST)
                return got
            }
            override fun write(bytes: ByteArray) { onWrite(bytes.copyOf()) }
            override fun close() = Unit
        })
        session.handshake("password")
        return session
    }

    @Test fun releaseKeepsAPausedSessionAndTakeResumesIt() = runTest {
        val seen = mutableListOf<Set<ResourceId>>()
        val keeper = keeper(this) { seen += it }
        val session = readySession()
        val id = guest(1).resource
        keeper.adopt(id, session).release(true)
        assertTrue(session.isPaused())
        assertFalse(session.isClosed())
        assertEquals(listOf(setOf(id)), seen)
        val lease = keeper.take(id)
        assertNotNull(lease)
        assertFalse(lease.fresh)
        assertSame(session, lease.session)
        assertFalse(session.isPaused())
        assertEquals(listOf(setOf(id), emptySet()), seen)
        lease.release(false)
        assertTrue(session.isClosed())
    }

    @Test fun releaseInputHappensBeforePause() = runTest {
        var keyUps = 0
        var keyUpsWhilePaused = 0
        lateinit var session: VncSession
        session = readySession { bytes ->
            if (bytes.size >= 2 && (bytes[0].toInt() and 255) == 4 && bytes[1].toInt() == 0) {
                keyUps++
                if (session.isPaused()) keyUpsWhilePaused++
            }
        }
        session.keyInput(1L, true, 0x61)
        val keeper = keeper(this)
        keeper.adopt(guest(1).resource, session).release(true)
        assertTrue(keyUps > 0, "the held key was released")
        assertEquals(0, keyUpsWhilePaused, "releaseInput runs before pause")
        assertTrue(session.isPaused())
        // A frozen test clock never reaches the keep deadline, so the expiry job has to be cancelled here.
        keeper.closeAll()
        assertTrue(session.isClosed())
    }

    @Test fun theSessionClosesWhenItsKeepTimeRunsOut() = runTest {
        val keeper = keeper(this, now = { currentTime }, keep = { 5_000L })
        val session = readySession()
        val id = guest(1).resource
        keeper.adopt(id, session).release(true)
        advance(4_999)
        assertFalse(session.isClosed())
        assertNotNull(keeper.take(id)).release(false)
        val again = readySession()
        keeper.adopt(id, again).release(true)
        advance(5_000)
        assertTrue(again.isClosed())
        assertNull(keeper.take(id))
    }

    @Test fun aFifthKeptSessionClosesTheOldest() = runTest {
        val seen = mutableListOf<Set<ResourceId>>()
        val keeper = keeper(this, now = { currentTime }, onChange = { seen += it })
        val sessions = (1..5).map { readySession() }
        val ids = (1..5).map { guest(it).resource }
        sessions.indices.forEach { index ->
            keeper.adopt(ids[index], sessions[index]).release(true)
            advance(1)
        }
        assertTrue(sessions[0].isClosed())
        assertFalse(sessions[4].isClosed())
        assertNull(keeper.take(ids[0]))
        val kept = keeper.take(ids[4])
        assertNotNull(kept)
        assertFalse(kept.fresh)
        assertEquals(4, seen.last { it.size == 4 }.size)
        sessions.forEach { it.close() }
    }

    @Test fun keepingOffClosesOnReleaseAndCloseAllEmptiesTheSet() = runTest {
        val seen = mutableListOf<Set<ResourceId>>()
        var keep = 0L
        val keeper = keeper(this, keep = { keep }, onChange = { seen += it })
        val refused = readySession()
        keeper.adopt(guest(1).resource, refused).release(true)
        assertTrue(refused.isClosed())
        assertTrue(seen.isEmpty(), "a session that was never stored does not publish a kept set")
        keep = 60_000L
        val session = readySession()
        val id = guest(2).resource
        keeper.adopt(id, session).release(true)
        assertEquals(setOf(id), seen.last())
        keeper.closeAll()
        assertTrue(session.isClosed())
        assertEquals(emptySet(), seen.last())
    }

    @Test fun aClosedCallbackDropsTheKeptSession() = runTest {
        val seen = mutableListOf<Set<ResourceId>>()
        val keeper = keeper(this, onChange = { seen += it })
        val session = readySession()
        val id = guest(1).resource
        keeper.adopt(id, session).release(true)
        assertFalse(session.pump(), "the server ended the session")
        assertEquals(VNC_LOST, session.reason)
        assertNull(keeper.take(id))
        assertEquals(emptySet(), seen.last())
    }

    @Test fun closeGuestClosesOnlyThatGuest() = runTest {
        val keeper = keeper(this)
        val first = readySession()
        val second = readySession()
        val a = guest(1).resource
        val b = guest(2).resource
        keeper.adopt(a, first).release(true)
        keeper.adopt(b, second).release(true)
        keeper.closeGuest(a)
        assertTrue(first.isClosed())
        assertFalse(second.isClosed())
        assertNotNull(keeper.take(b))
        assertNull(keeper.take(a))
        second.close()
    }

    @Test fun aLaterReleaseReplacesTheEarlierKeptSession() = runTest {
        val keeper = keeper(this)
        val first = readySession()
        val second = readySession()
        val id = guest(1).resource
        val older = keeper.adopt(id, first)
        val newer = keeper.adopt(id, second)
        older.release(true)
        assertFalse(first.isClosed())
        newer.release(true)
        assertTrue(first.isClosed())
        val taken = keeper.take(id)
        assertSame(second, taken?.session)
        second.close()
    }

    @Test fun aSealedLeaseCannotBeKeptAfterCloseAll() = runTest {
        val keeper = keeper(this)
        val session = readySession()
        val lease = keeper.adopt(guest(1).resource, session)
        keeper.closeAll(sealOutstanding = true)
        lease.release(true)
        assertTrue(session.isClosed())
        assertNull(keeper.take(guest(1).resource))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.advance(millis: Long) {
    testScheduler.advanceTimeBy(millis)
    testScheduler.runCurrent()
}
