package local.ringleader.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import javax.net.ssl.SSLException

const val VNC_NOT_VM = "Consoles are for QEMU VMs."
const val VNC_GUEST_STOPPED = "The guest is stopped."
const val VNC_NO_PERMISSION = "This token lacks VM.Console on this VM."
const val VNC_LOST = "The connection was lost."

/** True when [requireSpiceDisplay] accepts this VM config `vga` value (qxl, including options after a comma). */
fun spiceDisplayAvailable(vga: String?): Boolean = runCatching { requireSpiceDisplay(vga) }.isSuccess

/**
 * Desktop key that is not already a Unicode code point. Printable characters use [VncKey.Character]
 * plus the event code point.
 */
enum class VncKey {
    Character,
    Enter, Escape, Backspace, Tab, Delete, Insert, Home, End, PageUp, PageDown,
    Left, Up, Right, Down,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    ShiftLeft, ShiftRight, ControlLeft, ControlRight, AltLeft, AltRight, MetaLeft, MetaRight,
    CapsLock, NumLock, PrintScreen, Pause,
    Keypad0, Keypad1, Keypad2, Keypad3, Keypad4, Keypad5, Keypad6, Keypad7, Keypad8, Keypad9,
    KeypadEnter, KeypadAdd, KeypadSubtract, KeypadMultiply, KeypadDivide, KeypadDecimal, KeypadEqual,
}

/**
 * X11 keysym for a desktop key. Latin-1 code points (U+0020..U+00FF, except DEL) are the keysym.
 * Other non-zero code points are `0x01000000 + codePoint`. Named keys win over the code point.
 */
fun vncKeysym(key: VncKey, codePoint: Int): Int? = when (key) {
    VncKey.Character -> when {
        codePoint in 0x20..0x7E || codePoint in 0xA0..0xFF -> codePoint
        codePoint in 0x100..0x10FFFF -> 0x01000000 + codePoint
        else -> null
    }
    VncKey.Enter -> 0xff0d
    VncKey.Escape -> 0xff1b
    VncKey.Backspace -> 0xff08
    VncKey.Tab -> 0xff09
    VncKey.Delete -> 0xffff
    VncKey.Insert -> 0xff63
    VncKey.Home -> 0xff50
    VncKey.End -> 0xff57
    VncKey.PageUp -> 0xff55
    VncKey.PageDown -> 0xff56
    VncKey.Left -> 0xff51
    VncKey.Up -> 0xff52
    VncKey.Right -> 0xff53
    VncKey.Down -> 0xff54
    VncKey.F1 -> 0xffbe
    VncKey.F2 -> 0xffbf
    VncKey.F3 -> 0xffc0
    VncKey.F4 -> 0xffc1
    VncKey.F5 -> 0xffc2
    VncKey.F6 -> 0xffc3
    VncKey.F7 -> 0xffc4
    VncKey.F8 -> 0xffc5
    VncKey.F9 -> 0xffc6
    VncKey.F10 -> 0xffc7
    VncKey.F11 -> 0xffc8
    VncKey.F12 -> 0xffc9
    VncKey.ShiftLeft -> 0xffe1
    VncKey.ShiftRight -> 0xffe2
    VncKey.ControlLeft -> 0xffe3
    VncKey.ControlRight -> 0xffe4
    VncKey.AltLeft -> 0xffe9
    VncKey.AltRight -> 0xffea
    VncKey.MetaLeft -> 0xffe7
    VncKey.MetaRight -> 0xffe8
    VncKey.CapsLock -> 0xffe5
    VncKey.NumLock -> 0xff7f
    VncKey.PrintScreen -> 0xff61
    VncKey.Pause -> 0xff13
    VncKey.Keypad0 -> 0xffb0
    VncKey.Keypad1 -> 0xffb1
    VncKey.Keypad2 -> 0xffb2
    VncKey.Keypad3 -> 0xffb3
    VncKey.Keypad4 -> 0xffb4
    VncKey.Keypad5 -> 0xffb5
    VncKey.Keypad6 -> 0xffb6
    VncKey.Keypad7 -> 0xffb7
    VncKey.Keypad8 -> 0xffb8
    VncKey.Keypad9 -> 0xffb9
    VncKey.KeypadEnter -> 0xff8d
    VncKey.KeypadAdd -> 0xffab
    VncKey.KeypadSubtract -> 0xffad
    VncKey.KeypadMultiply -> 0xffaa
    VncKey.KeypadDivide -> 0xffaf
    VncKey.KeypadDecimal -> 0xffae
    VncKey.KeypadEqual -> 0xffbd
}

