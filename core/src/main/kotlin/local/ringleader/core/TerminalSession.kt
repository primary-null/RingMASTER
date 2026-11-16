package local.ringleader.core

import com.jediterm.terminal.*
import com.jediterm.terminal.model.*
import com.jediterm.terminal.emulator.JediEmulator
import com.jediterm.core.util.TermSize
import kotlinx.serialization.json.*
import java.lang.reflect.Proxy
import java.net.URI
import java.net.URLEncoder
import java.net.http.*
import java.nio.ByteBuffer
import java.io.InputStreamReader
import java.time.Duration
import java.util.concurrent.TimeUnit

/** A route contains no credentials. Guest routes are revalidated against the workspace inventory. */
data class TerminalTarget(val node: String, val kind: String = "node", val vmid: Int? = null) {
    fun base(): String {
        require(node.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) { "Invalid node." }
        require(kind in listOf("node", "lxc", "qemu")) { "Invalid terminal type." }
        require(if (kind == "node") vmid == null else vmid != null && vmid > 0) { "Invalid guest ID." }
        return "/api2/json/nodes/$node" + if (kind == "node") "" else "/$kind/$vmid"
    }
    companion object { fun guest(guest: Guest) = TerminalTarget(guest.node, if (guest.kind == "VM") "qemu" else "lxc", guest.id.toInt()) }
}
data class TermProxy(val port: Int, val ticket: String, val user: String, val upid: String)
fun parseTermProxy(body: String): TermProxy {
    val d = Json.parseToJsonElement(body).jsonObject["data"]!!.jsonObject
    fun field(key: String) = d[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && '\n' !in it && '\r' !in it } ?: error("Invalid terminal channel.")
    return TermProxy(field("port").toInt().also { require(it in 1..65535) }, field("ticket"), field("user"), field("upid"))
}
fun terminalAuth(proxy: TermProxy) = "${proxy.user}:${proxy.ticket}\n"
fun terminalFrame(data: String? = null, cols: Int? = null, rows: Int? = null): String = when {
    data != null -> "0:${data.toByteArray(Charsets.UTF_8).size}:$data"
    cols != null && rows != null -> { require(cols in 2..1000 && rows in 2..500); "1:$cols:$rows:" }
    else -> "2"
}
fun terminalKey(key: String, text: String = "", ctrl: Boolean = false, alt: Boolean = false): String? {
    val value = when (key) {
        "Enter" -> "\r"; "Backspace" -> "\u007f"; "Tab" -> "\t"; "Escape" -> "\u001b"
        "Up" -> "\u001b[A"; "Down" -> "\u001b[B"; "Right" -> "\u001b[C"; "Left" -> "\u001b[D"
        "Home" -> "\u001b[H"; "End" -> "\u001b[F"; "PageUp" -> "\u001b[5~"; "PageDown" -> "\u001b[6~"
        "Insert" -> "\u001b[2~"; "Delete" -> "\u001b[3~"
        "F1" -> "\u001bOP"; "F2" -> "\u001bOQ"; "F3" -> "\u001bOR"; "F4" -> "\u001bOS"
        "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12" -> "\u001b[${mapOf("F5" to 15,"F6" to 17,"F7" to 18,"F8" to 19,"F9" to 20,"F10" to 21,"F11" to 23,"F12" to 24)[key]}~"
        else -> if (ctrl && text.length == 1 && text[0].uppercaseChar() in '@'..'_') (text[0].uppercaseChar().code and 31).toChar().toString() else text.takeIf { it.isNotEmpty() } ?: return null
    }
    return (if (alt) "\u001b" else "") + value
}

