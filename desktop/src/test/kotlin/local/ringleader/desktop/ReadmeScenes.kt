@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package local.ringleader.desktop

import local.ringleader.core.RfbChannel
import local.ringleader.core.SecretVault
import local.ringleader.core.VaultStatus
import local.ringleader.core.VncSession
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/*
 * Helpers for ReadmeShotsTest. Everything here is invented: no server, no credential store, no real screen.
 */

/** A vault for the sign-in picture. It reports itself available and keeps nothing; the real credential store is never touched. */
internal class ReadmeVault(override val name: String) : SecretVault {
    override fun status() = VaultStatus.Available
    override fun store(profileId: String, login: String, label: String, secret: CharArray) = Unit
    override fun read(profileId: String): CharArray? = null
    override fun forget(profileId: String) = Unit
    override fun forgetAll() = Unit
}

/** One line of an invented text console: runs of (text, colour). */
internal class ConsoleLine(vararg val runs: Pair<String, Color>)

internal val consoleGrey = Color(0xAA, 0xAA, 0xAA)
internal val consoleGreen = Color(0x55, 0xFF, 0x55)
internal val consoleBlue = Color(0x55, 0x99, 0xFF)
internal val consoleWhite = Color(0xF0, 0xF0, 0xF0)

/** The bash prompt Debian draws for a user, in its colours. */
internal fun promptLine(user: String, host: String, command: String) = ConsoleLine(
    "$user@$host" to consoleGreen, ":" to consoleGrey, "~" to consoleBlue, "$ " to consoleGrey, command to consoleWhite)

internal fun plainLine(text: String = "") = ConsoleLine(text to consoleGrey)

/** Draws a text console the way a guest's framebuffer shows it: black, monospaced, a block cursor on the last line. */
internal fun drawConsole(width: Int, height: Int, lines: List<ConsoleLine>): IntArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    try {
        g.color = Color.BLACK
        g.fillRect(0, 0, width, height)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = Font(Font.MONOSPACED, Font.PLAIN, 16)
        val metrics = g.fontMetrics
        val lineHeight = 20
        var y = 12 + metrics.ascent
        var cursorX = 0
        for (line in lines) {
            var x = 10
            for ((text, color) in line.runs) {
                g.color = color
                g.drawString(text, x, y)
                x += metrics.stringWidth(text)
            }
            cursorX = x
            y += lineHeight
        }
        g.color = consoleGrey
        g.fillRect(cursorX, y - lineHeight - metrics.ascent + 2, metrics.charWidth('M'), metrics.ascent + metrics.descent - 2)
    } finally { g.dispose() }
    return image.getRGB(0, 0, width, height, null, 0, width)
}

/**
 * A console session that already holds [argb] as its picture, made the way core's solidFrameSession is: a scripted RFB
 * stream (version, VNC auth, ServerInit, one raw framebuffer update) with no socket behind it and no reader thread.
 */
internal fun pictureSession(width: Int, height: Int, argb: IntArray): VncSession {
    require(argb.size == width * height)
    val raw = ByteArray(width * height * 4)
    var i = 0
    for (pixel in argb) {
        raw[i++] = (pixel and 255).toByte(); raw[i++] = (pixel ushr 8 and 255).toByte(); raw[i++] = (pixel ushr 16 and 255).toByte(); raw[i++] = 0
    }
    val bin = ByteArrayOutputStream()
    val data = DataOutputStream(bin)
    data.writeBytes("RFB 003.008\n")
    data.writeByte(1); data.writeByte(2); data.write(ByteArray(16)); data.writeInt(0)
    data.writeShort(width); data.writeShort(height); data.write(ByteArray(16)); data.writeInt(4); data.writeBytes("demo")
    data.writeByte(0); data.writeByte(0); data.writeShort(1)
    data.writeShort(0); data.writeShort(0); data.writeShort(width); data.writeShort(height); data.writeInt(0); data.write(raw)
    data.flush()
    val session = VncSession(object : RfbChannel {
        private val input = bin.toByteArray().inputStream()
        override fun read(n: Int): ByteArray {
            val got = input.readNBytes(n)
            if (got.size != n) error("The invented console stream ended.")
            return got
        }
        override fun write(bytes: ByteArray) = Unit
        override fun close() = Unit
    })
    session.handshake("demo")
    check(session.pump())
    return session
}

/** The PNG chunk types in [file], in order. */
internal fun pngChunks(file: File): List<String> = DataInputStream(file.inputStream().buffered()).use { input ->
    val signature = ByteArray(8).also { input.readFully(it) }
    check(signature.contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) { "${file.name} is not a PNG" }
    val types = mutableListOf<String>()
    while (true) {
        val length = input.readInt()
        val type = ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)
        types += type
        input.skipNBytes(length.toLong() + 4)
        if (type == "IEND") break
    }
    types
}

/** Chunks the publish gate refuses: text, compressed text, international text, EXIF and the modification time. */
internal val forbiddenPngChunks = setOf("tEXt", "zTXt", "iTXt", "eXIf", "tIME")
