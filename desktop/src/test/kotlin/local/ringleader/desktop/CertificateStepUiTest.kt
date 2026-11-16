@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.delay
import local.ringleader.core.*
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

/**
 * Network audit W3, W5 and F7 with owner decision B: a saved profile with its own pin gets a certificate step after a
 * certificate refusal (known and observed fingerprints, subject, issuer, validity, same key yes/no), a renewal with the same
 * key is accepted in one explicit click, a changed key needs the replacement checkbox, an out-of-date certificate is never
 * offered, and the editor's check fills the draft pin with Save as the approval. Enter never approves.
 */
class CertificateStepUiTest {
    private val origin = "https://pve.example.test:8006"
    private val oldPin = "ab".repeat(32)
    private val newPin = "cd".repeat(32)
    private val key = "11".repeat(32)
    private val otherKey = "22".repeat(32)

    private fun certificate(pin: String, spki: String, from: String = "2026-11-01T00:00:00Z", to: String = "2028-11-01T00:00:00Z") =
        ProbedCertificate(pin, spki, "CN=pve.example.test, OU=PVE Cluster Node, O=Proxmox Virtual Environment",
            "CN=Proxmox Virtual Environment, OU=PVE Cluster Manager CA, O=PVE Cluster Manager CA", Instant.parse(from), Instant.parse(to),
            listOf("pve.example.test", "192.0.2.10"))

    private fun refused(failure: NetworkFailure) = WorkspaceState(connected = true, address = origin, account = "root@pam",
        error = failure.sentence, failure = failure, certificateHold = true)

    private class Rig(val desk: ProfileDesk, val workspace: Workspace, val root: java.nio.file.Path, val scene: ImageComposeScene, val ui: CreateScene, val routed: MutableList<Connection>) {
        fun close() { scene.close(); desk.close(); workspace.close(); root.toFile().deleteRecursively() }
    }

