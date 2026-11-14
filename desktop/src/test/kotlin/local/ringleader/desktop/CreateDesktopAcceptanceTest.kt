@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.yield
import local.ringleader.core.*
import java.time.Instant
import java.nio.file.Files
import kotlin.test.*

internal class CreateScene(val scene: ImageComposeScene) {
    private var time = 0L
    suspend fun frames() { repeat(6) { time += 50_000_000; scene.render(time).close(); yield() } }
    fun nodes(): List<SemanticsNode> = buildList {
        fun visit(n: SemanticsNode) { add(n); n.children.forEach(::visit) }
        scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
    }
    fun text(s: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.equals(s,ignoreCase=true) } == true }
        ?: error("Missing '$s': " + nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString { it.text })
    suspend fun click(s: String) {
        val at = text(s).boundsInWindow.center
        scene.sendPointerEvent(PointerEventType.Move,at)
        scene.sendPointerEvent(PointerEventType.Press,at)
        scene.sendPointerEvent(PointerEventType.Release,at)
        frames()
    }
    suspend fun key(k: Key, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) {
        scene.sendKeyEvent(KeyEvent(k,KeyEventType.KeyDown,isCtrlPressed=ctrl,isAltPressed=alt,isShiftPressed=shift))
        scene.sendKeyEvent(KeyEvent(k,KeyEventType.KeyUp,isCtrlPressed=ctrl,isAltPressed=alt,isShiftPressed=shift)); frames()
    }
    suspend fun type(value: String) {
        nodes().first { it.config.getOrNull(SemanticsActions.SetText) != null }.config[SemanticsActions.SetText].action!!(AnnotatedString(value)); frames()
    }
    fun concealed(n: SemanticsNode): Boolean {
        var p: SemanticsNode? = n
        while (p != null) {
            if (p.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Hidden") == true) return true
            p = p.parent
        }
        return false
    }
}

class CreateDesktopAcceptanceTest {
    @Test fun resultAndNestedModalKeysCannotCaptureOrNavigate() = runSceneTest {
        var result by mutableStateOf<CreateResult?>(null)
        var current by mutableStateOf("general")
        var captures = 0
        var cancelled = 0
        val stack = ModalStack()
        var nested by mutableStateOf(false)
        val scene = ImageComposeScene(960,640,coroutineContext=coroutineContext) {
            MaterialTheme(colorScheme=ringColors) { CompositionLocalProvider(LocalModalStack provides stack, LocalReduceMotion provides true) {
                CreateWizardFrame(NewGuestKind.Vm,"New VM","node-a","140",listOf(WizardStep("general","General",0,false),WizardStep("os","OS",0,false)),current,{current=it},emptyList(),false,{},true,"Create VM",{captures++},{cancelled++},false,result,{}) {
                    TechSelect("Storage", listOf("private-store"), "private-store", {})
                }
                if (nested) TechDialog("Nested confirmation",{nested=false},dismiss=Dismiss.Confirm) { TechAction("Cancel",onClick={nested=false}) }
            } }
        }
        val h=CreateScene(scene)
        try {
            h.frames(); h.key(Key.Tab)
            assertTrue(h.nodes().any { it.config.getOrNull(SemanticsProperties.Focused) == true },"Tab must reach an interactive control")
            h.key(Key.Tab,shift=true)
            h.key(Key.PageDown,ctrl=true); assertEquals("os",current)
            h.key(Key.PageUp,ctrl=true); assertEquals("general",current)
            h.key(Key.Enter,ctrl=true); assertEquals(1,captures)
            nested=true; h.frames(); h.key(Key.Enter,ctrl=true); h.key(Key.PageDown,ctrl=true)
            assertEquals(1,captures); assertEquals("general",current)
            h.key(Key.Escape); assertFalse(nested); assertEquals(0,cancelled)
            h.click("private-store"); h.key(Key.Enter,ctrl=true); assertEquals(1,captures)
            // Enter chooses inside the popup; it never captures a create.
            h.key(Key.Escape)
            val outcomes=listOf<CreateResult>(CreateResult.Sending(NewGuestKind.Vm,"node-a",140),CreateResult.Following(NewGuestKind.Vm,"node-a",140,"task"),CreateResult.Simulated(NewGuestKind.Vm,"node-a",140),CreateResult.Rejected(NewGuestKind.Vm,"node-a",140,400,"bad"))
            for (value in outcomes) {
                result=value; h.frames()
                h.key(Key.Enter,ctrl=true); h.key(Key.PageDown,ctrl=true); h.key(Key.Two,alt=true); h.key(Key.Enter)
                assertEquals(1,captures); assertEquals("general",current)
            }
        } finally { scene.close() }
    }

