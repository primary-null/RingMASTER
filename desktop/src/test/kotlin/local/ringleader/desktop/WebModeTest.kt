@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import kotlin.test.*
import local.ringleader.desktop.web.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.net.URI
import java.net.http.*
import java.nio.ByteBuffer
import java.util.concurrent.*
import org.jetbrains.skia.*

/**
 * Centre of the Explore demo label in the 1440x900 Desk frame. Anchors on the amber outline of the disabled Connect
 * button, then takes the bright label just to its right on the same row (checked against Windows and Debian frames).
 */
internal fun exploreDemoCentre(screen: java.awt.image.BufferedImage): Pair<Int, Int> {
    fun px(x: Int, y: Int) = screen.getRGB(x, y).let { Triple(it shr 16 and 255, it shr 8 and 255, it and 255) }
    var ax1 = -1; var ay0 = Int.MAX_VALUE; var ay1 = -1
    for (y in 480 until 680) for (x in 720 until 1420) {
        val (r, g, b) = px(x, y)
        if (r > 200 && g in 111..189 && b < 90) { ax1 = maxOf(ax1, x); ay0 = minOf(ay0, y); ay1 = maxOf(ay1, y) }
    }
    check(ax1 >= 0) { "Connect button outline not found in the Desk frame" }
    var minX = Int.MAX_VALUE; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1
    for (y in ay0..ay1) for (x in ax1 + 1 until minOf(screen.width, ax1 + 300)) {
        val (r, g, b) = px(x, y)
        if (r > 200 && g > 200 && b > 200) { minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y) }
    }
    check(maxX >= 0) { "Explore demo label not found beside Connect" }
    return (minX + maxX) / 2 to (minY + maxY) / 2
}

/** Status code of one plain HTTP/1.1 request with a chosen Host, which java.net.http does not allow setting. */
internal fun rawStatus(port: Int, path: String, host: String, headers: String = ""): Int =
    java.net.Socket(java.net.InetAddress.getLoopbackAddress(), port).use { socket ->
        socket.soTimeout = 15000
        socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $host\r\n${headers}Connection: close\r\n\r\n".toByteArray())
        socket.getInputStream().bufferedReader().readLine().split(' ')[1].toInt()
    }

class WebModeTest {
    /** A fixed operator login phrase for these tests (not a secret). */
    private val operatorLogin = "operator-fixed-login-of-24-plus-chars"
    /** The sample handshake nonce from RFC 6455, section 1.3. */
    private val RFC6455_SAMPLE_NONCE = "dGhlIHNhbXBsZSBub25jZQ=="
    private val quick = WebLimits(failedLoginDelayMs = 0)
    private val client = HttpClient.newHttpClient()
    private fun get(url: String, vararg headers: String): HttpResponse<String> =
        client.send(HttpRequest.newBuilder(URI(url)).apply { if (headers.isNotEmpty()) headers(*headers) }.GET().build(), HttpResponse.BodyHandlers.ofString())
    private fun cookieOf(login: HttpResponse<String>) = login.headers().firstValue("set-cookie").get().substringBefore(';')
    private fun handshakeStatus(server: WebServer, cookie: String?): Int {
        val builder = client.newWebSocketBuilder().header("Origin", server.origin)
        if (cookie != null) builder.header("Cookie", cookie)
        return try { builder.buildAsync(URI(server.origin.replace("http", "ws") + "/session"), object : WebSocket.Listener {}).join().abort(); 101 }
        catch (e: CompletionException) { (e.cause as WebSocketHandshakeException).response.statusCode() }
    }
    /** Opens /session and reports the server's close code and reason. */
    private fun openSocket(server: WebServer, cookie: String, closed: CompletableFuture<Pair<Int, String>>): WebSocket =
        client.newWebSocketBuilder().header("Origin", server.origin).header("Cookie", cookie)
            .buildAsync(URI(server.origin.replace("http", "ws") + "/session"), object : WebSocket.Listener {
                override fun onOpen(ws: WebSocket) { ws.request(Long.MAX_VALUE) }
                override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? { closed.complete(statusCode to reason); return null }
            }).join()