/** RFB KeyEvent. Big-endian keysym, down-flag 0 or 1. */
internal fun vncKeyEvent(down: Boolean, keysym: Int): ByteArray =
    ByteBuffer.allocate(8).put(4).put(if (down) 1 else 0).putShort(0).putInt(keysym).array()

/** RFB PointerEvent. [mask] bits 0..2 are buttons 1..3; bits 3..4 are wheel buttons 4..5. */
internal fun vncPointerEvent(mask: Int, x: Int, y: Int): ByteArray =
    ByteBuffer.allocate(6).put(5).put((mask and 255).toByte()).putShort(x.toShort()).putShort(y.toShort()).array()

/** Local cursor from the Cursor pseudo-encoding. [argb] uses 0 alpha where the mask bit is clear. */
class VncCursor(val width: Int, val height: Int, val hotspotX: Int, val hotspotY: Int, val argb: IntArray) {
    override fun toString() = "VncCursor(${width}x$height @$hotspotX,$hotspotY)"
    override fun equals(other: Any?): Boolean = other is VncCursor && width == other.width && height == other.height &&
        hotspotX == other.hotspotX && hotspotY == other.hotspotY && argb.contentEquals(other.argb)
    override fun hashCode(): Int = width * 31 + height + argb.contentHashCode()
}

/** Result of [VncSession.readInto]. [copied] is false when [dest] was too small; the sizes are still current. */
class VncSnapshot(val version: Long, val width: Int, val height: Int, val cursor: VncCursor?, val copied: Boolean)

interface VncConsoleGateway {
    suspend fun open(connection: Connection, guest: Guest): VncSession
}

class LiveVncConsoleGateway(private val client: PveClient = PveClient()) : VncConsoleGateway {
    override suspend fun open(connection: Connection, guest: Guest): VncSession =
        runInterruptible(Dispatchers.IO) { client.consoleSession(connection, guest) }
}

/**
 * Continuous RFB 3.8 client. Pixels are 32-bit little-endian true colour with red at shift 16, green at 8 and blue at 0,
 * so each pixel is an ARGB int (alpha forced on, because RFB has no alpha). One zlib [Inflater] lives for the connection.
 * ZRLE is not requested. Close is idempotent and does not by itself set [reason].
 */
class VncSession internal constructor(rawChannel: RfbChannel) : AutoCloseable {
    private val trace = Trace.begin(TraceSource.Console, "VNC session", session=true)
    private val channel = object : RfbChannel {
        override fun read(n: Int) = rawChannel.read(n).also { trace.bytes(bytesIn=it.size.toLong()) }
        override fun write(bytes: ByteArray) { rawChannel.write(bytes); trace.bytes(bytesOut=bytes.size.toLong()) }
        override fun close() = rawChannel.close()
    }
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()
    private val frameLock = Any()
    private val inflater = Inflater()
    private var zbuf = ByteArray(0)
    private var pixels = IntArray(0)
    private var thread: Thread? = null
    @Volatile var width: Int = 0
        private set
    @Volatile var height: Int = 0
        private set
    @Volatile var version: Long = 0
        private set
    @Volatile var ready: Boolean = false
        private set
    /** Plain end reason. Null while the session is open and after a clean [close]. */
    @Volatile var reason: String? = null
        private set
    @Volatile var cursor: VncCursor? = null
        private set
    /** Invoked on the reader thread after a frame, resize or cursor change. */
    @Volatile var onUpdate: (() -> Unit)? = null
    /** Invoked on the reader thread when the session ends with [reason]. */
    @Volatile var onClosed: ((String) -> Unit)? = null
    internal var http: HttpClient? = null
    internal var socket: WebSocket? = null