/** Core emulation only: no Swing, AWT, native widgets, or browser engine. */
class TerminalModel(cols: Int = 80, rows: Int = 24) : AutoCloseable {
    @Volatile var cursorVisible = true
    @Volatile var bracketedPaste = false
    private val style = StyleState()
    val buffer = TerminalTextBuffer(cols, rows, style, 5000)
    private val display = Proxy.newProxyInstance(TerminalDisplay::class.java.classLoader, arrayOf(TerminalDisplay::class.java)) { _, method, args ->
        when (method.name) {
            "setCursorVisible" -> { cursorVisible = args[0] as Boolean; null }
            "setBracketedPasteMode" -> { bracketedPaste = args[0] as Boolean; null }
            "ambiguousCharsAreDoubleWidth" -> false
            "getWindowTitle" -> ""
            else -> null
        }
    } as TerminalDisplay
    val terminal = JediTerminal(display, buffer, style)
    /**
     * Host output waiting for the emulator: bounded, in order, and independent of which thread writes it. A pipe would
     * fail the emulator once the websocket's pool thread that last wrote had exited (after 60 s of silence).
     */
    private val hostOutput = HostOutputStream()
    @Volatile private var closing = false
    @Volatile var onUpdate: (() -> Unit)? = null
    var onInput: ((String) -> Unit)? = null
    /**
     * Called once, on the emulator thread, when the emulator stops although nobody closed the model: the picture can no
     * longer follow the host. Output written after that fails with an [java.io.IOException].
     */
    @Volatile var onStopped: ((Throwable) -> Unit)? = null
    init {
        terminal.setTerminalOutput(object : TerminalOutputStream {
            override fun sendBytes(bytes: ByteArray, userInput: Boolean) { onInput?.invoke(bytes.toString(Charsets.UTF_8)) }
            override fun sendString(value: String, userInput: Boolean) { onInput?.invoke(value) }
        })
    }
    private val worker = Thread({
        val reader = InputStreamReader(hostOutput, Charsets.UTF_8)
        val pushed = java.util.ArrayDeque<Char>()
        val stream = object : TerminalDataStream {
            override fun getChar(): Char = if (pushed.isNotEmpty()) pushed.removeFirst() else reader.read().let { if (it < 0) throw TerminalDataStream.EOF(); it.toChar() }
            override fun pushChar(c: Char) { pushed.addFirst(c) }
            override fun readNonControlCharacters(max: Int): String {
                val result = StringBuilder()
                while (result.length < max && (pushed.isNotEmpty() || reader.ready())) {
                    val c = getChar()
                    if (c < ' ' || c == '\u007f' || c == '\u009b' || c == '\u009d') { pushChar(c); break }
                    result.append(c)
                }
                return result.toString()
            }
            override fun pushBackBuffer(chars: CharArray, length: Int) { for (i in length - 1 downTo 0) pushed.addFirst(chars[i]) }
            override fun isEmpty() = false
        }
        val emulator = JediEmulator(stream, terminal)
        try { while (!Thread.currentThread().isInterrupted) { emulator.next(); onUpdate?.invoke() } }
        catch (_: TerminalDataStream.EOF) { } // closed, or the host output was finished and drained
        catch (e: Exception) {
            // A failure nobody asked for must not leave a terminal that accepts output and never draws it.
            if (!closing) { hostOutput.close(); runCatching { onStopped?.invoke(e) } }
        }
    }, "terminal-emulator").apply { isDaemon = true; start() }
    private val linuxConsole = LinuxCursorFilter()
    /** Queues host output for the emulator, waiting while it is behind. Throws [java.io.IOException] once the model is closed or stopped. */
    fun feed(bytes: ByteArray) { hostOutput.write(linuxConsole.filter(bytes)) }
    fun resize(cols: Int, rows: Int) { buffer.lock(); try { terminal.resize(TermSize(cols, rows), RequestOrigin.User) } finally { buffer.unlock() } }
    fun paste(text: String) = if (bracketedPaste) "\u001b[200~$text\u001b[201~" else text
    /**
     * The host has nothing more to say. Lets the emulator draw what it was already given, for at most [waitMillis], then
     * closes. Output fed afterwards fails.
     */
    fun finish(waitMillis: Long = 500) {
        hostOutput.finish()
        if (Thread.currentThread() !== worker) runCatching { worker.join(waitMillis) }
        close()
    }
    /** Idempotent, and safe from any thread: drops queued output, releases a writer that is waiting, ends the emulator. */
    override fun close() { closing = true; hostOutput.close(); worker.interrupt() }
}

