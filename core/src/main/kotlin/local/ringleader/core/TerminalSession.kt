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
import java.io.PipedInputStream
import java.io.PipedOutputStream
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
    private val input = PipedInputStream(65536)
    private val output = PipedOutputStream(input)
    @Volatile var onUpdate: (() -> Unit)? = null
    var onInput: ((String) -> Unit)? = null
    init {
        terminal.setTerminalOutput(object : TerminalOutputStream {
            override fun sendBytes(bytes: ByteArray, userInput: Boolean) { onInput?.invoke(bytes.toString(Charsets.UTF_8)) }
            override fun sendString(value: String, userInput: Boolean) { onInput?.invoke(value) }
        })
    }
    private val worker = Thread({
        val reader = InputStreamReader(input, Charsets.UTF_8)
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
        try { while (!Thread.currentThread().isInterrupted) { emulator.next(); onUpdate?.invoke() } } catch (_: Exception) { }
    }, "terminal-emulator").apply { isDaemon = true; start() }
    private val linuxConsole = LinuxCursorFilter()
    fun feed(bytes: ByteArray) { output.write(linuxConsole.filter(bytes)); output.flush() }
    fun resize(cols: Int, rows: Int) { buffer.lock(); try { terminal.resize(TermSize(cols, rows), RequestOrigin.User) } finally { buffer.unlock() } }
    fun paste(text: String) = if (bracketedPaste) "\u001b[200~$text\u001b[201~" else text
    override fun close() { output.close(); input.close(); worker.interrupt() }
}

class TerminalSession internal constructor(val model: TerminalModel, private val http: HttpClient) : AutoCloseable, WebSocket.Listener {
    private val trace = Trace.begin(TraceSource.Terminal, "Terminal websocket", session=true)
    @Volatile var ready = false
    @Volatile var reason: String? = null
    private var socket: WebSocket? = null
    private val pending = java.io.ByteArrayOutputStream()
    private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r,"terminal-ping").apply { isDaemon=true } }
    override fun onOpen(ws: WebSocket) { socket = ws; ws.request(1) }
    @Synchronized private fun receive(bytes: ByteArray) {
        trace.bytes(bytesIn=bytes.size.toLong())
        if (ready) { model.feed(bytes); return }
        pending.write(bytes)
        if (pending.size() < 2) return
        val all = pending.toByteArray(); pending.reset()
        if (all[0] != 79.toByte() || all[1] != 75.toByte()) { reason = "The terminal refused authentication."; close(); return }
        ready = true
        model.feed(all.copyOfRange(2, all.size))
    }
    override fun onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): java.util.concurrent.CompletionStage<*>? { val b=ByteArray(data.remaining()); data.get(b); receive(b); ws.request(1); return null }
    override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): java.util.concurrent.CompletionStage<*>? { receive(data.toString().toByteArray()); ws.request(1); return null }
    override fun onClose(ws: WebSocket, code: Int, message: String): java.util.concurrent.CompletionStage<*>? { reason="The terminal connection closed."; close(); return null }
    override fun onError(ws: WebSocket, error: Throwable) { trace.end(error=error); reason="The terminal connection was lost."; close() }
    fun start(ws: WebSocket, proxy: TermProxy) { socket=ws; ws.sendText(terminalAuth(proxy),true).get(20,TimeUnit.SECONDS); model.onInput = ::input; timer.schedule({ if (!ready) { reason="The terminal timed out."; close() } },20,TimeUnit.SECONDS); timer.scheduleAtFixedRate({ if (ready) { Trace.emit(TraceSource.Terminal,"heartbeat","Terminal keepalive",spanId=trace.id); send(terminalFrame()) } },30,30,TimeUnit.SECONDS) }
    @Synchronized private fun send(value: String) { trace.bytes(bytesOut=value.toByteArray().size.toLong()); try { socket?.sendText(value,true)?.get(20,TimeUnit.SECONDS) } catch (_: Exception) { reason="The terminal connection was lost."; close() } }
    fun input(text: String) { if (ready) send(terminalFrame(data=text)) }
    fun resize(cols: Int, rows: Int) { model.resize(cols,rows); if (ready) send(terminalFrame(cols=cols,rows=rows)) }
    override fun close() { trace.end(); ready=false; timer.shutdownNow(); socket?.abort(); model.close(); http.shutdownNow() }
}
internal fun openTerminalSession(connection: Connection, target: TerminalTarget, proxy: () -> String): TerminalSession = connection.withSecret { secret ->
    val info = parseTermProxy(Trace.call(TraceSource.Terminal, "POST termproxy") { proxy() })
    val origin = serverOrigin(connection.address)
    val uri=URI("wss://${origin.rawAuthority}${target.base()}/vncwebsocket?port=${info.port}&vncticket=${URLEncoder.encode(info.ticket,Charsets.UTF_8)}")
    val http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(20)).apply { pinnedSslContext(connection.fingerprint)?.let { sslContext(it) } }.build()
    val session=TerminalSession(TerminalModel(),http)
    try {
        val header=if(connection.passwordLogin) "Cookie" else "Authorization"
        val value=if(connection.passwordLogin) connection.tickets.get(connection).cookie else authorizationHeader(DIRECT_PVE_KIND,connection.tokenId,secret)
        val ws=http.newWebSocketBuilder().header(header,value).subprotocols("binary").buildAsync(uri,session).get(20,TimeUnit.SECONDS)
        session.start(ws,info); session
    } catch (_: Exception) { session.close(); error("The terminal could not connect.") }
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
