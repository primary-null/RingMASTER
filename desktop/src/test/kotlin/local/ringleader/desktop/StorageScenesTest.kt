@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.ui.InternalComposeUiApi::class)
@file:Suppress("INVISIBLE_MEMBER","INVISIBLE_REFERENCE")
package local.ringleader.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import kotlin.test.assertTrue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import local.ringleader.core.*
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

class StorageScenesTest {
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        val all=mutableListOf<SemanticsNode>();fun visit(n: SemanticsNode) { all+=n;n.children.forEach(::visit) }
        scene.semanticsOwners.forEach {visit(it.rootSemanticsNode)};return all
    }
    private fun text(n: SemanticsNode)=n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") {it.text}.orEmpty()

    @Test fun pictures() = runSceneTest {
        val folder = System.getenv("STORAGE_SHOTS") ?: return@runSceneTest
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val workspace = Workspace(scope)
        workspace.exploreDemo()
        val row = workspace.state.value.overview!!.storage.first()
        workspace.selectStorage(row.node, row.id)
        workspace.loadStorageConfig()
        workspace.loadStorageHistory(row.node, row.id)
        yield()
        val base = workspace.state.value
        for ((w,h) in listOf(960 to 640,1600 to 1000,2560 to 1440,1366 to 728)) {
            val scale = if(w==1366) 2f else 1f
            for (theme in listOf(ThemeMode.Dark,ThemeMode.Light,ThemeMode.PitchBlack)) {
                Appearance.preview(theme, textScale=if(scale==2f) 1.5f else 1f)
                val scene = ImageComposeScene(w,h, density=Density(scale,if(scale==2f) 1.5f else 1f),coroutineContext=coroutineContext) {
                    MaterialTheme(colorScheme=ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxWidth().height(32.dp))
                            Box(Modifier.weight(1f)) { ChartLensHost(base,workspace) { StoragePage(base,workspace) } }
                        }
                    } }
                }
                try {
                    repeat(3) { scene.render(it*100_000_000L); yield() }
                    val target=File(folder,"storage-${theme.name}-${w}x$h.png"); target.parentFile.mkdirs()
                    scene.render(500_000_000L).use { target.writeBytes(it.encodeToData()!!.bytes) }
                    if(w==1366 || w==960) {
                        nodes(scene).filter { it.config.getOrNull(SemanticsActions.ScrollBy)!=null }.forEach { it.config[SemanticsActions.ScrollBy].action?.invoke(0f,10000f) }
                        repeat(5) {scene.render(600_000_000L+it*100_000_000L).close();yield()}
                        scene.render(1_200_000_000L).use { File(folder,"scrolled-${theme.name}-${w}x$h.png").writeBytes(it.encodeToData()!!.bytes) }
                        val contents=nodes(scene).first {text(it)=="CONTENTS" && it.config.getOrNull(SemanticsActions.OnClick)!=null}
                        contents.config[SemanticsActions.OnClick].action!!.invoke()
                        repeat(5) {scene.render(1_300_000_000L+it*100_000_000L).close();yield()}
                        assertTrue(nodes(scene).any {text(it).contains("local:iso/")})
                        scene.render(1_900_000_000L).use { File(folder,"contents-${theme.name}-${w}x$h.png").writeBytes(it.encodeToData()!!.bytes) }
                    }
                } finally { scene.close() }
            }
        }
        Appearance.preview(false); scope.cancel()
    }
}
