package local.ringleader.core

import java.util.Collections
import java.util.concurrent.Semaphore
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Network audit 2026-11-16, N-05: a terminal paste used to be one websocket frame, and Proxmox's websocket proxy ends the
 * connection on any client frame over 128 KiB. Typed input now goes out in frames of at most [TERMINAL_FRAME_BYTES] data bytes and a paste in frames of [TERMINAL_PASTE_FRAME_BYTES],
 * each a whole `0:<bytes>:<data>` message, cut only between characters, in order, one at a time as the socket takes them.
 */
class TerminalPasteTest {
    private val esc = Char(27).toString()
    private val proxmoxFrameLimit = 128 * 1024

    private fun waitFor(what: String, millis: Long = 5000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (!condition()) { check(System.nanoTime() < deadline) { "timed out waiting for $what" }; Thread.sleep(5) }
    }

    /** A ready session whose writer records every frame it is given, optionally one at a time through [gate]. */
    private class Rig(gated: Boolean = false) {
        // No server answers here, so a paste decides quickly that there is no echo to wait for (see TerminalPasteEchoTest for the paced cases).
        val session = TerminalSession(TerminalModel(), null, pasteProbeMillis = 20)
        val frames: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val gate = Semaphore(0)
        init {
            session.receive("OK".toByteArray())
            session.startWriter { if (gated) gate.acquire(); frames += it }
        }
        fun close() { gate.release(10_000); session.close() }
    }

    /** The text of the data messages in [frames]; each is checked to be a whole, exactly sized `0:<bytes>:<data>`. */
    private fun dataOf(frames: List<String>): List<String> = frames.filter { it.startsWith("0:") }.map { frame ->
        val length = frame.substring(2, frame.indexOf(':', 2)).toInt()
        val data = frame.substring(frame.indexOf(':', 2) + 1)
        assertEquals(length, data.toByteArray(Charsets.UTF_8).size, "the length in the header is the byte length of the data")
        assertTrue(frame.toByteArray(Charsets.UTF_8).size < proxmoxFrameLimit)
        data
    }

    // ---- the pure parts ----

