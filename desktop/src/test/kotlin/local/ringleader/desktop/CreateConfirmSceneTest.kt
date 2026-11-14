@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.yield
import local.ringleader.core.*
import kotlin.test.*

class CreateConfirmSceneTest {
    @Test fun confirmationUsesOnlySecretState() = runSceneTest {
        val confirmation = CreateConfirmation(1,DEMO_REMOTE_KEY,"https://example.invalid","Sample connection",
            CreateRequest(NewGuestKind.Container,"node-a",140,"sample-ct",listOf(CreateParam("vmid",CreateValue.Int(140)))), SecretState(true,2))
        val scene = ImageComposeScene(960,640,coroutineContext=coroutineContext) {
            MaterialTheme(colorScheme=ringColors) { CreateConfirmDialog(confirmation,emptyList(),true,{},{}) }
        }
        try {
            repeat(5) { scene.render((it+1)*16_000_000L).close(); yield() }
            val texts = mutableListOf<String>()
            fun visit(n: SemanticsNode) { texts += n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }; n.children.forEach(::visit) }
            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
            val text = texts.joinToString(" ")
            assertTrue(text.contains("Root password: set")); assertTrue(text.contains("SSH keys: 2"))
            assertTrue(text.contains("example.invalid")); assertTrue(text.contains("sample-ct"))
            assertTrue(text.contains("SIMULATE CREATE"))
        } finally { scene.close() }
    }
}
