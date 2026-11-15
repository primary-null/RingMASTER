package local.ringleader.desktop

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.Netty
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Browser mode with a TLS keystore runs on the Netty engine, built the way [local.ringleader.desktop.web.WebServer] builds it.
 * The packages leave out Netty's HTTP/3 native libraries (one per operating system, none of them used), so this proves the
 * engine still starts and answers over HTTPS without them.
 */
class WebTlsEngineTest {
    @Test fun nettyAnswersOverHttpsWithoutTheHttp3NativeLibraries() {
        val dir = Files.createTempDirectory("web-tls-engine")
        val key = dir.resolve("fixture.p12")
        val password = "fixture-only".toCharArray()
        val keytool = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool")
        val process = ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
            "-dname", "CN=fixture.invalid", "-ext", "SAN=ip:127.0.0.1", "-storetype", "PKCS12", "-keystore", key.toString(),
            "-storepass", String(password), "-keypass", String(password), "-noprompt").redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        check(process.waitFor() == 0)
        val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(key).use { load(it, password) } }
        val server = embeddedServer(Netty, applicationEnvironment {}, {
            sslConnector(store, "fixture", { password }, { password }) { host = "127.0.0.1"; port = 0 }
        }) { routing { get("/") { call.respondText("ok") } } }
        try {
            server.start(false)
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            // The fixture certificate is self-signed and lives for this test only; what is checked is the engine, not trust.
            val anyCertificate = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, type: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, type: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(anyCertificate), null) }
            val response = HttpClient.newBuilder().sslContext(context).build()
                .send(HttpRequest.newBuilder(URI("https://127.0.0.1:$port/")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode()); assertEquals("ok", response.body())
            val classPath = System.getProperty("java.class.path")
            assertFalse("netty-codec-native-quic" in classPath, "the HTTP/3 native libraries are not shipped")
        } finally { server.stop(0, 0); Files.deleteIfExists(key); Files.deleteIfExists(dir) }
    }
}
