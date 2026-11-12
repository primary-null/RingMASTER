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
const val VNC_TEXT_FRAME = "The console sent text instead of screen data."

/** Client messages waiting for the writer. Far more than a live socket ever holds; a full outbox means a stall. */
internal const val VNC_OUTBOX_LIMIT = 4096
/** Websocket chunks the reader may lag behind before the socket stops asking the server for more. */
internal const val VNC_SOCKET_CHUNKS = 256
/** Keepalive period. A ping still unanswered at the next period ends the session. */
internal const val VNC_PING_SECONDS = 30L
internal const val VNC_CLOSE_FLUSH_MS = 300L
private val VNC_PING_PAYLOAD = "ringmaster".toByteArray(Charsets.US_ASCII)

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

/** Pseudo-encoding a server confirms (with an empty rectangle) when it accepts [vncQemuKeyEvent]. */
internal const val VNC_QEMU_EXTENDED_KEY = -258

/**
 * QEMU extended key event (client message 255, submessage 0): down-flag U16, keysym U32, XT keycode U32. The keycode
 * names the physical key, so the guest's own layout decides the character. An `E0 xx` scancode is sent as `xx | 0x80`.
 */
internal fun vncQemuKeyEvent(down: Boolean, keysym: Int, keycode: Int): ByteArray =
    ByteBuffer.allocate(12).put(255.toByte()).put(0).putShort(if (down) 1 else 0).putInt(keysym).putInt(keycode).array()

/** The RFB keycode for an XT make code: one byte as is, `E0 xx` as `xx | 0x80`. Zero when there is no usable code. */
fun vncXtKeycode(scancode: Int, extended: Boolean): Int =
    if (scancode !in 1..0x7f) 0 else if (extended) scancode or 0x80 else scancode

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
 * Websocket keepalive. Each [tick] sends one ping; a tick that finds the previous ping still unanswered, or a ping
 * that cannot be sent, reports [lost]. Nothing is retried and no new console ticket is requested.
 */
internal class VncKeepalive(private val ping: () -> CompletionStage<*>, private val lost: () -> Unit) {
    private val waiting = AtomicBoolean(false)
    private val ended = AtomicBoolean(false)
    fun tick() {
        if (ended.get()) return
        if (waiting.getAndSet(true)) { end(); return }
        try { ping().whenComplete { _, error -> if (error != null) failed(error) } }
        catch (e: Exception) { failed(e) }
    }
    /** IllegalStateException only says another ping or pong is still being sent (or the socket is closing): skip one beat. */
    private fun failed(error: Throwable) {
        val root = if (error is java.util.concurrent.CompletionException && error.cause != null) error.cause!! else error
        if (root is IllegalStateException) waiting.set(false) else end()
    }
    fun pong() { waiting.set(false) }
    private fun end() { if (ended.compareAndSet(false, true)) lost() }
}

/**
 * Keys and buttons the guest currently sees as held. A key-up repeats what its key-down sent, so a modifier that
 * changed the character in between still releases the same key. [Physical] keys are identified by the caller.
 */
internal class VncHeldInput {
    class Key(val keysym: Int, val keycode: Int)
    private val keys = LinkedHashMap<Long, Key>()
    var mask = 0
        private set
    var x = 0
        private set
    var y = 0
        private set
    @Synchronized fun down(id: Long, key: Key) { keys[id] = key }
    /** The key-down to repeat for [id], or null when that key was not held. */
    @Synchronized fun up(id: Long): Key? = keys.remove(id)
    @Synchronized fun pointer(mask: Int, x: Int, y: Int) { this.mask = mask and 7; this.x = x; this.y = y }
    /** Every held key, most recent first, and whether a button was held; both are cleared. */
    @Synchronized fun drain(): Pair<List<Key>, Boolean> {
        val held = keys.values.reversed()
        keys.clear()
        val buttons = mask != 0
        mask = 0
        return held to buttons
    }
}

/**
 * Continuous RFB 3.8 client. Pixels are 32-bit little-endian true colour with red at shift 16, green at 8 and blue at 0,
 * so each pixel is an ARGB int (alpha forced on, because RFB has no alpha). One zlib [Inflater] lives for the connection.
 * ZRLE is not requested. Close is idempotent and does not by itself set [reason].
 *
 * After [start], every client message goes through one bounded outbox drained by a writer thread, so neither the UI
 * thread nor the reader ever waits on the websocket. Before [start] (the handshake), writes are direct.
 */