/** Frames waiting for the terminal writer. A full queue means the socket has stalled; the session then ends. */
internal const val TERMINAL_OUTBOX_LIMIT = 1024
const val TERMINAL_LOST = "The terminal connection was lost. Reconnect to open it again."
/** The server stopped answering websocket pings, as after the computer slept or a network mapping disappeared. */
const val TERMINAL_NO_ANSWER = "The terminal connection was lost: the server stopped answering. Reconnect to open it again."
/** The emulator itself stopped: the screen can no longer follow the host, whatever the network is doing. */
const val TERMINAL_DISPLAY_STOPPED = "The terminal display stopped working."

/**
 * Data bytes in one input frame. Proxmox's websocket proxy takes no client frame over 128 KiB: pve-http-server,
 * src/PVE/APIServer/AnyEvent.pm, `websocket_proxy` (`$max_payload_size = 128 * 1024`; a bigger frame ends the connection
 * with "received too large websocket frame"). Proxmox's own web client sends one frame per xterm.js data event, so it
 * leaves the size of a paste to xterm.js; this client cuts it. 16 KiB is eight times under the limit, so neither the
 * `0:<length>:` header nor the bracketed-paste markers can push a frame over it, and small enough that a resize or a
 * keepalive queued behind a paste waits for one frame, not for the whole paste.
 */
internal const val TERMINAL_FRAME_BYTES = 16 * 1024

/**
 * The largest paste sent. A paste goes to a shell that acts on it line by line, as if typed, so a clipboard of more than
 * 1 MiB (512 frames; about 8 s on a 1 Mbit/s link, with the terminal busy all that time) is far more likely a mistake
 * than an intent, and cannot be called back once sent. Larger text is refused with a sentence, never cut short.
 */
const val TERMINAL_PASTE_LIMIT = 1024 * 1024
internal fun terminalPasteTooLarge(bytes: Long) =
    "That paste is ${"%.1f".format(java.util.Locale.ROOT, bytes / 1048576.0)} MiB. A paste over 1 MiB is refused, so a stray clipboard cannot flood the shell. Send large text as a file instead."
/**
 * How a paste is paced. Measured on the lab host (2026-11-16): a container's console behaves like a relay (Proxmox runs termproxy
 * with `pct console`; the lab token cannot see the host's processes, so that part is read from its source) that stops for good
 * when it is handed more input than it has echoed back. With the tty echo on, a paste sent
 * as one burst came back whole at 4,080 and at 16,320 bytes, but a burst of 32,720 bytes (two frames of 16 KiB) returned 3,752
 * bytes of echo and then nothing, ever; 96,000 bytes in 188 frames of 512 bytes, all handed over within 0.2 s, left the shell
 * silent afterwards (so it is the burst, not the frame size), and 300 KiB in 16 KiB frames ended after 5 s with Proxmox resetting
 * the connection ("Connection reset by peer"). That console stayed dead after the connection was gone. With the tty echo off the
 * same 300 KiB went through whole (`wc -c` counted 307,200).
 *
 * So a paste leaves in frames of [TERMINAL_PASTE_FRAME_BYTES] and never more than [TERMINAL_PASTE_WINDOW] bytes ahead of the
 * output that has come back since it began (half of the 16 KiB burst seen to work). If nothing at all comes back for
 * [TERMINAL_PASTE_PROBE_MS] after the first window there is no echo to wait for (echo off, a password prompt), and the rest is
 * sent without waiting, as the measurement allows. If output was coming back and then stops for [TERMINAL_PASTE_STALL_MS], the
 * server has stopped taking input: the paste ends there with [terminalPasteStalled] and sends no more into it.
 */
