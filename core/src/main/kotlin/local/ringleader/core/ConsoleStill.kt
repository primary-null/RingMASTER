package local.ringleader.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Instant
import java.time.Duration
import java.util.BitSet
import java.util.concurrent.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.imageio.ImageIO

/** One bounded last-known image; byte contents never enter diagnostic text. */
data class ConsoleStill(val width: Int, val height: Int, val png: ByteArray, val at: Instant, val blank: Boolean) {
    override fun toString() = "ConsoleStill(${width}x$height, at=$at, blank=$blank)"
}

const val CONSOLE_STILL_LIMIT = 8

interface ConsoleStillGateway {
    /** Captures a frame without sending keyboard, pointer or clipboard input. */
    suspend fun capture(connection: Connection, guest: Guest): ConsoleStill
}

class LiveConsoleStillGateway(private val client: PveClient = PveClient()) : ConsoleStillGateway {
    override suspend fun capture(connection: Connection, guest: Guest): ConsoleStill =
        runInterruptible(Dispatchers.IO) { client.consoleStill(connection, guest) }
}

/** An exact-length byte stream independent of WebSocket message boundaries. */
internal interface RfbChannel : AutoCloseable {
    fun read(n: Int): ByteArray
    fun write(bytes: ByteArray)
}

internal class RfbFrame(val width: Int, val height: Int, val pixels: IntArray)

internal fun consoleStillRequestBody() = """{"websocket":1,"generate-password":1}""".toByteArray()

internal fun vncResponse(password: String, challenge: ByteArray): ByteArray {
    val source = password.toByteArray(Charsets.ISO_8859_1)
    val key = ByteArray(8) { Integer.reverse((source.getOrNull(it)?.toInt() ?: 0) and 255).ushr(24).toByte() }
    return Cipher.getInstance("DES/ECB/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES")); doFinal(challenge)
    }
}

/** Negotiates Raw pixels only and stops as soon as every pixel has been received. */
internal fun captureRfbFrame(channel: RfbChannel, password: String): RfbFrame {
    fun u8() = channel.read(1)[0].toInt() and 255
    fun u16(): Int { val b = channel.read(2); return ((b[0].toInt() and 255) shl 8) or (b[1].toInt() and 255) }
    fun i32() = ByteBuffer.wrap(channel.read(4)).int
    fun skip(n: Int) { check(n in 0..16_777_216) { "The console message is too large." }; var left = n; while (left > 0) { val size = minOf(left, 8192); channel.read(size); left -= size } }
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
    channel.write(byteArrayOf(1)) // shared: preserve existing viewers
    val width = u16(); val height = u16()
    check(width in 1..8192 && height in 1..8192) { "The console dimensions are invalid." }
    channel.read(16); skip(i32())
    channel.write(byteArrayOf(0, 0, 0, 0, 32, 24, 0, 1, 0, -1, 0, -1, 0, -1, 16, 8, 0, 0, 0, 0))
    channel.write(byteArrayOf(2, 0, 0, 1, 0, 0, 0, 0))
    channel.write(ByteBuffer.allocate(10).put(3).put(0).putShort(0).putShort(0).putShort(width.toShort()).putShort(height.toShort()).array())
    val pixels = IntArray(width * height)
    val covered = BitSet(pixels.size)
    while (covered.cardinality() < pixels.size) {
        when (u8()) {
            0 -> {
                u8(); val count = u16()
                repeat(count) {
                    val x = u16(); val y = u16(); val w = u16(); val h = u16(); val encoding = i32()
                    check(encoding == 0) { "Unsupported console rectangle encoding: $encoding." }
                    check(w > 0 && h > 0 && x + w <= width && y + h <= height) { "The console rectangle is invalid." }
                    repeat(h) { row ->
                        val bytes = channel.read(w * 4)
                        repeat(w) { col ->
                            val i = col * 4; val target = (y + row) * width + x + col
                            pixels[target] = (255 shl 24) or ((bytes[i + 2].toInt() and 255) shl 16) or ((bytes[i + 1].toInt() and 255) shl 8) or (bytes[i].toInt() and 255)
                        }
                        covered.set((y + row) * width + x, (y + row) * width + x + w)
                    }
                }
            }
            1 -> { u8(); u16(); skip(u16() * 6) }
            2 -> Unit
            3 -> { channel.read(3); skip(i32()) }
            else -> error("Unsupported console server message.")
        }
    }
    return RfbFrame(width, height, pixels)
}