    /** Copies the current framebuffer into [dest] when it is large enough. Safe to call from the UI thread. */
    fun readInto(dest: IntArray): VncSnapshot = synchronized(frameLock) {
        val count = width * height
        val copied = dest.size >= count
        if (copied && count > 0) pixels.copyInto(dest, 0, 0, count)
        VncSnapshot(version, width, height, cursor, copied)
    }

    fun key(down: Boolean, keysym: Int) { send(vncKeyEvent(down, keysym)) }
    fun pointer(mask: Int, x: Int, y: Int) {
        if (width == 0 || height == 0) return
        send(vncPointerEvent(mask, x.coerceIn(0, width - 1), y.coerceIn(0, height - 1)))
    }

    internal fun handshake(password: String) {
        fun u8() = channel.read(1)[0].toInt() and 255
        fun u16(): Int { val b = channel.read(2); return ((b[0].toInt() and 255) shl 8) or (b[1].toInt() and 255) }
        fun i32() = ByteBuffer.wrap(channel.read(4)).int
        val version = channel.read(12).toString(Charsets.US_ASCII)
        check(version in listOf("RFB 003.003\n", "RFB 003.007\n", "RFB 003.008\n")) { "Unsupported console RFB version." }
        channel.write("RFB 003.008\n".toByteArray())
        val security = if (version == "RFB 003.003\n") i32() else {
            val types = channel.read(u8()).map { it.toInt() and 255 }
            (if (2 in types) 2 else if (1 in types) 1 else 0).also { if (it != 0) channel.write(byteArrayOf(it.toByte())) }
        }
        check(security == 1 || security == 2) { "The console offers no supported authentication method." }
        if (security == 2) channel.write(vncResponse(password, channel.read(16)))
        if (security == 2 || version == "RFB 003.008\n") {
            if (i32() != 0) {
                if (version == "RFB 003.008\n") skip(i32())
                error("The console refused the one-time password.")
            }
        }
        channel.write(byteArrayOf(1))
        val w = u16(); val h = u16()
        check(w in 1..8192 && h in 1..8192) { "The console dimensions are invalid." }
        channel.read(16)
        skip(i32())
        synchronized(frameLock) {
            pixels = IntArray(w * h)
            width = w
            height = h
        }
        // 32bpp little-endian true colour: bytes B,G,R,pad become an ARGB int once alpha is set.
        send(byteArrayOf(0, 0, 0, 0, 32, 24, 0, 1, 0, -1, 0, -1, 0, -1, 16, 8, 0, 0, 0, 0))
        val encodings = intArrayOf(6, 1, 0, -223, -239)
        send(ByteBuffer.allocate(4 + encodings.size * 4).put(2).put(0).putShort(encodings.size.toShort()).apply {
            encodings.forEach { putInt(it) }
        }.array())
        request(false)
        ready = true
    }

    internal fun start() {
        if (closed.get()) return
        thread = Thread({
            try { while (!closed.get() && pump()) Unit }
            catch (e: InterruptedException) { if (!closed.get()) finish(VNC_LOST) }
            catch (e: Exception) { if (!closed.get()) finish(plain(e)) }
        }, "vnc-console").apply { isDaemon = true; start() }
    }

    /** Applies one server message. Returns false when the channel has closed. */
    internal fun pump(): Boolean {
        if (closed.get()) return false
        return try { dispatch(); true }
        catch (e: IllegalStateException) {
            if (closed.get()) false
            else if (e.message == VNC_LOST) { finish(VNC_LOST); false }
            else throw e
        }
    }

    private fun dispatch() {
        when (val type = u8()) {
            0 -> framebufferUpdate()
            1 -> { u8(); u16(); skip(u16() * 6) }
            2 -> Unit
            3 -> { channel.read(3); val n = i32(); skip(if (n < 0) -n else n) }
            else -> error("The console used an unsupported server message.")
        }
    }

