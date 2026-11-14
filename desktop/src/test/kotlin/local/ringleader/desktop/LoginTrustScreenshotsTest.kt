package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.*
import local.ringleader.core.*
import java.nio.file.Files
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Scroll the actual action into view, instead of overshooting into the saved-profile section. */
internal suspend fun revealLoginAction(ui: CreateScene, label: String, height: Int): SemanticsNode {
    fun action() = ui.nodes().firstNotNullOfOrNull { node ->
        if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.equals(label, true) } != true) null
        else generateSequence(node) { it.parent }.firstOrNull { it.config.contains(SemanticsActions.OnClick) }
    } ?: error("Missing login action: $label")
    repeat(12) {
        val node = action()
        val top = node.positionInRoot.y
        val bottom = top + node.size.height
        if (top >= 0f && bottom <= height && node.size.height > 0) return node
        val scroll = generateSequence(node.parent) { it.parent }
            .first { it.config.contains(SemanticsActions.ScrollBy) }
        scroll.config[SemanticsActions.ScrollBy].action!!(0f, top - height * .55f)
        ui.frames()
    }
    error("Login action could not be brought into view: $label")
}

class LoginTrustScreenshotsTest {
    @Test fun screenshots() = runSceneTest {
        if (System.getenv("RINGMASTER_SCREENSHOTS") != "1") return@runSceneTest
        val phase = System.getenv("RINGMASTER_UI_PHASE") ?: "login-after"
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = Workspace(scope)
        val root = Files.createTempDirectory("login-shots")
        val desk = ProfileDesk(workspace, ProfileStore(root.resolve("profiles.json")))
        val hit = ScanHit("pve.example.test",8006,true,ScanKind.PVE,"Proxmox", "AB:".repeat(31)+"CD")
        try {
            for (trustState in if (phase == "login-before") listOf("first") else listOf("first","repeat","changed")) {
            val store = CertificateTrustStore(root.resolve("certificates.json"))
            store.load()
            store.save(if (trustState == "first") emptyList() else listOf(ApprovedCertificate(certificateOrigin(hit.origin), if (trustState == "repeat") canonicalOptionalPin(hit.fingerprint!!)!! else "cd".repeat(32))))
            for (light in listOf(false,true)) for ((w,h,density,font) in listOf(listOf(960,640,1,1),listOf(1600,900,1,1),listOf(960,640,2,1))) {
                val scale = if (density == 2) 1.5f else font.toFloat()
                Appearance.preview(light, textScale = scale)
                for (bottom in listOf(false,true)) {
                    if (bottom && w >= 1600) continue
                    if (System.getenv("RINGMASTER_LOGIN_GAP_ONLY") == "1" && !(trustState == "repeat" && density == 2 && bottom)) continue
                    val scene = ImageComposeScene(w,h,Density(density.toFloat(),scale),coroutineContext=coroutineContext) {
                        MaterialTheme(colorScheme=ringColors) {
                            CompositionLocalProvider(LocalReduceMotion provides true) { Surface(Modifier.fillMaxSize(),color=Tech.Canvas) {
                                DeskScreen(workspace.state.value,desk.state.value,desk,scanned=hit,
                                    motion=MotionPreferences(root.resolve("motion"))) {}
                            } }
                        }
                    }
                    try {
                        val ui = CreateScene(scene)
                        ui.frames()
                        if (bottom) {
                            val label = when(trustState) { "repeat" -> "Connect"; "changed" -> "Replace trust and connect"; else -> "Trust and connect" }
                            val action = revealLoginAction(ui, label, h)
                            assertTrue(action.boundsInWindow.height > 0f)
                        }
                        val file = File("../artifacts/ui/$phase/$trustState-${if(light) "light" else "dark"}-${w}x$h-${density}x-${if(bottom) "bottom" else "top"}.png")
                        file.parentFile.mkdirs(); file.writeBytes(scene.render().encodeToData()!!.bytes)
                    } finally { scene.close() }
                }
            }
            }
        } finally { desk.close(); workspace.close(); scope.cancel(); Appearance.preview(false) }
    }
}