internal const val TERMINAL_PASTE_FRAME_BYTES = 2048
internal const val TERMINAL_PASTE_WINDOW = 8 * 1024
internal const val TERMINAL_PASTE_PROBE_MS = 1000L
internal const val TERMINAL_PASTE_STALL_MS = 8000L
internal fun terminalPasteStalled(sentBytes: Int) =
    "The terminal stopped taking the paste after ${"%.1f".format(java.util.Locale.ROOT, sentBytes / 1024.0)} KiB, so the rest was not sent. If the terminal no longer answers, use Reconnect."
const val TERMINAL_PASTE_BUSY = "The previous paste is still being sent. Wait for it to finish, or stop it."
const val TERMINAL_PASTE_NO_ROOM = "The terminal has not yet taken the earlier input. Try the paste again in a moment."

/** UTF-8 bytes [text] takes on the wire. An unpaired surrogate is sent as U+FFFD (three bytes), as [terminalChunks] does. */
internal fun terminalByteSize(text: String): Long {
    var total = 0L
    var i = 0
    while (i < text.length) {
        val c = text[i]
        total += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> { i++; 4 }
            else -> 3
        }
        i++
    }
    return total
}

/**
 * Cuts [text] into pieces of at most [maxBytes] UTF-8 bytes, only between characters: a surrogate pair is never split,
 * so every piece is well-formed text and its frame's length is exact. An unpaired surrogate (which no UTF-8 text can
 * carry, and which would fail the websocket send) becomes U+FFFD. Each piece is sent as a whole `0:<bytes>:<data>`
 * message of its own, in order.
 */
internal fun terminalChunks(text: String, maxBytes: Int = TERMINAL_FRAME_BYTES): List<String> {
    require(maxBytes >= 4)
    if (text.isEmpty()) return emptyList()
    if (text.length <= maxBytes / 3 && text.none { Character.isSurrogate(it) }) return listOf(text)
    val pieces = ArrayList<String>()
    val piece = StringBuilder()
    var bytes = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val codePoint: Int
        if (Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) { codePoint = Character.toCodePoint(c, text[i + 1]); i += 2 }
        else { codePoint = if (Character.isSurrogate(c)) 0xFFFD else c.code; i++ }
        val size = when { codePoint < 0x80 -> 1; codePoint < 0x800 -> 2; codePoint < 0x10000 -> 3; else -> 4 }
        if (bytes + size > maxBytes) { pieces += piece.toString(); piece.setLength(0); bytes = 0 }
        piece.appendCodePoint(codePoint)
        bytes += size
    }
    if (piece.isNotEmpty()) pieces += piece.toString()
    return pieces
}

/** A paste in flight. Its frames can be dropped while they wait, by [TerminalSession.stopPaste]. */
private class PasteJob(val total: Int) {
    @Volatile var stopped = false
    @Volatile var started = false
    /** Data bytes already handed to the socket. */
    val sent = java.util.concurrent.atomic.AtomicInteger()
    // Pacing, touched by the writer thread only: what the server had sent when the paste began, how much has come back since,
    // when that last grew, and whether it was decided that nothing will come back.
    var baseline = -1L
    var echoed = 0L
    var lastEchoNanos = 0L
    var noEcho = false
}

/**
 * One frame for the writer. [bytes] of data count toward the progress of its paste. [last] ends the paste; [keep] sends
 * the frame even after the paste was stopped, once it had started (the closing bracketed-paste marker).
 */
private class Outgoing(val frame: String, val paste: PasteJob? = null, val bytes: Int = 0, val last: Boolean = false, val keep: Boolean = false)

/**
 * Host output is fed to the emulator on the websocket thread, without holding any lock, so a full emulator queue only
 * slows the socket. Input from the UI and from the emulator (answers to host queries) is queued for one writer
 * thread: callers never wait on the network. Frames are sent in order and never resent.
 */