    @Test fun pickerScrollsFiltersReopensAndClosesWhenDisabled() = runSceneTest {
        var selected by mutableStateOf("private-00")
        var enabled by mutableStateOf(true)
        val options=(0..79).map { "private-%02d".format(it) }
        val scene=ImageComposeScene(960,640,coroutineContext=coroutineContext) {
            MaterialTheme(colorScheme=ringColors) { TechSelect("Storage",options,selected,{selected=it},enabled=enabled) }
        }
        val h=CreateScene(scene)
        try {
            h.frames(); h.key(Key.Tab); h.key(Key.Enter)
            assertTrue(h.nodes().any { it.config.getOrNull(SemanticsActions.SetText) != null },"A tabbed picker must open with Enter")
            repeat(35) { h.key(Key.DirectionDown) }
            val row=h.text("private-35")
            assertTrue(row.boundsInWindow.top >= 0 && row.boundsInWindow.bottom <= 640)
            h.key(Key.Enter); assertEquals("private-35",selected)
            h.click(selected); h.type("79"); h.key(Key.Enter); assertEquals("private-79",selected)
            h.click(selected); assertTrue(h.nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "" })
            enabled=false; h.frames(); h.key(Key.Enter); assertEquals("private-79",selected)
            assertFalse(h.nodes().any { it.config.getOrNull(SemanticsActions.SetText) != null })
            enabled=true; h.frames(); h.click(selected); h.key(Key.DirectionUp); h.key(Key.Enter); assertEquals("private-78",selected)
        } finally { scene.close() }
    }

    @Test fun showcaseConcealsFrameDraftParametersPickerSearchAndErrors() = runSceneTest {
        val old=Appearance.showcase
        Appearance.preview(false,showcase=false)
        val scene=ImageComposeScene(1600,1000,coroutineContext=coroutineContext) {
            MaterialTheme(colorScheme=ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                CreateWizardFrame(NewGuestKind.Vm,"New VM","private-node","140",listOf(WizardStep("general","General",0,false)),"general",{},listOf(ParamLine("name","private-name")),false,{},true,"Create VM 140",{},{},false,null,{}) {
                    TechField("private-draft",{},"Name",Modifier.identity())
                    TechNumberField("2",{},"Cores",error="private-number-error")
                    TechSelect("Storage",listOf("private-store"),"private-store",{},error="private-error")
                }
            } }
        }
        val h=CreateScene(scene)
        try {
            h.frames()
            fun assertPrivateValuesAbsent() {
                val words = h.nodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                    listOfNotNull(n.config.getOrNull(SemanticsProperties.EditableText)?.text) +
                    n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                assertFalse(words.any { "private" in it }, words.toString())
                assertTrue("Hidden" in words)
            }
            assertTrue(h.nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "private-draft" })
            h.text("private-name"); h.text("private-error"); h.text("private-number-error")
            Appearance.preview(false,showcase=true); h.frames()
            assertPrivateValuesAbsent()
            Appearance.preview(false,showcase=false); h.frames()
            h.click("private-store")
            h.nodes().last { it.config.getOrNull(SemanticsActions.SetText) != null }.config[SemanticsActions.SetText].action!!(AnnotatedString("private")); h.frames()
            assertTrue(h.nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "private" })
            Appearance.preview(false,showcase=true); h.frames()
            assertPrivateValuesAbsent()
        } finally { scene.close(); Appearance.preview(false,showcase=old) }
    }

    @Test fun dirtyEscapeKeepsDraftAndConfirmationStartsOnCancel() = runSceneTest {
        var cancels=0; var creates=0
        val confirmation=CreateConfirmation(1,DEMO_REMOTE_KEY,"https://private-origin.invalid","private-profile",
            CreateRequest(NewGuestKind.Vm,"private-node",140,"private-name",listOf(CreateParam("vmid",CreateValue.Int(140)))),SecretState())
        var confirm by mutableStateOf(false)
        val scene=ImageComposeScene(960,640,coroutineContext=coroutineContext) {
            MaterialTheme(colorScheme=ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                CreateWizardFrame(NewGuestKind.Vm,"New VM","private-node","140",listOf(WizardStep("general","General",0,false)),"general",{},emptyList(),false,{},true,"Create VM",{confirm=true},{cancels++},true,null,{}) {}
                if (confirm) CreateConfirmDialog(confirmation,emptyList(),false,{creates++},{confirm=false})
            } }
        }
        val h=CreateScene(scene)
        try {
            h.frames(); h.key(Key.Escape); h.text("Discard this new vm?")
            h.key(Key.Escape); assertEquals(0,cancels)
            h.key(Key.Enter,ctrl=true); assertTrue(confirm)
            h.frames(); assertTrue(h.nodes().any { it.config.getOrNull(SemanticsProperties.Focused)==true })
            h.key(Key.Enter); assertEquals(0,creates); assertFalse(confirm)
        } finally { scene.close() }
    }

    @Test fun showcaseConcealsConfirmationAndEveryServerResultString() = runSceneTest {
        val before=Appearance.showcase
        Appearance.preview(false,showcase=false)
        val confirmation=CreateConfirmation(1,DEMO_REMOTE_KEY,"https://private-origin.invalid","private-profile",
            CreateRequest(NewGuestKind.Vm,"private-node",140,"private-name",listOf(CreateParam("vmid",CreateValue.Int(140)))),SecretState())
        val results=listOf<CreateResult>(
            CreateResult.Rejected(NewGuestKind.Vm,"private-node",140,400,"private-error",serverSaid=mapOf("field" to "private-server")),
            CreateResult.Failed(NewGuestKind.Vm,"private-node",140,"task","private-failure",listOf("private-log")),
            CreateResult.FinishedWithWarnings(NewGuestKind.Vm,"private-node",140,"task",1,listOf("private-warning"),GuestAppearance.Appeared),
            CreateResult.Unknown(NewGuestKind.Vm,"private-node",140,null,"private-unknown",IdCheck.Taken(140,"private-taken")))
        try {
            for (result in listOf<CreateResult?>(null)+results) {
                val scene=ImageComposeScene(960,640,coroutineContext=coroutineContext) {
                    MaterialTheme(colorScheme=ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                        if (result==null) CreateConfirmDialog(confirmation,listOf(ParamLine("net0","private-bridge")),false,{},{},listOf("private-hint"))
                        else Column { CreateResultView(result) {} }
                    } }
                }
                val h=CreateScene(scene)
                try {
                    h.frames()
                    val sensitive=h.nodes().filter { it.config.getOrNull(SemanticsProperties.Text)?.any { t->t.text.contains("private-") }==true }
                    assertTrue(sensitive.isNotEmpty())
                    Appearance.preview(false,showcase=true); h.frames()
                    val words=h.nodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                    assertFalse(words.any { "private-" in it }, words.toString())
                    assertTrue("Hidden" in words)
                    Appearance.preview(false,showcase=false)
                } finally { scene.close() }
            }
        } finally { Appearance.preview(false,showcase=before) }
    }

    @Test fun tabVisitsFieldsAndFooterAndStepMotionKeepsFocus() = runSceneTest {
        var current by mutableStateOf("general")
        val scene=ImageComposeScene(1600,1000,coroutineContext=coroutineContext) {
            MaterialTheme(colorScheme=ringColors) {
                CreateWizardFrame(NewGuestKind.Vm,"New VM","node-a","140",listOf(WizardStep("general","General",0,false),WizardStep("os","OS",0,false),WizardStep("cpu","CPU",0,false)),current,{current=it},emptyList(),false,{},true,"Create VM",{},{},false,null,{}) {
                    TechField("first",{},"First")
                    TechField("second",{},"Second")
                }
            }
        }
        val h=CreateScene(scene)
        try {
            h.frames()
            val visited=mutableSetOf<String>()
            repeat(22) {
                h.key(Key.Tab)
                fun collect(n: SemanticsNode) {
                    visited+=n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
                    n.config.getOrNull(SemanticsProperties.EditableText)?.text?.let { visited+=it }
                    n.children.forEach(::collect)
                }
                h.nodes().filter { it.config.getOrNull(SemanticsProperties.Focused)==true }.forEach(::collect)
            }
            assertTrue(visited.containsAll(listOf("first","second","NEXT","CREATE VM","CANCEL")),visited.toString())
            var button: SemanticsNode?=h.text("CREATE VM")
            while (button!=null && button.config.getOrNull(SemanticsActions.RequestFocus)==null) button=button.parent
            val target=assertNotNull(button)
            assertNotNull(target.config[SemanticsActions.RequestFocus].action).invoke();h.frames()
            val before=h.text("CREATE VM").boundsInWindow
            h.key(Key.PageDown,ctrl=true)
            assertEquals("os",current)
            assertEquals(before,h.text("CREATE VM").boundsInWindow)
            assertTrue(h.nodes().any { it.id==target.id && it.config.getOrNull(SemanticsProperties.Focused)==true })
        } finally { scene.close() }
    }

    @Test fun preferenceStoreSharesAdvancedWithoutTouchingUserSettings() {
        val dir=Files.createTempDirectory("create-prefs")
        try {
            val file=dir.resolve("motion.txt")
            val vm=CreateWizardPreferences.stored(MotionPreferences(file))
            assertFalse(vm.advanced); vm.advanced=true
            val ct=CreateWizardPreferences.stored(MotionPreferences(file))
            assertTrue(ct.advanced); ct.advanced=false; assertFalse(vm.advanced)
            assertFalse(CreateWizardPreferences.memory().advanced)
        } finally { dir.toFile().deleteRecursively() }
    }
}