class VncSession internal constructor(rawChannel: RfbChannel) : AutoCloseable {
    private val trace = Trace.begin(TraceSource.Console, "VNC session", session=true)
    private val channel = object : RfbChannel {
        override fun read(n: Int) = rawChannel.read(n).also { trace.bytes(bytesIn=it.size.toLong()) }
        override fun write(bytes: ByteArray) { rawChannel.write(bytes); trace.bytes(bytesOut=bytes.size.toLong()) }
        override fun close() = rawChannel.close()
    }
    private val closed = AtomicBoolean(false)
    @Volatile private var lost = false
    private val writeLock = Any()
    private val frameLock = Any()
    private val inflater = Inflater()
    private var zbuf = ByteArray(0)
    private var pixels = IntArray(0)
    private var thread: Thread? = null
    private val outbox = LinkedBlockingQueue<ByteArray>(VNC_OUTBOX_LIMIT)
    private val stop = ByteArray(0)
    @Volatile private var writer: Thread? = null
    private var keepalive: java.util.concurrent.ScheduledExecutorService? = null
    @Volatile private var heartbeat: VncKeepalive? = null
    private val held = VncHeldInput()
    /** True once the server confirmed QEMU extended key events; keys with a keycode then use [vncQemuKeyEvent]. */
    @Volatile var extendedKeys: Boolean = false
        private set
    /** Sends one websocket ping. Set by [openVncSession]; tests leave it null and get no keepalive. */
    internal var pinger: (() -> CompletionStage<*>)? = null
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

    /** One untracked key event, for synthetic chords such as Ctrl+Alt+Del that send their own key-ups. */
    fun key(down: Boolean, keysym: Int, keycode: Int = 0) { send(keyMessage(down, VncHeldInput.Key(keysym, keycode))) }

    /**
     * A key event from physical key [id]. [keycode] is the RFB XT keycode ([vncXtKeycode]) or 0 when unknown; it is
     * used only when the server confirmed [extendedKeys]. A key-up repeats the keysym and keycode of its key-down.
     */
    fun keyInput(id: Long, down: Boolean, keysym: Int, keycode: Int = 0) {
        val key = if (down) VncHeldInput.Key(keysym, keycode).also { held.down(id, it) } else held.up(id) ?: VncHeldInput.Key(keysym, keycode)
        send(keyMessage(down, key))
    }

    fun pointer(mask: Int, x: Int, y: Int) {
        if (width == 0 || height == 0) return
        val px = x.coerceIn(0, width - 1)
        val py = y.coerceIn(0, height - 1)
        val motion = mask == held.mask
        held.pointer(mask, px, py)
        send(vncPointerEvent(mask, px, py), droppable = motion)
    }

    /** True while the guest sees a mouse button held. */
    val buttonsHeld: Boolean get() = held.mask != 0

    /**
     * Releases everything the guest sees as held: a key-up for every key that went down here, and button mask 0 at the
     * last pointer position. Call it when the console loses focus or the pointer leaves with a button down.
     */
    fun releaseInput() {
        val (keys, buttons) = held.drain()
        keys.forEach { send(keyMessage(false, it)) }
        if (buttons) send(vncPointerEvent(0, held.x, held.y))
    }

    /** Releases the mouse buttons only (the pointer left the screen); held keys stay down. */
    fun releaseButtons() {
        if (held.mask == 0) return
        held.pointer(0, held.x, held.y)
        send(vncPointerEvent(0, held.x, held.y))
    }

    private fun keyMessage(down: Boolean, key: VncHeldInput.Key): ByteArray =
        if (extendedKeys && key.keycode != 0) vncQemuKeyEvent(down, key.keysym, key.keycode) else vncKeyEvent(down, key.keysym)