internal fun frameStill(frame: RfbFrame): ConsoleStill {
    val samples = (0 until minOf(100, frame.pixels.size)).map { frame.pixels[it * (frame.pixels.size - 1) / (minOf(100, frame.pixels.size) - 1).coerceAtLeast(1)] }
    fun channels(p: Int) = listOf((p ushr 16) and 255, (p ushr 8) and 255, p and 255)
    val first = channels(samples.first())
    val blank = samples.all { channels(it).all { c -> c <= 16 } } || samples.all { channels(it).zip(first).all { (a, b) -> kotlin.math.abs(a - b) <= 8 } }
    val source = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
    source.setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width)
    val scale = minOf(1.0, 960.0 / maxOf(frame.width, frame.height))
    val image = BufferedImage(maxOf(1, (frame.width * scale).toInt()), maxOf(1, (frame.height * scale).toInt()), BufferedImage.TYPE_INT_RGB)
    image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        drawImage(source, 0, 0, image.width, image.height, null); dispose()
    }
    val output = ByteArrayOutputStream(); ImageIO.write(image, "png", output)
    return ConsoleStill(image.width, image.height, output.toByteArray(), Instant.now(), blank)
}

private class WebSocketRfb(private val deadline: Long) : RfbChannel, WebSocket.Listener {
    private val queue = LinkedBlockingQueue<ByteArray>(256)
    @Volatile private var closed = false
    private var pending = ByteArray(0)
    private var offset = 0
    lateinit var socket: WebSocket
    private fun remaining() = (deadline - System.nanoTime()).coerceAtLeast(1)
    override fun onOpen(webSocket: WebSocket) { socket = webSocket; if (closed) webSocket.abort() else webSocket.request(1) }
    override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
        val bytes = ByteArray(data.remaining()); data.get(bytes)
        if (bytes.isNotEmpty() && !queue.offer(bytes)) { close(); queue.clear(); queue.offer(ByteArray(0)) }
        webSocket.request(1); return null
    }
    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? { queue.offer(ByteArray(0)); return null }
    override fun onError(webSocket: WebSocket, error: Throwable) { queue.offer(ByteArray(0)) }
    override fun read(n: Int): ByteArray {
        val result = ByteArray(n); var written = 0
        while (written < n) {
            if (offset == pending.size) {
                pending = queue.poll(remaining(), TimeUnit.NANOSECONDS) ?: error("The console capture timed out."); offset = 0
                check(pending.isNotEmpty()) { "The console channel closed before a frame arrived." }
            }
            val count = minOf(n - written, pending.size - offset)
            pending.copyInto(result, written, offset, offset + count); offset += count; written += count
        }
        return result
    }
    override fun write(bytes: ByteArray) { socket.sendBinary(ByteBuffer.wrap(bytes), true).get(remaining(), TimeUnit.NANOSECONDS) }
    override fun close() { closed = true; if (::socket.isInitialized) socket.abort() }
}

internal fun stillFailure(e: Throwable): Throwable {
    var root: Throwable = e
    while (root is ExecutionException && root.cause != null) root = root.cause!!
    if (root is SessionExpiredException || root is java.io.IOException) return root
    if (root is IllegalStateException) {
        val msg = root.message
        if (msg != null && msg.length <= 200 && Redact.text(msg) == msg) return IllegalStateException(msg)
    }
    return IllegalStateException("The screen could not be captured.")
}

internal fun captureConsoleStill(connection: Connection, base: String, proxy: (String) -> String): ConsoleStill {
    val trace = Trace.begin(TraceSource.Console, "VNC still capture", mapOf("path" to base))
    try {
    val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "console-still").apply { isDaemon = true } }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    val channel = WebSocketRfb(deadline)
    val future = worker.submit<ConsoleStill> {
        connection.withSecret { secret ->
            val data = Json.parseToJsonElement(Trace.call(TraceSource.Console, "POST $base/vncproxy") { proxy(secret) }).jsonObject["data"]!!.jsonObject
            val port = data["port"]?.jsonPrimitive?.intOrNull
            check(port != null && port in 1..65535) { "The server returned an unusable console channel." }
            val ticket = data["ticket"]?.jsonPrimitive?.content ?: error("Missing console ticket.")
            val password = data["password"]?.jsonPrimitive?.content ?: error("Missing console password.")
            val origin = serverOrigin(connection.address)
            val uri = URI("wss://${origin.rawAuthority}$base/vncwebsocket?port=$port&vncticket=${URLEncoder.encode(ticket, Charsets.UTF_8)}")
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(20)).apply {
                pinnedSslContext(connection.fingerprint)?.let { sslContext(it) }
            }.build().use { client ->
                client.newWebSocketBuilder().header(if (connection.passwordLogin) "Cookie" else "Authorization", if (connection.passwordLogin) connection.tickets.get(connection).cookie else authorizationHeader(DIRECT_PVE_KIND, connection.tokenId, secret))
                    .subprotocols("binary").buildAsync(uri, channel).get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
                try { frameStill(captureRfbFrame(channel, password)) } finally { channel.close() }
            }
        }
    }
    try { return future.get(20, TimeUnit.SECONDS) }
    catch (e: ExecutionException) { throw stillFailure(e) }
    catch (e: TimeoutException) { error("The console capture timed out.") }
    finally { future.cancel(true); channel.close(); worker.shutdownNow() }
    } catch (t: Throwable) { trace.end(error=t); throw t } finally { trace.end() }
}
