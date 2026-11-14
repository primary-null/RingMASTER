package local.ringleader.core

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Last console frames stay in memory, under the same rules as a captured still. */
class KeepConsoleFrameTest {
    private val origin = "https://192.0.2.10:8006"

    private fun guest(id: Int, status: String = "running", kind: String = "VM", scope: String = "", node: String = "node-a") =
        Guest(origin, id, "vm-$id", node, kind, status, scope = scope)

    private fun red(size: Int = 16) = IntArray(size) { 0xFF2244AA.toInt() }
    private fun black(size: Int = 16) = IntArray(size) { 0xFF000000.toInt() }

    private fun Workspace.encodeHere() {
        consoleFrameEncode = { width, height, pixels -> frameStill(RfbFrame(width, height, pixels)) }
    }

    @Test fun aUsefulFrameReplacesAnOlderStillAndABlankDoesNot() = runTest {
        val workspace = Workspace(this)
        workspace.encodeHere()
        val guest = guest(112)
        try {
            workspace.keepConsoleFrame(guest, 4, 4, black())
            runCurrent()
            assertTrue(workspace.state.value.consoleStills.getValue(guest.resource).blank)
            workspace.keepConsoleFrame(guest, 4, 4, red())
            runCurrent()
            val useful = workspace.state.value.consoleStills.getValue(guest.resource)
            assertFalse(useful.blank)
            workspace.state.value.let { assertTrue(guest.resource !in it.consoleStillErrors) }
            workspace.keepConsoleFrame(guest, 4, 4, black())
            runCurrent()
            val kept = workspace.state.value.consoleStills.getValue(guest.resource)
            assertFalse(kept.blank)
            assertTrue(useful.png.contentEquals(kept.png))
        } finally { workspace.close(); runCurrent() }
    }

    @Test fun onlyTheNewestStillsAreKept() = runTest {
        val workspace = Workspace(this)
        workspace.encodeHere()
        try {
            repeat(CONSOLE_STILL_LIMIT + 1) { index ->
                workspace.keepConsoleFrame(guest(300 + index), 4, 4, red())
                runCurrent()
            }
            val stills = workspace.state.value.consoleStills
            assertEquals(CONSOLE_STILL_LIMIT, stills.size)
            assertNull(stills[guest(300).resource])
            assertTrue(guest(300 + CONSOLE_STILL_LIMIT).resource in stills)
        } finally { workspace.close(); runCurrent() }
    }

    @Test fun demoStoppedContainerScopeAndABadSizeDoNothing() = runTest {
        val workspace = Workspace(this)
        workspace.encodeHere()
        try {
            workspace.keepConsoleFrame(guest(1, status = "stopped"), 4, 4, red())
            workspace.keepConsoleFrame(guest(2, kind = "Container"), 4, 4, red())
            workspace.keepConsoleFrame(guest(3, scope = "lab"), 4, 4, red())
            workspace.keepConsoleFrame(guest(4), 0, 4, red())
            workspace.keepConsoleFrame(guest(5), 4, 4, IntArray(3))
            runCurrent()
            assertTrue(workspace.state.value.consoleStills.isEmpty())
            workspace.exploreDemo()
            workspace.setLive(false)
            val demoGuest = workspace.state.value.overview!!.guests.first { it.id == 120 }
            workspace.keepConsoleFrame(demoGuest, 4, 4, red())
            runCurrent()
            assertTrue(workspace.state.value.consoleStills.isEmpty())
        } finally { workspace.close(); runCurrent() }
    }

    @Test fun aPdmConnectionKeepsNothing() = runTest {
        val gate = CompletableDeferred<PdmFleetDocument>()
        val workspace = Workspace(this, pdmGateway = object : PdmReadGateway {
            override suspend fun read(connection: Connection): PdmFleetDocument = gate.await()
        })
        workspace.encodeHere()
        try {
            workspace.connect(Connection(origin, "tester@pam!read", "secret", "", PDM_KIND))
            workspace.keepConsoleFrame(guest(9), 4, 4, red())
            runCurrent()
            assertTrue(workspace.state.value.consoleStills.isEmpty())
        } finally { workspace.close(); runCurrent() }
    }

    @Test fun aFrameFromAnOlderConnectionIsDropped() = runTest {
        val workspace = Workspace(this)
        val gate = CompletableDeferred<ConsoleStill?>()
        workspace.consoleFrameEncode = { _, _, _ -> gate.await() }
        try {
            workspace.keepConsoleFrame(guest(112), 4, 4, red())
            runCurrent()
            workspace.disconnect()
            val late = ConsoleStill(1, 1, byteArrayOf(1, 2, 3), Instant.parse("2026-11-11T00:00:00Z"), blank = false)
            gate.complete(late)
            runCurrent()
            assertTrue(workspace.state.value.consoleStills.isEmpty(), "a frame encoded for the old connection must not land on the new state")
        } finally { workspace.close(); runCurrent() }
    }

    @Test fun theDefaultEncoderKeepsARealFrame() = runBlocking {
        val workspace = Workspace(this)
        val guest = guest(7)
        try {
            workspace.keepConsoleFrame(guest, 2, 2, red(4))
            withTimeout(15_000) {
                while (workspace.state.value.consoleStills[guest.resource] == null) delay(20)
            }
            val still = workspace.state.value.consoleStills.getValue(guest.resource)
            assertFalse(still.blank)
            assertTrue(still.png.size > 8)
            assertFalse(still.toString().contains("png="))
        } finally { workspace.close() }
    }
}
