@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.yield
import local.ringleader.core.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Actual defaults, validators, encoders and strict demo choice evidence; only initial field selections are fixtures. */
class CreateRealCoreRendersTest {
    @Test fun renderBothRealWizardsWhenRequested() = runSceneTest {
        val output=System.getenv("RINGMASTER_REAL_CREATE_SCENES")?.let(Path::of) ?: return@runSceneTest
        Files.createDirectories(output)
        for(kind in NewGuestKind.entries) {
            val gateway=FakeCreateGuestGateway()
            val workspace=Workspace(this,createGateway=gateway)
            workspace.exploreDemo();assertTrue(workspace.openCreateSession(kind,"demo-node-a"))
            val session=workspace.state.value.creation!!
            val context=createContext(workspace.state.value,session)
            assertNotNull(context.choiceNode);assertNotNull(context.volumes);assertNotNull(context.bridges)
            val vm=defaultVmDraft(session.node,context).copy(vmid="140",name="sample-vm",install=CdDrive(media=CdMedia.Iso("local",demoCreateVolumes.getValue("local" to "iso").first().volid)),nic=VmNic(bridge="vmbr0"))
            val ct=defaultCtDraft(session.node,context).copy(vmid="140",hostname="sample-ct",template=demoCreateVolumes.getValue("local" to "vztmpl").first().volid,nic=CtNic(bridge="vmbr0"))
            if(kind==NewGuestKind.Vm) assertTrue(validateVm(vm,context).isEmpty(),validateVm(vm,context).toString())
            else assertTrue(validateCt(ct,context).isEmpty(),validateCt(ct,context).toString())
            for(light in listOf(false,true)) for((width,height) in listOf(960 to 640,1600 to 1000))
                for(step in if(kind==NewGuestKind.Vm) CreateFields.VM_STEPS else CreateFields.CT_STEPS)
                    for((position,scroll) in listOf("top" to 0,"bottom" to 1000000)) {
                        Appearance.preview(light)
                        val scene=ImageComposeScene((width*1.25f).toInt(),(height*1.25f).toInt(),Density(1.25f),coroutineContext=coroutineContext) {
                            MaterialTheme(colorScheme=ringColors) { CompositionLocalProvider(LocalCreatePreviewScroll provides scroll,LocalReduceMotion provides true) {
                                if(kind==NewGuestKind.Vm) CreateVmWizard(session,context,listOf(session.node),{},{},{},{},{},initialStep=step,initialAdvanced=true,defaults={_,_->vm},preferences=CreateWizardPreferences.memory())
                                else CreateCtWizard(session,context,ct,{_,s->s.close()},{},initialStep=step,initialAdvanced=true,previewScroll=scroll,preferences=CreateWizardPreferences.memory())
                            } }
                        }
                        try {
                            repeat(8) { scene.render((it+1)*100000000L).close();yield() }
                            val cancel=assertNotNull(allNodes(scene).firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { t->t.text=="CANCEL" }==true })
                            assertTrue(cancel.boundsInWindow.bottom<=height*1.25f && cancel.boundsInWindow.right<=width*1.25f)
                            val file="${if(kind==NewGuestKind.Vm) "vm" else "ct"}-${if(light) "light" else "dark"}-${width}x$height-$step-$position.png"
                            Files.write(output.resolve(file),scene.render(900000000L).encodeToData()!!.bytes)
                        } finally { scene.close() }
                    }
            assertTrue(gateway.calls.isEmpty());workspace.close()
        }
        Appearance.preview(false)
    }
}
