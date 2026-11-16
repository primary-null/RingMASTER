package local.ringleader.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.Deflater

/*
 * Fixtures for the big-screen VNC tests: a scripted RFB server (bytes the client reads), and encoders for the rectangles it
 * sends. Local data only; nothing here talks to a network.
 */

/**
 * The bytes a server sends. [read] allocates as any plain channel does; [readInto] copies into the caller's buffer without
 * allocating, as the live socket does. [maxRequest] is the largest single read asked for at or after [watchFrom].
 */
internal class RecordingScript(private val bytes: ByteArray) : RfbChannel {
    private var at = 0
    var maxRequest = 0; private set
    var watchFrom = Int.MAX_VALUE
    val writes = mutableListOf<ByteArray>()
    val consumed: Int get() = at
    private fun note(n: Int) { if (at >= watchFrom) maxRequest = maxOf(maxRequest, n) }
    override fun read(n: Int): ByteArray { check(at + n <= bytes.size) { VNC_LOST }; note(n); return bytes.copyOfRange(at, at + n).also { at += n } }
    override fun readInto(dest: ByteArray, offset: Int, length: Int) {
        check(at + length <= bytes.size) { VNC_LOST }
        note(length); bytes.copyInto(dest, offset, at, at + length); at += length
    }
    override fun write(bytes: ByteArray) { writes += bytes }
    override fun close() = Unit
}

/** Server hello for a [width] x [height] screen, then framebuffer updates. */
internal class VncTranscript {
    private val raw = ByteArrayOutputStream()
    private val data = DataOutputStream(raw)
    fun hello(width: Int, height: Int) = apply {
        data.writeBytes("RFB 003.008\n"); data.writeByte(1); data.writeByte(2); data.write(ByteArray(16)); data.writeInt(0)
        data.writeShort(width); data.writeShort(height); data.write(ByteArray(16)); data.writeInt(4); data.writeBytes("test")
    }
    fun update(vararg rects: ByteArray) = apply {
        data.writeByte(0); data.writeByte(0); data.writeShort(rects.size)
        rects.forEach { data.write(it) }
    }
    fun bytes(): ByteArray = raw.toByteArray()
}

internal fun vncRect(x: Int, y: Int, w: Int, h: Int, encoding: Int, payload: ByteArray = ByteArray(0)): ByteArray {
    val out = ByteArrayOutputStream(); val data = DataOutputStream(out)
    data.writeShort(x); data.writeShort(y); data.writeShort(w); data.writeShort(h); data.writeInt(encoding); data.write(payload)
    return out.toByteArray()
}

/** The payload of a zlib rectangle: the length of [compressed], then [compressed]. */
internal fun vncZlibPayload(compressed: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(); DataOutputStream(out).apply { writeInt(compressed.size); write(compressed) }
    return out.toByteArray()
}

/** [argb] as the four bytes a server sends for it (B, G, R, pad), at [at] in [out]. */
internal fun putPixel(out: ByteArray, at: Int, argb: Int) {
    out[at] = argb.toByte(); out[at + 1] = (argb shr 8).toByte(); out[at + 2] = (argb shr 16).toByte(); out[at + 3] = 0
}

/**
 * Compresses a [w] x [h] rectangle row by row, so the raw bytes are never held whole (a test for a big screen must not need
 * a second copy of it). One [deflater] is the connection's stream: every rectangle ends in a sync flush, as QEMU's does, and
 * the next continues the same stream.
 */
internal fun deflateRect(w: Int, h: Int, deflater: Deflater, pixel: (Int, Int) -> Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(65536)
    val row = ByteArray(w * 4)
    for (y in 0 until h) {
        for (x in 0 until w) putPixel(row, x * 4, pixel(x, y))
        deflater.setInput(row)
        while (!deflater.needsInput()) { val n = deflater.deflate(buffer, 0, buffer.size, Deflater.NO_FLUSH); out.write(buffer, 0, n) }
    }
    while (true) { val n = deflater.deflate(buffer, 0, buffer.size, Deflater.SYNC_FLUSH); out.write(buffer, 0, n); if (n < buffer.size) break }
    return out.toByteArray()
}

/** A busy desktop: a gradient title bar, a light window, and lines of dark "text" in it. */
internal fun desktopPixel(w: Int, x: Int, y: Int): Int {
    var r = 0x20; var g = 0x30; var b = 0x40
    if (y < 40) { b = 0x80 + x * 127 / w; g = 0x60; r = 0x30 }
    else if (x in 300 until w - 300 && y in 200 until 2000) {
        r = 0xf0; g = 0xf0; b = 0xf0
        if (y % 24 in 6..16 && (x * 31 + y * 17) % 3 == 0) { r = 0x10; g = 0x10; b = 0x10 }
    }
    return (255 shl 24) or (r shl 16) or (g shl 8) or b
}