    private fun kotlinx.coroutines.CoroutineScope.rig(read: ProbedCertificate?, state: WorkspaceState, spki: String? = key, pin: String = oldPin, scroll: Int = 420): Rig {
        val root = Files.createTempDirectory("certificate-step")
        val workspace = Workspace(this)
        val desk = ProfileDesk(workspace, ProfileStore(root.resolve("profiles.json")))
        desk.beginCreate(); desk.updateDraft("Lab node", origin, "root@pam", pin); assertTrue(desk.saveDraft(passwordLogin = true))
        if (spki != null) {
            // The key as an earlier approval recorded it.
            val profile = desk.state.value.profiles.single()
            ProfileStore(root.resolve("profiles.json")).apply { load(); save(listOf(profile.copy(sha256Spki = spki))) }
            desk.reload()
        }
        desk.selectProfile(desk.state.value.profiles.single().id)
        val routed = mutableListOf<Connection>()
        desk.router = { c, _ -> routed += c!!; error("Offline route reached") }
        certificateProbe = { read }
        val scene = ImageComposeScene(1280, 720, coroutineContext = coroutineContext) {
            val desks by desk.state.collectAsState()
            MaterialTheme(colorScheme = ringColors) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(Tech.Canvas).padding(16.dp)) { DeskScreen(state, desks, desk, scanned = null, initialScroll = scroll) {} }
            }
        }
        return Rig(desk, workspace, root, scene, CreateScene(scene), routed)
    }

    private suspend fun CreateScene.await(what: String): SemanticsNode {
        repeat(100) { frames(); runCatching { text(what) }.onSuccess { return it }; delay(20) }
        return text(what)
    }
    private fun CreateScene.actionable(label: String) = nodes()
        .filter { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.equals(label, ignoreCase = true) } == true }
        .firstNotNullOf { n -> generateSequence(n) { it.parent }.firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null } }
    /** Activates a button or checkbox through its semantics, wherever the Desk's scroll has put it. */
    private suspend fun CreateScene.press(label: String) { actionable(label).config[SemanticsActions.OnClick].action!!(); frames() }
    /** Scrolls the Desk so the node with [label] is in the 720-unit picture. */
    private suspend fun CreateScene.reveal(label: String) {
        val node = text(label)
        // Unclipped: a node below the fold has empty clipped bounds.
        val bottom = node.positionInRoot.y + node.size.height
        // The Desk's own scroll is the tallest scrollable on screen (the manual form has none of its own).
        val scroller = nodes().filter { it.config.getOrNull(SemanticsActions.ScrollBy) != null }.maxByOrNull { it.boundsInWindow.height }
        if (bottom > 680f && scroller != null) scroller.config[SemanticsActions.ScrollBy].action!!(0f, bottom - 600f)
        repeat(4) { frames() }
    }
    private fun CreateScene.secret() = nodes().first { it.config.getOrNull(SemanticsActions.SetText) != null }
    private suspend fun CreateScene.type(text: String) {
        secret().config[SemanticsActions.SetText].action!!(androidx.compose.ui.text.AnnotatedString(text)); frames()
        secret().config[SemanticsActions.RequestFocus].action!!(); frames()
    }

    @AfterTest fun reset() { certificateProbe = { address -> probeTlsCertificate(address) }; Appearance.preview(false) }

    @Test fun aPinnedProfileRenewedWithTheSameKeyIsAcceptedInOneClickAndNeverByEnter() = runSceneTest {
        val r = rig(certificate(newPin, key), refused(NetworkFailure.RenewedSameKey(newPin)))
        try {
            // The panel read the certificate by itself after the refusal.
            r.ui.await("Accept renewed certificate and connect")
            r.ui.text(NetworkFailure.RenewedSameKey().sentence)
            r.ui.text("Known SHA-256: $oldPin"); r.ui.text("Observed SHA-256: $newPin")
            r.ui.text("Same key as before: yes")
            r.ui.text("Subject: CN=pve.example.test, OU=PVE Cluster Node, O=Proxmox Virtual Environment")
            r.ui.text("Valid ${certificateDay(Instant.parse("2026-11-01T00:00:00Z"))} to ${certificateDay(Instant.parse("2028-11-01T00:00:00Z"))}")
            assertFails { r.ui.text("Replace the known certificate with this one") }
            r.ui.type("typed-password")
            r.ui.reveal("Accept renewed certificate and connect")
            saveTrustShot(r.scene, "pinned-renewed-same-key")
            for ((theme, apply) in trustShotThemes) { apply(); repeat(6) { r.ui.frames() }; r.ui.reveal("Clear secret"); saveTrustShot(r.scene, "pinned-renewed-same-key-$theme") }
            Appearance.preview(false); repeat(4) { r.ui.frames() }
            // Enter in the password field approves nothing and connects nothing.
            r.ui.key(Key.Enter)
            assertTrue(r.routed.isEmpty()); assertEquals(oldPin, ProfileStore(r.root.resolve("profiles.json")).load().single().sha256Pin)
            r.ui.press("Accept renewed certificate and connect")
            assertEquals(newPin, r.routed.single().fingerprint, "the connection uses the accepted certificate")
            val saved = ProfileStore(r.root.resolve("profiles.json")).load().single()
            assertEquals(newPin, saved.sha256Pin); assertEquals(key, saved.sha256Spki)
        } finally { r.close() }
    }

    @Test fun aPinnedProfileWithANewKeyNeedsTheReplacementCheckbox() = runSceneTest {
        val r = rig(certificate(newPin, otherKey), refused(NetworkFailure.Changed(newPin, otherKey)))
        try {
            r.ui.await("Replace trust and connect")
            r.ui.text("Same key as before: no")
            r.ui.text(NetworkFailure.Changed().sentence)
            r.ui.type("typed-password")
            assertNotNull(r.ui.actionable("Replace trust and connect").config.getOrNull(SemanticsProperties.Disabled), "not before the checkbox")
            r.ui.reveal("Replace trust and connect")
            saveTrustShot(r.scene, "pinned-changed-key")
            for ((theme, apply) in trustShotThemes) { apply(); repeat(6) { r.ui.frames() }; r.ui.reveal("Clear secret"); saveTrustShot(r.scene, "pinned-changed-key-$theme") }
            Appearance.preview(false); repeat(4) { r.ui.frames() }
            r.ui.press("Replace the known certificate with this one")
            assertNull(r.ui.actionable("Replace trust and connect").config.getOrNull(SemanticsProperties.Disabled))
            r.ui.press("Replace trust and connect")
            assertEquals(newPin, ProfileStore(r.root.resolve("profiles.json")).load().single().sha256Pin)
            assertEquals(otherKey, ProfileStore(r.root.resolve("profiles.json")).load().single().sha256Spki)
        } finally { r.close() }
    }

    @Test fun aKeyThatWasNeverRecordedIsSaidSoAndNeedsTheCheckbox() = runSceneTest {
        val r = rig(certificate(newPin, key), refused(NetworkFailure.Changed(newPin, key)), spki = null)
        try {
            r.ui.await("Replace trust and connect")
            r.ui.text("Same key as before: not known (no key was recorded with the approved certificate)")
        } finally { r.close() }
    }

    @Test fun anExpiredCertificateShowsItsDatesAndIsNeverOffered() = runSceneTest {
        val now = Instant.now()
        val expired = certificate(oldPin, key, "2024-10-01T00:00:00Z", "2026-10-01T00:00:00Z")
        val failure = NetworkFailure.CertificateDates(true, now, expired.notBefore, expired.notAfter)
        val r = rig(expired, refused(failure))
        try {
            r.ui.await("Observed SHA-256: $oldPin")
            r.ui.text(failure.sentence); r.ui.text(failure.nextStep)
            r.ui.await("It cannot be accepted", partial = true)
            assertFails { r.ui.text("Accept renewed certificate and connect") }; assertFails { r.ui.text("Replace trust and connect") }; assertFails { r.ui.text("Trust and connect") }
            r.ui.reveal("Clear secret")
            saveTrustShot(r.scene, "pinned-expired-dates")
            for ((theme, apply) in trustShotThemes) { apply(); repeat(6) { r.ui.frames() }; saveTrustShot(r.scene, "pinned-expired-dates-$theme") }
        } finally { r.close() }
        // A changed AND expired certificate is not offered either.
        val both = rig(certificate(newPin, key, "2024-10-01T00:00:00Z", "2026-10-01T00:00:00Z"), refused(NetworkFailure.Changed(newPin, key)))
        try {
            both.ui.await("Accept renewed certificate and connect")
            both.ui.type("typed-password")
            assertNotNull(both.ui.actionable("Accept renewed certificate and connect").config.getOrNull(SemanticsProperties.Disabled))
        } finally { both.close() }
    }

    @Test fun showcaseHidesFingerprintsSubjectsAndTheServer() = runSceneTest {
        Appearance.preview(ThemeMode.Dark, showcase = true)
        val r = rig(certificate(newPin, key), refused(NetworkFailure.RenewedSameKey(newPin)))
        try {
            r.ui.await("Accept renewed certificate and connect")
            // Masked values carry no text at all in Showcase (Modifier.identity); the real ones are nowhere.
            assertTrue(r.ui.nodes().none { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(newPin) || it.text.contains(oldPin) } == true }, "no fingerprint")
            r.ui.reveal("Accept renewed certificate and connect")
            assertTrue(r.ui.nodes().none { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains("OU=PVE Cluster") } == true }, "no subject or issuer text")
            saveTrustShot(r.scene, "pinned-renewed-showcase")
        } finally { r.close() }
    }

    @Test fun theEditorChecksTheCertificateAndUseThisCertificateFillsTheDraftUntilSave() = runSceneTest {
        val r = rig(certificate(newPin, otherKey), WorkspaceState())
        try {
            r.desk.beginEdit(); r.ui.frames()
            r.ui.press("Check server certificate")
            r.ui.await("Use this certificate")
            r.ui.reveal("Save profile")
            r.ui.text("Known SHA-256: $oldPin"); r.ui.text("Observed SHA-256: $newPin"); r.ui.text("Same key as before: no")
            saveTrustShot(r.scene, "editor-certificate-check")
            for ((theme, apply) in trustShotThemes) { apply(); repeat(6) { r.ui.frames() }; saveTrustShot(r.scene, "editor-certificate-check-$theme") }
            r.ui.press("Use this certificate")
            assertEquals(newPin, r.desk.state.value.draft.sha256Pin)
            assertEquals(oldPin, ProfileStore(r.root.resolve("profiles.json")).load().single().sha256Pin, "Save is the approval")
            r.ui.await("The pin above is this server's certificate.")
            r.ui.press("Save profile")
            val saved = ProfileStore(r.root.resolve("profiles.json")).load().single()
            assertEquals(newPin, saved.sha256Pin); assertEquals(otherKey, saved.sha256Spki)
        } finally { r.close() }
    }

    @Test fun theEditorNeverOffersAnOutOfDateCertificate() = runSceneTest {
        val r = rig(certificate(newPin, key, "2027-01-01T00:00:00Z", "2029-01-01T00:00:00Z"), WorkspaceState())
        try {
            r.desk.beginEdit(); r.ui.frames()
            r.ui.press("Check server certificate")
            r.ui.await("It cannot be accepted", partial = true)
            assertFails { r.ui.text("Use this certificate") }
        } finally { r.close() }
    }

    private suspend fun CreateScene.await(what: String, partial: Boolean): SemanticsNode {
        if (!partial) return await(what)
        repeat(100) {
            frames()
            nodes().firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(what) } == true }?.let { return it }
            delay(20)
        }
        error("Missing text containing '$what'")
    }
}
