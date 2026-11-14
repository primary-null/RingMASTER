@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.yield
import local.ringleader.core.*
import kotlin.test.*

class CreateWizardKeysTest {
    @Test fun enterNavigatesAndEscapeNeverCreates() = runSceneTest {
        var current by mutableStateOf("general")
        var creates = 0
        var cancels = 0
        val scene = ImageComposeScene(960,640,coroutineContext=coroutineContext) {
            MaterialTheme(colorScheme=ringColors) {
                CreateWizardFrame(NewGuestKind.Vm,"New VM","node-a","140",
                    listOf(WizardStep("general","General",0,false),WizardStep("os","OS",0,false)),current,{current=it},
                    emptyList(),false,{},true,"Create VM",{creates++},{cancels++},false,null,{}) {}
            }
        }
        suspend fun frames() { repeat(4) { scene.render(System.nanoTime()).close(); yield() } }
        suspend fun key(k: Key) { scene.sendKeyEvent(KeyEvent(k,KeyEventType.KeyDown)); scene.sendKeyEvent(KeyEvent(k,KeyEventType.KeyUp)); frames() }
        try {
            frames(); key(Key.Enter); assertEquals("os",current)
            key(Key.Escape); assertEquals(1,cancels); assertEquals(0,creates)
        } finally { scene.close() }
    }
}
