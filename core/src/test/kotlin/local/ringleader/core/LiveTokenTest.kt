package local.ringleader.core

import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.test.assertTrue

/**
 * Opt-in live check of token login through the real client: RINGMASTER_LIVE_PVE=1 with PVE_HOST and PVE_TOKEN in the
 * environment. Learns the server's certificate fingerprint first (test only), then pins it. Prints counts, never secrets.
 */
class LiveTokenTest {
    @Test fun tokenLoginStillWorks() {
        if (System.getenv("RINGMASTER_LIVE_PVE") != "1") return
        val host = System.getenv("PVE_HOST") ?: return
        val raw = System.getenv("PVE_TOKEN")?.removePrefix("PVEAPIToken=") ?: return
        val tokenId = raw.substringBefore('='); val secret = raw.substringAfter('=')
        val origin = serverOrigin(if (host.startsWith("https://")) host else "https://$host")
        val port = if (origin.port > 0) origin.port else 8006
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        })
        val ctx = SSLContext.getInstance("TLS").apply { init(null, trustAll, null) }
        val pin = (ctx.socketFactory.createSocket(origin.host, port) as SSLSocket).use { s ->
            s.startHandshake(); MessageDigest.getInstance("SHA-256").digest(s.session.peerCertificates[0].encoded).joinToString("") { "%02x".format(it) }
        }
        Connection("https://${origin.host}:$port", tokenId, secret, pin).use { c ->
            val overview = PveClient().overview(c)
            println("LIVE-TOKEN nodes=${overview.nodes} guests=${overview.guests.size} passwordLogin=${c.passwordLogin}")
            assertTrue(overview.guests.isNotEmpty())
        }
        // Password path against the real server: a user that does not exist must be refused cleanly (HTTP 401).
        val refused = runCatching { Connection("https://${origin.host}:$port", "ringmaster-probe-nouser@pve", "not-a-password", pin).use { PveClient().overview(it) } }.exceptionOrNull()
        println("LIVE-PASSWORD-REFUSAL ${refused?.javaClass?.simpleName}: ${refused?.message}")
        assertTrue(refused is LoginRefusedException)
    }

    /** Opt-in live VNC console on a disposable test VM: RINGMASTER_LIVE_PVE=1 RINGMASTER_LIVE_VNC_VMID=<vmid> (+ PVE_HOST/PVE_TOKEN). */
    @Test fun vncConsoleStreamsFramesAndTakesInput() {
        if (System.getenv("RINGMASTER_LIVE_PVE") != "1") return
        val vmid = System.getenv("RINGMASTER_LIVE_VNC_VMID")?.toIntOrNull() ?: return
        val host = System.getenv("PVE_HOST") ?: return
        val raw = System.getenv("PVE_TOKEN")?.removePrefix("PVEAPIToken=") ?: return
        val origin = serverOrigin(if (host.startsWith("https://")) host else "https://$host")
        val port = if (origin.port > 0) origin.port else 8006
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        })
        val ctx = SSLContext.getInstance("TLS").apply { init(null, trustAll, null) }
        val pin = (ctx.socketFactory.createSocket(origin.host, port) as SSLSocket).use { s ->
            s.startHandshake(); MessageDigest.getInstance("SHA-256").digest(s.session.peerCertificates[0].encoded).joinToString("") { "%02x".format(it) }
        }
        Connection("https://${origin.host}:$port", raw.substringBefore('='), raw.substringAfter('='), pin).use { c ->
            val guest = PveClient().overview(c).guests.single { it.id == vmid && it.kind == "VM" }
            PveClient().consoleSession(c, guest).use { session ->
                val deadline = System.currentTimeMillis() + 15_000
                while (session.version == 0L && session.reason == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
                val buffer = IntArray(session.width * session.height)
                val shot = session.readInto(buffer)
                val distinct = buffer.distinct().size
                println("LIVE-VNC ${shot.width}x${shot.height} version=${shot.version} distinctColours=$distinct reason=${session.reason}")
                System.getenv("RINGMASTER_LIVE_VNC_PNG")?.let { path ->
                    val img = java.awt.image.BufferedImage(shot.width, shot.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
                    img.setRGB(0, 0, shot.width, shot.height, buffer, 0, shot.width)
                    javax.imageio.ImageIO.write(img, "png", java.io.File(path))
                }
                // Visible, harmless input: right-click the empty desktop (opens its menu), then Escape closes it.
                val before = session.version
                session.pointer(0, shot.width / 2, shot.height / 2); Thread.sleep(200)
                session.pointer(4, shot.width / 2, shot.height / 2); session.pointer(0, shot.width / 2, shot.height / 2)
                Thread.sleep(1500)
                val afterMenu = session.version
                System.getenv("RINGMASTER_LIVE_VNC_PNG")?.let { path ->
                    val b2 = IntArray(session.width * session.height); val s2 = session.readInto(b2)
                    val img = java.awt.image.BufferedImage(s2.width, s2.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
                    img.setRGB(0, 0, s2.width, s2.height, b2, 0, s2.width)
                    javax.imageio.ImageIO.write(img, "png", java.io.File(path.replace(".png", "-menu.png")))
                }
                session.key(true, 0xff1b); session.key(false, 0xff1b)
                Thread.sleep(1000)
                println("LIVE-VNC versions: start=$before afterRightClick=$afterMenu afterEscape=${session.version} reason=${session.reason}")
                assertTrue(shot.width > 0 && shot.version > 0 && session.reason == null)
            }
        }
    }

    /** Opt-in: the node network read and parser against the live server (counts and types only). */
    @Test fun nodeNetworkReadsLive() {
        if (System.getenv("RINGMASTER_LIVE_PVE") != "1") return
        val host = System.getenv("PVE_HOST") ?: return
        val raw = System.getenv("PVE_TOKEN")?.removePrefix("PVEAPIToken=") ?: return
        val node = System.getenv("RINGMASTER_LIVE_NODE") ?: return
        val origin = serverOrigin(if (host.startsWith("https://")) host else "https://$host")
        val port = if (origin.port > 0) origin.port else 8006
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        })
        val ctx = SSLContext.getInstance("TLS").apply { init(null, trustAll, null) }
        val http = java.net.URI("https://${origin.host}:$port${nodeNetworkPath(node)}").toURL().openConnection() as javax.net.ssl.HttpsURLConnection
        http.sslSocketFactory = ctx.socketFactory; http.setHostnameVerifier { _, _ -> true }
        http.setRequestProperty("Authorization", "PVEAPIToken=$raw")
        val code = http.responseCode
        println("LIVE-NET status=$code")
        val body = if (code == 200) http.inputStream.bufferedReader().readText() else ""
        if (code == 200) parseNodeNetwork(body).forEach {
            println("LIVE-NET ${it.type} active=${it.active} members=${it.members.size} cidr=${it.cidr != null} vlanAware=${it.vlanAware} bond=${it.bondMode}")
        }
    }

    /** Opt-in live terminal on a disposable test container: RINGMASTER_LIVE_PVE=1, RINGMASTER_LIVE_NODE, RINGMASTER_LIVE_CT=<vmid>, RINGMASTER_LIVE_CT_PASSWORD. */
    @Test fun containerTerminalLogsInAndRuns() {
        if (System.getenv("RINGMASTER_LIVE_PVE") != "1") return
        val ct = System.getenv("RINGMASTER_LIVE_CT")?.toIntOrNull() ?: return
        val password = System.getenv("RINGMASTER_LIVE_CT_PASSWORD") ?: return
        val host = System.getenv("PVE_HOST") ?: return
        val raw = System.getenv("PVE_TOKEN")?.removePrefix("PVEAPIToken=") ?: return
        val origin = serverOrigin(if (host.startsWith("https://")) host else "https://$host")
        val port = if (origin.port > 0) origin.port else 8006
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        })
        val ctx = SSLContext.getInstance("TLS").apply { init(null, trustAll, null) }
        val pin = (ctx.socketFactory.createSocket(origin.host, port) as SSLSocket).use { s ->
            s.startHandshake(); MessageDigest.getInstance("SHA-256").digest(s.session.peerCertificates[0].encoded).joinToString("") { "%02x".format(it) }
        }
        Connection("https://${origin.host}:$port", raw.substringBefore('='), raw.substringAfter('='), pin).use { c ->
            PveClient().terminalSession(c, TerminalTarget(System.getenv("RINGMASTER_LIVE_NODE") ?: return, "lxc", ct)).use { t ->
                fun screen() = t.model.buffer.getScreenLines()
                fun waitFor(text: String, ms: Long = 15_000): Boolean {
                    val end = System.currentTimeMillis() + ms
                    while (System.currentTimeMillis() < end) { if (screen().contains(text)) return true; Thread.sleep(150) }
                    return false
                }
                val readyBy = System.currentTimeMillis() + 15_000
                while (!t.ready && t.reason == null && System.currentTimeMillis() < readyBy) Thread.sleep(100)
                println("LIVE-TERM ready=${t.ready} reason=${t.reason}")
                val cr = "\r"
                t.input(cr)
                // The console keeps its session between connections: log in only when it shows a login prompt.
                val loginPrompt = waitFor("login:", 5_000)
                println("LIVE-TERM login prompt=$loginPrompt")
                if (loginPrompt) { t.input("root$cr"); println("LIVE-TERM password prompt=${waitFor("Password:")}"); t.input(password + cr) }
                println("LIVE-TERM shell=${waitFor("#")}")
                t.input("clear$cr"); Thread.sleep(400)
                t.input("echo RM-\$((6*7))$cr"); val ran = waitFor("RM-42"); println("LIVE-TERM command output=$ran")
                t.resize(100, 30); Thread.sleep(500)
                t.input("stty size$cr"); val resized = waitFor("30 100"); println("LIVE-TERM resize seen by guest=$resized")
                // A full-screen program: nano on the alternate screen, then Ctrl+X back to the shell.
                t.input("nano /tmp/ringmaster-probe$cr")
                val nanoUp = waitFor("^X Exit")
                println("LIVE-TERM nano drawn=$nanoUp alternate=${t.model.buffer.isUsingAlternateBuffer}")
                println("LIVE-TERM nano screen:\n" + screen().lines().filter { it.isNotBlank() }.let { it.take(2) + it.takeLast(2) }.joinToString("\n"))
                t.input("\u0018"); Thread.sleep(800)
                val clean = !screen().contains("6c")
                println("LIVE-TERM back to shell=${!t.model.buffer.isUsingAlternateBuffer} no-stray-text=$clean")
                t.input("exit$cr"); Thread.sleep(800)
                println("LIVE-TERM last lines:\n" + screen().lines().filter { it.isNotBlank() }.takeLast(6).joinToString("\n"))
                assertTrue(ran && resized && nanoUp && clean)
            }
        }
    }
}
