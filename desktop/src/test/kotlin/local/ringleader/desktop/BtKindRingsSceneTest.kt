@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.yield
import local.ringleader.core.*
import kotlin.test.*

class BtKindRingsSceneTest {
    @Test fun shortViewCanReachResultsAndSelectionDetails() = runSceneTest {
        Appearance.preview(ThemeMode.Dark)
        val sim=BtSimulatedScan(5).apply { advanceTo(18_000) }
        val scene=ImageComposeScene(760,320,Density(1f,1.5f),coroutineContext=coroutineContext) {
            MaterialTheme(colorScheme=ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                Surface(Modifier.fillMaxSize()) { BtScanView(sim.snapshot(),false) }
            } }
        }
        var time=0L
        suspend fun settle() { repeat(24) { time+=16_000_000L; scene.render(time).close(); yield() } }
        fun nodes(): List<SemanticsNode> {
            val all= mutableListOf<SemanticsNode>()
            fun visit(n:SemanticsNode) { all+=n; n.children.forEach(::visit) }
            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }; return all
        }
        fun find(text:String)=nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { t->t.text==text }==true }
        try {
            settle()
            val scroll=nodes().first { it.config.getOrNull(SemanticsActions.ScrollBy)!=null }
            assertTrue(scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f,240f))
            settle()
            val name=nodes().firstOrNull { n ->
                val texts=n.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
                "Office mouse" in texts && sim.address(2) in texts
            }
            assertNotNull(name,"results remain present after scrolling")
            assertTrue(name.boundsInWindow.top >= 0 && name.boundsInWindow.bottom <= 320,"row is reachable: ${name.boundsInWindow}")
            val p=Offset(120f,name.boundsInWindow.center.y)
            scene.sendPointerEvent(PointerEventType.Press,p); scene.sendPointerEvent(PointerEventType.Release,p); settle()
            scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f,-240f); settle()
            val overview=nodes().filter { it.config.getOrNull(SemanticsActions.ScrollBy)!=null }[1]
            overview.config[SemanticsActions.ScrollBy].action!!.invoke(0f,800f); settle()
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { t->t.text.contains("readings") && t.text.contains("avg") }==true },"selected signal detail is reachable in the overview")
        } finally { scene.close(); Appearance.preview(ThemeMode.Dark) }
    }

    @Test fun inactiveLiveViewRequestsNoDecorativeFrames()=runSceneTest {
        val sim=BtSimulatedScan(120).apply { advanceTo(8000) }
        val scene=ImageComposeScene(1600,1000,Density(1f),coroutineContext=coroutineContext) {
            MaterialTheme(colorScheme=ringColors) { CompositionLocalProvider(LocalWindowActive provides false) { BtScanView(sim.snapshot(),true) } }
        }
        try { repeat(8) { scene.render(it*16_000_000L).close(); yield() }; assertFalse(scene.hasInvalidations()) }
        finally { scene.close() }
    }

    @Test fun adapterOffNotesAndUnheardReportsStayVisible()=runSceneTest {
        Appearance.preview(ThemeMode.Dark)
        val scan=BtScan(source=BtSource.Local,adapters=emptyList(),devices=emptyList(),at=java.time.Instant.EPOCH,note=BT_ADAPTER_OFF)
        val scene=ImageComposeScene(720,479,Density(1f),coroutineContext=coroutineContext) {
            MaterialTheme(colorScheme=ringColors) { BtScanView(scan,false,platform="Linux") }
        }
        try {
            repeat(6) { scene.render(it*16_000_000L).close(); yield() }
            val texts=mutableListOf<String>()
            fun visit(n:SemanticsNode) { n.config.getOrNull(SemanticsProperties.Text)?.forEach { texts+=it.text }; n.children.forEach(::visit) }
            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
            assertTrue(BT_ADAPTER_OFF in texts)
            assertTrue(texts.any { it.contains("will not turn the radio on") })
            assertFalse(texts.any { it.contains("HEARD") })
        } finally { scene.close() }
    }

    @Test fun guestAttachmentMetadataWrapsAndScanActionRemainsReachable()=runSceneTest {
        Appearance.preview(ThemeMode.Dark,textScale=1.5f)
        val scope=kotlinx.coroutines.CoroutineScope(coroutineContext+kotlinx.coroutines.SupervisorJob())
        val workspace=Workspace(scope)
        val guest=Guest("https://192.0.2.10:8006",120,"Example guest","node","VM","running")
        val mapping="Example adapter mapping with a deliberately long invented name so every part remains readable at large text size"
        val hardware=parseHardware("""{"data":{"agent":"1","usb0":"mapping=$mapping,usb3=1","usb1":"spice"}}""")
        val scene=ImageComposeScene(720,479,Density(1f,1.5f),coroutineContext=coroutineContext) {
            MaterialTheme(colorScheme=ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                Surface(Modifier.fillMaxSize(),color=Tech.Canvas) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        GuestBluetoothTab(WorkspaceState(),workspace,guest,hardware)
                    }
                }
            } }
        }
        var time=0L
        suspend fun settle() { repeat(24) { time+=16_000_000L;scene.render(time).close();yield() } }
        fun nodes():List<SemanticsNode> {
            val out=mutableListOf<SemanticsNode>()
            fun visit(n:SemanticsNode) { out+=n;n.children.forEach(::visit) }
            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) };return out
        }
        suspend fun picture(name:String) {
            val out=System.getenv("RINGMASTER_KIND_SHOTS")?.let { java.io.File(it) } ?: return
            time+=16_000_000L;scene.render(time).close();yield()
            time+=16_000_000L
            scene.render(time).use { img->img.encodeToData()!!.use { java.io.File(out,name).writeBytes(it.bytes) } }
            yield()
        }
        try {
            settle()
            val detail=nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { t->mapping in t.text }==true }
            val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            detail.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            assertTrue(layouts.isNotEmpty() && layouts.all { !it.hasVisualOverflow },"full mapping metadata wraps")
            assertTrue(detail.boundsInWindow.width<=720)
            picture("guest-metadata.png")
            nodes().first { it.config.getOrNull(SemanticsActions.ScrollBy)!=null }.config[SemanticsActions.ScrollBy].action!!.invoke(0f,800f)
            settle()
            assertTrue(nodes().any { n->n.config.getOrNull(SemanticsProperties.Text)?.any { t->t.text.startsWith("SCAN INSIDE VM") }==true && n.boundsInWindow.top>=0 && n.boundsInWindow.bottom<=479 },"scan action remains reachable")
            picture("guest-actions.png")
        } finally {
            scene.close();workspace.close();scope.coroutineContext[kotlinx.coroutines.Job]?.cancel();Appearance.preview(ThemeMode.Dark)
        }
    }

    @Test fun longSelectedCanvasNamesStayInsideSmallFields()=runSceneTest {
        Appearance.preview(ThemeMode.Dark,textScale=1.5f)
        val d=BtDevice("02:00:00:00:00:01","Very long synthetic selected device name ".repeat(12),rssi=-45,category="input-mouse")
        val scan=BtScan(BtSource.Local,emptyList(),listOf(d),java.time.Instant.EPOCH)
        try { for (density in listOf(1f,.875f)) {
            val scene=ImageComposeScene(300,220,Density(density,1.5f),coroutineContext=coroutineContext) {
                MaterialTheme(colorScheme=ringColors) {
                    Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                        KindRings(listOf(d),scan,ScanClock(0),false,d.address,null,{}, {},Modifier.size(180.dp))
                    }
                }
            }
            try {
                repeat(8) { scene.render(it*16_000_000L).close();yield() }
                scene.render(144_000_000).use { image ->
                    org.jetbrains.skia.Bitmap().use { bitmap ->
                        bitmap.allocPixels(image.imageInfo);image.readPixels(bitmap)
                        val edge=kotlin.math.ceil(180*density).toInt()
                        for (x in edge+4 until edge+55 step 3) for (y in 10 until edge-10 step 3)
                            assertEquals(0xffff00ff.toInt(),bitmap.getColor(x,y),"canvas label escaped at $density: $x,$y")
                    }
                }
                yield()
            } finally { scene.close() }
        } } finally { Appearance.preview(ThemeMode.Dark) }
    }
}
