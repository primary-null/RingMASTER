package local.ringleader.desktop.web

import local.ringleader.core.Trace
import local.ringleader.core.TraceOutcome
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.text.Charsets.UTF_8

/** A login link. It works once: [used] turns true when a browser has redeemed it. */
data class LoginLink(val url: String, val used: Boolean = false)

/**
 * Whether headless mode prints the one-time login link. To a terminal it always does (it is the only way to hand it over). Into a
 * log (output redirected, as a service manager does) it does not when the operator has a fixed key to sign in with, because the
 * log would keep a link that anyone who can read it could use first.
 */
internal fun linkMayBePrinted(terminal: Boolean, hasFixedKey: Boolean) = terminal || !hasFixedKey

/** What headless mode says when it has started: where it listens, what is switched on, and how to sign in. */
internal fun headlessLines(options: WebOptions, origin: String, loginUrl: String, terminal: Boolean, hasFixedKey: Boolean): List<String> = buildList {
    add("RingMASTER browser server: $origin")
    if (options.hostActions) add("HOST ACTIONS ARE ON. $HOST_ACTIONS_MEANING${if (options.reachableFromOtherComputers) " Other computers can reach this listener." else ""}")
    if (options.reachableFromOtherComputers) add(NETWORK_POSITION_NOTE)
    if (linkMayBePrinted(terminal, hasFixedKey)) {
        add("RingMASTER browser login: $loginUrl")
        add("The link works once. Press Enter here for a new one.")
    } else {
        // A service manager keeps what a service prints in its log, and anyone who can read the log could use the link first.
        add("No login link is printed because this output is not a terminal. Sign in with the key in RINGMASTER_WEB_KEY: $origin/login?key=<that key>")
    }
}

/** Close codes the page reads to stop reconnecting and say why. */
internal val SESSION_EXPIRED = CloseReason(4001.toShort(), "Session expired. Open a new login link from RingMASTER.")
internal val SESSION_SIGNED_OUT = CloseReason(4002.toShort(), "Signed out. Open a new login link from RingMASTER.")
internal val SERVER_STOPPED = CloseReason(4003.toShort(), "RingMASTER stopped serving this browser.")

/**
 * Serves the desktop UI to a browser.
 *
 * Login: [newLoginLink] mints a random key that is redeemed once for a session cookie, then no longer works. [key]
 * (RINGMASTER_WEB_KEY) is the operator's own fixed key: it is reusable by design so a service can be reached after a
 * restart without reading its output, so it is throttled like every login and never printed or shown. Only SHA-256
 * digests of keys are kept, and they are compared in constant time whatever the supplied length.
 *
 * Failed logins are counted per remote address ([LoginGate]); the live one-time link is never made to wait.
 */
