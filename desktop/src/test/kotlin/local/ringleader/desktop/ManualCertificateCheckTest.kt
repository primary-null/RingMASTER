@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import local.ringleader.core.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
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

    // --- "Connect with normal TLS" is offered only where the platform's own TLS could accept the server ---

    private val refusedNote = "This server has a private certificate, so normal TLS cannot verify it. Compare the fingerprint with the server's own, then use Trust and connect."

    /** The desk as the app shows it: the saved connections beside the Connect form. */
    private fun CoroutineScope.deskScene(workspace: Workspace, desk: ProfileDesk, motion: MotionPreferences, scanned: ScanHit? = null) =
        ImageComposeScene(1600, 900, coroutineContext = coroutineContext) {
            val state by desk.state.collectAsState()
            MaterialTheme(colorScheme = ringColors) { DeskScreen(workspace.state.value, state, desk, scanned = scanned, motion = motion) {} }
        }

    private fun SemanticsNode.withText(text: String) = config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true

    // Unclipped, unlike boundsInWindow: the page scrolls, so a button can sit below the window.
    private val SemanticsNode.pageTop get() = positionInRoot.y
    private val SemanticsNode.pageBottom get() = positionInRoot.y + size.height

    /** The style the node's text was laid out with. */
    private fun SemanticsNode.drawnStyle(): TextStyle {
        val layouts = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
        return layouts.first().layoutInput.style
    }

    @Test fun aPrivateCertificateTakesNormalTlsAwayAndSaysWhy() = runSceneTest {
        tlsServer { port, _ ->
            val root = Files.createTempDirectory("manual-check-private")
            val workspace = Workspace(this)
            val desk = ProfileDesk(workspace, ProfileStore(root.resolve("profiles.json")))
            var calls = 0
            desk.router = { _, _ -> calls++; error("Offline route reached") }
            val motion = MotionPreferences(root.resolve("motion")); motion.put(DESK_PASSWORD_MODE, true)
            val scene = deskScene(workspace, desk, motion)
            val ui = CreateScene(scene)
            try {
                ui.frames()
                ui.fields()[0].config[SemanticsActions.SetText].action!!(AnnotatedString("https://127.0.0.1:$port")); ui.frames()
                ui.click("Check server certificate")
                ui.await("Trust and connect")
                // The platform's own TLS does not know this self-signed certificate: normal TLS cannot work, and the form says so.
                ui.until("the note about a private certificate") { ui.has(refusedNote) }
                assertFalse(ui.has("Connect with normal TLS"))
                assertTrue(ui.has("Trust and connect"))
                // One muted 12 sp line, between the fingerprint and the buttons.
                val note = ui.nodes().first { it.withText(refusedNote) }
                assertEquals(12.sp, note.drawnStyle().fontSize)
                assertEquals(Tech.Muted, note.drawnStyle().color)
                val observed = ui.nodes().first { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("Observed SHA-256: ") } == true }
                assertTrue(observed.pageBottom <= note.pageTop, "the note follows the fingerprint")
                assertTrue(note.pageBottom <= ui.actionable("Trust and connect").pageTop, "the note sits above the buttons")
                // Nothing was trusted or connected by looking.
                assertEquals(0, calls); assertFalse(Files.exists(root.resolve("certificates.json")))
            } finally { scene.close(); desk.close(); workspace.close() }
        }
    }

    @Test fun aSavedProfileWithoutAPinGetsTheSameAnswer() = runSceneTest {
        tlsServer { port, _ ->
            val root = Files.createTempDirectory("manual-check-profile")
            val workspace = Workspace(this)
            val id = UUID.randomUUID().toString()
            val store = ProfileStore(root.resolve("profiles.json"))
            store.save(listOf(ConnectionProfile(id, "Lab server", DIRECT_PVE_KIND, "https://127.0.0.1:$port", "root@pam", null)))
            val desk = ProfileDesk(workspace, store)
            desk.selectProfile(id)
            var calls = 0
            desk.router = { _, _ -> calls++; error("Offline route reached") }
            val motion = MotionPreferences(root.resolve("motion"))
            val scene = deskScene(workspace, desk, motion)
            val ui = CreateScene(scene)
            try {
                ui.frames()
                ui.click("Check server certificate")
                ui.await("Trust and connect")
                ui.until("the note about a private certificate") { ui.has(refusedNote) }
                assertFalse(ui.has("Connect with normal TLS"))
                assertTrue(ui.has("Trust and connect"))
                val note = ui.nodes().first { it.withText(refusedNote) }
                assertEquals(12.sp, note.drawnStyle().fontSize)
                assertEquals(Tech.Muted, note.drawnStyle().color)
                assertTrue(note.pageBottom <= ui.actionable("Trust and connect").pageTop, "the note sits above the buttons")
                assertEquals(0, calls); assertFalse(Files.exists(root.resolve("certificates.json")))
            } finally { scene.close(); desk.close(); workspace.close() }
        }
    }

    @Test fun anAddressThePlatformCannotJudgeKeepsNormalTls() = runSceneTest {
        // Nothing listens there: the probe ends as Unknown, which is what the form has always assumed.
        val closed = java.net.ServerSocket(0).use { it.localPort }
        val hit = ScanHit("127.0.0.1", closed, true, ScanKind.PVE, fingerprint = "ab".repeat(32))
        val root = Files.createTempDirectory("manual-check-unknown")
        val workspace = Workspace(this)
        val desk = ProfileDesk(workspace, ProfileStore(root.resolve("profiles.json")))
        val motion = MotionPreferences(root.resolve("motion")); motion.put(DESK_PASSWORD_MODE, true)
        val answers = CopyOnWriteArrayList<NormalTls>()
        val asked = CopyOnWriteArrayList<String>()
        val previous = normalTlsProbe
        normalTlsProbe = { address -> asked += address; probeNormalTls(address).also { answers += it } }
        val scene = deskScene(workspace, desk, motion, hit)
        val ui = CreateScene(scene)
        try {
            ui.frames()
            // The scan offer is there from the first frame; the choice is too.
            assertTrue(ui.has("Trust and connect")); assertTrue(ui.has("Connect with normal TLS"))
            ui.until("the platform's answer") { answers.isNotEmpty() }
            ui.frames()
            assertEquals(listOf(hit.origin), asked.toList())
            assertEquals(listOf(NormalTls.Unknown), answers.toList())
            assertTrue(ui.has("Connect with normal TLS"), "an answer of Unknown changes nothing")
            assertFalse(ui.has(refusedNote))
        } finally { normalTlsProbe = previous; scene.close(); desk.close(); workspace.close() }
    }

    @Test fun nothingWaitsForTheProbeAndAnAcceptedAnswerChangesNothing() = runSceneTest {
        val closed = java.net.ServerSocket(0).use { it.localPort }
        val hit = ScanHit("127.0.0.1", closed, true, ScanKind.PVE, fingerprint = "ab".repeat(32))
        val root = Files.createTempDirectory("manual-check-accepted")
        val workspace = Workspace(this)
        val desk = ProfileDesk(workspace, ProfileStore(root.resolve("profiles.json")))
        val motion = MotionPreferences(root.resolve("motion")); motion.put(DESK_PASSWORD_MODE, true)
        val gate = CompletableDeferred<NormalTls>()
        val asked = CopyOnWriteArrayList<String>()
        val answered = CopyOnWriteArrayList<NormalTls>()
        val previous = normalTlsProbe
        normalTlsProbe = { address -> asked += address; gate.await().also { answered += it } }
        val scene = deskScene(workspace, desk, motion, hit)
        val ui = CreateScene(scene)
        try {
            ui.frames()
            ui.until("the probe is asked") { asked.isNotEmpty() }
            // Asked, not answered: the buttons are today's, and no note.
            assertTrue(ui.has("Trust and connect")); assertTrue(ui.has("Connect with normal TLS"))
            assertFalse(ui.has(refusedNote))
            gate.complete(NormalTls.Accepted)
            ui.until("the answer") { answered.isNotEmpty() }
            ui.frames()
            assertTrue(ui.has("Trust and connect")); assertTrue(ui.has("Connect with normal TLS"))
            assertFalse(ui.has(refusedNote))
            assertEquals(listOf(hit.origin), asked.toList())
        } finally { normalTlsProbe = previous; scene.close(); desk.close(); workspace.close() }
    }

    @Test fun aLateAnswerForAnEarlierAddressIsNeverShown() = runSceneTest {
        tlsServer { port, _ ->
            // The scan offers a certificate for an address nobody listens on; the probe for it is held back.
            val closed = java.net.ServerSocket(0).use { it.localPort }
            val hit = ScanHit("127.0.0.1", closed, true, ScanKind.PVE, fingerprint = "ab".repeat(32))
            val root = Files.createTempDirectory("manual-check-late")
            val workspace = Workspace(this)
            val desk = ProfileDesk(workspace, ProfileStore(root.resolve("profiles.json")))
            val motion = MotionPreferences(root.resolve("motion")); motion.put(DESK_PASSWORD_MODE, true)
            val first = CompletableDeferred<NormalTls>()
            val firstEnded = CompletableDeferred<Unit>()
            val asked = CopyOnWriteArrayList<String>()
            val previous = normalTlsProbe
            // The first question is answered late, and by a probe that does not stop when asked to: the worst case.
            normalTlsProbe = { address ->
                asked += address
                if (asked.size == 1) {
                    try { withContext(NonCancellable) { first.await() } } finally { firstEnded.complete(Unit) }
                } else NormalTls.Unknown
            }
            val scene = deskScene(workspace, desk, motion, hit)
            val ui = CreateScene(scene)
            try {
                ui.frames()
                ui.until("the first question") { asked.size == 1 }
                // The address is edited, then another server's certificate is read: a different address and certificate.
                ui.fields()[0].config[SemanticsActions.SetText].action!!(AnnotatedString("https://127.0.0.1:$port")); ui.frames()
                assertFalse(ui.has("Trust and connect"), "an edit takes the scan offer away")
                ui.click("Check server certificate")
                ui.await("Trust and connect")
                ui.until("the second question") { asked.size == 2 }
                assertEquals(listOf(hit.origin, "https://127.0.0.1:$port"), asked.toList())
                // Now the first answer comes in. It is for an address and certificate that are no longer on the screen.
                first.complete(NormalTls.Refused)
                ui.until("the late answer") { firstEnded.isCompleted }
                ui.frames()
                assertFalse(ui.has(refusedNote), "a refusal for the earlier address is not applied to this one")
                assertTrue(ui.has("Connect with normal TLS"))
            } finally { normalTlsProbe = previous; scene.close(); desk.close(); workspace.close() }
        }
    }
}
