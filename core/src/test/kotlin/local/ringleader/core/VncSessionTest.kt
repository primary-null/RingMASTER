package local.ringleader.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.test.*

class VncSessionTest {
    private class Script(bytes: ByteArray) : RfbChannel {
        private val input = bytes.inputStream()
        val writes = mutableListOf<ByteArray>()
        override fun read(n: Int): ByteArray {
            val got = input.readNBytes(n)
            if (got.size != n) error(VNC_LOST)
            return got
        }
        override fun write(bytes: ByteArray) { writes += bytes }
        override fun close() = Unit
    }

    private class Bin {
        val raw = ByteArrayOutputStream()
        private val data = DataOutputStream(raw)
        fun str(value: String) { data.writeBytes(value) }
        fun u8(value: Int) { data.writeByte(value) }
        fun u16(value: Int) { data.writeShort(value) }
        fun i32(value: Int) { data.writeInt(value) }
        fun bytes(value: ByteArray) { data.write(value) }
    }

    private fun handshake(width: Int = 4, height: Int = 2, name: String = "test"): Bin = Bin().apply {
        str("RFB 003.008\n"); u8(1); u8(2); bytes(ByteArray(16) { it.toByte() }); i32(0)
        u16(width); u16(height); bytes(ByteArray(16)); i32(name.length); str(name)
    }

    private fun rect(x: Int, y: Int, w: Int, h: Int, encoding: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val bin = Bin()
        bin.u16(x); bin.u16(y); bin.u16(w); bin.u16(h); bin.i32(encoding); bin.bytes(payload)
        return bin.raw.toByteArray()
    }

    private fun frame(vararg rects: ByteArray): ByteArray {
        val bin = Bin()
        bin.u8(0); bin.u8(0); bin.u16(rects.size)
        rects.forEach { bin.bytes(it) }
        return bin.raw.toByteArray()
    }

    /** B,G,R,pad bytes for an opaque pixel. */
    private fun px(r: Int, g: Int, b: Int) = byteArrayOf(b.toByte(), g.toByte(), r.toByte(), 0)
    private fun argb(r: Int, g: Int, b: Int) = (255 shl 24) or (r shl 16) or (g shl 8) or b

    private fun zlibChunks(vararg parts: ByteArray): List<ByteArray> {
        val deflater = Deflater()
        try {
            return parts.map { part ->
                deflater.setInput(part)
                val buf = ByteArray(part.size + 128)
                val n = deflater.deflate(buf, 0, buf.size, Deflater.SYNC_FLUSH)
                check(n > 0)
                buf.copyOf(n)
            }
        } finally { deflater.end() }
    }

    @Test fun handshakeSecurityResultAndServerInit() {
        val channel = Script(handshake(name = "vm").raw.toByteArray())
        val session = VncSession(channel)
        session.handshake("password")
        assertEquals(4, session.width); assertEquals(2, session.height); assertTrue(session.ready)
        assertEquals("RFB 003.008\n", channel.writes[0].decodeToString())
        assertContentEquals(byteArrayOf(2), channel.writes[1])
        assertEquals("b866924125c8eebb9debc1db61c538e2", channel.writes[2].joinToString("") { "%02x".format(it) })
        assertContentEquals(vncResponse("password", ByteArray(16) { it.toByte() }), channel.writes[2])
        assertContentEquals(byteArrayOf(1), channel.writes[3])
        assertContentEquals(byteArrayOf(0, 0, 0, 0, 32, 24, 0, 1, 0, -1, 0, -1, 0, -1, 16, 8, 0, 0, 0, 0), channel.writes[4])
        val encodings = ByteBuffer.wrap(channel.writes[5])
        assertEquals(2, encodings.get().toInt()); encodings.get()
        assertEquals(6, encodings.short.toInt())
        // QEMU extended key events (-258) are offered; keysyms remain the fallback until the server confirms them.
        assertEquals(listOf(6, 1, 0, -223, -239, -258), List(6) { encodings.int })
        assertFalse(session.extendedKeys)
        val request = ByteBuffer.wrap(channel.writes[6])
        assertEquals(3, request.get().toInt()); assertEquals(0, request.get().toInt())
        assertEquals(0, request.short.toInt()); assertEquals(0, request.short.toInt())
        assertEquals(4, request.short.toInt()); assertEquals(2, request.short.toInt())
        assertFalse(session.toString().contains("password"))
        session.close(); assertNull(session.reason); session.close()
    }

