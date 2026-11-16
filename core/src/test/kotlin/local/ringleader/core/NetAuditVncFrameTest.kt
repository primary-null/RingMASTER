package local.ringleader.core

import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Network audit 2026-11-16: large RFB frames and large terminal frames against what Proxmox accepts and what QEMU sends.
 * The audit's characterizations of the limits are flipped here (N-04 and N-05 are fixed): same scenarios, renamed, asserting the
 * right behaviour. More cases: VncLargeFrameTest, TerminalPasteTest.
 */
class NetAuditVncFrameTest {
    /** One update that holds a single rectangle covering all of a [width] x [height] screen, flat [argb], in the given encoding. */
    private fun fullScreen(width: Int, height: Int, encoding: Int, argb: Int): ByteArray {
        val payload = if (encoding == 6) vncZlibPayload(deflateRect(width, height, Deflater()) { _, _ -> argb })
        else ByteArray(width * height * 4).also { raw -> for (i in 0 until width * height) putPixel(raw, i * 4, argb) }
        return VncTranscript().hello(width, height).update(vncRect(0, 0, width, height, encoding, payload)).bytes()
    }

    @Test fun aZlibRectangleOverSixteenMebibytesIsDecodedSoAFullFrameOfABigScreenCanBeShown() {
        // The audit: 2560x1600 = 16,384,000 bytes passed the 16 MiB check, and a full-frame update of these screens (ONE rectangle in
        // QEMU's zlib encoder) ended the console with "The console rectangle is too large.". Now they are all shown.
        for ((w, h) in listOf(2560 to 1600, 3840 to 2160, 3440 to 1440, 3840 to 1600, 2560 to 1664)) {
            val session = VncSession(RecordingScript(fullScreen(w, h, 6, 0xFF336699.toInt())))
            try {
                session.handshake("password")
                assertTrue(session.pump(), "${w}x$h = ${w * h * 4} bytes")
                val frame = IntArray(w * h).also { session.readInto(it) }
                assertEquals(0xFF336699.toInt(), frame[0], "${w}x$h first pixel")
                assertEquals(0xFF336699.toInt(), frame[w * h - 1], "${w}x$h last pixel")
            } finally { session.close() }
        }
        println("NETAUDIT zlib rectangles: no 16 MiB cap; the bound is the framebuffer (8192 x 8192); 3840x2160 needs ${3840 * 2160 * 4} bytes and is decoded in slices")
    }

    @Test fun rawRectanglesAreReadInSlicesInsteadOfOneArrayOfTheWholeRectangle() {
        // The audit: a full-screen Raw rectangle asked the channel for the whole 8,294,400 bytes at once (one ByteArray(w*h*4), up to 256 MiB on
        // the biggest screen). Now the largest single read is one slice.
        val script = RecordingScript(fullScreen(1920, 1080, 0, 0xFF102030.toInt()))
        val session = VncSession(script)
        try {
            session.handshake("password")
            script.watchFrom = script.consumed
            assertTrue(session.pump())
            assertTrue(script.maxRequest in 1..VNC_SLICE_BYTES, "largest read: ${script.maxRequest} bytes")
        } finally { session.close() }
        assertEquals(1920 * 1080 * 4, 8_294_400)
    }

    /**
     * N-05, fixed. pve-http-server websocket_proxy: $max_payload_size = 128 * 1024; a bigger client frame dies with "received too
     * large websocket frame". The audit's scenario (a 300,000 character paste) was ONE frame of 300,000 bytes; it is now several
     * frames of at most 16 KiB of data, in order. More cases: TerminalPasteTest.
     */
    @Test fun aLargePasteIsSentInSeveralFramesFarBelowWhatProxmoxAccepts() {
        val session = TerminalSession(TerminalModel(), null)
        // Read while the writer thread appends: a copy-on-write list, so the waiting loop below never iterates a list in motion.
        val frames = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            session.receive("OK".toByteArray())
            session.startWriter { frames += it }
            session.input("x".repeat(300_000))
            val deadline = System.nanoTime() + 5_000_000_000
            while (frames.sumOf { it.length } < 300_000 && System.nanoTime() < deadline) Thread.sleep(10)
            val sent = frames.toList()
            assertTrue(sent.size > 1, "several frames, not one")
            assertTrue(sent.all { it.toByteArray(Charsets.UTF_8).size < 128 * 1024 / 4 }, "every frame is far below 128 KiB: ${sent.map { it.length }}")
            assertEquals("x".repeat(300_000), sent.joinToString("") { it.substringAfter(':').substringAfter(':') })
        } finally { session.close() }
    }
}
