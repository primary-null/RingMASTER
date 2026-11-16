@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import local.ringleader.core.*
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

/** Network audit F4 (Forget asks once and says what happened) and M3 (same certificate as another approved address). */
class ForgetAndSameCertificateUiTest {
    private val origin = "https://pve.example.test:8006"
    private val pin = "ab".repeat(32)

    @AfterTest fun reset() { certificateProbe = { address -> probeTlsCertificate(address) }; Appearance.preview(false) }

    private fun kotlinx.coroutines.CoroutineScope.scene(desk: ProfileDesk, workspace: Workspace) = ImageComposeScene(1280, 720, coroutineContext = coroutineContext) {
        val state by desk.state.collectAsState()
        MaterialTheme(colorScheme = ringColors) {
            Box(Modifier.fillMaxSize().background(Tech.Canvas).padding(16.dp)) { DeskScreen(workspace.state.value, state, desk, scanned = null) {} }
        }
    }
    private fun CreateScene.fields() = nodes().filter { it.config.getOrNull(SemanticsActions.SetText) != null }
    private fun CreateScene.press(label: String) {
        nodes().filter { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.equals(label, ignoreCase = true) } == true }
            .firstNotNullOf { n -> generateSequence(n) { it.parent }.firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null } }
            .config[SemanticsActions.OnClick].action!!()
    }
    /** Scrolls the Desk so the node with [label] is in the 720-unit picture (unclipped position; see CertificateStepUiTest). */
    private suspend fun CreateScene.reveal(label: String) {
        val node = nodes().first { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.equals(label, true) } == true }
        val bottom = node.positionInRoot.y + node.size.height
        val scroller = nodes().filter { it.config.getOrNull(SemanticsActions.ScrollBy) != null }.maxByOrNull { it.boundsInWindow.height }
        if (bottom > 680f && scroller != null) scroller.config[SemanticsActions.ScrollBy].action!!(0f, bottom - 600f)
        repeat(4) { frames() }
    }
    private suspend fun CreateScene.await(what: String, partial: Boolean = false) {
        repeat(100) {
            frames()
            if (nodes().any { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { if (partial) it.text.contains(what) else it.text.equals(what, true) } == true }) return
            delay(20)
        }
        error("Missing '$what'")
    }

    @Test fun forgetAsksOnceKeepChangesNothingAndForgetSaysWhatHappened() = runSceneTest {
        val root = Files.createTempDirectory("forget-ui")
        CertificateTrustStore(root.resolve("certificates.json")).save(listOf(ApprovedCertificate(origin, pin)))
        val workspace = Workspace(this)
        val desk = ProfileDesk(workspace, ProfileStore(root.resolve("profiles.json")))
        val scene = scene(desk, workspace)
        val ui = CreateScene(scene)
        try {
            ui.frames()
            ui.fields()[0].config[SemanticsActions.SetText].action!!(AnnotatedString(origin)); ui.frames()
            ui.await("Forget remembered certificate")
            ui.press("Forget remembered certificate"); ui.frames()
            ui.await(forgetCertificateQuestion(origin))
            assertEquals(1, CertificateTrustStore(root.resolve("certificates.json")).load().size, "the first click forgets nothing")
            ui.reveal("Keep")
            saveTrustShot(scene, "forget-confirmation")
            for ((theme, apply) in trustShotThemes) { apply(); repeat(6) { ui.frames() }; saveTrustShot(scene, "forget-confirmation-$theme") }
            Appearance.preview(false)
            ui.press("Keep"); ui.frames()
            ui.await("Forget remembered certificate")
            assertEquals(1, CertificateTrustStore(root.resolve("certificates.json")).load().size)
            ui.press("Forget remembered certificate"); ui.frames()
            ui.press("Forget certificate"); ui.frames()
            ui.await(FORGOT_CERTIFICATE_NOTICE)
            assertTrue(CertificateTrustStore(root.resolve("certificates.json")).load().isEmpty())
            saveTrustShot(scene, "forget-done")
            // Showcase: the question names no server.
            CertificateTrustStore(root.resolve("certificates.json")).let { it.load(); it.save(listOf(ApprovedCertificate(origin, pin))) }
            ui.fields()[0].config[SemanticsActions.SetText].action!!(AnnotatedString(origin + " ")); ui.frames()
            ui.fields()[0].config[SemanticsActions.SetText].action!!(AnnotatedString(origin)); ui.frames()
            Appearance.preview(ThemeMode.Dark, showcase = true); ui.frames()
            ui.await("Forget remembered certificate"); ui.press("Forget remembered certificate"); ui.frames()
            assertTrue(ui.nodes().none { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("Forget the approved certificate for https") } == true })
        } finally { scene.close(); desk.close(); workspace.close(); root.toFile().deleteRecursively() }
    }

    @Test fun theSameCertificateAsAnotherApprovedAddressIsSaidAndStillNeedsTrustAndConnect() = runSceneTest {
        val root = Files.createTempDirectory("same-ui")
        CertificateTrustStore(root.resolve("certificates.json")).save(listOf(ApprovedCertificate(origin, pin)))
        val workspace = Workspace(this)
        val desk = ProfileDesk(workspace, ProfileStore(root.resolve("profiles.json")))
        var routed = 0
        desk.router = { _, _ -> routed++; error("offline") }
        certificateProbe = { ProbedCertificate(pin, "11".repeat(32), "CN=pve.example.test", "CN=PVE Cluster Manager CA", Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2028-11-01T00:00:00Z")) }
        val scene = scene(desk, workspace)
        val ui = CreateScene(scene)
        try {
            ui.frames()
            ui.fields()[0].config[SemanticsActions.SetText].action!!(AnnotatedString("https://192.0.2.10:8006")); ui.frames()
            ui.press("Check server certificate")
            ui.await("Same certificate as $origin", partial = true)
            ui.await("Trust and connect")
            assertEquals(0, routed)
            ui.reveal("Trust and connect")
            saveTrustShot(scene, "same-certificate-as")
            for ((theme, apply) in trustShotThemes) { apply(); repeat(6) { ui.frames() }; saveTrustShot(scene, "same-certificate-as-$theme") }
            Appearance.preview(ThemeMode.Dark, showcase = true); ui.frames()
            ui.await("Same certificate as another server you approved", partial = true)
        } finally { scene.close(); desk.close(); workspace.close(); root.toFile().deleteRecursively() }
    }
}