    @Test fun passwordFailureHasSafeMessage() {
        val bin = Bin().apply {
            str("RFB 003.008\n"); u8(1); u8(2); bytes(ByteArray(16)); i32(1); i32(6); str("denied")
        }
        val session = VncSession(Script(bin.raw.toByteArray()))
        assertEquals("The console refused the one-time password.", assertFailsWith<IllegalStateException> { session.handshake("secret") }.message)
        session.close()
    }

    @Test fun rawCopyRectZlibDesktopSizeAndCursor() {
        val raw = ByteArray(4 * 2 * 4)
        for (y in 0 until 2) for (x in 0 until 4) px(x + y * 10, 40, 50).copyInto(raw, (y * 4 + x) * 4)
        val zlibA = px(3, 2, 1) + px(6, 5, 4)
        val zlibB = px(9, 8, 7) + px(12, 11, 10)
        val chunks = zlibChunks(zlibA, zlibB)
        val fresh = Inflater()
        fresh.setInput(chunks[1])
        val alone = runCatching { fresh.inflate(ByteArray(zlibB.size)) }.getOrDefault(-1)
        assertTrue(alone < zlibB.size)
        fresh.end()
        val cursorPixels = px(3, 2, 1) + px(6, 5, 4) + px(9, 8, 7) + px(12, 11, 10)
        val script = Bin().apply {
            bytes(handshake().raw.toByteArray())
            u8(2)
            u8(3); u8(0); u8(0); u8(0); i32(4); str("text")
            bytes(frame(rect(0, 0, 4, 2, 0, raw)))
            bytes(frame(rect(2, 0, 2, 2, 1, Bin().apply { u16(0); u16(0) }.raw.toByteArray())))
            bytes(frame(
                rect(0, 0, 2, 1, 6, Bin().apply { i32(chunks[0].size); bytes(chunks[0]) }.raw.toByteArray()),
                rect(2, 0, 2, 1, 6, Bin().apply { i32(chunks[1].size); bytes(chunks[1]) }.raw.toByteArray()),
            ))
            bytes(frame(rect(0, 0, 6, 3, -223)))
            bytes(frame(rect(1, 2, 2, 2, -239, cursorPixels + byteArrayOf(0x80.toByte(), 0xC0.toByte()))))
        }
        val channel = Script(script.raw.toByteArray())
        val session = VncSession(channel)
        session.handshake("password")
        val writesAtHandshake = channel.writes.size
        assertTrue(session.pump())
        assertTrue(session.pump())
        assertEquals(writesAtHandshake, channel.writes.size)
        assertTrue(session.pump())
        val frame = IntArray(8)
        assertTrue(session.readInto(frame).copied)
        for (y in 0 until 2) for (x in 0 until 4) assertEquals(argb(x + y * 10, 40, 50), frame[y * 4 + x])
        assertEquals(1, channel.writes[writesAtHandshake][1].toInt())
        assertTrue(session.pump())
        session.readInto(frame)
        assertEquals(argb(0, 40, 50), frame[2]); assertEquals(argb(1, 40, 50), frame[3])
        assertEquals(argb(10, 40, 50), frame[4 + 2]); assertEquals(argb(11, 40, 50), frame[4 + 3])
        assertTrue(session.pump())
        session.readInto(frame)
        assertEquals(argb(3, 2, 1), frame[0]); assertEquals(argb(6, 5, 4), frame[1])
        assertEquals(argb(9, 8, 7), frame[2]); assertEquals(argb(12, 11, 10), frame[3])
        assertEquals(argb(10, 40, 50), frame[4])
        assertTrue(session.pump())
        assertEquals(6, session.width); assertEquals(3, session.height)
        val grown = IntArray(6 * 3)
        val snap = session.readInto(grown)
        assertTrue(snap.copied); assertEquals(6, snap.width); assertEquals(3, snap.height)
        assertEquals(argb(3, 2, 1), grown[0]); assertEquals(0, grown[5 + 2 * 6])
        assertFalse(session.readInto(IntArray(4)).copied)
        assertTrue(session.pump())
        val cursor = session.cursor
        assertNotNull(cursor)
        assertEquals(2, cursor.width); assertEquals(2, cursor.height)
        assertEquals(1, cursor.hotspotX); assertEquals(2, cursor.hotspotY)
        assertEquals(argb(3, 2, 1), cursor.argb[0]); assertEquals(0, cursor.argb[1])
        assertEquals(argb(9, 8, 7), cursor.argb[2]); assertEquals(argb(12, 11, 10), cursor.argb[3])
        assertFalse(cursor.toString().contains("px"))
        session.key(true, 0xff0d)
        session.pointer(5, 256, 16)
        assertContentEquals(vncKeyEvent(true, 0xff0d), channel.writes[channel.writes.lastIndex - 1])
        // The session clamps the pointer into the framebuffer (6×3 after DesktopSize).
        assertContentEquals(vncPointerEvent(5, 5, 2), channel.writes.last())
        assertContentEquals(byteArrayOf(4, 1, 0, 0, 0, 0, 0xff.toByte(), 0x0d), vncKeyEvent(true, 0xff0d))
        assertContentEquals(byteArrayOf(4, 0, 0, 0, 0, 0, 0xff.toByte(), 0x0d), vncKeyEvent(false, 0xff0d))
        assertContentEquals(byteArrayOf(5, 5, 1, 0, 0, 16), vncPointerEvent(5, 256, 16))
        session.close()
        session.key(false, 0xff0d)
        assertNull(session.reason)
    }

