@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import local.ringleader.core.BtSource
import java.io.File
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

/** Synthetic production views only. Pixel dimensions are content, not native window bounds. */
class BtKindRingsShotsTest {
    @Test fun pictures() {
        val out = System.getenv("RINGMASTER_KIND_SHOTS")?.let(::File) ?: return
        out.mkdirs()
        runSceneTest {
            val shapes = listOf(Triple(1600,1000,1f), Triple(960,640,1f), Triple(760,420,1f),
                Triple(640,300,1f), Triple(2560,1440,1.25f), Triple(950,525,1.25f), Triple(1200,800,2f), Triple(868,479,1f), Triple(720,479,1f), Triple(1085,599,1.25f), Triple(760,419,.875f), Triple(2170,1198,2.5f))
            for (theme in listOf(ThemeMode.Dark, ThemeMode.Light, ThemeMode.PitchBlack))
                for (n in listOf(5,40,120)) for ((w,h,density) in shapes) {
                    val large = w == 950 || w == 1200 || w == 1085 || w == 2170
                    Appearance.preview(theme, textScale = if (large) 1.5f else 1f)
                    val sim = BtSimulatedScan(n).apply { advanceTo(18_000) }
                    val scene = ImageComposeScene(w,h,Density(density, if (large) 1.5f else 1f),coroutineContext = coroutineContext) {
                        MaterialTheme(colorScheme = ringColors) {
                            CompositionLocalProvider(LocalReduceMotion provides true) {
                                Surface(Modifier.fillMaxSize(), color=Tech.Canvas) {
                                    BtScanView(sim.snapshot().let { if (w==720) it.copy(source=BtSource("synthetic-guest","VM Example")) else it }, true, Modifier.padding(if (w in listOf(868,720,1085,2170) || h==419) 0.dp else 16.dp), "Synthetic local / guest")
                                }
                            }
                        }
                    }
                    try {
                        repeat(6) { scene.render(it*16_000_000L).close(); yield() }
                        if (w==1600 && n==40) {
                            selectMouse(scene,sim.address(2))
                            repeat(6) { scene.render(112_000_000L+it*16_000_000).close(); yield() }
                        }
                        scene.render(224_000_000).use { img -> img.encodeToData()!!.use { File(out,"${theme.name}-$n-${w}x$h.png").writeBytes(it.bytes) } }; yield()
                    } finally { scene.close() }
                }
            Appearance.preview(ThemeMode.Dark)
            val sim = BtSimulatedScan(40).apply { advanceTo(4000) }
            var scan by mutableStateOf(sim.snapshot())
            var received by mutableLongStateOf(0L)
            val scene = ImageComposeScene(1600,1000,Density(1f),coroutineContext=coroutineContext) {
                MaterialTheme(colorScheme=ringColors) { Surface(Modifier.fillMaxSize(),color=Tech.Canvas) { BtScanView(scan,true,Modifier.padding(16.dp),"Synthetic",received) } }
            }
            try {
                repeat(30) { i ->
                    sim.advanceTo(4000L+i*100); scan=sim.snapshot(); received=i*100_000_000L
                    repeat(3) { scene.render(received+it*16_000_000).close(); yield() }
                    scene.render(received+48_000_000).use { img -> img.encodeToData()!!.use { File(out,"frame-%02d.png".format(i)).writeBytes(it.bytes) } }; yield()
                }
            } finally { scene.close() }
        }
        Appearance.preview(ThemeMode.Dark)
    }

    @Test fun liteAndShowcase() {
        val out=System.getenv("RINGMASTER_KIND_SHOTS")?.let(::File) ?: return
        runSceneTest {
            Appearance.preview(ThemeMode.Dark,showcase=true)
            val sim=BtSimulatedScan(120,simultaneous=true).apply { advanceTo(4000) }
            var scan by mutableStateOf(sim.snapshot())
            var received by mutableLongStateOf(0)
            val scene=ImageComposeScene(1600,1000,Density(1f),coroutineContext=coroutineContext) {
                MaterialTheme(colorScheme=ringColors) { CompositionLocalProvider(LocalMotion provides RmMotion(MotionTier.Lite)) {
                    Surface(Modifier.fillMaxSize(),color=Tech.Canvas) { BtScanView(scan,true,Modifier.padding(16.dp),"Synthetic Lite",received) }
                } }
            }
            try {
                repeat(12) { i ->
                    sim.advanceTo(4000L+i*200);scan=sim.snapshot();received=i*200_000_000L
                    repeat(3) { scene.render(received+it*16_000_000).close(); yield() }
                    kotlinx.coroutines.delay(65)
                    scene.render(received+80_000_000).use { img -> img.encodeToData()!!.use { File(out,"lite-frame-%02d.png".format(i)).writeBytes(it.bytes) } }; yield()
                }
            } finally { scene.close(); Appearance.preview(ThemeMode.Dark) }
        }
    }

    private fun selectMouse(scene:ImageComposeScene,address:String) {
        fun visit(n:SemanticsNode):SemanticsNode? {
            val texts=n.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
            if (address in texts && "Office mouse" in texts) return n
            return n.children.firstNotNullOfOrNull(::visit)
        }
        val node=scene.semanticsOwners.firstNotNullOfOrNull { visit(it.rootSemanticsNode) } ?: error("mouse row missing")
        val p=Offset(node.boundsInWindow.left+60,node.boundsInWindow.center.y)
        scene.sendPointerEvent(PointerEventType.Press,p);scene.sendPointerEvent(PointerEventType.Release,p)
    }
}
