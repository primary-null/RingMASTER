@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import local.ringleader.core.*
import java.nio.file.Files
import kotlin.test.*

class LoginTrustInteractionTest {
    @Test fun enlargedRepeatLoginActionScrollsIntoViewAndConnectsWithoutApproval() = runSceneTest {
        val root = Files.createTempDirectory("login-repeat-enlarged")
        val hit = ScanHit("pve.example.test",8006,true,ScanKind.PVE,fingerprint="ab".repeat(32))
        CertificateTrustStore(root.resolve("certificates.json")).save(listOf(ApprovedCertificate(hit.origin,"ab".repeat(32))))
        val workspace = Workspace(this)
        val desk = ProfileDesk(workspace,ProfileStore(root.resolve("profiles.json")))
        val motion = MotionPreferences(root.resolve("motion")); motion.put(DESK_PASSWORD_MODE,true)
        var calls = 0
        desk.router = { _,_ -> calls++; error("Offline route reached") }
        Appearance.preview(false,textScale=1.5f)
        val scene = ImageComposeScene(960,640,Density(2f,1.5f),coroutineContext=coroutineContext) {
            val state by desk.state.collectAsState()
            MaterialTheme(colorScheme=ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    DeskScreen(workspace.state.value,state,desk,scanned=hit,motion=motion) {}
                }
            }
        }
        val ui = CreateScene(scene)
        try {
            ui.frames()
            val secret = ui.nodes().filter { it.config.getOrNull(SemanticsActions.SetText)!=null }[2]
            secret.config[SemanticsActions.SetText].action!!(AnnotatedString("test-password")); ui.frames()
            val action = revealLoginAction(ui,"Connect",640)
            assertNull(action.config.getOrNull(SemanticsProperties.Disabled))
            assertFails { ui.text("Trust and connect") }
            val at = action.boundsInWindow.center
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move,at)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press,at)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release,at)
            ui.frames()
            assertEquals(1,calls)
            assertEquals("ab".repeat(32),desk.manualTrust(hit.origin).known.single())
        } finally { scene.close(); desk.close(); workspace.close(); Appearance.preview(false) }
    }
    @Test fun passwordEnterDoesNotApproveButFocusedConsentConnectsOnce() = runSceneTest {
        val root=Files.createTempDirectory("login-interaction")
        val workspace=Workspace(this)
        val desk=ProfileDesk(workspace,ProfileStore(root.resolve("profiles.json")))
        var calls=0
        desk.router={_,_-> calls++; error("Offline route reached") }
        val motion=MotionPreferences(root.resolve("motion"));motion.put(DESK_PASSWORD_MODE,true)
        val hit=ScanHit("pve.example.test",8006,true,ScanKind.PVE,fingerprint="ab".repeat(32))
        val scene=ImageComposeScene(1600,900,coroutineContext=coroutineContext) {
            val state by desk.state.collectAsState()
            MaterialTheme(colorScheme=ringColors) { DeskScreen(workspace.state.value,state,desk,scanned=hit,motion=motion) {} }
        }
        val ui=CreateScene(scene)
        try {
            ui.frames()
            val fields=ui.nodes().filter { it.config.getOrNull(SemanticsActions.SetText)!=null }
            // address, user, password, OTP, optional pasted fingerprint
            assertEquals(5,fields.size)
            fields[2].config[SemanticsActions.SetText].action!!(AnnotatedString("test-password"));ui.frames()
            fields[2].config[SemanticsActions.RequestFocus].action!!();ui.frames()
            ui.key(Key.Enter)
            assertEquals(0,calls);assertFalse(Files.exists(root.resolve("certificates.json")))
            val button=ui.text("Trust and connect").let { n -> generateSequence(n) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick)!=null } }
            assertNull(button.config.getOrNull(SemanticsProperties.Disabled))
            button.config[SemanticsActions.RequestFocus].action!!();ui.frames();ui.key(Key.Enter)
            assertEquals(1,calls)
            assertEquals("ab".repeat(32),desk.manualTrust(hit.origin).known.single())
            assertTrue(desk.hasManualSecret()) // refused route retains the typed secret
        } finally { scene.close();desk.close();workspace.close() }
    }
    @Test fun replacementRequiresCheckboxAndPasswordEnterCannotApproveIt() = runSceneTest {
        val root=Files.createTempDirectory("login-replace")
        val origin="https://pve.example.test:8006"
        CertificateTrustStore(root.resolve("certificates.json")).save(listOf(ApprovedCertificate(origin,"cd".repeat(32))))
        val workspace=Workspace(this);val desk=ProfileDesk(workspace,ProfileStore(root.resolve("profiles.json")))
        var calls=0;desk.router={_,_->calls++;error("Offline route reached")}
        val motion=MotionPreferences(root.resolve("motion"));motion.put(DESK_PASSWORD_MODE,true)
        val scene=ImageComposeScene(1600,900,coroutineContext=coroutineContext) {
            val state by desk.state.collectAsState()
            MaterialTheme(colorScheme=ringColors) { DeskScreen(workspace.state.value,state,desk,
                scanned=ScanHit("pve.example.test",8006,true,ScanKind.PVE,fingerprint="ab".repeat(32)),motion=motion) {} }
        }
        val ui=CreateScene(scene)
        fun actionable(label:String): SemanticsNode = generateSequence(ui.text(label)) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick)!=null }
        try {
            ui.frames()
            val secret=ui.nodes().filter { it.config.getOrNull(SemanticsActions.SetText)!=null }[2]
            secret.config[SemanticsActions.SetText].action!!(AnnotatedString("secret"));ui.frames()
            secret.config[SemanticsActions.RequestFocus].action!!();ui.frames();ui.key(Key.Enter)
            assertEquals(0,calls)
            assertNotNull(actionable("Replace trust and connect").config.getOrNull(SemanticsProperties.Disabled))
            ui.click("Replace the known certificate with this scanned certificate")
            assertNull(actionable("Replace trust and connect").config.getOrNull(SemanticsProperties.Disabled))
            actionable("Replace trust and connect").config[SemanticsActions.RequestFocus].action!!();ui.frames();ui.key(Key.Enter)
            assertEquals(1,calls);assertEquals("ab".repeat(32),desk.manualTrust(origin).known.single())
        } finally {scene.close();desk.close();workspace.close()}
    }
    @Test fun addressEditAndCancelRemoveOfferAndKindEditCannotRestoreIt() = runSceneTest {
        val root=Files.createTempDirectory("login-invalidation")
        val workspace=Workspace(this);val desk=ProfileDesk(workspace,ProfileStore(root.resolve("profiles.json")))
        val hit=ScanHit("pve.example.test",8006,true,ScanKind.PVE,fingerprint="ab".repeat(32))
        val scene=ImageComposeScene(1600,900,coroutineContext=coroutineContext) {
            val state by desk.state.collectAsState()
            MaterialTheme(colorScheme=ringColors) { DeskScreen(workspace.state.value,state,desk,scanned=hit,motion=MotionPreferences(root.resolve("motion"))) {} }
        }
        val ui=CreateScene(scene)
        try {
            ui.frames();ui.text("Trust and connect")
            ui.click("Cancel scanned certificate")
            assertFails { ui.text("Trust and connect") }
            val address=ui.nodes().first { it.config.getOrNull(SemanticsActions.SetText)!=null }
            address.config[SemanticsActions.SetText].action!!(AnnotatedString("https://other.example.test:8006"));ui.frames()
            address.config[SemanticsActions.SetText].action!!(AnnotatedString(hit.origin));ui.frames()
            assertFails { ui.text("Trust and connect") }
            assertFalse(Files.exists(root.resolve("certificates.json")))
        } finally { scene.close();desk.close();workspace.close() }
    }
}