    @Test fun loginLinkWorksOnceAndANewLinkReplacesIt() {
        WebServer(WebOptions(port=0),operatorLogin,quick).use { server ->
            server.start()
            val first=server.loginUrl
            assertFalse(first.contains("operator-fixed-login"),"the fixed key is never put in a link")
            assertFalse(server.link.value!!.used)
            assertEquals(302,get(first).statusCode())
            assertTrue(server.link.value!!.used)
            assertEquals(403,get(first).statusCode(),"a used link no longer signs in")
            val second=server.newLoginLink(); val third=server.newLoginLink()
            assertNotEquals(second,third)
            assertEquals(403,get(second).statusCode(),"minting a link cancels the unused one")
            assertEquals(302,get(third).statusCode())
            assertEquals(403,get(third).statusCode())
            // RINGMASTER_WEB_KEY is the operator's reusable key.
            repeat(2) { assertEquals(302,get("${server.origin}/login?key=$operatorLogin").statusCode()) }
        }
        WebServer(WebOptions(port=0),null,quick).use { server ->
            server.start()
            assertEquals(302,get(server.loginUrl).statusCode())
            assertEquals(403,get(server.loginUrl).statusCode())
        }
    }

    @Test fun failedLoginsAreDelayedThenLockedOut() {
        WebServer(WebOptions(port=0),operatorLogin,WebLimits(failedLoginDelayMs=300,failuresBeforeLock=3,lockMs=60_000)).use { server ->
            server.start()
            val started=System.nanoTime()
            assertEquals(403,get("${server.origin}/login?key=x").statusCode())
            assertTrue(System.nanoTime()-started>=TimeUnit.MILLISECONDS.toNanos(300),"a failed login is answered late")
            // Any length of guess is compared the same way, including a long one.
            assertEquals(403,get("${server.origin}/login?key=${"y".repeat(500)}").statusCode())
            assertEquals(403,get("${server.origin}/login?key=").statusCode())
            val locked=get(server.loginUrl)
            assertEquals(429,locked.statusCode())
            assertTrue(locked.headers().firstValue("retry-after").isPresent)
            assertContains(locked.body(),"Try again")
            assertEquals(429,get("${server.origin}/login?key=$operatorLogin").statusCode())
            assertFalse(server.link.value!!.used,"a refused attempt does not spend the link")
        }
        WebServer(WebOptions(port=0),operatorLogin,WebLimits(failedLoginDelayMs=0,failuresBeforeLock=3)).use { server ->
            server.start()
            // A success resets the count.
            repeat(2) { assertEquals(403,get("${server.origin}/login?key=x").statusCode()) }
            assertEquals(302,get(server.loginUrl).statusCode())
            repeat(2) { assertEquals(403,get("${server.origin}/login?key=x").statusCode()) }
            assertEquals(302,get("${server.origin}/login?key=$operatorLogin").statusCode())
        }
    }

    @Test fun hostAndOriginAllowlistOnEveryRoute() {
        WebServer(WebOptions(port=0),operatorLogin,quick).use { server ->
            server.start()
            val own="127.0.0.1:${server.port}"
            val key=server.loginUrl.substringAfter("?")
            assertEquals(200,rawStatus(server.port,"/",own))
            val upgrade="Upgrade: websocket\r\nSec-WebSocket-Key: $RFC6455_SAMPLE_NONCE\r\nSec-WebSocket-Version: 13\r\nOrigin: ${server.origin}\r\n"
            for(host in listOf("rebind.example:${server.port}","localhost:${server.port}","127.0.0.1","127.0.0.1:1")) {
                assertEquals(403,rawStatus(server.port,"/",host),host)
                assertEquals(403,rawStatus(server.port,"/login?$key",host),host)
                assertEquals(403,rawStatus(server.port,"/status",host),host)
                assertEquals(403,rawStatus(server.port,"/no-such-page",host),host)
                assertEquals(403,rawStatus(server.port,"/session",host,upgrade),host)
            }
            assertFalse(server.link.value!!.used,"a refused host does not spend the link")
            assertEquals(403,get(server.loginUrl,"Origin","https://foreign.invalid").statusCode())
            assertEquals(403,get(server.loginUrl,"Origin","null").statusCode())
            assertEquals(302,get(server.loginUrl).statusCode())
        }
    }