    @Test fun droppedChannelEndsWithConnectionLost() {
        val session = VncSession(Script(handshake().raw.toByteArray()))
        session.handshake("password")
        assertFalse(session.pump())
        assertEquals(VNC_LOST, session.reason)
    }

    @Test fun keysymsUseCodePointAndNamedTable() {
        assertEquals(0x61, vncKeysym(VncKey.Character, 'a'.code))
        assertEquals(0x41, vncKeysym(VncKey.Character, 'A'.code))
        assertEquals(0x20, vncKeysym(VncKey.Character, ' '.code))
        assertEquals(0xe9, vncKeysym(VncKey.Character, 'é'.code))
        assertEquals(0x01000000 + 0x1F600, vncKeysym(VncKey.Character, 0x1F600))
        assertNull(vncKeysym(VncKey.Character, 0))
        assertNull(vncKeysym(VncKey.Character, '\n'.code))
        assertEquals(0xff0d, vncKeysym(VncKey.Enter, '\n'.code))
        assertEquals(0xff1b, vncKeysym(VncKey.Escape, 0))
        assertEquals(0xff08, vncKeysym(VncKey.Backspace, 0))
        assertEquals(0xff09, vncKeysym(VncKey.Tab, 0))
        assertEquals(0xffff, vncKeysym(VncKey.Delete, 0))
        assertEquals(0xff63, vncKeysym(VncKey.Insert, 0))
        assertEquals(0xff50, vncKeysym(VncKey.Home, 0))
        assertEquals(0xff57, vncKeysym(VncKey.End, 0))
        assertEquals(0xff55, vncKeysym(VncKey.PageUp, 0))
        assertEquals(0xff56, vncKeysym(VncKey.PageDown, 0))
        assertEquals(listOf(0xff51, 0xff52, 0xff53, 0xff54), listOf(VncKey.Left, VncKey.Up, VncKey.Right, VncKey.Down).map { vncKeysym(it, 0) })
        val fn = listOf(VncKey.F1, VncKey.F2, VncKey.F3, VncKey.F4, VncKey.F5, VncKey.F6, VncKey.F7, VncKey.F8, VncKey.F9, VncKey.F10, VncKey.F11, VncKey.F12)
        fn.forEachIndexed { index, key -> assertEquals(0xffbe + index, vncKeysym(key, 0)) }
        assertEquals(0xffe1, vncKeysym(VncKey.ShiftLeft, 0)); assertEquals(0xffe2, vncKeysym(VncKey.ShiftRight, 0))
        assertEquals(0xffe3, vncKeysym(VncKey.ControlLeft, 0)); assertEquals(0xffe4, vncKeysym(VncKey.ControlRight, 0))
        assertEquals(0xffe9, vncKeysym(VncKey.AltLeft, 0)); assertEquals(0xffea, vncKeysym(VncKey.AltRight, 0))
        assertEquals(0xffe7, vncKeysym(VncKey.MetaLeft, 0)); assertEquals(0xffe8, vncKeysym(VncKey.MetaRight, 0))
        assertEquals(0xffe5, vncKeysym(VncKey.CapsLock, 0)); assertEquals(0xff7f, vncKeysym(VncKey.NumLock, 0))
        assertEquals(0xff61, vncKeysym(VncKey.PrintScreen, 0)); assertEquals(0xff13, vncKeysym(VncKey.Pause, 0))
        listOf(VncKey.Keypad0, VncKey.Keypad1, VncKey.Keypad2, VncKey.Keypad3, VncKey.Keypad4, VncKey.Keypad5, VncKey.Keypad6, VncKey.Keypad7, VncKey.Keypad8, VncKey.Keypad9)
            .forEachIndexed { index, key -> assertEquals(0xffb0 + index, vncKeysym(key, 0)) }
        assertEquals(0xff8d, vncKeysym(VncKey.KeypadEnter, 0))
        assertEquals(0xffab, vncKeysym(VncKey.KeypadAdd, 0)); assertEquals(0xffad, vncKeysym(VncKey.KeypadSubtract, 0))
        assertEquals(0xffaa, vncKeysym(VncKey.KeypadMultiply, 0)); assertEquals(0xffaf, vncKeysym(VncKey.KeypadDivide, 0))
        assertEquals(0xffae, vncKeysym(VncKey.KeypadDecimal, 0)); assertEquals(0xffbd, vncKeysym(VncKey.KeypadEqual, 0))
    }

