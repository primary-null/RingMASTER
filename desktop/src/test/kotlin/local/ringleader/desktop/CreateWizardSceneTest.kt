@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.yield
import local.ringleader.core.*
import java.io.File
import java.time.Instant
import kotlin.test.*

class CreateWizardSceneTest {
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun visit(n: SemanticsNode) { result += n; n.children.forEach(::visit) }
        scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
        return result
    }
    @Test fun everyVmStepKeepsActionsInView() = runSceneTest {
        val out = System.getenv("RINGMASTER_CREATE_SHOTS")?.let { File(it).apply { mkdirs() } }
        val sizes = listOf(960 to 640, 1280 to 800, 1600 to 1000, 2560 to 1440)
        val storage = StorageChoice("demo-node-a", "sample-disks", "dir", setOf("images", "iso", "import"), false, 1024L, 500L*1073741824, "available")
        val session = demoCreateSession(NewGuestKind.Vm, "demo-node-a", 1, Instant.EPOCH)
        try {
            for (light in listOf(false,true)) for ((w,h) in sizes) for (step in CreateFields.VM_STEPS) for (advanced in listOf(false,true)) {
                Appearance.preview(light)
                val scene = ImageComposeScene((w*1.25f).toInt(), (h*1.25f).toInt(), Density(1.25f), coroutineContext = coroutineContext) {
                    MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                        CreateVmWizard(session, CreateContext(storages = listOf(storage)), listOf("demo-node-a"), {}, {}, {}, {}, {},
                            initialStep = step, initialAdvanced = advanced, defaults = { node,_ -> VmDraft(node, vmid = "140", name = "sample-vm", install = CdDrive(media = CdMedia.Iso("local", "local:iso/debian-13.2.0-amd64-netinst.iso")), disks = listOf(VmDisk(source = DiskSource.New("sample-disks")))) },
                            validate = { _,_ -> emptyMap() }, parameters = { d,_ -> listOf(ParamLine("vmid",d.vmid),ParamLine("name",d.name),ParamLine("scsi0","sample-disks:32,iothread=1"),ParamLine("memory",d.memoryMib)) },
                            freeze = { _,_ -> FreezeResult.Invalid(mapOf("general.vmid" to "test")) })
                    } }
                }
                try {
                    repeat(4) { scene.render((it+1)*16_000_000L).close(); yield() }
                    val all = nodes(scene)
                    for (label in listOf("CREATE VM 140\u2026", "CANCEL", "NEXT", "BACK")) {
                        val node = all.firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
                        assertNotNull(node, "$label missing at $w x $h / $step")
                        assertTrue(node.boundsInWindow.top >= 0 && node.boundsInWindow.bottom <= h*1.25f, "$label outside $w x $h / $step: ${node.boundsInWindow}")
                        assertTrue(node.boundsInWindow.right <= w*1.25f)
                    }
                    out?.let { File(it,"${if(light) "light" else "dark"}-$step-${w}x$h-${if(advanced) "advanced" else "basic"}.png").writeBytes(scene.render(100_000_000L).encodeToData()!!.bytes); yield() }
                } finally { scene.close() }
            }
        } finally { Appearance.preview(false) }
    }

    @Test fun outcomeViewsNeverOfferResubmission() = runSceneTest {
        val results = listOf<CreateResult>(
            CreateResult.Sending(NewGuestKind.Vm,"node-a",140),
            CreateResult.Following(NewGuestKind.Vm,"node-a",140,"local-a"),
            CreateResult.Succeeded(NewGuestKind.Vm,"node-a",140,"local-a",false,GuestAppearance.Appeared),
            CreateResult.FinishedWithWarnings(NewGuestKind.Vm,"node-a",140,"local-a",2,listOf("WARN: sample warning"),GuestAppearance.NotYet),
            CreateResult.Failed(NewGuestKind.Vm,"node-a",140,"local-a","sample failure",listOf("sample log")),
            CreateResult.Unknown(NewGuestKind.Vm,"node-a",140,null,"The reply was lost."),
            CreateResult.Rejected(NewGuestKind.Vm,"node-a",140,400,"Invalid field"),
            CreateResult.Simulated(NewGuestKind.Vm,"node-a",140))
        for ((i,result) in results.withIndex()) {
            val scene = ImageComposeScene(960,640,coroutineContext = coroutineContext) { MaterialTheme(colorScheme=ringColors) { Column { CreateResultView(result) {} } } }
            try {
                repeat(3) { scene.render((it+1)*16_000_000L).close(); yield() }
                val text = nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString(" ") { it.text }
                assertFalse(text.contains("Retry create"))
                if (result is CreateResult.Unknown) { assertTrue(text.contains("Check ID".uppercase())); assertTrue(text.contains("not sent again")) }
                if (result is CreateResult.FinishedWithWarnings) { assertTrue(text.contains("Finished with 2 warnings.")); assertFalse(text.contains("was created")) }
                System.getenv("RINGMASTER_CREATE_SHOTS")?.let { File(it,"result-$i.png").writeBytes(scene.render(100_000_000L).encodeToData()!!.bytes); yield() }
            } finally { scene.close() }
        }
    }
    @Test fun listStatesExplainLoadingFailureAndEmptyAndAllowRetry() = runSceneTest {
        var retries = 0
        val scene = ImageComposeScene(960,640,coroutineContext=coroutineContext) {
            MaterialTheme(colorScheme=ringColors) { Column {
                ListStateView<String>(ReadSection(loading=true),"Reading bridges...","No bridges.",{retries++}) {}
                ListStateView<String>(ReadSection(error="Permission refused."),"Reading...","No choices.",{retries++}) {}
                ListStateView<String>(ReadSection(value=emptyList()),"Reading...","No ISO images on this storage. Upload one in Storage.",{retries++}) {}
                ListStateView<String>(ReadSection(value=listOf("sample")),"Reading...","Empty.",{retries++}) { androidx.compose.material3.Text(it.single()) }
            } }
        }
        try {
            repeat(4) { scene.render((it+1)*16_000_000L).close(); yield() }
            val all = nodes(scene)
            val text = all.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString(" ") { it.text }
            assertTrue(text.contains("Reading bridges...")); assertTrue(text.contains("Permission refused."))
            assertTrue(text.contains("No ISO images")); assertTrue(text.contains("sample"))
            val retry = all.first { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "RETRY" } == true }
            var n: SemanticsNode? = retry
            while(n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
            assertNotNull(n?.config?.getOrNull(SemanticsActions.OnClick)?.action).invoke()
            assertEquals(1,retries)
        } finally { scene.close() }
    }

}
