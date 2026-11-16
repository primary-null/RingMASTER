package local.ringleader.core

import java.lang.management.ManagementFactory
import java.util.zip.Deflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Network audit 2026-11-16, N-04: a zlib rectangle over 16 MiB ended the console, so a full-frame update of a 3840x2160,
 * 3440x1440 or 3840x1600 screen (one rectangle in QEMU's zlib encoder) could not be shown, and every rectangle was held
 * whole, compressed and decompressed, while it was read. A rectangle is now checked against the framebuffer first and decoded
 * a 64 KiB slice at a time into reused buffers.
 */
class VncLargeFrameTest {
    private fun open(script: RecordingScript): VncSession = VncSession(script).also { it.handshake("password"); script.watchFrom = script.consumed }

    private fun frameOf(session: VncSession): IntArray = IntArray(session.width * session.height).also { session.readInto(it) }

    private fun allocatedBy(block: () -> Unit): Long {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(id)
        block()
        return bean.getThreadAllocatedBytes(id) - before
    }

    @Test fun aFullFrameUpdateOfABigScreenIsDecodedPixelForPixelAndNothingBigIsAllocated() {
        for ((w, h) in listOf(3840 to 2160, 3440 to 1440, 3840 to 1600, 2560 to 1664, 2560 to 1600)) {
            val compressed = deflateRect(w, h, Deflater()) { x, y -> desktopPixel(w, x, y) }
            val script = RecordingScript(VncTranscript().hello(w, h).update(vncRect(0, 0, w, h, 6, vncZlibPayload(compressed))).bytes())
            val session = open(script)
            try {
                val started = System.nanoTime()
                var ok = false
                val allocated = allocatedBy { ok = session.pump() }
                val ms = (System.nanoTime() - started) / 1_000_000
                assertTrue(ok, "${w}x$h: the update was applied")
                val frame = frameOf(session)
                var wrong = 0
                for (y in 0 until h) for (x in 0 until w) if (frame[y * w + x] != desktopPixel(w, x, y)) wrong++
                assertEquals(0, wrong, "${w}x$h: pixels that differ")
                println("NETAUDIT zlib full frame ${w}x$h: ${compressed.size / 1024} KiB compressed, $ms ms, ${allocated / 1024} KiB allocated while decoding")
                assertTrue(allocated < 2L * 1024 * 1024, "${w}x$h: decoding allocated ${allocated / 1024} KiB; a rectangle must not be held whole (it is ${w * h * 4 / 1024} KiB)")
                assertTrue(script.maxRequest <= VNC_SLICE_BYTES, "${w}x$h: asked the channel for ${script.maxRequest} bytes at once")
            } finally { session.close() }
        }
    }

    @Test fun incompressibleDataTheWorstCaseForTheCompressedLengthIsDecodedToo() {
        assumeSlowAudit()
        val w = 3840; val h = 2160
        val random = Random(4)
        val compressed = deflateRect(w, h, Deflater()) { _, _ -> random.nextInt() or (255 shl 24) }
        val script = RecordingScript(VncTranscript().hello(w, h).update(vncRect(0, 0, w, h, 6, vncZlibPayload(compressed))).bytes())
        val session = open(script)
        try {
            var ok = false
            val allocated = allocatedBy { ok = session.pump() }
            assertTrue(ok)
            println("NETAUDIT zlib 3840x2160 incompressible: ${compressed.size / 1048576} MiB compressed (${"%.3f".format(compressed.size * 100.0 / (w * h * 4) - 100)} % over the raw size), ${allocated / 1024} KiB allocated")
            assertTrue(allocated < 2L * 1024 * 1024)
            val check = Random(4)
            val frame = frameOf(session)
            for (i in frame.indices) assertEquals(check.nextInt() or (255 shl 24), frame[i], "pixel $i")
        } finally { session.close() }
    }

    @Test fun aRawFullScreenRectangleIsReadInSlicesNotInOneArray() {
        // The audit's characterization scripted only the header and saw the pump fail on the single 8,294,400 byte read it asked for.
        val w = 1920; val h = 1080
        val raw = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) putPixel(raw, (y * w + x) * 4, desktopPixel(w, x, y))
        val script = RecordingScript(VncTranscript().hello(w, h).update(vncRect(0, 0, w, h, 0, raw)).bytes())
        val session = open(script)
        try {
            assertTrue(session.pump())
            assertTrue(script.maxRequest <= VNC_SLICE_BYTES, "asked for ${script.maxRequest} bytes at once")
            val frame = frameOf(session)
            for (y in 0 until h step 7) for (x in 0 until w step 3) assertEquals(desktopPixel(w, x, y), frame[y * w + x])
            assertEquals(desktopPixel(w, w - 1, h - 1), frame[w * h - 1])
        } finally { session.close() }
    }

    @Test fun aRectangleThatEndsInsideAPixelAtASliceBoundaryIsStillExact() {
        // 3 x 7000 pixels = 84,000 bytes: the 64 KiB slice ends in the middle of a pixel (65,536 = 21,845 pixels and 1 byte).
        val w = 3; val h = 7000
        fun pixel(x: Int, y: Int) = (255 shl 24) or (((y * 7 + x) and 255) shl 16) or (((y + x * 50) and 255) shl 8) or ((y xor x) and 255)
        val compressed = deflateRect(w, h, Deflater()) { x, y -> pixel(x, y) }
        val script = RecordingScript(VncTranscript().hello(w, h).update(vncRect(0, 0, w, h, 6, vncZlibPayload(compressed))).bytes())
        val session = open(script)
        try {
            assertTrue(session.pump())
            val frame = frameOf(session)
            for (y in 0 until h) for (x in 0 until w) assertEquals(pixel(x, y), frame[y * w + x], "($x,$y)")
        } finally { session.close() }
    }

    @Test fun theZlibStreamContinuesAcrossRectanglesUpdatesAndSlices() {
        val w = 400; val h = 300
        val deflater = Deflater()
        val random = Random(8)
        val noise = IntArray(200 * 200) { random.nextInt() or (255 shl 24) }
        // Rectangle 1 (a noisy block, bigger than a slice compressed) and rectangle 2 (flat) share one update; rectangle 3 is the next update.
        val first = deflateRect(200, 200, deflater) { x, y -> noise[y * 200 + x] }
        val second = deflateRect(100, 100, deflater) { _, _ -> 0xFF112233.toInt() }
        val third = deflateRect(w, h, deflater) { x, y -> desktopPixel(w, x, y) }
        assertTrue(first.size > VNC_SLICE_BYTES, "the first rectangle needs more than one slice: ${first.size}")
        val script = RecordingScript(VncTranscript().hello(w, h)
            .update(vncRect(10, 20, 200, 200, 6, vncZlibPayload(first)), vncRect(300, 100, 100, 100, 6, vncZlibPayload(second)))
            .update(vncRect(0, 0, w, h, 6, vncZlibPayload(third))).bytes())
        val session = open(script)
        try {
            assertTrue(session.pump())
            var frame = frameOf(session)
            for (y in 0 until 200) for (x in 0 until 200) assertEquals(noise[y * 200 + x], frame[(20 + y) * w + 10 + x])
            assertEquals(0xFF112233.toInt(), frame[100 * w + 300]); assertEquals(0xFF112233.toInt(), frame[199 * w + 399])
            assertTrue(session.pump())
            frame = frameOf(session)
            for (y in 0 until h) for (x in 0 until w) assertEquals(desktopPixel(w, x, y), frame[y * w + x])
        } finally { session.close() }
    }

    // ---- bounds ----

    @Test fun aSevenSixtyEightyByFortyThreeTwentyScreenPassesTheSizeChecks() {
        // 7680 x 4320 x 4 = 132,710,400 bytes: the biggest rectangle the console is meant for. With no payload behind the header
        // the rectangle is accepted, and the stream then ends early: the failure is not about size.
        val script = RecordingScript(VncTranscript().hello(7680, 4320).update(vncRect(0, 0, 7680, 4320, 6, ByteArray(4))).bytes())
        val session = open(script)
        try {
            val failure = assertFailsWith<IllegalStateException> { session.pump() }
            assertEquals("The console zlib data ended early.", failure.message)
        } finally { session.close() }
    }

    @Test fun aFramebufferOverTheHardBoundIsRefusedAtTheHello() {
        val script = RecordingScript(VncTranscript().hello(8193, 100).bytes())
        val failure = assertFailsWith<IllegalStateException> { VncSession(script).handshake("password") }
        assertEquals("The console dimensions are invalid.", failure.message)
    }

    @Test fun aRectangleOutsideTheFramebufferIsRefusedBeforeAnyOfItsBytesAreRead() {
        val w = 640; val h = 480
        for (rect in listOf(Triple(600, 0, 100 to 10), Triple(0, 470, 10 to 20), Triple(0, 0, 0 to 5), Triple(0, 0, 641 to 480))) {
            val (x, y, size) = rect
            val script = RecordingScript(VncTranscript().hello(w, h).update(vncRect(x, y, size.first, size.second, 6, ByteArray(100_000))).bytes())
            val session = open(script)
            val helloEnd = script.consumed
            try {
                val failure = assertFailsWith<IllegalStateException>("$rect") { session.pump() }
                assertEquals("The console rectangle is invalid.", failure.message)
                // The update header and the rectangle's own header (4 x u16 and the encoding) were read: 16 bytes. Not the length field, not the payload.
                assertEquals(16, script.consumed - helloEnd, "bytes read after the hello")
            } finally { session.close() }
        }
    }

    @Test fun aCompressedLengthBeyondWhatTheRectangleCouldNeedIsRefused() {
        val w = 64; val h = 64                       // 16,384 bytes of pixels: at most 16,384 + 256 + 65,536 compressed
        val script = RecordingScript(VncTranscript().hello(w, h)
            .update(vncRect(0, 0, w, h, 6, java.nio.ByteBuffer.allocate(4).putInt(16_384 + 256 + 65_536 + 1).array())).bytes())
        val session = open(script)
        try {
            assertEquals("The console message is too large.", assertFailsWith<IllegalStateException> { session.pump() }.message)
        } finally { session.close() }
        // A negative length (the high bit of the u32) is refused too.
        val negative = RecordingScript(VncTranscript().hello(w, h).update(vncRect(0, 0, w, h, 6, byteArrayOf(-1, 0, 0, 0))).bytes())
        val other = open(negative)
        try { assertEquals("The console message is too large.", assertFailsWith<IllegalStateException> { other.pump() }.message) } finally { other.close() }
    }

    @Test fun zlibDataThatIsTooShortTooLongOrCorruptIsRefusedWithASentence() {
        fun failure(width: Int, height: Int, payload: ByteArray): String? {
            val script = RecordingScript(VncTranscript().hello(8, 8).update(vncRect(0, 0, width, height, 6, payload)).bytes())
            val session = open(script)
            try { return assertFailsWith<IllegalStateException> { session.pump() }.message } finally { session.close() }
        }
        val two = deflateRect(2, 1, Deflater()) { x, _ -> 0xFF000000.toInt() or x }       // 8 bytes of pixels
        assertEquals("The console zlib data is longer than its rectangle.", failure(1, 1, vncZlibPayload(two)))
        val sixteen = deflateRect(4, 1, Deflater()) { x, _ -> 0xFF000000.toInt() or x }
        assertEquals("The console zlib data ended early.", failure(8, 1, vncZlibPayload(sixteen)))
        assertEquals("The console zlib data ended early.", failure(2, 1, vncZlibPayload(ByteArray(0))))
        assertEquals("The console zlib data is invalid.", failure(2, 1, vncZlibPayload(ByteArray(40) { (it * 37 + 11).toByte() })))
    }

    // ---- CopyRect: a move that needs no copy of the rectangle ----

    @Test fun copyRectBehavesLikeAMoveThroughATemporaryCopyWhateverTheOverlap() {
        val w = 40; val h = 30
        val random = Random(21)
        val start = IntArray(w * h) { random.nextInt() or (255 shl 24) }
        val raw = ByteArray(w * h * 4); start.forEachIndexed { i, argb -> putPixel(raw, i * 4, argb) }
        repeat(400) {
            val rw = random.nextInt(1, w + 1); val rh = random.nextInt(1, h + 1)
            val sx = random.nextInt(0, w - rw + 1); val sy = random.nextInt(0, h - rh + 1)
            val x = random.nextInt(0, w - rw + 1); val y = random.nextInt(0, h - rh + 1)
            val expected = start.copyOf()
            val before = start.copyOf()                       // the source as it was before the move
            for (row in 0 until rh) for (col in 0 until rw) expected[(y + row) * w + x + col] = before[(sy + row) * w + sx + col]
            val copy = java.nio.ByteBuffer.allocate(4).putShort(sx.toShort()).putShort(sy.toShort()).array()
            val script = RecordingScript(VncTranscript().hello(w, h)
                .update(vncRect(0, 0, w, h, 0, raw)).update(vncRect(x, y, rw, rh, 1, copy)).bytes())
            val session = open(script)
            try {
                assertTrue(session.pump()); assertTrue(session.pump())
                assertTrue(expected.contentEquals(frameOf(session)), "copy ${rw}x$rh from ($sx,$sy) to ($x,$y)")
            } finally { session.close() }
        }
    }

    @Test fun aCopyRectOutsideTheFramebufferIsRefused() {
        for ((x, y, sx, sy) in listOf(listOf(0, 0, 35, 0), listOf(0, 0, 0, 25), listOf(35, 0, 0, 0))) {
            val script = RecordingScript(VncTranscript().hello(40, 30)
                .update(vncRect(x, y, 10, 10, 1, java.nio.ByteBuffer.allocate(4).putShort(sx.toShort()).putShort(sy.toShort()).array())).bytes())
            val session = open(script)
            try { assertEquals("The console rectangle is invalid.", assertFailsWith<IllegalStateException> { session.pump() }.message) } finally { session.close() }
        }
    }
}
