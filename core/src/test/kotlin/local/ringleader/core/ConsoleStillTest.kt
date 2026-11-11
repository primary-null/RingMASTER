package local.ringleader.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

class ConsoleStillTest {
    private class Script(bytes: ByteArray) : RfbChannel {
        val input = bytes.inputStream()
        val writes = mutableListOf<ByteArray>()
        override fun read(n: Int) = input.readNBytes(n).also { check(it.size == n) }
        override fun write(bytes: ByteArray) { writes += bytes }
        override fun close() = Unit
    }
    private fun server(version: String = "008", auth: Boolean = true, fail: Boolean = false): Script {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeBytes("RFB 003.$version\n")
            if (version == "003") writeInt(if (auth) 2 else 1) else { writeByte(1); writeByte(if (auth) 2 else 1) }
            if (auth) write(ByteArray(16) { it.toByte() })
            if (auth || version == "008") writeInt(if (fail) 1 else 0)
            if (fail) { writeInt(6); writeBytes("denied") } else {
                writeShort(4); writeShort(3); write(ByteArray(16)); writeInt(0)
                writeByte(2) // Bell
                writeByte(3); write(ByteArray(3)); writeInt(4); writeBytes("text")
                writeByte(1); writeByte(0); writeShort(0); writeShort(1); write(ByteArray(6))
                // Two updates; coverage must survive update boundaries.
                for (x in listOf(0, 2)) {
                    writeByte(0); writeByte(0); writeShort(1)
                    writeShort(x); writeShort(0); writeShort(2); writeShort(3); writeInt(0)
                    repeat(3) { y -> repeat(2) { col -> writeByte(x + col); writeByte(y); writeByte(100); writeByte(0) } }
                }
            }
        }
        return Script(out.toByteArray())
    }
    @Test fun authenticatedFrameIsReadOnlyAndShared() {
        val channel = server()
        val frame = captureRfbFrame(channel, "password")
        assertEquals(4, frame.width); assertEquals(3, frame.height)
        repeat(3) { y -> repeat(4) { x -> assertEquals(0xff640000.toInt() or (y shl 8) or x, frame.pixels[y * 4 + x]) } }
        // Independent known answer: DES ECB, key 0e86ceceeef64e26, plaintext 000102...0f.
        assertEquals("b866924125c8eebb9debc1db61c538e2", channel.writes[2].joinToString("") { "%02x".format(it) })
        assertContentEquals(byteArrayOf(1), channel.writes[3])
        assertEquals(listOf(0, 2, 3), channel.writes.drop(4).map { it[0].toInt() })
    }
    @Test fun version33WithNoAuthentication() {
        val channel = server("003", false)
        assertEquals(12, captureRfbFrame(channel, "").pixels.size)
        assertContentEquals(byteArrayOf(1), channel.writes[1])
        assertEquals(listOf(0, 2, 3), channel.writes.drop(2).map { it[0].toInt() })
    }
    @Test fun passwordFailureHasSafeMessage() {
        assertEquals("The console refused the one-time password.", assertFailsWith<IllegalStateException> { captureRfbFrame(server(fail = true), "secret") }.message)
    }
    @Test fun blankDetection() {
        assertTrue(frameStill(RfbFrame(10, 10, IntArray(100))).blank)
        assertTrue(frameStill(RfbFrame(10, 10, IntArray(100) { 0x808080 })).blank)
        assertFalse(frameStill(RfbFrame(10, 10, IntArray(100) { if (it % 2 == 0) 0xffffff else 0 })).blank)
    }
    @Test fun pngIsScaledAndDiagnosticTextOmitsBytes() {
        val still = frameStill(RfbFrame(1920, 1080, IntArray(1920 * 1080) { it }))
        val image = ImageIO.read(still.png.inputStream())
        assertEquals(960, image.width); assertEquals(540, image.height)
        assertEquals(image.width, still.width); assertEquals(image.height, still.height)
        assertFalse(still.toString().contains("png="))
    }
    @Test fun proxyRequestsJson() {
        assertEquals("""{"websocket":1,"generate-password":1}""", consoleStillRequestBody().decodeToString())
    }
    @Test fun guardsRunBeforeAnyNetwork() {
        val remote = "https://example.invalid:8006"
        Connection(remote, "test@pve!test", "secret", "").use { connection ->
            val guest = Guest(remote, 100, "test", "node", "VM", "stopped")
            assertEquals("Start the VM to capture its screen.", assertFailsWith<IllegalArgumentException> { PveClient().consoleStill(connection, guest) }.message)
            assertEquals("Screen captures are for QEMU VMs.", assertFailsWith<IllegalArgumentException> { PveClient().consoleStill(connection, guest.copy(kind = "Container")) }.message)
            assertFailsWith<IllegalArgumentException> { PveClient().consoleStill(connection, guest.copy(status = "running", template = true)) }
        }
    }
}