class TerminalSession internal constructor(
    val model: TerminalModel,
    private val http: HttpClient?,
    /** Seconds between keepalive beats; tests shorten it. */
    private val pingSeconds: Long = WEBSOCKET_PING_SECONDS,
    /** How long a paste waits for any echo before it decides there is none, and for echo that stopped before it gives up; tests shorten them. */
    private val pasteProbeMillis: Long = TERMINAL_PASTE_PROBE_MS,
    private val pasteStallMillis: Long = TERMINAL_PASTE_STALL_MS,
) : AutoCloseable, WebSocket.Listener {
    private val trace = Trace.begin(TraceSource.Terminal, "Terminal websocket", session=true)
    @Volatile var ready = false
    @Volatile var reason: String? = null
    @Volatile private var socket: WebSocket? = null
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    // Touched only by the websocket listener, whose callbacks never overlap.
    private val pending = java.io.ByteArrayOutputStream()
    private var textTail: Char? = null
    private val outbox = java.util.concurrent.LinkedBlockingQueue<Outgoing>(TERMINAL_OUTBOX_LIMIT)
    /** Every enqueue holds it, so the frames of one paste (or one burst of typing) never have another frame between them. */
    private val sendLock = Any()
    @Volatile private var paste: PasteJob? = null
    private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r,"terminal-ping").apply { isDaemon=true } }
    @Volatile private var writer: Thread? = null
    @Volatile private var keepalive: WebSocketKeepalive? = null
    private val received = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var pasteNote: String? = null
    /** Bytes the server has sent over this channel so far, the greeting included. */
    val bytesReceived: Long get() = received.get()
    /** What ended the session, when something threw: the trace records its class (never its message, which may name an address). */
    @Volatile private var cause: Throwable? = null
    init {
        model.onStopped = { if (!closed.get()) { if (reason == null) reason = TERMINAL_DISPLAY_STOPPED; close() } }
    }
    override fun onOpen(ws: WebSocket) { socket = ws; ws.request(1) }
    internal fun receive(bytes: ByteArray) {
        if (closed.get()) return
        keepalive?.pong() // anything heard proves the server is there; a pong can queue behind a flood of output
        trace.bytes(bytesIn=bytes.size.toLong())
        received.addAndGet(bytes.size.toLong())
        val output = if (ready) bytes else {
            pending.write(bytes)
            if (pending.size() < 2) return
            val all = pending.toByteArray(); pending.reset()
            if (all[0] != 79.toByte() || all[1] != 75.toByte()) { reason = "The terminal refused authentication."; close(); return }
            ready = true
            all.copyOfRange(2, all.size)
        }
        if (output.isEmpty()) return
        try { model.feed(output) } catch (e: java.io.IOException) { if (!closed.get()) { reason = TERMINAL_LOST; cause = e; close() } }
    }
    /** Text frames are UTF-8 on the wire; a surrogate pair split across partial frames is joined before encoding. */
    internal fun receiveText(data: CharSequence, last: Boolean) {
        val text = StringBuilder()
        textTail?.let { text.append(it) }; textTail = null
        text.append(data)
        if (!last && text.isNotEmpty() && text.last().isHighSurrogate()) { textTail = text.last(); text.setLength(text.length - 1) }
        if (text.isNotEmpty()) receive(text.toString().toByteArray(Charsets.UTF_8))
    }
    override fun onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): java.util.concurrent.CompletionStage<*>? { val b=ByteArray(data.remaining()); data.get(b); receive(b); ws.request(1); return null }
    override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): java.util.concurrent.CompletionStage<*>? { receiveText(data, last); ws.request(1); return null }
    override fun onPong(ws: WebSocket, message: ByteBuffer): java.util.concurrent.CompletionStage<*>? { keepalive?.pong(); ws.request(1); return null }
    override fun onClose(ws: WebSocket, code: Int, message: String): java.util.concurrent.CompletionStage<*>? { remoteClosed(); return null }
    /** The server closed the channel (a shell that exited, a timeout): the last lines it sent are drawn before the session ends. */
    internal fun remoteClosed() {
        if (reason == null) reason = "The terminal connection closed."
        if (!closed.get()) model.finish()
        close()
    }
    override fun onError(ws: WebSocket, error: Throwable) { trace.end(error=error); if (reason == null) reason=TERMINAL_LOST; close() }
    fun start(ws: WebSocket, proxy: TermProxy) {
        socket=ws
        ws.sendText(terminalAuth(proxy),true).get(20,TimeUnit.SECONDS)
        startWriter { value -> ws.sendText(value,true).get(20,TimeUnit.SECONDS) }
        model.onInput = ::input
        timer.schedule({ if (!ready) { reason="The terminal timed out."; close() } },20,TimeUnit.SECONDS)
        startKeepalive { ws.sendPing(ByteBuffer.wrap(WEBSOCKET_PING_PAYLOAD)) }
    }
    /**
     * Every [pingSeconds]: a websocket ping, whose answer (or any output) proves the path still works, and the "2" message
     * Proxmox's own terminal sends. A ping still unanswered at the next beat ends the session ([TERMINAL_NO_ANSWER]).
     * Fixed delay, not fixed rate: after the computer slept, a fixed-rate task runs once for every period it missed,
     * back to back, which would read a healthy connection as a dead one.
     */
    internal fun startKeepalive(ping: () -> java.util.concurrent.CompletionStage<*>) {
        val beat = WebSocketKeepalive(ping) { lost(TERMINAL_NO_ANSWER) }
        keepalive = beat
        timer.scheduleWithFixedDelay({
            // A task that throws is never run again, so nothing may escape.
            try {
                if (ready) {
                    beat.tick()
                    Trace.emit(TraceSource.Terminal,"heartbeat","Terminal keepalive",spanId=trace.id)
                    send(terminalFrame())
                }
            } catch (_: Exception) { }
        },pingSeconds,pingSeconds,TimeUnit.SECONDS)
    }
    /** Ends the session with [why] unless it already ended or has a reason. */
    private fun lost(why: String, because: Throwable? = null) {
        if (closed.get()) return
        if (reason == null) reason = why
        if (because != null && cause == null) cause = generateSequence(because) { if (it is java.util.concurrent.ExecutionException || it is java.util.concurrent.CompletionException) it.cause else null }.last()
        close()
    }
    /** Starts the single writer. [write] blocks until one frame is handed to the socket. */
    internal fun startWriter(write: (String) -> Unit) {
        writer = Thread({
            try {
                while (!closed.get()) {
                    val next = outbox.take()
                    val job = next.paste
                    // Frames of a stopped paste are dropped as they come up. The closing bracket of one that had already
                    // started is not: the shell must not be left believing a paste is still going on.
                    if (job != null && job.stopped && !(next.keep && job.started)) { if (next.last) pasteEnded(job); continue }
                    if (job != null && next.bytes > 0 && !pace(job, next.bytes)) { if (next.last) pasteEnded(job); continue }
                    write(next.frame)
                    if (job != null) {
                        job.started = true
                        job.sent.addAndGet(next.bytes)
                        if (next.last) pasteEnded(job) else pasteChanged()
                    }
                }
            }
            catch (_: InterruptedException) { }
            catch (e: Exception) { lost(TERMINAL_LOST, e) }
        }, "terminal-writer").apply { isDaemon = true; start() }
    }
    /**
     * Holds a paste frame back until the output that came back leaves at most [TERMINAL_PASTE_WINDOW] bytes of what was sent unanswered
     * (see the constants for why). False when the paste was stopped or the session closed while waiting, or when the server stopped
     * taking input: the paste then ends there and the writer drops the rest. Runs on the writer thread.
     */
    private fun pace(job: PasteJob, frameBytes: Int): Boolean {
        if (job.baseline < 0) { job.baseline = received.get(); job.lastEchoNanos = System.nanoTime() }
        while (true) {
            if (closed.get() || job.stopped) return false
            val echoed = received.get() - job.baseline
            if (echoed > job.echoed) { job.echoed = echoed; job.lastEchoNanos = System.nanoTime(); job.noEcho = false }
            if (job.noEcho || job.sent.get() - echoed + frameBytes <= TERMINAL_PASTE_WINDOW) return true
            val quietMillis = (System.nanoTime() - job.lastEchoNanos) / 1_000_000
            if (job.echoed == 0L && quietMillis >= pasteProbeMillis) { job.noEcho = true; return true }
            if (job.echoed > 0L && quietMillis >= pasteStallMillis) { job.stopped = true; pasteNote = terminalPasteStalled(job.sent.get()); pasteChanged(); return false }
            Thread.sleep(10)
        }
    }
    /** What ended the last paste early, said once: the server stopped taking it. Null when there is nothing new. */
    fun takePasteNote(): String? { val note = pasteNote; pasteNote = null; return note }
    /** Queues frames together and in order, never blocking. A queue without room for all of them means the socket has stalled: the session ends. */
    private fun sendAll(frames: List<Outgoing>) {
        if (closed.get() || frames.isEmpty()) return
        trace.bytes(bytesOut=frames.sumOf { it.frame.toByteArray(Charsets.UTF_8).size.toLong() })
        val queued = synchronized(sendLock) {
            val room = outbox.remainingCapacity() >= frames.size
            if (room) frames.forEach { outbox.add(it) }
            room
        }
        if (!queued) lost(TERMINAL_LOST, IllegalStateException("The outbox is full."))
    }
    private fun send(value: String) = sendAll(listOf(Outgoing(value)))
    /**
     * Keys, and answers to the host's queries. Text over [TERMINAL_FRAME_BYTES] goes out as several frames, each a whole
     * message, in order, so that no frame can pass what Proxmox's proxy accepts.
     */
    fun input(text: String) {
        if (!ready || text.isEmpty()) return
        sendAll(terminalChunks(text).map { Outgoing(terminalFrame(data = it)) })
    }
    /**
     * Sends clipboard text as a paste: in frames of at most [TERMINAL_PASTE_FRAME_BYTES], in order, behind whatever was typed
     * before and ahead of whatever is typed after, one frame at a time, paced by the echo that comes back (see
     * [TERMINAL_PASTE_WINDOW]) as well as by the socket. With [bracketed], the
     * text sits between the shell's paste markers. Returns null when the paste was accepted, and otherwise a sentence
     * saying why not: over [TERMINAL_PASTE_LIMIT], another paste still going, or no room behind earlier input. Nothing is
     * sent in those cases. Not connected yet, or empty, is quietly nothing.
     */
    fun paste(text: String, bracketed: Boolean = false): String? = paste(text, bracketed, TERMINAL_PASTE_FRAME_BYTES)
    internal fun paste(text: String, bracketed: Boolean, frameBytes: Int): String? {
        if (!ready || text.isEmpty()) return null
        val size = terminalByteSize(text)
        if (size > TERMINAL_PASTE_LIMIT) return terminalPasteTooLarge(size)
        val chunks = terminalChunks(text, frameBytes)
        val job = PasteJob(size.toInt())
        val frames = ArrayList<Outgoing>(chunks.size + 2)
        if (bracketed) frames += Outgoing(terminalFrame(data = "\u001b[200~"), job)
        chunks.forEachIndexed { i, chunk -> frames += Outgoing(terminalFrame(data = chunk), job, terminalByteSize(chunk).toInt(), last = !bracketed && i == chunks.lastIndex) }
        if (bracketed) frames += Outgoing(terminalFrame(data = "\u001b[201~"), job, last = true, keep = true)
        synchronized(sendLock) {
            if (closed.get()) return null
            if (paste?.stopped == false) return TERMINAL_PASTE_BUSY
            if (outbox.remainingCapacity() < frames.size) return TERMINAL_PASTE_NO_ROOM
            paste = job
            frames.forEach { outbox.add(it) }
        }
        trace.bytes(bytesOut=frames.sumOf { it.frame.toByteArray(Charsets.UTF_8).size.toLong() })
        pasteChanged()
        return null
    }
    /** Data bytes of the paste in flight that have not left yet; 0 when there is none, or it was stopped. */
    val pasteLeft: Int get() = paste?.let { if (it.stopped) 0 else (it.total - it.sent.get()).coerceAtLeast(0) } ?: 0
    /** Data bytes in the paste in flight. */
    val pasteTotal: Int get() = paste?.total ?: 0
    /** Drops what is left of the paste in flight. Whatever already left stays sent; a bracketed paste is still closed. */
    fun stopPaste() { val job = paste ?: return; job.stopped = true; pasteChanged() }
    private fun pasteEnded(job: PasteJob) { synchronized(sendLock) { if (paste === job) paste = null }; pasteChanged() }
    /** The screen reads the paste's progress when it redraws, so it is told by the same poke as new output. */
    private fun pasteChanged() { runCatching { model.onUpdate?.invoke() } }
    fun resize(cols: Int, rows: Int) { model.resize(cols,rows); if (ready) send(terminalFrame(cols=cols,rows=rows)) }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        trace.end(error = cause); ready=false; timer.shutdownNow()
        writer?.let { if (it != Thread.currentThread()) it.interrupt() }
        outbox.clear()
        paste = null
        socket?.abort(); model.close(); http?.shutdownNow()
        // The screen shows the reason; nothing else would make it look again, as no more output is coming.
        runCatching { model.onUpdate?.invoke() }
    }
}
internal fun openTerminalSession(connection: Connection, target: TerminalTarget, proxy: () -> String): TerminalSession = connection.withSecret { secret ->
    val info = parseTermProxy(Trace.call(TraceSource.Terminal, "POST termproxy") { proxy() })
    val origin = serverOrigin(connection.address)
    val uri=URI("wss://${origin.rawAuthority}${target.base()}/vncwebsocket?port=${info.port}&vncticket=${URLEncoder.encode(info.ticket,Charsets.UTF_8)}")
    val http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(20)).apply { sslContext(pinnedSslContext(connection.fingerprint) ?: platformSslContext()); sslParameters(tlsFloorParameters()) }.build()
    val session=TerminalSession(TerminalModel(),http)
    try {
        val header=if(connection.passwordLogin) "Cookie" else "Authorization"
        val value=if(connection.passwordLogin) connection.tickets.get(connection).cookie else authorizationHeader(DIRECT_PVE_KIND,connection.tokenId,secret)
        val ws=http.newWebSocketBuilder().header(header,value).subprotocols("binary").buildAsync(uri,session).get(20,TimeUnit.SECONDS)
        session.start(ws,info); session
    } catch (e: Exception) { session.close(); error(if (certificateRefused(e)) CONSOLE_CERTIFICATE_REFUSED else "The terminal could not connect.") }
}

