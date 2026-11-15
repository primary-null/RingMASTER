@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.delay
import local.ringleader.core.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.test.*

/** The certificate check for a typed address (a server the scan cannot see): it reads, it never trusts, and it never connects. */
class ManualCertificateCheckTest {
    /** A self-signed HTTPS server on loopback, as a Proxmox node presents itself. Gives its port and SHA-256 pin. */
    private suspend fun tlsServer(block: suspend (Int, String) -> Unit) {
        val dir = Files.createTempDirectory("manual-check-tls")
        val key = dir.resolve("fixture.p12")
        val keytool = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool")
        val process = ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
            "-dname", "CN=fixture.invalid", "-storetype", "PKCS12", "-keystore", key.toString(),
            "-storepass", "fixture-only", "-keypass", "fixture-only", "-noprompt").redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        check(process.waitFor() == 0)
        val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(key).use { load(it, "fixture-only".toCharArray()) } }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "fixture-only".toCharArray()) }
        val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        val pin = MessageDigest.getInstance("SHA-256").digest(store.getCertificate("fixture").encoded).joinToString("") { "%02x".format(it) }
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(context)
        server.createContext("/") { e -> e.sendResponseHeaders(401, -1); e.close() }
        server.start()
        try { block(server.address.port, pin) } finally { server.stop(0); Files.deleteIfExists(key); Files.deleteIfExists(dir) }
    }
    private suspend fun CreateScene.await(what: String): SemanticsNode {
        repeat(200) { frames(); runCatching { text(what) }.onSuccess { return it }; delay(50) }
        return text(what)
    }
    private fun CreateScene.fields() = nodes().filter { it.config.getOrNull(SemanticsActions.SetText) != null }
    /** The button with this label; a heading with the same words (the form's own "Connect" label) is not one. */
    private fun CreateScene.actionable(label: String) = nodes()
        .filter { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.equals(label, ignoreCase = true) } == true }
        .firstNotNullOf { n -> generateSequence(n) { it.parent }.firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null } }

    @Test fun typedBareAddressIsCheckedOnItsOwnPortAndStillNeedsExplicitTrust() = runSceneTest {
        tlsServer { port, pin ->
            val root = Files.createTempDirectory("manual-check")
            val workspace = Workspace(this)
            val desk = ProfileDesk(workspace, ProfileStore(root.resolve("profiles.json")))
            var calls = 0
            desk.router = { _, _ -> calls++; error("Offline route reached") }
            val motion = MotionPreferences(root.resolve("motion")); motion.put(DESK_PASSWORD_MODE, true)
            val scene = ImageComposeScene(1600, 900, coroutineContext = coroutineContext) {
                val state by desk.state.collectAsState()
                MaterialTheme(colorScheme = ringColors) { DeskScreen(workspace.state.value, state, desk, scanned = null, motion = motion) {} }
            }
            val ui = CreateScene(scene)
            val origin = "https://127.0.0.1:$port"
            try {
                ui.frames()
                // address, user, password, OTP, optional pasted fingerprint
                assertEquals(5, ui.fields().size)
                ui.fields()[0].config[SemanticsActions.SetText].action!!(AnnotatedString("127.0.0.1:$port")); ui.frames()
                // A bare host is not an error to correct: it says what will be checked.
                ui.text("Will be checked as $origin")
                assertFails { ui.text("Use an HTTPS server address, including its port.") }
                ui.fields()[2].config[SemanticsActions.SetText].action!!(AnnotatedString("test-password")); ui.frames()
                ui.fields()[2].config[SemanticsActions.RequestFocus].action!!(); ui.frames()
                // Enter reads the certificate. It does not connect and it does not trust.
                ui.key(Key.Enter)
                ui.await("Trust and connect")
                assertEquals(0, calls); assertFalse(Files.exists(root.resolve("certificates.json")))
                assertEquals(origin, ui.fields()[0].config[SemanticsProperties.EditableText].text)
                ui.text("Observed SHA-256: " + pin.uppercase().chunked(2).joinToString(":"))
                // Enter again still approves nothing.
                ui.fields()[2].config[SemanticsActions.RequestFocus].action!!(); ui.frames(); ui.key(Key.Enter)
                assertEquals(0, calls); assertFalse(Files.exists(root.resolve("certificates.json")))
                val trust = ui.actionable("Trust and connect")
                assertNull(trust.config.getOrNull(SemanticsProperties.Disabled))
                trust.config[SemanticsActions.RequestFocus].action!!(); ui.frames(); ui.key(Key.Enter)
                assertEquals(1, calls)
                assertEquals(pin, desk.manualTrust(origin).known.single())
            } finally { scene.close(); desk.close(); workspace.close() }
        }
    }

    @Test fun anUnreachableAddressSaysSoAndTheNextConnectGoesAhead() = runSceneTest {
        val closed = java.net.ServerSocket(0).use { it.localPort }
        val root = Files.createTempDirectory("manual-check-closed")
        val workspace = Workspace(this)
        val desk = ProfileDesk(workspace, ProfileStore(root.resolve("profiles.json")))
        var calls = 0
        desk.router = { _, _ -> calls++; error("Offline route reached") }
        val motion = MotionPreferences(root.resolve("motion")); motion.put(DESK_PASSWORD_MODE, true)
        val scene = ImageComposeScene(1600, 900, coroutineContext = coroutineContext) {
            val state by desk.state.collectAsState()
            MaterialTheme(colorScheme = ringColors) { DeskScreen(workspace.state.value, state, desk, scanned = null, motion = motion) {} }
        }
        val ui = CreateScene(scene)
        val origin = "https://127.0.0.1:$closed"
        try {
            ui.frames()
            ui.fields()[0].config[SemanticsActions.SetText].action!!(AnnotatedString(origin)); ui.frames()
            ui.fields()[2].config[SemanticsActions.SetText].action!!(AnnotatedString("test-password")); ui.frames()
            ui.click("Check server certificate")
            ui.await("No certificate could be read from $origin. Check the address and port (Proxmox VE uses 8006), and that this computer can reach it.")
            assertEquals(0, calls)
            assertFails { ui.text("Trust and connect") }
            // The check is advice, not a gate: Connect now goes ahead and the connection reports its own error.
            ui.actionable("Connect").config[SemanticsActions.RequestFocus].action!!(); ui.frames(); ui.key(Key.Enter)
            assertEquals(1, calls)
            assertFalse(Files.exists(root.resolve("certificates.json")))
        } finally { scene.close(); desk.close(); workspace.close() }
    }
}