    @Test fun documentHeadersAndCookieFlags() {
        WebServer(WebOptions(port=0),operatorLogin,quick).use { server ->
            server.start()
            val login=get(server.loginUrl)
            val setCookie=login.headers().firstValue("set-cookie").get()
            assertTrue(setCookie.startsWith("ringmaster="),setCookie)
            val flags=setCookie.split(';').map { it.trim().lowercase() }
            assertTrue("httponly" in flags,setCookie); assertTrue("samesite=strict" in flags,setCookie); assertTrue("path=/" in flags,setCookie)
            assertFalse("secure" in flags,"plain loopback HTTP cannot keep a Secure cookie")
            assertTrue(flags.any { it.startsWith("max-age=") },setCookie)
            val page=WebServer::class.java.getResource("/web/index.html")!!.readText()
            fun hash(tag: String)="'sha256-"+java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(page.substringAfter("<$tag>").substringBefore("</$tag>").toByteArray()))+"'"
            for(response in listOf(login,get("${server.origin}/"),get("${server.origin}/","Cookie",cookieOf(login)))) {
                val csp=response.headers().firstValue("content-security-policy").get()
                assertContains(csp,"frame-ancestors 'none'"); assertContains(csp,"default-src 'none'")
                assertContains(csp,"script-src ${hash("script")}"); assertContains(csp,"style-src ${hash("style")}")
                assertFalse(csp.contains("unsafe-inline"))
                assertEquals("nosniff",response.headers().firstValue("x-content-type-options").get())
                assertEquals("no-referrer",response.headers().firstValue("referrer-policy").get())
                assertEquals("no-store",response.headers().firstValue("cache-control").get())
            }
            assertContains(get("${server.origin}/","Cookie",cookieOf(login)).body(),"<script>")
        }
    }

    @Test fun logoutEndsTheSessionAndItsSockets() {
        WebServer(WebOptions(port=0),operatorLogin,quick).use { server ->
            server.start()
            val cookie=cookieOf(get(server.loginUrl))
            fun status()=get("${server.origin}/status","Cookie",cookie).statusCode()
            assertEquals(204,status())
            val closed=CompletableFuture<Pair<Int,String>>()
            val ws=openSocket(server,cookie,closed)
            try {
                fun logout(vararg headers: String)=client.send(HttpRequest.newBuilder(URI("${server.origin}/logout")).headers("Cookie",cookie,*headers).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString())
                assertEquals(403,logout().statusCode(),"logout needs this page's Origin")
                assertEquals(403,logout("Origin","https://foreign.invalid").statusCode())
                assertEquals(204,status())
                val out=logout("Origin",server.origin)
                assertEquals(204,out.statusCode())
                assertContains(out.headers().firstValue("set-cookie").get().lowercase(),"max-age=0")
                val (code,reason)=closed.get(20,TimeUnit.SECONDS)
                assertEquals(4002,code); assertContains(reason,"login link")
                assertEquals(403,status())
                assertEquals(403,handshakeStatus(server,cookie))
                assertContains(get("${server.origin}/","Cookie",cookie).body(),"Open the link")
            } finally { ws.abort() }
        }
    }

    @Test fun openSocketClosesWhenTheSessionReachesItsLifetime() {
        WebServer(WebOptions(port=0),operatorLogin,WebLimits(failedLoginDelayMs=0,absoluteMs=4_000,recheckMs=100)).use { server ->
            server.start()
            val started=System.nanoTime()
            val cookie=cookieOf(get(server.loginUrl))
            val closed=CompletableFuture<Pair<Int,String>>()
            val ws=openSocket(server,cookie,closed)
            try {
                // Input keeps the idle clock fresh; it does not extend the absolute lifetime.
                repeat(5) { ws.sendText("""{"t":"pointer","kind":"move","x":1,"y":1}""",true).join(); Thread.sleep(200) }
                assertFalse(closed.isDone,"still inside the lifetime")
                val (code,reason)=closed.get(30,TimeUnit.SECONDS)
                assertTrue(System.nanoTime()-started>=TimeUnit.MILLISECONDS.toNanos(4_000))
                assertEquals(4001,code); assertContains(reason,"Open a new login link")
                assertEquals(403,get("${server.origin}/status","Cookie",cookie).statusCode())
                assertEquals(403,handshakeStatus(server,cookie))
            } finally { ws.abort() }
        }
        WebServer(WebOptions(port=0),operatorLogin,WebLimits(failedLoginDelayMs=0,idleMs=1_500,recheckMs=100)).use { server ->
            server.start()
            val cookie=cookieOf(get(server.loginUrl))
            val closed=CompletableFuture<Pair<Int,String>>()
            val ws=openSocket(server,cookie,closed)
            try {
                // No input at all: the idle limit closes the socket.
                val (code,_)=closed.get(30,TimeUnit.SECONDS)
                assertEquals(4001,code)
            } finally { ws.abort() }
        }
    }
    @Test fun arguments() {
        assertNull(parseWebArgs(emptyList()))
        assertEquals(WEB_DEFAULT_PORT, parseWebArgs(listOf("--web"))!!.port)
        assertFalse(parseWebArgs(listOf("--web"))!!.portExplicit)
        assertTrue(parseWebArgs(listOf("--web","--port","19000"))!!.portExplicit)
        assertEquals(0, parseWebArgs(listOf("--web","--port","0"))!!.port)
        assertFails { parseWebArgs(listOf("--web","--bind","0.0.0.0")) }
        // A wildcard bind is no origin a browser can send; a network address needs TLS here or at a proxy.
        for(wildcard in listOf("0.0.0.0","::")) {
            val refused=assertFails { parseWebArgs(listOf("--web","--bind",wildcard,"--allow-remote")) }
            assertContains(refused.message.orEmpty(),"--public-origin")
            assertTrue(parseWebArgs(listOf("--web","--bind",wildcard,"--allow-remote","--public-origin","https://ring.example.com"))!!.allowRemote)
        }
        val clear=assertFails { parseWebArgs(listOf("--web","--bind","192.0.2.10","--allow-remote")) }
        assertContains(clear.message.orEmpty(),"--tls-keystore"); assertContains(clear.message.orEmpty(),"--public-origin")
        assertEquals("ring.p12",parseWebArgs(listOf("--web","--bind","192.0.2.10","--allow-remote","--tls-keystore","ring.p12"))!!.keystore)
        assertNotNull(parseWebArgs(listOf("--web","--bind","192.0.2.10","--allow-remote","--public-origin","https://ring.example.com")))
        assertFails { WebServer(WebOptions(bind="0.0.0.0",allowRemote=true,port=0),"x".repeat(32)).use { it.start() } }
        assertFails { WebServer(WebOptions(bind="192.0.2.10",allowRemote=true,port=0),"x".repeat(32)).use { it.start() } }
        for(args in listOf(listOf("--web","--port"),listOf("--web","--port","-1"),listOf("--web","--unknown"))) assertFails { parseWebArgs(args) }
        assertFails { WebServer(WebOptions(), "short") }
        // --public-origin
        assertEquals("https://example.com",parseWebArgs(listOf("--web","--public-origin","https://example.com"))!!.publicOrigin)
        assertEquals("https://example.com",parseWebArgs(listOf("--web","--public-origin","HTTPS://EXAMPLE.COM:443/"))!!.publicOrigin)
        assertEquals("https://example.com:8443",parseWebArgs(listOf("--web","--public-origin","https://example.com:8443"))!!.publicOrigin)
        assertFails { parseWebArgs(listOf("--web","--public-origin","http://example.com")) }
        assertFails { parseWebArgs(listOf("--web","--public-origin","https://example.com/path")) }
        assertFails { parseWebArgs(listOf("--web","--public-origin","https://example.com?q=1")) }
        assertFails { parseWebArgs(listOf("--web","--public-origin","https://user@example.com")) }
        // --allow-host-actions
        assertFalse(parseWebArgs(listOf("--web"))!!.hostActions)
        assertTrue(parseWebArgs(listOf("--web","--allow-host-actions"))!!.hostActions)
        assertFalse(AppHost(true).hostActions)
        assertTrue(AppHost(true, true).hostActions)
        assertTrue(DesktopAppHost().hostActions)
    }

    @Test fun vpnActSkipsBackendWhenHostActionsOff() {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val backend = object : local.ringleader.core.VpnBackend {
            override val platform = "Test"
            override fun snapshot() = local.ringleader.core.VpnSnapshot(java.time.Instant.now(), emptyList(), false, null, true, emptyList(), emptyList())
            override fun routeTo(host: String, snapshot: local.ringleader.core.VpnSnapshot?) = local.ringleader.core.VpnRoute(host, host, null, null, null)
            override fun setTunnel(tunnel: local.ringleader.core.VpnTunnel, up: Boolean) { calls.incrementAndGet() }
            override fun setExitNode(peer: local.ringleader.core.TailscalePeer?, allowLanAccess: Boolean) { calls.incrementAndGet() }
            override fun importWireGuard(config: java.nio.file.Path, name: String): String { calls.incrementAndGet(); return "" }
            override fun removeWireGuard(tunnel: local.ringleader.core.VpnTunnel) { calls.incrementAndGet() }
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val session = VpnSession(scope, backend, hostActions = false)
            session.act("test", null, "done") { it.setTunnel(local.ringleader.core.VpnTunnel("x", local.ringleader.core.VpnKind.WireGuard, "wg0", local.ringleader.core.VpnState.Down, "wg0"), true) }
            Thread.sleep(150)
            assertEquals(0, calls.get())
            assertNull(session.busy)
            assertEquals(local.ringleader.core.VPN_HOST_ACTIONS_REASON, session.message)
            assertTrue(session.failed)
        } finally { scope.cancel() }
    }
    @Test fun busyDefaultPortFallsForwardButANamedPortDoesNot() {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { busy ->
            val taken = busy.localPort
            WebServer(WebOptions(port = taken), "x".repeat(32)).use { server ->
                server.start()
                assertTrue(server.port in (taken + 1) until (taken + 20), "moved past the busy port")
            }
            val refused = assertFails { WebServer(WebOptions(port = taken, portExplicit = true), "x".repeat(32)).use { it.start() } }
            assertTrue(refused.message.orEmpty().contains("--port"))
        }
    }

    @Test fun tiles() {
        Surface.makeRasterN32Premul(130,70).use { surface ->
            surface.canvas.clear(Color.BLACK)
            val diff=TileDiff()
            surface.makeImageSnapshot().use { assertEquals(6,diff.frame(it).size); assertEquals(0,diff.frame(it).size) }
            Paint().use { paint -> paint.color=Color.WHITE; surface.canvas.drawRect(Rect.makeXYWH(1f,1f,2f,2f),paint) }
            surface.makeImageSnapshot().use { assertEquals(1,diff.frame(it).size) }
        }
    }
    @Test fun typing() = runBlocking {
        var text by mutableStateOf("")
        val focus=FocusRequester()
        val scene=ImageComposeScene(400,120,coroutineContext=coroutineContext) {
            BasicTextField(text,{text=it},Modifier.fillMaxSize().focusRequester(focus))
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
        try {
            repeat(3) { scene.render(System.nanoTime()).close(); yield() }
            sendText(scene,"Hello ?")
            scene.render(System.nanoTime()).close()
            assertEquals("Hello ?",text)
            assertEquals(androidx.compose.ui.input.key.Key.Two,browserKey("2","Digit2"))
            scene.constraints=androidx.compose.ui.unit.Constraints.fixed(500,200)
            scene.render(System.nanoTime()).close()
            assertEquals("Hello ?",text)
        } finally { scene.close() }
    }
    @Test fun sessionPathBypassBlocked() {
        val client=HttpClient.newHttpClient()
        WebServer(WebOptions(port=0),"test-key-with-at-least-24-characters").use { server ->
            server.start()
            val login=client.send(HttpRequest.newBuilder(URI(server.loginUrl)).GET().build(),HttpResponse.BodyHandlers.ofString())
            val cookie=login.headers().firstValue("set-cookie").get().substringBefore(';')
            fun rejected(path: String, cookie: String?, origin: String) {
                val builder=client.newWebSocketBuilder().header("Origin",origin)
                if(cookie!=null) builder.header("Cookie",cookie)
                val error=assertFails { builder.buildAsync(URI(server.origin.replace("http","ws")+path),object: WebSocket.Listener {}).join() }
                assertEquals(403,((error as CompletionException).cause as WebSocketHandshakeException).response.statusCode())
            }
            rejected("//session",null,server.origin)
            rejected("/%73ession",null,server.origin)
            rejected("//session",cookie,"https://foreign.invalid")
            rejected("/%73ession",cookie,"https://foreign.invalid")
        }
    }
    @Test fun publicOriginCookieAndOriginCheck() {
        val client=HttpClient.newHttpClient()
        val browserGate="ringmaster-browser-gate-value"
        WebServer(WebOptions(port=0, publicOrigin="https://ring.example.com"),browserGate).use { server ->
            server.start()
            val bindOrigin="http://127.0.0.1:${server.port}"
            val login=client.send(HttpRequest.newBuilder(URI("$bindOrigin/login?key=$browserGate")).GET().build(),HttpResponse.BodyHandlers.ofString())
            assertEquals(302,login.statusCode())
            val setCookie=login.headers().firstValue("set-cookie").get()
            assertTrue(setCookie.lowercase().contains("secure"))
            assertTrue(setCookie.startsWith("__Host-ringmaster="),setCookie)
            // The public name is served (a proxy that keeps Host), as is the listener address (a proxy that rewrites it).
            assertEquals(200,rawStatus(server.port,"/","ring.example.com"))
            assertEquals(200,rawStatus(server.port,"/","127.0.0.1:${server.port}"))
            assertEquals(403,rawStatus(server.port,"/","rebind.example:${server.port}"))
            val cookie=setCookie.substringBefore(';')
            val builder=client.newWebSocketBuilder().header("Origin",bindOrigin).header("Cookie",cookie)
            val error=assertFails { builder.buildAsync(URI("ws://127.0.0.1:${server.port}/session"),object: WebSocket.Listener {}).join() }
            assertEquals(403,((error as CompletionException).cause as WebSocketHandshakeException).response.statusCode())
        }
    }
    @Test fun forwardedHeadersIgnored() {
        val client=HttpClient.newHttpClient()
        WebServer(WebOptions(port=0),"test-key-with-at-least-24-characters").use { server ->
            server.start()
            val login=client.send(HttpRequest.newBuilder(URI(server.loginUrl)).header("X-Forwarded-Proto","https").header("Forwarded","proto=https").GET().build(),HttpResponse.BodyHandlers.ofString())
            assertEquals(302,login.statusCode())
            assertFalse(login.headers().firstValue("set-cookie").get().lowercase().contains("secure"))
        }
    }
    @Test fun authenticationAndDeskRoundTrip() {
        val client=HttpClient.newHttpClient()
        WebServer(WebOptions(port=0),"test-key-with-at-least-24-characters").use { server ->
            server.start()
            fun get(path: String)=client.send(HttpRequest.newBuilder(URI(server.origin+path)).GET().build(),HttpResponse.BodyHandlers.ofString())
            assertContains(get("/").body(),"Open the link")
            assertEquals(403,get("/login?key=bad").statusCode())
            assertFalse(get("/login?key=bad").body().contains("test-key"))
            fun rejected(cookie: String?, origin: String) {
                val builder=client.newWebSocketBuilder().header("Origin",origin)
                if(cookie!=null)builder.header("Cookie",cookie)
                val error=assertFails { builder.buildAsync(URI(server.origin.replace("http","ws")+"/session"),object: WebSocket.Listener {}).join() }
                assertEquals(403,((error as CompletionException).cause as WebSocketHandshakeException).response.statusCode())
            }
            rejected(null,server.origin)
            val login=client.send(HttpRequest.newBuilder(URI(server.loginUrl)).GET().build(),HttpResponse.BodyHandlers.ofString())
            assertEquals(302,login.statusCode())
            val cookie=login.headers().firstValue("set-cookie").get().substringBefore(';')
            assertFalse(cookie.contains("test-key"))
            rejected(cookie,"https://foreign.invalid")
            val frames=LinkedBlockingQueue<ByteArray>()
            val listener=object: WebSocket.Listener {
                private val bytes=java.io.ByteArrayOutputStream()
                override fun onOpen(ws: WebSocket) { ws.request(1) }
                override fun onBinary(ws: WebSocket,data: ByteBuffer,last: Boolean): CompletionStage<*>? {
                    val chunk=ByteArray(data.remaining()); data.get(chunk);bytes.write(chunk)
                    if(last) { frames.add(bytes.toByteArray());bytes.reset() };ws.request(1);return null
                }
            }
            val ws=client.newWebSocketBuilder().header("Origin",server.origin).header("Cookie",cookie).buildAsync(URI(server.origin.replace("http","ws")+"/session"),listener).join()
            try {
                // Malformed messages are dropped one by one; the socket and its Desk stay up.
                ws.sendText("""{not json""",true).join()
                ws.sendText("""{"t":"pointer","mods":"not an object"}""",true).join()
                ws.sendText("""[1,2,3]""",true).join()
                ws.sendText("""{"t":"resize","w":1440,"h":900,"dpr":1}""",true).join()
                val screen=java.awt.image.BufferedImage(1440,900,java.awt.image.BufferedImage.TYPE_INT_ARGB)
                val covered=mutableSetOf<Pair<Int,Int>>()
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
                while(covered.size<345 && System.nanoTime()<deadline) {
                    val frame=frames.poll(5,TimeUnit.SECONDS) ?: continue
                    val header=ByteBuffer.wrap(frame); val x=header.int; val y=header.int; covered.add(x to y)
                    val tile=javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(frame,16,frame.size-16)); screen.createGraphics().let { it.drawImage(tile,x,y,null); it.dispose() }
                }
                assertEquals(345,covered.size,"Full 1440x900 frame")
                javax.imageio.ImageIO.write(screen,"png",java.io.File(System.getProperty("java.io.tmpdir"),"ringmaster-web-test.png"))
                // Find Explore demo in the frame instead of trusting fixed coordinates: font metrics move it by ~130 px between
                // Windows and Linux. Its label is the only bright text in the connect panel's button band (labels,
                // placeholders and the disabled Connect are muted).
                val (cx, cy) = exploreDemoCentre(screen)
                frames.clear()
                ws.sendText("""{"t":"pointer","kind":"down","x":$cx,"y":$cy,"button":0,"buttons":1}""",true).join()
                ws.sendText("""{"t":"pointer","kind":"up","x":$cx,"y":$cy,"button":0,"buttons":0}""",true).join()
                assertNotNull(frames.poll(10,TimeUnit.SECONDS),"Navigation must invalidate the scene")
            } finally { ws.sendClose(WebSocket.NORMAL_CLOSURE,"done").join() }
        }
    }
}
