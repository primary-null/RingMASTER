package local.ringleader.desktop.web

import local.ringleader.core.Trace
import local.ringleader.core.TraceSource

import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.netty.Netty
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.websocket.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class WebServer(val options: WebOptions, key: String? = System.getenv("RINGMASTER_WEB_KEY")) : AutoCloseable {
    private fun random() = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private val accessKey = key?.also { require(it.length >= 24) { "RINGMASTER_WEB_KEY must have at least 24 characters" } } ?: random()
    private val tokens = ConcurrentHashMap<String, Long>()
    private val active = AtomicInteger()
    private var engine: EmbeddedServer<*, *>? = null
    var port: Int = options.port; private set
    val origin get() = options.publicOrigin ?: "${if(options.keystore == null) "http" else "https"}://${if (':' in options.bind) "[${options.bind}]" else options.bind}:$port"
    val loginUrl get() = "$origin/login?key=${java.net.URLEncoder.encode(accessKey, java.nio.charset.StandardCharsets.UTF_8)}"
    fun start(wait: Boolean = false) {

        require(java.net.InetAddress.getByName(options.bind).isLoopbackAddress || options.allowRemote)
        if (options.keystore == null && !java.net.InetAddress.getByName(options.bind).isLoopbackAddress) System.err.println("WARNING: plain HTTP exposes this session on the network. Use a reverse proxy or tailscale serve.")
        val module: Application.() -> Unit = {
            install(WebSockets) { maxFrameSize = 65536; pingPeriod = kotlin.time.Duration.parse("30s") }
            routing {
                fun valid(call: ApplicationCall): Boolean {
                    val token = call.request.cookies["ringmaster"] ?: return false
                    val last = tokens[token] ?: return false
                    if (System.currentTimeMillis() - last > 1800000) { tokens.remove(token); return false }
                    tokens[token] = System.currentTimeMillis(); return true
                }
                get("/login") {
                    val supplied = call.request.queryParameters["key"].orEmpty()
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.response.header("Referrer-Policy", "no-referrer")
                    if (!java.security.MessageDigest.isEqual(supplied.toByteArray(), accessKey.toByteArray())) { call.respond(HttpStatusCode.Forbidden); return@get }
                    if(tokens.size >= 128) tokens.entries.removeIf { System.currentTimeMillis() - it.value > 1800000 }
                    if(tokens.size >= 128) { call.respond(HttpStatusCode.TooManyRequests); return@get }
                    val token = random(); tokens[token] = System.currentTimeMillis()
                    call.response.cookies.append(Cookie("ringmaster", token, path = "/", httpOnly = true, secure = options.keystore != null || options.publicOrigin != null, extensions = mapOf("SameSite" to "Strict")))
                    call.respondRedirect("/")
                }
                get("/") {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.respondText(if(valid(call)) WebServer::class.java.getResource("/web/index.html")!!.readText() else "<p>Open the link printed by RingMASTER</p>", ContentType.Text.Html)
                }
                route("/session") {
                    intercept(ApplicationCallPipeline.Call) {
                        if (!valid(call) || call.request.header(HttpHeaders.Origin) != origin) { call.respond(HttpStatusCode.Forbidden); finish() }
                    }
                    webSocket {
                        if(active.incrementAndGet() > options.maxSessions) { active.decrementAndGet(); close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Session limit")); return@webSocket }
                        val trace = Trace.begin(TraceSource.Web, "Browser client connected", session=true)
                        try { WebScene(options.hostActions).use { it.run(this) } } finally { trace.end(); active.decrementAndGet() }
                    }
                }
            }
        }
        fun build(port: Int): EmbeddedServer<*, *> = if(options.keystore == null) embeddedServer(CIO, host=options.bind, port=port, module=module)
        else {
            val password = requireNotNull(System.getenv("RINGMASTER_WEB_KEYSTORE_PASSWORD")) { "Set RINGMASTER_WEB_KEYSTORE_PASSWORD" }.toCharArray()
            val store = java.security.KeyStore.getInstance("PKCS12")
            java.io.FileInputStream(options.keystore).use { store.load(it,password) }
            val alias = store.aliases().toList().first { store.isKeyEntry(it) }
            embeddedServer(Netty, applicationEnvironment {}, {
                sslConnector(store, alias, { password }, { password }) { host=options.bind; this.port=port }
            }, module)
        }
        // A named port is used as given. The default walks forward past ports that are taken or reserved by the OS.
        val candidates = if (options.portExplicit || options.port == 0) listOf(options.port) else (options.port until options.port + 20).toList()
        var lastFailure: Throwable? = null
        for (candidate in candidates) {
            val server = build(candidate)
            try {
                server.start(false)
                engine = server
                port = runBlocking { server.engine.resolvedConnectors().first().port }
                break
            } catch (e: Exception) {
                runCatching { server.stop(0, 0) }
                if (!isBindFailure(e)) throw e
                lastFailure = e
            }
        }
        if (engine == null) error(if (candidates.size == 1) "Port ${candidates.single()} is in use or reserved on this computer. Choose another with --port."
            else "No free port between ${candidates.first()} and ${candidates.last()}. Choose one with --port.".also { lastFailure })
        Trace.emit(TraceSource.Web, "start", "Browser server started", mapOf("url" to origin))
        println("RingMASTER browser login: $loginUrl")
        Runtime.getRuntime().addShutdownHook(Thread { close() })
        if(wait) runBlocking { awaitCancellation() }
    }
    private fun isBindFailure(e: Throwable): Boolean = generateSequence(e) { it.cause }.any { it is java.net.BindException || it is java.net.SocketException }
    override fun close() { Trace.emit(TraceSource.Web, "stop", "Browser server stopped"); engine?.stop(500, 2000); engine = null; tokens.clear() }
}