    @Test fun spiceDisplayUsesQxlOnly() {
        assertTrue(spiceDisplayAvailable("qxl")); assertTrue(spiceDisplayAvailable("qxl2,memory=32"))
        assertTrue(spiceDisplayAvailable("type=qxl,memory=16"))
        assertFalse(spiceDisplayAvailable(null)); assertFalse(spiceDisplayAvailable("std")); assertFalse(spiceDisplayAvailable("virtio"))
    }

    @Test fun guardsRunBeforeAnyNetwork() {
        val remote = "https://example.invalid:8006"
        Connection(remote, "test@pve!test", "secret", "").use { connection ->
            val guest = Guest(remote, 100, "test", "node", "VM", "stopped")
            assertEquals(VNC_GUEST_STOPPED, assertFailsWith<IllegalArgumentException> { PveClient().consoleSession(connection, guest) }.message)
            assertEquals(VNC_NOT_VM, assertFailsWith<IllegalArgumentException> { PveClient().consoleSession(connection, guest.copy(kind = "Container", status = "running")) }.message)
            assertEquals(VNC_GUEST_STOPPED, assertFailsWith<IllegalArgumentException> { PveClient().consoleSession(connection, guest.copy(status = "running", template = true)) }.message)
        }
    }

    @Test fun freshInflaterCannotContinueTheSecondRectangle() {
        val chunks = zlibChunks(ByteArray(8) { it.toByte() }, ByteArray(8) { (it + 20).toByte() })
        val inflater = Inflater()
        inflater.setInput(chunks[1])
        val failed = runCatching { inflater.inflate(ByteArray(8)) }.exceptionOrNull()
        assertTrue(failed is DataFormatException || inflater.remaining == chunks[1].size)
        inflater.end()
    }
}
