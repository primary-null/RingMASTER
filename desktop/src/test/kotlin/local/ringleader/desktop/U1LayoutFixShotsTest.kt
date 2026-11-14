@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import local.ringleader.core.*
import java.nio.file.Files
import java.io.File
import kotlin.test.Test

/** Focused production-repair evidence; deliberately independent of the sibling's matrix runner. */
class U1LayoutFixShotsTest {
    @Test fun pictures() {
        val output = System.getenv("U1_LAYOUT_FIX_SHOTS") ?: return
        val home = System.getProperty("user.home")
        val root = Files.createTempDirectory("u1-layout-fix")
        System.setProperty("user.home", root.toString())
        try { runSceneTest {
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            val lab = MatrixLab(root)
            val idle = Workspace(scope)
            val connected = connectedWorkspace(scope, lab)
            val env = MatrixEnv(lab,idle,connected,scope,root)
            val names = setOf("guest-summary-long","guest-console-rail-folded","fleet-vm-rail","activity-log","dlg-chart-lens","tools-result","scan-results","hood","vpn","settings-appearance")
            try {
                val shots = matrixShots(scope,lab,env).filter { it.name in names }
                val sizes = listOf(Triple(960,640,1f), Triple(1366,728,2f), Triple(960,520,2f), Triple(1600,900,1f))
                for ((w,h,ui) in sizes) for (shot in shots) {
                    Appearance.preview(ThemeMode.Dark,false,1.5f)
                    val driver = MatrixDriver(width=w,height=h)
                    val scene = ImageComposeScene(w,h,Density(ui,1.5f),coroutineContext=coroutineContext) {
                        MaterialTheme(colorScheme=ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) { Surface(color=Tech.Canvas,contentColor=Tech.Ink) { shot.content(env) } } }
                    }
                    try {
                        driver.frames(scene,shot.frames,shot.stepMs)
                        shot.prepare?.invoke(scene,driver)
                        val file=File(output,"${shot.name}__${w}x${h}__ui$ui.png").apply { parentFile.mkdirs() }
                        scene.render(driver.clock+16_000_000).use { it.encodeToData()!!.use { data -> file.writeBytes(data.bytes) } }
                        driver.scrollToEnd(scene,w,h)
                        scene.render(driver.clock+16_000_000).use { it.encodeToData()!!.use { data -> File(output,file.nameWithoutExtension+"__end.png").writeBytes(data.bytes) } }
                    } finally { scene.close() }
                    System.gc()
                }
            } finally { connected.close();idle.close();scope.cancel() }
        } } finally { System.setProperty("user.home",home); root.toFile().deleteRecursively();Appearance.preview(false) }
    }
}