    private fun framebufferUpdate() {
        u8()
        val count = u16()
        var dirty = false
        repeat(count) {
            val x = u16(); val y = u16(); val w = u16(); val h = u16(); val encoding = i32()
            when (encoding) {
                0 -> { raw(x, y, w, h); dirty = true }
                1 -> { copyRect(x, y, w, h, u16(), u16()); dirty = true }
                6 -> { rawBytes(x, y, w, h, zlibRect(w, h)); dirty = true }
                -223 -> resize(w, h)
                -239 -> cursor(x, y, w, h)
                else -> error("The console used an unsupported encoding.")
            }
        }
        if (dirty || count == 0) publish()
        if (!closed.get()) request(true)
    }

    private fun raw(x: Int, y: Int, w: Int, h: Int) {
        check(w > 0 && h > 0 && x + w <= width && y + h <= height) { "The console rectangle is invalid." }
        val bytes = channel.read(w * h * 4)
        rawBytes(x, y, w, h, bytes)
    }

    private fun rawBytes(x: Int, y: Int, w: Int, h: Int, bytes: ByteArray) {
        check(w > 0 && h > 0 && x + w <= width && y + h <= height && bytes.size >= w * h * 4) { "The console rectangle is invalid." }
        synchronized(frameLock) {
            var i = 0
            repeat(h) { row ->
                val dest = (y + row) * width + x
                repeat(w) { col ->
                    val b = bytes[i].toInt() and 255
                    val g = bytes[i + 1].toInt() and 255
                    val r = bytes[i + 2].toInt() and 255
                    pixels[dest + col] = (255 shl 24) or (r shl 16) or (g shl 8) or b
                    i += 4
                }
            }
        }
    }

    private fun copyRect(x: Int, y: Int, w: Int, h: Int, sx: Int, sy: Int) {
        check(w > 0 && h > 0 && x + w <= width && y + h <= height && sx + w <= width && sy + h <= height) { "The console rectangle is invalid." }
        synchronized(frameLock) {
            val tmp = IntArray(w * h)
            repeat(h) { row ->
                val src = (sy + row) * width + sx
                pixels.copyInto(tmp, row * w, src, src + w)
            }
            repeat(h) { row -> tmp.copyInto(pixels, (y + row) * width + x, row * w, row * w + w) }
        }
    }

    /** One persistent inflater. Unconsumed input is prepended to the next rectangle so the zlib stream stays continuous. */
    private fun zlibRect(w: Int, h: Int): ByteArray {
        val need = w * h * 4
        check(need in 4..16_777_216) { "The console rectangle is too large." }
        val length = u32()
        check(length in 0..16_777_216) { "The console message is too large." }
        val chunk = if (length == 0) ByteArray(0) else channel.read(length)
        val unused = inflater.remaining
        val tail = if (unused > 0 && zbuf.size >= unused) zbuf.copyOfRange(zbuf.size - unused, zbuf.size) else ByteArray(0)
        zbuf = if (tail.isEmpty()) chunk else tail + chunk
        if (zbuf.isEmpty()) error("The console zlib data ended early.")
        inflater.setInput(zbuf)
        val out = ByteArray(need)
        var filled = 0
        while (filled < need) {
            val n = try { inflater.inflate(out, filled, need - filled) }
            catch (e: DataFormatException) { error("The console zlib data is invalid.") }
            if (n == 0) error("The console zlib data ended early.")
            filled += n
        }
        return out
    }

    private fun resize(w: Int, h: Int) {
        check(w in 1..8192 && h in 1..8192) { "The console dimensions are invalid." }
        synchronized(frameLock) {
            val next = IntArray(w * h)
            val copyW = minOf(width, w)
            val copyH = minOf(height, h)
            repeat(copyH) { row -> pixels.copyInto(next, row * w, row * width, row * width + copyW) }
            pixels = next
            width = w
            height = h
            version++
        }
        publish(alreadyBumped = true)
    }