    /** Called when the websocket answers a ping. */
    internal fun pong() { heartbeat?.pong() }

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
        val encodings = intArrayOf(6, 1, 0, -223, -239, VNC_QEMU_EXTENDED_KEY)
        send(ByteBuffer.allocate(4 + encodings.size * 4).put(2).put(0).putShort(encodings.size.toShort()).apply {
            encodings.forEach { putInt(it) }
        }.array())
        request(false)
        ready = true
    }

    internal fun start() {
        if (closed.get()) return
        writer = Thread({
            try {
                while (true) {
                    val bytes = outbox.take()
                    if (bytes === stop) break
                    channel.write(bytes)
                }
            } catch (e: InterruptedException) { if (!closed.get()) finish(VNC_LOST) }
            catch (e: Exception) { if (!closed.get()) finish(VNC_LOST) }
        }, "vnc-console-writer").apply { isDaemon = true; start() }
        pinger?.let { ping ->
            val beat = VncKeepalive(ping) { if (!closed.get()) finish(VNC_LOST) }
            heartbeat = beat
            keepalive = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "vnc-keepalive").apply { isDaemon = true } }
                .also { it.scheduleAtFixedRate({ beat.tick() }, VNC_PING_SECONDS, VNC_PING_SECONDS, TimeUnit.SECONDS) }
        }
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
                VNC_QEMU_EXTENDED_KEY -> extendedKeys = true
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

    /**
     * Queues one client message for the writer, or writes it directly before [start]. A full outbox means the socket
     * has stalled: pointer motion ([droppable]) is dropped, anything else ends the session as [VNC_LOST].
     */
    private fun send(bytes: ByteArray, droppable: Boolean = false) {
        if (closed.get()) return
        if (writer != null) {
            if (!outbox.offer(bytes) && !droppable && !closed.get()) finish(VNC_LOST)
            return
        }
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
        lost = true
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

    /**
     * Ends the session. A clean close first releases held keys and buttons, and gives the writer a moment to send
     * those key-ups, so the guest is never left with a modifier down.
     */
    override fun close() {
        if (closed.get()) return
        if (ready && !lost) runCatching { releaseInput() }
        if (!closed.compareAndSet(false, true)) return
        trace.end()
        keepalive?.shutdownNow()
        val out = writer
        if (out != null) {
            outbox.offer(stop)
            if (out != Thread.currentThread()) {
                runCatching { out.join(VNC_CLOSE_FLUSH_MS) }
                out.interrupt()
            }
        }
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
                pipe.onPong = session::pong
                session.pinger = { ws.sendPing(ByteBuffer.wrap(VNC_PING_PAYLOAD)) }
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

/**
 * Byte pipe over one PVE vncwebsocket. Reads block after the handshake; the ticket is never stored. The queue is
 * bounded by flow control: the socket asks the server for one more message only while the queue has room, and the
 * reader asks again once it has taken a chunk, so a slow reader never piles up websocket data.
 */
internal class VncSocket(private val chunks: Int = VNC_SOCKET_CHUNKS) : RfbChannel, WebSocket.Listener {
    private val queue = LinkedBlockingQueue<ByteArray>(chunks)
    private val writeLock = Any()
    private var pending = ByteArray(0)
    private var offset = 0
    private val openedAt = System.nanoTime()
    private val paused = AtomicBoolean(false)
    @Volatile private var ended = false
    @Volatile private var failure: String? = null
    @Volatile var handshake = true
    @Volatile var onPong: (() -> Unit)? = null
    @Volatile private var socket: WebSocket? = null
    fun attach(webSocket: WebSocket) { socket = webSocket }
    override fun onOpen(webSocket: WebSocket) { socket = webSocket; webSocket.request(1) }
    override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
        val bytes = ByteArray(data.remaining()); data.get(bytes)
        if (bytes.isNotEmpty() && !queue.offer(bytes)) { end(VNC_LOST); return null }
        more(webSocket); return null
    }
    /** PVE's vncwebsocket is binary. A text frame means something in between speaks another protocol: stop at once. */
    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? { end(VNC_TEXT_FRAME); return null }
    override fun onPong(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*>? {
        runCatching { onPong?.invoke() }
        more(webSocket); return null
    }
    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? { end(null); return null }
    override fun onError(webSocket: WebSocket, error: Throwable) { end(null) }
    private fun end(message: String?) {
        if (message != null && failure == null) failure = message
        ended = true
        queue.offer(ByteArray(0))
    }
    /** Requests the next message while the queue has room; otherwise the reader resumes the flow in [next]. */
    private fun more(webSocket: WebSocket) {
        if (queue.remainingCapacity() > 0) { webSocket.request(1); return }
        paused.set(true)
        if (queue.remainingCapacity() > 0 && paused.compareAndSet(true, false)) webSocket.request(1)
    }
    override fun read(n: Int): ByteArray {
        val result = ByteArray(n); var written = 0
        while (written < n) {
            if (offset == pending.size) {
                pending = next(); offset = 0
                check(pending.isNotEmpty()) { failure ?: VNC_LOST }
            }
            val count = minOf(n - written, pending.size - offset)
            pending.copyInto(result, written, offset, offset + count); offset += count; written += count
        }
        return result
    }
    private fun next(): ByteArray {
        val chunk = queue.poll() ?: if (ended) ByteArray(0) else if (!handshake) queue.take() else {
            val left = openedAt + TimeUnit.SECONDS.toNanos(20) - System.nanoTime()
            if (left <= 0) error("The console timed out.")
            queue.poll(left, TimeUnit.NANOSECONDS) ?: error("The console timed out.")
        }
        if (chunk.isNotEmpty() && paused.compareAndSet(true, false)) socket?.request(1)
        return chunk
    }
    override fun write(bytes: ByteArray) {
        val ws = socket ?: error(VNC_LOST)
        synchronized(writeLock) { ws.sendBinary(ByteBuffer.wrap(bytes), true).get(20, TimeUnit.SECONDS) }
    }
    override fun close() { end(null); socket?.abort() }
}