    @Test fun chunksStayWithinTheLimitJoinBackToTheTextAndNeverSplitACharacter() {
        val random = Random(5)
        val alphabet = listOf("a", "Z", " ", "\n", "é", "ß", "€", "中", "😀", "🧪")
        for (limit in listOf(4, 5, 7, 16, 1000)) {
            val text = buildString { repeat(20_000) { append(alphabet[random.nextInt(alphabet.size)]) } }
            val pieces = terminalChunks(text, limit)
            assertEquals(text, pieces.joinToString(""), "limit $limit: nothing lost, nothing reordered")
            for (piece in pieces) {
                val bytes = piece.toByteArray(Charsets.UTF_8).size
                assertTrue(bytes in 1..limit, "limit $limit: piece of $bytes bytes")
                assertEquals(bytes.toLong(), terminalByteSize(piece))
                assertFalse(piece.first().isLowSurrogate() || piece.last().isHighSurrogate(), "a surrogate pair was cut")
            }
            assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), terminalByteSize(text))
        }
    }

    @Test fun aFourByteCharacterAtTheEdgeOfAPieceMovesToTheNextPiece() {
        assertEquals(listOf("😀", "😀", "😀"), terminalChunks("😀😀😀", 4))
        assertEquals(listOf("ab", "😀", "cd"), terminalChunks("ab😀cd", 4))
        assertEquals(listOf("é€", "é"), terminalChunks("é€é", 5))
        assertEquals(emptyList(), terminalChunks(""))
        assertEquals(listOf("hello"), terminalChunks("hello"))
    }

    @Test fun anUnpairedSurrogateBecomesTheReplacementCharacterSoTheFrameIsWellFormedText() {
        val lone = Char(0xD83D).toString()
        assertEquals(listOf("a�b"), terminalChunks("a" + lone + "b"))
        assertEquals(listOf("�" + "x"), terminalChunks(Char(0xDE00).toString() + "x"))
        assertEquals(3L + 2L, terminalByteSize(lone + "é"))
        // A pair that is complete stays whole, whatever surrounds it.
        assertEquals(listOf("a😀b"), terminalChunks("a😀b"))
    }

    // ---- the audit's scenario, now fixed ----

    @Test fun aLargeInputIsSentInManySmallFramesFarBelowWhatProxmoxAccepts() {
        // The audit's characterization asserted ONE frame of 300,000 bytes here, over pve-http-server's 128 KiB limit.
        val rig = Rig()
        try {
            val text = "x".repeat(300_000)
            rig.session.input(text)
            waitFor("every frame") { dataOf(rig.frames.toList()).sumOf { it.length } == text.length }
            val frames = rig.frames.toList()
            assertEquals(19, frames.size, "300,000 bytes in frames of at most ${TERMINAL_FRAME_BYTES}")
            assertTrue(frames.all { it.toByteArray(Charsets.UTF_8).size <= TERMINAL_FRAME_BYTES + 16 })
            assertEquals(text, dataOf(frames).joinToString(""))
        } finally { rig.close() }
    }

    @Test fun aPasteOfMultibyteTextIsCutBetweenCharactersAndArrivesWhole() {
        val rig = Rig()
        try {
            val text = buildString { repeat(40_000) { append("é€😀中x") } }          // about 400 KB
            assertEquals(null, rig.session.paste(text))
            waitFor("the paste to leave") { rig.session.pasteLeft == 0 && dataOf(rig.frames.toList()).joinToString("").length == text.length }
            val data = dataOf(rig.frames.toList())
            assertEquals(text, data.joinToString(""))
            assertTrue(data.all { !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
        } finally { rig.close() }
    }

    @Test fun aBracketedPasteKeepsItsMarkersAroundTheTextInOrder() {
        val rig = Rig()
        try {
            val text = "line one\nline two\n" + "y".repeat(40_000)
            assertNull(rig.session.paste(text, bracketed = true))
            waitFor("the closing marker") { rig.frames.lastOrNull()?.endsWith("$esc[201~") == true }
            val frames = rig.frames.toList()
            assertEquals("0:6:$esc[200~", frames.first())
            assertEquals("0:6:$esc[201~", frames.last())
            assertEquals(text, dataOf(frames.drop(1).dropLast(1)).joinToString(""))
            assertEquals(0, rig.session.pasteLeft)
        } finally { rig.close() }
    }

    @Test fun typingBeforeAndAfterAPasteKeepsItsPlaceAndNothingLandsInsideIt() {
        val rig = Rig()
        try {
            rig.session.input("before")
            // Another thread types all the while: its frames may land before or after the paste, never inside it.
            val going = java.util.concurrent.atomic.AtomicBoolean(true)
            val typist = Thread { while (going.get()) { rig.session.input("k"); Thread.sleep(0, 100_000) } }.apply { start() }
            Thread.sleep(50)
            assertNull(rig.session.paste("P".repeat(100_000)))
            rig.session.resize(120, 30)
            Thread.sleep(50)
            going.set(false); typist.join()
            rig.session.input("after")
            waitFor("everything to leave") { rig.frames.contains("0:5:after") }
            val frames = rig.frames.toList()
            val first = frames.indexOfFirst { it.endsWith("PPPP") }
            val last = frames.indexOfLast { it.endsWith("PPPP") }
            assertEquals("0:6:before", frames.first())
            assertTrue((first..last).all { frames[it].endsWith("PPPP") }, "no other frame between the paste's frames")
            assertEquals(49, last - first + 1, "100,000 bytes in 2 KiB frames")
            assertTrue(frames.indexOf("1:120:30:") > last, "the resize stays behind the paste, as it was issued")
            assertEquals("0:5:after", frames.last())
            assertTrue(first > 1, "the typist really was running before the paste: $first")
        } finally { rig.close() }
    }

    // ---- the limit ----

    @Test fun aPasteOverOneMebibyteIsRefusedWithASentenceAndNothingIsSent() {
        val rig = Rig()
        try {
            val refusal = rig.session.paste("x".repeat(TERMINAL_PASTE_LIMIT + 1))
            assertNotNull(refusal)
            assertTrue("1 MiB" in refusal && "refused" in refusal, refusal)
            assertEquals(0, rig.session.pasteLeft)
            // The limit is on bytes, not characters: 400,000 euro signs are 1.2 MB.
            val euros = rig.session.paste("€".repeat(400_000))
            assertTrue(euros != null && "1.1 MiB" in euros, euros)
            Thread.sleep(100)
            assertTrue(rig.frames.isEmpty(), "a refused paste sends nothing, not even the start of it")
            assertNull(rig.session.reason)
            assertTrue(rig.session.ready, "a refused paste does not touch the session")
        } finally { rig.close() }
    }

    @Test fun aPasteOfExactlyTheLimitIsAccepted() {
        val rig = Rig()
        try {
            assertNull(rig.session.paste("x".repeat(TERMINAL_PASTE_LIMIT)))
            waitFor("all 512 frames") { rig.frames.size == 512 }
            assertEquals(TERMINAL_PASTE_LIMIT, dataOf(rig.frames.toList()).sumOf { it.length })
        } finally { rig.close() }
    }

    @Test fun anEmptyPasteOrOneBeforeTheSessionIsReadyIsQuietlyNothing() {
        val rig = Rig()
        try {
            assertNull(rig.session.paste(""))
        } finally { rig.close() }
        val early = TerminalSession(TerminalModel(), null)
        try { assertNull(early.paste("text")); assertEquals(0, early.pasteLeft) } finally { early.close() }
    }

    // ---- progress, stopping, and one at a time ----

    @Test fun progressFollowsTheFramesAsTheSocketTakesThemAndTheScreenIsToldEachTime() {
        val rig = Rig(gated = true)
        try {
            val pokes = java.util.concurrent.atomic.AtomicInteger()
            rig.session.model.onUpdate = { pokes.incrementAndGet() }
            assertNull(rig.session.paste("a".repeat(3 * TERMINAL_PASTE_FRAME_BYTES)))
            assertEquals(3 * TERMINAL_PASTE_FRAME_BYTES, rig.session.pasteTotal)
            assertEquals(3 * TERMINAL_PASTE_FRAME_BYTES, rig.session.pasteLeft)
            rig.gate.release(1)
            waitFor("the first frame") { rig.session.pasteLeft == 2 * TERMINAL_PASTE_FRAME_BYTES }
            rig.gate.release(2)
            waitFor("the paste to end") { rig.session.pasteLeft == 0 && rig.session.pasteTotal == 0 }
            assertTrue(pokes.get() >= 3, "told on every frame: ${pokes.get()}")
        } finally { rig.close() }
    }

    @Test fun stoppingAPasteDropsWhatIsLeftAndKeepsEverythingElse() {
        val rig = Rig(gated = true)
        try {
            assertNull(rig.session.paste("p".repeat(10 * TERMINAL_PASTE_FRAME_BYTES)))
            rig.gate.release(2)
            waitFor("two frames out") { rig.frames.size == 2 }
            Thread.sleep(100)                          // the writer now holds the third frame: it cannot be called back
            rig.session.stopPaste()
            assertEquals(0, rig.session.pasteLeft, "stopped at once, as far as the screen is concerned")
            rig.session.input("typed after")
            rig.gate.release(100)
            waitFor("the typed text") { rig.frames.contains("0:11:typed after") }
            assertEquals(4, rig.frames.size, "three frames of the paste (the third was already in the writer's hand), then what was typed")
            assertEquals("0:11:typed after", rig.frames.last())
            assertNull(rig.session.reason)
        } finally { rig.close() }
    }

    @Test fun stoppingABracketedPasteAfterItStartedStillClosesTheBracket() {
        val rig = Rig(gated = true)
        try {
            assertNull(rig.session.paste("q".repeat(10 * TERMINAL_PASTE_FRAME_BYTES), bracketed = true))
            rig.gate.release(3)                        // the opening marker and two chunks
            waitFor("three frames out") { rig.frames.size == 3 }
            Thread.sleep(100)                          // the writer holds the third chunk
            rig.session.stopPaste()
            rig.gate.release(100)
            waitFor("the closing marker") { rig.frames.lastOrNull() == "0:6:$esc[201~" }
            assertEquals("0:6:$esc[200~", rig.frames.first())
            assertEquals(5, rig.frames.size, "opening marker, three chunks (one already in hand), closing marker: nothing else")
            assertTrue(dataOf(rig.frames.drop(1).dropLast(1)).all { it.all { c -> c == 'q' } })
        } finally { rig.close() }
    }

    @Test fun stoppingABracketedPasteBeforeAnythingLeftSendsNothingAtAll() {
        val rig = Rig(gated = true)
        try {
            rig.session.input("hold")                  // the writer takes this and waits at the gate, so the paste queues behind it
            Thread.sleep(100)
            assertNull(rig.session.paste("r".repeat(5 * TERMINAL_PASTE_FRAME_BYTES), bracketed = true))
            rig.session.stopPaste()
            rig.session.input("go")
            rig.gate.release(100)
            waitFor("the typed text") { rig.frames.contains("0:2:go") }
            assertEquals(listOf("0:4:hold", "0:2:go"), rig.frames.toList(), "neither the markers nor the text of a paste that never started")
        } finally { rig.close() }
    }

    @Test fun onlyOnePasteGoesAtATimeAndAStoppedOneNoLongerBlocksTheNext() {
        val rig = Rig(gated = true)
        try {
            assertNull(rig.session.paste("a".repeat(3 * TERMINAL_PASTE_FRAME_BYTES)))
            assertEquals(TERMINAL_PASTE_BUSY, rig.session.paste("b"))
            rig.session.stopPaste()
            assertNull(rig.session.paste("c"), "the stopped paste is over as far as a new one is concerned")
            rig.gate.release(100)
            waitFor("the new paste") { rig.frames.contains("0:1:c") }
            assertFalse(rig.frames.any { it == "0:1:b" })
        } finally { rig.close() }
    }

    @Test fun aPasteThatDoesNotFitBehindEarlierInputIsRefusedWholeAndTheSessionLives() {
        val rig = Rig(gated = true)
        try {
            // One frame is held by the writer; fill the queue until two slots are left.
            repeat(TERMINAL_OUTBOX_LIMIT - 1) { rig.session.input("k") }
            assertEquals(TERMINAL_PASTE_NO_ROOM, rig.session.paste("m".repeat(3 * TERMINAL_PASTE_FRAME_BYTES)))
            assertNull(rig.session.reason)
            assertEquals(0, rig.session.pasteLeft)
            rig.gate.release(10_000)
            waitFor("the typing to leave") { rig.frames.size == TERMINAL_OUTBOX_LIMIT - 1 }
            Thread.sleep(100)
            assertEquals(TERMINAL_OUTBOX_LIMIT - 1, rig.frames.size, "not one frame of the refused paste was queued")
        } finally { rig.close() }
    }

    @Test fun closingTheSessionDropsAPasteInFlight() {
        val rig = Rig(gated = true)
        assertNull(rig.session.paste("z".repeat(5 * TERMINAL_PASTE_FRAME_BYTES)))
        rig.session.close()
        assertEquals(0, rig.session.pasteLeft)
        assertEquals(0, rig.session.pasteTotal)
        rig.close()
    }
}