    private fun cursor(hotX: Int, hotY: Int, w: Int, h: Int) {
        if (w == 0 || h == 0) {
            synchronized(frameLock) { cursor = null }
            publish(alreadyBumped = false, bump = false)
            return
        }
        check(w in 1..256 && h in 1..256) { "The console cursor is invalid." }
        val raw = channel.read(w * h * 4)
        val stride = (w + 7) / 8
        val mask = channel.read(stride * h)
        val argb = IntArray(w * h)
        var i = 0
        repeat(h) { row ->
            repeat(w) { col ->
                val bit = (mask[row * stride + col / 8].toInt() and 255) and (0x80 shr (col % 8))
                val b = raw[i].toInt() and 255
                val g = raw[i + 1].toInt() and 255
                val r = raw[i + 2].toInt() and 255
                argb[row * w + col] = if (bit != 0) (255 shl 24) or (r shl 16) or (g shl 8) or b else 0
                i += 4
            }
        }
        synchronized(frameLock) { cursor = VncCursor(w, h, hotX, hotY, argb) }
        publish(alreadyBumped = false, bump = false)
    }

    private fun publish(alreadyBumped: Boolean = false, bump: Boolean = true) {
        trace.frame()
        if (bump) synchronized(frameLock) { if (!alreadyBumped) version++ }
        val callback = onUpdate
        if (callback != null) runCatching { callback() }
    }

    private fun request(incremental: Boolean) {
        send(ByteBuffer.allocate(10).put(3).put(if (incremental) 1 else 0).putShort(0).putShort(0).putShort(width.toShort()).putShort(height.toShort()).array())
    }

    private fun send(bytes: ByteArray) {
        if (closed.get()) return
        synchronized(writeLock) {
            if (closed.get()) return
            try { channel.write(bytes) }
            catch (e: Exception) {
                if (ready && !closed.get()) finish(VNC_LOST) else throw e
            }
        }
    }

    private fun finish(message: String) {
        trace.end(error=IllegalStateException())
        if (reason == null) reason = message
        val callback = onClosed
        close()
        if (callback != null && reason != null) runCatching { callback(reason!!) }
    }

    private fun u8() = channel.read(1)[0].toInt() and 255
    private fun u16(): Int { val b = channel.read(2); return ((b[0].toInt() and 255) shl 8) or (b[1].toInt() and 255) }
    private fun i32() = ByteBuffer.wrap(channel.read(4)).int
    private fun u32(): Int { val v = i32(); check(v >= 0) { "The console message is too large." }; return v }
    private fun skip(n: Int) {
        check(n in 0..16_777_216) { "The console message is too large." }
        var left = n
        while (left > 0) { val size = minOf(left, 8192); channel.read(size); left -= size }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        trace.end()
        channel.close()
        val worker = thread
        if (worker != null && worker != Thread.currentThread()) runCatching { worker.join(500) }
        runCatching { inflater.end() }
        runCatching { socket?.abort() }
        val client = http
        http = null
        runCatching { client?.close() }
    }

    override fun toString() = "VncSession(${width}x$height)"
}

private fun plain(e: Exception): String {
    if (e is SSLException || e is java.io.IOException) return VNC_LOST
    val message = e.message ?: return VNC_LOST
    if (message.length > 200 || "vncticket" in message || "wss://" in message) return VNC_LOST
    return if (message.startsWith("The console") || message.startsWith("The guest") || message == VNC_LOST || message == VNC_NO_PERMISSION) message else VNC_LOST
}