/**
 * Drops the Linux console's cursor-shape sequence `ESC [ ? Ps ; ... c` from host output. Container consoles run with
 * TERM=linux, and programs such as nano send it; the xterm emulator does not know it and would print its tail ("6c")
 * as text. Host output never uses the `?` form of `c` for anything else, so dropping it is safe. Sequences split across
 * chunks are held back until complete.
 */
class LinuxCursorFilter {
    private var held = ByteArray(0)
    fun filter(input: ByteArray): ByteArray {
        val bytes = if (held.isEmpty()) input else held + input
        held = ByteArray(0)
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            if (b == 0x1b.toByte()) {
                // Need ESC [ ? to decide; hold a short incomplete tail for the next chunk.
                if (i + 2 >= bytes.size) {
                    val prefix = bytes.copyOfRange(i, bytes.size)
                    if (matchesPrefix(prefix)) { held = prefix; break }
                } else if (bytes[i + 1] == '['.code.toByte() && bytes[i + 2] == '?'.code.toByte()) {
                    var j = i + 3
                    while (j < bytes.size && (bytes[j] in '0'.code.toByte()..'9'.code.toByte() || bytes[j] == ';'.code.toByte())) j++
                    if (j >= bytes.size) {
                        if (j - i <= 32) { held = bytes.copyOfRange(i, bytes.size); break }
                    } else if (bytes[j] == 'c'.code.toByte()) { i = j + 1; continue }
                }
            }
            out.write(b.toInt()); i++
        }
        return out.toByteArray()
    }
    private fun matchesPrefix(p: ByteArray): Boolean = when (p.size) {
        1 -> true
        2 -> p[1] == '['.code.toByte()
        else -> false
    }
}
