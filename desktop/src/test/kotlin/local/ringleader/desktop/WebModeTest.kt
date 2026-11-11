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

class WebModeTest {
    @Test fun arguments() {
        assertNull(parseWebArgs(emptyList()))
        assertEquals(WEB_DEFAULT_PORT, parseWebArgs(listOf("--web"))!!.port)
        assertFalse(parseWebArgs(listOf("--web"))!!.portExplicit)
        assertTrue(parseWebArgs(listOf("--web","--port","19000"))!!.portExplicit)
        assertEquals(0, parseWebArgs(listOf("--web","--port","0"))!!.port)
        assertFails { parseWebArgs(listOf("--web","--bind","0.0.0.0")) }
        assertTrue(parseWebArgs(listOf("--web","--bind","0.0.0.0","--allow-remote"))!!.allowRemote)
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
            override fun setExitNode(peer: local.ringleader.core.TailscalePeer?) { calls.incrementAndGet() }
            override fun importWireGuard(config: java.nio.file.Path, name: String): String { calls.incrementAndGet(); return "" }
            override fun removeWireGuard(tunnel: local.ringleader.core.VpnTunnel) { calls.incrementAndGet() }
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val session = VpnSession(scope, backend)
            session.hostActions = false
            session.act("test", null, "done") { it.setTunnel(local.ringleader.core.VpnTunnel("x", local.ringleader.core.VpnKind.WireGuard, "wg0", local.ringleader.core.VpnState.Down, "wg0"), true) }
            Thread.sleep(150)
            assertEquals(0, calls.get())
            assertNull(session.busy)
            assertNull(session.message)
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