class WebServer(val options: WebOptions, key: String? = System.getenv("RINGMASTER_WEB_KEY"), val limits: WebLimits = WebLimits()) : AutoCloseable {
    private class Session(val created: Long) {
        @Volatile var lastSeen = created
        val sockets: MutableSet<CompletableDeferred<CloseReason>> = ConcurrentHashMap.newKeySet()
    }
    private class Pending(val digest: ByteArray, val link: LoginLink)
    private fun random() = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8))
    private val fixedKey = key?.let { require(it.length >= 24) { "RINGMASTER_WEB_KEY must have at least 24 characters" }; digest(it) }
    private val pending = AtomicReference<Pending?>()
    private val linkState = MutableStateFlow<LoginLink?>(null)
    /** The latest login link, or null while the server is stopped. */
    val link: StateFlow<LoginLink?> = linkState.asStateFlow()
    val loginUrl: String get() = linkState.value?.url.orEmpty()
    private val tokens = ConcurrentHashMap<String, Session>()
    private val active = AtomicInteger()
    private val gate = LoginGate(limits)
    @Volatile private var engine: EmbeddedServer<*, *>? = null
    private var shutdownHook: Thread? = null
    var port: Int = options.port; private set
    private val secure get() = options.keystore != null || options.publicOrigin != null
    /**
     * The session cookie's name carries the port this server listens on. Browsers do not keep cookies apart by port (RFC 6265
     * section 8.5): every service on 127.0.0.1 shares one jar, and a second RingMASTER (or any other service) on another port
     * that set a cookie called "ringmaster" replaced this one and ended the session. A name of its own cannot be replaced by
     * accident, and two servers on one host can be signed in at once. (It does not stop a hostile service the browser visits
     * from being SENT the cookie: nothing about a cookie can; the cookie is HttpOnly, SameSite=Strict and expires, and a
     * session is still only as private as the other programs on the machine.) The __Host- prefix, with TLS, pins the cookie
     * to this origin with no Domain and a Secure attribute; browsers accept it only on a Secure cookie.
     */
    private val cookieName get() = (if (secure) "__Host-ringmaster-" else "ringmaster-") + port
    private val bindAuthority get() = "${if (':' in options.bind) "[${options.bind}]" else options.bind}:$port"
    val origin get() = options.publicOrigin ?: "${if(options.keystore == null) "http" else "https"}://$bindAuthority"
    /** Exact Host values answered: the origin, and the listener address that a reverse proxy may pass on. */
    internal val allowedHosts: Set<String> get() = buildSet {
        val public = URI(origin)
        add(public.rawAuthority.lowercase())
        if (public.port == -1) add("${public.host.lowercase()}:443")
        add(bindAuthority.lowercase())
    }
    private val page: String by lazy { WebServer::class.java.getResource("/web/index.html")!!.readText() }
    /** The page's one inline script and style are allowed by hash; nothing else may run or load. */
    internal val contentSecurityPolicy: String by lazy {
        fun hash(tag: String): String {
            val body = page.substringAfter("<$tag>").substringBefore("</$tag>")
            return "'sha256-${Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body.toByteArray(UTF_8)))}'"
        }
        val socket = origin.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        "default-src 'none'; script-src ${hash("script")}; style-src ${hash("style")}; connect-src 'self' $socket; " +
            "frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
    }

    /** Mints a new one-time login link. The previous link stops working even if it was never opened. */
    fun newLoginLink(): String {
        check(engine != null) { "The browser server is not running." }
        val key = random()
        val link = LoginLink("$origin/login?key=${URLEncoder.encode(key, UTF_8)}")
        pending.set(Pending(digest(key), link))
        linkState.value = link
        return link.url
    }
    /** Live session for [token], or null. Expired sessions are removed. [touch] counts this as activity. */
    private fun session(token: String?, touch: Boolean): Session? {
        val key = token ?: return null
        val found = tokens[key] ?: return null
        val now = System.currentTimeMillis()
        if (now - found.lastSeen > limits.idleMs || now - found.created > limits.absoluteMs) { tokens.remove(key, found); return null }
        if (touch) found.lastSeen = now
        return found
    }
    private fun sessionCookie(value: String, maxAge: Int) = Cookie(cookieName, value, maxAge = maxAge, path = "/", secure = secure,
        httpOnly = true, extensions = mapOf("SameSite" to "Strict"))

    fun start(wait: Boolean = false) {
        options.checked()
        val module: Application.() -> Unit = {
            install(WebSockets) { maxFrameSize = 65536; pingPeriod = kotlin.time.Duration.parse("30s") }
            // Every route: exact Host (a rebinding name is refused), exact Origin when one is sent, document headers.
            intercept(ApplicationCallPipeline.Plugins) {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.response.header("X-Content-Type-Options", "nosniff")
                call.response.header("Referrer-Policy", "no-referrer")
                call.response.header("X-Frame-Options", "DENY")
                call.response.header("Content-Security-Policy", contentSecurityPolicy)
                val host = (call.request.headers[HttpHeaders.Host] ?: call.request.headers[":authority"])?.lowercase()
                val requestOrigin = call.request.headers[HttpHeaders.Origin]
                // A path with an empty segment ("//", "//session") is no address this server uses; the router would read it as another.
                val emptySegment = call.request.path().let { it.startsWith("/") && "//" in it }
                if (host == null || host !in allowedHosts || (requestOrigin != null && requestOrigin != origin) || emptySegment) {
                    call.respondText("Refused: this address is not served here.", status = HttpStatusCode.Forbidden); finish()
                }
            }
            routing {
                get("/login") {
                    val now = System.currentTimeMillis()
                    val address = call.request.local.remoteAddress
                    val supplied = digest(call.request.queryParameters["key"].orEmpty())
                    val once = pending.get()
                    val fixed = fixedKey != null && MessageDigest.isEqual(supplied, fixedKey)
                    val oneTime = once != null && MessageDigest.isEqual(supplied, once.digest)
                    // The live one-time link is 256 random bits no peer can guess, so it never waits: a lock that stopped it would only
                    // let whoever trips the lock keep the owner out. Every other key (the operator's, or a guess) waits its address's turn.
                    val locked = if (oneTime) 0L else gate.waitMillis(address)
                    if (locked > 0) {
                        val seconds = (locked + 999) / 1000
                        call.response.header(HttpHeaders.RetryAfter, seconds.toString())
                        call.respondText("Too many failed logins. Try again in $seconds seconds.", status = HttpStatusCode.TooManyRequests); return@get
                    }
                    if (!fixed && !oneTime) {
                        gate.failed(address)
                        Trace.emit(TraceSource.Web, "login", "Browser login refused", outcome = TraceOutcome.Warn)
                        delay(limits.failedLoginDelayMs)
                        call.respondText("This login link is not valid or was used already. Open a new link from RingMASTER.", status = HttpStatusCode.Forbidden); return@get
                    }
                    if (tokens.size >= 128) tokens.entries.removeIf { now - it.value.lastSeen > limits.idleMs || now - it.value.created > limits.absoluteMs }
                    if (tokens.size >= 128) { call.respondText("Too many browser sessions. Sign out of one first.", status = HttpStatusCode.TooManyRequests); return@get }
                    // The one-time key is spent here; a second browser racing the same link loses.
                    if (!fixed && !pending.compareAndSet(once, null)) {
                        call.respondText("This login link is not valid or was used already. Open a new link from RingMASTER.", status = HttpStatusCode.Forbidden); return@get
                    }
                    if (!fixed) linkState.compareAndSet(once!!.link, once.link.copy(used = true))
                    gate.succeeded(address)
                    val token = random(); tokens[token] = Session(now)
                    call.response.cookies.append(sessionCookie(token, (limits.absoluteMs / 1000).toInt().coerceAtLeast(1)))
                    Trace.emit(TraceSource.Web, "login", "Browser login accepted", outcome = TraceOutcome.Ok)
                    call.respondRedirect("/")
                }
                get("/") {
                    call.respondText(if (session(call.request.cookies[cookieName], touch = true) != null) page else "<p>Open the link printed by RingMASTER</p>", ContentType.Text.Html)
                }
                // The page asks this after a dropped socket: 403 means the session is over and reconnecting cannot help.
                get("/status") { call.respond(if (session(call.request.cookies[cookieName], touch = false) != null) HttpStatusCode.NoContent else HttpStatusCode.Forbidden) }
                post("/logout") {
                    if (call.request.header(HttpHeaders.Origin) != origin) { call.respond(HttpStatusCode.Forbidden); return@post }
                    call.request.cookies[cookieName]?.let { tokens.remove(it) }?.sockets?.forEach { it.complete(SESSION_SIGNED_OUT) }
                    call.response.cookies.append(sessionCookie("", 0))
                    call.respond(HttpStatusCode.NoContent)
                }
                route("/session") {
                    intercept(ApplicationCallPipeline.Call) {
                        if (session(call.request.cookies[cookieName], touch = true) == null || call.request.header(HttpHeaders.Origin) != origin) { call.respond(HttpStatusCode.Forbidden); finish() }
                    }
                    webSocket {
                        val token = call.request.cookies[cookieName]
                        val owner = session(token, touch = false) ?: run { close(SESSION_EXPIRED); return@webSocket }
                        if(active.incrementAndGet() > options.maxSessions) { active.decrementAndGet(); close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Session limit")); return@webSocket }
                        val ended = CompletableDeferred<CloseReason>()
                        owner.sockets += ended
                        val trace = Trace.begin(TraceSource.Web, "Browser client connected", session=true)
                        try {
                            coroutineScope {
                                // Browser input counts as session activity; the idle and absolute limits still apply.
                                val scene = launch { WebScene(options.hostActions) { owner.lastSeen = System.currentTimeMillis() }.use { it.run(this@webSocket) } }
                                val watcher = launch {
                                    while (true) {
                                        val reason = withTimeoutOrNull(limits.recheckMs) { ended.await() }
                                            ?: SESSION_EXPIRED.takeIf { session(token, touch = false) !== owner }
                                        if (reason != null) { this@webSocket.close(reason); scene.cancel(); break }
                                    }
                                }
                                scene.join(); watcher.cancel()
                            }
                        } finally { owner.sockets -= ended; trace.end(); active.decrementAndGet() }
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
        newLoginLink()
        Trace.emit(TraceSource.Web, "start", "Browser server started", mapOf("url" to origin, "hostActions" to options.hostActions.toString()))
        // One hook per running server: it is removed when the server stops, so starting and stopping does not collect them.
        shutdownHook?.let { runCatching { Runtime.getRuntime().removeShutdownHook(it) } }
        shutdownHook = Thread { close() }.also { Runtime.getRuntime().addShutdownHook(it) }
        if(wait) {
            val terminal = System.console() != null
            headlessLines(options, origin, loginUrl, terminal, fixedKey != null).forEach(::println)
            // Headless: the terminal is the only place to hand over a link, so it is printed there and nowhere else.
            if (linkMayBePrinted(terminal, fixedKey != null)) {
                Thread({ while (engine != null) { readlnOrNull() ?: break; if (engine != null) println("RingMASTER browser login: ${newLoginLink()}") } }, "browser-login-links").apply { isDaemon = true }.start()
            }
            runBlocking { awaitCancellation() }
        }
    }
    private fun isBindFailure(e: Throwable): Boolean = generateSequence(e) { it.cause }.any { it is java.net.BindException || it is java.net.SocketException }
    override fun close() {
        val running = engine ?: return
        engine = null
        shutdownHook?.let { runCatching { Runtime.getRuntime().removeShutdownHook(it) } }
        shutdownHook = null
        Trace.emit(TraceSource.Web, "stop", "Browser server stopped")
        tokens.values.forEach { session -> session.sockets.forEach { it.complete(SERVER_STOPPED) } }
        running.stop(500, 2000); tokens.clear(); pending.set(null); linkState.value = null
    }
}
