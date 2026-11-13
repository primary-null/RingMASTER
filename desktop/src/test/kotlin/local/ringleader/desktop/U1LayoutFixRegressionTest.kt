@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import local.ringleader.core.*
import java.nio.file.Files
import java.awt.Rectangle
import java.time.Instant
import kotlin.test.*

class U1LayoutFixRegressionTest {
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = buildList {
        fun visit(n: SemanticsNode) { add(n);n.children.forEach(::visit) }
        scene.semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun text(scene: ImageComposeScene, label: String) = assertNotNull(nodes(scene).firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.equals(label,true) } == true }, label)
    private fun whole(scene: ImageComposeScene, label: String,w:Int,h:Int) {
        val n=text(scene,label); val b=n.boundsInWindow
        assertTrue(b.width>5 && b.left>=0 && b.top>=0 && b.right<=w+1 && b.bottom<=h+1,"$label outside $w x $h: $b")
        val layouts=mutableListOf<TextLayoutResult>()
        n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        layouts.forEach { assertFalse(it.didOverflowHeight,"$label clips vertically") }
    }
    @Test fun minimumGeometryAccountsForWorkAreaAndScale() {
        assertEquals(1920,minimumWindowSize(2f,Rectangle(0,0,1920,1040)).width)
        assertEquals(1040,minimumWindowSize(2f,Rectangle(0,0,1920,1040)).height)
        assertEquals(728,minimumWindowSize(2f,Rectangle(0,0,1366,728)).height)
    }
    @Test fun guestIdentityNeverCompetesWithActionWidths() = runSceneTest {
        val root=Files.createTempDirectory("u1-header");val ws=Workspace(this)
        try {
            val lab=MatrixLab(root)
            val scene=ImageComposeScene(600,600,Density(1f,1.5f),coroutineContext=coroutineContext) {
                MaterialTheme(colorScheme=ringColors) { GuestDetailPage(lab.state(lab.longVm),ws,{},{_,_->},pins=lab.pins,connectionKey=lab.origin) }
            }
            try { MatrixDriver().frames(scene,5)
                val name=text(scene,lab.longVm.name)
                assertTrue(name.boundsInRoot.width>500,"Guest name collapsed: ${name.boundsInRoot}")
                val identity=text(scene,"${lab.longVm.kind} ${lab.longVm.id} · ${lab.longVm.node}")
                assertTrue(identity.boundsInRoot.height<40,"Metadata wraps one glyph per line")
                whole(scene,"Console",600,600)
            } finally { scene.close() }
        } finally { ws.close();root.toFile().deleteRecursively() }
    }
    @Test fun shortRailCanReachSettingsAndSettingsCanReachAbout() = runSceneTest {
        val root=Files.createTempDirectory("u1-rail");val ws=Workspace(this)
        try { val lab=MatrixLab(root)
            for (settings in listOf(false,true)) {
                val scene=ImageComposeScene(640,270,Density(1f,1.5f),coroutineContext=coroutineContext) {
                    MaterialTheme(colorScheme=ringColors) { if(settings) SettingsSheet(lab.state(),ws,SettingsSection.Appearance,{},{},{},Modifier.fillMaxSize())
                    else NavigationRail(lab.state(),"Fleet","Synthetic",ws,initialExpanded=true) {} }
                }
                try { val d=MatrixDriver();d.frames(scene,5)
                    val scroll=assertNotNull(nodes(scene).firstOrNull { it.config.contains(SemanticsActions.ScrollBy) })
                    assertTrue(scroll.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!!.maxValue()>0)
                    scroll.config.getOrNull(SemanticsActions.ScrollBy)!!.action!!.invoke(0f,10000f);d.frames(scene,4)
                    whole(scene,if(settings) "About" else "Settings",640,270)
                } finally { scene.close() }
            }
        } finally { ws.close();root.toFile().deleteRecursively() }
    }
    @Test fun shortTaskLogKeepsFooterActionsAndCloseReachable() = runSceneTest {
        val root=Files.createTempDirectory("u1-log"); val ws=Workspace(this)
        try {
            val lab=MatrixLab(root)
            val task=lab.tasks.first()
            val scene=ImageComposeScene(480,240,Density(1f,1.5f),coroutineContext=coroutineContext) {
                MaterialTheme(colorScheme=ringColors) {
                    TaskLogPanel(lab.state().copy(taskLog=TaskLogState(task=task)),ws,Modifier.fillMaxSize())
                }
            }
            try { MatrixDriver().frames(scene,5);whole(scene,"Copy log",480,240);whole(scene,"Close",480,240) }
            finally { scene.close() }
        } finally { ws.close();root.toFile().deleteRecursively() }
    }
    @Test fun createWizardActionsFitEveryStepAtLargestTextAndReachableScaledMinimum() = runSceneTest {
        val root=Files.createTempDirectory("u1-create");val home=System.getProperty("user.home")
        System.setProperty("user.home",root.toString())
        try {
            for(kind in listOf(NewGuestKind.Vm,NewGuestKind.Container)) for(step in if(kind==NewGuestKind.Vm) CreateFields.VM_STEPS else CreateFields.CT_STEPS) {
                val session=demoCreateSession(kind,"demo-node-a",1,Instant.EPOCH)
                val scene=ImageComposeScene(1366,728,Density(2f,1.5f),coroutineContext=coroutineContext) {
                    MaterialTheme(colorScheme=ringColors) { Surface(color=Tech.Canvas,contentColor=Tech.Ink) {
                        if(kind==NewGuestKind.Vm) CreateVmWizard(session,CreateContext(),listOf(session.node),{},{},{},{},{},initialStep=step,initialAdvanced=true,preferences=CreateWizardPreferences.memory())
                        else CreateCtWizard(session,CreateContext(),CtDraft(session.node,vmid="140",hostname="sample-ct"),{_,_->},{},initialStep=step,initialAdvanced=true,preferences=CreateWizardPreferences.memory())
                    } }
                }
                try { MatrixDriver().frames(scene,5)
                    whole(scene,"Cancel",1366,728)
                    whole(scene,"Next",1366,728)
                    val title = text(scene,if(kind==NewGuestKind.Vm) "New VM \u00b7 Demo \u00b7 sample lists" else "Create container")
                    assertTrue(title.boundsInRoot.height < 80, "Create title wraps")
                    System.getenv("U1_LAYOUT_FIX_SHOTS")?.let { out -> java.io.File(out,"create-$kind-$step.png").apply { parentFile.mkdirs() }.writeBytes(scene.render(500_000_000).use { it.encodeToData()!!.use { d -> d.bytes } }) }
                } finally { scene.close() }
            }
        } finally { System.setProperty("user.home",home);root.toFile().deleteRecursively() }
    }
}