/** Blocks until the websocket is up and the RFB handshake has finished, then reads frames on a daemon thread. */
internal fun openVncSession(connection: Connection, base: String, proxy: (String) -> String): VncSession {
    return connection.withSecret { secret ->
        val body = try { Trace.call(TraceSource.Console, "POST $base/vncproxy") { proxy(secret) } }
        catch (e: GuestPermissionException) { error(VNC_NO_PERMISSION) }
        catch (e: AmbiguousPowerResultException) { error("The server did not open a console channel.") }
        val data = Json.parseToJsonElement(body).jsonObject["data"]!!.jsonObject
        val port = data["port"]?.jsonPrimitive?.intOrNull
        check(port != null && port in 1..65535) { "The server returned an unusable console channel." }
        val ticket = data["ticket"]?.jsonPrimitive?.content ?: error("Missing console ticket.")
        val password = data["password"]?.jsonPrimitive?.content ?: error("Missing console password.")
        val origin = serverOrigin(connection.address)
        val uri = URI("wss://${origin.rawAuthority}$base/vncwebsocket?port=$port&vncticket=${URLEncoder.encode(ticket, Charsets.UTF_8)}")
        val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(20)).apply {
            pinnedSslContext(connection.fingerprint)?.let { sslContext(it) }
        }.build()
        val pipe = VncSocket()
        try {
            val header = if (connection.passwordLogin) "Cookie" else "Authorization"
            val value = if (connection.passwordLogin) connection.tickets.get(connection).cookie else authorizationHeader(DIRECT_PVE_KIND, connection.tokenId, secret)
            val ws = http.newWebSocketBuilder().header(header, value).subprotocols("binary").buildAsync(uri, pipe).get(20, TimeUnit.SECONDS)
            pipe.attach(ws)
            val session = VncSession(pipe)
            session.http = http
            session.socket = ws
            try {
                session.handshake(password)
                pipe.handshake = false
                session.start()
                session
            } catch (e: Exception) {
                session.close()
                throw sanitized(e)
            }
        } catch (e: Exception) {
            pipe.close()
            runCatching { http.close() }
            throw sanitized(e)
        }
    }
}

private fun sanitized(e: Exception): Exception {
    if (e is java.util.concurrent.ExecutionException) return sanitized((e.cause as? Exception) ?: IllegalStateException(VNC_LOST))
    val message = e.message.orEmpty()
    if ("vncticket" in message || "wss://" in message) return IllegalStateException(VNC_LOST)
    if (e is IllegalStateException || e is IllegalArgumentException) return e
    return IllegalStateException(VNC_LOST)
}

/** Byte pipe over one PVE vncwebsocket. Reads block after the handshake; the ticket is never stored. */
private class VncSocket : RfbChannel, WebSocket.Listener {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val writeLock = Any()
    private var pending = ByteArray(0)
    private var offset = 0
    private val openedAt = System.nanoTime()
    @Volatile var handshake = true
    @Volatile private var socket: WebSocket? = null
    fun attach(webSocket: WebSocket) { socket = webSocket }
    override fun onOpen(webSocket: WebSocket) { socket = webSocket; webSocket.request(1) }
    override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
        val bytes = ByteArray(data.remaining()); data.get(bytes)
        if (bytes.isNotEmpty()) queue.offer(bytes)
        webSocket.request(1); return null
    }
    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? { webSocket.request(1); return null }
    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? { queue.offer(ByteArray(0)); return null }
    override fun onError(webSocket: WebSocket, error: Throwable) { queue.offer(ByteArray(0)) }
    override fun read(n: Int): ByteArray {
        val result = ByteArray(n); var written = 0
        while (written < n) {
            if (offset == pending.size) {
                pending = next(); offset = 0
                check(pending.isNotEmpty()) { VNC_LOST }
            }
            val count = minOf(n - written, pending.size - offset)
            pending.copyInto(result, written, offset, offset + count); offset += count; written += count
        }
        return result
    }
    private fun next(): ByteArray {
        if (!handshake) return queue.take()
        val left = openedAt + TimeUnit.SECONDS.toNanos(20) - System.nanoTime()
        if (left <= 0) error("The console timed out.")
        return queue.poll(left, TimeUnit.NANOSECONDS) ?: error("The console timed out.")
    }
    override fun write(bytes: ByteArray) {
        val ws = socket ?: error(VNC_LOST)
        synchronized(writeLock) { ws.sendBinary(ByteBuffer.wrap(bytes), true).get(20, TimeUnit.SECONDS) }
    }
    override fun close() { queue.offer(ByteArray(0)); socket?.abort() }
}
